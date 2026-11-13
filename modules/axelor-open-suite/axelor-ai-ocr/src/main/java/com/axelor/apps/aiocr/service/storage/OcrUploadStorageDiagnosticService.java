package com.axelor.apps.aiocr.service.storage;

import com.axelor.app.AppSettings;
import com.axelor.apps.aiocr.service.ingest.SafeZipExtractor;
import com.axelor.apps.aiocr.service.storage.OcrMetaFileReferenceScanner.BatchResult;
import com.axelor.db.JPA;
import com.axelor.meta.MetaFiles;
import com.google.inject.Singleton;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.persistence.TypedQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Temporary, READ-ONLY diagnostic for the upload storage question that cannot be answered from the
 * browser on a hosted environment with no Azure Console access: how much is used, how much is free,
 * and whether the exact guard that already gates archive extraction currently allows it.
 *
 * <p>Deliberately not the removed OCR Storage Report: no entity, no menu, no stored snapshot, no
 * bucket classification. One scan, one dialog, counts and bytes only — see {@link Result}.
 *
 * <h2>Bounds and fail-closed behaviour</h2>
 *
 * The filesystem walk and the {@code MetaFile} page-through are each hard-capped ({@link
 * #MAX_DISK_ENTRIES}, {@link #MAX_METAFILE_ROWS}). Hitting either cap makes the scan {@code
 * Incomplete} and withholds the three integrity figures ({@link Result#getUnreferencedCount()},
 * {@link Result#getStrayBlobCount()}, {@link Result#getMissingBlobCount()}) as {@code null} — a
 * capped scan must never publish a partial total as if it were complete. A failed reference query
 * (via the reused {@link OcrMetaFileReferenceScanner}) withholds the unreferenced figure the same
 * way, never as zero.
 */
@Singleton
public class OcrUploadStorageDiagnosticService {

  private static final Logger LOG =
      LoggerFactory.getLogger(OcrUploadStorageDiagnosticService.class);

  /** Hard ceiling on MetaFile rows examined in one scan. */
  static final int MAX_METAFILE_ROWS = 10_000;

  static final int METAFILE_PAGE_SIZE = 500;

  /** Hard ceiling on filesystem entries visited in the one bounded walk. */
  static final int MAX_DISK_ENTRIES = 20_000;

  /**
   * The exact key {@link com.axelor.apps.aiocr.service.ingest.AiOcrArchiveExtractService} reads for
   * the free-space reserve enforced during archive extraction. Reused verbatim rather than invented
   * again, so this report can never disagree with the guard it is describing.
   */
  static final String MIN_FREE_BYTES_KEY = "aiocr.storage.min-free-bytes";

  /**
   * Same literal name as {@code OcrFileQuarantineService.QUARANTINE_DIR_NAME} (a different package,
   * protected there). Read-only here on purpose: unlike {@code
   * OcrFileQuarantineService#quarantineDir()} this never creates the directory — a diagnostic scan
   * must do no writes.
   */
  static final String QUARANTINE_DIR_NAME = ".ocr-quarantine";

  static final String QUARANTINE_DIR_KEY = "aiocr.file.quarantine.dir";

  protected final OcrMetaFileReferenceScanner scanner;

  @Inject
  public OcrUploadStorageDiagnosticService(OcrMetaFileReferenceScanner scanner) {
    this.scanner = scanner;
  }

  /** Scan state, in increasing order of "the figures below can be trusted". */
  public enum State {
    FAILED,
    INCOMPLETE,
    COMPLETE
  }

  /** One MetaFile row projection: only what the scan needs, never a whole entity. */
  protected static final class FileRow {
    final Long id;
    final String path;
    final long size;

    FileRow(Long id, String path, long size) {
      this.id = id;
      this.path = path == null ? "" : normalize(path);
      this.size = size;
    }
  }

  /** One bounded pass over the upload directory. */
  protected static final class DiskWalk {
    final Map<String, Long> sizeByPath;
    final boolean complete;
    final long quarantineFileCount;
    final long quarantineBytes;

    DiskWalk(Map<String, Long> sizeByPath, boolean complete, long qCount, long qBytes) {
      this.sizeByPath = sizeByPath;
      this.complete = complete;
      this.quarantineFileCount = qCount;
      this.quarantineBytes = qBytes;
    }
  }

  /** Immutable result: counts and bytes only, never a name, path or id. */
  public static final class Result {

    private final State state;
    private final String reason;

    private final Long uploadFileCount;
    private final Long uploadBytes;

    private final Long freeBytes;
    private final long minFreeReserveBytes;
    private final Boolean extractionPermitted;

    private final long quarantineFileCount;
    private final long quarantineBytes;

    private final String tempAreaUnavailableReason;

    private final Long unreferencedCount;
    private final Long unreferencedBytes;
    private final Long strayBlobCount;
    private final Long strayBlobBytes;
    private final Long missingBlobCount;
    private final Long missingBlobBytes;

    Result(
        State state,
        String reason,
        Long uploadFileCount,
        Long uploadBytes,
        Long freeBytes,
        long minFreeReserveBytes,
        Boolean extractionPermitted,
        long quarantineFileCount,
        long quarantineBytes,
        String tempAreaUnavailableReason,
        Long unreferencedCount,
        Long unreferencedBytes,
        Long strayBlobCount,
        Long strayBlobBytes,
        Long missingBlobCount,
        Long missingBlobBytes) {
      this.state = state;
      this.reason = reason;
      this.uploadFileCount = uploadFileCount;
      this.uploadBytes = uploadBytes;
      this.freeBytes = freeBytes;
      this.minFreeReserveBytes = minFreeReserveBytes;
      this.extractionPermitted = extractionPermitted;
      this.quarantineFileCount = quarantineFileCount;
      this.quarantineBytes = quarantineBytes;
      this.tempAreaUnavailableReason = tempAreaUnavailableReason;
      this.unreferencedCount = unreferencedCount;
      this.unreferencedBytes = unreferencedBytes;
      this.strayBlobCount = strayBlobCount;
      this.strayBlobBytes = strayBlobBytes;
      this.missingBlobCount = missingBlobCount;
      this.missingBlobBytes = missingBlobBytes;
    }

    static Result failed(String reason) {
      return new Result(
          State.FAILED,
          reason,
          null,
          null,
          null,
          0L,
          null,
          0L,
          0L,
          "the upload directory could not be resolved",
          null,
          null,
          null,
          null,
          null,
          null);
    }

    public State getState() {
      return state;
    }

    public String getReason() {
      return reason;
    }

    public Long getUploadFileCount() {
      return uploadFileCount;
    }

    public Long getUploadBytes() {
      return uploadBytes;
    }

    public Long getFreeBytes() {
      return freeBytes;
    }

    public long getMinFreeReserveBytes() {
      return minFreeReserveBytes;
    }

    public Boolean getExtractionPermitted() {
      return extractionPermitted;
    }

    public long getQuarantineFileCount() {
      return quarantineFileCount;
    }

    public long getQuarantineBytes() {
      return quarantineBytes;
    }

    public String getTempAreaUnavailableReason() {
      return tempAreaUnavailableReason;
    }

    public Long getUnreferencedCount() {
      return unreferencedCount;
    }

    public Long getUnreferencedBytes() {
      return unreferencedBytes;
    }

    public Long getStrayBlobCount() {
      return strayBlobCount;
    }

    public Long getStrayBlobBytes() {
      return strayBlobBytes;
    }

    public Long getMissingBlobCount() {
      return missingBlobCount;
    }

    public Long getMissingBlobBytes() {
      return missingBlobBytes;
    }
  }

  /** One unreferenced MetaFile row found by a scan, with enough detail for cleanup. */
  protected static final class UnreferencedFile {
    final Long id;
    final long size;
    final boolean blobMissing;

    UnreferencedFile(Long id, long size, boolean blobMissing) {
      this.id = id;
      this.size = size;
      this.blobMissing = blobMissing;
    }

    public Long getId() {
      return id;
    }

    public long getSize() {
      return size;
    }

    public boolean isBlobMissing() {
      return blobMissing;
    }
  }

  /**
   * Full scan outcome: the same counts-only {@link Result} plus the exact unreferenced rows.
   * Package-visible only, for the one internal caller (the orphan-cleanup service, same package)
   * that needs ids to act on — never exposed outside {@code storage}. {@code unreferencedFiles} is
   * only meaningful to act on when {@code result.getState() == State.COMPLETE}: see the class
   * javadoc on fail-closed behaviour.
   */
  static final class ScanOutcome {
    final Result result;
    final List<UnreferencedFile> unreferencedFiles;

    ScanOutcome(Result result, List<UnreferencedFile> unreferencedFiles) {
      this.result = result;
      this.unreferencedFiles = unreferencedFiles;
    }
  }

  // ---------------------------------------------------------------------------------------------

  public Result scan() {
    return computeScan().result;
  }

  /** Same scan as {@link #scan()}, additionally exposing the unreferenced rows by id. */
  ScanOutcome computeScan() {
    Path root = resolveUploadRoot();
    if (root == null) {
      return new ScanOutcome(
          Result.failed("the upload directory could not be resolved"), Collections.emptyList());
    }

    Long freeBytes;
    try {
      freeBytes = usableSpace(root);
    } catch (RuntimeException e) {
      LOG.warn("Could not read free space for {}: {}", root, e.getMessage());
      freeBytes = null;
    }

    long minFreeReserve = cfgLong(MIN_FREE_BYTES_KEY, 0L);
    Boolean extractionPermitted;
    try {
      extractionPermitted =
          minFreeReserve <= 0 || SafeZipExtractor.tmpUsableSpace() >= minFreeReserve;
    } catch (RuntimeException e) {
      LOG.warn("Could not evaluate the extraction free-space guard: {}", e.getMessage());
      extractionPermitted = null;
    }

    Path quarantineDir = resolveQuarantineDir(root);
    DiskWalk walk = walkUploadRoot(root, quarantineDir);

    List<FileRow> rows = new ArrayList<>();
    String referenceFailure = null;
    boolean metaFileComplete = true;
    {
      Long lastId = null;
      while (rows.size() <= MAX_METAFILE_ROWS) {
        List<FileRow> page = fetchMetaFilePage(lastId, METAFILE_PAGE_SIZE);
        if (page.isEmpty()) {
          break;
        }
        rows.addAll(page);
        lastId = page.get(page.size() - 1).id;
        if (page.size() < METAFILE_PAGE_SIZE) {
          break;
        }
      }
      if (rows.size() > MAX_METAFILE_ROWS) {
        metaFileComplete = false;
        rows = rows.subList(0, MAX_METAFILE_ROWS);
      }
    }

    // Collected over ALL fetched rows regardless of any reference-check failure below: stray-blob
    // detection (item 8) only needs to know which paths a MetaFile row claims, not whether that
    // row is referenced.
    Set<String> metaFilePaths = new HashSet<>();
    for (FileRow r : rows) {
      metaFilePaths.add(r.path);
    }

    List<FileRow> unreferenced = new ArrayList<>();
    for (int i = 0; i < rows.size() && referenceFailure == null; i += METAFILE_PAGE_SIZE) {
      List<FileRow> page = rows.subList(i, Math.min(i + METAFILE_PAGE_SIZE, rows.size()));
      List<Long> ids = new ArrayList<>();
      for (FileRow r : page) {
        ids.add(r.id);
      }
      BatchResult batch = checkReferences(ids);
      referenceFailure = batch.getFailureReason();
      Set<Long> decided = new HashSet<>(batch.getReferenced());
      decided.addAll(batch.getUnproven());
      for (FileRow r : page) {
        if (!decided.contains(r.id)) {
          unreferenced.add(r);
        }
      }
    }

    boolean integrityComplete = walk.complete && metaFileComplete;

    Long unreferencedCount = null;
    Long unreferencedBytes = null;
    if (referenceFailure == null) {
      long count = 0;
      long bytes = 0;
      for (FileRow r : unreferenced) {
        count++;
        bytes += r.size;
      }
      unreferencedCount = count;
      unreferencedBytes = bytes;
    }

    Long strayCount = null;
    Long strayBytes = null;
    Long missingCount = null;
    Long missingBytes = null;
    if (integrityComplete) {
      long sc = 0;
      long sb = 0;
      for (Map.Entry<String, Long> e : walk.sizeByPath.entrySet()) {
        if (!metaFilePaths.contains(e.getKey())) {
          sc++;
          sb += e.getValue() == null ? 0L : e.getValue();
        }
      }
      strayCount = sc;
      strayBytes = sb;

      long mc = 0;
      long mb = 0;
      for (FileRow r : rows) {
        if (!walk.sizeByPath.containsKey(r.path)) {
          mc++;
          mb += r.size;
        }
      }
      missingCount = mc;
      missingBytes = mb;
    }

    State state;
    String reason;
    if (!integrityComplete) {
      state = State.INCOMPLETE;
      reason =
          !walk.complete
              ? "the filesystem walk stopped at the " + MAX_DISK_ENTRIES + "-entry cap"
              : "more than " + MAX_METAFILE_ROWS + " MetaFile rows exist; the scan stopped there";
    } else if (referenceFailure != null) {
      state = State.INCOMPLETE;
      reason = "a reference check failed: " + referenceFailure;
    } else {
      state = State.COMPLETE;
      reason = null;
    }

    long uploadFileCount = walk.sizeByPath.size();
    long uploadBytes = 0;
    for (Long v : walk.sizeByPath.values()) {
      uploadBytes += v == null ? 0L : v;
    }

    List<UnreferencedFile> unreferencedDetail = new ArrayList<>(unreferenced.size());
    for (FileRow r : unreferenced) {
      unreferencedDetail.add(
          new UnreferencedFile(r.id, r.size, !walk.sizeByPath.containsKey(r.path)));
    }

    Result result =
        new Result(
            state,
            reason,
            uploadFileCount,
            uploadBytes,
            freeBytes,
            minFreeReserve,
            extractionPermitted,
            walk.quarantineFileCount,
            walk.quarantineBytes,
            "OCR temporary files are written under the shared system temp directory"
                + " (java.io.tmpdir), which is not exclusive to OCR, so it cannot be resolved as an"
                + " OCR-only area.",
            unreferencedCount,
            unreferencedBytes,
            strayCount,
            strayBytes,
            missingCount,
            missingBytes);
    return new ScanOutcome(result, unreferencedDetail);
  }

  // ---------------------------------------------------------------------------------------------
  // I/O seams — overridable so a test can drive the pure logic above without a database or disk.
  // ---------------------------------------------------------------------------------------------

  /**
   * The directory every {@code MetaFile.filePath} is relative to. {@code MetaFiles.getPath("")}
   * throws (the platform rejects a name resolving to the upload directory itself), so the root is
   * taken as the parent of a resolved harmless child, exactly as the removed OCR Storage Report did
   * (see git history 1d47234c) — reusing the platform's own tenant-aware resolution rather than
   * reconstructing it.
   */
  protected Path resolveUploadRoot() {
    try {
      return MetaFiles.getPath("x").getParent();
    } catch (RuntimeException e) {
      LOG.warn("Upload directory unavailable: {}", e.getMessage());
      return null;
    }
  }

  /**
   * The quarantine directory, without creating it (unlike {@code
   * OcrFileQuarantineService#quarantineDir()} — this scan does no writes). {@code null} when it
   * cannot be resolved or does not exist yet.
   */
  protected Path resolveQuarantineDir(Path root) {
    try {
      String override = AppSettings.get().get(QUARANTINE_DIR_KEY, null);
      Path dir =
          (override != null && !override.trim().isEmpty())
              ? Path.of(override.trim())
              : root.resolve(QUARANTINE_DIR_NAME);
      return Files.isDirectory(dir) ? dir : null;
    } catch (RuntimeException e) {
      return null;
    }
  }

  protected long usableSpace(Path root) {
    return root.toFile().getUsableSpace();
  }

  /**
   * One bounded {@code walkFileTree} over the upload root. Quarantine files (if the quarantine
   * directory lives under the root) are tallied separately and excluded from {@code sizeByPath} —
   * they must never be counted as stray.
   */
  protected DiskWalk walkUploadRoot(Path root, Path quarantineDir) {
    Map<String, Long> sizes = new HashMap<>();
    long[] visited = {0L};
    long[] quarantined = {0L, 0L};
    boolean[] capped = {false};
    boolean[] unreadable = {false};

    if (root == null || !Files.isDirectory(root)) {
      return new DiskWalk(sizes, false, 0L, 0L);
    }

    try {
      Files.walkFileTree(
          root,
          new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
              if (!attrs.isRegularFile()) {
                return FileVisitResult.CONTINUE;
              }
              if (visited[0] >= MAX_DISK_ENTRIES) {
                capped[0] = true;
                return FileVisitResult.TERMINATE;
              }
              visited[0]++;
              long size = Math.max(0L, attrs.size());
              if (quarantineDir != null && file.startsWith(quarantineDir)) {
                quarantined[0]++;
                quarantined[1] += size;
                return FileVisitResult.CONTINUE;
              }
              sizes.put(normalize(root.relativize(file).toString()), size);
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
              unreadable[0] = true;
              return FileVisitResult.CONTINUE;
            }
          });
    } catch (IOException e) {
      LOG.warn("Could not walk the upload directory {}: {}", root, e.getMessage());
      return new DiskWalk(sizes, false, quarantined[0], quarantined[1]);
    }

    boolean complete = !capped[0] && !unreadable[0];
    return new DiskWalk(sizes, complete, quarantined[0], quarantined[1]);
  }

  /** One keyset page of {@code MetaFile}, ordered by id, projected to id/path/size only. */
  @SuppressWarnings("unchecked")
  protected List<FileRow> fetchMetaFilePage(Long lastId, int limit) {
    String jpql =
        lastId == null
            ? "SELECT self.id, self.filePath, self.fileSize FROM MetaFile self ORDER BY self.id"
            : "SELECT self.id, self.filePath, self.fileSize FROM MetaFile self"
                + " WHERE self.id > :lastId ORDER BY self.id";
    TypedQuery<Object[]> query = JPA.em().createQuery(jpql, Object[].class);
    if (lastId != null) {
      query.setParameter("lastId", lastId);
    }
    query.setMaxResults(limit);
    List<Object[]> rows = query.getResultList();
    List<FileRow> result = new ArrayList<>(rows.size());
    for (Object[] row : rows) {
      Long id = (Long) row[0];
      String path = (String) row[1];
      Long size = (Long) row[2];
      result.add(new FileRow(id, path, size == null ? 0L : size));
    }
    return result;
  }

  /** Delegates to the reused scanner. Overridable seam so a test can drive it without JPA. */
  protected BatchResult checkReferences(List<Long> ids) {
    return scanner.referencedIds(ids);
  }

  static String normalize(String path) {
    return path == null ? "" : path.replace('\\', '/').trim();
  }

  protected static long cfgLong(String key, long def) {
    try {
      return Long.parseLong(AppSettings.get().get(key, String.valueOf(def)).trim());
    } catch (RuntimeException e) {
      return def;
    }
  }
}
