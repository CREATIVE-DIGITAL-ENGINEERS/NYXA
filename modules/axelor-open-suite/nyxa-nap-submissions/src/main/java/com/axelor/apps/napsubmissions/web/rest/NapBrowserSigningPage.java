package com.axelor.apps.napsubmissions.web.rest;

import com.axelor.app.internal.AppFilter;
import com.axelor.i18n.I18n;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ПОДПИСВАЩАТА СТРАНИЦА С ПРЕВОДИТЕ НА ПОТРЕБИТЕЛЯ.
 *
 * <p>Текстовете в {@code nap-browser-signing.html} са английски ключове — в JavaScript като {@code
 * t("...")} (и като няколко литерала, слепени с {@code +}), в HTML като {@code data-i18n="..."}.
 * Тук ключовете се намират в самия файл, превеждат се с {@link I18n#get(String)} за езика на
 * текущия потребител и се вграждат в страницата като JSON. Така няма втори списък с ключове, който
 * да се разминава със страницата, а преводите са в {@code messages_bg.csv} както за всеки изглед.
 */
public final class NapBrowserSigningPage {

  static final String RESOURCE = "/nap-browser-signing/nap-browser-signing.html";

  private static final String LITERAL = "\"(?:[^\"\\\\]|\\\\.)*\"";

  /** {@code t("a" + "b", ...)} — един или няколко слепени литерала като първи аргумент. */
  private static final Pattern T_CALL =
      Pattern.compile("\\bt\\(\\s*(" + LITERAL + "(?:\\s*\\+\\s*" + LITERAL + ")*)");

  private static final Pattern LITERAL_PART = Pattern.compile(LITERAL);

  private static final Pattern DATA_I18N = Pattern.compile("data-i18n=\"([^\"]*)\"");

  private static final Pattern UNICODE_ESCAPE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

  private NapBrowserSigningPage() {}

  /** Страницата, готова за браузъра: с езика и преводите на текущия потребител. */
  public static String render() throws IOException {
    String html = template();
    Locale locale = AppFilter.getLocale();
    String lang = locale == null || locale.getLanguage().isEmpty() ? "en" : locale.getLanguage();
    return render(html, lang, I18n::get);
  }

  static String render(String html, String lang, UnaryOperator<String> translate)
      throws IOException {
    Map<String, String> messages = new LinkedHashMap<>();
    for (String key : keys(html)) {
      String value = translate.apply(key);
      messages.put(key, value == null || value.isEmpty() ? key : value);
    }
    // В <script> не бива да се появи "</script>" — "<" се кодира, JSON-ът остава същият.
    String json = new ObjectMapper().writeValueAsString(messages).replace("<", "\\u003c");
    return html.replace("__NAP_LANG__", lang.replaceAll("[^A-Za-z-]", ""))
        .replace("__NAP_I18N__", json);
  }

  static String template() throws IOException {
    try (InputStream in = NapBrowserSigningPage.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IOException("signing page not found");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  /** Всички ключове за превод в страницата, в реда на появяване. */
  static Set<String> keys(String html) {
    Set<String> keys = new LinkedHashSet<>();
    Matcher attribute = DATA_I18N.matcher(html);
    while (attribute.find()) {
      keys.add(attribute.group(1));
    }
    Matcher call = T_CALL.matcher(html);
    while (call.find()) {
      StringBuilder key = new StringBuilder();
      Matcher part = LITERAL_PART.matcher(call.group(1));
      while (part.find()) {
        String literal = part.group();
        key.append(unescape(literal.substring(1, literal.length() - 1)));
      }
      keys.add(key.toString());
    }
    return keys;
  }

  static String unescape(String body) {
    Matcher unicode = UNICODE_ESCAPE.matcher(body);
    StringBuilder out = new StringBuilder();
    while (unicode.find()) {
      unicode.appendReplacement(
          out,
          Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(unicode.group(1), 16))));
    }
    unicode.appendTail(out);
    return out.toString().replace("\\\"", "\"").replace("\\'", "'").replace("\\\\", "\\");
  }
}
