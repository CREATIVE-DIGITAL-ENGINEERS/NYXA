package com.axelor.apps.aiocr.service.ai;

import com.axelor.apps.aiaccountant.context.AiEvidenceNotReadyException;
import com.axelor.apps.aiaccountant.context.AiTargetRelationship;
import com.axelor.apps.aiaccountant.security.AiCompanyScope;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.BgOcrIntakeService;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.record.OcrInvoiceLink;
import com.axelor.apps.aiocr.service.supplier.SupplierEik;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;

/**
 * Proves a {@code manage_supplier(create)} call's synthetic target against the document that
 * authorised the job it runs inside.
 *
 * <h2>What it checks</h2>
 *
 * One side of the pairing is a {@link PartnerCreationTargetIdentity} reference — company and
 * normalised ЕИК, from the call's own arguments. The other is the job's authorised {@code
 * AiOcrUpload#} or {@code Invoice#} target. This resolver reaches the upload either directly, or —
 * from the invoice side — through {@link OcrInvoiceLink#currentUpload}, rehydrates what Azure
 * actually extracted for it, and answers {@code true} only when the upload is the create's own
 * company's and that extraction's own supplier identity ({@link #documentSupplierEik}) is the one
 * the create call is for.
 *
 * <p>This is document evidence, not model output: the ЕИК compared against is read back from the
 * stored extraction, not from anything the call's arguments claim. A create for an unrelated or
 * unprovable supplier is refused exactly as before — this only permits the one case where the
 * document the job is already authorised for itself attests to the identity being created.
 */
@Singleton
public class UploadSupplierIdentityRelationship implements AiTargetRelationship {

  private static final String UPLOAD_KIND = "AiOcrUpload";
  private static final String INVOICE_KIND = "Invoice";

  @Override
  public Boolean isRelated(String canonicalA, String canonicalB) {
    PartnerCreationTargetIdentity.Parsed creation = creation(canonicalA, canonicalB);
    String documentRef = document(canonicalA, canonicalB);
    if (creation == null || documentRef == null) {
      return null;
    }
    AiOcrUpload upload = resolveUpload(documentRef);
    if (upload == null) {
      return false;
    }
    if (upload.getCompany() == null || !creation.companyId.equals(upload.getCompany().getId())) {
      // A document of one company never vouches for a supplier created in another, whatever the
      // ЕИК: the create would land in books the document does not belong to.
      return false;
    }
    OcrExtractResult result;
    try {
      result = extraction(upload);
    } catch (IllegalStateException notStoredYet) {
      // Genuinely no stored OCR result at all — not a contradiction, and not something this
      // resolver may collapse to "unrelated": see AiEvidenceNotReadyException. Left uncaught by the
      // generic Exception handling in AiTargetRegistry.relatedTarget, so the caller can tell "the
      // evidence is not ready" apart from "the evidence disagrees".
      throw new AiEvidenceNotReadyException(
          "The OCR evidence for "
              + documentRef
              + " has not finished processing yet, so it cannot be confirmed related to "
              + creation.companyId
              + ":"
              + creation.eik
              + ". Try again in a moment.");
    }
    String extractedEik = documentSupplierEik(result);
    return extractedEik != null && extractedEik.equals(creation.eik);
  }

  /**
   * The supplier ЕИК the document itself attests to: its printed ЕИК, else the ЕИК inside its BG
   * VAT number — the same key the intake matches suppliers by ({@code BgOcrIntakeService.eikKey}).
   * Comparing the ЕИК field alone refused every invoice that prints only «ИН по ДДС BG…», which is
   * most of them: the agent read the identity from the VAT number and the create was declared
   * unrelated to the very document it came from.
   *
   * <p>Null when the document names no BG identity, or names two that disagree — an identity the
   * document contradicts itself about is not one it attests to.
   */
  static String documentSupplierEik(OcrExtractResult result) {
    if (result == null) {
      return null;
    }
    String eik = SupplierEik.canonical(result.getSupplierEik());
    String fromVat = SupplierEik.canonical(result.getSupplierVat());
    if (eik != null && fromVat != null && !eik.equals(fromVat)) {
      return null;
    }
    return eik != null ? eik : fromVat;
  }

  /** The stored extraction; throws {@link IllegalStateException} when none is stored yet. */
  protected OcrExtractResult extraction(AiOcrUpload upload) {
    return Beans.get(BgOcrIntakeService.class).rehydrateExtraction(upload);
  }

  private static PartnerCreationTargetIdentity.Parsed creation(String a, String b) {
    PartnerCreationTargetIdentity.Parsed parsed = PartnerCreationTargetIdentity.parse(a);
    return parsed != null ? parsed : PartnerCreationTargetIdentity.parse(b);
  }

  private static String document(String a, String b) {
    if (isDocument(a)) {
      return a;
    }
    return isDocument(b) ? b : null;
  }

  private static boolean isDocument(String ref) {
    if (ref == null) {
      return false;
    }
    int hash = ref.lastIndexOf('#');
    if (hash <= 0) {
      return false;
    }
    String kind = ref.substring(0, hash);
    return UPLOAD_KIND.equals(kind) || INVOICE_KIND.equals(kind);
  }

  protected AiOcrUpload resolveUpload(String documentRef) {
    int hash = documentRef.lastIndexOf('#');
    String kind = documentRef.substring(0, hash);
    Long id = idOf(documentRef.substring(hash + 1));
    if (id == null) {
      return null;
    }
    if (UPLOAD_KIND.equals(kind)) {
      AiOcrUpload upload = Beans.get(AiOcrUploadRepository.class).find(id);
      return upload != null && AiCompanyScope.owns(upload.getCompany()) ? upload : null;
    }
    return Beans.get(OcrInvoiceLink.class).currentUpload(id);
  }

  private static Long idOf(String text) {
    try {
      return Long.parseLong(text.trim());
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
