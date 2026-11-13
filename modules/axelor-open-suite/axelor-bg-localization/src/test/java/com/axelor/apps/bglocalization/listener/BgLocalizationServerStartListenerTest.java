package com.axelor.apps.bglocalization.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Localization;
import com.axelor.apps.bglocalization.db.BgFixedAssetRealizationSettings;
import com.axelor.apps.bglocalization.service.batch.BgFixedAssetRealizationSettingsService;
import com.axelor.studio.db.AppBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link
 * BgLocalizationServerStartListener#ensureFixedAssetRealizationSettingsAndSchedule}: every startup
 * must find-or-create the settings row and sync the derived MetaSchedule from it — never skipped on
 * a later boot, since the settings row (not the MetaSchedule row) is the sole source of truth (see
 * {@link BgFixedAssetRealizationSettingsService}). No mocking framework is used (none is on this
 * module's test classpath); the service is faked by subclassing and overriding its public seams,
 * avoiding real JPA.
 */
class BgLocalizationServerStartListenerTest {

  private static class FakeSettingsService extends BgFixedAssetRealizationSettingsService {
    final BgFixedAssetRealizationSettings settings = new BgFixedAssetRealizationSettings();
    int findOrCreateCalls;
    int syncCalls;
    BgFixedAssetRealizationSettings lastSynced;

    FakeSettingsService() {
      super(null, null, null);
    }

    @Override
    public BgFixedAssetRealizationSettings findOrCreate() {
      findOrCreateCalls++;
      return settings;
    }

    @Override
    public void syncSchedule(BgFixedAssetRealizationSettings settings) {
      syncCalls++;
      lastSynced = settings;
    }
  }

  @Test
  void everyStartupSyncsTheSameSettingsRowIntoTheSchedule() {
    FakeSettingsService service = new FakeSettingsService();
    BgLocalizationServerStartListener listener = new BgLocalizationServerStartListener();

    // Boot 1: first time — settings row created, schedule synced from it.
    listener.ensureFixedAssetRealizationSettingsAndSchedule(service);
    assertEquals(1, service.findOrCreateCalls);
    assertEquals(1, service.syncCalls);
    assertSame(service.settings, service.lastSynced);

    // Boot 2 (a later rebuild/restart): still exactly the one row, re-synced (not skipped) so an
    // out-of-band MetaSchedule edit made through generic Job Management is corrected back.
    listener.ensureFixedAssetRealizationSettingsAndSchedule(service);
    assertEquals(2, service.findOrCreateCalls);
    assertEquals(2, service.syncCalls);
    assertSame(service.settings, service.lastSynced);
  }

  /**
   * This optional schedule sync must never take down the rest of server startup — a failure (e.g.
   * an oversized description) is logged and swallowed here, unlike the interactive settings save
   * which still lets such failures reach the admin normally.
   */
  // ---------------------------------------------------------- default partner localization

  private static Localization bulgarian() {
    Country bulgaria = new Country();
    bulgaria.setAlpha2Code("BG");
    Localization localization = new Localization();
    localization.setName("Български (България)");
    localization.setCountry(bulgaria);
    return localization;
  }

  @Test
  @DisplayName(
      "⚠️ Празната настройка се запълва с България — иначе новият партньор тръгва генеричен")
  void emptyDefaultPartnerLocalizationIsFilled() {
    BgLocalizationServerStartListener listener = new BgLocalizationServerStartListener();
    AppBase appBase = new AppBase();
    Localization bg = bulgarian();

    assertTrue(listener.applyDefaultPartnerLocalization(appBase, bg));
    assertSame(bg, appBase.getDefaultPartnerLocalization());
  }

  @Test
  @DisplayName("⚠️ Вече зададена стойност НЕ се пипа — листенерът върви на всеки boot")
  void anExistingChoiceIsNeverOverwritten() {
    BgLocalizationServerStartListener listener = new BgLocalizationServerStartListener();
    Localization french = new Localization();
    french.setName("French (FRANCE)");
    AppBase appBase = new AppBase();
    appBase.setDefaultPartnerLocalization(french);

    // Безусловното задаване щеше да връща избора на администратора при ВСЕКИ деплой, мълчаливо.
    assertFalse(listener.applyDefaultPartnerLocalization(appBase, bulgarian()));
    assertSame(french, appBase.getDefaultPartnerLocalization());
  }

  @Test
  @DisplayName("⚠️ Втори boot не променя нищо — стойността вече е сложена от първия")
  void theSecondBootIsANoOp() {
    BgLocalizationServerStartListener listener = new BgLocalizationServerStartListener();
    AppBase appBase = new AppBase();
    Localization bg = bulgarian();

    assertTrue(listener.applyDefaultPartnerLocalization(appBase, bg));
    assertFalse(listener.applyDefaultPartnerLocalization(appBase, bg));
    assertSame(bg, appBase.getDefaultPartnerLocalization());
  }

  @Test
  @DisplayName("⚠️ Липсващ ред за bg_BG или липсващ AppBase не създава нищо")
  void missingRowsCreateNothing() {
    BgLocalizationServerStartListener listener = new BgLocalizationServerStartListener();
    AppBase appBase = new AppBase();

    // Редът за bg_BG идва от кореновия data-init на axelor-base и може да го няма. Тогава
    // настройката остава празна — НЕ се създава локализация и НЕ се създава втори AppBase.
    assertFalse(listener.applyDefaultPartnerLocalization(appBase, null));
    assertNull(appBase.getDefaultPartnerLocalization());

    // И обратното: няма ли AppBase изобщо, не се ражда такъв. Вторият ред би бил невидим дефект —
    // AppBaseService#getAppBase() е fetchOne() и оттам нататък коя настройка важи е лотария.
    assertFalse(listener.applyDefaultPartnerLocalization(null, bulgarian()));
  }

  @Test
  void aSyncFailureIsSwallowedAndNeverPropagates() {
    FakeSettingsService service =
        new FakeSettingsService() {
          @Override
          public void syncSchedule(BgFixedAssetRealizationSettings settings) {
            super.syncSchedule(settings);
            throw new RuntimeException("simulated startup sync failure");
          }
        };
    BgLocalizationServerStartListener listener = new BgLocalizationServerStartListener();

    listener.ensureFixedAssetRealizationSettingsAndSchedule(service); // must not throw

    assertEquals(1, service.findOrCreateCalls);
    assertEquals(1, service.syncCalls);
  }
}
