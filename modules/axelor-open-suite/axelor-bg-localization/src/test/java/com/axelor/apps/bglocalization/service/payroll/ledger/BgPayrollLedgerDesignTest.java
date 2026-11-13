package com.axelor.apps.bglocalization.service.payroll.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПЕЧАТНАТА ВЕДОМОСТ — <b>структурата, проверена без BIRT и без база</b>, по образеца на {@code
 * BgPaySlipReportDesignTest}.
 *
 * <p><b>⚠️ КАКВО ДОКАЗВА И КАКВО НЕ.</b> Доказва, че образецът е СЪГЛАСЕН СЪС САМИЯ СЕБЕ СИ: всяка
 * стойност, която чете, има ред в набора данни, връзка и клетка, в която се показва; всяка парична
 * колона има свой сбор И свое число за сверка. НЕ доказва, че PDF-ът излиза красив — това се вижда
 * само с очи. Че имената на колоните съществуват, се сверява в {@code BgReportQueryColumnTest}; че
 * заявката се ИЗПЪЛНЯВА — в {@code BgReportQueryDbTest}; че ЧИСЛАТА са верни — в {@link
 * BgPayrollLedgerTest}.
 *
 * <p><b>⚠️ И ЕДИН СВИДЕТЕЛ, КОЙТО НЕ Е ЗА ОБРАЗЕЦА, А ЗА ПАДАЩИЯ СПИСЪК.</b> Стойностите в {@code
 * bg.payroll.ledger.variant.select} СА имената на {@link BgPayrollLedgerSelection}. Преименувана
 * стойност там не дава нито грешка, нито празен екран — {@code BgPayrollLedgerSelection#of} връща
 * подразбирането и човекът получава ВЕДОМОСТТА НА ТРУДОВИТЕ ДОГОВОРИ, докато на екрана пише
 * „Самоосигуряващи се“. Тих грешен лист.
 */
class BgPayrollLedgerDesignTest {

  private static final String DESIGN = "src/main/resources/reports/BgPayrollLedger.rptdesign";
  private static final String SELECTS = "src/main/resources/views/Selects.xml";

  /** Паричните колони — всяка от тях иска ТРИ неща: колона, сбор и число за сверка. */
  private static final List<String> MONEY_COLUMNS =
      Arrays.asList(
          "gross_total",
          "employee_contributions",
          "tax_amount",
          "advance_amount",
          "deductions",
          "net_total",
          "employer_contributions",
          "employer_cost");

  /** Колоните, които не са пари, но пак трябва да стигнат до хартията. */
  private static final List<String> TEXT_COLUMNS =
      Arrays.asList(
          "slip_reference",
          "first_name",
          "middle_name",
          "last_name",
          "personal_id",
          "personal_id_type",
          "insured_type_code",
          "status_select",
          "worked_days");

  @Test
  @DisplayName("всяка колона от набора данни стига до връзка на таблицата")
  void everyQueriedColumnIsBound() throws IOException {
    String design = design();
    List<String> missing = new ArrayList<>();

    for (String column : TEXT_COLUMNS) {
      if (!design.contains("dataSetRow[\"" + column + "\"]")) {
        missing.add(column);
      }
    }
    for (String column : MONEY_COLUMNS) {
      if (!design.contains("dataSetRow[\"" + column + "\"]")) {
        missing.add(column);
      }
    }

    assertTrue(
        missing.isEmpty(),
        "⚠️ Колона, която заявката чете, а нищо не показва, е мълчалива липса: заявката става"
            + " по-скъпа, а листът остава без графата. "
            + missing);
  }

  /**
   * ⚠️ ТРИТЕ НЕЩА НА ВСЯКА ПАРИЧНА КОЛОНА. Липсващият сбор дава ред „Общо“ с празна клетка —
   * изглежда като нула. Липсващото число за сверка маха точно онази проверка, заради която редът
   * „Сверка“ съществува: че сборът на отпечатаното и сборът, сметнат от перата, са едно и също.
   */
  @Test
  @DisplayName("⚠️ всяка парична колона има И сбор, И число за сверка")
  void everyMoneyColumnHasATotalAndACrossCheck() throws IOException {
    String design = design();
    List<String> missing = new ArrayList<>();

    for (String column : MONEY_COLUMNS) {
      if (!design.contains("<property name=\"name\">sum_" + column + "</property>")) {
        missing.add("липсва сборът sum_" + column);
      }
      if (!design.contains("<property name=\"resultSetColumn\">sum_" + column + "</property>")) {
        missing.add("сборът sum_" + column + " не се показва в нито една клетка");
      }
    }

    // ⚠️ Имената на параметрите за сверка са ИЗВЕДЕНИ от името на колоната по едно правило —
    // gross_total → CheckGross. Списъкът им се чете от самия образец, а не се преписва тук: така
    // тестът пада при ЛИПСВАЩ параметър, а не при преименуван.
    Set<String> checkParams = matches(design, "<scalar-parameter name=\"(Check[A-Za-z]+)\"");
    assertEquals(
        MONEY_COLUMNS.size(),
        checkParams.size(),
        "⚠️ По едно число за сверка на всяка парична колона — нито повече, нито по-малко: "
            + checkParams);
    for (String param : checkParams) {
      assertTrue(
          design.contains("params[\"" + param + "\"]"),
          "⚠️ Обявен, но непоказан параметър за сверка е по-лош от липсващ: услугата го смята и"
              + " подава, а листът мълчи. "
              + param);
    }

    assertTrue(missing.isEmpty(), String.join("\n  ", missing));
  }

  /**
   * ⚠️ РАЗМИНАВАНЕТО СЕ СМЯТА, А НЕ СЕ ТЪРСИ С ОЧИ. Два реда по осем числа значат шестнайсет числа
   * за сверяване на всеки лист; без този ред човек ги сверява само когато вече подозира нещо.
   */
  @Test
  @DisplayName("⚠️ листът сам казва, когато двата реда със суми се разминават")
  void theSheetSaysWhenTheTwoTotalRowsDisagree() throws IOException {
    String design = design();
    assertTrue(design.contains("mismatch_note"), "липсва самата проверка за разминаване");
    assertTrue(
        design.contains("<property name=\"resultSetColumn\">mismatch_note</property>"),
        "проверката съществува, но не се показва — тоест не съществува");
    for (String column : MONEY_COLUMNS) {
      assertTrue(
          design.contains("row[\"sum_" + column + "\"]"),
          "⚠️ Колона, изпусната от проверката за разминаване, е точно колоната, в която дефектът ще"
              + " остане невидим: "
              + column);
    }
  }

  /**
   * ⚠️ ДВАТА ПАРАМЕТЪРА НА ЗАЯВКАТА СА ЕДИН И СЪЩ НИЗ, НО НА ДВЕ ПОЗИЦИИ. Първият реже редовете,
   * вторият им дава реда. Разменени или свързани с различни параметри, ведомостта би излязла с
   * верни редове в произволен ред — и това не пада никъде.
   */
  @Test
  @DisplayName("⚠️ заявката взима номерата за ФИЛТЪРА и за ПОДРЕДБАТА, точно в този ред")
  void theQueryTakesTheIdsForBothTheFilterAndTheOrder() throws IOException {
    String design = design();
    String query = query(design);

    assertEquals(
        2,
        query.length() - query.replace("?", "").length(),
        "⚠️ Заявката иска точно два параметъра. Промяна в броя им иска промяна и в набора"
            + " параметри на набора данни — инак BIRT връзва не онова, което SQL чака.");
    assertTrue(
        query.contains("string_to_array(?, ',')") && query.contains("array_position("),
        "номерата влизат като масив, а редът идва от позицията в него:\n" + query);
    assertTrue(
        query.indexOf("where") < query.indexOf("order by"),
        "филтърът е първият параметър, подредбата — вторият");

    List<String> datasetParams =
        orderedMatches(design, "<property name=\"paramName\">([A-Za-z]+)</property>");
    assertEquals(
        Arrays.asList("SlipIds", "SlipIdsOrder"),
        datasetParams,
        "⚠️ Позицията на параметъра е онова, което BIRT подава на JDBC. Разменени, филтърът и"
            + " подредбата си сменят местата, а и двете са низове — тоест нищо не гърми.");
  }

  /**
   * ⚠️ И КОЛОНИТЕ НА ХАРТИЯТА СА КОЛКОТО КЛЕТКИТЕ. Ред с една клетка по-малко не е крива таблица —
   * BIRT мести съдържанието наляво и числата сядат под ЧУЖДИ заглавия.
   */
  @Test
  @DisplayName("⚠️ заглавният ред, редът с данни и двата реда със суми имат еднакъв брой клетки")
  void everyRowHasAsManyCellsAsThereAreColumns() throws IOException {
    String design = design();
    int columns = count(design, "<column id=");
    assertTrue(columns > 0, "таблицата няма нито една обявена колона");

    assertEquals(columns, cellsIn(design, "header"), "заглавният ред");
    assertEquals(columns, cellsIn(design, "detail"), "редът с данни");

    // ⚠️ Долният блок носи ТРИ реда: „Общо“, „Сверка“ и редът с предупреждението. Първите два имат
    // по колкото колони, третият е ЕДНА клетка през целия ред.
    int footerCells = cellsIn(design, "footer");
    assertEquals(
        2 * columns + 1,
        footerCells,
        "⚠️ Долният блок: два пълни реда със суми плюс един ред с една клетка за предупреждението");
    assertTrue(
        design.contains("<property name=\"colSpan\">" + columns + "</property>"),
        "редът с предупреждението минава през ЦЕЛИЯ лист, а не стои в първата колона");
  }

  /**
   * ТРИТЕ КАПАНА НА BIRT, ХВАНАТИ ПРИ РЕНДИРАНЕ — и трите МЪЛЧАТ, тоест не падат никъде другаде.
   *
   * <p><b>1. {@code params["X"]} В RHINO Е ВИНАГИ ИСТИНА</b>, дори за празен низ: параметърът не е
   * низ, а обект. Идиомът {@code params["X"] ? … : …} печата клона „има дружество“ с ПРАЗНО име
   * вместо изречението за целия период — тоест листът би твърдял обхват, какъвто няма. Пита се
   * дължината.
   *
   * <p><b>2. U+26A0 (⚠) НЯМА ГЛИФ В ШРИФТА НА PDF-ЕМИТЕРА</b> и изчезва без грешка: маркерът на
   * предупреждението става водещ ИНТЕРВАЛ. Върху хартията маркерът е {@code (!)}.
   *
   * <p><b>3. В CDATA НА {@code text-property} НЯМА XML РАЗЕСКАПИРАНЕ</b>: написано там, {@code
   * &amp;gt;} стига до Rhino дословно и поваля ЦЕЛИЯ отчет с „missing ) in parenthetical“. Вътре в
   * {@code <expression>} същото entity е вярно — разликата е само в носителя, и точно затова се
   * бърка. Правило: в CDATA не се пише нито едно entity.
   */
  @Test
  @DisplayName("⚠️ трите капана на BIRT: истинността на params, липсващият глиф, entity в CDATA")
  void theThreeBirtTrapsStayFixed() throws IOException {
    String design = design();

    assertFalse(
        Pattern.compile("params\\[\"[A-Za-z]+\"\\]\\s*\\?").matcher(design).find(),
        "⚠️ Тернар върху params е ВИНАГИ истина — празният низ минава за попълнен. Пита се"
            + " дължината: (params[..] + \"\").length.");

    // ⚠️ Пита се само онова, което СТИГА ДО ХАРТИЯТА. Описанието на образеца е документация за
    // четящия файла и никога не се рендира — забранен там, знакът щеше да бъде забранен и в
    // изречението, което обяснява защо е забранен.
    assertFalse(
        printable(design).contains("\u26a0") || printable(design).contains("⚠"),
        "⚠️ Знакът за предупреждение върху ХАРТИЯТА изчезва без глиф. Маркерът е (!).");

    for (String cdata : orderedMatches(design, "<!\\[CDATA\\[(.*?)]]>")) {
      assertFalse(
          cdata.contains("&gt;") || cdata.contains("&lt;") || cdata.contains("&amp;"),
          "⚠️ Entity вътре в CDATA стига до Rhino дословно и поваля отчета при РЕНДИРАНЕ: "
              + cdata);
    }

    assertTrue(
        design.contains("<value-of format=\"html\">params[\"BoundaryReport\"]</value-of>"),
        "⚠️ Без format=\"html\" <value-of> ЕКРАНИРА изхода и докладът излиза на един ред с"
            + " етикети в него.");
  }

  /**
   * ⚠️ ПРАЗНИЯТ ПАРАМЕТЪР НЕ БИВА ДА ОТКАЗВА ЛИСТА — И ТОВА Е ИЗМЕРЕНО, А НЕ ПРЕДПОЛОЖЕНО.
   *
   * <p>Скаларният параметър в BIRT е ЗАДЪЛЖИТЕЛЕН по подразбиране, а празният низ се брои за
   * ЛИПСВАЩ: рендирането отказва с „Required parameter … is blank“ ПРЕДИ изобщо да е стигнало до
   * заявката. Три от параметрите тук са празни при съвсем законни обстоятелства — списъкът с номера
   * (разрез без лица, например месец без граждански договори), името на дружеството (когато
   * обхватът е целият период) и докладът за границите. Тоест без този ред ведомостта хвърля точно в
   * случаите, в които трябва да отпечата лист с нули.
   */
  @Test
  @DisplayName("⚠️ нито един параметър не е задължителен — празният низ е законен отговор")
  void noParameterIsRequired() throws IOException {
    String design = design();
    int declared = count(design, "<scalar-parameter name=");
    assertTrue(declared > 0, "образецът няма нито един параметър");
    assertEquals(
        declared,
        count(design, "<property name=\"isRequired\">false</property>"),
        "⚠️ Всеки параметър трябва да е обявен за незадължителен. Пропуснатият пада само когато е"
            + " празен — тоест точно на празната ведомост, която е законно състояние.");
    assertEquals(
        declared,
        count(design, "<property name=\"allowBlank\">true</property>"),
        "и празният НИЗ е различен въпрос от липсващата стойност — BIRT пита и двата");
  }

  /**
   * ⚠️ ПАДАЩИЯТ СПИСЪК И ИЗБРОЯВАНЕТО СА ЕДНО И СЪЩО, БУКВА ПО БУКВА. Разминаването тук е
   * най-тихата възможна грешка във ведомостта: {@code BgPayrollLedgerSelection#of} връща
   * подразбирането за непознато име, тоест човекът избира „Самоосигуряващи се“ и получава
   * ведомостта на ТРУДОВИТЕ ДОГОВОРИ — с верни числа, на грешния лист, без нито едно съобщение.
   */
  @Test
  @DisplayName("⚠️ падащият списък изрежда ТОЧНО стойностите на BgPayrollLedgerSelection")
  void theSelectionListMatchesTheEnum() throws IOException {
    String selects =
        new String(Files.readAllBytes(module().resolve(SELECTS)), StandardCharsets.UTF_8);
    int start = selects.indexOf("bg.payroll.ledger.variant.select");
    assertTrue(start > 0, "падащият списък го няма в Selects.xml");
    String block = selects.substring(start, selects.indexOf("</selection>", start));

    List<String> onScreen = orderedMatches(block, "<option value=\"([A-Z]+)\"");
    List<String> inCode = new ArrayList<>();
    for (BgPayrollLedgerSelection selection : BgPayrollLedgerSelection.values()) {
      inCode.add(selection.name());
    }
    assertEquals(inCode, onScreen, "стойностите и редът им");

    for (BgPayrollLedgerSelection selection : BgPayrollLedgerSelection.values()) {
      assertEquals(
          selection,
          BgPayrollLedgerSelection.of(selection.name()),
          "стойността от екрана се разпознава от кода");
      assertTrue(
          block.contains(">" + selection.getLabel() + "<"),
          "⚠️ Заглавието на екрана и заглавието върху хартията са едно и също: човек, който избира"
              + " „"
              + selection.getLabel()
              + "“, трябва да го прочете и на листа. "
              + block);
    }
  }

  // ── помощници ───────────────────────────────────────────────────────────

  /** Образецът БЕЗ собственото си описание — тоест само онова, което може да излезе на хартия. */
  private static String printable(String design) {
    int from = design.indexOf("<text-property name=\"description\">");
    if (from < 0) {
      return design;
    }
    int to = design.indexOf("</text-property>", from);
    return design.substring(0, from) + design.substring(to);
  }

  private static String design() throws IOException {
    return new String(Files.readAllBytes(module().resolve(DESIGN)), StandardCharsets.UTF_8);
  }

  private static String query(String design) {
    Matcher m =
        Pattern.compile(
                "<xml-property name=\"queryText\"><!\\[CDATA\\[(.*?)]]></xml-property>",
                Pattern.DOTALL)
            .matcher(design);
    assertTrue(m.find(), "образецът няма заявка");
    return m.group(1);
  }

  /** Клетките между {@code <блок>} и {@code </блок>} на таблицата. */
  private static int cellsIn(String design, String block) {
    int from = design.indexOf("<" + block + ">");
    int to = design.indexOf("</" + block + ">", from);
    assertTrue(from > 0 && to > from, "образецът няма блок „" + block + "“");
    return count(design.substring(from, to), "<cell id=");
  }

  private static int count(String text, String needle) {
    int n = 0;
    int at = text.indexOf(needle);
    while (at >= 0) {
      n++;
      at = text.indexOf(needle, at + needle.length());
    }
    return n;
  }

  private static Set<String> matches(String text, String regex) {
    Set<String> out = new LinkedHashSet<>(orderedMatches(text, regex));
    return out;
  }

  private static List<String> orderedMatches(String text, String regex) {
    List<String> out = new ArrayList<>();
    Matcher m = Pattern.compile(regex).matcher(text);
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }

  /** Коренът на модула — тестът се пуска и от корена на репото, и от модула. */
  private static Path module() {
    Path here = Paths.get("").toAbsolutePath();
    for (int i = 0; i < 12 && here != null; i++) {
      if (Files.isRegularFile(here.resolve(DESIGN))) {
        return here;
      }
      Path nested = here.resolve("modules/axelor-open-suite/axelor-bg-localization");
      if (Files.isRegularFile(nested.resolve(DESIGN))) {
        return nested;
      }
      here = here.getParent();
    }
    throw new IllegalStateException(
        "не намирам " + DESIGN + " спрямо " + Paths.get("").toAbsolutePath());
  }
}
