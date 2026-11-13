package com.axelor.apps.aiaccountant.route;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Reads database references the user typed out of their own message, and nothing else.
 *
 * <h2>The gap this closes</h2>
 *
 * {@link AiScope#NAMED_RECORDS} existed as a value the deterministic adapter never produced,
 * because reading ids out of a sentence was the Router Agent's job. With the router off,
 * unavailable or too slow, «валидирай invoiceId=641» authorised nothing at all: the number was
 * ignored, the scope fell back to whatever was on screen, and a user who had named the record
 * precisely was asked which record they meant. Working without an open form was simply not
 * possible.
 *
 * <h2>Why parsing this is safe when guessing is not</h2>
 *
 * Only unmistakable database-id syntax counts: a field name or an entity name welded to a number —
 * {@code invoiceId=641}, {@code invoiceId: 641}, {@code Invoice#641}. A bare number never matches,
 * because a bare number in an accountant's sentence is far more often an amount, a date, a VAT rate
 * or a document number than a primary key.
 *
 * <p>{@code фактура №641} is deliberately <b>not</b> matched, though it is the most natural
 * Bulgarian phrasing. «№» introduces the printed invoice number — the sequence Axelor assigns at
 * ventilation, or the number the supplier put on their own document — which is a different value
 * from the row id and routinely disagrees with it. Reading one as the other would authorise a
 * confidently-named wrong document, which is worse than authorising nothing.
 *
 * <h2>What this class deliberately does not do</h2>
 *
 * No database access, no company check, no permission check, no authorisation. It converts text
 * into candidate references and stops. Whether any of them names a real record of this company that
 * this user may write to is decided later and elsewhere, by {@code AiWorkflowController}, against
 * the same registry every other scope goes through — and the set it freezes there is the only thing
 * a tool call is ever checked against. A reference the model puts in a tool <em>argument</em> is
 * not an input to this class and can never become one: this reads the user's message, once, before
 * the model runs.
 */
public final class AiExplicitReferenceParser {

  private AiExplicitReferenceParser() {}

  /**
   * A bound on how many references one message may contribute, so a pasted report cannot build an
   * enormous list. Far above any real instruction and far below anything that would matter for
   * memory; the authorisation ceiling that actually governs a job is {@code
   * AiAccountantConfig.maxAuthorisedTargets}, applied later.
   */
  static final int MAX_PARSED = 200;

  /**
   * {@code invoiceId=641}, {@code invoiceId : 641}. The field name, then a separator, then digits.
   */
  private static final Pattern FIELD_FORM =
      Pattern.compile(
          "\\b(invoiceId|uploadId|moveId|bankStatementId|partnerId)\\s*[:=]\\s*(\\d{1,18})\\b",
          Pattern.CASE_INSENSITIVE);

  /** {@code Invoice#641}. The entity name, then a hash, then digits. */
  private static final Pattern HASH_FORM =
      Pattern.compile(
          "\\b(AiOcrUpload|BankStatement|Invoice|Upload|Move|Partner)\\s*#\\s*(\\d{1,18})\\b",
          Pattern.CASE_INSENSITIVE);

  /**
   * Field name to reference kind. {@code uploadId} becomes {@code Upload#} rather than {@code
   * AiOcrUpload#} because that is the form the OCR tools already produce as their target key; both
   * are resolved to the same record by the module that owns them.
   */
  static final Map<String, String> KIND_OF_FIELD =
      Stream.of(
              new String[] {"invoiceid", "Invoice"},
              new String[] {"uploadid", "Upload"},
              new String[] {"moveid", "Move"},
              new String[] {"bankstatementid", "BankStatement"},
              new String[] {"partnerid", "Partner"})
          .collect(Collectors.toMap(pair -> pair[0], pair -> pair[1]));

  /** Lower-cased entity name to its canonical casing, so {@code invoice#641} normalises. */
  static final Map<String, String> KIND_OF_ENTITY =
      Stream.of("AiOcrUpload", "BankStatement", "Invoice", "Upload", "Move", "Partner")
          .collect(Collectors.toMap(kind -> kind.toLowerCase(Locale.ROOT), kind -> kind));

  /**
   * Every distinct reference the message names, in the order they were written.
   *
   * <p>Never null. An empty list means the message named no record, which leaves the scope decision
   * exactly where it was before this class existed.
   */
  public static List<String> parse(String message) {
    List<String> refs = new ArrayList<>();
    if (message == null || message.isEmpty()) {
      return refs;
    }
    Set<String> seen = new LinkedHashSet<>();
    collect(FIELD_FORM.matcher(message), KIND_OF_FIELD, seen);
    collect(HASH_FORM.matcher(message), KIND_OF_ENTITY, seen);
    refs.addAll(seen);
    return refs;
  }

  private static void collect(Matcher matcher, Map<String, String> kinds, Set<String> seen) {
    while (matcher.find() && seen.size() < MAX_PARSED) {
      String kind = kinds.get(matcher.group(1).toLowerCase(Locale.ROOT));
      if (kind == null) {
        continue;
      }
      long id;
      try {
        id = Long.parseLong(matcher.group(2));
      } catch (NumberFormatException e) {
        // Eighteen digits is inside long's range, so this is unreachable in practice; a reference
        // that cannot be a row id is simply not one.
        continue;
      }
      if (id <= 0) {
        continue;
      }
      // Re-rendered from the parsed value rather than the matched text, so `invoiceId=0641` and
      // `Invoice#641` are one reference rather than two.
      seen.add(kind + "#" + id);
    }
  }
}
