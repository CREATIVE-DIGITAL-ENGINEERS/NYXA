package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.context.AiCompletionRegistry;
import com.axelor.apps.aiaccountant.context.AiCreationTarget;
import com.axelor.apps.aiaccountant.context.AiMessageTargetRegistry;
import com.axelor.apps.aiaccountant.context.AiMessageTargetResolver;
import com.axelor.apps.aiaccountant.context.AiPageContextRegistry;
import com.axelor.apps.aiaccountant.context.AiTargetRegistry;
import com.axelor.apps.aiaccountant.context.AiWorklistRegistry;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.AiWorkflowRecord;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.resilience.AiUncertainWrites;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.aiaccountant.route.AiSetSelection;
import com.axelor.apps.aiaccountant.route.AiWriteOperation;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.tool.AiTurnToolLog;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The thin deterministic layer around the model: what job is running, on which records, and how far
 * each one got.
 *
 * <h2>What moved into Java, and what deliberately did not</h2>
 *
 * Java now owns the mechanics — the goal, the target set, the authorisation that covers them, which
 * targets reached the goal, which are blocked and why. The model still owns every accounting and
 * OCR judgement: what is wrong with an invoice, which lines the document really has, which product
 * a description means, whether a VAT rate is a special regime. Nothing here decides how to repair
 * anything, and nothing here calls a tool.
 *
 * <p>The split matters because the failures this addresses were never accounting failures. Asking
 * for permission twice, offering a numbered menu, promising to carry on afterwards, losing the
 * target set on «продължи», stopping four invoices because one was blocked — every one is a
 * bookkeeping error about the conversation, not about the books.
 *
 * <h2>Observed, never assumed</h2>
 *
 * Completion is read from the record's own lifecycle through {@link AiPageContextRegistry}, exactly
 * as {@link AiTurnTruthCheck} does. A target counts as done because the invoice is POSTED, not
 * because a tool returned success and not because the model said so. Blocking comes from a refusal
 * the server saw, classified by {@link AiBlockerClass}. A target nobody touched stays remaining.
 */
@Singleton
public class AiWorkflowController {

  private static final Logger LOG = LoggerFactory.getLogger(AiWorkflowController.class);

  /**
   * Opens or resumes the job this turn belongs to.
   *
   * <h2>Why a continuation inherits rather than re-derives</h2>
   *
   * «Продължи» carries no goal and no targets of its own. Re-deriving them from the word would find
   * nothing, and the scope would be lost: the assistant would fall back on whatever record is on
   * screen, or ask which invoices are meant — about a set the user has already named once.
   * Inheriting is not a convenience, it is the only correct reading of the word.
   *
   * <p>A new instruction with its own goal replaces the workflow outright. It does not merge:
   * «осчетоводи 946» after a four-invoice job is a new job, and quietly adding 946 to the old
   * target set would widen an authorisation the user never widened.
   *
   * @param goal the destination this turn's own words authorise, or null
   * @return the active workflow, or null when this turn is not workflow work at all
   */
  public AiWorkflow open(
      AiWorkflow previous, String message, AiWorkflowIntent.Stage goal, AiTask task) {
    return open(message, new AiRoute(null, task, goal, null, null), null, previous, null, null);
  }

  /**
   * As above, with the route it was resolved under and the identity it belongs to.
   *
   * <h2>One copy, and it is the server's</h2>
   *
   * The job is read only from the row this user, company and conversation own, never from a copy
   * the browser returns. Checks applied to a client copy can be real and still cannot make it an
   * authorisation, because the thing being checked is supplied by the caller: it says which
   * conversation the client <em>claims</em> the job belongs to and which records it <em>claims</em>
   * were authorised. Accepting it when the server has no record is a path by which a client hands
   * itself a job.
   *
   * <p>So {@link #resume} reads the row and nothing else. No row means no resumable job, and the
   * turn is in the same position as a first message: an instruction is needed, and «продължи» finds
   * nothing to continue.
   *
   * <h2>Company</h2>
   *
   * The row is keyed by company, so a job authorised in another one is not found at all — the
   * records it names are not visible from here, and the authorisation itself was given about a
   * different set of books.
   */
  public AiWorkflow open(
      String message,
      AiRoute route,
      PageContext page,
      Long userId,
      Long companyId,
      String conversationId) {
    return open(
        message, route, page, resume(userId, companyId, conversationId), companyId, conversationId);
  }

  /**
   * As above, given the job the caller already loaded.
   *
   * <p>The previous job is a parameter now rather than something this method looks up, because the
   * turn needs the same snapshot in three places — to resolve the route, to feed the shadow router,
   * and here — and three lookups of one row is three chances for them to disagree about what was
   * running when the message arrived.
   *
   * @param previous the stored job, or null when the server holds none
   */
  public AiWorkflow open(
      String message,
      AiRoute route,
      PageContext page,
      AiWorkflow previous,
      Long companyId,
      String conversationId) {
    return open(message, route, page, previous, companyId, conversationId, Collections.emptyList());
  }

  /**
   * As above, told which records the user's own message named in database-id syntax.
   *
   * <p>The list arrives unvalidated — {@link
   * com.axelor.apps.aiaccountant.route.AiExplicitReferenceParser} only reads text — and is
   * validated here, in the one place that is allowed to decide what a job may write to. It is the
   * same list routing was given, so the scope this method sees and the set it freezes cannot
   * describe different messages.
   *
   * @param explicitRefs candidate references from the user's message, or empty
   */
  public AiWorkflow open(
      String message,
      AiRoute route,
      PageContext page,
      AiWorkflow previous,
      Long companyId,
      String conversationId,
      List<String> explicitRefs) {
    boolean continuation = AiWorkflowIntent.isContinuation(message);
    AiWorkflowIntent.Stage goal = route == null ? null : route.goal();
    boolean writeJob = route != null && route.task() != null && route.task().authorisesWrites();

    // NB: an unrelated old job must already have been filtered out of `previous` by the caller
    // (AiRouteAdapter.compatibleWorkflow, called with the SAME route's own domain, after routing)
    // — this method trusts what it is handed, exactly as {@link #authoriseTargets} already does
    // by reading route.scope() rather than re-deriving domain fitness itself.
    boolean elsewhere = pointsElsewhere(page, previous);
    // What may end a job the user gave no instruction to end: an explicit reference this turn
    // that names a DIFFERENT record than the one already authorised. The page moving on is not
    // that evidence by itself — see matchesPrevious — so it is deliberately not checked here any
    // more; it still decides, below, what a genuinely NEW instruction's own target is.
    boolean namesElsewhere = !matchesPrevious(explicitRefs, previous);
    // INHERITED is the route saying, in so many words, that this turn continues an authorisation
    // given earlier — a bare «да» the router (or AiRouteSelector's continuation check) attached to
    // the job on record. It is read here exactly like «продължи»: the job the server holds is
    // returned, with its frozen targets, and nothing about the page decides anything. Minting a
    // fresh job instead would break "read A, open B, say да": the new job would freeze whatever
    // record is on screen (OPEN_RECORD) and INHERITED would then pass checkAuthorisation on it, so
    // B would be written under an approval given about A — and a job over NAMED_RECORDS would lose
    // its set entirely.
    boolean inherits = route != null && route.explicitness() == AiExplicitness.INHERITED;
    boolean resuming =
        (continuation || inherits) && previous != null && previous.isActive() && !namesElsewhere;
    if (resuming && !continuation && (explicitRefs == null || explicitRefs.isEmpty())) {
      // The same rule as the explicit-reference one above, applied to a business key instead of a
      // row id. «Направи доставчик с ЕИК 130159785 …» names its own record as plainly as
      // «валидирай invoiceId=641» does — just in the form a user actually types — so a stale job
      // blocked on a DIFFERENT ЕИК must not swallow it. Checked only here, where the job would
      // otherwise be resumed, so an ordinary turn pays for the lookup only when it decides
      // something; and never for a bare «продължи», which names nothing of its own by definition.
      if (!matchesPrevious(messageTargets(route, message), previous)) {
        resuming = false;
        namesElsewhere = true;
      }
    }
    if (resuming) {
      return previous;
    }
    if (inherits
        && !continuation
        && !namesElsewhere
        && (previous == null || !previous.isActive())
        && !messageTargets(route, message).isEmpty()) {
      // «ЕИК 130159785» answering the assistant's own question is labelled INHERITED — it continues
      // the conversation — but there is no job to inherit when the previous one could not resolve
      // its supplier. Read as INHERITED-with-nothing, it opened no job and every write was refused,
      // so the assistant asked again and again. The message names its own record, so it gets its
      // own job, frozen on what it names and on nothing else.
      namesElsewhere = true;
    }
    if (inherits
        && !continuation
        // ...unless this message named a record of its own that the job on record is not about.
        // Then it is not a bare affirmation at all: it carries its own target, which the branches
        // below resolve and freeze. Returning null here would refuse a complete, self-contained
        // instruction — «Направи доставчик с ЕИК 130159785 …» after a job blocked on a different
        // ЕИК — purely because the router labelled it INHERITED.
        && !namesElsewhere
        && (route.scope() != AiScope.OPEN_RECORD
            || (explicitRefs != null && !explicitRefs.isEmpty())
            || (previous != null && previous.isActive()))) {
      // An affirmation with no job of its own to inherit. The one fresh job it may still open is
      // the
      // verified turn-memory continuation, which AiRouteSelector only ever hands over as
      // OPEN_RECORD
      // on the very record the proposal was made on. Anything else — a set it would have to
      // resolve itself, a record it names that is not the job's, a job it does not match — has
      // nothing server-side behind it, so nothing is opened and INHERITED then authorises nothing.
      return null;
    }
    if (goal == null && !writeJob) {
      // A question, or something with no destination and no change asked for. An existing job
      // survives a turn spent asking about it — «какво остана?» must not end the workflow it is
      // asking about — and survives the page having moved on too, for the same reason. Only an
      // explicit reference to a different record is evidence the old job is not the answer to it.
      return previous != null && previous.isActive() && !namesElsewhere ? previous : null;
    }
    AiWorkflow workflow = new AiWorkflow();
    // Minted here and nowhere else. Everything that later has to decide whether two updates belong
    // to the same job compares this and nothing else.
    workflow.setWorkflowId(UUID.randomUUID().toString());
    // «Същото и с тази» on another record inherits the PATTERN and nothing else: what kind of work
    // it was, and where it was headed. The records, the progress and the blockers all belong to the
    // job that earned them and stay there.
    workflow.setGoal(
        goal == null ? (elsewhere && previous != null ? previous.getGoal() : null) : goal.name());
    workflow.setWriteJob(writeJob);
    workflow.setTask(
        route.task() == null
            ? (elsewhere && previous != null ? previous.getTask() : null)
            : route.task().name());
    workflow.setRequest(message);
    workflow.setCompanyId(companyId);
    workflow.setConversationId(conversationId);
    workflow.setScope(route.scope() == null ? null : route.scope().name());
    workflow.setExplicitness(route.explicitness() == null ? null : route.explicitness().name());
    authoriseTargets(workflow, route, page, previous, explicitRefs);
    return workflow;
  }

  /** Both halves of a turn promoted from a stored {@link AiPendingProposalStore.Reading}. */
  public static final class Promotion {

    private final AiWorkflow workflow;
    private final AiRoute route;

    Promotion(AiWorkflow workflow, AiRoute route) {
      this.workflow = workflow;
      this.route = route;
    }

    public AiWorkflow workflow() {
      return workflow;
    }

    public AiRoute route() {
      return route;
    }
  }

  /**
   * Promotes the one stored {@link AiPendingProposalStore.Reading}, if any, that this message
   * confirms — and, whatever this turn turns out to be, leaves nothing pending for the turn after
   * it.
   *
   * <h2>The only thing a bare "yes" is ever allowed to authorise</h2>
   *
   * {@link AiWorkflowIntent#isAffirmativeConfirmation} says the message reads as a confirmation; it
   * says nothing about what is confirmed, on purpose — see its own javadoc. What is confirmed is
   * decided entirely here, from a row a trusted read tool wrote from stored evidence, never from
   * this message's own words: the job this method builds is authorised over exactly one target, the
   * proposal's own canonical reference, and nothing the message contains — "дадада <b>създай
   * доставчика</b>" carries its own supplier-creation words, and none of them changes which target
   * is frozen — can add to or change it.
   *
   * <h2>Valid only for the immediately following turn</h2>
   *
   * A proposal is a standing invitation to write, so its window has to be exactly one turn wide,
   * not "until somebody eventually says yes". So this is called on every turn, whether or not the
   * message looks like a confirmation, and a stored proposal is consumed here on its very first
   * turn back, one way or the other:
   *
   * <ul>
   *   <li>the message confirms it (an affirmation, its target not contradicted) — promoted, and
   *       consumed;
   *   <li>anything else — not an affirmation at all, an affirmation naming a different record, or
   *       the company/conversation no longer matching what {@link AiPendingProposalStore#find} was
   *       even asked about — consumed, promoting nothing.
   * </ul>
   *
   * <p>That second branch is what makes an unrelated instruction, a task switch, or a bare "да"
   * that turns out to belong to a different question all invalidate a proposal exactly as an
   * explicit different target does: none of them is the one confirmation this proposal was for, and
   * by the time any of them has been read, the window has closed. A company change is caught before
   * this is even called — see {@code AiAccountantServiceImpl#endWorkflowsOfOtherCompanies} and
   * {@link AiPendingProposalStore#consumeOtherCompanies} — and Clear/Delete by {@code
   * AiConversationEraser}, both for the same reason.
   *
   * <p>A caller that got null back is exactly where it would have been had this method never run:
   * ordinary routing decides the turn, and — for a message with no destination of its own, like a
   * plain "да" whose proposal has already expired — that ordinarily means the usual "nothing in
   * this message asked for a change" refusal, which is itself the request for clarification the
   * turn needs.
   *
   * <h2>A broken store fails the turn, not silently to "nothing pending"</h2>
   *
   * {@link #pendingProposalStore} is not guarded here the way {@code targetRegistry()}'s callers
   * guard theirs: a proposal is an authorisation-bearing row, and if the injector or the store
   * cannot be reached, that has to surface as a failed turn, not be read as "there was never
   * anything pending" — the one reading that would let a real dependency-injection fault silently
   * disable confirmation handling. Production always has a bound store; a caller with none (a pure
   * unit test built with no Guice injector at all) must override {@link #pendingProposalStore}
   * itself to supply one, exactly as {@code AiWorkflowControllerPromotionTest} and {@code
   * AiAccountantServiceImplTest} do.
   */
  public Promotion promoteIfConfirmed(
      String message,
      List<String> namedRecords,
      Long userId,
      Long companyId,
      String conversationId) {
    AiPendingProposalStore store = pendingProposalStore();
    AiPendingProposalStore.Reading proposal = store.find(userId, companyId, conversationId);
    if (proposal == null) {
      return null;
    }
    if (!AiWorkflowIntent.isAffirmativeConfirmation(message)) {
      // This turn is something else entirely — a new instruction, a question, a task switch. The
      // proposal was for the turn right after it was read, and this is not that turn's confirmation
      // of it, so its one window has closed.
      store.consume(userId, companyId, conversationId);
      return null;
    }
    if (namedRecords != null && !namedRecords.isEmpty()) {
      AiTargetRegistry registry = targetRegistry();
      boolean matches = false;
      for (String ref : namedRecords) {
        if (registry.sameTarget(proposal.sourceRef(), ref)
            || registry.sameTarget(proposal.canonicalTarget(), ref)) {
          matches = true;
          break;
        }
      }
      if (!matches) {
        // An affirmation, but naming a different record than the proposal was about — evidence
        // this "yes" is not confirming it. Consumed all the same: a second, later "да" must not be
        // read as still confirming the same offer.
        store.consume(userId, companyId, conversationId);
        return null;
      }
    }
    AiTask task;
    try {
      task = proposal.task() == null ? AiTask.GENERAL : AiTask.valueOf(proposal.task());
    } catch (IllegalArgumentException e) {
      task = AiTask.GENERAL;
    }
    AiWorkflow workflow = new AiWorkflow();
    workflow.setWorkflowId(UUID.randomUUID().toString());
    workflow.setWriteJob(true);
    workflow.setTask(task.name());
    workflow.setRequest(message);
    workflow.setCompanyId(companyId);
    workflow.setConversationId(conversationId);
    workflow.setScope(AiScope.NAMED_RECORDS.name());
    workflow.setExplicitness(AiExplicitness.EXPLICIT.name());
    workflow.setAuthorisedTargets(Collections.singletonList(proposal.canonicalTarget()));
    workflow.addTarget(proposal.canonicalTarget());
    AiRoute route =
        new AiRoute(task.domain(), task, null, AiScope.NAMED_RECORDS, AiExplicitness.EXPLICIT);
    store.consume(userId, companyId, conversationId);
    return new Promotion(workflow, route);
  }

  /**
   * The one place {@link #matchesPrevious} and {@link #pointsElsewhere} get their registry, so a
   * test can substitute a fixed one instead of needing a running Guice injector — the same reason
   * {@code AiRecordDomainRegistry}'s own {@code discover()} is overridable.
   */
  protected AiTargetRegistry targetRegistry() {
    return Beans.get(AiTargetRegistry.class);
  }

  /**
   * The seam a test overrides to supply an in-memory store instead of a Guice one — see {@link
   * #promoteIfConfirmed}. Deliberately NOT guarded with a try/catch the way {@link #targetRegistry}
   * is read: an unbound or broken {@link AiPendingProposalStore} is a real production fault, and
   * swallowing it here would read a dependency-injection failure as "no proposal was pending",
   * silently disabling confirmation handling instead of surfacing the fault.
   */
  protected AiPendingProposalStore pendingProposalStore() {
    return Beans.get(AiPendingProposalStore.class);
  }

  /**
   * Whether an explicit reference this turn is consistent with the job already on record.
   *
   * <h2>Why this, and not the page, decides whether an explicit target replaces the job</h2>
   *
   * A record the user actually named outranks everything else — the active job included. True
   * (nothing to contradict) whenever there is no active job with a closed target set to contradict,
   * or the message named no record at all: an ordinary continuation or ambient turn has nothing
   * here to conflict with. False only when the message named a record in database-id syntax and
   * none of those names canonically match one of the job's own frozen targets — the one case an
   * explicit target is evidence the old job is not what this turn is about.
   *
   * <p>Conservative on failure, the same way {@link #pointsElsewhere} is: a comparison that cannot
   * be made is not evidence of a conflict, so it resolves to "matches".
   */
  protected boolean matchesPrevious(List<String> explicitRefs, AiWorkflow previous) {
    if (previous == null || !previous.hasClosedTargets()) {
      return true;
    }
    if (explicitRefs == null || explicitRefs.isEmpty()) {
      return true;
    }
    // The literal comparison first, before anything that can fail: two references that are the
    // same string are the same record whatever the registry says, and this is the one comparison
    // that still works when the registry cannot be reached. Without it the catch below turned
    // every unreachable-registry turn into "matches", so a job blocked on one ЕИК swallowed a
    // fresh instruction naming a different one.
    for (String ref : explicitRefs) {
      if (ref == null) {
        continue;
      }
      for (String target : previous.getAuthorisedTargets()) {
        if (target != null && target.trim().equals(ref.trim())) {
          return true;
        }
      }
    }
    try {
      AiTargetRegistry registry = targetRegistry();
      for (String ref : explicitRefs) {
        for (String target : previous.getAuthorisedTargets()) {
          if (registry.sameTarget(target, ref)) {
            return true;
          }
        }
      }
      return false;
    } catch (RuntimeException e) {
      LOG.debug(
          "AI Accountant: could not compare the named reference to the job: {}", e.getMessage());
      return true;
    }
  }

  /**
   * Whether the record in front of the user is one the previous job was never about.
   *
   * <h2>What this guards against</h2>
   *
   * A job is a record plus an intention. The intention survives the user turning to another
   * document — «същото и с тази», «continue», «this one» — but the record does not, and the two
   * must not be carried together. Otherwise a job opened on invoice A stays open across a move to
   * invoice B: B's turn resumes A's workflowId, A's authorised targets and A's blockers, and the
   * status line reports progress on a document the user has left. Worse, the frozen target set
   * still names A, so a write aimed at B is refused by {@code checkTargets} with a message about a
   * record nobody has mentioned.
   *
   * <p>Answered from the page alone, because the page is trusted context the server received rather
   * than anything the model produced. Comparison is by canonical identity, so a short ref and a
   * fully-qualified one are the same record.
   *
   * <p>False whenever it cannot be established — no page record, no previous job, no closed set, an
   * unresolvable ref. The conservative answer here is "same job", which behaves exactly as if this
   * check did not exist.
   */
  protected boolean pointsElsewhere(PageContext page, AiWorkflow previous) {
    if (page == null || !page.hasRecord() || previous == null || !previous.isActive()) {
      return false;
    }
    List<String> targets = previous.getAuthorisedTargets();
    if (targets == null || targets.isEmpty()) {
      // An open set is not "somewhere else" — a worklist job legitimately covers records the page
      // has never shown.
      return false;
    }
    String open = shortRef(page.getModel(), page.getRecordId());
    try {
      AiTargetRegistry registry = targetRegistry();
      for (String target : targets) {
        if (registry.sameTarget(target, open)) {
          return false;
        }
      }
      return true;
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not compare the open record to the job: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Freezes the set of records this job may write to, before any tool runs.
   *
   * <h2>Trusted sources only</h2>
   *
   * Three things may establish the set, and all three are known to the server before the model is
   * called: the record the client says is open, the targets of a job already on record, and — for a
   * worklist or a delegated selection — nothing, deliberately, because there the set is open and
   * finding the records is the work.
   *
   * <p>What may <em>not</em> establish it is a tool argument. The previous version promoted an
   * unscoped job to a named one from the ids its calls happened to carry, which authorises a job by
   * the work done under it. The set is now taken before the work and never revisited.
   *
   * <p>A job that ends up with an empty set authorises nothing — {@link AiWriteAuthorisation}
   * refuses every write on it. That is the intended answer for an unscoped turn, for a delegated
   * selection that arrived empty, and for a worklist instruction covering more records than one
   * sentence may authorise.
   */
  protected void authoriseTargets(
      AiWorkflow workflow, AiRoute route, PageContext page, AiWorkflow previous) {
    authoriseTargets(workflow, route, page, previous, Collections.emptyList());
  }

  protected void authoriseTargets(
      AiWorkflow workflow,
      AiRoute route,
      PageContext page,
      AiWorkflow previous,
      List<String> explicitRefs) {
    List<String> authorised = new ArrayList<>();
    boolean elsewhere = pointsElsewhere(page, previous);
    ResolvedSet resolved = null;
    if (explicitRefs != null && !explicitRefs.isEmpty()) {
      // The user named the records themselves, in unmistakable database-id syntax, so nothing
      // else may widen the set or stand in for it — not the document that happens to be on
      // screen, not the job that was running a moment ago, not a worklist. «Валидирай
      // invoiceId=641» is about 641 and about nothing else.
      //
      // Whatever scope the route carries. The parser's output is the server's own reading of the
      // user's text; the router's scope is advisory, and a router that answered OPEN_RECORD or
      // WORKLIST for «валидирай invoiceId=641» must not turn it into "the record on screen" or "the
      // whole queue". And it ends here either way: a set that does not resolve — a mistyped id,
      // another company's, more than one instruction may name — is refused with its own code and
      // never replaced by the open record or by anything the message's other words might resolve.
      resolved = resolveExplicitSet(explicitRefs);
      authorised.addAll(resolved.authorised);
      if (authorised.isEmpty()) {
        workflow.refuseTargets(
            AiBlockerCode.EXPLICIT_REFERENCE_UNRESOLVED.wire(),
            "The records this message names by id could not all be identified as records of the"
                + " active company that this instruction may cover, so no record is authorised —"
                + " not the one on screen and not any other. Check the ids, or open the record and"
                + " say what to do with it. Nothing was changed.");
      }
      freeze(workflow, authorised, resolved);
      return;
    }
    AiBlockerCode worklistRefusal = null;
    String worklistRefusalMessage = null;
    if (route.scope() == AiScope.NAMED_RECORDS) {
      // NAMED_RECORDS with an EMPTY explicitRefs is a contradiction {@link
      // AiExplicitReferenceParser}
      // itself would never produce — {@code AiRouteAdapter} only ever selects this scope because
      // that parser found unmistakable database-id syntax. The only other source of a route is the
      // Router Agent, which is free to set all five fields on a ROUTE verdict, including scope, and
      // can call a turn NAMED_RECORDS because it read a business key — an ЕИК, a VAT number — as "a
      // record the user named", which is true in spirit but not in the narrow, syntactic sense this
      // scope exists to mean. «Направи доставчик с ЕИК 130159785 ...» routed NAMED_RECORDS with no
      // explicitRefs at all would make {@code resolveExplicitSet([])} trivially freeze an empty set
      // and refuse the complete, explicit instruction with "the set of records this instruction
      // covers could not be established" — never reaching manage_supplier's own authorisation
      // check at all. So when the deterministic parser found nothing, the message is read exactly
      // as an UNSCOPED turn would read it — the one source that still might establish what the
      // user actually named — never a tool argument.
      List<String> fromMessage = messageTargets(route, workflow.getRequest());
      if (!fromMessage.isEmpty()) {
        resolved = resolveExplicitSet(fromMessage);
        authorised.addAll(resolved.authorised);
      }
    } else if (route.scope() == AiScope.OPEN_RECORD && page != null && page.hasRecord()) {
      authorised.add(shortRef(page.getModel(), page.getRecordId()));
    } else if (elsewhere && page != null && page.hasRecord()) {
      // The conversation continues but the record does not. Whatever the scope says, the set that
      // may be written is the record in front of the user — never the previous job's, which is
      // about a document they have moved on from. This is the stale-workflow case: without it,
      // «същото» on invoice B inherited invoice A's targets and blockers and the assistant would
      // report work on a record nobody was looking at.
      authorised.add(shortRef(page.getModel(), page.getRecordId()));
    } else if (route.scope() == AiScope.CONVERSATION && previous != null) {
      // This turn's own words outrank a job already on record — the same rule {@link
      // #matchesPrevious} applies to an explicit database-id reference, applied here to whatever
      // business key (an ЕИК, a name) the message itself carries. A CONVERSATION verdict is not
      // always right about WHICH earlier proposal a message continues: a duplicate-ЕИК create left
      // blocked a moment ago, followed immediately by a complete, unrelated «Направи доставчик с
      // ЕИК 130159785 ...» for a DIFFERENT ЕИК, must never be frozen onto the stale job's own
      // target — it names its own, and that is resolved first. Only when this message names
      // nothing of its own does the job actually being resumed decide the boundary.
      List<String> fromMessage = messageTargets(route, workflow.getRequest());
      if (!fromMessage.isEmpty()) {
        resolved = resolveExplicitSet(fromMessage);
        authorised.addAll(resolved.authorised);
      } else if (route.selection() == AiSetSelection.REMAINING) {
        // «Останалите» said about the job itself: the remainder of what was already authorised,
        // never the whole set again. A target already done is not re-authorised.
        authorised.addAll(previous.remaining());
        if (authorised.isEmpty()) {
          worklistRefusal = AiBlockerCode.WORKLIST_NOTHING_REMAINING;
          worklistRefusalMessage =
              "There is nothing left of the job in progress — every record it covers is already"
                  + " done or blocked, so \"the rest\" names no record. Say which records to work"
                  + " on. Nothing was changed.";
        }
      } else {
        // Resuming a job whose own set was closed: the new job inherits that boundary rather than
        // starting an open one. Inheriting a set is never widening it.
        authorised.addAll(previous.getAuthorisedTargets());
      }
    }
    if (route.scope() == AiScope.WORKLIST) {
      // How much of the list is meant is a reading of the user's sentence, and the router — which
      // reads the message, the page and the conversation — states it as one of four structured
      // shapes. The server resolves exactly that shape from its own queries and freezes it; nothing
      // here reads the words. The predecessor did: a closed whitelist of quantifiers and verbs that
      // refused «осчетоводи фактурите», «останалите» and «следващите две» — ordinary Bulgarian for
      // three things the server can resolve exactly — and so taught users to guess magic phrases.
      Selected selected = resolveWorklist(route, previous);
      if (selected.refusal == null) {
        resolved = selected.set;
        authorised.addAll(selected.set.authorised);
      } else {
        // The message's own business keys are still read below — an ЕИК typed on the supplier list
        // names its own record — and only if they name nothing either does this refusal stand.
        worklistRefusal = selected.refusal;
        worklistRefusalMessage = selected.message;
      }
    } else if (route.scope() == AiScope.DELEGATED_SELECTION) {
      resolved = resolveSet(route, page);
      authorised.addAll(resolved.authorised);
    }
    if (authorised.isEmpty()
        && (route.scope() == AiScope.UNSCOPED
            || route.scope() == AiScope.WORKLIST
            || route.scope() == AiScope.CONVERSATION
            || route.scope() == AiScope.NAMED_RECORDS
            || (route.scope() == AiScope.OPEN_RECORD && (page == null || !page.hasRecord())))) {
      // Nothing established a set yet — the common case is UNSCOPED, but a CONVERSATION-scoped
      // turn with no previous job to inherit from (the branch above requires `previous != null`
      // and never reaches here otherwise) is just as empty-handed, and this handles exactly that
      // one: a route verdict calling this turn CONVERSATION when there is nothing
      // on record to continue must not leave a complete, self-contained instruction — name, ЕИК,
      // phone, company, all in one sentence — with no authorised target at all. The user's own
      // words may still name the record — «смени IBAN-а на доставчик с ЕИК 131071587» from the
      // supplier list or the dashboard, «Направи доставчик с ЕИК 130159785 ...» with no job
      // running — so they are read here, never from a tool argument, and validated exactly like a
      // named set.
      List<String> fromMessage = messageTargets(route, workflow.getRequest());
      if (fromMessage.isEmpty()) {
        // The words name nothing — «създай липсващия доставчик». What the server holds may still:
        // the OCR upload on screen, or a pending supplier the user named.
        fromMessage = contextTargets(route, workflow.getRequest(), page);
      }
      if (!fromMessage.isEmpty()) {
        resolved = resolveExplicitSet(fromMessage);
        authorised.addAll(resolved.authorised);
      }
    }
    if (authorised.isEmpty() && worklistRefusal == null) {
      // Last, and only when nothing else established a set: a creation instruction's own
      // destination — the kind of document and the company, from the route's task, never from a
      // tool argument. Without it every create was refused outright, because a record that does
      // not exist yet cannot be named and checkTargets refuses both a null target and a job with
      // no set (see AiCreationTarget).
      //
      // A fallback, deliberately, not an override. «Създай фактурата» with an OCR upload open
      // means "make it FROM this document", and the upload is the right target: freezing a bare
      // Create: reference there would lose the very record the instruction is about. Only when
      // the page, the selection, the job and the message's own business keys all say nothing is
      // the creation free-standing, and only then is this what it is about.
      String creation = creationTarget(route, companyIdOf(workflow));
      if (creation != null) {
        resolved = resolveExplicitSet(Collections.singletonList(creation));
        authorised.addAll(resolved.authorised);
        if (authorised.isEmpty()) {
          workflow.refuseTargets(
              AiBlockerCode.WRITE_NOT_AUTHORISED.wire(),
              "This asks for a new record to be created, and the creation could not be authorised"
                  + " in the active company — you may not have permission to create this kind of"
                  + " document. Nothing was changed.");
        }
      }
    }
    if (authorised.isEmpty() && worklistRefusal != null) {
      workflow.refuseTargets(worklistRefusal.wire(), worklistRefusalMessage);
    }
    if (authorised.isEmpty()
        && workflow.getTargetRefusalCode() == null
        && route.domain() == AiDomain.PARTNER
        && route.operation() == AiWriteOperation.CREATE) {
      // The generic "could not be established" sentence sent the model into asking for
      // confirmations and fixed wordings that could never change the answer. Say exactly what is
      // missing and the one thing the user can give that does.
      workflow.refuseTargets(
          AiBlockerCode.WRITE_NOT_AUTHORISED.wire(),
          "This asks to create a supplier, but the server could not tell WHICH supplier: the"
              + " message gives no ЕИК or supplier name, and the page has no OCR upload or supplier"
              + " invoice open whose document names one. Ask the user ONE question: the supplier's"
              + " ЕИК (and name), or to open the upload in question. Their answer naming the ЕИК is"
              + " itself the authorisation —"
              + " never ask them to confirm, choose an option or type a fixed sentence. Nothing was"
              + " changed.");
    }
    freeze(workflow, authorised, resolved);
  }

  /**
   * Records the server can tie this turn to when its words name none — see {@link
   * AiMessageTargetResolver#resolveFromContext}. Empty when the lookup is unavailable.
   */
  protected List<String> contextTargets(AiRoute route, String message, PageContext page) {
    try {
      return Beans.get(AiMessageTargetRegistry.class).resolveFromContext(route, message, page);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: context target lookup unavailable: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  /**
   * The creation destination this turn's own task establishes, or null when it creates nothing.
   *
   * <h2>Why the task, and why only an explicit one</h2>
   *
   * A creation has no record to point at, so the set has to come from what kind of document the
   * instruction asked for. That is the route's task — resolved from the user's words before any
   * tool runs, exactly like every other input to the frozen set — and never a tool argument, which
   * is the output being authorised rather than evidence for it.
   *
   * <p>Only an EXPLICIT turn qualifies. An INHERITED or IMPLIED one has not asked for anything of
   * its own: «да» after a read must not quietly mint an authorisation to create a document, and a
   * continuation already has the job it is continuing. {@code checkAuthorisation} would refuse
   * those anyway; this keeps the set from existing in the first place.
   */
  protected String creationTarget(AiRoute route, Long companyId) {
    if (route == null
        || route.explicitness() != AiExplicitness.EXPLICIT
        || route.task() == null
        || !route.task().authorisesWrites()) {
      return null;
    }
    AiDomain creates = AiCreationTarget.creates(route.task());
    if (creates == null) {
      return null;
    }
    // The route's task names the document; the company comes from the scope the turn opened in.
    return companyId == null
        ? AiCreationTarget.ref(creates)
        : AiCreationTarget.ref(creates, companyId);
  }

  private static Long companyIdOf(AiWorkflow workflow) {
    return workflow == null ? null : workflow.getCompanyId();
  }

  /** What a WORKLIST instruction resolved to, or why it could not be. */
  protected static final class Selected {

    final ResolvedSet set;
    final AiBlockerCode refusal;
    final String message;

    Selected(ResolvedSet set) {
      this.set = set;
      this.refusal = null;
      this.message = null;
    }

    Selected(AiBlockerCode refusal, String message) {
      this.set = new ResolvedSet();
      this.refusal = refusal;
      this.message = message;
    }
  }

  /**
   * Turns the router's structured reading of a list instruction into an exact, frozen set.
   *
   * <p>Four shapes and one absence, each resolved from the server's own queries:
   *
   * <ul>
   *   <li>{@link AiSetSelection#WHOLE} — the domain's whole eligible queue, as before.
   *   <li>{@link AiSetSelection#COUNT} — the first N of that queue in the provider's documented,
   *       repeatable order. N is bounded by the same ceiling every other set is held to; a count
   *       larger than the queue simply takes the queue.
   *   <li>{@link AiSetSelection#REMAINING} — the remainder of the job already on record. Never a
   *       fresh query: "the rest" is the rest of an authorisation already given, so a record that
   *       appeared since is not in it.
   *   <li>{@link AiSetSelection#FILTERED} — a subset a worklist provider declares by name. One it
   *       does not declare is refused, naming the filter, and never answered with the whole queue.
   *   <li>absent — nothing read the sentence (the deterministic fallback route). Refused with its
   *       own code rather than guessed at, because the only guess available is "all of it".
   * </ul>
   */
  protected Selected resolveWorklist(AiRoute route, AiWorkflow previous) {
    AiSetSelection selection = route.selection();
    if (selection == null) {
      return new Selected(
          AiBlockerCode.WORKLIST_SELECTION_UNRESOLVED,
          "This is an instruction about a list, and nothing established how much of the list it"
              + " covers — all of it, a given number of records, what is left of an earlier job, or"
              + " a named subset. Ask the user to select the rows, open the record, or say how many"
              + " records to work on. Nothing was changed.");
    }
    if (selection == AiSetSelection.REMAINING) {
      ResolvedSet remaining = new ResolvedSet();
      if (previous != null && previous.isActive()) {
        remaining.authorised.addAll(previous.remaining());
      }
      if (remaining.authorised.isEmpty()) {
        return new Selected(
            AiBlockerCode.WORKLIST_NOTHING_REMAINING,
            "This asks for what is left of an earlier instruction, and there is no job on record"
                + " with records still outstanding. Say which records to work on. Nothing was"
                + " changed.");
      }
      return new Selected(remaining);
    }
    int limit = maxAuthorisedTargets();
    if (selection == AiSetSelection.COUNT) {
      Integer count = route.count();
      if (count == null || count <= 0) {
        return new Selected(
            AiBlockerCode.WORKLIST_SELECTION_UNRESOLVED,
            "This asks for a particular number of records from the list, and the number could not"
                + " be read. Say how many. Nothing was changed.");
      }
      if (count > limit) {
        return new Selected(
            AiBlockerCode.WORKLIST_SELECTION_UNRESOLVED,
            "This asks for "
                + count
                + " records at once, and one instruction may cover at most "
                + limit
                + ". Ask for fewer. Nothing was changed.");
      }
      // Exactly as many as were asked for, from the head of the provider's own stable order — the
      // ceiling applies to the whole set elsewhere, but a count IS the user's own bound, so it is
      // taken as given rather than compared against the queue's total.
      return new Selected(validate(worklistCandidates(route.domain(), null, count), limit));
    }
    if (selection == AiSetSelection.FILTERED) {
      String filter = route.filter();
      if (filter == null || !supportsFilter(route.domain(), filter)) {
        return new Selected(
            AiBlockerCode.WORKLIST_FILTER_UNSUPPORTED,
            "This narrows the list in a way the server cannot resolve"
                + (filter == null ? "" : " (" + filter + ")")
                + ", and a request it cannot narrow is never answered with the whole list. Ask the"
                + " user to select the rows, open the record, or ask for every record in the list."
                + " Nothing was changed.");
      }
      // One more than the ceiling, so a subset that is too large is recognisable as too large.
      return new Selected(validate(worklistCandidates(route.domain(), filter, limit + 1), limit));
    }
    return new Selected(resolveSet(route, null));
  }

  /** The eligible refs for a domain, optionally narrowed. A seam a test overrides. */
  protected List<String> worklistCandidates(AiDomain domain, String filter, int limit) {
    return Beans.get(AiWorklistRegistry.class).candidates(domain, filter, limit);
  }

  /** Whether a worklist provider declares this subset. A seam a test overrides. */
  protected boolean supportsFilter(AiDomain domain, String filter) {
    return Beans.get(AiWorklistRegistry.class).supportsFilter(domain, filter);
  }

  /** {@code aiaccountant.workflow.max.authorised.targets}. A seam a test overrides. */
  protected int maxAuthorisedTargets() {
    return Beans.get(AiAccountantConfig.class).maxAuthorisedTargets();
  }

  /**
   * The company/permission pass every set-scoped instruction gets — the same rules {@link
   * #resolveSet} applies, over a list of refs that has already been chosen.
   */
  protected ResolvedSet validate(List<String> requested, int limit) {
    ResolvedSet resolved = new ResolvedSet();
    if (requested == null || requested.isEmpty()) {
      return resolved;
    }
    if (requested.size() > limit) {
      LOG.warn(
          "AI Accountant: a list instruction covers more than {} records; refusing to authorise a"
              + " set that large.",
          limit);
      return resolved;
    }
    AiTargetRegistry targets = targetRegistry();
    for (String ref : requested) {
      if (targets.canonical(ref) == null) {
        LOG.info("AI Accountant: '{}' is not an identifiable record of this company.", ref);
        continue;
      }
      resolved.authorised.add(ref);
      if (!targets.writable(ref)) {
        LOG.info("AI Accountant: '{}' is in the requested set but is not writable.", ref);
        resolved.unwritable.add(ref);
      }
    }
    return resolved;
  }

  /**
   * Writes the resolved set onto the job: the frozen snapshot, the matching work-in-progress
   * targets, and the requested-but-unwritable records marked blocked.
   */
  private void freeze(AiWorkflow workflow, List<String> authorised, ResolvedSet resolved) {
    workflow.setAuthorisedTargets(authorised);
    // Seeded as work-in-progress too, so a job over one named record is active from the moment it
    // opens — which is what lets a goal-less repair be a job at all, and what the authorisation
    // check reads on the very first call rather than after the first write.
    for (String ref : authorised) {
      workflow.addTarget(ref);
    }
    if (resolved != null) {
      // Records the user asked for and may not change stay in the job, marked. Dropping them
      // silently would let the assistant work on nine of ten and report ten as the set it had been
      // given, so the tenth would be invisible to the only person who could act on it.
      for (String ref : resolved.unwritable) {
        workflow.markBlocked(
            ref,
            AiBlockerCode.PERMISSION_DENIED.classification().name(),
            AiBlockerCode.PERMISSION_DENIED.wire(),
            "You do not have permission to change this record, so it was left out of the work.");
      }
    }
  }

  /**
   * Resolves records the user named in their own message into an exact, frozen set.
   *
   * <h2>All or nothing, on identity</h2>
   *
   * A worklist or a delegated selection drops a reference it cannot resolve and carries on, which
   * is right for those: the set came from the server's own query or from rows the client reported,
   * so a gap in it is a record that quietly stopped qualifying. This set came from characters in a
   * sentence. A reference that does not resolve there means the id was mistyped, belongs to another
   * company, or was never a record at all — and working on the rest of the list would answer
   * «осчетоводи invoiceId=641 и invoiceId=642» by posting one invoice and saying nothing useful
   * about the other. So one bad reference refuses the whole instruction, and the user re-states it.
   *
   * <h2>Permission is the other axis, and behaves as it does everywhere else</h2>
   *
   * A reference that resolves but cannot be written stays in the set, marked blocked, exactly as in
   * {@link #resolveSet} — see the "unwritable records stay visible" reasoning there. Identity is
   * about whether the instruction made sense; permission is about what this user may do with a
   * record that plainly exists, and silently dropping those is how the one record somebody needs to
   * grant access to becomes invisible.
   *
   * <h2>Size</h2>
   *
   * The same ceiling every other set-scoped instruction is held to, measured on the complete
   * requested list before anything is filtered out of it.
   */
  protected ResolvedSet resolveExplicitSet(List<String> explicitRefs) {
    ResolvedSet resolved = new ResolvedSet();
    if (explicitRefs == null || explicitRefs.isEmpty()) {
      return resolved;
    }
    int limit = maxAuthorisedTargets();
    if (explicitRefs.size() > limit) {
      LOG.warn(
          "AI Accountant: the message named {} records, more than the {} a single instruction may"
              + " authorise; refusing the set.",
          explicitRefs.size(),
          limit);
      return resolved;
    }
    AiTargetRegistry targets = targetRegistry();
    for (String ref : explicitRefs) {
      if (targets.canonical(ref) == null) {
        // Not this company's, or not a record at all. Never named back to the caller — a reference
        // they cannot see must not be confirmed as existing or denied as missing.
        LOG.info(
            "AI Accountant: '{}' was named in the message but is not an identifiable record of this"
                + " company; the whole named set is refused.",
            ref);
        return new ResolvedSet();
      }
    }
    for (String ref : explicitRefs) {
      resolved.authorised.add(ref);
      if (!targets.writable(ref)) {
        LOG.info("AI Accountant: '{}' was named in the message but is not writable.", ref);
        resolved.unwritable.add(ref);
      }
    }
    return resolved;
  }

  /**
   * Records a business key in the user's message identifies; empty when the lookup is unavailable.
   */
  protected List<String> messageTargets(AiRoute route, String message) {
    try {
      // The whole route, not only its domain: a resolver has to be able to tell «създай доставчик
      // с ЕИК X» from «смени IBAN-а на доставчик с ЕИК X», which carry the same business key and
      // need different targets. That reading is the router's (AiWriteOperation), never a verb list.
      return Beans.get(AiMessageTargetRegistry.class).resolve(route, message);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: message target lookup unavailable: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

  /** What a set-scoped instruction resolved to: what may be worked on, and what may not. */
  protected static final class ResolvedSet {

    final List<String> authorised = new ArrayList<>();
    final List<String> unwritable = new ArrayList<>();
  }

  /**
   * Resolves a set-scoped instruction into an exact list of records, before any tool runs.
   *
   * <h2>Why an open set could not stay open</h2>
   *
   * «Осчетоводи всичко от инбокса» must not authorise a job with no target list at all, on the
   * reasoning that finding the records <em>is</em> the work. The set would then become whatever the
   * assistant's searches return — a search is a query the model composes, so the job's boundary
   * would be drawn by the thing the boundary exists to bound. Widen the query, widen the
   * authorisation.
   *
   * <p>So the set is resolved here, from the same worklist providers that already answer "what is
   * there to work on" from the database, and frozen. A record that appears in the inbox a minute
   * later is not in it; the user asks again, and gets a job for it.
   *
   * <h2>What "safely resolved" means</h2>
   *
   * <ul>
   *   <li><b>Where the records come from</b> — a worklist instruction is resolved from the
   *       server-side providers; a delegated selection is resolved from the rows the client says
   *       the user selected, and from nothing else. The two are never substituted for one another:
   *       "these ones I highlighted" and "everything eligible" are different instructions, and
   *       answering the first with the second silently widens it.
   *   <li><b>Company</b> — every ref must resolve through {@link AiTargetRegistry}, which is
   *       company-scoped. One that does not is not a record of this company and is left out.
   *   <li><b>Permission</b> — a record the user may not change is <em>kept</em> in the set and
   *       marked blocked, not quietly removed. See below.
   *   <li><b>Size</b> — measured on the complete requested set, before any of it is filtered, and
   *       over {@code aiaccountant.workflow.max.authorised.targets} the whole set is refused rather
   *       than truncated. Counting only the writable ones would let a set of ninety pass as
   *       twenty-five, which is the same over-large authorisation arrived at more quietly.
   * </ul>
   *
   * <h2>Unwritable records stay visible</h2>
   *
   * Dropping them made the job smaller than the instruction: the assistant would work on nine of
   * ten records and report the ten as its set, so the one the user could not change was mentioned
   * by nobody. They are now part of the job, blocked with {@link AiBlockerCode#PERMISSION_DENIED},
   * and appear in the status line as blocked — which is the only way the person who can grant the
   * permission ever hears about it.
   *
   * <p>An empty answer is a refusal, not an open set: {@link AiWriteAuthorisation} declines every
   * write on a job with no authorised targets.
   */
  protected ResolvedSet resolveSet(AiRoute route, PageContext page) {
    ResolvedSet resolved = new ResolvedSet();
    int limit = maxAuthorisedTargets();
    // One more than the ceiling, so a set that is too large is recognisable as too large rather
    // than arriving pre-truncated to exactly the limit.
    List<String> requested = requestedSet(route, page, limit + 1);
    if (requested.isEmpty()) {
      LOG.info(
          "AI Accountant: {} instruction resolved to no records in {}; no writes are authorised.",
          route.scope(),
          route.domain());
      return resolved;
    }
    if (requested.size() > limit) {
      LOG.warn(
          "AI Accountant: {} instruction covers more than {} records; refusing to authorise a set"
              + " that large.",
          route.scope(),
          limit);
      return resolved;
    }
    AiTargetRegistry targets = targetRegistry();
    for (String ref : requested) {
      if (targets.canonical(ref) == null) {
        // Not this company's, or not a record at all. Nothing to authorise and nothing to report:
        // a reference the caller cannot see must not be named back to them.
        LOG.info("AI Accountant: '{}' is not an identifiable record of this company.", ref);
        continue;
      }
      if (targets.writable(ref)) {
        resolved.authorised.add(ref);
      } else {
        LOG.info("AI Accountant: '{}' is in the requested set but is not writable.", ref);
        resolved.authorised.add(ref);
        resolved.unwritable.add(ref);
      }
    }
    return resolved;
  }

  /**
   * The records the instruction asked for, before any validation.
   *
   * <p>Two sources, kept strictly apart. A worklist instruction means the eligible set the server
   * computes; a delegated selection means the rows the user actually selected, taken from the
   * request exactly as sent. There is deliberately no fallback from one to the other — a selection
   * that arrives empty is an instruction with no records in it, which is refused, not an invitation
   * to supply some.
   */
  protected List<String> requestedSet(AiRoute route, PageContext page, int limit) {
    if (route.scope() == AiScope.DELEGATED_SELECTION) {
      List<String> refs = new ArrayList<>();
      if (page == null) {
        return refs;
      }
      String defect = page.selectionDefect();
      if (defect != null) {
        // The set that arrived is not provably the set the user chose — shortened, over the
        // transport bound, or not matching the count the client declared. Working on the part that
        // did arrive would answer «направи го за тези» with a smaller job and no sign of it, so the
        // whole instruction is refused and the user is left to make the selection again.
        LOG.warn(
            "AI Accountant: refusing a delegated selection on {} ({}); the selection did not"
                + " arrive intact.",
            page.getModel(),
            defect);
        return refs;
      }
      // Exact or empty, never partial, and tied to the page's own model. See
      // PageContext.exactSelection.
      for (Long id : page.exactSelection()) {
        refs.add(shortRef(page.getModel(), id));
        if (refs.size() >= limit) {
          // The caller asks for one more than the ceiling so it can recognise an over-large set and
          // refuse it. Stopping here bounds the loop; it never becomes a smaller authorised job.
          break;
        }
      }
      return refs;
    }
    return worklistCandidates(route.domain(), null, limit);
  }

  /**
   * The job to carry into this turn: the stored one where there is one, the client's otherwise.
   *
   * <p>Never throws and never blocks the turn. A store that cannot be read leaves the assistant
   * exactly where it was before the store existed — working from the blob.
   */
  /**
   * The job as it stood before this message was read, for anything that must classify the turn
   * rather than take part in it.
   *
   * <h2>Why the shadow router may not be handed the live workflow</h2>
   *
   * {@link #open} is where this message becomes state: it mints a job, or replaces the previous
   * one, or returns it for the turn to mutate — and every one of those outcomes was decided by the
   * adapter's reading of the message. A component being compared against the adapter cannot be
   * shown the adapter's conclusions as if they were facts; it would be told the task and goal the
   * adapter just chose and asked to guess them.
   *
   * <p>So this is the same read {@link #open} performs, taken before it runs and separately from
   * it. The returned object is the live one, not a copy — callers must read it immediately and keep
   * a value of their own (the shadow path renders it to text on the spot) rather than hold the
   * reference across the turn, because a continuation hands that same object to the turn to change.
   */
  public AiWorkflow preTurnSnapshot(Long userId, Long companyId, String conversationId) {
    return resume(userId, companyId, conversationId);
  }

  /**
   * The job this user, company and conversation own — from the row, and from nowhere else.
   *
   * <h2>Why the browser's copy is not used</h2>
   *
   * A fallback to the client's copy — no row, so adopt whatever job the client returned, after
   * checking its stamp — cannot make the result an authorisation, however real each check is,
   * because the thing being checked is supplied by the caller. The stamp says which conversation
   * the client <em>claims</em> the job belongs to, the target list says which records it
   * <em>claims</em> were authorised, and a fallback that accepts them when the server has no record
   * of the job is a path by which a client can hand itself one.
   *
   * <p>So there is no fallback. A job exists when the server wrote it down, and not otherwise.
   *
   * <p><b>The cost, stated plainly.</b> A conversation whose row could not be written — the store
   * was unreachable, the conversation id was missing — has no resumable job, so «продължи» finds
   * nothing to continue and the user is asked what they want done. That is a real loss of
   * convenience on a rare path, and it is the correct trade: the alternative is an authorisation
   * whose only evidence is that somebody's browser said so.
   */
  protected AiWorkflow resume(Long userId, Long companyId, String conversationId) {
    if (userId == null || companyId == null || conversationId == null) {
      // Keyed by all three, so an incomplete key resumes nothing rather than picking up whichever
      // job this user happens to have open elsewhere.
      return null;
    }
    AiWorkflow stored;
    try {
      stored = Beans.get(AiWorkflowStore.class).load(userId, companyId, conversationId);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not load the stored workflow: {}", e.getMessage());
      return null;
    }
    return stored != null && stored.isActive() ? stored : null;
  }

  /**
   * Folds everything this turn did into the workflow.
   *
   * <p>Order is deliberate. Targets first, so a record the turn merely attempted is on the list
   * even if it then failed. Completion second, from the database. Blockers last, and never over a
   * completed record: a refusal that arrives after an invoice is already posted says nothing about
   * that invoice.
   */
  public void update(AiWorkflow workflow, AiWorkflowIntent.Stage goal) {
    update(workflow, goal, null, null, null, null);
  }

  /**
   * As above, and then writes the result down.
   *
   * <p>Everything folded in here comes from something the server observed: the tool ledger for what
   * was attempted and refused, and the records' own lifecycle for what was reached. The model's
   * account of the turn is not consulted, and there is deliberately no path by which it could be —
   * a summary of intent is not evidence about a ledger.
   */
  public void update(
      AiWorkflow workflow,
      AiWorkflowIntent.Stage goal,
      AiRoute route,
      Long userId,
      Long companyId,
      String conversationId) {
    if (workflow == null) {
      return;
    }
    try {
      fold(workflow, goal);
      // Last, once everything is folded in: the blob goes to the browser and comes back, so it has
      // to be bounded by something other than optimism.
      workflow.capToBudget();
      persist(workflow, route, userId, companyId, conversationId);
      // A target this turn proved complete must never be left blocked on whichever row is actually
      // on record for this user, company and conversation — see
      // AiWorkflowStore.reconcileCompletedTarget.
      // Ordinarily the persist above already carries this, because the same object is both the one
      // markWorkDone completed it on and the one just saved; this is the defence for the case where
      // they are not the same object.
      for (String ref : workflow.getCompleted()) {
        reconcileCompleted(ref, userId, companyId, conversationId);
      }
    } catch (RuntimeException e) {
      // A workflow that cannot be updated must never cost the user their answer. The blob simply
      // carries what it already had, which is how the conversation behaved before this existed.
      LOG.debug("AI Accountant: could not update the workflow state: {}", e.getMessage());
    }
  }

  /**
   * The folding {@link #update} does before it persists anything — targets, then completion, then
   * blockers, in that order for the reasons given on {@link #update} itself.
   *
   * <p>Pulled out so {@link #settledAfterThisTurn} can ask "if this turn's findings were folded in,
   * would the job be settled?" without persisting or reconciling anything — folding twice on the
   * same object is safe (every step here is idempotent: adding a target already present, completing
   * a target already completed, or blocking one already blocked with the same facts all no-op), so
   * running it once more for real in {@link #update} costs nothing.
   */
  protected void fold(AiWorkflow workflow, AiWorkflowIntent.Stage goal) {
    for (String ref : touchedRefs()) {
      workflow.addTarget(ref);
    }
    markCompleted(workflow, goal);
    for (AiTurnToolLog.Entry failure : AiTurnToolLog.failedWrites()) {
      if (failure.getTarget() == null) {
        continue;
      }
      if (AiBlockerCode.OCR_EVIDENCE_NOT_READY.wire().equals(failure.getBlocker())) {
        // Not yet provable either way, never a blocker on the job: the target is left off the
        // list entirely so a later turn — after the extraction lands — starts clean rather than
        // finding this one already marked blocked from evidence that has since arrived.
        continue;
      }
      // The refusal's own code decides what this means for the job; its message is only what the
      // user is shown. Tools name their blockers, so the classification must not re-derive them
      // from prose. The code is kept beside the class, so a later turn can see WHICH blocker it
      // was and not only what kind.
      workflow.markBlocked(
          failure.getTarget(),
          AiBlockerClass.of(failure.getBlocker(), null, failure.getReason()).name(),
          failure.getBlocker(),
          failure.getReason());
    }
    if (goal == null) {
      // After the blockers, not before: a record that was written and then refused later in the
      // same turn is blocked, not done, and the ledger has to be read in that order to say so.
      markWorkDone(workflow);
    }
  }

  /**
   * Whether this job would have nothing left outstanding once this turn's own findings — the same
   * ones {@link #update} is about to fold in — are accounted for.
   *
   * <h2>Why the iteration-cap notice needed this</h2>
   *
   * The turn that hits the tool-iteration cap decides what to tell the user before {@link #update}
   * ever runs, so a stage-based check that requires a lifecycle destination ({@code
   * AiWorkflowIntent.Stage}) was the only signal available to it — and a repair job, which has no
   * destination by design (see {@code OcrRepairCompletion}), can never satisfy one. The result was
   * a job that finished correctly still being told it had not: the truthful half of the answer, the
   * workflow's own completed/blocked/remaining tracking, was computed only afterwards.
   *
   * <p>This runs the same fold on a private copy, never on the live {@code workflow} and never
   * persisted — the real fold and the real persist still happen exactly once, from {@link #update}
   * after this turn's reply has already been decided. A copy that cannot be built or folded answers
   * {@code false}: the conservative direction, same as {@link AiCompletionRegistry}'s own silence
   * rule, because wrongly claiming a job unfinished costs a "say продължи" nobody needed, while
   * wrongly claiming one finished tells the user a document is done when it might not be.
   *
   * @return true only when the job has at least one target and, after folding, none of them are
   *     blocked or remaining
   */
  public boolean settledAfterThisTurn(AiWorkflow workflow, AiWorkflowIntent.Stage goal) {
    if (workflow == null || !workflow.isWriteJob()) {
      return false;
    }
    try {
      AiWorkflow copy = copy(workflow);
      if (copy == null) {
        return false;
      }
      fold(copy, goal);
      return !copy.getTargets().isEmpty() && !copy.hasRemaining() && copy.blocked().isEmpty();
    } catch (RuntimeException e) {
      LOG.debug(
          "AI Accountant: could not assess whether the job would be settled: {}", e.getMessage());
      return false;
    }
  }

  private static AiWorkflow copy(AiWorkflow workflow) {
    try {
      ObjectMapper mapper = new ObjectMapper();
      return mapper.readValue(mapper.writeValueAsString(workflow), AiWorkflow.class);
    } catch (Exception e) {
      LOG.debug("AI Accountant: could not copy the workflow: {}", e.getMessage());
      return null;
    }
  }

  /**
   * Stores the job, when there is an identity to store it under.
   *
   * <p>Separate from the folding above so a store failure cannot cost the turn what it worked out:
   * the workflow in memory is complete either way, and the reply is built from that.
   */
  protected AiWorkflowStore.Outcome persist(
      AiWorkflow workflow, AiRoute route, Long userId, Long companyId, String conversationId) {
    if (userId == null || companyId == null || conversationId == null) {
      return AiWorkflowStore.Outcome.NOT_APPLICABLE;
    }
    try {
      AiWorkflowStore.Outcome outcome =
          Beans.get(AiWorkflowStore.class).save(workflow, route, userId, companyId, conversationId);
      if (outcome == AiWorkflowStore.Outcome.CONFLICT) {
        // Another job is running for this user and company. Reported, never resolved by merging:
        // this turn's work was authorised against the job as it stood and has already happened, so
        // there is nothing to undo — but the two jobs stay two jobs.
        LOG.warn(
            "AI Accountant: workflow {} diverged from the stored job; progress was not merged.",
            workflow.getWorkflowId());
      }
      return outcome;
    } catch (RuntimeException e) {
      LOG.warn("AI Accountant: could not store the workflow: {}", e.getMessage());
      return AiWorkflowStore.Outcome.FAILED;
    }
  }

  /**
   * Best-effort call into {@link AiWorkflowStore#reconcileCompletedTarget}, never allowed to cost
   * the turn its answer.
   */
  protected void reconcileCompleted(
      String ref, Long userId, Long companyId, String conversationId) {
    try {
      Beans.get(AiWorkflowStore.class)
          .reconcileCompletedTarget(userId, companyId, conversationId, ref);
    } catch (RuntimeException e) {
      LOG.debug(
          "AI Accountant: could not reconcile completed target '{}': {}", ref, e.getMessage());
    }
  }

  /**
   * Marks every target whose record has provably reached the goal.
   *
   * <p>Reads the lifecycle of each target rather than only of the records this turn wrote, because
   * a target can reach the goal in a way this turn did not cause — the user posts one invoice in
   * another tab while the assistant works through the rest, and a job that still called it
   * remaining would go and try to post it again.
   */
  protected void markCompleted(AiWorkflow workflow, AiWorkflowIntent.Stage goal) {
    if (goal == null) {
      // A job with no destination is settled from the ledger instead, and only after the blockers
      // are known — see markWorkDone, which is called at the end of update for that reason.
      return;
    }
    AiPageContextRegistry registry = Beans.get(AiPageContextRegistry.class);
    for (AiWorkflowRecord target : new ArrayList<>(workflow.getTargets())) {
      String ref = target.getRef();
      if (AiUncertainWrites.contains(ref)) {
        // A write on this record ended without a knowable outcome. Its lifecycle may well say the
        // goal was reached — and that reading is authoritative about the record — but marking the
        // job done with it would quietly close a case that still needs somebody to look at what the
        // lost call did. The blocker recorded below is what carries it to the user instead.
        continue;
      }
      if (AiCreationTarget.parse(ref) != null) {
        // A creation destination has no lifecycle to read — there was no record when the job
        // opened, and the ref names a kind of document rather than a row. Left to the lifecycle
        // check below it could never be completed, so a creation job with a goal («създай фактура и
        // я осчетоводи») would report outstanding work for ever and the next instruction could
        // inherit it. Its own resolver answers instead, from the record the turn actually
        // committed — the same registry the goal-less path uses.
        if (Boolean.TRUE.equals(completionRegistry().isComplete(ref, workflow.getTask()))) {
          workflow.markCompleted(ref);
        }
        continue;
      }
      Long id = idOf(ref);
      if (id == null) {
        continue;
      }
      String lifecycle = registry.lifecycle(modelOf(ref), id);
      if (AiWorkflowIntent.satisfies(lifecycle, goal)) {
        workflow.markCompleted(ref);
      }
    }
  }

  /**
   * Completion for a job with no lifecycle destination.
   *
   * <h2>What "done" means when there is no stage to reach</h2>
   *
   * «Оправи 946» asks for a change, not for a state. There is nothing to compare a record against
   * afterwards — an invoice that was repaired is still a DRAFT — so completion cannot be read from
   * the lifecycle the way a posting's is.
   *
   * <h2>A successful write is progress, not completion</h2>
   *
   * The first version read it from the tool ledger: a write succeeded on the record, so the record
   * was done. That is a claim about a tool call. {@code set_invoice_line_product} on one of four
   * broken lines succeeds exactly as loudly as the call that fixes the last of them, so the job
   * would report itself finished with the invoice still unable to validate — the same false claim
   * this whole layer exists to prevent, arriving through the last door left open.
   *
   * <p>So a write only puts the record on the target list. Whether the job is done with it is asked
   * of {@link AiCompletionRegistry}, which answers from the document's own state — for a supplier
   * invoice, its readiness. No resolver for that domain means the target stays active: an
   * unverifiable outcome is not a finished one.
   *
   * <p>This still invents no goal. A repair job never claims an invoice reached a stage; it claims
   * the change it was asked for was made, which is smaller and checkable.
   *
   * <h2>A blocked record is asked again, not skipped</h2>
   *
   * A record this same turn blocked earlier — a first {@code edit_supplier_invoice} refusing an
   * IBAN that was not yet on the supplier — and then a later call in the same turn fixed, must not
   * stay blocked forever, so the completion check below does not skip {@code isBlocked()} records
   * on the reasoning that a blocker is a settled fact. It is not; {@code markBlocked} refuses to
   * touch a completed record for exactly the reverse reason, and this makes the other direction
   * true. A blocker only means "not provably done as of the last thing that touched it" — a fact
   * about that write, not about the record — so every non-uncertain target is asked about here
   * regardless of whether it currently carries one; {@link AiWorkflow#markCompleted} itself clears
   * the blocker the moment completion is proven, which is the only piece of state that needs to
   * change for the earlier refusal to stop being reported.
   *
   * <h2>A supporting record is never asked to prove itself</h2>
   *
   * {@code manage_supplier} runs along the way to an invoice's own goal — adding the bank account a
   * repair needed — and a successful call on it cannot be added here like any other write and left
   * for {@link AiCompletionRegistry} to answer for. Nothing can answer <em>soundly</em>: a bare
   * {@code Partner#id} names a record that always "exists", before the call and after, whether the
   * requested field landed or not, so treating existence as the postcondition would report a FAILED
   * {@code manage_supplier} update complete too, the moment {@link #markBlocked} added it to the
   * target list. So a bare {@code Partner#id} is filtered out of the "everything written" loops
   * below by {@link #isAffectedRecordOnly}: authorisation and the write-effects the client
   * refreshes from still see it, this job's own required-work tracking never does. A write that
   * actually failed still reaches {@code workflow.blocked()} regardless — that path runs in {@link
   * #fold}, not here, and does not filter anything, because a genuine refusal is exactly the kind
   * of fact this job must report. Proving a specific field-level postcondition — "this partner now
   * has bank account X" — takes a typed reference such as {@code
   * PartnerBankAccount:<companyId>:<partnerId>:<iban>} and a resolver that checks it, the same
   * shape {@code PartnerCreateCompletion} already uses for a creation.
   */
  protected void markWorkDone(AiWorkflow workflow) {
    if (!workflow.isWriteJob()) {
      return;
    }
    // Progress: everything written this turn is part of the job, under either of its names — except
    // a record that is only ever affected in support of the job's real target; see the class doc
    // above and isAffectedRecordOnly.
    for (String ref : AiTurnToolLog.writtenTargets()) {
      if (!isAffectedRecordOnly(ref)) {
        workflow.addTarget(ref);
      }
    }
    for (AiWriteEffects.Ref effect : AiWriteEffects.snapshot()) {
      String ref = shortRef(effect.getModel(), effect.getId());
      if (!isAffectedRecordOnly(ref)) {
        workflow.addTarget(ref);
      }
    }
    // Completion: asked of the domain, for every target, exactly as the goal-driven path asks the
    // lifecycle. A record can also become complete through work done outside this turn.
    AiCompletionRegistry completion = completionRegistry();
    for (AiWorkflowRecord record : new ArrayList<>(workflow.getTargets())) {
      if (AiUncertainWrites.contains(record.getRef())) {
        // Same rule as the goal-driven path: a record whose write outcome is unknown is not settled
        // from its own state, however healthy that state looks.
        continue;
      }
      if (completion.isComplete(record.getRef(), workflow.getTask())) {
        workflow.markCompleted(record.getRef());
      }
    }
  }

  /**
   * A record this job only ever touches in support of its real target, never as the target itself —
   * so it must never be turned into required work that a completion resolver has to sign off on.
   *
   * <h2>Why a bare {@code Partner#id} qualifies and nothing else does</h2>
   *
   * Every AI Accountant job that can reach a supplier is about an invoice, a move or an upload; the
   * supplier is written along the way, and its own "state" has no domain-defined finished stage the
   * way an invoice's readiness does — it always exists, before and after any particular field edit,
   * which is exactly why existence was never valid proof that a specific edit landed. Excluding it
   * here does not hide a failure: {@link #fold}'s blocking loop still marks a bare {@code
   * Partner#id} blocked when a write on it is refused, independently of this method and never
   * filtered — a refusal is a fact worth reporting whether or not the record is itself a "goal".
   *
   * <p>The synthetic references stay out of this exemption on purpose: {@code PartnerCreate:} names
   * an exact, checkable postcondition ({@link
   * com.axelor.apps.aiaccountant.context.AiCompletionResolver} implementations answer for it, e.g.
   * {@code PartnerCreateCompletion}), and so would a typed {@code PartnerBankAccount:} reference —
   * neither is a bare {@code Partner#id}, so neither matches the prefix check below.
   */
  protected static boolean isAffectedRecordOnly(String ref) {
    return ref != null && ref.startsWith("Partner#");
  }

  /**
   * The seam a test overrides to supply a fixed {@link AiCompletionRegistry} instead of needing a
   * running Guice injector — the same idiom {@link #targetRegistry()} uses.
   */
  protected AiCompletionRegistry completionRegistry() {
    return Beans.get(AiCompletionRegistry.class);
  }

  /**
   * Whether this turn should be asking the user anything at all.
   *
   * <p>The single question the friction rules reduce to. If the job has targets that are neither
   * done nor blocked, the next move is work, not a question — the goal and the scope are already on
   * record, so "shall I continue?", "which record?" and a numbered menu are all asking for
   * something the server can already answer.
   */
  public boolean shouldKeepWorking(AiWorkflow workflow) {
    return workflow != null && workflow.isActive() && workflow.hasRemaining();
  }

  /**
   * True when the job has anything left to say about: work not done, or work stopped.
   *
   * <p>The condition for stating the status, and deliberately not "the reply sounded evasive". What
   * remains and what is blocked are facts about records; whether the user is told them must not
   * depend on which words the model chose.
   */
  public boolean hasUnfinishedBusiness(AiWorkflow workflow) {
    return workflow != null
        && workflow.isActive()
        && (workflow.hasRemaining() || !workflow.blocked().isEmpty());
  }

  /** True when at least one blocker genuinely needs a person, so a question is legitimate. */
  public boolean needsUser(AiWorkflow workflow) {
    if (workflow == null) {
      return false;
    }
    for (AiWorkflowRecord blocked : workflow.blocked()) {
      // Both classes need a person, for different reasons: one is missing a judgement, the other is
      // missing a fact. Only the first was listed here, which meant a record with a lost write —
      // the case where a person is needed most — did not count as needing one.
      if (AiBlockerClass.HUMAN_DECISION_REQUIRED.name().equals(blocked.getBlocker())
          || AiBlockerClass.UNCERTAIN_OUTCOME.name().equals(blocked.getBlocker())) {
        return true;
      }
    }
    return false;
  }

  /**
   * The three-outcome summary, from the state rather than from the reply.
   *
   * <p>This is what a bulk job owes the user and what prose kept getting wrong: what completed,
   * what is blocked and why, and what is left. Short by construction — refs and one clause each —
   * because rule 1 is still in force and a status line that becomes a report is a status line
   * nobody reads.
   *
   * @return null when there is nothing worth saying
   */
  public String statusLine(AiWorkflow workflow, boolean bulgarian) {
    if (workflow == null || !workflow.isActive()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    if (!workflow.getCompleted().isEmpty()) {
      parts.add(
          (bulgarian ? "Готови: " : "Completed: ")
              + AiWorkflow.preview(workflow.getCompleted(), bulgarian));
    }
    List<AiWorkflowRecord> blocked = workflow.blocked();
    if (!blocked.isEmpty()) {
      List<String> items = new ArrayList<>();
      for (AiWorkflowRecord r : blocked) {
        if (items.size() == AiWorkflow.PREVIEW_REFS) {
          items.add((bulgarian ? "и още " : "and ") + (blocked.size() - items.size()));
          break;
        }
        items.add(r.getReason() == null ? r.getRef() : r.getRef() + " — " + r.getReason());
      }
      parts.add((bulgarian ? "Блокирани: " : "Blocked: ") + String.join("; ", items));
    }
    List<String> remaining = workflow.remaining();
    if (!remaining.isEmpty()) {
      // The count leads, because it is the number the user actually needs from a bulk job; the ids
      // are a sample. Forty refs in a chat bubble is not a status line, it is a list.
      parts.add(
          (bulgarian ? "Остават " : "Remaining ")
              + remaining.size()
              + ": "
              + AiWorkflow.preview(remaining, bulgarian));
    }
    return parts.isEmpty() ? null : String.join(". ", parts) + ".";
  }

  /**
   * Records this turn wrote, as the short refs the workflow keys on — except a bare {@code
   * Partner#id}, which {@link #isAffectedRecordOnly} excludes from required-work tracking for the
   * same reason {@link #markWorkDone} does. A write that failed on one still reaches {@code
   * workflow.blocked()} through {@link #fold}'s own blocking loop, which does not call this method.
   */
  protected List<String> touchedRefs() {
    List<String> refs = new ArrayList<>();
    for (AiWriteEffects.Ref ref : AiWriteEffects.snapshot()) {
      String shortRef = shortRef(ref.getModel(), ref.getId());
      if (!isAffectedRecordOnly(shortRef)) {
        refs.add(shortRef);
      }
    }
    // Writes the tools attributed to an upload, including ones that failed: a record the assistant
    // tried and could not finish is part of the job, not absent from it.
    for (AiTurnToolLog.Entry entry : AiTurnToolLog.entries()) {
      if (entry.isWrite()
          && entry.getTarget() != null
          && !refs.contains(entry.getTarget())
          && !isAffectedRecordOnly(entry.getTarget())) {
        refs.add(entry.getTarget());
      }
    }
    return refs;
  }

  /** {@code Invoice#592} — the entity name without its package. */
  public static String shortRef(String model, Long id) {
    String name = model == null ? "?" : model;
    int dot = name.lastIndexOf('.');
    return (dot < 0 ? name : name.substring(dot + 1)) + "#" + id;
  }

  /** The id half of a short ref, or null when it is not one. */
  static Long idOf(String ref) {
    if (ref == null) {
      return null;
    }
    int hash = ref.lastIndexOf('#');
    if (hash < 0) {
      return null;
    }
    try {
      return Long.parseLong(ref.substring(hash + 1).trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /**
   * The fully-qualified model a short ref names.
   *
   * <p>{@code Upload#946} is the form the OCR tools produce, because every invoice write tool takes
   * an {@code uploadId}; {@code Invoice#606} is what {@code AiWriteEffects} records. Both resolve
   * to the same invoice lifecycle through the OCR resolver, so a workflow can hold either without
   * the completion check caring which. {@code Move#102} is a journal entry, whose lifecycle its own
   * resolver answers.
   *
   * <p>A ref whose model is not named here has no lifecycle, so it can never be marked completed
   * and would stay "remaining" for the whole job — which is why a new write target belongs here as
   * well as in its resolver.
   */
  static String modelOf(String ref) {
    if (ref == null) {
      return null;
    }
    if (ref.startsWith("Upload#") || ref.startsWith("AiOcrUpload#")) {
      return "com.axelor.apps.aiocr.db.AiOcrUpload";
    }
    if (ref.startsWith("Invoice#")) {
      return "com.axelor.apps.account.db.Invoice";
    }
    if (ref.startsWith("Move#")) {
      return "com.axelor.apps.account.db.Move";
    }
    return null;
  }
}
