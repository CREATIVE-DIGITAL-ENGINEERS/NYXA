package com.axelor.apps.aiaccounting.ai;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.aiaccountant.context.AiTargetIdentity;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.route.AiRecordDomainResolver;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.tool.AiWriteTargetState;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSearchService;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceSide;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceReadinessContributorRegistry;
import com.axelor.db.JpaSecurity;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single owner of everything an {@code Invoice#id} reference means.
 *
 * <h2>Why one class answers three questions</h2>
 *
 * Which domain an invoice belongs to, which record a reference names, and what its write state is
 * are three questions with one answer each, and all three are derived from the same row. Splitting
 * them across three classes is what let them drift apart before: the OCR module answered all three
 * for {@code Invoice#}, on the assumption that every invoice was a supplier invoice, and a customer
 * invoice would have been classified as a supplier one by a resolver nobody would think to look at.
 *
 * <p>So this class owns {@code Invoice#} completely, and the OCR module owns {@code Upload#}
 * completely, and neither answers for the other's kind. Ownership by reference kind is the only
 * rule that makes the registries' first-answer-wins dispatch safe, because it removes the case
 * where two resolvers both have an answer.
 *
 * <h2>The side comes from the row, not from the caller</h2>
 *
 * {@code operationTypeSelect} decides: 1 and 2 are supplier documents, 3 and 4 customer ones. That
 * is why {@link AiRecordDomainResolver} exists at all — the entity name is the same for both, so
 * the class alone cannot answer, and reading the row needs {@code axelor-account}, which the
 * framework module deliberately does not depend on.
 */
@Singleton
public class InvoiceRecordResolver
    implements AiRecordDomainResolver, AiTargetIdentity, AiWriteTargetState {

  static final String INVOICE_MODEL = "com.axelor.apps.account.db.Invoice";
  static final String INVOICE_KIND = "Invoice";

  // ── AiRecordDomainResolver ────────────────────────────────────────────────

  @Override
  public AiDomain domainOfModel(String model, Long recordId) {
    if (!INVOICE_MODEL.equals(model)) {
      return null;
    }
    if (recordId == null) {
      // A blank invoice form: the page names the entity but no row exists to read the direction
      // from. Answering "supplier" here — the old hardcoded behaviour — would be a coin flip
      // dressed as a fact, so this says nothing and lets the task's own domain decide.
      return null;
    }
    Invoice invoice = find(recordId);
    return invoice == null ? null : domainOf(invoice);
  }

  @Override
  public AiDomain domainOfRef(String ref) {
    Long id = invoiceId(ref);
    if (id == null) {
      return null;
    }
    Invoice invoice = find(id);
    return invoice == null ? null : domainOf(invoice);
  }

  // ── AiTargetIdentity ──────────────────────────────────────────────────────

  @Override
  public String canonical(String ref) {
    Long id = invoiceId(ref);
    if (id == null) {
      return null;
    }
    // Company-scoped, and unresolvable when it is another company's — exactly as though the record
    // did not exist, because the canonical form is what an authorisation is checked against.
    return find(id) == null ? null : INVOICE_KIND + "#" + id;
  }

  @Override
  public Boolean writable(String ref) {
    Long id = invoiceId(ref);
    if (id == null) {
      return null;
    }
    if (find(id) == null) {
      return null;
    }
    return Beans.get(JpaSecurity.class).isPermitted(JpaSecurity.CAN_WRITE, Invoice.class, id);
  }

  // ── AiWriteTargetState ────────────────────────────────────────────────────

  /**
   * An opaque string that changes whenever anything a write here could have touched has changed.
   *
   * <h2>Why the header alone is not enough</h2>
   *
   * The reconciliation path uses this to answer "did my lost write land?", and answers NOT_WRITTEN
   * when the fingerprint is unchanged — which then permits a retry. So a fingerprint that misses a
   * change is not a weaker signal, it is a wrong one: it invites the same write to be applied
   * twice.
   *
   * <p>A line edit is the case that would slip through a header-only fingerprint. Whether a change
   * to a child collection bumps the parent's {@code version} depends on the mapping, and depending
   * on it here would make correctness a property of a cascade setting. So this includes:
   *
   * <ul>
   *   <li>the header's own {@code version}, {@code updatedOn} and {@code statusSelect};
   *   <li>the payment condition's version, since a terms refresh moves it;
   *   <li>all four totals, which move whenever any line materially does;
   *   <li>a digest of every line — id, version, product, quantity, price, account — sorted by id,
   *       so an added, removed, reordered or re-priced line changes the string even if nothing else
   *       did;
   *   <li>whatever a contributor adds — an OCR upload's own processing state lives on another table
   *       and no query here can see it.
   * </ul>
   */
  @Override
  public String fingerprint(String targetKey) {
    Long id = invoiceId(targetKey);
    if (id == null) {
      return null;
    }
    Invoice invoice = find(id);
    if (invoice == null) {
      return null;
    }
    StringBuilder sb = new StringBuilder(INVOICE_KIND).append('#').append(id);
    append(sb, invoice.getVersion());
    append(sb, invoice.getUpdatedOn());
    append(sb, invoice.getStatusSelect());
    append(
        sb,
        invoice.getPaymentCondition() == null ? null : invoice.getPaymentCondition().getVersion());
    append(sb, invoice.getExTaxTotal());
    append(sb, invoice.getTaxTotal());
    append(sb, invoice.getInTaxTotal());
    append(sb, invoice.getCompanyInTaxTotal());

    List<String> lines = new ArrayList<>();
    if (invoice.getInvoiceLineList() != null) {
      for (InvoiceLine line : invoice.getInvoiceLineList()) {
        StringBuilder row = new StringBuilder();
        row.append(line.getId())
            .append(':')
            .append(line.getVersion())
            .append(':')
            .append(line.getProduct() == null ? "-" : line.getProduct().getId())
            .append(':')
            .append(line.getQty())
            .append(':')
            .append(line.getPrice())
            .append(':')
            .append(line.getInTaxPrice())
            .append(':')
            .append(line.getAccount() == null ? "-" : line.getAccount().getId())
            .append(':')
            .append(line.getExTaxTotal());
        lines.add(row.toString());
      }
    }
    // Sorted, so the same set of lines fingerprints identically whatever order the collection came
    // back in — and so a reordering that changes nothing else does not read as a change.
    Collections.sort(lines);
    sb.append("|lines=")
        .append(lines.size())
        .append('[')
        .append(String.join(",", lines))
        .append(']');

    String extra = Beans.get(AiInvoiceReadinessContributorRegistry.class).extraFingerprint(invoice);
    if (extra != null && !extra.isEmpty()) {
      sb.append("|extra=").append(extra);
    }
    return sb.toString();
  }

  // ── shared ────────────────────────────────────────────────────────────────

  /** The invoice, if it exists and belongs to the active company. */
  static Invoice find(long invoiceId) {
    return Beans.get(InvoiceSearchService.class).findScoped(invoiceId, null);
  }

  /** Which domain this invoice's own operation type puts it in. */
  static AiDomain domainOf(Invoice invoice) {
    if (InvoiceSide.SUPPLIER.covers(invoice)) {
      return AiDomain.SUPPLIER_INVOICE;
    }
    if (InvoiceSide.CUSTOMER.covers(invoice)) {
      return AiDomain.CUSTOMER_INVOICE;
    }
    // An operation type outside the four Axelor defines. Not classified rather than guessed.
    return null;
  }

  /** The id of an {@code Invoice#id} reference, or null for anything else. */
  static Long invoiceId(String ref) {
    if (ref == null) {
      return null;
    }
    String trimmed = ref.trim();
    int hash = trimmed.lastIndexOf('#');
    if (hash <= 0 || !INVOICE_KIND.equals(trimmed.substring(0, hash))) {
      return null;
    }
    try {
      long id = Long.parseLong(trimmed.substring(hash + 1).trim());
      return id <= 0 ? null : id;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static void append(StringBuilder sb, Object value) {
    sb.append('|').append(value == null ? "-" : value);
  }

  /** Company scope is part of every answer here; kept for readers looking for the check. */
  static boolean inScope(Invoice invoice) {
    return invoice != null && AiCompanyScope.owns(invoice.getCompany());
  }
}
