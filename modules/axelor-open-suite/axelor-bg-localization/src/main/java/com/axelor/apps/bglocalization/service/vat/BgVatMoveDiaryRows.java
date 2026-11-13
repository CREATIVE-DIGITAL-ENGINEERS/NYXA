package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * РЕДОВЕТЕ, КОИТО ДНЕВНИЦИТЕ ПРАВЯТ ОТ ЕДНА СТАТИЯ БЕЗ ФАКТУРА — за SAF-T (G5b), от същия източник,
 * от който ги пише Блок Б.
 *
 * <p><b>⚠️ НЕ ПРЕПИСВА НИТО ЕДНО ПРАВИЛО НА КОЛЕКТОРА.</b> Класът е в пакета на колектора, за да
 * стигне до {@code protected} методите му на ИНЖЕКТИРАНИЯ екземпляр (и до всеки негов override):
 * {@link BgVatDocumentCollector#isVatRelevant}, {@link BgVatDocumentCollector#toDocument} и {@link
 * BgVatDocumentCollector#customsDocument} — в реда на {@link BgVatDocumentCollector#collect}. Кой
 * документ в кой дневник влиза, решават филтрите на {@link BgVatExportService}, а колоните на
 * PRODAGBI — {@link BgVatSalesAdapter#buildRows}; колоните на POKUPKI са {@link
 * BgVatDocumentDto#getPurchaseDiaryAmounts()}, както ги прелива {@code generatePurchaseFile}.
 * Колекторът остава непипнат.
 */
@Singleton
public class BgVatMoveDiaryRows {

  /** Един ред в дневниците: документът и колоните му в PRODAGBI и POKUPKI. */
  public static final class Row {
    private final BgVatDocumentDto document;
    private final boolean sales;
    private final boolean purchases;
    private final Map<BgVatSalesColumn, BigDecimal> salesColumns;
    private final Map<BgVatPurchaseColumn, BigDecimal> purchaseColumns;
    private final BigDecimal salesTotalBase;
    private final BigDecimal salesTotalVat;

    Row(
        BgVatDocumentDto document,
        boolean sales,
        boolean purchases,
        Map<BgVatSalesColumn, BigDecimal> salesColumns,
        Map<BgVatPurchaseColumn, BigDecimal> purchaseColumns,
        BigDecimal salesTotalBase,
        BigDecimal salesTotalVat) {
      this.document = document;
      this.sales = sales;
      this.purchases = purchases;
      this.salesColumns = Collections.unmodifiableMap(salesColumns);
      this.purchaseColumns = Collections.unmodifiableMap(purchaseColumns);
      this.salesTotalBase = salesTotalBase;
      this.salesTotalVat = salesTotalVat;
    }

    /** Документът на колектора: номер, дата, вид, к.8а и грешките му. */
    public BgVatDocumentDto getDocument() {
      return document;
    }

    /** Влиза в PRODAGBI.TXT. */
    public boolean isSales() {
      return sales;
    }

    /** Влиза в POKUPKI.TXT. */
    public boolean isPurchases() {
      return purchases;
    }

    /** Колоните за стойност на реда в PRODAGBI — без тоталите к.09/к.10. */
    public Map<BgVatSalesColumn, BigDecimal> getSalesColumns() {
      return salesColumns;
    }

    /** Колоните за стойност на реда в POKUPKI. */
    public Map<BgVatPurchaseColumn, BigDecimal> getPurchaseColumns() {
      return purchaseColumns;
    }

    /**
     * к.09 на реда в PRODAGBI — сборът на ЗАКРЪГЛЕНИТЕ колони за основа ({@link
     * BgVatSalesAdapter#buildRows}); нула, когато документът не е в дневника за продажбите.
     */
    public BigDecimal getSalesTotalBase() {
      return salesTotalBase;
    }

    /** к.10 на реда в PRODAGBI — сборът на закръглените колони за данък. */
    public BigDecimal getSalesTotalVat() {
      return salesTotalVat;
    }
  }

  protected final BgVatDocumentCollector collector;
  protected final BgVatExportService exportService;
  protected final BgVatSalesAdapter salesAdapter;

  @Inject
  public BgVatMoveDiaryRows(
      BgVatDocumentCollector collector,
      BgVatExportService exportService,
      BgVatSalesAdapter salesAdapter) {
    this.collector = collector;
    this.exportService = exportService;
    this.salesAdapter = salesAdapter;
  }

  /** Влиза ли статията в дневниците — {@link BgVatDocumentCollector#isVatRelevant} дословно. */
  public boolean isRelevant(Move move) {
    return collector.isVatRelevant(move);
  }

  /** Редът е по сметката за ДДС по покупки (4531) — правилото на колектора. */
  public boolean isVatPurchaseAccount(MoveLine line) {
    return collector.isVatPurchaseAccount(line);
  }

  /** Редът е по сметката за ДДС по продажби (4532) — правилото на колектора. */
  public boolean isVatSalesAccount(MoveLine line) {
    return collector.isVatSalesAccount(line);
  }

  /**
   * Редовете на статията в дневниците: протоколът и при внос по чл. 167а — митническият документ.
   * Празно, когато статията не влиза в дневниците.
   */
  public List<Row> rows(Move move) {
    List<Row> rows = new ArrayList<>();
    if (!isRelevant(move)) {
      return rows;
    }
    List<BgVatDocumentDto> documents = new ArrayList<>();
    BgVatDocumentDto document = collector.toDocument(move);
    documents.add(document);
    BgVatDocumentDto customs = collector.customsDocument(move, document);
    if (customs != null) {
      documents.add(customs);
    }
    List<BgVatDocumentDto> sales = exportService.filterSalesDocuments(documents);
    List<BgVatDocumentDto> purchases = exportService.filterPurchaseDocuments(documents);
    for (BgVatDocumentDto each : documents) {
      boolean inSales = sales.contains(each);
      Map<BgVatSalesColumn, BigDecimal> salesColumns = new EnumMap<>(BgVatSalesColumn.class);
      BigDecimal totalBase = BigDecimal.ZERO;
      BigDecimal totalVat = BigDecimal.ZERO;
      if (inSales) {
        BgVatSalesRowDto row = salesAdapter.buildRows(Collections.singletonList(each), null).get(0);
        row.getAmounts().forEach(salesColumns::put);
        totalBase = salesColumns.getOrDefault(BgVatSalesColumn.K09_TOTAL_BASE, BigDecimal.ZERO);
        totalVat = salesColumns.getOrDefault(BgVatSalesColumn.K10_TOTAL_VAT, BigDecimal.ZERO);
        salesColumns.remove(BgVatSalesColumn.K09_TOTAL_BASE);
        salesColumns.remove(BgVatSalesColumn.K10_TOTAL_VAT);
      }
      Map<BgVatPurchaseColumn, BigDecimal> purchaseColumns =
          new EnumMap<>(BgVatPurchaseColumn.class);
      boolean inPurchases = purchases.contains(each);
      if (inPurchases) {
        purchaseColumns.putAll(each.getPurchaseDiaryAmounts());
      }
      rows.add(
          new Row(each, inSales, inPurchases, salesColumns, purchaseColumns, totalBase, totalVat));
    }
    return rows;
  }

  /**
   * Ставката на протокола като ДРОБ, както я чете колекторът ({@link
   * BgVatDocumentCollector#protocolRates}): ръчната, иначе различните ставки по редовете. Повече от
   * една — смесени ставки.
   */
  public Set<BigDecimal> protocolRates(Move move) {
    return collector.protocolRates(move);
  }
}
