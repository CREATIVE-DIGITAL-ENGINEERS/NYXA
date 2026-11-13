package com.axelor.apps.bglocalization.service.payroll;

import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.amountOf;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.fresh;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.lineOf;
import static com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.pin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Fixture;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollRealPath.Result;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgSickLeaveController;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A5 — ПЕРО 195 ПО МЕСЕЦА НА НАСТЪПВАНЕ, по реалния път ({@link BgPayrollRealPath}: сийдът →
 * „Преизчисли“ → „Потвърди“ → Д1).
 *
 * <p><b>Основание.</b> КСО чл. 40 ал. 5 (изм. ДВ бр. 106/2023, в сила от 01.01.2024): за първите
 * два работни дни осигурителят изплаща 70 на сто от среднодневното брутно възнаграждение ЗА МЕСЕЦА,
 * В КОЙТО Е НАСТЪПИЛА неработоспособността, но не по-малко от 70 на сто от среднодневното
 * уговорено.
 *
 * <p><b>Какво пази.</b> Лист от сряда 30.09 дава на работодателя 30.09 и 01.10. Денят 01.10 се
 * плаща във фиша за октомври по базата на септември (20 работни дни), не по базата на ОКТОМВРИ
 * (22). При отказ заварено перо 195 не остава начислено до отказа, а самият отказ стои веднъж.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgSickPayOnsetMonthTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static final LocalDate SEP_FROM = LocalDate.of(2026, 9, 1);
  private static final LocalDate SEP_TO = LocalDate.of(2026, 9, 30);
  private static final LocalDate OCT_FROM = LocalDate.of(2026, 10, 1);
  private static final LocalDate OCT_TO = LocalDate.of(2026, 10, 31);

  /** Сряда — първият ден на осигурителя е в септември, вторият в октомври. */
  private static final LocalDate SICK_FROM = LocalDate.of(2026, 9, 30);

  private static final LocalDate SICK_TO = LocalDate.of(2026, 10, 2);

  private static final class Scenario {
    Fixture f;
    Long sheet;
    Long sep;
    Long sepSlip;
    Long oct;
    Long octSlip;
  }

  /** Лицето, листът 30.09–02.10, двата периода, двата фиша и отчетите — без роля. */
  private static Scenario scenario(String letter) throws Exception {
    return scenario(letter, SICK_TO);
  }

  /**
   * @param octAbsentTo до кой ден на октомври отчетът е без часове — продължението го удължава
   */
  private static Scenario scenario(String letter, LocalDate octAbsentTo) throws Exception {
    BgPayrollRealPath.seed();
    warmUpRoles();
    Scenario s = new Scenario();
    // Стажът е към 31.08 — преди листа: правото се определя към 30.09.
    s.f = BgPayrollRealPath.fixture(letter, 24, LocalDate.of(2026, 8, 31));
    s.sheet = persisted(s.f, "sick_er", SICK_FROM, SICK_TO, "Е2026ONSET" + letter, null);
    s.sep = BgPayrollRealPath.period(s.f, SEP_FROM, SEP_TO);
    s.sepSlip = BgPayrollRealPath.slip(s.f, s.sep, "SEP");
    BgPayrollRealPath.timesheet(s.f, SEP_FROM, SEP_TO, SICK_FROM, SEP_TO);
    s.oct = BgPayrollRealPath.period(s.f, OCT_FROM, OCT_TO);
    s.octSlip = BgPayrollRealPath.slip(s.f, s.oct, "OCT");
    BgPayrollRealPath.timesheet(s.f, OCT_FROM, OCT_TO, OCT_FROM, octAbsentTo);
    return s;
  }

  // ═══ (а) ДЕНЯТ 01.10 ПО БАЗАТА НА СЕПТЕМВРИ ═══════════════════════════════

  @Test
  @DisplayName("(а) лист 30.09–02.10: 01.10 във фиша за октомври — по базата на септември (20 дни)")
  void theOctoberDayIsPaidOnTheSeptemberBase() throws Exception {
    Scenario s = scenario("a");
    assertEquals(20, BgPayrollRealPath.workingDays(SEP_FROM, SEP_TO), "септември 2026: 20 дни");
    assertEquals(22, BgPayrollRealPath.workingDays(OCT_FROM, OCT_TO), "октомври 2026: 22 дни");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    Result sep = BgPayrollRealPath.run(s.f, s.sepSlip, s.sep);
    assertEquals(1, sep.slip.getBgSickDaysEmployer().intValue(), "30.09: " + sep.trace);
    BgPaySlipLine sep195 = lineOf(sep.slip, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY);
    assertNotNull(sep195, "перо 195 за 30.09: " + BgPayrollRealPath.lines(sep.slip));
    // Постоянното месечно възнаграждение на септември, прочетено обратно от реда: ставката е M /
    // 20.
    BigDecimal septemberMonthly = sep195.getBgRate().multiply(new BigDecimal("20"));

    Result oct = BgPayrollRealPath.run(s.f, s.octSlip, s.oct);
    assertEquals(1, oct.slip.getBgSickDaysEmployer().intValue(), "01.10: " + oct.trace);
    assertEquals(1, oct.slip.getBgSickDaysDoo().intValue(), "02.10 — ДОО");
    BgPaySlipLine oct195 = lineOf(oct.slip, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY);
    assertNotNull(oct195, "перо 195 за 01.10: " + BgPayrollRealPath.lines(oct.slip));

    BigDecimal onSeptember =
        septemberMonthly
            .multiply(new BigDecimal("70"))
            .divide(new BigDecimal("2000"), 2, RoundingMode.HALF_UP);
    pin(onSeptember.toPlainString(), oct195.getBgAmount(), "195 = M(09) / 20 × 70 % × 1");
    // M = 310,10 + клас 1,2 % × 31 г. (115,36) = 425,46 → 425,46 / 20 × 70 % = 14,8911 → 14,89.
    // По базата на октомври щеше да е 425,46 / 22 × 70 % = 13,54 (измерено с мутация).
    pin("14.89", oct195.getBgAmount(), "числото на свидетеля");
    pin(sep195.getBgAmount().toPlainString(), oct195.getBgAmount(), "същият ден, същата база");
    pin(sep195.getBgRate().toPlainString(), oct195.getBgRate(), "среднодневното е септемврийското");
    BigDecimal onOctober =
        septemberMonthly
            .multiply(new BigDecimal("70"))
            .divide(new BigDecimal("2200"), 2, RoundingMode.HALF_UP);
    assertTrue(
        onOctober.compareTo(oct195.getBgAmount()) != 0,
        "базата на октомври (" + onOctober + ") щеше да е грешното число");
    pin("1", oct.rec.getT16aEmployerSickDays(), "т.16.А на октомври");
  }

  // ═══ (е) КОРИГИРАН МЕСЕЦ НА НАСТЪПВАНЕ → БАЗАТА Е ОТ КОРЕКЦИЯТА ═════════

  /**
   * Базата за 01.10 е от ДЕЙСТВАЩИЯ фиш за септември ({@code BgEffectivePaySlips}) — при коригиран
   * септември от корекцията, не от оригинала. Корекцията вдига основната заплата 310,10 → 410,10;
   * копието носи пълните верни числа за септември.
   *
   * <p>⚠️ Очакваното НЕ идва от перо 195 на копието: на коригиращо копие перото и дните не се
   * смятат наново ({@code BgSickLeaveService#healthAbsenceOnCorrection}), тоест то носи числото на
   * оригинала. Идва от базата: M = 410,10 + клас 1,2 % × 31 г. (152,56) = 562,66 → 562,66 / 20 × 70
   * % = 19,6931 → 19,69. По оригинала (425,46) щеше да е 14,89 — числото на (а).
   */
  @Test
  @DisplayName("(е) коригиран септември — 01.10 по базата на КОРЕКЦИЯТА (19,69), не на оригинала")
  void aCorrectedOnsetMonthGivesTheBaseOfTheCorrection() throws Exception {
    Scenario s = scenario("e");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);
    Result sep = BgPayrollRealPath.run(s.f, s.sepSlip, s.sep);
    BgPaySlipLine sep195 = lineOf(sep.slip, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY);
    assertNotNull(sep195, "перо 195 за 30.09: " + BgPayrollRealPath.lines(sep.slip));

    // „Коригирай“ септември: основната заплата 410,10 вместо 310,10.
    BgPayrollRealPath.openToday(s.f);
    BgPayrollRealPath.periodStatus(s.sep, PeriodRepository.STATUS_CLOSED);
    ActionResponse correct = BgPayrollRealPath.press("correct", s.sepSlip);
    Long copyId =
        fresh(
            em -> {
              java.util.List<Long> found =
                  em.createQuery(
                          "SELECT s.id FROM BgPaySlip s WHERE s.bgCorrectionOf.id = :o", Long.class)
                      .setParameter("o", s.sepSlip)
                      .getResultList();
              return found.isEmpty() ? null : found.get(0);
            });
    assertNotNull(copyId, "коригиращото копие е създадено: " + correct.getData());
    JPA.runInTransaction(
        () -> {
          BgPaySlip copy = JPA.em().find(BgPaySlip.class, copyId);
          copy.setBgAutoFillBaseSalary(false);
          copy.setBgBaseSalary(new BigDecimal("410.10"));
        });
    ActionResponse recalc = BgPayrollRealPath.press("recalculate", copyId);
    ActionResponse confirm = BgPayrollRealPath.press("confirm", copyId);
    assertEquals(
        BgPaySlipResolver.CONFIRMED,
        fresh(em -> em.find(BgPaySlip.class, copyId)).getStatusSelect(),
        "копието е потвърдено: " + recalc.getData() + " / " + confirm.getData());

    Result oct = BgPayrollRealPath.run(s.f, s.octSlip, s.oct);
    BgPaySlipLine oct195 = lineOf(oct.slip, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY);
    assertNotNull(oct195, "перо 195 за 01.10: " + BgPayrollRealPath.lines(oct.slip));
    pin("19.69", oct195.getBgAmount(), "195 = M(09, корекцията) / 20 × 70 % × 1");
    assertTrue(
        oct195.getBgAmount().compareTo(sep195.getBgAmount()) != 0,
        "⚠️ по оригинала щеше да е "
            + sep195.getBgAmount()
            + " — числото, което корекцията замени");
  }

  // ═══ (б) НЯМА ПОТВЪРДЕН ФИШ ЗА МЕСЕЦА НА НАСТЪПВАНЕ ═══════════════════════

  @Test
  @DisplayName("(б) фишът за септември е чернова — шумен отказ, който го назовава; без 195")
  void noConfirmedOnsetSlipIsALoudRefusal() throws Exception {
    Scenario s = scenario("b");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);

    BgPayrollRealPath.press("recalculate", s.octSlip);
    BgPaySlip slip = fresh(em -> em.find(BgPaySlip.class, s.octSlip));
    String sepRef = fresh(em -> em.find(BgPaySlip.class, s.sepSlip)).getReference();

    assertNull(
        amountOf(slip, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY),
        "без базата на септември перо няма: " + BgPayrollRealPath.lines(slip));
    assertEquals(1, slip.getBgSickDaysEmployer().intValue(), "дните са разделени");
    String gaps = String.valueOf(slip.getBgGeneratorGaps());
    assertTrue(
        gaps.contains("09.2026") && gaps.contains("ПОТВЪРДЕНИЯТ фиш") && gaps.contains(sepRef),
        "отказът казва кой фиш липсва: " + gaps);
    assertEquals(1, occurrences(gaps, "ПОТВЪРДЕНИЯТ фиш"), "веднъж: " + gaps);
    assertTrue(
        String.valueOf(slip.getBgSickDaysRefusal())
            .startsWith(BgSickLeaveService.SICK_PAY_REFUSAL_LEAD),
        "отказът е на фиша: " + slip.getBgSickDaysRefusal());

    // ⚠️ ОТКАЗЪТ Е ПОСЛЕДНОТО ДЕЙСТВИЕ: rollback-ът чисти контекста.
    ActionResponse confirm = BgPayrollRealPath.press("confirm", s.octSlip);
    String text = String.valueOf(confirm.getData());
    assertEquals(
        BgPaySlipResolver.DRAFT,
        fresh(em -> em.find(BgPaySlip.class, s.octSlip)).getStatusSelect(),
        "НЕ е потвърден с неплатен 01.10: " + text);
    assertTrue(
        text.contains("09.2026") && text.contains("Перо 195"), "„Потвърди“ казва защо: " + text);
  }

  // ═══ (в) ОТКАЗ СЛЕД СМЕТНАТО ПЕРО — ПЕРОТО СЕ МАХА, ОТКАЗЪТ Е ЕДИН ═══════

  @Test
  @DisplayName("(в) 195 сметнато, после отказан разбив — перото се маха, отказът излиза веднъж")
  void aRefusalDropsTheStaleLineAndIsShownOnce() throws Exception {
    Scenario s = scenario("c");
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);
    BgPayrollRealPath.run(s.f, s.sepSlip, s.sep);
    BgPayrollRealPath.press("recalculate", s.octSlip);
    assertNotNull(
        amountOf(
            fresh(em -> em.find(BgPaySlip.class, s.octSlip)),
            BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY),
        "първото пресмятане ражда 195");

    // Лист с вид, който НЕ е вид на болничен лист — записан покрай repository-то (A4 го отказва).
    persisted(
        s.f, "unpaid_prod_le30", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 13), "X", null);
    BgPayrollRealPath.press("recalculate", s.octSlip);
    BgPaySlip slip = fresh(em -> em.find(BgPaySlip.class, s.octSlip));

    assertNull(
        amountOf(slip, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY),
        "при отказ перото НЕ остава от предишното пресмятане: " + BgPayrollRealPath.lines(slip));
    String refusal = slip.getBgSickDaysRefusal();
    assertNotNull(refusal, "отказаният разбив е на фиша");
    String gaps = String.valueOf(slip.getBgGeneratorGaps());
    assertEquals(1, occurrences(gaps, refusal), "отказът излиза веднъж: " + gaps);
  }

  // ═══ (г) ПРОДЪЛЖЕНИЕ ПО ВЕРИГАТА — РАБОТОДАТЕЛЯТ НЕ ПЛАЩА ОТНОВО ═════════

  @Test
  @DisplayName("(г) 30.09–02.10 + продължение 05–09.10: октомври — пак само 01.10 на осигурителя")
  void theContinuationDoesNotPayTheEmployerAgain() throws Exception {
    Scenario s = scenario("d", LocalDate.of(2026, 10, 9));
    persisted(
        s.f,
        "sick_doo",
        LocalDate.of(2026, 10, 5),
        LocalDate.of(2026, 10, 9),
        "Е2026ONSETd1",
        s.sheet);
    loginAs(s.f, BgRoleLogin.WRITE_ROLE);
    Result sep = BgPayrollRealPath.run(s.f, s.sepSlip, s.sep);

    Result oct = BgPayrollRealPath.run(s.f, s.octSlip, s.oct);

    assertEquals(1, oct.slip.getBgSickDaysEmployer().intValue(), "само 01.10: " + oct.trace);
    assertEquals(6, oct.slip.getBgSickDaysDoo().intValue(), "02.10 + 05–09.10 — ДОО");
    pin(
        amountOf(sep.slip, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY).toPlainString(),
        amountOf(oct.slip, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY),
        "един ден по базата на септември — не три");
  }

  // ═══ (д) „ОПРЕДЕЛИ ПРАВОТО НАНОВО“ — АКТИВНОТО ДРУЖЕСТВО ═════════════════

  @Test
  @DisplayName("(д) „Определи правото наново“ от друго активно дружество — отказ по активното")
  void redeterminingTheRightRequiresTheActiveCompany() throws Exception {
    Scenario s = scenario("e");
    Fixture other = BgPayrollRealPath.fixture("e2", 12);
    BgRoleLogin.logout();
    // Правата и за двете дружества, активно е ДРУГОТО — гейтът е по активното.
    BgRoleLogin.login(
        JPA.em().find(Company.class, other.companyId),
        Collections.singleton(JPA.em().find(Company.class, s.f.companyId)),
        BgRoleLogin.WRITE_ROLE);

    ActionResponse refused = redetermine(s.sheet, "опит от друго дружество");

    assertTrue(
        String.valueOf(refused.getData()).contains("You can only work on your active company"),
        String.valueOf(refused.getData()));
    assertNull(
        fresh(em -> em.find(BgSickLeave.class, s.sheet)).getBgBenefitVerdictReason(),
        "нищо не е сменено");
  }

  // ═══ ПОМОЩНИТЕ ═══════════════════════════════════════════════════════════

  /** Валидиран лист, записан с {@code persist} — покрай repository-то. */
  private static Long persisted(
      Fixture f, String code, LocalDate from, LocalDate to, String no, Long continuationOf) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgSickLeave sheet = new BgSickLeave();
          sheet.setEmploymentContract(JPA.em().find(EmploymentContract.class, f.contractId));
          sheet.setBgAbsenceType(
              JPA.em()
                  .createQuery(
                      "SELECT t FROM BgAbsenceType t WHERE t.code = :c", BgAbsenceType.class)
                  .setParameter("c", code)
                  .getSingleResult());
          sheet.setFromDate(from);
          sheet.setToDate(to);
          sheet.setBgIncapacityStartDate(from);
          sheet.setBgDocumentNumber(no);
          sheet.setBgIsContinuation(continuationOf != null);
          if (continuationOf != null) {
            sheet.setBgContinuationOf(JPA.em().find(BgSickLeave.class, continuationOf));
          }
          sheet.setStatusSelect(BgPayrollInputBuilder.SICK_LEAVE_VALIDATED);
          sheet.setBgEmployerReceivedDate(from.plusDays(3));
          JPA.em().persist(sheet);
          id[0] = sheet.getId();
        });
    return id[0];
  }

  /** „Определи правото наново“ — контролерът от Guice, без транзакция. */
  private static ActionResponse redetermine(Long sheetId, String reason) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", sheetId);
    context.put("_model", BgSickLeave.class.getName());
    context.put("bgBenefitVerdictReasonInput", reason);
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(BgSickLeave.class.getName());
    request.setData(data);
    ActionResponse response = new ActionResponse();
    Beans.get(BgSickLeaveController.class).redetermineRight(request, response);
    return response;
  }

  private static int occurrences(String text, String part) {
    int n = 0;
    for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + part.length())) {
      n++;
    }
    return n;
  }

  /**
   * Ролите от каталога — ВЕДНЪЖ на схема и ПРЕДИ данните. Виж {@code
   * BgSickLeaveContinuationChainTest#warmUpRoles}.
   */
  private static void warmUpRoles() {
    long present =
        JPA.em()
            .createQuery("SELECT COUNT(r) FROM Role r WHERE r.name IN (:w, :r)", Long.class)
            .setParameter("w", BgRoleLogin.WRITE_ROLE)
            .setParameter("r", BgRoleLogin.READ_ROLE)
            .getSingleResult();
    if (present == 2) {
      return;
    }
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          Company c = new Company();
          c.setName("Зареждане на ролите " + System.nanoTime());
          c.setCode("RW" + System.nanoTime());
          JPA.em().persist(c);
          id[0] = c.getId();
        });
    BgRoleLogin.logout();
    BgRoleLogin.login(
        JPA.em().find(Company.class, id[0]), BgRoleLogin.WRITE_ROLE, BgRoleLogin.READ_ROLE);
    BgRoleLogin.logout();
  }

  private static void loginAs(Fixture f, String role) {
    BgRoleLogin.logout();
    BgRoleLogin.login(JPA.em().find(Company.class, f.companyId), role);
  }
}
