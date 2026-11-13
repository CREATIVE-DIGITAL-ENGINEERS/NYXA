package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.db.repo.UnitConversionRepository;
import com.axelor.apps.bglocalization.db.BgSafTaxMapping;
import com.axelor.apps.bglocalization.db.repo.BgSafTaxMappingRepository;
import com.axelor.apps.stock.db.repo.StockLocationRepository;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.Query;

/**
 * ЕДИНСТВЕНОТО МЯСТО, КОЕТО ЧЕТЕ БАЗАТА ЗА TaxTable, UOMTable И Products — всичко надолу работи
 * върху стойности.
 *
 * <p><b>⚠️ СТАТИИТЕ СА ТЕЗИ НА {@link BgSaftMoveScope}.</b> Данъците се събират от редовете на
 * статиите в периода ({@link BgSaftMoveScope#TAXES_ON_LINES}) и от редовете на фактурите, чиито
 * статии са в периода; продуктите — от същите фактури. Така таблиците описват точно редовете, които
 * дневникът (G3) и документите (G5) ще сочат, а не друг набор със свои дати.
 *
 * <p><b>⚠️ ПРОДУКТИТЕ СА ФАКТУРИРАНИТЕ ∪ СТОКИТЕ С НАЛИЧНОСТ.</b> Приложение №3 (т. 1) и MF.P.1
 * искат всички материални запаси в отчетността за месеца, „независимо дали с тях са извършвани
 * транзакции“. Наличността е {@code StockLocationLine.currentQty} в собствените (вътрешни) складове
 * на дружеството. ⚠️ Това е ТЕКУЩАТА наличност, не тази към края на периода: стока, изчерпана след
 * месеца, но без фактура в него, не влиза — да се потвърди, точното смятане е на PhysicalStock.
 *
 * <p>Нито една заявка не зарежда обект: ред на продукт, на единица и на конверсия са скалари.
 */
@Singleton
public class BgSaftMasterDataReader {

  private static final String PRODUCT_COLUMNS =
      "SELECT DISTINCT product.id, product.code, product.name, product.productTypeSelect,"
          + " unit.id, customs.code FROM ";

  private static final String PRODUCT_JOINS =
      " JOIN line.product product LEFT JOIN product.unit unit"
          + " LEFT JOIN product.customsCodeNomenclature customs";

  /**
   * Продуктите по редовете на фактурите, чиито статии са в периода — с посоката на фактурата и
   * сметката на реда (G2c): покупка на ДМА или директно разходван артикул не е запас (Прил. 3 т.
   * IV.6) и не влиза в Products, точно както редът ѝ носи ProductCode „0“.
   */
  public static final String INVOICED_PRODUCTS =
      "SELECT DISTINCT product.id, product.code, product.name, product.productTypeSelect,"
          + " unit.id, customs.code, invoice.operationTypeSelect, account.id FROM "
          + "InvoiceLine line JOIN line.invoice invoice JOIN invoice.move self"
          + PRODUCT_JOINS
          + " LEFT JOIN line.account account"
          + " WHERE "
          + BgSaftMoveScope.PERIOD_MOVES;

  /** Стоките с наличност, различна от нула, в собствените складове на дружеството. */
  public static final String STOCKED_PRODUCTS =
      PRODUCT_COLUMNS
          + "StockLocationLine line JOIN line.stockLocation location"
          + PRODUCT_JOINS
          + " WHERE location.company.id = :companyId"
          + " AND location.typeSelect = "
          + StockLocationRepository.TYPE_INTERNAL
          + " AND line.currentQty <> 0"
          + " AND product.productTypeSelect = '"
          + ProductRepository.PRODUCT_TYPE_STORABLE
          + "'";

  static final String UNITS = "SELECT self.id, self.name, self.bgUomCode FROM Unit self";

  /** Конверсиите с коефициент — тези с формула зависят от продукта и не дават коефициент. */
  static final String UNIT_CONVERSIONS =
      "SELECT self.startUnit.id, self.endUnit.id, self.coef FROM UnitConversion self"
          + " WHERE (self.typeSelect IS NULL OR self.typeSelect <> "
          + UnitConversionRepository.TYPE_FORMULA
          + ")";

  protected final BgSafTaxMappingRepository taxMappingRepo;

  @Inject
  public BgSaftMasterDataReader(BgSafTaxMappingRepository taxMappingRepo) {
    this.taxMappingRepo = taxMappingRepo;
  }

  /** Кодовете на данъците по редовете на статиите и на фактурите в периода. */
  public Set<String> usedTaxes(BgSaftMoveScope scope) {
    Set<String> codes = new LinkedHashSet<>();
    codes.addAll(strings(BgSaftMoveScope.TAXES_ON_LINES, scope));
    codes.addAll(strings(BgSaftMoveScope.TAXES_ON_INVOICE_LINES, scope));
    return codes;
  }

  /** Има ли в периода ред без данък. */
  public boolean hasUntaxedLines(BgSaftMoveScope scope) {
    Object count = query(BgSaftMoveScope.UNTAXED_LINES, scope).getSingleResult();
    return count != null && ((Number) count).longValue() > 0;
  }

  /** {@code BgSafTaxMapping} по код на данъка. */
  public Map<String, BgSaftTaxTableAssembler.MappingRow> taxMappings() {
    Map<String, BgSaftTaxTableAssembler.MappingRow> map = new HashMap<>();
    for (BgSafTaxMapping mapping : taxMappingRepo.all().fetch()) {
      String code = mapping.getTaxCode();
      if (code == null || code.trim().isEmpty()) {
        continue;
      }
      map.put(
          code.trim(),
          new BgSaftTaxTableAssembler.MappingRow(
              mapping.getSafTaxType(),
              mapping.getSafTaxCode(),
              Boolean.TRUE.equals(mapping.getIsDynamic())));
    }
    return map;
  }

  /**
   * Фактурираните в периода продукти — по един ред на (продукт, посока, сметка); кои от тях са
   * запаси решава {@link BgSaftProductsAssembler#select} по сметката на НАП.
   */
  public List<BgSaftProductsAssembler.Use> invoicedProducts(BgSaftMoveScope scope) {
    List<BgSaftProductsAssembler.Use> uses = new ArrayList<>();
    for (Object[] row : rows(INVOICED_PRODUCTS, scope)) {
      int operation = row[6] == null ? 0 : ((Number) row[6]).intValue();
      uses.add(
          new BgSaftProductsAssembler.Use(
              product(row),
              operation == InvoiceRepository.OPERATION_TYPE_CLIENT_SALE
                  || operation == InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND,
              id(row[7])));
    }
    return uses;
  }

  /** Стоките с наличност в собствените складове. */
  public List<BgSaftProductsAssembler.ProductRow> stockedProducts(BgSaftMoveScope scope) {
    List<BgSaftProductsAssembler.ProductRow> products = new ArrayList<>();
    for (Object[] row : rows(STOCKED_PRODUCTS, scope)) {
      products.add(product(row));
    }
    return products;
  }

  private static BgSaftProductsAssembler.ProductRow product(Object[] row) {
    return new BgSaftProductsAssembler.ProductRow(
        id(row[0]), (String) row[1], (String) row[2], (String) row[3], id(row[4]), (String) row[5]);
  }

  /** Всички мерни единици — каноничната на кода се търси сред целия код. */
  public List<BgSaftProductsAssembler.UnitRow> units() {
    List<BgSaftProductsAssembler.UnitRow> units = new ArrayList<>();
    for (Object[] row : rows(UNITS, null)) {
      units.add(new BgSaftProductsAssembler.UnitRow(id(row[0]), (String) row[1], (String) row[2]));
    }
    return units;
  }

  /** Конверсиите между мерни единици с коефициент. */
  public List<BgSaftProductsAssembler.ConversionRow> unitConversions() {
    List<BgSaftProductsAssembler.ConversionRow> conversions = new ArrayList<>();
    for (Object[] row : rows(UNIT_CONVERSIONS, null)) {
      conversions.add(
          new BgSaftProductsAssembler.ConversionRow(id(row[0]), id(row[1]), decimal(row[2])));
    }
    return conversions;
  }

  // ─────────────── изпълнението ───────────────

  private static Query query(String jpql, BgSaftMoveScope scope) {
    Query query = JPA.em().createQuery(jpql);
    if (scope != null) {
      scope.parameters(jpql).forEach(query::setParameter);
    }
    return query;
  }

  @SuppressWarnings("unchecked")
  private static List<Object[]> rows(String jpql, BgSaftMoveScope scope) {
    return query(jpql, scope).getResultList();
  }

  private static List<String> strings(String jpql, BgSaftMoveScope scope) {
    List<String> values = new ArrayList<>();
    for (Object value : query(jpql, scope).getResultList()) {
      if (value != null) {
        values.add(value.toString());
      }
    }
    return values;
  }

  private static Long id(Object value) {
    return value == null ? null : ((Number) value).longValue();
  }

  private static BigDecimal decimal(Object value) {
    if (value == null || value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return new BigDecimal(value.toString());
  }
}
