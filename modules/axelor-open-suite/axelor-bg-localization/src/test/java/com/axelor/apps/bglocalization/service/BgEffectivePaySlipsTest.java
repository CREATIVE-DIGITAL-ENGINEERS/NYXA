package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.hr.db.EmploymentContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОТКАЗЪТ ЗА ВТОРИ РЕДОВЕН ФИШ — текстът и вратата към базата. Без база, без Guice; самата заявка
 * се мери по реалния път в {@code BgEffectivePaySlipRealPathTest}.
 */
class BgEffectivePaySlipsTest {

  @Test
  @DisplayName("отказът назовава СЪЩЕСТВУВАЩИЯ фиш — номер и статус на езика на екрана")
  void theRefusalNamesTheExistingSlip() {
    BgPaySlip existing = slip("FISH-2026-0011", BgPaySlipResolver.CONFIRMED);
    BgPaySlip second = slip("FISH-2026-0021", BgPaySlipResolver.DRAFT);

    String refusal = BgEffectivePaySlips.refuseSecondRegular(second, existing);

    assertTrue(refusal.startsWith("Фиш FISH-2026-0021 не се записва"), refusal);
    assertTrue(
        refusal.contains("вече има редовен фиш FISH-2026-0011 (статус: потвърден)"), refusal);
    assertTrue(refusal.contains("„10/2026“"), "и периода: " + refusal);
    assertTrue(
        BgEffectivePaySlips.refuseSecondRegular(second, slip("FISH-2026-0012", "DRAFT"))
            .contains("(статус: чернова)"));
    assertNull(BgEffectivePaySlips.refuseSecondRegular(second, null), "няма друг — няма отказ");
  }

  @Test
  @DisplayName("ретро-копие, отказан фиш и фиш без идентификатори НЕ стигат до базата")
  void theGuardDoesNotAskTheDatabaseWhenThereIsNothingToAsk() {
    BgPaySlip copy = slip("FISH-2026-0019", BgPaySlipResolver.DRAFT);
    copy.setBgIsCorrection(true);
    assertNull(BgEffectivePaySlips.otherRegular(copy), "копието минава по своя път");

    BgPaySlip cancelled = slip("FISH-2026-0021", BgPaySlipResolver.CANCELLED);
    assertNull(BgEffectivePaySlips.otherRegular(cancelled), "отказаният не се брои");

    assertNull(
        BgEffectivePaySlips.otherRegular(slip("FISH-2026-0022", BgPaySlipResolver.DRAFT)),
        "⚠️ период без идентификатор — шевът на declaredToNap: unit-тестът не стига до базата");
  }

  private static BgPaySlip slip(String reference, String status) {
    Period period = new Period();
    period.setName("10/2026");
    EmploymentContract contract = new EmploymentContract();
    BgPaySlip slip = new BgPaySlip();
    slip.setReference(reference);
    slip.setStatusSelect(status);
    slip.setPeriod(period);
    slip.setEmploymentContract(contract);
    return slip;
  }
}
