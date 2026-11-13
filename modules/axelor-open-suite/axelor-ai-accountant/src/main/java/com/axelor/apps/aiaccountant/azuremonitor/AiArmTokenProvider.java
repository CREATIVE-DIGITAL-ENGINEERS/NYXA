package com.axelor.apps.aiaccountant.azuremonitor;

import com.axelor.apps.aiaccountant.client.AiAccountantException;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Acquires an Azure Resource Manager token, for reading metrics and nothing else.
 *
 * <h2>Why this is a separate class and not a parameter on the existing provider</h2>
 *
 * A token is minted for one audience. {@code AiTokenProvider.bearerToken()} takes no arguments and
 * both of its implementations bake in the Foundry data-plane audience — one via {@code
 * AiAccountantConfig.SCOPE}, the other as a literal {@code --resource} on the CLI command line.
 * Widening that interface to carry a scope would mean editing the code path every chat turn depends
 * on in order to add a reporting screen, and a mistake there would take the assistant down rather
 * than the dashboard. So the ARM path is built alongside it: the same two auth modes, the same
 * caching, the same refusal to log anything sensitive, and no shared code with the chat's token at
 * all. The chat's provider is not touched by this feature.
 *
 * <p>Credentials are reused, audiences are not: tenant, client id and client secret come from the
 * same configuration the Foundry provider reads, so nothing new has to be stored anywhere.
 *
 * <h2>No turn deadline here</h2>
 *
 * The chat's provider clips its own timeout to what is left of the current turn. This one runs on a
 * scheduled job with no turn around it, so it uses a fixed deadline instead — borrowing a
 * turn-scoped budget from a background thread would read as either zero or unbounded depending on
 * where it was called from.
 */
@Singleton
public class AiArmTokenProvider {

  private static final Logger LOG = LoggerFactory.getLogger(AiArmTokenProvider.class);

  /** Refresh this long before the stated expiry, so a call never races the boundary. */
  protected static final long REFRESH_SKEW_MS = 300_000L;

  protected static final long REQUEST_TIMEOUT_SECONDS = 20L;
  protected static final long CLI_TIMEOUT_SECONDS = 60L;

  /** az states no expiry in tsv output; re-shell every 30 min, as the chat's CLI provider does. */
  protected static final long ASSUMED_CLI_LIFETIME_MS = 1_800_000L;

  private final AiAccountantConfig config;
  private final ObjectMapper mapper = new ObjectMapper();

  private String cachedToken;
  private long expiresAtMs;

  @Inject
  public AiArmTokenProvider(AiAccountantConfig config) {
    this.config = config;
  }

  public synchronized String bearerToken() {
    if (cachedToken != null && monotonicMillis() < expiresAtMs - REFRESH_SKEW_MS) {
      return cachedToken;
    }
    if (AiAccountantConfig.AUTH_AZURE_CLI.equalsIgnoreCase(config.getAuthMode())) {
      cachedToken = viaCli();
      expiresAtMs = monotonicMillis() + ASSUMED_CLI_LIFETIME_MS;
      LOG.info("AI Accountant: acquired an ARM token via the Azure CLI (development mode).");
      return cachedToken;
    }
    Acquired acquired = viaClientSecret();
    cachedToken = acquired.token;
    expiresAtMs = monotonicMillis() + acquired.expiresInSeconds * 1000L;
    LOG.info("AI Accountant: acquired an ARM token, valid for {}s.", acquired.expiresInSeconds);
    return cachedToken;
  }

  protected Acquired viaClientSecret() {
    String tenant = config.getTenantId();
    String clientId = config.getClientId();
    String secret = config.getClientSecret();
    if (tenant.isEmpty() || clientId.isEmpty() || secret.isEmpty()) {
      throw new AiAccountantException(
          "AI Accountant: Azure Monitor needs the same Entra client credentials as the assistant"
              + " (tenant id, client id and client secret).");
    }
    String url = "https://login.microsoftonline.com/" + tenant + "/oauth2/v2.0/token";
    String form =
        "grant_type=client_credentials"
            + "&client_id="
            + enc(clientId)
            + "&client_secret="
            + enc(secret)
            + "&scope="
            + enc(AiAccountantConfig.ARM_SCOPE);

    TokenResponse resp;
    try {
      resp = httpPost(url, form);
    } catch (IOException e) {
      throw new AiAccountantException("Cannot reach Entra token endpoint: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiAccountantException("Interrupted while acquiring an ARM token.", e);
    }
    if (resp.status != 200) {
      throw new AiAccountantException(
          "ARM token request failed (HTTP " + resp.status + "): " + errorCode(resp.body));
    }
    try {
      JsonNode node = mapper.readTree(resp.body);
      String token = node.path("access_token").asText(null);
      if (token == null || token.isEmpty()) {
        throw new AiAccountantException("ARM token response contained no access_token.");
      }
      return new Acquired(token, node.path("expires_in").asLong(3600L));
    } catch (IOException e) {
      throw new AiAccountantException("Malformed ARM token response.", e);
    }
  }

  protected String viaCli() {
    List<String> cmd = new ArrayList<>();
    if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
      cmd.add("cmd.exe");
      cmd.add("/c");
    }
    cmd.add("az");
    cmd.add("account");
    cmd.add("get-access-token");
    cmd.add("--resource");
    cmd.add(AiAccountantConfig.ARM_CLI_RESOURCE);
    cmd.add("--query");
    cmd.add("accessToken");
    cmd.add("-o");
    cmd.add("tsv");

    Process p = null;
    try {
      p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
      String out;
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
        out = r.readLine();
      }
      if (!p.waitFor(CLI_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new AiAccountantException("Azure CLI timed out while acquiring an ARM token.");
      }
      if (p.exitValue() != 0) {
        throw new AiAccountantException(
            "Azure CLI failed (exit " + p.exitValue() + "). Is 'az login' current?");
      }
      if (out == null || out.trim().isEmpty()) {
        throw new AiAccountantException("Azure CLI returned no ARM token.");
      }
      return out.trim();
    } catch (IOException e) {
      throw new AiAccountantException(
          "Could not run the Azure CLI ('az' not on PATH?): " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AiAccountantException("Interrupted while running the Azure CLI.", e);
    } finally {
      if (p != null && p.isAlive()) {
        p.destroyForcibly();
      }
    }
  }

  /** Extracts just the error code, so nothing sensitive is logged or surfaced. */
  protected String errorCode(String body) {
    try {
      return mapper.readTree(body).path("error").asText("unknown_error");
    } catch (Exception e) {
      return "unparseable_error";
    }
  }

  protected TokenResponse httpPost(String url, String form)
      throws IOException, InterruptedException {
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
            .build();
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new TokenResponse(resp.statusCode(), resp.body());
  }

  protected static String enc(String v) {
    try {
      return URLEncoder.encode(v, StandardCharsets.UTF_8.name());
    } catch (java.io.UnsupportedEncodingException e) {
      throw new IllegalStateException(e); // UTF-8 always present
    }
  }

  protected long monotonicMillis() {
    return System.nanoTime() / 1_000_000L;
  }

  /** A token and how long it lasts, so {@link #bearerToken()} can cache it. */
  protected static final class Acquired {
    final String token;
    final long expiresInSeconds;

    Acquired(String token, long expiresInSeconds) {
      this.token = token;
      this.expiresInSeconds = expiresInSeconds;
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
}
