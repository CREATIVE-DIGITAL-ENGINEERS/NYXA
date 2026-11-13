package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.fixedasset.BgTaxDepreciation;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Пазачи на seed-а с българските категории дълготрайни активи по чл. 55 ЗКПО.
 *
 * <p><b>Защо тестът е ТУК, а не при другите seed пазачи.</b> {@code BgSeedCsvArityTest}, {@code
 * BgSeedImportIdUniquenessTest} и {@code BgImportBindingTest} гледат само {@code
 * src/main/resources/apps/init-data} на ТОЗИ модул. Категориите обаче не идват през App Install: те
 * са част от българския сметкоплан и живеят в {@code
 * axelor-account/…/l10n/l10n_bgr/BG_COA/account_fixedAssetCategory.csv}, който се внася през {@code
 * AccountingConfigTemplate} (зип от 29 CSV-та) и {@code l10n/chart-config.xml}. Тоест досега НИТО
 * ЕДИН пазач не е гледал този файл.
 *
 * <p><b>Файлов, нарочно.</b> Без Postgres и без импорт: всичко тук има отговор от файла. И е важно
 * да е така — базата не помни какво е поискал файлът. Импортът в {@code chart-config.xml}
 * автоматично връзва (autoBind) всяка колона, която не е изрично вързана, а {@code
 * CSVBinder.bind()} проверява {@code mapper.getProperty(bean, field)} и при {@code null} праща
 * колоната в {@code handleDummyBind} — тоест сгрешено име на колона се ПРЕНЕБРЕГВА мълчаливо и
 * импортът приключва зелен с празно поле.
 */
class BgFixedAssetCategorySeedTest {

  private static final String ENTITY = "com.axelor.apps.account.db.FixedAssetCategory";

  private static final Path SEED =
      BgDomainColumns.modulesRoot()
          .resolve(
              "axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA/"
                  + "account_fixedAssetCategory.csv");

  private static final Path ACCOUNTS =
      BgDomainColumns.modulesRoot()
          .resolve(
              "axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA/"
                  + "account_account.csv");

  private static final Path JOURNALS =
      BgDomainColumns.modulesRoot()
          .resolve(
              "axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA/"
                  + "account_journal.csv");

  private static final Path TYPES =
      BgDomainColumns.modulesRoot()
          .resolve(
              "axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA/"
                  + "account_fixedAssetType.csv");

  private static final Path CHART_CONFIG =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/chart-config.xml");

  /**
   * Земята: единственият ред без данъчен план. Държи се по номер, защото по норма не се различава.
   */
  private static final String LAND_IMPORT_ID = "49";

  /**
   * Полета, които домейнът НЕ обявява, а генераторът все пак дава.
   *
   * <p>{@code importId} и {@code importOrigin} се наследяват от {@code AuditableModel} — виж как
   * никой домейн XML в тази база не ги декларира.
   */
  private static final Set<String> INHERITED =
      new LinkedHashSet<>(Arrays.asList("importId", "importOrigin"));

  // ------------------------------------------------------------------ форма на файла

  @Test
  @DisplayName("всеки ред носи точно толкова колони, колкото обявява заглавието")
  void всеки_ред_е_с_еднаква_дължина() throws IOException {
    List<List<String>> rows = rows();
    int width = rows.get(0).size();
    assertTrue(width > 1, "заглавието на " + SEED + " е с една колона — грешен разделител?");
    for (int i = 1; i < rows.size(); i++) {
      assertEquals(
          width,
          rows.get(i).size(),
          "ред "
              + (i + 1)
              + " на "
              + SEED.getFileName()
              + " е с "
              + rows.get(i).size()
              + " колони вместо "
              + width
              + " — един неекраниран „;“ отнася останалите полета мълчаливо");
    }
  }

  @Test
  @DisplayName("никой importId не се среща два пъти")
  void номерата_са_уникални() throws IOException {
    Map<String, Integer> seen = new LinkedHashMap<>();
    List<Map<String, String>> data = data();
    for (int i = 0; i < data.size(); i++) {
      String id = data.get(i).get("importId");
      Integer first = seen.put(id, i + 2);
      if (first != null) {
        fail(
            "importId "
                + id
                + " се среща на редове "
                + first
                + " и "
                + (i + 2)
                + ": импортът е upsert по номер и НЕ трие — вторият ред просто обновява първия,"
                + " а вносителят приключва зелен");
      }
    }
  }

  @Test
  @DisplayName("всяка колона в заглавието е истинско поле или изрично вързан параметър")
  void колоните_отговарят_на_полета() throws IOException {
    Map<String, BgDomainColumns.FieldSpec> fields = BgDomainColumns.allFields().get(ENTITY);
    assertNotNull(fields, "не намирам декларациите на " + ENTITY + " в нито един домейн XML");
    Set<String> boundParams = explicitBindParameters();
    assertFalse(
        boundParams.isEmpty(),
        "не намирам изрично вързани параметри за account_fixedAssetCategory.csv в "
            + CHART_CONFIG
            + " — ако входът е бил преместен, тестът мълчи вместо да мери");

    List<String> unknown = new ArrayList<>();
    for (String column : rows().get(0)) {
      String root = column.contains(".") ? column.substring(0, column.indexOf('.')) : column;
      if (INHERITED.contains(root) || fields.containsKey(root) || boundParams.contains(column)) {
        continue;
      }
      unknown.add(column);
    }
    assertTrue(
        unknown.isEmpty(),
        "колони, които не са ни поле, ни вързан параметър: "
            + unknown
            + ". autoBind изпраща такава колона в handleDummyBind и я ПРЕНЕБРЕГВА мълчаливо —"
            + " импортът минава зелен, а полето остава празно");
  }

  // ------------------------------------------------------------------ съдържание

  @Test
  @DisplayName("петнайсет категории: четиринайсет амортизируеми и земята")
  void броят_и_земята() throws IOException {
    List<Map<String, String>> data = data();
    assertEquals(
        15,
        data.size(),
        "очаквам 14 амортизируеми категории плюс земите. Четиринайсет, а не дванайсет: чл. 55"
            + " ал. 8 (нова, ДВ бр. 30 от 2026 г.) дава на електрическите автомобили от кат. V"
            + " норма до 50%, а ал. 7 т. 2 дава 100% и на компютрите и телефоните, НА КОИТО е"
            + " инсталиран софтуер от списъка по чл. 118 ал. 16 ЗДДС — не само на самия софтуер");

    List<Map<String, String>> land = new ArrayList<>();
    for (Map<String, String> row : data) {
      if (LAND_IMPORT_ID.equals(row.get("importId"))) {
        land.add(row);
      }
    }
    assertEquals(1, land.size(), "земите се държат на importId " + LAND_IMPORT_ID);

    Map<String, String> земя = land.get(0);
    assertEquals(
        "0",
        земя.get("depreciationPlanSelect"),
        "земята не се амортизира нито счетоводно (СС 4 т. 3), нито данъчно (не е данъчен"
            + " амортизируем актив по чл. 50 ЗКПО) — планът ѝ е изричната нула „0“ (None), а не"
            + " празно. Празното не се различава от „никой не е попълвал“, а"
            + " FixedAssetManagementRepository.save() го замества с „0“ така или иначе — тоест"
            + " изричната нула е и единственото, което оцелява след първия запис");
    assertEquals(
        "",
        земя.get("bgTaxDepreciationRate"),
        "земята няма данъчна норма — с норма BgFixedAssetLineTaxComputationServiceImpl би построил ДАП");
    assertEquals(
        "", земя.get("bgTaxCategorySelect"), "земята не е в нито една категория по чл. 55");
  }

  @Test
  @DisplayName("броят месеци на всяка категория излиза точно от нормата: 1200/ГДАН")
  void месеците_излизат_от_нормата() throws IOException {
    for (Map<String, String> row : amortizable()) {
      BigDecimal rate = new BigDecimal(row.get("bgTaxDepreciationRate"));
      BigDecimal months = BgTaxDepreciation.monthsForRate(rate).stripTrailingZeros();
      assertTrue(
          months.scale() <= 0,
          "норма "
              + rate
              + "% на „"
              + row.get("name")
              + "“ не дава цяло число месеци: 1200/"
              + rate
              + " = "
              + months
              + ". Планът остава верен — последният месец поема остатъка — но seed-ът не може да"
              + " носи същото число в numberOfDepreciation, тъй че счетоводният план би тръгнал с"
              + " друга сума и решението „нула временни разлики“ би паднало тихо");
      assertEquals(
          months.toBigIntegerExact().toString(),
          row.get("numberOfDepreciation"),
          "„"
              + row.get("name")
              + "“: numberOfDepreciation трябва да е 1200/"
              + rate
              + " = "
              + months.toBigIntegerExact()
              + " месеца. Това поле храни СЧЕТОВОДНИЯ план, а нормата — данъчния; разминати ли са,"
              + " двата плана тръгват с различни суми");
    }
  }

  @Test
  @DisplayName("периодичността е месечна, а срокът е периодичност × брой (формулата на изгледа)")
  void срокът_е_съгласуван() throws IOException {
    for (Map<String, String> row : data()) {
      String name = row.get("name");
      assertEquals(
          "1",
          row.get("periodicityTypeSelect"),
          "„"
              + name
              + "“: периодичността е МЕСЕЦ (1) — чл. 58 ал. 1 пуска амортизацията от месец,"
              + " чл. 59 я спира по месеци, и точно в месеци всяка норма по чл. 55 дава цяло число"
              + " периоди");
      assertEquals("1", row.get("periodicityInMonth"), "„" + name + "“: един месец в период");

      int number = Integer.parseInt(row.get("numberOfDepreciation"));
      int periodicity = Integer.parseInt(row.get("periodicityInMonth"));
      assertEquals(
          Integer.toString(periodicity * number),
          row.get("durationInMonth"),
          "„"
              + name
              + "“: durationInMonth трябва да е periodicityInMonth × numberOfDepreciation —"
              + " това е формулата, с която самият изглед го изчислява"
              + " (action-fixed-asset-category-record-compute-duration), тъй че разминат seed се"
              + " самопоправя при първия ръчен запис и стойността в базата зависи от това дали"
              + " някой е отварял формата");
    }
  }

  @Test
  @DisplayName("нито една норма не надхвърля тавана по чл. 55")
  void нормите_не_надхвърлят_тавана() throws IOException {
    for (Map<String, String> row : amortizable()) {
      String category = row.get("bgTaxCategorySelect");
      BigDecimal rate = new BigDecimal(row.get("bgTaxDepreciationRate"));
      assertNotNull(
          BgTaxDepreciation.ceilingForCategory(category),
          "„"
              + row.get("name")
              + "“ сочи категория „"
              + category
              + "“, която чл. 55 ал. 1 не познава");
      assertFalse(
          BgTaxDepreciation.exceedsCeiling(category, rate),
          "„"
              + row.get("name")
              + "“: норма "
              + rate
              + "% надхвърля тавана по чл. 55 за категория "
              + category
              + " ("
              + BgTaxDepreciation.ceilingForCategory(category)
              + "%) — по-висока норма не се признава за данъчни цели");
    }
  }

  @Test
  @DisplayName("амортизируемите носят и данъчен, и счетоводен план, без дерогативен")
  void плановете_са_данъчен_и_счетоводен() throws IOException {
    for (Map<String, String> row : amortizable()) {
      assertEquals(
          "1,2",
          row.get("depreciationPlanSelect"),
          "„"
              + row.get("name")
              + "“: планът е „1,2“ — данъчен (извънсчетоводен ДАП) плюс счетоводен (осчетоводяван)."
              + " „3“ (дерогативен) НЕ се ползва: българското данъчно право не познава дерогативна"
              + " амортизация, а планът я осчетоводява и би пуснал статии, които никой не иска");
    }
  }

  @Test
  @DisplayName("сметките: разход 603, натрупана 241 за ДМА и 242 за ДНА")
  void сметките_по_вид_актив() throws IOException {
    for (Map<String, String> row : data()) {
      String name = row.get("name");
      assertEquals("603", row.get("chargeAccount_code"), "„" + name + "“: разходът е Дт 603");
      String type = row.get("fixedAssetType.code");
      String expected = "FA_IN".equals(type) ? "242" : "241";
      assertEquals(
          expected,
          row.get("depreciationAccount_code"),
          "„"
              + name
              + "“ е "
              + type
              + ", тъй че натрупаната амортизация е Кт "
              + expected
              + " (241 за ДМА, 242 за ДНА). chargeAccount е ДЕБИТНАТА страна, а depreciationAccount"
              + " — КРЕДИТНАТА (FixedAssetLineMoveServiceImpl, редове 530–572)");
      assertTrue(
          Arrays.asList("FA_TA", "FA_IN").contains(type),
          "„" + name + "“: вид актив " + type + " не е нито ДМА (FA_TA), нито ДНА (FA_IN)");
    }
  }

  @Test
  @DisplayName("сметките за отписване и продажба са попълнени — иначе Axelor хвърля изключение")
  void сметките_за_изход_са_налице() throws IOException {
    for (Map<String, String> row : data()) {
      String name = row.get("name");
      assertEquals(
          "705",
          row.get("realisedAssetsIncomeAccount_code"),
          "„"
              + name
              + "“: без приход от продажба (705) generateSaleMove хвърля"
              + " IMMO_FIXED_ASSET_GENERATE_SALE_MOVE_CATEGORY_ACCOUNTS_MISSING");
      // ⚠️ ОТ PR-N2 ТОВА Е 60908, А НЕ 609. 609 „Други разходи“ стана репортен ВЪЗЕЛ (view) и вече
      // не се избира за статия; НАП дава на балансовата стойност собствено листо — 60908
      // „Балансова стойност на отписани дълготрайни активи“. Оставен 609, всеки опит за продажба
      // на актив щеше да осчетоводява по възел и Move-ът да хвърля ПРИ ЗАПИС, в чужда транзакция.
      assertEquals(
          "60908",
          row.get("realisedAssetsValueAccount_code"),
          "„"
              + name
              + "“: без разходна сметка за балансовата стойност generateDisposalMove хвърля"
              + " при ПРОДАЖБА (FixedAssetLineMoveServiceImpl, ред 665). Изгледът иска тук сметка от"
              + " тип charge, тъй че 705 не може да стои и на двете страни, както е в класическата"
              + " българска схема");
      // ⚠️ НЕ 691. По NRA_Nom_Accounts 691 е „Разходи от отписване на кредитни експозиции“, а това
      // поле носи всяко отписване, което не е продажба. 60905 „Разходи за липси и брак на активи“
      // е НАП листото за изчезнал актив (липса); унищожен актив (брак, непреодолима сила) се
      // пренасочва към 60908 от кода на причината (bg_asset_disposal_reason.csv).
      assertEquals(
          "60905",
          row.get("approExtraordDepreciationExpenseAccount_code"),
          "„" + name + "“: без нея generateDisposalMove хвърля при БРАКУВАНЕ (ред 676)");
      assertEquals(
          "411",
          row.get("debtReceivableAccount_code"),
          "„" + name + "“: без вземане (411) статията за продажбата не се съставя (ред 840)");
    }
  }

  @Test
  @DisplayName("дерогативните и IFRS сметките остават празни")
  void неползваните_сметки_са_празни() throws IOException {
    List<String> unused =
        Arrays.asList(
            "capitalDepreciationDerogatoryAccount_code",
            "expenseDepreciationDerogatoryAccount_code",
            "incomeDepreciationDerogatoryAccount_code",
            "ifrsDepreciationAccount_code",
            "ifrsChargeAccount_code");
    for (Map<String, String> row : data()) {
      for (String column : unused) {
        assertEquals(
            "",
            row.get(column),
            "„"
                + row.get("name")
                + "“: "
                + column
                + " трябва да е празна. Дерогативен план не се ползва, а IFRS сметка при"
                + " depreciationPlanSelect без „4“ е мъртва конфигурация, която обещава план,"
                + " каквъвто няма");
      }
    }
  }

  // ------------------------------------------------------------------ външни ключове

  @Test
  @DisplayName("всяка сочена сметка, журнал и вид актив ги има в българския сметкоплан")
  void сочените_записи_съществуват() throws IOException {
    Set<String> accounts = firstColumnValues(ACCOUNTS, "code");
    Set<String> journals = firstColumnValues(JOURNALS, "code");
    Set<String> types = firstColumnValues(TYPES, "code");
    assertFalse(accounts.isEmpty(), "не се чете " + ACCOUNTS);
    assertFalse(journals.isEmpty(), "не се чете " + JOURNALS);
    assertFalse(types.isEmpty(), "не се чете " + TYPES);

    Set<String> missing = new TreeSet<>();
    for (Map<String, String> row : data()) {
      for (Map.Entry<String, String> cell : row.entrySet()) {
        String column = cell.getKey();
        String value = cell.getValue();
        if (value.isEmpty()) {
          continue;
        }
        if (column.endsWith("Account_code") && !accounts.contains(value)) {
          missing.add(column + " = " + value);
        }
        if ("journal.code".equals(column) && !journals.contains(value)) {
          missing.add(column + " = " + value);
        }
        if ("fixedAssetType.code".equals(column) && !types.contains(value)) {
          missing.add(column + " = " + value);
        }
      }
    }
    assertTrue(
        missing.isEmpty(),
        "seed-ът сочи записи, които сметкопланът не съдържа: "
            + missing
            + ". Импортът търси по код и при липса СЪЗДАВА празен бийн вместо да гърми"
            + " (CSVBinder.find), тъй че сгрешен код дава категория без сметка");
  }

  /**
   * Дневникът на категорията се търси по код И дружество. Без изрична връзка платформата връзва
   * колоната {@code journal.code} сама, само по код, и второто дружество с BG_COA получава MOA на
   * първото. Колоната остава {@code journal.code} — зипът на шаблона в заварените бази е с нея;
   * стойността ѝ CSVBinder дава като {@code journal_code_}. Вносът от край до край: {@code
   * BgCoaFixedAssetCategoryJournalPostgresIntegrationTest}.
   */
  @Test
  @DisplayName("дневникът на категорията се търси по код И дружество на вноса")
  void дневникът_е_на_дружеството() throws Exception {
    assertTrue(Files.isRegularFile(CHART_CONFIG), "няма " + CHART_CONFIG);
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    Element root = factory.newDocumentBuilder().parse(CHART_CONFIG.toFile()).getDocumentElement();
    NodeList inputs = root.getElementsByTagNameNS("*", "input");
    List<String> journalBinds = new ArrayList<>();
    for (int i = 0; i < inputs.getLength(); i++) {
      Element input = (Element) inputs.item(i);
      if (!"account_fixedAssetCategory.csv".equals(input.getAttribute("file"))) {
        continue;
      }
      NodeList binds = input.getElementsByTagNameNS("*", "bind");
      for (int b = 0; b < binds.getLength(); b++) {
        Element bind = (Element) binds.item(b);
        if ("journal".equals(bind.getAttribute("to"))) {
          journalBinds.add(
              bind.getAttribute("column")
                  + " | "
                  + bind.getAttribute("search").replaceAll("\\s+", " ")
                  + " | update="
                  + bind.getAttribute("update"));
        }
      }
    }
    assertEquals(
        List.of(
            "journal.code | self.code = :journal_code_ and self.company.id = :_companyId"
                + " | update=true"),
        journalBinds,
        "една връзка на дневника: колоната journal.code, по код и дружество, без нов дневник при"
            + " липса (update=true)");
  }

  // ------------------------------------------------------------------ помощни

  private static List<Map<String, String>> amortizable() throws IOException {
    List<Map<String, String>> out = new ArrayList<>();
    for (Map<String, String> row : data()) {
      if (!LAND_IMPORT_ID.equals(row.get("importId"))) {
        out.add(row);
      }
    }
    return out;
  }

  private static List<Map<String, String>> data() throws IOException {
    List<List<String>> rows = rows();
    List<String> header = rows.get(0);
    List<Map<String, String>> out = new ArrayList<>();
    for (int i = 1; i < rows.size(); i++) {
      Map<String, String> row = new LinkedHashMap<>();
      List<String> cells = rows.get(i);
      for (int c = 0; c < header.size(); c++) {
        row.put(header.get(c), c < cells.size() ? cells.get(c) : "");
      }
      out.add(row);
    }
    return out;
  }

  private static List<List<String>> rows() throws IOException {
    assertTrue(Files.isRegularFile(SEED), "не намирам " + SEED);
    List<List<String>> out = new ArrayList<>();
    for (String line : Files.readAllLines(SEED, StandardCharsets.UTF_8)) {
      if (line.trim().isEmpty()) {
        continue;
      }
      out.add(split(line));
    }
    assertFalse(out.isEmpty(), SEED + " е празен");
    return out;
  }

  /** Разделя ред с точка и запетая, зачитайки кавичките — описанията съдържат запетаи и тирета. */
  private static List<String> split(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (c == ';' && !quoted) {
        cells.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    cells.add(cell.toString());
    return cells;
  }

  private static Set<String> firstColumnValues(Path csv, String column) throws IOException {
    if (!Files.isRegularFile(csv)) {
      return new LinkedHashSet<>();
    }
    List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
    if (lines.isEmpty()) {
      return new LinkedHashSet<>();
    }
    List<String> header = split(lines.get(0));
    int index = header.indexOf(column);
    if (index < 0) {
      return new LinkedHashSet<>();
    }
    Set<String> out = new LinkedHashSet<>();
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).trim().isEmpty()) {
        continue;
      }
      List<String> cells = split(lines.get(i));
      if (index < cells.size()) {
        out.add(cells.get(index));
      }
    }
    return out;
  }

  /**
   * Параметрите, които {@code chart-config.xml} свързва ИЗРИЧНО за този вход — те не са полета и не
   * бива да се търсят като такива.
   *
   * <p>Колоните за сметки се казват {@code chargeAccount_code}, а не {@code chargeAccount.code},
   * защото стоят като {@code :chargeAccount_code} в {@code search} на изричния bind. Търсени като
   * полета, всичките четиринайсет биха се обадили като непознати.
   */
  private static Set<String> explicitBindParameters() {
    Set<String> out = new LinkedHashSet<>();
    if (!Files.isRegularFile(CHART_CONFIG)) {
      return out;
    }
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      Element root = factory.newDocumentBuilder().parse(CHART_CONFIG.toFile()).getDocumentElement();
      NodeList inputs = root.getElementsByTagNameNS("*", "input");
      for (int i = 0; i < inputs.getLength(); i++) {
        Element input = (Element) inputs.item(i);
        if (!"account_fixedAssetCategory.csv".equals(input.getAttribute("file"))) {
          continue;
        }
        NodeList binds = input.getElementsByTagNameNS("*", "bind");
        for (int b = 0; b < binds.getLength(); b++) {
          Element bind = (Element) binds.item(b);
          out.addAll(parameters(bind.getAttribute("search")));
          String column = bind.getAttribute("column");
          if (!column.isEmpty()) {
            out.add(column);
          }
        }
      }
    } catch (Exception e) {
      fail("не се чете " + CHART_CONFIG + ": " + e.getMessage());
    }
    return out;
  }

  /** Изважда {@code :име} от JPQL израз. */
  private static List<String> parameters(String search) {
    List<String> out = new ArrayList<>();
    if (search == null) {
      return out;
    }
    java.util.regex.Matcher m = java.util.regex.Pattern.compile(":(\\w+)").matcher(search);
    while (m.find()) {
      out.add(m.group(1));
    }
    return out;
  }
}
