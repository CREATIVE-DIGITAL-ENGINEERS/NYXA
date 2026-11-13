package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The supplier-draft half: the stated date reaches the invoice through the field's owning module,
 * and nothing is written — only a note — when the document's answer is not certain. The invoice and
 * due dates are set on the result to prove they are never used in its place.
 */
class BgOcrTaxEventDateIntakeTest {

  private final List<LocalDate> written = new ArrayList<>();

  private final BgOcrIntakeService intake =
      new BgOcrIntakeService(null, null, null, null, null) {
        @Override
        protected boolean applyTaxEventDateField(Invoice draft, LocalDate date) {
          written.add(date);
          return true;
        }
      };

  private static OcrExtractResult result(String text) {
    OcrExtractResult result = new OcrExtractResult();
    result.setInvoiceDate(LocalDate.of(2026, 10, 1));
    result.setDueDate(LocalDate.of(2026, 10, 15));
    result.setRawText(text);
    return result;
  }

  @Test
  void aStatedDateIsWrittenToTheDraftWithNoNote() {
    String note =
        intake.applyStatedTaxEventDate(
            new Invoice(),
            result("Дата на издаване: 01.10.2026\nДата на данъчното събитие: 28.09.2026"));

    assertNull(note);
    assertEquals(List.of(LocalDate.of(2026, 9, 28)), written);
  }

  @Test
  void noStatedDateWritesNothingAndNeedsNoNote() {
    assertNull(intake.applyStatedTaxEventDate(new Invoice(), result("Дата: 01.10.2026")));
    assertTrue(written.isEmpty());
  }

  @Test
  void aConflictingOrUnclearDateWritesNothingAndLeavesANote() {
    assertNotNull(
        intake.applyStatedTaxEventDate(
            new Invoice(),
            result(
                "Дата на данъчното събитие: 28.09.2026\nДата на данъчното събитие: 30.09.2026")));
    assertNotNull(
        intake.applyStatedTaxEventDate(
            new Invoice(),
            result("Дата на издаване Дата на данъчно събитие\n01.10.2026 28.09.2026")));
    assertTrue(written.isEmpty());
  }
}
