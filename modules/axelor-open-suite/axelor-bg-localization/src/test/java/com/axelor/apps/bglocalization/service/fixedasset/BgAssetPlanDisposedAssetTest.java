package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.bglocalization.db.BgAssetPlanLine;
import com.axelor.apps.bglocalization.db.BgAssetPlanReport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Изведеният актив в амортизационния план — само до месеца на извеждане ({@link
 * BgAssetPlanReportService}), без база.
 */
class BgAssetPlanDisposedAssetTest {

  private final BgAssetPlanReportService service = new BgAssetPlanReportService(null, null, null);

  /** Обзавеждането на „Демо Търговия“: 100 на месец, продадено на 22.09.2026 (73,33 до датата). */
  private static FixedAsset furniture(boolean linesAfterDisposalLeftRealized) {
    FixedAsset a = new FixedAsset();
    a.setGrossValue(new BigDecimal("8000"));
    a.setDisposalDate(LocalDate.of(2026, 9, 22));
    List<FixedAssetLine> economic = new ArrayList<>();
    List<FixedAssetLine> fiscal = new ArrayList<>();
    for (int m = 1; m <= 12; m++) {
      LocalDate end =
          LocalDate.of(2026, m, 1).withDayOfMonth(LocalDate.of(2026, m, 1).lengthOfMonth());
      boolean after = m > 9;
      if (after && !linesAfterDisposalLeftRealized) {
        continue;
      }
      economic.add(line(m == 9 ? LocalDate.of(2026, 9, 22) : end, m == 9 ? "73.33" : "100"));
      if (m < 9 || after) {
        fiscal.add(line(end, "100"));
      }
    }
    a.setFixedAssetLineList(economic);
    a.setFiscalFixedAssetLineList(fiscal);
    return a;
  }

  private static FixedAssetLine line(LocalDate date, String amount) {
    FixedAssetLine l = new FixedAssetLine();
    l.setDepreciationDate(date);
    l.setDepreciation(new BigDecimal(amount));
    l.setStatusSelect(FixedAssetLineRepository.STATUS_REALIZED);
    // Не е напълно амортизиран (иначе чл. 60 ал. 3 т. 1 го задържа в ДАП).
    l.setAccountingValue(new BigDecimal("5000"));
    return l;
  }

  @Test
  @DisplayName(
      "Счетоводният план — до датата на извеждане; данъчният — до края на предходния месец"
          + " (чл. 60 ал. 2 ЗКПО); редове след извеждането не се броят, дори да са реализирани")
  void начисленото_е_до_извеждането() {
    FixedAsset a = furniture(true);
    LocalDate accounting = service.leftPlanOn(a, false);
    LocalDate tax = service.leftPlanOn(a, true);
    assertEquals(LocalDate.of(2026, 9, 22), accounting);
    assertEquals(LocalDate.of(2026, 8, 31), tax);
    assertEquals(
        0,
        new BigDecimal("873.33")
            .compareTo(service.sum(a.getFixedAssetLineList(), 2026, true, accounting)));
    assertEquals(
        0,
        new BigDecimal("800")
            .compareTo(service.sum(a.getFiscalFixedAssetLineList(), 2026, true, tax)));
    // Без извеждане — цялата година (мутацията „без рязане“ би дала 1173,33).
    assertEquals(
        0,
        new BigDecimal("1173.33")
            .compareTo(service.sum(a.getFixedAssetLineList(), 2026, true, null)));
    assertNull(service.leftPlanOn(new FixedAsset(), false), "неизведен актив");
  }

  @Test
  @DisplayName("Изведеният актив: в края на годината не е в плана — стойностите в края са нула")
  void в_края_на_годината_не_е_в_плана() {
    FixedAsset a = furniture(false);
    BgAssetPlanReport report = new BgAssetPlanReport();
    report.setLineList(new ArrayList<>());
    LocalDate leftOn = service.leftPlanOn(a, false);
    service.addLine(
        report,
        a,
        false,
        2026,
        a.getFixedAssetLineList(),
        service.sum(a.getFixedAssetLineList(), 2026, true, leftOn),
        service.sum(a.getFixedAssetLineList(), 2026, false, leftOn));
    service.left(report, a, false, leftOn);
    BgAssetPlanLine line = report.getLineList().get(0);
    assertEquals(0, BigDecimal.ZERO.compareTo(line.getAmortizableValueAtEnd()));
    assertEquals(0, BigDecimal.ZERO.compareTo(line.getCarryingValueAtEnd()));
    assertEquals(0, new BigDecimal("873.33").compareTo(line.getChargeForYear()));
    assertTrue(line.getNote().contains("2026-09-22"), line.getNote());
  }
}
