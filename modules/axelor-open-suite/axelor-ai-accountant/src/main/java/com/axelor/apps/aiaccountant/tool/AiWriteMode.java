package com.axelor.apps.aiaccountant.tool;

/**
 * How a WRITE tool is allowed to be invoked.
 *
 * <p>The two channels are genuinely different in kind, not just in degree:
 *
 * <ul>
 *   <li>{@link #UNATTENDED} — reachable from the OCR intake pipeline, where there is no human at
 *       all. Only for changes the server can decide entirely on its own: a deterministic lookup
 *       with exactly one answer, validated in Java. Model judgement must never travel this path.
 *   <li>{@link #CONFIRMED} — reachable from the chat, where a human wrote the instruction this
 *       turn. The name says who authorises, not that the server holds a pending change: there is no
 *       proposal queue and no apply step (see {@link AiToolDispatcher}), so the confirmation is the
 *       user's own message and the model's reading of it, backed by the operating procedure's
 *       authorisation rules and by every guard inside the tool itself.
 * </ul>
 *
 * <p>Declaring a mode is a <b>ceiling, not a grant</b>: configuration still has to name the tool in
 * the matching allowlist before anything can run.
 */
public enum AiWriteMode {
  UNATTENDED,
  CONFIRMED
}
