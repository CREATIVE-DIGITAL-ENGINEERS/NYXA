package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.service.UnitConversionService;
import com.axelor.apps.bglocalization.db.BgStockCostLayer;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockLocationLine;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Началните слоеве (ИЗП): заварените наличности влизат в слоя по текущата средна цена на локацията
 * ({@code StockLocationLine.avgPrice}) — решение 6, вкл. за партидите без собствена цена.
 *
 * <p>Само веднъж на дружество: има ли вече слой, нищо не се прави. Вика се при първото активиране
 * на политика. Разликата спрямо салдото на 30x не се осчетоводява тук — тя излиза в сверката в края
 * на месеца, по документи.
 *
 * <p>Стойността е {@code к-во × средна цена}, HALF_UP до 0,01, по ред на локация; слоят е сборът за
 * обхвата (склад по правилото за WarehouseID или цялото дружество).
 */
public class BgStockCostingOpeningService {

  /** {@code StockLocation.typeSelect} = 1 — вътрешна локация. */
  static final int LOCATION_INTERNAL = 1;

  protected final UnitConversionService unitConversionService;

  @Inject
  public BgStockCostingOpeningService(UnitConversionService unitConversionService) {
    this.unitConversionService = unitConversionService;
  }

  /** Създава началните слоеве. Връща броя им; 0, когато дружеството вече има слоеве. */
  public int createOpeningLayers(BgStockCostingPolicy policy, LocalDate date)
      throws AxelorException {
    Company company = policy.getCompany();
    if (JPA.all(BgStockCostLayer.class).filter("self.company = ?1", company).count() > 0) {
      return 0;
    }
    boolean byWarehouse =
        BgStockCostingRules.SCOPE_WAREHOUSE.equals(policy.getAverageScopeSelect());
    List<StockLocationLine> lines =
        JPA.all(StockLocationLine.class)
            .filter(
                "self.stockLocation.company = ?1 AND self.stockLocation.typeSelect = ?2"
                    + " AND self.detailsStockLocation IS NULL AND self.currentQty > 0"
                    + " AND self.product.productTypeSelect = ?3",
                company,
                LOCATION_INTERNAL,
                ProductRepository.PRODUCT_TYPE_STORABLE)
            .order("id")
            .fetch();
    Map<String, BgStockCostLayer> layers = new LinkedHashMap<>();
    for (StockLocationLine line : lines) {
      Product product = line.getProduct();
      if (BgSaftInventoryTypes.typeOf(product) == null) {
        continue;
      }
      if (BgStockCostingRules.SPECIFIC.equals(
          BgStockCostingRules.methodFor(policy, product.getProductFamily()))) {
        // Конкретна стойност: по слой на всяка партида, по средната цена на локацията (решение 6);
        // наличност без партида тук няма — активирането я отказва (правило 4).
        for (StockLocationLine detail :
            JPA.all(StockLocationLine.class)
                .filter(
                    "self.detailsStockLocation = ?1 AND self.product = ?2"
                        + " AND self.trackingNumber IS NOT NULL AND self.currentQty > 0",
                    line.getStockLocation(),
                    product)
                .order("id")
                .fetch()) {
          StockLocation scope = byWarehouse ? BgSaftWarehouses.of(line.getStockLocation()) : null;
          BgStockCostLayer lotLayer = new BgStockCostLayer();
          lotLayer.setCompany(company);
          lotLayer.setProduct(product);
          lotLayer.setWarehouse(scope);
          lotLayer.setTrackingNumber(detail.getTrackingNumber());
          lotLayer.setOriginSelect(BgStockValuationService.ORIGIN_OPENING);
          lotLayer.setLayerDate(date);
          lotLayer.setQty(toProductUnit(detail, detail.getCurrentQty()));
          lotLayer.setStockValue(
              BgStockCostEngine.valueOf(detail.getCurrentQty(), line.getAvgPrice()));
          lotLayer.setUnitCost(
              new BgStockCostEngine.Layer(lotLayer.getQty(), lotLayer.getStockValue()).unitCost());
          layers.put("lot|" + detail.getId(), lotLayer);
        }
        continue;
      }
      StockLocation warehouse = byWarehouse ? BgSaftWarehouses.of(line.getStockLocation()) : null;
      BigDecimal qty = toProductUnit(line, line.getCurrentQty());
      BigDecimal value = BgStockCostEngine.valueOf(line.getCurrentQty(), line.getAvgPrice());
      String key = product.getId() + "|" + (warehouse == null ? "" : warehouse.getId());
      BgStockCostLayer layer = layers.get(key);
      if (layer == null) {
        layer = new BgStockCostLayer();
        layer.setCompany(company);
        layer.setProduct(product);
        layer.setWarehouse(warehouse);
        layer.setOriginSelect(BgStockValuationService.ORIGIN_OPENING);
        layer.setLayerDate(date);
        layer.setQty(BigDecimal.ZERO);
        layer.setStockValue(BigDecimal.ZERO.setScale(BgStockCostEngine.AMOUNT_SCALE));
        layers.put(key, layer);
      }
      BgStockCostEngine.Layer sum =
          new BgStockCostEngine.Layer(layer.getQty(), layer.getStockValue());
      BgStockCostEngine.receive(sum, qty, value);
      layer.setQty(sum.qty());
      layer.setStockValue(sum.value());
      layer.setUnitCost(sum.unitCost());
    }
    for (BgStockCostLayer layer : layers.values()) {
      JPA.save(layer);
    }
    return layers.size();
  }

  protected BigDecimal toProductUnit(StockLocationLine line, BigDecimal qty)
      throws AxelorException {
    Product product = line.getProduct();
    if (line.getUnit() == null
        || product.getUnit() == null
        || line.getUnit().equals(product.getUnit())) {
      return qty;
    }
    return unitConversionService.convert(
        line.getUnit(), product.getUnit(), qty, qty.scale(), product);
  }
}
