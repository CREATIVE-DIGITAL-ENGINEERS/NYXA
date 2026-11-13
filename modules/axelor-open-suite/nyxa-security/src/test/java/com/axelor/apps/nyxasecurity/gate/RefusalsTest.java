package com.axelor.apps.nyxasecurity.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.nyxasecurity.user.CompanyAssignmentRule;
import com.axelor.db.JpaSecurity;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The refusals nyxa-security shows read in Bulgarian (decision В6, 21.10.2026): the role by its
 * Bulgarian name with its code in brackets, then „Обърнете се към администратора.“
 *
 * <p>No database: {@code I18n} reads translations from the database, so the Bulgarian text is
 * rendered here with the module's {@code messages_bg.csv} as the translation.
 */
class RefusalsTest {

  private static final Path BG = Paths.get("src/main/resources/i18n/messages_bg.csv");
  private static final Path ROLES = Paths.get("src/main/resources/nyxa-security/nx_role.csv");
  private static final Path JAVA = Paths.get("src/main/java/com/axelor/apps/nyxasecurity");

  /** The files that throw a refusal at the user; each must take its text from {@link Refusals}. */
  private static final List<String> THROW_SITES =
      List.of(
          "gate/CapabilityGate.java",
          "user/UserSelfEditListener.java",
          "user/CompanyAssignmentRule.java",
          "service/CompanyAssignmentService.java",
          "posting/ManualValidationGuard.java");

  private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");
  private static final Pattern PLACEHOLDER = Pattern.compile("%s");

  @Test
  void everyRefusalHasABulgarianTranslation() throws Exception {
    Map<String, String> bg = bg();
    List<String> missing = new ArrayList<>();
    for (String key : refusalKeys()) {
      String text = bg.get(key);
      if (text == null || !CYRILLIC.matcher(text).find()) {
        missing.add(key);
      } else if (count(key) != count(text)) {
        missing.add(key + " (placeholders differ)");
      }
    }
    assertTrue(missing.isEmpty(), "No Bulgarian in messages_bg.csv for: " + missing);
  }

  @Test
  void everyCatalogueRoleHasABulgarianName() throws Exception {
    Map<String, String> bg = bg();
    List<String> missing = new ArrayList<>();
    for (String line : Files.readAllLines(ROLES, StandardCharsets.UTF_8).subList(1, roleCount())) {
      String role = csv(line).get(0);
      if ("Staff Baseline".equals(role)) {
        continue; // never mapped from Keycloak and never named in a refusal
      }
      String name = Refusals.roleNames().get(role);
      if (name == null || bg.get(name) == null || !CYRILLIC.matcher(bg.get(name)).find()) {
        missing.add(role);
      }
    }
    assertTrue(missing.isEmpty(), "Roles without a Bulgarian name in refusals: " + missing);
  }

  @Test
  void platformRefusalsHaveBulgarian() throws Exception {
    Map<String, String> bg = bg();
    List<String> missing = new ArrayList<>();
    // Without a database AccessType.getMessage() returns its key: exactly what is translated.
    for (JpaSecurity.AccessType type : JpaSecurity.AccessType.values()) {
      if (!bg.containsKey(type.getMessage())) {
        missing.add(type.getMessage());
      }
    }
    if (!bg.containsKey("Access error")) {
      missing.add("Access error");
    }
    assertTrue(missing.isEmpty(), "Platform refusals without Bulgarian: " + missing);
  }

  @Test
  void notAllowedNamesEveryRoleInBulgarianWithItsCode() throws Exception {
    Function<String, String> bg = translator("generate a VAT return", "генериране на справка");
    assertEquals(
        "Нямате право за „генериране на справка“. Нужна е роля „НАП — запис“"
            + " (accounting.nap.write) или „Всички области — запис“ (all.write) или"
            + " „Администратор на клиента“ (client.admin). Обърнете се към администратора.",
        Refusals.notAllowed(bg, "generate a VAT return", Capabilities.NAP_WRITE));
  }

  @Test
  void englishStillStartsWithTheAction() {
    // The English text is the key itself; tests and logs elsewhere look for this beginning.
    assertEquals(
        "You are not allowed to generate a VAT return. Required role: „Tax filings (НАП) — write“"
            + " (accounting.nap.write) or „All areas — write“ (all.write) or „Client"
            + " administrator“ (client.admin). Contact your administrator.",
        Refusals.notAllowed(k -> k, "generate a VAT return", Capabilities.NAP_WRITE));
  }

  @Test
  void flushRefusalTranslatesItsTerms() throws Exception {
    Function<String, String> bg = translator("Invoice", "Фактура");
    Refusals.Refusal refusal =
        new Refusals.Refusal(Refusals.NO_WRITE_ON_SOURCE, new Refusals.Term("Invoice"));
    assertEquals(
        "Нямате право на запис върху документа „Фактура“, от който се осчетоводява това движение,"
            + " затова не можете да го осчетоводите. Обърнете се към администратора.",
        refusal.message(bg));
  }

  @Test
  void assignmentRefusalNamesTheRole() throws Exception {
    assertEquals(
        "Само „Администратор на клиента“ (client.admin) може да назначава компании на"
            + " потребители. Обърнете се към администратора.",
        CompanyAssignmentRule.message(Refusals.ONLY_ASSIGNER, translator()));
  }

  @Test
  void throwSitesTakeTheirTextFromRefusals() throws IOException {
    Pattern literal =
        Pattern.compile(
            "new\\s+[\\w.]*Exception\\s*\\(\\s*\"|return\\s+\"[^\"]*\\.\"|new\\s+[\\w.]*PersistenceException\\s*\\(");
    List<String> found = new ArrayList<>();
    for (String file : THROW_SITES) {
      String source = new String(Files.readAllBytes(JAVA.resolve(file)), StandardCharsets.UTF_8);
      Matcher m = literal.matcher(source);
      while (m.find()) {
        found.add(file + ": " + m.group());
      }
    }
    assertTrue(found.isEmpty(), "Refusal text written in place instead of Refusals: " + found);
  }

  // ------------------------------------------------------------------ helpers

  private static Set<String> refusalKeys() throws IllegalAccessException {
    Set<String> keys = new java.util.LinkedHashSet<>();
    for (Field field : Refusals.class.getFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
        keys.add((String) field.get(null));
      }
    }
    keys.addAll(Refusals.roleNames().values());
    return keys;
  }

  /** messages_bg.csv as a translation, plus extra pairs for keys of other modules. */
  private static Function<String, String> translator(String... extra) throws IOException {
    Map<String, String> bg = new HashMap<>(bg());
    for (int i = 0; i + 1 < extra.length; i += 2) {
      bg.put(extra[i], extra[i + 1]);
    }
    return key -> bg.getOrDefault(key, key);
  }

  private static Map<String, String> bg() throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    List<String> lines = Files.readAllLines(BG, StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      List<String> cells = csv(line);
      if (cells.size() >= 2) {
        out.putIfAbsent(cells.get(0), cells.get(1));
      }
    }
    return out;
  }

  private static int roleCount() throws IOException {
    return Files.readAllLines(ROLES, StandardCharsets.UTF_8).size();
  }

  private static int count(String text) {
    Matcher m = PLACEHOLDER.matcher(text);
    int n = 0;
    while (m.find()) {
      n++;
    }
    return n;
  }

  /** One CSV line, quotes and doubled quotes; the files here have no line breaks in a cell. */
  private static List<String> csv(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          cell.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',' || c == ';') {
        cells.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    cells.add(cell.toString());
    return cells;
  }
}
