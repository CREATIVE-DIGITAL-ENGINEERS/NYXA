package com.axelor.apps.aiaccountant.router;

import com.axelor.apps.aiaccountant.client.AiAccountantException;
import com.axelor.apps.aiaccountant.client.AiTokenProvider;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.axelor.apps.aiaccountant.metrics.AiRequestMetrics;
import com.axelor.apps.aiaccountant.resilience.AiResiliencePolicy;
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

/**
 * The Router Agent over the same Foundry Responses API the chat uses, with the opposite settings.
 *
 * <h2>Stateless, tool-free, one attempt</h2>
 *
 * <ul>
 *   <li><b>No {@code previous_response_id}.</b> Routing one message must not depend on how the last
 *       one was routed; a classifier that remembers is a classifier whose mistakes compound. It
 *       also means two turns never queue behind one conversation.
 *   <li><b>No tools.</b> Tools are declared on the agent definition, and the router's definition
 *       has none. Nothing here can send any: the request body carries {@code input} and nothing
 *       else. A router that could call a tool would be reading the ERP to decide how to read the
 *       ERP, and its answer would stop being a classification.
 *   <li><b>No retry.</b> The chat retries because losing a turn loses the user's work. A routing
 *       answer that arrives late is worth nothing — the turn has already gone. One attempt inside a
 *       short deadline, then the caller falls back.
 * </ul>
 *
 * <p>Every failure leaves as an exception, and the caller turns it into a logged reason. Nothing
 * here decides what a failure means.
 */
@Singleton
public class FoundryRouterRestClient implements AiRouterClient {

  private final AiAccountantConfig config;
  private final AiTokenProvider tokenProvider;
  private final ObjectMapper mapper = new ObjectMapper();

  @Inject
  public FoundryRouterRestClient(AiAccountantConfig config, AiTokenProvider tokenProvider) {
    this.config = config;
    this.tokenProvider = tokenProvider;
  }

  @Override
  public boolean isConfigured() {
    return !config.routerChatUrl().isEmpty();
  }

  @Override
  public String classify(String input) {
    return classify(input, new AiResiliencePolicy(config).routerRequestTimeoutSeconds());
  }

  @Override
  public String classify(String input, int timeoutSeconds) {
    String url = config.routerChatUrl();
    if (url.isEmpty()) {
      throw new AiAccountantException(
          "AI Accountant: the Router Agent endpoint is not configured ("
              + AiAccountantConfig.ENV_ROUTER_AGENT_ENDPOINT
              + ").");
    }
    ObjectNode body = mapper.createObjectNode();
    body.put("input", input);

    // Measured exactly as the chat client measures its own requests, and for a sharper version of
    // the same reason. The router is a second agent on a second endpoint that is sent the whole
    // conversation on every turn, and until this call existed it appeared in no measurement at all:
    // its share of the tokens was simply missing from every figure the assistant reported about
    // itself. `chained` is always false — the router never continues a conversation.
    long started = System.nanoTime();
    HttpResult res;
    try {
      res = httpPost(url, body.toString(), tokenProvider.bearerToken(), timeoutSeconds);
    } catch (java.net.http.HttpTimeoutException e) {
      AiRequestMetrics.failure(
          elapsedMillis(started), false, "transport_timeout", AiRequestMetrics.AGENT_ROUTER);
      throw new AiAccountantException("Router timed out.", e);
    } catch (IOException e) {
      AiRequestMetrics.failure(
          elapsedMillis(started), false, "transport_unreachable", AiRequestMetrics.AGENT_ROUTER);
      throw new AiAccountantException("Cannot reach the router: " + e.getMessage(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      AiRequestMetrics.failure(
          elapsedMillis(started), false, "interrupted", AiRequestMetrics.AGENT_ROUTER);
      throw new AiAccountantException("Interrupted while calling the router.", e);
    }
    if (res.status != 200) {
      AiRequestMetrics.failure(
          elapsedMillis(started), false, "http_" + res.status, AiRequestMetrics.AGENT_ROUTER);
      throw new AiAccountantException("Router returned HTTP " + res.status + ".");
    }
    try {
      FoundryResponse response = new FoundryResponse(mapper.readTree(res.body));
      AiRequestMetrics.success(
          elapsedMillis(started), response, false, AiRequestMetrics.AGENT_ROUTER);
      return response.assistantText();
    } catch (IOException e) {
      AiRequestMetrics.failure(
          elapsedMillis(started), false, "malformed", AiRequestMetrics.AGENT_ROUTER);
      throw new AiAccountantException("Malformed router response.", e);
    }
  }

  private static long elapsedMillis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000L;
  }

  /** Minimal transport result, so HTTP can be stubbed in tests. Mirrors the chat client's. */
  protected static final class HttpResult {
    final int status;
    final String body;

    HttpResult(int status, String body) {
      this.status = status;
      this.body = body;
    }
  }

  /**
   * One attempt, under whichever deadline is shorter: the router's own or the caller's.
   *
   * <p>The configured numbers come from {@link AiResiliencePolicy} like every other outbound
   * call's. In shadow mode the caller passes that configured value straight through — a background
   * thread with no turn around it and nobody waiting. In active mode the turn waits, so the caller
   * passes a slice of the turn's remaining budget instead, and the router can no longer spend time
   * the main agent needs.
   */
  protected HttpResult httpPost(String url, String body, String bearer, int timeoutSeconds)
      throws IOException, InterruptedException {
    AiResiliencePolicy policy = new AiResiliencePolicy(config);
    int request = Math.max(1, Math.min(timeoutSeconds, policy.routerRequestTimeoutSeconds()));
    // Connecting cannot be allowed to consume the whole slice, or a slow handshake leaves no time
    // to receive the answer it was opened for.
    int connect = Math.max(1, Math.min(policy.routerConnectTimeoutSeconds(), request));
    HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(connect))
            .build();
    HttpResponse<String> resp =
        client.send(
            HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(request))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + bearer)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build(),
            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    return new HttpResult(resp.statusCode(), resp.body());
  }
}
