package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgContributionLine;
import com.axelor.apps.bglocalization.service.BgContributionLine.Payer;
import com.axelor.apps.bglocalization.service.BgContributionRequest;
import com.axelor.apps.bglocalization.service.BgContributionResolver;
import com.axelor.apps.bglocalization.service.BgContributionResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeService;
import com.axelor.apps.bglocalization.service.BgMinIncomeResult;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgWorkTimeFacts;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ЗЛАТНИЯТ ФИШ — РЕАЛЕН ФИШ ОТ МИКРОИНВЕСТ ЗА 07.2026 г., ВЪЗПРОИЗВЕДЕН ЧИСЛО ПО ЧИСЛО.
 *
 * <p><b>Случаят.</b> Юли 2026 г. Основна заплата 1805,00; 23 работни дни в месеца; лицето е болно
 * ЦЕЛИЯ месец — 2 работни дни за сметка на осигурителя по чл. 40 ал. 5 КСО и 21 дни за сметка на
 * ДОО; отработени нула.
 *
 * <p><b>Какво пази този тест.</b> Не една сума, а ЦЯЛАТА верига: болничен → осигурителен доход →
 * вноски → данъчна основа → нето, плюс здравната вноска за дните ВНР. Всяко число е ОТДЕЛЕН assert,
 * защото всяко от тях може да се сбърка самостоятелно, а сбор, който случайно излиза верен, крие
 * две компенсиращи се грешки.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ, НЕ ПРАВИЛАТА.</b>
 *
 * <ul>
 *   <li>{@link BgSickLeaveResolver} се вика ИСТИНСКИ — дните и сумите са негови.
 *   <li>{@link BgContributionResolver#resolve} се вика ИСТИНСКИ, върху редове, ПРОЧЕТЕНИ ОТ {@code
 *       bg_insurance_contribution.csv} — тоест ставките са реалните за 2026 г., а не измислени.
 *       Смени ли се seed-ът, числата тук се разминават и тестът пада.
 *   <li>{@link BgInsurableIncomeService#compute} се вика ИСТИНСКИ — подът, таванът и пропорцията са
 *       неговата аритметика.
 *   <li>{@link BgPaySlipResolver} се вика ИСТИНСКИ — брутото, осигурителният доход, данъчната
 *       основа и нетото са негови сборове.
 *   <li>Подменени са само зареждането на речниците и на параметрите.
 * </ul>
 *
 * <p><b>⚠️ ГРАНИЦАТА НА ТОЗИ ТЕСТ — ПРОЧЕТИ, ПРЕДИ ДА ТЪРСИЩ ТУК ЦЯЛАТА ВЕРИГА.</b> Осигурителните
 * пера се сглобяват в редове ОТ САМИЯ ТЕСТ ({@link #withContributionLines}), а не от генератор.
 * Тоест 0,00 данък и 94,72 нето са проверени срещу ИСТИНСКИТЕ формули на {@code BgPaySlipResolver},
 * но пътят „вноска → перо“ тук НЕ се минава.
 *
 * <p>⚠️ Той вече СЪЩЕСТВУВА: {@code BgContributionLineService} и {@code BgIncomeTaxService} го
 * затварят, а {@code BgPaySlipLinesGoldenTest} проверява СЪЩИЯ фиш през едно извикване на {@code
 * prepare()}, БЕЗ нито едно перо, сложено от теста. Двата теста НЕ се дублират и нито един не бива
 * да се маха: този пази АРИТМЕТИКАТА на фиша срещу подадени редове (тоест пада, ако {@code taxBase}
 * или {@code netTotal} се промени), а другият пази ВЕРИГАТА и РЕДА на генераторите (тоест пада, ако
 * осигуровките минат преди начисленията). Ако някога числата на двата се разминат, грешката е в
 * генератор, не в резолвер.
 */
class BgSickLeaveGoldenTest {

  // ═══ ФИШЪТ, КАКТО Е НА ХАРТИЯ ═════════════════════════════════════════════

  private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
  private static final LocalDate TO = LocalDate.of(2026, 7, 31);

  /** Юли 2026: 1 юли е сряда, 23 работни дни, нула официални празници. */
  private static final int WORKING_DAYS = 23;

  /**
   * Дните по чл. 40 ал. 5 КСО на ТОЗИ фиш — за сметка на осигурителя. Единица: РАБОТНИ ДНИ.
   *
   * <p>Заковано и като поле на фиша ({@code bgSickDaysEmployer}), и като числител на пропорцията:
   * двата читателя на едно число трябва да четат ЕДНО число и в теста.
   */
  private static final int EMPLOYER_SICK_DAYS = 2;

  private static final BigDecimal SALARY = new BigDecimal("1805.00");

  /** ЗБДОО чл. 9 т. 1 — максималният осигурителен доход до 31.07.2026 г. */
  private static final BigDecimal CAP = new BigDecimal("2111.6400");

  /** {@code HEALTH_ABSENCE_BASE} за 01.01–31.07.2026 г. */
  private static final BigDecimal HEALTH_ABSENCE_BASE = new BigDecimal("550.6600");

  /**
   * МОД за длъжността. ⚠️ Числото е ПРЕДСТАВИТЕЛНО, не снето от фиша: на него не е отпечатан МОД.
   * Ролята му в теста е една — да е ПО-ВИСОК от болничния, за да се види, че осигурителният доход
   * НЕ се вдига до него. Виж {@link #theInsurableIncomeIsNotLiftedToTheMinimum}.
   */
  private static final BigDecimal MOD = new BigDecimal("1077.00");

  // ═══ СТАВКИТЕ, ПРОЧЕТЕНИ ОТ SEED-А ═══════════════════════════════════════

  /**
   * Редовете за вид осигурен 01, трета категория, кохорта {@code WITH_UPF} — ОТ ФАЙЛА.
   *
   * <p>⚠️ Филтърът е ДОСЛОВНО този на {@code BgInsuranceCalculationService}: позволителният
   * заместител {@code ANY} по трите оси и точно съвпадение по основанието. Преписан е тук нарочно —
   * така ключът, по който се избират редовете, е ВИДИМ, вместо да се крие зад JPQL, който тест без
   * база не може да пусне.
   */
  private static List<BgInsuranceContribution> seedRows() {
    List<String> h = BgSeed.quotedHeader("bg_insurance_contribution.csv");
    List<BgInsuranceContribution> out = new ArrayList<>();
    for (String[] r : BgSeed.quotedRows("bg_insurance_contribution.csv")) {
      if (!"01".equals(BgSeed.col(h, r, "bgInsuredTypeCode"))
          || !"3".equals(BgSeed.col(h, r, "laborCategory"))) {
        continue;
      }
      String upf = BgSeed.col(h, r, "bgUpfRegime");
      if (!"ANY".equals(upf) && !"WITH_UPF".equals(upf)) {
        continue;
      }
      if (!"STANDARD".equals(BgSeed.col(h, r, "bgCompensationTypeCode"))) {
        continue;
      }
      BgInsuranceContribution c = new BgInsuranceContribution();
      c.setImportId(BgSeed.col(h, r, "importId"));
      c.setFundCode(BgSeed.col(h, r, "fundCode"));
      c.setEmployerRate(new BigDecimal(BgSeed.col(h, r, "employerRate")));
      c.setEmployeeRate(new BigDecimal(BgSeed.col(h, r, "employeeRate")));
      c.setBgEmployerRateKind(BgSeed.col(h, r, "bgEmployerRateKind"));
      c.setBgEmployeeRateKind(BgSeed.col(h, r, "bgEmployeeRateKind"));
      out.add(c);
    }
    return out;
  }

  /** Ставката на фонд ЗОВ за платеца РАБОТОДАТЕЛ — ОТ ФАЙЛА, не число в теста. */
  private static BigDecimal healthEmployerRateFromSeed() {
    for (BgInsuranceContribution row : seedRows()) {
      if ("HEALTH".equals(row.getFundCode())) {
        return row.getEmployerRate();
      }
    }
    throw new IllegalStateException("няма ред за фонд HEALTH при вид 01, трета категория");
  }

  // ═══ ЕНДЖИНЪТ С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  private static final class Service extends BgSickLeaveServiceOnDeclaredStage {

    /**
     * Базата на 195 (чл. 40 ал. 5 КСО) — постоянното месечно възнаграждение. ⚠️ Фикстурата няма
     * клас, степен и постоянни пера, тоест то Е основната заплата; самото {@code M} се мери в
     * {@code BgPaySlipRetroGeneratorsTest#theSickPayBaseCarriesThePermanentAdditions}.
     */
    @Override
    protected java.math.BigDecimal monthlyPermanentPay(
        com.axelor.apps.bglocalization.db.BgPaySlip slip,
        com.axelor.apps.bglocalization.service.payroll.BgPayrollInput input) {
      return slip.getBgBaseSalary();
    }

    @Override
    protected BgAbsenceType absenceType(String code) {
      if (!"sick_er".equals(code)) {
        return null;
      }
      // Редът от bg_absence_type.csv: платец осигурителят, 70 на сто.
      BgAbsenceType t = new BgAbsenceType();
      t.setCode("sick_er");
      t.setPayer("employer");
      t.setCompensationPct(new BigDecimal("70"));
      t.setRequiresSickNote(true); // вид на болничен лист, като в сийда
      t.setCountsAsInsuranceService(true);
      return t;
    }

    @Override
    protected Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
      return Collections.emptySet();
    }

    /** Базата, ставката и сумата, с които шевът е извикан — {@code null}, ако не е. */
    BigDecimal writtenBase;

    BigDecimal writtenRate;
    BigDecimal writtenAmount;

    /**
     * ⚠️ ШЕВЪТ СЕ ЗАГЛУШАВА И СЕ ЗАПИСВА, а не просто се заглушава.
     *
     * <p>Заглушен само, той би направил свидетеля „вноската се смята“ зелен и за код, който НЕ я
     * записва — тоест точно границата, която този етап затваря, щеше да остане невидима. Записан,
     * той дава на теста как да провери, че перото се пише И с кои три числа.
     */
    @Override
    protected void writeSpecialHealthBase(
        com.axelor.apps.bglocalization.db.BgPaySlip slip,
        BigDecimal base,
        BigDecimal ratePercent,
        BigDecimal amount) {
      this.writtenBase = base;
      this.writtenRate = ratePercent;
      this.writtenAmount = amount;
    }

    @Override
    protected BigDecimal healthAbsenceBase(LocalDate date) {
      return HEALTH_ABSENCE_BASE;
    }

    @Override
    protected BigDecimal healthEmployerRatePct(BgPayrollInput input, LocalDate date) {
      return healthEmployerRateFromSeed();
    }

    @Override
    protected BgPaySlipLineType lineType(String code) {
      BgPaySlipLineType t = new BgPaySlipLineType();
      t.setCode(BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY);
      t.setNumber(195);
      t.setName("Болничен от осигурител (чл. 40 ал. 5 КСО)");
      t.setBgSign("CREDIT");
      t.setBgIsTaxable(false);
      t.setBgIsInsuranceIncome(true);
      t.setBgIsPermanentCharacter(false);
      return t;
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgLineNumber(type.getNumber());
      line.setName(type.getName());
      line.setBgSign(type.getBgSign());
      line.setBgIsTaxable(type.getBgIsTaxable());
      line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
      line.setBgIsPermanentCharacter(type.getBgIsPermanentCharacter());
      line.setBgAmount(amount);
      return line;
    }
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  private static Period july() {
    Period p = new Period();
    p.setName("07/PY2026");
    p.setFromDate(FROM);
    p.setToDate(TO);
    return p;
  }

  /** Фишът, замразен от {@code prepare()}: заплата, норма, отработени = цял месец. */
  private static BgPaySlip slip() {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setPeriod(july());
    slip.setBgBaseSalary(SALARY);
    slip.setBgWorkingDaysInPeriod(WORKING_DAYS);
    slip.setBgWorkedDays(WORKING_DAYS);
    slip.setBgPaySlipLineList(new ArrayList<>());
    return slip;
  }

  /** Един болничен лист за целия юли, общо заболяване, не е продължение. */
  private static BgPayrollInput input() {
    return BgPayrollInput.builder()
        .insuredTypeCode("01")
        .laborCategory("3")
        .bornAfter1959(true)
        .birthDate(LocalDate.of(1985, 4, 12))
        .insuranceServiceMonths(120)
        .periodFrom(FROM)
        .periodTo(TO)
        .parameterReferenceDate(FROM)
        .workingDaysInPeriod(WORKING_DAYS)
        // ⚠️ НУЛА ОТРАБОТЕНИ: лицето е болно целия месец. Числото влиза в пропорцията по НЕВД чл. 1
        // ал. 3 и е причината прагът МОД да се свие — виж свидетеля за осигурителния доход.
        .workedDays(0)
        .baseSalary(SALARY)
        .sickLeaveEpisode(
            new BgPayrollInput.SickLeaveEpisode(FROM, TO, false, "БЛ-2026-07", "sick_er"))
        .build();
  }

  /** Фишът, минал през енджина за болнични — точно както {@code prepare()} го прекарва. */
  private static BgPaySlip prepared() {
    Service s = new Service();
    BgPaySlip slip = slip();
    s.generate(slip, input());
    return slip;
  }

  private static BgPaySlipLine lineOf(BgPaySlip slip, int number) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgLineNumber() == number) {
        return line;
      }
    }
    return null;
  }

  /** Осигурителната база: истинската аритметика, само подът и таванът са подадени. */
  private static BgInsurableIncomeResult insurableIncome(BgPaySlip slip) {
    return BgInsurableIncomeService.compute(
        BgPaySlipResolver.insuranceIncomeBase(slip.getBgPaySlipLineList()),
        BgMinIncomeResult.resolved(MOD, "МОД-представителен", 1),
        null,
        true,
        CAP,
        FROM,
        // НЕВД чл. 1 ал. 3: делител работните дни в месеца, числител отработените И ДНИТЕ ПО ЧЛ. 40
        // АЛ. 5 КСО.
        // ⚠️ ДНИТЕ СЕ ПОДАВАТ — BgPaySlipService.contributions(...) вика двуарговия
        // BgPayrollWorkTime.decide(input, slip.getBgSickDaysEmployer()). Тук се подава СЪЩОТО
        // число, което производственият път подава, за да мери този златен фиш живото поведение.
        BgWorkTimeFacts.byDays(
            BigDecimal.valueOf(WORKING_DAYS),
            BigDecimal.ZERO,
            BigDecimal.valueOf(EMPLOYER_SICK_DAYS)));
  }

  /** Вноските: истинският резолвер върху истинските ставки от seed-а. */
  private static BgContributionResult contributions(BigDecimal base) {
    return BgContributionResolver.resolve(
        seedRows(),
        new BgContributionRequest(
            base,
            "01",
            "3",
            true,
            null,
            null,
            FROM,
            // ТЗПБ по раздела на икономическата дейност — 0,5 на сто за тази дейност.
            new BigDecimal("0.5000")));
  }

  private static Map<String, BigDecimal> byFundAndPayer(BgContributionResult r) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (BgContributionLine l : r.getLines()) {
      out.put(
          l.getFundCode() + "/" + (l.getPayer() == Payer.EMPLOYER ? "РАБ" : "ЛИЦЕ"), l.getAmount());
    }
    return out;
  }

  /**
   * Фишът с ДОБАВЕНИ осигурителни пера — сглобени от теста, НЕ от генератора.
   *
   * <p>⚠️ Група 5xx, знак DEBIT за лицето и CREDIT за работодателя. Именно тези два знака решават
   * данъчната основа и нетото: {@code BgPaySlipResolver} вади от основата САМО удръжките от 5xx, а
   * перата на работодателя не влизат нито в нея, нито в нетото.
   *
   * <p>⚠️ Помощникът СТОИ нарочно, макар генераторът вече да съществува: тук се проверява
   * аритметиката на фиша срещу ИЗВЕСТНИ редове, тоест входът трябва да е под контрол на теста.
   * Веригата „вноска → перо“ се проверява в {@code BgPaySlipLinesGoldenTest}, където нито едно перо
   * не идва от теста. Кодовете на типовете тук са СГЛОБЕНИ ({@code pl_vn_<фонд>_<платец>}) и НЕ
   * съвпадат с речника — това е нарочно: така помощникът не може да мине за генератора.
   */
  private static BgPaySlip withContributionLines(BgPaySlip slip, BgContributionResult r) {
    int number = 500;
    for (BgContributionLine l : r.getLines()) {
      BgPaySlipLineType type = new BgPaySlipLineType();
      type.setCode("pl_vn_" + l.getFundCode().toLowerCase() + "_" + l.getPayer());
      type.setNumber(number++);
      type.setName("Осигурителна вноска — " + l.getFundCode());
      type.setBgSign(l.getPayer() == Payer.EMPLOYER ? "CREDIT" : "DEBIT");
      type.setBgIsTaxable(false);
      type.setBgIsInsuranceIncome(false);

      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgLineNumber(type.getNumber());
      line.setName(type.getName());
      line.setBgSign(type.getBgSign());
      line.setBgIsTaxable(false);
      line.setBgIsInsuranceIncome(false);
      line.setBgAmount(l.getAmount());
      line.setPaySlip(slip);
      slip.getBgPaySlipLineList().add(line);
    }
    return slip;
  }

  // ═══ 1 · ДНИТЕ И ПЕРОТО ═══════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · дните са 2 от работодател + 21 от ДОО, отработените стават 0")
  void theDaysMatchThePaySlip() {
    BgPaySlip slip = prepared();

    assertEquals(
        EMPLOYER_SICK_DAYS,
        slip.getBgSickDaysEmployer().intValue(),
        "чл. 40 ал. 5 КСО — 1 и 2 юли");
    assertEquals(21, slip.getBgSickDaysDoo().intValue(), "останалите 21 работни дни на месеца");
    assertEquals(
        0,
        slip.getBgWorkedDays().intValue(),
        "⚠️ Болен целия месец значи НУЛА отработени дни. Оттам основното възнаграждение и класът"
            + " прослужено време са 0,00 — без това намаляване лицето получава и пълна заплата, и"
            + " болничен за същите дни.");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · перото „Болничен от осигурител“ = 1805,00 / 23 × 70% × 2 = 109,87")
  void theSickPayIsOneHundredAndNineEightySeven() {
    BgPaySlipLine line = lineOf(prepared(), 195);

    assertNotNull(line, "перото трябва да е на фиша");
    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(line.getBgAmount()),
        "сверено срещу реален фиш на Микроинвест за 07.2026 г. Намерено: " + line.getBgAmount());
    assertTrue(line.getBgIsInsuranceIncome(), "осигурителен доход по чл. 40 ал. 5 КСО");
    assertTrue(!line.getBgIsTaxable(), "необлагаем по ЗДДФЛ чл. 24 ал. 2 т. 14");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · брутото на фиша е САМО болничният — заплата за 0 отработени дни няма")
  void theGrossIsOnlyTheSickPay() {
    BgPaySlip slip = prepared();

    assertEquals(
        0,
        new BigDecimal("109.87")
            .compareTo(BgPaySlipResolver.grossTotal(slip.getBgPaySlipLineList())),
        "перото е в група 1xx и точно затова влиза в брутото и в разхода за работодателя. Ред от 3xx"
            + " щеше да падне извън двете.");
  }

  // ═══ 2 · ОСИГУРИТЕЛНИЯТ ДОХОД ═════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · осигурителният доход е 109,87 и НЕ СЕ ВДИГА до МОД")
  void theInsurableIncomeIsNotLiftedToTheMinimum() {
    BgPaySlip slip = prepared();
    BgInsurableIncomeResult income = insurableIncome(slip);

    assertTrue(income.isResolved(), String.valueOf(income.getDetail()));
    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(income.getAmount()),
        "⚠️ ТОВА Е НАЙ-ЛЕСНАТА ГРЕШКА НА ЦЕЛИЯ ФИШ. МОД за длъжността е "
            + MOD.toPlainString()
            + ", тоест почти десет пъти болничния. Приложен ЦЯЛ, той щеше да вдигне осигурителния"
            + " доход до "
            + MOD.toPlainString()
            + " и вноските с него — за месец, в който лицето не е работило нито ден. Пази го"
            + " пропорцията по НЕВД чл. 1 ал. 3: прагът се свива с отработеното време. Намерено: "
            + income.getAmount());

    // Котвата: БЕЗ пропорция същият вход дава точно грешното число.
    BgInsurableIncomeResult unprorated =
        BgInsurableIncomeService.compute(
            BgPaySlipResolver.insuranceIncomeBase(slip.getBgPaySlipLineList()),
            BgMinIncomeResult.resolved(MOD, "МОД-представителен", 1),
            null,
            true,
            CAP,
            FROM,
            BgWorkTimeFacts.fullMonth());
    assertEquals(
        0,
        MOD.compareTo(unprorated.getAmount()),
        "котвата на свидетеля: без пропорция базата става целият МОД. Ако това някога съвпадне с"
            + " 109,87, свидетелят спира да пази каквото и да е.");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · числителят по чл. 40 ал. 5 НЕ мени ТОЗИ фиш — и затова той не го лови")
  void theArtFortyParFiveDaysInTheNumeratorDoNotChangeThisPaySlip() {
    BgPaySlip slip = prepared();

    // БЕЗ дните по чл. 40 ал. 5 в числителя.
    BgInsurableIncomeResult without =
        BgInsurableIncomeService.compute(
            BgPaySlipResolver.insuranceIncomeBase(slip.getBgPaySlipLineList()),
            BgMinIncomeResult.resolved(MOD, "МОД-представителен", 1),
            null,
            true,
            CAP,
            FROM,
            BgWorkTimeFacts.byDays(BigDecimal.valueOf(WORKING_DAYS), BigDecimal.ZERO, null));

    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(without.getAmount()),
        "котвата: сметката без числителя дава 109,87 на този фиш");
    assertEquals(
        0,
        without.getAmount().compareTo(insurableIncome(slip).getAmount()),
        "⚠️ ЗАЩО ТОЗИ ЗЛАТЕН ФИШ НЕ ЛОВИ ЗАНИЖАВАНЕТО, И ТОВА СЕ ИЗПИСВА, А НЕ СЕ"
            + " ПРЕМЪЛЧАВА. Дните по чл. 40 ал. 5 КСО ВЛИЗАТ в числителя, но на ТОЗИ фиш двете"
            + " сметки съвпадат: прагът пораства от 0,00 до "
            + MOD.multiply(BigDecimal.valueOf(EMPLOYER_SICK_DAYS))
                .divide(BigDecimal.valueOf(WORKING_DAYS), 2, java.math.RoundingMode.HALF_UP)
                .toPlainString()
            + ", което е ПОД болничния доход 109,87 — а базата е max(доход, праг). Тоест фиш, болен"
            + " ЦЕЛИЯ месец, е точно случаят, в който занижаването не се вижда: числителят движи"
            + " ПРАГА, а прагът стига до базата само когато е НАД дохода. Свидетелят с числа е"
            + " BgArt40Par5NumeratorTest — частично отработен месец.");
  }

  // ═══ 3 · ВНОСКИТЕ ═════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · вноски на СЛУЖИТЕЛЯ = 15,15, фонд по фонд")
  void theEmployeeContributionsAreFifteenFifteen() {
    BgContributionResult r = contributions(new BigDecimal("109.87"));
    assertEquals(
        BgContributionResult.Status.RESOLVED, r.getStatus(), String.valueOf(r.getDetail()));
    Map<String, BigDecimal> lines = byFundAndPayer(r);

    assertEquals(0, new BigDecimal("7.23").compareTo(lines.get("PENSION/ЛИЦЕ")), "Пенсии 6,58%");
    assertEquals(
        0, new BigDecimal("0.44").compareTo(lines.get("UNEMPLOYMENT/ЛИЦЕ")), "Безработица 0,4%");
    assertEquals(
        0, new BigDecimal("1.54").compareTo(lines.get("OZM/ЛИЦЕ")), "Общо заболяване 1,4%");
    assertEquals(0, new BigDecimal("2.42").compareTo(lines.get("UPF/ЛИЦЕ")), "ДЗПО 2,2%");
    assertEquals(0, new BigDecimal("3.52").compareTo(lines.get("HEALTH/ЛИЦЕ")), "ЗО 3,2%");

    assertEquals(
        0,
        new BigDecimal("15.15").compareTo(r.totalEmployee()),
        "⚠️ 15,15, А НЕ 15,14. Сборът на ставките е 13,78 на сто и 109,87 × 13,78% = 15,140086, което"
            + " закръглено на веднъж дава 15,14. Фишът показва 15,15, защото по чл. 2 ал. 4 НЕВД"
            + " ВСЯКА вноска се закръгля ПООТДЕЛНО. Тоест това число доказва правилото за"
            + " закръглянето, не само сбора. Намерено: "
            + r.totalEmployee());
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · вноски на РАБОТОДАТЕЛЯ върху 109,87 = 20,90")
  void theEmployerContributionsAreTwentyNinety() {
    Map<String, BigDecimal> lines = byFundAndPayer(contributions(new BigDecimal("109.87")));

    // ДОО = Пенсии + ОЗМ + Безработица, всяко закръглено поотделно: 9,03 + 2,31 + 0,66 = 12,00.
    assertEquals(0, new BigDecimal("9.03").compareTo(lines.get("PENSION/РАБ")), "Пенсии 8,22%");
    assertEquals(0, new BigDecimal("2.31").compareTo(lines.get("OZM/РАБ")), "Общо заболяване 2,1%");
    assertEquals(
        0, new BigDecimal("0.66").compareTo(lines.get("UNEMPLOYMENT/РАБ")), "Безработица 0,6%");
    assertEquals(
        0,
        new BigDecimal("12.00")
            .compareTo(
                lines
                    .get("PENSION/РАБ")
                    .add(lines.get("OZM/РАБ"))
                    .add(lines.get("UNEMPLOYMENT/РАБ"))),
        "ДОО на фиша е 12,00 — сборът на трите фонда, всеки закръглен поотделно");
    assertEquals(0, new BigDecimal("3.08").compareTo(lines.get("UPF/РАБ")), "ДЗПО 2,8%");
    assertEquals(0, new BigDecimal("0.55").compareTo(lines.get("TZPB/РАБ")), "ТЗПБ 0,5%");
    assertEquals(0, new BigDecimal("5.27").compareTo(lines.get("HEALTH/РАБ")), "ЗО 4,8%");

    assertEquals(
        0,
        new BigDecimal("20.90").compareTo(contributions(new BigDecimal("109.87")).totalEmployer()),
        "12,00 + 3,08 + 0,55 + 5,27 = 20,90");
  }

  @Test
  @DisplayName("⚠️ ГВРС НЯМА ред — и липсата му ТУК е вярна, не пропусната")
  void theGvrsFundProducesNoLine() {
    Map<String, BigDecimal> lines = byFundAndPayer(contributions(new BigDecimal("109.87")));
    assertTrue(
        !lines.containsKey("GVRS/РАБ") && !lines.containsKey("GVRS/ЛИЦЕ"),
        "и двете ставки на ГВРС са 0,0000 с вид LEGAL_ZERO (ЗБДОО чл. 15 ал. 1 спира вноската за"
            + " 2026 г.), а резолверът не строи ред за нулева ставка. Ред с нула тук би бил грешен.");
  }

  // ═══ 4 · ЗДРАВНАТА ВНОСКА ЗА ДНИТЕ ДОО ════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · здравна вноска за 21 дни ДОО = 550,66 × 21/23 × 4,8% = 24,13")
  void theHealthContributionForTheDooDaysIsTwentyFourThirteen() {
    Service s = new Service();
    BgPaySlip slip = slip();
    List<BgSickLeaveOutcome> outcomes = s.generate(slip, input());
    BgSickLeaveOutcome health = outcomes.get(1);

    assertEquals(BgSickLeaveOutcome.Status.COMPUTED, health.status, String.valueOf(health.detail));
    assertEquals(
        0,
        new BigDecimal("24.13").compareTo(health.amount),
        "ЗЗО чл. 40 ал. 1 т. 5 — САМО за сметка на работодателя. Намерено: " + health.amount);
    assertEquals(
        0,
        new BigDecimal("4.8000").compareTo(healthEmployerRateFromSeed()),
        "⚠️ Ставката НЕ е число в теста: прочетена е от bg_insurance_contribution.csv. Смени ли се"
            + " seed-ът, това число се разминава и тестът пада — точно каквото трябва.");
    assertEquals(
        0,
        new BigDecimal("21").compareTo(health.quantity),
        "количеството са дните БЕЗ осигурителен доход — двата дни на работодателя ГИ НОСЯТ и затова"
            + " не влизат тук");
  }

  // ═══ 5 · ДАНЪКЪТ И НЕТОТО ═════════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ ЗЛАТЕН · ДОД = 0,00 — болничният е необлагаем, а вноските свалят основата под нула")
  void theIncomeTaxIsZero() {
    BgContributionResult r = contributions(new BigDecimal("109.87"));
    BgPaySlip slip = withContributionLines(prepared(), r);

    BigDecimal taxBase = BgPaySlipResolver.taxBase(slip.getBgPaySlipLineList());
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(taxBase),
        "⚠️ ДВЕ ПРИЧИНИ, ЕДНА НУЛА, и те не се сливат. ПЪРВО: болничният е bgIsTaxable = false, тоест"
            + " облагаемите начисления са 0,00. ВТОРО: вноските на лицето (15,15) се ВАДЯТ от"
            + " основата, тоест тя е −15,15 и резолверът я ограничава до нула. Вдигнат погрешно"
            + " флагът, основата щеше да е 109,87 − 15,15 = 94,72 и данъкът 9,47. Намерено: "
            + taxBase);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(BgPaySlipResolver.taxAmount(taxBase, new BigDecimal("10.0000"))),
        "10 на сто от нула е нула — чл. 42 ЗДДФЛ");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · нето за получаване = 109,87 − 15,15 = 94,72")
  void theNetIsNinetyFourSeventyTwo() {
    BgContributionResult r = contributions(new BigDecimal("109.87"));
    BgPaySlip slip = withContributionLines(prepared(), r);

    assertEquals(
        0,
        new BigDecimal("94.72").compareTo(BgPaySlipResolver.netTotal(slip.getBgPaySlipLineList())),
        "⚠️ Вноските на РАБОТОДАТЕЛЯ (20,90) НЕ участват: те не са доход на лицето. Влезли в нетото,"
            + " числото щеше да е 115,62 — по-голямо от самия болничен, и то за месец без нито един"
            + " отработен ден.");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · разход за работодателя = 109,87 + 20,90 = 130,77")
  void theEmployerCostIsOneHundredThirtySeventySeven() {
    BgContributionResult r = contributions(new BigDecimal("109.87"));
    BgPaySlip slip = withContributionLines(prepared(), r);

    assertEquals(
        0,
        new BigDecimal("130.77")
            .compareTo(BgPaySlipResolver.employerCost(slip.getBgPaySlipLineList())),
        "⚠️ Здравната вноска за дните ДОО (24,13) НЕ е в това число, защото още не е перо — виж"
            + " BgSickLeaveService#healthAbsence. Пълният разход за работодателя по този фиш е"
            + " 130,77 + 24,13 = 154,90. ⚠️ И ТОВА ОСТАВА ОТВОРЕНО И СЛЕД КАТО 5xx получиха"
            + " генератор: двайсет и първи ред би скъсал огледалността на групата с речника на"
            + " фондовете, а вписването ѝ в 516 би слепило две вноски върху РАЗЛИЧНИ бази в едно"
            + " число. Същото е измерено и край-до-край в BgPaySlipLinesGoldenTest.");
  }
}
