package com.axelor.apps.bglocalization.service.batch;

import com.axelor.apps.account.db.AccountingBatch;
import com.axelor.apps.account.db.repo.AccountingBatchRepository;
import com.axelor.apps.account.db.repo.FixedAssetDerogatoryLineRepository;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Prepares one company's fixed-asset realization run: finds or creates its {@link AccountingBatch}
 * config row, and decides whether this cycle should be skipped.
 *
 * <p>Deliberately does NOT call {@code AccountingBatchService.run(...)} itself — that dispatch
 * happens afterwards, outside this method's transaction, so {@link
 * com.axelor.apps.base.service.administration.AbstractBatch}'s own per-step transactions
 * (checkpoint, per-line realize, unarchive) keep committing independently exactly as they already
 * do for every other caller (the UI "Run" button, {@code BatchJob}). Wrapping the dispatch call in
 * here as well would make the whole thing one ambient transaction and defer the archived-flag
 * commit until the very end, changing that shared framework's crash-safety contract.
 *
 * <p>Takes a company id, not a {@link Company} reference: the caller ({@code
 * BgFixedAssetRealizationJob}) calls {@code JPA.clear()} between companies, so a {@link Company}
 * held across that call would be detached by the time the next company's transaction opens. {@link
 * #findCompany} re-fetches it fresh, inside this method's own transaction.
 */
public class BgFixedAssetRealizationPreparationService {

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  protected final AccountingBatchRepository accountingBatchRepo;
  protected final FixedAssetLineRepository fixedAssetLineRepo;
  protected final FixedAssetDerogatoryLineRepository fixedAssetDerogatoryLineRepo;
  protected final CompanyRepository companyRepo;

  @Inject
  public BgFixedAssetRealizationPreparationService(
      AccountingBatchRepository accountingBatchRepo,
      FixedAssetLineRepository fixedAssetLineRepo,
      FixedAssetDerogatoryLineRepository fixedAssetDerogatoryLineRepo,
      CompanyRepository companyRepo) {
    this.accountingBatchRepo = accountingBatchRepo;
    this.fixedAssetLineRepo = fixedAssetLineRepo;
    this.fixedAssetDerogatoryLineRepo = fixedAssetDerogatoryLineRepo;
    this.companyRepo = companyRepo;
  }

  /**
   * Returns the {@link AccountingBatch} to run for this company this cycle, or {@code null} if the
   * company should be skipped (no longer exists, archived config, or blocking simulated lines).
   */
  @Transactional(rollbackOn = Exception.class)
  public AccountingBatch prepare(Long companyId) {
    Company company = findCompany(companyId);
    if (company == null) {
      log.warn(
          "Fixed asset realization skipped: company id {} no longer exists (removed between the "
              + "job's company scan and this company's turn).",
          companyId);
      return null;
    }

    AccountingBatch accountingBatch = findOrCreate(company);

    if (Boolean.TRUE.equals(accountingBatch.getArchived())) {
      log.warn(
          "Fixed asset realization skipped for company {}: AccountingBatch {} is still marked "
              + "Archived (likely a prior crashed run) — clear the Archived flag on that record "
              + "manually to resume.",
          company.getCode(),
          accountingBatch.getCode());
      return null;
    }

    if (hasBlockingSimulatedLines(company)) {
      log.warn(
          "Fixed asset realization skipped for company {}: one or more PLANNED fixed asset "
              + "lines are flagged as simulated (pending manual follow-up on a prior 'Simulate' "
              + "action) — running the batch now would post their moves. Resolve manually; this "
              + "company will be picked up again on the next run.",
          company.getCode());
      return null;
    }

    return accountingBatch;
  }

  // A separate, overridable seam so a test can supply a fixed Company without needing a real
  // CompanyRepository/JPA lookup.
  protected Company findCompany(Long companyId) {
    return companyRepo.find(companyId);
  }

  // Split into findExisting()/create() (rather than one inline method) so a test can override
  // just these two narrow seams and prove the reuse-not-duplicate branching without needing to
  // fake the underlying JPQL Query chain.
  protected AccountingBatch findOrCreate(Company company) {
    AccountingBatch existing = findExisting(company);
    if (existing != null) {
      return existing;
    }
    return create(company);
  }

  protected AccountingBatch findExisting(Company company) {
    return accountingBatchRepo
        .all()
        .filter("self.actionSelect = :actionSelect AND self.company.id = :companyId")
        .bind("actionSelect", AccountingBatchRepository.ACTION_REALIZE_FIXED_ASSET_LINES)
        .bind("companyId", company.getId())
        .fetchOne();
  }

  protected AccountingBatch create(Company company) {
    AccountingBatch accountingBatch = new AccountingBatch();
    accountingBatch.setCode("FA_REALIZE_" + company.getCode());
    accountingBatch.setActionSelect(AccountingBatchRepository.ACTION_REALIZE_FIXED_ASSET_LINES);
    accountingBatch.setCompany(company);
    accountingBatch.setUpdateAllRealizedFixedAssetLines(true);
    accountingBatch.setDescription(
        "Auto-created for the monthly fixed asset depreciation realization job "
            + "(BgFixedAssetRealizationJob) — reused on every run, never recreated.");
    accountingBatchRepo.save(accountingBatch);
    log.info(
        "Created AccountingBatch {} for company {} (fixed asset realization, monthly job).",
        accountingBatch.getCode(),
        company.getCode());
    return accountingBatch;
  }

  protected boolean hasBlockingSimulatedLines(Company company) {
    long simulatedLineCount =
        fixedAssetLineRepo
            .all()
            .filter(
                "self.statusSelect = :statusSelect AND self.isSimulated = true AND "
                    + "(self.fixedAsset.company.id = :companyId "
                    + "OR self.fiscalFixedAsset.company.id = :companyId "
                    + "OR self.ifrsFixedAsset.company.id = :companyId)")
            .bind("statusSelect", FixedAssetLineRepository.STATUS_PLANNED)
            .bind("companyId", company.getId())
            .count();
    if (simulatedLineCount > 0) {
      return true;
    }

    long simulatedDerogatoryLineCount =
        fixedAssetDerogatoryLineRepo
            .all()
            .filter(
                "self.statusSelect = :statusSelect AND self.isSimulated = true AND "
                    + "self.fixedAsset.company.id = :companyId")
            .bind("statusSelect", FixedAssetLineRepository.STATUS_PLANNED)
            .bind("companyId", company.getId())
            .count();
    return simulatedDerogatoryLineCount > 0;
  }
}
