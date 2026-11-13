package com.axelor.apps.aiocr.service.storage;

import com.axelor.db.JPA;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether one {@link MetaFile} may be shared with one more record instead of being copied.
 *
 * <h2>The invariant, and why it is checked rather than assumed</h2>
 *
 * A shared MetaFile may have at most <b>one</b> DMS-attached consumer. The platform's attachment
 * bookkeeping is owner-blind in two places: {@code DMSFileRepository.save} deletes <i>any</i>
 * existing {@code MetaAttachment} for a file ("remove old attachment if file is moved") regardless
 * of which record owns it, and {@code DMSFileRepository.remove} matches the attachment to clean up
 * on {@code metaFile} alone. Attaching one file to two records therefore corrupts the attachment
 * rows of whichever record was attached first.
 *
 * <p>Plain foreign-key holders ({@code AiOcrUpload.originalFile}, the receipt fields, {@code
 * BankStatement.bankStatementFile}) create no {@code MetaAttachment} and are unaffected — one DMS
 * consumer plus any number of FK holders is safe, which is exactly the sharing this enables.
 *
 * <p>Both holder kinds are checked independently: the platform can leave an orphan {@code
 * MetaAttachment} with no matching {@code DMSFile}, and that orphan alone must block sharing.
 */
@Singleton
public class OcrFileSharingService {

  private static final Logger LOG = LoggerFactory.getLogger(OcrFileSharingService.class);

  /**
   * True when {@code file} can be attached to {@code (ownerModel, ownerId)} without creating a
   * second DMS consumer — i.e. no {@code DMSFile} and no {@code MetaAttachment} for this file is
   * owned by any <b>other</b> record. Fail-closed: any query problem answers {@code false}, so the
   * caller falls back to an independent copy rather than risking the invariant.
   */
  public boolean canShareWith(MetaFile file, String ownerModel, Long ownerId) {
    if (file == null || file.getId() == null || ownerModel == null || ownerId == null) {
      return false;
    }
    try {
      return foreignDmsHolders(file, ownerModel, ownerId) == 0
          && foreignAttachmentHolders(file, ownerModel, ownerId) == 0;
    } catch (RuntimeException e) {
      LOG.warn(
          "Could not establish whether MetaFile id={} is safe to share with {}#{}: {}."
              + " Falling back to an independent copy.",
          file.getId(),
          ownerModel,
          ownerId,
          e.getMessage());
      return false;
    }
  }

  /** DMSFile rows holding this file for a record OTHER than the intended owner. */
  protected long foreignDmsHolders(MetaFile file, String ownerModel, Long ownerId) {
    Long n =
        JPA.em()
            .createQuery(
                "SELECT COUNT(d) FROM DMSFile d WHERE d.metaFile = :file"
                    + " AND NOT (d.relatedModel = :model AND d.relatedId = :id)",
                Long.class)
            .setParameter("file", file)
            .setParameter("model", ownerModel)
            .setParameter("id", ownerId)
            .getSingleResult();
    return n == null ? 0 : n;
  }

  /** MetaAttachment rows holding this file for a record OTHER than the intended owner. */
  protected long foreignAttachmentHolders(MetaFile file, String ownerModel, Long ownerId) {
    Long n =
        JPA.em()
            .createQuery(
                "SELECT COUNT(a) FROM MetaAttachment a WHERE a.metaFile = :file"
                    + " AND NOT (a.objectName = :model AND a.objectId = :id)",
                Long.class)
            .setParameter("file", file)
            .setParameter("model", ownerModel)
            .setParameter("id", ownerId)
            .getSingleResult();
    return n == null ? 0 : n;
  }
}
