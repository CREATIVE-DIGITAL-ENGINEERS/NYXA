package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Контролната цифра на ЕИК.
 *
 * <p><b>Деветзначните еталони са РЕАЛНИ кодове от Търговския регистър</b>, а не изчислени от самата
 * сметка. Разликата е съществена: код, построен от алгоритъма, доказва единствено, че функцията е
 * съгласувана със себе си, и би минал и при сгрешени тегла. Точно такъв е заварения случай при ЛНЧ
 * — виж бележката в {@link BgIdentifierValidatorTest}.
 *
 * <p><b>Тринайсетзначните еталони са ПОСТРОЕНИ и затова доказват само вътрешна съгласуваност.</b>
 * Маркирани са „да се потвърди" и тъкмо заради това повикващият предупреждава, вместо да блокира.
 */
class BgEikValidationServiceTest {

  private final BgEikValidationService service = new BgEikValidationService();

  // ---------------------------------------------------------------- 9-значен, реални кодове

  @ParameterizedTest(name = "реален ЕИК {0} е валиден")
  @ValueSource(
      strings = {"130175000", "812114069", "202048483", "832046871", "123598789", "131471738"})
  void realNineDigitCodesAreValid(String eik) {
    assertTrue(service.validateEik(eik), eik);
    assertTrue(service.validateEik9(eik), eik);
  }

  @Test
  @DisplayName("130175000 минава през РЕЗЕРВНАТА схема — тя не е мъртъв код")
  void fallbackWeightsAreExercisedByARealCode() {
    // Основната сметка (тегла 1..8) дава остатък 10 за този код, тоест верният отговор идва само
    // от втория пробег с тегла 3..10. Ако някой махне резервната схема, този ред пада.
    int[] digits = new int[9];
    for (int i = 0; i < 9; i++) {
      digits[i] = "130175000".charAt(i) - '0';
    }
    assertTrue(service.weightedRemainder(digits, 0, new int[] {1, 2, 3, 4, 5, 6, 7, 8}) == 10);
    assertTrue(service.validateEik("130175000"));
  }

  @ParameterizedTest(name = "подправената контролна цифра в {0} се хваща")
  @ValueSource(strings = {"130175001", "812114060"})
  void tamperedControlDigitIsRejected(String eik) {
    assertFalse(service.validateEik(eik), eik);
  }

  // ---------------------------------------------------------------- дължини

  @ParameterizedTest(name = "дължина на {0} не е нито 9, нито 13 → невалиден")
  @ValueSource(strings = {"1301750000", "13017500001", "130175000012", "13017500", "1"})
  void onlyNineOrThirteenDigitsAreCodes(String value) {
    // ⚠️ Чл. 6 познава точно две дължини. Десет цифри не са „почти ЕИК" — SupplierEik#canonical
    // приема 9..13 нарочно, но то СВЕРЯВА разпознат текст, а тук решаваме какво да влезе в базата.
    assertFalse(service.validateEik(value), value);
  }

  @Test
  @DisplayName("нецифрен знак, празно и null се отхвърлят")
  void nonDigitsAreRejected() {
    assertFalse(service.validateEik("13017500X"));
    assertFalse(service.validateEik("BG130175000")); // префиксът е за ДДС номера, не за ЕИК
    assertFalse(service.validateEik("130 175 000")); // интервалите се чистят при въвеждане
    assertFalse(service.validateEik(""));
    assertFalse(service.validateEik("   "));
    assertFalse(service.validateEik(null));
  }

  @Test
  @DisplayName("крайните интервали се отрязват")
  void surroundingWhitespaceIsTrimmed() {
    assertTrue(service.validateEik("  130175000  "));
  }

  // ---------------------------------------------------------------- 13-значен · ДА СЕ ПОТВЪРДИ

  @ParameterizedTest(name = "построен 13-значен {0} е съгласуван със сметката")
  @ValueSource(strings = {"1301750000015", "8121140690011", "8121140699995"})
  void thirteenDigitCodesAreSelfConsistent(String eik) {
    // ⚠️ ДА СЕ ПОТВЪРДИ: тези кодове са ПОСТРОЕНИ от самия алгоритъм, защото нямаме реален
    // 13-значен еталон. Редът доказва, че опашката се смята и че валиден 9-значен префикс се
    // изисква — НЕ доказва, че теглата [2,7,3,5]/[4,9,5,7] са верните. Докато не дойде реален
    // код, BgIdentifierValidator предупреждава за 13-значните, вместо да отменя записа.
    assertTrue(service.validateEik(eik), eik);
    assertTrue(service.validateEik13(eik), eik);
  }

  @Test
  @DisplayName("13-значен с НЕВАЛИДЕН 9-значен префикс пада, колкото и вярна да е опашката")
  void thirteenDigitRequiresAValidNineDigitPrefix() {
    assertFalse(service.validateEik("1301750010015"));
  }

  @Test
  @DisplayName("подправената опашка на 13-значния се хваща")
  void thirteenDigitTamperedTail() {
    assertFalse(service.validateEik("1301750000016"));
  }

  @Test
  @DisplayName("9-значната врата не приема 13-значен и обратно")
  void lengthSpecificDoorsDoNotOverlap() {
    assertFalse(service.validateEik9("1301750000015"));
    assertFalse(service.validateEik13("130175000"));
  }
}
