package com.axelor.apps.bglocalization.service.art73.adapter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * ПИСАЧЪТ НА XML-А ПО {@code dec731} — тагове в реда на образеца, екраниране и нито един измислен
 * елемент.
 *
 * <p><b>⚠️ ПРОПУСНАТИЯТ ЕЛЕМЕНТ Е ОТГОВОР, А НЕ ЛИПСА — И ТОВА Е ОБРАТНОТО НА {@code
 * BgApp9XmlWriter}.</b> Схемата на НОИ иска ВСИЧКИ тагове винаги, с празни стойности за
 * неизбраното; {@code dec731} е построена другояче — почти всеки елемент е {@code minOccurs="0"}, а
 * заповедта изрично забранява да се пълнят определени полета за определени кодове („задължително да
 * НЕ е попълнено“). Празен таг там НЕ е същото като липсващ: той твърди попълнена стойност. Затова
 * тук {@code null} значи ПРОПУСКАНЕ, не {@code <tag></tag>}.
 *
 * <p><b>⚠️ ДЕКЛАРАЦИЯТА КАЗВА WINDOWS-1251, И БАЙТОВЕТЕ ТРЯБВА ДА Я СЛЕДВАТ.</b> Заповедта, раздел
 * „Изисквания към съдържанието“, т. 7. Тук се пише само декларацията — кодирането е на {@code
 * BgArt73Para1FileService}, който отказва при знак, който CP1251 не носи, вместо да го замени с
 * въпросителна.
 *
 * <p>Обектът НЕ Е нишково безопасен и се ползва веднъж, за един файл.
 */
final class BgArt73XmlWriter {

  /** Разделителят на редове. LF: XML нормализира края на реда при четене. */
  private static final String EOL = "\n";

  private final StringBuilder sb = new StringBuilder(8192);
  private int depth;

  /** Писач за ал. 1 — декларацията носи кодировката от {@link BgArt73Xsd}. */
  BgArt73XmlWriter() {
    this(BgArt73Xsd.ENCODING);
  }

  /**
   * Писач за посочената кодировка.
   *
   * <p><b>⚠️ КОДИРОВКАТА Е ПАРАМЕТЪР, ЗАЩОТО ЗАПОВЕДИТЕ СА ДВЕ.</b> Днес и {@code dec731}, и {@code
   * dec736} искат {@code WINDOWS-1251}, тоест закована тук стойност би работила. Но тя щеше да
   * твърди, че двата формата СПОДЕЛЯТ едно решение, а те просто съвпадат — смени ли НАП едната
   * заповед, другият файл щеше да тръгне с чужда декларация, без нито един тест да падне.
   */
  BgArt73XmlWriter(String encoding) {
    sb.append("<?xml version=\"1.0\" encoding=\"").append(encoding).append("\"?>").append(EOL);
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

  /**
   * Текстов елемент. ⚠️ {@code null} и празният низ СЕ ПРОПУСКАТ — виж класовия javadoc.
   *
   * <p>⚠️ Не се реже по дължина: надхвърлена дължина е ДЕФЕКТ НА ДАННИТЕ и трябва да падне на
   * валидатора със съобщение, а не да пътува мълчаливо орязана до НАП.
   */
  void text(String name, String value) {
    if (value == null || value.trim().isEmpty()) {
      return;
    }
    indent();
    sb.append('<').append(name).append('>');
    escape(value.trim());
    sb.append("</").append(name).append('>').append(EOL);
  }

  /** Цяло число; {@code null} се пропуска. */
  void integer(String name, Integer value) {
    if (value == null) {
      return;
    }
    indent();
    sb.append('<').append(name).append('>').append(value.intValue());
    sb.append("</").append(name).append('>').append(EOL);
  }

  /**
   * Сума — две дробни, явна десетична ТОЧКА.
   *
   * <p><b>⚠️ „0.00“ СЕ ПИШЕ, КОГАТО ЕЛЕМЕНТЪТ Е ЗАДЪЛЖИТЕЛЕН.</b> {@code income} е {@code
   * minOccurs="1"}, а нула е законен отговор за необлагаемите доходи по Част ІV, които чл. 73 ал. 1
   * т. 4 изрично иска ОПИСАНИ. Пропуснат, задължителният елемент вали файла на схемата.
   *
   * <p>⚠️ {@code xs:float} в схемата НЕ значи експоненциален запис: „0.00“ е валиден {@code float}
   * и е формата, който образецът показва („в левове и стотинки до втория десетичен знак“, указание
   * за попълване т. 10). {@code Float.toString} би дал „1200.0“, а при голяма сума и „1.2E7“.
   */
  /*
   * ⚠️ ВАЛУТАТА ЗА 2026 Е ОТВОРЕН ВЪПРОС, И ТУК НЕ СЕ КОНВЕРТИРА. Заповедта (2019) и
   * указанията (2025) казват „в левове и стотинки“; от 01.01.2026 г. данните в този модул са
   * НАТИВНО в евро. Пише се ЗАПИСАНОТО число: мълчаливо умножение по 1.95583 тук би дало файл,
   * който изглежда правдоподобен и в двата случая, а грешката се вижда чак при засичане от НАП.
   *
   */
  void amount(String name, BigDecimal value, boolean required) {
    if (value == null) {
      if (!required) {
        return;
      }
      indent();
      sb.append('<').append(name).append(">0.00</").append(name).append('>').append(EOL);
      return;
    }
    indent();
    sb.append('<').append(name).append('>');
    sb.append(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
    sb.append("</").append(name).append('>').append(EOL);
  }

  /** Дата по {@code xs:date} — ГГГГ-ММ-ДД; {@code null} се пропуска. */
  void date(String name, LocalDate value) {
    if (value == null) {
      return;
    }
    text(name, value.toString());
  }

  /** Готовият документ. */
  String document() {
    return sb.toString();
  }

  private void indent() {
    for (int i = 0; i < depth; i++) {
      sb.append('\t');
    }
  }

  /**
   * ⚠️ И петте предефинирани обекта, не само трите „очевидни“. Апострофът и кавичката не могат да
   * се появят в текстов възел като проблем, но екранирани навсякъде, писачът не зависи от това къде
   * е извикан — а имена от вида „О'Брайън“ съществуват.
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
          sb.append(c);
      }
    }
  }
}
