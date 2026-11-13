package com.axelor.apps.aiaccountant.router;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Asks the Router Agent, and answers for it when it cannot.
 *
 * <h2>One exit</h2>
 *
 * This never throws and never returns null. Every way the router can fail — disabled, unconfigured,
 * timed out, refused, unreachable, unparseable, or answering with a value that is not in the
 * vocabulary — comes back as {@link AiRouterVerdict.Status#FAILED} carrying a short reason. That is
 * what makes the fallback trivial: the caller has one thing to check, and the answer to a failed
 * check is always the same, namely the route {@code AiRouteAdapter} already produced.
 *
 * <p>The reasons are a closed set of short tokens rather than exception messages, because they are
 * counted. "How often does the router time out" is the question this phase exists to answer, and it
 * cannot be answered from free text.
 */
@Singleton
public class AiRouterService {

  private static final Logger LOG = LoggerFactory.getLogger(AiRouterService.class);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final AiAccountantConfig config;
  private final AiRouterClient client;

  @Inject
  public AiRouterService(AiAccountantConfig config, AiRouterClient client) {
    this.config = config;
    this.client = client;
  }

  /** Whether the router should be called at all: switched on, and with somewhere to call. */
  public boolean isAvailable() {
    return config.isRouterEnabled() && client.isConfigured();
  }

  /**
   * Classifies one turn.
   *
   * @param input the complete request text from {@link AiRouterContract#request}
   */
  public AiRouterVerdict classify(String input) {
    return classify(input, 0);
  }

  /**
   * Classifies one turn under a caller-imposed ceiling.
   *
   * @param timeoutSeconds the most time this call may take; {@code 0} means the configured router
   *     timeout, which is what shadow mode uses because nothing is waiting for it
   */
  public AiRouterVerdict classify(String input, int timeoutSeconds) {
    if (!config.isRouterEnabled()) {
      return AiRouterVerdict.failed("disabled", 0L);
    }
    if (!client.isConfigured()) {
      return AiRouterVerdict.failed("not_configured", 0L);
    }
    long started = System.nanoTime();
    String text;
    try {
      text = timeoutSeconds > 0 ? client.classify(input, timeoutSeconds) : client.classify(input);
    } catch (RuntimeException e) {
      long millis = elapsedMillis(started);
      String reason = failureReason(e);
      LOG.debug("AI Accountant: router call failed ({}): {}", reason, e.getMessage());
      return AiRouterVerdict.failed(reason, millis);
    }
    long millis = elapsedMillis(started);
    if (text == null || text.trim().isEmpty()) {
      return AiRouterVerdict.failed("empty_reply", millis);
    }
    return AiRouterVerdict.parse(text, MAPPER).withLatency(millis);
  }

  /**
   * A short, countable token for what went wrong.
   *
   * <p>Read from the message because the transport reports timeouts, refusals and HTTP statuses as
   * one exception type. Coarse on purpose: the distinction that matters is "the router was too
   * slow" against "the router said no" against "the router was not reachable".
   */
  static String failureReason(RuntimeException e) {
    String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
    if (message.contains("timed out") || message.contains("timeout")) {
      return "timeout";
    }
    if (message.contains("http 4")) {
      return "rejected";
    }
    if (message.contains("http 5")) {
      return "service_error";
    }
    if (message.contains("cannot reach")) {
      return "unreachable";
    }
    if (message.contains("malformed")) {
      return "malformed";
    }
    return "error";
  }

  private static long elapsedMillis(long startedNanos) {
    return (System.nanoTime() - startedNanos) / 1_000_000L;
  }
}
