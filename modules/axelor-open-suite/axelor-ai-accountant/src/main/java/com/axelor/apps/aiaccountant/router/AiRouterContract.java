package com.axelor.apps.aiaccountant.router;

import com.axelor.apps.aiaccountant.config.AiAccountantConfig;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.metrics.AiTokenEstimate;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiExplicitness;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.aiaccountant.route.AiSetSelection;
import com.axelor.apps.aiaccountant.route.AiWriteOperation;
import com.axelor.apps.aiaccountant.service.AiTurnMemoryStore;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * What the Router Agent is asked, and what it is allowed to answer.
 *
 * <h2>The vocabulary is generated, never typed</h2>
 *
 * Every allowed value below is read from the enum it must map onto, so the contract cannot drift
 * from the code that validates against it. The alternative — a prompt listing the values by hand —
 * fails silently: a value is added to {@link AiScope}, the prompt still describes the old set, and
 * the router keeps answering with a vocabulary that no longer matches. The same class of drift
 * costs an iteration per turn when a prompt names a tool that does not exist.
 *
 * <h2>Why the contract travels with the request</h2>
 *
 * The agent's portal-managed instructions are the natural home for it, and eventually that is where
 * it should live. Sending it per request is what makes this phase possible without touching a
 * Foundry resource at all: the router works against an agent with empty instructions, so it can be
 * evaluated before anybody publishes anything. It is a few hundred tokens on a stateless call.
 *
 * <h2>What is structured, and what is verbatim</h2>
 *
 * The structured facts below — page, selection, activeWorkflow — carry no record ids, no supplier
 * names, no amounts, no invoice numbers: the selection travels as a count, not as a list, and every
 * id is settled server-side by machinery the router has no part in. That half of the payload is
 * free of business data by construction, not by redaction.
 *
 * <p>The conversation is not: the current message and the stored history ({@code
 * AiTurnMemoryStore}) are sent verbatim and, with no per-message caps, in full — see {@link
 * #history}. Whatever business data a user actually typed into a message — an amount, a supplier
 * name, an invoice number — is in there as plain visible text, because resolving "it", "the same
 * one" or an unfamiliar word depends on reading what was actually said. Only the structured,
 * machine-populated fields are guaranteed free of business data; the conversation text itself is
 * not filtered.
 */
public final class AiRouterContract {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private AiRouterContract() {}

  /** The complete input text: the rules, then this turn's facts, then the user's own words. */
  public static String request(
      String message, PageContext page, AiWorkflow workflow, Long companyId) {
    return request(message, page, workflow, companyId, null, Collections.emptyList());
  }

  /**
   * As above, telling the router what the server remembers of the last exchange in this same chat
   * panel — the one thing that lets it recognise «да», «направи го», «продължи» or "same but the
   * date +1 day" as continuing a specific proposal rather than reading them cold.
   *
   * @param previousTurn the last exchange this user, company and conversation had, or null when the
   *     server holds none
   */
  public static String request(
      String message,
      PageContext page,
      AiWorkflow workflow,
      Long companyId,
      AiTurnMemoryStore.Reading previousTurn) {
    return request(message, page, workflow, companyId, previousTurn, Collections.emptyList());
  }

  /**
   * As above, also giving the router the complete stored conversation for this chat panel — see
   * {@code AiTurnMemoryStore#loadHistory} — so it can resolve a reference to something said further
   * back than {@code previousTurn} alone would show, and read unfamiliar wording in the light of
   * what was actually being discussed.
   *
   * @param history the conversation so far, oldest first, NOT including the current message — it is
   *     sent separately, below, so nothing here is asked to interpret its own duplicate
   */
  public static String request(
      String message,
      PageContext page,
      AiWorkflow workflow,
      Long companyId,
      AiTurnMemoryStore.Reading previousTurn,
      List<AiTurnMemoryStore.Reading> history) {
    return request(message, page, workflow, companyId, previousTurn, history, 0);
  }

  /**
   * As above, also telling the router how many database-id references the server's own parser
   * ({@code AiExplicitReferenceParser}) found in the message — {@code explicitRefCount}, a count
   * and never the ids, so the structured half of the payload stays free of record identifiers.
   *
   * @param explicitRefCount references such as {@code invoiceId=641} or {@code Invoice#641} found
   *     in THIS message; 0 when there were none
   */
  public static String request(
      String message,
      PageContext page,
      AiWorkflow workflow,
      Long companyId,
      AiTurnMemoryStore.Reading previousTurn,
      List<AiTurnMemoryStore.Reading> history,
      int explicitRefCount) {
    String head =
        instructions()
            + "\n\nCONTEXT (facts established by the server; trust these over the message):\n";
    String tail =
        "\n\nUSER MESSAGE:\n"
            + (message == null ? "" : message.trim())
            + "\n\nAnswer with the JSON object only.";
    // Everything around the context block is part of the same request, so it is charged against
    // the same budget the history has to fit into.
    int outside = AiTokenEstimate.of(head) + AiTokenEstimate.of(tail);
    return head
        + context(page, workflow, companyId, previousTurn, history, outside, explicitRefCount)
        + tail;
  }

  /** The rules and the closed vocabularies, built from the enums they must map onto. */
  public static String instructions() {
    StringBuilder sb = new StringBuilder();
    sb.append(
        "You are a routing classifier for a Bulgarian accounting ERP assistant. You do not answer"
            + " the user, do not give accounting advice and do not perform work — and nothing"
            + " reads your text back to them: there is no channel from you to the user, ever. You"
            + " read one message plus server-established context, including the conversation so"
            + " far, and return how the turn should be routed."
            + "\n\nReturn ONE JSON object and nothing else — no prose, no code fences. NEVER return"
            + " an empty object: every status below fills in either the five routing values or"
            + " \"reason\" as stated for it. NEVER leave status empty and never use it to end the"
            + " turn — see status=route below for what to do when you are not sure.\n"
            + "{\"status\":\"route|continue|invalid\",\"domain\":…,\"task\":…,"
            + "\"goal\":…,\"scope\":…,\"explicitness\":…,\"setSelection\":…,\"setCount\":…,"
            + "\"setFilter\":…,\"operation\":…,\"reason\":…}\n\n"
            + "status=route: all five routing values are filled in, read from THIS message. When"
            + " you are not fully certain, still answer route with your BEST-SUPPORTED reading —"
            + " never invent a status to avoid deciding. Use domain=GENERAL, task=GENERAL,"
            + " scope=UNSCOPED, explicitness=NONE when nothing more specific is supported by the"
            + " message and the context; use the most specific values you can actually support"
            + " otherwise. A wrong GENERAL costs the user one extra sentence from the assistant"
            + " asking what they mean — every other wrong answer costs a turn on the wrong"
            + " playbook, so prefer the less committal reading when two are genuinely open.\n"
            + "status=continue: all five routing values are filled in too, but they describe a"
            + " change the ASSISTANT proposed last turn (see previousTurn below), which this"
            + " message is confirming, repeating or lightly modifying rather than naming from"
            + " scratch. Use it only when ALL of these hold:\n"
            + "  1. previousTurn.present is true, and its assistantReply names ONE concrete,"
            + " specific change to make. CONTINUE may confirm one coherent, fully specified job"
            + " toward one outcome on one business document and its server-verifiable linked"
            + " records (e.g. an OCR upload and the invoice it produced) — it may contain an"
            + " ORDERED SEQUENCE of tool actions (e.g. \"edit the lines, validate, refresh terms,"
            + " then post\") and still counts as one concrete change. Reject this condition only"
            + " when the proposal offered alternative choices to pick between, named unrelated"
            + " jobs or records, was a vague suggestion or general description of what is wrong,"
            + " or asked the user a question rather than proposing a change.\n"
            + "  2. THIS message is a bare affirmation («да», «ок», «направи го», \"yes\", \"do"
            + " it\", \"go ahead\") or that affirmation plus a small adjustment stated in the"
            + " user's own words («същото, но датата +1 ден», \"same but with tomorrow's"
            + " date\") — never a message that names its own destination or change independently"
            + " of what was just proposed.\n"
            + "  3. There is something on record for the proposal to be about. If"
            + " activeWorkflow.present is true, the continuation continues THAT job: scope is"
            + " CONVERSATION, and the server applies it only to the job's own authorised records,"
            + " whatever page is open now. Otherwise the page context must still show a record"
            + " open — the server checks separately that it is the SAME record the proposal was"
            + " made on — and scope is OPEN_RECORD. Never WORKLIST, NAMED_RECORDS or"
            + " DELEGATED_SELECTION for a continuation.\n"
            + "  When all three hold: task is the WRITE task the proposal actually described"
            + " (e.g. VALIDATE_SUPPLIER_INVOICE when the proposal was to validate) — never the"
            + " read-only task the previous turn ran under; explicitness is INHERITED, never"
            + " EXPLICIT, because the affirmation itself named nothing and is only inheriting"
            + " authority from what was already proposed. If any of the three fails — no proposal"
            + " on record, the proposal named more than one action, this message names its own"
            + " destination independently, or there is genuinely nothing to attach the words to —"
            + " do not guess: answer status=route instead, exactly as described there (the least"
            + " committal supported reading, down to GENERAL if nothing else is supported). There"
            + " is no clarification status: you cannot ask the user anything, so a message you"
            + " cannot confidently attach to a proposal is simply routed on its own, not escalated.\n"
            + "status=invalid: the message is not a request about this ERP at all; explain in"
            + " \"reason\".\n\n"
            + "Use ONLY these values. Never invent one, never abbreviate, never translate them.\n");
    sb.append(catalog());
    sb.append("\ndomain: ").append(names(AiDomain.values()));
    sb.append("\ntask: ").append(names(AiTask.values()));
    sb.append("\ngoal (null when the message names no destination): ")
        .append(names(AiWorkflowIntent.Stage.values()));
    sb.append("\nscope: ").append(names(AiScope.values()));
    sb.append("\nexplicitness: ").append(names(AiExplicitness.values()));
    sb.append("\nsetSelection (REQUIRED when scope is WORKLIST, otherwise omit or null): ")
        .append(names(AiSetSelection.values()));
    sb.append("\noperation (REQUIRED when task is MAINTAIN_SUPPLIER, otherwise omit or null): ")
        .append(names(AiWriteOperation.values()));
    sb.append(
        "\n\nHow to choose:\n"
            + "- goal is the lifecycle state the user asked a record to REACH, and only when this"
            + " message says so. A question about a record has no goal.\n"
            + "- scope describes where the set of records comes from, not how many there are:"
            + " OPEN_RECORD when the context says a record is open and the message is about it;"
            + " DELEGATED_SELECTION when the context reports a selection and the message refers to"
            + " it («тези», \"these\"); WORKLIST when a list is open (page.open true, recordOpen"
            + " false) and the instruction is about that list rather than one record — then you"
            + " MUST also say HOW MUCH of the list in setSelection, described below, and you must"
            + " never pick another scope to avoid that question. Creating ONE new record"
            + " (operation CREATE) is not about the list even when one is open: scope UNSCOPED,"
            + " no setSelection; CONVERSATION when it continues"
            + " work already under way"
            + " («продължи», \"carry on\") without naming anything; NAMED_RECORDS ONLY when"
            + " explicitRefCount in the context is greater than 0 — the message itself contains"
            + " database-id syntax such as invoiceId=641, uploadId=12, Invoice#641 or Move#102."
            + " An ЕИК/Булстат or VAT number, a printed invoice number («фактура №641»), a"
            + " supplier, customer or file name is NOT NAMED_RECORDS: route such a message by the"
            + " page (OPEN_RECORD, WORKLIST) or UNSCOPED — the server reads business keys from the"
            + " message itself. Whenever explicitRefCount is greater than 0 the server takes the"
            + " records from those ids whatever scope you return, and never falls back to the"
            + " open record; UNSCOPED when none of these hold. activeWorkflow, when present,"
            + " is about ONE domain (its own task implies one) — use CONVERSATION only when the"
            + " domain you are otherwise selecting for this turn (from the page, or from a document"
            + " the message itself names) agrees with it, or when neither says anything. A page or"
            + " an explicit document that disagrees with activeWorkflow means that job does not"
            + " apply to this turn — it may still exist, just not for what is being asked now.\n"
            + "- explicitness is about THIS message: EXPLICIT when it states the destination"
            + " itself, or itself asks for a specific change with no lifecycle destination —"
            + " create, correct, fix, change or maintain something («оправи фактурата», «смени"
            + " IBAN-а на доставчика», «създай доставчик с ЕИК …»); INHERITED when it continues an"
            + " authorisation given earlier; IMPLIED when a change is only suggested; NONE when"
            + " nothing was asked for.\n"
            + "- setSelection says how much of a list the instruction covers. It is REQUIRED on"
            + " every WORKLIST answer and must be omitted (or null) on every other scope, EXCEPT"
            + " that REMAINING is also allowed with CONVERSATION when the message asks for what is"
            + " left of the job already running. Read the user's own words — any wording, any"
            + " language, no fixed phrases:\n"
            + "    WHOLE — every eligible record of that list («всички фактури», «осчетоводи"
            + " фактурите», «цялата опашка», \"post them all\", and an unqualified plural with"
            + " nothing narrowing it).\n"
            + "    COUNT — a stated number of records, taken from the head of the list's own"
            + " order («следващите две», «първите три», «една фактура», \"the next one\"). Put the"
            + " number in setCount as an integer. COUNT with no setCount is rejected.\n"
            + "    REMAINING — what is left of work already under way («останалите», «довърши"
            + " ги», «продължи с другите», \"the rest\"). The server takes the remainder of the"
            + " job on record; it does not re-query the list, so nothing that arrived since is"
            + " included. Only meaningful when activeWorkflow.present is true.\n"
            + "    FILTERED — a named subset of the queue. Put the server's own name for it in"
            + " setFilter, from the list below and from nowhere else. There is no free-text"
            + " filtering: a narrowing the list below does not name (a supplier, a date, an"
            + " amount, a customer, a file name) is still FILTERED with the closest setFilter you"
            + " can support, or — when none fits — FILTERED with setFilter set to a short"
            + " description of what was asked. The server then refuses precisely, naming it, and"
            + " the user is told what it cannot do. NEVER answer WHOLE for a narrowed request:"
            + " that silently turns «осчетоводи фактурите на Лидл» into every eligible invoice.\n"
            + "  Supported setFilter values (exact spellings; nothing else is supported):"
            + " DRAFT, NEEDS_REVIEW.\n"
            + "  A complete instruction needs no confirmation — the server executes it. Do not"
            + " hold one back for a question; you cannot ask one anyway.\n"
            + "- operation says whether a MAINTAIN_SUPPLIER turn makes a supplier or changes an"
            + " existing one. MAINTAIN_SUPPLIER is one task for both, so this is the only thing"
            + " that tells them apart, and the server freezes a different authorised target for"
            + " each: a creation is authorised on the ЕИК it is for, an update on the supplier"
            + " record itself. Read the user's own words, any wording, any language — «създай»,"
            + " «направи», «заведи», «регистрирай», «добави нов», \"set up\", \"add\" are CREATE;"
            + " «смени», «обнови», «поправи», «добави IBAN на», \"change\", \"fix\", \"update\""
            + " are UPDATE. Say CREATE when the supplier is being introduced for the first time"
            + " even if the sentence never uses a create verb («трябва ми доставчик с ЕИК"
            + " 831642181 …»), and UPDATE when a field of a supplier that already exists is being"
            + " changed. Omit it on every other task. If you genuinely cannot tell, omit it: the"
            + " server then reads the turn as being about a supplier that already exists, which"
            + " refuses a creation rather than performing one nobody clearly asked for.\n"
            + "- Bulgarian and English are both ordinary input; the vocabulary above stays"
            + " English.\n"
            + "- A bare approval on its own — «да», «ок», «потвърждавам», \"yes\", \"do it\" —"
            + " authorises nothing by itself and is NEVER explicitness=EXPLICIT (the server caps"
            + " it at INHERITED). It can only continue something already on record: (a) when"
            + " previousTurn's assistantReply named one concrete change, answer status=continue"
            + " as described above — scope CONVERSATION if activeWorkflow.present, else"
            + " OPEN_RECORD; (b) when activeWorkflow.present is true but no such proposal is in"
            + " previousTurn, answer status=route, explicitness=INHERITED, scope=CONVERSATION,"
            + " task/goal from activeWorkflow. Either way the server applies it to that job's own"
            + " records only. With neither present, explicitness=NONE: there is nothing on record"
            + " to continue. Separately, the server may hold a structured pending proposal you are"
            + " not shown — one concrete change a read tool recorded last turn — and promotes a"
            + " bare approval into exactly that change on that one record by itself; your answer"
            + " neither enables nor prevents that.\n"
            + "- When two readings are genuinely open, prefer the less committal one — GENERAL, or"
            + " a lower explicitness — over guessing a specific write. You cannot ask a question,"
            + " so the choice is between two routes, never between a route and a question.\n"
            + "- history is the conversation so far, oldest first, NOT including this message. Use"
            + " it the way a person would reread a chat: to work out what \"it\", \"the same one\","
            + " an abbreviation or a word you do not recognise actually refers to. It is where most"
            + " of your context comes from beyond this one message — read it before falling back to"
            + " GENERAL for something merely unfamiliar rather than genuinely unrelated to this"
            + " ERP. historyTruncated/historyOmittedTurns/historyTotalTurns tell you when the"
            + " oldest turns were left out because the conversation has grown very long — route"
            + " from what you can see; do not treat the gap itself as evidence of anything."
            + " previousTurn IS the most recent exchange in history — when its own text is not"
            + " repeated (sameAsLastHistoryEntry=true), read it from history's LAST entry instead;"
            + " both name the same exchange, never two different ones.\n\n"
            + "WHAT YOUR ANSWER CONTROLS\n"
            + "Your route selects which guide and playbook the assistant is given and which job the"
            + " turn attaches to. It is not a permission. It cannot name a record, cannot reach"
            + " data, and cannot grant a write: the server re-checks the company, the scope, the"
            + " authorised record set and the caller's rights after your answer, and refuses"
            + " anything they do not allow. explicitness is taken as you state it — it is not"
            + " re-capped against a lexical fallback reading, except that a message that is only an"
            + " approval is never EXPLICIT and INHERITED with no matching active job on record"
            + " authorises nothing — and by itself it only satisfies the FIRST of several separate"
            + " checks the server runs before anything is written, so getting it right narrows"
            + " what happens next without granting a write on its own. A status=continue answer is"
            + " checked once more, using something you are not shown: with an active job on record"
            + " it is bound to that job's own authorised records, regardless of what page is"
            + " currently open — opening another record neither invalidates it nor redirects it to"
            + " that record; without such a job, it applies only when the record on screen is the"
            + " one the proposal was about. Database-id references in THIS message"
            + " (explicitRefCount > 0) always override and are never accepted as a continuation."
            + " A continuation that does not check out falls back to an"
            + " ordinary route rather than being trusted, and the assistant — not you — decides"
            + " from there whether the user needs to be asked anything.");
    return sb.toString();
  }

  /**
   * What the screens are called, what may be done on them, and what follows from what.
   *
   * <h2>Why the server supplies this rather than the model knowing it</h2>
   *
   * The router was being asked to map «фактурите», "the uploads", «банковото извлечение» onto a
   * closed vocabulary it had only the enum names for. It guessed, and its guesses were the largest
   * single source of disagreement with the adapter. The vocabulary is the server's, so the server's
   * own names for it belong in the question — including the Bulgarian ones, because that is what
   * users type.
   *
   * <p>Deliberately confined to what this assistant actually supports. An alias for a screen with
   * no tools behind it would route a turn to a capability that does not exist, which is worse than
   * routing it to GENERAL. Nothing here is business data: these are screen names and lifecycle
   * words, identical for every company and every user.
   */
  static String catalog() {
    return "\n\nWHAT THIS ASSISTANT WORKS ON — screens, their names, and what follows from what.\n"
        + "Only these. A request about anything else is domain=GENERAL, not a near match. The BG/EN"
        + " words beside each one are EXAMPLES of common phrasing, not the exhaustive list you"
        + " match against — recognise the same concept under a synonym, a translation, an"
        + " abbreviation, a misspelling or a form none of these examples shows; do not fail a"
        + " match just because the exact word is not listed here.\n\n"
        + "1. OCR Upload → Supplier Invoice → Move\n"
        + "   OCR Upload (BG: качване, документ за разпознаване, OCR документ; EN: upload, scan,"
        + " OCR document). The scanned document before it becomes anything. Model"
        + " com.axelor.apps.aiocr.db.AiOcrUpload, lifecycle …→ COMPLETED (or COMPLETED_WITH_ISSUES)"
        + " once text extraction finishes. Actions: read it, create a draft supplier invoice from"
        + " it. domain=SUPPLIER_INVOICE. An upload reaching COMPLETED is NOT the same fact as an"
        + " invoice reaching POSTED below — completing extraction only means a draft CAN now be"
        + " created from it, not that any invoice exists yet or that one has been posted.\n"
        + "   Supplier Invoice (BG: доставна фактура, фактура за покупка, покупна фактура,"
        + " фактури, ДФ; EN: supplier invoice, purchase invoice, vendor invoice, AP invoice)."
        + " Model com.axelor.apps.account.db.Invoice. Lifecycle DRAFT → VALIDATED → VENTILATED"
        + " (BG: чернова → валидирана → осчетоводена). Actions: read, correct lines and header,"
        + " validate (BG: валидирай), refresh payment terms, ventilate/post (BG: осчетоводи,"
        + " публикувай). Ventilating a supplier invoice PRODUCES a Move — that is the same piece"
        + " of work, not a new one. domain=SUPPLIER_INVOICE; goal DRAFT/VALIDATED/POSTED.\n"
        + "   Move (BG: счетоводна статия, статия, записване, journal entry; EN: move, journal"
        + " entry, accounting entry). Model com.axelor.apps.account.db.Move. Lifecycle NEW →"
        + " DAYBOOK → ACCOUNTED (BG: нова → дневник → осчетоводена). Actions: read, create, edit"
        + " lines, post (BG: осчетоводи). A Move the user names DIRECTLY is"
        + " domain=JOURNAL_ENTRY; a Move reached by posting an invoice is still"
        + " domain=SUPPLIER_INVOICE.\n\n"
        + "2. Customer Invoice → Move\n"
        + "   Customer Invoice (BG: клиентска фактура, продажна фактура, фактура за продажба,"
        + " изходяща фактура, фактура към клиент; EN: customer invoice, sales invoice, outgoing"
        + " invoice, AR invoice). Model com.axelor.apps.account.db.Invoice — the SAME entity as the"
        + " supplier invoice above, told apart only by operationTypeSelect (1/2 supplier purchase"
        + " and refund, 3/4 customer sale and refund). An invoice the company ISSUES to somebody it"
        + " sells to; a supplier invoice is one it RECEIVED. Lifecycle DRAFT → VALIDATED →"
        + " VENTILATED (BG: чернова → валидирана → осчетоводена). Actions: read, create, correct"
        + " lines and header, validate (BG: валидирай), refresh payment terms, ventilate/post (BG:"
        + " осчетоводи, публикувай). Ventilating one PRODUCES a Move, exactly as on the supplier"
        + " side — same piece of work, domain stays CUSTOMER_INVOICE. Raising a NEW credit note"
        + " (BG: кредитно известие) is NOT supported — no tool creates one; an existing credit note"
        + " (operationTypeSelect 4) is read and reported exactly like any other customer invoice."
        + " domain=CUSTOMER_INVOICE; goal DRAFT/VALIDATED/POSTED.\n"
        + "   Choosing between the two invoice domains: say CUSTOMER_INVOICE when the counterparty"
        + " is somebody the company sells to, or the words name a sale, a customer or an outgoing"
        + " document; say SUPPLIER_INVOICE when it is a document that arrived from a supplier, and"
        + " for anything OCR — OCR intake is supplier-side only. An unqualified «фактура» with no"
        + " other signal is SUPPLIER_INVOICE, which is where the inbox work is.\n\n"
        + "3. Bank Statement Line → reconciliation → MoveLine\n"
        + "   Bank Statement (BG: банково извлечение, извлечение, банков отчет; EN: bank"
        + " statement). Model com.axelor.apps.bankpayment.db.BankStatement. Actions: read,"
        + " import. domain=BANK_STATEMENT.\n"
        + "   Bank Statement Line (BG: ред от извлечение, банков ред; EN: bank statement line)."
        + " One movement on the statement. Reconciling a line matches it against a MoveLine (BG:"
        + " ред от счетоводна статия) — that is what «осчетоводи извлечението» and «свържи"
        + " плащането» mean, and it stays domain=BANK_STATEMENT even though it touches a MoveLine,"
        + " because the statement is what the user is working from. NO TOOL currently performs this"
        + " write — route it to domain=BANK_STATEMENT all the same; the assistant reports that"
        + " reconciliation cannot be executed rather than routing the request elsewhere.\n\n"
        + "   Partner (BG: контрагент, доставчик, партньор; EN: partner, supplier, vendor)."
        + " Model com.axelor.apps.base.db.Partner — the external suppliers listed under Purchases →"
        + " Suppliers (Покупки → Доставчици), never the active company's own partner from"
        + " Application config → Companies. Actions: find, create, correct master data (address,"
        + " IBAN/bank account, phone, email, contact, payment mode/condition, currency) and"
        + " accounts. domain=PARTNER; task REVIEW_SUPPLIER to read one, MAINTAIN_SUPPLIER to"
        + " create or change one. A supplier looked up ON THE WAY to fixing an invoice is"
        + " still domain=SUPPLIER_INVOICE — the domain is what the user is working on, not every"
        + " record the work touches.\n";
  }

  /** Everything the server already knows, compact and free of business data. */
  static String context(PageContext page, AiWorkflow workflow, Long companyId) {
    return context(page, workflow, companyId, null, Collections.emptyList());
  }

  /** As above, plus the last exchange this chat panel had, when the server still remembers one. */
  static String context(
      PageContext page,
      AiWorkflow workflow,
      Long companyId,
      AiTurnMemoryStore.Reading previousTurn) {
    return context(page, workflow, companyId, previousTurn, Collections.emptyList());
  }

  /** As above, plus the complete stored conversation, oldest first — see {@link #history}. */
  static String context(
      PageContext page,
      AiWorkflow workflow,
      Long companyId,
      AiTurnMemoryStore.Reading previousTurn,
      List<AiTurnMemoryStore.Reading> history) {
    return context(
        page, workflow, companyId, previousTurn, history, AiTokenEstimate.of(instructions()));
  }

  /**
   * @param outsideTokens estimated tokens of the request text around this block — the contract and
   *     the user's message — which share the budget the history has to fit into
   */
  static String context(
      PageContext page,
      AiWorkflow workflow,
      Long companyId,
      AiTurnMemoryStore.Reading previousTurn,
      List<AiTurnMemoryStore.Reading> history,
      int outsideTokens) {
    return context(page, workflow, companyId, previousTurn, history, outsideTokens, 0);
  }

  /**
   * @param explicitRefCount database-id references the server parsed out of this message — sent as
   *     a count only; see {@link #request}
   */
  static String context(
      PageContext page,
      AiWorkflow workflow,
      Long companyId,
      AiTurnMemoryStore.Reading previousTurn,
      List<AiTurnMemoryStore.Reading> history,
      int outsideTokens,
      int explicitRefCount) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("activeCompanyId", companyId == null ? 0L : companyId);
    // The server's own reading of the message for record ids, as a number. It is the only thing
    // NAMED_RECORDS may be chosen from (see the scope rule), and whatever scope the router returns,
    // a non-zero count is what the server freezes the target set from — never the page.
    root.put("explicitRefCount", Math.max(0, explicitRefCount));

    ObjectNode pageNode = root.putObject("page");
    if (page == null || !page.hasModel()) {
      // Said explicitly rather than omitted: "nothing is open" is a fact the router should route
      // on, and an absent field would be indistinguishable from a client that did not report.
      pageNode.put("open", false);
    } else {
      pageNode.put("open", true);
      pageNode.put("model", page.getModel());
      pageNode.put("viewType", page.getViewType() == null ? "" : page.getViewType());
      pageNode.put("recordOpen", page.hasRecord());
    }

    ObjectNode selection = root.putObject("selection");
    if (page == null || !page.hasDeclaredSelection()) {
      selection.put("present", false);
    } else {
      // A summary, never the ids. The count is what distinguishes "these three" from "these
      // ninety"; which three is not a routing question and never becomes one.
      selection.put("present", true);
      selection.put("count", page.getSelectedCount() == null ? 0 : page.getSelectedCount());
      String defect = page.selectionDefect();
      // A damaged selection is reported as damaged. The router may not be told a set is usable
      // when the server has already decided it is not.
      selection.put("usable", defect == null);
      if (defect != null) {
        selection.put("defect", defect);
      }
    }

    // The complete, trusted pre-turn job, whether or not it turns out to belong to this turn's
    // document. Whether it still applies is a routing decision — AiRouteAdapter compares the
    // SELECTED AiRoute's domain against it once routing is done, and the Router Agent is asked to
    // make the identical judgement itself, from the same facts. Pre-filtering it here with a
    // guess at what the message names (AiRouteAdapter's own text matching) would show the router
    // less than the server actually knows, and a wrong guess would hide a job that did apply.
    ObjectNode job = root.putObject("activeWorkflow");
    if (workflow == null || !workflow.isActive()) {
      job.put("present", false);
    } else {
      job.put("present", true);
      job.put("task", nullSafe(workflow.getTask()));
      job.put("goal", nullSafe(workflow.getGoal()));
      job.put("scope", nullSafe(workflow.getScope()));
      job.put("explicitness", nullSafe(workflow.getExplicitness()));
      job.put("targets", workflow.getTargets() == null ? 0 : workflow.getTargets().size());
      job.put("completed", workflow.getCompleted() == null ? 0 : workflow.getCompleted().size());
      job.put("blocked", workflow.blocked().size());
      job.put("remaining", workflow.remaining().size());
      // The words that authorised the job, so a continuation can be recognised as continuing THIS
      // one. It is the user's own sentence, already stored on the workflow row.
      job.put("request", trim(workflow.getRequest(), 200));
    }

    // The last exchange this chat panel had, from the row the server owns — never from anything
    // the client sent, which is exactly the property that makes a continuation checkable rather
    // than a client's own claim about what it was told. Present only when the server still holds
    // one; a bare affirmation with nothing here has nothing to continue and must not become
    // status=continue.
    ObjectNode previous = root.putObject("previousTurn");
    if (previousTurn == null || !previousTurn.hasOffer()) {
      previous.put("present", false);
    } else {
      previous.put("present", true);
      if (sameAsLastHistoryEntry(previousTurn, history)) {
        // Already present, in full, as the last entry of "history" below — both are read from the
        // same stored conversation. Repeating the text here too would send this one exchange
        // twice in every request, at full length since neither copy is capped.
        previous.put("sameAsLastHistoryEntry", true);
      } else {
        // No history was supplied (or it does not carry this exchange, e.g. a caller using the
        // history-less overload) — this is the only copy, so it is sent in full.
        previous.put("userMessage", nullSafe(previousTurn.userMessage()));
        previous.put("assistantReply", nullSafe(previousTurn.assistantReply()));
      }
    }

    // The complete stored conversation, oldest first, NOT including the current message — that is
    // sent separately as USER MESSAGE, so nothing here is asked to interpret its own duplicate. See
    // #firstKept for the one case it is not sent whole.
    int total = history == null ? 0 : history.size();
    int fixed = outsideTokens + AiTokenEstimate.of(root.toString()) + HISTORY_FIELDS_TOKENS;
    int omitted = total == 0 ? 0 : firstKept(history, historyAllowanceTokens(fixed));
    root.set("history", history(history, omitted));
    // Stated on the root, not folded into the array, so the router reads it as a fact about the
    // conversation rather than as a turn to interpret. Present even when nothing was omitted, so
    // its absence never has to be read as "nothing was cut" by inference.
    root.put("historyTruncated", omitted > 0);
    root.put("historyOmittedTurns", omitted);
    root.put("historyTotalTurns", total);
    return root.toString();
  }

  /**
   * The index of the oldest exchange still kept, when the stored conversation must be cut down to
   * fit one router request — 0 when it all fits, which is the ordinary case.
   *
   * <h2>The hard-limit case, and why it is never silent</h2>
   *
   * {@code AiTurnMemoryStore} never discards a message — the row keeps every exchange until the
   * user clears the conversation. What can genuinely not happen is handing the router more text
   * than its own request can carry alongside these instructions and the catalog above. That is the
   * same shape of problem {@code AiContextBudget} solves for the main model's chain, and it is
   * solved the same way: past the bound, the OLDEST whole exchanges are left out — never a partial
   * one — and the omission is stated on the payload itself, as {@code historyTruncated} / {@code
   * historyOmittedTurns} / {@code historyTotalTurns}, so the router (and anyone reading a captured
   * request) can see exactly what happened rather than reasoning from a transcript that quietly
   * starts later than it should.
   *
   * <h2>What is counted</h2>
   *
   * Each exchange is measured as the JSON entry actually sent — escaping and keys included — with
   * {@link AiTokenEstimate}, a conservative estimate rather than a count: a router call is
   * stateless and has no earlier {@code usage} figure to project from. The allowance is what
   * remains of {@code AiAccountantConfig#routerRequestTokenBudget} after the output reserve and
   * everything else in the same request — the contract, the structured context and the user's
   * message — so none of those is ever cut to make room for history.
   *
   * <p>The newest exchange is always kept, even alone over the allowance: it is the one a «да» or
   * «продължи» refers to, and without it a continuation cannot be recognised at all. That makes
   * this a best effort, not a bound: the allowance can go negative (a very long message alone can
   * exceed it), so {@code AiRouteSelector} measures the complete request afterwards and does not
   * send one that cannot fit — the adapter's route is used instead.
   *
   * @param allowanceTokens estimated tokens the history may use; zero or negative keeps only the
   *     newest exchange
   */
  static int firstKept(List<AiTurnMemoryStore.Reading> history, int allowanceTokens) {
    int[] cost = new int[history.size()];
    long size = 0;
    for (int i = 0; i < history.size(); i++) {
      // +1 for the separating comma.
      cost[i] = AiTokenEstimate.of(historyEntry(history.get(i)).toString()) + 1;
      size += cost[i];
    }
    int start = 0;
    while (start < history.size() - 1 && size > allowanceTokens) {
      size -= cost[start];
      start++;
    }
    return start;
  }

  /** What the history may use once the output reserve and the rest of the request are paid for. */
  static int historyAllowanceTokens(int fixedTokens) {
    int budget = AiAccountantConfig.DEFAULT_ROUTER_REQUEST_TOKEN_BUDGET;
    int reserve = AiAccountantConfig.DEFAULT_ROUTER_OUTPUT_RESERVE_TOKENS;
    try {
      AiAccountantConfig config = Beans.get(AiAccountantConfig.class);
      budget = config.routerRequestTokenBudget();
      reserve = config.routerOutputReserveTokens();
    } catch (RuntimeException e) {
      // No injector (unit tests, early boot): the configuration's own defaults, not a second set.
    }
    return budget - Math.max(0, reserve) - fixedTokens;
  }

  /**
   * The kept exchanges, oldest first, as {@code {"user":…, "assistant":…}} objects.
   *
   * <p>Each turn's full stored text goes in, untrimmed: the only size bound on this array is {@link
   * #firstKept} dropping whole oldest turns to fit the router's token budget. A per-message trim
   * here on top of that would cap every turn's history at a fixed length regardless of how far
   * under the whole-conversation budget it was — exactly the "complete conversation" property
   * {@code AiTurnMemoryStore} exists to provide.
   */
  static ArrayNode history(List<AiTurnMemoryStore.Reading> history, int firstKept) {
    ArrayNode array = MAPPER.createArrayNode();
    if (history == null) {
      return array;
    }
    for (int i = firstKept; i < history.size(); i++) {
      array.add(historyEntry(history.get(i)));
    }
    return array;
  }

  /** One exchange exactly as it is sent, so it is also exactly what {@link #firstKept} measures. */
  private static ObjectNode historyEntry(AiTurnMemoryStore.Reading turn) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("user", nullSafe(turn.userMessage()));
    node.put("assistant", nullSafe(turn.assistantReply()));
    return node;
  }

  /**
   * The history fields' own cost on the root, with room for six-digit counts — charged to the fixed
   * part, since they are sent whether or not anything is omitted.
   */
  private static final int HISTORY_FIELDS_TOKENS =
      AiTokenEstimate.of(
          ",\"history\":[],\"historyTruncated\":false,\"historyOmittedTurns\":000000,"
              + "\"historyTotalTurns\":000000");

  /**
   * Whether {@code previousTurn} names the same exchange as {@code history}'s last entry — true
   * whenever both were loaded from the same stored conversation, which is the ordinary case. See
   * {@link #context}: when true, {@code previousTurn}'s own text is omitted rather than repeated.
   */
  private static boolean sameAsLastHistoryEntry(
      AiTurnMemoryStore.Reading previousTurn, List<AiTurnMemoryStore.Reading> history) {
    if (previousTurn == null || history == null || history.isEmpty()) {
      return false;
    }
    AiTurnMemoryStore.Reading last = history.get(history.size() - 1);
    return Objects.equals(previousTurn.userMessage(), last.userMessage())
        && Objects.equals(previousTurn.assistantReply(), last.assistantReply());
  }

  private static String nullSafe(String s) {
    return s == null ? "" : s;
  }

  private static String trim(String s, int max) {
    if (s == null) {
      return "";
    }
    String t = s.trim();
    return t.length() <= max ? t : t.substring(0, max) + "…";
  }

  private static String names(Enum<?>[] values) {
    StringBuilder sb = new StringBuilder();
    for (Enum<?> v : values) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(v.name());
    }
    return sb.toString();
  }
}
