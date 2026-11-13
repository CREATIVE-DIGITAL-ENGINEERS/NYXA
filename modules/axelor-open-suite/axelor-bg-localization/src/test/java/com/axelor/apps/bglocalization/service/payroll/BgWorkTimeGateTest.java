package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.BgInsurableIncomeResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeService;
import com.axelor.apps.bglocalization.service.BgWorkTimeFacts;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ РАБОТНО-ВРЕМЕВИЯТ ОТКАЗ ГЕЙТВА САМО ТАМ, КЪДЕТО ПАЗИ НЕЩО.
 *
 * <p><b>Какво заковава този тест.</b> Отказът на {@link BgPayrollWorkTime#decide} не се чете ГОЛ —
 * {@code if (workTime.isRefused())} — нито в {@code BgPaySlipService.contributions}, нито в {@code
 * BgContributionPreviewController}. Иначе фиш на МОРСКО ЛИЦЕ (код 92) би се отказал, защото {@code
 * EmploymentContract.bgHoursPerDay} не е попълнено по договора. А код 92 носи {@code bgZeroWorkTime
 * = true}: {@code BgInsurableIncomeService} му дава {@code prorates = false} и прагът се дължи ЦЯЛ.
 * Такъв гейт би пазил избор между ал. 3 и ал. 4 по НЕВД чл. 1, който за този вид ИЗОБЩО НЕ СЕ ПРАВИ
 * — би блокирал заради вход, от който резултатът не зависи.
 *
 * <p><b>Как изглежда.</b> Диагностичният екран за вид 92 / категория 3 / брутно 620,20 би върнал
 * „ОТКАЗ: режимът на работното време не е разпознат“ и нито МОД, нито база, нито вноски. Засетите
 * договори имат {@code bg_hours_per_day = 0.00}, тоест отказът би важал за всеки код.
 *
 * <p><b>Какво се твърди тук и какво — другаде.</b> Тук е ПРАВИЛОТО ({@link
 * BgPayrollWorkTime.Decision#blocks}) и доказателството, че пропускането е БЕЗОПАСНО — тоест че при
 * {@code prorates = false} наборът факти наистина не влиза в сметката с нито едно число. Числата на
 * истински фиш са в {@code BgPaySlipContributionWiringTest}, а екранът — в {@code
 * BgContributionPreviewGateTest}.
 */
class BgWorkTimeGateTest {

  /** Минималният осигурителен доход за СОЛ от 01.08.2026 — прагът на морското лице. */
  private static final BigDecimal FLOOR = new BigDecimal("620.20");

  /** ЗБДОО чл. 9 т. 1 б. „в“ — максималният осигурителен доход. */
  private static final BigDecimal CAP = new BigDecimal("2111.64");

  private static final int WORKING_DAYS = 21;

  /**
   * Вход БЕЗ договорени часове на ден — точно състоянието на всеки засет договор ({@code
   * bg_hours_per_day = 0.00}, което сглобителят чете като {@code null}).
   *
   * <p>Всичко останало е налице: знаменател, часове и дни. Липсва САМО режимът.
   */
  private static BgPayrollInput withoutContractHours() {
    return BgPayrollInput.builder()
        .contractHoursPerDay(null)
        .workingDaysInPeriod(WORKING_DAYS)
        .workedHours(new BigDecimal("40.00"))
        .workedDays(10)
        .build();
  }

  /** Същият вход, но с пълно работно време — режимът се разпознава и отказ няма. */
  private static BgPayrollInput fullTime() {
    return BgPayrollInput.builder()
        .contractHoursPerDay(new BigDecimal("8.00"))
        .workingDaysInPeriod(WORKING_DAYS)
        .workedHours(new BigDecimal("168.00"))
        .workedDays(WORKING_DAYS)
        .build();
  }

  // ═══ 1 · ПРАВИЛОТО ════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ СВИДЕТЕЛ · отказът блокира при prorates=true и НЕ блокира при prorates=false")
  void theRefusalOnlyBlocksWhenTheFloorActuallyProrates() {
    BgPayrollWorkTime.Decision refused = BgPayrollWorkTime.decide(withoutContractHours());

    // Котвата: това наистина Е отказът от репродукцията, а не някакво друго състояние.
    assertTrue(refused.isRefused(), "котвата: вход без договорени часове трябва да е ОТКАЗ");
    assertEquals(BgPayrollWorkTime.Regime.UNKNOWN, refused.regime);
    assertEquals(BgPayrollWorkTime.REFUSALS.get("unknownRegime"), refused.refusal);
    assertTrue(refused.refusal.contains("bgHoursPerDay"), refused.refusal);

    assertTrue(
        refused.blocks(true),
        "⚠️ ЗА НОРМАЛНО трудово правоотношение липсващият режим ТРЯБВА да спира. Двата"
            + " реда по НЕВД чл. 1 дават различно число за един и същ месец — 40/168 = 0,2381 срещу"
            + " 10/21 = 0,4762 — тоест избор по подразбиране е праг наужким.");

    assertFalse(
        refused.blocks(false),
        "⚠️ ПРИ bgZeroWorkTime прагът се дължи ЦЯЛ и режимът не участва в сметката с нито"
            + " едно число. Отказ заради вход, от който резултатът НЕ зависи, не пази нищо — само"
            + " блокира фиша на морското лице (код 92).");
  }

  @Test
  @DisplayName("⚠️ Разпознат режим не блокира при никоя от двете стойности")
  void aRecognisedRegimeNeverBlocks() {
    BgPayrollWorkTime.Decision fine = BgPayrollWorkTime.decide(fullTime());

    assertFalse(fine.isRefused(), "котвата: пълно работно време с отчет не е отказ");
    assertFalse(fine.blocks(true));
    assertFalse(
        fine.blocks(false),
        "blocks() не бива да ИЗМИСЛЯ отказ: той е свойство на решението, а prorates само го условява");
  }

  // ═══ 2 · ЗАЩО ПРОПУСКАНЕТО Е БЕЗОПАСНО ════════════════════════════════════

  @Test
  @DisplayName("⚠️ factsFor(false) дава набор, който не чупи сметката — null при отказ би я счупил")
  void theFactsAreNeverNullWhenTheGateIsSkipped() {
    BgPayrollWorkTime.Decision refused = BgPayrollWorkTime.decide(withoutContractHours());

    assertNull(refused.facts, "котвата: при отказ самото решение НЯМА факти");
    assertNotNull(
        refused.factsFor(false),
        "⚠️ Пропуснатият гейт продължава към резолвера, а той получава набора като аргумент. Гол"
            + " workTime.facts тук е null и подмяната съществува точно за да не тръгне null нататък.");

    BgPayrollWorkTime.Decision fine = BgPayrollWorkTime.decide(fullTime());
    assertSame(
        fine.facts,
        fine.factsFor(true),
        "при пропорциониращ вид се подават СОБСТВЕНИТЕ факти на решението, не заместител");
  }

  @Test
  @DisplayName(
      "⚠️ СВИДЕТЕЛ · при prorates=false наборът факти НЕ влиза в сметката — праг 620,20 цял")
  void theWorkTimeIsTrulyIrrelevantWhenTheFloorDoesNotProrate() {
    // ⚠️ ИСТИНСКАТА аритметика на BgInsurableIncomeService, не преразказана тук. Това е проверката,
    // която прави пропускането на гейта безопасно: ако наборът факти влизаше някъде при
    // prorates=false, това число нямаше да е 620,20.
    BgWorkTimeFacts shrinking =
        BgWorkTimeFacts.byHours(new BigDecimal("168"), new BigDecimal("40"), null);

    BgInsurableIncomeResult whole =
        BgInsurableIncomeService.compute(BigDecimal.ZERO, null, FLOOR, false, CAP, null, shrinking);
    assertTrue(whole.isResolved(), String.valueOf(whole.getDetail()));
    assertEquals(
        0,
        FLOOR.compareTo(whole.getAmount()),
        "⚠️ Факти, които БИХА свили прага до 40/168 от него, не го пипат при prorates=false. Точно"
            + " затова работно-времевият отказ няма какво да пази за такъв вид осигурен.");

    // ── КОНТРОЛАТА: същите факти С пропорция наистина свиват прага ──────────
    BgInsurableIncomeResult prorated =
        BgInsurableIncomeService.compute(BigDecimal.ZERO, null, FLOOR, true, CAP, null, shrinking);
    assertTrue(prorated.isResolved(), String.valueOf(prorated.getDetail()));
    assertEquals(
        0,
        new BigDecimal("147.67").compareTo(prorated.getAmount()),
        "⚠️ БЕЗ ТАЗИ КОНТРОЛА горното твърдение щеше да мине и с аритметика, която не пропорционира"
            + " НИКОГА. 620,20 × 40/168 = 147,666… → 147,67 по НЕВД чл. 1 ал. 4.");

    // ── и целият път: отказано решение + prorates=false → пак цял праг ──────
    BgPayrollWorkTime.Decision refused = BgPayrollWorkTime.decide(withoutContractHours());
    BgInsurableIncomeResult afterSkippedGate =
        BgInsurableIncomeService.compute(
            BigDecimal.ZERO, null, FLOOR, false, CAP, null, refused.factsFor(false));
    assertTrue(
        afterSkippedGate.isResolved(),
        "⚠️ Пропуснатият гейт трябва да стигне до РЕШЕНА база, а не до нов отказ по-надолу: "
            + afterSkippedGate.getDetail());
    assertEquals(0, FLOOR.compareTo(afterSkippedGate.getAmount()));
  }

  // ═══ 3 · ГЕЙТЪТ НЕ СЕ ЧЕТЕ ГОЛ НИКЪДЕ ════════════════════════════════════

  private static final Path MAIN = Paths.get("src/main/java");

  private static final String WORK_TIME = "BgPayrollWorkTime.java";

  /**
   * ⚠️ ИНВАРИАНТ: {@code Decision.isRefused()} НЕ се чете като гейт извън {@code
   * BgPayrollWorkTime}.
   *
   * <p><b>Защо тест върху сорса.</b> Твърдението е за СВЪРЗАНОСТ на правилото с местата, които го
   * ползват. Поведенчески тест на един от двата пътя минава зелен, докато другият път още чете
   * отказа гол: {@code BgPaySlipContributionWiringTest} е зелен с фикстура от 8 часа на ден, дори
   * ако реалният фиш с непопълнени часове се блокира. Роднини са {@code BgWorkTimeFactsReachTest} и
   * {@code BgInertSeamTest}.
   *
   * <p>⚠️ {@code BgPayrollInputBuilder.Refusal} също има {@code isRefused()} — друг тип, друг
   * въпрос. Затова проверката хваща само файлове, които наистина работят с {@code
   * BgPayrollWorkTime}.
   */
  @Test
  @DisplayName(
      "⚠️ ИНВАРИАНТ · всеки повикващ минава през blocks(prorates), не през гол isRefused()")
  void noCallerReadsTheRefusalWithoutTheProratesCondition() throws IOException {
    List<Path> sources;
    try (Stream<Path> files = Files.walk(MAIN)) {
      sources = files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
    assertTrue(
        sources.size() > 50,
        "котвата: очаквах десетки файла в src/main/java, намерих " + sources.size());

    List<String> bare = new ArrayList<>();
    List<String> gated = new ArrayList<>();
    for (Path file : sources) {
      String name = file.getFileName().toString();
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      boolean mentionsWorkTime = String.join("\n", lines).contains("BgPayrollWorkTime");
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        String trimmed = line.trim();
        // ⚠️ Само КОД: ред, който е коментар или javadoc, не е повикващ — инак изречението „четеше
        // се гол isRefused()“ в самия javadoc щеше да вали този тест.
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
          continue;
        }
        if (line.contains(".blocks(")) {
          gated.add(name + ":" + (i + 1));
        }
        if (mentionsWorkTime && !WORK_TIME.equals(name) && line.contains(".isRefused()")) {
          bare.add(name + ":" + (i + 1) + "  " + trimmed);
        }
      }
    }

    if (!bare.isEmpty()) {
      fail(
          "РАБОТНО-ВРЕМЕВИЯТ ОТКАЗ СЕ ЧЕТЕ ГОЛ:\n  "
              + String.join("\n  ", bare)
              + "\n\n  ⚠️ Безусловен гейт блокира фиша на всеки"
              + " вид осигурен с bgZeroWorkTime (ДУК 10, самоосигуряващи се 12/13/22, морско лице"
              + " 92) заради непопълнено EmploymentContract.bgHoursPerDay — вход, от който сметката"
              + " им НЕ зависи.\n"
              + "  Правилното: BgPayrollWorkTime.Decision#blocks(prorates), а prorates идва от"
              + " BgInsurableIncomeService#proratesWithWorkTime — СЪЩИЯТ прочит на"
              + " BgInsuredType.bgZeroWorkTime, с който резолверът после смята.");
    }

    // Котва в другата посока: инвариантът да не мине зелен, защото никой вече не пита изобщо.
    assertTrue(
        gated.size() >= 2,
        "⚠️ Очаквах поне ДВА условени гейта — реалният фиш (BgPaySlipService) и диагностичният екран"
            + " (BgContributionPreviewController). Намерени: "
            + gated
            + "\n  Нула или един значи, че някой е махнал гейт, вместо да го условѝ — а махнатият"
            + " гейт е точно тихото поведение, което отказът съществува да замени.");

    List<String> files =
        gated.stream()
            .map(s -> s.substring(0, s.indexOf(':')))
            .distinct()
            .collect(Collectors.toList());
    assertTrue(
        files.containsAll(
            Arrays.asList("BgPaySlipService.java", "BgContributionPreviewController.java")),
        "⚠️ И ДВАТА пътя трябва да носят условения гейт — реалният фиш и preview екранът. Намерени: "
            + files
            + "\n  Само единият значи, че условието е стигнало до диагностиката, но не до фиша (или"
            + " обратното) — тоест морското лице пак е блокирано там, където има значение.");
  }
}
