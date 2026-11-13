package com.axelor.apps.aiaccountant.service;

import com.axelor.apps.aiaccountant.tool.AiToolException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A refusal that also says, in fields, what is wrong and what repairs it.
 *
 * <h2>Why prose was not enough</h2>
 *
 * Prose alone made the caller responsible for recognising a recoverable blocker by its wording, and
 * the caller was the workflow controller deciding whether to keep working or stop and ask a person.
 * So a reworded sentence could turn a fixable blocker into a question, or the reverse. The code is
 * stable, the repair tool is named, and both survive any later rewording of the message.
 *
 * <h2>Why it lives in the core</h2>
 *
 * It was {@code OcrBlocked} in the OCR module, and when the banking module needed the same thing it
 * wrote a small factory rather than depend on OCR for an exception — with a note saying that a
 * third module should promote one shared class here instead of adding a third copy. Journal entries
 * are that third module. The class is the OCR one, moved, so a service still refuses the same way
 * and the envelope the model sees is unchanged.
 *
 * <p>{@code OcrBlocked} and {@code AiBankingBlocked} still exist and still behave identically;
 * collapsing their call sites into this class is a tidying job of its own, not part of the
 * extraction that created it.
 *
 * <p>Still an {@link IllegalArgumentException}: every caller that already treats a business refusal
 * as one keeps working unchanged, and a tool that has not yet learned to read the code simply gets
 * the message it always got.
 */
public class AiBlocked extends IllegalArgumentException {

  private static final long serialVersionUID = 1L;

  private final String code;
  private final String repairTool;

  public AiBlocked(AiBlockerCode code, String message) {
    this(code, null, message);
  }

  /**
   * @param code what kind of refusal this is — the vocabulary, not free text
   * @param repairTool the tool that can clear it, or null when nothing here can
   */
  public AiBlocked(AiBlockerCode code, String repairTool, String message) {
    super(message);
    this.code = code == null ? null : code.wire();
    this.repairTool = repairTool;
  }

  /**
   * As above, keeping the exception that caused the refusal.
   *
   * <p>For the cases where the refusal is a relay of somebody else's — Axelor's own validation or
   * posting controls. The user is told what Axelor said; the stack trace is what a developer needs
   * and is not theirs to lose.
   */
  public AiBlocked(AiBlockerCode code, String message, Throwable cause) {
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

  /**
   * The same refusal, as the exception a tool throws — with the code and repair tool kept.
   *
   * <p>Here rather than repeated in every tool's catch block. The tools used to do {@code throw new
   * AiToolException(e.getMessage())}, which is exactly where the structure was being lost: the
   * service had said what kind of refusal it was and the tool threw away everything but the
   * sentence, leaving the orchestrator to work it out again from the words.
   */
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
