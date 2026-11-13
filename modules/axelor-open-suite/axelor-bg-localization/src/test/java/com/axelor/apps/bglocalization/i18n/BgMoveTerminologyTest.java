/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Accounting Move terminology: Движение / Счетоводно движение / Ред от движение.
 */
package com.axelor.apps.bglocalization.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.i18n.BgTranslationFiles.Row;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The owner's terminology is definitive: {@code Move} is "Движение", {@code Account move} is
 * "Счетоводно движение", {@code Move line} is "Ред от движение". "Статия" and "преместване" must
 * never be what the user sees for an accounting move.
 *
 * <p>The test judges the <b>effective</b> value of each key: when the root application module
 * defines the key, its value wins (it loads after every other module); otherwise every module row
 * for the key counts, because the load order between unrelated modules is not guaranteed. Upstream
 * module files may therefore keep a legacy wording, as long as the root layer overrides it.
 *
 * <p>Stock movements ({@code Stock move}, incoming/outgoing/internal moves, MRP) are a different
 * concept and are checked separately: they must never be called a "счетоводно" movement.
 */
class BgMoveTerminologyTest {

  /** Anything that names a move at all. */
  private static final Pattern MOVE_KEY =
      Pattern.compile(
          "(^|[^A-Za-z])moves?([^a-z]|$)|move ?lines?|Move[A-Z]|moveline|origin\\.SimulatedMove"
              + "|entry lines|The input entry",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /** Keys about physical stock movement, mail actions and similar - not accounting moves. */
  private static final Pattern NOT_ACCOUNTING =
      Pattern.compile(
          "stock|st\\. move|incoming|outgoing|internal move|mass move|mrp|inventory|picking"
              + "|delivery|receipt|move to |move up|move down|products to move|draft move"
              + "|move canceled|move realized|remove|moved\\b|mobile|line verification|any product",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /** The two words that the accounting Move must never be translated with. */
  private static final Pattern FORBIDDEN_FOR_MOVE =
      Pattern.compile(
          "(?<![а-я])стати(я|и|ята|ите)(?![а-я])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final Pattern PREMESTVANE =
      Pattern.compile(
          "преместван|преместени|подвижн(ия|ият|ата)? състав",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final Pattern STOCK_MOVE_KEY =
      Pattern.compile("stock ?move|st\\. move", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /** Effective Bulgarian values per key: the root value alone if the root defines the key. */
  static Map<String, List<String>> effective(List<Row> rows) {
    final Map<String, List<Row>> byKey = BgTranslationFiles.byKey(rows);
    final Map<String, List<String>> result = new java.util.LinkedHashMap<>();
    for (Map.Entry<String, List<Row>> entry : byKey.entrySet()) {
      final List<String> root = new ArrayList<>();
      final List<String> all = new ArrayList<>();
      for (Row row : entry.getValue()) {
        if (row.isEnglish()) {
          continue;
        }
        (row.root ? root : all).add(row.message);
      }
      result.put(entry.getKey(), root.isEmpty() ? all : root);
    }
    return result;
  }

  static List<String> violations(Map<String, List<String>> effective) {
    final List<String> found = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : effective.entrySet()) {
      final String key = entry.getKey();
      if (!MOVE_KEY.matcher(key).find() || NOT_ACCOUNTING.matcher(key).find()) {
        continue;
      }
      for (String value : entry.getValue()) {
        if (FORBIDDEN_FOR_MOVE.matcher(value).find() || PREMESTVANE.matcher(value).find()) {
          found.add(key + "  =>  " + value);
        }
      }
    }
    return found;
  }

  @Test
  @DisplayName("an accounting Move is never „статия“ or „преместване“ in the effective translation")
  void accountingMoveIsMovement() throws Exception {
    final List<String> bad = violations(effective(BgTranslationFiles.load()));
    assertTrue(
        bad.isEmpty(),
        "Accounting Move must read Движение / Счетоводно движение / Ред от движение. Put the"
            + " correct wording in the root src/main/resources/i18n/custom_bg.csv:\n  "
            + String.join("\n  ", new TreeSet<>(bad)));
  }

  @Test
  @DisplayName("stock movements are never called an accounting movement")
  void stockMovesStaySeparate() throws Exception {
    final List<String> bad = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : effective(BgTranslationFiles.load()).entrySet()) {
      if (!STOCK_MOVE_KEY.matcher(entry.getKey()).find()) {
        continue;
      }
      for (String value : entry.getValue()) {
        if (value.toLowerCase().contains("счетоводн") || FORBIDDEN_FOR_MOVE.matcher(value).find()) {
          bad.add(entry.getKey() + "  =>  " + value);
        }
      }
    }
    assertTrue(bad.isEmpty(), "Stock moves must stay „Складов документ“: " + bad);
  }

  /** The retired stock wording, in every form; {@code stock-move-dvizhenie} in forbidden-bg.csv. */
  private static final Pattern RETIRED_STOCK_WORDING =
      Pattern.compile(
          "складов[а-я]* движени|скл\\. движени", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  @Test
  @DisplayName("„складово движение“ never reaches the user: a stock move is „складов документ“")
  void retiredStockWordingIsGone() throws Exception {
    final List<String> bad = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : effective(BgTranslationFiles.load()).entrySet()) {
      for (String value : entry.getValue()) {
        if (RETIRED_STOCK_WORDING.matcher(value).find()) {
          bad.add(entry.getKey() + "  =>  " + value);
        }
      }
    }
    assertTrue(
        bad.isEmpty(),
        "Stock move is „Складов документ“. Put the new wording in the root"
            + " src/main/resources/i18n/custom_bg.csv:\n  "
            + String.join("\n  ", new TreeSet<>(bad)));
  }

  @Test
  @DisplayName("the detector separates accounting moves from stock moves")
  void detectorWorks() {
    final List<Row> rows = new ArrayList<>();
    rows.add(new Row("account", "messages_bg.csv", "Move", "Статия", false));
    rows.add(
        new Row("bank", "messages_bg.csv", "Receiver move", "Преместване на приемника", false));
    rows.add(new Row("stock", "messages_bg.csv", "Incoming move", "Входящ ход", false));
    assertEquals(2, violations(effective(rows)).size());

    rows.add(new Row("nyxa", "custom_bg.csv", "Move", "Движение", true));
    rows.add(new Row("nyxa", "custom_bg.csv", "Receiver move", "Движение на получателя", true));
    assertTrue(violations(effective(rows)).isEmpty());
  }
}
