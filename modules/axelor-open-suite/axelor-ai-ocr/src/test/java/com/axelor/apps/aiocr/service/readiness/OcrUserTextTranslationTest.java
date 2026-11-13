package com.axelor.apps.aiocr.service.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.record.OcrDocumentRefusal;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Every text the supplier invoice form shows from this module — why Validate is locked, what
 * Validate or Ventilate would refuse, the OCR refusals, the new view titles — has a Bulgarian line
 * with the same placeholders. A missing line would put the English key on the screen, which is
 * exactly what these messages exist to stop.
 */
class OcrUserTextTranslationTest {

  private static final Pattern CYRILLIC = Pattern.compile("[\\u0400-\\u04FF]");

  /** The titles and texts that live in the view XML rather than in a Java constant. */
  private static final List<String> VIEW_KEYS =
      List.of(
          "Missing before validation",
          "Needed later",
          "Draft no.",
          "To review",
          "No bank account on this supplier invoice: it will be needed to pay it (field Bank details).",
          "Bank details is required.");

  @Test
  void everyMessageHasABulgarianLineWithTheSamePlaceholders() throws Exception {
    List<String> keys = new ArrayList<>(VIEW_KEYS);
    for (Class<?> type :
        new Class<?>[] {
          OcrReviewMessages.class, SupplierInvoiceButtonCheck.class, OcrDocumentRefusal.class
        }) {
      for (Field field : type.getDeclaredFields()) {
        int m = field.getModifiers();
        if (Modifier.isPublic(m)
            && Modifier.isStatic(m)
            && Modifier.isFinal(m)
            && field.getType() == String.class) {
          keys.add((String) field.get(null));
        }
      }
    }
    assertTrue(keys.size() > 30, "the constants were found: " + keys.size());

    List<String> wrong = new ArrayList<>();
    for (String key : keys) {
      String bg = BgMessages.all().get(key);
      if (bg == null || !CYRILLIC.matcher(bg).find()) {
        wrong.add("no Bulgarian: " + key);
      } else if (count(key) != count(bg)) {
        wrong.add("placeholders differ: " + key);
      }
    }
    assertEquals(List.of(), wrong);
  }

  private static int count(String text) {
    Matcher m = Pattern.compile("%s").matcher(text);
    int n = 0;
    while (m.find()) {
      n++;
    }
    return n;
  }
}
