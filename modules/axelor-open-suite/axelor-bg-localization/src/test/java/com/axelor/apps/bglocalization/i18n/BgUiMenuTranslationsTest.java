/*
 * Nyxa - Bulgarian localization for Axelor Open Suite.
 *
 * Pins the Bulgarian menu and button translations of AOS keys.
 */
package com.axelor.apps.bglocalization.i18n;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.i18n.BgTranslationFiles.Row;
import com.axelor.meta.loader.XMLViews;
import com.axelor.meta.schema.ObjectViews;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.meta.schema.views.MenuItem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * МЕНЮТАТА И БУТОНЪТ „ОСЧЕТОВОДИ“ НА БЪЛГАРСКИ.
 *
 * <p>Ключовете са на AOS (axelor-account, axelor-supplychain, axelor-human-resource, axelor-stock),
 * затова преводът е в {@code custom_bg.csv} на приложния модул {@code nyxa}: той зависи от всички
 * модули и се зарежда последен, а {@code custom_*} е след {@code messages_*} (заковано от {@link
 * BgResolutionGuaranteeTest}). {@link BgTranslationFiles#load()} чете редовете в същия ред, тоест
 * последният ред на ключа е текстът, който приложението показва.
 */
class BgUiMenuTranslationsTest {

  private static final Map<String, String> EXPECTED = new LinkedHashMap<>();

  static {
    EXPECTED.put("Cust. Invoices", "Фактури за продажба");
    EXPECTED.put("Suppl. Invoices", "Фактури за покупка");
    EXPECTED.put("Cust. Credit notes", "Кредитни известия за продажба");
    EXPECTED.put("Suppl. Credit notes", "Кредитни известия за покупка");
    EXPECTED.put("Suppl. Invoices to pay", "Фактури за покупка за плащане");
    EXPECTED.put("Accounting.toAccount", "Осчетоводи");
    EXPECTED.put("Leaves calendar", "Календар на отпуските");
    EXPECTED.put("Human Resources Employee", "Служител в отдел „Човешки ресурси“");
    EXPECTED.put("Stock Correction Reason", "Причина за корекция на запасите");
    // Вече преведен в axelor-stock; тук само се проверява, че текстът е този.
    EXPECTED.put("Stock Correction Reasons", "Причини за корекция на запасите");
  }

  /** Ключовете, чийто текст идва от AOS и не е наш ред в custom_bg.csv. */
  private static final List<String> FROM_AOS = List.of("Stock Correction Reasons");

  private static final Path STOCK_OVERRIDE =
      Paths.get("src/main/resources/views/Menu-stock-override.xml");

  @Test
  @DisplayName("менютата и бутонът на статията връщат новия български текст")
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
      } else if (!last.root && !FROM_AOS.contains(e.getKey())) {
        wrong.add(e.getKey() + " → печели " + last.where() + ", а не custom_bg.csv на nyxa");
      }
    }
    if (!wrong.isEmpty()) {
      fail("преводите не са новите (" + wrong.size() + "):\n  " + String.join("\n  ", wrong));
    }
  }

  @Test
  @DisplayName("общият ключ „Account“ остава „Сметка“ — бутонът има свой ключ")
  void theSharedAccountKeyIsUntouched() throws Exception {
    Map<String, List<Row>> byKey = BgTranslationFiles.byKey(BgTranslationFiles.load());
    List<Row> account = byKey.get("Account");
    assertFalse(account == null || account.isEmpty(), "ключът „Account“ изчезна");
    for (Row row : account) {
      assertEquals(
          "Сметка",
          row.message,
          "„Account“ е полето за сметка навсякъде; „Осчетоводи“ е само за Accounting.toAccount ("
              + row.where()
              + ")");
    }
  }

  /**
   * „Осчетоводи“ е глагол за бутон. Ако ключът почне да се ползва за поле или заглавие, новият
   * превод ще го развали — затова употребите са заковани: двата бутона в Move.xml на axelor-account
   * и extend на axelor-budget, който само ги цели.
   */
  @Test
  @DisplayName("Accounting.toAccount се ползва само за бутоните на статията")
  void toAccountIsOnlyTheMoveButtons() throws Exception {
    Path suite = BgTranslationFiles.repositoryRoot().resolve("modules/axelor-open-suite");
    List<String> uses = new ArrayList<>();
    List<Path> files;
    try (Stream<Path> walk = Files.walk(suite)) {
      files =
          walk.filter(p -> unix(p).contains("/src/main/"))
              .filter(p -> !unix(p).contains("/i18n/"))
              .filter(p -> p.toString().endsWith(".xml") || p.toString().endsWith(".java"))
              .collect(Collectors.toList());
    }
    for (Path file : files) {
      List<String> lines = read(file);
      for (int i = 0; i < lines.size(); i++) {
        if (lines.get(i).contains("Accounting.toAccount")) {
          uses.add(unix(suite.relativize(file)) + ": " + lines.get(i).trim());
        }
      }
    }
    assertEquals(
        List.of(
            "axelor-account/src/main/resources/views/Move.xml: <button name=\"accountingBtn\""
                + " title=\"Accounting.toAccount\" css=\"btn-success\"",
            "axelor-account/src/main/resources/views/Move.xml: <button name=\"accountingMoveBtn\""
                + " title=\"Accounting.toAccount\"",
            "axelor-budget/src/main/resources/views/Move.xml: <extend"
                + " target=\"//button[@name='accountingBtn' and @title='Accounting.toAccount']\">"),
        uses.stream().sorted().collect(Collectors.toList()),
        "Accounting.toAccount има нова употреба — проверете дали „Осчетоводи“ ѝ подхожда");
  }

  /**
   * Менюто и табът със списъка са в множествено число, формата — в единствено. axelor-stock дава
   * един ключ и на трите; менюто и action-view-ът са преопределени с id и ключа „Stock Correction
   * Reasons“, а формата пази „Stock Correction Reason“. Преопределянето заменя чуждия ред изцяло,
   * затова моделът, изгледите, родителят, действието и редът са сверени с axelor-stock.
   */
  @Test
  @DisplayName("„Причини за корекция на запасите“: менюто и списъкът — мн.ч., формата — ед.ч.")
  void stockCorrectionReasonMenuIsPlural() throws Exception {
    ObjectViews ours;
    try (InputStream in = Files.newInputStream(STOCK_OVERRIDE)) {
      ours = XMLViews.unmarshal(in);
    }
    Path stock =
        BgTranslationFiles.repositoryRoot()
            .resolve("modules/axelor-open-suite/axelor-stock/src/main/resources/views");
    ObjectViews upstreamMenus;
    try (InputStream in = Files.newInputStream(stock.resolve("Menu.xml"))) {
      upstreamMenus = XMLViews.unmarshal(in);
    }
    String menuName = "stock-root-conf-stock-correction-reason";
    String actionName = "stock.root.conf.stock.correction.reason";

    MenuItem menu = only(ours.getMenus(), m -> menuName.equals(m.getName()), "менюто");
    MenuItem upMenu =
        only(upstreamMenus.getMenus(), m -> menuName.equals(m.getName()), "чуждото меню");
    assertEquals("Stock Correction Reasons", menu.getTitle());
    assertFalse(
        menu.getXmlId() == null || menu.getXmlId().isBlank(), "менюто без id не се прилага");
    assertEquals(upMenu.getParent(), menu.getParent());
    assertEquals(upMenu.getAction(), menu.getAction());
    assertEquals(upMenu.getOrder(), menu.getOrder());

    ActionView action =
        (ActionView) only(ours.getActions(), a -> actionName.equals(a.getName()), "действието");
    ActionView upAction =
        (ActionView)
            only(
                upstreamMenus.getActions(),
                a -> actionName.equals(a.getName()),
                "чуждото действие");
    assertEquals("Stock Correction Reasons", action.getTitle());
    assertFalse(
        action.getXmlId() == null || action.getXmlId().isBlank(),
        "действието без id не се прилага");
    assertEquals(upAction.getModel(), action.getModel());
    assertEquals(
        views(upAction), views(action), "изгледите на списъка трябва да са като в axelor-stock");
    assertEquals(upAction.getDomain(), action.getDomain());

    try (InputStream in = Files.newInputStream(stock.resolve("StockCorrectionReason.xml"))) {
      String form =
          XMLViews.unmarshal(in).getViews().stream()
              .filter(v -> "stock-correction-reason-form".equals(v.getName()))
              .map(v -> v.getTitle())
              .findFirst()
              .orElse(null);
      assertEquals("Stock Correction Reason", form, "формата пази ключа в единствено число");
    }
  }

  private static List<String> views(ActionView action) {
    return action.getViews().stream()
        .map(v -> v.getType() + ":" + v.getName())
        .collect(Collectors.toList());
  }

  private static <T> T only(List<? extends T> items, Predicate<T> match, String what) {
    List<T> found = new ArrayList<>();
    for (T item : items == null ? List.<T>of() : items) {
      if (match.test(item)) {
        found.add(item);
      }
    }
    assertEquals(1, found.size(), what + ": очаквам точно един ред");
    return found.get(0);
  }

  /** Пътят с „/“ — на Windows {@link Path#toString()} дава „\“ и филтрите не биха хванали нищо. */
  private static String unix(Path path) {
    return path.toString().replace('\\', '/');
  }

  private static List<String> read(Path file) throws IOException {
    try {
      return Files.readAllLines(file, StandardCharsets.UTF_8);
    } catch (java.nio.charset.MalformedInputException e) {
      return List.of();
    }
  }
}
