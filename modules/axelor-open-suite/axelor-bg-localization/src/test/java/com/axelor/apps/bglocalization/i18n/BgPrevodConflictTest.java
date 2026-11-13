/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Guard against translation overrides that are not guaranteed to survive module loading.
 */
package com.axelor.apps.bglocalization.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.common.StringUtils;
import com.axelor.common.csv.CSVFile;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Asserts that every translation this module overrides is guaranteed to win.
 *
 * <p><b>What "guaranteed" means here.</b> {@code I18nLoader} overwrites unconditionally — with an
 * existing row it calls {@code entity.setMessage(message)} and saves ({@code I18nLoader:153-161}).
 * There is no "first wins", and no record of which module wrote a row: {@code meta_translation} is
 * keyed on {@code (message_key, language)} alone. So the surviving value is whatever the
 * last-loaded module said.
 *
 * <p>Module load order comes from {@code ModuleResolver}, which walks {@code modules.keySet()} and
 * depth-first resolves each module's dependencies before the module itself. That key set is filled
 * from {@code MetaScanner.findModuleFiles()}, which is a bare {@code
 * ClassLoader#getResources("META-INF/axelor-module.properties")} enumeration — <b>no sorting at
 * all</b>. Consequently:
 *
 * <ul>
 *   <li>If the competing module is a (transitive) dependency of ours, the DFS puts it strictly
 *       before us. We overwrite it. Safe.
 *   <li>If we are a (transitive) dependency of the competing module, it is strictly after us. It
 *       overwrites us. Broken.
 *   <li>If neither depends on the other, the relative order is <b>whatever the classpath happens to
 *       be</b>. It may hold today and invert after a build change, a dependency addition, or a
 *       different JVM. That is not a guarantee, so this test treats it as broken too. Note that
 *       this stays true when we currently win — winning by classpath order is still not a
 *       guarantee, and that is precisely the case the waiver below documents.
 * </ul>
 *
 * <p><b>One case overrides all of the above.</b> If some module defines the key and every other
 * module defining it is inside that module's dependency closure, the DFS must place them all before
 * it, so its write is last no matter how the classpath is ordered. When that module writes our
 * value, the key is settled and the pairwise order of everyone else stops mattering. The
 * application module is the practical instance: it depends on every module in the build, so a key
 * it defines is decided by it alone. Without this rule, applying the very fix this test points at —
 * moving a contested string into the application module — would leave the test failing on the key
 * it just fixed, and the natural response would be to add a waiver that hides a solved problem.
 *
 * <p><b>This test deliberately does not compute the actual load order.</b> Reproducing the
 * classpath enumeration inside a test JVM would produce a check that passes or fails by accident of
 * the day — exactly the property that let this class of bug hide. The dependency graph is
 * deterministic, so that is what gets asserted: read from {@code META-INF/axelor-module.properties}
 * for modules on the classpath, and from the {@code project(":modules:…")} declarations in {@code
 * build.gradle} for repository modules that are not (see {@link #scanModules()}).
 *
 * <p><b>What is not a conflict</b> (both follow from {@code I18nLoader}):
 *
 * <ul>
 *   <li>A blank message in the other module — {@code I18nLoader:149-151} skips blank values, so it
 *       never overwrites anything.
 *   <li>The same value in both modules — order cannot change the outcome.
 * </ul>
 *
 * <p>No Guice, no database, no Axelor runtime context: this reads CSV, properties and Gradle files
 * off the test classpath and the repository tree, the same way {@code BgViewExtendTargetTest} reads
 * view XML.
 */
class BgPrevodConflictTest {

  private static final String OWN_MODULE = "axelor-bg-localization";

  private static final String MODULE_DESCRIPTOR = "META-INF/axelor-module.properties";

  /** Same pattern {@code I18nLoader} uses to recognise a translation file and its language. */
  private static final Pattern FILE_PATTERN =
      Pattern.compile("^i18n/(?:messages_|custom_)([a-zA-Z_-]+)\\.csv$");

  // ------------------------------------------------------------------ waivers

  /**
   * Conflicts that are knowingly accepted. <b>Empty is the intended state</b>, not an oversight:
   * every entry is an override that the dependency graph does not guarantee, kept only because
   * fixing it has been deferred.
   *
   * <p>Adding one requires naming the competing module and saying what would close it. The list is
   * guarded by {@link #waiversAreStillContested()}: a waiver whose conflict has disappeared fails
   * the build rather than lingering as false coverage.
   *
   * <p>It held exactly one entry until the {@code Civility} conflict with {@code axelor-crm} was
   * resolved. That conflict was real but invisible in practice — we won it, just by classpath order
   * rather than by any guarantee, so a build change could have inverted it silently. It was closed
   * by moving the override to {@code src/main/resources/i18n/custom_bg.csv} in the application
   * module, which the graph does guarantee to be last; the waiver then went stale on its own and
   * this test demanded its removal.
   */
  private static final List<Waiver> WAIVERS = List.of();

  private static final class Waiver {
    final String key;
    final String language;
    final String competingModule;
    final String reason;

    Waiver(String key, String language, String competingModule, String reason) {
      this.key = key;
      this.language = language;
      this.competingModule = competingModule;
      this.reason = reason;
    }

    boolean matches(Conflict c) {
      return key.equals(c.key)
          && language.equals(c.language)
          && competingModule.equals(c.competitor.name);
    }

    String describe() {
      return String.format("%s [%s] vs %s", key, language, competingModule);
    }
  }

  // ------------------------------------------------------------------ model

  private static final class ModuleInfo {
    final String name;
    final Set<String> directDepends = new LinkedHashSet<>();

    /** language -> key -> value. */
    final Map<String, Map<String, String>> messages = new LinkedHashMap<>();

    /** language -> key -> origin file, for failure messages. */
    final Map<String, Map<String, String>> origins = new LinkedHashMap<>();

    ModuleInfo(String name) {
      this.name = name;
    }

    void put(String language, String key, String value, String origin) {
      messages.computeIfAbsent(language, k -> new LinkedHashMap<>()).put(key, value);
      origins.computeIfAbsent(language, k -> new LinkedHashMap<>()).put(key, origin);
    }
  }

  private enum Relation {
    /** Competitor is a transitive dependency of ours — always loaded before us. */
    BEFORE_US_GUARANTEED,
    /** We are a transitive dependency of the competitor — it is always loaded after us. */
    AFTER_US_GUARANTEED,
    /** No dependency either way — order is classpath order, i.e. not guaranteed. */
    UNDETERMINED
  }

  private static final class Conflict {
    final String key;
    final String language;
    final String ourValue;
    final String ourOrigin;
    final ModuleInfo competitor;
    final String theirValue;
    final String theirOrigin;
    final Relation relation;

    /** Module guaranteed to write this key last, or null when no module dominates the others. */
    final ModuleInfo settledBy;

    final String settledValue;

    Conflict(
        String key,
        String language,
        String ourValue,
        String ourOrigin,
        ModuleInfo competitor,
        String theirValue,
        String theirOrigin,
        Relation relation,
        ModuleInfo settledBy,
        String settledValue) {
      this.key = key;
      this.language = language;
      this.ourValue = ourValue;
      this.ourOrigin = ourOrigin;
      this.competitor = competitor;
      this.theirValue = theirValue;
      this.theirOrigin = theirOrigin;
      this.relation = relation;
      this.settledBy = settledBy;
      this.settledValue = settledValue;
    }

    /**
     * True when some module writes this key last by construction and writes our value. The pairwise
     * order of everyone else stops mattering: whatever they write is overwritten.
     */
    boolean isSettledInOurFavour() {
      return settledBy != null && ourValue.equals(settledValue);
    }

    boolean isFailure() {
      return !isSettledInOurFavour() && relation != Relation.BEFORE_US_GUARANTEED;
    }

    String describe() {
      return String.format(
          "  key      : %s   [language %s]%n"
              + "  ours     : %s = \"%s\"%n"
              + "             %s%n"
              + "  competing: %s = \"%s\"%n"
              + "             %s%n"
              + "  relation : %s%n"
              + "             %s",
          key,
          language,
          OWN_MODULE,
          ourValue,
          ourOrigin,
          competitor.name,
          theirValue,
          theirOrigin,
          relation,
          explain(relation, competitor.name));
    }

    private static String explain(Relation relation, String other) {
      switch (relation) {
        case AFTER_US_GUARANTEED:
          return other
              + " depends on "
              + OWN_MODULE
              + ", so it is always loaded after us and always overwrites our value.";
        case UNDETERMINED:
          return "neither module depends on the other, so their relative load order is decided "
              + "by classpath enumeration order (MetaScanner.findModuleFiles does not sort). "
              + "Whichever wins today can invert without any change to either module.";
        default:
          return "";
      }
    }
  }

  // ------------------------------------------------------------------ tests

  @Test
  @DisplayName("every translation we override is guaranteed to survive module loading")
  void everyOverrideIsGuaranteedToWin() throws Exception {
    final Map<String, ModuleInfo> modules = scanModules();
    final ModuleInfo ours = requireOwnModule(modules);
    final List<Conflict> conflicts = findConflicts(modules, ours);

    report(ours, conflicts);

    final List<Conflict> unwaived =
        conflicts.stream()
            .filter(Conflict::isFailure)
            .filter(c -> WAIVERS.stream().noneMatch(w -> w.matches(c)))
            .collect(Collectors.toList());

    assertTrue(unwaived.isEmpty(), failureMessage(unwaived));
  }

  /**
   * A waiver that no longer corresponds to a real conflict must be deleted, not left behind. Left
   * behind, it reads as coverage while covering nothing.
   */
  @Test
  @DisplayName("every waiver still corresponds to a real conflict")
  void waiversAreStillContested() throws Exception {
    final Map<String, ModuleInfo> modules = scanModules();
    final ModuleInfo ours = requireOwnModule(modules);
    final List<Conflict> conflicts = findConflicts(modules, ours);

    final List<Waiver> stale =
        WAIVERS.stream()
            .filter(w -> conflicts.stream().filter(Conflict::isFailure).noneMatch(w::matches))
            .collect(Collectors.toList());

    assertTrue(
        stale.isEmpty(),
        "Waiver(s) that no longer match any conflict. Either the competing module stopped "
            + "defining the key, the values converged, the dependency graph changed so we are now "
            + "guaranteed to win, or we stopped overriding the key at all. In every one of those "
            + "cases the waiver is dead weight — delete it:\n  "
            + stale.stream().map(Waiver::describe).collect(Collectors.joining("\n  ")));
  }

  /**
   * Discovery must reach modules that are not our dependencies. This is not a formality: the test
   * classpath contains our dependency closure only, so a classpath-only scan sees every module we
   * depend on — all of them safe by construction — and none of the modules whose order relative to
   * ours is undetermined. Such a test reports "all clear" precisely because it cannot look where
   * the problem is. If this fails, the conflict report above is worthless, not empty.
   */
  @Test
  @DisplayName("discovery reaches modules outside our dependency closure")
  void discoveryCanSeeBeyondOurDependencies() throws Exception {
    final Map<String, ModuleInfo> modules = scanModules();
    requireOwnModule(modules);

    final Set<String> ourDependencies = transitiveDependencies(modules, OWN_MODULE);
    final Set<String> outsiders =
        modules.keySet().stream()
            .filter(name -> !OWN_MODULE.equals(name) && !ourDependencies.contains(name))
            .collect(Collectors.toCollection(LinkedHashSet::new));

    assertFalse(
        outsiders.isEmpty(),
        "Every discovered module is a dependency of "
            + OWN_MODULE
            + ", so no module could ever fall into the UNDETERMINED category and this test can "
            + "only ever pass. Discovery of the repository source tree has broken — check "
            + "repositoryRoot() and the modules/*/* layout. Discovered: "
            + modules.keySet());
  }

  /**
   * The settlement rule can be perfectly correct and still never fire, if the real scan does not
   * discover the application module or gives it an incomplete dependency closure. The synthetic
   * tests below prove the logic; this one proves the wiring it depends on.
   */
  @Test
  @DisplayName("a module dominating every other discovered module exists in the real scan")
  void applicationModuleIsDiscoveredAndDominatesEverything() throws Exception {
    final Map<String, ModuleInfo> modules = scanModules();
    requireOwnModule(modules);

    final List<String> dominators =
        modules.values().stream()
            .filter(
                m -> {
                  final Set<String> closure = transitiveDependencies(modules, m.name);
                  return modules.keySet().stream()
                      .filter(other -> !other.equals(m.name))
                      .allMatch(closure::contains);
                })
            .map(m -> m.name)
            .collect(Collectors.toList());

    assertFalse(
        dominators.isEmpty(),
        "No discovered module depends on every other one, so no key could ever be settled and "
            + "the settlement rule is dead code against this repository. The application module "
            + "is supposed to fill that role — check that its i18n directory still exists and "
            + "that scanRepository still registers it. Discovered: "
            + modules.keySet());
    assertTrue(
        dominators.stream()
            .anyMatch(name -> transitiveDependencies(modules, name).contains(OWN_MODULE)),
        "A dominating module was found ("
            + dominators
            + ") but it does not depend on "
            + OWN_MODULE
            + ", so it could never settle one of our keys.");
  }

  /**
   * Proves the settlement rule actually fires, rather than merely looking as if it would.
   *
   * <p>The situation cannot be staged from the real repository without adding a {@code
   * custom_bg.csv} to the application module, and whether to do that is an open decision — so the
   * module graph is built by hand here. The test carries its own control: the same graph is
   * classified twice, once without the application module and once with it, and the conflict must
   * flip from failing to settled. Asserting only the second half would pass even if the conflict
   * had never been detected at all.
   */
  @Test
  @DisplayName("a module guaranteed to load last settles an otherwise undetermined conflict")
  void applicationModuleSettlesUndeterminedConflicts() {
    final Map<String, ModuleInfo> withoutApp = new LinkedHashMap<>();
    final ModuleInfo base = synthetic(withoutApp, "axelor-base");
    final ModuleInfo crm = synthetic(withoutApp, "axelor-crm", "axelor-base");
    final ModuleInfo ours = synthetic(withoutApp, OWN_MODULE, "axelor-base");
    base.put("bg", "Civility", "Цивилизованост", "synthetic");
    crm.put("bg", "Civility", "Цивилизованост", "synthetic");
    ours.put("bg", "Civility", "Обръщение", "synthetic");

    // Control: without a dominating module, axelor-crm is an unguaranteed competitor.
    final List<Conflict> before = findConflicts(withoutApp, ours);
    final List<Conflict> failingBefore =
        before.stream().filter(Conflict::isFailure).collect(Collectors.toList());
    assertEquals(
        1,
        failingBefore.size(),
        "Control failed: expected exactly one unguaranteed conflict before the application "
            + "module is introduced, got "
            + before);
    assertEquals(Relation.UNDETERMINED, failingBefore.get(0).relation);
    assertEquals("axelor-crm", failingBefore.get(0).competitor.name);

    // Same graph, plus an application module that depends on everything and repeats our value.
    final Map<String, ModuleInfo> withApp = new LinkedHashMap<>();
    final ModuleInfo base2 = synthetic(withApp, "axelor-base");
    final ModuleInfo crm2 = synthetic(withApp, "axelor-crm", "axelor-base");
    final ModuleInfo ours2 = synthetic(withApp, OWN_MODULE, "axelor-base");
    final ModuleInfo app = synthetic(withApp, "nyxa", "axelor-base", "axelor-crm", OWN_MODULE);
    base2.put("bg", "Civility", "Цивилизованост", "synthetic");
    crm2.put("bg", "Civility", "Цивилизованост", "synthetic");
    ours2.put("bg", "Civility", "Обръщение", "synthetic");
    app.put("bg", "Civility", "Обръщение", "synthetic");

    final List<Conflict> after = findConflicts(withApp, ours2);
    assertTrue(
        after.stream().noneMatch(Conflict::isFailure),
        "The application module writes our value last by construction, so nothing here should "
            + "still count as a failure: "
            + after.stream()
                .filter(Conflict::isFailure)
                .map(Conflict::describe)
                .collect(Collectors.joining("\n")));
    assertTrue(
        after.stream().anyMatch(c -> "axelor-crm".equals(c.competitor.name)),
        "The axelor-crm conflict must still be detected and reported, only reclassified as "
            + "settled — silently dropping it would hide a real contest.");
    assertEquals(
        "nyxa",
        after.stream()
            .filter(c -> "axelor-crm".equals(c.competitor.name))
            .findFirst()
            .orElseThrow()
            .settledBy
            .name);
  }

  /**
   * The settlement rule must not neutralise a conflict just because some module loads last — only
   * when that module writes <i>our</i> value. A last writer with a different value is a guaranteed
   * defeat, which is the worst case, not a safe one.
   */
  @Test
  @DisplayName("a module guaranteed to load last with a different value is still a failure")
  void guaranteedLastModuleWithDifferentValueStillFails() {
    final Map<String, ModuleInfo> modules = new LinkedHashMap<>();
    final ModuleInfo base = synthetic(modules, "axelor-base");
    final ModuleInfo crm = synthetic(modules, "axelor-crm", "axelor-base");
    final ModuleInfo ours = synthetic(modules, OWN_MODULE, "axelor-base");
    final ModuleInfo app = synthetic(modules, "nyxa", "axelor-base", "axelor-crm", OWN_MODULE);
    base.put("bg", "Civility", "Цивилизованост", "synthetic");
    crm.put("bg", "Civility", "Цивилизованост", "synthetic");
    ours.put("bg", "Civility", "Обръщение", "synthetic");
    app.put("bg", "Civility", "Нещо трето", "synthetic");

    final List<Conflict> conflicts = findConflicts(modules, ours);
    final List<Conflict> failing =
        conflicts.stream().filter(Conflict::isFailure).collect(Collectors.toList());

    assertFalse(
        failing.isEmpty(),
        "A guaranteed-last module writing a different value defeats our override outright; "
            + "the settlement rule must not swallow that.");
    assertTrue(
        failing.stream()
            .anyMatch(
                c ->
                    "nyxa".equals(c.competitor.name) && c.relation == Relation.AFTER_US_GUARANTEED),
        "The failure must name the application module as the guaranteed overwriter: " + failing);
  }

  /** Builds an in-memory module for the synthetic graphs above. */
  private static ModuleInfo synthetic(
      Map<String, ModuleInfo> into, String name, String... depends) {
    final ModuleInfo module = into.computeIfAbsent(name, ModuleInfo::new);
    module.directDepends.addAll(List.of(depends));
    return module;
  }

  // ------------------------------------------------------------------ analysis

  private static ModuleInfo requireOwnModule(Map<String, ModuleInfo> modules) {
    final ModuleInfo ours = modules.get(OWN_MODULE);
    assertFalse(
        ours == null,
        "Module "
            + OWN_MODULE
            + " was not found on the test classpath (looked for "
            + MODULE_DESCRIPTOR
            + " in every classpath entry). Discovery is broken — this test would otherwise pass "
            + "while checking nothing. Found modules: "
            + modules.keySet());
    assertFalse(
        ours.messages.isEmpty(),
        "Module "
            + OWN_MODULE
            + " was found but carries no translation files. Either i18n resources stopped being "
            + "packaged, or the file naming no longer matches I18nLoader's pattern.");
    return ours;
  }

  private static List<Conflict> findConflicts(Map<String, ModuleInfo> modules, ModuleInfo ours) {
    final List<Conflict> conflicts = new ArrayList<>();
    final Set<String> ourDependencies = transitiveDependencies(modules, OWN_MODULE);

    for (Map.Entry<String, Map<String, String>> byLanguage : ours.messages.entrySet()) {
      final String language = byLanguage.getKey();

      for (Map.Entry<String, String> entry : byLanguage.getValue().entrySet()) {
        final String key = entry.getKey();
        final String ourValue = entry.getValue();

        // A module that every other definer of this key is a dependency of writes last by
        // construction. If it writes our value, the outcome is fixed regardless of how the rest
        // are ordered — see settlement handling in Conflict#isFailure.
        final List<ModuleInfo> definers = definersOf(modules, key, language);
        final ModuleInfo settledBy = guaranteedLastDefiner(modules, definers);
        final String settledValue =
            settledBy == null ? null : settledBy.messages.get(language).get(key);

        for (ModuleInfo other : modules.values()) {
          if (other == ours || OWN_MODULE.equals(other.name)) continue;

          final String theirValue = other.messages.getOrDefault(language, Map.of()).get(key);

          // Not a conflict: the other module does not define the key, or defines it blank
          // (I18nLoader:149-151 skips blank values, so it never overwrites), or agrees with us.
          if (theirValue == null
              || StringUtils.isEmpty(theirValue)
              || theirValue.equals(ourValue)) {
            continue;
          }

          final Relation relation;
          if (ourDependencies.contains(other.name)) {
            relation = Relation.BEFORE_US_GUARANTEED;
          } else if (transitiveDependencies(modules, other.name).contains(OWN_MODULE)) {
            relation = Relation.AFTER_US_GUARANTEED;
          } else {
            relation = Relation.UNDETERMINED;
          }

          conflicts.add(
              new Conflict(
                  key,
                  language,
                  ourValue,
                  ours.origins.get(language).get(key),
                  other,
                  theirValue,
                  other.origins.get(language).get(key),
                  relation,
                  settledBy,
                  settledValue));
        }
      }
    }

    conflicts.sort(
        Comparator.comparing((Conflict c) -> c.language)
            .thenComparing(c -> c.key)
            .thenComparing(c -> c.competitor.name));
    return conflicts;
  }

  /** Every module that defines this key with a value {@code I18nLoader} would actually write. */
  private static List<ModuleInfo> definersOf(
      Map<String, ModuleInfo> modules, String key, String language) {
    return modules.values().stream()
        .filter(
            m -> {
              final String value = m.messages.getOrDefault(language, Map.of()).get(key);
              return value != null && !StringUtils.isEmpty(value);
            })
        .collect(Collectors.toList());
  }

  /**
   * The module whose write for this key is last by construction: every other module defining the
   * key is inside its dependency closure, so the DFS in {@code ModuleResolver} must place them all
   * before it, whatever the classpath order.
   *
   * <p>The application module is the practical instance — it depends on every module in the build,
   * so a key it defines is decided by it alone. Returns null when no module dominates the rest,
   * which is exactly when the outcome is left to classpath order.
   */
  private static ModuleInfo guaranteedLastDefiner(
      Map<String, ModuleInfo> modules, List<ModuleInfo> definers) {
    for (ModuleInfo candidate : definers) {
      final Set<String> closure = transitiveDependencies(modules, candidate.name);
      final boolean dominatesEveryOther =
          definers.stream().filter(d -> d != candidate).allMatch(d -> closure.contains(d.name));
      if (dominatesEveryOther) {
        return candidate;
      }
    }
    return null;
  }

  /** Transitive dependency closure of a module, excluding the module itself. */
  private static Set<String> transitiveDependencies(
      Map<String, ModuleInfo> modules, String moduleName) {
    final Set<String> seen = new LinkedHashSet<>();
    final Deque<String> queue = new ArrayDeque<>();
    final ModuleInfo start = modules.get(moduleName);
    if (start != null) queue.addAll(start.directDepends);

    while (!queue.isEmpty()) {
      final String next = queue.poll();
      if (next == null || next.isEmpty() || !seen.add(next)) continue;
      final ModuleInfo info = modules.get(next);
      if (info != null) queue.addAll(info.directDepends);
    }
    seen.remove(moduleName);
    return seen;
  }

  // ------------------------------------------------------------------ discovery

  /**
   * Indexes every module we can see, from two sources that are each incomplete on their own.
   *
   * <p><b>The test classpath</b> carries our dependency closure and nothing else — including the
   * external addon jars (axelor-utils, axelor-message, axelor-studio) that have no source in this
   * repository. Its module descriptors are authoritative for the dependency graph, because the
   * Gradle plugin writes the full {@code depends} list there, including the platform modules it
   * injects implicitly.
   *
   * <p><b>The repository source tree</b> carries the sibling modules that are deliberately absent
   * from our classpath — and those are precisely the dangerous ones. A module we do not depend on
   * cannot appear on our classpath, yet it is exactly the module whose load order relative to ours
   * is undetermined. Scanning only the classpath would make this test blind to the entire
   * UNDETERMINED category, which is the category it exists to catch. {@link
   * #discoveryCanSeeBeyondOurDependencies} asserts that this does not silently regress.
   */
  private static Map<String, ModuleInfo> scanModules() throws Exception {
    final Map<String, ModuleInfo> modules = new LinkedHashMap<>();
    for (String entry : classpathEntries()) {
      final File file = new File(entry);
      if (file.isDirectory()) {
        scanDirectory(file, modules);
      } else if (file.isFile() && entry.endsWith(".jar")) {
        scanJar(file, modules);
      }
    }
    scanRepository(modules);
    return modules;
  }

  /**
   * Adds modules found in the repository source tree that the classpath did not already provide.
   * Their dependency edges are read from {@code build.gradle} — which is what the Gradle plugin
   * turns into the descriptor's {@code depends} anyway. For these modules only the reverse question
   * matters ("does it depend on us?"), and a project dependency answers it exactly.
   */
  private static void scanRepository(Map<String, ModuleInfo> modules) throws Exception {
    final Path root = repositoryRoot();
    if (root == null) return;

    for (Path moduleDir : repositoryModuleDirs(root)) {
      final String name = moduleDir.getFileName().toString();
      if (modules.containsKey(name)) continue; // classpath descriptor wins

      final Path resources = moduleDir.resolve("src/main/resources");
      if (!Files.isDirectory(resources.resolve("i18n"))) continue;

      final ModuleInfo module = modules.computeIfAbsent(name, ModuleInfo::new);
      module.directDepends.addAll(projectDependencies(moduleDir.resolve("build.gradle")));
      readTranslationsFrom(resources, root, module);
    }

    // The application module depends on every module (root build.gradle wires each of them in),
    // so the dependency graph guarantees it loads last — it can only ever be AFTER_US.
    final Path appResources = root.resolve("src/main/resources");
    if (Files.isDirectory(appResources.resolve("i18n"))) {
      final ModuleInfo app = modules.computeIfAbsent(applicationModuleName(root), ModuleInfo::new);
      app.directDepends.addAll(modules.keySet());
      app.directDepends.remove(app.name);
      readTranslationsFrom(appResources, root, app);
    }
  }

  private static void readTranslationsFrom(Path resources, Path root, ModuleInfo module)
      throws Exception {
    try (Stream<Path> stream = Files.list(resources.resolve("i18n"))) {
      for (Path p : sortedTranslationFiles(stream.collect(Collectors.toList()), Path::toString)) {
        try (InputStream in = Files.newInputStream(p)) {
          readTranslations(in, "i18n/" + p.getFileName(), root.relativize(p).toString(), module);
        }
      }
    }
  }

  /** Walks up from the working directory to the repository root. */
  private static Path repositoryRoot() {
    for (Path p = Paths.get("").toAbsolutePath(); p != null; p = p.getParent()) {
      if (Files.isRegularFile(p.resolve("settings.gradle"))
          && Files.isDirectory(p.resolve("modules"))) {
        return p;
      }
    }
    return null;
  }

  /** Module directories, two levels below {@code modules/} (e.g. modules/axelor-open-suite/x). */
  private static List<Path> repositoryModuleDirs(Path root) throws IOException {
    final List<Path> dirs = new ArrayList<>();
    try (Stream<Path> groups = Files.list(root.resolve("modules"))) {
      for (Path group : groups.filter(Files::isDirectory).collect(Collectors.toList())) {
        try (Stream<Path> children = Files.list(group)) {
          children.filter(Files::isDirectory).forEach(dirs::add);
        }
      }
    }
    dirs.sort(Comparator.comparing(Path::toString));
    return dirs;
  }

  private static final Pattern PROJECT_DEPENDENCY =
      Pattern.compile("project\\(\\s*['\"]:modules:([A-Za-z0-9_.-]+)['\"]\\s*\\)");

  private static Set<String> projectDependencies(Path buildFile) throws IOException {
    final Set<String> names = new LinkedHashSet<>();
    if (!Files.isRegularFile(buildFile)) return names;
    final Matcher matcher =
        PROJECT_DEPENDENCY.matcher(
            new String(Files.readAllBytes(buildFile), StandardCharsets.UTF_8));
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }

  private static String applicationModuleName(Path root) throws IOException {
    final Path settings = root.resolve("settings.gradle");
    final Matcher matcher =
        Pattern.compile("rootProject\\.name\\s*=\\s*['\"]([^'\"]+)['\"]")
            .matcher(new String(Files.readAllBytes(settings), StandardCharsets.UTF_8));
    return matcher.find() ? matcher.group(1) : "application";
  }

  private static void scanDirectory(File root, Map<String, ModuleInfo> modules) throws Exception {
    final Path descriptor = root.toPath().resolve(MODULE_DESCRIPTOR);
    if (!Files.isRegularFile(descriptor)) return;

    final ModuleInfo module;
    try (InputStream in = Files.newInputStream(descriptor)) {
      module = moduleOf(in, modules);
    }
    if (module == null) return;

    final Path i18n = root.toPath().resolve("i18n");
    if (!Files.isDirectory(i18n)) return;

    try (Stream<Path> stream = Files.list(i18n)) {
      for (Path p : sortedTranslationFiles(stream.collect(Collectors.toList()), Path::toString)) {
        final String resource = "i18n/" + p.getFileName();
        try (InputStream in = Files.newInputStream(p)) {
          readTranslations(in, resource, root + "/" + resource, module);
        }
      }
    }
  }

  private static void scanJar(File jar, Map<String, ModuleInfo> modules) throws Exception {
    try (JarFile jarFile = new JarFile(jar)) {
      if (jarFile.getEntry(MODULE_DESCRIPTOR) == null) return;

      final ModuleInfo module;
      try (InputStream in = jarFile.getInputStream(jarFile.getEntry(MODULE_DESCRIPTOR))) {
        module = moduleOf(in, modules);
      }
      if (module == null) return;

      final List<String> names =
          sortedTranslationFiles(
              jarFile.stream().map(java.util.zip.ZipEntry::getName).collect(Collectors.toList()),
              name -> name);

      for (String name : names) {
        try (InputStream in = jarFile.getInputStream(jarFile.getEntry(name))) {
          readTranslations(in, name, jar.getPath() + "!/" + name, module);
        }
      }
    }
  }

  /**
   * Keeps only translation files, ordered the way {@code I18nLoader#findFiles} orders them: {@code
   * custom_*} last, so that within one module a custom file overrides a messages file.
   */
  private static <T> List<T> sortedTranslationFiles(
      List<T> candidates, java.util.function.Function<T, String> toName) {
    return candidates.stream()
        .filter(c -> FILE_PATTERN.matcher(resourceName(toName.apply(c))).matches())
        .sorted(
            Comparator.comparing((T c) -> resourceName(toName.apply(c)).contains("custom_"))
                .thenComparing(c -> toName.apply(c)))
        .collect(Collectors.toList());
  }

  /** Normalises a filesystem path or jar entry name to the {@code i18n/<file>.csv} form. */
  private static String resourceName(String raw) {
    final String normalised = raw.replace('\\', '/');
    final int i18n = normalised.lastIndexOf("i18n/");
    return i18n < 0 ? normalised : normalised.substring(i18n);
  }

  private static ModuleInfo moduleOf(InputStream in, Map<String, ModuleInfo> modules)
      throws IOException {
    final Properties properties = new Properties();
    properties.load(in);
    final String name = properties.getProperty("name", "").trim();
    if (name.isEmpty()) return null;

    final ModuleInfo module = modules.computeIfAbsent(name, ModuleInfo::new);
    Stream.of(properties.getProperty("depends", "").trim().split("\\s*,\\s*"))
        .filter(s -> !s.isEmpty())
        .forEach(module.directDepends::add);
    return module;
  }

  /** Mirrors {@code I18nLoader#process}: same CSV dialect, same key/message columns, same skips. */
  private static void readTranslations(
      InputStream stream, String resource, String origin, ModuleInfo module) throws IOException {
    final Matcher matcher = FILE_PATTERN.matcher(resourceName(resource));
    if (!matcher.matches()) return;

    final String language = StringUtils.normalizeLanguageTag(matcher.group(1));
    final CSVFile csv = CSVFile.DEFAULT.withFirstRecordAsHeader();

    try (CSVParser parser = csv.parse(stream, StandardCharsets.UTF_8)) {
      for (CSVRecord record : parser) {
        if (CSVFile.isEmpty(record)) continue;
        final Map<String, String> map = record.toMap();
        final String key = map.get("key");
        final String message = map.get("message");
        if (StringUtils.isBlank(key) || StringUtils.isEmpty(message)) continue;
        module.put(language, key, message, origin);
      }
    }
  }

  /** Classpath entries, expanding a Gradle pathing jar if one is in use. */
  private static List<String> classpathEntries() {
    final List<String> entries =
        Stream.of(System.getProperty("java.class.path", "").split(File.pathSeparator))
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    if (entries.size() != 1 || !entries.get(0).endsWith(".jar")) {
      return entries;
    }
    final File single = new File(entries.get(0));
    try (JarFile jarFile = new JarFile(single)) {
      final Manifest manifest = jarFile.getManifest();
      if (manifest == null) return entries;
      final String classPath = manifest.getMainAttributes().getValue(Attributes.Name.CLASS_PATH);
      if (classPath == null || classPath.isBlank()) return entries;
      final List<String> expanded = new ArrayList<>();
      for (String part : classPath.split("\\s+")) {
        if (part.isEmpty()) continue;
        try {
          expanded.add(Paths.get(URI.create(part)).toString());
        } catch (Exception e) {
          expanded.add(new File(single.getParentFile(), part).getPath());
        }
      }
      return expanded.isEmpty() ? entries : expanded;
    } catch (IOException e) {
      return entries;
    }
  }

  // ------------------------------------------------------------------ reporting

  private static void report(ModuleInfo ours, List<Conflict> conflicts) {
    final StringBuilder out = new StringBuilder();
    out.append("\n=== ").append(OWN_MODULE).append(" translation overrides ===\n");

    final Map<String, Map<String, String>> byLanguage = new TreeMap<>(ours.messages);
    byLanguage.forEach(
        (language, entries) -> {
          out.append("\n--- language ").append(language).append('\n');
          new TreeMap<>(entries)
              .forEach(
                  (key, value) -> {
                    final List<Conflict> forKey =
                        conflicts.stream()
                            .filter(c -> c.key.equals(key) && c.language.equals(language))
                            .collect(Collectors.toList());
                    out.append(String.format("  %-16s = \"%s\"%n", key, value));
                    if (forKey.isEmpty()) {
                      out.append("                   uncontested\n");
                      return;
                    }
                    for (Conflict c : forKey) {
                      final boolean waived = WAIVERS.stream().anyMatch(w -> w.matches(c));
                      final String verdict;
                      if (c.isSettledInOurFavour() && !OWN_MODULE.equals(c.settledBy.name)) {
                        // Only worth naming when someone else guarantees the outcome; when we are
                        // the dominating module this is the plain "we win" case.
                        verdict = "  (settled by " + c.settledBy.name + ")";
                      } else if (!c.isFailure()) {
                        verdict = "  (we win)";
                      } else {
                        verdict = waived ? "  (WAIVED)" : "  <-- FAIL";
                      }
                      out.append(
                          String.format(
                              "                   %-22s %-24s \"%s\"%s%n",
                              c.relation, c.competitor.name, c.theirValue, verdict));
                    }
                  });
        });

    final long failing = conflicts.stream().filter(Conflict::isFailure).count();
    out.append(
        String.format(
            "%n%d override(s), %d contested, %d not guaranteed, %d waived%n",
            ours.messages.values().stream().mapToInt(Map::size).sum(),
            conflicts.size(),
            failing,
            WAIVERS.size()));
    System.out.println(out);
  }

  private static String failureMessage(List<Conflict> unwaived) {
    final StringBuilder sb = new StringBuilder();
    sb.append(unwaived.size())
        .append(" translation override(s) are not guaranteed to survive module loading.\n")
        .append("I18nLoader overwrites on every load and meta_translation is keyed on ")
        .append("(message_key, language) with no record of the writing module, so the value that ")
        .append("survives is the one written by the module loaded last.\n");
    for (Conflict c : unwaived) {
      sb.append('\n').append(c.describe()).append('\n');
    }
    sb.append(
        "\nOptions: put the override in a module that the dependency graph guarantees to be "
            + "last (the application module depends on every other module), or set the label "
            + "directly on the view so no translation key is contested at all. If the conflict is "
            + "known and accepted, add a waiver to WAIVERS with the reason and what closes it.\n");
    return sb.toString();
  }
}
