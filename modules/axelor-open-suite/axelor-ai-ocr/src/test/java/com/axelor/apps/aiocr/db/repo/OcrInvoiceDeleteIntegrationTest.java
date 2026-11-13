package com.axelor.apps.aiocr.db.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.studio.db.AppSale;
import com.axelor.studio.db.repo.AppSaleRepository;
import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/**
 * DB-backed (real transaction + filesystem) proof of the hardened OCR delete rules:
 *
 * <ul>
 *   <li><b>Suppl. Invoices</b> delete removes the invoice and only UNLINKS the upload (kept); an
 *       OCR draft is auto-cancelled via the stock service first.
 *   <li><b>Upload Invoices</b> delete fully removes the upload, its blob, and the produced invoice.
 *   <li>A ventilated (ever) invoice is deletable from NEITHER path.
 *   <li>An invoice with accounting dependencies is not silently cancelled/deleted.
 * </ul>
 *
 * <p>The stock {@code Invoice} carries a {@code @Formula} sub-select HSQLDB cannot parse, so we
 * never SELECT an Invoice: fixtures keep the managed entity and assert existence with {@code
 * count(id)}.
 */
class OcrInvoiceDeleteIntegrationTest extends AiOcrTest {

  private InvoiceRepository invoiceRepo() {
    return Beans.get(InvoiceRepository.class);
  }

  private AiOcrUploadRepository uploadRepo() {
    return Beans.get(AiOcrUploadRepository.class);
  }

  private long countById(String entityName, long id) {
    return JPA.em()
        .createQuery("SELECT COUNT(e) FROM " + entityName + " e WHERE e.id = :id", Long.class)
        .setParameter("id", id)
        .getSingleResult();
  }

  private MetaFile newBlob(String content) throws Exception {
    File tmp = File.createTempFile("ocr-it", ".pdf");
    Files.writeString(tmp.toPath(), content);
    return Beans.get(MetaFiles.class).upload(tmp);
  }

  /** The stock invoice save/cancel chain needs an AppSale record + a Company; seed once. */
  private void seedAccountingConfig() {
    if (Beans.get(AppSaleRepository.class).all().count() == 0) {
      JPA.save(new AppSale());
    }
  }

  private Company sharedCompany() {
    Company c = Beans.get(CompanyRepository.class).all().fetchOne();
    if (c == null) {
      c = new Company();
      c.setName("IT Co");
      c.setCode("ITCO");
      c = JPA.save(c);
    }
    return c;
  }

  private static int seq = 0;

  private static final class Fixture {
    Invoice invoice;
    long invoiceId;
    long uploadId;
    long blobId;
    Path blobPath;
  }

  private Fixture createFixture(int status, boolean ventilated, boolean withCompany) {
    Fixture fx = new Fixture();
    inTransaction(
        () -> {
          try {
            seedAccountingConfig();
            MetaFile blob = newBlob("PDF");
            fx.blobId = blob.getId();
            fx.blobPath = MetaFiles.getPath(blob);
            Invoice inv = new Invoice();
            inv.setStatusSelect(status);
            inv.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
            inv.setAiGenerated(Boolean.TRUE); // OCR-managed
            inv.setInvoiceId("IT-" + (++seq)); // non-empty → stock save skips draft-sequence lookup
            inv.setInvoiceLineList(new java.util.ArrayList<>()); // stock cancel hooks iterate lines
            if (withCompany) {
              inv.setCompany(sharedCompany());
            }
            if (ventilated) {
              inv.setVentilatedDateTime(LocalDateTime.of(2026, 1, 1, 12, 0));
            }
            fx.invoice = JPA.save(inv);
            fx.invoiceId = fx.invoice.getId();
            AiOcrUpload up = new AiOcrUpload();
            up.setInvoice(fx.invoice);
            up.setOriginalFile(blob);
            fx.uploadId = JPA.save(up).getId();
          } catch (Exception e) {
            throw new RuntimeException(e);
          }
        });
    return fx;
  }

  @Test
  void repositoriesResolveToOcrOverrides() {
    assertTrue(invoiceRepo() instanceof OcrInvoiceRepository, "InvoiceRepository override");
    assertTrue(
        uploadRepo() instanceof AiOcrUploadManagementRepository, "AiOcrUploadRepository override");
  }

  // ---------------- Suppl. Invoices path ----------------

  @Test
  void supplierDeleteCanceledOcrKeepsUploadUnlinkedAndBlob() {
    Fixture fx = createFixture(InvoiceRepository.STATUS_CANCELED, false, false);

    inTransaction(() -> invoiceRepo().remove(fx.invoice));
    JPA.em().clear();

    assertEquals(0, countById("Invoice", fx.invoiceId), "invoice deleted");
    assertEquals(1, countById("AiOcrUpload", fx.uploadId), "upload kept");
    assertNull(uploadRepo().find(fx.uploadId).getInvoice(), "upload unlinked");
    assertEquals(1, countById("MetaFile", fx.blobId), "MetaFile kept");
    assertTrue(Files.exists(fx.blobPath), "blob kept");
  }

  @Test
  void supplierDeleteDraftOcrAutoCancelledAndDeletedKeepsUploadAndBlob() {
    Fixture fx = createFixture(InvoiceRepository.STATUS_DRAFT, false, true);

    inTransaction(() -> invoiceRepo().remove(fx.invoice)); // auto-cancel via stock, then delete
    JPA.em().clear();

    assertEquals(0, countById("Invoice", fx.invoiceId), "draft OCR invoice deleted");
    assertEquals(1, countById("AiOcrUpload", fx.uploadId), "upload kept");
    assertNull(uploadRepo().find(fx.uploadId).getInvoice(), "upload unlinked");
    assertTrue(Files.exists(fx.blobPath), "blob kept");
  }

  @Test
  void supplierDeleteVentilatedBlocked() {
    Fixture fx = createFixture(InvoiceRepository.STATUS_VENTILATED, false, false);

    assertThrows(
        RuntimeException.class, () -> inTransaction(() -> invoiceRepo().remove(fx.invoice)));
    JPA.em().clear();

    assertEquals(1, countById("Invoice", fx.invoiceId), "ventilated invoice not deleted");
    assertEquals(1, countById("AiOcrUpload", fx.uploadId), "upload untouched");
  }

  @Test
  void supplierDeleteCanceledAfterVentilationBlocked() {
    Fixture fx = createFixture(InvoiceRepository.STATUS_CANCELED, true, false);

    assertThrows(
        RuntimeException.class, () -> inTransaction(() -> invoiceRepo().remove(fx.invoice)));
    JPA.em().clear();

    assertEquals(1, countById("Invoice", fx.invoiceId), "canceled-after-ventilation kept");
  }

  @Test
  void supplierDeleteRollbackRestoresUnlink() {
    Fixture fx = createFixture(InvoiceRepository.STATUS_CANCELED, false, false);

    try {
      inTransaction(
          () -> {
            invoiceRepo().remove(fx.invoice);
            throw new IllegalStateException("boom — force rollback");
          });
      fail("expected rollback");
    } catch (RuntimeException expected) {
      // expected
    }
    JPA.em().clear();

    assertEquals(1, countById("Invoice", fx.invoiceId), "invoice kept on rollback");
    assertEquals(1, countById("AiOcrUpload", fx.uploadId), "upload kept");
    assertNotNull(uploadRepo().find(fx.uploadId).getInvoice(), "upload link RESTORED on rollback");
  }

  // ---------------- Upload Invoices path ----------------

  @Test
  void uploadDeleteCanceledOcrFullyDeletesUploadInvoiceBlob() {
    Fixture fx = createFixture(InvoiceRepository.STATUS_CANCELED, false, false);
    assertTrue(Files.exists(fx.blobPath), "blob present before");

    inTransaction(() -> uploadRepo().remove(uploadRepo().find(fx.uploadId)));
    JPA.em().clear();

    assertEquals(0, countById("AiOcrUpload", fx.uploadId), "upload deleted");
    assertEquals(0, countById("Invoice", fx.invoiceId), "produced invoice deleted");
    assertEquals(0, countById("MetaFile", fx.blobId), "MetaFile deleted");
    assertFalse(Files.exists(fx.blobPath), "blob deleted on commit");
  }

  @Test
  void uploadDeleteDraftOcrAutoCancelledAndFullyDeleted() {
    Fixture fx = createFixture(InvoiceRepository.STATUS_DRAFT, false, true);

    inTransaction(() -> uploadRepo().remove(uploadRepo().find(fx.uploadId)));
    JPA.em().clear();

    assertEquals(0, countById("AiOcrUpload", fx.uploadId), "upload deleted");
    assertEquals(0, countById("Invoice", fx.invoiceId), "draft invoice auto-cancelled + deleted");
    assertFalse(Files.exists(fx.blobPath), "blob deleted");
  }

  @Test
  void uploadDeleteVentilatedBlocked() {
    Fixture fx = createFixture(InvoiceRepository.STATUS_VENTILATED, true, false);

    assertThrows(
        RuntimeException.class,
        () -> inTransaction(() -> uploadRepo().remove(uploadRepo().find(fx.uploadId))));
    JPA.em().clear();

    assertEquals(1, countById("AiOcrUpload", fx.uploadId), "upload untouched");
    assertEquals(1, countById("Invoice", fx.invoiceId), "ventilated invoice untouched");
    assertTrue(Files.exists(fx.blobPath), "blob untouched");
  }

  @Test
  void uploadDeleteBlockedByAccountingDependency() {
    // A paid amount is an accounting dependency → auto-cancel refused → nothing deleted.
    Fixture fx = createFixture(InvoiceRepository.STATUS_DRAFT, false, false);
    inTransaction(
        () -> {
          Invoice inv = JPA.em().find(Invoice.class, fx.invoiceId);
          inv.setAmountPaid(new BigDecimal("10.00"));
          JPA.save(inv);
        });

    assertThrows(
        RuntimeException.class,
        () -> inTransaction(() -> uploadRepo().remove(uploadRepo().find(fx.uploadId))));
    JPA.em().clear();

    assertEquals(1, countById("AiOcrUpload", fx.uploadId), "upload untouched");
    assertEquals(1, countById("Invoice", fx.invoiceId), "invoice with paid amount untouched");
    assertTrue(Files.exists(fx.blobPath), "blob untouched");
  }

  @Test
  void uploadDeleteKeepsSharedFile() {
    Fixture fx = createFixture(InvoiceRepository.STATUS_CANCELED, false, false);
    long[] otherUploadId = new long[1];
    inTransaction(
        () -> {
          AiOcrUpload other = new AiOcrUpload();
          other.setOriginalFile(JPA.em().find(MetaFile.class, fx.blobId));
          otherUploadId[0] = JPA.save(other).getId();
        });

    inTransaction(() -> uploadRepo().remove(uploadRepo().find(fx.uploadId)));
    JPA.em().clear();

    assertEquals(0, countById("AiOcrUpload", fx.uploadId), "linked upload deleted");
    assertEquals(1, countById("AiOcrUpload", otherUploadId[0]), "other upload survives");
    assertEquals(1, countById("MetaFile", fx.blobId), "shared MetaFile kept");
    assertTrue(Files.exists(fx.blobPath), "shared blob kept");
  }

  @Test
  void uploadDeleteRollbackRestoresFilesAndRows() {
    Fixture fx = createFixture(InvoiceRepository.STATUS_CANCELED, false, false);

    try {
      inTransaction(
          () -> {
            uploadRepo().remove(uploadRepo().find(fx.uploadId));
            throw new IllegalStateException("boom — force rollback");
          });
      fail("expected rollback");
    } catch (RuntimeException expected) {
      // expected
    }
    JPA.em().clear();

    assertEquals(1, countById("AiOcrUpload", fx.uploadId), "upload kept on rollback");
    assertEquals(1, countById("Invoice", fx.invoiceId), "invoice kept on rollback");
    assertEquals(1, countById("MetaFile", fx.blobId), "MetaFile kept on rollback");
    assertTrue(Files.exists(fx.blobPath), "blob restored on rollback");
  }
}
