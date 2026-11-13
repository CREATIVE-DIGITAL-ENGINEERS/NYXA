/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Ratchet over conflicting Bulgarian translations: the set of unsettled conflicts may only shrink.
 */
package com.axelor.apps.bglocalization.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.i18n.BgTranslationFiles.Row;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Two modules defining the same key with different Bulgarian values is decided by module load
 * order. That is harmless for a synonym but wrong when one value is poor (for example "Преместване
 * на сметката" for "Account move"). A conflict is <i>settled</i> when the root application module
 * supplies the value, since it always loads last.
 *
 * <p>{@code bg-conflict-baseline.txt} lists the conflicts that are still open. The test fails when
 * a <b>new</b> unsettled conflict appears, and when a baseline entry has been settled or has
 * disappeared (so the list can only shrink).
 */
class BgConflictRatchetTest {

  private static final String BASELINE = "/i18n/bg-conflict-baseline.txt";

  /** Keys with at least two distinct Bulgarian (non-English) values and no root override. */
  static Set<String> unsettledConflicts(List<Row> rows) {
    final Set<String> settled = BgTranslationFiles.settledByRoot(rows);
    final Set<String> result = new TreeSet<>();
    for (Map.Entry<String, List<Row>> entry : BgTranslationFiles.byKey(rows).entrySet()) {
      final long distinct =
          entry.getValue().stream()
              .filter(r -> !r.isEnglish())
              .map(r -> r.message)
              .distinct()
              .count();
      if (distinct >= 2 && !settled.contains(entry.getKey())) {
        result.add(entry.getKey());
      }
    }
    return result;
  }

  static Set<String> readBaseline() throws IOException {
    try (InputStream in = BgConflictRatchetTest.class.getResourceAsStream(BASELINE)) {
      assertTrue(in != null, "Missing test resource " + BASELINE);
      final String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      final Set<String> keys = new LinkedHashSet<>();
      for (String line : text.split("\n", -1)) {
        if (!line.isEmpty() && !line.startsWith("#")) {
          keys.add(line);
        }
      }
      return keys;
    }
  }

  @Test
  @DisplayName("no new conflicting Bulgarian translation appears outside the baseline")
  void noNewConflicts() throws Exception {
    final Set<String> actual = unsettledConflicts(BgTranslationFiles.load());
    final List<String> added = new ArrayList<>(actual);
    added.removeAll(readBaseline());
    assertTrue(
        added.isEmpty(),
        "New conflicting translations. Pick the winner and put it in the root"
            + " src/main/resources/i18n/custom_bg.csv (or align the modules):\n"
            + added.stream().map(k -> "  " + k).collect(Collectors.joining("\n")));
  }

  @Test
  @DisplayName("the baseline holds no entry that has been settled or has disappeared")
  void baselineHasNoStaleEntries() throws Exception {
    final Set<String> actual = unsettledConflicts(BgTranslationFiles.load());
    final List<String> stale = new ArrayList<>(readBaseline());
    stale.removeAll(actual);
    assertTrue(
        stale.isEmpty(),
        "Delete these lines from bg-conflict-baseline.txt, they are no longer open conflicts:\n"
            + stale.stream().map(k -> "  " + k).collect(Collectors.joining("\n")));
  }

  @Test
  @DisplayName("a root override settles a conflict, an English row does not create one")
  void detectorWorks() {
    final List<Row> rows = new ArrayList<>();
    rows.add(new Row("a", "messages_bg.csv", "Account move", "Статия", false));
    rows.add(new Row("b", "messages_bg.csv", "Account move", "Преместване", false));
    rows.add(new Row("c", "messages_bg.csv", "Forwarder", "Спедитор", false));
    rows.add(new Row("d", "messages_bg.csv", "Forwarder", "Forwarder", false));
    assertEquals(Set.of("Account move"), unsettledConflicts(rows));

    rows.add(new Row("nyxa", "custom_bg.csv", "Account move", "Счетоводно движение", true));
    assertTrue(unsettledConflicts(rows).isEmpty());
  }
}
