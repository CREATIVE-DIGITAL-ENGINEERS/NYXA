package com.axelor.apps.aiocr.service.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.aiocr.module.AiOcrTest;
import com.axelor.auth.db.Group;
import com.axelor.auth.db.Permission;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.repo.GroupRepository;
import com.axelor.auth.db.repo.PermissionRepository;
import com.axelor.auth.db.repo.RoleRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaAction;
import com.axelor.meta.db.MetaMenu;
import com.axelor.meta.db.MetaView;
import com.axelor.meta.db.repo.MetaActionRepository;
import com.axelor.meta.db.repo.MetaMenuRepository;
import com.axelor.meta.db.repo.MetaViewRepository;
import org.junit.jupiter.api.Test;

/**
 * DB-backed (HSQLDB) proof of the two things the pure-decision test in {@link
 * ObsoleteAiOcrMetadataCleanupTest} cannot exercise: a real foreign key, and a real transaction.
 *
 * <p>{@code account-root-ai-ocr-docs} / {@code account-root-ai-ocr-inbox} were never actually
 * nested in production (see the class javadoc on {@link ObsoleteAiOcrMetadataCleanup}), but the
 * fixture nests them here anyway — deliberately, so this test proves the child-before-parent guard
 * against {@code MetaMenu.parent}'s real foreign key, not just against the plain {@code Set} the
 * unit test uses.
 */
class ObsoleteAiOcrMetadataCleanupIntegrationTest extends AiOcrTest {

  private MetaMenuRepository menuRepo() {
    return Beans.get(MetaMenuRepository.class);
  }

  private MetaActionRepository actionRepo() {
    return Beans.get(MetaActionRepository.class);
  }

  private MetaViewRepository viewRepo() {
    return Beans.get(MetaViewRepository.class);
  }

  private PermissionRepository permissionRepo() {
    return Beans.get(PermissionRepository.class);
  }

  private RoleRepository roleRepo() {
    return Beans.get(RoleRepository.class);
  }

  private GroupRepository groupRepo() {
    return Beans.get(GroupRepository.class);
  }

  private ObsoleteAiOcrMetadataCleanup cleanup() {
    return Beans.get(ObsoleteAiOcrMetadataCleanup.class);
  }

  @Test
  void cleanupRemovesNestedMenusTheirActionAndPermissionLinksThenNoopsOnRerun() {
    JPA.runInTransaction(
        () -> {
          MetaAction action = new MetaAction();
          action.setName("action-ai-ocr-document-view");
          action.setType("action-view");
          action.setModel("com.axelor.apps.aiocr.db.AiOcrUpload");
          action.setXml("<action-view name=\"action-ai-ocr-document-view\"/>");
          actionRepo().save(action);

          MetaMenu parent = new MetaMenu();
          parent.setName("account-root-ai-ocr-docs");
          parent.setTitle("OCR Invoice Review");
          parent.setAction(action);
          menuRepo().save(parent);

          // Deliberately nested under an obsolete parent, unlike production, to prove the
          // child-first guard against a real FK — see the class javadoc.
          MetaMenu child = new MetaMenu();
          child.setName("account-root-ai-ocr-inbox");
          child.setTitle("OCR Inbox");
          child.setParent(parent);
          menuRepo().save(child);

          MetaView view = new MetaView();
          view.setName("ai-ocr-document-form");
          view.setTitle("AI OCR Document");
          view.setType("form");
          view.setXml("<form name=\"ai-ocr-document-form\"/>");
          viewRepo().save(view);

          Role role = new Role();
          role.setName("ObsoleteCleanupTestRole");
          roleRepo().save(role);

          Group group = new Group();
          group.setCode("obsoleteCleanupTestGroup");
          group.setName("Obsolete cleanup test group");
          groupRepo().save(group);

          Permission permission = new Permission();
          permission.setName(CompanyScopePermissionMigration.AIOCR_MONITORED_FOLDER_SCOPED);
          permission.setObject("com.axelor.apps.aiocr.db.AiOcrMonitoredFolder");
          permission.setCanRead(Boolean.TRUE);
          role.addPermission(permission);
          group.addPermission(permission);
          permissionRepo().save(permission);
          roleRepo().save(role);
          groupRepo().save(group);
        });

    assertDoesNotThrow(() -> cleanup().cleanupOnStartup());

    JPA.runInTransaction(
        () -> {
          assertEquals(0, countByName("MetaMenu", "account-root-ai-ocr-docs"));
          assertEquals(0, countByName("MetaMenu", "account-root-ai-ocr-inbox"));
          assertEquals(0, countByName("MetaAction", "action-ai-ocr-document-view"));
          assertEquals(0, countByName("MetaView", "ai-ocr-document-form"));

          Permission permission =
              permissionRepo()
                  .all()
                  .filter("self.name = :name")
                  .bind("name", CompanyScopePermissionMigration.AIOCR_MONITORED_FOLDER_SCOPED)
                  .fetchOne();
          assertNull(permission, "the retired permission row itself must be gone");

          Role role =
              roleRepo()
                  .all()
                  .filter("self.name = :name")
                  .bind("name", "ObsoleteCleanupTestRole")
                  .fetchOne();
          assertEquals(0, role.getPermissions().size(), "the role must no longer hold it");

          Group group =
              groupRepo()
                  .all()
                  .filter("self.code = :code")
                  .bind("code", "obsoleteCleanupTestGroup")
                  .fetchOne();
          assertEquals(0, group.getPermissions().size(), "the group must no longer hold it");
        });

    // Repeated boot on the same, now-clean database: must find nothing left and still not throw.
    assertDoesNotThrow(() -> cleanup().cleanupOnStartup());
  }

  @Test
  void aFailureDuringTheRealTransactionIsLoggedNotThrown() {
    ObsoleteAiOcrMetadataCleanup failing =
        new ObsoleteAiOcrMetadataCleanup(
            menuRepo(), actionRepo(), viewRepo(), permissionRepo(), roleRepo(), groupRepo()) {
          @Override
          protected boolean deleteMenus(String name, Result result) {
            throw new IllegalStateException("simulated failure inside the real transaction");
          }
        };

    assertDoesNotThrow(failing::cleanupOnStartup, "a cleanup failure must never abort startup");
  }

  private long countByName(String entityName, String name) {
    return JPA.em()
        .createQuery("SELECT COUNT(e) FROM " + entityName + " e WHERE e.name = :name", Long.class)
        .setParameter("name", name)
        .getSingleResult();
  }
}
