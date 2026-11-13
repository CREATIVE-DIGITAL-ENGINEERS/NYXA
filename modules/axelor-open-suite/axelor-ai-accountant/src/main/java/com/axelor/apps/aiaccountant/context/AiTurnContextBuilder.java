package com.axelor.apps.aiaccountant.context;

import com.axelor.apps.aiaccountant.dto.AiConversationState;
import com.axelor.apps.aiaccountant.dto.AiWorkflow;
import com.axelor.apps.aiaccountant.dto.AiWorkflowRecord;
import com.axelor.apps.aiaccountant.dto.PageContext;
import com.axelor.apps.aiaccountant.prompt.AiInstructionSet;
import com.axelor.apps.aiaccountant.prompt.AiOperatingProcedure;
import com.axelor.apps.aiaccountant.prompt.AiPlaybookLibrary;
import com.axelor.apps.aiaccountant.prompt.AiPromptDocument;
import com.axelor.apps.aiaccountant.prompt.AiTask;
import com.axelor.apps.aiaccountant.prompt.AiTaskResolver;
import com.axelor.apps.aiaccountant.route.AiRoute;
import com.axelor.apps.aiaccountant.route.AiScope;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.service.AiWorkflowIntent;
import com.axelor.apps.base.db.Company;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles the text one model request is given.
 *
 * <h2>Why it is its own class</h2>
 *
 * Four different things decide what the model sees — the operating procedure, the continuity block
 * after a chain reset, the page context, and the recent-workflow descriptor — and all four lived
 * inside the class that drives the Responses API and executes tool calls. They share nothing with
 * the tool loop except the moment they run. Collected here, the whole of what the model is told can
 * be read in one file, and the orchestrator asks for it in one line.
 *
 * <h2>The order is load-bearing</h2>
 *
 * Procedure first, then continuity, then page context, then the message. Prompt caching matches on
 * a <em>prefix</em>, so anything that differs between users or between turns — a company name, a
 * record id, today's date — has to come after everything that does not. Interleaving them, or
 * moving the company into the procedure, would invalidate the cached prefix on every turn and turn
 * a multi-thousand-token cache hit into a full re-read.
 *
 * <p>Everything below the procedure is best-effort and individually guarded: context enrichment is
 * an optimisation over tool calls the model would otherwise make, and a failure in it must cost a
 * round trip, never the turn.
 */
@Singleton
public class AiTurnContextBuilder {

  private static final Logger LOG = LoggerFactory.getLogger(AiTurnContextBuilder.class);

  /** The OCR carrier model, so page context can turn "viewing this" into a usable uploadId. */
  public static final String OCR_UPLOAD_MODEL = "com.axelor.apps.aiocr.db.AiOcrUpload";

  /**
   * The whole input for one request.
   *
   * @param firstTurn true when this starts a new Azure chain and the procedure must be re-sent
   * @param continuity the handoff block, or null when the chain was not reset by size
   * @param state what the client carried over from the previous turn, or null
   */
  public String build(
      String message,
      PageContext context,
      boolean firstTurn,
      String continuity,
      AiConversationState state) {
    return build(
        message, context, firstTurn, continuity, state, AiTaskResolver.resolve(message, state));
  }

  /**
   * As above, with the task already resolved so the caller can record it without resolving twice.
   *
   * <h2>Where the domain guide and the playbook go, and why there</h2>
   *
   * Procedure, then domain guide, then playbook, then continuity, then page context, then the
   * message — ordered by how often each one changes. The procedure is the same bytes for every user
   * and every turn, so it heads the cached prefix. The domain guide is the same bytes for every
   * turn about the same kind of document, so it survives a move between reviewing, repairing,
   * validating and posting one invoice. The playbook is the same bytes for every turn <em>of the
   * same task</em> — and a session is normally a run of turns on one job — so it sits next, and
   * only a genuine change of subject breaks the prefix.
   *
   * <p>Everything below is volatile by nature: a record id, a company name, today's date. Moving
   * any of that above the playbook would invalidate the prefix on every single turn.
   *
   * <p>Both are sent every turn rather than only the first, because a conversation that opens with
   * a general question and then turns to an invoice must still get the rules for it — and the task
   * can change from one turn to the next, which is the whole point of resolving it per turn.
   */
  public String build(
      String message,
      PageContext context,
      boolean firstTurn,
      String continuity,
      AiConversationState state,
      AiTask task) {
    return build(
        message,
        context,
        firstTurn,
        continuity,
        state,
        new AiRoute(null, task, null, AiScope.UNSCOPED, null));
  }

  /**
   * As above, selecting the instruction documents from the whole route rather than the task alone.
   *
   * <h2>The order, and why it is this one</h2>
   *
   * <ol>
   *   <li><b>Global rules</b> — the operating procedure, identical for every user and every turn;
   *   <li><b>domain guide</b> — identical for every turn about this kind of document;
   *   <li><b>task playbook</b>, and the <b>bulk supplement</b> when the instruction covers a set;
   *   <li><b>active workflow state</b> — what the server recorded about the job in progress;
   *   <li><b>ERP/page/company context</b> — who, where, today, what is on screen;
   *   <li><b>the user's message</b>, last and unaltered.
   * </ol>
   *
   * <p>Static first, volatile last, because prompt caching matches on a prefix: a company name or a
   * record id above the playbook would invalidate several thousand tokens of cache on every turn.
   * Within the volatile part the job comes before the screen deliberately — an active job is the
   * authority on what is being worked on, and the open record is only what happens to be visible,
   * so the model reads them in that order of precedence.
   *
   * <p>The documents are sent every turn rather than only the first, because a conversation that
   * opens with a general question and then turns to an invoice must still get the rules for it.
   */
  public String build(
      String message,
      PageContext context,
      boolean firstTurn,
      String continuity,
      AiConversationState state,
      AiRoute route) {
    return build(message, context, firstTurn, continuity, state, route, null);
  }

  /**
   * As above, told which job the server holds.
   *
   * <h2>Why the workflow is a parameter and not read from the state</h2>
   *
   * Everything below the instruction documents is what the model is allowed to treat as fact about
   * the work in progress, and after a chain reset it is the <em>only</em> thing it has — the
   * retained history is gone. So it has to come from the record the server owns rather than from
   * the blob the browser returned, which is a value this process last saw a turn ago and has no way
   * to vouch for.
   *
   * <p>Null is ordinary and means "use whatever the blob has", which is how every caller behaved
   * before this parameter existed and remains right for a turn with no stored job.
   */
  public String build(
      String message,
      PageContext context,
      boolean firstTurn,
      String continuity,
      AiConversationState state,
      AiRoute route,
      AiWorkflow authoritative) {
    return build(message, context, firstTurn, continuity, state, route, authoritative, null);
  }

  /**
   * As above, telling the model what it is continuing when this turn's route came from a verified
   * continuation of last turn's own proposal.
   *
   * <h2>Why this is not the continuity block above</h2>
   *
   * {@link #continuityHandoff} exists for the moment the retained history is GONE and deliberately
   * carries no model prose — a caller able to supply the assistant's own earlier words there would
   * be a caller able to tell the assistant what it had said, and it would be believed because
   * nothing is left to check it against. This block is different in kind: it runs on ordinary
   * turns, chain intact or not, and its whole job is to say what the assistant itself proposed —
   * because that is precisely the fact a bare «да» needs interpreted. It is sent only once {@code
   * AiRouteSelector} has confirmed the record on screen matches the one that proposal was about, so
   * trusting it here carries the same guarantee trusting an explicit instruction does.
   *
   * @param continuationEcho the block from {@code AiRequestContext.getContinuationEcho()}, or null
   */
  public String build(
      String message,
      PageContext context,
      boolean firstTurn,
      String continuity,
      AiConversationState state,
      AiRoute route,
      AiWorkflow authoritative,
      String continuationEcho) {
    StringBuilder sb = new StringBuilder();
    if (firstTurn) {
      sb.append(AiOperatingProcedure.text()).append("\n\n");
    }
    AiInstructionSet instructions = AiInstructionSet.forRoute(route);
    if (instructions.isComplete()) {
      for (AiPromptDocument doc : instructions.documents()) {
        sb.append(doc.text()).append("\n\n");
      }
    } else {
      // Something the route called for is not on the classpath. Rather than send a set of
      // instructions with a hole in it — a batch turn told it is a batch by one document and given
      // the single-record procedure by another — fall back to the composition that existed before
      // the selection layer, which is complete on its own terms.
      LOG.warn(
          "AI Accountant: instruction set incomplete for {}; falling back to the domain guide and"
              + " playbook alone.",
          route == null ? "-" : route.describe());
      AiTask task = route == null ? null : route.task();
      String domain = AiPlaybookLibrary.domainGuide(task);
      if (domain != null) {
        sb.append(domain).append("\n\n");
      }
      String playbook = AiPlaybookLibrary.playbook(task);
      if (playbook != null) {
        sb.append(playbook).append("\n\n");
      }
    }
    if (continuity != null && !continuity.isEmpty()) {
      sb.append(continuity).append("\n\n");
    }
    sb.append(activeWorkflow(state, authoritative));
    if (continuationEcho != null && !continuationEcho.isEmpty()) {
      sb.append(continuationEcho).append("\n\n");
    }
    sb.append(pageContext(context, state)).append(message);
    return sb.toString();
  }

  /**
   * The job in progress as its own block, ahead of the page context.
   *
   * <p>It used to sit inside {@code [Nyxa context]}, between the company and the open record. Both
   * are volatile so the cache is indifferent, but the reading order is not: the block that says
   * what was authorised and what remains belongs above the block that says what happens to be on
   * screen, because that is the precedence the rules give them.
   *
   * @return the block and a blank line, or an empty string when no job is active
   */
  public String activeWorkflow(AiConversationState state) {
    return activeWorkflow(state, null);
  }

  /**
   * As above, stating the job the <em>server</em> holds rather than the one the browser returned.
   *
   * <p>Same rule as the continuity handoff, and for the same reason: this block tells the model
   * what is authorised, what is done and what is left, which is precisely the sort of thing that
   * must not be read from a value the caller supplied when the server has its own record of it.
   * They normally agree; when they do not, the row is what the last turn actually observed.
   */
  public String activeWorkflow(AiConversationState state, AiWorkflow authoritative) {
    StringBuilder sb = new StringBuilder();
    if (!appendActiveWorkflow(sb, state, authoritative)) {
      return "";
    }
    // The appender writes its parts space-separated, as it did when it was one clause inside the
    // context block; standing on its own it starts at the margin.
    return sb.toString().trim() + "\n\n";
  }

  /**
   * What a new chain needs to know about the old one — deterministic, and deliberately tiny.
   *
   * <h2>Why not a summary</h2>
   *
   * Summarising the old conversation would mean another model call, on the turn the user is already
   * waiting on, to compress history that is mostly tool output nobody will refer to again. Worse, a
   * generated summary can be wrong about accounting facts while sounding authoritative — the exact
   * failure this design spends its effort avoiding everywhere else.
   *
   * <p>So this carries pointers, not data: which records were being worked on, and the user's own
   * unfinished instruction in their own words. Everything substantive — statuses, totals, blockers,
   * supplier details — is re-read through a tool against the live database, which is where it
   * should come from anyway. That is said to the model explicitly, because a block of context with
   * no such warning reads exactly like the tool results it replaced.
   *
   * <h2>What may cross a reset, exhaustively</h2>
   *
   * Three kinds of thing, and nothing else:
   *
   * <ul>
   *   <li><b>the user's own words</b> — the instruction that authorised the job, verbatim and
   *       capped;
   *   <li><b>record references and counts</b> — {@code Invoice#606}, "four remaining", read from
   *       the workflow the server folded in at the end of each turn;
   *   <li><b>refusals a tool itself produced</b>, as the blocked reasons, each a sentence a Java
   *       service wrote about a record it had just examined.
   * </ul>
   *
   * <p>What may <em>not</em> cross is anything the model said. Not a summary of the conversation,
   * not its account of what it did, not its explanation of why something failed. The reason is the
   * one this whole layer exists for: a chain reset is the moment the model's memory is replaced by
   * whatever this block contains, so a sentence it wrote about its own work would come back as
   * established fact with nothing left to check it against. There is deliberately no field here
   * that could carry one — the sources above are the only inputs, and each is written by a person
   * or by Java.
   *
   * <p>Bounded by construction: the instruction is capped at 300 characters and the record list at
   * six ids, so it cannot grow into the thing it exists to prevent.
   */
  public String continuityHandoff(AiConversationState state) {
    return continuityHandoff(state, null);
  }

  /**
   * As above, preferring the job the <em>server</em> recorded over the one the browser returned.
   *
   * <h2>Which copy is authority</h2>
   *
   * Both exist and they normally agree. The blob is a value the client supplied and sent back; the
   * workflow is the row this user, this company and this conversation own, folded in at the end of
   * every turn from what the server actually watched happen. When a reset rebuilds the model's
   * context, the whole point is that it is rebuilt from what can be trusted — so the row wins, and
   * the blob is the fallback for a conversation that has no row (a read-only session, a store that
   * could not be reached).
   *
   * <p>Note what is carried either way: the user's own words, and record references. No model
   * prose, no summary, no earlier answer. That is not an accident of what happens to be available —
   * it is the rule, and it is why this method takes a workflow and a state rather than a
   * conversation.
   *
   * @param workflow the stored job, or null when there is none to carry
   */
  public String continuityHandoff(AiConversationState state, AiWorkflow workflow) {
    StringBuilder sb = new StringBuilder("[Continuity]");
    sb.append(
        " The earlier part of this conversation is no longer attached (it grew too large). This"
            + " block is NOT data — it is a reminder of where you were. Every fact you state must"
            + " come from a tool call against the current records.");

    // The stored job, or nothing at all. There is deliberately no fallback to the returned blob:
    // this block is what the model treats as established fact once the history is gone, so a caller
    // able to supply it would be a caller able to tell the assistant what it had been authorised to
    // do — and it would be believed, because after a reset there is nothing left to check it
    // against.
    boolean fromStore = workflow != null && workflow.isActive();
    String request = fromStore ? workflow.getRequest() : null;
    List<String> refs = fromStore ? workflow.remaining() : null;

    if (request == null || request.trim().isEmpty()) {
      sb.append(
          " No unfinished work is recorded. If the user refers to something earlier that you cannot"
              + " identify from the context below, ask which record they mean.");
      return sb.toString();
    }
    sb.append(" UNFINISHED, ALREADY AUTHORISED by the user in these words: \"")
        .append(request)
        .append("\".");
    if (refs != null && !refs.isEmpty()) {
      sb.append(" It applies to these records and to NO others: ")
          .append(
              String.join(
                  ", ",
                  refs.size() > AiConversationState.MAX_REFS
                      ? refs.subList(0, AiConversationState.MAX_REFS)
                      : refs))
          .append(refs.size() > AiConversationState.MAX_REFS ? ", and others" : "")
          .append(".");
    }
    if (fromStore) {
      // Counts, from the row rather than from anybody's recollection. The full block below the
      // handoff states them again in detail; what this adds is that they survived the reset.
      sb.append(" Progress so far: ")
          .append(workflow.getCompleted().size())
          .append(" done, ")
          .append(workflow.blocked().size())
          .append(" blocked, ")
          .append(workflow.remaining().size())
          .append(" remaining — the [Active job] block below is authoritative on all three.");
    }
    sb.append(
        " That authorisation still stands: if the user says «продължи»/«continue», resume it from"
            + " the CURRENT state of those records — read them first — and do not ask them to"
            + " confirm the same operations again. If they are already in the state that was asked"
            + " for, say so and stop; never carry this authorisation over to another invoice. Ask"
            + " only if a genuinely new acknowledgement appears (duplicate document number, PFP"
            + " approval) or a tool refuses.");
    return sb.toString();
  }

  /**
   * Everything the model would otherwise have to spend a tool call discovering: who is asking,
   * which company they are confined to, today's date, exactly which record is on their screen, what
   * the next eligible record is, and which operation «същото» refers to.
   *
   * <p>This block costs roughly sixty to a hundred tokens per turn. It replaces, on the turns where
   * it applies, a {@code get_current_user_info} call and a {@code search_ocr_records} call — each
   * of which is a whole extra model request carrying the full fixed payload before it can even ask.
   * The trade is not close.
   *
   * <p>Sent on every turn rather than only the first, because a user changes company and opens
   * another record mid-conversation, and stale context is worse than none.
   */
  public String pageContext(PageContext context, AiConversationState state) {
    StringBuilder sb = new StringBuilder("[Nyxa context]");
    boolean any = false;

    // Identity and scope. The company is not a preference the model may reason about: it is the
    // boundary every tool enforces, so saying it here also tells the model what it cannot see.
    try {
      User user = AuthUtils.getUser();
      if (user != null) {
        sb.append(" User: ")
            .append(user.getName())
            .append(" (")
            .append(user.getCode())
            .append(").");
        any = true;
      }
      Company company = AiCompanyScope.currentCompanyOrNull();
      if (company != null) {
        sb.append(" Active company: ")
            .append(company.getName())
            .append(" (id ")
            .append(company.getId())
            .append(") — the ONLY company whose data you can read or change.");
        any = true;
      }
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not add identity context: {}", e.getMessage());
    }
    sb.append(" Today: ").append(LocalDate.now()).append('.');

    // The active job is no longer written here: it is its own block, emitted above this one by
    // build(). Kept out rather than duplicated — two statements of what remains, one of them
    // stale, is exactly the ambiguity the block was introduced to remove.
    any |= appendOpenRecord(sb, context);
    any |= appendWorklist(sb);
    any |= appendRecentWorkflow(sb, state);

    if (!any) {
      // Only the date is known. Still worth sending: it is what "днес"/"this month" resolve
      // against, and it is two tokens.
      return sb.append("\n\n").toString();
    }
    sb.append("\n\n");
    return sb.toString();
  }

  /**
   * The job in progress, as the server recorded it — the answer to «продължи», «какво остана?» and
   * «какво оправи?».
   *
   * <h2>Why it is stated rather than inferred</h2>
   *
   * All three questions used to be answered from the conversation: the model re-read its own
   * earlier prose and worked out what it thought was left. That is the one source that cannot be
   * checked, and it failed in both directions — a record it had described as fixed but never
   * written, and a target set it silently narrowed to the invoice most recently discussed. The
   * server watched every write and every refusal happen, so it can simply say.
   *
   * <p>It also removes the reason to ask. A turn that knows the goal and the remaining targets has
   * no honest use for "shall I continue?" or "which records did you mean?", and the block says so
   * in one sentence rather than leaving the model to infer it from the authorisation rules.
   */
  protected boolean appendActiveWorkflow(StringBuilder sb, AiConversationState state) {
    return appendActiveWorkflow(sb, state, null);
  }

  /** As above, with the stored job taking precedence over the returned blob's copy of it. */
  protected boolean appendActiveWorkflow(
      StringBuilder sb, AiConversationState state, AiWorkflow authoritative) {
    // The stored job, or no block at all. The blob used to stand in when there was no row, which
    // meant the sentence "that authorisation still stands for every record below" could be printed
    // from a list the caller supplied.
    if (authoritative == null || !authoritative.isActive()) {
      return false;
    }
    AiWorkflow workflow = authoritative;
    sb.append(" [Active job] Goal: bring these to ").append(workflow.getGoal()).append(".");
    if (workflow.getRequest() != null) {
      sb.append(" Authorised by the user in these words: \"")
          .append(workflow.getRequest())
          .append("\" — that authorisation still stands for every record below; do NOT ask for it")
          .append(" again.");
    }
    // Counts first, then a preview. The full set lives in the state and is authoritative; printing
    // forty ids here would cost a hundred and sixty tokens on every turn of the job to tell the
    // model something it can act on without seeing.
    sb.append(" Targets: ").append(workflow.getTargets().size()).append(" in total.");
    if (!workflow.getCompleted().isEmpty()) {
      sb.append(" DONE (")
          .append(workflow.getCompleted().size())
          .append(", already at the goal — never write to these again): ")
          .append(AiWorkflow.preview(workflow.getCompleted(), true))
          .append(".");
    }
    List<AiWorkflowRecord> blocked = workflow.blocked();
    if (!blocked.isEmpty()) {
      sb.append(" BLOCKED (").append(blocked.size()).append("): ");
      int shown = 0;
      for (AiWorkflowRecord record : blocked) {
        if (shown == AiWorkflow.PREVIEW_REFS) {
          sb.append("; и още ").append(blocked.size() - shown);
          break;
        }
        if (shown > 0) {
          sb.append("; ");
        }
        shown++;
        sb.append(record.getRef());
        if (record.getReason() != null) {
          sb.append(" (").append(record.getReason()).append(")");
        }
      }
      sb.append(". Only a blocker needing a human DECISION is a reason to ask the user; a blocker")
          .append(" you have a tool for is work, not a question.");
    }
    List<String> remaining = workflow.remaining();
    if (!remaining.isEmpty()) {
      sb.append(" REMAINING (")
          .append(remaining.size())
          .append("): ")
          .append(AiWorkflow.preview(remaining, true))
          .append(". That COUNT is the authoritative size of what is left — «продължи», «какво")
          .append(" остана», «довърши» mean all ")
          .append(remaining.size())
          .append(" of them, not only the ids printed here. Do not ask which ones, do not ask")
          .append(" whether to continue, and do not re-derive the set from earlier messages; work")
          .append(" through them and say how many are left when the turn runs out.");
    } else {
      sb.append(" Nothing remains: every target is done or blocked. Say so and stop.");
    }
    return true;
  }

  /**
   * The record on the user's screen — or, just as important, the fact that there is not one.
   *
   * <h2>Why a list says so out loud</h2>
   *
   * The block used to be omitted entirely when no record was open, and the model filled the silence
   * with the last record it had heard about. Asked «дай needsReview записи» from the Upload
   * Invoices list, it argued about a completed invoice from three turns earlier. Saying "a list is
   * open and nothing is selected" costs about fifteen tokens and removes the ambiguity that
   * produced it.
   *
   * <p>Either way it ends with the precedence rule, because an open record is a convenience, not an
   * instruction: a user looking at invoice 946 who asks for every needsReview record is asking
   * about every needsReview record.
   */
  protected boolean appendOpenRecord(StringBuilder sb, PageContext context) {
    if (context == null || !context.hasModel()) {
      return false;
    }
    if (!context.hasRecord()) {
      sb.append("\nCurrent page: ")
          .append(context.getModel())
          .append(" ")
          .append(listWord(context.getViewType()))
          .append(
              ". NO record is open — there is no \"this record\". If the user refers to one, it")
          .append(" is a record you must find from what they say, not one you remember.");
      return true;
    }
    sb.append("\nOpen record: ")
        .append(context.getModel())
        .append(" id ")
        .append(context.getRecordId());
    if (context.getViewName() != null && !context.getViewName().trim().isEmpty()) {
      sb.append(" (view ").append(context.getViewName().trim()).append(")");
    }
    sb.append(".");
    // Spelling out the consequence, not just the fact. Left implicit, the model opened almost every
    // turn with a search_ocr_records for the record already on the user's screen — a whole round
    // trip to learn an id it had been handed. "this record"/"този запис" means this one.
    if (OCR_UPLOAD_MODEL.equals(context.getModel())) {
      sb.append(" That IS the OCR record this question is about: use uploadId=")
          .append(context.getRecordId())
          .append(" directly. Do NOT call search_ocr_records — there is nothing to find.");
      sb.append(
          " But this is only what is on screen: if the message names other records by id or"
              + " refers to a selection, work on THOSE and ignore what is open. A narrowed set"
              + " (needsReview, the remaining ones, the next two) cannot be resolved by the server"
              + " and its writes are refused: say so and ask the user to select the rows or name"
              + " the records.");
      return true;
    }
    // A feature module may be able to turn this record into the id its own tools take — the OCR
    // Inbox is a filtered Invoice view, so this is the common case, not an edge one.
    List<String> resolved;
    try {
      resolved =
          Beans.get(AiPageContextRegistry.class)
              .describe(context.getModel(), context.getRecordId());
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not resolve the open record: {}", e.getMessage());
      resolved = null;
    }
    if (resolved == null || resolved.isEmpty()) {
      sb.append(
          " If they say \"this\" record/invoice/document, they mean this one — identify it from"
              + " this context rather than searching for it.");
    } else {
      for (String line : resolved) {
        sb.append(' ').append(line);
      }
    }
    sb.append(
        " But this is only what is on screen: if the message names other records by id or"
            + " refers to a selection, work on THOSE and ignore what is open. A narrowed set"
            + " (needsReview, the remaining ones, the next two) cannot be resolved by the server"
            + " and its writes are refused: say so and ask the user to select the rows or name"
            + " the records.");
    return true;
  }

  /** "list" for a grid, otherwise the view type itself, so cards and calendars read correctly. */
  private static String listWord(String viewType) {
    if (viewType == null || viewType.trim().isEmpty() || "grid".equals(viewType)) {
      return "list";
    }
    return viewType.trim() + " view";
  }

  /**
   * What "the next invoice" means, computed from the database on this turn.
   *
   * <p>Without it the model answers that question from the transcript, where the most recently
   * discussed invoice may have been posted several turns ago — recency in a conversation is not
   * eligibility in a ledger, and only the server can tell them apart.
   */
  protected boolean appendWorklist(StringBuilder sb) {
    boolean any = false;
    try {
      for (String line : Beans.get(AiWorklistRegistry.class).describe()) {
        sb.append(' ').append(line);
        any = true;
      }
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not add the worklist to context: {}", e.getMessage());
    }
    return any;
  }

  /**
   * Which operation «същото» means, as a shape and nothing else.
   *
   * <h2>Why the server has to say it</h2>
   *
   * «Същото и с тази» was understood when the previous workflow was still in the model's context
   * and not when it was not — after a size-bounded chain reset, or simply far enough back. The
   * failure was silent: it asked what "the same" meant, on a turn where the answer was one tool
   * call away.
   *
   * <p>A shape is all it needs, and a shape is all it gets. No amount, no date, no supplier, no
   * line: repeating an operation is not copying a record, and the sentence says so, because a model
   * handed both a previous invoice and a new one will otherwise reuse a value from the wrong one.
   */
  protected boolean appendRecentWorkflow(StringBuilder sb, AiConversationState state) {
    if (state == null || !state.hasShape()) {
      return false;
    }
    String readable = AiWorkflowIntent.readable(state.getShape());
    if (readable == null) {
      return false;
    }
    sb.append(" [Recent] The last operation you performed was ").append(readable);
    if (state.getShapeTarget() != null) {
      sb.append(" on ").append(state.getShapeTarget());
    }
    sb.append(state.isShapeOk() ? " (it succeeded)." : " (it did not finish).");
    sb.append(
        " If the user says «същото»/«направи същото»/«same again», that is the sequence they mean:"
            + " repeat those OPERATIONS on the record they now indicate. Take every value from that"
            + " record's own evidence — never reuse an amount, date, supplier or line from the"
            + " previous one.");
    return true;
  }
}
