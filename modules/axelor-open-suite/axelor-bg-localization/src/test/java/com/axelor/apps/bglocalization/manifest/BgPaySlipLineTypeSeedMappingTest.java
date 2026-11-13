package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgIncomeTaxService;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛОВАТА ПОЛОВИНА на съответствието между речника с типове пера и каталозите, от които групите
 * 5xx и 9xx са ГЕНЕРИРАНИ. ⚠️ Днес покрива 9xx; половината за 5xx влиза с генератора на
 * осигуровките.
 *
 * <p><b>⚠️ ЗАЩО ВТОРИ ТЕСТ, КОГАТО {@code BgPaySlipLineTypeCatalogueTest} ВЕЧЕ ПАЗИ СЪЩОТО.</b> Той
 * пита БАЗАТА и се ПРОПУСКА, когато Postgres липсва — тоест на всяка машина без база и в CI без
 * услуга той е зелен, без да е проверил нищо. А точно това съответствие е онова, върху което двата
 * генератор стъпва: {@code BgCompensationLineService} намира типа перо по ВИДА ОБЕЗЩЕТЕНИЕ.
 * Разминат ли се файловете, генераторът вдига изключение върху ЖИВ фиш. Този тест чете САМИТЕ
 * CSV-та и върви навсякъде.
 *
 * <p>Двата теста не се дублират, а гледат различни неща: този пита „файловете съгласни ли са
 * помежду си“, другият — „базата отговаря ли на файловете след App Install“. Първото се чупи при
 * писане на seed, второто — при непуснат импорт.
 *
 * <p><b>Какво този тест пази ОТВЪД броенето.</b> Числата съвпадат и при разместени връзки: 25 реда
 * срещу 25 обезщетения минава и когато два реда сочат към един вид, а трети към нищо. Затова тук се
 * сверява ВРЪЗКА ПО ВРЪЗКА, а не се броят редове.
 */
class BgPaySlipLineTypeSeedMappingTest {

  private static final String COMPENSATION_TYPES = "bg_compensation_type.csv";
  private static final String COMPENSATION_LINES = "bg_pay_slip_line_type_compensation.csv";
  private static final String FUNDS = "bg_contribution_fund.csv";
  private static final String CONTRIBUTION_LINES = "bg_pay_slip_line_type_contribution.csv";

  /** Стойността на една колона за всеки ред, по реда на файла. */
  private static List<String> column(String file, String name) {
    List<String> header = BgSeed.quotedHeader(file);
    List<String> out = new ArrayList<>();
    for (String[] row : BgSeed.quotedRows(file)) {
      out.add(BgSeed.col(header, row, name));
    }
    return out;
  }

  /** Редовете на файл като карта код → ред, за четене по име на колона. */
  private static Map<String, Map<String, String>> rowsByCode(String file) {
    List<String> header = BgSeed.quotedHeader(file);
    Map<String, Map<String, String>> out = new LinkedHashMap<>();
    for (String[] row : BgSeed.quotedRows(file)) {
      Map<String, String> cells = new LinkedHashMap<>();
      for (String c : header) {
        cells.put(c, BgSeed.col(header, row, c));
      }
      out.put(BgSeed.col(header, row, "code"), cells);
    }
    return out;
  }

  private static void empty(String какво, List<String> случаи) {
    if (!случаи.isEmpty()) {
      fail(какво + " — " + случаи.size() + " случая:\n  " + String.join("\n  ", случаи));
    }
  }

  // ═══ 9xx ↔ КАТАЛОГА НА ОБЕЗЩЕТЕНИЯТА ══════════════════════════════════════

  /**
   * ⚠️ ТОВА Е ПРЕДПОСТАВКАТА НА {@code BgCompensationLineService}. Липсващ ред тук значи изключение
   * върху жив фиш: признато обезщетение, което не може да стане перо.
   */
  @Test
  @DisplayName("⚠️ всеки вид обезщетение има ТОЧНО ЕДИН ред на фиша — иначе генераторът се спъва")
  void everyCompensationKindHasExactlyOneLineType() {
    Set<String> kinds = new LinkedHashSet<>(column(COMPENSATION_TYPES, "code"));
    List<String> mapped = column(COMPENSATION_LINES, "bgCompensationType");

    Map<String, Integer> перКод = new TreeMap<>();
    for (String k : mapped) {
      перКод.merge(k, 1, Integer::sum);
    }

    List<String> липсващи = new ArrayList<>();
    for (String kind : kinds) {
      if (!перКод.containsKey(kind)) {
        липсващи.add(kind + " — няма ред на фиша, тоест няма къде да се отпечата");
      }
    }
    empty("вид обезщетение БЕЗ ред на фиша", липсващи);

    List<String> двойни = new ArrayList<>();
    List<String> сираци = new ArrayList<>();
    for (Map.Entry<String, Integer> e : перКод.entrySet()) {
      if (e.getValue() > 1) {
        двойни.add(e.getKey() + " → " + e.getValue() + " реда");
      }
      if (!kinds.contains(e.getKey())) {
        сираци.add(e.getKey() + " — ред на фиша сочи вид, който каталогът НЕ носи");
      }
    }
    empty(
        "вид обезщетение с ПОВЕЧЕ ОТ ЕДИН ред на фиша (генераторът чете единия и мълчи за другия)",
        двойни);
    empty("ред на фиша с празна или непозната препратка (печата се празен)", сираци);

    assertEquals(kinds.size(), перКод.size(), "по един ред за всеки вид, и нито един в повече");
  }

  /**
   * ⚠️ ФЛАГОВЕТЕ СА КОПИЯ, НЕ ВТОРО МНЕНИЕ — и ТОВА е твърдението, което бележката в CSV-то прави.
   *
   * <p>Бележката на всеки ред от 9xx казва: „Името, правното основание, облагаемостта и
   * осигурителният характер са КОПИЯ от него, а не второ мнение: BgPaySlipLineTypeCatalogueTest
   * пада, ако се разминат.“ ⚠️ Онзи тест обаче сверява САМО името. Тук се сверяват и трите
   * останали.
   *
   * <p>Не е педантичност: {@code BgCompensationLineService} записва перото с флаговете НА РЕЧНИКА,
   * а документът замразява своите от КАТАЛОГА. Разминат ли се двата файла, всеки фиш с такова
   * обезщетение излиза с обявено разминаване — шум, чиято причина е в seed-а, не в данните.
   */
  @Test
  @DisplayName("⚠️ облагаемост, осигурителен характер и основание на 9xx са КОПИЯ от каталога")
  void theCompensationLineFlagsAreCopiesOfTheCatalogue() {
    Map<String, Map<String, String>> kinds = rowsByCode(COMPENSATION_TYPES);
    List<String> header = BgSeed.quotedHeader(COMPENSATION_LINES);
    List<String> разминати = new ArrayList<>();

    for (String[] row : BgSeed.quotedRows(COMPENSATION_LINES)) {
      String code = BgSeed.col(header, row, "code");
      String kindCode = BgSeed.col(header, row, "bgCompensationType");
      Map<String, String> kind = kinds.get(kindCode);
      if (kind == null) {
        continue; // отчетено от другия тест
      }
      сверка(
          разминати,
          code,
          "облагаемост (bgIsTaxable ↔ isTaxable)",
          BgSeed.col(header, row, "bgIsTaxable"),
          kind.get("isTaxable"));
      сверка(
          разминати,
          code,
          "осигурителен доход (bgIsInsuranceIncome ↔ socialContributionsDue)",
          BgSeed.col(header, row, "bgIsInsuranceIncome"),
          kind.get("socialContributionsDue"));
      сверка(разминати, code, "наименование", BgSeed.col(header, row, "name"), kind.get("name"));
      сверка(
          разминати,
          code,
          "правно основание (bgLegalBasis ↔ legalBasis)",
          BgSeed.col(header, row, "bgLegalBasis"),
          kind.get("legalBasis"));
    }

    empty("ред на фиша, чието третиране се разминава с каталога на обезщетенията", разминати);
  }

  private static void сверка(
      List<String> out, String code, String какво, String наРеда, String вКаталога) {
    String a = наРеда == null ? "" : наРеда.trim();
    String b = вКаталога == null ? "" : вКаталога.trim();
    if (!a.equalsIgnoreCase(b)) {
      out.add(code + " · " + какво + ": редът казва „" + a + "“, каталогът — „" + b + "“");
    }
  }

  // ═══ 5xx ↔ РЕЧНИКА НА ФОНДОВЕТЕ ═══════════════════════════════════════════

  /**
   * ⚠️ ТОВА Е ПРЕДПОСТАВКАТА НА {@code BgContributionLineService}: той търси типа по ФОНД И ЗНАК.
   *
   * <p>Затова се проверява не че фондът има ДВА реда, а че има ТОЧНО ЕДИН CREDIT и ТОЧНО ЕДИН
   * DEBIT. Два реда със същия знак дават точно същия БРОЙ и оставят единия платец без перо —
   * вноската на лицето изчезва от фиша, данъчната основа излиза по-висока, а нетото — по-голямо.
   */
  @Test
  @DisplayName("⚠️ всеки фонд има ТОЧНО ЕДИН CREDIT (осигурител) и ТОЧНО ЕДИН DEBIT (лице) ред")
  void everyFundHasExactlyOneLinePerPayer() {
    Set<String> funds = new LinkedHashSet<>(column(FUNDS, "code"));
    List<String> header = BgSeed.quotedHeader(CONTRIBUTION_LINES);

    Map<String, Integer> credit = new TreeMap<>();
    Map<String, Integer> debit = new TreeMap<>();
    List<String> непознат = new ArrayList<>();
    List<String> безЗнак = new ArrayList<>();

    for (String[] row : BgSeed.quotedRows(CONTRIBUTION_LINES)) {
      String code = BgSeed.col(header, row, "code");
      String fund = BgSeed.col(header, row, "bgContributionFund");
      String sign = BgSeed.col(header, row, "bgSign");
      if (fund == null || fund.trim().isEmpty() || !funds.contains(fund.trim())) {
        непознат.add(code + " → фонд „" + fund + "“, който речникът на фондовете НЕ носи");
        continue;
      }
      if ("CREDIT".equals(sign)) {
        credit.merge(fund.trim(), 1, Integer::sum);
      } else if ("DEBIT".equals(sign)) {
        debit.merge(fund.trim(), 1, Integer::sum);
      } else {
        безЗнак.add(code + " → знак „" + sign + "“, който не е нито CREDIT, нито DEBIT");
      }
    }

    empty("ред за осигуровка с празен или непознат фонд (печата се празен)", непознат);
    empty("ред за осигуровка без разпознаваем знак — по знака се избира ПЛАТЕЦЪТ", безЗнак);

    List<String> непълни = new ArrayList<>();
    for (String fund : funds) {
      int c = credit.getOrDefault(fund, 0);
      int d = debit.getOrDefault(fund, 0);
      if (c != 1 || d != 1) {
        непълни.add(
            fund + " → CREDIT (осигурител) " + c + ", DEBIT (лице) " + d + "; трябва по едно");
      }
    }
    empty("⚠️ фонд без пълния чифт редове — генераторът вдига изключение върху жив фиш", непълни);

    assertEquals(
        funds.size() * 2,
        BgSeed.quotedRows(CONTRIBUTION_LINES).size(),
        "10 фонда × 2 платеца = 20 реда, и нито един в повече");
  }

  /**
   * ⚠️ ОСИГУРОВКАТА НЕ Е ДОХОД. Вдигнат флаг тук вкарва вноските в осигурителния доход и в
   * данъчната основа като начисления — тоест базата се осигурява и облага втори път, върху самите
   * вноски.
   */
  @Test
  @DisplayName("⚠️ нито един ред от 5xx не е облагаем и нито един не е осигурителен доход")
  void contributionLinesAreNeitherTaxableNorInsurable() {
    List<String> header = BgSeed.quotedHeader(CONTRIBUTION_LINES);
    List<String> лоши = new ArrayList<>();

    for (String[] row : BgSeed.quotedRows(CONTRIBUTION_LINES)) {
      String code = BgSeed.col(header, row, "code");
      if (!"false".equalsIgnoreCase(BgSeed.col(header, row, "bgIsTaxable"))) {
        лоши.add(code + " · bgIsTaxable ≠ false");
      }
      if (!"false".equalsIgnoreCase(BgSeed.col(header, row, "bgIsInsuranceIncome"))) {
        лоши.add(code + " · bgIsInsuranceIncome ≠ false");
      }
    }

    empty("ред за осигуровка, обявен за доход", лоши);
  }

  /**
   * ⚠️ ГРУПАТА СЕ ЧЕТЕ ОТ НОМЕРА — {@code BgPaySlipResolver#groupOf} дели на 100. Ред от 5xx с
   * номер извън 500–599 пада в друга група и спира да участва в данъчната основа и в разхода за
   * работодателя, при това ТИХО: знакът и флаговете му остават верни.
   */
  @Test
  @DisplayName("⚠️ номерата на 5xx са в 500–599, а на 9xx — в 900–999; групата се чете от номера")
  void theNumbersFallInTheirGroups() {
    List<String> вън = new ArrayList<>();
    провериГрупа(вън, CONTRIBUTION_LINES, 5, "CONTRIBUTIONS");
    провериГрупа(вън, COMPENSATION_LINES, 9, "COMPENSATIONS");
    empty("⚠️ ред с номер извън групата си — BgPaySlipResolver.groupOf го брои другаде", вън);
  }

  private static void провериГрупа(List<String> out, String file, int group, String name) {
    List<String> header = BgSeed.quotedHeader(file);
    for (String[] row : BgSeed.quotedRows(file)) {
      String code = BgSeed.col(header, row, "code");
      int number = Integer.parseInt(BgSeed.col(header, row, "number").trim());
      if (number / 100 != group) {
        out.add(code + " · номер " + number + " → група " + (number / 100) + ", а не " + group);
      }
      if (!name.equals(BgSeed.col(header, row, "bgGroup"))) {
        out.add(code + " · bgGroup „" + BgSeed.col(header, row, "bgGroup") + "“ ≠ " + name);
      }
    }
  }

  // ═══ 7xx · ПЕРОТО НА ДАНЪКА ═══════════════════════════════════════════════

  /**
   * НСОРЗ ЧЛ. 17 АЛ. 1 → {@code bgIsArt17Base}: шестте реда, които влизат в среднодневното по КТ
   * чл. 177, и инвариантът, който пази постоянните вътре.
   *
   * <p><b>⚠️ ТРИТЕ ФЛАГА СА ТРИ РАЗЛИЧНИ МНОЖЕСТВА, И ИМЕННО ТОВА СЕ ПАЗИ ТУК.</b> Дотук базата се
   * четеше през {@code bgIsPermanentCharacter} — тоест през чл. 15, който е само т. 3 от седемте
   * съставки на чл. 17 ал. 1. Престоят (т. 6) падаше извън и месец с престой даваше занижено
   * среднодневно. Обратното изкушение — да се отметне постоянният характер за престоя — е по-скъпо:
   * СЪЩИЯТ флаг храни часовата ставка по чл. 7, тоест престоят щеше да поскъпи извънредния труд.
   */
  @Test
  @DisplayName("НСОРЗ чл. 17 ал. 1 · седемте реда в базата, и постоянното Е сред тях")
  void theArt17BaseIsTheSevenDeclaredRows() {
    List<String> header = BgSeed.quotedHeader("bg_pay_slip_line_type.csv");

    List<String> inBase = new ArrayList<>();
    List<String> permanentOutside = new ArrayList<>();
    for (String[] row : BgSeed.quotedRows("bg_pay_slip_line_type.csv")) {
      String code = BgSeed.col(header, row, "code");
      boolean art17 = "true".equals(BgSeed.col(header, row, "bgIsArt17Base"));
      boolean permanent = "true".equals(BgSeed.col(header, row, "bgIsPermanentCharacter"));
      if (art17) {
        inBase.add(code);
      }
      // ⚠️ ИНВАРИАНТЪТ: постоянното Е в базата по т. 3. Обратното НЕ важи — престоят е в базата,
      // без да е постоянен. Еднопосочен, а не тъждество, и точно затова се пише, а не се извежда.
      if (permanent && !art17) {
        permanentOutside.add(code);
      }
    }

    assertEquals(
        new ArrayList<String>(),
        permanentOutside,
        "⚠️ ПОСТОЯНЕН РЕД ИЗВЪН БАЗАТА ПО ЧЛ. 17 Е МЪЛЧАЛИВА ЗАГУБА НА ПАРИ. Чл. 17 ал. 1 т. 3"
            + " включва ДТВ с постоянен характер дословно; изпаднал оттам, редът спира да влиза в"
            + " среднодневното и отпускът излиза занижен, без нито един отказ. Дизюнкцията в"
            + " art17BaseOf днес го спасява (тя е МИГРАЦИЯТА за заварени редове), но новият ред"
            + " трябва да е верен и без нея");

    assertEquals(
        Arrays.asList(
            "pl_osnovna",
            "pl_tspo",
            "pl_nauchna_stepen",
            "pl_prestoi_267",
            "pl_postoyanen_bonus",
            "pl_postoyanna_premia",
            "pl_rezultati"),
        inBase,
        "⚠️ СЕДЕМ РЕДА, И СПИСЪКЪТ Е ЗАКОВАН, ЗАЩОТО ВСЕКИ ОСМИ Е ПАРИ. Т. 1 (основната), т. 2"
            + " (резултатите), т. 3 (четирите постоянни) и т. 6 (престоят по чл. 267 ал. 1 и 3)."
            + " ⚠️ pl_ednokraten_bonus (185) е ИЗВЪН нарочно и бележката му го казва: т. 2 иска"
            + " възнаграждение, „определено според прилаганите СИСТЕМИ за заплащане“, а дискреция"
            + " не е система. Т. 4 (чл. 259), т. 5 (чл. 266 ал. 1) и т. 7 (чл. 268) нямат перо днес");
  }

  /**
   * ⚠️ Т. 2 СРЕЩУ Т. 3 — ГРЕШКАТА, КОЯТО ТОЗИ СВИДЕТЕЛ ПАЗИ ДА НЕ СЕ ПОВТОРИ.
   *
   * <p>Бележката на перо 190 отрича постоянния характер („зависи от резултат, не единствено от
   * отработеното време“) и изкушава перото да се зареди ИЗВЪН базата. Това обаче е отговор на <b>т.
   * 3</b>, а въпросът на <b>т. 2</b> е друг: „възнаграждението НАД ОСНОВНАТА работна заплата,
   * определено според прилаганите СИСТЕМИ за заплащане на труда“. Отричането на едната точка,
   * прочетено като отричане на другата, вади перото от среднодневното.
   *
   * <p>⚠️ Разграничителят спрямо перо 185 НЕ е „редовно срещу еднократно“, а <b>система срещу
   * преценка</b>. Затова двата реда се четат ЗАЕДНО тук: свидетел само за единия би минал зелен и
   * при слети флагове.
   */
  @Test
  @DisplayName("⚠️ т. 2 · резултатите (190) са В базата, дискреционният бонус (185) — ВЪН")
  void performancePayIsInTheBaseButTheDiscretionaryBonusIsNot() {
    Map<String, Map<String, String>> rows = rowsByCode("bg_pay_slip_line_type.csv");

    Map<String, String> performance = rows.get("pl_rezultati");
    assertTrue(performance != null, "речникът няма ред pl_rezultati");
    assertEquals(
        "true",
        performance.get("bgIsArt17Base"),
        "⚠️ Т. 2, НЕ Т. 3. Възнаграждението за постигнати резултати е „над основната, определено"
            + " според прилаганите системи за заплащане“ — точно текстът на т. 2");
    assertEquals(
        "false",
        performance.get("bgIsPermanentCharacter"),
        "⚠️ И ТОВА ОСТАВА ВЯРНО: по чл. 15 то НЕ е постоянно, тоест НЕ влиза в часовата ставка по"
            + " чл. 7 и НЕ се карвира при отпуск. Двете твърдения не си противоречат — те са за"
            + " различни норми");

    Map<String, String> oneOff = rows.get("pl_ednokraten_bonus");
    assertTrue(oneOff != null, "речникът няма ред pl_ednokraten_bonus");
    assertEquals(
        "false",
        oneOff.get("bgIsArt17Base"),
        "⚠️ ДИСКРЕЦИЯТА НЕ Е СИСТЕМА. Еднократният бонус е по преценка и затова НЕ е т. 2 — виж"
            + " бележката на реда. Вкаран в базата, той щеше да вдигне среднодневното на следващия"
            + " отпуск заради еднократно плащане, което никога няма да се повтори");
  }

  /**
   * ⚠️ И ДВАТА ФЛАГА НА ПРЕСТОЯ — точно този ред носи цялото правило. Свидетелят е предпазната
   * мрежа за перо, което иначе никой не би забелязал, че се е счупило.
   */
  @Test
  @DisplayName("⚠️ престоят (170) · в базата по чл. 17, НО без постоянен характер — двете заедно")
  void theStoppageLineIsInTheBaseButNotPermanent() {
    Map<String, Map<String, String>> rows = rowsByCode("bg_pay_slip_line_type.csv");
    Map<String, String> prestoy = rows.get("pl_prestoi_267");

    assertTrue(prestoy != null, "речникът няма ред pl_prestoi_267 — чл. 267 КТ остава без перо");
    assertEquals(
        "true",
        prestoy.get("bgIsArt17Base"),
        "чл. 17 ал. 1 т. 6 изброява дословно „възнаграждението, заплатено при престой или поради"
            + " производствена необходимост, по чл. 267, ал. 1 и 3“");
    assertEquals(
        "false",
        prestoy.get("bgIsPermanentCharacter"),
        "⚠️ И ТОВА Е ДРУГАТА ПОЛОВИНА НА ПРАВИЛОТО. Вдигнат, флагът вкарва престоя в базата"
            + " по НСОРЗ чл. 7 и ПОСКЪПЯВА часовата ставка на нощния и извънредния труд — пари,"
            + " преместени в съвсем друго перо, за да се поправи отпускът. Двете норми са различни"
            + " и затова са различни колони");
    assertEquals(
        "CREDIT", prestoy.get("bgSign"), "начисление, не удръжка — art17BaseOf иска CREDIT");
  }

  /**
   * ⚠️ {@code BgIncomeTaxService} търси перото си по КОДА. Разминат ли се низът в кода и редът във
   * файла, генераторът вдига изключение върху ЖИВ фиш — и то в момента, в който данък наистина се
   * дължи, тоест не при първия фиш, а при първия ОБЛАГАЕМ фиш.
   */
  @Test
  @DisplayName("⚠️ pl_dod съществува: номер 700, група TAX, знак DEBIT — кодът е и в кода")
  void theIncomeTaxLineTypeExists() {
    Map<String, Map<String, String>> rows = rowsByCode("bg_pay_slip_line_type.csv");
    Map<String, String> dod = rows.get(BgIncomeTaxService.LINE_INCOME_TAX);

    if (dod == null) {
      fail(
          "речникът не носи ред с код „"
              + BgIncomeTaxService.LINE_INCOME_TAX
              + "“ — генераторът на ДОД няма перо, в което да сложи данъка");
    }
    assertEquals(
        "700", dod.get("number"), "номерът решава групата — BgPaySlipResolver дели на 100");
    assertEquals("TAX", dod.get("bgGroup"));
    assertEquals(
        "DEBIT",
        dod.get("bgSign"),
        "⚠️ данъкът се УДЪРЖА. CREDIT тук би го ДОБАВИЛ към нетото и taxFromLines нямаше да го брои");
    assertEquals("false", dod.get("bgIsTaxable"), "данъкът не се облага сам");
    assertEquals("false", dod.get("bgIsInsuranceIncome"), "и не е осигурителен доход");
    assertEquals("true", dod.get("active"));

    List<String> tax = new ArrayList<>();
    List<String> header = BgSeed.quotedHeader("bg_pay_slip_line_type.csv");
    for (String[] row : BgSeed.quotedRows("bg_pay_slip_line_type.csv")) {
      if ("TAX".equals(BgSeed.col(header, row, "bgGroup"))) {
        tax.add(BgSeed.col(header, row, "code"));
      }
    }
    assertEquals(
        Arrays.asList(BgIncomeTaxService.LINE_INCOME_TAX, BgPaySlipResolver.LINE_ART49_SETTLEMENT),
        tax,
        "⚠️ ТОЧНО ДВА реда в 7xx, и вторият е БЕЗОПАСЕН САМО защото taxFromLines го изключва"
            + " поименно. Трети ред, добавен без същото изключване, би се събрал от taxFromLines и"
            + " би дал данък, който после блокира потвърждаването с необяснимо число.");
  }

  /**
   * ⚠️ ВТОРИЯТ РЕД НА ГРУПАТА — годишното изравняване по чл. 49, и той се чете от ДВЕ места с
   * РАЗЛИЧНИ правила.
   *
   * <p>{@code BgD6Aggregator} го мете в т. 16 на Д6 (указанието иска дословно „чл. 41а, чл. 42 и
   * чл. 49 ал. 5“), а {@code BgPaySlipResolver#taxFromLines} НЕ го брои — той сверява МЕСЕЧНИЯ
   * данък срещу заглавната сума, която не знае нищо за чл. 49. Двете правила са противоположни и
   * двете са верни; тестът отдолу заковава връзката между тях, за да не се „поправи“ едното без
   * другото.
   */
  @Test
  @DisplayName(
      "⚠️ pl_dod_chl49 съществува: номер 710, група TAX, DERIVED — и НЕ се брои от taxFromLines")
  void theArt49SettlementLineTypeExists() {
    Map<String, Map<String, String>> rows = rowsByCode("bg_pay_slip_line_type.csv");
    Map<String, String> row = rows.get(BgPaySlipResolver.LINE_ART49_SETTLEMENT);

    if (row == null) {
      fail(
          "речникът не носи ред с код „"
              + BgPaySlipResolver.LINE_ART49_SETTLEMENT
              + "“ — годишното изравняване по чл. 49 няма перо, в което да легне");
    }
    assertEquals(
        "710", row.get("number"), "номерът решава групата — BgPaySlipResolver дели на 100");
    assertEquals("TAX", row.get("bgGroup"));
    assertEquals(
        "DEBIT",
        row.get("bgSign"),
        "⚠️ НОМИНАЛНАТА посока. Истинската се преписва на ПЕРОТО по знака на колона 19 —"
            + " BgArt49Settlement#sign. Речникът не може да носи две посоки, а перото може.");
    assertEquals("DERIVED", row.get("bgCalculationBase"), "чете се от справката, не се въвежда");
    assertEquals("false", row.get("bgIsTaxable"), "данъкът не се облага сам");
    assertEquals("false", row.get("bgIsInsuranceIncome"), "и не е осигурителен доход");
    assertEquals(
        "false",
        row.get("bgFormsGrossWage"),
        "⚠️ не формира БРЗ: изравняването е данък за МИНАЛА година, не възнаграждение за тази");
    assertEquals("true", row.get("active"));

    // ⚠️ ДВЕТЕ КОНСТАНТИ СА РАЗЛИЧНИ НИЗОВЕ. Слети по невнимание (една и съща стойност на двете
    // места), генераторът на изравняването щеше да обновява перото на месечния данък.
    assertNotEquals(
        BgIncomeTaxService.LINE_INCOME_TAX,
        BgPaySlipResolver.LINE_ART49_SETTLEMENT,
        "двата реда на 7xx трябва да имат РАЗЛИЧНИ кодове");
  }
}
