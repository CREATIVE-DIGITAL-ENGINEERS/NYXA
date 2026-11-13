package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИНВАРИАНТ (G3b): ръчният код на реда ({@code MoveLine.bgSaftTaxCode}) и подразбирането на
 * сметката ({@code Account.bgSaftTaxCode}) избират САМО от кодовете на TAX-IMP вид 600 и 700 —
 * всичките, и нищо друго. Изборът е преписан в изгледа, тоест без този тест нов код на НАП би
 * останал недостъпен, а сгрешен — избираем.
 */
class BgSaftTaxCodeSelectionTest {

  private static final String SELECTION = "bg.saft.tax.code.select";
  private static final Path RESOURCES = Paths.get("src/main/resources");

  @Test
  @DisplayName("ФАЙЛ · изборът е точно кодовете на TAX-IMP вид 600 и 700")
  void изборътЕTaxImp600и700() throws IOException {
    Set<String> expected = new TreeSet<>();
    try (InputStream in =
            BgSaftTaxCodeSelectionTest.class.getResourceAsStream("/nomenclatures/nra/tax_imp.csv");
        CSVParser parser = CSVFile.DEFAULT.parse(in, StandardCharsets.UTF_8)) {
      for (CSVRecord r : parser) {
        String type = r.get(0).trim();
        if ("600".equals(type) || "700".equals(type)) {
          expected.add(r.get(1).trim());
        }
      }
    }
    String views =
        new String(
            Files.readAllBytes(RESOURCES.resolve("views/bg-saf-mapping-views.xml")),
            StandardCharsets.UTF_8);
    Matcher block =
        Pattern.compile(
                "<selection name=\"" + Pattern.quote(SELECTION) + "\">(.*?)</selection>",
                Pattern.DOTALL)
            .matcher(views);
    if (!block.find()) {
      fail("няма избор " + SELECTION + " в bg-saf-mapping-views.xml");
    }
    Set<String> actual = new TreeSet<>();
    Matcher option =
        Pattern.compile("<option value=\"([^\"]+)\">([^<]+)</option>").matcher(block.group(1));
    while (option.find()) {
      actual.add(option.group(1));
      assertTrue(
          option.group(2).startsWith(option.group(1) + " "),
          "етикетът започва с кода: " + option.group(2));
    }
    assertEquals(62, expected.size(), "TAX-IMP носи 8 кода от вид 600 и 54 от вид 700");
    assertEquals(expected, actual);
  }

  @Test
  @DisplayName("ФАЙЛ · двете полета ползват този избор")
  void полетата() throws IOException {
    for (String domain : new String[] {"MoveLine.xml", "Account.xml"}) {
      String xml =
          new String(
              Files.readAllBytes(RESOURCES.resolve("domains").resolve(domain)),
              StandardCharsets.UTF_8);
      Matcher field =
          Pattern.compile("<string name=\"bgSaftTaxCode\"[^>]*selection=\"([^\"]+)\"").matcher(xml);
      assertTrue(field.find(), domain + ": няма поле bgSaftTaxCode с избор");
      assertEquals(SELECTION, field.group(1), domain);
    }
  }
}
