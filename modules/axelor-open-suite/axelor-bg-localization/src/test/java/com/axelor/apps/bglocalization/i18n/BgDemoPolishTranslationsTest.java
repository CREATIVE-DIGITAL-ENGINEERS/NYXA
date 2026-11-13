/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Pins the demo-data translations of AOS keys.
 */
package com.axelor.apps.bglocalization.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.i18n.BgTranslationFiles.Row;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРЕВОДИТЕ НА КЛЮЧОВЕ НА AOS, КОИТО СЕ ВИЖДАТ В ДЕМО ДАННИТЕ.
 *
 * <p>Ключовете са на AOS, затова преводът е в {@code custom_bg.csv} на приложния модул {@code nyxa}
 * — последен по графа (виж {@link BgUiMenuTranslationsTest}). {@link BgTranslationFiles#load()}
 * чете редовете в реда на зареждане, тоест последният ред на ключа е текстът на екрана.
 */
class BgDemoPolishTranslationsTest {

  private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

  static {
    EXPECTED.put("Reverse", "Сторно");
    EXPECTED.put("See Move Lines", "Редове на движението");
    EXPECTED.put("Delete", "Изтриване");
    EXPECTED.put("Generate", "Генериране");
    EXPECTED.put("Cancel", "Отмяна");
    EXPECTED.put("FixedAsset.Location", "Местонахождение");
    EXPECTED.put("Inventories", "Инвентаризации");
    EXPECTED.put("Create inventory", "Създаване на инвентаризация");
    EXPECTED.put("Transferred", "Изведен");
    EXPECTED.put("Invoice.taxCode", "Данък");
  }

  @Test
  @DisplayName("ключовете на AOS от демо данните връщат новия текст от custom_bg.csv")
  void theAppShowsTheNewText() throws Exception {
    Map<String, List<Row>> byKey = BgTranslationFiles.byKey(BgTranslationFiles.load());
    List<String> wrong = new ArrayList<>();
    for (Map.Entry<String, String> e : EXPECTED.entrySet()) {
      List<Row> rows = byKey.get(e.getKey());
      if (rows == null) {
        wrong.add(e.getKey() + " → няма нито един ред");
        continue;
      }
      Row last = rows.get(rows.size() - 1);
      if (!e.getValue().equals(last.message)) {
        wrong.add(
            e.getKey()
                + " → „"
                + last.message
                + "“ от "
                + last.where()
                + ", очаквано „"
                + e.getValue()
                + "“");
      } else if (!last.root) {
        wrong.add(e.getKey() + " → печели " + last.where() + ", а не custom_bg.csv на nyxa");
      }
    }
    if (!wrong.isEmpty()) {
      fail("преводите не са новите (" + wrong.size() + "):\n  " + String.join("\n  ", wrong));
    }
  }

  /**
   * „Сторно“ е глагол за бутона на статията; „Изведен“ е статус на актив; „Инвентаризации“ — менюто
   * на инвентаризациите. Ако ключът почне да се ползва другаде, новият текст може да не му подхожда
   * — затова употребите са заковани (изгледи, Java, BIRT; без i18n файловете).
   */
  @Test
  @DisplayName("Reverse, Transferred, Inventories, Create inventory — употребите са заковани")
  void keysKeepTheirOnlyMeaning() throws Exception {
    assertUses(
        "Reverse",
        List.of(
            "axelor-account/src/main/resources/views/Move.xml: <button name=\"generateReverseBtn\""
                + " title=\"Reverse\" icon=\"fa-exchange\"",
            "axelor-account/src/main/resources/views/Move.xml: <button name=\"generateReverseBtn\""
                + " title=\"Reverse\" icon=\"fa-exchange\"",
            "axelor-account/src/main/resources/views/Move.xml: <button name=\"reverseBtn\""
                + " title=\"Reverse\" icon=\"fa-exchange\"",
            "axelor-account/src/main/resources/views/Move.xml: <button name=\"reverseBtn\""
                + " title=\"Reverse\" icon=\"fa-exchange\""));
    assertUses(
        "Transferred",
        List.of(
            "axelor-account/src/main/resources/views/Selects.xml: <option value=\"4\""
                + " color=\"purple\">Transferred</option>"));
    assertUses(
        "Inventories",
        List.of(
            "axelor-stock/src/main/resources/views/Menu.xml: <action-view"
                + " name=\"stock.root.inventories.draft\" title=\"Inventories\"",
            "axelor-stock/src/main/resources/views/Menu.xml: <menuitem"
                + " name=\"stock-root-inventories\" parent=\"stock-root\" title=\"Inventories\""));
    assertUses(
        "Create inventory",
        List.of(
            "axelor-stock/src/main/resources/views/StockLocation.xml: <action-view"
                + " name=\"action-location-view-create-inventory\" title=\"Create inventory\"",
            "axelor-stock/src/main/resources/views/StockLocation.xml: <button"
                + " name=\"createInventoryBtn\" title=\"Create inventory\""));
  }

  /** Собствените заглавия на НОИ: „Експорт към НОИ“, не „Износ“ — и английският им ключ. */
  @Test
  @DisplayName("„Износ/Износи към НОИ“ → „Експорт към НОИ“ в менюто, списъка и формата")
  void noiExportIsCalledExport() throws Exception {
    Path views =
        BgTranslationFiles.repositoryRoot()
            .resolve("modules/axelor-open-suite/axelor-bg-localization/src/main/resources/views");
    List<String> old = new ArrayList<>();
    int titles = 0;
    try (Stream<Path> files = Files.list(views)) {
      for (Path file :
          files.filter(p -> p.toString().endsWith(".xml")).collect(Collectors.toList())) {
        for (String line : read(file)) {
          if (line.matches(".*title=\"Износ(и|ът)?( към НОИ)?[\" (].*")) {
            old.add(file.getFileName() + ": " + line.trim());
          }
          if (line.contains("title=\"Експорт към НОИ (")) {
            titles++;
          }
        }
      }
    }
    assertTrue(old.isEmpty(), "заглавия с „Износ“: " + old);
    assertEquals(5, titles, "менюто, действието, списъкът, формата и панелът на удостоверението");

    Path en =
        BgTranslationFiles.repositoryRoot()
            .resolve(
                "modules/axelor-open-suite/axelor-bg-localization/src/main/resources/i18n/messages_en.csv");
    String text = String.join("\n", read(en));
    for (String key :
        List.of("Експорт към НОИ (Прил. № 9)", "Експорт към НОИ (Приложение № 9)", "Експортът")) {
      assertTrue(text.contains("\"" + key + "\",\""), "няма английски ред за „" + key + "“");
    }
  }

  private static void assertUses(String key, List<String> expected) throws IOException {
    Path suite = BgTranslationFiles.repositoryRoot().resolve("modules/axelor-open-suite");
    Pattern use =
        Pattern.compile(
            "title=\""
                + Pattern.quote(key)
                + "\"|>"
                + Pattern.quote(key)
                + "<|\""
                + Pattern.quote(key)
                + "\"\\s*\\)|'"
                + Pattern.quote(key)
                + "'");
    List<String> uses = new ArrayList<>();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(suite)) {
      files =
          walk.filter(p -> p.toString().contains("/src/main/"))
              .filter(p -> !p.toString().contains("/i18n/"))
              .filter(
                  p ->
                      p.toString().endsWith(".xml")
                          || p.toString().endsWith(".java")
                          || p.toString().endsWith(".rptdesign"))
              .collect(Collectors.toList());
    }
    for (Path file : files) {
      for (String line : read(file)) {
        if (use.matcher(line).find()) {
          uses.add(suite.relativize(file) + ": " + line.trim());
        }
      }
    }
    assertEquals(
        expected,
        uses.stream().sorted().collect(Collectors.toList()),
        "„" + key + "“ има нова употреба — проверете дали новият текст ѝ подхожда");
  }

  private static List<String> read(Path file) throws IOException {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (MalformedInputException e) {
      return List.of();
    }
  }
}
