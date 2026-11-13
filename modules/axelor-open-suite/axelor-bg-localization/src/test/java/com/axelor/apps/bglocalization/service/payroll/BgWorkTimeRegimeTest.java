package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.BgInsurableIncomeResult;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeService;
import com.axelor.apps.bglocalization.service.BgWorkTimeFacts;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ РЕДЪТ ПО НЕВД ЧЛ. 1 СЕ ИЗБИРА ПО КРИТЕРИЙ, А НЕ ПО НАЛИЧНОСТ НА ДАННИ.
 *
 * <p><b>Какво е грешно.</b> Да се избира часовият ред „когато има часови данни“, а дневният —
 * „когато часовете са неизползваеми“, е избор по това какво е попълнено. Нормата дава КРИТЕРИЙ: ал.
 * 4 важи заради РЕЖИМА на работното време (чл. 138 ал. 2 КТ), а ал. 3 — за пълното работно време
 * при неотработени всички дни.
 *
 * <p><b>Разликата се мери в пари, не в педантизъм.</b> Пълно работно време, 176 отчетени часа при
 * 168 законоустановени и 10 отработени от 21 работни дни: изборът по наличност дава 176/168 →
 * ограничено до 1 → ПЪЛЕН праг 550,66 при десет отработени дни. Верният отговор по ал. 3 е 10/21 →
 * 262,22. Завишение от 288,44 евро, и то без нито един червен ред. Заковано долу с точна сума.
 *
 * <p><b>Защо не {@code workTimeMode}.</b> Селекцията се ЧЕТЕ ОТ ДОМЕЙНА в този тест, не се
 * предполага: тя носи чл. 142 КТ (подневно отчитане / СИРВ), а ал. 4 пита за чл. 138 ал. 2 КТ
 * (непълно работно време). Нито една от стойностите ѝ не се съпоставя с „пълно“ или „непълно“ —
 * заковано, за да не се приема на доверие и за да падне, ако някой добави такава стойност.
 */
class BgWorkTimeRegimeTest {

  private static final Path SELECTS = Paths.get("src/main/resources/views/Selects.xml");

  private static final String SELECTION = "bg.work.time.mode.select";

  /**
   * МОД за самоосигуряващите се до 31.07.2026 — същата стойност като в
   * BgInsurableIncomeServiceTest.
   */
  private static final BigDecimal FLOOR = new BigDecimal("550.66");

  private static final BigDecimal CAP = new BigDecimal("2111.64");

  /** 21 работни дни в периода — март 2026 без 03.03. */
  private static final int WORKING_DAYS = 21;

  // ═══ СРЕДАТА ══════════════════════════════════════════════════════════════

  /**
   * Вход, построен ПРЯКО от строителя на контракта.
   *
   * <p>Нарочно не през сглобителя: тук се проверява ПРАВИЛОТО, а не четенето от базата. Сглобителят
   * си има свой тест и подмяна на шевовете там би внесла втора причина за червено.
   */
  private static BgPayrollInput input(
      String contractHoursPerDay, Integer workingDays, String workedHours, Integer workedDays) {
    return input(contractHoursPerDay, null, workingDays, workedHours, workedDays);
  }

  /** Както горното, но със ДОГОВОРЕНИ ДНИ НА СЕДМИЦА — вторият път към непълното работно време. */
  private static BgPayrollInput input(
      String contractHoursPerDay,
      String contractDaysPerWeek,
      Integer workingDays,
      String workedHours,
      Integer workedDays) {
    return BgPayrollInput.builder()
        .contractHoursPerDay(
            contractHoursPerDay == null ? null : new BigDecimal(contractHoursPerDay))
        .contractDaysPerWeek(
            contractDaysPerWeek == null ? null : new BigDecimal(contractDaysPerWeek))
        .workingDaysInPeriod(workingDays)
        .workedHours(workedHours == null ? null : new BigDecimal(workedHours))
        .workedDays(workedDays)
        .build();
  }

  private static BigDecimal prorated(BgWorkTimeFacts facts) {
    BgInsurableIncomeResult result =
        BgInsurableIncomeService.compute(BigDecimal.ZERO, null, FLOOR, true, CAP, null, facts);
    assertTrue(result.isResolved(), String.valueOf(result.getDetail()));
    return result.getAmount();
  }

  // ═══ СЕЛЕКЦИЯТА, ПРОЧЕТЕНА ОТ ДОМЕЙНА ════════════════════════════════════

  /**
   * Стойностите на селекцията, както са ЗАПИСАНИ в {@code Selects.xml}.
   *
   * <p>Липсващият файл ПАДА, не се пропуска: пропуснатата проверка изглежда точно като минала.
   */
  private static Map<String, String> workTimeModeOptions() throws IOException {
    assertTrue(
        Files.isRegularFile(SELECTS),
        "не намирам "
            + SELECTS.toAbsolutePath()
            + "\n  ⚠️ Пътят е спрямо директорията на модула — проверете от къде се пуска тестът.");
    Map<String, String> options = new LinkedHashMap<>();
    boolean inside = false;
    Pattern option = Pattern.compile("<option\\s+value=\"([^\"]*)\"\\s*>([^<]*)</option>");
    for (String line : Files.readAllLines(SELECTS, StandardCharsets.UTF_8)) {
      if (line.contains("name=\"" + SELECTION + "\"")) {
        inside = true;
        continue;
      }
      if (inside) {
        if (line.contains("</selection>")) {
          break;
        }
        Matcher m = option.matcher(line.trim());
        if (m.find()) {
          options.put(m.group(1), m.group(2).trim());
        }
      }
    }
    // ⚠️ КОТВАТА: без нея счупен regex или преместена селекция дават празна карта, а всяко
    // твърдение
    // от вида „нито една стойност не значи непълно работно време“ минава зелено над нищо.
    assertFalse(
        options.isEmpty(),
        "котвата: не прочетох нито една стойност на "
            + SELECTION
            + " — regex-ът или пътят е счупен");
    return options;
  }

  @Test
  @DisplayName("⚠️ bgWorkTimeMode носи чл. 142 КТ (отчитане), НЕ чл. 138 ал. 2 КТ (непълно време)")
  void theWorkTimeModeSelectionCarriesTheReportingRegimeNotThePartTimeFlag() throws IOException {
    Map<String, String> options = workTimeModeOptions();

    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("1", "Подневно отчитане");
    expected.put("2", "Сумирано изчисляване (СИРВ)");
    assertEquals(
        expected,
        options,
        "СЕЛЕКЦИЯТА "
            + SELECTION
            + " СЕ Е ПРОМЕНИЛА.\n  ⚠️ Заковано е нарочно: докато стойностите ѝ са „подневно“ и"
            + " „сумирано“, полето НЕ може да е дискриминаторът между НЕВД чл. 1 ал. 3 и ал. 4 —"
            + " то отговаря на чл. 142 КТ (режим на ОТЧИТАНЕ), а ал. 4 пита за чл. 138 ал. 2 КТ"
            + " (НЕПЪЛНО работно време). Двете са ортогонални: договор на непълно работно време може"
            + " да е и подневен, и сумиран.\n  ⚠️ Ако тук се е появила стойност, която ОЗНАЧАВА"
            + " непълно работно време, тогава полето става дискриминатор и BgPayrollWorkTime.regimeOf"
            + " трябва да се пренапише — не обновявайте само този списък.");

    // И същинското: нито една стойност не се съпоставя с режим по КТ.
    for (Map.Entry<String, String> option : options.entrySet()) {
      String title = option.getValue().toLowerCase();
      if (title.contains("непълно") || title.contains("пълно")) {
        fail(
            "стойността „"
                + option.getKey()
                + " = "
                + option.getValue()
                + "“ говори за ПЪЛНОТА на работното време, тоест селекцията вече носи признака и"
                + " regimeOf трябва да го чете оттам, а не от договорените часове");
      }
    }
  }

  @Test
  @DisplayName("⚠️ Режимът НЕ зависи от bgWorkTimeMode — зависи от договорените часове на ден")
  void theRegimeComesFromTheContractedHoursNotFromTheReportingMode() {
    // Един и същ договор по работно време, различен режим на ОТЧИТАНЕ → един и същ ред по НЕВД.
    // workTimeMode дори не участва в решението, затова се подава и в двата случая, и в нито един.
    BgPayrollInput daily =
        BgPayrollInput.builder()
            .contractHoursPerDay(new BigDecimal("4.00"))
            .workTimeMode("1")
            .workingDaysInPeriod(WORKING_DAYS)
            .workedHours(new BigDecimal("40.00"))
            .build();
    BgPayrollInput summed =
        BgPayrollInput.builder()
            .contractHoursPerDay(new BigDecimal("4.00"))
            .workTimeMode("2")
            .workingDaysInPeriod(WORKING_DAYS)
            .workedHours(new BigDecimal("40.00"))
            .build();
    BgPayrollInput noMode =
        BgPayrollInput.builder()
            .contractHoursPerDay(new BigDecimal("4.00"))
            .workingDaysInPeriod(WORKING_DAYS)
            .workedHours(new BigDecimal("40.00"))
            .build();

    for (BgPayrollInput candidate : new BgPayrollInput[] {daily, summed, noMode}) {
      assertEquals(
          BgPayrollWorkTime.Regime.PART,
          BgPayrollWorkTime.regimeOf(candidate),
          "режимът на ОТЧИТАНЕ не мени режима на работното време");
      assertTrue(BgPayrollWorkTime.decide(candidate).facts.hasHourData(), "все ал. 4");
    }

    // Обратното: същият режим на отчитане, различно договорено работно време → РАЗЛИЧЕН ред.
    BgPayrollInput fullTime =
        BgPayrollInput.builder()
            .contractHoursPerDay(new BigDecimal("8.00"))
            .workTimeMode("1")
            .workingDaysInPeriod(WORKING_DAYS)
            .workedHours(new BigDecimal("40.00"))
            .workedDays(5)
            .build();
    assertEquals(BgPayrollWorkTime.Regime.FULL, BgPayrollWorkTime.regimeOf(fullTime));
    assertTrue(BgPayrollWorkTime.decide(fullTime).facts.hasDayData(), "ал. 3, макар часове да ИМА");
  }

  // ═══ (а) ПЪЛНО ВРЕМЕ: ДНЕВНИЯТ РЕД, ДОРИ КОГАТО ЧАСОВЕТЕ СА НАД НОРМАТА ═══

  @Test
  @DisplayName("⚠️ (а) ПЪЛНО време, 176 ч при 168 и 10 от 21 дни → 10/21, а НЕ 176/168")
  void atFullTimeTheDayRuleWinsEvenWhenTheHoursExceedTheStatutoryMonth() {
    BgPayrollInput input = input("8.00", WORKING_DAYS, "176.00", 10);

    assertEquals(BgPayrollWorkTime.Regime.FULL, BgPayrollWorkTime.regimeOf(input));
    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(input);
    assertFalse(decision.isRefused(), String.valueOf(decision.refusal));

    assertTrue(
        decision.facts.hasDayData(),
        "ВЗЕТ Е ЧАСОВИЯТ РЕД ПРИ ПЪЛНО РАБОТНО ВРЕМЕ. Ал. 4 се прилага заради РЕЖИМА (чл. 138 ал. 2"
            + " КТ), не заради това, че часови данни има. При пълно работно време важи ал. 3.");
    assertFalse(decision.facts.hasHourData(), "наборът не твърди две неща наведнъж");
    assertEquals(new BigDecimal("21"), decision.facts.getWorkingDaysInMonth());
    assertEquals(new BigDecimal("10"), decision.facts.getWorkedDays());

    // Точната сума: 550,66 × 10/21 = 262,22.
    assertEquals(
        new BigDecimal("262.22"),
        prorated(decision.facts),
        "10/21 = 0,4761904762 → 550,66 × 0,4761904762 = 262,22");

    // ⚠️ И КАКВО БИ ДАЛ ИЗБОРЪТ ПО НАЛИЧНОСТ — заковано тук, за да се вижда цената му. 176/168 =
    // 1,0476, ограничено до 1 от BgInsurableIncomeService.prorate, тоест ЦЯЛ праг при десет
    // отработени дни от двайсет и един.
    BgWorkTimeFacts oldChoice =
        BgWorkTimeFacts.byHours(new BigDecimal("168"), new BigDecimal("176.00"), null);
    assertEquals(
        FLOOR,
        prorated(oldChoice),
        "изборът по наличност дава ЦЕЛИЯ праг — таванът на коефициента от 1 скрива фактора над"
            + " единица, тоест прагът не излиза НАД пълния МОД, а точно пълния. Завишението спрямо"
            + " верните 262,22 е 288,44 евро и няма нито един червен ред, който да го каже.");
    assertEquals(
        new BigDecimal("288.44"),
        FLOOR.subtract(new BigDecimal("262.22")),
        "цената на грешката, изписана като число");
  }

  // ═══ (б) НЕПЪЛНО ВРЕМЕ: ЧАСОВИЯТ РЕД ДОРИ ПРИ НЕОТРАБОТЕНИ ДНИ ═══════════

  @Test
  @DisplayName(
      "⚠️ (б) НЕПЪЛНО време и неотработени всички дни → пак ал. 4, по последното ѝ изречение")
  void atPartTimeTheHourRuleAppliesEvenWhenNotAllDaysWereWorked() {
    // Договор от 4 часа дневно; отработени 40 часа за 10 от 21 работни дни, тоест НЕ всички дни.
    BgPayrollInput input = input("4.00", WORKING_DAYS, "40.00", 10);

    assertEquals(BgPayrollWorkTime.Regime.PART, BgPayrollWorkTime.regimeOf(input));
    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(input);
    assertFalse(decision.isRefused(), String.valueOf(decision.refusal));

    assertTrue(
        decision.facts.hasHourData(),
        "ПРЕХВЪРЛЕНО КЪМ АЛ. 3 ЗАРАДИ НЕОТРАБОТЕНИ ДНИ. Последното изречение на ал. 4 казва точно"
            + " обратното: „По този ред се определя и минималният месечен осигурителен доход, когато"
            + " лицето … НЕ Е ОТРАБОТИЛО ВСИЧКИ РАБОТНИ ДНИ ПРЕЗ МЕСЕЦА.“");
    assertFalse(decision.facts.hasDayData());

    // ⚠️ ДЕЛИТЕЛЯТ Е ЗАКОНОУСТАНОВЕН, НЕ ДОГОВОРЕН: 21 × 8 = 168, а НЕ 21 × 4 = 84.
    assertEquals(
        new BigDecimal("168"),
        decision.facts.getStatutoryMonthlyHours(),
        "договорените часове определят РЕЖИМА и нищо друго. Влезели в делителя, 40/84 = 0,4762"
            + " щеше да е двойно на верните 40/168 = 0,2381 — тоест пропорцията изчезва точно за"
            + " случая, за който съществува");
    assertEquals(
        new BigDecimal("262.22"),
        FLOOR.multiply(new BigDecimal("10")).divide(new BigDecimal("21"), 2, RoundingMode.HALF_UP),
        "колкото БИ дал дневният ред — за сравнение");
    assertEquals(
        new BigDecimal("131.11"),
        prorated(decision.facts),
        "40/168 = 0,2380952381 → 550,66 × 0,2380952381 = 131,11. Дневният ред щеше да даде 262,22,"
            + " тоест точно двойно — и това е разликата, която режимът решава");
  }

  @Test
  @DisplayName("⚠️ (б·2) НЕПЪЛНО време БЕЗ часове → ОТКАЗ, а НЕ дневен ред. Заковано измерване.")
  void atPartTimeWithoutAnyHoursTheAnswerIsARefusalNotTheDayRule() {
    // ⚠️ ТОВА Е ИЗМЕРЕНОТО И ЗАКОВАНОТО, с причината. Ал. 4 иска отработени ЧАСОВЕ и няма
    // изключение за липсващ отчет. Дневният ред НЕ е замяна: при 4-часов договор 10 от 21 дни дава
    // 0,4762, а верният коефициент по часове е 0,2381 — тоест падането към ал. 3 удвоява прага.
    // Затова липсата се обявява, вместо да се замести с правдоподобно число.
    BgPayrollInput input = input("4.00", WORKING_DAYS, null, 10);

    assertEquals(BgPayrollWorkTime.Regime.PART, BgPayrollWorkTime.regimeOf(input));
    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(input);

    assertTrue(
        decision.isRefused(),
        "ПРИ НЕПЪЛНО РАБОТНО ВРЕМЕ БЕЗ ОТРАБОТЕНИ ЧАСОВЕ Е ВЗЕТО РЕШЕНИЕ, вместо да се откаже."
            + " Ал. 4 иска часове и не допуска дневния ред като замяна.");
    assertNull(decision.facts, "при отказ няма факти — инак някой ще ги ползва");
    assertEquals(
        BgPayrollWorkTime.REFUSALS.get("partTimeWithoutHours"),
        decision.refusal,
        "текстът на отказа е ПРИЧИНАТА от списъка, не свободно изречение на мястото на извикването");
    assertTrue(BgPayrollWorkTime.describe(decision).startsWith("ОТКАЗ: "), "и се чете от човек");
  }

  // ═══ (в) НЕРАЗПОЗНАТ РЕЖИМ → ОТКАЗ ═══════════════════════════════════════

  @Test
  @DisplayName("⚠️ (в) Липсващ или неразпознат режим → ОТКАЗ с назована липса, не изчисление")
  void anUnknownRegimeRefusesInsteadOfPickingARule() {
    // Всичко останало е налице: знаменател, часове И дни. Липсва САМО договореното работно време.
    BgPayrollInput unknown = input(null, WORKING_DAYS, "84.00", 11);

    assertEquals(BgPayrollWorkTime.Regime.UNKNOWN, BgPayrollWorkTime.regimeOf(unknown));
    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(unknown);

    assertTrue(
        decision.isRefused(),
        "НЕРАЗПОЗНАТ РЕЖИМ ПАДНА ПО ПОДРАЗБИРАНЕ НА ЕДИН ОТ ДВАТА РЕДА. И двата дават число, което"
            + " изглежда като отговор: по часове 84/168 = 0,5 → 275,33, по дни 11/21 = 0,5238 →"
            + " 288,44. Кое от двете е вярно зависи от режима, а той не се знае — тоест изборът е"
            + " праг наужким.");
    assertNull(decision.facts);
    assertEquals(BgPayrollWorkTime.REFUSALS.get("unknownRegime"), decision.refusal);
    assertTrue(
        decision.refusal.contains("bgHoursPerDay"), "казва КОЕ поле липсва: " + decision.refusal);
    assertTrue(decision.refusal.contains("bgWorkTimeMode"), "и кое НЕ отговаря на въпроса");

    // Нула и отрицателно са същото незнание, не „много непълно работно време“.
    assertEquals(
        BgPayrollWorkTime.Regime.UNKNOWN,
        BgPayrollWorkTime.regimeOf(input("0.00", 21, "84.00", 11)));
    assertEquals(
        BgPayrollWorkTime.Regime.UNKNOWN,
        BgPayrollWorkTime.regimeOf(input("-4.00", 21, "84.00", 11)));
    assertEquals(BgPayrollWorkTime.Regime.UNKNOWN, BgPayrollWorkTime.regimeOf(null));
    assertTrue(
        BgPayrollWorkTime.decide(null).isRefused(), "и празният вход е отказ, не пълен месец");
    assertEquals(BgPayrollWorkTime.REFUSALS.get("noInput"), BgPayrollWorkTime.decide(null).refusal);
  }

  // ═══ ГРАНИЦИТЕ НА РЕЖИМА ═════════════════════════════════════════════════

  @Test
  @DisplayName("Границата е 8 часа: под нея НЕПЪЛНО, на нея и над нея ПЪЛНО (чл. 136а КТ при СИРВ)")
  void theBoundaryIsTheStatutoryDailyNorm() {
    assertEquals(
        BgPayrollWorkTime.Regime.PART, BgPayrollWorkTime.regimeOf(input("7.99", 21, "1", 1)));
    assertEquals(
        BgPayrollWorkTime.Regime.FULL, BgPayrollWorkTime.regimeOf(input("8.00", 21, "1", 1)));
    assertEquals(
        BgPayrollWorkTime.Regime.FULL,
        BgPayrollWorkTime.regimeOf(input("12.00", 21, "1", 1)),
        "при сумирано изчисляване дневната продължителност законно стига до 12 часа (чл. 136а КТ) —"
            + " това е ПЪЛНО работно време, не незнание и не „свръхпълно“");
    // ⚠️ 8 и 8.00 са едно и също число тук: сравнява се compareTo, не equals. BigDecimal.equals
    // различава мащаба и би направило „8.00 часа“ различно от „8 часа“ — тоест договор, попълнен с
    // два знака, щеше да се класифицира различно от същия договор с нула знака.
    assertEquals(BgPayrollWorkTime.Regime.FULL, BgPayrollWorkTime.regimeOf(input("8", 21, "1", 1)));
  }

  @Test
  @DisplayName("⚠️ (а) 8 ч × 3 дни СЪС отчет: режимът НЕПЪЛНО НЕ мени нито едно число")
  void atEightHoursADayReducedDaysChangeTheRuleButNotTheNumber() {
    // Договор 8 часа × 3 дни на седмица, 13 отработени дни от 21, тоест 13 × 8 = 104 часа.
    int workedDays = 13;
    BgPayrollInput reduced = input("8.00", "3.00", WORKING_DAYS, "104.00", workedDays);

    // Режимът е НЕПЪЛНО (чл. 138 ал. 1 КТ — намален брой ДНИ), тоест ал. 4.
    assertEquals(
        BgPayrollWorkTime.Regime.PART,
        BgPayrollWorkTime.regimeOf(reduced),
        "8 часа × 3 дни е НЕПЪЛНО работно време по чл. 138 ал. 1 КТ, макар часовете на ден да са"
            + " пълните");
    BgPayrollWorkTime.Decision after = BgPayrollWorkTime.decide(reduced);
    assertFalse(after.isRefused(), String.valueOf(after.refusal));
    assertTrue(after.facts.hasHourData(), "ал. 4");
    assertFalse(after.isAssumed(), "дните са попълнени — няма какво да се допуска");

    // ⚠️ Прочетен като ПЪЛНО работно време, същият договор би минал по ал. 3 по ДНИ — с точно
    // този набор.
    BgWorkTimeFacts before =
        BgWorkTimeFacts.byDays(new BigDecimal(WORKING_DAYS), new BigDecimal(workedDays), null);

    // ⚠️ И ДВЕТЕ ДАВАТ ЕДНО И СЪЩО ЧИСЛО. Това е ТВЪРДЕНИЕ, не надежда: при 8 часа на ден
    // отработените часове са точно 8 × отработените дни, а делителят е 8 × работните дни, тоест
    // 13/21 и 104/168 са един и същ коефициент.
    assertEquals(
        prorated(before),
        prorated(after.facts),
        "РЕЖИМЪТ СМЕНИ ЧИСЛО. Той сменя само ПЪТЯ НА „НЕ ЗНАМ“, не аритметиката: при наличен"
            + " отчет часовият и дневният ред дават един и същ праг, и ако това спре да е вярно,"
            + " режимът мени пари, а не само пътя.");
    assertEquals(
        new BigDecimal("340.88"), prorated(after.facts), "550,66 × 13/21 = 550,66 × 104/168");
    assertEquals(
        new BigDecimal("340.88"), prorated(before), "същата сума, изписана и от двете страни");
  }

  @Test
  @DisplayName("⚠️ (б) 8 ч × 3 дни БЕЗ отчет → ОТКАЗ, а НЕ цял праг")
  void atEightHoursADayReducedDaysWithoutATimesheetRefuses() {
    BgPayrollInput reduced = input("8.00", "3.00", WORKING_DAYS, null, 13);

    assertEquals(
        BgPayrollWorkTime.Regime.PART,
        BgPayrollWorkTime.regimeOf(reduced),
        "8 часа × 3 дни е НЕПЪЛНО работно време по чл. 138 ал. 1 КТ. Прочетено като ПЪЛНО, то поема"
            + " другия път на „не знам“: цял праг вместо отказ. Точно това е разликата, която"
            + " равенството на числата НЕ покрива.");
    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(reduced);

    assertTrue(
        decision.isRefused(),
        "ДОГОВОР 8 ЧАСА × 3 ДНИ БЕЗ ОТЧЕТ ПОЛУЧИ РЕШЕНИЕ, вместо отказ. Точно тук равенството на"
            + " числата НЕ важи: пълното работно време без отчет получава ЦЯЛ праг (ал. 3 важи само"
            + " при неотработени всички дни), а непълното — отказ (ал. 4 иска часове винаги). Тоест"
            + " прочетен като ПЪЛНО, такъв договор би получил 550,66 там, където нормата иска"
            + " отказ.");
    assertEquals(
        BgPayrollWorkTime.REFUSALS.get("partTimeWithoutHours"),
        decision.refusal,
        "и то ТОЧНО отказът за непълно работно време без часове");
    assertNull(decision.facts);

    // И контрастът, който показва че разликата е в ДНИТЕ, а не в нещо друго: СЪЩАТА липса на отчет,
    // но при ПЕТ дни на седмица, дава цял праг вместо отказ.
    // ⚠️ И отработените дни трябва да липсват, инак случаят не е „без отчет“: 13 отработени дни са
    // отчет и дневният ред се прилага (13/21 → 340,88), а не „цял праг“.
    BgPayrollWorkTime.Decision fiveDays =
        BgPayrollWorkTime.decide(input("8.00", "5.00", WORKING_DAYS, null, null));
    assertFalse(fiveDays.isRefused(), "пет дни е пълна седмица");
    assertEquals(
        FLOOR, prorated(fiveDays.facts), "цял праг — ал. 3 важи само при НЕотработени дни");

    // А трите дни без никакъв отчет са отказ — двете страни на контраста, с еднакъв вход иначе.
    assertTrue(
        BgPayrollWorkTime.decide(input("8.00", "3.00", WORKING_DAYS, null, null)).isRefused());
  }

  @Test
  @DisplayName(
      "⚠️ (в) 8 ч, липсващи дни/седмица → ОБЯВЕНО допускане „пълна седмица“, видимо в изхода")
  void missingDaysPerWeekIsADeclaredAssumptionNotASilentDefault() {
    BgPayrollInput unstated = input("8.00", null, WORKING_DAYS, null, 13);

    assertEquals(
        BgPayrollWorkTime.Regime.FULL,
        BgPayrollWorkTime.regimeOf(unstated),
        "липсата НЕ е отказ: почти всеки договор е пететдневен и отказ при всяко непопълнено поле"
            + " щеше да е червено, което стои");
    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(unstated);
    assertFalse(decision.isRefused());

    assertTrue(
        decision.isAssumed(),
        "ДОПУСКАНЕТО НЕ СТИГА ДО РЕШЕНИЕТО, тоест е мълчаливо подразбиране: човекът пред екрана вижда"
            + " цял праг и няма как да разбере, че режимът е приет, а не прочетен.");
    assertEquals(
        BgPayrollWorkTime.ASSUMPTIONS.get("contractDaysPerWeek"),
        decision.assumption,
        "текстът е ПРИЧИНАТА от списъка, не свободно изречение на мястото на употребата");

    // ⚠️ И СЕ ВИЖДА В ИЗХОДА — това е половината от твърдението. Допускане, което не се показва, не
    // може да бъде оспорено.
    String described = BgPayrollWorkTime.describe(decision);
    assertTrue(described.contains("⚠️ ДОПУСКАНЕ:"), described);
    assertTrue(described.contains("пететдневна по чл. 136 ал. 1 КТ"), described);

    // Нулата се чете като липса, не като нула дни: нула дни на седмица не е договор.
    assertTrue(BgPayrollWorkTime.decide(input("8.00", "0.00", WORKING_DAYS, null, 13)).isAssumed());
    // А попълненото НЕ допуска нищо.
    assertFalse(
        BgPayrollWorkTime.decide(input("8.00", "5.00", WORKING_DAYS, null, 13)).isAssumed());
  }

  // ═══ БЕЗ ПРОПОРЦИЯ — ОБЯВЕНО, НЕ ПОДРАЗБИРАНЕ ════════════════════════════

  @Test
  @DisplayName("Липсващ знаменател → БЕЗ пропорция с обявена причина, а не отказ")
  void aMissingDenominatorMeansNoProrationWithAStatedReason() {
    BgPayrollInput input = input("4.00", null, "40.00", 10);
    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(input);

    assertFalse(decision.isRefused(), "режимът СЕ знае — отказът е за незнание на режима");
    assertNotNull(decision.facts);
    assertFalse(decision.facts.hasHourData(), "без знаменател нито един ред не е приложим");
    assertFalse(decision.facts.hasDayData());
    assertEquals(BgPayrollWorkTime.Regime.PART, decision.regime, "режимът остава в решението");
    assertTrue(
        BgPayrollWorkTime.describe(decision).contains("Причината е обявена"),
        BgPayrollWorkTime.describe(decision));
  }

  @Test
  @DisplayName(
      "ПЪЛНО време без отчет за дни → цял праг, защото ал. 3 важи само при НЕотработени дни")
  void atFullTimeWithoutAWorkedDayCountTheWholeThresholdApplies() {
    BgPayrollInput input = input("8.00", WORKING_DAYS, null, null);
    BgPayrollWorkTime.Decision decision = BgPayrollWorkTime.decide(input);

    assertFalse(decision.isRefused(), "не е отказ: нормата дава правило за този случай");
    assertFalse(decision.facts.hasDayData());
    assertFalse(decision.facts.hasHourData());
    assertEquals(FLOOR, prorated(decision.facts), "цял праг");
    // Асиметрията с непълното работно време е ПРИНЦИПНА, не произволна: ал. 3 се прилага „за
    // лицата, които НЕ СА отработили всичките дни“, тоест липсата на твърдение за неотработване е
    // отговор. Ал. 4 няма такова условие — тя иска часове винаги, и затова там липсата е отказ.
    assertTrue(BgPayrollWorkTime.decide(input("4.00", WORKING_DAYS, null, null)).isRefused());
  }
}
