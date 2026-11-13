package com.axelor.apps.bglocalization.service.vat.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Maps a manual move's {@code bgVatMoveVatOperation} (selection {@code
 * bg.vat.move.operation.select}) to its route: which kind of handling it gets and which diary
 * columns it fills. Pure and stateless — the AMOUNTS (Σ по 4531/4532, base lines, the base reverse-
 * computed from the rate) depend on the move's lines and stay in the collector.
 *
 * <p>The lookup is EXACT: the collector trims the value first, and any other spelling — lower case
 * included — is an unknown operation.
 */
public final class BgVatMoveOperationRouting {

  /** How the collector computes the amounts for an operation. */
  public enum Kind {
    /** Base from the base lines, VAT from 4531, into {@link Route#getPurchaseTarget()}. */
    PURCHASE,
    /**
     * Self-assessment protocol: base reverse-computed from VAT / rate, output side by the operation
     * and input side by each line's credit type ({@link Route#isPurchaseByCreditType()}).
     */
    PROTOCOL,
    /** Протокол по чл. 79 ал. 4: charged tax from 4532, sales side only, no base. */
    ART79_CORRECTION,
    /** Протокол по чл. 79 ал. 7: deductible VAT from 4531, purchase side only, no base. */
    ART79_DEDUCTION,
    /** Протокол по чл. 79а/79б ал. 4: signed correction from 4531, purchase side only. */
    ART79A_ANNUAL
  }

  /** One operation's route. Columns that the operation does not fill are {@code null}. */
  public static final class Route {
    private final Kind kind;
    private final BgVatPurchaseTarget purchaseTarget;
    private final BgVatSalesColumn salesBaseColumn;
    private final BgVatSalesColumn salesVatColumn;
    private final String supplyType;
    private final boolean customsDocument;

    private Route(
        Kind kind,
        BgVatPurchaseTarget purchaseTarget,
        BgVatSalesColumn salesBaseColumn,
        BgVatSalesColumn salesVatColumn) {
      this(kind, purchaseTarget, salesBaseColumn, salesVatColumn, null, false);
    }

    private Route(
        Kind kind,
        BgVatPurchaseTarget purchaseTarget,
        BgVatSalesColumn salesBaseColumn,
        BgVatSalesColumn salesVatColumn,
        String supplyType,
        boolean customsDocument) {
      this.kind = kind;
      this.purchaseTarget = purchaseTarget;
      this.salesBaseColumn = salesBaseColumn;
      this.salesVatColumn = salesVatColumn;
      this.supplyType = supplyType;
      this.customsDocument = customsDocument;
    }

    public Kind getKind() {
      return kind;
    }

    /**
     * Purchase-diary columns, or {@code null} when the operation has no input side or when they
     * come from the credit type of each line ({@link #isPurchaseByCreditType()}).
     */
    public BgVatPurchaseTarget getPurchaseTarget() {
      return purchaseTarget;
    }

    /**
     * {@code true} for a protocol: its input side goes by the credit type of the line, not by the
     * operation. ППЗДДС чл. 97а ал. 4 б. „б“ (протоколът по чл. 163а) и чл. 49а ал. 5 т. 1
     * (протоколът по чл. 167а): „данъчната основа в колони 9, 10 или 12 и начисления данък в колони
     * 11 или 13“ на дневника за покупките.
     */
    public boolean isPurchaseByCreditType() {
      return kind == Kind.PROTOCOL;
    }

    /** Purchase-diary columns for one line with {@code creditType}; never {@code null} for one. */
    public BgVatPurchaseTarget purchaseTargetFor(String creditType) {
      return isPurchaseByCreditType()
          ? BgVatPurchaseCreditTypeMapping.forCreditType(creditType)
          : purchaseTarget;
    }

    /** Sales-diary base column (self-charged output), or {@code null}. */
    public BgVatSalesColumn getSalesBaseColumn() {
      return salesBaseColumn;
    }

    /** Sales-diary VAT column (self-charged output), or {@code null}. */
    public BgVatSalesColumn getSalesVatColumn() {
      return salesVatColumn;
    }

    /**
     * The к.8а code the operation itself prescribes (поле 02-27 / 03-45), or {@code null} when it
     * comes from the move's own {@code bgVatSupplyType}.
     */
    public String getSupplyType() {
      return supplyType;
    }

    /**
     * {@code true} when the operation puts a SECOND row in both diaries for the customs document,
     * with columns 1–8 only (ППЗДДС чл. 49а ал. 4 т. 2 и ал. 5 т. 2).
     */
    public boolean hasCustomsDocument() {
      return customsDocument;
    }
  }

  /** Операциите на протоколите за самоначисляване ({@code bg.vat.move.operation.select}). */
  public static final String OPERATION_VOP = "VOP";

  public static final String OPERATION_SERVICE_EU = "SERVICE_EU";
  public static final String OPERATION_SERVICE_NONEU = "SERVICE_NONEU";
  public static final String OPERATION_ART163A = "ART163A";

  /** к.8а „Доставка по чл. 163а ЗДДС или внос по чл. 167а от ЗДДС“ — код „03“ за вноса. */
  public static final String SUPPLY_TYPE_IMPORT_167A = "03";

  private static final Map<String, Route> ROUTES = buildRoutes();

  private BgVatMoveOperationRouting() {}

  private static Map<String, Route> buildRoutes() {
    Map<String, Route> m = new LinkedHashMap<>();

    m.put("PURCHASE_FULL", purchase(BgVatPurchaseTarget.FULL_CREDIT));
    m.put("PURCHASE_82_NONE", purchase(BgVatPurchaseTarget.NO_CREDIT));
    m.put("PURCHASE_PARTIAL", purchase(BgVatPurchaseTarget.PARTIAL_CREDIT));
    // Годишна корекция (чл.73 ал.8 / чл.147 ал.3) — a signed VAT correction into к.14.
    m.put("PURCHASE_ANNUAL", purchase(BgVatPurchaseTarget.ANNUAL_ADJUSTMENT));
    m.put("PURCHASE_TRIANGULAR", purchase(BgVatPurchaseTarget.TRIANGULAR));

    // Protocols: the input side goes by each line's credit type (Route#isPurchaseByCreditType);
    // the self-charged output side depends on the operation. Заглавията са от ППЗДДС Прил. № 10.
    //
    // ВОП (ЗДДС чл. 13): к.13 „ДО на ВОП“ и к.15 „Начислен ДДС за ВОП и доставки по к. 14“.
    m.put(OPERATION_VOP, protocol(BgVatSalesColumn.K13_BASE_ICA, BgVatSalesColumn.K15_VAT_ICA_RC));
    // Получена услуга от неустановен доставчик — от ЕС или извън — е ЗДДС чл. 82 ал. 2 т. 3:
    // „данъкът е изискуем от получателя“. к.14 „ДО на получени доставки по чл. 82, ал. 2 - 6 ЗДДС“
    // и к.15. ⚠️ НЕ к.22: тя е „ДО на доставки на услуги по чл. 21, ал. 2 ЗДДС с място на
    // изпълнение на територията на друга държава членка“, тоест НАШИТЕ услуги към ЕС (и VIES).
    m.put(
        OPERATION_SERVICE_EU,
        protocol(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put(
        OPERATION_SERVICE_NONEU,
        protocol(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    // ППЗДДС чл. 97а ал. 4 б. „а“: „данъчната основа в колони 9 и 14, съответно данъка в колони 10
    // и 15 на дневник за продажбите“.
    m.put(
        OPERATION_ART163A, protocol(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    // Внос с отложено начисляване по чл. 167а — ППЗДДС чл. 49а. Протоколът по чл. 57 ал. 6 е в
    // ДВАТА дневника с код „03“ в к.8а: в продажбите основата е в колони 9 и 11, а данъкът в 10 и
    // 12 (ал. 4 т. 1); в покупките — по кредитния тип (ал. 5 т. 1). Митническият документ е втори
    // ред в двата, само колони 1–8 (ал. 4 т. 2 и ал. 5 т. 2). к.9/к.10 са тоталите на реда.
    m.put(
        "IMPORT_167A",
        new Route(
            Kind.PROTOCOL,
            null,
            BgVatSalesColumn.K11_BASE_20,
            BgVatSalesColumn.K12_VAT_20,
            SUPPLY_TYPE_IMPORT_167A,
            true));

    // ППЗДДС Прил. № 10: к.12 „… начислен данък (20 %), предвиден в закона в други случаи“.
    m.put(
        "CORRECTION_79", new Route(Kind.ART79_CORRECTION, null, null, BgVatSalesColumn.K12_VAT_20));
    m.put(
        "DEDUCTION_79",
        new Route(Kind.ART79_DEDUCTION, BgVatPurchaseTarget.FULL_CREDIT_VAT_ONLY, null, null));
    // ППЗДДС чл. 66 ал. 9: протоколите по чл. 79а/79б ал. 4 — в к.11, и при двете посоки.
    m.put(
        "ANNUAL_79A",
        new Route(Kind.ART79A_ANNUAL, BgVatPurchaseTarget.FULL_CREDIT_VAT_ONLY, null, null));

    return Collections.unmodifiableMap(m);
  }

  private static Route purchase(BgVatPurchaseTarget target) {
    return new Route(Kind.PURCHASE, target, null, null);
  }

  private static Route protocol(BgVatSalesColumn salesBase, BgVatSalesColumn salesVat) {
    return new Route(Kind.PROTOCOL, null, salesBase, salesVat);
  }

  /** Route for {@code operation} (exact match), or empty when it is not a known operation. */
  public static Optional<Route> forOperation(String operation) {
    return Optional.ofNullable(operation == null ? null : ROUTES.get(operation));
  }

  /**
   * {@code true} when the operation is a VAT PROTOCOL — a self-assessment (ВОП, чл. 82, чл. 163а,
   * чл. 167а) or a correction under чл. 79/79а — and not a plain purchase. Trimmed, then exact.
   */
  public static boolean isProtocol(String operation) {
    return forOperation(operation == null ? null : operation.trim())
        .map(route -> route.getKind() != Kind.PURCHASE)
        .orElse(false);
  }

  /** The operation codes this routing recognises. */
  public static Set<String> operations() {
    return ROUTES.keySet();
  }
}
