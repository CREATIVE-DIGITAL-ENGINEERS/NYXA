package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Прагът за данъчен амортизируем актив по чл. 50 и чл. 51 ЗКПО. */
class BgAssetThresholdRulesTest {

  /** Законовата сума: 700 лв → 357,90 EUR (виж {@code BgCurrencyConversionTest}). */
  private static final BigDecimal ЗАКОНОВА = new BigDecimal("357.90");

  @Test
  @DisplayName("⚠️ решава ПО-НИСКАТА — по-висок праг по политика НЕ вдига летвата")
  void решаващият_праг_е_минимумът() {
    // Чл. 50 ал. 1: „чиято стойност е равна или превишава ПО-НИСКАТА стойност от“. Тоест политика с
    // праг 1 000 EUR не изважда от ДАП актива за 400 EUR — законовите 357,90 остават решаващи.
    assertEquals(
        ЗАКОНОВА,
        BgAssetThresholdRules.decisiveThreshold(new BigDecimal("1000.00"), ЗАКОНОВА),
        "по-високата политика се подминава — минимумът е законовата сума");
    assertEquals(
        new BigDecimal("200.00"),
        BgAssetThresholdRules.decisiveThreshold(new BigDecimal("200.00"), ЗАКОНОВА),
        "по-ниската политика решава");
    assertFalse(
        BgAssetThresholdRules.isBelowThreshold(new BigDecimal("400.00"), ЗАКОНОВА),
        "актив за 400 EUR превишава 357,90 и Е данъчен амортизируем актив");
  }

  @Test
  @DisplayName("незададен източник се подминава, а не смъква прага до нула")
  void празният_праг_не_е_нулев() {
    assertEquals(
        ЗАКОНОВА,
        BgAssetThresholdRules.decisiveThreshold(null, ЗАКОНОВА),
        "политика без обявен праг значи „решава само законовата сума“");
    assertEquals(
        ЗАКОНОВА,
        BgAssetThresholdRules.decisiveThreshold(BigDecimal.ZERO, ЗАКОНОВА),
        "нулата е същото като липса — генерираният getter връща ZERO и за незададена колона");
    assertNull(
        BgAssetThresholdRules.decisiveThreshold(null, null),
        "без нито един източник гейт НЕ се прилага — липсата на конфигурация не е основание активът"
            + " да излезе от плана");
    assertFalse(
        BgAssetThresholdRules.isBelowThreshold(new BigDecimal("10.00"), null),
        "и затова липсващият праг не изхвърля даже актив за 10 EUR");
  }

  @Test
  @DisplayName("⚠️ границата е ВКЛЮЧВАЩА в полза на актива — „равна или превишава“")
  void границата_включва() {
    assertFalse(
        BgAssetThresholdRules.isBelowThreshold(new BigDecimal("357.90"), ЗАКОНОВА),
        "актив ТОЧНО на прага Е данъчен амортизируем актив — текстът е „равна ИЛИ превишава“");
    assertTrue(
        BgAssetThresholdRules.isBelowThreshold(new BigDecimal("357.89"), ЗАКОНОВА),
        "една стотинка под прага вече е извън ДАП");
  }

  @Test
  @DisplayName("чл. 60 ал. 3 т. 2: само УВЕЛИЧЕНИЕ поражда основание")
  void само_увеличението_се_брои() {
    // Тригерът е „в резултат на УВЕЛИЧЕНИЕ в стойностния праг на същественост“. Свален праг не
    // поражда отписване — той по-скоро ВКАРВА активи в обхвата на чл. 50/51.
    assertTrue(BgAssetThresholdRules.isIncrease(new BigDecimal("300"), new BigDecimal("500")));
    assertFalse(
        BgAssetThresholdRules.isIncrease(new BigDecimal("500"), new BigDecimal("300")),
        "свален праг не е увеличение");
    assertFalse(
        BgAssetThresholdRules.isIncrease(new BigDecimal("500"), new BigDecimal("500")),
        "непроменен праг също не е");
    assertFalse(
        BgAssetThresholdRules.isIncrease(null, null), "липсващи стойности не са увеличение");
  }

  @Test
  @DisplayName("вдигането хваща актив под НОВИЯ праг, но не и при сваляне")
  void кой_е_засегнат_от_вдигането() {
    BigDecimal стар = new BigDecimal("357.90");
    BigDecimal нов = new BigDecimal("1000.00");
    assertTrue(
        BgAssetThresholdRules.isCaughtByIncrease(new BigDecimal("800.00"), стар, нов),
        "актив за 800 EUR е бил над стария праг и пада под новия — точно случаят на т. 2");
    assertFalse(
        BgAssetThresholdRules.isCaughtByIncrease(new BigDecimal("1200.00"), стар, нов),
        "актив над новия праг остава непокътнат");
    assertFalse(
        BgAssetThresholdRules.isCaughtByIncrease(new BigDecimal("800.00"), нов, стар),
        "при СВАЛЯНЕ на прага т. 2 не важи, макар стойността да е под старото число");
  }

  @Test
  @DisplayName("⚠️ свалянето на FISCAL не разкъсва останалите планове")
  void планът_се_разцепва_не_се_реже_текстово() {
    // depreciationPlanSelect е низ от единични цифри, съединени със запетая. Текстова замяна върху
    // „1,2“ оставя „,2“, а върху „2,1“ — „2,“; и двете минават мълчаливо през contains(...) и се
    // появяват като повреден избор в изгледа.
    assertEquals("2", BgAssetThresholdRules.withoutFiscalPlan("1,2"));
    assertEquals("2", BgAssetThresholdRules.withoutFiscalPlan("2,1"));
    assertEquals("2,4", BgAssetThresholdRules.withoutFiscalPlan("1,2,4"));
    assertEquals("2,3,4", BgAssetThresholdRules.withoutFiscalPlan("2,3,4"));
    assertEquals(
        "0",
        BgAssetThresholdRules.withoutFiscalPlan("1"),
        "останал без нито един план, активът получава NONE, а не празен низ");
    assertNull(BgAssetThresholdRules.withoutFiscalPlan(null));
  }
}
