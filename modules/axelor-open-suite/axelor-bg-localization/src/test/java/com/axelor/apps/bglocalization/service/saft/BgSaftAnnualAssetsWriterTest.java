package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAddress;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAsset;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetCounterparty;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetTransactions;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftExtraordinaryDepreciation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFilesAnnual;
import com.axelor.apps.bglocalization.service.saft.dto.SaftNonDepreciableAsset;
import com.axelor.apps.bglocalization.service.saft.dto.SaftValuationDap;
import com.axelor.apps.bglocalization.service.saft.dto.SaftValuationSap;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * АКТИВИТЕ В ГОДИШНИЯ ФАЙЛ (SAF-T A2): Asset и пълната AssetTransaction от writer-а.
 *
 * <p><b>Еталонът е официалният годишен пример на НАП</b> ({@code
 * VS_SAMPLE_AuditFile_Annual_V_1.0.1.xml}, Прил. № 1 към З-ЦУ-30-1797/13.10.2026): двата му актива
 * (амортизируем и неамортизируем) и двете транзакции, сглобени като DTO, дават същите елементи в
 * същия ред и със същите стойности. Сумите се сравняват като числа — примерът пише „156“ и
 * „100000“, writer-ът винаги два знака.
 *
 * <p><b>Схемата е 1.0.3</b> — NonDepreciableAssetDetails я има само там. Writer-ът не знае
 * версията: съдия е валидаторът по датата на генериране, и той отказва неамортизируем актив по
 * 1.0.2.
 *
 * <p>⚠️ Зеленото тук значи структура, не числа: стойностите са на примера. Извличането от модела на
 * активите е SAF-T A3.
 */
class BgSaftAnnualAssetsWriterTest {

  private static final String SAMPLE =
      "/schemas/saft/samples/VS_SAMPLE_AuditFile_Annual_V_1.0.1.xml";

  private static BigDecimal m(String value) {
    return new BigDecimal(value);
  }

  private static byte[] bytes(SaftAuditFile file) {
    return BgSaftXmlWriter.toBytes(file);
  }

  private static String text(SaftAuditFile file) {
    return new String(bytes(file), StandardCharsets.UTF_8);
  }

  private static List<BgDeclarationFileFinding> validate103(SaftAuditFile file) {
    return BgSaftFixtures.validate(bytes(file), BgSaftXsd.V1_0_3);
  }

  private static SaftAddress address(String city, String country) {
    return new SaftAddress().city(city).country(country);
  }

  // ─────────────── примерът на НАП като DTO ───────────────

  private static SaftAsset vwTransporter() {
    return new SaftAsset()
        .assetId("ДА000030")
        .accountId("206")
        .description("VW Transporter")
        .addSupplier(
            new SaftAssetCounterparty()
                .name("Mortimor Car Company")
                .id("02UK741852979")
                .postalAddress(
                    new SaftAddress()
                        .streetName("Industrial Heights")
                        .number("43")
                        .additionalAddressDetail("")
                        .building("")
                        .city("Stratford")
                        .postalCode("")
                        .region("")
                        .country("UK")
                        .addressType("StreetAddress")))
        .purchaseOrderDate(LocalDate.of(2021, 10, 1))
        .dateOfAcquisition(LocalDate.of(2021, 12, 20))
        .startUpDate(LocalDate.of(2022, 1, 1))
        .addValuationSap(
            new SaftValuationSap()
                .valuationClass("206")
                .acquisitionAndProductionCostsBegin(m("10000.00"))
                .acquisitionAndProductionCostsEnd(m("10000.00"))
                .investmentSupport(m("0.00"))
                .assetLifeYear(4)
                .assetAddition(m("0.00"))
                .transfers(m("0.00"))
                .assetDisposal(m("0.00"))
                .bookValueBegin(m("10000.00"))
                .depreciationMethod("Линеен")
                .depreciationPercentage(m("25"))
                .depreciationForPeriod(m("208.33"))
                .appreciationForPeriod(m("0.00"))
                .addExtraordinaryDepreciation(SaftExtraordinaryDepreciation.none())
                .accumulatedDepreciation(m("208.33"))
                .bookValueEnd(m("9791.67")))
        .addValuationDap(
            new SaftValuationDap()
                .valuationClass("202")
                .categoryTaxDepreciable(2)
                .taxDepreciableValue(m("156"))
                .accruedTaxDepreciation(m("31.20"))
                .taxValueAsset(m("124.80"))
                .annualTaxDepreciationRate(m("20"))
                .monthChangeAssetValue("3")
                .monthSuspensionResumptionAccrual("6")
                .monthWriteOffAccounting("9")
                .monthWriteOffTax("10")
                .numberMonthsDepreciationDuring(10)
                .depreciationForPeriod(m("54"))
                .accumulatedDepreciation(m("73"))
                .taxValueEndPeriod(m("73")));
  }

  private static SaftAsset investmentProperty() {
    return new SaftAsset()
        .assetId("ДА000070")
        .accountId("223")
        .description("неамортизируем инвестиционен имот")
        .addSupplier(
            new SaftAssetCounterparty()
                .name("WoodMart Supply Co.")
                .id("02UK741852963")
                .postalAddress(
                    new SaftAddress()
                        .streetName("")
                        .number("")
                        .additionalAddressDetail("")
                        .building("")
                        .city("Stratford")
                        .postalCode("")
                        .region("")
                        .country("UK")
                        .addressType("StreetAddress")))
        .purchaseOrderDate(LocalDate.of(2021, 10, 1))
        .dateOfAcquisition(LocalDate.of(2021, 12, 20))
        .nonDepreciable(
            new SaftNonDepreciableAsset()
                .acquisitionAndProductionCostsBegin(m("100000"))
                .acquisitionAndProductionCostsEnd(m("100000"))
                .investmentSupport(m("0"))
                .bookValueBegin(m("100000"))
                .revaluationForPeriod(m("1000"))
                .bookValueEnd(m("101000")));
  }

  private static SaftAssetTransaction depreciation(
      String id, String supplier, String partyId, String gl) {
    return new SaftAssetTransaction()
        .assetTransactionId(id)
        .assetId("ДА000030")
        .assetTransactionType("30")
        .description("Амортизация")
        .assetTransactionDate(LocalDate.of(2022, 1, 31))
        .supplierCustomer(
            new SaftAssetCounterparty().name(supplier).id(partyId).postalAddress(address("", "BG")))
        .transactionId(gl)
        .acquisitionAndProductionCostsOnTransaction(m("0.00"))
        .bookValueOnTransaction(m("9791.67"))
        .assetTransactionAmount(m("208.33"));
  }

  private static SaftAuditFile sampleAsDto() {
    return BgSaftFixtures.minimalAnnual()
        .masterFilesAnnual(
            new SaftMasterFilesAnnual().addAsset(vwTransporter()).addAsset(investmentProperty()))
        .assetTransactions(
            new SaftAssetTransactions()
                .numberOfAssetTransactions(2L)
                .addAssetTransaction(
                    depreciation("35", "Mortimor Car Company", "02UK741852979", "48"))
                .addAssetTransaction(
                    depreciation("15", "WoodMart Supply Co.", "02UK741852963", "26")));
  }

  /** Годишен файл с един актив — останалото от минималния. */
  private static SaftAuditFile withAsset(SaftAsset asset) {
    return BgSaftFixtures.minimalAnnual()
        .masterFilesAnnual(new SaftMasterFilesAnnual().addAsset(asset));
  }

  private static SaftAsset vwWith(Consumer<SaftAsset> change) {
    SaftAsset a = vwTransporter();
    change.accept(a);
    return a;
  }

  private static String refusal(SaftAuditFile file) {
    return assertThrows(IllegalStateException.class, () -> bytes(file)).getMessage();
  }

  // ─────────────── сравнението с примера ───────────────

  private static Document parse(byte[] xml) throws Exception {
    DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
    f.setNamespaceAware(true);
    f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
  }

  /** Листата на елемента {@code name}: „път = стойност“ в реда на документа. */
  private static List<String> leaves(Document doc, String name) {
    Node root = doc.getElementsByTagNameNS(BgSaftXsd.NAMESPACE, name).item(0);
    assertNotNull(root, "няма " + name);
    List<String> out = new ArrayList<>();
    collect((Element) root, name, out);
    return out;
  }

  private static void collect(Element e, String path, List<String> out) {
    boolean leaf = true;
    for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
      if (n instanceof Element) {
        leaf = false;
        collect((Element) n, path + "/" + n.getLocalName(), out);
      }
    }
    if (leaf) {
      out.add(path + " = " + number(e.getTextContent()));
    }
  }

  /** Число → каноничен вид (156 и 156.00 са едно); всичко друго — както е. */
  private static String number(String value) {
    if (value.matches("-?\\d+(\\.\\d+)?")) {
      BigDecimal d = new BigDecimal(value).stripTrailingZeros();
      return d.scale() < 0 ? d.setScale(0).toPlainString() : d.toPlainString();
    }
    return value;
  }

  private static byte[] sample() throws Exception {
    try (InputStream in = BgSaftAnnualAssetsWriterTest.class.getResourceAsStream(SAMPLE)) {
      assertNotNull(in, "еталонът " + SAMPLE + " не е в classpath-а");
      return in.readAllBytes();
    }
  }

  // ─────────────── тестовете ───────────────

  @Test
  @DisplayName(
      "Примерът на НАП, сглобен като DTO: Assets и AssetTransactions са същите и минават 1.0.3")
  void примерът_на_нап() throws Exception {
    SaftAuditFile file = sampleAsDto();
    assertEquals(List.of(), validate103(file));
    Document ours = parse(bytes(file));
    Document theirs = parse(sample());
    assertEquals(leaves(theirs, "Assets"), leaves(ours, "Assets"));
    assertEquals(leaves(theirs, "AssetTransactions"), leaves(ours, "AssetTransactions"));
  }

  @Test
  @DisplayName("Неамортизируем актив: 1.0.3 го приема, 1.0.2 (до 31.10.2026) — не")
  void неамортизируемият_е_от_1_0_3() {
    SaftAuditFile file = withAsset(investmentProperty());
    assertEquals(List.of(), validate103(file));
    assertFalse(
        BgSaftFixtures.validate(bytes(file), BgSaftXsd.V1_0_2).isEmpty(),
        "1.0.2 няма NonDepreciableAssetDetails");
    String xml = text(file);
    assertFalse(xml.contains("<nsSAFT:Valuations>"), "без Valuations");
    assertFalse(xml.contains("StartUpDate"), "незадължителен — и не е зададен");
  }

  @Test
  @DisplayName(
      "Оценката е choice: Valuations и NonDepreciableAssetDetails заедно или нито едното — отказ")
  void оценката_е_точно_едната() {
    String both =
        refusal(withAsset(vwWith(a -> a.nonDepreciable(investmentProperty().getNonDepreciable()))));
    assertTrue(both.contains("ДА000030") && both.contains("NonDepreciableAssetDetails"), both);

    SaftAsset none = investmentProperty().nonDepreciable(null);
    String neither = refusal(withAsset(none));
    assertTrue(neither.contains("ДА000070") && neither.contains("Valuations"), neither);
  }

  @Test
  @DisplayName("Valuations иска и двата плана — поне една ValuationSAP и поне една ValuationDAP")
  void двата_плана() {
    SaftAsset noSap = vwTransporter();
    noSap.getValuationsSap().clear();
    assertTrue(refusal(withAsset(noSap)).contains("ValuationSAP"));

    SaftAsset noDap = vwTransporter();
    noDap.getValuationsDap().clear();
    assertTrue(refusal(withAsset(noDap)).contains("ValuationDAP"));
  }

  @Test
  @DisplayName("StartUpDate е задължителна при Valuations (книгата, MF.A.8)")
  void датата_на_въвеждане() {
    String r = refusal(withAsset(vwWith(a -> a.startUpDate(null))));
    assertTrue(r.contains("StartUpDate") && r.contains("MF.A.8"), r);
  }

  @Test
  @DisplayName("Полезният живот: точно едно от AssetLifeYear и AssetLifeMonth")
  void полезният_живот() {
    SaftAsset both = vwTransporter();
    both.getValuationsSap().get(0).assetLifeMonth(48);
    assertTrue(refusal(withAsset(both)).contains("AssetLife"));

    SaftAsset none = vwTransporter();
    none.getValuationsSap().get(0).assetLifeYear(null);
    assertTrue(refusal(withAsset(none)).contains("AssetLife"));

    SaftAsset months = vwTransporter();
    months.getValuationsSap().get(0).assetLifeYear(null).assetLifeMonth(48);
    SaftAuditFile file = withAsset(months);
    assertEquals(List.of(), validate103(file));
    assertTrue(text(file).contains("<nsSAFT:AssetLifeMonth>48</nsSAFT:AssetLifeMonth>"));
  }

  @Test
  @DisplayName("Категорията по ЗКПО е 0–7; месеците на амортизация не са отрицателни")
  void категорията_и_месеците() {
    SaftAsset eight = vwTransporter();
    eight.getValuationsDap().get(0).categoryTaxDepreciable(8);
    assertTrue(refusal(withAsset(eight)).contains("CategoryTaxDepreciable"));

    SaftAsset negative = vwTransporter();
    negative.getValuationsDap().get(0).categoryTaxDepreciable(-1);
    assertTrue(refusal(withAsset(negative)).contains("CategoryTaxDepreciable"));

    SaftAsset months = vwTransporter();
    months.getValuationsDap().get(0).numberMonthsDepreciationDuring(-1);
    assertTrue(refusal(withAsset(months)).contains("NumberMonthsDepreciationDuring"));
  }

  @Test
  @DisplayName("Без доставчик и без извънредна амортизация — отказ с пътя; сума с 3 знака — отказ")
  void задължителните_списъци_и_сумите() {
    SaftAsset noSupplier = vwTransporter();
    noSupplier.getSuppliers().clear();
    assertTrue(refusal(withAsset(noSupplier)).contains("AssetSupplier"));

    SaftAsset noExtraordinary = vwTransporter();
    noExtraordinary.getValuationsSap().get(0).getExtraordinaryDepreciations().clear();
    assertTrue(refusal(withAsset(noExtraordinary)).contains("ExtraordinaryDepreciationForPeriod"));

    SaftAsset threeDecimals = vwTransporter();
    threeDecimals.getValuationsSap().get(0).depreciationForPeriod(m("208.333"));
    assertTrue(refusal(withAsset(threeDecimals)).contains("DepreciationForPeriod"));
  }

  @Test
  @DisplayName("Актив само в ДАП или само в САП: нулевият план минава 1.0.3 и е само нули")
  void нулевите_планове() throws Exception {
    SaftAsset onlyTax =
        vwWith(
            a -> {
              a.getValuationsSap().clear();
              a.addValuationSap(SaftValuationSap.zero());
            });
    SaftAsset onlyBook =
        vwWith(
            a -> {
              a.getValuationsDap().clear();
              a.addValuationDap(SaftValuationDap.zero());
            });
    for (SaftAsset asset : List.of(onlyTax, onlyBook)) {
      assertEquals(List.of(), validate103(withAsset(asset)));
    }
    for (String leaf : leaves(parse(bytes(withAsset(onlyTax))), "ValuationSAP")) {
      assertTrue(leaf.endsWith(" = 0"), leaf);
    }
    for (String leaf : leaves(parse(bytes(withAsset(onlyBook))), "ValuationDAP")) {
      assertTrue(leaf.endsWith(" = 0"), leaf);
    }
  }

  @Test
  @DisplayName(
      "Транзакция без незадължителните: нито Description, нито доставчик, нито SD.AT.15–16")
  void незадължителните_на_транзакцията() {
    SaftAuditFile file =
        BgSaftFixtures.minimalAnnual()
            .assetTransactions(
                new SaftAssetTransactions()
                    .numberOfAssetTransactions(1L)
                    .addAssetTransaction(
                        new SaftAssetTransaction()
                            .assetTransactionId("FAL-1")
                            .assetId("ДА000030")
                            .assetTransactionType("30")
                            .assetTransactionDate(LocalDate.of(2026, 1, 31))
                            .transactionId("48")
                            .bookValueOnTransaction(m("9791.67"))
                            .assetTransactionAmount(m("208.33"))));
    String xml = text(file);
    assertEquals(List.of(), validate103(file));
    assertFalse(xml.contains("<nsSAFT:Description>"));
    assertFalse(xml.contains("AssetSupplierCustomer"));
    assertFalse(xml.contains("AssetValuationType"));
    assertFalse(xml.contains("AcquisitionAndProductionCostsOnTransaction"));

    SaftAuditFile typed =
        BgSaftFixtures.minimalAnnual().assetTransactions(file.getAssetTransactions());
    typed.getAssetTransactions().getAssetTransactions().get(0).assetValuationType("SAP");
    String withType = text(typed);
    assertEquals(List.of(), validate103(typed));
    assertTrue(
        withType.contains(
            "<nsSAFT:AssetTransactionValuation><nsSAFT:AssetValuationType>SAP</nsSAFT:AssetValuationType>"
                + "<nsSAFT:BookValueOnTransaction>"),
        "SD.AT.15 е първи в оценката");
  }
}
