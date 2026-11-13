package com.axelor.apps.nyxasecurity.directory;

/**
 * A refusal or failure from the user directory. The message is safe to show to the administrator:
 * it is the directory's own explanation (or ours, for transport failures) and never carries a token
 * or secret.
 */
public class DirectoryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** The directory could not be reached, or answered something we don't understand. */
  public static final String UNAVAILABLE = "unavailable";

  private final int status;
  private final String code;

  public DirectoryException(int status, String code, String message) {
    super(message);
    this.status = status;
    this.code = code;
  }

  public DirectoryException(String message, Throwable cause) {
    super(message, cause);
    this.status = 0;
    this.code = UNAVAILABLE;
  }

  /** HTTP status of the refusal, or 0 when the directory was never reached. */
  public int getStatus() {
    return status;
  }

  /** The directory's machine-readable error code, or {@link #UNAVAILABLE}. */
  public String getCode() {
    return code;
  }

  /** A refusal (bad request, conflict, forbidden) rather than an outage: retrying won't help. */
  public boolean isRefusal() {
    return status >= 400 && status < 500 && status != 401;
  }
}
