package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.module.BgLocalizationTest;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ИЗБОРЪТ НА ФИШОВЕ ПО ВИД — през ИСТИНСКАТА заявка, срещу истинска база.
 *
 * <p><b>ЗАЩО DB-ТЕСТ, А НЕ ПОДМЕНЕН ШЕВ.</b> Твърдението е за JPQL: че условието по вида наистина
 * разделя фишовете на периода и че нито един не изпада от двете декларации. Проверено през подменен
 * {@code slipsFor}, то доказва само че Java-та вика каквото викаме — а сгрешено име на колона или
 * обърнато условие се виждат чак срещу база. Тук заявката се изпълнява.
 *
 * <p>⚠️ Класът е {@code @Tag("integration")} по наследство от {@link BgLocalizationTest} и се пуска
 * с {@code gradlew integrationTest}, НЕ с {@code gradlew test}. Числата му се отчитат отделно.
 */
class BgD1KindQueryDbTest extends BgLocalizationTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  private Company company;
  private Year year;
  private Period december;
  private EmploymentContract asen;
  private EmploymentContract boris;

  /**
   * ⚠️ НОМЕРЪТ НА ФИША Е UNIQUE (BgPaySlip.xml, {@code reference}), а базата е ЕДНА за целия JVM и
   * {@code @BeforeEach} се изпълнява ПРЕД ВСЕКИ метод. Втората фикстура с „BGPS-2026-12-001“ пада,
   * а първата минава — тоест един тест зелен и три червени, което лесно се чете като „грешка в
   * заявката“. Затова всеки пробег си слага СВОЙ префикс: номерата остават четими, а редовете —
   * различни.
   */
  private String tag;

  private String refOriginal;
  private String refCorrection;
  private String refOther;
  private BgPaySlip original;
  private BgPaySlip correction;

  /**
   * ⚠️ НИЩО НЕ СЕ ТРИЕ, И ТОВА Е НАРОЧНО. {@code jdbc:hsqldb:mem:test} е ЕДНА база за целия JVM, а
   * не по клас — тоест редовете на другите интеграционни класове са живи тук. Глобално {@code
   * DELETE FROM BgPaySlip} пада точно на това: чужди деца държат чужди родители.
   *
   * <p>Изолацията идва от ФИКСТУРАТА, не от чистенето: всеки пробег си прави СВОЯ фирма и СВОЙ
   * период, а {@code slipsFor} филтрира точно по тях. Чужд ред не може да влезе в отговора, дори да
   * съществува — и това е по-силна изолация от триенето, защото не зависи от реда на класовете.
   */
  @BeforeEach
  void seed() {
    JPA.runInTransaction(
        () -> {
          tag = "T" + System.nanoTime();
          refOriginal = tag + "/BGPS-2026-12-001";
          refCorrection = tag + "/BGPS-2026-12-001-К";
          refOther = tag + "/BGPS-2026-12-002";

          company = new Company();
          company.setName("Демо почистване ЕООД " + tag);
          company.setCode("CV" + tag);
          JPA.em().persist(company);

          // Period.year е required в домейна (Period.xml), Year — име, код, дати, тип и дружество.
          // ⚠️ Измерено: in-memory схемата ПРОПУСКА липсваща година мълчаливо, тоест липсата ѝ не е
          // това, което вали фикстурата. Пълни се пак, защото „минава срещу HSQLDB“ не значи
          // „минава
          // срещу PostgreSQL“, а свидетелят не бива да зависи от по-мека схема.
          year = new Year();
          year.setName("2026 " + tag);
          year.setCode("Y" + tag);
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setCompany(company);
          year.setTypeSelect(YearRepository.TYPE_PAYROLL);
          JPA.em().persist(year);

          december = new Period();
          december.setName("12/2026 " + tag);
          december.setFromDate(DEC_FROM);
          december.setToDate(DEC_TO);
          december.setYear(year);
          JPA.em().persist(december);

          asen = contract("Иван Колев");
          boris = contract("Борис Тонев");

          // Асен: оригиналът (редовен) и коригиращото копие — СЪЩИЯТ период. ⚠️ Копието СОЧИ
          // оригинала, както го прави retroCopyOf: без препратката то не би го изместило.
          original = slip(asen, refOriginal, false, false);
          JPA.em().persist(original);
          correction = slip(asen, refCorrection, true, false);
          correction.setBgCorrectionOf(original);
          JPA.em().persist(correction);
          // Борис: само редовен фиш. Той не бива да влиза в коригиращата.
          JPA.em().persist(slip(boris, refOther, false, false));
        });
  }

  // ═══ ДЕЙСТВАЩИЯТ ФИШ ════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ РЕДОВНАТА носи ДЕЙСТВАЩИЯ фиш — копието ВМЕСТО оригинала")
  void theRegularDeclarationCarriesTheEffectiveSlip() {
    List<BgPaySlip> slips = new Service().slipsFor(declaration(BgD1DeclarationKind.REGULAR));

    assertEquals(
        List.of(refCorrection, refOther),
        references(slips),
        "⚠️ ТУК НЕ БИВА ДА ВЛИЗА ОРИГИНАЛЪТ (Д1 би носила оригинала, а Д6 — и двата)."
            + " Потвърденото копие ЗАМЕНЯ оригинала за същия месец; редовна, генерирана преди"
            + " подаването, носи него — веднъж.");
  }

  @Test
  @DisplayName("⚠️ ЧЕРНОВА на корекция не измества — важи оригиналът")
  void aDraftCorrectionDoesNotSupersede() {
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(BgPaySlip.class, correction.getId())
                .setStatusSelect(BgPaySlipResolver.DRAFT));

    assertEquals(
        List.of(refOriginal, refOther),
        references(new Service().slipsFor(declaration(BgD1DeclarationKind.REGULAR))),
        "черновата още не е твърдение — може да бъде отказана");
  }

  @Test
  @DisplayName("⚠️ ЗАЛИЧЕН оригинал изпада; заличаващото копие не е действащо")
  void aDeletedOriginalDropsOut() {
    JPA.runInTransaction(
        () -> {
          BgPaySlip deletion = slip(asen, tag + "/BGPS-2026-12-001-З", false, true);
          deletion.setBgDeletionOf(JPA.em().find(BgPaySlip.class, original.getId()));
          JPA.em().persist(deletion);
        });

    assertEquals(
        List.of(refOther),
        references(new Service().slipsFor(declaration(BgD1DeclarationKind.REGULAR))),
        "⚠️ заличен е оригиналът — значи и корекцията му не важи; Асен изпада от редовната");
  }

  @Test
  @DisplayName("⚠️ от ДВЕ корекции на един оригинал важи ПО-КЪСНАТА")
  void theLaterOfTwoCorrectionsWins() {
    String later = tag + "/BGPS-2026-12-001-К2";
    JPA.runInTransaction(
        () -> {
          BgPaySlip second = slip(asen, later, true, false);
          second.setBgCorrectionOf(JPA.em().find(BgPaySlip.class, original.getId()));
          JPA.em().persist(second);
        });

    assertEquals(
        List.of(later, refOther),
        references(new Service().slipsFor(declaration(BgD1DeclarationKind.REGULAR))));
  }

  @Test
  @DisplayName("КОРИГИРАЩАТА носи САМО копието — и то само за засегнатото лице")
  void thecorrectingDeclarationCarriesOnlyTheRetroSlips() {
    List<BgPaySlip> slips = new Service().slipsFor(declaration(BgD1DeclarationKind.CORRECTING));

    assertEquals(
        List.of(refCorrection),
        references(slips),
        "⚠️ И БОРИС НЕ ВЛИЗА. Д1 е декларация за ЕДНО лице за ЕДИН месец (Н-13, чл. 9 ал. 1) —"
            + " корекцията засяга само лицето, за което има ретро-фиш. Взет целият месец, тя би"
            + " подала коригиращи записи за хора, чиито числа не са се променили.");
    assertTrue(slips.get(0).getBgIsCorrection(), "и е коригиращ, не редовен");
  }

  @Test
  @DisplayName("⚠️ копие, което РЕДОВНАТА вече носи, НЕ влиза в коригиращата")
  void acopyAlreadyCarriedByTheRegularIsNotCorrectedAgain() {
    JPA.runInTransaction(
        () -> {
          BgD1Declaration regular = new BgD1Declaration();
          regular.setBgDeclarationKind(BgD1DeclarationKind.REGULAR);
          regular.setCompany(JPA.em().find(Company.class, company.getId()));
          regular.setBgDataPeriod(JPA.em().find(Period.class, december.getId()));
          regular.setStatusSelect("generated");
          JPA.em().persist(regular);
          BgD1Record record = new BgD1Record();
          record.setBgD1Declaration(regular);
          record.setPaySlip(JPA.em().find(BgPaySlip.class, correction.getId()));
          JPA.em().persist(record);
        });

    assertEquals(
        Collections.emptyList(),
        references(new Service().slipsFor(declaration(BgD1DeclarationKind.CORRECTING))),
        "⚠️ корекция, направена ПРЕДИ подаването, е влязла в редовната като обикновен запис —"
            + " взета и тук, тя би стигнала до НАП втори път като „К“");
  }

  @Test
  @DisplayName("⚠️ ЗАВАРЕНА декларация (NULL вид) се държи като РЕДОВНА и срещу базата")
  void alegacyDeclarationBehavesAsRegular() {
    BgD1Declaration legacy = declaration(BgD1DeclarationKind.REGULAR);
    legacy.setBgDeclarationKind(null);

    assertEquals(
        List.of(refCorrection, refOther),
        references(new Service().slipsFor(legacy)),
        "⚠️ Прочетена наивно, заварената би дала УСЛОВИЕ ПО NULL и не би върнала нищо — тоест"
            + " декларацията би излязла празна мълчаливо. Прочитът „липса ⇒ редовна“ е в"
            + " BgD1DeclarationKind#of и минава и оттук.");
  }

  // ═══ ВТОРАТА ИСТИНСКА ЗАЯВКА · ПОСЛЕДНОТО ПОДАВАНЕ ══════════════════════

  /**
   * ⚠️ ВСИЧКИТЕ ОСЕМ, А НЕ ТРИТЕ ИНТЕРЕСНИ. Пазачът е списък на ПОЗВОЛЕНОТО, тоест твърдението му е
   * за всеки статус поотделно — включително за онези, които никой не е мислил. Списъкът е същият
   * като в {@code nap.submission.status.select} (NapSubmission.xml:123-132).
   */
  private static final List<String> ALL_SUBMISSION_STATUSES =
      Arrays.asList(
          NapSubmissionRepository.STATUS_DRAFT,
          NapSubmissionRepository.STATUS_SUBMITTING,
          NapSubmissionRepository.STATUS_SUBMITTED,
          NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN,
          NapSubmissionRepository.STATUS_ACCEPTED,
          NapSubmissionRepository.STATUS_REJECTED,
          NapSubmissionRepository.STATUS_CORRECTION_REQUIRED,
          NapSubmissionRepository.STATUS_TECHNICAL_ERROR);

  @Test
  @DisplayName("⚠️ ОСЕМТЕ СТАТУСА през ИСТИНСКАТА заявка · пускат само отхвърлено и грешка")
  void allEightSubmissionStatusesDecideThroughTheRealQuery() {
    Service service = new Service();

    for (String status : ALL_SUBMISSION_STATUSES) {
      Period period =
          periodWithSubmission(
              status, BgD1PeriodLockRules.SERVICE_CODE, BgD1PeriodLockRules.DOCUMENT_TYPE);

      assertEquals(
          status,
          service.latestSubmissionStatus(period),
          "⚠️ Заявката намира реда и чете СТАТУСА му. Разминаване тук значи, че филтърът по обхват"
              + " (услуга + вид документ) не хваща собствения ни ред — а тогава пазачът мълчи"
              + " винаги.");

      String refusal = service.refuseRegularRegeneration(regularFor(period));
      boolean allowed = BgD1RegenerationRules.ALLOWING_STATUSES.contains(status);

      assertEquals(
          allowed,
          refusal == null,
          "⚠️ Статус „"
              + status
              + "“: позволено = "
              + allowed
              + ". Пускат се САМО rejected и technical_error — при тях НАП не пази нищо. Причина: "
              + refusal);
      if (!allowed) {
        assertTrue(refusal.contains(status), "отказът назовава статуса: " + refusal);
      }
    }
  }

  @Test
  @DisplayName("⚠️ НЯКОГА ПРИЕТО · прието → отхвърлено пак ОТКАЗВА (махната граница)")
  void anAcceptedSubmissionInTheHistoryRefusesEvenAfterALaterRejection() {
    Period period =
        periodWithSubmission(
            NapSubmissionRepository.STATUS_ACCEPTED,
            BgD1PeriodLockRules.SERVICE_CODE,
            BgD1PeriodLockRules.DOCUMENT_TYPE);
    addSubmission(
        period,
        NapSubmissionRepository.STATUS_REJECTED,
        BgD1PeriodLockRules.SERVICE_CODE,
        BgD1PeriodLockRules.DOCUMENT_TYPE);

    Service service = new Service();
    assertEquals(
        NapSubmissionRepository.STATUS_REJECTED,
        service.latestSubmissionStatus(period),
        "последното наистина е отхвърленото — тоест списъкът на позволеното би пуснал");
    assertEquals(
        NapSubmissionRepository.STATUS_ACCEPTED,
        service.blockingHistoryStatus(period),
        "но историята носи приетото");

    String refusal = service.refuseRegularRegeneration(regularFor(period));
    assertNotNull(
        refusal,
        "⚠️ Правило, което чете само последното подаване, би обявило „период в поправка“ и би"
            + " пуснало случая. Грешката е в посоката, в която боли — по-късното отхвърляне НЕ"
            + " изтрива от НАП онова, което вече е прието.");
    assertTrue(refusal.contains("историята"), refusal);
    assertTrue(refusal.contains("Коригираща"), "и сочи изхода: " + refusal);
  }

  @Test
  @DisplayName("⚠️ ИЗИСКВАЩО КОРЕКЦИЯ в историята отказва по същата причина")
  void aCorrectionRequiredSubmissionInTheHistoryRefusesToo() {
    Period period =
        periodWithSubmission(
            NapSubmissionRepository.STATUS_CORRECTION_REQUIRED,
            BgD1PeriodLockRules.SERVICE_CODE,
            BgD1PeriodLockRules.DOCUMENT_TYPE);
    addSubmission(
        period,
        NapSubmissionRepository.STATUS_TECHNICAL_ERROR,
        BgD1PeriodLockRules.SERVICE_CODE,
        BgD1PeriodLockRules.DOCUMENT_TYPE);

    assertNotNull(
        new Service().refuseRegularRegeneration(regularFor(period)),
        "⚠️ „Изисква корекция“ е произнасяне ВЪРХУ получено подаване — тоест то е стигнало до НАП"
            + " и данните за месеца са там.");
  }

  @Test
  @DisplayName("⚠️ И ОБРАТНО · отхвърлено → прието ОТКАЗВА")
  void theLatestSubmissionDecidesInTheOtherDirectionToo() {
    Period period =
        periodWithSubmission(
            NapSubmissionRepository.STATUS_REJECTED,
            BgD1PeriodLockRules.SERVICE_CODE,
            BgD1PeriodLockRules.DOCUMENT_TYPE);
    addSubmission(
        period,
        NapSubmissionRepository.STATUS_ACCEPTED,
        BgD1PeriodLockRules.SERVICE_CODE,
        BgD1PeriodLockRules.DOCUMENT_TYPE);

    Service service = new Service();
    assertEquals(NapSubmissionRepository.STATUS_ACCEPTED, service.latestSubmissionStatus(period));
    assertNotNull(
        service.refuseRegularRegeneration(regularFor(period)),
        "⚠️ Прочетен като „има ли отхвърлено“, пазачът щеше да пусне точно този период — най-скъпия"
            + " от двата. Подредбата по id надолу е цялата разлика.");
  }

  @Test
  @DisplayName("⚠️ ПРИЕТО подаване на ДРУГА услуга или друг документ не се вижда изобщо")
  void anacceptedSubmissionOfAnotherServiceDoesNotBlock() {
    Service service = new Service();

    Period saft =
        periodWithSubmission(
            NapSubmissionRepository.STATUS_ACCEPTED,
            NapSubmissionRepository.SERVICE_SAFT,
            BgD1PeriodLockRules.DOCUMENT_TYPE);
    assertNull(
        service.latestSubmissionStatus(saft),
        "⚠️ SAF-T за същия месец не е Д1. Регистърът на подаванията е ОБЩ за всички услуги към НАП"
            + " и широкият прочит би отказал генерирането заради чужд документ.");
    assertNull(service.refuseRegularRegeneration(regularFor(saft)));

    Period otherDocument =
        periodWithSubmission(
            NapSubmissionRepository.STATUS_ACCEPTED,
            BgD1PeriodLockRules.SERVICE_CODE,
            "OTHER_DOCUMENT");
    assertNull(
        service.latestSubmissionStatus(otherDocument),
        "⚠️ И видът документ се пита. Д1 и Д6 пътуват в едно подаване (DEC_1_6); друг вид документ"
            + " по същата услуга не декларира тези фишове.");
    assertNull(service.refuseRegularRegeneration(regularFor(otherDocument)));
  }

  // ═══ ИЗХОДЪТ ОТ ЧЕРНОВАТА ═══════════════════════════════════════════════

  /**
   * ⚠️ ОТКАЗ БЕЗ ИЗХОД Е ЗАДЪНЕНА УЛИЦА, затова изходът иска свидетел, а не обещание.
   *
   * <p><b>Какъв е изходът.</b> Чернова на подаване се ИЗТРИВА. Друг път от {@code draft} навън няма
   * в екрана: панелът с изхода (приета/отхвърлена/изисква корекция) се показва само при {@code
   * submissionStatusSelect == 'submitted'} ({@code NapSubmission.xml:83-93}), а {@code
   * resolveUnknownBtn} работи само от {@code submission_outcome_unknown} ({@code
   * NapSubmission.xml:42-44}). Остават два хода: да подадеш черновата, или да я махнеш.
   *
   * <p><b>И триенето е възможно.</b> Нито решетката, нито формата слагат {@code canDelete="false"}
   * ({@code NapSubmission.xml:7} и {@code :22}), собствено repository с презаписан {@code remove}
   * няма, listener около триенето — също. Тук това не се ТВЪРДИ, а се МЕРИ: редът се маха през
   * истинското repository и генерирането минава след това.
   */
  @Test
  @DisplayName("⚠️ ЧЕРНОВА → отказ → изтрита чернова → генерирането МИНАВА")
  void deletingTheDraftSubmissionReopensTheRegeneration() {
    insuredType("01");
    nameTheContracts();

    Period period =
        periodWithSubmission(
            NapSubmissionRepository.STATUS_DRAFT,
            BgD1PeriodLockRules.SERVICE_CODE,
            BgD1PeriodLockRules.DOCUMENT_TYPE);
    BgD1Declaration declaration = persistDeclarationFor(period, BgD1DeclarationKind.REGULAR);

    BgD1GenerationService service = Beans.get(BgD1GenerationService.class);
    IllegalStateException refused =
        assertThrows(
            IllegalStateException.class,
            () -> service.generate(declaration.getId()),
            "⚠️ Докато черновата стои, генерирането се отказва — това е цената на списъка на"
                + " позволеното и тя трябва да е ВИДИМА, не изведена.");
    assertTrue(refused.getMessage().contains("draft"), refused.getMessage());

    int removed = deleteSubmissionsOf(period);
    assertEquals(1, removed, "⚠️ Триенето не е блокирано от нищо — измерено, не прочетено");
    assertNull(new Service().latestSubmissionStatus(period), "и подаване за периода вече няма");

    service.generate(declaration.getId());

    JPA.em().clear();
    assertEquals(
        "generated",
        JPA.em().find(BgD1Declaration.class, declaration.getId()).getStatusSelect(),
        "⚠️ И ИЗХОДЪТ Е ПРОХОДИМ ДОКРАЙ: след махнатата чернова генерирането минава през същата"
            + " врата, която току-що отказа.");
  }

  // ═══ ЗАВАРЕНАТА ДЕКЛАРАЦИЯ СЕ ЗАПИСВА ═══════════════════════════════════

  /**
   * ⚠️ КАКВО ТОЗИ СВИДЕТЕЛ МОЖЕ И КАКВО НЕ. Мери ДВЕ неща: че колоната приема NULL и че записът не
   * ДОПИСВА вид на заварен ред. И НЕ мери третото — дали {@code @NotNull} би спрял записа: {@code
   * src/test/resources/META-INF/persistence.xml:17} изключва бийн-валидацията изрично ({@code
   * javax.persistence.validation.mode=none}), тоест тук всеки {@code @NotNull} мълчи по
   * конструкция. Зелен запис оттук НЕ е свидетелство за продукцията.
   *
   * <p>Онзи въпрос се задава на САМИЯ валидатор, върху обекта — {@code
   * BgD1DeclarationKindTest#alegacyDeclarationHasNoConstraintViolationOnTheKind}, — а защо
   * задължителността слезе в изгледа, стои в {@code BgD1DeclarationKindViewTest}.
   */
  @Test
  @DisplayName("⚠️ ЗАВАРЕНА декларация с NULL вид се ЗАПИСВА — смяна на статус минава")
  void alegacyDeclarationWithoutAKindCanStillBeSaved() {
    Long id = persistLegacyDeclaration();

    BgD1Declaration reloaded = JPA.em().find(BgD1Declaration.class, id);
    assertNull(
        reloaded.getBgDeclarationKind(),
        "⚠️ Редът трябва да стигне дотук с NULL — инак свидетелят мери нещо друго. Инициализаторът"
            + " на полето пълни само НОВИТЕ обекти; SQL-ът го заобикаля, както го заобикаля и"
            + " заварената база.");
    assertEquals(
        BgD1DeclarationKind.REGULAR, BgD1DeclarationKind.of(reloaded), "и се чете като редовна");

    JPA.runInTransaction(
        () -> {
          BgD1Declaration legacy = JPA.em().find(BgD1Declaration.class, id);
          legacy.setStatusSelect("generated");
          JPA.em().persist(legacy);
          JPA.em().flush();
        });

    JPA.em().clear();
    BgD1Declaration after = JPA.em().find(BgD1Declaration.class, id);
    assertEquals("generated", after.getStatusSelect(), "записът мина");
    assertNull(
        after.getBgDeclarationKind(),
        "⚠️ И НИЩО НЕ Е ДОПИСАНО. Запис на REGULAR „в движение“ е backfill на парче: той твърди за"
            + " стария ред нещо, което никой не е решавал, и то точно в момента, в който човек е"
            + " пипнал съвсем друго поле.");
  }

  // ═══ „К“ ОТ КРАЙ ДО КРАЙ · ПРЕЗ ИСТИНСКИЯ generate ══════════════════════

  /**
   * ⚠️ ПРЕЗ {@code generate(Long)}, А НЕ ПРЕЗ СТРОИТЕЛЯ. Че {@code BgD1RecordBuilder} пише „К“ за
   * коригиращ фиш, се знае от {@code BgD1RecordTest}. Тук се мери ДРУГО: че фишът, който ВИДЪТ на
   * декларацията е избрал, стига до строителя и излиза със своя код — тоест че изборът по вид и
   * кодът в т. 1 сочат едно и също нещо. Разминати, редовната декларация би носила запис с „К“ или
   * коригиращата — запис без код, и двете правдоподобни на екрана.
   */
  @Test
  @DisplayName("⚠️ ОТ КРАЙ ДО КРАЙ · коригиращата дава запис с „К“, редовната — запис без код")
  void theCorrectionCodeTravelsFromTheKindToTheRecord() {
    insuredType("01");
    nameTheContracts();

    BgD1Declaration regular = persistDeclaration(BgD1DeclarationKind.REGULAR, null);
    BgD1Declaration correcting = persistDeclaration(BgD1DeclarationKind.CORRECTING, regular);

    // ⚠️ РЕДЪТ Е СЛУЧАЙ (б): редовната е генерирана и ПРИЕТА, докато корекцията е била чернова;
    // чак после копието е потвърдено. Обратният ред (копие преди подаването) е случай (а) — там
    // копието влиза в редовната като обикновен запис и коригираща няма.
    setStatus(correction, BgPaySlipResolver.DRAFT);
    BgD1GenerationService service = Beans.get(BgD1GenerationService.class);
    service.generate(regular.getId());
    addSubmission(
        december,
        NapSubmissionRepository.STATUS_ACCEPTED,
        BgD1PeriodLockRules.SERVICE_CODE,
        BgD1PeriodLockRules.DOCUMENT_TYPE);
    setStatus(correction, BgPaySlipResolver.CONFIRMED);
    service.generate(correcting.getId());

    List<String> regularCodes = correctionCodesOf(regular);
    List<String> correctingCodes = correctionCodesOf(correcting);
    String report =
        String.valueOf(
            JPA.em().find(BgD1Declaration.class, correcting.getId()).getBgValidationReport());
    assertTrue(
        !report.contains("D1_CHL9_AL1_IMMUTABLE"),
        "предишният запис е намерен — оригиналът в приетата редовна: " + report);

    assertEquals(
        Arrays.asList((String) null, (String) null),
        regularCodes,
        "⚠️ РЕДОВНАТА носи ДВА записа и НИТО ЕДИН с код: празното в т. 1 не е трети код, а липсата"
            + " на код (Приложение № 1 т. 1 дава букви само на корекцията и заличаването).");
    assertEquals(
        Collections.singletonList("К"),
        correctingCodes,
        "⚠️ КОРИГИРАЩАТА носи ЕДИН запис и той е с „К“ — КИРИЛСКА буква (U+041A). Латинското „K“"
            + " изглежда еднакво и би стигнало до НАП като друг знак.");
    assertEquals(
        0x041A, correctingCodes.get(0).codePointAt(0), "кодовата точка, не изгледът: „К“ е U+041A");
  }

  /**
   * ⚠️ ПРЕДИШНИЯТ ЗАПИС ПО ВЕРИГАТА. Корекция, влязла в редовната ПРЕДИ подаването, прави подадения
   * запис да сочи КОПИЕТО, не оригинала. Втора корекция на същия оригинал след приемането трябва да
   * намери точно него — иначе {@code D1_CHL9_AL1_IMMUTABLE} „предишна декларация НЕ Е НАМЕРЕНА“
   * сочи напълно грешно място.
   */
  @Test
  @DisplayName("⚠️ втора корекция след приемането намира записа на ПЪРВАТА (по веригата)")
  void asecondCorrectionFindsTheFirstOnesRecordThroughTheChain() {
    insuredType("01");
    nameTheContracts();

    BgD1Declaration regular = persistDeclaration(BgD1DeclarationKind.REGULAR, null);
    BgD1GenerationService service = Beans.get(BgD1GenerationService.class);
    service.generate(regular.getId());
    assertEquals(
        Arrays.asList((String) null, (String) null),
        correctionCodesOf(regular),
        "случай (а): първата корекция е в редовната като обикновен запис");
    addSubmission(
        december,
        NapSubmissionRepository.STATUS_ACCEPTED,
        BgD1PeriodLockRules.SERVICE_CODE,
        BgD1PeriodLockRules.DOCUMENT_TYPE);

    JPA.runInTransaction(
        () -> {
          BgPaySlip second = slip(asen, tag + "/BGPS-2026-12-001-К2", true, false);
          second.setBgCorrectionOf(JPA.em().find(BgPaySlip.class, original.getId()));
          JPA.em().persist(second);
        });
    BgD1Declaration correcting = persistDeclaration(BgD1DeclarationKind.CORRECTING, regular);
    service.generate(correcting.getId());

    assertEquals(Collections.singletonList("К"), correctionCodesOf(correcting));
    String report =
        String.valueOf(
            JPA.em().find(BgD1Declaration.class, correcting.getId()).getBgValidationReport());
    assertTrue(
        !report.contains("D1_CHL9_AL1_IMMUTABLE"),
        "⚠️ подаденият запис сочи ПЪРВАТА корекция, не оригинала — търси се по веригата: "
            + report);
  }

  private static void setStatus(BgPaySlip slip, String status) {
    JPA.runInTransaction(
        () -> JPA.em().find(BgPaySlip.class, slip.getId()).setStatusSelect(status));
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /**
   * Нов период със СВОЕ подаване. ⚠️ Отделен период за всеки статус, вместо триене между
   * итерациите: базата е обща за целия JVM и всяко триене тук е триене на чужда работа.
   */
  private Period periodWithSubmission(String status, String service, String documentType) {
    Period[] created = new Period[1];
    JPA.runInTransaction(
        () -> {
          Period period = new Period();
          period.setName(status + "/" + service + "/" + documentType + " " + System.nanoTime());
          period.setFromDate(DEC_FROM);
          period.setToDate(DEC_TO);
          period.setYear(year);
          JPA.em().persist(period);
          created[0] = period;
        });
    addSubmission(created[0], status, service, documentType);
    return created[0];
  }

  /** Второ подаване за СЪЩИЯ период — редът на вмъкване е редът, по който заявката ги подрежда. */
  private void addSubmission(Period period, String status, String service, String documentType) {
    JPA.runInTransaction(
        () -> {
          NapSubmission submission = new NapSubmission();
          submission.setCompany(company);
          submission.setPeriod(JPA.em().find(Period.class, period.getId()));
          submission.setServiceCodeSelect(service);
          submission.setServiceDocumentTypeSelect(documentType);
          submission.setSubmissionStatusSelect(status);
          JPA.em().persist(submission);
          JPA.em().flush();
        });
  }

  private BgD1Declaration regularFor(Period period) {
    BgD1Declaration declaration = new BgD1Declaration();
    declaration.setBgDeclarationKind(BgD1DeclarationKind.REGULAR);
    declaration.setCompany(company);
    declaration.setBgDataPeriod(period);
    return declaration;
  }

  /**
   * ⚠️ ЗАВАРЕНИЯТ РЕД СЕ ПРАВИ С SQL, не с {@code setBgDeclarationKind(null)} върху нов обект. През
   * обекта Hibernate вижда полето „зададено на null“ и валидацията му е същата; през SQL редът е
   * точно какъвто го е оставила ddl=update върху стара база — колона с NULL, която никой не е
   * пипал.
   */
  private Long persistLegacyDeclaration() {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgD1Declaration declaration = new BgD1Declaration();
          declaration.setCompany(company);
          declaration.setBgDataPeriod(december);
          declaration.setStatusSelect("draft");
          declaration.setBgDeclarationKind(BgD1DeclarationKind.REGULAR);
          JPA.em().persist(declaration);
          JPA.em().flush();
          JPA.em()
              .createNativeQuery(
                  "UPDATE bglocalization_bg_d1_declaration SET bg_declaration_kind = NULL"
                      + " WHERE id = :id")
              .setParameter("id", declaration.getId())
              .executeUpdate();
          id[0] = declaration.getId();
        });
    JPA.em().clear();
    return id[0];
  }

  /** Декларация за ДРУГ период — пазачът на подаванията пита периода, не фишовете. */
  private BgD1Declaration persistDeclarationFor(Period period, String kind) {
    BgD1Declaration[] created = new BgD1Declaration[1];
    JPA.runInTransaction(
        () -> {
          BgD1Declaration declaration = new BgD1Declaration();
          declaration.setBgDeclarationKind(kind);
          declaration.setCompany(JPA.em().find(Company.class, company.getId()));
          declaration.setBgDataPeriod(JPA.em().find(Period.class, period.getId()));
          declaration.setStatusSelect("draft");
          JPA.em().persist(declaration);
          JPA.em().flush();
          created[0] = declaration;
        });
    return created[0];
  }

  /** Маха подаванията на периода през ИСТИНСКОТО repository — както би го направил екранът. */
  private int deleteSubmissionsOf(Period period) {
    int[] removed = new int[1];
    JPA.runInTransaction(
        () -> {
          NapSubmissionRepository repository = Beans.get(NapSubmissionRepository.class);
          for (NapSubmission submission :
              repository.all().filter("self.period = :p").bind("p", period).fetch()) {
            repository.remove(submission);
            removed[0]++;
          }
        });
    JPA.em().clear();
    return removed[0];
  }

  private BgD1Declaration persistDeclaration(String kind, BgD1Declaration corrects) {
    BgD1Declaration[] created = new BgD1Declaration[1];
    JPA.runInTransaction(
        () -> {
          BgD1Declaration declaration = new BgD1Declaration();
          declaration.setBgDeclarationKind(kind);
          declaration.setCompany(
              JPA.em().find(com.axelor.apps.base.db.Company.class, company.getId()));
          declaration.setBgDataPeriod(JPA.em().find(Period.class, december.getId()));
          declaration.setStatusSelect("draft");
          if (corrects != null) {
            declaration.setCorrectsPrevious(JPA.em().find(BgD1Declaration.class, corrects.getId()));
          }
          JPA.em().persist(declaration);
          JPA.em().flush();
          created[0] = declaration;
        });
    return created[0];
  }

  /**
   * Видът осигурен — без ред в речника емитерът ОТКАЗВА фиша и запис изобщо не се ражда.
   *
   * <p>⚠️ ИДЕМПОТЕНТНО, защото го искат ДВА теста, а базата е ЕДНА за целия JVM и {@code
   * BgInsuredType} има unique по (code, validFrom). Вторият безусловен insert пада със
   * SQLIntegrityConstraintViolationException В ДРУГИЯ тест — провал, който сочи напълно грешно
   * място. Редът се прави веднъж и се ползва от всички.
   */
  private void insuredType(String code) {
    JPA.runInTransaction(
        () -> {
          LocalDate from = LocalDate.of(2020, 1, 1);
          Long existing =
              JPA.em()
                  .createQuery(
                      "SELECT COUNT(t) FROM BgInsuredType t WHERE t.code = :c AND t.validFrom = :f",
                      Long.class)
                  .setParameter("c", code)
                  .setParameter("f", from)
                  .getSingleResult();
          if (existing > 0) {
            return;
          }
          BgInsuredType type = new BgInsuredType();
          type.setCode(code);
          type.setName("Работници и служители " + tag);
          type.setValidFrom(from);
          JPA.em().persist(type);
        });
  }

  /** Кодът на вида осигурен виси на ДОГОВОРА — носителят го чете оттам (BgD1Carrier). */
  private void nameTheContracts() {
    JPA.runInTransaction(
        () -> {
          for (Long id : Arrays.asList(asen.getId(), boris.getId())) {
            JPA.em().find(EmploymentContract.class, id).setBgInsuredTypeCode("01");
          }
        });
  }

  private static List<String> correctionCodesOf(BgD1Declaration declaration) {
    List<BgD1Record> records =
        JPA.em()
            .createQuery(
                "SELECT r FROM BgD1Record r WHERE r.bgD1Declaration.id = :id ORDER BY r.id",
                BgD1Record.class)
            .setParameter("id", declaration.getId())
            .getResultList();
    List<String> out = new ArrayList<>();
    for (BgD1Record record : records) {
      out.add(record.getT1CorrectionCode());
    }
    return out;
  }

  private EmploymentContract contract(String name) {
    // Employee.contactPartner е required и unique (Employee.xml) — по същата причина като
    // годината се пълни, вместо да се разчита на това, че тестовата схема прощава.
    Partner partner = new Partner();
    partner.setName(name);
    partner.setCompany(company);
    JPA.em().persist(partner);
    Employee employee = new Employee();
    employee.setName(name);
    employee.setContactPartner(partner);
    JPA.em().persist(employee);
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);
    contract.setPayCompany(company);
    JPA.em().persist(contract);
    return contract;
  }

  private BgPaySlip slip(
      EmploymentContract contract, String reference, boolean correction, boolean deletion) {
    BgPaySlip slip = new BgPaySlip();
    slip.setEmploymentContract(contract);
    slip.setPeriod(december);
    slip.setReference(reference);
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    slip.setBgIsCorrection(correction);
    slip.setBgIsDeletion(deletion);
    return slip;
  }

  private BgD1Declaration declaration(String kind) {
    BgD1Declaration declaration = new BgD1Declaration();
    declaration.setBgDeclarationKind(kind);
    declaration.setCompany(company);
    declaration.setBgDataPeriod(december);
    return declaration;
  }

  private static List<String> references(List<BgPaySlip> slips) {
    List<String> out = new ArrayList<>();
    for (BgPaySlip slip : slips) {
      out.add(slip.getReference());
    }
    out.sort(String::compareTo);
    return out;
  }

  /** ⚠️ НИЩО НЕ Е ПОДМЕНЕНО: {@code slipsFor} е истинският и удря истинската база. */
  private static final class Service extends BgD1GenerationService {}
}
