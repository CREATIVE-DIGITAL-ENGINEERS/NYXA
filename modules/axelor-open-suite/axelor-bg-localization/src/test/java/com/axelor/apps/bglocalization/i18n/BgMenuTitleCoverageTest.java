/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Every menu title a user can see must read as Bulgarian.
 */
package com.axelor.apps.bglocalization.i18n;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.i18n.BgTranslationFiles.Row;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Parses the {@code <menuitem title="...">} of every suite module and of {@code axelor-core}. A
 * title passes when it already contains Cyrillic, has no letters, is an established acronym or
 * brand, or has a non-English Bulgarian value in some {@code messages_bg.csv}/{@code
 * custom_bg.csv}.
 *
 * <p>Menus of the external add-ons ({@code axelor-studio}, {@code axelor-message}) have no source
 * in this repository and are not covered.
 */
class BgMenuTitleCoverageTest {

  /** Established acronyms and brands that stay unchanged in Bulgarian. */
  private static final Set<String> KEEP_AS_IS =
      Set.of(
          "AI Accountant",
          "CRM",
          "DPAEs",
          "GDPR",
          "HR",
          "LCR",
          "MPS",
          "MRP",
          "Nyxa",
          "PFP",
          "S&OP",
          "SLA",
          "S&OP/MPS");

  private static final Pattern MENU_ITEM =
      Pattern.compile("<menuitem\\b[^>]*?\\btitle=\"([^\"]*)\"", Pattern.DOTALL);

  private static String unescape(String xml) {
    return xml.replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'");
  }

  @Test
  @DisplayName("every menu title is Bulgarian, translated, or an allowed acronym")
  void menuTitlesAreCovered() throws Exception {
    final Path root = BgTranslationFiles.repositoryRoot();
    final Set<String> translated = new HashSet<>();
    for (Row row : BgTranslationFiles.load()) {
      if (!row.isEnglish()) {
        translated.add(row.key);
      }
    }

    final List<Path> viewDirs = new ArrayList<>();
    viewDirs.add(root.resolve("modules/axelor-open-platform/axelor-core/src/main/resources/views"));
    for (Path module : BgTranslationFiles.suiteModules(root)) {
      viewDirs.add(module.resolve("src/main/resources/views"));
    }

    final Set<String> missing = new TreeSet<>();
    for (Path dir : viewDirs) {
      if (!Files.isDirectory(dir)) {
        continue;
      }
      final List<Path> files;
      try (Stream<Path> stream = Files.list(dir)) {
        files = stream.filter(p -> p.toString().endsWith(".xml")).collect(Collectors.toList());
      }
      for (Path file : files) {
        final Matcher matcher = MENU_ITEM.matcher(read(file));
        while (matcher.find()) {
          final String title = unescape(matcher.group(1));
          final boolean cyrillic = title.matches("(?s).*\\p{IsCyrillic}.*");
          final boolean letters = title.matches("(?s).*\\p{IsLatin}.*");
          if (cyrillic || !letters || KEEP_AS_IS.contains(title) || translated.contains(title)) {
            continue;
          }
          missing.add(title + "   (" + root.relativize(file) + ")");
        }
      }
    }
    assertTrue(
        missing.isEmpty(),
        "Menu titles without a Bulgarian translation. Add them to the root messages_bg.csv /"
            + " custom_bg.csv, or to KEEP_AS_IS if they are an established acronym:\n  "
            + String.join("\n  ", missing));
  }

  private static String read(Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }
}
