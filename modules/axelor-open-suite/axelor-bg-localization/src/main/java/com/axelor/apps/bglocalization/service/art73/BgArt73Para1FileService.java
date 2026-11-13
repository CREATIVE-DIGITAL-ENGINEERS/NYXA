package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Para1FileLayout;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileContent;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileLayout;
import com.google.inject.Singleton;
import java.io.IOException;
import javax.inject.Inject;

/**
 * СЪДЪРЖАНИЕ → БАЙТОВЕ за справката по чл. 73 ал. 1.
 *
 * <p><b>⚠️ НЕ МИНАВА ПРЕЗ {@code BgVatFileWriter}, И ТОВА Е ОБРАТНОТО НА Д1.</b> Онзи писач слепва
 * РЕДОВЕ с CRLF и кодира — вярното за дневниците по ДДС и за {@code EMPL2021.TXT}. Форматът по чл.
 * 73 е XML: редове няма, краят на реда се нормализира при четене, а CRLF след последния таг би бил
 * съдържание извън кореновия елемент. Оформлението дава ЦЕЛИЯ документ, тук той само се кодира.
 *
 * <p><b>⚠️ ЗНАК ИЗВЪН CP1251 ПАДА, А НЕ СТАВА ВЪПРОСИТЕЛНА.</b> {@code String.getBytes(charset)}
 * заменя непредставимия знак с „?“ МЪЛЧАЛИВО — и точно това вече ухапа ЕТЗ, където „ș“ стигна до
 * НАП като „?“. Име на чуждестранно лице с румънска, полска или гръцка буква е напълно нормален
 * вход; заменено, то дава файл, който НАП ПРИЕМА с изкривено име. Затова кодерът е нагласен на
 * {@link CodingErrorAction#REPORT} и отказът е изключение с назован знак.
 */
@Singleton
public class BgArt73Para1FileService {

  private final BgDeclarationFileLayout<BgArt73Para1FileContent> layout;

  /**
   * ⚠️ Параметърът е КОНКРЕТНИЯТ клас (Guice го свързва JIT, както целият ДДС-слой), а полето е
   * ИНТЕРФЕЙСЪТ — тоест услугата вижда само въпросите на формата, не реализацията им.
   */
  @Inject
  public BgArt73Para1FileService(BgArt73Para1FileLayout layout) {
    this.layout = layout;
  }

  /** Може ли изобщо да се произведе файл — питането ПРЕДИ опита. */
  public boolean isLayoutConfirmed() {
    return layout.isConfirmed();
  }

  /** Името на образеца за човешките съобщения. */
  public String formName() {
    return layout.formName();
  }

  /** Името на файла по заповедта. */
  public String fileName() {
    return layout.fileName();
  }

  /** Документът като текст — за валидация срещу схемата и за преглед, без повторно кодиране. */
  public String document(BgArt73Para1FileContent content) {
    layout.requireConfirmed();
    return layout.document(content);
  }

  /**
   * Байтовете на файла в кодировката, която заповедта иска.
   *
   * @throws IOException когато документът носи знак, който кодировката не представя
   */
  public byte[] toBytes(BgArt73Para1FileContent content) throws IOException {
    return encode(document(content), layout.encoding());
  }

  /**
   * Текст → байтове, с ОТКАЗ при непредставим знак.
   *
   * <p>⚠️ Делегира на {@link BgArt73FileEncoder}, откакто и ал. 6 иска същото кодиране. Оставен тук
   * като врата, защото свидетелите на ал. 1 го викат по това име.
   */
  static byte[] encode(String document, String encoding) throws IOException {
    return BgArt73FileEncoder.encode(document, encoding);
  }
}
