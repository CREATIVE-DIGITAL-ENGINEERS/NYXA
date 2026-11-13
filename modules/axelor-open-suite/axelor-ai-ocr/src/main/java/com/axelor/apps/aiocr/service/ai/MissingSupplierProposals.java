package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiaccountant.context.AiRequestContext;
import com.axelor.apps.aiaccountant.service.AiPendingProposalStore;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.supplier.SupplierMaintenanceService;
import com.axelor.apps.base.db.Partner;
import com.axelor.inject.Beans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records a structured {@code AiPendingProposal} when a trusted read tool — {@code get_ocr_record},
 * {@code get_ocr_evidence} — surfaces a supplier the document's own stored OCR extraction names but
 * no partner yet exists for.
 *
 * <h2>Read tools only, and only from stored evidence</h2>
 *
 * Called from a {@code READ}-kind tool, after it has already read the upload — never from anything
 * the model writes, and never from the assistant's reply text. The ЕИК compared is the one {@link
 * BgOcrIntakeService#rehydrateExtraction} reads back from the document's own committed extraction,
 * exactly as {@link UploadSupplierIdentityRelationship} reads it to authorise the eventual create;
 * this is the same fact, offered a turn earlier so a plain confirmation next turn has something
 * specific to confirm.
 *
 * <h2>Never an authorisation</h2>
 *
 * This only ever calls {@link AiPendingProposalStore#record}, which itself decides nothing — see
 * its own javadoc. If the upload's extraction is not committed yet, or a partner for that ЕИК
 * already exists, or there is no chat turn to attribute the proposal to, nothing is recorded.
 */
public final class MissingSupplierProposals {

  private static final Logger LOG = LoggerFactory.getLogger(MissingSupplierProposals.class);

  private MissingSupplierProposals() {}

  /** Best-effort; never allowed to fail the read it is called from. */
  public static void recordIfMissing(AiOcrUpload upload) {
    try {
      recordIfMissingInternal(upload);
    } catch (RuntimeException e) {
      LOG.debug("AI Accountant: could not record a missing-supplier proposal: {}", e.getMessage());
    }
  }

  private static void recordIfMissingInternal(AiOcrUpload upload) {
    if (upload == null || upload.getCompany() == null) {
      return;
    }
    AiRequestContext context = AiRequestContext.current();
    if (context == null || context.getUserId() == null || context.getConversationId() == null) {
      // No chat turn to attribute this to — the unattended intake pipeline, most likely. A
      // proposal only ever means something inside one specific chat panel.
      return;
    }
    Long companyId = upload.getCompany().getId();
    if (companyId == null || !companyId.equals(context.getCompanyId())) {
      return;
    }
    OcrExtractResult result;
    try {
      result = Beans.get(BgOcrIntakeService.class).rehydrateExtraction(upload);
    } catch (RuntimeException notReady) {
      // Nothing stored yet to propose from — not an error, just nothing to offer this turn.
      return;
    }
    String eik = UploadSupplierIdentityRelationship.documentSupplierEik(result);
    if (eik == null) {
      return;
    }
    Partner existing = Beans.get(SupplierMaintenanceService.class).findScopedByEik(eik);
    if (existing != null) {
      // The supplier already exists; there is nothing to propose creating.
      return;
    }
    Beans.get(AiPendingProposalStore.class)
        .record(
            context.getUserId(),
            companyId,
            context.getConversationId(),
            "PartnerCreate:" + companyId + ":" + eik,
            "Upload#" + upload.getId(),
            "CREATE_SUPPLIER_INVOICE");
  }
}
