package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Записът на подразбирането върху актива — входът на чл. 79 ал. 3 ЗДДС.
 *
 * <p>Огледалото на {@link BgArt79AcquisitionTest}: там се проверява какво се ЧЕТЕ наживо, тук —
 * какво остава ЗАПИСАНО в колоните, защото годишният съветник пита базата, не Java.
 *
 * <p>Чисти функции върху обикновени bean-ове, тъй че без Guice и без база.
 */
class BgArt79AcquisitionDefaultsTest {

  private InvoiceLine purchaseLine(String exTax, String inTax, String creditType, int year) {
    InvoiceLine line = new InvoiceLine();
    line.setExTaxTotal(new BigDecimal(exTax));
    line.setInTaxTotal(new BigDecimal(inTax));
    line.setBgVatCreditType(creditType);
    Invoice invoice = new Invoice();
    invoice.setInvoiceDate(LocalDate.of(year, 3, 15));
    line.setInvoice(invoice);
    return line;
  }

  private FixedAsset assetFromInvoice(String creditType) {
    FixedAsset asset = new FixedAsset();
    asset.setInvoiceLine(purchaseLine("10000", "12000", creditType, 2026));
    return asset;
  }

  @Test
  @DisplayName("Актив от фактура — НДДС, годината и видът кредит се записват в празните колони")
  void пълни_празните_от_фактурата() {
    FixedAsset asset = assetFromInvoice("2");

    BgArt79Acquisition.applyInvoiceDefaults(asset);

    assertEquals(
        0,
        new BigDecimal("2000").compareTo(asset.getBgVatChargedOnAcquisition()),
        "12000 − 10000 е БРУТНИЯТ начислен данък");
    assertEquals(2026, asset.getBgVatCreditYear(), "годината на фактурата е подразбиране");
    assertEquals("PARTIAL", asset.getBgVatAcquisitionCreditTypeSelect(), "„2“ → частичен");
  }

  @Test
  @DisplayName("Въведеното от счетоводителя НЕ се презаписва — и при повторен запис")
  void въведеното_не_се_презаписва() {
    FixedAsset asset = assetFromInvoice("2");
    asset.setBgVatChargedOnAcquisition(new BigDecimal("1800.00"));
    asset.setBgVatCreditYear(2027);
    asset.setBgVatAcquisitionCreditTypeSelect("PROPORTIONAL");

    BgArt79Acquisition.applyInvoiceDefaults(asset);
    BgArt79Acquisition.applyInvoiceDefaults(asset);

    assertEquals(0, new BigDecimal("1800.00").compareTo(asset.getBgVatChargedOnAcquisition()));
    assertEquals(2027, asset.getBgVatCreditYear(), "чл. 72 ал. 1 мести годината законно");
    assertEquals(
        "PROPORTIONAL",
        asset.getBgVatAcquisitionCreditTypeSelect(),
        "буква „в“ се маркира на ръка и записът не бива да я сваля до „б“");
  }

  @Test
  @DisplayName("Класификацията, ПрНИД₀ и годината на ползване НЕ се гадаят от фактурата")
  void четирите_полета_без_източник_остават_празни() {
    FixedAsset asset = assetFromInvoice("1");

    BgArt79Acquisition.applyInvoiceDefaults(asset);

    assertNull(
        asset.getBgVatAssetKindSelect(),
        "точката на ал. 3 не е изводима — сгрешена, тя умножава данъка по четири");
    assertEquals(0, asset.getBgVatProportionNid0().signum(), "ПрНИД₀ няма източник в системата");
    assertEquals(0, asset.getBgVatFirstUseYear(), "фактическото използване е факт за имота");
  }

  @Test
  @DisplayName("Празен вид кредит на фактурата оставя полето ПРАЗНО, а не NONE")
  void празното_не_става_none() {
    FixedAsset asset = assetFromInvoice(null);

    BgArt79Acquisition.applyInvoiceDefaults(asset);

    assertNull(
        asset.getBgVatAcquisitionCreditTypeSelect(),
        "записано NONE е ТВЪРДЕНИЕ, че кредит не е ползван — и заковава корекцията на нула");
    assertEquals(
        BgArt79CorrectionRules.CreditType.NONE,
        BgArt79Acquisition.creditTypeOf(asset),
        "докато ЧЕТЕНЕТО пак приема „не знам“ за „няма кредит“ — двете не са едно и също");
  }

  @Test
  @DisplayName("Ръчно заведен актив (без фактурен ред) — не се пише нищо")
  void без_фактура_нищо_не_се_пише() {
    FixedAsset asset = new FixedAsset();

    BgArt79Acquisition.applyInvoiceDefaults(asset);

    assertEquals(0, asset.getBgVatChargedOnAcquisition().signum());
    assertEquals(0, asset.getBgVatCreditYear());
    assertNull(asset.getBgVatAcquisitionCreditTypeSelect());
  }

  @Test
  @DisplayName("К₀ се записва САМО при частичен кредит — чл. 79в т. 3 и т. 5 заковават другите")
  void к0_само_при_частичен() {
    BigDecimal coefficient = new BigDecimal("0.60");

    FixedAsset partial = new FixedAsset();
    partial.setBgVatAcquisitionCreditTypeSelect("PARTIAL");
    BgArt79Acquisition.applyCoefficientDefault(partial, coefficient);
    assertEquals(0, coefficient.compareTo(partial.getBgVatCoefficientK0()));

    for (String other : new String[] {"FULL", "NONE", "PROPORTIONAL"}) {
      FixedAsset asset = new FixedAsset();
      asset.setBgVatAcquisitionCreditTypeSelect(other);
      BgArt79Acquisition.applyCoefficientDefault(asset, coefficient);
      assertEquals(
          0,
          asset.getBgVatCoefficientK0().signum(),
          other + ": коефициентът по чл. 73 не се взема предвид, тъй че записът би бил шум");
    }

    FixedAsset undeclared = new FixedAsset();
    BgArt79Acquisition.applyCoefficientDefault(undeclared, coefficient);
    assertEquals(
        0,
        undeclared.getBgVatCoefficientK0().signum(),
        "празен вид кредит → К₀ няма как да е верен");
  }

  @Test
  @DisplayName("Въведеният К₀ не се презаписва, а липсващият коефициент не пише нула")
  void к0_не_се_презаписва() {
    FixedAsset asset = new FixedAsset();
    asset.setBgVatAcquisitionCreditTypeSelect("PARTIAL");
    asset.setBgVatCoefficientK0(new BigDecimal("0.85"));

    BgArt79Acquisition.applyCoefficientDefault(asset, new BigDecimal("0.60"));
    assertEquals(0, new BigDecimal("0.85").compareTo(asset.getBgVatCoefficientK0()));

    FixedAsset empty = new FixedAsset();
    empty.setBgVatAcquisitionCreditTypeSelect("PARTIAL");
    BgArt79Acquisition.applyCoefficientDefault(empty, null);
    BgArt79Acquisition.applyCoefficientDefault(empty, BigDecimal.ZERO);
    assertTrue(
        empty.getBgVatCoefficientK0().signum() == 0,
        "липсващ ред в „Коефициенти чл.73“ не бива да става К₀ = 0 — чл. 79в т. 4 го отказва");
  }
}
