package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AssetDisposalReason;
import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgFixedAssetWriteOff;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Сметката на протокола по чл. 79 ЗДДС при отписване на ДМА — от резолвъра на причината, НЕ от
 * първия дебитен ред на статията.
 *
 * <p>Правилото: унищожен актив (брак, непреодолима сила) → 60908 „Балансова стойност на отписани
 * дълготрайни активи“; изчезнал (липса) → 60905 „Разходи за липси и брак на активи“.
 *
 * <p><b>Защо напълно амортизиран актив.</b> Там базата не създава ред за балансовата стойност и
 * статията за отписване е само Дт 241 / Кт 20x. Старият протокол взимаше „първия дебитен ред“ —
 * тоест 241, натрупаната амортизация. Резолвърът тук е наследник без база: двете заявки (фирмено
 * припокриване, кодът в чарта) са подменени, а решението минава през истинските {@link
 * BgRemovalAccountRules#defaultAccountCode} и {@link BgRemovalAccountRules#chargeAccount}.
 */
class BgArt79ProtocolAccountTest {

  private final Map<String, Account> chart = new HashMap<>();

  @Test
  @DisplayName("напълно амортизиран, брак → протоколът е с 60908, не с 241")
  void брак() {
    assertEquals("60908", protocolAccount("BG_SCRAP", "60908"));
  }

  @Test
  @DisplayName("напълно амортизиран, непреодолима сила → 60908 (унищожен актив)")
  void непреодолима_сила() {
    assertEquals("60908", protocolAccount("BG_FORCE", "60908"));
  }

  @Test
  @DisplayName("напълно амортизиран, липса → 60905 от категорията (изчезнал актив)")
  void липса() {
    assertEquals("60905", protocolAccount("BG_SHORT", ""));
  }

  @Test
  @DisplayName("възстановима липса → 442, както и в отписването")
  void възстановима_липса() {
    assertEquals("442", protocolAccount("BG_SHORT_REC", "442"));
  }

  /** Протоколът при напълно амортизиран актив, причина с даден код за сметка. */
  private String protocolAccount(String reasonCode, String removalAccountCode) {
    for (String code : List.of("241", "204", "60905", "60908", "442")) {
      chart.put(code, account(code));
    }
    FixedAssetCategory category = new FixedAssetCategory();
    category.setRealisedAssetsValueAccount(chart.get("60908"));
    category.setApproExtraordDepreciationExpenseAccount(chart.get("60905"));

    AssetDisposalReason reason = new AssetDisposalReason();
    reason.setCode(reasonCode);
    reason.setBgRemovalAccountCode(removalAccountCode);

    FixedAsset asset = new FixedAsset();
    asset.setCompany(new Company());
    asset.setFixedAssetCategory(category);
    asset.setTransferredReasonSelect(FixedAssetRepository.TRANSFERED_REASON_SCRAPPING);
    asset.setAssetDisposalReason(reason);

    // Напълно амортизиран: няма ред за балансовата стойност, първият дебитен ред е 241.
    Move disposal = new Move();
    List<MoveLine> lines = new ArrayList<>();
    lines.add(line(chart.get("241"), "1000.00", "0"));
    lines.add(line(chart.get("204"), "0", "1000.00"));
    disposal.setMoveLineList(lines);

    BgFixedAssetWriteOff writeOff = new BgFixedAssetWriteOff();
    writeOff.setFixedAsset(asset);
    writeOff.setAssetDisposalReason(reason);
    writeOff.setDisposalMove(disposal);

    BgArt79ProtocolService protocol =
        new BgArt79ProtocolService(null, null, null, null, new ChartResolver());
    return protocol.expenseAccountOf(writeOff).getCode();
  }

  /** Резолвърът без база: фирменото припокриване липсва, кодът се търси в собствения чарт. */
  private final class ChartResolver extends BgRemovalAccountResolver {
    ChartResolver() {
      super(null, null);
    }

    @Override
    public Account removalAccount(Company company, AssetDisposalReason reason) {
      String code = BgRemovalAccountRules.defaultAccountCode(reason);
      return BgRemovalAccountRules.removalAccount(null, code == null ? null : chart.get(code));
    }
  }

  private static Account account(String code) {
    Account account = new Account();
    account.setCode(code);
    return account;
  }

  private static MoveLine line(Account account, String debit, String credit) {
    MoveLine line = new MoveLine();
    line.setAccount(account);
    line.setDebit(new BigDecimal(debit));
    line.setCredit(new BigDecimal(credit));
    return line;
  }
}
