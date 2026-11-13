package com.axelor.apps.aibanking.service;

import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a refusal that names what kind it is.
 *
 * <h2>Why a factory rather than an exception class</h2>
 *
 * The OCR module carries an {@code OcrBlocked} exception for this, which exists so a service can
 * refuse without knowing about the tool layer. It does not quite achieve that — it imports {@code
 * AiToolException} anyway, to convert itself — so the class buys an extra hop rather than a
 * boundary. This module has three refusal sites, so it skips the hop: the code and the message go
 * straight onto the exception the dispatcher already understands, and the {@code blocker} field
 * lands in the error envelope exactly as it does for an invoice or a move.
 *
 * <p>If a third module needs this, the right move is to promote one shared {@code AiBlocked} into
 * {@code axelor-ai-accountant} beside {@link AiBlockerCode} and collapse both call sites into it —
 * not to add a third copy.
 */
public final class AiBankingBlocked {

  private AiBankingBlocked() {}

  /** A refusal carrying its code, so the workflow classifies it without reading the sentence. */
  public static AiToolException of(AiBlockerCode code, String message) {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("blocker", code.wire());
    return new AiToolException(message, details);
  }

  /** As above, naming the tool that clears the blocker. */
  public static AiToolException of(AiBlockerCode code, String repairTool, String message) {
    Map<String, String> details = new LinkedHashMap<>();
    details.put("blocker", code.wire());
    details.put("repairTool", repairTool);
    return new AiToolException(message, details);
  }
}
