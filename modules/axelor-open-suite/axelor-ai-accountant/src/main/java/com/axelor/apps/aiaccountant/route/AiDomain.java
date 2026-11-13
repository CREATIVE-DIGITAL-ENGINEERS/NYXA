package com.axelor.apps.aiaccountant.route;

/**
 * The kind of document a turn is about.
 *
 * <h2>Why a domain, when a task already exists</h2>
 *
 * {@code AiTask} answers "which page of instructions", and every one of its constants today is a
 * supplier-invoice job. As the assistant gained moves and bank statements, the thing a turn is
 * <em>about</em> stopped being derivable from the task: a question about a journal entry and a
 * question about the weather both resolve to {@code GENERAL}, which is the right playbook decision
 * and a useless statement about the subject.
 *
 * <p>So the domain is separated from the task. It is a closed vocabulary rather than a string
 * because everything downstream — routing, capability selection, the metric line — compares it, and
 * a comparison against free text is a comparison against whatever somebody typed last.
 *
 * <p>Nothing dispatches on this yet. It is recorded so the Router Agent, when it arrives, has a
 * vocabulary to answer in rather than one to invent.
 */
public enum AiDomain {

  /** OCR uploads and the supplier invoices they become. */
  SUPPLIER_INVOICE,

  /**
   * Invoicing → Cust. Invoices: what the company bills its own customers.
   *
   * <p>The same {@code Invoice} entity as {@link #SUPPLIER_INVOICE}, told apart by {@code
   * operationTypeSelect} (1/2 supplier, 3/4 customer) and by nothing else — which is why the model
   * name alone can no longer decide a domain, and why {@code AiRecordDomainResolver} exists.
   */
  CUSTOMER_INVOICE,

  /** Accounting → Moves: a manual or generated journal entry. */
  JOURNAL_ENTRY,

  /** Accounting → Bank statements: a statement file and the lines it imports to. */
  BANK_STATEMENT,

  /** Partners, their bank details and their accounting configuration. */
  PARTNER,

  /** Who am I, which company am I in, what is today — no document at all. */
  GENERAL
}
