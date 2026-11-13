package com.axelor.apps.aiocr.service.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.auth.db.Permission;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The decision table of the permission migration, without a database.
 *
 * <p>This runs on every boot, so the property that matters is convergence: a second run must find
 * nothing to do, and a condition somebody wrote by hand must survive. Both are cheap to state here
 * and expensive to discover in production.
 *
 * <p>The DB plumbing (repository lookups, the role swap) is deliberately not exercised — that needs
 * a real database and belongs in the integration suite. What is exercised is which rows the
 * migration decides to rewrite, which is where a mistake would actually cause harm.
 */
class CompanyScopePermissionMigrationTest {

  /**
   * A migration whose only DB dependency — "the Invoice permissions" — is supplied by the test.
   * Everything else is left alone, so this stays a pure decision test.
   */
  private static final class Harness extends CompanyScopePermissionMigration {
    private final List<Permission> rows;

    Harness(List<Permission> rows) {
      super(null, null);
      this.rows = rows;
    }

    @Override
    protected List<Permission> readPermissionsFor(String object) {
      return CompanyScopePermissionMigration.INVOICE_OBJECT.equals(object)
          ? rows
          : java.util.Collections.emptyList();
    }

    @Override
    protected void savePermission(com.axelor.auth.db.Permission permission) {
      // The decision, not the persistence, is what this test is about.
    }
  }

  private static Permission permission(String name, String condition, String params) {
    Permission p = new Permission();
    p.setName(name);
    p.setObject(CompanyScopePermissionMigration.INVOICE_OBJECT);
    p.setCanRead(Boolean.TRUE);
    p.setCondition(condition);
    p.setConditionParams(params);
    return p;
  }

  private static CompanyScopePermissionMigration.Result run(List<Permission> rows) {
    CompanyScopePermissionMigration.Result result = new CompanyScopePermissionMigration.Result();
    new Harness(rows)
        .scopeReadPermissions(
            CompanyScopePermissionMigration.INVOICE_OBJECT,
            CompanyScopePermissionMigration.COMPANY_CONDITION,
            result);
    return result;
  }

  @Test
  void theStockCompanySetConditionIsNarrowedToTheActiveCompany() {
    // What axelor-account ships: the whole company set, not the one being worked in.
    Permission stock =
        permission(
            "perm.account.Invoice.r", "self.company.id in (?)", "__user__.companySet.id.plus(0)");

    CompanyScopePermissionMigration.Result result = run(Arrays.asList(stock));

    assertEquals(CompanyScopePermissionMigration.COMPANY_CONDITION, stock.getCondition());
    assertEquals(CompanyScopePermissionMigration.COMPANY_PARAMS, stock.getConditionParams());
    assertEquals(1, result.getScoped().size());
    assertTrue(result.getScoped().get(0).contains("perm.account.Invoice.r"));
  }

  @Test
  void anUnconditionalInvoiceGrantIsClosed() {
    Permission wide = permission("perm.local.invoice.r", null, null);

    run(Arrays.asList(wide));

    assertEquals(CompanyScopePermissionMigration.COMPANY_CONDITION, wide.getCondition());
    assertEquals(CompanyScopePermissionMigration.COMPANY_PARAMS, wide.getConditionParams());
  }

  @Test
  void aSecondRunChangesNothing() {
    // The whole design rests on this: it executes on every single boot.
    Permission stock =
        permission(
            "perm.account.Invoice.r", "self.company.id in (?)", "__user__.companySet.id.plus(0)");
    List<Permission> rows = new ArrayList<>(Arrays.asList(stock));

    assertFalse(run(rows).getScoped().isEmpty(), "the first run must do the work");
    assertTrue(run(rows).isClean(), "the second run must find nothing to do");
  }

  @Test
  void theBrokenNumberedPlaceholderIsRepaired() {
    // Filter.build turns "?1" into "?11", a parameter that doesn't exist, which denied every read.
    // Rows written by earlier versions of this migration must converge on the plain "?" form.
    Permission legacy =
        permission("perm.account.Invoice.r", "self.company = ?1", "__user__.activeCompany");

    run(Arrays.asList(legacy));

    assertEquals("self.company = ?", legacy.getCondition());
    assertFalse(CompanyScopePermissionMigration.COMPANY_CONDITION.contains("?1"));
    assertFalse(CompanyScopePermissionMigration.MOVE_COMPANY_CONDITION.contains("?1"));
  }

  @Test
  void aHandWrittenConditionIsReportedNotOverwritten() {
    // Converging on our value would silently undo a deliberate local policy, so it is flagged
    // instead — and flagged loudly, because it might not restrict the company at all.
    Permission custom =
        permission(
            "perm.local.invoice.tradingName", "self.tradingName = ?1", "__user__.tradingName");

    CompanyScopePermissionMigration.Result result = run(Arrays.asList(custom));

    assertEquals("self.tradingName = ?1", custom.getCondition(), "left exactly as it was");
    assertTrue(result.getScoped().isEmpty());
    assertEquals(1, result.getNeedsAttention().size());
    assertTrue(result.getNeedsAttention().get(0).contains("perm.local.invoice.tradingName"));
  }

  @Test
  void aWriteOnlyGrantIsNotTouched() {
    // It cannot be used to read another company's invoice, and conditioning it would change
    // semantics this migration has no business changing.
    Permission writeOnly = permission("perm.local.invoice.w", null, null);
    writeOnly.setCanRead(Boolean.FALSE);

    CompanyScopePermissionMigration.Result result = run(Arrays.asList(writeOnly));

    assertTrue(result.isClean());
    assertEquals(null, writeOnly.getCondition());
  }
}
