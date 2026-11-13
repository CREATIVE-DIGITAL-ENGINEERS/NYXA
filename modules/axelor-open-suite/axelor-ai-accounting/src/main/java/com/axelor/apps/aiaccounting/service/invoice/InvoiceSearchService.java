package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.db.Query;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Finding invoices, inside the caller's company and on one side of the ledger only.
 *
 * <h2>Company and side are in the query, not applied after it</h2>
 *
 * Both filters are bound before the page is fetched, so an invoice belonging to another company or
 * to the other direction is never read, never counted in the total and never paged over. Filtering
 * after the fetch would leak the count even while withholding the rows — "your search matched 40
 * invoices" is itself information about another company's books — and would let a customer-invoice
 * search page through supplier documents.
 *
 * <h2>Paging</h2>
 *
 * Same contract as the other searches here: {@code totalMatches} is the size of the whole set and
 * {@code offset} walks it. A search that returned the first page as though it were the whole set is
 * how a job finishes early and calls itself done.
 */
@Singleton
public class InvoiceSearchService {

  /** One page. Fifty summaries is already a large answer to put in front of a person. */
  public static final int MAX_LIMIT = 50;

  public static final int DEFAULT_LIMIT = 20;

  /** Beyond this an offset is a mistake rather than a page. */
  public static final int MAX_OFFSET = 10_000;

  /** What the model may filter on. Every field is optional; company and side never are. */
  public static class InvoiceQuery {
    public InvoiceSide side;
    public Long invoiceId;
    public Long partnerId;
    public String partnerName;
    public String partnerRegistrationCode;
    public String invoiceNumber;
    public Integer statusSelect;
    public Integer operationTypeSelect;
    public LocalDate invoiceDateFrom;
    public LocalDate invoiceDateTo;
    public LocalDate dueDateFrom;
    public LocalDate dueDateTo;
    public BigDecimal minInTaxTotal;
    public BigDecimal maxInTaxTotal;
    public String currencyCode;
    public Boolean unpaidOnly;
    public int limit = DEFAULT_LIMIT;
    public int offset;
  }

  /** One page of matches, plus the size of the set it came from. */
  public static class Page {
    private final List<Invoice> invoices;
    private final long totalMatches;

    Page(List<Invoice> invoices, long totalMatches) {
      this.invoices = invoices;
      this.totalMatches = totalMatches;
    }

    public List<Invoice> getInvoices() {
      return invoices;
    }

    public long getTotalMatches() {
      return totalMatches;
    }
  }

  public Page search(InvoiceQuery q) {
    if (q.side == null) {
      // Never defaulted. A search with no side would answer a customer question with supplier
      // documents, and the caller always knows which tool it is.
      throw new IllegalArgumentException("An invoice search must name the side it is for.");
    }
    List<String> where = new ArrayList<>();
    Map<String, Object> params = new LinkedHashMap<>();

    // Always first, and never taken from the arguments: the dispatcher strips any company the model
    // tried to name, and the active scope is the only thing that decides what is visible.
    where.add(AiCompanyScope.companyFilter("self.company"));
    params.put(AiCompanyScope.COMPANY_PARAM, AiCompanyScope.requireCompanyId());

    if (q.operationTypeSelect != null) {
      // A narrower request inside the side — "only credit notes". Checked against the side rather
      // than trusted, so it can never widen the search to the other direction.
      if (q.operationTypeSelect != q.side.standardType()
          && q.operationTypeSelect != q.side.refundType()) {
        throw new IllegalArgumentException(
            "operationTypeSelect "
                + q.operationTypeSelect
                + " is not a "
                + q.side.label()
                + " type; allowed are "
                + q.side.standardType()
                + " and "
                + q.side.refundType()
                + ".");
      }
      where.add("self.operationTypeSelect = :operationTypeSelect");
      params.put("operationTypeSelect", q.operationTypeSelect);
    } else {
      where.add("self.operationTypeSelect IN (:sideTypes)");
      params.put("sideTypes", java.util.Arrays.asList(q.side.standardType(), q.side.refundType()));
    }

    if (q.invoiceId != null) {
      where.add("self.id = :invoiceId");
      params.put("invoiceId", q.invoiceId);
    }
    if (q.partnerId != null) {
      where.add("self.partner.id = :partnerId");
      params.put("partnerId", q.partnerId);
    }
    if (notBlank(q.partnerName)) {
      where.add("LOWER(self.partner.fullName) LIKE :partnerName");
      params.put("partnerName", like(q.partnerName));
    }
    if (notBlank(q.partnerRegistrationCode)) {
      where.add(
          "(LOWER(self.partner.registrationCode) = :partnerCode"
              + " OR LOWER(self.partner.taxNbr) = :partnerCode)");
      params.put("partnerCode", q.partnerRegistrationCode.trim().toLowerCase(Locale.ROOT));
    }
    if (notBlank(q.invoiceNumber)) {
      // Both numbers, because a person searching for "the invoice numbered X" means whichever of
      // the two is printed on the document in front of them: ours for a customer invoice, theirs
      // for a supplier one.
      where.add(
          "(LOWER(self.invoiceId) LIKE :invoiceNumber"
              + " OR LOWER(self.supplierInvoiceNb) LIKE :invoiceNumber)");
      params.put("invoiceNumber", like(q.invoiceNumber));
    }
    if (q.statusSelect != null) {
      where.add("self.statusSelect = :statusSelect");
      params.put("statusSelect", q.statusSelect);
    }
    if (q.invoiceDateFrom != null) {
      where.add("self.invoiceDate >= :invoiceDateFrom");
      params.put("invoiceDateFrom", q.invoiceDateFrom);
    }
    if (q.invoiceDateTo != null) {
      where.add("self.invoiceDate <= :invoiceDateTo");
      params.put("invoiceDateTo", q.invoiceDateTo);
    }
    if (q.dueDateFrom != null) {
      where.add("self.dueDate >= :dueDateFrom");
      params.put("dueDateFrom", q.dueDateFrom);
    }
    if (q.dueDateTo != null) {
      where.add("self.dueDate <= :dueDateTo");
      params.put("dueDateTo", q.dueDateTo);
    }
    if (q.minInTaxTotal != null) {
      where.add("self.inTaxTotal >= :minInTaxTotal");
      params.put("minInTaxTotal", q.minInTaxTotal);
    }
    if (q.maxInTaxTotal != null) {
      where.add("self.inTaxTotal <= :maxInTaxTotal");
      params.put("maxInTaxTotal", q.maxInTaxTotal);
    }
    if (notBlank(q.currencyCode)) {
      where.add("LOWER(self.currency.code) = :currencyCode");
      params.put("currencyCode", q.currencyCode.trim().toLowerCase(Locale.ROOT));
    }
    if (Boolean.TRUE.equals(q.unpaidOnly)) {
      where.add("self.amountRemaining > 0");
    }

    Query<Invoice> query =
        Beans.get(InvoiceRepository.class).all().filter(String.join(" AND ", where)).bind(params);
    // Newest first, id as the tiebreak so paging over same-date invoices is stable rather than
    // arbitrary.
    query.order("-invoiceDate").order("-id");

    long total = query.count();
    int limit = Math.max(1, Math.min(q.limit, MAX_LIMIT));
    int offset = Math.max(0, Math.min(q.offset, MAX_OFFSET));
    return new Page(query.fetch(limit, offset), total);
  }

  /**
   * How many invoices match, without fetching any of them.
   *
   * <p>For "how many customer invoices are still unpaid" — a question whose answer is a number, and
   * which should not have to page through the rows to produce it.
   */
  public long count(InvoiceQuery q) {
    InvoiceQuery counting = q;
    counting.limit = 1;
    counting.offset = 0;
    return search(counting).getTotalMatches();
  }

  /**
   * The one invoice an id names on this side, or null when it is absent, another company's, or the
   * other direction.
   */
  public Invoice findScoped(long invoiceId, InvoiceSide side) {
    Invoice invoice = Beans.get(InvoiceRepository.class).find(invoiceId);
    if (invoice == null || !AiCompanyScope.owns(invoice.getCompany())) {
      return null;
    }
    return side != null && !side.covers(invoice) ? null : invoice;
  }

  private static boolean notBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }

  private static String like(String value) {
    return "%" + value.trim().toLowerCase(Locale.ROOT) + "%";
  }
}
