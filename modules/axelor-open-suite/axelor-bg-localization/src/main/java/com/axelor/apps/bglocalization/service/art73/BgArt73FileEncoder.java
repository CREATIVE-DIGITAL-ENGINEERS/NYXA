package com.axelor.apps.bglocalization.service.art73;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;

/**
 * ТЕКСТ → БАЙТОВЕ В КОДИРОВКАТА НА ЗАПОВЕДТА, С ОТКАЗ ПРИ НЕПРЕДСТАВИМ ЗНАК.
 *
 * <p><b>⚠️ ЕДИН ЕНКОДЕР ЗА ДВЕТЕ АЛИНЕИ.</b> Ал. 1 ({@code dec731}) и ал. 6 ({@code dec736}) са
 * различни схеми с различни заповеди, но кодирането е едно и също решение. Преписан за втората,
 * кодът щеше да се разминава при първата поправка — и то мълчаливо, защото и двата варианта връщат
 * байтове.
 *
 * <p><b>⚠️ ЗНАК ИЗВЪН CP1251 ПАДА, А НЕ СТАВА ВЪПРОСИТЕЛНА.</b> {@code String.getBytes(charset)}
 * заменя непредставимия знак с „?“ МЪЛЧАЛИВО — и точно това вече ухапа ЕТЗ, където „ș“ стигна до
 * НАП като „?“. Име на чуждестранно лице с румънска, полска или гръцка буква е напълно нормален
 * вход; заменено, то дава файл, който НАП ПРИЕМА с изкривено име. Затова кодерът е нагласен на
 * {@link CodingErrorAction#REPORT}.
 */
final class BgArt73FileEncoder {

  private BgArt73FileEncoder() {}

  /**
   * Кодира документа, или хвърля с назована причина.
   *
   * @throws IOException когато документът носи знак, който кодировката не представя
   */
  static byte[] encode(String document, String encoding) throws IOException {
    Charset charset = Charset.forName(encoding);
    CharsetEncoder encoder =
        charset
            .newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      ByteBuffer buffer = encoder.encode(CharBuffer.wrap(document));
      byte[] bytes = new byte[buffer.remaining()];
      buffer.get(bytes);
      return bytes;
    } catch (CharacterCodingException e) {
      throw new IOException(
          "Документът съдържа знак, който "
              + encoding
              + " не представя, а заповедта иска точно тази кодировка. Поправи данните (най-често"
              + " име на чуждестранно лице с буква извън кирилицата и латиницата) — замяната с „?“"
              + " би дала файл, който НАП ПРИЕМА с изкривено съдържание.",
          e);
    }
  }
}
