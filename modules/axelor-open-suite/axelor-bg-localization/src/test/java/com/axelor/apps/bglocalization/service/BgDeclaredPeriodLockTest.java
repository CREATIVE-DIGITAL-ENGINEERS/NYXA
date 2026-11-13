package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipManagementRepository;
import com.axelor.apps.bglocalization.service.d1.BgD1PeriodLockRules;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КЛЮЧ (Б) НА WS-7: ПОДАДЕНАТА Д1/Д6 ЗАКЛЮЧВА ПЕРИОДА, дори той да е още ОТВОРЕН.
 *
 * <p><b>Какво лови.</b> Ключ (А) — статусът CLOSED на периода — не е вързан за декларацията по
 * никакъв начин. Само с него редът „подай Д1/Д6 към НАП, после затвори месеца“ (или „изобщо не го
 * затвори“) оставя цял прозорец, в който фиш от вече декларирания месец може да се откаже или
 * изтрие, а следващата декларация просто няма да го носи. Тихо е, защото всяко от двете е вярно за
 * себе си: фишът е верен, декларацията е била вярна при подаването, а разминаването няма нито един
 * читател.
 *
 * <p><b>Двете посоки, и двете задължителни.</b> Заключва САМО реално подадената и НЕотхвърлена
 * декларация: генерираната (чернова на подаване) не е изявление пред НАП, а отхвърлената иска точно
 * поправка и ново подаване — заключени, те щяха да блокират единствения смислен път.
 *
 * <p><b>Без база и без Guice.</b> Правилото е в {@link BgD1PeriodLockRules} и в {@link
 * BgPaySlipResolver}; заявката ({@code BgD1PeriodLockService}) виси на шев, който тук се подменя с
 * един ред — модулът няма mock-подпора и JPQL се сверява структурно.
 */
class BgDeclaredPeriodLockTest {

  // ── услугата с подменени шевове ─────────────────────────────────────────

  /** Същите неутрални шевове като {@code BgPaySlipLockedPeriodTest}, плюс ключ (Б) наум. */
  private static class Service extends BgPaySlipService {

    /**
     * Производните пера на коригиращото копие (5xx, 516 по т. 5, 700, карвът на 180/181) имат свой
     * свидетел — {@code BgPaySlipRetroGeneratorsTest}. Тук се мери механиката на ретро-потока
     * (делтата, периодът, ключовете), не числата на генераторите; calculate() минава истински —
     * отказите, сборовете и отпечатъкът.
     */
    @Override
    protected void prepareCorrection(BgPaySlip copy) {}

    /** Какъвто и период да дойде: „за него има подадена Д1/Д6“ или „няма“. */
    private final boolean declared;

    private Service(boolean declared) {
      this.declared = declared;
    }

    @Override
    protected boolean declaredToNap(Period period) {
      return declared;
    }

    @Override
    protected void generateEarnings(BgPaySlip slip) {}

    // ⚠️ Потвърждаването приема само фиш, пресметнат по calculate(). Тестовете тук мерят
    // пазачите на ПЕРИОДА, не генераторите — затова генераторите са празни, точно както при
    // BgPaySlipGuardsTest, а calculate() е истинският.
    @Override
    protected void generateSickLeave(BgPaySlip slip) {}

    @Override
    protected void freezeLeaveDays(BgPaySlip slip) {}

    @Override
    protected void generateArt177Leave(BgPaySlip slip) {}

    @Override
    protected void generateCompensations(BgPaySlip slip) {}

    @Override
    protected void generateContributions(BgPaySlip slip) {}

    @Override
    protected void generateIncomeTax(BgPaySlip slip) {}

    @Override
    protected void generateAnnualSettlement(BgPaySlip slip) {}

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return new BgWorkingDayService() {
        @Override
        public java.util.Set<Integer> coveredYears() {
          return new java.util.HashSet<>(Arrays.asList(2026, 2027));
        }

        @Override
        protected java.util.Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
          return Collections.emptySet();
        }
      }.count(LocalDate.of(2026, 3, 1), 31);
    }

    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      return BgContributionResult.resolved(Collections.emptyList());
    }

    @Override
    protected BgMinIncomeResult minIncome(BgPaySlip slip) {
      return BgMinIncomeResult.notApplicable("неутрално за този тест");
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.0000");
    }

    @Override
    protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
      return null;
    }

    @Override
    protected void save(BgPaySlip slip) {}

    @Override
    protected void saveCompensation(BgCompensation doc) {}

    // ── шевовете на ретро-механизма, подменени както в BgPaySlipCorrectionTest ──

    /** Текущият отворен месец, в който сяда нетната делта. */
    @Override
    protected Period currentOpenPeriod(BgPaySlip slip) {
      Period may = march(PeriodRepository.STATUS_OPENED);
      may.setName("05/PY2026");
      may.setFromDate(LocalDate.of(2026, 5, 1));
      may.setToDate(LocalDate.of(2026, 5, 31));
      return may;
    }

    @Override
    protected BgPaySlip openDraftSlip(EmploymentContract contract, Period period) {
      BgPaySlip draft = new BgPaySlip();
      draft.setStatusSelect(BgPaySlipResolver.DRAFT);
      draft.setPeriod(period);
      draft.setBgPaySlipLineList(new ArrayList<>());
      return draft;
    }

    @Override
    protected BgPaySlipLineType correctionLineType() {
      return type(620, "Доплащане/удръжка по корекция за минал период", "CREDIT", false, false);
    }

    @Override
    protected void savePeriod(Period period) {}

    @Override
    protected void logAdjustStart(Period period) {}

    @Override
    protected void logAdjustEnd(Period period) {}

    @Override
    protected LocalDate today() {
      return LocalDate.of(2026, 5, 20);
    }
  }

  /** Репозиторият с подменени шевове: пазачът се мери без JPA. */
  private static class Repo extends BgPaySlipManagementRepository {

    private final boolean declared;
    private boolean removed;

    private Repo(boolean declared) {
      this.declared = declared;
    }

    @Override
    protected boolean declaredToNap(Period period) {
      return declared;
    }

    @Override
    protected void removeRecord(BgPaySlip slip) {
      removed = true;
    }
  }

  private static BgPaySlipLineType type(
      int number, String name, String sign, boolean taxable, boolean insurable) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(taxable);
    t.setBgIsInsuranceIncome(insurable);
    t.setBgIsPermanentCharacter(false);
    return t;
  }

  private static Period march(Integer status) {
    Year year = new Year();
    year.setName("Заплатна 2026");
    year.setTypeSelect(2);
    Period period = new Period();
    period.setName("03/PY2026");
    period.setFromDate(LocalDate.of(2026, 3, 1));
    period.setToDate(LocalDate.of(2026, 3, 31));
    period.setYear(year);
    period.setStatusSelect(status);
    return period;
  }

  /** Мартенски фиш, готов за потвърждаване, върху период със ЗАДАДЕН статус. */
  private static BgPaySlip readySlip(Service s, Integer periodStatus) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setPeriod(march(periodStatus));
    slip.setBgPaySlipLineList(new ArrayList<>());
    s.addLine(
        slip,
        s.lineFrom(
            type(100, "Основно възнаграждение", "CREDIT", true, true), new BigDecimal("2000.00")));
    s.recompute(slip);
    s.addLine(
        slip,
        s.lineFrom(
            type(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT", false, false), slip.getBgTaxAmount()));
    s.calculate(slip);
    return slip;
  }

  private static BgPaySlip declaredSlip(String status, Integer periodStatus) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(status);
    slip.setPeriod(march(periodStatus));
    slip.setBgCancelReason("операторът смята, че фишът е сгрешен");
    return slip;
  }

  // ── 1 · кои статуси на подаването заключват ─────────────────────────────

  @Test
  @DisplayName(
      "⚠️ Заключва САМО реално подаденото: подадено, прието, иска корекция, неизвестен изход")
  void onlyRealSubmissionsLock() {
    assertTrue(BgD1PeriodLockRules.locks(NapSubmissionRepository.STATUS_SUBMITTED), "подадено");
    assertTrue(BgD1PeriodLockRules.locks(NapSubmissionRepository.STATUS_ACCEPTED), "прието");
    assertTrue(
        BgD1PeriodLockRules.locks(NapSubmissionRepository.STATUS_CORRECTION_REQUIRED),
        "⚠️ „изисква корекция“ НЕ е отказ: файлът е в НАП и точно затова поправката му е КОРИГИРАЩА"
            + " декларация, а не редовна редакция на месеца");
    assertTrue(
        BgD1PeriodLockRules.locks(NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN),
        "⚠️ неизвестният изход заключва НАРОЧНО: самият транспорт отказва повторен POST по него,"
            + " защото НАП може вече да държи файла");
  }

  @Test
  @DisplayName(
      "⚠️ ГЕНЕРИРАНАТА (чернова на подаване) НЕ заключва — иначе всеки пробен натиск замразява месеца")
  void aGeneratedButUnsentDeclarationDoesNotLock() {
    assertFalse(BgD1PeriodLockRules.locks(NapSubmissionRepository.STATUS_DRAFT), "чернова");
    assertFalse(
        BgD1PeriodLockRules.locks(NapSubmissionRepository.STATUS_SUBMITTING),
        "⚠️ „изпраща се“ живее и умира вътре в транзакцията около POST-а — в базата не остава, а"
            + " прочетен като подаден, би заключил месец по опит, приключил с техническа грешка");
  }

  @Test
  @DisplayName("⚠️ ОТХВЪРЛЕНАТА не заключва — тя иска поправка и НОВО подаване, не корекция")
  void aRejectedSubmissionDoesNotLock() {
    assertFalse(BgD1PeriodLockRules.locks(NapSubmissionRepository.STATUS_REJECTED), "отхвърлено");
    assertFalse(
        BgD1PeriodLockRules.locks(NapSubmissionRepository.STATUS_TECHNICAL_ERROR),
        "техническата грешка изобщо не е стигнала до НАП");
    assertFalse(BgD1PeriodLockRules.locks(null), "празен статус");
    assertFalse(
        BgD1PeriodLockRules.locks("непознат_статус"), "нов статус по подразбиране НЕ заключва");
  }

  @Test
  @DisplayName("Осемте статуса на договора са изброени — нито един не е забравен")
  void everyContractStatusIsAccountedFor() {
    List<String> all =
        Arrays.asList(
            NapSubmissionRepository.STATUS_DRAFT,
            NapSubmissionRepository.STATUS_SUBMITTING,
            NapSubmissionRepository.STATUS_SUBMITTED,
            NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN,
            NapSubmissionRepository.STATUS_ACCEPTED,
            NapSubmissionRepository.STATUS_REJECTED,
            NapSubmissionRepository.STATUS_CORRECTION_REQUIRED,
            NapSubmissionRepository.STATUS_TECHNICAL_ERROR);
    assertEquals(8, all.size(), "nap.submission.status.select носи осем стойности");
    assertTrue(
        all.containsAll(BgD1PeriodLockRules.LOCKING_STATUSES),
        "заключващите са ПОДМНОЖЕСТВО на договора — измислен статус тук не заключва нищо");
    assertEquals(4, BgD1PeriodLockRules.LOCKING_STATUSES.size(), "четири заключват, четири не");
  }

  // ── 2 · заявката: кое подаване изобщо се пита ───────────────────────────

  @Test
  @DisplayName("⚠️ Пита се САМО подаване на декларации DEC_1_6 за ТОЗИ период")
  void theQueryAsksForDeclarationsOnly() {
    String filter = BgD1PeriodLockRules.FILTER;
    assertTrue(filter.contains("self.period = :period"), filter);
    assertTrue(filter.contains("self.serviceCodeSelect = :service"), filter);
    assertTrue(filter.contains("self.serviceDocumentTypeSelect = :documentType"), filter);
    assertTrue(filter.contains("self.submissionStatusSelect IN :statuses"), filter);
    assertEquals("declarations", BgD1PeriodLockRules.SERVICE_CODE, "не SAF-T");
    assertEquals("DEC_1_6", BgD1PeriodLockRules.DOCUMENT_TYPE, "Д1 и Д6 пътуват в едно подаване");
  }

  @Test
  @DisplayName("⚠️ Четирите полета във филтъра СЪЩЕСТВУВАТ върху NapSubmission")
  void everyFieldInTheFilterExists() {
    // ⚠️ BgQueryFieldTest сверява само филтри, написани като ЛИТЕРАЛ на самото място — този е
    // константа в чужд клас и остава извън обхвата му. Тоест сгрешено име тук не пада при билд, а
    // при първото извикване, с празен резултат или с изключение от Hibernate: пазач, който мълчи,
    // изглежда точно като период без подадена декларация.
    for (String field :
        Arrays.asList(
            "period", "serviceCodeSelect", "serviceDocumentTypeSelect", "submissionStatusSelect")) {
      assertTrue(
          BgD1PeriodLockRules.FILTER.contains("self." + field), "филтърът вече не пита " + field);
      assertDoesNotThrow(
          () -> NapSubmission.class.getDeclaredField(field),
          "NapSubmission няма поле „" + field + "“ — филтърът сочи в празното");
    }
  }

  @Test
  @DisplayName(
      "⚠️ Д6 БЕЗ Д1 (и обратно) заключва също: питането е за ПОДАВАНЕТО, не за файловете в него")
  void theFileSetOfTheSubmissionIsIrrelevant() {
    // ⚠️ Подаването е ЕДНО (DEC_1_6) и носи един или два файла: Д1 е задължителен, Д6 е по избор
    // (BgD1SubmissionPlan#plan). Ако заявката питаше за файловете, подаване само с Д1 нямаше да
    // заключи месеца, макар че точно то декларира лицата му.
    assertFalse(BgD1PeriodLockRules.FILTER.contains("submissionFileList"), "не пита файловете");
    assertFalse(BgD1PeriodLockRules.FILTER.contains("fileDocumentType"), "нито вида им");
  }

  @Test
  @DisplayName("⚠️ Обратният прочит съвпада с онова, което подаването ЗАПИСВА")
  void theReverseReadMatchesWhatAttachWrites() throws IOException {
    Path attach =
        Paths.get(
            "src/main/java/com/axelor/apps/bglocalization/service/d1/BgD1SubmissionService.java");
    String source = new String(Files.readAllBytes(attach), StandardCharsets.UTF_8);
    // ⚠️ ТОВА Е ЕДИНСТВЕНАТА връзка между подаването и периода. Махне ли се редът, пазачът
    // продължава да компилира и мълчаливо спира да заключва каквото и да било.
    assertTrue(
        source.contains("submission.setPeriod(d1.getBgDataPeriod())"),
        "подаването пълни period от периода на данните на Д1 — оттам идва и обратният прочит");
    assertTrue(
        source.contains("ServiceDocumentType.DEC_1_6.name()"),
        "видът документ на подаването е DEC_1_6");
    assertTrue(
        source.contains("NapSubmissionRepository.STATUS_DRAFT"),
        "⚠️ attach() спира на ЧЕРНОВА — подписът и подаването са човешки стъпки, тоест закачането"
            + " на файловете само по себе си НЕ бива да заключва месеца");
  }

  // ── 3 · самото правило „А ИЛИ Б“ ────────────────────────────────────────

  @Test
  @DisplayName("⚠️ ОТВОРЕН период с подадена декларация Е заключен — това е цялата дупка")
  void anOpenButDeclaredPeriodIsLocked() {
    Period open = march(PeriodRepository.STATUS_OPENED);
    assertFalse(BgPaySlipResolver.isPeriodLocked(open), "ключ (А) мълчи");
    assertTrue(BgPaySlipResolver.isPeriodLocked(open, true), "ключ (Б) го заключва");
    assertFalse(BgPaySlipResolver.isPeriodLocked(open, false), "без подаване — не");
  }

  @Test
  @DisplayName("Ключ (А) важи както преди — липсващото подаване не отключва ЗАТВОРЕН период")
  void keyAStillWorksOnItsOwn() {
    Period closed = march(PeriodRepository.STATUS_CLOSED);
    assertTrue(BgPaySlipResolver.isPeriodLocked(closed), "без промяна");
    assertTrue(BgPaySlipResolver.isPeriodLocked(closed, false), "и с изричното „няма подаване“");
    assertFalse(
        BgPaySlipResolver.isPeriodLocked(null, true), "липсващ период не е заключен период");
  }

  @Test
  @DisplayName(
      "⚠️ И в ADJUSTING подаването държи месеца заключен — корекцията минава по ДРУГ признак")
  void adjustingStaysLockedWhileTheSubmissionExists() {
    Period adjusting = march(PeriodRepository.STATUS_ADJUSTING);
    assertFalse(
        BgPaySlipResolver.isPeriodLocked(adjusting), "ADJUSTING сам по себе си не е заключен");
    assertTrue(
        BgPaySlipResolver.isPeriodLocked(adjusting, true),
        "⚠️ подаването не изчезва, докато месецът се коригира — иначе редовен фиш би влязъл през"
            + " прозореца, отворен за КОРИГИРАЩИЯ");
  }

  // ── 4 · потвърждаването ─────────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ Редовен фиш върху ОТВОРЕН, но ДЕКЛАРИРАН период не се потвърждава")
  void anOrdinarySlipOnADeclaredOpenPeriodIsRefused() {
    Service s = new Service(true);
    BgPaySlip slip = readySlip(s, PeriodRepository.STATUS_OPENED);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(e.getMessage().contains("03/PY2026"), e.getMessage());
    assertTrue(e.getMessage().contains("ЗАКЛЮЧЕН"), e.getMessage());
    assertTrue(e.getMessage().contains("Д1/Д6"), e.getMessage());
    assertTrue(e.getMessage().contains("КОРЕКЦИЯ"), "съобщението сочи изхода: " + e.getMessage());
    assertTrue(e.getMessage().contains("ЗАЛИЧАВАНЕ"), e.getMessage());
    assertTrue(
        e.getMessage().contains("затворете"),
        "⚠️ корекция се отваря САМО върху ЗАКЛЮЧЕН период (refuseRetroPeriod) — без това изречение"
            + " съобщението праща оператора към врата, която ще му откаже: "
            + e.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect(), "остава чернова");
  }

  @Test
  @DisplayName("Същият фиш върху ОТВОРЕН и НЕдеклариран период минава — без регресия")
  void theSameSlipPassesWhenNothingWasSubmitted() throws Exception {
    Service s = new Service(false);
    BgPaySlip slip = readySlip(s, PeriodRepository.STATUS_OPENED);
    s.confirm(slip);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect());
  }

  @Test
  @DisplayName("⚠️ КОРИГИРАЩИЯТ фиш минава и през ключ (Б) — инак поправка няма")
  void theCorrectingSlipPassesTheSecondKeyToo() throws Exception {
    Service s = new Service(true);
    BgPaySlip original = readySlip(s, PeriodRepository.STATUS_CLOSED);
    original.setStatusSelect(BgPaySlipResolver.PAID);

    BgPaySlip copy = readySlip(s, PeriodRepository.STATUS_ADJUSTING);
    copy.setBgIsCorrection(true);
    copy.setBgCorrectionOf(original);
    inherited(copy);
    s.confirm(copy);
    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect(), "корекцията се потвърждава");
  }

  @Test
  @DisplayName("⚠️ ЗАЛИЧАВАЩИЯТ фиш също минава — той е пътят записът да ИЗЧЕЗНЕ от НАП")
  void theDeletingSlipPassesTheSecondKeyToo() throws Exception {
    Service s = new Service(true);
    BgPaySlip original = readySlip(s, PeriodRepository.STATUS_CLOSED);
    original.setStatusSelect(BgPaySlipResolver.PAID);

    BgPaySlip copy = readySlip(s, PeriodRepository.STATUS_ADJUSTING);
    copy.setBgIsDeletion(true);
    copy.setBgDeletionOf(original);
    inherited(copy);
    s.confirm(copy);
    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect());
  }

  /**
   * Режимът, който копието наследява от пресметнат оригинал. Фикстурата тук няма договор, тоест
   * режим няма откъде да се замрази; копието го носи като ДАННИ, точно както retroCopyOf би го
   * пренесло. Тестът мери ключа на периода, не режима.
   */
  private static void inherited(BgPaySlip copy) {
    new com.axelor.apps.bglocalization.service.payroll.BgPayrollRegime(
            "01",
            "3",
            true,
            null,
            "ANY",
            "NON_TEACHER",
            null,
            null,
            null,
            "1",
            null,
            null,
            null,
            false,
            null,
            null,
            false,
            null,
            null,
            null)
        .writeTo(
            copy,
            com.axelor.apps.bglocalization.service.payroll.BgPayrollRegime.COPIED,
            java.time.LocalDateTime.of(2026, 4, 1, 0, 0));
  }

  // ── 5 · отказът на фиша ─────────────────────────────────────────────────

  @Test
  @DisplayName(
      "⚠️ ПОТВЪРДЕН фиш от деклариран период не се ОТКАЗВА — иначе изчезва от следващата Д1")
  void cancellingADeclaredSlipIsRefused() {
    Service s = new Service(true);
    BgPaySlip slip = declaredSlip(BgPaySlipResolver.CONFIRMED, PeriodRepository.STATUS_OPENED);

    AxelorException e = assertThrows(AxelorException.class, () -> s.cancel(slip));
    assertTrue(e.getMessage().contains("03/PY2026"), e.getMessage());
    assertTrue(e.getMessage().contains("не се ОТКАЗВА"), e.getMessage());
    assertTrue(e.getMessage().contains("КОРЕКЦИЯ"), e.getMessage());
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect(), "статусът не е пипнат");
  }

  @Test
  @DisplayName("⚠️ И ИЗПЛАТЕНИЯТ фиш е в декларацията — отказът му се спира по същия начин")
  void cancellingAPaidDeclaredSlipIsRefused() {
    Service s = new Service(true);
    BgPaySlip slip = declaredSlip(BgPaySlipResolver.PAID, PeriodRepository.STATUS_CLOSED);
    AxelorException e = assertThrows(AxelorException.class, () -> s.cancel(slip));
    assertTrue(e.getMessage().contains("ЗАКЛЮЧЕН"), e.getMessage());
  }

  @Test
  @DisplayName("ЧЕРНОВАТА от деклариран период се отказва свободно — тя не е влизала в декларация")
  void cancellingADraftIsStillAllowed() throws Exception {
    Service s = new Service(true);
    BgPaySlip slip = declaredSlip(BgPaySlipResolver.DRAFT, PeriodRepository.STATUS_OPENED);
    s.cancel(slip);
    assertEquals(BgPaySlipResolver.CANCELLED, slip.getStatusSelect());
  }

  @Test
  @DisplayName("⚠️ ОТКАЗЪТ НА КОРИГИРАЩО КОПИЕ минава — той е единият от двата изхода на ADJUSTING")
  void cancellingTheCorrectingCopyIsStillAllowed() throws Exception {
    Service s = new Service(true);
    BgPaySlip copy = declaredSlip(BgPaySlipResolver.CONFIRMED, PeriodRepository.STATUS_ADJUSTING);
    copy.setBgIsCorrection(true);
    s.cancel(copy);
    assertEquals(BgPaySlipResolver.CANCELLED, copy.getStatusSelect());
  }

  @Test
  @DisplayName("Без подаване отказът на потвърден фиш по ОТВОРЕН период минава — без регресия")
  void cancellingAnUndeclaredSlipIsUntouched() throws Exception {
    Service s = new Service(false);
    BgPaySlip slip = declaredSlip(BgPaySlipResolver.CONFIRMED, PeriodRepository.STATUS_OPENED);
    s.cancel(slip);
    assertEquals(BgPaySlipResolver.CANCELLED, slip.getStatusSelect());
  }

  // ── 6 · изтриването ─────────────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ Деклариран фиш не се ТРИЕ — и най-грубата врата е пазена")
  void deletingADeclaredSlipIsRefused() {
    Repo repo = new Repo(true);
    BgPaySlip slip = declaredSlip(BgPaySlipResolver.CONFIRMED, PeriodRepository.STATUS_OPENED);

    PersistenceException e = assertThrows(PersistenceException.class, () -> repo.remove(slip));
    assertNotNull(e.getMessage());
    assertTrue(e.getMessage().contains("не се ТРИЕ"), e.getMessage());
    assertTrue(e.getMessage().contains("ЗАЛИЧАВАНЕ"), "сочи верния изход: " + e.getMessage());
    assertFalse(repo.removed, "редът остава");
  }

  @Test
  @DisplayName("Черновата от деклариран период се трие — тя няма ред в нито една декларация")
  void deletingADraftIsAllowed() {
    Repo repo = new Repo(true);
    repo.remove(declaredSlip(BgPaySlipResolver.DRAFT, PeriodRepository.STATUS_OPENED));
    assertTrue(repo.removed, "черновата се трие");
  }

  @Test
  @DisplayName("Без подаване и без CLOSED изтриването минава както преди — без регресия")
  void deletingAnUndeclaredSlipIsUntouched() {
    Repo repo = new Repo(false);
    repo.remove(declaredSlip(BgPaySlipResolver.CONFIRMED, PeriodRepository.STATUS_OPENED));
    assertTrue(repo.removed);
  }

  // ── 7 · чистите откази, дума по дума ────────────────────────────────────

  @Test
  @DisplayName("Отказите мълчат, когато няма какво да откажат")
  void theRefusalsStaySilentWithoutAReason() {
    assertNull(BgPaySlipResolver.refuseLockedPeriod(null, true));
    assertNull(BgPaySlipResolver.refuseLockedPeriod(new BgPaySlip(), true), "фиш без период");
    assertNull(BgPaySlipResolver.refuseCancelInLockedPeriod(null, true));
    assertNull(BgPaySlipResolver.refuseDeleteInLockedPeriod(null, true));
    assertNull(
        BgPaySlipResolver.refuseCancelInLockedPeriod(
            declaredSlip(BgPaySlipResolver.CANCELLED, PeriodRepository.STATUS_CLOSED), true),
        "вече отказаният фиш не е в декларацията");
  }

  @Test
  @DisplayName("⚠️ Върху CLOSED период съобщението НЕ праща да се затваря вече затвореното")
  void theClosedPeriodMessageDoesNotAskToCloseAgain() {
    String refusal =
        BgPaySlipResolver.refuseLockedPeriod(
            declaredSlip(BgPaySlipResolver.DRAFT, PeriodRepository.STATUS_CLOSED), true);
    assertNotNull(refusal);
    assertFalse(refusal.contains("затворете"), "периодът вече е затворен: " + refusal);
    assertTrue(refusal.contains("КОРЕКЦИЯ"), refusal);
  }

  @Test
  @DisplayName("Списъкът на декларируемите статуси е ЕДИН — пазачът и заявката не се разминават")
  void declarableStatusesAreASingleSource() {
    assertEquals(
        Arrays.asList(BgPaySlipResolver.CONFIRMED, BgPaySlipResolver.PAID),
        BgPaySlipResolver.DECLARABLE_STATUSES);
    assertTrue(BgPaySlipResolver.isDeclarable(declaredSlip(BgPaySlipResolver.PAID, null)));
    assertFalse(BgPaySlipResolver.isDeclarable(declaredSlip(BgPaySlipResolver.DRAFT, null)));
    assertFalse(BgPaySlipResolver.isDeclarable(null));
  }

  /**
   * ⚠️ ПРАВИЛОТО НЕ СЕ МЕНИ, МЕНИ СЕ КЪДЕ ЖИВЕЕ ЗАЯВКАТА. Д1 и Д6 четат фишовете през ЕДНА заявка
   * ({@code BgEffectivePaySlips}): две копия се разминават — едното взима оригинала, другото
   * оригинала И корекцията. Тестът сочи ФАЙЛА, който носи заявката, и проверява, че двете
   * декларации не носят своя.
   */
  @Test
  @DisplayName("⚠️ Д1 и Д6 четат фишовете през ЕДИН метод, а той — споделения списък")
  void bothDeclarationQueriesReadTheSharedList() throws IOException {
    String shared =
        new String(
            Files.readAllBytes(
                Paths.get(
                    "src/main/java/com/axelor/apps/bglocalization/service/BgEffectivePaySlips.java")),
            StandardCharsets.UTF_8);
    assertTrue(
        shared.contains("BgPaySlipResolver.DECLARABLE_STATUSES"),
        "общата заявка държи свое копие на списъка — то е свободно да се размине с пазача");
    for (String path :
        Arrays.asList(
            "src/main/java/com/axelor/apps/bglocalization/service/d1/BgD1GenerationService.java",
            "src/main/java/com/axelor/apps/bglocalization/service/d1/BgD6GenerationService.java")) {
      String source = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
      assertTrue(
          source.contains("BgEffectivePaySlips.effectiveFor("),
          path + " не чете действащите фишове през общия метод");
      assertTrue(
          !source.contains("self.statusSelect IN"),
          path + " ⚠️ носи СВОЯ заявка за фишовете — второ копие се разминава с общото");
    }
  }
}
