package com.axelor.apps.bglocalization.db.repo;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.payroll.BgEarningsResolver;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import javax.validation.ValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОТКЪДЕ ЗАПИСЪТ ВЗЕМА ДВЕТЕ ПОЛОВИНИ НА КЛЮЧА — прочитът, не правилото.
 *
 * <p>Правилото само по себе си е {@code BgInsuredTypeCategoryRuleTest} и няма нужда нито от база,
 * нито от домейн. Тук се проверява само това, което е СПЕЦИФИЧНО за записа на договора: че
 * категорията труд се чете от СЛУЖИТЕЛЯ, а не от договора, и че веригата издържа липсващ служител.
 *
 * <p>⚠️ Точно това разминаване между двата екрана прави едно-полевото съобщение подвеждащо, и
 * затова прочитът е отделен, назован метод, а не вграден null-израз в {@code save}.
 *
 * <p><b>⚠️ И ЗАБРАНЕНИЯТ РЕЖИМ НА ОТЧИТАНЕ (КТ чл. 142 ал. 5).</b> Същият прочит и същата граница:
 * правилото само по себе си е {@code BgWorkTimeModeRuleTest}, а тук се проверява само това, което е
 * специфично за записа — че отказът ИЗБУХВА (а не връща стойност, която някой да подмине), че носи
 * текста на нормата и че трите допустими двойки минават.
 *
 * <p>⚠️ МЕРИ СЕ ШЕВЪТ, А НЕ {@code save} — и това е граница на свидетеля, не небрежност. {@code
 * save} иска Guice, база и цялата верига на {@code EmploymentContractHRRepository}
 * (последователност за номера на договора, предложение на обезщетенията). Затова отказите са
 * изнесени като статични, назовани методи и се питат тук поотделно; че {@code save} ги вика ПРЕДИ
 * {@code super.save}, се чете от четири реда в самия метод.
 */
class BgEmploymentContractSaveGuardTest {

  @Test
  @DisplayName("Категорията труд идва от СЛУЖИТЕЛЯ, не от договора")
  void theLaborCategoryIsReadFromTheEmployee() {
    Employee employee = new Employee();
    employee.setBgLaborCategory("1");
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);
    contract.setBgInsuredTypeCode("01");

    assertEquals(
        "1",
        BgEmploymentContractManagementRepository.laborCategoryOf(contract),
        "⚠️ Категорията се води на Employee.bgLaborCategory. Прочетена от договора, тя би била"
            + " винаги null и валидаторът щеше да мълчи за ВСЕКИ запис — зелен пазач, който не"
            + " пази нищо.");
  }

  @Test
  @DisplayName("Договор без служител не гърми — той е друг дефект и има друга причина")
  void aContractWithoutAnEmployeeIsNotAnError() {
    EmploymentContract contract = new EmploymentContract();
    contract.setBgInsuredTypeCode("01");

    assertNull(
        BgEmploymentContractManagementRepository.laborCategoryOf(contract),
        "договор без служител е дефект в данните, но НЕ е дефект във вида осигурен — записът не"
            + " бива да се отказва с чужда причина");
    assertNull(BgEmploymentContractManagementRepository.laborCategoryOf(null));
  }

  @Test
  @DisplayName("Служител без попълнена категория — незададен въпрос, не разминаване")
  void anEmployeeWithoutACategoryIsSilent() {
    Employee employee = new Employee();
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);

    assertNull(BgEmploymentContractManagementRepository.laborCategoryOf(contract));
  }

  // ── КТ ЧЛ. 142 АЛ. 5 · ЗАБРАНЕНИЯТ РЕЖИМ НА ОТЧИТАНЕ ────────────────────

  /**
   * ⚠️ НЕНОРМИРАН РАБОТЕН ДЕН + СИРВ — ЗАПИСЪТ СЕ ОТКАЗВА, А НЕ СЕ ОБСЛУЖВА.
   *
   * <p>КТ чл. 142 ал. 5: „Не се допуска сумирано изчисляване на работното време за работниците и
   * служителите с ненормиран работен ден.“ Тоест въпросът не е „плаща ли се извънредният труд при
   * тази двойка“ — двойката НЕ БИВА ДА СЪЩЕСТВУВА, и това е основната преграда.
   *
   * <p>⚠️ Отказът е ИЗКЛЮЧЕНИЕ, а не върната стойност: върната, тя щеше да се подмине от втория
   * читател на {@code save} и договорът щеше да се запише въпреки нея.
   *
   * <p>FAIL-FIRST: правило, което пита само за отметката „ненормиран работен ден“, отказва и
   * подневното отчитане — и тогава пада свидетелят за допустимите двойки по-долу.
   *
   * <p>⚠️ Че викането стои ПРЕДИ {@code super.save}, този свидетел НЕ мери — то се чете от реда в
   * {@code save} и няма как да се измери без база. Отказът след записа би оставил базата с
   * недопустимата двойка, затова редът е част от промяната, а не подробност.
   */
  @Test
  @DisplayName("⚠️ чл. 142 ал. 5 · договор с ненормиран ден И СИРВ не се записва")
  void aContractWithBothUnregulatedDayAndSirvIsRefused() {
    EmploymentContract contract = new EmploymentContract();
    contract.setBgIsUnregulatedWorkDay(Boolean.TRUE);
    contract.setBgWorkTimeMode(BgEarningsResolver.WORK_TIME_MODE_SIRV);

    ValidationException thrown =
        assertThrows(
            ValidationException.class,
            () -> BgEmploymentContractManagementRepository.refuseForbiddenWorkTimeMode(contract),
            "⚠️ Недопустимата двойка се записваше без възражение и изплуваше чак на фиша.");
    assertTrue(
        thrown.getMessage().contains("чл. 142, ал. 5"),
        "отказът трябва да назове НОРМАТА — адресатът му е операторът на договора, не читателят"
            + " на кода. Получено: "
            + thrown.getMessage());
  }

  /**
   * ⚠️ ТРИТЕ ДОПУСТИМИ ДВОЙКИ МИНАВАТ — пазач, който спира и тях, спира всеки втори договор.
   *
   * <p>Ненормиран ден при подневно отчитане (чл. 139а) и СИРВ без ненормиран ден (чл. 142 ал. 2) са
   * обичайните случаи, а непопълненият режим е незададен въпрос с друг адресат: перото за работа на
   * официален празник го отказва по СВОЯ причина.
   */
  @Test
  @DisplayName("допустимите двойки и празният договор минават през пазача")
  void theAllowedPairsPassTheGuard() {
    EmploymentContract unregulatedByDay = new EmploymentContract();
    unregulatedByDay.setBgIsUnregulatedWorkDay(Boolean.TRUE);
    unregulatedByDay.setBgWorkTimeMode("1");

    EmploymentContract sirvOnly = new EmploymentContract();
    sirvOnly.setBgWorkTimeMode(BgEarningsResolver.WORK_TIME_MODE_SIRV);

    EmploymentContract modeUnset = new EmploymentContract();
    modeUnset.setBgIsUnregulatedWorkDay(Boolean.TRUE);

    for (EmploymentContract allowed :
        new EmploymentContract[] {
          unregulatedByDay, sirvOnly, modeUnset, new EmploymentContract(), null
        }) {
      assertDoesNotThrow(
          () -> BgEmploymentContractManagementRepository.refuseForbiddenWorkTimeMode(allowed),
          "пазачът отказва двойка, която КТ допуска — мери отметката, а не забраната");
    }
  }
}
