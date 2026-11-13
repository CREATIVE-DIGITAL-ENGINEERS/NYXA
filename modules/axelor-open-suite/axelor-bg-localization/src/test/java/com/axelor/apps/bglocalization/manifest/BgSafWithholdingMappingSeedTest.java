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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИНВАРИАНТ: петата референтна таблица на SAF-T — ВИД ДОХОД → КОД ЗА ДАНЪК ПРИ ИЗТОЧНИКА (G3b) —
 * се сверява срещу двата си източника: каталога {@code bg_income_type.csv} и номенклатурата
 * TAX-IMP.
 *
 * <p><b>Защо файлов.</b> Същият довод като в {@link BgSafMappingSeedTest}: връзката е по КОД, не по
 * външен ключ, тоест вносителят не вижда разминаване; база не е нужна, за да се отговори.
 *
 * <p><b>Трите правила не са степени на увереност.</b> {@code CODE} — кодът е еднозначен; {@code
 * NONE} — платецът НЕ удържа данък при източника (шаблони D и F, или {@code withheldByPayer =
 * false}), тоест редът законно носи 000/000000; {@code AMBIGUOUS} — един вид доход покрива два кода
 * на TAX-IMP и без ръчен код на реда генераторът дава ГРЕШКА.
 */
class BgSafWithholdingMappingSeedTest {

  private static final String MAP = "bg_saf_withholding_mapping.csv";
  private static final String INCOME = "bg_income_type.csv";
  private static final String TAX_IMP = "/nomenclatures/nra/tax_imp.csv";

  private static final Set<String> RULES =
      new LinkedHashSet<>(Arrays.asList("CODE", "NONE", "AMBIGUOUS"));

  /**
   * Единственият двусмислен вид доход: 804 е „наем … на движимо ИЛИ недвижимо имущество“, а TAX-IMP
   * ги разделя — 700260 (ЗКПО чл. 12 ал. 5 т. 2) и 700300 (ЗКПО чл. 12 ал. 8 т. 1). Изброен
   * поименно, защото двусмислието е ИЗЯВЛЕНИЕ, а не пропуск.
   */
  private static final Set<String> AMBIGUOUS = Collections.singleton("804");

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: всеки вид доход има ТОЧНО един ред, и нищо повече")
  void всеки_вид_доход_има_ред() {
    Set<String> catalogue = new TreeSet<>(incomeTypes().keySet());
    List<String> h = BgSeed.header(MAP);
    Set<String> mapped = new TreeSet<>();
    List<String> duplicates = new ArrayList<>();
    List<String> badImportId = new ArrayList<>();
    for (String[] row : BgSeed.rows(MAP)) {
      String code = BgSeed.col(h, row, "incomeTypeCode");
      if (!mapped.add(code)) {
        duplicates.add(code);
      }
      if (!("SAFTWHT-" + code).equals(BgSeed.col(h, row, "importId"))) {
        badImportId.add(code + " → " + BgSeed.col(h, row, "importId"));
      }
    }
    assertEquals(Collections.emptyList(), duplicates, "вид доход с повече от един ред");
    assertEquals(
        Collections.emptyList(),
        badImportId,
        "importId е производен от кода (SAFTWHT-<код>), не брояч — инак пренареждане на файла"
            + " преименува заварени редове при upsert-а");
    assertEquals(
        catalogue,
        mapped,
        "картата се разминава с bg_income_type.csv. Непокрит вид доход значи плащане по фактура,"
            + " което излиза в дневника без код за данък при източника.");
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: CODE носи код от TAX-IMP вид 700, другите две — празно")
  void кодът_е_от_tax_imp_вид_700() {
    Map<String, CSVRecord> taxImp = taxImp();
    List<String> h = BgSeed.header(MAP);
    List<String> problems = new ArrayList<>();
    Set<String> ambiguous = new TreeSet<>();
    for (String[] row : BgSeed.rows(MAP)) {
      String code = BgSeed.col(h, row, "incomeTypeCode");
      String rule = BgSeed.col(h, row, "ruleSelect");
      String saf = BgSeed.col(h, row, "safTaxCode");
      if (!RULES.contains(rule)) {
        problems.add(code + " · правило „" + rule + "“");
        continue;
      }
      if ("AMBIGUOUS".equals(rule)) {
        ambiguous.add(code);
      }
      if ("CODE".equals(rule) != !saf.isEmpty()) {
        problems.add(code + " · " + rule + " с код „" + saf + "“");
        continue;
      }
      if (saf.isEmpty()) {
        continue;
      }
      CSVRecord r = taxImp.get(saf);
      if (r == null) {
        problems.add(code + " → " + saf + " (няма го в tax_imp.csv)");
      } else if (!"700".equals(r.get(0).trim())) {
        problems.add(code + " → " + saf + " (вид " + r.get(0) + ", не 700)");
      }
    }
    assertEquals(Collections.emptyList(), problems, "редове извън правилата");
    assertEquals(AMBIGUOUS, ambiguous, "двусмислените видове доход не са точно изброените");
  }

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ ИНВАРИАНТ: NONE е точно когато платецът не удържа (D, F или withheldByPayer=false)")
  void none_следва_от_каталога() {
    Map<String, Map<String, String>> catalogue = incomeTypes();
    List<String> h = BgSeed.header(MAP);
    List<String> problems = new ArrayList<>();
    for (String[] row : BgSeed.rows(MAP)) {
      String code = BgSeed.col(h, row, "incomeTypeCode");
      Map<String, String> type = catalogue.get(code);
      if (type == null) {
        continue;
      }
      String pattern = type.get("taxPattern");
      boolean notWithheld =
          "D".equals(pattern) || "F".equals(pattern) || !"true".equals(type.get("withheldByPayer"));
      boolean none = "NONE".equals(BgSeed.col(h, row, "ruleSelect"));
      if (notWithheld != none) {
        problems.add(
            code
                + " · шаблон "
                + pattern
                + ", удържа="
                + type.get("withheldByPayer")
                + ", правило "
                + BgSeed.col(h, row, "ruleSelect"));
      }
    }
    assertEquals(
        Collections.emptyList(),
        problems,
        "NONE трябва да съвпада с „платецът не удържа“ — инак или ред с данък излиза като 000,"
            + " или ред без данък получава код 700");
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: ставката на кода в TAX-IMP е ставката на вида доход")
  void ставката_съвпада() {
    Map<String, CSVRecord> taxImp = taxImp();
    Map<String, Map<String, String>> catalogue = incomeTypes();
    List<String> h = BgSeed.header(MAP);
    List<String> problems = new ArrayList<>();
    for (String[] row : BgSeed.rows(MAP)) {
      String saf = BgSeed.col(h, row, "safTaxCode");
      Map<String, String> type = catalogue.get(BgSeed.col(h, row, "incomeTypeCode"));
      if (saf.isEmpty() || type == null || taxImp.get(saf) == null) {
        continue;
      }
      // Окончателният данък (шаблон C) е по глава шеста; всички останали удържани — авансов.
      String rate =
          "C".equals(type.get("taxPattern"))
              ? type.get("finalTaxRate")
              : type.get("advanceTaxRate");
      BigDecimal expected = new BigDecimal(rate).movePointLeft(2);
      BigDecimal actual = new BigDecimal(taxImp.get(saf).get(4).trim());
      if (expected.compareTo(actual) != 0) {
        problems.add(
            type.get("code") + " → " + saf + " (" + rate + " % срещу TAX-IMP " + actual + ")");
      }
    }
    assertEquals(
        Collections.emptyList(),
        problems,
        "кодът по TAX-IMP носи друга ставка — значи е сгрешен ред на номенклатурата");
  }

  @Test
  @DisplayName("ФАЙЛ · свидетели: решенията от дискавърито G3b, ред по ред")
  void свидетели() {
    Map<String, String> expected = new LinkedHashMap<>();
    // Трудови доходи — ЗДДФЛ чл. 42 ал. 4: 10 на сто, морско лице 1 на сто.
    expected.put("101", "700500");
    expected.put("102", "700500");
    expected.put("103", "700520");
    // Стопанска дейност, наем и други източници — авансов данък, удържан от платеца.
    expected.put("3073", "700500");
    expected.put("401", "700500");
    expected.put("606", "700500");
    // Окончателен данък — по бележката на TAX-IMP.
    expected.put("805", "700010");
    expected.put("806", "700080");
    expected.put("807", "700140");
    expected.put("803", "700180");
    expected.put("819", "700180");
    expected.put("820", "700300");
    expected.put("814", "700330");
    expected.put("8141", "700330");
    expected.put("809", "700360");
    expected.put("813", "700400");
    expected.put("816", "700420");
    expected.put("817", "700420");
    expected.put("8171", "700430");
    expected.put("801", "700450");
    expected.put("802", "700450");
    expected.put("808", "700450");
    expected.put("822", "700480");
    // Без данък при източника.
    expected.put("810", "");
    expected.put("815", "");
    expected.put("501", "");
    expected.put("903", "");
    expected.put("804", "");

    Map<String, String> actual = new TreeMap<>();
    List<String> h = BgSeed.header(MAP);
    for (String[] row : BgSeed.rows(MAP)) {
      String code = BgSeed.col(h, row, "incomeTypeCode");
      if (expected.containsKey(code)) {
        actual.put(code, BgSeed.col(h, row, "safTaxCode"));
      }
    }
    assertEquals(new TreeMap<>(expected), actual);
  }

  // ═══════════════════════════ ПОМОЩНИ ═══════════════════════════

  /** Каталогът по код — последният ред печели, но кодовете в него са уникални. */
  private static Map<String, Map<String, String>> incomeTypes() {
    List<String> h = BgSeed.quotedHeader(INCOME);
    Map<String, Map<String, String>> out = new TreeMap<>();
    for (String[] row : BgSeed.quotedRows(INCOME)) {
      Map<String, String> type = new LinkedHashMap<>();
      for (String column :
          Arrays.asList(
              "code", "taxPattern", "finalTaxRate", "advanceTaxRate", "withheldByPayer")) {
        type.put(column, BgSeed.col(h, row, column));
      }
      out.put(type.get("code"), type);
    }
    return out;
  }

  /** TAX-IMP по код — със запетая и кавички по RFC 4180. */
  private static Map<String, CSVRecord> taxImp() {
    try (InputStream in = BgSafWithholdingMappingSeedTest.class.getResourceAsStream(TAX_IMP)) {
      if (in == null) {
        fail("липсва номенклатура на classpath-а: " + TAX_IMP);
      }
      Map<String, CSVRecord> out = new LinkedHashMap<>();
      try (CSVParser parser = CSVFile.DEFAULT.parse(in, StandardCharsets.UTF_8)) {
        boolean header = true;
        for (CSVRecord r : parser) {
          if (header) {
            header = false;
            continue;
          }
          out.put(r.get(1).trim(), r);
        }
      }
      return out;
    } catch (IOException e) {
      throw new IllegalStateException("не се чете " + TAX_IMP, e);
    }
  }
}
