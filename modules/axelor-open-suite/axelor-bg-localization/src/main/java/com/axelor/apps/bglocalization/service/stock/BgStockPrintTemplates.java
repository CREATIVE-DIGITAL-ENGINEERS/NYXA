package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.PrintingTemplate;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.db.JPA;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * ШАБЛОНИТЕ ЗА ПЕЧАТ НА СКЛАДА ПО ПОДРАЗБИРАНЕ.
 *
 * <p>Без шаблон „Печат“ на складово място отваря съветник, в който полето „Шаблон за складово
 * място“ е празно, а бутонът „Печат“ е заключен, докато не се избере шаблон ({@code
 * stock-location-print-wizard-form}, axelor-stock). Конфигурацията на склада на дружеството също е
 * празна — AOS пълни шаблоните само в своите френски демо данни ({@code stock_stockConfig.csv}), а
 * нашето дружество получава StockConfig от {@link BgStockSetupService}.
 *
 * <p>Шаблоните са тези от data-init на axelor-stock ({@code base_printingTemplate.csv}) — по име и
 * модел. ⚠️ Попълва се САМО празното: избран шаблон е избор на дружеството.
 */
public final class BgStockPrintTemplates {

  private BgStockPrintTemplates() {}

  /** Едно поле на StockConfig и шаблонът, който получава, когато е празно. */
  public static final class Spec {
    public final String field;
    public final String templateName;
    public final String modelName;
    final Function<StockConfig, PrintingTemplate> getter;
    final BiConsumer<StockConfig, PrintingTemplate> setter;

    Spec(
        String field,
        String templateName,
        String modelName,
        Function<StockConfig, PrintingTemplate> getter,
        BiConsumer<StockConfig, PrintingTemplate> setter) {
      this.field = field;
      this.templateName = templateName;
      this.modelName = modelName;
      this.getter = getter;
      this.setter = setter;
    }
  }

  /** „Справката“ на складовото място — шаблонът, който съветникът за печат предлага. */
  public static final Spec STOCK_LOCATION =
      new Spec(
          "stockLocationPrintTemplate",
          "StockLocation PDF",
          "com.axelor.apps.stock.db.StockLocation",
          StockConfig::getStockLocationPrintTemplate,
          StockConfig::setStockLocationPrintTemplate);

  /** Четирите шаблона на StockConfig — имената и моделите са от data-init на axelor-stock. */
  public static final List<Spec> DEFAULTS =
      List.of(
          STOCK_LOCATION,
          new Spec(
              "inventoryPrintTemplate",
              "Inventory PDF",
              "com.axelor.apps.stock.db.Inventory",
              StockConfig::getInventoryPrintTemplate,
              StockConfig::setInventoryPrintTemplate),
          new Spec(
              "pickingStockMovePrintTemplate",
              "PickingStockMove",
              "com.axelor.apps.stock.db.StockMove",
              StockConfig::getPickingStockMovePrintTemplate,
              StockConfig::setPickingStockMovePrintTemplate),
          new Spec(
              "conformityCertificatePrintTemplate",
              "ConformityCertificate",
              "com.axelor.apps.stock.db.StockMove",
              StockConfig::getConformityCertificatePrintTemplate,
              StockConfig::setConformityCertificatePrintTemplate));

  /**
   * Попълва празните шаблони на {@code config}. Шаблон, който {@code lookup} не намира (базата е
   * без data-init на axelor-stock), оставя полето празно.
   *
   * @return имената на попълнените полета, по реда на {@link #DEFAULTS}
   */
  public static List<String> fillEmpty(
      StockConfig config, Function<Spec, PrintingTemplate> lookup) {
    List<String> filled = new ArrayList<>();
    for (Spec spec : DEFAULTS) {
      if (spec.getter.apply(config) != null) {
        continue;
      }
      PrintingTemplate template = lookup.apply(spec);
      if (template != null) {
        spec.setter.accept(config, template);
        filled.add(spec.field);
      }
    }
    return filled;
  }

  /**
   * Шаблонът, който съветникът за печат на складово място предлага: този от конфигурацията на
   * дружеството, а при празна — шаблонът по подразбиране.
   */
  public static PrintingTemplate forStockLocationWizard(
      StockConfig config, Function<Spec, PrintingTemplate> lookup) {
    PrintingTemplate configured = config == null ? null : STOCK_LOCATION.getter.apply(config);
    return configured != null ? configured : lookup.apply(STOCK_LOCATION);
  }

  /** Шаблонът от базата по име и модел (първият по id, ако са няколко). */
  public static PrintingTemplate find(Spec spec) {
    return JPA.all(PrintingTemplate.class)
        .filter(
            "self.name = ?1 AND self.metaModel.fullName = ?2", spec.templateName, spec.modelName)
        .order("id")
        .fetchOne();
  }

  /** StockConfig на дружеството или {@code null}. */
  public static StockConfig configOf(Company company) {
    return company == null
        ? null
        : JPA.all(StockConfig.class).filter("self.company = ?1", company).fetchOne();
  }
}
