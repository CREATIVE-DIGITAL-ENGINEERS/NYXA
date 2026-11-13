package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ТРИТЕ, КОИТО ЧАКАТ БЪДЕЩИЯ БЮДЖЕТЕН/МСС СМЕТКОПЛАН — и отменят ЦЯЛАТА статия, а не своя ред.
 *
 * <p>Учителският пенсионен фонд, обезщетенията по ЗДСл/ЗВО (922–927) и алтернативният данък по
 * ЗДДФЛ чл. 41а нямат сметка в BG_COA и НЕ се гадаят. Счетоводното сверяване свали от този списък
 * всичко останало (2xx, 175/195, 610, 800/830/831/840/850, 909/921) — тези три останаха назовани.
 *
 * <p><b>⚠️ ТВЪРДЕНИЕТО НЕ Е „РЕДЪТ ЛИПСВА“, А „ДОКУМЕНТ НЯМА“.</b> Разликата е целият смисъл на
 * класа. Всяко перо внася ДВОЙКА сметки, тоест махнеш ли едно, остатъкът ПАК балансира: излиза
 * статия, която изглежда напълно сметната и е по-малка от истината. Такъв дефект не се вижда при
 * проверка на документа — вижда се чак когато салдото на 4211 не се сведе с ведомостта. Затова
 * всеки тест отдолу проверява ТРИ неща: че статия няма, че перото е назовано, и че остатъкът СЕ
 * БАЛАНСИРА (тоест балансът наистина не може да бъде свидетелят).
 *
 * <p>⚠️ И всеки има КОНТРОЛЕН ОПИТ — същият фиш без спорното перо дава статия. Без него тестът щеше
 * да минава и за фиш, който не се осчетоводява по съвсем друга причина.
 */
class BgPayrollOutOfScopeRefusalTest {

  private static final String EMPLOYED = "01";

  /** Код 41 — събирачът на диворастящи гъби и плодове; неговият данък е по чл. 41а. */
  private static final String ALTERNATIVE_TAX_TYPE = "41";

  // ── 1 · Учителският пенсионен фонд ──────────────────────────────────────

  @Test
  @DisplayName("1 · УчПФ отменя цялата статия, а остатъкът ѝ пак балансира")
  void theTeacherPensionFundCancelsTheWholeArticle() {
    List<BgPaySlipLine> lines = new ArrayList<>(core());
    lines.add(contribution(514, "Учителски ПФ · осигурител", "TEACHER_PF", "CREDIT", "68.37"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertFalse(plan.isPostable(), "статия не бива да се строи");
    assertEquals(1, plan.getRefusals().size());
    assertTrue(plan.getRefusals().get(0).contains("TEACHER_PF"), plan.getRefusals().get(0));
    assertTrue(plan.isBalanced(), "и точно затова балансът НЕ може да е свидетелят");
    assertTrue(BgPayrollPostingPlan.of(core(), EMPLOYED).isPostable(), "контролният опит");
  }

  // ── 2 · обезщетенията по ЗДСл и ЗВО ─────────────────────────────────────

  @Test
  @DisplayName("2 · всяко от 922–927 отменя цялата статия и се назовава поименно")
  void everyCivilServantCompensationCancelsTheWholeArticle() {
    List<String> failures = new ArrayList<>();
    for (int number = 922; number <= 927; number++) {
      List<BgPaySlipLine> lines = new ArrayList<>(core());
      lines.add(line(number, "Обезщетение по ЗДСл/ЗВО", "CREDIT", "980.00"));

      BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

      if (plan.isPostable()) {
        failures.add(number + ": статия се строи");
      }
      if (plan.getRefusals().size() != 1
          || !plan.getRefusals().get(0).startsWith("перо " + number)) {
        failures.add(number + ": отказът не назовава перото — " + plan.getRefusals());
      } else if (!plan.getRefusals().get(0).contains("ЗДСл")) {
        failures.add(number + ": причината не сочи закона — " + plan.getRefusals().get(0));
      }
      if (!plan.isBalanced()) {
        failures.add(number + ": остатъкът не балансира — предположението на класа е грешно");
      }
    }
    assertEquals(List.of(), failures);
    // ⚠️ И КОНТРАПРИМЕРЪТ ОТ СЪСЕДНАТА ГРУПА: 928 е по КТ и НЕ се отказва. Без него тестът щеше
    // да минава и за модел, който отказва цялата група 9xx.
    List<BgPaySlipLine> byLabourCode = new ArrayList<>(core());
    byLabourCode.add(line(928, "Обезщетение при трудоустрояване", "CREDIT", "980.00"));
    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(byLabourCode, EMPLOYED);
    assertEquals(List.of(), plan.getRefusals(), "928 е по КТ и има сметка");
    assertEquals(new BigDecimal("980.00"), amountOn(plan, "6043", true));
  }

  // ── 3 · алтернативният данък по чл. 41а ─────────────────────────────────

  @Test
  @DisplayName("3 · чл. 41а отменя цялата статия — 4543 „окончателни“ НЕ е неговата сметка")
  void theAlternativeTaxCancelsTheWholeArticle() {
    List<BgPaySlipLine> lines = new ArrayList<>(core());
    lines.add(line(700, "Алтернативен данък по чл. 41а", "DEBIT", "15.34"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, ALTERNATIVE_TAX_TYPE);

    assertFalse(plan.isPostable());
    assertEquals(1, plan.getRefusals().size());
    assertTrue(plan.getRefusals().get(0).contains("чл. 41а"), plan.getRefusals().get(0));
    assertTrue(plan.isBalanced());

    // ⚠️ КОНТРОЛНИЯТ ОПИТ ТУК Е СЪЩОТО ПЕРО ПРИ ДРУГ ВИД ОСИГУРЕН. Перото 700 е ЕДНО и носи и
    // трите данъка; разграничителят е видът осигурен. Тоест ако отказът се беше написал върху
    // ПЕРОТО, този тест щеше да мине, а всеки нормален данък щеше да спре да се осчетоводява.
    BgPayrollPostingPlan.Plan employed = BgPayrollPostingPlan.of(lines, EMPLOYED);
    assertEquals(List.of(), employed.getRefusals());
    assertEquals(new BigDecimal("15.34"), amountOn(employed, "4541", false));
  }

  // ── 4 · и на ниво документ: причина върху фиша, нула записи ─────────────

  @Test
  @DisplayName("4 · и трите оставят ПРИЧИНА върху фиша, а не изключение и не половин статия")
  void allThreeLeaveAReasonOnTheSlipAndWriteNothing() throws Exception {
    List<BgPaySlipLine> teacher = new ArrayList<>(core());
    teacher.add(contribution(514, "Учителски ПФ", "TEACHER_PF", "CREDIT", "68.37"));
    List<BgPaySlipLine> civilServant = new ArrayList<>(core());
    civilServant.add(line(923, "Обезщетение по ЗДСл", "CREDIT", "500.00"));
    List<BgPaySlipLine> alternative = new ArrayList<>(core());
    alternative.add(line(700, "Алтернативен данък", "DEBIT", "15.34"));

    for (Object[] probe :
        new Object[][] {
          {teacher, EMPLOYED, "TEACHER_PF"},
          {civilServant, EMPLOYED, "перо 923"},
          {alternative, ALTERNATIVE_TAX_TYPE, "чл. 41а"}
        }) {
      Recording service = new Recording();
      @SuppressWarnings("unchecked")
      BgPaySlip slip = slip((List<BgPaySlipLine>) probe[0], (String) probe[1]);

      service.generate(slip);

      assertNull(slip.getBgPayrollMove(), "статия не бива да има: " + probe[2]);
      assertNotNull(slip.getBgPayrollMoveNote());
      assertTrue(
          slip.getBgPayrollMoveNote().contains((String) probe[2]),
          "причината трябва да назовава спорното: " + slip.getBgPayrollMoveNote());
      assertTrue(
          slip.getBgPayrollMoveNote().contains("ЦЯЛО ИЛИ НИЩО"), slip.getBgPayrollMoveNote());
      assertEquals(0, service.writes);
    }

    // Контролният опит на ниво документ: същият фиш без спорното перо получава статия.
    Recording control = new Recording();
    BgPaySlip clean = slip(core(), EMPLOYED);
    control.generate(clean);
    assertNotNull(clean.getBgPayrollMove(), clean.getBgPayrollMoveNote());
    assertEquals(1, control.writes);
  }

  // ── подпората ───────────────────────────────────────────────────────────

  private static final class Recording extends BgPaySlipMoveService {

    int writes;

    Recording() {
      super(null, null, null, null, null, null, null);
    }

    /** Осчетоводяването на AOS иска контейнер; тук се брои, не се вика. */
    @Override
    protected void post(Move move) {}

    @Override
    protected Account accountFor(String code, Company company) {
      AccountType type = new AccountType();
      type.setTechnicalTypeSelect(
          code.startsWith("60") ? "charge" : code.startsWith("70") ? "income" : "debt");
      Account account = new Account();
      account.setCode(code);
      account.setAccountType(type);
      return account;
    }

    @Override
    protected Move write(
        BgPaySlip slip,
        Company company,
        Journal journal,
        List<BgPayrollPosting> postings,
        String description) {
      writes++;
      return new Move();
    }
  }

  private static BgPaySlip slip(List<BgPaySlipLine> lines, String insuredTypeCode) {
    Company company = new Company();
    company.setName("Тест ЕООД");
    Currency euro = new Currency();
    euro.setCode("EUR");
    company.setCurrency(euro);
    Journal journal = new Journal();
    journal.setCode("PAY");
    LinkedHashSet<AccountType> types = new LinkedHashSet<>();
    for (String technical : List.of("debt", "charge", "income")) {
      AccountType type = new AccountType();
      type.setTechnicalTypeSelect(technical);
      types.add(type);
    }
    journal.setValidAccountTypeSet(types);
    AccountConfig config = new AccountConfig();
    config.setBgPayrollJournal(journal);
    company.setAccountConfig(config);

    EmploymentContract contract = new EmploymentContract();
    contract.setPayCompany(company);
    contract.setBgInsuredTypeCode(insuredTypeCode);

    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-2026-0007");
    slip.setEmploymentContract(contract);
    slip.setBgPaidDate(LocalDate.of(2026, 6, 10));
    slip.setBgPaySlipLineList(new ArrayList<>(lines));
    return slip;
  }

  /** Минималният фиш, който САМ дава статия — за да е ясно, че отказът идва от добавката. */
  private static List<BgPaySlipLine> core() {
    List<BgPaySlipLine> lines = new ArrayList<>();
    BgPaySlipLine wage = line(100, "Основно възнаграждение", "CREDIT", "1500.00");
    wage.setBgFormsGrossWage(true);
    lines.add(wage);
    return lines;
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
