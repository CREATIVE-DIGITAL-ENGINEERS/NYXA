package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
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
import java.util.TreeMap;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * ⚠️ ИНВАРИАНТ: в НИТО ЕДИН seed файл един {@code importId} не се среща два пъти.
 *
 * <p><b>Защо инвариант, а не факт за днес.</b> App Install е upsert ПО {@code importId} и НЕ ТРИЕ.
 * Два реда с един и същ номер в един файл не дават два записа: вторият ОБНОВЯВА първия, тоест
 * данните на първия ред изчезват, а вносителят приключва зелен. Точно това стана с {@code
 * bg_compensation_type.csv}: трите нови реда по чл. 106 ЗДСл влязоха под CT-12/13/14-2026 — вече
 * заети от чл. 221 ал. 1, чл. 221 ал. 2 и чл. 200 КТ — и 28 реда в файла щяха да дадат 25 в базата,
 * с три обезщетения по КТ, преименувани в обезщетения по ЗДСл. Файлът броеше 28, базата 25, и
 * разликата се виждаше само ако някой брои — тоест не се виждаше.
 *
 * <p><b>Защо СВЕРКАТА Е ПО ФАЙЛ, а не по entity.</b> Изкушението е да се събере целият номерен
 * обхват на едно entity и да се иска уникалност там. Това би било ГРЕШНО твърдение: {@code
 * bg_nkid_mod_mapping.csv} нарочно преизползва всичките 649 номера на {@code bg_nkid.csv}, за да
 * ДОПИШЕ колони върху същите записи — втори вносител върху същия ключ е точно начинът, по който се
 * обогатява заварен ред. Уникалността по файл различава двете: повторение ВЪТРЕ в един вход винаги
 * губи данни, повторение МЕЖДУ входове е обогатяване.
 *
 * <p><b>Откъде идва списъкът с файлове.</b> От самите конфигурации в {@code init-data}, а не от
 * закован списък в теста и не от изброяване на директорията: закованият списък остарява мълчаливо
 * при нов seed файл, а директорията съдържа и файлове, които никой вход не чете. Разделителят също
 * идва от входа — {@code bg_absence_type.csv} е със запетая, останалите с точка и запетая.
 *
 * <p><b>Файлов, нарочно.</b> Без Postgres и без App Install: „уникален ли е номерът“ има отговор от
 * файла. Базовата половина на този въпрос е неотговорима — след като вносителят е слял двата реда,
 * базата вече не помни, че са били два.
 */
class BgSeedImportIdUniquenessTest {

  private static final Path INIT_DATA =
      BgDomainColumns.moduleRoot().resolve("src/main/resources/apps/init-data");
  private static final List<String> LANGS = Arrays.asList("en", "fr");
  private static final String ID = "importId";

  /** Каталогът на обезщетенията — файлът, върху който инвариантът се счупи. */
  private static final String CATALOGUE = "bg_compensation_type.csv";

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: нито един seed файл не повтаря importId")
  void noSeedFileRepeatsAnImportId() throws Exception {
    List<String> broken = new ArrayList<>();
    List<String> withoutImportId = new ArrayList<>();
    int checkedFiles = 0;
    int checkedRows = 0;

    Map<String, Character> inputs = declaredInputs();
    assertTrue(
        inputs.size() > 10,
        "конфигурациите в "
            + INIT_DATA
            + " дадоха само "
            + inputs.size()
            + " CSV входа — ако подредбата им се е сменила, този тест мълчи вместо да мери");

    for (Map.Entry<String, Character> input : inputs.entrySet()) {
      for (String lang : LANGS) {
        Path csv = INIT_DATA.resolve(lang).resolve(input.getKey());
        if (!Files.exists(csv)) {
          // studio_appBgLocalization.csv стои само в en/ — състояние на модула, не дефект.
          continue;
        }

        List<String> fields = firstFieldOfEveryRecord(csv, input.getValue());
        if (fields.isEmpty()) {
          fail("seed файлът " + csv + " е празен — това не е състояние, което тест бива да мине");
        }
        if (!ID.equals(fields.get(0))) {
          withoutImportId.add(
              lang + "/" + input.getKey() + " (първа колона „" + fields.get(0) + "“)");
          continue;
        }

        checkedFiles++;
        Set<String> seen = new LinkedHashSet<>();
        for (int row = 1; row < fields.size(); row++) {
          checkedRows++;
          String id = fields.get(row);
          if (id.isEmpty()) {
            broken.add(lang + "/" + input.getKey() + " · ред " + (row + 1) + " · ПРАЗЕН importId");
          } else if (!seen.add(id)) {
            broken.add(lang + "/" + input.getKey() + " · ред " + (row + 1) + " · " + id);
          }
        }
      }
    }

    // ⚠️ ПОДОВЕ, А НЕ ТОЧНИ ЧИСЛА: точното равенство пада при всеки нов seed ред, тоест наказва
    // точно растежа, който тестът съществува да пази. Подът пази от обратното — сверка, която е
    // спряла да намира файлове и минава вакуумно.
    assertTrue(
        checkedFiles >= 40,
        "сверени са само "
            + checkedFiles
            + " файла (очаквани поне 40) — сверката е спряла да намира seed-а и зеленото не значи"
            + " нищо. Пропуснати без колона importId: "
            + withoutImportId);
    assertTrue(
        checkedRows > 10000,
        "сверени са само " + checkedRows + " реда (очаквани над 10 000) — виж горното");

    if (!broken.isEmpty()) {
      fail(
          "повторен importId в един и същ seed вход — App Install е upsert по него, тоест вторият"
              + " ред ПРЕЗАПИСВА първия и данните на първия изчезват мълкогом ("
              + broken.size()
              + " случая):\n  "
              + String.join("\n  ", broken)
              + "\n\nПоправката е СВОБОДЕН номер за НОВИЯ ред — заварените остават с техните, инак"
              + " заварените редове осиротяват в базата.");
    }
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ обезщетенията по чл. 106 ЗДСл НЕ стоят върху номерата на КТ")
  void theCivilServantCompensationsDoNotSitOnTheLabourCodeNumbers() {
    Map<String, String> want = new LinkedHashMap<>();
    want.put("comp_kt_221_1", "CT-12-2026");
    want.put("comp_kt_221_2", "CT-13-2026");
    want.put("comp_kt_200", "CT-14-2026");
    want.put("comp_zdsl_106_3", "CT-26-2026");
    want.put("comp_zdsl_106_3a", "CT-27-2026");
    want.put("comp_zdsl_106_4", "CT-28-2026");

    List<String> header = BgSeed.quotedHeader(CATALOGUE);
    Map<String, String> got = new TreeMap<>();
    for (String[] row : BgSeed.quotedRows(CATALOGUE)) {
      String code = BgSeed.col(header, row, "code");
      if (want.containsKey(code)) {
        got.put(code, BgSeed.col(header, row, ID));
      }
    }

    assertEquals(
        new TreeMap<>(want),
        got,
        "⚠️ Трите реда по чл. 106 ЗДСл влязоха под CT-12/13/14-2026, вече заети от чл. 221 ал. 1,"
            + " чл. 221 ал. 2 и чл. 200 КТ. App Install е upsert по importId: вторият ред обновява"
            + " първия, тоест трите обезщетения по КТ се ПРЕИМЕНУВАТ в обезщетения по ЗДСл и"
            + " каталогът излиза с 25 реда вместо 28. Новите редове носят СВОБОДНИ номера"
            + " (CT-26/27/28-2026), а заварените ЗАПАЗВАТ своите — нови номера и за тях биха"
            + " оставили старите редове живи и без ред на фиша.");
  }

  /** Файловете, които входовете в {@code init-data} четат → разделителят на всеки. */
  private static Map<String, Character> declaredInputs() throws Exception {
    List<Path> configs = new ArrayList<>();
    try (DirectoryStream<Path> xmls = Files.newDirectoryStream(INIT_DATA, "*.xml")) {
      for (Path xml : xmls) {
        configs.add(xml);
      }
    }
    if (configs.isEmpty()) {
      fail("няма нито една конфигурация в " + INIT_DATA);
    }
    Collections.sort(configs);

    Map<String, Character> out = new LinkedHashMap<>();
    for (Path config : configs) {
      NodeList inputs = parse(config).getElementsByTagNameNS("*", "input");
      for (int i = 0; i < inputs.getLength(); i++) {
        Element input = (Element) inputs.item(i);
        String file = input.getAttribute("file");
        if (file.endsWith(".csv")) {
          out.put(file, separatorOf(input));
        }
      }
    }
    return out;
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
   * ПЪРВАТА колона на всеки запис — заглавният ред включително, по RFC 4180.
   *
   * <p>Кавичките се четат, макар стойността да не се пази: поле в кавички може да носи и
   * разделителя, и нов ред, а голото цепене по разделителя би сметнало продължението за нов запис и
   * би обявило измислени повторения. Останалите колони се подминават нарочно — този тест не пита за
   * тях, а пълен прочит на {@code bg_nkpd.csv} е 5507 реда без нужда.
   *
   * <p><b>Защо не през {@link BgSeed}.</b> Той цепи само по {@code ;} и настоява en/ и fr/ да са
   * байт по байт еднакви — вярно за файловете, които той чете, но {@code bg_absence_type.csv} е със
   * запетая и тази сверка минава през ВСИЧКИ входове, включително него.
   */
  private static List<String> firstFieldOfEveryRecord(Path file, char separator)
      throws IOException {
    String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    List<String> out = new ArrayList<>();
    StringBuilder value = new StringBuilder();
    boolean quoted = false;
    boolean first = true; // още сме в първата колона на записа

    for (int i = 0; i < text.length(); i++) {
      char ch = text.charAt(i);
      if (quoted) {
        if (ch == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            if (first) {
              value.append('"');
            }
            i++;
          } else {
            quoted = false;
          }
        } else if (first) {
          value.append(ch);
        }
      } else if (ch == '"') {
        quoted = true;
      } else if (ch == separator) {
        first = false; // първата колона свърши
      } else if (ch == '\n' || ch == '\r') {
        if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
          i++;
        }
        if (value.length() > 0 || !first) {
          out.add(value.toString());
        }
        value.setLength(0);
        first = true;
      } else if (first) {
        value.append(ch);
      }
    }
    if (value.length() > 0 || !first) {
      out.add(value.toString());
    }
    return out;
  }
}
