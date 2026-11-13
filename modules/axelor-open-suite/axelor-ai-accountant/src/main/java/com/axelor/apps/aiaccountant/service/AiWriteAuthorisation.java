package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.context.AiEvidenceNotReadyException;
import com.axelor.apps.aiaccountant.context.AiRequestContext;
import com.axelor.apps.aiaccountant.context.AiTargetRegistry;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.AiWorkflowRecord;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiRefDomain;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the conversation has actually asked for this change.
 *
 * <h2>What this is, and what it is not</h2>
 *
 * Not a permission check. Axelor decides what a user may do to a record, and every tool still
 * enforces the company scope, the document's state and its own arguments; none of that moves here.
 * This answers a different question, and one nothing was answering: <em>did anybody ask for
 * this?</em>
 *
 * <p>Until now the answer was the model's judgement of the conversation, stated in the operating
 * procedure and enforced by nothing. That worked because the model is usually right, which is a
 * different property from being checkable. A turn where the user said «виж фактура 946» and the
 * assistant decided to fix it on the way was indistinguishable, server-side, from one where they
 * asked for the fix.
 *
 * <h2>The four rules</h2>
 *
 * <ol>
 *   <li><b>EXPLICIT</b> — this turn's own words named a destination or a change. It may start a job
 *       and authorise the goal and scope it named.
 *   <li><b>INHERITED</b> — «продължи». It may continue the job already on record, and only that
 *       job: the word carries no authorisation of its own, so without an active workflow it
 *       authorises nothing.
 *   <li><b>IMPLIED and NONE</b> — the turn is about a document, or about nothing, but asked for no
 *       change. No write.
 *   <li><b>UNSCOPED</b> — nothing the server received says which records are meant. No write, and
 *       no exception for a call that names a record: see {@link #checkScope}.
 * </ol>
 *
 * <p>Within those, a write must also stay inside the company the job was authorised in, inside the
 * authorised target set — which every job has, resolved before dispatch and frozen — and inside the
 * kind of document the job is about. A job whose set could not be resolved authorises nothing;
 * there is no longer such a thing as an open set that the tools fill in as they go.
 *
 * <h2>What counts as evidence</h2>
 *
 * Only what the server received or itself decided: the message, the page the client reported, and
 * the job it persisted. Tool arguments are not evidence — they are the model's output, and the very
 * thing being authorised. An earlier version accepted a record id in the arguments as proof that
 * the user had named that record; it cannot be, because the same field looks identical when the
 * model chose the record itself.
 *
 * <p>Membership in the authorised set is therefore proved rather than assumed: {@link
 * AiTargetRegistry} resolves both references to canonical, company-scoped records and they match
 * only when those are the same — or, failing that, when a {@link
 * com.axelor.apps.aiaccountant.context.AiTargetRelationship} proves the new target belongs to one
 * that is already authorised, such as an invoice's own supplier. Anything unresolvable, and
 * anything no resolver will vouch for, is refused. That costs a refusal when a reference kind has
 * no identity resolver yet — a missing class, which shows up the first time it is used — and it
 * buys the property that nothing gets written to a record nobody named or proved related to one.
 */
public final class AiWriteAuthorisation {

  private static final Logger LOG = LoggerFactory.getLogger(AiWriteAuthorisation.class);

  private AiWriteAuthorisation() {}

  /** A refusal, with the structured code that classifies it. Never carries a stack trace. */
  public static final class Refusal {

    private final AiBlockerCode code;
    private final String message;

    Refusal(AiBlockerCode code, String message) {
      this.code = code;
      this.message = message;
    }

    public AiBlockerCode code() {
      return code;
    }

    public String message() {
      return message;
    }
  }

  /**
   * @param context the request this write belongs to, or null when there is no chat turn
   * @param tool the write about to run
   * @param args its arguments, for the tool's own statement of what it targets
   * @return null to proceed, or the refusal to answer with
   */
  public static Refusal check(AiRequestContext context, AiTool tool, JsonNode args) {
    if (context == null || tool == null) {
      // No chat turn around this call. The unattended path has its own gate — mode, allowlist and
      // the tool's own guard — and this layer is about a conversation, which that path has not got.
      return null;
    }
    AiRoute route = context.getRoute();
    AiWorkflow workflow = context.getWorkflow();
    String target = targetOf(tool, args);

    Refusal authorised = checkAuthorisation(route, workflow);
    if (authorised != null) {
      return authorised;
    }
    Refusal scoped = checkScope(route, workflow);
    if (scoped != null) {
      return scoped;
    }
    Refusal company = checkCompany(context, workflow);
    if (company != null) {
      return company;
    }
    Refusal stale = checkCompanyStillCurrent(context);
    if (stale != null) {
      return stale;
    }
    Refusal conversation = checkConversation(context, workflow);
    if (conversation != null) {
      return conversation;
    }
    AiTargetRegistry registry = Beans.get(AiTargetRegistry.class);
    TargetCheck targets = checkTargets(workflow, target, registry);
    if (targets.refusal() != null) {
      return targets.refusal();
    }
    return checkCapability(tool, workflow, targets.related());
  }

  /**
   * The outcome of {@link #checkTargets}: a refusal, or a pass that also says <em>how</em> the
   * target matched — directly, or only through a proven relationship to an authorised target — so
   * {@link #checkCapability} can tell the two apart.
   */
  static final class TargetCheck {

    private final Refusal refusal;
    private final boolean related;

    private TargetCheck(Refusal refusal, boolean related) {
      this.refusal = refusal;
      this.related = related;
    }

    static TargetCheck ok(boolean related) {
      return new TargetCheck(null, related);
    }

    static TargetCheck refuse(Refusal refusal) {
      return new TargetCheck(refusal, false);
    }

    Refusal refusal() {
      return refusal;
    }

    boolean related() {
      return related;
    }
  }

  // ───────────────────────────────────────────────────────────────────────────
  // The rules
  // ───────────────────────────────────────────────────────────────────────────

  /** Did this turn ask for a change at all, and may it? */
  static Refusal checkAuthorisation(AiRoute route, AiWorkflow workflow) {
    AiExplicitness explicitness = route.explicitness();
    if (explicitness == AiExplicitness.EXPLICIT) {
      return null;
    }
    if (explicitness == AiExplicitness.INHERITED) {
      if (workflow != null && workflow.isActive()) {
        return null;
      }
      return new Refusal(
          AiBlockerCode.WRITE_NOT_AUTHORISED,
          "This message continues an earlier instruction, but there is no job on record to"
              + " continue. Ask what should be changed, and on which records, before writing"
              + " anything. Nothing was changed.");
    }
    return new Refusal(
        AiBlockerCode.WRITE_NOT_AUTHORISED,
        "Nothing in this message asked for a change — it names no outcome and no correction, so"
            + " there is no authorisation for a write. Answer what was asked, and if a change is"
            + " needed, say what you would change and let the user ask for it. Nothing was"
            + " changed.");
  }

  /**
   * Is it established which records are meant?
   *
   * <h2>Why the arguments do not count</h2>
   *
   * The first version let an unscoped turn write as long as the call named a record, on the
   * reasoning that the id was in the arguments because the user had put it in their sentence. That
   * is a guess about where a value came from. The arguments are written by the model: they are the
   * thing being authorised, not evidence of authorisation, and accepting them as evidence means a
   * turn can authorise itself by naming a record. The one case it was meant to serve — «оправи 946»
   * typed with nothing on screen — is exactly the case where the server cannot tell that
   * instruction from the model choosing 946 on its own.
   *
   * <p>So the set has to be established from something the server received rather than generated:
   * the record on screen, a set the user explicitly delegated, or a job already on record. Where
   * none of those says anything, there is no write.
   */
  static Refusal checkScope(AiRoute route, AiWorkflow workflow) {
    if (route.scope() != AiScope.UNSCOPED) {
      return null;
    }
    if (workflow != null && workflow.isActive()) {
      // A job on record is itself trusted context: it was authorised on an earlier turn, from
      // trusted context, and persisted by the server.
      return null;
    }
    return new Refusal(
        AiBlockerCode.WRITE_NOT_AUTHORISED,
        "Nothing in this request establishes which records are meant — no record is open, no set"
            + " was delegated, and no job is in progress — so a change cannot be bounded. Open the"
            + " record, or say which records to work on. Nothing was changed.");
  }

  /**
   * Is the job still in the company it was authorised in?
   *
   * <p>The blob comes back from a browser that may have switched company since. {@code
   * AiCompanyScope} already prevents the write from reaching another company's data, so this is not
   * the last line of defence — it is the one that says <em>why</em> plainly, instead of letting a
   * cross-company continuation surface as a record that mysteriously cannot be found.
   */
  static Refusal checkCompany(AiRequestContext context, AiWorkflow workflow) {
    if (workflow == null || workflow.getCompanyId() == null || context.getCompanyId() == null) {
      return null;
    }
    if (workflow.getCompanyId().equals(context.getCompanyId())) {
      return null;
    }
    return new Refusal(
        AiBlockerCode.WRITE_NOT_AUTHORISED,
        "The job in progress was authorised in a different company from the one now active, so it"
            + " does not carry over. Say what should be done in this company. Nothing was"
            + " changed.");
  }

  /**
   * Is the company still the one this turn opened in?
   *
   * <h2>The write that was already in flight</h2>
   *
   * The company is read once, at the top of the turn, and everything downstream is authorised
   * against that reading. A turn is not instantaneous: it makes several model requests and can run
   * for tens of seconds, and the user may switch company in another tab while it does. Without this
   * the tool loop would carry on writing under an authorisation given for a company that is no
   * longer active — the company scope would still keep it inside <em>a</em> company's data, but not
   * necessarily the one whose books the user thought they were working in.
   *
   * <p>So the live value is read again here, at the last moment before the write, and compared with
   * the one the turn was authorised under. A change refuses what is left of the turn rather than
   * finishing it quietly under the old assumption.
   */
  static Refusal checkCompanyStillCurrent(AiRequestContext context) {
    Long opened = context.getCompanyId();
    if (opened == null) {
      return null;
    }
    Long live;
    try {
      live = AiCompanyScope.companyIdOrNull();
    } catch (RuntimeException e) {
      // The scope could not be read. Not evidence of a change, and not a reason to refuse work the
      // user asked for: the tool's own company scoping still stands between this and any record.
      return null;
    }
    if (live == null || live.equals(opened)) {
      return null;
    }
    return new Refusal(
        AiBlockerCode.WRITE_NOT_AUTHORISED,
        "The active company changed while this was running, so what was authorised no longer"
            + " describes the books being written to. Nothing further was changed — say what should"
            + " be done in the company now active.");
  }

  /**
   * Was this job authorised in this conversation?
   *
   * <h2>Why the panel is part of the authorisation</h2>
   *
   * Two browser tabs are one user in one company, and until the conversation became part of the key
   * they shared the stored job. The consequence was not theoretical: a job authorised by a sentence
   * typed in one tab could be continued by «продължи» typed in another, where nobody had authorised
   * anything — and the second tab's user could not see the target set they were continuing.
   *
   * <p>A workflow carried in from another conversation therefore authorises nothing — and neither
   * does one that cannot say which conversation it came from. An unstamped job is legacy state or a
   * turn the server could not attribute to a panel; either way nobody can be shown to have asked
   * for it, and "we cannot tell" is not a reason to write to somebody's books. It fails closed, at
   * the cost of one refusal on the first turn after an upgrade, which the user clears by saying
   * what they want done.
   *
   * <p>A turn with no job at all is not this check's business: the explicitness rule above has
   * already decided whether a jobless turn may write.
   */
  static Refusal checkConversation(AiRequestContext context, AiWorkflow workflow) {
    if (workflow == null) {
      return null;
    }
    if (AiConversationKey.same(workflow.getConversationId(), context.getConversationId())) {
      return null;
    }
    if (workflow.getConversationId() == null) {
      return new Refusal(
          AiBlockerCode.WRITE_NOT_AUTHORISED,
          "The job in progress cannot be traced to this conversation, so it does not authorise"
              + " anything here. Say what should be changed, and on which records. Nothing was"
              + " changed.");
    }
    return new Refusal(
        AiBlockerCode.WRITE_NOT_AUTHORISED,
        "The job in progress was authorised in a different conversation, so it does not carry over"
            + " to this one. Say what should be changed here, and on which records. Nothing was"
            + " changed.");
  }

  /**
   * Is this record one of the ones that were authorised?
   *
   * <h2>Proved the same, or refused</h2>
   *
   * The authorised set is a frozen snapshot taken before any tool ran, and membership is decided by
   * {@link AiTargetRegistry}: each reference is resolved to a canonical, company-scoped record, and
   * they match only when those resolve to the same one. {@code Upload#946} and {@code Invoice#606}
   * are the same document when the OCR module says so, and not because their kinds happen to
   * differ.
   *
   * <p>Unresolvable is a refusal. A reference kind nobody owns, a record that no longer exists, one
   * belonging to another company — none of them can be shown to be inside the set, and "cannot be
   * shown to be outside it" is not the standard an authorisation is held to.
   *
   * <p>A write that names no target at all is refused for the same reason, once the job has a
   * closed set: a change whose blast radius cannot be identified is by definition not confined to
   * the records the user named.
   *
   * <h2>A related record is not the same record</h2>
   *
   * A target that is not itself authorised may still be reached when {@link
   * AiTargetRegistry#relatedTarget} proves it is tied to one that is — an invoice job's own
   * supplier, or the supplier a create call for that job's own document is about. That match is
   * weaker than {@code sameTarget} on purpose: it is tried only after every authorised target has
   * failed to match directly, and the caller ({@link #check}) is told which kind of match it got,
   * because {@link #checkCapability} treats a related match as already having answered the domain
   * question a direct match still has to pass.
   */
  static TargetCheck checkTargets(AiWorkflow workflow, String target, AiTargetRegistry registry) {
    if (workflow == null) {
      // A chat write with no job at all: the route claimed an authorisation (checkAuthorisation
      // passed) but the turn opened no job, so no set was ever frozen — typically a route whose
      // task authorises no writes and names no goal. "No set" is not "any record": refused, for
      // the same reason an empty set is below.
      return TargetCheck.refuse(
          new Refusal(
              AiBlockerCode.WRITE_NOT_AUTHORISED,
              "No job was opened for this message, so no set of records was authorised and this"
                  + " change cannot be shown to stay inside one. Say what should be changed, and on"
                  + " which records. Nothing was changed."));
    }
    if (!workflow.hasClosedTargets() && workflow.getTargetRefusalCode() != null) {
      // The set was refused for a reason the controller could name precisely when the job opened
      // — an explicit id that does not resolve, a filtered worklist — so the write is answered
      // with that reason rather than the generic one below.
      AiBlockerCode code = AiBlockerCode.parse(workflow.getTargetRefusalCode());
      return TargetCheck.refuse(
          new Refusal(
              code == null ? AiBlockerCode.WRITE_NOT_AUTHORISED : code,
              workflow.getTargetRefusalMessage()));
    }
    if (!workflow.hasClosedTargets()) {
      // Every job now carries an exact set, resolved before dispatch. An empty one means it could
      // not be resolved safely — nothing eligible, more records than one instruction may authorise,
      // or none that survived the company and permission checks — and an unresolved set authorises
      // nothing. It is never an "open" set to be filled in later by whatever the tools find.
      return TargetCheck.refuse(
          new Refusal(
              AiBlockerCode.WRITE_NOT_AUTHORISED,
              "The set of records this instruction covers could not be established — there may be"
                  + " nothing eligible, more of them than one instruction may authorise, or some the"
                  + " user cannot change. Say which records to work on. Nothing was changed."));
    }
    if (target == null) {
      return TargetCheck.refuse(
          new Refusal(
              AiBlockerCode.WRITE_NOT_AUTHORISED,
              "The job in progress covers particular records, and this change does not say which"
                  + " record it is aimed at, so it cannot be shown to stay inside them. Nothing was"
                  + " changed."));
    }
    for (String authorised : workflow.getAuthorisedTargets()) {
      if (!registry.sameTarget(authorised, target)) {
        continue;
      }
      // In the set, but the set records that this one may not be changed by this user. It is kept
      // in the job so it is reported rather than forgotten; it is not kept in order to be written.
      AiWorkflowRecord record = workflow.find(authorised);
      if (record != null
          && AiBlockerCode.PERMISSION_DENIED.wire().equals(record.getBlockerCode())) {
        return TargetCheck.refuse(
            new Refusal(
                AiBlockerCode.PERMISSION_DENIED,
                "This record is part of the job but you do not have permission to change it, so it"
                    + " was left out of the work. Report it as blocked. Nothing was changed."));
      }
      return TargetCheck.ok(false);
    }
    for (String authorised : workflow.getAuthorisedTargets()) {
      try {
        if (registry.relatedTarget(authorised, target)) {
          return TargetCheck.ok(true);
        }
      } catch (AiEvidenceNotReadyException notReady) {
        // Neither proven related nor proven unrelated: the evidence this pairing would be checked
        // against has not been committed yet. A different, retryable refusal from the ordinary
        // "not one of the authorised targets" — see AiBlockerCode.OCR_EVIDENCE_NOT_READY — because
        // this is a fact about timing, not about whether the two records belong together.
        return TargetCheck.refuse(
            new Refusal(AiBlockerCode.OCR_EVIDENCE_NOT_READY, notReady.getMessage()));
      }
    }
    return TargetCheck.refuse(
        new Refusal(
            AiBlockerCode.WRITE_NOT_AUTHORISED,
            "The authorised job covers "
                + String.join(", ", workflow.getAuthorisedTargets())
                + " and "
                + target
                + " could not be shown to be one of them, or related to one of them. Finish or"
                + " report on the records that were named; if this one should be included, the user"
                + " has to say so. Nothing was changed."));
  }

  /**
   * Is this tool about the same kind of document as the job?
   *
   * <p>Read from the job's own targets rather than from the route, because the route's domain is
   * partly an inference from a sentence while a target is a record that was actually written. A job
   * that has touched invoices does not get to post a journal entry on the same authorisation.
   *
   * <p>Silent on everything else. A cross-cutting capability ({@link AiCapability#CORE}, {@link
   * AiCapability#ACCOUNTING}) belongs to no single document and is not restricted here, and a job
   * with no targets yet has not established a domain to conflict with.
   *
   * <h2>A related match already answered this</h2>
   *
   * When {@link #checkTargets} reached this call only through {@link
   * AiTargetRegistry#relatedTarget}, some resolver has already proved the target belongs to one of
   * the job's own authorised records — which is a stronger, document-specific check than comparing
   * two domain labels. Re-running the label comparison on top of that would only ever produce a
   * false refusal: a related match is, by construction, never the same domain as the job's own
   * document (a supplier's {@code Partner} record next to a {@code SUPPLIER_INVOICE} job, for
   * instance), so the label check exists to catch a target that was never verified this way at all.
   */
  static Refusal checkCapability(AiTool tool, AiWorkflow workflow, boolean relatedMatch) {
    if (relatedMatch) {
      return null;
    }
    AiDomain toolDomain = domainOf(tool.capability());
    if (toolDomain == null || workflow == null || workflow.getTargets().isEmpty()) {
      return null;
    }
    AiDomain jobDomain = null;
    for (AiWorkflowRecord record : workflow.getTargets()) {
      AiDomain domain = domainOfRef(record.getRef());
      if (domain == null && AiRefDomain.rowDependent(record.getRef())) {
        // An Invoice ref IS a document with a domain — supplier or customer — and this is the case
        // where that domain could not be read: the row is gone, two resolvers disagree, or the
        // registry was unavailable. Treating that as "unprovable, so not a conflict" is how a
        // customer-invoice write walks into a supplier-invoice job. So it fails closed instead:
        // the one thing that would decide whether this call belongs to the job is missing.
        LOG.warn(
            "AI Accountant: '{}' is a target of the job in progress but its invoice direction could"
                + " not be determined; refusing the write rather than assuming it matches.",
            record.getRef());
        return new Refusal(
            AiBlockerCode.WRITE_NOT_AUTHORISED,
            "One of the records this job was authorised for can no longer be identified, so this"
                + " change cannot be checked against it. Say again which record to work on."
                + " Nothing was changed.");
      }
      if (domain == null) {
        // A target whose kind this module does not recognise makes the job's domain unprovable,
        // and an unprovable conflict is not a conflict.
        return null;
      }
      if (jobDomain == null) {
        jobDomain = domain;
      } else if (jobDomain != domain) {
        // The job already spans more than one kind of document, so it is not a single-domain job
        // and there is nothing for this call to be outside of.
        return null;
      }
    }
    if (jobDomain == null || jobDomain == toolDomain) {
      return null;
    }
    return new Refusal(
        AiBlockerCode.WRITE_NOT_AUTHORISED,
        "The job in progress is about "
            + jobDomain.name()
            + " records and this change is a "
            + toolDomain.name()
            + " one, which it was not authorised for. Say what should be done with the "
            + toolDomain.name()
            + " record. Nothing was changed.");
  }

  // ───────────────────────────────────────────────────────────────────────────
  // Mapping
  // ───────────────────────────────────────────────────────────────────────────

  /** The document domain a capability belongs to, or null when it belongs to no single one. */
  static AiDomain domainOf(AiCapability capability) {
    if (capability == null) {
      return null;
    }
    switch (capability) {
      case SUPPLIER_INVOICE:
        return AiDomain.SUPPLIER_INVOICE;
      case CUSTOMER_INVOICE:
        return AiDomain.CUSTOMER_INVOICE;
      case JOURNAL_ENTRY:
        return AiDomain.JOURNAL_ENTRY;
      case BANK_STATEMENT:
        return AiDomain.BANK_STATEMENT;
      case PARTNER:
        return AiDomain.PARTNER;
      default:
        // CORE and ACCOUNTING cut across documents: a company, a journal, an account, a period.
        return null;
    }
  }

  /**
   * The domain a short ref names, or null when it cannot be settled.
   *
   * <p>Delegated to {@link AiRefDomain} so routing and this check read every reference the same
   * way. Null has two causes there — an unknown kind, and a known kind whose row could not be read
   * — and {@link AiRefDomain#rowDependent} is what tells {@link #checkCapability} which one it is
   * looking at.
   */
  static AiDomain domainOfRef(String ref) {
    return AiRefDomain.of(ref);
  }

  /** What the tool says it is aimed at. Defensive: a tool that throws here names nothing. */
  static String targetOf(AiTool tool, JsonNode args) {
    try {
      return tool.targetKey(args);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
