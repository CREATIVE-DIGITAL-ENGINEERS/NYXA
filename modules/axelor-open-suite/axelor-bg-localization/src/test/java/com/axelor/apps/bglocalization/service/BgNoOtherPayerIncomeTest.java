package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ТРЕТОТО СЪСТОЯНИЕ НА ЧУЖДИЯ ДОХОД — „ТОЗИ МЕСЕЦ НЯМА“, казано изрично.
 *
 * <p><b>Дефектът, който този тест заковава.</b> Носителите на чуждия доход са ДВА — стояща стойност
 * на {@code EmploymentContract.bgOtherPayerInsurableIncome} и месечно надписване на {@code
 * BgPaySlip.bgOtherPayerInsurableIncome}, — но носеха само ДВЕ състояния: „деклариран размер“ и
 * „няма декларация“. Липсваше третото: ИЗЯВЛЕНИЕТО, че за ТОЗИ месец доход при друг платец няма.
 * Генерираният getter за decimal връща ZERO за непопълнена колона, тоест изричната нула на фиша е
 * НЕРАЗЛИЧИМА от непипано поле — и пропадаше към стоящата стойност на договора.
 *
 * <p><b>Следствието е в ПАРИ и е в грешната посока.</b> Договор със стояща стойност 1000,00 и
 * месец, в който другият платец не е осигурявал: месецът се смяташе КООРДИНИРАНО, тоест МОД подът
 * НЕ се прилагаше. При собствен доход 300,00 базата оставаше 300,00 вместо МОД пода 936,00 —
 * разлика от 636,00, тоест 205,43 EUR недовнесени вноски (117,79 работодател + 87,64 лице). И Д1 т.
 * 21 тръгваше със същото занижено число.
 *
 * <p><b>⚠️ ОСНОВАНИЕТО.</b> НЕВДПОВ чл. 4: задължението за деклариране възниква „когато лицето
 * упражнява трудова дейност на ПОВЕЧЕ ОТ ЕДНО основание“, тоест липсата на декларация е нормалното
 * състояние на човек с една работа. И КСО чл. 6 ал. 11: вноските се внасят „върху сбора от
 * осигурителните им доходи“ — СБОРЪТ е МЕСЕЧЕН факт, тоест изявлението е за МЕСЕЦ. ⚠️ Чл. 4а
 * НЕВДПОВ („или декларира обстоятелството, че НЕ Е БИЛО ОСИГУРЯВАНО“) е свидетел само за ФОРМАТА:
 * буквалният му обхват са периодите по КСО чл. 9 ал. 3 т. 1 и 2 (незаконно недопускане и незаконно
 * уволнение), не общият случай с втори работодател.
 *
 * <p><b>⚠️ ПРИЗНАКЪТ НЕ Е НОВА СМЕТКА.</b> „Няма изявление“ и „изявление, че няма“ дават ЕДИН И СЪЩ
 * изход — нормалният единичен път. Пари мърдат само защото досега нямаше кой да спре каскадата.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate: всичко тук са чисти функции.
 */
class BgNoOtherPayerIncomeTest {

  /** ЗБДОО за 2026 г., чл. 9 т. 2 б. „в“ — таванът от 01.08.2026 г. ({@code MAXOD-2026-08}). */
  private static final BigDecimal CAP = new BigDecimal("2300.0000");

  /** Приложение № 1А, ред 01, квалификационна група 1 ({@code MODA-01-1-2026}). */
  private static final BigDecimal MOD = new BigDecimal("936.00");

  private static final LocalDate CAP_FROM = LocalDate.of(2026, 8, 1);

  /** Осигурителният доход на ТОВА правоотношение — под МОД пода, и точно затова мери. */
  private static final BigDecimal OWN = new BigDecimal("300.00");

  /** Стоящата стойност на договора: второто правоотношение си стои месец след месец. */
  private static final BigDecimal STANDING = new BigDecimal("1000.00");

  // ═══ ВНОСКИТЕ ЗА ВИД ОСИГУРЕН 01, WITH_UPF, от bg_insurance_contribution ═══
  // PENSION 8,22/6,58 · UPF 2,80/2,20 · OZM 2,10/1,40 · UNEMPLOYMENT 0,60/0,40 · HEALTH 4,80/3,20
  private static final BigDecimal EMPLOYER_RATE = new BigDecimal("18.52");
  private static final BigDecimal EMPLOYEE_RATE = new BigDecimal("13.78");

  // ═══ ФИШЪТ В ТРИТЕ СЪСТОЯНИЯ ══════════════════════════════════════════════

  private static BgPaySlip slip(BigDecimal monthly, BigDecimal standing, Boolean noOtherPayer) {
    BgPaySlip slip = new BgPaySlip();
    EmploymentContract contract = new EmploymentContract();
    contract.setBgOtherPayerInsurableIncome(standing);
    slip.setEmploymentContract(contract);
    slip.setBgOtherPayerInsurableIncome(monthly);
    if (noOtherPayer != null) {
      slip.setBgNoOtherPayerIncome(noOtherPayer);
    }
    return slip;
  }

  /** Базата, каквато я смята производството: разрешаване → декларация → чистата аритметика. */
  private static BgInsurableIncomeResult baseOf(BgPaySlip slip) {
    BigDecimal declared = BgPaySlipResolver.otherPayerInsurableIncome(slip);
    return BgInsurableIncomeService.compute(
        OWN,
        BgMinIncomeResult.resolved(MOD, "01", 1),
        null,
        false,
        CAP,
        CAP_FROM,
        null,
        BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
        false,
        BgInsurableIncomeService.coordinationOf(declared));
  }

  private static BigDecimal contributionsOn(BigDecimal base, BigDecimal ratePercent) {
    return base.multiply(ratePercent).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ТРИТЕ СЪСТОЯНИЯ, СРЕЩУ ЧИСЛА
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("1/3 · НЯМА ИЗЯВЛЕНИЕ (нито стояща, нито месечна) → нормален път, база 936,00")
  void withoutAnyDeclarationTheMinimumFloorApplies() {
    BgInsurableIncomeResult r = baseOf(slip(null, null, false));

    assertNull(
        BgPaySlipResolver.otherPayerInsurableIncome(slip(null, null, false)),
        "без никакъв носител разрешаването е ЛИПСА, не нула");
    assertTrue(r.isResolved(), "нормалният единичен път, не координация. Статус: " + r.getStatus());
    assertEquals(
        0,
        MOD.compareTo(r.getAmount()),
        "⚠️ БЕЗ ДЕКЛАРАЦИЯ МОД ПОДЪТ ВАЖИ. Доход 300,00 под пода 936,00 се вдига до пода — прагът"
            + " по ТОВА правоотношение се дължи, защото друг платец не го е покрил.");
  }

  @Test
  @DisplayName("2/3 · ДЕКЛАРИРАН РАЗМЕР (стояща 1000,00) → координация, база 300,00")
  void aDeclaredAmountCoordinatesAndDropsTheFloor() {
    BgInsurableIncomeResult r = baseOf(slip(null, STANDING, false));

    assertEquals(
        0,
        STANDING.compareTo(
            BgPaySlipResolver.otherPayerInsurableIncome(slip(null, STANDING, false))),
        "стоящата стойност на договора е декларация за всеки месец по него");
    assertTrue(r.isCoordinated(), "КСО чл. 6 ал. 11. Статус: " + r.getStatus());
    assertEquals(
        0,
        OWN.compareTo(r.getAmount()),
        "⚠️ ПРИ КООРДИНАЦИЯ МОД ПОДЪТ ОТПАДА: прагът е на ЛИЦЕТО за месеца и вече е покрит от"
            + " другия платец. Базата остава суровите 300,00, а не се вдига до 936,00.");
  }

  @Test
  @DisplayName("3/3 · ИЗЯВЛЕНИЕ „ТОЗИ МЕСЕЦ НЯМА“ при стояща 1000,00 → нормален път, база 936,00")
  void theDeclaredAbsenceStopsTheCascade() {
    BgPaySlip slip = slip(null, STANDING, true);

    assertNull(
        BgPaySlipResolver.otherPayerInsurableIncome(slip),
        "⚠️ КАСКАДАТА СПИРА. Признакът връща ЛИПСА и стоящата стойност на договора НЕ се чете —"
            + " инак месецът щеше да се смята с чуждо, застояло число.");

    BgInsurableIncomeResult r = baseOf(slip);
    assertTrue(
        r.isResolved(),
        "⚠️ „ИЗЯВЛЕНИЕ, ЧЕ НЯМА“ ДАВА СЪЩИЯ ИЗХОД КАТО „НЯМА ИЗЯВЛЕНИЕ“ — нормалният единичен път."
            + " Признакът е носител, не нова сметка. Статус: "
            + r.getStatus());
    assertEquals(
        0,
        MOD.compareTo(r.getAmount()),
        "МОД подът важи отново, защото сборът по ал. 11 е само тоя");
  }

  @Test
  @DisplayName("⚠️ СТОЯЩАТА СТОЙНОСТ ОСТАВА НЕПИПНАТА — това е целият смисъл на признака")
  void theStandingValueSurvivesTheDeclaredAbsence() {
    BgPaySlip slip = slip(null, STANDING, true);

    assertEquals(
        0,
        STANDING.compareTo(slip.getEmploymentContract().getBgOtherPayerInsurableIncome()),
        "⚠️ ЗАОБИКОЛКАТА „ЗАНУЛЕТЕ ДОГОВОРА“ е симетричният дефект: забравено"
            + " възстановяване значи, че СЛЕДВАЩИЯТ месец губи координацията и МОД подът се прилага"
            + " без основание — същите 205,43 EUR, но в НАДВНАСЯНЕ.");

    slip.setBgNoOtherPayerIncome(false);
    assertEquals(
        0,
        STANDING.compareTo(BgPaySlipResolver.otherPayerInsurableIncome(slip)),
        "махнат признакът, следващият месец се координира пак — без никой да пипа договора");
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // СЛЕТИЯТ СЛУЧАЙ — С ЧИСЛОТО, КОЕТО МЪРДА
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ СЛЕТИЯТ СЛУЧАЙ: 636,00 делта в базата, 205,43 EUR вноски")
  void theMergedCaseIsNowDistinguishedAndItIsWorthTwoHundredEuro() {
    // ДНЕС (и преди признака): изричната нула на фиша пропада към стоящата стойност.
    BgPaySlip withZero = slip(BigDecimal.ZERO, STANDING, false);
    assertEquals(
        0,
        STANDING.compareTo(BgPaySlipResolver.otherPayerInsurableIncome(withZero)),
        "⚠️ НУЛАТА НЕ Е ИЗЯВЛЕНИЕ И ОСТАВА ДА НЕ Е. Генерираният getter за decimal връща ZERO за"
            + " непопълнена колона, тоест „не съм пипал“ и „въведох 0,00“ са неразличими на ЗАПИС."
            + " Затова носителят на третото състояние е ПРИЗНАК, а не стойност.");

    BigDecimal wrong = baseOf(withZero).getAmount();
    BigDecimal right = baseOf(slip(BigDecimal.ZERO, STANDING, true)).getAmount();

    assertEquals(0, OWN.compareTo(wrong), "слятото четене дава суровите 300,00");
    assertEquals(0, MOD.compareTo(right), "изявлението дава МОД пода 936,00");
    assertEquals(
        0,
        new BigDecimal("636.00").compareTo(right.subtract(wrong)),
        "⚠️ 936,00 − 300,00 = 636,00 осигурителна база, която досега изчезваше мълчаливо");

    BigDecimal employer = contributionsOn(right.subtract(wrong), EMPLOYER_RATE);
    BigDecimal employee = contributionsOn(right.subtract(wrong), EMPLOYEE_RATE);
    assertEquals(0, new BigDecimal("117.79").compareTo(employer), "работодател, 18,52 % без ТЗПБ");
    assertEquals(0, new BigDecimal("87.64").compareTo(employee), "лице, 13,78 %");
    assertEquals(
        0,
        new BigDecimal("205.43").compareTo(employer.add(employee)),
        "⚠️ 205,43 EUR на месец, НЕДОвнесени — фиксът вдига базата, тоест мърда в ВЯРНАТА посока");
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ЗАВАРЕНИТЕ РЕДОВЕ — НУЛА BACKFILL
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЗАВАРЕН РЕД (непопълнена колона) се държи ТОЧНО както досега")
  void legacyRowsNeedNoBackfill() {
    assertFalse(
        new BgPaySlip().getBgNoOtherPayerIncome(),
        "⚠️ ГЕНЕРИРАНИЯТ GETTER ЗА BOOLEAN ВРЪЩА FALSE, НИКОГА null — и точно затова миграция няма."
            + " false е ТОЧНО днешното поведение, тоест заварената стойност вече Е верният отговор."
            + " Това е разликата с bgLeaveDaysFrozenAt, където дискриминаторът трябваше да е"
            + " nullable, защото разграничаваше „преди/след“.");

    // Заварен фиш: признакът никога не е пипан (null → FALSE), каскадата е непокътната.
    assertEquals(
        0,
        STANDING.compareTo(BgPaySlipResolver.otherPayerInsurableIncome(slip(null, STANDING, null))),
        "заварен ред със стояща стойност → пропада към нея, както винаги");
    assertEquals(
        0,
        new BigDecimal("1200.00")
            .compareTo(
                BgPaySlipResolver.otherPayerInsurableIncome(
                    slip(new BigDecimal("1200.00"), STANDING, null))),
        "заварен ред с месечно надписване → месечното печели, както винаги");
    assertNull(
        BgPaySlipResolver.otherPayerInsurableIncome(slip(null, null, null)),
        "заварен ред без нищо → липса, както винаги");
    assertEquals(
        0,
        OWN.compareTo(baseOf(slip(null, STANDING, null)).getAmount()),
        "и базата на заварения координационен фиш не мърда: 300,00, както преди признака");
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // FAIL-LOUD
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПРИЗНАК + МЕСЕЧНА СУМА > 0 е ОТКАЗ с назована причина")
  void twoContradictoryStatementsForOneMonthAreRefused() {
    String refusal =
        BgPaySlipResolver.refuseContradictoryOtherPayerDeclaration(
            slip(new BigDecimal("1200.00"), STANDING, true));

    assertNotNull(
        refusal,
        "⚠️ Две изявления на лицето за ЕДИН месец, които се изключват. Кодът не бива да предпочете"
            + " едното наум — същият довод като при refuseBothRetroFlags.");
    assertTrue(refusal.contains("1200.00"), "причината назовава числото. Съобщение: " + refusal);
    assertTrue(
        refusal.contains("чл. 4"),
        "причината назовава нормата (НЕВДПОВ чл. 4). Съобщение: " + refusal);
  }

  @Test
  @DisplayName("⚠️ ПРИЗНАК + СТОЯЩА СТОЙНОСТ > 0 НЕ е отказ — това е самият случай")
  void theStandingValueIsNeverAContradiction() {
    assertNull(
        BgPaySlipResolver.refuseContradictoryOtherPayerDeclaration(slip(null, STANDING, true)),
        "⚠️ Пазач и върху стоящата стойност би отказал ВЕРНИЯ фиш: второто правоотношение си стои,"
            + " но този месец не е осигурявало. Признакът съществува точно за този случай.");
    assertNull(
        BgPaySlipResolver.refuseContradictoryOtherPayerDeclaration(
            slip(BigDecimal.ZERO, STANDING, true)),
        "нулата не е изявление (signum() > 0, не != null) и не противоречи на нищо");
    assertNull(
        BgPaySlipResolver.refuseContradictoryOtherPayerDeclaration(
            slip(new BigDecimal("1200.00"), STANDING, false)),
        "без признак няма и противоречие");
    assertNull(BgPaySlipResolver.refuseContradictoryOtherPayerDeclaration(null), "нулев фиш");
  }
}
