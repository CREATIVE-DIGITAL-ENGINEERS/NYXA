package com.axelor.apps.napsubmissions.common.submission;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class NapResultHtmlSanitizerTest {

  private static String clean(String html) {
    return NapResultHtmlSanitizer.sanitize(html.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void keepsTheResultTablesAndText() {
    String out =
        clean(
            "<html><head><title>Резултат</title></head><body><h3>Вх. № 30E000000001</h3>"
                + "<table border=\"1\"><tr><th>Декларация</th><td colspan=\"2\">приета</td></tr>"
                + "</table></body></html>");
    assertTrue(out.contains("<h3>Вх. № 30E000000001</h3>"), out);
    assertTrue(out.contains("<table>"), out);
    assertTrue(out.contains("colspan=\"2\""), out);
    assertTrue(out.contains("приета"), out);
  }

  @Test
  void dropsAnythingThatCouldRunOrCallOut() {
    String out =
        clean(
            "<body><script>alert(1)</script><style>p{}</style>"
                + "<p onclick=\"alert(2)\" style=\"color:red\">текст</p>"
                + "<img src=\"https://tracker.example/x.png\">"
                + "<a href=\"javascript:alert(3)\">лошо</a>"
                + "<iframe src=\"https://example.com\"></iframe>"
                + "<form action=\"https://example.com\"><input name=\"x\"></form></body>");
    for (String bad :
        new String[] {
          "script", "alert", "style", "onclick", "img", "javascript", "iframe", "form", "input"
        }) {
      assertFalse(out.contains(bad), bad + " survived: " + out);
    }
    assertTrue(out.contains("текст"), out);
  }

  @Test
  void linksOpenSafelyInANewTab() {
    String out = clean("<a href=\"https://portal.nra.bg\">портал</a>");
    assertTrue(out.contains("href=\"https://portal.nra.bg\""), out);
    assertTrue(out.contains("rel=\"noopener noreferrer nofollow\""), out);
    assertTrue(out.contains("target=\"_blank\""), out);
  }

  @Test
  void readsTheCharsetTheDocumentDeclares() {
    String html =
        "<html><head><meta charset=\"windows-1251\"></head><body><p>Приета</p></body></html>";
    String out = NapResultHtmlSanitizer.sanitize(html.getBytes(Charset.forName("windows-1251")));
    assertTrue(out.contains("Приета"), out);
  }

  @Test
  void nothingToShow() {
    assertNull(NapResultHtmlSanitizer.sanitize(null));
    assertNull(NapResultHtmlSanitizer.sanitize(new byte[0]));
    assertNull(clean("<script>only()</script>"));
    assertNull(
        NapResultHtmlSanitizer.sanitize(new byte[NapResultHtmlSanitizer.MAX_HTML_BYTES + 1]));
  }
}
