package com.axelor.apps.napsubmissions.services.declarations.common;

import com.axelor.apps.napsubmissions.db.repo.NapProcessingResultRepository;
import com.axelor.i18n.I18n;
import com.google.inject.Singleton;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads an НАП Declarations result body (the official HTML) into per-file and overall outcomes.
 * Pure: it parses and returns, it never touches an entity, a repository or the network.
 *
 * <p>Everything it recognises comes from the checked-in official samples {@code the official NAP
 * API specification declarations/2026-03-24/.../DEC_D1_D6 API_MockResultData.txt}:
 *
 * <ul>
 *   <li>the single-declaration acceptance sentence «Подадената от Вас Декларация образец … е
 *       приета»;
 *   <li>the per-form counters «Брой подадени / приети / отхвърлени Декларации обр.1 | обр.6»;
 *   <li>the per-file refusal «Декларация обр.N Файлът е отхвърлен …»;
 *   <li>the submission-wide refusal «Декларациите се отхвърлят …».
 * </ul>
 *
 * <p><b>Conservative by construction.</b> HTTP 200 proves nothing; only these sentences do.
 * ACCEPTED requires every submitted file to be accepted, any refusal makes the whole submission
 * REJECTED, a submitted file with no result at all keeps it PENDING, and a body in which nothing is
 * recognised is UNKNOWN (needs review) — never accepted.
 */
@Singleton
public class DeclarationsResultParser {

  private static final Pattern TAGS = Pattern.compile("<[^>]*>");
  private static final Pattern COMMENTS = Pattern.compile("(?s)<!--.*?-->");
  private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*\"?([a-z0-9-]+)");

  /** «Подадената от Вас Декларация образец ХХХ е приета.» — the whole submission was accepted. */
  private static final Pattern WHOLE_ACCEPTED =
      Pattern.compile("Подадената\\s+от\\s+Вас\\s+Декларация[^.]{0,80}?е\\s+приета");

  /** «Декларациите се отхвърлят …» — the whole submission was refused. */
  private static final Pattern WHOLE_REJECTED = Pattern.compile("Декларациите\\s+се\\s+отхвърлят");

  /** «Декларация обр.1 Файлът е отхвърлен …» — that one file was refused outright. */
  private static final Pattern FILE_REJECTED =
      Pattern.compile("Декларация\\s+обр\\.\\s*([16])\\s*:?\\s*Файлът\\s+е\\s+отхвърлен");

  private static final int MAX_SUMMARY = 400;

  /**
   * Parse {@code rawBytes} knowing which file document types were actually submitted (so a missing
   * result for one of them is reported as still processing rather than silently ignored).
   */
  public ParsedDeclarationsResult parse(byte[] rawBytes, Set<FileDocumentType> submittedTypes) {
    String text = toPlainText(rawBytes);
    Map<FileDocumentType, String> outcomes = new EnumMap<>(FileDocumentType.class);

    if (text.isEmpty()) {
      return new ParsedDeclarationsResult(
          outcomes,
          NapProcessingResultRepository.OUTCOME_UNKNOWN,
          I18n.get("Empty response from NAP — the original needs to be reviewed."),
          true);
    }

    boolean wholeAccepted = WHOLE_ACCEPTED.matcher(text).find();
    boolean wholeRejected = WHOLE_REJECTED.matcher(text).find();

    readCounters(text, FileDocumentType.DEC_1, 1, outcomes);
    readCounters(text, FileDocumentType.DEC_6, 6, outcomes);
    readFileRefusals(text, outcomes);

    boolean recognised = wholeAccepted || wholeRejected || !outcomes.isEmpty();
    if (!recognised) {
      return new ParsedDeclarationsResult(
          outcomes,
          NapProcessingResultRepository.OUTCOME_UNKNOWN,
          summarize(
              I18n.get(
                      "The NAP response cannot be read automatically — the original needs to be"
                          + " reviewed.")
                  + " ",
              text),
          true);
    }

    // A whole-submission acceptance with no counters at all still states an outcome per file.
    if (wholeAccepted && !wholeRejected && outcomes.isEmpty() && submittedTypes != null) {
      for (FileDocumentType type : submittedTypes) {
        outcomes.put(type, NapProcessingResultRepository.OUTCOME_ACCEPTED);
      }
    }

    String overall = deriveOverall(wholeAccepted, wholeRejected, outcomes, submittedTypes);
    return new ParsedDeclarationsResult(outcomes, overall, summarize("", text), false);
  }

  // --- derivation -------------------------------------------------------------------------------

  protected String deriveOverall(
      boolean wholeAccepted,
      boolean wholeRejected,
      Map<FileDocumentType, String> outcomes,
      Set<FileDocumentType> submittedTypes) {
    // Any refusal — of the submission or of a single required file — rejects the whole thing.
    if (wholeRejected) {
      return NapProcessingResultRepository.OUTCOME_REJECTED;
    }
    for (String outcome : outcomes.values()) {
      if (NapProcessingResultRepository.OUTCOME_REJECTED.equals(outcome)
          || NapProcessingResultRepository.OUTCOME_PARTIALLY_ACCEPTED.equals(outcome)) {
        return NapProcessingResultRepository.OUTCOME_REJECTED;
      }
    }
    // A submitted file НАП said nothing about is still being processed.
    if (submittedTypes != null) {
      for (FileDocumentType type : submittedTypes) {
        if (!outcomes.containsKey(type)) {
          return NapProcessingResultRepository.OUTCOME_PENDING;
        }
      }
    }
    boolean allAccepted = !outcomes.isEmpty();
    for (String outcome : outcomes.values()) {
      allAccepted &= NapProcessingResultRepository.OUTCOME_ACCEPTED.equals(outcome);
    }
    if (allAccepted || (wholeAccepted && outcomes.isEmpty())) {
      return NapProcessingResultRepository.OUTCOME_ACCEPTED;
    }
    return NapProcessingResultRepository.OUTCOME_UNKNOWN;
  }

  // --- recognisers ------------------------------------------------------------------------------

  /** «Брой подадени / приети / отхвърлени Декларации обр.N: x» → an outcome for that form. */
  protected void readCounters(
      String text, FileDocumentType type, int form, Map<FileDocumentType, String> outcomes) {
    Integer accepted = count(text, "приети", form);
    Integer rejected = count(text, "отхвърлени", form);
    if (accepted == null && rejected == null) {
      return;
    }
    int ok = accepted == null ? 0 : accepted;
    int ko = rejected == null ? 0 : rejected;
    if (ko > 0 && ok > 0) {
      outcomes.put(type, NapProcessingResultRepository.OUTCOME_PARTIALLY_ACCEPTED);
    } else if (ko > 0) {
      outcomes.put(type, NapProcessingResultRepository.OUTCOME_REJECTED);
    } else if (ok > 0) {
      outcomes.put(type, NapProcessingResultRepository.OUTCOME_ACCEPTED);
    } else {
      outcomes.put(type, NapProcessingResultRepository.OUTCOME_UNKNOWN);
    }
  }

  protected Integer count(String text, String word, int form) {
    Matcher m =
        Pattern.compile(
                "Брой\\s+" + word + "\\s+Декларации\\s+обр\\.\\s*" + form + "\\s*:\\s*(\\d+)")
            .matcher(text);
    return m.find() ? Integer.valueOf(m.group(1)) : null;
  }

  /** An outright per-file refusal overrides whatever the counters said for that form. */
  protected void readFileRefusals(String text, Map<FileDocumentType, String> outcomes) {
    Matcher m = FILE_REJECTED.matcher(text);
    while (m.find()) {
      FileDocumentType type =
          "6".equals(m.group(1)) ? FileDocumentType.DEC_6 : FileDocumentType.DEC_1;
      outcomes.put(type, NapProcessingResultRepository.OUTCOME_REJECTED);
    }
  }

  // --- text handling ----------------------------------------------------------------------------

  /** Strip comments and markup and collapse whitespace; the raw bytes stay untouched elsewhere. */
  protected String toPlainText(byte[] rawBytes) {
    if (rawBytes == null || rawBytes.length == 0) {
      return "";
    }
    String html = decode(rawBytes);
    String text = COMMENTS.matcher(html).replaceAll(" ");
    text = TAGS.matcher(text).replaceAll(" ");
    return text.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
  }

  /**
   * Decode with the charset the document declares; fall back to UTF-8 and, if that yields
   * replacement characters, to windows-1251 (the usual Bulgarian legacy encoding).
   */
  protected String decode(byte[] rawBytes) {
    String ascii =
        new String(rawBytes, 0, Math.min(rawBytes.length, 2048), StandardCharsets.ISO_8859_1);
    Matcher m = CHARSET.matcher(ascii);
    if (m.find()) {
      try {
        return new String(rawBytes, Charset.forName(m.group(1)));
      } catch (RuntimeException ignore) {
        // unknown/unsupported declared charset: fall through to the heuristics below
      }
    }
    String utf8 = new String(rawBytes, StandardCharsets.UTF_8);
    if (utf8.indexOf('\ufffd') < 0) {
      return utf8;
    }
    try {
      return new String(rawBytes, Charset.forName("windows-1251"));
    } catch (RuntimeException e) {
      return utf8;
    }
  }

  protected String summarize(String prefix, String text) {
    String body = prefix + text;
    return body.length() > MAX_SUMMARY ? body.substring(0, MAX_SUMMARY - 1) + "…" : body;
  }
}
