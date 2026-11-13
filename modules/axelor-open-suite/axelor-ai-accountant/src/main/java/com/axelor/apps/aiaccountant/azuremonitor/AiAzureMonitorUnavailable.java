package com.axelor.apps.aiaccountant.azuremonitor;

/**
 * Azure Monitor could not answer, and why in terms an administrator can act on.
 *
 * <h2>Why the reason is an enum</h2>
 *
 * The Stats page has to say something more useful than "error". The three cases that actually
 * happen need three different actions: {@link Reason#FORBIDDEN} means the {@code Monitoring Reader}
 * role was never granted and no amount of waiting will fix it; {@link Reason#THROTTLED} means wait;
 * {@link Reason#NOT_CONFIGURED} means nobody has set the resource id. Collapsing them into one
 * message is what makes people restart things instead of granting a role.
 */
public class AiAzureMonitorUnavailable extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public enum Reason {
    NOT_CONFIGURED,
    FORBIDDEN,
    THROTTLED,
    TIMEOUT,
    UNREACHABLE,
    MALFORMED,
    REJECTED
  }

  private final Reason reason;

  public AiAzureMonitorUnavailable(Reason reason, String message) {
    super(message);
    this.reason = reason;
  }

  public AiAzureMonitorUnavailable(Reason reason, String message, Throwable cause) {
    super(message, cause);
    this.reason = reason;
  }

  public Reason getReason() {
    return reason;
  }
}
