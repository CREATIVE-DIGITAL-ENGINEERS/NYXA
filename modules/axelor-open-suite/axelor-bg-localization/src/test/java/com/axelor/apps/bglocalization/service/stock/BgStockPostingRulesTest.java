package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.bglocalization.db.BgStockCostingAccountRule;
import com.axelor.apps.bglocalization.service.stock.BgStockMovementCodes.Direction;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Сметките на статията по одобрената таблица: изход Дт насрещна / Кт 30x, вход обратно. */
class BgStockPostingRulesTest {

  private static Account account(String code) {
    Account a = new Account();
    a.setCode(code);
    return a;
  }

  private static final Account STOCK = account("304");

  /** Одобрената таблица като редове на политика. */
  private static List<BgStockCostingAccountRule> defaults() {
    List<BgStockCostingAccountRule> rules = new ArrayList<>();
    for (BgStockCostingDefaults.Row row : BgStockCostingDefaults.ROWS) {
      BgStockCostingAccountRule r = new BgStockCostingAccountRule();
      r.setMovementCode(row.movementCode);
      r.setInventoryType(row.inventoryType);
      r.setWithResponsiblePartner(row.withResponsiblePartner);
      r.setAccount(account(row.accountCode));
      rules.add(r);
    }
    return rules;
  }

  private static BgStockPostingRules.Posting plan(
      String code, Direction d, String type, boolean partner) {
    return BgStockPostingRules.plan(code, d, type, partner, STOCK, defaults());
  }

  @Test
  @DisplayName("продажба на стоки: Дт 702 / Кт 304; на продукция — 701; на материали — 706")
  void продажбата() {
    BgStockPostingRules.Posting goods = plan("30", Direction.OUT, "30", false);
    assertEquals("702", goods.debit.getCode());
    assertEquals("304", goods.credit.getCode());
    assertEquals("701", plan("30", Direction.OUT, "20", false).debit.getCode());
    assertEquals("706", plan("30", Direction.OUT, "10", false).debit.getCode());
  }

  @Test
  @DisplayName("връщане от клиент: Дт 304 / Кт сметката на продажбата (702)")
  void връщането() {
    BgStockPostingRules.Posting p = plan("40", Direction.IN, "30", false);
    assertEquals("304", p.debit.getCode());
    assertEquals("702", p.credit.getCode());
  }

  @Test
  @DisplayName("120: без лице — 60905, с лице — 442 (вземане по липси); брак и срок — 60905")
  void липсите() {
    assertEquals("60905", plan("120", Direction.OUT, "30", false).debit.getCode());
    assertEquals("442", plan("120", Direction.OUT, "30", true).debit.getCode());
    assertEquals("60905", plan("160", Direction.OUT, "10", false).debit.getCode());
    assertEquals("60905", plan("170", Direction.OUT, "10", false).debit.getCode());
  }

  @Test
  @DisplayName("вход 110 и 60: Дт 30x / Кт 709; влагане 70 — Дт 60101")
  void останалите() {
    BgStockPostingRules.Posting gain = plan("110", Direction.IN, "10", false);
    assertEquals("304", gain.debit.getCode());
    assertEquals("709", gain.credit.getCode());
    assertEquals("709", plan("60", Direction.IN, "30", false).credit.getCode());
    assertEquals("60101", plan("70", Direction.OUT, "10", false).debit.getCode());
    assertEquals("60906", plan("150", Direction.OUT, "30", false).debit.getCode());
    assertEquals("60909", plan("65", Direction.OUT, "30", false).debit.getCode());
  }

  @Test
  @DisplayName("код 20 (продукция): Дт 30x на продукта / Кт 611; 180 няма подразбиране — ERROR")
  void продукцията() {
    BgStockPostingRules.Posting p =
        BgStockPostingRules.plan("20", Direction.IN, "20", false, account("303"), defaults());
    assertEquals("303", p.debit.getCode());
    assertEquals("611", p.credit.getCode());
    assertNotNull(plan("180", Direction.IN, "20", false).refusal, "180 остава без подразбиране");
  }

  @Test
  @DisplayName("10, 50, 80 и трансферът — без статия, и това не е грешка")
  void без_статия() {
    for (String code : List.of("10", "50", "80")) {
      BgStockPostingRules.Posting p = plan(code, Direction.OUT, "30", false);
      assertTrue(p.none, code);
      assertNull(p.refusal, code);
    }
    assertTrue(plan("180", Direction.TRANSFER, "30", false).none);
  }

  @Test
  @DisplayName("липсваща сметка — ERROR с името на кода, не мълчание")
  void липсващата_сметка() {
    BgStockPostingRules.Posting p = plan("180", Direction.OUT, "30", false);
    assertNotNull(p.refusal);
    assertTrue(p.refusal.contains("код 180"), p.refusal);
    BgStockPostingRules.Posting noStock =
        BgStockPostingRules.plan("30", Direction.OUT, "30", false, null, defaults());
    assertTrue(noStock.refusal.contains("30x"), noStock.refusal);
    assertTrue(
        BgStockPostingRules.plan(null, Direction.OUT, "30", false, STOCK, defaults())
            .refusal
            .contains("не се определя"));
  }

  @Test
  @DisplayName("редът с точен вид бие реда без вид")
  void точният_вид_бие() {
    List<BgStockCostingAccountRule> rules = defaults();
    BgStockCostingAccountRule specific = new BgStockCostingAccountRule();
    specific.setMovementCode("70");
    specific.setInventoryType("30");
    specific.setAccount(account("60105"));
    rules.add(specific);
    assertEquals(
        "60105",
        BgStockPostingRules.plan("70", Direction.OUT, "30", false, STOCK, rules).debit.getCode());
    assertEquals(
        "60101",
        BgStockPostingRules.plan("70", Direction.OUT, "10", false, STOCK, rules).debit.getCode());
  }
}
