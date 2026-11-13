package com.axelor.apps.bglocalization.service.vat.adapter;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * В КОЙ ДНЕВНИК МОЖЕ ДА СТОИ ВИДЪТ НА ДОКУМЕНТА ({@code bg.nap.document.type.select}) — чиста
 * функция. Полето „Вид на документа“ е 02-04 в PRODAGBI.TXT и 03-05 в POKUPKI.TXT.
 *
 * <p><b>Източникът (ППЗДДС, сверено дословно):</b>
 *
 * <ul>
 *   <li>Прил. № 12: „Полето „Вид на документа“ във файла „POKUPKI.ТХТ“ не може да съдържа код „04“,
 *       „50“, „81“, „82“, „83“, „84“ и „85“.“ и „… във файла „PRODAGBI.ТХТ“ не може да съдържа код
 *       „92“ и „05“.“
 *   <li>чл. 126 ал. 2 и 4: протоколът по чл. 126б ал. 2 и 7 ЗДДС — „в колона 3 „Вид на документа“
 *       на дневник за продажбите … с код „29““. Кредитното известие по чл. 126б ал. 1 (код 23) е в
 *       „отчетните регистри по чл. 124“, тоест в двата.
 *   <li>чл. 113б: протоколът за безвъзмездно предоставени хранителни стоки — „с код „95“ в дневника
 *       за продажби“.
 * </ul>
 *
 * <p>Касовата отчетност (11/12/13, 91/93/94) е и в двата: издадените документи и протоколи са в
 * дневника за продажби (чл. 104е ал. 9), получените — в дневника за покупки (чл. 104з ал. 3–4).
 *
 * <p>⚠️ 50, 83, 84 и 85 ги НЯМА: добавени с ДВ бр. 59/2022, в сила до 31.12.2023, и не са в
 * Nom_Invoice_Types. Непознат код няма дневник — {@link #diaryOf} е празно.
 *
 * <p>Днес никой не я вика: ползването ѝ (G5b, Блок Б) е по-късно. Тя е тук, за да бъде правилото
 * едно, когато дойде.
 */
public final class BgVatDocumentType {

  /** Дневникът, в който видът може да стои. */
  public enum Diary {
    SALES,
    PURCHASES,
    BOTH
  }

  private static final Map<String, Diary> DIARIES = diaries();

  private BgVatDocumentType() {}

  private static Map<String, Diary> diaries() {
    Map<String, Diary> m = new LinkedHashMap<>();
    m.put("01", Diary.BOTH); // Фактура
    m.put("02", Diary.BOTH); // Дебитно известие
    m.put("03", Diary.BOTH); // Кредитно известие
    m.put("04", Diary.SALES); // Регистър — изпратени под режим складиране до поискване
    m.put("05", Diary.PURCHASES); // Регистър — получени под режим складиране до поискване
    m.put("07", Diary.BOTH); // Митническа декларация
    m.put("09", Diary.BOTH); // Протокол или друг документ
    m.put("11", Diary.BOTH); // Фактура - касова отчетност
    m.put("12", Diary.BOTH); // Дебитно известие – касова отчетност
    m.put("13", Diary.BOTH); // Кредитно известие – касова отчетност
    m.put("23", Diary.BOTH); // Кредитно известие по чл. 126б, ал. 1
    m.put("29", Diary.SALES); // Протокол по чл. 126б, ал. 2 и 7
    m.put("81", Diary.SALES); // Отчет за извършените продажби
    m.put("82", Diary.SALES); // Отчет … при специален ред на облагане
    m.put("91", Diary.BOTH); // Протокол за изискуемия данък по чл. 151в, ал. 3
    m.put("92", Diary.PURCHASES); // Протокол за данъчния кредит по чл. 151г, ал. 8
    m.put("93", Diary.BOTH); // Протокол по чл. 151в, ал. 7 — получател без режима
    m.put("94", Diary.BOTH); // Протокол по чл. 151в, ал. 7 — получател с режима
    m.put("95", Diary.SALES); // Протокол за безвъзмездно предоставени хранителни стоки
    return Collections.unmodifiableMap(m);
  }

  /** Дневникът на вида; празно за непознат или празен код. Стойността се подрязва. */
  public static Optional<Diary> diaryOf(String code) {
    return Optional.ofNullable(code == null ? null : DIARIES.get(code.trim()));
  }

  /** Може ли видът да стои в PRODAGBI.TXT. */
  public static boolean allowedInSales(String code) {
    return diaryOf(code).map(d -> d != Diary.PURCHASES).orElse(false);
  }

  /** Може ли видът да стои в POKUPKI.TXT. */
  public static boolean allowedInPurchases(String code) {
    return diaryOf(code).map(d -> d != Diary.SALES).orElse(false);
  }

  /** Кодовете, които функцията познава — точно тези на селекцията и на Nom_Invoice_Types. */
  public static Set<String> codes() {
    return DIARIES.keySet();
  }
}
