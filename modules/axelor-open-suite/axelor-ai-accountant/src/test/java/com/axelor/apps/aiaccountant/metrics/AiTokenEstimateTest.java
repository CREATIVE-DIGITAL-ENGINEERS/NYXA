package com.axelor.apps.aiaccountant.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AiTokenEstimateTest {

  /**
   * Every kind of text the assistant sends lands between what a real BPE tokeniser could do at its
   * most efficient (about 4 characters per token for English, Cyrillic and JSON alike) and the hard
   * ceiling of a byte-level tokeniser (one token per UTF-8 byte). Below the first would be an
   * under-estimate a bound could be crossed on; above the second would be impossible.
   */
  @Test
  void staysBetweenTheEfficientCaseAndTheByteCeilingForEveryKindOfText() {
    String[] samples = {
      "Post the supplier invoice and validate it, then ventilate the next two.",
      "Осчетоводи фактурата от доставчика и я валидирай, после следващите две.",
      "Смени IBAN-а на доставчик Тест Фиш ЕООД (ЕИК 123456789) на BG80BNBG96611020345678.",
      "{\"type\":\"function\",\"name\":\"find_supplier\",\"description\":\"Търси доставчик по ЕИК\","
          + "\"parameters\":{\"type\":\"object\",\"properties\":{\"eik\":{\"type\":\"string\"}},"
          + "\"required\":[\"eik\"]}}"
    };
    for (String text : samples) {
      int estimate = AiTokenEstimate.of(text);
      int chars = text.codePointCount(0, text.length());
      int bytes = text.getBytes(StandardCharsets.UTF_8).length;
      assertTrue(estimate >= chars / 4, "under-estimates: " + text);
      assertTrue(estimate <= bytes, "over the byte ceiling: " + text);
    }
    assertTrue(
        AiTokenEstimate.of("фактура") > AiTokenEstimate.of("invoice"),
        "the same length of Cyrillic costs more than Latin");
    assertEquals(0, AiTokenEstimate.of(null));
    assertEquals(0, AiTokenEstimate.of(""));
  }
}
