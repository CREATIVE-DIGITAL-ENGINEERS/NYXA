package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.AssetKind;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.CreditType;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.Input;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.Result;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Корекция по чл. 79 ал. 3 ЗДДС — еталоните.
 *
 * <p>Числата НЕ са преписани отнякъде: всяко е изведено от самата формула, а коментарът над него
 * показва извеждането. Чиста функция, тъй че без Guice и без база.
 */
class BgArt79CorrectionRulesTest {

  private static BigDecimal bg(String value) {
    return new BigDecimal(value);
  }

  private static Input movable(
      String vatCharged, CreditType creditType, int creditYear, int eventYear, String k0) {
    return new Input(
        AssetKind.GOODS,
        creditType,
        bg(vatCharged),
        creditYear,
        null,
        eventYear,
        k0 == null ? null : bg(k0),
        null);
  }

  // ------------------------------------------------------------------ еталон 1

  @Test
  @DisplayName("Еталон 1 — движима вещ, пълен кредит: 2000 × 1/5 × 3 = 1200")
  void етал1_движима_пълен() {
    // Кредит 2026 → 5-годишният срок обхваща 2026–2030. Брак 2028 → БГ брои 2028, 2029, 2030 = 3.
    Result r =
        BgArt79CorrectionRules.correction(movable("2000", CreditType.FULL, 2026, 2028, null));

    assertEquals(3, r.years(), "БГ = 2026 + 5 − 2028");
    assertEquals(5, r.periodYears(), "движима вещ → т. 2, знаменател 5");
    assertEquals(0, bg("1200.00").compareTo(r.amount()), "2000 × 1/5 × 3");
    assertEquals("чл. 79 ал. 3 т. 2 б. „а“", r.legalGround());
    assertNull(r.timeBarGround(), "срокът тече — няма основание по чл. 80 ал. 1");
    assertTrue(r.isDue());
  }

  // ------------------------------------------------------------------ еталон 2

  @Test
  @DisplayName("Еталон 2 — недвижим имот, пълен кредит: 20000 × 1/20 × 16 = 16000")
  void етал2_недвижим_пълен() {
    // Кредит 2026 → 20-годишният срок обхваща 2026–2045. Събитие 2030 → БГ = 2030…2045 = 16.
    Result r =
        BgArt79CorrectionRules.correction(
            new Input(
                AssetKind.IMMOVABLE_PROPERTY,
                CreditType.FULL,
                bg("20000"),
                2026,
                null,
                2030,
                null,
                null));

    assertEquals(16, r.years(), "БГ = 2026 + 20 − 2030");
    assertEquals(20, r.periodYears(), "недвижим имот → т. 1, знаменател 20");
    assertEquals(0, bg("16000.00").compareTo(r.amount()), "20000 × 1/20 × 16");
    assertEquals("чл. 79 ал. 3 т. 1 б. „а“", r.legalGround());
  }

  // ------------------------------------------------------------------ еталон 3

  @Test
  @DisplayName("Еталон 3 — движима, частичен К₀=0.60: 2000 × 1/5 × 0.60 × 3 = 720")
  void етал3_движима_частичен() {
    Result r =
        BgArt79CorrectionRules.correction(movable("2000", CreditType.PARTIAL, 2026, 2028, "0.60"));

    assertEquals(3, r.years());
    assertEquals(0, bg("720.00").compareTo(r.amount()), "2000 × 1/5 × 0.60 × 3");
    // Контрола по друг път: приспаднати са 2000 × 0.60 = 1200, от които се връщат 3/5 = 720.
    assertEquals(
        0,
        bg("1200").multiply(bg("3")).divide(bg("5")).compareTo(r.amount()),
        "= ползваният кредит 1200 × 3/5");
    assertEquals("чл. 79 ал. 3 т. 2 б. „б“", r.legalGround());
    assertEquals(0, bg("0.60").compareTo(r.appliedK0()));
  }

  // ------------------------------------------------------------------ еталон 4

  @Test
  @DisplayName("Еталон 4 — ДНА-услуга (т. 3), пълен кредит: 1000 × 1/5 × 3 = 600")
  void етал4_услуга_пълен() {
    Result r =
        BgArt79CorrectionRules.correction(
            new Input(
                AssetKind.SERVICE, CreditType.FULL, bg("1000"), 2026, null, 2028, null, null));

    assertEquals(3, r.years());
    assertEquals(5, r.periodYears(), "услугите са в т. 3 — също 5-годишен срок");
    assertEquals(0, bg("600.00").compareTo(r.amount()), "1000 × 1/5 × 3");
    assertEquals("чл. 79 ал. 3 т. 3 б. „а“", r.legalGround());
  }

  @Test
  @DisplayName("Т. 3 няма буква „в“ — пропорционална услуга се отказва, не се смята")
  void услуга_пропорционален_се_отказва() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                BgArt79CorrectionRules.correction(
                    new Input(
                        AssetKind.SERVICE,
                        CreditType.PROPORTIONAL,
                        bg("1000"),
                        2026,
                        null,
                        2028,
                        bg("0.60"),
                        bg("0.50"))));
    assertTrue(e.getMessage().contains("т. 3"), e.getMessage());
  }

  // ------------------------------------------------------------------ буква „в“

  @Test
  @DisplayName("Буква „в“ — движима, ПрНИД₀=0.50 и К₀=0.60: 2000 × 1/5 × 0.50 × 0.60 × 3 = 360")
  void буква_в_пропорционален() {
    Result r =
        BgArt79CorrectionRules.correction(
            new Input(
                AssetKind.GOODS,
                CreditType.PROPORTIONAL,
                bg("2000"),
                2026,
                null,
                2028,
                bg("0.60"),
                bg("0.50")));

    assertEquals(0, bg("360.00").compareTo(r.amount()), "2000 × 1/5 × 0.50 × 0.60 × 3");
    assertEquals("чл. 79 ал. 3 т. 2 б. „в“", r.legalGround());
    assertEquals(0, bg("0.50").compareTo(r.appliedProportion()));
  }

  // ------------------------------------------------------------------ еталон 6 (чл. 79в)

  @Test
  @DisplayName("Еталон 6 — чл. 79в т. 4: К₀ = 0 при частичен кредит се отказва")
  void етал6_к0_нула_при_частичен() {
    IllegalArgumentException e =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                BgArt79CorrectionRules.correction(
                    movable("2000", CreditType.PARTIAL, 2026, 2028, "0")));
    assertTrue(e.getMessage().contains("79в т. 4"), e.getMessage());

    // И липсващият К₀ е същият отказ — „не е въведено“ не бива да се чете като 0.
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BgArt79CorrectionRules.correction(
                movable("2000", CreditType.PARTIAL, 2026, 2028, null)));
  }

  @Test
  @DisplayName("Еталон 6 — чл. 79в т. 8: ПрНИД₀ не може да е 0 или 1")
  void етал6_прнид_нула_или_едно() {
    for (String invalid : new String[] {"0", "1", "1.00"}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              BgArt79CorrectionRules.correction(
                  new Input(
                      AssetKind.GOODS,
                      CreditType.PROPORTIONAL,
                      bg("2000"),
                      2026,
                      null,
                      2028,
                      bg("0.60"),
                      bg(invalid))),
          "ПрНИД₀ = " + invalid + " е изключен от чл. 79в т. 8");
    }
  }

  @Test
  @DisplayName("Еталон 6 — чл. 79в т. 3: при пълен кредит К₀ е 1 и коеф. по чл. 73 не се чете")
  void етал6_пълен_кредит_к0_едно() {
    // Подаден коефициент 0.60 при ПЪЛЕН кредит НЕ бива да свали данъка: законът го игнорира.
    Result r =
        BgArt79CorrectionRules.correction(movable("2000", CreditType.FULL, 2026, 2028, "0.60"));

    assertEquals(0, BigDecimal.ONE.compareTo(r.appliedK0()), "чл. 79в т. 3 → К₀ = 1");
    assertEquals(0, bg("1200.00").compareTo(r.amount()), "подаденият 0.60 се пренебрегва");
  }

  // ------------------------------------------------------------------ еталон 7 (давност)

  @Test
  @DisplayName("Еталон 7 — чл. 80 ал. 1 т. 4: движима вещ, брак 2032 след срок до 2030 → 0")
  void етал7_давност_движима() {
    // Кредит 2026, срок 2026–2030. Брак 2032 → 2026 + 5 − 2032 = −1 → БГ = 0.
    Result r =
        BgArt79CorrectionRules.correction(movable("2000", CreditType.FULL, 2026, 2032, null));

    assertEquals(0, r.years(), "срокът е изтекъл");
    assertEquals(0, BigDecimal.ZERO.compareTo(r.amount()), "корекция не се извършва");
    assertEquals(BgAssetRemoval.VatGround.ART80_1_4, r.timeBarGround());
    assertFalse(r.isDue());
  }

  @Test
  @DisplayName("Еталон 7 — чл. 80 ал. 1 т. 3: недвижим имот, брак 2047 след срок до 2045 → 0")
  void етал7_давност_недвижим() {
    Result r =
        BgArt79CorrectionRules.correction(
            new Input(
                AssetKind.IMMOVABLE_PROPERTY,
                CreditType.FULL,
                bg("20000"),
                2026,
                null,
                2047,
                null,
                null));

    assertEquals(0, r.years());
    assertEquals(0, BigDecimal.ZERO.compareTo(r.amount()));
    assertEquals(BgAssetRemoval.VatGround.ART80_1_3, r.timeBarGround());
  }

  @Test
  @DisplayName("Последната година от срока още дължи — 2030 при срок 2026–2030 дава БГ = 1")
  void последната_година_още_дължи() {
    Result r =
        BgArt79CorrectionRules.correction(movable("2000", CreditType.FULL, 2026, 2030, null));

    assertEquals(1, r.years(), "БГ включва и последната година на срока");
    assertEquals(0, bg("400.00").compareTo(r.amount()), "2000 × 1/5 × 1");
  }

  // ------------------------------------------------------------------ еталон 8

  @Test
  @DisplayName("Еталон 8 — без ползван данъчен кредит (чл. 70) → няма какво да се връща")
  void етал8_без_кредит() {
    Result r =
        BgArt79CorrectionRules.correction(movable("2000", CreditType.NONE, 2026, 2028, null));

    assertEquals(0, BigDecimal.ZERO.compareTo(r.amount()), "чл. 79в т. 5: К₀ = 0");
    assertEquals(0, BigDecimal.ZERO.compareTo(r.appliedK0()));
    assertFalse(r.isDue());
  }

  // ------------------------------------------------------------- алтернативното начало (т. 1)

  @Test
  @DisplayName("Т. 1 — имот, неизползван повече от година, брои от годината на фактическо ползване")
  void недвижим_алтернативно_начало() {
    // Кредит 2026, фактическо ползване 2029 (> 2026 + 1) → срокът е 2029–2048, не 2026–2045.
    Result r =
        BgArt79CorrectionRules.correction(
            new Input(
                AssetKind.IMMOVABLE_PROPERTY,
                CreditType.FULL,
                bg("20000"),
                2026,
                2029,
                2030,
                null,
                null));

    assertEquals(2029, r.periodStartYear(), "началото се мести на годината на фактическо ползване");
    assertEquals(19, r.years(), "БГ = 2029 + 20 − 2030");

    // Ползване още през 2027 (една година след кредита) НЕ мести началото.
    Result near =
        BgArt79CorrectionRules.correction(
            new Input(
                AssetKind.IMMOVABLE_PROPERTY,
                CreditType.FULL,
                bg("20000"),
                2026,
                2027,
                2030,
                null,
                null));
    assertEquals(2026, near.periodStartYear(), "„повече от една година“ — 2027 не стига");
    assertEquals(16, near.years());
  }

  @Test
  @DisplayName("Алтернативното начало е САМО за т. 1 — движима вещ го пренебрегва")
  void движима_пренебрегва_фактическото_ползване() {
    Result r =
        BgArt79CorrectionRules.correction(
            new Input(AssetKind.GOODS, CreditType.FULL, bg("2000"), 2026, 2029, 2028, null, null));

    assertEquals(2026, r.periodStartYear(), "т. 2 няма алтернативно начало в текста си");
    assertEquals(3, r.years());
  }

  // ------------------------------------------------------------------ еталон 5 (ал. 5)

  @Test
  @DisplayName("Еталон 5 — обратната посока: 2000 × 1/5 × (1 − 0.60) × 3 = 480")
  void етал5_приспадане_движима_частичен() {
    // Активът е придобит с частичен кредит К₀ = 0,60 (приспаднати 1200 от 2000) и се ПРОДАВА с
    // облагаема доставка през 2028. Неползваната част е 800; от нея се възстановяват 3/5 = 480.
    Result r =
        BgArt79CorrectionRules.deduction(movable("2000", CreditType.PARTIAL, 2026, 2028, "0.60"));

    assertEquals(3, r.years(), "БГ се брои както при ал. 3");
    assertEquals(0, bg("480.00").compareTo(r.amount()), "2000 × 1/5 × 0.40 × 3");
    // Контрола по друг път: неползваните 800 × 3/5.
    assertEquals(
        0,
        bg("800").multiply(bg("3")).divide(bg("5")).compareTo(r.amount()),
        "= неползваният кредит 800 × 3/5");
    assertEquals("чл. 79 ал. 5 т. 2 б. „а“", r.legalGround(), "при ал. 5 частичният е буква „а“");
  }

  @Test
  @DisplayName("Ал. 5 няма вариант за пълен кредит — приспаднал изцяло няма неползвана част")
  void приспадане_при_пълен_кредит_е_нула() {
    Result r = BgArt79CorrectionRules.deduction(movable("2000", CreditType.FULL, 2026, 2028, null));

    assertEquals(0, BigDecimal.ZERO.compareTo(r.amount()));
    assertFalse(r.isDue());
  }

  @Test
  @DisplayName("Буква „б“ по ал. 5 — (1 − ПрНИД₀ × К₀): 2000 × 1/5 × (1 − 0.30) × 3 = 840")
  void приспадане_пропорционален() {
    // ПрНИД₀ = 0,50 и К₀ = 0,60 → ползвано 0,30, неползвано 0,70.
    Result r =
        BgArt79CorrectionRules.deduction(
            new Input(
                AssetKind.GOODS,
                CreditType.PROPORTIONAL,
                bg("2000"),
                2026,
                null,
                2028,
                bg("0.60"),
                bg("0.50")));

    assertEquals(0, bg("840.00").compareTo(r.amount()), "2000 × 1/5 × 0.70 × 3");
    assertEquals("чл. 79 ал. 5 т. 2 б. „б“", r.legalGround());
  }

  @Test
  @DisplayName("Давността по чл. 80 ал. 1 важи и за приспадането — изтекъл срок дава 0")
  void приспадане_след_изтекъл_срок() {
    Result r =
        BgArt79CorrectionRules.deduction(movable("2000", CreditType.PARTIAL, 2026, 2032, "0.60"));

    assertEquals(0, r.years());
    assertEquals(0, BigDecimal.ZERO.compareTo(r.amount()));
    assertEquals(BgAssetRemoval.VatGround.ART80_1_4, r.timeBarGround());
  }

  @Test
  @DisplayName("Ал. 5 т. 3 (услуги) е без букви — цитатът не бива да преписва буквата от ал. 3")
  void приспадане_услуга_без_букви() {
    Result r =
        BgArt79CorrectionRules.deduction(
            new Input(
                AssetKind.SERVICE,
                CreditType.PARTIAL,
                bg("1000"),
                2026,
                null,
                2028,
                bg("0.60"),
                null));

    assertEquals("чл. 79 ал. 5 т. 3", r.legalGround());
    assertEquals(0, bg("240.00").compareTo(r.amount()), "1000 × 1/5 × 0.40 × 3");
  }
}
