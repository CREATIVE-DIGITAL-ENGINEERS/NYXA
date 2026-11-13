package com.axelor.apps.bglocalization.service.saft.dto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * StockMovement — само задължителните елементи. Незадължителните (SD.MG.7–9, SD.MG.17 DocumentLine)
 * добавя пистата на склада.
 */
public final class SaftStockMovement {

  /** SD.MG.5. */
  private String movementReference;

  /** SD.MG.6. */
  private LocalDate movementDate;

  /** SD.MG.10 — код по Stock movement. */
  private String movementType;

  /** SD.MG.11. */
  private String sourceId;

  /** SD.MG.12. */
  private String systemId;

  /** SD.MG.15 — в DocumentReference. */
  private String documentType;

  /** SD.MG.16 — в DocumentReference. */
  private String documentNumber;

  /** SD.MG.14 — поне един ред. */
  private final List<SaftStockMovementLine> lines = new ArrayList<>();

  public String getMovementReference() {
    return movementReference;
  }

  public SaftStockMovement movementReference(String value) {
    this.movementReference = value;
    return this;
  }

  public LocalDate getMovementDate() {
    return movementDate;
  }

  public SaftStockMovement movementDate(LocalDate value) {
    this.movementDate = value;
    return this;
  }

  public String getMovementType() {
    return movementType;
  }

  public SaftStockMovement movementType(String value) {
    this.movementType = value;
    return this;
  }

  public String getSourceId() {
    return sourceId;
  }

  public SaftStockMovement sourceId(String value) {
    this.sourceId = value;
    return this;
  }

  public String getSystemId() {
    return systemId;
  }

  public SaftStockMovement systemId(String value) {
    this.systemId = value;
    return this;
  }

  public String getDocumentType() {
    return documentType;
  }

  public SaftStockMovement documentType(String value) {
    this.documentType = value;
    return this;
  }

  public String getDocumentNumber() {
    return documentNumber;
  }

  public SaftStockMovement documentNumber(String value) {
    this.documentNumber = value;
    return this;
  }

  public List<SaftStockMovementLine> getLines() {
    return lines;
  }

  public SaftStockMovement addLine(SaftStockMovementLine value) {
    this.lines.add(value);
    return this;
  }
}
