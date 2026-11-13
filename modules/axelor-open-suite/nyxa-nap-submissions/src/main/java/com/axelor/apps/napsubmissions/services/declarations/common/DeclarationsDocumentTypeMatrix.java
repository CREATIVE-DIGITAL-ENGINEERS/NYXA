package com.axelor.apps.napsubmissions.services.declarations.common;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * Which {@link FileDocumentType}s are allowed under each {@link ServiceDocumentType}. We enforce
 * <strong>only combinations explicitly confirmed by the official documents</strong> — the full
 * matrix is deliberately NOT invented. Anything not registered here is rejected with a clear error
 * (never silently accepted). Extending it later is a one-line addition to {@link #CONFIRMED}.
 *
 * <p>Confirmed from the official NAP API specification (DOCX):
 *
 * <ul>
 *   <li>{@code DEC_1_6} → {@code DEC_1}, {@code DEC_6} (Декларация обр. 1 и обр. 6; numRecords
 *       req.).
 *   <li>{@code DEC_ETZ62} → {@code DEC_ETZ62}.
 *   <li>{@code DEC_ETZ123} → {@code DEC_ETZ123}.
 * </ul>
 *
 * <p><strong>Unresolved:</strong> the {@code DEC_HIGH_FISC_RISK} file-type set ({@code
 * DEC_FISC_RISK_*}) is NOT spelled out as a table in the package (the XSDs live on nra.bg). It is
 * intentionally left unregistered, so such submissions fail validation until the exact set is
 * confirmed — then add it here.
 */
public final class DeclarationsDocumentTypeMatrix {

  private static final Map<ServiceDocumentType, Set<FileDocumentType>> CONFIRMED =
      new EnumMap<>(ServiceDocumentType.class);

  static {
    CONFIRMED.put(
        ServiceDocumentType.DEC_1_6, EnumSet.of(FileDocumentType.DEC_1, FileDocumentType.DEC_6));
    CONFIRMED.put(ServiceDocumentType.DEC_ETZ62, EnumSet.of(FileDocumentType.DEC_ETZ62));
    CONFIRMED.put(ServiceDocumentType.DEC_ETZ123, EnumSet.of(FileDocumentType.DEC_ETZ123));
    // DEC_HIGH_FISC_RISK: intentionally unregistered — combination unresolved (see class doc).
  }

  private DeclarationsDocumentTypeMatrix() {}

  /** True only if the (service, file) pair is an explicitly confirmed combination. */
  public static boolean isConfirmed(ServiceDocumentType service, FileDocumentType file) {
    Set<FileDocumentType> allowed = CONFIRMED.get(service);
    return allowed != null && allowed.contains(file);
  }

  /** Whether any file combination is confirmed for the service at all. */
  public static boolean hasConfirmedRules(ServiceDocumentType service) {
    return CONFIRMED.containsKey(service);
  }
}
