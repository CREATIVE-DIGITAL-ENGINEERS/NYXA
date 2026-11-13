package com.axelor.apps.aiocr.service.ingest;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * Pure, streaming, recursive ZIP extractor with hard safety guards. It never loads a whole archive
 * into memory and never trusts declared entry sizes — every limit is enforced on the bytes actually
 * streamed, which is the real defence against zip bombs.
 *
 * <p>For each discovered leaf it invokes {@link EntryHandler} with the full archive-relative path.
 * Supported documents (PDF/PNG/JPG/JPEG/TIFF) are extracted to a temporary file whose lifetime is
 * the handler call; unsupported files are reported by path only (their bytes are still streamed
 * through the guards, so a bomb hidden behind an unsupported extension is still caught); nested
 * {@code .zip} entries are extracted to a temp file and recursed (depth-guarded).
 *
 * <p>Failure model (no over-promising): standard {@link ZipInputStream} cannot reliably distinguish
 * an <em>encrypted</em> archive from a corrupted or otherwise malformed one, so all of these
 * collapse into a single {@link UnreadableArchiveException}. A tripped guard raises {@link
 * ArchiveLimitException}. Both extend {@link ArchiveRejectedException} — the caller treats either
 * as a batch that fails <em>before</em> ingestion (no documents created).
 */
public final class SafeZipExtractor {

  private static final int BUFFER = 8192;

  private SafeZipExtractor() {}

  /** Guard thresholds (resolved from config by the caller; defaults match the plan). */
  public static final class Limits {
    public int maxDepth = 5;
    public long maxEntries = 5000;
    public long maxEntryBytes = 50L * 1024 * 1024;
    public long maxTotalBytes = 500L * 1024 * 1024;
    public long maxRatio = 200;

    /** Keep at least this many bytes free on the temp filesystem (0 = disabled). */
    public long minFreeBytes = 0;

    public Limits() {}

    public Limits(
        int maxDepth, long maxEntries, long maxEntryBytes, long maxTotalBytes, long maxRatio) {
      this.maxDepth = maxDepth;
      this.maxEntries = maxEntries;
      this.maxEntryBytes = maxEntryBytes;
      this.maxTotalBytes = maxTotalBytes;
      this.maxRatio = maxRatio;
    }
  }

  /**
   * Callback invoked per discovered leaf. Business errors should be handled inside the callback.
   */
  public interface EntryHandler {
    /**
     * A supported document extracted to {@code tempFile} (valid only for the duration of the call).
     */
    void onDocument(String path, Path tempFile) throws IOException;

    /** A leaf whose type is not supported for OCR (no bytes are materialised). */
    void onUnsupported(String path) throws IOException;

    /**
     * A nested {@code .zip} that could not be read (corrupt/encrypted/malformed). Extraction of the
     * other independent entries continues — this one path is reported so it can be recorded as a
     * failed child.
     */
    void onCorruptNested(String path, String reason) throws IOException;
  }

  /** Base type: the archive was rejected as a whole (nothing should be ingested). */
  public static class ArchiveRejectedException extends RuntimeException {
    public ArchiveRejectedException(String message) {
      super(message);
    }

    public ArchiveRejectedException(String message, Throwable cause) {
      super(message, cause);
    }
  }

  /** Encrypted / corrupted / malformed archive that cannot be read. */
  public static class UnreadableArchiveException extends ArchiveRejectedException {
    public UnreadableArchiveException(String message, Throwable cause) {
      super(message, cause);
    }

    public UnreadableArchiveException(String message) {
      super(message);
    }
  }

  /** A safety limit (depth / entry count / size / expansion ratio) was exceeded. */
  public static class ArchiveLimitException extends ArchiveRejectedException {
    public ArchiveLimitException(String message) {
      super(message);
    }
  }

  /** Mutable running totals shared across the recursion. */
  private static final class Counters {
    long entries;
    long uncompressed;
    final long compressedBase; // original archive size, denominator of the expansion ratio

    Counters(long compressedBase) {
      this.compressedBase = Math.max(1L, compressedBase);
    }
  }

  /**
   * Extract {@code zipFile} recursively, invoking {@code handler} for each leaf. Throws {@link
   * ArchiveRejectedException} (never a raw {@link ZipException}) when the archive cannot be read or
   * a guard trips.
   */
  public static void extract(Path zipFile, Limits limits, EntryHandler handler) throws IOException {
    long base;
    try {
      base = Files.size(zipFile);
    } catch (IOException e) {
      throw new UnreadableArchiveException("Cannot read the archive file.", e);
    }
    Counters counters = new Counters(base);
    try (InputStream in = new BufferedInputStream(Files.newInputStream(zipFile))) {
      extractStream(in, "", 0, limits, handler, counters);
    } catch (ZipException | EOFException e) {
      throw new UnreadableArchiveException(
          "Unreadable archive (corrupted, encrypted, or unsupported format).", e);
    }
  }

  private static void extractStream(
      InputStream in,
      String base,
      int depth,
      Limits limits,
      EntryHandler handler,
      Counters counters)
      throws IOException {
    if (depth > limits.maxDepth) {
      throw new ArchiveLimitException(
          "Archive nesting depth exceeds the limit of " + limits.maxDepth + ".");
    }
    ZipInputStream zis = new ZipInputStream(in);
    ZipEntry entry;
    while ((entry = zis.getNextEntry()) != null) {
      String name = entry.getName();
      if (entry.isDirectory()) {
        zis.closeEntry();
        continue;
      }
      if (isUnsafePath(name)) {
        throw new UnreadableArchiveException(
            "Archive contains an unsafe (path-traversal) entry: " + name);
      }
      if (++counters.entries > limits.maxEntries) {
        throw new ArchiveLimitException(
            "Archive entry count exceeds the limit of " + limits.maxEntries + ".");
      }
      String path = joinPath(base, name);

      if (AiOcrSupportedTypes.isZip(name)) {
        Path tmp = Files.createTempFile("aiocr-zip-", ".zip");
        try {
          try (OutputStream os = Files.newOutputStream(tmp)) {
            drainEntry(zis, os, limits, counters);
          }
          // A corrupt/encrypted NESTED archive fails only that path; continue with other entries. A
          // tripped guard (ArchiveLimitException) is a controlled whole-archive stop and
          // propagates.
          try (InputStream nested = new BufferedInputStream(Files.newInputStream(tmp))) {
            extractStream(nested, path, depth + 1, limits, handler, counters);
          } catch (UnreadableArchiveException | ZipException | EOFException nestedErr) {
            handler.onCorruptNested(path, "Nested archive is corrupted, encrypted, or unreadable.");
          }
        } finally {
          Files.deleteIfExists(tmp);
        }
      } else if (AiOcrSupportedTypes.isSupportedFileName(name)) {
        Path tmp = Files.createTempFile("aiocr-doc-", tempSuffix(name));
        try {
          try (OutputStream os = Files.newOutputStream(tmp)) {
            drainEntry(zis, os, limits, counters);
          }
          handler.onDocument(path, tmp);
        } finally {
          Files.deleteIfExists(tmp);
        }
      } else {
        // Unsupported leaf: stream through the guards (bomb defence) but do not materialise bytes.
        drainEntry(zis, null, limits, counters);
        handler.onUnsupported(path);
      }
      zis.closeEntry();
    }
  }

  /**
   * Read the current entry, optionally writing it to {@code out}, enforcing the per-entry,
   * total-size and expansion-ratio guards on the bytes actually read.
   */
  private static long drainEntry(
      ZipInputStream zis, OutputStream out, Limits limits, Counters counters) throws IOException {
    byte[] buffer = new byte[BUFFER];
    long entryBytes = 0;
    long sinceDiskCheck = 0;
    int read;
    while ((read = zis.read(buffer)) != -1) {
      entryBytes += read;
      counters.uncompressed += read;
      // Enforce the free-disk reserve continuously (only meaningful when we're writing a temp
      // file).
      if (out != null && limits.minFreeBytes > 0) {
        sinceDiskCheck += read;
        if (sinceDiskCheck >= 16L * 1024 * 1024) {
          sinceDiskCheck = 0;
          if (tmpUsableSpace() < limits.minFreeBytes) {
            throw new ArchiveLimitException(
                "Stopped: free disk space fell below the reserve of "
                    + limits.minFreeBytes
                    + " bytes.");
          }
        }
      }
      if (entryBytes > limits.maxEntryBytes) {
        throw new ArchiveLimitException(
            "An archive entry exceeds the per-file size limit of "
                + limits.maxEntryBytes
                + " bytes.");
      }
      if (counters.uncompressed > limits.maxTotalBytes) {
        throw new ArchiveLimitException(
            "The archive exceeds the total extracted-size limit of "
                + limits.maxTotalBytes
                + " bytes.");
      }
      if (counters.uncompressed > limits.maxRatio * counters.compressedBase) {
        throw new ArchiveLimitException(
            "The archive expansion ratio exceeds the limit of "
                + limits.maxRatio
                + ":1 (possible zip bomb).");
      }
      if (out != null) {
        out.write(buffer, 0, read);
      }
    }
    return entryBytes;
  }

  /** Reject absolute paths and any {@code ..} segment (zip-slip / path traversal). */
  static boolean isUnsafePath(String name) {
    if (name == null || name.trim().isEmpty()) {
      return true;
    }
    String normalized = name.replace('\\', '/');
    if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")) {
      return true;
    }
    for (String segment : normalized.split("/")) {
      if ("..".equals(segment)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Usable bytes on the temp filesystem (where entries are materialised). Public so the read-only
   * storage diagnostic can report whether this exact guard currently permits extraction, without
   * duplicating the calculation.
   */
  public static long tmpUsableSpace() {
    return new java.io.File(System.getProperty("java.io.tmpdir", ".")).getUsableSpace();
  }

  static String joinPath(String base, String name) {
    String normalized = name.replace('\\', '/');
    if (base == null || base.isEmpty()) {
      return normalized;
    }
    return base + "/" + normalized;
  }

  private static String tempSuffix(String name) {
    String ext = AiOcrSupportedTypes.extensionOf(name);
    return ext.isEmpty() ? ".tmp" : "." + ext;
  }
}
