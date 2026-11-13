package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns.Width;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * ⚠️ ИНВАРИАНТ: нито една стойност в seed-а не надхвърля колоната, в която ще влезе.
 *
 * <p><b>Защо инвариант, а не факт за днес.</b> Дефектът засяга всяко текстово поле по един и същи
 * начин — например {@code BgCompensationType.note}, {@code BgInsuranceContribution.bgNote} (където
 * живее единственият ред с {@code bgTeacherScope = TEACHER}) и {@code
 * BgPayrollParameter.description} (двата здравни параметъра). Поправката е винаги една и съща —
 * {@code large="true"} плюс миграция — а следващото поле обикновено е „с запас, но със същия
 * таван“. Този тест пита за ВСИЧКИ полета наведнъж, вместо да гони поредното.
 *
 * <p><b>Защо мълчи иначе.</b> Вносителят не съкращава дългата стойност: той ОТХВЪРЛЯ целия ред с
 * „value too long for type character varying(255)“ и продължава със следващия. App Install свършва
 * зелен, а таблицата излиза с по-малко редове, отколкото казва файлът. Разминаването се вижда само
 * ако някой брои — тоест не се вижда.
 *
 * <p><b>Файлов, нарочно.</b> Без Postgres и без App Install: въпросът „ще се побере ли“ има отговор
 * от декларацията и от файла, а база не е нужна, за да се отговори. Манифестните тестове питат
 * другото — доставено ли е — и се пропускат, когато App Install не е пускан.
 *
 * <p><b>Откъде идват дължините.</b> От домейн XML-ите, през {@link BgDomainColumns} — същия прочит,
 * който ползва и базовата половина {@code BgSchemaColumnWidthTest}.
 *
 * <p><b>⚠️ Този тест мери срещу ДЕКЛАРАЦИЯТА, не срещу базата.</b> Той минава и когато базата още е
 * {@code varchar(255)}, защото {@code ddl=update} не разширява заварена колона. Твърдението
 * „действителната колона отговаря на декларираната“ е на {@code BgSchemaColumnWidthTest} и без него
 * зеленото тук не значи, че App Install ще зареди редовете.
 *
 * <p><b>Кои колони НЕ се мерят.</b> Само низовите. Колона, която сочи към дата, число, булево или
 * m2o, няма знаков таван и се подминава — включително колоните, които {@code <bind search="…">}
 * превръща във външен ключ. Тоест този тест не е проверка, че CSV-то е свързано правилно; той
 * отговаря на един въпрос и на никой друг.
 */
class BgSeedColumnWidthTest {

  private static final Path INIT_DATA =
      BgDomainColumns.moduleRoot().resolve("src/main/resources/apps/init-data");
  private static final Path CONFIG = INIT_DATA.resolve("bg-localization-config.xml");
  private static final List<String> LANGS = Arrays.asList("en", "fr");

  /**
   * Колони от базовия модел, които домейн XML-ът не декларира, а вносителят пълни. Дължините са
   * снети от {@code information_schema} на заварена база.
   */
  private static final Map<String, Integer> FRAMEWORK_COLUMNS = new LinkedHashMap<>();

  static {
    FRAMEWORK_COLUMNS.put("importId", 255);
    FRAMEWORK_COLUMNS.put("importOrigin", 255);
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: нито една seed стойност не надхвърля колоната си")
  void noSeedValueOverflowsItsDeclaredColumn() throws Exception {
    Map<String, Map<String, Width>> byEntity = BgDomainColumns.acrossAllModules();
    assertTrue(
        byEntity.size() > 100,
        "домейн XML-ите не са намерени — прочетени са само "
            + byEntity.size()
            + " entity-та; ако подредбата на модулите се е сменила, този тест мълчи вместо да мери");

    List<String> overflows = new ArrayList<>();
    int checkedValues = 0;

    Element config = parse(CONFIG);
    NodeList inputs = config.getElementsByTagNameNS("*", "input");
    assertTrue(inputs.getLength() > 0, "няма нито един <input> в " + CONFIG);

    for (int i = 0; i < inputs.getLength(); i++) {
      Element input = (Element) inputs.item(i);
      String file = input.getAttribute("file");
      String type = input.getAttribute("type");
      char separator = separatorOf(input);

      Map<String, Width> fields = byEntity.get(type);
      if (fields == null) {
        fail(
            "конфигът зарежда "
                + file
                + " в "
                + type
                + ", а такова entity няма в нито един домейн XML — дължините му не могат да се проверят");
      }

      // Колона → полетата, в които влиза: едноименното (авто-мапване) плюс всяко <bind to="…">.
      Map<String, List<String>> targets = new TreeMap<>();
      NodeList binds = input.getElementsByTagNameNS("*", "bind");
      for (int b = 0; b < binds.getLength(); b++) {
        Element bind = (Element) binds.item(b);
        String column = bind.getAttribute("column");
        String to = bind.getAttribute("to");
        if (!column.isEmpty() && !to.isEmpty()) {
          targets.computeIfAbsent(column, k -> new ArrayList<>()).add(to);
        }
      }

      for (String lang : LANGS) {
        Path csv = INIT_DATA.resolve(lang).resolve(file);
        if (!Files.exists(csv)) {
          // studio_appBgLocalization.csv стои само в en/ — това е състояние на модула, не дефект.
          continue;
        }
        List<List<String>> records = readCsv(csv, separator);
        if (records.size() < 2) {
          fail("seed файлът " + csv + " няма нито един ред под заглавния");
        }
        List<String> header = records.get(0);
        int importIdAt = header.indexOf("importId");

        for (int r = 1; r < records.size(); r++) {
          List<String> row = records.get(r);
          String importId = importIdAt >= 0 && importIdAt < row.size() ? row.get(importIdAt) : "—";

          for (int c = 0; c < header.size() && c < row.size(); c++) {
            String column = header.get(c);
            Integer limit = limitOf(column, targets.get(column), fields);
            if (limit == null) {
              continue; // не е низова колона, или е text — няма какво да се мери
            }
            checkedValues++;
            int length = row.get(c).length();
            if (length > limit) {
              overflows.add(
                  String.format(
                      "%s/%s · importId=%s · колона %s · %d знака срещу %d позволени",
                      lang, file, importId, column, length, limit));
            }
          }
        }
      }
    }

    assertTrue(
        checkedValues > 1000,
        "измерени са само "
            + checkedValues
            + " стойности — това е твърде малко, за да е вярно, и значи че мапването е спряло да намира колони");

    if (!overflows.isEmpty()) {
      fail(
          "seed стойности, които вносителят ще ОТХВЪРЛИ мълчаливо (редът не влиза, App Install"
              + " остава зелен) — "
              + overflows.size()
              + " на брой:\n  "
              + String.join("\n  ", overflows)
              + "\n\nПоправката е large=\"true\" в домейн XML-а ПЛЮС миграция за заварените бази —"
              + " виж src/main/resources/db/migration/2026-08-16-bg-payroll-parameter-description-text.sql");
    }
  }

  /** Тавана за една колона: {@code null}, когато няма какво да се мери. */
  private static Integer limitOf(String column, List<String> bound, Map<String, Width> fields) {
    List<String> candidates = new ArrayList<>();
    candidates.add(column);
    if (bound != null) {
      candidates.addAll(bound);
    }

    Integer limit = null;
    for (String candidate : candidates) {
      Width declared = fields.get(candidate);
      if (declared != null) {
        if (declared.isText()) {
          return null; // text — таван няма
        }
        limit = limit == null ? declared.chars : Math.min(limit, declared.chars);
      } else if (FRAMEWORK_COLUMNS.containsKey(candidate)) {
        int framework = FRAMEWORK_COLUMNS.get(candidate);
        limit = limit == null ? framework : Math.min(limit, framework);
      }
    }
    return limit;
  }

  private static char separatorOf(Element input) {
    String declared = input.getAttribute("separator");
    if (declared.isEmpty()) {
      return ','; // подразбиращият се на вносителя
    }
    if (declared.length() != 1) {
      fail("разделител с повече от един знак: „" + declared + "“");
    }
    return declared.charAt(0);
  }

  private static Element parse(Path file) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
  }

  /**
   * Чете CSV-то по RFC 4180: кавички групират, {@code ""} е кавичка вътре в поле, а поле в кавички
   * може да носи и разделителя, и нов ред.
   *
   * <p>Голото цепене по разделителя не върши работа тук: seed-ът наистина ползва кавички (напр.
   * „Управители и прокуристи на ТД, държавни и общински предприятия“) и техните два знака биха
   * влезли в измерената дължина.
   */
  private static List<List<String>> readCsv(Path file, char separator) throws IOException {
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    List<List<String>> records = new ArrayList<>();
    List<String> record = new ArrayList<>();
    StringBuilder value = new StringBuilder();
    boolean quoted = false;

    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            value.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          value.append(ch);
        }
      } else if (ch == '"') {
        quoted = true;
      } else if (ch == separator) {
        record.add(value.toString());
        value.setLength(0);
      } else if (ch == '\n' || ch == '\r') {
        if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        record.add(value.toString());
        value.setLength(0);
        if (record.size() > 1 || !record.get(0).isEmpty()) {
          records.add(record);
        }
        record = new ArrayList<>();
      } else {
        value.append(ch);
      }
    }
    if (value.length() > 0 || !record.isEmpty()) {
      record.add(value.toString());
      records.add(record);
    }
    return records;
  }
}
