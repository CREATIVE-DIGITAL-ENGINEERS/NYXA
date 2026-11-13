package com.axelor.apps.aiocr.service.ocr;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * DEV/TEST-ONLY helper. Submits already-read document bytes to the <b>real</b> Azure Document
 * Intelligence path (reusing the production {@code submitWithRetry} + {@code pollUntilDone}) and
 * returns the raw {@code analyzeResult} JSON. It exists so the fixture generator can capture the
 * exact Azure response for one ≤2-page PDF chunk at a time under the free tier, WITHOUT changing
 * any production behavior. Lives in the test source set only.
 */
public class AzureRawAnalyzer extends AzureDocumentIntelligenceExtractor {

  /** Raw {@code analyzeResult} JSON for {@code bytes}, or throws with Azure's status + body. */
  public String analyzeRaw(byte[] bytes, String mime) {
    AzureResponse submit = submitWithRetry(bytes, mime);
    if (submit.status != 202 || submit.operationLocation == null) {
      throw new AzureChunkException(submit.status, submit.body);
    }
    JsonNode analyzeResult = pollUntilDone(submit.operationLocation);
    return analyzeResult.toString();
  }

  /** Carries Azure's rejection status + body so the generator can save it for inspection. */
  public static class AzureChunkException extends RuntimeException {
    public final int status;
    public final String body;

    AzureChunkException(int status, String body) {
      super("Azure returned HTTP " + status + (body == null ? "" : (": " + trim(body))));
      this.status = status;
      this.body = body == null ? "" : body;
    }

    private static String trim(String b) {
      return b.length() > 600 ? b.substring(0, 600) + "…" : b;
    }
  }
}
