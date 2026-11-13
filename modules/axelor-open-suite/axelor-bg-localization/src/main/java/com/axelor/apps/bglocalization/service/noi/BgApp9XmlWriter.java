package com.axelor.apps.bglocalization.service.noi;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ПИСАЧЪТ НА ФАЙЛА ПО {@code Pril9.xsd} — тагове в реда на схемата, екраниране, и НИТО ЕДИН
 * пропуснат елемент.
 *
 * <p><b>⚠️ ВСИЧКИ ТАГОВЕ ВИНАГИ ПРИСЪСТВАТ, И ТОВА Е ИЗИСКВАНЕ, НЕ СТИЛ.</b> Изискванията за прием
 * на файла, т. 2, са дословни: „Структурата ВИНАГИ съдържа всички тагове/секции с попълнени или
 * непопълнени стойности според случая. Когато не е избран цял раздел I. или II., структурата му
 * съдържа всички тагове/секции с непопълнени стойности или попълнени стойностите, означаващи, че не
 * е направен избор“. Затова тук няма условно пропускане на елемент: празното се пише като {@code
 * <Day></Day>}, а „не е избрано“ — като кода, който схемата дава за него (9 при {@code Code019}).
 *
 * <p><b>⚠️ И ЗАТОВА „ВСИЧКИ ТАГОВЕ“ НЕ Е СЪЩОТО КАТО „ТИХИ НУЛИ“.</b> Разликата минава по {@link
 * BgApp9MandatoryPoints}: структурно задължителен, но неотносим елемент се пише празен — това е
 * ДЕКЛАРИРАНОТО „няма избор“ на образеца. Задължително ОБСТОЯТЕЛСТВО без отговор изобщо не стига
 * дотук: емисията спира преди първия байт.
 *
 * <p><b>⚠️ Самозатварящ таг НЕ се пише.</b> {@code <Day/>} и {@code <Day></Day>} са едно и също за
 * XML, но приемащата страна е стара (схемата е редактирана с XMLSpy 2013) и празният елемент с
 * изричен затварящ таг е формата, която описанието показва. Цената е няколко байта.
 *
 * <p>Обектът НЕ Е нишково безопасен и се ползва веднъж, за един файл.
 */
final class BgApp9XmlWriter {

  /** Разделителят на редове. LF, а не CRLF: XML нормализира края на реда при четене. */
  private static final String EOL = "\n";

  private final StringBuilder sb = new StringBuilder(8192);
  private int depth;

  BgApp9XmlWriter() {
    sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>").append(EOL);
  }

  /** Отваря секция и слиза едно ниво. */
  void open(String name) {
    indent();
    sb.append('<').append(name).append('>').append(EOL);
    depth++;
  }

  /** Затваря секция и се качва едно ниво. */
  void close(String name) {
    depth--;
    indent();
    sb.append("</").append(name).append('>').append(EOL);
  }

  /** Прост елемент със стойност; {@code null} се пише като ПРАЗЕН елемент, не се пропуска. */
  void leaf(String name, String value) {
    indent();
    sb.append('<').append(name).append('>');
    escape(value == null ? "" : value);
    sb.append("</").append(name).append('>').append(EOL);
  }

  /** Цяло число или празно. */
  void leaf(String name, Integer value) {
    leaf(name, value == null ? "" : String.valueOf(value.intValue()));
  }

  /**
   * Сума по {@code moneyOrEmptyType} — две дробни, явна десетична ТОЧКА.
   *
   * <p>⚠️ НУЛАТА СЕ ПИШЕ КАТО ПРАЗНО. Типът {@code money} е {@code minExclusive = 0}, тоест „0.00“
   * НЕ е валидна стойност по схемата — файл с нулево възнаграждение в т. 10.1 би бил отхвърлен на
   * структурна проверка. Празно е единственият начин да се каже „няма сума“.
   */
  void money(String name, BigDecimal value) {
    if (value == null || value.signum() <= 0) {
      leaf(name, "");
      return;
    }
    leaf(name, value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString());
  }

  /**
   * Дата по {@code BDateType} — три елемента, всеки от които МОЖЕ да е празен.
   *
   * <p>Ползва се за всяка ДОПУСТИМО празна дата: срокове, прекратяване, заповеди.
   */
  void optionalDate(String name, LocalDate date) {
    open(name);
    leaf("Day", date == null ? "" : String.valueOf(date.getDayOfMonth()));
    leaf("Month", date == null ? "" : String.valueOf(date.getMonthValue()));
    leaf("Year", date == null ? "" : String.valueOf(date.getYear()));
    close(name);
  }

  /**
   * Дата по {@code FullDateType} — трите елемента са ЗАДЪЛЖИТЕЛНИ по схемата.
   *
   * <p>⚠️ Празна дата тук би дала файл, който пада на структурна проверка. Затова емитерът НИКОГА
   * не стига дотук с {@code null}: {@link BgApp9MandatoryPoints} спира преди това и назовава
   * полето. Проверката се повтаря и тук, защото мълчаливо произведен невалиден файл е точно
   * дефектът, който тази фаза не бива да създава.
   */
  void fullDate(String name, LocalDate date) {
    if (date == null) {
      throw new IllegalStateException(
          "пълна дата " + name + " е празна — емисията трябваше да спре при проверката на липсите");
    }
    open(name);
    leaf("Day", String.valueOf(date.getDayOfMonth()));
    leaf("Month", String.valueOf(date.getMonthValue()));
    leaf("Year", String.valueOf(date.getYear()));
    close(name);
  }

  /** Готовият документ. */
  String toXml() {
    return sb.toString();
  }

  private void indent() {
    for (int i = 0; i < depth; i++) {
      sb.append('\t');
    }
  }

  /**
   * Екранирането — петте знака, които XML не приема голи, плюс изхвърляне на управляващите.
   *
   * <p>⚠️ Управляващите знаци се МАХАТ, а не се екранират: {@code &#x1;} е синтактично валиден в
   * XML 1.0 точно колкото голият байт, тоест невалиден. Такъв знак идва от копиран текст в поле за
   * забележка и не носи смисъл — а изискванията за прием отхвърлят целия файл заради него.
   */
  private void escape(String value) {
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '&':
          sb.append("&amp;");
          break;
        case '<':
          sb.append("&lt;");
          break;
        case '>':
          sb.append("&gt;");
          break;
        case '"':
          sb.append("&quot;");
          break;
        case '\'':
          sb.append("&apos;");
          break;
        default:
          if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
            sb.append(c);
          }
          break;
      }
    }
  }
}
