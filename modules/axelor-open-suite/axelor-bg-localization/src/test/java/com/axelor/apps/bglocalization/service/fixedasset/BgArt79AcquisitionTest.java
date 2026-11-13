package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.AssetKind;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.CreditType;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.Input;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Сглобяването на входа за чл. 79 ал. 3 — активът бие фактурата, а липсата не се догажда.
 *
 * <p>Чисти функции върху обикновени bean-ове, тъй че без Guice и без база.
 */
class BgArt79AcquisitionTest {

  private static final LocalDate EVENT = LocalDate.of(2028, 6, 30);

  private FixedAsset asset() {
    FixedAsset asset = new FixedAsset();
    asset.setBgVatAssetKindSelect("GOODS");
    asset.setBgVatCreditYear(2026);
    asset.setBgVatChargedOnAcquisition(new BigDecimal("2000.00"));
    asset.setBgVatAcquisitionCreditTypeSelect("FULL");
    return asset;
  }

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

  @Test
  @DisplayName("Пълен вход от актива — фактурата не се чете")
  void вход_от_актива() {
    Input in = BgArt79Acquisition.inputFor(asset(), EVENT);

    assertEquals(AssetKind.GOODS, in.kind());
    assertEquals(CreditType.FULL, in.creditType());
    assertEquals(0, new BigDecimal("2000.00").compareTo(in.vatCharged()));
    assertEquals(2026, in.creditYear());
    assertEquals(2028, in.eventYear(), "годината на събитието идва от датата на извеждането");
  }

  @Test
  @DisplayName("Празните полета се допълват от покупната фактура — НДДС = с данък минус без данък")
  void подразбиране_от_фактурата() {
    FixedAsset asset = new FixedAsset();
    asset.setBgVatAssetKindSelect("GOODS");
    asset.setInvoiceLine(purchaseLine("10000", "12000", "2", 2026));

    Input in = BgArt79Acquisition.inputFor(asset, EVENT);

    assertEquals(0, new BigDecimal("2000").compareTo(in.vatCharged()), "12000 − 10000");
    assertEquals(2026, in.creditYear(), "годината на фактурата е подразбиране");
    assertEquals(CreditType.PARTIAL, in.creditType(), "„2“ на реда → частичен кредит");
  }

  @Test
  @DisplayName("Стойността върху актива БИЕ фактурата — срокът може законно да е друг (чл. 72)")
  void активът_бие_фактурата() {
    FixedAsset asset = asset();
    asset.setBgVatCreditYear(2027);
    asset.setInvoiceLine(purchaseLine("10000", "12000", "2", 2026));

    Input in = BgArt79Acquisition.inputFor(asset, EVENT);

    assertEquals(2027, in.creditYear(), "упражненото право, а не датата на фактурата");
    assertEquals(CreditType.FULL, in.creditType(), "видът от актива, не „2“ от реда");
    assertEquals(0, new BigDecimal("2000.00").compareTo(in.vatCharged()));
  }

  @Test
  @DisplayName("Без класификация или без година на кредита входът е null — не се догажда")
  void липсващите_факти_не_се_догаждат() {
    FixedAsset noKind = asset();
    noKind.setBgVatAssetKindSelect(null);
    assertNull(BgArt79Acquisition.inputFor(noKind, EVENT), "без точка на ал. 3 няма знаменател");

    FixedAsset noYear = asset();
    // ⚠️ Генерираният Integer getter връща 0 за незададено, не null — затова „няма година“ е 0.
    noYear.setBgVatCreditYear(0);
    assertNull(BgArt79Acquisition.inputFor(noYear, EVENT), "без година на кредита няма срок");
  }

  @Test
  @DisplayName("Нулевите десетични се четат като „не е въведено“, а не като 0 във формулата")
  void нулевите_десетични_са_липса() {
    FixedAsset asset = asset();
    asset.setBgVatAcquisitionCreditTypeSelect("PARTIAL");
    asset.setBgVatCoefficientK0(BigDecimal.ZERO);

    Input in = BgArt79Acquisition.inputFor(asset, EVENT);

    assertNull(in.k0(), "нулата на generated getter-а не бива да влиза като К₀ = 0");
    // И тогава чл. 79в т. 4 отказва сметката, вместо да я направи нула.
    org.junit.jupiter.api.Assertions.assertThrows(
        IllegalArgumentException.class, () -> BgArt79CorrectionRules.correction(in));
  }

  @Test
  @DisplayName("Пропорционалният НЕ се извежда от фактурата — „2“ значи частичен, нищо повече")
  void пропорционалният_не_се_извежда() {
    FixedAsset asset = new FixedAsset();
    asset.setBgVatAssetKindSelect("GOODS");
    asset.setInvoiceLine(purchaseLine("10000", "12000", "2", 2026));

    assertEquals(
        CreditType.PARTIAL,
        BgArt79Acquisition.inputFor(asset, EVENT).creditType(),
        "дневникът не различава чл. 71а/71б от частичния — догадка тук слага ПрНИД₀ без основание");
  }

  @Test
  @DisplayName("Без никакви данни за кредит — NONE, тоест няма какво да се връща")
  void без_данни_за_кредит() {
    FixedAsset asset = new FixedAsset();
    asset.setBgVatAssetKindSelect("GOODS");
    asset.setBgVatCreditYear(2026);

    assertEquals(CreditType.NONE, BgArt79Acquisition.inputFor(asset, EVENT).creditType());
  }
}
