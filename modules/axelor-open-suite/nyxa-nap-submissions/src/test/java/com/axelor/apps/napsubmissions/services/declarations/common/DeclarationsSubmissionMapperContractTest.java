package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Transport validation of the submit request against the НАП contract (официалната спецификация на
 * API на НАП: „Формат за API" + SwaggerTechnicalDesc_Declarations): the documented enums for {@code
 * taxPeriodFrom/To}, the four-digit {@code year}, the from&le;to ordering, and the {@code txt}-only
 * file type of DEC_1/DEC_6.
 */
class DeclarationsSubmissionMapperContractTest {

  private static final byte[] D1 = "D1-payload".getBytes(StandardCharsets.UTF_8);
  private static final byte[] PKCS7 = "pkcs7-d1".getBytes(StandardCharsets.UTF_8);

  private final Map<NapArtifact, byte[]> bytes = new IdentityHashMap<>();

  private final DeclarationsSubmissionMapper mapper =
      new DeclarationsSubmissionMapper() {
        @Override
        protected byte[] readBytes(NapArtifact artifact) {
          return bytes.get(artifact);
        }
      };

  private final Map<NapSubmissionFile, byte[]> transientSignatures = new IdentityHashMap<>();

  private NapArtifact artifact(byte[] content) {
    NapArtifact artifact = new NapArtifact();
    artifact.setByteLength((long) content.length);
    artifact.setFinalized(Boolean.TRUE);
    artifact.setOriginalFileName("EMPL2021.TXT");
    bytes.put(artifact, content);
    return artifact;
  }

  private NapSubmissionFile d1File(String fileType) {
    NapSubmissionFile file = new NapSubmissionFile();
    file.setSequence(0);
    file.setSubmittedFileName("EMPL2021.TXT");
    file.setFileDocumentTypeSelect(FileDocumentType.DEC_1.name());
    file.setFileTypeSelect(fileType);
    file.setNumRecords(1);
    file.setPayloadArtifact(artifact(D1));
    // Д1/Д6 take the detached PKCS#7 ONLY from the transient signing material, never from a stored
    // signature artifact (that fallback is closed for DEC_1_6).
    transientSignatures.put(file, PKCS7);
    return file;
  }

  private NapSubmission submission(NapSubmissionFile... files) {
    NapSubmission submission = new NapSubmission();
    submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_DECLARATIONS);
    submission.setServiceDocumentTypeSelect(ServiceDocumentType.DEC_1_6.name());
    submission.setTaxpayerPin("987654321");
    submission.setTaxpayerPinTypeSelect(PinType.BUS_BULSTAT.name());
    submission.setSubmissionFileList(new ArrayList<>(Arrays.asList(files)));
    return submission;
  }

  private DeclarationsSubmitContext context() {
    DeclarationsSubmitContext ctx = new DeclarationsSubmitContext();
    // One shared, mutable map instance, so it does not matter whether the file or the context is
    // built first.
    ctx.detachedPkcs7ByFile = transientSignatures;
    ctx.userPin = "1234567890";
    ctx.userPinType = PinType.IND_EGN.name();
    ctx.userCertificateBase64 = "dXNlclNpZ25hdHVyZQ==";
    ctx.taxPeriodFrom = "03";
    ctx.taxPeriodTo = "03";
    ctx.year = "2023";
    ctx.insuranceFund = 0;
    return ctx;
  }

  // --- the documented happy path ----------------------------------------------------------------

  /**
   * Д1 alone is an explicitly documented combination ("При подаване само на Д1 не се подава Д6").
   */
  @Test
  void d1AloneBuildsTheDocumentedRequest() {
    DeclarationsSubmitRequest req = mapper.build(submission(d1File("txt")), context());

    assertEquals(1, req.files.size());
    assertEquals(FileDocumentType.DEC_1, req.files.get(0).fileDocumentType);
    assertEquals("txt", req.files.get(0).fileType);
    assertEquals(Integer.valueOf(1), req.files.get(0).numRecords);
    assertEquals(D1.length, req.files.get(0).fileSize);
    assertEquals("03", req.taxPeriodFrom);
    assertEquals("2023", req.year);
    assertEquals(Integer.valueOf(0), req.insuranceFund);
  }

  // --- contract validations -----------------------------------------------------------------

  @Test
  void aTaxPeriodOutsideTheDocumentedMonthEnumIsRefused() {
    DeclarationsSubmitContext ctx = context();
    ctx.taxPeriodFrom = "13";

    DeclarationsApiException e =
        assertThrows(
            DeclarationsApiException.class, () -> mapper.build(submission(d1File("txt")), ctx));
    assertTrue(e.getMessage().contains("taxPeriodFrom"), e.getMessage());
  }

  @Test
  void aTaxPeriodFromGreaterThanToIsRefused() {
    DeclarationsSubmitContext ctx = context();
    ctx.taxPeriodFrom = "05";
    ctx.taxPeriodTo = "03";

    assertThrows(
        DeclarationsApiException.class, () -> mapper.build(submission(d1File("txt")), ctx));
  }

  @Test
  void aYearThatIsNotFourDigitsIsRefused() {
    DeclarationsSubmitContext ctx = context();
    ctx.year = "23";

    assertThrows(
        DeclarationsApiException.class, () -> mapper.build(submission(d1File("txt")), ctx));
  }

  @Test
  void aNonTxtFileTypeForD1IsRefused() {
    DeclarationsApiException e =
        assertThrows(
            DeclarationsApiException.class,
            () -> mapper.build(submission(d1File("csv")), context()));
    assertTrue(e.getMessage().contains("txt"), e.getMessage());
  }

  @Test
  void aMissingInsuranceFundIsRefusedForDec16() {
    DeclarationsSubmitContext ctx = context();
    ctx.insuranceFund = null;

    assertThrows(
        DeclarationsApiException.class, () -> mapper.build(submission(d1File("txt")), ctx));
  }
}
