package com.axelor.apps.aiaccountant.router;

import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.aiaccountant.route.AiSetSelection;
import com.axelor.apps.aiaccountant.route.AiWriteOperation;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * What the router answered, once the server has decided whether to believe it.
 *
 * <h2>Validation is the whole point</h2>
 *
 * A model asked for one of five enum values will occasionally return a sixth: a lowercase variant,
 * a plausible-sounding name that never existed, a Bulgarian translation of a constant, a JSON
 * object wrapped in prose. None of those may become a route. Every value is looked up in the enum
 * it must map onto, by exact name, and anything else makes the <em>whole</em> answer {@link
 * Status#FAILED} — not a route with one field defaulted, and not {@link Status#INVALID} either:
 * that status is reserved for the router's own, deliberate "this is not ERP work" answer, which is
 * a different thing from a value this parser could not recognise at all.
 *
 * <p>Defaulting is what would make this dangerous. {@link AiRoute}'s constructor is deliberately
 * null-tolerant, so an unrecognised scope would quietly become {@code UNSCOPED} and an unrecognised
 * task {@code GENERAL}, and the router would appear to be answering when it was not. A partial
 * answer is therefore rejected here, before it can be smoothed over there.
 *
 * <h2>Statuses</h2>
 *
 * {@link Status#ROUTE} is an answer. {@link Status#CONTINUE} is also an answer, and carries the
 * identical five fields — never an empty object — but says something ROUTE does not: this message
 * is a bare affirmation or a modified repeat of a change the assistant proposed last turn, and
 * these five values describe THAT change, not a fresh reading of the message on its own. {@code
 * AiRouteSelector} verifies it — against the active job's own authorised targets when one exists
 * (regardless of what page is open), or otherwise against whether the record on screen canonically
 * matches the one the proposal was about — before it is trusted any further than ROUTE would be.
 * {@link Status#CLARIFICATION} is legacy defensive parsing: the current contract ({@code
 * AiRouterContract#instructions}) tells the router there is no clarification status and never asks
 * for one, but this parser still recognises the shape and falls back to it gracefully if a model
 * emits it anyway, rather than treating an unexpected-but-well-formed reply as a hard failure.
 * {@link Status#INVALID} is the router saying the message is not ERP work — still a live, requested
 * status. {@link Status#FAILED} is the server saying the router did not answer: timeout, transport,
 * non-200, unparseable text, or a value outside the vocabulary. Only ROUTE and CONTINUE carry a
 * route, and in shadow mode nothing carries anything anywhere.
 */
public final class AiRouterVerdict {

  public enum Status {
    ROUTE,
    CONTINUE,
    CLARIFICATION,
    INVALID,
    FAILED
  }

  private final Status status;
  private final AiRoute route;
  private final String question;
  private final String reason;
  private final long latencyMillis;

  private AiRouterVerdict(
      Status status, AiRoute route, String question, String reason, long latencyMillis) {
    this.status = status;
    this.route = route;
    this.question = question;
    this.reason = reason;
    this.latencyMillis = latencyMillis;
  }

  public static AiRouterVerdict failed(String reason, long latencyMillis) {
    return new AiRouterVerdict(Status.FAILED, null, null, reason, latencyMillis);
  }

  public AiRouterVerdict withLatency(long millis) {
    return new AiRouterVerdict(status, route, question, reason, millis);
  }

  public Status status() {
    return status;
  }

  /** The routed answer, or null for every other status. Never a partially filled one. */
  public AiRoute route() {
    return route;
  }

  public String question() {
    return question;
  }

  /** Why it is not a route: the router's own explanation, or the server's failure code. */
  public String reason() {
    return reason;
  }

  public long latencyMillis() {
    return latencyMillis;
  }

  public boolean isRoute() {
    return status == Status.ROUTE && route != null;
  }

  /** True for a CONTINUE answer — a route about last turn's proposal, not yet verified by Java. */
  public boolean isContinuation() {
    return status == Status.CONTINUE && route != null;
  }

  /**
   * Reads the router's reply, rejecting anything that is not exactly the agreed shape.
   *
   * @param text the raw assistant text; may carry code fences or stray prose around the object
   */
  public static AiRouterVerdict parse(String text, ObjectMapper mapper) {
    String json = extractObject(text);
    if (json == null) {
      return failed("no_json", 0L);
    }
    JsonNode node;
    try {
      node = mapper.readTree(json);
    } catch (Exception e) {
      return failed("invalid_json", 0L);
    }
    if (!node.isObject()) {
      return failed("not_an_object", 0L);
    }
    String status = node.path("status").asText("").trim().toLowerCase();
    if ("clarification".equals(status)) {
      String question = textOf(node, "question");
      if (question == null) {
        // A clarification with nothing to ask is not an answer in this vocabulary — it is
        // indistinguishable from the router silently giving up, and treating it as a real
        // CLARIFICATION would ask the caller to relay a question that does not exist. Read as a
        // malformed reply, the same as any other shape this parser refuses.
        return failed("empty_clarification", 0L);
      }
      return new AiRouterVerdict(Status.CLARIFICATION, null, question, textOf(node, "reason"), 0L);
    }
    if ("invalid".equals(status)) {
      return new AiRouterVerdict(Status.INVALID, null, null, textOf(node, "reason"), 0L);
    }
    // ROUTE and CONTINUE are the same five-field shape — CONTINUE says the fields describe last
    // turn's proposal rather than a fresh reading — so both are validated by the identical ladder
    // below and never produce a partial or empty object either way.
    Status outcome;
    if ("route".equals(status)) {
      outcome = Status.ROUTE;
    } else if ("continue".equals(status)) {
      outcome = Status.CONTINUE;
    } else {
      return failed("unknown_status", 0L);
    }

    AiDomain domain = value(AiDomain.class, textOf(node, "domain"));
    AiTask task = value(AiTask.class, textOf(node, "task"));
    AiScope scope = value(AiScope.class, textOf(node, "scope"));
    AiExplicitness explicitness = value(AiExplicitness.class, textOf(node, "explicitness"));
    if (domain == null) {
      return failed("unknown_domain", 0L);
    }
    if (task == null) {
      return failed("unknown_task", 0L);
    }
    if (scope == null) {
      return failed("unknown_scope", 0L);
    }
    if (explicitness == null) {
      return failed("unknown_explicitness", 0L);
    }
    // The one optional field: a turn that asks for nothing has no destination, and null is the
    // right answer rather than a missing one. A present-but-wrong value is still a failure.
    String rawGoal = textOf(node, "goal");
    AiWorkflowIntent.Stage goal = null;
    if (rawGoal != null && !rawGoal.isEmpty() && !"null".equalsIgnoreCase(rawGoal)) {
      goal = value(AiWorkflowIntent.Stage.class, rawGoal);
      if (goal == null) {
        return failed("unknown_goal", 0L);
      }
    }
    // How much of a list the instruction covers. Optional in the same sense goal is: a turn that is
    // not about a list states nothing, and null is the right answer rather than a missing one — see
    // AiSetSelection, where "nothing stated it" is a distinct state the server refuses rather than
    // reads as "all of it". A present-but-wrong value is still a hard failure, exactly like a
    // bad scope: a selection this parser could not recognise must never become a silent default.
    String rawSelection = textOf(node, "setSelection");
    AiSetSelection selection = null;
    Integer count = null;
    String filter = null;
    if (rawSelection != null && !"null".equalsIgnoreCase(rawSelection)) {
      selection = value(AiSetSelection.class, rawSelection);
      if (selection == null) {
        return failed("unknown_set_selection", 0L);
      }
      if (selection == AiSetSelection.COUNT) {
        JsonNode rawCount = node.path("setCount");
        if (!rawCount.isIntegralNumber() || rawCount.asInt() <= 0) {
          // A count that is not a positive whole number is not a count. Refused here rather than
          // defaulted, because every default available ("one", "all") is a guess at how many
          // records to write to.
          return failed("invalid_set_count", 0L);
        }
        count = rawCount.asInt();
      }
      if (selection == AiSetSelection.FILTERED) {
        filter = textOf(node, "setFilter");
        if (filter == null) {
          // FILTERED says "not the whole queue" and nothing else. Without the name of the subset
          // the server has nothing to resolve and nothing precise to refuse, so the answer is not
          // usable as it stands.
          return failed("missing_set_filter", 0L);
        }
      }
    }
    // Whether a maintenance instruction makes a record or changes one — the same optional-but-never
    // -defaulted treatment as the selection above, and for the same reason: MAINTAIN_SUPPLIER is
    // one task for both, and reading an unstated operation as CREATE would mint the authority to
    // add a partner out of a sentence that only asked to change one. See AiWriteOperation.
    String rawOperation = textOf(node, "operation");
    AiWriteOperation operation = null;
    if (rawOperation != null && !"null".equalsIgnoreCase(rawOperation)) {
      operation = value(AiWriteOperation.class, rawOperation);
      if (operation == null) {
        return failed("unknown_operation", 0L);
      }
    }
    return new AiRouterVerdict(
        outcome,
        new AiRoute(domain, task, goal, scope, explicitness, selection, count, filter, operation),
        null,
        textOf(node, "reason"),
        0L);
  }

  /** Exact-name lookup. Case and spacing are tolerated; invention is not. */
  private static <E extends Enum<E>> E value(Class<E> type, String raw) {
    if (raw == null) {
      return null;
    }
    String name = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
    if (name.isEmpty()) {
      return null;
    }
    for (E candidate : type.getEnumConstants()) {
      if (candidate.name().equals(name)) {
        return candidate;
      }
    }
    return null;
  }

  private static String textOf(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isNull() || value.isMissingNode()) {
      return null;
    }
    String text = value.asText("");
    return text.trim().isEmpty() ? null : text.trim();
  }

  /**
   * The first balanced JSON object in the text.
   *
   * <p>Models wrap objects in ```json fences and occasionally in a sentence, and refusing those
   * outright would report a formatting habit as a routing failure. Scanning for a balanced object
   * is tolerant of the wrapper and of nothing else: the braces still have to match, and what is
   * inside still has to validate. Quotes and escapes are tracked so a brace inside a string — a
   * Bulgarian clarification question, most likely — cannot end the object early.
   */
  static String extractObject(String text) {
    if (text == null) {
      return null;
    }
    int start = text.indexOf('{');
    if (start < 0) {
      return null;
    }
    int depth = 0;
    boolean inString = false;
    boolean escaped = false;
    for (int i = start; i < text.length(); i++) {
      char c = text.charAt(i);
      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (c == '\\') {
          escaped = true;
        } else if (c == '"') {
          inString = false;
        }
        continue;
      }
      if (c == '"') {
        inString = true;
      } else if (c == '{') {
        depth++;
      } else if (c == '}') {
        depth--;
        if (depth == 0) {
          return text.substring(start, i + 1);
        }
      }
    }
    return null;
  }
}
