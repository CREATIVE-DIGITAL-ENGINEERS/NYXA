package com.axelor.apps.bglocalization.service.vat.adapter;

import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.field;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.formatAmount;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.padLeft;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.padRight;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;

/**
 * Builds the VAT declaration <b>DEKLAR.TXT</b> — one fixed-width record of 590 chars (CP1251 + CRLF
 * on write). By design it AGGREGATES control totals from the already-collected purchase and sales
 * diary DTOs (never recalculates from unrelated raw accounting data), so the declaration always
 * reconciles with the diaries it is derived from.
 *
 * <p>Layout is the authoritative ППЗДДС Приложение №12 map, byte-verified against the accepted
 * DEKLAR_1.txt golden fixture: a 151-char header, then the value cells in {@code DECLARATION_ORDER}
 * (each 15 wide EXCEPT кл.33 = 4).
 *
 * <p>The declaration cell numbers (кл.01, кл.20, …) are the official declaration cells; the
 * per-document ledger fields they sum (02-XX sales / 03-XX purchases) map to the diary column enums
 * ({@link BgVatSalesColumn} / {@link BgVatPurchaseColumn}) as documented on each cell below.
 *
 * <p>Sales column values are read from the rows of {@link BgVatSalesAdapter} — the SAME rows as
 * PRODAGBI.TXT, which already carry the tax-code routing AND the collector's direct protocol
 * columns ({@link BgVatDocumentDto#getSalesAmounts()}); purchase column values are read from {@link
 * BgVatDocumentDto#getPurchaseAmount} (collector-filled).
 */
@Singleton
public class BgVatDeclarationAdapter {

  static final int RECORD_LENGTH = 590;

  // Field widths.
  private static final int W_COMPANY_VAT = 15; // [0:15]
  private static final int W_COMPANY_NAME = 50; // [15:65]
  private static final int W_PERIOD = 6; // [65:71]
  private static final int W_SUBMITTER = 50; // [71:121]
  private static final int W_COUNT = 15; // [121:136] sales count, [136:151] purchase count
  private static final int W_CELL = 15; // every value cell …
  private static final int W_COEFFICIENT = 4; // … except кл.33

  /** Document types EXCLUDED from the sales control sums (кл.01–кл.24). */
  private static final Set<String> SALES_EXCLUDED_TYPES =
      new HashSet<>(Arrays.asList("04", "11", "12", "13"));

  /** Document types EXCLUDED from the purchase control sums (кл.30–кл.43). */
  private static final Set<String> PURCHASE_EXCLUDED_TYPES =
      new HashSet<>(Arrays.asList("05", "11", "12", "13", "94"));

  private static final BigDecimal HUNDREDTHS = new BigDecimal("0.00");

  private final BgVatSalesAdapter salesAdapter;

  @Inject
  public BgVatDeclarationAdapter(BgVatSalesAdapter salesAdapter) {
    this.salesAdapter = salesAdapter;
  }

  /** Header identification fields (Phase 1b will source these from the company / wizard). */
  public static final class Header {
    private final String companyVatNumber;
    private final String companyName;
    private final String period;
    private final String submitter;

    public Header(String companyVatNumber, String companyName, String period, String submitter) {
      this.companyVatNumber = companyVatNumber;
      this.companyName = companyName;
      this.period = period;
      this.submitter = submitter;
    }
  }

  /** Manually-entered declaration cells (Phase 1b will source these from the wizard/UI). */
  public static final class ManualCells {
    private final BigDecimal coefficient; // кл.33
    private final BigDecimal cell70;
    private final BigDecimal cell71;
    private final BigDecimal cell80;
    private final BigDecimal cell81;
    private final BigDecimal cell82;

    public ManualCells(
        BigDecimal coefficient,
        BigDecimal cell70,
        BigDecimal cell71,
        BigDecimal cell80,
        BigDecimal cell81,
        BigDecimal cell82) {
      this.coefficient = coefficient;
      this.cell70 = cell70;
      this.cell71 = cell71;
      this.cell80 = cell80;
      this.cell81 = cell81;
      this.cell82 = cell82;
    }
  }

  /**
   * Build the single DEKLAR.TXT record. Returns a one-element list to match the adapter/writer
   * pipeline used by the diaries.
   */
  public List<String> buildLines(
      List<BgVatDocumentDto> salesDocuments,
      List<BgVatDocumentDto> purchaseDocuments,
      Header header,
      ManualCells manual) {
    List<BgVatDocumentDto> sales =
        salesDocuments != null ? salesDocuments : Collections.emptyList();
    List<BgVatDocumentDto> purchases =
        purchaseDocuments != null ? purchaseDocuments : Collections.emptyList();

    Map<BgVatSalesColumn, BigDecimal> s = sumSales(sales);
    Map<BgVatPurchaseColumn, BigDecimal> p = sumPurchases(purchases);

    // --- Sales control cells (кл.XX = Σ of the mapped 02-XX ledger fields) ---
    BigDecimal k01 = s(s, BgVatSalesColumn.K09_TOTAL_BASE); // 02-10
    BigDecimal k20 = s(s, BgVatSalesColumn.K10_TOTAL_VAT); // 02-20
    BigDecimal k11 = s(s, BgVatSalesColumn.K11_BASE_20); // 02-11
    BigDecimal k21 = s(s, BgVatSalesColumn.K12_VAT_20); // 02-21
    BigDecimal k12 =
        s(s, BgVatSalesColumn.K13_BASE_ICA).add(s(s, BgVatSalesColumn.K14_BASE_RC)); // 02-12+02-26
    BigDecimal k22 = s(s, BgVatSalesColumn.K15_VAT_ICA_RC); // 02-22
    BigDecimal k23 = s(s, BgVatSalesColumn.K16_VAT_SELF_SUPPLY); // 02-23
    BigDecimal k13 = s(s, BgVatSalesColumn.K17_BASE_9); // 02-13
    BigDecimal k24 = s(s, BgVatSalesColumn.K18_VAT_9); // 02-24
    BigDecimal k14 = s(s, BgVatSalesColumn.K19_BASE_ZERO_CH3); // 02-14
    BigDecimal k15 = s(s, BgVatSalesColumn.K20_BASE_ICS); // 02-15
    BigDecimal k16 = s(s, BgVatSalesColumn.K21_BASE_ART140_146_173); // 02-16
    BigDecimal k17 = s(s, BgVatSalesColumn.K22_BASE_EU_SERVICES); // 02-17
    BigDecimal k18 =
        s(s, BgVatSalesColumn.K23_BASE_ART69_2)
            .add(s(s, BgVatSalesColumn.K25_BASE_TRIANGULAR)); // 02-18+02-25
    BigDecimal k19 = s(s, BgVatSalesColumn.K24_BASE_EXEMPT); // 02-19

    // --- Purchase control cells ---
    BigDecimal k30 =
        p(p, BgVatPurchaseColumn.K09_BASE_NO_CREDIT)
            .add(p(p, BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ)); // 03-30+03-44
    BigDecimal k31 = p(p, BgVatPurchaseColumn.K10_BASE_FULL_CREDIT); // 03-31
    BigDecimal k41 = p(p, BgVatPurchaseColumn.K11_VAT_FULL_CREDIT); // 03-41
    BigDecimal k32 = p(p, BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT); // 03-32
    BigDecimal k42 = p(p, BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT); // 03-42
    BigDecimal k43 = p(p, BgVatPurchaseColumn.K14_ANNUAL_ADJUSTMENT); // 03-43

    // --- Computed cells ---
    BigDecimal coef = nz(manual.coefficient);
    BigDecimal k40 =
        k41.add(k42.multiply(coef)).add(k43).setScale(2, RoundingMode.HALF_UP); // credit total
    BigDecimal k50 = nonNegative(k20.subtract(k40)); // ДДС за внасяне
    BigDecimal k60 = nonNegative(k40.subtract(k20)); // ДДС за възстановяване

    StringBuilder sb = new StringBuilder(RECORD_LENGTH);
    // Header [0:151].
    sb.append(padRight(field(header.companyVatNumber), W_COMPANY_VAT));
    sb.append(padRight(field(header.companyName), W_COMPANY_NAME));
    sb.append(padRight(field(header.period), W_PERIOD));
    sb.append(padRight(field(header.submitter), W_SUBMITTER));
    sb.append(
        padLeft(Integer.toString(sales.size()), W_COUNT)); // брой док. продажби (без изключване)
    sb.append(padLeft(Integer.toString(purchases.size()), W_COUNT)); // брой док. покупки

    // Value cells in DECLARATION_ORDER.
    amount(sb, k01);
    amount(sb, k20);
    amount(sb, k11);
    amount(sb, k21);
    amount(sb, k12);
    amount(sb, k22);
    amount(sb, k23);
    amount(sb, k13);
    amount(sb, k24);
    amount(sb, k14);
    amount(sb, k15);
    amount(sb, k16);
    amount(sb, k17);
    amount(sb, k18);
    amount(sb, k19);
    amount(sb, k30);
    amount(sb, k31);
    amount(sb, k41);
    amount(sb, k32);
    amount(sb, k42);
    amount(sb, k43);
    sb.append(padLeft(formatAmount(coef), W_COEFFICIENT)); // кл.33 (4 wide)
    amount(sb, k40);
    amount(sb, k50);
    amount(sb, k60);
    amount(sb, nz(manual.cell70));
    amount(sb, nz(manual.cell71));
    amount(sb, nz(manual.cell80));
    amount(sb, nz(manual.cell81));
    amount(sb, nz(manual.cell82));

    String record = sb.toString();
    if (record.length() != RECORD_LENGTH) {
      throw new IllegalStateException(
          "DEKLAR record must be " + RECORD_LENGTH + " chars but was " + record.length());
    }
    return Collections.singletonList(record);
  }

  // ---------------------------------------------------------------------------
  // Aggregation
  // ---------------------------------------------------------------------------

  /**
   * Sum the sales diary columns per document (excluding the sales-excluded doc types) from the
   * PRODAGBI rows of {@link BgVatSalesAdapter}.
   *
   * <p>⚠️ Only the row. It already holds the protocol columns, and its к.09/к.10 totals include
   * them; adding {@link BgVatDocumentDto#getSalesAmount} on top would count them twice.
   */
  protected Map<BgVatSalesColumn, BigDecimal> sumSales(List<BgVatDocumentDto> salesDocs) {
    Map<BgVatSalesColumn, BigDecimal> sums = new EnumMap<>(BgVatSalesColumn.class);
    List<BgVatSalesRowDto> rows = salesAdapter.buildRows(salesDocs, null);
    for (int i = 0; i < salesDocs.size(); i++) {
      BgVatDocumentDto doc = salesDocs.get(i);
      if (SALES_EXCLUDED_TYPES.contains(nz(doc.getNapDocumentType()))) {
        continue;
      }
      BgVatSalesRowDto row = rows.get(i);
      for (BgVatSalesColumn col : BgVatSalesColumn.values()) {
        BigDecimal v = row.getAmount(col);
        if (v.signum() != 0) {
          sums.merge(col, v, BigDecimal::add);
        }
      }
    }
    return sums;
  }

  /** Sum the purchase diary columns per document (excluding the purchase-excluded doc types). */
  protected Map<BgVatPurchaseColumn, BigDecimal> sumPurchases(List<BgVatDocumentDto> purchaseDocs) {
    Map<BgVatPurchaseColumn, BigDecimal> sums = new EnumMap<>(BgVatPurchaseColumn.class);
    for (BgVatDocumentDto doc : purchaseDocs) {
      if (PURCHASE_EXCLUDED_TYPES.contains(nz(doc.getNapDocumentType()))) {
        continue;
      }
      // Закръглените колони на документа — същите, които POKUPKI пише; кл. на DEKLAR е техният
      // сбор.
      Map<BgVatPurchaseColumn, BigDecimal> diary = doc.getPurchaseDiaryAmounts();
      for (BgVatPurchaseColumn col : BgVatPurchaseColumn.values()) {
        BigDecimal v = diary.getOrDefault(col, BigDecimal.ZERO);
        if (v.signum() != 0) {
          sums.merge(col, v, BigDecimal::add);
        }
      }
    }
    return sums;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  private static BigDecimal s(Map<BgVatSalesColumn, BigDecimal> m, BgVatSalesColumn c) {
    return m.getOrDefault(c, BigDecimal.ZERO);
  }

  private static BigDecimal p(Map<BgVatPurchaseColumn, BigDecimal> m, BgVatPurchaseColumn c) {
    return m.getOrDefault(c, BigDecimal.ZERO);
  }

  private static void amount(StringBuilder sb, BigDecimal value) {
    sb.append(padLeft(formatAmount(value), W_CELL));
  }

  private static BigDecimal nonNegative(BigDecimal value) {
    return value.signum() < 0 ? HUNDREDTHS : value;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String nz(String value) {
    return value == null ? "" : value.trim();
  }
}
