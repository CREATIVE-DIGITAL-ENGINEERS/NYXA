package com.axelor.apps.aiocr.service.ocr;

/**
 * Reports OCR progress as pages are analysed, so the intake can persist a carrier's "processed OCR
 * pages / total pages" percentage after each Azure chunk while the long extraction is still
 * running. Implementations must be cheap and must not throw; a slow/failing listener must never
 * affect the extraction. Called from the extraction thread, never concurrently for the same call.
 */
@FunctionalInterface
public interface OcrProgressListener {

  /** No-op listener for callers that do not track progress (e.g. single-page split children). */
  OcrProgressListener NONE = (pagesDone, pagesTotal) -> {};

  /**
   * @param pagesDone pages whose OCR has completed so far (1-based cumulative count)
   * @param pagesTotal total pages in the document being analysed
   */
  void onPagesProcessed(int pagesDone, int pagesTotal);
}
