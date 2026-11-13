package com.axelor.apps.bglocalization.service.art73;

import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Para6FileLayout;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileContent;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileLayout;
import com.google.inject.Singleton;
import java.io.IOException;
import javax.inject.Inject;

/**
 * СЪДЪРЖАНИЕ → БАЙТОВЕ за справката по чл. 73 ал. 6.
 *
 * <p>Огледално на {@code BgArt73Para1FileService}: оформлението дава ЦЕЛИЯ документ като текст, тук
 * той само се кодира. Виж {@link BgArt73FileEncoder} за това защо непредставимият знак ПАДА, вместо
 * да стане въпросителна.
 */
@Singleton
public class BgArt73Para6FileService {

  private final BgDeclarationFileLayout<BgArt73Para6FileContent> layout;

  /**
   * ⚠️ Guice подава реализацията, но полето — и целият останал клас — я държи през ИНТЕРФЕЙСА,
   * тоест услугата вижда само въпросите на формата, не реализацията им.
   */
  @Inject
  public BgArt73Para6FileService(BgArt73Para6FileLayout layout) {
    this.layout = layout;
  }

  /** Потвърден ли е физическият формат срещу заповед на изпълнителния директор на НАП. */
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
  public String document(BgArt73Para6FileContent content) {
    layout.requireConfirmed();
    return layout.document(content);
  }

  /**
   * Байтовете на файла в кодировката, която заповедта иска.
   *
   * @throws IOException когато документът носи знак, който кодировката не представя
   */
  public byte[] toBytes(BgArt73Para6FileContent content) throws IOException {
    return BgArt73FileEncoder.encode(document(content), layout.encoding());
  }
}
