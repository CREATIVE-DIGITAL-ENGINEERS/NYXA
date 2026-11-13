package com.axelor.apps.bglocalization.service;

import java.math.BigDecimal;

/**
 * One social-insurance contribution line: a single fund, a single payer, and the amount due.
 *
 * <p>Immutable and free of any Axelor dependency, so it is unit testable in isolation. A single
 * {@code BgInsuranceContribution} row produces up to two lines — one per payer — because a row
 * carries both the employer and the insured rate.
 */
public class BgContributionLine {

  /** Who bears the contribution: the employer or the insured person. */
  public enum Payer {
    EMPLOYER,
    INSURED
  }

  private final String fundCode;
  private final Payer payer;
  private final BigDecimal base;
  private final BigDecimal rate;
  private final BigDecimal amount;

  public BgContributionLine(
      String fundCode, Payer payer, BigDecimal base, BigDecimal rate, BigDecimal amount) {
    this.fundCode = fundCode;
    this.payer = payer;
    this.base = base;
    this.rate = rate;
    this.amount = amount;
  }

  public String getFundCode() {
    return fundCode;
  }

  public Payer getPayer() {
    return payer;
  }

  public BigDecimal getBase() {
    return base;
  }

  public BigDecimal getRate() {
    return rate;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  @Override
  public String toString() {
    return "BgContributionLine["
        + fundCode
        + ", "
        + payer
        + ", base="
        + base
        + ", rate="
        + rate
        + ", amount="
        + amount
        + "]";
  }
}
