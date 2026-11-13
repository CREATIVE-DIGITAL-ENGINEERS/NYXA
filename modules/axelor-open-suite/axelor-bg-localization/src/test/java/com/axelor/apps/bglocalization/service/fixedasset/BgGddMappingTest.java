package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Съответствието величина → шифър на образец 1010, Част V.
 *
 * <p>⚠️ Шифрите идват от сверка срещу ДВ бр. 16 от 25.02.2025 г. (Заповед ЗМФ-158). Независима
 * проверка срещу ДВ и НАП не е направена — затова тестът пази ЦЕЛОСТТА на мапинга (шифър, колона,
 * основание, статус), а не истинността на самите номера.
 */
class BgGddMappingTest {

  @Test
  @DisplayName("четирите реда по амортизациите носят шифър, колона и основание")
  void четирите_реда() {
    assertEquals("7010", BgGddMapping.ART54_ACCOUNTING.code);
    assertEquals(BgGddMapping.COLUMN_INCREASE, BgGddMapping.ART54_ACCOUNTING.column);
    assertEquals("чл. 54 ал. 2 ЗКПО", BgGddMapping.ART54_ACCOUNTING.legalGround);

    assertEquals("8010", BgGddMapping.ART54_TAX.code);
    assertEquals(BgGddMapping.COLUMN_DECREASE, BgGddMapping.ART54_TAX.column);
    assertEquals("чл. 54 ал. 1 ЗКПО", BgGddMapping.ART54_TAX.legalGround);

    assertEquals("7020", BgGddMapping.ART66_INCREASE.code);
    assertEquals(BgGddMapping.COLUMN_INCREASE, BgGddMapping.ART66_INCREASE.column);
    assertEquals("чл. 66 ал. 1 ЗКПО", BgGddMapping.ART66_INCREASE.legalGround);

    assertEquals("8020", BgGddMapping.ART66_DECREASE.code);
    assertEquals(BgGddMapping.COLUMN_DECREASE, BgGddMapping.ART66_DECREASE.column);
    assertEquals("чл. 66 ал. 2 ЗКПО", BgGddMapping.ART66_DECREASE.legalGround);
  }

  @Test
  @DisplayName("⚠️ алинеите не са разменени: ал. 2 увеличава, ал. 1 намалява")
  void алинеите_не_са_разменени() {
    // Ал. 1 признава ДАНЪЧНИТЕ амортизации (намаление, 8010); ал. 2 увеличава резултата със
    // СЧЕТОВОДНИТЕ (увеличение, 7010). Разменени, знакът на цялата справка се обръща — затова
    // двойката се заключва тук, а не се оставя на прочита на коментар.
    assertTrue(BgGddMapping.ART54_ACCOUNTING.legalGround.contains("ал. 2"));
    assertEquals(BgGddMapping.COLUMN_INCREASE, BgGddMapping.ART54_ACCOUNTING.column);
    assertTrue(BgGddMapping.ART54_TAX.legalGround.contains("ал. 1"));
    assertEquals(BgGddMapping.COLUMN_DECREASE, BgGddMapping.ART54_TAX.column);
  }

  @Test
  @DisplayName("⚠️ сверени са ЧЕТИРИ реда, не целият образец")
  void скоупът_на_сверката() {
    // Предишният глобален IS_VERIFIED би трябвало да се вдигне „наведнъж" за целия образец —
    // включително за редове, за които нямаме шифър. Затова статусът е per-ред, а глобалният
    // отговор се ИЗЧИСЛЯВА и не може да се разсинхронизира с тях.
    assertTrue(BgGddMapping.ART54_ACCOUNTING.verified);
    assertTrue(BgGddMapping.ART54_TAX.verified);
    assertTrue(BgGddMapping.ART66_INCREASE.verified);
    assertTrue(BgGddMapping.ART66_DECREASE.verified);
    assertFalse(
        BgGddMapping.ART59_REVERSAL.verified,
        "чл. 59 ал. 2 няма сверен самостоятелен шифър — нетирането в 8010 е ТЪЛКУВАНЕ");
    assertFalse(
        BgGddMapping.isFullyVerified(),
        "щом остане несверен ред, глобалният отговор сам казва „не“ — не се обявява");
  }

  @Test
  @DisplayName("печатният маркер изброява ТОЧНО несверените, а не твърди нещо общо")
  void маркерът_назовава_конкретното() {
    String note = BgGddMapping.unverifiedNote();
    assertFalse(note.isEmpty(), "има несверен ред, тъй че маркерът стои");
    assertTrue(note.contains(BgGddMapping.ART59_REVERSAL.title), "назовава чл. 59 ал. 2 поименно");
    assertFalse(
        note.contains("7010") || note.contains("7020") || note.contains("8020"),
        "⚠️ и НЕ споменава сверените редове — предупреждение, което стои и след сверката, спира да"
            + " се чете");
    assertTrue(note.contains("ДВ бр. 16"), "казва срещу какво са сверени останалите");
  }

  @Test
  @DisplayName("надписът на реда се чете: шифър, колона, заглавие")
  void надписът_на_реда() {
    assertEquals(
        "шифър 7020, колона А — Счетоводна балансова стойност на отписаните активи от счетоводния"
            + " амортизационен план",
        BgGddMapping.ART66_INCREASE.label());
    assertTrue(
        BgGddMapping.ART59_REVERSAL.label().startsWith("(без самостоятелен шифър)"),
        "ред без шифър го казва, вместо да покаже празен шифър");
  }

  @Test
  @DisplayName("източникът е изведен веднъж и носи брой, дата и заповед")
  void източникът() {
    for (String part :
        new String[] {"1010", "ДВ бр. 16", "25.02.2025", "ЗМФ-158", "ДВ бр. 22", "24.02.2026"}) {
      assertTrue(BgGddMapping.SOURCE.contains(part), "източникът трябва да носи " + part);
    }
  }
}
