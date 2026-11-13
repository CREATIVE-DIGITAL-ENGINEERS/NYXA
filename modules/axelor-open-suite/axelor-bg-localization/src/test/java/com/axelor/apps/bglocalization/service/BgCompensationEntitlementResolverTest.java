package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgTerminationCompensation;
import com.axelor.apps.hr.db.EmploymentContract;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРАВО ПРЕДИ ПАРИ — правилата без база: {@link BgCompensationResolver#refuseWithoutEntitlement},
 * {@link BgCompensationResolver#refuseMissingFacts} и {@link
 * BgCompensationResolver#refuseDocumentFromEntitlement}.
 */
class BgCompensationEntitlementResolverTest {

  private static final LocalDate TERMINATED = LocalDate.of(2026, 9, 30);

  private static BgCompensationType type(String code, boolean arises) {
    BgCompensationType t = new BgCompensationType();
    t.setId(7L);
    t.setCode(code);
    t.setName("Вид " + code);
    t.setLegalBasis("чл. " + code);
    t.setArisesOnTermination(arises);
    t.setActive(true);
    t.setLegalFamily("KT");
    return t;
  }

  private static EmploymentContract contract() {
    EmploymentContract c = new EmploymentContract();
    c.setId(11L);
    c.setBgIsPrimaryContract(true);
    c.setBgTerminationDate(TERMINATED);
    return c;
  }

  private static BgTerminationCompensation right(EmploymentContract c, BgCompensationType t) {
    BgTerminationCompensation r = new BgTerminationCompensation();
    r.setId(21L);
    r.setEmploymentContract(c);
    r.setBgCompensationType(t);
    r.setTerminationDate(TERMINATED);
    r.setStatus(BgCompensationResolver.ENTITLEMENT_CONFIRMED);
    return r;
  }

  /** Документ, свързан с потвърдено право — минава, докато тестът не развали нещо. */
  private static BgCompensation linked(BgCompensationType t) {
    EmploymentContract c = contract();
    BgCompensation doc = new BgCompensation();
    doc.setEmploymentContract(c);
    doc.setBgCompensationType(t);
    doc.setBgTerminationCompensation(right(c, t));
    doc.setBgEventDate(TERMINATED);
    return doc;
  }

  private static void refused(String reason, String expected) {
    assertTrue(
        reason != null && reason.contains(expected), "очакван отказ „" + expected + "“: " + reason);
  }

  // ═══ ПРАВОТО ═══

  @Test
  @DisplayName("вид по време на договора (без „Възниква при прекратяване“) → право не се иска")
  void aCompensationDuringTheContractNeedsNoEntitlement() {
    BgCompensation doc = new BgCompensation();
    doc.setBgCompensationType(type("comp_kt_214", false));
    assertNull(BgCompensationResolver.refuseWithoutEntitlement(doc));
  }

  @Test
  @DisplayName(
      "възниква при прекратяване, без връзка към право → отказ, който казва „Създай документ“")
  void withoutALinkItIsRefused() {
    BgCompensation doc = new BgCompensation();
    doc.setEmploymentContract(contract());
    doc.setBgCompensationType(type("comp_kt_222_1", true));
    doc.setBgEventDate(TERMINATED);
    refused(BgCompensationResolver.refuseWithoutEntitlement(doc), "„Създай документ“");
  }

  @Test
  @DisplayName("свързан с потвърдено право, същият договор, вид и дата → минава")
  void aLinkedDocumentPasses() {
    assertNull(
        BgCompensationResolver.refuseWithoutEntitlement(linked(type("comp_kt_222_1", true))));
  }

  @Test
  @DisplayName("правото е само предложено → отказ")
  void aProposedEntitlementIsRefused() {
    BgCompensation doc = linked(type("comp_kt_222_1", true));
    doc.getBgTerminationCompensation().setStatus("PROPOSED");
    refused(BgCompensationResolver.refuseWithoutEntitlement(doc), "не е потвърдено");
  }

  @Test
  @DisplayName("правото е по друг договор → отказ")
  void anEntitlementOfAnotherContractIsRefused() {
    BgCompensation doc = linked(type("comp_kt_222_1", true));
    EmploymentContract other = contract();
    other.setId(12L);
    doc.getBgTerminationCompensation().setEmploymentContract(other);
    refused(BgCompensationResolver.refuseWithoutEntitlement(doc), "друг трудов договор");
  }

  @Test
  @DisplayName("правото е за друг вид → отказ")
  void anEntitlementOfAnotherTypeIsRefused() {
    BgCompensation doc = linked(type("comp_kt_222_1", true));
    BgCompensationType other = type("comp_kt_331", true);
    other.setId(8L);
    doc.getBgTerminationCompensation().setBgCompensationType(other);
    refused(BgCompensationResolver.refuseWithoutEntitlement(doc), "друг вид");
  }

  @Test
  @DisplayName("допълнителен договор → отказ")
  void anAdditionalContractIsRefused() {
    BgCompensation doc = linked(type("comp_kt_222_1", true));
    doc.getEmploymentContract().setBgIsPrimaryContract(false);
    refused(BgCompensationResolver.refuseWithoutEntitlement(doc), "допълнителен трудов договор");
  }

  @Test
  @DisplayName("договорът не е прекратен (нито дата, нито край) → отказ, без падане към днес")
  void anOngoingContractIsRefused() {
    BgCompensation doc = linked(type("comp_kt_222_1", true));
    doc.getEmploymentContract().setBgTerminationDate(null);
    doc.getEmploymentContract().setEndDate(null);
    refused(BgCompensationResolver.refuseWithoutEntitlement(doc), "не е прекратен");
  }

  @Test
  @DisplayName("датата в договора ≠ датата в правото → отказ")
  void aDifferentTerminationDateIsRefused() {
    BgCompensation doc = linked(type("comp_kt_222_1", true));
    doc.getEmploymentContract().setBgTerminationDate(TERMINATED.plusDays(1));
    refused(BgCompensationResolver.refuseWithoutEntitlement(doc), "не съвпада с датата в правото");
  }

  @Test
  @DisplayName("датата на основанието в документа ≠ датата на прекратяване → отказ")
  void aDifferentEventDateIsRefused() {
    BgCompensation doc = linked(type("comp_kt_222_1", true));
    doc.setBgEventDate(TERMINATED.minusDays(3));
    refused(BgCompensationResolver.refuseWithoutEntitlement(doc), "Датата на основанието");
  }

  // ═══ ФАКТИТЕ ═══

  @Test
  @DisplayName("чл. 220 без срок на предизвестието → отказ; с него → минава")
  void art220NeedsTheNoticePeriod() {
    BgCompensation doc = linked(type("comp_kt_220_1", true));
    refused(BgCompensationResolver.refuseMissingFacts(doc), "предизвестието");
    doc.setBgNoticeStartDate(TERMINATED.minusDays(10));
    doc.setBgNoticeCalendarDays(30);
    assertNull(BgCompensationResolver.refuseMissingFacts(doc));
  }

  @Test
  @DisplayName("чл. 221 без основание → отказ; с основание, което не го поражда → отказ")
  void art221NeedsAGroundThatGivesRiseToIt() {
    BgCompensationType t = type("comp_kt_221_2", true);
    t.setTriggerGrounds("330_2_6,330_1");
    BgCompensation doc = linked(t);
    refused(BgCompensationResolver.refuseMissingFacts(doc), "основанието за прекратяване");
    doc.setBgTerminationGround("328_1_2");
    refused(BgCompensationResolver.refuseMissingFacts(doc), "не поражда");
    doc.setBgTerminationGround("330_2_6");
    assertNull(BgCompensationResolver.refuseMissingFacts(doc));
  }

  @Test
  @DisplayName("чл. 225 ал. 1 без влязло в сила решение (номер и дата) → отказ")
  void art225NeedsTheFinalCourtDecision() {
    BgCompensation doc = linked(type("comp_kt_225_1", true));
    doc.setBgCourtDecisionNumber("123");
    refused(BgCompensationResolver.refuseMissingFacts(doc), "ВЛЯЗЛО В СИЛА");
    doc.setBgCourtDecisionEffectiveDate(LocalDate.of(2027, 3, 1));
    assertNull(BgCompensationResolver.refuseMissingFacts(doc));
  }

  @Test
  @DisplayName("чл. 226 ал. 2 без дата на вписване в ЕТЗ → отказ")
  void art226Par2NeedsTheRegistrationDate() {
    BgCompensation doc = linked(type("comp_kt_226_2", true));
    refused(BgCompensationResolver.refuseMissingFacts(doc), "ЕТЗ");
    doc.setBgEtzRegistrationDate(TERMINATED.plusDays(20));
    assertNull(BgCompensationResolver.refuseMissingFacts(doc));
  }

  // ═══ „СЪЗДАЙ ДОКУМЕНТ“ ═══

  @Test
  @DisplayName("„Създай документ“: само от потвърдено право, без документ в работа")
  void aDocumentComesOnlyFromAConfirmedEntitlementWithoutAnOpenOne() {
    BgCompensationType t = type("comp_kt_222_1", true);
    BgTerminationCompensation r = right(contract(), t);
    assertNull(BgCompensationResolver.refuseDocumentFromEntitlement(r, null));
    refused(BgCompensationResolver.refuseDocumentFromEntitlement(r, "OBEZ-2026-1"), "OBEZ-2026-1");
    r.setStatus("PROPOSED");
    refused(BgCompensationResolver.refuseDocumentFromEntitlement(r, null), "ПОТВЪРДЕНО");
    r.setStatus(BgCompensationResolver.ENTITLEMENT_CONFIRMED);
    t.setArisesOnTermination(false);
    refused(
        BgCompensationResolver.refuseDocumentFromEntitlement(r, null), "Възниква при прекратяване");
  }
}
