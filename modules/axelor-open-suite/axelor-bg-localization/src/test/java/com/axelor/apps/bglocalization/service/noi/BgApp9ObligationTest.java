package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОЙ ДЪЛЖИ ПРИЛОЖЕНИЕ № 9 — всяко правило на {@link BgApp9Obligation} поотделно.
 *
 * <p>Изходната точка е лист, който СЕ ДЪЛЖИ (валидиран, болничен, след границата, представен);
 * всеки свидетел сменя ЕДИН факт и показва коя група излиза.
 */
class BgApp9ObligationTest {

  private static final LocalDate TRACK_FROM = LocalDate.of(2026, 9, 1);
  private static final LocalDate RECEIVED = LocalDate.of(2026, 10, 1);

  private static BgApp9Obligation.Facts owing() {
    return BgApp9Obligation.facts()
        .status("VALIDATED")
        .absenceType("sick_er", true)
        .insuredType("01")
        .received(RECEIVED)
        .from(LocalDate.of(2026, 9, 28))
        .trackFrom(TRACK_FROM);
  }

  private static BgApp9Obligation.Verdict decide(BgApp9Obligation.Facts facts) {
    return BgApp9Obligation.decide(facts);
  }

  @Test
  @DisplayName("Обикновеният лист: дължи се, срокът тече от ПРЕДСТАВЯНЕТО (чл. 12 т. 1)")
  void anOrdinarySheetRunsFromPresentation() {
    BgApp9Obligation.Verdict v = decide(owing());
    assertEquals(BgApp9Obligation.Group.OWES, v.group);
    assertEquals(BgApp9Deadline.Kind.PRESENTATION, v.kind);
    assertEquals(RECEIVED, v.anchor);
  }

  @Test
  @DisplayName("Лист ИЗЦЯЛО в двата дни на осигурителя: дължи се (чл. 8 ал. 1 — за всеки лист)")
  void aSheetWithinTheEmployerDaysIsOwed() {
    // Правилото не чете платеца и дните: те не са вход на задължението, а на парите.
    BgApp9Obligation.Verdict v =
        decide(owing().from(LocalDate.of(2026, 9, 29)).received(LocalDate.of(2026, 10, 1)));
    assertEquals(BgApp9Obligation.Group.OWES, v.group);
  }

  @Test
  @DisplayName("ПРОДЪЛЖЕНИЕТО се дължи със СВОЙ срок — от своето представяне")
  void aContinuationHasItsOwnDeadline() {
    LocalDate continuationReceived = LocalDate.of(2026, 11, 3);
    BgApp9Obligation.Verdict v = decide(owing().received(continuationReceived));
    assertEquals(BgApp9Obligation.Group.OWES, v.group);
    assertEquals(continuationReceived, v.anchor);
  }

  @Test
  @DisplayName("Вид 18 (бременност и раждане) се дължи")
  void maternityIsOwed() {
    assertEquals(
        BgApp9Obligation.Group.OWES, decide(owing().absenceType("maternity_birth", true)).group);
  }

  @Test
  @DisplayName("Видове 27/28 (без право на обезщетение) се дължат — правото решава НОИ")
  void noBenefitTypesAreOwed() {
    assertEquals(
        BgApp9Obligation.Group.OWES, decide(owing().absenceType("sick_no_benefit_er", true)).group);
    assertEquals(
        BgApp9Obligation.Group.OWES,
        decide(owing().absenceType("sick_no_benefit_doo", true)).group);
  }

  @Test
  @DisplayName("Вид 21 „Непредставен“ НЕ се дължи — срокът тръгва при представянето")
  void anUnpresentedSheetIsNotOwedYet() {
    BgApp9Obligation.Verdict v =
        decide(owing().absenceType(BgApp9Obligation.UNPRESENTED_CODE, true));
    assertEquals(BgApp9Obligation.Group.UNPRESENTED, v.group);
    assertFalse(v.group.listed);
  }

  @Test
  @DisplayName(
      "Код 10 (управител по договор за управление): осигурител е дружеството — ПРЕДСТАВЯНЕ")
  void aManagerRunsFromPresentation() {
    BgApp9Obligation.Verdict v = decide(owing().insuredType("10"));
    assertEquals(BgApp9Obligation.Group.OWES, v.group);
    assertEquals(BgApp9Deadline.Kind.PRESENTATION, v.kind);
  }

  @Test
  @DisplayName("ОБЖАЛВАН (чл. 8 ал. 2): срокът не тече; приключил — тече от приключването")
  void anAppealStopsTheClockUntilItCloses() {
    BgApp9Obligation.Verdict open = decide(owing().appeal(true, null));
    assertEquals(BgApp9Obligation.Group.APPEALED, open.group);
    assertNull(open.anchor, "няма от какво да тече");
    assertTrue(open.group.listed, "видим, в своя група");

    LocalDate closed = LocalDate.of(2026, 12, 4);
    BgApp9Obligation.Verdict running = decide(owing().appeal(true, closed));
    assertEquals(BgApp9Obligation.Group.OWES, running.group);
    assertEquals(BgApp9Deadline.Kind.APPEAL_CLOSED, running.kind);
    assertEquals(closed, running.anchor);
  }

  @Test
  @DisplayName("Без дата на представяне: дължи се, но в групата „няма дата“ — не тихо")
  void aMissingReceivedDateIsItsOwnGroup() {
    BgApp9Obligation.Verdict v = decide(owing().received(null));
    assertEquals(BgApp9Obligation.Group.NO_ANCHOR_DATE, v.group);
    assertTrue(v.group.listed);
  }

  @Test
  @DisplayName("Видове 12/13/96 (т. 25 не се попълва): „извън обхвата — подава лицето“")
  void personsWhoFileThemselvesAreOutOfScope() {
    for (String code : new String[] {"12", "13", "96"}) {
      BgApp9Obligation.Verdict v = decide(owing().insuredType(code));
      assertEquals(BgApp9Obligation.Group.OUT_OF_SCOPE_SELF, v.group, "вид " + code);
      assertTrue(v.group.listed, "видимо изключени");
    }
  }

  @Test
  @DisplayName("Граница: празна → изключено; преди нея → не излиза; без дата — по началото")
  void theCutoffIsForwardOnly() {
    assertEquals(BgApp9Obligation.Group.TRACKING_OFF, decide(owing().trackFrom(null)).group);
    assertEquals(
        BgApp9Obligation.Group.BEFORE_CUTOFF,
        decide(owing().received(LocalDate.of(2026, 8, 31))).group);
    assertEquals(
        BgApp9Obligation.Group.BEFORE_CUTOFF,
        decide(owing().received(null).from(LocalDate.of(2026, 8, 20))).group,
        "заварен лист без дата не бива да наводни групата „няма дата“");
    assertFalse(BgApp9Obligation.Group.BEFORE_CUTOFF.listed);
  }

  @Test
  @DisplayName("Исторически внесен и невалидиран: не излизат")
  void historyAndDraftsAreHidden() {
    assertEquals(BgApp9Obligation.Group.HISTORICAL, decide(owing().historical(true)).group);
    assertEquals(BgApp9Obligation.Group.NOT_VALIDATED, decide(owing().status("DRAFT")).group);
    assertEquals(
        BgApp9Obligation.Group.NOT_A_SICK_NOTE,
        decide(owing().absenceType("paid_annual", false)).group);
  }

  @Test
  @DisplayName("Подадено удостоверение → „Подадено в НОИ“, срокът не тече; отменено → пак дължи")
  void aSubmittedSheetIsNoLongerOwed() {
    BgApp9Obligation.Verdict v = decide(owing().submitted(true));
    assertEquals(BgApp9Obligation.Group.SUBMITTED, v.group);
    assertFalse(v.owes(), "подаденото не брои срок");
    assertNull(v.anchor);
    assertTrue(BgApp9Obligation.Group.SUBMITTED.listed, "остава видим в „Прил. 9 за подаване“");

    assertEquals(BgApp9Obligation.Group.OWES, decide(owing().submitted(false)).group);
  }

  @Test
  @DisplayName("Подаден, но и обжалван или без дата на представяне → пак „Подадено в НОИ“")
  void submittedWinsOverAppealAndMissingAnchor() {
    assertEquals(
        BgApp9Obligation.Group.SUBMITTED, decide(owing().appeal(true, null).submitted(true)).group);
    assertEquals(
        BgApp9Obligation.Group.SUBMITTED, decide(owing().received(null).submitted(true)).group);
  }

  @Test
  @DisplayName("Изключващите правила остават първи: заварен подаден лист си остава „исторически“")
  void exclusionsStillComeFirst() {
    assertEquals(
        BgApp9Obligation.Group.HISTORICAL, decide(owing().historical(true).submitted(true)).group);
  }
}
