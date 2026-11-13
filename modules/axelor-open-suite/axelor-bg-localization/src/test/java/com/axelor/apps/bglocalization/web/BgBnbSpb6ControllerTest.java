package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * „БНБ — СПБ-6А/6Б“ › „Свали Excel“: при избрани година и период контролерът не бива да казва
 * „Изберете година и период.“ Той не може да чете {@code context.get("$year")}: front-ът никога не
 * праща ключ с „$“: {@code processContextValues} (axelor-front views/form/builder/utils.ts) добавя
 * {@code year} до {@code $year}, а JSON сериализаторът (services/client/client.ts) изхвърля всеки
 * ключ с „$“. Контекстът тук е точно това, което стига до сървъра.
 */
class BgBnbSpb6ControllerTest {

  /** Записът на формата, минал през processContextValues + JSON replacer-а на front-а. */
  private static Map<String, Object> asSentByTheFront(Map<String, Object> record) {
    Map<String, Object> processed = new HashMap<>(record);
    for (Map.Entry<String, Object> e : record.entrySet()) {
      if (e.getKey().startsWith("$")) {
        processed.putIfAbsent(e.getKey().replaceFirst("^\\$+", ""), e.getValue());
      }
    }
    processed.keySet().removeIf(k -> k.startsWith("$"));
    return processed;
  }

  @Test
  @DisplayName("Година и период от формата стигат до контролера (без „$“ в ключа)")
  void yearAndQuarterAsSentByTheFront() {
    Map<String, Object> record = new HashMap<>();
    record.put("$year", 2026);
    record.put("$quarter", 3);
    record.put("_model", "com.axelor.utils.db.Wizard");
    Map<String, Object> context = asSentByTheFront(record);

    assertEquals(Integer.valueOf(2026), BgBnbSpb6Controller.wizardInt(context, "year"));
    assertEquals(Integer.valueOf(3), BgBnbSpb6Controller.wizardInt(context, "quarter"));
  }

  @Test
  @DisplayName("„Цялата година“ е 0, не липса; празно поле е липса")
  void wholeYearIsZeroAndBlankIsMissing() {
    Map<String, Object> context = new HashMap<>();
    context.put("quarter", 0);
    context.put("year", "");
    assertEquals(Integer.valueOf(0), BgBnbSpb6Controller.wizardInt(context, "quarter"));
    assertNull(BgBnbSpb6Controller.wizardInt(context, "year"));
    assertNull(BgBnbSpb6Controller.wizardInt(new HashMap<>(), "year"));
  }

  /**
   * Същият капан другаде в модула: контролер, който чете преходно поле по името му с „$“, получава
   * винаги null от формата.
   */
  @Test
  @DisplayName("Никой контролер на модула не чете ключ с „$“ от контекста")
  void noControllerReadsADollarKeyFromTheContext() throws IOException {
    Path root = Paths.get("src/main/java");
    Pattern dollarRead = Pattern.compile("getContext\\(\\)\\s*\\.get\\(\\s*\"\\$");
    List<String> offenders = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path file :
          (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator) {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
          if (dollarRead.matcher(lines.get(i)).find()) {
            offenders.add(root.relativize(file) + ":" + (i + 1));
          }
        }
      }
    }
    assertTrue(offenders.isEmpty(), "четат „$…“ от контекста: " + offenders);
  }
}
