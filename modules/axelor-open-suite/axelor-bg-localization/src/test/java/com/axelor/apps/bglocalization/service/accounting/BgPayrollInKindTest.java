package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НАСРЕЩНАТА СМЕТКА НА ПЕРО 630 — по ВИДА на придобивката, която приключва.
 *
 * <p><b>⚠️ ЕДИН РАЗХОД, ТРИ НАСРЕЩНИКА, И ТОВА НЕ Е УСЛОЖНЯВАНЕ.</b> Разходната страна на целия
 * клас 2xx е ЕДНА (6094) — класът се чете като едно число. Насрещната страна на ПРЕДАВАНЕТО обаче
 * зависи от формата: ваучерът е задължение към оператора (409), стоката излиза от склада (304), а
 * застраховката и вноската са задължение към трето лице (401). Сгрешен, насрещникът не разваля нито
 * баланса, нито нетото — той просто оставя склада завишен или създава задължение, което никой няма
 * да плати.
 *
 * <p><b>⚠️ И ЗАТОВА ВСЕКИ ТЕСТ ОТДОЛУ ПРОВЕРЯВА И ЧЕТВЪРТО НЕЩО: ЧЕ 4211 СЕ ЗАНУЛЯВА.</b> Двойката
 * 2xx (начисляване) + 630 (предаване) нето дава Dr 6094 / Cr [натура] и по 4211 не остава нищо от
 * придобивката — ваучерът НЕ става пари в плика. Точно това равенство пада, когато насрещникът е
 * верен, а посоката или сумата — не.
 *
 * <p>⚠️ Класификацията се проверява срещу РЕЧНИКА в {@code
 * BgPayrollAccountCatalogueTest#everySocialLineIsEitherClassifiedOrDeclaredUnclassified}. Тук се
 * мери какво прави постинг планът с нея.
 */
class BgPayrollInKindTest {

  private static final String EMPLOYED = "01";

  /**
   * Заплатата на всеки фиш отдолу — и ЕДИНСТВЕНОТО, което трябва да остане по 4211.
   *
   * <p>⚠️ Свидетелят НЕ е „нула по 4211“, а „точно заплатата“. Нулата щеше да мине и при фиш без
   * заплата, тоест да не доказва, че придобивката се е занулила.
   */
  private static final BigDecimal WAGE = new BigDecimal("1500.00");

  // ── 1 · трите вида ──────────────────────────────────────────────────────

  @Test
  @DisplayName("1 · ваучерът за храна приключва в 409, а не в 401 и не в 304")
  void aFoodVoucherClosesAgainstItsOwnAccount() {
    for (int number : new int[] {210, 211}) {
      BgPayrollPostingPlan.Plan plan = pair(number, "Ваучери за храна", "102.26");

      assertEquals(List.of(), plan.getRefusals(), "перо " + number);
      assertEquals(new BigDecimal("102.26"), amountOn(plan, "40901", false), "перо " + number);
      assertEquals(BigDecimal.ZERO, amountOn(plan, "401", false), "перо " + number);
      assertEquals(BigDecimal.ZERO, amountOn(plan, "304", false), "перо " + number);
      assertEquals(new BigDecimal("102.26"), amountOn(plan, "6094", true), "перо " + number);
      assertTrue(plan.isBalanced());
      assertEquals(
          WAGE, personnelBalance(plan), "по 4211 остава САМО заплатата — придобивката се занулява");
    }
  }

  @Test
  @DisplayName("2 · облеклото приключва в 304 „Стоки“ — то излиза от СКЛАДА")
  void clothingClosesAgainstInventory() {
    for (int number : new int[] {230, 231, 232}) {
      BgPayrollPostingPlan.Plan plan = pair(number, "Облекло", "60.00");

      assertEquals(List.of(), plan.getRefusals(), "перо " + number);
      assertEquals(new BigDecimal("60.00"), amountOn(plan, "304", false), "перо " + number);
      // ⚠️ КОНТРОЛЪТ Е СРЕЩУ 401: облекло, записано като задължение към доставчик, щеше да остави
      // склада завишен И да създаде задължение, което никой няма да плати. И двете салда лъжат, а
      // статията балансира.
      assertEquals(BigDecimal.ZERO, amountOn(plan, "401", false), "перо " + number);
      assertEquals(WAGE, personnelBalance(plan), "перо " + number);
    }
  }

  @Test
  @DisplayName("3 · застраховките и вноските приключват в 401, с аналитичност по придобивка")
  void thirdPartyBenefitsCloseAgainstSuppliers() {
    for (int number : new int[] {200, 201, 202, 203, 204, 205, 245}) {
      BgPayrollPostingPlan.Plan plan = pair(number, "Придобивка №" + number, "30.68");

      assertEquals(List.of(), plan.getRefusals(), "перо " + number);
      assertEquals(new BigDecimal("30.68"), amountOn(plan, "401", false), "перо " + number);
      assertEquals(
          "Придобивка №" + number,
          analyticOn(plan, "401"),
          "редът по 401 трябва да носи ИМЕТО НА ПРИДОБИВКАТА — доставчикът се попълва после");
      assertEquals(WAGE, personnelBalance(plan), "перо " + number);
    }
  }

  // ── 4 · няколко вида на един фиш ────────────────────────────────────────

  @Test
  @DisplayName("4 · три вида придобивка дават ТРИ насрещни реда, всеки със своята сума")
  void threeKindsYieldThreeCounterparts() {
    List<BgPaySlipLine> lines =
        new ArrayList<>(
            List.of(
                wage(100, "Основно възнаграждение", "1500.00"),
                line(210, "Ваучери за храна", "CREDIT", "102.26"),
                line(230, "Работно облекло", "CREDIT", "60.00"),
                line(245, "Задължителни застраховки", "CREDIT", "17.74"),
                line(630, "Предадено в натура", "DEBIT", "180.00")));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    assertTrue(plan.isBalanced());
    // Разходът е ЕДИН ред — класът е един.
    assertEquals(new BigDecimal("180.00"), amountOn(plan, "6094", true));
    // Насрещниците са ТРИ, всеки със своята сума. 102,26 + 60,00 + 17,74 = 180,00 — точно.
    assertEquals(new BigDecimal("102.26"), amountOn(plan, "40901", false));
    assertEquals(new BigDecimal("60.00"), amountOn(plan, "304", false));
    assertEquals(new BigDecimal("17.74"), amountOn(plan, "401", false));
    // А по 4211 остава САМО заплатата.
    assertEquals(new BigDecimal("1500.00"), personnelBalance(plan));
    assertEquals(BgPaySlipResolver.netTotal(lines), personnelBalance(plan));
  }

  @Test
  @DisplayName("4а · две придобивки от трети лица дават ДВА реда по 401, а не един сборен")
  void twoThirdPartyBenefitsDoNotMergeIntoOneLine() {
    List<BgPaySlipLine> lines =
        new ArrayList<>(
            List.of(
                wage(100, "Основно", "1500.00"),
                line(202, "Доброволно здравно осигуряване", "CREDIT", "20.00"),
                line(245, "Задължителни застраховки", "CREDIT", "12.00"),
                line(630, "Предадено в натура", "DEBIT", "32.00")));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    Map<String, String> byAnalytic = new LinkedHashMap<>();
    for (BgPayrollPosting p : plan.getPostings()) {
      if ("401".equals(p.getAccountCode())) {
        byAnalytic.put(p.getAnalytic(), p.getAmount().toPlainString());
      }
    }
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("Доброволно здравно осигуряване", "20.00");
    expected.put("Задължителни застраховки", "12.00");
    // ⚠️ ДВЕ ПРИДОБИВКИ, ДВА РАЗЛИЧНИ ДОСТАВЧИКА — здравноосигурително дружество и застраховател.
    // Слети в един кредит на 401, салдото щеше да е несверимо с нито един от двамата.
    assertEquals(expected, byAnalytic);
    assertEquals(new BigDecimal("32.00"), amountOn(plan, "401", false), "сборът им е пълният 630");
  }

  @Test
  @DisplayName("5 · ЕДНА придобивка и ЧАСТИЧНО предаване — цялото 630 отива при нея")
  void aPartialHandoverWithOneBenefitGoesEntirelyToIt() {
    // Част ваучер, част пари — нормален случай, и кандидатът е само един.
    List<BgPaySlipLine> lines =
        new ArrayList<>(
            List.of(
                wage(100, "Основно", "1500.00"),
                line(210, "Ваучери за храна", "CREDIT", "102.26"),
                line(630, "Предадено в натура", "DEBIT", "60.00")));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    assertEquals(new BigDecimal("60.00"), amountOn(plan, "40901", false));
    // Остатъкът от 42,26 наистина е в плика — той НЕ е предаден в натура.
    assertEquals(new BigDecimal("1542.26"), personnelBalance(plan));
    assertEquals(BgPaySlipResolver.netTotal(lines), personnelBalance(plan));
  }

  @Test
  @DisplayName("5а · НЯКОЛКО придобивки и частично предаване се ОТКАЗВА, не се разпределя")
  void aPartialHandoverWithSeveralBenefitsIsRefused() {
    List<BgPaySlipLine> lines =
        new ArrayList<>(
            List.of(
                wage(100, "Основно", "1500.00"),
                line(210, "Ваучери за храна", "CREDIT", "100.00"),
                line(230, "Работно облекло", "CREDIT", "50.00"),
                line(630, "Предадено в натура", "DEBIT", "120.00")));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(1, plan.getRefusals().size(), plan.getRefusals().toString());
    assertTrue(plan.getRefusals().get(0).startsWith("перо 630"), plan.getRefusals().get(0));
    assertTrue(plan.getRefusals().get(0).contains("150.00"), plan.getRefusals().get(0));
    assertTrue(plan.getRefusals().get(0).contains("120.00"), plan.getRefusals().get(0));
    assertFalse(plan.isPostable());
    // ⚠️ И ТУК Е ТИХИЯТ ДЕФЕКТ, СРЕЩУ КОЙТО Е ОТКАЗЪТ: пропорционалното разпределение (80/40) щеше
    // да даде НАПЪЛНО БАЛАНСИРАНА статия с измислени числа по 409 и 304.
    assertTrue(plan.isBalanced());
  }

  // ── 6 · fail-loud ───────────────────────────────────────────────────────

  @Test
  @DisplayName("6 · некласифицируема придобивка + 630 ОТМЕНЯ статията и се назовава поименно")
  void anUnclassifiableBenefitCancelsTheWholeArticle() {
    int[] unclassified = {240, 250, 260, 261, 262, 270};
    List<String> failures = new ArrayList<>();
    for (int number : unclassified) {
      BgPayrollPostingPlan.Plan plan = pair(number, "Придобивка №" + number, "40.00");

      if (plan.isPostable()) {
        failures.add(number + ": статия се строи въпреки неясния вид");
      }
      if (plan.getRefusals().size() != 1
          || !plan.getRefusals().get(0).startsWith("перо " + number)) {
        failures.add(number + ": отказът не назовава перото — " + plan.getRefusals());
        continue;
      }
      if (!plan.getRefusals().get(0).contains("630")) {
        failures.add(number + ": причината не казва, че 630 налага избора — " + plan.getRefusals());
      }
      // ⚠️ ОСТАТЪКЪТ БАЛАНСИРА — тоест балансът не може да е свидетелят, а тихото попадение в
      // 401 щеше да изглежда като напълно сметнат документ.
      if (!plan.isBalanced()) {
        failures.add(number + ": остатъкът не балансира — предположението на класа е грешно");
      }
      // И насрещник НЕ се е появил по никоя от трите сметки.
      for (String code : new String[] {"40901", "304", "401"}) {
        if (amountOn(plan, code, false).signum() != 0) {
          failures.add(number + ": появи се насрещник по " + code);
        }
      }
    }
    assertEquals(List.of(), failures);
  }

  @Test
  @DisplayName("6а · КОНТРОЛЕН ОПИТ: същите пера БЕЗ 630 се осчетоводяват безпроблемно")
  void theSameBenefitsWithoutTheHandoverLinePostFine() {
    // ⚠️ БЕЗ ТОЗИ ТЕСТ ОТКАЗЪТ ОТГОРЕ БИ БИЛ ПРЕКАЛЕН. Видът придобивка е нужен ЕДИНСТВЕНО за да
    // се избере насрещникът на предаването. Без перо 630 придобивката е в ПАРИ и е еднозначна:
    // Dr 6094 / Cr 4211. Прочетено по-широко, правилото щеше да направи безплатната храна и
    // организирания превоз неосчетоводими изобщо.
    for (int number : new int[] {240, 250, 260, 261, 262, 270}) {
      List<BgPaySlipLine> lines =
          new ArrayList<>(
              List.of(
                  wage(100, "Основно", "1500.00"),
                  line(number, "Придобивка №" + number, "CREDIT", "40.00")));

      BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

      assertEquals(List.of(), plan.getRefusals(), "перо " + number);
      assertTrue(plan.isPostable(), "перо " + number);
      assertEquals(new BigDecimal("40.00"), amountOn(plan, "6094", true), "перо " + number);
      assertEquals(new BigDecimal("1540.00"), personnelBalance(plan), "перо " + number);
    }
  }

  @Test
  @DisplayName("7 · 630 срещу придобивка ИЗРИЧНО В ПАРИ е противоречие и се отказва")
  void aHandoverAgainstACashBenefitIsRefused() {
    for (int number : new int[] {220, 222, 224, 271}) {
      BgPayrollPostingPlan.Plan plan = pair(number, "Помощ №" + number, "100.00");

      assertEquals(1, plan.getRefusals().size(), "перо " + number + ": " + plan.getRefusals());
      assertTrue(plan.getRefusals().get(0).startsWith("перо 630"), plan.getRefusals().get(0));
      assertTrue(plan.getRefusals().get(0).contains("В ПАРИ"), plan.getRefusals().get(0));
      assertFalse(plan.isPostable());
    }
  }

  @Test
  @DisplayName("8 · 630 без НИТО ЕДНО перо от 2xx се отказва — няма какво да приключва")
  void aHandoverWithoutAnyBenefitIsRefused() {
    List<BgPaySlipLine> lines =
        new ArrayList<>(
            List.of(
                wage(100, "Основно", "1500.00"),
                line(630, "Предадено в натура", "DEBIT", "40.00")));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(1, plan.getRefusals().size());
    assertTrue(plan.getRefusals().get(0).startsWith("перо 630"), plan.getRefusals().get(0));
    assertTrue(plan.getRefusals().get(0).contains("2xx"), plan.getRefusals().get(0));
    assertFalse(plan.isPostable());
  }

  @Test
  @DisplayName("8а · ДВА пъти 630 на един фиш се отказва — перото е DERIVED и е едно")
  void twoHandoverLinesAreRefused() {
    List<BgPaySlipLine> lines =
        new ArrayList<>(
            List.of(
                wage(100, "Основно", "1500.00"),
                line(210, "Ваучери", "CREDIT", "50.00"),
                line(630, "Предадено в натура", "DEBIT", "30.00"),
                line(630, "Предадено в натура (второ)", "DEBIT", "20.00")));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(1, plan.getRefusals().size());
    assertTrue(plan.getRefusals().get(0).contains("2 пера 630"), plan.getRefusals().get(0));
    assertFalse(plan.isPostable());
  }

  @Test
  @DisplayName("8б · 630 с обърнат знак (CREDIT) се отказва — то ПРИКЛЮЧВА, не начислява")
  void aHandoverWithTheWrongSignIsRefused() {
    List<BgPaySlipLine> lines =
        new ArrayList<>(
            List.of(
                wage(100, "Основно", "1500.00"),
                line(210, "Ваучери", "CREDIT", "50.00"),
                line(630, "Предадено в натура", "CREDIT", "50.00")));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(1, plan.getRefusals().size());
    assertTrue(plan.getRefusals().get(0).startsWith("перо 630"), plan.getRefusals().get(0));
    assertTrue(plan.getRefusals().get(0).contains("DEBIT"), plan.getRefusals().get(0));
    assertFalse(plan.isPostable());
  }

  // ── 9 · насрещниците не носят партньора на статията ─────────────────────

  @Test
  @DisplayName("9 · трите насрещника се пишат БЕЗ партньора на фиша, останалите — с него")
  void theInKindCounterpartsCarryNoPartner() {
    // ⚠️ 401 е ЕДИНСТВЕНАТА сметка от набора с useForPartnerBalance = true. Записан с партньора на
    // статията (ЛИЦЕТО), кредитът щеше да покаже служителя като доставчик, на когото се дължи
    // премията на застрахователя.
    for (String code : new String[] {"40901", "304", "401"}) {
      assertTrue(BgPayrollAccount.isPartnerless(code), code + " трябва да е без партньор");
    }
    for (String code : new String[] {"4211", "4214", "6094", "4551", "709"}) {
      assertFalse(BgPayrollAccount.isPartnerless(code), code + " носи партньора на статията");
    }
  }

  // ── помощници ───────────────────────────────────────────────────────────

  /** Фиш със заплата, ЕДНА придобивка от 2xx и пълното ѝ предаване в натура. */
  private static BgPayrollPostingPlan.Plan pair(int number, String name, String amount) {
    List<BgPaySlipLine> lines =
        new ArrayList<>(
            List.of(
                wage(100, "Основно възнаграждение", "1500.00"),
                line(number, name, "CREDIT", amount),
                line(630, "Предадено в натура", "DEBIT", amount)));
    return BgPayrollPostingPlan.of(lines, EMPLOYED);
  }

  /** Салдото на 4211: кредит минус дебит. */
  private static BigDecimal personnelBalance(BgPayrollPostingPlan.Plan plan) {
    return amountOn(plan, BgPayrollAccount.PERSONNEL_NET_WAGE, false)
        .subtract(amountOn(plan, BgPayrollAccount.PERSONNEL_NET_WAGE, true));
  }

  private static BigDecimal amountOn(BgPayrollPostingPlan.Plan plan, String code, boolean debit) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BgPayrollPosting p : plan.getPostings()) {
      if (p.getAccountCode().equals(code) && p.isDebit() == debit) {
        sum = sum.add(p.getAmount());
      }
    }
    return sum;
  }

  private static String analyticOn(BgPayrollPostingPlan.Plan plan, String code) {
    for (BgPayrollPosting p : plan.getPostings()) {
      if (p.getAccountCode().equals(code) && !p.isDebit()) {
        return p.getAnalytic();
      }
    }
    return null;
  }

  private static BgPaySlipLine wage(int number, String name, String amount) {
    BgPaySlipLine line = line(number, name, "CREDIT", amount);
    line.setBgFormsGrossWage(true);
    return line;
  }

  private static BgPaySlipLine line(int number, String name, String sign, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setName(name);
    line.setBgSign(sign);
    line.setBgAmount(new BigDecimal(amount));
    return line;
  }
}
