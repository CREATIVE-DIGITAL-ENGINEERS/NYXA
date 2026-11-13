package com.axelor.apps.bglocalization.service.stock;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Двигателят на себестойността при изписване (ИЗП). Чиста функция: само числа, без база.
 *
 * <p><b>Стойност, не цена</b> (решение от 08.10.2026). Слоят пази количество и СТОЙНОСТ. Изходът
 * взема {@code стойност × изх. к-во / к-во}, закръглено HALF_UP до 0,01; изходът, който изчерпва
 * слоя, взема ЦЯЛАТА остатъчна стойност — в слоя не остават стотинки. Средната единична цена само
 * се показва и не участва в сметката.
 *
 * <p>Затова сумата на изписаното плюс остатъкът е точно сумата на постъпленията — до стотинка.
 * Формулата на Axelor (единична цена до {@code nbDecimalDigitForUnitPrice} на всеки вход) би дала
 * за примера 100 × 10,00 + 50 × 12,00, изход 120: 10,67 × 120 = 1 280,40 и остатък 320,10, тоест 1
 * 600,50 срещу вход 1 600,00 — и твърдата сверка с 30x би паднала по стотинки.
 *
 * <p>Средната претеглена след всяко постъпление и FIFO/LIFO по слоеве. Конкретната стойност и
 * периодичната средна идват по-късно със същия слой.
 */
public final class BgStockCostEngine {

  /** Стотинки: сумите на статиите са до 0,01. */
  public static final int AMOUNT_SCALE = 2;

  /** Само за показване на средната единична цена. */
  public static final int UNIT_COST_DISPLAY_SCALE = 6;

  private BgStockCostEngine() {}

  /** Слой: количество и стойност. Средната единична цена е производна и само за показване. */
  public static final class Layer {
    private BigDecimal qty;
    private BigDecimal value;

    public Layer(BigDecimal qty, BigDecimal value) {
      this.qty = nz(qty);
      this.value = nz(value).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
    }

    public static Layer empty() {
      return new Layer(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public BigDecimal qty() {
      return qty;
    }

    public BigDecimal value() {
      return value;
    }

    /** Средната единична цена — само за показване; {@code null} при празен слой. */
    public BigDecimal unitCost() {
      return qty.signum() == 0
          ? null
          : value.divide(qty, UNIT_COST_DISPLAY_SCALE, RoundingMode.HALF_UP);
    }
  }

  /** Резултатът от изписване: сумата или недостигът. */
  public static final class Issue {
    private final BigDecimal amount;
    private final BigDecimal shortage;
    private final List<Layer> taken;

    Issue(BigDecimal amount, BigDecimal shortage) {
      this(amount, shortage, Collections.<Layer>emptyList());
    }

    Issue(BigDecimal amount, BigDecimal shortage, List<Layer> taken) {
      this.amount = amount;
      this.shortage = shortage;
      this.taken = taken;
    }

    /** Колко и на каква стойност е взето от всеки слой, по реда на изчерпване. */
    public List<Layer> taken() {
      return taken;
    }

    /** Себестойността на изхода — винаги неотрицателна; {@code null} при недостиг. */
    public BigDecimal amount() {
      return amount;
    }

    /** Колко липсва в слоя; нула, когато изписването е минало. */
    public BigDecimal shortage() {
      return shortage;
    }

    public boolean isShort() {
      return shortage.signum() > 0;
    }
  }

  /**
   * Стойността на постъпление: количество × единична цена, HALF_UP до 0,01. Цената идва от реда
   * (модел A: цената от поръчката при приемане).
   */
  public static BigDecimal valueOf(BigDecimal qty, BigDecimal unitPrice) {
    return nz(qty).multiply(nz(unitPrice)).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  /** Средна след всяко постъпление: постъплението се слива в слоя по количество и стойност. */
  public static void receive(Layer layer, BigDecimal qty, BigDecimal value) {
    requireNonNegative(qty, "количество");
    layer.qty = layer.qty.add(qty);
    layer.value = layer.value.add(nz(value).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP));
  }

  /**
   * Средна след всяко постъпление: изходът взема дял от стойността, а изчерпващият — целия остатък.
   * При недостиг слоят не се пипа и сумата е {@code null} — изписването остава неосчетоводено с
   * грешка (решение 4), а не отрицателен слой.
   */
  public static Issue issue(Layer layer, BigDecimal qty) {
    requireNonNegative(qty, "количество");
    BigDecimal shortage = qty.subtract(layer.qty);
    if (shortage.signum() > 0) {
      return new Issue(null, shortage);
    }
    BigDecimal amount = share(layer.value, qty, layer.qty);
    layer.qty = layer.qty.subtract(qty);
    layer.value = layer.value.subtract(amount);
    return new Issue(amount, BigDecimal.ZERO);
  }

  /**
   * FIFO (СС 2 т. 9.2 а) или LIFO (т. 10): изходът изчерпва слоевете по реда на постъпване — от
   * най-стария при FIFO, от най-новия при LIFO. Всеки слой отдава дял от стойността си по същото
   * правило като средната ({@link #issue(Layer, BigDecimal)}): изчерпаният — цялата.
   *
   * <p>При недостиг нито един слой не се пипа и сумата е {@code null}.
   */
  public static Issue issue(List<Layer> layers, BigDecimal qty, boolean lifo) {
    requireNonNegative(qty, "количество");
    BigDecimal available = BigDecimal.ZERO;
    for (Layer layer : layers) {
      available = available.add(layer.qty);
    }
    BigDecimal shortage = qty.subtract(available);
    if (shortage.signum() > 0) {
      return new Issue(null, shortage);
    }
    List<Layer> taken = new ArrayList<>();
    BigDecimal amount = BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    BigDecimal left = qty;
    for (int i = 0; i < layers.size() && left.signum() > 0; i++) {
      Layer layer = layers.get(lifo ? layers.size() - 1 - i : i);
      if (layer.qty.signum() == 0) {
        continue;
      }
      BigDecimal take = left.min(layer.qty);
      BigDecimal part = issue(layer, take).amount();
      taken.add(new Layer(take, part));
      amount = amount.add(part);
      left = left.subtract(take);
    }
    return new Issue(amount, BigDecimal.ZERO, taken);
  }

  /**
   * Слоевете в един: количество и стойност се събират. Смяна FIFO/LIFO → средна от началото на
   * годината (решение 5): един слой със същата стойност, без статия.
   */
  public static Layer merge(List<Layer> layers) {
    Layer merged = Layer.empty();
    for (Layer layer : layers) {
      receive(merged, layer.qty, layer.value);
    }
    return merged;
  }

  /**
   * Делът на {@code take} от слой с количество {@code qty} и стойност {@code value}: цялата
   * стойност при изчерпване, иначе пропорционално, HALF_UP до 0,01.
   */
  static BigDecimal share(BigDecimal value, BigDecimal take, BigDecimal qty) {
    if (take.signum() == 0) {
      return BigDecimal.ZERO.setScale(AMOUNT_SCALE);
    }
    if (take.compareTo(qty) == 0) {
      return value;
    }
    return value.multiply(take).divide(qty, AMOUNT_SCALE, RoundingMode.HALF_UP);
  }

  private static void requireNonNegative(BigDecimal qty, String what) {
    if (qty == null || qty.signum() < 0) {
      throw new IllegalArgumentException("Отрицателно или празно " + what + ": " + qty);
    }
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
