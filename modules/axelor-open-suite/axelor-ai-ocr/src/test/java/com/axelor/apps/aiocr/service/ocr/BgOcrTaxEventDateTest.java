package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.aiocr.service.ocr.BgOcrTaxEventDate.Reading;
import com.axelor.apps.aiocr.service.ocr.BgOcrTaxEventDate.Status;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class BgOcrTaxEventDateTest {

  private static final LocalDate SEPT_28 = LocalDate.of(2026, 9, 28);

  private static void assertStated(String text) {
    Reading reading = BgOcrTaxEventDate.read(text);
    assertEquals(Status.STATED, reading.status(), text);
    assertEquals(SEPT_28, reading.date(), text);
    assertNull(reading.reviewNote());
  }

  private static void assertNoDate(Status expected, String text) {
    Reading reading = BgOcrTaxEventDate.read(text);
    assertEquals(expected, reading.status(), text);
    assertNull(reading.date(), text);
  }

  @Test
  void aClearlyStatedDateIsReadInTheUsualPrintedForms() {
    assertStated(
        "ФАКТУРА № 0000012345\nДата на издаване: 01.10.2026\n"
            + "Дата на данъчното събитие: 28.09.2026\nМясто на сделката: София");
    assertStated("Дата на данъчно събитие 28.09.2026 г.");
    assertStated("Дата на дан. събитие: 28/09/26");
    assertStated("Дата на данъчното събитие:\n28.09.2026\nПолучател: Алфа ООД");
    assertStated("Данъчно събитие: 2026-09-28");
    assertStated("Tax event date: 28.09.2026");
    // Original and copy print the same date twice: one date, not a conflict.
    assertStated(
        "ОРИГИНАЛ Дата на данъчното събитие: 28.09.2026 ... КОПИЕ Дата на данъчното събитие:"
            + " 28.09.2026");
  }

  /** Every other date on the page is left alone: none of them is the tax-event date. */
  @Test
  void noStatedDateLeavesItUnsetWhateverOtherDatesThePageCarries() {
    assertNoDate(
        Status.NOT_STATED,
        "ФАКТУРА № 1\nДата на издаване: 01.10.2026\nПадеж: 15.10.2026\nКачено: 23.10.2026");
    // Legal boilerplate mentions the term without stating a date.
    assertNoDate(
        Status.NOT_STATED,
        "Дата: 01.10.2026\nДДС се начислява при възникване на данъчно събитие по чл. 25 ЗДДС.");
    assertNoDate(Status.NOT_STATED, null);
    assertNull(BgOcrTaxEventDate.read("Дата: 01.10.2026").reviewNote());
  }

  @Test
  void twoDifferentStatedDatesAreAConflictAndNeitherIsChosen() {
    Reading reading =
        BgOcrTaxEventDate.read(
            "Дата на данъчното събитие: 28.09.2026\n...\nДата на данъчното събитие: 30.09.2026");

    assertEquals(Status.CONFLICTING, reading.status());
    assertNull(reading.date());
    assertNotNull(reading.reviewNote());
  }

  @Test
  void aLabelWhoseValueCannotBeToldApartIsUnclearAndNeverGuessed() {
    // Table header row: the first date after the label is the issue date's, not this one's.
    assertNoDate(
        Status.UNCLEAR,
        "Дата на издаване   Дата на данъчно събитие\n01.10.2026   28.09.2026\nДоставчик: Алфа");
    // Label with nothing readable beside it.
    assertNoDate(Status.UNCLEAR, "Дата на данъчното събитие: виж приложението\nДата: 01.10.2026");
    // Not a real calendar day.
    assertNoDate(Status.UNCLEAR, "Дата на данъчното събитие: 31.02.2026");
    assertNotNull(BgOcrTaxEventDate.read("Дата на данъчното събитие: 31.02.2026").reviewNote());
  }
}
