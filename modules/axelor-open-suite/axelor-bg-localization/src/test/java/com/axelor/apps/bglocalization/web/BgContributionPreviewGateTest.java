package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.service.BgContributionRequest;
import com.axelor.apps.bglocalization.service.BgContributionResolver;
import com.axelor.apps.bglocalization.service.BgContributionResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeResult;
import com.axelor.apps.bglocalization.service.BgMinIncomeResult;
import com.axelor.apps.bglocalization.service.BgMinInsuranceIncomeService;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInput;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollWorkTime;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ДИАГНОСТИЧНИЯТ ЕКРАН СПИРА САМО КОГАТО ОТКАЗЪТ ПАЗИ НЕЩО — вторият от двата пътя.
 *
 * <p><b>Случаят.</b> {@code POST /nyxa/ws/action} → {@code
 * action-bg-contribution-preview-calculate}, служител #1, дружество #1, 19.08.2026, вид осигурен
 * 92, категория 3, STANDARD, брутно 620,20 — без тази граница екранът връща:
 *
 * <blockquote>
 *
 * Режим на работното време: UNKNOWN<br>
 * ОТКАЗ: режимът на работното време не е разпознат … (EmploymentContract.bgHoursPerDay)<br>
 * (нито МОД, нито база, нито вноски се смятат — виж отказа)
 *
 * </blockquote>
 *
 * <p>А код 92 е с {@code bgZeroWorkTime = true}: прагът му се дължи ЦЯЛ и работното време не влиза
 * в сметката с нито едно число. Тогава гейтът блокира заради вход, от който резултатът НЕ зависи.
 *
 * <p><b>Защо {@link BgContributionPreviewController#format} и защо тестът е в същия пакет.</b>
 * {@code calculate(ActionRequest, ActionResponse)} иска жив Axelor контекст; {@code format} е
 * ЧИСТАТА половина — взима вече готовите резултати и решава КАКВО се показва, включително дали да
 * върне блока „ОТКАЗ“ и да спре. Пакетът е същият, тоест защитеният метод се вика без подклас и без
 * Guice. Правилото зад условието е в {@code BgWorkTimeGateTest}, числата на истински фиш — в {@code
 * BgPaySlipContributionWiringTest}.
 */
class BgContributionPreviewGateTest {

  private static final LocalDate DATE = LocalDate.of(2026, 8, 19);

  /** МОД за СОЛ от 01.08.2026 — базата на морското лице, дължима цяла. */
  private static final BigDecimal BASE = new BigDecimal("620.20");

  private static final BigDecimal CAP = new BigDecimal("2111.64");

  /** {@code describe} минава през {@code I18n}; тестът проверява ГЕЙТА, не превода. */
  private static class Describing extends BgMinInsuranceIncomeService {
    @Override
    public String describe(BgMinIncomeResult result) {
      return "(описание на МОД)";
    }
  }

  /**
   * Решението за работното време при засет договор: {@code bg_hours_per_day = 0.00}, което
   * сглобителят чете като {@code null} → {@code UNKNOWN} → ОТКАЗ. Точно входът от репродукцията.
   */
  private static BgPayrollWorkTime.Decision refusedDecision() {
    BgPayrollInput input =
        BgPayrollInput.builder()
            .contractHoursPerDay(null)
            .workingDaysInPeriod(21)
            .workedHours(new BigDecimal("40.00"))
            .workedDays(10)
            .build();
    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(input);
    assertTrue(decision.isRefused(), "котвата: това трябва да Е отказът от репродукцията");
    return decision;
  }

  /**
   * Редовете на код 92 за трета категория, кохорта WITH_UPF — със СТАВКИТЕ ОТ SEED-А за 2026 г.
   *
   * <p>⚠️ Резолверът се вика ИСТИНСКИ върху тях: сумите отдолу са негова аритметика, не преписани
   * числа. Верността на самите редове спрямо {@code bg_insurance_contribution.csv} се пази от
   * {@code BgSeafarerContributionManifestTest} и {@code BgPaySlipContributionWiringTest} — тук
   * нарочно не се преписва техният филтър по ключа, за да няма трето копие, което да се разминава.
   */
  private static List<BgInsuranceContribution> seafarerRows() {
    List<BgInsuranceContribution> rows = new ArrayList<>();
    rows.add(row("IC-128-2026", "PENSION", "14.8000"));
    rows.add(row("IC-129-2026", "UPF", "5.0000"));
    rows.add(row("IC-130-2026", "OZM", "3.5000"));
    rows.add(row("IC-132-2026", "TZPB", "1.1000"));
    rows.add(row("IC-147-2026", "HEALTH", "8.0000"));
    return rows;
  }

  /** Всичките на ЛИЦЕТО: чл. 4а ал. 1 КСО — работодателят само удържа, ставката му е нула. */
  private static BgInsuranceContribution row(String importId, String fund, String employeeRate) {
    BgInsuranceContribution c = new BgInsuranceContribution();
    c.setImportId(importId);
    c.setFundCode(fund);
    c.setEmployerRate(new BigDecimal("0.0000"));
    c.setBgEmployerRateKind("STRUCTURAL_ZERO");
    c.setEmployeeRate(new BigDecimal(employeeRate));
    c.setBgEmployeeRateKind("VALUE");
    return c;
  }

  private static BgContributionResult seafarerContributions() {
    BgContributionResult result =
        BgContributionResolver.resolve(
            seafarerRows(),
            new BgContributionRequest(
                BASE, "92", "3", true, "DEFAULT", null, DATE, null, "STANDARD", "NON_TEACHER"));
    assertEquals(
        BgContributionResult.Status.RESOLVED,
        result.getStatus(),
        "котвата: резолверът трябва да е доволен от редовете — " + result.getDetail());
    return result;
  }

  private static String render(BgPayrollWorkTime.Decision workTime, boolean prorates) {
    BgContributionPreviewController controller = new BgContributionPreviewController();
    boolean blocked = workTime.blocks(prorates);
    return controller.format(
        new Describing(),
        BgMinIncomeResult.notApplicable("вид осигурен: 92"),
        BASE,
        blocked ? null : BgInsurableIncomeResult.resolved(BASE, CAP, LocalDate.of(2026, 8, 1)),
        new BigDecimal("1.1000"),
        "19.10",
        "19",
        null,
        "81212001",
        true,
        "DEFAULT",
        "92",
        "3",
        workTime,
        prorates,
        blocked ? null : seafarerContributions());
  }

  // ═══ 1 · КОД 92 — ЕКРАНЪТ ВЕЧЕ НЕ ОТКАЗВА ═════════════════════════════════

  @Test
  @DisplayName("⚠️ СВИДЕТЕЛ · вид 92 с непопълнени часове → екранът СМЯТА, а не отказва")
  void theSeafarerScreenNoLongerRefusesOverUnpopulatedContractHours() {
    String out = render(refusedDecision(), false);

    assertFalse(
        out.contains("═══ ОТКАЗ ═══"),
        "⚠️ ДЕФЕКТЪТ, ДОСЛОВНО: екранът връщаше блока „ОТКАЗ“ и спираше, защото bgHoursPerDay не е"
            + " попълнено — при вид осигурен, чийто праг НЕ се пропорционира.\n"
            + out);
    assertFalse(
        out.contains("нито МОД, нито база, нито вноски"),
        "⚠️ Веригата вече не спира преди резолвера.\n" + out);

    // ── и наистина СТИГА до трите раздела ──────────────────────────────────
    assertTrue(out.contains("═══ МОД (минимален осигурителен доход) ═══"), out);
    assertTrue(out.contains("═══ Осигурителна база ═══"), out);
    assertTrue(out.contains("═══ Осигурителни вноски ═══"), out);
    assertTrue(
        out.contains("Използвана осигурителна база: 620.20"),
        "прагът за СОЛ се дължи ЦЯЛ — морското лице няма работно време, на което да е част\n"
            + out);

    // ── петте реда, всичките на ЛИЦЕТО, и общата сума ──────────────────────
    for (String fund : Arrays.asList("PENSION", "UPF", "OZM", "TZPB", "HEALTH")) {
      assertTrue(out.contains(fund), "липсва ред за фонд " + fund + "\n" + out);
    }
    // ⚠️ „| работодател“ е КОЛОНАТА ПЛАТЕЦ на ред от таблицата, не думата където и да е: редът
    // „Общо работодател: 0“ трябва да остане и той съдържа същата дума.
    assertFalse(
        out.contains("| работодател"),
        "⚠️ чл. 4а ал. 1 КСО — вноските са ИЗЦЯЛО за сметка на лицето, тоест ред на работодателя не"
            + " бива да се появи изобщо. ЗОВ на работодателя е STRUCTURAL_ZERO по ЗЗО чл. 40 ал. 1"
            + " т. 2а, а чл. 4а ал. 7 му дава само УДЪРЖАНЕТО.\n"
            + out);
    assertTrue(out.contains("Общо лице: 200.95"), "32,40% от 620,20\n" + out);
    assertTrue(out.contains("Общо работодател: 0"), out);

    // ── ЛИПСАТА В ДАННИТЕ ОСТАВА ВИДИМА, само че не блокира ────────────────
    assertTrue(
        out.contains("Режим на работното време: UNKNOWN"),
        "режимът си е неразпознат и екранът го казва\n" + out);
    assertTrue(
        out.contains("НЕПРИЛОЖИМА"),
        "⚠️ Пропорцията е обявена за неприложима, а не премълчана.\n" + out);
    assertTrue(
        out.contains("bgHoursPerDay"),
        "⚠️ И ВЪПРЕКИ ТОВА казва КОЕ поле по договора стои непопълнено. Скриването на истинска липса"
            + " зад верен резултат е точно тихото поведение, което отказите тук съществуват да"
            + " заменят.\n"
            + out);
  }

  // ═══ 2 · РЕГРЕСИЯ — ЛЕГИТИМНИЯТ ГЕЙТ Е ЖИВ ════════════════════════════════

  @Test
  @DisplayName("⚠️ РЕГРЕСИЯ · пропорциониращ вид с непопълнени часове → ВСЕ ОЩЕ отказва")
  void aProratingTypeStillRefusesOnTheUnknownRegime() {
    BgPayrollWorkTime.Decision refused = refusedDecision();
    String out = render(refused, true);

    assertTrue(
        out.contains("═══ ОТКАЗ ═══"),
        "⚠️ За нормално трудово правоотношение липсващият режим ТРЯБВА да спира екрана: ал. 3 и ал. 4"
            + " по НЕВД чл. 1 дават различно число за един и същ месец.\n"
            + out);
    assertTrue(out.contains("нито МОД, нито база, нито вноски се смятат"), out);
    assertTrue(
        out.contains(BgPayrollWorkTime.REFUSALS.get("unknownRegime")),
        "причината идва ДОСЛОВНО от REFUSALS, не преразказана\n" + out);
    assertFalse(
        out.contains("═══ Осигурителни вноски ═══"),
        "нищо след отказа не бива да се смята\n" + out);
    assertFalse(out.contains("НЕПРИЛОЖИМА"), "пропорцията Е приложима за такъв вид\n" + out);
  }
}
