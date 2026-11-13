package com.axelor.apps.aiaccountant.tool;

/**
 * Read vs write separation, present from day one.
 *
 * <p>{@link #WRITE} must be per-tool allowlisted, authorised and audited by {@code
 * AiToolDispatcher} before it runs.
 */
public enum AiToolKind {

  /** Returns data. Changes nothing, and may be repeated freely. */
  READ,

  /** Changes business data. Everything in the write path applies. */
  WRITE;

  /** True only for a tool that changes business data. */
  public boolean isWrite() {
    return this == WRITE;
  }

  /** True for a tool that only returns data. */
  public boolean isRead() {
    return this == READ;
  }
}
