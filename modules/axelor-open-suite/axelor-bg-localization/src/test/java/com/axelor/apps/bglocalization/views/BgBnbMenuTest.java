package com.axelor.apps.bglocalization.views;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.meta.loader.XMLViews;
import com.axelor.meta.schema.ObjectViews;
import com.axelor.meta.schema.actions.Action;
import com.axelor.meta.schema.views.MenuItem;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГЛАВНОТО МЕНЮ „БНБ“.
 *
 * <p>Справката СПБ-6А/6Б се премести от „Счетоводство › Отчети“ в собствен корен „БНБ“ след „НСИ“ —
 * по образеца на „НАП“, „НОИ“ и „НСИ“: органът, пред който се отчита. Детето {@code bnb-spb6} сочи
 * към ТОВА СЪЩО действие, а старото {@code bg-bnb-spb6} е скрито с {@code id} и {@code
 * hidden="true"}, за да няма две. Ролите на новото меню са точно ролите, които виждаха старото — и
 * те са отнети от старото през {@code nx_retired.csv}.
 *
 * <p>Менютата се четат така, както ги чете зареждането: {@link XMLViews#unmarshal} е JAXB-ът с
 * object-views.xsd, през който минава {@code ViewLoader}.
 */
class BgBnbMenuTest {

  private static final String ROOT = "bnb-root";
  private static final String CHILD = "bnb-spb6";
  private static final String OLD = "bg-bnb-spb6";
  private static final String OLD_PARENT = "account-root-report";
  private static final String ACTION = "action-bg-bnb-spb6-wizard";

  /** Ролите, които виждаха „Счетоводство › Отчети › БНБ — СПБ-6А/6Б“. */
  private static final Set<String> ROLES =
      new TreeSet<>(
          List.of("accounting.read", "accounting.write", "all.read", "all.write", "client.admin"));

  private static final Path VIEWS = Paths.get("src/main/resources/views");
  private static final Path MENU_GRANTS =
      Paths.get("../nyxa-security/src/main/resources/nyxa-security/nx_menu.csv");
  private static final Path RETIRED =
      Paths.get("../nyxa-security/src/main/resources/nyxa-security/nx_retired.csv");

  /** Всяко определение на меню в изгледите на модула: име → редовете (повече от един = грешка). */
  private static final Map<String, List<MenuItem>> MENUS = new LinkedHashMap<>();

  private static final Set<String> ACTIONS = new LinkedHashSet<>();

  @BeforeAll
  static void readViews() throws Exception {
    try (DirectoryStream<Path> files = Files.newDirectoryStream(VIEWS, "*.xml")) {
      for (Path file : files) {
        ObjectViews views;
        try (InputStream in = Files.newInputStream(file)) {
          views = XMLViews.unmarshal(in);
        }
        if (views.getMenus() != null) {
          for (MenuItem item : views.getMenus()) {
            MENUS.computeIfAbsent(item.getName(), k -> new ArrayList<>()).add(item);
          }
        }
        if (views.getActions() != null) {
          for (Action action : views.getActions()) {
            ACTIONS.add(action.getName());
          }
        }
      }
    }
    assertFalse(MENUS.isEmpty(), "не намирам нито едно меню в " + VIEWS.toAbsolutePath());
  }

  private static MenuItem single(String name) {
    List<MenuItem> found = MENUS.getOrDefault(name, List.of());
    assertEquals(1, found.size(), name + " трябва да е определено точно веднъж в " + VIEWS);
    return found.get(0);
  }

  @Test
  @DisplayName("„БНБ“ е главно меню като „НСИ“ и стои след него")
  void главното_меню_е_като_НСИ() {
    MenuItem root = single(ROOT);
    MenuItem nsi = single("nsi-root");
    MenuItem noi = single("noi-root");
    assertAll(
        () -> assertNull(root.getParent(), ROOT + " трябва да е главно меню (без родител)"),
        () -> assertEquals("БНБ", root.getTitle()),
        () -> assertEquals(nsi.getGroups(), root.getGroups(), "групите — както при „НСИ“"),
        () -> assertFalse(isBlank(root.getIcon()), "без икона"),
        () -> assertFalse(isBlank(root.getIconBackground()), "без цвят на иконата"),
        () -> assertNotEquals(nsi.getIconBackground(), root.getIconBackground(), "цветът на НСИ"),
        () -> assertNotEquals(noi.getIconBackground(), root.getIconBackground(), "цветът на НОИ"),
        () -> assertTrue(root.getOrder() > nsi.getOrder(), "„БНБ“ трябва да е след „НСИ“"),
        () -> assertTrue(root.getOrder() > noi.getOrder(), "„БНБ“ трябва да е след „НОИ“"),
        () -> assertFalse(Boolean.TRUE.equals(root.getHidden()), ROOT + " е скрито"));
  }

  @Test
  @DisplayName("„БНБ › СПБ-6А/6Б“ сочи към същото действие, старото меню е скрито")
  void детето_сочи_към_същото_действие_а_старото_е_скрито() {
    MenuItem child = single(CHILD);
    MenuItem old = single(OLD);
    List<String> visibleWithAction = new ArrayList<>();
    MENUS.values().stream()
        .flatMap(List::stream)
        .filter(m -> ACTION.equals(m.getAction()) && !Boolean.TRUE.equals(m.getHidden()))
        .forEach(m -> visibleWithAction.add(m.getName()));
    assertAll(
        () -> assertEquals(ROOT, child.getParent()),
        () -> assertEquals("СПБ-6А/6Б", child.getTitle()),
        () -> assertEquals(ACTION, child.getAction()),
        () -> assertTrue(ACTIONS.contains(ACTION), ACTION + " не е определено в " + VIEWS),
        () -> assertFalse(Boolean.TRUE.equals(child.getHidden()), CHILD + " е скрито"),
        () -> assertEquals(Boolean.TRUE, old.getHidden(), OLD + " не е hidden=\"true\""),
        () -> assertFalse(isBlank(old.getXmlId()), OLD + " без id — не може да се скрие с hidden"),
        () -> assertEquals(OLD_PARENT, old.getParent(), "родителят на стария ред се пази"),
        () ->
            assertEquals(
                List.of(CHILD), visibleWithAction, "видимите менюта към " + ACTION + " — едно"));
  }

  @Test
  @DisplayName("ролите на „БНБ“ са тези, които виждаха старото меню, и са отнети от него")
  void ролите_са_тези_на_старото_меню() throws Exception {
    Map<String, Set<String>> grants = new LinkedHashMap<>();
    for (String line : Files.readAllLines(MENU_GRANTS, StandardCharsets.UTF_8)) {
      String[] cells = line.split(";", -1);
      if (cells.length == 2) {
        grants.put(unquote(cells[0]), new TreeSet<>(Arrays.asList(unquote(cells[1]).split("\\|"))));
      }
    }
    Set<String> retired = new TreeSet<>();
    for (String line : Files.readAllLines(RETIRED, StandardCharsets.UTF_8)) {
      String[] cells = line.split(";", -1);
      if (cells.length == 3
          && "role-menu".equals(unquote(cells[0]))
          && OLD.equals(unquote(cells[2]))) {
        retired.add(unquote(cells[1]));
      }
    }
    assertAll(
        () -> assertEquals(ROLES, grants.get(ROOT), ROOT + " в " + MENU_GRANTS),
        () -> assertEquals(ROLES, grants.get(CHILD), CHILD + " в " + MENU_GRANTS),
        () -> assertEquals(grants.get("nsi-root"), grants.get(ROOT), "ролите — както при „НСИ“"),
        () -> assertFalse(grants.containsKey(OLD), OLD + " е още дадено в " + MENU_GRANTS),
        () -> assertEquals(ROLES, retired, "отнетите от " + OLD + " в " + RETIRED));
  }

  private static String unquote(String cell) {
    String s = cell.trim();
    return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")
        ? s.substring(1, s.length() - 1)
        : s;
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
