package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Производственият календар: контролните числа за 2026, двата капана на 2027, и разликата между
 * „няма празници“ и „не знам“.
 *
 * <p><b>Празниците се четат от самия seed</b>, а не се преписват в кода: тестът, който пази
 * данните, не бива да носи собствено копие от тях — иначе пази копието си, а не файла.
 */
class BgHolidayCalendarTest {

  private static final Path SEED =
      Paths.get("src/main/resources/apps/init-data/en/bg_eventsPlanningLine.csv");

  // ── четене на seed-а ────────────────────────────────────────────────────

  private static List<String[]> seedRows() throws IOException {
    List<String[]> out = new ArrayList<>();
    for (String line : Files.readAllLines(SEED, StandardCharsets.UTF_8)) {
      if (line.isEmpty() || line.startsWith("importId")) {
        continue;
      }
      out.add(line.split(";", -1));
    }
    return out;
  }

  private static Set<LocalDate> holidaysOf(int year) throws IOException {
    Set<LocalDate> days = new HashSet<>();
    for (String[] r : seedRows()) {
      if (Integer.parseInt(r[1]) == year) {
        days.add(LocalDate.parse(r[2]));
      }
    }
    return days;
  }

  /** Календарът от seed-а, без база — същият, който услугата ползва в производство. */
  private static BgWorkingDayService calendar() throws IOException {
    Set<LocalDate> all = new HashSet<>();
    Set<Integer> years = new TreeSet<>();
    for (String[] r : seedRows()) {
      all.add(LocalDate.parse(r[2]));
      years.add(Integer.parseInt(r[1]));
    }
    return new BgWorkingDayService() {
      @Override
      public Set<Integer> coveredYears() {
        return years;
      }

      @Override
      protected Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
        Set<LocalDate> in = new HashSet<>(all);
        in.removeIf(d -> d.isBefore(from) || d.isAfter(to));
        return in;
      }
    };
  }

  private static int workingDays(LocalDate from, LocalDate to, Set<LocalDate> holidays) {
    return BgCompensationResolver.workingDaysIn(
        from, (int) (to.toEpochDay() - from.toEpochDay()) + 1, holidays);
  }

  // ── контролният тест срещу 2026 — пише се ПРЕДИ да се пипа 2027 ─────────

  @Test
  @DisplayName("КОНТРОЛЕН · 2026 дава 20 работни дни през април и 19 през декември")
  void the2026CalendarStillGivesTheNumbersItAlwaysGave() throws Exception {
    Set<LocalDate> h = holidaysOf(2026);

    // 22 делника, минус Разпети петък 10.04 и Велики понеделник 13.04. Великден 12.04 е неделя.
    assertEquals(20, workingDays(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 4, 30), h));
    // 22 делника, минус 24.12, 25.12 и 28.12 — последният е изместване, защото 26.12.2026 е събота.
    assertEquals(19, workingDays(LocalDate.of(2026, 12, 1), LocalDate.of(2026, 12, 30), h));

    // ⚠️ ЧЕТИРИНАЙСЕТ, НЕ ТРИНАЙСЕТ. Велика събота липсваше в заредената 2026 г. — тя Е официален
    // празник по последната точка на чл. 154 ал. 1 („Велики петък, Велика събота и Великден…“).
    // Числата по-горе НЕ мръднаха от добавянето ѝ: Велика събота винаги пада в събота и не влиза в
    // броенето понеделник–петък. Точно затова липсата ѝ можеше да стои дълго незабелязана.
    assertEquals(
        14, h.size(), "конвенцията: по един ред на неприсъствен ден, Велика събота включена");
    assertTrue(h.contains(LocalDate.of(2026, 4, 11)), "Велика събота 11.04.2026 липсва");
  }

  // ── двата капана на 2027 ────────────────────────────────────────────────

  @Test
  @DisplayName("2027 · КАПАН 1: изместеният Ден на труда е ВТОРНИК 04.05, не понеделник")
  void labourDayMovesPastEasterMonday() throws Exception {
    Set<LocalDate> h = holidaysOf(2027);

    // 01.05.2027 е събота (и Велика събота). Като Ден на труда се мести — изключението по ал. 2 е
    // само за Великденските празници. Първият РАБОТЕН ден след него не е 03.05: той е Велики
    // понеделник и вече е неработен. Значи 04.05.
    assertTrue(h.contains(LocalDate.of(2027, 5, 4)), "изместеният Ден на труда липсва");

    // ⚠️ ДВАТА РЕДА СЪЩЕСТВУВАТ ЕДНОВРЕМЕННО И НЕ СИ ПРОТИВОРЕЧАТ. 01.05.2027 е ред като ВЕЛИКА
    // СЪБОТА — записва се на своята дата, защото великденските празници не се местят. Същият ден е
    // и Ден на труда, който СЕ мести, и затова има втори ред на 04.05. Един празник на своята дата
    // плюс друг на изместената, не дубликат.
    assertTrue(
        h.contains(LocalDate.of(2027, 5, 1)),
        "Велика събота 01.05.2027 липсва — тя е ред на своята дата, макар да е събота");

    // ⚠️ Наивното преместване „на следващия понеделник“ би слепило Деня на труда с Велики
    // понеделник и би загубило един почивен ден. Ето числото, което го лови:
    assertEquals(
        3,
        workingDays(LocalDate.of(2027, 4, 29), LocalDate.of(2027, 5, 7), h),
        "работни са само 29.04, 05.05 и 07.05: 30.04, 03.05 и 04.05 са празници, 01–02.05 е уикенд,"
            + " а 06.05 е Гергьовден");

    for (LocalDate d :
        Arrays.asList(
            LocalDate.of(2027, 4, 30),
            LocalDate.of(2027, 5, 1),
            LocalDate.of(2027, 5, 2),
            LocalDate.of(2027, 5, 3))) {
      assertTrue(h.contains(d), "липсва великденски ден " + d);
    }

    // Четири последователни великденски дни плюс изместеният Ден на труда — пет реда, пет дати.
    List<LocalDate> streak = new ArrayList<>();
    for (LocalDate d = LocalDate.of(2027, 4, 30);
        !d.isAfter(LocalDate.of(2027, 5, 4));
        d = d.plusDays(1)) {
      if (h.contains(d)) {
        streak.add(d);
      }
    }
    assertEquals(5, streak.size(), "30.04, 01.05, 02.05, 03.05 и 04.05: " + streak);
  }

  @Test
  @DisplayName("2027 · КАПАН 2: Коледа пада през уикенда и низът е 24–28 декември")
  void christmas2027MovesToTwoWorkingDays() throws Exception {
    Set<LocalDate> h = holidaysOf(2027);

    // 24.12 петък е празник сам по себе си; 25.12 събота и 26.12 неделя се местят на ПЪРВИТЕ ДВА
    // работни дни след тях — понеделник 27.12 и вторник 28.12.
    assertTrue(h.contains(LocalDate.of(2027, 12, 24)), "24.12 липсва");
    assertTrue(h.contains(LocalDate.of(2027, 12, 27)), "изместеното Рождество липсва");
    assertTrue(h.contains(LocalDate.of(2027, 12, 28)), "изместеният втори ден липсва");
    assertFalse(h.contains(LocalDate.of(2027, 12, 25)), "25.12 е събота — не е отделен ред");
    assertFalse(h.contains(LocalDate.of(2027, 12, 26)), "26.12 е неделя — не е отделен ред");

    // и петте дни 24–28 са неприсъствени: два празника, уикенд, две измествания
    assertEquals(
        0,
        workingDays(LocalDate.of(2027, 12, 24), LocalDate.of(2027, 12, 28), h),
        "нито един работен ден между 24 и 28 декември");
    assertEquals(
        7,
        workingDays(LocalDate.of(2027, 12, 20), LocalDate.of(2027, 12, 31), h),
        "работни са 20–23 и 29–31: целият низ 24–28 отпада");
  }

  // ── ЧАСТ Б · четирите теста за покритието ───────────────────────────────

  private static BgCompensationType noticeType() {
    BgCompensationType t = new BgCompensationType();
    t.setCode("comp_kt_220_1");
    t.setName("Обезщетение за неспазено предизвестие");
    t.setLegalBasis("чл. 220 ал. 1 КТ");
    t.setBaseKind("NOTICE_DAYS");
    t.setBgDirection("EMPLOYER_TO_EMPLOYEE");
    t.setIsAmountAdjustable(true);
    t.setRequiresProof(false);
    t.setIsTaxable(true);
    t.setSocialContributionsDue(false);
    return t;
  }

  private static BgCompensationService serviceOver(BgWorkingDayService calendar) {
    return new BgCompensationService() {
      @Override
      protected BgWorkingDayService workingDayService() {
        return calendar;
      }

      @Override
      protected boolean hasAttachment(BgCompensation doc) {
        return true;
      }

      @Override
      protected void save(BgCompensation doc) {
        // без база
      }

      @Override
      protected BigDecimal paidOnEntitlement(BgCompensation doc) {
        return null;
      }
    };
  }

  private static BgCompensation notice(LocalDate start) {
    BgCompensation d = new BgCompensation();
    d.setStatusSelect(BgCompensationResolver.DRAFT);
    d.setBgCompensationType(noticeType());
    d.setBgEventDate(start.plusMonths(2));
    d.setBgBaseMonthGross(new BigDecimal("1050.00"));
    d.setBgBaseWorkingDays(21);
    d.setBgNoticeStartDate(start);
    return d;
  }

  @Test
  @DisplayName("Б-а · интервал изцяло в покрита година — потвърждаването минава")
  void aFullyCoveredIntervalConfirms() throws Exception {
    BgCompensationService s = serviceOver(calendar());
    BgCompensation d = notice(LocalDate.of(2027, 4, 1));
    s.prepare(d);
    s.recompute(d);
    s.confirm(d);
    assertEquals(BgCompensationResolver.CONFIRMED, d.getStatusSelect());
  }

  @Test
  @DisplayName("Б-б · интервал в НЕПОКРИТА година с изведено количество — блокира, с годината")
  void anUncoveredIntervalIsRefused() throws Exception {
    BgCompensationService s = serviceOver(calendar());
    BgCompensation d = notice(LocalDate.of(2028, 3, 1)); // 2028 не е заредена
    s.prepare(d);
    s.recompute(d);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(d));
    assertTrue(e.getMessage().contains("2028"), e.getMessage());
    assertTrue(
        e.getMessage().contains("bg_eventsPlanningLine.csv"),
        "не казва кой файл: " + e.getMessage());
    assertEquals(BgCompensationResolver.DRAFT, d.getStatusSelect(), "остава чернова");
  }

  @Test
  @DisplayName("Б-в · същият интервал с РЪЧНО количество — минава")
  void aManualQuantityUnblocksTheUncoveredYear() throws Exception {
    BgCompensationService s = serviceOver(calendar());
    BgCompensation d = notice(LocalDate.of(2028, 3, 1));
    s.prepare(d);
    // ТРЗ знае календара за 2028 от друг източник и надписва изведеното.
    d.setBgQuantity(d.getBgQuantity().subtract(BigDecimal.ONE));
    d.setBgFinalAmount(null);
    s.recompute(d);
    d.setNote("Работните дни за март 2028 са сверени по календара на НОИ.");

    s.confirm(d);
    assertEquals(
        BgCompensationResolver.CONFIRMED,
        d.getStatusSelect(),
        "надписаното количество е нечие решение и не се спира");
  }

  /**
   * ⚠️ ГОЛОТО ПРАВИЛО, БЕЗ ДОКУМЕНТ. Отделен тест нарочно: подава числата ПРАВО на резолвера, тоест
   * НЕ доказва нищо за производството. Стои, защото описва правилото четимо — но ако беше сам, щеше
   * да е капан №10: зелено през път, който в живото приложение не съществува. Доказателството е
   * следващият тест.
   */
  @Test
  @DisplayName("Б-в2 · правилото голо: надписано значи ПОЛОЖИТЕЛНО и различно от изведеното")
  void theRuleItselfTreatsZeroAsUnset() {
    assertNotNull(
        BgCompensationResolver.refuseOnUncoveredCalendar(22, BigDecimal.ZERO, "2028"),
        "нулата е празно поле, а не решение");
    assertNotNull(BgCompensationResolver.refuseOnUncoveredCalendar(22, null, "2028"));
    assertNotNull(
        BgCompensationResolver.refuseOnUncoveredCalendar(22, new BigDecimal("22"), "2028"),
        "числото на системата — блокира");
    assertNull(
        BgCompensationResolver.refuseOnUncoveredCalendar(22, new BigDecimal("20"), "2028"),
        "надписано на ръка — минава");
  }

  /**
   * ⚠️ СЪЩОТО, НО ПО ПЪТЯ НА ПРОИЗВОДСТВОТО. Стойността НЕ се подава на резолвера от теста: тя
   * стига до него през {@code doc.getBgQuantity()} в {@code BgCompensationService#confirm}, тоест
   * през генерирания getter, който връща ZERO за непопълнена колона. Точно оттам идва рискът и само
   * този тест го доказва.
   *
   * <p>Между полето и защитата няма подменен шев: {@code serviceOver} замества календара, базата и
   * прикачените файлове, но количеството минава непокътнато.
   */
  @Test
  @DisplayName("⚠️ Б-в3 · ИЗЧИСТЕНАТА клетка минава през getter-а и отказът пак се задейства")
  void anEmptyQuantityDoesNotCountAsAManualOne() throws Exception {
    // ТРЗ изчиства количеството и не преизчислява, тоест сумата остава от предишното броене.
    BgCompensationService s = serviceOver(calendar());
    BgCompensation d = notice(LocalDate.of(2028, 3, 1));
    s.prepare(d);
    s.recompute(d);
    d.setBgQuantity(null); // изчистена клетка във формата

    // Ето самия капан, в едно твърдение: полето е null, а четенето му дава 0,00.
    assertEquals(0, d.getBgQuantity().signum(), "getter-ът връща ZERO, не null");
    assertTrue(d.getBgFinalAmount().signum() > 0, "сумата остава — иначе щеше да спре по-рано");

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(d));
    // ⚠️ Твърдението е върху текст, който НОСИ САМО този отказ. „2028“ би могло да дойде отвсякъде,
    // а „сумата е празна“ е съседният отказ, който не бива да е този, който спира документа.
    assertTrue(
        e.getMessage().contains("bg_eventsPlanningLine.csv"),
        "спрял е ДРУГ отказ, не календарният: " + e.getMessage());
    assertTrue(e.getMessage().contains("2028"), e.getMessage());
    assertEquals(BgCompensationResolver.DRAFT, d.getStatusSelect(), "остава чернова");
  }

  @Test
  @DisplayName("Б-г · покрита година БЕЗ празници вътре — минава и БЕЗ предупреждение")
  void aCoveredIntervalWithoutHolidaysIsSilent() throws Exception {
    BgWorkingDayService calendar = calendar();
    // 01.02–02.03.2027: покрита година, а в интервала няма нито един празник (03.03 е извън него).
    BgWorkingDayService.Count count = calendar.count(LocalDate.of(2027, 2, 1), 30);

    assertTrue(count.isReliable(), "покрита година — числото е надеждно");
    assertTrue(count.getUncoveredYears().isEmpty());
    assertEquals("", count.uncoveredYearsAsText(), "няма за какво да се предупреждава");
    assertEquals(22, count.getWorkingDays(), "22 делника, нула празници в интервала");

    BgCompensationService s = serviceOver(calendar);
    BgCompensation d = notice(LocalDate.of(2027, 2, 1));
    s.prepare(d);
    s.recompute(d);
    s.confirm(d);
    assertEquals(BgCompensationResolver.CONFIRMED, d.getStatusSelect());

    // ⚠️ Ето разликата, заради която съществува цялата част Б: същият брой дни, но единият отговор
    // е знание, а другият — незнание.
    BgWorkingDayService.Count unknown = calendar.count(LocalDate.of(2028, 2, 1), 30);
    assertEquals(
        22,
        unknown.getWorkingDays(),
        "същото число като покритата година — и точно затова числото само по себе си не стига");
    assertFalse(unknown.isReliable(), "2028 не е заредена — числото не е надеждно");
    assertEquals("2028", unknown.uncoveredYearsAsText());
  }

  // ── ТРИПВАЙЕР ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("ТРИПВАЙЕР · календарът стига поне до годината на днес + 6 месеца")
  void theCalendarReachesSixMonthsAhead() throws Exception {
    int latest = 0;
    for (String[] r : seedRows()) {
      latest = Math.max(latest, Integer.parseInt(r[1]));
    }
    LocalDate horizon = LocalDate.now().plusMonths(6);

    if (latest < horizon.getYear()) {
      fail(
          "производственият календар стига до "
              + latest
              + " г., а до "
              + horizon
              + " остават по-малко от шест месеца. ЛИПСВА "
              + horizon.getYear()
              + " г.\n"
              + "  Допълнете apps/init-data/en/bg_eventsPlanningLine.csv И fr/ (плюс реда за плана в"
              + " bg_eventsPlanning.csv), обновете manifest/holidays.yml и заредете наново.\n"
              + "  ⚠️ Шестте месеца не са произволни: срокът на предизвестието стига до 3 месеца по"
              + " КТ чл. 326 ал. 2, а документът се прави СЛЕД прекратяването — интервалът навлиза в"
              + " следващата година много преди 31 декември. Този тест пада нарочно, защото модул,"
              + " който тихо надплаща обезщетения, е по-лош от билд, който пада с ясно съобщение.");
    }
    assertTrue(latest >= horizon.getYear());
  }
}
