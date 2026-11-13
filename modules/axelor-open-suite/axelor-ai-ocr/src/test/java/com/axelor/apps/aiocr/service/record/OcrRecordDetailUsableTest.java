package com.axelor.apps.aiocr.service.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

/**
 * What {@code detail()} now tells a caller, and why it matters.
 *
 * <p>The headline is the gap between {@code ocrReviewStatus} and {@code usable}: review status
 * checks five header fields, so an invoice with no line accounts and no tax passes it while still
 * being impossible to validate or ventilate. {@code usable} is the honest verdict, and the lines
 * and readiness lists are what let a caller say *why*.
 */
class OcrRecordDetailUsableTest extends AiOcrTest {

  private OcrRecordViewService view() {
    return Beans.get(OcrRecordViewService.class);
  }

  /** See the search test: a system scope must name a company, so one is created and handed over. */
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
      c = JPA.save(c);
    }
    return c;
  }

  /** A draft that would read "complete" today: header fields present, lines bare. */
  private AiOcrUpload completeLookingUpload(Company company) {
    Partner partner = new Partner();
    partner.setName("Demo EOOD");
    partner.setFullName("Demo EOOD");
    partner.setIsSupplier(true);
    partner = JPA.save(partner);

    Invoice invoice = new Invoice();
    invoice.setCompany(company);
    invoice.setPartner(partner);
    invoice.setInvoiceDate(LocalDate.now().minusDays(1));
    invoice.setAiGenerated(Boolean.TRUE);
    invoice.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    invoice.setOcrReviewStatus("complete");
    invoice.setExTaxTotal(new BigDecimal("100.00"));
    invoice.setTaxTotal(new BigDecimal("20.00"));
    invoice.setInTaxTotal(new BigDecimal("120.00"));

    InvoiceLine line = new InvoiceLine();
    line.setInvoice(invoice);
    line.setProductName("Услуга по договор");
    line.setQty(BigDecimal.ONE);
    line.setPrice(new BigDecimal("100.00"));
    line.setExTaxTotal(new BigDecimal("100.00"));
    line.setInTaxTotal(new BigDecimal("120.00"));
    line.setTaxRate(new BigDecimal("7")); // a rate the BG purchase mapping does not know
    invoice.setInvoiceLineList(new ArrayList<>());
    invoice.addInvoiceLineListItem(line);
    invoice = JPA.save(invoice);

    AiOcrUpload upload = new AiOcrUpload();
    upload.setFileName("usable-test.pdf");
    upload.setCompany(company);
    upload.setInvoice(invoice);
    upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    return JPA.save(upload);
  }

  @SuppressWarnings("unchecked")
  @Test
  void reviewCompleteDoesNotMeanUsable() {
    asSystem(
        "USE1",
        c -> {
          AiOcrUpload upload = completeLookingUpload(c);
          Map<String, Object> detail = view().detail(upload);

          Map<String, Object> inv = (Map<String, Object>) detail.get("invoice");
          assertEquals("complete", inv.get("ocrReviewStatus"));

          // ...and yet it cannot be ventilated: no line account, no tax, no journal config.
          assertEquals(
              Boolean.FALSE, detail.get("usable"), "the honest verdict must disagree with review");

          Map<String, Object> readiness = (Map<String, Object>) detail.get("readiness");
          assertNotNull(readiness);
          List<String> ventilate = (List<String>) readiness.get("ventilateBlockers");
          assertFalse(ventilate.isEmpty(), "and it must say why");
          assertTrue(
              ventilate.stream().anyMatch(b -> b.contains("no tax")),
              "the unmapped 7% rate must be reported: " + ventilate);
        });
  }

  @SuppressWarnings("unchecked")
  @Test
  void ventilateReadinessAndStatusLabelAreReported() {
    asSystem(
        "USE6",
        c -> {
          AiOcrUpload upload = completeLookingUpload(c);
          Map<String, Object> detail = view().detail(upload);

          // The ventilate-ready twin of readyToValidate: this fixture cannot be posted (no tax, no
          // line account), so it must say so explicitly rather than leave the caller to infer it.
          assertEquals(Boolean.FALSE, detail.get("readyToVentilate"), detail.toString());
          // And it is a draft, reported as a label and not only as the raw statusSelect number.
          Map<String, Object> inv = (Map<String, Object>) detail.get("invoice");
          assertEquals(InvoiceRepository.STATUS_DRAFT, inv.get("statusSelect"));
          assertEquals("DRAFT", inv.get("status"));
        });
  }

  @SuppressWarnings("unchecked")
  @Test
  void linesAreExposedWithTheirProductAccountAndTaxState() {
    asSystem(
        "USE2",
        c -> {
          AiOcrUpload upload = completeLookingUpload(c);
          Map<String, Object> detail = view().detail(upload);

          List<Map<String, Object>> lines = (List<Map<String, Object>>) detail.get("lines");
          assertEquals(1, lines.size());
          Map<String, Object> line = lines.get(0);

          // Without these a model literally cannot diagnose a line problem.
          assertNotNull(line.get("lineId"));
          assertEquals(0, line.get("index"));
          assertEquals("Услуга по договор", line.get("productName"));
          assertEquals(Boolean.FALSE, line.get("hasTaxLine"));
          assertEquals(null, line.get("productId"));
          assertEquals(null, line.get("accountId"));
          assertEquals(new BigDecimal("100.00"), line.get("exTaxTotal"));
        });
  }

  @SuppressWarnings("unchecked")
  @Test
  void headerAmountsAndReconciliationAreReported() {
    asSystem(
        "USE3",
        c -> {
          AiOcrUpload upload = completeLookingUpload(c);
          Map<String, Object> inv = (Map<String, Object>) view().detail(upload).get("invoice");

          assertEquals(new BigDecimal("100.00"), inv.get("exTaxTotal"));
          assertEquals(new BigDecimal("20.00"), inv.get("taxTotal"));
          assertEquals(new BigDecimal("120.00"), inv.get("inTaxTotal"));

          Map<String, Object> rec = (Map<String, Object>) inv.get("amountsReconcile");
          assertEquals(Boolean.TRUE, rec.get("headerReconciles"), "100 + 20 = 120");
          assertEquals(Boolean.TRUE, rec.get("linesReconcile"), "the single line sums to the base");
        });
  }

  @SuppressWarnings("unchecked")
  @Test
  void abrokenHeaderIsReportedAsNotReconciling() {
    asSystem(
        "USE4",
        c -> {
          AiOcrUpload upload = completeLookingUpload(c);
          Invoice invoice = upload.getInvoice();
          invoice.setInTaxTotal(new BigDecimal("999.00")); // base + vat no longer equals gross
          JPA.save(invoice);

          Map<String, Object> inv = (Map<String, Object>) view().detail(upload).get("invoice");
          Map<String, Object> rec = (Map<String, Object>) inv.get("amountsReconcile");
          assertEquals(Boolean.FALSE, rec.get("headerReconciles"));
          assertNotNull(rec.get("headerDelta"));
        });
  }

  @Test
  void rawOcrIsStillNotEmbeddedByDefault() {
    asSystem(
        "USE5",
        c -> {
          // The projection grew a lot in this phase; the 32 KB tool-result ceiling still has to
          // hold, so the big payload stays opt-in.
          @SuppressWarnings("unchecked")
          Map<String, Object> raw =
              (Map<String, Object>) view().detail(completeLookingUpload(c)).get("rawOcr");
          assertEquals(null, raw.get("json"));
        });
  }
}
