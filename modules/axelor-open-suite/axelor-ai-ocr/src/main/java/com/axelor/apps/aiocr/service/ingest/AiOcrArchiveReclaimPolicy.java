package com.axelor.apps.aiocr.service.ingest;

/**
 * Decides whether a ZIP carrier's original archive blob may be reclaimed after extraction.
 *
 * <p>Once every document inside an archive has been written out as its own durable child {@code
 * MetaFile}, the archive itself is redundant: each child is independently viewable, re-processable
 * and re-tryable from its own blob, and the carrier row keeps the file name, the content hashes and
 * the audit trail. Keeping the ZIP as well stores the same bytes twice — and because a carrier
 * whose child produced a ventilated invoice can never be deleted, that second copy is unreclaimable
 * for the life of the database.
 *
 * <p>The rule is deliberately <b>conservative and provable</b>, not best-effort: the archive is the
 * only copy of anything it holds that was <em>not</em> written out, so it is reclaimed only when
 * the extraction can account for every single entry. {@link SafeZipExtractor} makes that accounting
 * possible — every non-directory entry takes exactly one of three branches, each of which invokes a
 * handler callback ({@code onDocument} / {@code onUnsupported} / {@code onCorruptNested}), and the
 * only other way out of the loop is a controlled stop that throws. So a completed pass whose tally
 * is all-documents, with one materialised child per document, proves nothing was left behind.
 *
 * <p>Anything else keeps the ZIP and says why. In particular an <b>unsupported</b> entry (a {@code
 * .xlsx} or {@code .docx} inside the archive) creates no record and stores no bytes anywhere, so
 * the archive is genuinely its only copy — that case must never reclaim.
 *
 * <p>Pure and side-effect free so the decision can be unit-tested without Guice, JPA or a
 * filesystem.
 */
public final class AiOcrArchiveReclaimPolicy {

  private AiOcrArchiveReclaimPolicy() {}

  /**
   * What one extraction pass observed, accumulated from the {@link SafeZipExtractor.EntryHandler}
   * callbacks. Not thread-safe: one extraction runs on one thread.
   */
  public static final class Tally {

    private int documentEntries;
    private int unsupportedEntries;
    private int corruptNestedEntries;
    private String firstUnsupportedPath;
    private String firstCorruptPath;
    private String stopReason;

    /** A supported document entry was handed to the handler for materialisation. */
    public void recordDocument() {
      documentEntries++;
    }

    /** An entry the pipeline does not OCR. No record is created and no bytes are kept. */
    public void recordUnsupported(String path) {
      unsupportedEntries++;
      if (firstUnsupportedPath == null) {
        firstUnsupportedPath = path;
      }
    }

    /** A nested archive that could not be opened; its contents were never seen. */
    public void recordCorruptNested(String path) {
      corruptNestedEntries++;
      if (firstCorruptPath == null) {
        firstCorruptPath = path;
      }
    }

    /**
     * A controlled whole-archive stop (a tripped ZIP guard, an unsafe path, an I/O failure).
     * Entries after this point were never enumerated, so the archive must be kept.
     */
    public void recordStop(String reason) {
      if (stopReason == null) {
        stopReason = (reason == null || reason.trim().isEmpty()) ? "extraction stopped" : reason;
      }
    }

    public int getDocumentEntries() {
      return documentEntries;
    }

    public int getUnsupportedEntries() {
      return unsupportedEntries;
    }

    public int getCorruptNestedEntries() {
      return corruptNestedEntries;
    }

    public String getFirstUnsupportedPath() {
      return firstUnsupportedPath;
    }

    public String getFirstCorruptPath() {
      return firstCorruptPath;
    }

    public String getStopReason() {
      return stopReason;
    }
  }

  /** Whether to reclaim, and — when not — the reason to record on the carrier. */
  public static final class Decision {

    private final boolean reclaim;
    private final String reason;

    private Decision(boolean reclaim, String reason) {
      this.reclaim = reclaim;
      this.reason = reason;
    }

    static Decision reclaim() {
      return new Decision(true, null);
    }

    static Decision retain(String reason) {
      return new Decision(false, reason);
    }

    public boolean isReclaim() {
      return reclaim;
    }

    /** Why the archive was kept; {@code null} when it is being reclaimed. */
    public String getReason() {
      return reason;
    }
  }

  /**
   * Why one child cannot be counted as durably stored, or {@code null} when it can.
   *
   * <p>Pure: the caller resolves the facts through the platform's own storage abstraction ({@code
   * MetaFiles.getPath}) and passes them in, so this is testable without a filesystem and stays
   * correct on a mounted Azure File Share — no path is constructed or assumed here.
   *
   * @param label how to name this child in the reason (its file name or id)
   * @param childPresent the child row still exists on re-read
   * @param metaFilePresent the child has a {@code MetaFile} row that still resolves
   * @param pathResolved the storage layer returned a location for that MetaFile
   * @param regularFileExists that location exists and is a regular file
   * @param sizeBytes its size; a zero-byte blob is not a stored document
   */
  public static String childStorageFailure(
      String label,
      boolean childPresent,
      boolean metaFilePresent,
      boolean pathResolved,
      boolean regularFileExists,
      long sizeBytes) {

    String name = describe(label);
    if (!childPresent) {
      return "document \"" + name + "\" is no longer in the database";
    }
    if (!metaFilePresent) {
      return "document \"" + name + "\" holds no file of its own (a duplicate or failed entry)";
    }
    if (!pathResolved) {
      return "the storage location of document \"" + name + "\" could not be resolved";
    }
    if (!regularFileExists) {
      // The MetaFile row exists but its blob does not: the row is NOT proof of durability.
      return "the stored file for document \"" + name + "\" is missing from storage";
    }
    if (sizeBytes <= 0L) {
      return "the stored file for document \"" + name + "\" is empty";
    }
    return null;
  }

  /**
   * @param archivePresent whether the carrier still holds an archive blob. {@code false} makes this
   *     a no-op rather than an error, so a repeated completion (a re-run of a already-reclaimed
   *     carrier) is safe and idempotent.
   * @param tally what the extraction pass observed; {@code null} is treated as "nothing proven"
   * @param childCount children of this carrier in the database
   * @param verifiedChildCount children proven durably stored — the row re-read, its MetaFile
   *     present, and its physical blob confirmed to be a non-empty regular file. A {@code
   *     duplicate} child holds no file of its own, so it never counts here.
   * @param firstStorageFailure the first {@link #childStorageFailure} reason, or a message from a
   *     verification that threw; {@code null} when every child verified
   */
  public static Decision decide(
      boolean archivePresent,
      Tally tally,
      long childCount,
      long verifiedChildCount,
      String firstStorageFailure) {

    if (!archivePresent) {
      return Decision.retain("the original archive was already reclaimed");
    }
    if (tally == null) {
      return Decision.retain("the extraction outcome could not be established");
    }
    if (tally.getStopReason() != null) {
      // Entries beyond the stop were never enumerated, so nothing can be proven about them.
      return Decision.retain(
          "extraction did not run to completion (" + tally.getStopReason() + ")");
    }
    if (tally.getUnsupportedEntries() > 0) {
      // onUnsupported creates no record and stores no bytes: the archive is the only copy.
      return Decision.retain(
          tally.getUnsupportedEntries()
              + " entry/entries are not supported document types and were not stored individually"
              + " (e.g. "
              + describe(tally.getFirstUnsupportedPath())
              + ")");
    }
    if (tally.getCorruptNestedEntries() > 0) {
      return Decision.retain(
          tally.getCorruptNestedEntries()
              + " nested archive(s) could not be read, so their contents were never extracted"
              + " (e.g. "
              + describe(tally.getFirstCorruptPath())
              + ")");
    }
    if (tally.getDocumentEntries() <= 0) {
      return Decision.retain("the archive produced no document, so it is the only copy of itself");
    }
    if (childCount != tally.getDocumentEntries()) {
      return Decision.retain(
          "only "
              + childCount
              + " of "
              + tally.getDocumentEntries()
              + " document(s) reached the database");
    }
    if (verifiedChildCount != childCount) {
      // A MetaFile row is not proof of durability, so this counts only children whose physical
      // blob was confirmed present and non-empty.
      long unverified = childCount - verifiedChildCount;
      return Decision.retain(
          unverified
              + " of "
              + childCount
              + " document(s) could not be confirmed as durably stored"
              + (firstStorageFailure == null ? "" : " — " + firstStorageFailure));
    }
    if (firstStorageFailure != null) {
      // Belt to the braces: a verification problem that did not reduce the count still blocks.
      return Decision.retain("storage could not be verified — " + firstStorageFailure);
    }
    return Decision.reclaim();
  }

  private static String describe(String path) {
    return (path == null || path.trim().isEmpty()) ? "unnamed entry" : path;
  }
}
