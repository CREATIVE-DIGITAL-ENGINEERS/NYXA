package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.NapSubmissionFile;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.services.declarations.common.DeclarationsApiException.Category;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * Builds the official {@link DeclarationsSubmitRequest} from a {@link NapSubmission}, its ordered
 * {@link NapSubmissionFile}s and their finalized {@link NapArtifact} payloads/signatures, plus the
 * caller-supplied {@link DeclarationsSubmitContext}. Also runs TRANSPORT-level validation only — no
 * payroll/accounting/business-content checks. Throws {@link DeclarationsApiException} with {@link
 * Category#INVALID_REQUEST} on any transport-level problem.
 */
@Singleton
public class DeclarationsSubmissionMapper {

  /** The only file type the contract allows for DEC_1 / DEC_6. */
  protected static final String FILE_TYPE_TXT = "txt";

  public DeclarationsSubmitRequest build(NapSubmission submission, DeclarationsSubmitContext ctx) {
    require(
        NapSubmissionRepository.SERVICE_DECLARATIONS.equals(submission.getServiceCodeSelect()),
        "Submission is not a declarations service");
    require(ctx != null, "Missing submit context");
    ServiceDocumentType serviceType = parseServiceType(submission.getServiceDocumentTypeSelect());

    DeclarationsSubmitRequest req = new DeclarationsSubmitRequest();
    req.taxpayerPin = requireText(submission.getTaxpayerPin(), "taxpayerPin");
    req.taxpayerPinType = parsePin(submission.getTaxpayerPinTypeSelect(), "taxpayerPinType");
    // The signer identifier comes from the caller's context (the signer's UserQesProfile, where it
    // is encrypted at rest); the submission snapshot is only a fallback for an already-stamped row.
    req.userPin = requireText(firstNonBlank(ctx.userPin, submission.getSubmitterPin()), "userPin");
    req.userPinType =
        parsePin(
            firstNonBlank(ctx.userPinType, submission.getSubmitterPinTypeSelect()), "userPinType");
    req.serviceDocumentType = serviceType;
    req.userSignatureBase64 = requireText(ctx.userCertificateBase64, "userSignatureBase64");

    if (serviceType == ServiceDocumentType.DEC_1_6) {
      req.taxPeriodFrom = requireMonth(ctx.taxPeriodFrom, "taxPeriodFrom");
      req.taxPeriodTo = requireMonth(ctx.taxPeriodTo, "taxPeriodTo");
      // "Период от е по-голям от период до!" (API_TAXPERIOD_FROM_GREATER_THAT_TAXPERIOD_TO).
      // Two-digit months compare correctly as strings.
      require(
          req.taxPeriodFrom.compareTo(req.taxPeriodTo) <= 0,
          "taxPeriodFrom must not be greater than taxPeriodTo");
      req.year = requireYear(ctx.year);
      require(ctx.insuranceFund != null, "insuranceFund is required for DEC_1_6");
      require(ctx.insuranceFund == 0 || ctx.insuranceFund == 1, "insuranceFund must be 0 or 1");
      req.insuranceFund = ctx.insuranceFund;
    }

    List<NapSubmissionFile> files = new ArrayList<>();
    if (submission.getSubmissionFileList() != null) {
      files.addAll(submission.getSubmissionFileList());
    }
    require(!files.isEmpty(), "At least one file is required");
    files.sort(Comparator.comparing(f -> f.getSequence() == null ? 0 : f.getSequence()));

    require(
        DeclarationsDocumentTypeMatrix.hasConfirmedRules(serviceType),
        "No confirmed file-type rules exist yet for service "
            + serviceType
            + " (matrix unresolved); refusing rather than guessing");

    req.files = new ArrayList<>();
    for (NapSubmissionFile file : files) {
      req.files.add(buildFile(serviceType, file, ctx));
    }
    return req;
  }

  protected DeclarationsFile buildFile(
      ServiceDocumentType serviceType, NapSubmissionFile file, DeclarationsSubmitContext ctx) {
    NapArtifact payload = file.getPayloadArtifact();
    require(payload != null, "A payload artifact is required for each file");
    require(Boolean.TRUE.equals(payload.getFinalized()), "Payload artifact must be finalized");

    // The detached PKCS#7 comes from the TRANSIENT signing material when present (never persisted);
    // only the legacy path reads a stored signature artifact.
    byte[] detachedPkcs7 = transientPkcs7(ctx, file);
    if (detachedPkcs7 == null) {
      // CLOSED for Д1/Д6: their signature never comes from a stored artifact.
      require(
          serviceType != ServiceDocumentType.DEC_1_6,
          "A stored signature artifact may not be used for DEC_1_6; the detached PKCS#7 must be"
              + " supplied transiently by the signing adapter");
      NapArtifact signature = file.getSignatureArtifact();
      require(signature != null, "A detached PKCS#7 signature artifact is required for each file");
      require(
          Boolean.TRUE.equals(signature.getFinalized()), "Signature artifact must be finalized");
      detachedPkcs7 = readBytes(signature);
    }
    require(detachedPkcs7.length > 0, "The detached PKCS#7 signature is empty");

    byte[] payloadBytes = readBytes(payload);
    // fileSize is the ORIGINAL byte length (pre-base64/sign) and must match the recorded artifact.
    require(
        payload.getByteLength() != null && payload.getByteLength() == payloadBytes.length,
        "Payload byte length does not match the recorded artifact size");

    FileDocumentType fileDocType =
        parseFileType(file.getFileDocumentTypeSelect(), "fileDocumentType");
    require(
        DeclarationsDocumentTypeMatrix.isConfirmed(serviceType, fileDocType),
        "File type " + fileDocType + " is not a confirmed combination for service " + serviceType);

    DeclarationsFile dto = new DeclarationsFile();
    dto.fileName = firstNonBlank(file.getSubmittedFileName(), payload.getOriginalFileName());
    require(notBlank(dto.fileName), "fileName is required");
    dto.fileType = requireText(file.getFileTypeSelect(), "fileType");
    dto.fileSize = payloadBytes.length;
    dto.fileContentBase64 = Base64.getEncoder().encodeToString(payloadBytes);
    dto.base64EncodedPkcs7 = Base64.getEncoder().encodeToString(detachedPkcs7);
    dto.fileDocumentType = fileDocType;

    if (fileDocType == FileDocumentType.DEC_1 || fileDocType == FileDocumentType.DEC_6) {
      // "Допустими типове според типа на документа: DEC_1: txt, DEC_6: txt" (Формат за API /
      // Swagger UploadFileDto.fileType). Друг формат връща API_WRONG_FILE_TYPE от НАП.
      require(
          FILE_TYPE_TXT.equals(dto.fileType),
          "fileType for " + fileDocType + " must be \"txt\"; got: " + dto.fileType);
      require(file.getNumRecords() != null, "numRecords is required for DEC_1 / DEC_6 files");
      dto.numRecords = file.getNumRecords();
    }
    return dto;
  }

  /** A two-digit month "01".."12" (contract enum; API_INPUT_MONTH_FROM/TO_ERROR). */
  protected String requireMonth(String value, String field) {
    String month = requireText(value, field + " (required for DEC_1_6)");
    require(
        month.matches("0[1-9]|1[0-2]"),
        field + " must be a two-digit month \"01\"..\"12\"; got: " + month);
    return month;
  }

  /** A four-digit tax year (API_INPUT_YEAR_ERROR). */
  protected String requireYear(String value) {
    String year = requireText(value, "year (required for DEC_1_6)");
    require(year.matches("\\d{4}"), "year must be four digits; got: " + year);
    return year;
  }

  protected byte[] readBytes(NapArtifact artifact) {
    MetaFile metaFile = artifact.getMetaFile();
    require(metaFile != null, "Artifact has no stored file");
    try {
      return Files.readAllBytes(MetaFiles.getPath(metaFile));
    } catch (Exception e) {
      throw new DeclarationsApiException(
          Category.INVALID_REQUEST,
          "validate",
          "Cannot read artifact bytes: " + artifact.getName(),
          e);
    }
  }

  protected ServiceDocumentType parseServiceType(String value) {
    require(notBlank(value), "serviceDocumentType is required");
    try {
      return ServiceDocumentType.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw invalid("Unsupported serviceDocumentType: " + value);
    }
  }

  protected FileDocumentType parseFileType(String value, String field) {
    require(notBlank(value), field + " is required");
    try {
      return FileDocumentType.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw invalid("Unsupported " + field + ": " + value);
    }
  }

  protected PinType parsePin(String value, String field) {
    require(notBlank(value), field + " is required");
    try {
      return PinType.valueOf(value);
    } catch (IllegalArgumentException e) {
      throw invalid("Unsupported " + field + ": " + value);
    }
  }

  protected String requireText(String value, String field) {
    require(notBlank(value), field + " is required");
    return value;
  }

  protected void require(boolean condition, String message) {
    if (!condition) {
      throw invalid(message);
    }
  }

  protected DeclarationsApiException invalid(String message) {
    return new DeclarationsApiException(Category.INVALID_REQUEST, 0, "validate", null, message);
  }

  /** The transient detached PKCS#7 for this file, or {@code null} when the legacy path applies. */
  protected byte[] transientPkcs7(DeclarationsSubmitContext ctx, NapSubmissionFile file) {
    if (ctx == null || ctx.detachedPkcs7ByFile == null) {
      return null;
    }
    return ctx.detachedPkcs7ByFile.get(file);
  }

  protected static String firstNonBlank(String a, String b) {
    return notBlank(a) ? a : b;
  }

  protected static boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }
}
