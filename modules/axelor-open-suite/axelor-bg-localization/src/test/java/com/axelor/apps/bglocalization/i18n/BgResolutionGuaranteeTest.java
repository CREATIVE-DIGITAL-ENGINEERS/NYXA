/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Guards the premise the root-app override layer relies on: it is loaded after every module.
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code ModuleResolver} orders modules depth-first by dependency, so a module that depends on
 * every other module is always last, and {@code I18nLoader} sorts {@code custom_*.csv} after {@code
 * messages_*.csv} inside a module. The root application module ({@code nyxa}) gets its dependencies
 * from the root {@code build.gradle}, which wires in every module that {@code settings.gradle}
 * discovers. If either wiring changes, the "last wins" guarantee behind {@code custom_bg.csv} is
 * gone, so this test pins it.
 *
 * <p>It also keeps modules that ship no {@code messages_bg.csv} (currently {@code
 * axelor-ai-accounting} and {@code axelor-ai-banking}) honest: any literal they translate must
 * already have a Bulgarian value somewhere.
 */
class BgResolutionGuaranteeTest {

  private static final Pattern I18N_GET =
      Pattern.compile("I18n\\.get\\(\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
  private static final Pattern TITLE_ATTR = Pattern.compile("\\btitle=\"([^\"]+)\"");

  private static String read(Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("the root application module depends on every module, so it is loaded last")
  void rootModuleIsWiredAfterEveryModule() throws Exception {
    final Path root = BgTranslationFiles.repositoryRoot();
    final String settings = read(root.resolve("settings.gradle"));
    final String build = read(root.resolve("build.gradle"));

    assertTrue(
        settings.contains("new File(it, \"build.gradle\").exists()")
            && settings.contains("gradle.ext.appModules = modules"),
        "settings.gradle no longer auto-discovers every open-suite module with a build.gradle");
    assertTrue(
        build.contains("gradle.appModules.each")
            && build.contains("implementation project(\":modules:$dir.name\")"),
        "root build.gradle no longer depends on every discovered module; custom_bg.csv would no"
            + " longer be guaranteed to load last");
    assertTrue(
        Files.isRegularFile(root.resolve("src/main/resources/i18n/custom_bg.csv"))
            && Files.isRegularFile(root.resolve("src/main/resources/i18n/messages_bg.csv")),
        "The root override files src/main/resources/i18n/{custom,messages}_bg.csv are missing");
  }

  @Test
  @DisplayName("modules without messages_bg.csv do not hide untranslated user-visible text")
  void modulesWithoutBgCsvAreCovered() throws Exception {
    final Path root = BgTranslationFiles.repositoryRoot();
    final Set<String> translated = new HashSet<>();
    for (Row row : BgTranslationFiles.load()) {
      if (!row.isEnglish()) {
        translated.add(row.key);
      }
    }

    final List<String> missing = new ArrayList<>();
    for (Path module : BgTranslationFiles.suiteModules(root)) {
      if (Files.isRegularFile(module.resolve("src/main/resources/i18n/messages_bg.csv"))) {
        continue;
      }
      final String name = module.getFileName().toString();
      final List<String> literals = new ArrayList<>();
      collect(module.resolve("src/main/java"), ".java", I18N_GET, literals);
      collect(module.resolve("src/main/resources/views"), ".xml", TITLE_ATTR, literals);
      collect(module.resolve("src/main/resources/domains"), ".xml", TITLE_ATTR, literals);
      for (String literal : literals) {
        if (literal.matches(".*\\p{IsCyrillic}.*") || !literal.matches(".*\\p{IsLatin}.*")) {
          continue;
        }
        if (!translated.contains(literal)) {
          missing.add(name + ": " + literal);
        }
      }
    }
    assertTrue(
        missing.isEmpty(),
        "These modules have no messages_bg.csv and use text that has no Bulgarian translation."
            + " Add a messages_bg.csv to the module (or a row to the root custom_bg.csv):\n  "
            + String.join("\n  ", missing));
  }

  private static void collect(Path dir, String suffix, Pattern pattern, List<String> out)
      throws IOException {
    if (!Files.isDirectory(dir)) {
      return;
    }
    final List<Path> files;
    try (Stream<Path> stream = Files.walk(dir)) {
      files =
          stream
              .filter(p -> Files.isRegularFile(p) && p.toString().endsWith(suffix))
              .collect(Collectors.toList());
    }
    for (Path file : files) {
      final Matcher matcher = pattern.matcher(read(file));
      while (matcher.find()) {
        out.add(matcher.group(1));
      }
    }
  }
}
