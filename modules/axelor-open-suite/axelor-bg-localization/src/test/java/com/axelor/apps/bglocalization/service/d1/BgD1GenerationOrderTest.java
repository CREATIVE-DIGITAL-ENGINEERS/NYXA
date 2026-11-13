package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * РЕДЪТ НА ГЕНЕРИРАНЕТО — и ГРАНИЦАТА, която вече е обърната.
 *
 * <p><b>ПЪТЯТ Е ИСТИНСКИЯТ.</b> Свидетелите викат {@code BgD1GenerationService#generate}, тоест
 * редът на стъпките е неговият, а не описан в проза.
 *
 * <p><b>⚠️ КАКВО ТЕЗИ СВИДЕТЕЛИ НЕ ДОКАЗВАТ.</b> {@link BgD1GenerationService#slipsFor} е подменен
 * — тук се мери РЕДЪТ на стъпките, не заявката. Кои фишове връща всеки вид се мери срещу истинска
 * база в {@code BgD1KindQueryDbTest}. Само по период наборът „оригинал + копие“ е ДОСТИЖИМ (копието
 * носи същия период), а набор само от ретро-фишове — не, защото нищо не изключва оригинала.
 * Изключва го видът на декларацията.
 */
class BgD1GenerationOrderTest {

  private static final LocalDate DEC_FROM = LocalDate.of(2026, 12, 1);
  private static final LocalDate DEC_TO = LocalDate.of(2026, 12, 31);

  // ═══ ОБЪРНАТАТА ГРАНИЦА ═════════════════════════════════════════════════

  /**
   * <b>⚠️ ОБЪРНАТАТА ГРАНИЦА — смесеният файл не може да се роди.</b>
   *
   * <p>Избор само по период би взел при втори „Генерирай“ след пристигането и оригинала, и копието
   * (копието носи СЪЩИЯ период) и би произвел файл с редовен И коригиращ запис за едно лице; {@code
   * D1_MIXED_CODES} го обявява, но не го спира. Отказ върху СМЕСВАНЕТО би затворил единствения
   * изход, който сам сочи.
   *
   * <p><b>Затова разграничителят е ВИДЪТ на декларацията.</b> Редовната носи действащия фиш като
   * запис без код, коригиращата — само ретро-копия с код, тоест смесен набор не възниква. Тук се
   * мери, че наборът, който ЕДНА декларация носи, е чист по код; кои фишове връща заявката за всеки
   * вид се мери срещу истинска база в {@code BgD1KindQueryDbTest}.
   */
  @Test
  @DisplayName("⚠️ ГРАНИЦА (обърната) · наборът на една декларация вече е чист по код")
  void adeclarationSetIsNeverMixedAnyMore() {
    // Редовната: само оригинали.
    Service regular = new Service(regular("7501014567", "BGPS-2026-12-001"));
    regular.generate(1L);
    assertTrue(regular.emitted && regular.deleted);
    assertNull(
        BgD1CorrectionRules.refuseMixedCodes(Arrays.asList(recordFor("7501014567", null))),
        "редовната носи само записи без код — няма какво да се обявява");

    // Коригиращата: само копието, с код „К“.
    Service correcting = new Service(correcting("7501014567", "BGPS-2026-12-001-К"));
    correcting.kind = BgD1DeclarationKind.CORRECTING;
    // Коригиращата се генерира само когато НАП държи редовната.
    correcting.history = NapSubmissionRepository.STATUS_ACCEPTED;
    correcting.generate(1L);
    assertTrue(correcting.emitted && correcting.deleted);
    assertNull(
        BgD1CorrectionRules.refuseMixedCodes(Arrays.asList(recordFor("7501014567", "К"))),
        "⚠️ И КОРИГИРАЩАТА Е ЧИСТА: само „К“, нито един редовен запис. Правилото за смесването"
            + " остава находка и НЕ се ползва като предпоставка — моделът просто не смесва.");
  }

  @Test
  @DisplayName("⚠️ КОРИГИРАЩА без прието подаване · отказ ПРЕДИ емитера и изтриването")
  void acorrectingDeclarationBeforeAcceptanceIsRefusedBeforeTheDeletion() {
    Service correcting = new Service(correcting("7501014567", "BGPS-2026-12-001-К"));
    correcting.kind = BgD1DeclarationKind.CORRECTING;

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> correcting.generate(1L));
    assertTrue(refused.getMessage().contains("НАП още не държи РЕДОВНАТА"), refused.getMessage());
    assertFalse(correcting.emitted, "емитерът не е викан");
    assertFalse(correcting.deleted, "⚠️ и нищо не е изтрито — спреният случай не струва нищо");
  }

  @Test
  @DisplayName("Само редовни фишове · непроменен път за месец без корекции")
  void aregularOnlyDeclarationPasses() {
    Service service =
        new Service(
            regular("7501014567", "BGPS-2026-12-001"), regular("8002024321", "BGPS-2026-12-002"));
    service.generate(1L);
    assertTrue(service.emitted && service.deleted);
    assertNull(
        BgD1CorrectionRules.refuseMixedCodes(
            Arrays.asList(recordFor("7501014567", null), recordFor("8002024321", null))),
        "и файлът е чист — няма какво да се обявява");
  }

  private static com.axelor.apps.bglocalization.db.BgD1Record recordFor(
      String personalId, String correctionCode) {
    com.axelor.apps.bglocalization.db.BgD1Record record =
        new com.axelor.apps.bglocalization.db.BgD1Record();
    record.setT5PersonalId(personalId);
    record.setT1CorrectionCode(correctionCode);
    return record;
  }

  @Test
  @DisplayName("⚠️ РЕДЪТ · при чист набор изтриването е СЛЕД емитера, не преди него")
  void thedeletionHappensAfterTheEmitter() {
    Service service = new Service(regular("7501014567", "BGPS-2026-12-001"));
    service.generate(1L);
    assertEquals(
        Arrays.asList("emit", "delete", "save"),
        service.trace,
        "⚠️ Емитерът може да откаже фиш или да хвърли; изтрити преди него, записите на предишното"
            + " генериране изчезват заради провал, който изобщо не ги засяга.");
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  private static BgPaySlip regular(String personalId, String reference) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    slip.setReference(reference);
    slip.setPeriod(period());
    Partner partner = new Partner();
    partner.setRegistrationCode(personalId);
    Employee employee = new Employee();
    employee.setContactPartner(partner);
    EmploymentContract contract = new EmploymentContract();
    contract.setId(3L);
    contract.setEmployee(employee);
    slip.setEmploymentContract(contract);
    return slip;
  }

  /** Копието, както го оставя retroCopyOf: СЪЩИЯТ период, потвърдено, признак за корекция. */
  private static BgPaySlip correcting(String personalId, String reference) {
    BgPaySlip slip = regular(personalId, reference);
    slip.setBgIsCorrection(true);
    return slip;
  }

  private static Period period() {
    Period period = new Period();
    period.setId(55L);
    period.setName("12/2026");
    period.setFromDate(DEC_FROM);
    period.setToDate(DEC_TO);
    period.setStatusSelect(PeriodRepository.STATUS_CLOSED);
    return period;
  }

  /**
   * Услугата с подменени ЧЕТЦИ и ПИСАЧИ — редът на стъпките върви истински.
   *
   * <p>⚠️ Подменен е и {@code slipsFor}: заявката към базата е предмет на инсталацията, а
   * твърдението тук е за реда и за отказа. Че копието наистина се озовава в този набор, се дължи на
   * {@code period}-а, който {@code retroCopyOf} му дава — виж javadoc-а на класа.
   */
  private static final class Service extends BgD1GenerationService {

    private final List<BgPaySlip> slips;
    private String kind = BgD1DeclarationKind.REGULAR;

    /** Статусът от историята на подаванията — „НАП държи редовната“ или нищо. */
    private String history;

    private final List<String> trace = new ArrayList<>();
    private final List<Object> saved = new ArrayList<>();
    private boolean deleted;
    private boolean emitted;

    private Service(BgPaySlip... slips) {
      this.slips = Arrays.asList(slips);
    }

    @Override
    protected List<BgPaySlip> slipsFor(BgD1Declaration declaration) {
      return slips;
    }

    /**
     * ⚠️ ДВАТА ШЕВА СЕ ЗАТВАРЯТ ЗАЕДНО. Пазачът пита ДВЕ заявки — последното подаване и историята;
     * подменена само едната, другата тръгва към базата без Guice и целият свидетел пада с „Guice is
     * not initialized“ — провал, който сочи напълно грешно място. Тук подаване НЯМА.
     */
    @Override
    protected String latestSubmissionStatus(com.axelor.apps.base.db.Period period) {
      return null;
    }

    @Override
    protected String blockingHistoryStatus(com.axelor.apps.base.db.Period period) {
      return history;
    }

    @Override
    protected void deleteRecordsOf(Long declarationId) {
      deleted = true;
      trace.add("delete");
    }

    @Override
    public BgD1Emitter.Outcome generate(Long declarationId) {
      return super.generate(declarationId);
    }

    @Override
    protected BgD1Emitter emitter() {
      emitted = true;
      return new BgD1Emitter(null, null, null) {
        @Override
        public Outcome emit(BgD1Declaration declaration, List<BgPaySlip> slips) {
          trace.add("emit");
          return new Outcome();
        }
      };
    }

    @Override
    protected com.axelor.apps.bglocalization.db.repo.BgD1DeclarationRepository declarations() {
      return new com.axelor.apps.bglocalization.db.repo.BgD1DeclarationRepository() {
        @Override
        public BgD1Declaration find(Long id) {
          BgD1Declaration declaration = new BgD1Declaration();
          declaration.setId(id);
          declaration.setBgDataPeriod(period());
          declaration.setBgDeclarationKind(kind);
          // ⚠️ Коригиращата иска препратка към редовната — пазачът се проверява отделно в
          // BgD1DeclarationKindTest; тук тя се дава наготово, за да не спира реда на стъпките.
          if (BgD1DeclarationKind.CORRECTING.equals(kind)) {
            BgD1Declaration target = new BgD1Declaration();
            target.setBgDeclarationKind(BgD1DeclarationKind.REGULAR);
            target.setBgDataPeriod(declaration.getBgDataPeriod());
            target.setCompany(declaration.getCompany());
            declaration.setCorrectsPrevious(target);
          }
          return declaration;
        }

        @Override
        public BgD1Declaration save(BgD1Declaration entity) {
          trace.add("save");
          saved.add(entity);
          return entity;
        }
      };
    }

    @Override
    protected com.axelor.apps.bglocalization.db.repo.BgD1RecordRepository records() {
      return new com.axelor.apps.bglocalization.db.repo.BgD1RecordRepository();
    }
  }
}
