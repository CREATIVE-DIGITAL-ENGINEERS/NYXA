package com.axelor.apps.bglocalization.service.noi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ИЗХОДЪТ НА ЕМИТЕРА — ИЛИ файл, ИЛИ назован списък с липси. Никога и двете, никога нито едно.
 *
 * <p><b>⚠️ ТОВА Е ЦЯЛАТА ИДЕЯ НА КЛАСА, И ТЯ Е УРОКЪТ НА Ф1, ПРОДЪЛЖЕН.</b> Ф1 показа какво става,
 * когато присъдите се произвеждат и се изхвърлят: в четирите генератора на фиша {@code
 * BgPaySlipService} вика {@code generate()} и не поглежда върнатия списък с откази. Тук отказът НЕ
 * МОЖЕ да се изхвърли, защото при отказ ПРОСТО НЯМА какво друго да се вземе — {@link #getXml()}
 * хвърля. Подател, който е решил да игнорира липсите, ще получи изключение, а не тих файл с нули.
 *
 * <p><b>⚠️ И затова липсите не са „предупреждения“.</b> Схемата приема файл, в който всяко „да/не“
 * е попълнено с 9 „не е избрано“ — тоест XSD зелено НЕ значи пълно удостоверение. Разликата между
 * двете живее тук.
 *
 * <p><b>⚠️ ИЗКЛЮЧЕНИТЕ ЛИСТОВЕ НЕ СА ЛИПСИ НА ФАЙЛА.</b> Месечният износ оставя ВЪН лист с липси в
 * собствените му точки и генерира файла с останалите. Липсите на изключения лист се носят отделно —
 * {@link #getExcluded()} — защото файлът е годен: смесени с {@link #getGaps()}, те щяха да обявят
 * готовия файл за отказан.
 *
 * <p>Обектът е НЕИЗМЕНЯЕМ.
 */
public final class BgApp9Emission {

  private final String xml;
  private final List<BgApp9Gap> gaps;
  private final int documentCount;
  private final String fileName;
  private final List<BgApp9Gap> excluded;

  private BgApp9Emission(
      String xml,
      List<BgApp9Gap> gaps,
      int documentCount,
      String fileName,
      List<BgApp9Gap> excluded) {
    this.xml = xml;
    this.gaps =
        gaps == null ? Collections.<BgApp9Gap>emptyList() : Collections.unmodifiableList(gaps);
    this.documentCount = documentCount;
    this.fileName = fileName;
    this.excluded =
        excluded == null
            ? Collections.<BgApp9Gap>emptyList()
            : Collections.unmodifiableList(new ArrayList<>(excluded));
  }

  private BgApp9Emission(String xml, List<BgApp9Gap> gaps, int documentCount, String fileName) {
    this(xml, gaps, documentCount, fileName, null);
  }

  /** Успех: файлът е произведен и няма нито една липса. */
  static BgApp9Emission emitted(String xml, int documentCount, String fileName) {
    return new BgApp9Emission(xml, Collections.<BgApp9Gap>emptyList(), documentCount, fileName);
  }

  /** Отказ: изброените липси спират емисията и НИТО ЕДИН байт не е произведен. */
  static BgApp9Emission refused(List<BgApp9Gap> gaps) {
    return new BgApp9Emission(null, gaps, 0, null);
  }

  /** Същата емисия, която носи и липсите на ИЗКЛЮЧЕНИТЕ листове. */
  BgApp9Emission withExcluded(List<BgApp9Gap> excludedGaps) {
    return new BgApp9Emission(xml, gaps, documentCount, fileName, excludedGaps);
  }

  /** Липсите на листовете, оставени ВЪН от файла. Празен списък, когато никой не е изключен. */
  public List<BgApp9Gap> getExcluded() {
    return excluded;
  }

  /**
   * Изключените листове — по един на удостоверение, в реда на първата им липса. Броят се по id
   * ({@link BgApp9Gap#declarationId}), не по етикета: две удостоверения на лице с два договора имат
   * един и същ етикет, а са два изключени листа.
   */
  public List<String> excludedDocuments() {
    Map<Object, String> out = new LinkedHashMap<>();
    for (BgApp9Gap gap : excluded) {
      out.putIfAbsent(gap.declarationId != null ? gap.declarationId : gap.document, gap.document);
    }
    return new ArrayList<>(out.values());
  }

  /** Спряна ли е емисията. */
  public boolean isRefused() {
    return xml == null;
  }

  /** Броят липси, които трябва да се поправят. Влиза в съобщението към оператора. */
  public int gapCount() {
    return gaps.size();
  }

  /** Липсите, в реда, в който се показват. Празен списък при успех. */
  public List<BgApp9Gap> getGaps() {
    return gaps;
  }

  /** Броят удостоверения във файла. Нула при отказ. */
  public int getDocumentCount() {
    return documentCount;
  }

  /** Името на файла по описанието: {@code Pril9_ddmmyyyy_n.xml}. {@code null} при отказ. */
  public String getFileName() {
    return fileName;
  }

  /**
   * Файлът.
   *
   * @throws IllegalStateException при отказ — виж класовия javadoc: тихият непълен файл е
   *     невъзможен по устройство, а не по дисциплина на подателя
   */
  public String getXml() {
    if (xml == null) {
      throw new IllegalStateException(
          "емисията е ОТКАЗАНА с "
              + gaps.size()
              + " липси — няма файл. Първата е: "
              + (gaps.isEmpty() ? "(няма)" : gaps.get(0).asNote()));
    }
    return xml;
  }

  /** Списъкът „какво липсва, за да е валидна“ — текстът, който операторът чете. */
  public String gapReport() {
    StringBuilder sb = new StringBuilder();
    for (BgApp9Gap gap : gaps) {
      if (sb.length() > 0) {
        sb.append('\n');
      }
      sb.append(gap.asNote());
    }
    return sb.toString();
  }
}
