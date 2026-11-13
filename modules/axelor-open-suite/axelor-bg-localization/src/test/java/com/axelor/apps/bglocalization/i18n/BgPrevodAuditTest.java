/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Quality audit of the Bulgarian translation corpus.
 */
package com.axelor.apps.bglocalization.i18n;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Audits the Bulgarian translation corpus for the defects that a machine-translated ERP leaves
 * behind, and holds the result to a ratchet.
 *
 * <p><b>Why this is a separate class from {@link BgPrevodConflictTest}.</b> That one answers a
 * mechanical question — will the value we write survive module loading — and needs the module
 * dependency graph. This one answers a linguistic question — is the value that survives correct
 * Bulgarian — and needs a glossary. Same corpus, unrelated machinery; merging them would make a
 * failure in either read as a failure of both.
 *
 * <p><b>The corpus is read from the repository tree, not the classpath.</b> The classpath carries
 * only our dependency closure, and the worst offenders (axelor-stock, axelor-production,
 * axelor-crm) are outside it. {@link #discoveryReachesTheWholeCorpus()} guards against that
 * blindness returning, the same reason {@code discoveryCanSeeBeyondOurDependencies} exists next
 * door.
 *
 * <p><b>What is asserted and what is only reported.</b> The stock {@code messages_bg.csv} files are
 * upstream artefacts with thousands of defects; asserting on them would produce a permanently red
 * build that says nothing. So:
 *
 * <ul>
 *   <li>Every check runs over every file and the full result is printed on every run.
 *   <li>A {@code (module, check)} pair listed in {@code baseline-bg.csv} is <b>asserted</b>: the
 *       count may not grow, and may not silently shrink either — a stale baseline is lowered by
 *       hand so the number always says what is actually there.
 *   <li>A pair not listed is reported only. Cleaning a module ends with adding its rows to the
 *       baseline; from that point the module cannot regress.
 * </ul>
 *
 * <p>The two files we own — the {@code axelor-bg-localization} one and the application module's
 * {@code custom_bg.csv} — are seeded at zero, so anything we write is held to the glossary from the
 * first commit.
 *
 * <p>No Guice, no database, no Axelor runtime: this reads CSV off the repository tree and the test
 * classpath.
 */
class BgPrevodAuditTest {

  /** Label used for the application module's {@code custom_bg.csv}; matches the baseline file. */
  private static final String APP_MODULE = "nyxa-app";

  private static final String GLOSSARY = "i18n-audit/glossary-bg.csv";
  private static final String FORBIDDEN = "i18n-audit/forbidden-bg.csv";
  private static final String BASELINE = "i18n-audit/baseline-bg.csv";

  /** Everything {@code I18n} substitutes at runtime; a translated one of these is a dead screen. */
  private static final Pattern PLACEHOLDER =
      Pattern.compile("\\$\\{[^}]*\\}|\\{\\d+\\}|%[sdfn]|%\\d+\\$[sdf]");

  private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");
  private static final Pattern LATIN = Pattern.compile("[A-Za-z]");
  private static final Pattern FRENCH =
      Pattern.compile(
          "[\\u00E0\\u00E2\\u00E7\\u00E9\\u00E8\\u00EA\\u00EB\\u00EE"
              + "\\u00EF\\u00F4\\u00FB\\u00F9\\u0153]");

  /** A dotted identifier such as {@code Move.canceled} — a value the code reads, not a label. */
  private static final Pattern PROGRAMMATIC =
      Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$");

  // ------------------------------------------------------------------ checks

  private enum Check {
    /** Blank message: the key falls through to English at runtime. */
    EMPTY_MESSAGE,
    /** Message is character-for-character the English key. */
    MESSAGE_EQUALS_KEY,
    /** Message differs from the key but carries no Cyrillic at all. */
    UNTRANSLATED_LATIN,
    /** French accented characters left in a Bulgarian message. */
    FRENCH_RESIDUE,
    /**
     * The placeholders in the message do not match the ones in the key. This is the only check here
     * that is a runtime defect rather than a wording defect: {@code ${name}} rendered as {@code
     * ${име}} is evaluated against the record and yields nothing.
     */
    PLACEHOLDER_MISMATCH,
    /** The same key appears twice in one file with two different values; the last one wins. */
    DUPLICATE_KEY_CONFLICT,
    /** A rendering listed in {@code forbidden-bg.csv}. */
    FORBIDDEN_TERM,
    /** An enforced glossary term whose Bulgarian rendering is absent from the message. */
    GLOSSARY_DEVIATION,
    /**
     * A dotted key carrying a translated value. Never asserted: usually it must be left alone, but
     * {@code fiscalYear.code} is the counter-example: there the raw key reaches accounting data, so
     * translating it is mandatory. Reported so the decision is made by a human each time.
     */
    PROGRAMMATIC_KEY_TRANSLATED
  }

  private static final Set<Check> REPORT_ONLY = Set.of(Check.PROGRAMMATIC_KEY_TRANSLATED);

  /** Rows printed per (module, check) pair that is not baselined. */
  private static final int DETAIL_SAMPLE = 10;

  // ------------------------------------------------------------------ model

  private static final class Entry {
    final String module;
    final String origin;
    final String key;
    final String message;

    Entry(String module, String origin, String key, String message) {
      this.module = module;
      this.origin = origin;
      this.key = key;
      this.message = message;
    }
  }

  private static final class Finding {
    final Check check;
    final Entry entry;
    final String detail;

    Finding(Check check, Entry entry, String detail) {
      this.check = check;
      this.entry = entry;
      this.detail = detail;
    }

    String describe() {
      return String.format(
          "  %-26s %s%n    key     : %s%n    message : %s%n    %s%n    %s",
          check, entry.module, entry.key, entry.message, detail, entry.origin);
    }
  }

  private static final class Term {
    final String en;
    final String bg;
    final Pattern enInKey;

    /**
     * Keys on which this term does not apply, because the same English word means something else
     * there. {@code Move} is the reason the column exists: in {@code axelor-account} it is the
     * journal entry, in {@code axelor-stock} it is a physical movement of goods, and enforcing one
     * rendering on the other would be a worse error than the one being fixed.
     */
    final Pattern excludedKeys;

    final Pattern bgInMessage;
    final boolean enforce;
    final String note;

    Term(
        String en,
        String bg,
        Pattern enInKey,
        Pattern excludedKeys,
        Pattern bgInMessage,
        boolean enforce,
        String note) {
      this.en = en;
      this.bg = bg;
      this.enInKey = enInKey;
      this.excludedKeys = excludedKeys;
      this.bgInMessage = bgInMessage;
      this.enforce = enforce;
      this.note = note;
    }
  }

  private static final class Rule {
    final String id;
    final Pattern bg;
    final Pattern keyScope;
    final String severity;
    final String why;

    Rule(String id, Pattern bg, Pattern keyScope, String severity, String why) {
      this.id = id;
      this.bg = bg;
      this.keyScope = keyScope;
      this.severity = severity;
      this.why = why;
    }
  }

  // ------------------------------------------------------------------ tests

  /**
   * The audit is only as good as its data files. A regex that does not compile, a duplicate term or
   * a missing column would quietly shrink the checks to nothing, and every other test here would
   * still be green.
   */
  @Test
  @DisplayName("the glossary and the forbidden list are well formed")
  void dataFilesAreWellFormed() throws Exception {
    final List<Term> glossary = loadGlossary();
    final List<Rule> forbidden = loadForbidden();

    assertFalse(glossary.isEmpty(), "Glossary " + GLOSSARY + " is empty or was not found.");
    assertFalse(forbidden.isEmpty(), "Forbidden list " + FORBIDDEN + " is empty or was not found.");

    final List<String> duplicateTerms =
        glossary.stream()
            .collect(Collectors.groupingBy(t -> t.en.toLowerCase(), Collectors.counting()))
            .entrySet()
            .stream()
            .filter(e -> e.getValue() > 1)
            .map(Map.Entry::getKey)
            .sorted()
            .collect(Collectors.toList());
    assertTrue(
        duplicateTerms.isEmpty(),
        "A term appears twice in " + GLOSSARY + ", so one row is dead: " + duplicateTerms);

    final List<String> duplicateRules =
        forbidden.stream()
            .collect(Collectors.groupingBy(r -> r.id, Collectors.counting()))
            .entrySet()
            .stream()
            .filter(e -> e.getValue() > 1)
            .map(Map.Entry::getKey)
            .sorted()
            .collect(Collectors.toList());
    assertTrue(
        duplicateRules.isEmpty(), "Duplicate rule id(s) in " + FORBIDDEN + ": " + duplicateRules);

    assertTrue(
        glossary.stream().anyMatch(t -> t.enforce),
        "No glossary term is enforced, so GLOSSARY_DEVIATION can never fire; the check is dead.");
  }

  /**
   * Discovery must reach the whole corpus, not just our dependency closure. A scan that silently
   * narrowed to the classpath would report a clean audit precisely because it cannot see the files
   * with the defects.
   */
  @Test
  @DisplayName("discovery reaches the whole Bulgarian corpus")
  void discoveryReachesTheWholeCorpus() throws Exception {
    final List<Entry> corpus = loadCorpus();
    final Set<String> modules =
        corpus.stream().map(e -> e.module).collect(Collectors.toCollection(LinkedHashSet::new));

    assertTrue(
        modules.size() >= 25,
        "Only "
            + modules.size()
            + " module(s) carry a Bulgarian translation file. The repository "
            + "has around thirty; discovery has broken and every count below is meaningless. "
            + "Found: "
            + modules);
    assertTrue(
        modules.contains("axelor-account"),
        "axelor-account is missing from the corpus. It is the largest and most defect-dense file "
            + "in the repository; an audit that cannot see it is no audit. Found: "
            + modules);
    assertTrue(
        modules.contains(APP_MODULE),
        "The application module's custom_bg.csv was not discovered, so the file that decides every "
            + "contested key is unaudited. Found: "
            + modules);
  }

  /** Prints the whole audit, then holds the baselined pairs to the ratchet. */
  @Test
  @DisplayName("audit findings do not exceed the recorded baseline")
  void findingsDoNotExceedBaseline() throws Exception {
    final List<Finding> findings = audit();
    final Map<String, Integer> baseline = loadBaseline();
    final Map<String, Integer> actual = countByModuleAndCheck(findings);

    report(findings, baseline, actual);

    final List<String> regressions = new ArrayList<>();
    for (Map.Entry<String, Integer> limit : baseline.entrySet()) {
      final int now = actual.getOrDefault(limit.getKey(), 0);
      if (now > limit.getValue()) {
        regressions.add(
            String.format(
                "  %-46s baseline %d, now %d  (+%d)",
                limit.getKey(), limit.getValue(), now, now - limit.getValue()));
      }
    }

    assertTrue(
        regressions.isEmpty(),
        "Bulgarian translation quality regressed against "
            + BASELINE
            + ":\n"
            + String.join("\n", regressions)
            + "\n\nThe offending rows are listed in the report above, under the matching check. "
            + "Fix the translation rather than raising the baseline — the baseline is a ratchet, "
            + "and raising it is how a cleanup silently unwinds.\n");
  }

  /**
   * A baseline above the real count is worse than no baseline: it reads as coverage while leaving
   * room to regress back into it. Lowering it is one line, and part of finishing a cleanup.
   */
  @Test
  @DisplayName("no baseline entry is above the real count")
  void baselineIsNotStale() throws Exception {
    final Map<String, Integer> baseline = loadBaseline();
    final Map<String, Integer> actual = countByModuleAndCheck(audit());

    final List<String> stale = new ArrayList<>();
    for (Map.Entry<String, Integer> limit : baseline.entrySet()) {
      final int now = actual.getOrDefault(limit.getKey(), 0);
      if (now < limit.getValue()) {
        stale.add(
            String.format("  %-46s baseline %d, now %d", limit.getKey(), limit.getValue(), now));
      }
    }

    assertTrue(
        stale.isEmpty(),
        "Baseline entries are above the real count. Lower them in "
            + BASELINE
            + " so the numbers keep saying what is actually there:\n"
            + String.join("\n", stale)
            + "\n");
  }

  // ------------------------------------------------------------------ navigation

  /** A {@code <menuitem ...>} opening tag, capturing its {@code title}, across line breaks. */
  private static final Pattern MENUITEM_TITLE =
      Pattern.compile("<menuitem\\b[^>]*?\\btitle=\"([^\"]*)\"");

  private static final Pattern XML_COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);

  /**
   * Every menu or submenu whose label is authored in Bulgarian must carry an English translation,
   * or the English UI shows Cyrillic in the navigation tree. The stock modules label their menus in
   * English, so this only ever fires on the custom (BG) modules; it is the guard for the exact
   * regression where a {@code messages_en.csv} covers the menu roots but misses the submenu entries
   * defined inside feature view files.
   *
   * <p>A Bulgarian menu title is one carrying Cyrillic. "Covered" means some module declares an
   * {@code en} translation for that exact key whose value is non-blank and itself carries no
   * Cyrillic — i.e. the English UI will not fall back to the Bulgarian key.
   */
  @Test
  @DisplayName("every Bulgarian menu/submenu label has an English translation")
  void everyBulgarianMenuHasEnglish() throws Exception {
    final Map<String, String> bgMenus = discoverBulgarianMenuTitles();
    assertFalse(
        bgMenus.isEmpty(),
        "No Bulgarian menu titles were discovered at all. The custom modules define them, so "
            + "discovery has broken and this test can only ever pass. Check the modules/*/* layout "
            + "and MENUITEM_TITLE.");

    final Map<String, String> english = loadEnglish();

    final List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> menu : bgMenus.entrySet()) {
      final String title = menu.getKey();
      final String en = english.get(title);
      if (StringUtils.isBlank(en)) {
        missing.add(
            String.format("  \"%s\"%n      no en translation   (%s)", title, menu.getValue()));
      } else if (CYRILLIC.matcher(en).find()) {
        missing.add(
            String.format(
                "  \"%s\"%n      en still Cyrillic: \"%s\"   (%s)", title, en, menu.getValue()));
      }
    }

    assertTrue(
        missing.isEmpty(),
        missing.size()
            + " Bulgarian menu/submenu label(s) have no usable English translation, so the English "
            + "UI shows Cyrillic in the navigation tree. Add an entry to the owning module's "
            + "messages_en.csv (key = the Bulgarian title, message = natural English):\n"
            + String.join("\n", missing)
            + "\n");
  }

  /** Bulgarian menu/submenu titles across the repository: title -> first origin file. */
  private static Map<String, String> discoverBulgarianMenuTitles() throws IOException {
    final Path root = repositoryRoot();
    assertFalse(root == null, "Repository root not found.");
    final Map<String, String> titles = new TreeMap<>();

    for (Path moduleDir : repositoryModuleDirsForMenus(root)) {
      final Path resources = moduleDir.resolve("src/main/resources");
      if (!Files.isDirectory(resources)) continue;
      try (Stream<Path> files = Files.walk(resources)) {
        for (Path xml :
            files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".xml"))
                .collect(Collectors.toList())) {
          final String text =
              XML_COMMENT
                  .matcher(new String(Files.readAllBytes(xml), StandardCharsets.UTF_8))
                  .replaceAll("");
          final Matcher m = MENUITEM_TITLE.matcher(text);
          while (m.find()) {
            final String title = m.group(1);
            if (CYRILLIC.matcher(title).find()) {
              titles.putIfAbsent(title, root.relativize(xml).toString());
            }
          }
        }
      }
    }
    return titles;
  }

  /** Module directories two levels below {@code modules/}. */
  private static List<Path> repositoryModuleDirsForMenus(Path root) throws IOException {
    final List<Path> dirs = new ArrayList<>();
    try (Stream<Path> groups = Files.list(root.resolve("modules"))) {
      for (Path group : groups.filter(Files::isDirectory).collect(Collectors.toList())) {
        try (Stream<Path> children = Files.list(group)) {
          children.filter(Files::isDirectory).forEach(dirs::add);
        }
      }
    }
    return dirs;
  }

  /** All English translations in the tree: key -> a non-blank message, if any module gives one. */
  private static Map<String, String> loadEnglish() throws IOException {
    final Path root = repositoryRoot();
    final List<Path> files = new ArrayList<>();
    try (Stream<Path> groups = Files.list(root.resolve("modules"))) {
      for (Path group : groups.filter(Files::isDirectory).collect(Collectors.toList())) {
        try (Stream<Path> children = Files.list(group)) {
          children
              .filter(Files::isDirectory)
              .forEach(
                  d -> {
                    files.add(d.resolve("src/main/resources/i18n/messages_en.csv"));
                    files.add(d.resolve("src/main/resources/i18n/custom_en.csv"));
                  });
        }
      }
    }
    files.add(root.resolve("src/main/resources/i18n/messages_en.csv"));
    files.add(root.resolve("src/main/resources/i18n/custom_en.csv"));

    final Map<String, String> english = new LinkedHashMap<>();
    for (Path file : files) {
      if (!Files.isRegularFile(file)) continue;
      try (InputStream in = Files.newInputStream(file)) {
        for (CSVRecord record : records(in)) {
          final Map<String, String> row = record.toMap();
          final String key = row.get("key");
          final String message = row.getOrDefault("message", "");
          if (StringUtils.isBlank(key) || StringUtils.isBlank(message)) continue;
          english.putIfAbsent(key, message);
        }
      }
    }
    return english;
  }

  /**
   * The custom modules that own Nyxa/BG navigation. The reverse guard is scoped to these on
   * purpose: the stock modules label their menus in English and we deliberately do not translate
   * those this way, so scanning the whole tree would demand a Bulgarian rendering for every
   * upstream menu.
   */
  private static final Set<String> NYXA_CUSTOM_MODULES =
      Set.of("axelor-bg-localization", "axelor-ai-ocr", "nyxa-nap-submissions");

  /**
   * A whole {@code <menuitem ...>} opening tag, so {@code hidden} and {@code title} can be read.
   */
  private static final Pattern MENUITEM_TAG = Pattern.compile("<menuitem\\b[^>]*>");

  private static final Pattern TITLE_ATTR = Pattern.compile("\\btitle=\"([^\"]*)\"");

  /**
   * The mirror of {@link #everyBulgarianMenuHasEnglish()}: a custom menu or submenu authored in
   * English leaks that English into the Bulgarian UI, because the literal title is the translation
   * key and Bulgarian has nothing to override it with. Every <i>visible</i> English label a Nyxa
   * custom module defines must therefore carry a Bulgarian translation.
   *
   * <p>"Visible" excludes {@code hidden="true"} overrides: those re-declare a stock menu only to
   * hide it, and the XSD forces them to keep the foreign title even though the node is never shown
   * (see {@code Menu-hr-hide.xml} / {@code Menu-account-hide.xml}). "English" means the title
   * carries a Latin letter and no Cyrillic — a mixed label like {@code "SAF-T номенклатури"}
   * already reads as Bulgarian. "Covered" means some module declares a non-blank {@code bg} value
   * for that exact key which itself carries Cyrillic, so the Bulgarian UI will not echo the English
   * key. Generic labels such as {@code Settings} or {@code Files} are covered by the stock
   * Bulgarian files and so pass without a custom entry.
   */
  @Test
  @DisplayName("every visible English custom menu/submenu label has a Bulgarian translation")
  void everyCustomEnglishMenuHasBulgarian() throws Exception {
    final Map<String, String> enMenus = discoverEnglishCustomMenuTitles();
    assertFalse(
        enMenus.isEmpty(),
        "No English custom menu titles were discovered at all. The custom modules define them, so "
            + "discovery has broken and this test can only ever pass. Check NYXA_CUSTOM_MODULES, "
            + "the modules/*/* layout and MENUITEM_TAG.");

    final Map<String, String> bulgarian = loadBulgarianAll();

    final List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> menu : enMenus.entrySet()) {
      final String title = menu.getKey();
      final String bg = bulgarian.get(title);
      if (StringUtils.isBlank(bg)) {
        missing.add(
            String.format("  \"%s\"%n      no bg translation   (%s)", title, menu.getValue()));
      } else if (!CYRILLIC.matcher(bg).find()) {
        missing.add(
            String.format(
                "  \"%s\"%n      bg still Latin: \"%s\"   (%s)", title, bg, menu.getValue()));
      }
    }

    assertTrue(
        missing.isEmpty(),
        missing.size()
            + " visible English custom menu/submenu label(s) have no usable Bulgarian translation, "
            + "so the Bulgarian UI shows English in the navigation tree. Add an entry to the owning "
            + "module's messages_bg.csv (key = the English title, message = natural Bulgarian):\n"
            + String.join("\n", missing)
            + "\n");
  }

  /** Visible English menu/submenu titles owned by the Nyxa custom modules: title -> origin file. */
  private static Map<String, String> discoverEnglishCustomMenuTitles() throws IOException {
    final Path root = repositoryRoot();
    assertFalse(root == null, "Repository root not found.");
    final Map<String, String> titles = new TreeMap<>();

    for (Path moduleDir : repositoryModuleDirsForMenus(root)) {
      if (!NYXA_CUSTOM_MODULES.contains(moduleDir.getFileName().toString())) continue;
      final Path resources = moduleDir.resolve("src/main/resources");
      if (!Files.isDirectory(resources)) continue;
      try (Stream<Path> files = Files.walk(resources)) {
        for (Path xml :
            files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".xml"))
                .collect(Collectors.toList())) {
          final String text =
              XML_COMMENT
                  .matcher(new String(Files.readAllBytes(xml), StandardCharsets.UTF_8))
                  .replaceAll("");
          final Matcher tag = MENUITEM_TAG.matcher(text);
          while (tag.find()) {
            final String element = tag.group();
            if (element.contains("hidden=\"true\"")) continue; // hidden override, never shown
            final Matcher title = TITLE_ATTR.matcher(element);
            if (!title.find()) continue;
            final String label = title.group(1);
            if (LATIN.matcher(label).find() && !CYRILLIC.matcher(label).find()) {
              titles.putIfAbsent(label, root.relativize(xml).toString());
            }
          }
        }
      }
    }
    return titles;
  }

  /**
   * All Bulgarian translations in the tree: key -> a non-blank message, if any module gives one.
   */
  private static Map<String, String> loadBulgarianAll() throws IOException {
    final Path root = repositoryRoot();
    final List<Path> files = new ArrayList<>();
    try (Stream<Path> groups = Files.list(root.resolve("modules"))) {
      for (Path group : groups.filter(Files::isDirectory).collect(Collectors.toList())) {
        try (Stream<Path> children = Files.list(group)) {
          children
              .filter(Files::isDirectory)
              .forEach(
                  d -> {
                    files.add(d.resolve("src/main/resources/i18n/messages_bg.csv"));
                    files.add(d.resolve("src/main/resources/i18n/custom_bg.csv"));
                  });
        }
      }
    }
    files.add(root.resolve("src/main/resources/i18n/messages_bg.csv"));
    files.add(root.resolve("src/main/resources/i18n/custom_bg.csv"));

    final Map<String, String> bulgarian = new LinkedHashMap<>();
    for (Path file : files) {
      if (!Files.isRegularFile(file)) continue;
      try (InputStream in = Files.newInputStream(file)) {
        for (CSVRecord record : records(in)) {
          final Map<String, String> row = record.toMap();
          final String key = row.get("key");
          final String message = row.getOrDefault("message", "");
          if (StringUtils.isBlank(key) || StringUtils.isBlank(message)) continue;
          bulgarian.putIfAbsent(key, message);
        }
      }
    }
    return bulgarian;
  }

  /**
   * The two modules whose non-menu view labels this guard covers. Scoped on purpose: these are the
   * actively used Nyxa features localised string-by-string, and widening the scan to stock modules
   * would demand a translation for every upstream label.
   */
  private static final Set<String> TWO_LANGUAGE_MODULES =
      Set.of("nyxa-nap-submissions", "axelor-ai-accountant");

  /**
   * Product names and universal standards that are the same in both languages, so neither leaks.
   */
  private static final Set<String> LANGUAGE_NEUTRAL =
      Set.of(
          "ai accountant",
          "ai ocr",
          "microinvest",
          "saf-t",
          "sepa",
          "iban",
          "eik",
          "нап",
          "nap",
          "nyxa id");

  /** A visible label attribute carried by a field/panel/button/tab. */
  private static final Pattern LABEL_ATTR =
      Pattern.compile("\\b(?:title|help|placeholder)=\"([^\"]+)\"");

  /** A lowercase Latin run: the mark of a real English word, as opposed to an all-caps code. */
  private static final Pattern LATIN_WORD = Pattern.compile("[a-z]{2,}");

  /**
   * The non-menu counterpart to the two menu guards: a view label in these modules must read
   * correctly in both languages. An English label (one carrying a lowercase Latin word) needs a
   * Bulgarian override or it leaks English into the BG UI; a label carrying Cyrillic needs an
   * English override or it leaks Cyrillic into the EN UI; a mixed label needs both. Pure codes
   * ({@code SHA-256}) and the product/standard names in {@link #LANGUAGE_NEUTRAL} read the same in
   * both languages and are skipped. Only {@code title/help/placeholder} attributes are scanned —
   * menus have their own guards, and free-text selection codes are reported manually, not here.
   */
  @Test
  @DisplayName("every visible view label in NAP and AI Accountant reads in both languages")
  void everyCustomViewLabelHasBothLanguages() throws Exception {
    final Map<String, String> labels = discoverTwoLanguageViewLabels();
    assertFalse(
        labels.isEmpty(),
        "No view labels were discovered in "
            + TWO_LANGUAGE_MODULES
            + ". Discovery has broken and this test can only ever pass; check the module layout and "
            + "LABEL_ATTR.");

    final Map<String, String> bg = loadBulgarianAll();
    final Map<String, String> en = loadEnglish();

    final List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> label : labels.entrySet()) {
      final String text = label.getKey();
      if (LANGUAGE_NEUTRAL.contains(text.toLowerCase())) continue;

      final boolean hasCyrillic = CYRILLIC.matcher(text).find();
      final boolean hasEnglishWord = LATIN_WORD.matcher(text).find();
      if (!hasCyrillic && !hasEnglishWord) continue; // pure code / number / standard

      if (hasEnglishWord) {
        final String t = bg.get(text);
        if (StringUtils.isBlank(t) || !CYRILLIC.matcher(t).find()) {
          missing.add(
              String.format(
                  "  BG missing for \"%s\"%n      (%s)", shorten(text), label.getValue()));
        }
      }
      if (hasCyrillic) {
        final String t = en.get(text);
        if (StringUtils.isBlank(t) || CYRILLIC.matcher(t).find()) {
          missing.add(
              String.format(
                  "  EN missing for \"%s\"%n      (%s)", shorten(text), label.getValue()));
        }
      }
    }

    assertTrue(
        missing.isEmpty(),
        missing.size()
            + " view label(s) in NAP / AI Accountant do not read in both languages. Add the missing "
            + "side to the owning module's messages_bg.csv / messages_en.csv (key = the authored "
            + "label):\n"
            + String.join("\n", missing)
            + "\n");
  }

  private static String shorten(String s) {
    return s.length() <= 80 ? s : s.substring(0, 77) + "...";
  }

  /** Visible title/help/placeholder labels owned by the two-language modules: label -> origin. */
  private static Map<String, String> discoverTwoLanguageViewLabels() throws IOException {
    final Path root = repositoryRoot();
    assertFalse(root == null, "Repository root not found.");
    final Map<String, String> labels = new TreeMap<>();

    for (Path moduleDir : repositoryModuleDirsForMenus(root)) {
      if (!TWO_LANGUAGE_MODULES.contains(moduleDir.getFileName().toString())) continue;
      final Path resources = moduleDir.resolve("src/main/resources");
      if (!Files.isDirectory(resources)) continue;
      try (Stream<Path> files = Files.walk(resources)) {
        for (Path xml :
            files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".xml"))
                .collect(Collectors.toList())) {
          final String text =
              XML_COMMENT
                  .matcher(new String(Files.readAllBytes(xml), StandardCharsets.UTF_8))
                  .replaceAll("");
          final Matcher m = LABEL_ATTR.matcher(text);
          while (m.find()) {
            // The i18n key is the XML-normalised attribute value: XML turns every newline/tab into
            // a
            // single space but does NOT collapse runs (stock CSV keys keep the source indentation),
            // and entities are decoded. Model that exactly so the key here equals the one looked up
            // at runtime and written into the CSV.
            final String label = xmlDecode(m.group(1));
            labels.putIfAbsent(label, root.relativize(xml).toString());
          }
        }
      }
    }
    return labels;
  }

  /**
   * The {@code axelor-bg-localization} accounting / VAT view files whose visible labels this guard
   * covers. Scoped to a named file set rather than the whole module on purpose: the module is
   * Bulgarian-first and still has a large untranslated payroll/HR/fixed-asset surface, so a
   * module-wide scan would demand translations far outside this batch.
   */
  private static final Set<String> ACCOUNTING_VAT_FILES =
      Set.of(
          "Account-bg.xml",
          "AccountConfig-bg.xml",
          "Year-bg.xml",
          "Invoice-bg.xml",
          "InvoiceLine-bg.xml",
          "Move-bg.xml",
          "MoveLine-bg.xml",
          "bg-book-report-views.xml",
          "bg-saf-mapping-views.xml",
          "Selects-invoice-status-override.xml",
          "Account.xml",
          "AccountConfig.xml",
          "Year.xml",
          "Invoice.xml",
          "InvoiceLine.xml",
          "Move.xml",
          "MoveLine.xml",
          "BgBookReport.xml",
          "BgSafMapping.xml",
          // VAT returns and VAT registers (same name in domains/ and views/, both are scanned).
          "BgVatReturn.xml",
          "BgVatSalesLine.xml",
          "BgVatPurchaseLine.xml",
          "BgVatProtocolNumberEntry.xml",
          // VAT configuration: НАП package/submission, чл. 73 coefficient, чл. 79а/79б annual
          // adjustment, and the Microinvest accounting-import wizard that feeds the same ledgers.
          "BgVatPackage.xml",
          "BgVatPackageEvent.xml",
          "BgVatPackageFile.xml",
          "BgVatSubmission.xml",
          "BgVatCoefficient.xml",
          "BgVatAnnualCorrection.xml",
          "BgVatAnnualCorrectionLine.xml",
          "microinvest-accounting-import-views.xml");

  /**
   * The accounting/VAT counterpart of {@link #everyCustomViewLabelHasBothLanguages()}, with the one
   * difference this module forces: here the labels are Bulgarian-first and the help texts routinely
   * embed identifiers ({@code SEPA}, {@code Dbtr}, {@code BG_COA}, file names), so "has a Latin
   * word" cannot mean "is English". The rule is therefore by script:
   *
   * <ul>
   *   <li>a label carrying Cyrillic reads as Bulgarian and needs an English override, or it leaks
   *       Cyrillic into the EN UI;
   *   <li>a label with no Cyrillic that still carries a real (lowercase) English word, and is not a
   *       product/standard in {@link #LANGUAGE_NEUTRAL}, needs a Bulgarian override, or it leaks
   *       English into the BG UI.
   * </ul>
   *
   * Pure codes ({@code SHA-256}) satisfy neither and are skipped.
   */
  @Test
  @DisplayName("every accounting/VAT view label in bg-localization reads in both languages")
  void everyAccountingVatLabelReadsInBothLanguages() throws Exception {
    final Map<String, String> labels =
        discoverScopedLabels("axelor-bg-localization", ACCOUNTING_VAT_FILES);
    assertFalse(
        labels.isEmpty(),
        "No accounting/VAT labels were discovered. Discovery has broken and this test can only ever "
            + "pass; check ACCOUNTING_VAT_FILES and the module layout.");

    final Map<String, String> bg = loadBulgarianAll();
    final Map<String, String> en = loadEnglish();

    final List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> label : labels.entrySet()) {
      final String text = label.getKey();
      if (LANGUAGE_NEUTRAL.contains(text.toLowerCase())) continue;
      final boolean hasCyrillic = CYRILLIC.matcher(text).find();

      if (hasCyrillic) {
        final String t = en.get(text);
        if (StringUtils.isBlank(t) || CYRILLIC.matcher(t).find()) {
          missing.add(
              String.format(
                  "  EN missing for \"%s\"%n      (%s)", shorten(text), label.getValue()));
        }
      } else if (LATIN_WORD.matcher(text).find()) {
        final String t = bg.get(text);
        if (StringUtils.isBlank(t) || !CYRILLIC.matcher(t).find()) {
          missing.add(
              String.format(
                  "  BG missing for \"%s\"%n      (%s)", shorten(text), label.getValue()));
        }
      }
    }

    assertTrue(
        missing.isEmpty(),
        missing.size()
            + " accounting/VAT label(s) in bg-localization do not read in both languages. Add the "
            + "missing side to messages_bg.csv / messages_en.csv (key = the authored label):\n"
            + String.join("\n", missing)
            + "\n");
  }

  /**
   * The {@code axelor-bg-localization} fixed-asset files whose visible labels this guard covers:
   * the asset record and its categories, the tax depreciation schedule (ДАП) and the accounting
   * one, suspension under art. 59, the disposal/write-off register with its reasons and removal
   * accounts, subsequent costs under art. 63/64, the materiality-threshold raise, the annual
   * tax-temporary-difference report and the depreciation automation settings.
   *
   * <p>Named file by file for the same reason as {@link #ACCOUNTING_VAT_FILES}: the module is
   * Bulgarian-first and still carries an untranslated payroll/HR surface, so a module-wide scan
   * would fail on labels far outside this batch. Selection option texts are not scanned here — they
   * live in one shared {@code Selects.xml} that the payroll selections share, and scoping by file
   * cannot separate the two; the fixed-asset ones are translated and reviewed by hand.
   */
  private static final Set<String> FIXED_ASSET_FILES =
      Set.of(
          // domains/ and views/ share these names; both directories are scanned.
          "FixedAsset.xml",
          "FixedAssetCategory.xml",
          "FixedAssetLine.xml",
          "FixedAsset-bg.xml",
          "FixedAssetCategory-bg.xml",
          "AssetDisposalReason.xml",
          "AssetDisposalReason-bg.xml",
          "BgAssetMaterialityIncrease.xml",
          "BgAssetPlanReport.xml",
          "BgAssetRemovalAccount.xml",
          "BgAssetReports.xml",
          "BgFixedAssetRealizationSettings.xml",
          "BgFixedAssetWriteOff.xml",
          "BgSubsequentCost.xml",
          "BgTaxDifferenceReport.xml",
          "Menu-assets.xml");

  /**
   * The fixed-asset counterpart of {@link #everyAccountingVatLabelReadsInBothLanguages()}, by the
   * same script rule: a label carrying Cyrillic needs an English override or the EN UI shows
   * Bulgarian, and a label with no Cyrillic that still carries a real (lowercase) English word
   * needs a Bulgarian override or the BG UI shows English.
   *
   * <p>It guards a class of gap this surface produces on its own: the screens are authored in
   * Bulgarian legal prose — article references, ДАП/ДАС/СБС — and a field added to one of these
   * forms leaks Cyrillic into the English UI with nothing else failing.
   */
  @Test
  @DisplayName("every fixed-asset view label in bg-localization reads in both languages")
  void everyFixedAssetLabelReadsInBothLanguages() throws Exception {
    final Map<String, String> labels =
        discoverScopedLabels("axelor-bg-localization", FIXED_ASSET_FILES);
    assertFalse(
        labels.isEmpty(),
        "No fixed-asset labels were discovered. Discovery has broken and this test can only ever "
            + "pass; check FIXED_ASSET_FILES and the module layout.");

    final Map<String, String> bg = loadBulgarianAll();
    final Map<String, String> en = loadEnglish();

    final List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> label : labels.entrySet()) {
      final String text = label.getKey();
      if (LANGUAGE_NEUTRAL.contains(text.toLowerCase())) continue;

      if (CYRILLIC.matcher(text).find()) {
        final String t = en.get(text);
        if (StringUtils.isBlank(t) || CYRILLIC.matcher(t).find()) {
          missing.add(
              String.format(
                  "  EN missing for \"%s\"%n      (%s)", shorten(text), label.getValue()));
        }
      } else if (LATIN_WORD.matcher(text).find()) {
        final String t = bg.get(text);
        if (StringUtils.isBlank(t) || !CYRILLIC.matcher(t).find()) {
          missing.add(
              String.format(
                  "  BG missing for \"%s\"%n      (%s)", shorten(text), label.getValue()));
        }
      }
    }

    assertTrue(
        missing.isEmpty(),
        missing.size()
            + " fixed-asset label(s) in bg-localization do not read in both languages. Add the "
            + "missing side to messages_bg.csv / messages_en.csv (key = the authored label):\n"
            + String.join("\n", missing)
            + "\n");
  }

  /**
   * The {@code axelor-bg-localization} payroll / HR files whose visible labels this guard covers:
   * the payroll parameter catalogue and its rate tables, the payslip and its lines, the payroll
   * ledger, the SEPA net-pay run, sick leave with its Appendix No. 9 certificate, absence and leave
   * types, termination compensations, social-expense caps, self-insured registrations, and the
   * employee and employment-contract records that feed all of them.
   *
   * <p>Named file by file for the same reason as {@link #ACCOUNTING_VAT_FILES} and {@link
   * #FIXED_ASSET_FILES}: the module is Bulgarian-first, and scoping by file is what lets each batch
   * be held to both languages without demanding translations for the surfaces that have not been
   * done yet. The D1/D6 and NSSI declaration views are deliberately absent — they are a separate
   * surface with its own transport work and are not part of this batch.
   *
   * <p>This surface produces its own class of gap: the screens are written in Bulgarian statutory
   * prose — КТ, КСО, НСОРЗ article references, Declaration 1 item numbers — so a field added to one
   * of these forms leaks Cyrillic into the English UI with nothing else failing.
   */
  private static final Set<String> PAYROLL_HR_FILES =
      Set.of(
          // domains/ and views/ share these names; both directories are scanned.
          "BgPayrollParameter.xml",
          "BgPayrollParameters.xml",
          "Menu-payroll.xml",
          "BgPaySlip.xml",
          "BgPaySlipLine.xml",
          "BgPaySlipLineType.xml",
          "BgPaySlipRegimeChange.xml",
          "bg-payslip-views.xml",
          "BgPayrollLedgerWizard.xml",
          "bg-payroll-ledger-views.xml",
          "BgPayrollSepaWizard.xml",
          "bg-payroll-sepa-views.xml",
          // Absence, leave and sick leave, incl. the Appendix No. 9 certificate.
          "BgAbsenceType.xml",
          "bg-absence-type-views.xml",
          "BgSickLeave.xml",
          "BgSickLeavePeriodLine.xml",
          "bg-sick-leave-views.xml",
          "LeaveReason.xml",
          "LeaveReason-bg.xml",
          "LeaveRequest.xml",
          // Termination compensations and their types.
          "BgCompensation.xml",
          "BgCompensationPayment.xml",
          "BgCompensationType.xml",
          "bg-compensation-views.xml",
          "bg-compensation-type-views.xml",
          "BgTerminationCompensation.xml",
          // Contributions, funds, insured types and the minimum insurance income tables.
          "BgContributionFund.xml",
          "BgContributionPreview.xml",
          "bg-contribution-preview-views.xml",
          "BgInsuranceContribution.xml",
          "BgInsuredType.xml",
          "bg-insured-type-views.xml",
          "BgMinIncomeOverride.xml",
          "bg-min-income-override-views.xml",
          "BgMinInsuranceIncome.xml",
          "BgMinInsuranceIncomeAmount.xml",
          "bg-min-insurance-income-views.xml",
          "BgTzpbRate.xml",
          "BgGarnishmentScale.xml",
          // Declared hours, children, self-insured registrations and social-expense caps.
          "BgDeclaredWorkHours.xml",
          "bg-declared-work-hours-views.xml",
          "BgEmployeeChild.xml",
          "bg-employee-child-views.xml",
          "BgSelfInsuredRegistration.xml",
          "bg-self-insured-views.xml",
          "BgSocialExpenseCap.xml",
          "BgSocialExpenseException.xml",
          "bg-social-expense-views.xml",
          // The employee and contract records the payroll reads.
          // ЕТЗ — записът за прекратяване.
          "BgContractTermination.xml",
          "Employee.xml",
          "Employee-bg.xml",
          "EmploymentContract.xml",
          "EmploymentContract-bg.xml",
          "BgContractAmendment.xml",
          "bg-contract-amendment-views.xml",
          "BgContractTransfer.xml",
          "EndOfContractReason.xml",
          "EndOfContractReason-bg.xml",
          "ExtraHoursType.xml",
          "ExtraHoursType-bg.xml");

  /**
   * The payroll/HR counterpart of {@link #everyFixedAssetLabelReadsInBothLanguages()}, by the same
   * script rule: a label carrying Cyrillic needs an English override or the EN UI shows Bulgarian,
   * and a label with no Cyrillic that still carries a real (lowercase) English word needs a
   * Bulgarian override or the BG UI shows English.
   */
  @Test
  @DisplayName("every payroll/HR view label in bg-localization reads in both languages")
  void everyPayrollLabelReadsInBothLanguages() throws Exception {
    final Map<String, String> labels =
        discoverScopedLabels("axelor-bg-localization", PAYROLL_HR_FILES);
    assertFalse(
        labels.isEmpty(),
        "No payroll/HR labels were discovered. Discovery has broken and this test can only ever "
            + "pass; check PAYROLL_HR_FILES and the module layout.");

    final Map<String, String> bg = loadBulgarianAll();
    final Map<String, String> en = loadEnglish();

    final List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> label : labels.entrySet()) {
      final String text = label.getKey();
      if (LANGUAGE_NEUTRAL.contains(text.toLowerCase())) continue;

      if (CYRILLIC.matcher(text).find()) {
        final String t = en.get(text);
        if (StringUtils.isBlank(t) || CYRILLIC.matcher(t).find()) {
          missing.add(
              String.format(
                  "  EN missing for \"%s\"%n      (%s)", shorten(text), label.getValue()));
        }
      } else if (LATIN_WORD.matcher(text).find()) {
        final String t = bg.get(text);
        if (StringUtils.isBlank(t) || !CYRILLIC.matcher(t).find()) {
          missing.add(
              String.format(
                  "  BG missing for \"%s\"%n      (%s)", shorten(text), label.getValue()));
        }
      }
    }

    assertTrue(
        missing.isEmpty(),
        missing.size()
            + " payroll/HR label(s) in bg-localization do not read in both languages. Add the "
            + "missing side to messages_bg.csv / messages_en.csv (key = the authored label):\n"
            + String.join("\n", missing)
            + "\n");
  }

  /**
   * The {@code axelor-bg-localization} statutory-declaration files whose visible labels this guard
   * covers: Declaration 1 and its per-insured records, Declaration 6, the declarant identity shared
   * by both, the Appendix No. 9 sick-leave declaration with its NSSI export and the due-dates
   * screen, and the art. 55 PITA/SIC declaration.
   *
   * <p>Named file by file for the same reason as {@link #ACCOUNTING_VAT_FILES}, {@link
   * #FIXED_ASSET_FILES} and {@link #PAYROLL_HR_FILES}: the module is Bulgarian-first, and scoping
   * by file is what lets each batch be held to both languages without demanding translations for
   * the surfaces that have not been done yet.
   *
   * <p>This surface produces its own class of gap: the screens are written in NSSI/NAP filing prose
   * — declaration item numbers, КСО/ЗДДФЛ article references, Appendix No. 9 point numbers — so a
   * field added to one of these forms leaks Cyrillic into the English UI with nothing else failing.
   */
  private static final Set<String> DECLARATION_FILES =
      Set.of(
          // domains/ and views/ share these names; both directories are scanned.
          "BgD1Declaration.xml",
          "BgD1Record.xml",
          "BgD6Declaration.xml",
          "BgDeclarant.xml",
          "bg-d1-views.xml",
          "bg-d6-views.xml",
          // Appendix No. 9 sick-leave declaration, its NSSI export and the due-dates screen.
          "BgApp9Declaration.xml",
          "BgApp9Export.xml",
          "BgApp9ExportLine.xml",
          "bg-app9-views.xml",
          "bg-app9-export-views.xml",
          "bg-app9-due-views.xml",
          // The art. 55 PITA / SIC declaration.
          "BgArt55Declaration.xml",
          "bg-art55-views.xml");

  /**
   * The labels whose English rendering legitimately keeps a Cyrillic letter, because the letter is
   * the value the filer types onto the NAP form: the correction code is „К“ for a correction and
   * „З“ for a deletion, on the form and in the file alike. Rendering those as {@code K} / {@code Z}
   * would print a character the declaration does not accept, so here — and only here — the English
   * message is required to exist and to differ from the Bulgarian key, but may carry the code
   * letter.
   */
  private static final Set<String> STATUTORY_CODE_LABELS =
      Set.of(
          "Буква „К“ САМО когато се НАМАЛЯВА декларирано задължение (чл. 9 ал. 7). При увеличение"
              + " се подава НОВА декларация с положителната разлика.",
          "Буква „К“ при корекция, буква „З“ при заличаване. Празно при редовна декларация.",
          "т. 1 Код корекция (К/З)",
          "т. 7.1 Код корекция (К)");

  /**
   * The statutory-declaration counterpart of {@link #everyPayrollLabelReadsInBothLanguages()}, by
   * the same script rule: a label carrying Cyrillic needs an English override or the EN UI shows
   * Bulgarian, and a label with no Cyrillic that still carries a real (lowercase) English word
   * needs a Bulgarian override or the BG UI shows English. The one relaxation is {@link
   * #STATUTORY_CODE_LABELS}.
   */
  @Test
  @DisplayName("every D1/D6/NSSI declaration view label in bg-localization reads in both languages")
  void everyDeclarationLabelReadsInBothLanguages() throws Exception {
    final Map<String, String> labels =
        discoverScopedLabels("axelor-bg-localization", DECLARATION_FILES);
    assertFalse(
        labels.isEmpty(),
        "No declaration labels were discovered. Discovery has broken and this test can only ever "
            + "pass; check DECLARATION_FILES and the module layout.");

    final Map<String, String> bg = loadBulgarianAll();
    final Map<String, String> en = loadEnglish();

    final List<String> missing = new ArrayList<>();
    for (Map.Entry<String, String> label : labels.entrySet()) {
      final String text = label.getKey();
      if (LANGUAGE_NEUTRAL.contains(text.toLowerCase())) continue;

      if (CYRILLIC.matcher(text).find()) {
        final String t = en.get(text);
        final boolean codeLabel = STATUTORY_CODE_LABELS.contains(text);
        final boolean leaks =
            codeLabel ? text.equals(t) : CYRILLIC.matcher(t == null ? "" : t).find();
        if (StringUtils.isBlank(t) || leaks) {
          missing.add(
              String.format(
                  "  EN missing for \"%s\"%n      (%s)", shorten(text), label.getValue()));
        }
      } else if (LATIN_WORD.matcher(text).find()) {
        final String t = bg.get(text);
        if (StringUtils.isBlank(t) || !CYRILLIC.matcher(t).find()) {
          missing.add(
              String.format(
                  "  BG missing for \"%s\"%n      (%s)", shorten(text), label.getValue()));
        }
      }
    }

    assertTrue(
        missing.isEmpty(),
        missing.size()
            + " declaration label(s) in bg-localization do not read in both languages. Add the "
            + "missing side to messages_bg.csv / messages_en.csv (key = the authored label):\n"
            + String.join("\n", missing)
            + "\n");
  }

  /** Visible title/help/placeholder labels in the named files of one module: label -> origin. */
  private static Map<String, String> discoverScopedLabels(String module, Set<String> fileNames)
      throws IOException {
    final Path root = repositoryRoot();
    assertFalse(root == null, "Repository root not found.");
    final Map<String, String> labels = new TreeMap<>();

    for (Path moduleDir : repositoryModuleDirsForMenus(root)) {
      if (!module.equals(moduleDir.getFileName().toString())) continue;
      final Path resources = moduleDir.resolve("src/main/resources");
      if (!Files.isDirectory(resources)) continue;
      try (Stream<Path> files = Files.walk(resources)) {
        for (Path xml :
            files
                .filter(Files::isRegularFile)
                .filter(p -> fileNames.contains(p.getFileName().toString()))
                .collect(Collectors.toList())) {
          final String text =
              XML_COMMENT
                  .matcher(new String(Files.readAllBytes(xml), StandardCharsets.UTF_8))
                  .replaceAll("");
          final Matcher m = LABEL_ATTR.matcher(text);
          while (m.find()) {
            final String label = xmlDecode(m.group(1));
            labels.putIfAbsent(label, root.relativize(xml).toString());
          }
        }
      }
    }
    return labels;
  }

  /**
   * Turns an attribute value as it is written in the source into the string the XML parser hands
   * Axelor — which is the i18n key. Entities are decoded, the ampersand last so that an escaped
   * entity in the source is not decoded twice, and every newline/tab becomes a single space without
   * collapsing runs.
   *
   * <p>Decoding more than the ampersand is not a formality: the VAT return titles for кл.50 and
   * кл.60 carry a comparison operator, written escaped in the view. Matching on the escaped form
   * would demand a translation for a key that never exists at runtime, and accept one that is never
   * looked up.
   *
   * <p><b>A numeric character reference is not whitespace to be normalised.</b> Attribute-value
   * normalisation applies to the whitespace <i>characters</i> written in the source, not to {@code
   * &#10;} — SAX hands that one through as a real line feed, so the key Axelor looks up carries the
   * line feed. The two long help texts on the asset-disposal screens are written that way, and
   * collapsing their breaks to spaces would demand a translation for a key that never exists. Hence
   * the order here: literal whitespace first, then the references, and the ampersand last so an
   * escaped entity in the source is not decoded twice.
   */
  private static String xmlDecode(String raw) {
    return raw.replaceAll("[\\r\\n\\t]", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&#10;", "\n")
        .replace("&#13;", "\r")
        .replace("&#9;", "\t")
        .replace("&amp;", "&");
  }

  // ------------------------------------------------------------------ analysis

  private static List<Finding> audit() throws Exception {
    final List<Entry> corpus = loadCorpus();
    final List<Term> glossary = loadGlossary();
    final List<Rule> forbidden = loadForbidden();
    final List<Finding> findings = new ArrayList<>();

    // The root application module loads last, so a row it overrides never reaches the user:
    // terminology rules (forbidden terms, glossary) judge the effective value, not the shadowed
    // one.
    final Map<String, String> rootValues = new LinkedHashMap<>();
    for (Entry e : corpus) {
      if (APP_MODULE.equals(e.module) && !e.message.equals(e.key)) {
        rootValues.put(e.key, e.message);
      }
    }

    // key -> first value seen, per file
    final Map<String, Map<String, String>> seen = new LinkedHashMap<>();

    for (Entry e : corpus) {
      final Map<String, String> inFile = seen.computeIfAbsent(e.origin, k -> new LinkedHashMap<>());
      final String previous = inFile.put(e.key, e.message);
      if (previous != null && !previous.equals(e.message)) {
        findings.add(
            new Finding(
                Check.DUPLICATE_KEY_CONFLICT, e, "earlier row in the same file said: " + previous));
      }

      if (StringUtils.isBlank(e.message)) {
        findings.add(new Finding(Check.EMPTY_MESSAGE, e, "falls through to the English key"));
        continue;
      }

      final boolean programmatic = PROGRAMMATIC.matcher(e.key).matches();

      if (e.message.equals(e.key)) {
        if (!programmatic) {
          findings.add(new Finding(Check.MESSAGE_EQUALS_KEY, e, "no translation was ever written"));
        }
      } else if (programmatic) {
        findings.add(
            new Finding(
                Check.PROGRAMMATIC_KEY_TRANSLATED,
                e,
                "dotted key with a translated value - check the code does not read the raw key"));
      } else if (!CYRILLIC.matcher(e.message).find() && LATIN.matcher(e.message).find()) {
        findings.add(new Finding(Check.UNTRANSLATED_LATIN, e, "no Cyrillic in the message"));
      }

      if (FRENCH.matcher(e.message).find()) {
        findings.add(new Finding(Check.FRENCH_RESIDUE, e, "French accented characters"));
      }

      final String inKey = placeholders(e.key);
      final String inMessage = placeholders(e.message);
      if (!inKey.equals(inMessage)) {
        findings.add(
            new Finding(
                Check.PLACEHOLDER_MISMATCH,
                e,
                "key has [" + inKey + "], message has [" + inMessage + "]"));
      }

      final boolean shadowedByRoot =
          !APP_MODULE.equals(e.module)
              && rootValues.containsKey(e.key)
              && !rootValues.get(e.key).equals(e.message);
      if (shadowedByRoot) {
        continue;
      }

      for (Rule rule : forbidden) {
        if (rule.keyScope != null && !rule.keyScope.matcher(e.key).find()) continue;
        final Matcher m = rule.bg.matcher(e.message);
        if (m.find()) {
          findings.add(
              new Finding(
                  Check.FORBIDDEN_TERM,
                  e,
                  rule.id + " [" + rule.severity + "] matched \"" + m.group() + "\": " + rule.why));
        }
      }

      for (Term term : glossary) {
        if (!term.enforce) continue;
        if (!term.enInKey.matcher(e.key).find()) continue;
        if (term.excludedKeys != null && term.excludedKeys.matcher(e.key).find()) continue;
        if (!term.bgInMessage.matcher(e.message).find()) {
          findings.add(
              new Finding(
                  Check.GLOSSARY_DEVIATION,
                  e,
                  "\"" + term.en + "\" must render as \"" + term.bg + "\"" + suffix(term.note)));
        }
      }
    }
    return findings;
  }

  private static String suffix(String note) {
    return StringUtils.isBlank(note) ? "" : " - " + note;
  }

  private static String placeholders(String text) {
    final Matcher m = PLACEHOLDER.matcher(text);
    final List<String> found = new ArrayList<>();
    while (m.find()) found.add(m.group());
    found.sort(Comparator.naturalOrder());
    return String.join("|", found);
  }

  private static Map<String, Integer> countByModuleAndCheck(List<Finding> findings) {
    final Map<String, Integer> counts = new TreeMap<>();
    for (Finding f : findings) {
      if (REPORT_ONLY.contains(f.check)) continue;
      counts.merge(f.entry.module + " / " + f.check, 1, Integer::sum);
    }
    return counts;
  }

  // ------------------------------------------------------------------ loading

  /**
   * Every Bulgarian translation file in the repository: one {@code messages_bg.csv} a module, plus
   * the application module's {@code custom_bg.csv}, which is where contested keys are settled.
   */
  private static List<Entry> loadCorpus() throws IOException {
    final Path root = repositoryRoot();
    assertFalse(root == null, "Repository root not found (looked for settings.gradle + modules/).");

    final List<Entry> corpus = new ArrayList<>();
    final List<Path> files = new ArrayList<>();

    try (Stream<Path> groups = Files.list(root.resolve("modules"))) {
      for (Path group : groups.filter(Files::isDirectory).collect(Collectors.toList())) {
        try (Stream<Path> children = Files.list(group)) {
          children
              .filter(Files::isDirectory)
              .map(d -> d.resolve("src/main/resources/i18n/messages_bg.csv"))
              .filter(Files::isRegularFile)
              .forEach(files::add);
        }
      }
    }
    files.sort(Comparator.comparing(Path::toString));

    final Path custom = root.resolve("src/main/resources/i18n/custom_bg.csv");
    if (Files.isRegularFile(custom)) files.add(custom);

    for (Path file : files) {
      // .../modules/<group>/<module>/src/main/resources/i18n/messages_bg.csv -> <module>
      final String module =
          file.startsWith(root.resolve("modules"))
              ? file.getParent()
                  .getParent()
                  .getParent()
                  .getParent()
                  .getParent()
                  .getFileName()
                  .toString()
              : APP_MODULE;
      final String origin = root.relativize(file).toString();
      try (InputStream in = Files.newInputStream(file)) {
        for (CSVRecord record : records(in)) {
          final Map<String, String> row = record.toMap();
          final String key = row.get("key");
          if (StringUtils.isBlank(key)) continue;
          corpus.add(new Entry(module, origin, key, row.getOrDefault("message", "")));
        }
      }
    }
    return corpus;
  }

  private static List<Term> loadGlossary() throws IOException {
    final List<Term> terms = new ArrayList<>();
    for (Map<String, String> row : audits(GLOSSARY)) {
      final String en = row.get("en");
      if (StringUtils.isBlank(en)) continue;
      final String excluded = row.getOrDefault("key_not_regex", "");
      terms.add(
          new Term(
              en,
              row.get("bg"),
              compile(GLOSSARY, en, "(?<![A-Za-z])" + Pattern.quote(en) + "(?![A-Za-z])"),
              StringUtils.isBlank(excluded) ? null : compile(GLOSSARY, en, excluded),
              compile(GLOSSARY, en, row.get("bg_regex")),
              "yes".equalsIgnoreCase(row.getOrDefault("enforce", "no")),
              row.getOrDefault("note", "")));
    }
    return terms;
  }

  private static List<Rule> loadForbidden() throws IOException {
    final List<Rule> rules = new ArrayList<>();
    for (Map<String, String> row : audits(FORBIDDEN)) {
      final String id = row.get("id");
      if (StringUtils.isBlank(id)) continue;
      final String scope = row.getOrDefault("key_regex", "");
      rules.add(
          new Rule(
              id,
              compile(FORBIDDEN, id, row.get("bg_regex")),
              StringUtils.isBlank(scope) ? null : compile(FORBIDDEN, id, scope),
              row.getOrDefault("severity", "error"),
              row.getOrDefault("why", "")));
    }
    return rules;
  }

  private static Map<String, Integer> loadBaseline() throws IOException {
    final Map<String, Integer> baseline = new TreeMap<>();
    for (Map<String, String> row : audits(BASELINE)) {
      final String module = row.get("module");
      final String check = row.get("check");
      if (StringUtils.isBlank(module) || StringUtils.isBlank(check)) continue;
      baseline.put(module + " / " + check, Integer.parseInt(row.get("max").trim()));
    }
    return baseline;
  }

  private static Pattern compile(String file, String id, String regex) {
    if (StringUtils.isBlank(regex)) {
      throw new IllegalStateException("Empty pattern for \"" + id + "\" in " + file);
    }
    try {
      return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    } catch (PatternSyntaxException e) {
      throw new IllegalStateException(
          "Pattern for \"" + id + "\" in " + file + " does not compile: " + regex, e);
    }
  }

  /** Reads one of the audit data files off the test classpath. */
  private static List<Map<String, String>> audits(String resource) throws IOException {
    try (InputStream in = BgPrevodAuditTest.class.getClassLoader().getResourceAsStream(resource)) {
      assertFalse(
          in == null,
          "Audit data file "
              + resource
              + " is not on the test classpath. Without it the whole "
              + "audit degrades to nothing while still reporting success.");
      final List<Map<String, String>> rows = new ArrayList<>();
      for (CSVRecord record : records(in)) {
        rows.add(record.toMap());
      }
      return rows;
    }
  }

  private static List<CSVRecord> records(InputStream in) throws IOException {
    try (CSVParser parser =
        CSVFile.DEFAULT.withFirstRecordAsHeader().parse(in, StandardCharsets.UTF_8)) {
      return parser.getRecords().stream()
          .filter(r -> !CSVFile.isEmpty(r))
          .collect(Collectors.toList());
    }
  }

  private static Path repositoryRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("settings.gradle"))
          && Files.isDirectory(p.resolve("modules"))) {
        return p;
      }
    }
    return null;
  }

  // ------------------------------------------------------------------ reporting

  private static void report(
      List<Finding> findings, Map<String, Integer> baseline, Map<String, Integer> actual) {
    final StringBuilder out = new StringBuilder();
    out.append("\n=== Bulgarian translation audit ===\n");

    out.append("\n--- findings by check\n");
    for (Check check : Check.values()) {
      final long n = findings.stream().filter(f -> f.check == check).count();
      out.append(
          String.format(
              "  %-28s %5d%s%n", check, n, REPORT_ONLY.contains(check) ? "   (report only)" : ""));
    }

    out.append("\n--- findings by module and check   (b = baselined)\n");
    final Map<String, Integer> byPair = new TreeMap<>(actual);
    byPair.forEach(
        (pair, n) ->
            out.append(
                String.format(
                    "  %s %-46s %5d%s%n",
                    baseline.containsKey(pair) ? "b" : " ",
                    pair,
                    n,
                    baseline.containsKey(pair) ? "   of " + baseline.get(pair) : "")));

    // The stock files carry thousands of defects; printing every one buries the ratchet failure
    // that the reader actually came for. Baselined pairs are printed in full because those are the
    // ones a build can fail on; the rest are sampled.
    out.append("\n--- detail   (baselined pairs in full, others sampled)\n");
    final Map<String, Integer> totals = new LinkedHashMap<>();
    findings.forEach(f -> totals.merge(f.entry.module + " / " + f.check, 1, Integer::sum));
    final Map<String, Integer> printed = new LinkedHashMap<>();
    findings.stream()
        .sorted(
            Comparator.comparing((Finding f) -> f.check.name())
                .thenComparing(f -> f.entry.module)
                .thenComparing(f -> f.entry.key))
        .forEach(
            f -> {
              final String pair = f.entry.module + " / " + f.check;
              final int n = printed.merge(pair, 1, Integer::sum);
              if (baseline.containsKey(pair) || n <= DETAIL_SAMPLE) {
                out.append(f.describe()).append('\n');
              } else if (n == DETAIL_SAMPLE + 1) {
                out.append(
                    String.format(
                        "  ... %s: %d more, not printed%n",
                        pair, totals.getOrDefault(pair, 0) - DETAIL_SAMPLE));
              }
            });

    out.append(
        String.format("%n%d finding(s), %d baselined pair(s)%n", findings.size(), baseline.size()));
    System.out.println(out);
  }
}
