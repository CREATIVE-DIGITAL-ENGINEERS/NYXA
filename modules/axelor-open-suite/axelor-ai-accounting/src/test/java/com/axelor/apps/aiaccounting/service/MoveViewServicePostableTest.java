package com.axelor.apps.aiaccounting.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code get_move}'s {@code postable} must never read true for an unbalanced entry.
 *
 * <p>What it guards: a status-only {@code postable} (NEW/DAYBOOK/SIMULATED) would return {@code
 * postable: true} for an unbalanced NEW entry in the very same payload as {@code balance.balanced:
 * false}. A caller keying on it by analogy with the invoice tools' {@code postReady} could then
 * treat an entry that cannot post as ready. So {@code postable} is balance-aware.
 *
 * <p>Pure — no Guice, no JPA. {@code detail()} reaches outside only through {@link
 * MoveViewService#summarise}, {@link MoveViewService#totalDebit} and {@link
 * MoveViewService#totalCredit}; all three are overridden here so the projection can be exercised on
 * a bare {@code new Move()}.
 */
class MoveViewServicePostableTest {

  /** A view whose totals are fixed, so the balance is whatever the test wants it to be. */
  private static MoveViewService view(BigDecimal debit, BigDecimal credit) {
    return new MoveViewService() {
      @Override
      public Map<String, Object> summarise(Move move) {
        return new LinkedHashMap<>();
      }

      @Override
      public BigDecimal totalDebit(Move move) {
        return debit;
      }

      @Override
      public BigDecimal totalCredit(Move move) {
        return credit;
      }
    };
  }

  @SuppressWarnings("unchecked")
  @Test
  void anUnbalancedNewEntryIsNotReportedAsPostable() {
    Move move = new Move();
    move.setStatusSelect(MoveRepository.STATUS_NEW);

    Map<String, Object> detail =
        view(new BigDecimal("100.00"), new BigDecimal("40.00")).detail(move);

    Map<String, Object> balance = (Map<String, Object>) detail.get("balance");
    assertEquals(Boolean.FALSE, balance.get("balanced"), "the fixture is deliberately unbalanced");
    // Must not read TRUE beside balanced=false.
    assertEquals(
        Boolean.FALSE,
        detail.get("postable"),
        "an unbalanced entry must never be reported as postable: " + detail);
    // The status fact is still available; it is just no longer conflated with "ready to post".
    assertEquals(Boolean.TRUE, detail.get("editable"), "a NEW entry is still editable");
  }

  @Test
  void aBalancedNewEntryIsStillPostable() {
    Move move = new Move();
    move.setStatusSelect(MoveRepository.STATUS_NEW);

    Map<String, Object> detail =
        view(new BigDecimal("100.00"), new BigDecimal("100.00")).detail(move);

    assertEquals(Boolean.TRUE, detail.get("postable"), "a balanced NEW entry remains postable");
  }

  @Test
  void anAccountedEntryIsNotPostableWhateverItsBalance() {
    Move move = new Move();
    move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);

    Map<String, Object> detail =
        view(new BigDecimal("100.00"), new BigDecimal("100.00")).detail(move);

    assertEquals(
        Boolean.FALSE,
        detail.get("postable"),
        "a status that does not allow the Post button is never postable, balanced or not");
  }
}
