package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.BgInsurableIncomeResult.Status;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the insurable-base rule {@code min(max(gross, МОД floor, statutory floor), cap)}.
 *
 * <p>No database: both bounds are passed in, mirroring how the resolvers take their rows. The 2026
 * cap is 2300.00 (from 01.08.2026); the floors and gross vary per case. A {@code null} statutory
 * floor means the insured type has none — most cases, since types with МОД carry {@code NONE}.
 */
class BgInsurableIncomeServiceTest {

  private static final BigDecimal CAP = new BigDecimal("2300.00");
  private static final LocalDate CAP_FROM = LocalDate.of(2026, 8, 1);

  private static BgMinIncomeResult mod(String amount) {
    return BgMinIncomeResult.resolved(new BigDecimal(amount), "50", 1);
  }

  private static boolean eq(BigDecimal a, String b) {
    return a != null && a.compareTo(new BigDecimal(b)) == 0;
  }

  @Test
  void grossBelowModFloorsToMod() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("500.00"), mod("933.00"), null, true, CAP, CAP_FROM);
    assertTrue(r.isResolved());
    assertTrue(eq(r.getAmount(), "933.00"));
  }

  @Test
  void grossBetweenModAndCapIsGross() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("1500.00"), mod("933.00"), null, true, CAP, CAP_FROM);
    assertTrue(eq(r.getAmount(), "1500.00"));
  }

  @Test
  void grossAboveCapIsCapped() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("3000.00"), mod("933.00"), null, true, CAP, CAP_FROM);
    assertTrue(eq(r.getAmount(), "2300.00"));
  }

  @Test
  void grossExactlyAtCapIsCap() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("2300.00"), mod("933.00"), null, true, CAP, CAP_FROM);
    assertTrue(eq(r.getAmount(), "2300.00"));
  }

  @Test
  void modNotApplicableUsesGrossCapped() {
    BgMinIncomeResult notApplicable = BgMinIncomeResult.notApplicable("код 05");
    BgInsurableIncomeResult within =
        BgInsurableIncomeService.compute(
            new BigDecimal("1500.00"), notApplicable, null, true, CAP, CAP_FROM);
    assertTrue(eq(within.getAmount(), "1500.00"));
    BgInsurableIncomeResult over =
        BgInsurableIncomeService.compute(
            new BigDecimal("3000.00"), notApplicable, null, true, CAP, CAP_FROM);
    assertTrue(eq(over.getAmount(), "2300.00"));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // A blocking МОД status must not be mistaken for "МОД does not apply".
  // ─────────────────────────────────────────────────────────────────────────

  /**
   * The case that must not pass silently: the contract carries no НКПД, so the МОД floor cannot be
   * found. Skipping the floor would return the base RESOLVED at the raw gross — a lower base with
   * nothing to say why.
   */
  @Test
  void blockingModBlocksTheBase() {
    BgMinIncomeResult blocked =
        BgMinIncomeResult.failure(
            BgMinIncomeResult.Status.NKPD_MISSING, "НКПД кодът не е зададен на трудовия договор");
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("400.00"), blocked, null, true, CAP, CAP_FROM);
    assertEquals(Status.MOD_BLOCKED, r.getStatus());
    assertFalse(r.isResolved());
    assertTrue(r.isBlocking());
    assertNull(r.getAmount());
    assertNotNull(r.getDetail());
  }

  /** Every non-resolved, non-applicable status blocks — not just the one that was noticed. */
  @Test
  void everyBlockingModStatusBlocks() {
    BgMinIncomeResult.Status[] blocking = {
      BgMinIncomeResult.Status.INSURED_TYPE_NOT_FOUND,
      BgMinIncomeResult.Status.NKPD_MISSING,
      BgMinIncomeResult.Status.NKPD_INVALID,
      BgMinIncomeResult.Status.QUALIFICATION_GROUP_ZERO,
      BgMinIncomeResult.Status.NKID_MISSING,
      BgMinIncomeResult.Status.NKID_NOT_FOUND,
      BgMinIncomeResult.Status.AMBIGUOUS_REQUIRES_OVERRIDE,
      BgMinIncomeResult.Status.NO_MOD_ROW_FOR_NKID,
      BgMinIncomeResult.Status.MOD_ROW_NOT_FOUND,
      BgMinIncomeResult.Status.AMOUNT_NOT_DEFINED
    };
    for (BgMinIncomeResult.Status s : blocking) {
      BgInsurableIncomeResult r =
          BgInsurableIncomeService.compute(
              new BigDecimal("400.00"),
              BgMinIncomeResult.failure(s, "детайл"),
              null,
              true,
              CAP,
              CAP_FROM);
      assertEquals(Status.MOD_BLOCKED, r.getStatus(), "очаква се блокиране при " + s);
      assertNull(r.getAmount(), "не бива да има база при " + s);
    }
  }

  /**
   * The distinction itself, pinned in one test: same gross, same cap, two МОД outcomes, two
   * different answers. If this ever collapses to one, the silent case is back.
   */
  @Test
  void notApplicableAndBlockingAreNotTheSame() {
    BigDecimal gross = new BigDecimal("400.00");
    BgInsurableIncomeResult applies =
        BgInsurableIncomeService.compute(
            gross, BgMinIncomeResult.notApplicable("вид осигурен: 12"), null, true, CAP, CAP_FROM);
    BgInsurableIncomeResult blocked =
        BgInsurableIncomeService.compute(
            gross,
            BgMinIncomeResult.failure(BgMinIncomeResult.Status.NKPD_MISSING, "вид осигурен: 01"),
            null,
            true,
            CAP,
            CAP_FROM);
    assertTrue(applies.isResolved());
    assertTrue(eq(applies.getAmount(), "400.00"));
    assertFalse(blocked.isResolved());
    assertEquals(Status.MOD_BLOCKED, blocked.getStatus());
  }

  /** A null МОД is not a blocking МОД — the callers that pass none must keep working. */
  @Test
  void nullModStillResolves() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("1500.00"), null, null, true, CAP, CAP_FROM);
    assertTrue(r.isResolved());
    assertTrue(eq(r.getAmount(), "1500.00"));
  }

  // ─────────────────────────────────────────────────────────────────────────
  // The second floor: flat statutory minimum for the insured types without МОД.
  // МРЗ (КСО чл.6 ал.5а) for codes 05 and 24; the self-insured minimum for 12 and 22.
  // Свиването зависи от ПРАВООТНОШЕНИЕТО, не от прага: наетите се свиват, ДУК и СОЛ — не.
  // ─────────────────────────────────────────────────────────────────────────

  /** The August 2026 self-insured minimum. */
  private static final BigDecimal FLOOR = new BigDecimal("620.20");

  /**
   * The headline case: a self-insured person declaring 400 EUR must not be insured on 400 EUR. МОД
   * does not apply to code 12 — correctly — so the statutory floor has to stand in.
   */
  @Test
  void statutoryFloorLiftsTheBaseWhenModDoesNotApply() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("400.00"),
            BgMinIncomeResult.notApplicable("вид осигурен: 12"),
            FLOOR,
            false,
            CAP,
            CAP_FROM);
    assertTrue(r.isResolved());
    assertTrue(eq(r.getAmount(), "620.20"));
  }

  /** A floor is a floor: it never pulls a higher gross down. */
  @Test
  void statutoryFloorDoesNotLowerAHigherGross() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("1500.00"),
            BgMinIncomeResult.notApplicable("вид осигурен: 12"),
            FLOOR,
            false,
            CAP,
            CAP_FROM);
    assertTrue(eq(r.getAmount(), "1500.00"));
  }

  /** The cap still wins over everything — the ceiling is applied last, on purpose. */
  @Test
  void capBeatsAStatutoryFloorAboveIt() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("100.00"),
            BgMinIncomeResult.notApplicable("вид осигурен: 12"),
            new BigDecimal("2500.00"),
            true,
            CAP,
            CAP_FROM);
    assertTrue(eq(r.getAmount(), "2300.00"));
  }

  /** When both floors are present the higher one wins; neither is dropped. */
  @Test
  void higherOfTheTwoFloorsWins() {
    BgInsurableIncomeResult modHigher =
        BgInsurableIncomeService.compute(
            new BigDecimal("100.00"), mod("933.00"), FLOOR, true, CAP, CAP_FROM);
    assertTrue(eq(modHigher.getAmount(), "933.00"));
    BgInsurableIncomeResult floorHigher =
        BgInsurableIncomeService.compute(
            new BigDecimal("100.00"), mod("500.00"), FLOOR, true, CAP, CAP_FROM);
    assertTrue(eq(floorHigher.getAmount(), "620.20"));
  }

  /**
   * Решено: прагът е месечна величина и се свива с отработеното. При четиричасов ден се дължи върху
   * половината от МРЗ, не върху цялата. 84 от 168 законоустановени часа → 620,20 × 0,5 = 310,10.
   */
  @Test
  void statutoryFloorIsProratedLikeMod() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("0.00"),
            BgMinIncomeResult.notApplicable("вид осигурен: 05"),
            FLOOR,
            true,
            CAP,
            CAP_FROM,
            BgWorkTimeFacts.byHours(bd("168"), bd("84"), null));
    assertTrue(eq(r.getAmount(), "310.10"), "четири часа → половин МРЗ");
  }

  /**
   * Двата прага взимат един и същ коефициент, тоест по-високият остава по-висок и след свиването —
   * редът на пропорционализиране и сравняване не може да се разминат.
   */
  @Test
  void bothFloorsShrinkTogetherSoTheHigherOneStaysHigher() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("0.00"),
            mod("550.66"),
            FLOOR,
            true,
            CAP,
            CAP_FROM,
            BgWorkTimeFacts.byHours(bd("168"), bd("84"), null));
    // МОД 550.66 → 275.33 · праг 620.20 → 310.10 · печели по-високият
    assertTrue(eq(r.getAmount(), "310.10"), "по-високият праг печели и след свиването");
  }

  /**
   * Обратната страна на горното решение: минималният за СОЛ **не** се свива с часове.
   * Самоосигуряващо се лице няма работно време, от което да е част — както и договорът за
   * управление и контрол. Същите факти като горния тест, различен флаг, различно число.
   */
  @Test
  void selfInsuredFloorIsNotProrated() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("0.00"),
            BgMinIncomeResult.notApplicable("вид осигурен: 12"),
            FLOOR,
            false,
            CAP,
            CAP_FROM,
            BgWorkTimeFacts.byHours(bd("168"), bd("84"), null));
    assertTrue(eq(r.getAmount(), "620.20"), "минималният за СОЛ остава цял при непълно време");
  }

  /**
   * ДУК — вид осигурен 10. Има МОД, но няма работно време: `bgZeroWorkTime` е true за него. Тоест
   * МОД за управител **не** се свива, макар за наето лице със същите факти да се свива. Решено: „не
   * се пропорционира ДУК".
   *
   * <p>Двата случая са един до друг нарочно — това е цялата разлика, и тя е един флаг.
   */
  @Test
  void modIsNotProratedForRelationshipsWithoutWorkTime() {
    BgWorkTimeFacts halfMonth = BgWorkTimeFacts.byHours(bd("168"), bd("84"), null);

    BgInsurableIncomeResult employee =
        BgInsurableIncomeService.compute(
            new BigDecimal("0.00"), mod("933.00"), null, true, CAP, CAP_FROM, halfMonth);
    assertTrue(eq(employee.getAmount(), "466.50"), "наето лице: МОД се свива наполовина");

    BgInsurableIncomeResult manager =
        BgInsurableIncomeService.compute(
            new BigDecimal("0.00"), mod("933.00"), null, false, CAP, CAP_FROM, halfMonth);
    assertTrue(eq(manager.getAmount(), "933.00"), "ДУК: МОД остава цял");
  }

  /** Пълен месец не свива нищо — регресия срещу коефициент, приложен два пъти. */
  @Test
  void fullMonthLeavesTheStatutoryFloorWhole() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("0.00"),
            BgMinIncomeResult.notApplicable("вид осигурен: 05"),
            FLOOR,
            true,
            CAP,
            CAP_FROM,
            BgWorkTimeFacts.fullMonth());
    assertTrue(eq(r.getAmount(), "620.20"));
  }

  /** No statutory floor must behave exactly as before it existed. */
  @Test
  void nullStatutoryFloorChangesNothing() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("400.00"),
            BgMinIncomeResult.notApplicable("вид осигурен: 14"),
            null,
            false,
            CAP,
            CAP_FROM);
    assertTrue(r.isResolved());
    assertTrue(eq(r.getAmount(), "400.00"));
  }

  @Test
  void capCarriesPeriodForTransparency() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("1500.00"), mod("933.00"), null, true, CAP, CAP_FROM);
    assertTrue(eq(r.getCapAmount(), "2300.00"));
    assertEquals(CAP_FROM, r.getCapValidFrom());
  }

  @Test
  void missingCapBlocks() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("1500.00"), mod("933.00"), null, true, null, null);
    assertEquals(Status.MISSING_CAP, r.getStatus());
    assertFalse(r.isResolved());
    assertTrue(r.isBlocking());
  }

  // ─────────────────────────────────────────────────────────────────────────
  // Proration of the МОД floor — НЕВД art. 1 par. 3 (days) and par. 4 (hours).
  // Gross is zero in most cases so the floor is what shows through in the base.
  // ─────────────────────────────────────────────────────────────────────────

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static BgInsurableIncomeResult base(
      String gross, String modAmount, BgWorkTimeFacts facts) {
    return BgInsurableIncomeService.compute(
        bd(gross), mod(modAmount), null, true, CAP, CAP_FROM, facts);
  }

  /** Cross-check against another system: half a month of hours halves the floor exactly. */
  @Test
  void hourlyProrationMatchesReferenceFigure() {
    BgInsurableIncomeResult r =
        base("0.00", "550.66", BgWorkTimeFacts.byHours(bd("168"), bd("84"), null));
    assertTrue(r.isResolved());
    assertTrue(eq(r.getAmount(), "275.33"), "expected 275.33, got " + r.getAmount());
  }

  /**
   * The case that breaks the naive formula. 40 contracted hours a month over 21 working days is
   * 40/168 = 0.2381, not the daily 2/8 = 0.2500. Getting 137.67 here means someone reintroduced the
   * daily ratio.
   */
  @Test
  void hourlyProrationUsesMonthlyStatutoryHoursNotDailyNorm() {
    BgInsurableIncomeResult r =
        base("0.00", "550.66", BgWorkTimeFacts.byHours(bd("168"), bd("40"), null));
    assertTrue(eq(r.getAmount(), "131.11"), "expected 131.11, got " + r.getAmount());
    assertFalse(eq(r.getAmount(), "137.67"), "daily ratio 2/8 leaked back into the calculation");
  }

  /** Art. 40 par. 5 КСО hours count towards the numerator: 80 + 8 must equal a plain 88. */
  @Test
  void art40Par5HoursCountTowardsTheNumerator() {
    BgInsurableIncomeResult withSickDays =
        base("0.00", "550.66", BgWorkTimeFacts.byHours(bd("168"), bd("80"), bd("8")));
    BgInsurableIncomeResult asEightyEight =
        base("0.00", "550.66", BgWorkTimeFacts.byHours(bd("168"), bd("88"), null));
    assertTrue(withSickDays.isResolved());
    assertEquals(0, withSickDays.getAmount().compareTo(asEightyEight.getAmount()));
    assertTrue(eq(withSickDays.getAmount(), "288.44"), "got " + withSickDays.getAmount());
  }

  /** The day path — art. 1 par. 3 — taken when no hour facts are supplied. */
  @Test
  void dailyProrationWhenNoHourDataIsSupplied() {
    BgInsurableIncomeResult r =
        base("0.00", "620.20", BgWorkTimeFacts.byDays(bd("21"), bd("10"), null));
    assertTrue(r.isResolved());
    assertTrue(eq(r.getAmount(), "295.33"), "expected 295.33, got " + r.getAmount());
  }

  /**
   * Art. 40 par. 5 КСО days count too, on the day path.
   *
   * <p>⚠️ The split 8 + 2 is an arbitrary fixture, not the statutory count — this test asserts only
   * that the two paths agree. A 7 + 3 split would invite the reader to see the old three-day rule
   * in it: по указание на НОИ, чл. 40 ал. 5 КСО, от 1 януари 2024 г. дните са ДВА.
   */
  @Test
  void art40Par5DaysCountTowardsTheNumerator() {
    BgInsurableIncomeResult withSickDays =
        base("0.00", "620.20", BgWorkTimeFacts.byDays(bd("21"), bd("8"), bd("2")));
    BgInsurableIncomeResult asTen =
        base("0.00", "620.20", BgWorkTimeFacts.byDays(bd("21"), bd("10"), null));
    assertEquals(0, withSickDays.getAmount().compareTo(asTen.getAmount()));
  }

  /**
   * ⚠️ ТАВАНЪТ 1 Е НАШЕ ЧЕТЕНЕ, И ТО СВЕРЕНО СРЕЩУ АКТА. Коефициентът се ограничава до 1, тоест
   * извънреден труд не вдига законовия минимум: прагът е праг, не цел.
   *
   * <p>Сверено дословно срещу НЕВД чл. 1 ал. 3 и ал. 4 (обн. ДВ бр. 263 от 30.12.1999 г., ред.
   * 2024) на 18.08.2026 г.: нито едната алинея НЕ съдържа таван — и двете дават само делене на
   * месечния знаменател и умножение по отработеното. Тоест това остава ЧЕТЕНЕ, а не цитат; сверката
   * смени само това, че вече се знае срещу какво е сверено.
   *
   * <p>Този тест е ЕДИНСТВЕНОТО място, което трябва да се смени, ако някога се появи основание за
   * друго четене — заедно с javadoc-а на {@code BgInsurableIncomeService.prorate}, който стои до
   * него.
   */
  @Test
  void coefficientIsCappedAtOneSoOvertimeDoesNotRaiseTheFloor() {
    BgInsurableIncomeResult r =
        base("0.00", "550.66", BgWorkTimeFacts.byHours(bd("168"), bd("180"), null));
    assertTrue(r.isResolved());
    assertTrue(
        eq(r.getAmount(), "550.66"),
        "очаква се МОД непроменен, получено "
            + r.getAmount()
            + ". ⚠️ Таванът 1 е НАШЕ ЧЕТЕНЕ (прагът е праг, не цел, която расте над пълен месец):"
            + " сверено дословно срещу НЕВД чл. 1 ал. 3 и ал. 4 (обн. ДВ бр. 263 от 30.12.1999 г.,"
            + " ред. 2024) на 18.08.2026 г. — нормата НЕ съдържа таван, тоест ограничението е наш"
            + " извод, а не разпоредба. Ако този тест пада заради НАРОЧНА промяна, смени и четенето в"
            + " BgInsurableIncomeService.prorate: двете стоят заедно и се менят заедно.");
  }

  /** A zero denominator blocks — no division by zero, no silent zero, no silent full month. */
  @Test
  void zeroDenominatorBlocks() {
    BgInsurableIncomeResult hours =
        base("0.00", "550.66", BgWorkTimeFacts.byHours(BigDecimal.ZERO, bd("84"), null));
    assertEquals(Status.INVALID_WORK_TIME, hours.getStatus());
    assertTrue(hours.isBlocking());
    assertFalse(hours.isResolved());
    assertNull(hours.getAmount());
    assertNotNull(hours.getDetail());

    BgInsurableIncomeResult days =
        base("0.00", "620.20", BgWorkTimeFacts.byDays(BigDecimal.ZERO, bd("10"), null));
    assertEquals(Status.INVALID_WORK_TIME, days.getStatus());
  }

  /** A missing or negative denominator is the same data error as a zero one. */
  @Test
  void missingOrNegativeDenominatorBlocks() {
    BgInsurableIncomeResult missing =
        base("0.00", "550.66", BgWorkTimeFacts.byHours(null, bd("84"), null));
    assertEquals(Status.INVALID_WORK_TIME, missing.getStatus());

    BgInsurableIncomeResult negative =
        base("0.00", "550.66", BgWorkTimeFacts.byHours(bd("-168"), bd("84"), null));
    assertEquals(Status.INVALID_WORK_TIME, negative.getStatus());
  }

  /** Regression: fullMonth() must be indistinguishable from the pre-proration behaviour. */
  @Test
  void fullMonthMatchesTheUnproratedBehaviour() {
    String[][] cases = {
      {"500.00", "933.00"}, {"1500.00", "933.00"}, {"3000.00", "933.00"}, {"2300.00", "933.00"}
    };
    for (String[] c : cases) {
      BgInsurableIncomeResult legacy =
          BgInsurableIncomeService.compute(bd(c[0]), mod(c[1]), null, true, CAP, CAP_FROM);
      BgInsurableIncomeResult withFacts = base(c[0], c[1], BgWorkTimeFacts.fullMonth());
      assertEquals(legacy.getStatus(), withFacts.getStatus());
      assertEquals(
          0,
          legacy.getAmount().compareTo(withFacts.getAmount()),
          "gross " + c[0] + " diverged: " + legacy.getAmount() + " vs " + withFacts.getAmount());
    }
  }

  /** Proration lowers the floor; it never lowers what was actually paid. */
  @Test
  void grossAboveTheProratedFloorWins() {
    BgInsurableIncomeResult r =
        base("400.00", "550.66", BgWorkTimeFacts.byHours(bd("168"), bd("84"), null));
    assertTrue(eq(r.getAmount(), "400.00"), "gross should win over the 275.33 floor");
  }

  /** The cap applies after proration and is not itself prorated — half of 2300 would be 1150. */
  @Test
  void capIsAppliedAfterProrationAndIsNotProrated() {
    BgInsurableIncomeResult r =
        base("3000.00", "550.66", BgWorkTimeFacts.byHours(bd("168"), bd("84"), null));
    assertTrue(eq(r.getAmount(), "2300.00"), "expected the full cap, got " + r.getAmount());
    assertTrue(eq(r.getCapAmount(), "2300.00"));
    assertFalse(eq(r.getAmount(), "1150.00"), "the cap was prorated — it must not be");
  }

  /** Proration applies to the МОД floor only; a non-applicable МОД leaves the gross alone. */
  @Test
  void prorationDoesNotTouchGrossWhenModIsNotApplicable() {
    BgInsurableIncomeResult r =
        BgInsurableIncomeService.compute(
            new BigDecimal("1500.00"),
            BgMinIncomeResult.notApplicable("код 05"),
            null,
            true,
            CAP,
            CAP_FROM,
            BgWorkTimeFacts.byHours(bd("168"), bd("84"), null));
    assertTrue(eq(r.getAmount(), "1500.00"), "gross must not be prorated");
  }
}
