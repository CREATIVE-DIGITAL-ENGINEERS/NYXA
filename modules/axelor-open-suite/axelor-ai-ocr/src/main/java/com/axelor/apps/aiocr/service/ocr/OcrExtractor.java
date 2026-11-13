package com.axelor.apps.aiocr.service.ocr;

import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.meta.db.MetaFile;

/**
 * Extracts structured invoice data from an uploaded {@link MetaFile}. Implementations are pure data
 * producers — they never create Axelor business entities. The concrete engine is chosen server-side
 * by {@link OcrExtractorRouter} (never by a request/user/filename).
 */
public interface OcrExtractor {

  /**
   * Engine identifier stored on the upload/invoice for audit (e.g. {@code azure}, {@code mock}).
   */
  String name();

  /**
   * Extract from the file. Returns a result with {@code extractionFailed=true} for a document the
   * engine could read but rejected (unreadable/unsupported); throws {@link OcrServiceException}
   * when the extraction service itself is unavailable/misbehaving. Never falls back to another
   * engine.
   */
  OcrExtractResult extract(MetaFile file);

  /**
   * As {@link #extract(MetaFile)}, but reports progress after each analysed page-chunk via {@code
   * progress} so the caller can persist a live "processed pages / total pages" percentage. The
   * default ignores progress; engines that analyse a document in chunks (Azure) override this.
   */
  default OcrExtractResult extract(MetaFile file, OcrProgressListener progress) {
    return extract(file);
  }
}
