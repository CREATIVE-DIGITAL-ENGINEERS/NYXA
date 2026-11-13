package com.axelor.apps.bglocalization.service.accounting;

import java.math.BigDecimal;

/**
 * ЕДИН РЕД ОТ СЧЕТОВОДНАТА СТАТИЯ — сметка, аналитичност и сумата по нея в едната посока.
 *
 * <p>⚠️ Получава се от СБИРАНЕТО на двойките ({@link BgPayrollPostingEntry}) по сметка, посока и
 * аналитичност. Двайсет пера дават четири-пет реда — точно както се пише статия на ръка. Един ред
 * на перо би дал вярна, но нечетима статия: сметка 4211 щеше да се появи девет пъти в един
 * документ.
 *
 * <p>⚠️ Дебитът и кредитът НЕ се нетират в едно число. Сметка 4211 стои от двете страни на една
 * статия — кредитира се с брутото и се дебитира с вноските, данъка и удръжките — и нетирана, тя би
 * скрила оборота, по който се сверява ведомостта. Затова редовете са ДВА: 4211 Cr и 4211 Dr.
 *
 * <p><b>⚠️ АНАЛИТИЧНОСТТА Е ЧАСТ ОТ КЛЮЧА НА СБИРАНЕТО, А НЕ УКРАСА НА РЕДА.</b> Удръжките по 8xx
 * влизат всички в 4214, но се ПРЕВЕЖДАТ поотделно — запор на взискател, заем на банка, издръжка на
 * издържания, глоба на държавен орган. Слети в един кредит на 4214, салдото става несверимо с нито
 * едно платежно нареждане. Затова две удръжки с различна аналитичност дават ДВА реда, а две с
 * еднаква — един.
 *
 * <p>⚠️ И ЗАЩО НЕ Е {@code AnalyticMoveLine}. Аналитичното разпределение на Axelor иска сметката да
 * е с {@code analyticDistributionAuthorized}, а 4214 в BG_COA е с ИЗРИЧНО {@code false} — тоест
 * аналитичен ред там би бил отказан при запис. Аналитичността тук живее в описанието на реда, което
 * счетоводителят чете, и в разцепването на самия ред. Вдигне ли се флагът в сметкоплана, тази
 * стойност е точно входът за истинско аналитично разпределение.
 */
public final class BgPayrollPosting {

  private final String accountCode;
  private final boolean debit;
  private final BigDecimal amount;
  private final String analytic;

  public BgPayrollPosting(String accountCode, boolean debit, BigDecimal amount) {
    this(accountCode, debit, amount, null);
  }

  public BgPayrollPosting(String accountCode, boolean debit, BigDecimal amount, String analytic) {
    this.accountCode = accountCode;
    this.debit = debit;
    this.amount = amount;
    this.analytic = analytic;
  }

  public String getAccountCode() {
    return accountCode;
  }

  /** {@code true} = дебит, {@code false} = кредит. */
  public boolean isDebit() {
    return debit;
  }

  public BigDecimal getAmount() {
    return amount;
  }

  /** Видът удръжка (запор, заем, издръжка, глоба, членски внос, влог), или {@code null}. */
  public String getAnalytic() {
    return analytic;
  }

  /** Ключът, по който редовете се сбират: сметка + посока + аналитичност. */
  public String key() {
    return accountCode + (debit ? "|D|" : "|C|") + (analytic == null ? "" : analytic);
  }

  @Override
  public String toString() {
    return (debit ? "Dr " : "Cr ")
        + accountCode
        + (analytic == null ? "" : " · " + analytic)
        + " "
        + amount;
  }
}
