package com.axelor.apps.aiocr.service.supplier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A create (or an ЕИК change) refused because a partner the active company can see already holds
 * the registration number.
 *
 * <p>Carries the existing record's id and supplier flag as structured fields, so a caller can act
 * on them without parsing the sentence. Both are null when the caller may not read that record —
 * the refusal then still stops the duplicate, but says nothing about the record itself.
 */
public class DuplicateSupplierException extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  private final Long existingSupplierId;
  private final Boolean existingIsSupplier;
  private final boolean existingShared;

  public DuplicateSupplierException(
      String message, Long existingSupplierId, Boolean existingIsSupplier, boolean existingShared) {
    super(message);
    this.existingSupplierId = existingSupplierId;
    this.existingIsSupplier = existingIsSupplier;
    this.existingShared = existingShared;
  }

  /** The accessible existing partner's id, or null when it is not readable by this caller. */
  public Long getExistingSupplierId() {
    return existingSupplierId;
  }

  public Boolean getExistingIsSupplier() {
    return existingIsSupplier;
  }

  /** True when the existing partner is shared beyond the active company, so not editable here. */
  public boolean isExistingShared() {
    return existingShared;
  }

  /** The short scalar fields for a tool error envelope; empty when nothing may be disclosed. */
  public Map<String, String> details() {
    Map<String, String> details = new LinkedHashMap<>();
    if (existingSupplierId == null) {
      return details;
    }
    details.put("existingSupplierId", String.valueOf(existingSupplierId));
    details.put("existingIsSupplier", String.valueOf(Boolean.TRUE.equals(existingIsSupplier)));
    if (existingShared) {
      details.put("existingShared", "true");
    } else {
      details.put("repairTool", "manage_supplier");
      details.put("repairOperation", "update");
    }
    return details;
  }
}
