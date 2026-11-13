package com.axelor.apps.bglocalization.service.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.account.db.AccountingBatch;
import com.axelor.apps.account.db.repo.AccountingBatchRepository;
import com.axelor.apps.base.db.Company;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BgFixedAssetRealizationPreparationService}. No mocking framework is used —
 * this module's existing tests fake behaviour by subclassing and overriding protected seams (see
 * {@code BgSocialExpenseWiringTest}), so these tests follow the same style rather than introducing
 * Mockito.
 *
 * <p>Scope: the decision branching in {@code prepare()} (company no longer exists, archived skip,
 * blocking-simulated-line skip) and the find-or-create idempotency of {@code findOrCreate()}. Does
 * not re-verify the underlying batch's own draft-only / rerun-idempotent behaviour — that is
 * unchanged stock/BG code, already proven by direct code trace in the design plan.
 */
class BgFixedAssetRealizationPreparationServiceTest {

  /**
   * Fakes findCompany()/findOrCreate()/hasBlockingSimulatedLines() so prepare()'s branching is
   * isolated.
   */
  private static class DecisionOnlyService extends BgFixedAssetRealizationPreparationService {
    Company stubCompany;
    AccountingBatch stubBatch;
    boolean stubBlocking;

    DecisionOnlyService() {
      super(null, null, null, null);
    }

    @Override
    protected Company findCompany(Long companyId) {
      return stubCompany;
    }

    @Override
    protected AccountingBatch findOrCreate(Company company) {
      return stubBatch;
    }

    @Override
    protected boolean hasBlockingSimulatedLines(Company company) {
      return stubBlocking;
    }
  }

  /** Fakes findExisting()/create() so the reuse-vs-duplicate branching is isolated. */
  private static class FindOrCreateTrackingService
      extends BgFixedAssetRealizationPreparationService {
    AccountingBatch existingToReturn;
    final AccountingBatch created = new AccountingBatch();
    int findExistingCalls;
    int createCalls;

    FindOrCreateTrackingService() {
      super(null, null, null, null);
    }

    @Override
    protected AccountingBatch findExisting(Company company) {
      findExistingCalls++;
      return existingToReturn;
    }

    @Override
    protected AccountingBatch create(Company company) {
      createCalls++;
      return created;
    }
  }

  private static Company company(String code) {
    Company company = new Company();
    company.setCode(code);
    return company;
  }

  @Test
  void companyNoLongerExistingIsSkipped() {
    DecisionOnlyService service = new DecisionOnlyService();
    service.stubCompany = null; // findCompany() found nothing

    assertNull(
        service.prepare(999L), "a company id that no longer resolves must be skipped, not NPE");
  }

  @Test
  void archivedAccountingBatchIsSkipped() {
    DecisionOnlyService service = new DecisionOnlyService();
    service.stubCompany = company("C1");
    AccountingBatch batch = new AccountingBatch();
    batch.setArchived(true);
    service.stubBatch = batch;

    assertNull(
        service.prepare(1L), "an Archived AccountingBatch must be skipped, never auto-reset");
  }

  @Test
  void blockingSimulatedLineIsSkipped() {
    DecisionOnlyService service = new DecisionOnlyService();
    service.stubCompany = company("C1");
    AccountingBatch batch = new AccountingBatch();
    batch.setArchived(false);
    service.stubBatch = batch;
    service.stubBlocking = true;

    assertNull(
        service.prepare(1L),
        "a PLANNED line flagged isSimulated=true must block this company's run so realize() "
            + "cannot post its move");
  }

  @Test
  void normalCompanyReturnsTheAccountingBatch() {
    DecisionOnlyService service = new DecisionOnlyService();
    service.stubCompany = company("C1");
    AccountingBatch batch = new AccountingBatch();
    batch.setArchived(false);
    service.stubBatch = batch;
    service.stubBlocking = false;

    assertSame(batch, service.prepare(1L));
  }

  @Test
  void findOrCreateReusesTheExistingAccountingBatchInsteadOfDuplicatingIt() {
    FindOrCreateTrackingService service = new FindOrCreateTrackingService();
    Company company = company("C1");

    // First run: nothing exists yet -> create() is called exactly once.
    service.existingToReturn = null;
    AccountingBatch first = service.findOrCreate(company);
    assertSame(service.created, first);
    assertEquals(1, service.createCalls);

    // Next day's run: now found -> create() must NOT be called again.
    service.existingToReturn = service.created;
    AccountingBatch second = service.findOrCreate(company);
    assertSame(service.created, second);
    assertEquals(1, service.createCalls, "a second run must reuse the row, never duplicate it");
    assertEquals(2, service.findExistingCalls);
  }

  /**
   * Overrides only save() (JpaRepository.save() would otherwise call the real JPA/EntityManager).
   */
  private static class NoOpSaveAccountingBatchRepository extends AccountingBatchRepository {
    AccountingBatch saved;

    @Override
    public AccountingBatch save(AccountingBatch entity) {
      saved = entity;
      return entity;
    }
  }

  @Test
  void createSetsTheFieldsTheMonthlyJobNeedsAndSavesExactlyOnce() {
    NoOpSaveAccountingBatchRepository repo = new NoOpSaveAccountingBatchRepository();
    BgFixedAssetRealizationPreparationService service =
        new BgFixedAssetRealizationPreparationService(repo, null, null, null);
    Company company = company("C1");

    AccountingBatch created = service.create(company);

    assertSame(repo.saved, created, "create() must save the row it returns");
    assertEquals(
        AccountingBatchRepository.ACTION_REALIZE_FIXED_ASSET_LINES, created.getActionSelect());
    assertEquals(Boolean.TRUE, created.getUpdateAllRealizedFixedAssetLines());
    assertSame(company, created.getCompany());
  }
}
