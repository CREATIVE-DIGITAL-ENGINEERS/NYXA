package com.axelor.apps.aiaccountant.prompt;

import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * What the user is trying to do this turn, and therefore which playbook to send.
 *
 * <h2>Why a task at all</h2>
 *
 * The operating procedure had grown to twenty-odd rules and 28 KB, of which the great majority
 * described one workflow: correcting, validating and posting an OCR supplier invoice. Every turn
 * carried all of it — the address rules while posting, the posting rules while listing records —
 * and every new module would have added its own share to the same document, sent to everybody.
 *
 * <p>Splitting it by task keeps the universal rules universal and hands the model only the
 * procedure for the job in front of it. It also gives the next module somewhere obvious to go: a
 * bank statement or a fixed asset is a new constant here and a new text file beside the others,
 * with nothing to change in the global prompt.
 *
 * <h2>Why a task also names a domain</h2>
 *
 * Tasks are not independent: posting an invoice routinely means reviewing, repairing and validating
 * it first, and routing picks the destination, not the steps. With the rules split strictly by
 * task, a POST turn arrived without the line-fidelity rule, the reconciliation vocabulary or the
 * partner account fix — exactly the guidance a repair on the way to posting needs, sitting in a
 * playbook it would never be sent.
 *
 * <p>So a task carries two resources: the <b>domain guide</b>, shared by every task about the same
 * kind of document and holding the invariants any of them may need, and the <b>playbook</b>, which
 * is the route and the completion goal for this one job. Duplicating the shared rules into all five
 * playbooks would have worked once and then drifted, with five copies to keep in step.
 *
 * <h2>What a task is not</h2>
 *
 * Not a state machine, and not a permission. It selects a page of instructions; the model still
 * chooses its calls, and every accounting, company, duplicate and PFP guard sits in Java where it
 * already was. Resolving the wrong task costs the model the wrong page of advice, never a write it
 * would not otherwise have been allowed to make.
 */
public enum AiTask {

  /** Read-only: what is on this record, why is it blocked, list what is waiting. */
  REVIEW_SUPPLIER_INVOICE(
      AiDomain.SUPPLIER_INVOICE,
      "supplier-invoice-review.txt",
      AiCapability.SUPPLIER_INVOICE,
      AiCapability.PARTNER,
      AiCapability.ACCOUNTING),

  /** Turn an OCR upload into a DRAFT supplier invoice, supplier and all. */
  CREATE_SUPPLIER_INVOICE(
      AiDomain.SUPPLIER_INVOICE,
      "supplier-invoice-create.txt",
      AiCapability.SUPPLIER_INVOICE,
      AiCapability.PARTNER),

  /** Correct an existing DRAFT: lines, amounts, tax codes, header fields. */
  REPAIR_SUPPLIER_INVOICE(
      AiDomain.SUPPLIER_INVOICE,
      "supplier-invoice-repair.txt",
      AiCapability.SUPPLIER_INVOICE,
      AiCapability.ACCOUNTING),

  /** Bring a DRAFT to VALIDATED, repairing what the evidence supports on the way. */
  VALIDATE_SUPPLIER_INVOICE(
      AiDomain.SUPPLIER_INVOICE,
      "supplier-invoice-validate.txt",
      AiCapability.SUPPLIER_INVOICE,
      AiCapability.ACCOUNTING),

  /**
   * Bring an invoice to POSTED, through correction and validation if that is what it takes.
   *
   * <p>The widest set, and for the AI-3.1 reason: the route to POSTED runs through repair and
   * validation, so a posting turn may legitimately need to fix a payable account or re-read a
   * supplier. Declaring only SUPPLIER_INVOICE here would describe the tool call, not the job.
   */
  POST_SUPPLIER_INVOICE(
      AiDomain.SUPPLIER_INVOICE,
      "supplier-invoice-post.txt",
      AiCapability.SUPPLIER_INVOICE,
      AiCapability.PARTNER,
      AiCapability.ACCOUNTING),

  /** Read-only: what is on this customer invoice, what does it total, why is it blocked. */
  REVIEW_CUSTOMER_INVOICE(
      AiDomain.CUSTOMER_INVOICE,
      "customer-invoice-review.txt",
      AiCapability.CUSTOMER_INVOICE,
      AiCapability.PARTNER,
      AiCapability.ACCOUNTING),

  /**
   * Raise a new DRAFT customer invoice for a customer this company sells to.
   *
   * <p>Declares PARTNER and ACCOUNTING as well as its own area because that is the route: the
   * customer has to be found before an invoice can name it, and a line has to resolve a product
   * before it can resolve an account.
   */
  CREATE_CUSTOMER_INVOICE(
      AiDomain.CUSTOMER_INVOICE,
      "customer-invoice-create.txt",
      AiCapability.CUSTOMER_INVOICE,
      AiCapability.PARTNER,
      AiCapability.ACCOUNTING),

  /** Correct an existing DRAFT: lines, amounts, tax codes, header fields, the customer itself. */
  REPAIR_CUSTOMER_INVOICE(
      AiDomain.CUSTOMER_INVOICE,
      "customer-invoice-repair.txt",
      AiCapability.CUSTOMER_INVOICE,
      AiCapability.PARTNER,
      AiCapability.ACCOUNTING),

  /** Bring a DRAFT to VALIDATED, repairing what the evidence supports on the way. */
  VALIDATE_CUSTOMER_INVOICE(
      AiDomain.CUSTOMER_INVOICE,
      "customer-invoice-validate.txt",
      AiCapability.CUSTOMER_INVOICE,
      AiCapability.ACCOUNTING),

  /**
   * Bring a customer invoice to POSTED, through correction and validation if that is what it takes.
   */
  POST_CUSTOMER_INVOICE(
      AiDomain.CUSTOMER_INVOICE,
      "customer-invoice-post.txt",
      AiCapability.CUSTOMER_INVOICE,
      AiCapability.PARTNER,
      AiCapability.ACCOUNTING),

  /**
   * Read-only: what is in this journal entry, does it balance, why will it not post.
   *
   * <p>The four journal-entry tasks declare {@link AiCapability#JOURNAL_ENTRY} and nothing else.
   * That is not modesty, it is the tool list: everything these jobs can reach — search_moves,
   * get_move, create_move, edit_move, post_move — is in that one group. The chart-of-accounts tools
   * in {@link AiCapability#ACCOUNTING} are invoice-side helpers (a payable account's flags, a
   * product's expense account) and no move flow calls them, so declaring it would describe a route
   * that does not exist.
   */
  REVIEW_JOURNAL_ENTRY(
      AiDomain.JOURNAL_ENTRY, "journal-entry-review.txt", AiCapability.JOURNAL_ENTRY),

  /** Write a manual journal entry, in NEW status, from accounts and amounts the user gave. */
  CREATE_JOURNAL_ENTRY(
      AiDomain.JOURNAL_ENTRY, "journal-entry-create.txt", AiCapability.JOURNAL_ENTRY),

  /** Correct a NEW entry — header fields and lines. Nothing else is editable. */
  REPAIR_JOURNAL_ENTRY(
      AiDomain.JOURNAL_ENTRY, "journal-entry-repair.txt", AiCapability.JOURNAL_ENTRY),

  /**
   * Put an entry in the books through Axelor's own lifecycle.
   *
   * <p>There is no separate validation task, because on a Move there is no separate transition: the
   * Move form's own button runs {@code MoveValidateService.accounting}, which is exactly what
   * {@code post_move} calls. Inventing a VALIDATE task here would mean writing a playbook for a
   * step Axelor does not have.
   */
  POST_JOURNAL_ENTRY(
      AiDomain.JOURNAL_ENTRY,
      "journal-entry-post.txt",
      AiCapability.JOURNAL_ENTRY,
      AiCapability.ACCOUNTING),

  /** Read-only: which statements are there, what is in one, do its balances agree. */
  REVIEW_BANK_STATEMENT(
      AiDomain.BANK_STATEMENT, "bank-statement-review.txt", AiCapability.BANK_STATEMENT),

  /**
   * Import a RECEIVED statement's file through Axelor's importer.
   *
   * <p>The only write this domain has. There is no create, repair or post task, because there is no
   * tool and no Axelor path for any of them from here: a statement arrives as a file, its lines are
   * read-only, and it becomes accounting through bank reconciliation on another screen.
   */
  IMPORT_BANK_STATEMENT(
      AiDomain.BANK_STATEMENT, "bank-statement-import.txt", AiCapability.BANK_STATEMENT),

  /**
   * Read-only: who a supplier is, what bank accounts, addresses and payment settings it has.
   *
   * <p>A supplier is an external partner from Purchases → Suppliers — never the active company's
   * own legal identity ({@code Company.partner}), which is configured elsewhere and has no tool.
   */
  REVIEW_SUPPLIER(
      AiDomain.PARTNER, "partner-review.txt", AiCapability.PARTNER, AiCapability.ACCOUNTING),

  /**
   * Create a supplier, or change its master data: address, bank account, contacts, payment
   * settings.
   *
   * <p>Before this existed the PARTNER domain had no task at all, so «смени IBAN-а на доставчика»
   * either authorised nothing (no verb this resolver knew) or was read as a supplier-INVOICE repair
   * and then dropped to GENERAL on the supplier's own form — no playbook, no job, and every turn
   * started from a refusal the model answered by asking for a confirmation it could never use.
   *
   * <p>SUPPLIER_INVOICE is declared so the playbook's «създай липсващия доставчик» can READ what
   * the OCR upload shows (get_ocr_record, get_ocr_evidence). It adds visibility, not authority: the
   * job of a supplier turn authorises only Partner# / PartnerCreate: targets, so any invoice or
   * upload write the model attempted is still refused as unauthorised.
   */
  MAINTAIN_SUPPLIER(
      AiDomain.PARTNER,
      "partner-maintain.txt",
      AiCapability.PARTNER,
      AiCapability.ACCOUNTING,
      AiCapability.SUPPLIER_INVOICE),

  /**
   * Anything that is not document work: which company am I in, what is today, small talk.
   *
   * <p>Gets neither a domain guide nor a playbook. Sending either would be paying every turn for
   * advice about a workflow the user is not doing, which is the cost this whole split exists to
   * remove.
   */
  GENERAL(null, null);

  private final Set<AiCapability> capabilities;

  private final AiDomain domain;
  private final String resource;

  AiTask(AiDomain domain, String resource, AiCapability... extra) {
    this.domain = domain;
    this.resource = resource;
    // CORE is on every task by construction rather than by being listed five times: who the user is
    // and which company they are in is not something any job can opt out of.
    Set<AiCapability> all = EnumSet.of(AiCapability.CORE);
    all.addAll(Arrays.asList(extra));
    this.capabilities = Collections.unmodifiableSet(all);
  }

  /**
   * The areas of the ERP this job may need to touch, {@link AiCapability#CORE} always included.
   *
   * <h2>What this is for, and what it is not</h2>
   *
   * It describes the route, not the single call: posting runs through repair and validation, so
   * POST declares what those need too. It is <b>not</b> a permission and <b>not</b> a filter. The
   * model is offered the same fourteen tools whatever the task, because the persisted Foundry agent
   * declares one fixed list and the Responses API will not take request-local tools; narrowing that
   * would mean an agent per task, and a conversation that moves from reviewing to posting would
   * break its own chain.
   *
   * <p>So this earns its place two ways today. It keeps a playbook honest — a test fails if one
   * starts naming a tool from an area its task never declared — and it makes the cost of per-task
   * exposure measurable, in bytes, before anyone decides whether it is worth building.
   */
  public Set<AiCapability> capabilities() {
    return capabilities;
  }

  /** The playbook file name, or null for {@link #GENERAL}. */
  public String resource() {
    return resource;
  }

  /**
   * The kind of document this task is about, or null for {@link #GENERAL}.
   *
   * <p>Held rather than inferred, so the route says which domain a turn belongs to without anyone
   * having to map task names back to documents. Before there was a second domain, everything that
   * was not GENERAL was a supplier invoice, and that assumption was written into the adapter.
   */
  public AiDomain domain() {
    return domain;
  }

  /**
   * The shared domain-guide file name, or null for {@link #GENERAL}.
   *
   * <p>Derived from the domain rather than stored beside it: {@code JOURNAL_ENTRY} → {@code
   * journal-entry.txt}. One naming rule, so a new domain cannot end up with a guide the library
   * looks for under a name nobody chose deliberately.
   */
  public String domainResource() {
    if (domain == null) {
      return null;
    }
    return domain.name().toLowerCase(Locale.ROOT).replace('_', '-') + ".txt";
  }

  public boolean hasDomainGuide() {
    return domain != null;
  }

  /** Stable identifier for the metric line — the file name without its extension, or {@code -}. */
  public String playbookId() {
    return resource == null ? "-" : resource.substring(0, resource.length() - ".txt".length());
  }

  public boolean hasPlaybook() {
    return resource != null;
  }

  /**
   * Whether a turn on this task is asking for something to be changed.
   *
   * <h2>What it is for</h2>
   *
   * A destination — validated, posted — is not the only way a user authorises a write. «Оправи 946»
   * names no lifecycle stage and is still an unambiguous instruction to change a record, while «виж
   * 946» is equally unambiguous that nothing should be. {@code AiWorkflowIntent.requested} only
   * knows about destinations, so without this the first would have been indistinguishable from the
   * second.
   *
   * <p>Read only when the task came from the user's own words this turn. A task inferred from the
   * page or from a previous operation says what the turn is <em>about</em>, not what was asked for,
   * and inferring an authorisation from context is the thing {@link
   * com.axelor.apps.aiaccountant.route.AiExplicitness} exists to prevent.
   */
  public boolean authorisesWrites() {
    return this == CREATE_SUPPLIER_INVOICE
        || this == REPAIR_SUPPLIER_INVOICE
        || this == VALIDATE_SUPPLIER_INVOICE
        || this == POST_SUPPLIER_INVOICE
        || this == CREATE_CUSTOMER_INVOICE
        || this == REPAIR_CUSTOMER_INVOICE
        || this == VALIDATE_CUSTOMER_INVOICE
        || this == POST_CUSTOMER_INVOICE
        || this == CREATE_JOURNAL_ENTRY
        || this == REPAIR_JOURNAL_ENTRY
        || this == POST_JOURNAL_ENTRY
        || this == IMPORT_BANK_STATEMENT
        || this == MAINTAIN_SUPPLIER;
  }
}
