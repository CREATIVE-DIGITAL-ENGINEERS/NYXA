package com.axelor.apps.aiocr.service.ingest;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure, dependency-free allow-list of the document types the OCR pipeline accepts: PDF, PNG, JPG,
 * JPEG and TIFF. Used at ingest to route anything else to the {@code unsupported} status (never
 * queued for extraction). Kept in sync with the extractor's own allowed-MIME set ({@code
 * AzureDocumentIntelligenceExtractor.DEFAULT_ALLOWED_MIME}).
 */
public final class AiOcrSupportedTypes {

  /** Supported file extensions (lower-case, no dot). */
  public static final Set<String> EXTENSIONS =
      new HashSet<>(Arrays.asList("pdf", "png", "jpg", "jpeg", "tif", "tiff"));

  /** Supported MIME types (lower-case). */
  public static final Set<String> MIME_TYPES =
      new HashSet<>(
          Arrays.asList("application/pdf", "image/png", "image/jpeg", "image/jpg", "image/tiff"));

  private AiOcrSupportedTypes() {}

  /**
   * True when the file name has one of the supported extensions. Null/blank/no-extension → false.
   */
  public static boolean isSupportedFileName(String fileName) {
    return EXTENSIONS.contains(extensionOf(fileName));
  }

  /** True when the MIME type is one of the supported ones. Null/blank → false. */
  public static boolean isSupportedMime(String mime) {
    if (mime == null) {
      return false;
    }
    // Strip any "; charset=..." parameters before matching.
    int semi = mime.indexOf(';');
    String base = (semi >= 0 ? mime.substring(0, semi) : mime).trim().toLowerCase();
    return MIME_TYPES.contains(base);
  }

  /** True when the name is a ZIP archive (so the ingester recurses into it). */
  public static boolean isZip(String fileName) {
    return "zip".equals(extensionOf(fileName));
  }

  /**
   * True when a file picked in the "Archive" section really is a ZIP.
   *
   * <p>The <b>extension decides whenever there is one</b>, and the MIME type is consulted only for
   * an extension-less file. That order is deliberate: {@code .xlsx}, {@code .docx} and {@code .odt}
   * <em>are</em> ZIP containers, and some browsers report {@code application/zip} for them — so
   * trusting the MIME type first would let a spreadsheet through as an archive, which then fails
   * the carrier with an unreadable-archive error.
   */
  public static boolean isZipUpload(String fileName, String mime) {
    String ext = extensionOf(fileName);
    if (!ext.isEmpty()) {
      return "zip".equals(ext);
    }
    if (mime == null) {
      return false;
    }
    int semi = mime.indexOf(';');
    String base = (semi >= 0 ? mime.substring(0, semi) : mime).trim().toLowerCase();
    return "application/zip".equals(base) || "application/x-zip-compressed".equals(base);
  }

  /** Lower-case extension without the dot, or empty string when there is none. */
  public static String extensionOf(String fileName) {
    if (fileName == null) {
      return "";
    }
    String name = fileName.trim();
    // Use only the last path segment so a directory with a dot doesn't fool the check.
    int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
    if (slash >= 0) {
      name = name.substring(slash + 1);
    }
    int dot = name.lastIndexOf('.');
    if (dot < 0 || dot == name.length() - 1) {
      return "";
    }
    return name.substring(dot + 1).toLowerCase();
  }

  /** Human-readable list of the accepted extensions (for messages). */
  public static String describe() {
    return EXTENSIONS.stream().sorted().collect(Collectors.joining(", "));
  }
}
