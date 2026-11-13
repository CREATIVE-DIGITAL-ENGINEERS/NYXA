package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.io.InputStream;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИНВАРИАНТ: десетте номенклатури на НАП стоят в {@code resources/nomenclatures/nra/} ЦЕЛИ,
 * НЕПРОМЕНЕНИ и ИНЕРТНИ — и остават такива. Всички са извлечени от книгата на НАП към SAF-T 1.0.2;
 * {@code stock_movement.csv}, {@code asset_movement_types.csv} и {@code nom_inventory_types.csv}
 * служат на годишния файл и на файла при поискване.
 *
 * <p><b>Защо изобщо съществува.</b> Тези файлове са единственият авторитетен препис на
 * номенклатурите от „Структура на файловете на одитен файл SAF-T", НАП, версия 1.0.2. Срещу тях ще
 * се пише сметкопланът, данъчната таблица и мерните единици в следващите кръгове. Днес обаче НИЩО
 * не ги чете: нито App Install, нито синкът на боот, нито ред код. Тоест мълчаливо осакатяване —
 * изгубени редове при ново извличане, разместени колони, изядена колона от неекраниран разделител —
 * няма как да се обади само. Този тест е единственият им читател и затова брои ТОЧНО.
 *
 * <p><b>Защо бройките са заковани числа, а не „повече от нула".</b> Дефектът, който плаши, не е
 * празен файл — той е ЧАСТИЧЕН файл. Едно прекъснато извличане дава 300 от 360 сметки и изглежда
 * съвършено правдоподобно в ревю. Заковано число различава „НАП смени номенклатурата" (тогава
 * числото тук се вдига СЪЗНАТЕЛНО, заедно с README-то) от „извличането се счупи" — а те са различни
 * събития с различни последици.
 *
 * <p><b>Защо четенето е през {@link CSVFile}, а не през {@code BgSeed}.</b> Тези файлове НЕ СА
 * начални данни на модула и се различават от тях по всичко съществено: разделителят е ЗАПЕТАЯ (а не
 * {@code ;}), няма {@code importId}, няма близнак в {@code fr/} и няма entity. {@code
 * BgSeed.splitQuoted} цепи по {@code ;} и тук би върнал по една колона на ред. {@code
 * CSVFile.DEFAULT} е {@code CSVFormat.DEFAULT}, тоест RFC 4180 със запетая — същото правило, по
 * което файловете са писани (виж реда {@code 915020}, чието наименование носи кавички около „А").
 *
 * <p><b>Краят на реда не се проверява.</b> Файловете са с LF и минават през кореновата нормализация
 * {@code * text=auto eol=lf} като всеки наш текстов файл — за разлика от {@code
 * resources/schemas/}, където отпечатъкът на ЧУЖД публикуван файл е заковaн и затова нормализацията
 * е изключена. Тук няма закован отпечатък: {@code CSVParser} чете LF, CR и CRLF еднакво, а {@code
 * CSVFile.parse(InputStream)} маха и евентуален BOM, тоест твърденията са слепи за края на реда.
 */
public class BgNraNomenclatureIntegrityTest {

  /** Коренът на номенклатурите — на classpath-а, защото пътуват в jar-а заедно с модула. */
  private static final String RESOURCE_DIR = "/nomenclatures/nra/";

  /** Същият корен, но като път — за проверката на инертността, която чете конфигурациите. */
  private static final String RESOURCE_PATH = "src/main/resources/nomenclatures/nra";

  /**
   * Двата вносителски корена на модула. Файл в който и да е от тях се внася — {@code
   * apps/init-data} от App Install, {@code data-init} от стоковия {@code DataLoader} на Axelor и от
   * синка на всеки боот. Номенклатурите НЕ БИВА да са там, докато няма entity, което да ги приеме.
   */
  private static final List<String> IMPORT_ROOTS =
      Arrays.asList("src/main/resources/apps/init-data", "src/main/resources/data-init");

  private static final Pattern TAX_CODE = Pattern.compile("^[0-9]{6}$");

  /**
   * Сметките на НАП са от 3 до 5 цифри и не почват с нула — 313 на три цифри, 16 на четири и 31 на
   * пет.
   */
  private static final Pattern NRA_CODE = Pattern.compile("^[1-9][0-9]{2,4}$");

  /** Кодът по КН е точно 8 цифри, с водещите нули — {@code 01012100}, не {@code 1012100}. */
  private static final Pattern NC8_CODE = Pattern.compile("^[0-9]{8}$");

  /**
   * Кодът за УСЛУГИ — единственият ред, който не е от таблицата на лист {@code NC8_TARIC}, а от
   * текста над нея (ред 11). Пасва на {@link #NC8_CODE}, тоест само форматът не би го забелязал.
   */
  private static final String NC8_SERVICES = "00000000";

  /** Видът документ е двуцифрен низ — {@code 01}, {@code 09}, {@code 95}. */
  private static final Pattern INVOICE_TYPE = Pattern.compile("^[0-9]{2}$");

  /** Един файл: заглавен ред, брой редове, ключ и задължителни колони. */
  private static final class Nomenclature {
    final String file;
    final List<String> header;
    final int rows;
    final List<String> key;
    final List<String> required;

    Nomenclature(String file, String[] header, int rows, String[] key, String[] required) {
      this.file = file;
      this.header = Arrays.asList(header);
      this.rows = rows;
      this.key = Arrays.asList(key);
      this.required = Arrays.asList(required);
    }
  }

  private static final List<Nomenclature> ALL =
      Arrays.asList(
          new Nomenclature(
              "nra_nom_accounts.csv",
              new String[] {"nra_code", "nra_name", "section", "group"},
              360,
              new String[] {"nra_code"},
              new String[] {"nra_code"}),
          new Nomenclature(
              "tax_imp.csv",
              new String[] {"taxType", "taxCode", "taxName", "description", "rate", "note"},
              169,
              new String[] {"taxCode"},
              new String[] {"taxCode"}),
          // ⚠️ Ключът е ДВОЙКАТА: 9-те реда носят само 3 различни метода (01, 02, 03), защото един
          // метод има по няколко механизма — 02 „Прихващане" покрива и бартера, и подотчетните
          // лица. Ключ само по methodCode би обявил собствената номенклатура на НАП за счупена.
          new Nomenclature(
              "nom_paymentmethod.csv",
              new String[] {"methodCode", "methodName", "mechanismCode", "mechanismName"},
              9,
              new String[] {"methodCode", "mechanismCode"},
              new String[] {"methodCode", "mechanismCode"}),
          new Nomenclature(
              "unit_of_measure.csv",
              new String[] {"code", "nameEn", "nameBg"},
              2071,
              new String[] {"code"},
              new String[] {"code"}),
          new Nomenclature(
              "vat_taxtype.csv",
              new String[] {"code", "description"},
              3,
              new String[] {"code"},
              new String[] {"code"}),
          // 9896 кода от листа + 00000000 за услугите. Описанието е задължително, но НЕ е дословно:
          // интервалите са нормализирани при извличането (виж README-то) — валидира се кодът.
          new Nomenclature(
              "nc8_taric.csv",
              new String[] {"nc8_code", "description"},
              9897,
              new String[] {"nc8_code"},
              new String[] {"nc8_code", "description"}),
          new Nomenclature(
              "nom_invoice_types.csv",
              new String[] {"code", "name"},
              19,
              new String[] {"code"},
              new String[] {"code", "name"}),
          // PR-F0 (N0.3) — за годишния файл и за файла при поискване. Наименованията са дословни,
          // с крайния интервал на 30 и 150 в stock_movement.csv (виж EXACT_CODES).
          new Nomenclature(
              "stock_movement.csv",
              new String[] {"code", "name"},
              20,
              new String[] {"code"},
              new String[] {"code", "name"}),
          new Nomenclature(
              "asset_movement_types.csv",
              new String[] {"code", "name"},
              13,
              new String[] {"code"},
              new String[] {"code", "name"}),
          new Nomenclature(
              "nom_inventory_types.csv",
              new String[] {"code", "name"},
              5,
              new String[] {"code"},
              new String[] {"code", "name"}));

  /**
   * Трите малки номенклатури от PR-F0 — ТОЧНИЯТ НАБОР кодове в реда на книгата, не само броят.
   *
   * <p><b>⚠️ БРОЯТ НЕ ХВАЩА ПОДМЕНЕН КОД.</b> „65“ прочетено като „66“ оставя 20 реда, уникален
   * ключ и правилен формат — и всяко движение „Предоставени отстъпки в натура“ отива в
   * несъществуващ вид. Наборите са кратки и са преписани от листовете {@code Stock movement},
   * {@code AssetMovementTypes} и {@code Nom_Inventory_Types} на книгата.
   */
  private static final Map<String, List<String>> EXACT_CODES = new LinkedHashMap<>();

  static {
    EXACT_CODES.put(
        "stock_movement.csv",
        Arrays.asList(
            "10", "20", "30", "40", "50", "60", "65", "70", "80", "90", "100", "101", "110", "120",
            "130", "140", "150", "160", "170", "180"));
    EXACT_CODES.put(
        "asset_movement_types.csv",
        Arrays.asList(
            "10", "20", "30", "40", "50", "60", "70", "80", "90", "100", "110", "120", "130"));
    EXACT_CODES.put("nom_inventory_types.csv", Arrays.asList("10", "20", "30", "40", "50"));
  }

  /**
   * Наименованията с краен интервал в книгата — пазят се дословно, защото официалният образец ги
   * пише така в MovementTypeTable/Description. „Почистено“ извличане ги губи без нито един друг
   * сигнал.
   */
  private static final Map<String, String> VERBATIM_TRAILING_SPACE = new LinkedHashMap<>();

  static {
    VERBATIM_TRAILING_SPACE.put("30", "Продажба ");
    VERBATIM_TRAILING_SPACE.put("150", "Безвъзмездно предоставени материални запаси ");
  }

  @Test
  @DisplayName("Заглавният ред е точно очакваният — по име И по ред на колоните")
  void headersAreExact() {
    List<String> broken = new ArrayList<>();
    for (Nomenclature n : ALL) {
      List<String> actual = header(n.file);
      if (!n.header.equals(actual)) {
        broken.add(n.file + ": " + actual + " вместо " + n.header);
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "заглавен ред, различен от закования — колоните се четат по ИМЕ, тоест разместване или"
              + " преименуване тихо размества всяко твърдение надолу:\n  "
              + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Броят редове е точният: 360 / 169 / 9 / 2071 / 3 / 9897 / 19 / 20 / 13 / 5")
  void rowCountsAreExact() {
    List<String> broken = new ArrayList<>();
    for (Nomenclature n : ALL) {
      int actual = rows(n.file).size();
      if (actual != n.rows) {
        broken.add(n.file + ": " + actual + " реда вместо " + n.rows);
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "броят редове се разминава със закования. ЧАСТИЧЕН файл изглежда правдоподобно и няма"
              + " друг читател, който да се обади. Ако НАП наистина е сменила номенклатурата —"
              + " вдигни числото ТУК и бройката в "
              + RESOURCE_PATH
              + "/README.md, с дата на сверяване:\n  "
              + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Ключът е уникален — за начините на плащане: двойката метод + механизъм")
  void keysAreUnique() {
    List<String> broken = new ArrayList<>();
    for (Nomenclature n : ALL) {
      Map<String, Integer> seen = new LinkedHashMap<>();
      List<CSVRecord> records = rows(n.file);
      for (int i = 0; i < records.size(); i++) {
        CSVRecord record = records.get(i);
        String k =
            n.key.stream()
                .map(c -> record.get(n.header.indexOf(c)))
                .collect(Collectors.joining("|"));
        Integer first = seen.put(k, i + 2);
        if (first != null) {
          broken.add(n.file + ": „" + k + "“ на редове " + first + " и " + (i + 2));
        }
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "повторен ключ в номенклатура — дубликатът значи или изядени, или удвоени редове при"
              + " извличането:\n  "
              + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Задължителните колони не са празни")
  void requiredColumnsArePresent() {
    List<String> broken = new ArrayList<>();
    for (Nomenclature n : ALL) {
      List<CSVRecord> records = rows(n.file);
      for (int i = 0; i < records.size(); i++) {
        for (String column : n.required) {
          String v = records.get(i).get(n.header.indexOf(column));
          if (v == null || v.trim().isEmpty()) {
            broken.add(n.file + ", ред " + (i + 2) + ": „" + column + "“ е празна");
          }
        }
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "празна задължителна колона — най-вероятно разместен ред от неекраниран разделител:\n  "
              + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Кодът на сметка е от 3 до 5 цифри и не почва с нула")
  void nraAccountCodesAreWellFormed() {
    Nomenclature n = spec("nra_nom_accounts.csv");
    List<String> broken = new ArrayList<>();
    List<CSVRecord> records = rows(n.file);
    for (int i = 0; i < records.size(); i++) {
      String code = records.get(i).get(n.header.indexOf("nra_code"));
      if (!NRA_CODE.matcher(code).matches()) {
        broken.add("ред " + (i + 2) + ": „" + code + "“");
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "nra_code не пасва на "
              + NRA_CODE.pattern()
              + " — в тази колона стои САМО кодът. ⚠️ Кодът стои и като префикс в nra_name („101"
              + " Основен капитал…“) и това е дословната формулировка на НАП — не е повод колоната"
              + " да бъде „изчистена“:\n  "
              + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Кодът на данък е 6-цифрен и започва с кода на своя вид")
  void taxCodesAreWellFormed() {
    Nomenclature n = spec("tax_imp.csv");
    int typeAt = n.header.indexOf("taxType");
    int codeAt = n.header.indexOf("taxCode");
    List<String> broken = new ArrayList<>();
    List<CSVRecord> records = rows(n.file);
    for (int i = 0; i < records.size(); i++) {
      String type = records.get(i).get(typeAt);
      String code = records.get(i).get(codeAt);
      if (!TAX_CODE.matcher(code).matches()) {
        broken.add("ред " + (i + 2) + ": taxCode „" + code + "“ не е 6 цифри");
      } else if (!code.startsWith(type)) {
        broken.add("ред " + (i + 2) + ": taxCode „" + code + "“ не почва с taxType „" + type + "“");
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "taxCode се разминава с taxType. Тази връзка е ЙЕРАРХИЯТА на данъчната таблица: видът е"
              + " първите три цифри на кода. Правилото минава 169 от 169 реда и няма изключение —"
              + " червено тук значи разместени колони или грешно преписан ред, не нова наредба:\n  "
              + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Кодът на мерна единица е непразен низ, а не число")
  void unitCodesAreNonBlankStrings() {
    Nomenclature n = spec("unit_of_measure.csv");
    int codeAt = n.header.indexOf("code");
    List<String> broken = new ArrayList<>();
    List<CSVRecord> records = rows(n.file);
    for (int i = 0; i < records.size(); i++) {
      String code = records.get(i).get(codeAt);
      if (code == null || code.trim().isEmpty()) {
        broken.add("ред " + (i + 2) + ": празен код");
      }
    }
    if (!broken.isEmpty()) {
      fail("празен код на мерна единица:\n  " + String.join("\n  ", broken));
    }
    // ⚠️ Кодът НЕ Е ЧИСЛО и не бива да се чете като такова: 2071-те кода са [0-9A-Z]{2,4} и
    // включват XP3, XSX, H87. Формата не се твърди тук нарочно — UN/ECE Rec. 20 е чужда
    // номенклатура и нейната азбука не е наше правило. Твърди се само, че кодът го има.
  }

  @Test
  @DisplayName("Кодът по КН е 8 цифри, а кодът за услуги 00000000 е налице")
  void nc8CodesAreWellFormed() {
    Nomenclature n = spec("nc8_taric.csv");
    int codeAt = n.header.indexOf("nc8_code");
    List<String> broken = new ArrayList<>();
    boolean services = false;
    List<CSVRecord> records = rows(n.file);
    for (int i = 0; i < records.size(); i++) {
      String code = records.get(i).get(codeAt);
      if (!NC8_CODE.matcher(code).matches()) {
        broken.add("ред " + (i + 2) + ": „" + code + "“");
      }
      services |= NC8_SERVICES.equals(code);
    }
    if (!broken.isEmpty()) {
      fail(
          "nc8_code не пасва на "
              + NC8_CODE.pattern()
              + ". Най-честата причина е изядена водеща нула — кодът е минал през число"
              + " (01012100 → 1012100). ⚠️ И литералът „0“ (кодът по КН е неприложим) НЕ е ред от"
              + " номенклатурата: той е стойност, която попълва генераторът:\n  "
              + String.join("\n  ", broken));
    }
    if (!services) {
      fail(
          NC8_SERVICES
              + " липсва. Това е единственият код, който не е от таблицата на лист NC8_TARIC, а от"
              + " текста над нея — „обща категория за попълване на продуктовия код на УСЛУГИ“."
              + " Ново извличане само от таблицата го губи и всяка услуга остава без код.");
    }
  }

  @Test
  @DisplayName("Видът документ е двуцифрен низ")
  void invoiceTypeCodesAreWellFormed() {
    Nomenclature n = spec("nom_invoice_types.csv");
    int codeAt = n.header.indexOf("code");
    List<String> broken = new ArrayList<>();
    List<CSVRecord> records = rows(n.file);
    for (int i = 0; i < records.size(); i++) {
      String code = records.get(i).get(codeAt);
      if (!INVOICE_TYPE.matcher(code).matches()) {
        broken.add("ред " + (i + 2) + ": „" + code + "“");
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "code не пасва на "
              + INVOICE_TYPE.pattern()
              + " — кодът е двуцифрен НИЗ и водещата нула е част от него („01“ Фактура, „09“"
              + " Протокол). „1“ вместо „01“ значи, че файлът е минал през число:\n  "
              + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Движенията, активите и видовете запас: точният набор кодове в реда на книгата")
  void smallNomenclaturesHaveExactCodes() {
    List<String> broken = new ArrayList<>();
    for (Map.Entry<String, List<String>> e : EXACT_CODES.entrySet()) {
      int codeAt = spec(e.getKey()).header.indexOf("code");
      List<String> actual =
          rows(e.getKey()).stream().map(r -> r.get(codeAt)).collect(Collectors.toList());
      if (!e.getValue().equals(actual)) {
        broken.add(e.getKey() + ": " + actual + " вместо " + e.getValue());
      }
    }
    Map<String, String> names = new LinkedHashMap<>();
    rows("stock_movement.csv").forEach(r -> names.put(r.get(0), r.get(1)));
    for (Map.Entry<String, String> e : VERBATIM_TRAILING_SPACE.entrySet()) {
      if (!e.getValue().equals(names.get(e.getKey()))) {
        broken.add(
            "stock_movement.csv "
                + e.getKey()
                + ": „"
                + names.get(e.getKey())
                + "“ вместо „"
                + e.getValue()
                + "“");
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "кодовете или наименованията се разминават с листа на книгата — подменен, изгубен или"
              + " „почистен“ ред при извличането:\n  "
              + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Номенклатурите са ИНЕРТНИ — никоя вносителска конфигурация не ги сочи")
  void nomenclaturesAreNotImported() {
    Path root = BgDomainColumns.moduleRoot();
    List<String> broken = new ArrayList<>();

    for (String importRoot : IMPORT_ROOTS) {
      if (Files.exists(root.resolve(importRoot).resolve("nomenclatures"))) {
        broken.add("папка „nomenclatures“ е под вносителския корен " + importRoot);
      }
    }

    Set<String> names =
        ALL.stream().map(n -> n.file).collect(Collectors.toCollection(LinkedHashSet::new));
    Path resources = root.resolve("src/main/resources");
    List<Path> configs;
    try (Stream<Path> walk = Files.walk(resources)) {
      configs =
          walk.filter(p -> p.getFileName().toString().endsWith(".xml"))
              .filter(p -> p.getFileName().toString().contains("config"))
              .collect(Collectors.toList());
    } catch (IOException e) {
      fail("не се четат конфигурациите под " + resources + ": " + e.getMessage());
      return;
    }
    if (configs.isEmpty()) {
      fail(
          "нито една вносителска конфигурация не се намери под "
              + resources
              + " — проверката би"
              + " минавала зелено без да е проверила нищо");
    }
    for (Path config : configs) {
      String text;
      try {
        text = new String(Files.readAllBytes(config), StandardCharsets.UTF_8);
      } catch (IOException e) {
        fail("не се чете " + config + ": " + e.getMessage());
        return;
      }
      for (String name : names) {
        if (text.contains(name)) {
          broken.add(root.relativize(config) + " сочи " + name);
        }
      }
    }

    if (!broken.isEmpty()) {
      fail(
          "номенклатурите вече не са инертни:\n  "
              + String.join("\n  ", broken)
              + "\n\nТе нямат entity, нямат importId и нямат близнак в fr/. Вносител върху тях"
              + " или пада, или — по-лошо — създава редове с празен ключ. Ако наистина им дойде"
              + " редът да се внасят, това става със СВОЙ entity и СВОЙ кръг (PR-N3), а този тест"
              + " се пренаписва съзнателно, а не се трие.");
    }
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────

  private static Nomenclature spec(String file) {
    return ALL.stream()
        .filter(n -> n.file.equals(file))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("няма спецификация за " + file));
  }

  /** Всички записи БЕЗ заглавния ред. */
  private static List<CSVRecord> rows(String file) {
    List<CSVRecord> all = parse(file);
    return all.size() <= 1 ? Collections.emptyList() : all.subList(1, all.size());
  }

  /** Заглавният ред, цепен по същото правило като редовете — иначе индексите се разминават. */
  private static List<String> header(String file) {
    List<CSVRecord> all = parse(file);
    if (all.isEmpty()) {
      fail("празен файл: " + RESOURCE_DIR + file);
    }
    List<String> out = new ArrayList<>();
    all.get(0).forEach(out::add);
    return out;
  }

  /** Разборът е кеширан: тестовете по десет файла иначе прочитат 9897-те реда многократно. */
  private static final Map<String, List<CSVRecord>> PARSED = new LinkedHashMap<>();

  private static List<CSVRecord> parse(String file) {
    List<CSVRecord> cached = PARSED.get(file);
    if (cached != null) {
      return cached;
    }
    String resource = RESOURCE_DIR + file;
    try (InputStream in = BgNraNomenclatureIntegrityTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        fail(
            "липсва номенклатура на classpath-а: "
                + resource
                + " — очаква се в "
                + RESOURCE_PATH
                + "/");
      }
      try (CSVParser parser = CSVFile.DEFAULT.parse(in, StandardCharsets.UTF_8)) {
        List<CSVRecord> out = new ArrayList<>();
        for (CSVRecord record : parser) {
          out.add(record);
        }
        PARSED.put(file, out);
        return out;
      }
    } catch (IOException e) {
      fail("не се чете " + resource + ": " + e.getMessage());
      return null;
    }
  }
}
