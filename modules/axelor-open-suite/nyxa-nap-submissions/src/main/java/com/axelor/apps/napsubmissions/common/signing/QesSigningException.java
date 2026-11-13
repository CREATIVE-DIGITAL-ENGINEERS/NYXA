package com.axelor.apps.napsubmissions.common.signing;

/**
 * An orchestration-level signing failure the user must see and act on — distinct from the
 * provider-neutral {@link QesException} (transport/config of the provider itself). It carries a
 * ready, user-facing message for the submission form; the message never contains a secret, a PIN or
 * any payload content.
 */
public class QesSigningException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public QesSigningException(String message) {
    super(message);
  }
}
