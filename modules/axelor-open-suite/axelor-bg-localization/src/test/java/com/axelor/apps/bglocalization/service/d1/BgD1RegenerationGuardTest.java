package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ПОВТОРНОТО ГЕНЕРИРАНЕ НА РЕДОВНА ДЕКЛАРАЦИЯ — СПИСЪК НА ПОЗВОЛЕНОТО.
 *
 * <p><b>⚠️ ПОЗВОЛЕНИТЕ СЕ ИЗБРОЯВАТ, ЗАБРАНЕНИТЕ — НЕ.</b> Генерирането е ЗАМЯНА. Изброени ли бяха
 * забранените статуси, всеки НОВ статус в {@code nap.submission.status.select} щеше да е позволен
 * по подразбиране — тоест списъкът щеше да расте сам в посоката, в която греши по-скъпо. Пуска се
 * само липса на подаване, {@code rejected} и {@code technical_error}: там НАП не пази нищо.
 *
 * <p>Осемте статуса: {@code draft}, {@code submitting}, {@code submitted}, {@code
 * submission_outcome_unknown}, {@code accepted}, {@code rejected}, {@code correction_required},
 * {@code technical_error} (NapSubmission.xml:123-132). Всеки има свидетел тук, а срещу истинската
 * заявка — в {@code BgD1KindQueryDbTest}.
 *
 * <p>⚠️ НЕ Е КЛЮЧ (Б): {@code BgD1PeriodLockRules.LOCKING_STATUSES} заключва при четири статуса,
 * защото отговаря на „може ли месецът да се редактира по редовния път“. Друг въпрос, друг списък.
 */
class BgD1RegenerationGuardTest {

  // ═══ 1 · ПОЗВОЛЕНОТО ═════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ БЕЗ ПОДАВАНЕ · минава — няма какво да се презапише")
  void noSubmissionAtAllAllowsTheRegeneration() {
    assertNull(BgD1RegenerationRules.refuse("12/2026", null, null));
    assertNull(
        BgD1RegenerationRules.refuse("12/2026", "   ", null), "празният статус е същата липса");
    assertTrue(BgD1RegenerationRules.allows(null));
  }

  @ParameterizedTest(name = "{0} → минава")
  @ValueSource(strings = {"rejected", "technical_error"})
  @DisplayName("⚠️ ОТХВЪРЛЕНО и ТЕХНИЧЕСКА ГРЕШКА · минават — НАП не пази нищо")
  void theTwoAllowingStatusesPass(String status) {
    assertNull(
        BgD1RegenerationRules.refuse("12/2026", status, null),
        "⚠️ Отказ тук би спрял ЕДИНСТВЕНАТА поправка, която в тези два случая има смисъл: подаването"
            + " се е върнало, нищо не стои в НАП, а човекът иска нов файл от поправените фишове.");
  }

  @Test
  @DisplayName("Позволените са точно ДВА и са изброени поименно")
  void theAllowListIsExactlyTheTwoKnownStatuses() {
    assertEquals(
        Arrays.asList(
            NapSubmissionRepository.STATUS_REJECTED,
            NapSubmissionRepository.STATUS_TECHNICAL_ERROR),
        BgD1RegenerationRules.ALLOWING_STATUSES);
  }

  // ═══ 2 · ОТКАЗАНОТО · ШЕСТТЕ ОСТАНАЛИ ════════════════════════════════════

  @ParameterizedTest(name = "{0} → отказ")
  @ValueSource(
      strings = {
        "draft",
        "submitting",
        "submitted",
        "submission_outcome_unknown",
        "accepted",
        "correction_required"
      })
  @DisplayName("⚠️ Останалите ШЕСТ се отказват, и отказът НАЗОВАВА статуса")
  void theOtherSixStatusesRefuseAndNameTheStatus(String status) {
    String refusal = BgD1RegenerationRules.refuse("12/2026", status, null);

    assertNotNull(refusal, "статус „" + status + "“ не бива да минава през списък на позволеното");
    assertTrue(refusal.contains(status), "суровата стойност я няма в причината: " + refusal);
    assertTrue(
        refusal.contains(BgD1RegenerationRules.label(status)),
        "⚠️ И БЪЛГАРСКОТО ИМЕ. Причина, която казва само „submission_outcome_unknown“, праща човека"
            + " да търси какво значи това, вместо какво да направи: "
            + refusal);
    assertTrue(refusal.contains("12/2026"), "периодът е назован: " + refusal);
  }

  @Test
  @DisplayName("⚠️ ЧЕРНОВА също се отказва — и това е СЪЗНАТЕЛНА промяна")
  void aDraftSubmissionRefusesToo() {
    String refusal =
        BgD1RegenerationRules.refuse("12/2026", NapSubmissionRepository.STATUS_DRAFT, null);

    assertNotNull(
        refusal,
        "⚠️ Черновата не минава с довода „нищо не е тръгвало към НАП“. При списък на"
            + " ПОЗВОЛЕНОТО тя се отказва: редът съществува, а дали е тръгнал, се чете от статус,"
            + " който се мени ИЗВЪН това правило. Цената е видима и поправима — причината назовава"
            + " статуса, а черновата се изтрива или подава. Обратната грешка (мълчаливо"
            + " презаписване на подаден месец) не се вижда никъде.");
  }

  @Test
  @DisplayName("⚠️ НЕПОЗНАТ статус се отказва по подразбиране — списъкът не расте сам")
  void anUnknownStatusIsRefusedByDefault() {
    String refusal = BgD1RegenerationRules.refuse("12/2026", "sent_by_courier_pigeon", null);

    assertNotNull(
        refusal, "⚠️ Нов статус НЕ бива да става позволен, защото никой не го е забранил");
    assertTrue(refusal.contains("sent_by_courier_pigeon"), refusal);
    assertTrue(
        refusal.contains("ALLOWING_STATUSES"),
        "и причината казва КЪДЕ се добавя, ако наистина е безопасен: " + refusal);
    assertEquals("непознат статус", BgD1RegenerationRules.label("sent_by_courier_pigeon"));
  }

  @Test
  @DisplayName("⚠️ НЕИЗВЕСТЕН ИЗХОД · причината сочи СВЕРЯВАНЕТО, не корекцията")
  void theUnknownOutcomeRefusalPointsAtReconcilingFirst() {
    String refusal =
        BgD1RegenerationRules.refuse(
            "12/2026", NapSubmissionRepository.STATUS_OUTCOME_UNKNOWN, null);

    assertTrue(refusal.contains("сверете"), "⚠️ Първата стъпка е сверяване: " + refusal);
    assertTrue(refusal.contains("documentId"), "и по какво се сверява: " + refusal);
    assertTrue(
        refusal.contains("ПРИЕТО"),
        "⚠️ И ЗАЩО: подаване с неизвестен изход МОЖЕ да е прието. „Неизвестно“ не е „не е станало“ —"
            + " точно това разминаване прави повторното подаване на сляпо опасно: "
            + refusal);
  }

  @Test
  @DisplayName("⚠️ ПРИЕТА · причината сочи изхода — коригираща декларация")
  void theAcceptedRefusalPointsAtTheCorrectingDeclaration() {
    String refusal =
        BgD1RegenerationRules.refuse("12/2026", NapSubmissionRepository.STATUS_ACCEPTED, null);

    assertTrue(refusal.contains("Коригираща"), "отказът сочи изход, а не само забрана: " + refusal);
  }

  // ═══ 3 · ИСТОРИЯТА БИЕ ПОСЛЕДНОТО ═══════════════════════════════════════

  /**
   * ⚠️ „ПРИЕТО → ОТХВЪРЛЕНО“ НЕ МИНАВА. Правило, което чете само последното подаване, би го пуснало
   * — грешка в посоката, в която боли: отхвърленото ПО-КЪСНО подаване не изтрива от НАП онова,
   * което вече е прието. Затова историята се пита отделно и бие последното.
   */
  @ParameterizedTest(name = "в историята {0} → отказ дори при отхвърлено последно")
  @ValueSource(strings = {"accepted", "correction_required"})
  @DisplayName("⚠️ НЯКОГА ПРИЕТО (или изисквало корекция) · отказва независимо от последното")
  void aBlockingHistoryRefusesWhateverTheLatestSubmissionSays(String history) {
    String refusal =
        BgD1RegenerationRules.refuse("12/2026", NapSubmissionRepository.STATUS_REJECTED, history);

    assertNotNull(
        refusal,
        "⚠️ Последното подаване е ОТХВЪРЛЕНО, тоест списъкът на позволеното би пуснал. Историята"
            + " казва друго: веднъж приет, месецът е в НАП.");
    assertTrue(refusal.contains(history), "назовава статуса ОТ ИСТОРИЯТА: " + refusal);
    assertTrue(
        refusal.contains("Коригираща"),
        "⚠️ И сочи изхода. Отказ без изход превръща месеца в задънена улица: " + refusal);
    assertTrue(refusal.contains("ПАЗИ"), "и казва ЗАЩО: " + refusal);
  }

  @Test
  @DisplayName("⚠️ Историята НАДДЕЛЯВА и причината е нейната, не на последното подаване")
  void theHistoryReasonWinsOverTheLatestStatusReason() {
    String refusal =
        BgD1RegenerationRules.refuse(
            "12/2026",
            NapSubmissionRepository.STATUS_TECHNICAL_ERROR,
            NapSubmissionRepository.STATUS_ACCEPTED);

    assertTrue(refusal.contains("историята"), refusal);
    assertFalse(
        refusal.contains("technical_error"),
        "⚠️ Причина, която казва „последното подаване е техническа грешка“, праща човека да гледа"
            + " точно реда, който НЕ го спира: "
            + refusal);
  }

  @Test
  @DisplayName("⚠️ ОТХВЪРЛЕНО в историята НЕ блокира — там НАП не пази нищо")
  void aRejectedHistoryIsNotBlocking() {
    assertEquals(
        Arrays.asList(
            NapSubmissionRepository.STATUS_ACCEPTED,
            NapSubmissionRepository.STATUS_CORRECTION_REQUIRED),
        BgD1RegenerationRules.HISTORY_BLOCKING_STATUSES,
        "⚠️ Само двата, които значат „НАП ГО ИМА“. Отхвърленото и техническата грешка не влизат,"
            + " колкото и пъти да са се повторили — инак един неуспешен опит заключва месеца"
            + " завинаги.");
    assertNull(
        BgD1RegenerationRules.refuse("12/2026", null, NapSubmissionRepository.STATUS_REJECTED),
        "подадена невярно, отхвърлена стойност не бива да блокира");
  }

  @Test
  @DisplayName("⚠️ Историята стига до причината ПРЕЗ сглобката, не само през правилото")
  void thehistoryTravelsThroughTheComposition() {
    String refusal =
        new Service(
                NapSubmissionRepository.STATUS_REJECTED, NapSubmissionRepository.STATUS_ACCEPTED)
            .refuseRegularRegeneration(regular());

    assertNotNull(refusal, "⚠️ Двата шева са РАЗЛИЧНИ заявки; сглобката трябва да пита и двата");
    assertTrue(refusal.contains("accepted"), refusal);
  }

  // ═══ 4 · СГЛОБКАТА · ВИДЪТ РЕШАВА КОЙ СЕ ПИТА ════════════════════════════

  @Test
  @DisplayName("⚠️ КОРИГИРАЩАТА не се пита никога — тя съществува, защото редовната е подадена")
  void thecorrectingDeclarationIsNeverRefusedByThisGuard() {
    BgD1Declaration correcting = regular();
    correcting.setBgDeclarationKind(BgD1DeclarationKind.CORRECTING);

    assertNull(
        new Service(NapSubmissionRepository.STATUS_ACCEPTED).refuseRegularRegeneration(correcting),
        "⚠️ Отказ и за нея би затворил изхода, който самият отказ сочи — същата грешка като при"
            + " refuseMixedCarriers, само на друго място.");
  }

  @Test
  @DisplayName("⚠️ ЗАВАРЕНА декларация (NULL вид) се пази като РЕДОВНА")
  void alegacyDeclarationIsGuardedAsRegular() {
    BgD1Declaration legacy = regular();
    legacy.setBgDeclarationKind(null);

    assertNotNull(
        new Service(NapSubmissionRepository.STATUS_ACCEPTED).refuseRegularRegeneration(legacy),
        "⚠️ Прочетена като „нито едното“, заварената би се изплъзнала от пазача и би презаписала"
            + " подаден период мълчаливо. Прочитът „липса ⇒ редовна“ важи и тук.");
  }

  @Test
  @DisplayName("Без период няма какво да се пита")
  void withoutAPeriodThereIsNothingToAsk() {
    BgD1Declaration noPeriod = new BgD1Declaration();
    noPeriod.setBgDeclarationKind(BgD1DeclarationKind.REGULAR);
    assertNull(
        new Service(NapSubmissionRepository.STATUS_ACCEPTED).refuseRegularRegeneration(noPeriod));
  }

  @Test
  @DisplayName("⚠️ Периодът стига до причината ПРЕЗ сглобката, не само през правилото")
  void theperiodNameTravelsThroughTheComposition() {
    String refusal =
        new Service(NapSubmissionRepository.STATUS_SUBMITTED).refuseRegularRegeneration(regular());

    assertNotNull(refusal);
    assertTrue(refusal.contains("12/2026"), "името на периода идва от декларацията: " + refusal);
    assertTrue(refusal.contains("submitted"), refusal);
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  private static BgD1Declaration regular() {
    BgD1Declaration declaration = new BgD1Declaration();
    declaration.setBgDeclarationKind(BgD1DeclarationKind.REGULAR);
    Period period = new Period();
    period.setId(55L);
    period.setName("12/2026");
    declaration.setBgDataPeriod(period);
    return declaration;
  }

  /**
   * Услугата с подменен САМО прочита на подаването — правилото върви истинско.
   *
   * <p>⚠️ Че заявката наистина връща ПОСЛЕДНОТО подаване и че филтрира по услуга и вид документ, се
   * мери срещу база в {@code BgD1KindQueryDbTest}. Тук се мери сглобката.
   */
  private static final class Service extends BgD1GenerationService {
    private final String status;
    private final String history;

    private Service(String status) {
      this(status, null);
    }

    private Service(String status, String history) {
      this.status = status;
      this.history = history;
    }

    @Override
    protected String latestSubmissionStatus(Period period) {
      return period == null || period.getId() == null ? null : status;
    }

    @Override
    protected String blockingHistoryStatus(Period period) {
      return period == null || period.getId() == null ? null : history;
    }
  }

  /** Осемте статуса — държани тук само за да се види, че списъкът на позволеното е ПОДМНОЖЕСТВО. */
  static final List<String> ALL_STATUSES =
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
  @DisplayName("Позволените са ПОДМНОЖЕСТВО на осемте — никой позволен статус не е измислен")
  void theAllowListIsASubsetOfTheEight() {
    assertTrue(ALL_STATUSES.containsAll(BgD1RegenerationRules.ALLOWING_STATUSES));
    assertEquals(8, ALL_STATUSES.size(), "осем статуса — ако станат девет, този тест го казва");
  }
}
