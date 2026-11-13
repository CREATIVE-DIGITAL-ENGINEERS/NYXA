package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for "which compensations does this termination ground give rise to".
 *
 * <p>No database and no Guice: the resolver takes types built in memory. The fixtures mirror the
 * eight seeded rows, because the traps are in the real data — see {@link
 * #groundIsNotMatchedBySubstring}.
 */
class BgCompensationProposalResolverTest {

  // ── fixtures ────────────────────────────────────────────────────────────

  private static BgCompensationType type(String code, boolean anyGround, String grounds) {
    return type(code, anyGround, grounds, true);
  }

  private static BgCompensationType type(
      String code, boolean anyGround, String grounds, boolean active) {
    return type(code, anyGround, grounds, active, "KT");
  }

  private static BgCompensationType type(
      String code, boolean anyGround, String grounds, boolean active, String family) {
    BgCompensationType t = new BgCompensationType();
    t.setCode(code);
    t.setAppliesToAnyGround(anyGround);
    t.setTriggerGrounds(grounds);
    t.setActive(active);
    t.setLegalFamily(family);
    return t;
  }

  /** The nine seeded rows, with only the fields this resolver reads. */
  private static List<BgCompensationType> seed() {
    return Arrays.asList(
        type("comp_kt_222_1", false, "328_1_1,328_1_2,328_1_3"),
        type("comp_kt_222_2", false, "325_1_9"),
        type("comp_kt_222_3", false, "328_1_10"),
        type("comp_kt_224_1", true, null),
        type("comp_kt_220_1", false, null),
        type("comp_kt_220_2", false, null),
        type("comp_kt_331", false, "331"),
        type("comp_zdsl_106_2", false, null, true, "ZDSL"),
        type("comp_zvo_58_3", false, "58_1_3", true, "ZVO"));
  }

  private static List<String> codes(List<BgCompensationType> types) {
    return types.stream().map(BgCompensationType::getCode).collect(Collectors.toList());
  }

  // ── съпоставяне по основание ──────────────────────────────────────────────

  @Test
  void groundMatchesAnEntryInTheList() {
    assertTrue(
        BgCompensationProposalResolver.matches(
            type("comp_kt_222_1", false, "328_1_1,328_1_2,328_1_3"), "328_1_2"));
  }

  @Test
  void groundOutsideTheListDoesNotMatch() {
    assertFalse(
        BgCompensationProposalResolver.matches(
            type("comp_kt_222_1", false, "328_1_1,328_1_2,328_1_3"), "326"));
  }

  /**
   * Основанията се сравняват цели. Селекцията съдържа и 328_1_1, и 328_1_10, тоест наивното {@code
   * contains} би предложило обезщетението при пенсиониране върху обикновено съкращение — и
   * обратното. И двете посоки се проверяват, защото и двете са в истинските данни.
   */
  @Test
  void groundIsNotMatchedBySubstring() {
    BgCompensationType pension = type("comp_kt_222_3", false, "328_1_10");
    assertFalse(
        BgCompensationProposalResolver.matches(pension, "328_1_1"),
        "328_1_1 не бива да пали реда за 328_1_10");

    BgCompensationType redundancy = type("comp_kt_222_1", false, "328_1_1,328_1_2,328_1_3");
    assertFalse(
        BgCompensationProposalResolver.matches(redundancy, "328_1_10"),
        "328_1_10 не бива да пали реда за 328_1_1");

    assertTrue(BgCompensationProposalResolver.matches(pension, "328_1_10"));
  }

  @Test
  void singleEntryListMatchesExactly() {
    BgCompensationType art331 = type("comp_kt_331", false, "331");
    assertTrue(BgCompensationProposalResolver.matches(art331, "331"));
    assertFalse(BgCompensationProposalResolver.matches(art331, "33"));
    assertFalse(BgCompensationProposalResolver.matches(art331, "3310"));
  }

  @Test
  void whitespaceAroundEntriesIsTolerated() {
    assertTrue(
        BgCompensationProposalResolver.matches(type("x", false, " 328_1_1 , 328_1_2 "), "328_1_2"));
    assertTrue(
        BgCompensationProposalResolver.matches(type("x", false, "328_1_1,328_1_2"), " 328_1_2 "));
  }

  // ── „при всяко основание" ─────────────────────────────────────────────────

  @Test
  void appliesToAnyGroundMatchesEveryGround() {
    BgCompensationType leave = type("comp_kt_224_1", true, null);
    assertTrue(BgCompensationProposalResolver.matches(leave, "328_1_2"));
    assertTrue(BgCompensationProposalResolver.matches(leave, "326"));
    assertTrue(BgCompensationProposalResolver.matches(leave, "71_1"));
  }

  /**
   * Неползваният отпуск се дължи, защото правоотношението свършва, не заради начина. Ако
   * основанието още не е записано, редът пак се предлага — предложен ред се изключва с едно
   * действие, а липсващият мълчи.
   */
  @Test
  void appliesToAnyGroundMatchesEvenWithoutAGround() {
    BgCompensationType leave = type("comp_kt_224_1", true, null);
    assertTrue(BgCompensationProposalResolver.matches(leave, null));
    assertTrue(BgCompensationProposalResolver.matches(leave, "   "));
  }

  // ── трите реда, които нарочно не се предлагат по основание ────────────────

  /**
   * чл. 220 ал. 1 и ал. 2 се пораждат от неспазено предизвестие — отделен факт, не основание — а
   * обезщетението по ЗДСл иска собствена номенклатура. И трите са с празни triggerGrounds и
   * изключено appliesToAnyGround. Това не е дупка в данните.
   */
  @Test
  void typesWithNeitherGroundNorAnyFlagAreNeverProposed() {
    for (String code : Arrays.asList("comp_kt_220_1", "comp_kt_220_2", "comp_zdsl_106_2")) {
      BgCompensationType t = type(code, false, null);
      assertFalse(BgCompensationProposalResolver.matches(t, "328_1_2"), code);
      assertFalse(BgCompensationProposalResolver.matches(t, null), code);
    }
    assertFalse(BgCompensationProposalResolver.matches(type("x", false, "  "), "328_1_2"));
  }

  // ── състояние и празни входове ────────────────────────────────────────────

  @Test
  void inactiveTypeIsNeverProposed() {
    assertFalse(
        BgCompensationProposalResolver.matches(type("x", true, "328_1_2", false), "328_1_2"));
    assertFalse(
        BgCompensationProposalResolver.matches(type("x", false, "328_1_2", false), "328_1_2"));
  }

  @Test
  void nullTypeAndNullListAreSafe() {
    assertFalse(BgCompensationProposalResolver.matches(null, "328_1_2"));
    assertTrue(BgCompensationProposalResolver.proposedFor(null, "328_1_2").isEmpty());
    assertTrue(
        BgCompensationProposalResolver.proposedFor(Collections.emptyList(), "328_1_2").isEmpty());
  }

  // ── върху целия seed ──────────────────────────────────────────────────────

  @Test
  void redundancyGroundProposesSeveranceAndUnusedLeave() {
    // 328_1_2 — съкращаване на щата. Договор 2 в тестовата база е точно този случай.
    assertEquals(
        Arrays.asList("comp_kt_222_1", "comp_kt_224_1"),
        codes(BgCompensationProposalResolver.proposedFor(seed(), "328_1_2")));
  }

  @Test
  void pensionGroundProposesItsOwnRowAndNotTheRedundancyOne() {
    assertEquals(
        Arrays.asList("comp_kt_222_3", "comp_kt_224_1"),
        codes(BgCompensationProposalResolver.proposedFor(seed(), "328_1_10")));
  }

  @Test
  void employerProposalGroundProposesArt331() {
    assertEquals(
        Arrays.asList("comp_kt_224_1", "comp_kt_331"),
        codes(BgCompensationProposalResolver.proposedFor(seed(), "331")));
  }

  @Test
  void resignationProposesOnlyTheUnusedLeave() {
    // чл. 326 — с предизвестие от работника. Обезщетение за оставане без работа не се дължи.
    assertEquals(
        Collections.singletonList("comp_kt_224_1"),
        codes(BgCompensationProposalResolver.proposedFor(seed(), "326")));
  }

  @Test
  void orderOfTheInputIsPreserved() {
    List<BgCompensationType> reversed = Arrays.asList(seed().get(3), seed().get(0));
    assertEquals(
        Arrays.asList("comp_kt_224_1", "comp_kt_222_1"),
        codes(BgCompensationProposalResolver.proposedFor(reversed, "328_1_2")));
  }

  // ── идемпотентност ────────────────────────────────────────────────────────

  private static BgTerminationCompensation existing(String typeCode, String status) {
    BgTerminationCompensation row = new BgTerminationCompensation();
    BgCompensationType t = new BgCompensationType();
    t.setCode(typeCode);
    row.setBgCompensationType(t);
    row.setStatus(status);
    return row;
  }

  /** Второто извикване не бива да произведе нищо — това е тестът, който вика два пъти. */
  @Test
  void proposingTwiceCreatesNothingTheSecondTime() {
    List<BgCompensationType> types = seed();
    String ground = "328_1_2";

    List<BgCompensationType> first = BgCompensationProposalResolver.toCreate(types, ground, null);
    assertEquals(Arrays.asList("comp_kt_222_1", "comp_kt_224_1"), codes(first));

    // симулира записването на първия пас
    List<BgTerminationCompensation> saved =
        first.stream().map(t -> existing(t.getCode(), "PROPOSED")).collect(Collectors.toList());

    List<BgCompensationType> second = BgCompensationProposalResolver.toCreate(types, ground, saved);
    assertTrue(second.isEmpty(), "второто предлагане не бива да добави нищо");
  }

  /**
   * Изключеният ред е решение с записана причина. Повторното предлагане не бива да го възкресява —
   * това е точно двойката записи от тестовата база (чл. 224 ал. 1 и чл. 222 ал. 1, и двата EXCLUDED
   * на договор 2).
   */
  @Test
  void anExcludedRowIsNotResurrected() {
    List<BgTerminationCompensation> saved =
        Arrays.asList(existing("comp_kt_224_1", "EXCLUDED"), existing("comp_kt_222_1", "EXCLUDED"));
    assertTrue(
        BgCompensationProposalResolver.toCreate(seed(), "328_1_2", saved).isEmpty(),
        "изключен ред не се предлага наново");
  }

  @Test
  void aConfirmedRowIsNotDuplicated() {
    List<BgTerminationCompensation> saved =
        Collections.singletonList(existing("comp_kt_222_1", "CONFIRMED"));
    assertEquals(
        Collections.singletonList("comp_kt_224_1"),
        codes(BgCompensationProposalResolver.toCreate(seed(), "328_1_2", saved)),
        "потвърденият остава, липсващият се добавя");
  }

  /** Ред за друг вид не блокира предлагането на този, който липсва. */
  @Test
  void anUnrelatedExistingRowDoesNotBlock() {
    List<BgTerminationCompensation> saved =
        Collections.singletonList(existing("comp_kt_331", "PROPOSED"));
    assertEquals(
        Arrays.asList("comp_kt_222_1", "comp_kt_224_1"),
        codes(BgCompensationProposalResolver.toCreate(seed(), "328_1_2", saved)));
  }

  @Test
  void existingRowsAreMatchedByIdWhenPresent() {
    BgCompensationType t = seed().get(0);
    t.setId(42L);
    BgTerminationCompensation row = new BgTerminationCompensation();
    BgCompensationType sameById = new BgCompensationType();
    sameById.setId(42L);
    sameById.setCode("друг-код-същият-запис");
    row.setBgCompensationType(sameById);
    assertTrue(
        BgCompensationProposalResolver.toCreate(
                Collections.singletonList(t), "328_1_2", Collections.singletonList(row))
            .isEmpty());
  }

  // ── актът е втората част на основанието ───────────────────────────────────

  /**
   * СЪЩИНАТА: съвпадение по член, но различен акт → нула предложения. Номерата на членовете се
   * повтарят между актовете, тоест членът сам не идентифицира нищо.
   */
  @Test
  void sameArticleUnderADifferentActProposesNothing() {
    BgCompensationType ktRow = type("comp_kt_331", false, "331", true, "KT");
    BgCompensationType zvoRow = type("измислен_zvo_331", false, "331", true, "ZVO");
    List<BgCompensationType> both = Arrays.asList(ktRow, zvoRow);

    assertEquals(Collections.singletonList("comp_kt_331"), codes(proposedFor(both, "331", "KT")));
    assertEquals(
        Collections.singletonList("измислен_zvo_331"), codes(proposedFor(both, "331", "ZVO")));
    assertTrue(proposedFor(both, "331", "ZDSL").isEmpty(), "трети акт → нищо");
  }

  private static List<BgCompensationType> proposedFor(
      List<BgCompensationType> types, String ground, String family) {
    return BgCompensationProposalResolver.proposedFor(types, ground, family);
  }

  @Test
  void theZvoGroundReachesItsOwnRowPlusTheLabourCodeAnyGroundOnes() {
    assertEquals(
        Arrays.asList("comp_kt_224_1", "comp_zvo_58_3"),
        codes(proposedFor(seed(), "58_1_3", "ZVO")));
    // Същият номер, обявен като КТ, НЕ стига до реда по ЗВО — конкретният член е вързан за своя
    // акт.
    // Стига обаче до чл. 224, който е „при всяко основание“. Комбинацията КТ + 58_1_3 е безсмислена
    // като данни и нищо не я забранява: селекцията е обща за всички актове.
    assertEquals(
        Collections.singletonList("comp_kt_224_1"), codes(proposedFor(seed(), "58_1_3", "KT")));
  }

  /**
   * „При всяко основание“ е ограничено от ВИДА ПРАВООТНОШЕНИЕ, не от акта. Чл. 59 ЗВО праща
   * неуредените случаи по Глава VI обратно към КТ, а ВКС Решение № 142/01.07.2019 г. по гр. д. №
   * 3934/2018 г., III г. о. определя преподавателя като работещ по трудов договор — тоест
   * прекратяване по ЗВО поражда и обезщетението за неползван отпуск по чл. 224 КТ.
   */
  @Test
  void aLabourCodeAnyGroundCompensationReachesEveryEmploymentAct() {
    assertTrue(
        codes(proposedFor(seed(), "58_1_3", "ZVO")).contains("comp_kt_224_1"),
        "прекратяване по ЗВО дължи и неползвания отпуск по чл. 224 КТ");
    assertEquals(
        Arrays.asList("comp_kt_224_1", "comp_zvo_58_3"),
        codes(proposedFor(seed(), "58_1_3", "ZVO")));
    assertEquals(
        Arrays.asList("comp_kt_222_1", "comp_kt_224_1"),
        codes(proposedFor(seed(), "328_1_2", "KT")));
  }

  /** Служебното правоотношение получава само своите — чл. 224 КТ не се дължи по ЗДСл. */
  @Test
  void aCivilServiceTerminationDoesNotInheritLabourCodeCompensations() {
    assertFalse(
        codes(proposedFor(seed(), "106_2", "ZDSL")).contains("comp_kt_224_1"),
        "служебното правоотношение не наследява обезщетенията по КТ");
    assertTrue(proposedFor(seed(), "106_2", "ZDSL").isEmpty());
  }

  @Test
  void theRelationshipKindIsAPropertyOfTheAct() {
    assertEquals("01", BgCompensationProposalResolver.relationshipKindOf("KT"));
    assertEquals("01", BgCompensationProposalResolver.relationshipKindOf("ZVO"));
    assertEquals("02", BgCompensationProposalResolver.relationshipKindOf("ZDSL"));
    assertNull(BgCompensationProposalResolver.relationshipKindOf("НЕПОЗНАТ"));
    assertNull(BgCompensationProposalResolver.relationshipKindOf(null));
  }

  /** Непознат акт не наследява нищо — по-добре нула предложения, отколкото чужди. */
  @Test
  void anUnknownActInheritsNothing() {
    assertTrue(proposedFor(seed(), "328_1_2", "НЕПОЗНАТ").isEmpty());
  }

  /**
   * ЛИПСВАЩО основание не е ЧУЖД акт, и разликата е съществена. Договор без причина за прекратяване
   * няма нито член, нито акт — но обезщетението за неползван отпуск пак се дължи, защото
   * правоотношението е свършило. Ако липсата се държи като непознат акт, оттеглянето маха тихо вече
   * предложения ред.
   */
  @Test
  void aMissingGroundIsNotAForeignAct() {
    assertEquals(
        Collections.singletonList("comp_kt_224_1"), codes(proposedFor(seed(), null, null)));
    assertEquals(Collections.singletonList("comp_kt_224_1"), codes(proposedFor(seed(), "", "")));

    // и оттеглянето не го пипа
    BgCompensationProposalPlan plan =
        BgCompensationProposalResolver.plan(
            seed(),
            null,
            null,
            Collections.singletonList(existing("comp_kt_224_1", "PROPOSED")),
            true);
    assertTrue(plan.isEmpty(), "нито създава, нито оттегля при липсващо основание");
  }

  /** Конкретният член остава вързан за своя акт — там сравнението е точно, не по правоотношение. */
  @Test
  void aSpecificArticleIsStillBoundToItsOwnAct() {
    assertTrue(
        codes(proposedFor(seed(), "328_1_2", "ZVO")).contains("comp_kt_222_1") == false,
        "чл. 328 ал. 1 т. 2 КТ не се пали от прекратяване по ЗВО");
    assertEquals(
        Collections.singletonList("comp_kt_224_1"), codes(proposedFor(seed(), "328_1_2", "ZVO")));
  }

  @Test
  void anAbsentActDoesNotNarrow() {
    // Викащ без акт се държи както преди тази ос — за миграцията, не за данните.
    assertEquals(
        codes(BgCompensationProposalResolver.proposedFor(seed(), "328_1_2")),
        codes(proposedFor(seed(), "328_1_2", null)));
    assertEquals(
        codes(proposedFor(seed(), "328_1_2", "  ")), codes(proposedFor(seed(), "328_1_2", null)));
  }

  // ── оттегляне на предложени, които вече не се пораждат ────────────────────

  private static BgTerminationCompensation row(String typeCode, String status) {
    return existing(typeCode, status);
  }

  /** Основанието е поправено от чл. 331 на чл. 328 ал. 1 т. 2 — предложението по 331 отпада. */
  @Test
  void aProposalTheGroundNoLongerGivesRiseToIsWithdrawn() {
    List<BgTerminationCompensation> existing =
        Arrays.asList(row("comp_kt_331", "PROPOSED"), row("comp_kt_224_1", "PROPOSED"));
    BgCompensationProposalPlan plan =
        BgCompensationProposalResolver.plan(seed(), "328_1_2", "KT", existing, true);

    assertEquals(Collections.singletonList("comp_kt_222_1"), codes(plan.getToCreate()));
    assertEquals(1, plan.getToRetract().size());
    assertEquals("comp_kt_331", plan.getToRetract().get(0).getBgCompensationType().getCode());
  }

  @Test
  void aConfirmedRowIsNeverWithdrawn() {
    List<BgTerminationCompensation> existing =
        Collections.singletonList(row("comp_kt_331", "CONFIRMED"));
    assertTrue(
        BgCompensationProposalResolver.plan(seed(), "328_1_2", "KT", existing, true)
            .getToRetract()
            .isEmpty());
  }

  @Test
  void anExcludedRowIsNeverWithdrawn() {
    List<BgTerminationCompensation> existing =
        Collections.singletonList(row("comp_kt_331", "EXCLUDED"));
    assertTrue(
        BgCompensationProposalResolver.plan(seed(), "328_1_2", "KT", existing, true)
            .getToRetract()
            .isEmpty());
  }

  /** Ръчно коригирана сума на потвърден ред остава непокътната — планът дори не я споменава. */
  @Test
  void aManuallyAdjustedConfirmedRowIsUntouched() {
    BgTerminationCompensation adjusted = row("comp_kt_331", "CONFIRMED");
    adjusted.setAmount(new java.math.BigDecimal("4321.00"));
    BgCompensationProposalPlan plan =
        BgCompensationProposalResolver.plan(
            seed(), "328_1_2", "KT", Collections.singletonList(adjusted), true);
    assertTrue(plan.getToRetract().isEmpty());
    assertTrue(eq(adjusted.getAmount(), "4321.00"));
  }

  @Test
  void anAdditionalContractYieldsAnEmptyPlanInBothDirections() {
    List<BgTerminationCompensation> existing =
        Collections.singletonList(row("comp_kt_331", "PROPOSED"));
    BgCompensationProposalPlan plan =
        BgCompensationProposalResolver.plan(seed(), "328_1_2", "KT", existing, false);
    assertTrue(plan.isEmpty(), "нито създава, нито оттегля");
  }

  @Test
  void anUnchangedGroundWithdrawsNothingAndCreatesNothing() {
    List<BgTerminationCompensation> existing =
        Arrays.asList(row("comp_kt_222_1", "PROPOSED"), row("comp_kt_224_1", "PROPOSED"));
    BgCompensationProposalPlan plan =
        BgCompensationProposalResolver.plan(seed(), "328_1_2", "KT", existing, true);
    assertTrue(plan.isEmpty(), "втори пас върху непроменен вход не прави нищо");
  }

  private static boolean eq(java.math.BigDecimal a, String b) {
    return a.compareTo(new java.math.BigDecimal(b)) == 0;
  }

  // ── допълнителен договор не поражда право ─────────────────────────────────

  /**
   * По допълнителен трудов договор обезщетение изобщо не се поражда — решение на клиента. Проверява
   * се при ВСЯКО основание от селекцията плюс празното, защото правилото не е за отчитане, а за
   * възникване: няма основание, което да го заобиколи.
   */
  @Test
  void anAdditionalContractProposesNothingWhateverTheGround() {
    List<String> everyGround =
        Arrays.asList(
            "325_1_1",
            "325_1_3",
            "325_1_4",
            "325_1_9",
            "326",
            "327_1",
            "328_1_1",
            "328_1_2",
            "328_1_3",
            "328_1_4",
            "328_1_5",
            "328_1_7",
            "328_1_8",
            "328_1_10",
            "328_1_11",
            "330_2",
            "331",
            "71_1",
            null,
            "");
    for (String ground : everyGround) {
      assertTrue(
          BgCompensationProposalResolver.toCreate(seed(), ground, null, false).isEmpty(),
          "основание " + ground + " не бива да поражда нищо по допълнителен договор");
    }
  }

  /** Непопълненият флаг отказва наравно с изричното false — не се гадае. */
  @Test
  void anUnsetPrimaryFlagAlsoProposesNothing() {
    assertTrue(BgCompensationProposalResolver.toCreate(seed(), "328_1_2", null, null).isEmpty());
  }

  /** Контролата: същият вход по ОСНОВЕН договор връща каквото и без пазача. */
  @Test
  void aPrimaryContractIsUnaffectedByTheGuard() {
    assertEquals(
        codes(BgCompensationProposalResolver.toCreate(seed(), "328_1_2", null)),
        codes(BgCompensationProposalResolver.toCreate(seed(), "328_1_2", null, true)));
    assertEquals(
        Arrays.asList("comp_kt_222_1", "comp_kt_224_1"),
        codes(BgCompensationProposalResolver.toCreate(seed(), "328_1_2", null, true)));
  }

  // ── коя дата важи ─────────────────────────────────────────────────────────

  private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

  @Test
  void anActualTerminationDateWinsOverTheAgreedEnd() {
    // предсрочно прекратен срочен договор: уговорен край 31.12, прекратен на 15.08
    assertEquals(
        LocalDate.of(2026, 8, 15),
        BgCompensationProposalResolver.effectiveTerminationDate(
            LocalDate.of(2026, 8, 15), LocalDate.of(2026, 12, 31), TODAY));
  }

  @Test
  void anExpiredAgreedEndCountsAsATermination() {
    // срочен договор, чийто срок е изтекъл, без ръчно въведена дата — случаят от раздел 4.5
    assertEquals(
        LocalDate.of(2026, 7, 31),
        BgCompensationProposalResolver.effectiveTerminationDate(
            null, LocalDate.of(2026, 7, 31), TODAY));
  }

  @Test
  void anAgreedEndInTheFutureIsNotATermination() {
    assertNull(
        BgCompensationProposalResolver.effectiveTerminationDate(
            null, LocalDate.of(2026, 12, 31), TODAY));
  }

  @Test
  void theAgreedEndCountsOnTheDayItArrives() {
    assertEquals(
        TODAY, BgCompensationProposalResolver.effectiveTerminationDate(null, TODAY, TODAY));
    assertNull(
        BgCompensationProposalResolver.effectiveTerminationDate(null, TODAY.plusDays(1), TODAY));
  }

  @Test
  void aContractWithNeitherDateIsNotTerminated() {
    assertNull(BgCompensationProposalResolver.effectiveTerminationDate(null, null, TODAY));
  }

  @Test
  void nullRowsAndNullTypesInExistingAreSkipped() {
    List<BgTerminationCompensation> saved =
        Arrays.asList(null, new BgTerminationCompensation(), existing("comp_kt_222_1", "PROPOSED"));
    assertEquals(
        Collections.singletonList("comp_kt_224_1"),
        codes(BgCompensationProposalResolver.toCreate(seed(), "328_1_2", saved)));
  }
}
