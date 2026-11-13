package com.axelor.apps.napsubmissions.common.signing;

/**
 * Error raised by a {@link CloudQesProvider}. Provider-neutral and carries a coarse {@link
 * Category} so the caller can react (fail closed, retry, surface to a human) without parsing
 * messages.
 *
 * <p>It never carries private-key material and never carries the raw signing payload.
 */
public class QesException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** Coarse classification of a signing failure. */
  public enum Category {
    /** No provider configured, or misconfiguration — the signing feature is unavailable. */
    CONFIG,
    /** The request itself is invalid (e.g. empty payload) — do not retry unchanged. */
    INVALID_REQUEST,
    /** The referenced external signing request is unknown to the provider. */
    NOT_FOUND,
    /** Transport/communication problem talking to the provider — may be retried. */
    TRANSPORT
  }

  private final Category category;

  public QesException(Category category, String message) {
    super(message);
    this.category = category;
  }

  public QesException(Category category, String message, Throwable cause) {
    super(message, cause);
    this.category = category;
  }

  public Category getCategory() {
    return category;
  }
}
