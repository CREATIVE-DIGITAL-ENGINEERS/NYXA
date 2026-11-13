package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.ProductFamily;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.db.BgStockCostingAccountRule;
import com.axelor.apps.bglocalization.db.BgStockCostingFamilyOverride;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Правилата на политиката за изписване (ИЗП) — отрицателните свидетели: LIFO при МСФО, смяна на
 * метода в средата на годината, периодична средна без обобщено осчетоводяване.
 */
class BgStockCostingRulesTest {

  private static Company company(long id, String name) {
    Company c = new Company();
    c.setId(id);
    c.setName(name);
    return c;
  }

  private static Year fiscal(Company company) {
    Year y = new Year();
    y.setName("2027");
    y.setTypeSelect(YearRepository.TYPE_FISCAL);
    y.setCompany(company);
    return y;
  }

  private static BgStockCostingPolicy policy(String method, String posting) {
    Company c = company(1L, "Склад ЕООД");
    BgStockCostingPolicy p = new BgStockCostingPolicy();
    p.setCompany(c);
    p.setYear(fiscal(c));
    p.setCostingMethodSelect(method);
    p.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);
    p.setPostingModeSelect(posting);
    p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_DRAFT);
    return p;
  }

  private static ProductFamily family(long id, String code) {
    ProductFamily f = new ProductFamily();
    f.setId(id);
    f.setCode(code);
    return f;
  }

  private static BgStockCostingFamilyOverride override(ProductFamily family, String method) {
    BgStockCostingFamilyOverride o = new BgStockCostingFamilyOverride();
    o.setProductFamily(family);
    o.setCostingMethodSelect(method);
    return o;
  }

  private static BgStockCostingAccountRule rule(String code, String type, boolean partner) {
    BgStockCostingAccountRule r = new BgStockCostingAccountRule();
    r.setMovementCode(code);
    r.setInventoryType(type);
    r.setWithResponsiblePartner(partner);
    return r;
  }

  // ── LIFO само при НСС ───────────────────────────────────────────────────

  @Test
  @DisplayName("LIFO при МСФО — отказ (МСС 2)")
  void lifo_при_мсфо() {
    String refusal =
        BgStockCostingRules.refusal(
            policy(BgStockCostingRules.LIFO, BgStockCostingRules.POSTING_CURRENT),
            BgStockCostingRules.IFRS);
    assertNotNull(refusal);
    assertTrue(refusal.contains("LIFO не се допуска при МСФО"), refusal);
  }

  @Test
  @DisplayName("LIFO при непосочена база — отказ; при НСС — допустим")
  void lifo_без_база_и_при_нсс() {
    BgStockCostingPolicy p = policy(BgStockCostingRules.LIFO, BgStockCostingRules.POSTING_CURRENT);
    String refusal = BgStockCostingRules.refusal(p, null);
    assertNotNull(refusal);
    assertTrue(refusal.contains("не е посочена"), refusal);
    assertNull(BgStockCostingRules.refusal(p, BgStockCostingRules.NSS));
  }

  @Test
  @DisplayName("LIFO в изключение за семейство при МСФО — отказ, с името на семейството")
  void lifo_в_изключение() {
    BgStockCostingPolicy p = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    p.addFamilyOverrideListItem(override(family(7L, "MAT"), BgStockCostingRules.LIFO));
    assertNull(BgStockCostingRules.refusal(p, BgStockCostingRules.NSS));
    String refusal = BgStockCostingRules.refusal(p, BgStockCostingRules.IFRS);
    assertNotNull(refusal);
    assertTrue(refusal.contains("MAT"), refusal);
  }

  @Test
  @DisplayName("FIFO, средна и конкретна стойност минават и при МСФО")
  void другите_методи_при_мсфо() {
    for (String method :
        List.of(
            BgStockCostingRules.FIFO,
            BgStockCostingRules.WAP_PERPETUAL,
            BgStockCostingRules.SPECIFIC)) {
      assertNull(
          BgStockCostingRules.refusal(
              policy(method, BgStockCostingRules.POSTING_CURRENT), BgStockCostingRules.IFRS),
          method);
    }
  }

  @Test
  @DisplayName("дружеството не минава на МСФО, докато незавършила година ползва LIFO")
  void смяна_на_базата() {
    assertNotNull(
        BgStockCostingRules.frameworkChangeRefusal(BgStockCostingRules.IFRS, List.of("2027")));
    assertNull(BgStockCostingRules.frameworkChangeRefusal(BgStockCostingRules.IFRS, List.of()));
    assertNull(
        BgStockCostingRules.frameworkChangeRefusal(BgStockCostingRules.NSS, List.of("2027")));
  }

  // ── периодичната средна ─────────────────────────────────────────────────

  @Test
  @DisplayName("периодична средна при текущо осчетоводяване — отказ; при обобщено — минава")
  void периодична_иска_обобщено() {
    String refusal =
        BgStockCostingRules.refusal(
            policy(BgStockCostingRules.WAP_PERIODIC, BgStockCostingRules.POSTING_CURRENT),
            BgStockCostingRules.NSS);
    assertNotNull(refusal);
    assertTrue(refusal.contains("обобщено"), refusal);
    assertNull(
        BgStockCostingRules.refusal(
            policy(BgStockCostingRules.WAP_PERIODIC, BgStockCostingRules.POSTING_SUMMARY),
            BgStockCostingRules.NSS));

    BgStockCostingPolicy p = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    p.addFamilyOverrideListItem(override(family(3L, "GOODS"), BgStockCostingRules.WAP_PERIODIC));
    assertNotNull(BgStockCostingRules.refusal(p, BgStockCostingRules.NSS), "и в изключение");
  }

  // ── годината ────────────────────────────────────────────────────────────

  @Test
  @DisplayName("годината трябва да е финансова и на същото дружество")
  void годината() {
    BgStockCostingPolicy p = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    p.getYear().setTypeSelect(YearRepository.TYPE_PAYROLL);
    assertTrue(BgStockCostingRules.refusal(p, BgStockCostingRules.NSS).contains("не е финансова"));

    BgStockCostingPolicy q = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    q.getYear().setCompany(company(2L, "Друго АД"));
    assertTrue(BgStockCostingRules.refusal(q, BgStockCostingRules.NSS).contains("Друго АД"));
  }

  // ── смяна в средата на годината ─────────────────────────────────────────

  @Test
  @DisplayName("активна политика: смяна на метода в средата на годината — отказ (СС 2 т. 8.5)")
  void смяна_на_метода_в_средата_на_годината() {
    BgStockCostingPolicy p =
        policy(BgStockCostingRules.WAP_PERPETUAL, BgStockCostingRules.POSTING_CURRENT);
    p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
    p.setLockedSignature(BgStockCostingRules.signature(p));
    assertNull(BgStockCostingRules.lockRefusal(p), "непроменена");

    p.setCostingMethodSelect(BgStockCostingRules.FIFO);
    String refusal = BgStockCostingRules.lockRefusal(p);
    assertNotNull(refusal);
    assertTrue(refusal.contains("8.5"), refusal);
  }

  @Test
  @DisplayName("активна политика: обхватът, осчетоводяването и изключенията също са заключени")
  void заключени_са_и_останалите() {
    BgStockCostingPolicy p = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
    p.setLockedSignature(BgStockCostingRules.signature(p));

    p.setAverageScopeSelect(BgStockCostingRules.SCOPE_COMPANY);
    assertNotNull(BgStockCostingRules.lockRefusal(p), "обхват");
    p.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);

    p.setPostingModeSelect(BgStockCostingRules.POSTING_SUMMARY);
    assertNotNull(BgStockCostingRules.lockRefusal(p), "осчетоводяване");
    p.setPostingModeSelect(BgStockCostingRules.POSTING_CURRENT);

    p.addFamilyOverrideListItem(override(family(4L, "MAT"), BgStockCostingRules.WAP_PERPETUAL));
    assertNotNull(BgStockCostingRules.lockRefusal(p), "ново изключение");
  }

  @Test
  @DisplayName("активна политика: сметките се сменят свободно; чернова — всичко")
  void сметките_не_са_заключени() {
    BgStockCostingPolicy p = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
    p.setLockedSignature(BgStockCostingRules.signature(p));
    p.addAccountRuleListItem(rule("30", "30", false));
    assertNull(BgStockCostingRules.lockRefusal(p));

    BgStockCostingPolicy draft =
        policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    draft.setLockedSignature("method=WAP_PERPETUAL;scope=WAREHOUSE;posting=CURRENT;families=");
    assertNull(BgStockCostingRules.lockRefusal(draft), "черновата не е заключена");
  }

  @Test
  @DisplayName("„Валидиране на статиите“ не е метод — сменя се и в активна политика")
  void валидирането_не_е_заключено() {
    BgStockCostingPolicy p = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
    p.setMoveValidationSelect(BgStockCostingRules.VALIDATION_AUTO);
    p.setLockedSignature(BgStockCostingRules.signature(p));
    p.setMoveValidationSelect(BgStockCostingRules.VALIDATION_MANUAL);
    assertNull(BgStockCostingRules.lockRefusal(p));
  }

  @Test
  @DisplayName("отпечатъкът не зависи от реда на изключенията")
  void отпечатъкът_е_подреден() {
    BgStockCostingPolicy a = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    a.addFamilyOverrideListItem(override(family(1L, "A"), BgStockCostingRules.WAP_PERPETUAL));
    a.addFamilyOverrideListItem(override(family(2L, "B"), BgStockCostingRules.SPECIFIC));
    BgStockCostingPolicy b = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    b.addFamilyOverrideListItem(override(family(2L, "B"), BgStockCostingRules.SPECIFIC));
    b.addFamilyOverrideListItem(override(family(1L, "A"), BgStockCostingRules.WAP_PERPETUAL));
    assertEquals(BgStockCostingRules.signature(a), BgStockCostingRules.signature(b));
  }

  @Test
  @DisplayName("активна политика не се трие; черновата — да")
  void триене() {
    BgStockCostingPolicy p = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    assertNull(BgStockCostingRules.removeRefusal(p));
    p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
    assertNotNull(BgStockCostingRules.removeRefusal(p));
  }

  // ── редовете за сметки ──────────────────────────────────────────────────

  @Test
  @DisplayName("ред за 10, 50 или 80 — отказ: те нямат статия от склада (модел A)")
  void кодове_без_статия() {
    for (String code : List.of("10", "50", "80")) {
      String refusal = BgStockCostingRules.accountRulesRefusal(List.of(rule(code, null, false)));
      assertNotNull(refusal, code);
      assertTrue(refusal.contains("няма статия от склада"), refusal);
    }
    assertNull(BgStockCostingRules.accountRulesRefusal(List.of(rule("30", "30", false))));
  }

  @Test
  @DisplayName("„с виновно лице“ само за 120; два реда за един ключ — отказ")
  void виновно_лице_и_дубликати() {
    assertNotNull(BgStockCostingRules.accountRulesRefusal(List.of(rule("160", null, true))));
    assertNull(
        BgStockCostingRules.accountRulesRefusal(
            List.of(rule("120", null, true), rule("120", null, false))));
    assertNotNull(
        BgStockCostingRules.accountRulesRefusal(
            List.of(rule("30", "30", false), rule("30", "30", false))));
    assertNull(
        BgStockCostingRules.accountRulesRefusal(
            List.of(rule("30", "30", false), rule("30", "20", false))));
  }

  @Test
  @DisplayName("сметка от чужд сметкоплан — отказ")
  void чужда_сметка() {
    BgStockCostingPolicy p = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    BgStockCostingAccountRule r = rule("30", "30", false);
    Account account = new Account();
    account.setCode("702");
    account.setCompany(company(9L, "Чуждо ООД"));
    r.setAccount(account);
    p.addAccountRuleListItem(r);
    String refusal = BgStockCostingRules.refusal(p, BgStockCostingRules.NSS);
    assertNotNull(refusal);
    assertTrue(refusal.contains("Чуждо ООД"), refusal);

    account.setCompany(p.getCompany());
    assertNull(BgStockCostingRules.refusal(p, BgStockCostingRules.NSS));
  }

  // ── методът за семейство ────────────────────────────────────────────────

  @Test
  @DisplayName("методът за семейство: изключението бие политиката")
  void метод_за_семейство() {
    BgStockCostingPolicy p = policy(BgStockCostingRules.FIFO, BgStockCostingRules.POSTING_CURRENT);
    ProductFamily mat = family(4L, "MAT");
    p.addFamilyOverrideListItem(override(mat, BgStockCostingRules.WAP_PERPETUAL));
    assertEquals(BgStockCostingRules.WAP_PERPETUAL, BgStockCostingRules.methodFor(p, mat));
    assertEquals(BgStockCostingRules.FIFO, BgStockCostingRules.methodFor(p, family(5L, "GOODS")));
    assertEquals(BgStockCostingRules.FIFO, BgStockCostingRules.methodFor(p, null));
    assertTrue(BgStockCostingRules.usesLifo(policy(BgStockCostingRules.LIFO, "CURRENT")));
    assertFalse(BgStockCostingRules.usesLifo(p));
  }
}
