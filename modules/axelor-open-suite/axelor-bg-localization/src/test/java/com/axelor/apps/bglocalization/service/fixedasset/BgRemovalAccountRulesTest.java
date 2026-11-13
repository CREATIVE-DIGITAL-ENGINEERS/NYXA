package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AssetDisposalReason;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Сметката, която носи стойността на актива при отписване.
 *
 * <p>Възстановимата липса не е разход, а вземане: при отговорно лице стойността не напуска
 * предприятието, а сменя формата си. Осчетоводена на 60905, тя намалява финансовия резултат с нещо,
 * което ще се събере.
 *
 * <p><b>⚠️ ОТ PR-N2 НОМЕРЪТ Е 60908, А НЕ 609.</b> 609 „Други разходи“ стана репортен ВЪЗЕЛ и вече
 * не се избира за статия; всичките петнайсет категории в {@code account_fixedAssetCategory.csv}
 * сочат НАП листото 60908 „Балансова стойност на отписани дълготрайни активи“ — дословно това,
 * което полето {@code realisedAssetsValueAccount} значи. Фикстурата тук е СОБСТВЕНА, тъй че тестът
 * щеше да мине и със стария номер: това е огледало на избора в seed-а и трябва да се мени заедно с
 * него, инак документира състояние, което вече го няма.
 */
class BgRemovalAccountRulesTest {

  @Test
  @DisplayName("огледалото повтаря избора на базата: продажба → 60908, всичко останало → 60905")
  void огледалото_на_базовия_избор() {
    // FixedAssetLineMoveServiceImpl.generateDisposalMove решава вътре в себе си, без шев.
    // Огледалото
    // служи да се РАЗПОЗНАЕ редът за пренасочване, не да се вземе решението наново.
    FixedAssetCategory cat = category("60908", "60905");
    assertEquals(
        "60908",
        BgRemovalAccountRules.baseChargeAccount(cat, FixedAssetRepository.TRANSFERED_REASON_CESSION)
            .getCode());
    assertEquals(
        "60908",
        BgRemovalAccountRules.baseChargeAccount(
                cat, FixedAssetRepository.TRANSFERED_REASON_PARTIAL_CESSION)
            .getCode());
    assertEquals(
        "60905",
        BgRemovalAccountRules.baseChargeAccount(
                cat, FixedAssetRepository.TRANSFERED_REASON_SCRAPPING)
            .getCode(),
        "липси и брак минават по клона „всичко останало“");
    assertNull(
        BgRemovalAccountRules.baseChargeAccount(
            null, FixedAssetRepository.TRANSFERED_REASON_SCRAPPING));
  }

  @Test
  @DisplayName("сметката на отписването: разрешената за причината, иначе базовата за вида")
  void сметката_на_отписването() {
    // Това е и сметката на протокола по чл. 79 — НЕ първият дебитен ред на статията, който при
    // напълно амортизиран актив е 241/242.
    FixedAssetCategory cat = category("60908", "60905");
    int брак = FixedAssetRepository.TRANSFERED_REASON_SCRAPPING;
    assertEquals("60905", BgRemovalAccountRules.chargeAccount(null, cat, брак).getCode());
    Account унищожен = account("60908");
    assertSame(унищожен, BgRemovalAccountRules.chargeAccount(унищожен, cat, брак));
    assertEquals(
        "60908",
        BgRemovalAccountRules.chargeAccount(
                null, cat, FixedAssetRepository.TRANSFERED_REASON_CESSION)
            .getCode());
  }

  @Test
  @DisplayName("намира се ДЕБИТНИЯТ ред по очакваната сметка, не кредитните")
  void намира_дебитния_ред() {
    Account разход = account("60905");
    Account актив = account("204");
    Account амортизация = account("241");
    List<MoveLine> lines =
        new ArrayList<>(
            Arrays.asList(
                credit(актив, "10000.00"),
                debit(амортизация, "7500.00"),
                debit(разход, "2500.00")));

    MoveLine found = BgRemovalAccountRules.chargeLineToRepoint(lines, разход);
    assertSame(lines.get(2), found, "дебитният ред на 60905 носи балансовата стойност");

    assertNull(
        BgRemovalAccountRules.chargeLineToRepoint(lines, account("999")),
        "непозната сметка → няма какво да се пренасочи");
    assertNull(BgRemovalAccountRules.chargeLineToRepoint(null, разход));
    assertNull(BgRemovalAccountRules.chargeLineToRepoint(lines, null));
  }

  @Test
  @DisplayName("⚠️ напълно амортизиран актив няма разходен ред — и това не е грешка")
  void напълно_амортизиран_няма_ред() {
    // Базата създава разходния ред само при chargeAmount.signum() != 0. При нулева балансова
    // стойност такъв ред просто няма, тъй че пренасочване не се случва и това е нормалният изход.
    List<MoveLine> lines =
        new ArrayList<>(
            Arrays.asList(credit(account("204"), "10000.00"), debit(account("241"), "10000.00")));
    assertNull(BgRemovalAccountRules.chargeLineToRepoint(lines, account("60905")));
  }

  @Test
  @DisplayName("липсващ мапинг значи „остави както е“, не грешка")
  void липсващ_мапинг_не_е_грешка() {
    Account текуща = account("60905");
    assertFalse(
        BgRemovalAccountRules.shouldRepoint(null, текуща),
        "без запис в мапинга поведението остава базовото — 60905 — и е видимо в статията");
    assertFalse(
        BgRemovalAccountRules.shouldRepoint(account("60905"), текуща),
        "мапинг към същата сметка не е промяна");
    assertTrue(
        BgRemovalAccountRules.shouldRepoint(account("442"), текуща),
        "мапинг към вземане пренасочва: възстановимата липса не е разход");
  }

  // ── помощни ─────────────────────────────────────────────────────────────

  private static Account account(String code) {
    Account a = new Account();
    a.setCode(code);
    return a;
  }

  private static FixedAssetCategory category(String realised, String extraordinary) {
    FixedAssetCategory c = new FixedAssetCategory();
    c.setRealisedAssetsValueAccount(account(realised));
    c.setApproExtraordDepreciationExpenseAccount(account(extraordinary));
    return c;
  }

  private static MoveLine debit(Account account, String amount) {
    MoveLine line = new MoveLine();
    line.setAccount(account);
    line.setDebit(new BigDecimal(amount));
    line.setCredit(BigDecimal.ZERO);
    return line;
  }

  private static MoveLine credit(Account account, String amount) {
    MoveLine line = new MoveLine();
    line.setAccount(account);
    line.setDebit(BigDecimal.ZERO);
    line.setCredit(new BigDecimal(amount));
    return line;
  }

  @Test
  @DisplayName("подразбиращият се код идва от причината, а празният се чете като „няма“")
  void кодът_по_подразбиране() {
    assertNull(BgRemovalAccountRules.defaultAccountCode(null));
    assertNull(BgRemovalAccountRules.defaultAccountCode(new AssetDisposalReason()));
    assertEquals("442", BgRemovalAccountRules.defaultAccountCode(reason("442")));
    assertEquals(
        "442",
        BgRemovalAccountRules.defaultAccountCode(reason("  442 ")),
        "колоната се пълни на ръка — заобикалящите интервали не бива да менят поведението");
    assertNull(
        BgRemovalAccountRules.defaultAccountCode(reason("   ")),
        "само бели интервали значи „няма код“, също като празно: инак търсенето би тръгнало с"
            + " код, който никоя сметка няма, и щеше да се държи различно от празно без причина");
  }

  @Test
  @DisplayName("фирменото припокриване печели над подразбирането, а нищо значи „остави 60905“")
  void редът_на_резолюция() {
    Account поКод = account("442");
    Account припокриване = account("4425");

    assertSame(
        припокриване,
        BgRemovalAccountRules.removalAccount(припокриване, поКод),
        "редът в BgAssetRemovalAccount е изричен избор на човек за ЕДНА фирма — той бие"
            + " подразбирането, което важи за всички");
    assertSame(поКод, BgRemovalAccountRules.removalAccount(null, поКод));
    assertSame(припокриване, BgRemovalAccountRules.removalAccount(припокриване, null));
    assertNull(
        BgRemovalAccountRules.removalAccount(null, null),
        "нито ред, нито намерен код значи „остави както е“ — сметката остава онази, която базата е"
            + " избрала (60905 за липси). Това е нормален изход, видим в статията, а не грешка");
  }

  @Test
  @DisplayName("възстановимата липса сменя ИМЕННО дебитния 60905 ред, и то на 442")
  void дебитният_ред_отива_на_442() {
    // Еталонът за двете причини едно до друго: при BG_SHORT редът остава 60905, при BG_SHORT_REC
    // същият ред става 442. Кредитните редове — отписването на актива (Кт 20x) и натрупаната
    // амортизация — не се пипат в нито единия случай.
    Account разход60905 = account("60905");
    Account вземане442 = account("442");
    Account актив204 = account("204");

    MoveLine дебит60905 = debit(разход60905, "1000.00");
    MoveLine кредит204 = credit(актив204, "1000.00");
    List<MoveLine> редове = Arrays.asList(дебит60905, кредит204);

    MoveLine намерен = BgRemovalAccountRules.chargeLineToRepoint(редове, разход60905);
    assertSame(дебит60905, намерен, "пренасочва се ДЕБИТНИЯТ ред по очакваната сметка");

    assertTrue(
        BgRemovalAccountRules.shouldRepoint(вземане442, намерен.getAccount()),
        "BG_SHORT_REC: 60905 → 442, защото стойността е вземане, а не разход");
    assertFalse(
        BgRemovalAccountRules.shouldRepoint(разход60905, намерен.getAccount()),
        "BG_SHORT: мапинг към същата сметка не е промяна — редът остава 60905");
    assertSame(
        актив204,
        кредит204.getAccount(),
        "кредитният ред е отписването на самия актив и не се пипа при никоя от двете причини");
  }

  @Test
  @DisplayName("търсенето на подразбиращата се сметка е ПО ФИРМА, не само по код")
  void резолверът_е_company_scoped() {
    // Account е уникален по (code, company): един и същ код „442“ съществува отделно за всяко
    // дружество. Търсене само по код с fetchOne() връща произволната — точно това прави базовият
    // резолвър на справките (ImportAccountingReportConfigLine#setAccounts). Филтърът стои като
    // константа именно за да е проверим оттук.
    assertTrue(
        BgRemovalAccountRules.DEFAULT_ACCOUNT_FILTER.contains("self.company"),
        "без фирма в филтъра статията на едното дружество би сочила сметка на другото: "
            + BgRemovalAccountRules.DEFAULT_ACCOUNT_FILTER);
    assertTrue(
        BgRemovalAccountRules.DEFAULT_ACCOUNT_FILTER.contains("self.code"),
        "подразбирането се намира по КОД — причината няма компания и не може да носи връзка");
    assertTrue(
        BgRemovalAccountRules.DEFAULT_ACCOUNT_FILTER.contains("self.statusSelect"),
        "деактивирана сметка на ред от статия поваля валидирането с INACTIVE_ACCOUNT_FOUND."
            + " Подразбирането трябва да отстъпи към 60905, а не да спре цяло отписване");
    assertTrue(
        BgRemovalAccountRules.OVERRIDE_FILTER.contains("self.company"),
        "BgAssetRemovalAccount е с unique-constraint по (company, assetDisposalReason) — търсене"
            + " без фирма би върнало ред на чуждо дружество: "
            + BgRemovalAccountRules.OVERRIDE_FILTER);
  }

  private static AssetDisposalReason reason(String code) {
    AssetDisposalReason reason = new AssetDisposalReason();
    reason.setBgRemovalAccountCode(code);
    return reason;
  }
}
