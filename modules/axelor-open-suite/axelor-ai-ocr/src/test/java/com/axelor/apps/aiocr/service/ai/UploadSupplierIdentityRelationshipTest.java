package com.axelor.apps.aiocr.service.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiaccountant.context.AiEvidenceNotReadyException;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.base.db.Company;
import org.junit.jupiter.api.Test;

/**
 * «създай този доставчик» from {@code AiOcrUpload#153}: the create for {@code
 * PartnerCreate:1:203970390} was refused as unrelated to the upload because only the extraction's
 * ЕИК field was compared, and the invoice printed its identity only as «ИН по ДДС BG203970390».
 * Pure — the upload lookup and the stored extraction are seams.
 */
class UploadSupplierIdentityRelationshipTest {

  private static final String UPLOAD = "AiOcrUpload#153";

  private static OcrExtractResult extracted(String eik, String vat) {
    OcrExtractResult result = new OcrExtractResult();
    result.setSupplierEik(eik);
    result.setSupplierVat(vat);
    return result;
  }

  private static UploadSupplierIdentityRelationship relationship(
      long uploadCompanyId, OcrExtractResult result) {
    Company company = new Company();
    company.setId(uploadCompanyId);
    AiOcrUpload upload = new AiOcrUpload();
    upload.setId(153L);
    upload.setCompany(company);
    return new UploadSupplierIdentityRelationship() {
      @Override
      protected AiOcrUpload resolveUpload(String documentRef) {
        assertEquals(UPLOAD, documentRef);
        return upload;
      }

      @Override
      protected OcrExtractResult extraction(AiOcrUpload u) {
        if (result == null) {
          throw new IllegalStateException("no stored OCR result yet");
        }
        return result;
      }
    };
  }

  @Test
  void aSupplierIdentifiedOnlyByItsBgVatNumberIsTheUploadsOwnSupplier() {
    assertTrue(
        relationship(1L, extracted(null, "BG203970390"))
            .isRelated(UPLOAD, "PartnerCreate:1:203970390"));
    assertTrue(
        relationship(1L, extracted("203970390", null))
            .isRelated("PartnerCreate:1:203970390", UPLOAD));
  }

  @Test
  void anUnrelatedSupplierIsRefused() {
    assertFalse(
        relationship(1L, extracted(null, "BG203970390"))
            .isRelated(UPLOAD, "PartnerCreate:1:999999995"));
  }

  @Test
  void aDocumentContradictingItselfAboutTheSupplierAttestsToNeither() {
    UploadSupplierIdentityRelationship ambiguous =
        relationship(1L, extracted("203970390", "BG131071587"));

    assertFalse(ambiguous.isRelated(UPLOAD, "PartnerCreate:1:203970390"));
    assertFalse(ambiguous.isRelated(UPLOAD, "PartnerCreate:1:131071587"));
    assertNull(
        UploadSupplierIdentityRelationship.documentSupplierEik(
            extracted("203970390", "BG131071587")));
  }

  @Test
  void anUploadOfAnotherCompanyNeverVouchesForThisCompanysCreate() {
    assertFalse(
        relationship(1L, extracted(null, "BG203970390"))
            .isRelated(UPLOAD, "PartnerCreate:3:203970390"));
  }

  @Test
  void evidenceNotStoredYetIsNotReadAsUnrelated() {
    assertThrows(
        AiEvidenceNotReadyException.class,
        () -> relationship(1L, null).isRelated(UPLOAD, "PartnerCreate:1:203970390"));
  }
}
