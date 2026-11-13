package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgEmployeeChild;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГЕЙТЪТ НА §3: ПРИСЪДИТЕ СЕ ИЗКАРВАТ, А НЕ СЕ ИЗХВЪРЛЯТ.
 *
 * <p><b>Защо това е ОТДЕЛЕН тест, а не ред в тестовете на резолвера.</b> Резолверът вече е доказано
 * приказлив — {@link BgApp9ObstoyatelstvaResolverTest} заковава свидетеля на всяко правило. Но
 * приказлив резолвер и МЪЛЧАЛИВА система са напълно съвместими: {@code BgEarningsService}, {@code
 * BgCompensationLineService}, {@code BgContributionLineService} и {@code BgSickLeaveService} връщат
 * назовани откази, а ако {@code BgPaySlipService} ги вика и ИЗХВЪРЛЯ върнатите списъци, четирите
 * правила отказват точно както трябва, и нито един оператор не научава защо перото липсва.
 *
 * <p>Никой тест не пада от това. Именно затова тук се пита ЗА КОНСУМАЦИЯТА: не „връща ли резолверът
 * присъди“, а „СТИГАТ ЛИ ТЕ ДО ЗАПИСА“. Дефект от този клас се лови само с въпрос, зададен от
 * страната на повикващия.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ НА БАЗАТА.</b> {@code factsOf} и {@code childOf} са шевовете —
 * иначе искат Postgres и Guice. {@link BgApp9ObstoyatelstvaResolver} и {@code
 * BgApp9Resolution#asNotes} се викат ИСТИНСКИ: текстът, който този тест чете, е текстът, който
 * операторът вижда.
 */
class BgApp9ResolverNotesTest {

  private static final LocalDate RISK_DAY = LocalDate.of(2026, 11, 2);

  /**
   * Службата без база: подменени са ДВАТА ѝ достъпа до нея и нищо друго.
   *
   * <p>⚠️ Шевовете са върху отделен клас — нито един друг наследник не се пипа. (Разширен заварен
   * protected шев поваля override-ите на съществуващите свидетели; тук такива няма.)
   */
  private static final class Service extends BgApp9DeclarationService {

    /**
     * Свидетелят мери бележките на резолвера, не присъдата: т. 2 тръгва по обичайния път. Четенето
     * на замразената присъда мери {@code BgApp9ObstoyatelstvaResolverTest}.
     */
    @Override
    protected com.axelor.apps.bglocalization.service.payroll.BgSickBenefitVerdictRules.Verdict
        benefitVerdict(com.axelor.apps.bglocalization.db.BgSickLeave sickLeave) {
      return null;
    }

    private final BgApp9Facts facts;

    private Service(BgApp9Facts facts) {
      this.facts = facts;
    }

    @Override
    public BgApp9Facts factsOf(BgSickLeave sickLeave) {
      return facts;
    }

    @Override
    protected BgEmployeeChild childOf(Long childId) {
      if (childId == null) {
        return null;
      }
      BgEmployeeChild child = new BgEmployeeChild();
      child.setFullName("дете #" + childId);
      return child;
    }
  }

  private static BgApp9Declaration declarationFor(BgApp9Facts facts) {
    BgApp9Declaration declaration = new BgApp9Declaration();
    declaration.setSickLeave(new BgSickLeave());
    declaration.setStatusSelect("DRAFT");
    new Service(facts).resolveInto(declaration);
    return declaration;
  }

  /** Здравият случай — за да може всеки тест да маха по едно нещо и да гледа отказа. */
  private static BgApp9Facts.Builder healthy() {
    return BgApp9Facts.builder()
        .incapacityStartDate(RISK_DAY)
        .sickPeriod(RISK_DAY, LocalDate.of(2026, 11, 4))
        .absenceType(2, "sick_er", "Болнични Р-л (общо заболяване)")
        .contractType("67")
        .contractDates(LocalDate.of(2024, 1, 15), null)
        .insuredTypeCode("01")
        .insuranceService(true, 12)
        .birthDate(LocalDate.of(1985, 4, 12))
        .relationships(1, 1)
        .employerReceived("125", LocalDate.of(2026, 11, 5))
        .periodLines(2, 3)
        .benefitIban("BG18RZBB91550123456789")
        .earlierApp9Submitted(Boolean.FALSE);
  }

  // ═══ 1 · ОТКАЗЪТ СТИГА ДО ЗАПИСА, ДУМА ПО ДУМА ════════════════════════════

  @Test
  @DisplayName("отказът по т. 2 се ЧЕТЕ в resolverNotes — не се губи между резолвера и записа")
  void refusalReachesResolverNotes() {
    BgApp9Declaration declaration = declarationFor(healthy().insuranceService(false, null).build());

    String notes = declaration.getResolverNotes();
    assertNotNull(notes, "присъдите ТРЯБВА да са записани — това е половината от резолвера");
    assertFalse(notes.trim().isEmpty(), "празни присъди значат изхвърлени присъди");

    assertTrue(notes.contains("т. 2"), "точката трябва да е адресируема в текста");
    assertTrue(
        notes.contains("bgInsuranceServiceDeclared"),
        "отказът трябва да НАЗОВАВА полето за поправка, инак не е ползваем:\n" + notes);
    assertTrue(
        notes.contains("[! ОТКАЗ]"), "отказът трябва да е МАРКИРАН, за да се намери:\n" + notes);

    // И стойността НЕ е записана — отказът не е тиха нула.
    assertNull(
        declaration.getBgP2SixMonthStaySelect(),
        "отказът не бива да остави стойност, която да мине за отговор");
  }

  @Test
  @DisplayName("ВСЯКА присъда стига до записа, не само отказите")
  void everyOutcomeReachesResolverNotes() {
    BgApp9Facts facts = healthy().build();
    BgApp9Resolution resolution = BgApp9ObstoyatelstvaResolver.resolve(facts);
    BgApp9Declaration declaration = declarationFor(facts);

    String notes = declaration.getResolverNotes();
    for (BgApp9Outcome outcome : resolution.outcomes) {
      assertTrue(
          notes.contains(outcome.witness),
          "присъдата за "
              + outcome.clause
              + " не е стигнала до записа. ⚠️ «Т. 3 не се отнася, защото рискът е общо заболяване»"
              + " е ОТГОВОР на въпрос, който операторът ще зададе, като види точката празна —"
              + " отговор, който го няма, се връща като докладван дефект.");
    }
    assertEquals(
        resolution.outcomes.size(),
        notes.split("\n").length,
        "по един ред на присъда — слепени, редовете стават абзац, който никой не чете");
  }

  @Test
  @DisplayName("неотносимостта е ВИДИМА, а не мълчалива празнота")
  void notApplicableIsVisible() {
    BgApp9Declaration declaration = declarationFor(healthy().build());

    String notes = declaration.getResolverNotes();
    assertTrue(notes.contains("[не се отнася]"), "неотносимостта има свой маркер:\n" + notes);
    assertTrue(
        notes.contains("т. 13–23"),
        "блокът за детето е неотносим при обикновен болничен и това се КАЗВА:\n" + notes);
  }

  @Test
  @DisplayName("ръчните точки са обявени в записа с ПРЕДЛОЖЕНИЕТО си")
  void manualClausesAndProposalsReachTheRecord() {
    BgApp9Declaration declaration =
        declarationFor(
            healthy()
                .contractType("114")
                .overlappingPaidLeave(
                    java.util.Collections.singletonList(
                        "Платен годишен отпуск (2026-11-03 – 2026-11-06)"))
                .build());

    String notes = declaration.getResolverNotes();
    assertTrue(notes.contains("[ръчно]"), "ръчните точки имат свой маркер:\n" + notes);
    assertTrue(
        notes.contains("2026-11-03"),
        "предложението по т. 12 трябва да носи точните дати до оператора:\n" + notes);
    assertTrue(
        notes.contains("чл. 114"), "предложението по т. 8 трябва да стигне до записа:\n" + notes);

    // ⚠️ И НАЙ-ВАЖНОТО ЗА Т. 12: предложението НЕ е станало потвърждение.
    assertNull(
        declaration.getBgP12ConfirmedSelect(),
        "припокриване по календар НЕ бива да потвърждава т. 12 — най-честият изход е отпускът да"
            + " е бил ОТМЕНЕН заради болничния");
  }

  // ═══ 2 · ИЗВЕДЕНИТЕ СТОЙНОСТИ СТИГАТ ДО ПОЛЕТАТА ═════════════════════════

  @Test
  @DisplayName("изведените стойности се записват в своите полета, а състоянието става RESOLVED")
  void derivedValuesLandOnTheirFields() {
    BgApp9Declaration declaration =
        declarationFor(
            healthy().relationships(2, 2).terminationDate(LocalDate.of(2026, 11, 20)).build());

    assertEquals("YES", declaration.getBgP1Insured());
    assertEquals("INDEFINITE", declaration.getBgP1TermTypeSelect());
    assertEquals("MET", declaration.getBgP2SixMonthStaySelect());
    assertEquals("NOT_APPLICABLE", declaration.getBgP3TwelveMonthStaySelect());
    assertEquals("01", declaration.getBgP4InsuredTypeCode());
    assertEquals(Integer.valueOf(2), declaration.getBgP5RelationshipCount());
    assertEquals(Integer.valueOf(2), declaration.getCertificateSeqNo());
    assertEquals(LocalDate.of(2026, 11, 21), declaration.getBgP7TerminatedFrom());
    assertEquals("BG18RZBB91550123456789", declaration.getBenefitIban());
    assertEquals("YES", declaration.getBgP26FirstSubmissionSelect());

    assertEquals("RESOLVED", declaration.getStatusSelect());
    assertNotNull(declaration.getBgResolvedAt(), "без дата присъдите са текст без възраст");
  }

  @Test
  @DisplayName("второто извеждане МАХА стойност, която вече не следва от данните")
  void reResolvingClearsStaleValues() {
    BgApp9Declaration declaration = declarationFor(healthy().build());
    assertEquals("MET", declaration.getBgP2SixMonthStaySelect());

    // Стажът е бил деклариран по погрешка и флагът пада. Старата стойност НЕ бива да остане.
    new Service(healthy().insuranceService(false, null).build()).resolveInto(declaration);

    assertNull(
        declaration.getBgP2SixMonthStaySelect(),
        "оставена, старата стойност щеше да е присъда от преди поправката, която новият свидетел"
            + " вече отрича — а на екрана двете стоят едно до друго");
    assertTrue(declaration.getResolverNotes().contains("[! ОТКАЗ]"));
  }

  @Test
  @DisplayName("РЪЧНИТЕ полета НЕ се пипат от извеждането — нито първия, нито втория път")
  void manualFieldsSurviveResolving() {
    BgApp9Declaration declaration = new BgApp9Declaration();
    declaration.setSickLeave(new BgSickLeave());
    declaration.setStatusSelect("DRAFT");

    // Операторът е попълнил ръчните точки.
    declaration.setBgP8InterruptedFrom(LocalDate.of(2026, 10, 1));
    declaration.setBgP11SurveyNo("АЛ-77");
    declaration.setBgP12ConfirmedSelect("YES");
    declaration.setBgP12LeaveOrderNo("З-451");
    declaration.setBgP24OtherCircumstances("Лицето работи и по втори договор при друг осигурител.");
    declaration.setBgP15ChildAlive("YES");

    Service service = new Service(healthy().build());
    service.resolveInto(declaration);
    service.resolveInto(declaration);

    assertEquals(LocalDate.of(2026, 10, 1), declaration.getBgP8InterruptedFrom());
    assertEquals("АЛ-77", declaration.getBgP11SurveyNo());
    assertEquals("YES", declaration.getBgP12ConfirmedSelect());
    assertEquals("З-451", declaration.getBgP12LeaveOrderNo());
    assertEquals(
        "Лицето работи и по втори договор при друг осигурител.",
        declaration.getBgP24OtherCircumstances());
    assertEquals("YES", declaration.getBgP15ChildAlive());
  }

  @Test
  @DisplayName("ПОДАДЕНО удостоверение не пада обратно в «изведено»")
  void submittedStatusSurvivesReResolving() {
    BgApp9Declaration declaration = new BgApp9Declaration();
    declaration.setSickLeave(new BgSickLeave());
    declaration.setStatusSelect("SUBMITTED");

    new Service(healthy().build()).resolveInto(declaration);

    assertEquals(
        "SUBMITTED",
        declaration.getStatusSelect(),
        "подаването е ФАКТ, извеждането е изчисление върху него");
    assertNotNull(declaration.getResolverNotes(), "присъдите все пак се обновяват");
  }

  // ═══ 3 · БРОЯЧИТЕ, КОИТО ПЪЛНЯТ СЪОБЩЕНИЕТО НА БУТОНА ═══════════════════

  @Test
  @DisplayName("броячите отговарят на присъдите — съобщението на бутона не лъже")
  void countersMatchTheOutcomes() {
    BgApp9Resolution resolution =
        BgApp9ObstoyatelstvaResolver.resolve(healthy().insuranceService(false, null).build());

    int derived = 0;
    int refused = 0;
    for (BgApp9Outcome outcome : resolution.outcomes) {
      if (outcome.status == BgApp9Outcome.Status.DERIVED) {
        derived++;
      } else if (outcome.status == BgApp9Outcome.Status.REFUSED) {
        refused++;
      }
    }
    assertEquals(derived, resolution.derivedCount());
    assertEquals(refused, resolution.refusalCount());
    assertTrue(refused >= 1, "махнатият стаж трябва да произведе поне един отказ");
  }

  @Test
  @DisplayName("удостоверение без болничен лист се ОТКАЗВА, а не се извежда от нищо")
  void resolvingWithoutSickLeaveFails() {
    BgApp9Declaration declaration = new BgApp9Declaration();
    try {
      new Service(healthy().build()).resolveInto(declaration);
      org.junit.jupiter.api.Assertions.fail(
          "обстоятелствата се извеждат ОТ листа — без него извеждането е безпредметно");
    } catch (IllegalStateException expected) {
      assertTrue(expected.getMessage().contains("болничен лист"));
    }
  }
}
