package com.axelor.apps.aiaccountant.client;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.axelor.apps.aiaccountant.metrics.AiRequestMetrics;
import com.axelor.apps.aiaccountant.metrics.AiTurnMetrics;
import com.axelor.apps.aiaccountant.resilience.AiFailureClassifier;
import com.axelor.apps.aiaccountant.resilience.AiFailureKind;
import com.axelor.apps.aiaccountant.resilience.AiResiliencePolicy;
import com.axelor.apps.aiaccountant.resilience.AiTurnDeadline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * REST implementation over the JDK {@code java.net.http.HttpClient} + Jackson.
 *
 * <p>No Azure SDK: consistent with {@code AzureDocumentIntelligenceExtractor}, which documents the
 * decision to keep azure-core/Reactor/Netty out of the Axelor runtime.
 *
 * <p>Retries only transient failures — a refused connection, a 429, a 5xx — with exponential
 * backoff, jitter and {@code Retry-After}, all bounded by {@link AiResiliencePolicy} and clipped to
 * what is left of the turn. A <em>timeout</em> is deliberately not retried: the request already
 * consumed a full deadline, and repeating it spends another one to learn the same thing.
 *
 * <p>Every deadline and every attempt count here comes from the policy rather than from this class,
 * so the ceiling on what a turn can spend is visible in one place.
 */
@Singleton
public class FoundryResponsesRestClient implements FoundryResponsesClient {

  private static final Logger LOG = LoggerFactory.getLogger(FoundryResponsesRestClient.class);

  private final AiAccountantConfig config;
  private final AiTokenProvider tokenProvider;
  private final AiResiliencePolicy policy;
  private final ObjectMapper mapper = new ObjectMapper();

  @Inject
  public FoundryResponsesRestClient(
      AiAccountantConfig config, AiTokenProvider tokenProvider, AiResiliencePolicy policy) {
    this.config = config;
    this.tokenProvider = tokenProvider;
    this.policy = policy;
  }

  /** For callers that construct the client directly; the policy reads the same configuration. */
  public FoundryResponsesRestClient(AiAccountantConfig config, AiTokenProvider tokenProvider) {
    this(config, tokenProvider, new AiResiliencePolicy(config));
  }

  @Override
  public FoundryResponse createResponse(String previousResponseId, JsonNode input) {
    String url = config.chatUrl();
    if (url.isEmpty()) {
      throw new AiAccountantException(
          "AI Accountant: the Foundry agent endpoint is not configured ("
              + AiAccountantConfig.ENV_AGENT_ENDPOINT
              + ").");
    }

    ObjectNode body = mapper.createObjectNode();
    body.set("input", input);
    boolean chained = previousResponseId != null && !previousResponseId.trim().isEmpty();
    if (chained) {
      body.put("previous_response_id", previousResponseId.trim());
    }

    // Measured here, at the one place every caller must pass through. A turn-level metric can only
    // see requests that opened a turn, and the background OCR passes never do — see
    // AiRequestMetrics for why that mattered.
    long started = System.nanoTime();
    HttpResult res;
    try {
      res = postWithRetry(url, body.toString());
    } catch (RuntimeException e) {
      // Classified rather than labelled "transport_error" for everything: a turn that ended in a
      // timeout and one that never reached the service are different operational problems, and the
      // metric was previously unable to tell them apart.
      AiRequestMetrics.failure(
          elapsedMillis(started),
          chained,
          "transport_" + AiFailureClassifier.classify(e).wire(),
          AiRequestMetrics.AGENT_MAIN);
      throw e;
    }

    if (res.status == 401 || res.status == 403) {
      AiRequestMetrics.failure(
          elapsedMillis(started), chained, "http_" + res.status, AiRequestMetrics.AGENT_MAIN);
      throw new AiAccountantException(
          "Foundry rejected the credentials (HTTP "
              + res.status
              + "). Check the Entra identity's RBAC on the agent.");
    }
    if (res.status != 200) {
      AiRequestMetrics.failure(
          elapsedMillis(started), chained, "http_" + res.status, AiRequestMetrics.AGENT_MAIN);
      throw new AiAccountantException(
          "Foundry returned HTTP " + res.status + ": " + shortError(res.body));
    }
    try {
      FoundryResponse response = new FoundryResponse(mapper.readTree(res.body));
      AiRequestMetrics.success(
          elapsedMillis(started), response, chained, AiRequestMetrics.AGENT_MAIN);
      return response;
    } catch (IOException e) {
      AiRequestMetrics.failure(
          elapsedMillis(started), chained, "malformed", AiRequestMetrics.AGENT_MAIN);
      throw new AiAccountantException("Malformed Foundry response.", e);
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000L;
  }

  /**
   * Sends the request, retrying only what the policy says may be retried.
   *
   * <h2>What changed, and why each part of it</h2>
   *
   * <ul>
   *   <li><b>A timeout is no longer retried.</b> It used to be, on the reasoning that a model call
   *       writes nothing so repeating it is free. It is not free: the request that timed out
   *       already consumed a full request deadline, and repeating it spends another one to learn
   *       the same thing — two minutes of a person's attention, twice, for a service that is
   *       evidently not answering. Only {@link AiFailureKind#TRANSIENT} is retried: a refused
   *       connection, a 429, a 503, all of which are the service saying "not now" rather than "not
   *       yet finished".
   *   <li><b>The attempt count is bounded by the policy</b> (three, not five) rather than by this
   *       class's own arithmetic.
   *   <li><b>Every wait is clipped to what is left of the turn.</b> The old curve could sleep
   *       thirty seconds, or honour a {@code Retry-After} of any size, inside a turn nobody had
   *       bounded. A backoff that outlasts the turn buys an attempt that will never be made.
   *   <li><b>No attempt is started without time to finish it.</b> Checked before each one, so a
   *       turn near its deadline fails quickly and truthfully instead of hanging on a call whose
   *       answer would be discarded.
   * </ul>
   */
  private HttpResult postWithRetry(String url, String body) {
    int attempts = policy().maxModelAttempts();
    AiAccountantException last = null;
    for (int i = 1; i <= attempts; i++) {
      AiFailureKind kind;
      try {
        HttpResult res = httpPost(url, body, tokenProvider.bearerToken());
        kind = AiFailureClassifier.ofHttpStatus(res.status);
        if (kind == null) {
          return res;
        }
        last = new AiAccountantException("Foundry answered HTTP " + res.status + ".");
        if (!kind.isRetryable() || !retryNow(i, attempts, res.retryAfter)) {
          throw last;
        }
        continue;
      } catch (java.net.http.HttpTimeoutException e) {
        // The deadline expired with the outcome unknown. Nothing in the ERP was written by it — the
        // model has no such power — so this is simply reported, never repeated.
        throw new AiAccountantException("Foundry request timed out (attempt " + i + ").", e);
      } catch (IOException e) {
        kind = AiFailureClassifier.classify(e);
        last =
            new AiAccountantException(
                "Cannot reach Foundry (attempt " + i + "): " + e.getMessage(), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AiAccountantException("Interrupted while calling Foundry.", e);
      }
      if (!kind.isRetryable() || !retryNow(i, attempts, null)) {
        throw last;
      }
    }
    throw last != null ? last : new AiAccountantException("Foundry call failed.");
  }

  /**
   * Waits for the next attempt, and says whether there should be one.
   *
   * @return false when the attempts are spent, the turn has no room left, or the wait was
   *     interrupted — in every case the caller reports the failure it already has
   */
  protected boolean retryNow(int attempt, int attempts, String retryAfter) {
    if (attempt >= attempts) {
      return false;
    }
    long remaining = AiTurnDeadline.remainingMillis();
    if (!policy().hasTimeForAnotherAttempt(remaining)) {
      log().warn("AI Accountant: not retrying Foundry — the turn's time budget is nearly spent.");
      return false;
    }
    backoff(attempt, retryAfter);
    AiTurnMetrics.modelRetried(AiFailureKind.TRANSIENT.wire());
    return true;
  }

  /** The deadlines and attempt bounds every outbound call here runs under. */
  protected AiResiliencePolicy policy() {
    return policy;
  }

  /**
   * How long this particular request may hang: the configured per-request deadline, or what is left
   * of the turn when that is less.
   *
   * <p>The clip is what stops a turn from ending in a request the caller has already given up
   * waiting for. Without it a 120-second request started with twenty seconds of budget left would
   * hold the thread for a hundred seconds after the answer stopped being wanted.
   */
  protected Duration requestDeadline() {
    long configured = policy().modelRequestTimeoutSeconds() * 1000L;
    long remaining = AiTurnDeadline.remainingMillis();
    // At least a second, so a nearly-spent budget produces a prompt timeout rather than a request
    // that is illegal to construct.
    long millis = Math.max(1_000L, Math.min(configured, remaining));
    return Duration.ofMillis(millis);
  }

  /** Extracts a short, non-sensitive message from an error body. */
  private String shortError(String body) {
    if (body == null || body.isEmpty()) {
      return "(empty body)";
    }
    try {
      JsonNode err = mapper.readTree(body).path("error");
      String msg = err.path("message").asText("");
      String code = err.path("code").asText("");
      if (!msg.isEmpty()) {
        return (code.isEmpty() ? "" : code + ": ") + msg;
      }
    } catch (Exception ignore) {
      // fall through to the truncated raw body
    }
    return body.length() > 300 ? body.substring(0, 300) + "…" : body;
  }

  /** Minimal transport result, so HTTP can be stubbed in tests. */
  protected static final class HttpResult {
    final int status;
    final String body;
    final String retryAfter;

    HttpResult(int status, String body, String retryAfter) {
      this.status = status;
      this.body = body;
      this.retryAfter = retryAfter;
    }
  }

  protected HttpResult httpPost(String url, String body, String bearer)
      throws IOException, InterruptedException {
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(policy().modelConnectTimeoutSeconds()))
            .build();
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(requestDeadline())
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new HttpResult(
        resp.statusCode(), resp.body(), resp.headers().firstValue("Retry-After").orElse(null));
  }

  /**
   * Waits before the next attempt, for as long as the policy allows and no longer than the turn has
   * left.
   *
   * <p>The curve itself moved to {@link AiResiliencePolicy}, so this client and the tool dispatcher
   * back off the same way and one class holds the ceiling. What used to be here doubled to thirty
   * seconds and honoured a {@code Retry-After} of any size, both inside a turn nothing bounded —
   * which meant a service asking for a minute got a minute of somebody's afternoon.
   *
   * <p>Kept as a protected method rather than inlined: it is the seam a test overrides to avoid
   * sleeping.
   */
  protected void backoff(int attempt, String retryAfter) {
    long millis = policy().backoffMillis(attempt, retryAfter, AiTurnDeadline.remainingMillis());
    sleep(millis);
  }

  protected void sleep(long ms) {
    if (!AiTurnDeadline.sleep(ms)) {
      throw new AiAccountantException("Interrupted while backing off.");
    }
  }

  protected Logger log() {
    return LOG;
  }
}
