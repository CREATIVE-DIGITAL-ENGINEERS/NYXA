package com.axelor.apps.bglocalization.service.stock;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Сметките по подразбиране за изписването (ИЗП) — таблицата, одобрена на 07.10.2026, с поправките
 * от 08.10.2026 (442 за липса с виновно лице, код 20). Чиста данна. Nyxa я ПРЕДЛАГА в новата
 * политика, но не я налага: всяко дружество сменя редовете.
 *
 * <p>Сметката тук е НАСРЕЩНАТА. Другата страна е складовата 30x на семейството (S0): при изход Дт
 * насрещната / Кт 30x, при вход Дт 30x / Кт насрещната.
 *
 * <ul>
 *   <li>30 продажба — нетиране в 70x: стоки → 702, продукция → 701, материали → 706;
 *   <li>40 връщане от клиент — сметката от продажбата (същите 70x), по първоначалната себестойност;
 *   <li>70 влагане в производство → 60101;
 *   <li>20 продукция (и странични продукти) от производството → Кт 611 „Разходи за основна дейност“
 *       (Дт 303 идва от семейството); оценката на продукцията е отделна задача;
 *   <li>120 без виновно лице, 160 брак, 170 изтекъл срок → 60905; 120 с виновно лице → 442
 *       „Вземания по липси и начети“ аналитично по лицето — както при активите;
 *   <li>150 безвъзмездно → 60906; 65 предоставени отстъпки в натура → 60909;
 *   <li>110 положителна корекция и 60 получени отстъпки в натура → Кт 709.
 * </ul>
 *
 * <p>Не са тук: 10, 50 и 80 нямат статия от склада ({@link
 * BgStockCostingRules#CODES_WITHOUT_POSTING}); 130/140 (преоценка) и 100/101 (ценови разлики) са в
 * „стойностните корекции“; 180 „други“ няма подразбиране по решение — без ред движението остава
 * неосчетоводено с ERROR, докато дружеството не избере сметка.
 */
public final class BgStockCostingDefaults {

  /**
   * Един ред: код по „Stock movement“, вид на запаса ({@code null} — всеки), виновно лице, сметка.
   */
  public static final class Row {
    public final String movementCode;
    public final String inventoryType;
    public final boolean withResponsiblePartner;
    public final String accountCode;

    Row(String movementCode, String inventoryType, boolean withResponsiblePartner, String account) {
      this.movementCode = movementCode;
      this.inventoryType = inventoryType;
      this.withResponsiblePartner = withResponsiblePartner;
      this.accountCode = account;
    }

    @Override
    public String toString() {
      return movementCode
          + (inventoryType == null ? "" : "/" + inventoryType)
          + (withResponsiblePartner ? "/лице" : "")
          + " → "
          + accountCode;
    }
  }

  /** Видовете запас по Nom_Inventory_Types: 10 материали, 20 продукция, 30 стоки. */
  static final String MATERIALS = "10";

  static final String PRODUCTION = "20";
  static final String GOODS = "30";

  public static final List<Row> ROWS =
      Collections.unmodifiableList(
          Arrays.asList(
              new Row("30", GOODS, false, "702"),
              new Row("30", PRODUCTION, false, "701"),
              new Row("30", MATERIALS, false, "706"),
              new Row("40", GOODS, false, "702"),
              new Row("40", PRODUCTION, false, "701"),
              new Row("40", MATERIALS, false, "706"),
              new Row("70", null, false, "60101"),
              new Row("120", null, false, "60905"),
              new Row("120", null, true, "442"),
              new Row("160", null, false, "60905"),
              new Row("170", null, false, "60905"),
              new Row("150", null, false, "60906"),
              new Row("65", null, false, "60909"),
              new Row("110", null, false, "709"),
              new Row("60", null, false, "709"),
              new Row("20", null, false, "611")));

  private BgStockCostingDefaults() {}
}
