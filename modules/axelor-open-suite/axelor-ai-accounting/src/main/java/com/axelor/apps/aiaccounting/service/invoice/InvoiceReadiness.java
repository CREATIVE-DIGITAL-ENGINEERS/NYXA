package com.axelor.apps.aiaccounting.service.invoice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** What still stands between an invoice and each of its next two transitions. */
public final class InvoiceReadiness {

  private final List<String> validateBlockers;
  private final List<String> ventilateBlockers;
  private final List<String> warnings;

  InvoiceReadiness(
      List<String> validateBlockers, List<String> ventilateBlockers, List<String> warnings) {
    this.validateBlockers = Collections.unmodifiableList(new ArrayList<>(validateBlockers));
    this.ventilateBlockers = Collections.unmodifiableList(new ArrayList<>(ventilateBlockers));
    this.warnings = Collections.unmodifiableList(new ArrayList<>(warnings));
  }

  /** What would refuse a DRAFT → VALIDATED transition right now. */
  public List<String> getValidateBlockers() {
    return validateBlockers;
  }

  /**
   * What would refuse a VALIDATED → VENTILATED transition right now.
   *
   * <p>Includes everything in {@link #getValidateBlockers()}: posting runs through validation, so a
   * blocker there is a blocker here. Reporting them separately would let a caller "clear the
   * posting blockers" and then be refused by validation for something it was never shown.
   */
  public List<String> getVentilateBlockers() {
    return ventilateBlockers;
  }

  /** Things worth saying that stop nothing. */
  public List<String> getWarnings() {
    return warnings;
  }

  public boolean isValidateReady() {
    return validateBlockers.isEmpty();
  }

  public boolean isVentilateReady() {
    return ventilateBlockers.isEmpty();
  }
}
