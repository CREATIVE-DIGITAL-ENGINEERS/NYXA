package com.axelor.apps.aiocr.service.ocr;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The «дата на данъчното събитие» a supplier document itself states, read from its OCR text.
 *
 * <h2>Why from the text</h2>
 *
 * Azure's invoice model has no field for it — {@code InvoiceDate} and {@code DueDate} are different
 * dates, and so is the day the file was uploaded. The only evidence is the labelled value the
 * document prints, so that is the only thing read: a date standing right after the label. Nothing
 * else is ever substituted for it.
 *
 * <h2>When it answers nothing</h2>
 *
 * <ul>
 *   <li>{@link Status#NOT_STATED} — no tax-event label at all.
 *   <li>{@link Status#CONFLICTING} — the label appears with two different dates (an amended copy,
 *       two invoices in one text).
 *   <li>{@link Status#UNCLEAR} — the label is there but no date can be tied to it: nothing readable
 *       beside it, an impossible date, or a table header row where the label is followed by a row
 *       of dates and the value cannot be told apart from its neighbours' («Дата на издаване | Дата
 *       на данъчно събитие» over «01.10.2026 | 28.09.2026»).
 * </ul>
 *
 * Only {@link Status#STATED} carries a date. Pure: no Guice, no database.
 */
public final class BgOcrTaxEventDate {

  /** How the document's own text answered. */
  public enum Status {
    STATED,
    NOT_STATED,
    CONFLICTING,
    UNCLEAR
  }

  /** The answer: a date only when {@link Status#STATED}. */
  public static final class Reading {

    private final Status status;
    private final LocalDate date;

    private Reading(Status status, LocalDate date) {
      this.status = status;
      this.date = date;
    }

    public Status status() {
      return status;
    }

    public LocalDate date() {
      return date;
    }

    /** A review note for an answer the accountant has to settle, or null when there is none. */
    public String reviewNote() {
      switch (status) {
        case CONFLICTING:
          return "The document states more than one «дата на данъчното събитие»; none was filled"
              + " in. Enter the correct one by hand.";
        case UNCLEAR:
          return "The document mentions «дата на данъчното събитие» but no date could be tied to"
              + " it with certainty; none was filled in. Check the document and enter it by hand.";
        default:
          return null;
      }
    }
  }

  /**
   * A label that names the date itself. «данъчно(то) събитие» on its own also appears in legal
   * boilerplate («при възникване на данъчно събитие по чл. 25»), so the bare form counts only when
   * a date stands right after it; the forms here are labels whatever follows them.
   */
  private static final Pattern DATE_LABEL =
      Pattern.compile(
          "(?:дата\\s+на\\s+(?:възникване\\s+на\\s+)?дан(?:ъчно(?:то)?|\\.)\\s*събитие"
              + "|date\\s+of\\s+(?:the\\s+)?tax(?:able)?\\s+event"
              + "|tax(?:able)?\\s+event\\s+date"
              + "|tax\\s+point(?:\\s+date)?)",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  private static final Pattern BARE_LABEL =
      Pattern.compile(
          "дан(?:ъчно(?:то)?|\\.)\\s*събитие", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /** Separators allowed between a label and its value: spaces, a colon, a dash, a table bar. */
  private static final String GAP = "[\\s:|\\-–—]*(?:\\(?\\s*(?:дата|date)\\s*\\)?[\\s:|\\-–—]*)?";

  /** dd.mm.yyyy, dd/mm/yy, dd-mm-yyyy (optionally «г.»), or ISO yyyy-mm-dd. */
  private static final String DATE =
      "(?:(\\d{1,2})[./\\-](\\d{1,2})[./\\-](\\d{4}|\\d{2})(?!\\d)(?:\\s*г\\.?)?"
          + "|(\\d{4})-(\\d{1,2})-(\\d{1,2})(?!\\d))";

  private static final Pattern VALUE = Pattern.compile("\\A" + GAP + DATE);

  /** A second date right after the first: the label heads a row of values, not this one. */
  private static final Pattern NEXT_DATE = Pattern.compile("\\A[\\s|]*" + DATE);

  private BgOcrTaxEventDate() {}

  /** Reads the tax-event date the text states, never anything in its place. */
  public static Reading read(String text) {
    if (text == null || text.trim().isEmpty()) {
      return new Reading(Status.NOT_STATED, null);
    }
    Set<LocalDate> stated = new LinkedHashSet<>();
    boolean unclear = false;
    boolean labelled = false;
    Matcher label = BARE_LABEL.matcher(text);
    while (label.find()) {
      int start = labelStart(text, label.start());
      Matcher explicit = DATE_LABEL.matcher(text).region(start, text.length());
      boolean isDateLabel = explicit.lookingAt();
      int end = isDateLabel ? Math.max(explicit.end(), label.end()) : label.end();
      Value value = valueAfter(text, end);
      if (value == null) {
        if (isDateLabel) {
          labelled = true;
          unclear = true;
        }
        continue;
      }
      labelled = true;
      if (value.date == null || value.rowOfDates) {
        unclear = true;
      } else {
        stated.add(value.date);
      }
    }
    Matcher english = DATE_LABEL.matcher(text);
    while (english.find()) {
      if (BARE_LABEL.matcher(english.group()).find()) {
        continue; // the Bulgarian forms were read above
      }
      labelled = true;
      Value value = valueAfter(text, english.end());
      if (value == null || value.date == null || value.rowOfDates) {
        unclear = true;
      } else {
        stated.add(value.date);
      }
    }
    if (stated.size() > 1) {
      return new Reading(Status.CONFLICTING, null);
    }
    if (unclear) {
      return new Reading(Status.UNCLEAR, null);
    }
    if (stated.size() == 1) {
      return new Reading(Status.STATED, stated.iterator().next());
    }
    return new Reading(labelled ? Status.UNCLEAR : Status.NOT_STATED, null);
  }

  /** Where the label starts once its «дата на (възникване на)» prefix is included, if present. */
  private static int labelStart(String text, int bare) {
    int from = Math.max(0, bare - 40);
    Matcher m = DATE_LABEL.matcher(text).region(from, text.length());
    while (m.find()) {
      if (m.start() > bare) {
        break;
      }
      if (m.end() > bare) {
        return m.start();
      }
    }
    return bare;
  }

  private static final class Value {
    final LocalDate date;
    final boolean rowOfDates;

    Value(LocalDate date, boolean rowOfDates) {
      this.date = date;
      this.rowOfDates = rowOfDates;
    }
  }

  /** The date standing right after a label, or null when none does. */
  private static Value valueAfter(String text, int end) {
    Matcher m = VALUE.matcher(text).region(end, text.length());
    if (!m.lookingAt()) {
      return null;
    }
    LocalDate date = toDate(m);
    boolean row = NEXT_DATE.matcher(text).region(m.end(), text.length()).lookingAt();
    return new Value(date, row);
  }

  /** The matched date, or null when it is not a real calendar day. */
  private static LocalDate toDate(Matcher m) {
    try {
      if (m.group(1) != null) {
        int year = Integer.parseInt(m.group(3));
        if (m.group(3).length() == 2) {
          year += 2000;
        }
        return LocalDate.of(year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(1)));
      }
      return LocalDate.of(
          Integer.parseInt(m.group(4)), Integer.parseInt(m.group(5)), Integer.parseInt(m.group(6)));
    } catch (DateTimeException | NumberFormatException e) {
      return null;
    }
  }
}
