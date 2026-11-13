package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import com.axelor.apps.bglocalization.service.selfinsured.BgSelfInsuredRules;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.apps.hr.db.Employee;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ САМООСИГУРЯВАЩОТО СЕ ЛИЦЕ СТИГА ДО ФИШ — числата, ред по ред.
 *
 * <p><b>Какво точно се проверява.</b> Че веригата регистрация → {@code BgPayrollInput} → {@code
 * BgContributionRequest} → вноски работи от край до край, и че произведените числа са тези на
 * НОРМАТА. Предпоставката е {@code BgPaySlip.employmentContract} да НЕ е {@code required="true"}:
 * инак лице без трудов договор не може дори да има фиш.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ, НЕ ПРАВИЛАТА</b> — дословно същият договор като в {@code
 * BgPaySlipContributionWiringTest}:
 *
 * <ul>
 *   <li>{@link BgContributionResolver#resolve} се вика ИСТИНСКИ, върху редовете на код 12,
 *       прочетени от {@code bg_insurance_contribution.csv}. Тоест ставките са СЕДМИТЕ реални за
 *       2026 г. — смени ли се seed-ът, числата тук се разминават и тестът пада.
 *   <li>{@link BgInsurableIncomeService#compute} се вика ИСТИНСКИ — подът, таванът и решението да
 *       НЕ се пропорционира са негова аритметика.
 *   <li>{@link BgPayrollInputBuilder#buildSelfInsured} се вика ИСТИНСКИ — включително
 *       разклоняването по носителя в {@code BgPaySlipService#payrollInput}.
 *   <li>Подменени са само {@code inputBuilder()}, {@code minIncomeFor}, {@code tzpbRate} и
 *       зареждането на редовете.
 * </ul>
 *
 * <p><b>Числата не са произволни.</b> Те се проверяват срещу НАП Приложение № 1
 * (ВК-30-10237/12.08.2026), разд. 10: за самоосигуряващо се лице, осигурено за инвалидност поради
 * общо заболяване, старост и смърт — 19,8 на сто за родените преди 1960 г. и 14,8 + 5 за УПФ за
 * родените след 1959 г.; при избор и за общо заболяване и майчинство — 23,3, съответно 18,3 + 5.
 * Плюс 8 на сто здравно във всички случаи. Тоест 14,8 + 3,5 + 5 = 23,3 ✓ и 19,8 + 3,5 = 23,3 ✓.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 */
class BgSelfInsuredContributionWiringTest {

  /** Първото полугодие на 2026 г. — ЗБДОО чл. 9 т. 1. */
  private static final LocalDate H1_FROM = LocalDate.of(2026, 3, 1);

  private static final LocalDate H1_TO = LocalDate.of(2026, 3, 31);

  /** Второто полугодие — ЗБДОО чл. 9 т. 2, в сила по § 5 ПЗР. */
  private static final LocalDate H2_FROM = LocalDate.of(2026, 8, 1);

  private static final LocalDate H2_TO = LocalDate.of(2026, 8, 31);

  /** ЗБДОО чл. 9 т. 1 б. „б“ — минималният за самоосигуряващите се до 31.07.2026 г. */
  private static final BigDecimal FLOOR_H1 = new BigDecimal("550.6600");

  /** ЗБДОО чл. 9 т. 2 б. „б“ — от 01.08.2026 г. */
  private static final BigDecimal FLOOR_H2 = new BigDecimal("620.2000");

  /** ЗБДОО чл. 9 т. 1 б. „в“ — максималният до 31.07.2026 г. */
  private static final BigDecimal CAP_H1 = new BigDecimal("2111.6400");

  /** ЗБДОО чл. 9 т. 2 б. „в“ — от 01.08.2026 г. */
  private static final BigDecimal CAP_H2 = new BigDecimal("2300.0000");

  private static final int WORKING_DAYS = 21;

  // ═══ РЕДОВЕТЕ ОТ SEED-А ═══════════════════════════════════════════════════

  /**
   * Редовете за един вид осигурен, кохорта и СОЛ обхват, ПРОЧЕТЕНИ ОТ SEED-А.
   *
   * <p>⚠️ Филтърът е ДОСЛОВНО този на {@code BgInsuranceCalculationService} — позволителният
   * заместител {@code ANY} по трите оси и точно съвпадение по основанието. Преписан е тук нарочно,
   * както и в {@code BgPaySlipContributionWiringTest}: така ключът, по който се избират редовете, е
   * ВИДИМ, вместо да се крие зад JPQL, който тест без база не може да пусне.
   */
  private static List<BgInsuranceContribution> seedRows(
      String insuredType, String cohort, String solScope) {
    List<String> h = BgSeed.quotedHeader("bg_insurance_contribution.csv");
    List<BgInsuranceContribution> out = new ArrayList<>();
    for (String[] r : BgSeed.quotedRows("bg_insurance_contribution.csv")) {
      if (!insuredType.equals(BgSeed.col(h, r, "bgInsuredTypeCode"))
          || !BgSelfInsuredRules.LABOR_CATEGORY.equals(BgSeed.col(h, r, "laborCategory"))) {
        continue;
      }
      String upf = BgSeed.col(h, r, "bgUpfRegime");
      String sol = BgSeed.col(h, r, "bgSolScope");
      String tea = BgSeed.col(h, r, "bgTeacherScope");
      if (!"ANY".equals(upf) && !upf.equals(cohort)) {
        continue;
      }
      if (!"ANY".equals(sol) && !sol.equals(solScope)) {
        continue;
      }
      if (!"ANY".equals(tea) && !tea.equals(BgContributionRequest.NON_TEACHER)) {
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

  // ═══ СГЛОБИТЕЛЯТ С ПОДМЕНЕН КАЛЕНДАР ═════════════════════════════════════

  /**
   * ⚠️ САМО КАЛЕНДАРЪТ Е ПОДМЕНЕН. {@code buildSelfInsured} не пипа нито отчет за време, нито
   * заявки за отпуск, нито обезщетения — виж javadoc-а му за четирите групи полета, които нарочно
   * остават празни. Тоест за него единственият шев към базата е производственият календар.
   */
  private static final class Builder extends BgPayrollInputBuilder {
    @Override
    protected BgWorkingDayService.Count workingDays(LocalDate from, LocalDate to) {
      return new BgWorkingDayService.Count(WORKING_DAYS, Collections.emptyList());
    }
  }

  // ═══ УСЛУГАТА С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  private static final class Service extends BgPaySlipService {
    private final Builder builder = new Builder();

    BigDecimal floor = FLOOR_H1;
    BigDecimal cap = CAP_H1;
    LocalDate referenceDate = H1_FROM;
    List<BgInsuranceContribution> rows = Collections.emptyList();

    BgContributionRequest seenRequest;
    BgInsurableIncomeResult seenIncome;
    BgPayrollInput seenInput;

    /**
     * ⚠️ ПОДМЕНЯ СЕ САМО {@code Beans.get}, А НЕ {@code payrollInput}. Точно това прави теста
     * свидетел на РАЗКЛОНЯВАНЕТО: производственият {@code payrollInput} тече истински, вижда
     * регистрацията на фиша и сам избира втората врата.
     */
    @Override
    protected BgPayrollInputBuilder inputBuilder() {
      return builder;
    }

    @Override
    protected BgPayrollInput payrollInput(BgPaySlip slip) {
      seenInput = super.payrollInput(slip);
      return seenInput;
    }

    /**
     * МОД не се прилага при самоосигуряващите се — {@code BgInsuredType.bgAppliesMod} е {@code
     * false} за кодове 12, 13, 22, 41 и 96 (измерено в seed-а). {@code NOT_APPLICABLE} НЕ е отказ.
     */
    @Override
    protected BgMinIncomeResult minIncomeFor(BgPayrollInput in, BgPaySlip slip) {
      return BgMinIncomeResult.notApplicable("вид осигурен: самоосигуряващо се лице");
    }

    /**
     * ⚠️ {@code false} — прагът НЕ се пропорционира. Огледало на {@code
     * BgInsuredType.bgZeroWorkTime}, което за 12, 13, 22, 41 и 96 е {@code true}: указанието към т.
     * 16 позиция 3 на Д1 изброява дословно тези кодове сред попълващите НУЛА работно време.
     * Месечният праг им се дължи ЦЯЛ.
     */
    @Override
    protected boolean proratesWithWorkTime(BgPayrollInput in, BgPaySlip slip) {
      return false;
    }

    // ⚠️ ИСТИНСКАТА аритметика — подменено е само зареждането на прага и тавана.
    @Override
    protected BgInsurableIncomeResult insurableIncome(
        BigDecimal gross, BgMinIncomeResult mod, BgPayrollInput in, BgWorkTimeFacts facts) {
      seenIncome =
          BgInsurableIncomeService.compute(gross, mod, floor, false, cap, referenceDate, facts);
      return seenIncome;
    }

    /** Самоосигуряващият се НЕ се осигурява за ТЗПБ — КСО чл. 4 ал. 3 не изброява този фонд. */
    @Override
    protected BigDecimal tzpbRate(BgPaySlip slip, LocalDate date) {
      return null;
    }

    // ⚠️ ИСТИНСКИЯТ резолвер — подменено е само зареждането на редовете.
    @Override
    protected BgContributionResult calculate(BgContributionRequest request) {
      seenRequest = request;
      return BgContributionResolver.resolve(rows, request);
    }
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  private static Period period(LocalDate from, LocalDate to) {
    Period p = new Period();
    p.setFromDate(from);
    p.setToDate(to);
    return p;
  }

  /**
   * Регистрация на самоосигуряващо се лице.
   *
   * @param birthYear годината на раждане решава кохортата по ДЗПО
   */
  private static BgSelfInsuredRegistration registration(
      int birthYear, String scope, String income) {
    return registration(birthYear, "12", scope, income);
  }

  /** Същото, но с ИЗРИЧЕН вид осигурен — за кодовете с фиксирана база. */
  private static BgSelfInsuredRegistration registration(
      int birthYear, String insuredTypeCode, String scope, String income) {
    Employee employee = new Employee();
    employee.setId(7L);
    employee.setBirthDate(LocalDate.of(birthYear, 4, 12));
    employee.setBgBornAfter1959(birthYear > 1959);
    employee.setBgIsPensioner(false);
    employee.setBgIsSelfInsured(true);
    // ⚠️ НАРОЧНО ПЪРВА КАТЕГОРИЯ. Ако сглобителят четеше категорията от ЛИЦЕТО, редовете на код 12
    // (всичките на категория 3) нямаше да се намерят и тестът щеше да падне на NO_CONTRIBUTIONS.
    // Тоест това поле е свидетелят, че BgSelfInsuredRules.LABOR_CATEGORY се подава ЯВНО.
    employee.setBgLaborCategory("1");

    BgSelfInsuredRegistration reg = new BgSelfInsuredRegistration();
    reg.setId(99L);
    reg.setEmployee(employee);
    reg.setBgIsSelfInsured(true);
    reg.setBgInsuredTypeCode(insuredTypeCode);
    reg.setBgActivityGround("ART4_AL3_T1");
    reg.setBgObligedPersonCode("1234567890");
    reg.setBgSolScope(scope);
    reg.setBgScopeYear(2026);
    reg.setBgSelectedIncome(new BigDecimal(income));
    return reg;
  }

  private static BgPaySlip slipFor(BgSelfInsuredRegistration reg, Period p) {
    BgPaySlip slip = new BgPaySlip();
    slip.setBgSelfInsuredRegistration(reg);
    slip.setPeriod(p);
    slip.setBgPaySlipLineList(new ArrayList<>());
    return slip;
  }

  /** Сумите по фонд, за да се четат твърденията по име, а не по индекс. */
  private static Map<String, BigDecimal> employeeByFund(BgContributionResult result) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (BgContributionLine line : result.getLines()) {
      out.merge(line.getFundCode(), line.getAmount(), BigDecimal::add);
    }
    return out;
  }

  // ═══ ТВЪРДЕНИЯТА ══════════════════════════════════════════════════════════

  /**
   * ⚠️ ЗЛАТНОТО ЧИСЛО: 313,00 €.
   *
   * <p>Самоосигуряващо се лице, родено 1985 г., избрало ВСИЧКИ РИСКОВЕ, върху избран доход 1000,00
   * € за март 2026 г.:
   *
   * <pre>
   *   фонд „Пенсии“   1000,00 × 14,80 % = 148,00
   *   ДЗПО-УПФ        1000,00 ×  5,00 % =  50,00
   *   фонд „ОЗМ“      1000,00 ×  3,50 % =  35,00
   *   здравно         1000,00 ×  8,00 % =  80,00
   *                                       ───────
   *                                        313,00
   * </pre>
   *
   * <p>Сверка срещу НАП Приложение № 1 разд. 10.2.2: „за ДОО и ДЗПО 23,3“ — а 14,80 + 3,50 + 5,00 =
   * 23,30 ✓. Здравното е отделната колона, 8 на сто.
   */
  @Test
  @DisplayName(
      "⚠️ ЗЛАТНО · код 12, всички рискове, роден 1985, доход 1000 € → 313,00 € ИЗЦЯЛО лице")
  void theAllRisksSelfInsuredPersonPaysThreeHundredThirteen() {
    Service service = new Service();
    service.rows = seedRows("12", "WITH_UPF", BgSelfInsuredRules.SCOPE_ALL_RISKS);
    assertEquals(4, service.rows.size(), "код 12 при WITH_UPF + ALL_RISKS дава точно четири реда");

    BgPaySlip slip =
        slipFor(
            registration(1985, BgSelfInsuredRules.SCOPE_ALL_RISKS, "1000.00"),
            period(H1_FROM, H1_TO));
    BgContributionResult result = service.contributions(slip);

    assertEquals(BgContributionResult.Status.RESOLVED, result.getStatus(), result.getDetail());
    assertEquals(0, new BigDecimal("1000.00").compareTo(service.seenIncome.getAmount()));

    Map<String, BigDecimal> byFund = employeeByFund(result);
    assertEquals(0, new BigDecimal("148.00").compareTo(byFund.get("PENSION")), byFund.toString());
    assertEquals(0, new BigDecimal("50.00").compareTo(byFund.get("UPF")), byFund.toString());
    assertEquals(0, new BigDecimal("35.00").compareTo(byFund.get("OZM")), byFund.toString());
    assertEquals(0, new BigDecimal("80.00").compareTo(byFund.get("HEALTH")), byFund.toString());

    assertEquals(0, new BigDecimal("313.00").compareTo(result.totalEmployee()), result.toString());

    // ⚠️ ИЗЦЯЛО ЗА СМЕТКА НА ЛИЦЕТО — КСО чл. 6 ал. 8 дословно: „за сметка на осигурените лица“.
    // Нула тук НЕ е защото няма редове: и седемте реда на код 12 носят employerRate = 0,0000 с вид
    // STRUCTURAL_ZERO, тоест самите данни твърдят „осигурител няма“.
    assertEquals(0, BigDecimal.ZERO.compareTo(result.totalEmployer()), result.toString());

    // ⚠️ БЕЗ ФОНД „БЕЗРАБОТИЦА“ И БЕЗ ТЗПБ — НИКОГА. КСО чл. 4 ал. 3 изброява само инвалидност
    // поради общо заболяване, старост и смърт; ал. 4 добавя ОЗМ по избор. Ако някой ден в seed-а
    // се появи такъв ред, това твърдение пада ПРЕДИ да е стигнало до декларация.
    assertTrue(!byFund.containsKey("UNEMPLOYMENT"), byFund.toString());
    assertTrue(!byFund.containsKey("TZPB"), byFund.toString());
    assertTrue(!byFund.containsKey("GVRS"), byFund.toString());
  }

  /**
   * ⚠️ РОДЕНИЯТ ПРЕДИ 1960 г. НЯМА УПФ, а пенсионната му ставка е ПО-ВИСОКА.
   *
   * <p>19,80 % вместо 14,80 % — разликата е точно вноската за универсален пенсионен фонд, която при
   * него отива изцяло във фонд „Пенсии“ на ДОО. НАП Приложение № 1 разд. 10.1.1: „за ДОО 19,8; за
   * ЗО-8“.
   */
  @Test
  @DisplayName("⚠️ ЗЛАТНО · код 12, само пенсия, роден 1955 → 19,8 % + 8 %, БЕЗ ОЗМ и БЕЗ УПФ")
  void thePensionOnlyPersonBornBeforeNineteenSixtyHasNoUpfAndNoOzm() {
    Service service = new Service();
    service.rows = seedRows("12", "WITHOUT_UPF", BgSelfInsuredRules.SCOPE_PENSION_ONLY);
    assertEquals(2, service.rows.size(), "WITHOUT_UPF + PENSION_ONLY дава точно два реда");

    BgPaySlip slip =
        slipFor(
            registration(1955, BgSelfInsuredRules.SCOPE_PENSION_ONLY, "1000.00"),
            period(H1_FROM, H1_TO));
    BgContributionResult result = service.contributions(slip);

    assertEquals(BgContributionResult.Status.RESOLVED, result.getStatus(), result.getDetail());
    Map<String, BigDecimal> byFund = employeeByFund(result);
    assertEquals(0, new BigDecimal("198.00").compareTo(byFund.get("PENSION")), byFund.toString());
    assertEquals(0, new BigDecimal("80.00").compareTo(byFund.get("HEALTH")), byFund.toString());
    assertTrue(!byFund.containsKey("UPF"), "роденият преди 1960 г. НЕ се осигурява в УПФ");
    // ⚠️ ОЗМ отпада заради ОБХВАТА, не заради кохортата: редът IC-080 носи bgSolScope = ALL_RISKS.
    assertTrue(
        !byFund.containsKey("OZM"), "неупражненият избор по чл. 4 ал. 4 КСО не се предполага");
    assertEquals(0, new BigDecimal("278.00").compareTo(result.totalEmployee()), result.toString());
    assertEquals(0, BigDecimal.ZERO.compareTo(result.totalEmployer()));
  }

  /**
   * ⚠️ ТАВАНЪТ Е НА ВТОРОТО ПОЛУГОДИЕ, а не един за годината.
   *
   * <p>ЗБДОО чл. 9 е построен на две точки — т. 1 за 01.01–31.07 и т. 2 за 01.08–31.12. Избран
   * доход 3000,00 € през август се капва на 2300,00 €, а не на 2111,64 €.
   */
  @Test
  @DisplayName("⚠️ ТАВАН · избран доход 3000 € през август 2026 → КАПНАТ на 2300,00 €")
  void theSelectedIncomeIsCappedAtTheSecondHalfCeiling() {
    Service service = new Service();
    service.rows = seedRows("12", "WITH_UPF", BgSelfInsuredRules.SCOPE_ALL_RISKS);
    service.floor = FLOOR_H2;
    service.cap = CAP_H2;
    service.referenceDate = H2_FROM;

    BgPaySlip slip =
        slipFor(
            registration(1985, BgSelfInsuredRules.SCOPE_ALL_RISKS, "3000.00"),
            period(H2_FROM, H2_TO));
    BgContributionResult result = service.contributions(slip);

    assertEquals(BgContributionResult.Status.RESOLVED, result.getStatus(), result.getDetail());
    assertEquals(
        0,
        new BigDecimal("2300.00").compareTo(service.seenIncome.getAmount()),
        "таванът за второто полугодие е 2300,00 €, не 2111,64 €");
    // 2300 × 23,30 % = 535,90 плюс 2300 × 8 % = 184,00
    assertEquals(0, new BigDecimal("719.90").compareTo(result.totalEmployee()), result.toString());
  }

  /**
   * ⚠️ ПОДЪТ СЕ НАЛАГА И ВЪРХУ ИЗБРАНОТО, и той също е полугодишен.
   *
   * <p>Чл. 6 ал. 8 КСО иска доход МЕЖДУ минималния и максималния — тоест избор под минимума не е
   * валиден избор и базата се вдига. Числото е 550,66 € за март и 620,20 € за август.
   */
  @Test
  @DisplayName("⚠️ ПОД · избран доход 300 € → ВДИГНАТ до 550,66 € (март) и до 620,20 € (август)")
  void aSelectedIncomeBelowTheFloorIsRaised() {
    Service march = new Service();
    march.rows = seedRows("12", "WITH_UPF", BgSelfInsuredRules.SCOPE_ALL_RISKS);
    march.contributions(
        slipFor(
            registration(1985, BgSelfInsuredRules.SCOPE_ALL_RISKS, "300.00"),
            period(H1_FROM, H1_TO)));
    assertEquals(0, new BigDecimal("550.66").compareTo(march.seenIncome.getAmount()));

    Service august = new Service();
    august.rows = seedRows("12", "WITH_UPF", BgSelfInsuredRules.SCOPE_ALL_RISKS);
    august.floor = FLOOR_H2;
    august.cap = CAP_H2;
    august.referenceDate = H2_FROM;
    august.contributions(
        slipFor(
            registration(1985, BgSelfInsuredRules.SCOPE_ALL_RISKS, "300.00"),
            period(H2_FROM, H2_TO)));
    assertEquals(0, new BigDecimal("620.20").compareTo(august.seenIncome.getAmount()));
  }

  /**
   * ⚠️ КЛЮЧЪТ, С КОЙТО РЕЗОЛВЕРЪТ Е ПИТАН — не само сумите.
   *
   * <p>Пет от седемте му полета решават КОИ редове се намират, и всяко от петте има начин да е
   * невярно тихо: чужда категория от лицето, обхват ANY вместо избора, учителски обхват ANY вместо
   * NON_TEACHER. Проверени само сумите, всяко от тях би останало скрито зад правдоподобно число.
   */
  @Test
  @DisplayName("⚠️ КЛЮЧ · заявката носи кода от РЕГИСТРАЦИЯТА, категория 3 и обхвата на лицето")
  void theResolverIsAskedWithTheRegistrationsKey() {
    Service service = new Service();
    service.rows = seedRows("12", "WITH_UPF", BgSelfInsuredRules.SCOPE_ALL_RISKS);
    service.contributions(
        slipFor(
            registration(1985, BgSelfInsuredRules.SCOPE_ALL_RISKS, "1000.00"),
            period(H1_FROM, H1_TO)));

    BgContributionRequest request = service.seenRequest;
    assertNotNull(request, "резолверът трябва да е бил питан");
    assertEquals("12", request.getInsuredTypeCode());
    assertEquals("3", request.getLaborCategory(), "категорията е ТРЕТА, въпреки че лицето носи 1");
    assertEquals(BgSelfInsuredRules.SCOPE_ALL_RISKS, request.getSolScope());
    assertEquals(BgContributionRequest.NON_TEACHER, request.getTeacherScope());
    assertEquals(H1_FROM, request.getReferenceDate());
    assertEquals(null, request.getTzpbRate(), "самоосигуряващият се не се осигурява за ТЗПБ");
  }

  /**
   * ⚠️ ВХОДЪТ Е СГЛОБЕН ОТ ВТОРАТА ВРАТА, И ТОВА СЕ ВИЖДА В САМИЯ НЕГО.
   *
   * <p>Двете полета на СОЛ са попълнени, а идентификаторът на договора е ПРАЗЕН — огледално на пътя
   * на наетия. Тоест разклоняването в {@code payrollInput} наистина е избрало другата врата, а не
   * просто е върнало нещо, което изглежда правилно.
   */
  @Test
  @DisplayName("⚠️ ВХОД · сглобен от регистрацията: договор НЯМА, избраният доход е пренесен")
  void theInputComesFromTheRegistrationAndNotFromAContract() {
    Service service = new Service();
    service.rows = seedRows("12", "WITH_UPF", BgSelfInsuredRules.SCOPE_ALL_RISKS);
    service.contributions(
        slipFor(
            registration(1985, BgSelfInsuredRules.SCOPE_ALL_RISKS, "1000.00"),
            period(H1_FROM, H1_TO)));

    BgPayrollInput input = service.seenInput;
    assertNotNull(input);
    assertEquals(null, input.employmentContractId, "самоосигуряващият се НЯМА трудов договор");
    assertEquals(Long.valueOf(99L), input.selfInsuredRegistrationId);
    assertEquals(Long.valueOf(7L), input.employeeId);
    assertEquals(0, new BigDecimal("1000.00").compareTo(input.selfInsuredSelectedIncome));
    assertEquals("12", input.insuredTypeCode);
    assertEquals("3", input.laborCategory);
    // Работните дни се четат и за него — т. 16 позиции 1 и 2 на Д1 ги искат.
    assertEquals(Integer.valueOf(WORKING_DAYS), input.workingDaysInPeriod);
    // ⚠️ И четирите групи, които втората врата НАРОЧНО не пълни — виж javadoc-а на
    // buildSelfInsured.
    assertEquals(null, input.nkpdCode);
    assertEquals(null, input.nkidCode);
    assertEquals(null, input.baseSalary);
    assertEquals(null, input.contractHoursPerDay);
  }

  /**
   * ⚠️ ФИШ С ДВА НОСИТЕЛЯ НЕ СМЯТА ВНОСКИ — и причината е назована ПРЕДИ сглобяването.
   *
   * <p>{@code required="true"} не би хванал този случай: той пита само „има ли договор“.
   *
   * <p>⚠️ И ТОЙ Е ЕДИНСТВЕНИЯТ, КОЙТО СЕ ПИТА ПРЕДИ ШЕВА. Случаят „нито един носител“ се вижда там,
   * където входът не може да се сглоби — инак широкият въпрос изпреварва {@code payrollInput(...)}
   * и отказва фиш на всеки повикващ, който подава собствен вход. Виж {@code
   * BgSelfInsuredRules#refuseAmbiguousCarrier}.
   */
  @Test
  @DisplayName("⚠️ НОСИТЕЛ · фиш и с договор, и с регистрация → INPUT_NOT_ASSEMBLED с причината")
  void aSlipWithBothCarriersRefusesBeforeAssembling() {
    Service service = new Service();
    BgPaySlip slip =
        slipFor(
            registration(1985, BgSelfInsuredRules.SCOPE_ALL_RISKS, "1000.00"),
            period(H1_FROM, H1_TO));
    slip.setEmploymentContract(new com.axelor.apps.hr.db.EmploymentContract());

    BgContributionResult result = service.contributions(slip);
    assertEquals(BgContributionResult.Status.INPUT_NOT_ASSEMBLED, result.getStatus());
    assertTrue(result.getDetail().contains("И трудов договор, И регистрация"), result.getDetail());
  }

  /**
   * ⚠️ ЗЛАТНОТО ЧИСЛО: 144,51 € за код 96.
   *
   * <p>Съпруг по чл. 4 ал. 9 КСО, роден 1985 г., август 2026 г. — <b>с избран доход 3000,00 €,
   * който НЕ участва в сметката</b>:
   *
   * <pre>
   *   фонд „Пенсии“   620,20 × 14,80 % =  91,79
   *   фонд „ОЗМ“      620,20 ×  3,50 % =  21,71
   *   ДЗПО-УПФ        620,20 ×  5,00 % =  31,01
   *                                       ───────
   *                                        144,51
   * </pre>
   *
   * <p>Сверка срещу НАП Приложение № 1 разд. 15.2: „за ДОО и ДЗПО 23,3“ — а 14,80 + 3,50 + 5,00 =
   * 23,30 ✓, целите при лицето.
   *
   * <p><b>⚠️ ТРИТЕ НЕЩА, КОИТО ТОЗИ СВИДЕТЕЛ ДЪРЖИ НАВЕДНЪЖ, и всяко от тях може да е невярно
   * тихо:</b>
   *
   * <ul>
   *   <li><b>Базата е ЗАКОНОВИЯТ ПРАГ, а не изборът.</b> Подаденият доход е 3000,00 € — почти пет
   *       пъти прага и над тавана. Прочетен, той би дал 699,00 € вноски вместо 144,51 и всяко от
   *       двете числа изглежда правдоподобно на фиш. НООСЛБГРЧМЛ чл. 17б ал. 1: вноските „се внасят
   *       ВЪРХУ МИНИМАЛНИЯ осигурителен доход за самоосигуряващите се лица“.
   *   <li><b>ОЗМ ГО ИМА.</b> Това е единствената разлика от 86, 87 и 91 — чл. 17б добавя „и за общо
   *       заболяване и майчинство“, докато чл. 16, 17 и 17а спират на трите риска на фонд „Пенсии“.
   *   <li><b>ЗДРАВЕН РЕД НЯМА.</b> Сумата 144,51 не съдържа 8 на сто; с тях щеше да е 194,13.
   *       Здравното на съпруга е по ЗЗО чл. 40 ал. 5 — САМ, с декларация обр. 7 — и изобщо не е в
   *       този поток.
   * </ul>
   */
  @Test
  @DisplayName(
      "⚠️ ЗЛАТНО · код 96, избран доход 3000 € → 144,51 € върху ПРАГА, с ОЗМ и БЕЗ здравно")
  void theSpouseUnderArt4Al9PaysOnTheFloorAndCarriesNoHealthRow() {
    Service service = new Service();
    service.rows = seedRows("96", "WITH_UPF", BgSelfInsuredRules.SCOPE_ALL_RISKS);
    assertEquals(
        3, service.rows.size(), "код 96 при WITH_UPF дава точно три реда — Пенсии, ОЗМ, УПФ");

    service.floor = FLOOR_H2;
    service.cap = CAP_H2;
    service.referenceDate = H2_FROM;
    BgContributionResult result =
        service.contributions(
            slipFor(
                registration(1985, "96", BgSelfInsuredRules.SCOPE_ALL_RISKS, "3000.00"),
                period(H2_FROM, H2_TO)));

    assertEquals(
        0,
        FLOOR_H2.compareTo(service.seenIncome.getAmount()),
        "базата е законовият праг, а НЕ избраните 3000,00 € — чл. 17б ал. 1 НООСЛБГРЧМЛ");

    Map<String, BigDecimal> byFund = employeeByFund(result);
    assertEquals(0, new BigDecimal("91.79").compareTo(byFund.get("PENSION")), "фонд Пенсии");
    assertEquals(0, new BigDecimal("21.71").compareTo(byFund.get("OZM")), "фонд ОЗМ");
    assertEquals(0, new BigDecimal("31.01").compareTo(byFund.get("UPF")), "ДЗПО-УПФ");
    assertNull(
        byFund.get("HEALTH"),
        "⚠️ ЗДРАВЕН РЕД НЕ СЕ ПОЛАГА: съпругът по чл. 4 ал. 9 се здравоосигурява САМ по ЗЗО чл. 40"
            + " ал. 5 с декларация обр. 7. Ред тук би удържал вноска, която лицето вече е внесло"
            + " само — и фишът щеше да изглежда пълен.");
    assertEquals(
        0,
        new BigDecimal("144.51")
            .compareTo(byFund.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add)),
        "сборът е 144,51 €, ИЗЦЯЛО за сметка на лицето");
  }
}
