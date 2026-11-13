package com.axelor.apps.aiaccountant.security;

import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import java.util.concurrent.Callable;

/**
 * Explicit execution context for AI-driven work.
 *
 * <p>Why this exists: the OCR intake pipeline runs on background worker threads ({@code
 * AiOcrProcessingExecutor}), where there is no authenticated Shiro subject. Treating "no user" as
 * implicitly trusted would mean any code path that happens to lose its subject silently gains full
 * access — an accident waiting to happen. So the trust is made explicit and narrow instead:
 *
 * <ul>
 *   <li>the default is UNTRUSTED — services refuse when there is no authenticated user;
 *   <li>trusted mode exists only inside {@link #callAsSystem}, is bound to the current thread, and
 *       is always cleared in a finally block;
 *   <li>trusted mode must name the company it is acting for, so a system caller is still scoped to
 *       one company rather than the whole database;
 *   <li>nothing here is reachable from tool arguments: there is no field, no parameter and no JSON
 *       path that can turn it on. Only Java code that already holds the record can open it.
 * </ul>
 */
public final class AiExecutionContext {

  /** Set only by {@link #callAsSystem}; inherited by nothing, cleared in a finally block. */
  private static final ThreadLocal<SystemScope> SYSTEM = new ThreadLocal<>();

  /** Set only by {@link #callWithWriteMode}; purely descriptive, grants nothing. */
  private static final ThreadLocal<String> WRITE_MODE = new ThreadLocal<>();

  /** Set only by {@link #callAsToolWrite}: an AI write tool is running on this thread. */
  private static final ThreadLocal<Boolean> TOOL_WRITE = new ThreadLocal<>();

  private AiExecutionContext() {}

  /** The company a system-mode block is acting for, plus why it was opened (for logs). */
  public static final class SystemScope {
    private final Long companyId;
    private final String reason;

    SystemScope(Long companyId, String reason) {
      this.companyId = companyId;
      this.reason = reason;
    }

    public Long getCompanyId() {
      return companyId;
    }

    public String getReason() {
      return reason;
    }
  }

  /**
   * Runs {@code work} as a trusted internal caller scoped to one company.
   *
   * <p>Only call this from Java that already legitimately holds the record — never from anything
   * driven by model output.
   *
   * @param companyId the company the work is confined to; may be null only when the record itself
   *     has no company
   * @param reason short description used in log lines, e.g. "ocr-intake"
   */
  public static <T> T callAsSystem(Long companyId, String reason, Callable<T> work) {
    if (SYSTEM.get() != null) {
      throw new IllegalStateException("System execution context is already open on this thread.");
    }
    SYSTEM.set(new SystemScope(companyId, reason));
    try {
      return work.call();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException("System-context work failed: " + e.getMessage(), e);
    } finally {
      SYSTEM.remove();
    }
  }

  /** Void form of {@link #callAsSystem}. */
  public static void runAsSystem(Long companyId, String reason, Runnable work) {
    callAsSystem(
        companyId,
        reason,
        () -> {
          work.run();
          return null;
        });
  }

  /** The open system scope, or null when this thread is a normal user request. */
  public static SystemScope systemScope() {
    return SYSTEM.get();
  }

  public static boolean isSystem() {
    return SYSTEM.get() != null;
  }

  /**
   * @return true when the caller is either an authenticated user or an explicit system context
   */
  public static boolean isAuthorisedCaller() {
    return AuthUtils.getUser() != null || isSystem();
  }

  /**
   * The company a system context declared, or null when this thread is a normal user request — or a
   * system context that named no company.
   *
   * <p>This is raw state, not an authorisation answer: null does NOT mean "no restriction". {@link
   * AiCompanyScope} is the only thing that turns this into a scope, and it refuses a system context
   * with no company rather than treating it as unrestricted.
   */
  public static Long systemCompanyId() {
    SystemScope scope = SYSTEM.get();
    return scope == null ? null : scope.getCompanyId();
  }

  /**
   * Labels how the write about to happen was authorised, so the audit note on the record says it.
   *
   * <p>Descriptive only: it opens no permission and is never consulted by a guard. It exists
   * because "changed by jdoe" and "changed by jdoe, after they approved it in the chat" are
   * different facts to a reviewer reading {@code aiValidationNotes} six months later.
   */
  public static <T> T callWithWriteMode(String mode, Callable<T> work) {
    String previous = WRITE_MODE.get();
    WRITE_MODE.set(mode);
    try {
      return work.call();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e.getMessage(), e);
    } finally {
      if (previous == null) {
        WRITE_MODE.remove();
      } else {
        WRITE_MODE.set(previous);
      }
    }
  }

  /**
   * Runs an AI write tool, marked so that what it writes can be held to "drafts only" ( * {@code
   * accounting.ai.write} creates and changes drafts). The posting guard in axelor-ai-accounting
   * refuses an entry made Accounted while this is open, whatever service the tool went through.
   *
   * <p>Restrictive, never permissive: it opens nothing, so a nested call is harmless.
   */
  public static <T> T callAsToolWrite(Callable<T> work) {
    Boolean previous = TOOL_WRITE.get();
    TOOL_WRITE.set(Boolean.TRUE);
    try {
      return work.call();
    } catch (RuntimeException e) {
      throw e;
    } catch (Exception e) {
      throw new IllegalStateException(e.getMessage(), e);
    } finally {
      if (previous == null) {
        TOOL_WRITE.remove();
      }
    }
  }

  /** Whether an AI write tool is running on this thread ({@link #callAsToolWrite}). */
  public static boolean isToolWrite() {
    return Boolean.TRUE.equals(TOOL_WRITE.get());
  }

  /** How the current write was authorised, or null when nothing declared it. */
  public static String writeMode() {
    return WRITE_MODE.get();
  }

  /** Short description of the caller, for audit notes and log lines. */
  public static String callerDescription() {
    String suffix = WRITE_MODE.get() == null ? "" : " (" + WRITE_MODE.get() + ")";
    User user = AuthUtils.getUser();
    if (user != null) {
      return user.getCode() + suffix;
    }
    SystemScope scope = SYSTEM.get();
    return (scope == null ? "unauthenticated" : "system:" + scope.getReason()) + suffix;
  }
}
