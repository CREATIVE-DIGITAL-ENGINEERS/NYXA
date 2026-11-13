package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgVatCoefficient;
import com.axelor.apps.bglocalization.db.BgVatPackage;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.db.repo.BgVatPackageRepository;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Кл.33 се попълва от реда с коефициента по чл. 73 ЗДДС — а „няма ред“ и „ред с празна стойност“ до
 * тази поправка бяха различими само на думи.
 *
 * <p>Условието беше {@code coefficient != null && coefficient.getCoefficient() != null}. Втората
 * половина е мъртва: генерираният getter за decimal връща ZERO. Тоест ред с непопълнен коефициент
 * слагаше кл.33 = 0,00 и НЕ казваше нищо — а редът без коефициент и липсващият ред дават еднаква
 * декларация. Разликата се виждаше само по това дали е предупреден човекът.
 */
class BgVatReturnControllerTest {

  private static BgVatCoefficient coefficient(String value) {
    BgVatCoefficient c = new BgVatCoefficient();
    if (value != null) {
      c.setCoefficient(new BigDecimal(value));
    }
    return c;
  }

  @Test
  @DisplayName("⚠️ Ред с НЕПОПЪЛНЕН коефициент не се брои за попълнен — това беше мъртвото условие")
  void aRowWithAnEmptyCoefficientDoesNotCount() {
    // Първо самият капан, за да не се чете тестът като догма: полето не е пипано, а getter-ът
    // връща 0,00 вместо null.
    BgVatCoefficient empty = coefficient(null);
    assertEquals(0, empty.getCoefficient().signum(), "getter-ът връща ZERO, не null");

    assertFalse(BgVatReturnController.hasUsableCoefficient(empty), "празен ред → предупреждение");
    assertFalse(BgVatReturnController.hasUsableCoefficient(null), "липсващ ред → същото");
    assertFalse(BgVatReturnController.hasUsableCoefficient(coefficient("0.00")), "нулата — също");
  }

  /** Контролерът с подменени пакет и проверки — истинският {@code submitRefusal}. */
  private static BgVatReturnController controller(BgVatPackage pkg, boolean stale) {
    return new BgVatReturnController() {
      @Override
      protected List<String> validateForSubmit(BgVatReturn r) {
        return Collections.emptyList();
      }

      @Override
      protected BgVatPackage latestPackage(BgVatReturn vatReturn) {
        return pkg;
      }

      @Override
      protected boolean isLatestPackageStale(BgVatReturn vatReturn) {
        return stale;
      }
    };
  }

  private static BgVatPackage pkg(String readiness) {
    BgVatPackage p = new BgVatPackage();
    p.setReadinessSelect(readiness);
    return p;
  }

  private static BgVatReturn generated() {
    BgVatReturn r = new BgVatReturn();
    r.setStatusSelect("generated");
    return r;
  }

  @Test
  @DisplayName("„Отбележи като подадена“ отказва пакет с блокиращи грешки — както sendToNap")
  void markSubmitted_refusesAPackageWithErrors() {
    assertEquals(
        "Пакетът има блокиращи грешки — коригирайте и генерирайте отново.",
        controller(pkg(BgVatPackageRepository.READINESS_ERRORS), false).submitRefusal(generated()));
    // Грешките се казват преди остарелия пакет: регенерирането само не ги маха.
    assertEquals(
        "Пакетът има блокиращи грешки — коригирайте и генерирайте отново.",
        controller(pkg(BgVatPackageRepository.READINESS_ERRORS), true).submitRefusal(generated()));
  }

  @Test
  @DisplayName("Пакет без грешки и без промени се отбелязва; останалите откази са непокътнати")
  void markSubmitted_otherwiseAsBefore() {
    assertEquals(
        null,
        controller(pkg(BgVatPackageRepository.READINESS_READY), false).submitRefusal(generated()));
    assertEquals(
        null,
        controller(pkg(BgVatPackageRepository.READINESS_WARNINGS), false)
            .submitRefusal(generated()));
    assertEquals(null, controller(null, false).submitRefusal(generated()));
    assertTrue(controller(pkg("ready"), true).submitRefusal(generated()).startsWith("Изходните"));
    BgVatReturn draft = new BgVatReturn();
    draft.setStatusSelect("draft");
    assertEquals(
        "Само генерирана декларация може да се отбележи като подадена.",
        controller(pkg("ready"), false).submitRefusal(draft));
  }

  @Test
  @DisplayName("Попълненият коефициент минава без предупреждение")
  void aFilledCoefficientIsUsable() {
    assertTrue(BgVatReturnController.hasUsableCoefficient(coefficient("0.87")));
    assertTrue(BgVatReturnController.hasUsableCoefficient(coefficient("1.00")));
  }
}
