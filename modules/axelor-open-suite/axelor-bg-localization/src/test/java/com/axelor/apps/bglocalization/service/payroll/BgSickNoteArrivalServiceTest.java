package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * УСЛУГАТА НА ПРИСТИГНАЛИЯ БОЛНИЧЕН — пазачите ѝ и двете ѝ странични действия.
 *
 * <p>Чистите правила над дати имат свой файл ({@code BgSickNoteArrivalRulesTest}); тук се проверява
 * онова, което иска СЪСТОЯНИЕ: статусът на листа, статусът на периода, месецът за Приложение № 9.
 * Подменени са само четците към базата — редът на отказите и решенията вървят истински.
 */
class BgSickNoteArrivalServiceTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);
  private static final LocalDate SICK_FROM = LocalDate.of(2026, 12, 7);
  private static final LocalDate SICK_TO = LocalDate.of(2026, 12, 9);
  private static final LocalDate RECEIVED = LocalDate.of(2027, 1, 12);
  private static final LocalDate TODAY = LocalDate.of(2027, 1, 20);

  // ═══ СТАТУСЪТ НА ЛИСТА ═══════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Лист в ЧЕРНОВА не се отразява — ведомостта също чете само ВАЛИДИРАНИ")
  void adraftSickNoteIsRefused() {
    BgSickLeave sickLeave = sickLeave();
    sickLeave.setStatusSelect("DRAFT");

    String refusal = new Service().refusalFor(sickLeave);

    assertNotNull(refusal, "черновата е нечие намерение, не документ");
    assertTrue(refusal.contains("ВАЛИДИРАН"), refusal);
    assertTrue(
        refusal.contains("sickLeaveEpisodesOf"),
        "⚠️ Причината сочи КОЙ ОЩЕ чете само валидирани — инак операторът мисли, че това е каприз"
            + " на бутона, а не същото правило, по което се смята и фишът. Намерено: "
            + refusal);
  }

  @Test
  @DisplayName("⚠️ И ОТКАЗАНИЯТ лист не се отразява — поправката е чрез отказ и НОВ запис")
  void acancelledSickNoteIsRefused() {
    BgSickLeave sickLeave = sickLeave();
    sickLeave.setStatusSelect("CANCELLED");
    assertNotNull(new Service().refusalFor(sickLeave), "отказаният лист не е документ");
  }

  // ═══ КЛЮЧ (Б) ════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ КЛЮЧ (Б) · заключен от подадена Д1/Д6 период → корекцията МИНАВА, копието се ражда")
  void alockedPeriodDoesNotBlockTheArrival() {
    // Периодът е CLOSED и Д1/Д6 за него е ПОДАДЕНА и неотхвърлена — тоест ключ (Б) е вдигнат.
    Service service = new Service();
    service.declaredToNap = true;

    BgSickLeave sickLeave = sickLeave();

    assertNull(
        service.refusalFor(sickLeave),
        "⚠️ КЛЮЧ (Б) НЕ Е ОТКАЗ ЗА ТОЗИ ПОТОК, и това е измерено, не прочетено."
            + " BgPaySlipResolver#refuseLockedPeriod връща null за ретро-фиш: подадената декларация"
            + " заключва РЕДОВНИЯ път и сочи корекцията като изход. Тоест заключеният период прави"
            + " този поток ЕДИНСТВЕНИЯ, а не невъзможния.");

    BgPaySlip copy = service.apply(sickLeave);

    assertNotNull(copy, "копието се ражда");
    assertTrue(copy.getBgIsCorrection(), "и е коригиращо");
    assertEquals(
        BgPaySlipResolver.DRAFT,
        copy.getStatusSelect(),
        "⚠️ И Е ЧЕРНОВА: модел (б) — предложение, което човек потвърждава. Потвърдено тук, услугата"
            + " би преместила пари без нито едно човешко натискане.");
    assertNotNull(copy.getBgCorrectionOf(), "със запазена препратка към оригинала");
  }

  @Test
  @DisplayName("Отворен период → отказ: докато месецът се смята, корекция не е нужна")
  void anopenPeriodIsRefused() {
    Service service = new Service();
    service.periodStatus = PeriodRepository.STATUS_OPENED;
    String refusal = service.refusalFor(sickLeave());
    assertNotNull(refusal);
    assertTrue(refusal.contains("ОТВОРЕН"), refusal);
  }

  // ═══ ВТОРО ОТРАЗЯВАНЕ ВЪРХУ СЪЩИЯ ОРИГИНАЛ ═══════════════════════════════

  @Test
  @DisplayName(
      "⚠️ Второ натискане веднага след успех: казва „вече отразен“, не само „под корекция“")
  void asecondPressRightAfterSuccessNamesTheDraftCopy() {
    // Състоянието след успешно натискане: копие в ЧЕРНОВА, периодът — ADJUSTING.
    Service service = new Service();
    service.periodStatus = PeriodRepository.STATUS_ADJUSTING;
    service.retro.add(
        new BgSickNoteArrivalRules.RetroCopy("FISH-2026-0099", BgPaySlipResolver.DRAFT, false));

    String refusal = service.refusalFor(sickLeave());

    assertNotNull(refusal, "второто натискане не бива да ражда второ копие");
    assertTrue(
        refusal.contains("Повторно отразяване не е разрешено"),
        "⚠️ ПАЗАЧЪТ НА ПОВТОРНОТО ОТРАЗЯВАНЕ Е ПРЕДИ refuseRetroPeriod. Обратното дава „ВЕЧЕ е под"
            + " корекция“, а то не казва, че ТОЗИ лист вече е в копието. Намерено: "
            + refusal);
    assertTrue(refusal.contains("FISH-2026-0099"), "и назовава копието: " + refusal);
  }

  @Test
  @DisplayName("⚠️ Потвърдена корекция на същия оригинал → отказ: делтата е спрямо оригинала")
  void aconfirmedCorrectionOfTheSameOriginalRefuses() {
    // Периодът е CLOSED отново — confirm() го е върнал. Без пазача всички останали откази минават.
    Service service = new Service();
    service.retro.add(
        new BgSickNoteArrivalRules.RetroCopy("FISH-2026-0099", BgPaySlipResolver.CONFIRMED, false));

    String refusal = service.refusalFor(sickLeave());

    assertNotNull(
        refusal,
        "⚠️ ТОВА НЕ БИВА ДА МИНЕ: периодът е CLOSED, оригиналът е потвърден, заявка за отмяна няма."
            + " Второто копие би тръгнало от ОРИГИНАЛА, а settleRetroDelta смята спрямо него.");
    assertTrue(refusal.contains("Повторно отразяване не е разрешено"), refusal);
    assertTrue(refusal.contains("„Потвърден“"), "етикетът, който операторът вижда: " + refusal);
  }

  // ═══ ОТКАЗАНА ПРИСЪДА НА РАЗБИВА ═════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Отказан разбив (няма деклариран стаж) → apply хвърля, вместо празно копие")
  void arefusedSplitStopsTheWholeAction() {
    Service service = new Service();
    service.outcomes =
        Collections.singletonList(
            BgSickLeaveOutcome.refused(
                BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY,
                "осигурителният стаж не е подаден (insuranceServiceMonths)"));

    IllegalStateException refusal =
        assertThrows(IllegalStateException.class, () -> service.apply(sickLeave()));

    assertTrue(
        refusal.getMessage().contains("НЕ е отразен"),
        "⚠️ apply не пренебрегва присъдата и не връща копие без нито една промяна: "
            + refusal.getMessage());
    assertTrue(refusal.getMessage().contains("осигурителният стаж"), refusal.getMessage());
    assertTrue(
        refusal.getMessage().contains("Осигурителният стаж е деклариран"),
        "насоката е с думите от формата на служителя: " + refusal.getMessage());
  }

  // ═══ МЕСЕЦЪТ ЗА ПРИЛОЖЕНИЕ № 9 ═══════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПРАЗЕН bgExportMonth → попълва се с месеца на ПОЛУЧАВАНЕ, не на листа")
  void anemptyExportMonthIsFilledFromTheReceivedDate() {
    BgSickLeave sickLeave = sickLeave();
    assertNull(sickLeave.getBgExportMonth(), "котвата: полето тръгва празно");

    new Service().applyExportMonth(sickLeave);

    assertEquals(
        LocalDate.of(2027, 1, 1),
        sickLeave.getBgExportMonth(),
        "⚠️ МЕСЕЦЪТ НА ПОЛУЧАВАНЕ (01.2027), А НЕ НА ЛИСТА (12.2026). Празното значи „води се по"
            + " периода си“ — а за закъснял лист това е месец, който вече е подаден. Точно затова"
            + " полето съществува.");
  }

  @Test
  @DisplayName("⚠️ ПОПЪЛНЕН bgExportMonth НЕ се пипа — попълненото е нечие решение")
  void afilledExportMonthIsLeftAlone() {
    BgSickLeave sickLeave = sickLeave();
    sickLeave.setBgExportMonth(LocalDate.of(2027, 2, 1));

    new Service().applyExportMonth(sickLeave);

    assertEquals(
        LocalDate.of(2027, 2, 1),
        sickLeave.getBgExportMonth(),
        "⚠️ Операторът може да е насочил листа към ДРУГ пакет — февруарския, защото януарският вече"
            + " е предаден. Презаписано, полето би върнало пакета в подаден месец, и то мълчаливо.");
  }

  @Test
  @DisplayName("Без дата на получаване месецът остава празен — няма от какво да се изведе")
  void withoutAReceivedDateTheExportMonthStaysEmpty() {
    BgSickLeave sickLeave = sickLeave();
    sickLeave.setBgEmployerReceivedDate(null);
    new Service().applyExportMonth(sickLeave);
    assertNull(sickLeave.getBgExportMonth());
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  private static BgSickLeave sickLeave() {
    BgSickLeave sickLeave = new BgSickLeave();
    sickLeave.setId(11L);
    sickLeave.setStatusSelect("VALIDATED");
    sickLeave.setBgDocumentNumber("БЛ-77/2026");
    sickLeave.setFromDate(SICK_FROM);
    sickLeave.setToDate(SICK_TO);
    sickLeave.setBgEmployerReceivedDate(RECEIVED);
    Employee employee = new Employee();
    employee.setId(7L);
    EmploymentContract contract = new EmploymentContract();
    contract.setId(3L);
    contract.setEmployee(employee);
    contract.setBgHoursPerDay(new BigDecimal("8.00"));
    contract.setBgInsuredTypeCode("01");
    sickLeave.setEmploymentContract(contract);
    return sickLeave;
  }

  private static BgPaySlip confirmedSlip(int periodStatus) {
    BgPaySlip slip = new BgPaySlip();
    slip.setId(101L);
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    slip.setReference("BGPS-2026-12-001");
    slip.setBgWorkingDaysInPeriod(22);
    slip.setBgWorkedDays(22);
    slip.setBgBaseSalary(new BigDecimal("1200.00"));
    slip.setBgLeaveDaysOutside(new BigDecimal("3.00"));
    slip.setBgLeaveDaysFrozenAt(DEC_TO.atStartOfDay());
    slip.setBgPaySlipLineList(new ArrayList<>());

    Period period = new Period();
    period.setId(55L);
    period.setName("12/2026");
    period.setFromDate(DEC_FROM);
    period.setToDate(DEC_TO);
    period.setStatusSelect(periodStatus);
    slip.setPeriod(period);

    Employee employee = new Employee();
    employee.setId(7L);
    EmploymentContract contract = new EmploymentContract();
    contract.setId(3L);
    contract.setEmployee(employee);
    contract.setBgHoursPerDay(new BigDecimal("8.00"));
    contract.setBgInsuredTypeCode("01");
    slip.setEmploymentContract(contract);
    return slip;
  }

  /**
   * Услугата с подменени ЧЕТЦИ към базата.
   *
   * <p>⚠️ Подменени са само заявките и записите. Редът на отказите, решението кой е засегнатият фиш
   * и изборът какво да се отмени вървят истински — тоест махне ли някой отказ от списъка,
   * свидетелят пада.
   */
  private static final class Service extends BgSickNoteArrivalService {

    private int periodStatus = PeriodRepository.STATUS_CLOSED;
    private boolean declaredToNap;
    private final List<BgSickNoteArrivalRules.LeaveSpan> leave = new ArrayList<>();
    private final List<BgSickNoteArrivalRules.RetroCopy> retro = new ArrayList<>();
    private List<BgSickLeaveOutcome> outcomes =
        Collections.singletonList(
            BgSickLeaveOutcome.computed(
                BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY,
                new BigDecimal("76.36"),
                new BigDecimal("2"),
                new BigDecimal("54.5455")));

    @Override
    protected BgPaySlip originalSlipFor(BgSickLeave sickLeave) {
      return confirmedSlip(periodStatus);
    }

    @Override
    protected List<BgSickNoteArrivalRules.RetroCopy> existingRetroOf(BgPaySlip original) {
      return retro;
    }

    @Override
    protected List<BgSickNoteArrivalRules.LeaveSpan> cancellableLeave(BgSickLeave sickLeave) {
      return leave;
    }

    @Override
    protected void cancelCoveredLeave(BgSickLeave sickLeave) {
      // Отмяната пипа LeaveRequest през репозиторията; тук твърдението е за пазачите и копието.
    }

    @Override
    protected void saveSickLeave(BgSickLeave sickLeave) {}

    @Override
    protected void saveSlip(BgPaySlip slip) {}

    @Override
    protected LocalDate today() {
      return TODAY;
    }

    /**
     * ⚠️ Ключ (Б) СЕ ВДИГА ТУК — и точно затова свидетелят му е честен: услугата получава период,
     * за който декларацията Е подадена, и въпреки това не отказва.
     */
    @Override
    protected com.axelor.apps.bglocalization.service.BgPaySlipService paySlipService() {
      return new com.axelor.apps.bglocalization.service.BgPaySlipService() {
        /** Производните пера на копието имат свой свидетел (BgPaySlipRetroGeneratorsTest). */
        @Override
        protected void prepareCorrection(BgPaySlip copy) {}

        @Override
        protected boolean declaredToNap(Period period) {
          return declaredToNap;
        }

        @Override
        protected void save(BgPaySlip slip) {}

        @Override
        protected void savePeriod(Period period) {}

        @Override
        protected void logAdjustStart(Period period) {}

        @Override
        protected void logAdjustEnd(Period period) {}

        @Override
        protected BigDecimal incomeTaxRate(BgPaySlip slip) {
          return new BigDecimal("10.0000");
        }

        @Override
        protected com.axelor.apps.bglocalization.service.BgContributionResult contributions(
            BgPaySlip slip) {
          return com.axelor.apps.bglocalization.service.BgContributionResult.resolved(
              Collections.<com.axelor.apps.bglocalization.service.BgContributionLine>emptyList());
        }

        @Override
        public BgPayrollInput payrollInputFor(BgPaySlip slip) {
          return BgPayrollInput.builder()
              .periodFrom(DEC_FROM)
              .periodTo(DEC_TO)
              .parameterReferenceDate(DEC_FROM)
              .workingDaysInPeriod(22)
              .workedDays(22)
              .baseSalary(new BigDecimal("1200.00"))
              .build();
        }
      };
    }

    /**
     * Разбивът има свой свидетел ({@code BgSickNoteArrivalMoneyTest}); тук се подават наготово
     * присъдите му, за да се провери само какво {@code apply} прави с тях.
     */
    @Override
    protected BgSickLeaveService sickLeaveService() {
      return new BgSickLeaveServiceOnDeclaredStage() {
        @Override
        public List<BgSickLeaveOutcome> applyArrival(BgPaySlip slip, BgPayrollInput input) {
          return outcomes;
        }
      };
    }
  }
}
