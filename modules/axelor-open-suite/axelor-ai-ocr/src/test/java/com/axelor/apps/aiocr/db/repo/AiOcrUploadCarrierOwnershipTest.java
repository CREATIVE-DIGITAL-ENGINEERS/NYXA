package com.axelor.apps.aiocr.db.repo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.auth.db.User;
import org.junit.jupiter.api.Test;

/**
 * Ownership of a pipeline-created record, which is what the <b>Upload Invoices</b> list scopes on.
 *
 * <p>The list used to test {@code createdBy} plus a single {@code importArchive.createdBy} arm, and
 * that reaches exactly one ancestor: in the required ZIP -> PDF carrier -> invoice child shape the
 * grandchild has no {@code createdBy} (created by a worker with no authenticated subject) and its
 * parent has none either, so both arms missed it and a completed invoice was absent from the list.
 * {@code uploadedBy} carries the uploader down instead, and {@code inheritUploaderFromCarrier}
 * re-anchors it to the parent on every save — so it holds at ANY depth rather than one.
 *
 * <p>Pure: the method only reads and writes the two entities handed to it.
 */
class AiOcrUploadCarrierOwnershipTest {

  private final AiOcrUploadManagementRepository repo = new AiOcrUploadManagementRepository();

  private static User user(long id) {
    User u = new User();
    u.setId(id);
    return u;
  }

  /** A child of {@code parent}, saved through the repository's inheritance step. */
  private AiOcrUpload childOf(AiOcrUpload parent, User postedUploader) {
    AiOcrUpload child = new AiOcrUpload();
    child.setImportArchive(parent);
    child.setUploadedBy(postedUploader);
    repo.inheritUploaderFromCarrier(child);
    return child;
  }

  @Test
  void ownershipReachesEveryDepth() {
    User uploader = user(1L);

    // The manual upload: the repository already derived this from AuthUtils.getUser().
    AiOcrUpload zip = new AiOcrUpload();
    zip.setUploadedBy(uploader);

    // Depth 2 — the extracted PDF, created by a worker with no authenticated subject.
    AiOcrUpload pdfCarrier = childOf(zip, null);
    // Depth 3 — the invoice split out of that PDF. This is the row the old domain lost.
    AiOcrUpload invoice = childOf(pdfCarrier, null);
    // Depth 4 — nothing special about three; the invariant is not depth-limited.
    AiOcrUpload deeper = childOf(invoice, null);

    assertSame(uploader, pdfCarrier.getUploadedBy(), "depth 2");
    assertSame(uploader, invoice.getUploadedBy(), "depth 3 — the record that used to vanish");
    assertSame(uploader, deeper.getUploadedBy(), "depth 4");
  }

  @Test
  void everyChildKindInheritsIncludingDuplicatesAndFailures() {
    // Duplicate / unsupported / corrupt-nested entries go through the same child factory as a real
    // document, so they must be just as visible: a ZIP that produced nothing but duplicates has to
    // be able to explain itself in the list.
    User uploader = user(4L);
    AiOcrUpload zip = new AiOcrUpload();
    zip.setUploadedBy(uploader);

    // These differ only in status downstream; ownership is decided here, before any of that.
    assertSame(uploader, childOf(zip, null).getUploadedBy());
    assertSame(uploader, childOf(zip, null).getUploadedBy());
  }

  @Test
  void aPostedUploaderOnACarrierChildIsOverwrittenWithTheCarriersOwn() {
    // uploadedBy is a writable column, so a crafted POST can set it — and such a row is excluded
    // from isManualUpload by its carrier markers, so applyManualUploadRules would never correct it.
    // Re-anchoring to the parent on every save is what makes the claim unusable.
    User owner = user(1L);
    User attacker = user(99L);
    AiOcrUpload zip = new AiOcrUpload();
    zip.setUploadedBy(owner);

    AiOcrUpload adopted = childOf(zip, attacker);

    assertSame(owner, adopted.getUploadedBy(), "the carrier decides, not the caller");
  }

  @Test
  void systemCreatedRecordsKeepTheirIntendedInvisibility() {
    // A folder-monitor import is created by the system with no uploader and no carrier. It has
    // never appeared in the client list, and must not start appearing now.
    AiOcrUpload monitored = new AiOcrUpload();
    repo.inheritUploaderFromCarrier(monitored);
    assertNull(monitored.getUploadedBy(), "a root record's uploader is not invented here");

    // Nor do its own descendants gain one: the splitter copies the carrier's (empty) uploader, and
    // nothing here invents one for them.
    assertNull(childOf(monitored, null).getUploadedBy());
  }

  @Test
  void anUploaderIsNeverErasedByACarrierThatHasNone() {
    // The rule hands ownership DOWN; it does not take it away. A carrier with no uploader has none
    // to give, and blanking the child's would un-own a row that legitimately has one — dropping it
    // out of its owner's list. This runs on EVERY save, so an overwrite here would also clear
    // uploadedBy on a plain retry/reprocess, where it is a structural field that must survive
    // (BgOcrRetryReprocessFieldPreservationIntegrationTest asserts exactly that).
    User owner = user(5L);
    AiOcrUpload uploaderlessCarrier = new AiOcrUpload();

    assertSame(owner, childOf(uploaderlessCarrier, owner).getUploadedBy());
  }

  @Test
  void identityIsByIdSoAReloadedUserIsNotTreatedAsADifferentOwner() {
    assertEquals(true, AiOcrUploadManagementRepository.sameUser(user(3L), user(3L)));
    assertEquals(false, AiOcrUploadManagementRepository.sameUser(user(3L), user(4L)));
    assertEquals(false, AiOcrUploadManagementRepository.sameUser(null, user(3L)));
    assertEquals(false, AiOcrUploadManagementRepository.sameUser(user(3L), null));
    assertEquals(true, AiOcrUploadManagementRepository.sameUser(null, null));
  }
}
