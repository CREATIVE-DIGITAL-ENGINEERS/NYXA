package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.context.AiWorklistProvider;
import com.axelor.apps.aiaccountant.route.AiDomain;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.db.Query;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Answers "which is the next invoice to work on" from the database, not from the transcript.
 *
 * <h2>The failure this fixes</h2>
 *
 * «оправи и осчетоводи следващата» was resolved from conversation history, so it could select an
 * invoice that had been ventilated several turns earlier. From the model's side that is not a
 * mistake — the invoice was genuinely the most recent one discussed, and nothing told it the record
 * had moved on. Recency in a transcript simply is not eligibility in a ledger, and only the server
 * can tell the difference.
 *
 * <h2>The eligible set</h2>
 *
 * OCR uploads of the <b>active company</b> that already carry a supplier invoice in <b>DRAFT</b>,
 * oldest upload first. Ventilated and cancelled invoices are excluded by construction rather than
 * by the model's judgement, so the ordering is total and repeatable: two identical questions in the
 * same ERP state get the same answer.
 *
 * <p>Ordered by upload id ascending — the order the documents arrived. Ascending because "next"
 * means the next one waiting, and an accountant works the backlog from the oldest end; ids are
 * monotonic here, so it needs no tie-break and cannot drift when a record is edited (which {@code
 * updatedOn} would).
 *
 * <h2>A preview, and it has to say so</h2>
 *
 * The list is capped at {@link #MAX_LISTED} — a pointer into the ERP, not a worklist dump, because
 * a longer list costs tokens on every turn to answer a question most turns never ask.
 *
 * <p>But it used to print those five ids and stop, with no total and nothing marking it as partial.
 * To a reader that is indistinguishable from the complete set, and «осчетоводи останалите» with
 * eight drafts waiting was carried out on five of them — correctly, from the model's point of view,
 * because five was all it had been told about. A context preview must never be what defines a
 * business target set, so the total is now stated first and the list is explicitly labelled as a
 * head, with the instruction to resolve the full set through {@code search_ocr_records}.
 *
 * <p>Company scope comes from {@code AiCompanyScope.companyFilter}, the same expression every OCR
 * search uses, so this cannot become the one place another company's document leaks through.
 */
@Singleton
public class OcrWorklistProvider implements AiWorklistProvider {

  /** Enough for "the next two/three", short enough to stay a pointer. */
  static final int MAX_LISTED = 5;

  @Override
  public String describe() {
    // With no caller identity there is no active company either, and a worklist assembled without
    // one would be a worklist without a boundary.
    if (!AiExecutionContext.isAuthorisedCaller()) {
      return null;
    }
    Long companyId = AiCompanyScope.companyIdOrNull();
    if (companyId == null) {
      return null; // no boundary, no worklist — never list across companies
    }
    long total = countEligibleDrafts();
    if (total == 0) {
      return "There are 0 OCR supplier invoices in DRAFT for this company, so there is no 'next'"
          + " invoice to correct, validate or post. Say so rather than choosing an older record.";
    }
    List<AiOcrUpload> drafts = eligibleDrafts();
    StringBuilder sb = new StringBuilder();
    // The count comes first and is the load-bearing part of this line: "всички"/"останалите" means
    // this many records, whatever the preview below happens to show.
    sb.append("DRAFT OCR supplier invoices waiting: ").append(total).append(" in total");
    sb.append(
        total > drafts.size() ? ", oldest " + drafts.size() + " listed: " : ", oldest first: ");
    for (int i = 0; i < drafts.size(); i++) {
      AiOcrUpload upload = drafts.get(i);
      if (i > 0) {
        sb.append(", ");
      }
      sb.append("uploadId=").append(upload.getId());
      if (upload.getInvoice() != null) {
        sb.append(" (invoice ").append(upload.getInvoice().getId()).append(")");
      }
    }
    sb.append(
        ". 'следващата'/'the next invoice' means the FIRST entry here and 'следващите две' the first"
            + " two — never an invoice remembered from earlier in this conversation, which may"
            + " already be posted.");
    if (total > drafts.size()) {
      sb.append(
              " This is a PREVIEW, not the target set: 'всички'/'останалите'/'all'/'the remaining"
                  + " ones' means all ")
          .append(total)
          .append(
              " of them. Resolve the full set with search_ocr_records (limit up to 25) and work on"
                  + " every one — never only the ids listed here.");
    } else {
      sb.append(" These ")
          .append(total)
          .append(" are the complete set, so 'всички'/'останалите' means exactly these.");
    }
    return sb.toString();
  }

  /**
   * The eligible drafts as refs, for an instruction that authorised a set.
   *
   * <h2>The same query, a different purpose</h2>
   *
   * {@link #describe} produces a five-record preview for the prompt and says so. This produces the
   * authorised target set, and the two must not be confused: a set read off a capped preview is how
   * «осчетоводи останалите» once got carried out on five of eight documents. So this takes the
   * caller's limit rather than {@link #MAX_LISTED}, and the caller refuses outright when the answer
   * fills it — a set too large to authorise is never quietly trimmed to fit.
   *
   * <p>Same ordering as the preview, oldest first, so the authorised set is the head of the same
   * backlog the model is being shown.
   */
  @Override
  public List<String> candidates(AiDomain domain, int limit) {
    if (domain != AiDomain.SUPPLIER_INVOICE
        || limit <= 0
        || !AiExecutionContext.isAuthorisedCaller()
        || AiCompanyScope.companyIdOrNull() == null) {
      return Collections.emptyList();
    }
    List<String> refs = new ArrayList<>();
    for (AiOcrUpload upload : eligibleQuery().order("id").fetch(limit)) {
      refs.add("Upload#" + upload.getId());
    }
    return refs;
  }

  /**
   * The subsets of the OCR queue this provider can resolve by name.
   *
   * <ul>
   *   <li>{@code DRAFT} — the same set {@link #candidates(AiDomain, int)} answers: uploads whose
   *       supplier invoice is a DRAFT. Named so «осчетоводи черновите» is a filtered request the
   *       server can satisfy exactly rather than one it has to read as "the whole queue".
   *   <li>{@code NEEDS_REVIEW} — uploads the intake could not finish on its own, which is the one
   *       status an accountant routinely works as a batch of its own.
   * </ul>
   *
   * <p>Deliberately short. A filter exists here when a deterministic, company-scoped query answers
   * it; a supplier, a date or an amount does not get one by being plausible, and the registry
   * refuses what is not listed rather than widening it to the whole queue.
   */
  @Override
  public Set<String> filters(AiDomain domain) {
    return domain == AiDomain.SUPPLIER_INVOICE
        ? new LinkedHashSet<>(Arrays.asList(FILTER_DRAFT, FILTER_NEEDS_REVIEW))
        : Collections.emptySet();
  }

  /** Uploads whose supplier invoice is a DRAFT — the ordinary queue, addressable by name. */
  static final String FILTER_DRAFT = "DRAFT";

  /** Uploads the intake left for a person to look at. */
  static final String FILTER_NEEDS_REVIEW = "NEEDS_REVIEW";

  @Override
  public List<String> candidates(AiDomain domain, String filter, int limit) {
    if (domain != AiDomain.SUPPLIER_INVOICE
        || limit <= 0
        || !AiExecutionContext.isAuthorisedCaller()
        || AiCompanyScope.companyIdOrNull() == null) {
      return Collections.emptyList();
    }
    if (FILTER_DRAFT.equals(filter)) {
      return candidates(domain, limit);
    }
    if (!FILTER_NEEDS_REVIEW.equals(filter)) {
      // Not one this provider declared. Empty, never the unfiltered queue — see
      // AiWorklistRegistry.candidates.
      return Collections.emptyList();
    }
    List<String> refs = new ArrayList<>();
    for (AiOcrUpload upload : needsReviewQuery().order("id").fetch(limit)) {
      refs.add("Upload#" + upload.getId());
    }
    return refs;
  }

  /** Same company scope and same ordering as {@link #eligibleQuery}, on the review status. */
  protected Query<AiOcrUpload> needsReviewQuery() {
    return Beans.get(AiOcrUploadRepository.class)
        .all()
        .filter(
            "self.statusSelect = :needsReview AND " + AiCompanyScope.companyFilter("self.company"))
        .bind("needsReview", AiOcrUploadStatus.NEEDS_REVIEW)
        .bind(AiCompanyScope.COMPANY_PARAM, AiCompanyScope.companyIdOrNull());
  }

  /**
   * How many DRAFT OCR invoices this company really has.
   *
   * <p>Counted rather than inferred from the preview, because the preview is capped and the whole
   * point of the count is that the two are different numbers.
   */
  public long countEligibleDrafts() {
    return eligibleQuery().count();
  }

  /** The head of the eligible set, in the documented order. Never null. */
  public List<AiOcrUpload> eligibleDrafts() {
    return eligibleQuery().order("id").fetch(MAX_LISTED);
  }

  protected Query<AiOcrUpload> eligibleQuery() {
    return Beans.get(AiOcrUploadRepository.class)
        .all()
        .filter(
            "self.invoice IS NOT NULL AND self.invoice.statusSelect = :draft AND "
                + AiCompanyScope.companyFilter("self.company"))
        .bind("draft", InvoiceRepository.STATUS_DRAFT)
        .bind(AiCompanyScope.COMPANY_PARAM, AiCompanyScope.companyIdOrNull());
  }
}
