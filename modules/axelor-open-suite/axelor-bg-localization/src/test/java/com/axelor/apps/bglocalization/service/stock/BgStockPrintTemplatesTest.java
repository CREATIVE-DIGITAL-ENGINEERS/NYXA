package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.base.db.PrintingTemplate;
import com.axelor.apps.stock.db.StockConfig;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Шаблоните за печат на склада по подразбиране — без база (виж {@link BgStockPrintTemplates}). */
class BgStockPrintTemplatesTest {

  private static final Map<String, PrintingTemplate> SEEDED = new HashMap<>();

  static {
    for (BgStockPrintTemplates.Spec spec : BgStockPrintTemplates.DEFAULTS) {
      PrintingTemplate t = new PrintingTemplate();
      t.setName(spec.templateName);
      SEEDED.put(spec.templateName, t);
    }
  }

  private static final Function<BgStockPrintTemplates.Spec, PrintingTemplate> LOOKUP =
      spec -> SEEDED.get(spec.templateName);

  @Test
  @DisplayName("празната конфигурация получава четирите шаблона на axelor-stock")
  void emptyConfigGetsAllFour() {
    StockConfig config = new StockConfig();
    List<String> filled = BgStockPrintTemplates.fillEmpty(config, LOOKUP);
    assertEquals(
        List.of(
            "stockLocationPrintTemplate",
            "inventoryPrintTemplate",
            "pickingStockMovePrintTemplate",
            "conformityCertificatePrintTemplate"),
        filled);
    assertSame(SEEDED.get("StockLocation PDF"), config.getStockLocationPrintTemplate());
    assertSame(SEEDED.get("Inventory PDF"), config.getInventoryPrintTemplate());
    assertSame(SEEDED.get("PickingStockMove"), config.getPickingStockMovePrintTemplate());
    assertSame(SEEDED.get("ConformityCertificate"), config.getConformityCertificatePrintTemplate());
  }

  @Test
  @DisplayName("избраният шаблон не се пипа; липсващ в базата — полето остава празно")
  void chosenStaysMissingStaysEmpty() {
    StockConfig config = new StockConfig();
    PrintingTemplate own = new PrintingTemplate();
    config.setStockLocationPrintTemplate(own);
    List<String> filled =
        BgStockPrintTemplates.fillEmpty(
            config, spec -> "Inventory PDF".equals(spec.templateName) ? null : LOOKUP.apply(spec));
    assertSame(own, config.getStockLocationPrintTemplate());
    assertNull(config.getInventoryPrintTemplate());
    assertEquals(
        List.of("pickingStockMovePrintTemplate", "conformityCertificatePrintTemplate"), filled);
  }

  @Test
  @DisplayName("съветникът: шаблонът от конфигурацията, иначе „StockLocation PDF“")
  void wizardDefault() {
    StockConfig config = new StockConfig();
    PrintingTemplate own = new PrintingTemplate();
    config.setStockLocationPrintTemplate(own);
    assertSame(own, BgStockPrintTemplates.forStockLocationWizard(config, LOOKUP));
    assertSame(
        SEEDED.get("StockLocation PDF"),
        BgStockPrintTemplates.forStockLocationWizard(new StockConfig(), LOOKUP));
    assertSame(
        SEEDED.get("StockLocation PDF"),
        BgStockPrintTemplates.forStockLocationWizard(null, LOOKUP));
  }
}
