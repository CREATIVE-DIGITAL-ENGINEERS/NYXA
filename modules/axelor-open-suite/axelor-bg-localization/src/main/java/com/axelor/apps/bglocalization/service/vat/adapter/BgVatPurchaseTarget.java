package com.axelor.apps.bglocalization.service.vat.adapter;

/**
 * Where one purchase amount pair (base + VAT) lands in the purchase diary: a base column, a VAT
 * column, or both. Shared by {@link BgVatPurchaseCreditTypeMapping} (line credit type) and {@link
 * BgVatMoveOperationRouting} (manual-move operation), so the two routes cannot drift apart.
 *
 * <p>Three shapes occur:
 *
 * <ul>
 *   <li>base + VAT in two columns (к.10/к.11, к.12/к.13);
 *   <li>VAT folded into the base column ({@link #isVatFoldedIntoBase()}: к.09 = ДО + данък);
 *   <li>one side only — the other column is {@code null} and that amount is NOT written anywhere
 *       (к.14 takes VAT only, к.15 base only, the чл. 79 ал. 7 / чл. 79а protocols VAT only).
 * </ul>
 */
public final class BgVatPurchaseTarget {

  /** к.10 / к.11 — право на пълен данъчен кредит. */
  public static final BgVatPurchaseTarget FULL_CREDIT =
      new BgVatPurchaseTarget(
          BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, false);

  /** к.12 / к.13 — право на частичен данъчен кредит. */
  public static final BgVatPurchaseTarget PARTIAL_CREDIT =
      new BgVatPurchaseTarget(
          BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT,
          BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT,
          false);

  /** к.09 — без право на данъчен кредит: ДО и данъкът в ЕДНА колона. */
  public static final BgVatPurchaseTarget NO_CREDIT =
      new BgVatPurchaseTarget(BgVatPurchaseColumn.K09_BASE_NO_CREDIT, null, true);

  /** к.11 само — данъчен кредит без получена доставка, тоест без ДО. */
  public static final BgVatPurchaseTarget FULL_CREDIT_VAT_ONLY =
      new BgVatPurchaseTarget(null, BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, false);

  /** к.14 само — годишна корекция по чл. 73 ал. 8, със знак. */
  public static final BgVatPurchaseTarget ANNUAL_ADJUSTMENT =
      new BgVatPurchaseTarget(null, BgVatPurchaseColumn.K14_ANNUAL_ADJUSTMENT, false);

  /** к.15 само — ДО при тристранна операция; ДДС не се пише в нито една колона. */
  public static final BgVatPurchaseTarget TRIANGULAR =
      new BgVatPurchaseTarget(BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ, null, false);

  private final BgVatPurchaseColumn baseColumn;
  private final BgVatPurchaseColumn vatColumn;
  private final boolean vatFoldedIntoBase;

  private BgVatPurchaseTarget(
      BgVatPurchaseColumn baseColumn, BgVatPurchaseColumn vatColumn, boolean vatFoldedIntoBase) {
    this.baseColumn = baseColumn;
    this.vatColumn = vatColumn;
    this.vatFoldedIntoBase = vatFoldedIntoBase;
  }

  /** Base column, or {@code null} when the base is not reported. */
  public BgVatPurchaseColumn getBaseColumn() {
    return baseColumn;
  }

  /** VAT column, or {@code null} when VAT is folded into the base or not reported. */
  public BgVatPurchaseColumn getVatColumn() {
    return vatColumn;
  }

  /** {@code true} when the base column receives base + VAT (к.09). */
  public boolean isVatFoldedIntoBase() {
    return vatFoldedIntoBase;
  }
}
