package com.axelor.apps.aiaccountant.route;

import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;

/**
 * What this turn is: which document, which job, what destination, over which records, and how
 * firmly any of that was said.
 *
 * <h2>The five answers, in one object</h2>
 *
 * Today those five facts are derived in four places from the same message — {@code AiTaskResolver}
 * for the playbook, {@code AiWorkflowIntent.requested} for the goal, {@code
 * AiWorkflowIntent.delegatesSelection} and the page context for the scope — and each caller
 * re-derives what it needs. They agree because they read the same helpers, not because anything
 * makes them agree.
 *
 * <p>Gathering them into one immutable value resolved once per turn removes the possibility of
 * drift and, more to the point, gives the Router Agent a shape to fill in. When routing becomes a
 * model decision, it produces one of these; nothing downstream needs to know which side of that
 * change it came from.
 *
 * <h2>Nothing dispatches on it yet</h2>
 *
 * This phase builds the vocabulary and the seam. {@link AiRouteAdapter} produces a route from the
 * existing resolvers, the turn carries it, and behaviour is exactly what it was — the same task
 * still selects the same playbook through the same call. A route that decides anything is a later
 * change, and it will be visible as one.
 */
public final class AiRoute {

  private final AiDomain domain;
  private final AiTask task;
  private final AiWorkflowIntent.Stage goal;
  private final AiScope scope;
  private final AiExplicitness explicitness;
  private final AiSetSelection selection;
  private final Integer count;
  private final String filter;
  private final AiWriteOperation operation;

  public AiRoute(
      AiDomain domain,
      AiTask task,
      AiWorkflowIntent.Stage goal,
      AiScope scope,
      AiExplicitness explicitness) {
    this(domain, task, goal, scope, explicitness, null, null, null, null);
  }

  /**
   * As above, with how much of a list the instruction covers.
   *
   * @param selection the shape of the set, or null when nothing stated one — see {@link
   *     AiSetSelection}, where null is a distinct, deliberately preserved state
   * @param count how many records a {@link AiSetSelection#COUNT} selection asked for
   * @param filter the subset a {@link AiSetSelection#FILTERED} selection named
   */
  public AiRoute(
      AiDomain domain,
      AiTask task,
      AiWorkflowIntent.Stage goal,
      AiScope scope,
      AiExplicitness explicitness,
      AiSetSelection selection,
      Integer count,
      String filter) {
    this(domain, task, goal, scope, explicitness, selection, count, filter, null);
  }

  /**
   * As above, with whether a maintenance instruction makes a record or changes one.
   *
   * @param operation {@link AiWriteOperation}, or null when nothing stated it — a distinct state,
   *     never read as {@code CREATE}
   */
  public AiRoute(
      AiDomain domain,
      AiTask task,
      AiWorkflowIntent.Stage goal,
      AiScope scope,
      AiExplicitness explicitness,
      AiSetSelection selection,
      Integer count,
      String filter,
      AiWriteOperation operation) {
    // Null-tolerant on construction rather than at every reader: a route is built on the error path
    // too, and a turn must never fail because one of five descriptive fields could not be resolved.
    this.domain = domain == null ? AiDomain.GENERAL : domain;
    this.task = task == null ? AiTask.GENERAL : task;
    this.goal = goal;
    this.scope = scope == null ? AiScope.UNSCOPED : scope;
    this.explicitness = explicitness == null ? AiExplicitness.NONE : explicitness;
    // Deliberately NOT defaulted: "nothing said how much of the list" must stay distinguishable
    // from "the whole list", or the adapter's page-derived WORKLIST scope would authorise every
    // eligible record for a message nobody read that way.
    this.selection = selection;
    this.count = count;
    this.filter = filter == null || filter.trim().isEmpty() ? null : filter.trim();
    // Same rule, same reason: "nothing said whether this makes or changes a record" is not
    // "it makes one" — see AiWriteOperation.
    this.operation = operation;
  }

  /** The route of a turn nothing could be established about. Never null-checked by callers. */
  public static AiRoute unknown() {
    return new AiRoute(
        AiDomain.GENERAL, AiTask.GENERAL, null, AiScope.UNSCOPED, AiExplicitness.NONE);
  }

  public AiDomain domain() {
    return domain;
  }

  /** The playbook decision, unchanged and still made by {@code AiTaskResolver}. */
  public AiTask task() {
    return task;
  }

  /** The destination this turn is authorised to reach, or null when none was established. */
  public AiWorkflowIntent.Stage goal() {
    return goal;
  }

  public AiScope scope() {
    return scope;
  }

  public AiExplicitness explicitness() {
    return explicitness;
  }

  /** How much of a list this turn covers, or null when nothing stated it. */
  public AiSetSelection selection() {
    return selection;
  }

  /** How many records a {@link AiSetSelection#COUNT} instruction asked for, or null. */
  public Integer count() {
    return count;
  }

  /** The subset a {@link AiSetSelection#FILTERED} instruction named, or null. */
  public String filter() {
    return filter;
  }

  /** Whether this turn makes a record or changes one, or null when nothing stated it. */
  public AiWriteOperation operation() {
    return operation;
  }

  /** The same route with a different scope, keeping everything else — used when binding. */
  public AiRoute withScope(AiScope newScope) {
    return new AiRoute(
        domain, task, goal, newScope, explicitness, selection, count, filter, operation);
  }

  /** The same route with a different explicitness, keeping everything else. */
  public AiRoute withExplicitness(AiExplicitness newExplicitness) {
    return new AiRoute(
        domain, task, goal, scope, newExplicitness, selection, count, filter, operation);
  }

  /** True only when this turn's own words named the destination — never for a continuation. */
  public boolean isExplicitGoal() {
    return goal != null && explicitness == AiExplicitness.EXPLICIT;
  }

  /** True when the set of records was established by something other than the message. */
  public boolean hasScope() {
    return scope != AiScope.UNSCOPED;
  }

  /** One line for a log or a metric: {@code SUPPLIER_INVOICE/POST_SUPPLIER_INVOICE/POSTED/…}. */
  public String describe() {
    return domain.name()
        + '/'
        + task.name()
        + '/'
        + (goal == null ? "-" : goal.name())
        + '/'
        + scope.name()
        + '/'
        + explicitness.name()
        + (selection == null
            ? ""
            : '/'
                + selection.name()
                + (count == null ? "" : "(" + count + ")")
                + (filter == null ? "" : "(" + filter + ")"))
        + (operation == null ? "" : "/" + operation.name());
  }

  @Override
  public String toString() {
    return "AiRoute[" + describe() + ']';
  }
}
