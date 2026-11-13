package com.axelor.apps.aiocr.service.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Decision-level proof of the cleanup, without a database — the same split {@link
 * CompanyScopePermissionMigrationTest} uses: the repository plumbing needs a real database and
 * belongs in the integration suite ({@link ObsoleteAiOcrMetadataCleanupIntegrationTest}), but what
 * would actually cause harm here (which exact names get deleted, whether a second run is a true
 * no-op, and whether a failure can escape {@code cleanupOnStartup()}) does not.
 */
class ObsoleteAiOcrMetadataCleanupTest {

  /**
   * Simulates the four tables as plain name sets: {@code cleanup()} is exercised for real, only the
   * repository calls are swapped for set membership checks.
   */
  private static class Harness extends ObsoleteAiOcrMetadataCleanup {
    final Set<String> menus;
    final Set<String> actions;
    final Set<String> views;
    final Set<String> permissions;
    final Set<String> permissionLinks = new HashSet<>();

    Harness(Set<String> menus, Set<String> actions, Set<String> views, Set<String> permissions) {
      super(null, null, null, null, null, null);
      this.menus = menus;
      this.actions = actions;
      this.views = views;
      this.permissions = permissions;
    }

    @Override
    protected boolean deleteMenus(String name, Result result) {
      if (menus.remove(name)) {
        result.menus++;
      }
      return true; // this harness has no FK, so a name is always resolved in one pass
    }

    @Override
    protected int deleteActions(String name) {
      return actions.remove(name) ? 1 : 0;
    }

    @Override
    protected int deleteViews(String name) {
      return views.remove(name) ? 1 : 0;
    }

    @Override
    protected int deletePermission(String name, Result result) {
      if (!permissions.remove(name)) {
        return 0;
      }
      // A populated database would have this permission linked to the role it was written for;
      // the real method unlinks every such holder before deleting the row.
      if (permissionLinks.remove(name)) {
        result.permissionUnlinks++;
      }
      return 1;
    }

    @Override
    protected Result runTransactionally() {
      // No EntityManager in this pure-decision test; cleanup() here does not touch one either.
      return cleanup();
    }
  }

  /** One row from every list a populated database would still have, plus one unrelated survivor. */
  private static Harness populated() {
    Set<String> menus =
        new HashSet<>(
            java.util.Arrays.asList(
                "ai-ocr-monitored-folder-root",
                "ai-ocr-storage-report-root",
                "account-root-ai-ocr-docs",
                "account-root-ai-ocr-inbox",
                "account-root-ai-ocr-failed",
                "ai-ocr-upload-root")); // current, unrelated menu — must survive
    Set<String> actions =
        new HashSet<>(
            java.util.Arrays.asList(
                "action-ai-ocr-monitored-folder-defaults",
                "action-ai-ocr-monitored-folder-view",
                "action-ai-ocr-storage-method-refresh",
                "action-ai-ocr-storage-method-open",
                "action-ai-ocr-storage-report-view",
                "action-ai-ocr-document-defaults",
                "action-ai-ocr-document-approve",
                "action-ai-ocr-document-reject",
                "action-ai-ocr-document-view",
                "action-ai-ocr-inbox",
                "action-ai-ocr-upload-view-failed",
                "action-ai-ocr-upload-method-process-all",
                "action-ai-ocr-upload-view-client")); // current, unrelated action — must survive
    Set<String> views =
        new HashSet<>(
            java.util.Arrays.asList(
                "ai-ocr-monitored-folder-grid",
                "ai-ocr-monitored-folder-form",
                "ai-ocr-storage-report-form",
                "ai-ocr-document-grid",
                "ai-ocr-document-form",
                "ai-ocr-document-line-grid",
                "ai-ocr-document-line-form",
                "ai-ocr-upload-grid")); // current, unrelated view — must survive
    Set<String> permissions =
        new HashSet<>(
            java.util.Arrays.asList(
                CompanyScopePermissionMigration.AIOCR_MONITORED_FOLDER_SCOPED,
                CompanyScopePermissionMigration.AIOCR_SCOPED,
                "perm.aiocr.upload.internal")); // current, unrelated permission — must survive
    Harness harness = new Harness(menus, actions, views, permissions);
    harness.permissionLinks.add(CompanyScopePermissionMigration.AIOCR_MONITORED_FOLDER_SCOPED);
    return harness;
  }

  @Test
  void firstRunRemovesExactlyTheObsoleteRowsAndLeavesEverythingElse() {
    Harness harness = populated();

    ObsoleteAiOcrMetadataCleanup.Result result = harness.cleanup();

    assertEquals(5, result.getMenus());
    assertEquals(12, result.getActions());
    assertEquals(7, result.getViews());
    assertEquals(2, result.getPermissions());
    assertEquals(1, result.getPermissionUnlinks());
    assertEquals(0, result.getMenusBlockedByChildren());
    assertTrue(harness.menus.contains("ai-ocr-upload-root"), "unrelated menu must survive");
    assertTrue(
        harness.actions.contains("action-ai-ocr-upload-view-client"),
        "unrelated action must survive");
    assertTrue(harness.views.contains("ai-ocr-upload-grid"), "unrelated view must survive");
    assertTrue(
        harness.permissions.contains("perm.aiocr.upload.internal"),
        "unrelated permission must survive");
  }

  @Test
  void aSecondRunOnAnAlreadyCleanDatabaseIsANoop() {
    // Nothing populated: every database that never had the feature, or already ran this once.
    Harness harness =
        new Harness(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>());

    ObsoleteAiOcrMetadataCleanup.Result result = harness.cleanup();

    assertTrue(result.isNoop());
  }

  @Test
  void runningTwiceOnTheSamePopulatedStateConvergesToNoopSecondTime() {
    Harness harness = populated();

    assertTrue(harness.cleanup().getMenus() > 0, "the first run must find work to do");
    assertTrue(harness.cleanup().isNoop(), "the second run must find nothing left to remove");
  }

  @Test
  void aFailureInsideCleanupNeverEscapesCleanupOnStartup() {
    Harness harness =
        new Harness(new HashSet<>(), new HashSet<>(), new HashSet<>(), new HashSet<>()) {
          @Override
          protected boolean deleteMenus(String name, Result result) {
            throw new IllegalStateException("simulated failure, e.g. a bad query or a commit");
          }
        };

    assertDoesNotThrow(harness::cleanupOnStartup, "a cleanup failure must never abort startup");
  }
}
