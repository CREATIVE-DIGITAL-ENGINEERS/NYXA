package com.axelor.apps.bglocalization.service.nsi;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.nsi.BgNsiGodCalculator.Hit;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Family;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Kind;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Table;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВСЯКА СМЕТКА ОТ BG_COA ИМА ТОЧНО ЕДНО МЯСТО В ГОД.
 *
 * <p>„Точно едно“ значи: основните редове (без „в т.ч.“), които печелят сметката, са един ред, или
 * двойка с допълващи се правила — Д/К по знака (DP + KP), приход и себестойност на продаденото
 * (TNK_NOSTOCK + TN_STOCK), продажба на ДА (TC + TD). Иначе една и съща сума влиза два пъти.
 */
class BgNsiGodCoverageTest {

  private static final Path ACCOUNTS =
      BgDomainColumns.modulesRoot()
          .resolve(
              "axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA/"
                  + "account_account.csv");

  private static final List<Set<String>> ALLOWED =
      Arrays.asList(
          set("DP", "KP"),
          set("TNK_NOSTOCK", "TN_STOCK"),
          set("TC", "IGNORE"),
          set("TD", "IGNORE"));

  private static Set<String> set(String... s) {
    return new TreeSet<>(Arrays.asList(s));
  }

  /** Осчетоводимите сметки на сметкоплана (без групите, вид 1 „view“). */
  static List<String> postingAccounts() throws Exception {
    List<String> out = new ArrayList<>();
    List<String> lines = Files.readAllLines(ACCOUNTS, StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      String[] c = line.split(";", -1);
      String code = c[0].replace("\"", "");
      String type = c[3].replace("\"", "");
      if (!code.isEmpty() && Character.isDigit(code.charAt(0)) && !"1".equals(type)) {
        out.add(code);
      }
    }
    return out;
  }

  private static String rule(Hit h) {
    return h.token.rule != null ? h.token.rule.name() : h.row.kind.name();
  }

  @Test
  @DisplayName("Баланс: всяка сметка от клас 1–7 и 9 — в един ред или в двойка Д/К")
  void балансът_покрива_сметкоплана() throws Exception {
    BgNsiGodForm form = BgNsiGodForm.forYear(2025);
    List<String> problems = new ArrayList<>();
    for (String account : postingAccounts()) {
      check(form, Table.BS, Family.BAL, account, problems);
    }
    assertTrue(problems.isEmpty(), String.join("\n", problems));
  }

  @Test
  @DisplayName("ОПР: всяка сметка от клас 6 и 7 — в един ред или в двойка приход/себестойност")
  void опр_покрива_класове_6_и_7() throws Exception {
    BgNsiGodForm form = BgNsiGodForm.forYear(2025);
    List<String> problems = new ArrayList<>();
    for (String account : postingAccounts()) {
      if (account.startsWith("6") || account.startsWith("7")) {
        check(form, Table.PL, Family.TURN, account, problems);
      }
    }
    assertTrue(problems.isEmpty(), String.join("\n", problems));
  }

  @Test
  @DisplayName("Изключените сметки (застраховане, банки, НПО, 9000/9010) не дават стойност")
  void изключените_не_дават_стойност() throws Exception {
    BgNsiGodForm form = BgNsiGodForm.forYear(2025);
    for (String account : Arrays.asList("104", "131", "161", "417", "661", "761", "9000", "9010")) {
      List<Hit> hits = BgNsiGodCalculator.hits(form, Table.BS, Family.BAL, account);
      assertTrue(
          hits.stream().allMatch(h -> h.row.kind == Kind.EXCL),
          account + " → " + hits.stream().map(h -> h.row.code).collect(Collectors.toList()));
    }
  }

  private static void check(
      BgNsiGodForm form, Table table, Family family, String account, List<String> problems) {
    List<Hit> main =
        BgNsiGodCalculator.hits(form, table, family, account).stream()
            .filter(h -> !h.row.isSub())
            .collect(Collectors.toList());
    if (main.isEmpty()) {
      problems.add(account + ": няма ред в " + table);
      return;
    }
    if (main.size() == 1) {
      return;
    }
    Set<String> rules = main.stream().map(BgNsiGodCoverageTest::rule).collect(Collectors.toSet());
    if (main.size() != 2 || !ALLOWED.contains(new TreeSet<>(rules))) {
      problems.add(
          account
              + " в "
              + table
              + ": "
              + main.stream()
                  .map(h -> (h.row.code.isEmpty() ? h.row.label : h.row.code) + "/" + rule(h))
                  .collect(Collectors.joining(", ")));
    }
  }
}
