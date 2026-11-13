package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.ProductFamily;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.db.BgStockCostingAccountRule;
import com.axelor.apps.bglocalization.db.BgStockCostingFamilyOverride;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Правилата на политиката за изписване (ИЗП). Чиста функция: чете само подадения обект, не базата.
 *
 * <ul>
 *   <li><b>LIFO само при НСС.</b> СС 2 т. 10 го допуска като алтернативен подход, МСС 2 — не. При
 *       непосочена база LIFO също се отказва: допустимостта му не може да се докаже.
 *   <li><b>Периодичната средна иска обобщено осчетоводяване</b> — средната е известна чак в края на
 *       месеца, тъй че текущото няма по какво да осчетоводи.
 *   <li><b>Годината е финансова и е на същото дружество.</b>
 *   <li><b>Активираната политика не сменя метода</b> (СС 2 т. 8.5: подходът се прилага системно
 *       най-малко през един отчетен период). Заключени са методът, обхватът на средната,
 *       осчетоводяването и изключенията по семейства; сметките не са — те са адрес на статията.
 *   <li><b>Ред за сметка само за код, който се осчетоводява от склада.</b> 10, 50 и 80 нямат статия
 *       от склада (модел A: фактурата вече движи 30x; трансферът е в същата 30x) — ред за тях би
 *       бил мъртъв и би заблудил, че нещо се осчетоводява.
 * </ul>
 */
public final class BgStockCostingRules {

  public static final String NSS = "NSS";
  public static final String IFRS = "IFRS";

  public static final String WAP_PERPETUAL = "WAP_PERPETUAL";
  public static final String WAP_PERIODIC = "WAP_PERIODIC";
  public static final String FIFO = "FIFO";
  public static final String LIFO = "LIFO";
  public static final String SPECIFIC = "SPECIFIC";

  public static final String SCOPE_WAREHOUSE = "WAREHOUSE";
  public static final String SCOPE_COMPANY = "COMPANY";

  public static final String VALIDATION_AUTO = "AUTO";
  public static final String VALIDATION_MANUAL = "MANUAL";

  public static final String POSTING_CURRENT = "CURRENT";
  public static final String POSTING_SUMMARY = "SUMMARY";

  /**
   * Кодовете без статия от склада: 10 покупка и 50 връщане към доставчик (модел A — фактурата и
   * кредитното известие движат 30x), 80 трансфер (същата 30x).
   */
  public static final Set<String> CODES_WITHOUT_POSTING =
      Collections.unmodifiableSet(new HashSet<>(List.of("10", "50", "80")));

  private BgStockCostingRules() {}

  /** Защо методът не е допустим при тази база, или {@code null}. */
  public static String methodRefusal(String framework, String method, String where) {
    if (!LIFO.equals(method)) {
      return null;
    }
    if (IFRS.equals(framework)) {
      return String.format(
          "LIFO не се допуска при МСФО (МСС 2)%s. Изберете FIFO, средна претеглена или конкретно"
              + " определена стойност.",
          where);
    }
    if (!NSS.equals(framework)) {
      return String.format(
          "LIFO е допустим само при НСС (СС 2 т. 10)%s, а приложимата счетоводна база на"
              + " дружеството не е посочена. Посочете я в дружеството (раздел SAF-T и счетоводна"
              + " политика).",
          where);
    }
    return null;
  }

  /** Защо методът не върви с това осчетоводяване, или {@code null}. */
  public static String postingRefusal(String method, String postingMode, String where) {
    if (WAP_PERIODIC.equals(method) && !POSTING_SUMMARY.equals(postingMode)) {
      return String.format(
          "Периодичната средна претеглена%s е известна чак в края на месеца и иска обобщено"
              + " осчетоводяване. Изберете „Обобщено — в края на месеца“.",
          where);
    }
    return null;
  }

  /** Защо годината не става за политиката на дружеството, или {@code null}. */
  public static String yearRefusal(Year year, Company company) {
    if (year == null) {
      return null;
    }
    if (year.getTypeSelect() != YearRepository.TYPE_FISCAL) {
      return String.format(
          "Годината „%s“ не е финансова. Методът влиза в сила от началото на финансовата година.",
          year.getName());
    }
    if (company != null && year.getCompany() != null && !sameId(company, year.getCompany())) {
      return String.format(
          "Годината „%s“ е на друго дружество („%s“).",
          year.getName(), year.getCompany().getName());
    }
    return null;
  }

  /** Първата причина политиката да не се запише, или {@code null}. Без заключването. */
  public static String refusal(BgStockCostingPolicy policy, String framework) {
    if (policy == null) {
      return null;
    }
    List<String> refusals = new ArrayList<>();
    refusals.add(yearRefusal(policy.getYear(), policy.getCompany()));
    refusals.add(methodRefusal(framework, policy.getCostingMethodSelect(), ""));
    refusals.add(
        postingRefusal(policy.getCostingMethodSelect(), policy.getPostingModeSelect(), ""));
    Set<Long> families = new HashSet<>();
    for (BgStockCostingFamilyOverride o : nonNull(policy.getFamilyOverrideList())) {
      String where = " (семейство „" + familyLabel(o.getProductFamily()) + "“)";
      refusals.add(methodRefusal(framework, o.getCostingMethodSelect(), where));
      refusals.add(
          postingRefusal(o.getCostingMethodSelect(), policy.getPostingModeSelect(), where));
      if (o.getProductFamily() != null
          && o.getProductFamily().getId() != null
          && !families.add(o.getProductFamily().getId())) {
        refusals.add(
            String.format(
                "Семейството „%s“ е с два реда за метод — един ред на семейство.",
                familyLabel(o.getProductFamily())));
      }
    }
    refusals.add(accountRulesRefusal(policy.getAccountRuleList()));
    refusals.add(accountCompanyRefusal(policy));
    for (String r : refusals) {
      if (r != null) {
        return r;
      }
    }
    return null;
  }

  /** Защо редовете за сметки не стават, или {@code null}. */
  public static String accountRulesRefusal(Collection<BgStockCostingAccountRule> rules) {
    Set<String> keys = new HashSet<>();
    for (BgStockCostingAccountRule rule : nonNull(rules)) {
      String code = rule.getMovementCode();
      if (code == null) {
        continue;
      }
      if (CODES_WITHOUT_POSTING.contains(code)) {
        return String.format(
            "Код %s няма статия от склада: покупката и връщането към доставчик се осчетоводяват с"
                + " фактурата/кредитното известие, а вътрешният трансфер остава в същата 30x."
                + " Махнете реда.",
            code);
      }
      if (BgStockMovementCodes.allowed(code) == null) {
        return String.format("Кодът „%s“ не е в номенклатурата „Stock movement“.", code);
      }
      if (Boolean.TRUE.equals(rule.getWithResponsiblePartner()) && !"120".equals(code)) {
        return String.format(
            "„С виновно лице“ важи само за код 120 (отрицателна корекция), а редът е за код %s.",
            code);
      }
      String key =
          code + "|" + blank(rule.getInventoryType()) + "|" + rule.getWithResponsiblePartner();
      if (!keys.add(key)) {
        return String.format(
            "Код %s%s е с два реда за сметка — един ред на код, вид и виновно лице.",
            code, rule.getInventoryType() == null ? "" : " / вид " + rule.getInventoryType());
      }
    }
    return null;
  }

  /** Сметка от чужд сметкоплан би вкарала статията в друго дружество. Отказът, или {@code null}. */
  static String accountCompanyRefusal(BgStockCostingPolicy policy) {
    Company company = policy.getCompany();
    for (BgStockCostingAccountRule rule : nonNull(policy.getAccountRuleList())) {
      Account account = rule.getAccount();
      if (company != null
          && account != null
          && account.getCompany() != null
          && !sameId(company, account.getCompany())) {
        return String.format(
            "Сметката %s (код %s) е от сметкоплана на „%s“, а политиката е на „%s“.",
            account.getCode(),
            rule.getMovementCode(),
            account.getCompany().getName(),
            company.getName());
      }
    }
    return null;
  }

  /**
   * Отпечатъкът на заключените полета: метод, обхват, осчетоводяване и изключенията по семейства,
   * подредени по id на семейството. Сметките НЕ влизат.
   */
  public static String signature(BgStockCostingPolicy policy) {
    List<String> overrides = new ArrayList<>();
    for (BgStockCostingFamilyOverride o : nonNull(policy.getFamilyOverrideList())) {
      Long family = o.getProductFamily() == null ? null : o.getProductFamily().getId();
      overrides.add(family + "=" + o.getCostingMethodSelect());
    }
    Collections.sort(overrides);
    return String.join(
        ";",
        "method=" + policy.getCostingMethodSelect(),
        "scope=" + policy.getAverageScopeSelect(),
        "posting=" + policy.getPostingModeSelect(),
        "families=" + String.join(",", overrides));
  }

  /**
   * Активираната политика не сменя метода в средата на годината. Връща отказа, или {@code null}.
   */
  public static String lockRefusal(BgStockCostingPolicy policy) {
    if (policy == null
        || policy.getStatusSelect() != BgStockCostingPolicyRepository.STATUS_ACTIVE
        || policy.getLockedSignature() == null) {
      return null;
    }
    if (policy.getLockedSignature().equals(signature(policy))) {
      return null;
    }
    return String.format(
        "Политиката за %s е активна: методът, средната цена, осчетоводяването и изключенията по"
            + " семейства не се сменят в средата на финансовата година — подходът се прилага"
            + " системно най-малко през един отчетен период (СС 2 т. 8.5). Новият метод се задава в"
            + " политиката за следващата година. Сметките могат да се сменят.",
        policy.getYear() == null ? "годината" : "„" + policy.getYear().getName() + "“");
  }

  /** Активната политика не се трие — с нея са осчетоводени изписванията за годината. */
  public static String removeRefusal(BgStockCostingPolicy policy) {
    if (policy == null
        || policy.getStatusSelect() != BgStockCostingPolicyRepository.STATUS_ACTIVE) {
      return null;
    }
    return "Активна политика не се трие: по нея се оценява изписването за годината.";
  }

  /**
   * Дружеството минава на МСФО, а политика за незавършила година ползва LIFO. Връща отказа, или
   * {@code null}. {@code lifoYears} са имената на тези години.
   */
  public static String frameworkChangeRefusal(String framework, Collection<String> lifoYears) {
    if (!IFRS.equals(framework) || lifoYears == null || lifoYears.isEmpty()) {
      return null;
    }
    return String.format(
        "Дружеството не може да мине на МСФО, докато политиката за изписване за %s ползва LIFO —"
            + " МСС 2 не го допуска. Сменете метода в политиката за следващата година.",
        String.join(", ", lifoYears));
  }

  /** Методът за семейството: изключението, ако има, иначе методът на политиката. */
  public static String methodFor(BgStockCostingPolicy policy, ProductFamily family) {
    if (policy == null) {
      return null;
    }
    if (family != null) {
      for (BgStockCostingFamilyOverride o : nonNull(policy.getFamilyOverrideList())) {
        if (o.getProductFamily() != null && sameId(o.getProductFamily(), family)) {
          return o.getCostingMethodSelect();
        }
      }
    }
    return policy.getCostingMethodSelect();
  }

  /** Дали политиката ползва LIFO — сама или в изключение. */
  public static boolean usesLifo(BgStockCostingPolicy policy) {
    if (policy == null) {
      return false;
    }
    if (LIFO.equals(policy.getCostingMethodSelect())) {
      return true;
    }
    for (BgStockCostingFamilyOverride o : nonNull(policy.getFamilyOverrideList())) {
      if (LIFO.equals(o.getCostingMethodSelect())) {
        return true;
      }
    }
    return false;
  }

  private static boolean sameId(Object a, Object b) {
    if (a == b) {
      return true;
    }
    Long ia = idOf(a);
    Long ib = idOf(b);
    return ia != null && Objects.equals(ia, ib);
  }

  private static Long idOf(Object o) {
    if (o instanceof Company) {
      return ((Company) o).getId();
    }
    if (o instanceof ProductFamily) {
      return ((ProductFamily) o).getId();
    }
    return null;
  }

  private static String familyLabel(ProductFamily family) {
    if (family == null) {
      return "?";
    }
    return family.getCode() != null ? family.getCode() : String.valueOf(family.getName());
  }

  private static String blank(String value) {
    return value == null ? "" : value.trim();
  }

  private static <T> Collection<T> nonNull(Collection<T> list) {
    return list == null ? Collections.emptyList() : list;
  }
}
