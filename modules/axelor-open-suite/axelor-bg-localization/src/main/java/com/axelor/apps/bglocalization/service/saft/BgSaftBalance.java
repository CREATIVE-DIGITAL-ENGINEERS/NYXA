package com.axelor.apps.bglocalization.service.saft;

import java.math.BigDecimal;

/**
 * Суровите суми на една сметка, както ги връща базата: началното салдо (дебит положителен) и
 * дебитният и кредитният оборот в периода.
 *
 * <p>⚠️ НЕЗАКРЪГЛЕНИ. {@code MoveLine.debit/credit} са с ТРИ знака след запетаята, а схемата
 * допуска два. Закръгляването е правило и затова е в assembler-а — с находка, когато промени сума.
 */
public final class BgSaftBalance {

  private final BigDecimal opening;
  private final BigDecimal debit;
  private final BigDecimal credit;

  public BgSaftBalance(BigDecimal opening, BigDecimal debit, BigDecimal credit) {
    this.opening = opening == null ? BigDecimal.ZERO : opening;
    this.debit = debit == null ? BigDecimal.ZERO : debit;
    this.credit = credit == null ? BigDecimal.ZERO : credit;
  }

  public static BgSaftBalance opening(BigDecimal opening) {
    return new BgSaftBalance(opening, null, null);
  }

  public static BgSaftBalance turnover(BigDecimal debit, BigDecimal credit) {
    return new BgSaftBalance(null, debit, credit);
  }

  /** Сумата на две части за една и съща сметка — началното салдо и оборотът идват поотделно. */
  public BgSaftBalance plus(BgSaftBalance other) {
    return new BgSaftBalance(
        opening.add(other.opening), debit.add(other.debit), credit.add(other.credit));
  }

  public BigDecimal opening() {
    return opening;
  }

  public BigDecimal debit() {
    return debit;
  }

  public BigDecimal credit() {
    return credit;
  }
}
