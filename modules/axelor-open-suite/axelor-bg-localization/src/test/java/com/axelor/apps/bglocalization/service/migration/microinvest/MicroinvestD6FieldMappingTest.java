package com.axelor.apps.bglocalization.service.migration.microinvest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgD6Declaration;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic Dekl6 -&gt; BgD6Declaration field mapping, including the four-"section"-per-field
 * merge described in {@link MicroinvestD6FieldMapping}'s class javadoc.
 */
class MicroinvestD6FieldMappingTest {

  @Test
  @DisplayName("an identity field takes the first non-blank section")
  void takesFirstNonBlankSectionForStringField() {
    BgD6Declaration declaration = new BgD6Declaration();
    List<String> sections = Arrays.asList(null, "ТЕСТЕКС ООД", "ТЕСТЕКС ООД", null);
    assertTrue(
        MicroinvestD6FieldMapping.apply(declaration, "Наименование на осигурителя", sections));
    assertEquals("ТЕСТЕКС ООД", declaration.getT2Name());
  }

  @Test
  @DisplayName("an amount field takes the first non-zero section, skipping zero/blank sections")
  void takesFirstNonZeroSectionForAmountField() {
    BgD6Declaration declaration = new BgD6Declaration();
    List<String> sections = Arrays.asList("0", "373.90", null, "");
    MicroinvestD6FieldMapping.apply(declaration, "Дължими вноски за ДОО", sections);
    assertEquals(0, new BigDecimal("373.90").compareTo(declaration.getT10Doo()));
  }

  @Test
  @DisplayName("the payment date field parses the dd.MM.yyyy Bulgarian date format")
  void parsesPaymentDate() {
    BgD6Declaration declaration = new BgD6Declaration();
    MicroinvestD6FieldMapping.apply(
        declaration, "Дата на изплащане", Arrays.asList("31.07.2026", "31.07.2026", null, null));
    assertEquals(LocalDate.of(2026, 7, 31), declaration.getT18PaymentDate());
  }

  @Test
  @DisplayName("all-blank sections and an unknown field name leave the record untouched")
  void doesNothingForBlankOrUnknownField() {
    BgD6Declaration declaration = new BgD6Declaration();
    assertTrue(
        MicroinvestD6FieldMapping.apply(
            declaration, "Дължими вноски за ДОО", Arrays.asList(null, "", "0", null)));
    assertFalse(
        MicroinvestD6FieldMapping.apply(
            declaration, "Флаг за вид плащане", Arrays.asList("52", "8", null, null)));
    // ⚠️ Same defaulted-getter behavior as BgD1Record -- see MicroinvestD1FieldMappingTest.
    assertTrue(BigDecimal.ZERO.compareTo(declaration.getT10Doo()) == 0);
    assertNull(declaration.getT81PaymentKind());
  }
}
