/*
 * Axelor Business Solutions
 *
 * Copyright (C) 2005-2025 Axelor (<http://axelor.com>).
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИНВАРИАНТ: мерните единици носят код по НАП, конверсиите им сочат съществуващи единици, а
 * преписът на номенклатурата е преписът на номенклатурата.
 *
 * <p><b>Защо изобщо съществува.</b> {@code data-init/input/} е ВТОРИЯТ вносителски корен на модула
 * и до този кръг беше БЕЗ НИТО ЕДИН пазач на съдържанието: {@code BgSeed} чете само {@code
 * apps/init-data/}, тоест 32-та seed теста не виждат нито {@code base_unit.csv}, нито {@code
 * base_unitConversion.csv}. Единственият им читател — {@code BgUnitDataInitPersistenceTest} — е
 * {@code @Tag("integration")}, а {@code build.gradle} изключва този таг от {@code test}. CI пуска
 * {@code ./gradlew build}. Тоест до днес тези два файла не са били проверявани от НИЩО в CI.
 *
 * <p><b>Защо е файлов, а не през базата.</b> Същият довод като при кръг 3: въпросът „носи ли всяка
 * единица код по НАП“ има отговор от файловете, а манифестните тестове се пропускат, когато App
 * Install не е пускан — тоест точно този инвариант щеше да мълчи в CI.
 *
 * <p><b>Защо кодът се сверява срещу номенклатурата, а не срещу списък тук.</b> Списък в тест
 * остарява мълчаливо при първото обновяване от НАП. {@code unit_of_measure.csv} е единственият
 * авторитетен препис и {@code BgNraNomenclatureIntegrityTest} вече пази него.
 */
class BgUnitSeedTest {

  private static final String UNITS = "/data-init/input/base_unit.csv";
  private static final String CONVERSIONS = "/data-init/input/base_unitConversion.csv";
  private static final String NOMENCLATURE = "/nomenclatures/nra/unit_of_measure.csv";
  private static final String NRA_SEED = "bg_nra_uom.csv";

  /**
   * 36-те заварени единици. ⚠️ ЗАКОВАНИ ПОИМЕННО, А НЕ ПРЕБРОЕНИ. Номерата им са ЧУЖДИ: те идват от
   * демо-данните на axelor-base и {@code base_unitConversion.csv} сочи дванайсет от тях по номер.
   * Преномериране не е преименуване — то би пренасочило конверсия към друга единица и би мълчало,
   * защото новият номер пак съществува. Брой нямаше да го хване; наборът го хваща.
   */
  private static final Set<String> LEGACY_UNITS =
      new LinkedHashSet<>(
          Arrays.asList(
              "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "100", "101", "102", "103", "104",
              "105", "106", "200", "300", "301", "302", "303", "304", "305", "306", "307", "308",
              "400", "401", "402", "403", "404", "405", "406", "407", "501"));

  /** 24-те заварени конверсии — същият довод, същият ключ. */
  private static final int LEGACY_CONVERSIONS = 24;

  /**
   * Масовата стълба, в грамове. ⚠️ Квинталът (307) е 100 kg — това е определението му, а НАП няма
   * код „квинтал“: той се изнася като DTN (децитон), което е същите 100 kg под друго име.
   */
  private static final Map<String, String> MASS =
      ladder(
          "300:0.001",
          "301:0.01",
          "302:0.1",
          "303:1",
          "304:10",
          "305:100",
          "306:1000",
          "307:100000",
          "308:1000000");

  /**
   * Дължинната стълба, в метри. ⚠️ Милята (407) НЕ е степен на десет: 1 mi = 1609.344 m точно
   * (international mile). Тя е в стълбата, защото е единица за дължина и без нея седем двойки биха
   * липсвали мълчаливо.
   */
  private static final Map<String, String> LENGTH =
      ladder(
          "400:0.001",
          "401:0.01",
          "402:0.1",
          "403:1",
          "404:10",
          "405:100",
          "406:1000",
          "407:1609.344");

  private static Map<String, String> ladder(String... pairs) {
    Map<String, String> out = new LinkedHashMap<>();
    for (String p : pairs) {
      String[] kv = p.split(":");
      out.put(kv[0], kv[1]);
    }
    return out;
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("Единиците са 36, с уникални и НЕПРОМЕНЕНИ importId-та")
  void unitsAreTheThirtySixLegacyRows() {
    List<CSVRecord> rows = rows(UNITS);
    Set<String> ids = new LinkedHashSet<>();
    List<String> duplicates = new ArrayList<>();
    for (CSVRecord r : rows) {
      if (!ids.add(r.get("importId"))) {
        duplicates.add(r.get("importId"));
      }
    }
    if (!duplicates.isEmpty()) {
      fail(
          "повторен importId в base_unit.csv: "
              + duplicates
              + " — вносителят е upsert по този ключ, тоест вторият ред ПРЕЗАПИСВА първия и"
              + " единицата просто изчезва, без нито една грешка при импорт.");
    }
    assertEquals(
        LEGACY_UNITS,
        ids,
        "наборът importId-та в base_unit.csv се различава от заварения. Старите 36 номера са ЧУЖДИ"
            + " (идват от демо-данните на axelor-base) и base_unitConversion.csv сочи част от тях"
            + " ПО НОМЕР — преномериране пренасочва конверсия към друга единица и мълчи.");
  }

  @Test
  @DisplayName("Всяка единица носи код по НАП, който съществува в номенклатурата")
  void everyUnitCarriesAKnownNraCode() {
    Set<String> known = new LinkedHashSet<>();
    for (CSVRecord r : rows(NOMENCLATURE, ',')) {
      known.add(r.get("code"));
    }
    List<String> broken = new ArrayList<>();
    for (CSVRecord r : rows(UNITS)) {
      String code = r.get("bgUomCode");
      if (code == null || code.trim().isEmpty()) {
        broken.add("importId " + r.get("importId") + " („" + r.get("name") + "“): празен код");
      } else if (!known.contains(code)) {
        broken.add(
            "importId " + r.get("importId") + " („" + r.get("name") + "“): „" + code + "“ го НЯМА");
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "код по НАП, който не съществува в nomenclatures/nra/unit_of_measure.csv (2071 реда).\n"
              + "⚠️ Измислен код не пада при импорт и не пада при валидация на SAF-T срещу XSD-то —"
              + " SAFcodeType е просто „текст до 9 знака“. Чупи се чак при НАП:\n  "
              + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Конверсиите сочат съществуващи единици и нито една двойка не е определена два пъти")
  void conversionsResolveAndAreUnambiguous() {
    Set<String> units = new LinkedHashSet<>();
    for (CSVRecord r : rows(UNITS)) {
      units.add(r.get("importId"));
    }
    Set<String> ids = new LinkedHashSet<>();
    Map<String, String> seenPairs = new LinkedHashMap<>();
    List<String> broken = new ArrayList<>();
    int legacy = 0;
    for (CSVRecord r : rows(CONVERSIONS)) {
      String id = r.get("importId");
      String from = r.get("startUnit.importId");
      String to = r.get("endUnit.importId");
      if (!ids.add(id)) {
        broken.add("повторен importId " + id);
      }
      if (Integer.parseInt(id) <= LEGACY_CONVERSIONS) {
        legacy++;
      }
      if (!units.contains(from)) {
        broken.add("конверсия " + id + ": startUnit " + from + " го няма в base_unit.csv");
      }
      if (!units.contains(to)) {
        broken.add("конверсия " + id + ": endUnit " + to + " го няма в base_unit.csv");
      }
      // Двойката е НЕНАСОЧЕНА: UnitConversionServiceImpl#getCoefficient чете реда и в обратната
      // посока (1/coef), тоест втори ред за същите две единици прави резултата зависим от реда на
      // fetch-а — мълчаливо и невъзпроизводимо.
      String key = from.compareTo(to) < 0 ? from + "↔" + to : to + "↔" + from;
      String prev = seenPairs.put(key, id);
      if (prev != null) {
        broken.add("двойката " + key + " е определена и в " + prev + ", и в " + id);
      }
      if ("1".equals(r.get("typeSelect"))
          && new BigDecimal(r.get("coef")).compareTo(BigDecimal.ZERO) <= 0) {
        broken.add("конверсия " + id + ": коефициент " + r.get("coef") + " не е положителен");
      }
    }
    assertEquals(
        LEGACY_CONVERSIONS,
        legacy,
        "броят заварени конверсии (importId <= " + LEGACY_CONVERSIONS + ") се е променил");
    if (!broken.isEmpty()) {
      fail("base_unitConversion.csv:\n  " + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Метричните стълби са ПЪЛНИ по двойки и коефициентите им са точни")
  void metricLaddersAreCompleteAndExact() {
    Map<String, BigDecimal> actual = new LinkedHashMap<>();
    for (CSVRecord r : rows(CONVERSIONS)) {
      if ("1".equals(r.get("typeSelect"))) {
        actual.put(
            r.get("startUnit.importId") + "→" + r.get("endUnit.importId"),
            new BigDecimal(r.get("coef")));
      }
    }
    List<String> broken = new ArrayList<>();
    for (Map<String, String> ladder : Arrays.asList(MASS, LENGTH)) {
      List<String> ids = new ArrayList<>(ladder.keySet());
      for (int i = 0; i < ids.size(); i++) {
        for (int j = i + 1; j < ids.size(); j++) {
          String small = ids.get(i);
          String big = ids.get(j);
          BigDecimal expected =
              new BigDecimal(ladder.get(big))
                  .divide(new BigDecimal(ladder.get(small)))
                  .stripTrailingZeros();
          BigDecimal got = actual.get(big + "→" + small);
          if (got == null) {
            broken.add(
                "липсва " + big + " → " + small + " (очаква се " + expected.toPlainString() + ")");
          } else if (got.compareTo(expected) != 0) {
            broken.add(
                big
                    + " → "
                    + small
                    + ": "
                    + got.toPlainString()
                    + " вместо "
                    + expected.toPlainString());
          }
        }
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "метричните конверсии са непълни или неточни.\n"
              + "⚠️ ПЪЛНИ ПО ДВОЙКИ, А НЕ ВЕРИГА, И ТОВА Е ИЗМЕРЕНО:"
              + " UnitConversionServiceImpl#getCoefficient търси ПРЯКА двойка (или обратната ѝ) и"
              + " ХВЪРЛЯ, ако я няма — транзитивност НЯМА. Веригата mg→g→kg→t оставя mg→kg счупена,"
              + " а SAF-T иска точно такъв коефициент (UOMToUOMBaseConversionFactor):\n  "
              + String.join("\n  ", broken));
    }
  }

  @Test
  @DisplayName("Преписът на номенклатурата съвпада с номенклатурата ред по ред")
  void nraSeedMirrorsTheNomenclature() {
    Map<String, String[]> source = new LinkedHashMap<>();
    for (CSVRecord r : rows(NOMENCLATURE, ',')) {
      source.put(r.get("code"), new String[] {r.get("nameEn"), r.get("nameBg")});
    }

    List<String> header = BgSeed.quotedHeader(NRA_SEED);
    assertEquals(
        Arrays.asList("importId", "code", "nameEn", "nameBg"),
        header,
        "заглавният ред на преписа се е разминал");

    Map<String, String[]> copy = new LinkedHashMap<>();
    List<String> broken = new ArrayList<>();
    for (String[] row : BgSeed.quotedRows(NRA_SEED)) {
      String code = BgSeed.col(header, row, "code");
      String importId = BgSeed.col(header, row, "importId");
      if (!("SAFTUOM-" + code).equals(importId)) {
        broken.add("код " + code + ": importId е „" + importId + "“, а не „SAFTUOM-" + code + "“");
      }
      if (copy.put(
              code,
              new String[] {BgSeed.col(header, row, "nameEn"), BgSeed.col(header, row, "nameBg")})
          != null) {
        broken.add("код " + code + " се среща два пъти в преписа");
      }
    }

    Set<String> missing = new TreeSet<>(source.keySet());
    missing.removeAll(copy.keySet());
    Set<String> extra = new TreeSet<>(copy.keySet());
    extra.removeAll(source.keySet());
    if (!missing.isEmpty()) {
      broken.add("липсват в преписа: " + missing);
    }
    if (!extra.isEmpty()) {
      broken.add("измислени в преписа: " + extra);
    }
    for (Map.Entry<String, String[]> e : source.entrySet()) {
      String[] mine = copy.get(e.getKey());
      if (mine != null && !Arrays.equals(e.getValue(), mine)) {
        broken.add(
            "код "
                + e.getKey()
                + ": наименование „"
                + Arrays.toString(mine)
                + "“ вместо „"
                + Arrays.toString(e.getValue())
                + "“");
      }
    }
    if (!broken.isEmpty()) {
      fail(
          "преписът apps/init-data/*/"
              + NRA_SEED
              + " се е разминал с"
              + " nomenclatures/nra/unit_of_measure.csv.\n"
              + "⚠️ ПОСОКАТА Е ЕДНА: файлът в nomenclatures/ е източникът и остава инертен."
              + " Обновяване от НАП се прави ТАМ, а преписът се прегенерира — никога обратното:\n  "
              + String.join("\n  ", broken));
    }
    assertEquals(source.size(), copy.size(), "броят редове в преписа се разминава с източника");
  }

  // ─────────────────────────────────────────────────────────────────────────────────────────────

  private static List<CSVRecord> rows(String resource) {
    return rows(resource, ';');
  }

  /**
   * Чете през {@link CSVFile}, а не през голо цепене: {@code base_unitConversion.csv} носи ВГРАДЕН
   * НОВ РЕД във formula на ред 24 (преобразуването m3 → kg), тоест четене „по редове“ дава счупен
   * последен запис. {@code CSVFile.parse(InputStream, Charset)} маха и BOM-а.
   */
  private static List<CSVRecord> rows(String resource, char delimiter) {
    try (InputStream in = BgUnitSeedTest.class.getResourceAsStream(resource)) {
      if (in == null) {
        fail("липсва на classpath-а: " + resource);
      }
      try (CSVParser parser =
          CSVFile.DEFAULT
              .withDelimiter(delimiter)
              .withFirstRecordAsHeader()
              .parse(in, StandardCharsets.UTF_8)) {
        List<CSVRecord> out = new ArrayList<>();
        for (CSVRecord record : parser) {
          out.add(record);
        }
        return out;
      }
    } catch (IOException e) {
      fail("не се чете " + resource + ": " + e.getMessage());
      return null;
    }
  }
}
