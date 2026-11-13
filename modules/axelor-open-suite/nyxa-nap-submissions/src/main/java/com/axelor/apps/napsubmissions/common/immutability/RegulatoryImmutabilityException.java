package com.axelor.apps.napsubmissions.common.immutability;

import javax.persistence.PersistenceException;

/**
 * Raised by {@link RegulatoryImmutabilityGuard} when a write would change a value that is frozen
 * once persisted — a finalized artifact's bytes/hash, or the identity/signed artifacts of a signed
 * submission.
 *
 * <p>It extends {@link PersistenceException} on purpose: the guard lives inside repository {@code
 * save}/{@code remove}, so an {@code unchecked} persistence-layer exception propagates out of a
 * transaction exactly like a constraint violation, without every caller having to declare it. The
 * message never carries a secret or any payload content.
 */
public class RegulatoryImmutabilityException extends PersistenceException {

  private static final long serialVersionUID = 1L;

  public RegulatoryImmutabilityException(String message) {
    super(message);
  }
}
