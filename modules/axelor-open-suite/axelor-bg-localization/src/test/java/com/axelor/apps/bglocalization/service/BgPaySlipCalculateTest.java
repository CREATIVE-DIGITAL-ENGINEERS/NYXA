package com.axelor.apps.bglocalization.service;

import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.contract;
import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.contributionRows;
import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.draft;
import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.type;
import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.wired;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЕДИН ИЗЧИСЛИТЕЛЕН ПЪТ, И ПОТВЪРЖДАВАНЕ, КОЕТО НЕ ПРИЕМА НЕПРЕСМЕТНАТ ФИШ.
 *
 * <p>Рискът: перо 180 „Постоянен бонус“ с въведени 200,00, добавено след пресмятането, остава със
 * сума 0,00, ако карвът живее само в {@code prepare()}, а формата при промяна на перата пуска само
 * {@code recompute()}. Заглавният данък и перото 700 остават равни, пазачът на данъка мълчи, и
 * фишът би се потвърдил без бонуса.
 *
 * <p>Всички свидетели минават през {@link BgSeededPaySlipService}: истинската услуга с истинските
 * генератори, а речникът, ставките и параметрите са от сийда. Числата са сметнати на ръка от сийда
 * — виж всеки свидетел.
 *
 * <p>Ставките за вид 01, трета категория, роден след 1959, с ДЗПО (bg_insurance_contribution.csv):
 * лице 6,58 + 2,20 + 1,40 + 0,40 + 3,20 = 13,78 %; осигурител 8,22 + 2,80 + 2,10 + 0,60 + 4,80 =
 * 18,52 %. ГВРС е законна нула за 2026 г. ДОД 10 % (INCOME_TAX_RATE), таванът 2111,64
 * (MAX_INSURABLE_INCOME до 31.07.2026).
 */
class BgPaySlipCalculateTest {

  private static final String PERMANENT_BONUS = "pl_postoyanen_bonus";
  private static final String ONE_OFF_BONUS = "pl_ednokraten_bonus";
  private static final String BASE_SALARY = "pl_osnovna";
  private static final String TAX = "pl_dod";
  private static final String SICK_PAY = "pl_bolnichen_rabotodatel";

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static void same(String expected, BigDecimal actual, String what) {
    assertEquals(
        0, bd(expected).compareTo(actual), what + ": очаквах " + expected + ", а е " + actual);
  }

  private static List<BgPaySlipLine> linesWithCode(BgPaySlip slip, String code) {
    List<BgPaySlipLine> out = new ArrayList<>();
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (l.getBgPaySlipLineType() != null && code.equals(l.getBgPaySlipLineType().getCode())) {
        out.add(l);
      }
    }
    return out;
  }

  private static BgPaySlipLine line(BgPaySlip slip, String code) {
    List<BgPaySlipLine> found = linesWithCode(slip, code);
    assertEquals(1, found.size(), "очаквах точно едно перо „" + code + "“, а са " + found.size());
    return found.get(0);
  }

  /** Вноските на фиша по код на перо — само нормалната база, само собственият период. */
  private static Map<String, BigDecimal> contributions(BgPaySlip slip) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (BgPaySlipResolver.groupOf(l) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
          && Boolean.TRUE.equals(l.getBgIsGenerated())) {
        out.put(l.getBgPaySlipLineType().getCode(), l.getBgAmount());
      }
    }
    return out;
  }

  /** Ново перо — точно както го оставя прозорецът на перото: видът, после сумата. */
  private static BgPaySlipLine added(
      BgSeededPaySlipService s, BgPaySlip slip, String code, String amount, String entered) {
    BgPaySlipLine line = s.lineFrom(type(code), bd(amount));
    if (entered != null) {
      line.setBgEnteredAmount(bd(entered));
    }
    s.addLine(slip, line);
    return line;
  }

  private static BgPaySlip calculated(BgSeededPaySlipService s) {
    BgPaySlip slip = draft(contract());
    s.calculate(slip);
    same("1500.00", slip.getBgGrossTotal(), "котвата: брутото на чистия фиш е самата заплата");
    return slip;
  }

  // ═══ W1 · ПОСТОЯНЕН БОНУС СЛЕД ПРЕСМЯТАНЕТО ═══════════════════════════════

  @Test
  @DisplayName("W1 · перо 180 с въведени 200,00 след пресмятането → отказ, фишът остава чернова")
  void aPermanentBonusAddedAfterTheCalculationIsRefused() {
    BgSeededPaySlipService s = wired(contract());
    BgPaySlip slip = calculated(s);

    // Операторът добавя 180 и пише 200 във „Въведена месечна сума“ — „Сума“ е readonly за тези
    // пера, тоест остава 0,00. После идва onChange на перата — само recompute().
    added(s, slip, PERMANENT_BONUS, "0.00", "200.00");
    s.recompute(slip);
    same("1500.00", slip.getBgGrossTotal(), "recompute сумира 0,00 — бонусът не е в брутото");

    AxelorException refused = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertEquals(
        "Фишът е променян след последното пресмятане: перо 180 „Постоянен бонус“ — въведени"
            + " 200,00, сума 0,00. Натиснете „Преизчисли“, прегледайте сумите и потвърдете"
            + " отново.",
        refused.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect(), "фишът остава чернова");
    assertEquals(0, s.saves, "отказът не записва нищо");
  }

  // ═══ W2 · ПРЕСМЯТАНЕТО ВКАРВА БОНУСА ВЪВ ВСЯКО ЧИСЛО ══════════════════════

  @Test
  @DisplayName("W2 · след calculate() перо 180 = 200,00, а 5xx и 700 са пресметнати върху 1700,00")
  void calculatePutsTheBonusIntoEveryNumber() throws AxelorException {
    BgSeededPaySlipService s = wired(contract());
    BgPaySlip slip = calculated(s);
    same("206.70", sumDebit(slip), "преди бонуса: 13,78 % от 1500,00");
    same("129.33", line(slip, TAX).getBgAmount(), "преди бонуса: 10 % от (1500,00 − 206,70)");

    added(s, slip, PERMANENT_BONUS, "0.00", "200.00");
    s.calculate(slip);

    BgPaySlipLine bonus = line(slip, PERMANENT_BONUS);
    same("200.00", bonus.getBgAmount(), "карвът без отпуск: 200 × 21/21");
    same("200.00", bonus.getBgEnteredAmount(), "въведеното остава");

    // Базата 1500 + 200 = 1700,00 — под тавана 2111,64. Всяка вноска е закръглена поотделно.
    Map<String, BigDecimal> c = contributions(slip);
    same("139.74", c.get("pl_vn_pension_osig"), "8,22 % от 1700");
    same("111.86", c.get("pl_vn_pension_lice"), "6,58 % от 1700");
    same("47.60", c.get("pl_vn_upf_osig"), "2,80 % от 1700");
    same("37.40", c.get("pl_vn_upf_lice"), "2,20 % от 1700");
    same("35.70", c.get("pl_vn_ozm_osig"), "2,10 % от 1700");
    same("23.80", c.get("pl_vn_ozm_lice"), "1,40 % от 1700");
    same("10.20", c.get("pl_vn_unemployment_osig"), "0,60 % от 1700");
    same("6.80", c.get("pl_vn_unemployment_lice"), "0,40 % от 1700");
    same("81.60", c.get("pl_vn_health_osig"), "4,80 % от 1700");
    same("54.40", c.get("pl_vn_health_lice"), "3,20 % от 1700");
    assertEquals(10, c.size(), "ГВРС е законна нула за 2026 г. и няма перо: " + c.keySet());
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (BgPaySlipResolver.groupOf(l) == BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
        same("1700.00", l.getBgQuantity(), "базата на " + l.getName());
      }
    }

    // Данъкът: (1700,00 − 234,26) × 10 % = 146,574 → 146,57.
    BgPaySlipLine tax = line(slip, TAX);
    same("1465.74", tax.getBgQuantity(), "данъчната основа на перото");
    same("146.57", tax.getBgAmount(), "ДОД");

    same("1700.00", slip.getBgGrossTotal(), "бруто");
    same("1700.00", slip.getBgInsuranceIncome(), "осигурителен доход");
    same("1465.74", slip.getBgTaxBase(), "данъчна основа");
    same("146.57", slip.getBgTaxAmount(), "данък");
    same("1319.17", slip.getBgNetTotal(), "нето: 1700,00 − 234,26 − 146,57");
    same("2014.84", slip.getBgEmployerCost(), "разход: 1700,00 + 314,84");

    s.confirm(slip);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect());
    same("1700.00", slip.getBgGrossTotal(), "потвърденото бруто носи бонуса");
  }

  private static BigDecimal sumDebit(BgPaySlip slip) {
    return BgPaySlipResolver.employeeContributions(slip.getBgPaySlipLineList());
  }

  // ═══ W3 · ЕДНОКРАТЕН БОНУС И РЪЧНО ОПРАВЕН ДАНЪК ══════════════════════════

  @Test
  @DisplayName("W3 · перо 185 и ръчно оправен 700 → отказ (вноските остават без бонуса)")
  void aOneOffBonusWithAHandFixedTaxIsRefused() {
    BgSeededPaySlipService s = wired(contract());
    BgPaySlip slip = calculated(s);

    added(s, slip, ONE_OFF_BONUS, "200.00", null);
    s.recompute(slip);
    // Заглавният данък вече е (1700,00 − 206,70) × 10 % = 149,33, а перото 700 — 129,33.
    // Проверката на данъка би спряла фиша със съвета „поправете перото“; операторът го прави.
    same("149.33", slip.getBgTaxAmount(), "заглавният данък след recompute");
    line(slip, TAX).setBgAmount(bd("149.33"));
    s.recompute(slip);
    assertNull(
        BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList()),
        "котвата: данъкът вече се връзва — старият пазач мълчи");

    AxelorException refused = assertThrows(AxelorException.class, () -> s.confirm(slip));
    String m = refused.getMessage();
    assertTrue(m.startsWith("Фишът е променян след последното пресмятане: "), m);
    assertTrue(m.contains("перо 185 „Еднократен бонус“ — сума 200,00"), m);
    assertTrue(m.contains("перо 700 „" + type(TAX).getName() + "“ — сума 129,33 → 149,33"), m);
    assertTrue(
        m.contains(
            "Вноските не съответстват на осигурителния доход: вноските са върху 1500,00,"
                + " а осигурителният доход е 1700,00"),
        m);
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect());
  }

  // ═══ W5 · РЪЧНИЯТ ТРУД ОЦЕЛЯВА ═════════════════════════════════════════════

  @Test
  @DisplayName("W5 · calculate() пази еднократното ръчно перо и ръчно редактираната заплата")
  void calculateKeepsManualWork() throws AxelorException {
    BgSeededPaySlipService s = wired(contract());
    BgPaySlip slip = calculated(s);

    added(s, slip, ONE_OFF_BONUS, "200.00", null);
    // Ръчната заплата: формата сваля признака (action-bg-payslip-record-manual-base-salary).
    slip.setBgBaseSalary(bd("1600.00"));
    slip.setBgAutoFillBaseSalary(false);
    s.calculate(slip);
    s.calculate(slip);

    same("200.00", line(slip, ONE_OFF_BONUS).getBgAmount(), "еднократното перо — същата сума");
    same("1600.00", slip.getBgBaseSalary(), "ръчната заплата не е презаписана от договора");
    assertFalse(slip.getBgAutoFillBaseSalary(), "признакът остава свален");
    same("1600.00", line(slip, BASE_SALARY).getBgAmount(), "основното — от ръчната заплата");
    same("1800.00", slip.getBgGrossTotal(), "1600,00 + 200,00");

    s.confirm(slip);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect());
  }

  // ═══ W6 · ГЕНЕРАТОРЪТ МАХА САМО СВОЕТО ════════════════════════════════════

  @Test
  @DisplayName("W6 · отпаднал фонд → старите 510/511 изчезват; ръчен 511 със същия код остава")
  void aDroppedFundRemovesOnlyTheGeneratorsLines() {
    BgSeededPaySlipService s = wired(contract());
    BgPaySlip slip = calculated(s);
    assertEquals(1, linesWithCode(slip, "pl_vn_upf_lice").size(), "котвата: ДЗПО е начислено");

    BgPaySlipLine manual = added(s, slip, "pl_vn_upf_lice", "10.00", null);
    s.rows = contributionRows("WITHOUT_UPF");
    s.calculate(slip);

    assertTrue(linesWithCode(slip, "pl_vn_upf_osig").isEmpty(), "белязаното 510 е махнато");
    List<BgPaySlipLine> upf = linesWithCode(slip, "pl_vn_upf_lice");
    assertEquals(1, upf.size(), "от 511 остава само ръчният ред");
    assertTrue(upf.get(0) == manual, "оцелелият е ръчният ред, не генерираният");
    same("10.00", manual.getBgAmount(), "сумата на ръчния ред не е пипана");
    assertFalse(manual.getBgIsGenerated(), "ръчният ред не е осиновен");
  }

  @Test
  @DisplayName("W6 · законна нула → старите 5xx и 700 изчезват; ръчен 517 остава")
  void aLegalZeroRemovesTheOldLines() {
    BgSeededPaySlipService s = wired(contract());
    BgPaySlip slip = calculated(s);
    assertEquals(10, contributions(slip).size(), "котвата: десет вноски");
    assertEquals(1, linesWithCode(slip, TAX).size(), "котвата: данък има");

    BgPaySlipLine manual = added(s, slip, "pl_vn_health_lice", "5.00", null);
    slip.setBgWorkedDays(0);
    s.calculate(slip);

    same("0.00", slip.getBgGrossTotal(), "нула отработени дни — нула бруто");
    assertTrue(
        contributions(slip).isEmpty(), "всяка вноска е законна нула: " + contributions(slip));
    assertTrue(linesWithCode(slip, TAX).isEmpty(), "данъкът е законна нула и перото му е махнато");
    List<BgPaySlipLine> health = linesWithCode(slip, "pl_vn_health_lice");
    assertEquals(1, health.size());
    assertTrue(health.get(0) == manual, "ръчният 517 остава");
  }

  // ═══ W7 · ДНИТЕ СЛЕД ПРЕСМЯТАНЕТО ════════════════════════════════════════

  @Test
  @DisplayName("W7 · сменени дни след пресмятането → confirm отказва и не записва нищо")
  void changedDaysAfterTheCalculationAreRefused() {
    BgSeededPaySlipService s = wired(contract());
    BgPaySlip slip = calculated(s);

    slip.setBgWorkedDays(19);
    AxelorException refused = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(
        refused.getMessage().contains("отработени дни 21 → 19"),
        "отказът назовава входа: " + refused.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect());
    assertEquals(19, (int) slip.getBgWorkedDays(), "confirm не е пипал дните");
    assertEquals(0, s.saves, "confirm не е записал нищо");
  }

  @Test
  @DisplayName("W7 · контролерът сравнява екрана със записа и вече НЕ пренася дните преди confirm")
  void theControllerNoLongerCopiesTheDays() throws IOException {
    BgPaySlip saved = draft(contract());
    saved.setBgWorkedDays(21);
    BgPaySlip screen = draft(contract());
    screen.setBgWorkedDays(19);
    assertEquals(
        java.util.Collections.singletonList("отработени дни — записано 21, на екрана 19"),
        BgPaySlipFingerprint.screenDiffers(saved, screen));
    assertTrue(BgPaySlipFingerprint.screenDiffers(saved, saved).isEmpty());

    String controller =
        new String(
            Files.readAllBytes(
                Paths.get(
                    "src/main/java/com/axelor/apps/bglocalization/web/BgPaySlipController.java")),
            StandardCharsets.UTF_8);
    assertFalse(controller.contains("setBgWorkedDays(context"), "дните не се пренасят от екрана");
    assertFalse(controller.contains("setBgWorkingDaysInPeriod(context"));
    assertTrue(controller.contains("BgPaySlipFingerprint.screenDiffers(slip, context)"));
  }

  // ═══ W8 · ИСТОРИЧЕСКИЯТ ВНОС ═════════════════════════════════════════════

  @Test
  @DisplayName("W8 · исторически внесен фиш: пазачът мълчи, calculate() не го пипа, confirm минава")
  void aHistoricalImportIsNotBlocked() throws AxelorException {
    BgSeededPaySlipService s = wired(contract());
    BgPaySlip slip = draft(contract());
    slip.setBgIsHistoricalImport(true);
    s.addLine(slip, s.lineFrom(type(BASE_SALARY), bd("1500.00")));
    // Сумите са от Microinvest и законно НЕ са сбор на перата.
    slip.setBgGrossTotal(bd("1512.34"));
    slip.setBgNetTotal(bd("1200.00"));

    s.calculate(slip);
    same("1512.34", slip.getBgGrossTotal(), "calculate() не пипа внесения фиш");
    assertNull(slip.getBgCalcFingerprint(), "и не му пише отпечатък");
    assertEquals(1, slip.getBgPaySlipLineList().size(), "генераторите не са пускани");

    assertNull(s.staleSlipRefusal(slip), "пазачът не обявява Microinvest за дефект");
    s.confirm(slip);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect());
  }

  // ═══ КОРИГИРАЩОТО КОПИЕ ═══════════════════════════════════════════════

  /** Копие, на което потвърждаването стига до края без база: делтата и статията са вън от теста. */
  private static BgSeededPaySlipService wiredForRetro() {
    BgSeededPaySlipService s =
        new BgSeededPaySlipService() {
          @Override
          protected void settleRetroDelta(BgPaySlip retro) {}

          @Override
          protected void generatePayrollMove(BgPaySlip slip) {}

          @Override
          protected void closeAfterAdjustment(com.axelor.apps.base.db.Period period) {}
        };
    return s;
  }

  @Test
  @DisplayName("копие с перо 195 само със сборове → (а) и (в) отказват; след calculate минава")
  void aCopyWithAnUnchargedSickLineIsRefusedUntilCalculated() throws AxelorException {
    BgSeededPaySlipService s = wiredForRetro();
    BgPaySlip original = calculated(s);
    s.confirm(original);

    // Копие, перо 195, само сборове — както „Отрази пристигнал болничен“ без пресмятане.
    BgPaySlip copy = s.retroCopyOf(original, false);
    added(s, copy, SICK_PAY, "142.86", null);
    s.recompute(copy);
    same("1642.86", copy.getBgInsuranceIncome(), "195 е в осигурителния доход на заглавието");

    AxelorException refused = assertThrows(AxelorException.class, () -> s.confirm(copy));
    assertEquals(
        "Коригиращото копие не е пресмятано — липсва отпечатък от „Преизчисли“ (промяната по"
            + " формата само показва числата, без да ги записва). Вноските не съответстват на осигурителния доход: перо 195 „"
            + type(SICK_PAY).getName()
            + "“ не е обложено: вноските са върху 1500,00, а осигурителният доход е 1642,86."
            + " Натиснете „Преизчисли“, прегледайте сумите и потвърдете отново.",
        refused.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, copy.getStatusSelect());

    // „Преизчисли“: вноските отиват върху 1642,86, и пазачът за непресметнат фиш мълчи.
    // Лице: 108,10 + 36,14 + 23,00 + 6,57 + 52,57 = 226,38.
    s.calculate(copy);
    BigDecimal personal = BigDecimal.ZERO;
    for (BgPaySlipLine l : copy.getBgPaySlipLineList()) {
      if (BgPaySlipResolver.groupOf(l) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
          && BgPaySlipResolver.DEBIT.equals(l.getBgSign())) {
        same("1642.86", l.getBgQuantity(), "базата на " + l.getName());
        personal = personal.add(l.getBgAmount());
      }
    }
    same("226.38", personal, "личните вноски — и върху 195");
    assertNull(
        s.staleSlipRefusal(copy),
        "правилно сметнатото копие не стига до пазача за непресметнат фиш");
    s.confirm(copy);
    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect());
  }

  @Test
  @DisplayName("обикновено копие след „Преизчисли“ → не се блокира")
  void aPlainCopyIsNotBlocked() throws AxelorException {
    BgSeededPaySlipService s = wiredForRetro();
    BgPaySlip original = calculated(s);
    s.confirm(original);

    BgPaySlip copy = s.retroCopyOf(original, false);
    s.calculate(copy);
    assertNull(s.staleSlipRefusal(copy), "пазачът мълчи");
    s.confirm(copy);
    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect());
  }
}
