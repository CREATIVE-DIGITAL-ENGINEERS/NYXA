package com.axelor.apps.aiocr.service.ai.tool;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccountant.tool.AiCapability;
import com.axelor.apps.aiaccountant.tool.AiTool;
import com.axelor.apps.aiaccountant.tool.AiToolArgs;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiaccountant.tool.AiWriteMode;
import com.axelor.apps.aiocr.service.readiness.OcrTotalAdjustment;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.aiocr.service.record.OcrDocumentTotalCorrectionService;
import com.axelor.apps.aiocr.service.record.OcrDraftInvoiceGuard;
import com.axelor.apps.aiocr.service.record.OcrEvidenceService;
import com.axelor.inject.Beans;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * WRITE tool: correct a document total OCR read wrongly, without touching the invoice.
 *
 * <h2>Why a tool of its own, and not an operation of {@code edit_supplier_invoice}</h2>
 *
 * The two look adjacent — both make a draft validate — and they are opposites. {@code
 * edit_supplier_invoice} changes what the ERP says the invoice is; this changes what the system
 * believes the <em>document</em> said, and the whole value of the document figure is that it is the
 * one number the lines are checked against. Folding it into the edit tool would put both sides of
 * that check behind one call and one approval, so a single request could move the lines and move
 * the figure they are measured by — which is precisely the "edit the invoice until it adds up"
 * failure the header totals exist to prevent. Kept separate, "this tool never touches a line" is a
 * property of the code rather than a rule someone has to keep remembering.
 *
 * <p>It also addresses a different kind of wrongness. An edit says the ERP got the invoice wrong; a
 * correction here says <b>Azure misread the page</b>, which needs its own evidence (the printed
 * figure), its own audit (who read it, where, when) and its own preservation rule (the extraction
 * survives untouched). None of that has an equivalent on the edit path.
 *
 * <h2>What it will not do</h2>
 *
 * <ul>
 *   <li><b>It never derives the total from the invoice.</b> A figure that merely equals what the
 *       lines calculate, and is not printed anywhere on the document, is refused by name — that is
 *       not a correction, it is making the check agree with the thing it checks.
 *   <li><b>It never chooses between printed amounts.</b> A fuel receipt prints several totals; the
 *       caller names the one it read and this verifies that the document contains it. Picking one
 *       on the caller's behalf is the mistake that produced the wrong total in the first place.
 *   <li><b>It never validates or posts.</b> Correcting the evidence and committing the document are
 *       two different instructions from the user.
 * </ul>
 */
@Singleton
public class CorrectOcrDocumentTotalTool implements AiTool {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The figure was read off the scanned document; this tool checks that claim. */
  static final String SOURCE_DOCUMENT = "document";

  /** The user stated the figure themselves, knowing what the document and the lines say. */
  static final String SOURCE_USER = "user";

  @Override
  public String name() {
    return "correct_ocr_document_total";
  }

  @Override
  public String description() {
    return "Corrects the GRAND TOTAL that OCR read off a scanned supplier document, when the "
        + "extraction picked up a subtotal or a stray amount instead. Use it when validation or "
        + "amountsReconcile refuses because the document total disagrees with the lines AND the "
        + "lines are right — read the real printed total with get_ocr_evidence find=<the figure> "
        + "first. This writes ONLY the OCR evidence: the original extracted amounts are preserved "
        + "untouched, no invoice line, price, tax or header total is changed, and nothing is "
        + "validated or posted. Give the gross exactly as printed, and the net/VAT pair only when "
        + "the document prints those too. The corrected figure MUST appear in the document text, "
        + "which this verifies; a total equal to what the invoice's own lines calculate but absent "
        + "from the document is refused, because that is adjusting the check rather than correcting "
        + "it. The result returns the original amount, the corrected amount, who recorded it, the "
        + "matching document lines and whether the remaining difference is now inside the automatic "
        + "rounding allowance. When the user has authorised this correction, call it and report the "
        + "outcome — do not ask for a further confirmation and never offer a numbered choice.";
  }

  @Override
  public JsonNode parametersSchema() {
    ObjectNode schema = MAPPER.createObjectNode();
    schema.put("type", "object");
    ObjectNode props = schema.putObject("properties");

    SupplierInvoiceTarget.describeIds(props, "corrected");
    props
        .putObject("correctedTotal")
        .put("type", "number")
        .put(
            "description",
            "The grand total as the document prints it, e.g. the figure under 'ОБЩА СУМА' /"
                + " 'ВСИЧКО ЗА ПЛАЩАНЕ'. Never the total the invoice's own lines add up to.");
    props
        .putObject("correctedNet")
        .put("type", "number")
        .put(
            "description",
            "The printed net, only when the document prints a net/VAT breakdown. Give it together"
                + " with correctedVat or leave both out; the two must add up to correctedTotal.");
    props
        .putObject("correctedVat")
        .put("type", "number")
        .put("description", "The printed VAT. Goes with correctedNet; see above.");
    props
        .putObject("evidence")
        .put("type", "string")
        .put(
            "description",
            "Where the figure came from: the printed label you read it under, or the user's own"
                + " instruction when they stated it. Stored on the invoice as the audit note.");
    ObjectNode source = props.putObject("source");
    source.put("type", "string");
    source.putArray("enum").add(SOURCE_DOCUMENT).add(SOURCE_USER);
    source.put(
        "description",
        "document (default) = you read the figure on the page; the tool confirms the document text"
            + " contains it. user = the user stated it explicitly after being shown what the"
            + " document and the lines say; the tool records that instead of searching. Do not use"
            + " 'user' to get past a figure the document does not show.");

    schema.putArray("required").add("correctedTotal").add("evidence");
    SupplierInvoiceTarget.declareExactlyOneId(schema);
    schema.put("additionalProperties", false);
    return schema;
  }

  @Override
  public AiCapability capability() {
    return AiCapability.SUPPLIER_INVOICE;
  }

  @Override
  public AiToolKind kind() {
    return AiToolKind.WRITE;
  }

  /** Chat only. It changes the figure an invoice is posted against; a person has to be there. */
  @Override
  public Set<AiWriteMode> writeModes() {
    return EnumSet.of(AiWriteMode.CONFIRMED);
  }

  /** The same rank as {@code edit_supplier_invoice}: a draft correction, before any validation. */
  @Override
  public int writeOrder() {
    return 20;
  }

  @Override
  public String targetKey(JsonNode args) {
    SupplierInvoiceTarget target = SupplierInvoiceTarget.ofOrNull(args);
    return target == null ? null : target.targetKey();
  }

  @Override
  public Object execute(JsonNode args) throws AiToolException {
    SupplierInvoiceTarget target = resolveTarget(args);
    if (!target.isOcr()) {
      // Not a vague "cannot do that": the caller named a real invoice of this company that simply
      // has no scanned page behind it, so there is no extraction to correct and no evidence to
      // read a figure from. Saying which tool owns that record's amounts is the useful half.
      throw new OcrBlocked(
              AiBlockerCode.NOT_AN_OCR_DRAFT,
              "Invoice "
                  + target.invoiceId()
                  + " has no scanned document behind it, so there is no OCR document total to"
                  + " correct. Its amounts come from its own lines — change those with"
                  + " edit_supplier_invoice if they are wrong.")
          .asToolException();
    }
    long uploadId = target.uploadId();

    String evidence = AiToolArgs.optText(args, "evidence");
    if (evidence == null || evidence.trim().isEmpty()) {
      throw badArguments(
          "Say where the corrected total came from in 'evidence' — the printed label you read it"
              + " under, or the user's own instruction. It is stored as the audit note and a"
              + " correction without one cannot be reviewed later.");
    }
    String source = AiToolArgs.optText(args, "source");
    source = source == null || source.trim().isEmpty() ? SOURCE_DOCUMENT : source.trim();
    if (!SOURCE_DOCUMENT.equals(source) && !SOURCE_USER.equals(source)) {
      throw badArguments("source must be '" + SOURCE_DOCUMENT + "' or '" + SOURCE_USER + "'.");
    }

    // Authorisation BEFORE anything the arguments say is believed: company scope, OCR provenance,
    // DRAFT status and Axelor's own write permission, decided server-side from the id alone. The
    // amounts in the call grant nothing — they are only ever data to be checked.
    Invoice invoice;
    try {
      invoice = authorise(uploadId);
    } catch (OcrBlocked e) {
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }

    BigDecimal gross = scale(AiToolArgs.optDecimal(args, "correctedTotal"));
    BigDecimal net = scale(AiToolArgs.optDecimal(args, "correctedNet"));
    BigDecimal vat = scale(AiToolArgs.optDecimal(args, "correctedVat"));
    // The amount rules first, so a half-breakdown or an impossible VAT rate is refused before the
    // document is searched and before anything is written.
    try {
      OcrDocumentTotalCorrectionService.checkAmounts(gross, net, vat);
    } catch (OcrBlocked e) {
      throw e.asToolException();
    }

    BigDecimal calculated = nz(invoice.getInTaxTotal());
    List<String> matches;
    try {
      matches = verify(uploadId, gross, calculated, source);
    } catch (OcrBlocked e) {
      throw e.asToolException();
    }

    OcrDocumentTotalCorrectionService.Result result;
    try {
      result = apply(invoice, gross, net, vat, evidence.trim());
    } catch (OcrBlocked e) {
      throw e.asToolException();
    } catch (IllegalArgumentException | IllegalStateException e) {
      throw new AiToolException(e.getMessage());
    }

    return report(uploadId, invoice, result, source, matches, calculated);
  }

  /**
   * Checks the claim the arguments make about the document, and refuses precisely when it fails.
   *
   * <p>Two refusals, not one, because they are two different mistakes and the repair differs. A
   * figure that equals what the lines calculate is refused <em>whatever</em> the stated source,
   * unless the document text actually shows it: that is the one correction this feature exists to
   * prevent, and letting {@code source=user} buy it back would leave no rule at all. Anything else
   * the document does not contain is refused only on the document path, where the claim was that it
   * was read off the page.
   *
   * @return the matching document lines, empty when none were searched for
   */
  protected List<String> verify(
      long uploadId, BigDecimal gross, BigDecimal calculated, String source) {
    List<String> matches = new ArrayList<>();
    List<String> found = null;
    for (String form : printedForms(gross)) {
      List<String> hits = quoteDocument(uploadId, form);
      if (hits == null) {
        found = null;
        break; // no stored extraction: nothing was searched, so nothing is confirmed either
      }
      found = found == null ? new ArrayList<>() : found;
      for (String hit : hits) {
        if (!matches.contains(hit)) {
          matches.add(hit);
        }
      }
    }
    boolean searched = found != null;
    boolean onDocument = !matches.isEmpty();

    if (!onDocument && gross.compareTo(calculated) == 0) {
      throw new OcrBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          "Refusing to set the document total to "
              + gross
              + ": that is exactly what this invoice's own lines calculate, and it is not printed"
              + " on the document. The document total is the independent check on the lines —"
              + " setting it to the lines' own answer makes that check agree with itself. Read the"
              + " real printed grand total with get_ocr_evidence (a receipt often prints a subtotal"
              + " above it), or report the discrepancy and leave the figure alone.");
    }
    if (SOURCE_DOCUMENT.equals(source) && !onDocument) {
      throw new OcrBlocked(
          AiBlockerCode.BAD_ARGUMENTS,
          searched
              ? "The document text for OCR record "
                  + uploadId
                  + " does not contain "
                  + gross
                  + ". Find the printed grand total with get_ocr_evidence find=<figure> and send"
                  + " that, or — only if the user told you this figure knowing what the document"
                  + " shows — call again with source=user and say so in 'evidence'."
              : "No OCR extraction is stored for record "
                  + uploadId
                  + ", so "
                  + gross
                  + " cannot be checked against the document. If the user is telling you this"
                  + " figure, call again with source=user and record where it came from.");
    }
    return matches;
  }

  /** The written forms of one amount a Bulgarian document may print it in. */
  static List<String> printedForms(BigDecimal gross) {
    Set<String> forms = new LinkedHashSet<>();
    String plain = gross.toPlainString();
    forms.add(plain);
    forms.add(plain.replace('.', ','));
    if (gross.stripTrailingZeros().scale() <= 0) {
      // 100.00 is printed "100" at least as often as "100.00".
      forms.add(gross.stripTrailingZeros().toPlainString());
    }
    return new ArrayList<>(forms);
  }

  /** The answer the caller reports to the user: before, after, who, evidence and what follows. */
  protected Map<String, Object> report(
      long uploadId,
      Invoice invoice,
      OcrDocumentTotalCorrectionService.Result result,
      String source,
      List<String> matches,
      BigDecimal calculated) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("uploadId", uploadId);
    out.put("invoiceId", invoice.getId());

    Map<String, Object> original = new LinkedHashMap<>();
    original.put("documentTotal", result.getPreviousExtractedTotal());
    original.put("documentNet", result.getPreviousExtractedNet());
    original.put("documentVat", result.getPreviousExtractedVat());
    original.put(
        "preserved",
        "The extracted figures above are unchanged and kept as evidence of what OCR read.");
    out.put("originalExtraction", original);

    Map<String, Object> corrected = new LinkedHashMap<>();
    corrected.put("documentTotal", result.getCorrectedTotal());
    corrected.put("documentNet", result.getCorrectedNet());
    corrected.put("documentVat", result.getCorrectedVat());
    corrected.put("breakdownSupplied", result.isBreakdownSupplied());
    out.put("corrected", corrected);

    Map<String, Object> audit = new LinkedHashMap<>();
    audit.put("correctedBy", result.getCorrectedBy());
    audit.put(
        "correctedOn", result.getCorrectedOn() == null ? null : result.getCorrectedOn().toString());
    audit.put("reason", result.getReason());
    audit.put("source", source);
    audit.put("documentLines", matches);
    out.put("audit", audit);

    // The same comparison the validation gate itself will make, from the same class, so the words
    // here and the words validation uses cannot drift apart.
    OcrTotalAdjustment check = OcrTotalAdjustment.of(result.getCorrectedTotal(), calculated);
    Map<String, Object> verification = new LinkedHashMap<>();
    verification.put("correctedDocumentTotal", result.getCorrectedTotal());
    verification.put("calculatedFromLines", calculated);
    verification.put("difference", check.getDifference());
    verification.put("verdict", check.getVerdict().name());
    verification.put(
        "meaning",
        check.isTooLarge()
            ? "Still too far apart for the automatic rounding correction. Validation will refuse:"
                + " either the corrected total is not the one printed, or the lines are wrong."
            : check.isAdjustable()
                ? "Within the automatic rounding allowance: validation books the difference to the"
                    + " rounding account by itself."
                : "The corrected total and the lines agree exactly.");
    out.put("verification", verification);

    out.put("invoiceLinesChanged", false);
    out.put("headerTotalsChanged", false);
    out.put(
        "nextStep",
        "The OCR evidence is corrected and nothing else was touched. Validating or posting this"
            + " invoice is a separate step — do it only if the user asked for it.");
    return out;
  }

  // ── seams ────────────────────────────────────────────────────────────────────────────────────
  // Each one is the single call into a collaborator that needs a database, an injector or a logged
  // -in user, so the decisions above (authorisation relay, document verification, refusal wording,
  // the reported shape) can be exercised without any of the three.

  /** Which record this call names, and therefore whether it has a document at all. */
  protected SupplierInvoiceTarget resolveTarget(JsonNode args) {
    return SupplierInvoiceTarget.of(args);
  }

  /** The DRAFT OCR invoice behind the upload, or a refusal. Server-side, from the id alone. */
  protected Invoice authorise(long uploadId) {
    return OcrDraftInvoiceGuard.resolve(uploadId).getInvoice();
  }

  /** Document lines containing the text; empty when absent, null when there is nothing to read. */
  protected List<String> quoteDocument(long uploadId, String needle) {
    try {
      return Beans.get(OcrEvidenceService.class).quote(uploadId, needle);
    } catch (OcrEvidenceService.EvidenceUnavailableException e) {
      // The guard above already proved this company may read this record, so reaching here means
      // the evidence store itself is unavailable — not an authorisation answer to relay.
      return null;
    }
  }

  /** Writes the correction. Re-authorises by invoice id inside the service; that is deliberate. */
  protected OcrDocumentTotalCorrectionService.Result apply(
      Invoice invoice, BigDecimal gross, BigDecimal net, BigDecimal vat, String reason) {
    return Beans.get(OcrDocumentTotalCorrectionService.class)
        .correct(invoice.getId(), gross, net, vat, reason);
  }

  private static AiToolException badArguments(String message) {
    return new OcrBlocked(AiBlockerCode.BAD_ARGUMENTS, message).asToolException();
  }

  private static BigDecimal scale(BigDecimal v) {
    return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
