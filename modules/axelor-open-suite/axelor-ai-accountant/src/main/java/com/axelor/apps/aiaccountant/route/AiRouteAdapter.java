package com.axelor.apps.aiaccountant.route;

import com.axelor.apps.aiaccountant.dto.AiConversationState;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.prompt.AiTaskResolver;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import com.axelor.inject.Beans;
import java.util.Collections;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds an {@link AiRoute} out of the resolvers that already exist.
 *
 * <h2>A wrapper, deliberately, and not a rewrite</h2>
 *
 * Every answer here comes from code that is already deciding these things in production: {@code
 * AiTaskResolver} for the task, {@code AiWorkflowIntent.requested} for the goal, {@code
 * delegatesSelection} and the page context for the scope. This class calls them in one place and
 * writes the answers down together. It adds no new matching of its own — no new stem list, no
 * second reading of the message — because a second opinion about the same sentence is exactly the
 * kind of thing that disagrees with the first one in production.
 *
 * <p>The domain is the only genuinely new answer, and it is derived structurally: from the task
 * where the task says (every non-GENERAL task today is supplier-invoice work), otherwise from the
 * model name the client reported for the open page. A model name is a fact, not an interpretation.
 *
 * <h2>Where the Router Agent goes</h2>
 *
 * Here, behind this signature. When routing becomes a model decision it produces an {@code AiRoute}
 * from the same inputs, and this implementation becomes the fallback for when it is unavailable,
 * unconfigured or too slow to be worth waiting for. Nothing downstream has to change, because
 * nothing downstream knows which of the two produced the route.
 */
public final class AiRouteAdapter {

  private static final Logger LOG = LoggerFactory.getLogger(AiRouteAdapter.class);

  private AiRouteAdapter() {}

  /**
   * @param message the user's own words this turn
   * @param page what the client says is on screen, or null
   * @param state the conversation state the client carried back, or null
   */
  public static AiRoute resolve(String message, PageContext page, AiConversationState state) {
    return resolve(message, page, state, null);
  }

  /**
   * As the four-argument form, told which records the user's own message named.
   *
   * <p>Separate overload rather than a changed signature so callers that have no parser output —
   * and the tests that predate it — keep resolving exactly as before, with no named records.
   *
   * @param explicitRefs candidate references from {@link AiExplicitReferenceParser}, never
   *     validated here; this class only counts them
   */
  public static AiRoute resolve(
      String message,
      PageContext page,
      AiConversationState state,
      AiWorkflow previous,
      List<String> explicitRefs) {
    return resolveInternal(message, page, state, previous, explicitRefs);
  }

  /**
   * As above, told which job the server holds.
   *
   * <h2>What "resumable" is allowed to mean</h2>
   *
   * It used to mean "the client returned a blob with a workflow in it". That made the browser the
   * arbiter of whether «продължи» inherits a goal — and an inherited goal is an authorisation the
   * user gave earlier and has not repeated, so it is precisely the thing that must not be
   * establishable by a caller. It now means the server has an ACTIVE row for this user, company and
   * conversation, and nothing else does.
   *
   * <p>With no row, «продължи» resolves to no goal and no inherited explicitness — the same reading
   * the word gets on a first message, which is the honest one: there is nothing on record to
   * continue.
   *
   * <h2>Applicability is decided AFTER routing, from the route's own domain</h2>
   *
   * Task and domain are resolved first, exactly as if every stored job applied — a continuation may
   * still inherit its task, precisely as before. Only once the domain that routing actually
   * selected is known is the stored job compared against it, and only on that one structural fact:
   * the domain its own stored task belongs to. No separate reading of the message decides this
   * ahead of time; that would be a second, unpublished opinion about the same sentence, hiding a
   * job routing itself never disagreed with, or keeping one alive that it did.
   *
   * @param previous the stored job, or null when the server holds none
   */
  public static AiRoute resolve(
      String message, PageContext page, AiConversationState state, AiWorkflow previous) {
    return resolveInternal(message, page, state, previous, Collections.emptyList());
  }

  private static AiRoute resolveInternal(
      String message,
      PageContext page,
      AiConversationState state,
      AiWorkflow previous,
      List<String> explicitRefs) {
    AiTask task = AiTaskResolver.resolve(message, state, previous);
    AiDomain domain = domain(task, page, explicitRefs);

    // Whether the stored job applies to THIS turn: its own domain against the one routing just
    // selected. Nothing here ends the job — a turn that finds it inapplicable simply does not
    // resume it, and an explicit later return to its own page or document reads it exactly as
    // before, because the row itself is never touched.
    AiDomain previousDomain = domainOf(previous);
    boolean applies = previous != null && (previousDomain == null || previousDomain == domain);
    AiWorkflow effectivePrevious = applies ? previous : null;

    AiWorkflowIntent.Stage own = AiWorkflowIntent.requested(message);
    // «Продължи» carries no target of its own, so whether it means anything depends entirely on
    // whether the SERVER has an applicable job on record to mean it about. With none, this reads
    // exactly as a first message would.
    boolean continuation = AiWorkflowIntent.isContinuation(message);
    boolean resumable = continuation && effectivePrevious != null && effectivePrevious.isActive();

    // Exactly the ladder AiAccountantServiceImpl.requestedStage walks, and for the same reason:
    // «продължи» authorises nothing of its own, so the goal is the one already on record.
    AiWorkflowIntent.Stage goal = own;
    AiExplicitness explicitness = own == null ? AiExplicitness.NONE : AiExplicitness.EXPLICIT;

    // A destination is not the only explicit instruction. «Оправи 946» names no stage and is still
    // a plain request to change something, so the task the user's OWN WORDS name is read here —
    // fromMessage, not resolve, because resolve deliberately falls back to the page and to the
    // previous operation, and a task inherited from context authorises nothing.
    if (goal == null && AiTaskResolver.fromMessage(message).authorisesWrites()) {
      explicitness = AiExplicitness.EXPLICIT;
    }

    if (goal == null && explicitness == AiExplicitness.NONE && resumable) {
      goal = AiWorkflowIntent.requested(effectivePrevious.getRequest());
      explicitness = goal == null ? AiExplicitness.NONE : AiExplicitness.INHERITED;
    }
    if (explicitness == AiExplicitness.NONE && resumable) {
      // «Продължи» on a job whose authorisation was a repair rather than a destination. It still
      // inherits — what it may do is bounded by the workflow it resumes, not by this word.
      explicitness = AiExplicitness.INHERITED;
    }
    if (explicitness == AiExplicitness.NONE && task != AiTask.GENERAL) {
      // A document is plainly in view and a job is plainly meant, but nothing was asked for.
      // Says what the turn is about; says nothing about what may be written.
      explicitness = AiExplicitness.IMPLIED;
    }

    if (task != AiTask.GENERAL && task.domain() != null && task.domain() != domain) {
      // The task names a document the resolved domain contradicts — typically a task inherited
      // from a job the paragraph above has just decided does not apply here. A write-authorising
      // task is never carried over to the wrong domain: this turn named no write for THAT
      // document, so none is inferred for it — GENERAL, no playbook, no capabilities. A read-only
      // task instead becomes the resolved domain's own review task, so a plain question still gets
      // a proper, read-only answer about the document actually in front of the user, rather than
      // none at all.
      task = task.authorisesWrites() ? writeTaskFor(domain, task) : reviewTaskFor(domain);
    }

    return new AiRoute(
        domain, task, goal, scope(message, page, resumable, explicitRefs), explicitness);
  }

  /**
   * The stored workflow, unless the SELECTED route names a different document. Takes the route's
   * own {@code domain} — resolved from the page and the task, never re-derived from a fresh guess
   * at the message — so a caller deciding what a turn may resume agrees with what {@link #resolve}
   * decided internally, from the same one comparison.
   *
   * <p>The row itself is never touched; only whether it applies to this one turn's route.
   */
  public static AiWorkflow compatibleWorkflow(AiWorkflow previous, AiDomain domain) {
    if (previous == null) {
      return null;
    }
    AiDomain previousDomain = domainOf(previous);
    if (previousDomain != null && previousDomain != domain) {
      return null;
    }
    return previous;
  }

  /**
   * The domain the stored workflow's own task belongs to, or null when the task is missing, {@code
   * GENERAL}, or not a name this build of {@link AiTask} recognises — a row written by an older
   * version is read as "unknown", never as a mismatch.
   */
  static AiDomain domainOf(AiWorkflow workflow) {
    if (workflow == null || workflow.getTask() == null) {
      return null;
    }
    try {
      return AiTask.valueOf(workflow.getTask()).domain();
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * The domain's own read-only task, or {@link AiTask#GENERAL} when it has none (PARTNER today).
   * Never a task that writes — a mismatch is resolved to what the resolved document can safely be
   * asked ABOUT, never to an inferred instruction to change it.
   */
  static AiTask reviewTaskFor(AiDomain domain) {
    if (domain == AiDomain.SUPPLIER_INVOICE) {
      return AiTask.REVIEW_SUPPLIER_INVOICE;
    }
    if (domain == AiDomain.CUSTOMER_INVOICE) {
      return AiTask.REVIEW_CUSTOMER_INVOICE;
    }
    if (domain == AiDomain.JOURNAL_ENTRY) {
      return AiTask.REVIEW_JOURNAL_ENTRY;
    }
    if (domain == AiDomain.BANK_STATEMENT) {
      return AiTask.REVIEW_BANK_STATEMENT;
    }
    if (domain == AiDomain.PARTNER) {
      return AiTask.REVIEW_SUPPLIER;
    }
    return AiTask.GENERAL;
  }

  /**
   * The write task a change request becomes when the page names a different document than the words
   * did.
   *
   * <p>A supplier's own form (Purchases → Suppliers) is the one case where the words alone cannot
   * say it: «смени IBAN-а» reads as an invoice repair, and the page is what says the record being
   * changed is a supplier. The user asked for a change on the supplier in front of them, so it
   * stays a change — dropping it to GENERAL is what left every such turn without a job. A supplier
   * change asked from an invoice page is the invoice job's own supplier step, as it always was.
   * Everything else is still GENERAL: a write is never re-aimed at a different kind of document.
   */
  static AiTask writeTaskFor(AiDomain domain, AiTask task) {
    if (domain == AiDomain.PARTNER) {
      return AiTask.MAINTAIN_SUPPLIER;
    }
    if (task == AiTask.MAINTAIN_SUPPLIER && domain == AiDomain.SUPPLIER_INVOICE) {
      return AiTask.REPAIR_SUPPLIER_INVOICE;
    }
    return AiTask.GENERAL;
  }

  /**
   * The document kind: the open page's model where it names one, the task otherwise.
   *
   * <h2>Why the page outranks the task</h2>
   *
   * Every task constant today is a supplier-invoice one, because that is the only domain with
   * playbooks — so «осчетоводи» resolves to POST_SUPPLIER_INVOICE whatever is on screen, including
   * a journal entry. Taking the domain from the task would then describe a posting of Move 102 as
   * supplier-invoice work, which is not a harmless mislabel: the domain is what a later phase reads
   * to decide whether a tool belongs to this job at all.
   *
   * <p>The page is a fact the client reported; the task is an inference from a sentence. The fact
   * wins. Only exact model names are read — a page this module has never heard of falls through to
   * the task rather than being guessed at from a substring.
   */
  static AiDomain domain(AiTask task, PageContext page) {
    return domain(task, page, Collections.emptyList());
  }

  /**
   * As above, with the records the user named in their own message ranked first.
   *
   * <p>Same ordering as {@link #scope}: a user who typed {@code invoiceId=641} has said which
   * document they mean, and the one that happens to be on screen is not an argument against it. It
   * also matters more here than it looks — {@code Invoice#641} is a supplier invoice or a customer
   * one depending on the row, and the domain is what decides which playbook the turn gets and,
   * later, whether a tool call belongs to the job at all. Reading it off the page while the user
   * named a different invoice would authorise a job about the wrong direction of document.
   */
  static AiDomain domain(AiTask task, PageContext page, List<String> explicitRefs) {
    AiDomain named = domainOfRefs(explicitRefs);
    if (named != null) {
      return named;
    }
    AiDomain fromPage =
        domainOfModel(
            page == null ? null : page.getModel(), page == null ? null : page.getRecordId());
    if (fromPage != AiDomain.GENERAL) {
      return fromPage;
    }
    // The task's own domain, not an assumption about it. This used to answer SUPPLIER_INVOICE for
    // every task that was not GENERAL, which was true while supplier invoices were the only domain
    // with tasks and silently wrong the moment a second one had them.
    if (task != null && task.domain() != null) {
      return task.domain();
    }
    return AiDomain.GENERAL;
  }

  /**
   * The single domain every record the user named belongs to, or null when there is no single one.
   *
   * <h2>Why an Invoice reference is resolved here and not assumed</h2>
   *
   * {@code Invoice#641} does not say which direction of invoice it is; only the row does. So each
   * reference goes through {@link AiRefDomain}, which asks the module that owns invoices. A
   * reference that cannot be classified — a deleted row, another company's id, an unknown kind —
   * makes the answer null rather than a guess, and routing then falls back to the page and the task
   * exactly as it did before any of this existed. Guessing SUPPLIER_INVOICE from the word
   * "Invoice", which is what the old switch did, is the one thing that must not happen: it is a
   * customer invoice mislabelled as a supplier one, before any authorisation has run.
   *
   * <p>A mixed set is also null. «осчетоводи invoiceId=641 и moveId=102» is not a single-domain
   * instruction, and pretending it is would give the turn one document's playbook for two.
   */
  static AiDomain domainOfRefs(List<String> explicitRefs) {
    if (explicitRefs == null || explicitRefs.isEmpty()) {
      return null;
    }
    AiDomain answer = null;
    for (String ref : explicitRefs) {
      AiDomain candidate;
      try {
        candidate = AiRefDomain.of(ref);
      } catch (RuntimeException e) {
        LOG.debug("AI Accountant: could not classify named ref '{}': {}", ref, e.getMessage());
        return null;
      }
      if (candidate == null) {
        return null;
      }
      if (answer == null) {
        answer = candidate;
      } else if (answer != candidate) {
        return null;
      }
    }
    return answer;
  }

  /**
   * The domain an exact entity name belongs to, or GENERAL when nobody here knows it.
   *
   * <h2>Why an entity name is no longer a complete answer</h2>
   *
   * {@code com.axelor.apps.account.db.Invoice} used to map straight to {@link
   * AiDomain#SUPPLIER_INVOICE}, which was true while that was the only kind of invoice the
   * assistant knew. A customer invoice is the same entity with a different {@code
   * operationTypeSelect}, so the class name alone now separates nothing, and separating them needs
   * to read the row — which needs {@code axelor-account}, which this module does not depend on and
   * should not.
   *
   * <p>So the question goes to {@link AiRecordDomainRegistry} first, and whichever module owns the
   * document answers it. What remains — entity names whose class genuinely is the whole answer — is
   * {@link AiRefDomain#ofModel}, kept beside the reference-kind table so the two cannot drift. A
   * model neither claims falls through to GENERAL, exactly as an unknown page always has.
   */
  static AiDomain domainOfModel(String model, Long recordId) {
    if (model == null) {
      return AiDomain.GENERAL;
    }
    AiDomain owned = null;
    try {
      owned = Beans.get(AiRecordDomainRegistry.class).domainOfModel(model, recordId);
    } catch (RuntimeException e) {
      // Routing must not fail because a resolver or the injector did; an unclassified page is the
      // same position this method was in before the registry existed.
      LOG.debug("AI Accountant: record-domain registry unavailable: {}", e.getMessage());
    }
    if (owned != null) {
      return owned;
    }
    AiDomain byClass = AiRefDomain.ofModel(model);
    return byClass == null ? AiDomain.GENERAL : byClass;
  }

  /**
   * How the set of records was established, in the order the answers override each other.
   *
   * <p>{@link AiScope#NAMED_RECORDS} wins outright. It is produced only from references the user
   * wrote in unmistakable database-id syntax ({@code invoiceId=641}, {@code Invoice#641}), parsed
   * once by {@link AiExplicitReferenceParser} before routing and handed in — this method does not
   * read the message for them and does not validate them, it only asks whether there were any.
   * Whether they name real records of this company that this user may write to is settled by {@code
   * AiWorkflowController}, which freezes the set; the ids never enter a route. A user who typed an
   * id has said which record they mean, and neither a resumed job nor the document that happens to
   * be on screen is an argument against it.
   *
   * <p>A resumable continuation is next — «продължи» means the workflow's targets and nothing the
   * page happens to be showing, so long as the message named no record of its own. Delegated
   * selection comes after that, because handing the choice over is a statement about the set. Only
   * then does the screen speak.
   */
  static AiScope scope(String message, PageContext page, boolean resumable) {
    return scope(message, page, resumable, Collections.emptyList());
  }

  static AiScope scope(
      String message, PageContext page, boolean resumable, List<String> explicitRefs) {
    if (explicitRefs != null && !explicitRefs.isEmpty()) {
      return AiScope.NAMED_RECORDS;
    }
    if (resumable) {
      return AiScope.CONVERSATION;
    }
    // Only an actual selection makes a delegated selection. The phrase alone used to be enough,
    // and the set was then filled in from the worklist — which answers a different question:
    // "these ones, which I have highlighted" is not "everything currently eligible". With no
    // selection the page decides the scope as it would have anyway, and a phrase like «вземи някой»
    // over an open list is a worklist instruction, which it always was.
    // Declared, not validated: a selection that arrived damaged is still a turn about a selection,
    // and it belongs on the delegated path so it is refused there. Reading it as "nothing selected"
    // would route it to the worklist instead, which is how "these three" quietly becomes
    // "everything eligible" — the exact substitution the paragraph above exists to prevent.
    if (page != null && page.hasDeclaredSelection()) {
      return AiScope.DELEGATED_SELECTION;
    }
    if (page != null && page.hasRecord()) {
      return AiScope.OPEN_RECORD;
    }
    if (page != null && page.hasModel()) {
      return AiScope.WORKLIST;
    }
    return AiScope.UNSCOPED;
  }
}
