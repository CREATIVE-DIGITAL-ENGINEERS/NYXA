package com.axelor.apps.napsubmissions.common.signing;

/**
 * Lifecycle of one asynchronous cloud-QES signing request, provider-neutral.
 *
 * <p>Real cloud-QES providers (B-Trust, Evrotrust, …) sign asynchronously: a request is started,
 * the signer authorizes it out-of-band (on a phone/app), and the signature becomes available later.
 * This enum is the common vocabulary the submission layer polls against, independent of any one
 * provider's own status names.
 *
 * <ul>
 *   <li>{@link #PENDING} — started, awaiting the signer / provider; poll again later.
 *   <li>{@link #COMPLETED} — a detached signature + certificate are available (terminal, success).
 *   <li>{@link #REJECTED} — the signer declined (terminal, no signature).
 *   <li>{@link #FAILED} — the provider could not complete the request (terminal, no signature).
 *   <li>{@link #EXPIRED} — the signer did not act in time (terminal, no signature).
 * </ul>
 */
public enum QesSigningStatus {
  PENDING,
  COMPLETED,
  REJECTED,
  FAILED,
  EXPIRED;

  /** Whether no further polling will change the outcome. */
  public boolean isTerminal() {
    return this != PENDING;
  }
}
