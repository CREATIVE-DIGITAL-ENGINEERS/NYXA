/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Guard against an English "translation" overwriting a real Bulgarian one.
 */
package com.axelor.apps.bglocalization.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.i18n.BgTranslationFiles.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code I18nLoader} upserts by {@code (key, language)} and the last module wins, so a row whose
 * message is just its own English key ({@code "Forwarder","Forwarder"}) silently erases a real
 * translation another module loaded earlier. That is exactly how {@code Products.items}, {@code
 * MoveLine}, {@code Forwarder} and others ended up English.
 *
 * <p>The root application module depends on every other module, so it is loaded last and its value
 * is final (see {@link BgResolutionGuaranteeTest}). A shadowed key is therefore acceptable only if
 * the root {@code custom_bg.csv} / {@code messages_bg.csv} supplies the Bulgarian value.
 */
class BgEnglishShadowTest {

  /** Keys that have an English row AND a Bulgarian row somewhere, and are not settled by root. */
  static Set<String> unsettledShadows(List<Row> rows) {
    final Set<String> settled = BgTranslationFiles.settledByRoot(rows);
    final Set<String> result = new TreeSet<>();
    for (Map.Entry<String, List<Row>> entry : BgTranslationFiles.byKey(rows).entrySet()) {
      final boolean hasEnglish = entry.getValue().stream().anyMatch(Row::isEnglish);
      final boolean hasTranslation = entry.getValue().stream().anyMatch(r -> !r.isEnglish());
      if (hasEnglish && hasTranslation && !settled.contains(entry.getKey())) {
        result.add(entry.getKey());
      }
    }
    return result;
  }

  @Test
  @DisplayName("an English message == key row never shadows a Bulgarian translation")
  void englishRowNeverShadowsTranslation() throws Exception {
    final Set<String> shadows = unsettledShadows(BgTranslationFiles.load());
    assertTrue(
        shadows.isEmpty(),
        "These keys have an English row that can overwrite a Bulgarian translation. Add the"
            + " Bulgarian value to src/main/resources/i18n/custom_bg.csv (root app, always loaded"
            + " last): "
            + shadows);
  }

  @Test
  @DisplayName("the root override layer itself contains no English message == key rows")
  void rootLayerHasNoEnglishRows() throws Exception {
    final List<String> english = new ArrayList<>();
    for (Row row : BgTranslationFiles.load()) {
      if (row.root && row.isEnglish()) {
        english.add(row.key + " (" + row.file + ")");
      }
    }
    assertTrue(english.isEmpty(), "Root rows must carry a real translation: " + english);
  }

  @Test
  @DisplayName("the detector flags a synthetic shadow and accepts a root-settled one")
  void detectorWorks() {
    final List<Row> rows = new ArrayList<>();
    rows.add(new Row("a", "messages_bg.csv", "Forwarder", "Спедитор", false));
    rows.add(new Row("b", "messages_bg.csv", "Forwarder", "Forwarder", false));
    assertEquals(Set.of("Forwarder"), unsettledShadows(rows));

    rows.add(new Row("nyxa", "custom_bg.csv", "Forwarder", "Спедитор", true));
    assertTrue(unsettledShadows(rows).isEmpty());
  }
}
