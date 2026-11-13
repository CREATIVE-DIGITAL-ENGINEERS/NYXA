package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.context.AiPageContextRegistry;
import com.axelor.apps.aiaccountant.prompt.AiAccountantMessages;
import com.axelor.apps.aiaccountant.security.AiWriteEffects;
import com.axelor.apps.aiaccountant.tool.AiTurnToolLog;
import com.axelor.inject.Beans;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stops the assistant reporting work that did not happen — and, equally, reporting finished work as
 * unfinished.
 *
 * <h2>The two failures this exists for</h2>
 *
 * <ol>
 *   <li><b>Claiming work that never ran.</b> A three-invoice workflow hit the iteration cap after
 *       two edits. The third call never happened — and the closing sentence said all three were
 *       fixed. The model was summarising its own intent; nothing downstream could tell the
 *       difference, because the reply was accepted verbatim whatever the tools had actually done.
 *   <li><b>Calling finished work unfinished.</b> A turn asked to correct, validate and post invoice
 *       606 ran out of iterations with the invoice already POSTED, and the server answered "the
 *       turn hit its limit before the task was finished… say continue". Reaching the iteration cap
 *       had been treated as proof that the workflow was incomplete. It proves only that the model
 *       wanted another round trip.
 * </ol>
 *
 * <p>Both are answered from what the server saw happen — {@link AiTurnToolLog}, {@code
 * AiWriteEffects} and the records' own lifecycle — never from what the model says happened.
 *
 * <h2>Deliberate restraint</h2>
 *
 * A normal successful answer is never touched. The false-success guard fires only when the reply
 * claims <em>this turn</em> performed a write <em>and</em> the ledger shows no successful write. It
 * appends rather than rewrites, so the assistant's own wording survives and the user sees both.
 *
 * <p>The patterns are first-person perfective verbs and nothing else, because that is the only form
 * a claim about this turn takes. «Осчетоводих я» is a claim; «фактурата е осчетоводена» is a
 * statement about a record, and a read-only confirmation of existing state is a completely
 * legitimate answer to a completely legitimate question. Flagging the second is not a stricter
 * guard — it is a wrong one, and it fired on a user who had explicitly asked for nothing but a
 * check.
 */
public final class AiTurnTruthCheck {

  /** Enough to describe a "next three" workflow without turning the notice into a report. */
  private static final int MAX_STATES = 6;

  /**
   * Claims that THIS TURN performed a write.
   *
   * <p>Bulgarian first-person aorist ({@code -их/-ах}) and first-person-plural ({@code
   * -ихме/-ахме}) only, plus the English {@code I …} forms. Every one of them has a grammatical
   * subject that is the assistant, in a completed past tense.
   *
   * <p>What was deliberately REMOVED, and why: {@code е осчетоводена}, {@code е валидирана}, {@code
   * е създаден}, {@code са осчетоводени}, {@code has been posted} are third-person statements about
   * a record's state — true or false independently of who did it and of when. {@code успешно} and
   * {@code successfully} are adverbs that appear in both. {@code готово е} is an assessment. All of
   * them fired on «Да — фактурата вече е осчетоводена», which is the correct answer to «само
   * потвърди дали е така» and which the guard then contradicted with a warning that nothing had
   * been saved.
   *
   * <p>{@code (?<!не )} is kept: «не съм я осчетоводил» is a denial, and flagging it would produce
   * a correction that contradicts a correct sentence.
   */
  private static final Pattern CLAIMED_WRITE =
      Pattern.compile(
          "(?iu)(?<!не )\\b("
              // Bulgarian, 1st person singular/plural aorist: I posted / validated / corrected /
              // created / changed / updated / added / saved / attached / deleted
              + "осчетоводих|осчетоводихме|валидирах|валидирахме|коригирах|коригирахме"
              + "|поправих|поправихме|създадох|създадохме|промених|променихме"
              + "|обнових|обновихме|добавих|добавихме|запазих|запазихме"
              + "|прикачих|прикачихме|изтрих|изтрихме|зададох|зададохме"
              // English, explicitly first person
              + "|i (?:have |just )?(?:posted|ventilated|validated|corrected|created|changed"
              + "|updated|edited|fixed|saved|set|attached|deleted)"
              + ")");

  /**
   * First-person promises to keep working after the response ends.
   *
   * <p>There is no background execution and no way for the assistant to send a second message, so
   * every one of these is a statement about a system that does not exist. The user is left waiting
   * for a reply that can never arrive, and the work is never done.
   *
   * <p>Matched narrowly, on first-person continuation and reporting only. Not «ще го направя» — an
   * offer to act on confirmation is exactly what rule 3 asks for, and flagging it would push the
   * model into never offering anything. Not «ще мога да го обработя», which is a statement of
   * capability. Conditional sentences are excluded outright by {@link #promisesFutureWork}.
   */
  private static final Pattern FUTURE_WORK =
      Pattern.compile(
          "(?iu)("
              // Bulgarian: I'm starting / I'm processing them now / I will process
              + "започвам|започваме|ще започна|ще започнем"
              + "|обработвам|обработваме|ще обработя|ще обработим"
              + "|ще стартирам|пускам ги сега"
              // …and will come back to you. The pronoun is OPTIONAL: «ще докладвам» is the same
              // promise as «ще ви докладвам», and requiring it is how the real failure got through.
              + "|ще (?:(?:ви|ти|Ви|ни|Ви)\\s+)?"
              + "(?:кажа|кажем|съобщя|съобщим|уведомя|уведомим|информирам|информираме"
              + "|докладвам|докладваме|дам знак|дадем знак)"
              + "|ще върна (?:резултат|отговор|обобщение)|ще върнем (?:резултат|отговор|обобщение)"
              + "|(?:щом|когато|след като) (?:приключа|приключим|завърша|завършим|съм готов)"
              + "|ще продължа|ще продължим|продължавам да|продължаваме да"
              // English
              + "|i'?ll (?:process|handle|start|run|report|get back|let you know|update you|come"
              + " back|continue)"
              + "|i will (?:process|handle|start|run|report|get back|let you know|update you|come"
              + " back|continue)"
              + "|i'?m (?:processing|working on it|starting)|i am (?:processing|starting)"
              + "|starting now|let me start"
              + "|(?:once|when) i'?m done|(?:once|when) i am done|after i finish"
              + ")");

  /**
   * A promise aimed at the records that are left, in whatever words.
   *
   * <p>The backstop for the enumeration above, which is a list of phrasings and therefore a list of
   * the phrasings somebody thought of. The real reply that got through said «обработвам останалите
   * и после ще докладвам»: {@code обработвам} was only matched with an adverb attached, and {@code
   * ще докладвам} was only matched with a pronoun attached, so a perfectly ordinary sentence
   * promising a perfectly impossible thing matched nothing at all.
   *
   * <p>This matches on shape instead: a mention of the REST of the set together with a first-person
   * verb of continuing or reporting. It is what a fake-background promise is actually made of, and
   * it does not depend on guessing the wording.
   */
  private static final Pattern REMAINING_SET =
      Pattern.compile(
          "(?iu)(останал\\p{L}*|остатък\\p{L}*|другите|следващите|the rest|the others|remaining)");

  private static final Pattern FIRST_PERSON_WORK =
      Pattern.compile(
          "(?iu)\\b(обработвам|обработваме|обработя|обработим|продължавам|продължаваме|продължа"
              + "|довърша|довършим|завърша|завършим|изпълнявам|изпълня|правя|подготвям"
              + "|докладвам|докладваме|кажа|съобщя|уведомя"
              + "|process|processing|handle|handling|continue|continuing|finish|finishing"
              + "|report|reporting)\\b");

  /**
   * A sentence that only describes what WOULD happen is not a promise about this turn.
   *
   * <h2>What was removed, and why it was a hole</h2>
   *
   * It also listed «кажете», «кажи», «потвърдете», {@code tell me}, {@code let me know}, {@code say
   * "continue} and {@code confirm}. Those are imperatives addressed to the user, not conditionals,
   * and every one of them exempted its whole sentence from the promise check — so «Потвърдете и
   * обработвам останалите сега» passed, and so did anything else that mentioned confirming. A guard
   * that switches off when you use a common word is not a guard.
   *
   * <p>What remains are subordinating conjunctions only: a clause introduced by «ако» or "if"
   * genuinely describes a hypothetical, and reading it as a promise would be the wrong answer
   * rather than a lenient one.
   *
   * <p>The reason those imperatives were here at all was to protect ONE thing — this server's own
   * truncation notice, «Кажете „продължи“ и ще довърша останалото», which is honest and must not be
   * deleted. That is now handled by {@link #isServerNotice(String)}, which recognises the server's
   * own sentences by identity instead of by vocabulary.
   */
  private static final Pattern CONDITIONAL =
      Pattern.compile(
          "(?iu)(\\bако\\b|\\bif you\\b|\\bif the\\b|\\bпри условие\\b|\\bв случай\\b)");

  /**
   * The fixed sentences this server writes itself.
   *
   * <p>Several of them promise to carry on — «и ще довърша останалото», «and I will finish the
   * rest» — and they are the one case where that is true, because the user saying «продължи» really
   * does resume the job. They are exempt because they are <em>ours</em>, matched against the exact
   * constants that produced them, and not because of any word they contain. A model that writes the
   * same sentence itself is making the same promise the server can keep, so an exact match is a
   * safe thing to let through; anything it invents is not.
   */
  private static final String[] SERVER_NOTICES = {
    AiAccountantMessages.TRUNCATED_UNKNOWN_BG,
    AiAccountantMessages.TRUNCATED_UNKNOWN_EN,
    AiAccountantMessages.TRUNCATED_CONTINUE_BG,
    AiAccountantMessages.TRUNCATED_CONTINUE_EN,
    AiAccountantMessages.NAME_THE_RECORD_BG,
    AiAccountantMessages.FALSE_SUCCESS_BG,
    AiAccountantMessages.FALSE_SUCCESS_EN,
    AiAccountantMessages.UNCERTAIN_WRITE_BG,
    AiAccountantMessages.UNCERTAIN_WRITE_EN
  };

  private AiTurnTruthCheck() {}

  /** What the server can prove about the records this turn wrote. */
  public static final class TurnState {

    private final List<String> states;
    private final boolean complete;

    TurnState(List<String> states, boolean complete) {
      this.states = states;
      this.complete = complete;
    }

    /** One short sentence per written record, e.g. {@code invoice 606 is POSTED}. Never null. */
    public List<String> getStates() {
      return states;
    }

    /** True only when every written record has provably reached the authorised outcome. */
    public boolean isComplete() {
      return complete;
    }
  }

  /**
   * Reads the current state of everything this turn wrote and decides whether the authorised
   * outcome has been reached.
   *
   * <h2>The rule</h2>
   *
   * Complete requires all of: an outcome the server could identify from the user's own words; at
   * least one written record whose lifecycle a resolver can name; every one of those records at or
   * beyond that outcome; and no failed or skipped call left unrecovered. Fail any of them and the
   * answer is "cannot prove", which is not the same as "unfinished" but is treated the same way,
   * because the honest message for both is the one that claims nothing.
   *
   * <p>Note what is <em>not</em> in the rule: how many tool calls ran, how many succeeded, and
   * whether the iteration cap was reached. None of those is a statement about an invoice.
   */
  public static TurnState assess(AiWorkflowIntent.Stage requested) {
    List<String> states = new ArrayList<>();
    boolean allSatisfied = true;
    boolean anyStaged = false;
    try {
      AiPageContextRegistry registry = Beans.get(AiPageContextRegistry.class);
      Set<String> seen = new LinkedHashSet<>();
      for (AiWriteEffects.Ref ref : AiWriteEffects.snapshot()) {
        String prose = registry.state(ref.getModel(), ref.getId());
        if (prose != null && seen.add(prose) && states.size() < MAX_STATES) {
          states.add(prose);
        }
        String lifecycle = registry.lifecycle(ref.getModel(), ref.getId());
        if (lifecycle == null) {
          // Not every written record has a lifecycle — a Partner does not. It is simply not
          // evidence either way, so it neither proves nor disproves completion.
          continue;
        }
        anyStaged = true;
        allSatisfied &= AiWorkflowIntent.satisfies(lifecycle, requested);
      }
    } catch (RuntimeException e) {
      // A message that cannot be built must not become a failed turn on top of an already
      // truncated one. Claiming nothing is always available.
      return new TurnState(new ArrayList<>(), false);
    }
    boolean complete =
        requested != null && anyStaged && allSatisfied && !AiTurnToolLog.hasUnrecoveredFailure();
    return new TurnState(states, complete);
  }

  /**
   * The reply for a turn that reached the iteration cap.
   *
   * <h2>Why it no longer counts tool calls</h2>
   *
   * It used to say "N of M requested operations completed", counting entries in the tool ledger.
   * That produced "5 of 5 operations succeeded" attached to a workflow that had plainly not
   * finished, because a tool call is not a business operation: five successful calls can leave
   * three invoices in three different states, and the user asked about invoices.
   *
   * <p>So it reports what the database says about the records this turn wrote. Where the authorised
   * outcome has already been reached it says so and asks for nothing — telling someone to say
   * "continue" about an invoice that is already in the books is a contradiction, not a courtesy.
   * Where nothing can be established it falls back to a message that claims nothing at all. Under
   * no circumstances does the count of successful calls appear as evidence that the request was
   * carried out.
   */
  public static String truncatedReply(String modelText, TurnState state) {
    boolean bulgarian = isBulgarian(modelText);
    if (state.isComplete()) {
      return completedNotice(state, bulgarian, modelText);
    }
    String notice = truncationNotice(bulgarian, state);
    if (modelText == null || modelText.trim().isEmpty()) {
      return notice;
    }
    if (claimsUnsupportedWrite(modelText)) {
      // Its summary asserts completed work the ledger does not show. Keeping it under the notice
      // would leave the user with two contradictory sentences and no way to tell which is
      // authoritative, so only the provable one survives.
      return notice;
    }
    return notice + "\n\n" + modelText.trim();
  }

  /**
   * The work is done; the turn merely ran out of room to talk about it.
   *
   * <p>The model's own closing text is kept when it claims nothing unsupported, because after a
   * successful workflow it usually carries the useful part — the move number, the journal, what it
   * corrected. The state line goes first: it is the part the server can prove.
   */
  static String completedNotice(TurnState state, boolean bulgarian, String modelText) {
    StringBuilder sb = new StringBuilder();
    sb.append(bulgarian ? AiAccountantMessages.COMPLETED_BG : AiAccountantMessages.COMPLETED_EN);
    sb.append(String.join("; ", state.getStates())).append('.');
    if (modelText != null && !modelText.trim().isEmpty() && !claimsUnsupportedWrite(modelText)) {
      sb.append("\n\n").append(modelText.trim());
    }
    return sb.toString();
  }

  /** The line the server states itself when a turn was cut short with work outstanding. */
  public static String truncationNotice(boolean bulgarian, TurnState state) {
    List<String> states = state == null ? new ArrayList<>() : state.getStates();
    StringBuilder sb = new StringBuilder();
    if (bulgarian) {
      sb.append(AiAccountantMessages.TRUNCATED_BG);
      if (states.isEmpty()) {
        sb.append(AiAccountantMessages.TRUNCATED_UNKNOWN_BG);
      } else {
        sb.append(AiAccountantMessages.TRUNCATED_STATE_BG)
            .append(String.join("; ", states))
            .append(AiAccountantMessages.TRUNCATED_CONTINUE_BG);
      }
    } else {
      sb.append(AiAccountantMessages.TRUNCATED_EN);
      if (states.isEmpty()) {
        sb.append(AiAccountantMessages.TRUNCATED_UNKNOWN_EN);
      } else {
        sb.append(AiAccountantMessages.TRUNCATED_STATE_EN)
            .append(String.join("; ", states))
            .append(AiAccountantMessages.TRUNCATED_CONTINUE_EN);
      }
    }
    return sb.toString();
  }

  /** As above, for the path where even the settling request failed and no state was assessed. */
  public static String truncationNotice(boolean bulgarian) {
    return truncationNotice(bulgarian, assess(null));
  }

  /**
   * A correction to append to an ordinary reply, or null when the reply is fine.
   *
   * <p>Null in every normal case: a turn that wrote something, a reply that claims nothing, and —
   * the case this was narrowed for — a reply that states what a record's status already is.
   */
  public static String falseSuccessCorrection(String modelText) {
    if (!claimsUnsupportedWrite(modelText)) {
      return null;
    }
    return isBulgarian(modelText)
        ? AiAccountantMessages.FALSE_SUCCESS_BG
        : AiAccountantMessages.FALSE_SUCCESS_EN;
  }

  /**
   * A first-person claim to have written something, with no successful write to support it.
   *
   * <h2>Which half decides what</h2>
   *
   * Whether a write happened is decided <em>only</em> by {@link AiTurnToolLog#noSuccessfulWrite()},
   * which counts entries the dispatcher wrote as each call returned. The pattern never establishes
   * that anything was or was not saved; it selects, among turns already proven to have written
   * nothing, the ones whose wording would mislead. Reverse that order and the guard would be
   * deciding a fact about the database by reading a sentence about it.
   *
   * <p>The consequence is worth stating plainly: this returns false for every turn where any write
   * succeeded, even one claiming five writes when one landed. Proving <em>which</em> claim is
   * unsupported would mean matching prose to records, which is the thing being avoided. The
   * per-record answer comes from the workflow status line instead, which is built from state and
   * goes out whether or not the reply sounded wrong.
   */
  public static boolean claimsUnsupportedWrite(String modelText) {
    if (modelText == null || modelText.trim().isEmpty()) {
      return false;
    }
    if (!AiTurnToolLog.noSuccessfulWrite()) {
      return false; // something really was written; the claim has support
    }
    return CLAIMED_WRITE.matcher(modelText).find();
  }

  /**
   * True when the answer promises work that can never happen, and no write actually landed.
   *
   * <h2>Both halves are required</h2>
   *
   * <p><b>The promise.</b> Matched sentence by sentence, and a sentence containing a conditional
   * marker is skipped: «ако по-късно качите документа, ще ви кажа какво липсва» describes what
   * would happen if the user does something, which is a perfectly good sentence. «Започвам веднага
   * и ще върна резултат щом приключа» is not.
   *
   * <p><b>No write landed.</b> Gated on {@link AiTurnToolLog#noSuccessfulWrite()} — successful
   * <em>writes</em>, not successful calls. A read must not suppress this, and gating on any
   * successful call is how the real failure got through: «Прочетох записите, започвам да ги
   * обработвам и ще върна резултат» reads the records first and then announces work it never does.
   * Reading is what the model does <em>before</em> the promise, so counting it as work done disarms
   * the guard in precisely the case it was written for. A read changes nothing; a turn that only
   * read has not done the work, whatever it says about starting.
   *
   * <p>Re-prompting after reads is safe for the same reason — reads are idempotent, so a correction
   * round can only cost a model request. A turn where a write DID land is left alone: re-running
   * the model there risks repeating a write that already committed, which is a worse outcome than
   * clumsy phrasing. When a batch was aborted part-way, accuracy comes from rule 22 instead, which
   * makes the model report completed, failed and not-executed separately.
   */
  public static boolean promisesFutureWork(String modelText) {
    if (modelText == null || modelText.trim().isEmpty()) {
      return false;
    }
    if (!AiTurnToolLog.noSuccessfulWrite()) {
      return false; // a write really landed; correcting it risks repeating the write
    }
    for (String sentence : modelText.split("(?<=[.!?\\n])")) {
      if (isFutureWorkPromise(sentence)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The same reply with every promise of later work removed, sentence by sentence.
   *
   * <h2>Why removing beats appending</h2>
   *
   * Appending the true state under «обработвам останалите и ще докладвам» leaves the user holding
   * two contradictory sentences and no way to tell which is authoritative — and the promise is the
   * one written in the assistant's own confident voice. Worse, it is a promise about a system that
   * does not exist: the turn ends when this method returns, so the message they are waiting for can
   * never arrive. A sentence that can only mislead has no claim to survive.
   *
   * <p>Only the offending sentences go. The rest of the reply usually carries the part worth
   * keeping — which invoice was posted, which move number, what the blocker was — and deleting that
   * to punish one clause would cost the user more than the clause did.
   *
   * <p>Deliberately not gated on the tool ledger: the caller has already established that no write
   * landed. Kept pure so it can be tested against text alone.
   *
   * @return the surviving text, trimmed; empty when every sentence was a promise
   */
  public static String withoutFutureWorkPromises(String modelText) {
    if (modelText == null || modelText.trim().isEmpty()) {
      return "";
    }
    StringBuilder kept = new StringBuilder();
    for (String sentence : modelText.split("(?<=[.!?\\n])")) {
      if (isFutureWorkPromise(sentence)) {
        continue;
      }
      kept.append(sentence);
    }
    return kept.toString().trim();
  }

  /**
   * One sentence, judged on its own — the shared rule behind {@link #promisesFutureWork} and {@link
   * #withoutFutureWorkPromises}, so detection and removal can never disagree about what a promise
   * is.
   */
  private static boolean isFutureWorkPromise(String sentence) {
    if (sentence.trim().isEmpty()
        || isServerNotice(sentence)
        || CONDITIONAL.matcher(sentence).find()) {
      return false;
    }
    if (FUTURE_WORK.matcher(sentence).find()) {
      return true;
    }
    // The shape check: "…the rest…" plus a first-person verb of working or reporting. Catches the
    // phrasings the list above does not happen to contain.
    return REMAINING_SET.matcher(sentence).find() && FIRST_PERSON_WORK.matcher(sentence).find();
  }

  /**
   * True when this sentence came out of {@link AiAccountantMessages} rather than out of the model.
   *
   * <p>Deterministic by construction: the sentence has to appear verbatim inside one of the
   * server's own constants. It replaces the vocabulary exemption that used to protect these
   * notices, which protected far more than them.
   *
   * <p>The sentence splitter cuts after {@code .!?\n}, so a notice arrives here in pieces; each
   * piece is still a substring of the whole. Short fragments are ignored — a bare «Готово.» is a
   * substring of half the constants and proves nothing about who wrote it.
   */
  static boolean isServerNotice(String sentence) {
    String trimmed = sentence == null ? "" : sentence.trim();
    if (trimmed.length() < MIN_NOTICE_FRAGMENT) {
      return false;
    }
    for (String notice : SERVER_NOTICES) {
      if (notice.contains(trimmed)) {
        return true;
      }
    }
    return false;
  }

  /** Below this, a fragment is too generic to identify anything. */
  private static final int MIN_NOTICE_FRAGMENT = 12;

  /** Unfinished call names, for the log line only — never shown to the user. */
  public static List<String> unfinished() {
    return AiTurnToolLog.unfinished();
  }

  /** Cyrillic anywhere is enough: the assistant answers in the language it was written to. */
  static boolean isBulgarian(String text) {
    if (text == null) {
      return true; // the deployment's language, and the safer default for an operator-facing notice
    }
    for (int i = 0; i < text.length(); i++) {
      if (Character.UnicodeBlock.of(text.charAt(i)) == Character.UnicodeBlock.CYRILLIC) {
        return true;
      }
    }
    return false;
  }
}
