package com.axelor.apps.bglocalization.service.saft.adapter;

import com.axelor.apps.bglocalization.service.saft.dto.SaftAddress;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAmount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAsset;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetCounterparty;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetTransactions;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftBankAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftCompanyHeader;
import com.axelor.apps.bglocalization.service.saft.dto.SaftContact;
import com.axelor.apps.bglocalization.service.saft.dto.SaftExtraordinaryDepreciation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftFileKind;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGeneralLedger;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGlAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftHeader;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFiles;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFilesAnnual;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFilesOnDemand;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMovementOfGoods;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMovementType;
import com.axelor.apps.bglocalization.service.saft.dto.SaftNonDepreciableAsset;
import com.axelor.apps.bglocalization.service.saft.dto.SaftOwnership;
import com.axelor.apps.bglocalization.service.saft.dto.SaftParty;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPayment;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPaymentLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPaymentSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPersonName;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPhysicalStockEntry;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProduct;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProductTax;
import com.axelor.apps.bglocalization.service.saft.dto.SaftSourceDocuments;
import com.axelor.apps.bglocalization.service.saft.dto.SaftStockMovement;
import com.axelor.apps.bglocalization.service.saft.dto.SaftStockMovementLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxCodeDetail;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxRegistration;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxTableEntry;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftUom;
import com.axelor.apps.bglocalization.service.saft.dto.SaftValuationDap;
import com.axelor.apps.bglocalization.service.saft.dto.SaftValuationSap;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

/**
 * ПИШЕ ОДИТНИЯ ФАЙЛ SAF-T ОТ {@link SaftAuditFile} — поточно, елемент по елемент, в реда на
 * схемата.
 *
 * <p><b>⚠️ ВИДЪТ ИЗБИРА КОРЕНА, НЕ СЕКЦИИТЕ.</b> Месечният, годишният и файлът при поискване са три
 * клона на един choice; {@link SaftAuditFile#getKind()} казва кой. Попълнена секция от друг вид
 * спира ПРЕДИ първия байт — иначе би изчезнала мълчаливо, а файлът би изглеждал пълен. Writer-ът не
 * решава нищо по съдържанието: M/A/D в S.H.11, клонът на SelectionCriteria и нулевите структури
 * идват готови.
 *
 * <p><b>⚠️ ЧИСТ StAX, БЕЗ XJC.</b> ADR 0001 предвиждаше генерирани типове; допълнението към него
 * обяснява отказа: без промяна по билда, без санирано копие на схемата и без риска {@code
 * xmlns:doc}. Цената е, че РЕДЪТ на елементите е ръчен — и точно затова единственият съдия е XSD
 * проверката над байтовете ({@code BgXsdValidator}), не този клас.
 *
 * <p><b>⚠️ {@code null} И {@code ""} СА ДВЕ РАЗЛИЧНИ НЕЩА.</b> Празен низ в задължителен елемент е
 * ИЗЯВЛЕНИЕ — „няма установена стойност“, с предупреждение от извличането (десетте данъка „да се
 * потвърди“, непотвърденият SoftwareID). Схемата го приема и writer-ът го пише. {@code null} в
 * задължителен елемент е ЗАБРАВЕНО поле и writer-ът спира с пътя му — иначе забравеното би
 * изглеждало точно като изявеното. Без изключения: „0“/„00000000“ на {@code ProductCommodityCode}
 * решава {@code BgSaftProductsAssembler}, не writer-ът.
 *
 * <p><b>⚠️ ПРАЗЕН ЗАДЪЛЖИТЕЛЕН СПИСЪК СПИРА ТУК, НЕ В XSD-ТО.</b> Същото съобщение от валидатора би
 * казало само „очаква се елемент“; оттук казва кой.
 *
 * <p><b>⚠️ СУМИТЕ НЕ СЕ ЗАКРЪГЛЯТ.</b> {@code SAFmonetaryType} допуска два знака след запетаята;
 * сума с повече отказва с пътя си, вместо writer-ът тихо да реши закръглянето вместо закона (ЗВЕРБ
 * чл. 13 е работа на извличането).
 */
public final class BgSaftXmlWriter {

  private BgSaftXmlWriter() {}

  /** Целият файл като UTF-8 байтове — това, което се валидира и се подава. */
  public static byte[] toBytes(SaftAuditFile file) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    write(file, out);
    return out.toByteArray();
  }

  /** Пише файла в потока. Потокът НЕ се затваря. */
  public static void write(SaftAuditFile file, OutputStream out) {
    need(file, "AuditFile");
    try {
      XMLStreamWriter xml =
          XMLOutputFactory.newInstance().createXMLStreamWriter(out, BgSaftXsd.ENCODING);
      new BgSaftXmlWriter.Doc(xml).auditFile(file);
      xml.flush();
    } catch (XMLStreamException e) {
      throw new IllegalStateException("SAF-T: XML потокът се провали: " + e.getMessage(), e);
    }
  }

  // ═══════════════════ документът ═══════════════════

  private static final class Doc {

    private final XMLStreamWriter xml;

    private Doc(XMLStreamWriter xml) {
      this.xml = xml;
    }

    void auditFile(SaftAuditFile file) throws XMLStreamException {
      SaftFileKind kind = need(file.getKind(), "AuditFile — видът на файла");
      onlyOwnSections(file, kind);
      xml.writeStartDocument(BgSaftXsd.ENCODING, "1.0");
      xml.setPrefix(BgSaftXsd.NAMESPACE_PREFIX, BgSaftXsd.NAMESPACE);
      xml.writeStartElement(
          BgSaftXsd.NAMESPACE_PREFIX, BgSaftXsd.ROOT_ELEMENT, BgSaftXsd.NAMESPACE);
      xml.writeNamespace(BgSaftXsd.NAMESPACE_PREFIX, BgSaftXsd.NAMESPACE);
      header(need(file.getHeader(), "Header"));
      switch (kind) {
        case ANNUAL:
          masterFilesAnnual(need(file.getMasterFilesAnnual(), "MasterFilesAnnual"));
          sourceDocumentsAnnual(
              need(file.getAssetTransactions(), "SourceDocumentsAnnual/AssetTransactions"));
          break;
        case ON_DEMAND:
          masterFilesOnDemand(need(file.getMasterFilesOnDemand(), "MasterFilesOnDemand"));
          sourceDocumentsOnDemand(
              need(file.getMovementOfGoods(), "SourceDocumentsOnDemand/MovementOfGoods"));
          break;
        default:
          masterFiles(need(file.getMasterFiles(), "MasterFilesMonthly"));
          generalLedger(need(file.getGeneralLedger(), "GeneralLedgerEntries"));
          sourceDocuments(need(file.getSourceDocuments(), "SourceDocumentsMonthly"));
      }
      xml.writeEndElement();
      xml.writeEndDocument();
    }

    // ─────────────── Header ───────────────

    private void header(SaftHeader h) throws XMLStreamException {
      start("Header");
      text("AuditFileVersion", h.getAuditFileVersion(), "Header/AuditFileVersion");
      opt("AuditFileCountry", h.getAuditFileCountry());
      date("AuditFileDateCreated", h.getAuditFileDateCreated(), "Header/AuditFileDateCreated");
      text("SoftwareCompanyName", h.getSoftwareCompanyName(), "Header/SoftwareCompanyName");
      text("SoftwareID", h.getSoftwareId(), "Header/SoftwareID");
      text("SoftwareVersion", h.getSoftwareVersion(), "Header/SoftwareVersion");
      company(need(h.getCompany(), "Header/Company"));
      ownership(need(h.getOwnership(), "Header/Ownership"));
      text("DefaultCurrencyCode", h.getDefaultCurrencyCode(), "Header/DefaultCurrencyCode");
      selectionCriteria(h);
      opt("HeaderComment", h.getHeaderComment());
      text("TaxAccountingBasis", h.getTaxAccountingBasis(), "Header/TaxAccountingBasis");
      opt("TaxEntity", h.getTaxEntity());
      end();
    }

    /**
     * S.H.10 — ТОЧНО ЕДИН клон на choice-а: датите S.SC.3–4 или периодите S.SC.5–8. Елемент от
     * клона, който липсва, спира с пътя си, както всеки задължителен.
     */
    private void selectionCriteria(SaftHeader h) throws XMLStreamException {
      boolean dates = h.getSelectionStartDate() != null || h.getSelectionEndDate() != null;
      boolean periods =
          h.getPeriodStart() != null
              || h.getPeriodStartYear() != null
              || h.getPeriodEnd() != null
              || h.getPeriodEndYear() != null;
      if (dates == periods) {
        throw new IllegalStateException(
            "SAF-T: SelectionCriteria иска точно един клон — датите (S.SC.3–4) или периодите"
                + " (S.SC.5–8), а "
                + (dates ? "са попълнени и двата" : "няма нито един"));
      }
      start("SelectionCriteria");
      if (dates) {
        date(
            "SelectionStartDate",
            h.getSelectionStartDate(),
            "SelectionCriteria/SelectionStartDate");
        date("SelectionEndDate", h.getSelectionEndDate(), "SelectionCriteria/SelectionEndDate");
      } else {
        integer("PeriodStart", h.getPeriodStart(), "SelectionCriteria/PeriodStart");
        integer("PeriodStartYear", h.getPeriodStartYear(), "SelectionCriteria/PeriodStartYear");
        integer("PeriodEnd", h.getPeriodEnd(), "SelectionCriteria/PeriodEnd");
        integer("PeriodEndYear", h.getPeriodEndYear(), "SelectionCriteria/PeriodEndYear");
      }
      end();
    }

    private void company(SaftCompanyHeader c) throws XMLStreamException {
      start("Company");
      text("RegistrationNumber", c.getRegistrationNumber(), "Company/RegistrationNumber");
      text("Name", c.getName(), "Company/Name");
      for (SaftAddress a : nonEmpty(c.getAddresses(), "Company/Address")) {
        address("Address", a);
      }
      for (SaftContact contact : nonEmpty(c.getContacts(), "Company/Contact")) {
        contact(contact);
      }
      for (SaftTaxRegistration t : c.getTaxRegistrations()) {
        taxRegistration(t);
      }
      for (SaftBankAccount b : nonEmpty(c.getBankAccounts(), "Company/BankAccount")) {
        start("BankAccount");
        text("IBANNumber", b.getIbanNumber(), "BankAccount/IBANNumber");
        end();
      }
      end();
    }

    private void contact(SaftContact c) throws XMLStreamException {
      start("Contact");
      SaftPersonName p = need(c.getContactPerson(), "Contact/ContactPerson");
      start("ContactPerson");
      text("FirstName", p.getFirstName(), "ContactPerson/FirstName");
      text("LastName", p.getLastName(), "ContactPerson/LastName");
      for (String title : nonEmpty(p.getOtherTitles(), "ContactPerson/OtherTitles")) {
        text("OtherTitles", title, "ContactPerson/OtherTitles");
      }
      end();
      for (String phone : c.getTelephones()) {
        text("Telephone", phone, "Contact/Telephone");
      }
      for (String email : c.getEmails()) {
        text("Email", email, "Contact/Email");
      }
      end();
    }

    private void taxRegistration(SaftTaxRegistration t) throws XMLStreamException {
      start("TaxRegistration");
      text(
          "TaxRegistrationNumber",
          t.getTaxRegistrationNumber(),
          "TaxRegistration/TaxRegistrationNumber");
      opt("TaxType", t.getTaxType());
      opt("TaxNumber", t.getTaxNumber());
      opt("TaxAuthority", t.getTaxAuthority());
      if (t.getTaxVerificationDate() != null) {
        date("TaxVerificationDate", t.getTaxVerificationDate(), "TaxVerificationDate");
      }
      end();
    }

    private void ownership(SaftOwnership o) throws XMLStreamException {
      start("Ownership");
      text("IsPartOfGroup", o.getIsPartOfGroup(), "Ownership/IsPartOfGroup");
      list("UltimateOwnerNameCyrillicBG", o.getUltimateOwnerNameCyrillicBg());
      list("UltimateOwnerUICBG", o.getUltimateOwnerUicBg());
      list("UltimateOwnerNameCyrillicForeign", o.getUltimateOwnerNameCyrillicForeign());
      list("UltimateOwnerNameLatinForeign", o.getUltimateOwnerNameLatinForeign());
      list("CountryForeign", o.getCountryForeign());
      end();
    }

    private void list(String name, Collection<String> values) throws XMLStreamException {
      for (String value : nonEmpty(values, "Ownership/" + name)) {
        text(name, value, "Ownership/" + name);
      }
    }

    private void address(String element, SaftAddress a) throws XMLStreamException {
      start(element);
      opt("StreetName", a.getStreetName());
      opt("Number", a.getNumber());
      opt("AdditionalAddressDetail", a.getAdditionalAddressDetail());
      opt("Building", a.getBuilding());
      text("City", a.getCity(), element + "/City");
      opt("PostalCode", a.getPostalCode());
      opt("Region", a.getRegion());
      text("Country", a.getCountry(), element + "/Country");
      opt("AddressType", a.getAddressType());
      end();
    }

    // ─────────────── MasterFiles ───────────────

    private void masterFiles(SaftMasterFiles m) throws XMLStreamException {
      start("MasterFilesMonthly");
      start("GeneralLedgerAccounts");
      for (SaftGlAccount a : nonEmpty(m.getGlAccounts(), "GeneralLedgerAccounts/Account")) {
        start("Account");
        text("AccountID", a.getAccountId(), "Account/AccountID");
        text("AccountDescription", a.getAccountDescription(), "Account/AccountDescription");
        text("TaxpayerAccountID", a.getTaxpayerAccountId(), "Account/TaxpayerAccountID");
        text("AccountType", a.getAccountType(), "Account/AccountType");
        balance("Opening", a.getOpeningBalance(), "Account/OpeningBalance");
        balance("Closing", a.getClosingBalance(), "Account/ClosingBalance");
        end();
      }
      end();
      parties("Customers", "Customer", "CustomerID", m.getCustomers());
      parties("Suppliers", "Supplier", "SupplierID", m.getSuppliers());
      taxTable(m.getTaxTableEntries());
      uomTable(m.getUoms());
      products(m.getProducts());
      end();
    }

    private void uomTable(List<SaftUom> uoms) throws XMLStreamException {
      start("UOMTable");
      for (SaftUom u : nonEmpty(uoms, "UOMTable/UOMTableEntry")) {
        start("UOMTableEntry");
        text("UnitOfMeasure", u.getUnitOfMeasure(), "UOMTableEntry/UnitOfMeasure");
        text("Description", u.getDescription(), "UOMTableEntry/Description");
        end();
      }
      end();
    }

    private void parties(
        String wrapper, String element, String idElement, Collection<SaftParty> all)
        throws XMLStreamException {
      start(wrapper);
      for (SaftParty p : nonEmpty(all, wrapper + "/" + element)) {
        start(element);
        start("CompanyStructure");
        text("RegistrationNumber", p.getRegistrationNumber(), element + "/RegistrationNumber");
        text("Name", p.getName(), element + "/Name");
        for (SaftAddress a : nonEmpty(p.getAddresses(), element + "/Address")) {
          address("Address", a);
        }
        for (SaftTaxRegistration t : p.getTaxRegistrations()) {
          taxRegistration(t);
        }
        text("RelatedParty", p.getRelatedParty(), element + "/RelatedParty");
        end();
        text(idElement, p.getPartyId(), element + "/" + idElement);
        text("AccountID", p.getAccountId(), element + "/AccountID");
        balance("Opening", p.getOpeningBalance(), element + "/OpeningBalance");
        balance("Closing", p.getClosingBalance(), element + "/ClosingBalance");
        end();
      }
      end();
    }

    private void taxTable(List<SaftTaxTableEntry> entries) throws XMLStreamException {
      start("TaxTable");
      for (SaftTaxTableEntry e : nonEmpty(entries, "TaxTable/TaxTableEntry")) {
        start("TaxTableEntry");
        text("TaxType", e.getTaxType(), "TaxTableEntry/TaxType");
        text("Description", e.getDescription(), "TaxTableEntry/Description");
        for (SaftTaxCodeDetail d :
            nonEmpty(e.getTaxCodeDetails(), "TaxTableEntry/TaxCodeDetails")) {
          start("TaxCodeDetails");
          text("TaxCode", d.getTaxCode(), "TaxCodeDetails/TaxCode");
          if (d.getEffectiveDate() != null) {
            date("EffectiveDate", d.getEffectiveDate(), "TaxCodeDetails/EffectiveDate");
          }
          if (d.getExpirationDate() != null) {
            date("ExpirationDate", d.getExpirationDate(), "TaxCodeDetails/ExpirationDate");
          }
          opt("Description", d.getDescription());
          if (d.getTaxPercentage() != null) {
            decimal("TaxPercentage", d.getTaxPercentage(), "TaxCodeDetails/TaxPercentage");
          }
          if (d.getBaseRate() != null) {
            money("BaseRate", d.getBaseRate(), "TaxCodeDetails/BaseRate");
          }
          text("Country", d.getCountry(), "TaxCodeDetails/Country");
          end();
        }
        end();
      }
      end();
    }

    private void products(List<SaftProduct> products) throws XMLStreamException {
      start("Products");
      for (SaftProduct p : nonEmpty(products, "Products/Product")) {
        start("Product");
        text("ProductCode", p.getProductCode(), "Product/ProductCode");
        opt("GoodsServicesID", p.getGoodsServicesId());
        text("Description", p.getDescription(), "Product/Description");
        text("ProductCommodityCode", p.getProductCommodityCode(), "Product/ProductCommodityCode");
        text("UOMBase", p.getUomBase(), "Product/UOMBase");
        text("UOMStandard", p.getUomStandard(), "Product/UOMStandard");
        decimal(
            "UOMToUOMBaseConversionFactor",
            p.getUomToUomBaseConversionFactor(),
            "Product/UOMToUOMBaseConversionFactor");
        for (SaftProductTax t : p.getTaxes()) {
          start("Tax");
          text("TaxType", t.getTaxType(), "Product/Tax/TaxType");
          text("TaxCode", t.getTaxCode(), "Product/Tax/TaxCode");
          end();
        }
        end();
      }
      end();
    }

    // ─────────────── годишният файл ───────────────

    /**
     * MasterFilesAnnual: Assets с Asset 0..n. Без активи StAX затваря празния елемент като {@code
     * <nsSAFT:Assets/>} — същите байтове като преди пистата на активите. Owners е незадължителна и
     * не се пише.
     */
    private void masterFilesAnnual(SaftMasterFilesAnnual m) throws XMLStreamException {
      start("MasterFilesAnnual");
      start("Assets");
      for (SaftAsset a : need(m.getAssets(), "MasterFilesAnnual/Assets")) {
        asset(a);
      }
      end();
      end();
    }

    /**
     * Asset (MF.A.1–9.1). Оценката е choice: Valuations (двата плана) или
     * NonDepreciableAssetDetails — точно едното. Пътят в отказите носи AssetID, иначе „кой актив“
     * остава за отгатване.
     */
    private void asset(SaftAsset a) throws XMLStreamException {
      need(a, "Assets/Asset");
      String path = "Asset " + need(a.getAssetId(), "Asset/AssetID");
      boolean valuations =
          !need(a.getValuationsSap(), path + "/Valuations/ValuationSAP").isEmpty()
              || !need(a.getValuationsDap(), path + "/Valuations/ValuationDAP").isEmpty();
      if (valuations == (a.getNonDepreciable() != null)) {
        throw new IllegalStateException(
            "SAF-T: "
                + path
                + (valuations
                    ? " носи и Valuations, и NonDepreciableAssetDetails"
                    : " няма нито Valuations, нито NonDepreciableAssetDetails")
                + " — схемата иска точно едното (MF.A.9 / MF.A.9.1)");
      }
      start("Asset");
      text("AssetID", a.getAssetId(), path + "/AssetID");
      text("AccountID", a.getAccountId(), path + "/AccountID");
      text("Description", a.getDescription(), path + "/Description");
      for (SaftAssetCounterparty s : nonEmpty(a.getSuppliers(), path + "/AssetSupplier")) {
        counterparty("AssetSupplier", "Supplier", s, path + "/AssetSupplier");
      }
      if (a.getPurchaseOrderDate() != null) {
        date("PurchaseOrderDate", a.getPurchaseOrderDate(), path + "/PurchaseOrderDate");
      }
      date("DateOfAcquisition", a.getDateOfAcquisition(), path + "/DateOfAcquisition");
      if (valuations) {
        // Схемата 1.0.3 я допуска празна, книгата (MF.A.8) — не, когато има Valuations.
        date(
            "StartUpDate",
            a.getStartUpDate(),
            path + "/StartUpDate (книгата, MF.A.8: задължителна при Valuations)");
        start("Valuations");
        for (SaftValuationSap v :
            nonEmpty(a.getValuationsSap(), path + "/Valuations/ValuationSAP")) {
          valuationSap(v, path + "/ValuationSAP");
        }
        for (SaftValuationDap v :
            nonEmpty(a.getValuationsDap(), path + "/Valuations/ValuationDAP")) {
          valuationDap(v, path + "/ValuationDAP");
        }
        end();
      } else {
        if (a.getStartUpDate() != null) {
          date("StartUpDate", a.getStartUpDate(), path + "/StartUpDate");
        }
        nonDepreciable(a.getNonDepreciable(), path + "/NonDepreciableAssetDetails");
      }
      end();
    }

    /**
     * Името, номерът и адресът: SupplierName/ID в Asset, SupplierCustomerName/ID в транзакцията.
     */
    private void counterparty(String element, String prefix, SaftAssetCounterparty c, String path)
        throws XMLStreamException {
      need(c, path);
      start(element);
      text(prefix + "Name", c.getName(), path + "/" + prefix + "Name");
      text(prefix + "ID", c.getId(), path + "/" + prefix + "ID");
      address("PostalAddress", need(c.getPostalAddress(), path + "/PostalAddress"));
      end();
    }

    private void valuationSap(SaftValuationSap v, String path) throws XMLStreamException {
      need(v, path);
      start("ValuationSAP");
      text("ValuationClass", v.getValuationClass(), path + "/ValuationClass");
      money(
          "AcquisitionAndProductionCostsBegin",
          v.getAcquisitionAndProductionCostsBegin(),
          path + "/AcquisitionAndProductionCostsBegin");
      money(
          "AcquisitionAndProductionCostsEnd",
          v.getAcquisitionAndProductionCostsEnd(),
          path + "/AcquisitionAndProductionCostsEnd");
      money("InvestmentSupport", v.getInvestmentSupport(), path + "/InvestmentSupport");
      if ((v.getAssetLifeYear() == null) == (v.getAssetLifeMonth() == null)) {
        throw new IllegalStateException(
            "SAF-T: "
                + path
                + " — AssetLifeYear и AssetLifeMonth: схемата иска точно едното (MF.A.19/20)");
      }
      if (v.getAssetLifeYear() != null) {
        integer("AssetLifeYear", v.getAssetLifeYear(), path + "/AssetLifeYear");
      } else {
        integer("AssetLifeMonth", v.getAssetLifeMonth(), path + "/AssetLifeMonth");
      }
      money("AssetAddition", v.getAssetAddition(), path + "/AssetAddition");
      money("Transfers", v.getTransfers(), path + "/Transfers");
      money("AssetDisposal", v.getAssetDisposal(), path + "/AssetDisposal");
      money("BookValueBegin", v.getBookValueBegin(), path + "/BookValueBegin");
      text("DepreciationMethod", v.getDepreciationMethod(), path + "/DepreciationMethod");
      decimal(
          "DepreciationPercentage",
          v.getDepreciationPercentage(),
          path + "/DepreciationPercentage");
      money("DepreciationForPeriod", v.getDepreciationForPeriod(), path + "/DepreciationForPeriod");
      money("AppreciationForPeriod", v.getAppreciationForPeriod(), path + "/AppreciationForPeriod");
      start("ExtraordinaryDepreciationsForPeriod");
      String extraordinary = path + "/ExtraordinaryDepreciationForPeriod";
      for (SaftExtraordinaryDepreciation e :
          nonEmpty(v.getExtraordinaryDepreciations(), extraordinary)) {
        need(e, extraordinary);
        start("ExtraordinaryDepreciationForPeriod");
        text(
            "ExtraordinaryDepreciationMethod",
            e.getMethod(),
            extraordinary + "/ExtraordinaryDepreciationMethod");
        money(
            "ExtraordinaryDepreciationAmountForPeriod",
            e.getAmount(),
            extraordinary + "/ExtraordinaryDepreciationAmountForPeriod");
        end();
      }
      end();
      money(
          "AccumulatedDepreciation",
          v.getAccumulatedDepreciation(),
          path + "/AccumulatedDepreciation");
      money("BookValueEnd", v.getBookValueEnd(), path + "/BookValueEnd");
      end();
    }

    private void valuationDap(SaftValuationDap v, String path) throws XMLStreamException {
      need(v, path);
      start("ValuationDAP");
      text("ValuationClass", v.getValuationClass(), path + "/ValuationClass");
      int category = need(v.getCategoryTaxDepreciable(), path + "/CategoryTaxDepreciable");
      if (category < 0 || category > 7) {
        throw new IllegalStateException(
            "SAF-T: "
                + path
                + "/CategoryTaxDepreciable е 0–7 (0 — не е в ДАП, 1–7 — категории I–VII), не "
                + category);
      }
      element("CategoryTaxDepreciable", Integer.toString(category));
      money("TaxDepreciableValue", v.getTaxDepreciableValue(), path + "/TaxDepreciableValue");
      money(
          "AccruedTaxDepreciation",
          v.getAccruedTaxDepreciation(),
          path + "/AccruedTaxDepreciation");
      money("TaxValueAsset", v.getTaxValueAsset(), path + "/TaxValueAsset");
      decimal(
          "AnnualTaxDepreciationRate",
          v.getAnnualTaxDepreciationRate(),
          path + "/AnnualTaxDepreciationRate");
      text("MonthChangeAssetValue", v.getMonthChangeAssetValue(), path + "/MonthChangeAssetValue");
      text(
          "MonthSuspensionResumptionAccrual",
          v.getMonthSuspensionResumptionAccrual(),
          path + "/MonthSuspensionResumptionAccrual");
      text(
          "MonthWriteOffAccounting",
          v.getMonthWriteOffAccounting(),
          path + "/MonthWriteOffAccounting");
      text("MonthWriteOffTax", v.getMonthWriteOffTax(), path + "/MonthWriteOffTax");
      integer(
          "NumberMonthsDepreciationDuring",
          v.getNumberMonthsDepreciationDuring(),
          path + "/NumberMonthsDepreciationDuring");
      money("DepreciationForPeriod", v.getDepreciationForPeriod(), path + "/DepreciationForPeriod");
      money(
          "AccumulatedDepreciation",
          v.getAccumulatedDepreciation(),
          path + "/AccumulatedDepreciation");
      money("TaxValueEndPeriod", v.getTaxValueEndPeriod(), path + "/TaxValueEndPeriod");
      end();
    }

    private void nonDepreciable(SaftNonDepreciableAsset n, String path) throws XMLStreamException {
      need(n, path);
      start("NonDepreciableAssetDetails");
      money(
          "NDAAcquisitionAndProductionCostsBegin",
          n.getAcquisitionAndProductionCostsBegin(),
          path + "/NDAAcquisitionAndProductionCostsBegin");
      money(
          "NDAAcquisitionAndProductionCostsEnd",
          n.getAcquisitionAndProductionCostsEnd(),
          path + "/NDAAcquisitionAndProductionCostsEnd");
      money("NDAInvestmentSupport", n.getInvestmentSupport(), path + "/NDAInvestmentSupport");
      money("NDABookValueBegin", n.getBookValueBegin(), path + "/NDABookValueBegin");
      money("RevaluationForPeriod", n.getRevaluationForPeriod(), path + "/RevaluationForPeriod");
      money("NDABookValueEnd", n.getBookValueEnd(), path + "/NDABookValueEnd");
      end();
    }

    private void sourceDocumentsAnnual(SaftAssetTransactions a) throws XMLStreamException {
      start("SourceDocumentsAnnual");
      start("AssetTransactions");
      count(
          "NumberOfAssetTransactions",
          a.getNumberOfAssetTransactions(),
          "AssetTransactions/NumberOfAssetTransactions");
      for (SaftAssetTransaction t :
          nonEmpty(a.getAssetTransactions(), "AssetTransactions/AssetTransaction")) {
        start("AssetTransaction");
        text(
            "AssetTransactionID", t.getAssetTransactionId(), "AssetTransaction/AssetTransactionID");
        text("AssetID", t.getAssetId(), "AssetTransaction/AssetID");
        text(
            "AssetTransactionType",
            t.getAssetTransactionType(),
            "AssetTransaction/AssetTransactionType");
        opt("Description", t.getDescription());
        date(
            "AssetTransactionDate",
            t.getAssetTransactionDate(),
            "AssetTransaction/AssetTransactionDate");
        if (t.getSupplierCustomer() != null) {
          counterparty(
              "AssetSupplierCustomer",
              "SupplierCustomer",
              t.getSupplierCustomer(),
              "AssetTransaction/AssetSupplierCustomer");
        }
        text("TransactionID", t.getTransactionId(), "AssetTransaction/TransactionID");
        start("AssetTransactionValuations");
        start("AssetTransactionValuation");
        opt("AssetValuationType", t.getAssetValuationType());
        if (t.getAcquisitionAndProductionCostsOnTransaction() != null) {
          money(
              "AcquisitionAndProductionCostsOnTransaction",
              t.getAcquisitionAndProductionCostsOnTransaction(),
              "AssetTransactionValuation/AcquisitionAndProductionCostsOnTransaction");
        }
        money(
            "BookValueOnTransaction",
            t.getBookValueOnTransaction(),
            "AssetTransactionValuation/BookValueOnTransaction");
        money(
            "AssetTransactionAmount",
            t.getAssetTransactionAmount(),
            "AssetTransactionValuation/AssetTransactionAmount");
        end();
        end();
        end();
      }
      end();
      end();
    }

    // ─────────────── файлът при поискване ───────────────

    /** TaxTable е незадължителна и празна не се пише (Прил. 3 т. I.1 и т. III.3). */
    private void masterFilesOnDemand(SaftMasterFilesOnDemand m) throws XMLStreamException {
      start("MasterFilesOnDemand");
      if (!need(m.getTaxTableEntries(), "MasterFilesOnDemand/TaxTable").isEmpty()) {
        taxTable(m.getTaxTableEntries());
      }
      start("MovementTypeTable");
      for (SaftMovementType t :
          nonEmpty(m.getMovementTypes(), "MovementTypeTable/MovementTypeTableEntry")) {
        start("MovementTypeTableEntry");
        text("MovementType", t.getMovementType(), "MovementTypeTableEntry/MovementType");
        text("Description", t.getDescription(), "MovementTypeTableEntry/Description");
        end();
      }
      end();
      uomTable(m.getUoms());
      products(m.getProducts());
      start("PhysicalStock");
      for (SaftPhysicalStockEntry e :
          nonEmpty(m.getPhysicalStock(), "PhysicalStock/PhysicalStockEntry")) {
        physicalStockEntry(e);
      }
      end();
      end();
    }

    private void physicalStockEntry(SaftPhysicalStockEntry e) throws XMLStreamException {
      start("PhysicalStockEntry");
      text("WarehouseID", e.getWarehouseId(), "PhysicalStockEntry/WarehouseID");
      text("ProductCode", e.getProductCode(), "PhysicalStockEntry/ProductCode");
      text("ProductType", e.getProductType(), "PhysicalStockEntry/ProductType");
      text(
          "StockAccountCommodityCode",
          e.getStockAccountCommodityCode(),
          "PhysicalStockEntry/StockAccountCommodityCode");
      text("OwnerID", e.getOwnerId(), "PhysicalStockEntry/OwnerID");
      text("UOMPhysicalStock", e.getUomPhysicalStock(), "PhysicalStockEntry/UOMPhysicalStock");
      decimal(
          "UOMToUOMBaseConversionFactor",
          e.getUomToUomBaseConversionFactor(),
          "PhysicalStockEntry/UOMToUOMBaseConversionFactor");
      money("UnitPriceBegin", e.getUnitPriceBegin(), "PhysicalStockEntry/UnitPriceBegin");
      money("UnitPriceEnd", e.getUnitPriceEnd(), "PhysicalStockEntry/UnitPriceEnd");
      quantity(
          "OpeningStockQuantity",
          e.getOpeningStockQuantity(),
          "PhysicalStockEntry/OpeningStockQuantity");
      money("OpeningStockValue", e.getOpeningStockValue(), "PhysicalStockEntry/OpeningStockValue");
      quantity(
          "ClosingStockQuantity",
          e.getClosingStockQuantity(),
          "PhysicalStockEntry/ClosingStockQuantity");
      money("ClosingStockValue", e.getClosingStockValue(), "PhysicalStockEntry/ClosingStockValue");
      end();
    }

    private void sourceDocumentsOnDemand(SaftMovementOfGoods g) throws XMLStreamException {
      start("SourceDocumentsOnDemand");
      start("MovementOfGoods");
      count(
          "NumberOfMovementLines",
          g.getNumberOfMovementLines(),
          "MovementOfGoods/NumberOfMovementLines");
      quantity(
          "TotalQuantityReceived",
          g.getTotalQuantityReceived(),
          "MovementOfGoods/TotalQuantityReceived");
      quantity(
          "TotalQuantityIssued", g.getTotalQuantityIssued(), "MovementOfGoods/TotalQuantityIssued");
      for (SaftStockMovement m : nonEmpty(g.getStockMovements(), "MovementOfGoods/StockMovement")) {
        stockMovement(m);
      }
      end();
      end();
    }

    private void stockMovement(SaftStockMovement m) throws XMLStreamException {
      start("StockMovement");
      text("MovementReference", m.getMovementReference(), "StockMovement/MovementReference");
      date("MovementDate", m.getMovementDate(), "StockMovement/MovementDate");
      text("MovementType", m.getMovementType(), "StockMovement/MovementType");
      text("SourceID", m.getSourceId(), "StockMovement/SourceID");
      text("SystemID", m.getSystemId(), "StockMovement/SystemID");
      start("DocumentReference");
      text("DocumentType", m.getDocumentType(), "DocumentReference/DocumentType");
      text("DocumentNumber", m.getDocumentNumber(), "DocumentReference/DocumentNumber");
      end();
      for (SaftStockMovementLine l : nonEmpty(m.getLines(), "StockMovement/StockMovementLine")) {
        start("StockMovementLine");
        text("LineNumber", l.getLineNumber(), "StockMovementLine/LineNumber");
        text("AccountID", l.getAccountId(), "StockMovementLine/AccountID");
        text("TransactionID", l.getTransactionId(), "StockMovementLine/TransactionID");
        text("CustomerID", l.getCustomerId(), "StockMovementLine/CustomerID");
        text("SupplierID", l.getSupplierId(), "StockMovementLine/SupplierID");
        text("ProductCode", l.getProductCode(), "StockMovementLine/ProductCode");
        quantity("Quantity", l.getQuantity(), "StockMovementLine/Quantity");
        text("UnitOfMeasure", l.getUnitOfMeasure(), "StockMovementLine/UnitOfMeasure");
        decimal(
            "UOMToUOMPhysicalStockConversionFactor",
            l.getUomToUomPhysicalStockConversionFactor(),
            "StockMovementLine/UOMToUOMPhysicalStockConversionFactor");
        money("BookValue", l.getBookValue(), "StockMovementLine/BookValue");
        text("MovementSubType", l.getMovementSubType(), "StockMovementLine/MovementSubType");
        end();
      }
      end();
    }

    // ─────────────── GeneralLedgerEntries ───────────────

    private void generalLedger(SaftGeneralLedger g) throws XMLStreamException {
      start("GeneralLedgerEntries");
      count("NumberOfEntries", g.getNumberOfEntries(), "GeneralLedgerEntries/NumberOfEntries");
      money("TotalDebit", g.getTotalDebit(), "GeneralLedgerEntries/TotalDebit");
      money("TotalCredit", g.getTotalCredit(), "GeneralLedgerEntries/TotalCredit");
      for (SaftJournal j : nonEmpty(g.getJournals(), "GeneralLedgerEntries/Journal")) {
        start("Journal");
        opt("JournalID", j.getJournalId());
        opt("Description", j.getDescription());
        opt("Type", j.getType());
        for (SaftTransaction t : nonEmpty(j.getTransactions(), "Journal/Transaction")) {
          transaction(t);
        }
        end();
      }
      end();
    }

    private void transaction(SaftTransaction t) throws XMLStreamException {
      start("Transaction");
      text("TransactionID", t.getTransactionId(), "Transaction/TransactionID");
      integer("Period", t.getPeriod(), "Transaction/Period");
      integer("PeriodYear", t.getPeriodYear(), "Transaction/PeriodYear");
      date("TransactionDate", t.getTransactionDate(), "Transaction/TransactionDate");
      text("Description", t.getDescription(), "Transaction/Description");
      date("SystemEntryDate", t.getSystemEntryDate(), "Transaction/SystemEntryDate");
      date("GLPostingDate", t.getGlPostingDate(), "Transaction/GLPostingDate");
      text("CustomerID", t.getCustomerId(), "Transaction/CustomerID");
      text("SupplierID", t.getSupplierId(), "Transaction/SupplierID");
      for (SaftTransactionLine l : nonEmpty(t.getLines(), "Transaction/TransactionLine")) {
        start("TransactionLine");
        text("RecordID", l.getRecordId(), "TransactionLine/RecordID");
        text("AccountID", l.getAccountId(), "TransactionLine/AccountID");
        text("TaxpayerAccountID", l.getTaxpayerAccountId(), "TransactionLine/TaxpayerAccountID");
        opt("SourceDocumentID", l.getSourceDocumentId());
        text("CustomerID", l.getCustomerId(), "TransactionLine/CustomerID");
        text("SupplierID", l.getSupplierId(), "TransactionLine/SupplierID");
        text("Description", l.getDescription(), "TransactionLine/Description");
        boolean debit = need(l.getDebit(), "TransactionLine/DebitAmount|CreditAmount");
        amount(debit ? "DebitAmount" : "CreditAmount", l.getAmount(), "TransactionLine/Amount");
        for (SaftTaxInformation ti :
            nonEmpty(l.getTaxInformation(), "TransactionLine/TaxInformation")) {
          taxInformation("TaxInformation", ti);
        }
        end();
      }
      end();
    }

    // ─────────────── SourceDocuments ───────────────

    private void sourceDocuments(SaftSourceDocuments s) throws XMLStreamException {
      start("SourceDocumentsMonthly");
      invoices("SalesInvoices", true, need(s.getSalesInvoices(), "SalesInvoices"));
      payments(need(s.getPayments(), "Payments"));
      invoices("PurchaseInvoices", false, need(s.getPurchaseInvoices(), "PurchaseInvoices"));
      end();
    }

    private void invoices(String wrapper, boolean sales, SaftInvoiceSection section)
        throws XMLStreamException {
      start(wrapper);
      count("NumberOfEntries", section.getNumberOfEntries(), wrapper + "/NumberOfEntries");
      money("TotalDebit", section.getTotalDebit(), wrapper + "/TotalDebit");
      money("TotalCredit", section.getTotalCredit(), wrapper + "/TotalCredit");
      for (SaftInvoice i : nonEmpty(section.getInvoices(), wrapper + "/Invoice")) {
        invoice(sales, i);
      }
      end();
    }

    private void invoice(boolean sales, SaftInvoice i) throws XMLStreamException {
      start("Invoice");
      text("InvoiceNo", i.getInvoiceNo(), "Invoice/InvoiceNo");
      boolean customer =
          i.getPartyRole() == null ? sales : i.getPartyRole() == SaftInvoice.PartyRole.CUSTOMER;
      start(customer ? "CustomerInfo" : "SupplierInfo");
      text(customer ? "CustomerID" : "SupplierID", i.getPartyId(), "Invoice/PartyID");
      text("Name", i.getPartyName(), "Invoice/PartyName");
      address("BillingAddress", need(i.getBillingAddress(), "Invoice/BillingAddress"));
      end();
      text("AccountID", i.getAccountId(), "Invoice/AccountID");
      if (i.getPeriod() != null) {
        integer("Period", i.getPeriod(), "Invoice/Period");
      }
      if (i.getPeriodYear() != null) {
        integer("PeriodYear", i.getPeriodYear(), "Invoice/PeriodYear");
      }
      date("InvoiceDate", i.getInvoiceDate(), "Invoice/InvoiceDate");
      text("InvoiceType", i.getInvoiceType(), "Invoice/InvoiceType");
      text("SelfBillingIndicator", i.getSelfBillingIndicator(), "Invoice/SelfBillingIndicator");
      text("TransactionID", i.getTransactionId(), "Invoice/TransactionID");
      for (SaftInvoiceLine l : nonEmpty(i.getLines(), "Invoice/InvoiceLine")) {
        invoiceLine(l);
      }
      if (i.getNetTotal() != null && i.getGrossTotal() != null) {
        start("InvoiceDocumentTotals");
        for (SaftTaxInformation ti : i.getTaxInformationTotals()) {
          taxInformation("TaxInformationTotals", ti);
        }
        money("NetTotal", i.getNetTotal(), "InvoiceDocumentTotals/NetTotal");
        money("GrossTotal", i.getGrossTotal(), "InvoiceDocumentTotals/GrossTotal");
        end();
      }
      end();
    }

    private void invoiceLine(SaftInvoiceLine l) throws XMLStreamException {
      start("InvoiceLine");
      opt("LineNumber", l.getLineNumber());
      text("AccountID", l.getAccountId(), "InvoiceLine/AccountID");
      opt("GoodsServicesID", l.getGoodsServicesId());
      text("ProductCode", l.getProductCode(), "InvoiceLine/ProductCode");
      opt("ProductDescription", l.getProductDescription());
      quantity("Quantity", l.getQuantity(), "InvoiceLine/Quantity");
      opt("InvoiceUOM", l.getInvoiceUom());
      if (l.getUomToUomBaseConversionFactor() != null) {
        decimal(
            "UOMToUOMBaseConversionFactor",
            l.getUomToUomBaseConversionFactor(),
            "InvoiceLine/UOMToUOMBaseConversionFactor");
      }
      money("UnitPrice", l.getUnitPrice(), "InvoiceLine/UnitPrice");
      date("TaxPointDate", l.getTaxPointDate(), "InvoiceLine/TaxPointDate");
      text("Description", l.getDescription(), "InvoiceLine/Description");
      amount("InvoiceLineAmount", l.getInvoiceLineAmount(), "InvoiceLine/InvoiceLineAmount");
      text("DebitCreditIndicator", l.getDebitCreditIndicator(), "InvoiceLine/DebitCreditIndicator");
      for (SaftTaxInformation ti : nonEmpty(l.getTaxInformation(), "InvoiceLine/TaxInformation")) {
        taxInformation("TaxInformation", ti);
      }
      end();
    }

    private void payments(SaftPaymentSection section) throws XMLStreamException {
      start("Payments");
      count("NumberOfEntries", section.getNumberOfEntries(), "Payments/NumberOfEntries");
      money("TotalDebit", section.getTotalDebit(), "Payments/TotalDebit");
      money("TotalCredit", section.getTotalCredit(), "Payments/TotalCredit");
      for (SaftPayment p : nonEmpty(section.getPayments(), "Payments/Payment")) {
        payment(p);
      }
      end();
    }

    private void payment(SaftPayment p) throws XMLStreamException {
      start("Payment");
      text("PaymentRefNo", p.getPaymentRefNo(), "Payment/PaymentRefNo");
      if (p.getPeriod() != null) {
        integer("Period", p.getPeriod(), "Payment/Period");
      }
      if (p.getPeriodYear() != null) {
        integer("PeriodYear", p.getPeriodYear(), "Payment/PeriodYear");
      }
      text("TransactionID", p.getTransactionId(), "Payment/TransactionID");
      date("TransactionDate", p.getTransactionDate(), "Payment/TransactionDate");
      text("PaymentMethod", p.getPaymentMethod(), "Payment/PaymentMethod");
      text("Description", p.getDescription(), "Payment/Description");
      for (SaftPaymentLine l : nonEmpty(p.getLines(), "Payment/PaymentLine")) {
        start("PaymentLine");
        opt("LineNumber", l.getLineNumber());
        opt("SourceDocumentID", l.getSourceDocumentId());
        text("AccountID", l.getAccountId(), "PaymentLine/AccountID");
        text("CustomerID", l.getCustomerId(), "PaymentLine/CustomerID");
        text("SupplierID", l.getSupplierId(), "PaymentLine/SupplierID");
        if (l.getTaxPointDate() != null) {
          date("TaxPointDate", l.getTaxPointDate(), "PaymentLine/TaxPointDate");
        }
        opt("Description", l.getDescription());
        text(
            "DebitCreditIndicator",
            l.getDebitCreditIndicator(),
            "PaymentLine/DebitCreditIndicator");
        amount("PaymentLineAmount", l.getPaymentLineAmount(), "PaymentLine/PaymentLineAmount");
        for (SaftTaxInformation ti : l.getTaxInformation()) {
          taxInformation("TaxInformation", ti);
        }
        end();
      }
      if (p.getPaymentMechanism() != null) {
        start("PaymentSettlement");
        element("PaymentMechanism", p.getPaymentMechanism());
        end();
      }
      if (p.getGrossTotal() != null) {
        start("PaymentDocumentTotals");
        money("GrossTotal", p.getGrossTotal(), "PaymentDocumentTotals/GrossTotal");
        end();
      }
      end();
    }

    // ─────────────── общите структури ───────────────

    private void taxInformation(String element, SaftTaxInformation t) throws XMLStreamException {
      start(element);
      text("TaxType", t.getTaxType(), element + "/TaxType");
      text("TaxCode", t.getTaxCode(), element + "/TaxCode");
      if (t.getTaxPercentage() != null) {
        decimal("TaxPercentage", t.getTaxPercentage(), element + "/TaxPercentage");
      }
      if (t.getTaxBase() != null) {
        decimal("TaxBase", t.getTaxBase(), element + "/TaxBase");
      }
      amount("TaxAmount", t.getTaxAmount(), element + "/TaxAmount");
      end();
    }

    private void amount(String element, SaftAmount a, String path) throws XMLStreamException {
      need(a, path);
      start(element);
      money("Amount", a.getAmount(), path + "/Amount");
      text("CurrencyCode", a.getCurrencyCode(), path + "/CurrencyCode");
      money("CurrencyAmount", a.getCurrencyAmount(), path + "/CurrencyAmount");
      if (a.getExchangeRate() != null) {
        scaled("ExchangeRate", a.getExchangeRate(), 4, path + "/ExchangeRate");
      }
      end();
    }

    /** Салдо със знак → Debit (≥ 0) или Credit (< 0) — схемата иска точно едното. */
    private void balance(String prefix, BigDecimal signed, String path) throws XMLStreamException {
      need(signed, path);
      if (signed.signum() >= 0) {
        money(prefix + "DebitBalance", signed, path);
      } else {
        money(prefix + "CreditBalance", signed.negate(), path);
      }
    }

    // ─────────────── листата ───────────────

    private void start(String name) throws XMLStreamException {
      xml.writeStartElement(BgSaftXsd.NAMESPACE_PREFIX, name, BgSaftXsd.NAMESPACE);
    }

    private void end() throws XMLStreamException {
      xml.writeEndElement();
    }

    private void element(String name, String value) throws XMLStreamException {
      start(name);
      xml.writeCharacters(value);
      end();
    }

    /** Задължителен низ: {@code null} спира, {@code ""} се пише. */
    private void text(String name, String value, String path) throws XMLStreamException {
      element(name, need(value, path));
    }

    /** Незадължителен низ: {@code null} не се пише изобщо. */
    private void opt(String name, String value) throws XMLStreamException {
      if (value != null) {
        element(name, value);
      }
    }

    private void date(String name, LocalDate value, String path) throws XMLStreamException {
      element(name, need(value, path).toString());
    }

    private void integer(String name, Integer value, String path) throws XMLStreamException {
      int v = need(value, path);
      if (v < 0) {
        throw new IllegalStateException("SAF-T: " + path + " не може да е отрицателно: " + v);
      }
      element(name, Integer.toString(v));
    }

    private void count(String name, Long value, String path) throws XMLStreamException {
      long v = need(value, path);
      if (v < 0) {
        throw new IllegalStateException("SAF-T: " + path + " не може да е отрицателно: " + v);
      }
      element(name, Long.toString(v));
    }

    private void money(String name, BigDecimal value, String path) throws XMLStreamException {
      scaled(name, value, 2, path);
    }

    private void quantity(String name, BigDecimal value, String path) throws XMLStreamException {
      BigDecimal v = need(value, path).stripTrailingZeros();
      if (v.scale() > 6) {
        throw new IllegalStateException(
            "SAF-T: " + path + " има повече от 6 знака след запетаята: " + value.toPlainString());
      }
      element(name, v.scale() < 0 ? v.setScale(0).toPlainString() : v.toPlainString());
    }

    private void decimal(String name, BigDecimal value, String path) throws XMLStreamException {
      element(name, need(value, path).toPlainString());
    }

    private void scaled(String name, BigDecimal value, int scale, String path)
        throws XMLStreamException {
      need(value, path);
      BigDecimal v;
      try {
        v = value.setScale(scale);
      } catch (ArithmeticException e) {
        throw new IllegalStateException(
            "SAF-T: "
                + path
                + " има повече от "
                + scale
                + " знака след запетаята и не се закръгля тук: "
                + value.toPlainString(),
            e);
      }
      element(name, v.toPlainString());
    }
  }

  // ═══════════════════ проверки ═══════════════════

  /**
   * Секция от друг вид спира ПРЕДИ първия байт — схемата допуска само един клон на корена, а
   * пропусната тихо, тя би оставила файл, който изглежда пълен.
   */
  private static void onlyOwnSections(SaftAuditFile file, SaftFileKind kind) {
    List<String> foreign = new ArrayList<>();
    if (kind != SaftFileKind.MONTHLY) {
      foreign(foreign, file.getMasterFiles(), "MasterFilesMonthly");
      foreign(foreign, file.getGeneralLedger(), "GeneralLedgerEntries");
      foreign(foreign, file.getSourceDocuments(), "SourceDocumentsMonthly");
    }
    if (kind != SaftFileKind.ANNUAL) {
      foreign(foreign, file.getMasterFilesAnnual(), "MasterFilesAnnual");
      foreign(foreign, file.getAssetTransactions(), "SourceDocumentsAnnual");
    }
    if (kind != SaftFileKind.ON_DEMAND) {
      foreign(foreign, file.getMasterFilesOnDemand(), "MasterFilesOnDemand");
      foreign(foreign, file.getMovementOfGoods(), "SourceDocumentsOnDemand");
    }
    if (!foreign.isEmpty()) {
      throw new IllegalStateException(
          "SAF-T: файлът е "
              + kind
              + " и не носи "
              + String.join(", ", foreign)
              + " — схемата допуска един клон на корена");
    }
  }

  private static void foreign(List<String> found, Object section, String name) {
    if (section != null) {
      found.add(name);
    }
  }

  private static <T> T need(T value, String path) {
    if (value == null) {
      throw new IllegalStateException("SAF-T: липсва задължителното " + path);
    }
    return value;
  }

  private static <T> Iterable<T> nonEmpty(Iterable<T> values, String path) {
    Iterator<T> it = need(values, path).iterator();
    if (!it.hasNext()) {
      throw new IllegalStateException("SAF-T: " + path + " иска поне един елемент");
    }
    // Итераторът вече е взет — подава се той, а не нов обход: Iterable от извличането може да
    // позволява САМО един обход (курсор към базата).
    return () -> it;
  }

  private static <T> Collection<T> nonEmpty(Collection<T> values, String path) {
    if (need(values, path).isEmpty()) {
      throw new IllegalStateException("SAF-T: " + path + " иска поне един елемент");
    }
    return values;
  }
}
