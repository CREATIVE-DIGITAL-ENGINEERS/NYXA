package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingRules;

/**
 * РАМКАТА НА ДРУЖЕСТВОТО ЗА АКТИВИТЕ — {@code Company.bgAccountingFramework} (НСС / МСФО), един
 * вход за пистата на активите (SAF-T A1–A6).
 *
 * <p><b>Без мълчалив default</b>: празната рамка не се чете като НСС, макар ЗСч чл. 34 да прави НСС
 * основното правило — правилата за финансирането, началото и спирането на амортизацията и
 * инвестиционния имот зависят от нея, а грешното предположение не личи. Затова {@link #of} връща
 * {@code null} и всеки потребител дава отказ или ГРЕШКА с {@link #NOT_SET}.
 *
 * <p>Стойностите са същите като на склада ({@link BgStockCostingRules#NSS}, {@link
 * BgStockCostingRules#IFRS}) — една селекция {@code bg.accounting.framework.select}.
 */
public final class BgAccountingFramework {

  public static final String NSS = BgStockCostingRules.NSS;
  public static final String IFRS = BgStockCostingRules.IFRS;

  public static final String NOT_SET =
      "Рамката на дружеството (НСС / МСФО) не е зададена — „Приложима счетоводна база“ в"
          + " настройките на дружеството.";

  private BgAccountingFramework() {}

  /** НСС или МСФО; {@code null} без дружество, без рамка или с непозната стойност. */
  public static String of(Company company) {
    String framework = company == null ? null : company.getBgAccountingFramework();
    return NSS.equals(framework) || IFRS.equals(framework) ? framework : null;
  }
}
