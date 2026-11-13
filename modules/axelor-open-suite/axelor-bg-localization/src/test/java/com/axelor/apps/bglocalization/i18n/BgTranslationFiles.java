/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Shared, file-system-only view of every Bulgarian translation row in the repository.
 */
package com.axelor.apps.bglocalization.i18n;

import com.axelor.common.StringUtils;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Reads {@code messages_bg.csv} / {@code custom_bg.csv} of every repository module and of the root
 * application module with the same dialect and the same skips as {@code I18nLoader#process} (blank
 * key and empty message rows are ignored).
 *
 * <p>Only the working tree is read — no classpath, no database, no Axelor runtime — so the result
 * is deterministic and independent of classpath order.
 */
final class BgTranslationFiles {

  /** The Bulgarian files {@code I18nLoader} picks up: {@code messages_bg} and {@code custom_bg}. */
  private static final Pattern BG_FILE = Pattern.compile("(?:messages|custom)_bg\\.csv");

  private BgTranslationFiles() {}

  /** One translation row. */
  static final class Row {
    final String module;
    final String file;
    final String key;
    final String message;
    final boolean root;

    Row(String module, String file, String key, String message, boolean root) {
      this.module = module;
      this.file = file;
      this.key = key;
      this.message = message;
      this.root = root;
    }

    /** The "translation" is the English key itself. */
    boolean isEnglish() {
      return key.equals(message);
    }

    String where() {
      return module + "/" + file;
    }
  }

  /** Walks up from the working directory to the repository root. */
  static Path repositoryRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("settings.gradle"))
          && Files.isDirectory(p.resolve("modules"))) {
        return p;
      }
    }
    throw new IllegalStateException("Repository root (settings.gradle + modules/) not found");
  }

  /** Module directories of the open suite that have a {@code build.gradle}. */
  static List<Path> suiteModules(Path root) throws IOException {
    try (Stream<Path> children = Files.list(root.resolve("modules/axelor-open-suite"))) {
      return children
          .filter(p -> Files.isRegularFile(p.resolve("build.gradle")))
          .sorted(Comparator.comparing(Path::toString))
          .collect(Collectors.toList());
    }
  }

  /** Every Bulgarian row, suite modules first, the root application module last. */
  static List<Row> load() throws IOException {
    final Path root = repositoryRoot();
    final List<Row> rows = new ArrayList<>();
    for (Path module : suiteModules(root)) {
      readModule(
          module.getFileName().toString(), module.resolve("src/main/resources"), false, rows);
    }
    readModule("nyxa", root.resolve("src/main/resources"), true, rows);
    return rows;
  }

  private static void readModule(String name, Path resources, boolean root, List<Row> rows)
      throws IOException {
    final Path i18n = resources.resolve("i18n");
    if (!Files.isDirectory(i18n)) {
      return;
    }
    final List<Path> files;
    try (Stream<Path> stream = Files.list(i18n)) {
      files =
          stream
              .filter(p -> BG_FILE.matcher(p.getFileName().toString()).matches())
              // custom_* is loaded after messages_*; same order here.
              .sorted(
                  Comparator.comparing((Path p) -> p.getFileName().toString().startsWith("custom_"))
                      .thenComparing(Path::toString))
              .collect(Collectors.toList());
    }
    for (Path file : files) {
      try (InputStream in = Files.newInputStream(file);
          CSVParser parser =
              CSVFile.DEFAULT.withFirstRecordAsHeader().parse(in, StandardCharsets.UTF_8)) {
        for (CSVRecord record : parser) {
          if (CSVFile.isEmpty(record)) {
            continue;
          }
          final Map<String, String> map = record.toMap();
          final String key = map.get("key");
          final String message = map.get("message");
          if (StringUtils.isBlank(key) || StringUtils.isEmpty(message)) {
            continue;
          }
          rows.add(new Row(name, file.getFileName().toString(), key, message, root));
        }
      }
    }
  }

  /** key -> rows, in load order. */
  static Map<String, List<Row>> byKey(List<Row> rows) {
    final Map<String, List<Row>> map = new LinkedHashMap<>();
    for (Row row : rows) {
      map.computeIfAbsent(row.key, k -> new ArrayList<>()).add(row);
    }
    return map;
  }

  /** Keys for which the root application module supplies a non-English value. */
  static Set<String> settledByRoot(List<Row> rows) {
    final Set<String> keys = new LinkedHashSet<>();
    for (Row row : rows) {
      if (row.root && !row.isEnglish()) {
        keys.add(row.key);
      }
    }
    return keys;
  }
}
