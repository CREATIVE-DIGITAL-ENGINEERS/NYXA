package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СРОКЪТ: корекция и заличаване до 30 АПРИЛ на годината СЛЕД годината на периода (Наредба Н-13).
 *
 * <p><b>Защо един тест за двата потока.</b> Пазачът живее в общия {@code openRetro} — там, където
 * {@code correct()} и {@code voidRecord()} се събират. Тест само за единия щеше да минава зелен и
 * ако пазачът беше сложен в единия вход вместо в общия път, тоест точно грешката, която този тест
 * съществува да хване. Затова всяко твърдение долу се проверява ДВА пъти, по един за всеки поток.
 *
 * <p><b>⚠️ ДНЕШНАТА ДАТА ИДВА ПРЕЗ ШЕВ, не от системния часовник.</b> Тест, който чете {@code
 * LocalDate.now()}, е зелен днес и червен на 1 май — тоест мери календара на машината вместо кода.
 * Тук датите са фиксирани и границата се обхожда ден по ден: 30.04 минава, 01.05 не.
 */
class BgPaySlipRetroDeadlineTest {

  /** Периодът, който се поправя: март 2026. Срокът му изтича на 30.04.2027. */
  private static final LocalDate DEADLINE = LocalDate.of(2027, 4, 30);

  // ── услугата без база ───────────────────────────────────────────────────

  /**
   * ⚠️ САМО ШЕВОВЕТЕ, ДОКЪДЕТО СТИГА ПЪТЯТ. Отказът за срока пада ПРЕДИ копието да е построено и
   * преди периодът да е местен, тоест текущият отворен период, текущата чернова и перото 620 не се
   * докосват изобщо. Подменени са само онези, които минаващият случай наистина ползва — подменен
   * шев, който не се вика, учи следващия читател, че пътят минава оттам.
   */
  private static class Service extends BgPaySlipService {

    /**
     * Генераторът на начисления пипа речника и каталога с параметри; тук твърдението е за
     * ретро-механизма, не за перата. Че {@code prepare()} наистина вика шева, се проверява в {@code
     * BgPaySlipServiceTest}, а какво пише той — в {@code BgEarningsGeneratorTest}.
     */
    @Override
    protected void generateEarnings(BgPaySlip slip) {}

    LocalDate today = LocalDate.of(2026, 5, 20);

    final java.util.List<String> adjustLog = new ArrayList<>();

    @Override
    protected LocalDate today() {
      return today;
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.0000");
    }

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return null;
    }

    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      return BgContributionResult.resolved(Collections.emptyList());
    }

    @Override
    protected BgMinIncomeResult minIncome(BgPaySlip slip) {
      return BgMinIncomeResult.notApplicable("неутрално за този тест");
    }

    @Override
    protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
      return null;
    }

    @Override
    protected void save(BgPaySlip slip) {}

    @Override
    protected void saveCompensation(BgCompensation doc) {}

    @Override
    protected void savePeriod(Period period) {}

    @Override
    protected void logAdjustStart(Period period) {
      adjustLog.add("start:" + period.getName());
    }

    @Override
    protected void logAdjustEnd(Period period) {
      adjustLog.add("end:" + period.getName());
    }
  }

  // ── фикстури ────────────────────────────────────────────────────────────

  private static BgPaySlipLineType type(int number, String name, String sign) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(number == 100);
    t.setBgIsInsuranceIncome(number == 100);
    t.setBgIsPermanentCharacter(false);
    return t;
  }

  private static Period period(int year, int month) {
    Year y = new Year();
    y.setName("Заплатна " + year);
    y.setTypeSelect(2);
    Period p = new Period();
    p.setName(String.format("%02d/PY%d", month, year));
    p.setFromDate(LocalDate.of(year, month, 1));
    p.setToDate(LocalDate.of(year, month, 1).withDayOfMonth(28));
    p.setYear(y);
    p.setStatusSelect(PeriodRepository.STATUS_CLOSED);
    return p;
  }

  /** Изплатен фиш върху ЗАКЛЮЧЕН период — тоест минал е всички пазачи преди срока. */
  private static BgPaySlip paidSlip(Service s, Period p) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setReference("FISH-2026-0101");
    slip.setPeriod(p);
    slip.setBgPaySlipLineList(new ArrayList<>());
    s.addLine(
        slip, s.lineFrom(type(100, "Основно възнаграждение", "CREDIT"), new BigDecimal("2000.00")));
    s.addLine(
        slip, s.lineFrom(type(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT"), new BigDecimal("200.00")));
    s.recompute(slip);
    slip.setStatusSelect(BgPaySlipResolver.PAID);
    return slip;
  }

  /** Отваря ретро-поток по избор — един вход, два потока. */
  private static BgPaySlip open(Service s, BgPaySlip original, boolean deletion)
      throws AxelorException {
    return deletion ? s.voidRecord(original) : s.correct(original);
  }

  private static final boolean[] BOTH_FLOWS = {false, true};

  private static String flow(boolean deletion) {
    return deletion ? "заличаване" : "корекция";
  }

  // ── 1 · извън прозореца се отказва, и то по ДВАТА пътя ──────────────────

  @Test
  @DisplayName("⚠️ ИЗВЪН срока: и корекцията, и заличаването се отказват, съобщението казва срока")
  void bothFlowsAreRefusedAfterTheDeadline() {
    for (boolean deletion : BOTH_FLOWS) {
      Service s = new Service();
      s.today = LocalDate.of(2027, 5, 1); // ⚠️ ЕДИН ДЕН след срока
      Period march = period(2026, 3);
      BgPaySlip original = paidSlip(s, march);

      AxelorException e = assertThrows(AxelorException.class, () -> open(s, original, deletion));
      assertTrue(e.getMessage().contains("03/2026"), flow(deletion) + " → " + e.getMessage());
      assertTrue(e.getMessage().contains("30.04.2027"), flow(deletion) + " → " + e.getMessage());
      assertTrue(e.getMessage().contains("1.05.2027"), flow(deletion) + " → " + e.getMessage());
      assertTrue(
          e.getMessage().contains(deletion ? "заличава" : "коригира"),
          "съобщението назовава ПОТОКА: " + e.getMessage());

      // ⚠️ И ПЕРИОДЪТ НЕ Е ПИПНАТ: отказът пада преди местенето, тоест месецът не остава „под
      // корекция“ заради изтекъл срок.
      assertEquals(
          PeriodRepository.STATUS_CLOSED,
          march.getStatusSelect().intValue(),
          flow(deletion) + ": периодът остава заключен");
      assertTrue(s.adjustLog.isEmpty(), flow(deletion) + ": нищо не е вписано в историята");
    }
  }

  @Test
  @DisplayName("В срок и двата потока минават — пазачът не спира редовната поправка")
  void bothFlowsPassInsideTheWindow() throws Exception {
    for (boolean deletion : BOTH_FLOWS) {
      Service s = new Service();
      s.today = LocalDate.of(2026, 5, 20);
      Period march = period(2026, 3);

      BgPaySlip copy = open(s, paidSlip(s, march), deletion);

      assertNotNull(copy, flow(deletion));
      assertEquals(deletion, copy.getBgIsDeletion(), flow(deletion) + ": вдигнат е верният флаг");
      assertEquals(!deletion, copy.getBgIsCorrection(), flow(deletion));
      assertEquals(
          PeriodRepository.STATUS_ADJUSTING,
          march.getStatusSelect().intValue(),
          flow(deletion) + ": и периодът е отворен за поправка");
    }
  }

  @Test
  @DisplayName("⚠️ ГРАНИЦАТА ден по ден: 30.04.2027 минава, 01.05.2027 не — и по двата пътя")
  void theBoundaryIsInclusive() throws Exception {
    for (boolean deletion : BOTH_FLOWS) {
      Service onTime = new Service();
      onTime.today = DEADLINE; // последният ден
      open(onTime, paidSlip(onTime, period(2026, 3)), deletion);
      assertTrue(
          onTime.adjustLog.contains("start:03/PY2026"),
          flow(deletion) + ": 30.04 е ВКЛЮЧЕН в срока — „до 30 април“ значи и на 30 април");

      Service late = new Service();
      late.today = DEADLINE.plusDays(1);
      Period march = period(2026, 3);
      BgPaySlip original = paidSlip(late, march);
      assertThrows(AxelorException.class, () -> open(late, original, deletion));
    }
  }

  // ── 2 · срокът се мери по ПЕРИОДА, не по днешния ден ────────────────────

  @Test
  @DisplayName(
      "⚠️ Срокът зависи от годината на ПЕРИОДА, а не от днешната — декември е важният случай")
  void theDeadlineFollowsThePeriodYear() {
    assertEquals(LocalDate.of(2027, 4, 30), BgPaySlipResolver.retroDeadline(period(2026, 3)));
    // ⚠️ Декемврийският фиш се издава през януари. Мерен по ДНЕШНАТА година, срокът му щеше да е с
    // цяла година по-дълъг — същият капан, който BgPaySlipManagementRepository вече избягва при
    // номера на фиша.
    assertEquals(LocalDate.of(2027, 4, 30), BgPaySlipResolver.retroDeadline(period(2026, 12)));
    assertEquals(LocalDate.of(2026, 4, 30), BgPaySlipResolver.retroDeadline(period(2025, 12)));
    assertNull(BgPaySlipResolver.retroDeadline(null));
    assertNull(
        BgPaySlipResolver.retroDeadline(new Period()), "без начална дата срокът не се определя");
  }

  @Test
  @DisplayName("Период БЕЗ начална дата се отказва с назована причина, а не се пуска мълчаливо")
  void aPeriodWithoutDatesIsRefusedWithAReason() {
    for (boolean deletion : BOTH_FLOWS) {
      Period nameless = new Period();
      nameless.setName("03/PY2026");
      nameless.setStatusSelect(PeriodRepository.STATUS_CLOSED);
      String refusal =
          BgPaySlipResolver.refuseRetroDeadline(nameless, LocalDate.of(2026, 5, 20), deletion);
      assertNotNull(refusal, flow(deletion));
      assertTrue(refusal.contains("няма начална дата"), refusal);
      assertTrue(refusal.contains("30 април"), refusal);
    }
  }

  @Test
  @DisplayName("Правилото поотделно: преди, на, и след срока — за двата потока")
  void theRuleItself() {
    Period march = period(2026, 3);
    for (boolean deletion : BOTH_FLOWS) {
      assertNull(
          BgPaySlipResolver.refuseRetroDeadline(march, LocalDate.of(2026, 4, 1), deletion),
          "същата година");
      assertNull(
          BgPaySlipResolver.refuseRetroDeadline(march, DEADLINE, deletion), "последният ден");
      assertNotNull(
          BgPaySlipResolver.refuseRetroDeadline(march, DEADLINE.plusDays(1), deletion),
          "един ден по-късно");
      assertNotNull(
          BgPaySlipResolver.refuseRetroDeadline(march, LocalDate.of(2030, 1, 1), deletion),
          "години по-късно");
      assertNull(BgPaySlipResolver.refuseRetroDeadline(null, DEADLINE, deletion));
      assertNull(BgPaySlipResolver.refuseRetroDeadline(march, null, deletion), "без днешна дата");
    }
  }

  // ── 3 · пазачът е ЕДИН, в общия път ────────────────────────────────────

  @Test
  @DisplayName("⚠️ Пазачът е ЕДИН, в общия openRetro — не по един на вход")
  void theGuardLivesInTheSharedPath() throws Exception {
    // ⚠️ Тестът е структурен нарочно. Поведенческият тест отгоре минава зелен и когато срокът е
    // проверен ДВА пъти — по веднъж в correct() и voidRecord(), — а две копия на едно правило са
    // свободни да се разминат: първото ще каже „до 30.04“, второто ще пусне 15.05, и разминаването
    // ще се види само по единия път.
    java.nio.file.Path service =
        java.nio.file.Paths.get(
            "src/main/java/com/axelor/apps/bglocalization/service/BgPaySlipService.java");
    String src =
        new String(
            java.nio.file.Files.readAllBytes(service), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(
        1,
        src.split("refuseRetroDeadline", -1).length - 1,
        "срокът се проверява на ПОВЕЧЕ от едно място в услугата — сведете го до общия openRetro");

    // и correct()/voidRecord() наистина минават през общия път, а не се дублират
    assertTrue(
        src.contains("return openRetro(original, false);")
            && src.contains("return openRetro(original, true);"),
        "correct() и voidRecord() трябва да са тънки входове към общия openRetro");
  }

  @Test
  @DisplayName("⚠️ Изключенията от срока са ЗАПИСАНИ като непостроени, а не забравени")
  void theExemptionsAreRecordedAsMissing() throws Exception {
    java.nio.file.Path resolver =
        java.nio.file.Paths.get(
            "src/main/java/com/axelor/apps/bglocalization/service/BgPaySlipResolver.java");
    String src =
        new String(
            java.nio.file.Files.readAllBytes(resolver), java.nio.charset.StandardCharsets.UTF_8);
    int at = src.indexOf("String refuseRetroDeadline");
    assertTrue(at > 0, "refuseRetroDeadline изчезна от резолвера");
    int close = src.lastIndexOf("*/", at);
    int open = src.lastIndexOf("/**", close);
    String javadoc = src.substring(open, close);
    assertTrue(
        javadoc.contains("НАП"),
        "⚠️ Наредба Н-13 допуска корекция и след 30.04 по разпореждане на НАП или на съдебен орган."
            + " Отказът тук е ПЪЛЕН и това е решение, не пропуск — липсата трябва да е записана до"
            + " функцията, инак следващият читател ще прочете половин правило като цяло.");
  }
}
