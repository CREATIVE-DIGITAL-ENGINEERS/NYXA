package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveServiceOnDeclaredStage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Т. 9 ОТ ПРИСТИГНАЛИЯ ЛИСТ ДО ФАЙЛА ЗА НОИ — разбивът пише редовете, емитерът ги чете, схемата ги
 * приема.
 *
 * <p>Без редове по т. 9 Приложение № 9 излиза без нито един {@code BSickLeave}. Тук редовете идват
 * от ИСТИНСКИЯ разбив ({@code applyArrival}) върху листа на фикстурата на Приложение № 9 — не са
 * написани на ръка, — а файлът минава през ИСТИНСКИЯ емитер и през {@code Pril9.xsd}.
 *
 * <p>Листът е 05–12.06.2026 (пт–пт; 06–07 и 13–14 са почивни): 6 работни дни — 05 и 08 за сметка на
 * осигурителя по чл. 40 ал. 5 КСО, 09–12 за сметка на ДОО. Схемата носи ЕДНО число на месец — сбора
 * ({@link BgApp9Point9}).
 */
class BgApp9Point9FromArrivalTest {

  private static final BgApp9Validator VALIDATOR = new BgApp9Validator();

  /** Разбивът с подменени ЧЕТЦИ към базата; разделянето и т. 9 вървят истински. */
  private static final class Engine extends BgSickLeaveServiceOnDeclaredStage {

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

    private final BgSickLeave sheet;

    Engine(BgSickLeave sheet) {
      this.sheet = sheet;
    }

    @Override
    protected BgSickLeave sickLeaveById(Long id) {
      return id != null && id.equals(sheet.getId()) ? sheet : null;
    }

    @Override
    protected void saveSickLeave(BgSickLeave sickLeave) {}

    @Override
    protected BgAbsenceType absenceType(String code) {
      BgAbsenceType type = new BgAbsenceType();
      type.setCode(code);
      type.setPayer("employer");
      type.setCompensationPct(new BigDecimal("70"));
      type.setRequiresSickNote(true); // A4: вид на болничен лист, като в сийда
      type.setCountsAsInsuranceService(true);
      return type;
    }

    @Override
    protected Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
      return Collections.emptySet();
    }

    @Override
    protected BgPaySlipLineType lineType(String code) {
      BgPaySlipLineType type = new BgPaySlipLineType();
      type.setCode(code);
      type.setNumber(195);
      type.setName(code);
      type.setBgSign(BgPaySlipResolver.CREDIT);
      type.setBgIsInsuranceIncome(true);
      return type;
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgLineNumber(type.getNumber());
      line.setName(type.getName());
      line.setBgSign(type.getBgSign());
      line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
      line.setBgAmount(amount);
      return line;
    }

    @Override
    protected BigDecimal healthAbsenceBase(LocalDate date) {
      return new BigDecimal("550.66");
    }

    @Override
    protected BigDecimal healthEmployerRatePct(BgPayrollInput input, LocalDate date) {
      return new BigDecimal("4.80");
    }

    /** Перото 516 по т. 5 не е предмет на този свидетел — то има свой. */
    @Override
    protected void writeSpecialHealthBase(
        BgPaySlip slip, BigDecimal base, BigDecimal ratePercent, BigDecimal amount) {}
  }

  @Test
  @DisplayName("пристигнал лист → т. 9 по месеци → Приложение № 9 минава Pril9.xsd")
  void anArrivedSheetReachesTheFileAndPassesTheSchema() {
    BgSickLeave sheet = BgApp9Fixture.sickLeave();
    sheet.setId(31L);
    LocalDate from = LocalDate.of(2026, 6, 1);
    LocalDate to = LocalDate.of(2026, 6, 30);

    // Коригиращото копие за юни, както го ражда „Отрази пристигнал болничен“.
    BgPaySlip copy = new BgPaySlip();
    copy.setStatusSelect(BgPaySlipResolver.DRAFT);
    copy.setBgIsCorrection(true);
    Period june = new Period();
    june.setFromDate(from);
    june.setToDate(to);
    june.setStatusSelect(PeriodRepository.STATUS_ADJUSTING);
    copy.setPeriod(june);
    copy.setBgWorkingDaysInPeriod(22);
    copy.setBgWorkedDays(22);
    copy.setBgBaseSalary(new BigDecimal("1200.00"));
    copy.setBgPaySlipLineList(new ArrayList<BgPaySlipLine>());

    BgPayrollInput input =
        BgPayrollInput.builder()
            .periodFrom(from)
            .periodTo(to)
            .parameterReferenceDate(from)
            .workingDaysInPeriod(22)
            .workedDays(22)
            .baseSalary(new BigDecimal("1200.00"))
            .insuranceServiceMonths(120)
            .insuredTypeCode("01")
            .laborCategory("3")
            .workTimeMode("1")
            .contractHoursPerDay(new BigDecimal("8.00"))
            .sickLeaveEpisode(
                new BgPayrollInput.SickLeaveEpisode(
                    sheet.getFromDate(),
                    sheet.getToDate(),
                    false,
                    sheet.getBgDocumentNumber(),
                    "sick_er",
                    sheet.getId()))
            .build();

    new Engine(sheet).applyArrival(copy, input);

    assertEquals(2, sheet.getBgPeriodLineList().size(), "т. 9: осигурител и ДОО за юни");
    int days = 0;
    for (com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine line :
        sheet.getBgPeriodLineList()) {
      days += line.getWorkDays();
    }
    assertEquals(6, days, "05 и 08 за осигурителя, 09–12 за ДОО");

    BgApp9Declaration d = BgApp9Fixture.declarationFor(sheet);
    BgApp9Emission emission = BgApp9Emitter.emit(BgApp9Fixture.context(), BgApp9Fixture.one(d), 1);
    assertFalse(emission.isRefused(), "емисията отказа: " + emission.gapReport());

    String xml = emission.getXml();
    assertTrue(xml.contains("<BSickLeave>"), "файлът носи т. 9: " + xml);
    assertTrue(xml.contains("<Days>6</Days>"), "сборът за юни — 6 работни дни: " + xml);
    List<BgApp9Finding> schema = VALIDATOR.validateAgainstSchema(xml);
    assertTrue(schema.isEmpty(), "схемата намери: " + BgApp9Validator.report(schema));
  }
}
