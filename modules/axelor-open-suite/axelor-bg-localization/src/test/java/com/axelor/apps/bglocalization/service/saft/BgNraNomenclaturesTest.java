package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗАРЕЖДАЧЪТ НА НОМЕНКЛАТУРИТЕ — чете файловете от classpath-а, без база.
 *
 * <p>Броят на редовете е същият, който {@code BgNraNomenclatureIntegrityTest} пази за самите
 * файлове: тук се твърди, че ЧЕТЕНЕТО не губи и не добавя ред, не че файлът е верен.
 */
class BgNraNomenclaturesTest {

  /** SAFAccountType в схемата. */
  private static final Pattern SAF_ACCOUNT = Pattern.compile("[1-9][0-9]{2,4}");

  @Test
  @DisplayName("Сметкопланът на НАП: 360 кода, всички във формата на схемата")
  void сметкопланът() {
    assertEquals(360, BgNraNomenclatures.accounts().size());
    BgNraNomenclatures.accounts()
        .keySet()
        .forEach(code -> assertTrue(SAF_ACCOUNT.matcher(code).matches(), code));
    assertTrue(BgNraNomenclatures.accounts().get("411").startsWith("411"));
    assertTrue(BgNraNomenclatures.accounts().containsKey("122"));
    assertTrue(BgNraNomenclatures.accounts().containsKey("60909"));
  }

  @Test
  @DisplayName("TAX-IMP: 169 кода; „rate“ дословно (дроб, сума, Н/П); кавичките по RFC 4180")
  void данъците() {
    assertEquals(169, BgNraNomenclatures.taxCodes().size());
    BgNraNomenclatures.TaxCode vat = BgNraNomenclatures.taxCodes().get("100211");
    assertEquals("100", vat.taxType());
    assertEquals("0.2", vat.rate());
    // „rate“ е дословен: „Н/П“ при кредитните клетки, сума при хазарта, празно другаде.
    assertEquals("Н/П", BgNraNomenclatures.taxCodes().get("100331").rate());
    assertEquals("500", BgNraNomenclatures.taxCodes().get("500020").rate());
    assertTrue(
        BgNraNomenclatures.taxCodes().values().stream().anyMatch(tax -> tax.rate().isEmpty()),
        "книгата има кодове с празна ставка");
    // Описание с „""Живот""“ и запетаи вътре в кавичките.
    assertTrue(BgNraNomenclatures.taxCodes().get("700420").description().endsWith("\"Живот\""));
    assertFalse(BgNraNomenclatures.taxCodes().containsKey("000000"), "000000 е заместител");
  }

  @Test
  @DisplayName("Nom_Invoice_Types: 19 вида — с протоколите по 151в и отчета за продажби")
  void видовете_документи() {
    Map<String, String> types = BgNraNomenclatures.invoiceTypes();
    assertEquals(19, types.size());
    assertEquals("Фактура", types.get("01"));
    assertEquals("Митническа декларация", types.get("07"));
    assertEquals("Протокол или друг документ", types.get("09"));
    assertEquals("Отчет за извършените продажби", types.get("81"));
    assertTrue(types.keySet().containsAll(List.of("11", "12", "13", "91", "93", "94", "95")));
    assertFalse(
        types.containsKey("50"), "50 е в ППЗДДС Прил. № 12, но не и в номенклатурата на SAF-T");
  }

  @Test
  @DisplayName("Stock movement: 20 вида в реда на книгата; крайният интервал на 30 и 150 остава")
  void видовете_движения_на_запаси() {
    Map<String, String> types = BgNraNomenclatures.movementTypes();
    assertEquals(20, types.size());
    assertEquals("10", types.keySet().iterator().next(), "редът е този на книгата");
    assertEquals("Продажба ", types.get("30"));
    assertEquals("Безвъзмездно предоставени материални запаси ", types.get("150"));
    assertEquals("Вътрешен трансфер", types.get("80"));
    assertTrue(types.keySet().containsAll(List.of("65", "100", "101", "180")));
    assertThrows(UnsupportedOperationException.class, () -> types.put("190", "x"));
  }

  @Test
  @DisplayName("AssetMovementTypes: 13 вида; Nom_Inventory_Types: 5 вида")
  void видовете_за_активи_и_запаси() {
    Map<String, String> assets = BgNraNomenclatures.assetTransactionTypes();
    assertEquals(13, assets.size());
    assertEquals("Придобиване", assets.get("10"));
    assertEquals("Амортизация", assets.get("30"));
    assertEquals("Други транзакции", assets.get("130"));
    Map<String, String> inventory = BgNraNomenclatures.inventoryTypes();
    assertEquals(List.of("10", "20", "30", "40", "50"), new ArrayList<>(inventory.keySet()));
    assertEquals("Незавършено производство", inventory.get("40"));
  }

  @Test
  @DisplayName("Мерните единици: 2071 кода; TNE е метричният тон")
  void единиците() {
    assertEquals(2071, BgNraNomenclatures.unitsOfMeasure().size());
    assertEquals("бройка", BgNraNomenclatures.unitsOfMeasure().get("H87"));
    assertEquals("тон (метричен тон)", BgNraNomenclatures.unitsOfMeasure().get("TNE"));
  }

  @Test
  @DisplayName("КН8: 9897 кода с 00000000 за услуги, без кода 0")
  void кн8() {
    assertEquals(9897, BgNraNomenclatures.nc8Codes().size());
    assertTrue(BgNraNomenclatures.nc8Codes().contains("00000000"));
    assertTrue(BgNraNomenclatures.nc8Codes().contains("01012100"));
    assertFalse(BgNraNomenclatures.nc8Codes().contains("0"));
    BgNraNomenclatures.nc8Codes().forEach(code -> assertTrue(code.matches("[0-9]{8}"), code));
  }

  @Test
  @DisplayName("Nom_PaymentMethod: 3 метода, 9 двойки; механизмът е валиден само със своя метод")
  void методите_на_плащане() {
    Map<String, Set<String>> methods = BgNraNomenclatures.paymentMechanisms();
    assertEquals(List.of("01", "02", "03"), new ArrayList<>(methods.keySet()));
    assertEquals(Set.of("10"), methods.get("01"));
    assertEquals(Set.of("97", "98", "99"), methods.get("02"));
    assertEquals(Set.of("20", "42", "48", "68", "30"), methods.get("03"));
    assertEquals(9, methods.values().stream().mapToInt(Set::size).sum());
    assertThrows(UnsupportedOperationException.class, () -> methods.get("01").add("42"));
  }

  @Test
  @DisplayName("Редът по RFC 4180: запетая и удвоени кавички в поле; незатворени кавички спират")
  void редът() {
    assertArrayEquals(
        new String[] {"a", "b, \"c\"", "", "d"},
        BgNraNomenclatures.split("a,\"b, \"\"c\"\"\",,d", "x", 1));
    assertThrows(IllegalStateException.class, () -> BgNraNomenclatures.split("a,\"b", "x.csv", 7));
  }

  @Test
  @DisplayName("Променен заглавен ред спира четенето, вместо да чете колоните разместени")
  void заглавният_ред() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> BgNraNomenclatures.read(BgNraNomenclatures.ACCOUNTS, "nra_code,nra_name"));
    assertTrue(e.getMessage().contains("nra_nom_accounts.csv"));
    assertThrows(
        IllegalStateException.class,
        () -> BgNraNomenclatures.read(BgNraNomenclatures.DIR + "missing.csv", "a"));
  }
}
