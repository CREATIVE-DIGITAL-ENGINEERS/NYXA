package com.axelor.apps.aiaccountant.tool;

/**
 * What area of the ERP a tool belongs to.
 *
 * <h2>Why the registry needed a second axis</h2>
 *
 * {@link AiToolKind} says whether a tool reads or writes, and {@link AiTool#published()} says
 * whether the model can see it. Neither says what a tool is <em>about</em>, so with fifteen tools
 * the only way to answer "which of these does a posting turn actually need" was to read all fifteen
 * classes. At fifty that stops being possible, and the questions that matter when a second module
 * arrives — what does this task reach, what would per-task exposure save, which tools does a bank
 * statement flow need — have no mechanical answer at all.
 *
 * <p>This is metadata, not a gate. Nothing is refused for being in the wrong capability: company
 * scope, write modes, the unattended guard and every check inside a tool are unchanged and remain
 * the only things that decide whether a call runs. Mis-classifying a tool costs an inaccurate
 * report, never a wrong write.
 *
 * <h2>Adding a module</h2>
 *
 * A bank-statement or fixed-asset module adds a constant here, and its tools name it. Nothing in
 * this class has to know about that module, and no existing tool changes.
 */
public enum AiCapability {

  /**
   * Not about any one document: who is asking, which company, what the session can see. Every task
   * needs these, so they are the one group that can never be narrowed away.
   */
  CORE,

  /**
   * The OCR intake queue and the supplier invoice itself — reading it, correcting it, moving it.
   */
  SUPPLIER_INVOICE,

  /**
   * The customer invoice — reading it, writing one, correcting it, moving it through its lifecycle.
   *
   * <p>Separate from {@link #SUPPLIER_INVOICE} although both are the same {@code Invoice} entity,
   * because they are different jobs with different rules: a supplier invoice is a document that
   * arrived and has to be matched to what was ordered, a customer invoice is one this company
   * issues and is responsible for the contents of. Their tools refuse each other's records
   * outright.
   */
  CUSTOMER_INVOICE,

  /** Who the company trades with: finding a supplier, creating one, its addresses and bank data. */
  PARTNER,

  /**
   * The chart of accounts and what has to be true in it before a document can be posted — partner
   * account flags, and the product lookups whose only purpose in these flows is to give a line its
   * expense account.
   *
   * <p>Products sit here rather than in a master-data group of their own because that is what they
   * are for here: {@code find_product} exists so a line resolves an account, and its own
   * description tells the model to prefer a candidate with {@code hasPurchaseAccount}. When a
   * second consumer of the catalogue appears — a sales or stock flow that wants a product for its
   * own sake — this is the classification to revisit.
   */
  ACCOUNTING,

  /**
   * The accounting move itself — reading a journal entry, writing one, posting it.
   *
   * <p>Separate from {@link #ACCOUNTING} because they answer different questions. ACCOUNTING is
   * about the chart and its configuration: is this account usable, does this product resolve to
   * one. This is about a document with its own lifecycle, its own balance rule and its own posting
   * step — the thing an accountant opens under Accounting → Moves.
   *
   * <p>Also separate from {@link #SUPPLIER_INVOICE}, though a ventilated invoice produces a move. A
   * move reached that way is a consequence of the invoice and is governed by the invoice's rules; a
   * move reached through these tools is a manual journal entry, which is a different document with
   * a different set of things that can be wrong with it.
   */
  JOURNAL_ENTRY,

  /**
   * Bank statements: the imported file, its lines and whether its balances hang together.
   *
   * <p>Separate from {@link #JOURNAL_ENTRY} because a statement is not an entry — nothing here
   * touches the ledger. A statement is a record of what the bank says happened, and it becomes
   * accounting only through reconciliation, which is a different screen and is not in this group.
   */
  BANK_STATEMENT
}
