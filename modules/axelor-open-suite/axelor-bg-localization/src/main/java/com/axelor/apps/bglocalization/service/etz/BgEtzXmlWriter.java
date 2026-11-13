package com.axelor.apps.bglocalization.service.etz;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.UnmappableCharacterException;

/**
 * ПИСАЧЪТ НА ЕТЗ ФАЙЛА — <b>WINDOWS-1251, и то докрай</b>.
 *
 * <p><b>⚠️ КОДИРАНЕТО Е ДВЕ РАЗЛИЧНИ НЕЩА И ТЕ СЕ БЪРКАТ ЛЕСНО.</b> Спецификацията на API-то казва
 * „За кодиране на данните трябва да се използва UTF-8“ — това е за JSON ОБВИВКАТА на заявката.
 * Файлът вътре в нея е отделен байтов низ, който се подава base64-кодиран, и НАП го проверява
 * поотделно: пакетът носи два отделни кода за грешка, {@code API_XML_ENCODING_HEADER} („Грешно
 * зададено кодиране за XML файла“) и {@code API_XML_ENCODING_FILE} („Грешно кодиране на файла“).
 * Тоест обвивката е UTF-8, а записът — WINDOWS-1251, и това не е противоречие.
 *
 * <p><b>⚠️ И ДВАТА КОДА ЗА ГРЕШКА СА ОТДЕЛНИ, ЗАЩОТО ДВЕТЕ ГРЕШКИ СА ОТДЕЛНИ.</b> Първата е
 * ДЕКЛАРАЦИЯТА ({@code <?xml … encoding="…"?>}), втората са БАЙТОВЕТЕ. Файл с вярна декларация и
 * UTF-8 байтове минава първата проверка и пада на втората — затова тук двете не могат да се
 * разминат по конструкция: декларацията се пише от {@link #XML_ENCODING}, а байтовете излизат от
 * {@link #CHARSET}, и {@link #toBytes()} е единственият изход.
 *
 * <p><b>⚠️ НЕПРЕДСТАВИМ ЗНАК ХВЪРЛЯ, А НЕ СТАВА ВЪПРОСИТЕЛНА.</b> Подразбирането на Java при
 * кодиране е ЗАМЯНА: знак извън WINDOWS-1251 тихо става {@code ?}. За име на латиница с диакритика
 * („Ceaușescu“) или за случайно попаднало типографско тире това значи запис, който се вписва в
 * регистъра с осакатено име — валиден файл с невярно съдържание, тоест дефектът, който не пада
 * никъде. Затова кодерът е нагласен на {@link CodingErrorAction#REPORT} и {@link #toBytes()}
 * назовава знака и полето.
 *
 * <p><b>⚠️ ИМЕНАТА НА ЕЛЕМЕНТИТЕ ИДВАТ ОТВЪН.</b> Писачът не знае нито един таг на ЕТЗ записа и
 * това е нарочно: те са в XSD схемата, а тя не е набавена ({@link BgEtzXsd}). Класът е ГОТОВ и
 * проверен — липсва му само речникът с имената. Виж {@link BgEtzSchemaBinding}.
 *
 * <p>Обектът НЕ Е нишково безопасен и се ползва веднъж, за един файл.
 */
public final class BgEtzXmlWriter {

  /** Името на кодировката, както влиза в декларацията на XML-а. */
  public static final String XML_ENCODING = "WINDOWS-1251";

  /** Същата кодировка като {@link Charset} — байтовете и декларацията НЕ могат да се разминат. */
  public static final Charset CHARSET = Charset.forName("windows-1251");

  /** Разделителят на редове. LF: XML нормализира края на реда при четене. */
  private static final String EOL = "\n";

  private final StringBuilder sb = new StringBuilder(4096);
  private int depth;

  public BgEtzXmlWriter() {
    sb.append("<?xml version=\"1.0\" encoding=\"").append(XML_ENCODING).append("\"?>").append(EOL);
  }

  /** Отваря секция и слиза едно ниво. */
  public void open(String name) {
    indent();
    sb.append('<').append(name).append('>').append(EOL);
    depth++;
  }

  /** Затваря секция и се качва едно ниво. */
  public void close(String name) {
    depth--;
    indent();
    sb.append("</").append(name).append('>').append(EOL);
  }

  /**
   * Прост елемент със стойност; {@code null} се пише като ПРАЗЕН елемент, не се пропуска.
   *
   * <p>⚠️ Самозатварящ таг не се пише — {@code <X></X>}, не {@code <X/>}. Двете са едно и също за
   * XML, но приемащите страни на НАП/НОИ са стари и описанията показват изричния затварящ таг.
   * Цената е няколко байта.
   */
  public void leaf(String name, String value) {
    indent();
    sb.append('<').append(name).append('>');
    escape(value == null ? "" : value);
    sb.append("</").append(name).append('>').append(EOL);
  }

  /** Текстът дотук, БЕЗ кодиране. За четене в тест и за съобщения; не е това, което се подава. */
  public String toXml() {
    return sb.toString();
  }

  /**
   * БАЙТОВЕТЕ — единственият изход, който бива да стигне до подаване.
   *
   * @throws BgEtzEncodingException когато знак не е представим в WINDOWS-1251
   */
  public byte[] toBytes() {
    return encode(sb.toString());
  }

  /** Кодирането, изнесено, за да може да се проверява самостоятелно. */
  public static byte[] encode(String text) {
    CharsetEncoder encoder =
        CHARSET
            .newEncoder()
            // ⚠️ И ДВЕТЕ СА ЗАДЪЛЖИТЕЛНИ. malformedInput пази срещу счупена двойка сурогати,
            // unmappableCharacter — срещу знак, който просто го няма в таблицата. Подразбирането и
            // на двете е REPLACE, тоест мълчаливо „?“.
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      ByteBuffer buffer = encoder.encode(CharBuffer.wrap(text));
      byte[] out = new byte[buffer.remaining()];
      buffer.get(out);
      return out;
    } catch (UnmappableCharacterException e) {
      throw new BgEtzEncodingException(
          "текстът съдържа знак, който не съществува в "
              + XML_ENCODING
              + " — "
              + describeOffender(text)
              + ". ⚠️ Мълчаливата замяна с „?“ е изключена нарочно: тя дава ВАЛИДЕН файл с"
              + " осакатено"
              + " съдържание, който се вписва в регистъра и се поправя само с код корекция.",
          e);
    } catch (CharacterCodingException e) {
      throw new BgEtzEncodingException(
          "текстът не може да се кодира в " + XML_ENCODING + ": " + e.getMessage(), e);
    }
  }

  /** Намира ПЪРВИЯ непредставим знак и го описва четимо — иначе отказът е безадресен. */
  private static String describeOffender(String text) {
    CharsetEncoder probe = CHARSET.newEncoder();
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (!probe.canEncode(c)) {
        return "знак „" + c + "“ (U+" + String.format("%04X", (int) c) + ") на позиция " + i;
      }
    }
    return "непредставим знак не бе намерен при повторния прочит";
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
   * XML 1.0 точно колкото голия байт, тоест невалиден. Такъв знак идва от копиран текст и не носи
   * смисъл, а отхвърля целия файл.
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

  /** Отказът при непредставим знак — отделен тип, за да се лови точно той. */
  public static class BgEtzEncodingException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    public BgEtzEncodingException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
