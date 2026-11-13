package com.axelor.apps.aiaccountant.service;

import java.util.Locale;

/**
 * The refusals this system can produce, each named once and classified once.
 *
 * <h2>Why the words were not good enough</h2>
 *
 * {@link AiBlockerClass} used to read the refusal's prose: a message containing "account" meant the
 * assistant could fix it, one containing "duplicate" meant a person had to decide. That worked only
 * for as long as nobody reworded a message — and the strings it read are operator-facing English
 * and Bulgarian written to be helpful to an accountant, not to be parsed. Two of them contained
 * both words. One contained the text of an <em>earlier</em> failure, so the same refusal classified
 * differently depending on what had failed before it.
 *
 * <p>So the refusal now says what it is. The code is the contract, the message is the explanation,
 * and rewording the explanation cannot change what the orchestrator does about it.
 *
 * <h2>Two families, one vocabulary</h2>
 *
 * <ul>
 *   <li><b>Envelope codes</b> — the {@code error} field {@code AiToolDispatcher} already puts on
 *       every refusal. Lower-case, and they were always structured; they simply had their mapping
 *       written out as a switch somewhere else.
 *   <li><b>Business blockers</b> — the {@code blocker} detail a tool adds when a domain rule
 *       stopped it. Upper-case, one per refusal site that actually exists. None is speculative:
 *       every constant below replaces a specific {@code throw} whose class was previously guessed
 *       from its wording.
 * </ul>
 *
 * <p>Adding a refusal means adding a constant here. That is the point — a blocker with no code
 * falls back to the prose matching in {@link AiBlockerClass}, which is a fallback rather than a
 * design, and the compiler cannot tell you that you needed one.
 */
public enum AiBlockerCode {

  // ── envelope codes: the dispatcher's own refusals ──────────────────────────

  /** The model asked for a tool that is not registered. */
  UNKNOWN_TOOL("unknown_tool", AiBlockerClass.CAPABILITY_MISSING),

  /** Writes are off, the tool is not in the allowlist, or its unattended guard refused. */
  WRITE_NOT_ALLOWED("write_not_allowed", AiBlockerClass.CAPABILITY_MISSING),

  /** The tool threw something that was not a refusal. */
  TOOL_FAILED("tool_failed", AiBlockerClass.TECHNICAL_FAILURE),

  /** The result could not be serialised, so the model never saw it. */
  UNSERIALISABLE_RESULT("unserialisable_result", AiBlockerClass.TECHNICAL_FAILURE),

  /** The arguments were not what the schema promised. */
  BAD_ARGUMENTS("bad_arguments", AiBlockerClass.TECHNICAL_FAILURE),

  /** No authenticated user, so nothing may be read or written on anyone's behalf. */
  NO_USER("no_user", AiBlockerClass.TECHNICAL_FAILURE),

  /** No company scope could be established. */
  NO_COMPANY_SCOPE("no_company_scope", AiBlockerClass.TECHNICAL_FAILURE),

  /** Both a user and a system scope were open — an invariant violation, not a document problem. */
  AMBIGUOUS_CONTEXT("ambiguous_context", AiBlockerClass.TECHNICAL_FAILURE),

  /**
   * Never ran: an earlier failure in the same batch, or a duplicate call.
   *
   * <p>Fixable because nothing was attempted, so nothing is blocked. A batch abort must never read
   * as a reason to stop and ask about records that were never tried.
   */
  NOT_EXECUTED("not_executed", AiBlockerClass.FIXABLE),

  /** The same call, twice in one response. As above: nothing was blocked. */
  DUPLICATE_CALL("duplicate_call", AiBlockerClass.FIXABLE),

  /**
   * The repeat guard refused an identical call on unchanged state.
   *
   * <p>Not fixable by trying again, which is the whole point of the guard. Whatever really blocks
   * the record was recorded when the call first failed.
   */
  ALREADY_FAILED("already_failed", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  // ── envelope codes: time and transport ─────────────────────────────────────

  /**
   * A read gave up before it answered.
   *
   * <p>Technical rather than uncertain: a read that timed out changed nothing whatever happened on
   * the server, so the record is in whichever state it was already in and asking again is safe.
   */
  TIMEOUT("timeout", AiBlockerClass.TECHNICAL_FAILURE),

  /**
   * The call could not reach something able to serve it — a refused connection, a 429, a 503 — and
   * the bounded retries were spent.
   */
  TRANSIENT_FAILURE("transient_failure", AiBlockerClass.TECHNICAL_FAILURE),

  /**
   * The turn's time budget ran out before this call was started.
   *
   * <p>Nothing was attempted, so it is fixable in the same sense {@link #NOT_EXECUTED} is: the
   * record is untouched and the work can be asked for again in a new turn.
   */
  TURN_DEADLINE_EXCEEDED("turn_deadline_exceeded", AiBlockerClass.FIXABLE),

  /**
   * A write ended without a knowable outcome, and re-reading the record could not settle it.
   *
   * <p>The one refusal that does not say what happened. It is returned only after {@code
   * AiWriteReconciliation} has failed to prove the write did not land, so it means precisely: the
   * change may be in the database, and no further call may assume either way. Never retried, never
   * reported as a failure, and never counted as progress.
   */
  UNCERTAIN_WRITE("uncertain_write", AiBlockerClass.UNCERTAIN_OUTCOME),

  /**
   * A target relationship could not be proven because the evidence it depends on — an OCR upload's
   * own extraction — has not been committed yet.
   *
   * <p>Technical, not a refusal about the two records: nothing here says they are unrelated, only
   * that the fact needed to say either way is not there yet. Retryable once the extraction lands,
   * and never turned into a permanent blocker on the workflow — see {@code AiWorkflowController},
   * which treats every {@link AiBlockerClass#TECHNICAL_FAILURE} the same way it treats a timeout.
   */
  OCR_EVIDENCE_NOT_READY("ocr_evidence_not_ready", AiBlockerClass.TECHNICAL_FAILURE),

  /**
   * The turn does not carry an authorisation this write would fall under.
   *
   * <p>Not a permission in the Axelor sense — the user may well be entitled to make the change. It
   * says the <em>conversation</em> has not asked for it: nothing in this message authorised a
   * write, or the write is aimed outside the record set that was authorised. A person has to say
   * what they want changed, so it needs them and not another attempt.
   */
  WRITE_NOT_AUTHORISED("write_not_authorised", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * The message named records in database-id syntax ({@code invoiceId=641}, {@code Invoice#641}),
   * and at least one of them is not an identifiable record of this company — mistyped, another
   * company's, never a record — or there were more than one instruction may authorise.
   *
   * <p>Never answered with a different set: not the open record, not the job that was running, not
   * a worklist. A person re-states which records they mean. Which reference failed is deliberately
   * not named back, so another company's id is neither confirmed nor denied.
   */
  EXPLICIT_REFERENCE_UNRESOLVED(
      "explicit_reference_unresolved", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * A list-page (WORKLIST) instruction that could not be confirmed as meaning the whole queue — it
   * may narrow it by supplier, date, amount, status or wording the server does not read.
   *
   * <p>Filtering a worklist is not supported: the server can only resolve the domain's whole
   * eligible queue, and answering a filtered request with the unfiltered queue would widen it. So
   * it is refused rather than widened; the user selects the rows, opens the record, or asks for the
   * whole list in plain words.
   */
  WORKLIST_FILTER_UNSUPPORTED("worklist_filter_unsupported", AiBlockerClass.CAPABILITY_MISSING),

  /**
   * A list-page instruction arrived with nothing saying how much of the list it covers — no whole
   * set, no count, no "the rest", no named subset.
   *
   * <p>That is the deterministic fallback route: the router was off, unreachable or did not answer,
   * so nothing read the sentence. The server will not guess, because the only guess available is
   * "all of it" and a wrong one writes to records nobody named. The user selects the rows, opens a
   * record, or says again how many — which the router reads as a structured selection.
   */
  WORKLIST_SELECTION_UNRESOLVED(
      "worklist_selection_unresolved", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * «Останалите» with no job on record to take the remainder from, or one whose work is all done.
   *
   * <p>Never answered with a fresh query: "the rest" is the rest of an authorisation already given,
   * and resolving it from the current queue would silently include records that arrived since.
   */
  WORKLIST_NOTHING_REMAINING("worklist_nothing_remaining", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * The tool registry failed validation at discovery (a blank or duplicate tool name), so no tool
   * can be resolved by name. A deployment fault: every call is refused until it is fixed.
   */
  TOOL_REGISTRY_INVALID("tool_registry_invalid", AiBlockerClass.TECHNICAL_FAILURE),

  // ── business blockers: posting ─────────────────────────────────────────────

  /** The document is not a supplier invoice at all. */
  NOT_A_SUPPLIER_INVOICE("NOT_A_SUPPLIER_INVOICE", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * The company this document is being filed under has no legal identity of its own — no Partner
   * linked by {@code Company.partner}, or one carrying no ЕИК/Булстат and no VAT number.
   *
   * <p>Its own code because it is not an accounting-configuration gap and not a statement about the
   * document: nothing about the invoice is wrong, and no amount of correcting it helps. Without
   * that identity the company cannot be recognised as either party, so which direction the document
   * runs cannot be established at all — and defaulting to "we are the buyer" is what books somebody
   * else's invoice, or the company's own sale, as a purchase.
   *
   * <p>A person's decision, always: the company's identity is master data, and picking a Partner
   * that merely shares the name or the ЕИК would designate a record nobody chose for that.
   */
  MISSING_COMPANY_IDENTITY("MISSING_COMPANY_IDENTITY", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * The document is not a customer invoice — a supplier one was handed to a customer tool.
   *
   * <p>Its own code rather than a reuse of the supplier one, because the two say opposite things to
   * whoever reads them: this one means "you reached for the wrong side", and answering it by
   * retrying on the same record can never work. Not fixable by any tool: nothing changes an
   * invoice's direction, by design.
   */
  NOT_A_CUSTOMER_INVOICE("NOT_A_CUSTOMER_INVOICE", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * The company's accounting configuration is missing something the transition needs — a journal, a
   * sequence, a sequence version covering the date.
   *
   * <p>A person's decision, always: picking a substitute journal or sequence would put a document
   * in the wrong book, and there is no evidence anywhere in the record that says which one was
   * meant.
   */
  MISSING_ACCOUNT_CONFIG("MISSING_ACCOUNT_CONFIG", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * The installed localization has no default unit of measure configured for a new invoice line.
   *
   * <p>A person's decision, not a guess: {@code Unit} carries no code and no unique key — only a
   * translatable, editable name — so there is no evidence in the record that says which unit was
   * meant. Picking one would put a measurement on a purchase line that nothing downstream would
   * ever re-check.
   */
  MISSING_UNIT_CONFIG("MISSING_UNIT_CONFIG", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** Readiness blockers remain — missing accounts, missing tax, missing review fields. */
  NOT_READY_TO_POST("NOT_READY_TO_POST", AiBlockerClass.FIXABLE),

  /** Header, base and VAT do not agree, or the lines do not sum to the base. */
  AMOUNTS_DO_NOT_RECONCILE("AMOUNTS_DO_NOT_RECONCILE", AiBlockerClass.FIXABLE),

  /**
   * No payable account on the invoice at all.
   *
   * <p>Deliberately not fixable: {@code fix_partner_account} turns flags on for an account that is
   * already there, and nothing here assigns one. An invoice that reaches posting without a payable
   * account has a partner or company configuration problem behind it, and grinding on it would
   * spend the turn discovering that.
   */
  MISSING_PARTNER_ACCOUNT("MISSING_PARTNER_ACCOUNT", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * The payable account exists but its reconcile/partner-balance flags are off.
   *
   * <p>Fixable, and precisely so: those two flags are the entirety of what {@code
   * fix_partner_account} changes.
   */
  PARTNER_ACCOUNT_MISCONFIGURED("PARTNER_ACCOUNT_MISCONFIGURED", AiBlockerClass.FIXABLE),

  /** The payable account belongs to another company — a configuration fault, not a document one. */
  PARTNER_ACCOUNT_WRONG_COMPANY(
      "PARTNER_ACCOUNT_WRONG_COMPANY", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** Lines carry tax but the VAT summary is empty, so the VAT would not be posted. */
  MISSING_TAX_SUMMARY("MISSING_TAX_SUMMARY", AiBlockerClass.FIXABLE),

  /** A VAT summary row has no tax line, so its account cannot be resolved. */
  TAX_SUMMARY_ROW_WITHOUT_TAX_LINE("TAX_SUMMARY_ROW_WITHOUT_TAX_LINE", AiBlockerClass.FIXABLE),

  /** The VAT summary no longer matches the invoice's VAT total. */
  STALE_TAX_SUMMARY("STALE_TAX_SUMMARY", AiBlockerClass.FIXABLE),

  /** No payment terms, so the supplier credit line cannot be built. */
  MISSING_INVOICE_TERMS("MISSING_INVOICE_TERMS", AiBlockerClass.FIXABLE),

  /** A payment term has no due date. */
  INVOICE_TERM_WITHOUT_DUE_DATE("INVOICE_TERM_WITHOUT_DUE_DATE", AiBlockerClass.FIXABLE),

  /** The terms do not add up to the invoice total; the payment condition has to be corrected. */
  INVOICE_TERMS_DO_NOT_MATCH_TOTAL(
      "INVOICE_TERMS_DO_NOT_MATCH_TOTAL", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * Axelor's own ventilation refused. Its reason is in the message and is not ours to interpret.
   */
  AXELOR_REFUSED_POSTING("AXELOR_REFUSED_POSTING", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  // ── business blockers: validation ──────────────────────────────────────────

  /** Readiness blockers remain before validation. */
  NOT_READY_TO_VALIDATE("NOT_READY_TO_VALIDATE", AiBlockerClass.FIXABLE),

  /**
   * The payment mode is a bank transfer and no supplier bank account is set.
   *
   * <p>A person's decision: the IBAN is a fact about the supplier that is on the document or in no
   * record at all, and this system never invents one.
   */
  MISSING_BANK_DETAILS("MISSING_BANK_DETAILS", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** Axelor cannot create the invoice terms as they stand. */
  INVOICE_TERMS_NOT_CREATABLE(
      "INVOICE_TERMS_NOT_CREATABLE", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** A holdback term was deleted; Axelor does not allow validation in that state. */
  DELETED_HOLDBACK_TERM("DELETED_HOLDBACK_TERM", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** The analytic configuration refuses this invoice. */
  ANALYTIC_CONFIGURATION_REFUSED(
      "ANALYTIC_CONFIGURATION_REFUSED", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * Another invoice already carries this supplier invoice number.
   *
   * <p>The archetype for this whole enum. The same number can legitimately reappear and it can
   * equally be the same document scanned twice, and only a person can say which — but the refusal
   * used to be recognised by the word "duplicate" appearing somewhere in a sentence.
   */
  DUPLICATE_INVOICE_NUMBER("DUPLICATE_INVOICE_NUMBER", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** The VAT summary could not be built — a company VAT account configuration problem. */
  VAT_SUMMARY_NOT_BUILT("VAT_SUMMARY_NOT_BUILT", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** Axelor's own validation refused. */
  AXELOR_REFUSED_VALIDATION("AXELOR_REFUSED_VALIDATION", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  // ── business blockers: accounting moves ───────────────────────────────────

  /**
   * Total debit does not equal total credit.
   *
   * <p>Fixable, and the only move blocker that is. {@code create_move} refuses before writing
   * anything and reports both totals and the difference, so the assistant has everything it needs
   * to submit corrected lines — which is a repair it can perform with the tools it already has.
   */
  MOVE_NOT_BALANCED("MOVE_NOT_BALANCED", AiBlockerClass.FIXABLE),

  /**
   * The move is not in a status this operation accepts.
   *
   * <p>Covers a posted move someone asked to post again and a cancelled one someone asked to act
   * on. Not fixable: nothing here sends a move backwards through its lifecycle, and inventing a way
   * to would be exactly the kind of automatic accounting action this system does not take.
   */
  MOVE_INVALID_STATUS("MOVE_INVALID_STATUS", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * Axelor's own preconditions refuse the move.
   *
   * <p>Deliberately <em>not</em> fixable, unlike the supplier-invoice {@link #NOT_READY_TO_POST}.
   * There the assistant has edit tools and can clear what it finds; here there is no move-editing
   * tool at all, so calling this fixable would send it looking for a capability that does not
   * exist. If a move edit tool is ever added, this classification is the thing to revisit.
   */
  MOVE_NOT_READY_TO_POST("MOVE_NOT_READY_TO_POST", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** The accounting period for this date is closed, missing, or the user may not post into it. */
  PERIOD_NOT_AVAILABLE("PERIOD_NOT_AVAILABLE", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** The journal's authorised role set does not include this user. */
  JOURNAL_NOT_AUTHORISED("JOURNAL_NOT_AUTHORISED", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * A journal or account named in the request does not exist in this company.
   *
   * <p>A person's decision because the alternative is guessing which account was meant, and an
   * entry posted to a near-miss account is worse than one not posted at all.
   */
  ACCOUNTING_REFERENCE_NOT_FOUND(
      "ACCOUNTING_REFERENCE_NOT_FOUND", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** Axelor's own move accounting refused. Its reason is in the message and is not ours to read. */
  AXELOR_REFUSED_MOVE("AXELOR_REFUSED_MOVE", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  // ── business blockers: bank statements ────────────────────────────────────

  /**
   * The statement has no file attached, or no file format chosen.
   *
   * <p>Not fixable here and never will be: attaching a file is something a person does, and this
   * assistant has no way to put one on a record.
   */
  BANK_STATEMENT_NOT_IMPORTABLE(
      "BANK_STATEMENT_NOT_IMPORTABLE", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** The statement has already been imported; importing again is not a thing Axelor offers. */
  BANK_STATEMENT_ALREADY_IMPORTED(
      "BANK_STATEMENT_ALREADY_IMPORTED", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * Axelor's own import refused the file.
   *
   * <p>Its reasons are all judgements about the file against the ledger — a duplicate statement, an
   * initial balance that does not follow the previous statement's final one, movements that do not
   * add up. Every one of them needs somebody to look at the file, and none of them is cleared by
   * trying again.
   */
  BANK_STATEMENT_IMPORT_REFUSED(
      "BANK_STATEMENT_IMPORT_REFUSED", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * The imported lines belong to a different bank account than the caller named.
   *
   * <p>The statement's own company can only be known from its lines, so this is the check that
   * makes importing one a scoped operation at all. See {@code run_bank_statement_import}.
   */
  BANK_STATEMENT_WRONG_BANK_DETAILS(
      "BANK_STATEMENT_WRONG_BANK_DETAILS", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * The statement's file format imports in committed batches and so cannot be rolled back.
   *
   * <p>Not a fault and not something to retry: Axelor's CSV importer commits as it goes on purpose,
   * so an import this assistant could not undo is one it does not start. The statement is imported
   * from the form instead.
   */
  BANK_STATEMENT_FORMAT_NOT_ATOMIC(
      "BANK_STATEMENT_FORMAT_NOT_ATOMIC", AiBlockerClass.CAPABILITY_MISSING),

  // ── business blockers: access ──────────────────────────────────────────────

  /**
   * No such record, or one this company may not see. The two are deliberately indistinguishable.
   */
  RECORD_NOT_FOUND("RECORD_NOT_FOUND", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** The user may not read or write this record. */
  PERMISSION_DENIED("PERMISSION_DENIED", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /** The record is not in the lifecycle state the operation requires. */
  INVALID_STATUS("INVALID_STATUS", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * The record exists but is not something the assistant may act on — no invoice behind it, not
   * created by OCR, or booked as an accounting move rather than an invoice.
   */
  NOT_AN_OCR_DRAFT("NOT_AN_OCR_DRAFT", AiBlockerClass.HUMAN_DECISION_REQUIRED),

  /**
   * A supplier write is missing data it cannot be made without (a name, a readable ЕИК). Fixable:
   * the values are usually on the OCR document, and otherwise one question to the user supplies
   * them.
   */
  MISSING_SUPPLIER_DATA("MISSING_SUPPLIER_DATA", AiBlockerClass.FIXABLE);

  private final String wire;
  private final AiBlockerClass classification;

  AiBlockerCode(String wire, AiBlockerClass classification) {
    this.wire = wire;
    this.classification = classification;
  }

  /** The string that travels in the tool result — the {@code error} or {@code blocker} field. */
  public String wire() {
    return wire;
  }

  /** What this refusal means for the workflow. */
  public AiBlockerClass classification() {
    return classification;
  }

  /**
   * The code for a wire string, or null when it is not one of ours.
   *
   * <p>Case-insensitive, and it accepts the enum name as well as the wire form so that a caller
   * cannot get it subtly wrong. Null is a real answer: an unrecognised code is exactly the case the
   * conservative fallback in {@link AiBlockerClass} exists for, and quietly resolving it to
   * something would defeat that.
   */
  public static AiBlockerCode parse(String code) {
    if (code == null) {
      return null;
    }
    String trimmed = code.trim();
    if (trimmed.isEmpty()) {
      return null;
    }
    String upper = trimmed.toUpperCase(Locale.ROOT);
    for (AiBlockerCode candidate : values()) {
      if (candidate.wire.toUpperCase(Locale.ROOT).equals(upper) || candidate.name().equals(upper)) {
        return candidate;
      }
    }
    return null;
  }
}
