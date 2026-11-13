package com.axelor.apps.bglocalization.service.vat.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDocumentType.Diary;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.common.csv.CSVFile;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * ВИДЪТ НА ДОКУМЕНТА: селекцията, номенклатурата на НАП и дневниците говорят за едни и същи 19
 * кода.
 */
class BgVatDocumentTypeTest {

  private static final Path SELECTS = Paths.get("src/main/resources/views/Selects.xml");
  private static final String SELECTION = "bg.nap.document.type.select";
  private static final String NOMENCLATURE = "/nomenclatures/nra/nom_invoice_types.csv";

  /** Опциите на селекцията: стойност → заглавие, в реда на файла. */
  private static Map<String, String> selection() throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(false);
    Document xml;
    try (InputStream in = Files.newInputStream(SELECTS)) {
      xml = factory.newDocumentBuilder().parse(in);
    }
    NodeList selections = xml.getElementsByTagName("selection");
    for (int i = 0; i < selections.getLength(); i++) {
      Element selection = (Element) selections.item(i);
      if (SELECTION.equals(selection.getAttribute("name"))) {
        Map<String, String> options = new LinkedHashMap<>();
        NodeList nodes = selection.getElementsByTagName("option");
        for (int j = 0; j < nodes.getLength(); j++) {
          Element option = (Element) nodes.item(j);
          if (options.put(option.getAttribute("value"), option.getTextContent()) != null) {
            fail("повторена стойност " + option.getAttribute("value"));
          }
        }
        return options;
      }
    }
    fail(SELECTION + " я няма в " + SELECTS);
    return null;
  }

  /** Nom_Invoice_Types от книгата 1.0.2: код → наименование, в реда на книгата. */
  private static Map<String, String> nomenclature() throws Exception {
    Map<String, String> types = new LinkedHashMap<>();
    try (InputStream in = BgVatDocumentTypeTest.class.getResourceAsStream(NOMENCLATURE);
        CSVParser parser = CSVFile.DEFAULT.parse(in, StandardCharsets.UTF_8)) {
      boolean header = true;
      for (CSVRecord record : parser) {
        if (header) {
          assertEquals("code", record.get(0));
          header = false;
          continue;
        }
        types.put(record.get(0), record.get(1));
      }
    }
    return types;
  }

  @Test
  @DisplayName("Селекцията е Nom_Invoice_Types 19/19: същите кодове, в същия ред, „код – име“")
  void паритетът() throws Exception {
    Map<String, String> options = selection();
    Map<String, String> types = nomenclature();
    assertEquals(19, types.size());
    assertEquals(new ArrayList<>(types.keySet()), new ArrayList<>(options.keySet()));
    List<String> wrong = new ArrayList<>();
    types.forEach(
        (code, name) -> {
          String expected = code + " – " + name;
          if (!expected.equals(options.get(code))) {
            wrong.add(code + ": „" + options.get(code) + "“ ≠ „" + expected + "“");
          }
        });
    assertEquals(List.of(), wrong);
    // Функцията познава точно тези кодове — нито повече, нито по-малко.
    assertEquals(new ArrayList<>(types.keySet()), new ArrayList<>(BgVatDocumentType.codes()));
  }

  @Test
  @DisplayName("Петте заварени стойности са същите — заварените записи остават валидни")
  void заварените() throws Exception {
    Map<String, String> options = selection();
    assertEquals("01 – Фактура", options.get("01"));
    assertEquals("02 – Дебитно известие", options.get("02"));
    assertEquals("03 – Кредитно известие", options.get("03"));
    assertEquals("07 – Митническа декларация", options.get("07"));
    assertEquals("09 – Протокол или друг документ", options.get("09"));
  }

  @Test
  @DisplayName("Посоката по ППЗДДС: само продажби, само покупки, и двата")
  void матрицата() {
    Map<Diary, List<String>> byDiary = new LinkedHashMap<>();
    for (String code : BgVatDocumentType.codes()) {
      byDiary
          .computeIfAbsent(BgVatDocumentType.diaryOf(code).orElseThrow(), d -> new ArrayList<>())
          .add(code);
    }
    assertEquals(List.of("04", "29", "81", "82", "95"), byDiary.get(Diary.SALES));
    assertEquals(List.of("05", "92"), byDiary.get(Diary.PURCHASES));
    assertEquals(
        List.of("01", "02", "03", "07", "09", "11", "12", "13", "23", "91", "93", "94"),
        byDiary.get(Diary.BOTH));

    assertTrue(BgVatDocumentType.allowedInSales("81"));
    assertFalse(BgVatDocumentType.allowedInPurchases("81"));
    assertTrue(BgVatDocumentType.allowedInPurchases("92"));
    assertFalse(BgVatDocumentType.allowedInSales("92"));
    assertTrue(BgVatDocumentType.allowedInSales(" 01 "), "стойността се подрязва");
    assertTrue(BgVatDocumentType.allowedInPurchases("01"));
  }

  @Test
  @DisplayName("50, 83, 84, 85 (ДВ бр. 59/2022, до 31.12.2023) и празното нямат дневник")
  void непознатите() {
    for (String code : new String[] {"50", "83", "84", "85", "", "  ", "1", "001", null}) {
      assertFalse(BgVatDocumentType.diaryOf(code).isPresent(), "„" + code + "“");
      assertFalse(BgVatDocumentType.allowedInSales(code));
      assertFalse(BgVatDocumentType.allowedInPurchases(code));
    }
  }

  private static BgVatDocumentDto document(String type) {
    BgVatDocumentDto document = new BgVatDocumentDto();
    document.setNapDocumentType(type);
    document.setDocumentNumber("D-" + type);
    document.setDocumentDate(LocalDate.of(2026, 10, 5));
    return document;
  }

  @Test
  @DisplayName("PRODAGBI и POKUPKI пишат всеки нов код дословно в полето „Вид на документа“")
  void писателите() {
    BgVatExportRequestDto request = new BgVatExportRequestDto();
    List<BgVatDocumentDto> sales = new ArrayList<>();
    List<BgVatDocumentDto> purchases = new ArrayList<>();
    for (String code : BgVatDocumentType.codes()) {
      if (BgVatDocumentType.allowedInSales(code)) {
        sales.add(document(code));
      }
      if (BgVatDocumentType.allowedInPurchases(code)) {
        purchases.add(document(code));
      }
    }
    assertEquals(17, sales.size());
    assertEquals(14, purchases.size());

    List<String> salesLines = new BgVatSalesAdapter().buildLines(sales, request);
    for (int i = 0; i < sales.size(); i++) {
      assertEquals(sales.get(i).getNapDocumentType(), salesLines.get(i).substring(40, 42));
    }
    BgVatPurchaseAdapter purchaseAdapter = new BgVatPurchaseAdapter();
    List<String> purchaseLines =
        purchaseAdapter.buildLines(purchaseAdapter.buildRows(purchases, request));
    for (int i = 0; i < purchases.size(); i++) {
      assertEquals(purchases.get(i).getNapDocumentType(), purchaseLines.get(i).substring(40, 42));
    }
  }
}
