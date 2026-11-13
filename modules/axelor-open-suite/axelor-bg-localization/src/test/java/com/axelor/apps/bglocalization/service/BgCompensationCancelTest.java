package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.bglocalization.db.BgCompensation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОТКАЗЪТ НА ОБЕЗЩЕТЕНИЕ ИСКА ПРИЧИНА, И ТЯ ИМА ОТКЪДЕ ДА ДОЙДЕ.
 *
 * <p>Същата задънена улица като при фиша: {@code BgCompensationService#cancel} иска причина, и ако
 * формата показва полето едва СЛЕД отказа и само за четене, документът не може да се откаже от
 * екрана. Истинската услуга; подменен е само записът (база).
 */
class BgCompensationCancelTest {

  private static final String REASON = "Документът е издаден по грешен договор";

  /** Трите състояния, от които отказът е разрешен. */
  private static final String[] LIVE = {
    BgCompensationResolver.DRAFT, BgCompensationResolver.CONFIRMED, BgCompensationResolver.PAID
  };

  private static final class Service extends BgCompensationService {
    int saves;

    @Override
    protected void save(BgCompensation doc) {
      saves++;
    }
  }

  private static BgCompensation doc(String status) {
    BgCompensation d = new BgCompensation();
    d.setReference("OBEZ-2026-0001");
    d.setStatusSelect(status);
    return d;
  }

  @Test
  @DisplayName("без причина → ясен отказ, а документът не мърда и не се записва")
  void withoutAReasonNothingChanges() {
    for (String status : LIVE) {
      Service s = new Service();
      BgCompensation d = doc(status);
      for (String blank : new String[] {null, "", "   "}) {
        AxelorException e = assertThrows(AxelorException.class, () -> s.cancel(d, blank));
        assertEquals("Отказът иска причина.", e.getMessage(), status);
      }
      assertEquals(status, d.getStatusSelect(), "статусът не мърда");
      assertNull(d.getBgCancelReason(), "нищо не е записано като причина");
      assertEquals(0, s.saves, "документът не е записан");
    }
  }

  @Test
  @DisplayName("с причина → отказан, причината е в документа без празните краища")
  void withAReasonTheDocumentIsCancelled() throws AxelorException {
    for (String status : LIVE) {
      Service s = new Service();
      BgCompensation d = doc(status);
      s.cancel(d, "  " + REASON + "  ");
      assertEquals(BgCompensationResolver.CANCELLED, d.getStatusSelect(), status);
      assertEquals(REASON, d.getBgCancelReason(), status);
      assertEquals(1, s.saves, status);
    }
  }

  @Test
  @DisplayName("заварената врата cancel(doc) чете причината от документа")
  void theLegacyDoorStillWorks() throws AxelorException {
    Service s = new Service();
    BgCompensation d = doc(BgCompensationResolver.CONFIRMED);
    d.setBgCancelReason(REASON);
    s.cancel(d);
    assertEquals(BgCompensationResolver.CANCELLED, d.getStatusSelect());
    assertEquals(REASON, d.getBgCancelReason());
  }
}
