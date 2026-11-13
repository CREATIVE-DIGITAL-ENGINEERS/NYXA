package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * MovementOfGoods (SourceDocumentsOnDemand) — движенията на запасите в периода.
 *
 * <p>⚠️ Схемата иска поне едно движение, а книгата (SD.MG.4) казва „не се подава при брой 0“. При
 * период без движения се подава нулевата структура по QA IV.2.6 — виж {@code BgSaftZeroStructures}.
 */
public final class SaftMovementOfGoods {

  /** SD.MG.1 — броят на РЕДОВЕТЕ (SD.MG.18), QA с. 69. */
  private Long numberOfMovementLines;

  /** SD.MG.2. */
  private BigDecimal totalQuantityReceived;

  /** SD.MG.3. */
  private BigDecimal totalQuantityIssued;

  /** SD.MG.4 — поне едно. */
  private final List<SaftStockMovement> stockMovements = new ArrayList<>();

  public Long getNumberOfMovementLines() {
    return numberOfMovementLines;
  }

  public SaftMovementOfGoods numberOfMovementLines(Long value) {
    this.numberOfMovementLines = value;
    return this;
  }

  public BigDecimal getTotalQuantityReceived() {
    return totalQuantityReceived;
  }

  public SaftMovementOfGoods totalQuantityReceived(BigDecimal value) {
    this.totalQuantityReceived = value;
    return this;
  }

  public BigDecimal getTotalQuantityIssued() {
    return totalQuantityIssued;
  }

  public SaftMovementOfGoods totalQuantityIssued(BigDecimal value) {
    this.totalQuantityIssued = value;
    return this;
  }

  public List<SaftStockMovement> getStockMovements() {
    return stockMovements;
  }

  public SaftMovementOfGoods addStockMovement(SaftStockMovement value) {
    this.stockMovements.add(value);
    return this;
  }
}
