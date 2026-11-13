package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccountant.tool.AiToolException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal from the invoice services that also says, in fields, what is wrong and what repairs it.
 *
 * <h2>Why the code travels with the message</h2>
 *
 * The workflow controller decides whether to keep working or stop and ask a person, and it decides
 * that from the blocker's classification. Handing it prose alone would make a reworded sentence
 * able to turn a fixable blocker into a question, or the reverse. So the code is stable, the repair
 * tool is named where one exists, and both survive any later rewording.
 *
 * <p>Same shape and the same reasoning as {@code OcrBlocked} in the OCR module, which does this for
 * the supplier-upload flow. Deliberately not shared with it: that class belongs to a module this
 * one must not depend on, and the duplication here is thirty lines against a dependency edge that
 * would point the wrong way.
 *
 * <p>Still an {@link IllegalArgumentException}, so every caller that already treats a business
 * refusal as one keeps working unchanged.
 */
public class InvoiceBlocked extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  private final String code;
  private final String repairTool;

  public InvoiceBlocked(AiBlockerCode code, String message) {
    this(code, null, message);
  }

  /**
   * @param code what kind of refusal this is — the vocabulary, not free text
   * @param repairTool the tool that can clear it, or null when nothing here can
   */
  public InvoiceBlocked(AiBlockerCode code, String repairTool, String message) {
    super(message);
    this.code = code == null ? null : code.wire();
    this.repairTool = repairTool;
  }

  /**
   * As above, keeping the exception that caused the refusal.
   *
   * <p>For refusals that relay somebody else's — Axelor's own validation or ventilation controls.
   * The user is told what Axelor said; the stack trace is what a developer needs and is not theirs
   * to lose.
   */
  public InvoiceBlocked(AiBlockerCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code == null ? null : code.wire();
    this.repairTool = null;
  }

  public String getCode() {
    return code;
  }

  /** The tool that can clear this blocker, or null when nothing here can. */
  public String getRepairTool() {
    return repairTool;
  }

  /** The same refusal, as the exception a tool throws — with the code and repair tool kept. */
  public AiToolException asToolException() {
    Map<String, String> details = new LinkedHashMap<>();
    if (code != null) {
      details.put("blocker", code);
    }
    if (repairTool != null) {
      details.put("repairTool", repairTool);
    }
    return new AiToolException(getMessage(), details);
  }
}
