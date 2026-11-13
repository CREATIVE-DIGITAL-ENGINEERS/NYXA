package com.axelor.apps.bglocalization.service.art73.adapter;

/**
 * ОТКЪДЕ Е СХЕМАТА НА СПРАВКАТА ПО ЧЛ. 73 АЛ. 6, КОЯ Е ТЯ И С КОЯ ЗАПОВЕД Е УТВЪРДЕНА — заковано в
 * код, огледално на {@link BgArt73Xsd} за ал. 1.
 *
 * <p><b>⚠️ ДРУГА ЗАПОВЕД, ДРУГА СХЕМА, ДРУГО ИМЕ НА ФАЙЛ.</b> Ал. 1 и ал. 6 се подават поотделно и
 * с РАЗЛИЧНИ заповеди — {@link BgArt73Xsd#ORDER} срещу {@link #ORDER}. Един общ пин щеше да значи,
 * че смяна на едната схема мълчаливо мести валидацията и на другата.
 *
 * <p><b>⚠️ СХЕМАТА Е ОЩЕ ПО-СЛАБ ПАЗАЧ ОТ ТАЗИ НА АЛ. 1.</b> В {@code dec736} дори {@code
 * incomedata} е {@code minOccurs="0"}: лице БЕЗ нито един ред доход минава структурно. Кое е
 * задължително ЗА ПРИЕМ и кое не приема отрицателна стойност живее в ЗАПОВЕДТА („Ред и формат",
 * раздел „Изисквания към съдържанието") и се пази от {@code BgArt73Para6FileValidator}. Заповедта,
 * т. 3: файл с липсващо задължително или некоректно попълнено поле се отхвърля ЦЕЛИЯТ.
 *
 * <p><b>⚠️ ОТПЕЧАТЪКЪТ Е ТУК, ЗА ДА СЕ ВИДИ ПОДМЯНА.</b> Файлът в {@code resources/schemas/nap/} е
 * копие; замени ли го някой, без да пипне този клас, {@code BgArt73Para6XsdPinTest} пада с назована
 * причина. Схемата е публикувана в CP1251 с CRLF и се пази БАЙТ ПО БАЙТ — {@code
 * schemas/.gitattributes} носи {@code *.xsd -text} точно затова.
 *
 * <p><b>⚠️ НУЛА МРЕЖА ПО ВРЕМЕ НА РАБОТА.</b> Валидацията е ОФЛАЙН, срещу това копие.
 */
public final class BgArt73Para6Xsd {

  private BgArt73Para6Xsd() {}

  /** Заповедта на изпълнителния директор на НАП, с която форматът е утвърден. */
  public static final String ORDER = "З-ЦУ-1877/05.12.2019 г.";

  /** Обхватът на заповедта — от коя дата нататък важи форматът. */
  public static final String APPLIES_FROM = "доходи, изплатени след 31.12.2018 г.";

  /** Датата, на която схемата е свалена и е проверено, че по-нова няма. */
  public static final String DOWNLOADED_ON = "2026-09-20";

  /** Пътят в classpath-а до копието на схемата. */
  public static final String RESOURCE = "/schemas/nap/dec736_SPR73_6.xsd";

  /** Точният размер на закрепения файл в байтове. */
  public static final long SCHEMA_BYTE_LENGTH = 18841L;

  /** SHA-256 на закрепения файл — сверява се от {@code BgArt73Para6XsdPinTest}. */
  public static final String SCHEMA_SHA256 =
      "f810062f9f6cd0dbdd0db8bd8e873f89b350271776f2eb9357e73eb802b05c32";

  /** Кореновият елемент, който схемата описва. */
  public static final String ROOT_ELEMENT = "dec736";

  /**
   * Името на файла по заповедта.
   *
   * <p><b>⚠️ БЕЗ ЕИК И БЕЗ ГОДИНА В ИМЕТО</b> — същото както при ал. 1: и двете се носят ВЪТРЕ във
   * файла ({@code part1/eik} и {@code year}), тоест суфикс би бил наша измислица.
   */
  public static final String FILE_NAME = "SPR73_6.xml";

  /** Кодировката по заповедта. */
  public static final String ENCODING = "WINDOWS-1251";

  /** Едноредово описание за доклада и за екрана: срещу КОЯ схема е валидиран файлът. */
  public static String describe() {
    return "dec736 ("
        + FILE_NAME
        + ", заповед "
        + ORDER
        + ", свалена "
        + DOWNLOADED_ON
        + ", sha256 "
        + SCHEMA_SHA256.substring(0, 12)
        + "…)";
  }
}
