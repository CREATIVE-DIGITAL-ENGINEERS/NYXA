package com.axelor.apps.bglocalization.service.noi;

/**
 * НОМЕРЪТ НА БОЛНИЧНИЯ ЛИСТ — трите полета на Ф0, сведени до дванайсетте знака, които схемата иска.
 *
 * <p>Структурата е дословна от описанието на {@code PatientsChartNumber}: „1-ва позиция —
 * едносимволен буквен код. Допустими са букви — А, Б — кирилица или латиница, Е, Р — латиница; 2-5
 * позиции — 4-цифрено пълно означение на календарната година, в която е генериран номерът от НОИ;
 * 6-12 позиции — 7-цифрен нарастващ пореден номер С ВОДЕЩИ НУЛИ“.
 *
 * <p><b>⚠️ ТРИТЕ ПОЛЕТА СА ОТДЕЛНИ ВЪВ Ф0 НАРОЧНО, И ТУК СЕ СЛЕПВАТ САМО ЗА ФАЙЛА.</b> Домейнът на
 * листа го казва: „болничният лист се идентифицира от ТРОЙКАТА серия-номер-година … слепени в един
 * низ, те стават неразделими без разбор на формат, а форматът се е менял“. Тоест слепването е
 * свойство на ТОЗИ файл и живее тук, а не в записа.
 *
 * <p><b>⚠️ ЕДИН ДОПУСКАН ВХОД ПОВЕЧЕ, И ТОЙ Е ЕДНОЗНАЧЕН.</b> Ако {@code bgDocumentNumber} САМ вече
 * носи пълните дванайсет знака, той се приема както е. Двусмислица няма по построение: седемцифрен
 * пореден номер не може да съвпадне с образец „буква + 4 цифри + 7 цифри“. Практиката е реална —
 * операторът преписва номера от листа наведнъж, — а отказът заради нея би бил отказ по формат на
 * въвеждане, не по липса на данни.
 *
 * <p><b>⚠️ КИРИЛСКОТО „А“ И ЛАТИНСКОТО „A“ СА РАЗЛИЧНИ ЗНАЦИ И ДВАТА СА ДОПУСТИМИ.</b> Затова
 * наборът е изписан с кодови точки, а не с букви в кавички: в изходния текст двете са неразличими
 * за окото и разликата между приет и отхвърлен файл би зависела от това коя клавиатура е ползвана.
 * ⚠️ Е и Р са САМО латински — описанието е изрично, а кирилските им близнаци се пишат еднакво.
 */
public final class BgApp9ChartNumber {

  /** Общата дължина на номера. */
  public static final int LENGTH = 12;

  /**
   * Допустимите първи знаци.
   *
   * <p>U+0410 = кирилско А, U+0041 = латинско A, U+0411 = кирилско Б, U+0042 = латинско B, U+0045 =
   * латинско E, U+0050 = латинско P.
   */
  private static final String ALLOWED_PREFIXES = "АAБBEP";

  private BgApp9ChartNumber() {}

  /**
   * Слепва трите полета или връща {@code null}, когато номерът не може да се построи.
   *
   * <p>⚠️ Връща {@code null}, а НЕ частичен низ: „Е2026“ изглежда като номер и би тръгнал към НОИ.
   * Кой точно вход липсва казва {@link #describeMissing(String, Integer, String)}.
   */
  public static String compose(String series, Integer year, String number) {
    String trimmedNumber = trim(number);
    if (isWellFormed(trimmedNumber)) {
      return trimmedNumber;
    }
    String trimmedSeries = trim(series);
    if (trimmedSeries == null || year == null || trimmedNumber == null) {
      return null;
    }
    if (trimmedSeries.length() != 1 || year.intValue() < 1000 || year.intValue() > 9999) {
      return null;
    }
    String digits = trimmedNumber.replace(" ", "");
    if (digits.length() > 7 || !isDigits(digits)) {
      return null;
    }
    StringBuilder sb = new StringBuilder(LENGTH);
    sb.append(trimmedSeries).append(String.format("%04d", year.intValue()));
    for (int i = digits.length(); i < 7; i++) {
      sb.append('0');
    }
    sb.append(digits);
    String composed = sb.toString();
    return isWellFormed(composed) ? composed : null;
  }

  /** Отговаря ли низът на образеца: допустима буква, четирицифрена година, седем цифри. */
  public static boolean isWellFormed(String value) {
    if (value == null || value.length() != LENGTH) {
      return false;
    }
    if (ALLOWED_PREFIXES.indexOf(value.charAt(0)) < 0) {
      return false;
    }
    return isDigits(value.substring(1));
  }

  /**
   * ЦЯЛО ИЗРЕЧЕНИЕ за оператора: кое от трите поля пречи и къде се попълва.
   *
   * <p>Извиква се САМО когато {@link #compose} е върнало {@code null} — тоест съобщението никога не
   * се показва до успешно построен номер.
   *
   * <p><b>⚠️ ЦЕЛИЯТ НОМЕР В „НОМЕР“ СЕ ОБЯСНЯВА ПРЕДИ ТРИТЕ ПОЛЕТА.</b> Операторът, преписал
   * е-болничния наведнъж с кирилско Е, иначе чете „липсва серия; номерът съдържа знаци, които не са
   * цифри“ — тоест го пращат да попълва серия, която не му трябва, а истинската причина (буквата) е
   * невидима на екрана. Затова номер, който без невидимите знаци има ВИДА на целия номер (буква +
   * 11 цифри), получава своето обяснение и нищо за серия и година. Самата проверка ({@link
   * #compose}) не се мени: тук се мени само ТЕКСТЪТ на отказа.
   */
  public static String describeMissing(String series, Integer year, String number) {
    String whole = describeWholeNumber(number);
    if (whole != null) {
      return whole + WHERE;
    }
    StringBuilder sb = new StringBuilder();
    if (trim(series) == null) {
      sb.append("липсва серия на листа");
    } else if (trim(series).length() != 1) {
      sb.append("серията е ").append(trim(series).length()).append(" знака, а образецът иска ЕДИН");
    } else if (ALLOWED_PREFIXES.indexOf(trim(series).charAt(0)) < 0) {
      sb.append("серия „")
          .append(trim(series))
          .append("“ не е сред допустимите: А и Б се приемат")
          .append(" и на кирилица, и на латиница, а Е и Р — САМО на латиница");
      // ⚠️ Съобщението назовава капана изрично, защото на екрана двете букви са НЕРАЗЛИЧИМИ.
      // Оператор с българска подредба пише кирилско Е (U+0415) и вижда отказ за буква, която
      // изглежда точно като допустимата — без този ред той би сменял всичко друго преди серията.
      char first = trim(series).charAt(0);
      if (first == '\u0415' || first == '\u0420') {
        sb.append(". Въведената буква е КИРИЛСКА (")
            .append(first == '\u0415' ? "Е" : "Р")
            .append("), а изглежда точно като латинската — сменете подредбата на клавиатурата");
      }
    }
    if (year == null) {
      append(sb, "липсва година на листа");
    }
    if (trim(number) == null) {
      append(sb, "липсва номер на листа");
    } else if (invisibleIn(trim(number)) != null) {
      append(sb, invisibleIn(trim(number)));
    } else if (!isDigits(trim(number).replace(" ", ""))) {
      append(sb, "номерът съдържа знаци, които не са цифри");
    } else if (trim(number).replace(" ", "").length() > 7) {
      append(sb, "номерът е над 7 цифри");
    }
    if (sb.length() == 0) {
      sb.append("номерът не се сглобява в 12 знака по образеца");
    }
    return sb + WHERE;
  }

  private static final String WHERE =
      " — попълва се на формата на болничния лист (панел „Приложение № 9“)";

  /**
   * Обяснението за ЦЕЛИЯ номер, въведен в полето „Номер“, или {@code null}, ако входът няма неговия
   * вид (тогава важат трите полета).
   *
   * <p>„Вид на целия номер“ = след махане на невидимите знаци остават буква и 11 цифри. Така
   * отказът за кирилско Е и за невидим знак говори за това, което операторът е въвел, а не за
   * серия.
   */
  private static String describeWholeNumber(String number) {
    String t = trim(number);
    if (t == null) {
      return null;
    }
    StringBuilder visible = new StringBuilder(t.length());
    for (int i = 0; i < t.length(); i++) {
      if (!isInvisible(t.charAt(i))) {
        visible.append(t.charAt(i));
      }
    }
    String v = visible.toString();
    if (v.length() != LENGTH || !Character.isLetter(v.charAt(0)) || !isDigits(v.substring(1))) {
      return null;
    }
    StringBuilder sb = new StringBuilder();
    char first = v.charAt(0);
    if (first == 'Е' || first == 'Р') {
      // ⚠️ На екрана кирилското Е/Р и латинското E/P са НЕРАЗЛИЧИМИ — без това изречение
      // операторът сменя всичко друго преди буквата.
      sb.append("номерът започва с кирилско „")
          .append(first)
          .append("“, а образецът на НОИ допуска Е и Р САМО на латиница: буквата е кирилска –")
          .append(" сменете подредбата на клавиатурата на латиница и въведете номера наново");
    } else if (ALLOWED_PREFIXES.indexOf(first) < 0) {
      sb.append("номерът започва с „")
          .append(first)
          .append("“, а образецът допуска само А и Б (и на кирилица, и на латиница) и Е и Р")
          .append(" (САМО на латиница)");
    }
    String invisible = invisibleIn(t);
    if (invisible != null) {
      append(sb, invisible);
    }
    return sb.length() == 0 ? null : sb.toString();
  }

  /**
   * Изречение за ПЪРВИЯ невидим знак в стойността или {@code null}. Обикновеният интервал не е
   * невидим знак тук — той е видим при редакция и се маха при сглобяването на хартиения номер.
   */
  private static String invisibleIn(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      if (isInvisible(c)) {
        return "номерът съдържа НЕВИДИМ знак — "
            + invisibleName(c)
            + String.format(" (U+%04X)", (int) c)
            + " на позиция "
            + (i + 1)
            + ": на екрана не се вижда, но файлът го носи; изтрийте полето и въведете номера наново"
            + " на ръка, без копиране";
      }
    }
    return null;
  }

  private static boolean isInvisible(char c) {
    if (c == ' ') {
      return false;
    }
    int type = Character.getType(c);
    return Character.isWhitespace(c)
        || Character.isSpaceChar(c)
        || type == Character.FORMAT
        || type == Character.CONTROL;
  }

  private static String invisibleName(char c) {
    switch (c) {
      case ' ':
        return "неразделящ интервал";
      case ' ':
        return "тесен неразделящ интервал";
      case '​':
        return "интервал с нулева ширина";
      case '﻿':
        return "знак BOM с нулева ширина";
      case '­':
        return "мек дефис";
      case '\t':
        return "табулация";
      default:
        return "невидим знак";
    }
  }

  private static void append(StringBuilder sb, String text) {
    if (sb.length() > 0) {
      sb.append("; ");
    }
    sb.append(text);
  }

  private static boolean isDigits(String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (int i = 0; i < value.length(); i++) {
      if (value.charAt(i) < '0' || value.charAt(i) > '9') {
        return false;
      }
    }
    return true;
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String t = value.trim();
    return t.isEmpty() ? null : t;
  }
}
