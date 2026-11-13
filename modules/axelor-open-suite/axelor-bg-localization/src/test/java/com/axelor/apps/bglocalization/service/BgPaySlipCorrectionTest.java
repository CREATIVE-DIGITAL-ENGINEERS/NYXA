package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.hr.db.EmploymentContract;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СЦЕНАРИЯТ МАРТ → МАЙ, край до край (спецификация § 3 и § 9).
 *
 * <p>Март 2026, Иванов, база 2000, период CLOSED, Д1/Д6 подадени и платени. През май се открива
 * бонус 300 за март. Числата НЕ са измислени за теста — те са тези от спецификацията и се сверяват
 * поотделно, за да се вижда КОЕ се е разминало, а не само че сборът не излиза.
 *
 * <p><b>Какво точно доказва.</b> Трите потока на парите се разделят и това е цялата трудност:
 *
 * <ul>
 *   <li><b>декларация</b> — коригиращият МАРТЕНСКИ фиш носи пълните верни числа и храни коригираща
 *       Д1/Д6 за МАРТ. Майските Д1/Д6 остават чисти;
 *   <li><b>към лицето</b> — САМО нетната разлика, в МАЙСКИЯ фиш, тоест майското нето и ведомостта
 *       се променят;
 *   <li><b>към НАП</b> — доплащането на вноските и данъка е за МАРТ и остава извън този модел
 *       (WS-7), точно защото коригиращият фиш го носи, а не майският.
 * </ul>
 *
 * <p>Разминаването между тези три е тихо по устройство: всяко число поотделно изглежда вярно.
 * Затова тук се проверяват едновременно нетото И агрегатите на декларацията — зелено само на едното
 * е половин отговор.
 *
 * <p>Без база и без Guice: правилата са в {@link BgPaySlipResolver}, шевовете към базата са
 * подменени.
 */
class BgPaySlipCorrectionTest {

  // ── числата от § 3, всяко назовано ──────────────────────────────────────

  /** Март, оригинал: база 2000, лични 13,78 %, ДОД 10 %. */
  private static final BigDecimal MARCH_BASE = new BigDecimal("2000.00");

  private static final BigDecimal MARCH_PERSONAL = new BigDecimal("275.60");
  private static final BigDecimal MARCH_EMPLOYER = new BigDecimal("378.40");
  private static final BigDecimal MARCH_TAX = new BigDecimal("172.44");
  private static final BigDecimal MARCH_NET = new BigDecimal("1551.96");

  /** Март, коригиран: осигурителен доход 2300 (бонусът 300 влиза). */
  private static final BigDecimal FIXED_BASE = new BigDecimal("2300.00");

  private static final BigDecimal FIXED_PERSONAL = new BigDecimal("316.94");
  private static final BigDecimal FIXED_EMPLOYER = new BigDecimal("435.16");
  private static final BigDecimal FIXED_TAX = new BigDecimal("198.31");
  private static final BigDecimal FIXED_NET = new BigDecimal("1784.75");

  /** Нетната разлика към лицето — единственото число, което влиза в май. */
  private static final BigDecimal DELTA = new BigDecimal("232.79");

  /**
   * Май, текущият месец. Базата е РАЗЛИЧНА от мартенската нарочно: с еднакви бази майското нето
   * след делтата съвпада с коригиращото мартенско (1784,75) и тестът щеше да мине зелен и ако кодът
   * ПРЕПИШЕ коригиращото нето в май вместо да добави разликата.
   */
  private static final BigDecimal MAY_BASE = new BigDecimal("2100.00");

  private static final BigDecimal MAY_PERSONAL = new BigDecimal("289.38");
  private static final BigDecimal MAY_EMPLOYER = new BigDecimal("397.32");
  private static final BigDecimal MAY_TAX = new BigDecimal("181.06");
  private static final BigDecimal MAY_NET = new BigDecimal("1629.56");
  private static final BigDecimal MAY_NET_AFTER = new BigDecimal("1862.35");

  private static final BigDecimal MAY_TAX_BASE = new BigDecimal("1810.62");

  // ── услугата без база ───────────────────────────────────────────────────

  private static class Service extends BgPaySlipService {

    /**
     * Производните пера на коригиращото копие (5xx, 516 по т. 5, 700, карвът на 180/181) имат свой
     * свидетел — {@code BgPaySlipRetroGeneratorsTest}. Тук се мери механиката на ретро-потока
     * (делтата, периодът, ключовете), не числата на генераторите; calculate() минава истински —
     * отказите, сборовете и отпечатъкът.
     */
    @Override
    protected void prepareCorrection(BgPaySlip copy) {}

    /**
     * Генераторът на начисления пипа речника и каталога с параметри; тук твърдението е за
     * ретро-механизма, не за перата. Че {@code prepare()} наистина вика шева, се проверява в {@code
     * BgPaySlipServiceTest}, а какво пише той — в {@code BgEarningsGeneratorTest}.
     */
    @Override
    protected void generateEarnings(BgPaySlip slip) {}

    /**
     * Същото за болничните: генераторът пипа четири речника, а тук твърдението е за друго.
     *
     * <p>⚠️ Добавен на 21.08.2026 г. заедно с шева в {@code prepare()}. Без него всеки тест, който
     * вика {@code prepare()}, пада с „Guice is not initialized“ — тоест новият шев не може да се
     * промъкне незабелязано.
     */
    @Override
    protected void generateSickLeave(BgPaySlip slip) {}

    /**
     * ⚠️ ЗАМРАЗЯВАНЕТО НА ДНИТЕ ОТПУСК — заглушено по същия довод като съседите: то пита базата за
     * заявките за отпуск през {@code payrollInput}. Декларация обр. 1 ги чете оттам вместо да ги
     * смята наново; този свидетел не мери нито едното.
     */
    @Override
    protected void freezeLeaveDays(BgPaySlip slip) {}

    /** Стъпката по чл. 177 — заглушена: този свидетел не мери отпуск. */
    @Override
    protected void generateArt177Leave(BgPaySlip slip) {}

    /**
     * Същото за обезщетенията: генераторът чете документите и речника, а тук твърдението е за
     * друго.
     *
     * <p>⚠️ Добавен заедно с шева в {@code prepare()}. Без него всеки тест, който вика {@code
     * prepare()}, пада с „Guice is not initialized“ — тоест новият шев не може да се промъкне
     * незабелязано.
     */
    @Override
    protected void generateCompensations(BgPaySlip slip) {}

    /**
     * Същото за осигуровките: генераторът чете речника с типове пера, а тук твърдението е за друго.
     *
     * <p>⚠️ Добавен заедно с шева в {@code prepare()}. Без него всеки тест, който вика {@code
     * prepare()}, пада с „Guice is not initialized“ — тоест новият шев не може да се промъкне
     * незабелязано.
     */
    @Override
    protected void generateContributions(BgPaySlip slip) {}

    /**
     * Същото за данъка: генераторът чете речника с типове пера, а тук твърдението е за друго.
     *
     * <p>⚠️ Добавен заедно с шева в {@code prepare()}. Без него всеки тест, който вика {@code
     * prepare()}, пада с „Guice is not initialized“.
     */
    @Override
    protected void generateIncomeTax(BgPaySlip slip) {}

    @Override
    protected void generateAnnualSettlement(BgPaySlip slip) {}

    Period currentPeriod;
    BgPaySlip currentDraft;
    BgPaySlipLineType deltaType = deltaLineType();
    boolean saveExplodes;

    final List<BgPaySlip> savedSlips = new ArrayList<>();
    final List<Period> savedPeriods = new ArrayList<>();
    final List<String> adjustLog = new ArrayList<>();

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return new BgWorkingDayService() {
        @Override
        public java.util.Set<Integer> coveredYears() {
          return new java.util.HashSet<>(java.util.Arrays.asList(2026, 2027));
        }

        @Override
        protected java.util.Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
          return Collections.emptySet();
        }
      }.count(LocalDate.of(2026, 3, 1), 31);
    }

    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      return BgContributionResult.resolved(Collections.emptyList());
    }

    @Override
    protected BgMinIncomeResult minIncome(BgPaySlip slip) {
      return BgMinIncomeResult.notApplicable("неутрално за този тест");
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.0000"); // INCOME_TAX_RATE от seed-а
    }

    @Override
    protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
      return null;
    }

    @Override
    protected void save(BgPaySlip slip) {
      if (saveExplodes) {
        throw new IllegalStateException("базата отказа записа");
      }
      savedSlips.add(slip);
    }

    @Override
    protected void saveCompensation(BgCompensation doc) {}

    @Override
    protected Period currentOpenPeriod(BgPaySlip slip) {
      return currentPeriod;
    }

    @Override
    protected BgPaySlip openDraftSlip(EmploymentContract contract, Period period) {
      return currentDraft;
    }

    @Override
    protected BgPaySlipLineType correctionLineType() {
      return deltaType;
    }

    @Override
    protected void savePeriod(Period period) {
      savedPeriods.add(period);
    }

    @Override
    protected void logAdjustStart(Period period) {
      adjustLog.add("start:" + period.getName());
    }

    @Override
    protected void logAdjustEnd(Period period) {
      adjustLog.add("end:" + period.getName());
    }

    @Override
    protected LocalDate today() {
      return LocalDate.of(2026, 5, 20);
    }
  }

  // ── фикстури ────────────────────────────────────────────────────────────

  private static BgPaySlipLineType type(
      int number, String name, String sign, boolean taxable, boolean insurable) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(taxable);
    t.setBgIsInsuranceIncome(insurable);
    t.setBgIsPermanentCharacter(false);
    return t;
  }

  /** Перото 620 от речника, както го носи seed-ът: посока CREDIT, извън дохода и извън основата. */
  private static BgPaySlipLineType deltaLineType() {
    BgPaySlipLineType t =
        type(620, "Доплащане/удръжка по корекция за минал период", "CREDIT", false, false);
    t.setCode(BgPaySlipService.CORRECTION_LINE_CODE);
    t.setBgGroup("PAYMENTS");
    return t;
  }

  private static Period period(String name, LocalDate from, LocalDate to, int status) {
    Year year = new Year();
    year.setName("Заплатна " + from.getYear());
    year.setTypeSelect(2);
    Period p = new Period();
    p.setName(name);
    p.setFromDate(from);
    p.setToDate(to);
    p.setYear(year);
    p.setStatusSelect(status);
    return p;
  }

  private static Period march(int status) {
    return period("03/PY2026", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), status);
  }

  private static Period may() {
    return period(
        "05/PY2026",
        LocalDate.of(2026, 5, 1),
        LocalDate.of(2026, 5, 31),
        PeriodRepository.STATUS_OPENED);
  }

  private static final EmploymentContract IVANOV = new EmploymentContract();

  /** Фиш с четирите пера на един обикновен месец: заплата, лични, работодателски, данък. */
  private static BgPaySlip monthSlip(
      Service s,
      Period p,
      BigDecimal base,
      BigDecimal personal,
      BigDecimal employer,
      BigDecimal tax) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setEmploymentContract(IVANOV);
    slip.setPeriod(p);
    slip.setBgPaySlipLineList(new ArrayList<>());
    s.addLine(slip, s.lineFrom(type(100, "Основно възнаграждение", "CREDIT", true, true), base));
    s.addLine(
        slip,
        s.lineFrom(type(501, "Осигуровки — за сметка на лицето", "DEBIT", false, false), personal));
    s.addLine(
        slip,
        s.lineFrom(
            type(551, "Осигуровки — за сметка на работодателя", "CREDIT", false, false), employer));
    s.addLine(slip, s.lineFrom(type(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT", false, false), tax));
    s.recompute(slip);
    return slip;
  }

  /** Мартенският оригинал: потвърден, изплатен, замразен. */
  private static BgPaySlip originalMarch(Service s, Period p) {
    BgPaySlip slip = monthSlip(s, p, MARCH_BASE, MARCH_PERSONAL, MARCH_EMPLOYER, MARCH_TAX);
    slip.setReference("FISH-2026-0101");
    // ⚠️ Оригиналът е пресметнат по общия път, тоест режимът му е замразен — истинската първа
    // стъпка на calculate(). Без нея копието би било копие на заварен фиш и щеше да отказва.
    s.freezeRegime(slip);
    slip.setStatusSelect(BgPaySlipResolver.PAID);
    return slip;
  }

  /** Операторът нанася бонуса върху копието: четирите пера стават коригираните. */
  private static void applyTheBonus(Service s, BgPaySlip copy) {
    for (BgPaySlipLine line : copy.getBgPaySlipLineList()) {
      switch (line.getBgLineNumber()) {
        case 100:
          line.setBgAmount(FIXED_BASE);
          break;
        case 501:
          line.setBgAmount(FIXED_PERSONAL);
          break;
        case 551:
          line.setBgAmount(FIXED_EMPLOYER);
          break;
        case 700:
          line.setBgAmount(FIXED_TAX);
          break;
        default:
          break;
      }
    }
    s.recompute(copy);
  }

  /** Услугата, мартенският оригинал и майската чернова — подредени както в § 3. */
  private static Service scene(BgPaySlip[] out) {
    Service s = new Service();
    Period marchPeriod = march(PeriodRepository.STATUS_CLOSED);
    out[0] = originalMarch(s, marchPeriod);
    s.currentPeriod = may();
    s.currentDraft = monthSlip(s, s.currentPeriod, MAY_BASE, MAY_PERSONAL, MAY_EMPLOYER, MAY_TAX);
    out[1] = s.currentDraft;
    return s;
  }

  // ── 1 · оригиналът и копието ────────────────────────────────────────────

  @Test
  @DisplayName("§ 3 · оригиналният мартенски фиш се връзва до стотинка, преди да го пипаме")
  void theOriginalMarchSlipAddsUp() {
    Service s = new Service();
    BgPaySlip original = originalMarch(s, march(PeriodRepository.STATUS_CLOSED));

    assertEquals(MARCH_BASE, original.getBgGrossTotal(), "бруто");
    assertEquals(MARCH_BASE, original.getBgInsuranceIncome(), "осигурителен доход");
    assertEquals(new BigDecimal("1724.40"), original.getBgTaxBase(), "2000 − 275,60");
    assertEquals(MARCH_TAX, original.getBgTaxAmount(), "10 на сто от 1724,40");
    assertEquals(MARCH_NET, original.getBgNetTotal(), "2000 − 275,60 − 172,44");
    assertEquals(new BigDecimal("2378.40"), original.getBgEmployerCost(), "2000 + 378,40");
  }

  @Test
  @DisplayName("correct() дава РЕДАКТИРУЕМО копие с пълните СТАРИ числа, а оригиналът е замразен")
  void correctOpensAnEditableCopyAndFreezesTheOriginal() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip original = scene[0];

    BgPaySlip copy = s.correct(original);

    assertEquals(BgPaySlipResolver.DRAFT, copy.getStatusSelect(), "копието е чернова");
    assertTrue(copy.getBgIsCorrection(), "копието е обявено за коригиращо");
    assertSame(original, copy.getBgCorrectionOf(), "и сочи оригинала");
    assertSame(original.getPeriod(), copy.getPeriod(), "същият период — март");
    assertSame(original.getEmploymentContract(), copy.getEmploymentContract());
    assertEquals(4, copy.getBgPaySlipLineList().size(), "четирите пера са пренесени");
    assertEquals(MARCH_NET, copy.getBgNetTotal(), "копието започва от СТАРИТЕ числа");

    // ⚠️ Копие, не препратка: редакция на копието не мърда оригинала.
    for (BgPaySlipLine line : copy.getBgPaySlipLineList()) {
      for (BgPaySlipLine source : original.getBgPaySlipLineList()) {
        assertNotSame(source, line, "перото на копието е нов запис");
      }
      assertSame(copy, line.getPaySlip(), "и е закачено за копието");
    }
    applyTheBonus(s, copy);
    assertEquals(FIXED_NET, copy.getBgNetTotal());
    assertEquals(MARCH_NET, original.getBgNetTotal(), "ОРИГИНАЛЪТ Е ЗАМРАЗЕН");
    assertEquals(BgPaySlipResolver.PAID, original.getStatusSelect());
    assertEquals(MARCH_BASE, original.getBgPaySlipLineList().get(0).getBgAmount());
  }

  @Test
  @DisplayName("correct() слага периода в ADJUSTING и ЛОГВА прехода")
  void correctMovesThePeriodToAdjustingAndLogsIt() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    s.correct(scene[0]);

    assertEquals(
        PeriodRepository.STATUS_ADJUSTING,
        scene[0].getPeriod().getStatusSelect().intValue(),
        "периодът стои ADJUSTING, докато операторът редактира");
    assertTrue(s.adjustLog.contains("start:03/PY2026"), "преходът е вписан: " + s.adjustLog);
    assertTrue(s.savedPeriods.contains(scene[0].getPeriod()), "и записан");
  }

  @Test
  @DisplayName(
      "⚠️ Провал ПРЕДИ местенето оставя периода CLOSED — не по транзакция, а по устройство")
  void aFailureBeforeTheMoveLeavesThePeriodClosed() {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    s.saveExplodes = true;

    assertThrows(IllegalStateException.class, () -> s.correct(scene[0]));

    assertEquals(
        PeriodRepository.STATUS_CLOSED,
        scene[0].getPeriod().getStatusSelect().intValue(),
        "⚠️ Периодът се мести ПОСЛЕДЕН точно за това: провал по пътя не оставя месец, обявен за „под"
            + " корекция“, без коригиращ фиш зад себе си.");
    assertTrue(s.adjustLog.isEmpty(), "и нищо не е вписано в историята: " + s.adjustLog);
  }

  // ── 2 · потвърждаването: трите потока се разделят ────────────────────────

  @Test
  @DisplayName("§ 3 · потвърждаването слага САМО нетната разлика в май, с маркер за март")
  void confirmingPutsOnlyTheNetDeltaIntoTheCurrentSlip() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip original = scene[0];
    BgPaySlip may = scene[1];

    assertEquals(MAY_NET, may.getBgNetTotal(), "майското нето ПРЕДИ корекцията");

    BgPaySlip copy = s.correct(original);
    applyTheBonus(s, copy);
    s.calculate(copy);
    s.confirm(copy);

    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect());
    assertEquals(5, may.getBgPaySlipLineList().size(), "точно ЕДИН нов ред в май");

    BgPaySlipLine delta = may.getBgPaySlipLineList().get(4);
    assertEquals(620, delta.getBgLineNumber().intValue(), "перото на корекцията");
    assertEquals(DELTA, delta.getBgAmount(), "нетната разлика, 1784,75 − 1551,96");
    assertEquals(BgPaySlipResolver.CREDIT, delta.getBgSign(), "начислява се");
    assertEquals("Доплащане за 03/2026", delta.getName(), "името казва КОЙ месец се поправя");
    assertSame(original.getPeriod(), delta.getBgCorrectsPeriod(), "маркерът сочи МАРТ");
    assertFalse(delta.getBgIsInsuranceIncome(), "не е осигурителен доход");
    assertFalse(delta.getBgIsTaxable(), "и е извън данъчната основа");
  }

  @Test
  @DisplayName("⚠️ § 4 · майските Д1/Д6 остават ЧИСТИ, а майското нето се променя — едновременно")
  void theCurrentDeclarationStaysCleanWhileTheCurrentNetChanges() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip may = scene[1];

    BgPaySlip copy = s.correct(scene[0]);
    applyTheBonus(s, copy);
    s.calculate(copy);
    s.confirm(copy);

    // ДЕКЛАРАЦИЯТА за май — непокътната. Осигурителният доход и данъкът за март са в коригиращия
    // мартенски фиш и оттам в коригиращата Д1/Д6 ЗА МАРТ.
    assertEquals(MAY_BASE, may.getBgInsuranceIncome(), "майският осигурителен доход НЕ мърда");
    assertEquals(MAY_TAX_BASE, may.getBgTaxBase(), "майската данъчна основа НЕ мърда");
    assertEquals(MAY_TAX, may.getBgTaxAmount(), "майският данък НЕ мърда");
    assertEquals(MAY_BASE, may.getBgGrossTotal(), "майското бруто НЕ мърда");
    assertEquals(
        new BigDecimal("2497.32"), may.getBgEmployerCost(), "разходът за работодателя НЕ мърда");

    // ВЕДОМОСТТА за май — променена, точно с разликата.
    assertEquals(MAY_NET_AFTER, may.getBgNetTotal(), "1629,56 + 232,79");
    assertEquals(
        DELTA,
        may.getBgNetTotal().subtract(MAY_NET),
        "майското нето се вдига точно с нетната разлика, нито стотинка повече");
  }

  @Test
  @DisplayName("⚠️ ИНВАРИАНТЪТ: оригинал + разлика = коригиращ, и разликата е тази в май")
  void theInvariantHolds() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip original = scene[0];

    BgPaySlip copy = s.correct(original);
    applyTheBonus(s, copy);
    s.calculate(copy);
    s.confirm(copy);

    assertEquals(
        copy.getBgNetTotal(),
        original.getBgNetTotal().add(DELTA),
        "1551,96 + 232,79 = 1784,75 — инак делтата не е разликата");
    BgPaySlipLine delta = scene[1].getBgPaySlipLineList().get(4);
    assertEquals(
        copy.getBgNetTotal().subtract(original.getBgNetTotal()),
        delta.getBgAmount(),
        "и точно тя е сумата на реда в май");
  }

  @Test
  @DisplayName("Периодът се връща в CLOSED при потвърждаване, и краят е вписан")
  void confirmingReturnsThePeriodToClosed() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    BgPaySlip copy = s.correct(scene[0]);
    applyTheBonus(s, copy);
    s.calculate(copy);
    s.confirm(copy);

    assertEquals(
        PeriodRepository.STATUS_CLOSED,
        scene[0].getPeriod().getStatusSelect().intValue(),
        "март пак е заключен");
    assertEquals(
        java.util.Arrays.asList("start:03/PY2026", "end:03/PY2026"),
        s.adjustLog,
        "историята носи и началото, и края");
  }

  // ── 3 · отказът също затваря периода ────────────────────────────────────

  @Test
  @DisplayName("⚠️ ОТКАЗ на коригиращото копие връща CLOSED и НЕ слага нищо в май")
  void cancellingTheCopyAlsoClosesThePeriod() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    BgPaySlip copy = s.correct(scene[0]);
    applyTheBonus(s, copy);
    copy.setBgCancelReason("операторът се отказа — бонусът е за април");
    s.cancel(copy);

    assertEquals(BgPaySlipResolver.CANCELLED, copy.getStatusSelect());
    assertEquals(
        PeriodRepository.STATUS_CLOSED,
        scene[0].getPeriod().getStatusSelect().intValue(),
        "⚠️ Инак месецът остава „под корекция“ завинаги — без нито един документ, който да го затвори");
    assertEquals(4, scene[1].getBgPaySlipLineList().size(), "в май НЯМА делта-ред");
    assertEquals(MAY_NET, scene[1].getBgNetTotal(), "и нетото не е мърдало");
  }

  // ── 4 · коригиращият не плаща ───────────────────────────────────────────

  @Test
  @DisplayName("⚠️ КОРИГИРАЩИЯТ ФИШ НЕ СЕ ИЗПЛАЩА — инак лицето получава месеца два пъти")
  void theCorrectingSlipIsNeverPaid() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    BgPaySlip copy = s.correct(scene[0]);
    applyTheBonus(s, copy);
    s.calculate(copy);
    s.confirm(copy);
    copy.setBgPaidDate(LocalDate.of(2026, 5, 25));

    AxelorException e = assertThrows(AxelorException.class, () -> s.pay(copy));
    assertTrue(e.getMessage().contains("НЕ се изплаща"), e.getMessage());
    assertTrue(e.getMessage().contains("два пъти"), e.getMessage());
    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect(), "остава потвърден");

    // а редовният фиш се плаща както винаги — пазачът не спира обичайната работа
    BgPaySlip may = scene[1];
    s.calculate(may);
    s.confirm(may);
    may.setBgPaidDate(LocalDate.of(2026, 5, 31));
    s.pay(may);
    assertEquals(BgPaySlipResolver.PAID, may.getStatusSelect());
  }

  // ── 5 · обратната посока: удръжка и нула ────────────────────────────────

  @Test
  @DisplayName("НАМАЛЕНИЕ дава УДРЪЖКА: отрицателна делта, DEBIT ред, майското нето пада")
  void aDownwardCorrectionBecomesADeduction() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    BgPaySlip copy = s.correct(scene[0]);
    // Обратният случай: заплатата е била 2000, а вярната е 1900 — надплатено. ⚠️ Данъкът също се
    // поправя: 1900 − 275,60 = 1624,40, тоест 162,44. Без него refuseTaxMismatch спира
    // потвърждаването — и това е ПРАВИЛНО, защото фиш с несъответстващ данък е грешен и при
    // корекция.
    for (BgPaySlipLine line : copy.getBgPaySlipLineList()) {
      if (line.getBgLineNumber() == 100) {
        line.setBgAmount(new BigDecimal("1900.00"));
      }
      if (line.getBgLineNumber() == 700) {
        line.setBgAmount(new BigDecimal("162.44"));
      }
    }
    s.recompute(copy);
    assertEquals(new BigDecimal("1461.96"), copy.getBgNetTotal(), "1900 − 275,60 − 162,44");
    s.calculate(copy);
    s.confirm(copy);

    BgPaySlipLine delta = scene[1].getBgPaySlipLineList().get(4);
    assertEquals(BgPaySlipResolver.DEBIT, delta.getBgSign(), "удържа се");
    assertEquals("Удръжка за 03/2026", delta.getName());
    assertEquals(
        new BigDecimal("90.00"),
        delta.getBgAmount(),
        "1461,96 − 1551,96 = −90,00, а сумата на перото е ВИНАГИ положителна — знакът е отделно");
    assertEquals(new BigDecimal("1539.56"), scene[1].getBgNetTotal(), "1629,56 − 90,00");
    assertEquals(MAY_BASE, scene[1].getBgInsuranceIncome(), "и Д1/Д6 пак не мърдат");
  }

  @Test
  @DisplayName(
      "⚠️ НУЛЕВА делта НЕ слага ред — корекция може да смени декларацията, без да мени нето")
  void aZeroDeltaAddsNoLine() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    BgPaySlip copy = s.correct(scene[0]);
    // Преквалификация: същите пари, друго перо — нетото не се мени, декларацията да.
    copy.getBgPaySlipLineList().get(0).setBgLineNumber(180);
    s.recompute(copy);
    assertEquals(MARCH_NET, copy.getBgNetTotal(), "нетото е същото");
    s.calculate(copy);
    s.confirm(copy);

    assertEquals(4, scene[1].getBgPaySlipLineList().size(), "ред за нула не се слага");
    assertEquals(MAY_NET, scene[1].getBgNetTotal());
    assertEquals(
        PeriodRepository.STATUS_CLOSED,
        scene[0].getPeriod().getStatusSelect().intValue(),
        "но периодът пак се затваря");
  }

  // ── 6 · текущият фиш се създава, ако го няма ────────────────────────────

  @Test
  @DisplayName("Ако лицето още няма майски фиш, делтата си създава такъв")
  void theCurrentSlipIsCreatedWhenMissing() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    s.currentDraft = null; // няма текуща чернова

    BgPaySlip copy = s.correct(scene[0]);
    applyTheBonus(s, copy);
    s.calculate(copy);
    s.confirm(copy);

    BgPaySlip created =
        s.savedSlips.stream()
            .filter(x -> x != copy && s.currentPeriod.equals(x.getPeriod()))
            .findFirst()
            .orElse(null);
    assertNotNull(created, "нов майски фиш трябва да е записан: " + s.savedSlips.size());
    assertEquals(BgPaySlipResolver.DRAFT, created.getStatusSelect());
    assertEquals(1, created.getBgPaySlipLineList().size(), "с единствения ред — делтата");
    assertEquals(DELTA, created.getBgNetTotal(), "и нетото му е точно разликата");
    assertSame(scene[0].getPeriod(), created.getBgPaySlipLineList().get(0).getBgCorrectsPeriod());
  }

  // ── 7 · отказите на correct() ───────────────────────────────────────────

  @Test
  @DisplayName("Коригира се само ПОТВЪРДЕН или ИЗПЛАТЕН фиш")
  void onlyAConfirmedOrPaidSlipCanBeCorrected() {
    Service s = new Service();
    BgPaySlip draft = originalMarch(s, march(PeriodRepository.STATUS_CLOSED));
    draft.setStatusSelect(BgPaySlipResolver.DRAFT);

    AxelorException e = assertThrows(AxelorException.class, () -> s.correct(draft));
    assertTrue(e.getMessage().contains("ПОТВЪРДЕН или ИЗПЛАТЕН"), e.getMessage());
    assertEquals(
        PeriodRepository.STATUS_CLOSED,
        draft.getPeriod().getStatusSelect().intValue(),
        "и периодът не е пипнат");
  }

  @Test
  @DisplayName("⚠️ Всеки статус на периода получава СВОЙ отказ — изходите са различни")
  void everyPeriodStatusGetsItsOwnRefusal() {
    Service s = new Service();

    assertTrue(
        refusalFor(s, PeriodRepository.STATUS_OPENED).contains("ОТВОРЕН"),
        "отворен период: откажи фиша и направи нов");
    assertTrue(
        refusalFor(s, PeriodRepository.STATUS_ADJUSTING).contains("ВЕЧЕ"), "вече под корекция");
    assertTrue(
        refusalFor(s, PeriodRepository.STATUS_TEMPORARILY_CLOSED).contains("СЧЕТОВОДСТВОТО"),
        "временно затворен — отказът сочи ЧОВЕК, не бутон");
    assertTrue(
        refusalFor(s, PeriodRepository.STATUS_CLOSURE_IN_PROGRESS).contains("СЧЕТОВОДСТВОТО"),
        "затваряне в ход — същото");
    assertNull(
        BgPaySlipResolver.refuseRetroPeriod(march(PeriodRepository.STATUS_CLOSED), false),
        "и само CLOSED минава");
  }

  private static String refusalFor(Service s, int status) {
    BgPaySlip original = originalMarch(s, march(status));
    AxelorException e = assertThrows(AxelorException.class, () -> s.correct(original));
    return e.getMessage();
  }

  @Test
  @DisplayName("Без ОТВОРЕН заплатен период разликата няма къде да седне — отказ, който казва къде")
  void withoutAnOpenPeriodTheDeltaHasNowhereToGo() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip copy = s.correct(scene[0]);
    applyTheBonus(s, copy);
    s.currentPeriod = null;

    s.calculate(copy);
    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(copy));
    assertTrue(e.getMessage().contains("ОТВОРЕН заплатен период"), e.getMessage());
    assertTrue(e.getMessage().contains("Заплатни години"), e.getMessage());
  }

  @Test
  @DisplayName("Липсващото перо 620 в речника е ОТКАЗ, който сочи файла — не тих липсващ ред")
  void aMissingDictionaryRowIsARefusalThatPointsAtTheFile() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip copy = s.correct(scene[0]);
    applyTheBonus(s, copy);
    s.deltaType = null;

    s.calculate(copy);
    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(copy));
    assertTrue(e.getMessage().contains(BgPaySlipService.CORRECTION_LINE_CODE), e.getMessage());
    assertTrue(e.getMessage().contains("App Install"), e.getMessage());
  }

  @Test
  @DisplayName("⚠️ Признак БЕЗ препратка се отказва — инак фишът не прави нищо и изглежда направен")
  void aCorrectionFlagWithoutAnOriginalIsRefused() {
    Service s = new Service();
    BgPaySlip orphan = monthSlip(s, may(), MAY_BASE, MAY_PERSONAL, MAY_EMPLOYER, MAY_TAX);
    orphan.setBgIsCorrection(true);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(orphan));
    assertTrue(e.getMessage().contains("не сочи оригинала"), e.getMessage());
    assertEquals(
        BgPaySlipResolver.DRAFT,
        orphan.getStatusSelect(),
        "отказът идва ПРЕДИ фишът да се замрази — той е в списъка на confirm(), не по-късно");
  }

  // ── 8 · правилата поотделно ─────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ Маркираният ред е ИЗВЪН агрегатите на декларацията и ВЪТРЕ в нетото")
  void theMarkedLineIsOutOfTheDeclarationAndInsideTheNet() {
    Service s = new Service();
    BgPaySlip may = monthSlip(s, may(), MAY_BASE, MAY_PERSONAL, MAY_EMPLOYER, MAY_TAX);

    // ⚠️ Ред, който Е осигурителен доход и Е облагаем по флагове, но носи МАРКЕР. Флаговете и
    // маркерът се проверяват поотделно нарочно: с изключен само по флаг ред тестът щеше да мине
    // зелен и ако маркерът не се чете изобщо.
    BgPaySlipLine marked =
        s.lineFrom(type(620, "Доплащане за 03/2026", "CREDIT", true, true), DELTA);
    marked.setBgCorrectsPeriod(march(PeriodRepository.STATUS_CLOSED));
    s.addLine(may, marked);

    List<BgPaySlipLine> lines = may.getBgPaySlipLineList();
    assertFalse(BgPaySlipResolver.isOwnPeriod(marked), "редът не е за собствения период");
    assertTrue(BgPaySlipResolver.isOwnPeriod(lines.get(0)), "а заплатата е");
    assertEquals(MAY_BASE, BgPaySlipResolver.insuranceIncomeBase(lines), "извън осиг. доход");
    assertEquals(MAY_TAX_BASE, BgPaySlipResolver.taxBase(lines), "извън данъчната основа");
    assertEquals(MAY_NET_AFTER, BgPaySlipResolver.netTotal(lines), "но ВЪТРЕ в нетото");
  }

  @Test
  @DisplayName("Знакът и името на делта-реда следват знака на разликата")
  void theSignAndNameFollowTheDelta() {
    Period march = march(PeriodRepository.STATUS_CLOSED);
    assertEquals(BgPaySlipResolver.CREDIT, BgPaySlipResolver.deltaSign(DELTA));
    assertEquals(BgPaySlipResolver.DEBIT, BgPaySlipResolver.deltaSign(DELTA.negate()));
    assertEquals(BgPaySlipResolver.CREDIT, BgPaySlipResolver.deltaSign(BigDecimal.ZERO));
    assertEquals("Доплащане за 03/2026", BgPaySlipResolver.deltaLineName(false, DELTA, march));
    assertEquals(
        "Удръжка за 03/2026", BgPaySlipResolver.deltaLineName(false, DELTA.negate(), march));
    assertEquals("03/2026", BgPaySlipResolver.periodLabel(march));

    // ⚠️ Етикетът идва от ДАТИТЕ, не от името: „03/PY2026“ е нашата конвенция за име на период и не
    // бива да изтича в текст, който лицето чете на фиша си.
    Period noDates = new Period();
    noDates.setName("резервно име");
    assertEquals("резервно име", BgPaySlipResolver.periodLabel(noDates), "чак когато дати няма");
    assertEquals("минал период", BgPaySlipResolver.periodLabel(null));
  }

  @Test
  @DisplayName("§ 5.3 · делтата се закръгля до стотинка, половина нагоре")
  void theDeltaIsRoundedToTheCent() {
    assertEquals(
        new BigDecimal("232.79"),
        BgPaySlipResolver.retroDelta(false, FIXED_NET, MARCH_NET),
        "точната разлика");
    assertEquals(
        new BigDecimal("0.01"),
        BgPaySlipResolver.retroDelta(false, new BigDecimal("100.005"), new BigDecimal("100.00")),
        "половина нагоре");
    assertEquals(
        new BigDecimal("-232.79"),
        BgPaySlipResolver.retroDelta(false, MARCH_NET, FIXED_NET),
        "и надолу");
  }

  // ── 9 · кодът в кода и кодът в seed-а са един ───────────────────────────

  private static final Path SEED =
      Paths.get("src/main/resources/apps/init-data/en/bg_pay_slip_line_type.csv");

  @Test
  @DisplayName("⚠️ Кодът, който услугата търси, е кодът, който seed-ът засява")
  void theCodeTheServiceLooksForIsTheCodeTheSeedPlants() throws IOException {
    String seed = new String(Files.readAllBytes(SEED), StandardCharsets.UTF_8);
    assertTrue(
        seed.contains(";" + BgPaySlipService.CORRECTION_LINE_CODE + ";"),
        "⚠️ Услугата търси перо с код „"
            + BgPaySlipService.CORRECTION_LINE_CODE
            + "“, а в bg_pay_slip_line_type.csv такъв ред няма. Разминаването е тихо в най-лошия"
            + " момент: корекцията минава докрай и пада чак когато делтата трябва да седне в"
            + " текущия фиш — тоест след като периодът вече е местен.");
  }
}
