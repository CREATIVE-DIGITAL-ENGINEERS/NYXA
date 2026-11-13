package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.meta.loader.XMLViews;
import com.axelor.meta.schema.ObjectViews;
import com.axelor.meta.schema.actions.Action;
import com.axelor.meta.schema.actions.ActionRecord;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.meta.schema.views.MenuItem;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * „СЧЕТОВОДСТВО › СПРАВКИ › СПРАВКА ПО ЦЕНТРОВЕ“ и подразбирането на „Аналитичен преглед“.
 *
 * <p>Справката е отчетите на Axelor 2000/2001 (аналитична оборотна ведомост и аналитична главна
 * книга), които BG_COA вече създава за всяко дружество. Пазачът държи веригата, която няма кой друг
 * да държи: меню → действие → двата вида отчет в шаблона → двата образеца на Axelor → поредицата на
 * номерата → ролите. Числата ги пази {@code BgAnalyticCentersReportPostgresIntegrationTest}.
 */
class BgAnalyticCentersReportViewTest {

  private static final String MENU = "bg-analytic-centers-report";
  private static final String PARENT = "account-root-report";
  private static final String ACTION = "action-bg-analytic-centers-report";
  private static final String QUERY_DEFAULT = "action-analytic-move-line-query-record-default";

  /** Ролите на другите счетоводни справки („Счетоводни отчети“). */
  private static final String SIBLING = "account-root-accounting-accounting-reports";

  private static final Set<String> ROLES =
      new TreeSet<>(
          List.of("accounting.read", "accounting.write", "all.read", "all.write", "client.admin"));

  private static final Path FILE =
      Paths.get("src/main/resources/views/BgAnalyticCentersReport.xml");
  private static final Path BG_COA =
      Paths.get("../axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");
  private static final Path AOS_REPORTS = Paths.get("../axelor-account/src/main/resources/reports");
  private static final Path MENU_GRANTS =
      Paths.get("../nyxa-security/src/main/resources/nyxa-security/nx_menu.csv");
  private static final Path EN = Paths.get("src/main/resources/i18n/messages_en.csv");

  private static ObjectViews views;

  @BeforeAll
  static void read() throws Exception {
    try (InputStream in = Files.newInputStream(FILE)) {
      views = XMLViews.unmarshal(in);
    }
  }

  private static Action action(String name) {
    List<Action> found =
        views.getActions().stream()
            .filter(a -> name.equals(a.getName()))
            .collect(Collectors.toList());
    assertEquals(1, found.size(), name + " трябва да е определено точно веднъж в " + FILE);
    return found.get(0);
  }

  @Test
  @DisplayName("менюто е под „Счетоводство › Справки“ и отваря само отчетите 2000/2001")
  void менюто_отваря_аналитичните_отчети() {
    List<MenuItem> menus =
        views.getMenus().stream()
            .filter(m -> MENU.equals(m.getName()))
            .collect(Collectors.toList());
    assertEquals(1, menus.size(), MENU);
    MenuItem menu = menus.get(0);
    ActionView view = (ActionView) action(ACTION);
    Map<String, String> contexts = new LinkedHashMap<>();
    view.getContexts().forEach(c -> contexts.put(c.getName(), c.getExpression()));
    assertAll(
        () -> assertEquals(PARENT, menu.getParent()),
        () -> assertEquals("Справка по центрове", menu.getTitle()),
        () -> assertEquals(ACTION, menu.getAction()),
        () -> assertFalse(Boolean.TRUE.equals(menu.getHidden()), MENU + " е скрито"),
        () -> assertEquals("com.axelor.apps.account.db.AccountingReport", view.getModel()),
        () ->
            assertEquals(
                "self.reportType.typeSelect IN (2000, 2001)",
                view.getDomain().trim(),
                "домейнът на списъка"),
        () ->
            assertEquals(
                List.of("grid:accounting-report-grid", "form:accounting-report-form"),
                view.getViews().stream()
                    .map(v -> v.getType() + ":" + v.getName())
                    .collect(Collectors.toList())),
        () -> assertEquals("eval: true", contexts.get("_bgAnalyticCenters")),
        () -> assertEquals("eval: false", contexts.get("_isCustom")));
  }

  @Test
  @DisplayName("BG_COA създава 2000 и 2001 за дружеството, Axelor има образците, поредицата е там")
  void шаблонът_и_образците_са_налице() throws Exception {
    List<String> types = Files.readAllLines(BG_COA.resolve("account_accountingReportType.csv"));
    List<String> sequences = Files.readAllLines(BG_COA.resolve("base_sequence.csv"));
    assertAll(
        () ->
            assertTrue(
                types.stream().anyMatch(l -> l.contains("\"2000\";\"\";\"1\"")),
                "BG_COA без вид 2000 (отчет, не експорт)"),
        () ->
            assertTrue(
                types.stream().anyMatch(l -> l.contains("\"2001\";\"\";\"1\"")),
                "BG_COA без вид 2001 (отчет, не експорт)"),
        () ->
            assertTrue(
                sequences.stream().anyMatch(l -> l.contains("\"analyticReport\"")),
                "BG_COA без поредицата analyticReport — „Печат“ отказва без номер"),
        () ->
            assertTrue(
                Files.isRegularFile(AOS_REPORTS.resolve("AccountingReportType2000.rptdesign"))),
        () ->
            assertTrue(
                Files.isRegularFile(AOS_REPORTS.resolve("AccountingReportType2001.rptdesign"))));
  }

  @Test
  @DisplayName("„Нов“ от менюто: вид 2000 на дружеството и период; извън менюто — нищо")
  void нов_запис_от_менюто() {
    Action group = action("action-bg-analytic-centers-report-on-new");
    ActionRecord type = (ActionRecord) action("action-bg-analytic-centers-report-record-type");
    ActionRecord period = (ActionRecord) action("action-bg-analytic-centers-report-record-period");
    String typeExpr = type.getFields().get(0).getExpression();
    assertAll(
        () -> assertNotNull(group),
        () -> assertEquals("reportType", type.getFields().get(0).getName()),
        () -> assertTrue(typeExpr.contains("self.typeSelect = 2000"), typeExpr),
        () -> assertTrue(typeExpr.contains("self.company = ?1"), "видът — на дружеството"),
        () ->
            assertEquals(
                List.of("dateFrom", "dateTo"),
                period.getFields().stream().map(f -> f.getName()).collect(Collectors.toList())));
    String xml = read(FILE);
    // Всяко действие на групата е под условието на менюто — от „Счетоводни отчети“ нищо не се мени.
    for (String name :
        List.of(
            "action-bg-analytic-centers-report-record-type",
            "action-group-account-accounting-report-reporttype-onchange",
            "action-bg-analytic-centers-report-record-period")) {
      assertTrue(
          xml.matches("(?s).*<action name=\"" + name + "\"\\s+if=\"_bgAnalyticCenters\"/>.*"),
          name + " без if=\"_bgAnalyticCenters\"");
    }
    assertTrue(
        xml.matches(
            "(?s).*<attribute\\s+name=\"domain\"\\s+for=\"reportType\"\\s+if=\"_bgAnalyticCenters\".*"),
        "изборът на вид — само под условието на менюто");
  }

  @Test
  @DisplayName("„Аналитичен преглед“: произход 3 (фактури и осчетоводени), преопределен с id")
  void аналитичният_преглед_търси_и_фактурите() {
    ActionRecord record = (ActionRecord) action(QUERY_DEFAULT);
    Map<String, String> fields = new LinkedHashMap<>();
    record.getFields().forEach(f -> fields.put(f.getName(), f.getExpression()));
    assertAll(
        () -> assertFalse(record.getXmlId() == null || record.getXmlId().isBlank(), "без id"),
        () -> assertEquals("com.axelor.apps.account.db.AnalyticMoveLineQuery", record.getModel()),
        () -> assertEquals("eval: 3", fields.get("specificOriginSelect")),
        () -> assertTrue(fields.containsKey("company"), "дружеството — както при Axelor"),
        () -> assertTrue(fields.containsKey("fromDate")),
        () -> assertTrue(fields.containsKey("toDate")));
  }

  @Test
  @DisplayName("ролите на менюто са тези на другите счетоводни справки; EN превод на заглавието")
  void ролите_и_преводът() throws Exception {
    Map<String, Set<String>> grants = new LinkedHashMap<>();
    for (String line : Files.readAllLines(MENU_GRANTS, StandardCharsets.UTF_8)) {
      String[] cells = line.split(";", -1);
      if (cells.length == 2) {
        grants.put(unquote(cells[0]), new TreeSet<>(Arrays.asList(unquote(cells[1]).split("\\|"))));
      }
    }
    assertAll(
        () -> assertEquals(ROLES, grants.get(MENU), MENU + " в " + MENU_GRANTS),
        () -> assertEquals(grants.get(SIBLING), grants.get(MENU), "както „Счетоводни отчети“"),
        () -> assertEquals(grants.get(PARENT), grants.get(MENU), "както „Справки“"),
        () -> assertTrue(read(EN).contains("\"Справка по центрове\",\""), "липсва EN ред в " + EN));
  }

  private static String read(Path p) {
    try {
      return Files.readString(p, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException(p.toString(), e);
    }
  }

  private static String unquote(String cell) {
    String s = cell.trim();
    return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")
        ? s.substring(1, s.length() - 1)
        : s;
  }
}
