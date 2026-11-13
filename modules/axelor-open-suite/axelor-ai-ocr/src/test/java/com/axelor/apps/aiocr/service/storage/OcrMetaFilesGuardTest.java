package com.axelor.apps.aiocr.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.service.OcrUploadFileCleanupService;
import com.axelor.apps.aiocr.service.storage.OcrMetaFileReferenceScanner.FileReferences;
import com.axelor.apps.aiocr.service.storage.OcrMetaFileReferenceScanner.Relation;
import com.axelor.apps.base.db.Company;
import com.axelor.dms.db.DMSFile;
import com.axelor.meta.db.MetaAttachment;
import com.axelor.meta.db.MetaFile;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The reference guard in front of physical file deletion and in-place overwrite — the safety net
 * that makes it possible for an upload and the Invoice/Move it produced to share ONE {@link
 * MetaFile} instead of storing the same bytes twice.
 *
 * <p>Driven through the scanner's single query seam, so the whole decision table is exercised
 * without a database.
 */
class OcrMetaFilesGuardTest {

  // --- relations, named the way the real metamodel walk names them -------------------------------

  private static final Relation DMS = new Relation(DMSFile.class, "metaFile", false);
  private static final Relation ATTACH = new Relation(MetaAttachment.class, "metaFile", false);
  private static final Relation UPLOAD_ORIGINAL =
      new Relation(AiOcrUpload.class, "originalFile", false);

  /** The deprecated singular field, mapped only so this scan can see its pre-existing FK. */
  private static final Relation UPLOAD_LEGACY_RECEIPT =
      new Relation(AiOcrUpload.class, "stockReceiptFile", false);

  /**
   * A business relation owned by a DIFFERENT module — the class of reference the old hand-written
   * check could not see. The real-world instance is {@code BankStatement.bankStatementFile}, which
   * cannot be named here because ai-ocr deliberately does not depend on axelor-bank-payment (hence
   * the {@code OcrBankStatementCreator} SPI); {@code Company.logo} stands in for it and exercises
   * exactly the same code path, since the metamodel walk treats every non-carrier relation alike.
   */
  private static final Relation OTHER_MODULE_FILE = new Relation(Company.class, "logo", false);

  private static final Relation INVOICE_PRINTED = new Relation(Invoice.class, "printedPDF", false);

  private static final List<Relation> ALL =
      Arrays.asList(
          DMS, ATTACH, UPLOAD_ORIGINAL, UPLOAD_LEGACY_RECEIPT, OTHER_MODULE_FILE, INVOICE_PRINTED);

  private static MetaFile file(long id) {
    MetaFile mf = new MetaFile();
    mf.setId(id);
    mf.setFileName("invoice.pdf");
    mf.setFilePath("invoice.pdf");
    return mf;
  }

  /** A scanner whose hits and caveat are scripted; everything else is the real implementation. */
  private static class ScriptedScanner extends OcrMetaFileReferenceScanner {
    private final Set<Relation> hits = new LinkedHashSet<>();
    private String caveat;
    private Relation explodeOn;
    final List<Relation> asked = new ArrayList<>();

    ScriptedScanner holding(Relation... rs) {
      hits.addAll(Arrays.asList(rs));
      return this;
    }

    ScriptedScanner withCaveat(String reason) {
      this.caveat = reason;
      return this;
    }

    ScriptedScanner failingOn(Relation r) {
      this.explodeOn = r;
      return this;
    }

    @Override
    public List<Relation> relations() {
      return ALL;
    }

    @Override
    public String jsonReferenceCaveat() {
      return caveat;
    }

    @Override
    protected List<Long> executeRelationQuery(Relation relation, List<Long> ids) {
      asked.add(relation);
      if (relation.equals(explodeOn)) {
        throw new IllegalStateException("simulated relation failure");
      }
      return hits.contains(relation) ? ids : Collections.emptyList();
    }
  }

  /** OcrMetaFiles with the platform delegation replaced by a recorder. */
  private static class ProbeMetaFiles extends OcrMetaFiles {
    boolean deleted;
    boolean uploaded;

    ProbeMetaFiles(OcrMetaFileReferenceScanner scanner) {
      super(null, scanner);
    }

    @Override
    protected void doDelete(MetaFile metaFile) {
      deleted = true;
    }

    @Override
    protected MetaFile doUpload(File f, MetaFile metaFile) {
      uploaded = true;
      return metaFile;
    }
  }

  // --- delete: the four states ------------------------------------------------------------------

  @Test
  void noHoldersDelegatesToThePlatform() throws IOException {
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner());
    files.delete(file(1L));
    assertTrue(files.deleted, "an unreferenced file must still be deleted as it is today");
  }

  @Test
  void businessHolderRetainsAndReturnsSoTheCallersOwnDeleteCompletes() throws IOException {
    // The Invoice-delete cascade: the platform already detached the exact DMSFile + MetaAttachment,
    // and only AiOcrUpload.originalFile is left. The invoice delete must NOT be broken, and the
    // upload must keep its reprocessable source.
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner().holding(UPLOAD_ORIGINAL));
    files.delete(file(1L));
    assertFalse(files.deleted, "a file a business record still holds must be retained");
  }

  @Test
  void remainingDmsHolderIsRefused() {
    // Either the caller never identified one exact attachment (MetaService.removeAttachment gets
    // only a MetaFile id), or a second DMS consumer exists. Both must refuse, not delete.
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner().holding(DMS, UPLOAD_ORIGINAL));
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> files.delete(file(1L)));
    assertTrue(e.getMessage().contains("still attached"));
    assertFalse(files.deleted);
  }

  @Test
  void orphanAttachmentAloneIsEnoughToRefuse() {
    // The platform can leave a MetaAttachment with no matching DMSFile; that orphan must still
    // block deletion rather than be ignored.
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner().holding(ATTACH));
    assertThrows(IllegalStateException.class, () -> files.delete(file(1L)));
    assertFalse(files.deleted);
  }

  // --- fail-closed --------------------------------------------------------------------------

  @Test
  void jsonCaveatMakesTheScanUnprovenAndRetainsTheFile() throws IOException {
    ScriptedScanner scanner = new ScriptedScanner().withCaveat("2 custom JSON field(s) target it");
    assertTrue(scanner.referencesOf(file(1L)).isUnproven());
    ProbeMetaFiles files = new ProbeMetaFiles(scanner);
    files.delete(file(1L));
    assertFalse(files.deleted, "a MetaJsonField may hold the id outside any FK — never delete");
    assertTrue(scanner.asked.isEmpty(), "the caveat short-circuits before any relation is queried");
  }

  @Test
  void relationQueryFailureRetainsTheFile() throws IOException {
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner().failingOn(OTHER_MODULE_FILE));
    files.delete(file(1L));
    assertFalse(files.deleted, "an unreadable relation is never proof of an orphan");
  }

  @Test
  void legacyStockReceiptFieldProtectsTheBlob() throws IOException {
    // The pre-existing aiocr_ai_ocr_upload.stock_receipt_file column. Unmapped it was invisible to
    // the metamodel walk, and MetaFiles.delete deletes the bytes BEFORE the FK violation surfaces
    // at commit — so a rollback would restore the row but not the file.
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner().holding(UPLOAD_LEGACY_RECEIPT));
    files.delete(file(1L));
    assertFalse(files.deleted);
  }

  @Test
  void otherModuleBusinessHolderProtectsTheBlob() throws IOException {
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner().holding(OTHER_MODULE_FILE));
    files.delete(file(1L));
    assertFalse(files.deleted, "the relation the old hand-written check missed");
  }

  // --- classification: no relation type is excluded from counting -------------------------------

  @Test
  void dmsCarriersAreAskedFirstAndDecideAlone() {
    ScriptedScanner scanner = new ScriptedScanner().holding(DMS);
    FileReferences refs = scanner.referencesOf(file(1L));
    assertTrue(refs.hasDmsHolder());
    assertFalse(refs.hasOtherHolder());
    assertFalse(refs.isUnreferenced());
    // Only the two carriers were consulted: a remaining attachment is already the whole answer.
    assertEquals(Arrays.asList(DMS, ATTACH), scanner.asked);
  }

  @Test
  void businessRelationsStopAtTheFirstHolder() {
    ScriptedScanner scanner = new ScriptedScanner().holding(UPLOAD_ORIGINAL, OTHER_MODULE_FILE);
    FileReferences refs = scanner.referencesOf(file(1L));
    assertTrue(refs.hasOtherHolder());
    assertEquals(Set.of(UPLOAD_ORIGINAL), refs.getOtherHolders());
    assertFalse(scanner.asked.contains(INVOICE_PRINTED), "one holder is enough to protect");
  }

  @Test
  void everyRelationIsCountedWhenNothingHits() {
    ScriptedScanner scanner = new ScriptedScanner();
    assertTrue(scanner.referencesOf(file(1L)).isUnreferenced());
    assertEquals(ALL, scanner.asked, "proving an orphan requires asking every relation");
  }

  // --- the batched reference API ----------------------------------------------------------------
  // referencedIds / isUnreferenced(counts) are part of the scanner's reference-safety surface.
  // Their fail-closed behaviour used to be covered only indirectly, through the (now removed) OCR
  // Storage Report test. These cases assert it against the scanner directly so the coverage
  // survives the report's removal.

  @Test
  void batchScanFailsClosedSoAFailedQueryNeverManufacturesAnOrphan() {
    // The second relation throws. Every id still unresolved at that point is unprovable — not just
    // the one being examined — so none of them may be reported as unreferenced.
    ScriptedScanner scanner = new ScriptedScanner().failingOn(ATTACH);
    OcrMetaFileReferenceScanner.BatchResult r = scanner.referencedIds(Arrays.asList(1L, 2L, 3L));

    assertTrue(r.getReferenced().isEmpty());
    assertEquals(Set.of(1L, 2L, 3L), r.getUnproven(), "unresolved ids must all be unproven");
    assertTrue(r.getFailureReason().contains("could not be queried"));
  }

  @Test
  void batchScanCostsOneQueryPerRelationNotPerFile() {
    ScriptedScanner scanner = new ScriptedScanner(); // nothing holds anything
    OcrMetaFileReferenceScanner.BatchResult r =
        scanner.referencedIds(Arrays.asList(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L));

    assertTrue(r.getUnproven().isEmpty());
    assertEquals(ALL.size(), r.getQueriesExecuted(), "one query per relation for the whole batch");
    assertEquals(ALL.size(), scanner.asked.size(), "never one round of queries per file");
  }

  @Test
  void theCountOnceRuleTreatsNullAsUnknownAndRequiresEveryCountZero() {
    assertTrue(OcrMetaFileReferenceScanner.isUnreferenced(Arrays.asList(0L, 0L, 0L)));
    assertFalse(OcrMetaFileReferenceScanner.isUnreferenced(Arrays.asList(0L, 1L, 0L)));
    assertFalse(OcrMetaFileReferenceScanner.isUnreferenced(Collections.singletonList(5L)));
    // A null count is "unknown", not "zero" — but an all-null list still has no positive hit.
    assertTrue(OcrMetaFileReferenceScanner.isUnreferenced(Arrays.asList(null, 0L)));
    assertTrue(OcrMetaFileReferenceScanner.isUnreferenced(null));
  }

  // --- the cleanup service reads the same authority ---------------------------------------------

  @Test
  void cleanupServiceIsUnreferencedUsesTheFullScanAndFailsClosed() {
    assertTrue(cleanupWith(new ScriptedScanner()).isUnreferenced(file(1L)));
    assertFalse(
        cleanupWith(new ScriptedScanner().holding(OTHER_MODULE_FILE)).isUnreferenced(file(1L)));
    assertFalse(cleanupWith(new ScriptedScanner().holding(DMS)).isUnreferenced(file(1L)));
    assertFalse(
        cleanupWith(new ScriptedScanner().withCaveat("json")).isUnreferenced(file(1L)),
        "an unproven scan is never 'unreferenced'");
  }

  private static OcrUploadFileCleanupService cleanupWith(OcrMetaFileReferenceScanner scanner) {
    return new OcrUploadFileCleanupService(null, null, null, null, null, null, null, null, scanner);
  }

  // --- in-place overwrite -----------------------------------------------------------------------

  @Test
  void inPlaceOverwriteOfASharedFileIsRefused() {
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner().holding(UPLOAD_ORIGINAL));
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class, () -> files.upload(new File("new.pdf"), file(1L)));
    assertTrue(e.getMessage().contains("in place"));
    assertFalse(files.uploaded);
  }

  @Test
  void inPlaceOverwriteIsRefusedWhenTheScanIsUnproven() {
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner().withCaveat("json"));
    assertThrows(IllegalStateException.class, () -> files.upload(new File("new.pdf"), file(1L)));
    assertFalse(files.uploaded);
  }

  @Test
  void freshMetaFileUploadIsNotAnOverwriteAndPassesThrough() throws IOException {
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner().holding(DMS));
    MetaFile fresh = new MetaFile(); // no id, no filePath → not an update
    files.upload(new File("new.pdf"), fresh);
    assertTrue(files.uploaded);
  }

  @Test
  void unreferencedFileMayStillBeOverwrittenInPlace() throws IOException {
    ProbeMetaFiles files = new ProbeMetaFiles(new ScriptedScanner());
    files.upload(new File("new.pdf"), file(1L));
    assertTrue(files.uploaded, "nothing else sees these bytes — today's behaviour is preserved");
  }
}
