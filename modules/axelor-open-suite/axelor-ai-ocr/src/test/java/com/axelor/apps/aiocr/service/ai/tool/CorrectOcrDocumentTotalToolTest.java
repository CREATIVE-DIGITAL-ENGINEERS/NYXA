package com.axelor.apps.aiocr.service.ai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import com.axelor.apps.aiaccountant.tool.AiToolKind;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.aiocr.service.record.OcrDocumentTotalCorrectionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code correct_ocr_document_total} — the decisions the tool itself makes, with no injector, no
 * database and no logged-in user.
 *
 * <h2>The record behind the numbers</h2>
 *
 * A fuel receipt whose text prints {@code ОБЩА СУМА ЕВРО … 86.73} with {@code ВСИЧКО 80.75} (the
 * fuel subtotal) above it. Azure stored the subtotal as the grand total, so {@code
 * ocrDocumentTotal} is 80.75 while the invoice's own lines calculate 72.27 + 14.45 = 86.72. Both
 * figures below are therefore real: 86.73 is printed and 86.72 is not.
 *
 * <p>The four collaborators that need a runtime — the id-to-record resolution, the authorisation
 * guard, the document search and the write — are the tool's protected seams, so everything here is
 * about what the tool decides, never about what Guice would have returned. The guard's own rules
 * are the guard's tests; what is asserted here is that a refusal from it reaches the caller intact
 * and that nothing is written on the way.
 */
class CorrectOcrDocumentTotalToolTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** The receipt's text, as {@code OcrEvidenceService.quote} would return matching lines of it. */
  private static final List<String> DOCUMENT_LINES =
      Arrays.asList("ОБЩА СУМА ЕВРО", "ОБЩА СУМА ЛЕВА 86.73", "обменен курс 1ЕВРО=1.95583 ЛЕВА");

  // ── the tool under test, with every runtime collaborator replaced ────────────────────────────

  /** Records what the tool asked of each seam, so "nothing was written" is checkable. */
  private static final class Harness extends CorrectOcrDocumentTotalTool {

    private final Invoice invoice;
    private final SupplierInvoiceTarget target;
    private final RuntimeException authorisationRefusal;
    private final List<String> documentMatches;

    private final List<String> searched = new ArrayList<>();
    private final Map<String, Object> written = new LinkedHashMap<>();

    Harness(
        Invoice invoice,
        SupplierInvoiceTarget target,
        RuntimeException authorisationRefusal,
        List<String> documentMatches) {
      this.invoice = invoice;
      this.target = target;
      this.authorisationRefusal = authorisationRefusal;
      this.documentMatches = documentMatches;
    }

    @Override
    protected SupplierInvoiceTarget resolveTarget(JsonNode args) {
      return target;
    }

    @Override
    protected Invoice authorise(long uploadId) {
      if (authorisationRefusal != null) {
        throw authorisationRefusal;
      }
      return invoice;
    }

    @Override
    protected List<String> quoteDocument(long uploadId, String needle) {
      searched.add(needle);
      if (documentMatches == null) {
        return null; // no stored extraction
      }
      List<String> hits = new ArrayList<>();
      for (String line : documentMatches) {
        if (line.contains(needle)) {
          hits.add(line);
        }
      }
      return hits;
    }

    @Override
    protected OcrDocumentTotalCorrectionService.Result apply(
        Invoice target, BigDecimal gross, BigDecimal net, BigDecimal vat, String reason) {
      written.put("gross", gross);
      written.put("net", net);
      written.put("vat", vat);
      written.put("reason", reason);
      // Exactly what the real service returns: the extracted figures as they were, the corrected
      // ones as applied, and the audit it just stored.
      boolean breakdown = net != null && vat != null;
      return new OcrDocumentTotalCorrectionService.Result(
          target.getOcrDocumentTotal(),
          target.getOcrDocumentNetTotal(),
          target.getOcrDocumentVatTotal(),
          gross,
          breakdown ? net : null,
          breakdown ? vat : null,
          breakdown,
          "jdoe",
          LocalDateTime.of(2026, 10, 25, 11, 30),
          reason);
    }

    boolean wroteAnything() {
      return !written.isEmpty();
    }
  }

  /** Invoice 41 as it stands before any correction. */
  private static Invoice invoice41() {
    Invoice invoice = new Invoice();
    invoice.setId(41L);
    invoice.setOcrDocumentTotal(new BigDecimal("80.750"));
    invoice.setExTaxTotal(new BigDecimal("72.27"));
    invoice.setTaxTotal(new BigDecimal("14.45"));
    invoice.setInTaxTotal(new BigDecimal("86.72"));
    InvoiceLine fuel = new InvoiceLine();
    fuel.setId(1L);
    invoice.setInvoiceLineList(new ArrayList<>(List.of(fuel)));
    return invoice;
  }

  private static Harness tool(Invoice invoice) {
    return new Harness(invoice, new SupplierInvoiceTarget(95L, null), null, DOCUMENT_LINES);
  }

  private static JsonNode args(String json) {
    try {
      return MAPPER.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(Object result, String key) {
    return (Map<String, Object>) ((Map<String, Object>) result).get(key);
  }

  // ── the measured case ────────────────────────────────────────────────────────────────────────

  /**
   * 86.73 is printed, 86.72 is what the lines calculate. Correcting to the printed figure leaves a
   * one-stotinka gap, which is exactly what the automatic rounding adjustment is for — so the tool
   * must report ADJUSTABLE and not pretend the two now agree.
   */
  @Test
  void theDocumentsPrintedTotalIsStoredAndTheRemainingCentIsReportedAsAdjustable() {
    Harness tool = tool(invoice41());

    Object out =
        tool.execute(
            args(
                "{\"uploadId\":95,\"correctedTotal\":86.73,"
                    + "\"evidence\":\"ОБЩА СУМА ЕВРО on the receipt\"}"));

    assertEquals(new BigDecimal("86.73"), tool.written.get("gross"));
    Map<String, Object> verification = section(out, "verification");
    assertEquals("ADJUSTABLE", verification.get("verdict"));
    assertEquals(new BigDecimal("86.72"), verification.get("calculatedFromLines"));
    assertEquals(new BigDecimal("0.01"), verification.get("difference"));
  }

  /** The invoice's own 86.72 is not a document total, and saying so must be the refusal's point. */
  @Test
  void theTotalTheLinesCalculateIsRefusedByName() {
    Harness tool = tool(invoice41());

    AiToolException refusal =
        assertThrows(
            AiToolException.class,
            () ->
                tool.execute(
                    args(
                        "{\"uploadId\":95,\"correctedTotal\":86.72,"
                            + "\"evidence\":\"to make it reconcile\"}")));

    assertTrue(refusal.getMessage().contains("86.72"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("lines calculate"), refusal.getMessage());
    assertFalse(tool.wroteAnything(), "a refusal must leave the record untouched");
  }

  /**
   * And {@code source=user} does not buy it back. The one correction this feature exists to prevent
   * cannot have a door marked "the user said so" next to it, because that is how it would be asked
   * for — the user wants the invoice to validate, not a particular figure.
   */
  @Test
  void theLineDerivedTotalStaysRefusedEvenWhenTheUserIsSaidToHaveStatedIt() {
    Harness tool = tool(invoice41());

    assertThrows(
        AiToolException.class,
        () ->
            tool.execute(
                args(
                    "{\"uploadId\":95,\"correctedTotal\":86.72,\"source\":\"user\","
                        + "\"evidence\":\"the user told me 86.72\"}")));
    assertFalse(tool.wroteAnything());
  }

  /** A figure that is on no page: refused, with the repair named. */
  @Test
  void anAmountTheDocumentDoesNotContainIsRefusedOnTheDocumentPath() {
    Harness tool = tool(invoice41());

    AiToolException refusal =
        assertThrows(
            AiToolException.class,
            () ->
                tool.execute(
                    args(
                        "{\"uploadId\":95,\"correctedTotal\":120.00,"
                            + "\"evidence\":\"bottom of the page\"}")));

    assertTrue(refusal.getMessage().contains("does not contain"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("get_ocr_evidence"), refusal.getMessage());
    assertFalse(tool.wroteAnything());
    assertTrue(tool.searched.contains("120.00"), "it must actually have looked");
  }

  // ── authorisation and company isolation ──────────────────────────────────────────────────────

  /**
   * An upload of another company answers as not-found, and that wording — never "another company's"
   * — is what has to reach the caller. Nothing may be searched or written on the way.
   */
  @Test
  void anUploadOutsideTheActiveCompanyIsRefusedAsNotFoundAndWritesNothing() {
    Harness tool =
        new Harness(
            null,
            new SupplierInvoiceTarget(95L, null),
            new OcrBlocked(AiBlockerCode.RECORD_NOT_FOUND, "No OCR record with id 95."),
            DOCUMENT_LINES);

    AiToolException refusal =
        assertThrows(
            AiToolException.class,
            () ->
                tool.execute(
                    args("{\"uploadId\":95,\"correctedTotal\":86.73,\"evidence\":\"ОБЩА СУМА\"}")));

    assertEquals("No OCR record with id 95.", refusal.getMessage());
    assertFalse(refusal.getMessage().contains("company"), "never confirms the record exists");
    assertTrue(tool.searched.isEmpty(), "authorisation decides before the document is read");
    assertFalse(tool.wroteAnything());
  }

  /** A caller without write permission on the row is refused with the guard's own blocker code. */
  @Test
  void aCallerWithoutWritePermissionIsRefusedWithTheBlockerKept() {
    Harness tool =
        new Harness(
            null,
            new SupplierInvoiceTarget(95L, null),
            new OcrBlocked(
                AiBlockerCode.PERMISSION_DENIED,
                "You do not have permission to change invoice 41."),
            DOCUMENT_LINES);

    AiToolException refusal =
        assertThrows(
            AiToolException.class,
            () ->
                tool.execute(
                    args("{\"uploadId\":95,\"correctedTotal\":86.73,\"evidence\":\"ОБЩА СУМА\"}")));

    assertEquals(AiBlockerCode.PERMISSION_DENIED.wire(), refusal.details().get("blocker"));
    assertFalse(tool.wroteAnything());
  }

  /** An invoice with no scanned page has no extraction to correct, and the refusal says which. */
  @Test
  void anInvoiceWithNoDocumentBehindItIsRefusedPrecisely() {
    Harness tool =
        new Harness(invoice41(), new SupplierInvoiceTarget(null, 606L), null, DOCUMENT_LINES);

    AiToolException refusal =
        assertThrows(
            AiToolException.class,
            () ->
                tool.execute(
                    args("{\"invoiceId\":606,\"correctedTotal\":86.73,\"evidence\":\"printed\"}")));

    assertTrue(refusal.getMessage().contains("no scanned document"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains("edit_supplier_invoice"), refusal.getMessage());
    assertFalse(tool.wroteAnything());
  }

  // ── audit, preservation and blast radius ─────────────────────────────────────────────────────

  /**
   * The whole point of the feature: the extraction stays as it was. The correction is reported
   * beside the original rather than in place of it, and the invoice's own fields are not touched by
   * this tool at all — the lines least of all.
   */
  @Test
  void theOriginalExtractionIsPreservedAndNoInvoiceLineIsTouched() {
    Invoice invoice = invoice41();
    List<InvoiceLine> linesBefore = invoice.getInvoiceLineList();
    Harness tool = tool(invoice);

    Object out =
        tool.execute(
            args("{\"uploadId\":95,\"correctedTotal\":86.73,\"evidence\":\"ОБЩА СУМА ЕВРО\"}"));

    Map<String, Object> original = section(out, "originalExtraction");
    assertEquals(new BigDecimal("80.750"), original.get("documentTotal"));
    assertEquals(
        new BigDecimal("80.750"),
        invoice.getOcrDocumentTotal(),
        "the extracted total is evidence and is never rewritten");
    assertEquals(new BigDecimal("72.27"), invoice.getExTaxTotal(), "header totals untouched");
    assertEquals(new BigDecimal("86.72"), invoice.getInTaxTotal(), "header totals untouched");
    assertSameLines(linesBefore, invoice.getInvoiceLineList());
    assertEquals(Boolean.FALSE, ((Map<?, ?>) out).get("invoiceLinesChanged"));
  }

  /** Who, when, why and the quoted document lines all come back with the corrected amount. */
  @Test
  void theAuditAndTheEvidenceAreReturnedWithTheCorrection() {
    Harness tool = tool(invoice41());

    Object out =
        tool.execute(
            args(
                "{\"uploadId\":95,\"correctedTotal\":86.73,"
                    + "\"evidence\":\"ОБЩА СУМА ЕВРО on the receipt\"}"));

    Map<String, Object> audit = section(out, "audit");
    assertEquals("jdoe", audit.get("correctedBy"));
    assertNotNull(audit.get("correctedOn"));
    assertEquals("ОБЩА СУМА ЕВРО on the receipt", audit.get("reason"));
    assertEquals("document", audit.get("source"));
    assertTrue(audit.get("documentLines").toString().contains("86.73"));
    assertEquals(
        "ОБЩА СУМА ЕВРО on the receipt", tool.written.get("reason"), "stored, not just reported");
  }

  /** No note, no correction: an unexplained amount cannot be reviewed afterwards. */
  @Test
  void aCorrectionWithoutAnEvidenceNoteIsRefused() {
    Harness tool = tool(invoice41());

    assertThrows(
        AiToolException.class,
        () -> tool.execute(args("{\"uploadId\":95,\"correctedTotal\":86.73}")));
    assertFalse(tool.wroteAnything());
  }

  /**
   * The breakdown rules are the service's ({@code OcrDocumentTotalCorrectionAmountsTest} states
   * them); what matters here is that they run BEFORE the document is searched or anything written.
   */
  @Test
  void anIncoherentBreakdownIsRefusedBeforeAnythingIsSearchedOrWritten() {
    Harness tool = tool(invoice41());

    assertThrows(
        AiToolException.class,
        () ->
            tool.execute(
                args(
                    "{\"uploadId\":95,\"correctedTotal\":86.73,\"correctedNet\":72.27,"
                        + "\"evidence\":\"ОБЩА СУМА\"}")));

    assertTrue(tool.searched.isEmpty());
    assertFalse(tool.wroteAnything());
  }

  /** The printed net/VAT pair, when the document carries one, is stored alongside the gross. */
  @Test
  void aCoherentPrintedBreakdownIsStoredWithTheGross() {
    Harness tool = tool(invoice41());

    Object out =
        tool.execute(
            args(
                "{\"uploadId\":95,\"correctedTotal\":86.73,\"correctedNet\":72.27,"
                    + "\"correctedVat\":14.46,\"evidence\":\"ОБЩА СУМА + ДДС rows\"}"));

    assertEquals(new BigDecimal("72.27"), tool.written.get("net"));
    assertEquals(new BigDecimal("14.46"), tool.written.get("vat"));
    Map<String, Object> corrected = section(out, "corrected");
    assertEquals(Boolean.TRUE, corrected.get("breakdownSupplied"));
  }

  // ── contract ─────────────────────────────────────────────────────────────────────────────────

  /** A WRITE tool with a closed schema; the dispatcher refuses it unless writes are enabled. */
  @Test
  void itIsAClosedSchemaWriteTool() {
    CorrectOcrDocumentTotalTool tool = new CorrectOcrDocumentTotalTool();
    assertEquals("correct_ocr_document_total", tool.name());
    assertEquals(AiToolKind.WRITE, tool.kind());
    assertFalse(tool.parametersSchema().path("additionalProperties").asBoolean(true));
    assertNull(tool.parametersSchema().path("properties").get("lines"), "it cannot reach a line");
  }

  /** The description has to tell the model not to stop and ask again. That is the reported bug. */
  @Test
  void theDescriptionForbidsAFurtherConfirmationOrANumberedChoice() {
    String description = new CorrectOcrDocumentTotalTool().description();
    assertTrue(description.contains("do not ask for a further confirmation"), description);
    assertTrue(description.contains("numbered choice"), description);
  }

  private static void assertSameLines(List<InvoiceLine> before, List<InvoiceLine> after) {
    assertEquals(before.size(), after.size(), "no line added or removed");
    for (int i = 0; i < before.size(); i++) {
      assertTrue(before.get(i) == after.get(i), "line " + i + " replaced");
    }
  }
}
