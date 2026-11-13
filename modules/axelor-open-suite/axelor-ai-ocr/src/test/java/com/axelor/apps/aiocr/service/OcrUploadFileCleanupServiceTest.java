package com.axelor.apps.aiocr.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoicePayment;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.PaymentSchedule;
import com.axelor.apps.account.db.SubrogationRelease;
import com.axelor.apps.account.db.repo.InvoicePaymentRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.base.AxelorException;
import com.axelor.auth.db.User;
import com.axelor.meta.db.MetaFile;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic unit tests for the OCR invoice-delete cleanup: canceled gate, recursive/cycle-safe
 * deletion-set traversal, candidate-file dedup, and the reference-count decision. No DB/Guice.
 */
class OcrUploadFileCleanupServiceTest {

  private static AiOcrUpload upload(long id) {
    AiOcrUpload u = mock(AiOcrUpload.class);
    lenient().when(u.getId()).thenReturn(id);
    return u;
  }

  private static MetaFile file(Long id) {
    MetaFile f = mock(MetaFile.class);
    lenient().when(f.getId()).thenReturn(id);
    return f;
  }

  private static Function<AiOcrUpload, List<AiOcrUpload>> graph(
      Map<AiOcrUpload, List<AiOcrUpload>> children) {
    return u -> children.getOrDefault(u, Collections.emptyList());
  }

  // --- wasVentilated: tax-compliance hard stop ---

  @Test
  void wasVentilatedByCurrentStatus() {
    Invoice ventilated = mock(Invoice.class);
    when(ventilated.getStatusSelect()).thenReturn(InvoiceRepository.STATUS_VENTILATED);
    assertTrue(OcrUploadFileCleanupService.wasVentilated(ventilated));
  }

  @Test
  void wasVentilatedByTimestampEvenAfterCancel() {
    // Canceled AFTER ventilation must still be blocked (cancel-then-delete fraud).
    Invoice canceledAfterVentilation = mock(Invoice.class);
    when(canceledAfterVentilation.getStatusSelect()).thenReturn(InvoiceRepository.STATUS_CANCELED);
    when(canceledAfterVentilation.getVentilatedDateTime()).thenReturn(LocalDateTime.now());
    assertTrue(OcrUploadFileCleanupService.wasVentilated(canceledAfterVentilation));
  }

  @Test
  void wasVentilatedByPostedMoveOrVentilatedByUser() {
    Invoice byMove = mock(Invoice.class);
    when(byMove.getStatusSelect()).thenReturn(InvoiceRepository.STATUS_CANCELED);
    when(byMove.getMove()).thenReturn(mock(Move.class)); // posted move exists → was ventilated
    assertTrue(OcrUploadFileCleanupService.wasVentilated(byMove));

    Invoice byUser = mock(Invoice.class);
    when(byUser.getStatusSelect()).thenReturn(InvoiceRepository.STATUS_DRAFT);
    when(byUser.getVentilatedByUser()).thenReturn(mock(User.class));
    assertTrue(OcrUploadFileCleanupService.wasVentilated(byUser));
  }

  @Test
  void notVentilatedForDraftOrNull() {
    Invoice draft = mock(Invoice.class);
    when(draft.getStatusSelect()).thenReturn(InvoiceRepository.STATUS_DRAFT);
    assertFalse(OcrUploadFileCleanupService.wasVentilated(draft));
    assertFalse(OcrUploadFileCleanupService.wasVentilated(null));
  }

  // --- assertSafeToAutoCancel: refuse silent cancel when accounting dependencies exist ---

  private OcrUploadFileCleanupService bareService() {
    return new OcrUploadFileCleanupService(null, null, null, null, null, null, null, null, null);
  }

  private OcrUploadFileCleanupService serviceWith(InvoiceService invoiceService) {
    return new OcrUploadFileCleanupService(
        null, null, invoiceService, null, null, null, null, null, null);
  }

  private Invoice cleanDraft() {
    Invoice inv = mock(Invoice.class);
    lenient().when(inv.getStatusSelect()).thenReturn(InvoiceRepository.STATUS_DRAFT);
    return inv;
  }

  @Test
  void autoCancelAllowedForCleanDraft() {
    assertDoesNotThrow(() -> bareService().assertSafeToAutoCancel(cleanDraft()));
  }

  @Test
  void autoCancelRefusedForMove() {
    Invoice inv = cleanDraft();
    when(inv.getMove()).thenReturn(mock(Move.class));
    assertThrows(PersistenceException.class, () -> bareService().assertSafeToAutoCancel(inv));
  }

  @Test
  void autoCancelRefusedForPaymentMove() {
    Invoice inv = cleanDraft();
    when(inv.getPaymentMove()).thenReturn(mock(Move.class));
    assertThrows(PersistenceException.class, () -> bareService().assertSafeToAutoCancel(inv));
  }

  @Test
  void autoCancelRefusedForPaymentScheduleOrSubrogation() {
    Invoice sched = cleanDraft();
    when(sched.getPaymentSchedule()).thenReturn(mock(PaymentSchedule.class));
    assertThrows(PersistenceException.class, () -> bareService().assertSafeToAutoCancel(sched));

    Invoice subro = cleanDraft();
    when(subro.getSubrogationRelease()).thenReturn(mock(SubrogationRelease.class));
    assertThrows(PersistenceException.class, () -> bareService().assertSafeToAutoCancel(subro));
  }

  @Test
  void autoCancelRefusedForPaidAmount() {
    Invoice inv = cleanDraft();
    when(inv.getAmountPaid()).thenReturn(new BigDecimal("10.00"));
    assertThrows(PersistenceException.class, () -> bareService().assertSafeToAutoCancel(inv));
  }

  @Test
  void autoCancelRefusedForValidatedPayment() {
    Invoice inv = cleanDraft();
    InvoicePayment p = mock(InvoicePayment.class);
    when(p.getStatusSelect()).thenReturn(InvoicePaymentRepository.STATUS_VALIDATED);
    when(inv.getInvoicePaymentList()).thenReturn(List.of(p));
    assertThrows(PersistenceException.class, () -> bareService().assertSafeToAutoCancel(inv));
  }

  @Test
  void ventilatedInvoiceIsRejectedByAssertNotVentilated() {
    Invoice ventilated = mock(Invoice.class);
    when(ventilated.getStatusSelect()).thenReturn(InvoiceRepository.STATUS_VENTILATED);
    assertThrows(
        PersistenceException.class, () -> bareService().assertNotVentilated(List.of(ventilated)));
  }

  // --- autoCancelIfSafe: uses the stock InvoiceService.cancel (never mutates status directly) ---

  @Test
  void autoCancelUsesStockServiceForDraft() throws Exception {
    InvoiceService svc = mock(InvoiceService.class);
    Invoice draft = cleanDraft();
    serviceWith(svc).autoCancelIfSafe(draft);
    verify(svc).cancel(draft); // delegated to stock business logic
  }

  @Test
  void autoCancelSkipsAlreadyCanceledInvoice() throws Exception {
    InvoiceService svc = mock(InvoiceService.class);
    Invoice canceled = mock(Invoice.class);
    when(canceled.getStatusSelect()).thenReturn(InvoiceRepository.STATUS_CANCELED);
    serviceWith(svc).autoCancelIfSafe(canceled);
    verify(svc, never()).cancel(canceled);
  }

  @Test
  void autoCancelWrapsStockAxelorExceptionAndDoesNotSwallow() throws Exception {
    InvoiceService svc = mock(InvoiceService.class);
    Invoice draft = cleanDraft();
    doThrow(new AxelorException(1, "stock refused")).when(svc).cancel(draft);
    assertThrows(PersistenceException.class, () -> serviceWith(svc).autoCancelIfSafe(draft));
  }

  @Test
  void autoCancelRefusesDependencyBeforeCallingStock() throws Exception {
    InvoiceService svc = mock(InvoiceService.class);
    Invoice draft = cleanDraft();
    when(draft.getMove()).thenReturn(mock(Move.class));
    assertThrows(PersistenceException.class, () -> serviceWith(svc).autoCancelIfSafe(draft));
    verify(svc, never()).cancel(draft); // guard runs before the stock cancel
  }

  // --- deletion-set traversal ---

  @Test
  void collectsRecursiveChildrenAndNestedChildren() {
    AiOcrUpload root = upload(1);
    AiOcrUpload child = upload(2);
    AiOcrUpload grandchild = upload(3);
    Map<AiOcrUpload, List<AiOcrUpload>> g =
        Map.of(root, List.of(child), child, List.of(grandchild));
    Set<AiOcrUpload> set = OcrUploadFileCleanupService.collectDeletionSet(List.of(root), graph(g));
    assertEquals(Set.of(root, child, grandchild), set);
  }

  @Test
  void carrierCycleTerminates() {
    AiOcrUpload a = upload(1);
    AiOcrUpload b = upload(2);
    // a -> b -> a (cycle)
    Map<AiOcrUpload, List<AiOcrUpload>> g = Map.of(a, List.of(b), b, List.of(a));
    Set<AiOcrUpload> set = OcrUploadFileCleanupService.collectDeletionSet(List.of(a), graph(g));
    assertEquals(Set.of(a, b), set); // no infinite loop
  }

  @Test
  void deletionSetDedupesRowFetchedTwice() {
    // Same logical row (id=2) reachable via two parents, as distinct Java instances.
    AiOcrUpload root = upload(1);
    AiOcrUpload childA = upload(2);
    AiOcrUpload childAgain = upload(2);
    Map<AiOcrUpload, List<AiOcrUpload>> g = Map.of(root, Arrays.asList(childA, childAgain));
    Set<AiOcrUpload> set = OcrUploadFileCleanupService.collectDeletionSet(List.of(root), graph(g));
    assertEquals(2, set.size()); // root + one child (id-deduped)
  }

  // --- candidate file collection / dedup ---

  @Test
  void collectsAndDedupesOriginalAndArchiveFiles() {
    MetaFile fOrig = file(10L);
    MetaFile fArch = file(11L);
    AiOcrUpload u1 = upload(1);
    when(u1.getOriginalFile()).thenReturn(fOrig);
    when(u1.getArchiveFile()).thenReturn(fArch);
    AiOcrUpload u2 = upload(2);
    when(u2.getOriginalFile()).thenReturn(fOrig); // shared across parent/child
    when(u2.getArchiveFile()).thenReturn(null);
    Set<MetaFile> files = OcrUploadFileCleanupService.collectCandidateFiles(List.of(u1, u2));
    assertEquals(Set.of(fOrig, fArch), files); // fOrig counted once
  }

  @Test
  void duplicateOriginalEqualsArchiveCollapsesToOne() {
    MetaFile same = file(20L);
    AiOcrUpload u = upload(1);
    when(u.getOriginalFile()).thenReturn(same);
    when(u.getArchiveFile()).thenReturn(same); // originalFile == archiveFile
    Set<MetaFile> files = OcrUploadFileCleanupService.collectCandidateFiles(List.of(u));
    assertEquals(1, files.size());
  }

  @Test
  void canceledInvoiceWithNoUploadsYieldsEmptySets() {
    Set<AiOcrUpload> set =
        OcrUploadFileCleanupService.collectDeletionSet(new ArrayList<>(), graph(Map.of()));
    assertTrue(set.isEmpty());
    assertTrue(OcrUploadFileCleanupService.collectCandidateFiles(set).isEmpty());
  }

  // --- produced vs merely linked documents: a duplicate upload owns NEITHER ---

  /**
   * A DUPLICATE upload only links a document it did not create, so deleting it must delete neither
   * the Invoice nor the Move. Transient (id == null) documents are used so neither helper needs a
   * database.
   *
   * <p>Nothing enforces {@code status == DUPLICATE ⇒ move == null}: three of the four places that
   * set DUPLICATE never touch {@code move}, and the two duplicate exits in the invoice route return
   * before that method's own {@code setMove(null)}. The exclusion must therefore live here, not in
   * the callers.
   */
  @Test
  void duplicateUploadProducesNeitherInvoiceNorMove() {
    Move linkedMove = mock(Move.class);
    Invoice linkedInvoice = mock(Invoice.class);
    AiOcrUpload dup = upload(1);
    when(dup.getStatusSelect()).thenReturn("duplicate");
    lenient().when(dup.getMove()).thenReturn(linkedMove);
    lenient().when(dup.getInvoice()).thenReturn(linkedInvoice);

    // Both documents demonstrably still exist, so the exclusion is proven to come from the status
    // filter and not from an existence probe happening to fail.
    OcrUploadFileCleanupService svc = existingDocumentsService();
    assertTrue(svc.producedMoves(List.of(dup)).isEmpty(), "move must not be deleted");
    assertTrue(svc.producedInvoices(List.of(dup)).isEmpty(), "invoice must not be deleted");
  }

  @Test
  void nonDuplicateUploadProducesItsInvoiceAndMove() {
    Move ownMove = mock(Move.class);
    Invoice ownInvoice = mock(Invoice.class);
    AiOcrUpload own = upload(2);
    when(own.getStatusSelect()).thenReturn("completed");
    lenient().when(own.getMove()).thenReturn(ownMove);
    lenient().when(own.getInvoice()).thenReturn(ownInvoice);

    // The existence probes are the only DB access on these paths; stub them so this stays a unit
    // test (the point here is the status filter, not whether the row survives).
    OcrUploadFileCleanupService svc = existingDocumentsService();
    assertEquals(Set.of(ownMove), svc.producedMoves(List.of(own)));
    assertEquals(Set.of(ownInvoice), svc.producedInvoices(List.of(own)));
  }

  /** Bare service whose Invoice/Move existence probes answer "still there" without a database. */
  private OcrUploadFileCleanupService existingDocumentsService() {
    return new OcrUploadFileCleanupService(null, null, null, null, null, null, null, null, null) {
      @Override
      protected boolean invoiceExists(Long id) {
        return true;
      }

      @Override
      protected boolean moveExists(Long id) {
        return true;
      }
    };
  }

  // NB: shared-file protection used to be the pure safeToDelete(upload, attachment, dms) rule.
  // It is now OcrMetaFileReferenceScanner.referencesOf — every mapped relation, fail-closed —
  // and is covered by OcrMetaFilesGuardTest in the storage package (where Relation/FileReferences
  // are constructible).

  // --- document-scoped deletion: protection belongs to one document, never to a family ---

  /** A ZIP carrier (1) holding a ventilated child (2) and a plain draft child (3). */
  private static final class Archive {
    final AiOcrUpload carrier = upload(1L);
    final AiOcrUpload ventilatedChild = upload(2L);
    final AiOcrUpload draftChild = upload(3L);
    final Function<AiOcrUpload, List<AiOcrUpload>> tree =
        graph(Map.of(carrier, Arrays.asList(ventilatedChild, draftChild)));

    /** Stands in for evaluateProtections' per-upload database re-read. */
    String protectionOf(AiOcrUpload u) {
      if (u == ventilatedChild) {
        return OcrUploadFileCleanupService.protectionReason(true, "0000000060", false, null);
      }
      return OcrUploadFileCleanupService.protectionReason(false, null, false, null);
    }

    List<String> protectionsIn(Set<AiOcrUpload> scope) {
      List<String> found = new ArrayList<>();
      for (AiOcrUpload u : scope) {
        String reason = protectionOf(u);
        if (reason != null) {
          found.add(reason);
        }
      }
      return found;
    }
  }

  @Test
  void aVentilatedChildDoesNotBlockDeletingItsDraftSibling() {
    Archive a = new Archive();

    // The scope of deleting ONE child is that child alone: not the carrier, not the sibling.
    Set<AiOcrUpload> scope =
        OcrUploadFileCleanupService.collectDeletionSet(List.of(a.draftChild), a.tree);
    assertEquals(Set.of(a.draftChild), scope);
    assertFalse(scope.contains(a.carrier), "a child delete must not reach its carrier");
    assertFalse(scope.contains(a.ventilatedChild), "a child delete must not reach a sibling");

    // So the sibling's ventilation is not even evaluated, and the delete proceeds.
    assertTrue(a.protectionsIn(scope).isEmpty());
  }

  @Test
  void deletingTheProtectedChildItselfIsRefused() {
    Archive a = new Archive();
    Set<AiOcrUpload> scope =
        OcrUploadFileCleanupService.collectDeletionSet(List.of(a.ventilatedChild), a.tree);
    assertEquals(Set.of(a.ventilatedChild), scope);

    List<String> refs = a.protectionsIn(scope);
    assertEquals(1, refs.size());
    assertTrue(refs.get(0).contains("ventilated"), refs.get(0));

    String message = OcrUploadFileCleanupService.refusalMessage("invoice-b.pdf", true, refs);
    assertTrue(message.contains("cannot be deleted"), message);
    assertTrue(message.contains("0000000060"), message);
    assertTrue(message.contains("Nothing was deleted"), message);
  }

  @Test
  void anUnprotectedTreeStillDeletesCompletely() {
    AiOcrUpload carrier = upload(10L);
    AiOcrUpload a = upload(11L);
    AiOcrUpload b = upload(12L);
    AiOcrUpload nested = upload(13L); // a child that is itself a carrier
    Function<AiOcrUpload, List<AiOcrUpload>> tree =
        graph(Map.of(carrier, Arrays.asList(a, b), b, List.of(nested)));

    Set<AiOcrUpload> scope = OcrUploadFileCleanupService.collectDeletionSet(List.of(carrier), tree);
    assertEquals(Set.of(carrier, a, b, nested), scope, "the whole tree is still collected");

    // Nothing protected anywhere -> no refusal, so the original complete-tree delete runs.
    for (AiOcrUpload u : scope) {
      assertNull(OcrUploadFileCleanupService.protectionReason(false, null, false, null), "" + u);
    }
  }

  @Test
  void aCarrierWithProtectedDescendantsIsNeverReportedAsFullyDeleted() {
    Archive a = new Archive();
    Set<AiOcrUpload> scope =
        OcrUploadFileCleanupService.collectDeletionSet(List.of(a.carrier), a.tree);
    assertEquals(Set.of(a.carrier, a.ventilatedChild, a.draftChild), scope);

    List<String> refs = new ArrayList<>();
    refs.add(
        new OcrUploadFileCleanupService.ProtectedUpload(
                2L, "invoice-b.pdf", a.protectionOf(a.ventilatedChild))
            .describeRef());
    assertFalse(refs.isEmpty(), "the descendant is protected, so the carrier delete must refuse");

    // rootProtected = false: the carrier itself is fine, a descendant is not.
    String message = OcrUploadFileCleanupService.refusalMessage("archive.zip", false, refs);
    assertTrue(message.contains("was NOT deleted"), message);
    assertTrue(message.contains("archive.zip"), message);
    assertTrue(message.contains("invoice-b.pdf"), message);
    assertTrue(message.contains("id=2"), message);
    // The crucial property: it never claims success, and it says the siblings were kept too.
    assertTrue(message.contains("Nothing was deleted"), message);
    assertFalse(message.toLowerCase().contains("deleted successfully"), message);
  }

  @Test
  void aPostedMoveProtectsOnlyItsOwnUpload() {
    assertNull(OcrUploadFileCleanupService.protectionReason(false, null, false, null));
    String reason = OcrUploadFileCleanupService.protectionReason(false, null, true, "PCH260063");
    assertTrue(reason.contains("PCH260063"), reason);
    assertTrue(reason.contains("posted"), reason);

    // Mirrors the stock guard: only new/simulated are unposted.
    assertFalse(OcrUploadFileCleanupService.isMoveProtected(MoveRepository.STATUS_NEW));
    assertFalse(OcrUploadFileCleanupService.isMoveProtected(MoveRepository.STATUS_SIMULATED));
    assertTrue(OcrUploadFileCleanupService.isMoveProtected(MoveRepository.STATUS_ACCOUNTED));
    assertTrue(OcrUploadFileCleanupService.isMoveProtected(MoveRepository.STATUS_DAYBOOK));
    assertTrue(OcrUploadFileCleanupService.isMoveProtected(null));
  }
}
