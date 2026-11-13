package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.db.mapper.Mapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import javax.persistence.CascadeType;
import javax.persistence.FetchType;
import javax.persistence.ManyToOne;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Шаблонът на склада като чиста функция: поредиците са същите като редовете в BG_COA, а полетата на
 * axelor-production се пишат по име на свойството.
 */
class BgStockTemplateTest {

  private static final Path COA =
      Paths.get("../axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static List<String> lines(String file) throws IOException {
    return Files.readAllLines(COA.resolve(file), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("поредиците на шаблона са редовете 146–149 в BG_COA, дума по дума, с версия от 2000")
  void поредиците_съвпадат_с_bg_coa() throws IOException {
    List<String> sequences = lines("base_sequence.csv");
    List<String> versions = lines("base_sequenceVersion.csv");
    for (BgStockTemplate.SequenceSpec spec : BgStockTemplate.SEQUENCES) {
      String row =
          String.format(
              "\"%s\";\"%s\";\"%s\";\"NUMBERS\";\"UPPERCASE\";\"1\";\"%d\";\"%s\";\"\";\"1\";0",
              spec.templateImportId,
              spec.code,
              spec.name,
              BgStockTemplate.SEQUENCE_PADDING,
              spec.prefix);
      assertTrue(sequences.contains(row), "липсва в BG_COA: " + row);
      String version =
          String.format(
              "\"V%s\";\"%s\";\"2000-01-01\"", spec.templateImportId, spec.templateImportId);
      assertTrue(versions.contains(version), "липсва версията: " + version);
    }
  }

  @Test
  @DisplayName("кодовете на поредиците са тези, които складът на Axelor търси")
  void кодовете_са_на_axelor() {
    List<String> codes = new ArrayList<>();
    BgStockTemplate.SEQUENCES.forEach(s -> codes.add(s.code));
    assertEquals(List.of("inStockMove", "outStockMove", "intStockMove", "inventory"), codes);
  }

  @Test
  @DisplayName("поредицата носи importId-а на шаблона — урокът от #277")
  void importid_на_шаблона() {
    Company company = new Company();
    company.setCode("ACME");
    Sequence sequence = BgStockTemplate.newSequence(company, BgStockTemplate.SEQUENCES.get(0));
    assertEquals("ACME-146", sequence.getImportId());
    assertEquals("inStockMove", sequence.getCodeSelect());
    assertEquals("PRM", sequence.getPrefixe());
    assertEquals(Boolean.FALSE, sequence.getYearlyResetOk());
    assertEquals(1, sequence.getSequenceVersionList().size());
    assertEquals(
        BgStockTemplate.VERSION_START, sequence.getSequenceVersionList().get(0).getStartDate());
    assertNull(BgStockTemplate.importIdFor(new Company(), "146"), "без код няма importId");
  }

  /**
   * StockConfig, какъвто го генерира axelor-production: с двете полета, които този модул не вижда.
   * Mapper ги открива по JavaBeans интроспекция, точно както в приложението.
   */
  public static class ProductionStockConfig extends StockConfig {
    // Анотациите са като в генерирания StockConfig: Mapper строи свойството от тях.
    @ManyToOne(
        fetch = FetchType.LAZY,
        cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private StockLocation productionVirtualStockLocation;

    @ManyToOne(
        fetch = FetchType.LAZY,
        cascade = {CascadeType.PERSIST, CascadeType.MERGE})
    private StockLocation wasteStockLocation;

    public StockLocation getProductionVirtualStockLocation() {
      return productionVirtualStockLocation;
    }

    public void setProductionVirtualStockLocation(StockLocation value) {
      this.productionVirtualStockLocation = value;
    }

    public StockLocation getWasteStockLocation() {
      return wasteStockLocation;
    }

    public void setWasteStockLocation(StockLocation value) {
      this.wasteStockLocation = value;
    }
  }

  private static BgStockTemplate.LocationSpec spec(String key) {
    return BgStockTemplate.LOCATIONS.stream().filter(s -> s.key.equals(key)).findFirst().get();
  }

  @Test
  @DisplayName("при наличен production „Производство“ и „Брак“ се записват по име на свойството")
  void полетата_на_production_се_попълват() {
    ProductionStockConfig config = new ProductionStockConfig();
    StockLocation production = new StockLocation();
    StockLocation waste = new StockLocation();
    for (String key : List.of("BGSTK-5", "BGSTK-6")) {
      List<String> empty = BgStockTemplate.emptyFields(config, spec(key));
      assertEquals(spec(key).configFields, empty, key);
      BgStockTemplate.point(config, empty, "BGSTK-5".equals(key) ? production : waste);
    }
    assertSame(production, config.getProductionVirtualStockLocation());
    assertSame(waste, config.getWasteStockLocation());
    assertTrue(
        BgStockTemplate.emptyFields(config, spec("BGSTK-5")).isEmpty(), "попълненото не се пипа");
  }

  @Test
  @DisplayName("без production полетата ги няма — локацията не се насочва и не се създава")
  void без_production_нищо() {
    StockConfig config = new StockConfig();
    assertNull(Mapper.of(StockConfig.class).getProperty("productionVirtualStockLocation"));
    assertTrue(BgStockTemplate.presentFields(config, spec("BGSTK-5")).isEmpty());
    assertTrue(BgStockTemplate.emptyFields(config, spec("BGSTK-6")).isEmpty());
  }

  @Test
  @DisplayName("попълнено поле на склада не се пренасочва")
  void попълненото_остава() {
    StockConfig config = new StockConfig();
    StockLocation own = new StockLocation();
    config.setCustomerVirtualStockLocation(own);
    assertTrue(BgStockTemplate.emptyFields(config, spec("BGSTK-3")).isEmpty());
    config.setReceiptDefaultStockLocation(own);
    assertEquals(
        List.of("pickupDefaultStockLocation"),
        BgStockTemplate.emptyFields(config, spec("BGSTK-1")));
  }

  @Test
  @DisplayName("основният склад е вътрешен и се оценява; виртуалните — не")
  void локациите() {
    Company company = new Company();
    company.setCode("ACME");
    StockLocation main = BgStockTemplate.newLocation(company, spec("BGSTK-1"));
    assertEquals("ACME-BGSTK-1", main.getImportId());
    assertEquals("Основен склад", main.getName());
    assertEquals(1, main.getTypeSelect());
    assertEquals(Boolean.TRUE, main.getIsValued());
    StockLocation suppliers = BgStockTemplate.newLocation(company, spec("BGSTK-2"));
    assertEquals(3, suppliers.getTypeSelect());
    assertEquals(Boolean.FALSE, suppliers.getIsValued());
  }
}
