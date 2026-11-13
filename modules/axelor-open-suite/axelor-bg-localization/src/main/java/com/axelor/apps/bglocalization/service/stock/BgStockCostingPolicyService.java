package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.ProductCompanyService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.bglocalization.db.BgStockCostingAccountRule;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import com.axelor.apps.bglocalization.service.accounting.BgAutoPostingUserService;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockLocationLine;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Политиката за изписване (ИЗП): сметките по подразбиране, активирането и смяната на базата. */
public class BgStockCostingPolicyService {

  protected final BgStockCostingPolicyRepository policyRepo;

  @Inject
  public BgStockCostingPolicyService(BgStockCostingPolicyRepository policyRepo) {
    this.policyRepo = policyRepo;
  }

  /**
   * Добавя редовете от {@link BgStockCostingDefaults}, които политиката още няма. Сметката се търси
   * по код в сметкоплана на дружеството; няма ли я там — редът не се добавя, а кодът се връща в
   * списъка. Съществуващ ред (същият код, вид и виновно лице) не се пипа: той е избор на
   * дружеството.
   *
   * @return кодовете на сметките, които липсват в сметкоплана на дружеството
   */
  public List<String> fillDefaultRules(BgStockCostingPolicy policy) {
    List<String> missing = new ArrayList<>();
    if (policy == null || policy.getCompany() == null) {
      return missing;
    }
    for (BgStockCostingDefaults.Row row : BgStockCostingDefaults.ROWS) {
      if (hasRule(policy, row)) {
        continue;
      }
      Account account = findAccount(policy.getCompany(), row.accountCode);
      if (account == null) {
        if (!missing.contains(row.accountCode)) {
          missing.add(row.accountCode);
        }
        continue;
      }
      BgStockCostingAccountRule rule = new BgStockCostingAccountRule();
      rule.setMovementCode(row.movementCode);
      rule.setInventoryType(row.inventoryType);
      rule.setWithResponsiblePartner(row.withResponsiblePartner);
      rule.setAccount(account);
      policy.addAccountRuleListItem(rule);
    }
    return missing;
  }

  static boolean hasRule(BgStockCostingPolicy policy, BgStockCostingDefaults.Row row) {
    if (policy.getAccountRuleList() == null) {
      return false;
    }
    for (BgStockCostingAccountRule rule : policy.getAccountRuleList()) {
      if (row.movementCode.equals(rule.getMovementCode())
          && Objects.equals(row.inventoryType, blankToNull(rule.getInventoryType()))
          && row.withResponsiblePartner == Boolean.TRUE.equals(rule.getWithResponsiblePartner())) {
        return true;
      }
    }
    return false;
  }

  protected Account findAccount(Company company, String code) {
    return JPA.all(Account.class)
        .filter("self.code = ?1 AND self.company = ?2", code, company)
        .fetchOne();
  }

  /**
   * Активира политиката: от тук нататък методът, обхватът, осчетоводяването и изключенията по
   * семейства не се сменят до края на годината (СС 2 т. 8.5).
   */
  @Transactional(rollbackOn = Exception.class)
  public void activate(BgStockCostingPolicy policy) throws AxelorException {
    String refusal =
        BgStockCostingRules.refusal(
            policy,
            policy.getCompany() == null ? null : policy.getCompany().getBgAccountingFramework());
    if (refusal == null) {
      refusal = trackingRefusal(policy);
    }
    if (refusal == null) {
      refusal = undistributedRefusal(policy);
    }
    if (refusal != null) {
      throw new AxelorException(policy, TraceBackRepository.CATEGORY_INCONSISTENCY, refusal);
    }
    policy.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
    policy.setLockedSignature(BgStockCostingRules.signature(policy));
    policyRepo.save(policy);
    // Първото активиране на дружеството зарежда заварените наличности като начални слоеве по
    // средната цена на локацията (решение 6). Има ли вече слоеве — нищо.
    // Потребителят за автоматично осчетоводяване — ако настройката на дружеството е празна.
    Beans.get(BgAutoPostingUserService.class).ensureCompanyPostingUser(policy.getCompany());
    openingService()
        .createOpeningLayers(
            policy, Beans.get(AppBaseService.class).getTodayDate(policy.getCompany()));
  }

  protected BgStockCostingOpeningService openingService() {
    return Beans.get(BgStockCostingOpeningService.class);
  }

  /**
   * Имената на незавършилите години (краят им е днес или по-късно), чиято политика ползва LIFO — те
   * не допускат дружеството да мине на МСФО.
   */
  public List<String> lifoYearNames(Company company, LocalDate today) {
    List<String> names = new ArrayList<>();
    if (company == null || company.getId() == null) {
      return names;
    }
    List<BgStockCostingPolicy> policies =
        policyRepo
            .all()
            .filter("self.company.id = ?1 AND self.year.toDate >= ?2", company.getId(), today)
            .fetch();
    for (BgStockCostingPolicy policy : policies) {
      if (BgStockCostingRules.usesLifo(policy)) {
        names.add(policy.getYear().getName());
      }
    }
    return names;
  }

  /** Колко продукта се изброяват в отказ — останалите се броят. */
  static final int LISTED = 10;

  /**
   * Продуктите, които политиката изписва по конкретно определена стойност: складируеми, със вид на
   * запаса, чийто метод (изключението на семейството или методът по подразбиране) е SPECIFIC.
   * Празно, когато политиката не ползва конкретна стойност.
   */
  public List<Product> specificProducts(BgStockCostingPolicy policy) {
    List<Product> out = new ArrayList<>();
    if (!usesSpecific(policy)) {
      return out;
    }
    List<Product> storable =
        JPA.all(Product.class)
            .filter("self.productTypeSelect = ?1", ProductRepository.PRODUCT_TYPE_STORABLE)
            .order("code")
            .fetch();
    for (Product product : storable) {
      if (BgSaftInventoryTypes.typeOf(product) != null
          && BgStockCostingRules.SPECIFIC.equals(
              BgStockCostingRules.methodFor(policy, product.getProductFamily()))) {
        out.add(product);
      }
    }
    return out;
  }

  public static boolean usesSpecific(BgStockCostingPolicy policy) {
    if (policy == null) {
      return false;
    }
    if (BgStockCostingRules.SPECIFIC.equals(policy.getCostingMethodSelect())) {
      return true;
    }
    if (policy.getFamilyOverrideList() != null) {
      for (com.axelor.apps.bglocalization.db.BgStockCostingFamilyOverride o :
          policy.getFamilyOverrideList()) {
        if (BgStockCostingRules.SPECIFIC.equals(o.getCostingMethodSelect())) {
          return true;
        }
      }
    }
    return false;
  }

  /**
   * Правило 3 (09.10.2026): конкретна стойност само за продукти с проследяване (партиди или серийни
   * номера в настройките на продукта, вкл. по дружество). Отказът изброява продуктите.
   */
  public String trackingRefusal(BgStockCostingPolicy policy) {
    List<String> untracked = new ArrayList<>();
    for (Product product : specificProducts(policy)) {
      Object cfg;
      try {
        cfg =
            Beans.get(ProductCompanyService.class)
                .get(product, "trackingNumberConfiguration", policy.getCompany());
      } catch (AxelorException e) {
        cfg = product.getTrackingNumberConfiguration();
      }
      if (cfg == null) {
        untracked.add(product.getCode());
      }
    }
    if (untracked.isEmpty()) {
      return null;
    }
    return String.format(
        "Конкретно определена стойност (СС 2 т. 9.1) различава партидите, а тези продукти са без"
            + " проследяване (партиди или серийни номера в настройките на продукта): %s. Включете"
            + " проследяването им или изберете друг метод за семейството им.",
        listed(untracked));
  }

  /**
   * Правило 4 (09.10.2026): наличност, неразпределена по партиди, не може да има слоеве по партиди
   * — активирането се отказва със списък продукт × склад × количество.
   */
  public String undistributedRefusal(BgStockCostingPolicy policy) {
    List<Product> products = specificProducts(policy);
    if (products.isEmpty()) {
      return null;
    }
    List<String> rows = new ArrayList<>();
    for (StockLocationLine main : mainLines(policy.getCompany(), products)) {
      BigDecimal distributed = BigDecimal.ZERO;
      for (StockLocationLine detail :
          JPA.all(StockLocationLine.class)
              .filter(
                  "self.detailsStockLocation = ?1 AND self.product = ?2"
                      + " AND self.trackingNumber IS NOT NULL",
                  main.getStockLocation(),
                  main.getProduct())
              .fetch()) {
        distributed = distributed.add(detail.getCurrentQty());
      }
      BigDecimal free = main.getCurrentQty().subtract(distributed);
      if (free.signum() > 0) {
        StockLocation warehouse = BgSaftWarehouses.of(main.getStockLocation());
        rows.add(
            String.format(
                "%s × %s × %s",
                main.getProduct().getCode(),
                (warehouse == null ? main.getStockLocation() : warehouse).getName(),
                free.stripTrailingZeros().toPlainString()));
      }
    }
    if (rows.isEmpty()) {
      return null;
    }
    return String.format(
        "Наличността не е разпределена по партиди, а конкретно определена стойност иска слой на"
            + " всяка партида. Разпределете я (продукт × склад × количество без партида): %s.",
        listed(rows));
  }

  static List<StockLocationLine> mainLines(
      com.axelor.apps.base.db.Company company, List<Product> products) {
    return JPA.all(StockLocationLine.class)
        .filter(
            "self.stockLocation.company = ?1 AND self.stockLocation.typeSelect = 1"
                + " AND self.detailsStockLocation IS NULL AND self.currentQty > 0"
                + " AND self.product IN (?2)",
            company,
            products)
        .order("product.code")
        .fetch();
  }

  static String listed(List<String> items) {
    String shown = String.join("; ", items.subList(0, Math.min(LISTED, items.size())));
    return items.size() > LISTED ? shown + " … и още " + (items.size() - LISTED) : shown;
  }

  private static String blankToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
