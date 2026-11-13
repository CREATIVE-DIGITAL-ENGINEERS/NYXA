package com.axelor.apps.aiocr.service.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.aiocr.service.ocr.BgOcrAddressParser.Decision;
import com.axelor.apps.aiocr.service.ocr.BgOcrAddressParser.Parsed;
import org.junit.jupiter.api.Test;

/** Unit tests for the strict BG address parser + match/decision helpers. */
class BgOcrAddressParserTest {

  private Parsed sofia() {
    return BgOcrAddressParser.parse("България, гр. София, П.К. 1582, жк. Дружба, №2");
  }

  @Test
  void buildSupplierUsesAzureHintsAndFirstLineCity() {
    // Real ТЕСТЕКС invoice: city on line 1, street on line 2, NO postcode; Azure gave road+number.
    String content = "София\nул.Самоковско шосе 2Л ТЦ\"БОИЛА\" ет.3 офис 321";
    Parsed p = BgOcrAddressParser.buildSupplier(content, null, "ул.Самоковско шосе", "2Л", null);
    assertEquals("София", p.cityName);
    assertTrue(p.street != null && p.street.contains("Самоковско"));
    assertEquals("2Л", p.number);
    assertNull(p.zip); // no postcode in the document — caller takes it from the City
    assertEquals("BG", p.countryIso);
    assertTrue(p.usable());
  }

  @Test
  void buildSupplierPrefersAzureCityAndZip() {
    Parsed p =
        BgOcrAddressParser.buildSupplier("Пловдив\nул. Тест 5", "Пловдив", "ул. Тест", "5", "4000");
    assertEquals("Пловдив", p.cityName);
    assertEquals("4000", p.zip);
    assertEquals("5", p.number);
    assertTrue(p.usable());
  }

  @Test
  void buildSupplierTakesOnlyFirstOfSeveralAddresses() {
    // ELTRADE-style: two supplier addresses, city glued to "ГР." with the street on the same line.
    // Only the FIRST address is used; the second (София) is ignored.
    String content = "ГР.ПЛОВДИВ, БУЛ.ВАСИЛ ЛЕВСКИ 170\nГР.СОФИЯ, БУЛ.ПРОФ.ЦВЕТАН ЛАЗАРОВ 85";
    Parsed p = BgOcrAddressParser.buildSupplier(content, null, null, null, null);
    assertEquals("ПЛОВДИВ", p.cityName);
    assertTrue(p.street != null && p.street.contains("ВАСИЛ ЛЕВСКИ"));
    assertFalse(p.street.contains("ЛАЗАРОВ")); // the second address must not leak in
    assertTrue(p.usable());
  }

  @Test
  void buildSupplierCityAsFirstCommaSegmentWithoutMarker() {
    // Lindström-style: bare city + neighborhood/street on one comma-separated line, no "гр." marker
    // and no Azure city hint. The city must still be recognized so the address can be structured.
    Parsed p =
        BgOcrAddressParser.buildSupplier(
            "София, жк. Хаджи Димитър, ул. Витиня 2Ж", null, null, null, null);
    assertEquals("София", p.cityName);
    assertTrue(p.street != null && p.street.contains("Витиня"));
    assertTrue(p.usable());
  }

  @Test
  void buildSupplierRejectsStreetOnlyFirstLine() {
    // First line is a street, not a city → no city recognized → not usable (never guessed).
    Parsed p = BgOcrAddressParser.buildSupplier("ул. Тест 5\nнещо", null, null, null, null);
    assertFalse(p.usable());
  }

  @Test
  void parsesExplicitBulgarianAddress() {
    Parsed p = sofia();
    assertEquals("София", p.cityName);
    assertEquals("1582", p.zip);
    assertEquals("2", p.number);
    assertTrue(p.street != null && p.street.startsWith("жк"));
    assertEquals("BG", p.countryIso);
    assertTrue(p.usable());
  }

  @Test
  void recoversCityAfterLabelColon() {
    Parsed p = BgOcrAddressParser.parse("Адрес за кореспонденция: София");
    assertEquals("София", p.cityName);
    assertEquals("BG", p.countryIso);
  }

  @Test
  void noArbitraryFallbackForLatinJunkOrBareNumber() {
    assertFalse(BgOcrAddressParser.parse("SASSSS SADDAS SDASDAASD ASDDSA SAD 531").usable());
    assertFalse(BgOcrAddressParser.parse("531").usable());
    assertNull(BgOcrAddressParser.parse("531").zip); // 531 is not marked as П.К. → not a postcode
  }

  @Test
  void bareNumberNeverBecomesPostcodeWithoutMarker() {
    assertNull(BgOcrAddressParser.parse("гр. София, 531").zip);
    assertEquals("1000", BgOcrAddressParser.parse("гр. София, П.К. 1000").zip);
  }

  @Test
  void countryBulgariaOnlyByExplicitBg() {
    assertEquals("BG", BgOcrAddressParser.countryIso("България, гр. София"));
    assertEquals("BG", BgOcrAddressParser.countryIso("гр. София")); // default flow
    assertEquals("BG", BgOcrAddressParser.countryIso(null));
  }

  @Test
  void nonBulgarianExplicitCountryDetected() {
    assertEquals("GB", BgOcrAddressParser.countryIso("United Kingdom, London EC1"));
  }

  @Test
  void exactAndNormalizedMatch() {
    Parsed p = sofia();
    // The street now keeps the full tail after the keyword ("жк. Дружба, №2").
    assertTrue(BgOcrAddressParser.matches(p, "София", "1582", "жк. Дружба, №2", "2"));
    assertTrue(
        BgOcrAddressParser.matches(p, "  софия ", "1582", "ЖК.  Дружба,  №2", "2")); // spacing/case
  }

  @Test
  void sameZipDifferentCityDoesNotMatch() {
    assertFalse(BgOcrAddressParser.matches(sofia(), "sad", "1582", "жк. Дружба, №2", "2"));
  }

  @Test
  void intakePathParsesFullCommaAddressWithLeadingCountry() {
    // OTP Leasing (#280): the whole address is one comma-separated line with the country FIRST.
    // The intake path (buildSupplier → cityFromLine) must take the marked "гр. София" — never the
    // leading "България" — and keep the full street tail, not truncate at the first comma.
    Parsed p =
        BgOcrAddressParser.buildSupplier(
            "България, гр. София, П.К. 1582, жк. Дружба, №2, БЛ. 410, ВХ.Б, ЕТ.7, АП.51",
            null,
            null,
            null,
            null);
    assertEquals("София", p.cityName);
    assertEquals("1582", p.zip);
    assertEquals("2", p.number);
    assertEquals("BG", p.countryIso);
    assertTrue(p.street != null && p.street.startsWith("жк"), "street starts at the keyword");
    assertTrue(p.street.contains("БЛ. 410") && p.street.contains("АП.51"), "keeps the full tail");
    assertTrue(p.usable());
  }

  @Test
  void countryNameIsNeverTheCity() {
    // A lone country segment must not be mistaken for a settlement; the marked city after it wins.
    assertEquals(
        "София",
        BgOcrAddressParser.buildSupplier("България, гр. София", null, null, null, null).cityName);
    assertTrue(BgOcrAddressParser.isCountry("България"));
    assertTrue(BgOcrAddressParser.isCountry("Република България"));
    assertTrue(BgOcrAddressParser.isCountry("Bulgaria"));
    assertTrue(BgOcrAddressParser.isCountry("Republic of Bulgaria"));
    assertTrue(BgOcrAddressParser.isCountry("BG"));
    assertFalse(BgOcrAddressParser.isCountry("София"));
  }

  @Test
  void missingPostcodeMatchingIsExact() {
    Parsed p = BgOcrAddressParser.parse("гр. София");
    assertNull(p.zip);
    assertTrue(BgOcrAddressParser.matches(p, "София", null, null, null));
    assertFalse(BgOcrAddressParser.matches(p, "София", "1000", null, null));
  }

  @Test
  void decisionBySupplierMatchCount() {
    assertEquals(Decision.CREATE, BgOcrAddressParser.decide(0));
    assertEquals(Decision.SELECT, BgOcrAddressParser.decide(1));
    assertEquals(Decision.WARN_MULTIPLE, BgOcrAddressParser.decide(2));
  }

  @Test
  void firstSupplierAddressLineTakesSupplierNotCustomer() {
    // Fixture P17 (ЕКО БЪЛГАРИЯ receipt): the supplier address line is printed at the top, above
    // the
    // ТЕСТЕКС customer block. The fallback must take the FIRST explicit-marker city line
    // (supplier),
    // never borrow the customer's, and structure it into a usable supplier address.
    String receipt =
        "ELTRADE\n\"ЕКО БЪЛГАРИЯ\" ЕАД\nгр. София ул. Лучезар Станчев No3 ет.9\nБИК:130948987\n"
            + "ОРИГИНАЛ\nФактура №1084630089\nКУПУВАЧ:\nТЕСТЕКС ООД\n"
            + "Гр. София, Дружба-2, бл. 410\nФИСКАЛЕН БОН\n14:39:45";
    String line = BgOcrAddressParser.firstSupplierAddressLine(receipt);
    assertNotNull(line);
    assertTrue(line.contains("Лучезар Станчев"), line);
    assertFalse(line.contains("Дружба-2"), line); // not the customer address
    Parsed p = BgOcrAddressParser.buildSupplier(line, null, null, null, null);
    assertEquals("София", p.cityName);
    assertTrue(p.street != null && p.street.contains("Лучезар Станчев"), p.street);
    assertTrue(p.usable());
  }

  @Test
  void firstSupplierAddressLineNullWhenNoExplicitCity() {
    // No explicit settlement marker (гр./град/с./село) anywhere: an all-caps line like "ФИСКАЛЕН
    // БОН" must NOT be mistaken for a city → null, so the caller never fabricates an address.
    assertNull(BgOcrAddressParser.firstSupplierAddressLine("ELTRADE\nФИСКАЛЕН БОН\n0365369\nBG"));
    assertNull(BgOcrAddressParser.firstSupplierAddressLine(null));
  }
}
