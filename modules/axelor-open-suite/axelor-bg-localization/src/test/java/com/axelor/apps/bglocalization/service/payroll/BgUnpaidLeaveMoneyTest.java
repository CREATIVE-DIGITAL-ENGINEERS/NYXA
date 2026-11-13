package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.d1.BgD1DayBuckets;
import com.axelor.apps.bglocalization.service.d1.BgD1RecordBuilder;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НЕПЛАТЕНИЯТ ОТПУСК ДО ПАРИТЕ — карв в перата, съгласуваност с Декларация обр. 1, композиция с чл.
 * 177.
 *
 * <p><b>ЗАЩО ИЗОБЩО.</b> Дните неплатен отпуск падат ВЪТРЕ в {@code bgWorkedDays} — числото се
 * пълни с нормата на месеца и отсъствията НЕ се вадят от него ({@code BgPaySlipService#prepare}).
 * Тоест без карв основната заплата, класът и научната степен биха платили пълен месец на човек,
 * който е бил в неплатен отпуск. При основна 1000 € с клас 60 €, норма 22 и 5 дни това са 240,91 €,
 * изплатени без основание — КТ чл. 128 т. 2 дължи възнаграждение „ЗА ИЗВЪРШЕНАТА РАБОТА“, а
 * институтът по чл. 160 се нарича „НЕПЛАТЕН отпуск“ именно затова.
 *
 * <p><b>⚠️ И ЗАЩО {@code bgWorkedDays} НЕ СЕ ПИПА.</b> Декларация обр. 1 вече вади тези дни: т.
 * 16.1 се ИЗВЕЖДА като {@code bgWorkedDays − т. 16.3 − т. 16.4 − т. 16.5}. Намалени и на фиша,
 * същите дни щяха да излязат от декларацията ДВА пъти. Свидетелят {@link
 * #theSlipAndTheDeclarationAgreeOnTheSameSeventeenDays} мери точно тази двойка.
 */
class BgUnpaidLeaveMoneyTest {

  private static final LocalDate NOV_FROM = LocalDate.of(2026, 11, 1);
  private static final LocalDate NOV_TO = LocalDate.of(2026, 11, 30);
  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  /** Базовият месец на чл. 177 — ноември 2026. */
  private static final int NOV_WORKING_DAYS = 21;

  /** Месецът на всички свидетели тук: норма 22, отработени 22. */
  private static final int DEC_WORKING_DAYS = 22;

  private static final BigDecimal BASE_SALARY = new BigDecimal("1000.00");

  /** 0,6 на сто за всяка от 10 навършени години = 6 на сто = 60,00 €. */
  private static final BigDecimal SENIORITY_PCT = new BigDecimal("0.6000");

  private static final int SENIORITY_YEARS = 10;

  private static final BigDecimal MIN_WAGE = new BigDecimal("620.20");

  private static final BigDecimal FIVE = new BigDecimal("5");

  // ═══ ПАРИТЕ ══════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ ПАРИТЕ · основна 1000 + клас 60, норма 22, 5 неплатени → 819,09 (махнати 240,91)")
  void theUnpaidDaysAreCarvedOutOfTheDayCountedLines() {
    Harness h = new Harness();

    BgPaySlip before = h.run(BigDecimal.ZERO);
    BgPaySlip after = h.run(FIVE);

    assertEquals(new BigDecimal("1000.00"), amountOf(before, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("60.00"), amountOf(before, BgEarningsResolver.LINE_SENIORITY));
    assertEquals(new BigDecimal("1060.00"), gross(before), "пълен месец, без отсъствие");

    assertEquals(
        new BigDecimal("772.73"),
        amountOf(after, BgEarningsResolver.LINE_BASE_SALARY),
        "1000 × (22 − 5) / 22 = 772,7272… → 772,73");
    assertEquals(
        new BigDecimal("46.36"),
        amountOf(after, BgEarningsResolver.LINE_SENIORITY),
        "1000 × 0,6 × 10 × 17 / (100 × 22) = 46,3636… → 46,36");
    assertEquals(new BigDecimal("819.09"), gross(after), "1060 × 17 / 22 = 819,0909… → 819,09");
    assertEquals(
        new BigDecimal("240.91"),
        gross(before).subtract(gross(after)),
        "⚠️ И НИЩО НЕ ГИ ВРЪЩА — за разлика от чл. 177. Перо 165 НЕ се появява: неплатеният отпуск"
            + " няма среднодневно от предходния месец, защото няма право на възнаграждение изобщо.");
    assertNull(
        amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ неплатеният отпуск НЕ ражда перо по чл. 177");
  }

  @Test
  @DisplayName("⚠️ КОЛИЧЕСТВОТО НА РЕДА слиза на 17 — числото до сумата обяснява ИМЕННО нея")
  void theQuantityOnTheLineFollowsTheMoney() {
    BgPaySlip slip = new Harness().run(FIVE);
    assertEquals(
        0,
        new BigDecimal("17").compareTo(quantityOf(slip, BgEarningsResolver.LINE_BASE_SALARY)),
        "оставено на 22, редът щеше да твърди пълен месец срещу сума за 17 дни и следващият читател"
            + " щеше да сметне друга ставка");
  }

  @Test
  @DisplayName("⚠️ ПЛОСКОТО ПЕРО ПАДА СЪЩО · научната степен 100,00 → 77,27")
  void theFlatAcademicDegreeFallsToo() {
    Harness h = new Harness();
    h.academicDegree = "DOCTOR_OF_SCIENCES";
    h.degreeAmount = new BigDecimal("100.00");

    BgPaySlip after = h.run(FIVE);

    assertEquals(
        new BigDecimal("77.27"),
        amountOf(after, BgEarningsResolver.LINE_ACADEMIC_DEGREE),
        "⚠️ ТОВА Е РЕШЕНИЕТО. Перото НЕ се проратира от отсъствия (болният цял месец получава пълните"
            + " 25,56 €), но неплатеният отпуск не е отсъствие при течащо заплащане: през тези дни"
            + " възнаграждение не се дължи изобщо. 100 × (22 − 5) / 22 = 77,2727… → 77,27."
            + " Некарвирано, перото щеше да надплати 22,73 € с вид на пресметнати.");
    assertEquals(
        new BigDecimal("896.36"),
        gross(after),
        "772,73 + 46,36 + 77,27 = 896,36 = 1160 × 17 / 22 — трите пера падат в един и същ дял");
  }

  @Test
  @DisplayName("⚠️ Д1 · фишът плаща 17 дни и декларацията обявява 17 — bgWorkedDays остава 22")
  void theSlipAndTheDeclarationAgreeOnTheSameSeventeenDays() {
    BgPaySlip slip = new Harness().run(FIVE);

    assertEquals(
        DEC_WORKING_DAYS,
        slip.getBgWorkedDays().intValue(),
        "⚠️ ЕНДЖИНЪТ НЕ ПИПА ОТРАБОТЕНИТЕ. Намалени тук, същите дни щяха да излязат от т. 16.1 ДВА"
            + " пъти — веднъж като по-малко отработени и втори път като кофа 16.5.");

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        BgD1RecordBuilder.build(
            slip,
            insuredType(),
            period(DEC_FROM, DEC_TO),
            BgD1DayBuckets.sum(
                Collections.singletonList(new BgD1DayBuckets.Entry(BgD1DayBuckets.B_16_5, 5))),
            findings);

    assertEquals("5", record.getT165UnpaidLeaveDays(), "т. 16.5 — чл. 9 ал. 2 т. 3 КСО");
    assertEquals(
        "17",
        record.getT161WorkedDays(),
        "⚠️ ТОЧНО ДНИТЕ, КОИТО ФИШЪТ ПЛАЩА: 22 − 5 = 17. Тук е цялото основание карвът да е стеснен"
            + " до видовете с кофа — за вид БЕЗ кофа т. 16.1 щеше да остане 22 срещу заплата за 17.");
    assertEquals("22", record.getT16Days(), "заб. 1: 17 + 5 = 22 — точно работните дни на месеца");
  }

  // ═══ ПЕТТЕ ВИДА ИЗВЪН ОСИГУРЯВАНЕТО (КСО чл. 10 ал. 2) ═══════════════════

  @Test
  @DisplayName(
      "⚠️ ПАРИТЕ · 5 дни САМООТЛЪЧКА → същите 819,09 (махнати 240,91) — вторият път до карва")
  void thedaysOutsideInsuranceAreCarvedByTheSameArithmetic() {
    BgPaySlip before = new Harness().run(BigDecimal.ZERO);
    BgPaySlip after = new Harness().runOutsideInsurance(FIVE);

    assertEquals(new BigDecimal("1060.00"), gross(before), "пълен месец");
    assertEquals(
        new BigDecimal("772.73"),
        amountOf(after, BgEarningsResolver.LINE_BASE_SALARY),
        "1000 × (22 − 5) / 22 = 772,7272… → 772,73");
    assertEquals(
        new BigDecimal("46.36"),
        amountOf(after, BgEarningsResolver.LINE_SENIORITY),
        "1000 × 0,6 × 10 × 17 / (100 × 22) = 46,3636… → 46,36");
    assertEquals(
        new BigDecimal("819.09"),
        gross(after),
        "⚠️ НЕ 1060,00. Самоотлъчката е неявяване на работа (КТ чл. 190 ал. 1 т. 2), тоест труд"
            + " няма и по КТ чл. 128 т. 2 възнаграждение не се дължи — а кофата „други“, в която"
            + " тя пада, сама не се чете от производството.");
    assertEquals(
        new BigDecimal("240.91"),
        gross(before).subtract(gross(after)),
        "същото число като при неплатения отпуск — една аритметика, два входа към нея");
  }

  @Test
  @DisplayName("⚠️ Двата входа СЕ СЪБИРАТ в един числител: 3 с кофа + 2 извън → 5 карвирани дни")
  void thetwoCarveInputsShareOneNumerator() {
    Harness h = new Harness();
    h.outsideDays = new BigDecimal("2");
    BgPaySlip slip = h.run(new BigDecimal("3"));

    assertEquals(
        new BigDecimal("772.73"),
        amountOf(slip, BgEarningsResolver.LINE_BASE_SALARY),
        "⚠️ 1000 × (22 − 3 − 2) / 22 = 772,7272… → 772,73 — ЕДНО деление. Приложени едно след друго,"
            + " двете биха дали 774,38: същият капан, който BgUnpaidLeaveCarveScopeTest мери за"
            + " верижното делене, и точно затова сборът се прави в BgEarningsService, а не надолу.");
    assertEquals(new BigDecimal("819.09"), gross(slip), "1060 × 17 / 22");
  }

  @Test
  @DisplayName(
      "⚠️ СВЪРЗАНОСТ · заплатата пада И т. 16.1 пада — заедно, никакви осигурени дни без основа")
  void thesalaryAndPoint16_1MoveTogetherForTheUnbucketedFive() {
    BgPaySlip slip = new Harness().runOutsideInsurance(FIVE);

    assertEquals(
        DEC_WORKING_DAYS,
        slip.getBgWorkedDays().intValue(),
        "⚠️ И ТУК bgWorkedDays НЕ СЕ ПИПА. Намален, същите дни щяха да излязат от т. 16.1 два пъти —"
            + " веднъж като по-малко отработени, втори път като изваждаемото.");
    assertEquals(new BigDecimal("819.09"), gross(slip), "фишът плаща 17 дни (1060 × 17 / 22)");

    List<BgD1FindingDto> findings = new ArrayList<>();
    BgD1Record record =
        BgD1RecordBuilder.build(
            slip,
            insuredType(),
            period(DEC_FROM, DEC_TO),
            new com.axelor.apps.bglocalization.service.d1.BgD1Facts(
                BgD1DayBuckets.sum(Collections.emptyList()), null, null, null, null, 5),
            findings);

    assertEquals(
        "17",
        record.getT161WorkedDays(),
        "⚠️ И ДЕКЛАРАЦИЯТА ОБЯВЯВА СЪЩИТЕ 17. С 22 тук би имало 22 дни в осигуряване срещу"
            + " заплата за 17: осигурени дни без осигурителна основа, при това пред НАП. Двете"
            + " половини се движат по ЕДИН предикат (BgD1DayBuckets.outsideInsurance) и не могат да"
            + " се разминат.");
    assertEquals(
        "17",
        record.getT16Days(),
        "⚠️ И СБОРЪТ ПАДА ДО 17, не остава 22: петте дни не са в НИТО ЕДНА кофа, защото през тях"
            + " осигуряването е прекъснато (КСО чл. 10 ал. 2) — същите дати, които т. 14/15 вече"
            + " обявява като прекъсване.");
    assertEquals("0", record.getT165UnpaidLeaveDays(), "и не са неплатен отпуск, зачетен за стаж");
  }

  @Test
  @DisplayName("⚠️ Без такова отсъствие фишът не се променя — БИТ ПО БИТ")
  void withoutSuchAbsencesTheSlipIsUnchanged() {
    BgPaySlip untouched = new Harness().runOutsideInsurance(BigDecimal.ZERO);

    assertEquals(
        new BigDecimal("1000.00"), amountOf(untouched, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("60.00"), amountOf(untouched, BgEarningsResolver.LINE_SENIORITY));
    assertEquals(
        new BigDecimal("1060.00"), gross(untouched), "нула дни извън осигуряването → нула промяна");
  }

  // ═══ КОМПОЗИЦИЯТА С ЧЛ. 177 ═════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ КОМПОЗИЦИЯ · 5 дни по чл. 177 И 5 неплатени на един фиш → перо 165 е САМО за чл. 177")
  void theTwoCarvesComposeWithoutStealingFromEachOther() {
    Harness h = new Harness();
    BgPaySlip slip = h.runWithArt177(FIVE, FIVE);

    assertEquals(
        new BigDecimal("545.45"),
        amountOf(slip, BgEarningsResolver.LINE_BASE_SALARY),
        "1000 × (22 − 5 − 5) / 22 = 545,4545… → 545,45 — ЕДИН числител, не две последователни деления");
    assertEquals(new BigDecimal("32.73"), amountOf(slip, BgEarningsResolver.LINE_SENIORITY));
    assertEquals(
        new BigDecimal("240.91"),
        amountOf(slip, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ ПЕРОТО ВРЪЩА САМО ЧАСТТА ПО ЧЛ. 177: карв 227,28 + 13,63 = 240,91, надбавка нула."
            + " Неплатените дни НЕ влизат в него — те не се връщат никъде.");
    assertEquals(
        0,
        h.lastOutcome.uplift.compareTo(BigDecimal.ZERO),
        "непроменен състав на възнаграждението → надбавката е ТОЧНО нула");
    assertEquals(
        new BigDecimal("819.09"),
        gross(slip),
        "⚠️ И ЕТО ГО ДОКАЗАТЕЛСТВОТО, ЧЕ СЕ КОМПОЗИРАТ: тоталът е СЪЩИЯТ като при само 5 неплатени"
            + " дни. Платеният отпуск премести пари, без да добави; неплатеният свали 240,91.");
  }

  @Test
  @DisplayName("⚠️ СТОТИНКАТА · карвът по чл. 177 се мери при НЕПОДВИЖЕН неплатен отпуск")
  void theArt177CarveIsMeasuredWithTheUnpaidLeaveHeldConstant() {
    Harness h = new Harness();
    h.runWithArt177(FIVE, FIVE);

    assertEquals(
        0,
        new BigDecimal("240.91").compareTo(h.lastInputs.carve),
        "⚠️ 772,73 − 545,45 = 227,28 за основната и 46,36 − 32,73 = 13,63 за класа. Мерен срещу"
            + " НЕкарвирания за неплатения отпуск изход (1000,00 и 60,00), карвът щеше да излезе"
            + " 227,27 + 13,64 = 240,91 по друг път и да се размине с онова, което фишът наистина е"
            + " загубил — а точно това разминаване чупи инварианта „нето нула“ с една стотинка."
            + " Намерено: "
            + h.lastInputs.carve);
    assertEquals(
        0,
        new BigDecimal("1060.00").compareTo(h.lastInputs.currentMonthPermanentPay),
        "⚠️ M остава НЕПРОРАТИРАН: дневната ставка по чл. 177 е M / норма и НЕ пада заради неплатен"
            + " отпуск. Паднала, надбавката щеше да излезе завишена точно в такъв месец.");
  }

  @Test
  @DisplayName(
      "⚠️ 5 дни по чл. 177 И 5 ИЗВЪН осигуряването → същите 227,28 + 13,63, стотинката стои")
  void theArt177CarveStaysFrozenAgainstTheNewSubtrahendToo() {
    Harness h = new Harness();
    h.outsideDays = FIVE;
    BgPaySlip slip = h.runWithArt177(null, FIVE);

    assertEquals(
        new BigDecimal("545.45"),
        amountOf(slip, BgEarningsResolver.LINE_BASE_SALARY),
        "1000 × (22 − 5 − 5) / 22 — новият изваждаем дели СЪЩИЯ числител като стария");
    assertEquals(new BigDecimal("32.73"), amountOf(slip, BgEarningsResolver.LINE_SENIORITY));
    assertEquals(
        0,
        new BigDecimal("240.91").compareTo(h.lastInputs.carve),
        "⚠️ 227,28 + 13,63, НЕ 227,27 + 13,64. Разликовият карв по чл. 177 се мери между два изхода,"
            + " които се различават САМО по дните по чл. 177 — тоест новият изваждаем трябва да стои"
            + " НЕПОДВИЖЕН и в двете извиквания. Затова той се сгъва в unpaidCarveDays ЕДИН ПЪТ, в"
            + " BgEarningsService#carveDaysOf, а не пътува като трети параметър надолу. Намерено: "
            + h.lastInputs.carve);
    assertEquals(
        new BigDecimal("240.91"),
        amountOf(slip, BgArt177LeaveService.LINE_ART177_LEAVE),
        "перото връща точно карва по чл. 177 — самоотлъчката не се връща никъде");
    assertEquals(
        new BigDecimal("819.09"),
        gross(slip),
        "и тоталът е същият като при само 5 дни извън осигуряването");
  }

  // ═══ ОТКАЗЪТ ════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ОТКАЗ · непреброени дни отсъствие → шум в bgGeneratorGaps, а НЕ пълна заплата")
  void aRefusedLeaveSourceIsLoudAndPaysNothing() {
    Harness h = new Harness();
    h.leaveDaysRefusal = BgPayrollInputBuilder.REFUSALS.get("leaveReasonUnmapped");

    BgPaySlip slip = h.run(null);

    assertNull(
        amountOf(slip, BgEarningsResolver.LINE_BASE_SALARY),
        "⚠️ Отказът на leaveDaysOf обезсилва ЦЯЛАТА тройка кофи и не се изхвърля в build()."
            + " Прочетен като „нула дни отпуск“, той би платил ПЪЛЕН месец на"
            + " лице, което може да е било в неплатен отпуск целия месец — най-скъпата възможна"
            + " грешка, при това с вид на най-обикновен фиш.");
    assertNull(amountOf(slip, BgEarningsResolver.LINE_SENIORITY), "и класът спира");
    assertEquals(0, gross(slip).compareTo(BigDecimal.ZERO), "нито един лев не излиза мълчаливо");

    String gaps = slip.getBgGeneratorGaps();
    assertNotNull(gaps, "⚠️ ПРИЧИНАТА СТИГА ДО ЕКРАНА, не само до лога");
    assertTrue(
        gaps.contains("LeaveReason.bgAbsenceType"),
        "⚠️ и то ДОСЛОВНО от BgPayrollInputBuilder.REFUSALS — текстът казва КОЕ ПОЛЕ да се попълни."
            + " Преразказан тук, той щеше да е второ описание на същия проблем. Намерено: "
            + gaps);
    assertTrue(slip.getBgGeneratorRefusalCount() > 0, "и се брои като отказ, не като бележка");
  }

  @Test
  @DisplayName("⚠️ „Няма нито една заявка за отпуск“ НЕ е отказ — заплатата излиза цяла")
  void anInstallationWithoutLeaveRequestsStillGetsPaid() {
    BgPaySlip slip = new Harness().run(null);

    assertEquals(
        new BigDecimal("1000.00"),
        amountOf(slip, BgEarningsResolver.LINE_BASE_SALARY),
        "⚠️ И ДВЕТЕ СЪСТОЯНИЯ ДАВАТ null ДНИ, но искат ПРОТИВОПОЛОЖНО поведение. Инсталация, която"
            + " просто не води отпуските си в модула, не бива да остане без заплата — разликата е"
            + " САМО в leaveDaysRefusal.");
    assertNull(slip.getBgGeneratorGaps(), "нула откази");
  }

  // ═══ НУЛА ДНИ И РЪЧНОТО ПЕРО ═════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ НУЛА неплатени дни → фишът е БИТ ПО БИТ както без отсъствие")
  void zeroUnpaidDaysChangeNothing() {
    for (BigDecimal days : Arrays.asList(null, BigDecimal.ZERO)) {
      Harness h = new Harness();
      h.academicDegree = "DOCTOR_OF_SCIENCES";
      h.degreeAmount = new BigDecimal("100.00");
      BgPaySlip slip = h.run(days);

      assertEquals(new BigDecimal("1000.00"), amountOf(slip, BgEarningsResolver.LINE_BASE_SALARY));
      assertEquals(new BigDecimal("60.00"), amountOf(slip, BgEarningsResolver.LINE_SENIORITY));
      assertEquals(
          new BigDecimal("100.00"), amountOf(slip, BgEarningsResolver.LINE_ACADEMIC_DEGREE));
      assertEquals(
          0,
          BigDecimal.ONE.compareTo(quantityOf(slip, BgEarningsResolver.LINE_ACADEMIC_DEGREE)),
          "количеството на плоското перо остава ЕДИН МЕСЕЦ");
      assertEquals(new BigDecimal("1160.00"), gross(slip), "дни: " + days);
    }
  }

  @Test
  @DisplayName("⚠️ РЪЧНОТО постоянно перо ПАДА — 896,36, не 919,09")
  void theManualPermanentLineIsCarvedToo() {
    Harness h = new Harness();
    h.manualPermanentAllowance = new BigDecimal("100.00");

    BgPaySlip slip = h.run(FIVE);

    assertEquals(
        new BigDecimal("77.27"),
        amountOf(slip, MANUAL_BONUS),
        "100 × 17/22 = 77,2727… → 77,27. ⚠️ Перото е MANUAL_AMOUNT: без пазена въведена сума,"
            + " намалено на реда, то би се намалило ПАК при второто prepare(). Източникът е"
            + " BgPaySlipLine.bgEnteredAmount.");
    assertEquals(
        new BigDecimal("100.00"),
        lineOf(slip, MANUAL_BONUS).getBgEnteredAmount(),
        "въведената сума е УЛОВЕНА, не изгубена — оттам идва идемпотентността");
    assertEquals(
        new BigDecimal("896.36"),
        gross(slip),
        "⚠️ 772,73 + 46,36 + 77,27 = 896,36 = 1160 × 17/22. 919,09 би било надплащане от"
            + " 22,73 € = 100 × 5/22. КТ чл. 128 т. 2 дължи възнаграждение „за извършената"
            + " работа“, а през дните неплатен отпуск работа няма.");
  }

  @Test
  @DisplayName("⚠️ ЧЛ. 7 · часовата база НЕ пада с отпуска — иначе извънредният труд поевтинява")
  void theHourlyRateBaseIgnoresTheCarve() {
    Harness h = new Harness();
    h.manualPermanentAllowance = new BigDecimal("100.00");

    BgPaySlip slip = h.run(FIVE);
    BgEarningsService earnings = h.earnings();

    assertEquals(
        new BigDecimal("77.27"), amountOf(slip, MANUAL_BONUS), "редът наистина е карвиран");
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(earnings.permanentAllowances(slip)),
        "⚠️ И ТОВА Е ПО-СКЪПИЯТ ОТ ДВАТА ЧИТАТЕЛЯ. Базата по НСОРЗ чл. 7 е УГОВОРЕНОТО МЕСЕЧНО"
            + " възнаграждение „по индивидуалния трудов договор“ и НЕ се пропорционира от"
            + " отсъствия — javadoc-ът на permanentPayBase го казва дословно за класа. Прочетена"
            + " от карвирания ред, часовата ставка щеше да падне и нощният и извънредният труд да"
            + " се плащат по-евтино точно в месеците с отпуск, с вид на пресметнато."
            + " Намерено: "
            + earnings.permanentAllowances(slip));
  }

  @Test
  @DisplayName("⚠️ ИДЕМПОТЕНТНО · второ prepare() върху ръчното перо дава СЪЩОТО число")
  void theManualCarveIsIdempotent() {
    Harness h = new Harness();
    h.manualPermanentAllowance = new BigDecimal("100.00");

    BgPaySlip slip = h.run(FIVE);
    assertEquals(new BigDecimal("77.27"), amountOf(slip, MANUAL_BONUS));

    h.prepareOn(slip, FIVE, null);
    assertEquals(
        new BigDecimal("77.27"),
        amountOf(slip, MANUAL_BONUS),
        "⚠️ КОРЕНЪТ НА ЦЕЛИЯ КЛОН. Смятано от текущия ред, второто минаване щеше да даде"
            + " 77,27 × 17/22 = 59,71, третото 46,13 — всяко от тях правдоподобно. Смята се от"
            + " bgEnteredAmount и затова не мърда.");

    h.prepareOn(slip, FIVE, null);
    assertEquals(new BigDecimal("77.27"), amountOf(slip, MANUAL_BONUS), "и трето минаване");
    assertEquals(new BigDecimal("896.36"), gross(slip), "брутото също не мърда");
  }

  @Test
  @DisplayName("⚠️ РЕДАКЦИЯТА НА ОПЕРАТОРА СЕ УВАЖАВА · смени ли въведеното, сумата го следва")
  void anOperatorEditAfterTheFirstComputeIsHonoured() {
    Harness h = new Harness();
    h.manualPermanentAllowance = new BigDecimal("100.00");

    BgPaySlip slip = h.run(FIVE);
    assertEquals(new BigDecimal("77.27"), amountOf(slip, MANUAL_BONUS));

    // Операторът вдига бонуса на 120,00 СЛЕД първото преизчисляване — редактира се ВЪВЕДЕНАТА
    // сума, защото „Сума“ е резултат и е readonly за тези редове (виж bg-payslip-views.xml).
    lineOf(slip, MANUAL_BONUS).setBgEnteredAmount(new BigDecimal("120.00"));
    h.prepareOn(slip, FIVE, null);

    assertEquals(
        new BigDecimal("92.73"),
        amountOf(slip, MANUAL_BONUS),
        "⚠️ 120 × 17/22 = 92,7272… → 92,73. ИДЕМПОТЕНТНОСТТА НЕ БИВА ДА СТАВА ЗАМРАЗЯВАНЕ: улавяне"
            + " само при празно поле е вярно, но само защото редактируемото поле Е източникът."
            + " Пипал операторът „Сума“, редакцията му щеше да се пренапише мълчаливо при следващия"
            + " обход — затова тя е readonly точно за тези редове.");
    assertEquals(
        new BigDecimal("120.00"),
        lineOf(slip, MANUAL_BONUS).getBgEnteredAmount(),
        "новата въведена сума остава");
  }

  @Test
  @DisplayName("⚠️ РЕТРО ПЕРО НЕ ВЛИЗА · нито в M, нито в часовата база (собствен период)")
  void aRetroLineWithThePermanentFlagStaysOut() {
    Harness h = new Harness();
    h.manualPermanentAllowance = new BigDecimal("100.00");

    BgPaySlip slip = h.run(FIVE);
    BgEarningsService earnings = h.earnings();

    // Ред за МИНАЛ период с вдигнат постоянен характер. Днес речникът не ражда такъв
    // (pl_korekcia_minal_period е с постоянен характер FALSE) — тоест пазеше го СТОЙНОСТ В СИЙДА,
    // не код. Една отметка в CSV и ретро-делтата влизаше в M и в часовата ставка.
    BgPaySlipLine retro = newLine(typeOf(MANUAL_BONUS), new BigDecimal("500.00"));
    retro.setBgCorrectsPeriod(period(DEC_FROM.minusMonths(1), DEC_FROM.minusDays(1)));
    slip.getBgPaySlipLineList().add(retro);

    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(earnings.permanentAllowances(slip)),
        "⚠️ 500,00 за МИНАЛ месец не са възнаграждение за ТОЗИ — влезли в M, дневната ставка по"
            + " чл. 177 щеше да порасне и надбавката да излезе занижена, а часовата ставка по"
            + " чл. 7 да поскъпне. Като art17BaseOf и този обход филтрира по isOwnPeriod."
            + " Намерено: "
            + earnings.permanentAllowances(slip));

    h.prepareOn(slip, FIVE, null);
    assertEquals(
        new BigDecimal("500.00"),
        retro.getBgAmount(),
        "⚠️ И НЕ СЕ КАРВИРА: ретро редът носи числата на ОНЗИ месец и отпускът на ТОЗИ няма нищо"
            + " общо с тях. Карвиран, той щеше да пренапише история, която ретро-механизмът"
            + " съществува, за да не пренаписва.");
  }

  @Test
  @DisplayName("ИДЕМПОТЕНТНО · второ prepare() не карвира втори път")
  void theCarveIsIdempotent() {
    Harness h = new Harness();
    BgPaySlip slip = h.run(FIVE);
    BigDecimal first = gross(slip);
    h.prepareOn(slip, FIVE, null);

    assertEquals(first, gross(slip), "второ минаване дава същия фиш");
    assertEquals(new BigDecimal("772.73"), amountOf(slip, BgEarningsResolver.LINE_BASE_SALARY));
  }

  @Test
  @DisplayName("⚠️ Отпуск над зачетените дни е ОТКАЗ с ДВЕТЕ числа, не нула заплата")
  void anOverrunRefusesAndNamesBothSubtrahends() {
    Harness h = new Harness();
    h.workedDays = 8;
    BgPaySlip slip = h.run(FIVE);

    assertEquals(
        new BigDecimal("136.36"),
        amountOf(slip, BgEarningsResolver.LINE_BASE_SALARY),
        "8 − 5 = 3 платими дни: 1000 × 3 / 22 = 136,3636… → 136,36. ⚠️ Отпуск, който изяжда почти"
            + " всички зачетени дни, е ЗАКОНЕН случай, а не отказ — отказва се едва отрицателният.");

    h.workedDays = 3;
    BgPaySlip refused = h.run(FIVE);
    assertNull(amountOf(refused, BgEarningsResolver.LINE_BASE_SALARY), "3 − 5 < 0 → отказ");
    assertTrue(
        refused.getBgGeneratorGaps().contains("надхвърлят отработените"),
        "причината назовава и двата изваждаеми: " + refused.getBgGeneratorGaps());
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /**
   * Фишът и двата генератора, навързани БЕЗ база.
   *
   * <p>⚠️ Навързват се ИСТИНСКИТЕ услуги, а не имитации — по същия довод като {@code
   * BgArt177LeaveMoneyTest}: находката е МЕЖДУ тях.
   */
  private final class Harness {

    int workedDays = DEC_WORKING_DAYS;
    String academicDegree;
    BigDecimal degreeAmount;
    BigDecimal manualPermanentAllowance;
    String leaveDaysRefusal;

    BgArt177Outcome lastOutcome;
    BgEarningsService.Art177Inputs lastInputs;
    BigDecimal outsideDays;

    BgPaySlip run(BigDecimal unpaidInD1) {
      BgPaySlip slip = draft();
      prepareOn(slip, unpaidInD1, null);
      return slip;
    }

    /**
     * Същото, но дните идват по ВТОРИЯ път — извън осигуряването (КСО чл. 10 ал. 2), не като
     * неплатен отпуск с кофа.
     */
    BgPaySlip runOutsideInsurance(BigDecimal outsideDays) {
      this.outsideDays = outsideDays;
      BgPaySlip slip = draft();
      prepareOn(slip, null, null);
      return slip;
    }

    BgPaySlip runWithArt177(BigDecimal unpaidInD1, BigDecimal art177Days) {
      BgPaySlip slip = draft();
      prepareOn(slip, unpaidInD1, art177Days);
      return slip;
    }

    void prepareOn(BgPaySlip slip, BigDecimal unpaidInD1, BigDecimal art177Days) {
      BgPayrollInput input = input(unpaidInD1, art177Days);
      BgEarningsService earnings = earnings();
      // ⚠️ ПРИСЪДИТЕ СЕ СЪБИРАТ КАКТО В prepare(), а не се четат от върнатия списък: свидетелят на
      // отказа трябва да мери онова, което стига до ЕКРАНА (bgGeneratorGaps), а не вътрешния изход.
      BgPaySlipVerdicts.reset(slip);
      BgPaySlipVerdicts verdicts = new BgPaySlipVerdicts();
      verdicts.addEarnings(BgPaySlipVerdicts.STEP_EARNINGS, earnings.generate(slip, input));
      verdicts.appendInto(slip);
      lastInputs = earnings.art177Inputs(slip, input);
      if (art177Days != null && art177Days.signum() > 0) {
        lastOutcome = art177().generate(slip, input);
      }
    }

    private BgPaySlip draft() {
      BgPaySlip slip = new BgPaySlip();
      slip.setStatusSelect(BgPaySlipResolver.DRAFT);
      slip.setPeriod(period(DEC_FROM, DEC_TO));
      slip.setBgBaseSalary(BASE_SALARY);
      slip.setBgWorkedDays(workedDays);
      slip.setBgWorkingDaysInPeriod(DEC_WORKING_DAYS);
      slip.setEmploymentContract(contract());
      slip.setBgPaySlipLineList(new ArrayList<>());
      if (manualPermanentAllowance != null) {
        slip.getBgPaySlipLineList().add(newLine(typeOf(MANUAL_BONUS), manualPermanentAllowance));
      }
      return slip;
    }

    private BgPayrollInput input(BigDecimal unpaidInD1, BigDecimal art177Days) {
      return BgPayrollInput.builder()
          .parameterReferenceDate(DEC_FROM)
          .unpaidLeaveDays(unpaidInD1)
          .unpaidLeaveDaysInD1(unpaidInD1)
          .daysOutsideInsurance(outsideDays)
          .art177LeaveDays(art177Days)
          .paidLeaveDays(art177Days)
          .leaveDaysRefusal(leaveDaysRefusal)
          .recognisedServiceStartDate(DEC_FROM.minusYears(SENIORITY_YEARS))
          .academicDegree(academicDegree)
          .build();
    }

    private BgEarningsService earnings() {
      final Harness self = this;
      return new BgEarningsService() {
        @Override
        protected BigDecimal statutoryMinPct(LocalDate date) {
          return SENIORITY_PCT;
        }

        @Override
        protected BigDecimal payrollParameter(String code, LocalDate date) {
          return "DEGREE_DSC_EUR".equals(code) ? self.degreeAmount : null;
        }

        @Override
        protected Map<String, BigDecimal> agreedOvertimeUplift(
            BgPaySlip slip, BgPayrollInput input) {
          return Collections.emptyMap();
        }

        @Override
        protected List<BgPaySlipLineType> lineTypes() {
          return TYPES;
        }

        @Override
        protected BgPaySlipLineType lineType(String code) {
          return typeOf(code);
        }

        @Override
        protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
          return newLine(type, amount);
        }

        @Override
        protected Boolean seniorityEntitlement(BgPaySlip slip) {
          return Boolean.TRUE;
        }
      };
    }

    private BgArt177LeaveService art177() {
      final BgEarningsService earnings = earnings();
      return new BgArt177LeaveService() {
        @Override
        protected BgEarningsService earningsService() {
          return earnings;
        }

        @Override
        protected BgPaySlipLineType lineType() {
          return typeOf(LINE_ART177_LEAVE);
        }

        @Override
        protected BigDecimal minWage(LocalDate date) {
          return MIN_WAGE;
        }

        @Override
        protected List<BgPaySlip> earlierSlips(EmploymentContract contract, LocalDate before) {
          return Collections.singletonList(baseMonthSlip());
        }

        @Override
        protected BigDecimal absenceDaysOf(EmploymentContract contract, Period period) {
          return BigDecimal.ZERO;
        }

        @Override
        protected Integer workingDaysOf(LocalDate from, LocalDate to) {
          if (from.getMonthValue() == 1 && to.getMonthValue() == 12) {
            return 249;
          }
          return NOV_WORKING_DAYS;
        }

        @Override
        protected BgPaySlipService paySlipService() {
          return new BgPaySlipService() {
            @Override
            public BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
              return newLine(type, amount);
            }

            @Override
            public void addLine(BgPaySlip slip, BgPaySlipLine line) {
              slip.getBgPaySlipLineList().add(line);
            }
          };
        }
      };
    }

    /** Ноември 2026 — 21 работни дни, всичките отработени, начислено 1060,00 по чл. 17. */
    private BgPaySlip baseMonthSlip() {
      BgPaySlip slip = new BgPaySlip();
      slip.setStatusSelect("VALIDATED");
      slip.setReference("BGPS-2026-11-001");
      slip.setPeriod(period(NOV_FROM, NOV_TO));
      slip.setBgWorkedDays(NOV_WORKING_DAYS);
      slip.setBgPaySlipLineList(new ArrayList<>());
      BgPaySlipLine line =
          newLine(typeOf(BgEarningsResolver.LINE_BASE_SALARY), new BigDecimal("1060.00"));
      line.setBgIsPermanentCharacter(true);
      slip.getBgPaySlipLineList().add(line);
      return slip;
    }
  }

  // ═══ ОБЩИ ПОМОЩНИЦИ ══════════════════════════════════════════════════════

  /** Ръчно въведено перо с постоянен характер — генераторът НЕ го пипа. */
  private static final String MANUAL_BONUS = "pl_postoyanen_bonus";

  private static final List<BgPaySlipLineType> TYPES =
      Arrays.asList(
          type(BgEarningsResolver.LINE_BASE_SALARY, 100, "Основно възнаграждение", true),
          type(BgEarningsResolver.LINE_SENIORITY, 110, "Клас прослужено време", true),
          type(BgEarningsResolver.LINE_ACADEMIC_DEGREE, 120, "Научна степен", true),
          type(BgArt177LeaveService.LINE_ART177_LEAVE, 165, "Платен отпуск · чл. 177", false),
          type(MANUAL_BONUS, 180, "Постоянен бонус", true));

  private static BgPaySlipLineType type(String code, int number, String name, boolean permanent) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setCode(code);
    t.setNumber(number);
    t.setName(name);
    t.setBgGroup("SALARY_ADDITIONS");
    t.setBgSign(BgPaySlipResolver.CREDIT);
    t.setBgIsPermanentCharacter(permanent);
    t.setBgFormsGrossWage(true);
    t.setBgIsTaxable(true);
    t.setBgIsInsuranceIncome(true);
    t.setBgCalculationBase(calculationBaseOf(code));
    return t;
  }

  private static String calculationBaseOf(String code) {
    if (BgArt177LeaveService.LINE_ART177_LEAVE.equals(code)) {
      return BgEarningsResolver.CALC_DERIVED;
    }
    if (BgEarningsResolver.LINE_ACADEMIC_DEGREE.equals(code)) {
      return BgEarningsResolver.CALC_MONTHLY_FIXED;
    }
    if (MANUAL_BONUS.equals(code)) {
      return BgEarningsResolver.CALC_MANUAL_AMOUNT;
    }
    return BgEarningsResolver.CALC_CONTRACT_BASE_SALARY;
  }

  private static BgPaySlipLineType typeOf(String code) {
    for (BgPaySlipLineType t : TYPES) {
      if (t.getCode().equals(code)) {
        return t;
      }
    }
    return null;
  }

  private static BgPaySlipLine newLine(BgPaySlipLineType type, BigDecimal amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgPaySlipLineType(type);
    line.setBgLineNumber(type.getNumber());
    line.setName(type.getName());
    line.setBgSign(type.getBgSign());
    line.setBgIsPermanentCharacter(type.getBgIsPermanentCharacter());
    line.setBgFormsGrossWage(type.getBgFormsGrossWage());
    line.setBgIsTaxable(type.getBgIsTaxable());
    line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
    line.setBgAmount(amount);
    return line;
  }

  private static Period period(LocalDate from, LocalDate to) {
    Period p = new Period();
    p.setFromDate(from);
    p.setToDate(to);
    return p;
  }

  private static EmploymentContract contract() {
    EmploymentContract c = new EmploymentContract();
    c.setEmployee(new Employee());
    c.setBgBaseSalary(BASE_SALARY);
    c.setBgInsuredTypeCode("01");
    c.setBgHoursPerDay(new BigDecimal("8.00"));
    c.setBgLegalHoursPerDay(new BigDecimal("8.00"));
    return c;
  }

  private static BgInsuredType insuredType() {
    BgInsuredType t = new BgInsuredType();
    t.setCode("01");
    t.setBgAppliesMod(true);
    return t;
  }

  private static BigDecimal amountOf(BgPaySlip slip, String code) {
    BgPaySlipLine line = lineOf(slip, code);
    return line == null ? null : line.getBgAmount();
  }

  private static BigDecimal quantityOf(BgPaySlip slip, String code) {
    BgPaySlipLine line = lineOf(slip, code);
    return line == null ? null : line.getBgQuantity();
  }

  private static BgPaySlipLine lineOf(BgPaySlip slip, String code) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgPaySlipLineType() != null
          && code.equals(line.getBgPaySlipLineType().getCode())) {
        return line;
      }
    }
    return null;
  }

  /** Брутото на фиша — същият агрегат, който плаща и декларира. */
  private static BigDecimal gross(BgPaySlip slip) {
    return BgPaySlipResolver.grossTotal(slip.getBgPaySlipLineList());
  }
}
