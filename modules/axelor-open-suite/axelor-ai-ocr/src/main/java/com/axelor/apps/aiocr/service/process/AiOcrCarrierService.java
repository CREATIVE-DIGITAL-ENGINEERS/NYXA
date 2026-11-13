package com.axelor.apps.aiocr.service.process;

import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.List;
import javax.inject.Inject;
import javax.persistence.LockModeType;
import javax.persistence.LockTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Recomputes a ZIP carrier's status + progress <b>from database queries over its child records</b>
 * (never from worker-updated counters, which would race). One carrier = one uploaded ZIP; its
 * children share {@code importArchive = carrier}.
 *
 * <p>Lifecycle: {@code extracting → processing → completed | completedWithIssues | failed}.
 * Progress reaches 100% only when extraction has finished AND every child is in a terminal state.
 */
@Singleton
public class AiOcrCarrierService {

  private static final Logger LOG = LoggerFactory.getLogger(AiOcrCarrierService.class);

  /** How many times a lock timeout is retried before the rollup is given up on. */
  protected static final int LOCK_ATTEMPTS = 3;

  protected final AiOcrUploadRepository uploadRepo;

  @Inject
  public AiOcrCarrierService(AiOcrUploadRepository uploadRepo) {
    this.uploadRepo = uploadRepo;
  }

  /**
   * Recompute + persist the carrier status from its child records, via a version-free bulk UPDATE.
   *
   * <p>Persisting with a bulk UPDATE (not an entity save) is deliberate: several child workers can
   * finish and call this concurrently, and a Refresh may read at the same time — an entity save
   * would raise "Batch update returned unexpected row count … id=?/version=?" optimistic-lock
   * errors. The bulk UPDATE carries no version and simply writes the latest computed state.
   *
   * <p>Child-record rollup is kept SEPARATE from a PDF carrier's OCR-page progress: for a split-PDF
   * carrier the progress bar is owned by the OCR phase (pages processed / total pages, persisted
   * per Azure chunk), so this only updates its STATUS. A ZIP carrier has no OCR-page phase, so its
   * bar IS the child-based percent.
   *
   * <p><b>The bulk UPDATE is followed by a refresh of the loaded carrier</b>, and that is not
   * cosmetic. A bulk UPDATE writes the row without touching the persistence context, so the
   * already-managed instance keeps the values it was loaded with — and Axelor entities are not
   * {@code @DynamicUpdate}, so ANY later {@code save} of that same instance (dirtied by an
   * unrelated field such as {@code extractionInformation}) emits a full-column UPDATE that silently
   * reverts the status and the percent computed here. That is exactly how ZIP carriers whose
   * children were all created already-terminal ended up pinned at {@code extracting} with a null
   * percent: the rollup ran, then the archive-reclaim note was appended and saved on the stale
   * instance. Re-reading the row here means there is no stale copy left to write back, so the
   * rollup cannot be undone by a later save regardless of call order.
   */
  @Transactional
  public void recompute(Long carrierId) {
    AiOcrUpload carrier = lockForRollup(carrierId);
    if (carrier == null) {
      return;
    }
    // One GROUP BY round trip instead of three separate COUNT(*) queries — this rollup runs on
    // every single child completion (and recurses to the parent), so for a large ZIP the query
    // count would otherwise grow with child count squared over the carrier's lifetime.
    java.util.Map<String, Long> counts = countsByStatus(carrier);
    long total = counts.values().stream().mapToLong(Long::longValue).sum();
    long terminal =
        sumCounts(
            counts,
            AiOcrUploadStatus.COMPLETED,
            AiOcrUploadStatus.FAILED,
            AiOcrUploadStatus.DUPLICATE,
            AiOcrUploadStatus.UNSUPPORTED,
            AiOcrUploadStatus.CANCELLED,
            // A child whose invoice/move was created but is incomplete is 'needsReview'; OCR
            // processing IS done for it, so it counts as terminal for the carrier rollup.
            AiOcrUploadStatus.NEEDS_REVIEW,
            // A child can itself be a carrier (a ZIP-extracted PDF that turned out to hold
            // multiple invoices, nested one level under this ZIP) — once IT has settled into
            // 'archive' (clean) or 'completedWithIssues' (see the override below), it is done
            // and must count as terminal here too, or this carrier's rollup never advances past
            // its nested child.
            AiOcrUploadStatus.ARCHIVE,
            AiOcrUploadStatus.COMPLETED_WITH_ISSUES);
    long problematic = sumCounts(counts, AiOcrUploadStatus.FAILED, AiOcrUploadStatus.UNSUPPORTED);
    // A direct child can itself be a finished (ARCHIVE) nested carrier — its own children never
    // appear in the counts above, so a problem buried inside it would otherwise be invisible here.
    // Only checked when nothing already flagged this carrier, since computeStatus only cares
    // whether problematic > 0.
    if (problematic == 0 && hasProblematicNestedDescendant(carrier)) {
      problematic = 1;
    }
    boolean finished = carrier.getExtractionFinishedOn() != null;

    String status = computeStatus(total, terminal, problematic, finished);
    // The carrier is the archive record itself: once fully processed AND CLEAN it is kept as an
    // "Archive". A carrier that finished WITH issues must not be silently collapsed into that same
    // plain "Archive" value — computeStatus already returned 'completedWithIssues' for it above, so
    // this only overrides the clean case, leaving that value in place otherwise.
    if (finished && total > 0 && terminal >= total && problematic == 0) {
      status = AiOcrUploadStatus.ARCHIVE;
    }

    boolean pdfCarrier =
        AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT.equals(carrier.getCarrierKind())
            || AiOcrUploadStatus.CARRIER_KIND_PDF_BANK_STATEMENT.equals(carrier.getCarrierKind());
    if (pdfCarrier) {
      JPA.em()
          .createQuery("UPDATE AiOcrUpload s SET s.statusSelect = :st WHERE s.id = :id")
          .setParameter("st", status)
          .setParameter("id", carrierId)
          .executeUpdate();
    } else {
      JPA.em()
          .createQuery(
              "UPDATE AiOcrUpload s SET s.statusSelect = :st, s.progressPercent = :p "
                  + "WHERE s.id = :id")
          .setParameter("st", status)
          .setParameter("p", computePercent(total, terminal, finished))
          .setParameter("id", carrierId)
          .executeUpdate();
    }

    // Drop the now-stale in-memory copy of the two columns just written by the bulk UPDATE (see the
    // class-level note): after this, a later save of `carrier` writes back the rollup, never the
    // pre-rollup values it was loaded with.
    JPA.em().refresh(carrier);

    // This carrier can itself be nested under a ZIP (a ZIP-extracted PDF that turned out to hold
    // multiple invoices). The parent's own rollup only ever hears about ITS children directly, so
    // once this carrier's own children move it forward, the parent must be recomputed too — or a
    // nested carrier settling into 'archive' would leave the ZIP parent stuck forever. Bounded:
    // importArchive is fixed at child-creation time to a pre-existing row (no cycle), and a
    // pdfSplit/bank-statement carrier's own children get segmentType set, which blocks them from
    // ever becoming carriers themselves — so this recurses at most one extra level. Routed through
    // Beans.get(...), not a direct self-call: a Guice @Transactional interceptor only wraps calls
    // made through the injected proxy, never a same-instance `this` call.
    if (carrier.getImportArchive() != null) {
      Beans.get(AiOcrCarrierService.class).recompute(carrier.getImportArchive().getId());
    }
  }

  /**
   * Load the carrier for the rollup under a row lock, so two children finishing at the same instant
   * cannot both compute a status from a half-committed picture of their siblings.
   *
   * <p><b>This is the fix for the stuck ZIP carrier.</b> The rollup is purely event-driven: a child
   * finishing calls {@code recomputeCarrierForDoc}, which recomputes its carrier and then recurses
   * to the carrier's own parent. Each of those runs in its own transaction, and under READ
   * COMMITTED a transaction cannot see a sibling's not-yet-committed status. When three nested PDF
   * carriers settled within ~150 ms of each other, every one of the racing rollups of the ZIP above
   * them saw at least one sibling as still non-terminal, so each computed {@code processing} — and
   * because the rollup only ever runs as a reaction to a child finishing, <b>nothing was left to
   * correct it afterwards</b>. The ZIP stayed at {@code processing}/66% permanently.
   *
   * <p>A {@code PESSIMISTIC_WRITE} lock serializes the rollups of one carrier: the second rollup
   * blocks until the first commits and then re-reads the children, so the last one to run always
   * sees every sibling's committed state. Deadlock-free by lock ordering — the recursion always
   * goes child → parent, never the reverse, so two threads can never hold each other's next lock.
   *
   * <p><b>There is deliberately no unlocked fallback.</b> Reading unlocked after a failed lock
   * would compute the rollup from exactly the half-committed picture this method exists to prevent
   * — quietly reintroducing the stuck rollup for precisely the concurrent case that triggers it. A
   * {@link LockTimeoutException} is retried a bounded number of times (the JPA spec guarantees it
   * does NOT mark the transaction for rollback); anything else — notably {@code
   * PessimisticLockException}, which does doom the transaction — propagates at once.
   *
   * <p>Propagating is safe because the rollup is derived state, not work: the child's own
   * processing has already committed in its own transaction, the caller logs and moves on (see
   * {@code AiOcrProcessingExecutor.recomputeCarrierQuietly}), and the carrier is simply left
   * non-terminal — which {@code reconcileUnfinalizedCarriers} finalizes at the next startup. A
   * failed lock therefore costs a delayed finalization, never a wrong one.
   */
  protected AiOcrUpload lockForRollup(Long carrierId) {
    LockTimeoutException lastTimeout = null;
    for (int attempt = 1; attempt <= LOCK_ATTEMPTS; attempt++) {
      try {
        return findLocked(carrierId);
      } catch (LockTimeoutException e) {
        lastTimeout = e;
        LOG.debug("Lock attempt {} on carrier {} timed out.", attempt, carrierId);
        pauseBeforeLockRetry(attempt);
      }
    }
    throw new IllegalStateException(
        "Could not lock carrier "
            + carrierId
            + " to recompute its status after "
            + LOCK_ATTEMPTS
            + " attempts; it is left unfinalized for startup reconciliation.",
        lastTimeout);
  }

  /** One locked read. Isolated seam so the retry loop above can be exercised in a test. */
  protected AiOcrUpload findLocked(Long carrierId) {
    return JPA.em().find(AiOcrUpload.class, carrierId, LockModeType.PESSIMISTIC_WRITE);
  }

  /** Short pause between lock attempts. Overridable so tests never actually sleep. */
  protected void pauseBeforeLockRetry(int attempt) {
    try {
      Thread.sleep(50L * attempt);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting to lock a carrier.", e);
    }
  }

  /**
   * True when a direct ARCHIVE child (a finished nested carrier) has, among ITS OWN descendants,
   * anything that would be an issue if it were visible at this level: {@code FAILED}/{@code
   * UNSUPPORTED} (the same problematic statuses used above) or {@code NEEDS_REVIEW}. NEEDS_REVIEW
   * is not counted as problematic for a DIRECT child (its invoice/move already exists and is
   * separately visible/actionable), but nesting hides it behind a single collapsed ARCHIVE status
   * one level up, so it must still surface as "issues" on the ZIP that contains it.
   *
   * <p>Recurses one level per nested carrier found; in the currently supported shape (a
   * pdfSplit/bank-statement carrier's own children carry {@code segmentType} and can never
   * themselves become carriers) this never goes deeper than one extra hop, but is written
   * recursively so it stays correct if that ever changes.
   */
  protected boolean hasProblematicNestedDescendant(AiOcrUpload carrier) {
    List<AiOcrUpload> nestedCarriers =
        uploadRepo
            .all()
            .filter("self.importArchive = :carrier AND self.statusSelect = :archive")
            .bind("carrier", carrier)
            .bind("archive", AiOcrUploadStatus.ARCHIVE)
            .fetch();
    for (AiOcrUpload nested : nestedCarriers) {
      long issues =
          countChildren(
              nested,
              new String[] {
                AiOcrUploadStatus.FAILED,
                AiOcrUploadStatus.UNSUPPORTED,
                AiOcrUploadStatus.NEEDS_REVIEW
              });
      if (issues > 0 || hasProblematicNestedDescendant(nested)) {
        return true;
      }
    }
    return false;
  }

  protected long countChildren(AiOcrUpload carrier, String[] statuses) {
    StringBuilder f = new StringBuilder("self.importArchive = :carrier");
    if (statuses != null && statuses.length > 0) {
      f.append(" AND self.statusSelect IN :statuses");
    }
    com.axelor.db.Query<AiOcrUpload> q =
        uploadRepo.all().filter(f.toString()).bind("carrier", carrier);
    if (statuses != null && statuses.length > 0) {
      q = q.bind("statuses", java.util.Arrays.asList(statuses));
    }
    return q.count();
  }

  /** Direct children grouped by status, in one query — the basis for the rollup's three counts. */
  protected java.util.Map<String, Long> countsByStatus(AiOcrUpload carrier) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT s.statusSelect, COUNT(s) FROM AiOcrUpload s "
                    + "WHERE s.importArchive = :carrier GROUP BY s.statusSelect",
                Object[].class)
            .setParameter("carrier", carrier)
            .getResultList();
    java.util.Map<String, Long> counts = new java.util.HashMap<>();
    for (Object[] row : rows) {
      counts.put((String) row[0], (Long) row[1]);
    }
    return counts;
  }

  protected static long sumCounts(java.util.Map<String, Long> counts, String... statuses) {
    long sum = 0;
    for (String status : statuses) {
      sum += counts.getOrDefault(status, 0L);
    }
    return sum;
  }

  // ---------------------------------------------------------------------------
  // Pure helpers (unit-tested)
  // ---------------------------------------------------------------------------

  /**
   * Carrier status from child counts. {@code total} = all children; {@code terminal} = children in
   * a terminal state; {@code problematic} = failed + unsupported; {@code finished} = extraction
   * done.
   */
  public static String computeStatus(
      long total, long terminal, long problematic, boolean finished) {
    if (!finished) {
      return AiOcrUploadStatus.EXTRACTING;
    }
    if (total == 0) {
      return AiOcrUploadStatus.FAILED; // extraction finished but produced nothing usable
    }
    if (terminal < total) {
      return AiOcrUploadStatus.PROCESSING;
    }
    return problematic > 0 ? AiOcrUploadStatus.COMPLETED_WITH_ISSUES : AiOcrUploadStatus.COMPLETED;
  }

  /** Progress %, 0..100. Reaches 100 only when extraction finished AND all children terminal. */
  public static int computePercent(long total, long terminal, boolean finished) {
    if (total <= 0) {
      return finished ? 100 : 0;
    }
    if (finished && terminal >= total) {
      return 100;
    }
    int raw = (int) Math.floor(100.0 * terminal / total);
    return Math.min(raw, 99); // never show 100 until truly done
  }
}
