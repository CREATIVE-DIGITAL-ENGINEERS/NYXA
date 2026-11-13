package com.axelor.apps.bglocalization.service.art49;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРАВИЛОТО НА ПЕРОТО ПО ЧЛ. 49 — периодът, посоката и името, без база и без Guice.
 *
 * <p>Тук няма нито едно число, което да се смята: колона 19 идва наготово. Проверява се само какво
 * се прави С НЕЯ.
 */
class BgArt49SettlementTest {

  // ═══ ПЕРИОДЪТ ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("януари носи изравняването, останалите единайсет месеца — не")
  void januaryOnly() {
    assertTrue(BgArt49Settlement.isSettlementPeriod(LocalDate.of(2027, 1, 1)));
    for (int month = 2; month <= 12; month++) {
      assertFalse(
          BgArt49Settlement.isSettlementPeriod(LocalDate.of(2027, month, 1)),
          "месец " + month + " не носи изравняване");
    }
  }

  @Test
  @DisplayName(
      "⚠️ ДЕКЕМВРИ НЕ носи изравняването — чл. 65 ал. 11 изр. 2 иска внасяне до 25 ФЕВРУАРИ")
  void decemberIsNotTheCarrier() {
    // Общото правило на чл. 65 ал. 11: данъкът се внася до 25-о число на месеца, СЛЕДВАЩ месеца на
    // удържане. Изр. 2 дава за чл. 49 ал. 5 срок 25 ФЕВРУАРИ — тоест удържане през ЯНУАРИ.
    // Декемврийско удържане би искало 25 януари и би противоречало на изричното изречение.
    assertFalse(BgArt49Settlement.isSettlementPeriod(LocalDate.of(2026, 12, 1)));
  }

  @Test
  @DisplayName("липсваща начална дата на периода се чете като „не“, а не като „винаги“")
  void nullPeriodStart() {
    assertFalse(BgArt49Settlement.isSettlementPeriod(null));
    assertNull(BgArt49Settlement.settledTaxYear(null));
  }

  @Test
  @DisplayName("януари 2027 изравнява 2026 — ПРЕДХОДНАТА година, не своята")
  void settledYearIsThePreviousOne() {
    assertEquals(2026, BgArt49Settlement.settledTaxYear(LocalDate.of(2027, 1, 1)));
    assertEquals(2027, BgArt49Settlement.settledTaxYear(LocalDate.of(2028, 1, 31)));
    assertNull(BgArt49Settlement.settledTaxYear(LocalDate.of(2027, 2, 1)));
  }

  // ═══ ПОСОКАТА ═════════════════════════════════════════════════════════════

  @Test
  @DisplayName("положителна колона 19 = довнасяне = DEBIT; отрицателна = възстановяване = CREDIT")
  void directionFollowsColumn19() {
    assertEquals(BgPaySlipResolver.DEBIT, BgArt49Settlement.sign(new BigDecimal("41.00")));
    assertEquals(BgPaySlipResolver.CREDIT, BgArt49Settlement.sign(new BigDecimal("-41.00")));
    assertTrue(BgArt49Settlement.isWithholding(new BigDecimal("0.01")));
    assertFalse(BgArt49Settlement.isWithholding(new BigDecimal("-0.01")));
  }

  @Test
  @DisplayName("⚠️ ПОСОКАТА Е ОБРАТНАТА НА deltaSign — и това е самото правило, не грешка")
  void oppositeOfRetroDeltaSign() {
    // Ретро-делтата е ПАРИ КЪМ ЛИЦЕТО: положителна значи „получава“, тоест CREDIT.
    // Колона 19 е ДАНЪК ОТ ЛИЦЕТО: положителна значи „дължи“, тоест DEBIT.
    // Едно и също число, два противни отговора — затова deltaSign НЕ се преизползва.
    BigDecimal positive = new BigDecimal("41.00");
    assertEquals(BgPaySlipResolver.CREDIT, BgPaySlipResolver.deltaSign(positive));
    assertEquals(BgPaySlipResolver.DEBIT, BgArt49Settlement.sign(positive));
    assertNotEquals(BgPaySlipResolver.deltaSign(positive), BgArt49Settlement.sign(positive));

    BigDecimal negative = new BigDecimal("-41.00");
    assertEquals(BgPaySlipResolver.DEBIT, BgPaySlipResolver.deltaSign(negative));
    assertEquals(BgPaySlipResolver.CREDIT, BgArt49Settlement.sign(negative));
  }

  @Test
  @DisplayName("нула и null падат към CREDIT, но перо с тях изобщо не се създава")
  void zeroIsNotWithholding() {
    // ⚠️ Стойността при нула няма отражение: генераторът връща законна нула ПРЕДИ да пита за знак.
    // Заковано е, за да не се чете липсата на случай като недоглеждане.
    assertFalse(BgArt49Settlement.isWithholding(BigDecimal.ZERO));
    assertFalse(BgArt49Settlement.isWithholding(null));
    assertEquals(BgPaySlipResolver.CREDIT, BgArt49Settlement.sign(BigDecimal.ZERO));
    assertEquals(BgPaySlipResolver.CREDIT, BgArt49Settlement.sign(null));
  }

  // ═══ ИМЕТО ════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("името носи ГОДИНАТА и ПОСОКАТА — фишът се подписва")
  void nameCarriesYearAndDirection() {
    assertEquals(
        "Годишно изравняване по чл. 49 ЗДДФЛ за 2026 (доплащане)",
        BgArt49Settlement.lineName(2026, new BigDecimal("41.00")));
    assertEquals(
        "Годишно изравняване по чл. 49 ЗДДФЛ за 2026 (възстановяване)",
        BgArt49Settlement.lineName(2026, new BigDecimal("-41.00")));
  }
}
