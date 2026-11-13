/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 */
package com.axelor.apps.bglocalization.service.country;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * БЪЛГАРИЯ В НОМЕНКЛАТУРАТА НА ДЪРЖАВИТЕ — правилото като чиста функция, без база.
 *
 * <p><b>Капанът.</b> Ако България получи importId 99 (а bg seed-ът закачи шаблона RNT-BG и
 * локализацията bg_BG пак по 99), вносът на пълния списък държави на AOS (apps/demo-data), където
 * 99 е ЛАОС, а България е 30, презаписва реда с importId 99 на LAO и създава нова България. Всичко,
 * вързано за стария ред (адресите на дружествата, шаблонът), започва да показва Лаос, а истинската
 * България остава без шаблон: етикетът е „Регистрационен номер“ и проверките на ЕИК не тръгват.
 *
 * <p><b>Какво се проверява.</b> България (alpha2 BG) е точно веднъж и е с importId 30, както в
 * пълния списък — иначе следващ такъв внос пак ще презапише реда; шаблонът RNT-BG е само на нея;
 * bg_BG сочи нея; редът с alpha2 LA (ако го има) е Лаос (alpha3 LAO) и без RNT-BG. Името на Лаос не
 * се сверява: френският списък го пише „LAOS“.
 *
 * <p>Тук нищо не се чете и не се пише — входът е снимка на редовете, изходът са съобщения на
 * български. Четенето и известието са в {@link BgCountrySeedCheckService}.
 */
public final class BgCountrySeedCheck {

  public static final String BG_ALPHA2 = "BG";
  public static final String BG_IMPORT_ID = "30";
  public static final String LA_ALPHA2 = "LA";
  public static final String LA_ALPHA3 = "LAO";
  public static final String RNT_BG = "RNT-BG";
  public static final String BG_LOCALIZATION = "bg_BG";

  /** Проверката за четене, която показва препратките към сбърканите редове преди поправката. */
  public static final String CHECK_FILE =
      "modules/axelor-open-suite/axelor-bg-localization/src/main/resources/db/checks/"
          + "2026-10-23-country-references-before-swap.sql";

  /** Един ред на държава — само полетата, по които се съди. */
  public static final class CountryRow {
    final long id;
    final String importId;
    final String alpha2;
    final String alpha3;
    final String rntImportId;

    public CountryRow(long id, String importId, String alpha2, String alpha3, String rntImportId) {
      this.id = id;
      this.importId = importId;
      this.alpha2 = alpha2;
      this.alpha3 = alpha3;
      this.rntImportId = rntImportId;
    }
  }

  /**
   * Снимката: държавите с alpha2 BG или LA и всички с RNT-BG, плюс локализацията bg_BG. {@code
   * bgLocalizationExists=false} — редът bg_BG липсва; {@code bgLocalizationCountryId=null} при
   * съществуващ ред — той е без държава.
   */
  public static final class Snapshot {
    final List<CountryRow> countries;
    final boolean bgLocalizationExists;
    final Long bgLocalizationCountryId;

    public Snapshot(
        List<CountryRow> countries, boolean bgLocalizationExists, Long bgLocalizationCountryId) {
      this.countries = countries == null ? Collections.emptyList() : countries;
      this.bgLocalizationExists = bgLocalizationExists;
      this.bgLocalizationCountryId = bgLocalizationCountryId;
    }
  }

  private BgCountrySeedCheck() {}

  /** Нарушенията, по едно изречение; празен списък — всичко е наред. */
  public static List<String> violations(Snapshot snapshot) {
    List<String> out = new ArrayList<>();
    List<CountryRow> bg = withAlpha2(snapshot.countries, BG_ALPHA2);

    if (bg.isEmpty()) {
      out.add("Държавата България (BG) липсва в номенклатурата.");
    } else if (bg.size() > 1) {
      out.add(
          "България (BG) е "
              + bg.size()
              + " пъти в номенклатурата (id "
              + ids(bg)
              + ") — адресите и шаблонът се делят между редовете.");
    } else {
      CountryRow row = bg.get(0);
      if (!BG_IMPORT_ID.equals(row.importId)) {
        out.add(
            "България (id "
                + row.id
                + ") е с importId "
                + (row.importId == null ? "(празно)" : row.importId)
                + ", а в пълния списък държави на AOS е "
                + BG_IMPORT_ID
                + " — внос на този списък (демо данни) ще презапише или раздвои реда.");
      }
      if (!RNT_BG.equals(row.rntImportId)) {
        out.add(
            "България (id "
                + row.id
                + ") е без шаблона RNT-BG — етикетът „ЕИК/Булстат“ не излиза и проверките на ЕИК"
                + " не тръгват.");
      }
    }

    for (CountryRow row : snapshot.countries) {
      if (RNT_BG.equals(row.rntImportId) && !BG_ALPHA2.equals(upper(row.alpha2))) {
        out.add(
            "Шаблонът RNT-BG (ЕИК/Булстат) е закачен за държава "
                + (row.alpha2 == null ? "(без alpha2)" : row.alpha2)
                + " (id "
                + row.id
                + "), а не за България.");
      }
    }

    if (!snapshot.bgLocalizationExists) {
      out.add("Локализацията bg_BG липсва.");
    } else if (snapshot.bgLocalizationCountryId == null) {
      out.add("Локализацията bg_BG е без държава.");
    } else if (bg.stream().noneMatch(r -> r.id == snapshot.bgLocalizationCountryId)) {
      out.add(
          "Локализацията bg_BG сочи държава id "
              + snapshot.bgLocalizationCountryId
              + ", а не България.");
    }

    for (CountryRow row : withAlpha2(snapshot.countries, LA_ALPHA2)) {
      if (!LA_ALPHA3.equals(upper(row.alpha3))) {
        out.add(
            "Редът с alpha2 LA (id "
                + row.id
                + ") е с alpha3 "
                + (row.alpha3 == null ? "(празно)" : row.alpha3)
                + ", а Лаос е LAO.");
      }
    }
    return out;
  }

  /** Едно съобщение за лога и за администраторите; {@code null} — няма нарушения. */
  public static String summary(List<String> violations) {
    if (violations == null || violations.isEmpty()) {
      return null;
    }
    return "Номенклатурата на държавите е повредена: "
        + String.join(" ", violations)
        + " Поправката е в данните — миграция срещу базата"
        + "; препратките показва "
        + CHECK_FILE
        + ".";
  }

  /**
   * Предупреждението за локализацията по подразбиране на нов партньор; {@code null} — тя е bg_BG.
   * Не е нарушение: друга локализация може да е съзнателен избор.
   */
  public static String defaultPartnerLocalizationWarning(String code) {
    if (BG_LOCALIZATION.equals(code)) {
      return null;
    }
    return "Локализацията по подразбиране на нов партньор е "
        + (code == null ? "(празно)" : code)
        + ", а не bg_BG: новите партньори се печатат на този език и етикетът „ЕИК/Булстат“ не"
        + " излиза, докато потребителят не смени локализацията.";
  }

  private static List<CountryRow> withAlpha2(List<CountryRow> rows, String alpha2) {
    return rows.stream().filter(r -> alpha2.equals(upper(r.alpha2))).collect(Collectors.toList());
  }

  private static String ids(List<CountryRow> rows) {
    return rows.stream().map(r -> String.valueOf(r.id)).collect(Collectors.joining(", "));
  }

  private static String upper(String s) {
    return s == null ? null : s.trim().toUpperCase(Locale.ROOT);
  }
}
