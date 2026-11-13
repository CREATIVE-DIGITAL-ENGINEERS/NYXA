package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
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
 * ЗАПЛАЩАНЕТО НА ПЛАТЕНИЯ ОТПУСК ДО ПАРИТЕ — КТ чл. 177 през целия път: карв в перата, перо 165,
 * тотал на фиша.
 *
 * <p><b>⚠️ ГЛАВНИЯТ СВИДЕТЕЛ Е ИНВАРИАНТЪТ, А НЕ НОВОТО ЧИСЛО.</b> Перото по чл. 177 не добавя пари
 * — то ги ПРЕМЕСТВА. При непроменен състав на възнаграждението и напълно отработен базов месец
 * сборът на фиша е ТОЧНО същият, какъвто е без перото. Ако този файл някога падне на инварианта,
 * значи карвът и перото са се разминали — тоест или се плаща двойно, или се недоплаща.
 *
 * <p><b>⚠️ И ТОЧНО ЗАТОВА ИНВАРИАНТЪТ СЕ МЕРИ И С ПЛОСКО ПЕРО.</b> Карв само на дневно-броените
 * пера (основна и клас), който оставя научната степен цяла, дава на лице с научна степен 100,00 € и
 * 5 дни отпуск при норма 22 с 22,73 € повече от дължимото (= 100 × 5 / 22), при това с вид на
 * пресметнато. Свидетелят {@link #theInvariantHoldsWithAFlatPermanentLine} пази точно тази
 * стотинка.
 */
class BgArt177LeaveMoneyTest {

  private static final LocalDate NOV_FROM = LocalDate.of(2026, 11, 1);
  private static final LocalDate NOV_TO = LocalDate.of(2026, 11, 30);
  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  /** Ноември 2026 — базовият месец на всички свидетели тук. */
  private static final int NOV_WORKING_DAYS = 21;

  /** Декември 2026 — месецът на ползване. */
  private static final int DEC_WORKING_DAYS = 22;

  private static final BigDecimal BASE_SALARY = new BigDecimal("1000.00");

  /** 0,6 на сто за всяка от 10 навършени години = 6 на сто = 60,00 €. */
  private static final BigDecimal SENIORITY_PCT = new BigDecimal("0.6000");

  private static final int SENIORITY_YEARS = 10;

  private static final BigDecimal MIN_WAGE = new BigDecimal("620.20");

  // ═══ ИНВАРИАНТЪТ ═════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ИНВАРИАНТ · основна + клас, 5 дни отпуск → тоталът е ТОЧНО както без чл. 177")
  void theInvariantHoldsForBaseAndSeniority() {
    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    BgPaySlip before = h.run(BigDecimal.ZERO);
    BgPaySlip after = h.run(new BigDecimal("5"));

    assertEquals(new BigDecimal("1000.00"), amountOf(before, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("60.00"), amountOf(before, BgEarningsResolver.LINE_SENIORITY));
    assertNull(amountOf(before, BgArt177LeaveService.LINE_ART177_LEAVE), "без отпуск няма перо");

    // Карв: 1000 × 17/22 = 772,7272… → 772,73 и 60 × 17/22 = 46,3636… → 46,36.
    assertEquals(new BigDecimal("772.73"), amountOf(after, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("46.36"), amountOf(after, BgEarningsResolver.LINE_SENIORITY));
    // Перото: карв 227,27 + 13,64 = 240,91, надбавка 0,00 → 240,91 = 1060 × 5 / 22.
    assertEquals(new BigDecimal("240.91"), amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE));

    assertEquals(
        gross(before),
        gross(after),
        "⚠️ ИНВАРИАНТЪТ: непроменено възнаграждение → същите пари, само преместени");
    assertEquals(new BigDecimal("1060.00"), gross(after));
    assertEquals(0, h.lastOutcome.uplift.compareTo(BigDecimal.ZERO), "надбавката е ТОЧНО нула");
  }

  @Test
  @DisplayName("⚠️ ИНВАРИАНТ · с ПЛОСКО перо (научна степен) тоталът пак не мърда")
  void theInvariantHoldsWithAFlatPermanentLine() {
    Harness h = new Harness();
    h.academicDegree = "DOCTOR_OF_SCIENCES";
    h.degreeAmount = new BigDecimal("100.00");
    h.baseMonthArt17 = new BigDecimal("1160.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    BgPaySlip before = h.run(BigDecimal.ZERO);
    BgPaySlip after = h.run(new BigDecimal("5"));

    assertEquals(new BigDecimal("1160.00"), gross(before), "1000 + 60 + 100");
    // ⚠️ ТОВА Е СМИСЪЛЪТ. Некарвирана, научната степен би останала 100,00 и тоталът би
    // излязъл 1182,73 — с 22,73 € (= 100 × 5 / 22) над дължимото.
    assertEquals(
        new BigDecimal("77.27"),
        amountOf(after, BgEarningsResolver.LINE_ACADEMIC_DEGREE),
        "100 × (22 − 5) / 22");
    assertEquals(
        new BigDecimal("263.64"),
        amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE),
        "карв 227,27 + 13,64 + 22,73 = 263,64 = 1160 × 5 / 22");
    assertEquals(new BigDecimal("1160.00"), gross(after), "⚠️ пак ТОЧНО както без чл. 177");
    assertEquals(0, h.lastOutcome.uplift.compareTo(BigDecimal.ZERO));
  }

  @Test
  @DisplayName("⚠️ РЕГРЕСИЯ · нула дни по чл. 177 → перата са бит по бит днешните")
  void zeroArt177DaysChangeNothing() {
    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    BgPaySlip slip = h.run(BigDecimal.ZERO);
    assertEquals(new BigDecimal("1000.00"), amountOf(slip, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("60.00"), amountOf(slip, BgEarningsResolver.LINE_SENIORITY));
    assertNull(amountOf(slip, BgArt177LeaveService.LINE_ART177_LEAVE));
    assertEquals(
        BgArt177Outcome.Status.NOT_APPLICABLE,
        h.lastOutcome.status,
        "месец без отпуск по чл. 177 е НОРМАЛНО състояние, не отказ");
  }

  // ═══ КОГАТО ПАРИТЕ НАИСТИНА МЪРДАТ ═══════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПАРИ · по-богат базов месец плаща отпуска ПОВЕЧЕ, реконсилирано на ръка")
  void aRicherBaseMonthPaysMore() {
    Harness h = new Harness();
    // Ноември е носил и постоянен бонус 210,00 (чл. 17 ал. 1 т. 3): 1000 + 60 + 210 = 1270,00.
    h.baseMonthArt17 = new BigDecimal("1270.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    BgPaySlip after = h.run(new BigDecimal("5"));

    // Среднодневно 1270 ÷ 21 = 60,476190…; коефициент 21/22; × 5 дни = 1270 × 5 / 22 = 288,6363…
    assertEquals(
        new BigDecimal("288.64"),
        amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE),
        "карв 240,91 + надбавка 47,73");
    assertEquals(new BigDecimal("47.73"), h.lastOutcome.uplift, "210 × 5 / 22 = 47,7272… → 47,73");
    assertEquals(
        new BigDecimal("1107.73"),
        gross(after),
        "⚠️ 1060,00 днес + 47,73 — ТОЧНО частта на бонуса, падаща върху петте отпускови дни");
  }

  @Test
  @DisplayName("⚠️ ПАРИ · коефициентът по чл. 18 ал. 2 мести числото, когато нормите се различават")
  void theCoefficientMovesTheNumber() {
    Harness h = new Harness();
    h.baseMonthWorkingDays = 20;
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("20");
    h.usageWorkingDays = 23;
    // ⚠️ И ОТРАБОТЕНИТЕ, не само нормата: месецът на ползване е изживян изцяло, тоест 23 зачетени
    // дни, от които 5 в отпуск. Оставени на 22, свидетелят щеше да мери и един ден отсъствие.
    h.workedDays = 23;

    BgPaySlip after = h.run(new BigDecimal("5"));

    // Среднодневно 1060 ÷ 20 = 53,00; коефициент 20/23 → 1060 × 5 / 23 = 230,4347… → 230,43.
    // ⚠️ БЕЗ коефициента същите дни биха дали 53,00 × 5 = 265,00 — с 34,57 € повече.
    assertEquals(new BigDecimal("230.43"), amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE));
    // Карв: 1000 × 18/23 = 782,6086 → 782,61; 60 × 18/23 = 46,9565 → 46,96.
    assertEquals(new BigDecimal("782.61"), amountOf(after, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("46.96"), amountOf(after, BgEarningsResolver.LINE_SENIORITY));
    assertEquals(
        new BigDecimal("1060.00"),
        gross(after),
        "⚠️ базовият месец е носил същото → тоталът пак не мърда, колкото и да се различават нормите");
  }

  @Test
  @DisplayName(
      "⚠️ РЕЗЕРВЕН РЕД · базов месец с под 10 отработени дни → чл. 177 ал. 2, БЕЗ коефициент")
  void theFallbackAppliesUnderTenWorkedDays() {
    Harness h = new Harness();
    // Ноември: 21 зачетени дни, но 13 от тях са отпуск → 8 отработени, под прага на ал. 1.
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("8");
    h.workingDaysInYear = 249;

    BgPaySlip after = h.run(new BigDecimal("5"));

    // Уговорено 1000 + 60 = 1060,00; средномесечно 249 ÷ 12 = 20,75 → 1060 × 5 / 20,75 = 255,4216…
    assertEquals(
        new BigDecimal("255.42"),
        amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE),
        "карв 240,91 + надбавка 14,51");
    assertEquals(
        new BigDecimal("1074.51"),
        gross(after),
        "⚠️ ЗАКОННО различен от 1060,00: знаменателят е ГОДИШНАТА средна, не нормата на месеца");
    assertTrue(h.lastOutcome.basis.contains("ал. 2"), "основанието се записва на перото");
  }

  @Test
  @DisplayName("⚠️ РЕЗЕРВЕН РЕД · нов служител без нито един предходен фиш минава по същия ред")
  void aBrandNewEmployeeUsesTheFallback() {
    Harness h = new Harness();
    h.earlierSlips = Collections.emptyList();
    h.workingDaysInYear = 249;

    BgPaySlip after = h.run(new BigDecimal("5"));
    assertEquals(new BigDecimal("255.42"), amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE));
    assertTrue(h.lastOutcome.basis.contains("БЕЗ коефициент"));
  }

  // ═══ ПОДЪТ ПО ЧЛ. 19 АЛ. 2 ═══════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ПОД · беден базов месец се вдига до МРЗ-еквивалента — 50,05 € разлика")
  void theStatutoryFloorLiftsALowBaseMonth() {
    // Базовият месец е носил само 400,00 € начислено при 21 отработени дни → среднодневно
    // 400 ÷ 21 = 19,05, коригирано 400 × 21 / (21 × 22) = 18,18 на ден. Подът по чл. 19 ал. 2 е
    // 620,20 ÷ 22 = 28,19 на ден и е ПО-ВИСОКИЯТ, тоест хапе.
    Harness full = new Harness();
    full.baseMonthArt17 = new BigDecimal("400.00");
    full.baseMonthWorkedDays = new BigDecimal("21");
    BgPaySlip withFloor = full.run(new BigDecimal("5"));

    // Същият фиш при НЕПЪЛЕН работен ден: подът не се прилага изобщо (чл. 19 ал. 2 казва „при
    // пълен работен ден“), тоест същият беден базов месец минава некоригиран.
    Harness part = new Harness();
    part.contractedHoursPerDay = new BigDecimal("4.00");
    part.baseMonthArt17 = new BigDecimal("400.00");
    part.baseMonthWorkedDays = new BigDecimal("21");
    BgPaySlip withoutFloor = part.run(new BigDecimal("5"));

    assertEquals(
        new BigDecimal("140.96"),
        amountOf(withFloor, BgArt177LeaveService.LINE_ART177_LEAVE),
        "карв 240,91 + надбавка −99,95 (подът 620,20 × 5 / 22 срещу вече платените 1060 × 5 / 22)");
    assertEquals(
        new BigDecimal("90.91"),
        amountOf(withoutFloor, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ ЦЕНАТА НА ЛИПСВАЩИЯ ПОД, ИЗМЕРЕНА В ЕВРО: 50,05 € по-малко за същите пет дни."
            + " Тук това е ВЕРНИЯТ изход — МРЗ е за пълен работен ден и наложена на половин щат"
            + " би платила отпуска двойно.");
  }

  @Test
  @DisplayName("⚠️ ПОД · при нормален базов месец подът не хапе и не мести нищо")
  void theFloorIsInertOnANormalBaseMonth() {
    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    BgPaySlip after = h.run(new BigDecimal("5"));
    assertEquals(
        new BigDecimal("240.91"),
        amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ заплата над МРЗ → подът е под среднодневното и ИНВАРИАНТЪТ остава цял");
    assertEquals(new BigDecimal("1060.00"), gross(after));
  }

  // ═══ РЪЧНОТО ПОСТОЯННО ПЕРО ══════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ИНВАРИАНТ · ръчното постоянно перо КАРВИРА като другите, а нето нула оцелява")
  void aManualPermanentLineIsCarvedLikeTheOthers() {
    Harness h = new Harness();
    h.manualPermanentAllowance = new BigDecimal("210.00");
    h.baseMonthArt17 = new BigDecimal("1270.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    BgPaySlip before = h.run(BigDecimal.ZERO);
    BgPaySlip after = h.run(new BigDecimal("5"));

    // ⚠️ Перото не може да остане непокътнато с дял, приспаднат ВЪТРЕ в надбавката: за чл. 177
    // това дава верни пари, но при НЕПЛАТЕН отпуск няма надбавка, която да поеме приспадането, и
    // същият ред надплаща сума × дни / норма. Затова карвира ДИРЕКТНО, както основната и
    // степента, а надбавката връща пълния дял. Местят се границите между два реда, не парите —
    // долният ред го доказва.
    assertEquals(
        new BigDecimal("162.27"),
        amountOf(after, MANUAL_BONUS),
        "210 × 17/22 = 162,2727… → 162,27");
    assertEquals(
        new BigDecimal("210.00"),
        lineOf(after, MANUAL_BONUS).getBgEnteredAmount(),
        "⚠️ ВЪВЕДЕНОТО ОСТАВА ВЪЗСТАНОВИМО — това е разликата между карв и загуба на данни."
            + " Второто prepare() чете него, не намаления ред.");
    assertEquals(
        new BigDecimal("288.64"),
        amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE),
        "карв 227,27 + 13,64 + 47,73 = 288,64 (бонусът е вътре)");
    assertEquals(
        0,
        h.lastOutcome.uplift.compareTo(BigDecimal.ZERO),
        "⚠️ НАДБАВКАТА ОСТАВА ТОЧНО НУЛА, защото M НЕ мърда: permanentAllowances чете въведената"
            + " сума, не карвирания ред. Прочетена от реда, дневната ставка M/норма щеше да падне и"
            + " надбавката да излезе завишена точно в месеца с отпуск.");

    assertEquals(gross(before), gross(after), "⚠️ ИНВАРИАНТЪТ държи и с ръчно постоянно перо");
    assertEquals(
        new BigDecimal("1270.00"),
        gross(after),
        "772,73 + 46,36 + 162,27 + 288,64 — същите 1270,00 като преди клона, друга подредба");
  }

  // ═══ ЧЛ. 17 АЛ. 1 Т. 5–7 · ПРЕСТОЯТ ══════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЧЛ. 17 АЛ. 1 Т. 6 · престой в БАЗОВИЯ месец вдига отпуска с 47,73")
  void stoppagePayInTheBaseMonthRaisesTheLeavePay() {
    Harness control = new Harness();
    control.baseMonthArt17 = new BigDecimal("1060.00");
    control.baseMonthWorkedDays = new BigDecimal("21");
    BgPaySlip without = control.run(new BigDecimal("5"));

    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthStoppagePay = new BigDecimal("210.00");
    h.baseMonthWorkedDays = new BigDecimal("21");
    BgPaySlip with = h.run(new BigDecimal("5"));

    // ═══ СМЕТКАТА, ИЗПИСАНА ЦЯЛА, ЗАЩОТО Е ПРАВНО ПРОВЕРИМА ═══
    // Базов месец: ноември, 21 РАБОТНИ дни по календар, 21 ОТРАБОТЕНИ (без отсъствия).
    // База по чл. 17: 1060,00 постоянни + 210,00 престой (т. 6) = 1270,00.
    // Среднодневно по чл. 18 ал. 1 изр. 1:  1270,00 ÷ 21 отработени      = 60,4762 €/ден
    // Коефициент по чл. 18 ал. 2:           21 (базов) ÷ 22 (декември)   = 0,9545
    // Слети в едно деление (§ 5.3):  (1270 × 21) ÷ (21 × 22) = 1270 ÷ 22 = 57,7272 €/ден
    // Право за 5 дни: 1270 × 5 ÷ 22 = 288,6363… → 288,64.
    // ⚠️ ДВЕТЕ 21 СЕ СЪКРАЩАВАТ, защото месецът е отработен ЦЯЛ — не защото делителят е нормата на
    // декември. При непълно отработен базов месец те не се съкращават и коефициентът хапе.
    assertEquals(
        0,
        new BigDecimal("288.64").compareTo(h.lastOutcome.entitlement),
        "правото по чл. 177 за петте дни. Намерено: " + h.lastOutcome.entitlement);

    assertEquals(
        new BigDecimal("288.64"),
        amountOf(with, BgArt177LeaveService.LINE_ART177_LEAVE),
        "карв 240,91 + надбавка 47,73");
    assertEquals(
        new BigDecimal("240.91"),
        amountOf(without, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ КОНТРОЛАТА: същият фиш БЕЗ престой в базовия месец. Разликата между двата реда е"
            + " ЕДИНСТВЕНО престоят.");

    // Надбавката е разликата, която престоят създава: (1270 − 1060) × 5 ÷ 22 = 47,7272… → 47,73.
    assertEquals(
        0,
        new BigDecimal("47.73").compareTo(h.lastOutcome.uplift),
        "⚠️ 210 × 5 ÷ 22. НУЛА значи, че базата е прочетена само през bgIsPermanentCharacter и"
            + " престоят е паднал извън нея — тоест отпускът се плаща по среднодневно, изчислено"
            + " върху непълна база. Намерено: "
            + h.lastOutcome.uplift);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(control.lastOutcome.uplift),
        "без престой надбавката си остава ТОЧНО нула — нето нула на ръчното перо не е пипнато");

    assertEquals(new BigDecimal("1060.00"), gross(without));
    assertEquals(
        new BigDecimal("1107.73"),
        gross(with),
        "772,73 + 46,36 + 288,64. Престоят в ноември струва 47,73 € ПОВЕЧЕ отпуск в декември");
  }

  @Test
  @DisplayName("⚠️ ОБЯВАТА · бележката на перото казва ЗАЩО среднодневното е такова")
  void theNoteNamesTheStoppageComponentOfTheBase() {
    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthStoppagePay = new BigDecimal("210.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    BgPaySlip with = h.run(new BigDecimal("5"));
    String note = lineOf(with, BgArt177LeaveService.LINE_ART177_LEAVE).getNote();

    assertTrue(
        note.contains("210.00") && note.contains("чл. 267"),
        "⚠️ ПРЕСТОЯТ ЖИВЕЕ НА ДРУГ ФИШ — на базовия месец. Две лица с еднаква заплата получават"
            + " различен отпуск и нищо на екрана не казва защо; проверката минава през отваряне на"
            + " миналия фиш и събиране на редове наум. Намерено: "
            + note);

    Harness control = new Harness();
    control.baseMonthArt17 = new BigDecimal("1060.00");
    control.baseMonthWorkedDays = new BigDecimal("21");
    String plain =
        lineOf(control.run(new BigDecimal("5")), BgArt177LeaveService.LINE_ART177_LEAVE).getNote();
    assertTrue(
        !plain.contains("чл. 267"),
        "⚠️ И НЕ СЕ ПИШЕ ПРИ НУЛА. Постоянно „в т.ч. престой: 0,00 €“ е шум, а шумът спира да се"
            + " чете — тогава редът, който има значение, минава незабелязан. Намерено: "
            + plain);
  }

  @Test
  @DisplayName("⚠️ РЪБ · престой и отпуск в ЕДИН месец: не в M, не се карвира, не е в чл. 7")
  void stoppageInTheUsageMonthStaysOutOfEveryOtherBase() {
    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("21");
    h.currentMonthStoppagePay = new BigDecimal("210.00");

    BgPaySlip slip = h.run(new BigDecimal("5"));

    assertEquals(
        new BigDecimal("210.00"),
        amountOf(slip, STOPPAGE),
        "⚠️ НЕ СЕ КАРВИРА, И ТОВА НЕ Е ПРОПУСК. Дните престой и дните отпуск са НЕСЪВМЕСТИМИ по"
            + " природа — човек е или в престой, или в отпуск. Карвиран, редът щеше да плати"
            + " по-малко за СВОИТЕ дни заради отсъствие в ДРУГИ дни.");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(h.lastOutcome.uplift),
        "⚠️ И НЕ ВЛИЗА В M. M значи „колко фишът вече плаща на ВСЕКИ работен ден“; престоят се"
            + " плаща за своите дни. Влязъл в M, дневната ставка M/норма щеше да порасне до"
            + " 1270/22 и надбавката да излезе ОТРИЦАТЕЛНА — тоест собственият престой на лицето"
            + " щеше да му НАМАЛИ отпуска. Намерено: "
            + h.lastOutcome.uplift);
    assertEquals(
        new BigDecimal("240.91"),
        amountOf(slip, BgArt177LeaveService.LINE_ART177_LEAVE),
        "перото е същото като без престой в текущия месец");
    assertEquals(
        new BigDecimal("1270.00"),
        gross(slip),
        "772,73 + 46,36 + 210,00 + 240,91 — престоят си стои цял над нето-нулевия отпуск");
  }

  @Test
  @DisplayName("⚠️ ЧЛ. 7 · часовата база НЕ вижда престоя — извънредният труд не поскъпва")
  void theHourlyRateBaseNeverSeesTheStoppage() {
    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("21");
    h.currentMonthStoppagePay = new BigDecimal("210.00");

    BgPaySlip slip = h.run(new BigDecimal("5"));

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(h.earnings().permanentAllowances(slip)),
        "⚠️ ТОВА Е ПРИЧИНАТА ЗА ТРЕТИ ФЛАГ, А НЕ ЗА ОТМЕТКА НА ПОСТОЯННИЯ ХАРАКТЕР. Базата по НСОРЗ"
            + " чл. 7 минава през permanentAllowances → permanentPayBase и чете"
            + " bgIsPermanentCharacter. Отметнат за престоя, той щеше да вдигне часовата ставка на"
            + " нощния и извънредния труд — пари, преместени в съвсем друго перо, за да се поправи"
            + " отпускът. Новата колона няма читател по този път. Намерено: "
            + h.earnings().permanentAllowances(slip));
  }

  @Test
  @DisplayName("⚠️ МИГРАЦИЯ · заварен ред без новия флаг влиза през постоянния характер")
  void alegacyLineWithoutTheNewFlagStillCounts() {
    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("21");
    h.legacyBaseMonthLines = true;

    BgPaySlip slip = h.run(new BigDecimal("5"));

    assertEquals(
        new BigDecimal("240.91"),
        amountOf(slip, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ ДИЗЮНКЦИЯТА Е МИГРАЦИЯТА. Всеки ред, създаден преди колоната, я носи празна. Прочетена"
            + " строго, базата на ВСЕКИ исторически базов месец ставаше нула — среднодневно нула и"
            + " отпуск по пода от МРЗ, за всяко лице наведнъж. Заварените редове дават точно"
            + " днешното си число и нула backfill.");
    assertEquals(new BigDecimal("1060.00"), gross(slip), "и брутото е непокътнато");
  }

  @Test
  @DisplayName("⚠️ ЧЛ. 17 АЛ. 1 Т. 2 · резултатите в БАЗОВИЯ месец вдигат отпуска; 185 — не")
  void performancePayInTheBaseMonthRaisesTheLeavePayButTheOneOffDoesNot() {
    Harness control = new Harness();
    control.baseMonthArt17 = new BigDecimal("1060.00");
    control.baseMonthWorkedDays = new BigDecimal("21");
    BgPaySlip plain = control.run(new BigDecimal("5"));

    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthPerformancePay = new BigDecimal("210.00");
    // ⚠️ И ЕДНОКРАТЕН БОНУС В СЪЩИЯ БАЗОВ МЕСЕЦ — той НЕ бива да мени нищо. Без него свидетелят
    // щеше да мине зелен и при флаг, вдигнат на всяко ръчно перо.
    h.baseMonthOneOffBonus = new BigDecimal("500.00");
    h.baseMonthWorkedDays = new BigDecimal("21");
    BgPaySlip with = h.run(new BigDecimal("5"));

    // База: 1060,00 постоянни + 210,00 резултати (т. 2) = 1270,00. Еднократните 500,00 са ВЪН.
    // Среднодневно: (1270 × 21) ÷ (21 × 22) = 1270 ÷ 22 = 57,7272 €/ден. Право: 288,64.
    assertEquals(
        0,
        new BigDecimal("288.64").compareTo(h.lastOutcome.entitlement),
        "⚠️ АКО ТУК ИЗЛЕЗЕ 515,15, ЕДНОКРАТНИЯТ БОНУС Е ВЛЯЗЪЛ В БАЗАТА — (1770 × 21) ÷ (21 × 22)."
            + " Намерено: "
            + h.lastOutcome.entitlement);
    assertEquals(
        new BigDecimal("288.64"),
        amountOf(with, BgArt177LeaveService.LINE_ART177_LEAVE),
        "карв 240,91 + надбавка 47,73 = 210 × 5 ÷ 22");
    assertEquals(
        new BigDecimal("240.91"),
        amountOf(plain, BgArt177LeaveService.LINE_ART177_LEAVE),
        "контролата без 190 и без 185");

    // ⚠️ И ДВЕТЕ РЪЧНИ ПЕРА ОСТАВАТ ЦЕЛИ В ТЕКУЩИЯ МЕСЕЦ — те са на БАЗОВИЯ фиш, не на този.
    assertEquals(
        new BigDecimal("1107.73"),
        gross(with),
        "772,73 + 46,36 + 288,64. Резултатите от ноември струват 47,73 € повече отпуск в декември");
  }

  @Test
  @DisplayName("⚠️ Т. 2 В ТЕКУЩИЯ МЕСЕЦ · 190 не се карвира, не е в M, не е в чл. 7")
  void performancePayInTheUsageMonthBehavesLikeTheStoppage() {
    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("21");
    h.currentMonthPerformancePay = new BigDecimal("210.00");

    BgPaySlip slip = h.run(new BigDecimal("5"));

    assertEquals(
        new BigDecimal("210.00"),
        amountOf(slip, PERFORMANCE),
        "⚠️ НЕ СЕ КАРВИРА: карвът чете bgIsPermanentCharacter, а 190 не е постоянно. Изплатеното"
            + " за резултат не се намалява заради отпуск — резултатът вече е постигнат.");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(h.lastOutcome.uplift),
        "⚠️ И НЕ ВЛИЗА В M — иначе собствените резултати на лицето щяха да му НАМАЛЯТ отпуска."
            + " Намерено: "
            + h.lastOutcome.uplift);
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(h.earnings().permanentAllowances(slip)),
        "⚠️ И НЕ Е В ЧАСОВАТА БАЗА ПО ЧЛ. 7 — точно както престоя. Намерено: "
            + h.earnings().permanentAllowances(slip));
    assertEquals(
        new BigDecimal("1270.00"),
        gross(slip),
        "772,73 + 46,36 + 210,00 + 240,91 — резултатите стоят цели над нето-нулевия отпуск");
  }

  @Test
  @DisplayName("⚠️ ЕКРАНЪТ · ръчен престой 210 през save → отпускът се вдига с 47,73, а не пада")
  void aStoppageLineEnteredFromTheScreenStillReachesTheArt17Base() {
    Harness broken = new Harness();
    broken.baseMonthArt17 = new BigDecimal("1060.00");
    broken.baseMonthWorkedDays = new BigDecimal("21");
    broken.baseMonthStoppagePay = new BigDecimal("210.00");
    broken.baseMonthStoppageFromScreen = true;
    broken.skipTreatmentFill = true;
    BgPaySlip without = broken.run(new BigDecimal("5"));

    Harness fixed = new Harness();
    fixed.baseMonthArt17 = new BigDecimal("1060.00");
    fixed.baseMonthWorkedDays = new BigDecimal("21");
    fixed.baseMonthStoppagePay = new BigDecimal("210.00");
    fixed.baseMonthStoppageFromScreen = true;
    BgPaySlip with = fixed.run(new BigDecimal("5"));

    assertEquals(
        new BigDecimal("240.91"),
        amountOf(without, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ ДЕФЕКТЪТ, ЗАКОВАН: редът е записан от екрана с bgIsArt17Base = false, тоест 210,00"
            + " изпадат от базата по чл. 17 и отпускът се смята върху 1060,00 вместо върху 1270,00.");
    assertEquals(
        new BigDecimal("288.64"),
        amountOf(with, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ И ПОПРАВКАТА: fillTreatmentFromType преписва флага от речника при записа, базата става"
            + " 1270,00 и отпускът се връща на верния си размер.");

    assertEquals(
        new BigDecimal("47.73"),
        amountOf(with, BgArt177LeaveService.LINE_ART177_LEAVE)
            .subtract(amountOf(without, BgArt177LeaveService.LINE_ART177_LEAVE)),
        "⚠️ 210 × 5 ÷ 22 — СЪЩОТО число като при престоя в базовия месец, само че обратно. Пари"
            + " СЕ МЕСТЯТ, и това е вярната посока: престоят Е в базата по чл. 17 ал. 1 т. 6 и не"
            + " бива да изпада от нея, когато перото е въведено от екрана.");
  }

  // ═══ ОТКАЗИТЕ ════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ БОГАТ ТЕКУЩ МЕСЕЦ · дава ПЪЛНОТО право по чл. 177, а НЕ отказ")
  void aRicherCurrentMonthNowPaysTheFullEntitlementInsteadOfRefusing() {
    Harness h = new Harness();
    // Текущият месец носи ръчен бонус 2000,00, какъвто базовият не е носил. Надбавката излиза
    // отрицателна; ако бонусът не се карвира, карвът не я покрива и сборът пада под нула. Бонусът
    // се карвира и сборът е точно СТАВКА × ДНИ.
    h.manualPermanentAllowance = new BigDecimal("2000.00");
    h.baseMonthArt17 = new BigDecimal("1270.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    BgPaySlip after = h.run(new BigDecimal("5"));

    assertEquals(
        BgArt177Outcome.Status.COMPUTED,
        h.lastOutcome.status,
        "⚠️ ОТКАЗЪТ БЕШЕ СИМПТОМ НА FOLD-А, НЕ НА ДАННИТЕ. Причина: " + h.lastOutcome.detail);

    // Карв: 227,27 (основна) + 13,64 (клас) + 454,55 (бонус: 2000 − 2000 × 17/22) = 695,46.
    // Надбавка: (1270/22 − 3060/22) × 5 = −406,8181… → −406,82.
    // Сбор: 695,46 − 406,82 = 288,64 — ТОЧНО правото по чл. 177 за петте дни.
    assertEquals(
        new BigDecimal("288.64"),
        amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE),
        "⚠️ И ТОВА Е ПРАВИЛНОТО ЧИСЛО ПО НОРМАТА, не компромис: чл. 177 плаща по БАЗОВИЯ месец"
            + " („последния календарен месец, предхождащ ползването“), а не по текущия. По-богат"
            + " текущ месец значи, че лицето получава за отпусковите дни ПО-МАЛКО, отколкото ако"
            + " ги беше отработило — и точно това нормата казва.");
    assertEquals(
        0,
        h.lastOutcome.uplift.signum() < 0 ? 0 : 1,
        "надбавката СИ ОСТАВА отрицателна (−406,82) — тя е само едната съставка");
    assertEquals(
        0,
        h.lastOutcome.entitlement.compareTo(
            amountOf(after, BgArt177LeaveService.LINE_ART177_LEAVE)),
        "⚠️ КОГАТО ВСИЧКО Е КАРВИРАНО, карв + надбавка Е правото. Разминаване тук значи, че карвът"
            + " и надбавката са смятани върху различни числа. Право: "
            + h.lastOutcome.entitlement);
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ · дни отпуск над отработените спират сметката НАЗОВАНО")
  void moreLeaveDaysThanWorkedDaysRefuseLoudly() {
    Harness h = new Harness();
    h.workedDays = 4;
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    h.run(new BigDecimal("5"));
    assertEquals(BgArt177Outcome.Status.REFUSED, h.lastOutcome.status);
    assertTrue(
        h.lastOutcome.detail.contains("надхвърлят отработените"),
        "причината назовава КОЕ противоречи, а не просто „не можах“: " + h.lastOutcome.detail);
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ · непокрит производствен календар за базовия месец не приема коеф. 1")
  void anUncoveredCalendarRefusesRatherThanAssumingOne() {
    Harness h = new Harness();
    h.baseMonthCalendarCovered = false;
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    h.run(new BigDecimal("5"));
    assertEquals(BgArt177Outcome.Status.REFUSED, h.lastOutcome.status);
    assertTrue(h.lastOutcome.detail.contains("коефициент"));
  }

  @Test
  @DisplayName("ИДЕМПОТЕНТНО · второ минаване не удвоява нито карва, нито перото")
  void theChainIsIdempotent() {
    Harness h = new Harness();
    h.baseMonthArt17 = new BigDecimal("1060.00");
    h.baseMonthWorkedDays = new BigDecimal("21");

    BgPaySlip slip = h.run(new BigDecimal("5"));
    BigDecimal first = gross(slip);
    h.prepareOn(slip, new BigDecimal("5"));
    assertEquals(first, gross(slip), "второ prepare() дава същия фиш");
    assertEquals(new BigDecimal("772.73"), amountOf(slip, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(new BigDecimal("240.91"), amountOf(slip, BgArt177LeaveService.LINE_ART177_LEAVE));
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /**
   * Фишът и двата генератора, навързани БЕЗ база.
   *
   * <p>⚠️ Навързват се ИСТИНСКИТЕ услуги, а не имитации: находката на този файл е между тях — че
   * карвът на енджина за начисления и сумата на перото по чл. 177 се компенсират. Имитирана, всяка
   * от двете страни щеше да доказва само себе си.
   */
  private final class Harness {

    BigDecimal baseMonthArt17;
    BigDecimal baseMonthWorkedDays;
    int baseMonthWorkingDays = NOV_WORKING_DAYS;
    int usageWorkingDays = DEC_WORKING_DAYS;
    int workedDays = DEC_WORKING_DAYS;
    int workingDaysInYear = 249;
    boolean baseMonthCalendarCovered = true;
    String academicDegree;
    BigDecimal degreeAmount;
    BigDecimal contractedHoursPerDay = new BigDecimal("8.00");
    BigDecimal manualPermanentAllowance;

    /** Престой в БАЗОВИЯ месец — влиза в базата по чл. 17 ал. 1 т. 6, вдига среднодневното. */
    BigDecimal baseMonthStoppagePay;

    /**
     * Редът за престой да дойде КАКТО ГО ЗАПИСВА ЕКРАНЪТ — с третиране на {@code default="false"}.
     * Тогава фишът минава през {@code BgPaySlipManagementRepository#fillTreatmentFromType}, точно
     * както в производството.
     */
    boolean baseMonthStoppageFromScreen;

    /** Да НЕ мине през репозитория — така се заковава грешката, а не само правилният път. */
    boolean skipTreatmentFill;

    /** Престой в ТЕКУЩИЯ месец — НЕ влиза в M, НЕ се карвира, НЕ е в базата по чл. 7. */
    BigDecimal currentMonthStoppagePay;

    /** Базовият месец да носи редове ОТПРЕДИ колоната — bgIsArt17Base празно. */
    boolean legacyBaseMonthLines;

    /** Възнаграждение за резултати в БАЗОВИЯ месец — т. 2, вдига среднодневното. */
    BigDecimal baseMonthPerformancePay;

    /** Еднократен бонус в БАЗОВИЯ месец — ИЗВЪН базата, не бива да мени нищо. */
    BigDecimal baseMonthOneOffBonus;

    /** Резултати в ТЕКУЩИЯ месец — не се карвират, не влизат в M, не са в чл. 7. */
    BigDecimal currentMonthPerformancePay;

    List<BgPaySlip> earlierSlips;

    BgArt177Outcome lastOutcome;

    BgPaySlip run(BigDecimal art177Days) {
      BgPaySlip slip = draft();
      prepareOn(slip, art177Days);
      return slip;
    }

    void prepareOn(BgPaySlip slip, BigDecimal art177Days) {
      BgPayrollInput input = input(art177Days);
      earnings().generate(slip, input);
      lastOutcome = art177().generate(slip, input);
    }

    private BgPaySlip draft() {
      BgPaySlip slip = new BgPaySlip();
      slip.setStatusSelect(BgPaySlipResolver.DRAFT);
      slip.setPeriod(period(DEC_FROM, DEC_TO));
      slip.setBgBaseSalary(BASE_SALARY);
      slip.setBgWorkedDays(workedDays);
      slip.setBgWorkingDaysInPeriod(usageWorkingDays);
      slip.setEmploymentContract(contract(contractedHoursPerDay));
      slip.setBgPaySlipLineList(new ArrayList<>());
      if (manualPermanentAllowance != null) {
        slip.getBgPaySlipLineList().add(newLine(typeOf(MANUAL_BONUS), manualPermanentAllowance));
      }
      if (currentMonthStoppagePay != null) {
        slip.getBgPaySlipLineList().add(newLine(typeOf(STOPPAGE), currentMonthStoppagePay));
      }
      if (currentMonthPerformancePay != null) {
        slip.getBgPaySlipLineList().add(newLine(typeOf(PERFORMANCE), currentMonthPerformancePay));
      }
      return slip;
    }

    private BgPayrollInput input(BigDecimal art177Days) {
      return BgPayrollInput.builder()
          .parameterReferenceDate(DEC_FROM)
          .art177LeaveDays(art177Days)
          .paidLeaveDays(art177Days)
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
      final Harness self = this;
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
          if (self.earlierSlips != null) {
            return self.earlierSlips;
          }
          return Collections.singletonList(baseMonthSlip(self.baseMonthArt17));
        }

        @Override
        protected BigDecimal absenceDaysOf(EmploymentContract contract, Period period) {
          // Отработените на базовия месец се задават направо: bgWorkedDays минус отсъствията.
          return BigDecimal.valueOf(NOV_WORKING_DAYS).subtract(self.baseMonthWorkedDays);
        }

        @Override
        protected Integer workingDaysOf(LocalDate from, LocalDate to) {
          // Годишният интервал се различава от месечния по дължината си — един шев, два въпроса.
          if (from.getMonthValue() == 1 && to.getMonthValue() == 12) {
            return self.workingDaysInYear;
          }
          return self.baseMonthCalendarCovered ? self.baseMonthWorkingDays : null;
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

    private BgPaySlip baseMonthSlip(BigDecimal art17Total) {
      BgPaySlip slip = new BgPaySlip();
      slip.setStatusSelect("VALIDATED");
      slip.setReference("BGPS-2026-11-001");
      slip.setPeriod(period(NOV_FROM, NOV_TO));
      slip.setBgWorkedDays(NOV_WORKING_DAYS);
      slip.setBgPaySlipLineList(new ArrayList<>());
      // ⚠️ Един ред с постоянен характер, носещ ЦЕЛИЯ начислен сбор по чл. 17. Разбит на пера,
      // той би доказвал същото; слят, свидетелят казва точно колко е базата и нищо повече.
      BgPaySlipLine line = newLine(typeOf(BgEarningsResolver.LINE_BASE_SALARY), art17Total);
      line.setBgIsPermanentCharacter(true);
      line.setBgIsArt17Base(!legacyBaseMonthLines);
      slip.getBgPaySlipLineList().add(line);
      // ⚠️ ВТОРИ РЕД, И ТОЙ Е СМИСЪЛЪТ: престой по чл. 267 — в базата по чл. 17 ал. 1 т. 6, но
      // БЕЗ постоянен характер. Прочетена през bgIsPermanentCharacter, базата го губи.
      if (baseMonthStoppagePay != null) {
        BgPaySlipLine stoppage = newLine(typeOf(STOPPAGE), baseMonthStoppagePay);
        if (baseMonthStoppageFromScreen) {
          // ⚠️ ТОЧНО КАКВОТО ЕКРАНЪТ ЗАПИСВА: типът е избран, третирането е на подразбирането.
          stoppage.setBgFormsGrossWage(Boolean.FALSE);
          stoppage.setBgIsArt17Base(Boolean.FALSE);
          stoppage.setBgIsPermanentCharacter(Boolean.FALSE);
        }
        slip.getBgPaySlipLineList().add(stoppage);
      }
      if (baseMonthPerformancePay != null) {
        slip.getBgPaySlipLineList().add(newLine(typeOf(PERFORMANCE), baseMonthPerformancePay));
      }
      // ⚠️ БАЗОВИЯТ ФИШ МИНАВА ПРЕЗ ЗАПИСА, както в производството: там третирането се преписва от
      // речника. Без този ред свидетелят щеше да мери фикстура, а не пътя, по който данните идват.
      if (!skipTreatmentFill) {
        String status = slip.getStatusSelect();
        slip.setStatusSelect(BgPaySlipResolver.DRAFT);
        new TreatmentRepo().fill(slip);
        slip.setStatusSelect(status);
      }
      // ⚠️ КОНТРОЛЪТ: ред, който НЕ бива да влиза в базата. Без него свидетелят „190 вдига
      // отпуска“ би минал зелен и при флаг, вдигнат на ВСЯКО ръчно перо.
      if (baseMonthOneOffBonus != null) {
        slip.getBgPaySlipLineList().add(newLine(typeOf(ONE_OFF), baseMonthOneOffBonus));
      }
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
          type(MANUAL_BONUS, 180, "Постоянен бонус", true),
          stoppageType(),
          performanceType(),
          oneOffType());

  /** Перо 170 — престой по КТ чл. 267. ⚠️ В базата по чл. 17, БЕЗ постоянен характер. */
  private static final String STOPPAGE = "pl_prestoi_267";

  /**
   * ⚠️ ЕДИНСТВЕНИЯТ ТИП С РАЗЛИЧНИ СТОЙНОСТИ НА ДВАТА ФЛАГА, и точно затова е отделен строител.
   * Прекаран през общия {@code type(...)}, той щеше да носи двата флага СЛЕТИ — а смисълът е в
   * това, че те са различни норми: чл. 15 (часовата ставка по чл. 7) срещу чл. 17 ал. 1 т. 6.
   */
  /** Перо 190 — за постигнати резултати. ⚠️ В базата по т. 2, БЕЗ постоянен характер. */
  private static final String PERFORMANCE = "pl_rezultati";

  /** Перо 185 — еднократен бонус. ⚠️ ИЗВЪН базата: дискреция, не система за заплащане. */
  private static final String ONE_OFF = "pl_ednokraten_bonus";

  /**
   * ⚠️ ДВА ТИПА, КОИТО СЕ РАЗЛИЧАВАТ САМО ПО bgIsArt17Base — и точно това прави свидетеля силен. И
   * двата са ръчни, и двата са непостоянни, и двата се плащат в пари върху фиша. Разграничителят е
   * ЕДИНСТВЕНО дали възнаграждението е „определено според прилаганите СИСТЕМИ за заплащане“ (т. 2)
   * или е по преценка. Слети, тестът щеше да мине зелен и при сгрешен флаг.
   */
  private static BgPaySlipLineType performanceType() {
    BgPaySlipLineType t = type(PERFORMANCE, 190, "Възнаграждение за постигнати резултати", false);
    t.setBgIsArt17Base(true);
    t.setBgCalculationBase(BgEarningsResolver.CALC_MANUAL_AMOUNT);
    return t;
  }

  private static BgPaySlipLineType oneOffType() {
    BgPaySlipLineType t = type(ONE_OFF, 185, "Еднократен бонус", false);
    t.setBgIsArt17Base(false);
    t.setBgCalculationBase(BgEarningsResolver.CALC_MANUAL_AMOUNT);
    return t;
  }

  private static BgPaySlipLineType stoppageType() {
    BgPaySlipLineType t = type(STOPPAGE, 170, "Възнаграждение за престой", false);
    t.setBgIsArt17Base(true);
    t.setBgCalculationBase(BgEarningsResolver.CALC_MANUAL_AMOUNT);
    return t;
  }

  private static BgPaySlipLineType type(String code, int number, String name, boolean permanent) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setCode(code);
    t.setNumber(number);
    t.setName(name);
    t.setBgGroup("SALARY_ADDITIONS");
    t.setBgSign(BgPaySlipResolver.CREDIT);
    t.setBgIsPermanentCharacter(permanent);
    // ⚠️ ИНВАРИАНТЪТ НА РЕЧНИКА, ОГЛЕДАЛЕН И ТУК: постоянното Е в базата по чл. 17 (т. 3).
    t.setBgIsArt17Base(permanent);
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

  /**
   * {@code BgPaySlipManagementRepository} без база — вика се САМО преписването на третирането.
   *
   * <p>⚠️ Подклас, защото методът е {@code protected}, а този тест е в друг пакет. Разширяването на
   * видимостта в продукционния репозиторий заради тест би било промяна в шев, който е тесен
   * нарочно.
   */
  private static final class TreatmentRepo
      extends com.axelor.apps.bglocalization.db.repo.BgPaySlipManagementRepository {
    void fill(BgPaySlip slip) {
      fillTreatmentFromType(slip);
    }
  }

  private static BgPaySlipLine newLine(BgPaySlipLineType type, BigDecimal amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgPaySlipLineType(type);
    line.setBgLineNumber(type.getNumber());
    line.setName(type.getName());
    line.setBgSign(type.getBgSign());
    line.setBgIsPermanentCharacter(type.getBgIsPermanentCharacter());
    // ⚠️ Огледално на BgPaySlipService#lineFrom: замразява се СЪЩОТО, което услугата замразява.
    line.setBgIsArt17Base(type.getBgIsArt17Base());
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

  private static EmploymentContract contract(BigDecimal hoursPerDay) {
    EmploymentContract c = new EmploymentContract();
    c.setEmployee(new Employee());
    c.setBgBaseSalary(BASE_SALARY);
    // ⚠️ Договорените срещу законоустановените часове: равни са признакът за ПЪЛЕН работен ден,
    // от който виси подът по НСОРЗ чл. 19 ал. 2.
    c.setBgHoursPerDay(hoursPerDay);
    c.setBgLegalHoursPerDay(new BigDecimal("8.00"));
    return c;
  }

  private static BigDecimal amountOf(BgPaySlip slip, String code) {
    BgPaySlipLine line = lineOf(slip, code);
    return line == null ? null : line.getBgAmount();
  }

  /** Самият ред — иска се, когато свидетелят чете и ВЪВЕДЕНАТА сума, не само платената. */
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
