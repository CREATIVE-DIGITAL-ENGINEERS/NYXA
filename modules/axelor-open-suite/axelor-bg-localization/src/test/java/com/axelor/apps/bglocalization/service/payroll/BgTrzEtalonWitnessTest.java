package com.axelor.apps.bglocalization.service.payroll;

import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.amountOf;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.fresh;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.healthT5;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.lines;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.pin;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.quantityOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Result;
import com.axelor.db.JpaSupport;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ТРЗ СРЕЩУ ЕТАЛОН ОТ MICROINVEST, 10/2026 — по реалния път ({@link BgPayrollRealPath}).
 *
 * <p>Общи данни: 22 работни дни, 4 ч/ден; вид осигурен 01, кв. гр. 9, НКИД 91.11 (ТЗПБ 0,5 %),
 * роден след 1959 (ДЗПО 2,8/2,2); ТЕЛК ≥ 50 % (ЗДДФЛ чл. 18 — 337,45 €/мес.). Основна 310,10 €,
 * клас за ТС 115,36 € (310,10 × 1,2 % × 31 г. — изборът на вход е наш, еталонът дава само сумата),
 * парично СБКО 7,85 €.
 *
 * <p><b>Разминавания с еталона — закачени за ТЕКУЩОТО поведение, всяко е отворен въпрос:</b>
 *
 * <ul>
 *   <li>т. 29 за вид 01: еталонът 0,00 (и вторият еталон, {@code BgD1EtalonFileTest}), Nyxa пише
 *       брутото ({@code BgD1RecordBuilder} т. 29). Въпрос: т. 29 за вид 01 празна ли е винаги, и в
 *       случай Б.
 *   <li>т. 21 в А: 336,61 (всяко перо HALF_UP: 239,62 + 89,14 + 7,85); точният сбор 336,6124 също
 *       дава 336,61. Въпрос: перата 100 и 110 на Microinvest за случай А.
 *   <li>отпускът по чл. 177 в Б: 116,03 (карв + надбавка, {@code BgArt177Resolver}), еталонът 6 ×
 *       19,34 = 116,04. Въпрос: закръгля ли се среднодневното преди умножението.
 * </ul>
 *
 * <p>Дните без право на обезщетение са в т. 16.4, не в т. 16.2, и тук са закачени за еталона.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgTrzEtalonWitnessTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgPayrollRealPath.logout();
  }

  private static final LocalDate OCT_FROM = LocalDate.of(2026, 10, 1);
  private static final LocalDate OCT_TO = LocalDate.of(2026, 10, 31);

  // ═══ СЛУЧАЙ А — болничен 5 работни дни, стаж < 6 месеца ═══════════════════

  @Test
  @DisplayName("А: болничен 5 дни без 6 месеца стаж — фиш и Д1 срещу еталона")
  void caseA_sickWithoutSixMonths() throws Exception {
    BgPayrollRealPath.seed();
    Fixture f = BgPayrollRealPath.fixture("A", 3);
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = BgPayrollRealPath.slip(f, oct, "OCT");
    LocalDate from = LocalDate.of(2026, 10, 12);
    LocalDate to = LocalDate.of(2026, 10, 16);
    BgPayrollRealPath.sickSheet(f, "sick_er", from, to, "Е2026TEST0045");
    BgPayrollRealPath.timesheet(f, OCT_FROM, OCT_TO, from, to);

    Result r = BgPayrollRealPath.run(f, slipId, oct);
    System.out.println("\n=== СЛУЧАЙ А ===\n" + r.trace + lines(r.slip));

    // ── съвпада с еталона ──
    pin("17", r.rec.getT161WorkedDays(), "т.16.1");
    pin("0", r.rec.getT162SickDays(), "т.16.2 — няма дни С право на обезщетение");
    pin("5", r.rec.getT164NoContributionDays(), "т.16.4 — 5 дни ВН без право (чл. 40 ал. 1 КСО)");
    pin("0", r.rec.getT16aEmployerSickDays(), "т.16.А — осигурителят не плаща първите дни");
    pin("68", r.rec.getT167WorkedHours(), "т.16.7 = 17 × 4 ч");
    pin("140.95", r.rec.getT17Art4015Income(), "т.17 = 620,20 / 22 × 5");
    pin("4.80", r.rec.getT18HealthRateOnT17(), "т.18 — ЗО само за осигурителя");
    pin("0.00", r.slip.getBgTaxAmount(), "данък — ТЕЛК 337,45 поглъща основата");
    pin("6.77", healthT5(r.slip), "516 по ЗЗО чл. 40 ал. 1 т. 5: 140,95 × 4,8 %");
    assertNull(amountOf(r.slip, "pl_bolnichen_rabotodatel"), "без перо 195 (чл. 40 ал. 5)");
    assertNull(amountOf(r.slip, 180), "без перо 180");
    assertNull(amountOf(r.slip, 181), "без перо 181");
    // ── разминаване с еталона: т.29 за вид 01 ──
    pin("328.76", r.rec.getT29GrossWage(), "ОТВОРЕНО: еталонът т.29 = 0,00 за вид 01");
    // ── разминаване с еталона: една стотинка в т.21 ──
    pin(
        "336.61",
        r.rec.getT21InsuranceIncome(),
        "ОТВОРЕНО: т.21 = 239,62 + 89,14 + 7,85 (всяко перо HALF_UP); еталонът 336,62");
    pin("336.61", r.rec.getT31TaxableIncome(), "ОТВОРЕНО: т.31 — еталонът 336,62");
    pin("290.22", r.slip.getBgNetTotal(), "ОТВОРЕНО: нето — еталонът 290,23");
  }

  // ═══ СЛУЧАЙ А' — същото, но стажът НЕ е деклариран ════════════════════════

  @Test
  @DisplayName("А': стажът не е деклариран — шумен отказ, не нула")
  void caseA_serviceUnknownIsLoud() throws Exception {
    BgPayrollRealPath.seed();
    Fixture f = BgPayrollRealPath.fixture("A0", null);
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.sickSheet(
        f, "sick_er", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 16), "Е2026TEST0046");
    ActionResponse recalc = BgPayrollRealPath.press("recalculate", slipId);
    BgPaySlip slip = fresh(em -> em.find(BgPaySlip.class, slipId));
    String gaps = String.valueOf(slip.getBgGeneratorGaps());
    assertTrue(
        slip.getBgGeneratorRefusalCount() > 0 && gaps.contains("стаж"),
        "липсващият стаж е НАЗОВАН отказ: " + recalc.getData() + " · " + gaps);
    assertEquals(0, slip.getBgSickDaysNoBenefit(), "при отказ дни без право НЕ се измислят");
  }

  // ═══ СЛУЧАЙ Б — 16 отработени + 4 ПГО + 2 по чл. 157 (чл. 177) ════════════

  @Test
  @DisplayName("Б: 16 отработени + 4 дни ПГО + 2 дни по чл. 157 — фиш и Д1 срещу еталона")
  void caseB_leaveUnderArt177() throws Exception {
    BgPayrollRealPath.seed();
    Fixture f = BgPayrollRealPath.fixture("B", 120);
    // Базовият месец по чл. 177 ал. 1 — септември, ЦЯЛ отработен, по същия път.
    LocalDate sepFrom = LocalDate.of(2026, 9, 1);
    LocalDate sepTo = LocalDate.of(2026, 9, 30);
    Long sep = BgPayrollRealPath.period(f, sepFrom, sepTo);
    Long sepSlipId = BgPayrollRealPath.slip(f, sep, "SEP");
    BgPayrollRealPath.timesheet(f, sepFrom, sepTo, null, null);
    BgPayrollRealPath.press("recalculate", sepSlipId);
    BgPayrollRealPath.press("confirm", sepSlipId);
    BgPaySlip sepSlip = fresh(em -> em.find(BgPaySlip.class, sepSlipId));
    assertEquals(BgPaySlipResolver.CONFIRMED, sepSlip.getStatusSelect(), "базовият месец");
    pin("310.10", amountOf(sepSlip, "pl_osnovna"), "09: основна");
    pin("115.36", amountOf(sepSlip, "pl_tspo"), "09: клас = 310,10 × 1,2 % × 31");

    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = BgPayrollRealPath.slip(f, oct, "OCT");
    // пн 12.10 – чт 15.10: ПГО; пн 19.10 – вт 20.10: платен отпуск по чл. 157 (режимът по чл. 177)
    BgPayrollRealPath.leave(
        f, "paid_annual", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15), 4);
    BgPayrollRealPath.leave(
        f, "paid_157_art177", LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20), 2);
    // 16 отработени дни × 4 ч = 64 ч: без 12–15 и 19–20.10 (петък 16.10 е работен)
    BgPayrollRealPath.timesheet(
        f,
        OCT_FROM,
        LocalDate.of(2026, 10, 18),
        LocalDate.of(2026, 10, 12),
        LocalDate.of(2026, 10, 15));
    BgPayrollRealPath.timesheet(
        f,
        LocalDate.of(2026, 10, 19),
        OCT_TO,
        LocalDate.of(2026, 10, 19),
        LocalDate.of(2026, 10, 20));

    Result r = BgPayrollRealPath.run(f, slipId, oct);
    System.out.println("\n=== СЛУЧАЙ Б ===\n" + r.trace + lines(r.slip));

    // ── съвпада с еталона ──
    pin("22", r.rec.getT161WorkedDays(), "т.16.1 — дните по чл. 157 и ПГО са в 16.1");
    pin("0", r.rec.getT164NoContributionDays(), "т.16.4");
    pin("88", r.rec.getT167WorkedHours(), "т.16.7 = 64 отработени + 6 × 4 ч отпуск");
    pin("225.53", amountOf(r.slip, "pl_osnovna"), "100 = 310,10 × 16 / 22");
    pin("83.90", amountOf(r.slip, "pl_tspo"), "110 = 115,36 × 16 / 22");
    pin("6", quantityOf(r.slip, 165), "165: 6 дни (4 ПГО + 2 по чл. 157)");
    pin("7.85", amountOf(r.slip, "pl_soc_ne_v_natura_dohod"), "271 СБКО");
    pin("3.62", r.slip.getBgTaxAmount(), "данък — ТЕЛК 337,45 месечно");
    // ── разминаване с еталона: среднодневното закръглява ли се преди умножението ──
    pin(
        "116.03",
        amountOf(r.slip, 165),
        "ОТВОРЕНО: 165 = карв 116,03 + надбавка 0,00 (BgArt177Resolver); еталонът 6 × 19,34");
    pin("433.31", r.rec.getT21InsuranceIncome(), "ОТВОРЕНО: т.21 — еталонът 433,32");
    pin("433.31", r.rec.getT31TaxableIncome(), "ОТВОРЕНО: т.31 — еталонът 433,32");
    pin("369.98", r.slip.getBgNetTotal(), "ОТВОРЕНО: нето — еталонът 369,99");
    // ── разминаване с еталона: т.29 — за Б еталон няма ──
    pin("425.46", r.rec.getT29GrossWage(), "ОТВОРЕНО: т.29 = 225,53 + 83,90 + 116,03");
  }
}
