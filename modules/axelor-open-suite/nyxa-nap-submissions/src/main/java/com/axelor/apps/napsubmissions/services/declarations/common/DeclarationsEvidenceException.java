package com.axelor.apps.napsubmissions.services.declarations.common;

/**
 * A signed-evidence precondition of an НАП submit was not met: the submission is not signed, a file
 * is missing its payload/signature/certificate, or the stored bytes no longer match what was
 * signed. It is raised BEFORE any network call — nothing has been sent to НАП when it is thrown.
 *
 * <p>The message is user-facing (Bulgarian, via {@code I18n}) and never carries payload bytes, a
 * PIN or any other secret.
 */
public class DeclarationsEvidenceException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public DeclarationsEvidenceException(String message) {
    super(message);
  }
}
