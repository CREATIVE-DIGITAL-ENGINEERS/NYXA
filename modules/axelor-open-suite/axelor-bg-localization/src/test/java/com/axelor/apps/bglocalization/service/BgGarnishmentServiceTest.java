package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgEmployeeChild;
import com.axelor.apps.bglocalization.service.BgGarnishmentService.Status;
import com.axelor.apps.hr.db.Employee;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРИЗНАКЪТ „С ДЕЦА“ ПО ЧЛ. 446 — изведен от {@code Employee.bgChildren}, без ново поле.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate: услугата чете само подадения {@code Employee}.
 *
 * <p><b>Защо признакът си заслужава собствени тестове.</b> Той е БУЛЕВ вход на скалата и грешката
 * му не се вижда никъде: 1/4 вместо 1/3 върху нетото е сума, която изглежда точно като вярна.
 * Затова се проверява всяко от четирите положения поотделно, а не „общо взето работи“.
 *
 * <p>Основание: ГПК чл. 446 ал. 1 (ред. ДВ бр. 86/2017) — изразът „ако е с деца, които издържа“,
 * повторен и в трите точки на алинеята. Сверено срещу официалния ГПК (justice.government.bg) на
 * 22.08.2026 г.; дословният текст стои в javadoc-а на {@link BgGarnishmentService}.
 */
class BgGarnishmentServiceTest {

  /** Датата, към която се пита — начало на март 2026 г., както при фиш за периода. */
  private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);

  private final BgGarnishmentService service = new BgGarnishmentService();

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  private static BgEmployeeChild child(String name, LocalDate born) {
    BgEmployeeChild c = new BgEmployeeChild();
    c.setFullName(name);
    c.setBirthDate(born);
    return c;
  }

  private static BgEmployeeChild disabledChild(String name, LocalDate born, LocalDate telkUntil) {
    BgEmployeeChild c = child(name, born);
    c.setIsDisabled(true);
    c.setDisabilityValidTo(telkUntil);
    return c;
  }

  private static Employee employeeWith(BgEmployeeChild... children) {
    Employee e = new Employee();
    e.setBgChildren(new ArrayList<>(Arrays.asList(children)));
    return e;
  }

  // ═══ 1 · ЧЕТИРИТЕ ПОЛОЖЕНИЯ ═══════════════════════════════════════════════

  @Test
  @DisplayName("непълнолетно дете към датата → има деца на издръжка")
  void aMinorChildCounts() {
    Employee employee = employeeWith(child("Иван", LocalDate.of(2015, 6, 10)));

    assertTrue(service.hasMaintainedChildren(employee, MARCH));
    assertEquals(Status.WITH_CHILDREN, service.childrenStatus(employee, MARCH));
  }

  @Test
  @DisplayName("само пълнолетно дете, без увреждане → НЯМА деца на издръжка")
  void anAdultChildAloneDoesNotCount() {
    Employee employee = employeeWith(child("Петър", LocalDate.of(2000, 1, 20)));

    assertFalse(service.hasMaintainedChildren(employee, MARCH));
    assertEquals(Status.WITHOUT_CHILDREN, service.childrenStatus(employee, MARCH));
  }

  /**
   * ⚠️ ВТОРОТО ОСНОВАНИЕ, независимо от възрастта: пълнолетно дете с валиден ТЕЛК Е на издръжка.
   */
  @Test
  @DisplayName("⚠️ пълнолетно дете с ВАЛИДЕН ТЕЛК → има деца на издръжка")
  void anAdultChildWithAValidDisabilityDecisionCounts() {
    Employee employee =
        employeeWith(disabledChild("Мария", LocalDate.of(1999, 4, 2), LocalDate.of(2026, 12, 31)));

    assertTrue(service.hasMaintainedChildren(employee, MARCH));
    assertEquals(Status.WITH_CHILDREN, service.childrenStatus(employee, MARCH));
  }

  @Test
  @DisplayName("пълнолетно дете с ИЗТЕКЪЛ ТЕЛК → НЯМА деца на издръжка")
  void anExpiredDisabilityDecisionDoesNotCount() {
    Employee employee =
        employeeWith(disabledChild("Мария", LocalDate.of(1999, 4, 2), LocalDate.of(2025, 12, 31)));

    assertFalse(service.hasMaintainedChildren(employee, MARCH));
    assertEquals(Status.WITHOUT_CHILDREN, service.childrenStatus(employee, MARCH));
  }

  // ═══ 2 · ГРАНИЦИТЕ ════════════════════════════════════════════════════════

  /**
   * ⚠️ НА САМИЯ 18-И РОЖДЕН ДЕН ЛИЦЕТО ВЕЧЕ Е ПЪЛНОЛЕТНО. Ден по-рано още не е. Границата се
   * проверява от двете страни, защото сгрешена с един ден тя мълчи цял месец.
   */
  @Test
  @DisplayName("⚠️ границата на пълнолетието: денят преди 18-ия рожден ден брои, самият ден — не")
  void majorityStartsOnTheEighteenthBirthday() {
    LocalDate birthday = MARCH; // 18-и рожден ден точно на датата на питане
    Employee onTheDay = employeeWith(child("Ана", birthday.minusYears(18)));
    Employee dayBefore = employeeWith(child("Ана", birthday.minusYears(18).plusDays(1)));

    assertFalse(
        service.hasMaintainedChildren(onTheDay, MARCH), "на 18-ия рожден ден детето е пълнолетно");
    assertTrue(
        service.hasMaintainedChildren(dayBefore, MARCH),
        "ден преди 18-ия рожден ден още е непълнолетно");
  }

  /** ⚠️ ТЕЛК, валиден ТОЧНО до датата, още е валиден — границата е включваща. */
  @Test
  @DisplayName("⚠️ ТЕЛК с валидност точно до датата още брои; ден по-рано — вече не")
  void theDisabilityDecisionIsValidThroughItsLastDay() {
    Employee lastDay = employeeWith(disabledChild("Мария", LocalDate.of(1999, 4, 2), MARCH));
    Employee dayBefore =
        employeeWith(disabledChild("Мария", LocalDate.of(1999, 4, 2), MARCH.minusDays(1)));

    assertTrue(service.hasMaintainedChildren(lastDay, MARCH));
    assertFalse(service.hasMaintainedChildren(dayBefore, MARCH));
  }

  /**
   * ⚠️ ДАТАТА МЕНИ ОТГОВОРА, и точно затова е задължителен вход. Едно и също дете дава „да“ през
   * март и „не“ през септември.
   */
  @Test
  @DisplayName("⚠️ същото дете: „да“ за март, „не“ за септември — отговорът е на ДАТАТА")
  void theAnswerMovesWithTheDate() {
    Employee employee = employeeWith(child("Ана", LocalDate.of(2008, 6, 15)));

    assertTrue(service.hasMaintainedChildren(employee, MARCH), "през март още е на 17");
    assertFalse(
        service.hasMaintainedChildren(employee, LocalDate.of(2026, 9, 1)),
        "през септември вече е навършило 18");
  }

  // ═══ 3 · ПОЛОЖИТЕЛНИЯТ ОТГОВОР ПОБЕЖДАВА ══════════════════════════════════

  @Test
  @DisplayName("едно непълнолетно до едно пълнолетно → има деца на издръжка")
  void oneMaintainedChildIsEnough() {
    Employee employee =
        employeeWith(
            child("Петър", LocalDate.of(2000, 1, 20)), child("Ана", LocalDate.of(2015, 2, 2)));

    assertEquals(Status.WITH_CHILDREN, service.childrenStatus(employee, MARCH));
  }

  /**
   * ⚠️ УСТАНОВЕНОТО „ДА“ ПОБЕЖДАВА НЕПОПЪЛНЕНОТО. Дете без дата на раждане до дете, което вече е
   * установено като на издръжка, НЕ прави отговора неустановим — той вече е даден.
   */
  @Test
  @DisplayName("⚠️ непопълнено дете до установено непълнолетно → пак „да“, не отказ")
  void anUnknownChildDoesNotSpoilAnAlreadyPositiveAnswer() {
    Employee employee =
        employeeWith(child("Без данни", null), child("Ана", LocalDate.of(2015, 2, 2)));

    assertEquals(Status.WITH_CHILDREN, service.childrenStatus(employee, MARCH));
  }

  // ═══ 4 · НЕУСТАНОВИМОТО НЕ Е „БЕЗ ДЕЦА“ ═══════════════════════════════════

  /**
   * ⚠️ ЛИПСВАЩАТА ДАТА НА РАЖДАНЕ Е ОТКАЗ, НЕ „НЯМА ДЕЦА“. Прочетена като „не“, тя удържа 1/3
   * вместо 1/4 от родител, чиито данни просто не са попълнени — тоест взима пари по непопълнено
   * поле.
   */
  @Test
  @DisplayName("⚠️ дете без дата на раждане и без ТЕЛК → НЕУСТАНОВИМО, не „без деца“")
  void aChildWithoutABirthDateIsUndeterminable() {
    Employee employee = employeeWith(child("Без данни", null));

    assertEquals(Status.UNDETERMINABLE, service.childrenStatus(employee, MARCH));
    assertFalse(
        service.hasMaintainedChildren(employee, MARCH),
        "булевият вход дава false — затова пазачът пита childrenStatus, а не него");
  }

  /**
   * ⚠️ „ПОЖИЗНЕН ТЕЛК“ И „НИКОЙ НЕ Е ПОПЪЛНИЛ ДОКОГА“ ИЗГЛЕЖДАТ ЕДНАКВО. Затова отбелязано
   * увреждане без срок се чете като неустановимо, а не като изтекло.
   */
  @Test
  @DisplayName("⚠️ дете с увреждане, но без срок на ТЕЛК → НЕУСТАНОВИМО")
  void aDisabledChildWithoutAValidityDateIsUndeterminable() {
    Employee employee = employeeWith(disabledChild("Мария", LocalDate.of(1999, 4, 2), null));

    assertEquals(Status.UNDETERMINABLE, service.childrenStatus(employee, MARCH));
  }

  /**
   * ⚠️ ПРАЗНИЯТ СПИСЪК Е ОТГОВОР, а липсващият служител — не. Първото е попълнен картон без деца;
   * второто е дефект в данните. Слети, пазачът щеше да спира всеки запор на бездетен длъжник.
   */
  @Test
  @DisplayName("⚠️ празен списък → „без деца“; липсващ служител → НЕУСТАНОВИМО")
  void anEmptyListAnswersButAMissingEmployeeDoesNot() {
    Employee childless = new Employee();
    childless.setBgChildren(new ArrayList<>());

    assertEquals(Status.WITHOUT_CHILDREN, service.childrenStatus(childless, MARCH));
    assertEquals(Status.WITHOUT_CHILDREN, service.childrenStatus(new Employee(), MARCH));
    assertEquals(Status.UNDETERMINABLE, service.childrenStatus(null, MARCH));
    assertEquals(
        Status.UNDETERMINABLE,
        service.childrenStatus(employeeWith(child("Ана", LocalDate.of(2015, 2, 2))), null),
        "без дата въпросът няма смисъл — отговорът се мени с нея");
  }

  /** ⚠️ Броят НЕ степенува: чл. 446 не различава едно дете от четири. */
  @Test
  @DisplayName("⚠️ четири деца дават същия отговор като едно — признакът е булев")
  void theCountDoesNotMatter() {
    List<BgEmployeeChild> four = new ArrayList<>();
    for (int i = 0; i < 4; i++) {
      four.add(child("Дете " + i, LocalDate.of(2015, 1, 1)));
    }
    Employee many = new Employee();
    many.setBgChildren(four);
    Employee one = employeeWith(child("Ана", LocalDate.of(2015, 1, 1)));

    assertEquals(
        service.childrenStatus(one, MARCH),
        service.childrenStatus(many, MARCH),
        "⚠️ различен отговор тук би значел, че някъде брои — а чл. 446 не брои");
    assertEquals(Status.WITH_CHILDREN, service.childrenStatus(many, MARCH));
  }

  /** Списък с {@code null} елемент е дефект в данните, но не бива да хвърля. */
  @Test
  @DisplayName("null елемент в списъка се подминава, вместо да хвърли")
  void aNullEntryIsSkipped() {
    Employee employee = new Employee();
    employee.setBgChildren(new ArrayList<>(Collections.singletonList(null)));

    assertEquals(Status.WITHOUT_CHILDREN, service.childrenStatus(employee, MARCH));
  }
}
