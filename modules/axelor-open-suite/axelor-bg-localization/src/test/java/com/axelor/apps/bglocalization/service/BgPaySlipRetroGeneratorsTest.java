package com.axelor.apps.bglocalization.service;

import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.contract;
import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.draft;
import static com.axelor.apps.bglocalization.service.BgSeededPaySlipService.type;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgPaySlipRegimeChange;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRegime;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveService;
import com.axelor.apps.bglocalization.service.payroll.BgSickNoteArrivalRules;
import com.axelor.apps.bglocalization.service.payroll.BgSickNoteArrivalService;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.LeaveRequest;
import com.axelor.auth.db.User;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОРИГИРАЩОТО КОПИЕ НОСИ ВЕРНИ ПРОИЗВОДНИ РЕДОВЕ — 5xx, 516 по ЗЗО чл. 40 ал. 1 т. 5, 700 и
 * 180/181 от въведената сума, от ЗАМРАЗЕНОТО на копието и по параметрите на ПЕРИОДА му.
 *
 * <p>Истинската услуга с истинските генератори и истинския резолвер на вноските ({@link
 * BgSeededPaySlipService}); подменено е само четенето от базата. Работните дни са по календара на
 * сийда: март 2026 — 21, юли — 23, септември — 20.
 *
 * <p><b>Ставките, вид 01, трета категория, роден след 1959, с ДЗПО</b> (bg_insurance_contribution):
 * лице 6,58 + 2,20 + 1,40 + 0,40 + 3,20 = 13,78 %; осигурител 8,22 + 2,80 + 2,10 + 0,60 + 4,80 =
 * 18,52 % (ГВРС 0). Всяка вноска се закръглява поотделно (НЕВД чл. 2 ал. 4). Данък 10 % върху
 * облагаемото минус личните вноски (чл. 42 ал. 2 ЗДДФЛ).
 */
class BgPaySlipRetroGeneratorsTest {

  private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }

  private static void same(String expected, BigDecimal actual, String what) {
    assertEquals(
        0, bd(expected).compareTo(actual), what + ": очаквах " + expected + ", а е " + actual);
  }

  /** Харнесът с ТЕКУЩИЯ отворен период (октомври), в който сяда делтата от потвърденото копие. */
  private static final class Harness extends BgSeededPaySlipService {
    final Period october = period(2026, 10, PeriodRepository.STATUS_OPENED);
    BgPaySlip current;
    int correctionsPrepared;

    @Override
    protected Period currentOpenPeriod(BgPaySlip slip) {
      return october;
    }

    @Override
    protected BgPaySlip openDraftSlip(EmploymentContract contract, Period period) {
      return current;
    }

    @Override
    protected BgPaySlipLineType correctionLineType() {
      return type("pl_korekcia_minal_period");
    }

    @Override
    protected void generatePayrollMove(BgPaySlip slip) {}

    @Override
    protected LocalDate today() {
      return TODAY;
    }

    @Override
    protected void prepareCorrection(BgPaySlip copy) {
      correctionsPrepared++;
      super.prepareCorrection(copy);
    }

    /** Текущият отворен фиш за октомври — празен, за да се види само перото на делтата. */
    BgPaySlip openCurrent(EmploymentContract contract) {
      current = new BgPaySlip();
      current.setStatusSelect(BgPaySlipResolver.DRAFT);
      current.setEmploymentContract(contract);
      current.setPeriod(october);
      current.setBgPaySlipLineList(new ArrayList<>());
      return current;
    }
  }

  static Period period(int year, int month, int status) {
    Year y = new Year();
    y.setName("Заплатна " + year);
    y.setTypeSelect(2);
    y.setFromDate(LocalDate.of(year, 1, 1));
    y.setToDate(LocalDate.of(year, 12, 31));
    Period p = new Period();
    p.setName(String.format("%02d/%d", month, year));
    p.setYear(y);
    p.setFromDate(LocalDate.of(year, month, 1));
    p.setToDate(p.getFromDate().withDayOfMonth(p.getFromDate().lengthOfMonth()));
    p.setStatusSelect(status);
    return p;
  }

  private static BgPaySlip draftIn(EmploymentContract contract, Period period) {
    BgPaySlip slip = draft(contract);
    slip.setPeriod(period);
    return slip;
  }

  /** Потвърждава оригинала и затваря месеца му — както го заварва корекцията. */
  private static void confirmAndClose(Harness s, BgPaySlip original) throws AxelorException {
    s.calculate(original);
    s.confirm(original);
    original.getPeriod().setStatusSelect(PeriodRepository.STATUS_CLOSED);
  }

  /** Заявка за отпуск — редът на LEAVE_ROWS_JPQL, единайсет колони, в ДНИ. */
  private static Object[] leave(
      LocalDate from,
      LocalDate to,
      int days,
      int category,
      long typeId,
      String d1Code,
      boolean countsAsInsurance,
      String healthRule) {
    return new Object[] {
      from.atStartOfDay(),
      to.atStartOfDay(),
      new BigDecimal(days),
      BgPayrollInputBuilder.LEAVE_UNIT_DAYS,
      category,
      false,
      typeId,
      false,
      d1Code,
      countsAsInsurance,
      healthRule
    };
  }

  /**
   * Два дни неплатен отпуск по желание (до 30 дни): вид 12, т. 16.5, вноската по б. „б“ — лицето.
   */
  private static Object[] unpaidTwoDaysInMarch() {
    return leave(
        LocalDate.of(2026, 3, 10),
        LocalDate.of(2026, 3, 11),
        2,
        7,
        12L,
        "16.5",
        true,
        "unpaid_employee");
  }

  private static BgPaySlipLine bonus200(Harness s) {
    BgPaySlipLine bonus = s.lineFrom(type("pl_postoyanen_bonus"), BigDecimal.ZERO);
    bonus.setBgEnteredAmount(bd("200.00"));
    return bonus;
  }

  private static BigDecimal fund(BgPaySlip slip, String fund, String sign) {
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (l.getBgContributionFund() != null
          && fund.equals(l.getBgContributionFund().getCode())
          && sign.equals(l.getBgSign())
          && BgD1ContributionAggregator.BASE_STANDARD.equals(
              BgD1ContributionAggregator.healthBaseOf(l))) {
        return l.getBgAmount();
      }
    }
    return null;
  }

  /** Базата на вноската — {@code bgQuantity} на перото: върху нея е сметната сумата. */
  private static BigDecimal base(BgPaySlip slip, String fund, String sign) {
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (l.getBgContributionFund() != null
          && fund.equals(l.getBgContributionFund().getCode())
          && sign.equals(l.getBgSign())
          && BgD1ContributionAggregator.BASE_STANDARD.equals(
              BgD1ContributionAggregator.healthBaseOf(l))) {
        return l.getBgQuantity();
      }
    }
    return null;
  }

  private static BgPaySlipLine baseLine(BgPaySlip slip, String baseKind) {
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (BgPaySlipResolver.groupOf(l) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
          && baseKind.equals(BgD1ContributionAggregator.healthBaseOf(l))) {
        return l;
      }
    }
    return null;
  }

  private static BigDecimal sum5xx(BgPaySlip slip, String sign) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (BgPaySlipResolver.groupOf(l) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
          && sign.equals(l.getBgSign())) {
        sum = sum.add(l.getBgAmount());
      }
    }
    return sum;
  }

  private static BgPaySlipLine byCode(BgPaySlip slip, String code) {
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (l.getBgPaySlipLineType() != null && code.equals(l.getBgPaySlipLineType().getCode())) {
        return l;
      }
    }
    return null;
  }

  private static final String CREDIT = BgPaySlipResolver.CREDIT;
  private static final String DEBIT = BgPaySlipResolver.DEBIT;

  // ═══ 1 · „КОРИГИРАЙ“ С ИЗГУБЕН БОНУС 200 ═════════════════════════════════════════════

  /**
   *
   *
   * <pre>
   * ОРИГИНАЛЪТ, март 2026: 1500,00 · вноски върху 1500,00
   *   лице  98,70 + 33,00 + 21,00 + 6,00 + 48,00 = 206,70
   *   осиг. 123,30 + 42,00 + 31,50 + 9,00 + 72,00 = 277,80
   *   данък (1500,00 − 206,70) × 10 % = 129,33 · нето 1500,00 − 206,70 − 129,33 = 1163,97
   * КОПИЕТО: + перо 180 = 200,00 (въведено) · вноски върху 1700,00
   *   лице  111,86 + 37,40 + 23,80 + 6,80 + 54,40 = 234,26
   *   осиг. 139,74 + 47,60 + 35,70 + 10,20 + 81,60 = 314,84
   *   данък (1700,00 − 234,26) × 10 % = 146,574 → 146,57 · нето 1700 − 234,26 − 146,57 = 1319,17
   * РАЗЛИКАТА: лични +27,56 · осиг. +37,04 · данък +17,24 · нето +155,20 — точно ефектът от 200
   * </pre>
   */
  @Test
  @DisplayName("„Коригирай“ с изгубен бонус 200 → 180 = 200, вноски и данък наново; делтата 155,20")
  void aLostBonusIsComputedOnTheCopyAndItsDeltaReachesTheCurrentSlip() throws AxelorException {
    Harness s = new Harness();
    BgPaySlip original = draft(contract());
    confirmAndClose(s, original);
    same("206.70", sum5xx(original, DEBIT), "котвата: личните вноски на оригинала");
    same("1163.97", original.getBgNetTotal(), "котвата: нетото на оригинала");

    // ⚠️ Отпуск, въведен ПО-КЪСНО за онзи месец — живите данни вече не са тези на оригинала.
    // Копието не бива да ги види: 180 по живото щеше да е 200 × 19/21 = 180,95.
    s.builder.leave.add(unpaidTwoDaysInMarch());

    BgPaySlip copy = s.correct(original);
    BgPaySlipLine bonus = bonus200(s);
    s.addLine(copy, bonus);
    s.calculate(copy);

    same("200.00", bonus.getBgAmount(), "180 от въведената сума, по ЗАМРАЗЕНИТЕ дни (без отпуск)");
    same("1700.00", copy.getBgInsuranceIncome(), "осигурителният доход");
    same("111.86", fund(copy, "PENSION", DEBIT), "пенсии, лице");
    same("37.40", fund(copy, "UPF", DEBIT), "УПФ, лице");
    same("23.80", fund(copy, "OZM", DEBIT), "ОЗМ, лице");
    same("6.80", fund(copy, "UNEMPLOYMENT", DEBIT), "безработица, лице");
    same("54.40", fund(copy, "HEALTH", DEBIT), "ЗО, лице");
    same("234.26", sum5xx(copy, DEBIT), "личните вноски");
    same("314.84", sum5xx(copy, CREDIT), "вноските на осигурителя");
    same("1465.74", copy.getBgTaxBase(), "данъчната основа");
    same("146.57", copy.getBgTaxAmount(), "данъкът");
    same("1319.17", copy.getBgNetTotal(), "нетото");

    same("27.56", sum5xx(copy, DEBIT).subtract(sum5xx(original, DEBIT)), "Δ лични");
    same("37.04", sum5xx(copy, CREDIT).subtract(sum5xx(original, CREDIT)), "Δ осигурител");
    same("17.24", copy.getBgTaxAmount().subtract(original.getBgTaxAmount()), "Δ данък");
    same("155.20", copy.getBgNetTotal().subtract(original.getBgNetTotal()), "Δ нето");

    // Копието се потвърждава; делтата сяда в ТЕКУЩИЯ фиш като отделно перо.
    BgPaySlip current = s.openCurrent(original.getEmploymentContract());
    s.confirm(copy);
    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect());
    BgPaySlipLine delta = byCode(current, "pl_korekcia_minal_period");
    assertNotNull(delta, "перото на делтата е в текущия фиш");
    same("155.20", delta.getBgAmount(), "с вярната сума");
    assertEquals(CREDIT, delta.getBgSign(), "в полза на лицето");
    assertEquals(original.getPeriod(), delta.getBgCorrectsPeriod(), "и сочи коригирания месец");
  }

  // ═══ 2 · ТАВАНЪТ Е ТОЗИ НА ПЕРИОДА ═════════════════════════════════════════════════

  /**
   *
   *
   * <pre>
   * ЮЛИ 2026, 2500,00 — таванът до 31.07.2026 е 2111,64 (от 01.08 — 2300,00)
   *   лице върху 2111,64: 138,95 + 46,46 + 29,56 + 8,45 + 67,57 = 290,99
   *   осиг.:            173,58 + 59,13 + 44,34 + 12,67 + 101,36 = 391,08
   *   оригинал: данък (2500,00 − 290,99) × 10 % = 220,90 · нето 1988,11
   *   копие + 180 = 200: вноските СЪЩИТЕ (таван) · данък (2700 − 290,99) × 10 % = 240,90
   *     · нето 2168,11
   * Днешният таван (2300,00) би дал лични 316,94 — мутацията „днешният таван“ пада тук.
   * </pre>
   */
  @Test
  @DisplayName("копие за 07/2026 с бруто над 2111,64 → вноските са върху 2111,64, не върху 2300")
  void aJulyCopyIsInsuredUpToTheJulyCap() throws AxelorException {
    Harness s = new Harness();
    EmploymentContract c = contract();
    c.setBgBaseSalary(bd("2500.00"));
    BgPaySlip original = draftIn(c, period(2026, 7, 0));
    confirmAndClose(s, original);
    same(
        "2111.64",
        base(original, "PENSION", DEBIT),
        "котвата: базата на вноските — таванът на юли");
    same("290.99", sum5xx(original, DEBIT), "котвата: личните вноски на юли");

    BgPaySlip copy = s.correct(original);
    s.addLine(copy, bonus200(s));
    s.calculate(copy);

    same("2111.64", base(copy, "PENSION", DEBIT), "базата на вноските на копието — юлският таван");
    same("2111.64", base(copy, "HEALTH", CREDIT), "и на осигурителя");
    same("138.95", fund(copy, "PENSION", DEBIT), "пенсии, лице — върху 2111,64");
    same("290.99", sum5xx(copy, DEBIT), "личните вноски не мърдат");
    same("391.08", sum5xx(copy, CREDIT), "и вноските на осигурителя");
    same("240.90", copy.getBgTaxAmount(), "данъкът расте с 20,00");
    same("180.00", copy.getBgNetTotal().subtract(original.getBgNetTotal()), "Δ нето");
  }

  // ═══ 3 · ЗАЛИЧАВАЩОТО КОПИЕ ════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "заличаващо копие → без генератори, разлика нула перо по перо, отпечатък от раждането")
  void aDeletingCopyRunsNoGeneratorAndDiffersByZero() throws AxelorException {
    Harness s = new Harness();
    BgPaySlip original = draft(contract());
    confirmAndClose(s, original);

    BgPaySlip copy = s.voidRecord(original);
    assertNotNull(copy.getBgCalcFingerprint(), "пресметнато по устройство още при раждането");
    s.calculate(copy);

    assertEquals(0, s.correctionsPrepared, "нито един генератор не е пуснат върху него");
    assertEquals(original.getBgPaySlipLineList().size(), copy.getBgPaySlipLineList().size());
    for (int i = 0; i < copy.getBgPaySlipLineList().size(); i++) {
      BgPaySlipLine o = original.getBgPaySlipLineList().get(i);
      BgPaySlipLine c = copy.getBgPaySlipLineList().get(i);
      same("0", c.getBgAmount().subtract(o.getBgAmount()), "перо " + o.getBgLineNumber());
    }
    same("0", copy.getBgNetTotal().subtract(original.getBgNetTotal()), "нетото");
    same("0", copy.getBgTaxAmount().subtract(original.getBgTaxAmount()), "данъкът");

    // Потвърждаването минава; към лицето отива МИНУС цялото оригинално нето.
    BgPaySlip current = s.openCurrent(original.getEmploymentContract());
    s.confirm(copy);
    BgPaySlipLine delta = byCode(current, "pl_korekcia_minal_period");
    same("1163.97", delta.getBgAmount(), "сторно на цялото нето");
    assertEquals(DEBIT, delta.getBgSign(), "в тежест на лицето");
  }

  // ═══ 4 · ОТРАЗИ ПРИСТИГНАЛ БОЛНИЧЕН — ПЪЛНИТЕ СТАВКИ ЗА ВИД 01 ═════════════════════

  /** Отразяването с подменени шевове към базата; разбивът и пресмятането са истинските. */
  private static final class Arrival extends BgSickNoteArrivalService {
    private final Harness s;
    private final BgPaySlip original;

    Arrival(Harness s, BgPaySlip original) {
      this.s = s;
      this.original = original;
    }

    @Override
    protected BgPaySlip originalSlipFor(BgSickLeave sickLeave) {
      return original;
    }

    @Override
    protected List<BgSickNoteArrivalRules.RetroCopy> existingRetroOf(BgPaySlip slip) {
      return Collections.emptyList();
    }

    @Override
    protected List<BgSickNoteArrivalRules.LeaveSpan> cancellableLeave(BgSickLeave sickLeave) {
      return Collections.singletonList(
          new BgSickNoteArrivalRules.LeaveSpan(
              5L, sickLeave.getFromDate(), sickLeave.getToDate(), "Непредставен болничен"));
    }

    @Override
    protected LeaveRequest leaveRequestById(Long id) {
      return new LeaveRequest();
    }

    /** Стандартната отмяна: заявката излиза от живите прочити — и от сглобителя на харнеса. */
    @Override
    protected void cancelLeaveRequest(LeaveRequest request) {
      s.builder.leave.clear();
    }

    @Override
    protected void saveSickLeave(BgSickLeave sickLeave) {}

    @Override
    protected void saveSlip(BgPaySlip slip) {}

    @Override
    protected BgPaySlipService paySlipService() {
      return s;
    }

    @Override
    protected BgSickLeaveService sickLeaveService() {
      return s.sickLeave;
    }

    @Override
    protected LocalDate today() {
      return TODAY;
    }

    @Override
    protected User currentUser() {
      return null;
    }
  }

  private static final LocalDate SICK_FROM = LocalDate.of(2026, 9, 15);
  private static final LocalDate SICK_TO = LocalDate.of(2026, 9, 17);

  /**
   * Септември 2026, 2000,00, 20 работни дни по календара (07.09 и 22.09 са празници), 15–17.09
   * непредставен болничен — извън осигуряването, основната е 2000,00 × 17/20 = 1700,00.
   */
  private static BgPaySlip septemberWithUnsubmittedSickNote(Harness s) throws AxelorException {
    EmploymentContract c = contract();
    c.setStartDate(LocalDate.of(2026, 1, 1)); // под година стаж: без клас
    c.setBgBaseSalary(bd("2000.00"));
    c.getEmployee().setBgInsuranceServiceDeclared(true);
    c.getEmployee().setBgInsuranceServiceMonths(120);
    s.builder.leave.add(leave(SICK_FROM, SICK_TO, 3, 2, 21L, "", false, "standard"));
    BgPaySlip original = draftIn(c, period(2026, 9, 0));
    confirmAndClose(s, original);
    return original;
  }

  private static BgSickLeave sickNote(BgPaySlip original) {
    BgSickLeave sick = new BgSickLeave();
    sick.setId(9L);
    sick.setStatusSelect(BgPayrollInputBuilder.SICK_LEAVE_VALIDATED);
    sick.setFromDate(SICK_FROM);
    sick.setToDate(SICK_TO);
    sick.setBgDocumentNumber("БЛ-915/2026");
    sick.setBgEmployerReceivedDate(LocalDate.of(2026, 10, 1));
    sick.setEmploymentContract(original.getEmploymentContract());
    return sick;
  }

  private static void arriving(Harness s) {
    s.builder.episodes.add(
        new BgPayrollInput.SickLeaveEpisode(
            SICK_FROM, SICK_TO, false, "БЛ-915/2026", "sick_er", 9L));
  }

  /**
   *
   *
   * <pre>
   * ОРИГИНАЛЪТ: 100 = 1700,00 · вноски върху 1700,00
   *   лице 234,26 · осиг. 314,84 · данък (1700 − 234,26) × 10 % = 146,57 · нето 1319,17
   * КОПИЕТО: 15 и 16.09 — осигурителят, 17.09 — ДОО
   *   195 = 2000,00 / 20 × 70 % × 2 = 140,00 (осигурителен доход, НЕоблагаем)
   *   516 по ЗЗО чл. 40 ал. 1 т. 5 = 620,20 × 4,8 % × 1/20 = 1,48848 → 1,49
   *     (база 620,20/20 = 31,01)
   *   вноски върху 1700,00 + 140,00 = 1840,00
   *     лице  121,07 + 40,48 + 25,76 + 7,36 + 58,88 = 253,55
   *     осиг. 151,25 + 51,52 + 38,64 + 11,04 + 88,32 = 340,77 (+ 1,49 по т. 5)
   *   данък: облагаемото е 1700,00 (195 не е), но ВСИЧКИ лични вноски — и тези върху 195 —
   *     намаляват основата (чл. 42 ал. 2 ЗДДФЛ): (1700,00 − 253,55) × 10 % = 144,645 → 144,65
   *   нето 1840,00 − 253,55 − 144,65 = 1441,80 · разход 1840,00 + 340,77 + 1,49 = 2182,26
   * РАЗЛИКАТА копие − оригинал: бруто +140,00 · лични +19,29 · осиг. +27,42 · данък −1,92 ·
   *   нето +122,63
   * </pre>
   */
  @Test
  @DisplayName(
      "пристигнал болничен, пълни ставки за вид 01: 195, вноски върху 195, 516, данък, нето")
  void anArrivedSickNoteComputesEveryDerivedLine() throws AxelorException {
    Harness s = new Harness();
    BgPaySlip original = septemberWithUnsubmittedSickNote(s);
    same("1700.00", byCode(original, "pl_osnovna").getBgAmount(), "котвата: 2000 × 17/20");
    same("234.26", sum5xx(original, DEBIT), "котвата: лични на оригинала");
    same("1319.17", original.getBgNetTotal(), "котвата: нето на оригинала");

    arriving(s);
    BgSickLeave sheet = sickNote(original);
    s.sickLeave.sheets.put(sheet.getId(), sheet);
    BgPaySlip copy = new Arrival(s, original).apply(sheet);

    // Т. 9 на пристигналия лист: септември 2026, два дни осигурителят, един ДОО.
    assertPoint9(sheet);

    same(
        "140.00",
        byCode(copy, "pl_bolnichen_rabotodatel").getBgAmount(),
        "195: 2000/20 × 70 % × 2");
    BgPaySlipLine zzo = baseLine(copy, BgD1ContributionAggregator.BASE_ART40_1_5);
    assertNotNull(zzo, "⚠️ перото 516 по ЗЗО чл. 40 ал. 1 т. 5 се ражда");
    same("1.49", zzo.getBgAmount(), "516 по т. 5");
    same("31.01", zzo.getBgQuantity(), "и базата му — 620,20 × 1/20");
    same("1840.00", copy.getBgInsuranceIncome(), "осигурителният доход с 195");
    same("121.07", fund(copy, "PENSION", DEBIT), "пенсии, лице — върху 1840,00");
    same("253.55", sum5xx(copy, DEBIT), "личните вноски — ⚠️ и върху 195");
    same("342.26", sum5xx(copy, CREDIT), "осигурителят: 340,77 + 1,49");
    same("1446.45", copy.getBgTaxBase(), "данъчната основа: 1700,00 − 253,55");
    same("144.65", copy.getBgTaxAmount(), "данъкът");
    same("1441.80", copy.getBgNetTotal(), "нетото");
    same("2182.26", copy.getBgEmployerCost(), "разходът за работодателя");

    same("140.00", copy.getBgGrossTotal().subtract(original.getBgGrossTotal()), "Δ бруто");
    same("19.29", sum5xx(copy, DEBIT).subtract(sum5xx(original, DEBIT)), "Δ лични");
    same("27.42", sum5xx(copy, CREDIT).subtract(sum5xx(original, CREDIT)), "Δ осигурител");
    same("-1.92", copy.getBgTaxAmount().subtract(original.getBgTaxAmount()), "Δ данък");
    same("122.63", copy.getBgNetTotal().subtract(original.getBgNetTotal()), "Δ нето");

    // ⚠️ И ПАЗАЧЪТ МЪЛЧИ: правилно сметнатото копие се потвърждава, делтата сяда в текущия фиш.
    BgPaySlip current = s.openCurrent(original.getEmploymentContract());
    s.confirm(copy);
    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect());
    same("122.63", byCode(current, "pl_korekcia_minal_period").getBgAmount(), "делтата");
  }

  /**
   * Т. 9 на листа за 15–17.09.2026 (вт–чт, без празници): два реда за септември, по платец — два
   * работни дни за сметка на осигурителя (чл. 40 ал. 5 КСО), един за сметка на ДОО. Всеки ред носи
   * границите на листа в месеца (15–17), а платецът се различава по дните си. Подневно отчитане, 8
   * часа на ден — оттам и часовете: 16 и 8.
   */
  private static void assertPoint9(BgSickLeave sheet) {
    List<BgSickLeavePeriodLine> rows = sheet.getBgPeriodLineList();
    assertNotNull(rows, "т. 9 е записана");
    assertEquals(2, rows.size(), "два реда — осигурител и ДОО: " + rows);
    BgSickLeavePeriodLine employer = rows.get(0);
    BgSickLeavePeriodLine noi = rows.get(1);
    assertEquals("EMPLOYER", employer.getPayer());
    assertEquals(2026, employer.getPeriodYear().intValue());
    assertEquals(9, employer.getPeriodMonth().intValue());
    assertEquals(SICK_FROM, employer.getFromDay(), "границите на листа в месеца");
    assertEquals(SICK_TO, employer.getToDay());
    assertEquals(2, employer.getWorkDays().intValue(), "два работни дни за осигурителя");
    same("8.00", employer.getDailyHours(), "дневното работно време");
    same("16.00", employer.getWorkHours(), "часовете при подневно отчитане");
    assertEquals("NOI", noi.getPayer());
    assertEquals(9, noi.getPeriodMonth().intValue());
    assertEquals(SICK_FROM, noi.getFromDay());
    assertEquals(SICK_TO, noi.getToDay());
    assertEquals(1, noi.getWorkDays().intValue(), "един ден за ДОО");
    same("8.00", noi.getWorkHours(), "часовете на ДОО");
  }

  @Test
  @DisplayName("редовен фиш с болничен → т. 9 на листа, по месеци, в calculate()")
  void aRegularSlipWithASickNoteWritesPoint9() throws AxelorException {
    Harness s = new Harness();
    EmploymentContract c = contract();
    c.setStartDate(LocalDate.of(2026, 1, 1));
    c.setBgBaseSalary(bd("2000.00"));
    c.getEmployee().setBgInsuranceServiceDeclared(true);
    c.getEmployee().setBgInsuranceServiceMonths(120);
    BgSickLeave sheet = new BgSickLeave();
    sheet.setId(11L);
    sheet.setFromDate(SICK_FROM);
    sheet.setToDate(SICK_TO);
    sheet.setBgDocumentNumber("БЛ-911/2026");
    s.sickLeave.sheets.put(sheet.getId(), sheet);
    s.builder.episodes.add(
        new BgPayrollInput.SickLeaveEpisode(
            SICK_FROM, SICK_TO, false, "БЛ-911/2026", "sick_er", 11L));

    BgPaySlip slip = draftIn(c, period(2026, 9, 0));
    s.calculate(slip);

    same("140.00", byCode(slip, "pl_bolnichen_rabotodatel").getBgAmount(), "195 на редовния фиш");
    assertEquals(2, slip.getBgSickDaysEmployer());
    assertEquals(1, slip.getBgSickDaysDoo());
    assertPoint9(sheet);

    s.calculate(slip);
    assertEquals(2, sheet.getBgPeriodLineList().size(), "второ пресмятане презаписва, не дублира");
  }

  @Test
  @DisplayName("чл. 40 ал. 5 КСО · базата на 195 е основната + класът за ЦЕЛИЯ месец: 147,00")
  void theSickPayBaseCarriesThePermanentAdditions() throws AxelorException {
    Harness s = new Harness();
    EmploymentContract c = contract();
    c.setStartDate(LocalDate.of(2026, 1, 1));
    c.setBgPriorExperienceYears(5);
    c.setBgBaseSalary(bd("2000.00"));
    Company company = new Company();
    company.setBgSeniorityRatePct(bd("1.00"));
    c.setPayCompany(company);
    c.getEmployee().setBgInsuranceServiceDeclared(true);
    c.getEmployee().setBgInsuranceServiceMonths(120);
    BgSickLeave sheet = new BgSickLeave();
    sheet.setId(11L);
    sheet.setFromDate(SICK_FROM);
    sheet.setToDate(SICK_TO);
    sheet.setBgDocumentNumber("БЛ-911/2026");
    s.sickLeave.sheets.put(sheet.getId(), sheet);
    s.builder.episodes.add(
        new BgPayrollInput.SickLeaveEpisode(
            SICK_FROM, SICK_TO, false, "БЛ-911/2026", "sick_er", 11L));

    BgPaySlip slip = draftIn(c, period(2026, 9, 0));
    s.calculate(slip);

    // Септември 2026: 20 работни дни (07.09 и 22.09 са празници), болничен 15–17.09 (вт–чт) → 2 дни
    // за работодателя + 1 за ДОО, отработени 17. Класът: 5 години × 1 % = 5 % от 2000,00 = 100,00.
    assertEquals(2, slip.getBgSickDaysEmployer());
    assertEquals(1, slip.getBgSickDaysDoo());
    same("85.00", byCode(slip, "pl_tspo").getBgAmount(), "110 на фиша: 100 × 17/20");
    BgPaySlipLine sickPay = byCode(slip, "pl_bolnichen_rabotodatel");
    // (2000,00 + 100,00) / 20 × 70 % × 2 = 147,00. Само основната щеше да даде 140,00, а
    // показаните (пропорционирани) пера — (1700,00 + 85,00) / 20 × 70 % × 2 = 124,95.
    same("147.00", sickPay.getBgAmount(), "⚠️ 195 върху основната + класа за целия месец");
    same("105.0000", sickPay.getBgRate(), "среднодневното: 2100,00 / 20");
    same("2", sickPay.getBgQuantity(), "два дни за сметка на работодателя");
  }

  @Test
  @DisplayName("пресмятането на екрана: същите числа, НИТО ЕДИН ред по т. 9, без отпечатък")
  void theScreenCalculationWritesNoPoint9() throws AxelorException {
    Harness s = new Harness();
    EmploymentContract c = contract();
    c.setStartDate(LocalDate.of(2026, 1, 1));
    c.setBgBaseSalary(bd("2000.00"));
    c.getEmployee().setBgInsuranceServiceDeclared(true);
    c.getEmployee().setBgInsuranceServiceMonths(120);
    BgSickLeave sheet = new BgSickLeave();
    sheet.setId(11L);
    sheet.setFromDate(SICK_FROM);
    sheet.setToDate(SICK_TO);
    sheet.setBgDocumentNumber("БЛ-911/2026");
    s.sickLeave.sheets.put(sheet.getId(), sheet);
    s.builder.episodes.add(
        new BgPayrollInput.SickLeaveEpisode(
            SICK_FROM, SICK_TO, false, "БЛ-911/2026", "sick_er", 11L));

    BgPaySlip slip = draftIn(c, period(2026, 9, 0));
    slip.setBgCalcFingerprint("остатък от предишно „Преизчисли“");
    s.preview(slip);

    // Числата са ТЕЗИ на calculate() от свидетеля по-горе: 2000/20 × 70 % × 2 = 140,00, дни 2 + 1.
    same("140.00", byCode(slip, "pl_bolnichen_rabotodatel").getBgAmount(), "195 и на екрана");
    assertEquals(2, slip.getBgSickDaysEmployer());
    assertEquals(1, slip.getBgSickDaysDoo());
    assertTrue(
        sheet.getBgPeriodLineList() == null || sheet.getBgPeriodLineList().isEmpty(),
        "⚠️ т. 9 записана от пресмятане на ЕКРАНА: " + sheet.getBgPeriodLineList());
    assertEquals(0, s.sickLeave.sheetSaves, "⚠️ листът записан без записан фиш");
    assertNull(
        slip.getBgCalcFingerprint(),
        "отпечатъкът се ИЗТРИВА: потвърждава се само фиш след „Преизчисли“, заедно с т. 9");
    BigDecimal netOnScreen = slip.getBgNetTotal();

    s.calculate(slip);
    assertPoint9(sheet);
    assertEquals(1, s.sickLeave.sheetSaves, "„Преизчисли“ записва листа — веднъж");
    assertNotNull(slip.getBgCalcFingerprint());
    same(netOnScreen.toPlainString(), slip.getBgNetTotal(), "и нетото е същото като на екрана");
  }

  // ═══ 5 · 3А — ОТРАЗЯВАНЕ ВЪРХУ ЗАВАРЕН ОРИГИНАЛ ════════════════════════════════════

  @Test
  @DisplayName(
      "3А: заварен оригинал — планът показва режима, OK го попълва с одит „при отразяване“")
  void anArrivalOnALegacyOriginalFillsTheRegimeAsAnExplicitAction() throws AxelorException {
    Harness s = new Harness();
    BgPaySlip original = septemberWithUnsubmittedSickNote(s);
    original.setBgRegimeFrozenAt(null); // потвърден преди замразяването на режима
    arriving(s);
    Arrival arrival = new Arrival(s, original);

    String plan = arrival.plan(sickNote(original));
    assertTrue(plan.contains("ПОПЪЛВАТ от текущите данни"), plan);
    assertTrue(plan.contains("вид осигурен: 01"), "стойността, ПРЕДИ OK: " + plan);
    assertTrue(plan.contains("облекчение по ТЕЛК: не"), plan);
    assertTrue(plan.contains("ЗЗО чл. 40 ал. 1 т. 5"), "и обещанието за 516: " + plan);

    BgPaySlip copy = arrival.apply(sickNote(original));

    assertEquals(BgPayrollRegime.FILLED, copy.getBgRegimeSource());
    List<BgPaySlipRegimeChange> rows = copy.getBgRegimeChangeList();
    assertEquals(BgPayrollRegime.fieldsFor(copy).size(), rows.size(), "по ред на поле");
    for (BgPaySlipRegimeChange row : rows) {
      assertEquals(BgPaySlipRegimeAudit.FILLED, row.getSource(), row.getFieldName());
      assertEquals("при отразяване на болничен лист № БЛ-915/2026", row.getNote());
    }
    assertTrue(
        copy.getBgRegimeChangeSummary()
            .startsWith("Режимът е попълнен при отразяване на болничен лист № БЛ-915/2026"),
        copy.getBgRegimeChangeSummary());
    same("1441.80", copy.getBgNetTotal(), "и копието е пресметнато като при замразен оригинал");
  }

  // ═══ 6 · 1А — ДНИТЕ НА КАРВА ═══════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "1А: заварен оригинал с отсъствия и 180 → копието отказва поименно; „Попълни“ с одит")
  void aLegacyOriginalWithAbsencesRefusesUntilTheCarveDaysAreFilled() throws AxelorException {
    Harness s = new Harness();
    s.builder.leave.add(unpaidTwoDaysInMarch());
    BgPaySlip original = draft(contract());
    s.addLine(original, bonus200(s));
    confirmAndClose(s, original);
    same(
        "180.95",
        byCode(original, "pl_postoyanen_bonus").getBgAmount(),
        "котвата: 200 × 19/21 — два дни неплатен");
    original.setBgCarveDaysFrozenAt(null); // потвърден преди замразяването на дните на карва

    BgPaySlip copy = s.correct(original);
    BgRegimeMissingException refused =
        assertThrows(BgRegimeMissingException.class, () -> s.calculate(copy));
    assertTrue(
        refused
            .getMessage()
            .contains(
                "Липсват: дни платен отпуск по чл. 177, неплатени дни, които Д1 вади от т. 16.1."),
        refused.getMessage());
    assertTrue(refused.getMessage().contains("Попълни от текущите данни"), refused.getMessage());

    s.fillRegimeFromCurrent(copy, null);
    same("0", copy.getBgArt177LeaveDays(), "дни по чл. 177 — от текущите заявки");
    same("2", copy.getBgUnpaidLeaveDaysInD1(), "неплатени в Д1 — от текущите заявки");
    assertNotNull(copy.getBgCarveDaysFrozenAt());
    assertTrue(
        copy.getBgRegimeChangeSummary().contains("Дните на карва са попълнени от текущите данни"),
        copy.getBgRegimeChangeSummary());
    int filledDays = 0;
    for (BgPaySlipRegimeChange row : copy.getBgRegimeChangeList()) {
      if ("bgUnpaidLeaveDaysInD1".equals(row.getFieldName())) {
        assertEquals("—", row.getOldValue());
        assertEquals("2,00", row.getNewValue());
        assertEquals(BgPaySlipRegimeAudit.FILLED, row.getSource());
        filledDays++;
      }
    }
    assertEquals(1, filledDays, "одитният ред за неплатените дни");

    s.calculate(copy);
    same("180.95", byCode(copy, "pl_postoyanen_bonus").getBgAmount(), "180 по дните на онзи месец");
  }

  // ═══ 7 · „САМО ЗДРАВНО“ ════════════════════════════════════════════════════════════

  @Test
  @DisplayName("„само здравно“ остава копирано; сменени дни при непипнато перо → отказ")
  void aStaleHealthOnlyLineRefusesUntilCorrectedByHand() throws AxelorException {
    Harness s = new Harness();
    s.unpaidHealth.rules.add("unpaid_employee");
    s.builder.leave.add(unpaidTwoDaysInMarch());
    BgPaySlip original = draft(contract());
    confirmAndClose(s, original);
    BgPaySlipLine was = baseLine(original, BgD1ContributionAggregator.BASE_HEALTH_ONLY);
    assertNotNull(was, "котвата: оригиналът носи „само здравно“");

    BgPaySlip copy = s.correct(original);
    s.calculate(copy);
    BgPaySlipLine kept = baseLine(copy, BgD1ContributionAggregator.BASE_HEALTH_ONLY);
    same(was.getBgAmount().toPlainString(), kept.getBgAmount(), "без промяна в дните — копирано");

    copy.setBgLeaveDays165(bd("1.00")); // дните, които движат вноската, вече са други
    BgCalculationRefusedException refused =
        assertThrows(BgCalculationRefusedException.class, () -> s.calculate(copy));
    assertTrue(refused.getMessage().startsWith("„Само здравно“"), refused.getMessage());
    assertTrue(refused.getMessage().contains("т. 16.5 2,00 → 1,00"), refused.getMessage());

    kept.setBgAmount(bd("1.05"));
    kept.setBgQuantity(bd("13.11"));
    s.calculate(copy);
    same("1.05", kept.getBgAmount(), "поправеното на ръка е решение и остава");
  }

  @Test
  @DisplayName("ръчната поправка на „само здравно“ е в одита — поле, старо, ново, кой, кога")
  void aManualHealthOnlyCorrectionIsAudited() throws AxelorException {
    Harness s = new Harness();
    s.unpaidHealth.rules.add("unpaid_employee");
    s.builder.leave.add(unpaidTwoDaysInMarch());
    BgPaySlip original = draft(contract());
    confirmAndClose(s, original);
    BgPaySlip copy = s.correct(original);
    BgPaySlipLine line = baseLine(copy, BgD1ContributionAggregator.BASE_HEALTH_ONLY);
    String amountWas = BgPaySlipRegimeAudit.days(line.getBgAmount());
    String baseWas = BgPaySlipRegimeAudit.days(line.getBgQuantity());

    // Операторът поправя перото на ръка и записва формата (BgPaySlipManagementRepository#save).
    line.setBgAmount(bd("1.05"));
    line.setBgQuantity(bd("13.11"));
    User operator = new User();
    operator.setCode("trz");
    LocalDateTime at = LocalDateTime.of(2026, 10, 6, 9, 30);
    BgPaySlipRegimeAudit.sync(copy, operator, at);

    BgPaySlipRegimeChange amount = change(copy, "healthOnly.DEBIT.amount");
    assertEquals("„само здравно“ (за сметка на лицето) — сума", amount.getFieldTitle());
    assertEquals(amountWas, amount.getOldValue(), "старото — от оригинала");
    assertEquals("1,05", amount.getNewValue());
    assertEquals(operator, amount.getChangedBy(), "кой");
    assertEquals(at, amount.getChangedOn(), "кога");
    assertEquals(BgPaySlipRegimeAudit.MANUAL, amount.getSource());
    BgPaySlipRegimeChange base = change(copy, "healthOnly.DEBIT.base");
    assertEquals(baseWas, base.getOldValue());
    assertEquals("13,11", base.getNewValue());
    assertTrue(
        copy.getBgRegimeChangeSummary()
            .contains(
                "„Само здравно“ е поправено на ръка: „само здравно“ (за сметка на лицето) — сума "
                    + amountWas
                    + " → 1,05"),
        copy.getBgRegimeChangeSummary());

    int rows = copy.getBgRegimeChangeList().size();
    BgPaySlipRegimeAudit.sync(copy, operator, at.plusMinutes(5));
    assertEquals(rows, copy.getBgRegimeChangeList().size(), "без нова поправка — без нов ред");
  }

  private static BgPaySlipRegimeChange change(BgPaySlip copy, String field) {
    for (BgPaySlipRegimeChange c : copy.getBgRegimeChangeList()) {
      if (field.equals(c.getFieldName())) {
        return c;
      }
    }
    throw new AssertionError("няма ред в одита за " + field + ": " + copy.getBgRegimeChangeList());
  }

  // ═══ 8 · ОТПЕЧАТЪКЪТ (а) И ЗА КОПИЕТО ══════════════════════════════════════════════

  @Test
  @DisplayName("(а) и за копието: непресметнато коригиращо копие не се потвърждава")
  void anUncalculatedCorrectionIsNotConfirmed() throws AxelorException {
    Harness s = new Harness();
    BgPaySlip original = draft(contract());
    confirmAndClose(s, original);
    BgPaySlip copy = s.correct(original);
    assertNull(copy.getBgCalcFingerprint(), "котвата: коригиращото копие се ражда без отпечатък");

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(copy));
    assertTrue(
        e.getMessage().startsWith("Коригиращото копие не е пресмятано"), "(а): " + e.getMessage());

    s.calculate(copy);
    s.openCurrent(original.getEmploymentContract());
    s.confirm(copy);
    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect(), "след „Преизчисли“ минава");
  }
}
