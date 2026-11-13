package com.axelor.apps.aiaccountant.context;

import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.base.db.Company;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;

/**
 * Everything about <em>this</em> request that is settled before any work starts: who is asking, in
 * which company, looking at what, in the middle of which job, and what the turn is.
 *
 * <h2>Why it exists</h2>
 *
 * These five facts are read all over the turn today, each from its own source — {@code AuthUtils}
 * for the user, {@code AiCompanyScope} for the company, {@code ChatRequest.getContext()} for the
 * page, {@code AiConversationState} for the workflow, three separate resolvers for the route. Each
 * reader resolves what it needs, when it needs it, which means a turn can in principle see one
 * company early and another late, and every new participant has to know which of five helpers to
 * ask.
 *
 * <p>Resolved once, at the top of the turn, and carried. A reader downstream sees the same request
 * every other reader saw.
 *
 * <h2>Bound to the turn, like its neighbours</h2>
 *
 * The chat service passes it explicitly where a signature already existed to take it. Everything
 * else — a tool, a service three frames down — reads {@link #current()}, which is the same pattern
 * {@code AiWriteEffects}, {@code AiTurnReadMemory} and {@code AiTurnToolLog} already use, with the
 * same discipline: opened in a try, released in a finally, never nested. Threading a parameter
 * through the {@code AiTool} SPI would have changed every published tool's signature to deliver a
 * value none of them read yet.
 *
 * <p>Read-only. Nothing here is a permission and nothing here is enforced from here: the company
 * scope is still {@code AiCompanyScope}'s to enforce, and the caller's identity is still {@code
 * AiExecutionContext}'s. This is a description of the request, and a description that started
 * deciding things would be a second source of truth for both.
 */
public final class AiRequestContext {

  private static final ThreadLocal<AiRequestContext> CURRENT = new ThreadLocal<>();

  private final Long userId;
  private final String userName;
  private final Long companyId;
  private final String companyName;
  private final PageContext page;
  private final AiWorkflow workflow;
  private final AiRoute route;
  private final String message;
  private final String conversationId;
  private final String continuationEcho;

  private AiRequestContext(
      Long userId,
      String userName,
      Long companyId,
      String companyName,
      PageContext page,
      AiWorkflow workflow,
      AiRoute route,
      String message,
      String conversationId,
      String continuationEcho) {
    this.userId = userId;
    this.userName = userName;
    this.companyId = companyId;
    this.companyName = companyName;
    this.page = page;
    this.workflow = workflow;
    this.route = route == null ? AiRoute.unknown() : route;
    this.message = message;
    this.conversationId = conversationId;
    this.continuationEcho = continuationEcho;
  }

  /**
   * Resolves the request from where each fact actually lives.
   *
   * <p>Never throws. A company that cannot be resolved is a null company here — the refusal belongs
   * to {@code AiCompanyScope} at the moment a tool tries to read data, not to a descriptive object
   * built before anything has been attempted.
   */
  public static AiRequestContext of(
      String message, PageContext page, AiWorkflow workflow, AiRoute route) {
    return of(message, page, workflow, route, null, null);
  }

  /** As above, for a request that belongs to a named conversation — every chat turn does. */
  public static AiRequestContext of(
      String message, PageContext page, AiWorkflow workflow, AiRoute route, String conversationId) {
    return of(message, page, workflow, route, conversationId, null);
  }

  /**
   * As above, carrying the continuation block when this turn's route came from a verified
   * continuation of last turn's proposal — see {@code AiRouteSelector} and {@code
   * AiTurnMemoryStore}. Null on every other turn.
   *
   * @param continuationEcho the block {@code AiTurnContextBuilder} appends to tell the model what
   *     it is continuing, or null
   */
  public static AiRequestContext of(
      String message,
      PageContext page,
      AiWorkflow workflow,
      AiRoute route,
      String conversationId,
      String continuationEcho) {
    User user = null;
    try {
      user = AuthUtils.getUser();
    } catch (RuntimeException e) {
      // No Shiro subject on this thread. Legitimate for the intake pipeline, which runs as the
      // system; AiExecutionContext is what says whether that is authorised.
      user = null;
    }
    Company company = AiCompanyScope.currentCompanyOrNull();
    return new AiRequestContext(
        user == null ? null : user.getId(),
        user == null ? AiExecutionContext.callerDescription() : user.getCode(),
        company == null ? null : company.getId(),
        company == null ? null : company.getName(),
        page,
        workflow,
        route,
        message,
        conversationId,
        continuationEcho);
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Turn binding
  // ───────────────────────────────────────────────────────────────────────────

  /** Binds this context for the current thread. Paired with {@link #end()} in a finally block. */
  public static void begin(AiRequestContext context) {
    CURRENT.set(context);
  }

  /** The request being served on this thread, or null outside a turn. */
  public static AiRequestContext current() {
    return CURRENT.get();
  }

  public static void end() {
    CURRENT.remove();
  }

  // ───────────────────────────────────────────────────────────────────────────
  // What it holds
  // ───────────────────────────────────────────────────────────────────────────

  /** The authenticated user's id, or null when running as the system. */
  public Long getUserId() {
    return userId;
  }

  /** A short name for a log line — the user's code, or the system caller's description. */
  public String getUserName() {
    return userName;
  }

  public Long getCompanyId() {
    return companyId;
  }

  public String getCompanyName() {
    return companyName;
  }

  /** What the client says is on screen, or null when it said nothing. */
  public PageContext getPage() {
    return page;
  }

  /** True when a specific record is open, as opposed to a list or nothing. */
  public boolean hasOpenRecord() {
    return page != null && page.hasRecord();
  }

  /** The open record as {@code Invoice#592}, or null when no record is open. */
  public String getOpenRecordRef() {
    if (!hasOpenRecord()) {
      return null;
    }
    String model = page.getModel();
    int dot = model.lastIndexOf('.');
    return (dot < 0 ? model : model.substring(dot + 1)) + "#" + page.getRecordId();
  }

  /** The job this turn belongs to, or null when it belongs to none. */
  public AiWorkflow getWorkflow() {
    return workflow;
  }

  public boolean hasWorkflow() {
    return workflow != null && workflow.isActive();
  }

  /** Never null: an unresolvable turn carries {@link AiRoute#unknown()}. */
  public AiRoute getRoute() {
    return route;
  }

  /** The user's own words this turn. */
  public String getMessage() {
    return message;
  }

  /**
   * Which chat panel this turn came from, or null when the caller named none.
   *
   * <p>Read by the write check: an authorisation belongs to the conversation that gave it, so a job
   * carried in from another panel authorises nothing here. Null is not a wildcard — it matches no
   * conversation at all, which is what makes a turn the server cannot attribute unable to continue
   * anyone's work.
   */
  public String getConversationId() {
    return conversationId;
  }

  /**
   * The block telling the model what it is continuing, or null on every turn that is not a verified
   * continuation of last turn's own proposal.
   */
  public String getContinuationEcho() {
    return continuationEcho;
  }

  @Override
  public String toString() {
    return "AiRequestContext[user="
        + userName
        + ", company="
        + companyId
        + ", page="
        + (page == null ? "none" : page.kind())
        + ", route="
        + route.describe()
        + ']';
  }
}
