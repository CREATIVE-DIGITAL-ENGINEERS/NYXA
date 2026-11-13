package com.axelor.apps.napsubmissions.web.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * The signing page is English in its source and gets the user's translations from the server.
 *
 * <p>Every key the page uses ({@code t("...")}, also split over several {@code +}-joined literals,
 * and {@code data-i18n}) must have a Bulgarian row in this module's {@code messages_bg.csv}, and no
 * Bulgarian may be left in the page outside comments.
 */
class NapBrowserSigningPageTest {

  private static final Path BG = Paths.get("src/main/resources/i18n/messages_bg.csv");

  private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");

  @Test
  void findsKeysInCallsJoinedLiteralsAndAttributes() {
    String html =
        "<b data-i18n=\"Title\">Title</b><script>say(t(\"One {0}\", x));"
            + " t(\"Two \" +\n   \"parts \\u201cq\\u201d\");</script>";
    assertEquals(
        List.of("Title", "One {0}", "Two parts “q”"),
        new ArrayList<>(NapBrowserSigningPage.keys(html)));
  }

  @Test
  void rendersLanguageAndTranslationsSafely() throws Exception {
    String html =
        "<html lang=\"__NAP_LANG__\"><script id=\"nap-i18n\">__NAP_I18N__</script>"
            + "<script>t(\"Hello\"); t(\"</script>\");</script>";
    String page =
        NapBrowserSigningPage.render(html, "bg", key -> key.equals("Hello") ? "Здравей" : key);
    assertTrue(page.contains("lang=\"bg\""));
    assertTrue(page.contains("\"Hello\":\"Здравей\""));
    // A "</script>" inside a translation cannot close the JSON block early.
    assertFalse(page.contains("__NAP_I18N__"));
    assertEquals(3, page.split("</script>", -1).length - 1);
  }

  @Test
  void everyPageKeyHasABulgarianTranslation() throws Exception {
    Set<String> keys = NapBrowserSigningPage.keys(NapBrowserSigningPage.template());
    assertTrue(keys.size() > 40, "keys found: " + keys.size());
    Map<String, String> bg = readBg();
    List<String> missing = new ArrayList<>();
    for (String key : keys) {
      String value = bg.get(key);
      if (value == null || value.isEmpty() || !CYRILLIC.matcher(value).find()) {
        missing.add(key);
      }
    }
    assertTrue(missing.isEmpty(), "No Bulgarian row in " + BG + " for: " + missing);
  }

  @Test
  void theLoaderPromisesTheServersNapTimeout() throws Exception {
    java.util.regex.Matcher m =
        Pattern.compile("var NAP_WAIT_SECONDS = (\\d+);").matcher(NapBrowserSigningPage.template());
    assertTrue(m.find(), "NAP_WAIT_SECONDS not found in the signing page");
    assertEquals(
        com.axelor.apps.napsubmissions.services.declarations.common.NraDeclarationsApiClient
            .REQUEST_TIMEOUT
            .getSeconds(),
        Long.parseLong(m.group(1)));
  }

  @Test
  void theSourceIsEnglishOutsideComments() throws Exception {
    String code =
        NapBrowserSigningPage.template()
            .replaceAll("(?s)<!--.*?-->", "")
            .replaceAll("(?s)/\\*.*?\\*/", "")
            .replaceAll("(?m)^\\s*//.*$", "")
            .replaceAll("(?m)\\s//\\s.*$", "");
    List<String> lines = new ArrayList<>();
    for (String line : code.split("\n")) {
      if (CYRILLIC.matcher(line).find()) {
        lines.add(line.trim());
      }
    }
    assertTrue(lines.isEmpty(), "Bulgarian text outside comments: " + lines);
  }

  private static Map<String, String> readBg() throws Exception {
    String text = new String(Files.readAllBytes(BG), StandardCharsets.UTF_8);
    Map<String, String> rows = new HashMap<>();
    List<String> row = new ArrayList<>();
    StringBuilder field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
          field.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          field.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        row.add(field.toString());
        field.setLength(0);
      } else if (c == '\n') {
        row.add(field.toString().replace("\r", ""));
        if (row.size() >= 2) {
          rows.put(row.get(0), row.get(1));
        }
        row = new ArrayList<>();
        field.setLength(0);
      } else {
        field.append(c);
      }
    }
    return rows;
  }
}
