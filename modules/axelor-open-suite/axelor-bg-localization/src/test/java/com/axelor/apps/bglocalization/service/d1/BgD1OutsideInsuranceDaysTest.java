package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО, ПРОЧЕТЕНИ ОТ ЗАЯВКИТЕ — изваждаемото на т. 16.1.
 *
 * <p><b>ПРАВИЛОТО.</b> Заявка за отсъствие, чийто вид не се зачита за осигурителен стаж и няма кофа
 * в т. 16, НЕ пълни точка, а се ВАДИ от остатъка на т. 16.1. Основанието е двойно: указанието иска
 * в т. 16.1 „дните с <b>осигурителни вноски</b>“, а през тези дни възнаграждение не се дължи (КТ
 * чл. 128 т. 2), тоест няма осигурителен доход; и <b>КСО чл. 10 ал. 2</b> — „осигуряването СЕ
 * ПРЕКЪСВА през периодите, които не се зачитат за осигурителен стаж“.
 *
 * <p><b>⚠️ И ТОЧНО ТЕЗИ ДАТИ ПЪТУВАТ КЪМ Т. 14/15.</b> {@link BgD1InsuranceSegments} строи
 * прекъсванията по СЪЩАТА колона. Инак т. 15 би обявила „последен ден в осигуряване 4.12“, а т.
 * 16.1 би броила и 7–11.12 като дни в осигуряване — две точки на един запис, които си противоречат.
 * Свидетелят {@link #theSameRequestFeedsBothPoint14And16} мери, че казват едно.
 *
 * <p><b>⚠️ ЕДИН ПРЕДИКАТ С ЗАПЛАТАТА.</b> {@code BgD1DayBuckets#outsideInsurance} се чете и от
 * {@code BgPayrollInputBuilder#leaveDaysOf}. Множеството, което заплатата карвира, и множеството,
 * което т. 16.1 вади, СЪВПАДАТ по конструкция.
 */
class BgD1OutsideInsuranceDaysTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  // ═══ ПЕТТЕ ВИДА ══════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Самоотлъчката се ВАДИ от т. 16.1 — и НЕ влиза в нито една кофа")
  void awolIsSubtractedAndNeverBucketed() {
    BgD1DaySource.Outcome out =
        read(row("2026-12-07", "2026-12-11", "5", null, "Самоотлъчка", Boolean.FALSE));

    assertEquals(
        5,
        out.outsideInsuranceDays,
        "⚠️ Петте дни излизат от остатъка на т. 16.1. Останали вътре в него, те биха накарали"
            + " декларацията да обяви дни в осигуряване за човек, който през тях е бил в"
            + " самоотлъчка (КТ чл. 190 ал. 1 т. 2).");
    assertTrue(
        out.entries.isEmpty(),
        "⚠️ И НЕ СТАВА ШЕСТА КОФА: вписани като кофа, същите дни щяха да влязат в сбора по заб. 1 и"
            + " да се върнат обратно в „дни в осигуряване — общо“. Намерено: "
            + out.entries);
    assertTrue(out.problems.isEmpty(), "празната кофа тук е СТОЙНОСТ, не проблем: " + out.problems);
  }

  @Test
  @DisplayName("⚠️ Неплатеният НАД 30 дни се вади; неплатеният ДО 30 дни пълни т. 16.5")
  void thethirtyDayThresholdSplitsTheTwoPaths() {
    BgD1DaySource.Outcome out =
        read(
            row("2026-12-07", "2026-12-09", "3", "16.5", "Неплатен до 30 дни", Boolean.TRUE),
            row("2026-12-10", "2026-12-11", "2", null, "Неплатен над 30 дни", Boolean.FALSE));

    assertEquals(
        2,
        out.outsideInsuranceDays,
        "⚠️ КСО чл. 9 ал. 2 т. 3 зачита неплатения отпуск ДО 30 работни дни през календарната"
            + " година; над тях — не, и тогава чл. 10 ал. 2 прекъсва осигуряването.");
    assertEquals(1, out.entries.size(), "и трите дни ДО 30 си остават в т. 16.5");
    assertEquals(
        3,
        BgD1DayBuckets.sum(out.entries).get(BgD1DayBuckets.B_16_5).intValue(),
        "кофата 16.5 не се пипа — тя Е „дни в неплатен отпуск, ЗАЧЕТЕНИ за осигурителен стаж“");
  }

  @Test
  @DisplayName("⚠️ Непредставеният болничен се вади; ИСТИНСКИЯТ болничен пълни т. 16.2")
  void theinsuranceFlagSeparatesTheTwoSickCases() {
    BgD1DaySource.Outcome out =
        read(
            row("2026-12-07", "2026-12-09", "3", "16.2", "Болнични ДОО", Boolean.TRUE),
            row("2026-12-10", "2026-12-11", "2", "", "Непредставен болничен", Boolean.FALSE));

    assertEquals(
        2,
        out.outsideInsuranceDays,
        "⚠️ Разграничителят е countsAsInsuranceService, а НЕ категорията: и двата реда са"
            + " „болнични“ в речника. Празният низ е същото като null — липсваща кофа, не кофа с"
            + " име „“.");
    assertEquals(
        3,
        BgD1DayBuckets.sum(out.entries).get(BgD1DayBuckets.B_16_2).intValue(),
        "истинският болничен се зачита за стаж (КСО чл. 9 ал. 2 т. 2) и си има точка");
  }

  @Test
  @DisplayName("⚠️ Една и съща заявка храни И т. 14/15, И изваждаемото на т. 16.1")
  void theSameRequestFeedsBothPoint14And16() {
    BgD1DaySource.Outcome out =
        read(row("2026-12-07", "2026-12-11", "5", null, "Самоотлъчка", Boolean.FALSE));

    assertEquals(
        1,
        out.interruptions.size(),
        "прекъсването по КСО чл. 10 ал. 2 си остава — то е било там и преди клона");
    assertEquals(5, out.outsideInsuranceDays, "и СЪЩИТЕ дни вече излизат от т. 16.1");
    assertEquals(LocalDate.of(2026, 12, 7), out.interruptions.get(0)[0], "същите дати");
    assertEquals(LocalDate.of(2026, 12, 11), out.interruptions.get(0)[1]);
  }

  // ═══ FAIL-LOUD · ПРОТИВОРЕЧИВИЯТ РЕЧНИК ══════════════════════════════════

  @Test
  @DisplayName("⚠️ Кофа + „не се зачита за стаж“ → НАХОДКА, и денят НЕ се вади два пъти")
  void abucketedTypeThatInterruptsInsuranceIsReportedNotSubtracted() {
    BgD1DaySource.Outcome out =
        read(row("2026-12-07", "2026-12-11", "5", "16.5", "Противоречив вид", Boolean.FALSE));

    assertEquals(
        0,
        out.outsideInsuranceDays,
        "⚠️ НЕ СЕ ВАДИ: денят вече излиза от т. 16.1 през кофата си 16.5. Изваден и тук, той щеше да"
            + " излезе ДВА пъти и т. 16.1 щеше да обяви по-малко стаж, отколкото лицето има.");
    assertEquals(
        5,
        BgD1DayBuckets.sum(out.entries).get(BgD1DayBuckets.B_16_5).intValue(),
        "точката, която речникът изрично сочи, печели пред признака, който ѝ противоречи");
    assertTrue(
        out.problems.stream().anyMatch(p -> p.contains("ПРОТИВОРЕЧИ")),
        "⚠️ НО СЕ ОБЯВЯВА. Тук находка, а НЕ отказ — заварената асиметрия със сглобителя: там"
            + " противоречието спира един фиш преди да е платен, тук отказ би спрял декларация за"
            + " десетки лица. Проблеми: "
            + out.problems);
  }

  @Test
  @DisplayName("⚠️ Празна кофа + НЕПОПЪЛНЕН признак → находка; null НЕ е „не се зачита“")
  void ablankInsuranceFlagIsReportedNotTreatedAsFalse() {
    BgD1DaySource.Outcome out =
        read(row("2026-12-07", "2026-12-11", "5", null, "Нов вид от екрана", null));

    assertEquals(
        0,
        out.outsideInsuranceDays,
        "⚠️ Boolean.FALSE.equals(null) е false — тоест непопълнената отметка НЕ прави вида „извън"
            + " осигуряването“. Прочетена като такава, тя щеше да вади дни по мълчаливо допускане.");
    assertTrue(out.entries.isEmpty(), "и няма къде да ги брои — кофата е празна");
    assertTrue(
        out.problems.stream().anyMatch(p -> p.contains("непопълнен")),
        "и празнотата се НАЗОВАВА поименно: " + out.problems);
  }

  @Test
  @DisplayName("Несъпоставеният вид си остава своя проблем — не се слива с противоречието")
  void anunmappedTypeKeepsItsOwnProblem() {
    BgD1DaySource.Outcome out = read(row("2026-12-07", "2026-12-11", "5", null, null, null));

    assertEquals(0, out.outsideInsuranceDays, "без съпоставяне не се знае нищо за деня");
    assertTrue(
        out.problems.stream().anyMatch(p -> p.contains("не е съпоставена")),
        "„не се знае в коя кофа“ и „речникът си противоречи“ сочат РАЗЛИЧНИ поправки: "
            + out.problems);
  }

  // ═══ ПРОВЕРКИТЕ ЗА ДНИ ВЕЧЕ ВАЖАТ И ЗА ПЕТТЕ ═════════════════════════════

  @Test
  @DisplayName("⚠️ ПРОМЯНА В ПОВЕДЕНИЕТО · часова заявка за самоотлъчка вече дава НАХОДКА")
  void thehourCheckNowAppliesToTheUnbucketedFive() {
    BgD1DaySource.Outcome out =
        read(hours("2026-12-07", "2026-12-11", "8", null, "Самоотлъчка", Boolean.FALSE));

    assertEquals(
        0,
        out.outsideInsuranceDays,
        "⚠️ часове и дни не се събират — т. 16 брои РАБОТНИ ДНИ „в две позиции“");
    assertTrue(
        out.problems.stream().anyMatch(p -> p.contains("ЧАСОВЕ")),
        "⚠️ Петте вида минават през тази проверка, за да не мине сгрешената заявка мълчаливо:"
            + " тя получава същата находка като сгрешения платен отпуск. Проблеми: "
            + out.problems);
    assertTrue(
        out.interruptions.isEmpty(),
        "⚠️ И ЧАСОВАТА ЗАЯВКА ПАК НЕ ПРЕКЪСВА ОСИГУРЯВАНЕТО — отсъствие за част от деня не прави"
            + " деня неосигурен. Това изключение е заварено и не се мести.");
  }

  @Test
  @DisplayName("⚠️ Заявка, пресичаща границата на периода, също вече се обявява")
  void theperiodBoundaryCheckNowAppliesToo() {
    BgD1DaySource.Outcome out =
        read(row("2026-11-25", "2026-12-04", "8", null, "Неплатен над 30 дни", Boolean.FALSE));

    assertEquals(
        0, out.outsideInsuranceDays, "duration описва ЦЯЛАТА заявка, не частта в декември");
    assertTrue(
        out.problems.stream().anyMatch(p -> p.contains("пресича границата")),
        "иначе изваждаемото щеше да е ЗАВИШЕНО и т. 16.1 да падне под истинското: " + out.problems);
    assertEquals(
        1,
        out.interruptions.size(),
        "но прекъсването СЕ отчита — границите му са известни, дори когато дните не са");
  }

  @Test
  @DisplayName("⚠️ Дробен ден също — т. 16 се попълва в ЦЕЛИ дни")
  void thefractionalDayCheckNowAppliesToo() {
    BgD1DaySource.Outcome out =
        read(row("2026-12-07", "2026-12-11", "4.5", null, "Самоотлъчка", Boolean.FALSE));

    assertEquals(
        0, out.outsideInsuranceDays, "закръглянето е ПРАВИЛО, не прочит, и не се прави тук");
    assertTrue(
        out.problems.stream().anyMatch(p -> p.contains("ЦЕЛИ дни")), "обявено: " + out.problems);
  }

  // ═══ ГРАНИЦИТЕ ═══════════════════════════════════════════════════════════

  @Test
  @DisplayName("Без такива заявки изваждаемото е НУЛА — и това е стойност")
  void withoutSuchRequestsTheSubtrahendIsZero() {
    BgD1DaySource.Outcome out =
        read(row("2026-12-07", "2026-12-11", "5", "16.1", "Платен годишен", Boolean.TRUE));

    assertEquals(0, out.outsideInsuranceDays, "платеният отпуск Е дни с осигурителни вноски");
    assertEquals(1, out.entries.size(), "и си пълни кофата 16.1");
    assertTrue(out.interruptions.isEmpty(), "нито прекъсва осигуряването");
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /** Ред на {@code BgD1DaySource#leaveRows} — седем колони, заявка в ДНИ. */
  private static Object[] row(
      String from, String to, String days, String d1Code, String typeName, Boolean credited) {
    return new Object[] {
      LocalDate.parse(from).atStartOfDay(),
      LocalDate.parse(to).atTime(23, 59, 59),
      new BigDecimal(days),
      1,
      d1Code,
      typeName,
      credited
    };
  }

  /** Същото, но заявката е в ЧАСОВЕ ({@code unitSelect = 2}). */
  private static Object[] hours(
      String from, String to, String qty, String d1Code, String typeName, Boolean credited) {
    Object[] r = row(from, to, qty, d1Code, typeName, credited);
    r[3] = 2;
    return r;
  }

  private static BgD1DaySource.Outcome read(Object[]... rows) {
    BgPaySlip slip = new BgPaySlip();
    EmploymentContract contract = new EmploymentContract();
    Employee employee = new Employee();
    employee.setId(7L);
    contract.setEmployee(employee);
    slip.setEmploymentContract(contract);

    Period period = new Period();
    period.setFromDate(DEC_FROM);
    period.setToDate(DEC_TO);

    return new Source(Arrays.asList(rows)).read(slip, period);
  }

  /** Източник с подменен шев към базата — без Postgres и без Guice. */
  private static final class Source extends BgD1DaySource {

    private final List<Object[]> rows;

    private Source(List<Object[]> rows) {
      this.rows = rows;
    }

    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return rows;
    }
  }
}
