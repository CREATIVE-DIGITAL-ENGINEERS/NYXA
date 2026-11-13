package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetTransactions;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftFileKind;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFilesOnDemand;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxCodeDetail;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxTableEntry;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГОДИШНИЯТ ФАЙЛ И ФАЙЛЪТ ПРИ ПОИСКВАНЕ ОТ WRITER-А МИНАВАТ ЗАКОВАНАТА СХЕМА — и падат, когато
 * трябва.
 *
 * <p><b>⚠️ ЗЕЛЕНОТО ТУК ЗНАЧИ САМО СТРУКТУРА.</b> Както {@link BgSaftMinimalFileXsdTest}: числата
 * са измислени, а секциите — минимални, защото пълнят ги пистите на активите и на склада.
 *
 * <p><b>⚠️ ОТРИЦАТЕЛНИТЕ СВИДЕТЕЛИ СА ЗА ВАЛИДАТОРА, НЕ ЗА WRITER-А.</b> Writer-ът отказва да
 * напише чужда секция или празна задължителна таблица; затова развалените файлове се правят върху
 * байтовете — иначе „схемата отказва“ би било непроверено твърдение.
 */
class BgSaftAnnualOnDemandFileTest {

  private static String text(SaftAuditFile file) {
    return new String(BgSaftXmlWriter.toBytes(file), StandardCharsets.UTF_8);
  }

  private static List<BgDeclarationFileFinding> validate(String xml) {
    return BgSaftFixtures.validate(xml.getBytes(StandardCharsets.UTF_8));
  }

  /** Елементът {@code nsSAFT:name} с цялото му съдържание — първото срещане. */
  private static String element(String xml, String name) {
    int from = xml.indexOf("<nsSAFT:" + name + ">");
    String close = "</nsSAFT:" + name + ">";
    int to = xml.indexOf(close, from);
    assertTrue(from >= 0 && to > from, "няма " + name);
    return xml.substring(from, to + close.length());
  }

  // ─────────────── годишният файл ───────────────

  @Test
  @DisplayName(
      "Минималният годишен файл минава схемата: 1–12, „A“, празно Assets, нулева структура")
  void минималният_годишен_минава() {
    String xml = text(BgSaftFixtures.minimalAnnual());
    assertEquals(List.of(), validate(xml));
    assertTrue(xml.contains("<nsSAFT:PeriodStart>1</nsSAFT:PeriodStart>"));
    assertTrue(xml.contains("<nsSAFT:PeriodEnd>12</nsSAFT:PeriodEnd>"));
    assertFalse(xml.contains("SelectionStartDate"), "точно един клон на SelectionCriteria");
    assertTrue(xml.contains("<nsSAFT:HeaderComment>A</nsSAFT:HeaderComment>"), "латинско A");
    assertTrue(
        xml.contains("<nsSAFT:MasterFilesAnnual><nsSAFT:Assets/></nsSAFT:MasterFilesAnnual>"));
    assertTrue(
        xml.contains("<nsSAFT:NumberOfAssetTransactions>0</nsSAFT:NumberOfAssetTransactions>"));
    assertTrue(
        xml.contains("<nsSAFT:AssetTransactionDate>1900-01-01</nsSAFT:AssetTransactionDate>"));
    assertFalse(xml.contains("MasterFilesMonthly"));
    assertFalse(xml.contains("GeneralLedgerEntries"));
  }

  @Test
  @DisplayName("Годишен файл с истинска транзакция (амортизация, 30) също минава")
  void годишният_с_транзакция_минава() {
    SaftAuditFile file =
        BgSaftFixtures.minimalAnnual()
            .assetTransactions(
                new SaftAssetTransactions()
                    .numberOfAssetTransactions(1L)
                    .addAssetTransaction(
                        new SaftAssetTransaction()
                            .assetTransactionId("AT1")
                            .assetId("A1")
                            .assetTransactionType("30")
                            .assetTransactionDate(LocalDate.of(2026, 12, 31))
                            .transactionId("T9")
                            .bookValueOnTransaction(new BigDecimal("900.00"))
                            .assetTransactionAmount(new BigDecimal("100.00"))));
    assertEquals(List.of(), validate(text(file)));
  }

  @Test
  @DisplayName("Свидетел: годишен файл с месечна секция пада на схемата")
  void годишният_с_месечна_секция_пада() {
    String annual = text(BgSaftFixtures.minimalAnnual());
    String monthly = element(text(BgSaftFixtures.minimal()), "MasterFilesMonthly");
    String broken =
        annual.replace("<nsSAFT:MasterFilesAnnual>", monthly + "<nsSAFT:MasterFilesAnnual>");
    assertFalse(validate(broken).isEmpty(), "Header + MasterFilesMonthly + годишните секции");
  }

  @Test
  @DisplayName("Свидетел: брой 0 БЕЗ AssetTransaction (по книгата, SD.AT.2) пада на схемата")
  void нула_транзакции_без_структура_пада() {
    String xml = text(BgSaftFixtures.minimalAnnual());
    String broken = xml.replace(element(xml, "AssetTransaction"), "");
    assertFalse(validate(broken).isEmpty(), "затова е нулевата структура по QA IV.2.6");
  }

  @Test
  @DisplayName("Writer-ът отказва чужда секция, преди да е написал байт")
  void чуждата_секция_спира() {
    SaftAuditFile annual = BgSaftFixtures.minimalAnnual().masterFiles(BgSaftFixtures.masterFiles());
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(annual));
    assertTrue(e.getMessage().contains("MasterFilesMonthly"), e.getMessage());

    SaftAuditFile monthly =
        BgSaftFixtures.minimal().movementOfGoods(BgSaftZeroStructures.movementOfGoods());
    e = assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(monthly));
    assertTrue(e.getMessage().contains("SourceDocumentsOnDemand"), e.getMessage());
  }

  @Test
  @DisplayName("Годишен без AssetTransactions спира с пътя")
  void годишният_без_транзакции_спира() {
    SaftAuditFile file = BgSaftFixtures.minimalAnnual().assetTransactions(null);
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(file));
    assertTrue(e.getMessage().contains("AssetTransactions"), e.getMessage());
  }

  // ─────────────── файлът при поискване ───────────────

  @Test
  @DisplayName("Минималният файл при поискване минава: датите, „D“, без TaxTable")
  void минималният_при_поискване_минава() {
    String xml = text(BgSaftFixtures.minimalOnDemand());
    assertEquals(List.of(), validate(xml));
    assertTrue(xml.contains("<nsSAFT:SelectionStartDate>2026-07-01</nsSAFT:SelectionStartDate>"));
    assertTrue(xml.contains("<nsSAFT:SelectionEndDate>2026-07-15</nsSAFT:SelectionEndDate>"));
    assertFalse(xml.contains("PeriodStart"), "точно един клон на SelectionCriteria");
    assertTrue(xml.contains("<nsSAFT:HeaderComment>D</nsSAFT:HeaderComment>"));
    assertFalse(xml.contains("TaxTable"), "празна незадължителна таблица не се подава");
    assertTrue(
        element(xml, "MovementTypeTable")
            .contains(
                "<nsSAFT:MovementType>30</nsSAFT:MovementType>"
                    + "<nsSAFT:Description>Продажба </nsSAFT:Description>"),
        "видът на движението, с наименованието дословно");
    assertTrue(xml.contains("<nsSAFT:Quantity>-5</nsSAFT:Quantity>"), "излизането е с минус");
  }

  @Test
  @DisplayName("При поискване с TaxTable и с нулевата структура (всичките 20 вида) също минава")
  void при_поискване_с_данъци_и_нулева_структура_минава() {
    SaftAuditFile file = BgSaftFixtures.minimalOnDemand();
    file.getMasterFilesOnDemand()
        .addTaxTableEntry(
            new SaftTaxTableEntry()
                .taxType("100")
                .description("ДДС")
                .addTaxCodeDetail(new SaftTaxCodeDetail().taxCode("100211").country("BG")));
    assertEquals(List.of(), validate(text(file)));

    SaftMasterFilesOnDemand m = new SaftMasterFilesOnDemand();
    file.getMasterFilesOnDemand().getUoms().forEach(m::addUom);
    file.getMasterFilesOnDemand().getProducts().forEach(m::addProduct);
    file.getMasterFilesOnDemand().getPhysicalStock().forEach(m::addPhysicalStockEntry);
    BgSaftMovementTypeTableAssembler.assemble(
            BgSaftZeroStructures.movementOfGoods(), BgNraNomenclatures.movementTypes())
        .getEntries()
        .forEach(m::addMovementType);
    SaftAuditFile zero =
        BgSaftFixtures.minimalOnDemand()
            .masterFilesOnDemand(m)
            .movementOfGoods(BgSaftZeroStructures.movementOfGoods());
    String xml = text(zero);
    assertEquals(List.of(), validate(xml));
    assertEquals(20, xml.split("<nsSAFT:MovementTypeTableEntry>", -1).length - 1);
    assertTrue(xml.contains("<nsSAFT:NumberOfMovementLines>0</nsSAFT:NumberOfMovementLines>"));
  }

  @Test
  @DisplayName("Свидетел: файл при поискване без PhysicalStock пада на схемата")
  void при_поискване_без_задължителна_секция_пада() {
    String xml = text(BgSaftFixtures.minimalOnDemand());
    assertFalse(validate(xml.replace(element(xml, "PhysicalStock"), "")).isEmpty());
    assertFalse(validate(xml.replace(element(xml, "MovementTypeTable"), "")).isEmpty());
  }

  @Test
  @DisplayName("Свидетел: брой 0 БЕЗ StockMovement (по книгата, SD.MG.4) пада на схемата")
  void нула_движения_без_структура_пада() {
    String xml =
        text(
            BgSaftFixtures.minimalOnDemand()
                .movementOfGoods(BgSaftZeroStructures.movementOfGoods()));
    assertEquals(List.of(), validate(xml));
    assertFalse(validate(xml.replace(element(xml, "StockMovement"), "")).isEmpty());
  }

  @Test
  @DisplayName("Празна задължителна таблица при поискване спира в writer-а с пътя си")
  void празната_наличност_спира() {
    SaftAuditFile file = BgSaftFixtures.minimalOnDemand();
    file.getMasterFilesOnDemand().getPhysicalStock().clear();
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(file));
    assertTrue(e.getMessage().contains("PhysicalStock/PhysicalStockEntry"), e.getMessage());
  }

  // ─────────────── SelectionCriteria ───────────────

  @Test
  @DisplayName("SelectionCriteria: двата клона заедно или нито един спират writer-а")
  void точно_един_клон() {
    SaftAuditFile both = BgSaftFixtures.minimal();
    both.getHeader().selectionStartDate(BgSaftFixtures.DAY).selectionEndDate(BgSaftFixtures.DAY);
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(both));
    assertTrue(e.getMessage().contains("и двата"), e.getMessage());

    SaftAuditFile none = BgSaftFixtures.minimalOnDemand();
    none.getHeader().selectionStartDate(null).selectionEndDate(null);
    e = assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(none));
    assertTrue(e.getMessage().contains("нито един"), e.getMessage());

    SaftAuditFile half = BgSaftFixtures.minimalOnDemand();
    half.getHeader().selectionEndDate(null);
    e = assertThrows(IllegalStateException.class, () -> BgSaftXmlWriter.toBytes(half));
    assertTrue(e.getMessage().contains("SelectionEndDate"), e.getMessage());
  }

  @Test
  @DisplayName("Видът по подразбиране е месечният — месечните фикстури не го задават")
  void видът_по_подразбиране() {
    assertEquals(SaftFileKind.MONTHLY, new SaftAuditFile().getKind());
    assertEquals("M", SaftFileKind.MONTHLY.getHeaderComment());
    assertEquals("A", SaftFileKind.ANNUAL.getHeaderComment());
    assertEquals('A', SaftFileKind.ANNUAL.getHeaderComment().charAt(0), "U+0041, не U+0410");
    assertEquals("D", SaftFileKind.ON_DEMAND.getHeaderComment());
  }
}
