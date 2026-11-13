package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЧАСТИТЕ НА ОБЕЗЩЕТЕНИЕТО ПО ЧЛ. 222 АЛ. 1 КТ — по подадените файлове на Microinvest за 01, 03 и
 * 04.2025. Чиста функция: календарът е подаден.
 *
 * <p>Образецът 03/04.2025: прекратяване 23.03.2025, обезщетение 1077,00 (53,85 × 20). Период
 * 24.03–23.04; работни дни 6 + 15 = 21 (18.04 и 21.04 са Великден); дневно 1077,00 / 21 = 51,29;
 * март 6 × 51,29 = 307,74; април — остатъкът 769,26.
 */
class BgCompensationPartsTest {

  /** Официалните празници от 2025 г., които периодите тук докосват. */
  private static final Set<LocalDate> HOLIDAYS_2025 =
      new HashSet<>(
          Arrays.asList(
              LocalDate.of(2025, 1, 1),
              LocalDate.of(2025, 3, 3),
              LocalDate.of(2025, 4, 18),
              LocalDate.of(2025, 4, 21)));

  static final BgCompensationParts.WorkingDays CALENDAR_2025 =
      (from, to) -> {
        int days = 0;
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
          if (d.getDayOfWeek() != DayOfWeek.SATURDAY
              && d.getDayOfWeek() != DayOfWeek.SUNDAY
              && !HOLIDAYS_2025.contains(d)) {
            days++;
          }
        }
        return days;
      };

  // ═══ ПЕРИОДЪТ И РАЗДЕЛЯНЕТО ════════════════════════════════════════════

  @Test
  @DisplayName("03/04.2025 · 1077,00 → март 24–31 (6 р.д.) 307,74 + април 01–23 (15 р.д.) 769,26")
  void theMarchAprilSampleSplitsByWorkingDays() {
    List<BgCompensationParts.Segment> parts =
        BgCompensationParts.split(
            new BigDecimal("1077.00"), LocalDate.of(2025, 3, 23), CALENDAR_2025);

    assertEquals(2, parts.size(), "⚠️ периодът минава в април — ДВЕ части, не една: " + parts);
    assertSegment(parts.get(0), "2025-03-24", "2025-03-31", 6, "307.74");
    assertSegment(parts.get(1), "2025-04-01", "2025-04-23", 15, "769.26");
  }

  @Test
  @DisplayName("⚠️ по РАБОТНИ дни, не по календарни: март е 307,74, не 277,94 (8/31 × 1077,00)")
  void theSplitIsByWorkingDaysNotCalendarDays() {
    List<BgCompensationParts.Segment> parts =
        BgCompensationParts.split(
            new BigDecimal("1077.00"), LocalDate.of(2025, 3, 23), CALENDAR_2025);
    assertEquals(
        0,
        new BigDecimal("307.74").compareTo(parts.get(0).getAmount()),
        "дневно 1077,00 / 21 р.д. = 51,29; × 6 р.д. = 307,74");
  }

  @Test
  @DisplayName("⚠️ последната част е ОСТАТЪКЪТ: 769,26, не 15 × 51,29 = 769,35; сборът е 1077,00")
  void theLastPartIsTheRemainder() {
    List<BgCompensationParts.Segment> parts =
        BgCompensationParts.split(
            new BigDecimal("1077.00"), LocalDate.of(2025, 3, 23), CALENDAR_2025);
    assertEquals(0, new BigDecimal("769.26").compareTo(parts.get(1).getAmount()));
    assertEquals(
        0,
        new BigDecimal("1077.00").compareTo(parts.get(0).getAmount().add(parts.get(1).getAmount())),
        "сборът на частите е сумата до стотинка");
  }

  @Test
  @DisplayName(
      "01.2025 · прекратяване 01.01 → 02–31.01, 22 р.д., 1800,00 — 01.02 е събота и се реже")
  void theJanuarySampleIsOneMonth() {
    List<BgCompensationParts.Segment> parts =
        BgCompensationParts.split(
            new BigDecimal("1800.00"), LocalDate.of(2025, 1, 1), CALENDAR_2025);
    assertEquals(1, parts.size(), "частта без работен ден се реже: " + parts);
    assertSegment(parts.get(0), "2025-01-02", "2025-01-31", 22, "1800.00");
  }

  @Test
  @DisplayName("прекратяване в последния ден на месеца → целият следващ месец (31.01 → 01–28.02)")
  void anEndOfMonthTerminationGivesTheWholeNextMonth() {
    List<BgCompensationParts.Segment> parts =
        BgCompensationParts.split(
            new BigDecimal("1000.00"), LocalDate.of(2025, 1, 31), CALENDAR_2025);
    assertEquals(1, parts.size(), parts.toString());
    assertSegment(parts.get(0), "2025-02-01", "2025-02-28", 20, "1000.00");
  }

  @Test
  @DisplayName("без дата на основанието → няма части (повикващият го обявява), не „една за всичко“")
  void noTerminationDateGivesNoParts() {
    assertTrue(BgCompensationParts.split(new BigDecimal("1000.00"), null, CALENDAR_2025).isEmpty());
  }

  // ═══ ВНОСКИТЕ И ДАНЪКЪТ НА ЧАСТТА ═══════════════════════════════════════

  @Test
  @DisplayName(
      "03.2025 · ДОО 25,30 + 20,25 = 45,55, УПФ 8,62 + 6,77 = 15,39, лични 27,02, ДДФЛ 28,07;"
          + " 04.2025 · ДДФЛ 70,17")
  void theSharesCarryContributionsAndTax() {
    BgPaySlip slip = compensationOnlySlip(LocalDate.of(2025, 3, 23), "1077.00", "98.24");
    List<BgCompensationParts.Carried> carried = BgCompensationParts.carried(slip);
    List<BgCompensationParts.Share> shares =
        BgCompensationParts.sharesOf(
            slip, carried.get(0), carried, CALENDAR_2025, ceiling2025(slip));

    BgCompensationParts.Share march = shares.get(0);
    assertEquals(0, new BigDecimal("45.55").compareTo(march.d6ByPoint().get(BgD6Point.T10_DOO)));
    assertEquals(0, new BigDecimal("15.39").compareTo(march.d6ByPoint().get(BgD6Point.T12_UPF)));
    assertEquals(0, new BigDecimal("27.02").compareTo(march.personal()));
    assertEquals(0, new BigDecimal("28.07").compareTo(march.getTax()), "(307,74 − 27,02) × 10 %");

    BgCompensationParts.Share april = shares.get(1);
    assertEquals(
        0, new BigDecimal("70.17").compareTo(april.getTax()), "остатъкът до 98,24: 98,24 − 28,07");
    assertEquals(
        0,
        new BigDecimal("113.85").compareTo(april.d6ByPoint().get(BgD6Point.T10_DOO)),
        "ДОО на април — остатъкът до перата (88,53 − 25,30) + (70,87 − 20,25)");
    assertEquals(0, new BigDecimal("38.46").compareTo(april.d6ByPoint().get(BgD6Point.T12_UPF)));
  }

  @Test
  @DisplayName("смесен фиш: ред 27 = (1077,00 − 94,56) × 10 % = 98,24, ред 01 — остатъкът 129,33")
  void aMixedSlipSplitsTheTaxAndTheSumIsTheSlipTax() {
    BgPaySlip slip = compensationOnlySlip(LocalDate.of(2025, 3, 23), "1077.00", "227.57");
    BgPaySlipLine salary = new BgPaySlipLine();
    salary.setBgLineNumber(100);
    salary.setBgSign(BgPaySlipResolver.CREDIT);
    salary.setBgIsTaxable(true);
    salary.setBgIsInsuranceIncome(true);
    salary.setBgFormsGrossWage(true);
    salary.setBgAmount(new BigDecimal("1500.00"));
    slip.getBgPaySlipLineList().add(0, salary);

    assertFalse(BgCompensationParts.onlyCompensation(slip), "фишът носи и заплата");
    BigDecimal compensationTax =
        BgCompensationParts.taxTotals(slip, BgCompensationParts.carried(slip))
            .values()
            .iterator()
            .next();
    assertEquals(0, new BigDecimal("98.24").compareTo(compensationTax));
    assertEquals(
        0,
        new BigDecimal("129.33").compareTo(BgCompensationParts.taxWithoutParts(slip)),
        "сборът на двата реда = данъкът на фиша (227,57) до стотинка");
  }

  // ═══ ОБЩИЯТ ТАВАН С ВИД 01 (КСО ЧЛ. 6 АЛ. 11) ═════════════════════════════

  @Test
  @DisplayName(
      "⚠️ таван 4130,00, вид 01 = 4000,00 → мартенската част се осигурява САМО върху 130,00;"
          + " април (само вид 27) — 769,26, без промяна; сумите (т. 31) не се пипат")
  void theCeilingIsSharedWithType01InTheSameMonth() {
    List<BgCompensationParts.Segment> parts =
        BgCompensationParts.capped(
            BgCompensationParts.split(
                new BigDecimal("1077.00"), LocalDate.of(2025, 3, 23), CALENDAR_2025),
            roomAfter01(new BigDecimal("4000.00")));

    assertEquals(0, new BigDecimal("130.00").compareTo(parts.get(0).getInsurable()), "" + parts);
    assertEquals(0, new BigDecimal("307.74").compareTo(parts.get(0).getAmount()), "т. 31 остава");
    assertEquals(0, new BigDecimal("769.26").compareTo(parts.get(1).getInsurable()), "април");
    assertEquals(0, new BigDecimal("769.26").compareTo(parts.get(1).getAmount()));
    assertEquals(0, new BigDecimal("899.26").compareTo(BgCompensationParts.insurableTotal(parts)));
  }

  @Test
  @DisplayName("вид 01 над тавана → частта по вид 27 се осигурява върху 0,00, не върху отрицателно")
  void anExhaustedCeilingLeavesZeroNotANegativeBase() {
    List<BgCompensationParts.Segment> parts =
        BgCompensationParts.capped(
            BgCompensationParts.split(
                new BigDecimal("1077.00"), LocalDate.of(2025, 3, 23), CALENDAR_2025),
            roomAfter01(new BigDecimal("4500.00")));
    assertEquals(0, parts.get(0).getInsurable().signum(), "" + parts);
    assertEquals(0, new BigDecimal("769.26").compareTo(parts.get(1).getInsurable()));
  }

  @Test
  @DisplayName(
      "базата на фиша: 130,00 + 769,26 = 899,26; таванът не реже → цялата сума, БЕЗ календар")
  void theSlipBaseIsTheSumUnderTheCeiling() {
    assertEquals(
        0,
        new BigDecimal("899.26")
            .compareTo(
                BgCompensationParts.insurableOf(
                    new BigDecimal("1077.00"),
                    LocalDate.of(2025, 3, 23),
                    CALENDAR_2025,
                    roomAfter01(new BigDecimal("4000.00")))));
    BgCompensationParts.WorkingDays notAsked =
        (from, to) -> {
          throw new AssertionError("календарът не се пита, когато таванът не реже");
        };
    assertEquals(
        0,
        new BigDecimal("1077.00")
            .compareTo(
                BgCompensationParts.insurableOf(
                    new BigDecimal("1077.00"),
                    LocalDate.of(2025, 3, 23),
                    notAsked,
                    roomAfter01(new BigDecimal("1500.00")))));
  }

  @Test
  @DisplayName("месец без MAX_INSURABLE_INCOME → отказ с името на параметъра, не „без таван“")
  void aMonthWithoutACeilingIsRefused() {
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () ->
                BgCompensationParts.insurableOf(
                    new BigDecimal("1077.00"),
                    LocalDate.of(2025, 3, 23),
                    CALENDAR_2025,
                    m -> null));
    assertTrue(refused.getMessage().contains("MAX_INSURABLE_INCOME"), refused.getMessage());
  }

  @Test
  @DisplayName(
      "частите под тавана: перата върху 899,26; март — от 130,00 (10,69 / 8,55 / 3,64 / 2,86),"
          + " април — остатъкът (63,23 / 50,62 / 21,54 / 16,92 — както без тавана)")
  void theSharesFollowTheCappedBase() {
    BgPaySlip slip =
        compensationSlip(LocalDate.of(2025, 3, 23), "1077.00", new BigDecimal("899.26"), "99.81");
    List<BgCompensationParts.Carried> carried = BgCompensationParts.carried(slip);
    List<BgCompensationParts.Share> shares =
        BgCompensationParts.sharesOf(
            slip, carried.get(0), carried, CALENDAR_2025, roomAfter01(new BigDecimal("4000.00")));

    assertFunds(shares.get(0), "10.69", "8.55", "3.64", "2.86");
    assertFunds(shares.get(1), "63.23", "50.62", "21.54", "16.92");
    assertEquals(
        0,
        new BigDecimal("29.63").compareTo(shares.get(0).getTax()),
        "данъкът е от ЦЯЛАТА част: (307,74 − 11,41) × 10 %");
  }

  @Test
  @DisplayName("перото 9xx и вноските 5xx на вид 27 са „на реда 27“; другите пера — не")
  void partLinesAreRecognisedByReferenceAndGroup() {
    BgPaySlip slip = compensationOnlySlip(LocalDate.of(2025, 3, 23), "1077.00", "98.24");
    List<BgPaySlipLine> lines = slip.getBgPaySlipLineList();
    assertTrue(BgCompensationParts.isPartLine(lines.get(0)), "перото 900");
    assertTrue(BgCompensationParts.isPartLine(lines.get(1)), "вноската 501 върху обезщетението");
    assertFalse(BgCompensationParts.isPartLine(lines.get(lines.size() - 1)), "данъкът не е");
    assertTrue(BgCompensationParts.isCompensationLine(lines.get(0)));
    assertFalse(
        BgCompensationParts.isCompensationLine(lines.get(1)),
        "⚠️ вноската носи СЪЩАТА препратка, но НЕ е перото на обезщетението");
    assertTrue(BgCompensationParts.onlyCompensation(slip));
  }

  // ═══ строители ═══

  /**
   * Фиш само с обезщетение по вид 27 — перото 900 и вноските върху ЦЯЛАТА сума, както ги пише
   * генераторът (Пенсии 8,22/6,58, УПФ 2,80/2,20).
   */
  static BgPaySlip compensationOnlySlip(LocalDate terminated, String amount, String tax) {
    return compensationSlip(terminated, amount, new BigDecimal(amount), tax);
  }

  /** Фиш само с обезщетение, чиито вноски са върху {@code base} — под общия таван. */
  static BgPaySlip compensationSlip(
      LocalDate terminated, String amount, BigDecimal base, String tax) {
    BgCompensation doc = compensation222(terminated, null);
    BgPaySlip slip = new BgPaySlip();
    slip.setBgPaySlipLineList(new ArrayList<>());
    List<BgPaySlipLine> l = slip.getBgPaySlipLineList();
    l.add(compensationLine(doc, new BigDecimal(amount)));
    l.add(contribution(doc, 501, BgPaySlipResolver.CREDIT, "PENSION", "22", "8.22", base));
    l.add(contribution(doc, 502, BgPaySlipResolver.DEBIT, "PENSION", "22", "6.58", base));
    l.add(contribution(doc, 508, BgPaySlipResolver.CREDIT, "UPF", "26", "2.80", base));
    l.add(contribution(doc, 509, BgPaySlipResolver.DEBIT, "UPF", "26", "2.20", base));
    l.add(taxLine(tax));
    slip.setBgTaxAmount(new BigDecimal(tax));
    return slip;
  }

  static BgCompensation compensation222(LocalDate terminated, LocalDate paid) {
    BgCompensationType type = new BgCompensationType();
    type.setCode("comp_kt_222_1");
    type.setD1Code("27");
    BgCompensation doc = new BgCompensation();
    doc.setReference("ОБ-0001");
    doc.setBgCompensationType(type);
    doc.setBgEventDate(terminated);
    doc.setBgPaidDate(paid);
    return doc;
  }

  static BgPaySlipLine compensationLine(BgCompensation doc, BigDecimal amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(900);
    line.setBgSign(BgPaySlipResolver.CREDIT);
    line.setBgIsTaxable(true);
    line.setBgIsInsuranceIncome(true);
    line.setBgFormsGrossWage(false);
    line.setBgAmount(amount);
    line.setBgCompensation(doc);
    return line;
  }

  /** Вноска върху обезщетението — сумата е ставката върху базата, HALF_UP до стотинка. */
  static BgPaySlipLine contribution(
      BgCompensation doc,
      int number,
      String sign,
      String fundCode,
      String d1Field,
      String rate,
      BigDecimal base) {
    BgContributionFund fund = new BgContributionFund();
    fund.setCode(fundCode);
    fund.setBgD1Field(d1Field);
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setBgSign(sign);
    line.setBgContributionFund(fund);
    line.setBgRate(new BigDecimal(rate));
    line.setBgAmount(
        base.multiply(new BigDecimal(rate))
            .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP));
    line.setBgQuantity(base);
    line.setBgIsTaxable(false);
    line.setBgIsInsuranceIncome(false);
    line.setBgCompensation(doc);
    return line;
  }

  static BgPaySlipLine taxLine(String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(700);
    line.setBgSign(BgPaySlipResolver.DEBIT);
    line.setBgAmount(new BigDecimal(amount));
    line.setBgRate(new BigDecimal("10"));
    line.setBgIsTaxable(false);
    line.setBgIsInsuranceIncome(false);
    return line;
  }

  /** Таванът за 2025 г. — 4130,00 лв. (MAX_INSURABLE_INCOME). */
  static final BigDecimal CEILING_2025 = new BigDecimal("4130.00");

  /**
   * Мястото под тавана, както го дава {@code BgCompensationCalendar#roomFor}, без база: вид 01 на
   * лицето е само на носителя, в неговия месец.
   */
  static BgCompensationParts.Room ceiling2025(BgPaySlip carrier) {
    return month ->
        carrier.getPeriod() != null
                && YearMonth.from(carrier.getPeriod().getFromDate()).equals(month)
            ? CEILING_2025.subtract(BgCompensationParts.regularBase(carrier))
            : CEILING_2025;
  }

  /** Вид 01 = {@code base01} само през март 2025; април — без вид 01. */
  private static BgCompensationParts.Room roomAfter01(BigDecimal base01) {
    return month ->
        month.equals(YearMonth.of(2025, 3)) ? CEILING_2025.subtract(base01) : CEILING_2025;
  }

  /** Вноските на частта по реда на перата: Пенсии работодател/лице, УПФ работодател/лице. */
  private static void assertFunds(BgCompensationParts.Share share, String... amounts) {
    for (int i = 0; i < amounts.length; i++) {
      assertEquals(
          0,
          new BigDecimal(amounts[i]).compareTo(share.getFunds().get(i).getAmount()),
          "фонд " + (i + 1) + " в " + share.getSegment());
    }
  }

  private static void assertSegment(
      BgCompensationParts.Segment s, String from, String to, int days, String amount) {
    assertEquals(LocalDate.parse(from), s.getFrom(), "т. 14 — " + s);
    assertEquals(LocalDate.parse(to), s.getTo(), "т. 15 — " + s);
    assertEquals(days, s.getWorkingDays(), "т. 16.1 — " + s);
    assertEquals(0, new BigDecimal(amount).compareTo(s.getAmount()), "сумата — " + s);
  }
}
