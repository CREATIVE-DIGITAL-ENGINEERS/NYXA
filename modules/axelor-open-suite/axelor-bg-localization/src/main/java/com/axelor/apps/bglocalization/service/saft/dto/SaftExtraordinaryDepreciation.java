package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;

/**
 * ExtraordinaryDepreciationForPeriod (MF.A.32–34) — извънредната амортизация за периода (СС 4 т.
 * 6.5). Схемата иска поне една във всяка ValuationSAP.
 *
 * <p>Nyxa извънредна амортизация няма; за всеки актив се подава {@link #none()} — точно както
 * официалният годишен пример на НАП (Прил. № 1 към З-ЦУ-30-1797/13.10.2026, {@code
 * VS_SAMPLE_AuditFile_Annual_V_1.0.1.xml}): метод „ “ (един интервал) и сума 0.00.
 */
public final class SaftExtraordinaryDepreciation {

  /** MF.A.33 — ExtraordinaryDepreciationMethod, до 35 знака. */
  private String method;

  /** MF.A.34 — ExtraordinaryDepreciationAmountForPeriod. */
  private BigDecimal amount;

  /** Без извънредна амортизация — формата на официалния пример: „ “ и 0.00. */
  public static SaftExtraordinaryDepreciation none() {
    return new SaftExtraordinaryDepreciation().method(" ").amount(new BigDecimal("0.00"));
  }

  public String getMethod() {
    return method;
  }

  public SaftExtraordinaryDepreciation method(String value) {
    this.method = value;
    return this;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  public SaftExtraordinaryDepreciation amount(BigDecimal value) {
    this.amount = value;
    return this;
  }
}
