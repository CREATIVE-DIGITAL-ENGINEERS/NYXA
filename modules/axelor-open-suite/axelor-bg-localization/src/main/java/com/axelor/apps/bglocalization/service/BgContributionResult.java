package com.axelor.apps.bglocalization.service;

import com.axelor.apps.bglocalization.service.BgContributionLine.Payer;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

/**
 * Result of a social-insurance contribution calculation.
 *
 * <p>Immutable and Axelor-free, like {@link BgMinIncomeResult}. On success it carries the per-fund,
 * per-payer lines; on a blocking status it carries no lines and a reason. The service is a
 * calculator, not a policy — it never throws, and whether a block stops payroll is the caller's
 * decision.
 */
public class BgContributionResult {

  public enum Status {
    /** Contributions successfully computed and safe to declare. */
    RESOLVED,
    /**
     * Lines are computed and usable, but the data is known to be incomplete, so the result is not
     * safe to declare without review. Resolved (lines present) yet blocking (not declarable).
     */
    RESOLVED_INCOMPLETE,
    /** No contribution rows matched — unknown insured type, or no data for the period. */
    NO_CONTRIBUTIONS_FOUND,
    /**
     * The request could never be built, so the resolver was never asked. Distinct from {@link
     * #NO_CONTRIBUTIONS_FOUND} on purpose: that one means "the catalogue was searched and answered
     * nothing", this one means "the catalogue was never searched". Merging them would report a
     * missing insured type on the contract as a hole in the seed, and send the reader to fix the
     * wrong file.
     *
     * <p>Raised by callers that assemble the request — today {@code BgPaySlipService#contributions}
     * — never by {@link BgContributionResolver}, which by construction already holds one.
     */
    INPUT_NOT_ASSEMBLED,
    /** Rows matched but a legally mandatory fund is absent (pension is always due). */
    MISSING_MANDATORY_FUND,
    /** The same fund appears on more than one row for the key — the seed is ambiguous. */
    DUPLICATE_FUND_ROWS,
    /**
     * A rate on a matching row is declared {@code MISSING} — its value was never established from a
     * primary source. Refusing is the whole point: the stored decimal is {@code 0.0000} and the
     * generated getter never returns null, so without the kind column a missing rate is
     * indistinguishable from a legal or structural zero and would silently under-charge.
     */
    MISSING_RATE,
    /**
     * A rate on a matching row carries no kind at all, or one outside the selection. Treated as a
     * refusal rather than an assumed {@code VALUE}: a row that never declared what its zero means
     * is exactly the row whose zero must not be trusted.
     */
    UNDECLARED_RATE_KIND
  }

  private final Status status;
  private final List<BgContributionLine> lines;
  private final String detail;

  private BgContributionResult(Status status, List<BgContributionLine> lines, String detail) {
    this.status = status;
    this.lines = lines;
    this.detail = detail;
  }

  public static BgContributionResult resolved(List<BgContributionLine> lines) {
    return new BgContributionResult(Status.RESOLVED, lines, null);
  }

  /** Lines are usable but the data is incomplete — resolved yet blocking. */
  public static BgContributionResult resolvedIncomplete(
      List<BgContributionLine> lines, String detail) {
    return new BgContributionResult(Status.RESOLVED_INCOMPLETE, lines, detail);
  }

  public static BgContributionResult failure(Status status, String detail) {
    return new BgContributionResult(status, Collections.emptyList(), detail);
  }

  public Status getStatus() {
    return status;
  }

  public List<BgContributionLine> getLines() {
    return Collections.unmodifiableList(lines);
  }

  public String getDetail() {
    return detail;
  }

  /** True when lines are present and usable (fully resolved or resolved-but-incomplete). */
  public boolean isResolved() {
    return status == Status.RESOLVED || status == Status.RESOLVED_INCOMPLETE;
  }

  /** True when the result must not be declared as-is — every status except a clean RESOLVED. */
  public boolean isBlocking() {
    return status != Status.RESOLVED;
  }

  /** Sum of employer-borne amounts. */
  public BigDecimal totalEmployer() {
    return sum(Payer.EMPLOYER);
  }

  /** Sum of insured-borne amounts. */
  public BigDecimal totalEmployee() {
    return sum(Payer.INSURED);
  }

  /** Employer plus insured. */
  public BigDecimal total() {
    return totalEmployer().add(totalEmployee());
  }

  private BigDecimal sum(Payer payer) {
    BigDecimal acc = BigDecimal.ZERO;
    for (BgContributionLine line : lines) {
      if (line.getPayer() == payer) {
        acc = acc.add(line.getAmount());
      }
    }
    return acc;
  }

  @Override
  public String toString() {
    return "BgContributionResult["
        + status
        + ", lines="
        + lines.size()
        + (detail != null ? ", " + detail : "")
        + "]";
  }
}
