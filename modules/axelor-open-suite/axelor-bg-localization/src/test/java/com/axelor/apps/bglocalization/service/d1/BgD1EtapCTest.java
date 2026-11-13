package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * БЛОК Ф · ЕТАП C — <b>ръчните полета, които получиха източник, и онези, които НЕ можаха</b>.
 *
 * <p>Правилото за всяко от четирите: „има ли автоматичен източник — навържи го; няма ли — добави UI
 * поле“. Тези свидетели закрепват КОЙ КОЙ Е и, което е по-важно, ЗАЩО — за три от четирите
 * източникът е в модела.
 *
 * <p><b>⚠️ И ЗАЩО ЧЕТВЪРТОТО НЕ ПОЛУЧИ ИЗТОЧНИК.</b> Т. 13 позиция 2 иска НАЧАЛОТО на отчетния
 * период при СИРВ — акт на работодателя по КТ чл. 142 ал. 2, който може да започне в който и да е
 * месец. Изведена от календарната година, цифрата е ВАЛИДНА и невярна за половината работодатели.
 * Разликата между „няма източник“ и „източникът е труден“ е точно тази: тук няма ФАКТ, от който да
 * се изведе, а не липсва сметка.
 */
class BgD1EtapCTest {

  private static final LocalDate MAY_2026 = LocalDate.of(2026, 5, 1);

  private static Period period() {
    Period p = new Period();
    p.setFromDate(MAY_2026);
    p.setToDate(MAY_2026.withDayOfMonth(31));
    return p;
  }

  private static BgInsuredType insuredType(String code) {
    BgInsuredType t = new BgInsuredType();
    t.setCode(code);
    t.setBgAppliesMod(Boolean.TRUE);
    return t;
  }

  private static BgPaySlip slip(String insuredCode) {
    Partner companyPartner = new Partner();
    companyPartner.setName("Никса ЕООД");
    companyPartner.setRegistrationCode("831919536");

    Company company = new Company();
    company.setPartner(companyPartner);

    Partner person = new Partner();
    person.setName("Петров");
    person.setFirstName("Иван");

    Employee employee = new Employee();
    employee.setContactPartner(person);
    employee.setBgPersonalIdNumber("7501014567");

    EmploymentContract contract = new EmploymentContract();
    contract.setStartDate(LocalDate.of(2020, 1, 15));
    contract.setBgInsuredTypeCode(insuredCode);
    contract.setPayCompany(company);
    contract.setEmployee(employee);

    BgPaySlip s = new BgPaySlip();
    s.setReference("FISH-2026-05-0001");
    s.setEmploymentContract(contract);
    s.setBgPaySlipLineList(new ArrayList<>());
    s.setBgWorkedDays(21);
    return s;
  }

  /** Кофите, каквито {@code BgD1DaySource} би върнал за фиш без отсъствия. */
  private static Map<String, Integer> buckets() {
    Map<String, Integer> out = new LinkedHashMap<>();
    for (String bucket : BgD1DayBuckets.BUCKETS) {
      out.put(bucket, 0);
    }
    return out;
  }

  private static BgD1Record build(
      BgPaySlip slip, BgInsuredType type, BgD1Facts facts, List<BgD1FindingDto> findings) {
    return BgD1RecordBuilder.build(slip, type, period(), facts, findings);
  }

  // ═══ т. 13 · ПОЗИЦИЯ 1 ИМА ИЗТОЧНИК, ПОЗИЦИЯ 2 — НЕ ═══

  @Test
  @DisplayName("т. 13 поз. 1 · чете се от „Отчетен период при СИРВ“ на договора")
  void sirvPeriodComesFromTheContract() {
    BgPaySlip s = slip("16");
    s.getEmploymentContract().setBgSirvPeriodMonths(3);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = build(s, insuredType("16"), BgD1Facts.none(), findings);

    assertEquals(
        "03",
        record.getT13SirvPeriodMonths(),
        "⚠️ „в две позиции“ — водещата нула Е част от стойността. Записана като число, тя се губи, а"
            + " поле 31 е Char с размер РАВЕН НА 4.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T13_MONTH_INDEX_MISSING".equals(f.getCode())),
        "⚠️ И ПОЗИЦИЯ 2 СЕ ОБЯВЯВА ВЕДНАГА, макар позиция 1 да е попълнена: частично попълнена т. 13"
            + " излиза във файла като „0300“ и НЕ е грешка — тя е непълни данни, които някой трябва"
            + " да допълни. Находки: "
            + findings);
    assertNull(record.getT13SirvMonthIndex(), "поредността се въвежда на ръка");
  }

  @Test
  @DisplayName("⚠️ т. 13 · попълва се САМО за вид осигурен 16, а не за всеки на СИРВ")
  void sirvCodeFollowsTheInsuredTypeNotTheWorkTimeMode() {
    // Лице на СИРВ, но декларирано с код 04 (допълнителен трудов договор): режимът е СИРВ,
    // кодът — не.
    BgPaySlip s = slip("04");
    s.getEmploymentContract().setBgSirvPeriodMonths(3);
    s.getEmploymentContract().setBgWorkTimeMode("2");

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = build(s, insuredType("04"), BgD1Facts.none(), findings);

    assertNull(
        record.getT13SirvPeriodMonths(),
        "⚠️ УКАЗАНИЕТО КАЗВА „когато в т. 12 е попълнен код за вид осигурен 16“ — тоест условието е"
            + " КОДЪТ, не режимът. Попълнена по bgWorkTimeMode, точката твърди нещо за кода, което не"
            + " е вярно, при това с валидна цифра.");
    assertTrue(
        findings.stream().noneMatch(f -> f.getCode().startsWith("D1_T13_")),
        "и находка НЯМА: за код 04 точката не се полага изобщо. Находки: " + findings);
  }

  @Test
  @DisplayName("т. 13 поз. 1 · отчетен период под 2 месеца ПРОТИВОРЕЧИ на самия код 16")
  void aSirvPeriodBelowTwoIsRefused() {
    BgPaySlip s = slip("16");
    s.getEmploymentContract().setBgSirvPeriodMonths(1);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record = build(s, insuredType("16"), BgD1Facts.none(), findings);

    assertNull(record.getT13SirvPeriodMonths());
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T13_SIRV_PERIOD_MISSING".equals(f.getCode())),
        "⚠️ Указанието дава на позиция 1 диапазон от 02 до 12, а долната граница НЕ е произволна:"
            + " самият код 16 значи „сумирано изчисляване за ПОВЕЧЕ ОТ ЕДИН месец“. Записано „01“,"
            + " полето противоречи на кода, с който стои на един и същи ред. Находки: "
            + findings);
  }

  @Test
  @DisplayName("⚠️ т. 13 · поредност > дължина минава ВСЯКА проверка за диапазон и е невъзможна")
  void anIndexBeyondThePeriodLengthIsCaught() {
    BgD1Record record = new BgD1Record();
    record.setT2Month("05");
    record.setT4ObligedPersonCode("831919536");
    record.setT5PersonalId("7501014567");
    record.setT12InsuredTypeCode("16");
    record.setT13SirvPeriodMonths("02");
    record.setT13SirvMonthIndex("03");

    List<BgD1FindingDto> findings = new BgD1Validator().validate(record, null);
    assertTrue(
        findings.stream().anyMatch(f -> BgD1Validator.T13_OUT_OF_RANGE.equals(f.getCode())),
        "⚠️ „02“ Е в диапазона 02–12 и „03“ Е в диапазона 01–12 — тоест всяка позиция ПООТДЕЛНО е"
            + " валидна. „Трети месец от двумесечен период“ обаче не съществува, и точно затова"
            + " проверката е ТРЕТА, а не следствие от другите две. Находки: "
            + findings);
  }

  @Test
  @DisplayName("поле 31 · двете позиции се слепват; празната става „00“, не изчезва")
  void thePositionsAreGluedIntoFieldThirtyOne() {
    com.axelor.apps.bglocalization.service.d1.adapter.BgD1FileAdapter adapter =
        new com.axelor.apps.bglocalization.service.d1.adapter.BgD1FileAdapter();

    BgD1Record record = new BgD1Record();
    record.setT13SirvPeriodMonths("03");
    assertEquals(
        "0300",
        adapter.t13(record),
        "⚠️ ПОЛЕТО Е Char =4 И ПОЗИЦИИТЕ НЯМАТ СВОЙ РАЗДЕЛИТЕЛ. Празната позиция става „00“, инак"
            + " следващата се смъква на нейно място. Изхвърлено, полето би премълчало и дължината на"
            + " периода — а тя Е известна.");

    record.setT13SirvMonthIndex("2");
    assertEquals("0302", adapter.t13(record), "едноцифрената поредност получава водеща нула");

    assertEquals(
        "0000",
        adapter.t13(new BgD1Record()),
        "⚠️ ПРАЗНА Т. 13 ИЗЛИЗА „0000“: заповедта казва за поле 31 „задължително се"
            + " попълват 4 позиции“, а приетата от НАП коригираща Д1 за вид осигурен 01 носи точно"
            + " \"0000\" — нула месеца е „без сумирано отчитане“. Поле 19 НЕ следва това.");
  }

  // ═══ т. 16 позиция 3 · НОВИЯТ НОСИТЕЛ НА ДОГОВОРА ═══

  @Test
  @DisplayName("т. 16 поз. 3 · чете се от договора и е РАЗЛИЧНО от позиция 4")
  void legalWorkTimeComesFromTheContractAndDiffersFromTheContracted() {
    BgPaySlip s = slip("01");
    // Намалено работно време за длъжността по КТ чл. 137 (7 часа), непълно по чл. 138 (4 часа).
    s.getEmploymentContract().setBgLegalHoursPerDay(new BigDecimal("7"));
    s.getEmploymentContract().setBgHoursPerDay(new BigDecimal("4"));

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        build(s, insuredType("01"), new BgD1Facts(buckets(), null, null, null), findings);

    assertEquals("7", record.getT16LegalHours(), "позиция 3 — законоустановеното за ДЛЪЖНОСТТА");
    assertEquals("4", record.getT16ContractedHours(), "позиция 4 — договореното на ЛИЦЕТО");
    assertTrue(
        findings.stream().noneMatch(f -> "D1_T16_LEGAL_WORKTIME_MISSING".equals(f.getCode())),
        "попълнено на договора → находка НЯМА. Находки: " + findings);
  }

  @Test
  @DisplayName("⚠️ т. 16 поз. 3 · празно на договора НЕ се запълва с позиция 4")
  void aMissingLegalWorkTimeIsNotCopiedFromTheContracted() {
    BgPaySlip s = slip("01");
    s.getEmploymentContract().setBgHoursPerDay(new BigDecimal("8"));

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        build(s, insuredType("01"), new BgD1Facts(buckets(), null, null, null), findings);

    assertNull(
        record.getT16LegalHours(),
        "⚠️ ЗА ЛИЦЕ НА ПЪЛЕН ЩАТ ДВЕТЕ СА 8 И КОПИРАНЕТО ИЗГЛЕЖДА ВЯРНО — точно затова не се прави."
            + " Разминават се при намалено работно време по чл. 137 КТ и при непълно по чл. 138, а"
            + " там копието дава валидна цифра, която твърди друга длъжност.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T16_LEGAL_WORKTIME_MISSING".equals(f.getCode())),
        "причината се назовава и СОЧИ ДОГОВОРА, не записа: на записа числото ще се въвежда наново при"
            + " всяко генериране, защото генерирането е ЗАМЯНА. Находки: "
            + findings);
  }

  // ═══ т. 16.7 / 16.8 · ЧАСОВЕТЕ ═══

  @Test
  @DisplayName("т. 16.8 · САМО четирите вида извънреден труд по чл. 262 ал. 1 КТ")
  void overtimeHoursComeFromTheValidatedExtraHours() {
    BgPaySlip s = slip("01");
    s.getEmploymentContract().setBgHoursPerDay(new BigDecimal("8"));

    BgD1HourSource.Hours hours = BgD1HourSource.of(new BigDecimal("168"), new BigDecimal("12"), 21);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        build(s, insuredType("01"), new BgD1Facts(buckets(), null, hours, null), findings);

    assertEquals("12", record.getT168OvertimeHours());
    assertEquals(
        "180",
        record.getT167WorkedHours(),
        "⚠️ Т. 16.7 Е СБОР: „броят на отработените часове през месеца, ВКЛЮЧИТЕЛНО броят изработени"
            + " часове извънреден труд и броят на часовете за времето на платен отпуск“. 168 + 12 ="
            + " 180; платен отпуск няма (отчетът покрива всичките 21 дни от т. 16.1).");
  }

  @Test
  @DisplayName("т. 16.7 · часовете на ПЛАТЕНИЯ ОТПУСК влизат, изведени по договореното РВ")
  void paidLeaveHoursEnterTheTotal() {
    BgPaySlip s = slip("01");
    s.getEmploymentContract().setBgHoursPerDay(new BigDecimal("8"));

    // Отчетът носи часове за 16 дни, а т. 16.1 дава 21 → пет дни платен отпуск.
    BgD1HourSource.Hours hours = BgD1HourSource.of(new BigDecimal("128"), new BigDecimal("0"), 16);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        build(s, insuredType("01"), new BgD1Facts(buckets(), null, hours, null), findings);

    assertEquals(
        "168",
        record.getT167WorkedHours(),
        "128 отработени + 0 извънреден + 5 дни × 8 часа платен отпуск = 168. ⚠️ Без третата съставка"
            + " числото щеше да е 128 — по-МАЛКО и изглеждащо точно толкова пресметнато.");
  }

  @Test
  @DisplayName("⚠️ т. 16.7 · платен отпуск БЕЗ договорено РВ → точката остава празна, не непълна")
  void anIncompleteTotalIsNotWritten() {
    BgPaySlip s = slip("01");
    // Договореното работно време НЕ е попълнено.
    BgD1HourSource.Hours hours = BgD1HourSource.of(new BigDecimal("128"), new BigDecimal("0"), 16);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        build(s, insuredType("01"), new BgD1Facts(buckets(), null, hours, null), findings);

    assertNull(
        record.getT167WorkedHours(),
        "⚠️ 128 БИ БИЛО ПРАВДОПОДОБНО И НЕВЯРНО. Указанието иска и часовете на платения отпуск, а те"
            + " се извеждат по дневното договорено работно време — липсва ли то, сборът е непълен и"
            + " точката остава празна.");
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T167_PAID_LEAVE_HOURS_UNKNOWN".equals(f.getCode())),
        "причината назовава и ДВЕТЕ числа, които се разминават. Находки: " + findings);
  }

  @Test
  @DisplayName("⚠️ т. 16.7 · без ВАЛИДИРАН отчет за време остава празна — нула НЕ се записва")
  void withoutATimesheetTheHoursStayEmpty() {
    BgPaySlip s = slip("01");
    s.getEmploymentContract().setBgHoursPerDay(new BigDecimal("8"));

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        build(
            s,
            insuredType("01"),
            new BgD1Facts(buckets(), null, BgD1HourSource.none(), null),
            findings);

    assertNull(
        record.getT167WorkedHours(),
        "⚠️ „НУЛА ОТРАБОТЕНИ ЧАСА“ Е ТВЪРДЕНИЕ, КОЕТО НАП ОТХВЪРЛЯ за работещо лице. Инсталация без"
            + " отчети за време не е инсталация без работа.");
    assertNull(record.getT168OvertimeHours());
    assertTrue(
        findings.stream().anyMatch(f -> "D1_T167_T168_HOURS_MISSING".equals(f.getCode())),
        "находка: няма нито един валидиран отчет. Находки: " + findings);
  }

  @Test
  @DisplayName("⚠️ т. 16.7 и т. 16.8 липсват ПООТДЕЛНО — двата им източника са различни")
  void theTwoHourPointsFailIndependently() {
    BgPaySlip s = slip("01");
    s.getEmploymentContract().setBgHoursPerDay(new BigDecimal("8"));

    // Отчет за време НЯМА, но допълнителни часове ИМА — инсталация, която води само второто.
    BgD1HourSource.Hours hours = BgD1HourSource.of(null, new BigDecimal("6"), 0);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        build(s, insuredType("01"), new BgD1Facts(buckets(), null, hours, null), findings);

    assertEquals(
        "6",
        record.getT168OvertimeHours(),
        "⚠️ Т. 16.8 ИМА СВОЙ ИЗТОЧНИК (ExtraHoursLine) и се попълва независимо от т. 16.7. Слети в"
            + " едно условие, двете щяха да изчезнат заедно.");
    assertNull(record.getT167WorkedHours());
    assertTrue(
        findings.stream()
            .anyMatch(
                f ->
                    "D1_T167_T168_HOURS_MISSING".equals(f.getCode())
                        && f.getMessage().contains("Т. 16.8 обаче Е попълнена")),
        "текстът РАЗЛИЧАВА двата случая: човек, който чете „часовете липсват“, не бива да търси"
            + " извънредния труд, когато той Е там. Находки: "
            + findings);
  }

  @Test
  @DisplayName("т. 16.7/16.8 · за 23-те кода с bgNoHours точките мълчат — празното е СТОЙНОСТ")
  void theTwentyThreeCodesFillNoHoursAndRaiseNoFinding() {
    BgPaySlip s = slip("12");
    BgInsuredType type = insuredType("12");
    type.setBgNoHours(Boolean.TRUE);
    // ⚠️ zeroWorkTime се вдига също: всичките 17 кода с него СА подмножество на 23-те с bgNoHours.
    type.setBgZeroWorkTime(Boolean.TRUE);

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        build(s, type, new BgD1Facts(buckets(), null, BgD1HourSource.none(), null), findings);

    assertNull(record.getT167WorkedHours());
    assertEquals(
        "0",
        record.getT16LegalHours(),
        "⚠️ НУЛАТА ТУК Е СТОЙНОСТ, не липса: заб. към т. 16 казва „лицата с вид осигурен … попълват"
            + " 0“ в позиции 3 и 4.");
    assertTrue(
        findings.stream().noneMatch(f -> f.getCode().startsWith("D1_T167")),
        "по норма точките не се попълват → находка НЯМА. Находки: " + findings);
  }

  // ═══ ОТРЯЗЪЦИТЕ · чистата аритметика ═══

  @Test
  @DisplayName("отрязъци · прекъсване, започващо ПРЕДИ месеца, не дава т. 15 в този месец")
  void aBreakStartingBeforeTheMonthYieldsNoT15() {
    BgD1InsuranceSegments.Outcome outcome =
        BgD1InsuranceSegments.of(
            MAY_2026,
            MAY_2026.withDayOfMonth(31),
            null,
            null,
            Collections.singletonList(
                new LocalDate[] {LocalDate.of(2026, 4, 20), LocalDate.of(2026, 5, 10)}));

    assertEquals(1, outcome.getSegments().size(), "месецът започва ВЪТРЕ в прекъсването");
    assertEquals(
        LocalDate.of(2026, 5, 11),
        outcome.getSegments().get(0).getFrom(),
        "⚠️ Осигуряването се ВЪЗОБНОВЯВА на 11-и — тоест това Е т. 14 позиция 1, макар договорът да е"
            + " от 2020 г. Прекъсването е започнало в АПРИЛ и там е декларирана неговата т. 15.");
    assertEquals(LocalDate.of(2026, 5, 31), outcome.getSegments().get(0).getTo());
  }

  @Test
  @DisplayName("отрязъци · прекратяване НАСРЕД прекъсване не измисля отрязък")
  void aTerminationInsideABreakYieldsNothingAfterIt() {
    BgD1InsuranceSegments.Outcome outcome =
        BgD1InsuranceSegments.of(
            MAY_2026,
            MAY_2026.withDayOfMonth(31),
            null,
            LocalDate.of(2026, 5, 20),
            Collections.singletonList(
                new LocalDate[] {LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 31)}));

    assertEquals(1, outcome.getSegments().size());
    assertEquals(LocalDate.of(2026, 5, 1), outcome.getSegments().get(0).getFrom());
    assertEquals(
        LocalDate.of(2026, 5, 14),
        outcome.getSegments().get(0).getTo(),
        "⚠️ Прекъсването започва на 15-и, тоест последният осигурен ден е 14-и — а прекратяването на"
            + " 20-и пада ВЪТРЕ в него и не поражда втори отрязък. Записан, той щеше да е отрязък от"
            + " нула осигурени дни.");
  }

  @Test
  @DisplayName("отрязъци · шест отрязъка се орязват до пет, а ЗАГУБАТА се брои")
  void truncationIsCounted() {
    List<LocalDate[]> gaps = new ArrayList<>();
    for (int day : new int[] {3, 6, 9, 12, 15}) {
      gaps.add(new LocalDate[] {LocalDate.of(2026, 5, day), LocalDate.of(2026, 5, day)});
    }
    BgD1InsuranceSegments.Outcome outcome =
        BgD1InsuranceSegments.of(MAY_2026, MAY_2026.withDayOfMonth(31), null, null, gaps);

    assertEquals(5, outcome.getSegments().size(), "форматът носи ПЕТ двойки");
    assertEquals(6, outcome.getTotal(), "а отрязъците са ШЕСТ");
    assertTrue(
        outcome.isTruncated(),
        "⚠️ Броят на ВЪРНАТИТЕ отрязъци не може да каже това: пет върнати значат и „точно пет“, и"
            + " „шест, от които един изчезна“. Затова загубата се пази отделно.");
  }

  @Test
  @DisplayName("⚠️ прекъсванията пристигат в ПРОИЗВОЛЕН ред и се нормализират")
  void theInterruptionsNeedNoOrdering() {
    BgD1InsuranceSegments.Outcome outcome =
        BgD1InsuranceSegments.of(
            MAY_2026,
            MAY_2026.withDayOfMonth(31),
            null,
            null,
            Arrays.asList(
                new LocalDate[] {LocalDate.of(2026, 5, 20), LocalDate.of(2026, 5, 22)},
                new LocalDate[] {LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 7)}));

    assertEquals(3, outcome.getSegments().size());
    assertEquals(LocalDate.of(2026, 5, 1), outcome.getSegments().get(0).getFrom());
    assertEquals(LocalDate.of(2026, 5, 8), outcome.getSegments().get(1).getFrom());
    assertEquals(
        LocalDate.of(2026, 5, 23),
        outcome.getSegments().get(2).getFrom(),
        "⚠️ Заявките идват подредени по дата от базата, но НОРМАЛИЗАЦИЯТА не разчита на това:"
            + " припокриващи се заявки се сортират и сливат тук. Разчитано на реда от заявката, един"
            + " ORDER BY по-малко щеше да размести двойките безшумно.");
  }
}
