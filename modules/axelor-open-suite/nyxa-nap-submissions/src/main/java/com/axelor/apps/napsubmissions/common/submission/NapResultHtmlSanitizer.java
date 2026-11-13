package com.axelor.apps.napsubmissions.common.submission;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * Makes НАП's HTML result safe to show on the result page.
 *
 * <p>НАП returns its verdict as an HTML page (tables of checks, errors, the incoming number). The
 * bytes are kept untouched as evidence ({@code NapProcessingResult.rawResultArtifact}); this
 * produces a separate display copy through a jsoup allowlist: text, headings, lists, tables and
 * plain links survive; scripts, styles, event handlers, forms, frames and images do not (an image
 * would be an external request from the user's browser). Pure, no database.
 */
public final class NapResultHtmlSanitizer {

  /** A result larger than this is not shown inline; the raw file stays downloadable. */
  static final int MAX_HTML_BYTES = 2 * 1024 * 1024;

  private static final Safelist ALLOWED =
      Safelist.relaxed()
          .removeTags("img")
          .addEnforcedAttribute("a", "rel", "noopener noreferrer nofollow")
          .addEnforcedAttribute("a", "target", "_blank");

  private NapResultHtmlSanitizer() {}

  /**
   * @param html the raw HTML bytes as НАП returned them; the charset is taken from the document
   *     (BOM or meta), UTF-8 otherwise
   * @return the cleaned body, or null when there is nothing to show or it is too large
   */
  public static String sanitize(byte[] html) {
    if (html == null || html.length == 0 || html.length > MAX_HTML_BYTES) {
      return null;
    }
    Document document;
    try {
      document = Jsoup.parse(new ByteArrayInputStream(html), null, "");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    String cleaned = Jsoup.clean(document.body().html(), ALLOWED).trim();
    return cleaned.isEmpty() ? null : cleaned;
  }
}
