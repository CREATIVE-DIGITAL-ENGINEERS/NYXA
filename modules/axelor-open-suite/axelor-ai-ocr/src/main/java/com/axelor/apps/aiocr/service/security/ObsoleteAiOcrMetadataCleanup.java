package com.axelor.apps.aiocr.service.security;

import com.axelor.auth.db.Group;
import com.axelor.auth.db.Permission;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.repo.GroupRepository;
import com.axelor.auth.db.repo.PermissionRepository;
import com.axelor.auth.db.repo.RoleRepository;
import com.axelor.db.JPA;
import com.axelor.meta.db.MetaAction;
import com.axelor.meta.db.MetaMenu;
import com.axelor.meta.db.MetaView;
import com.axelor.meta.db.repo.MetaActionRepository;
import com.axelor.meta.db.repo.MetaMenuRepository;
import com.axelor.meta.db.repo.MetaViewRepository;
import com.google.inject.Singleton;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Deletes the {@code MetaMenu}/{@code MetaAction}/{@code MetaView}/{@code Permission} rows left
 * behind by three axelor-ai-ocr features whose source has since been removed, on every boot,
 * idempotently. Same pattern as {@link CompanyScopePermissionMigration} and {@code
 * BgCityImportService}: a startup migration, not data-init, because data-init only ever fixes a
 * fresh install and Axelor's {@code ViewLoader} never deletes a {@code MetaMenu}/{@code
 * MetaView}/{@code MetaAction} row whose XML has gone (see the retired {@code Menu.xml} comments in
 * commits f27ef00f and 28a491a0, which document exactly this and left the deletion as a manual
 * step). This class turns that manual step into code.
 *
 * <h2>What is deleted, and why each row is confirmed gone from source</h2>
 *
 * <ol>
 *   <li><b>Monitored Folders</b> (commit 28a491a0) — entity {@code AiOcrMonitoredFolder}, menu,
 *       grid/form views, action-record and action-view, plus the permission that named it.
 *   <li><b>OCR Storage Report</b> (commits 1d47234c / f27ef00f) — the temporary read-only report:
 *       menu, form view, the two action-methods and the action-view that opened it.
 *   <li><b>OCR Invoice Review / OCR Inbox / Failed OCR jobs</b> (Phase 1, commits 8bddea31 /
 *       f7861c84) — the pre-{@code AiOcrUpload} review surface (entity {@code AiOcrDocument} /
 *       {@code AiOcrDocumentLine}, renamed away before axelor-ai-ocr reached any real tenant
 *       database) and the two menus that briefly replaced it, along with their action-view.
 *   <li><b>Process all</b> (commit 7cd5e578) — one action-method backing a toolbar button that no
 *       longer exists in {@code AiOcrUpload.xml}.
 * </ol>
 *
 * <p>Every name below was checked against current source (none of these XML elements exist any
 * more) and against git history (each has a commit that removed it). Nothing is matched by prefix:
 * an unrelated {@code ai-ocr-*} row that is still defined in a current view file is not in any list
 * here and is left alone.
 *
 * <p><b>Domain tables are untouched.</b> {@code AiOcrMonitoredFolder}, {@code AiOcrStorageReport},
 * {@code AiOcrDocument} and {@code AiOcrDocumentLine} keep whatever rows and columns they have;
 * only Axelor metadata (menus/views/actions) and the retired permission rows are removed here.
 *
 * <h2>Menu parent/child ordering</h2>
 *
 * <p>{@code MetaMenu.parent} is a self-referencing foreign key, so a parent row cannot be deleted
 * while a child still points at it. Every historical {@code <menuitem>} definition for the five
 * names in {@link #OBSOLETE_MENUS} was inspected: {@code ai-ocr-monitored-folder-root} and {@code
 * ai-ocr-storage-report-root} were always top-level (no {@code parent} attribute at all), and
 * {@code account-root-ai-ocr-docs} / {@code account-root-ai-ocr-inbox} / {@code
 * account-root-ai-ocr-failed} all carried {@code parent="account-root"} — a stock axelor-account
 * menu, not one of these five. None of them was ever the parent of another, so no fixed ordering
 * between them is actually required. {@link #deleteMenusChildFirst} still checks for a live child
 * at delete time rather than trusting that history: each pass deletes whichever of the remaining
 * names currently has no child, and retries the rest, so a parent/child pair anywhere in this list
 * — today or after a future addition — resolves within one {@link #cleanup()} call regardless of
 * the list's own order. Only a row still blocked after a pass that removed nothing (a live child
 * outside this list entirely) is left in place and logged instead of throwing.
 *
 * <h2>Why this is safe to run on every boot, on every tenant database</h2>
 *
 * <p>Each step looks the row up by exact name first and does nothing if it is already gone, so a
 * repeat run — or a database that never had the row — is a no-op. Nothing is matched by a wildcard
 * or prefix, so a differently-named row already accidentally reusing part of one of these names is
 * never touched. Menus are deleted before the actions they point at (see above), and a {@code
 * Permission} is unlinked from every {@code Role}/{@code Group} still holding it before the row
 * itself is deleted (the join tables would otherwise reject the delete).
 *
 * <h2>Why {@link #cleanupOnStartup()} opens its own transaction</h2>
 *
 * <p>{@link #cleanup()} used to carry {@code @Transactional} and rely on Guice's AOP interceptor to
 * open the transaction. That only works when the call arrives through the Guice-managed proxy —
 * calling {@code cleanup()} from another method of this same class, as {@code cleanupOnStartup()}
 * does, is a plain Java self-invocation that never goes through the proxy, so no transaction would
 * actually be opened. {@link #cleanupOnStartup()} instead wraps the call itself with {@link
 * JPA#withTransaction}, the same explicit-transaction pattern already used elsewhere in this module
 * (see {@code AiOcrArchiveExtractService#reclaimArchiveIfFullyExtracted}) and in the stock batch
 * services — it joins an already-open transaction if one exists and opens/commits its own
 * otherwise. That call, and everything {@link #cleanup()} does inside it, stays inside {@link
 * #cleanupOnStartup()}'s try/catch, so a failure here — commit included — is logged and never
 * aborts server startup.
 */
@Singleton
public class ObsoleteAiOcrMetadataCleanup {

  private static final Logger LOG = LoggerFactory.getLogger(ObsoleteAiOcrMetadataCleanup.class);

  private static final List<String> OBSOLETE_MENUS =
      Collections.unmodifiableList(
          Arrays.asList(
              "ai-ocr-monitored-folder-root",
              "ai-ocr-storage-report-root",
              "account-root-ai-ocr-docs",
              "account-root-ai-ocr-inbox",
              "account-root-ai-ocr-failed"));

  private static final List<String> OBSOLETE_ACTIONS =
      Collections.unmodifiableList(
          Arrays.asList(
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
              "action-ai-ocr-upload-method-process-all"));

  private static final List<String> OBSOLETE_VIEWS =
      Collections.unmodifiableList(
          Arrays.asList(
              "ai-ocr-monitored-folder-grid",
              "ai-ocr-monitored-folder-form",
              "ai-ocr-storage-report-form",
              "ai-ocr-document-grid",
              "ai-ocr-document-form",
              "ai-ocr-document-line-grid",
              "ai-ocr-document-line-form"));

  /**
   * Retired permission rows. {@code perm.aiocr.monitored-folder.internal} named the Monitored
   * Folders entity directly; {@code perm.aiocr.all.internal} was the wildcard-object grant that
   * {@link CompanyScopePermissionMigration} already unlinks from {@code AiOcrInternal} (see its
   * class javadoc) but deliberately never deletes. Both were dropped from {@code
   * auth_permission.csv} the same commit that retired the feature they backed, so a fresh install
   * never creates them again.
   */
  private static final List<String> OBSOLETE_PERMISSIONS =
      Collections.unmodifiableList(
          Arrays.asList(
              CompanyScopePermissionMigration.AIOCR_MONITORED_FOLDER_SCOPED,
              CompanyScopePermissionMigration.AIOCR_SCOPED));

  private final MetaMenuRepository menuRepo;
  private final MetaActionRepository actionRepo;
  private final MetaViewRepository viewRepo;
  private final PermissionRepository permissionRepo;
  private final RoleRepository roleRepo;
  private final GroupRepository groupRepo;

  @Inject
  public ObsoleteAiOcrMetadataCleanup(
      MetaMenuRepository menuRepo,
      MetaActionRepository actionRepo,
      MetaViewRepository viewRepo,
      PermissionRepository permissionRepo,
      RoleRepository roleRepo,
      GroupRepository groupRepo) {
    this.menuRepo = menuRepo;
    this.actionRepo = actionRepo;
    this.viewRepo = viewRepo;
    this.permissionRepo = permissionRepo;
    this.roleRepo = roleRepo;
    this.groupRepo = groupRepo;
  }

  /** A concise count of what one run actually removed, by metadata type. */
  public static final class Result {
    // Package-private, not private: the test Harness (same package) overrides the delete* hooks
    // and needs to record what it "removed" too.
    int menus;
    int actions;
    int views;
    int permissions;
    int permissionUnlinks;
    int menusBlockedByChildren;

    public int getMenus() {
      return menus;
    }

    public int getActions() {
      return actions;
    }

    public int getViews() {
      return views;
    }

    public int getPermissions() {
      return permissions;
    }

    public int getPermissionUnlinks() {
      return permissionUnlinks;
    }

    /** Obsolete menus left in place this run because a live child still points at them. */
    public int getMenusBlockedByChildren() {
      return menusBlockedByChildren;
    }

    public boolean isNoop() {
      return menus == 0
          && actions == 0
          && views == 0
          && permissions == 0
          && permissionUnlinks == 0
          && menusBlockedByChildren == 0;
    }
  }

  /**
   * Deletes every obsolete row that is still present, in FK-safe order: menus first (each checked
   * for a live child before it is removed — see the class javadoc), then the actions those menus
   * pointed at, then views, then permissions (unlinked from every role/group first).
   *
   * <p>Must run inside a transaction. {@link #cleanupOnStartup()} provides one explicitly; a caller
   * with its own open transaction (an integration test, for instance) may call this directly.
   */
  public Result cleanup() {
    Result result = new Result();
    deleteMenusChildFirst(result);
    for (String name : OBSOLETE_ACTIONS) {
      result.actions += deleteActions(name);
    }
    for (String name : OBSOLETE_VIEWS) {
      result.views += deleteViews(name);
    }
    for (String name : OBSOLETE_PERMISSIONS) {
      result.permissions += deletePermission(name, result);
    }
    return result;
  }

  /**
   * Resolves every name in {@link #OBSOLETE_MENUS}, retrying whichever names are still blocked by a
   * live child after each pass. History proves none of these five ever nest (see the class
   * javadoc), so one pass always suffices in practice — the retry only matters if that ever stops
   * being true, and it lets deletion succeed within a single {@link #cleanup()} call regardless of
   * which order {@link #OBSOLETE_MENUS} happens to list them in. A pass that removes nothing is
   * proof no further progress is possible (a live child outside this list, or a cycle), so the loop
   * stops there rather than spinning forever, and whatever is still pending is logged and left in
   * place.
   */
  protected void deleteMenusChildFirst(Result result) {
    List<String> pending = new ArrayList<>(OBSOLETE_MENUS);
    boolean progress = true;
    while (progress && !pending.isEmpty()) {
      progress = false;
      for (Iterator<String> it = pending.iterator(); it.hasNext(); ) {
        if (deleteMenus(it.next(), result)) {
          it.remove();
          progress = true;
        }
      }
    }
    for (String stillBlocked : pending) {
      result.menusBlockedByChildren++;
      LOG.warn(
          "Obsolete AI-OCR menu {} still has a live child menu pointing at it; left in place this"
              + " run so its parent foreign key is never violated.",
          stillBlocked);
    }
  }

  /**
   * Deletes every {@code MetaMenu} row named {@code name}. Returns {@code true} once nothing named
   * {@code name} is left blocked by a live child — i.e. every matching row was either deleted or
   * never existed — so {@link #deleteMenusChildFirst} knows not to retry it.
   */
  protected boolean deleteMenus(String name, Result result) {
    List<MetaMenu> rows = menuRepo.all().filter("self.name = :name").bind("name", name).fetch();
    boolean blocked = false;
    for (MetaMenu row : rows) {
      if (countChildMenus(row) > 0) {
        blocked = true;
        continue;
      }
      menuRepo.remove(row);
      result.menus++;
    }
    return !blocked;
  }

  protected long countChildMenus(MetaMenu parent) {
    return menuRepo
        .all()
        .filter("self.parent.id = :parentId")
        .bind("parentId", parent.getId())
        .count();
  }

  protected int deleteActions(String name) {
    List<MetaAction> rows = actionRepo.all().filter("self.name = :name").bind("name", name).fetch();
    for (MetaAction row : rows) {
      actionRepo.remove(row);
    }
    return rows.size();
  }

  protected int deleteViews(String name) {
    List<MetaView> rows = viewRepo.all().filter("self.name = :name").bind("name", name).fetch();
    for (MetaView row : rows) {
      viewRepo.remove(row);
    }
    return rows.size();
  }

  /** Unlinks {@code name} from every role/group still holding it, then deletes the row. */
  protected int deletePermission(String name, Result result) {
    Permission permission =
        permissionRepo.all().filter("self.name = :name").bind("name", name).fetchOne();
    if (permission == null) {
      return 0;
    }
    List<Role> roles =
        roleRepo
            .all()
            .filter(":permission MEMBER OF self.permissions")
            .bind("permission", permission)
            .fetch();
    for (Role role : roles) {
      role.getPermissions().remove(permission);
      roleRepo.save(role);
      result.permissionUnlinks++;
    }
    List<Group> groups =
        groupRepo
            .all()
            .filter(":permission MEMBER OF self.permissions")
            .bind("permission", permission)
            .fetch();
    for (Group group : groups) {
      group.getPermissions().remove(permission);
      groupRepo.save(group);
      result.permissionUnlinks++;
    }
    permissionRepo.remove(permission);
    return 1;
  }

  /**
   * Opens the transaction {@link #cleanup()} needs and runs it. Overridden in tests that want to
   * exercise {@link #cleanupOnStartup()}'s failure handling without a real {@code EntityManager} —
   * everything else goes through {@link JPA#withTransaction}, joining a transaction already open on
   * the current thread or opening/committing its own.
   */
  protected Result runTransactionally() {
    return JPA.withTransaction(this::cleanup);
  }

  /**
   * Runs the cleanup inside its own transaction and logs a concise count, without ever failing the
   * boot: a stray metadata row is cosmetic, not a reason to take the ERP offline. The transaction —
   * including its commit — happens inside {@link #runTransactionally()}, called from within this
   * try, so a failure at any point (a bad query, a constraint violation, the commit itself) is
   * caught here rather than propagating out of {@code onStartup}.
   */
  public void cleanupOnStartup() {
    try {
      Result result = runTransactionally();
      if (result.isNoop()) {
        LOG.info("Obsolete AI-OCR metadata cleanup: nothing to remove.");
      } else {
        LOG.info(
            "Obsolete AI-OCR metadata cleanup: removed {} menu(s), {} action(s), {} view(s), {}"
                + " permission(s) ({} role/group link(s) unlinked first){}.",
            result.getMenus(),
            result.getActions(),
            result.getViews(),
            result.getPermissions(),
            result.getPermissionUnlinks(),
            result.getMenusBlockedByChildren() > 0
                ? "; " + result.getMenusBlockedByChildren() + " menu(s) blocked by a live child"
                : "");
      }
    } catch (Exception e) {
      LOG.error(
          "Obsolete AI-OCR metadata cleanup failed; a stale menu/view/action/permission row may"
              + " still be present.",
          e);
    }
  }
}
