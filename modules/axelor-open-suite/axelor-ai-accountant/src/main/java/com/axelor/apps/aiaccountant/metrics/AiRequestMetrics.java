package com.axelor.apps.aiaccountant.metrics;

import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.inject.Beans;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One line per Foundry request, whoever made it.
 *
 * <h2>Why this exists separately from {@link AiTurnMetrics}</h2>
 *
 * {@code ai.turn} measures a <em>chat turn</em>, and only a chat turn opens one. Azure's usage
 * counter, meanwhile, counts every request the resource served. Nineteen recorded turns accounted
 * for ~886k input tokens while the portal showed ~2.4M consumed over the same period — a gap that
 * cannot be explained from turn metrics alone, because anything that never opened a turn window is
 * invisible to them. The OCR intake correction and the post-draft line repair both call the same
 * agent through {@code AiAccountantService.chat(...)}, on background threads, and each carries the
 * full operating procedure and every tool declaration.
 *
 * <p>So this sits at the lowest shared layer — the REST client — where nothing can bypass it, and
 * attributes each request to its caller. It is the only instrument that can prove where the tokens
 * actually go.
 *
 * <h2>What is recorded</h2>
 *
 * The caller category, the model, durations and the response's own {@code usage} figures. Never a
 * prompt, an argument, a document, a supplier, an invoice or any response text — the same rule
 * {@code AiTurnMetrics} follows, and for the same reason. The timestamp comes from the log pattern
 * itself, so it is not repeated in the payload.
 *
 * <p>Emitted on the {@code com.axelor.ai.metrics} logger, so {@link AiMetricsFileLog} persists
 * these alongside {@code ai.turn} and {@code ai.budget} with no extra configuration.
 */
public final class AiRequestMetrics {

  private static final Logger LOG = LoggerFactory.getLogger("com.axelor.ai.metrics");

  /** Chat: a real user turn. The absence of a system scope is what defines it. */
  public static final String CALLER_CHAT = "chat";

  /**
   * Which agent served the request.
   *
   * <p>A separate dimension from {@link #caller()}, which says who asked. The two are orthogonal:
   * an OCR intake pass and a chat turn can both reach either agent, and the router is a different
   * agent with a different endpoint, a different definition and its own share of the bill. Until
   * this existed the router was not measured at all, so every token figure ever recorded here
   * described the main agent alone while appearing to describe the whole system.
   */
  public static final String AGENT_MAIN = "main";

  public static final String AGENT_ROUTER = "router";

  private AiRequestMetrics() {}

  /**
   * The caller category, from the execution context rather than from the call stack.
   *
   * <p>{@code AiExecutionContext.callAsSystem(companyId, reason, …)} already names every internal
   * entry point — {@code ocr-intake} for the pre-draft correction, {@code ocr-draft-repair} for the
   * line repair — and that scope is open for the whole call, including the HTTP request. Reading it
   * is exact; inferring a caller from a stack trace would not be.
   */
  public static String caller() {
    AiExecutionContext.SystemScope scope = AiExecutionContext.systemScope();
    if (scope == null) {
      return CALLER_CHAT;
    }
    String reason = scope.getReason();
    return reason == null || reason.trim().isEmpty() ? "system" : reason.trim();
  }

  /** A request that returned a response. */
  public static void success(
      long millis, FoundryResponse response, boolean chained, String agentRole) {
    emit(millis, response, chained, "ok", agentRole);
  }

  /** A request that failed; token figures are unknown, so they are reported as zero. */
  public static void failure(long millis, boolean chained, String outcome, String agentRole) {
    emit(millis, null, chained, outcome == null ? "error" : outcome, agentRole);
  }

  private static void emit(
      long millis, FoundryResponse response, boolean chained, String outcome, String agentRole) {
    String role = agentRole == null || agentRole.trim().isEmpty() ? AGENT_MAIN : agentRole.trim();
    String model = "-";
    String agentVersion = "-";
    int in = 0;
    int cached = 0;
    int out = 0;
    int reasoning = 0;
    int total = 0;
    if (response != null) {
      try {
        model = response.getModel() == null ? "-" : response.getModel();
        agentVersion = response.getAgentVersion() == null ? "-" : response.getAgentVersion();
        FoundryResponse.Usage u = response.usage();
        if (u.isPresent()) {
          in = u.inputTokens();
          cached = u.cachedInputTokens();
          out = u.outputTokens();
          reasoning = u.reasoningTokens();
          total = u.totalTokens();
        }
      } catch (RuntimeException e) {
        LOG.debug("AI Accountant: could not read request usage: {}", e.getMessage());
      }
    }

    try {
      StringBuilder sb = new StringBuilder();
      kv(sb, "caller", caller());
      kv(sb, "outcome", outcome);
      kv(sb, "chained", String.valueOf(chained));
      kv(sb, "ms", String.valueOf(millis));
      kv(sb, "model", model);
      kv(sb, "agentVersion", agentVersion);
      kv(sb, "inputTokens", String.valueOf(in));
      kv(sb, "cachedInputTokens", String.valueOf(cached));
      kv(sb, "uncachedInputTokens", String.valueOf(Math.max(0, in - cached)));
      kv(sb, "outputTokens", String.valueOf(out));
      kv(sb, "reasoningTokens", String.valueOf(reasoning));
      kv(sb, "totalTokens", String.valueOf(total));
      // Appended, never inserted: every existing key keeps its name and its place, so the grep and
      // the awk aggregator in AiTurnMetrics' javadoc read these lines exactly as before. The key is
      // new because the router's requests are new to this log — before it was instrumented, every
      // line here was a main-agent line by construction.
      kv(sb, "agentRole", role);
      LOG.info("ai.request {}", sb.toString().trim());
    } catch (RuntimeException e) {
      // Measuring a request must never be the reason that request is reported as broken.
      LOG.debug("AI Accountant: could not emit request metrics: {}", e.getMessage());
    }

    persist(millis, chained, outcome, role, model, agentVersion, in, cached, out, reasoning, total);
  }

  /**
   * Hands the same figures to the database, for the Stats page.
   *
   * <p>Separate from the log line above, and separately guarded, so neither can cost the other. The
   * user and company are read here because this still runs on the caller's own thread — the
   * security subject is thread-bound and would be gone by the time the row is written. Nothing is
   * queried: the ids are captured and the recorder resolves the labels on its own thread.
   */
  private static void persist(
      long millis,
      boolean chained,
      String outcome,
      String role,
      String model,
      String agentVersion,
      int in,
      int cached,
      int out,
      int reasoning,
      int total) {
    try {
      AiExecutionContext.SystemScope scope = AiExecutionContext.systemScope();
      Long userId = null;
      Long companyId;
      if (scope == null) {
        User user = AuthUtils.getUser();
        userId = user == null ? null : user.getId();
        companyId = AiCompanyScope.companyIdOrNull();
      } else {
        // A system pass has no user by construction, and its company is the one the scope names.
        companyId = scope.getCompanyId();
      }
      Beans.get(AiUsageStatRecorder.class)
          .record(
              new AiUsageSnapshot()
                  .at(LocalDateTime.now(ZoneOffset.UTC))
                  .user(userId)
                  .company(companyId)
                  .caller(caller())
                  .agentRole(role)
                  .outcome(outcome)
                  .chained(chained)
                  .model("-".equals(model) ? null : model)
                  .agentVersion("-".equals(agentVersion) ? null : agentVersion)
                  .durationMs(millis)
                  .tokens(in, cached, out, reasoning, total));
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not queue usage statistics: {}", e.getMessage());
    }
  }

  private static void kv(StringBuilder sb, String key, String value) {
    sb.append(key).append('=').append(value == null || value.isEmpty() ? "-" : value).append(' ');
  }
}
