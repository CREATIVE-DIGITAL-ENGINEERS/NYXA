package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.BgVatNumberValidator.Routing;
import com.axelor.apps.bglocalization.service.BgVatNumberValidator.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Офлайн слоят на ДДС номера.
 *
 * <p><b>Деветзначните еталони са същите РЕАЛНИ кодове от Търговския регистър</b>, с които се
 * доказва {@link BgEikValidationServiceTest} — тук не се преоткрива контролната цифра, а само
 * рутирането до нея. {@code 100000065} е добавен нарочно: той е и проверен в живия VIES
 * (17.09.2026, {@code isValid=true}), тоест е единственият еталон, за който двата слоя са
 * съгласувани по измерване, а не по допускане.
 *
 * <p><b>Тринайсетзначните са ПОСТРОЕНИ</b> и доказват само вътрешна съгласуваност — оттам и
 * предупреждението вместо блок в повикващия.
 *
 * <p>⚠️ Най-важните редове тук са тези, които твърдят {@link Verdict#NOT_CHECKED}. Те пазят
 * решението, че офлайн слоят МЪЛЧИ за чужд номер, за „-EX" и за десетзначния — мълчи, вместо да
 * гадае. Ако някой някога ги „поправи" на предупреждение, потребителят ще получава фалшива тревога
 * по законни номера.
 */
class BgVatNumberValidatorTest {

  // ------------------------------------------------------------------ БГ, 9-значни

  @ParameterizedTest(name = "валиден 9-значен БГ ДДС номер: {0}")
  @ValueSource(strings = {"BG100000065", "BG130175000", "BG812114069", "BG832046871"})
  void validNineDigitBgNumbersPass(String vat) {
    assertEquals(Verdict.OK, BgVatNumberValidator.checkLocally(vat), vat);
    assertFalse(BgVatNumberValidator.isBgChecksumViolation(vat), vat);
  }

  @ParameterizedTest(name = "невалиден 9-значен БГ ДДС номер: {0}")
  @ValueSource(strings = {"BG812114060", "BG130175001"})
  void invalidNineDigitBgNumbersWarn(String vat) {
    assertEquals(Verdict.BG_CHECKSUM_FAILED, BgVatNumberValidator.checkLocally(vat), vat);
    assertTrue(BgVatNumberValidator.isBgChecksumViolation(vat), vat);
  }

  // ------------------------------------------------------------------ БГ, 13-значни

  @ParameterizedTest(name = "валиден 13-значен БГ ДДС номер: {0}")
  @ValueSource(strings = {"BG1301750000015", "BG8121140690011", "BG8121140699995"})
  void validThirteenDigitBgNumbersPass(String vat) {
    assertEquals(Verdict.OK, BgVatNumberValidator.checkLocally(vat), vat);
  }

  @ParameterizedTest(name = "невалиден 13-значен БГ ДДС номер: {0}")
  @ValueSource(strings = {"BG1301750000016", "BG1301750010015"})
  void invalidThirteenDigitBgNumbersWarn(String vat) {
    assertEquals(Verdict.BG_CHECKSUM_FAILED, BgVatNumberValidator.checkLocally(vat), vat);
  }

  // ------------------------------------------------------------------ чуждите — офлайн МЪЛЧИ

  @ParameterizedTest(name = "чужд ДДС номер не се съди офлайн: {0}")
  @ValueSource(strings = {"DE811907980", "RO99999999", "FR12345678901", "EL123456789", "XX1"})
  void foreignNumbersAreNeverJudgedOffline(String vat) {
    assertEquals(Verdict.NOT_CHECKED, BgVatNumberValidator.checkLocally(vat), vat);
    assertFalse(BgVatNumberValidator.isBgChecksumViolation(vat), vat);
  }

  @Test
  @DisplayName("чуждият се разпознава като чужд — само той тръгва към VIES при запис")
  void foreignDetectionDrivesTheAutoCheck() {
    assertTrue(BgVatNumberValidator.isForeign("DE811907980"));
    assertTrue(BgVatNumberValidator.isForeign("ro99999999"));
    assertFalse(BgVatNumberValidator.isForeign("BG100000065"));
    // Без буквен префикс НЕ е чужд — иначе български запис би тръгнал към мрежата и би се забавил.
    assertFalse(BgVatNumberValidator.isForeign("100000065"));
    assertFalse(BgVatNumberValidator.isForeign(""));
    assertFalse(BgVatNumberValidator.isForeign(null));
  }

  // ------------------------------------------------------------------ „-EX" по чл. 168ж, ал. 11

  @ParameterizedTest(name = "номер по режима за малки предприятия се пропуска: {0}")
  @ValueSource(strings = {"BG100000065-EX", "BG130175001-EX", "bg100000065-ex"})
  void smallEnterpriseNumbersAreSkipped(String vat) {
    assertTrue(BgVatNumberValidator.isSmallEnterpriseNumber(vat), vat);
    assertEquals(Verdict.NOT_CHECKED, BgVatNumberValidator.checkLocally(vat), vat);
  }

  @Test
  @DisplayName("„-EX\" бие контролната цифра — иначе законен номер щеше да вдига тревога")
  void exSuffixWinsOverAFailingChecksum() {
    // 130175001 е ЧУПЛИВ 9-значен код и без окончанието дава предупреждение.
    assertEquals(Verdict.BG_CHECKSUM_FAILED, BgVatNumberValidator.checkLocally("BG130175001"));
    // Със същия код, но в режима по чл. 168ж, ал. 11, контролната цифра на ЕИК не важи.
    assertEquals(Verdict.NOT_CHECKED, BgVatNumberValidator.checkLocally("BG130175001-EX"));
  }

  // ------------------------------------------------------------------ 10-значният (ЕГН-базиран)

  @ParameterizedTest(name = "десетзначният БГ номер се пропуска офлайн: {0}")
  @ValueSource(strings = {"BG7501010010", "BG0000000000", "7501010010"})
  void tenDigitBgNumbersAreLeftToVies(String vat) {
    assertEquals(Verdict.NOT_CHECKED, BgVatNumberValidator.checkLocally(vat), vat);
    assertFalse(BgVatNumberValidator.isBgChecksumViolation(vat), vat);
  }

  // ------------------------------------------------------------------ празно и безсмислено

  @ParameterizedTest(name = "нищо за проверяване: [{0}]")
  @ValueSource(strings = {"", "   ", "BG", "BG-", "ЕИК", "BG12345", "BG12345678901234"})
  void nothingToCheckIsNotAViolation(String vat) {
    assertEquals(Verdict.NOT_CHECKED, BgVatNumberValidator.checkLocally(vat), vat);
    assertFalse(BgVatNumberValidator.isBgChecksumViolation(vat), vat);
  }

  @Test
  @DisplayName("null не гърми и не е нарушение")
  void nullIsHandled() {
    assertEquals(Verdict.NOT_CHECKED, BgVatNumberValidator.checkLocally(null));
    assertFalse(BgVatNumberValidator.isBgChecksumViolation(null));
    assertEquals("", BgVatNumberValidator.normalize(null));
    assertNull(BgVatNumberValidator.countryCode(null));
  }

  // ------------------------------------------------------------------ без префикс

  @Test
  @DisplayName("Номер без префикс се чете като БЪЛГАРСКИ — това е обичайното въвеждане")
  void prefixLessDigitsAreReadAsBulgarian() {
    assertEquals(Verdict.OK, BgVatNumberValidator.checkLocally("100000065"));
    assertEquals(Verdict.BG_CHECKSUM_FAILED, BgVatNumberValidator.checkLocally("130175001"));
    assertEquals(Verdict.OK, BgVatNumberValidator.checkLocally("1301750000015"));
  }

  // ------------------------------------------------------------------ нормализация

  @ParameterizedTest(name = "един и същи номер, различно преписан: [{0}]")
  @ValueSource(
      strings = {
        "BG100000065",
        "bg100000065",
        "BG 100 000 065",
        " BG100000065 ",
        "BG-100000065",
        "BG.202.670.670",
        "ЕИК: BG100000065"
      })
  void normalizationAcceptsHowPeopleCopyFromAnInvoice(String vat) {
    assertEquals(Verdict.OK, BgVatNumberValidator.checkLocally(vat), vat);
  }

  // ------------------------------------------------------------------ разлагане за VIES

  @Test
  @DisplayName("разлагането дава точно двата параметъра, които VIES иска")
  void decompositionFeedsTheViesCall() {
    assertEquals("DE", BgVatNumberValidator.countryCode("DE811907980"));
    assertEquals("811907980", BgVatNumberValidator.nationalNumber("DE811907980"));

    assertEquals("BG", BgVatNumberValidator.countryCode("bg 100 000 065"));
    assertEquals("100000065", BgVatNumberValidator.nationalNumber("bg 100 000 065"));

    // Без префикс няма код на държава — повикващият решава какво да прави, вместо да получи „BG"
    // наготово от функция, която не знае нищо за партньора.
    assertNull(BgVatNumberValidator.countryCode("100000065"));
    assertEquals("100000065", BgVatNumberValidator.nationalNumber("100000065"));

    // Окончанието не е част от номера.
    assertEquals("100000065", BgVatNumberValidator.nationalNumber("BG100000065-EX"));
    assertEquals("12345678", BgVatNumberValidator.nationalNumber("RO12345678EX"));
  }

  // ------------------------------------------------------------------ рутиране

  @ParameterizedTest(name = "български → локална контролна цифра: {0}")
  @ValueSource(strings = {"BG100000065", "BG130175001", "BG1301750000015", "100000065", "bg 202"})
  void bulgarianRoutesLocally(String vat) {
    assertEquals(Routing.BG_LOCAL, BgVatNumberValidator.routeOf(vat), vat);
  }

  @ParameterizedTest(name = "държава от VIES → жива справка: {0}")
  @ValueSource(
      strings = {"DE811907980", "RO12345678", "FR40303265045", "EL123456789", "XI123456789"})
  void viesCountriesRouteToTheService(String vat) {
    assertEquals(Routing.VIES, BgVatNumberValidator.routeOf(vat), vat);
  }

  @Test
  @DisplayName("Гърция е EL, а не GR — класическото място, където такъв списък е сгрешен")
  void greeceUsesTheVatCodeNotTheIsoCode() {
    assertEquals(Routing.VIES, BgVatNumberValidator.routeOf("EL123456789"));
    // GR не е код за целите на ДДС: няма кого да питаме, тоест мълчание, а не фалшива тревога.
    assertEquals(Routing.SKIP, BgVatNumberValidator.routeOf("GR123456789"));
  }

  @ParameterizedTest(name = "извън VIES → нищо не се проверява: {0}")
  @ValueSource(strings = {"CH1234567", "US123456789", "GB123456789", "NO123456789", "", "   "})
  void nonViesCountriesAreSkipped(String vat) {
    assertEquals(Routing.SKIP, BgVatNumberValidator.routeOf(vat), vat);
  }

  @Test
  @DisplayName("Обединеното кралство: GB отпадна след Брекзит, XI остава по Протокола")
  void unitedKingdomIsSplit() {
    assertEquals(Routing.SKIP, BgVatNumberValidator.routeOf("GB123456789"));
    assertEquals(Routing.VIES, BgVatNumberValidator.routeOf("XI123456789"));
  }

  @ParameterizedTest(name = "режимът за малки предприятия бие всичко останало: {0}")
  @ValueSource(strings = {"BG100000065-EX", "DE811907980-EX", "RO12345678EX", "bg130175001-ex"})
  void smallEnterpriseRoutingWinsOverCountry(String vat) {
    // ⚠️ Редът на проверките е част от правилото: такъв номер носи и код на държава и без този
    // приоритет би отишъл към VIES, който не го познава и би върнал INVALID — фалшива тревога по
    // напълно законен номер.
    assertEquals(Routing.SMALL_ENTERPRISE, BgVatNumberValidator.routeOf(vat), vat);
  }

  @Test
  @DisplayName("„EX“ след БУКВА не е режимът — пази формат, който случайно свършва на EX")
  void exAfterALetterIsNotTheRegime() {
    assertTrue(BgVatNumberValidator.isSmallEnterpriseNumber("DE1234567EX"));
    // А след буква — не: тук „EX“ е част от самия номер.
    assertFalse(BgVatNumberValidator.isSmallEnterpriseNumber("IEABCDEX"));
  }

  @Test
  @DisplayName("Боклук без цифри не се рутира никъде")
  void garbageIsSkipped() {
    assertEquals(Routing.SKIP, BgVatNumberValidator.routeOf("ЕИК"));
    assertEquals(Routing.SKIP, BgVatNumberValidator.routeOf("BG"));
    assertEquals(Routing.SKIP, BgVatNumberValidator.routeOf(null));
  }
}
