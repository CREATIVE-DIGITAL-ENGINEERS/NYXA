package com.axelor.apps.nyxasecurity.directory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link TenantUserDirectory} backed by the {@code client-users} endpoint of weAccount.Keycloak.
 *
 * <p>Authenticates with the client-credentials grant as this tenant's own Keycloak client, whose
 * service account holds no admin role: the endpoint derives the tenant from the token and refuses
 * anything outside it. Hand-rolled on the JDK {@code HttpClient}, like the other outbound clients
 * in this code base, so no Keycloak or OAuth library enters the Axelor runtime.
 *
 * <p>The access token is cached until shortly before it expires. Neither it nor the client secret
 * is ever logged; email addresses are logged at DEBUG only.
 */
@Singleton
public class KeycloakTenantUserDirectory implements TenantUserDirectory {

  private static final Logger LOG = LoggerFactory.getLogger(KeycloakTenantUserDirectory.class);

  /** Refresh this long before the stated expiry, so a call never races the boundary. */
  private static final long REFRESH_SKEW_MS = 30_000L;

  /** The endpoint's own cap on one page. */
  static final int PAGE_SIZE = 500;

  /** Keycloak user ids are UUIDs; anything else must not reach a URL path. */
  private static final Pattern ID = Pattern.compile("^[A-Za-z0-9-]{1,64}$");

  private final DirectorySettings settings;
  private final ObjectMapper mapper = new ObjectMapper();

  /** Upper bound on one connection attempt; see {@link #client()}. */
  static final int CONNECT_TIMEOUT_SECONDS = 5;

  private HttpClient http;

  private String cachedToken;
  private long expiresAtMs;

  @Inject
  public KeycloakTenantUserDirectory() {
    this(DirectorySettings.fromAppSettings());
  }

  KeycloakTenantUserDirectory(DirectorySettings settings) {
    this.settings = settings;
  }

  @Override
  public List<DirectoryUser> list() {
    List<DirectoryUser> users = new ArrayList<>();
    int first = 0;
    while (true) {
      JsonNode page = call("GET", "/users?first=" + first + "&max=" + PAGE_SIZE, null);
      JsonNode items = page.path("users");
      for (JsonNode item : items) {
        users.add(toUser(item));
      }
      int total = page.path("total").asInt(users.size());
      if (items.size() == 0 || users.size() >= total) {
        return users;
      }
      first += items.size();
    }
  }

  @Override
  public DirectoryUser get(String id) {
    return toUser(call("GET", "/users/" + checkId(id), null));
  }

  @Override
  public InviteResult invite(String email, Set<String> roles) {
    ObjectNode body = mapper.createObjectNode();
    body.put("email", email);
    body.set("roles", array(roles));
    JsonNode result = call("POST", "/users", body);
    LOG.debug("Directory invite of {} answered created={}", email, result.path("created"));
    return new InviteResult(
        toUser(result.path("user")),
        result.path("created").asBoolean(false),
        strings(result.path("rolesAdded")));
  }

  @Override
  public DirectoryUser setRoles(String id, Set<String> roles) {
    ObjectNode body = mapper.createObjectNode();
    body.set("roles", array(roles));
    return toUser(call("PUT", "/users/" + checkId(id) + "/roles", body));
  }

  @Override
  public void revoke(String id) {
    call("DELETE", "/users/" + checkId(id), null);
  }

  // ---------------------------------------------------------------------------------------------
  // Transport

  /** One call to the endpoint. A 401 drops the cached token and retries once with a fresh one. */
  private JsonNode call(String method, String path, JsonNode body) {
    String missing = settings.missing();
    if (missing != null) {
      throw new DirectoryException(
          "The user directory is not configured: " + missing + " is missing.", null);
    }
    String json = body == null ? null : body.toString();
    Response resp = send(method, settings.getDirectoryUrl() + path, bearer(false), json);
    if (resp.status == 401) {
      resp = send(method, settings.getDirectoryUrl() + path, bearer(true), json);
    }
    if (resp.status >= 200 && resp.status < 300) {
      return parse(resp.body);
    }
    JsonNode error = parse(resp.body);
    String code = error.path("error").asText("http_" + resp.status);
    String message = error.path("message").asText("");
    LOG.info("Directory refused {} {}: HTTP {} {}", method, pathOnly(path), resp.status, code);
    if (resp.status == 401 || resp.status >= 500) {
      throw new DirectoryException(
          resp.status,
          code,
          "The user directory is unavailable (HTTP " + resp.status + "). Try again later.");
    }
    throw new DirectoryException(
        resp.status, code, message.isEmpty() ? "Refused by the user directory: " + code : message);
  }

  private synchronized String bearer(boolean refresh) {
    if (!refresh && cachedToken != null && monotonicMillis() < expiresAtMs - REFRESH_SKEW_MS) {
      return cachedToken;
    }
    String form =
        "grant_type=client_credentials&client_id="
            + enc(settings.getClientId())
            + "&client_secret="
            + enc(settings.getClientSecret());
    Response resp = sendForm(settings.getTokenUrl(), form);
    if (resp.status != 200) {
      // Keycloak's body names an error code, never the secret; keep only that.
      throw new DirectoryException(
          resp.status,
          DirectoryException.UNAVAILABLE,
          "Could not authenticate to the user directory (HTTP "
              + resp.status
              + ", "
              + parse(resp.body).path("error").asText("unknown_error")
              + "). Check that this application's client in the identity provider has its service account enabled.");
    }
    JsonNode node = parse(resp.body);
    String token = node.path("access_token").asText("");
    if (token.isEmpty()) {
      throw new DirectoryException("The token response contained no access_token.", null);
    }
    cachedToken = token;
    expiresAtMs = monotonicMillis() + node.path("expires_in").asLong(60L) * 1000L;
    return cachedToken;
  }

  private JsonNode parse(String body) {
    if (body == null || body.isBlank()) {
      return mapper.createObjectNode();
    }
    try {
      return mapper.readTree(body);
    } catch (IOException e) {
      return mapper.createObjectNode();
    }
  }

  /** Minimal transport result, so the HTTP calls can be stubbed in tests. */
  protected static final class Response {
    final int status;
    final String body;

    Response(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }

  protected Response send(String method, String url, String token, String json) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
            .header("Authorization", "Bearer " + token)
            .header("Accept", "application/json");
    if (json != null) {
      request
          .header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    } else {
      request.method(method, HttpRequest.BodyPublishers.noBody());
    }
    return execute(request.build());
  }

  protected Response sendForm(String url, String form) {
    return execute(
        HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(settings.getTimeoutSeconds()))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
            .build());
  }

  /**
   * Sends one request. A connection that isn't established in time is tried once more with a fresh
   * one: the request was never sent, so this is safe for every call, invitations included. Seen
   * against the identity provider behind Azure Front Door, where an occasional TCP connect is lost
   * and retried by the network stack for seconds, while the next attempt connects in milliseconds.
   */
  private Response execute(HttpRequest request) {
    try {
      try {
        return exchange(request);
      } catch (HttpConnectTimeoutException first) {
        LOG.warn("Identity provider connection timed out; trying once more.");
        return exchange(request);
      }
    } catch (IOException e) {
      LOG.warn("Identity provider unreachable: {}", e.toString());
      throw new DirectoryException("The identity provider could not be reached. Try again.", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DirectoryException("Interrupted while calling the user directory.", e);
    }
  }

  /** The transport itself, so tests can simulate a lost connection. */
  protected Response exchange(HttpRequest request) throws IOException, InterruptedException {
    HttpResponse<String> resp =
        client().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new Response(resp.statusCode(), resp.body());
  }

  /**
   * One client for all calls, so connections are kept alive and reused instead of a new TCP and TLS
   * handshake per call. The connect limit is short, at most {@link #CONNECT_TIMEOUT_SECONDS}: a
   * connect that slow has most likely lost its packets, and a fresh attempt is faster than waiting.
   */
  private synchronized HttpClient client() {
    if (http == null) {
      http =
          HttpClient.newBuilder()
              .version(HttpClient.Version.HTTP_1_1)
              .connectTimeout(
                  Duration.ofSeconds(
                      Math.min(CONNECT_TIMEOUT_SECONDS, settings.getTimeoutSeconds())))
              .build();
    }
    return http;
  }

  protected long monotonicMillis() {
    return System.nanoTime() / 1_000_000L;
  }

  // ---------------------------------------------------------------------------------------------
  // Mapping

  private DirectoryUser toUser(JsonNode node) {
    return new DirectoryUser(
        node.path("id").asText(null),
        node.path("email").asText(null),
        node.path("firstName").asText(null),
        node.path("lastName").asText(null),
        node.path("enabled").asBoolean(true),
        node.path("hasLoggedIn").asBoolean(false),
        strings(node.path("roles")),
        node.path("managedByNyxa").asBoolean(false),
        node.path("owner").asBoolean(false));
  }

  private static Set<String> strings(JsonNode node) {
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode item : node) {
      values.add(item.asText());
    }
    return values;
  }

  private ArrayNode array(Set<String> values) {
    ArrayNode array = mapper.createArrayNode();
    if (values != null) {
      values.forEach(array::add);
    }
    return array;
  }

  private static String checkId(String id) {
    if (id == null || !ID.matcher(id).matches()) {
      throw new DirectoryException(400, "invalid_id", "Not a directory user id: " + id);
    }
    return id;
  }

  private static String pathOnly(String path) {
    int query = path.indexOf('?');
    return query < 0 ? path : path.substring(0, query);
  }

  private static String enc(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
