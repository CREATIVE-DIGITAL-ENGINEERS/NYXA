package com.axelor.apps.aiocr.service.readiness;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * This module's {@code messages_bg.csv}, for tests: {@code I18n} reads translations from the
 * database, so the Bulgarian text is rendered from the file instead.
 */
public final class BgMessages {

  private BgMessages() {}

  private static Map<String, String> cache;

  public static synchronized Map<String, String> all() {
    if (cache == null) {
      Map<String, String> out = new LinkedHashMap<>();
      try {
        List<String> lines =
            Files.readAllLines(
                Paths.get("src/main/resources/i18n/messages_bg.csv"), StandardCharsets.UTF_8);
        for (String line : lines.subList(1, lines.size())) {
          List<String> cells = csv(line);
          if (cells.size() >= 2) {
            out.putIfAbsent(cells.get(0), cells.get(1));
          }
        }
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      cache = out;
    }
    return cache;
  }

  /** The Bulgarian translation; fails the test when the key has none. */
  public static final Function<String, String> BG =
      key -> {
        String text = all().get(key);
        assertNotNull(text, "no Bulgarian text in messages_bg.csv for: " + key);
        return text;
      };

  /** One CSV line, quotes and doubled quotes; the file has no line breaks in a cell. */
  private static List<String> csv(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          cell.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        cells.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    cells.add(cell.toString());
    return cells;
  }
}
