package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.client.FoundryResponsesClient;
import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.context.AiRequestContext;
import com.axelor.apps.aiaccountant.context.AiTurnContextBuilder;
import com.axelor.apps.aiaccountant.dto.AiConversationState;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.ChatReply;
import com.axelor.apps.aiaccountant.dto.ChatRequest;
import com.axelor.apps.aiaccountant.dto.FoundryResponse;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.metrics.AiTurnMetrics;
import com.axelor.apps.aiaccountant.prompt.AiAccountantMessages;
import com.axelor.apps.aiaccountant.prompt.AiInstructionSet;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.resilience.AiChainResetReason;
import com.axelor.apps.aiaccountant.resilience.AiContextBudget;
import com.axelor.apps.aiaccountant.resilience.AiResiliencePolicy;
import com.axelor.apps.aiaccountant.resilience.AiTurnDeadline;
import com.axelor.apps.aiaccountant.resilience.AiUncertainWrites;
import com.axelor.apps.aiaccountant.route.AiExplicitReferenceParser;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiRouteAdapter;
import com.axelor.apps.aiaccountant.router.AiRouteSelection;
import com.axelor.apps.aiaccountant.router.AiRouteSelector;
import com.axelor.apps.aiaccountant.router.AiRouteShadow;
import com.axelor.apps.aiaccountant.router.AiRouterLog;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiTurnReadMemory;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.tool.AiToolBatchRunner;
import com.axelor.apps.aiaccountant.tool.AiToolDispatcher;
import com.axelor.apps.aiaccountant.tool.AiTurnToolLog;
import com.axelor.auth.AuthUtils;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drives one chat turn to completion.
 *
 * <p>The loop: send input → if the response contains {@code function_call} items, execute them and
 * send the {@code function_call_output} items back with {@code previous_response_id} → repeat until
 * the model returns only messages, or the iteration cap is hit.
 *
 * <h2>What this class does, and what it delegates</h2>
 *
 * It coordinates a turn and nothing else: resolve the incoming state, decide whether to continue or
 * reset the Azure chain, build the input, call the Responses API, run the tool calls, judge whether
 * the work is finished, persist the bounded continuation state, and answer.
 *
 * <ul>
 *   <li>what the model is <em>told</em> — {@code AiTurnContextBuilder} and {@code
 *       AiOperatingProcedure};
 *   <li>how a batch of tool calls is ordered and aborted — {@code AiToolBatchRunner};
 *   <li>whether a claim or a completion may be asserted — {@link AiTurnTruthCheck} and {@link
 *       AiWorkflowIntent};
 *   <li>fixed wording — {@code AiAccountantMessages}.
 * </ul>
 *
 * <p>Stateless by design: no per-user map, no session. The client owns {@code responseId} and the
 * conversation-state blob and sends both back, so conversation history lives in Azure and two users
 * simply carry two different ids.
 */
@Singleton
public class AiAccountantServiceImpl implements AiAccountantService {

  private static final Logger LOG = LoggerFactory.getLogger(AiAccountantServiceImpl.class);

  /**
   * The chain measurement store used when no injector is available.
   *
   * <p>Static because it holds no configuration: without an injector it finds no rows, which reads
   * as "no measurement" and resets the chain — the same conservative answer every other unknown
   * produces here.
   */
  private static final AiChainBudgetStore DEFAULT_CHAIN_BUDGET = new AiChainBudgetStore();

  /** As above, for the last-exchange store: no injector, no rows, nothing to continue. */
  private static final AiTurnMemoryStore DEFAULT_TURN_MEMORY = new AiTurnMemoryStore();

  private final FoundryResponsesClient client;
  private final AiAccountantConfig config;
  private final AiToolDispatcher dispatcher;
  private final AiToolBatchRunner batchRunner;

  /**
   * The policy and budget to fall back on, built from <em>this service's own</em> configuration.
   *
   * <h2>Why they are not static</h2>
   *
   * They were, and it was wrong. A static instance builds its own {@code AiAccountantConfig} and
   * reads {@code AppSettings} directly, so the fallback answered from the deployment's settings
   * while the service beside it answered from the configuration it had actually been given. The two
   * silently disagreed wherever a caller supplied a config of its own — and the iteration cap is
   * read through the policy, so a service configured for three round trips would take five.
   *
   * <p>Built from the injected config instead, so the fallback and the injected singleton give the
   * same answers for the same deployment, and a caller that configures this service configures
   * everything it consults.
   */
  private final AiResiliencePolicy fallbackPolicy;

  private final AiContextBudget fallbackBudget;

  private final AiTurnContextBuilder contextBuilder = new AiTurnContextBuilder();
  private final ObjectMapper mapper = new ObjectMapper();

  @Inject
  public AiAccountantServiceImpl(
      FoundryResponsesClient client, AiToolDispatcher dispatcher, AiAccountantConfig config) {
    this.client = client;
    this.config = config;
    this.dispatcher = dispatcher;
    this.batchRunner = new AiToolBatchRunner(dispatcher);
    this.fallbackPolicy = new AiResiliencePolicy(config);
    this.fallbackBudget = new AiContextBudget(config);
  }

  /**
   * Configured, and with a tool registry that actually resolves.
   *
   * <p>The registry half was missing: a failed discovery (a blank or duplicate tool name) left the
   * panel reporting the assistant available, every turn reached the model, and the first tool call
   * then failed inside the tool loop. Discovery is retried on every access rather than cached as a
   * failure, so a fixed deployment reports available again without anything else changing.
   */
  @Override
  public boolean isAvailable() {
    return config.isEnabled() && config.isChatConfigured() && toolRegistryProblem() == null;
  }

  /**
   * Why the tool registry cannot be used, or null when it can. Never throws: a registry that cannot
   * even be asked is reported as a problem, not as a crash of the availability check.
   */
  protected String toolRegistryProblem() {
    try {
      return dispatcher == null ? null : dispatcher.registryProblem();
    } catch (RuntimeException e) {
      return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }
  }

  @Override
  public List<AiChatTranscriptStore.Message> history() {
    // Same resolution as everywhere else: the session says who, the company scope says which books.
    // Nothing here is taken from the caller, so there is no request one user could shape to read
    // another's chat, or their own chat in a company they are not working in.
    Long userId = AuthUtils.getUser() == null ? null : AuthUtils.getUser().getId();
    Long companyId = AiCompanyScope.companyIdOrNull();
    if (userId == null || companyId == null) {
      return Collections.emptyList();
    }
    // Deliberately unguarded: a read that failed and a conversation that is empty are opposite
    // facts, and the panel shows a load error with a Retry for the first rather than an empty
    // transcript that looks like the second. The REST layer turns this into that error.
    return transcript().load(userId, companyId);
  }

  @Override
  public boolean deleteConversation() {
    if (!config.isEnabled()) {
      return false;
    }
    // Resolved here, from the authenticated session and the active company scope — never from a
    // value the client could set, or "Delete conversation" would be able to erase another user's
    // rows, or another company's, by sending a different id in the request body.
    Long userId = AuthUtils.getUser() == null ? null : AuthUtils.getUser().getId();
    Long companyId = AiCompanyScope.companyIdOrNull();
    if (userId == null || companyId == null) {
      LOG.info("AI Accountant: cannot delete a conversation without a user and a company.");
      return false;
    }
    try {
      Beans.get(AiConversationEraser.class).erase(userId, companyId);
      return true;
    } catch (RuntimeException e) {
      // No retry: the caller must not be told the conversation is gone when the transaction did
      // not commit. The rows survive to be deleted on a later attempt.
      LOG.warn(
          "AI Accountant: could not delete the conversation of user {} in company {}: {}",
          userId,
          companyId,
          e.getMessage());
      return false;
    }
  }

  @Override
  public ChatReply chat(ChatRequest request) {
    if (!config.isEnabled()) {
      return ChatReply.error("AI Accountant is disabled.");
    }
    if (!config.isChatConfigured()) {
      return ChatReply.error("AI Accountant is not configured.");
    }
    // Before the transcript, the router and the model: a turn the tool loop cannot serve must not
    // spend a model call to discover that at its first tool request. The detail is the operator's
    // (it names classes and jars) and goes to the log; the user gets a plain sentence.
    String registryProblem = toolRegistryProblem();
    if (registryProblem != null) {
      LOG.error(
          "AI Accountant: refusing a chat turn — the tool registry is invalid: {}",
          registryProblem);
      return ChatReply.error(AiAccountantMessages.TOOL_REGISTRY_INVALID_EN);
    }
    if (request == null || request.getMessage() == null || request.getMessage().trim().isEmpty()) {
      return ChatReply.error("Message is empty.");
    }
    String message = request.getMessage().trim();
    if (message.length() > config.maxMessageChars()) {
      return ChatReply.error("Message is too long (limit " + config.maxMessageChars() + " chars).");
    }

    AiConversationState incoming = AiConversationState.parse(request.getState());

    // Who and where, resolved before anything else runs, because the visible transcript is written
    // from them and that write has to happen first — see the append below. Held outside the try so
    // the error path can record what the user saw.
    Long userId = AuthUtils.getUser() == null ? null : AuthUtils.getUser().getId();
    Long companyId = AiCompanyScope.companyIdOrNull();
    // Which chat panel this turn came from. A user in one company with two tabs open is two
    // conversations, and they share neither a model chain nor an authorisation. Null when the
    // client sent nothing usable, which resumes nothing.
    String conversationId = AiConversationKey.of(request.getConversationId());
    String turnKey = blankToNull(request.getTurnKey());

    // Is this instruction still about the books the user was reading when they wrote it?
    //
    // The panel states the company its transcript was loaded for; this compares it with the company
    // actually in force now, and refuses the turn when they differ. It runs FIRST — before the
    // message is stored, before routing, before the model, before any tool — because the whole
    // point
    // is that nothing may execute. Everything downstream would agree the user is entitled to act in
    // the new company, which is exactly why none of it can catch this: the question is not whether
    // they may, it is whether they meant to.
    //
    // The client's value is only ever compared. Scope still comes from AiCompanyScope and nowhere
    // else, so a caller who sends another company's id gains nothing but a refusal of their own
    // turn.
    //
    // First: did it state a company context at all? A request that did not has proved nothing about
    // which conversation the user was reading, and "it did not say" must not be read as "there is
    // nothing to contradict" — that permissive reading is the hole this check exists to close. It
    // is
    // refused, and the client is told to reload rather than to resend, because a bundle that cannot
    // state its context now will not be able to state it on the next attempt either.
    if (!request.isCompanyContextLoaded()) {
      LOG.warn(
          "AI Accountant: refusing a turn that carries no company context (user {}, conversation"
              + " {}). Nothing was executed.",
          userId,
          conversationId);
      return ChatReply.companyMismatch(AiAccountantMessages.COMPANY_CONTEXT_MISSING_EN, companyId);
    }
    if (companyChanged(request, companyId)) {
      LOG.warn(
          "AI Accountant: refusing a turn written for company {} while company {} is active"
              + " (user {}, conversation {}). Nothing was executed.",
          request.getExpectedCompanyId(),
          companyId,
          userId,
          conversationId);
      return ChatReply.companyMismatch(AiAccountantMessages.COMPANY_CHANGED_EN, companyId);
    }

    // The user's own words, committed BEFORE the model is called and before any tool runs.
    //
    // The order matters and is the point of this line. A turn that executed first and stored
    // afterwards could write to the books and then fail to record that anybody asked it to, leaving
    // an accountant with a changed invoice and a chat that never mentions it. So if the transcript
    // cannot take the message, nothing happens at all: no routing, no model call, no tool, and a
    // plain error the user can act on. Unlike its neighbours this store throws rather than
    // returning false — see AiChatTranscriptStore.
    try {
      if (scoped(userId, companyId)) {
        transcript()
            .append(
                userId,
                companyId,
                conversationId,
                turnKey,
                AiChatTranscriptStore.ROLE_USER,
                message,
                null);
      }
    } catch (AiTranscriptPersistenceException e) {
      LOG.error("AI Accountant: refusing to run a turn whose message could not be stored.", e);
      return ChatReply.error(AiAccountantMessages.TRANSCRIPT_UNAVAILABLE_EN);
    }

    ChatReply reply;
    // Collect what this turn commits, so the reply can tell the client which open record is stale.
    // Bound here rather than inside runTurn: it has to outlive the tool loop and be released even
    // when a tool throws.
    AiWriteEffects.begin();
    // Remember which reads this turn has already answered, so an identical repeat costs nothing.
    // Same lifetime and the same reason as above: it must span the whole tool loop and be released
    // even when a tool throws.
    AiTurnReadMemory.begin();
    // Same window again: the model requests and the tool calls they interleave with are one turn,
    // and a failed turn is exactly the kind that has to be measured.
    AiTurnMetrics.begin(blankToNull(request.getResponseId()) != null);
    // What each requested tool call actually did. Same window as the three above, and for the same
    // reason: the reply is assembled from it after the tool loop has finished.
    AiTurnToolLog.begin();
    // The clock the whole turn runs against. Per-request timeouts bound one call and say nothing
    // about a turn that makes five of them with retries in between; this is the bound the person
    // waiting actually experiences, and everything that is about to spend time reads it.
    AiTurnDeadline.begin(policy().turnDeadlineMillis());
    // Records this turn wrote to and cannot account for. Same window again: it is consulted after
    // the tool loop, by the reply, the workflow and the metric line.
    AiUncertainWrites.begin();
    try {
      AiTurnMetrics.pageContext(
          request.getContext() == null ? "none" : request.getContext().kind(),
          AiWorkflowIntent.delegatesSelection(message));

      // userId, companyId and conversationId were resolved above, before the transcript write that
      // has to precede any execution. Everything below is keyed on them: the store, the workflow
      // this turn belongs to, and the pre-turn read the shadow comparison is taken from. An
      // authorisation that could not say whose it is or which books it was given in would not be
      // one.
      // A blob that is not provably this conversation's is read as absent, not as evidence. That
      // covers both a blob stamped by another panel and a legacy blob with no stamp at all: the
      // second cannot be attributed to anything, and an authorisation nobody can attribute is not
      // one. The client keeps showing its transcript either way — what is dropped here is the
      // chain, the job and the «същото» shape, which is a conversation boundary rather than a loss.
      if (incoming != null
          && !AiConversationKey.same(incoming.getConversationId(), conversationId)) {
        LOG.info(
            "AI Accountant: ignoring conversation state stamped {} on a turn belonging to {}.",
            incoming.getConversationId() == null ? "(none)" : incoming.getConversationId(),
            conversationId == null ? "(none)" : conversationId);
        incoming = null;
      }

      // The job as it stood BEFORE this message was read, from the row and from nowhere else.
      // Loaded unconditionally now, not only when the shadow router is watching: it is the sole
      // authority for what is resumable, so the route, the task, the goal and the continuity
      // handoff are all resolved from this one read. One lookup, one answer — three lookups of the
      // same row would be three chances for them to disagree about what was running.
      AiRouteShadow shadow = shadow();
      AiWorkflow preTurn = workflowController().preTurnSnapshot(userId, companyId, conversationId);

      // What this turn is — domain, task, destination, scope and how firmly any of it was said —
      // resolved once, from the resolvers that were already deciding each part separately. The
      // adapter is the seam the Router Agent will slot into; nothing dispatches on the route yet.
      // «Продължи» inherits a goal only when the SERVER has a job to inherit it from.
      // Records the user named themselves, in unmistakable id syntax, read once from the raw
      // message before anything else looks at it. Two things are given this same list — routing,
      // which only counts it to choose a scope, and the workflow controller, which validates it
      // against the company and freezes what may be written. Parsing it twice would let those two
      // disagree about the same sentence. Nothing the model later puts in a tool argument can add
      // to it: this is the user's own text, read before the model runs.
      List<String> namedRecords = AiExplicitReferenceParser.parse(message);

      AiRoute route =
          AiRouteAdapter.resolve(message, request.getContext(), incoming, preTurn, namedRecords);

      // Production v1. In active mode the Router Agent decides, synchronously, and the adapter
      // route above becomes the fallback it is measured against. The router never ends the turn:
      // CLARIFICATION, INVALID, FAILED, a malformed reply or a timeout all leave `route` on the
      // adapter's own resolution, and the turn always reaches the main agent below — deciding
      // whether the user needs to be asked anything is now entirely the main agent's job, made
      // with the full conversation and this turn's own tool results in hand.
      // What the server remembers of this chat panel's last exchange — the one thing that lets a
      // bare affirmation with no job on record be recognised as continuing a concrete change the
      // assistant proposed on a plain read, rather than routing cold. Read once, here, so the
      // router and the reply built later from the same turn see the identical exchange.
      AiTurnMemoryStore.Reading previousTurn = loadTurnMemory(userId, companyId, conversationId);
      // The complete stored conversation for this chat panel, oldest first — see
      // AiTurnMemoryStore#loadHistory. Sent to the Router alongside previousTurn so it can resolve
      // a reference that reaches back further than one exchange; never sent to the main agent,
      // which already has its own, richer memory of the conversation via the Azure chain.
      List<AiTurnMemoryStore.Reading> turnHistory =
          loadTurnHistory(userId, companyId, conversationId);

      // Set only once the selector has both chosen CONTINUE and Java has verified it against the
      // record on screen — see AiRouteSelector.verifiedTarget. Carried to the model as the block
      // that tells it what «да» is confirming, because that fact does not survive a chain reset
      // and, on the common turn where the chain IS intact, costs nothing beyond what the model
      // already has from its own previous reply.
      String continuationEcho = null;

      AiRouteSelector selector = routeSelector();
      if (selector != null && selector.isActive()) {
        // Never ends the turn any more: CLARIFICATION, INVALID, FAILED, a malformed reply or a
        // timeout all resolve to a route (the router's when usable, the adapter's otherwise) and
        // the turn always reaches the main agent below — see AiRouteSelection and AiRouteSelector.
        AiRouteSelection selection =
            selector.select(
                message,
                request.getContext(),
                preTurn,
                companyId,
                route,
                previousTurn,
                turnHistory,
                namedRecords.size());
        route = selection.route();
        if (AiRouterLog.USED_ROUTER_CONTINUATION.equals(selection.used())) {
          continuationEcho = continuationEcho(previousTurn);
        }
      }

      // The router's question, frozen from the same three things the adapter just read: this
      // message, this page and company, and the pre-turn job above. Captured before the workflow
      // is opened, because opening it turns the adapter's reading of this message into state — and
      // a router shown the adapter's conclusions is not being compared to the adapter, it is being
      // marked on how well it echoes it. Null when the router is off, which observe ignores.
      String shadowInput =
          shadow == null ? null : shadow.capture(message, request.getContext(), preTurn, companyId);

      // The destination the user's words name, resolved before the turn runs so it is recorded
      // even when the turn fails. A continuation inherits the goal of the workflow it resumes.
      AiWorkflowIntent.Stage goal = route.goal();
      AiTurnMetrics.resolvedGoal(goal == null ? "NONE" : goal.name());

      endWorkflowsOfOtherCompanies(request, userId, companyId);

      // The job this turn belongs to, opened before it runs so a continuation inherits its goal and
      // target set rather than re-deriving them from a word that carries neither. Given the same
      // pre-turn row the route was resolved from, rather than loading it again — but filtered
      // through route.domain(), the SAME comparison AiRouteAdapter itself just made: a row about a
      // document the selected route disagrees with must not be resumed here either, or this call
      // would silently re-attach the turn to the wrong job's targets while the route above had
      // already decided not to. The row itself is untouched; only whether THIS turn may resume it.
      // A bare confirmation ("да", "дадада създай доставчика") carries no destination of its own —
      // see AiWorkflowIntent.isContinuation's own javadoc — so before falling back to the ordinary
      // routing above, this checks for the one thing a "yes" IS allowed to confirm: a single,
      // structured proposal a trusted read tool derived from stored evidence a turn earlier. See
      // AiWorkflowController.promoteIfConfirmed — null on any turn this does not apply to, which is
      // every turn before this existed.
      AiWorkflowController.Promotion promotion =
          workflowController()
              .promoteIfConfirmed(message, namedRecords, userId, companyId, conversationId);
      AiWorkflow workflow;
      if (promotion != null) {
        workflow = promotion.workflow();
        route = promotion.route();
      } else {
        workflow =
            workflowController()
                .open(
                    message,
                    route,
                    request.getContext(),
                    AiRouteAdapter.compatibleWorkflow(preTurn, route.domain()),
                    companyId,
                    conversationId,
                    namedRecords);
      }

      // Who is asking, in which company, looking at what, in the middle of which job. Bound for the
      // turn so everything downstream — the tool dispatcher included — reads one request rather
      // than resolving each fact again for itself. Released in the same finally as its neighbours.
      AiRequestContext.begin(
          AiRequestContext.of(
              message, request.getContext(), workflow, route, conversationId, continuationEcho));

      // Phase 3, shadow only. The question captured before the workflow was opened is sent on a
      // background thread and the answer compared to the adapter route and logged. Nothing reads
      // the result: the turn continues on the adapter's route exactly as it did before this line
      // existed, and the call adds no latency because the turn does not wait for it.
      if (shadow != null) {
        shadow.observe(shadowInput, route);
      }

      // The job goes in, so a chain reset rebuilds the model's picture of the work from the row the
      // server owns rather than from the blob the browser sent back.
      reply = runTurn(request, message, incoming, workflow);
      com.axelor.apps.aiaccountant.tool.AiToolDebugLog.recordTurn(
          message, route, workflow, reply.getToolsUsed(), reply.getReply());
      reply.setWriteResult(writeResult());
      AiTurnMetrics.bulkTargets(AiWriteEffects.snapshot().size());
      recordGoalProgress(goal, reply);
      // Folded in after the tool loop, when what was written and what was refused are both known,
      // and then written down: the next turn's authorisation is read from the row, not from the
      // blob the browser happens to send back.
      workflowController().update(workflow, goal, route, userId, companyId, conversationId);
      reply.setReply(withWorkflowStatus(reply.getReply(), workflow));
      // Built here rather than in runTurn so every exit path — answered, finished-at-the-cap,
      // genuinely truncated — carries it, and so it is assembled after the tool loop, when what was
      // written is known.
      // What this turn measured, written where the next turn's safety decision can read it. Done
      // after the tool loop, from the provider's own usage figures, and before the blob is built —
      // the blob still carries the same numbers, but only so the next turn can notice a
      // disagreement, never so it can decide from them.
      recordChainMeasurement(userId, companyId, conversationId, AiTurnMetrics.measuredChain());
      // What a later «да» would be continuing: this exchange, and the record the page had open
      // while it was answered. Recorded on every turn, not only a write — a plain read that
      // proposes a fix is exactly the case this exists for — and appended to the stored
      // conversation rather than overwriting it, so the Router can read the whole thing back; see
      // AiTurnMemoryStore.
      recordTurnMemory(
          userId, companyId, conversationId, message, reply.getReply(), request.getContext());
      reply.setState(
          outgoingState(message, incoming, reply.isTruncated(), workflow, conversationId));
      // Stated last, over the top of everything else the reply says, because it is the one thing
      // that cannot be left to the model's account of the turn: a record whose write was lost.
      reply.setReply(withUncertaintyNotice(reply.getReply()));
      // The answer, committed before this turn is reported as a success.
      //
      // Unlike the user's message above, a failure here cannot abort anything: the tool loop has
      // already run and may have written to the books, and discarding the reply would hide work
      // that actually happened. So the reply is returned and says so itself, in the same way an
      // uncertain write does — the user is told this answer was not saved and will not be here
      // after a refresh, which is a fact they can act on, rather than discovering it silently
      // tomorrow.
      reply.setReply(
          withTranscriptNotice(
              reply.getReply(),
              storeAssistantMessage(
                  userId,
                  companyId,
                  conversationId,
                  turnKey,
                  reply.getReply(),
                  reply.getToolsUsed())));
      AiTurnMetrics.outcome(
          AiUncertainWrites.any() ? "uncertain" : reply.isTruncated() ? "truncated" : "ok");
    } catch (RuntimeException e) {
      AiTurnMetrics.outcome("error");
      // The error bubble the panel is about to show, recorded so the stored transcript matches what
      // the user saw rather than ending on their unanswered question.
      //
      // Guarded separately and on purpose: a failure to store the error must never replace the
      // error. It is attached to the original and the original is what propagates, so the user is
      // told what actually went wrong with their turn and the log still carries both.
      try {
        if (scoped(userId, companyId)) {
          transcript()
              .append(
                  userId,
                  companyId,
                  conversationId,
                  turnKey,
                  AiChatTranscriptStore.ROLE_ERROR,
                  e.getMessage(),
                  null);
        }
      } catch (RuntimeException storeFailure) {
        LOG.error(
            "AI Accountant: could not store the error message of a failed turn.", storeFailure);
        e.addSuppressed(storeFailure);
      }
      throw e;
    } finally {
      AiRequestContext.end();
      AiTurnMetrics.end();
      AiUncertainWrites.end();
      AiTurnDeadline.end();
      AiTurnToolLog.end();
      AiTurnReadMemory.end();
      AiWriteEffects.end();
    }
    return reply;
  }

  /**
   * The deadlines and attempt bounds this turn runs under.
   *
   * <p>Looked up rather than injected so a directly-constructed service — which the tests build —
   * behaves the same, and defensively, because a missing injector must never be the reason a turn
   * fails before it starts. The fallback reads the same configuration keys.
   */
  protected AiResiliencePolicy policy() {
    try {
      return Beans.get(AiResiliencePolicy.class);
    } catch (RuntimeException e) {
      return fallbackPolicy;
    }
  }

  /**
   * Appends the one statement the model is in no position to make.
   *
   * <h2>Why it is unconditional and why it goes last</h2>
   *
   * A lost write is the only outcome in this system about which nothing may be asserted — not
   * success, not failure, not "nothing was saved". The model has been told that in the tool result,
   * and it usually relays it; but "usually" is not a guarantee, and the consequence of it being
   * dropped is an accountant who never learns that an invoice may or may not be in the books. So
   * the server states it itself, from the register rather than from the reply, exactly as the
   * workflow status line is stated from the workflow.
   *
   * <p>Appended, never substituted: everything else the turn did is still worth reading, and most
   * of a batch usually succeeded.
   */
  protected String withUncertaintyNotice(String modelText) {
    try {
      if (!AiUncertainWrites.any()) {
        return modelText;
      }
      String notice =
          (AiTurnTruthCheck.isBulgarian(modelText)
                  ? AiAccountantMessages.UNCERTAIN_WRITE_BG
                  : AiAccountantMessages.UNCERTAIN_WRITE_EN)
              + String.join(", ", AiUncertainWrites.snapshot())
              + ".";
      LOG.error(
          "AI Accountant: turn ended with {} record(s) of unknown write outcome: {}.",
          AiUncertainWrites.snapshot().size(),
          AiUncertainWrites.snapshot());
      if (modelText == null || modelText.trim().isEmpty()) {
        return notice;
      }
      return modelText.trim() + "\n\n" + notice;
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not state the uncertain outcome: {}", e.getMessage());
      return modelText;
    }
  }

  /**
   * Ends this user's jobs in other companies when the active company has changed.
   *
   * <p>The chain is already dropped on a company change, so the model loses its context; the
   * authorisation has to go with it. An unfinished job in the company just left would otherwise sit
   * there and be resumed the moment the user switched back — days later, on invoices they have long
   * since dealt with by hand.
   *
   * <h2>Detected server-side, not from the stamp alone</h2>
   *
   * It used to be detected only from the company stamp the client returns with the chain — which
   * meant a client that simply omitted the field suppressed the whole thing, leaving jobs open in a
   * company the user had left. The stamp is still read, because it is the earliest signal, but it
   * is no longer the only one: a chain measurement for this same conversation under a
   * <em>different</em> company is a fact the server recorded itself, and it says the same thing.
   *
   * <p>Either signal is enough. Neither can be suppressed by omitting the other.
   */
  protected void endWorkflowsOfOtherCompanies(ChatRequest request, Long userId, Long companyId) {
    try {
      if (userId == null || companyId == null) {
        return;
      }
      Long stamped = request.getChainCompanyId();
      boolean clientSaysMoved = stamped != null && !stamped.equals(companyId);
      boolean serverSaysMoved =
          clientSaysMoved
              || chainBudget()
                  .conversationSeenInAnotherCompany(
                      userId, companyId, AiConversationKey.of(request.getConversationId()));
      if (!serverSaysMoved) {
        return;
      }
      Beans.get(AiWorkflowStore.class).abandonOtherCompanies(userId, companyId);
      // A company switch is itself an intervening event a pending proposal must not survive — see
      // AiPendingProposalStore#consumeOtherCompanies.
      Beans.get(AiPendingProposalStore.class).consumeOtherCompanies(userId, companyId);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not end workflows of other companies: {}", e.getMessage());
    }
  }

  /**
   * One model request, timed and accounted for.
   *
   * <p>Wrapped rather than measured inside the client so the numbers cover what the turn actually
   * waited for — token acquisition, retries and backoff included. A turn that spent nine seconds
   * retrying a 429 waited nine seconds, whatever the successful attempt took.
   */
  protected FoundryResponse callModel(String previousResponseId, JsonNode input) {
    long started = System.nanoTime();
    try {
      FoundryResponse response = client.createResponse(previousResponseId, input);
      AiTurnMetrics.modelRequest((System.nanoTime() - started) / 1_000_000L, response);
      return response;
    } catch (RuntimeException e) {
      AiTurnMetrics.modelRequestFailed((System.nanoTime() - started) / 1_000_000L);
      throw e;
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Conversation state
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * The conversation state the client stores and returns next turn.
   *
   * <h2>The three things it carries</h2>
   *
   * <p><b>Chain size</b>, always: the last request's input tokens, which is what the next turn's
   * threshold check reads. Measured, not estimated — Azure's own {@code usage} figure.
   *
   * <p><b>An unfinished authorisation</b>, only while a turn was cut short with work genuinely
   * outstanding. When «оправи, валидирай и осчетоводи следващите две» runs out of iterations after
   * the validations, the user has already authorised the posting; asking them to confirm it again
   * on «продължи» is the assistant forgetting, not the user changing their mind.
   *
   * <p><b>The shape of the last operation</b>, so «същото и с тази» has a referent that does not
   * depend on the conversation still holding the turn it refers to.
   *
   * <h2>When the authorisation clears</h2>
   *
   * On the first turn that is not cut short — whether it finished the work, failed, or went
   * somewhere else entirely. On a turn that hit the iteration cap but left every written record in
   * the state the user asked for: there is nothing to continue, and this is the case that let a
   * spent authorisation drift onto an unrelated invoice. New Conversation drops the blob outright,
   * and a company change starts a chain that never receives it.
   *
   * <p>Scope never widens. The authorisation names the records the workflow has touched, and rule
   * 16 and the continuity block both say it applies to those and to no others — a worklist is
   * selection context, not consent.
   */
  protected String outgoingState(
      String message,
      AiConversationState incoming,
      boolean unfinished,
      AiWorkflow workflow,
      String conversationId) {
    try {
      AiConversationState state = new AiConversationState();
      // Stamped so the next turn can tell its own blob from another tab's. Written even when it is
      // null, which is what a client with no conversation gets: a blob that matches nothing.
      state.setConversationId(conversationId);
      // The chain figures, and the one case where they must NOT be taken from this turn.
      //
      // A turn that never called the model — a router clarification, any early return — measured
      // nothing, so AiTurnMetrics reports 0. Writing that 0 into the blob makes the blob describe a
      // chain of zero tokens while the server's own row still holds the real figure from the last
      // turn that did call the model. Nothing updated that row, correctly: nothing happened to the
      // chain. But the next turn then compares 0 against it, finds a material gap, and resets with
      // MEASUREMENT_CONFLICT — a fail-closed answer to a disagreement this method invented.
      //
      // So an unmeasured turn carries the previous figures forward unchanged. That is not a
      // widened tolerance and not a client-supplied number being trusted: the server row remains
      // the sole authority and is still compared exactly, and this simply stops the blob asserting
      // a change that did not occur. A turn that did call the model always reports its own.
      int measured = AiTurnMetrics.lastInputTokens();
      if (measured > 0 || incoming == null) {
        state.setLastInputTokens(measured);
        // How much this turn added to the chain, so the next one can decide EARLY rather than one
        // turn late. Measured from the provider's own usage figures at both ends of the turn, not
        // estimated from the text.
        state.setChainGrowthTokens(AiTurnMetrics.chainGrowthTokens());
      } else {
        state.setLastInputTokens(incoming.getLastInputTokens());
        state.setChainGrowthTokens(incoming.getChainGrowthTokens());
      }
      carryShape(state, incoming, !unfinished);
      // The workflow is NO LONGER written into the blob. It used to be, so that a turn could resume
      // from it when no row existed — and that path is exactly what made a browser able to hand
      // itself a job. The job now lives only in AiWorkflowState; sending a copy the server will
      // never read again would be leaving the old door visible with nothing behind it.
      if (unfinished) {
        state.setPending(true);
        // On «продължи» the message itself authorises nothing; the instruction that did is the one
        // ALREADY ON RECORD — read from the stored job, not from the blob that came back. This is
        // an audit trail of what a person actually said, so its source has to be the copy the
        // server wrote.
        state.setRequest(
            AiWorkflowIntent.isContinuation(message) && workflow != null && workflow.isActive()
                ? workflow.getRequest()
                : message);
        state.setTouched(scope(workflow, message));
      }
      return state.toJson();
    } catch (RuntimeException e) {
      // The conversation must survive a state blob that cannot be built; the next turn simply has
      // no evidence to bound the chain on, which is how it behaved before this existed.
      LOG.debug("AI Accountant: could not build conversation state: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Records the operation sequence that just succeeded, or carries the previous one forward.
   *
   * <p>Carried forward, because a read-only turn in the middle of a session must not erase what
   * «същото» refers to. Replaced as soon as a new sequence succeeds, because the most recent one is
   * what the word means.
   */
  protected void carryShape(
      AiConversationState state, AiConversationState incoming, boolean succeeded) {
    Set<String> writes = AiTurnToolLog.successfulWrites();
    String shape = AiWorkflowIntent.shape(writes);
    if (shape != null) {
      state.setShape(shape);
      state.setShapeTarget(firstRef());
      state.setShapeOk(succeeded);
      return;
    }
    if (incoming != null && incoming.hasShape()) {
      state.setShape(incoming.getShape());
      state.setShapeTarget(incoming.getShapeTarget());
      state.setShapeOk(incoming.isShapeOk());
    }
  }

  /**
   * The records an unfinished authorisation applies to.
   *
   * <p>The union of what the workflow had already touched and what this turn touched — a
   * «следващите две» that gets through the first invoice and starts the second must keep both in
   * scope. It grows only with records the workflow itself wrote, never with records that merely
   * exist, and {@code AiConversationState} caps the list.
   *
   * <p>Taken from the stored job rather than from the returned blob. The list is a scope — the
   * records an authorisation applies to — so a caller able to supply it is a caller able to widen
   * what it covers, which is the one thing a scope must not permit.
   */
  protected List<String> scope(AiWorkflow workflow, String message) {
    Set<String> refs = new LinkedHashSet<>();
    if (AiWorkflowIntent.isContinuation(message) && workflow != null && workflow.isActive()) {
      refs.addAll(workflow.getTargets().stream().map(r -> r.getRef()).collect(Collectors.toList()));
    }
    for (AiWriteEffects.Ref ref : AiWriteEffects.snapshot()) {
      refs.add(shortRef(ref));
    }
    return new ArrayList<>(refs);
  }

  /** The first record this turn wrote, as {@code Invoice#592}, or null. */
  protected String firstRef() {
    List<AiWriteEffects.Ref> refs = AiWriteEffects.snapshot();
    return refs.isEmpty() ? null : shortRef(refs.get(0));
  }

  /** {@code Invoice#592} — the entity name without its package, which is all the model needs. */
  private static String shortRef(AiWriteEffects.Ref ref) {
    String model = ref.getModel() == null ? "?" : ref.getModel();
    int dot = model.lastIndexOf('.');
    return (dot < 0 ? model : model.substring(dot + 1)) + "#" + ref.getId();
  }

  /**
   * The records this turn committed, or {@code null} when it committed nothing.
   *
   * <p>Null rather than an empty object on purpose: the client treats the field's presence as
   * "there is something to refresh", and a turn that only read must not make anything reload.
   */
  protected ChatReply.WriteResult writeResult() {
    List<AiWriteEffects.Ref> touched = AiWriteEffects.snapshot();
    if (touched.isEmpty()) {
      return null;
    }
    ChatReply.WriteResult result = new ChatReply.WriteResult();
    result.setRefresh(true);
    List<ChatReply.Record> records = new ArrayList<>();
    for (AiWriteEffects.Ref ref : touched) {
      records.add(new ChatReply.Record(ref.getModel(), ref.getId()));
    }
    result.setRecords(records);
    LOG.info("AI Accountant: turn changed {}.", touched);
    return result;
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Chain continuation
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * The chain to continue, or null to start a new one because the active company changed.
   *
   * <p>Azure retains the whole conversation behind {@code previous_response_id} — every earlier
   * message, tool call and tool result. Nothing in the client resets that on a company switch (only
   * on sign-in as a different user), so one company's invoices stayed in the model's context while
   * the accountant worked in the next. Tools were never at risk — {@code AiCompanyScope} bounds
   * every one of them — but the model was still reasoning over documents it can no longer see, and
   * stale context is the kind of thing that produces a confidently wrong sentence.
   *
   * <p>Decided here rather than in the browser because the active company is resolved server-side;
   * the client only echoes back the id it was last told. A comparison on the company <em>id</em>,
   * never the display name: names are editable and duplicable, ids are not.
   *
   * <p>Deliberately not reset when the open record changes. That is the workflow — «също и тази»,
   * «следващата», «направи същото» all depend on the previous record still being in context.
   */
  protected String chainToContinue(ChatRequest request, Long currentCompanyId) {
    return chainDecision(request, currentCompanyId, null).responseId;
  }

  /** Whether to continue the Azure chain, and why not when the answer is no. */
  protected static final class ChainDecision {
    final String responseId;
    final AiChainResetReason reason;
    final int prevTokens;
    final int growthTokens;

    /**
     * The chain the measurement was read against, or null when none was read.
     *
     * <p>Carried to the end of the turn so the measurement it writes can be fenced: if another turn
     * of the same panel reset the conversation in the meantime, this turn's figures describe a
     * chain that no longer exists and must not be recorded as the current one.
     */
    final String chainId;

    ChainDecision(
        String responseId,
        AiChainResetReason reason,
        int prevTokens,
        int growthTokens,
        String chainId) {
      this.responseId = responseId;
      this.reason = reason == null ? AiChainResetReason.NONE : reason;
      this.prevTokens = prevTokens;
      this.growthTokens = growthTokens;
      this.chainId = chainId;
    }

    ChainDecision(String responseId, AiChainResetReason reason, int prevTokens, int growthTokens) {
      this(responseId, reason, prevTokens, growthTokens, null);
    }

    ChainDecision(String responseId, AiChainResetReason reason, int prevTokens) {
      this(responseId, reason, prevTokens, 0, null);
    }

    /**
     * Whether a fresh chain started here should be handed the continuity block.
     *
     * <p>Read from the reason rather than compared against a particular one. It used to be {@code
     * "token_threshold".equals(decision.reason)}, which was correct while there was one budget
     * reason and silently wrong the moment there were three: a chain reset because it would not fit
     * the context window is the same situation for the user as one reset because it grew expensive,
     * and dropping their authorised job on one but not the other has no defensible reading.
     */
    boolean carriesContinuity() {
      return reason.carriesContinuity();
    }
  }

  /**
   * As above, plus the hard-limit bound.
   *
   * <p>The second reason a chain is dropped, and — since the cost-only threshold was removed, see
   * {@code AiContextBudget} — the only one besides a boundary change (company, conversation, new
   * chat). Azure re-reads the entire retained conversation on every request, so a chain approaching
   * the deployed model's context window would eventually be refused outright; the bound is checked
   * on a projection of the turn about to run, not on a figure the user would recognise as "getting
   * expensive". It is checked between turns, never inside the tool loop: cutting a chain with
   * function calls outstanding would strand the response id and fail the next turn with HTTP 400.
   *
   * <h2>Where the size comes from</h2>
   *
   * From {@link AiChainBudgetStore} — the row this server wrote at the end of the previous turn
   * from the provider's own usage object. It used to come from the state blob, which made a safety
   * decision depend on a number the caller supplies: a client reporting a <em>smaller</em> chain
   * than it really had would stay chained past the point where the next request is refused. The
   * blob's copy is still read, and used for exactly one thing — noticing that the two disagree,
   * which is itself a reason to reset.
   *
   * <p>No usable measurement means no chain. That is the one behavioural change worth stating
   * plainly: a conversation the server cannot measure is restarted rather than continued on the
   * client's word.
   */
  protected ChainDecision chainDecision(
      ChatRequest request, Long currentCompanyId, AiConversationState state) {
    return chainDecision(request, currentCompanyId, state, null);
  }

  /** As above, told who the measurement should be looked up for. */
  protected ChainDecision chainDecision(
      ChatRequest request, Long currentCompanyId, AiConversationState state, Long userId) {
    String responseId = blankToNull(request.getResponseId());
    if (responseId == null) {
      // No id at all: a first message, or New Conversation cleared everything the client held.
      return new ChainDecision(null, AiChainResetReason.NEW_CONVERSATION, 0);
    }
    String conversation = AiConversationKey.of(request.getConversationId());
    if (conversation == null) {
      // A chain belongs to a conversation. Without one there is nothing to say this id is this
      // panel's rather than another tab's, so it is not continued — the turn starts clean instead.
      LOG.info("AI Accountant: no usable conversation id; starting a new model conversation.");
      return new ChainDecision(null, AiChainResetReason.NO_CONVERSATION, 0);
    }
    if (state == null) {
      // A response id with nothing to say which conversation it belongs to. Every chained turn is
      // answered with a state blob, so the absence of one means the blob was dropped as not ours,
      // failed to build, or was never sent. None of those is evidence that this chain is this
      // panel's, and a chain is exactly what must not be continued on a guess.
      LOG.info("AI Accountant: no conversation state with this chain; starting a new one.");
      return new ChainDecision(null, AiChainResetReason.UNSTAMPED_STATE, 0);
    }
    if (state.getConversationId() == null) {
      // Legacy state, written before conversations existed. It may keep the visible transcript on
      // the client, but it cannot carry a chain: nothing in it says which panel or which tab
      // produced it, and the whole point of the stamp is that unattributable state stops here.
      // Fail closed — one clean conversation boundary, once, on the first turn after an upgrade.
      LOG.info("AI Accountant: conversation state carries no stamp; starting a new one.");
      return new ChainDecision(null, AiChainResetReason.UNSTAMPED_STATE, 0);
    }
    if (!AiConversationKey.same(state.getConversationId(), conversation)) {
      // The blob was written by a different conversation. Two tabs sharing storage, a copied
      // session, an edited value — whatever the cause, the chain it names is not this panel's, and
      // continuing it would put another conversation's history in front of this one's model.
      LOG.info(
          "AI Accountant: conversation state belongs to {} but this turn is {}; starting a new"
              + " model conversation.",
          state.getConversationId(),
          conversation);
      return new ChainDecision(null, AiChainResetReason.CONVERSATION_CHANGE, 0);
    }

    // The server's own measurement, not the caller's. The blob's copy is read immediately below,
    // for the single purpose of noticing that the two disagree.
    AiChainBudgetStore.Reading measured =
        chainBudget().load(userId, currentCompanyId, conversation);
    AiContextBudget budget = budget();
    if (measured == null) {
      // Nothing here can say how big this chain is: no row, an unreadable one, or one too old to be
      // about the conversation the id belongs to. The bounds cannot be checked against a number
      // that does not exist, and the alternative to resetting is continuing on the client's word —
      // which is exactly the dependency this removes. The job survives: it lives in its own row and
      // the continuity handoff is rebuilt from that.
      LOG.info(
          "AI Accountant: no server-side measurement for this chain; starting a new model"
              + " conversation.");
      AiTurnMetrics.contextBudget(0, 0, 0, budget.usableChainTokens());
      return new ChainDecision(null, AiChainResetReason.UNMEASURED_CHAIN, 0);
    }
    int previous = measured.lastInputTokens();
    int growth = measured.chainGrowthTokens();
    AiTurnMetrics.contextBudget(
        budget.projectedPeakTokens(previous, growth),
        growth,
        budget.headroomTokens(previous),
        budget.usableChainTokens());
    if (!measured.agreesWith(state.getLastInputTokens())) {
      // The client stores what the last turn told it, so these should be equal. A material gap
      // means
      // the two are describing different conversations — a stale tab, a replayed blob, a number
      // someone changed — and nothing here can say which is right. Note that the gap is refused in
      // BOTH directions: under-reporting is the dangerous one, but over-reporting is equally a sign
      // that the client is not talking about the chain this row measured.
      LOG.warn(
          "AI Accountant: the client reports a chain of {} tokens where {} was measured; starting a"
              + " new model conversation.",
          state.getLastInputTokens(),
          previous);
      return new ChainDecision(
          null, AiChainResetReason.MEASUREMENT_CONFLICT, previous, growth, measured.chainId());
    }

    // A company change can no longer be hidden by omitting the stamp, and the reason is structural
    // rather than a check: the measurement row above is keyed by company, so a turn in a company
    // this conversation has not been measured in finds no reading at all and has already returned
    // UNMEASURED_CHAIN. The explicit comparison below stays because it names the reason precisely,
    // which is worth more in a metric line than "unmeasured".
    Long chainCompany = request.getChainCompanyId();
    if (chainCompany == null) {
      // A client older than this field. Treated as unknown rather than as a mismatch: resetting on
      // every turn would break the conversation outright for a stale browser bundle, whereas
      // keeping the chain is exactly the behaviour that shipped before. Nothing is lost by it —
      // see above.
      LOG.debug("AI Accountant: chaining without a company stamp; client predates chainCompanyId.");
    } else if (currentCompanyId != null && !chainCompany.equals(currentCompanyId)) {
      LOG.info(
          "AI Accountant: active company changed ({} to {}); starting a new model conversation.",
          chainCompany,
          currentCompanyId);
      return new ChainDecision(null, AiChainResetReason.COMPANY_CHANGE, 0);
    }

    // The budget's verdict, arrived at once and read here. Every bound it enforces — the cost
    // threshold, the provider's context window, and the projection of what this turn will reach —
    // is a reason to start a fresh chain WITH the continuity handoff, because the conversation and
    // the person are unchanged and only the retained history has become unaffordable.
    AiChainResetReason verdict = budget.verdict(previous, growth);
    if (verdict.isReset()) {
      LOG.info(
          "AI Accountant: starting a new model conversation ({}): previous {} tokens, growth {},"
              + " projected {}, usable {}.",
          verdict.wire(),
          previous,
          growth,
          budget.projectedPeakTokens(previous, growth),
          budget.usableChainTokens());
      return new ChainDecision(null, verdict, previous, growth, measured.chainId());
    }
    return new ChainDecision(
        responseId, AiChainResetReason.NONE, previous, growth, measured.chainId());
  }

  /**
   * The one policy that decides whether the retained conversation is still affordable and still
   * fits.
   *
   * <p>Looked up rather than injected so a directly-constructed service behaves the same, and
   * defensively, because a missing injector must never be the reason a turn cannot decide whether
   * to chain. The fallback reads the same configuration keys.
   */
  protected AiContextBudget budget() {
    try {
      return Beans.get(AiContextBudget.class);
    } catch (RuntimeException e) {
      return fallbackBudget;
    }
  }

  /**
   * The server's record of what each chain has measured.
   *
   * <p>Looked up rather than injected, like its neighbours, and never null: a store that cannot be
   * reached answers "no measurement", which resets the chain. That is the safe direction, and it is
   * the same answer a missing row gives.
   */
  protected AiChainBudgetStore chainBudget() {
    try {
      return Beans.get(AiChainBudgetStore.class);
    } catch (RuntimeException e) {
      return DEFAULT_CHAIN_BUDGET;
    }
  }

  /**
   * The workflow layer this turn is opened, promoted and updated through.
   *
   * <p>A method rather than a field precisely so a test can override it, the same way {@link
   * #chainBudget} and {@link #routeSelector} are — but unlike those two, this one is deliberately
   * NOT guarded against a missing injector: {@link AiWorkflowController#promoteIfConfirmed} reads a
   * pending write proposal through it, and a broken binding there must fail the turn rather than be
   * read as "nothing was pending". A test with no Guice injector at all overrides this method to
   * supply an {@link AiWorkflowController} whose own {@code pendingProposalStore()} is stubbed —
   * see {@code AiAccountantServiceImplTest} and {@code AiCompanyMismatchTest}.
   */
  protected AiWorkflowController workflowController() {
    return new AiWorkflowController();
  }

  /**
   * The shadow router, or null when it cannot be resolved.
   *
   * <h2>Why this is guarded and the others are not obviously so</h2>
   *
   * Every other {@code Beans.get} in this class already answers a fallback rather than throwing.
   * This one did not, and it is the one lookup where throwing is least defensible: the shadow
   * router observes a turn and changes nothing about it, so a turn that cannot reach it should
   * carry on exactly as it would with the router switched off. Its own documentation says as much —
   * "a router that is slow, unreachable or answering nonsense costs a log line, not a turn" — and
   * an unresolvable binding is the same class of problem as an unreachable endpoint.
   *
   * <p>Null rather than a no-op instance, because the two call sites are already conditional on the
   * router being worth calling and a null reads at those sites as exactly what it is.
   */
  protected AiRouteShadow shadow() {
    try {
      return Beans.get(AiRouteShadow.class);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: the shadow router is unavailable: {}", e.getMessage());
      return null;
    }
  }

  /**
   * The active-mode route selector, or null when it cannot be resolved.
   *
   * <p>Guarded for the same reason as {@link #shadow()}, and with the same consequence: a selector
   * that cannot be reached leaves the turn on the adapter's route, which is exactly what the
   * selector itself would have answered had the router been switched off.
   */
  protected AiRouteSelector routeSelector() {
    try {
      return Beans.get(AiRouteSelector.class);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: the route selector is unavailable: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Who this turn belongs to: the identity resolved once at the top of it, or the thread's.
   *
   * <p>The bound request is preferred so every lookup keyed on the user — the workflow row, the
   * chain measurement — uses the same identity, resolved once, rather than each asking again and
   * risking a different answer.
   */
  /**
   * Writes down how big this conversation's chain has become.
   *
   * <p>Never throws into the turn it is measuring. A measurement that cannot be stored means the
   * next turn finds no row and starts a fresh chain — an unnecessary reset, which is the direction
   * every failure here is meant to fall in.
   */
  protected void recordChainMeasurement(
      Long userId, Long companyId, String conversationId, String measuredChainId) {
    try {
      chainBudget()
          .record(
              userId,
              companyId,
              conversationId,
              // Which chain these figures describe. A concurrent turn of the same panel may have
              // reset the conversation while this one was running, in which case the measurement is
              // about a chain that no longer exists and the store discards it.
              measuredChainId,
              AiTurnMetrics.lastInputTokens(),
              AiTurnMetrics.chainGrowthTokens(),
              // The corrected value: whether the server actually continued the chain, not what the
              // client believed when the request arrived. A fresh chain restarts the turn counter.
              !AiTurnMetrics.wasChained());
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not record the chain measurement: {}", e.getMessage());
    }
  }

  /**
   * The last exchange this user, company and conversation had, or null when the server remembers
   * none — see {@link AiTurnMemoryStore}.
   *
   * <p>Never throws: a store that cannot be read leaves a bare affirmation with nothing to attach
   * to, which the router and {@link com.axelor.apps.aiaccountant.router.AiRouteSelector} already
   * treat as "nothing to continue" rather than as a failure.
   */
  protected AiTurnMemoryStore.Reading loadTurnMemory(
      Long userId, Long companyId, String conversationId) {
    try {
      return turnMemory().load(userId, companyId, conversationId);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not read the turn memory: {}", e.getMessage());
      return null;
    }
  }

  /**
   * The complete stored conversation for this user, company and conversation, oldest first — see
   * {@link AiTurnMemoryStore#loadHistory}. Used only to route this turn; the main agent's own
   * memory of the conversation is the Azure chain, not this.
   *
   * <p>Never throws: a store that cannot be read leaves the router with no more than {@code
   * previousTurn}, which is the same routing it had before this existed.
   */
  protected List<AiTurnMemoryStore.Reading> loadTurnHistory(
      Long userId, Long companyId, String conversationId) {
    try {
      return turnMemory().loadHistory(userId, companyId, conversationId);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not read the conversation history: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  /**
   * Writes down this turn's own words and reply, so a later «да» can be checked against them.
   *
   * <p>Recorded on every turn that reached an answer — a read that merely proposes a fix is exactly
   * the case this exists for — and never on the clarification exit above, which answered nothing of
   * its own to remember.
   */
  protected void recordTurnMemory(
      Long userId,
      Long companyId,
      String conversationId,
      String message,
      String reply,
      PageContext page) {
    try {
      String recordRef =
          page != null && page.hasRecord()
              ? AiWorkflowController.shortRef(page.getModel(), page.getRecordId())
              : null;
      turnMemory().record(userId, companyId, conversationId, message, reply, recordRef);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not record the turn memory: {}", e.getMessage());
    }
  }

  /**
   * The block that tells the model what a verified continuation is confirming.
   *
   * <p>Sent every turn it applies to, chain intact or not — unlike {@link
   * AiTurnContextBuilder#continuityHandoff}, which deliberately withholds model prose because it
   * exists for the one moment nothing is left to check it against. Here the opposite is true: a
   * verified continuation has already been checked, against the record on screen, before this is
   * ever built, so what the model is told is only what the server has already confirmed it may
   * treat as fact.
   */
  protected String continuationEcho(AiTurnMemoryStore.Reading previousTurn) {
    if (previousTurn == null || !previousTurn.hasOffer()) {
      return null;
    }
    StringBuilder sb = new StringBuilder("[Continuing] ");
    sb.append(
        "The user's current message confirms, or lightly adjusts, what YOU proposed last turn."
            + " Your own previous reply, verbatim: \"");
    sb.append(previousTurn.assistantReply());
    sb.append("\" — answering their message: \"");
    sb.append(previousTurn.userMessage() == null ? "" : previousTurn.userMessage());
    sb.append(
        "\". Treat the current message as authorising exactly the change you just proposed,"
            + " applying on top of it any adjustment the user just stated (a different date, a"
            + " different amount). Do not ask for confirmation again — it has already been"
            + " given.");
    return sb.toString();
  }

  /**
   * The last-exchange store, or null when it cannot be resolved.
   *
   * <p>Guarded like {@link #chainBudget()}: a store that cannot be reached leaves a bare
   * affirmation unrecognised, never the turn itself failed.
   */
  /**
   * Stores the assistant's answer, and reports whether it is actually in the database.
   *
   * @return true when the answer is stored; false when it is not, which the reply must then say
   */
  protected boolean storeAssistantMessage(
      Long userId,
      Long companyId,
      String conversationId,
      String turnKey,
      String replyText,
      List<String> toolsUsed) {
    if (!scoped(userId, companyId)) {
      return true;
    }
    try {
      transcript()
          .append(
              userId,
              companyId,
              conversationId,
              turnKey,
              AiChatTranscriptStore.ROLE_ASSISTANT,
              replyText,
              toolsUsed);
      return true;
    } catch (RuntimeException e) {
      LOG.error("AI Accountant: the assistant's reply could not be stored.", e);
      return false;
    }
  }

  /**
   * Appends the notice that this answer was not saved.
   *
   * <p>The same shape as {@link #withUncertaintyNotice}, and for the same reason: it is a statement
   * about the server that the model is in no position to make, so the server makes it rather than
   * leaving the user to discover at the next refresh that an exchange they relied on is gone.
   */
  protected String withTranscriptNotice(String modelText, boolean stored) {
    if (stored) {
      return modelText;
    }
    String notice =
        AiTurnTruthCheck.isBulgarian(modelText)
            ? AiAccountantMessages.TRANSCRIPT_NOT_SAVED_BG
            : AiAccountantMessages.TRANSCRIPT_NOT_SAVED_EN;
    return modelText == null || modelText.trim().isEmpty() ? notice : modelText + "\n\n" + notice;
  }

  /**
   * The durable transcript store.
   *
   * <p>Looked up rather than injected for the same reason as its neighbours: a directly-constructed
   * service, which the tests build, must behave the same. There is deliberately NO silent fallback
   * here — unlike {@link #turnMemory()}, whose contents only make the next turn's reading colder. A
   * missing transcript store means the conversation cannot be kept, and the callers are written to
   * say so rather than to carry on as if it had been.
   */
  protected AiChatTranscriptStore transcript() {
    return Beans.get(AiChatTranscriptStore.class);
  }

  /**
   * Whether this turn has somewhere to store a transcript.
   *
   * <p>A turn with no user or no company in scope has no books to act in — every tool refuses it
   * for the same reason — and no key a panel could ever read the messages back by. Checked before
   * the store is even looked up, so that case never depends on the store being reachable.
   */
  private static boolean scoped(Long userId, Long companyId) {
    return userId != null && companyId != null;
  }

  /**
   * Whether the company this message was written for is not the company now in force.
   *
   * <p>Fail-closed in both directions that can be decided. A client that sent no expectation — one
   * that predates the field, or one whose first transcript has not loaded — never reaches here: it
   * is refused above for having stated no context at all.
   *
   * <p>Everything that does reach here has made a claim, and the claim must match exactly. {@code
   * Objects.equals} is what gives all four combinations the right answer, and the two involving
   * null are the ones worth naming: "there was no active company" agrees only with the server also
   * resolving none, and disagrees with every company; "I am in company 7" disagrees with the server
   * resolving none, because a turn that ran there would be an instruction about books nobody can
   * name. Neither null is ever a wildcard.
   */
  private static boolean companyChanged(ChatRequest request, Long activeCompanyId) {
    return !Objects.equals(request.getExpectedCompanyId(), activeCompanyId);
  }

  protected AiTurnMemoryStore turnMemory() {
    try {
      return Beans.get(AiTurnMemoryStore.class);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: the turn memory store is unavailable: {}", e.getMessage());
      return DEFAULT_TURN_MEMORY;
    }
  }

  protected Long currentUserId() {
    AiRequestContext context = AiRequestContext.current();
    if (context != null && context.getUserId() != null) {
      return context.getUserId();
    }
    return AuthUtils.getUser() == null ? null : AuthUtils.getUser().getId();
  }

  // ───────────────────────────────────────────────────────────────────────────
  // The turn
  // ───────────────────────────────────────────────────────────────────────────

  protected ChatReply runTurn(ChatRequest request, String message, AiConversationState incoming) {
    return runTurn(request, message, incoming, null);
  }

  /**
   * As above, told which job the server holds for this user, company and conversation.
   *
   * <p>The workflow is passed in rather than read from the returned blob because everything the
   * model is told about the work in progress has to survive a chain reset, and after one the
   * context block is the <em>only</em> thing it has left. A value the browser supplied is not the
   * thing to rebuild that from when the server has its own record of it.
   */
  protected ChatReply runTurn(
      ChatRequest request, String message, AiConversationState incoming, AiWorkflow workflow) {
    ChatReply reply = new ChatReply();
    List<String> toolsUsed = new ArrayList<>();

    Long companyId = AiCompanyScope.companyIdOrNull();
    // Reported back so the client can stamp the chain it stores; also what the next turn compares
    // against. Set on every exit path below, including the truncated one.
    reply.setCompanyId(companyId);

    // The id the client may safely send back next turn. It only ever advances to a response with no
    // outstanding function calls: chaining onto one that still has them makes the service answer
    // 400 "No tool output found for function call call_…".
    ChainDecision decision = chainDecision(request, companyId, incoming, currentUserId());
    String safeResponseId = decision.responseId;
    String previousResponseId = safeResponseId;
    AiTurnMetrics.chainReset(decision.reason.wire(), decision.prevTokens);
    // Carried to the end of the turn, where the measurement is written: a concurrent turn may reset
    // this conversation while this one runs, and figures taken against the chain that ended must
    // not be recorded as describing the one that replaced it.
    AiTurnMetrics.measuredChain(decision.chainId);

    // A new chain — first message, company change or a budget bound — has to carry the operating
    // procedure again: the previous chain is where it used to live.
    boolean firstTurn = safeResponseId == null;
    // Corrects what begin() could only guess from the request: a dropped chain makes a turn the
    // client believed was chained into an unchained one, and the input-token jump that follows
    // would
    // otherwise be filed under the wrong group.
    AiTurnMetrics.chained(!firstTurn);

    // Only a BUDGET reset hands over continuity — the context limit or the growth projection of
    // it. Both leave the conversation, the person and the authorisation untouched and change only
    // that the retained history no longer fits, so dropping the job there would make a limit the
    // user never approached look to them like the assistant forgetting.
    //
    // Every boundary reset carries nothing, and that is the same rule stated from the other side: a
    // company change must not bring the previous company's records across, New Conversation means
    // the user asked for a clean slate, and an unstamped or foreign blob is state this turn has
    // already decided is not its own — handing it to a fresh chain would be reviving it.
    //
    // Built from the STORED job where there is one. The handoff is what the model will treat as
    // fact about the work in progress once the history is gone, so it comes from the row the server
    // owns rather than from the blob the browser returned.
    boolean fromStore = workflow != null && workflow.isActive();
    String continuity =
        decision.carriesContinuity() ? contextBuilder.continuityHandoff(incoming, workflow) : null;
    AiTurnMetrics.continuity(
        continuity == null ? 0 : continuity.getBytes(StandardCharsets.UTF_8).length,
        continuity != null && (fromStore || (incoming != null && incoming.hasWorkflow())));
    AiTurnMetrics.continuityFromStore(continuity != null && fromStore);

    // Resolved once, at the top of the turn, and read here: the same answer decides which playbook
    // the model is given and what the metric line records. It used to be resolved a second time in
    // this method from the same inputs — the same value, arrived at twice, which is one derivation
    // too many for something the route now states.
    AiRoute turnRoute = routeOf(message, request.getContext(), incoming);
    AiTask task = turnRoute.task();
    AiTurnMetrics.task(task.name(), task.playbookId());
    // Which instruction documents, at which revision, this turn was actually given. Recorded
    // because an answer can only be explained by the rules that were in front of the model, and
    // "the posting playbook, version 1, plus the bulk supplement" is that, exactly.
    AiTurnMetrics.instructions(AiInstructionSet.forRoute(turnRoute).describe());

    // Present only on a turn whose route came from a verified continuation of last turn's own
    // proposal — see AiRouteSelector and AiRequestContext#getContinuationEcho.
    AiRequestContext boundRequest = AiRequestContext.current();
    String continuationEcho = boundRequest == null ? null : boundRequest.getContinuationEcho();

    JsonNode input =
        mapper
            .getNodeFactory()
            .textNode(
                contextBuilder.build(
                    message,
                    request.getContext(),
                    firstTurn,
                    continuity,
                    incoming,
                    turnRoute,
                    workflow,
                    continuationEcho));

    int maxIterations = policy().maxModelIterations();
    ArrayNode pendingOutputs = null;
    // At most one correction per turn. Bounded by construction rather than by a retry counter:
    // there is no path back to this branch once it is set, so it cannot loop.
    boolean asyncCorrected = false;
    // Whether the loop ended because time ran out rather than because the iterations did. The two
    // finish the same way — settle what is outstanding and answer truthfully — but only one of them
    // has room left to ask the model for a closing sentence.
    boolean outOfTime = false;

    for (int i = 0; i < maxIterations; i++) {
      if (AiTurnDeadline.isExpired()) {
        // Between iterations is the only safe place to stop: nothing is in flight, and the pending
        // tool outputs from the previous round are still in hand to be settled below.
        LOG.warn("AI Accountant: the turn's time budget is spent after {} iteration(s).", i);
        AiTurnMetrics.deadlineExceeded();
        outOfTime = true;
        break;
      }
      FoundryResponse response = callModel(previousResponseId, input);
      previousResponseId = response.getId();

      List<FoundryResponse.FunctionCall> calls = response.functionCalls();
      if (calls.isEmpty()) {
        String text = response.assistantText();
        if (!asyncCorrected && i < maxIterations - 1 && AiTurnTruthCheck.promisesFutureWork(text)) {
          // It announced work instead of doing it, and nothing ran. Telling the user "Започвам…"
          // would leave them waiting for a message that can never come, so the model is told what
          // the runtime actually is and asked once more. If it now calls tools, the loop carries on
          // normally — which is the outcome this is for.
          asyncCorrected = true;
          AiTurnMetrics.asyncPromiseCorrected();
          LOG.warn(
              "AI Accountant: the reply promised background work with no tool call executed;"
                  + " asking the model to act or state the blocker.");
          input =
              mapper.createArrayNode().add(userMessage(AiAccountantMessages.NO_BACKGROUND_WORK));
          continue;
        }
        reply.setReply(verified(text));
        reply.setResponseId(previousResponseId);
        reply.setToolsUsed(toolsUsed);
        return reply;
      }

      pendingOutputs = batchRunner.executeAll(calls, toolsUsed);
      input = pendingOutputs;
    }

    return atIterationCap(
        reply,
        message,
        incoming,
        workflow,
        toolsUsed,
        pendingOutputs,
        previousResponseId,
        safeResponseId,
        !outOfTime);
  }

  /**
   * The turn used its whole tool budget and the model still wanted more.
   *
   * <h2>Why that is not the same as unfinished</h2>
   *
   * The cap counts model round trips, not business operations, and a workflow can reach its target
   * on the last one. A real turn corrected, validated and posted invoice 606, hit the cap, and was
   * told "the turn hit its limit before the task was finished… say continue" about an invoice that
   * was already in the books. So the outstanding calls are settled, and then {@link
   * AiTurnTruthCheck} compares the outcome the user authorised against the records' current state.
   * Finished means finished: a normal answer, no request to continue, and no authorisation carried
   * into the next turn.
   *
   * <p>Answering the outstanding calls is not optional either way: leaving them unanswered would
   * strand this response id, and the client would send it next turn and get a 400 for a call that
   * can never be satisfied. So every pending call is settled and, in the same request, the model is
   * told the budget is gone — that costs no extra tool round.
   */
  protected ChatReply atIterationCap(
      ChatReply reply,
      String message,
      AiConversationState incoming,
      AiWorkflow workflow,
      List<String> toolsUsed,
      ArrayNode pendingOutputs,
      String previousResponseId,
      String safeResponseId) {
    return atIterationCap(
        reply,
        message,
        incoming,
        workflow,
        toolsUsed,
        pendingOutputs,
        previousResponseId,
        safeResponseId,
        true);
  }

  /**
   * As above, told whether there is still time to ask the model for a closing sentence.
   *
   * <p>{@code maySettle} is false only when the turn stopped because its <em>time</em> ran out
   * rather than its iterations. Settling costs one more model request, and starting a request the
   * turn has no budget for would turn a bounded stop into an unbounded one — the exact failure the
   * deadline exists to prevent. The price of not settling is a stranded response id, which is why
   * the reply falls back to the last clean one: the client sends that next turn and the chain
   * continues from a point the service will accept.
   */
  protected ChatReply atIterationCap(
      ChatReply reply,
      String message,
      AiConversationState incoming,
      AiWorkflow workflow,
      List<String> toolsUsed,
      ArrayNode pendingOutputs,
      String previousResponseId,
      String safeResponseId,
      boolean maySettle) {
    LOG.warn(
        "AI Accountant: {}; settling pending calls and asking for a final answer.",
        maySettle ? "hit the tool-iteration cap" : "ran out of time");
    reply.setToolsUsed(toolsUsed);
    AiTurnMetrics.capReached();

    // The same goal the turn opened with, read from the route rather than derived from the message
    // a second time: what the user authorised cannot have changed since the turn began.
    AiWorkflowIntent.Stage requested = routeOf(message, null, incoming).goal();
    AiTurnTruthCheck.TurnState state = AiTurnTruthCheck.assess(requested);
    if (!state.isComplete() && workflowController().settledAfterThisTurn(workflow, requested)) {
      // AiTurnTruthCheck only ever proves completion against a lifecycle destination, so a job
      // with none — a repair, which has no stage to reach by design — could never satisfy it and
      // was told it was unfinished however cleanly it actually closed. The workflow's own
      // completed/blocked/remaining tracking is the same kind of proof (tool effects and
      // completion resolvers, never the model's prose), so it is asked here as a second, general
      // route to "complete" rather than folded into AiTurnTruthCheck's own lifecycle-only rule.
      state = new AiTurnTruthCheck.TurnState(state.getStates(), true);
    }

    // Nothing outstanding to answer, or no room left to answer it. Both skip straight to the
    // fallback: a settling request made on borrowed time would turn a bounded stop into an
    // unbounded one.
    if (maySettle && pendingOutputs != null) {
      ChatReply settledReply = settle(reply, state, pendingOutputs, previousResponseId);
      if (settledReply != null) {
        return settledReply;
      }
    }
    return atCapWithoutModelText(reply, state, safeResponseId);
  }

  /**
   * Answers the outstanding tool calls and asks for a closing sentence.
   *
   * @return the finished reply, or null when the model asked for more tools or the request failed —
   *     both of which leave the caller to answer from the record state alone
   */
  protected ChatReply settle(
      ChatReply reply,
      AiTurnTruthCheck.TurnState state,
      ArrayNode pendingOutputs,
      String previousResponseId) {
    try {
      // The outstanding tool outputs the loop has already built. The budget notice rides along with
      // them in the same request, so settling the calls costs no extra round trip.
      pendingOutputs.add(userMessage(AiAccountantMessages.TOOL_BUDGET_EXHAUSTED));
      FoundryResponse settled = callModel(previousResponseId, pendingOutputs);
      if (settled.functionCalls().isEmpty()) {
        // Not settled.assistantText() on its own. That is exactly what produced "all three invoices
        // are fixed" after the third edit never ran: a summary of intent, accepted as a report of
        // fact. The truth check states what the records say and keeps the model's wording only when
        // the ledger supports it.
        reply.setReply(AiTurnTruthCheck.truncatedReply(settled.assistantText(), state));
        reply.setResponseId(settled.getId());
        finishCap(reply, state);
        return reply;
      }
      // It asked for tools anyway, so that id is unusable too — fall back to the last clean one.
      LOG.warn("AI Accountant: the model requested more tools after the budget notice.");
    } catch (RuntimeException e) {
      LOG.warn("AI Accountant: could not settle the pending tool calls: {}", e.getMessage());
    }
    return null;
  }

  /**
   * The answer when there is no model text at all — the settling request failed, or was never made.
   *
   * <p>The record state comes first because it is the part that is provable; the request for a
   * record follows, since that is what actually helps. Deliberately says nothing about tools,
   * budgets, deadlines or iterations — those are this service's internals and mean nothing to an
   * accountant.
   */
  protected ChatReply atCapWithoutModelText(
      ChatReply reply, AiTurnTruthCheck.TurnState state, String safeResponseId) {
    reply.setReply(
        state.isComplete()
            ? AiTurnTruthCheck.completedNotice(state, true, null)
            : AiTurnTruthCheck.truncationNotice(true, state)
                + AiAccountantMessages.NAME_THE_RECORD_BG);
    reply.setResponseId(safeResponseId);
    finishCap(reply, state);
    return reply;
  }

  /** Marks the turn truncated only when work is genuinely outstanding, and logs either way. */
  protected void finishCap(ChatReply reply, AiTurnTruthCheck.TurnState state) {
    if (state.isComplete()) {
      LOG.info(
          "AI Accountant: iteration cap reached but the authorised outcome was already met ({}).",
          state.getStates());
      return;
    }
    reply.setTruncated(true);
    AiTurnMetrics.truncated();
    LOG.warn(
        "AI Accountant: turn truncated; {} ok, {} failed, {} not executed ({}).",
        AiTurnToolLog.count(AiTurnToolLog.Status.OK),
        AiTurnToolLog.count(AiTurnToolLog.Status.FAILED),
        AiTurnToolLog.count(AiTurnToolLog.Status.SKIPPED),
        AiTurnTruthCheck.unfinished());
  }

  /**
   * Two facts about how the turn behaved, recorded after it finished.
   *
   * <p><b>A prerequisite ran.</b> Told only "осчетоводи", a turn that also validated did work the
   * user asked for by implication. That is the behaviour C6.1 exists to produce, so it is the
   * behaviour worth counting — and the number that says whether the goal semantics landed.
   *
   * <p><b>A clarification was asked.</b> Approximated the only way it can be without reading the
   * conversation: the turn wrote nothing and its reply ends in a question. That is deliberately
   * crude, and it over-counts a legitimate question about an ambiguous supplier as readily as the
   * "изберете 1/2/3" menu this phase removes. It is a friction TREND, not a verdict on one turn —
   * what matters is whether it falls against the same scenarios over time.
   */
  protected void recordGoalProgress(AiWorkflowIntent.Stage goal, ChatReply reply) {
    try {
      if (AiWorkflowIntent.executedPrerequisite(goal, AiTurnToolLog.successfulWrites())) {
        AiTurnMetrics.deterministicPrerequisite();
      }
      String text = reply == null ? null : reply.getReply();
      if (text != null && AiTurnToolLog.noSuccessfulWrite() && endsWithQuestion(text)) {
        AiTurnMetrics.clarificationAsked();
      }
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not record goal progress: {}", e.getMessage());
    }
  }

  /** A question mark in the closing sentence — not anywhere, or every explanation counts. */
  protected boolean endsWithQuestion(String text) {
    String trimmed = text.trim();
    if (trimmed.isEmpty()) {
      return false;
    }
    int from = Math.max(0, trimmed.length() - 200);
    return trimmed.substring(from).contains("?");
  }

  /**
   * The outcome this turn was authorised to reach.
   *
   * <p>Taken from the user's own words. «Продължи» authorises nothing by itself, so it inherits the
   * instruction that is already recorded — which is the same text the continuity block quotes and
   * the same text rule 18 tells the model still stands.
   *
   * <p>Kept as the definition of that ladder; {@link AiRouteAdapter} walks the identical one, and
   * {@link #routeOf} is what callers inside a turn read so the answer is settled once.
   */
  protected AiWorkflowIntent.Stage requestedStage(String message, AiWorkflow previous) {
    AiWorkflowIntent.Stage stage = AiWorkflowIntent.requested(message);
    if (stage != null) {
      return stage;
    }
    // Inherited from the stored job, never from the returned blob. An inherited destination is an
    // authorisation the user gave earlier and has not repeated, so where it comes from is the whole
    // question.
    if (AiWorkflowIntent.isContinuation(message) && previous != null && previous.isActive()) {
      return AiWorkflowIntent.requested(previous.getRequest());
    }
    return null;
  }

  /**
   * The route this turn is running under.
   *
   * <p>The bound request's, when there is one — which is every turn that came through {@link
   * #chat}. Resolved on the spot otherwise, so a caller that constructs this service and invokes an
   * inner method directly (the tests do) behaves exactly as it did before the route existed.
   */
  protected AiRoute routeOf(String message, PageContext page, AiConversationState incoming) {
    AiRequestContext context = AiRequestContext.current();
    if (context != null) {
      return context.getRoute();
    }
    return AiRouteAdapter.resolve(message, page, incoming);
  }

  /**
   * The model's answer, with a server correction appended when it claims work that never happened.
   *
   * <p>Untouched in every ordinary case. It intervenes only where the reply asserts that <em>this
   * turn</em> performed a write and {@link AiTurnToolLog} shows no successful write to support it.
   * Appended rather than substituted: the assistant's own wording is often still useful, and
   * silently rewriting an answer would hide a real model problem instead of surfacing it.
   */
  protected String verified(String assistantText) {
    String correction = AiTurnTruthCheck.falseSuccessCorrection(assistantText);
    if (correction == null) {
      return assistantText;
    }
    LOG.warn(
        "AI Accountant: the reply claimed completed work but no write succeeded this turn; appended"
            + " a correction.");
    return (assistantText == null || assistantText.trim().isEmpty())
        ? correction
        : assistantText.trim() + "\n\n" + correction;
  }

  /**
   * States the job's real progress, and removes any promise that contradicts it.
   *
   * <h2>Two jobs, and only one of them may depend on the wording</h2>
   *
   * <p><b>Stating the truth</b> is unconditional. Whenever the job has work left or work stopped,
   * the completed/blocked/remaining line goes out — because those are facts about records, read
   * from the workflow state and the records' own lifecycle, and whether the user learns them must
   * not depend on which words the model happened to choose. This used to fire only when a regex
   * matched a promise, which meant a reply that quietly said nothing about the other thirty
   * invoices got no correction at all: the guard was strongest against clumsy honesty and weakest
   * against silence.
   *
   * <p><b>Removing a promise</b> is the part that necessarily reads the text, and it is now only
   * cosmetic: it deletes sentences that claim work will continue after this turn, which nothing can
   * do. If the pattern misses one, the authoritative status still went out beside it, so the worst
   * case is a contradiction the user can resolve rather than a silence they cannot.
   *
   * <h2>Why a second guard at all</h2>
   *
   * The tool loop already re-prompts once when it sees a promise with nothing executed, and that is
   * the good outcome: the model usually goes and does the work. But one round is all there is, and
   * if the second answer promises again, the promise reaches the user — who then waits for a
   * message that can never arrive, because the HTTP turn is over the moment this method returns.
   * Appending the truth underneath such a sentence leaves two contradictory statements with no way
   * to tell which is authoritative, and the misleading one is in the assistant's confident voice.
   * So the sentence goes; everything else the reply said is kept.
   */
  protected String withWorkflowStatus(String modelText, AiWorkflow workflow) {
    try {
      if (workflow == null || !workflow.isActive()) {
        return modelText;
      }
      boolean promised = AiTurnTruthCheck.promisesFutureWork(modelText);
      String kept = promised ? AiTurnTruthCheck.withoutFutureWorkPromises(modelText) : modelText;
      if (promised) {
        AiTurnMetrics.asyncPromiseCorrected();
        LOG.warn("AI Accountant: the reply promised later work; removed the promise.");
      }
      if (!workflowController().hasUnfinishedBusiness(workflow)) {
        // Nothing outstanding, so there is nothing to state. Any promise still had to go.
        return kept.isEmpty() ? modelText : kept;
      }
      String status =
          workflowController().statusLine(workflow, AiTurnTruthCheck.isBulgarian(modelText));
      if (status == null || kept.contains(status)) {
        return kept.isEmpty() ? modelText : kept;
      }
      return kept.isEmpty() ? status : kept + "\n\n" + status;
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not state the workflow status: {}", e.getMessage());
      return modelText;
    }
  }

  /** A plain user-role input item, mixable with function_call_output items in one input array. */
  protected ObjectNode userMessage(String text) {
    ObjectNode item = mapper.createObjectNode();
    item.put("type", "message");
    item.put("role", "user");
    item.putArray("content").addObject().put("type", "input_text").put("text", text);
    return item;
  }

  /**
   * Kept as a seam for tests and for callers that do not carry conversation state.
   *
   * @see AiTurnContextBuilder#build
   */
  protected String withContext(String message, PageContext context, boolean firstTurn) {
    return contextBuilder.build(message, context, firstTurn, null, null);
  }

  private static String blankToNull(String v) {
    return (v == null || v.trim().isEmpty()) ? null : v.trim();
  }
}
