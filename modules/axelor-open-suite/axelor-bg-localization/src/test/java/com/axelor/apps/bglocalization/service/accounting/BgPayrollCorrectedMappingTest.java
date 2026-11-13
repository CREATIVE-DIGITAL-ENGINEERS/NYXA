package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContributionFund;
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
 * ПРИСВОЯВАНИЯТА, КОИТО СЧЕТОВОДНОТО СВЕРЯВАНЕ ПОПРАВИ ИЛИ ПОТВЪРДИ — по едно твърдение на всяко.
 *
 * <p><b>⚠️ ОТДЕЛЕН КЛАС ОТ ЗЛАТНАТА СТАТИЯ, И ТОВА НЕ Е ПОДРЕДБА.</b> {@code
 * BgPayrollPostingModelTest} мери ЕДИН пълен фиш и двата независими свидетеля върху него; той
 * доказва, че моделът е вътрешно съгласуван. Тук се мери друго — че всяко ОТДЕЛНО присвояване е
 * това, което счетоводителят потвърди, — и то по едно перо наведнъж, за да пада поименно. Слети в
 * един тест, двете щяха да си пречат: разместено присвояване вътре в пълния фиш пада като „статията
 * има други сметки“, без да казва кое перо.
 *
 * <p>⚠️ ЧЕТИРИТЕ, КОИТО СЕ ПРЕМЕСТИХА (а не се добавиха), СА ПАЗЕНИ И ОТ ОБРАТНАТА СТРАНА: тестът
 * твърди не само „Безработица е 4554“, но и „Безработица НЕ Е 4555“. Първото минава и при план,
 * който праща всичко в 4554; второто пада точно тогава.
 */
class BgPayrollCorrectedMappingTest {

  private static final String EMPLOYED = "01";

  // ── 1 · разчетите по фонд след преномерирането ───────────────────────────

  @Test
  @DisplayName("1 · Безработица → 4554/6054, ТЗПБ → 4555/6055, ГВРС → 4556/6056")
  void theThreeShiftedFundsPointAtTheirNewAccounts() {
    Map<String, String[]> expected = new LinkedHashMap<>();
    expected.put("UNEMPLOYMENT", new String[] {"4554", "6054"});
    expected.put("TZPB", new String[] {"4555", "6055"});
    expected.put("GVRS", new String[] {"4556", "6056"});

    List<String> wrong = new ArrayList<>();
    for (Map.Entry<String, String[]> entry : expected.entrySet()) {
      String fund = entry.getKey();
      String liability = BgPayrollAccount.liabilityForFund(fund);
      String expense = BgPayrollAccount.employerExpenseForFund(fund);
      if (!entry.getValue()[0].equals(liability)) {
        wrong.add(fund + " разчет: " + liability + " вместо " + entry.getValue()[0]);
      }
      if (!entry.getValue()[1].equals(expense)) {
        wrong.add(fund + " разход: " + expense + " вместо " + entry.getValue()[1]);
      }
    }
    assertEquals(List.of(), wrong);

    // ⚠️ И СТАРИТЕ НОМЕРА СА ЗАБРАНЕНИ ПОИМЕННО. Без това твърдение тестът минава и при план,
    // който е забравил да мести — стига да съвпадне с новия по случайност на един фонд.
    assertNotEquals("4555", BgPayrollAccount.liabilityForFund("UNEMPLOYMENT"));
    assertNotEquals("4556", BgPayrollAccount.liabilityForFund("TZPB"));
    assertNotEquals("4557", BgPayrollAccount.liabilityForFund("GVRS"));
  }

  @Test
  @DisplayName("2 · УПФ и ППФ се сливат в ЕДНО салдо по 4553 и един разход по 6053")
  void bothSupplementaryPensionFundsLandInOneBalance() {
    List<BgPaySlipLine> lines =
        List.of(
            wage(100, "Основно възнаграждение", "2000.00"),
            contribution(510, "УПФ · осигурител", "UPF", "CREDIT", "56.00"),
            contribution(511, "УПФ · лице", "UPF", "DEBIT", "44.00"),
            contribution(512, "ППФ · осигурител", "PPF", "CREDIT", "140.00"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    // 56,00 + 44,00 + 140,00 — ЕДНО салдо, не две.
    assertEquals(new BigDecimal("240.00"), amountOn(plan, "4553", false));
    assertEquals(BigDecimal.ZERO, amountOn(plan, "4554", false), "ППФ няма СВОЙ разчет");
    assertEquals(new BigDecimal("196.00"), amountOn(plan, "6053", true), "УПФ + ППФ, един разход");
    assertTrue(plan.isBalanced());
  }

  // ── 3 · социалните разходи ───────────────────────────────────────────────

  @Test
  @DisplayName("3 · цялата група 2xx отива в 6094, а не в 609 и не в 6042 „за СБКО“")
  void everySocialExpenseLineLandsInItsOwnAccount() {
    // ⚠️ ЦЕЛИЯТ КЛАС, ОТ ДВАТА SEED ФАЙЛА — включително перата, чийто ВИД ПРИДОБИВКА е обявено
    // неизвестен (240, 250, 260–262, 270). Разходната им страна е еднозначна и е 6094; видът им
    // трябва чак когато на фиша има перо 630 (виж BgPayrollInKindTest).
    int[] numbers = {
      200, 201, 202, 203, 204, 205, 210, 211, 220, 221, 222, 223, 224, 225, 230, 231, 232, 240, 245,
      250, 260, 261, 262, 270, 271
    };
    List<String> wrong = new ArrayList<>();
    for (int number : numbers) {
      List<BgPaySlipLine> lines =
          List.of(
              wage(100, "Основно", "1000.00"), line(number, "социален разход", "CREDIT", "30.00"));
      BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);
      if (!plan.getRefusals().isEmpty()) {
        wrong.add(number + ": отказ — " + plan.getRefusals().get(0));
        continue;
      }
      String debit = debitAccountOf(plan, number);
      if (!"6094".equals(debit)) {
        wrong.add(number + ": Dr " + debit + " вместо 6094");
      }
      if (!BgPayrollAccount.PERSONNEL_NET_WAGE.equals(creditAccountOf(plan, number))) {
        wrong.add(number + ": Cr " + creditAccountOf(plan, number) + " вместо 4211");
      }
    }
    assertEquals(List.of(), wrong);
  }

  @Test
  @DisplayName("3а · двойката 2xx + 630 занулява 4211 — ваучерът НЕ става пари")
  void theInKindPairLeavesThePersonnelAccountEmpty() {
    List<BgPaySlipLine> lines =
        List.of(
            wage(100, "Основно", "1000.00"),
            line(210, "Ваучери за храна на електронен носител", "CREDIT", "80.00"),
            line(630, "Предадено в натура (ваучери)", "DEBIT", "80.00"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    assertTrue(plan.isBalanced());
    // ⚠️ ТОВА Е ЦЕЛИЯТ СМИСЪЛ НА ДВОЙКАТА: разходът стои в 6094, задължението към оператора — в
    // 409, а по 4211 не остава НИЩО от придобивката. Ваучерът не е стотинка от плика.
    assertEquals(new BigDecimal("80.00"), amountOn(plan, "6094", true));
    assertEquals(new BigDecimal("80.00"), amountOn(plan, "40901", false));
    assertEquals(
        new BigDecimal("1000.00"),
        amountOn(plan, "4211", false).subtract(amountOn(plan, "4211", true)),
        "по 4211 остава само заплатата — придобивката се занулява");
    // И нетото по фиша казва същото, без да знае за сметки.
    assertEquals(new BigDecimal("1000.00"), BgPaySlipResolver.netTotal(lines));
  }

  // ── 4 · удръжките и техните аналитичности ────────────────────────────────

  @Test
  @DisplayName("4 · деветте пера от 8xx отиват в 4214 с шест различни аналитичности")
  void everyDeductionLandsInOneAccountWithItsOwnAnalytic() {
    Map<Integer, String> expected = new LinkedHashMap<>();
    expected.put(800, "глоба");
    expected.put(810, "заем");
    expected.put(811, "заем");
    expected.put(820, "запор");
    expected.put(821, "запор");
    expected.put(830, "издръжка");
    expected.put(831, "издръжка");
    expected.put(840, "членски внос");
    expected.put(850, "работнически влог");

    List<String> wrong = new ArrayList<>();
    for (Map.Entry<Integer, String> entry : expected.entrySet()) {
      int number = entry.getKey();
      List<BgPaySlipLine> lines =
          List.of(wage(100, "Основно", "1000.00"), line(number, "удръжка", "DEBIT", "25.00"));
      BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);
      if (!plan.getRefusals().isEmpty()) {
        wrong.add(number + ": отказ — " + plan.getRefusals().get(0));
        continue;
      }
      if (!BgPayrollAccount.PERSONNEL_WITHHOLDING.equals(creditAccountOf(plan, number))) {
        wrong.add(number + ": Cr " + creditAccountOf(plan, number) + " вместо 4214");
      }
      String analytic = analyticOf(plan, number);
      if (!entry.getValue().equals(analytic)) {
        wrong.add(number + ": аналитичност „" + analytic + "“ вместо „" + entry.getValue() + "“");
      }
    }
    assertEquals(List.of(), wrong);
  }

  @Test
  @DisplayName("4а · четири вида удръжка дават ЧЕТИРИ реда по 4214, а не един сборен")
  void deductionsOfDifferentKindsDoNotMergeIntoOneLine() {
    List<BgPaySlipLine> lines =
        List.of(
            wage(100, "Основно", "2000.00"),
            line(820, "Запор", "DEBIT", "100.00"),
            line(821, "Запор по чл. 446 ал. 1 ГПК", "DEBIT", "50.00"),
            line(810, "Заем към работодателя", "DEBIT", "30.00"),
            line(830, "Издръжка, текуща", "DEBIT", "200.00"),
            line(800, "Глоба", "DEBIT", "20.00"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    assertTrue(plan.isBalanced());

    Map<String, String> byAnalytic = new LinkedHashMap<>();
    for (BgPayrollPosting p : plan.getPostings()) {
      if (BgPayrollAccount.PERSONNEL_WITHHOLDING.equals(p.getAccountCode())) {
        byAnalytic.put(p.getAnalytic(), p.getAmount().toPlainString());
      }
    }
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("запор", "150.00"); // 820 + 821 — един и същ получател по вид
    expected.put("заем", "30.00");
    expected.put("издръжка", "200.00");
    expected.put("глоба", "20.00");
    assertEquals(expected, byAnalytic, "4214 се цепи по вид удръжка, защото се превежда поотделно");

    // ⚠️ А от страната на ПЕРСОНАЛА разрез по получател НЯМА: 4211 е един дебит за всичките пет.
    assertEquals(new BigDecimal("400.00"), amountOn(plan, "4211", true));
  }

  // ── 5 · обезщетенията ОТ лицето ──────────────────────────────────────────

  @Test
  @DisplayName("5 · 909 и 921 отиват в ПРИХОД 709, а не в кредит на разходна сметка")
  void compensationOwedByTheEmployeeIsRevenue() {
    for (int number : new int[] {909, 921}) {
      List<BgPaySlipLine> lines =
          List.of(
              wage(100, "Основно", "1500.00"),
              line(number, "Обезщетение от лицето", "DEBIT", "450.00"));

      BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

      assertEquals(List.of(), plan.getRefusals(), "перо " + number);
      assertEquals(BgPayrollAccount.PERSONNEL_NET_WAGE, debitAccountOf(plan, number));
      assertEquals(BgPayrollAccount.REVENUE_FROM_EMPLOYEE, creditAccountOf(plan, number));
      // ⚠️ Разходът за заплати ОСТАВА пълен — точно това щеше да се развали при кредит на 6041.
      assertEquals(new BigDecimal("1500.00"), amountOn(plan, "6041", true), "перо " + number);
      assertEquals(new BigDecimal("450.00"), amountOn(plan, "709", false), "перо " + number);
    }
  }

  // ── 6 · плащанията и разходите, които не са възнаграждение ───────────────

  @Test
  @DisplayName("6 · 610 приключва срещу 4212 като аванса, а не по банкова сметка")
  void thePartialBankPaymentClosesTheAdvanceAccount() {
    List<BgPaySlipLine> lines =
        List.of(
            wage(100, "Основно", "1800.00"),
            line(600, "Аванс", "DEBIT", "400.00"),
            line(610, "Частично плащане по банков път", "DEBIT", "300.00"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    assertEquals(BgPayrollAccount.PERSONNEL_ADVANCE, creditAccountOf(plan, 610));
    // Двете пера дават ЕДИН ред по 4212 — една и същата операция.
    assertEquals(new BigDecimal("700.00"), amountOn(plan, "4212", false));
    assertEquals(BigDecimal.ZERO, amountOn(plan, "503", false), "преводът си има своя статия");
  }

  @Test
  @DisplayName("7 · 175 и 195 отиват в синтетичната 604, не в 6041/6042 и не в разчет")
  void theTwoNonWageSalaryLinesLandInTheSyntheticAccount() {
    List<BgPaySlipLine> lines =
        List.of(
            wage(100, "Основно възнаграждение", "1400.00"),
            line(175, "Компенсация по чл. 138а КТ", "CREDIT", "120.00"),
            line(195, "Болничен от осигурител (чл. 40 ал. 5 КСО)", "CREDIT", "86.40"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    assertTrue(plan.isBalanced());
    assertEquals(BgPayrollAccount.EXPENSE_WAGE, debitAccountOf(plan, 175));
    assertEquals(BgPayrollAccount.EXPENSE_WAGE, debitAccountOf(plan, 195));
    assertEquals(new BigDecimal("206.40"), amountOn(plan, "604", true));
    assertEquals(new BigDecimal("1400.00"), amountOn(plan, "6041", true), "основното остава само");
    assertEquals(BigDecimal.ZERO, amountOn(plan, "6042", true), "нито едно от двете не е добавка");
    // ⚠️ И НИТО ЕДНО НЕ ПРАВИ РАЗЧЕТ. Болничният по чл. 40 ал. 5 е за сметка на осигурителя и НЕ
    // се възстановява от НОИ — вземане по 45x би било вземане, което няма да се събере.
    for (BgPayrollPosting p : plan.getPostings()) {
      assertTrue(
          !p.getAccountCode().startsWith("45"), "перата 175/195 не бива да пораждат разчет: " + p);
    }
  }

  // ── помощници ───────────────────────────────────────────────────────────

  private static BigDecimal amountOn(BgPayrollPostingPlan.Plan plan, String code, boolean debit) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BgPayrollPosting p : plan.getPostings()) {
      if (p.getAccountCode().equals(code) && p.isDebit() == debit) {
        sum = sum.add(p.getAmount());
      }
    }
    return sum;
  }

  private static String debitAccountOf(BgPayrollPostingPlan.Plan plan, int lineNumber) {
    for (BgPayrollPostingEntry e : plan.getEntries()) {
      if (e.getLineNumber() == lineNumber) {
        return e.getDebitAccount();
      }
    }
    return null;
  }

  private static String creditAccountOf(BgPayrollPostingPlan.Plan plan, int lineNumber) {
    for (BgPayrollPostingEntry e : plan.getEntries()) {
      if (e.getLineNumber() == lineNumber) {
        return e.getCreditAccount();
      }
    }
    return null;
  }

  private static String analyticOf(BgPayrollPostingPlan.Plan plan, int lineNumber) {
    for (BgPayrollPostingEntry e : plan.getEntries()) {
      if (e.getLineNumber() == lineNumber) {
        return e.getAnalytic();
      }
    }
    return null;
  }

  private static BgPaySlipLine wage(int number, String name, String amount) {
    BgPaySlipLine line = line(number, name, "CREDIT", amount);
    line.setBgFormsGrossWage(true);
    return line;
  }

  private static BgPaySlipLine contribution(
      int number, String name, String fundCode, String sign, String amount) {
    BgPaySlipLine line = line(number, name, sign, amount);
    BgContributionFund fund = new BgContributionFund();
    fund.setCode(fundCode);
    fund.setName(fundCode);
    line.setBgContributionFund(fund);
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
