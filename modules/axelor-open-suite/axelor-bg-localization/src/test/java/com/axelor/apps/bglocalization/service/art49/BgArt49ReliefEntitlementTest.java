package com.axelor.apps.bglocalization.service.art49;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgEmployeeChild;
import com.axelor.apps.hr.db.Employee;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КОИ ОБЛЕКЧЕНИЯ СЕ ИЗВЕЖДАТ ОТ КАРТОНА — чл. 18, чл. 22в и чл. 22г, с ДАТИ.
 *
 * <p>Данъчната година навсякъде е 2026. Свидетелите менят по едно нещо: срока на решението, датата
 * на раждане, броя деца, заредеността на сумата.
 */
class BgArt49ReliefEntitlementTest {

  private static final int TAX_YEAR = 2026;

  /** Сумите, каквито биха били, ако са заредени — тестът мери МЕХАНИЗМА, не размера за годината. */
  private static BgArt49ReliefEntitlement.Amounts loaded() {
    BgArt49ReliefEntitlement.Amounts amounts = new BgArt49ReliefEntitlement.Amounts();
    amounts.disabilityAnnual = new BigDecimal("4049.43");
    amounts.childOne = new BigDecimal("102.26");
    amounts.childTwo = new BigDecimal("204.52");
    amounts.childThreePlus = new BigDecimal("306.78");
    amounts.childDisability = new BigDecimal("1022.58");
    return amounts;
  }

  // ═══ ЧЛ. 18 ══════════════════════════════════════════════════════════════

  @Test
  @DisplayName("СВИДЕТЕЛ · чл. 18 · решение без краен срок дава пълния годишен размер")
  void aDecisionWithoutAnEndDateGivesTheFullAnnualRelief() {
    BgArt49Reliefs reliefs = BgArt49ReliefEntitlement.derive(disabled(null), loaded(), TAX_YEAR);

    assertEquals(0, new BigDecimal("4049.43").compareTo(reliefs.disability), "колона 8");
  }

  /**
   * ⚠️ ГОДИНАТА НА ИЗТИЧАНЕ БРОИ ЦЯЛА. Чл. 18 ал. 1, дословно: облекчението се ползва „включително
   * за годината на настъпване на неработоспособността и за годината на изтичане срока на валидност
   * на решението“. Решение, изтекло на 3 февруари, дава ПЪЛНИЯ годишен размер, а не две дванайсети
   * и не нула. Мерено с „валидно ли е към 31 декември“, то щеше да даде нула точно за случая, който
   * нормата назовава изрично.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 18 АЛ. 1 · решение, изтекло през февруари, пак дава ПЪЛНИЯ годишен размер")
  void aDecisionExpiringDuringTheYearStillGivesTheWholeAnnualRelief() {
    BgArt49Reliefs reliefs =
        BgArt49ReliefEntitlement.derive(disabled("2026-02-03"), loaded(), TAX_YEAR);

    assertEquals(0, new BigDecimal("4049.43").compareTo(reliefs.disability), "колона 8");
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · чл. 18 · решение, изтекло ПРЕДНАТА година, не дава нищо")
  void aDecisionExpiredBeforeTheYearGivesNothing() {
    BgArt49Reliefs reliefs =
        BgArt49ReliefEntitlement.derive(disabled("2025-12-31"), loaded(), TAX_YEAR);

    assertEquals(0, BigDecimal.ZERO.compareTo(reliefs.disability), "колона 8");
    assertFalse(
        BgArt49ReliefEntitlement.hasDisabilityDecision(disabled("2025-12-31"), TAX_YEAR),
        "решението е изтекло преди данъчната година");
  }

  // ═══ ЧЛ. 22в ═════════════════════════════════════════════════════════════

  /**
   * ⚠️ СТЪПАЛО, А НЕ „ПО ТОЛКОВА НА ДЕТЕ“. Чл. 22в ал. 1 изброява три отделни суми — при едно, при
   * две и при три И ПОВЕЧЕ деца. Умножено по броя, семейство с пет деца би получило пет пъти сумата
   * за едно.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 22в · при пет деца сумата остава тази за „три и повече“")
  void theChildReliefIsAStepAndNotPerChild() {
    List<BgEmployeeChild> five =
        Arrays.asList(
            child("2015-05-05", false, null),
            child("2016-05-05", false, null),
            child("2017-05-05", false, null),
            child("2018-05-05", false, null),
            child("2019-05-05", false, null));

    assertEquals(5, BgArt49ReliefEntitlement.minorChildren(five, TAX_YEAR), "броят");
    assertEquals(
        0,
        new BigDecimal("306.78").compareTo(BgArt49ReliefEntitlement.childAmountFor(5, loaded())),
        "сумата за три и повече");
    assertEquals(
        0,
        new BigDecimal("102.26").compareTo(BgArt49ReliefEntitlement.childAmountFor(1, loaded())),
        "сумата за едно дете");
  }

  /**
   * ⚠️ ГОДИНАТА НА ПЪЛНОЛЕТИЕ БРОИ ЦЯЛА — ЧЛ. 22в АЛ. 7. „Данъчното облекчение се ползва
   * ВКЛЮЧИТЕЛНО за годината, в която детето е родено, навършило е пълнолетие или е починало.“ Дете,
   * навършило 18 на 3 януари 2026 г., брои за цялата 2026 г.; мерено „към 31 декември“, то щеше да
   * отпадне.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 22в АЛ. 7 · дете, навършило 18 през януари, брои за ЦЯЛАТА година")
  void aChildTurningEighteenInJanuaryStillCountsForTheWholeYear() {
    List<BgEmployeeChild> children = Collections.singletonList(child("2008-01-03", false, null));

    assertEquals(1, BgArt49ReliefEntitlement.minorChildren(children, TAX_YEAR), "чл. 22в ал. 7");
  }

  @Test
  @DisplayName("⚠️ ЧЛ. 22в АЛ. 7 · дете, родено на 28 декември, брои за цялата тази година")
  void aChildBornInDecemberCountsForTheWholeYear() {
    List<BgEmployeeChild> children = Collections.singletonList(child("2026-12-28", false, null));

    assertEquals(1, BgArt49ReliefEntitlement.minorChildren(children, TAX_YEAR), "чл. 22в ал. 7");
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · чл. 22в · дете без дата на раждане НЕ се брои")
  void aChildWithoutABirthDateIsNotCounted() {
    List<BgEmployeeChild> children = Collections.singletonList(child(null, false, null));

    assertEquals(
        0,
        BgArt49ReliefEntitlement.minorChildren(children, TAX_YEAR),
        "възрастта не се знае, а по чл. 22в ал. 9 лицето отговаря за неправомерно ползване");
  }

  // ═══ ЧЛ. 22г ═════════════════════════════════════════════════════════════

  /**
   * ⚠️ ЧЛ. 22г ИМА ДРУГА ВЪЗРАСТОВА ГРАНИЦА, И ТЯ ХАПЕ НА ЕДИН-ЕДИНСТВЕН ДЕН. Ал. 2 т. 2 иска
   * детето да не е навършило пълнолетие „към 1 ЯНУАРИ на данъчната година“, докато чл. 22в ал. 7
   * дава ЦЯЛАТА година на пълнолетието. За дете с рожден ден на 1 януари двете се разминават: то е
   * навършило 18 точно на измервателната дата на 22г, но годината му на пълнолетие е тази, тоест
   * 22в го брои.
   *
   * <p>⚠️ Тесен случай — и точно затова с витнес. Копирано условие между двата члена изглежда вярно
   * при всяка друга дата и пада само тук, тоест би минало през преглед.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 22г АЛ. 2 Т. 2 · дете с рожден ден на 1 януари брои за 22в, но НЕ за 22г")
  void theDisabledChildAgeLimitIsMeasuredAtFirstOfJanuary() {
    List<BgEmployeeChild> children =
        Collections.singletonList(child("2008-01-01", true, "2030-01-01"));

    assertEquals(
        1,
        BgArt49ReliefEntitlement.minorChildren(children, TAX_YEAR),
        "чл. 22в ал. 7 — годината на пълнолетието брои цяла");
    assertEquals(
        0,
        BgArt49ReliefEntitlement.disabledChildren(children, TAX_YEAR),
        "чл. 22г ал. 2 т. 2 — към 1 януари 2026 г. детето ВЕЧЕ е навършило пълнолетие");
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · чл. 22г · дете с рожден ден на 2 януари брои и за двата члена")
  void aDisabledChildBornOnTheSecondOfJanuaryCountsForBoth() {
    List<BgEmployeeChild> children =
        Collections.singletonList(child("2008-01-02", true, "2030-01-01"));

    assertEquals(1, BgArt49ReliefEntitlement.minorChildren(children, TAX_YEAR), "чл. 22в");
    assertEquals(
        1,
        BgArt49ReliefEntitlement.disabledChildren(children, TAX_YEAR),
        "към 1 януари е още на 17 — границата на 22г е включваща отгоре");
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · дете, навършило 18 ПРЕЗ МИНАЛАТА година, не брои за нито един член")
  void aChildWhoTurnedEighteenLastYearCountsForNeither() {
    List<BgEmployeeChild> children =
        Collections.singletonList(child("2007-06-15", true, "2030-01-01"));

    assertEquals(0, BgArt49ReliefEntitlement.minorChildren(children, TAX_YEAR), "чл. 22в");
    assertEquals(0, BgArt49ReliefEntitlement.disabledChildren(children, TAX_YEAR), "чл. 22г");
  }

  /**
   * ⚠️ ЧЛ. 22г Е НА ДЕТЕ, ЗА РАЗЛИКА ОТ ЧЛ. 22в. Ал. 1 не изброява брой, а дава едно число „за
   * отглеждане на дете с … увреждане“. Законодателят е знаел как се пише стъпало и го е написал в
   * 22в; в 22г не го е написал.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 22г · две деца с увреждания дават ДВЕ суми, а не стъпало")
  void theDisabledChildReliefIsPerChild() {
    Employee employee = employee();
    employee.setBgChildren(
        new ArrayList<>(
            Arrays.asList(
                child("2015-05-05", true, "2030-01-01"), child("2016-05-05", true, "2026-03-01"))));

    BgArt49Reliefs reliefs = BgArt49ReliefEntitlement.derive(employee, loaded(), TAX_YEAR);

    assertEquals(
        0,
        new BigDecimal("2045.16").compareTo(reliefs.disabledChildren),
        "2 x 1022,58 — колона 16: " + reliefs);
  }

  /**
   * ⚠️ ЛИПСВАЩ СРОК НА ТЕЛК НЕ СЕ ЧЕТЕ КАТО „ПОЖИЗНЕНО“. „Пожизнено решение“ и „никой не е попълнил
   * докога“ изглеждат ЕДНАКВО в базата — същият довод, с който {@code BgGarnishmentService} обявява
   * случая за неустановим. Прочетено като валидно, то би поискало облекчение, за което лицето
   * отговаря по чл. 22г ал. 8.
   */
  @Test
  @DisplayName("⚠️ ЧЛ. 22г · дете с увреждане БЕЗ срок на решението не се брои")
  void aDisabledChildWithoutADecisionEndDateIsNotCounted() {
    List<BgEmployeeChild> children = Collections.singletonList(child("2015-05-05", true, null));

    assertEquals(0, BgArt49ReliefEntitlement.disabledChildren(children, TAX_YEAR), "неустановимо");
  }

  // ═══ ЗАРЕДЕНОСТ НА СУМИТЕ ════════════════════════════════════════════════

  /**
   * ⚠️ НЕЗАРЕДЕНА СУМА Е {@code null}, А НЕ НУЛА. Двете дават еднакъв данък и напълно различна
   * поправка: нулата казва „лицето няма деца“, а {@code null} казва „не се знае с колко“. И само
   * второто позволява на повикващия да ЗАПАЗИ ръчно въведена стойност вместо да я затрие.
   */
  @Test
  @DisplayName("⚠️ НЕЗАРЕДЕНО ≠ НУЛА · без сума за годината изведеното е null, а не 0")
  void anUnloadedAmountYieldsNullAndNotZero() {
    Employee employee = employee();
    employee.setBgDisabilityRelief(true);
    employee.setBgChildren(
        new ArrayList<>(Collections.singletonList(child("2015-05-05", true, "2030-01-01"))));

    BgArt49Reliefs reliefs =
        BgArt49ReliefEntitlement.derive(employee, new BgArt49ReliefEntitlement.Amounts(), TAX_YEAR);

    assertNull(reliefs.disability, "колона 8 — сумата по чл. 18 ал. 1 не е заредена");
    assertNull(reliefs.children, "колона 15 — сумите по чл. 22в не са заредени");
    assertNull(reliefs.disabledChildren, "колона 16 — сумата по чл. 22г не е заредена");
  }

  @Test
  @DisplayName("СВИДЕТЕЛ · заредена сума и лице БЕЗ основание дават нула, а не null")
  void aLoadedAmountWithoutEntitlementYieldsZero() {
    BgArt49Reliefs reliefs = BgArt49ReliefEntitlement.derive(employee(), loaded(), TAX_YEAR);

    assertTrue(
        reliefs.disability != null && reliefs.disability.signum() == 0,
        "лицето няма решение на ТЕЛК — това е ОТГОВОР, а не липса на въпрос");
    assertTrue(
        reliefs.children != null && reliefs.children.signum() == 0, "лицето няма деца в картона");
  }

  // ═══ СРЕДАТА ══════════════════════════════════════════════════════════════

  private static Employee employee() {
    Employee employee = new Employee();
    employee.setBgChildren(new ArrayList<>());
    return employee;
  }

  private static Employee disabled(String validUntil) {
    Employee employee = employee();
    employee.setBgDisabilityRelief(true);
    employee.setBgDisabilityValidUntil(validUntil == null ? null : LocalDate.parse(validUntil));
    return employee;
  }

  private static BgEmployeeChild child(String born, boolean isDisabled, String disabilityValidTo) {
    BgEmployeeChild child = new BgEmployeeChild();
    child.setBirthDate(born == null ? null : LocalDate.parse(born));
    child.setIsDisabled(isDisabled);
    child.setDisabilityValidTo(
        disabilityValidTo == null ? null : LocalDate.parse(disabilityValidTo));
    return child;
  }
}
