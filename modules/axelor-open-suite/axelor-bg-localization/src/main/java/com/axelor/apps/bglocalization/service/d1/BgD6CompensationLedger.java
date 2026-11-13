package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ОБЕЗЩЕТЕНИЯТА ПО ВИД 27 В ЕДНА Д6 — частите за месеца и данъкът им. Чиста функция: без база (
 * календарът идва отвън, през {@link SharesFn}).
 *
 * <p><b>ЗАЩО ОТДЕЛНО ОТ СУМИТЕ НА ДЕКЛАРАЦИЯТА.</b> Указанието към т. 8 на Д6 дава на вноските за
 * лицата по чл. 9 ал. 3 т. 5 КСО (обезщетението за оставане без работа) СВОЙ вид плащане — 6, — а
 * към т. 18 СВОЯ дата: „последния календарен ден от месеца, в който са изплатени обезщетенията за
 * оставане без работа“. Тоест във файла те са ОТДЕЛЕН блок, а данъкът им — в блока с код 8 за
 * месеца на удържане. Т. 10 … т. 16 на декларацията остават СБОРОВЕ за месеца (така ги иска чл. 2
 * ал. 4 срещу Д1); разделянето по блокове е на файла ({@link BgD6Blocks}) и се извежда оттук.
 *
 * <p><b>⚠️ НИЩО ОТ ТОВА НЕ СЕ ПАЗИ — изчислява се при всяко генериране и при всеки файл</b>, от
 * същите действащи фишове и със същия календар. Затова двете винаги виждат едно и също, без нова
 * колона в схемата.
 */
public final class BgD6CompensationLedger {

  /** Частите на едно обезщетение от един фиш — шевът към календара. */
  @FunctionalInterface
  public interface SharesFn {
    List<BgCompensationParts.Share> of(
        BgPaySlip slip, BgCompensationParts.Carried carried, List<BgCompensationParts.Carried> all);
  }

  /** Данъкът на едно обезщетение — удържан при изплащането му, тоест на месеца на изплащане. */
  public static final class TaxPart {
    private final String reference;
    private final LocalDate paidDate;
    private final BigDecimal amount;

    TaxPart(String reference, LocalDate paidDate, BigDecimal amount) {
      this.reference = reference;
      this.paidDate = paidDate;
      this.amount = amount;
    }

    public String getReference() {
      return reference;
    }

    public LocalDate getPaidDate() {
      return paidDate;
    }

    public BigDecimal getAmount() {
      return amount;
    }
  }

  private final List<BgCompensationParts.Share> inPeriod = new ArrayList<>();
  private final List<TaxPart> taxes = new ArrayList<>();

  private BgD6CompensationLedger() {}

  /** Без обезщетения по вид 27 — файлът е какъвто беше. */
  public static BgD6CompensationLedger none() {
    return new BgD6CompensationLedger();
  }

  /**
   * Частите за месеца на декларацията и данъкът на обезщетенията, носени от фишовете НА месеца.
   *
   * @param slips действащите фишове на месеца ЗАЕДНО с носителите от други месеци ({@code
   *     BgEffectivePaySlips#compensationCarriersFor})
   */
  public static BgD6CompensationLedger of(Period period, List<BgPaySlip> slips, SharesFn fn) {
    BgD6CompensationLedger ledger = new BgD6CompensationLedger();
    LocalDate on = period == null ? null : period.getFromDate();
    if (on == null || slips == null) {
      return ledger;
    }
    for (BgPaySlip slip : slips) {
      List<BgCompensationParts.Carried> carried = BgCompensationParts.carried(slip);
      if (carried.isEmpty()) {
        continue;
      }
      for (BgCompensationParts.Carried c : carried) {
        for (BgCompensationParts.Share share : fn.of(slip, c, carried)) {
          LocalDate month = share.getSegment().getFrom();
          if (month.getYear() == on.getYear() && month.getMonthValue() == on.getMonthValue()) {
            ledger.inPeriod.add(share);
          }
        }
      }
      // ⚠️ ДАНЪКЪТ Е САМО НА ФИШОВЕТЕ НА МЕСЕЦА: т. 16 на Д6 се смята от тях. Носителят от друг
      // месец е декларирал данъка си в своята Д6.
      if (BgD1Emitter.ownPeriod(slip, period)) {
        Map<BgCompensation, BigDecimal> totals = BgCompensationParts.taxTotals(slip, carried);
        for (BgCompensationParts.Carried c : carried) {
          ledger.taxes.add(
              new TaxPart(c.reference(), c.getDoc().getBgPaidDate(), totals.get(c.getDoc())));
        }
      }
    }
    return ledger;
  }

  public boolean isEmpty() {
    return inPeriod.isEmpty() && taxes.isEmpty();
  }

  /** Частите, чийто месец е месецът на декларацията. */
  public List<BgCompensationParts.Share> getInPeriod() {
    return Collections.unmodifiableList(inPeriod);
  }

  /** Данъкът на обезщетенията, носени от фишовете на месеца. */
  public List<TaxPart> getTaxes() {
    return Collections.unmodifiableList(taxes);
  }

  /** Вноските на частите за месеца по точки на Д6. */
  public Map<String, BigDecimal> contributionsByPoint() {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    for (BgCompensationParts.Share share : inPeriod) {
      for (Map.Entry<String, BigDecimal> e : share.d6ByPoint().entrySet()) {
        out.merge(e.getKey(), e.getValue(), BigDecimal::add);
      }
    }
    return out;
  }

  /** Сборът на данъка на обезщетенията — частта от т. 16, която НЕ е на заплатите. */
  public BigDecimal tax() {
    BigDecimal total = BigDecimal.ZERO;
    for (TaxPart t : taxes) {
      if (t.amount != null) {
        total = total.add(t.amount);
      }
    }
    return total;
  }
}
