package com.axelor.apps.aiaccountant.client;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.resilience.AiResiliencePolicy;
import com.axelor.apps.aiaccountant.resilience.AiTurnDeadline;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAuth2 client-credentials token provider (development default).
 *
 * <p>Hand-rolled on the JDK {@code java.net.http.HttpClient}, consistent with {@code
 * AzureDocumentIntelligenceExtractor} — the Azure SDK is deliberately avoided so
 * azure-core/Reactor/ Netty/msal4j never enter the Axelor runtime.
 *
 * <p>The token is cached until shortly before it expires. Neither the token nor the client secret
 * is ever logged.
 */
@Singleton
public class ClientSecretTokenProvider implements AiTokenProvider {

  private static final Logger LOG = LoggerFactory.getLogger(ClientSecretTokenProvider.class);

  /** Refresh this long before the stated expiry, so a call never races the boundary. */
  private static final long REFRESH_SKEW_MS = 300_000L;

  private final AiAccountantConfig config;
  private final ObjectMapper mapper = new ObjectMapper();

  private String cachedToken;
  private long expiresAtMs;

  @Inject
  public ClientSecretTokenProvider(AiAccountantConfig config) {
    this.config = config;
  }

  @Override
  public synchronized String bearerToken() {
    if (cachedToken != null && monotonicMillis() < expiresAtMs - REFRESH_SKEW_MS) {
      return cachedToken;
    }
    String tenant = config.getTenantId();
    String clientId = config.getClientId();
    String secret = config.getClientSecret();
    if (tenant.isEmpty() || clientId.isEmpty() || secret.isEmpty()) {
      throw new AiAccountantException(
          "AI Accountant: Entra client credentials are not configured (need tenant id, client id and"
              + " client secret).");
    }

    String url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token";
    String form =
        "grant_type=client_credentials"
            + "&client_id="
            + enc(clientId)
            + "&client_secret="
            + enc(secret)
            + "&scope="
            + enc(AiAccountantConfig.SCOPE);

    TokenResponse resp;
    try {
      resp = httpPost(url, form);
    } catch (IOException e) {
      throw new AiAccountantException("Cannot reach Entra token endpoint: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiAccountantException("Interrupted while acquiring an Entra token.", e);
    }

    if (resp.status != 200) {
      // Body may echo the client id but never the secret; keep it short and non-sensitive.
      throw new AiAccountantException(
          "Entra token request failed (HTTP " + resp.status + "): " + errorCode(resp.body));
    }

    try {
      JsonNode node = mapper.readTree(resp.body);
      String token = node.path("access_token").asText(null);
      long expiresIn = node.path("expires_in").asLong(3600L);
      if (token == null || token.isEmpty()) {
        throw new AiAccountantException("Entra token response contained no access_token.");
      }
      cachedToken = token;
      expiresAtMs = monotonicMillis() + expiresIn * 1000L;
      LOG.info("AI Accountant: acquired Entra token, valid for {}s.", expiresIn);
      return cachedToken;
    } catch (IOException e) {
      throw new AiAccountantException("Malformed Entra token response.", e);
    }
  }

  /** Extracts just the error code, so nothing sensitive is logged or surfaced. */
  private String errorCode(String body) {
    try {
      return mapper.readTree(body).path("error").asText("unknown_error");
    } catch (Exception e) {
      return "unparseable_error";
    }
  }

  private static String enc(String v) {
    try {
      return URLEncoder.encode(v, StandardCharsets.UTF_8.name());
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e); // UTF-8 always present
    }
  }

  /** Minimal transport result, so the HTTP call can be stubbed in tests. */
  protected static final class TokenResponse {
    final int status;
    final String body;

    TokenResponse(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }

  /**
   * Asks Entra for a token, under a deadline of its own.
   *
   * <p>It used to borrow the chat's request timeout — two minutes for a call that answers in well
   * under a second or is broken. Since every model request waits on this first, an unresponsive
   * identity provider could spend the whole turn budget before a single word reached the model. The
   * policy gives it a short deadline instead, and the turn's remaining budget clips even that.
   */
  protected TokenResponse httpPost(String url, String form)
      throws IOException, InterruptedException {
    AiResiliencePolicy policy = new AiResiliencePolicy(config);
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(policy.tokenConnectTimeoutSeconds()))
            .build();
    long deadline =
        Math.max(
            1_000L,
            Math.min(
                policy.tokenRequestTimeoutSeconds() * 1000L, AiTurnDeadline.remainingMillis()));
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(deadline))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new TokenResponse(resp.statusCode(), resp.body());
  }

  protected long monotonicMillis() {
    return System.nanoTime() / 1_000_000L;
  }
}
