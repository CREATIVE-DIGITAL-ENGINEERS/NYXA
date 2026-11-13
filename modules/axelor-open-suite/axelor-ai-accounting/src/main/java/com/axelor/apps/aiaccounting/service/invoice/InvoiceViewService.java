package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Partner;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an invoice into the JSON an answer is built from.
 *
 * <h2>Two shapes, and why</h2>
 *
 * A {@link #row} is what a search returns: enough to recognise the document and decide which one to
 * open. A {@link #detail} is what a get returns: everything a person would read off the form,
 * including the lines and the tax summary. Returning the detail shape from a search would put fifty
 * invoices' worth of lines in one reply to answer "which of these is the one from March".
 *
 * <h2>Codes travel with labels</h2>
 *
 * Every reference is reported as its id and its stable code, not only as its printed name, because
 * the code is what a later edit call has to send back. A reply that named a payment condition only
 * as «30 дни» would leave the model to guess the code, or to send the name and be refused.
 */
@Singleton
public class InvoiceViewService {

  /** Summary form, for search results. */
  public Map<String, Object> row(Invoice invoice) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("invoiceId", invoice.getId());
    out.put("number", invoice.getInvoiceId());
    out.put("supplierInvoiceNb", invoice.getSupplierInvoiceNb());
    out.put("operationTypeSelect", invoice.getOperationTypeSelect());
    out.put("statusSelect", invoice.getStatusSelect());
    out.put("status", statusLabel(invoice.getStatusSelect()));
    out.put("partner", partnerRef(invoice.getPartner()));
    out.put("invoiceDate", text(invoice.getInvoiceDate()));
    out.put("dueDate", text(invoice.getDueDate()));
    out.put("exTaxTotal", invoice.getExTaxTotal());
    out.put("taxTotal", invoice.getTaxTotal());
    out.put("inTaxTotal", invoice.getInTaxTotal());
    out.put("amountRemaining", invoice.getAmountRemaining());
    out.put("currency", invoice.getCurrency() == null ? null : invoice.getCurrency().getCode());

    // The few contributed values that earn their place on EVERY row — today, the OCR upload ids
    // this
    // invoice was built from, because a row is where an id is first quoted and an assistant that
    // lists "invoice 651" and only learns on a second call that it is upload 973 has already said
    // the ambiguous thing. Deliberately readRowFields and not readHeaderFields: the header set also
    // carries per-document fields nobody needs twenty-five times in a search result. Core fields
    // are
    // written first and never replaced, so a contributor cannot shadow one.
    Map<String, Object> contributed =
        Beans.get(AiInvoiceFieldRegistry.class).readRowFields(invoice);
    for (Map.Entry<String, Object> e : contributed.entrySet()) {
      out.putIfAbsent(e.getKey(), e.getValue());
    }
    return out;
  }

  /** Everything on the document, plus its lines and tax summary. */
  public Map<String, Object> detail(Invoice invoice) {
    Map<String, Object> out = new LinkedHashMap<>(row(invoice));
    out.put("operationSubTypeSelect", invoice.getOperationSubTypeSelect());
    out.put("company", invoice.getCompany() == null ? null : invoice.getCompany().getName());
    out.put("contactPartner", partnerRef(invoice.getContactPartner()));
    out.put("originDate", text(invoice.getOriginDate()));
    out.put("internalReference", invoice.getInternalReference());
    out.put("externalReference", invoice.getExternalReference());
    out.put(
        "paymentMode",
        codeRef(
            invoice.getPaymentMode() == null ? null : invoice.getPaymentMode().getId(),
            invoice.getPaymentMode() == null ? null : invoice.getPaymentMode().getCode(),
            invoice.getPaymentMode() == null ? null : invoice.getPaymentMode().getName()));
    out.put(
        "paymentCondition",
        codeRef(
            invoice.getPaymentCondition() == null ? null : invoice.getPaymentCondition().getId(),
            invoice.getPaymentCondition() == null ? null : invoice.getPaymentCondition().getCode(),
            invoice.getPaymentCondition() == null
                ? null
                : invoice.getPaymentCondition().getName()));
    out.put(
        "journal",
        codeRef(
            invoice.getJournal() == null ? null : invoice.getJournal().getId(),
            invoice.getJournal() == null ? null : invoice.getJournal().getCode(),
            invoice.getJournal() == null ? null : invoice.getJournal().getName()));
    out.put("partnerAccount", accountRef(invoice.getPartnerAccount()));
    out.put(
        "fiscalPosition",
        codeRef(
            invoice.getFiscalPosition() == null ? null : invoice.getFiscalPosition().getId(),
            invoice.getFiscalPosition() == null ? null : invoice.getFiscalPosition().getCode(),
            invoice.getFiscalPosition() == null ? null : invoice.getFiscalPosition().getName()));
    out.put("bankDetails", bankRef(invoice.getBankDetails()));
    out.put("companyBankDetails", bankRef(invoice.getCompanyBankDetails()));
    out.put("priceList", invoice.getPriceList() == null ? null : invoice.getPriceList().getTitle());
    out.put(
        "tradingName",
        invoice.getTradingName() == null ? null : invoice.getTradingName().getName());
    out.put("inAti", invoice.getInAti());
    out.put("note", invoice.getNote());
    out.put("specificNotes", invoice.getSpecificNotes());
    out.put("companyExTaxTotal", invoice.getCompanyExTaxTotal());
    out.put("companyTaxTotal", invoice.getCompanyTaxTotal());
    out.put("companyInTaxTotal", invoice.getCompanyInTaxTotal());
    out.put("amountPaid", invoice.getAmountPaid());
    out.put("validatedDateTime", text(invoice.getValidatedDateTime()));
    out.put("ventilatedDateTime", text(invoice.getVentilatedDateTime()));
    out.put("moveId", invoice.getMove() == null ? null : invoice.getMove().getId());
    out.put(
        "originalInvoiceId",
        invoice.getOriginalInvoice() == null ? null : invoice.getOriginalInvoice().getId());

    // Whatever a localisation adds to the header — the NAP fields on a Bulgarian install. Merged
    // last so it cannot overwrite a core field by accident, and absent entirely when no module
    // contributes any. The row fields are already here, merged by row() above; a contributor puts a
    // field in one set or the other, so nothing is computed twice for one detail call.
    Map<String, Object> contributed =
        Beans.get(AiInvoiceFieldRegistry.class).readHeaderFields(invoice);
    for (Map.Entry<String, Object> e : contributed.entrySet()) {
      out.putIfAbsent(e.getKey(), e.getValue());
    }

    out.put("lines", lines(invoice));
    out.put("taxSummary", taxSummary(invoice));
    out.put("editable", isDraft(invoice));
    return out;
  }

  /** The lines, in their own order, each with what an edit call would need to change it. */
  public List<Map<String, Object>> lines(Invoice invoice) {
    List<Map<String, Object>> rows = new ArrayList<>();
    if (invoice.getInvoiceLineList() == null) {
      return rows;
    }
    AiInvoiceFieldRegistry fields = Beans.get(AiInvoiceFieldRegistry.class);
    for (InvoiceLine line : invoice.getInvoiceLineList()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("lineId", line.getId());
      row.put("sequence", line.getSequence());
      row.put("typeSelect", line.getTypeSelect());
      row.put("productId", line.getProduct() == null ? null : line.getProduct().getId());
      row.put("productCode", line.getProductCode());
      row.put("productName", line.getProductName());
      row.put("description", line.getDescription());
      row.put("qty", line.getQty());
      row.put("unitId", line.getUnit() == null ? null : line.getUnit().getId());
      row.put("unit", line.getUnit() == null ? null : line.getUnit().getName());
      row.put("price", line.getPrice());
      row.put("inTaxPrice", line.getInTaxPrice());
      row.put("discountAmount", line.getDiscountAmount());
      row.put("discountTypeSelect", line.getDiscountTypeSelect());
      row.put("exTaxTotal", line.getExTaxTotal());
      row.put(
          "taxTotal",
          line.getInTaxTotal() == null || line.getExTaxTotal() == null
              ? null
              : line.getInTaxTotal().subtract(line.getExTaxTotal()));
      row.put("inTaxTotal", line.getInTaxTotal());
      row.put("taxRate", line.getTaxRate());
      row.put("taxCode", line.getTaxCode());
      row.put("taxIds", taxIds(line));
      row.put("account", accountRef(line.getAccount()));
      row.put("fixedAssets", line.getFixedAssets());
      row.put(
          "fixedAssetCategory",
          line.getFixedAssetCategory() == null
              ? null
              : nameRef(
                  line.getFixedAssetCategory().getId(), line.getFixedAssetCategory().getName()));
      row.put("cutOffStartDate", text(line.getCutOffStartDate()));
      row.put("cutOffEndDate", text(line.getCutOffEndDate()));
      row.put("coefficient", line.getCoefficient());
      row.put(
          "analyticDistributionTemplate",
          line.getAnalyticDistributionTemplate() == null
              ? null
              : nameRef(
                  line.getAnalyticDistributionTemplate().getId(),
                  line.getAnalyticDistributionTemplate().getName()));
      Map<String, Object> contributed = fields.readLineFields(line);
      for (Map.Entry<String, Object> e : contributed.entrySet()) {
        row.putIfAbsent(e.getKey(), e.getValue());
      }
      rows.add(row);
    }
    return rows;
  }

  /**
   * The per-rate VAT summary Axelor builds at validation.
   *
   * <p>Empty on a draft that has not been validated, which is normal and not a fault: the summary
   * is derived from the lines when validation runs.
   */
  public List<Map<String, Object>> taxSummary(Invoice invoice) {
    List<Map<String, Object>> rows = new ArrayList<>();
    if (invoice.getInvoiceLineTaxList() == null) {
      return rows;
    }
    for (InvoiceLineTax tax : invoice.getInvoiceLineTaxList()) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("taxLine", tax.getTaxLine() == null ? null : tax.getTaxLine().getName());
      row.put(
          "taxCode",
          tax.getTaxLine() == null || tax.getTaxLine().getTax() == null
              ? null
              : tax.getTaxLine().getTax().getCode());
      row.put("exTaxBase", tax.getExTaxBase());
      row.put("taxTotal", tax.getTaxTotal());
      row.put("inTaxTotal", tax.getInTaxTotal());
      rows.add(row);
    }
    return rows;
  }

  /**
   * What a write reports back: enough to see what the record now is, and not the whole document.
   *
   * <p>Deliberately not {@link #detail}. A reply to "validate this" that carried every line and the
   * whole tax summary would spend the turn's budget restating what the caller already sent, and the
   * one thing it actually needs — did the status move, what is the number now — would be buried in
   * it.
   */
  public Map<String, Object> writeSummary(Invoice invoice, List<String> steps) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("invoiceId", invoice.getId());
    out.put("operationTypeSelect", invoice.getOperationTypeSelect());
    out.put("statusSelect", invoice.getStatusSelect());
    out.put("status", statusLabel(invoice.getStatusSelect()));
    out.put("number", invoice.getInvoiceId());
    out.put("partner", invoice.getPartner() == null ? null : invoice.getPartner().getFullName());
    out.put("invoiceDate", text(invoice.getInvoiceDate()));
    out.put("dueDate", text(invoice.getDueDate()));
    out.put("exTaxTotal", invoice.getExTaxTotal());
    out.put("taxTotal", invoice.getTaxTotal());
    out.put("inTaxTotal", invoice.getInTaxTotal());
    out.put("currency", invoice.getCurrency() == null ? null : invoice.getCurrency().getCode());
    out.put(
        "lineCount",
        invoice.getInvoiceLineList() == null ? 0 : invoice.getInvoiceLineList().size());
    out.put("moveId", invoice.getMove() == null ? null : invoice.getMove().getId());
    if (steps != null && !steps.isEmpty()) {
      out.put("steps", steps);
    }
    return out;
  }

  /** {@code DRAFT}, {@code VALIDATED}, {@code VENTILATED}, {@code CANCELED}, or the raw number. */
  public String statusLabel(Integer statusSelect) {
    if (statusSelect == null) {
      return null;
    }
    switch (statusSelect) {
      case InvoiceRepository.STATUS_DRAFT:
        return "DRAFT";
      case InvoiceRepository.STATUS_VALIDATED:
        return "VALIDATED";
      case InvoiceRepository.STATUS_VENTILATED:
        return "VENTILATED";
      case InvoiceRepository.STATUS_CANCELED:
        return "CANCELED";
      default:
        return String.valueOf(statusSelect);
    }
  }

  public boolean isDraft(Invoice invoice) {
    return invoice.getStatusSelect() != null
        && invoice.getStatusSelect() == InvoiceRepository.STATUS_DRAFT;
  }

  private static Map<String, Object> partnerRef(Partner partner) {
    if (partner == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("partnerId", partner.getId());
    out.put("name", partner.getFullName());
    out.put("registrationCode", partner.getRegistrationCode());
    out.put("taxNbr", partner.getTaxNbr());
    return out;
  }

  private static Map<String, Object> accountRef(Account account) {
    if (account == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("accountId", account.getId());
    out.put("code", account.getCode());
    out.put("name", account.getName());
    return out;
  }

  /** For a reference entity that carries a name but no code (fixed asset category, template). */
  private static Map<String, Object> nameRef(Long id, String name) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", id);
    out.put("name", name);
    return out;
  }

  /**
   * The line's tax ids and codes — what an edit call's {@code taxId}/{@code taxCode} would send.
   */
  private static List<Map<String, Object>> taxIds(InvoiceLine line) {
    List<Map<String, Object>> out = new ArrayList<>();
    if (line.getTaxLineSet() == null) {
      return out;
    }
    for (com.axelor.apps.account.db.TaxLine taxLine : line.getTaxLineSet()) {
      if (taxLine.getTax() == null) {
        continue;
      }
      Map<String, Object> ref = new LinkedHashMap<>();
      ref.put("taxId", taxLine.getTax().getId());
      ref.put("code", taxLine.getTax().getCode());
      out.add(ref);
    }
    return out;
  }

  private static Map<String, Object> bankRef(BankDetails bankDetails) {
    if (bankDetails == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("bankDetailsId", bankDetails.getId());
    out.put("iban", bankDetails.getIban());
    out.put("active", bankDetails.getActive());
    return out;
  }

  private static Map<String, Object> codeRef(Long id, String code, String name) {
    if (id == null && code == null && name == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", id);
    out.put("code", code);
    out.put("name", name);
    return out;
  }

  private static String text(Object value) {
    return value == null ? null : value.toString();
  }

  /** Kept for callers that want a figure rendered rather than a raw scale. */
  public static String amount(BigDecimal value) {
    return value == null ? null : value.toPlainString();
  }
}
