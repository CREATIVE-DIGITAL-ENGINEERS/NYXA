package com.axelor.apps.aiaccountant.router;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.context.AiTargetRegistry;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.metrics.AiTokenEstimate;
import com.axelor.apps.aiaccountant.resilience.AiResiliencePolicy;
import com.axelor.apps.aiaccountant.resilience.AiTurnDeadline;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiRouteAdapter;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.aiaccountant.route.AiWriteOperation;
import com.axelor.apps.aiaccountant.service.AiTurnMemoryStore;
import com.axelor.apps.aiaccountant.service.AiWorkflowController;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.Collections;
import java.util.List;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides which route the turn runs on: the Router Agent's, or the adapter's.
 *
 * <h2>Active mode</h2>
 *
 * The router is asked <em>synchronously</em>, because its answer is now needed before the turn can
 * proceed — the shadow phase could afford a background thread precisely because nothing waited for
 * it. Four outcomes:
 *
 * <ul>
 *   <li><b>ROUTE</b> — the router's five values control {@code AiRoute} for this turn.
 *   <li><b>CONTINUE</b> — the router's five values describe a change it read out of last turn's
 *       proposal rather than out of this message; taken only once {@link #verifiedTarget} confirms
 *       the record on screen is the one that proposal was about, and always capped at {@code
 *       INHERITED} by {@link #clampContinuation} rather than at the adapter's ceiling. Failing
 *       verification falls to the adapter's route, never silently to an unverified ROUTE.
 *   <li><b>CLARIFICATION</b> — never reaches the user from here. The router cannot ask anything
 *       itself and nothing downstream would show its question, so this now behaves exactly like
 *       INVALID or FAILED: the adapter's route is used and the turn still reaches the main agent,
 *       which decides — from the full conversation and this turn's own tool results — whether the
 *       user actually needs to be asked something.
 *   <li><b>anything else</b> — INVALID, FAILED, timed out, unreachable, unparseable, or a value
 *       outside the vocabulary: the adapter's route is used, exactly as in shadow mode.
 * </ul>
 *
 * <p>The adapter route is therefore still computed on every turn, in both modes. That is
 * deliberate: it is the fallback, and it is the other half of the {@code agreement=} measurement
 * that has to keep working after the promotion.
 *
 * <h2>What the router is not allowed to do</h2>
 *
 * The route is five enum values. It selects a playbook and a domain guide, and it is one of the
 * inputs {@code AiWriteAuthorisation} weighs. It is not a permission, it names no record, and it
 * reaches no data. Every check downstream — {@code AiCompanyScope}, {@code AiExecutionContext},
 * {@code checkScope}, {@code checkCompany}, {@code checkCompanyStillCurrent}, {@code
 * checkConversation}, {@code checkTargets}, {@code checkCapability} — runs afterwards, unchanged,
 * on whichever route was chosen. Promoting the router changes which route those checks are applied
 * to; it does not change that they are applied.
 *
 * <p>{@link AiExplicitness} used to be capped here at whatever the adapter's own lexical reading of
 * the message already allowed, on a ROUTE verdict — the router could lower it freely but never
 * raise it past the adapter's ceiling. That protected against a router manufacturing an
 * authorisation out of a word the server never agreed authorises anything, but it also suppressed a
 * <em>correct</em> router verdict whenever the adapter's own stem-matching missed something
 * semantic — a compound or negated Bulgarian sentence the adapter cannot read the way a classifier
 * can. The router is now trusted for explicitness exactly like the other four fields on a ROUTE
 * verdict. This is safe because {@code checkAuthorisation} — the one gate {@code AiExplicitness}
 * feeds — is only the first of seven gates in {@code AiWriteAuthorisation.check}; a
 * wrongly-EXPLICIT verdict still has to name a real, resolvable, company-scoped, domain-matching
 * target through {@code checkScope}, {@code checkTargets} and {@code checkCapability}, none of
 * which the router feeds or can satisfy on its own. CONTINUE keeps its own, separate ceiling
 * ({@link #clampContinuation}), because its fields describe last turn's proposal rather than a
 * fresh reading of this message.
 *
 * <p>There is no separate approval path: an explicit instruction authorises its own change and runs
 * immediately, and a bare «да» authorises nothing on its own.
 */
@Singleton
public class AiRouteSelector {

  private static final Logger LOG = LoggerFactory.getLogger(AiRouteSelector.class);

  /**
   * Absolute floor on what is kept back for the main agent, whatever the model timeout says.
   *
   * <p>Only reached in a deployment configured with an unusually short model request timeout. The
   * real reserve is {@link #mainAgentReserveMillis()}, which is derived rather than guessed.
   */
  static final long MIN_MAIN_AGENT_RESERVE_MILLIS = 30_000L;

  /**
   * Turn time the router may never take.
   *
   * <h2>Why this is derived and not a constant</h2>
   *
   * It was 30 seconds, chosen as "enough for one model round trip". It was not: {@code
   * aiaccountant.azure.request.timeout.seconds} is 120, so a single model call may legitimately
   * take four times the figure that was supposedly reserved for it. A reserve smaller than one
   * request's own timeout does not protect a round trip — it protects nothing, and says otherwise.
   *
   * <p>So it is one model request timeout: the router may not take time that a single call to the
   * main agent could need. With the deployed configuration — 180s turn, 120s model request, 15s
   * router — the router still gets its full 15s, because 180 − 120 = 60 is more than 15. The
   * reserve binds only where the turn budget is genuinely tight, which is the case it exists for.
   */
  protected long mainAgentReserveMillis() {
    return Math.max(MIN_MAIN_AGENT_RESERVE_MILLIS, policy().modelRequestTimeoutSeconds() * 1000L);
  }

  private final AiAccountantConfig config;
  private final AiRouterService router;

  @Inject
  public AiRouteSelector(AiAccountantConfig config, AiRouterService router) {
    this.config = config;
    this.router = router;
  }

  /** Whether this turn will wait for the router at all. */
  public boolean isActive() {
    try {
      return config.isRouterActiveMode() && router.isAvailable();
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not read the router configuration: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Chooses the route for this turn.
   *
   * <p>Never throws and never returns null: a router that fails in an unforeseen way is a router
   * that did not answer, and the answer to that is always the adapter's route.
   *
   * @param adapterRoute the route {@code AiRouteAdapter} produced — the fallback, and the other
   *     half of the agreement measurement
   */
  public AiRouteSelection select(
      String message, PageContext page, AiWorkflow preTurn, Long companyId, AiRoute adapterRoute) {
    return select(message, page, preTurn, companyId, adapterRoute, null, Collections.emptyList());
  }

  /**
   * As above, telling the router what the server remembers of this chat panel's last exchange — see
   * {@link AiTurnMemoryStore} — so a bare affirmation with no {@code activeWorkflow} can still be
   * recognised as continuing a concrete change the assistant proposed on a plain read.
   */
  public AiRouteSelection select(
      String message,
      PageContext page,
      AiWorkflow preTurn,
      Long companyId,
      AiRoute adapterRoute,
      AiTurnMemoryStore.Reading previousTurn) {
    return select(
        message, page, preTurn, companyId, adapterRoute, previousTurn, Collections.emptyList());
  }

  /**
   * As above, also giving the router the complete stored conversation for this chat panel — see
   * {@link AiTurnMemoryStore#loadHistory} — so it can resolve a reference that reaches back further
   * than the one exchange {@code previousTurn} carries. {@code history} is oldest first and must
   * not include the current message, which {@code AiRouterContract} already sends separately.
   */
  public AiRouteSelection select(
      String message,
      PageContext page,
      AiWorkflow preTurn,
      Long companyId,
      AiRoute adapterRoute,
      AiTurnMemoryStore.Reading previousTurn,
      List<AiTurnMemoryStore.Reading> history) {
    return select(message, page, preTurn, companyId, adapterRoute, previousTurn, history, 0);
  }

  /**
   * As above, told how many database-id references {@code AiExplicitReferenceParser} found in the
   * message — a count only, never the ids — so the router can tell a message that named records by
   * id from one that merely mentions an ЕИК, a number or a name.
   */
  public AiRouteSelection select(
      String message,
      PageContext page,
      AiWorkflow preTurn,
      Long companyId,
      AiRoute adapterRoute,
      AiTurnMemoryStore.Reading previousTurn,
      List<AiTurnMemoryStore.Reading> history,
      int explicitRefCount) {
    AiRoute fallback = adapterRoute == null ? AiRoute.unknown() : adapterRoute;
    if (!isActive()) {
      return AiRouteSelection.routed(fallback, AiRouterLog.USED_ADAPTER);
    }
    // The router now spends the user's turn, so it is bounded by what the turn has left minus what
    // the main agent needs to do the work. Falling back costs a routing improvement; overrunning
    // costs the answer, so the reserve wins.
    int budget = routerBudgetSeconds();
    if (budget <= 0) {
      AiRouterLog.emit(
          AiAccountantConfig.ROUTER_MODE_ACTIVE,
          fallback,
          AiRouterVerdict.failed("no_time", 0L),
          AiRouterLog.USED_ADAPTER_FALLBACK);
      return AiRouteSelection.routed(fallback, AiRouterLog.USED_ADAPTER_FALLBACK);
    }

    String input =
        AiRouterContract.request(
            message,
            page,
            preTurn,
            companyId,
            previousTurn,
            history == null ? Collections.emptyList() : history,
            explicitRefCount);
    // The whole request, measured once it is built. AiRouterContract.firstKept drops old history
    // to fit, but it always keeps the newest exchange and it cannot shorten the contract or the
    // user's own message, so its allowance can go negative and the request still overflow. Nothing
    // else checked that: the provider would refuse the call (or truncate it silently), costing the
    // router's whole timeout for no answer. A request that cannot fit is not sent at all — the
    // adapter's route, read from the same message, is the established fallback and loses nothing
    // of the user's instruction; the main agent still receives the message in full.
    int estimated = AiTokenEstimate.of(input);
    int allowed = routerRequestTokenBudget() - Math.max(0, routerOutputReserveTokens());
    if (estimated > allowed) {
      LOG.warn(
          "AI Accountant: router request estimated at {} tokens exceeds the {} allowed"
              + " (aiaccountant.router.request.token.budget minus"
              + " aiaccountant.router.output.reserve.tokens); router skipped, adapter route used.",
          estimated,
          allowed);
      AiRouterLog.emit(
          AiAccountantConfig.ROUTER_MODE_ACTIVE,
          fallback,
          AiRouterVerdict.failed(REASON_OVER_BUDGET, 0L),
          AiRouterLog.USED_ADAPTER_FALLBACK);
      return AiRouteSelection.routed(fallback, AiRouterLog.USED_ADAPTER_FALLBACK);
    }

    AiRouterVerdict verdict;
    try {
      verdict = router.classify(input, budget);
    } catch (RuntimeException e) {
      // AiRouterService is documented never to throw. Belt and braces: a routing decision must not
      // be the thing that fails a turn.
      LOG.debug("AI Accountant: active router call failed: {}", e.getMessage());
      verdict = AiRouterVerdict.failed("error", 0L);
    }

    if (verdict.status() == AiRouterVerdict.Status.CLARIFICATION) {
      // The router does not get to end a turn with a question any more — see AiRouteSelection.
      // Treated exactly like INVALID or FAILED: the adapter's route is used, and the main agent
      // decides for itself, from the full conversation, whether anything needs asking. The
      // question itself is kept only in the debug log, for whoever is tuning the router prompt —
      // never shown to the user and never read by any decision.
      LOG.debug("AI Accountant: router returned clarification, deferred: {}", verdict.question());
      AiRouterLog.emit(
          AiAccountantConfig.ROUTER_MODE_ACTIVE,
          fallback,
          verdict,
          AiRouterLog.USED_ROUTER_CLARIFICATION_DEFERRED);
      return AiRouteSelection.routed(fallback, AiRouterLog.USED_ROUTER_CLARIFICATION_DEFERRED);
    }

    if (verdict.isContinuation()) {
      // The one status whose fields are not a fresh reading of THIS message — they describe a
      // change the router read out of last turn's proposal. The router was never shown the record
      // itself, so the one thing it cannot verify is verified here: the record on screen now has
      // to canonically be the record the proposal was about, or this is not a continuation of it.
      Binding binding = continuationBinding(page, preTurn, previousTurn, fallback, verdict.route());
      if (binding != Binding.NONE) {
        // Bound to what verified it, whatever scope the router wrote: a job-verified continuation
        // inherits the job (CONVERSATION — AiWorkflowController returns the job itself for an
        // INHERITED route), a turn-memory one is about exactly the record on screen that was
        // checked against the proposal (OPEN_RECORD). Never WORKLIST or a set the router named.
        AiRoute chosen =
            clampContinuation(
                verdict.route(),
                binding == Binding.JOB ? AiScope.CONVERSATION : AiScope.OPEN_RECORD);
        AiRouterLog.emit(
            AiAccountantConfig.ROUTER_MODE_ACTIVE,
            fallback,
            verdict,
            AiRouterLog.USED_ROUTER_CONTINUATION);
        return AiRouteSelection.routed(chosen, AiRouterLog.USED_ROUTER_CONTINUATION);
      }
      // Unverified — the record on screen is not canonically the one the proposal was about, or
      // there was nothing to check it against. That is not evidence the turn is ambiguous, only
      // that this particular reading of it does not check out; the adapter's ordinary route is
      // used instead of trusting it, and the main agent is left to ask if it genuinely cannot tell.
      LOG.debug(
          "AI Accountant: router CONTINUE could not be verified against the record on screen;"
              + " falling back to the adapter's route.");
      AiRouterLog.emit(
          AiAccountantConfig.ROUTER_MODE_ACTIVE,
          fallback,
          verdict,
          AiRouterLog.USED_ROUTER_CONTINUATION_REJECTED);
      return AiRouteSelection.routed(fallback, AiRouterLog.USED_ROUTER_CONTINUATION_REJECTED);
    }

    if (!verdict.isRoute()) {
      AiRouterLog.emit(
          AiAccountantConfig.ROUTER_MODE_ACTIVE,
          fallback,
          verdict,
          AiRouterLog.USED_ADAPTER_FALLBACK);
      return AiRouteSelection.routed(fallback, AiRouterLog.USED_ADAPTER_FALLBACK);
    }

    AiRoute chosen = clamp(verdict.route(), fallback, preTurn, message);
    AiRouterLog.emit(
        AiAccountantConfig.ROUTER_MODE_ACTIVE, fallback, verdict, AiRouterLog.USED_ROUTER);
    return AiRouteSelection.routed(chosen, AiRouterLog.USED_ROUTER);
  }

  /**
   * How many seconds of the turn the router may have, or zero when it may not run at all.
   *
   * <h2>Why the router is inside the turn budget</h2>
   *
   * {@code AiTurnDeadline} is begun at the top of the turn, before the route is resolved, so the
   * clock the user experiences is already running when this is called. Left uncapped, the router
   * could spend fifteen seconds of it and hand the main agent a deadline it cannot finish the work
   * inside — the turn would then die at the iteration loop having produced nothing, which is a
   * strictly worse outcome than routing on the adapter would have been.
   *
   * <p>So the router gets what remains, less {@link #mainAgentReserveMillis()}, and never more than
   * its own configured timeout. When that leaves nothing, the router is not called at all and the
   * turn falls back immediately — costing a routing improvement rather than the answer.
   *
   * <p>An unbound deadline (no turn around this call) yields the configured timeout, unchanged.
   */
  protected int routerBudgetSeconds() {
    if (!AiTurnDeadline.isBound()) {
      return policy().routerRequestTimeoutSeconds();
    }
    long remaining = AiTurnDeadline.remainingMillis();
    long usable = remaining - mainAgentReserveMillis();
    if (usable <= 0) {
      return 0;
    }
    long seconds = usable / 1000L;
    if (seconds <= 0) {
      return 0;
    }
    return (int) Math.min(seconds, policy().routerRequestTimeoutSeconds());
  }

  protected AiResiliencePolicy policy() {
    return new AiResiliencePolicy(config);
  }

  /**
   * Uses the router's route as-is on a ROUTE verdict.
   *
   * <p>All five fields — explicitness included, see the class javadoc — are the router's to decide.
   * {@code adapterRoute} is kept only as the {@code null} fallback: a verdict validated as ROUTE
   * always carries a route ({@link AiRouterVerdict#parse}), so that branch is defensive only.
   */
  static AiRoute clamp(AiRoute routerRoute, AiRoute adapterRoute) {
    return routerRoute == null ? adapterRoute : routerRoute;
  }

  /**
   * As above, with the two explicitness facts the router cannot see enforced on top.
   *
   * <ul>
   *   <li>A message that is only an affirmation ({@link AiWorkflowIntent#isBareAffirmation}) stated
   *       nothing of its own, so it is never EXPLICIT — at most INHERITED.
   *   <li>INHERITED needs something on record to inherit: an active job the selected domain still
   *       matches ({@link AiRouteAdapter#compatibleWorkflow}, the same comparison the turn uses to
   *       decide what it resumes). Without one it is IMPLIED — it authorises nothing, which is what
   *       {@code AiWriteAuthorisation} would conclude anyway, except that a freshly opened job
   *       would otherwise count as "on record" for an approval nobody gave about it.
   * </ul>
   */
  static AiRoute clamp(
      AiRoute routerRoute, AiRoute adapterRoute, AiWorkflow preTurn, String message) {
    AiRoute route = clamp(routerRoute, adapterRoute);
    if (route == null) {
      return null;
    }
    if (route.scope() == AiScope.WORKLIST && route.operation() == AiWriteOperation.CREATE) {
      // A creation is about a record that does not exist yet, so it can never be "how much of the
      // list on screen". «Създай доставчик … ЕИК …» typed over the supplier list was routed
      // WORKLIST because a list was open, and then refused for not saying how much of the list it
      // covered. UNSCOPED, with no selection, is what it is: the server reads the new record's
      // identity from the message, exactly as from the dashboard.
      route =
          new AiRoute(
              route.domain(),
              route.task(),
              route.goal(),
              AiScope.UNSCOPED,
              route.explicitness(),
              null,
              null,
              null,
              route.operation());
    }
    AiExplicitness explicitness = route.explicitness();
    if (explicitness == AiExplicitness.EXPLICIT && AiWorkflowIntent.isBareAffirmation(message)) {
      explicitness = AiExplicitness.INHERITED;
    }
    if (explicitness == AiExplicitness.INHERITED && !inheritable(preTurn, route)) {
      explicitness = AiExplicitness.IMPLIED;
    }
    // withExplicitness, not a fresh five-field route: the selection, count and filter are part of
    // this answer too, and rebuilding without them would quietly turn a COUNT of two into "nothing
    // stated", which AiWorkflowController then refuses.
    return explicitness == route.explicitness() ? route : route.withExplicitness(explicitness);
  }

  private static boolean inheritable(AiWorkflow preTurn, AiRoute route) {
    return preTurn != null
        && preTurn.isActive()
        && AiRouteAdapter.compatibleWorkflow(preTurn, route.domain()) != null;
  }

  /** What a verified CONTINUE is bound to — see {@link #continuationBinding}. */
  enum Binding {
    /** Not verified: the adapter's route is used. */
    NONE,
    /** The active server-side job, with its own frozen targets. */
    JOB,
    /** The record on screen, proven to be the one the last proposal was made on. */
    OPEN_RECORD
  }

  /** Reason token on the {@code ai.router} line when the request could not fit its budget. */
  static final String REASON_OVER_BUDGET = "over_budget";

  /** {@code aiaccountant.router.request.token.budget}, or its default without a configuration. */
  protected int routerRequestTokenBudget() {
    try {
      return config.routerRequestTokenBudget();
    } catch (RuntimeException e) {
      return AiAccountantConfig.DEFAULT_ROUTER_REQUEST_TOKEN_BUDGET;
    }
  }

  /** {@code aiaccountant.router.output.reserve.tokens}, or its default without a configuration. */
  protected int routerOutputReserveTokens() {
    try {
      return config.routerOutputReserveTokens();
    } catch (RuntimeException e) {
      return AiAccountantConfig.DEFAULT_ROUTER_OUTPUT_RESERVE_TOKENS;
    }
  }

  /**
   * Whether Java can confirm a CONTINUE answer is about the record it claims to be about.
   *
   * <h2>Why this exists, deterministically, outside the router</h2>
   *
   * The router is never shown a record id — see {@code AiRouterContract}'s own rule about that — so
   * it cannot itself confirm that the record on screen now is the one its proposal was made on.
   * Trusting it anyway would let a stale continuation apply: read invoice A, get up, open invoice
   * B, say «да» — and CONTINUE would describe a write against A while the page, and every other
   * check downstream, is scoped to B. This is the one check standing between a verified
   * continuation and a guessed one.
   *
   * <h2>An explicit reference outranks everything else here</h2>
   *
   * If this turn's own words named a record in database-id syntax — {@code adapterRoute.scope() ==
   * NAMED_RECORDS} — that is the one thing that may override a continuation, so it is never
   * verified: the ordinary route/target resolution, which already handles {@code NAMED_RECORDS}
   * correctly, decides instead.
   *
   * <h2>An active server-side job outranks the page</h2>
   *
   * Failing that, an active {@code preTurn} with a real, frozen, canonical target set is
   * authoritative on its own — the page having moved on is not, by itself, evidence the
   * continuation is about something else, for the same reason {@code AiWorkflowController.open} no
   * longer treats page navigation alone as ending a job. Only with no active job to check against
   * at all does this fall back to comparing the page's open record against the single last
   * exchange's {@code recordRef}, exactly as before.
   *
   * <p>False whenever nothing here can be established. The conservative answer is always "not
   * verified", which falls back to the adapter's ordinary route, never a wrong write.
   */
  protected boolean verifiedTarget(
      PageContext page,
      AiWorkflow preTurn,
      AiTurnMemoryStore.Reading previousTurn,
      AiRoute adapterRoute) {
    return continuationBinding(page, preTurn, previousTurn, adapterRoute, null) != Binding.NONE;
  }

  /**
   * {@link #verifiedTarget}, saying WHAT verified it — because that, and not the router's own
   * scope, is what the continuation may then act on.
   *
   * <h2>Verified by a job means bound to the job</h2>
   *
   * Verification used to answer only yes or no, and a "yes" from the active job then travelled on
   * with the router's own scope — OPEN_RECORD, as the contract told it to say — so the turn froze
   * whatever record was on screen. Read A, open B, say «да»: verified (A's job is active), scoped
   * to B, written to B. Returning {@link Binding#JOB} lets {@link #select} rescope the continuation
   * to the job itself, and {@code AiWorkflowController.open} then resumes the job's own targets.
   *
   * <p>A job verifies only a continuation about its own kind of document ({@link
   * AiRouteAdapter#compatibleWorkflow} against the continued route's domain): one for another
   * domain would be filtered out of the turn before it could be resumed, leaving a "verified"
   * continuation with nothing behind it. That case falls through to the page check below.
   *
   * @param continued the router's continued route, or null when only the yes/no answer is wanted
   */
  protected Binding continuationBinding(
      PageContext page,
      AiWorkflow preTurn,
      AiTurnMemoryStore.Reading previousTurn,
      AiRoute adapterRoute,
      AiRoute continued) {
    if (adapterRoute != null && adapterRoute.scope() == AiScope.NAMED_RECORDS) {
      return Binding.NONE;
    }
    if (preTurn != null
        && preTurn.isActive()
        && preTurn.hasClosedTargets()
        && (continued == null
            || AiRouteAdapter.compatibleWorkflow(preTurn, continued.domain()) != null)) {
      return Binding.JOB;
    }
    if (previousTurn == null || !previousTurn.hasOffer() || previousTurn.recordRef() == null) {
      return Binding.NONE;
    }
    if (page == null || !page.hasRecord()) {
      return Binding.NONE;
    }
    String open = AiWorkflowController.shortRef(page.getModel(), page.getRecordId());
    try {
      return sameTarget(previousTurn.recordRef(), open) ? Binding.OPEN_RECORD : Binding.NONE;
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not compare the continuation's target: {}", e.getMessage());
      return Binding.NONE;
    }
  }

  /** The canonical comparison, as a seam so a test needs no Guice injector. */
  protected boolean sameTarget(String a, String b) {
    return Beans.get(AiTargetRegistry.class).sameTarget(a, b);
  }

  /**
   * Caps a verified CONTINUE at {@code INHERITED}.
   *
   * <p>{@link #clamp} caps an ordinary ROUTE answer at whatever the adapter's lexical reading of
   * THIS message already allows — which is the right ceiling when the fields describe a fresh
   * reading of the message. CONTINUE's fields describe last turn's proposal instead, so the
   * adapter's reading of the words «да» is not a fact about them; capping against it would refuse
   * the very thing this status exists to allow. The ceiling that still has to hold is the one
   * {@code AiWorkflowIntent}'s own javadoc names: an affirmation is never, by itself, the message
   * that authorises a change — it only ever inherits one. So EXPLICIT is refused here exactly as it
   * is in {@link #clamp}, on the same reasoning, against a different ceiling.
   */
  static AiRoute clampContinuation(AiRoute routerRoute, AiScope boundScope) {
    AiRoute capped = clampContinuation(routerRoute);
    if (boundScope == null || capped.scope() == boundScope) {
      return capped;
    }
    return capped.withScope(boundScope);
  }

  static AiRoute clampContinuation(AiRoute routerRoute) {
    if (routerRoute == null) {
      return AiRoute.unknown();
    }
    if (rank(routerRoute.explicitness()) <= rank(AiExplicitness.INHERITED)) {
      return routerRoute;
    }
    return routerRoute.withExplicitness(AiExplicitness.INHERITED);
  }

  /**
   * How much write authority each value carries, which is the only ordering that matters here.
   *
   * <p>{@code EXPLICIT} passes {@code checkAuthorisation} outright; {@code INHERITED} passes it
   * when a job is already on record; the other two never do.
   */
  private static int rank(AiExplicitness explicitness) {
    if (explicitness == null) {
      return 0;
    }
    switch (explicitness) {
      case EXPLICIT:
        return 3;
      case INHERITED:
        return 2;
      case IMPLIED:
        return 1;
      default:
        return 0;
    }
  }
}
