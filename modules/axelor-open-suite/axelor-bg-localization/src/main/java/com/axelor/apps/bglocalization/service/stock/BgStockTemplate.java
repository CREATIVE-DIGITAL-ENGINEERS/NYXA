package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.db.EntityHelper;
import com.axelor.db.mapper.Mapper;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Шаблонът на склада за едно дружество: четирите поредици на складовите документи, StockConfig,
 * „Основен склад“ и шестте виртуални локации. Чиста функция: строи и решава, не записва.
 *
 * <p><b>importId е този на шаблона</b>. Поредиците са и в BG_COA ({@code base_sequence.csv},
 * importId 146–149), а шаблонът ги търси по {@code <код на дружеството>-<id>} и дружеството.
 * Поредица от стартирането без този importId би накарала BG_COA, приложен по-късно, да направи
 * втора — и номерата на документите да се повторят.
 *
 * <p>Локациите и конфигурацията ги няма в BG_COA: {@code chart-config.xml} живее в axelor-account,
 * който не вижда склада. Те се строят само оттук, с importId {@code <код>-BGSTK-<n>}, за да ги
 * намира повторното прилагане.
 */
public final class BgStockTemplate {

  /** Поредицата започва на 01.01.2000, както версиите в BG_COA — една отворена версия. */
  public static final LocalDate VERSION_START = LocalDate.of(2000, 1, 1);

  public static final int SEQUENCE_PADDING = 6;

  /** Една поредица на шаблона: код на Axelor, importId в BG_COA, име, префикс. */
  public static final class SequenceSpec {
    public final String code;
    public final String templateImportId;
    public final String name;
    public final String prefix;

    SequenceSpec(String code, String templateImportId, String name, String prefix) {
      this.code = code;
      this.templateImportId = templateImportId;
      this.name = name;
      this.prefix = prefix;
    }
  }

  /**
   * Кодовете са тези на {@code SequenceRepository} в axelor-stock ({@code inStockMove}, {@code
   * outStockMove}, {@code intStockMove}, {@code inventory}). Вътрешната поредица номерира и
   * корекциите и движенията от инвентаризация — Axelor ги прави вътрешни.
   */
  public static final List<SequenceSpec> SEQUENCES =
      Collections.unmodifiableList(
          Arrays.asList(
              new SequenceSpec("inStockMove", "146", "Приемане на склад", "PRM"),
              new SequenceSpec("outStockMove", "147", "Експедиция от склад", "EXP"),
              new SequenceSpec("intStockMove", "148", "Вътрешно преместване", "VSD"),
              new SequenceSpec("inventory", "149", "Инвентаризация", "INV")));

  /** {@code StockLocation.typeSelect}: 1 — вътрешна, 3 — виртуална. */
  static final int LOCATION_INTERNAL = 1;

  static final int LOCATION_VIRTUAL = 3;

  /** Една локация на шаблона и полетата на StockConfig, които сочат към нея. */
  public static final class LocationSpec {
    public final String key;
    public final String name;
    public final int typeSelect;
    public final List<String> configFields;

    LocationSpec(String key, String name, int typeSelect, String... configFields) {
      this.key = key;
      this.name = name;
      this.typeSelect = typeSelect;
      this.configFields = Collections.unmodifiableList(Arrays.asList(configFields));
    }
  }

  /**
   * ⚠️ „Производство“ и „Брак“ сочат полета на axelor-production ({@code
   * productionVirtualStockLocation}, {@code wasteStockLocation}). Този модул не вижда production и
   * StockConfig в неговия src-gen ги няма — записват се по име на свойството ({@link Mapper}), а
   * където свойството липсва, локацията не се създава.
   */
  public static final List<LocationSpec> LOCATIONS =
      Collections.unmodifiableList(
          Arrays.asList(
              new LocationSpec(
                  "BGSTK-1",
                  "Основен склад",
                  LOCATION_INTERNAL,
                  "receiptDefaultStockLocation",
                  "pickupDefaultStockLocation"),
              new LocationSpec(
                  "BGSTK-2", "Доставчици", LOCATION_VIRTUAL, "supplierVirtualStockLocation"),
              new LocationSpec(
                  "BGSTK-3", "Клиенти", LOCATION_VIRTUAL, "customerVirtualStockLocation"),
              new LocationSpec(
                  "BGSTK-4", "Инвентаризация", LOCATION_VIRTUAL, "inventoryVirtualStockLocation"),
              new LocationSpec(
                  "BGSTK-5", "Производство", LOCATION_VIRTUAL, "productionVirtualStockLocation"),
              new LocationSpec("BGSTK-6", "Брак", LOCATION_VIRTUAL, "wasteStockLocation"),
              new LocationSpec(
                  "BGSTK-7",
                  "Подизпълнители",
                  LOCATION_VIRTUAL,
                  "virtualOutsourcingStockLocation")));

  /**
   * Флаговете на основния склад от supplychain и production — по име, защото production не се вижда
   * оттук. Без тях складът не се предлага в поръчките.
   */
  static final List<String> MAIN_WAREHOUSE_FLAGS =
      Collections.unmodifiableList(
          Arrays.asList("usableOnSaleOrder", "usableOnPurchaseOrder", "usableOnProduction"));

  /**
   * Локациите на шаблона, които са склад за SAF-T сами по себе си ({@code bgSaftWarehouse}).
   * „Подизпълнители“ е виртуална, но стоките там са на дружеството: решението на S1 е код 80 и
   * PhysicalStock включва локацията на подизпълнителя.
   */
  static final List<String> SAFT_WAREHOUSE_KEYS = Collections.singletonList("BGSTK-7");

  private BgStockTemplate() {}

  /** {@code _companyCode + '-' + importId}, както в {@code chart-config.xml}. */
  public static String importIdFor(Company company, String templateImportId) {
    return company == null || company.getCode() == null
        ? null
        : company.getCode() + "-" + templateImportId;
  }

  public static Sequence newSequence(Company company, SequenceSpec spec) {
    Sequence sequence = new Sequence();
    sequence.setCompany(company);
    sequence.setImportId(importIdFor(company, spec.templateImportId));
    sequence.setName(spec.name);
    sequence.setCodeSelect(spec.code);
    sequence.setPadding(SEQUENCE_PADDING);
    sequence.setPrefixe(spec.prefix);
    sequence.setSuffixe(null);
    sequence.setToBeAdded(1);
    sequence.setYearlyResetOk(false);
    sequence.setMonthlyResetOk(false);
    SequenceVersion version = new SequenceVersion();
    version.setStartDate(VERSION_START);
    version.setNextNum(1L);
    sequence.addSequenceVersionListItem(version);
    return sequence;
  }

  public static StockLocation newLocation(Company company, LocationSpec spec) {
    StockLocation location = new StockLocation();
    location.setCompany(company);
    location.setImportId(importIdFor(company, spec.key));
    location.setName(spec.name);
    location.setTypeSelect(spec.typeSelect);
    boolean internal = spec.typeSelect == LOCATION_INTERNAL;
    location.setIsValued(internal);
    location.setBgSaftWarehouse(SAFT_WAREHOUSE_KEYS.contains(spec.key));
    if (internal) {
      Mapper mapper = Mapper.of(EntityHelper.getEntityClass(location));
      for (String flag : MAIN_WAREHOUSE_FLAGS) {
        if (mapper.getProperty(flag) != null) {
          mapper.set(location, flag, Boolean.TRUE);
        }
      }
    }
    return location;
  }

  /** Полетата на конфигурацията, които тази среда има — по име, през {@link Mapper}. */
  public static List<String> presentFields(StockConfig config, LocationSpec spec) {
    Mapper mapper = Mapper.of(EntityHelper.getEntityClass(config));
    List<String> out = new ArrayList<>();
    for (String field : spec.configFields) {
      if (mapper.getProperty(field) != null) {
        out.add(field);
      }
    }
    return out;
  }

  /** От тях — празните. Попълненото е избор на потребителя и не се пипа. */
  public static List<String> emptyFields(StockConfig config, LocationSpec spec) {
    Mapper mapper = Mapper.of(EntityHelper.getEntityClass(config));
    List<String> out = new ArrayList<>();
    for (String field : presentFields(config, spec)) {
      if (mapper.get(config, field) == null) {
        out.add(field);
      }
    }
    return out;
  }

  /** Насочва празните полета към локацията. */
  public static void point(StockConfig config, List<String> fields, StockLocation location) {
    Mapper mapper = Mapper.of(EntityHelper.getEntityClass(config));
    for (String field : fields) {
      mapper.set(config, field, location);
    }
  }
}
