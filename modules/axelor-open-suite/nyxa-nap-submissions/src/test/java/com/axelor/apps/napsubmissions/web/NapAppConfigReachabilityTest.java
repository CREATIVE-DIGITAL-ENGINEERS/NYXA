package com.axelor.apps.napsubmissions.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.common.Inflector;
import com.axelor.db.mapper.Mapper;
import com.axelor.db.mapper.Property;
import com.axelor.db.mapper.PropertyType;
import com.axelor.studio.db.App;
import com.axelor.studio.db.AppNapSubmissions;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * "Configure" on the "НАП Submissions" app card opens the settings form.
 *
 * <p>axelor-studio {@code AppController#configure} reads {@code "app" + Inflector.camelize(code)}
 * from the card and, only when it is set, looks for a {@code MetaView} of type form, model {@code
 * com.axelor.studio.db.App<Camel>}, named {@code '%-config-form'}. Missing either one, the card
 * says "No configuration required" and the settings (API on/off, API key reference) cannot be
 * reached by a client administrator, whose menu does not include "НАП › Settings".
 */
class NapAppConfigReachabilityTest {

  private static final Path APP_YML = Paths.get("src/main/resources/apps/nap-submissions.yml");
  private static final Path APP_VIEW = Paths.get("src/main/resources/views/AppNapSubmissions.xml");

  @Test
  void theAppCardFindsItsConfiguration() throws Exception {
    String code = codeOf(APP_YML);
    assertEquals("nap-submissions", code);
    String camel = Inflector.getInstance().camelize(code);
    String field = "app" + camel;

    Property link = Mapper.of(App.class).getProperty(field);
    assertNotNull(link, "App has no field " + field + " (domains/App.xml)");
    assertEquals(PropertyType.ONE_TO_ONE, link.getType());
    assertEquals(AppNapSubmissions.class, link.getTarget());
    assertEquals("app", link.getMappedBy());

    assertEquals(AppNapSubmissions.class.getName(), "com.axelor.studio.db.App" + camel);
    String views = new String(Files.readAllBytes(APP_VIEW), StandardCharsets.UTF_8);
    Matcher form =
        Pattern.compile(
                "<form\\s+name=\"([^\"]*-config-form)\"[^>]*model=\""
                    + Pattern.quote(AppNapSubmissions.class.getName())
                    + "\"")
            .matcher(views);
    assertTrue(form.find(), "no *-config-form for AppNapSubmissions");
    String name = form.group(1);
    assertTrue(
        views.contains("<view type=\"form\" name=\"" + name + "\"/>"),
        "the menu action opens the same form");
  }

  private static String codeOf(Path yml) throws Exception {
    for (String line : Files.readAllLines(yml, StandardCharsets.UTF_8)) {
      if (line.startsWith("code:")) {
        return line.substring("code:".length()).trim();
      }
    }
    throw new AssertionError("no code in " + yml);
  }
}
