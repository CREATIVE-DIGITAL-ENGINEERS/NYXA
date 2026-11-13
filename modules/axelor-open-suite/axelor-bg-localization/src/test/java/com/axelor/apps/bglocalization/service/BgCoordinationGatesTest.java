package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ДВАТА ОТКАЗА, КОИТО КООРДИНАЦИЯТА ИЗКЛЮЧВА — защото базата НЕ зависи от тях.
 *
 * <p><b>Дефектът, който този тест заковава.</b> При активна декларация по КСО чл. 6 ал. 11 базата е
 * остатъкът до тавана: {@code clamp(min(собствен, MAX − деклариран), 0, MAX)}. В нея НЕ участва
 * нито пропорцията по НЕВД чл. 1, нито МОД подът. А два пазача могат да откажат такъв фиш:
 *
 * <ul>
 *   <li>работно-времевият гейт в {@code BgPaySlipService#contributions} — при непопълнен режим по
 *       договора ({@code EmploymentContract.bgWorkTimeMode}), а всичките засети договори имат
 *       {@code bg_hours_per_day = 0.00};
 *   <li>{@code BgPaySlipService#minIncomeRefusal} при потвърждаване — при липсващ ред по Приложение
 *       № 1 за НКПД/НКИД.
 * </ul>
 *
 * <p><b>Това е ТОЧНО същият клас дефект като безусловен работно-времеви гейт</b>, който блокира
 * фиша на морско лице заради вход, от който сметката му не зависи — виж {@code BgWorkTimeGateTest}.
 * Разликата е само в кое условие го прави безопасно: там {@code prorates}, тук активната
 * декларация.
 *
 * <p><b>⚠️ И ДВАТА СВИДЕТЕЛЯ СА ДВОЙНИ.</b> „Не отказва при координация“ само по себе си минава и
 * когато пазачът е изтрит. Затова всеки тест мери СЪЩИЯ вход и в двете посоки: с декларация →
 * минава, без декларация → още отказва. Второто е доказателството, че пазачът е ЖИВ, а не махнат.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate: подменени са само четците към базата.
 */
class BgCoordinationGatesTest {

  private static final LocalDate FROM = LocalDate.of(2026, 6, 1);
  private static final LocalDate TO = LocalDate.of(2026, 6, 30);

  /** ЗБДОО чл. 9 т. 1 б. „в“ — таванът за 01.01–31.07.2026 г. */
  private static final BigDecimal CAP = new BigDecimal("2111.6400");

  private static final BigDecimal SALARY = new BigDecimal("1252.00");

  private static final BigDecimal DECLARED = new BigDecimal("1500.00");

  // ═══ УСЛУГАТА С ПОДМЕНЕНИ ЧЕТЦИ, НО ИСТИНСКИ ПАЗАЧИ ══════════════════════

  private static final class Service extends BgPaySlipService {

    BgPayrollInput input;

    /**
     * ⚠️ БЛОКИРАЩ МОД по подразбиране — нарочно. Точно това е състоянието, при което вторият пазач
     * се задейства, и тестът щеше да е безсмислен с разрешен МОД.
     */
    BgMinIncomeResult mod =
        BgMinIncomeResult.failure(
            BgMinIncomeResult.Status.NO_MOD_ROW_FOR_NKID, "НКИД без ред по Приложение № 1");

    BgInsurableIncomeResult seenIncome;

    /** Броячът доказва, че МОД изобщо НЕ се пита при координация — не само че не отказва. */
    int modLookups;

    @Override
    protected BgPayrollInput payrollInput(BgPaySlip slip) {
      return input;
    }

    @Override
    protected BgMinIncomeResult minIncomeFor(BgPayrollInput in, BgPaySlip slip) {
      modLookups++;
      return mod;
    }

    @Override
    protected boolean proratesWithWorkTime(BgPayrollInput in, BgPaySlip slip) {
      // Обикновен нает по чл. 4 ал. 1 т. 1: прагът СЕ пропорционира, тоест гейтът е в сила.
      return true;
    }

    /** ⚠️ ИСТИНСКАТА аритметика; подменено е само зареждането на тавана. */
    @Override
    protected BgInsurableIncomeResult insurableIncome(
        BigDecimal gross, BgMinIncomeResult modResult, BgPayrollInput in, BgWorkTimeFacts facts) {
      seenIncome =
          BgInsurableIncomeService.compute(
              gross,
              modResult,
              null,
              true,
              CAP,
              FROM,
              facts,
              BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
              false,
              BgInsurableIncomeService.coordinationOf(in.otherEmployerInsurableIncome));
      return seenIncome;
    }

    /** Резолверът на ставките не е предмет на този тест — заявката стига дотук и това е важното. */
    @Override
    protected BgContributionResult calculate(BgContributionRequest request) {
      return BgContributionResult.resolved(
          Collections.singletonList(
              new BgContributionLine(
                  "PENSION",
                  BgContributionLine.Payer.INSURED,
                  request.getInsurableIncome(),
                  new BigDecimal("6.58"),
                  new BigDecimal("0.00"))));
    }

    @Override
    protected void save(BgPaySlip slip) {}
  }

  // ═══ ВХОДЪТ: НЕПОПЪЛНЕНО РАБОТНО ВРЕМЕ ══════════════════════════════════

  /**
   * Вход, при който {@code BgPayrollWorkTime.decide} ОТКАЗВА: режимът не се разпознава.
   *
   * <p>⚠️ Това НЕ е изкуствен случай. Режимът идва от {@code EmploymentContract.bgWorkTimeMode}, а
   * заварените договори в базата го нямат — точно затова безусловен гейт би важал за всеки код.
   */
  private static BgPayrollInput inputWithoutWorkTime(BigDecimal declared) {
    return BgPayrollInput.builder()
        .insuredTypeCode("01")
        .laborCategory("3")
        .bornAfter1959(true)
        .periodFrom(FROM)
        .periodTo(TO)
        .parameterReferenceDate(FROM)
        .workingDaysInPeriod(22)
        .workedDays(22)
        .baseSalary(SALARY)
        // ⚠️ НИТО режим, НИТО часове — точно състоянието на заварения договор.
        .workTimeMode(null)
        .otherEmployerInsurableIncome(declared)
        .build();
  }

  /**
   * ⚠️ Декларацията се слага И НА ФИША, не само във входа — както в производството: пазачите я
   * четат слип-локално (от фиша и от договора), а сметката — от входа. Само на едно от двете места
   * тя би дала състояние, което производството не може да произведе.
   */
  private static BgPaySlip slipWithSalaryLine(BigDecimal declared) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period p = new Period();
    p.setName("06/PY2026");
    p.setFromDate(FROM);
    p.setToDate(TO);
    slip.setPeriod(p);
    slip.setBgOtherPayerInsurableIncome(declared);
    slip.setBgPaySlipLineList(new ArrayList<>());

    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(100);
    line.setName("Основно възнаграждение");
    line.setBgSign(BgPaySlipResolver.CREDIT);
    line.setBgIsInsuranceIncome(true);
    line.setBgIsTaxable(true);
    line.setBgAmount(SALARY);
    slip.getBgPaySlipLineList().add(line);
    return slip;
  }

  private static Service service(BigDecimal declared) {
    Service s = new Service();
    s.input = inputWithoutWorkTime(declared);
    return s;
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // B5 · РАБОТНО-ВРЕМЕВИЯТ ГЕЙТ
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ B5 · координационен фиш с НЕПОПЪЛНЕНО работно време НЕ се отказва — базата не зависи от"
          + " него")
  void coordinationSurvivesAnUndeterminedWorkTimeRegime() {
    Service s = service(DECLARED);
    BgContributionResult result = s.contributions(slipWithSalaryLine(DECLARED));

    assertNotNull(result);
    assertEquals(
        BgContributionResult.Status.RESOLVED,
        result.getStatus(),
        "⚠️ INPUT_NOT_ASSEMBLED тук значи, че гейтът пак спира фиш, чието число не зависи от него —"
            + " същият клас дефект като безусловния работно-времеви гейт. Причина: "
            + result.getDetail());
    assertTrue(s.seenIncome.isCoordinated(), "и базата наистина е излязла по координирания път");
    // 2111,64 − 1500,00 = 611,64 остатък, а собственият доход е 1252,00 — остатъкът е по-МАЛЪК,
    // тоест той е базата.
    assertEquals(
        0,
        new BigDecimal("611.64").compareTo(s.seenIncome.getAmount()),
        "координираната база е остатъкът до тавана. Намерено: " + s.seenIncome.getAmount());
  }

  @Test
  @DisplayName("⚠️ B5 · СВИДЕТЕЛ · СЪЩИЯТ вход БЕЗ декларация още се отказва — пазачът е ЖИВ")
  void withoutADeclarationTheWorkTimeGateStillRefuses() {
    Service s = service(null);
    BgContributionResult result = s.contributions(slipWithSalaryLine(null));

    assertEquals(
        BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
        result.getStatus(),
        "⚠️ RESOLVED тук значи, че гейтът е МАХНАТ, а не направен условен — тоест фиш с подразбран"
            + " режим на работното време ще мине тихо, с праг наужким");
    assertTrue(
        result.getDetail().contains("работното време не е определено"),
        "и причината е точно работното време: " + result.getDetail());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // B5 · МОД ОТКАЗЪТ ПРИ ПОТВЪРЖДАВАНЕ
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ B5 · minIncomeRefusal МЪЛЧИ при координация — и МОД изобщо не се пита")
  void theMinIncomeRefusalIsSkippedWhenCoordinating() {
    Service s = service(DECLARED);
    BgPaySlip slip = slipWithSalaryLine(DECLARED);

    assertNull(
        s.minIncomeRefusal(slip),
        "⚠️ Отказ тук спира ВЕРЕН фиш: МОД подът е праг на ЛИЦЕТО за месеца и вече е покрит от"
            + " другото правоотношение, тоест координираната база не зависи от него");

    // ⚠️ И НЕ САМО „не отказва“: МОД изобщо НЕ СЕ ПИТА. Заявка, чийто отговор не влиза в нито едно
    // число, е само още един начин веригата да падне по повод, от който не зависи.
    s.modLookups = 0;
    s.minIncomeRefusal(slip);
    assertEquals(0, s.modLookups, "пазачът не пита МОД при активна координация");
    // ⚠️ И ЦЯЛАТА ВЕРИГА също не го пита — тук гейтът НЕ къса, защото координацията го изключва,
    // тоест contributions наистина стига до базата и пак минава без МОД.
    s.modLookups = 0;
    assertTrue(s.contributions(slip).isResolved(), "веригата минава докрай");
    assertEquals(0, s.modLookups, "и сметката не търси МОД при активна координация");
  }

  @Test
  @DisplayName("⚠️ B5 · СВИДЕТЕЛ · без декларация minIncomeRefusal още отказва и МОД СЕ пита")
  void withoutADeclarationTheMinIncomeRefusalStillFires() {
    Service s = service(null);
    BgPaySlip slip = slipWithSalaryLine(null);

    String refusal = s.minIncomeRefusal(slip);
    assertNotNull(
        refusal,
        "⚠️ null тук значи, че пазачът е МАХНАТ, а не направен условен — липсващият МОД щеше да"
            + " минава мълчаливо на ВСЕКИ фиш, а липсващ МОД НЕ значи нула");
    assertTrue(refusal.contains("Няма минимален осигурителен доход"), refusal);

    // ⚠️ Броячът се мери около minIncomeRefusal, НЕ около contributions — и това е находка, не
    // удобство: при този вход (непопълнено работно време) contributions се отказва на ГЕЙТА и
    // изобщо не стига до МОД. Тоест двата пазача се задействат на РАЗНИ стъпки и работно-времевият
    // къса веригата пред МОД търсенето. Мерено там, свидетелят щеше да е зелен по погрешна причина.
    s.modLookups = 0;
    s.minIncomeRefusal(slip);
    assertTrue(s.modLookups > 0, "и без координация МОД СЕ пита — инак прагът би паднал тихо");
  }
}
