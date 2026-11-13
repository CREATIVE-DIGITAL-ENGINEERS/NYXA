package com.axelor.apps.aiocr.service.security;

import com.axelor.auth.db.Group;
import com.axelor.auth.db.Permission;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.repo.GroupRepository;
import com.axelor.auth.db.repo.PermissionRepository;
import com.axelor.auth.db.repo.RoleRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Brings existing databases up to active-company isolation, at every boot, idempotently.
 *
 * <h2>Why a startup migration rather than data-init</h2>
 *
 * {@code data-init} CSVs are imported when a module is <em>installed</em>, so editing a CSV fixes
 * only future installations — an existing database keeps whatever permission rows it already has.
 * The rule this project needs applies to both, and it must not depend on somebody remembering to
 * click through Administration → Permissions. So it is expressed as code that runs on every startup
 * and converges: each step checks the current state first and does nothing when it is already
 * right. The same pattern the module already uses for {@code BgCityImportService}.
 *
 * <h2>What it changes, and what it deliberately refuses to</h2>
 *
 * <ol>
 *   <li><b>Invoice read scope.</b> The permissions that already grant Invoice access ({@code
 *       perm.account.Invoice.r} / {@code .rwc} / {@code .rwcde}, shipped by axelor-account) are
 *       re-pointed from the stock company-<em>set</em> condition to the active company. This is a
 *       narrowing of an existing grant, which is the only safe direction: Axelor permissions are
 *       additive, so <em>adding</em> a scoped permission would widen access instead.
 *   <li><b>The AI-OCR internal role.</b> The unconditional {@code perm.aiocr.all} is unlinked from
 *       {@code AiOcrInternal}, whose access now comes from one conditioned, exact-entity permission
 *       — {@code perm.aiocr.upload.internal} (AiOcrUpload) — instead of a package-wildcard row. It
 *       used to be one wildcard-object permission ({@code perm.aiocr.all.internal}, on {@code
 *       com.axelor.apps.aiocr.db.*}), back when every entity in that package had a {@code company}
 *       field; {@code AiOcrUploadAudit} broke that assumption (it has no {@code company} relation,
 *       only a scalar snapshot column), so the wildcard row was retired the same way {@code
 *       perm.aiocr.all} is — detached from the role, left alone as a row. A second named grant,
 *       {@code perm.aiocr.monitored-folder.internal} (AiOcrMonitoredFolder), briefly replaced part
 *       of the wildcard's scope too, but that entity — and the whole Monitored Folders feature —
 *       has since been removed, so that row is now also retired the same way. Without this the
 *       unconditional row keeps winning — see {@code AuthSecurity.isPermitted}, which returns true
 *       on the first unconditional permission that grants the access type. None of these retired
 *       permission rows are deleted; the Admin role still uses {@code perm.aiocr.all}.
 *   <li><b>Wildcard grants are reported, never rewritten.</b> A permission on {@code
 *       com.axelor.apps.account.db.*} also matches InvoiceLine, Tax, PaymentMode and every other
 *       account entity, most of which have no {@code company} field at all. One JPQL condition
 *       cannot be correct for all of them, and stripping the grant would remove access this module
 *       knows nothing about. So those are logged, loudly and by name, with the decision left where
 *       it belongs.
 * </ol>
 *
 * <p>A custom condition somebody wrote by hand is never overwritten either — it is logged instead.
 * Converging on our value would silently undo a deliberate local policy.
 */
@Singleton
public class CompanyScopePermissionMigration {

  private static final Logger LOG = LoggerFactory.getLogger(CompanyScopePermissionMigration.class);

  /**
   * The active-company condition and its parameter, as {@code AuthSecurity} evaluates them.
   *
   * <p><b>Plain {@code ?}, never {@code ?1}.</b> {@code Filter.build} renumbers every {@code ?}
   * character it finds, so a numbered {@code ?1} becomes {@code ?11}: a parameter that doesn't
   * exist. Every read of a specific record (and every list) then fails the permission check. This
   * constant was {@code ?1} until 2026-10-03, which silently denied non-admins all access the
   * migrated rows were meant to scope.
   */
  public static final String COMPANY_CONDITION = "self.company = ?";

  public static final String COMPANY_PARAMS = "__user__.activeCompany";

  /** For an entity whose company is one hop away, e.g. MoveLine → Move. */
  public static final String MOVE_COMPANY_CONDITION = "self.move.company = ?";

  /**
   * Conditions this converges from: the stock company-set condition in the two spellings
   * axelor-account ships, our own target, and the broken {@code ?1} forms earlier versions of this
   * migration wrote (so existing databases repair themselves on the next boot).
   */
  private static final List<String> REPLACEABLE_CONDITIONS =
      Collections.unmodifiableList(
          Arrays.asList(
              "self.company.id in (?)",
              "self.company.id in (?1)",
              COMPANY_CONDITION,
              MOVE_COMPANY_CONDITION,
              "self.company = ?1",
              "self.move.company = ?1"));

  static final String INVOICE_OBJECT = "com.axelor.apps.account.db.Invoice";
  static final String MOVE_OBJECT = "com.axelor.apps.account.db.Move";
  static final String MOVE_LINE_OBJECT = "com.axelor.apps.account.db.MoveLine";
  static final String BANK_RECONCILIATION_OBJECT =
      "com.axelor.apps.bankpayment.db.BankReconciliation";

  /**
   * Company-owned entities whose read permissions are narrowed, and how each reaches its company.
   *
   * <p>Every entry was checked against the schema rather than assumed. {@code Invoice}, {@code
   * Move} and {@code BankReconciliation} carry their own {@code company}; {@code MoveLine} does not
   * and is reached through its {@code Move}.
   *
   * <p><b>BankStatement is deliberately not here.</b> The entity has no company field at all — its
   * lines carry {@code bankDetails} — so there is no company to compare and any condition would be
   * an invention about what belongs to whom. It is reported instead, on every boot, so the gap is
   * visible rather than assumed handled.
   */
  private static final Map<String, String> SCOPED_OBJECTS = scopedObjects();

  private static Map<String, String> scopedObjects() {
    Map<String, String> objects = new LinkedHashMap<>();
    objects.put(INVOICE_OBJECT, COMPANY_CONDITION);
    objects.put(MOVE_OBJECT, COMPANY_CONDITION);
    objects.put(MOVE_LINE_OBJECT, MOVE_COMPANY_CONDITION);
    objects.put(BANK_RECONCILIATION_OBJECT, COMPANY_CONDITION);
    // Its data-init rows shipped the broken "?1" condition; this repairs them on existing
    // databases.
    objects.put(AIOCR_UPLOAD_OBJECT, COMPANY_CONDITION);
    return Collections.unmodifiableMap(objects);
  }

  /**
   * Company-owned objects reachable through an unconditional package wildcard.
   *
   * <p>{@code com.axelor.apps.aiocr.db.*} is included here too: {@link
   * #unlinkUnconditionalAiOcrPermission} only ever handles the one role this module knows about
   * ({@code AiOcrInternal}). If some other role or group is ever given an unconditional grant on
   * this wildcard directly (bypassing data-init), it would read every company's OCR uploads AND the
   * new {@code AiOcrUploadAudit} trail — reported here like every other wildcard leak, not silently
   * fixed, for the same reason: this migration does not know what that holder actually needs.
   */
  private static final List<String> WILDCARDS =
      Collections.unmodifiableList(
          Arrays.asList(
              "com.axelor.apps.account.db.*",
              "com.axelor.apps.bankpayment.db.*",
              "com.axelor.apps.aiocr.db.*"));

  static final String ACCOUNT_WILDCARD = "com.axelor.apps.account.db.*";
  static final String AIOCR_WILDCARD = "com.axelor.apps.aiocr.db.*";
  static final String AIOCR_UNCONDITIONAL = "perm.aiocr.all";
  static final String AIOCR_INTERNAL_ROLE = "AiOcrInternal";

  /** Retired: the old wildcard-object grant this role used to hold — see the class javadoc. */
  static final String AIOCR_SCOPED = "perm.aiocr.all.internal";

  /**
   * Retired: AiOcrMonitoredFolder (the entity this permission named) and the whole Monitored
   * Folders feature have been removed — see the class javadoc. Kept only so this migration can
   * detach it from any role that still holds it from an earlier deploy.
   */
  static final String AIOCR_MONITORED_FOLDER_SCOPED = "perm.aiocr.monitored-folder.internal";

  static final String AIOCR_UPLOAD_OBJECT = "com.axelor.apps.aiocr.db.AiOcrUpload";
  static final String AIOCR_UPLOAD_SCOPED = "perm.aiocr.upload.internal";

  private final PermissionRepository permissionRepo;
  private final RoleRepository roleRepo;

  @Inject
  public CompanyScopePermissionMigration(
      PermissionRepository permissionRepo, RoleRepository roleRepo) {
    this.permissionRepo = permissionRepo;
    this.roleRepo = roleRepo;
  }

  /** What one run did, so the boot log says whether anything was still outstanding. */
  public static final class Result {
    private final List<String> scoped = new ArrayList<>();
    private final List<String> unlinked = new ArrayList<>();
    private final List<String> needsAttention = new ArrayList<>();

    public List<String> getScoped() {
      return scoped;
    }

    public List<String> getUnlinked() {
      return unlinked;
    }

    /** Grants this migration will not touch, which still allow cross-company Invoice reads. */
    public List<String> getNeedsAttention() {
      return needsAttention;
    }

    public boolean isClean() {
      return scoped.isEmpty() && unlinked.isEmpty() && needsAttention.isEmpty();
    }
  }

  @Transactional(rollbackOn = Exception.class)
  public Result migrate() {
    Result result = new Result();
    for (Map.Entry<String, String> scoped : SCOPED_OBJECTS.entrySet()) {
      scopeReadPermissions(scoped.getKey(), scoped.getValue(), result);
    }
    unlinkUnconditionalAiOcrPermission(result);
    reportWildcardGrants(result);
    reportUnscopableObjects(result);
    return result;
  }

  /**
   * Re-points every permission granting read on one company-owned object at the active company.
   *
   * <p>Matches on the exact class name only. That is not laziness: see the class javadoc on why a
   * wildcard cannot carry this condition.
   *
   * @param object the entity class name
   * @param condition the JPQL that reaches this entity's company
   */
  protected void scopeReadPermissions(String object, String condition, Result result) {
    for (Permission permission : readPermissionsFor(object)) {
      if (!Boolean.TRUE.equals(permission.getCanRead())) {
        // A write-only grant cannot be used to read another company's invoice, and conditioning it
        // would change semantics this migration has no business changing.
        continue;
      }
      String current = trimmed(permission.getCondition());
      if (condition.equals(current)
          && COMPANY_PARAMS.equals(trimmed(permission.getConditionParams()))) {
        continue; // already converged
      }
      if (!current.isEmpty() && !REPLACEABLE_CONDITIONS.contains(current)) {
        result.needsAttention.add(
            permission.getName()
                + " ("
                + simpleName(object)
                + ") carries a hand-written condition \""
                + current
                + "\"; left untouched so a local policy is not silently overwritten. Confirm it"
                + " restricts to the active company.");
        continue;
      }
      permission.setCondition(condition);
      permission.setConditionParams(COMPANY_PARAMS);
      savePermission(permission);
      result.scoped.add(
          simpleName(object)
              + " permission "
              + permission.getName()
              + (current.isEmpty() ? " (was unconditional)" : " (was \"" + current + "\")"));
    }
  }

  /**
   * Every permission granting access to one object by exact class name.
   *
   * <p>A seam, so the decision table above can be tested without a database — which matters for
   * code that rewrites the permission model on every boot.
   */
  protected List<Permission> readPermissionsFor(String object) {
    return permissionRepo.all().filter("self.object = :object").bind("object", object).fetch();
  }

  private static String simpleName(String object) {
    int dot = object.lastIndexOf('.');
    return dot < 0 ? object : object.substring(dot + 1);
  }

  protected void savePermission(Permission permission) {
    permissionRepo.save(permission);
  }

  /**
   * Swaps the internal role's unconditional / wildcard AI-OCR grants for the one named, conditioned
   * permission it actually needs.
   *
   * <p><b>Order matters and is the whole point.</b> Unlinking first would leave the role with no
   * AI-OCR permission at all, and {@code AuthSecurity.isPermitted} denies on an empty permission
   * set — internal users would lose the OCR queue outright rather than merely being scoped to their
   * company. So the replacement is created and attached first, and only then are the wide/retired
   * ones dropped.
   *
   * <p>Creating a permission here is not a widening: the replacement grants the same access types
   * on the one exact entity the role already had access to (via the wildcard), plus a company
   * condition. A fresh install already gets this row from data-init, so on that path every step
   * below finds its work already done.
   *
   * <p>Three rows are dropped, not one: {@code perm.aiocr.all} (the Admin role legitimately keeps
   * it — admins bypass conditions by design), {@code perm.aiocr.all.internal} (the old
   * wildcard-object grant this role used to hold), and {@code perm.aiocr.monitored-folder.internal}
   * (a second named grant that briefly existed alongside the upload one, for an entity — {@code
   * AiOcrMonitoredFolder} — that has since been removed entirely). None of these rows are deleted,
   * only detached from this role.
   */
  protected void unlinkUnconditionalAiOcrPermission(Result result) {
    Role role =
        roleRepo.all().filter("self.name = :name").bind("name", AIOCR_INTERNAL_ROLE).fetchOne();
    if (role == null) {
      return; // module never installed on this database
    }

    Permission uploadScoped =
        ensureScopedPermission(AIOCR_UPLOAD_SCOPED, AIOCR_UPLOAD_OBJECT, result);

    Set<Permission> kept = new LinkedHashSet<>();
    boolean removedUnconditional = false;
    boolean removedWildcardScoped = false;
    boolean removedMonitoredFolderScoped = false;
    if (role.getPermissions() != null) {
      for (Permission permission : role.getPermissions()) {
        if (permission == null) {
          continue;
        }
        if (AIOCR_UNCONDITIONAL.equals(permission.getName())) {
          removedUnconditional = true;
          continue;
        }
        if (AIOCR_SCOPED.equals(permission.getName())) {
          // Superseded: it wildcarded the whole package, which now also matches
          // AiOcrUploadAudit — an entity with no company field to condition on.
          removedWildcardScoped = true;
          continue;
        }
        if (AIOCR_MONITORED_FOLDER_SCOPED.equals(permission.getName())) {
          // Retired: AiOcrMonitoredFolder and the whole Monitored Folders feature are gone.
          removedMonitoredFolderScoped = true;
          continue;
        }
        kept.add(permission);
      }
    }
    boolean attachedUpload = kept.add(uploadScoped);
    if (!removedUnconditional
        && !removedWildcardScoped
        && !removedMonitoredFolderScoped
        && !attachedUpload) {
      return; // already converged
    }
    role.setPermissions(kept);
    roleRepo.save(role);
    if (attachedUpload) {
      result.scoped.add(AIOCR_UPLOAD_SCOPED + " attached to role " + AIOCR_INTERNAL_ROLE);
    }
    if (removedUnconditional) {
      result.unlinked.add(AIOCR_UNCONDITIONAL + " from role " + AIOCR_INTERNAL_ROLE);
    }
    if (removedWildcardScoped) {
      result.unlinked.add(AIOCR_SCOPED + " from role " + AIOCR_INTERNAL_ROLE);
    }
    if (removedMonitoredFolderScoped) {
      result.unlinked.add(AIOCR_MONITORED_FOLDER_SCOPED + " from role " + AIOCR_INTERNAL_ROLE);
    }
  }

  /**
   * The conditioned, exact-entity AI-OCR permission for {@code name}/{@code object}, created if
   * this database predates it.
   *
   * <p>Exact-entity, not a package wildcard: {@code AiOcrUpload} has a {@code company} field, which
   * is what makes conditioning safe for it — but {@code AiOcrUploadAudit}, also in this package,
   * does not (it stores a scalar {@code companyId} snapshot, not a relation), so the package
   * wildcard can no longer carry this condition safely. Naming the entity explicitly is what keeps
   * that other one out of this grant entirely.
   */
  protected Permission ensureScopedPermission(String name, String object, Result result) {
    Permission scoped =
        permissionRepo.all().filter("self.name = :name").bind("name", name).fetchOne();
    if (scoped == null) {
      scoped = new Permission();
      scoped.setName(name);
      result.scoped.add(name + " created");
    }
    scoped.setObject(object);
    scoped.setCanRead(Boolean.TRUE);
    scoped.setCanWrite(Boolean.TRUE);
    scoped.setCanCreate(Boolean.TRUE);
    scoped.setCanRemove(Boolean.TRUE);
    scoped.setCanExport(Boolean.TRUE);
    scoped.setCondition(COMPANY_CONDITION);
    scoped.setConditionParams(COMPANY_PARAMS);
    return permissionRepo.save(scoped);
  }

  /**
   * Names every wildcard grant that still lets a non-admin read any company's accounting records.
   *
   * <p>Reported rather than fixed, and reported with the holder so the message is actionable: the
   * remedy is a policy decision about that role or group, not a mechanical edit.
   *
   * <p>Why it cannot be fixed here, precisely. {@code AuthResolver.filterPermissions} unions the
   * exact-object permissions with the package wildcard ones, and {@code AuthSecurity.isPermitted}
   * returns true on the first resolved permission that has no condition. So an unconditional
   * wildcard defeats a conditioned exact-object grant for <em>fetching a named id</em> — the
   * OR-ed-wildcard problem. Conditioning the wildcard row is not available as a fix: it also
   * matches InvoiceLine, Tax, PaymentMode and a hundred other entities, most with no {@code
   * company} field, and a condition naming a field the entity does not have fails the query
   * outright. Removing it would revoke access this module knows nothing about. The only correct
   * remedies are policy ones — drop the wildcard from that holder and grant the entities it
   * actually needs — so the decision is left where it belongs, named and repeated on every boot.
   *
   * <p>Note what this does <b>not</b> catch, because it cannot: {@code AuthSecurity} exempts
   * administrators from every condition before it looks at any permission, so for an admin user no
   * permission row scopes anything. That is Axelor's design, not a gap here — see {@code
   * CompanyScopedMenus.xml}, which scopes the menus themselves and therefore applies to everyone.
   */
  protected void reportWildcardGrants(Result result) {
    for (String wildcard : WILDCARDS) {
      for (Permission permission :
          permissionRepo
              .all()
              .filter("self.object = :object AND self.canRead = TRUE")
              .bind("object", wildcard)
              .fetch()) {
        if (permission.getCondition() != null && !permission.getCondition().trim().isEmpty()) {
          continue; // already restricted somehow; not this migration's call to judge how
        }
        List<String> holders = nonAdminHolders(permission);
        if (holders.isEmpty()) {
          continue; // only admins hold it, and admins bypass conditions by design
        }
        result.needsAttention.add(
            permission.getName()
                + " grants unconditional read on "
                + wildcard
                + " to "
                + String.join(", ", holders)
                + ". Because permissions are OR-ed, this defeats the company condition on"
                + " Invoice/Move/MoveLine/BankReconciliation for those users. It cannot be"
                + " conditioned automatically (the wildcard also matches entities with no company"
                + " field) — remove it from that holder and grant the entities it actually needs.");
      }
    }
  }

  /**
   * Says, once per boot, which company-owned-looking entities have no company to scope by.
   *
   * <p>{@code BankStatement} is the case that prompted this. It is listed under Accounting next to
   * Moves and Invoices, so it looks like it should be company-scoped, and an audit that simply left
   * it out would read as "checked and fine". It has no {@code company} field: a statement is
   * attached to bank details through its lines, and deciding that a statement "belongs" to the
   * company owning those bank details is a modelling decision for this project to make
   * deliberately, not something a permission migration should infer.
   */
  protected void reportUnscopableObjects(Result result) {
    result.needsAttention.add(
        "BankStatement has no company field, so no company condition can be applied to it and its"
            + " menu is not company-filtered. Its lines reference bankDetails, which is"
            + " company-owned; if statements must be separated per company, scope them through"
            + " bankDetails deliberately (entity change or a dedicated domain), not by inference.");
  }

  /**
   * Roles and groups holding this permission, excluding the ones admins reach it through.
   *
   * <p>"Admin" is excluded by name because {@code AuthUtils.isAdmin} short-circuits the whole
   * security layer for those users: a condition would be dead weight there, so reporting it would
   * be noise on every boot.
   */
  protected List<String> nonAdminHolders(Permission permission) {
    List<String> holders = new ArrayList<>();
    for (Role role :
        roleRepo
            .all()
            .filter(":permission MEMBER OF self.permissions")
            .bind("permission", permission)
            .fetch()) {
      if (!"Admin".equals(role.getName())) {
        holders.add("role " + role.getName());
      }
    }
    for (Group group :
        Beans.get(GroupRepository.class)
            .all()
            .filter(":permission MEMBER OF self.permissions")
            .bind("permission", permission)
            .fetch()) {
      if (!"admins".equals(group.getCode())) {
        holders.add("group " + group.getCode());
      }
    }
    return holders;
  }

  /**
   * Runs the migration and reports it, without ever failing the boot.
   *
   * <p>A permission model that cannot be adjusted is a problem to fix, not a reason to refuse to
   * start: leaving the server down would take the whole ERP offline over an RBAC detail.
   */
  public void migrateOnStartup() {
    try {
      Result result = migrate();
      for (String change : result.getScoped()) {
        LOG.info("Company isolation: {} — now restricted to the active company.", change);
      }
      for (String change : result.getUnlinked()) {
        LOG.info("Company isolation: unlinked {}.", change);
      }
      for (String warning : result.getNeedsAttention()) {
        LOG.warn("Company isolation NEEDS ATTENTION: {}", warning);
      }
      if (result.isClean()) {
        LOG.info("Company isolation: permission model already correct; nothing to change.");
      }
      // The L2 cache holds Permission rows (the entity is cacheable), and AuthSecurity reads them
      // per request. Evict so the first request after a change sees the new condition rather than
      // the pre-migration one.
      if (!result.getScoped().isEmpty() || !result.getUnlinked().isEmpty()) {
        evictPermissionCache();
      }
    } catch (Exception e) {
      LOG.error(
          "Company isolation migration failed; Invoice permissions may still be company-set scoped."
              + " The AI tools and the OCR action paths enforce the active company independently.",
          e);
    }
  }

  private static String trimmed(String value) {
    return value == null ? "" : value.trim();
  }

  protected void evictPermissionCache() {
    try {
      JPA.em().getEntityManagerFactory().getCache().evict(Permission.class);
      JPA.em().getEntityManagerFactory().getCache().evict(Role.class);
    } catch (Exception e) {
      LOG.warn("Could not evict the permission cache; a restart will pick the change up.", e);
    }
  }
}
