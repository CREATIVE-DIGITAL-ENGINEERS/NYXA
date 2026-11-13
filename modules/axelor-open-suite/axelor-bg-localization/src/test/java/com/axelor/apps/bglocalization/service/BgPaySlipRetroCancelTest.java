package com.axelor.apps.bglocalization.service;

import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.contract;
import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.draft;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.payroll.BgSickNoteArrivalRules;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОТКАЗЪТ НА КОРИГИРАЩО КОПИЕ ИСКА ПРИЧИНА, И ТЯ ИМА ОТКЪДЕ ДА ДОЙДЕ.
 *
 * <p>„Откажи“ на коригиращо копие (чернова) без причина дава „Отказът иска причина.“, затова
 * причината трябва да може да се въведе ПРЕДИ отказа. Инак копието остава чернова, периодът — под
 * корекция, и повторното „Отрази пристигнал болничен“ отказва с „ВЕЧЕ е под корекция“: от екрана
 * няма изход.
 *
 * <p>Истинската услуга ({@link BgSeededPaySlipService}); периодът се отваря за корекция по
 * истинския път ({@code openForAdjustment}) и се затваря по истинския ({@code
 * closeAfterAdjustment}). Подменени са само записът на периода и следата на корекцията (база).
 */
class BgPaySlipRetroCancelTest {

  private static final String REASON = "Листът е отразен в грешния фиш";

  /** Делтата и статията на ретро-потвърждаването са вън от теста — те търсят текущия фиш. */
  private static BgSeededPaySlipService wired() {
    BgSeededPaySlipService s =
        new BgSeededPaySlipService() {
          @Override
          protected void settleRetroDelta(BgPaySlip retro) {}

          @Override
          protected void generatePayrollMove(BgPaySlip slip) {}
        };
    return s;
  }

  /** Потвърден оригинал, затворен месец, отворен за корекция — точно както го оставя correct(). */
  private static BgPaySlip adjustingCopy(BgSeededPaySlipService s, boolean deletion)
      throws AxelorException {
    BgPaySlip original = draft(contract());
    s.calculate(original);
    s.confirm(original);
    Period period = original.getPeriod();
    period.setStatusSelect(PeriodRepository.STATUS_CLOSED);
    s.openForAdjustment(period);
    assertEquals(PeriodRepository.STATUS_ADJUSTING, (int) period.getStatusSelect(), "котвата");
    BgPaySlip copy = s.retroCopyOf(original, deletion);
    s.recompute(copy);
    s.saves = 0;
    s.periodSaves = 0;
    return copy;
  }

  @Test
  @DisplayName("без причина → ясен отказ, а копието, периодът и следата не мърдат")
  void withoutAReasonNothingChanges() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip copy = adjustingCopy(s, false);

    for (String blank : new String[] {null, "", "   "}) {
      AxelorException e = assertThrows(AxelorException.class, () -> s.cancel(copy, blank));
      assertEquals("Отказът иска причина.", e.getMessage());
    }
    assertEquals(BgPaySlipResolver.DRAFT, copy.getStatusSelect(), "копието остава чернова");
    assertNull(copy.getBgCancelReason(), "нищо не е записано като причина");
    assertEquals(
        PeriodRepository.STATUS_ADJUSTING,
        (int) copy.getPeriod().getStatusSelect(),
        "периодът остава под корекция");
    assertEquals(0, s.saves, "фишът не е записан");
    assertEquals(0, s.periodSaves, "периодът не е записан");
    assertEquals(0, s.adjustEnds, "следата на корекцията не е затворена");
  }

  @Test
  @DisplayName("с причина → копието е отказано, причината е записана, периодът е CLOSED")
  void withAReasonTheCopyIsCancelledAndThePeriodCloses() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip copy = adjustingCopy(s, false);
    Period period = copy.getPeriod();
    assertNotNull(
        BgPaySlipResolver.refuseRetroPeriod(period, false),
        "котвата: докато копието виси, нова корекция по периода се отказва");

    s.cancel(copy, "  " + REASON + "  ");

    assertEquals(BgPaySlipResolver.CANCELLED, copy.getStatusSelect());
    assertEquals(REASON, copy.getBgCancelReason(), "причината е във фиша, без празните краища");
    assertEquals(
        PeriodRepository.STATUS_CLOSED, (int) period.getStatusSelect(), "периодът е CLOSED");
    assertEquals(1, s.saves, "фишът е записан веднъж");
    assertEquals(1, s.periodSaves, "периодът е записан веднъж");
    assertEquals(1, s.adjustEnds, "следата на корекцията е затворена");

    // „Отрази пристигнал болничен“ отново е разрешено: периодът е затворен, а отказаното копие не
    // е сред „неотказаните“ (читателят на BgSickNoteArrivalService го изключва — виж
    // интеграционния свидетел за самата заявка).
    assertNull(BgPaySlipResolver.refuseRetroPeriod(period, false), "корекцията е разрешена");
    assertNull(
        BgSickNoteArrivalRules.refuseExistingRetro(
            "FISH-2026-0001", period.getName(), Collections.emptyList()),
        "без неотказано копие пазачът мълчи");
  }

  @Test
  @DisplayName("заличаващото копие се отказва по същия път и също затваря периода")
  void aVoidingCopyIsCancelledTheSameWay() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip copy = adjustingCopy(s, true);

    s.cancel(copy, REASON);

    assertEquals(BgPaySlipResolver.CANCELLED, copy.getStatusSelect());
    assertEquals(REASON, copy.getBgCancelReason());
    assertEquals(PeriodRepository.STATUS_CLOSED, (int) copy.getPeriod().getStatusSelect());
  }

  @Test
  @DisplayName("заварената врата cancel(slip) чете причината от фиша и минава по същия път")
  void theLegacyDoorStillWorks() throws AxelorException {
    BgSeededPaySlipService s = wired();
    BgPaySlip copy = adjustingCopy(s, false);
    copy.setBgCancelReason(REASON);

    s.cancel(copy);

    assertEquals(BgPaySlipResolver.CANCELLED, copy.getStatusSelect());
    assertTrue(REASON.equals(copy.getBgCancelReason()));
    assertEquals(PeriodRepository.STATUS_CLOSED, (int) copy.getPeriod().getStatusSelect());
  }
}
