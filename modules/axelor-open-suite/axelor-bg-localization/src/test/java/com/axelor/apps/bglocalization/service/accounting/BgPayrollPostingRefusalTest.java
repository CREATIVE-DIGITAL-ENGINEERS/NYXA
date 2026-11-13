package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОТКАЗИТЕ: перата, за които BG_COA НЯМА еднозначна сметка, спират цялата статия ПОИМЕННО.
 *
 * <p><b>⚠️ ТОЗИ ТЕСТ ПАЗИ ЕДИН ИНВАРИАНТ, А НЕ СПИСЪК.</b> Списъкът долу е днешното състояние на
 * сметкоплана и ЩЕ СЕ СВИВА — всяко потвърдено присвояване се мести оттук в модела и реда му тук се
 * маха. Инвариантът е другото: неразпознато перо НЕ бива да дава статия. Точно то е тихият провал,
 * срещу който целият клас съществува — пропуснато перо оставя статията БАЛАНСИРАНА (всяко перо
 * внася двойка), тоест документът изглежда напълно сметнат и е по-малък от истината.
 */
class BgPayrollPostingRefusalTest {

  private static final String EMPLOYED = "01";

  // ── 1 ───────────────────────────────────────────────────────────────────

  /**
   * ПЕРАТА БЕЗ ЕДНОЗНАЧНА СМЕТКА — днешното състояние, чакащо счетоводно потвърждение.
   *
   * <p>⚠️ Обхожда се в ЕДИН тест и се събират ВСИЧКИ провали, вместо да се спира на първия:
   * списъкът ще се свива с всяко потвърдено присвояване и тогава е важно да се види цялата разлика
   * наведнъж.
   */
  private static final String[][] UNMAPPED = {
    // ⚠️ СПИСЪКЪТ СЕ СВИ ОТ ДВАЙСЕТ И ЕДНО НА ОСЕМ СЛЕД СЧЕТОВОДНОТО СВЕРЯВАНЕ. Онова, което си
    // отиде, се мести в модела с ИМЕННО ПРИСВОЯВАНЕ (2xx → 609, 175/195 → 604, 610 → 4212,
    // 800/830/831/840/850 → 4214 с аналитичност, 909/921 → 709). Останалото НЕ е забравено — то е
    // отказано по причина, която се чете.
    // 630 · дебитът е ясен (4211), НАСРЕЩНАТА сметка на натурата — не.
    {"630", "Предадено в натура", "DEBIT"},
    // 620 БЕЗ маркер · с попълнен bgCorrectsPeriod перото се ОТЛАГА, не се отказва — виж
    // BgPayrollDeferredLineTest. Без маркера то твърди, че е корекция, и не казва за кой период.
    {"620", "Доплащане по корекция за минал период", "CREDIT"},
    // 922–927 · обезщетенията по ЗДСл и ЗВО. Чакат бюджетния/МСС сметкоплан.
    {"922", "Обезщетение за оставане без работа (държавен служител)", "CREDIT"},
    {"923", "Обезщетение за неползван платен отпуск (държавен служител)", "CREDIT"},
    {"924", "Обезщетение при освобождаване на преподавател (ЗВО)", "CREDIT"},
    {"925", "Обезщетение при пенсиониране (държавен служител)", "CREDIT"},
    {"926", "Обезщетение при пенсия в намален размер (държавен служител)", "CREDIT"},
    {"927", "Обезщетение за неспазено предизвестие (държавен служител)", "CREDIT"},
  };

  @Test
  @DisplayName("1 · перо без еднозначна сметка отказва ЦЯЛАТА статия и се назовава поименно")
  void anUnmappedLineRefusesTheWholeArticle() {
    List<String> failures = new ArrayList<>();
    for (String[] spec : UNMAPPED) {
      int number = Integer.parseInt(spec[0]);
      List<BgPaySlipLine> lines = new ArrayList<>(payableCore());
      lines.add(line(number, spec[1], spec[2], "50.00"));

      BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

      if (plan.getRefusals().size() != 1) {
        failures.add(number + ": очакван 1 отказ, получени " + plan.getRefusals().size());
        continue;
      }
      if (!plan.getRefusals().get(0).startsWith("перо " + number)) {
        failures.add(number + ": отказът не назовава перото — " + plan.getRefusals().get(0));
      }
      if (plan.isPostable()) {
        failures.add(number + ": статия се строи въпреки отказа");
      }
      // ⚠️ И ЕТО ГО ТИХИЯТ ДЕФЕКТ, СРЕЩУ КОЙТО Е ЦЕЛИЯТ КЛАС: без присъдата отгоре остатъкът пак е
      // балансиран, тоест нищо в самата статия не би подсказало, че перо липсва.
      if (!plan.isBalanced()) {
        failures.add(number + ": остатъкът не балансира — предположението на класа е грешно");
      }
    }
    assertEquals(List.of(), failures, "пера, чийто отказ не се държи както трябва");
  }

  // ── 2 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("2 · Учителският пенсионен фонд няма сметка в BG_COA и се отказва поименно")
  void theTeacherPensionFundHasNoAccount() {
    List<BgPaySlipLine> lines = new ArrayList<>(payableCore());
    lines.add(contribution(514, "Учителски ПФ · осигурител", "TEACHER_PF", "CREDIT", "68.37"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(1, plan.getRefusals().size());
    assertTrue(
        plan.getRefusals().get(0).contains("TEACHER_PF"),
        "отказът трябва да назове фонда: " + plan.getRefusals().get(0));
    assertFalse(plan.isPostable());
  }

  // ── 3 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("3 · алтернативният данък по чл. 41а няма сметка — 4543 е „окончателни“, не той")
  void theAlternativeTaxHasNoAccount() {
    List<BgPaySlipLine> lines =
        List.of(
            wage(100, "Възнаграждение", "300.00"),
            line(700, "Алтернативен данък по чл. 41а", "DEBIT", "15.34"));

    // ⚠️ Код 41 е събирачът на диворастящи гъби и плодове —
    // BgSelfInsuredRules.alternativeTaxApplies.
    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, "41");

    assertEquals(1, plan.getRefusals().size());
    assertTrue(plan.getRefusals().get(0).contains("чл. 41а"));
    assertFalse(plan.isPostable());
  }

  // ── 4 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("4 · перо с НУЛА не е отказ и не е ред — просто не участва")
  void aZeroLineIsNeitherRefusedNorPosted() {
    List<BgPaySlipLine> lines = new ArrayList<>(payableCore());
    // ГВРС със ставка 0,00 за 2026 г. — има го на фиша, но не мести пари.
    lines.add(contribution(518, "ГВРС · осигурител", "GVRS", "CREDIT", "0.00"));
    // И нулево перо, което иначе БИ БИЛО отказано — нулата излиза преди въпроса за сметка.
    lines.add(line(830, "Издръжка", "DEBIT", "0.00"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(List.of(), plan.getRefusals());
    assertTrue(plan.isPostable());
    assertEquals(1, plan.getEntries().size(), "само заплатата дава ред");
  }

  // ── 5 ───────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("5 · няколко неразпознати пера дават НЯКОЛКО отказа, не първия")
  void everyUnmappedLineIsNamed() {
    List<BgPaySlipLine> lines = new ArrayList<>(payableCore());
    lines.add(line(630, "Предадено в натура", "DEBIT", "40.00"));
    lines.add(line(922, "Обезщетение по ЗДСл чл. 106 ал. 2", "CREDIT", "150.00"));
    lines.add(line(620, "Корекция за минал период (без маркер)", "CREDIT", "22.00"));

    BgPayrollPostingPlan.Plan plan = BgPayrollPostingPlan.of(lines, EMPLOYED);

    assertEquals(3, plan.getRefusals().size(), "операторът трябва да види ВСИЧКИ липси наведнъж");
  }

  // ── помощници ───────────────────────────────────────────────────────────

  /**
   * Минималният фиш, който САМ по себе си дава статия — за да е ясно, че отказът идва от добавката.
   */
  private static List<BgPaySlipLine> payableCore() {
    return Arrays.asList(wage(100, "Основно възнаграждение", "1500.00"));
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
