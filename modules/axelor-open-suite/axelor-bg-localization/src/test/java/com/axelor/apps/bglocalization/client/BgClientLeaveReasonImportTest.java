package com.axelor.apps.bglocalization.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import java.io.File;
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
import java.util.TreeSet;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * ПАЗАЧЪТ НА КЛИЕНТСКИЯ ВНОС НА ВИДОВЕТЕ ОТПУСК — файловата половина.
 *
 * <p><b>⚠️ ФАЙЛЪТ Е В МОДУЛА, И ТОВА Е ВЪПРОС НА ПОДРЕДБА, НЕ НА УДОБСТВО.</b> Имената на редовете
 * са на конкретния клиент, но мястото на файла решава и КОГА тече вносът: кореновият {@code
 * data-init} ({@code src/main/resources/data-init/}) се изпълнява на БООТ, а речникът {@code
 * bg_absence_type}, към който вносът сочи, се създава от App Install — минути по-късно. Тогава
 * търсенето {@code self.code = :absenceType} не намира нищо, а ненамерен m2o не гърми: всичките 28
 * реда се записват с празна връзка и празен лог. Подредбата е СТРУКТУРНА (коренов data-init срещу
 * apps-init), тоест не се оправя с преподреждане вътре в някой от двата файла. Затова вносът е в
 * {@code apps/init-data/} на модула, в СВОЙ конфиг, който {@code AppServiceImpl#importPerConfig}
 * пуска СЛЕД каталога.
 *
 * <p><b>И затова липсващият файл ПАДА, вместо да пропуска.</b> Файлът пътува с модула: липсата му
 * не е състояние на средата, а дефект в самия модул.
 *
 * <p><b>⚠️ КАКВО ТОЗИ ТЕСТ ЛОВИ, И ЗАЩО ИМЕННО ТОВА.</b> Вносителят на Axelor ПРЕМЪЛЧАВА
 * несъществуващо поле: {@code XMLBinder#populate} прави {@code if (property == null) continue}.
 * Тоест внос преди полето {@code LeaveReason.bgAbsenceType} да съществува не гърми — той създава 28
 * реда БЕЗ съпоставяне и без вдигната грешка. Точно това е състоянието, при което {@code
 * BgPayrollInputBuilder} после ОТКАЗВА и трите кофи на отпуските, без никой да помни защо. Тук се
 * пази и вторият вид тишина — същият празен резултат, стигнат по друг път: конфиг, който тече ПРЕДИ
 * речника.
 */
class BgClientLeaveReasonImportTest {

  /** Кодът на приложението — той решава кои конфиг файлове App Install изобщо ВИЖДА. */
  private static final String APP_CODE = "bg-localization";

  private static final Path INIT_DATA = Paths.get("src/main/resources/apps/init-data");

  /** Данните — вече seed на модула, тоест в двата езика, байт по байт еднакви. */
  private static final Path IMPORT_FILE = INIT_DATA.resolve("en/leave_reason.xml");

  private static final Path IMPORT_FILE_FR = INIT_DATA.resolve("fr/leave_reason.xml");

  /** Конфигът на вноса — СВОЙ файл, защото съседът е {@code csv-inputs}, а този внос е XML. */
  private static final Path IMPORT_CONFIG =
      INIT_DATA.resolve("bg-localization-leave-reason-config.xml");

  /** Каталогът, който трябва да е зареден ПРЕДИ вноса — той носи {@code bg_absence_type.csv}. */
  private static final Path CATALOGUE_CONFIG = INIT_DATA.resolve("bg-localization-config.xml");

  private static final Path DICTIONARY = INIT_DATA.resolve("en/bg_absence_type.csv");

  /**
   * ⚠️ Редът на КОРЕНОВИЯ елемент, точно както го чете {@code AppServiceImpl#runImport}: {@code
   * Pattern.compile("^<\\s*csv-inputs")} върху всеки РЕД поотделно. Тоест хваща се само ред, който
   * ЗАПОЧВА с {@code <csv-inputs} — споменаване в коментар по средата на реда не брои, и тестът не
   * бива да брои и него, инак пада за собствената си документация.
   */
  private static final Pattern CSV_ROOT_LINE = Pattern.compile("^<\\s*csv-inputs");

  /**
   * Регулярният израз, с който {@code AppServiceImpl#extract} СЪБИРА конфизите на приложението:
   * {@code MetaScanner.findAll(module, "apps/init-data", appCode + "(-+.*)?-config.xml")}. Име,
   * което не му отговаря, изобщо не се копира — тоест вносът просто не се пуска, без съобщение.
   */
  private static final Pattern SCANNED_CONFIG =
      Pattern.compile(Pattern.quote(APP_CODE) + "(-+.*)?-config\\.xml");

  // ═══ ЧЕТЕНЕТО ═════════════════════════════════════════════════════════════

  private static String read(Path path, String what) {
    assertTrue(
        Files.isRegularFile(path),
        "липсва "
            + path.toAbsolutePath().normalize()
            + " — "
            + what
            + " НЕ може да се провери. Файлът вече е seed на МОДУЛА (премести се от кореновия"
            + " data-init, за да тече СЛЕД речника), тоест липсата му е дефект в модула, а не"
            + " състояние на средата.");
    try {
      return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    } catch (IOException e) {
      fail("не се чете " + path + ": " + e.getMessage());
      return null;
    }
  }

  /** Кодовете от МОДУЛНИЯ речник — източникът на истината за референтната цялост. */
  private static Set<String> dictionaryCodes() {
    assertTrue(
        Files.isRegularFile(DICTIONARY),
        "не намирам речника " + DICTIONARY.toAbsolutePath() + " — пътят е спрямо модула");
    Set<String> codes = new TreeSet<>();
    try {
      List<String> lines = Files.readAllLines(DICTIONARY, StandardCharsets.UTF_8);
      List<String> header = List.of(lines.get(0).split(",", -1));
      int codeAt = header.indexOf("code");
      assertTrue(codeAt >= 0, "речникът няма колона „code“ — заглавието се е сменило: " + header);
      for (int i = 1; i < lines.size(); i++) {
        if (!lines.get(i).trim().isEmpty()) {
          codes.add(lines.get(i).split(",", -1)[codeAt].trim());
        }
      }
    } catch (IOException e) {
      fail("не се чете речникът: " + e.getMessage());
    }
    return codes;
  }

  /**
   * Елементите {@code <leave-reason>} през ИСТИНСКИ XML парсер, не през regex.
   *
   * <p>⚠️ Regex по ФИКСИРАН ред на атрибутите не стига: {@code <leave-reasons>} (кореновият
   * елемент) съвпада с префикса {@code <leave-reason}, а ред с разместени атрибути изчезва
   * безшумно. Парсерът няма нито единия проблем и не зависи от подредбата.
   */
  private static List<Element> rowElements(Path path) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(false);
      Document document = factory.newDocumentBuilder().parse(path.toFile());
      NodeList nodes = document.getElementsByTagName("leave-reason");
      List<Element> out = new ArrayList<>();
      for (int i = 0; i < nodes.getLength(); i++) {
        out.add((Element) nodes.item(i));
      }
      return out;
    } catch (Exception e) {
      fail("файлът " + path + " не е валиден XML: " + e.getMessage());
      return null;
    }
  }

  /** Стойността на атрибута, или {@code null} когато го няма — празният низ също е липса. */
  private static String attr(Element element, String name) {
    String value = element.getAttribute(name);
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  // ═══ РЕФЕРЕНТНА ЦЯЛОСТ ════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Всеки внасян ред сочи СЪЩЕСТВУВАЩ код от речника BgAbsenceType")
  void everyImportedRowPointsAtARealDictionaryCode() {
    read(IMPORT_FILE, "референтната цялост на съпоставянето");
    List<Element> rows = rowElements(IMPORT_FILE);

    assertTrue(
        rows.size() >= 20,
        "котвата: очаквах десетки реда, намерих " + rows.size() + " — парсерът чете нещо друго");

    Set<String> known = dictionaryCodes();
    List<String> dangling = new ArrayList<>();
    for (Element row : rows) {
      String code = attr(row, "absenceType");
      if (code != null && !known.contains(code)) {
        dangling.add(attr(row, "importId") + " → „" + code + "“");
      }
    }
    if (!dangling.isEmpty()) {
      fail(
          "внасян ред сочи код, който речникът НЕ носи ("
              + dangling.size()
              + "):\n  "
              + String.join("\n  ", dangling)
              + "\n\n  ⚠️ Търсенето е search=\"self.code = :absenceType\" — ненамерен код НЕ вдига"
              + " грешка, а оставя връзката ПРАЗНА. Тоест редът се създава несъпоставен и"
              + " BgPayrollInputBuilder после ОТКАЗВА и трите кофи на отпуските."
              + "\n  Известни кодове: "
              + known);
    }
  }

  @Test
  @DisplayName("⚠️ Нито един внасян ред не остава БЕЗ съпоставяне")
  void noImportedRowIsLeftUnmapped() {
    read(IMPORT_FILE, "пълнотата на съпоставянето");

    // ⚠️ Проверява се ВСЕКИ елемент за ВСЕКИ от трите атрибута поотделно. Липсващ absenceType е
    // точно толкова вреден, колкото и сгрешен: несъпоставеният вид кара BgPayrollInputBuilder да
    // откаже и трите кофи на отпуските за целия фиш.
    List<String> incomplete = new ArrayList<>();
    for (Element row : rowElements(IMPORT_FILE)) {
      for (String required : new String[] {"importId", "name", "absenceType"}) {
        if (attr(row, required) == null) {
          incomplete.add(
              "ред #"
                  + (rowElements(IMPORT_FILE).indexOf(row) + 1)
                  + " (importId="
                  + attr(row, "importId")
                  + ") няма „"
                  + required
                  + "“");
        }
      }
    }
    assertEquals(
        new ArrayList<String>(),
        incomplete,
        "ред <leave-reason> без пълната тройка importId/name/absenceType");
  }

  @Test
  @DisplayName("Кодовете, имената и importId-тата са уникални — нито едно двойно съпоставяне")
  void theImportIsOneToOne() {
    read(IMPORT_FILE, "уникалността на съпоставянето");
    List<Element> rows = rowElements(IMPORT_FILE);

    for (String what : new String[] {"importId", "name"}) {
      Set<String> seen = new LinkedHashSet<>();
      List<String> dupes = new ArrayList<>();
      for (Element row : rows) {
        String value = attr(row, what);
        if (value != null && !seen.add(value)) {
          dupes.add(value);
        }
      }
      assertEquals(
          new ArrayList<String>(),
          dupes,
          "повтарящ се „" + what + "“ — два реда за едно и също нещо, и вносът не е идемпотентен");
    }

    // ⚠️ ВИДЪТ ОТСЪСТВИЕ СЕ ПОВТАРЯ ЗАКОННО, НО САМО С ДРУГА ЕДИНИЦА. Уникален absenceType е
    // вярно за клиентския списък, но не е изискване: връзката е m2o ОТ СТРАНАТА на LeaveReason, а
    // идемпотентността е по importId, не по вида. Контрапримерът е КТ чл. 157 ал. 3 т. 2: същият
    // РЕЖИМ на заплащане („в размера по чл. 177“) се ползва в ДВЕ единици — т. 5а е в ДНИ
    // (заседания), а т. 6 („по 1 ЧАС дневно за дните на предизвестието“) и ал. 2 са в ЧАСОВЕ.
    // Единицата живее на вида ОТПУСК, не на речника, тоест втори ред в речника с идентични флагове
    // би бил дубликат без разлика. Пазим онова, което НАИСТИНА трябва да е уникално: двойката (вид
    // отсъствие, единица) — два реда за един вид и една
    // единица пак са два реда за едно и също нещо.
    Set<String> pairs = new LinkedHashSet<>();
    List<String> dupePairs = new ArrayList<>();
    for (Element row : rows) {
      String type = attr(row, "absenceType");
      String unit = attr(row, "unitSelect");
      String pair = type + " / единица " + (unit == null ? "1 (дни, по подразбиране)" : unit);
      if (type != null && !pairs.add(pair)) {
        dupePairs.add(pair);
      }
    }
    assertEquals(
        new ArrayList<String>(),
        dupePairs,
        "повтаряща се двойка (вид отсъствие, единица) — два вида отпуск, между които нищо не"
            + " различава кой да избере операторът");
  }

  // ═══ КОНФИГЪТ НА ВНОСА ════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Конфигът търси по importId, НЕ по име — инак преименуването прави ВТОРИ ред")
  void theConfigSearchesByImportId() {
    String config = read(IMPORT_CONFIG, "ключът за идемпотентност");

    assertTrue(
        config.contains("file=\"leave_reason.xml\""),
        "котвата: конфигът изобщо не включва leave_reason.xml — вносът НЯМА да се пусне");
    assertTrue(
        config.contains("search=\"self.importId = :importId\""),
        "вносът трябва да се разпознава по importId. Търсене по name щеше да създаде ВТОРИ ред при"
            + " всяко преименуване — а два от редовете вече са преименувани спрямо подадения"
            + " списък (гледането на болен, по чл. 45 КСО)");
  }

  @Test
  @DisplayName("⚠️ Единицата се ПОДРАЗБИРА ДНИ, а съпоставянето е ПОТВЪРДЕНО (не подразбирано)")
  void theUnitDefaultsToDaysAndTheMappingIsConfirmed() {
    String config = read(IMPORT_CONFIG, "единицата и флагът за потвърждение");
    String flat = config.replaceAll("\\s+", " ");

    // ⚠️ `to="unitSelect" eval="1"` БЕЗ ВЪЗЕЛ е по-силно твърдение, отколкото нормата дава.
    // Вързан така, вносителят пише ДНИ на всеки ред и атрибут във файла няма как да го промени —
    // НЕВЯРНО, щом в списъка има право, което кодексът мери в ЧАСОВЕ (чл. 166 КТ, кърмене).
    assertTrue(
        flat.contains("node=\"@unitSelect\"") && flat.contains("to=\"unitSelect\""),
        "единицата трябва да се ЧЕТЕ от реда (node=\"@unitSelect\"), инак вид, чиято законна"
            + " единица е ЧАС, не може да бъде внесен изобщо. Намерено: "
            + flat);

    // ⚠️ И ВЪПРЕКИ ТОВА ПОДРАЗБИРАНЕТО ОСТАВА 1. Липсващ атрибут дава null, а null минава през
    // BgPayrollInputBuilder като „не е ден“ и праща заявката по ЧАСОВИЯ път — тоест отказ
    // leaveHoursWithoutContractedDay върху вид, който открай време е в дни. Двайсет и осемте
    // заварени реда нямат атрибут и точно този израз ги пази непокътнати.
    assertTrue(
        flat.contains("eval=\"leaveUnit ? leaveUnit as Integer : 1\""),
        "липсващата единица трябва да пада на ДНИ (1), а не на null: празна единица не е „ден“ и"
            + " праща заявката по часовия път без делител. Намерено: "
            + flat);

    // ⚠️ И ALIAS-ЪТ Е ЗАДЪЛЖИТЕЛЕН, по същия довод като при търсенето в речника: XMLBinder#toMap
    // ключи контекста по alias-а, а `eval` се смята точно върху този контекст. Без него изразът
    // би търсил променлива, която я няма, и би гръмнал при ВСЕКИ ред.
    assertTrue(
        flat.contains("alias=\"leaveUnit\""),
        "bind-ът за единицата иска alias „leaveUnit“ — eval се смята върху контекста, ключен по"
            + " него. Намерено: "
            + flat);

    assertTrue(
        config.contains("to=\"bgAbsenceTypeDefaulted\" eval=\"false\""),
        "съпоставянето е ПОТВЪРДЕНО от човек — флагът трябва да е false. Вдигнат, той праща"
            + " всичките редове в списъка „чака потвърждение“ и иска повторно решение за нещо,"
            + " което вече е решено");
  }

  @Test
  @DisplayName("⚠️ ЧАСОВЕТЕ СА ИЗКЛЮЧЕНИЕ, И ТО ИЗБРОЕНО: чл. 166 и чл. 157 ал. 1 т. 6 / ал. 2")
  void onlyTheNursingRowDeclaresHours() {
    read(IMPORT_FILE, "единицата по редове");

    List<String> withUnit = new ArrayList<>();
    for (Element row : rowElements(IMPORT_FILE)) {
      String unit = attr(row, "unitSelect");
      if (unit != null) {
        withUnit.add(attr(row, "importId") + " → " + unit);
      }
    }

    // ⚠️ СПИСЪКЪТ Е В РЕДА НА ФАЙЛА и е ИЗБРОЯВАНЕ, а не единица. Второто изключение идва от
    // разцепването на чл. 157 и е също толкова дословно: ал. 1 т. 6 дава отпуска
    // „по 1 ЧАС дневно за дните на предизвестието“, а ал. 2 — прегледи при бременност и ин-витро
    // „когато е необходимо те да се извършват през работно време“, тоест пак част от деня. И двете
    // се заплащат „в размера по чл. 177“ (ал. 3 т. 2), тоест сочат СЪЩИЯ ред от речника като
    // дневния им събрат по т. 5а — единицата е на вида отпуск, не на речника.
    assertEquals(
        List.of("lr_paid_157_art177_h → 2", "lr_nursing_166 → 2"),
        withUnit,
        "⚠️ АТРИБУТЪТ СЕ ИЗПИСВА САМО КОГАТО НОРМАТА ГО ИСКА. Чл. 166 ал. 1 КТ дава отпуска за"
            + " кърмене дословно „по 1 час два пъти дневно“ — тоест ЧАСОВЕ (unitSelect = 2). Всеки"
            + " друг ред мълчи и пада на ДНИ през eval-а на конфига. Нов ред с атрибут е или ново"
            + " право в часове, или недоглеждане, и разликата се решава от човек, не от този тест."
            + " Намерено: "
            + withUnit);
  }

  @Test
  @DisplayName("⚠️ Речникът се ЧЕТЕ, не се пише — клиентски внос не преправя модулен seed")
  void theDictionaryIsReadNotWritten() {
    String config = read(IMPORT_CONFIG, "посоката към речника");

    // ⚠️ Свива се БЯЛОТО ПОЛЕ, защото твърдението е за АТРИБУТИТЕ, не за пренасянето на редове.
    // Заковано за точния низ, това падаше при първото форматиране, което пренесе атрибут на нов
    // ред.
    String flat = config.replaceAll("\\s+", " ");

    assertTrue(
        flat.contains("to=\"bgAbsenceType\"") && flat.contains("update=\"false\""),
        "връзката към BgAbsenceType трябва да е update=\"false\": речникът е МОДУЛЕН seed и този"
            + " внос само СОЧИ към него. Без това клиентски внос би преправял общ речник");
  }

  @Test
  @DisplayName("⚠️ Търсенето в речника има alias — БЕЗ него съпоставянето мълчаливо остава NULL")
  void theDictionaryLookupIsBoundToAnAlias() {
    String config = read(IMPORT_CONFIG, "разрешимостта на :absenceType");

    String flat = config.replaceAll("\\s+", " ");

    // ⚠️ ТОВА Е ВТОРИЯТ ТИХ ОТКАЗ, И ТОЙ НЕ Е СЪЩИЯТ КАТО ЛИПСВАЩОТО ПОЛЕ.
    // XMLBinder#toMap ключи контекста по alias-а, а когато го няма — по ПОЛЕТО (`to`), НЕ по
    // името на възела (`node`). Без alias стойността стои под ключ „bgAbsenceType“, `:absenceType`
    // не се разрешава, заявката става `self.code = null`, а fetchOne() връща null. И понеже
    // под-връзката няма свои под-връзки, bind() също връща null. Резултатът е 28 ЗАРЕДЕНИ реда
    // БЕЗ съпоставяне и без нито едно съобщение, дори срещу пълна база С полето на WS-5 налице.
    assertTrue(
        flat.contains("alias=\"absenceType\""),
        "връзката към речника търси по `:absenceType`, значи контекстът трябва да носи ТОЧНО този"
            + " ключ — а той идва от alias-а. Без alias=\"absenceType\" ключът е „bgAbsenceType“,"
            + " параметърът се разрежда на NULL и всичките редове се записват НЕСЪПОСТАВЕНИ,"
            + " мълчаливо. Образецът в axelor-core (node=\"@group\" to=\"group\""
            + " search=\"self.code = :group\") не се спъва в това само защото там името на възела"
            + " съвпада с името на полето; тук не съвпадат");

    assertTrue(
        flat.contains("search=\"self.code = :absenceType\""),
        "търсенето в речника трябва да е по КОД (`self.code = :absenceType`) — това е стабилният"
            + " ключ на модулния seed, за разлика от заглавието, което е превод");
  }

  @Test
  @DisplayName("⚠️ Вносът е СВОЙ конфиг — втори корен в каталога би изключил единия вносител")
  void theImportKeepsItsOwnConfigFile() {
    String config = read(IMPORT_CONFIG, "изборът на вносител");
    String catalogue = read(CATALOGUE_CONFIG, "изборът на вносител за каталога");

    // ⚠️ AppServiceImpl#runImport чете конфига РЕД ПО РЕД и спира на ПЪРВИЯ съвпаднал корен, при
    // това CSV е пръв: if (patCsv…) new CSVImporter(...) else if (patXml…) new XMLImporter(...).
    // Тоест два корена в един файл не дават два входа — вторият вносител просто не се строи, а
    // входовете му изчезват МЪЛЧАЛИВО. Два ФАЙЛА се четат и двата, затова вносът е отделен.
    assertTrue(config.contains("<xml-inputs"), "котвата: конфигът на вноса вече не е XML вход");
    assertTrue(catalogue.contains("<csv-inputs"), "котвата: каталогът вече не е CSV вход");

    List<String> csvRoots = new ArrayList<>();
    for (String line : config.split("\n", -1)) {
      if (CSV_ROOT_LINE.matcher(line).find()) {
        csvRoots.add(line.trim());
      }
    }
    assertEquals(
        new ArrayList<String>(),
        csvRoots,
        "конфигът на вноса обявява И csv-inputs. runImport пробва CSV ПЪРВИ и при съвпадение НЕ"
            + " строи XML вносителя — тоест видовете отпуск спират да се внасят безшумно");

    assertTrue(
        !catalogue.contains("leave_reason.xml"),
        "входът за leave_reason.xml се е върнал в bg-localization-config.xml. Той е csv-inputs:"
            + " XML вход там не се чете от никого и вносът пак спира мълчаливо");
  }

  @Test
  @DisplayName("⚠️ App Install ВИЖДА конфига — име извън обхвата на скенера не се копира изобщо")
  void theConfigNameIsPickedUpByTheScanner() {
    for (Path config : new Path[] {CATALOGUE_CONFIG, IMPORT_CONFIG}) {
      String name = config.getFileName().toString();
      assertTrue(
          SCANNED_CONFIG.matcher(name).matches(),
          "името „"
              + name
              + "“ не отговаря на „"
              + SCANNED_CONFIG.pattern()
              + "“ — AppServiceImpl#extract събира конфизите на приложението точно с този израз и"
              + " файл извън него не се копира в работната директория. Резултатът не е грешка, а"
              + " ТИШИНА: вносът просто не се пуска.");
      // ⚠️ Вторият филтър, на друго място и с друго правило: importPerConfig изброява вече
      // копираните файлове с name.startsWith(appCode + "-") && name.endsWith("-config.xml").
      assertTrue(
          name.startsWith(APP_CODE + "-") && name.endsWith("-config.xml"),
          "името „" + name + "“ минава скенера, но пада на филтъра на importPerConfig");
    }
  }

  @Test
  @DisplayName("⚠️ ПОДРЕДБАТА: каталогът се зарежда ПРЕДИ вноса — инак връзката остава NULL")
  void theCatalogueIsLoadedBeforeTheLeaveReasons() {
    // ⚠️ ТОВА Е САМОТО ТВЪРДЕНИЕ НА ТОЗИ КЛОН, и то не се чете от нито един от двата файла: то
    // живее в АЗБУЧНАТА ПОДРЕДБА НА ИМЕНАТА ИМ. AppServiceImpl#importPerConfig прави
    // listFiles(filter) → Arrays.sort(files) → runImport за всеки. Тоест редът е File.compareTo,
    // тоест лексикографски по път. Тук се пресмята по същия начин, а не се преразказва: тест,
    // който повтаря „c е преди l“, би минал и при сгрешено правило.
    File[] configs =
        INIT_DATA
            .toFile()
            .listFiles(
                (dir, name) -> name.startsWith(APP_CODE + "-") && name.endsWith("-config.xml"));
    assertTrue(
        configs != null && configs.length >= 2,
        "намерих "
            + (configs == null ? "нула" : String.valueOf(configs.length))
            + " конфига в "
            + INIT_DATA.toAbsolutePath()
            + " — очаквах поне два (каталогът и вносът на видовете отпуск)");

    Arrays.sort(configs);
    List<String> order = new ArrayList<>();
    for (File config : configs) {
      order.add(config.getName());
    }

    int catalogue = order.indexOf(CATALOGUE_CONFIG.getFileName().toString());
    int leaveReasons = order.indexOf(IMPORT_CONFIG.getFileName().toString());
    assertTrue(catalogue >= 0, "каталогът не е в подредбата: " + order);
    assertTrue(leaveReasons >= 0, "вносът не е в подредбата: " + order);

    assertTrue(
        catalogue < leaveReasons,
        "вносът на видовете отпуск тръгва ПРЕДИ каталога. Подредбата е "
            + order
            + " — а тя е ЕДИНСТВЕНОТО, което пази съпоставянето: bg_absence_type се създава от"
            + " каталога, а търсенето self.code = :absenceType не гърми, когато не намери. Тоест"
            + " обърнат ред НЕ ПАДА никъде — той просто записва 28 реда с празна връзка, точно"
            + " както правеше вносът, докато стоеше в кореновия data-init и течеше на боот.");

    // Котва: каталогът наистина е този, който носи речника.
    assertTrue(
        read(CATALOGUE_CONFIG, "мястото на речника").contains("bg_absence_type.csv"),
        "bg_absence_type.csv вече не се зарежда от "
            + CATALOGUE_CONFIG.getFileName()
            + " — подредбата по-горе тогава пази нещо друго и трябва да се пренасочи");
  }

  @Test
  @DisplayName("⚠️ en/ и fr/ са байт по байт еднакви — правилото на seed-а важи и за този файл")
  void theClientFileIsIdenticalInBothLanguages() throws IOException {
    read(IMPORT_FILE, "съвпадението между езиците");
    read(IMPORT_FILE_FR, "съвпадението между езиците");

    // ⚠️ AppServiceImpl#extract копира САМО apps/init-data/<езикът на приложението>/, а езикът
    // идва от App.languageSelect. Тоест липсващ или разминат fr/ не пада никъде — той просто
    // прави вноса невалиден за инсталация на френски, мълчаливо. Същото ограничение, което
    // BgSeed.rows налага на CSV-тата.
    assertTrue(
        Arrays.equals(Files.readAllBytes(IMPORT_FILE), Files.readAllBytes(IMPORT_FILE_FR)),
        "en/ и fr/ се разминават за leave_reason.xml — всяка промяна влиза и в двете, байт по"
            + " байт еднакво, защото App Install чете само директорията на своя език");
  }

  // ═══ ЗАВИСИМОСТТА ОТ WS-5 ═════════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ Полето LeaveReason.bgAbsenceType съществува — инак вносът МЪЛЧАЛИВО не съпоставя")
  void theTargetFieldExists() {
    read(IMPORT_FILE, "зависимостта от WS-5");

    boolean present = false;
    try {
      Class<?> leaveReason = Class.forName("com.axelor.apps.hr.db.LeaveReason");
      for (java.lang.reflect.Method m : leaveReason.getMethods()) {
        if ("getBgAbsenceType".equals(m.getName())) {
          present = true;
          break;
        }
      }
    } catch (ClassNotFoundException e) {
      fail("не намирам com.axelor.apps.hr.db.LeaveReason — класпътят се е сменил");
    }

    if (!present) {
      BgSkip.because(
          "LeaveReason.bgAbsenceType още го НЯМА в този клон. Полето идва от WS-5."
              + "\n  ⚠️ ВНОСЪТ НЕ БИВА ДА СЕ ПУСКА ПРЕДИ ТОВА: XMLBinder#populate прави"
              + " `if (property == null) continue`, тоест внос СЕГА не гърми — той създава 28 реда"
              + " БЕЗ съпоставяне и без вдигната грешка, а несъпоставеният вид кара"
              + " BgPayrollInputBuilder да ОТКАЖЕ и трите кофи на отпуските."
              + "\n  Този тест става червен сам, щом WS-5 влезе и полето се появи.");
    }
    assertTrue(present, "недостижимо — горното или пропуска, или полето е налице");
  }
}
