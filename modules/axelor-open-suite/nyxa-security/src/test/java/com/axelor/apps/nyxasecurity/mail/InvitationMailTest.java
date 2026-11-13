package com.axelor.apps.nyxasecurity.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.message.db.repo.EmailAccountRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class InvitationMailTest {

  @Test
  void bothTemplatesRenderWithEveryValueEscaped() throws IOException {
    for (String lang : List.of("en", "bg")) {
      String html =
          InvitationMailer.render(
              read(lang),
              "ana+x@example.com",
              "acme<script>",
              List.of("client.admin", "all.read"),
              "https://acme.example/?a=1&b=2");

      assertFalse(html.contains("{{"), lang);
      assertFalse(html.contains("<script>"), lang);
      assertTrue(html.contains("acme&lt;script&gt;"), lang);
      assertTrue(html.contains("href=\"https://acme.example/?a=1&amp;b=2\""), lang);
      assertTrue(html.contains("all.read, client.admin"), lang);
      assertTrue(html.contains("ana+x@example.com"), lang);
    }
  }

  @Test
  void theBulgarianTemplateIsTranslated() throws IOException {
    String html = read("bg");
    assertTrue(html.contains("Получихте достъп"));
    assertFalse(html.contains("You have been given access"));
  }

  @Test
  void anUnknownPlaceholderIsRefused() {
    assertThrows(
        IllegalStateException.class,
        () -> InvitationMailer.render("{{NAME}}", "a@b.c", "t", List.of(), "https://x/"));
  }

  @Test
  void ampersandsAreEscapedOnce() {
    assertEquals("a&amp;lt;b &lt;c&gt;", InvitationMailer.escape("a&lt;b <c>"));
  }

  @Test
  void smtpChannelMapsToTheAccountSecurity() {
    assertEquals(
        EmailAccountRepository.SECURITY_STARTTLS, SmtpEmailAccountSync.security("starttls"));
    assertEquals(EmailAccountRepository.SECURITY_SSL, SmtpEmailAccountSync.security("SSL"));
    assertEquals(EmailAccountRepository.SECURITY_NONE, SmtpEmailAccountSync.security(""));
    assertEquals(587, SmtpEmailAccountSync.defaultPort(EmailAccountRepository.SECURITY_STARTTLS));
  }

  private static String read(String lang) throws IOException {
    String path = String.format(InvitationMailer.TEMPLATE, lang);
    try (InputStream in = InvitationMailTest.class.getClassLoader().getResourceAsStream(path)) {
      assertNotNull(in, path);
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
