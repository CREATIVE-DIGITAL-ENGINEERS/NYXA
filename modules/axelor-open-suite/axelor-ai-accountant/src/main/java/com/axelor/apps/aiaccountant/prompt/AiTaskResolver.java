package com.axelor.apps.aiaccountant.prompt;

import com.axelor.apps.aiaccountant.dto.AiConversationState;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import java.util.Locale;

/**
 * Decides which task a message is, from the message.
 *
 * <h2>No second model call</h2>
 *
 * Routing by asking a model which playbook to send would cost a whole extra request — the full
 * fixed payload, before the turn does any work — to answer a question that stem-matching answers
 * well enough. It would also be a new place for the assistant to be confidently wrong, and the
 * failure mode of a wrong route (the wrong page of advice) is not worth a round trip to make
 * marginally rarer.
 *
 * <p>So this reuses the lifecycle destination {@link AiWorkflowIntent#requested} already derives —
 * which is the same reading C6.1 gave those words — and adds two verb families it does not cover.
 *
 * <h2>The order, and why</h2>
 *
 * <ol>
 *   <li><b>A destination</b> wins outright. «Провери я, оправи я и я осчетоводи» is a posting task:
 *       the checking and the fixing are steps on the way, and {@code requested()} already resolves
 *       the furthest destination named rather than the first verb it sees.
 *   <li><b>Repair</b> verbs, then <b>review</b> verbs, for messages that name no destination.
 *   <li><b>Continuation.</b> «Продължи» carries no verbs of its own, so it inherits the task of the
 *       instruction it resumes — the same text the continuity block quotes.
 *   <li><b>«Същото».</b> The recent-workflow shape says what the previous operation was, so a
 *       message with no verbs at all still has a referent.
 *   <li>Otherwise, GENERAL only when nothing suggests supplier-invoice work at all. A message that
 *       mentions an invoice or an OCR record but names no verb gets the review playbook rather than
 *       nothing: guessing wrong there costs a page of advice, and guessing GENERAL wrong leaves the
 *       model with no procedure for the thing it is plainly being asked about.
 * </ol>
 */
public final class AiTaskResolver {

  private AiTaskResolver() {}

  /**
   * @param message the user's own words this turn
   * @param state what the client carried over, or null
   */
  public static AiTask resolve(String message, AiConversationState state) {
    return resolve(message, state, null);
  }

  /**
   * As above, told which job the server holds.
   *
   * <h2>Why the resumed task comes from the row</h2>
   *
   * «Продължи» describes nothing by itself, so the task has to be the one being resumed — and what
   * is being resumed is the job the server wrote down, not the job the browser says it has. Reading
   * it from the blob meant a client could choose which playbook the next turn was given by choosing
   * what to send back, which is a smaller version of choosing what it is authorised to do.
   *
   * <p>The {@code shape} below is deliberately still read from the blob. It is a hint about which
   * kind of operation «същото» refers to, it selects a playbook and nothing else, and it authorises
   * no write — every write is still gated on the workflow row's own target list.
   *
   * @param previous the stored job, or null when the server holds none
   */
  public static AiTask resolve(String message, AiConversationState state, AiWorkflow previous) {
    AiTask direct = fromMessage(message);
    if (direct != AiTask.GENERAL) {
      return direct;
    }
    // «Продължи» authorises nothing by itself and describes nothing by itself: the task is the one
    // being resumed, exactly as the goal is.
    if (AiWorkflowIntent.isContinuation(message) && previous != null && previous.isActive()) {
      AiTask resumed = fromMessage(previous.getRequest());
      if (resumed != AiTask.GENERAL) {
        return resumed;
      }
    }
    // «Същото и с тази» — the shape of the last successful workflow is what it refers to.
    if (state != null && state.hasShape()) {
      AiTask repeated = fromShape(state.getShape());
      if (repeated != AiTask.GENERAL) {
        return repeated;
      }
    }
    return mentionsSupplierInvoiceWork(message) ? AiTask.REVIEW_SUPPLIER_INVOICE : AiTask.GENERAL;
  }

  /** The task the words themselves name, or GENERAL when they name none. */
  public static AiTask fromMessage(String message) {
    if (message == null) {
      return AiTask.GENERAL;
    }
    // Subject first, verb second. «Осчетоводи я» is a posting instruction whichever document it is
    // about, and the document decides which posting it means: a supplier invoice is ventilated, a
    // journal entry goes through the Move lifecycle, and a bank statement is not posted at all.
    // Only a message that actually names one of the other two documents leaves the supplier-invoice
    // path below — everything else routes exactly as it did before these domains existed.
    AiDomain subject = subjectOf(message.toLowerCase(Locale.ROOT));
    if (subject == AiDomain.BANK_STATEMENT) {
      return bankStatementTask(message.toLowerCase(Locale.ROOT));
    }
    if (subject == AiDomain.JOURNAL_ENTRY) {
      return journalEntryTask(message);
    }
    if (subject == AiDomain.CUSTOMER_INVOICE) {
      return customerInvoiceTask(message);
    }
    if (subject == AiDomain.PARTNER) {
      return supplierTask(message.toLowerCase(Locale.ROOT));
    }
    AiWorkflowIntent.Stage stage = AiWorkflowIntent.requested(message);
    if (stage == AiWorkflowIntent.Stage.POSTED) {
      return AiTask.POST_SUPPLIER_INVOICE;
    }
    if (stage == AiWorkflowIntent.Stage.VALIDATED) {
      return AiTask.VALIDATE_SUPPLIER_INVOICE;
    }
    String text = message.toLowerCase(Locale.ROOT);
    // Same isCreate fallback journalEntryTask/customerInvoiceTask already use, so a create verb
    // with
    // no "чернов"/"draft" word (e.g. "създай фактура …") is not left to fall through to GENERAL.
    if (stage == AiWorkflowIntent.Stage.DRAFT || isCreate(text)) {
      return AiTask.CREATE_SUPPLIER_INVOICE;
    }
    if (isRepair(text)) {
      return AiTask.REPAIR_SUPPLIER_INVOICE;
    }
    if (isReview(text)) {
      return AiTask.REVIEW_SUPPLIER_INVOICE;
    }
    return AiTask.GENERAL;
  }

  /**
   * Which document the message is about, when it says so plainly.
   *
   * <h2>Why supplier invoices are still not looked for</h2>
   *
   * They remain the default: they were the only domain with tasks, so every existing route ends in
   * one, and adding a positive test for them would change how messages that name no document at all
   * are read. Every other domain has to be named to be reached, which keeps each addition to the
   * messages it is actually about.
   *
   * <p>Order is narrowest vocabulary first. A statement wins a message that somehow names both,
   * because «извлечение» is a bank statement and nothing else, while «журнал» is also a field on an
   * invoice.
   */
  static AiDomain subjectOf(String text) {
    if (text.contains("извлечен")
        || text.contains("bank statement")
        || text.contains("statement")
        || text.contains("cfonb")) {
      return AiDomain.BANK_STATEMENT;
    }
    // Whole words rather than the «стати» stem: «статистика» starts the same way and is not a
    // journal entry. Under-matching here costs the review playbook; over-matching would route a
    // supplier-invoice turn to a document type with no invoice tools in its guide.
    if (text.contains("мемориален ордер")
        || text.contains("статия")
        || text.contains("статии")
        || text.contains("проводк")
        || text.contains("журнал")
        || text.contains("journal entry")
        || text.contains("journal")
        || text.contains("move ")
        || text.contains("moves")) {
      return AiDomain.JOURNAL_ENTRY;
    }
    if (isCustomerInvoiceSubject(text)) {
      return AiDomain.CUSTOMER_INVOICE;
    }
    if (isSupplierMasterDataSubject(text)) {
      return AiDomain.PARTNER;
    }
    return AiDomain.GENERAL;
  }

  /**
   * Whether the message is about a supplier record itself (Purchases → Suppliers), not an invoice.
   *
   * <p>Needs a supplier word AND either a master-data field or a create verb, and no invoice word.
   * «Смени доставчика на 946» names no field, so it stays an invoice repair; «смени IBAN-а на
   * доставчик Лидл» and «създай доставчик ...» are supplier work.
   */
  static boolean isSupplierMasterDataSubject(String text) {
    boolean namesSupplier =
        text.contains("доставчик")
            || text.contains("контрагент")
            || text.contains("партньор")
            || text.contains("supplier")
            || text.contains("vendor")
            || text.contains("partner");
    if (!namesSupplier) {
      return false;
    }
    if (text.contains("фактур")
        || text.contains("invoice")
        || text.contains("upload")
        || text.contains("ocr")
        || text.contains("чернов")
        || text.contains("draft")) {
      return false;
    }
    return isCreate(text) || namesSupplierField(text);
  }

  private static boolean namesSupplierField(String text) {
    return text.contains("iban")
        || text.contains("банков")
        || text.contains("сметк")
        || text.contains("bic")
        || text.contains("swift")
        || text.contains("адрес")
        || text.contains("телефон")
        || text.contains("мобилен")
        || text.contains("имейл")
        || text.contains("мейл")
        || text.contains("email")
        || text.contains("e-mail")
        || text.contains("сайт")
        || text.contains("контакт")
        || text.contains("еик")
        || text.contains("булстат")
        || text.contains("ддс номер")
        || text.contains("ддс №")
        || text.contains("плащан")
        || text.contains("валута")
        || text.contains("фискална позиция")
        || text.contains("име")
        || text.contains("address")
        || text.contains("phone")
        || text.contains("bank account")
        || text.contains("vat number")
        || text.contains("payment")
        || text.contains("currency")
        || text.contains("website")
        || text.contains("contact")
        || text.contains("name");
  }

  /** A supplier record can be read, created or changed. */
  static AiTask supplierTask(String text) {
    if (isCreate(text) || isRepair(text)) {
      return AiTask.MAINTAIN_SUPPLIER;
    }
    return AiTask.REVIEW_SUPPLIER;
  }

  /**
   * Whether the message is plainly about an invoice this company issues rather than one it
   * received.
   *
   * <h2>Why this needs naming and the supplier side does not</h2>
   *
   * Both are the same {@code Invoice} entity and the same word — «фактура» — so there is no stem
   * that separates them. The supplier side keeps the default it has always had, and a customer
   * invoice is reached only when the message says so: either with a phrase that means nothing else
   * («клиентска фактура», «продажна фактура», "customer invoice"), or by naming a customer and an
   * invoice in the same breath.
   *
   * <p>That second form is the useful one — «направи фактура за клиента X» is how the instruction
   * is actually phrased — and it is also the one that could over-reach, so an explicit mention of a
   * supplier takes it back. «Фактурата от доставчика, който ни е и клиент» stays where it was. The
   * cost of guessing wrong either way is the wrong page of advice: the tools themselves refuse a
   * record of the other direction outright, whatever the playbook says.
   */
  private static boolean isCustomerInvoiceSubject(String text) {
    if (text.contains("клиентска фактура")
        || text.contains("клиентски фактури")
        || text.contains("клиентските фактури")
        || text.contains("продажна фактура")
        || text.contains("продажни фактури")
        || text.contains("фактура за продажба")
        || text.contains("изходяща фактура")
        || text.contains("изходящи фактури")
        || text.contains("customer invoice")
        || text.contains("cust. invoice")
        || text.contains("sales invoice")
        || text.contains("sale invoice")
        || text.contains("outgoing invoice")) {
      return true;
    }
    boolean namesSupplier =
        text.contains("доставчик") || text.contains("supplier") || text.contains("vendor");
    if (namesSupplier) {
      return false;
    }
    return (text.contains("клиент") && text.contains("фактур"))
        || (text.contains("customer") && text.contains("invoice"));
  }

  /**
   * A customer invoice can be read, raised, corrected while DRAFT, validated, or posted.
   *
   * <p>The same five steps as a supplier invoice, because it is the same lifecycle on the same
   * entity: DRAFT → VALIDATED → VENTILATED, through {@code InvoiceService}'s own transitions. What
   * differs is everything around it — which journal, which sequence, which account, and who is
   * responsible for the contents — and that is what the customer playbooks are for.
   */
  static AiTask customerInvoiceTask(String message) {
    String text = message.toLowerCase(Locale.ROOT);
    AiWorkflowIntent.Stage stage = AiWorkflowIntent.requested(message);
    if (stage == AiWorkflowIntent.Stage.POSTED) {
      return AiTask.POST_CUSTOMER_INVOICE;
    }
    if (stage == AiWorkflowIntent.Stage.VALIDATED) {
      return AiTask.VALIDATE_CUSTOMER_INVOICE;
    }
    if (stage == AiWorkflowIntent.Stage.DRAFT || isCreate(text)) {
      return AiTask.CREATE_CUSTOMER_INVOICE;
    }
    if (isRepair(text)) {
      return AiTask.REPAIR_CUSTOMER_INVOICE;
    }
    return AiTask.REVIEW_CUSTOMER_INVOICE;
  }

  /**
   * A bank statement can be looked at or imported. There is nothing else it can be.
   *
   * <p>No create, repair or post task exists here because no tool and no Axelor path does: a
   * statement arrives as a file, its lines are read-only, and it becomes accounting through bank
   * reconciliation on another screen. A message asking for any of those routes to the review
   * playbook, which is the document that explains why it cannot be done.
   */
  static AiTask bankStatementTask(String text) {
    if (text.contains("импортир")
        || text.contains("импорт")
        || text.contains("зареди")
        || text.contains("import")) {
      return AiTask.IMPORT_BANK_STATEMENT;
    }
    return AiTask.REVIEW_BANK_STATEMENT;
  }

  /**
   * A journal entry can be read, written, corrected while NEW, or put in the books.
   *
   * <p>«Валидирай» maps to posting, not to a validation task, because a Move has no intermediate
   * state: the button on the Move form runs {@code MoveValidateService.accounting}, which is what
   * {@code post_move} calls. Sending a "validate" playbook for a transition Axelor does not have
   * would be describing a step that does not exist.
   */
  static AiTask journalEntryTask(String message) {
    String text = message.toLowerCase(Locale.ROOT);
    AiWorkflowIntent.Stage stage = AiWorkflowIntent.requested(message);
    if (stage == AiWorkflowIntent.Stage.POSTED || stage == AiWorkflowIntent.Stage.VALIDATED) {
      return AiTask.POST_JOURNAL_ENTRY;
    }
    if (stage == AiWorkflowIntent.Stage.DRAFT || isCreate(text)) {
      return AiTask.CREATE_JOURNAL_ENTRY;
    }
    if (isRepair(text)) {
      return AiTask.REPAIR_JOURNAL_ENTRY;
    }
    return AiTask.REVIEW_JOURNAL_ENTRY;
  }

  /**
   * Verbs that ask for a new entry rather than a change to one.
   *
   * <p>Checked before the repair verbs, because «създай статия и добави ред» is one instruction to
   * write an entry, and {@code добави} would otherwise read it as a correction to an entry that
   * does not exist yet.
   */
  private static boolean isCreate(String text) {
    return text.contains("създай")
        || text.contains("създав")
        || text.contains("въведи")
        || text.contains("направи")
        || text.contains("нова стати")
        || text.contains("нов ордер")
        || text.contains("create")
        || text.contains("new ");
  }

  /**
   * The task a previous operation sequence belongs to.
   *
   * <p>Read from the furthest step it reached, for the same reason the message is: a workflow that
   * edited, validated and posted was a posting workflow, and repeating "the same" means repeating
   * all of it.
   */
  public static AiTask fromShape(String shape) {
    if (shape == null || shape.isEmpty()) {
      return AiTask.GENERAL;
    }
    if (shape.contains("VENTILATE")) {
      return AiTask.POST_SUPPLIER_INVOICE;
    }
    if (shape.contains("VALIDATE")) {
      return AiTask.VALIDATE_SUPPLIER_INVOICE;
    }
    if (shape.contains("EDIT") || shape.contains("SET_PRODUCT")) {
      return AiTask.REPAIR_SUPPLIER_INVOICE;
    }
    if (shape.contains("SUPPLIER")) {
      return AiTask.CREATE_SUPPLIER_INVOICE;
    }
    return AiTask.GENERAL;
  }

  /**
   * Verbs that ask for something to be changed.
   *
   * <h2>Why the list grew</h2>
   *
   * It used to decide only which playbook to send, and a miss cost a page of advice. It now also
   * decides whether the turn authorises a write at all ({@code AiTask.authorisesWrites}), so a miss
   * costs the user a refusal on a plain instruction. «Смени доставчика на 946» is not a request to
   * review anything, and reading it as one would have the assistant answer a question nobody asked.
   *
   * <p>Still stems, still both languages, and deliberately only verbs that ask for a change on
   * their own. The direction of error matters here: over-matching gives the model permission it
   * must still choose to use, behind every other guard; under-matching refuses work the user
   * plainly asked for.
   */
  private static boolean isRepair(String text) {
    return text.contains("оправ")
        || text.contains("поправ")
        || text.contains("коригир")
        || text.contains("редактир")
        || text.contains("промени")
        || text.contains("смени")
        || text.contains("задай")
        || text.contains("сложи")
        || text.contains("добави")
        || text.contains("премахни")
        || text.contains("изтрий")
        || text.contains("обнов")
        || text.contains("актуализ")
        || text.contains("попълн")
        || text.contains("нанес")
        || text.contains("впиш")
        || text.contains("запиши")
        || text.contains(" fix")
        || text.startsWith("fix")
        || text.contains("correct")
        || text.contains("repair")
        || text.contains("edit ")
        || text.contains("set ")
        || text.contains("change ")
        || text.contains("update ")
        || text.contains("add ")
        || text.contains("remove ")
        || text.contains("delete ");
  }

  private static boolean isReview(String text) {
    return text.contains("провер")
        || text.contains("виж ")
        || text.contains("покажи")
        || text.contains("защо")
        || text.contains("какво")
        || text.contains("колко")
        || text.contains("дай ")
        || text.contains("списък")
        || text.contains("check")
        || text.contains("show")
        || text.contains("list")
        || text.contains("why")
        || text.contains("what")
        || text.contains("how many")
        || text.contains("review");
  }

  /**
   * Does the message concern a supplier invoice or an OCR record at all?
   *
   * <p>The gate on GENERAL. «Коя е активната фирма?» genuinely needs no playbook; «а тази?» while
   * working through the inbox does, and has no verb to say so.
   */
  private static boolean mentionsSupplierInvoiceWork(String message) {
    if (message == null) {
      return false;
    }
    String text = message.toLowerCase(Locale.ROOT);
    return text.contains("фактур")
        || text.contains("запис")
        || text.contains("чернов")
        || text.contains("доставчик")
        || text.contains("ocr")
        || text.contains("upload")
        || text.contains("invoice")
        || text.contains("draft")
        || text.contains("supplier")
        || text.contains("needsreview")
        || text.contains("needs review");
  }
}
