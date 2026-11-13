package com.axelor.apps.bglocalization.service.vat;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * КУРСЪТ, ПО КОЙТО ОСНОВАТА НА ПРОТОКОЛА СЕ ПРЕВРЪЩА В ЕВРО — чиста стойност.
 *
 * <p>ЗДДС чл. 26 ал. 6 (изм. ДВ бр. 70 от 2024 г., в сила от 01.01.2026 г.): „Когато стойностите,
 * необходими за изчисляване на данъчната основа, са определени в чуждестранна валута, данъчната
 * основа се определя на базата на равностойността в евро на тази валута по последния обменен курс,
 * публикуван от Европейската централна банка към момента, в който данъкът става изискуем.
 * Конвертирането между валути, различни от еврото, се извършва, като се използва обменният курс на
 * всяка от тези валути към еврото.“
 *
 * <p>Редът в Axelor ({@code CurrencyConversionLine}) е „1 start = rate end“: прекият (USD → EUR) се
 * умножава, обратният (EUR → USD — така публикува ЕЦБ) се дели. Делението е направо до цент, без
 * междинна обратна стойност, която да губи знаци.
 */
public final class BgVatProtocolRate {

  /** Фактурата е във валутата на дружеството. */
  public static final BgVatProtocolRate SAME = new BgVatProtocolRate(BigDecimal.ONE, false, null);

  private final BigDecimal rate;
  private final boolean inverse;
  private final LocalDate date;
  private String currency;

  private BgVatProtocolRate(BigDecimal rate, boolean inverse, LocalDate date) {
    this.rate = rate;
    this.inverse = inverse;
    this.date = date;
  }

  /**
   * @param rate курсът на реда
   * @param inverse редът е от евро към валутата (EUR → USD)
   * @param date датата на реда ({@code CurrencyConversionLine.fromDate})
   */
  public static BgVatProtocolRate of(BigDecimal rate, boolean inverse, LocalDate date) {
    return new BgVatProtocolRate(rate, inverse, date);
  }

  /** Кодът на валутата — само за описанието. */
  public BgVatProtocolRate currency(String code) {
    this.currency = code;
    return this;
  }

  /** Сумата във валутата на фактурата → в евро, до цент (HALF_UP). */
  public BigDecimal toCompanyCurrency(BigDecimal amount) {
    if (this == SAME) {
      return amount.setScale(2, RoundingMode.HALF_UP);
    }
    return inverse
        ? amount.divide(rate, 2, RoundingMode.HALF_UP)
        : amount.multiply(rate).setScale(2, RoundingMode.HALF_UP);
  }

  /** Датата на курса; {@code null} при същата валута. */
  public LocalDate getDate() {
    return date;
  }

  public boolean isSame() {
    return this == SAME;
  }

  /** „курс на ЕЦБ 1 EUR = 1.08 USD от 2026-06-11“. */
  public String describe() {
    String code = currency == null ? "" : " " + currency;
    String plain = rate.stripTrailingZeros().toPlainString();
    return inverse
        ? "курс на ЕЦБ 1 EUR = " + plain + code + " от " + date
        : "курс на ЕЦБ 1" + code + " = " + plain + " EUR от " + date;
  }
}
