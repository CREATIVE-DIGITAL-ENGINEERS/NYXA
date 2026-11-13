package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.service.BgContributionLine.Payer;
import com.axelor.apps.bglocalization.service.BgContributionResult.Status;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the social-insurance contribution logic.
 *
 * <p>No database and no Guice: the resolver takes already-filtered rows built in memory. Rates are
 * from the 2026 seed. Bases of 100.00 make each amount equal its rate, so rate sums are checked
 * directly.
 */
class BgContributionResolverTest {

  // ── fixtures ────────────────────────────────────────────────────────────

  /**
   * A well-formed row: every rate declares its kind, the way all 143 seed rows do.
   *
   * <p>The kinds are derived here the same way the seed derives them — a nonzero rate is a {@code
   * VALUE}, a zero rate is a {@code STRUCTURAL_ZERO} — so that these fixtures keep exercising the
   * arithmetic rather than tripping the kind guard. Rows that deliberately carry a {@code MISSING}
   * or an undeclared kind are built by {@link #rowWithKinds} and used only by the guard tests.
   */
  private static BgInsuranceContribution row(
      String fund, String employerRate, String employeeRate) {
    BgInsuranceContribution c = new BgInsuranceContribution();
    c.setFundCode(fund);
    c.setEmployerRate(new BigDecimal(employerRate));
    c.setEmployeeRate(new BigDecimal(employeeRate));
    c.setBgEmployerRateKind(c.getEmployerRate().signum() == 0 ? "STRUCTURAL_ZERO" : "VALUE");
    c.setBgEmployeeRateKind(c.getEmployeeRate().signum() == 0 ? "STRUCTURAL_ZERO" : "VALUE");
    return c;
  }

  private static BgInsuranceContribution rowWithKinds(
      String importId,
      String fund,
      String employerRate,
      String employeeRate,
      String employerKind,
      String employeeKind) {
    BgInsuranceContribution c = row(importId, fund, employerRate, employeeRate);
    c.setBgEmployerRateKind(employerKind);
    c.setBgEmployeeRateKind(employeeKind);
    return c;
  }

  private static BgInsuranceContribution row(
      String importId, String fund, String employerRate, String employeeRate) {
    BgInsuranceContribution c = row(fund, employerRate, employeeRate);
    c.setImportId(importId);
    return c;
  }

  private static BgContributionRequest req(String base, String pensionChoice, String tzpbRate) {
    return req(base, pensionChoice, tzpbRate, true);
  }

  private static BgContributionRequest req(
      String base, String pensionChoice, String tzpbRate, boolean bornAfter1959) {
    return new BgContributionRequest(
        new BigDecimal(base),
        "01",
        "3",
        bornAfter1959,
        pensionChoice,
        null,
        LocalDate.of(2026, 1, 1),
        tzpbRate == null ? null : new BigDecimal(tzpbRate));
  }

  /** Rows the query returns for code 01, third category, WITH_UPF cohort. */
  private static List<BgInsuranceContribution> code01WithUpf() {
    return Arrays.asList(
        row("PENSION", "8.22", "6.58"),
        row("UPF", "2.80", "2.20"),
        row("OZM", "2.10", "1.40"),
        row("UNEMPLOYMENT", "0.60", "0.40"),
        row("HEALTH", "4.80", "3.20"),
        row("GVRS", "0.00", "0.00"));
  }

  /** Rows for code 01, WITHOUT_UPF cohort — pension rate absorbs the 5% УПФ, no UPF row. */
  private static List<BgInsuranceContribution> code01WithoutUpf() {
    return Arrays.asList(
        row("PENSION", "11.02", "8.78"),
        row("OZM", "2.10", "1.40"),
        row("UNEMPLOYMENT", "0.60", "0.40"),
        row("HEALTH", "4.80", "3.20"),
        row("GVRS", "0.00", "0.00"));
  }

  /**
   * Rows for code 05, third category, WITH_UPF cohort — the 2026 seed, including the ТЗПБ row that
   * carries the fixed 0,70%. Code 24 is seeded with the same rates.
   */
  private static List<BgInsuranceContribution> code05WithFixedTzpb() {
    return Arrays.asList(
        row("PENSION", "11.80", "3.00"),
        row("UPF", "4.00", "1.00"),
        row("OZM", "2.80", "0.70"),
        row("UNEMPLOYMENT", "0.80", "0.20"),
        row("HEALTH", "6.40", "1.60"),
        row("GVRS", "0.00", "0.00"),
        row("TZPB", "0.70", "0.00"));
  }

  private static boolean eq(BigDecimal a, String b) {
    return a.compareTo(new BigDecimal(b)) == 0;
  }

  private static List<BgContributionLine> linesOfFund(BgContributionResult r, String fundCode) {
    return r.getLines().stream()
        .filter(l -> fundCode.equals(l.getFundCode()))
        .collect(Collectors.toList());
  }

  // ── cohort derivation ─────────────────────────────────────────────────────

  @Test
  void bornBefore1960IsWithoutUpf() {
    assertEquals("WITHOUT_UPF", BgContributionResolver.deriveCohort(false, "DEFAULT"));
  }

  @Test
  void bornAfterDefaultIsWithUpf() {
    assertEquals("WITH_UPF", BgContributionResolver.deriveCohort(true, "DEFAULT"));
  }

  @Test
  void art4bTransfersToWithoutUpf() {
    assertEquals("WITHOUT_UPF", BgContributionResolver.deriveCohort(true, "ART4B"));
  }

  @Test
  void art4cStaysWithUpf() {
    assertEquals("WITH_UPF", BgContributionResolver.deriveCohort(true, "ART4C"));
  }

  // ── code 01 ───────────────────────────────────────────────────────────────

  @Test
  void code01WithUpfSumsToEighteenFiftyTwoAndThirteenSeventyEight() {
    BgContributionResult r =
        BgContributionResolver.resolve(code01WithUpf(), req("100.00", "DEFAULT", null));
    assertTrue(r.isResolved());
    assertFalse(r.isBlocking());
    assertTrue(eq(r.totalEmployer(), "18.52"));
    assertTrue(eq(r.totalEmployee(), "13.78"));
    // GVRS at 0/0 produces no line: 5 funds x 2 payers - 0 = 10 lines.
    assertEquals(10, r.getLines().size());
  }

  @Test
  void code01WithoutUpfDropsUpfKeepsSameTotals() {
    // WITHOUT_UPF cohort: born before 1960 → the missing-UPF flag must not fire.
    BgContributionResult r =
        BgContributionResolver.resolve(code01WithoutUpf(), req("100.00", "DEFAULT", null, false));
    assertTrue(r.isResolved());
    assertFalse(r.isBlocking());
    assertEquals(Status.RESOLVED, r.getStatus());
    assertTrue(eq(r.totalEmployer(), "18.52"));
    assertTrue(eq(r.totalEmployee(), "13.78"));
  }

  /** чл.4б is neutral on the total: 8.22+2.80 == 11.02 and 6.58+2.20 == 8.78. */
  @Test
  void art4bTotalEqualsWithUpfTotal() {
    BgContributionResult withUpf =
        BgContributionResolver.resolve(code01WithUpf(), req("100.00", "DEFAULT", null));
    // ART4B → WITHOUT_UPF cohort, so it runs on the WITHOUT_UPF row set.
    BgContributionResult art4b =
        BgContributionResolver.resolve(code01WithoutUpf(), req("100.00", "ART4B", null));
    assertEquals(0, withUpf.total().compareTo(art4b.total()));
    assertTrue(eq(art4b.total(), "32.30"));
  }

  // ── ART4C: КЕПВА РЕДА ЗА ППФ, защото и Д1, и Д6 декларират чл. 4в в точката на ППФ ─────

  /**
   * ⚠️ ОБРЪЩАНЕ НА ЗАВАРЕНО ПОВЕДЕНИЕ, И ЗАТОВА С НОРМАТА ДОСЛОВНО. Дотук този тест твърдеше {@code
   * ART4C_NOT_MODELLED} — заявката се блокираше, защото „чл. 4в пренасочва ППФ към фонд „Пенсии“,
   * което данните не моделират“. Пренасочването е вярно, но е за ПЛАЩАНЕТО.
   *
   * <p>Указание т. 25 от Приложение № 1 на Наредба Н-13: „размерът на вноската … за професионален
   * пенсионен фонд ИЛИ за лицата по чл. 4в от КСО за фонд „Пенсии“ с увеличена осигурителна вноска
   * в размера на вноската за професионален пенсионен фонд“. Д6 т. 13 казва същото с „и/или“. Тоест
   * ДЕКЛАРАЦИЯТА не различава двата случая — те са ЕДНА точка, — а редът за ППФ, оставен както е,
   * дава точно нея.
   *
   * <p>⚠️ И СБОРЪТ Е СВИДЕТЕЛЯТ, ЧЕ НИЩО НЕ СЕ Е ЗАГУБИЛО: 11,22 + 2,80 + 2,10 + 0,60 + 4,80 +
   * 12,00 за осигурителя и 6,58 + 2,20 + 1,40 + 0,40 + 3,20 за лицето. Блокиран, резултатът даваше
   * нула пункта; изгубен ред за ППФ би дал 12 пункта по-малко — тоест същите пари, за които пазачът
   * съществуваше.
   */
  @Test
  void art4cKeepsThePpfRowBecauseBothDeclarationsMergeItThere() {
    List<BgInsuranceContribution> code02Cat1 =
        Arrays.asList(
            row("PENSION", "11.22", "6.58"),
            row("UPF", "2.80", "2.20"),
            row("OZM", "2.10", "1.40"),
            row("UNEMPLOYMENT", "0.60", "0.40"),
            row("HEALTH", "4.80", "3.20"),
            row("GVRS", "0.00", "0.00"),
            row("PPF", "12.00", "0.00"));
    BgContributionResult r =
        BgContributionResolver.resolve(code02Cat1, req("100.00", "ART4C", null));

    assertEquals(Status.RESOLVED, r.getStatus(), "детайл: " + r.getDetail());
    assertTrue(r.isResolved());
    assertFalse(r.isBlocking(), "блокиран фиш не се потвърждава, тоест лицето не получава заплата");

    // Редът за ППФ е ТУК и носи своите 12 пункта — не е нито изгубен, нито слят с пенсионния.
    assertTrue(
        r.getLines().stream()
            .anyMatch(
                l ->
                    "PPF".equals(l.getFundCode())
                        && l.getRate().compareTo(new java.math.BigDecimal("12.00")) == 0),
        "ППФ трябва да е сред редовете със ставка 12,00 — оттам т. 25 на Д1 и т. 13 на Д6."
            + " Редове: "
            + r.getLines());
    assertTrue(
        eq(r.totalEmployer(), "33.52"),
        "11,22 + 2,80 + 2,10 + 0,60 + 4,80 + 12,00 = 33,52 на сто върху 100,00");
  }

  /**
   * ART4C is only meaningful when a ППФ row is present. Code 01, third category, has no ППФ row, so
   * ART4C is harmless and the result is a normal RESOLVED identical to DEFAULT — not a block.
   */
  @Test
  void art4cWithoutPpfResolvesNormally() {
    BgContributionResult art4c =
        BgContributionResolver.resolve(code01WithUpf(), req("100.00", "ART4C", null));
    BgContributionResult dflt =
        BgContributionResolver.resolve(code01WithUpf(), req("100.00", "DEFAULT", null));
    assertEquals(Status.RESOLVED, art4c.getStatus());
    assertFalse(art4c.isBlocking());
    assertEquals(dflt.getLines().size(), art4c.getLines().size());
    assertEquals(0, dflt.totalEmployer().compareTo(art4c.totalEmployer()));
    assertEquals(0, dflt.totalEmployee().compareTo(art4c.totalEmployee()));
  }

  // ── code 02 keeps ППФ under DEFAULT ─────────────────────────────────────────

  @Test
  void code02KeepsPpfAsEmployerOnlyUnderDefault() {
    // WITHOUT_UPF fixture (no UPF row) tested as born-before-1960 to isolate the ППФ line.
    List<BgInsuranceContribution> code02Cat1 =
        Arrays.asList(row("PENSION", "14.02", "8.78"), row("PPF", "12.00", "0.00"));
    BgContributionResult r =
        BgContributionResolver.resolve(code02Cat1, req("100.00", "DEFAULT", null, false));
    assertTrue(r.isResolved());
    List<BgContributionLine> ppf =
        r.getLines().stream()
            .filter(l -> "PPF".equals(l.getFundCode()))
            .collect(Collectors.toList());
    assertEquals(1, ppf.size());
    assertEquals(Payer.EMPLOYER, ppf.get(0).getPayer());
    assertTrue(eq(ppf.get(0).getAmount(), "12.00"));
  }

  // ── СОЛ code 12 ─────────────────────────────────────────────────────────────

  @Test
  void solAllRisksHasNoEmployerShare() {
    List<BgInsuranceContribution> sol =
        Arrays.asList(
            row("PENSION", "0.00", "18.30"),
            row("UPF", "0.00", "5.00"),
            row("HEALTH", "0.00", "8.00"));
    BgContributionResult r = BgContributionResolver.resolve(sol, req("100.00", "DEFAULT", null));
    assertTrue(r.isResolved());
    assertFalse(r.isBlocking());
    assertTrue(eq(r.totalEmployer(), "0.00"));
    assertTrue(eq(r.totalEmployee(), "31.30"));
  }

  @Test
  void solPensionOnly() {
    List<BgInsuranceContribution> sol =
        Arrays.asList(
            row("PENSION", "0.00", "14.80"),
            row("UPF", "0.00", "5.00"),
            row("HEALTH", "0.00", "8.00"));
    BgContributionResult r = BgContributionResolver.resolve(sol, req("100.00", "DEFAULT", null));
    assertTrue(eq(r.totalEmployee(), "27.80"));
    assertTrue(eq(r.totalEmployer(), "0.00"));
  }

  // ── code 05 80/20 split (arithmetic only; period selection is service-level) ──

  @Test
  void code05EightyTwentySplit() {
    List<BgInsuranceContribution> code05 =
        Arrays.asList(
            row("PENSION", "11.80", "3.00"),
            row("UPF", "4.00", "1.00"),
            row("HEALTH", "6.40", "1.60"));
    BgContributionResult r = BgContributionResolver.resolve(code05, req("100.00", "DEFAULT", null));
    assertTrue(r.isResolved());
    assertFalse(r.isBlocking());
    assertTrue(eq(r.totalEmployer(), "22.20"));
    assertTrue(eq(r.totalEmployee(), "5.60"));
  }

  // ── rounding: each line rounded on its own ───────────────────────────────────

  @Test
  void eachLineIsRoundedIndependently() {
    // base 1.50, two employer funds at 1.000%: each 1.50*1/100 = 0.015 -> 0.02 (HALF_UP).
    // Per-line rounding gives 0.04; summing bases first (0.03) then rounding would give 0.03.
    // WITHOUT_UPF (born before 1960) so the missing-UPF flag does not fire.
    List<BgInsuranceContribution> rows =
        Arrays.asList(row("PENSION", "1.000", "0.00"), row("HEALTH", "1.000", "0.00"));
    BgContributionResult r =
        BgContributionResolver.resolve(rows, req("1.50", "DEFAULT", null, false));
    for (BgContributionLine line : r.getLines()) {
      assertTrue(eq(line.getAmount(), "0.02"));
    }
    assertTrue(eq(r.totalEmployer(), "0.04"));
  }

  // ── ТЗПБ ──────────────────────────────────────────────────────────────────────

  @Test
  void tzpbAddsOneEmployerLineWhenNonzero() {
    BgContributionResult r =
        BgContributionResolver.resolve(code01WithUpf(), req("100.00", "DEFAULT", "0.9"));
    List<BgContributionLine> tzpb =
        r.getLines().stream()
            .filter(l -> "TZPB".equals(l.getFundCode()))
            .collect(Collectors.toList());
    assertEquals(1, tzpb.size());
    assertEquals(Payer.EMPLOYER, tzpb.get(0).getPayer());
    assertTrue(eq(tzpb.get(0).getAmount(), "0.90"));
  }

  @Test
  void tzpbZeroAddsNoLine() {
    BgContributionResult r =
        BgContributionResolver.resolve(code01WithUpf(), req("100.00", "DEFAULT", "0"));
    assertTrue(r.getLines().stream().noneMatch(l -> "TZPB".equals(l.getFundCode())));
  }

  @Test
  void fixedTzpbRowWinsOverTheActivityRate() {
    // КИД 19 is 1,100 from 01.08.2026 — the worst case, since it is not 0,70 and would look like a
    // perfectly valid number on the payslip.
    BgContributionResult r =
        BgContributionResolver.resolve(code05WithFixedTzpb(), req("100.00", "DEFAULT", "1.1"));
    List<BgContributionLine> tzpb = linesOfFund(r, "TZPB");
    assertEquals(1, tzpb.size());
    assertEquals(Payer.EMPLOYER, tzpb.get(0).getPayer());
    assertTrue(eq(tzpb.get(0).getAmount(), "0.70"));
  }

  @Test
  void fixedTzpbRowIsNotChargedTwice() {
    // The row emits its line in the fund loop; the request rate must not add a second one.
    BgContributionResult r =
        BgContributionResolver.resolve(code05WithFixedTzpb(), req("100.00", "DEFAULT", "1.1"));
    assertEquals(1, linesOfFund(r, "TZPB").size());
    // 11,80 + 4,00 + 2,80 + 0,80 + 6,40 + 0,00 = 25,80 по фондове, плюс 0,70 ТЗПБ.
    assertTrue(eq(r.totalEmployer(), "26.50"));
  }

  @Test
  void fixedTzpbRowAppliesWithoutAnyActivityRate() {
    // A company with no НКИД supplies no rate at all; the fixed one still stands.
    BgContributionResult r =
        BgContributionResolver.resolve(code05WithFixedTzpb(), req("100.00", "DEFAULT", null));
    List<BgContributionLine> tzpb = linesOfFund(r, "TZPB");
    assertEquals(1, tzpb.size());
    assertTrue(eq(tzpb.get(0).getAmount(), "0.70"));
  }

  @Test
  void withoutATzpbRowTheActivityRateStillApplies() {
    // The guard must not switch ТЗПБ off for the 41 insured types that have no row of their own.
    BgContributionResult r =
        BgContributionResolver.resolve(code01WithUpf(), req("100.00", "DEFAULT", "1.1"));
    List<BgContributionLine> tzpb = linesOfFund(r, "TZPB");
    assertEquals(1, tzpb.size());
    assertTrue(eq(tzpb.get(0).getAmount(), "1.10"));
  }

  // ── blocking / incomplete ─────────────────────────────────────────────────────

  @Test
  void unknownInsuredTypeBlocksWithoutThrowing() {
    BgContributionResult r =
        BgContributionResolver.resolve(Collections.emptyList(), req("100.00", "DEFAULT", null));
    assertEquals(Status.NO_CONTRIBUTIONS_FOUND, r.getStatus());
    assertFalse(r.isResolved());
    assertTrue(r.isBlocking());
  }

  /**
   * ⚠️ ПРИЧИНАТА НАЗОВАВА И ДВЕТЕ ПОЛЕТА НА КЛЮЧА, не само вида осигурен.
   *
   * <p>Ключът в каталога е ДВОЙКАТА (вид осигурен, категория труд) и празният набор не казва кое от
   * двете е сгрешено — съобщение само с „вид осигурен: 01“ праща оператора към грешния екран.
   * Например код 01 при ПЪРВА категория няма нито един ред, а грешката е в категорията, не в кода.
   */
  @Test
  @DisplayName("⚠️ NO_CONTRIBUTIONS_FOUND назовава вида осигурен И категорията труд")
  void theBlockingReasonNamesBothHalvesOfTheKey() {
    BgContributionRequest impossiblePair =
        new BgContributionRequest(
            new BigDecimal("100.00"),
            "01",
            "1",
            true,
            "DEFAULT",
            null,
            LocalDate.of(2026, 1, 1),
            null);

    BgContributionResult r =
        BgContributionResolver.resolve(Collections.emptyList(), impossiblePair);

    assertEquals(Status.NO_CONTRIBUTIONS_FOUND, r.getStatus());
    assertTrue(
        r.getDetail().contains("вид осигурен 01"),
        "причината не назовава вида осигурен: " + r.getDetail());
    assertTrue(
        r.getDetail().contains("категория труд 1"),
        "⚠️ КАТЕГОРИЯТА Е НУЖНА. Без категорията в изречението операторът поправя вида осигурен —"
            + " единственото, което съобщението му показва — докато сгрешена е категорията, и тя е"
            + " на ДРУГ екран (служителя): "
            + r.getDetail());
    assertTrue(
        r.getDetail().contains("няма ставки за тази комбинация"),
        "причината не казва какво липсва: " + r.getDetail());
    assertTrue(
        r.getDetail().startsWith(BgInsuredTypeCategoryRule.noRatesFor("01", "1")),
        "⚠️ Отказът на фиша и отказът при ЗАПИС на договора трябва да назовават двойката с ЕДИН И"
            + " СЪЩ низ — инак едно и също състояние изглежда като два различни проблема: "
            + r.getDetail());
    assertTrue(
        r.getDetail().contains("2026-01-01"),
        "⚠️ И датата. Откакто записът отказва невъзможната двойка, празният набор ТУК най-често"
            + " значи „редовете не са в сила на този ден“, не „двойката е невъзможна“ — а без"
            + " датата изречението твърди повече, отколкото резолверът знае: "
            + r.getDetail());
  }

  @Test
  void missingPensionFundBlocks() {
    List<BgInsuranceContribution> rows = Arrays.asList(row("HEALTH", "4.80", "3.20"));
    BgContributionResult r = BgContributionResolver.resolve(rows, req("100.00", "DEFAULT", null));
    assertEquals(Status.MISSING_MANDATORY_FUND, r.getStatus());
    assertTrue(r.isBlocking());
  }

  /** Two PENSION rows for one key (code 27 reality) → block, do not sum. */
  @Test
  void duplicatePensionRowsBlock() {
    List<BgInsuranceContribution> code27 =
        Arrays.asList(
            row("IC-070-2026", "PENSION", "8.22", "6.58"),
            row("IC-072-2026", "PENSION", "14.80", "0.00"),
            row("IC-073-2026", "HEALTH", "4.80", "3.20"));
    BgContributionResult r = BgContributionResolver.resolve(code27, req("100.00", "DEFAULT", null));
    assertEquals(Status.DUPLICATE_FUND_ROWS, r.getStatus());
    assertTrue(r.isBlocking());
    assertFalse(r.isResolved());
    // Not summed: 8.22 + 14.80 = 23.02 must never appear.
    assertTrue(eq(r.totalEmployer(), "0.00"));
  }

  /** WITH_UPF cohort, pension present but no UPF row → resolved yet incomplete. */
  @Test
  void withUpfMissingUpfRowIsIncomplete() {
    List<BgInsuranceContribution> code27 =
        Arrays.asList(row("PENSION", "8.22", "6.58"), row("HEALTH", "4.80", "3.20"));
    BgContributionResult r = BgContributionResolver.resolve(code27, req("100.00", "DEFAULT", null));
    assertEquals(Status.RESOLVED_INCOMPLETE, r.getStatus());
    assertTrue(r.isResolved());
    assertTrue(r.isBlocking());
    assertFalse(r.getLines().isEmpty());
  }

  /** Duplicate and missing-UPF at once → the duplicate wins (checked first). */
  @Test
  void duplicateWinsOverIncomplete() {
    List<BgInsuranceContribution> code27 =
        Arrays.asList(
            row("IC-070-2026", "PENSION", "8.22", "6.58"),
            row("IC-072-2026", "PENSION", "14.80", "0.00"),
            row("IC-073-2026", "HEALTH", "4.80", "3.20"));
    BgContributionResult r = BgContributionResolver.resolve(code27, req("100.00", "DEFAULT", null));
    assertEquals(Status.DUPLICATE_FUND_ROWS, r.getStatus());
  }

  /** RESOLVED_INCOMPLETE still sums correctly for the lines that are present. */
  @Test
  void incompleteTotalsAreCorrectForPresentLines() {
    List<BgInsuranceContribution> code27 =
        Arrays.asList(row("PENSION", "8.22", "6.58"), row("HEALTH", "4.80", "3.20"));
    BgContributionResult r = BgContributionResolver.resolve(code27, req("100.00", "DEFAULT", null));
    assertEquals(Status.RESOLVED_INCOMPLETE, r.getStatus());
    assertTrue(eq(r.totalEmployer(), "13.02"));
    assertTrue(eq(r.totalEmployee(), "9.78"));
  }

  // ── Код 27 / вид обезщетение (заявката филтрира по основание; тук подаваме филтрирания набор) ──
  // ЗАБЕЛЕЖКА: резолверът не филтрира по режим/обхват/основание — това е задача на заявката
  // (BgInsuranceCalculationService, проверена интеграционно). Тук подаваме редовете, които заявката
  // би върнала, и проверяваме, че резолверът вече не вижда дубликат и смята вярно.

  /**
   * Регресия срещу капана с ANY режима: код 01, STANDARD, WITHOUT_UPF връща петте реда с
   * ANY/WITHOUT режим (PENSION-WITHOUT, ОЗМ, безработица, ЗОВ, ГВРС) и НЕ съдържа UPF. Ако ANY
   * редовете изчезнеха от заявката, наборът тук щеше да е орязан. (Заданието казва „шест", но
   * фондовете са пет — UPF отпада при WITHOUT_UPF; сверено срещу дървото.)
   */
  @Test
  void code01StandardWithoutUpfHasFiveFundsNoUpf() {
    List<BgInsuranceContribution> rows =
        Arrays.asList(
            row("PENSION", "11.02", "8.78"),
            row("OZM", "2.10", "1.40"),
            row("UNEMPLOYMENT", "0.60", "0.40"),
            row("HEALTH", "4.80", "3.20"),
            row("GVRS", "0.00", "0.00"));
    BgContributionResult r =
        BgContributionResolver.resolve(rows, req("100.00", "DEFAULT", null, false));
    assertEquals(Status.RESOLVED, r.getStatus());
    assertTrue(r.getLines().stream().noneMatch(l -> "UPF".equals(l.getFundCode())));
    assertTrue(r.getLines().stream().anyMatch(l -> "PENSION".equals(l.getFundCode())));
    assertTrue(r.getLines().stream().anyMatch(l -> "OZM".equals(l.getFundCode())));
    assertTrue(r.getLines().stream().anyMatch(l -> "GVRS".equals(l.getFundCode())) == false);
  }

  /**
   * Код 27, чл.222 ал.1 КТ, WITH_UPF → PENSION 8.22/6.58 И UPF 2.80/2.20, RESOLVED (не дубликат).
   */
  @Test
  void code27KtWithUpfResolvesWithPensionAndUpf() {
    List<BgInsuranceContribution> rows =
        Arrays.asList(row("PENSION", "8.22", "6.58"), row("UPF", "2.80", "2.20"));
    BgContributionResult r = BgContributionResolver.resolve(rows, req("100.00", "DEFAULT", null));
    assertEquals(Status.RESOLVED, r.getStatus());
    assertTrue(eq(r.totalEmployer(), "11.02")); // 8.22 + 2.80
    assertTrue(eq(r.totalEmployee(), "8.78")); // 6.58 + 2.20
    assertTrue(r.getLines().stream().anyMatch(l -> "UPF".equals(l.getFundCode())));
  }

  /** Код 27, чл.106 ал.2 ЗДСл, WITHOUT_UPF → PENSION 19.80/0.00, RESOLVED. */
  @Test
  void code27ZdslWithoutUpfResolves() {
    List<BgInsuranceContribution> rows = Arrays.asList(row("PENSION", "19.80", "0.00"));
    BgContributionResult r =
        BgContributionResolver.resolve(rows, req("100.00", "DEFAULT", null, false));
    assertEquals(Status.RESOLVED, r.getStatus());
    assertTrue(eq(r.totalEmployer(), "19.80"));
    assertTrue(eq(r.totalEmployee(), "0.00"));
  }

  /** Код 27, чл.222 ал.1 КТ — чуждото основание (14.80/19.80 на ЗДСл) не е влязло. */
  @Test
  void code27KtExcludesForeignGroundRates() {
    List<BgInsuranceContribution> rows =
        Arrays.asList(row("PENSION", "8.22", "6.58"), row("UPF", "2.80", "2.20"));
    BgContributionResult r = BgContributionResolver.resolve(rows, req("100.00", "DEFAULT", null));
    assertTrue(
        r.getLines().stream().noneMatch(l -> eq(l.getRate(), "14.80") || eq(l.getRate(), "19.80")));
  }

  /** Патч 1: код 12, ALL_RISKS, WITHOUT_UPF → PENSION 19.80 + ОЗМ 3.50 + ЗОВ 8.00, общо 31.30. */
  @Test
  void code12AllRisksWithoutUpfTotals() {
    List<BgInsuranceContribution> rows =
        Arrays.asList(
            row("PENSION", "0.00", "19.80"),
            row("OZM", "0.00", "3.50"),
            row("HEALTH", "0.00", "8.00"));
    BgContributionResult r =
        BgContributionResolver.resolve(rows, req("100.00", "DEFAULT", null, false));
    assertTrue(eq(r.totalEmployee(), "31.30"));
    assertTrue(eq(r.totalEmployer(), "0.00"));
  }

  /** Патч 1: код 12, PENSION_ONLY, WITHOUT_UPF → PENSION 19.80 + ЗОВ 8.00, общо 27.80, БЕЗ ОЗМ. */
  @Test
  void code12PensionOnlyWithoutUpfHasNoOzm() {
    List<BgInsuranceContribution> rows =
        Arrays.asList(row("PENSION", "0.00", "19.80"), row("HEALTH", "0.00", "8.00"));
    BgContributionResult r =
        BgContributionResolver.resolve(rows, req("100.00", "DEFAULT", null, false));
    assertTrue(eq(r.totalEmployee(), "27.80"));
    assertTrue(r.getLines().stream().noneMatch(l -> "OZM".equals(l.getFundCode())));
  }

  // ── видът на ставката ───────────────────────────────────────────────────
  //
  // ⚠️ ЧЕСТНАТА БЕЛЕЖКА, ПО ОБРАЗЕЦА НА BgInertSeamTest. Четирите теста отдолу викат
  // BgContributionResolver.resolve ПРЯКО, с редове, построени в паметта. Тоест те доказват, че
  // пазачът работи, но НЕ доказват, че производственият път минава през него:
  // BgPaySlipService#contributions е още инертен (`return null;`) и е зает точно такъв в
  // KNOWN_INERT на BgInertSeamTest. Никой фиш днес не стига дотук.
  //
  // Тоест ТЕЗИ ТЕСТОВЕ МОГАТ ДА МИНАТ, БЕЗ ДА СА МИНАЛИ ПО ПРОИЗВОДСТВЕНИЯ ПЪТ, и това е разликата
  // между „пазачът е написан“ и „пазачът пази“. Второто става доказуемо чак когато WS-3 навърже
  // шева; дотогава зеленото тук значи само първото и не бива да се чете като второто.
  //
  // ⚠️ И ВТОРО ОГРАНИЧЕНИЕ, ОТ ДАННИТЕ. Нито един от 143-те seed реда не носи MISSING (виж
  // BgRateKindSeedTest#theDistributionIsTheDeclaredOne), защото единственият известен случай —
  // фонд „Пенсии“ по чл. 69 КСО — няма ред в таблицата изобщо. Затова редът долу е ПОСТРОЕН, а не
  // взет от seed-а: пазачът чака стойност, която данните още не произвеждат.

  @Test
  void aMissingRateRefusesInsteadOfCountingAsZero() {
    List<BgInsuranceContribution> rows =
        Arrays.asList(
            row("PENSION", "8.2200", "6.5800"),
            rowWithKinds("IC-999-2026", "HEALTH", "0.0000", "0.0000", "MISSING", "MISSING"));

    BgContributionResult r =
        BgContributionResolver.resolve(rows, req("100.00", "DEFAULT", null, false));

    assertEquals(Status.MISSING_RATE, r.getStatus());
    assertTrue(r.getLines().isEmpty(), "при отказ не се връщат редове");
    assertTrue(r.isBlocking());
    assertTrue(r.getDetail().contains("IC-999-2026"), "детайлът сочи КОЙ ред: " + r.getDetail());
  }

  @Test
  void aMissingRateRefusesEvenWhenTheOtherPayerHasARealRate() {
    // ⚠️ Точно случаят, който една колона на РЕД не би могла да опише: истинска ставка при единия
    // платец и липсваща стойност при другия, на един и същи ред.
    List<BgInsuranceContribution> rows =
        Collections.singletonList(
            rowWithKinds("IC-998-2026", "PENSION", "8.2200", "0.0000", "VALUE", "MISSING"));

    BgContributionResult r =
        BgContributionResolver.resolve(rows, req("100.00", "DEFAULT", null, false));

    assertEquals(Status.MISSING_RATE, r.getStatus());
    assertTrue(r.getDetail().contains("лице"), "детайлът сочи КОЙ платец: " + r.getDetail());
  }

  @Test
  void bothKindsOfGenuineZeroStillCompute() {
    // Законовата и структурната нула НЕ са отказ: те са истински нули и редът просто не дава ред.
    List<BgInsuranceContribution> rows =
        Arrays.asList(
            row("PENSION", "8.2200", "6.5800"),
            rowWithKinds("IC-007-2026", "GVRS", "0.0000", "0.0000", "LEGAL_ZERO", "LEGAL_ZERO"),
            rowWithKinds(
                "IC-045-2026", "TEACHER_PF", "4.3000", "0.0000", "VALUE", "STRUCTURAL_ZERO"));

    BgContributionResult r =
        BgContributionResolver.resolve(rows, req("100.00", "DEFAULT", null, false));

    assertEquals(Status.RESOLVED, r.getStatus());
    assertTrue(eq(r.totalEmployer(), "12.52"), "8,22 + 4,30, без нито един ред за ГВРС");
    assertTrue(eq(r.totalEmployee(), "6.58"));
    assertTrue(r.getLines().stream().noneMatch(l -> "GVRS".equals(l.getFundCode())));
  }

  @Test
  void anUndeclaredOrUnknownKindRefusesRatherThanAssumingValue() {
    List<BgInsuranceContribution> undeclared =
        Collections.singletonList(
            rowWithKinds("IC-997-2026", "PENSION", "8.2200", "6.5800", "VALUE", null));
    assertEquals(
        Status.UNDECLARED_RATE_KIND,
        BgContributionResolver.resolve(undeclared, req("100.00", "DEFAULT", null, false))
            .getStatus());

    List<BgInsuranceContribution> unknown =
        Collections.singletonList(
            rowWithKinds("IC-996-2026", "PENSION", "8.2200", "6.5800", "VALUE", "ZAKONOVA"));
    assertEquals(
        Status.UNDECLARED_RATE_KIND,
        BgContributionResolver.resolve(unknown, req("100.00", "DEFAULT", null, false)).getStatus());
  }
}
