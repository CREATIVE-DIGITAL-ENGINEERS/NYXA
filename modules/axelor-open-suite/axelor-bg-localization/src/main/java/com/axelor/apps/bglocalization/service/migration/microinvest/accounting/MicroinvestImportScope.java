package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

/**
 * Only the atomic importer may post/reconcile its historical records. Always cleared on rollback.
 */
public final class MicroinvestImportScope implements AutoCloseable {
  private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

  private MicroinvestImportScope() {
    if (isActive()) {
      throw new IllegalStateException("Nested Microinvest import");
    }
    ACTIVE.set(true);
  }

  static MicroinvestImportScope open() {
    return new MicroinvestImportScope();
  }

  public static boolean isActive() {
    return Boolean.TRUE.equals(ACTIVE.get());
  }

  @Override
  public void close() {
    ACTIVE.remove();
  }
}
