package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Авансът, приспаднат с ред, не се приспада втори път като плащане: О.Д. на AOS (Дт 412 / Кт 411)
 * не вижда плащанията му.
 */
class BgMoveExcessPaymentServiceTest {

  private static MoveLine line(Move move) {
    MoveLine line = new MoveLine();
    line.setMove(move);
    return line;
  }

  @Test
  @DisplayName("Плащането на приспаднатия аванс изпада, другото надплатено остава")
  void приспаднатият_изпада() {
    Move deductedPayment = new Move();
    Move otherPayment = new Move();
    MoveLine deducted = line(deductedPayment);
    MoveLine other = line(otherPayment);

    assertEquals(
        List.of(other),
        BgMoveExcessPaymentService.withoutDeducted(
            List.of(deducted, other), Set.of(deductedPayment)));
    assertEquals(
        List.of(deducted, other),
        BgMoveExcessPaymentService.withoutDeducted(List.of(deducted, other), Set.of()));
  }
}
