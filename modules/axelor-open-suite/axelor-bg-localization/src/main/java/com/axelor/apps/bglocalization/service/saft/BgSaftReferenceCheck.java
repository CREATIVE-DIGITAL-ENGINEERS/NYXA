package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGeneralLedger;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGlAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFiles;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPayment;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPaymentLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPaymentSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProduct;
import com.axelor.apps.bglocalization.service.saft.dto.SaftSourceDocuments;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxCodeDetail;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxTableEntry;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftUom;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ВСЕКИ ID, РЕФЕРИРАН В ДРУГИТЕ СЕКЦИИ, Е В MasterFiles — ПРОВЕРКАТА НАД ГОТОВИЯ ФАЙЛ.
 *
 * <p><b>Източникът.</b> QA IV.2.4 (с. 96): НАП отказва файл, чийто „GL.28 CustomerID … GL.29
 * SupplierID … не фигурира в секция 2. MasterFiles, елемент MF.C.3/MF.S.3“. QA с. 34 и 36 (MF.C.9):
 * „ако в други секции … са подадени данни за клиент/доставчик, същите данни следва да се подадат и
 * в секция MasterFiles“; с. 38 — същото за TaxTable (MF.TT.1) и UOMTable (MF.UOM.1). Книгата,
 * GL.19/GL.20/GL.28/GL.29, колона M: идентификаторите на контрагентите са „както са дефинирани и
 * подадени в секцията Master Files“.
 *
 * <p><b>⚠️ НАД ФАЙЛА, НЕ НАД ИЗВОРИТЕ.</b> Assembler-ите строят секциите поотделно; тук се гледа
 * това, което НАП ще види — така и бъдещ assembler, който забрави таблиците, пада тук (G6a, решение
 * от 14.10.2026). Петте вида ID:
 *
 * <ul>
 *   <li>CustomerID / SupplierID в GL, Payments и фактурите (вкл. протоколите) → Customers /
 *       Suppliers;
 *   <li>TaxType + TaxCode в GL и фактурите → TaxTable;
 *   <li>ProductCode във фактурите → Products;
 *   <li>AccountID в GL, Payments и фактурите → GeneralLedgerAccounts;
 *   <li>UOMBase / UOMStandard на продуктите и InvoiceUOM → UOMTable.
 * </ul>
 *
 * <p><b>⚠️ ИЗКЛЮЧЕНИЯТА СА ДВЕ И СА ИЗРИЧНИ.</b> ID на подаващия („10“ + ЕИК) не е в MasterFiles
 * (решение 10 от 05.10.2026; QA с. 33 — при обобщена информация се подават „идентификационните
 * данни на лицето, подаващо файла“, а в Customers/Suppliers „не следва да се подава информация“ за
 * тях). „0“ — „няма такъв“ при контрагентите (GL.19) и „без продукт“ при ProductCode (QA с. 33) —
 * не е запис. Нулевите структури (QA IV.2.6) се пропускат цели: AccountID 998 и „0“ там са по
 * образеца на НАП. Празен AccountID вече е ГРЕШКА в assembler-а и тук не се повтаря.
 *
 * <p><b>⚠️ СЕКЦИИТЕ СЕ ОБХОЖДАТ ВТОРИ ПЪТ.</b> Днес журналите, фактурите и плащанията са списъци.
 * Ако станат еднократни потоци (ADR 0001, допълнението от G1), тази проверка трябва да мине по пътя
 * на writer-а, а не преди него.
 */
public final class BgSaftReferenceCheck {

  public static final String PARTY = "SAFT_REF_PARTY_NOT_IN_MASTER";
  public static final String TAX = "SAFT_REF_TAX_NOT_IN_TABLE";
  public static final String PRODUCT = "SAFT_REF_PRODUCT_NOT_IN_MASTER";
  public static final String ACCOUNT = "SAFT_REF_ACCOUNT_NOT_IN_MASTER";
  public static final String UOM = "SAFT_REF_UOM_NOT_IN_TABLE";

  /** „Няма такъв“ — контрагент (GL.19) и продукт (QA с. 33). */
  static final String NONE = "0";

  private BgSaftReferenceCheck() {}

  /** Без указател към партньора — само ID-то. */
  public static List<BgDeclarationFileFinding> check(SaftAuditFile file, String ownId) {
    return check(file, ownId, Collections.emptyMap());
  }

  /**
   * @param ownId ID на подаващия — „10“ + ЕИК
   * @param partnerIds партньорът зад всеки CustomerID/SupplierID — за указателя на находката
   */
  public static List<BgDeclarationFileFinding> check(
      SaftAuditFile file, String ownId, Map<String, Long> partnerIds) {
    Check c = new Check(file.getMasterFiles(), ownId, partnerIds);
    SaftGeneralLedger ledger = file.getGeneralLedger();
    if (ledger != null && !zero(ledger.getNumberOfEntries())) {
      for (SaftJournal journal : ledger.getJournals()) {
        for (SaftTransaction t : journal.getTransactions()) {
          c.customer("GeneralLedgerEntries", t.getCustomerId());
          c.supplier("GeneralLedgerEntries", t.getSupplierId());
          for (SaftTransactionLine l : t.getLines()) {
            c.customer("GeneralLedgerEntries", l.getCustomerId());
            c.supplier("GeneralLedgerEntries", l.getSupplierId());
            c.account("GeneralLedgerEntries", l.getAccountId());
            l.getTaxInformation().forEach(ti -> c.tax("GeneralLedgerEntries", ti));
          }
        }
      }
    }
    SaftSourceDocuments documents = file.getSourceDocuments();
    if (documents != null) {
      invoices(c, "SalesInvoices", documents.getSalesInvoices(), SaftInvoice.PartyRole.CUSTOMER);
      invoices(
          c, "PurchaseInvoices", documents.getPurchaseInvoices(), SaftInvoice.PartyRole.SUPPLIER);
      payments(c, documents.getPayments());
    }
    if (c.master != null) {
      for (SaftProduct p : c.master.getProducts()) {
        c.uom("Products", p.getUomBase());
        c.uom("Products", p.getUomStandard());
      }
    }
    return c.findings;
  }

  /**
   * @param byDefault ролята на фактура без изрична — {@code null} значи „по раздела“ ({@link
   *     SaftInvoice}), точно както я пише {@code BgSaftXmlWriter}: продажби → CustomerInfo, покупки
   *     → SupplierInfo
   */
  private static void invoices(
      Check c, String section, SaftInvoiceSection invoices, SaftInvoice.PartyRole byDefault) {
    if (invoices == null || zero(invoices.getNumberOfEntries())) {
      return;
    }
    for (SaftInvoice invoice : invoices.getInvoices()) {
      SaftInvoice.PartyRole role =
          invoice.getPartyRole() == null ? byDefault : invoice.getPartyRole();
      if (role == SaftInvoice.PartyRole.SUPPLIER) {
        c.supplier(section, invoice.getPartyId());
      } else {
        c.customer(section, invoice.getPartyId());
      }
      c.account(section, invoice.getAccountId());
      for (SaftInvoiceLine line : invoice.getLines()) {
        c.account(section, line.getAccountId());
        c.product(section, line.getProductCode());
        c.uom(section, line.getInvoiceUom());
        line.getTaxInformation().forEach(ti -> c.tax(section, ti));
      }
    }
  }

  private static void payments(Check c, SaftPaymentSection payments) {
    if (payments == null || zero(payments.getNumberOfEntries())) {
      return;
    }
    for (SaftPayment payment : payments.getPayments()) {
      for (SaftPaymentLine line : payment.getLines()) {
        c.customer("Payments", line.getCustomerId());
        c.supplier("Payments", line.getSupplierId());
        c.account("Payments", line.getAccountId());
      }
    }
  }

  private static boolean zero(Long count) {
    return count != null && count == 0L;
  }

  private static final class Check {
    private final SaftMasterFiles master;
    private final String ownId;
    private final Map<String, Long> partnerIds;
    private final Set<String> customers = new HashSet<>();
    private final Set<String> suppliers = new HashSet<>();
    private final Set<String> accounts = new HashSet<>();
    private final Set<String> taxes = new HashSet<>();
    private final Set<String> products = new HashSet<>();
    private final Set<String> uoms = new HashSet<>();
    private final Set<String> reported = new LinkedHashSet<>();
    private final List<BgDeclarationFileFinding> findings = new ArrayList<>();

    private Check(SaftMasterFiles master, String ownId, Map<String, Long> partnerIds) {
      this.master = master;
      this.ownId = ownId;
      this.partnerIds = partnerIds;
      if (master == null) {
        return;
      }
      master.getCustomers().forEach(p -> customers.add(p.getPartyId()));
      master.getSuppliers().forEach(p -> suppliers.add(p.getPartyId()));
      for (SaftGlAccount account : master.getGlAccounts()) {
        accounts.add(account.getAccountId());
      }
      for (SaftTaxTableEntry entry : master.getTaxTableEntries()) {
        for (SaftTaxCodeDetail detail : entry.getTaxCodeDetails()) {
          taxes.add(entry.getTaxType() + "/" + detail.getTaxCode());
        }
      }
      for (SaftProduct product : master.getProducts()) {
        products.add(product.getProductCode());
      }
      for (SaftUom uom : master.getUoms()) {
        uoms.add(uom.getUnitOfMeasure());
      }
    }

    private void customer(String section, String id) {
      party(section, "CustomerID", "Customers", customers, id);
    }

    private void supplier(String section, String id) {
      party(section, "SupplierID", "Suppliers", suppliers, id);
    }

    private void party(String section, String element, String table, Set<String> known, String id) {
      if (id == null || id.isEmpty() || NONE.equals(id) || id.equals(ownId) || known.contains(id)) {
        return;
      }
      String label = section + " " + element + " " + id;
      if (reported.add(PARTY + "|" + label)) {
        BgDeclarationFileFinding finding =
            BgDeclarationFileFinding.error(
                PARTY,
                "Контрагент, рефериран във файла, който го няма в MasterFiles/"
                    + table
                    + " — НАП отказва файла (QA IV.2.4, с. 96): "
                    + label);
        Long partnerId = partnerIds.get(id);
        findings.add(partnerId == null ? finding : finding.at(BgSaftRef.PARTNER, partnerId));
      }
    }

    private void account(String section, String id) {
      if (id == null || id.isEmpty() || accounts.contains(id)) {
        return;
      }
      report(
          ACCOUNT,
          "AccountID, рефериран във файла, който го няма в GeneralLedgerAccounts (MF.GLA.2): ",
          section + " AccountID " + id);
    }

    private void tax(String section, SaftTaxInformation ti) {
      String key = ti.getTaxType() + "/" + ti.getTaxCode();
      if (taxes.contains(key)) {
        return;
      }
      report(
          TAX,
          "Данъчен код, рефериран във файла, който го няма в TaxTable — TaxTable съдържа кодовете,"
              + " за които са подадени данни в другите секции (QA с. 38, MF.TT.1): ",
          section + " " + key);
    }

    private void product(String section, String code) {
      if (code == null || NONE.equals(code) || products.contains(code)) {
        return;
      }
      report(
          PRODUCT,
          "ProductCode, рефериран във файла, който го няма в Products (MF.P.2): ",
          section + " ProductCode " + code);
    }

    private void uom(String section, String code) {
      if (code == null || code.isEmpty() || NONE.equals(code) || uoms.contains(code)) {
        return;
      }
      report(
          UOM,
          "Мерна единица, реферирана във файла, която я няма в UOMTable (QA с. 38, MF.UOM.1): ",
          section + " " + code);
    }

    private void report(String code, String text, String label) {
      if (reported.add(code + "|" + label)) {
        findings.add(BgDeclarationFileFinding.error(code, text + label));
      }
    }
  }
}
