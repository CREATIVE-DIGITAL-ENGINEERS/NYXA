package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgArt73Para6Report;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.payroll.BgArt177LeaveService;
import com.axelor.apps.bglocalization.service.payroll.BgArt49SettlementService;
import com.axelor.apps.bglocalization.service.payroll.BgCompensationLineService;
import com.axelor.apps.bglocalization.service.payroll.BgContributionLineService;
import com.axelor.apps.bglocalization.service.payroll.BgEarningsService;
import com.axelor.apps.bglocalization.service.payroll.BgIncomeTaxService;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipVerdicts;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveService;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveServiceOnDeclaredStage;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * БАЗОВАТА половина на координацията по КСО чл. 6 ал. 11: КРАЙ ДО КРАЙ, върху ДЕЙСТВИТЕЛНАТА база.
 *
 * <p><b>Разделението не е козметично.</b> {@code BgIncomeCoordinationTest} пита „вярна ли е
 * формулата“ и тече винаги, без Postgres. Този пита две други неща наведнъж: <b>стигнаха ли данните
 * до базата</b> (таван, данъчна ставка, осигурителни ставки, речник с типове пера) и <b>дава ли
 * ЦЯЛАТА верига над тях златните числа</b> — от {@code prepare()} до нетото. Затова иска база и се
 * пропуска с обявена причина, когато база няма.
 *
 * <p><b>⚠️ И ЕДИН НАЧИН ДА КАЖЕ „НЕ“, а не само „не знам“.</b> Празна таблица и таблица с ГРЕШНИ
 * редове НЕ са едно и също: първото е „App Install не е пускан“, второто е „импортирано е грешно“.
 * Затова предикатът на пропускането гледа дали каталозите СА ПРАЗНИ, а не дали ТЪРСЕНИТЕ редове
 * липсват — каталог с редове, но без нашите, е ПАДАНЕ. Същият идиом като {@code
 * BgSeafarerContributionManifestTest}.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ, НЕ ПРАВИЛАТА И НЕ ПОДРЕДБАТА.</b>
 *
 * <ul>
 *   <li>{@code BgPaySlipService#prepare} се вика ИСТИНСКИ — редът на петте генератора е негов;
 *   <li>{@link BgInsurableIncomeService#compute} се вика ИСТИНСКИ, с декларацията, сглобена през
 *       производствената врата {@link BgInsurableIncomeService#coordinationOf};
 *   <li>{@link BgContributionResolver#resolve} се вика ИСТИНСКИ, върху редове, ПРОЧЕТЕНИ ОТ БАЗАТА;
 *   <li>{@link BgPaySlipResolver} се вика ИСТИНСКИ — брутото, данъчната основа и нетото са негови;
 *   <li>речникът с типове пера, таванът, данъчната ставка и минимумът за клас — ОТ БАЗАТА.
 * </ul>
 *
 * <p><b>⚠️ ГРАНИЦАТА: JPA ЗАЯВКИТЕ СА ЗАМЕНЕНИ С JDBC, НЕ ПРЕМАХНАТИ.</b> Този тест не върви през
 * Guice и Hibernate — той чете ТЕЗИ ЖЕ таблици с прав SQL. Тоест доказва, че данните ги има и че
 * веригата над тях е вярна, но НЕ че JPQL-ът на услугите отговаря на схемата; това е работа на
 * {@code BgPayrollAssemblySchemaTest} и на {@code BgSchemaColumnWidthTest}.
 *
 * <p><b>⚠️ И ЕДНА ГРАНИЦА КЪМ ПРОИЗВОДСТВОТО.</b> Пътят „поле на фиша → сглобител → вход“ се пази
 * от {@code BgPayrollInputBuilderTest#theDeclaredOtherPayerIncomeIsForwarded}: тук входът се подава
 * готов, защото сглобителят иска JPA. Двете заедно затварят веригата от колоната до нетото.
 */
class BgOtherPayerCoordinationGoldenTest {

  // ═══ СЛУЧАИТЕ, КАКТО СА НА ХАРТИЯ ═════════════════════════════════════════

  private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
  private static final LocalDate TO = LocalDate.of(2026, 6, 30);

  /** Юни 2026: 1 юни е понеделник, нула официални празника, 22 работни дни. */
  private static final int WORKING_DAYS = 22;

  /** Лице А — собствен осигурителен доход 1252,00 срещу ИЗЧЕРПАН таван. */
  private static final BigDecimal PERSON_A_SALARY = new BigDecimal("1252.00");

  private static final BigDecimal PERSON_A_DECLARED = new BigDecimal("2111.64");

  /** Лице Б — собствен 398,36 срещу деклариран доход, който ОСТАВЯ място под тавана. */
  private static final BigDecimal PERSON_B_SALARY = new BigDecimal("398.36");

  /**
   * ⚠️ ПРЕДСТАВИТЕЛНО ЧИСЛО, НЕ СНЕТО ОТ ФИША. Точният чужд доход на лице Б е PII от друг
   * работодател и няма място в тест. Всеки деклариран, който оставя сбора под тавана, дава СЪЩИЯ
   * изход — 1500,00 е представителят, а свидетелят за това е {@link
   * #anyDeclarationLeavingRoomGivesTheSameBase}.
   */
  private static final BigDecimal PERSON_B_DECLARED = new BigDecimal("1500.00");

  /**
   * МОД за длъжността. ⚠️ ПРЕДСТАВИТЕЛНО число: на нито един от двата фиша не е отпечатан МОД.
   * Ролята му е една — да е ПО-ВИСОК от двата собствени дохода, за да се види, че координацията НЕ
   * го прилага. Без него МОД-пропускът щеше да е незабележим на тези числа.
   */
  private static final BigDecimal MOD = new BigDecimal("1077.00");

  /** ТЗПБ по раздела на икономическата дейност — подава се на заявката, както в производството. */
  private static final BigDecimal TZPB = new BigDecimal("0.5000");

  // ═══ БАЗАТА ═══════════════════════════════════════════════════════════════

  private static final String T_PARAM = "bglocalization_bg_payroll_parameter";
  private static final String T_RATES = "bglocalization_bg_insurance_contribution";
  private static final String T_TYPES = "bglocalization_bg_pay_slip_line_type";

  private static Connection open() {
    String url = BgTestDb.url();
    String user = BgTestDb.user();
    String pass = BgTestDb.password();
    try {
      Class.forName("org.postgresql.Driver");
      return DriverManager.getConnection(url, user, pass);
    } catch (Exception e) {
      BgSkip.because(
          "няма достъпна база ("
              + url
              + "): "
              + e.getMessage()
              + " — твърдението „координираният фиш дава златните числа върху ДЕЙСТВИТЕЛНИТЕ ставки“"
              + " НЕ е проверено. Файловата половина (BgIncomeCoordinationTest) тече и без база, но"
              + " тя мери срещу подадени граници, не срещу заредените.");
      return null;
    }
  }

  /** Броят редове в една таблица — предикатът „пускан ли е App Install“. */
  private static int count(Connection db, String table) throws Exception {
    try (Statement st = db.createStatement();
        ResultSet rs = st.executeQuery("select count(*) from " + table)) {
      return rs.next() ? rs.getInt(1) : 0;
    }
  }

  /**
   * Пропуска САМО когато и трите каталога са празни. Празно значи състояние на СРЕДАТА; всичко
   * останало, включително „има редове, но не тези“, минава нататък и ПАДА.
   */
  private static void requireImported(Connection db) throws Exception {
    if (count(db, T_PARAM) == 0 && count(db, T_RATES) == 0 && count(db, T_TYPES) == 0) {
      BgSkip.because(
          "каталозите на модула са ПРАЗНИ ("
              + T_PARAM
              + ", "
              + T_RATES
              + ", "
              + T_TYPES
              + " — нула реда), тоест App Install не е пускан на тази база. Заредете seed-а"
              + " (App Install) и пуснете отново. ⚠️ Каталог С редове, но без"
              + " търсените, НЕ се пропуска — той е импортиран, но грешен, и трябва да се вижда.");
    }
  }

  /** Стойността на един темпорален параметър за датата — ОТ БАЗАТА. */
  private static BigDecimal parameter(Connection db, String code, LocalDate date) throws Exception {
    try (PreparedStatement ps =
        db.prepareStatement(
            "select param_value from "
                + T_PARAM
                + " where code = ? and valid_from <= ?"
                + " and (valid_to is null or valid_to >= ?)")) {
      ps.setString(1, code);
      ps.setObject(2, date);
      ps.setObject(3, date);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(
            rs.next(),
            "⚠️ SEED-ПРОПУСК, НЕ ПРОПУСКАНЕ НА ТЕСТА: параметър „"
                + code
                + "“ няма ред, валиден за "
                + date
                + ", в "
                + T_PARAM
                + ". Каталогът НЕ е празен, тоест App Install е пускан — липсва самият ред.");
        BigDecimal value = rs.getBigDecimal(1);
        assertTrue(!rs.next(), "два валидни реда за „" + code + "“ на " + date + " — избор няма");
        return value;
      }
    }
  }

  /**
   * Осигурителните редове за вид осигурен 01, трета категория, кохорта {@code WITH_UPF} — ОТ
   * БАЗАТА.
   *
   * <p>⚠️ Филтърът е ДОСЛОВНО този на {@code BgInsuranceCalculationService}: позволителният
   * заместител {@code ANY} по осите и точно съвпадение по основанието. Преписан е нарочно — така
   * ключът, по който се избират редовете, е ВИДИМ.
   */
  private static List<BgInsuranceContribution> rates(Connection db, LocalDate date)
      throws Exception {
    List<BgInsuranceContribution> out = new ArrayList<>();
    try (PreparedStatement ps =
        db.prepareStatement(
            "select import_id, fund_code, employer_rate, employee_rate,"
                + " bg_employer_rate_kind, bg_employee_rate_kind from "
                + T_RATES
                + " where bg_insured_type_code = '01' and labor_category = '3'"
                + " and bg_upf_regime in ('ANY', 'WITH_UPF')"
                + " and bg_compensation_type_code = 'STANDARD'"
                + " and valid_from <= ? and (valid_to is null or valid_to >= ?)"
                + " order by fund_code")) {
      ps.setObject(1, date);
      ps.setObject(2, date);
      try (ResultSet rs = ps.executeQuery()) {
        while (rs.next()) {
          BgInsuranceContribution c = new BgInsuranceContribution();
          c.setImportId(rs.getString("import_id"));
          c.setFundCode(rs.getString("fund_code"));
          c.setEmployerRate(rs.getBigDecimal("employer_rate"));
          c.setEmployeeRate(rs.getBigDecimal("employee_rate"));
          c.setBgEmployerRateKind(rs.getString("bg_employer_rate_kind"));
          c.setBgEmployeeRateKind(rs.getString("bg_employee_rate_kind"));
          out.add(c);
        }
      }
    }
    assertTrue(
        !out.isEmpty(),
        "⚠️ SEED-ПРОПУСК: няма нито един осигурителен ред за вид 01 / трета категория, валиден за "
            + date
            + ". Каталогът не е празен — липсват точно тези редове.");
    return out;
  }

  /**
   * ЦЕЛИЯТ речник с типове пера — ОТ БАЗАТА.
   *
   * <p>⚠️ Целият, не само нужните: {@code BgEarningsService} обхожда ВСИЧКИ активни типове. Подаден
   * непълен речник, обходът щеше да мълчи точно за перата, които този тест проверява.
   *
   * <p>⚠️ Булевите се четат с {@code getBoolean}, а не като текст: JDBC дава на boolean стойността
   * „t“/„f“, не „true“/„false“, и сравнение с низ би обявило всеки флаг за вдигнат.
   */
  private static List<BgPaySlipLineType> dictionary(Connection db) throws Exception {
    List<BgPaySlipLineType> out = new ArrayList<>();
    try (Statement st = db.createStatement();
        ResultSet rs =
            st.executeQuery(
                "select t.code, t.line_number, t.name, t.bg_group, t.bg_sign, t.bg_is_taxable,"
                    + " t.bg_is_insurance_income, t.bg_is_permanent_character, t.bg_calculation_base,"
                    + " t.bg_legal_basis, t.sequence, t.active, f.code as fund_code from "
                    + T_TYPES
                    + " t left join bglocalization_bg_contribution_fund f"
                    + " on f.id = t.bg_contribution_fund order by t.line_number")) {
      while (rs.next()) {
        BgPaySlipLineType t = new BgPaySlipLineType();
        t.setCode(rs.getString("code"));
        t.setNumber(rs.getInt("line_number"));
        t.setName(rs.getString("name"));
        t.setBgGroup(rs.getString("bg_group"));
        t.setBgSign(rs.getString("bg_sign"));
        t.setBgIsTaxable(rs.getBoolean("bg_is_taxable"));
        t.setBgIsInsuranceIncome(rs.getBoolean("bg_is_insurance_income"));
        t.setBgIsPermanentCharacter(rs.getBoolean("bg_is_permanent_character"));
        t.setBgCalculationBase(rs.getString("bg_calculation_base"));
        t.setBgLegalBasis(rs.getString("bg_legal_basis"));
        t.setSequence(rs.getInt("sequence"));
        t.setActive(rs.getBoolean("active"));
        String fund = rs.getString("fund_code");
        if (fund != null) {
          BgContributionFund f = new BgContributionFund();
          f.setCode(fund);
          f.setName(fund);
          t.setBgContributionFund(f);
        }
        out.add(t);
      }
    }
    assertTrue(
        out.size() > 50,
        "⚠️ SEED-ПРОПУСК: речникът с типове пера е с "
            + out.size()
            + " реда, а модулът разчита на десетки. Непълен речник дава непълен фиш — тихо.");
    return out;
  }

  /** Всичко, което базата дава за един месец — на едно място, за да се чете какво е прочетено. */
  private static final class Seed {
    final BigDecimal cap;
    final BigDecimal taxRate;
    final BigDecimal seniorityMinPct;
    final BigDecimal healthAbsenceBase;
    final List<BgInsuranceContribution> rates;
    final List<BgPaySlipLineType> types;

    Seed(Connection db, LocalDate date) throws Exception {
      requireImported(db);
      this.cap = parameter(db, BgInsurableIncomeService.CODE_MAX_INSURABLE_INCOME, date);
      this.taxRate = parameter(db, "INCOME_TAX_RATE", date);
      this.seniorityMinPct = parameter(db, "SENIORITY_MIN_PCT", date);
      this.healthAbsenceBase = parameter(db, "HEALTH_ABSENCE_BASE", date);
      this.rates = rates(db, date);
      this.types = dictionary(db);
    }
  }

  private static Seed seedForJune() throws Exception {
    try (Connection db = open()) {
      return new Seed(db, FROM);
    }
  }

  // ═══ ВЕДОМОСТТА С ПОДМЕНЕНИ ЧЕТЦИ, НО ИСТИНСКА ПОДРЕДБА ══════════════════

  private static class Payroll extends BgPaySlipService {

    private final Seed seed;
    private final BgPayrollInput input;

    /** Базата, както я е видяла веригата — за свидетелите. */
    BgInsurableIncomeResult incomeSeen;

    private final BgEarningsService earnings;
    private final BgSickLeaveService sickLeave;
    private final BgCompensationLineService compensations;
    private final BgContributionLineService contributionLines;
    private final BgIncomeTaxService incomeTax;

    /** Истинската услуга по чл. 177 — виж {@link #generateArt177Leave} защо не е заглушена. */
    private final BgArt177LeaveService art177Leave = new BgArt177LeaveService();

    /**
     * Годишното изравняване по чл. 49 — БЕЗ справка в базата, както в {@code
     * BgPaySlipLinesGoldenTest}. {@code report()} връща {@code null} нарочно: фишовете тук са за
     * юни, тоест генераторът излиза, преди да поиска справка.
     */
    private final BgArt49SettlementService art49Settlement =
        new BgArt49SettlementService() {
          @Override
          protected BgArt73Para6Report report(Company company, int taxYear) {
            return null;
          }
        };

    Payroll(Seed seed, BgPayrollInput input) {
      this.seed = seed;
      this.input = input;

      final Payroll self = this;
      this.earnings =
          new BgEarningsService() {
            @Override
            protected BigDecimal statutoryMinPct(LocalDate date) {
              return seed.seniorityMinPct;
            }

            /**
             * ⚠️ ЧАСОВИТЕ СТАВКИ НЕ СЕ ЗАРЕЖДАТ, И ТОВА Е ОТГОВОР, А НЕ ПРОПУСК. Този свидетел не
             * гледа часови пера; без подмяна обаче четецът иска Postgres, защото {@code factsOf}
             * вади и седемте параметъра НАВЕДНЪЖ. Липсващият параметър е ОТКАЗ на съответното перо
             * — точно каквото трябва да получи фикстура, която не е обявила нито една часова
             * ставка.
             */
            @Override
            protected BigDecimal payrollParameter(String code, LocalDate date) {
              return null;
            }

            /**
             * Няма валидирани допълнителни часове → няма уговорено увеличение. Инак иска Hibernate.
             */
            @Override
            protected java.util.Map<String, BigDecimal> agreedOvertimeUplift(
                com.axelor.apps.bglocalization.db.BgPaySlip slip,
                com.axelor.apps.bglocalization.service.payroll.BgPayrollInput input) {
              return java.util.Collections.emptyMap();
            }

            @Override
            protected List<BgPaySlipLineType> lineTypes() {
              return self.activeTypes();
            }

            @Override
            protected BgPaySlipLineType lineType(String code) {
              return self.byCode(code);
            }

            @Override
            protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
              return self.lineFrom(type, amount);
            }

            // ⚠️ ЧЕТВЪРТИЯТ ЧЕТЕЦ, ПОДМЕНЕН ЗАРАДИ НОВАТА ОС: правото на клас (НСОРЗ чл. 12 ал. 1)
            // се вади от вида осигурен през BgPaySlipService, тоест иска Guice и Postgres. Фишовете
            // тук са на НАЕТИ по трудов договор — за тях отговорът е „да“, и подменен, той оставя
            // сметката на BgEarningsResolver, както досега.
            @Override
            protected Boolean seniorityEntitlement(BgPaySlip slip) {
              return Boolean.TRUE;
            }
          };
      this.sickLeave =
          new BgSickLeaveServiceOnDeclaredStage() {
            // ⚠️ И ДВАТА ЧЕТЦА СЕ ПОДМЕНЯТ, макар фишът да няма болничен: factsOf ги вика
            // БЕЗУСЛОВНО, тоест без подмяна тестът щеше да иска Guice за стойности, които после не
            // влизат в нито едно перо.
            @Override
            protected BigDecimal healthAbsenceBase(LocalDate date) {
              return seed.healthAbsenceBase;
            }

            @Override
            protected BigDecimal healthEmployerRatePct(BgPayrollInput in, LocalDate date) {
              for (BgInsuranceContribution row : seed.rates) {
                if ("HEALTH".equals(row.getFundCode())) {
                  return row.getEmployerRate();
                }
              }
              return null;
            }

            @Override
            protected java.util.Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
              return Collections.emptySet();
            }

            @Override
            protected com.axelor.apps.bglocalization.db.BgAbsenceType absenceType(String code) {
              return null;
            }

            @Override
            protected BgPaySlipLineType lineType(String code) {
              return self.byCode(code);
            }

            @Override
            protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
              return self.lineFrom(type, amount);
            }
          };
      this.compensations =
          new BgCompensationLineService() {
            @Override
            protected List<com.axelor.apps.bglocalization.db.BgCompensation> documents(
                BgPayrollInput in) {
              return Collections.emptyList();
            }
          };
      this.contributionLines =
          new BgContributionLineService() {
            @Override
            protected BgPaySlipLineType lineTypeFor(String fundCode, String sign) {
              for (BgPaySlipLineType t : self.activeTypes()) {
                if (t.getBgContributionFund() != null
                    && fundCode.equals(t.getBgContributionFund().getCode())
                    && sign.equals(t.getBgSign())) {
                  return t;
                }
              }
              return null;
            }

            @Override
            protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
              return self.lineFrom(type, amount);
            }
          };
      this.incomeTax =
          new BgIncomeTaxService() {
            @Override
            protected BgPaySlipLineType lineType(String code) {
              return self.byCode(code);
            }

            @Override
            protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
              return self.lineFrom(type, amount);
            }
          };
    }

    List<BgPaySlipLineType> activeTypes() {
      List<BgPaySlipLineType> out = new ArrayList<>();
      for (BgPaySlipLineType t : seed.types) {
        if (Boolean.TRUE.equals(t.getActive())) {
          out.add(t);
        }
      }
      return out;
    }

    BgPaySlipLineType byCode(String code) {
      for (BgPaySlipLineType t : seed.types) {
        if (code.equals(t.getCode())) {
          return t;
        }
      }
      return null;
    }

    // ── четците ──────────────────────────────────────────────────────────

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return new BgWorkingDayService.Count(WORKING_DAYS, Collections.emptyList());
    }

    @Override
    protected BgPayrollInput payrollInput(BgPaySlip slip) {
      return input;
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return seed.taxRate;
    }

    @Override
    protected BigDecimal disabilityRelief(BgPaySlip slip) {
      // Нито един от двата случая не носи облекчение по чл. 18 ал. 2 — четенето му иска Employee.
      return BigDecimal.ZERO;
    }

    /**
     * ЦЯЛАТА ВЕРИГА НА ОСИГУРОВКИТЕ, с истинска аритметика и с КООРДИНАЦИЯТА на мястото си.
     *
     * <p>⚠️ Декларацията минава през {@link BgInsurableIncomeService#coordinationOf} — същата
     * врата, която {@code BgPaySlipService#insurableIncome} ползва в производството, — а не се
     * тълкува тук.
     */
    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      BgInsurableIncomeResult income =
          BgInsurableIncomeService.compute(
              insuranceGross(slip),
              BgMinIncomeResult.resolved(MOD, "МОД-представителен", 1),
              null,
              true,
              seed.cap,
              FROM,
              BgWorkTimeFacts.byDays(
                  BigDecimal.valueOf(slip.getBgWorkingDaysInPeriod()),
                  BigDecimal.valueOf(slip.getBgWorkedDays()),
                  null),
              BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
              false,
              BgInsurableIncomeService.coordinationOf(input.otherEmployerInsurableIncome));
      incomeSeen = income;
      if (!income.isResolved()) {
        return BgContributionResult.failure(
            BgContributionResult.Status.INPUT_NOT_ASSEMBLED, String.valueOf(income.getDetail()));
      }
      return BgContributionResolver.resolve(
          seed.rates,
          new BgContributionRequest(
              income.getAmount(),
              input.insuredTypeCode,
              input.laborCategory,
              input.bornAfter1959,
              null,
              null,
              FROM,
              TZPB));
    }

    // ── седемте шева, навързани локално. ⚠️ Редът остава на prepare(). ──────

    @Override
    protected void generateEarnings(BgPaySlip slip) {
      earnings.generate(slip, payrollInput(slip));
    }

    @Override
    protected void generateSickLeave(BgPaySlip slip) {
      sickLeave.generate(slip, payrollInput(slip));
    }

    /**
     * Шевът по чл. 177, навързан с ИСТИНСКАТА услуга — както в {@code BgPaySlipLinesGoldenTest}.
     *
     * <p>⚠️ НАВЪРЗАН, А НЕ ЗАГЛУШЕН. Фишовете тук не носят отпуск по чл. 177, тоест услугата излиза
     * с {@code NOT_APPLICABLE} още преди да поиска база. Без подмяна базовият шев вика {@code
     * Beans.get} и всеки свидетел в класа пада с „Guice is not initialized“; CI не го вижда, защото
     * без база класът се пропуска.
     */
    @Override
    protected void generateArt177Leave(BgPaySlip slip) {
      new BgPaySlipVerdicts()
          .addArt177Leave(
              BgPaySlipVerdicts.STEP_ART177_LEAVE, art177Leave.generate(slip, payrollInput(slip)))
          .appendInto(slip);
    }

    @Override
    protected void generateCompensations(BgPaySlip slip) {
      compensations.generate(slip, payrollInput(slip));
    }

    @Override
    protected void generateContributions(BgPaySlip slip) {
      contributionLines.generate(slip, contributions(slip));
    }

    @Override
    protected void generateIncomeTax(BgPaySlip slip) {
      incomeTax.generate(slip, incomeTaxRate(slip), disabilityRelief(slip));
    }

    /**
     * Годишното изравняване по чл. 49, навързано с ИСТИНСКАТА услуга — по същия довод като шева по
     * чл. 177 горе. Стъпката е в {@code prepare()} и базовият шев вика {@code Beans.get}.
     */
    @Override
    protected void generateAnnualSettlement(BgPaySlip slip) {
      new BgPaySlipVerdicts()
          .addLines(BgPaySlipVerdicts.STEP_ART49_SETTLEMENT, art49Settlement.generate(slip))
          .appendInto(slip);
    }

    @Override
    protected void save(BgPaySlip slip) {}
  }

  // ═══ ОБЩИ ПОМОЩНИЦИ ══════════════════════════════════════════════════════

  private static BgPayrollInput inputFor(BigDecimal salary, BigDecimal declared) {
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
        .workedDays(WORKING_DAYS)
        .baseSalary(salary)
        .otherEmployerInsurableIncome(declared)
        .build();
  }

  private static BgPaySlip draft(BigDecimal salary) {
    return draft(salary, null);
  }

  /**
   * ⚠️ ДЕКЛАРАЦИЯТА СЕ СЛАГА И НА ФИША, не само във входа — както е в производството.
   *
   * <p>Входът тук се подава готов (сглобителят иска JPA), но пазачите на {@code BgPaySlipService}
   * четат декларацията СЛИП-ЛОКАЛНО: от полето на фиша и от договора. Оставена само във входа, тя
   * щеше да е видима за сметката и НЕВИДИМА за заглавната сума и за отказите — състояние, което
   * производството не може да произведе, и тестът щеше да пази несъществуващ случай.
   */
  private static BgPaySlip draft(BigDecimal salary, BigDecimal declared) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period p = new Period();
    p.setName("06/PY2026");
    p.setFromDate(FROM);
    p.setToDate(TO);
    slip.setPeriod(p);
    slip.setBgBaseSalary(salary);
    slip.setBgOtherPayerInsurableIncome(declared);
    slip.setBgPaySlipLineList(new ArrayList<>());
    return slip;
  }

  /** ⚠️ ЕДНО ИЗВИКВАНЕ. Нито едно перо не се добавя от теста. */
  private static BgPaySlip prepared(
      Seed seed, BigDecimal salary, BigDecimal declared, Payroll[] out) {
    Payroll payroll = new Payroll(seed, inputFor(salary, declared));
    BgPaySlip slip = draft(salary, declared);
    payroll.prepare(slip);
    payroll.recompute(slip);
    if (out != null) {
      out[0] = payroll;
    }
    return slip;
  }

  private static BigDecimal sumOfSign(BgPaySlip slip, String sign) {
    BigDecimal total = BigDecimal.ZERO;
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
          && sign.equals(line.getBgSign())) {
        total = total.add(line.getBgAmount());
      }
    }
    return total.setScale(2, RoundingMode.HALF_UP);
  }

  private static Map<Integer, String> linesOf(BgPaySlip slip) {
    Map<Integer, String> out = new LinkedHashMap<>();
    for (BgPaySlipLine line : BgPaySlipResolver.ordered(slip.getBgPaySlipLineList())) {
      out.put(
          line.getBgLineNumber(),
          line.getBgSign()
              + " "
              + line.getBgAmount().setScale(2, RoundingMode.HALF_UP).toPlainString());
    }
    return out;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ПРЕДПОСТАВКАТА: ТАВАНЪТ ЗА ЮНИ 2026
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Таванът за ЮНИ 2026 в базата е 2111,64 — предпоставката на всички числа отдолу")
  void theCeilingForJuneIsTheOneTheGoldenNumbersAssume() throws Exception {
    Seed seed = seedForJune();
    assertEquals(
        0,
        new BigDecimal("2111.64").compareTo(seed.cap),
        "⚠️ ЗБДОО за 2026 г. чл. 9 т. 1 б. „в“ — 2111,64 евро за 01.01–31.07.2026. Различно число"
            + " тук значи, че всяко златно число отдолу мери друг таван. Намерено: "
            + seed.cap);
    assertEquals(
        0,
        new BigDecimal("10.0000").compareTo(seed.taxRate),
        "чл. 42 ЗДДФЛ — 10 на сто. Намерено: " + seed.taxRate);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // СЦЕНАРИЙ 1 · ЛИЦЕ А — ИЗЧЕРПАН ТАВАН, БАЗА НУЛА
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ ЗЛАТЕН край-до-край · лице А: осиг. база 0,00 → вноски 0,00, данък 125,20, нето 1126,80")
  void anExhaustedCeilingYieldsZeroContributionsAndUntouchedTaxableIncome() throws Exception {
    Payroll[] out = new Payroll[1];
    BgPaySlip slip = prepared(seedForJune(), PERSON_A_SALARY, PERSON_A_DECLARED, out);

    // ── базата, както я е видяла веригата ────────────────────────────────
    assertNotNull(out[0].incomeSeen);
    assertTrue(out[0].incomeSeen.isCoordinated(), "базата излиза по КООРДИНИРАНИЯ път, не по друг");
    assertEquals(
        0,
        new BigDecimal("0.00").compareTo(out[0].incomeSeen.getAmount()),
        "⚠️ 2111,64 − 2111,64 = 0,00: таванът е изчерпан при другия платец. Намерено: "
            + out[0].incomeSeen.getAmount());
    // ⚠️ И МОД НЕ Е ВДИГНАЛ НУЛАТА. 1077,00 тук би значело двоен праг за едно лице.
    assertTrue(
        out[0].incomeSeen.getAmount().signum() == 0,
        "МОД подът НЕ се прилага при координация — прагът е на ЛИЦЕТО за месеца");

    // ── вноските ─────────────────────────────────────────────────────────
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(sumOfSign(slip, BgPaySlipResolver.DEBIT)),
        "личните вноски върху база 0,00 са 0,00. Намерено: "
            + sumOfSign(slip, BgPaySlipResolver.DEBIT));
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(sumOfSign(slip, BgPaySlipResolver.CREDIT)),
        "и работодателските също — те се смятат върху СЪЩАТА база. Намерено: "
            + sumOfSign(slip, BgPaySlipResolver.CREDIT));

    // ── облагаемият доход НЕ е рязан ─────────────────────────────────────
    assertEquals(
        0,
        PERSON_A_SALARY.compareTo(slip.getBgGrossTotal()),
        "⚠️ Координацията реже ОСИГУРИТЕЛНАТА база, не дохода: лицето е получило 1252,00");
    assertEquals(
        0,
        PERSON_A_SALARY.compareTo(slip.getBgTaxBase()),
        "⚠️ Данъчната основа се ВДИГА САМА до пълния доход, защото личните вноски паднаха до нула —"
            + " чл. 42 ал. 2 ЗДДФЛ върху вярната осигурителна база, не изключение от него."
            + " Намерено: "
            + slip.getBgTaxBase());
    assertEquals(
        0,
        new BigDecimal("125.20").compareTo(slip.getBgTaxAmount()),
        "1252,00 × 10 на сто. Намерено: " + slip.getBgTaxAmount());
    assertEquals(
        0,
        new BigDecimal("1126.80").compareTo(slip.getBgNetTotal()),
        "⚠️ 1252,00 − 0,00 вноски − 125,20 данък. Намерено: " + slip.getBgNetTotal());

    // ── B4 · и ЗАГЛАВНИЯТ осигурителен доход е КООРДИНИРАНАТА база ────────
    assertEquals(
        0,
        new BigDecimal("0.00").compareTo(slip.getBgInsuranceIncome()),
        "⚠️ 1252,00 ТУК значи, че екранът твърди осигурителен доход, върху който НИТО ЕДНА вноска"
            + " не е начислена — и точно това число отива в Д1 на ТОЗИ работодател. Заглавната сума"
            + " е базата, ползвана за вноските, не сборът на перата. Намерено: "
            + slip.getBgInsuranceIncome());
    // ⚠️ И РАЗЛИКАТА МЕЖДУ ДВЕТЕ ОСТАВА ВИДИМА: перата пак носят пълния придобит доход — той не се
    // реже, лицето е получило каквото е получило. Двете числа са различни НАРОЧНО.
    assertEquals(
        0,
        PERSON_A_SALARY.compareTo(
            BgPaySlipResolver.insuranceIncomeBase(slip.getBgPaySlipLineList())),
        "перата остават 1252,00 — координацията реже базата, не дохода");

    // ── и пазачът остава доволен: ЕДНА истина за базата ──────────────────
    assertNull(
        BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList()),
        "⚠️ Заглавният данък и перото от 7xx съвпадат, защото и двете стъпват на ЕДНА координирана"
            + " база: вноските я четат, а данъчната основа чете тях. Сметната два пъти,"
            + " координацията щеше да разсинхронизира точно тези две числа.");
  }

  @Test
  @DisplayName(
      "⚠️ СВИДЕТЕЛЯТ (fail-first) · без координация същият фиш дава ДРУГО нето — разликата е видима")
  void withoutTheDeclarationTheSameSlipPaysADifferentNet() throws Exception {
    Seed seed = seedForJune();
    BgPaySlip coordinated = prepared(seed, PERSON_A_SALARY, PERSON_A_DECLARED, null);
    // СЪЩИЯТ вход, само без декларация: базата става 1252,00 и вноските се появяват.
    BgPaySlip alone = prepared(seed, PERSON_A_SALARY, null, null);

    assertTrue(
        sumOfSign(alone, BgPaySlipResolver.DEBIT).signum() > 0,
        "без декларация лични вноски ИМА — инак свидетелят не мери нищо");
    assertTrue(
        alone.getBgNetTotal().compareTo(coordinated.getBgNetTotal()) < 0,
        "⚠️ Координираният фиш плаща ПОВЕЧЕ нето: вноските паднаха, а данъкът се вдигна с една"
            + " десета от тях, тоест девет десети от вноските остават на лицето. Некоординиран: "
            + alone.getBgNetTotal()
            + ", координиран: "
            + coordinated.getBgNetTotal());
    // ⚠️ И ЦЕЛИЯТ ПЪТ НА РАЗЛИКАТА, число по число — за да е ясно откъде идва, а не само че я има.
    assertEquals(
        0,
        PERSON_A_SALARY.compareTo(
            new BigDecimal("0.00")
                .add(alone.getBgTaxBase())
                .add(sumOfSign(alone, BgPaySlipResolver.DEBIT))),
        "некоординирана данъчна основа + лични вноски = пълният доход: "
            + alone.getBgTaxBase()
            + " + "
            + sumOfSign(alone, BgPaySlipResolver.DEBIT));
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // СЦЕНАРИЙ 2 · ЛИЦЕ Б — СБОР ПОД ТАВАНА, ДОХОДЪТ ОСТАВА ЦЯЛ
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ ЗЛАТЕН край-до-край · лице Б: база 398,36 → вноски 54,89, основа 343,47, данък 34,35,"
          + " нето 309,12")
  void aSumBelowTheCeilingLeavesTheWholeOwnIncomeInsured() throws Exception {
    Payroll[] out = new Payroll[1];
    BgPaySlip slip = prepared(seedForJune(), PERSON_B_SALARY, PERSON_B_DECLARED, out);

    assertTrue(out[0].incomeSeen.isCoordinated());
    assertEquals(
        0,
        PERSON_B_SALARY.compareTo(out[0].incomeSeen.getAmount()),
        "⚠️ Остатъкът (611,64) е по-голям от собствения доход, тоест нищо не се реже. Намерено: "
            + out[0].incomeSeen.getAmount());
    // ⚠️ И ТУК МОД НЕ Е ВДИГНАЛ БАЗАТА: 1077,00 би значело двоен праг.
    assertTrue(
        out[0].incomeSeen.getAmount().compareTo(MOD) < 0,
        "базата остава ПОД МОД — прагът вече е покрит при другия платец");

    assertEquals(
        0,
        new BigDecimal("54.89").compareTo(sumOfSign(slip, BgPaySlipResolver.DEBIT)),
        "⚠️ 13,78 на сто общо, но сметнати ПО ФОНД и закръглени по фонд (§ 5.3): 26,21 + 8,76 +"
            + " 5,58 + 1,59 + 12,75. Намерено: "
            + sumOfSign(slip, BgPaySlipResolver.DEBIT));
    assertEquals(
        0,
        PERSON_B_SALARY.compareTo(slip.getBgGrossTotal()),
        "облагаемият доход е пълният придобит");
    assertEquals(
        0,
        new BigDecimal("343.47").compareTo(slip.getBgTaxBase()),
        "398,36 − 54,89. Намерено: " + slip.getBgTaxBase());
    assertEquals(
        0,
        new BigDecimal("34.35").compareTo(slip.getBgTaxAmount()),
        "343,47 × 10 на сто = 34,347 → 34,35 (половина нагоре). Намерено: "
            + slip.getBgTaxAmount());
    assertEquals(
        0,
        new BigDecimal("309.12").compareTo(slip.getBgNetTotal()),
        "398,36 − 54,89 − 34,35. Намерено: " + slip.getBgNetTotal());
    // ── B4 · заглавният осигурителен доход ────────────────────────────────
    assertEquals(
        0,
        PERSON_B_SALARY.compareTo(slip.getBgInsuranceIncome()),
        "⚠️ Тук координираната база СЪВПАДА със сбора на перата (нищо не се реже) — и точно затова"
            + " лице Б не може да е единственият свидетел за B4. Намерено: "
            + slip.getBgInsuranceIncome());

    assertNull(
        BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList()),
        "пазачът остава доволен — една истина за базата");
  }

  @Test
  @DisplayName(
      "⚠️ Числото 1500,00 е ПРЕДСТАВИТЕЛНО: всеки деклариран, оставящ място, дава СЪЩИЯ изход")
  void anyDeclarationLeavingRoomGivesTheSameBase() throws Exception {
    Seed seed = seedForJune();
    // ⚠️ Точният чужд доход на лице Б е PII от друг работодател. Твърдението, което тестът пази, е
    // че изходът НЕ зависи от него, докато сборът е под тавана — тоест PII не е нужно.
    for (String declared : new String[] {"1.00", "900.00", "1500.00", "1713.28"}) {
      BgPaySlip slip = prepared(seed, PERSON_B_SALARY, new BigDecimal(declared), null);
      assertEquals(
          0,
          new BigDecimal("309.12").compareTo(slip.getBgNetTotal()),
          "деклариран "
              + declared
              + " → нетото трябва да е същото. Намерено: "
              + slip.getBgNetTotal());
    }
    // А точно НАД границата (2111,64 − 398,36 = 1713,28) изходът вече Е различен — за да се вижда,
    // че горното твърдение има ръб, а не е вярно за всяко число.
    BgPaySlip cut = prepared(seed, PERSON_B_SALARY, new BigDecimal("1800.00"), null);
    assertTrue(
        cut.getBgNetTotal().compareTo(new BigDecimal("309.12")) > 0,
        "над границата базата се реже, вноските падат и нетото се вдига: " + cut.getBgNetTotal());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // И ЕДИН ОТКАЗ ПРЕЗ ЦЯЛАТА ВЕРИГА
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ОТКАЗ през веригата · морско лице с деклариран чужд доход НЕ получава пера 5xx")
  void aSeafarerWithADeclarationRefusesInsteadOfGuessing() throws Exception {
    Seed seed = seedForJune();
    Payroll payroll =
        new Payroll(seed, inputFor(PERSON_A_SALARY, PERSON_A_DECLARED)) {
          @Override
          protected BgContributionResult contributions(BgPaySlip slip) {
            BgInsurableIncomeResult income =
                BgInsurableIncomeService.compute(
                    insuranceGross(slip),
                    BgMinIncomeResult.resolved(MOD, "МОД-представителен", 1),
                    null,
                    true,
                    seed.cap,
                    FROM,
                    BgWorkTimeFacts.fullMonth(),
                    BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
                    // ⚠️ ЕДИНСТВЕНАТА разлика от златния фиш на лице А: морско лице.
                    true,
                    BgInsurableIncomeService.coordinationOf(PERSON_A_DECLARED));
            return income.isResolved()
                ? super.contributions(slip)
                : BgContributionResult.failure(
                    BgContributionResult.Status.INPUT_NOT_ASSEMBLED, income.getDetail());
          }
        };
    BgPaySlip slip = draft(PERSON_A_SALARY, PERSON_A_DECLARED);
    payroll.prepare(slip);
    payroll.recompute(slip);

    assertTrue(
        linesOf(slip).keySet().stream()
            .noneMatch(n -> n / 100 == BgPaySlipResolver.GROUP_CONTRIBUTIONS),
        "⚠️ НИТО ЕДНО перо от 5xx: отказът НЕ се смята като нула. Перо с 0,00 тук би било"
            + " неразличимо от законната нула на лице А, а причината е съвсем друга. Намерено: "
            + linesOf(slip));
    assertTrue(
        payroll.contributionRefusal(slip).contains("чл. 4а ал. 6"),
        "и отказът при потвърждаване назовава нормата: " + payroll.contributionRefusal(slip));
    assertEquals(
        0,
        PERSON_A_SALARY.compareTo(slip.getBgGrossTotal()),
        "а начисленията си остават — отказът е за ВНОСКИТЕ, не за фиша: " + slip.getBgGrossTotal());
  }
}
