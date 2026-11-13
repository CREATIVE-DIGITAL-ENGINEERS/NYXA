package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.constraints.NotNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВИДЪТ НА ДЕКЛАРАЦИЯТА — четенето на липсата и трите отказа около препратката.
 *
 * <p>Чисти функции над заредени обекти: без база, без Guice. Заявката, която този вид управлява, се
 * проверява срещу ИСТИНСКА база в {@code BgD1KindQueryDbTest}.
 */
class BgD1DeclarationKindTest {

  // ═══ ЛИПСАТА СЕ ЧЕТЕ КАТО „РЕДОВНА“ ══════════════════════════════════════

  @Test
  @DisplayName("⚠️ ЗАВАРЕНА декларация с NULL вид се чете като РЕДОВНА — това е цялата миграция")
  void alegacyDeclarationWithoutAKindReadsAsRegular() {
    // ⚠️ ЗАВАРЕНИЯТ РЕД СЕ ПРОИЗВЕЖДА ИЗРИЧНО, И ТОВА Е ИЗМЕРЕН ФАКТ, НЕ ПРЕДПАЗЛИВОСТ.
    // Подразбирането „REGULAR“ живее в ИНИЦИАЛИЗАТОРА НА ПОЛЕТО на генерирания клас
    // (private String bgDeclarationKind = "REGULAR"), а НЕ в getter-а — той връща полето както е.
    // Тоест всеки НОВ обект носи „REGULAR“ от само себе си, но ред, записан ПРЕДИ колоната да
    // съществува, носи NULL в базата и се зарежда с NULL. Точно този ред се пресъздава тук.
    BgD1Declaration legacy = new BgD1Declaration();
    legacy.setBgDeclarationKind(null);
    assertNull(legacy.getBgDeclarationKind(), "котвата: заварен ред, зареден с NULL от базата");

    assertEquals(
        BgD1DeclarationKind.REGULAR,
        BgD1DeclarationKind.of(legacy),
        "⚠️ Прочетена наивно, заварената декларация би излязла от ВСЕКИ филтър по вид и би спряла"
            + " да носи фишове мълчаливо. Backfill няма и не трябва: всяка заварена Е БИЛА редовна"
            + " по определение — друга не е могла да се направи.");
    assertTrue(!BgD1DeclarationKind.isCorrecting(legacy));
  }

  @Test
  @DisplayName("⚠️ И празният низ е липса, не трети вид")
  void ablankKindAlsoReadsAsRegular() {
    BgD1Declaration blank = new BgD1Declaration();
    blank.setBgDeclarationKind("   ");
    assertEquals(
        BgD1DeclarationKind.REGULAR,
        BgD1DeclarationKind.of(blank),
        "required=\"true\" на колоната не пази от низ от интервали, дошъл при внос");
  }

  @Test
  @DisplayName("Изрично зададеният вид се чете какъвто е")
  void anexplicitKindIsRead() {
    BgD1Declaration correcting =
        declaration(BgD1DeclarationKind.CORRECTING, period("12/2026"), company("Фирма"));
    assertEquals(BgD1DeclarationKind.CORRECTING, BgD1DeclarationKind.of(correcting));
    assertTrue(BgD1DeclarationKind.isCorrecting(correcting));
  }

  // ═══ КОРИГИРАЩАТА ЧАКА ПРИЕТАТА РЕДОВНА ═════════════════════════════════

  @Test
  @DisplayName("⚠️ КОРИГИРАЩА без прието подаване → отказ, сочи „генерирайте наново РЕДОВНАТА“")
  void acorrectingDeclarationWaitsForTheAcceptedRegular() {
    BgD1Declaration correcting =
        declaration(BgD1DeclarationKind.CORRECTING, period("09/2026"), company("Фирма"));

    for (String notHeld :
        new String[] {
          null,
          NapSubmissionRepository.STATUS_DRAFT,
          NapSubmissionRepository.STATUS_SUBMITTED,
          NapSubmissionRepository.STATUS_REJECTED
        }) {
      String refusal = BgD1DeclarationKind.refuseCorrectingBeforeAccepted(correcting, notHeld);
      assertNotNull(refusal, "статус „" + notHeld + "“ — НАП не държи редовната");
      assertTrue(refusal.contains("09/2026"), refusal);
      assertTrue(refusal.contains("генерирайте наново РЕДОВНАТА"), "сочи изхода: " + refusal);
      assertTrue(refusal.contains("чл. 9 ал. 4"), refusal);
    }
  }

  @Test
  @DisplayName("⚠️ приета (или „изисква корекция“) в историята → коригиращата минава")
  void acorrectingDeclarationPassesWhenNapHoldsTheRegular() {
    BgD1Declaration correcting =
        declaration(BgD1DeclarationKind.CORRECTING, period("09/2026"), company("Фирма"));
    for (String held : BgD1RegenerationRules.HISTORY_BLOCKING_STATUSES) {
      assertNull(
          BgD1DeclarationKind.refuseCorrectingBeforeAccepted(correcting, held),
          "⚠️ двете врати се допълват: статус „"
              + held
              + "“ спира редовната и точно затова пуска коригиращата");
    }
  }

  @Test
  @DisplayName("РЕДОВНАТА не се пита от този отказ — нейната врата е BgD1RegenerationRules")
  void theRegularIsNotAskedByTheCorrectingRefusal() {
    BgD1Declaration regular =
        declaration(BgD1DeclarationKind.REGULAR, period("09/2026"), company("Фирма"));
    assertNull(BgD1DeclarationKind.refuseCorrectingBeforeAccepted(regular, null));
  }

  // ═══ ОТКАЗИТЕ ОКОЛО ПРЕПРАТКАТА ══════════════════════════════════════════

  @Test
  @DisplayName("⚠️ КОРИГИРАЩА без посочена редовна → отказ (чл. 9 ал. 4)")
  void acorrectingDeclarationMustNameTheOneItFixes() {
    BgD1Declaration correcting =
        declaration(BgD1DeclarationKind.CORRECTING, period("12/2026"), company("Фирма"));
    String refusal = BgD1DeclarationKind.refuseCorrectingTarget(correcting);
    assertNotNull(refusal);
    assertTrue(refusal.contains("Коригира декларация"), refusal);
    assertTrue(refusal.contains("чл. 9 ал. 4"), "причината сочи нормата: " + refusal);
  }

  @Test
  @DisplayName("⚠️ КОРИГИРАЩА, сочеща ДРУГА коригираща → отказ; коригира се РЕДОВНАТА")
  void achainOfCorrectionsIsRefused() {
    BgD1Declaration first =
        declaration(BgD1DeclarationKind.CORRECTING, period("12/2026"), company("Фирма"));
    BgD1Declaration second =
        declaration(BgD1DeclarationKind.CORRECTING, period("12/2026"), company("Фирма"));
    second.setCorrectsPrevious(first);

    String refusal = BgD1DeclarationKind.refuseCorrectingTarget(second);
    assertNotNull(refusal);
    assertTrue(
        refusal.contains("ДРУГА КОРИГИРАЩА"),
        "⚠️ Верига от корекции, всяка сочеща предишната, не казва кой е първоначалният запис: "
            + refusal);
  }

  @Test
  @DisplayName("⚠️ Разминат ПЕРИОД → отказ, който показва двата")
  void adifferentPeriodIsRefused() {
    BgD1Declaration correcting =
        declaration(BgD1DeclarationKind.CORRECTING, period("12/2026"), company("Фирма"));
    correcting.setCorrectsPrevious(
        declaration(BgD1DeclarationKind.REGULAR, period("11/2026"), company("Фирма")));

    String refusal = BgD1DeclarationKind.refuseCorrectingTarget(correcting);
    assertNotNull(refusal);
    assertTrue(refusal.contains("12/2026") && refusal.contains("11/2026"), refusal);
    assertTrue(refusal.contains("чл. 9 ал. 1 т. 1"), "и нормата: " + refusal);
  }

  @Test
  @DisplayName("⚠️ Разминато ДРУЖЕСТВО → отказ; по него НАП намира предишната декларация")
  void adifferentCompanyIsRefused() {
    BgD1Declaration correcting =
        declaration(BgD1DeclarationKind.CORRECTING, period("12/2026"), company("Фирма А"));
    correcting.setCorrectsPrevious(
        declaration(BgD1DeclarationKind.REGULAR, period("12/2026"), company("Фирма Б")));

    String refusal = BgD1DeclarationKind.refuseCorrectingTarget(correcting);
    assertNotNull(refusal);
    assertTrue(refusal.contains("Фирма А") && refusal.contains("Фирма Б"), refusal);
  }

  @Test
  @DisplayName("Съвпадащи период и дружество → минава")
  void amatchingTargetPasses() {
    BgD1Declaration correcting =
        declaration(BgD1DeclarationKind.CORRECTING, period("12/2026"), company("Фирма"));
    correcting.setCorrectsPrevious(
        declaration(BgD1DeclarationKind.REGULAR, period("12/2026"), company("Фирма")));
    assertNull(BgD1DeclarationKind.refuseCorrectingTarget(correcting));
  }

  @Test
  @DisplayName("⚠️ РЕДОВНАТА не се пита за препратка — тя не поправя нищо")
  void aregularDeclarationIsNotAskedForATarget() {
    assertNull(
        BgD1DeclarationKind.refuseCorrectingTarget(
            declaration(BgD1DeclarationKind.REGULAR, period("12/2026"), company("Фирма"))));
    assertNull(
        BgD1DeclarationKind.refuseCorrectingTarget(new BgD1Declaration()),
        "и заварената (NULL вид) също не се пита — тя се чете като редовна");
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  private static BgD1Declaration declaration(String kind, Period period, Company company) {
    BgD1Declaration declaration = new BgD1Declaration();
    declaration.setBgDeclarationKind(kind);
    declaration.setBgDataPeriod(period);
    declaration.setCompany(company);
    return declaration;
  }

  private static Period period(String name) {
    Period period = new Period();
    period.setName(name);
    return period;
  }

  private static Company company(String name) {
    Company company = new Company();
    company.setName(name);
    return company;
  }

  // ═══ БИЙН-ВАЛИДАЦИЯТА · ВЪПРОСЪТ, НА КОЙТО DB ТЕСТЪТ НЕ МОЖЕ ДА ОТГОВОРИ ══

  /**
   * ⚠️ ЗАВАРЕНИЯТ РЕД ТРЯБВА ДА МИНАВА ВАЛИДАТОРА, А НЕ САМО КОЛОНАТА.
   *
   * <p><b>Защо не в интеграционния тест.</b> {@code src/test/resources/META-INF/persistence.xml:17}
   * изключва бийн-валидацията изрично ({@code javax.persistence.validation.mode=none}) — „само DB
   * NOT NULL има значение за тези тестове“. Тоест там всеки {@code @NotNull} мълчи и зелен запис на
   * заварен ред НЕ доказва нищо за продукцията. В продукцията валидаторът Е включен: {@code
   * hibernate-validator} е зависимост на платформата ({@code
   * axelor-open-platform/gradle/libs.gradle:99}), а REST слоят лови {@code
   * ConstraintViolationException} ({@code ResponseInterceptor}).
   *
   * <p>Затова въпросът се задава на САМИЯ валидатор, върху обекта: заварен ред (вид = NULL) не бива
   * да има нито едно нарушение по {@code bgDeclarationKind}. С {@code required="true"} в домейна
   * този тест пада — и би паднал всеки запис на заварена декларация.
   */
  @Test
  @DisplayName("⚠️ ЗАВАРЕНА декларация (вид = NULL) минава БИЙН-ВАЛИДАЦИЯТА")
  void alegacyDeclarationHasNoConstraintViolationOnTheKind() throws NoSuchFieldException {
    BgD1Declaration legacy = new BgD1Declaration();
    legacy.setBgDeclarationKind(null);

    Set<ConstraintViolation<BgD1Declaration>> violations =
        Validation.buildDefaultValidatorFactory().getValidator().validate(legacy);

    List<String> onTheKind = new ArrayList<>();
    for (ConstraintViolation<BgD1Declaration> violation : violations) {
      if (violation.getPropertyPath().toString().equals("bgDeclarationKind")) {
        onTheKind.add(violation.getMessage());
      }
    }
    assertNull(
        BgD1Declaration.class.getDeclaredField("bgDeclarationKind").getAnnotation(NotNull.class),
        "⚠️ И ПРИЧИНАТА, не само следствието: @NotNull върху полето идва ПРАВО от required= в"
            + " домейна. Върне ли се там, този ред го казва на езика на генератора.");
    assertEquals(
        Collections.emptyList(),
        onTheKind,
        "⚠️ required в ДОМЕЙНА се превежда в @NotNull и вали ВСЕКИ запис на заварен ред — смяна на"
            + " статус, закачане на входящ номер, дори бутонът „Генерирай“, чийто onClick започва"
            + " със save. Задължителността принадлежи на НОВИЯ запис и стои в изгледа"
            + " (requiredIf=\"id == null\").");
  }

  @Test
  @DisplayName("⚠️ НОВИЯТ обект обаче се ражда РЕДОВЕН — това е „вид в кода при създаване“")
  void afreshDeclarationIsBornRegular() {
    assertEquals(
        BgD1DeclarationKind.REGULAR,
        new BgD1Declaration().getBgDeclarationKind(),
        "⚠️ default= в домейна е ИНИЦИАЛИЗАТОР НА ПОЛЕТО на генерирания клас, не стойност по"
            + " подразбиране на getter-а. Затова нов обект носи вида БЕЗ никой да го е задавал, а"
            + " заварен ред се зарежда с NULL — двете са различни неща и точно затова махането на"
            + " required не оставя нови декларации без вид.");
  }
}
