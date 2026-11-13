package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Сметките от миграцията на плана (#173) стигат ли до отчетите — 6094, 40901 и оттеглената 4557.
 *
 * <p><b>Механизмът, който тези пазачи предполагат: справките се вписват по КОД, не по група.</b> В
 * {@code account_accountingReportConfigLine.csv} колоните {@code accountSet_code} и {@code
 * accountTypeSet_code} са празни на ВСИЧКИ редове; работи само {@code accountCode}. Вносът го
 * резолвва с {@code self.code = :code} ({@code ImportAccountingReportConfigLine#setAccounts}), а
 * изчислението — с {@code self.code LIKE ?} ({@code
 * AccountingReportValueMoveLineServiceImpl#mergeWithAccountCode}), като стойността се цепи на
 * ЗАПЕТАЯ и всеки токен тръгва като отделен {@code LIKE}.
 *
 * <p><b>⚠️ ОТ PR-N2 ТОКЕНЪТ МОЖЕ ДА НОСИ {@code %}.</b> Шестте coarse сметки станаха репортни
 * възли, а НАП листата под тях се четат с {@code 201%}, {@code 452%}, {@code 601%}. Това е
 * стоковият идиом — FRA_PCG пише {@code 101%,108%}, GBR_COA пише {@code 1001%} — но значи, че
 * сравнението „покрита ли е сметката“ ВЕЧЕ НЕ Е РАВЕНСТВО. Проверка по равенство тук би обявила
 * 60101–60105 за сираци, а {@code 601%} — за несъществуваща сметка: две лъжливи червени наведнъж.
 * Затова и двата обобщаващи пазача минават през {@link #matching(String)}.
 *
 * <p><b>⚠️ WILDCARD САМО КЪДЕТО ПРЕФИКСЪТ Е ЧИСТ.</b> {@code 602%} би уловил и 6029, {@code 609%} —
 * 6091 и 6094, а те са НАРОЧНО другаде по функция. Затова 602 и 609 се изброяват изрично, и точно
 * това е разликата, която {@link #няма_блуждаещ_роднина_под_wildcard()} пази.
 *
 * <p>Колоната {@code parent_code} в чарта е репортно дърво за интерфейса и не се чете от нито един
 * ред на отчетния двигател. Следствие: <b>всяка сметка иска свой изричен ред (или да попада под
 * wildcard), а всеки сбор изброява редовете ПОИМЕННО</b> — нищо не се наследява от родителя.
 *
 * <p><b>Защо 6094 тежи повече от един ред в ОПР.</b> Разходите влизат и в двата БАЛАНСА през скрити
 * редове {@code R_EXP_*}, които се сумират поименно в {@code R_EXP}, а оттам {@code R_PL = R_INC −
 * R_EXP} дава финансовия резултат. Разход, забравен в {@code R_EXP}, не просто липсва от ОПР — той
 * <b>надценява печалбата в баланса</b>.
 *
 * <p><b>Двата обобщаващи пазача накрая</b> не гледат конкретни номера: една сметка без ред никъде и
 * един ред към изчезнала сметка са точно двата провала, които тази миграция допусна (6094 и
 * ваучерите влязоха в чарта без конфиг; 4557 излезе от чарта, а редовете ѝ останаха).
 */
class BgCoaReportCoverageTest {

  private static final Path COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Path ACCOUNTS = COA.resolve("account_account.csv");

  private static final Path REPORT_LINES = COA.resolve("account_accountingReportConfigLine.csv");

  /** Социални разходи (СБКО) — разход, тъй че и в ОПР, и в резултата на двата баланса. */
  private static final String SOCIAL_EXPENSE = "6094";

  /** Ваучери за храна — задължение към доставчик, тъй че само в двата баланса. */
  private static final String VOUCHERS = "40901";

  /** Оттеглена с миграцията: ГВРС се сля в 4556. */
  private static final String RETIRED = "4557";

  /**
   * КОДОВЕТЕ, КОИТО N1 ОСВОБОДИ, А N2 ЗАЕ ПАК — всеки от ДРУГА сметка, с ДРУГ смисъл.
   *
   * <p>В N1 тези пет кода трябваше да са празни и пазачът твърдеше точно това. Сега твърдението е
   * обърнато: те СЪЩЕСТВУВАТ, но вече значат друго, и затова се сверява ТИПЪТ. 124 беше разход
   * („Текущ корпоративен данък“), а е собствен капитал; 208 беше ДМА-позиция, а е „Подобрения на
   * наети помещения“. Сгрешен тип тук не гърми — той мести сметката в ДРУГ РАЗДЕЛ на баланса.
   */
  private static final Map<String, String> REOCCUPIED_BY_NRA = new LinkedHashMap<>();

  /** Начислени разходи и приходи — репортен възел, не осчетоводима сметка. */
  private static final String ACCRUALS_VIEW = "C050";

  /** Двете сметки, които висят под {@link #ACCRUALS_VIEW} и трябва да останат там. */
  private static final List<String> ACCRUALS_CHILDREN = Arrays.asList("481", "751");

  /** {@code account_accountType.csv}: 1 = {@code view}. */
  private static final String TYPE_VIEW = "1";

  /** {@code account_accountType.csv}: 13 = {@code charge}, 10 = {@code payable}. */
  private static final String TYPE_CHARGE = "13";

  private static final String TYPE_PAYABLE = "10";

  private static final String TYPE_EQUITY = "7";

  private static final String TYPE_DEBT = "9";

  private static final String TYPE_IMMO = "2";

  private static final String TYPE_RECEIVABLE = "5";

  /** Шестте coarse сметки, които PR-N2 превърна в репортни възли, и листата под тях. */
  private static final Map<String, List<String>> NRA_VIEW_PARENTS = new LinkedHashMap<>();

  /** Кои от тях се четат с wildcard — и кои НЕ, защото имат блуждаещи роднини. */
  private static final Map<String, String> WILDCARD_READS = new LinkedHashMap<>();

  /** Типовете, които НЕ се очаква да се явят в справка: 1 = {@code view}, 14 = {@code special}. */
  private static final List<String> NOT_REPORTABLE = Arrays.asList("1", "14");

  /** Къде 6094 трябва да се сумира: справка → видимият/скритият сбор, който го изброява. */
  private static final Map<String, String> SOCIAL_SUBTOTALS = new LinkedHashMap<>();

  /** Къде 40901 трябва да се сумира. */
  private static final Map<String, String> VOUCHER_SUBTOTALS = new LinkedHashMap<>();

  /** Редовете „Други разходи“ — 6094 НЕ е там: той е социален разход, не остатъчен. */
  private static final Map<String, String> OTHER_EXPENSES = new LinkedHashMap<>();

  static {
    // 27 = ОПР (НСС), ред „   Разходи за осигуровки“ — социалният ред на отчета.
    SOCIAL_SUBTOTALS.put("27", "E_ST1_5");
    // 45 = ОПР (МСС): отделен ред „осигуровки“ няма, 605x живеят в „Разходи за персонала“.
    SOCIAL_SUBTOTALS.put("45", "MO_ST_83158");
    // 28/46 = двата баланса: скритият сбор на ВСИЧКИ разходи, който храни финансовия резултат.
    SOCIAL_SUBTOTALS.put("28", "R_EXP");
    SOCIAL_SUBTOTALS.put("46", "R_EXP");

    VOUCHER_SUBTOTALS.put("28", "P_ST3_3");
    VOUCHER_SUBTOTALS.put("46", "MP_ST3_2");

    OTHER_EXPENSES.put("27", "E_ST1_7");
    OTHER_EXPENSES.put("45", "MO_ST_21931");

    // 7 = equity, 9 = debt, 2 = immobilisation, 10 = payable, 5 = receivable
    REOCCUPIED_BY_NRA.put("124", TYPE_EQUITY); // Резултат при несъстоятелност и ликвидация
    REOCCUPIED_BY_NRA.put("153", TYPE_DEBT); //  Кредитни карти
    REOCCUPIED_BY_NRA.put("208", TYPE_IMMO); //  Подобрения на наети помещения
    REOCCUPIED_BY_NRA.put("409", TYPE_PAYABLE); // Други задължения към доставчици
    REOCCUPIED_BY_NRA.put("416", TYPE_RECEIVABLE); // Нефактурирани аванси от клиенти

    NRA_VIEW_PARENTS.put("201", Arrays.asList("2011", "2012"));
    NRA_VIEW_PARENTS.put("452", Arrays.asList("4521", "4522"));
    NRA_VIEW_PARENTS.put("453", Arrays.asList("4531", "4532", "4536", "4537", "4538", "4539"));
    NRA_VIEW_PARENTS.put("601", Arrays.asList("60101", "60102", "60103", "60104", "60105"));
    NRA_VIEW_PARENTS.put(
        "602",
        Arrays.asList(
            "60201", "60202", "60203", "60204", "60205", "60206", "60207", "60208", "60209",
            "60210", "60211", "60212"));
    NRA_VIEW_PARENTS.put(
        "609",
        Arrays.asList(
            "60901", "60902", "60903", "60904", "60905", "60906", "60907", "60908", "60909"));

    // ⚠️ САМО ТРИТЕ ЧИСТИ ПРЕФИКСА. 602% лови 6029, 609% лови 6091 и 6094 — те са нарочно по
    // функция другаде (СБКО, социални разходи) и N3 ги мапва към 60909, а не ги слива тук.
    // 453% пък би дублирал: P_ST3_5 вече изброява 4531/4532/4538/4539 ПОИМЕННО.
    WILDCARD_READS.put("201", "201%");
    WILDCARD_READS.put("452", "452%");
    WILDCARD_READS.put("601", "601%");
  }

  @Test
  @DisplayName("6094 е разход в чарта и се сумира в ОПР и в резултата на ДВАТА баланса")
  void социалните_разходи_стигат_до_четирите_отчета() {
    assertAccountType(SOCIAL_EXPENSE, TYPE_CHARGE, "разход");
    for (Map.Entry<String, String> entry : SOCIAL_SUBTOTALS.entrySet()) {
      assertSummedIn(SOCIAL_EXPENSE, entry.getKey(), entry.getValue());
    }
  }

  @Test
  @DisplayName("6094 не виси в „Други разходи“ — той е социален разход, не остатъчен")
  void социалните_разходи_не_са_остатъчни() {
    for (Map.Entry<String, String> entry : OTHER_EXPENSES.entrySet()) {
      String reportType = entry.getKey();
      String detail = detailLineFor(reportType, SOCIAL_EXPENSE);
      assertTrue(
          detail == null || !members(reportType, entry.getValue()).contains(detail),
          "справка "
              + reportType
              + ": "
              + entry.getValue()
              + " („Други разходи“) събира "
              + detail
              + ". По номерация 6094 е 609x и изкушението е точно това, но СБКО е разход за"
              + " персонала — мястото му е при осигуровките ("
              + SOCIAL_SUBTOTALS.get(reportType)
              + "), инак отчетът показва социалните разходи като неразпределен остатък");
    }
  }

  @Test
  @DisplayName("40901 е задължение в чарта и се сумира в двата баланса")
  void ваучерите_стигат_до_двата_баланса() {
    assertAccountType(VOUCHERS, TYPE_PAYABLE, "задължение към доставчик");
    for (Map.Entry<String, String> entry : VOUCHER_SUBTOTALS.entrySet()) {
      assertSummedIn(VOUCHERS, entry.getKey(), entry.getValue());
    }
  }

  @Test
  @DisplayName("оттеглената 4557 я няма нито в чарта, нито в ред, нито в правило")
  void оттеглената_сметка_не_е_останала_никъде() {
    assertTrue(
        !rowsByCode(ACCOUNTS).containsKey(RETIRED),
        RETIRED + " се е върнала в сметкоплана — ГВРС се сля в 4556 и седмата сметка отпадна");

    List<String> header = header(REPORT_LINES);
    for (String[] row : rows(REPORT_LINES)) {
      assertTrue(
          !RETIRED.equals(col(header, row, "accountCode")),
          "ред "
              + col(header, row, "code")
              + " още сочи "
              + RETIRED
              + ". Сметката я няма в чарта, тъй че наборът ѝ излиза празен и редът мълчаливо дава"
              + " нула — отчетът не гърми, но носи мъртва позиция");
      String rule = col(header, row, "rule");
      if (!rule.isEmpty()) {
        for (String token : rule.split("\\s*\\+\\s*")) {
          assertTrue(
              !token.endsWith("_" + RETIRED),
              "правилото на "
                  + col(header, row, "code")
                  + " още изброява "
                  + token
                  + ". Ред и правило се махат ЗАЕДНО: сбор, който сочи изтрит ред, оставя груувския"
                  + " идентификатор недефиниран и цялата позиция изчезва от отчета");
        }
      }
    }
  }

  @Test
  @DisplayName("петте освободени от N1 кода са заети пак — и всеки с НОВИЯ си тип")
  void освободените_кодове_са_заети_от_НАП() {
    Map<String, String[]> chart = rowsByCode(ACCOUNTS);
    List<String> missing = new ArrayList<>();
    List<String> wrongType = new ArrayList<>();
    for (Map.Entry<String, String> entry : REOCCUPIED_BY_NRA.entrySet()) {
      String[] row = chart.get(entry.getKey());
      if (row == null) {
        missing.add(entry.getKey());
        continue;
      }
      String actual = col(header(ACCOUNTS), row, "accountType.importId");
      if (!entry.getValue().equals(actual)) {
        wrongType.add(
            entry.getKey()
                + " „"
                + col(header(ACCOUNTS), row, "name")
                + "“ е тип "
                + actual
                + ", а НАП я дава като "
                + entry.getValue());
      }
    }
    assertEquals(
        List.of(),
        missing,
        "кодове, които N1 освободи НАРОЧНО за НАП, още липсват: "
            + missing
            + ". N1 ги изпразни само за да ги заеме този кръг — оставени празни, преномерирането"
            + " е наполовина и SAF-T износът ще сочи дупка");
    assertEquals(
        List.of(),
        wrongType,
        "заети са, но с ГРЕШЕН тип: "
            + wrongType
            + ". Тук типът не е козметика — той решава в кой раздел на баланса застава сметката."
            + " 124 беше РАЗХОД, а новата 124 е СОБСТВЕН КАПИТАЛ: сбъркан тип я връща в ОПР-а с"
            + " целия си нов оборот");
  }

  @Test
  @DisplayName("Z124 не е останала в сметкоплана — паркингът е на базата, не на seed-а")
  void паркинг_кодът_не_е_в_seed_а() {
    assertTrue(
        !rowsByCode(ACCOUNTS).containsKey("Z124"),
        "Z124 се е появила в BG_COA. Паркинг-кодът на N1 живее САМО в миграцията за заварена база —"
            + " там той пази историческия оборот на пенсионираната 124. Попаднал в seed-а, той би"
            + " създал празна паркинг-сметка на всяка ПРЕСНА фирма, където нищо не се пенсионира");
  }

  @Test
  @DisplayName("шестте coarse сметки са view и всяко НАП листо виси под тях")
  void шестте_възела_държат_листата_си() {
    List<String> header = header(ACCOUNTS);
    Map<String, String[]> chart = rowsByCode(ACCOUNTS);
    for (Map.Entry<String, List<String>> entry : NRA_VIEW_PARENTS.entrySet()) {
      String parent = entry.getKey();
      assertAccountType(parent, TYPE_VIEW, "репортен възел, не осчетоводима сметка");
      List<String> orphans = new ArrayList<>(entry.getValue());
      for (String[] row : rows(ACCOUNTS)) {
        if (parent.equals(col(header, row, "parent_code"))) {
          orphans.remove(col(header, row, "code"));
        }
      }
      assertEquals(
          List.of(),
          orphans,
          "сметки, които не висят под "
              + parent
              + ": "
              + orphans
              + ". Типът ѝ стана view точно за да ги ДЪРЖИ като група — възел без децата си е"
              + " празна позиция в отчета, а лист без възел не се сумира никъде");
      assertTrue(
          chart.get(parent) != null && col(header, chart.get(parent), "bgApSelect").isEmpty(),
          parent + ": репортен възел няма собствено салдо, тъй че А/П оста му е празна");
    }
  }

  @Test
  @DisplayName("⚠️ wildcard само където префиксът е чист — 602% и 609% ловят чужди сметки")
  void няма_блуждаещ_роднина_под_wildcard() {
    Set<String> chart = rowsByCode(ACCOUNTS).keySet();
    for (Map.Entry<String, String> entry : WILDCARD_READS.entrySet()) {
      String parent = entry.getKey();
      Set<String> hit = new LinkedHashSet<>(matching(entry.getValue()));
      hit.remove(parent);
      Set<String> expected = new LinkedHashSet<>(NRA_VIEW_PARENTS.get(parent));
      assertEquals(
          expected,
          hit,
          entry.getValue()
              + " лови "
              + hit
              + ", а децата на "
              + parent
              + " са "
              + expected
              + ". Wildcard-ът е съкращение, не група: нов код със същия префикс влиза в отчета"
              + " мълчаливо, без никой да е пипал справка");
    }
    // Обратното твърдение: 602 и 609 НЕ СМЕЯТ да се четат с %, и ето защо.
    for (String parent : Arrays.asList("602", "609")) {
      Set<String> hit = new LinkedHashSet<>(matching(parent + "%"));
      hit.remove(parent);
      hit.removeAll(NRA_VIEW_PARENTS.get(parent));
      assertTrue(
          !hit.isEmpty(),
          parent
              + "%: блуждаещите роднини са изчезнали от плана. Докато ги имаше ("
              + "6029 под 602, 6091 и 6094 под 609), изричното изброяване беше ЗАДЪЛЖИТЕЛНО."
              + " Изчезнат ли, този тест трябва да се махне СЪЗНАТЕЛНО, а не да пази нищо");
      for (String[] row : rows(REPORT_LINES)) {
        String code = col(header(REPORT_LINES), row, "accountCode");
        assertTrue(
            !code.contains(parent + "%"),
            "ред "
                + col(header(REPORT_LINES), row, "code")
                + " чете "
                + parent
                + "% и така вмъква "
                + hit
                + " — сметки, които стоят другаде по ФУНКЦИЯ (СБКО и социалните разходи са при"
                + " персонала, не при „Други разходи“)");
      }
    }
  }

  @Test
  @DisplayName("C050 е view, а 481 и 751 остават под нея")
  void начисленията_са_репортен_възел_с_двете_си_деца() {
    assertAccountType(ACCRUALS_VIEW, TYPE_VIEW, "репортен възел, не осчетоводима сметка");

    List<String> header = header(ACCOUNTS);
    List<String> orphans = new ArrayList<>(ACCRUALS_CHILDREN);
    for (String[] row : rows(ACCOUNTS)) {
      if (ACCRUALS_VIEW.equals(col(header, row, "parent_code"))) {
        orphans.remove(col(header, row, "code"));
      }
    }
    assertEquals(
        List.of(),
        orphans,
        "сметки, които вече не висят на "
            + ACCRUALS_VIEW
            + ": "
            + orphans
            + ". Типът ѝ стана view точно за да ги ДЪРЖИ като група — възел без деца е празна"
            + " позиция в баланса, а дете без него изпада от „Други задължения и начисления“");
  }

  @Test
  @DisplayName("нито една справка не сочи сметка, която я няма в сметкоплана")
  void няма_ред_към_изчезнала_сметка() {
    List<String> header = header(REPORT_LINES);
    Set<String> stale = new LinkedHashSet<>();
    for (String[] row : rows(REPORT_LINES)) {
      for (String token : tokens(col(header, row, "accountCode"))) {
        if (matching(token).isEmpty()) {
          stale.add(token);
        }
      }
    }
    assertEquals(
        Collections.emptySet(),
        stale,
        "справки сочат сметки, които ги няма в BG_COA: "
            + stale
            + ". Изчезнала сметка не гърми, а дава тихо нула, тъй че оттегляне ВИНАГИ иска и"
            + " чистене на конфига. ⚠️ Токенът може да е и шаблон (601%) — празен ОТГОВОР значи"
            + " липсваща сметка, а не непознат синтаксис");
  }

  @Test
  @DisplayName("нито една АКТИВНА осчетоводима сметка не остава извън всички справки")
  void няма_осиротяла_осчетоводима_сметка() {
    List<String> chartHeader = header(ACCOUNTS);
    List<String> lineHeader = header(REPORT_LINES);

    Set<String> reported = new LinkedHashSet<>();
    for (String[] row : rows(REPORT_LINES)) {
      for (String token : tokens(col(lineHeader, row, "accountCode"))) {
        reported.addAll(matching(token));
      }
    }

    Set<String> orphans = new LinkedHashSet<>();
    for (String[] row : rows(ACCOUNTS)) {
      String code = col(chartHeader, row, "code");
      String type = col(chartHeader, row, "accountType.importId");
      // ⚠️ НЕАКТИВНАТА СМЕТКА НЕ Е СИРАК. PR-N2 внесе 165 специализирани сметки (застраховане,
      // емисии, депозити, осигурителни фондове, задбалансови банки) със statusSelect = 0: те стоят
      // в плана, защото НАП ги изисква за SAF-T, но никое Никса дружество не осчетоводява по тях.
      // Ред в отчет за сметка, по която няма и не може да има статия, е мъртва позиция. Активират
      // ли се някога, този пазач ще ги поиска в същия миг — и това е целият замисъл.
      boolean inactive = "0".equals(col(chartHeader, row, "statusSelect"));
      if (!NOT_REPORTABLE.contains(type) && !inactive && !reported.contains(code)) {
        orphans.add(code + " (тип " + type + ")");
      }
    }
    assertEquals(
        Collections.emptySet(),
        orphans,
        "АКТИВНИ осчетоводими сметки без ред в НИТО ЕДНА справка: "
            + orphans
            + ". Нищо не се наследява от родителя — сметка без изричен ред (или без wildcard, който"
            + " я лови) просто не се сумира никъде, а статиите по нея изчезват от отчетите. Точно"
            + " така 6094 и ваучерите влязоха в чарта и останаха невидими. Изключват се „view“"
            + " (репортни възли), „special“ (техническите приключвателни) и НЕАКТИВНИТЕ");
  }

  @Test
  @DisplayName("А/П оста е попълнена за всяка осчетоводима сметка и празна за всеки възел")
  void ап_оста_е_пълна_и_само_където_трябва() {
    List<String> header = header(ACCOUNTS);
    List<String> missing = new ArrayList<>();
    List<String> surplus = new ArrayList<>();
    int filled = 0;
    for (String[] row : rows(ACCOUNTS)) {
      String code = col(header, row, "code");
      String ap = col(header, row, "bgApSelect");
      boolean isView = TYPE_VIEW.equals(col(header, row, "accountType.importId"));
      if (isView && !ap.isEmpty()) {
        surplus.add(code + " = " + ap);
      } else if (!isView && ap.isEmpty()) {
        missing.add(code);
      }
      if (!ap.isEmpty()) {
        filled++;
        assertTrue(
            Arrays.asList("1", "2", "3").contains(ap),
            code + ": А/П оста е " + ap + ", а bg.account.ap.select дава само 1, 2 и 3");
      }
    }
    assertEquals(
        List.of(),
        missing,
        "осчетоводими сметки без А/П ос: "
            + missing
            + ". Празното е значещо —"
            + " то казва „репортен възел“, тъй че непопълнена осчетоводима сметка се чете като възел");
    assertEquals(
        List.of(),
        surplus,
        "репортни възли с А/П ос: "
            + surplus
            + ". Възелът няма собствено салдо; дадена му ос, той започва да изглежда като сметка");
    assertTrue(filled > 400, "попълнени са само " + filled + " А/П стойности — seed-ът е окълцан");
  }

  @Test
  @DisplayName("специализираните сметки са НЕАКТИВНИ, а не изтрити")
  void специализираните_са_неактивни_а_не_изтрити() {
    List<String> header = header(ACCOUNTS);
    int inactive = 0;
    for (String[] row : rows(ACCOUNTS)) {
      if ("0".equals(col(header, row, "statusSelect"))) {
        inactive++;
        assertTrue(
            !TYPE_VIEW.equals(col(header, row, "accountType.importId")),
            col(header, row, "code") + ": репортен възел не се изключва — той няма статус, а роля");
      }
    }
    assertEquals(
        165,
        inactive,
        "неактивните сметки са "
            + inactive
            + ", а решението по PR-N2 е 165. ⚠️ Изтриване НЯМА и не може да има:"
            + " AccountAccountRepository#remove() хвърля ACCOUNT_REGULATORY_REMOVE за всеки ред с"
            + " isRegulatoryAccount, а всички редове в BG_COA го носят. statusSelect = 0 е"
            + " единственият изразим начин сметката да стои в плана за SAF-T и да не се избира");
  }

  // ─── Редовете по образците към СС 1 и по МСС 1 ───

  @Test
  @DisplayName("104 е записан (основен) капитал, 114 — премиите от емисии; и в двата баланса")
  void капиталът_и_премиите_по_образеца() {
    // СС 1 Прил. № 1, раздел А: I. Записан капитал, II. Премии от емисии. 104 „Капитал на
    // предприятия с нестопанска дейност“ стоеше като премия — остатък от 105 → 115 (PR-N3).
    for (String[] bs : new String[][] {{"28", ""}, {"46", "M"}}) {
      String rt = bs[0];
      String p = bs[1];
      assertSummedIn("104", rt, p + "P_ST1_1");
      assertSummedIn("114", rt, p + "P_ST1_2");
      assertEquals(
          Collections.singletonList("114"),
          accountsOf(rt, p + "P_ST1_2"),
          "справка " + rt + ": премиите от емисии са само 114");
      assertTrue(
          !accountsOf(rt, p + "P_ST1_4").contains("114"),
          "справка " + rt + ": 114 е и в резервите — сумира се два пъти");
    }
  }

  @Test
  @DisplayName("494 е нетекущ ред „Отсрочени данъци“, не текущо вземане; и в двата баланса")
  void отсрочените_данъци_са_нетекущи() {
    // СС 1 Прил. № 1, Б.IV „Отсрочени данъци“; МСС 1 пар. 56 — отсрочените данъчни активи не се
    // класифицират като текущи. Кредитно салдо на 494 излиза с минус в актива: AOS смята ред по
    // сметка, без разделяне по знак — това е известно ограничение до двигателя върху ГОД.
    for (String[] bs : new String[][] {{"28", ""}, {"46", "M"}}) {
      String rt = bs[0];
      String p = bs[1];
      assertSummedIn("494", rt, p + "A_ST1_4");
      assertTrue(
          members(rt, p + "A_SEC1").contains(p + "A_ST1_4"),
          "справка " + rt + ": нетекущите активи не сумират реда на отсрочените данъци");
      assertTrue(
          !accountsOf(rt, p + "A_ST2_2").contains("494"),
          "справка " + rt + ": 494 е и в текущите вземания — сумира се два пъти");
    }
  }

  @Test
  @DisplayName("243 (амортизация на ДА с право на ползване) е при ДМА, не при НМА")
  void амортизацията_на_правото_на_ползване_е_при_дма() {
    for (String[] bs : new String[][] {{"28", ""}, {"46", "M"}}) {
      String rt = bs[0];
      String p = bs[1];
      assertSummedIn("243", rt, p + "A_ST1_2");
      assertTrue(
          !accountsOf(rt, p + "A_ST1_1").contains("243"),
          "справка " + rt + ": 243 е и при нематериалните активи");
    }
  }

  @Test
  @DisplayName("ОПР без извънредни статии: 79x в „Други приходи“, 69x в „Други разходи“")
  void опр_без_извънредни_статии() {
    // НСС: СС 1 Прил. № 2 (ред. ДВ 3/2016) няма раздел „извънредни“. МСФО: МСС 1 пар. 87 —
    // предприятието не представя приходи или разходи като извънредни статии.
    Map<String, String[]> other = new LinkedHashMap<>();
    other.put("27", new String[] {"I_ST1_2", "E_ST1_7", "TOT_INC", "TOT_EXP"});
    other.put("45", new String[] {"MO_ST_54756", "MO_ST_21931", "MO_TINC", "MO_TEXP"});
    for (Map.Entry<String, String[]> e : other.entrySet()) {
      String rt = e.getKey();
      String[] lines = e.getValue();
      for (String income : Arrays.asList("791", "792", "799")) {
        assertSummedIn(income, rt, lines[0]);
      }
      for (String expense : Arrays.asList("691", "692", "699")) {
        assertSummedIn(expense, rt, lines[1]);
      }
      Set<String> totals = new LinkedHashSet<>(members(rt, lines[2]));
      totals.addAll(members(rt, lines[3]));
      List<String> header = header(REPORT_LINES);
      for (String[] row : rows(REPORT_LINES)) {
        if (!rt.equals(col(header, row, "reportType_importId"))) {
          continue;
        }
        String code = col(header, row, "code");
        boolean extraordinary = col(header, row, "label").toLowerCase().contains("извънредн");
        assertTrue(
            !extraordinary || "true".equals(col(header, row, "hidden")),
            "справка " + rt + ": видим ред „" + col(header, row, "label").trim() + "“");
        assertTrue(
            !extraordinary || !totals.contains(code),
            "справка " + rt + ": общият сбор още събира " + code);
      }
    }
  }

  @Test
  @DisplayName("Отчетът за собствения капитал и балансът (НСС) събират едни и същи сметки по групи")
  void капиталът_се_равнява_с_баланса_по_групи() {
    // СС 1 Прил. № 4 има колоните на Прил. № 1, раздел А. Ако една сметка е в различна група в
    // двата отчета, редовете им не се равняват (премиите бяха 115 в 47, а 104 в баланса).
    Map<String, String> groups = new LinkedHashMap<>();
    groups.put("P_ST1_1", "53810");
    groups.put("P_ST1_2", "55018");
    groups.put("P_ST1_3", "75710");
    groups.put("P_ST1_4", "22505");
    groups.put("P_ST1_5", "93462");
    groups.put("P_ST1_6", "83747");
    for (Map.Entry<String, String> g : groups.entrySet()) {
      Set<String> balance = new LinkedHashSet<>(accountsOf("28", g.getKey()));
      for (String side : Arrays.asList("SC_OPEN_", "SC_CLOSE_")) {
        assertEquals(
            balance,
            new LinkedHashSet<>(accountsOf("47", side + g.getValue())),
            "справка 47 " + side + g.getValue() + " срещу баланса " + g.getKey());
      }
    }
  }

  /** Сметките, които сборът внася през детайлните си редове (в реда на правилото). */
  private static List<String> accountsOf(String reportType, String subtotal) {
    List<String> header = header(REPORT_LINES);
    Map<String, String> accountByLine = new LinkedHashMap<>();
    for (String[] row : rows(REPORT_LINES)) {
      if (reportType.equals(col(header, row, "reportType_importId"))) {
        accountByLine.put(col(header, row, "code"), col(header, row, "accountCode"));
      }
    }
    List<String> out = new ArrayList<>();
    for (String member : members(reportType, subtotal)) {
      String accountCode = accountByLine.get(member);
      if (accountCode == null) {
        fail("справка " + reportType + ": " + subtotal + " сочи липсващ ред " + member);
      }
      out.addAll(tokens(accountCode));
    }
    return out;
  }

  // ─── твърдения ───

  private static void assertAccountType(String code, String expectedType, String what) {
    String[] row = rowsByCode(ACCOUNTS).get(code);
    if (row == null) {
      fail("сметка " + code + " липсва в BG_COA");
    }
    assertEquals(
        expectedType,
        col(header(ACCOUNTS), row, "accountType.importId"),
        code + ": типът решава в кой раздел се явява сметката — очаква се " + what);
  }

  /** Сметката има детайлен ред в справката И този ред е изброен в назования сбор. */
  private static void assertSummedIn(String accountCode, String reportType, String subtotal) {
    String detail = detailLineFor(reportType, accountCode);
    assertTrue(
        detail != null,
        "справка "
            + reportType
            + ": няма ред с accountCode "
            + accountCode
            + ". Без него сметката не се сумира никъде — нито родителят ѝ, нито типът ѝ я внасят");

    List<String> members = members(reportType, subtotal);
    assertTrue(
        members.contains(detail),
        "справка "
            + reportType
            + ": сборът "
            + subtotal
            + " не изброява "
            + detail
            + ". Детайлните редове са hidden=true и съществуват САМО за да влязат в правило —"
            + " забравен там, редът се смята и се изхвърля. Правилото е: "
            + members);
  }

  // ─── четене ───

  /** Кодът на детайлния ред за тази сметка в тази справка, или {@code null}. */
  private static String detailLineFor(String reportType, String accountCode) {
    List<String> header = header(REPORT_LINES);
    for (String[] row : rows(REPORT_LINES)) {
      if (reportType.equals(col(header, row, "reportType_importId"))
          && accountCode.equals(col(header, row, "accountCode"))) {
        return col(header, row, "code");
      }
    }
    return null;
  }

  /**
   * Събираемите на един сбор, цепени на ТОКЕНИ.
   *
   * <p>⚠️ Сравнение по подниз тук лъже: {@code P_3_3_4091} е префикс на {@code P_3_3_40901}, а
   * преди преномерирането заварената двойка беше {@code P_3_3_409} / {@code P_3_3_4091}. Груувският
   * парсер ги различава като отделни идентификатори; проверката трябва да прави същото, инак „вече
   * е там“ се отчита за ред, който го няма.
   */
  private static List<String> members(String reportType, String subtotal) {
    List<String> header = header(REPORT_LINES);
    for (String[] row : rows(REPORT_LINES)) {
      if (reportType.equals(col(header, row, "reportType_importId"))
          && subtotal.equals(col(header, row, "code"))) {
        return Arrays.asList(col(header, row, "rule").split("\\s*\\+\\s*"));
      }
    }
    fail("справка " + reportType + ": липсва сборът " + subtotal);
    return null;
  }

  /** Стойността на {@code accountCode} се цепи на ЗАПЕТАЯ — всеки токен е отделен {@code LIKE}. */
  private static List<String> tokens(String accountCode) {
    List<String> out = new ArrayList<>();
    for (String token : accountCode.split(",")) {
      if (!token.trim().isEmpty()) {
        out.add(token.trim());
      }
    }
    return out;
  }

  /**
   * Кодовете от плана, които този токен лови по {@code LIKE} семантика.
   *
   * <p>⚠️ {@code %} е произволен низ, {@code _} е ЕДИН знак — второто се забравя лесно, а
   * подчертавка в код на сметка би станала шаблон. Всичко останало се сравнява буквално. Това е
   * същото, което прави {@code AccountingReportValueMoveLineServiceImpl#mergeWithAccountCode};
   * проверка по равенство тук би обявила всяко листо под wildcard за сирак.
   */
  private static Set<String> matching(String token) {
    // „702@30“ — сметката 702, само в статиите със 30x (BgReportAccountMap): търси се 702.
    token = com.axelor.apps.bglocalization.service.accounting.BgReportAccountMap.pattern(token);
    StringBuilder regex = new StringBuilder();
    for (char c : token.toCharArray()) {
      if (c == '%') {
        regex.append(".*");
      } else if (c == '_') {
        regex.append('.');
      } else {
        regex.append(java.util.regex.Pattern.quote(String.valueOf(c)));
      }
    }
    java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(regex.toString());
    Set<String> hit = new LinkedHashSet<>();
    for (String code : rowsByCode(ACCOUNTS).keySet()) {
      if (pattern.matcher(code).matches()) {
        hit.add(code);
      }
    }
    return hit;
  }

  private static List<String> header(Path file) {
    return Arrays.asList(unquote(lines(file).get(0)));
  }

  private static List<String[]> rows(Path file) {
    List<String> lines = lines(file);
    List<String[]> out = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      if (!lines.get(i).isEmpty()) {
        out.add(unquote(lines.get(i)));
      }
    }
    return out;
  }

  private static Map<String, String[]> rowsByCode(Path file) {
    Map<String, String[]> byCode = new LinkedHashMap<>();
    for (String[] row : rows(file)) {
      byCode.put(row[0], row);
    }
    return byCode;
  }

  private static String col(List<String> header, String[] row, String name) {
    int i = header.indexOf(name);
    if (i < 0) {
      fail("няма колона " + name + " — заглавният ред е " + header);
    }
    return i < row.length ? row[i] : "";
  }

  private static List<String> lines(Path file) {
    if (!Files.exists(file)) {
      fail("липсва " + file.toAbsolutePath());
    }
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Цепи ред с QUOTE_ALL колони: правилата носят плюсове, а описанията — запетаи и кавички. */
  private static String[] unquote(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder current = new StringBuilder();
    boolean inQuotes = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          current.append('"');
          i++;
        } else {
          inQuotes = !inQuotes;
        }
      } else if (c == ';' && !inQuotes) {
        out.add(current.toString());
        current.setLength(0);
      } else {
        current.append(c);
      }
    }
    out.add(current.toString());
    return out.toArray(new String[0]);
  }
}
