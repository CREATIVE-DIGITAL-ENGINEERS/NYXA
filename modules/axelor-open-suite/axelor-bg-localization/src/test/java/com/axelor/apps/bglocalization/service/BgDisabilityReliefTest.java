package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ДАНЪЧНОТО ОБЛЕКЧЕНИЕ ЗА НАМАЛЕНА РАБОТОСПОСОБНОСТ — РЕШЕНИЕТО: полага ли се и колко.
 *
 * <p>Файловата половина: „вярно ли е РЕШЕНО“. Без Postgres, без Guice, без Hibernate.
 *
 * <p><b>═══ НОРМАТА, ДОСЛОВНО ═══</b> Сверено срещу официалния ЗДДФЛ на 23.08.2026 г.
 *
 * <p><b>Чл. 18 ал. 2:</b> „Месечната данъчна основа по чл. 42, ал. 2 за доходи от трудови
 * правоотношения на лица с 50 и с над 50 на сто намалена работоспособност се намалява с 660 лв.,
 * включително за месеца на настъпване на неработоспособността и за месеца на изтичане срока на
 * валидност на решението.“
 *
 * <p><b>⚠️ „ВКЛЮЧИТЕЛНО ЗА МЕСЕЦА НА ИЗТИЧАНЕ“ Е ТВЪРДЕНИЕ ЗА ГРАНИЦА</b> и затова има свой тест от
 * двете страни: решение, валидно до който и да е ден от март, дава ЦЯЛОТО облекчение за март;
 * решение, изтекло през февруари, не дава нищо. Сгрешена с един месец, тази граница мълчи — данъкът
 * просто излиза с 33,75 по-висок и фишът изглежда сметнат.
 *
 * <p><b>⚠️ ПОДМЕНЕН Е САМО ЧЕТЕЦЪТ НА ПАРАМЕТЪРА.</b> {@code employeeOf} се вика ИСТИНСКИ — той е
 * два прочита без база (фиш → договор → лице) и подменен би скрил точно случая „договор без лице“.
 * Стойността 337,45 идва ОТ SEED ФАЙЛА, не е число в теста: смени ли се параметърът, тези тестове
 * падат.
 *
 * <p>Базовата половина — същото решение срещу ДОСТАВЕНИЯ параметър — е {@code
 * BgDisabilityReliefDbTest}.
 */
class BgDisabilityReliefTest {

  /** Периодът, за който се пита: март 2026 г. Датата е ПЪРВОТО число — оттам е и границата. */
  private static final LocalDate MARCH_FROM = LocalDate.of(2026, 3, 1);

  private static final LocalDate MARCH_TO = LocalDate.of(2026, 3, 31);

  // ═══ УСЛУГАТА С ПОДМЕНЕН ЧЕТЕЦ ════════════════════════════════════════════

  private static final class Service extends BgPaySlipService {

    /** Изключено — за да се види ОТКАЗЪТ при вдигнат флаг и незареден параметър. */
    boolean parameterLoaded = true;

    String askedForCode;
    LocalDate askedForDate;

    @Override
    protected BigDecimal payrollParameter(String code, LocalDate date) {
      askedForCode = code;
      askedForDate = date;
      if (!parameterLoaded) {
        return null;
      }
      return PARAM_DISABILITY_RELIEF.equals(code) ? reliefFromSeed() : null;
    }
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  /** 337,45 EUR — ОТ ФАЙЛА (660 лв. / 1,95583), не число в теста. */
  static BigDecimal reliefFromSeed() {
    List<String> header = BgSeed.quotedHeader("bg_payroll_parameter.csv");
    for (String[] row : BgSeed.quotedRows("bg_payroll_parameter.csv")) {
      if ("DISABILITY_RELIEF_MONTHLY".equals(BgSeed.col(header, row, "code"))) {
        return new BigDecimal(BgSeed.col(header, row, "paramValue").trim());
      }
    }
    fail("параметър DISABILITY_RELIEF_MONTHLY липсва в bg_payroll_parameter.csv");
    return null;
  }

  /**
   * Фиш за март 2026 г. по договор с лице, носещо (или не) решение за намалена работоспособност.
   */
  private static BgPaySlip slipFor(Boolean reliefFlag, LocalDate validUntil) {
    BgPaySlip slip = bareSlip();
    Employee employee = new Employee();
    if (reliefFlag != null) {
      employee.setBgDisabilityRelief(reliefFlag);
    }
    employee.setBgDisabilityValidUntil(validUntil);
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);
    slip.setEmploymentContract(contract);
    return slip;
  }

  private static BgPaySlip bareSlip() {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period period = new Period();
    period.setName("03/PY2026");
    period.setFromDate(MARCH_FROM);
    period.setToDate(MARCH_TO);
    slip.setPeriod(period);
    return slip;
  }

  private static void assertRelief(BgPaySlip slip, String expected, String why) {
    Service service = new Service();
    BigDecimal relief = service.disabilityRelief(slip);
    assertEquals(0, new BigDecimal(expected).compareTo(relief), why + " Намерено: " + relief);
  }

  // ═══ 1 · ПОЛАГА СЕ ════════════════════════════════════════════════════════

  @Test
  @DisplayName("флаг вдигнат, решение валидно до края на годината → 337,45")
  void aValidDecisionGivesTheRelief() {
    assertRelief(
        slipFor(true, LocalDate.of(2026, 12, 31)),
        reliefFromSeed().toPlainString(),
        "⚠️ чл. 18 ал. 2 — 660 лв. (337,45 EUR) месечно.");
  }

  /**
   * ⚠️ ПОЖИЗНЕНО РЕШЕНИЕ (§ 10а ал. 3 ПЗР ЗДДФЛ) — празната дата НЕ значи „изтекло“.
   *
   * <p>Прочетена като изтекло, тя би отнела облекчението на всяко лице с безсрочно решение, и то
   * тихо: данъкът просто излиза по-висок.
   */
  @Test
  @DisplayName("⚠️ флаг вдигнат, БЕЗ дата (пожизнено решение) → 337,45")
  void anOpenEndedDecisionGivesTheRelief() {
    assertRelief(
        slipFor(true, null),
        reliefFromSeed().toPlainString(),
        "⚠️ празната дата при ВДИГНАТ флаг е пожизнено решение.");
  }

  /**
   * ⚠️ СВИДЕТЕЛЯТ НА „ВКЛЮЧИТЕЛНО ЗА МЕСЕЦА НА ИЗТИЧАНЕ“. Решение, валидно до 15 март, дава ЦЯЛОТО
   * облекчение за март — алинеята включва месеца изрично. Сравнено с КРАЯ на периода, този случай
   * щеше да изпадне.
   */
  @Test
  @DisplayName("⚠️ решение, изтичащо В САМИЯ месец (15.03) → пълните 337,45 за март")
  void theMonthOfExpiryIsIncluded() {
    assertRelief(
        slipFor(true, LocalDate.of(2026, 3, 15)),
        reliefFromSeed().toPlainString(),
        "⚠️ „включително за месеца на изтичане срока на валидност на решението“.");
    assertRelief(
        slipFor(true, MARCH_FROM),
        reliefFromSeed().toPlainString(),
        "⚠️ и решение, валидно точно до 1 март, още покрива март.");
  }

  // ═══ 2 · НЕ СЕ ПОЛАГА ═════════════════════════════════════════════════════

  @Test
  @DisplayName("решение, изтекло преди периода (28.02) → 0,00")
  void anExpiredDecisionGivesNothing() {
    assertRelief(
        slipFor(true, LocalDate.of(2026, 2, 28)),
        "0",
        "⚠️ февруари е ПРЕДИ март — облекчението вече не се полага.");
  }

  @Test
  @DisplayName("флаг свален → 0,00, дори при попълнена дата")
  void withoutTheFlagThereIsNoRelief() {
    assertRelief(
        slipFor(false, LocalDate.of(2026, 12, 31)), "0", "няма решение — няма облекчение.");
  }

  /**
   * ⚠️ ПРАЗНАТА ДАТА НЕ Е САМОСТОЯТЕЛНО ОСНОВАНИЕ. Пожизнено е САМО когато флагът е вдигнат И
   * датата е празна; без флаг празната дата е просто непопълнено поле на човек без решение.
   */
  @Test
  @DisplayName("⚠️ флаг свален + празна дата → 0,00, не „пожизнено“")
  void anEmptyDateWithoutTheFlagIsNotOpenEnded() {
    assertRelief(slipFor(false, null), "0", "⚠️ празната дата без флаг не значи нищо.");
    assertRelief(
        slipFor(null, null), "0", "непопълнен флаг се чете като свален (getter-ът дава FALSE).");
  }

  /**
   * ⚠️ ЛИПСВАЩ СЛУЖИТЕЛ ИЛИ ДОГОВОР Е НУЛА, НЕ ОТКАЗ. Без установено решение няма облекчение, а
   * отказът за непопълнени данни по договора вече си има дом в {@code
   * BgPayrollInputBuilder.REFUSALS}.
   */
  @Test
  @DisplayName("фиш без договор и договор без лице → 0,00, без отказ")
  void aMissingContractOrEmployeeGivesZero() {
    assertRelief(bareSlip(), "0", "фиш без договор.");

    BgPaySlip withoutEmployee = bareSlip();
    withoutEmployee.setEmploymentContract(new EmploymentContract());
    assertRelief(withoutEmployee, "0", "договор без лице.");
  }

  // ═══ 3 · ОТКАЗЪТ ══════════════════════════════════════════════════════════

  /**
   * ⚠️ ВДИГНАТ ФЛАГ + НЕЗАРЕДЕН ПАРАМЕТЪР Е ОТКАЗ, НЕ НУЛА.
   *
   * <p>Нула тук би удържала ПЪЛНИЯ данък от лице с решение на ТЕЛК — 33,75 в повече всеки месец при
   * ставка 10 на сто — и нищо на фиша не би изглеждало сгрешено. {@code null} стига до {@code
   * BgIncomeTaxService}, който ОТКАЗВА с назована причина, и фишът остава непотвърдим.
   */
  @Test
  @DisplayName("⚠️ флаг вдигнат, но DISABILITY_RELIEF_MONTHLY не е зареден → null (ОТКАЗ), не 0")
  void aMissingParameterIsRefusedNotZero() {
    Service service = new Service();
    service.parameterLoaded = false;

    BigDecimal relief = service.disabilityRelief(slipFor(true, null));

    assertEquals("DISABILITY_RELIEF_MONTHLY", service.askedForCode);
    assertNull(relief, "⚠️ 0,00 тук значи пълен данък от лице с решение. Намерено: " + relief);
  }

  /**
   * ⚠️ СВАЛЕНИЯТ ФЛАГ НЕ ПИТА ЗА ПАРАМЕТЪРА ИЗОБЩО — и точно затова липсващият параметър не блокира
   * фишовете на лицата БЕЗ решение. Инак незареден параметър щеше да спре цялата ведомост.
   */
  @Test
  @DisplayName("⚠️ без флаг параметърът НЕ се пита — незаредена стойност не спира чуждите фишове")
  void theParameterIsNotEvenAskedWithoutTheFlag() {
    Service service = new Service();
    service.parameterLoaded = false;

    BigDecimal relief = service.disabilityRelief(slipFor(false, null));

    assertEquals(0, BigDecimal.ZERO.compareTo(relief));
    assertNull(service.askedForCode, "⚠️ питане тук би направило чуждия параметър общ проблем");
  }

  // ═══ 4 · ДАТАТА ═══════════════════════════════════════════════════════════

  /**
   * ⚠️ ПИТА СЕ ЗА НАЧАЛОТО НА ПЕРИОДА, не за днес. Взето днешното, коригиращ фиш за минал месец
   * щеше да чете ДНЕШНАТА стойност на облекчението и ДНЕШНАТА валидност на решението.
   */
  @Test
  @DisplayName("⚠️ параметърът се пита за 01.03.2026 — датата на периода, не днешната")
  void theParameterIsAskedForThePeriodDate() {
    Service service = new Service();

    service.disabilityRelief(slipFor(true, null));

    assertEquals(MARCH_FROM, service.askedForDate);
  }
}
