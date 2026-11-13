package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountManagement;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.ProductFamily;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * БГ настройката на склада, която заварените бази получават при стартиране.
 *
 * <p>BG_COA носи същите стойности за нови инсталации, но в заварена база архивът на шаблона е
 * остарял (axelor-account не е в регистъра за синхронизация), тоест стартирането е единственият път
 * до заварените дружества.
 *
 * <p>⚠️ САМО ПРАЗНОТО СЕ ПОПЪЛВА. Попълнено поле е избор на потребителя и не се пипа.
 */
public class BgStockSetupService {

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  /** {@code AccountManagement.typeSelect} = 1 — ред за продукт или семейство. */
  static final int ACCOUNT_MANAGEMENT_PRODUCT = 1;

  /** Видът на запаса на БГ семействата, по КОД ({@link BgSaftInventoryTypes#FAMILY_TYPES}). */
  @Transactional(rollbackOn = Exception.class)
  public int ensureFamilyInventoryTypes() {
    int filled = 0;
    for (Map.Entry<String, String> e : BgSaftInventoryTypes.FAMILY_TYPES.entrySet()) {
      ProductFamily family =
          JPA.all(ProductFamily.class).filter("self.code = ?1", e.getKey()).fetchOne();
      if (family == null || !isBlank(family.getBgSaftInventoryType())) {
        continue;
      }
      family.setBgSaftInventoryType(e.getValue());
      JPA.save(family);
      filled++;
    }
    if (filled > 0) {
      log.info("Filled the SAF-T inventory type of {} BG product families.", filled);
    }
    return filled;
  }

  /**
   * Складовата сметка 30x на редовете AccountManagement на БГ семействата, по дружество. Сметката
   * се търси по код в сметкоплана на дружеството на реда; няма ли я там — редът остава празен.
   */
  @Transactional(rollbackOn = Exception.class)
  public int ensureFamilyStockAccounts() {
    int filled = 0;
    List<AccountManagement> rows =
        JPA.all(AccountManagement.class)
            .filter(
                "self.typeSelect = ?1 AND self.product IS NULL AND self.productFamily.code IN ?2"
                    + " AND self.bgStockAccount IS NULL AND self.company IS NOT NULL",
                ACCOUNT_MANAGEMENT_PRODUCT,
                BgSaftInventoryTypes.FAMILY_STOCK_ACCOUNTS.keySet())
            .fetch();
    for (AccountManagement row : rows) {
      String code =
          BgSaftInventoryTypes.FAMILY_STOCK_ACCOUNTS.get(row.getProductFamily().getCode());
      Account account =
          JPA.all(Account.class)
              .filter("self.code = ?1 AND self.company = ?2", code, row.getCompany())
              .fetchOne();
      if (account == null) {
        continue;
      }
      row.setBgStockAccount(account);
      JPA.save(row);
      filled++;
    }
    if (filled > 0) {
      log.info("Filled the stock account (30x) on {} family account-management rows.", filled);
    }
    return filled;
  }

  /** Шаблонът на склада за всяко дружество — виж {@link #ensureTemplate(Company)}. */
  public void ensureTemplates() {
    for (Company company : JPA.all(Company.class).fetch()) {
      ensureTemplate(company);
    }
  }

  /**
   * Шаблонът на склада за едно дружество ({@link BgStockTemplate}): липсващото се създава,
   * съществуващото не се пипа. Независимо дали приложението „Склад“ е активно — без StockConfig
   * Axelor отказва всяко движение (STOCK_CONFIG_1), а записите са безвредни, докато е неактивно.
   *
   * <ul>
   *   <li>Поредица с този код за дружеството вече има — остава, каквато е (следващият номер,
   *       префиксът и версията са избор на дружеството).
   *   <li>StockConfig няма — създава се празна.
   *   <li>Полето на конфигурацията вече сочи локация — нито се пренасочва, нито се създава нова.
   *   <li>Локацията на шаблона се намира по importId {@code <код>-BGSTK-<n>} и дружеството, така че
   *       повторното прилагане не прави втора.
   *   <li>Празен шаблон за печат получава този от data-init на axelor-stock ({@link
   *       BgStockPrintTemplates}); избраният не се пипа.
   * </ul>
   */
  @Transactional(rollbackOn = Exception.class)
  public void ensureTemplate(Company company) {
    if (company == null) {
      return;
    }
    SequenceService sequences = Beans.get(SequenceService.class);
    for (BgStockTemplate.SequenceSpec spec : BgStockTemplate.SEQUENCES) {
      if (sequences.getSequence(spec.code, company) == null) {
        JPA.save(BgStockTemplate.newSequence(company, spec));
        log.info("Created the stock sequence {} for company {}.", spec.code, company.getCode());
      }
    }

    StockConfig config = JPA.all(StockConfig.class).filter("self.company = ?1", company).fetchOne();
    if (config == null) {
      config = new StockConfig();
      config.setCompany(company);
      config = JPA.save(config);
      log.info("Created the stock configuration for company {}.", company.getCode());
    }

    for (BgStockTemplate.LocationSpec spec : BgStockTemplate.LOCATIONS) {
      List<String> empty = BgStockTemplate.emptyFields(config, spec);
      if (empty.isEmpty()) {
        continue;
      }
      String importId = BgStockTemplate.importIdFor(company, spec.key);
      StockLocation location =
          importId == null
              ? null
              : JPA.all(StockLocation.class)
                  .filter("self.importId = ?1 AND self.company = ?2", importId, company)
                  .fetchOne();
      if (location == null) {
        location = JPA.save(BgStockTemplate.newLocation(company, spec));
      }
      BgStockTemplate.point(config, empty, location);
    }
    // Шаблоните за печат (справката на складовото място, инвентаризацията, листът за
    // комплектоване, сертификатът) — без тях съветникът „Печат“ стои със заключен бутон.
    List<String> templates = BgStockPrintTemplates.fillEmpty(config, BgStockPrintTemplates::find);
    if (!templates.isEmpty()) {
      log.info("Filled the stock print templates {} for company {}.", templates, company.getCode());
    }
    JPA.save(config);
  }

  static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
