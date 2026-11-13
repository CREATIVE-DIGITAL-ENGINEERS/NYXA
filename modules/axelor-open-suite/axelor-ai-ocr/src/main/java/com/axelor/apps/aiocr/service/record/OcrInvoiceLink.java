package com.axelor.apps.aiocr.service.record;

import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Answers one question: did an OCR upload produce this invoice?
 *
 * <h2>What it is for</h2>
 *
 * The supplier-invoice tools were built around {@code uploadId} because every supplier invoice the
 * assistant could reach came from the intake queue. They now also accept an {@code invoiceId}, so
 * that an invoice somebody typed in by hand is reachable at all — and that raises the question of
 * what should happen when the {@code invoiceId} names an invoice that <em>did</em> come from OCR.
 *
 * <p>The answer is: exactly what {@code uploadId} would have done. Those tools' OCR paths do work
 * the generic services cannot — reconciling the amounts against what was scanned, keeping the
 * review status in step, writing the audit note on the upload — and skipping it because the caller
 * happened to name the invoice instead of the upload would make the same operation mean two
 * different things. So the tools translate the reference and take their existing path.
 *
 * <h2>One invoice, possibly several uploads</h2>
 *
 * {@code AiOcrUpload.invoice} is a plain many-to-one with no uniqueness behind it, and the intake
 * queue produces duplicates in ordinary use: the same document uploaded twice, a re-scan after a
 * failed extraction, a page split out of a ZIP that lands on an invoice a sibling page already
 * created. So the link is a <em>list</em>, and a {@code fetchOne} over it returns whichever row the
 * database happened to hand back first — a different answer on a different day, for the same
 * invoice.
 *
 * <p>This class therefore fetches ordered and picks the newest ({@link #currentUpload}), which is
 * the one intake last worked on and the one whose state describes the invoice as it stands. Older
 * uploads are history: they are listed for callers that want the whole picture ({@link
 * #uploadsForInvoice}), and they never decide anything on their own, so a failed scan from a month
 * ago cannot block an invoice that has since been re-scanned and completed.
 */
@Singleton
public class OcrInvoiceLink {

  /**
   * Every upload pointing at this invoice, in the active company, newest first.
   *
   * <p>Newest first by id: {@code AiOcrUpload} is {@code sequential}, so a higher id is a later
   * upload, and the order does not depend on nullable timestamps. Empty — never null — when the
   * invoice is nobody's upload, which is the case that routes to the shared invoice services
   * instead.
   */
  public List<AiOcrUpload> uploadsForInvoice(Long invoiceId) {
    if (invoiceId == null) {
      return Collections.emptyList();
    }
    List<AiOcrUpload> found =
        Beans.get(AiOcrUploadRepository.class)
            .all()
            .filter("self.invoice.id = :id")
            .bind("id", invoiceId)
            .order("-id")
            .fetch();
    if (found == null || found.isEmpty()) {
      return Collections.emptyList();
    }
    // Company-scoped in Java rather than in the filter so the scope rule stays in one place: an
    // upload from another company's books cannot claim an invoice here.
    List<AiOcrUpload> scoped = new ArrayList<>(found.size());
    for (AiOcrUpload upload : found) {
      if (AiCompanyScope.owns(upload.getCompany())) {
        scoped.add(upload);
      }
    }
    return scoped;
  }

  /**
   * The upload that speaks for this invoice now — the newest in scope — or null when none does.
   *
   * <p>Deterministic by construction: the same invoice gives the same upload on every call, and a
   * duplicate or superseded upload never displaces the current one.
   */
  public AiOcrUpload currentUpload(Long invoiceId) {
    List<AiOcrUpload> uploads = uploadsForInvoice(invoiceId);
    return uploads.isEmpty() ? null : uploads.get(0);
  }

  /**
   * The id of {@link #currentUpload}, or null when this invoice did not come from OCR.
   *
   * <p>The caller treats null as "not an OCR invoice", which for a record it cannot see is the
   * honest answer either way.
   */
  public Long uploadIdForInvoice(Long invoiceId) {
    AiOcrUpload upload = currentUpload(invoiceId);
    return upload == null ? null : upload.getId();
  }
}
