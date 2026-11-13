package com.axelor.apps.aiaccountant.tool;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool could not run: bad arguments, missing record, denied permission.
 *
 * <p>The dispatcher turns this into a structured {@code {"error": …}} tool output rather than
 * failing the turn — the model should see the problem and be able to recover.
 *
 * <p>{@link #details()} carries the machine-readable part of a refusal, merged into that envelope
 * as extra fields. It exists so a recoverable blocker is recognised by its code rather than by the
 * model parsing English prose: a refusal that names both what is wrong ({@code blocker}) and the
 * tool that repairs it ({@code repairTool}) is acted on the same way every time. Keep it to a few
 * short scalars — it is sent to the model on every failure.
 */
public class AiToolException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final Map<String, String> details;

  public AiToolException(String message) {
    this(message, (Map<String, String>) null);
  }

  public AiToolException(String message, Map<String, String> details) {
    super(message);
    this.details =
        details == null || details.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(new LinkedHashMap<>(details));
  }

  public AiToolException(String message, Throwable cause) {
    super(message, cause);
    this.details = Collections.emptyMap();
  }

  /** Extra fields for the error envelope; never null. */
  public Map<String, String> details() {
    return details;
  }
}
