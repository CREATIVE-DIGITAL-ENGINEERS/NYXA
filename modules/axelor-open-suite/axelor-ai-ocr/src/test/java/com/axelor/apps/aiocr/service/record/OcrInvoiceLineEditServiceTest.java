package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.TaxRepository;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * Line editing, built around a real-shaped case.
 *
 * <p>A fuel receipt from ELTRADE where OCR produced two "lines" — the fragment "#в" (from "#в т.ч.
 * ДДС") at 3.99 and the card-payment row "MASTER CARD CONTACTLESS ПОКУПКА/SALE" at 46.88 — while
 * the header read 19.97 + 4.00 = 23.97 correctly. The document text says "E Gas LPG", "количество
 * 41.328", "нето стойност 19.97", "ДДС*В 4.00", "B=20.00%". The fixture reproduces exactly that
 * shape, and the headline test replaces the two junk lines with the one real one.
 *
 * <p>Everything else here is a refusal or a recomputation, which is the whole safety argument: the
 * caller may state what the document says, and Java decides what follows from it.
 */
class OcrInvoiceLineEditServiceTest extends AiOcrTest {

  private static final BigDecimal HEADER_BASE = new BigDecimal("19.97");
  private static final BigDecimal HEADER_VAT = new BigDecimal("4.00");
  private static final BigDecimal HEADER_GROSS = new BigDecimal("23.97");

  private OcrInvoiceLineEditService svc() {
    return Beans.get(OcrInvoiceLineEditService.class);
  }

  /**
   * See the search test: a system scope must name a company, so one is created here and handed to
   * the body. There is no "no company" variant, because there is no such scope any more.
   */
  private void asSystem(String companyCode, Consumer<Company> body) {
    inTransaction(
        () -> {
          Company scope = company(companyCode);
          AiExecutionContext.runAsSystem(scope.getId(), "test", () -> body.accept(scope));
        });
  }

  private Company company(String code) {
    Company c =
        Beans.get(CompanyRepository.class)
            .all()
            .filter("self.code = :c")
            .bind("c", code)
            .fetchOne();
    if (c == null) {
      c = new Company();
      c.setName(code);
      c.setCode(code);
      c.setCurrency(currency());
      c = JPA.save(c);
    }
    return c;
  }

  /**
   * EUR with two decimals.
   *
   * <p>{@code numberOfDecimals} matters more than it looks: {@code CurrencyScaleService} rounds
   * every amount to it, and the generated entity defaults it to <b>0</b>. A currency left at the
   * default would round 19.97 to 20 — in the suite's own {@code InvoiceLineGenerator} just as much
   * as here. Both live currencies (EUR, BGN) are configured with 2.
   */
  private Currency currency() {
    Currency eur = JPA.all(Currency.class).filter("self.codeISO = :c").bind("c", "EUR").fetchOne();
    if (eur == null) {
      eur = new Currency();
      eur.setName("Euro");
      eur.setCodeISO("EUR");
      eur.setNumberOfDecimals(2);
      eur = JPA.save(eur);
    }
    return eur;
  }

  /** The 20% BG purchase tax, with a version covering any plausible test date. */
  private Tax standardVat() {
    Tax tax =
        Beans.get(TaxRepository.class)
            .all()
            .filter("self.code = :c")
            .bind("c", BgOcrVatMapping.BG_STD_20)
            .fetchOne();
    if (tax == null) {
      tax = new Tax();
      tax.setCode(BgOcrVatMapping.BG_STD_20);
      tax.setName("Покупки – стандартна ставка 20%");
      TaxLine line = new TaxLine();
      line.setTax(tax);
      line.setValue(new BigDecimal("20.0000000000"));
      line.setStartDate(LocalDate.of(2023, 1, 1));
      tax.setTaxLineList(new ArrayList<>(Collections.singletonList(line)));
      tax = JPA.save(tax);
    }
    return tax;
  }

  /**
   * The #576 shape: correct header, two junk lines that sum to 50.87 instead of 19.97.
   *
   * <p>Dates are set because a tax version can only be chosen for a date — which is itself one of
   * the things invoice 576 was missing, and why it is fixed in the same session.
   */
  private AiOcrUpload eltradeReceipt(Company company) {
    standardVat();
    Partner partner = new Partner();
    partner.setName("ЕКО БЪЛГАРИЯ ЕАД");
    partner.setFullName("ЕКО БЪЛГАРИЯ ЕАД");
    partner.setIsSupplier(true);
    partner.setRegistrationCode("130948987");
    partner = JPA.save(partner);

    Invoice invoice = new Invoice();
    invoice.setCompany(company);
    invoice.setCurrency(currency());
    invoice.setPartner(partner);
    invoice.setSupplierInvoiceNb("№1084628785");
    invoice.setInvoiceDate(LocalDate.of(2026, 3, 3));
    invoice.setOriginDate(LocalDate.of(2026, 3, 3));
    invoice.setAiGenerated(Boolean.TRUE);
    invoice.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    invoice.setOcrReviewStatus("complete");
    invoice.setExTaxTotal(HEADER_BASE);
    invoice.setTaxTotal(HEADER_VAT);
    invoice.setInTaxTotal(HEADER_GROSS);
    invoice.setInAti(Boolean.FALSE);
    invoice.setInvoiceLineList(new ArrayList<>());

    invoice.addInvoiceLineListItem(junkLine("#в", new BigDecimal("3.99")));
    invoice.addInvoiceLineListItem(
        junkLine("MASTER CARD\nCONTACTLESS\nПОКУПКА/SALE", new BigDecimal("46.88")));
    invoice = JPA.save(invoice);

    AiOcrUpload upload = new AiOcrUpload();
    upload.setFileName("manyinvoices_plusreciepts-invoice-p19-18.pdf");
    upload.setCompany(company);
    upload.setInvoice(invoice);
    upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    return JPA.save(upload);
  }

  private InvoiceLine junkLine(String description, BigDecimal amount) {
    InvoiceLine line = new InvoiceLine();
    line.setProductName(description);
    line.setDescription(description);
    line.setQty(BigDecimal.ONE);
    line.setPrice(amount);
    line.setExTaxTotal(amount);
    line.setInTaxTotal(amount);
    line.setTaxRate(BigDecimal.ZERO);
    line.setTaxCode(BgOcrVatMapping.BG_ZERO);
    return line;
  }

  private OcrInvoiceLineEditService.LineSpec eGasLpg() {
    return new OcrInvoiceLineEditService.LineSpec()
        .setDescription("E Gas LPG")
        .setQty(new BigDecimal("41.328"))
        .setExTaxTotal(HEADER_BASE)
        .setTaxCode(BgOcrVatMapping.BG_STD_20);
  }

  // ---------------------------------------------------------------------------
  // The headline case
  // ---------------------------------------------------------------------------

  @Test
  void twoBadOcrLinesAreReplacedByTheOneRealLineAndTheInvoiceThenReconciles() {
    asSystem(
        "EDIT1",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          Invoice invoice = upload.getInvoice();
          List<Long> junk =
              Arrays.asList(
                  invoice.getInvoiceLineList().get(0).getId(),
                  invoice.getInvoiceLineList().get(1).getId());

          // Before: the lines add up to 50.87 against a printed base of 19.97.
          assertEquals(
              Boolean.FALSE,
              Beans.get(OcrRecordViewService.class)
                  .amountsReconcile(invoice)
                  .get("linesReconcile"));

          OcrInvoiceLineEditService.LineEditResult r =
              svc()
                  .replaceLines(
                      upload.getId(),
                      junk,
                      Collections.singletonList(eGasLpg()),
                      "OCR text: 'E Gas LPG', 'количество 41.328', 'нето стойност 19.97', 'B=20.00%'");

          Invoice reloaded = JPA.find(Invoice.class, invoice.getId());
          assertEquals(1, reloaded.getInvoiceLineList().size(), "two junk lines out, one real in");
          InvoiceLine line = reloaded.getInvoiceLineList().get(0);
          assertEquals("E Gas LPG", line.getProductName());
          assertEquals(0, line.getExTaxTotal().compareTo(HEADER_BASE));
          assertEquals(0, new BigDecimal("41.328").compareTo(line.getQty()));

          // Derived by Java, not supplied: 19.97 × 1.20 = 23.964 → 23.96 at currency scale.
          assertEquals(0, new BigDecimal("23.96").compareTo(line.getInTaxTotal()));
          assertEquals(0, new BigDecimal("20").compareTo(line.getTaxRate()));
          assertEquals(BgOcrVatMapping.BG_STD_20, line.getTaxCode());
          assertNotNull(line.getTaxLineSet());
          assertEquals(1, line.getTaxLineSet().size());
          // 19.97 / 41.328 = 0.4831..., i.e. the NET unit price, never the printed gross 0.58.
          assertTrue(
              line.getPrice().compareTo(new BigDecimal("0.49")) < 0,
              "the unit price must be net, was " + line.getPrice());

          // Header totals untouched — they are the check on the new lines, not an output of them.
          assertEquals(0, reloaded.getExTaxTotal().compareTo(HEADER_BASE));
          assertEquals(0, reloaded.getTaxTotal().compareTo(HEADER_VAT));
          assertEquals(0, reloaded.getInTaxTotal().compareTo(HEADER_GROSS));

          // ...and now they agree with the lines.
          assertEquals(Boolean.TRUE, r.getAmountsReconcile().get("linesReconcile"));
          assertEquals(2, r.getRemoved().size());
          assertFalse(
              r.getVentilateBlockers().stream().anyMatch(b -> b.contains("do not add up")),
              "the line-sum blocker must be gone: " + r.getVentilateBlockers());
        });
  }

  @Test
  void theAuditNoteRecordsBeforeAfterAndTheEvidence() {
    asSystem(
        "EDIT2",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          svc()
              .replaceLines(
                  upload.getId(),
                  null,
                  Collections.singletonList(eGasLpg()),
                  "нето стойност 19.97 / B=20.00%");

          String notes =
              JPA.find(Invoice.class, upload.getInvoice().getId()).getAiValidationNotes();
          assertNotNull(notes);
          assertTrue(notes.contains("Line correction by"), notes);
          assertTrue(notes.contains("removed line"), notes);
          assertTrue(notes.contains("MASTER CARD"), "the old value must be recoverable: " + notes);
          assertTrue(notes.contains("E Gas LPG"), notes);
          assertTrue(notes.contains("Evidence: нето стойност 19.97"), notes);
        });
  }

  @Test
  void aReplacementThatStillDoesNotAddUpIsReportedRatherThanSmoothedOver() {
    asSystem(
        "EDIT3",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          OcrInvoiceLineEditService.LineEditResult r =
              svc()
                  .replaceLines(
                      upload.getId(),
                      null,
                      Collections.singletonList(eGasLpg().setExTaxTotal(new BigDecimal("99.00"))),
                      null);

          // The change is applied — the caller may genuinely believe the header was misread — but
          // the disagreement stays visible, and never becomes "usable".
          assertEquals(Boolean.FALSE, r.getAmountsReconcile().get("linesReconcile"));
          assertTrue(
              r.getVentilateBlockers().stream().anyMatch(b -> b.contains("do not add up")),
              r.getVentilateBlockers().toString());
          assertFalse(r.isUsable());
          // Pre-existing staleness, unrelated to company scope: 9343b441 replaced the "still do not
          // add up" note with the three-way reconciliation wording and left this assertion behind.
          // The contract is unchanged — the disagreement is written into the audit note — so the
          // assertion is realigned with the sentence the service actually produces.
          String notes =
              JPA.find(Invoice.class, upload.getInvoice().getId()).getAiValidationNotes();
          assertTrue(notes.contains("which rounding cannot explain"), notes);
        });
  }

  // ---------------------------------------------------------------------------
  // add / update / delete
  // ---------------------------------------------------------------------------

  @Test
  void aLineCanBeAddedAndItsGrossAmountIsDerived() {
    asSystem(
        "EDIT4",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          svc()
              .addLine(
                  upload.getId(),
                  new OcrInvoiceLineEditService.LineSpec()
                      .setDescription("Такса обслужване")
                      .setExTaxTotal(new BigDecimal("10.00"))
                      .setTaxCode(BgOcrVatMapping.BG_STD_20),
                  null);

          Invoice reloaded = JPA.find(Invoice.class, upload.getInvoice().getId());
          assertEquals(3, reloaded.getInvoiceLineList().size());
          InvoiceLine added =
              reloaded.getInvoiceLineList().stream()
                  .filter(l -> "Такса обслужване".equals(l.getProductName()))
                  .findFirst()
                  .orElseThrow(() -> new AssertionError("the new line was not persisted"));
          assertEquals(0, new BigDecimal("12.00").compareTo(added.getInTaxTotal()));
          assertEquals(0, BigDecimal.ONE.compareTo(added.getQty()), "qty defaults to 1");
        });
  }

  @Test
  void anUpdateChangesOnlyTheFieldsSuppliedAndRecomputesWhatFollows() {
    asSystem(
        "EDIT5",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          long lineId = upload.getInvoice().getInvoiceLineList().get(0).getId();

          svc()
              .updateLine(
                  upload.getId(),
                  lineId,
                  new OcrInvoiceLineEditService.LineSpec().setTaxCode(BgOcrVatMapping.BG_STD_20),
                  null);

          InvoiceLine line =
              JPA.find(Invoice.class, upload.getInvoice().getId()).getInvoiceLineList().stream()
                  .filter(l -> l.getId() == lineId)
                  .findFirst()
                  .orElseThrow(AssertionError::new);
          assertEquals("#в", line.getDescription(), "the description was not touched");
          assertEquals(0, new BigDecimal("3.99").compareTo(line.getExTaxTotal()), "nor the amount");
          // But the gross followed the new rate: 3.99 × 1.20 = 4.788 → 4.79.
          assertEquals(0, new BigDecimal("4.79").compareTo(line.getInTaxTotal()));
          assertEquals(0, new BigDecimal("20").compareTo(line.getTaxRate()));
        });
  }

  @Test
  void aLineCanBeDeletedButNotTheLastOne() {
    asSystem(
        "EDIT6",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          long first = upload.getInvoice().getInvoiceLineList().get(0).getId();
          svc().deleteLine(upload.getId(), first, null);

          Invoice reloaded = JPA.find(Invoice.class, upload.getInvoice().getId());
          assertEquals(
              1, reloaded.getInvoiceLineList().size(), "orphanRemoval must delete the row");

          long last = reloaded.getInvoiceLineList().get(0).getId();
          IllegalArgumentException e =
              assertThrows(
                  IllegalArgumentException.class,
                  () -> svc().deleteLine(upload.getId(), last, null));
          assertTrue(e.getMessage().contains("no lines at all"), e.getMessage());
          assertEquals(
              1,
              JPA.find(Invoice.class, upload.getInvoice().getId()).getInvoiceLineList().size(),
              "and the refusal changed nothing");
        });
  }

  // ---------------------------------------------------------------------------
  // Invalid values
  // ---------------------------------------------------------------------------

  @Test
  void aUnitPriceThatContradictsTheAmountIsRefused() {
    // The real mistake this catches: passing the GROSS unit price (0.58 per litre) alongside the
    // NET amount (19.97). 41.328 × 0.58 = 23.97, not 19.97, so the two cannot both be right.
    asSystem(
        "EDIT7",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          IllegalArgumentException e =
              assertThrows(
                  IllegalArgumentException.class,
                  () ->
                      svc()
                          .replaceLines(
                              upload.getId(),
                              null,
                              Collections.singletonList(
                                  eGasLpg().setUnitPrice(new BigDecimal("0.58"))),
                              null));
          assertTrue(e.getMessage().contains("does not add up"), e.getMessage());

          assertEquals(
              2,
              JPA.find(Invoice.class, upload.getInvoice().getId()).getInvoiceLineList().size(),
              "a refused replacement must leave the original lines alone");
        });
  }

  @Test
  void aConsistentUnitPriceIsAcceptedDespiteRoundingAcrossManyUnits() {
    // The other side of the same check: 41.328 × 0.4832 = 19.9697…, which must not be rejected for
    // missing 19.97 by a fraction of a cent.
    asSystem(
        "EDIT8",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          OcrInvoiceLineEditService.LineEditResult r =
              svc()
                  .replaceLines(
                      upload.getId(),
                      null,
                      Collections.singletonList(eGasLpg().setUnitPrice(new BigDecimal("0.4832"))),
                      null);
          assertEquals(Boolean.TRUE, r.getAmountsReconcile().get("linesReconcile"));
        });
  }

  @Test
  void badLineValuesAreRefusedOneByOne() {
    asSystem(
        "EDIT9",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          long id = upload.getId();

          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          svc()
                              .addLine(
                                  id,
                                  new OcrInvoiceLineEditService.LineSpec()
                                      .setExTaxTotal(BigDecimal.TEN),
                                  null))
                  .getMessage()
                  .contains("needs a description"));

          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          svc()
                              .addLine(
                                  id,
                                  new OcrInvoiceLineEditService.LineSpec().setDescription("X"),
                                  null))
                  .getMessage()
                  .contains("needs an amount"));

          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          svc()
                              .addLine(
                                  id,
                                  new OcrInvoiceLineEditService.LineSpec()
                                      .setDescription("X")
                                      .setExTaxTotal(BigDecimal.TEN)
                                      .setQty(BigDecimal.ZERO),
                                  null))
                  .getMessage()
                  .contains("greater than zero"));

          // An output-VAT code, or anything not on the closed purchase list, never reaches a line.
          for (String bad : new String[] {"BG_SSP_20", "made-up", "VAT20"}) {
            assertTrue(
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                            svc()
                                .addLine(
                                    id,
                                    new OcrInvoiceLineEditService.LineSpec()
                                        .setDescription("X")
                                        .setExTaxTotal(BigDecimal.TEN)
                                        .setTaxCode(bad),
                                    null))
                    .getMessage()
                    .contains("not a Bulgarian purchase tax code"),
                "accepted " + bad);
          }

          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () -> svc().replaceLines(id, null, new ArrayList<>(), null))
                  .getMessage()
                  .contains("at least one new line"));
        });
  }

  @Test
  void anUnknownProductIsRefusedAndNothingIsWritten() {
    asSystem(
        "EDIT10",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          svc()
                              .addLine(
                                  upload.getId(),
                                  new OcrInvoiceLineEditService.LineSpec()
                                      .setDescription("X")
                                      .setExTaxTotal(BigDecimal.TEN)
                                      .setProductId(999_999L),
                                  null))
                  .getMessage()
                  .contains("No product with id"));
          assertEquals(
              2, JPA.find(Invoice.class, upload.getInvoice().getId()).getInvoiceLineList().size());
        });
  }

  // ---------------------------------------------------------------------------
  // Guards
  // ---------------------------------------------------------------------------

  @Test
  void aNonDraftInvoiceIsRefused() {
    asSystem(
        "EDIT11",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          Invoice invoice = upload.getInvoice();
          invoice.setStatusSelect(InvoiceRepository.STATUS_VALIDATED);
          JPA.save(invoice);

          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          svc()
                              .replaceLines(
                                  upload.getId(), null, Collections.singletonList(eGasLpg()), null))
                  .getMessage()
                  .contains("not a draft"));
        });
  }

  @Test
  void anInvoiceNotCreatedByOcrIsRefused() {
    asSystem(
        "EDIT12",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          Invoice invoice = upload.getInvoice();
          invoice.setAiGenerated(Boolean.FALSE);
          JPA.save(invoice);

          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          svc()
                              .deleteLine(
                                  upload.getId(),
                                  invoice.getInvoiceLineList().get(0).getId(),
                                  null))
                  .getMessage()
                  .contains("not created by OCR"));
        });
  }

  @Test
  void aSystemCallerCannotReachAnotherCompanysRecord() {
    inTransaction(
        () -> {
          Company mine = company("EDIT13A");
          Company theirs = company("EDIT13B");
          AiOcrUpload upload =
              AiExecutionContext.callAsSystem(
                  theirs.getId(), "setup", () -> eltradeReceipt(theirs));

          // Refused, and refused in the words of a missing record: a caller scoped to one company
          // must not be able to learn that the other company's document exists.
          String message =
              assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          AiExecutionContext.callAsSystem(
                              mine.getId(),
                              "test",
                              () ->
                                  svc()
                                      .replaceLines(
                                          upload.getId(),
                                          null,
                                          Collections.singletonList(eGasLpg()),
                                          null)))
                  .getMessage();
          assertTrue(
              message.contains("No OCR record with id"),
              "a cross-company refusal must read as not-found: " + message);
        });
  }

  @Test
  void withoutAnyCallerIdentityNothingCanBeChanged() {
    assertTrue(
        assertThrows(IllegalStateException.class, () -> svc().deleteLine(1L, 1L, null))
            .getMessage()
            .contains("No authenticated user"));
  }

  @Test
  void aLineIdFromAnotherInvoiceIsRefused() {
    asSystem(
        "EDIT14",
        c -> {
          AiOcrUpload a = eltradeReceipt(c);
          AiOcrUpload b = eltradeReceipt(c);
          long foreign = b.getInvoice().getInvoiceLineList().get(0).getId();

          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          svc()
                              .replaceLines(
                                  a.getId(),
                                  Collections.singletonList(foreign),
                                  Collections.singletonList(eGasLpg()),
                                  null))
                  .getMessage()
                  .contains("no line with id"));
          assertEquals(
              2, JPA.find(Invoice.class, b.getInvoice().getId()).getInvoiceLineList().size());
        });
  }

  // ---------------------------------------------------------------------------
  // Date-versioned taxes
  // ---------------------------------------------------------------------------

  @Test
  void aTaxCodeCannotBeResolvedWithoutADate() {
    asSystem(
        "EDIT15",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          Invoice invoice = upload.getInvoice();
          invoice.setInvoiceDate(null);
          invoice.setOriginDate(null);
          JPA.save(invoice);

          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () ->
                          svc()
                              .replaceLines(
                                  upload.getId(), null, Collections.singletonList(eGasLpg()), null))
                  .getMessage()
                  .contains("no date"));
        });
  }

  @Test
  void refreshingTaxVersionsIsANoOpWhenTheSameVersionStillApplies() {
    // The interesting property is that it does not churn: re-resolving a tax whose version has not
    // changed must report nothing, so a date edit does not fill the audit note with noise.
    asSystem(
        "EDIT16",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          svc().replaceLines(upload.getId(), null, Collections.singletonList(eGasLpg()), null);
          Invoice reloaded = JPA.find(Invoice.class, upload.getInvoice().getId());
          assertTrue(svc().refreshLineTaxVersions(reloaded).isEmpty());
        });
  }

  @Test
  void theProjectionReportsTheNewLinesAndTheReviewStateIsRederived() {
    asSystem(
        "EDIT17",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          OcrInvoiceLineEditService.LineEditResult r =
              svc().replaceLines(upload.getId(), null, Collections.singletonList(eGasLpg()), null);

          assertEquals(1, r.getLines().size());
          Map<String, Object> line = r.getLines().get(0);
          // The line is reported by its description. productName is deliberately omitted when it
          // equals the description: intake copies the description into productName when the
          // document
          // carries no product code, and OcrRecordViewService.lines drops that duplicate to keep
          // the
          // projection small (commit "Measure AI Accountant turn cost…"). So for this line, whose
          // description and productName are both "E Gas LPG", the key is absent, not populated.
          assertEquals("E Gas LPG", line.get("description"));
          assertFalse(
              line.containsKey("productName"),
              "productName is omitted when it only repeats the description");
          assertEquals(Boolean.TRUE, line.get("hasTaxLine"));
          assertNotNull(r.getOcrReviewStatus(), "recomputed, never asserted by the caller");
          assertNotNull(r.getMissingReviewFields());
        });
  }

  // ── OcrInvoiceLineUpdateService: product / account / tax on an existing line ───────────────
  // Merged in from OcrInvoiceLineUpdateIntegrationTest. The two services edit the same lines behind
  // the same guards, so a second class meant a second full Guice + HSQLDB bootstrap purely to build
  // an equivalent fixture. Its five guard cases (non-draft, not-OCR, other company, no caller, line
  // from another invoice) were already asserted above and are not repeated.

  private OcrInvoiceLineUpdateService updateSvc() {
    return Beans.get(OcrInvoiceLineUpdateService.class);
  }

  private Product product(String code, String name) {
    Product p = new Product();
    p.setCode(code);
    p.setName(name);
    return JPA.save(p);
  }

  @Test
  void productCodesAreUniqueAtSchemaLevelSoTheCodeTierCannotBeAmbiguous() {
    // Worth pinning: Product.code is unique="true", which is *why* resolving by code is safe enough
    // to run unattended. The "several matches" branch in the service is defence in depth against
    // that constraint being relaxed, not a case that can occur today.
    asSystem(
        "EDIT17B",
        c -> {
          product("DUP-CODE", "First");
          assertThrows(Exception.class, () -> product("DUP-CODE", "Second"));
        });
  }

  @Test
  void anUnknownProductCodeIsRefused() {
    asSystem(
        "EDIT18",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          long lineId = upload.getInvoice().getInvoiceLineList().get(0).getId();

          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () -> updateSvc().applyExactProductCode(upload.getId(), lineId, "NO-SUCH"))
                  .getMessage()
                  .contains("No product"));
        });
  }

  @Test
  void aProductWithoutAPurchaseAccountIsRefusedBecauseItWouldNotFixTheLine() {
    asSystem(
        "EDIT19",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          long lineId = upload.getInvoice().getInvoiceLineList().get(0).getId();
          Product p = product("NOACC", "No account product");

          // Bare test DB: nothing configures AccountManagement, so no account resolves. Accepting
          // this would leave the line just as unventilatable while claiming it was fixed.
          assertTrue(
              assertThrows(
                      IllegalArgumentException.class,
                      () -> updateSvc().setLineProduct(upload.getId(), lineId, p.getId()))
                  .getMessage()
                  .contains("purchase account"));
        });
  }

  @Test
  void aRefusedProductChangeLeavesEveryAmountExactlyAsItWas() {
    // The single most damaging thing this area could do is replace the supplier's printed figures
    // with derived ones. Asserted byte-for-byte on both the line and the header.
    asSystem(
        "EDIT20",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          Invoice invoice = upload.getInvoice();
          long lineId = invoice.getInvoiceLineList().get(0).getId();
          Product p = product("AMOUNTS", "Amount guard");

          BigDecimal lineEx = invoice.getInvoiceLineList().get(0).getExTaxTotal();
          BigDecimal lineIn = invoice.getInvoiceLineList().get(0).getInTaxTotal();
          BigDecimal headerEx = invoice.getExTaxTotal();
          BigDecimal headerIn = invoice.getInTaxTotal();

          try {
            updateSvc().setLineProduct(upload.getId(), lineId, p.getId());
          } catch (IllegalArgumentException expected) {
            // No account configured in a bare DB; either way the amounts must be untouched.
          }

          Invoice reloaded = JPA.find(Invoice.class, invoice.getId());
          InvoiceLine reloadedLine = reloaded.getInvoiceLineList().get(0);
          assertEquals(lineEx, reloadedLine.getExTaxTotal());
          assertEquals(lineIn, reloadedLine.getInTaxTotal());
          assertEquals(headerEx, reloaded.getExTaxTotal());
          assertEquals(headerIn, reloaded.getInTaxTotal());
        });
  }

  @Test
  void anUnknownTaxCodeIsRefusedByTheUpdateServiceToo() {
    // Same closed allowlist as the edit service, enforced independently.
    asSystem(
        "EDIT21",
        c -> {
          AiOcrUpload upload = eltradeReceipt(c);
          long lineId = upload.getInvoice().getInvoiceLineList().get(0).getId();

          for (String bad : new String[] {"BG_SSP_20", "made-up", ""}) {
            assertTrue(
                assertThrows(
                        IllegalArgumentException.class,
                        () -> updateSvc().setLineTax(upload.getId(), lineId, bad))
                    .getMessage()
                    .contains("not a Bulgarian purchase tax code"),
                "accepted '" + bad + "'");
          }
        });
  }
}
