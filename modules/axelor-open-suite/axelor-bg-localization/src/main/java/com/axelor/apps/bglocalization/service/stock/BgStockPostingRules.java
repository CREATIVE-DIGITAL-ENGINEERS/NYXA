package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.bglocalization.db.BgStockCostingAccountRule;
import com.axelor.apps.bglocalization.service.stock.BgStockMovementCodes.Direction;
import java.util.Collection;
import java.util.Collections;

/**
 * Сметките на статията за един ред на движението (ИЗП). Чиста функция.
 *
 * <p>Едната страна е складовата 30x на семейството (S0, {@code AccountManagement.bgStockAccount}),
 * другата — насрещната от таблицата на политиката. Изход: Дт насрещната / Кт 30x. Вход: Дт 30x / Кт
 * насрещната. Редът с точен вид на запаса бие реда без вид.
 *
 * <p>10, 50 и 80 нямат статия от склада ({@link BgStockCostingRules#CODES_WITHOUT_POSTING}).
 * Липсваща сметка е грешка с име, не мълчание.
 */
public final class BgStockPostingRules {

  /** Дебит и кредит на статията, или причината да няма такава. */
  public static final class Posting {
    public final Account debit;
    public final Account credit;
    public final String refusal;

    /** {@code true} — кодът няма статия от склада (не е грешка). */
    public final boolean none;

    private Posting(Account debit, Account credit, String refusal, boolean none) {
      this.debit = debit;
      this.credit = credit;
      this.refusal = refusal;
      this.none = none;
    }

    static Posting of(Account debit, Account credit) {
      return new Posting(debit, credit, null, false);
    }

    static Posting refused(String refusal) {
      return new Posting(null, null, refusal, false);
    }

    static Posting noPosting() {
      return new Posting(null, null, null, true);
    }
  }

  private BgStockPostingRules() {}

  /**
   * @param code кодът по „Stock movement“
   * @param direction IN или OUT (трансферът няма статия)
   * @param inventoryType видът на запаса на продукта
   * @param withResponsiblePartner 120 с виновно лице (причината го изисква и лицето е посочено)
   * @param stockAccount складовата 30x на семейството/продукта
   * @param rules редовете на политиката
   */
  public static Posting plan(
      String code,
      Direction direction,
      String inventoryType,
      boolean withResponsiblePartner,
      Account stockAccount,
      Collection<BgStockCostingAccountRule> rules) {
    if (code == null) {
      return Posting.refused(
          "Кодът по „Stock movement“ не се определя от движението. Посочете ръчния код в"
              + " движението (раздел SAF-T) и обработете изписването отново.");
    }
    if (BgStockCostingRules.CODES_WITHOUT_POSTING.contains(code)
        || direction == Direction.TRANSFER) {
      return Posting.noPosting();
    }
    Account counter = counterAccount(code, inventoryType, withResponsiblePartner, rules);
    if (counter == null) {
      return Posting.refused(
          String.format(
              "В политиката за изписване няма сметка за код %s%s%s. Добавете ред в „Сметки по"
                  + " код на движението“ и обработете изписването отново.",
              code,
              inventoryType == null ? "" : ", вид на запаса " + inventoryType,
              withResponsiblePartner ? ", с виновно лице" : ""));
    }
    if (stockAccount == null) {
      return Posting.refused(
          "Продуктът няма складова сметка 30x: попълнете „Складова сметка (30x)“ в счетоводната"
              + " настройка на семейството му (AccountManagement) за дружеството.");
    }
    return direction == Direction.IN
        ? Posting.of(stockAccount, counter)
        : Posting.of(counter, stockAccount);
  }

  /** Насрещната сметка: ред с точен вид, после ред без вид; лицето трябва да съвпада. */
  static Account counterAccount(
      String code,
      String inventoryType,
      boolean withResponsiblePartner,
      Collection<BgStockCostingAccountRule> rules) {
    Account any = null;
    for (BgStockCostingAccountRule rule :
        rules == null ? Collections.<BgStockCostingAccountRule>emptyList() : rules) {
      if (!code.equals(rule.getMovementCode())
          || withResponsiblePartner != Boolean.TRUE.equals(rule.getWithResponsiblePartner())) {
        continue;
      }
      String type = rule.getInventoryType();
      if (type == null || type.trim().isEmpty()) {
        any = rule.getAccount();
      } else if (type.trim().equals(inventoryType)) {
        return rule.getAccount();
      }
    }
    return any;
  }
}
