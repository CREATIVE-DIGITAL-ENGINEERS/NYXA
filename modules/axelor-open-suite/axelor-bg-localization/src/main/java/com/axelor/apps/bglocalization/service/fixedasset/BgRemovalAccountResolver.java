package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AssetDisposalReason;
import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgAssetRemovalAccount;
import com.axelor.apps.bglocalization.db.repo.BgAssetRemovalAccountRepository;
import com.google.inject.Inject;

/**
 * ЕДИНСТВЕНИЯТ резолвър на сметката, която носи стойността на актива при отписване.
 *
 * <p>Ползват го и отписването ({@link BgFixedAssetDisposalServiceImpl#repointChargeAccount}), и
 * протоколът по чл. 79 ЗДДС ({@link BgArt79ProtocolService}). Двата трябва да стигат до ЕДНА И СЪЩА
 * сметка: протоколът дебитира разхода, в който активът е отписан. Преди протоколът четеше „първия
 * дебитен ред“ на статията — но при напълно амортизиран актив базата не създава ред за балансовата
 * стойност ({@code chargeAmount.signum() != 0}), и първият дебитен ред е натрупаната амортизация
 * 241/242. Протокол Дт 241 е грешен: амортизацията не е разход.
 *
 * <p>Редът на стъпалата е „фирмено припокриване → кодът от причината → базовият избор“ — виж {@link
 * BgRemovalAccountRules#chargeAccount}.
 */
public class BgRemovalAccountResolver {

  protected final BgAssetRemovalAccountRepository bgAssetRemovalAccountRepository;
  protected final AccountRepository accountRepository;

  @Inject
  public BgRemovalAccountResolver(
      BgAssetRemovalAccountRepository bgAssetRemovalAccountRepository,
      AccountRepository accountRepository) {
    this.bgAssetRemovalAccountRepository = bgAssetRemovalAccountRepository;
    this.accountRepository = accountRepository;
  }

  /**
   * Сметката, в която активът се отписва: разрешената за причината, иначе базовата за вида
   * отписване. Не зависи от това дали статията изобщо има ред за балансовата стойност.
   */
  public Account chargeAccount(
      FixedAsset fixedAsset, AssetDisposalReason reason, int transferredReason) {
    if (fixedAsset == null) {
      return null;
    }
    return BgRemovalAccountRules.chargeAccount(
        removalAccount(fixedAsset.getCompany(), reason),
        fixedAsset.getFixedAssetCategory(),
        transferredReason);
  }

  /**
   * Коя сметка замества избраната от базата — по реда „фирмено припокриване, после подразбиране“.
   *
   * <p><b>Стъпало 1: {@code BgAssetRemovalAccount}.</b> Ред за ТАЗИ фирма и ТАЗИ причина — изричен
   * избор на човек, печели над всичко.
   *
   * <p><b>Стъпало 2: кодът от причината, потърсен в чарта на ТАЗИ фирма.</b> Подразбирането от
   * seed-а: 442 за възстановима липса, 60908 за брак и непреодолима сила.
   *
   * <p>⚠️ Търсенето е ПО ФИРМА — {@code Account} е уникален по {@code code + company}, и търсене
   * само по код би върнало сметката на друго дружество. ⚠️ Само АКТИВНА сметка: деактивирана сметка
   * на ред от статия поваля валидирането с {@code INACTIVE_ACCOUNT_FOUND}; понеже стъпалото е
   * подразбиране, пенсионираната сметка връща поведението към базовия избор, вместо да спре цяло
   * отписване. Стъпало 1 НЕ се филтрира така: посочена на ръка сметка заслужава шумния отказ.
   *
   * <p>Връща {@code null}, когато няма нито ред, нито намерен код — тогава остава базовият избор.
   */
  public Account removalAccount(Company company, AssetDisposalReason reason) {
    if (reason == null) {
      return null;
    }
    BgAssetRemovalAccount mapping =
        bgAssetRemovalAccountRepository
            .all()
            .filter(BgRemovalAccountRules.OVERRIDE_FILTER)
            .bind("company", company)
            .bind("reason", reason)
            .fetchOne();
    Account override = mapping == null ? null : mapping.getAccount();
    return BgRemovalAccountRules.removalAccount(override, defaultAccount(company, reason));
  }

  /** Сметката по подразбиране — кодът от причината, потърсен в чарта на ТАЗИ фирма. */
  protected Account defaultAccount(Company company, AssetDisposalReason reason) {
    String code = BgRemovalAccountRules.defaultAccountCode(reason);
    if (code == null || company == null) {
      return null;
    }
    return accountRepository
        .all()
        .filter(BgRemovalAccountRules.DEFAULT_ACCOUNT_FILTER)
        .bind("code", code)
        .bind("company", company)
        .bind("statusSelect", AccountRepository.STATUS_ACTIVE)
        .fetchOne();
  }
}
