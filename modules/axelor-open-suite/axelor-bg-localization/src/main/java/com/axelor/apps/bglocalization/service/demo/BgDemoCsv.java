package com.axelor.apps.bglocalization.service.demo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ФАЙЛ ОТ ДЕМО НАБОРА — {@code demo-data/<набор>/<име>.csv} в classpath-а на модула.
 *
 * <p>Форматът е този на останалите seed файлове в репото: UTF-8, разделител {@code ;}, стойностите
 * по желание в двойни кавички (удвоена кавичка вътре е една кавичка), първият ред е заглавие. Ред,
 * който започва с {@code #}, е коментар. Празен ред се пропуска.
 */
public final class BgDemoCsv {

  /** Един ред: колона → стойност (празна клетка е празен низ, не {@code null}). */
  public static final class Row {
    private final String file;
    private final int line;
    private final Map<String, String> values;

    Row(String file, int line, Map<String, String> values) {
      this.file = file;
      this.line = line;
      this.values = values;
    }

    public String get(String column) {
      if (!values.containsKey(column)) {
        throw new IllegalArgumentException(where() + ": няма колона „" + column + "“");
      }
      return values.get(column);
    }

    public String opt(String column) {
      String v = values.get(column);
      return v == null ? "" : v;
    }

    public boolean has(String column) {
      return !opt(column).isEmpty();
    }

    public BigDecimal decimal(String column) {
      String v = get(column);
      try {
        return v.isEmpty() ? BigDecimal.ZERO : new BigDecimal(v);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(where() + ": „" + v + "“ в " + column + " не е число");
      }
    }

    public int integer(String column) {
      String v = get(column);
      try {
        return v.isEmpty() ? 0 : Integer.parseInt(v);
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(where() + ": „" + v + "“ в " + column + " не е цяло");
      }
    }

    public boolean bool(String column) {
      return "true".equalsIgnoreCase(opt(column)) || "1".equals(opt(column));
    }

    /** Файл и ред — за съобщенията при грешни данни. */
    public String where() {
      return file + ":" + line;
    }
  }

  private BgDemoCsv() {}

  /** Пътят до файла в classpath-а. */
  public static String resource(BgDemoDataset dataset, String name) {
    return "demo-data/" + dataset.folder() + "/" + name + ".csv";
  }

  /** Всички редове на файла; липсващ файл е празен списък (наборът може да няма такъв раздел). */
  public static List<Row> read(BgDemoDataset dataset, String name) {
    String path = resource(dataset, name);
    InputStream in = BgDemoCsv.class.getClassLoader().getResourceAsStream(path);
    if (in == null) {
      return Collections.emptyList();
    }
    try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
      return parse(path, r);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  static List<Row> parse(String file, BufferedReader r) throws IOException {
    List<Row> rows = new ArrayList<>();
    List<String> header = null;
    String text;
    int line = 0;
    while ((text = r.readLine()) != null) {
      line++;
      if (line == 1 && text.startsWith("﻿")) {
        text = text.substring(1);
      }
      if (text.trim().isEmpty() || text.startsWith("#")) {
        continue;
      }
      List<String> cells = split(text);
      if (header == null) {
        header = cells;
        continue;
      }
      if (cells.size() != header.size()) {
        throw new IllegalArgumentException(
            file
                + ":"
                + line
                + ": "
                + cells.size()
                + " клетки при "
                + header.size()
                + " колони в заглавието");
      }
      Map<String, String> values = new LinkedHashMap<>();
      for (int i = 0; i < header.size(); i++) {
        values.put(header.get(i), cells.get(i));
      }
      rows.add(new Row(file, line, values));
    }
    return rows;
  }

  static List<String> split(String text) {
    List<String> out = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"') {
          if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
            cell.append('"');
            i++;
          } else {
            quoted = false;
          }
        } else {
          cell.append(c);
        }
      } else if (c == '"' && cell.toString().trim().isEmpty()) {
        // Кавичка отваря само в началото на клетката; вътре е знак (Монитор 27").
        cell.setLength(0);
        quoted = true;
      } else if (c == ';') {
        out.add(cell.toString().trim());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    out.add(cell.toString().trim());
    return out;
  }
}
