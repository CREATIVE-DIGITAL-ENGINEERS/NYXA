package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;

/**
 * ОБЛАСТ, ОБЩИНА И НАСЕЛЕНО МЯСТО ЗА ПРИЛОЖЕНИЕ № 9 — ЕДИНСТВЕНИЯТ ЧЕТЕЦ, и за осигурителя ({@code
 * BulAddressFull}), и за лицето ({@code InsuredAddress}).
 *
 * <p><b>Източникът е ЗАПИСЪТ НА ГРАДА от българската номенклатура</b> ({@code City} с ЕКАТТЕ,
 * внесен от {@code bgplaces.csv}): {@code region} е областта, {@code municipality} — общината,
 * {@code name} — населеното място. Контролът на НОИ е дословен: „Задължителни са поне ОБЛАСТ и
 * НАСЕЛЕНО МЯСТО“.
 *
 * <p><b>⚠️ ЗАЩО НЕ {@code City.department} И НЕ СВОБОДНИЯТ ТЕКСТ НА АДРЕСА.</b> При българските
 * градове {@code department} (и на {@code Address}, и на {@code City}) е празно, защото
 * номенклатурата пази областта като текст в {@code region}: адрес „София — Столична — София
 * (столица) — 1000“ (ЕКАТТЕ 68134), четен от {@code department}, дава отказ „поне ОБЛАСТ и НАСЕЛЕНО
 * МЯСТО“. Свободният текст на адреса е втори източник за същото: той се разминава с града при
 * първата поправка и никой не вижда кой от двата е влязъл във файла.
 *
 * <p><b>Без ЕКАТТЕ — отказ ПОИМЕННО</b>, не догадка: град без код не е от номенклатурата, тоест
 * областта му не е проверима.
 */
public final class BgApp9Place {

  /** Областта ({@code City.region}). */
  public final String region;

  /** Общината ({@code City.municipality}) — по схемата не е задължителна. */
  public final String municipality;

  /** Населеното място ({@code City.name}). */
  public final String settlement;

  /** {@code null} = адресът е годен; иначе назованата причина — за отказа на износа. */
  public final String refusal;

  private BgApp9Place(String region, String municipality, String settlement, String refusal) {
    this.region = region;
    this.municipality = municipality;
    this.settlement = settlement;
    this.refusal = refusal;
  }

  /**
   * Адресът на ОСИГУРИТЕЛЯ — точното поле и формата, където човекът го попълва. ⚠️ ЕДИНСТВЕНИЯТ
   * източник е {@code Company.address} — полето „Адрес“ във формата на дружеството, същото, което
   * чете SAF-T Header. Не партньорът на дружеството: той може да няма нито един адрес, докато
   * дружеството има, и отказът би пратил човека към попълнено поле.
   */
  public static final String EMPLOYER_WHERE =
      "Дружество › поле „Адрес“ (с „Град“ от номенклатурата)";

  /** Адресът на ЛИЦЕТО — основният адрес на контакта на служителя. */
  public static final String PERSON_WHERE =
      "Служител › „Контакт“ › основният адрес (с „Град“ от номенклатурата)";

  /**
   * @param address адресът
   * @param whose чий е адресът — влиза в причината: „осигурителя“, „лицето“
   * @param where точното поле и формата, където се попълва — отказът ги назовава
   */
  public static BgApp9Place of(Address address, String whose, String where) {
    BgApp9Place place = of(address, whose);
    return place.refusal == null ? place : refused(place.refusal + " Попълва се: " + where + ".");
  }

  static BgApp9Place of(Address address, String whose) {
    if (address == null) {
      return refused(
          "няма адрес на "
              + whose
              + " — нужен е адрес с населено място от номенклатурата (ЕКАТТЕ).");
    }
    City city = address.getCity();
    if (city == null) {
      return refused(
          "адресът на "
              + whose
              + " не сочи населено място от номенклатурата — изберете „Град“ на адреса (с ЕКАТТЕ);"
              + " свободният текст в адреса не се чете.");
    }
    String shown =
        trim(city.getFullName()) != null ? trim(city.getFullName()) : trim(city.getName());
    if (trim(city.getEkatte()) == null) {
      return refused(
          "населеното място „"
              + shown
              + "“ на "
              + whose
              + " няма ЕКАТТЕ код — изберете го от българската номенклатура на градовете.");
    }
    String region = trim(city.getRegion());
    String settlement = trim(city.getName());
    if (region == null || settlement == null) {
      return refused(
          "населеното място „"
              + shown
              + "“ (ЕКАТТЕ "
              + trim(city.getEkatte())
              + ") няма "
              + (region == null ? "област" : "име")
              + " в номенклатурата.");
    }
    return new BgApp9Place(region, trim(city.getMunicipality()), settlement, null);
  }

  public boolean isUsable() {
    return refusal == null;
  }

  private static BgApp9Place refused(String reason) {
    return new BgApp9Place(null, null, null, reason);
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }
}
