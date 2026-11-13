package com.axelor.apps.aiaccountant.client;

/** Service-level failure talking to Azure (auth, transport, unexpected response). */
public class AiAccountantException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public AiAccountantException(String message) {
    super(message);
  }

  public AiAccountantException(String message, Throwable cause) {
    super(message, cause);
  }
}
