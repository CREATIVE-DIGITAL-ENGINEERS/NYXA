package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * РЕДЪТ В ДНЕВНИКА → КЛЕТКИ НА SAF-T (G5b) — чиста функция. Кодът на всяка клетка идва от {@code
 * BgSafVatCellCode} по ключа ѝ ({@link BgSaftVatCell#key}); тук се решава само КОЯ е клетката.
 *
 * <p><b>⚠️ КОЛОНАТА В ДНЕВНИКА НЕ СТИГА.</b> Кодът = колоната + к.8а + ставката:
 *
 * <ul>
 *   <li><b>к.13/к.15 (ВОП)</b> → 02-12 при 20 %, 02-20 при 9 % (TAX-IMP 100212/100220). Дневникът
 *       не дели ВОП по ставка, затова ставката е тази на редовете на статията, а к.15/к.13 е само
 *       контрола. Смесени ставки — по един ред на ставка, сборовете са клетките на дневника.
 *   <li><b>к.14/к.15</b> → по к.8а: „01“/„02“ → 02-27[01]/[02] (100227/100228, чл. 163а — ППЗДДС
 *       чл. 97а ал. 1 и ал. 4 б. „а“); иначе 02-26 (100226, чл. 82). Протокол по чл. 163а без к.8а
 *       е ГРЕШКА: ал. 1 я иска задължително.
 *   <li><b>к.11/к.12 с к.8а „03“</b> → 02-29 (100229, внос по чл. 167а — QA с. 60; ППЗДДС чл. 49а
 *       ал. 4 т. 1). Кодът е за 20 %: друга ставка е ГРЕШКА.
 *   <li><b>к.12 без к.11</b> → 02-11 с основа 0 (100211, QA с. 80): протоколите по ППЗДДС чл. 66
 *       ал. 3 стоят в к.12 „… начислен данък (20 %), предвиден в закона в други случаи“. При ставка
 *       9 % → 02-13 (100213): QA с. 80 дава „100211 … или 100213 … в зависимост от приложимата
 *       ставка“ (G6a, решение 4 от 14.10.2026).
 *   <li><b>к.16 (лични нужди)</b> → ГРЕШКА: в дневника е само данъкът (ППЗДДС чл. 113а), а 100223/
 *       100224 са за основата, която дневникът няма.
 *   <li><b>Покупки</b>: к.9 → 03-30, к.10/к.11 → 03-31, к.12/к.13 → 03-32, к.15 → 03-44. к.11 без
 *       к.10 (ППЗДДС чл. 66 ал. 9 — DEDUCTION_79, ANNUAL_79A) е 03-31 с основа 0. к.14 (чл. 73 ал.
 *       8) → 03-43 — полето на POKUPKI по Прил. № 12 — а в {@code BgSafVatCellCode} 03-43 → 100242
 *       (изключение от „100 + номер“), с основа 0 и знака от дневника.
 *   <li><b>Покупната страна на протокола по чл. 163а и по чл. 167а</b> — по отговора на НАП по
 *       имейл от 19.10.2026: чл. 163а → 03-45 по к.8а („01“ → 100346 за част I на Прил. № 2, „02“ →
 *       100345 за част II), чл. 167а (к.8а „03“) → 03-47 (100347). ЕДНА клетка с основата на
 *       протокола и S.TI.6 = ПОЛЗВАНИЯТ данъчен кредит (к.11 + к.13): „в зависимост от това дали е
 *       налице право данъчен кредит (пълен или частичен)“; без кредит — 0. За 100347 ставката 20 %
 *       в номенклатурата не е пречка: „подаден протокол, в който … не е ползвано право на данъчен
 *       кредит или е ползван частичен данъчен кредит ще се приеме“.
 * </ul>
 *
 * <p><b>⚠️ к.9 СГЪВА ОСНОВА И ДАНЪК</b> (ППЗДДС Прил. № 11). При протокол основата и данъкът му са
 * известни от продажбената страна (ЗДДС чл. 117 ал. 2 т. 5 и т. 8), тоест частта в к.9 се разделя
 * точно: основа = основата на протокола − к.10 − к.12, данък = данъкът − к.11 − к.13. Без протокол
 * клетката се подава цяла, с данък 0.
 */
public final class BgSaftDiaryCells {

  /** Покупната страна на протокола по чл. 163а — 03-45 + к.8а (отговорът на НАП, 19.10.2026). */
  static final String ART163A_PURCHASE = "03-45";

  /** Покупната страна на протокола по чл. 167а — 03-47 (отговорът на НАП, 19.10.2026). */
  static final String IMPORT_167A_PURCHASE = "03-47";

  /** к.14 на POKUPKI „Годишна корекция по чл. 73, ал. 8“ — поле 03-43 по Прил. № 12. */
  static final String ANNUAL_PURCHASE_CELL = "03-43";

  static final String VOP_20 = "02-12";
  static final String VOP_9 = "02-20";
  static final String RC_ART82 = "02-26";
  static final String ART163A = "02-27";
  static final String IMPORT_167A = "02-29";
  static final String BASE_20 = "02-11";

  /**
   * ДО на облагаемите доставки със ставка 9 % — корекцията по чл. 79 ал. 1–3 при 9 % (QA с. 80).
   */
  static final String BASE_9 = "02-13";

  static final String SUPPLY_163A_PART_I = "01";
  static final String SUPPLY_163A_PART_II = "02";
  static final String SUPPLY_167A = "03";

  static final BigDecimal RATE_20 = new BigDecimal("0.20");
  static final BigDecimal RATE_9 = new BigDecimal("0.09");

  /** Контролата к.15 спрямо к.13 × ставката: базата на колектора е данък / ставка до стотинка. */
  static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  private static final int SCALE = 2;

  private BgSaftDiaryCells() {}

  // ─────────────── ставката ───────────────

  /** Ред на статията — само каквото трябва за ставката. */
  public static final class RateLine {
    private final BigDecimal taxRatePercent;
    private final BigDecimal amount;
    private final boolean vatAccount;

    /**
     * @param taxRatePercent {@code MoveLine.taxRate} — в проценти (20)
     * @param amount дебит − кредит
     * @param vatAccount редът е по 4531/4532 — не е носител на основа
     */
    public RateLine(BigDecimal taxRatePercent, BigDecimal amount, boolean vatAccount) {
      this.taxRatePercent = taxRatePercent == null ? BigDecimal.ZERO : taxRatePercent;
      this.amount = amount == null ? BigDecimal.ZERO : amount;
      this.vatAccount = vatAccount;
    }
  }

  /** Ставките на статията с тежестите им, или защо не се определят еднозначно. */
  public static final class Rates {
    private final Map<BigDecimal, BigDecimal> weights;
    private final String error;

    private Rates(Map<BigDecimal, BigDecimal> weights, String error) {
      this.weights = weights;
      this.error = error;
    }

    /** Една ставка — дробта; иначе {@code null}. */
    public BigDecimal single() {
      return error == null && weights.size() == 1 ? weights.keySet().iterator().next() : null;
    }

    public String getError() {
      return error;
    }
  }

  /**
   * Ставката на протокола — от редовете на статията ({@code MoveLine.taxRate}), а ръчната ({@code
   * Move.bgVatMoveVatRate}) само когато редовете нямат ставка или казват същото. Различни ставки по
   * редовете са позволени, ако носителите им на основа ги претеглят; ръчна ставка до тях е
   * нееднозначност.
   */
  public static Rates rates(List<RateLine> lines, BigDecimal manualRate) {
    Map<BigDecimal, BigDecimal> weights = new LinkedHashMap<>();
    for (RateLine line : lines) {
      if (line.taxRatePercent.signum() == 0) {
        continue;
      }
      BigDecimal rate = fraction(line.taxRatePercent.movePointLeft(2));
      BigDecimal weight = line.vatAccount ? BigDecimal.ZERO : line.amount;
      weights.merge(rate, weight, BigDecimal::add);
    }
    BigDecimal manual =
        manualRate == null || manualRate.signum() == 0
            ? null
            : fraction(
                manualRate.compareTo(BigDecimal.ONE) > 0
                    ? manualRate.movePointLeft(2)
                    : manualRate);
    if (weights.isEmpty()) {
      return manual == null
          ? failure("ставката не се определя — нито редовете, нито протоколът я носят")
          : new Rates(Collections.singletonMap(manual, BigDecimal.ONE), null);
    }
    if (weights.size() == 1) {
      BigDecimal rate = weights.keySet().iterator().next();
      if (manual != null && manual.compareTo(rate) != 0) {
        return failure(
            "ставката не е еднозначна — ръчна " + plain(manual) + ", на редовете " + plain(rate));
      }
      return new Rates(Collections.singletonMap(rate, BigDecimal.ONE), null);
    }
    if (manual != null) {
      return failure(
          "ставката не е еднозначна — ръчна " + plain(manual) + " до няколко ставки на редовете");
    }
    for (Map.Entry<BigDecimal, BigDecimal> entry : weights.entrySet()) {
      if (entry.getValue().signum() == 0) {
        return failure(
            "смесени ставки, а ставка "
                + plain(entry.getKey())
                + " няма ред с основа, който да я претегли");
      }
    }
    return new Rates(weights, null);
  }

  private static Rates failure(String error) {
    return new Rates(Collections.emptyMap(), error);
  }

  // ─────────────── клетките ───────────────

  /** Една клетка на SAF-T: ключът ѝ в {@code BgSafVatCellCode}, основа, данък и ставка. */
  public static final class Cell {
    private final String key;
    private final BigDecimal base;
    private final BigDecimal tax;
    private final BigDecimal rate;

    Cell(String key, BigDecimal base, BigDecimal tax, BigDecimal rate) {
      this.key = key;
      this.base = base;
      this.tax = tax;
      this.rate = rate;
    }

    public String getKey() {
      return key;
    }

    /** Основата — със знака от дневника. */
    public BigDecimal getBase() {
      return base;
    }

    /** Данъкът — със знака от дневника. */
    public BigDecimal getTax() {
      return tax;
    }

    /** Ставката като дроб, ако е еднозначна; иначе {@code null}. */
    public BigDecimal getRate() {
      return rate;
    }
  }

  /** Клетките на едната страна, грешките и предупрежденията. */
  public static final class Coding {
    private final List<Cell> cells = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    public List<Cell> getCells() {
      return cells;
    }

    public List<String> getErrors() {
      return errors;
    }

    public List<String> getWarnings() {
      return warnings;
    }

    private void cell(String key, BigDecimal base, BigDecimal tax, BigDecimal rate) {
      cells.add(new Cell(key, base.setScale(SCALE), tax.setScale(SCALE), rate));
    }
  }

  /** Протоколите по чл. 163а — к.8а е задължителна (ППЗДДС чл. 97а ал. 1). */
  static final Set<String> ART163A_OPERATIONS = Set.of("ART163A");

  /** Операциите, чийто код зависи от ставката. */
  static final Set<String> VOP_OPERATIONS = Set.of("VOP");

  /**
   * Продажбената страна: колоните на реда в PRODAGBI.
   *
   * @param columns колоните на реда без тоталите
   * @param supplyType к.8а на реда
   * @param operation ДДС операцията на статията
   * @param rates ставките на статията
   */
  public static Coding sales(
      Map<BgVatSalesColumn, BigDecimal> columns, String supplyType, String operation, Rates rates) {
    Coding coding = new Coding();
    BigDecimal k11 = get(columns, BgVatSalesColumn.K11_BASE_20);
    BigDecimal k12 = get(columns, BgVatSalesColumn.K12_VAT_20);
    BigDecimal k13 = get(columns, BgVatSalesColumn.K13_BASE_ICA);
    BigDecimal k14 = get(columns, BgVatSalesColumn.K14_BASE_RC);
    BigDecimal k15 = get(columns, BgVatSalesColumn.K15_VAT_ICA_RC);
    BigDecimal k16 = get(columns, BgVatSalesColumn.K16_VAT_SELF_SUPPLY);
    String supply = supplyType == null ? "" : supplyType.trim();
    String op = operation == null ? "" : operation.trim();

    if (k16.signum() != 0) {
      coding.errors.add(
          "к.16 (данък за лични нужди, ППЗДДС чл. 113а) — дневникът няма основата, а 100223/100224"
              + " са за нея");
    }

    // к.15 е данъкът и на ВОП (к.13), и на чл. 82/163а (к.14) — двете заедно не се делят.
    boolean vop = k13.signum() != 0 || (k14.signum() == 0 && VOP_OPERATIONS.contains(op));
    if (k13.signum() != 0 && k14.signum() != 0) {
      coding.errors.add("к.13 и к.14 в един ред — к.15 не се дели между ВОП и чл. 82/163а");
    } else if (vop && (k13.signum() != 0 || k15.signum() != 0)) {
      vop(coding, k13, k15, rates);
    } else if (k14.signum() != 0 || k15.signum() != 0) {
      String key;
      if (SUPPLY_163A_PART_I.equals(supply) || SUPPLY_163A_PART_II.equals(supply)) {
        key = BgSaftVatCell.key(ART163A, supply);
      } else if (ART163A_OPERATIONS.contains(op)) {
        coding.errors.add(
            "протокол по чл. 163а без к.8а „01“/„02“ (ППЗДДС чл. 97а ал. 1) — 100227 срещу 100228"
                + " не се знае");
        key = null;
      } else {
        key = RC_ART82;
      }
      if (key != null) {
        coding.cell(key, k14, k15, rates.single());
      }
    }

    boolean k11k12 = k11.signum() != 0 || k12.signum() != 0;
    if (k11k12 && SUPPLY_167A.equals(supply)) {
      BigDecimal rate = rates.single();
      if (rates.getError() != null) {
        coding.errors.add("внос по чл. 167а: " + rates.getError());
      } else if (rate == null || rate.compareTo(RATE_20) != 0) {
        coding.errors.add(
            "внос по чл. 167а със ставка "
                + (rate == null ? "—" : plain(rate))
                + " — 100229 е за 20 % (ППЗДДС чл. 49а ал. 4 т. 1: колони 11/12)");
      } else {
        coding.cell(BgSaftVatCell.key(IMPORT_167A, null), k11, k12, rate);
      }
    } else if (k11k12) {
      // Корекцията по чл. 79 ал. 1–3 (основа 0, данък в к.12): кодът е по ставката — QA с. 80,
      // дословно „100211 … или код „100213 … в зависимост от приложимата ставка“ (решение 4).
      boolean correction9 =
          k11.signum() == 0 && rates.single() != null && rates.single().compareTo(RATE_9) == 0;
      coding.cell(correction9 ? BASE_9 : BASE_20, k11, k12, rates.single());
    }

    // Останалите колони за основа — по клетката си, без данък (дневникът ги пълни само с основа).
    for (Map.Entry<BgVatSalesColumn, BigDecimal> entry : columns.entrySet()) {
      BgVatSalesColumn column = entry.getKey();
      if (entry.getValue().signum() == 0 || HANDLED_SALES.contains(column)) {
        continue;
      }
      String cell = BgSaftVatCell.ofSales(column);
      if (cell == null || SALES_VAT.contains(column)) {
        coding.errors.add("колона к." + column.name() + " без правило за SAF-T");
      } else {
        coding.cell(cell, entry.getValue(), BigDecimal.ZERO, null);
      }
    }
    return coding;
  }

  private static final Set<BgVatSalesColumn> HANDLED_SALES =
      Set.of(
          BgVatSalesColumn.K11_BASE_20,
          BgVatSalesColumn.K12_VAT_20,
          BgVatSalesColumn.K13_BASE_ICA,
          BgVatSalesColumn.K14_BASE_RC,
          BgVatSalesColumn.K15_VAT_ICA_RC,
          BgVatSalesColumn.K16_VAT_SELF_SUPPLY,
          BgVatSalesColumn.K09_TOTAL_BASE,
          BgVatSalesColumn.K10_TOTAL_VAT);

  private static final Set<BgVatSalesColumn> SALES_VAT = Set.of(BgVatSalesColumn.K18_VAT_9);

  /** ВОП: по един ред на ставка; сборовете са к.13 и к.15, а к.15 ≈ к.13 × ставката. */
  private static void vop(Coding coding, BigDecimal k13, BigDecimal k15, Rates rates) {
    if (rates.getError() != null) {
      coding.errors.add("ВОП (100212 срещу 100220): " + rates.getError());
      return;
    }
    List<BigDecimal> list = new ArrayList<>(rates.weights.keySet());
    BigDecimal total = BigDecimal.ZERO;
    for (BigDecimal weight : rates.weights.values()) {
      total = total.add(weight);
    }
    if (total.signum() == 0) {
      coding.errors.add("ВОП: тежестите на ставките се нулират — делът не се определя");
      return;
    }
    BigDecimal baseLeft = k13;
    BigDecimal taxLeft = k15;
    List<String> keys = new ArrayList<>();
    for (BigDecimal rate : list) {
      if (rate.compareTo(RATE_20) == 0) {
        keys.add(VOP_20);
      } else if (rate.compareTo(RATE_9) == 0) {
        keys.add(VOP_9);
      } else {
        coding.errors.add(
            "ВОП със ставка " + plain(rate) + " — TAX-IMP има код само за 20 % и 9 %");
        return;
      }
    }
    List<Cell> cells = new ArrayList<>();
    for (int i = 0; i < list.size(); i++) {
      BigDecimal rate = list.get(i);
      BigDecimal base = baseLeft;
      BigDecimal tax = taxLeft;
      if (i < list.size() - 1) {
        base = k13.multiply(rates.weights.get(rate)).divide(total, SCALE, RoundingMode.HALF_UP);
        tax = base.multiply(rate).setScale(SCALE, RoundingMode.HALF_UP);
        baseLeft = baseLeft.subtract(base);
        taxLeft = taxLeft.subtract(tax);
      }
      if (tax.subtract(base.multiply(rate)).abs().compareTo(TOLERANCE) > 0) {
        coding.errors.add(
            "ВОП: к.15 "
                + plain(k15)
                + " не съответства на к.13 "
                + plain(k13)
                + " при ставка "
                + plain(rate)
                + " — дневникът и ставката на редовете се разминават");
        return;
      }
      cells.add(new Cell(keys.get(i), base.setScale(SCALE), tax.setScale(SCALE), rate));
    }
    coding.cells.addAll(cells);
  }

  /**
   * Покупната страна: колоните на реда в POKUPKI.
   *
   * @param protocolBase основата на протокола (продажбената му страна) или {@code null}
   * @param protocolTax данъкът на протокола или {@code null}
   */
  public static Coding purchases(
      Map<BgVatPurchaseColumn, BigDecimal> columns,
      String supplyType,
      String operation,
      Rates rates,
      BigDecimal protocolBase,
      BigDecimal protocolTax) {
    Coding coding = new Coding();
    BigDecimal k09 = get(columns, BgVatPurchaseColumn.K09_BASE_NO_CREDIT);
    BigDecimal k10 = get(columns, BgVatPurchaseColumn.K10_BASE_FULL_CREDIT);
    BigDecimal k11 = get(columns, BgVatPurchaseColumn.K11_VAT_FULL_CREDIT);
    BigDecimal k12 = get(columns, BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT);
    BigDecimal k13 = get(columns, BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT);
    BigDecimal k14 = get(columns, BgVatPurchaseColumn.K14_ANNUAL_ADJUSTMENT);
    BigDecimal k15 = get(columns, BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ);
    BigDecimal rate = rates == null ? null : rates.single();
    String supply = supplyType == null ? "" : supplyType.trim();
    String op = operation == null ? "" : operation.trim();

    // к.9 — частта без кредит. При протокол основата и данъкът ѝ са известни от продажбената
    // страна, тоест к.9 се проверява срещу тях.
    BigDecimal baseNoCredit = k09;
    BigDecimal taxNoCredit = BigDecimal.ZERO;
    if (k09.signum() != 0 && protocolBase != null && protocolTax != null) {
      baseNoCredit = protocolBase.subtract(k10).subtract(k12);
      taxNoCredit = protocolTax.subtract(k11).subtract(k13);
      if (baseNoCredit.add(taxNoCredit).compareTo(k09) != 0) {
        coding.errors.add(
            "к.9 "
                + plain(k09)
                + " не е частта от протокола без кредит ("
                + plain(baseNoCredit)
                + " + "
                + plain(taxNoCredit)
                + ")");
        return coding;
      }
    }

    // Протоколът по чл. 163а и по чл. 167а — една клетка: основата на протокола, данъкът е
    // ползваният кредит (отговорът на НАП по имейл от 19.10.2026).
    String creditCell = null;
    if (ART163A_OPERATIONS.contains(op)) {
      if (SUPPLY_163A_PART_I.equals(supply) || SUPPLY_163A_PART_II.equals(supply)) {
        creditCell = BgSaftVatCell.key(ART163A_PURCHASE, supply);
      } else {
        coding.errors.add(
            "протокол по чл. 163а без к.8а „01“/„02“ (ППЗДДС чл. 97а ал. 1) — 100346 срещу 100345"
                + " не се знае");
        return coding;
      }
    } else if (SUPPLY_167A.equals(supply)) {
      creditCell = BgSaftVatCell.key(IMPORT_167A_PURCHASE, null);
    }
    if (creditCell != null) {
      if (protocolBase == null) {
        coding.errors.add(
            "протокол по чл. 163а/167а без продажбена страна — основата на протокола не е известна");
        return coding;
      }
      coding.cell(creditCell, protocolBase, k11.add(k13), rate);
    } else {
      if (k09.signum() != 0) {
        coding.cell(
            BgSaftVatCell.ofPurchaseColumn(BgVatPurchaseColumn.K09_BASE_NO_CREDIT),
            baseNoCredit,
            taxNoCredit,
            rate);
      }
      if (k10.signum() != 0 || k11.signum() != 0) {
        coding.cell(
            BgSaftVatCell.ofPurchaseColumn(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT),
            k10,
            k11,
            rate);
      }
      if (k12.signum() != 0 || k13.signum() != 0) {
        coding.cell(
            BgSaftVatCell.ofPurchaseColumn(BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT),
            k12,
            k13,
            rate);
      }
    }
    if (k14.signum() != 0) {
      coding.cell(ANNUAL_PURCHASE_CELL, BigDecimal.ZERO, k14, null);
    }
    if (k15.signum() != 0) {
      coding.cell(
          BgSaftVatCell.ofPurchaseColumn(BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ),
          k15,
          BigDecimal.ZERO,
          null);
    }
    return coding;
  }

  /**
   * ЕАД по чл. 167а в PurchaseInvoices: 100330 (QA с. 60), основата на протокола и данък 0 — в
   * дневника ЕАД е само с колони 1–8 (ППЗДДС чл. 49а ал. 5 т. 2), а данъкът е на протокола.
   */
  public static Coding customs(BigDecimal protocolBase) {
    Coding coding = new Coding();
    if (protocolBase == null || protocolBase.signum() == 0) {
      coding.errors.add("ЕАД без основа на протокола по чл. 167а в продажбите (к.11)");
    } else {
      coding.cell(CUSTOMS_CELL, protocolBase, BigDecimal.ZERO, null);
    }
    return coding;
  }

  /** ЕАД — „…вносът без право на данъчен кредит или без данък“ (QA с. 60). */
  static final String CUSTOMS_CELL = "03-30";

  // ─────────────── помощници ───────────────

  private static <K> BigDecimal get(Map<K, BigDecimal> columns, K column) {
    BigDecimal value = columns.get(column);
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal fraction(BigDecimal value) {
    BigDecimal v = value.stripTrailingZeros();
    return v.scale() < 2 ? v.setScale(2) : v;
  }

  static String plain(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }
}
