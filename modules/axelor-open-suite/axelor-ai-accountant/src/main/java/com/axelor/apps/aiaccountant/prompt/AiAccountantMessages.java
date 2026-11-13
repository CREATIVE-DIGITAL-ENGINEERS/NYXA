package com.axelor.apps.aiaccountant.prompt;

/**
 * The short fixed texts a turn can emit — to the model, or to the user when the model cannot.
 *
 * <h2>Why they are collected here</h2>
 *
 * They were scattered through the turn orchestrator as inline literals and private constants: an
 * internal instruction to the model in one method, a Bulgarian sentence shown to an accountant in
 * another, three more built with a {@code StringBuilder} in a third. Reviewing the assistant's
 * wording meant reading the tool loop, and changing a sentence meant touching the class that drives
 * the Responses API.
 *
 * <p>Two audiences live here, deliberately labelled, because they have opposite rules. Text sent to
 * the MODEL may talk about tools, budgets and responses. Text shown to the USER may not: they are
 * this service's internals and mean nothing to an accountant, who wants to know what happened to
 * their invoice.
 *
 * <p>Nothing here is templated from record data. Anything that names an invoice, a supplier or an
 * amount is built where that data is known and proven, never from a format string here.
 */
public final class AiAccountantMessages {

  private AiAccountantMessages() {}

  // ── to the model ──────────────────────────────────────────────────────────

  /**
   * Sent with the last batch of tool results when the iteration cap is reached.
   *
   * <p>It has to forbid mentioning the limit as explicitly as it announces it: told only that the
   * budget is gone, the model explains the budget to the user.
   */
  public static final String TOOL_BUDGET_EXHAUSTED =
      "The tool budget for this turn is now exhausted. Do not request any further tools. Answer"
          + " directly from what the tool results already gave you, and say plainly what you could"
          + " not determine. NEVER mention tools, a budget, a limit, iterations or anything else"
          + " about how you work — that is internal and means nothing to the user. Just answer, or"
          + " ask for the one thing you still need.";

  /**
   * Sent back when the model announced work instead of doing it.
   *
   * <p>The architecture is synchronous: one user message, one tool loop, one answer. There is no
   * worker that runs on afterwards and no way for the assistant to send a second message. So
   * "Започвам веднага… ще върна резултат щом приключа" is not optimism, it is a statement about a
   * system that does not exist, and the user waits for a reply that can never arrive.
   *
   * <p>Phrased as a fact about the runtime rather than a reprimand, and it names both acceptable
   * exits so the model is not pushed into inventing a third.
   */
  public static final String NO_BACKGROUND_WORK =
      "You cannot do any work after this response: there is no background execution and you cannot"
          + " send a later message. Nothing you promised will happen. Do it NOW with the tools in"
          + " this same turn, or state the concrete blocker that stops you and ask only for the one"
          + " thing you need. Do not say you are starting, processing, monitoring, continuing, or"
          + " will report back.";

  // ── to the user ───────────────────────────────────────────────────────────

  /** Prefix of every truncation notice. What follows it is either proven state or nothing. */
  public static final String TRUNCATED_BG =
      "⚠️ Текущият AI ход достигна лимита, преди задачата да бъде завършена.";

  public static final String TRUNCATED_EN =
      "⚠️ This AI turn hit its limit before the task was finished.";

  /**
   * Used when the server cannot establish what state the affected records are in.
   *
   * <p>Claims nothing at all on purpose. The alternative — naming operations that "may" have run —
   * is the failure this whole path exists to prevent.
   */
  public static final String TRUNCATED_UNKNOWN_BG =
      " Част от действията може да са изпълнени. Кажете „продължи“ и ще продължа от текущото"
          + " състояние в ERP.";

  public static final String TRUNCATED_UNKNOWN_EN =
      " Some actions may have been applied. Say \"continue\" and I will carry on from the current"
          + " ERP state.";

  public static final String TRUNCATED_STATE_BG = " Текущо състояние на засегнатите записи: ";

  public static final String TRUNCATED_STATE_EN = " Current state of the affected records: ";

  public static final String TRUNCATED_CONTINUE_BG = ". Кажете „продължи“ и ще довърша останалото.";

  public static final String TRUNCATED_CONTINUE_EN =
      ". Say \"continue\" and I will finish the rest.";

  /**
   * The turn ran out of iterations, but the records are already in the state that was asked for.
   *
   * <p>Deliberately says nothing about a limit and asks for nothing: from the accountant's side the
   * work is done, and "say continue" against a posted invoice is a contradiction, not a courtesy.
   */
  public static final String COMPLETED_BG = "✅ Готово. Текущо състояние: ";

  public static final String COMPLETED_EN = "✅ Done. Current state: ";

  /** Appended when even the settling request failed, so nothing but the record state is known. */
  public static final String NAME_THE_RECORD_BG =
      "\n\nПосочете ми точния запис (номер или име на файла) и ще довърша.";

  /** The reply claimed a write this turn did not make. Appended, never substituted. */
  public static final String FALSE_SUCCESS_BG =
      "⚠️ Забележка от системата: в този ход не беше извършена нито една успешна промяна по"
          + " записите. Каквото е описано като направено по-горе, НЕ е записано — проверете записа"
          + " и ми кажете да го изпълня.";

  public static final String FALSE_SUCCESS_EN =
      "⚠️ Server note: no change was successfully applied to any record in this turn. Anything"
          + " described above as done was NOT saved — check the record and tell me to run it.";

  /**
   * A write ended without a knowable outcome.
   *
   * <p>The wording is careful in a way the others do not have to be. Every other notice states
   * something — this one states the absence of a fact, and it must not drift toward either
   * reassurance ("probably fine") or alarm ("it failed"), because both are claims nobody is in a
   * position to make. It says what happened to the connection, what is therefore unknown, and the
   * one action that settles it: open the record.
   *
   * <p>Stated by the server rather than left to the model, for the same reason the workflow status
   * line is: whether the user hears it must not depend on which words the model chose.
   */
  public static final String UNCERTAIN_WRITE_BG =
      "⚠️ Забележка от системата: връзката беше прекъсната по време на промяна и НЕ е известно дали"
          + " тя е записана. Не я повтарям, защото това може да я приложи два пъти. Отворете и"
          + " проверете следното, преди да продължим: ";

  public static final String UNCERTAIN_WRITE_EN =
      "⚠️ Server note: the connection was lost during a change and it is NOT known whether it was"
          + " saved. I am not repeating it, because that could apply it twice. Please open and check"
          + " the following before we continue: ";

  /**
   * Shown instead of running the turn, when the request proved no company context at all.
   *
   * <p>Almost always a browser running a cached bundle from before this check existed. Telling that
   * user to send the instruction again would loop forever — the old bundle cannot state a context
   * on the next attempt either — so this asks for the one action that actually fixes it: reload the
   * page and get the current chat.
   */
  public static final String COMPANY_CONTEXT_MISSING_EN =
      "This chat is out of date and could not confirm which company it was showing, so nothing was"
          + " done. Please reload the page and try again.";

  /**
   * Shown instead of running the turn, when the active company changed under the panel.
   *
   * <p>States the two things the user needs: nothing happened, and why. It deliberately does not
   * offer to run the instruction in the new company — the words were written while reading another
   * company's conversation, and re-sending them is a decision only the person who wrote them can
   * make.
   */
  public static final String COMPANY_CHANGED_EN =
      "The active company changed while this chat was open, so nothing was done. This conversation"
          + " has been reloaded for the company you are in now. Please send your instruction again"
          + " if you still want it.";

  /**
   * The tool registry failed validation, so no turn is run — the model is never called. The
   * technical detail (which tool names clash, in which modules) is in the server log only.
   */
  public static final String TOOL_REGISTRY_INVALID_EN =
      "The AI Accountant is unavailable because its tool configuration is invalid, so nothing was"
          + " done and nothing has been changed in the books. Please contact your administrator.";

  /**
   * Shown instead of running the turn, when the user's own message could not be stored.
   *
   * <p>Nothing was executed — that is the whole point of storing the message first — so this says
   * so plainly and asks for the one thing that helps: try again. It names no table and no
   * exception, which mean nothing to an accountant.
   */
  public static final String TRANSCRIPT_UNAVAILABLE_EN =
      "The conversation could not be saved, so nothing was done. Nothing has been changed in the"
          + " books. Please try again; if it keeps happening, contact your administrator.";

  /**
   * Appended to an answer that was produced but could not be stored.
   *
   * <p>The work is real and may have reached the books, so the answer is kept; what the user has to
   * know is that this exchange will not be here after a refresh, while the change it describes
   * will.
   */
  public static final String TRANSCRIPT_NOT_SAVED_BG =
      "⚠️ Забележка от системата: този отговор не можа да бъде запазен и няма да се вижда в чата"
          + " след презареждане. Всичко, което е било записано в счетоводството, остава записано.";

  public static final String TRANSCRIPT_NOT_SAVED_EN =
      "⚠️ Server note: this answer could not be saved and will not be in the chat after a refresh."
          + " Anything that was written to the books stays written.";
}
