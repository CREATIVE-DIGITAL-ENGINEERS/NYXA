package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗДРАВНАТА ВНОСКА ПРИ НЕПЛАТЕН ОТПУСК — ЗЗО чл. 40 ал. 1 т. 1 б. „б“. Чиста аритметика, без база.
 *
 * <p>Числата не са измислени. Базата е {@code HEALTH_UNPAID_LEAVE_BASE} от seed-а — 275,33 до
 * 31.07.2026 г. и 310,10 от 01.08.2026 г., тоест половината от минималния осигурителен доход за
 * самоосигуряващите се (550,66 и 620,20). Ставката 8,00 е сборът на двете части на фонд ЗОВ, а
 * месецът е юли 2026 г. с 23 работни дни — същият, с който работи {@code BgSickLeaveResolverTest}.
 *
 * <p><b>Какво този файл НЕ проверява.</b> Че сумата се записва като перо — тя ДНЕС не се записва,
 * точно както здравната вноска за дните ДОО. Виж класовия javadoc на резолвера.
 */
class BgUnpaidLeaveHealthResolverTest {

  /** Юли 2026: 23 работни дни, нула официални празници. */
  private static final int JULY_WORKING_DAYS = 23;

  /** {@code HEALTH_UNPAID_LEAVE_BASE} за 01.01–31.07.2026 г. */
  private static final BigDecimal HALF_SOL_H1 = new BigDecimal("275.3300");

  /** {@code HEALTH_UNPAID_LEAVE_BASE} от 01.08.2026 г. — ЗБДОО 2026, ДВ бр. 68/28.07.2026. */
  private static final BigDecimal HALF_SOL_H2 = new BigDecimal("310.1000");

  /** ЦЯЛАТА вноска за фонд ЗОВ: 4,8 + 3,2 за вид 01, трета категория, 2026 г. */
  private static final BigDecimal TOTAL_RATE = new BigDecimal("8.0000");

  /**
   * Дните като {@link BigDecimal} — те носят ДРОБНА част.
   *
   * <p>⚠️ Причината е в {@code BgPayrollInput#paidLeaveDays}: час отпуск по КТ чл. 166 при
   * осемчасов ден е 0,125 дни, а {@code Integer} не може да го изрази. Хелпърът е тук, за да
   * останат заварените свидетели четими като цели дни.
   */
  private static BigDecimal days(int d) {
    return BigDecimal.valueOf(d);
  }

  // ═══ 1 · ПЛАТЕЦЪТ ════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПЛАТЕЦЪТ идва от речника на отсъствията, не от кода на отсъствието")
  void thePayerComesFromTheAbsenceDictionary() {
    assertEquals(
        BgUnpaidLeaveHealthResolver.Payer.INSURED_PERSON,
        BgUnpaidLeaveHealthResolver.payerFor("unpaid_employee"),
        "неплатен по ЖЕЛАНИЕ НА ЛИЦЕТО — вноската е изцяло негова");
    assertEquals(
        BgUnpaidLeaveHealthResolver.Payer.EMPLOYER,
        BgUnpaidLeaveHealthResolver.payerFor("unpaid_employer"),
        "чл. 167а КТ, производствена необходимост и престой — изцяло за сметка на работодателя");
  }

  @Test
  @DisplayName("„Осигурен на друго основание“ и стандартното правило НЕ пораждат вноска по б. „б“")
  void theOtherBasisAndStandardRulesFallOutsideTheHypothesis() {
    assertNull(BgUnpaidLeaveHealthResolver.payerFor("other_basis"));
    assertNull(BgUnpaidLeaveHealthResolver.payerFor("standard"));
    assertNull(BgUnpaidLeaveHealthResolver.payerFor(null));

    BgUnpaidLeaveHealthResolver.Outcome out =
        BgUnpaidLeaveHealthResolver.resolve(
            "other_basis", HALF_SOL_H2, TOTAL_RATE, days(21), JULY_WORKING_DAYS);
    assertEquals(BgUnpaidLeaveHealthResolver.Status.NOT_APPLICABLE, out.status);
    assertNull(out.amount, "не се дължи ≠ нула на фиша");
    assertTrue(out.detail.contains("other_basis"), out.detail);
  }

  // ═══ 2 · СУМАТА ══════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЗЛАТНАТА СУМА · 8% × 310,10 = 24,81 (H2) и × 275,33 = 22,03 (H1)")
  void theWitnessAmounts() {
    BigDecimal h2 =
        BgUnpaidLeaveHealthResolver.amount(
            HALF_SOL_H2, TOTAL_RATE, days(JULY_WORKING_DAYS), JULY_WORKING_DAYS);
    BigDecimal h1 =
        BgUnpaidLeaveHealthResolver.amount(
            HALF_SOL_H1, TOTAL_RATE, days(JULY_WORKING_DAYS), JULY_WORKING_DAYS);

    assertEquals(
        0, new BigDecimal("24.81").compareTo(h2), "8% × 310,10 = 24,808 → 24,81. Намерено: " + h2);
    assertEquals(
        0, new BigDecimal("22.03").compareTo(h1), "8% × 275,33 = 22,0264 → 22,03. Намерено: " + h1);
  }

  @Test
  @DisplayName("⚠️ РЕШЕНИЕ № 1 · ЦЯЛАТА вноска; частта на лицето би дала 9,92 вместо 24,81")
  void theWholeContributionIsOwedNotOnePayersShare() {
    BigDecimal whole =
        BgUnpaidLeaveHealthResolver.amount(
            HALF_SOL_H2, TOTAL_RATE, days(JULY_WORKING_DAYS), JULY_WORKING_DAYS);
    BigDecimal employeeShareOnly =
        BgUnpaidLeaveHealthResolver.amount(
            HALF_SOL_H2, new BigDecimal("3.2000"), days(JULY_WORKING_DAYS), JULY_WORKING_DAYS);

    assertEquals(0, new BigDecimal("24.81").compareTo(whole));
    assertEquals(
        0,
        new BigDecimal("9.92").compareTo(employeeShareOnly),
        "⚠️ „Изцяло за сметка на лицето“ значи ЦЯЛАТА вноска, носена от него, а не неговата ЧАСТ."
            + " Грешното четене дава 9,92 — под една трета от дължимото, с вид на пресметнато."
            + " Намерено: "
            + employeeShareOnly);
  }

  @Test
  @DisplayName("⚠️ Траншът от 01.08.2026 сменя БАЗАТА, не ставката — 22,03 → 24,81")
  void theSecondHalfOfTwentyTwentySixChangesTheBase() {
    BigDecimal h1 =
        BgUnpaidLeaveHealthResolver.amount(
            HALF_SOL_H1, TOTAL_RATE, days(JULY_WORKING_DAYS), JULY_WORKING_DAYS);
    BigDecimal h2 =
        BgUnpaidLeaveHealthResolver.amount(
            HALF_SOL_H2, TOTAL_RATE, days(JULY_WORKING_DAYS), JULY_WORKING_DAYS);
    assertEquals(
        0,
        new BigDecimal("2.78").compareTo(h2.subtract(h1)),
        "разликата е само от базата — 310,10 срещу 275,33. Намерено: " + h2.subtract(h1));
  }

  @Test
  @DisplayName("⚠️ РЕШЕНИЕ № 2 · частичният месец се проратира по работните дни в неплатен отпуск")
  void aPartialMonthIsProratedByUnpaidWorkingDays() {
    BigDecimal fiveDays =
        BgUnpaidLeaveHealthResolver.amount(HALF_SOL_H2, TOTAL_RATE, days(5), JULY_WORKING_DAYS);
    assertEquals(
        0,
        new BigDecimal("5.39").compareTo(fiveDays),
        "310,10 × 8% × 5 / 23 = 5,3930… → 5,39. Пълната месечна база би дала 24,81 за пет дни"
            + " отпуск, тоест би обложила и отработените дни, които вече са обложени по т. 1."
            + " Намерено: "
            + fiveDays);
  }

  @Test
  @DisplayName(
      "⚠️ ЕДНО ЗАКРЪГЛЯНЕ НАКРАЯ (§ 5.3) — междинно закръглена дневна база дава друго число")
  void oneRoundingAtTheEnd() {
    BigDecimal once =
        BgUnpaidLeaveHealthResolver.amount(HALF_SOL_H2, TOTAL_RATE, days(10), JULY_WORKING_DAYS);
    // Веригата „дневна база → закръгляне → умножение“: 310,10 / 23 = 13,4826… → 13,48 × 10 × 8%.
    BigDecimal chained =
        HALF_SOL_H2
            .divide(BigDecimal.valueOf(JULY_WORKING_DAYS), 2, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(10))
            .multiply(TOTAL_RATE)
            .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);

    assertEquals(
        0, new BigDecimal("10.79").compareTo(once), "310,10 × 8% × 10 / 23 = 10,7860… → 10,79");
    assertEquals(
        0,
        new BigDecimal("10.78").compareTo(chained),
        "⚠️ Същите входове, стотинка разлика: закръглената дневна база губи 10,784 срещу 10,786."
            + " Затова делението е ЕДНО и е ПОСЛЕДНО. Намерено: "
            + chained);
  }

  // ═══ 3 · ЦЕЛИЯТ ОТГОВОР ══════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПРИЕМЕН СЛУЧАЙ T12 · цял месец неплатен по молба → 24,81 за сметка на ЛИЦЕТО")
  void theWholeMonthOfUnpaidLeaveByOwnRequest() {
    BgUnpaidLeaveHealthResolver.Outcome out =
        BgUnpaidLeaveHealthResolver.resolve(
            "unpaid_employee", HALF_SOL_H2, TOTAL_RATE, days(JULY_WORKING_DAYS), JULY_WORKING_DAYS);

    assertEquals(BgUnpaidLeaveHealthResolver.Status.COMPUTED, out.status);
    assertEquals(0, new BigDecimal("24.81").compareTo(out.amount), String.valueOf(out.amount));
    assertEquals(BgUnpaidLeaveHealthResolver.Payer.INSURED_PERSON, out.payer);
    assertEquals(0, BigDecimal.valueOf(JULY_WORKING_DAYS).compareTo(out.quantity));
  }

  @Test
  @DisplayName("⚠️ СЪЩИТЕ 24,81 при отпуск за дете по чл. 167а — но за сметка на РАБОТОДАТЕЛЯ")
  void theSameAmountUnderArticle167aIsOwedByTheEmployer() {
    BgUnpaidLeaveHealthResolver.Outcome out =
        BgUnpaidLeaveHealthResolver.resolve(
            "unpaid_employer", HALF_SOL_H2, TOTAL_RATE, days(JULY_WORKING_DAYS), JULY_WORKING_DAYS);

    assertEquals(BgUnpaidLeaveHealthResolver.Status.COMPUTED, out.status);
    assertEquals(0, new BigDecimal("24.81").compareTo(out.amount));
    assertEquals(
        BgUnpaidLeaveHealthResolver.Payer.EMPLOYER,
        out.payer,
        "⚠️ Сумата е една и съща, платецът — не. Свит до число, отговорът губи половината от себе"
            + " си: 24,81 на лицето и 24,81 на работодателя са два различни фиша.");
  }

  @Test
  @DisplayName("Нула дни в неплатен отпуск → вноска не се дължи, и това не е отказ")
  void zeroUnpaidDaysOweNothing() {
    BgUnpaidLeaveHealthResolver.Outcome out =
        BgUnpaidLeaveHealthResolver.resolve(
            "unpaid_employee", HALF_SOL_H2, TOTAL_RATE, days(0), JULY_WORKING_DAYS);
    assertEquals(BgUnpaidLeaveHealthResolver.Status.NOT_APPLICABLE, out.status);
    assertNull(out.amount);
  }

  @Test
  @DisplayName("⚠️ ЛИПСВАЩА БАЗА или ставка · ОТКАЗ — недекларирана вноска, не нула")
  void aMissingBaseOrRateRefuses() {
    BgUnpaidLeaveHealthResolver.Outcome noBase =
        BgUnpaidLeaveHealthResolver.resolve(
            "unpaid_employee", null, TOTAL_RATE, days(21), JULY_WORKING_DAYS);
    assertEquals(BgUnpaidLeaveHealthResolver.Status.REFUSED, noBase.status);
    assertTrue(noBase.detail.contains("HEALTH_UNPAID_LEAVE_BASE"), noBase.detail);

    BgUnpaidLeaveHealthResolver.Outcome noRate =
        BgUnpaidLeaveHealthResolver.resolve(
            "unpaid_employee", HALF_SOL_H2, null, days(21), JULY_WORKING_DAYS);
    assertEquals(BgUnpaidLeaveHealthResolver.Status.REFUSED, noRate.status);
    assertTrue(noRate.detail.contains("bg_insurance_contribution"), noRate.detail);

    BgUnpaidLeaveHealthResolver.Outcome zeroBase =
        BgUnpaidLeaveHealthResolver.resolve(
            "unpaid_employee", BigDecimal.ZERO, TOTAL_RATE, days(21), JULY_WORKING_DAYS);
    assertEquals(
        BgUnpaidLeaveHealthResolver.Status.REFUSED,
        zeroBase.status,
        "генерираният getter връща ZERO и за непопълнен параметър — нулата тук е ЛИПСА");
  }

  @Test
  @DisplayName("⚠️ Липсваща норма и дни НАД нормата · ОТКАЗ — сверява се входът, не сметката")
  void aMissingOrExceededNormRefuses() {
    BgUnpaidLeaveHealthResolver.Outcome noNorm =
        BgUnpaidLeaveHealthResolver.resolve(
            "unpaid_employee", HALF_SOL_H2, TOTAL_RATE, days(21), null);
    assertEquals(BgUnpaidLeaveHealthResolver.Status.REFUSED, noNorm.status);
    assertNotNull(noNorm.detail);

    BgUnpaidLeaveHealthResolver.Outcome tooMany =
        BgUnpaidLeaveHealthResolver.resolve(
            "unpaid_employee",
            HALF_SOL_H2,
            TOTAL_RATE,
            days(JULY_WORKING_DAYS + 1),
            JULY_WORKING_DAYS);
    assertEquals(BgUnpaidLeaveHealthResolver.Status.REFUSED, tooMany.status);
    assertTrue(tooMany.detail.contains("надхвърлят"), tooMany.detail);
  }

  // ═══ 6 · ДРОБНИТЕ ДНИ ════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ДРОБЕН ДЕН · половин ден неплатен дава ПОЛОВИНАТА от еднодневната вноска")
  void aHalfDayOfUnpaidLeaveOwesHalfTheDailyContribution() {
    BigDecimal oneDay =
        BgUnpaidLeaveHealthResolver.amount(HALF_SOL_H2, TOTAL_RATE, days(1), JULY_WORKING_DAYS);
    BigDecimal halfDay =
        BgUnpaidLeaveHealthResolver.amount(
            HALF_SOL_H2, TOTAL_RATE, new BigDecimal("0.50"), JULY_WORKING_DAYS);

    // 8% × 310,10 = 24,808 ; за 1 от 23 работни дни → 1,0786… → 1,08 ; за половин ден → 0,54
    assertEquals(0, new BigDecimal("1.08").compareTo(oneDay), String.valueOf(oneDay));
    assertEquals(
        0,
        new BigDecimal("0.54").compareTo(halfDay),
        "⚠️ ПОЛОВИН ДЕН ТРЯБВА ДА Е ИЗРАЗИМ. Като Integer дните не биха стигнали до 0,5 изобщо —"
            + " BgPayrollInputBuilder би отказал ЦЯЛАТА тройка и вноската би изчезнала от фиша."
            + " Намерено: "
            + halfDay);
  }

  @Test
  @DisplayName("⚠️ Един ЧАС неплатен отпуск (КТ чл. 166) е 0,125 дни и НЕ е нула евро")
  void oneHourOfUnpaidLeaveIsAnEighthOfADayAndNotZero() {
    BgUnpaidLeaveHealthResolver.Outcome out =
        BgUnpaidLeaveHealthResolver.resolve(
            "unpaid_employee", HALF_SOL_H2, TOTAL_RATE, new BigDecimal("0.13"), JULY_WORKING_DAYS);

    assertEquals(BgUnpaidLeaveHealthResolver.Status.COMPUTED, out.status);
    assertEquals(
        0,
        new BigDecimal("0.14").compareTo(out.amount),
        "24,808 × 0,13 / 23 = 0,1402… → 0,14. Закръглен на входа до нула ден, този час щеше да"
            + " даде 0,00 — тоест недекларирана вноска с вид на „не се дължи“. Намерено: "
            + out.amount);
  }
}
