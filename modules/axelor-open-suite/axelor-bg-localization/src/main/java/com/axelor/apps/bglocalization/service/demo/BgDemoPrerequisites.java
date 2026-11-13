package com.axelor.apps.bglocalization.service.demo;

import com.axelor.inject.Beans;
import com.axelor.studio.app.service.AppService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ПРИЛОЖЕНИЯТА, КОИТО НАБОРЪТ ИЗИСКВА — проверка преди наливането, с ясен отказ кое липсва.
 *
 * <p>Всеки раздел на набора иска своето приложение инсталирано и активно („Приложения“ в
 * администрацията): фактурите и статиите — Фактуриране и Счетоводство; справочните данни на BG
 * (НКИД, видове доход по ЗДДФЛ, държави, причини за отписване на ДМА и за складови корекции) идват
 * с App Install на BG Localization; складът — само ако наборът има склад; ТРЗ — служители, отпуски
 * и извънреден труд, само ако наборът има служители. Отказът изброява всички липсващи наведнъж, с
 * името и кода им.
 */
public final class BgDemoPrerequisites {

  /** Код на приложението → името му в „Приложения“ (apps/*.yml). */
  static final Map<String, String> ALWAYS = new LinkedHashMap<>();

  static {
    ALWAYS.put("base", "Base");
    ALWAYS.put("invoice", "Invoicing / Фактуриране");
    ALWAYS.put("account", "Accounting / Счетоводство");
    ALWAYS.put("bg-localization", "BG Localization");
  }

  private BgDemoPrerequisites() {}

  /**
   * Нужните приложения за набора: винаги + склад, ако има склад + банкови плащания, ако има
   * извлечения + ТРЗ, ако има служители.
   */
  static Map<String, String> required(BgDemoDataset dataset) {
    Map<String, String> out = new LinkedHashMap<>(ALWAYS);
    if (!BgDemoCsv.read(dataset, "stock_opening").isEmpty()) {
      out.put("stock", "Stock / Склад");
    }
    if (!BgDemoCsv.read(dataset, "bank_statements").isEmpty()) {
      out.put("bank-payment", "Bank payment / Банкови плащания");
    }
    if (!BgDemoCsv.read(dataset, "employees").isEmpty()) {
      out.put("employee", "Employees Management / Управление на служителите");
      out.put("leave", "Leave Management / Отпуски");
      out.put("exthrs", "Extra hours / Извънреден труд");
    }
    return out;
  }

  /** Отказите — по един ред на липсващо приложение; празно значи „може“. */
  public static List<String> refusals(BgDemoDataset dataset) {
    AppService apps = Beans.get(AppService.class);
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> e : required(dataset).entrySet()) {
      if (!apps.isApp(e.getKey())) {
        missing.add("„" + e.getValue() + "“ (" + e.getKey() + ")");
      }
    }
    List<String> out = new ArrayList<>();
    if (!missing.isEmpty()) {
      out.add(
          "Липсват приложения, нужни за демо данните: "
              + String.join(", ", missing)
              + ". Инсталирайте ги в Администрация › Приложения и опитайте отново.");
    }
    return out;
  }
}
