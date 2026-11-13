package com.axelor.apps.aiaccountant.router;

import com.axelor.apps.aiaccountant.route.AiRoute;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one {@code ai.router} line, written the same way in both modes.
 *
 * <p>Shadow and active ask the router the identical question and get the identical answer shape.
 * The only difference is what the server then does with it, and that is exactly what {@code used=}
 * records. Keeping the format in one place is what makes a mode change readable in the metrics
 * file: the same fields, the same order, the same tokens, with {@code mode=} and {@code used=}
 * carrying the change.
 *
 * <pre>
 * ai.router mode=active outcome=route latency_ms=740 agreement=4/5 differs=task
 *           domain=SUPPLIER_INVOICE|SUPPLIER_INVOICE task=REVIEW…|REPAIR… used=router
 * ai.router mode=active outcome=failed latency_ms=15002 reason=timeout used=adapter_fallback
 * ai.router mode=active outcome=clarification latency_ms=610 used=router_clarification_deferred
 * </pre>
 *
 * <p>Adapter value first, router value second, in every pair. Enum names, counts and durations only
 * — never the message, never a record, never a supplier. That is the property that lets these lines
 * share a file with {@code ai.turn} and {@code ai.budget}.
 */
public final class AiRouterLog {

  /** Same logger as {@code AiTurnMetrics}, so router lines roll into the metrics file too. */
  private static final Logger METRICS = LoggerFactory.getLogger("com.axelor.ai.metrics");

  /** The adapter decided, because the router did not answer usably. */
  public static final String USED_ADAPTER_FALLBACK = "adapter_fallback";

  /** The router's route was taken. */
  public static final String USED_ROUTER = "router";

  /**
   * The router answered CLARIFICATION. It no longer ends the turn: the adapter's route is used
   * instead, and the turn still reaches the main agent, which decides for itself whether the user
   * needs to be asked anything.
   */
  public static final String USED_ROUTER_CLARIFICATION_DEFERRED = "router_clarification_deferred";

  /** The router's CONTINUE answer passed Java's own target check and was taken. */
  public static final String USED_ROUTER_CONTINUATION = "router_continuation";

  /**
   * The router answered CONTINUE but Java could not verify it — no exchange on record, or the
   * record on screen does not match the one the proposal was about — so the adapter's route is used
   * instead of trusting it, and the main agent decides for itself whether anything needs asking.
   */
  public static final String USED_ROUTER_CONTINUATION_REJECTED = "router_continuation_rejected";

  /** Shadow mode: the router was compared and nothing was taken from it. */
  public static final String USED_ADAPTER = "adapter";

  private AiRouterLog() {}

  public static void emit(String mode, AiRoute adapter, AiRouterVerdict verdict, String used) {
    METRICS.info("ai.router {}", line(mode, adapter, verdict, used));
  }

  /** Counted rather than silent: drops look exactly like agreement unless they are visible. */
  public static void emitDropped(String mode, long droppedTotal) {
    METRICS.info("ai.router mode={} outcome=dropped dropped_total={}", mode, droppedTotal);
  }

  /** One key=value line. Adapter value first, router value second, separated by a pipe. */
  static String line(String mode, AiRoute adapter, AiRouterVerdict verdict, String used) {
    StringBuilder sb = new StringBuilder("mode=").append(mode);
    sb.append(" outcome=").append(verdict.status().name().toLowerCase());
    sb.append(" latency_ms=").append(verdict.latencyMillis());
    if (verdict.route() == null) {
      // Nothing to compare. The reason is the measurement: it is what says whether the router is
      // slow, unreachable, or answering outside its own vocabulary.
      sb.append(" reason=").append(token(verdict.reason()));
      sb.append(" used=").append(used);
      return sb.toString();
    }
    AiRoute r = verdict.route();
    StringBuilder differs = new StringBuilder();
    int agreed = 0;
    agreed += field(sb, differs, "domain", adapter.domain().name(), r.domain().name());
    agreed += field(sb, differs, "task", adapter.task().name(), r.task().name());
    agreed +=
        field(
            sb,
            differs,
            "goal",
            adapter.goal() == null ? "-" : adapter.goal().name(),
            r.goal() == null ? "-" : r.goal().name());
    agreed += field(sb, differs, "scope", adapter.scope().name(), r.scope().name());
    agreed += field(sb, differs, "expl", adapter.explicitness().name(), r.explicitness().name());
    sb.append(" agreement=").append(agreed).append("/5");
    sb.append(" differs=").append(differs.length() == 0 ? "-" : differs.toString());
    // Said on every line, not only when they disagree: a reader a month from now has to be able to
    // tell what the router's answer actually did, without knowing which mode was deployed when.
    sb.append(" used=").append(used);
    return sb.toString();
  }

  private static int field(
      StringBuilder sb, StringBuilder differs, String name, String adapter, String router) {
    sb.append(' ').append(name).append('=').append(adapter).append('|').append(router);
    if (adapter.equals(router)) {
      return 1;
    }
    if (differs.length() > 0) {
      differs.append(',');
    }
    differs.append(name);
    return 0;
  }

  /** Reasons are logged as single tokens so the line stays parseable by field. */
  private static String token(String reason) {
    if (reason == null || reason.trim().isEmpty()) {
      return "-";
    }
    String t = reason.trim().replace(' ', '_').replace('=', '-').replace('|', '-');
    return t.length() > 40 ? t.substring(0, 40) : t;
  }
}
