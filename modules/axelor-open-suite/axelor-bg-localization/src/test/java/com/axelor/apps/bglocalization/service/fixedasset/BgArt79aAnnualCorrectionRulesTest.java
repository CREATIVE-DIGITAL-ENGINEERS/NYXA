package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.AssetKind;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.CreditType;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.Input;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.NidUse;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.Result;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.TaxableUse;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Годишната корекция по чл. 79а/79б ЗДДС — еталоните.
 *
 * <p>Числата НЕ са преписани отнякъде: всяко е изведено от самата формула, а коментарът над него
 * показва извеждането. Чиста функция, тъй че без Guice и без база.
 */
class BgArt79aAnnualCorrectionRulesTest {

  private static BigDecimal bg(String value) {
    return new BigDecimal(value);
  }

  /** Буква „б“/„в“ — формулата не чете ПрНИД, тъй че NidUse е без значение. */
  private static Input mixed(
      AssetKind kind, CreditType creditType, String vatCharged, String k0, String kx) {
    return new Input(
        kind,
        creditType,
        bg(vatCharged),
        2028,
        k0 == null ? null : bg(k0),
        null,
        TaxableUse.MIXED,
        bg(kx),
        null,
        null,
        false,
        false,
        false);
  }

  // ───────────────────────────────────────────────── чл. 79а ал. 3 т. 1 (недвижим имот, 1/20)

  @Test
  @DisplayName("чл. 79а ал. 3 т. 1 б. „в“ — частичен кредит, Кх се вдига: 1/20 × (Кх − К₀)")
  void immovablePartial_increase() {
    // ИРПДК = 20000 × 1/20 × (0,80 − 0,60) = 20000 × 0,20 / 20 = 4000 / 20 = 200,00
    Result result =
        BgArt79aAnnualCorrectionRules.correction(
            mixed(AssetKind.IMMOVABLE_PROPERTY, CreditType.PARTIAL, "20000", "0.60", "0.80"));

    assertEquals(0, bg("200.00").compareTo(result.amount()));
    assertEquals(20, result.periodYears());
    assertEquals("чл. 79а ал. 3 т. 1 б. „в“", result.legalGround());
    assertTrue(result.isDue());
  }

  @Test
  @DisplayName("чл. 79а ал. 6 — увеличението МОЖЕ да се пропусне, намалението НЕ може")
  void onlyIncreaseCanBeSkipped() {
    Result increase =
        BgArt79aAnnualCorrectionRules.correction(
            mixed(AssetKind.IMMOVABLE_PROPERTY, CreditType.PARTIAL, "20000", "0.60", "0.80"));
    Result decrease =
        BgArt79aAnnualCorrectionRules.correction(
            mixed(AssetKind.IMMOVABLE_PROPERTY, CreditType.FULL, "20000", null, "0.70"));

    assertTrue(increase.canBeSkipped());
    assertFalse(decrease.canBeSkipped());
  }

  @Test
  @DisplayName("чл. 79а ал. 3 т. 1 б. „б“ — изцяло приспаднат: 1/20 × (Кх − 1) е ОТРИЦАТЕЛНО")
  void immovableFull_decrease() {
    // ИРПДК = 20000 × 1/20 × (0,70 − 1) = 20000 × (−0,30) / 20 = −6000 / 20 = −300,00
    Result result =
        BgArt79aAnnualCorrectionRules.correction(
            mixed(AssetKind.IMMOVABLE_PROPERTY, CreditType.FULL, "20000", null, "0.70"));

    assertEquals(0, bg("-300.00").compareTo(result.amount()));
    assertEquals("чл. 79а ал. 3 т. 1 б. „б“", result.legalGround());
    // Чл. 79в т. 3 — К₀ е 1 по право, независимо че поле не е подадено.
    assertEquals(0, BigDecimal.ONE.compareTo(result.appliedK0()));
  }

  @Test
  @DisplayName("чл. 79а ал. 3 т. 1 б. „а“ — без кредит: ПрНИДх × Кх, БЕЗ изваждане, винаги ≥ 0")
  void immovableNone_alwaysIncrease() {
    // ИРПДК = 20000 × 1/20 × 1 × 0,80 = 16000 / 20 = 800,00
    Input in =
        new Input(
            AssetKind.IMMOVABLE_PROPERTY,
            CreditType.NONE,
            bg("20000"),
            2028,
            null,
            null,
            TaxableUse.MIXED,
            bg("0.80"),
            NidUse.ONLY_NID,
            null,
            false,
            false,
            false);

    Result result = BgArt79aAnnualCorrectionRules.correction(in);

    assertEquals(0, bg("800.00").compareTo(result.amount()));
    assertEquals("чл. 79а ал. 3 т. 1 б. „а“", result.legalGround());
    assertTrue(result.amount().signum() >= 0);
    // Чл. 79в т. 5 — К₀ е 0; т. 6 — ПрНИДх е 1.
    assertEquals(0, BigDecimal.ZERO.compareTo(result.appliedK0()));
    assertEquals(0, BigDecimal.ONE.compareTo(result.appliedProportionX()));
  }

  // ───────────────────────────────────────────────── чл. 79а ал. 3 т. 2 (останали стоки, 1/5)

  @Test
  @DisplayName("чл. 79а ал. 3 т. 2 б. „г“ — пропорционален: 1/5 × (ПрНИДх × Кх − ПрНИД₀ × К₀)")
  void goodsProportional() {
    // ИРПДК = 10000 × 1/5 × (0,80 × 0,75 − 0,50 × 0,60)
    //       = 10000 × (0,60 − 0,30) / 5 = 3000 / 5 = 600,00
    Input in =
        new Input(
            AssetKind.GOODS,
            CreditType.PROPORTIONAL,
            bg("10000"),
            2028,
            bg("0.60"),
            bg("0.50"),
            TaxableUse.MIXED,
            bg("0.75"),
            NidUse.MIXED,
            bg("0.80"),
            false,
            false,
            false);

    Result result = BgArt79aAnnualCorrectionRules.correction(in);

    assertEquals(0, bg("600.00").compareTo(result.amount()));
    assertEquals(5, result.periodYears());
    assertEquals("чл. 79а ал. 3 т. 2 б. „г“", result.legalGround());
  }

  @Test
  @DisplayName("чл. 79а ал. 3 т. 2 — знаменателят е 5, не 20: същото дава ЧЕТИРИ пъти повече")
  void goodsUsesFifth() {
    Result immovable =
        BgArt79aAnnualCorrectionRules.correction(
            mixed(AssetKind.IMMOVABLE_PROPERTY, CreditType.PARTIAL, "20000", "0.60", "0.80"));
    Result goods =
        BgArt79aAnnualCorrectionRules.correction(
            mixed(AssetKind.GOODS, CreditType.PARTIAL, "20000", "0.60", "0.80"));

    assertEquals(0, bg("200.00").compareTo(immovable.amount()));
    assertEquals(0, bg("800.00").compareTo(goods.amount()));
  }

  // ───────────────────────────────────────────────── чл. 79б ал. 3 (услуги, 1/5, три точки)

  @Test
  @DisplayName("чл. 79б ал. 3 т. 3 — услуга, частичен: 1/5 × (Кх − К₀), БЕЗ ПрНИД")
  void servicePartial() {
    // ИРПДК = 5000 × 1/5 × (0,90 − 0,40) = 5000 × 0,50 / 5 = 2500 / 5 = 500,00
    Result result =
        BgArt79aAnnualCorrectionRules.correction(
            mixed(AssetKind.SERVICE, CreditType.PARTIAL, "5000", "0.40", "0.90"));

    assertEquals(0, bg("500.00").compareTo(result.amount()));
    assertEquals(5, result.periodYears());
    assertEquals("чл. 79б ал. 3 т. 3", result.legalGround());
    // Услугата няма пропорционален режим — ПрНИД изобщо не влиза във формулата.
    assertNull(result.appliedProportionX());
  }

  @Test
  @DisplayName("чл. 79б ал. 3 т. 1 — услуга без кредит: САМО Кх, за разлика от чл. 79а б. „а“")
  void serviceNone_hasNoProportion() {
    // ИРПДК = 5000 × 1/5 × 0,90 = 4500 / 5 = 900,00 — без ПрНИДх множител.
    Input in =
        new Input(
            AssetKind.SERVICE,
            CreditType.NONE,
            bg("5000"),
            2028,
            null,
            null,
            TaxableUse.MIXED,
            bg("0.90"),
            null,
            null,
            false,
            false,
            false);

    Result result = BgArt79aAnnualCorrectionRules.correction(in);

    assertEquals(0, bg("900.00").compareTo(result.amount()));
    assertEquals("чл. 79б ал. 3 т. 1", result.legalGround());
    assertNull(result.appliedProportionX());
  }

  @Test
  @DisplayName("чл. 79б ал. 3 НЯМА пропорционален вариант — комбинацията се отказва")
  void serviceProportional_refused() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                BgArt79aAnnualCorrectionRules.correction(
                    mixed(AssetKind.SERVICE, CreditType.PROPORTIONAL, "5000", "0.40", "0.90")));

    assertTrue(error.getMessage().contains("79б ал. 3"));
  }

  // ───────────────────────────────────────────────── чл. 79в — клампите на Кх и ПрНИДх

  @Test
  @DisplayName("чл. 79в т. 1 — само облагаеми: Кх е 1, коефициентът по чл. 73 не се чете")
  void art79v_onlyWithCredit_kxIsOne() {
    Input in =
        new Input(
            AssetKind.GOODS,
            CreditType.FULL,
            bg("10000"),
            2028,
            null,
            null,
            TaxableUse.ONLY_WITH_CREDIT,
            null,
            null,
            null,
            false,
            false,
            false);

    Result result = BgArt79aAnnualCorrectionRules.correction(in);

    // (Кх − 1) = (1 − 1) = 0 — използването не се е изменило, корекция не се дължи.
    assertEquals(0, BigDecimal.ONE.compareTo(result.appliedKx()));
    assertEquals(0, BigDecimal.ZERO.compareTo(result.amount()));
    assertFalse(result.isDue());
  }

  @Test
  @DisplayName("чл. 79в т. 2 — само освободени: Кх е 0, кредитът за годината се връща")
  void art79v_onlyWithoutCredit_kxIsZero() {
    Input in =
        new Input(
            AssetKind.GOODS,
            CreditType.FULL,
            bg("10000"),
            2028,
            null,
            null,
            TaxableUse.ONLY_WITHOUT_CREDIT,
            null,
            null,
            null,
            false,
            false,
            false);

    Result result = BgArt79aAnnualCorrectionRules.correction(in);

    // ИРПДК = 10000 × 1/5 × (0 − 1) = −2000,00
    assertEquals(0, BigDecimal.ZERO.compareTo(result.appliedKx()));
    assertEquals(0, bg("-2000.00").compareTo(result.amount()));
  }

  @Test
  @DisplayName("чл. 79в т. 4 — при частичен кредит К₀ не може да е 0")
  void art79v_partialRequiresNonZeroK0() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BgArt79aAnnualCorrectionRules.correction(
                mixed(AssetKind.GOODS, CreditType.PARTIAL, "10000", "0", "0.80")));
  }

  @Test
  @DisplayName("чл. 79в т. 8 — ПрНИД₀ не може да бъде 0 или 1")
  void art79v_proportion0CannotBeOne() {
    Input in =
        new Input(
            AssetKind.GOODS,
            CreditType.PROPORTIONAL,
            bg("10000"),
            2028,
            bg("0.60"),
            BigDecimal.ONE,
            TaxableUse.MIXED,
            bg("0.75"),
            NidUse.MIXED,
            bg("0.80"),
            false,
            false,
            false);

    assertThrows(
        IllegalArgumentException.class, () -> BgArt79aAnnualCorrectionRules.correction(in));
  }

  // ───────────────────────────────────────────────── ППЗДДС чл. 67

  @Test
  @DisplayName("ППЗДДС чл. 67 ал. 3 — неизпълнен чл. 70 ал. 2: Кх е 0 дори при само облагаеми")
  void ppzdds67Para3_forcesKxZero() {
    Input in =
        new Input(
            AssetKind.GOODS,
            CreditType.FULL,
            bg("10000"),
            2028,
            null,
            null,
            TaxableUse.ONLY_WITH_CREDIT,
            null,
            null,
            null,
            false,
            false,
            true);

    Result result = BgArt79aAnnualCorrectionRules.correction(in);

    assertEquals(0, BigDecimal.ZERO.compareTo(result.appliedKx()));
    assertEquals(0, bg("-2000.00").compareTo(result.amount()));
  }

  @Test
  @DisplayName("ППЗДДС чл. 67 ал. 3 важи САМО за т. 2 б. „б“ — недвижимият имот не минава оттам")
  void ppzdds67Para3_doesNotTouchImmovable() {
    Input in =
        new Input(
            AssetKind.IMMOVABLE_PROPERTY,
            CreditType.FULL,
            bg("20000"),
            2028,
            null,
            null,
            TaxableUse.ONLY_WITH_CREDIT,
            null,
            null,
            null,
            false,
            false,
            true);

    Result result = BgArt79aAnnualCorrectionRules.correction(in);

    assertEquals(0, BigDecimal.ONE.compareTo(result.appliedKx()));
    assertEquals(0, BigDecimal.ZERO.compareTo(result.amount()));
  }

  @Test
  @DisplayName("ППЗДДС чл. 67 ал. 2 — без условието корекцията по т. 2 б. „а“ НЕ се извършва")
  void ppzdds67Para2_refusesWithoutCondition() {
    Result result = BgArt79aAnnualCorrectionRules.correction(art70Pt45Goods(false));

    assertEquals(0, BigDecimal.ZERO.compareTo(result.amount()));
    assertNotNull(result.refusalReason());
    assertTrue(result.refusalReason().contains("чл. 67 ал. 2"));
  }

  @Test
  @DisplayName("ППЗДДС чл. 67 ал. 2 — изпълненото условие пуска корекцията да се смята")
  void ppzdds67Para2_allowsWithCondition() {
    // ИРПДК = 10000 × 1/5 × 1 × 0,80 = 8000 / 5 = 1600,00
    Result result = BgArt79aAnnualCorrectionRules.correction(art70Pt45Goods(true));

    assertEquals(0, bg("1600.00").compareTo(result.amount()));
    assertNull(result.refusalReason());
  }

  private static Input art70Pt45Goods(boolean conditionMet) {
    return new Input(
        AssetKind.GOODS,
        CreditType.NONE,
        bg("10000"),
        2028,
        null,
        null,
        TaxableUse.MIXED,
        bg("0.80"),
        NidUse.ONLY_NID,
        null,
        true,
        conditionMet,
        false);
  }
}
