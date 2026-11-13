package com.axelor.apps.aiocr.service.ocr;

import com.axelor.app.AppSettings;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import javax.inject.Inject;

/**
 * Selects the OCR engine <b>server-side only</b> from {@code aiocr.extractor.mode} (or the {@code
 * AIOCR_ENGINE} env var), defaulting to {@code azure}. A request, user, browser or uploaded
 * filename can never change the engine. There is no HTTP/Python engine — only {@link
 * AzureDocumentIntelligenceExtractor} (production) and {@link MockOcrExtractor} (local/tests).
 */
@Singleton
public class OcrExtractorRouter {

  private final AzureDocumentIntelligenceExtractor azure;
  private final MockOcrExtractor mock;

  @Inject
  public OcrExtractorRouter(AzureDocumentIntelligenceExtractor azure, MockOcrExtractor mock) {
    this.azure = azure;
    this.mock = mock;
  }

  public OcrExtractResult extract(MetaFile file) {
    return select().extract(file);
  }

  /** As {@link #extract(MetaFile)}, forwarding a per-chunk progress listener to the engine. */
  public OcrExtractResult extract(MetaFile file, OcrProgressListener progress) {
    return select().extract(file, progress);
  }

  /** The engine chosen by server configuration. */
  public OcrExtractor select() {
    return MockOcrExtractor.ENGINE.equalsIgnoreCase(mode()) ? mock : azure;
  }

  static String mode() {
    String m = AppSettings.get().get("aiocr.extractor.mode", null);
    if (m == null || m.trim().isEmpty()) {
      String env = System.getenv("AIOCR_ENGINE");
      m = (env == null || env.trim().isEmpty()) ? AzureDocumentIntelligenceExtractor.ENGINE : env;
    }
    return m.trim().toLowerCase();
  }
}
