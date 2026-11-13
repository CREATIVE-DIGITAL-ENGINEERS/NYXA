package com.axelor.apps.bglocalization.service.payroll;

import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.amountOf;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.fresh;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.pin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.d1.BgD1DeclarationKind;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Result;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Д1: ДНИТЕ ВН БЕЗ ПРАВО НА ОБЕЗЩЕТЕНИЕ СА В Т. 16.4, НЕ В Т. 16.2 — по реалния път ({@link
 * BgPayrollRealPath}).
 *
 * <p><b>Основание.</b> КСО чл. 40 ал. 1 — без 6 месеца осигурителен стаж няма парично обезщетение;
 * указание на НОИ № 1029-40-1563/17.02.2016 — тогава осигурителят не плаща и дните по чл. 40 ал. 5;
 * КСО чл. 9 ал. 2 т. 2 (бележката на {@code sick_no_benefit_*} в {@code bg_absence_type.csv}) —
 * дните се зачитат за стаж без вноски. Указанията за Д1: т. 16.2 е за дните с право на обезщетение,
 * т. 16.4 — за неплатените дни ВН. Еталонът на Microinvest: 16.2 = 0, 16.4 = 5, т. 17 = 140,95, т.
 * 18 — само работодател.
 *
 * <p><b>Носителят.</b> {@code BgPaySlip.bgSickDaysNoBenefit} — подмножество на дните ДОО, попълнено
 * при пресмятането по присъдата на {@code BgSickLeaveResolver#eligibilityOf} (прагът на стажа) и
 * ЗАМРАЗЕНО: отпечатък, коригиращо копие. Здравната вноска по т. 5 продължава да чете всички дни
 * ДОО.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgD1SickWithoutBenefitTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgPayrollRealPath.logout();
  }

  private static final LocalDate OCT_FROM = LocalDate.of(2026, 10, 1);
  private static final LocalDate OCT_TO = LocalDate.of(2026, 10, 31);
  private static final LocalDate NOV_FROM = LocalDate.of(2026, 11, 1);
  private static final LocalDate NOV_TO = LocalDate.of(2026, 11, 30);

  /** пн 26.10 – пт 06.11: по 5 работни дни във всеки месец. */
  private static final LocalDate SPAN_FROM = LocalDate.of(2026, 10, 26);

  private static final LocalDate SPAN_TO = LocalDate.of(2026, 11, 6);

  // ═══ (а) ЛИСТ ПРЕЗ ДВА МЕСЕЦА, БЕЗ ПРАВО ═════════════════════════════════

  @Test
  @DisplayName("(а) болничен през два месеца без право — и двата месеца в т.16.4")
  void twoMonthsWithoutBenefit() throws Exception {
    BgPayrollRealPath.seed();
    Fixture f = BgPayrollRealPath.fixture("Ta", 3);
    BgPayrollRealPath.sickSheet(f, "sick_er", SPAN_FROM, SPAN_TO, "Е2026TEST0101");

    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long octSlip = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.timesheet(f, OCT_FROM, OCT_TO, SPAN_FROM, SPAN_TO);
    Result r10 = BgPayrollRealPath.run(f, octSlip, oct);

    Long nov = BgPayrollRealPath.period(f, NOV_FROM, NOV_TO);
    Long novSlip = BgPayrollRealPath.slip(f, nov, "NOV");
    BgPayrollRealPath.timesheet(f, NOV_FROM, NOV_TO, SPAN_FROM, SPAN_TO);
    Result r11 = BgPayrollRealPath.run(f, novSlip, nov);

    assertEquals(5, r10.slip.getBgSickDaysNoBenefit(), "10: замразено на фиша");
    pin("0", r10.rec.getT162SickDays(), "10: т.16.2");
    pin("5", r10.rec.getT164NoContributionDays(), "10: т.16.4 — пн 26 – пт 30.10");
    pin("0", r10.rec.getT16aEmployerSickDays(), "10: т.16.А");
    pin("17", r10.rec.getT161WorkedDays(), "10: т.16.1 = 22 − 5, без двойно изваждане");
    pin("140.95", r10.rec.getT17Art4015Income(), "10: т.17 = 620,20 / 22 × 5 — не се пипа");

    int novNorm = BgPayrollRealPath.workingDays(NOV_FROM, NOV_TO);
    assertEquals(5, r11.slip.getBgSickDaysNoBenefit(), "11: замразено на фиша");
    pin("0", r11.rec.getT162SickDays(), "11: т.16.2");
    pin("5", r11.rec.getT164NoContributionDays(), "11: т.16.4 — пн 02 – пт 06.11");
    pin(String.valueOf(novNorm - 5), r11.rec.getT161WorkedDays(), "11: т.16.1 = норма − 5");
    pin(
        new BigDecimal("620.20")
            .multiply(BigDecimal.valueOf(5))
            .divide(BigDecimal.valueOf(novNorm), 2, RoundingMode.HALF_UP)
            .toPlainString(),
        r11.rec.getT17Art4015Income(),
        "11: т.17 = 620,20 / " + novNorm + " × 5");
  }

  // ═══ (б) ТРУДОВА ЗЛОПОЛУКА БЕЗ 6 МЕСЕЦА — С ПРАВО ═════════════════════════

  @Test
  @DisplayName("(б) трудова злополука без 6 месеца стаж — с право, т.16.А и т.16.2")
  void workAccidentHasTheRight() throws Exception {
    BgPayrollRealPath.seed();
    Fixture f = BgPayrollRealPath.fixture("Tb", 3);
    LocalDate from = LocalDate.of(2026, 10, 12);
    LocalDate to = LocalDate.of(2026, 10, 16);
    BgPayrollRealPath.sickSheet(f, "work_accident_er", from, to, "Е2026TEST0102");
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.timesheet(f, OCT_FROM, OCT_TO, from, to);

    Result r = BgPayrollRealPath.run(f, slipId, oct);

    assertEquals(0, r.slip.getBgSickDaysNoBenefit(), "злополуката не иска стаж (чл. 40 ал. 2)");
    pin("2", r.rec.getT16aEmployerSickDays(), "т.16.А — първите два работни дни");
    pin("3", r.rec.getT162SickDays(), "т.16.2 — останалите три, С право");
    pin("0", r.rec.getT164NoContributionDays(), "т.16.4");
    pin("17", r.rec.getT161WorkedDays(), "т.16.1");
    assertNotNull(amountOf(r.slip, "pl_bolnichen_rabotodatel"), "перо 195 има");
  }

  // ═══ (в) СТАЖЪТ СТАВА 6 МЕСЕЦА ПО ВРЕМЕ НА БОЛНИЧНИЯ ═══════════════════════

  @Test
  @DisplayName(
      "(в) стажът става 6 месеца по време на листа — правото е КЪМ НАЧАЛОТО: и ноември е т.16.4")
  void serviceReachesSixMonthsDuringTheSheet() throws Exception {
    BgPayrollRealPath.seed();
    Fixture f = BgPayrollRealPath.fixture("Tc", 5);
    BgPayrollRealPath.sickSheet(f, "sick_er", SPAN_FROM, SPAN_TO, "Е2026TEST0103");

    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long octSlip = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.timesheet(f, OCT_FROM, OCT_TO, SPAN_FROM, SPAN_TO);
    Result r10 = BgPayrollRealPath.run(f, octSlip, oct);
    pin("5", r10.rec.getT164NoContributionDays(), "10: стаж 5 месеца — т.16.4");

    // ТРЗ обявява 6 месеца стаж към 31.10 ПРЕДИ ноемврийския фиш.
    BgPayrollRealPath.serviceMonths(f, 6, LocalDate.of(2026, 10, 31));

    // ⚠️ ЗАМРАЗЕНО: потвърденият октомврийски фиш не следва днешния стаж — нито при ново
    // генериране.
    Result again =
        BgPayrollRealPath.d1(
            r10.declarationId, fresh(em -> em.find(BgPaySlip.class, octSlip)), new StringBuilder());
    pin(
        "5",
        again.rec.getT164NoContributionDays(),
        "10 наново: пак т.16.4 — присъдата е замразена");
    pin("0", again.rec.getT162SickDays(), "10 наново: т.16.2 остава 0");

    Long nov = BgPayrollRealPath.period(f, NOV_FROM, NOV_TO);
    Long novSlip = BgPayrollRealPath.slip(f, nov, "NOV");
    BgPayrollRealPath.timesheet(f, NOV_FROM, NOV_TO, SPAN_FROM, SPAN_TO);
    Result r11 = BgPayrollRealPath.run(f, novSlip, nov);

    // Правото се определя КЪМ НАЧАЛОТО на неработоспособността (26.10, стаж 5) и е замразено на
    // листа при октомврийското пресмятане. Ноемврийският фиш ЧЕТЕ присъдата — днешните 6 месеца не
    // я мърдат, и лист, започнал без право, не дава т.16.2 = 5.
    assertEquals(5, r11.slip.getBgSickDaysNoBenefit(), "11: присъдата от началото на листа");
    pin("5", r11.rec.getT164NoContributionDays(), "11: т.16.4 = 5 — правото е към началото");
    pin("0", r11.rec.getT162SickDays(), "11: т.16.2 = 0");
  }

  // ═══ (г) КОРЕКЦИОННО КОПИЕ ═════════════════════════════════════════════════

  @Test
  @DisplayName("(г) коригиращо копие — т.16.4 от замразената стойност, не от днешния стаж")
  void correctionCopyKeepsTheFrozenVerdict() throws Exception {
    BgPayrollRealPath.seed();
    Fixture f = BgPayrollRealPath.fixture("Td", 3);
    LocalDate from = LocalDate.of(2026, 10, 12);
    LocalDate to = LocalDate.of(2026, 10, 16);
    BgPayrollRealPath.sickSheet(f, "sick_er", from, to, "Е2026TEST0104");
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.timesheet(f, OCT_FROM, OCT_TO, from, to);
    Result regular = BgPayrollRealPath.run(f, slipId, oct);
    pin("5", regular.rec.getT164NoContributionDays(), "редовната: т.16.4");
    // ⚠️ НАП Е ПРИЕЛА РЕДОВНАТА (случай б) — иначе коригираща няма: корекцията би била
    // действащият фиш на редовната, а не „К“.
    BgPayrollRealPath.accepted(f, oct);

    // Днес лицето има 120 месеца — прочетено наново, правото би СЪЩЕСТВУВАЛО.
    BgPayrollRealPath.serviceMonths(f, 120, LocalDate.of(2026, 10, 31));
    BgPayrollRealPath.periodStatus(oct, PeriodRepository.STATUS_CLOSED);
    JPA.runInTransaction(
        () -> {
          try {
            Beans.get(BgPaySlipService.class).correct(JPA.em().find(BgPaySlip.class, slipId));
          } catch (Exception e) {
            throw new IllegalStateException(e);
          }
        });
    List<Long> copies =
        fresh(
            em ->
                em.createQuery(
                        "SELECT s.id FROM BgPaySlip s WHERE s.bgCorrectionOf.id = :id", Long.class)
                    .setParameter("id", slipId)
                    .getResultList());
    assertEquals(1, copies.size(), "едно коригиращо копие");
    Long copyId = copies.get(0);
    assertEquals(
        5,
        fresh(em -> em.find(BgPaySlip.class, copyId)).getBgSickDaysNoBenefit(),
        "копието наследява замразеното от оригинала");

    ActionResponse recalc = BgPayrollRealPath.press("recalculate", copyId);
    ActionResponse confirm = BgPayrollRealPath.press("confirm", copyId);
    BgPaySlip copy = fresh(em -> em.find(BgPaySlip.class, copyId));
    assertEquals(
        BgPaySlipResolver.CONFIRMED,
        copy.getStatusSelect(),
        "копието е потвърдено: " + recalc.getData() + " · " + confirm.getData());
    Long correcting =
        BgPayrollRealPath.declaration(
            f, oct, BgD1DeclarationKind.CORRECTING, regular.declarationId);
    Result r = BgPayrollRealPath.d1(correcting, copy, new StringBuilder());

    assertEquals(5, copy.getBgSickDaysNoBenefit(), "копието след „Преизчисли“: пак 5");
    pin("0", r.rec.getT162SickDays(), "коригиращата: т.16.2");
    pin("5", r.rec.getT164NoContributionDays(), "коригиращата: т.16.4 — от замразеното");
    pin("17", r.rec.getT161WorkedDays(), "коригиращата: т.16.1");
  }

  // ═══ (д) ЧЕРНОВА, ПРЕСМЕТНАТА ПРЕДИ ДЕПЛОЯ ═════════════════════════════════

  @Test
  @DisplayName("(д) чернова отпреди полето — остарял отпечатък, „Потвърди“ отказва шумно")
  void draftCalculatedBeforeTheFieldIsRefusedLoudly() throws Exception {
    BgPayrollRealPath.seed();
    Fixture f = BgPayrollRealPath.fixture("Te", 3);
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = preDeployDraft(f, oct, "Е2026TEST0105");

    // ⚠️ ОТКАЗЪТ Е ПОСЛЕДНОТО ДЕЙСТВИЕ ТУК: rollback-ът чисти контекста, а следващо натискане
    // зарежда Partner наново — @Formula за PostgreSQL, която HSQLDB не изпълнява (виж
    // BgSickNoteArrivalAtomicityTest). „Преизчисли“ върху същата чернова — в следващия тест.
    ActionResponse refused = BgPayrollRealPath.press("confirm", slipId);
    String text = String.valueOf(refused.getData());
    System.out.println("(д) отказът: " + text);
    assertEquals(
        BgPaySlipResolver.DRAFT,
        fresh(em -> em.find(BgPaySlip.class, slipId)).getStatusSelect(),
        "НЕ е потвърдена тихо: " + text);
    assertTrue(
        text.contains("без право на обезщетение") && text.contains("Преизчисли"),
        "отказът назовава полето и действието: " + text);
  }

  @Test
  @DisplayName("(д) чернова отпреди полето — „Преизчисли“ я оправя: т.16.4, после потвърждаване")
  void draftCalculatedBeforeTheFieldIsFixedByRecalculate() throws Exception {
    BgPayrollRealPath.seed();
    Fixture f = BgPayrollRealPath.fixture("Tf", 3);
    Long oct = BgPayrollRealPath.period(f, OCT_FROM, OCT_TO);
    Long slipId = preDeployDraft(f, oct, "Е2026TEST0106");

    Result r = BgPayrollRealPath.run(f, slipId, oct);
    assertEquals(5, r.slip.getBgSickDaysNoBenefit(), "след „Преизчисли“: полето е попълнено");
    pin("5", r.rec.getT164NoContributionDays(), "след „Преизчисли“: т.16.4");
    pin("0", r.rec.getT162SickDays(), "след „Преизчисли“: т.16.2");
  }

  /**
   * Чернова, каквато стои в базата отпреди деплоя: пресметната, но колоната е празна, а отпечатъкът
   * е без реда на новото поле.
   */
  private static Long preDeployDraft(Fixture f, Long oct, String sheetNo) {
    LocalDate from = LocalDate.of(2026, 10, 12);
    LocalDate to = LocalDate.of(2026, 10, 16);
    BgPayrollRealPath.sickSheet(f, "sick_er", from, to, sheetNo);
    Long slipId = BgPayrollRealPath.slip(f, oct, "OCT");
    BgPayrollRealPath.timesheet(f, OCT_FROM, OCT_TO, from, to);
    BgPayrollRealPath.press("recalculate", slipId);
    JPA.runInTransaction(
        () -> {
          BgPaySlip s = JPA.em().find(BgPaySlip.class, slipId);
          String stored = s.getBgCalcFingerprint();
          assertTrue(stored.contains("I|bgSickDaysNoBenefit|5\n"), "новият отпечатък носи реда");
          s.setBgCalcFingerprint(stored.replace("I|bgSickDaysNoBenefit|5\n", ""));
          s.setBgSickDaysNoBenefit(null);
        });
    return slipId;
  }
}
