package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.napsubmissions.common.config.NapSecretProvider;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsApiException.Category;
import com.axelor.studio.db.AppNapSubmissions;
import com.axelor.studio.db.repo.AppNapSubmissionsRepository;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Real НАП Declarations API transport client (submit + result). JDK {@link HttpClient} (HTTP/1.1) +
 * Jackson, matching the project's OCR client. Pure transport: it (de)serializes the official DTOs,
 * sends the Bearer API key, applies timeouts and maps errors — it embeds NO D1/D6/ETZ business
 * rules.
 *
 * <p>Configuration (environment, endpoints, enabled flag) is read from {@link AppNapSubmissions} at
 * call time. The API key is not a setting: it is always the environment variable {@value
 * #API_KEY_SECRET}, resolved through {@link NapSecretProvider}. If anything is missing the call
 * fails with {@link Category#CONFIG} — startup is never affected. The API key and {@code
 * Authorization} header are never logged or placed in exceptions.
 *
 * <p>⚠️ There is no mock endpoint any more (removed 2026-10-30). Manual testing goes against НАП's
 * OWN official TEST service ({@value #TEST_SUBMIT}), so the only endpoints this transport can reach
 * are the four official ones below, optionally narrowed by the institution-gateway override on
 * {@link AppNapSubmissions}. Offline tests use an in-memory fake {@link DeclarationsApiClient}, not
 * a mock server.
 */
@Singleton
public class NraDeclarationsApiClient implements DeclarationsApiClient {

  private static final Logger LOG = LoggerFactory.getLogger(NraDeclarationsApiClient.class);

  /**
   * The environment variable with the НАП Declarations API key (Bearer). Its value is never logged.
   */
  public static final String API_KEY_SECRET = "NYXA_NAP_API_KEY";

  // Official endpoints (host public-api.nra.bg, basePath /declaration), verified against the НАП
  // package in the official NAP API specification: "NRA
  // API/ProdEnvironment/EndPointAPIService_Declarations.txt" and
  // "NRA API/TestEnvironment/EndPointAPIService_Declarations-Test.txt".
  static final String PROD_SUBMIT = "https://public-api.nra.bg/declaration/api-declarations/";
  static final String PROD_RESULT = "https://public-api.nra.bg/declaration/api-declarations/result";
  static final String TEST_SUBMIT = "https://public-api.nra.bg/declaration/api-declarations-test/";
  static final String TEST_RESULT =
      "https://public-api.nra.bg/declaration/api-declarations-test/result";

  private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
  // NAP verifies the signatures synchronously inside the submit call; on the test environment that
  // took longer than 60 s. The signing page tells the user it waits up to this long — keep in sync
  // with NAP_WAIT_SECONDS in nap-browser-signing.html.
  public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(120);
  // Response-size safeguard (declared Content-Length only): results are HTML/JSON, not huge.
  private static final long MAX_RESPONSE_BYTES = 25L * 1024 * 1024;

  protected final NapSecretProvider secretProvider;
  protected final AppNapSubmissionsRepository appRepo;
  protected final ObjectMapper mapper =
      new ObjectMapper()
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
          .setSerializationInclusion(JsonInclude.Include.NON_NULL);

  @Inject
  public NraDeclarationsApiClient(
      NapSecretProvider secretProvider, AppNapSubmissionsRepository appRepo) {
    this.secretProvider = secretProvider;
    this.appRepo = appRepo;
  }

  @Override
  public DeclarationsSubmitResponse submit(DeclarationsSubmitRequest request) {
    ResolvedConfig cfg = resolveConfig();
    String body = serialize(request, "submit");
    HttpResponse<String> resp = send(cfg.submitUrl, cfg.apiKey, body, "submit", true);
    if (resp.statusCode() == 200) {
      return parse(resp.body(), DeclarationsSubmitResponse.class, "submit");
    }
    throw errorFor(resp, "submit");
  }

  @Override
  public DeclarationsResultResponse getResult(DeclarationsResultRequest request) {
    ResolvedConfig cfg = resolveConfig();
    String body = serialize(request, "result");
    HttpResponse<String> resp = send(cfg.resultUrl, cfg.apiKey, body, "result", false);
    if (resp.statusCode() == 200) {
      return parse(resp.body(), DeclarationsResultResponse.class, "result");
    }
    throw errorFor(resp, "result");
  }

  // --- transport -----------------------------------------------------------------------------

  protected HttpResponse<String> send(
      String url, String apiKey, String jsonBody, String operation, boolean submit) {
    HttpRequest httpRequest =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer " + apiKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
            .build();
    long started = System.nanoTime();
    try {
      HttpResponse<String> resp = client().send(httpRequest, HttpResponse.BodyHandlers.ofString());
      guardResponseSize(resp, operation);
      LOG.info(
          "NAP declarations {} -> HTTP {} in {} ms",
          operation,
          resp.statusCode(),
          elapsedMillis(started));
      return resp;
    } catch (HttpTimeoutException e) {
      // Without this line a timeout left no trace in the log — only on the submission record.
      LOG.warn(
          "NAP declarations {} timed out after {} ms (limit {} s){}",
          operation,
          elapsedMillis(started),
          REQUEST_TIMEOUT.getSeconds(),
          submit ? "; outcome unknown, reconcile before any resubmission" : "");
      // Submit: unknown whether НАП received it -> must reconcile, never blind-retry.
      throw new DeclarationsApiException(
          submit ? Category.UNKNOWN_OUTCOME : Category.TRANSPORT,
          operation,
          "Timeout calling NAP declarations " + operation,
          e);
    } catch (java.io.IOException e) {
      LOG.warn(
          "NAP declarations {} transport error after {} ms: {}",
          operation,
          elapsedMillis(started),
          e.getClass().getSimpleName());
      throw new DeclarationsApiException(
          submit ? Category.UNKNOWN_OUTCOME : Category.TRANSPORT,
          operation,
          "Transport error calling NAP declarations " + operation,
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DeclarationsApiException(
          submit ? Category.UNKNOWN_OUTCOME : Category.TRANSPORT,
          operation,
          "Interrupted calling NAP declarations " + operation,
          e);
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
  }

  protected void guardResponseSize(HttpResponse<String> resp, String operation) {
    Optional<String> len = resp.headers().firstValue("Content-Length");
    if (len.isPresent()) {
      try {
        if (Long.parseLong(len.get().trim()) > MAX_RESPONSE_BYTES) {
          throw new DeclarationsApiException(
              Category.SERVER,
              operation,
              "NAP declarations " + operation + " response exceeds size limit",
              null);
        }
      } catch (NumberFormatException ignore) {
        // non-numeric Content-Length: fall through, body already materialized by ofString
      }
    }
  }

  protected HttpClient client() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(CONNECT_TIMEOUT)
        .build();
  }

  // --- serialization -------------------------------------------------------------------------

  protected String serialize(Object dto, String operation) {
    try {
      return mapper.writeValueAsString(dto);
    } catch (Exception e) {
      throw new DeclarationsApiException(
          Category.INVALID_REQUEST, operation, "Failed to serialize " + operation + " request", e);
    }
  }

  protected <T> T parse(String body, Class<T> type, String operation) {
    try {
      return mapper.readValue(body, type);
    } catch (Exception e) {
      throw new DeclarationsApiException(
          Category.UNKNOWN, operation, "Failed to parse NAP " + operation + " response", e);
    }
  }

  // --- error mapping -------------------------------------------------------------------------

  protected DeclarationsApiException errorFor(HttpResponse<String> resp, String operation) {
    int status = resp.statusCode();
    DeclarationsApiError error = null;
    try {
      error = mapper.readValue(resp.body(), DeclarationsApiError.class);
    } catch (Exception ignore) {
      // response was not a well-formed ApiError; keep error null and map by status only
    }
    String code = error == null ? null : error.errorCode;
    Category category = categorize(status, code, operation);
    LOG.warn(
        "НАП declarations {} failed: HTTP {} code={} exceptionId={}",
        operation,
        status,
        code,
        error == null ? null : error.exceptionId);
    String msg = error != null && error.i18nMessage != null ? error.i18nMessage : "HTTP " + status;
    return new DeclarationsApiException(category, status, operation, error, msg);
  }

  protected Category categorize(int status, String errorCode, String operation) {
    if ("API_DUPLICATED_FILE".equals(errorCode)) {
      return Category.DUPLICATE;
    }
    if ("API_DOCUMENT_RESULT_NOT_FOUND".equals(errorCode)) {
      return Category.RESULT_NOT_READY;
    }
    switch (status) {
      case 401:
      case 403:
        return Category.AUTH;
      case 429:
        return Category.RATE_LIMIT;
      case 400:
        return Category.INVALID_REQUEST;
      case 404:
        return "result".equals(operation) ? Category.RESULT_NOT_READY : Category.INVALID_REQUEST;
      case 500:
      case 502:
      case 503:
      case 504:
        return Category.SERVER;
      default:
        return Category.UNKNOWN;
    }
  }

  // --- configuration -------------------------------------------------------------------------

  protected ResolvedConfig resolveConfig() {
    AppNapSubmissions app = appRepo.all().fetchOne();
    if (app == null) {
      throw config("AppNapSubmissions is not installed/configured");
    }
    if (!Boolean.TRUE.equals(app.getApiSubmissionEnabled())) {
      throw config("NAP API submission is disabled in settings");
    }

    // Strict environment: must be exactly test or prod — never silently fall back one way or the
    // other.
    String env = app.getEnvironmentSelect();
    boolean prod = AppNapSubmissionsRepository.ENVIRONMENT_PROD.equals(env);
    boolean test = AppNapSubmissionsRepository.ENVIRONMENT_TEST.equals(env);
    if (!prod && !test) {
      throw config("NAP environment must be explicitly TEST or PRODUCTION (got: " + env + ")");
    }

    // The official endpoints are the defaults; the institution-gateway override applies ONLY when
    // explicitly enabled, and is then held to the TEST/PROD rules in resolveEndpoints.
    Endpoints endpoints =
        resolveEndpoints(
            prod,
            Boolean.TRUE.equals(app.getAllowEndpointOverride()),
            app.getSubmitEndpointOverride(),
            app.getResultEndpointOverride());
    String submitUrl = endpoints.submitUrl;
    String resultUrl = endpoints.resultUrl;

    // The key comes ONLY from the environment (or a JVM property of the same name), never from a
    // setting: one fixed name, so nothing in the database can point the client at another secret.
    String apiKey =
        secretProvider
            .resolveSecret(API_KEY_SECRET)
            .filter(k -> !k.isBlank())
            .orElseThrow(
                () ->
                    config(
                        "NAP API key is not set: the environment variable "
                            + API_KEY_SECRET
                            + " is missing or empty"));

    // Safe to log: environment + host only. NEVER the API key or payloads.
    LOG.info("НАП declarations config: env={} submitHost={}", env, hostOf(submitUrl));
    return new ResolvedConfig(submitUrl, resultUrl, apiKey);
  }

  /**
   * Pure endpoint resolution, kept side-effect-free so the TEST / PROD separation can be tested
   * without a container.
   *
   * <p>The defaults are the four official НАП endpoints: TEST resolves to НАП's own TEST service
   * and PROD to the production one. The only way to depart from them is the institution-gateway
   * override on {@link AppNapSubmissions}, which is off by default and is still held to the rules
   * below. There is no mock endpoint.
   *
   * <p><b>⚠️ The separation is enforced on the FULL URL, not the hostname.</b> НАП serves TEST and
   * PROD from the SAME host ({@code public-api.nra.bg}) and distinguishes them only by path ({@code
   * /declaration/api-declarations-test/} vs {@code /declaration/api-declarations/}), so a host-only
   * check cannot tell them apart. Both directions are therefore closed on the resolved endpoints:
   *
   * <p>The two directions are checked by deliberately DIFFERENT rules, because they protect against
   * opposite mistakes:
   *
   * <ul>
   *   <li><b>PROD — strict allow-list</b> ({@link #isExactOfficialProdUrl}): each resolved URL must
   *       be the exact official HTTPS endpoint for its own role. Another scheme, an explicit
   *       non-default port, a query, a fragment, user-info, the TEST path, or submit and result
   *       swapped round are all refused rather than normalised away.
   *   <li><b>TEST — broad deny-list</b> ({@link #reachesOfficialProdPath}): refused whenever a
   *       resolved URL would hit a PRODUCTION path on {@value #OFFICIAL_HOST}, by ANY scheme, port,
   *       query or fragment — so TEST cannot reach production by dropping to {@code http} or adding
   *       a port.
   * </ul>
   */
  protected static Endpoints resolveEndpoints(
      boolean prod,
      boolean allowEndpointOverride,
      String dbSubmitOverride,
      String dbResultOverride) {
    String submitUrl = prod ? PROD_SUBMIT : TEST_SUBMIT;
    String resultUrl = prod ? PROD_RESULT : TEST_RESULT;
    if (allowEndpointOverride) {
      submitUrl = firstNonBlank(dbSubmitOverride, submitUrl);
      resultUrl = firstNonBlank(dbResultOverride, resultUrl);
    }
    if (prod) {
      // PROD: ONLY the exact official HTTPS endpoints, each in its own role.
      if (!isExactOfficialProdUrl(submitUrl, PROD_SUBMIT)
          || !isExactOfficialProdUrl(resultUrl, PROD_RESULT)) {
        throw new DeclarationsApiException(
            Category.CONFIG,
            0,
            "config",
            null,
            "In PRODUCTION only the exact official HTTPS NAP endpoints are allowed, each in its own"
                + " role (submit and result may not be swapped) and with no other scheme, host,"
                + " port, query or fragment. Submission refused.");
      }
    } else if (reachesOfficialProdPath(submitUrl) || reachesOfficialProdPath(resultUrl)) {
      // TEST: never reach a PRODUCTION path on the official host, by any scheme/port/query.
      throw new DeclarationsApiException(
          Category.CONFIG,
          0,
          "config",
          null,
          "The TEST environment resolved to a NAP PRODUCTION path on "
              + OFFICIAL_HOST
              + "; a TEST configuration may never submit to PRODUCTION. Submission refused.");
    }
    return new Endpoints(submitUrl, resultUrl);
  }

  /** The single host НАП serves both environments from; they differ only by path. */
  static final String OFFICIAL_HOST = "public-api.nra.bg";

  /**
   * PROD acceptance, deliberately STRICT: {@code url} must be the official endpoint for exactly
   * this role — {@code https}, the official host, no explicit non-default port, no user-info, no
   * query and no fragment, and the same path (a trailing slash is the only tolerated difference).
   *
   * <p>Anything else is refused rather than normalised, so {@code http://}, {@code :8443}, {@code
   * ?debug=1}, {@code #x}, the TEST path, or submit/result swapped round can never be accepted as a
   * PRODUCTION endpoint.
   */
  protected static boolean isExactOfficialProdUrl(String url, String officialUrl) {
    URI uri = uriOf(url);
    URI official = uriOf(officialUrl);
    if (uri == null || official == null) {
      return false;
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      return false;
    }
    if (!OFFICIAL_HOST.equalsIgnoreCase(uri.getHost())) {
      return false;
    }
    // -1 = not specified; 443 is the default HTTPS port and therefore the same endpoint.
    if (uri.getPort() != -1 && uri.getPort() != 443) {
      return false;
    }
    if (uri.getRawQuery() != null || uri.getRawFragment() != null || uri.getRawUserInfo() != null) {
      return false;
    }
    return normalizedPath(uri).equals(normalizedPath(official));
  }

  /**
   * TEST refusal, deliberately BROAD: true when {@code url} would hit one of the two PRODUCTION
   * paths on the official НАП host, whatever the scheme, port, query or fragment. A TEST
   * configuration must not reach production by dropping to {@code http}, adding a port or
   * decorating the URL.
   *
   * <p>It keys on host + path, so an institution gateway on its OWN host stays usable in TEST even
   * if its path happens to be production-shaped.
   */
  protected static boolean reachesOfficialProdPath(String url) {
    URI uri = uriOf(url);
    if (uri == null || !OFFICIAL_HOST.equalsIgnoreCase(uri.getHost())) {
      return false;
    }
    String path = normalizedPath(uri);
    return path.equals(normalizedPath(uriOf(PROD_SUBMIT)))
        || path.equals(normalizedPath(uriOf(PROD_RESULT)));
  }

  /** Parsed URI, or {@code null} when blank/unparseable (which never matches an official URL). */
  private static URI uriOf(String url) {
    if (url == null || url.isBlank()) {
      return null;
    }
    try {
      return URI.create(url.trim());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Lower-case path with trailing slashes removed; {@code ""} when there is none. */
  private static String normalizedPath(URI uri) {
    if (uri == null || uri.getPath() == null) {
      return "";
    }
    String path = uri.getPath();
    while (path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path.toLowerCase();
  }

  /** A resolved (submit, result) endpoint pair. */
  protected static final class Endpoints {
    final String submitUrl;
    final String resultUrl;

    Endpoints(String submitUrl, String resultUrl) {
      this.submitUrl = submitUrl;
      this.resultUrl = resultUrl;
    }
  }

  protected static String hostOf(String url) {
    try {
      return URI.create(url).getHost();
    } catch (RuntimeException e) {
      return "?";
    }
  }

  protected DeclarationsApiException config(String message) {
    return new DeclarationsApiException(Category.CONFIG, 0, "config", null, message);
  }

  protected static String firstNonBlank(String override, String fallback) {
    return override != null && !override.isBlank() ? override : fallback;
  }

  /**
   * Resolved, ready-to-use transport config. The apiKey is held only transiently and never logged.
   */
  protected static class ResolvedConfig {
    final String submitUrl;
    final String resultUrl;
    final String apiKey;

    ResolvedConfig(String submitUrl, String resultUrl, String apiKey) {
      this.submitUrl = submitUrl;
      this.resultUrl = resultUrl;
      this.apiKey = apiKey;
    }
  }
}
