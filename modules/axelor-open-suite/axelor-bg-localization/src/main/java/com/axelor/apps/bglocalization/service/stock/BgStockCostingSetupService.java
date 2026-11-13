package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.repo.JournalRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.bglocalization.db.BgStockCostingAccountRule;
import com.axelor.db.JPA;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Журналът „Изписване на МЗ“ (ИЗП) за заварените дружества и за новото дружество веднага.
 *
 * <p>BG_COA го носи за новите инсталации ({@code account_journal.csv} importId {@value
 * #JOURNAL_IMPORT_ID}, поредица {@value #SEQUENCE_IMPORT_ID}, AccountConfig.bgStockIssueJournal).
 * Заварена база няма как да го получи от шаблона (архивът му е остарял — виж {@link
 * BgStockSetupService}), затова стартирането е пътят.
 *
 * <p>⚠️ importId-ът е този на шаблона: журнал и поредица без него биха накарали BG_COA, приложен
 * по-късно, да направи втори журнал STK и втора поредица.
 *
 * <p>⚠️ САМО ЛИПСВАЩОТО СЕ СЪЗДАВА. Журналът се търси по importId и дружество; попълненият
 * AccountConfig.bgStockIssueJournal не се пренасочва.
 */
public class BgStockCostingSetupService {

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  public static final String JOURNAL_IMPORT_ID = "98";
  public static final String SEQUENCE_IMPORT_ID = "150";
  public static final String JOURNAL_CODE = "STK";
  public static final String JOURNAL_NAME = "Изписване на МЗ";
  public static final String JOURNAL_TYPE_CODE = "OTH";

  /** 120 с виновно лице: старото подразбиране (до 08.10.2026) и новото. */
  static final String OLD_SHORTAGE_PARTNER_ACCOUNT = "498";

  static final String SHORTAGE_PARTNER_ACCOUNT = "442";

  /**
   * Типовете сметки, които журналът приема — празен списък значи „нито една“ ({@code
   * MoveLineControlServiceImpl}): currentAsset (30x), receivable (498), income (70x, 709), charge
   * (60x).
   */
  public static final List<String> VALID_ACCOUNT_TYPES =
      Collections.unmodifiableList(Arrays.asList("currentAsset", "receivable", "income", "charge"));

  static final BgStockTemplate.SequenceSpec SEQUENCE =
      new BgStockTemplate.SequenceSpec("move", SEQUENCE_IMPORT_ID, JOURNAL_NAME, "STK%YY");

  /**
   * Решение от 08.10.2026: липсата с виновно лице (120) е 442 „Вземания по липси и начети“, не 498
   * — както при активите. Заварените политики се сменят САМО когато редът е точно 498: всяка друга
   * сметка е избор на дружеството. 442 се търси в сметкоплана на дружеството на политиката; няма ли
   * я там — редът остава.
   *
   * @return колко реда са сменени
   */
  @Transactional(rollbackOn = Exception.class)
  public int ensureShortagePartnerAccounts() {
    List<BgStockCostingAccountRule> rules =
        JPA.all(BgStockCostingAccountRule.class)
            .filter(
                "self.movementCode = '120' AND self.withResponsiblePartner = TRUE"
                    + " AND self.account.code = ?1",
                OLD_SHORTAGE_PARTNER_ACCOUNT)
            .fetch();
    int changed = 0;
    for (BgStockCostingAccountRule rule : rules) {
      Account receivable =
          JPA.all(Account.class)
              .filter(
                  "self.code = ?1 AND self.company = ?2",
                  SHORTAGE_PARTNER_ACCOUNT,
                  rule.getPolicy().getCompany())
              .fetchOne();
      if (receivable == null) {
        continue;
      }
      rule.setAccount(receivable);
      JPA.save(rule);
      changed++;
    }
    if (changed > 0) {
      log.info(
          "Moved {} stock-shortage rule(s) with a responsible person from 498 to 442.", changed);
    }
    return changed;
  }

  /** Журналът за всяко дружество — виж {@link #ensureIssueJournal(Company)}. */
  public void ensureIssueJournals() {
    for (Company company : JPA.all(Company.class).fetch()) {
      ensureIssueJournal(company);
    }
  }

  /**
   * Журналът и поредицата му за едно дружество, ако липсват, и AccountConfig.bgStockIssueJournal,
   * ако е празно. Без код на дружеството или без тип журнал OTH не се прави нищо — първото би
   * оставило журнал без importId, второто идва с BG_COA.
   */
  @Transactional(rollbackOn = Exception.class)
  public void ensureIssueJournal(Company company) {
    String importId = BgStockTemplate.importIdFor(company, JOURNAL_IMPORT_ID);
    if (importId == null) {
      return;
    }
    Journal journal =
        JPA.all(Journal.class)
            .filter("self.importId = ?1 AND self.company = ?2", importId, company)
            .fetchOne();
    if (journal == null) {
      JournalType type =
          JPA.all(JournalType.class).filter("self.code = ?1", JOURNAL_TYPE_CODE).fetchOne();
      if (type == null) {
        return;
      }
      journal = JPA.save(newJournal(company, type, sequence(company)));
      log.info(
          "Created the stock issue journal {} for company {}.", JOURNAL_CODE, company.getCode());
    }

    AccountConfig config =
        JPA.all(AccountConfig.class).filter("self.company = ?1", company).fetchOne();
    if (config != null && config.getBgStockIssueJournal() == null) {
      config.setBgStockIssueJournal(journal);
      JPA.save(config);
    }
  }

  protected Sequence sequence(Company company) {
    String importId = BgStockTemplate.importIdFor(company, SEQUENCE_IMPORT_ID);
    Sequence sequence =
        JPA.all(Sequence.class)
            .filter("self.importId = ?1 AND self.company = ?2", importId, company)
            .fetchOne();
    return sequence != null ? sequence : JPA.save(BgStockTemplate.newSequence(company, SEQUENCE));
  }

  protected Journal newJournal(Company company, JournalType type, Sequence sequence) {
    Journal journal = new Journal();
    journal.setCompany(company);
    journal.setImportId(BgStockTemplate.importIdFor(company, JOURNAL_IMPORT_ID));
    journal.setCode(JOURNAL_CODE);
    journal.setName(JOURNAL_NAME);
    journal.setJournalType(type);
    journal.setStatusSelect(JournalRepository.STATUS_ACTIVE);
    journal.setSequence(sequence);
    // Без разрешени функционални произходи: статията за изписване няма такъв (0), а проверката
    // MoveValidateServiceImpl#checkFunctionalOriginSelect пуска 0 без да чете списъка.
    journal.setValidAccountTypeSet(
        new HashSet<>(
            JPA.all(AccountType.class)
                .filter("self.technicalTypeSelect IN ?1", VALID_ACCOUNT_TYPES)
                .fetch()));
    return journal;
  }
}
