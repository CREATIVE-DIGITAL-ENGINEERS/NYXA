package com.axelor.apps.napsubmissions.services.declarations.common;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.napsubmissions.db.repo.NapProcessingResultRepository;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link DeclarationsResultDecoder} — format detection + raw-byte preservation. */
class DeclarationsResultDecoderTest {

  private final DeclarationsResultDecoder decoder = new DeclarationsResultDecoder();

  private static String b64(String s) {
    return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void fullHtmlTakesPrecedenceAndPreservesBytes() {
    String html = "<html><body>ПРОТОКОЛ</body></html>";
    DeclarationsResultResponse r = new DeclarationsResultResponse();
    r.base64FullHtmlResultInfo = b64(html);
    r.base64HtmlResult = b64("<table></table>");

    DeclarationsResultDecoder.Decoded d = decoder.decode(r);
    assertEquals(NapProcessingResultRepository.FORMAT_HTML, d.format);
    assertEquals(NapProcessingResultRepository.OUTCOME_UNKNOWN, d.derivedOutcome);
    assertArrayEquals(html.getBytes(StandardCharsets.UTF_8), d.rawBytes);
  }

  @Test
  void htmlFragmentDetected() {
    DeclarationsResultResponse r = new DeclarationsResultResponse();
    r.base64HtmlResult = b64("<tr><td>x</td></tr>");
    assertEquals(NapProcessingResultRepository.FORMAT_HTML, decoder.decode(r).format);
  }

  @Test
  void nonHtmlJsonSniffed() {
    DeclarationsResultResponse r = new DeclarationsResultResponse();
    r.base64Result = b64("  {\"status\":\"ok\"}");
    DeclarationsResultDecoder.Decoded d = decoder.decode(r);
    assertEquals(NapProcessingResultRepository.FORMAT_JSON, d.format);
    assertEquals("json", d.fileExtension);
  }

  @Test
  void nonHtmlPlainTextSniffed() {
    DeclarationsResultResponse r = new DeclarationsResultResponse();
    r.base64Result = b64("30E000000001;OK;3 records");
    assertEquals(NapProcessingResultRepository.FORMAT_TEXT, decoder.decode(r).format);
  }

  @Test
  void emptyResponseDecodesToNull() {
    assertNull(decoder.decode(new DeclarationsResultResponse()));
    assertNull(decoder.decode(null));
  }
}
