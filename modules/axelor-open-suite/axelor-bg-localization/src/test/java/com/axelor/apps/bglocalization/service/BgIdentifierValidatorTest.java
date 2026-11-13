package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BgIdentifierValidator} (ЕГН/ЛНЧ checksum + date, postal code, ЕИК and the
 * branch by partner/identifier type). Reference valid EGN {@code 6803030040} (1950-09-16, control
 * digit 3) and a computed valid ЛНЧ.
 *
 * <p>⚠️ <b>ЛНЧ еталонът {@code 1000000001} е ИЗЧИСЛЕН от самата имплементация</b>, а не взет от
 * реален документ. Тоест той доказва, че функцията е съгласувана със себе си, и би минал и при
 * сгрешени тегла. Теглата на ЛНЧ остават „да се потвърди". За разлика от тях деветзначните ЕИК
 * еталони в {@link BgEikValidationServiceTest} са реални кодове от Търговския регистър.
 */
class BgIdentifierValidatorTest {

  // --- ЕГН ---

  @Test
  void validEgn() {
    assertTrue(BgIdentifierValidator.isValidEgn("6803030040"));
  }

  @Test
  void invalidEgn_badChecksum() {
    assertFalse(BgIdentifierValidator.isValidEgn("5009163204")); // control should be 3, not 4
  }

  @Test
  void invalidEgn_badDate() {
    assertFalse(BgIdentifierValidator.isValidEgn("5013163203")); // month 13 is not a valid encoding
  }

  // --- ЛНЧ ---

  @Test
  void validLnch() {
    assertTrue(BgIdentifierValidator.isValidLnch("1000000001")); // 1*21 % 10 = 1 = last digit
  }

  @Test
  void invalidLnch_badChecksum() {
    assertFalse(BgIdentifierValidator.isValidLnch("1000000002"));
  }

  // --- length / format ---

  @Test
  void nineDigits() {
    assertFalse(BgIdentifierValidator.isValidEgnOrLnch("500916320"));
  }

  @Test
  void elevenDigits() {
    assertFalse(BgIdentifierValidator.isValidEgnOrLnch("68030300403"));
  }

  @Test
  void nonDigits() {
    assertFalse(BgIdentifierValidator.isValidEgnOrLnch("50091632AX"));
    assertFalse(BgIdentifierValidator.isValidEgnOrLnch(null));
  }

  @Test
  void egnOrLnch_acceptsEither() {
    assertTrue(BgIdentifierValidator.isValidEgnOrLnch("6803030040")); // EGN
    assertTrue(BgIdentifierValidator.isValidEgnOrLnch("1000000001")); // LNCh
    assertFalse(BgIdentifierValidator.isValidEgnOrLnch("1234567890"));
  }

  // --- postal code ---

  @Test
  void validPostalCodes() {
    assertTrue(BgIdentifierValidator.isValidPostalCode("1000"));
    assertTrue(BgIdentifierValidator.isValidPostalCode("1186"));
    assertTrue(BgIdentifierValidator.isValidPostalCode("9999"));
  }

  @Test
  void invalidPostalCodes() {
    assertFalse(BgIdentifierValidator.isValidPostalCode("0999")); // < 1000
    assertFalse(BgIdentifierValidator.isValidPostalCode("123")); // 3 digits
    assertFalse(BgIdentifierValidator.isValidPostalCode("12345")); // 5 digits
    assertFalse(BgIdentifierValidator.isValidPostalCode("abcd"));
    assertFalse(BgIdentifierValidator.isValidPostalCode(null));
  }

  // --- ЕИК ---

  @Test
  void validEik() {
    assertTrue(BgIdentifierValidator.isValidEik("130175000")); // реален код от ТР
    assertFalse(BgIdentifierValidator.isValidEik("130175001")); // подправена контролна цифра
    assertFalse(BgIdentifierValidator.isValidEik("1301750000")); // 10 цифри не са ЕИК
  }

  // --- разклонение по вид партньор и признак ---

  /**
   * Фирма без попълнен признак: видът партньор решава и невалидният деветзначен код ОТМЕНЯ записа.
   */
  @Test
  void companyWithoutIdentifierTypeBlocksOnBadEik() {
    assertTrue(BgIdentifierValidator.isBlockingViolation(1, null, "130175001"));
    assertFalse(BgIdentifierValidator.isBlockingViolation(1, null, "130175000"));
    assertFalse(BgIdentifierValidator.isValidRegistrationCode(1, null, "130175001"));
    assertTrue(BgIdentifierValidator.isValidRegistrationCode(1, null, "130175000"));
  }

  /**
   * ⚠️ Тринайсетзначният НЕ отменя запис — само предупреждава, защото опашковата сметка не е
   * сверена срещу реален код.
   */
  @Test
  void thirteenDigitEikWarnsButNeverBlocks() {
    assertFalse(BgIdentifierValidator.isBlockingViolation(1, null, "1301750000016"));
    assertTrue(BgIdentifierValidator.isWarningViolation(1, null, "1301750000016"));
    assertFalse(BgIdentifierValidator.isWarningViolation(1, null, "1301750000015"));
  }

  /**
   * Всяка чужда на чл. 6 дължина също само предупреждава — не познаваме правило, което да наложим.
   */
  @Test
  void oddLengthWarnsButNeverBlocks() {
    assertFalse(BgIdentifierValidator.isBlockingViolation(1, null, "1301750000"));
    assertTrue(BgIdentifierValidator.isWarningViolation(1, null, "1301750000"));
    assertFalse(BgIdentifierValidator.isBlockingViolation(1, null, "13017500X"));
    assertTrue(BgIdentifierValidator.isWarningViolation(1, null, "13017500X"));
  }

  /**
   * ⚠️ ПРИЗНАКЪТ БИЕ ВИДА ПАРТНЬОР. Физическо лице със свободна професия носи БУЛСТАТ; водено по
   * вида партньор, проверката би търсила ЕГН и би отхвърлила законен код.
   */
  @Test
  void identifierTypeOverridesPartnerType() {
    // тип „2" = БУЛСТАТ, върху ФИЗИЧЕСКО лице → пак ЕИК
    assertTrue(BgIdentifierValidator.isBlockingViolation(2, "2", "130175001"));
    assertFalse(BgIdentifierValidator.isBlockingViolation(2, "2", "130175000"));
    // и обратното: тип „0" = ЕГН върху ФИРМА → ЕГН, тоест никакъв блок
    assertFalse(BgIdentifierValidator.isBlockingViolation(1, "0", "5009163204"));
    assertTrue(BgIdentifierValidator.isWarningViolation(1, "0", "5009163204"));
  }

  /**
   * ⚠️ И НЕ Е МАПИНГЪТ НА EMPLOYEE. Там {@code bg.personal.id.type.select} дава „2" = ЛНЧ; тук „2"
   * е БУЛСТАТ. Същите цифри, други значения — редът долу пази разликата.
   */
  @Test
  void typeTwoIsBulstatHereNotLnch() {
    // валиден ЛНЧ, обявен като тип „2": по мапинга на Employee това би минало, тук е ЕИК и не е
    assertTrue(BgIdentifierValidator.isWarningViolation(2, "2", "1000000001"));
  }

  /** Тип „0" е само ЕГН; валиден ЛНЧ там е предупреждение. */
  @Test
  void typeZeroIsEgnOnly() {
    assertFalse(BgIdentifierValidator.isWarningViolation(2, "0", "6803030040"));
    assertTrue(BgIdentifierValidator.isWarningViolation(2, "0", "1000000001"));
  }

  /** Тип „1" е ОБЕДИНЕНИЕ (ЛН, ЛНЧ, Сл. № от НАП) — приема и ЕГН, и ЛНЧ, и никога не блокира. */
  @Test
  void typeOneAcceptsEitherAndNeverBlocks() {
    assertFalse(BgIdentifierValidator.isWarningViolation(2, "1", "6803030040"));
    assertFalse(BgIdentifierValidator.isWarningViolation(2, "1", "1000000001"));
    assertTrue(BgIdentifierValidator.isWarningViolation(2, "1", "1234567890"));
    assertFalse(BgIdentifierValidator.isBlockingViolation(2, "1", "1234567890"));
  }

  /** Тип „3" (чуждестранно лице без идентификатор) се прескача изцяло. */
  @Test
  void typeThreeIsSkippedEntirely() {
    assertFalse(BgIdentifierValidator.isBlockingViolation(1, "3", "130175001"));
    assertFalse(BgIdentifierValidator.isWarningViolation(1, "3", "130175001"));
    assertTrue(BgIdentifierValidator.isValidRegistrationCode(1, "3", "каквото и да е"));
  }

  /** Непозната стойност на признака се прескача, вместо да се познава. */
  @Test
  void unknownIdentifierTypeIsSkipped() {
    assertFalse(BgIdentifierValidator.isBlockingViolation(1, "9", "130175001"));
    assertFalse(BgIdentifierValidator.isWarningViolation(1, "9", "130175001"));
  }

  /** Празен код не е нарушение — задължителността на полето е друг въпрос. */
  @Test
  void emptyCodeIsNeverAViolation() {
    assertFalse(BgIdentifierValidator.isBlockingViolation(1, null, null));
    assertFalse(BgIdentifierValidator.isBlockingViolation(1, null, "   "));
    assertFalse(BgIdentifierValidator.isWarningViolation(1, null, ""));
    assertTrue(BgIdentifierValidator.isValidRegistrationCode(1, null, null));
  }

  /** Липсващ вид партньор И липсващ признак → няма какво да се реши, прескача се. */
  @Test
  void noPartnerTypeAndNoIdentifierTypeIsSkipped() {
    assertFalse(BgIdentifierValidator.isBlockingViolation(null, null, "130175001"));
    assertFalse(BgIdentifierValidator.isWarningViolation(null, null, "130175001"));
  }

  // -------------------------------------------------- кое се проверява, казано с думи за екрана

  /**
   * Клонът, по който живият тост избира между „ЕИК/Булстат" и „ЕГН/ЛНЧ", е СЪЩИЯТ, по който тече и
   * самата проверка — затова признакът бие вида партньор и тук.
   */
  @Test
  void companyIdentifierFollowsTheSameBranchAsTheCheck() {
    assertTrue(BgIdentifierValidator.isCompanyIdentifier(1, null));
    assertFalse(BgIdentifierValidator.isCompanyIdentifier(2, null));

    // Физическо лице с БУЛСТАТ (свободна професия): признакът бие вида, тоест съобщението трябва
    // да говори за ЕИК/Булстат, не за ЕГН. Преписан в XML, този ред щеше да се забрави.
    assertTrue(BgIdentifierValidator.isCompanyIdentifier(2, "2"));
    assertFalse(BgIdentifierValidator.isCompanyIdentifier(1, "0"));
  }

  /**
   * ⚠️ ЗАЩО ЖИВАТА ПРОВЕРКА ТРЯБВА ДА Е ГЕЙТНАТА ПО БЪЛГАРСКА ИДЕНТИЧНОСТ.
   *
   * <p>Тези три са реални френски SIREN-и. Деветцифрени са, тоест минават през сметката за 9-значен
   * ЕИК и падат на нея — a 14-цифреният SIRET пада като „чужда на чл. 6 дължина". Тоест проверка
   * без гейт по държава обвинява законен чужд номер. Това не е дефект на правилото: то отговаря на
   * въпроса „верен ли е като БЪЛГАРСКИ код", а кога този въпрос е уместен, решава повикващият.
   */
  @Test
  void foreignCompanyNumbersLookInvalidToTheBulgarianRule() {
    for (String siren : new String[] {"552100554", "732829320", "404833048"}) {
      assertFalse(
          BgIdentifierValidator.isValidRegistrationCode(1, null, siren),
          "френски SIREN " + siren + " минава българската контролна цифра — еталонът е сгрешен");
    }
    assertFalse(BgIdentifierValidator.isValidRegistrationCode(1, null, "55210055400013"));
  }
}
