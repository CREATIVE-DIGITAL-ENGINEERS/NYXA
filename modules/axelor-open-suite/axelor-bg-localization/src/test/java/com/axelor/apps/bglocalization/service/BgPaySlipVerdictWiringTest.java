package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.payroll.BgCompensationLineService;
import com.axelor.apps.bglocalization.service.payroll.BgContributionLineService;
import com.axelor.apps.bglocalization.service.payroll.BgEarningsOutcome;
import com.axelor.apps.bglocalization.service.payroll.BgEarningsService;
import com.axelor.apps.bglocalization.service.payroll.BgIncomeTaxService;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipLineOutcome;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipVerdicts;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveOutcome;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveService;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveServiceOnDeclaredStage;
import com.axelor.apps.bglocalization.service.payroll.BgUnpaidLeaveHealthResolver;
import com.axelor.apps.bglocalization.service.payroll.BgUnpaidLeaveHealthService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ШЕСТТЕ ШЕВА НА {@code prepare()} НЕ ИЗХВЪРЛЯТ ПРИСЪДИТЕ — измерено ПРЕЗ ТЯХ.
 *
 * <p><b>Защо отделен тест от {@code BgGeneratorRefusalVisibleTest}.</b> Онзи доказва, че всеки
 * генератор ОТКАЗВА с назована причина и че причината се превежда вярно. Този доказва другата
 * половина: че {@code BgPaySlipService} ВЗЕМА върнатия списък. Двете не се заместват — първата може
 * да е вярна, а фишът пак да излиза мълчалив.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА ГЕНЕРАТОРНИТЕ УСЛУГИ, НЕ СТЪПКИТЕ.</b> Ако тестът подменеше {@code
 * generateEarnings}, той щеше да подмени и записа на присъдите — тоест да проверява собствената си
 * фикстура. Затова подменени са само {@code earningsService()} и петте му събратя: стъпката, която
 * се мери, си остава производствената.
 *
 * <p>⚠️ Услугите връщат ОТКАЗ, а не сметнато перо: отказът е състоянието, което е било невидимо.
 * Тече без база, без Guice и без Hibernate — и НЕ се пропуска.
 */
class BgPaySlipVerdictWiringTest {

  private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate TO = LocalDate.of(2026, 3, 31);

  private static final String EARNINGS_WHY = "основната заплата не е попълнена";
  private static final String COMPENSATION_WHY = "обезщетението OBEZ-1 няма вид";
  private static final String CONTRIBUTION_WHY = "видът осигурен е празен на договора";
  private static final String SICK_WHY = "осигурителният стаж не е подаден (чл. 40 ал. 1 КСО)";
  private static final String UNPAID_WHY = "два различни повода за неплатен отпуск в един месец";
  private static final String TAX_WHY = "ставката по чл. 42 липсва в каталога";

  /**
   * Услугата с подменени ГЕНЕРАТОРНИ УСЛУГИ и подменени входове — стъпките остават нейни.
   *
   * <p>⚠️ Всяка от шестте връща по един ОТКАЗ. Смисълът е точно този: тези шест реда трябва да
   * стигнат до фиша, а не само да се произведат и върнат.
   */
  private static class Service extends BgPaySlipService {

    @Override
    protected BgPayrollInput payrollInput(BgPaySlip slip) {
      return BgPayrollInput.builder()
          .periodFrom(FROM)
          .periodTo(TO)
          .parameterReferenceDate(FROM)
          .build();
    }

    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      return BgContributionResult.failure(
          BgContributionResult.Status.NO_CONTRIBUTIONS_FOUND, CONTRIBUTION_WHY);
    }

    @Override
    protected BgEarningsService earningsService() {
      return new BgEarningsService() {
        @Override
        public List<BgEarningsOutcome> generate(BgPaySlip slip, BgPayrollInput input) {
          return Collections.singletonList(BgEarningsOutcome.refused("pl_osnovna", EARNINGS_WHY));
        }
      };
    }

    @Override
    protected BgCompensationLineService compensationLineService() {
      return new BgCompensationLineService() {
        @Override
        public List<BgPaySlipLineOutcome> generate(BgPaySlip slip, BgPayrollInput input) {
          return Collections.singletonList(
              BgPaySlipLineOutcome.refused("OBEZ-1", COMPENSATION_WHY));
        }
      };
    }

    @Override
    protected BgContributionLineService contributionLineService() {
      return new BgContributionLineService() {
        @Override
        public List<BgPaySlipLineOutcome> generate(BgPaySlip slip, BgContributionResult result) {
          return Collections.singletonList(
              BgPaySlipLineOutcome.refused("вноските", CONTRIBUTION_WHY));
        }
      };
    }

    @Override
    protected BgSickLeaveService sickLeaveService() {
      return new BgSickLeaveServiceOnDeclaredStage() {
        @Override
        public List<BgSickLeaveOutcome> generate(BgPaySlip slip, BgPayrollInput input) {
          return Collections.singletonList(
              BgSickLeaveOutcome.refused("pl_bolnichen_osig", SICK_WHY));
        }
      };
    }

    @Override
    protected BgUnpaidLeaveHealthService unpaidLeaveHealthService() {
      return new BgUnpaidLeaveHealthService() {
        @Override
        public BgUnpaidLeaveHealthResolver.Outcome generate(BgPaySlip slip, BgPayrollInput input) {
          return BgUnpaidLeaveHealthResolver.Outcome.refused(UNPAID_WHY);
        }
      };
    }

    @Override
    protected BgIncomeTaxService incomeTaxService() {
      return new BgIncomeTaxService() {
        @Override
        public List<BgPaySlipLineOutcome> generate(
            BgPaySlip slip, BigDecimal ratePercent, BigDecimal relief) {
          return Collections.singletonList(BgPaySlipLineOutcome.refused("pl_dod", TAX_WHY));
        }
      };
    }

    @Override
    protected boolean hasAlternativeTax(BgPaySlip slip) {
      return false;
    }

    @Override
    protected boolean civilContractSlip(BgPaySlip slip) {
      return false;
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.00");
    }

    @Override
    protected BigDecimal disabilityRelief(BgPaySlip slip) {
      return null;
    }
  }

  private static BgPaySlip slip() {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period period = new Period();
    period.setFromDate(FROM);
    period.setToDate(TO);
    slip.setPeriod(period);
    slip.setBgPaySlipLineList(new ArrayList<>());
    return slip;
  }

  /**
   * ⚠️ ПО ЕДИН СВИДЕТЕЛ ЗА ВСЕКИ ШЕВ, а не един за всичките: вземането е шест ОТДЕЛНИ реда и може
   * да се разкачи на всеки от тях поотделно.
   *
   * <p>FAIL-FIRST: върнатият списък беше оставен неприсвоен → полето остана {@code null} и
   * свидетелят падна.
   */
  @Test
  @DisplayName("⚠️ ВСЕКИ от шестте шева на prepare() ЗАПИСВА отказа си върху фиша")
  void everySeamRecordsItsRefusal() {
    Service service = new Service();

    assertRefusalRecorded(
        service, BgPaySlipVerdicts.STEP_SICK_LEAVE, SICK_WHY, s -> service.generateSickLeave(s));
    assertRefusalRecorded(
        service, BgPaySlipVerdicts.STEP_EARNINGS, EARNINGS_WHY, s -> service.generateEarnings(s));
    assertRefusalRecorded(
        service,
        BgPaySlipVerdicts.STEP_COMPENSATIONS,
        COMPENSATION_WHY,
        s -> service.generateCompensations(s));
    assertRefusalRecorded(
        service, BgPaySlipVerdicts.STEP_INCOME_TAX, TAX_WHY, s -> service.generateIncomeTax(s));
    assertRefusalRecorded(
        service,
        BgPaySlipVerdicts.STEP_UNPAID_LEAVE_HEALTH,
        UNPAID_WHY,
        s -> service.generateUnpaidLeaveHealth(s));
  }

  /** Осигуровките водят и здравната при неплатен отпуск — тоест ДВА отказа от една стъпка. */
  @Test
  @DisplayName("⚠️ ОСИГУРОВКИ · стъпката записва и своя отказ, и този на здравната по чл. 40 ал. 1")
  void theContributionSeamRecordsBothOfItsRefusals() {
    BgPaySlip slip = slip();
    BgPaySlipVerdicts.reset(slip);

    new Service().generateContributions(slip);

    assertEquals(2, slip.getBgGeneratorRefusalCount().intValue(), slip.getBgGeneratorNotes());
    assertTrue(slip.getBgGeneratorGaps().contains(CONTRIBUTION_WHY), slip.getBgGeneratorGaps());
    assertTrue(
        slip.getBgGeneratorGaps().contains(UNPAID_WHY),
        "⚠️ здравната при неплатен отпуск е ВТОРА присъда на същата стъпка — изгубена, лицето остава"
            + " без вноска по ЗЗО чл. 40 ал. 1 т. 1 б. „б“ мълчаливо: "
            + slip.getBgGeneratorGaps());
  }

  /**
   * ⚠️ ПРЕДУПРЕЖДЕНИЕТО ЧЕТЕ ПОЛЕТО, а не текста: причината е цяло изречение и има право да съдържа
   * нов ред.
   */
  @Test
  @DisplayName("⚠️ отказът стига до ОПЕРАТОРА — предупреждението носи броя и къде се чете")
  void theOperatorIsWarned() {
    BgPaySlip slip = slip();
    BgPaySlipVerdicts.reset(slip);
    new Service().generateEarnings(slip);

    String warning = BgPaySlipResolver.generatorRefusalWarning(slip);

    assertNotNull(warning, "⚠️ отказ без предупреждение е точно тихата нула");
    assertTrue(warning.contains("1"), warning);
    assertTrue(warning.contains("Какво липсва"), "казва КЪДЕ се чете причината: " + warning);
    assertNull(
        BgPaySlipResolver.generatorRefusalWarning(slip()),
        "фиш без откази не бива да вдига предупреждение — иначе окото свиква да го прескача");
  }

  private interface Step {
    void run(BgPaySlip slip);
  }

  private static void assertRefusalRecorded(Service service, String step, String why, Step call) {
    BgPaySlip slip = slip();
    BgPaySlipVerdicts.reset(slip);

    call.run(slip);

    assertEquals(1, slip.getBgGeneratorRefusalCount().intValue(), step);
    assertTrue(slip.getBgGeneratorGaps().contains(step), step + ": " + slip.getBgGeneratorGaps());
    assertTrue(slip.getBgGeneratorGaps().contains(why), step + ": " + slip.getBgGeneratorGaps());
    assertTrue(slip.getBgPaySlipLineList().isEmpty(), step + ": отказ НЕ пише перо");
  }
}
