package com.axelor.apps.aiaccountant.resilience;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.http.HttpTimeoutException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import javax.persistence.LockTimeoutException;
import javax.persistence.OptimisticLockException;
import javax.persistence.PessimisticLockException;
import javax.persistence.QueryTimeoutException;

/**
 * Reads a failure and says which of the three kinds it is.
 *
 * <h2>By type, and only then by wording</h2>
 *
 * The whole cause chain is walked and matched on exception <em>types</em>, because that is the one
 * part of a failure nobody rewords. Message matching exists as a last resort for the failures that
 * arrive already wrapped in somebody's string — a JDBC driver that reports a socket timeout as a
 * {@code PersistenceException} with the word in the message, or this module's own {@code
 * AiAccountantException}, which flattens a transport problem into prose on the way out of the
 * client.
 *
 * <h2>Failing toward PERMANENT</h2>
 *
 * An unrecognised failure classifies as {@link AiFailureKind#PERMANENT}. That is the conservative
 * direction for both consumers: a read that could have been retried is merely reported, while a
 * write is neither retried nor declared uncertain on a guess. Calling an unknown failure transient
 * would be the opposite — a retry loop on something that will never succeed.
 *
 * <p>{@link OptimisticLockException} is deliberately TRANSIENT rather than PERMANENT: somebody else
 * changed the row, so a fresh read really can succeed. It is still never a licence to retry a write
 * — the write path re-reads the record before deciding anything, and a row that has moved is by
 * definition a record whose state must be looked at again.
 */
public final class AiFailureClassifier {

  private AiFailureClassifier() {}

  /** The kind of failure this throwable represents; never null. */
  public static AiFailureKind classify(Throwable error) {
    for (Throwable t = error; t != null; t = t.getCause()) {
      AiFailureKind byType = byType(t);
      if (byType != null) {
        return byType;
      }
      if (t.getCause() == t) {
        // A self-referential cause chain. Rare, and a loop here would hang the turn it is
        // describing.
        break;
      }
    }
    return byMessage(message(error));
  }

  /** True when the outcome of whatever produced this failure is unknown. */
  public static boolean isAmbiguous(Throwable error) {
    return classify(error).isAmbiguous();
  }

  private static AiFailureKind byType(Throwable t) {
    // Timeouts first: the outcome is unknown, and that answer must not be overridden by anything
    // below it. A SocketTimeoutException is a read timeout — the request was sent.
    if (t instanceof HttpTimeoutException
        || t instanceof SocketTimeoutException
        || t instanceof TimeoutException
        || t instanceof QueryTimeoutException
        || t instanceof LockTimeoutException
        || t instanceof PessimisticLockException) {
      return AiFailureKind.TIMEOUT;
    }
    // An interrupted I/O that is not a timeout: the thread was stopped mid-request, so what the
    // other side did with it is equally unknown.
    if (t instanceof InterruptedIOException) {
      return AiFailureKind.TIMEOUT;
    }
    // Never established a usable connection, or the database explicitly said "try again". Nothing
    // on the far side saw the work.
    if (t instanceof ConnectException
        || t instanceof UnknownHostException
        || t instanceof NoRouteToHostException
        || t instanceof SQLTransientException
        || t instanceof SQLRecoverableException
        || t instanceof OptimisticLockException) {
      return AiFailureKind.TRANSIENT;
    }
    if (t instanceof SocketException) {
      // A reset or a broken pipe. The request may have been delivered in full before the socket
      // died, so this is not the same as never having connected.
      return AiFailureKind.TIMEOUT;
    }
    return null;
  }

  /**
   * The fallback, for failures that arrive as prose.
   *
   * <p>Kept narrow on purpose: only words that name the transport condition itself, never words
   * that merely appear in a business message. Anything unmatched stays PERMANENT.
   */
  static AiFailureKind byMessage(String message) {
    if (message == null || message.isEmpty()) {
      return AiFailureKind.PERMANENT;
    }
    if (message.contains("timed out") || message.contains("timeout")) {
      return AiFailureKind.TIMEOUT;
    }
    if (message.contains("connection reset")
        || message.contains("broken pipe")
        || message.contains("goaway")
        || message.contains("eof reached")) {
      return AiFailureKind.TIMEOUT;
    }
    if (message.contains("transient")
        || message.contains("connection refused")
        || message.contains("cannot reach")
        || message.contains("temporarily unavailable")
        || message.contains("too many requests")) {
      return AiFailureKind.TRANSIENT;
    }
    return AiFailureKind.PERMANENT;
  }

  /** The whole chain's messages, lower-cased, so a wrapped cause is matched as readily. */
  private static String message(Throwable error) {
    StringBuilder sb = new StringBuilder();
    for (Throwable t = error; t != null; t = t.getCause()) {
      if (t.getMessage() != null) {
        sb.append(t.getMessage()).append(' ');
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  /**
   * The kind an HTTP status implies, or null when the status is not a failure this decides about.
   *
   * <p>429 and 5xx are the service saying it could not serve this request now. 408 and 504 are
   * timeouts reported by an intermediary, so the origin may still have done the work — which is why
   * they are not lumped in with the rest of the 5xx range.
   */
  public static AiFailureKind ofHttpStatus(int status) {
    if (status == 408 || status == 504) {
      return AiFailureKind.TIMEOUT;
    }
    if (status == 429 || (status >= 500 && status < 600)) {
      return AiFailureKind.TRANSIENT;
    }
    return null;
  }
}
