package com.axelor.apps.bglocalization.service.etz;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ЕДИН ФАЙЛ — заглавната част плюс редовете.
 *
 * <p><b>⚠️ ЕДИН ФАЙЛ = ЕДИН ЕИК, И ТОВА Е ИЗИСКВАНЕ, НЕ ПОДРЕДБА.</b> Приложение № 1: „Един файл
 * следва да съдържа данни само за един ЕИК на работодател“. При чл. 123 изискването е ДВОЙНО — един
 * ЕИК на нов И един на предишен работодател — и към него се добавя още едно: в един файл полетата
 * 2, 3, 4, 5 и 13 трябва да са ЕДНАКВИ за всички редове. Затова ЕИК-овете живеят тук, на файла, а
 * не в реда.
 *
 * <p><b>⚠️ МНОГО РЕДА В ЕДИН ФАЙЛ Е НОРМАЛНОТО СЪСТОЯНИЕ.</b> {@code rowenum} е {@code
 * maxOccurs="unbounded"} и при прехвърляне по чл. 123 файлът носи всички прехвърлени лица наведнъж.
 */
public final class BgEtzFile {

  private final BgEtzEventKind kind;
  private final Map<String, String> header = new LinkedHashMap<>();
  private final List<BgEtzRecord> rows = new ArrayList<>();

  public BgEtzFile(BgEtzEventKind kind) {
    if (kind == null) {
      throw new IllegalArgumentException(
          "няма вид събитие — без него не се знае коя схема се ползва");
    }
    this.kind = kind;
  }

  public BgEtzEventKind getKind() {
    return kind;
  }

  /** Схемата, срещу която този файл се валидира. */
  public BgEtzXsd.Schema getSchema() {
    return BgEtzXsd.forEvent(kind);
  }

  /** Слага стойност в заглавната част ({@code eik}, {@code formereik}). */
  public void putHeader(String tag, String value) {
    if (value != null && !value.trim().isEmpty()) {
      header.put(tag, value.trim());
    }
  }

  public String getHeader(String tag) {
    return header.get(tag);
  }

  public Map<String, String> getHeaders() {
    return Collections.unmodifiableMap(header);
  }

  public void add(BgEtzRecord row) {
    if (row != null) {
      rows.add(row);
    }
  }

  public List<BgEtzRecord> getRows() {
    return Collections.unmodifiableList(rows);
  }

  /** Броят редове — влиза в отчета; ⚠️ НЕ се подава като {@code numRecords} (виж плана). */
  public int size() {
    return rows.size();
  }
}
