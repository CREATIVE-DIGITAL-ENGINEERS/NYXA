package com.axelor.apps.aiaccountant.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What the user asked for, and what was actually done, in terms the server can check.
 *
 * <h2>The failure this exists for</h2>
 *
 * A turn asked to correct, validate and post invoice 606 ran out of tool iterations. The invoice
 * was POSTED — the work was finished — and the server still answered "the turn hit its limit before
 * the task was finished… say continue". Hitting the iteration cap had been treated as proof that
 * the business workflow was incomplete, and it is not: the cap counts model round trips, and a
 * workflow can reach its target on the last one.
 *
 * <p>So the completion question is answered the only way it can be answered honestly — by naming
 * the outcome the user authorised, reading the records' current lifecycle state, and comparing the
 * two. Both halves are deterministic; neither asks the model what it thinks it did.
 *
 * <h2>Deliberately narrow</h2>
 *
 * Only two outcomes are recognised, because only two are provable from a record's lifecycle:
 * VALIDATED and POSTED. "Correct this invoice" has no lifecycle signature — a corrected draft and
 * an uncorrected one are both DRAFT — so it returns null and the caller falls back to the message
 * that claims nothing. Unrecognised phrasing does the same. Every ambiguity resolves toward "cannot
 * prove", never toward "finished".
 */
public final class AiWorkflowIntent {

  /**
   * How far through its lifecycle the user asked a record to be taken.
   *
   * <p>A <em>destination</em>, not an instruction to call one tool. «Осчетоводи фактурата» on a
   * DRAFT used to mean a single {@code ventilate_supplier_invoice} call, which the tool correctly
   * refused because the invoice was not validated — and the user was told to say "валидирай и
   * осчетоводи" instead, as though the words rather than the invoice had been the problem. The
   * stage is the goal; the steps that reach it are the assistant's to take.
   */
  public enum Stage {
    DRAFT(1),
    VALIDATED(2),
    POSTED(3);

    private final int rank;

    Stage(int rank) {
      this.rank = rank;
    }

    public int rank() {
      return rank;
    }
  }

  /** Lifecycle tokens a resolver may return; see {@code AiPageRecordResolver.lifecycle}. */
  public static final String DRAFT = "DRAFT";

  public static final String VALIDATED = "VALIDATED";
  public static final String POSTED = "POSTED";
  public static final String CANCELLED = "CANCELLED";

  /** More than this and the descriptor stops being a shape and starts being a transcript. */
  private static final int MAX_SHAPE_STEPS = 4;

  private AiWorkflowIntent() {}

  /**
   * The terminal state the message authorises, or null when it cannot be established.
   *
   * <p>Matched on Bulgarian and English stems rather than whole words: Bulgarian conjugates
   * ('осчетоводи', 'осчетоводяване', 'да я осчетоводиш' are one instruction), and a stem match
   * cannot turn a request to post into a request to validate.
   *
   * <p>A negation anywhere in the message abandons the attempt entirely rather than trying to work
   * out what it negates. «недей да осчетоводяваш, само провери» must never be read as authorising a
   * posting, and no parser this size can reliably tell which verb a Bulgarian negation attaches to.
   */
  public static Stage requested(String message) {
    if (message == null) {
      return null;
    }
    String text = message.toLowerCase(Locale.ROOT);
    if (negated(text)) {
      return null;
    }
    if (text.contains("осчетовод") || text.contains("публикув") || text.contains("ventilat")) {
      return Stage.POSTED;
    }
    if (text.contains("валидир") || text.contains("validat")) {
      return Stage.VALIDATED;
    }
    // Last, so "оправи draft-а и я осчетоводи" resolves to POSTED rather than to the word 'draft'.
    if (text.contains("чернов") || text.contains("draft")) {
      return Stage.DRAFT;
    }
    return null;
  }

  /**
   * True when the user has explicitly handed the choice of record to the assistant.
   *
   * <p>«Вземи случаен запис needs review» was answered with "Нямам право да избера случаен запис
   * без uploadId". The user had just given exactly that right; the assistant asked them to do the
   * one part of the job it was best placed to do.
   *
   * <p>Recorded as a metric and stated in the context block. It does not authorise anything beyond
   * <em>selection</em> — which record to work on, not what may be done to it.
   */
  public static boolean delegatesSelection(String message) {
    if (message == null) {
      return false;
    }
    String text = message.toLowerCase(Locale.ROOT);
    return text.contains("случаен")
        || text.contains("случайн")
        || text.contains("който и да")
        || text.contains("която и да")
        || text.contains("някой от")
        || text.contains("някоя от")
        || text.contains("произволен")
        || text.contains("произволна")
        || text.contains(" random")
        || text.startsWith("random")
        || text.contains("any one")
        || text.contains("pick one")
        || text.contains("whichever");
  }

  /**
   * True when the message reads as a bare "yes" — confirming something, but naming no target of its
   * own for what.
   *
   * <h2>Why this exists separately from {@link #isContinuation}</h2>
   *
   * That method's own javadoc explains why approval words were deliberately kept out of it: an
   * approval must confirm a specific proposed action, and reading one as an ordinary continuation
   * would authorise it against whatever job happened to be on record — which may be a different
   * document entirely. This predicate exists for the one place that now has something narrower and
   * safer to confirm against: a single, structured, tool-derived {@code AiPendingProposal}. It is
   * never, by itself, treated as authorising anything; the caller must additionally hold a stored
   * proposal for the exact user, company and conversation before this message means anything, and
   * even then only the stored proposal's own canonical target is ever frozen into the resulting job
   * — never anything this method or the message itself supplies.
   *
   * <p>Deliberately generous about what counts as "yes" — "дадада", "да, добре", "yes please" — and
   * deliberately silent on anything after it: the safety here comes from requiring a matching
   * stored proposal to exist at all, not from how narrowly this reads the words. A negation is
   * still ruled out first, the same way {@link #requested} rules one out for a lifecycle stage.
   */
  public static boolean isAffirmativeConfirmation(String message) {
    if (message == null) {
      return false;
    }
    String text = message.toLowerCase(Locale.ROOT).trim();
    if (text.isEmpty() || text.startsWith("не") || text.startsWith("no ") || text.equals("no")) {
      return false;
    }
    return text.matches("^(да+[,.!\\s]*)+.*")
        || text.startsWith("ok")
        || text.startsWith("okay")
        || text.startsWith("yes")
        || text.startsWith("sure")
        || text.startsWith("confirm")
        || text.startsWith("потвърд")
        || text.startsWith("съглас");
  }

  /** Every word a bare affirmation may consist of; see {@link #isBareAffirmation}. */
  private static final Set<String> AFFIRMATION_WORDS =
      new HashSet<>(
          Arrays.asList(
              "да",
              "ок",
              "окей",
              "добре",
              "давай",
              "давайте",
              "потвърждавам",
              "потвърди",
              "съгласен",
              "съгласна",
              "направи",
              "направете",
              "го",
              "моля",
              "ok",
              "okay",
              "yes",
              "yep",
              "yeah",
              "sure",
              "do",
              "it",
              "go",
              "ahead",
              "confirm",
              "confirmed",
              "please"));

  /**
   * True when the whole message is an affirmation and nothing else — «да», «ок, направи го», "yes,
   * go ahead" — at most a handful of words, every one of them from a short list.
   *
   * <p>Strict where {@link #isAffirmativeConfirmation} is generous, because it answers a different
   * question: not "does this read as a yes?" (which a matching stored proposal then makes safe) but
   * "did this message state anything of its own?". A message that is only an affirmation names no
   * destination and no change, so it can never be the message that EXPLICITLY authorises a write —
   * {@code AiRouteSelector} caps a router's EXPLICIT at INHERITED for it, whatever the router read.
   * "Да, и осчетоводи" is not bare: it states its own change.
   */
  public static boolean isBareAffirmation(String message) {
    if (message == null) {
      return false;
    }
    String text = message.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}]+", " ").trim();
    if (text.isEmpty()) {
      return false;
    }
    String[] words = text.split(" ");
    if (words.length > 4) {
      return false;
    }
    for (String word : words) {
      if (!AFFIRMATION_WORDS.contains(word) && !word.matches("да+")) {
        return false;
      }
    }
    return true;
  }

  /**
   * True for "carry on with what you were doing" — the turn's own words carry no target.
   *
   * <h2>Approval words do NOT belong here</h2>
   *
   * «Одобрявам» / «потвърждавам» were folded into this once, on the reasoning that an approval
   * carries no destination of its own any more than «продължи» does. That reasoning missed two
   * things a stem cannot tell apart from the same substring: a BARE approval, with no specific
   * proposal in view, is not a decision about anything, and a NEGATED one — «не одобрявам» — still
   * contains the stem and would have inherited exactly like an affirmative one. Authorising a write
   * from either is wrong. What a genuine approval must confirm is a specific proposed action, which
   * this method has no way to identify; that belongs to an explicit correction stated in the
   * current message, or to a structured server-side pending action — see {@link
   * #isAffirmativeConfirmation} and {@code AiPendingProposal} for the latter. Until this message
   * itself names one, approval words are read as ordinary text — this predicate answers only for
   * the continuation words below.
   */
  public static boolean isContinuation(String message) {
    if (message == null) {
      return false;
    }
    String text = message.toLowerCase(Locale.ROOT).trim();
    return text.contains("продълж")
        || text.contains("довърш")
        || text.startsWith("continue")
        || text.startsWith("carry on")
        || text.startsWith("go on")
        || text.startsWith("finish");
  }

  /** True when the record's lifecycle has reached the requested stage or gone beyond it. */
  public static boolean satisfies(String lifecycle, Stage requested) {
    if (lifecycle == null || requested == null) {
      return false;
    }
    return rank(lifecycle) >= requested.rank();
  }

  /**
   * Rank of a lifecycle token. CANCELLED and DRAFT both rank 0: a cancelled invoice has satisfied
   * nothing the user asked for, and reporting it as complete would be the same class of lie.
   */
  public static int rank(String lifecycle) {
    if (POSTED.equals(lifecycle)) {
      return 3;
    }
    if (VALIDATED.equals(lifecycle)) {
      return 2;
    }
    if (DRAFT.equals(lifecycle)) {
      return 1;
    }
    return 0; // CANCELLED, or a token nobody defined: satisfies nothing
  }

  /**
   * The write tools that carry a record to this stage, in the order they must run.
   *
   * <p>Not used to call anything — the model chooses its calls. It exists so the server can say
   * whether a turn executed a prerequisite the user never named: told only "осчетоводи", a turn
   * that also validated did work the user asked for by implication, and that is the behaviour this
   * phase is trying to produce and therefore the behaviour worth measuring.
   */
  public static List<String> prerequisites(Stage stage) {
    if (stage == Stage.POSTED) {
      return Arrays.asList("edit_supplier_invoice", "validate_supplier_invoice");
    }
    if (stage == Stage.VALIDATED) {
      return Collections.singletonList("edit_supplier_invoice");
    }
    return Collections.emptyList();
  }

  /**
   * True when the turn ran a write the user did not name, on the way to the stage they did.
   *
   * <p>Deliberately one-directional: it says a prerequisite ran, never that one was needed and
   * skipped. Whether a step was necessary depends on the record's state at the time, which the
   * metric layer has no business re-deriving.
   */
  public static boolean executedPrerequisite(Stage goal, Collection<String> successfulWrites) {
    if (goal == null || successfulWrites == null || successfulWrites.isEmpty()) {
      return false;
    }
    for (String tool : prerequisites(goal)) {
      if (successfulWrites.contains(tool)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The operation sequence that just succeeded, e.g. {@code EDIT_VALIDATE_VENTILATE}.
   *
   * <p>This is the whole of what «същото и с тази» needs: a shape, never the values it was applied
   * to. Derived from the write tools that actually succeeded, so it describes what happened rather
   * than what was asked for — a workflow whose posting was refused reads as {@code EDIT_VALIDATE},
   * which is the truth and is what should be repeated.
   */
  public static String shape(Collection<String> successfulWriteTools) {
    if (successfulWriteTools == null || successfulWriteTools.isEmpty()) {
      return null;
    }
    Set<String> steps = new LinkedHashSet<>();
    for (String tool : successfulWriteTools) {
      String step = step(tool);
      if (step != null) {
        steps.add(step);
      }
      if (steps.size() >= MAX_SHAPE_STEPS) {
        break;
      }
    }
    return steps.isEmpty() ? null : String.join("_", steps);
  }

  /**
   * Tool name to shape word. Unknown write tools map to null rather than to their own name: a shape
   * is only useful if the model recognises it, and an invented token is noise in the context block.
   */
  private static String step(String tool) {
    if (tool == null) {
      return null;
    }
    switch (tool) {
      case "edit_supplier_invoice":
        return "EDIT";
        // Its own word, not EDIT: it corrects the scanned document's total and changes no part of
        // the invoice, so a turn that did it and then validated must not read as having edited the
        // invoice on the way.
      case "correct_ocr_document_total":
        return "CORRECT_DOCUMENT_TOTAL";
      case "validate_supplier_invoice":
        return "VALIDATE";
      case "refresh_supplier_invoice_terms":
        return "REFRESH_TERMS";
      case "ventilate_supplier_invoice":
        return "VENTILATE";
      case "manage_supplier":
        return "SUPPLIER";
      case "fix_partner_account":
        return "FIX_PARTNER_ACCOUNT";
      case "apply_exact_product_code":
        return "SET_PRODUCT";
      default:
        return null;
    }
  }

  /** Human-readable form of a shape for the context line: {@code EDIT → VALIDATE → VENTILATE}. */
  public static String readable(String shape) {
    if (shape == null || shape.isEmpty()) {
      return null;
    }
    List<String> parts = new ArrayList<>();
    for (String p : shape.split("_(?=EDIT|VALIDATE|REFRESH|VENTILATE|SUPPLIER|FIX|SET)")) {
      if (!p.isEmpty()) {
        parts.add(p);
      }
    }
    return String.join(" → ", parts);
  }

  private static boolean negated(String text) {
    return text.contains("недей")
        || text.contains("не осчетовод")
        || text.contains("не валидир")
        || text.contains("без да осчетовод")
        || text.contains("don't")
        || text.contains("do not")
        || text.contains("without posting");
  }
}
