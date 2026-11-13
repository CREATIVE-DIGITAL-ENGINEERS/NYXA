package com.axelor.apps.bglocalization.service.selfinsured;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРАВИЛАТА НА САМООСИГУРЯВАЩОТО СЕ ЛИЦЕ — без база, без Guice и без Hibernate.
 *
 * <p>Всичко тук е чиста функция върху вече сглобени обекти, точно както при {@code
 * BgD1InsuredTypeRulesTest}. Твърденията са за ДВЕТЕ неща, които правилата решават: кой носи фиша и
 * може ли регистрацията изобщо да роди фиш.
 */
class BgSelfInsuredRulesTest {

  private static BgSelfInsuredRegistration valid() {
    Employee employee = new Employee();
    employee.setId(7L);
    employee.setBgIsSelfInsured(true);

    BgSelfInsuredRegistration reg = new BgSelfInsuredRegistration();
    reg.setEmployee(employee);
    reg.setBgIsSelfInsured(true);
    reg.setBgInsuredTypeCode("12");
    reg.setBgActivityGround("ART4_AL3_T1");
    reg.setBgObligedPersonCode("1234567890");
    reg.setBgSolScope(BgSelfInsuredRules.SCOPE_ALL_RISKS);
    reg.setBgScopeYear(2026);
    reg.setBgSelectedIncome(new BigDecimal("1000.00"));
    return reg;
  }

  // ═══ КОЙ НОСИ ФИША ════════════════════════════════════════════════════════

  @Test
  @DisplayName("НОСИТЕЛ · само регистрация → приема се и фишът е на самоосигуряващо се лице")
  void aRegistrationOnlySlipIsSelfInsuredAndAccepted() {
    BgPaySlip slip = new BgPaySlip();
    slip.setBgSelfInsuredRegistration(valid());

    assertTrue(BgSelfInsuredRules.isSelfInsured(slip));
    assertNull(BgSelfInsuredRules.refuseCarrier(slip));
  }

  @Test
  @DisplayName("НОСИТЕЛ · само договор → приема се и фишът НЕ е на самоосигуряващо се лице")
  void aContractOnlySlipIsNotSelfInsuredAndAccepted() {
    BgPaySlip slip = new BgPaySlip();
    slip.setEmploymentContract(new EmploymentContract());

    assertEquals(false, BgSelfInsuredRules.isSelfInsured(slip));
    assertNull(BgSelfInsuredRules.refuseCarrier(slip));
  }

  /**
   * ⚠️ СЛУЧАЯТ, КОЙТО {@code required="true"} НИКОГА НЕ БИ ХВАНАЛ.
   *
   * <p>Изискване в колоната на домейна пита само „има ли договор“. Фиш И с договор, И с регистрация
   * минава през него без нито едно възражение — а той твърди ДВЕ различни правоотношения наведнъж:
   * вноските му биха се сметнали по единия носител и биха се декларирали по другия, при това с
   * числа, които изглеждат напълно нормални.
   */
  @Test
  @DisplayName("⚠️ НОСИТЕЛ · И договор, И регистрация → ОТКАЗ, който required никога не би дал")
  void bothCarriersAtOnceAreRefused() {
    BgPaySlip slip = new BgPaySlip();
    slip.setEmploymentContract(new EmploymentContract());
    slip.setBgSelfInsuredRegistration(valid());

    String refusal = BgSelfInsuredRules.refuseCarrier(slip);
    assertNotNull(refusal, "фиш с ДВА носителя трябва да бъде отказан");
    assertTrue(refusal.contains("И трудов договор, И регистрация"), refusal);
    assertTrue(refusal.contains("чл. 6 ал. 8"), "отказът трябва да назове нормата: " + refusal);
  }

  @Test
  @DisplayName("НОСИТЕЛ · нито договор, нито регистрация → ОТКАЗ с ДРУГ текст")
  void noCarrierAtAllIsRefusedWithADifferentReason() {
    String refusal = BgSelfInsuredRules.refuseCarrier(new BgPaySlip());
    assertNotNull(refusal);
    assertTrue(refusal.contains("нито трудов договор, нито регистрация"), refusal);
    // ⚠️ ДВАТА ОТКАЗА СА РАЗЛИЧНИ ТЕКСТОВЕ, а не един общ: първият се поправя с ИЗТРИВАНЕ на едната
    // препратка, вторият — с ПОПЪЛВАНЕ на едната. Общ текст би пратил и двамата на грешното място.
    BgPaySlip both = new BgPaySlip();
    both.setEmploymentContract(new EmploymentContract());
    both.setBgSelfInsuredRegistration(valid());
    assertTrue(
        !refusal.equals(BgSelfInsuredRules.refuseCarrier(both)),
        "двата грешни случая искат РАЗЛИЧНА поправка и затова искат различен текст");
  }

  /**
   * ⚠️ ТЕСНИЯТ ВЪПРОС МЪЛЧИ ЗА „НИТО ЕДНО“, И ТОВА Е САМОТО МУ ПРЕДНАЗНАЧЕНИЕ.
   *
   * <p>{@code confirm()} го пита вместо широкия, защото случаят „нито един носител“ вече има кой да
   * го хване — {@code contributions(...)} го връща като {@code INPUT_NOT_ASSEMBLED}. Питан и на
   * двете места, той изпреварва заварените пазачи и сменя съобщението за фиш, чийто дефект е съвсем
   * друг: седемнайсет заварени теста на потвърждаването строят фиш БЕЗ договор, за да проверят нещо
   * трето.
   */
  @Test
  @DisplayName("⚠️ НОСИТЕЛ · тесният въпрос хваща САМО двойния носител, не и празния фиш")
  void theNarrowQuestionOnlyCatchesTheAmbiguousCase() {
    BgPaySlip both = new BgPaySlip();
    both.setEmploymentContract(new EmploymentContract());
    both.setBgSelfInsuredRegistration(valid());
    assertNotNull(BgSelfInsuredRules.refuseAmbiguousCarrier(both));
    assertEquals(
        BgSelfInsuredRules.refuseCarrier(both),
        BgSelfInsuredRules.refuseAmbiguousCarrier(both),
        "при двойния носител двата въпроса дават ЕДИН И СЪЩ текст — иначе са два отговора за един дефект");

    // ⚠️ И трите останали случая МЪЛЧАТ — включително празният фиш, който широкият въпрос отказва.
    assertNull(BgSelfInsuredRules.refuseAmbiguousCarrier(new BgPaySlip()));
    assertNotNull(
        BgSelfInsuredRules.refuseCarrier(new BgPaySlip()),
        "широкият въпрос ГО отказва — покритието не се губи, само се мести");

    BgPaySlip contractOnly = new BgPaySlip();
    contractOnly.setEmploymentContract(new EmploymentContract());
    assertNull(BgSelfInsuredRules.refuseAmbiguousCarrier(contractOnly));

    BgPaySlip registrationOnly = new BgPaySlip();
    registrationOnly.setBgSelfInsuredRegistration(valid());
    assertNull(BgSelfInsuredRules.refuseAmbiguousCarrier(registrationOnly));
    assertNull(BgSelfInsuredRules.refuseAmbiguousCarrier(null));
  }

  // ═══ ВАЛИДНА ЛИ Е РЕГИСТРАЦИЯТА ═══════════════════════════════════════════

  @Test
  @DisplayName("РЕГИСТРАЦИЯ · пълна и действаща → без отказ")
  void aCompleteActiveRegistrationIsAccepted() {
    assertNull(BgSelfInsuredRules.refuseRegistration(valid()));
  }

  @Test
  @DisplayName("РЕГИСТРАЦИЯ · недействаща → ОТКАЗ (остава видима за одит, но не ражда фиш)")
  void anInactiveRegistrationIsRefused() {
    BgSelfInsuredRegistration reg = valid();
    reg.setBgIsSelfInsured(false);
    String refusal = BgSelfInsuredRules.refuseRegistration(reg);
    assertNotNull(refusal);
    assertTrue(refusal.contains("действаща"), refusal);
  }

  /**
   * ⚠️ ANY И ART4A_AL2 СА СТОЙНОСТИ НА РЕДА В КАТАЛОГА, НЕ НА ЛИЦЕТО.
   *
   * <p>Двете стоят в {@code bg.insurance.sol.scope.select}, защото описват кога един ред със ставки
   * се прилага. Върху регистрация ANY би значел „обхватът не е решен“, а неупражненият избор по чл.
   * 4 ал. 4 значи „не е избрал“ — две различни неща с една стойност. ART4A_AL2 пък е заета ос за
   * морското лице, което по чл. 4а ал. 7 КСО ИМА работодател.
   */
  @Test
  @DisplayName(
      "⚠️ РЕГИСТРАЦИЯ · ANY и ART4A_AL2 се ОТКАЗВАТ — те са стойности на реда, не на лицето")
  void catalogueOnlyScopesAreRefusedOnARegistration() {
    for (String scope : new String[] {"ANY", "ART4A_AL2", "", "WHATEVER"}) {
      BgSelfInsuredRegistration reg = valid();
      reg.setBgSolScope(scope);
      String refusal = BgSelfInsuredRules.refuseRegistration(reg);
      assertNotNull(refusal, "обхват „" + scope + "“ трябва да бъде отказан");
      assertTrue(refusal.contains("обхватът на осигуряването"), refusal);
    }
    assertEquals(2, BgSelfInsuredRules.SCOPES.size(), "допустимите обхвати са точно два");
  }

  @Test
  @DisplayName("РЕГИСТРАЦИЯ · година нула → ОТКАЗ, защото изборът за ОЗМ е ГОДИШЕН")
  void aScopeWithoutAYearIsRefused() {
    BgSelfInsuredRegistration reg = valid();
    reg.setBgScopeYear(0);
    String refusal = BgSelfInsuredRules.refuseRegistration(reg);
    assertNotNull(refusal);
    assertTrue(refusal.contains("чл. 4 ал. 4"), refusal);
  }

  /**
   * ⚠️ {@code signum()}, НЕ {@code == null} — и точно това прави теста нужен.
   *
   * <p>Генерираният getter за decimal връща {@code BigDecimal.ZERO} за непопълнена колона, тоест
   * „още не е избран доход“ пристига като „избран е нула“. Проверка за {@code null} тук би била
   * МЪРТЪВ КОД и нулата щеше да мине нататък, където подът по ЗБДОО щеше да я вдигне до 550,66 € —
   * правдоподобно число за лице, което не е избирало нищо.
   */
  @Test
  @DisplayName("⚠️ РЕГИСТРАЦИЯ · нула доход → ОТКАЗ, а НЕ мълчаливо вдигане до минимума")
  void aZeroSelectedIncomeIsRefusedRatherThanRaisedToTheFloor() {
    BgSelfInsuredRegistration zero = valid();
    zero.setBgSelectedIncome(BigDecimal.ZERO);
    String refusal = BgSelfInsuredRules.refuseRegistration(zero);
    assertNotNull(refusal, "нулевият избран доход трябва да бъде отказан");
    assertTrue(refusal.contains("чл. 6 ал. 8"), refusal);

    // И непопълненото поле дава СЪЩИЯ отказ — двете са неразличими по модела и значат едно.
    BgSelfInsuredRegistration empty = valid();
    empty.setBgSelectedIncome(null);
    assertEquals(refusal, BgSelfInsuredRules.refuseRegistration(empty));
  }

  // ═══ ПЪРВИЯТ ЧИТАТЕЛ НА Employee.bgIsSelfInsured ══════════════════════════

  @Test
  @DisplayName("⚠️ СВЕРКА · признакът на служителя вече СЕ ЧЕТЕ — и разминаването се назовава")
  void theEmployeeFlagIsFinallyRead() {
    assertNull(BgSelfInsuredRules.employeeFlagMismatch(valid()), "съвпадащите признаци мълчат");

    BgSelfInsuredRegistration regUp = valid();
    regUp.getEmployee().setBgIsSelfInsured(false);
    String a = BgSelfInsuredRules.employeeFlagMismatch(regUp);
    assertNotNull(a);
    assertTrue(a.contains("вдигнете чекбокса"), a);

    BgSelfInsuredRegistration regDown = valid();
    regDown.setBgIsSelfInsured(false);
    String b = BgSelfInsuredRules.employeeFlagMismatch(regDown);
    assertNotNull(b);
    // ⚠️ ДВЕТЕ ПОСОКИ ИСКАТ РАЗЛИЧНА ПОПРАВКА и затова носят различен текст: първата се поправя на
    // екрана на служителя, втората — на регистрацията.
    assertTrue(!a.equals(b), "двете посоки на разминаването не са едно и също");
  }

  // ═══ ФИКСИРАНАТА БАЗА ═════════════════════════════════════════════════════

  /**
   * ⚠️ ЗА КОДОВЕ 41 И 96 БАЗАТА НЕ Е ИЗБОР, И ОТКАЗЪТ ЗА НУЛА НЕ ВАЖИ ЗА ТЯХ.
   *
   * <p>КСО чл. 6 ал. 8 дава избора („между минималния и максималния“) само на лицата по чл. 4 ал. 3
   * т. 1, 2 и 4. За чл. 4а1 ал. 1 нормата назовава ПОЛОВИНАТА от МРЗ, а за чл. 4 ал. 9 — МИНИМАЛНИЯ
   * осигурителен доход за самоосигуряващите се. Отказ за нулев доход там би искал от оператора да
   * попълни поле, което после НЕ се чете — тоест би го научил на невярно.
   */
  @Test
  @DisplayName("⚠️ БАЗА · 41 и 96 имат ФИКСИРАНА база — нулевият избран доход НЕ се отказва")
  void theFixedBaseCodesDoNotDemandAChosenIncome() {
    assertTrue(BgSelfInsuredRules.incomeIsChosen("12"), "чл. 4 ал. 3 т. 1/2 — изборът е негов");
    assertTrue(BgSelfInsuredRules.incomeIsChosen("13"), "чл. 4 ал. 3 т. 4 — също");
    assertTrue(BgSelfInsuredRules.incomeIsChosen("22"), "същият избор, друга каденца на подаване");
    assertEquals(
        false, BgSelfInsuredRules.incomeIsChosen("41"), "чл. 4а1 ал. 1 — половината от МРЗ");
    // ⚠️ 96 ВЛЕЗЕ на 28.08.2026 г., щом правният въпрос за здравното му получи отговор. Базата му
    // беше сверена и преди това: НООСЛБГРЧМЛ чл. 17б ал. 1 изр. 2 — „върху МИНИМАЛНИЯ осигурителен
    // доход за самоосигуряващите се лица“, а НАП разд. 15 отпечатва едно число там, където разд. 10
    // и 14 пишат „избран между минималния и максималния“.
    assertEquals(
        false,
        BgSelfInsuredRules.incomeIsChosen("96"),
        "чл. 4 ал. 9 — минималният осигурителен доход за самоосигуряващите се");

    // ⚠️ И ОТТАМ ОТКАЗЪТ Е УСЛОВЕН: същата празна регистрация е валидна при 41 и 96, но не при 12.
    for (String fixed : new String[] {"41", "96"}) {
      BgSelfInsuredRegistration reg = valid();
      reg.setBgInsuredTypeCode(fixed);
      reg.setBgSelectedIncome(BigDecimal.ZERO);
      assertNull(
          BgSelfInsuredRules.refuseRegistration(reg),
          "код " + fixed + " няма какво да избира — нулата е верният отговор");
    }
    BgSelfInsuredRegistration chosen = valid();
    chosen.setBgSelectedIncome(BigDecimal.ZERO);
    assertNotNull(
        BgSelfInsuredRules.refuseRegistration(chosen), "код 12 ОЩЕ отказва — пазачът е ЖИВ");
  }

  // ═══ КАТЕГОРИЯТА ══════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ КАТЕГОРИЯ · трета, и то ЯВНА — празната дава празен набор ставки")
  void theLaborCategoryIsThirdAndExplicit() {
    assertEquals("3", BgSelfInsuredRules.LABOR_CATEGORY);
  }
}
