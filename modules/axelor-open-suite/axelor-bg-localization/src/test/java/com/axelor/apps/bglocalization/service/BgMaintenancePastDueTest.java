package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.db.BgGarnishmentScale;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛОВАТА ПОЛОВИНА на разграничението ТЕКУЩА срещу ПРОСРОЧЕНА издръжка (ГПК чл. 446 ал. 4).
 *
 * <p><b>Какво пази.</b> Че двата входа на {@code assess} влизат на РАЗЛИЧНИ места в сметката:
 * текущата издръжка (перо 830) намалява основата, върху която таванът се смята, а издръжката за
 * МИНАЛО ВРЕМЕ (перо 831) се бори за място ВЪТРЕ в тавана — по ал. 1, като запор.
 *
 * <p><b>═══ НОРМАТА, ДОСЛОВНО ═══</b> Чл. 446 ал. 4, сверено срещу официалния ГПК
 * (justice.government.bg) на 24.08.2026 г.: „Ограниченията по ал. 1 не се отнасят до задължения за
 * издръжка. В тези случаи присъдената сума за издръжка се удържа изцяло, а удръжките по ал. 1 за
 * другите задължения на осъдения И ЗА ЗАДЪЛЖЕНИЯ ЗА ИЗДРЪЖКА ЗА МИНАЛО ВРЕМЕ се правят ВЪРХУ
 * ОСТАТЪКА от всичките му доходи.“
 *
 * <p>⚠️ Алинеята прави разграничението ВЪТРЕ В СЕБЕ СИ: „присъдената сума … изцяло“ е ТЕКУЩАТА, а
 * „задължения за издръжка за минало време“ стоят в изброяването с другите задължения по ал. 1.
 * Тоест редът тук не е избор на теста, а буквата на нормата.
 *
 * <p><b>⚠️ РЕДЪТ ВЪТРЕ В ТАВАНА (просрочената ПРЕДИ запора) НОСИ ДРУГ ПРОВЕНАНС</b> и той е
 * по-слаб. Основание: ЗЗД чл. 136 ал. 1 т. 5 — вземанията за издръжка са привилегировани, а запорът
 * за обикновено вземане не е сред изброените. ⚠️ Точката е взета от caselaw източник
 * (gpk.caselaw.bg) и НЕ е сверена срещу официалния текст на ЗЗД в тази среда — цитира се с този
 * disclaimer. Сверен е само МЕХАНИЗМЪТ: ГПК чл. 460, дословно — „…съдебният изпълнител извършва
 * разпределение, като най-напред отделя суми за изплащане на вземанията, които се ползват с право
 * на предпочтително удовлетворение. Остатъкът се разпределя между другите вземания по
 * съразмерност.“ (официален ГПК, 24.08.2026 г.) Тя дава реда, но не и списъка кои вземания са
 * привилегировани.
 *
 * <p><b>⚠️ ЗЛАТНИТЕ ЧИСЛА СА КОНСТРУИРАНИ.</b> Реален случай с просрочена издръжка в данните НЯМА —
 * записано, за да не се чете като сверено срещу фиш. Числата са независимо пресметнати при МРЗ
 * 620,20 и без деца, а скалата и МРЗ идват ОТ SEED ФАЙЛОВЕТЕ, не от числа в теста.
 *
 * <p><b>Какво НЕ е тук.</b> Прочитът на перата (830 отделно от 831) и текстът на отказа са на
 * пазача — {@code BgGarnishmentGuardTest}. Доставеният ред PLT-831 и краят-до-край срещу базата са
 * на {@code BgMaintenancePastDueDbTest}. Тук се вика само чистият {@code assess}.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 */
class BgMaintenancePastDueTest {

  private static final boolean NO_CHILDREN = false;

  /** МРЗ за 2026 г. — ОТ SEED ФАЙЛА. ПМС № 243 от 13.11.2025 г., 620,20 EUR. */
  private static BigDecimal minWage() {
    List<String> header = BgSeed.quotedHeader("bg_payroll_parameter.csv");
    for (String[] row : BgSeed.quotedRows("bg_payroll_parameter.csv")) {
      if ("MIN_WAGE".equals(BgSeed.col(header, row, "code"))) {
        return new BigDecimal(BgSeed.col(header, row, "paramValue").trim());
      }
    }
    fail("параметър MIN_WAGE липсва в bg_payroll_parameter.csv");
    return null;
  }

  /**
   * Скалата — ОТ SEED ФАЙЛА, през четеца на пазача.
   *
   * <p>⚠️ БЕЗ ТРЕТО КОПИЕ на прочита. {@code BgGarnishmentGuardTest#scaleFromSeed} чете същите шест
   * реда от {@code bg_garnishment_scale.csv}; собствен четец тук би се разминал с него при първата
   * промяна в seed-а и разминаването щеше да мери себе си.
   */
  private static List<BgGarnishmentScale> scale() {
    return BgGarnishmentGuardTest.scaleFromSeed();
  }

  private static BigDecimal bgn(String amount) {
    return new BigDecimal(amount);
  }

  /**
   * КОЛКО ОСТАВА НА ЛИЦЕТО след удръжките по закон — основата минус двете разрешени.
   *
   * <p>⚠️ Всяко твърдение тук носи и това число, не само удържимото. „Удържимо 650,00“ не се
   * проверява с поглед, а „остава 650,00, тоест над МРЗ 620,20“ се проверява — и точно то е
   * свидетелят, който старото поведение вали.
   */
  private static BigDecimal remaining(BgGarnishmentOutcome outcome) {
    return outcome
        .base
        .subtract(outcome.allowedPastDueMaintenance)
        .subtract(outcome.allowedGarnishment);
  }

  private static BgGarnishmentOutcome assess(
      String net, String current, String pastDue, String garnishment, String consent) {
    return BgGarnishmentResolver.assess(
        bgn(net),
        bgn(current),
        bgn(pastDue),
        bgn(garnishment),
        bgn(consent),
        minWage(),
        NO_CHILDREN,
        scale());
  }

  // ═══ 1 · ЗЛАТЕН 1 — И ДВЕТЕ СЕ ПОБИРАТ ════════════════════════════════════

  /**
   * Нето 1500,00, текуща издръжка 200,00, просрочена 300,00, запор 500,00.
   *
   * <p>Текущата излиза цяла → основа 1300,00. Тя е между 2× и 4× МРЗ (1240,40 и 2480,80), тоест
   * праг 2 без деца → 1/2 от 1300,00 = 650,00, а подът по ал. 3 разрешава 679,80 — обвързва дялът.
   * Просрочената взима 300,00, за запора остават 350,00, а 150,00 от него не се събират този месец.
   */
  @Test
  @DisplayName(
      "ЗЛАТЕН 1 · нето 1500 − текуща 200 → таван 650: просрочена 300, запор 350, остава 650")
  void bothThePastDueMaintenanceAndTheGarnishmentFitUnderTheCeiling() {
    BgGarnishmentOutcome outcome = assess("1500.00", "200.00", "300.00", "500.00", "0.00");

    assertNull(outcome.refusal, String.valueOf(outcome.refusal));
    assertEquals(
        0,
        bgn("1300.00").compareTo(outcome.base),
        "⚠️ основата е нетото минус САМО текущата издръжка. Намерено: " + outcome.base);
    assertEquals(0, bgn("650.00").compareTo(outcome.limit), "1/2 от 1300,00 по праг 2");
    assertEquals(
        0,
        bgn("300.00").compareTo(outcome.allowedPastDueMaintenance),
        "просрочената се побира цяла под тавана");
    assertEquals(
        0,
        bgn("350.00").compareTo(outcome.allowedGarnishment),
        "за запора остава таванът МИНУС просрочената: 650,00 − 300,00");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(outcome.uncollectedPastDueMaintenance),
        "от издръжката за минало време не остава несъбрано");
    assertEquals(
        0,
        bgn("150.00").compareTo(outcome.uncollectedGarnishment),
        "⚠️ 500,00 − 350,00 остават ИЗИСКУЕМИ, не отпадат");
    assertEquals(
        0, bgn("150.00").compareTo(outcome.uncollected), "сборът на несъбраното — едно число");
    assertEquals(
        0,
        bgn("650.00").compareTo(remaining(outcome)),
        "ОСТАВА на лицето: 1300,00 − 300,00 − 350,00. Намерено: " + remaining(outcome));
  }

  // ═══ 2 · ЗЛАТЕН 2 — ПРОСРОЧЕНАТА ИЗЯЖДА ЦЕЛИЯ ТАВАН ═══════════════════════

  /**
   * ⚠️ СВИДЕТЕЛЯТ НА ПОДА. Същият фиш с просрочена 700,00: тя изяжда целия таван, за запора остава
   * нула — и въпреки това на лицето остават 650,00, тоест НАД МРЗ 620,20. Точно това старото
   * поведение не правеше.
   */
  @Test
  @DisplayName("ЗЛАТЕН 2 · просрочена 700 изяжда тавана: минало 650, запор 0, остава 650 (над МРЗ)")
  void thePastDueMaintenanceIsClampedToTheCeilingAndTheFloorHolds() {
    BgGarnishmentOutcome outcome = assess("1500.00", "200.00", "700.00", "500.00", "0.00");

    assertNull(
        outcome.refusal,
        "⚠️ КЛАМПВА, НЕ ОТКАЗВА: просрочената издръжка е съдебен акт, като запора — не съгласие."
            + " Намерено: "
            + outcome.refusal);
    assertEquals(0, bgn("1300.00").compareTo(outcome.base), "основата НЕ се мени от просрочената");
    assertEquals(0, bgn("650.00").compareTo(outcome.limit), "нито таванът");
    assertEquals(
        0,
        bgn("650.00").compareTo(outcome.allowedPastDueMaintenance),
        "просрочената се клампва ДО тавана, не над него");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(outcome.allowedGarnishment),
        "⚠️ за запора не остава нищо — просрочената издръжка е преди него");
    assertEquals(0, bgn("50.00").compareTo(outcome.uncollectedPastDueMaintenance));
    assertEquals(0, bgn("500.00").compareTo(outcome.uncollectedGarnishment));
    assertEquals(
        0,
        bgn("550.00").compareTo(outcome.uncollected),
        "несъбрано общо: 50,00 от издръжката + 500,00 от запора");
    assertEquals(
        0,
        bgn("650.00").compareTo(remaining(outcome)),
        "ОСТАВА на лицето. Намерено: " + remaining(outcome));
    assertTrue(
        remaining(outcome).compareTo(minWage()) >= 0,
        "⚠️ ПОДЪТ ПО АЛ. 3 ДЪРЖИ: остават "
            + remaining(outcome)
            + " срещу МРЗ "
            + minWage()
            + ". Обвързващото тук е дялът (1/2 = 650,00), а подът разрешава 679,80 — тоест лицето"
            + " остава над МРЗ по силата на скалата, а не по случайност.");
  }

  // ═══ 3 · FAIL-FIRST — СЛЯТА, ПРОСРОЧЕНАТА СВАЛЯ ПОД МРЗ ═══════════════════

  /**
   * ⚠️ ТВЪРДЕНИЕТО, КОЕТО ЧЕРВЕНЕЕ БЕЗ РАЗГРАНИЧЕНИЕТО. Подадена като ТЕКУЩА (ако {@code assess}
   * получава ЕДНО число за издръжка), просрочената излиза ЦЯЛА: основата пада на 600,00, таванът
   * става 0,00 и на лицето остават 600,00 — ПОД МРЗ 620,20.
   *
   * <p>Закрилата на ал. 4 е за присъдената ТЕКУЩА сума; просрочената я няма. Разликата е 50,00 лв.
   * от джоба на длъжника ВЪВ ФИШ, на който нищо не изглежда сгрешено — и това е целият дефект.
   */
  @Test
  @DisplayName("⚠️ FAIL-FIRST · слята с текущата, просрочената оставя 600,00 — ПОД МРЗ 620,20")
  void mergedIntoTheCurrentMaintenanceThePastDueBreaksTheMinimumWageFloor() {
    // СТАРОТО поведение: 200,00 текуща + 700,00 просрочена, подадени като едно число.
    BgGarnishmentOutcome merged = assess("1500.00", "900.00", "0.00", "500.00", "0.00");
    // НОВОТО: същите пари, разграничени.
    BgGarnishmentOutcome split = assess("1500.00", "200.00", "700.00", "500.00", "0.00");

    assertEquals(
        0,
        bgn("600.00").compareTo(merged.base),
        "слято основата пада на 1500,00 − 900,00. Намерено: " + merged.base);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(merged.limit),
        "⚠️ основа под МРЗ прави тавана нула — тоест запор не се удържа изобщо");
    assertEquals(
        0,
        bgn("600.00").compareTo(remaining(merged)),
        "слято на лицето остават 600,00. Намерено: " + remaining(merged));
    assertTrue(
        remaining(merged).compareTo(minWage()) < 0,
        "⚠️ ТОВА Е ДЕФЕКТЪТ: 600,00 < МРЗ 620,20, а под МРЗ има право да сваля САМО текущата"
            + " издръжка (ал. 4). Ако този ред мълчи, разграничението е изчезнало от кода.");
    assertEquals(
        0,
        bgn("650.00").compareTo(remaining(split)),
        "разграничено на лицето остават 650,00. Намерено: " + remaining(split));
    assertTrue(
        remaining(split).compareTo(minWage()) >= 0,
        "разграничението спазва пода: " + remaining(split) + " ≥ " + minWage());
    assertEquals(
        0,
        bgn("50.00").compareTo(remaining(split).subtract(remaining(merged))),
        "⚠️ СВИДЕТЕЛЯТ, В ПАРИ: 650,00 срещу 600,00 — 50,00 лв. месечно и нарушен под");
  }

  // ═══ 4 · ПРОСРОЧЕНАТА НЕ НАМАЛЯВА ОСНОВАТА ════════════════════════════════

  /**
   * ⚠️ ЦЯЛОТО ПРАВИЛО В ЕДНО ТВЪРДЕНИЕ: просрочената влиза ВЪТРЕ в тавана, не ПРЕДИ него. Вадена от
   * нетото преди тавана, тя би заобиколила пода по ал. 3, който тъкмо трябва да я ограничи — и
   * основата (а с нея и прагът по скалата) би зависела от нея.
   */
  @Test
  @DisplayName(
      "⚠️ основата и таванът НЕ зависят от просрочената: 1300,00 и 650,00 при 0, 300 и 700")
  void thePastDueMaintenanceNeverReducesTheBaseOnWhichTheCeilingIsComputed() {
    for (String pastDue : new String[] {"0.00", "300.00", "700.00", "5000.00"}) {
      BgGarnishmentOutcome outcome = assess("1500.00", "200.00", pastDue, "0.00", "0.00");

      assertEquals(
          0,
          bgn("1300.00").compareTo(outcome.base),
          "при просрочена " + pastDue + " основата трябва да е 1300,00. Намерено: " + outcome.base);
      assertEquals(
          0,
          bgn("650.00").compareTo(outcome.limit),
          "при просрочена " + pastDue + " таванът трябва да е 650,00. Намерено: " + outcome.limit);
      assertTrue(
          outcome.allowedPastDueMaintenance.compareTo(outcome.limit) <= 0,
          "⚠️ разрешеното от просрочената НИКОГА не е над тавана. Намерено: "
              + outcome.allowedPastDueMaintenance);
    }
  }

  // ═══ 5 · РЕДЪТ ВЪТРЕ В ТАВАНА ═════════════════════════════════════════════

  /**
   * ⚠️ РЕДЪТ МЕСТИ ПАРИ, И ЗАТОВА ИМА СВОЕ ТВЪРДЕНИЕ. При таван 650,00, просрочена 700,00 и запор
   * 500,00 издръжка-първо дава 650,00/0,00, а запор-първо би дало 150,00/500,00 — същите удръжки,
   * различен получател. Основание: ЗЗД чл. 136 ал. 1 т. 5 (⚠️ несверено срещу официален ЗЗД в тази
   * среда — виж javadoc-а на класа).
   */
  @Test
  @DisplayName("⚠️ РЕД · издръжката за минало време е ПРЕДИ запора: 650/0, а не 150/500")
  void insideTheCeilingThePastDueMaintenanceIsSatisfiedBeforeTheGarnishment() {
    BgGarnishmentOutcome outcome = assess("1500.00", "200.00", "700.00", "500.00", "0.00");

    assertEquals(
        0,
        bgn("650.00").compareTo(outcome.allowedPastDueMaintenance),
        "издръжката за минало време взима тавана първа");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(outcome.allowedGarnishment),
        "⚠️ ако тук се появи 500,00, редът се е обърнал и запорът е изпреварил издръжката");
    assertEquals(
        0,
        outcome.allowedPastDueMaintenance.add(outcome.allowedGarnishment).compareTo(outcome.limit),
        "двете заедно изчерпват тавана точно — нито над, нито под него");
  }

  // ═══ 6 · КТ ЧЛ. 272 АЛ. 2 — ДОБРОВОЛНОТО ОТКАЗВА ══════════════════════════

  /**
   * ⚠️ ДОБРОВОЛНАТА УДРЪЖКА НЕ СЕ КЛАМПВА МЪЛЧАЛИВО и когато над тавана я изкарва издръжка за
   * минало време. Гъвкавото е съгласието — заемът и глобата, — а не съдебният акт. Отказът трябва
   * да назове И перото 831, инак операторът маха от запора сума, която идва от издръжката.
   */
  @Test
  @DisplayName("⚠️ КТ 272 · просрочена 400 + заем 300 над таван 650 → ОТКАЗ, който назовава 831")
  void consentDeductionsOnTopOfPastDueMaintenanceAreRefusedWithANamedReason() {
    BgGarnishmentOutcome outcome = assess("1500.00", "200.00", "400.00", "0.00", "300.00");

    assertNotNull(outcome.refusal, "400,00 + 300,00 = 700,00 надхвърлят 650,00");
    assertTrue(outcome.refusal.contains("272"), outcome.refusal);
    assertTrue(outcome.refusal.contains("650.00"), "таванът. " + outcome.refusal);
    assertTrue(outcome.refusal.contains("700.00"), "сборът. " + outcome.refusal);
    assertTrue(
        outcome.refusal.contains("831"),
        "⚠️ отказът трябва да каже колко е ограничено по перо 831. " + outcome.refusal);
    assertTrue(
        outcome.refusal.contains("300.00"),
        "⚠️ и колко идва от доброволното — инак не се знае какво да падне. " + outcome.refusal);
  }

  /** Просрочена издръжка, която сама се побира заедно с доброволното → без отказ. */
  @Test
  @DisplayName("просрочена 300 + заем 300 = 600 под таван 650 → без отказ")
  void aPastDueMaintenanceThatFitsTogetherWithTheConsentPasses() {
    BgGarnishmentOutcome outcome = assess("1500.00", "200.00", "300.00", "0.00", "300.00");

    assertNull(outcome.refusal, String.valueOf(outcome.refusal));
    assertEquals(0, BigDecimal.ZERO.compareTo(outcome.uncollected), "нищо не се клампва");
  }

  // ═══ 7 · ОТРИЦАТЕЛЕН ВХОД НЕ ВДИГА ТАВАНА ═════════════════════════════════

  /**
   * ⚠️ СГРЕШЕН ЗНАК НА ЕДНО ПЕРО НЕ БИВА ДА РАЗРЕШАВА УДЪРЖАНЕ НАД ЗАКРИЛАТА. Прочетена буквално,
   * отрицателна просрочена издръжка би дала на запора {@code limit − (−100) = 750,00} — тоест над
   * тавана, който чл. 447 прави неотменяем.
   */
  @Test
  @DisplayName("⚠️ отрицателна просрочена издръжка НЕ вдига тавана за запора над 650,00")
  void aNegativePastDueMaintenanceDoesNotRaiseTheCeilingForTheGarnishment() {
    BgGarnishmentOutcome outcome = assess("1500.00", "200.00", "-100.00", "900.00", "0.00");

    assertEquals(
        0,
        bgn("650.00").compareTo(outcome.allowedGarnishment),
        "запорът се клампва до целия таван, но НЕ над него. Намерено: "
            + outcome.allowedGarnishment);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(outcome.allowedPastDueMaintenance),
        "отрицателното не се удържа и не се връща през фиша");
  }
}
