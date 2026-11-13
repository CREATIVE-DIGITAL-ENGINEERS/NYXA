package com.axelor.apps.bglocalization.i18n;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОТКАЗИТЕ НА БЪЛГАРСКИ: всяко действие, което контролер подава на гейта ({@code
 * CapabilityGate.requireRole}, {@code NapFilingGate.require} и помощниците {@code managed}/{@code
 * writable}), и всеки отказ „You are not allowed …“, подаден на {@code I18n.get}, има български
 * превод в {@code messages_bg.csv} на СЪЩИЯ модул.
 *
 * <p>Гейтът превежда описанието на действието с {@code I18n.get}; без ред в CSV-то отказът излиза
 * наполовина на английски. Кодът се чете от дървото на репото, не от classpath-а — така тестът
 * вижда и съседните модули (НАП, AI).
 */
class BgRefusalTranslationTest {

  private static final Path MODULES = Paths.get("..");

  /** Литерал или константа като първи аргумент на гейта или на помощниците му. */
  private static final Pattern GATE_ARG =
      Pattern.compile(
          "(?:requireRole|requireDifferentUser|NapFilingGate\\.require|managed|writable)\\(\\s*"
              + "(?:request,\\s*(?:response,\\s*)?)?(?:\"([^\"]+)\"|([A-Z][A-Z_]+)\\s*[,)])");

  private static final Pattern CONSTANT =
      Pattern.compile(
          "static final String ([A-Z][A-Z_]+)\\s*=\\s*(?:/\\*\\$\\$\\(\\*/\\s*)?\"([^\"]+)\"");

  private static final Pattern REFUSAL =
      Pattern.compile("I18n\\.get\\(\\s*\"(You are not (?:allowed|authori[sz]ed)[^\"]*)\"\\s*\\)");

  private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");

  @Test
  @DisplayName("every gate action and every refusal has Bulgarian in its own module")
  void everyActionAndRefusalHasBulgarian() {
    Map<String, Integer> found = new LinkedHashMap<>();
    List<String> missing = new ArrayList<>();
    for (String module :
        List.of(
            "axelor-bg-localization",
            "nyxa-nap-submissions",
            "axelor-ai-ocr",
            "axelor-ai-accountant")) {
      Map<String, String> bg = bg(module);
      List<String> keys = keys(module);
      found.put(module, keys.size());
      for (String key : keys) {
        String text = bg.get(key);
        if (text == null || !CYRILLIC.matcher(text).find()) {
          missing.add(module + ": " + key);
        }
      }
    }
    // Discovery must not go blind: the gate actions are dozens in bg-localization and НАП.
    assertTrue(found.get("axelor-bg-localization") >= 45, "discovery: " + found);
    assertTrue(found.get("nyxa-nap-submissions") >= 12, "discovery: " + found);
    assertTrue(
        missing.isEmpty(),
        "No Bulgarian (key = the English text, message = Bulgarian) in the module's"
            + " messages_bg.csv for:\n  "
            + String.join("\n  ", missing));
  }

  private static List<String> keys(String module) {
    List<String> keys = new ArrayList<>();
    for (Path file : java(module)) {
      String source = read(file);
      Map<String, String> constants = new HashMap<>();
      Matcher c = CONSTANT.matcher(source);
      while (c.find()) {
        constants.put(c.group(1), c.group(2));
      }
      Matcher g = GATE_ARG.matcher(source);
      while (g.find()) {
        String key = g.group(1) != null ? g.group(1) : constants.get(g.group(2));
        if (key != null && !keys.contains(key)) {
          keys.add(key);
        }
      }
      Matcher r = REFUSAL.matcher(source);
      while (r.find()) {
        if (!keys.contains(r.group(1))) {
          keys.add(r.group(1));
        }
      }
    }
    return keys;
  }

  private static List<Path> java(String module) {
    try (Stream<Path> files = Files.walk(MODULES.resolve(module).resolve("src/main/java"))) {
      return files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Map<String, String> bg(String module) {
    Map<String, String> out = new HashMap<>();
    Path file = MODULES.resolve(module).resolve("src/main/resources/i18n/messages_bg.csv");
    List<String> lines;
    try {
      lines = Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    for (String line : lines.subList(1, lines.size())) {
      List<String> cells = csv(line);
      if (cells.size() >= 2) {
        out.putIfAbsent(cells.get(0), cells.get(1));
      }
    }
    return out;
  }

  private static String read(Path file) {
    try {
      return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** Един CSV ред с кавички и удвоени кавички; клетките тук не носят нов ред. */
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
