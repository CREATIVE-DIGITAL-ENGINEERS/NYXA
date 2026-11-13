package com.axelor.apps.aiaccountant.service;

/**
 * The visible transcript could not be written to the database.
 *
 * <h2>Why this is an exception and not a {@code false}</h2>
 *
 * Its neighbours — {@link AiTurnMemoryStore}, {@link AiChainBudgetStore} — are deliberately
 * best-effort: what they keep is routing context and a token measurement, and losing either costs a
 * colder reading of the next turn, never anything the user can see. The transcript is the opposite.
 * It is the only record of the conversation there is, and a store that returned {@code false} here
 * would mean the panel showed an answer, told the user nothing, and lost it at the next refresh.
 *
 * <p>So this is thrown, and the two call sites treat it as the different things it means at each:
 * before the turn runs, it aborts the turn, because nothing should execute against a transcript
 * that cannot record it; after the turn has run and written to the books, it cannot abort anything,
 * so the reply carries an explicit notice that this answer was not saved. Neither path is silent.
 */
public class AiTranscriptPersistenceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AiTranscriptPersistenceException(String message, Throwable cause) {
    super(message, cause);
  }
}
