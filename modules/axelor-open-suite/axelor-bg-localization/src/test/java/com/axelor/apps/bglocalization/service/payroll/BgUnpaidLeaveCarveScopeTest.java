package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.d1.BgD1DayBuckets;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОБХВАТЪТ НА КАРВА ЗА НЕПЛАТЕНО ОТСЪСТВИЕ — КОИ ДНИ ГО ПОЛУЧАВАТ И КОИ НЕ.
 *
 * <p><b>ПРАВИЛОТО, КОЕТО ТОЗИ ФАЙЛ ПАЗИ.</b> Заплатата се сваля точно за онези дни, които
 * Декларация обр. 1 ВАДИ от т. 16.1 — нито ден повече, нито ден по-малко. Множеството е сборът на
 * ДВЕ ДИЗЮНКТНИ подмножества и всяко се чете по своя колона:
 *
 * <ul>
 *   <li><b>неплатеният отпуск С КОФА</b> ({@code d1Code} непразен) — т. 16.5 по КТ чл. 160 ал. 3 и
 *       т. 16.3 по чл. 167а. Той СЕ ЗАЧИТА за осигурителен стаж и си има точка в декларацията;
 *   <li><b>дните ИЗВЪН ОСИГУРЯВАНЕТО</b> ({@code countsAsInsuranceService = false} И празен {@code
 *       d1Code}) — петте вида в {@link BgD1DayBuckets#UNBUCKETED}. КСО чл. 10 ал. 2: осигуряването
 *       СЕ ПРЕКЪСВА през тези периоди.
 * </ul>
 *
 * <p><b>⚠️ ДВЕТЕ ПОЛОВИНИ НА ВТОРОТО СЕ ДВИЖАТ ЗАЕДНО, И ТОВА Е УСЛОВИЕТО.</b> Свалени само от
 * заплатата, докато т. 16.1 ги брои цели, петте вида биха дали фиш за 17 дни срещу декларация за 22
 * дни в осигуряване. Затова карвът и изваждаемото на т. 16.1 четат ЕДИН И СЪЩ предикат — {@link
 * BgD1DayBuckets#outsideInsurance} — и това е структурната гаранция, че не могат да се разминат.
 */
class BgUnpaidLeaveCarveScopeTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  /** Декември 2026: 23 делнични дни минус 24 и 25.12 (официални празници) = 21 работни. */
  private static final Set<LocalDate> HOLIDAYS =
      new HashSet<>(Arrays.asList(LocalDate.of(2026, 12, 24), LocalDate.of(2026, 12, 25)));

  // ═══ ПЪРВОТО ПОДМНОЖЕСТВО · НЕПЛАТЕНИЯТ С КОФА ═══════════════════════════

  @Test
  @DisplayName("⚠️ Неплатеният ДО 30 дни (т. 16.5) влиза в карва — и остава в общата кофа")
  void unpaidLeaveWithAD1BucketIsCarvedAndStillCounted() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(unpaid("2026-12-07", "2026-12-11", "5", "16.5"));

    assertEquals(0, new BigDecimal("5").compareTo(days.unpaid), "петте дни СА неплатен отпуск");
    assertEquals(
        0,
        new BigDecimal("5").compareTo(days.unpaidInD1),
        "⚠️ и ВСИЧКИТЕ пет се карвират: 16.5 значи, че Д1 ги вади от т. 16.1 (КТ чл. 160 ал. 3,"
            + " чл. 9 ал. 2 т. 3 КСО). Намерено: "
            + days.unpaidInD1);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(days.outsideInsurance),
        "⚠️ И НЕ В ДВЕТЕ ПОДМНОЖЕСТВА. Денят се зачита за осигурителен стаж (кофа 16.5), тоест"
            + " осигуряването НЕ се прекъсва — броен и тук, той щеше да се извади от заплатата ДВА"
            + " пъти. Намерено: "
            + days.outsideInsurance);
  }

  @Test
  @DisplayName("⚠️ Отпускът за дете до 8 г. (чл. 167а, т. 16.3) СЪЩО влиза — кофата решава, не КТ")
  void childCareUnpaidLeaveIsCarvedToo() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(unpaid("2026-12-07", "2026-12-11", "5", "16.3"));

    assertEquals(
        0,
        new BigDecimal("5").compareTo(days.unpaidInD1),
        "⚠️ Разграничителят Е d1Code, а не „кой член от КТ“: чл. 167а носи ДРУГА кофа (16.3, дни за"
            + " отглеждане на дете), но Д1 вади и нея от т. 16.1 — значи и тя се карвира. Списък с"
            + " членове тук щеше да пропусне точно този случай.");
  }

  // ═══ ВТОРОТО ПОДМНОЖЕСТВО · ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО ════════════════════

  @Test
  @DisplayName("⚠️ ГЛАВНИЯТ СВИДЕТЕЛ · неплатеният НАД 30 дни се карвира — и пак СЕ брои")
  void unpaidLeaveOutsideInsuranceIsCarvedAndStillCounted() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(unpaid("2026-12-07", "2026-12-11", "5", null));

    assertEquals(
        0,
        new BigDecimal("5").compareTo(days.unpaid),
        "⚠️ БРОИ СЕ: здравната вноска по ЗЗО чл. 40 ал. 1 т. 1 б. „б“ се дължи за ВСЕКИ ден неплатен"
            + " отпуск, независимо дали се зачита за осигурителен стаж — чл. 40 не познава"
            + " 30-дневния праг. Изваден от кофата, той щеше да отнеме и нея.");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(days.unpaidInD1),
        "⚠️ И НЕ Е В ПЪРВОТО ПОДМНОЖЕСТВО: без d1Code няма кофа в т. 16, тоест Д1 не го вади ОТТАМ."
            + " Двете подмножества са дизюнктни по конструкция. Намерено: "
            + days.unpaidInD1);
    assertEquals(
        0,
        new BigDecimal("5").compareTo(days.outsideInsurance),
        "⚠️ НО СЕ КАРВИРА — през второто подмножество. КСО чл. 9 ал. 2 т. 3 зачита неплатения отпуск"
            + " ДО 30 работни дни; над тях осигуряването СЕ ПРЕКЪСВА (чл. 10 ал. 2) и"
            + " възнаграждение не се дължи (КТ чл. 128 т. 2). Т. 16.1 вади същите дни. Намерено: "
            + days.outsideInsurance);
  }

  @Test
  @DisplayName(
      "⚠️ САМООТЛЪЧКАТА е категория 5 („други“) и пак се карвира — кофата на фиша не решава")
  void awolIsCarvedThoughItSitsInTheOtherBucket() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(disciplinary("2026-12-07", "2026-12-11", "5"));

    assertEquals(0, new BigDecimal("5").compareTo(days.other), "категория 5 → кофата „други“");
    assertEquals(
        0,
        new BigDecimal("5").compareTo(days.outsideInsurance),
        "⚠️ И СЕ КАРВИРА. Самоотлъчката е неявяване на работа (КТ чл. 190 ал. 1 т. 2) — труд няма,"
            + " тоест по КТ чл. 128 т. 2 възнаграждение не се дължи. Кофата „други“ сама не се чете"
            + " от производството, затова без карва петте дни биха се платили мълчаливо. Намерено: "
            + days.outsideInsurance);
  }

  @Test
  @DisplayName("⚠️ НЕПРЕДСТАВЕНИЯТ БОЛНИЧЕН е категория 2 — карвира се И се брои в „други“")
  void theUnsubmittedSickNoteIsCarvedAndLandsInTheOtherBucket() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(sickCategory("2026-12-07", "2026-12-11", "5", false));

    assertEquals(
        0, BigDecimal.ZERO.compareTo(days.paid), "не е платен отпуск — той няма платец по речника");
    assertEquals(0, BigDecimal.ZERO.compareTo(days.unpaid), "и не е неплатен отпуск: не е отпуск");
    assertEquals(
        0,
        new BigDecimal("5").compareTo(days.other),
        "⚠️ Денят на категория 2 се повишава в „други“ — същата кофа, в която са самоотлъчката и"
            + " неприсъствието, и по същия довод: заплащане не се дължи. Ако излезе през"
            + " continue-то без кофа, BgPaySlipService#freezeLeaveDays хвърля IllegalStateException"
            + " („дните по кофи (5) надхвърлят преброените дни отсъствие (0)“) и prepare() пада"
            + " на ВСЕКИ фиш с такава заявка. Намерено: "
            + days.other);
    assertEquals(
        0,
        new BigDecimal("5").compareTo(days.outsideInsurance),
        "⚠️ И КАРВЪТ НЕ СЕ МЕНИ. Дните остават извън осигуряването (КСО чл. 10 ал. 2), тоест"
            + " основната заплата се сваля както без повишаването — то пипа само"
            + " КОФАТА, не изваждаемото. Намерено: "
            + days.outsideInsurance);
  }

  @Test
  @DisplayName("⚠️ ИНВАРИАНТЪТ · всеки ден извън осигуряването е и в НЯКОЯ от трите кофи")
  void everyOutsideDayAlsoSitsInABucket() {
    Object[][] all = {
      unpaid("2026-12-07", "2026-12-08", "2", null), // неплатен над 30 дни → „неплатен“
      disciplinary("2026-12-09", "2026-12-10", "2"), // самоотлъчка/неприсъствие → „други“
      sickCategory("2026-12-11", "2026-12-14", "2", false) // непредставен болничен → „други“
    };
    BgPayrollInputBuilder.LeaveDays days = leaveDaysOf(all);

    BigDecimal buckets = days.paid.add(days.unpaid).add(days.other);
    assertTrue(
        days.outsideInsurance.compareTo(buckets) <= 0,
        "⚠️ ТОВА Е УСЛОВИЕТО, КОЕТО ПРАВИ ПАЗАЧА НА freezeLeaveDays ВЕРЕН ПО ПОСТРОЕНИЕ. Той"
            + " сверява „Σкофи + outside ≤ преброени дни отсъствие“; ден, който тежи в outside и в"
            + " нито една кофа, го чупи. Кофи: "
            + buckets
            + ", извън осигуряването: "
            + days.outsideInsurance);
    assertEquals(
        0,
        new BigDecimal("6").compareTo(days.outsideInsurance),
        "и шестте дни си остават в карва — повишаването не мени нито едно изваждаемо");
  }

  @Test
  @DisplayName("⚠️ ИСТИНСКИЯТ болничен (същата категория 2) НЕ се карвира — признакът ги дели")
  void agenuineSickLeaveIsNotCarved() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(sickCategory("2026-12-07", "2026-12-11", "5", true));

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(days.outsideInsurance),
        "⚠️ РАЗГРАНИЧИТЕЛЯТ Е countsAsInsuranceService, А НЕ КАТЕГОРИЯТА: непредставеният болничен и"
            + " истинският са ЕДНАКВО категория 2. Истинският се зачита за осигурителен стаж (КСО"
            + " чл. 9 ал. 2 т. 2), носи кофа 16.2/16.А и вече е изваден от bgWorkedDays от болничния"
            + " енджин — карвиран и тук, щеше да излезе от заплатата ДВА пъти. Намерено: "
            + days.outsideInsurance);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(days.paid),
        "и минава през continue-то — не влиза в нито една кофа");
  }

  @Test
  @DisplayName(
      "Смесен месец · 3 дни с кофа + 2 извън осигуряването → кофата 5, двата подсбора 3 и 2")
  void theSubsetsArePerRequestNotPerMonth() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(
            unpaid("2026-12-07", "2026-12-09", "3", "16.5"),
            unpaid("2026-12-10", "2026-12-11", "2", null));

    assertEquals(0, new BigDecimal("5").compareTo(days.unpaid), "3 + 2 = 5 дни неплатено");
    assertEquals(
        0,
        new BigDecimal("3").compareTo(days.unpaidInD1),
        "⚠️ Сплитът е ПО ЗАЯВКА, не по месец: един месец носи и двата вида и те не се сливат."
            + " Намерено: "
            + days.unpaidInD1);
    assertEquals(
        0,
        new BigDecimal("2").compareTo(days.outsideInsurance),
        "⚠️ И ВТОРИЯТ ПОДСБОР Е ПО ЗАЯВКА. Карвът е сборът на двата (5 дни), но те се носят"
            + " ПООТДЕЛНО до BgEarningsService, защото правят различни твърдения пред Д1: първите"
            + " три излизат от т. 16.1 през кофата си, вторите два — през изваждаемото. Намерено: "
            + days.outsideInsurance);
  }

  // ═══ ГРАНИЦИТЕ ═══════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПЛАТЕНИЯТ отпуск с кофа 16.1 НЕ влиза в нито едно от двете подмножества")
  void paidLeaveNeverEntersEitherSubset() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(
            row("2026-12-07", "2026-12-11", "5", 1, Boolean.TRUE, 2L, Boolean.TRUE, "16.1", true));

    assertEquals(0, new BigDecimal("5").compareTo(days.paid), "платен отпуск");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(days.unpaidInD1),
        "⚠️ Кофата се пита САМО вътре в неплатената: платеният отпуск с попълнен d1Code е нормалното"
            + " състояние на речника (16.1), а събран тук, той би СВАЛИЛ от заплатата дни, които тя"
            + " дължи — и то мълчаливо.");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(days.outsideInsurance),
        "и не е извън осигуряването — той Е дни с осигурителни вноски, тоест самата т. 16.1");
  }

  @Test
  @DisplayName("Празна клетка и низ от интервали са ЕДНО И СЪЩО — „без кофа“")
  void blankBucketIsTreatedAsNoBucket() {
    assertEquals(
        0,
        new BigDecimal("5")
            .compareTo(leaveDaysOf(unpaid("2026-12-07", "2026-12-11", "5", "  ")).outsideInsurance),
        "празен низ след trim() е липсваща кофа, не кофа с име „ “");
  }

  @Test
  @DisplayName("Нула валидирани заявки → и новият подсбор е „не знам“, не нула")
  void noRequestsMeansUnknownNotZero() {
    assertNull(
        leaveDaysOf().outsideInsurance,
        "празна заявка дава сбор нула — същото число като „не знам“");
  }

  @Test
  @DisplayName("Отказ → и новият подсбор е null, а причината е назована")
  void arefusalInvalidatesTheSubsetsToo() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(
            row(
                "2026-12-07",
                "2026-12-11",
                "5",
                7,
                Boolean.FALSE,
                null,
                Boolean.FALSE,
                "16.5",
                true));

    assertTrue(days.isRefused(), "несъпоставеният вид отпуск остава отказ");
    assertNull(days.unpaidInD1, "отказът обезсилва и подкофата, не само трите");
    assertNull(days.outsideInsurance, "и новия подсбор — инак нулата му би минала за измерена");
    assertNotNull(days.refusal);
  }

  // ═══ FAIL-LOUD · РЕЧНИКЪТ, КОЙТО СИ ПРОТИВОРЕЧИ ══════════════════════════

  @Test
  @DisplayName("⚠️ Кофа + „не се зачита за стаж“ → НАЗОВАН ОТКАЗ, не тих избор коя половина печели")
  void abucketedTypeThatInterruptsInsuranceIsRefused() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(
            row(
                "2026-12-07",
                "2026-12-11",
                "5",
                7,
                Boolean.FALSE,
                12L,
                Boolean.FALSE,
                "16.5",
                false));

    assertTrue(
        days.isRefused(),
        "⚠️ Кофа 16.5 („неплатен отпуск, ЗАЧЕТЕН за осигурителен стаж“) и признак „не се зачита“ са"
            + " взаимно изключващи се твърдения. Прочетени тихо в която и да е посока, те или вадят"
            + " деня от т. 16.1 ДВА пъти, или го плащат срещу прекъснато осигуряване.");
    assertTrue(
        days.refusal.contains("countsAsInsuranceService"),
        "отказът сочи ИМЕННО двете колони, за да е поправим: " + days.refusal);
  }

  @Test
  @DisplayName("⚠️ Празна кофа + НЕПОПЪЛНЕН признак → отказ; null НЕ е „не се зачита“")
  void ablankInsuranceFlagIsARefusalNotADefault() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(
            row("2026-12-07", "2026-12-11", "5", 7, Boolean.FALSE, 12L, Boolean.FALSE, null, null));

    assertTrue(
        days.isRefused(),
        "⚠️ ТИХИЯТ ПРОХОД, КОЙТО ТОЗИ ОТКАЗ СПИРА: Boolean.FALSE.equals(null) е false, тоест вид без"
            + " кофа и без отметка щеше да МИНЕ през outsideInsurance и да се плати ЦЯЛ, а Д1 да го"
            + " брои в т. 16.1 — при положение че никой не е твърдял, че се зачита за стаж. Нов вид,"
            + " създаден от екрана, е точно този случай.");
  }

  @Test
  @DisplayName("⚠️ Празна кофа + „зачита се за стаж“ → отказ: дните нямат къде да се броят")
  void ableedingTypeWithNoBucketIsRefused() {
    BgPayrollInputBuilder.LeaveDays days =
        leaveDaysOf(
            row("2026-12-07", "2026-12-11", "5", 7, Boolean.FALSE, 12L, Boolean.FALSE, "", true));

    assertTrue(
        days.isRefused(),
        "вид, който се зачита за осигурителен стаж, но няма точка в т. 16 — дните му изчезват от"
            + " декларацията, а заплатата ги плаща. Двете не могат да се съгласуват тихо.");
  }

  // ═══ ЕДИН ПРЕДИКАТ, ДВА ЧИТАТЕЛЯ ═════════════════════════════════════════

  @Test
  @DisplayName("⚠️ КОТВА · петте вида БЕЗ кофа са точно карвиращото множество")
  void theUnbucketedFiveAreExactlyTheCarvedSet() {
    assertEquals(
        new HashSet<>(
            Arrays.asList(
                "unpaid_prod_gt30",
                "unpaid_own_gt30",
                "non_attendance",
                "awol",
                "sicknote_unsubmitted")),
        new HashSet<>(BgD1DayBuckets.UNBUCKETED),
        "⚠️ Списъкът е ОБХВАТЪТ: за тези пет вида заплатата"
            + " пада И т. 16.1 пада, ЗАЕДНО. Шести вид без кофа влиза в същия обхват сам — предикатът"
            + " чете речника, а този списък само го прави видим в ревюто.");
  }

  @Test
  @DisplayName("⚠️ Предикатът е ЕДИН и е конюнкция — нито гол флаг, нито гола празна кофа")
  void thepredicateIsTheConjunctionOfBothHalves() {
    assertTrue(
        BgD1DayBuckets.outsideInsurance(null, Boolean.FALSE), "празна кофа + не се зачита → извън");
    assertTrue(BgD1DayBuckets.outsideInsurance("   ", Boolean.FALSE), "и празен низ след trim()");
    assertTrue(
        !BgD1DayBuckets.outsideInsurance("16.5", Boolean.FALSE),
        "⚠️ КОФА + false НЕ Е „извън“. Денят вече излиза от т. 16.1 през кофата си; броен и тук, той"
            + " щеше да излезе ДВА пъти. Затова предикатът е конюнкция, а не гол флаг.");
    assertTrue(
        !BgD1DayBuckets.outsideInsurance(null, null),
        "⚠️ И НЕПОПЪЛНЕНИЯТ ФЛАГ НЕ Е „извън“ — той е противоречие и се обявява отделно");
    assertTrue(
        !BgD1DayBuckets.outsideInsurance(null, Boolean.TRUE),
        "празна кофа при признат стаж също не е „извън“ — и това е другото противоречие");

    assertTrue(BgD1DayBuckets.insuranceFlagsConflict("16.5", Boolean.FALSE), "кофа + не се зачита");
    assertTrue(BgD1DayBuckets.insuranceFlagsConflict(null, null), "празна кофа + непопълнен флаг");
    assertTrue(BgD1DayBuckets.insuranceFlagsConflict("", Boolean.TRUE), "празна кофа + зачита се");
    assertTrue(
        !BgD1DayBuckets.insuranceFlagsConflict("16.1", Boolean.TRUE), "нормалният ред — съгласен");
    assertTrue(
        !BgD1DayBuckets.insuranceFlagsConflict("", Boolean.FALSE), "и петте — също съгласни");
  }

  @Test
  @DisplayName("⚠️ Трите карвирани пера са точно тези — часовите НЕ спират при отказ")
  void onlyTheThreeDayCountedLinesAreCarved() {
    assertEquals(
        new HashSet<>(
            Arrays.asList(
                BgEarningsResolver.LINE_BASE_SALARY,
                BgEarningsResolver.LINE_SENIORITY,
                BgEarningsResolver.LINE_ACADEMIC_DEGREE)),
        new HashSet<>(BgEarningsResolver.CARVED_LINES),
        "⚠️ Седемте часови пера делят на НОРМАТА и на договорените часове — отпускът не мени нито"
            + " едно тяхно число. Включени тук, един несъпоставен вид отпуск щеше да махне от фиша"
            + " и нощния труд.");

    BgEarningsFacts refused =
        BgEarningsFacts.builder().leaveDaysRefusal("вид отпуск без съпоставяне").build();
    assertNull(
        BgEarningsResolver.carveSourceRefusal(BgEarningsResolver.LINE_NIGHT_WORK, refused),
        "нощният труд НЕ спира при отказ на отсъствията");
    assertNotNull(
        BgEarningsResolver.carveSourceRefusal(BgEarningsResolver.LINE_BASE_SALARY, refused),
        "основната СПИРА");
  }

  // ═══ СГЪВАНЕТО НА ГРАНИЦАТА ══════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Карвът е СБОРЪТ на двата дизюнктни подсбора — и се смята ВЕДНЪЖ")
  void thecarveIsTheSumOfBothSubsets() {
    assertEquals(
        0,
        new BigDecimal("7")
            .compareTo(
                BgEarningsService.carveDaysOf(
                    BgPayrollInput.builder()
                        .unpaidLeaveDaysInD1(new BigDecimal("4"))
                        .daysOutsideInsurance(new BigDecimal("3"))
                        .build())),
        "4 дни с кофа + 3 извън осигуряването = 7 карвирани дни");
    assertNull(BgEarningsService.carveDaysOf(null), "без вход няма карв");
    assertEquals(
        0,
        new BigDecimal("4")
            .compareTo(
                BgEarningsService.carveDaysOf(
                    BgPayrollInput.builder().unpaidLeaveDaysInD1(new BigDecimal("4")).build())),
        "⚠️ null на едното НЕ обезсилва другото — иначе отсъствие от единия вид щеше да върне"
            + " пълната заплата на човек с отсъствие от другия");
    assertEquals(
        0,
        new BigDecimal("3")
            .compareTo(
                BgEarningsService.carveDaysOf(
                    BgPayrollInput.builder().daysOutsideInsurance(new BigDecimal("3")).build())),
        "и обратното");
  }

  // ═══ ЧИСТАТА АРИТМЕТИКА ══════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Двата изваждаеми са в ЕДИН числител — две деления дават друго число")
  void theTwoSubtrahendsShareOneNumerator() {
    BigDecimal both =
        BgEarningsResolver.monthlyFixedAfterLeaveCarve(
            new BigDecimal("100.00"), new BigDecimal("5"), new BigDecimal("5"), 22);
    assertEquals(new BigDecimal("54.55"), both, "100 × (22 − 5 − 5) / 22 = 54,5454… → 54,55");

    BigDecimal onceThenAgain =
        BgEarningsResolver.monthlyFixedAfterLeaveCarve(
            BgEarningsResolver.monthlyFixedAfterLeaveCarve(
                new BigDecimal("100.00"), new BigDecimal("5"), null, 22),
            new BigDecimal("5"),
            null,
            22);
    assertEquals(
        new BigDecimal("59.71"),
        onceThenAgain,
        "⚠️ Верижното делене дава 59,71 — с 5,16 € над верните 54,55. Затова двата изваждаеми"
            + " пътуват заедно до един числител, а не се прилагат един след друг."
            + " ⚠️ И ЗАТОВА ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО НЕ СТАНАХА ТРЕТИ ПАРАМЕТЪР: те се сгъват в"
            + " unpaidCarveDays още в BgEarningsService, тоест числителят остава ЕДИН.");
  }

  @Test
  @DisplayName("Отрицателните платими дни са ОТКАЗ, а не нула заплата")
  void anOverrunIsARefusalNotZero() {
    assertNull(
        BgEarningsResolver.paidDaysAfterLeaveCarve(22, new BigDecimal("15"), new BigDecimal("10")),
        "15 + 10 > 22 → противоречие във входа");
    assertEquals(
        0,
        new BigDecimal("12")
            .compareTo(
                BgEarningsResolver.paidDaysAfterLeaveCarve(
                    22, new BigDecimal("5"), new BigDecimal("5"))),
        "22 − 5 − 5 = 12");
    assertEquals(
        0,
        new BigDecimal("22").compareTo(BgEarningsResolver.paidDaysAfterLeaveCarve(22, null, null)),
        "без отпуск платимите дни са отработените — БИТ ПО БИТ както преди");
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /**
   * Един ред на {@code leaveRows} — ЕДИНАЙСЕТ колони.
   *
   * @param credited {@code countsAsInsuranceService}; {@code null} е НЕПОПЪЛНЕНА клетка, не „не“
   */
  private static Object[] row(
      String from,
      String to,
      String days,
      Integer category,
      Boolean paid,
      Long absenceTypeId,
      Boolean art177,
      String d1Code,
      Boolean credited) {
    return new Object[] {
      LocalDate.parse(from).atStartOfDay(),
      LocalDate.parse(to).atTime(23, 59, 59),
      new BigDecimal(days),
      1,
      category,
      paid,
      absenceTypeId,
      art177,
      d1Code,
      credited,
      // ⚠️ ЕДИНАЙСЕТАТА колона: healthContributionRule. Всички фикстури тук са на видове с платец
      // по
      // ЗЗО чл. 40 ал. 1 т. 1 б. „б“, тоест подмножеството unpaidUnderZzo съвпада с кофата и нито
      // едно число на файла не мърда. Виж BgPayrollInput#unpaidLeaveDaysUnderZzo.
      BgUnpaidLeaveHealthResolver.RULE_UNPAID_EMPLOYEE
    };
  }

  /**
   * Неплатен отпуск (категория 7) с явно зададена кофа в Д1.
   *
   * <p>⚠️ Признакът СЛЕДВА кофата — точно както го изисква речникът: непразна кофа значи „зачита се
   * за осигурителен стаж“, празна значи „не се зачита“. Разминат, редът е противоречие и си има
   * свой свидетел по-горе.
   */
  private static Object[] unpaid(String from, String to, String days, String d1Code) {
    boolean bucketed = d1Code != null && !d1Code.trim().isEmpty();
    return row(from, to, days, 7, Boolean.FALSE, 12L, Boolean.FALSE, d1Code, bucketed);
  }

  /** Самоотлъчка/неприсъствие — категория 5, без кофа, не се зачита за стаж. */
  private static Object[] disciplinary(String from, String to, String days) {
    return row(from, to, days, 5, Boolean.FALSE, 25L, Boolean.FALSE, null, Boolean.FALSE);
  }

  /**
   * Категория 2 — болничните. {@code credited} дели ИСТИНСКИЯ болничен (зачита се, кофа 16.2) от
   * НЕПРЕДСТАВЕНИЯ (не се зачита, без кофа). Категорията е една и съща и точно затова не върши
   * работа като разграничител.
   */
  private static Object[] sickCategory(String from, String to, String days, boolean credited) {
    return row(
        from, to, days, 2, Boolean.FALSE, 21L, Boolean.FALSE, credited ? "16.2" : null, credited);
  }

  private static BgPayrollInputBuilder.LeaveDays leaveDaysOf(Object[]... rows) {
    Builder builder = new Builder(Arrays.asList(rows));
    EmploymentContract contract = new EmploymentContract();
    Employee employee = new Employee();
    employee.setId(7L);
    contract.setEmployee(employee);
    return builder.leaveDaysOf(employee, DEC_FROM, DEC_TO, new BigDecimal("8.00"));
  }

  /** Сглобител с подменени шевове — редовете на заявките и календарът. Без база и без Guice. */
  private static final class Builder extends BgPayrollInputBuilder {

    private final List<Object[]> rows;

    private Builder(List<Object[]> rows) {
      this.rows = rows;
    }

    @Override
    protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
      return rows;
    }

    @Override
    protected Set<LocalDate> officialHolidays(LocalDate from, LocalDate to) {
      Set<LocalDate> in = new HashSet<>();
      for (LocalDate day : HOLIDAYS) {
        if (!day.isBefore(from) && !day.isAfter(to)) {
          in.add(day);
        }
      }
      return in;
    }

    @Override
    protected Map<LocalDate, BigDecimal> workedHoursByDate(
        Employee employee, LocalDate from, LocalDate to) {
      return Collections.emptyMap();
    }
  }
}
