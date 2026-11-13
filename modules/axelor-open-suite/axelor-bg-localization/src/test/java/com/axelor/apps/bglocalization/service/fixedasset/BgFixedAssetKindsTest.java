package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.base.db.Company;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Видът на актива (SAF-T A1): подразбирането от сметката, рамката, селекцията. */
class BgFixedAssetKindsTest {

  @Test
  @DisplayName("По подразбиране от сметката: 21x → НМА, всичко друго → ДМА; без сметка — празно")
  void подразбирането() {
    assertEquals(BgFixedAssetKinds.INTANGIBLE, BgFixedAssetKinds.defaultFor("2141"));
    assertEquals(BgFixedAssetKinds.INTANGIBLE, BgFixedAssetKinds.defaultFor(" 214 "));
    assertEquals(BgFixedAssetKinds.TANGIBLE, BgFixedAssetKinds.defaultFor("2041"));
    assertEquals(BgFixedAssetKinds.TANGIBLE, BgFixedAssetKinds.defaultFor("207"));
    assertEquals(
        BgFixedAssetKinds.TANGIBLE,
        BgFixedAssetKinds.defaultFor("223"),
        "инвестиционен имот не се извежда сам — избира го човекът");
    assertNull(BgFixedAssetKinds.defaultFor(null));
    assertNull(BgFixedAssetKinds.defaultFor("  "));
  }

  @Test
  @DisplayName("Неподдържаните в A1–A6 са справедлива стойност, МСФО 5 и МСФО 16; само МСФО — два")
  void множествата() {
    assertEquals(
        Set.of(
            BgFixedAssetKinds.INVESTMENT_PROPERTY_FAIR_VALUE,
            BgFixedAssetKinds.HELD_FOR_SALE,
            BgFixedAssetKinds.RIGHT_OF_USE),
        BgFixedAssetKinds.UNSUPPORTED);
    assertEquals(
        Set.of(BgFixedAssetKinds.HELD_FOR_SALE, BgFixedAssetKinds.RIGHT_OF_USE),
        BgFixedAssetKinds.IFRS_ONLY);
    assertTrue(BgFixedAssetKinds.ALL.containsAll(BgFixedAssetKinds.UNSUPPORTED));
  }

  @Test
  @DisplayName("Рамката: празна, непозната и без дружество — null (без мълчалив default)")
  void рамката() {
    assertNull(BgAccountingFramework.of(null));
    Company company = new Company();
    assertNull(BgAccountingFramework.of(company));
    company.setBgAccountingFramework("US_GAAP");
    assertNull(BgAccountingFramework.of(company));
    company.setBgAccountingFramework("IFRS");
    assertEquals(BgAccountingFramework.IFRS, BgAccountingFramework.of(company));
    company.setBgAccountingFramework("NSS");
    assertEquals(BgAccountingFramework.NSS, BgAccountingFramework.of(company));
  }

  @Test
  @DisplayName("Селекцията bg.fixed.asset.kind.select = BgFixedAssetKinds.ALL, в същия ред")
  void селекцията() throws Exception {
    assertEquals(new ArrayList<>(BgFixedAssetKinds.ALL), selection("bg.fixed.asset.kind.select"));
  }

  @Test
  @DisplayName("Полето е на актива (генерираният getter/setter съществува)")
  void полето() {
    FixedAsset asset = new FixedAsset();
    asset.setBgAssetKindSelect(BgFixedAssetKinds.TANGIBLE);
    assertEquals(BgFixedAssetKinds.TANGIBLE, asset.getBgAssetKindSelect());
  }

  /** Стойностите на селекция от views/Selects.xml, в реда на файла. */
  static List<String> selection(String name) throws Exception {
    String xml;
    try (InputStream in = BgFixedAssetKindsTest.class.getResourceAsStream("/views/Selects.xml")) {
      xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    int from = xml.indexOf("<selection name=\"" + name + "\">");
    assertTrue(from >= 0, "няма селекция " + name);
    String block = xml.substring(from, xml.indexOf("</selection>", from));
    List<String> values = new ArrayList<>();
    Matcher m = Pattern.compile("<option value=\"([^\"]+)\"").matcher(block);
    while (m.find()) {
      values.add(m.group(1));
    }
    return values;
  }
}
