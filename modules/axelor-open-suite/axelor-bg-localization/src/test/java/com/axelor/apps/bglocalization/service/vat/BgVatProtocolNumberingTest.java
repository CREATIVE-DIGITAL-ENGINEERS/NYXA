package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.MoveRepository;
import org.junit.jupiter.api.Test;

/** Кога ДДС протоколът дължи номер и как изглежда номерът. Без база, без Guice. */
class BgVatProtocolNumberingTest {

  private static Move move(int status, String operation, String number) {
    Move move = new Move();
    move.setStatusSelect(status);
    move.setBgVatMoveVatOperation(operation);
    move.setBgVatProtocolNumber(number);
    return move;
  }

  @Test
  void anAccountedProtocolWithoutANumber_isDue() {
    for (String protocol :
        new String[] {
          "VOP",
          "SERVICE_EU",
          "SERVICE_NONEU",
          "ART163A",
          "IMPORT_167A",
          "CORRECTION_79",
          "DEDUCTION_79",
          "ANNUAL_79A",
          " VOP "
        }) {
      assertTrue(
          BgVatProtocolNumbering.isDue(move(MoveRepository.STATUS_ACCOUNTED, protocol, null)),
          protocol);
      assertTrue(
          BgVatProtocolNumbering.isDue(move(MoveRepository.STATUS_ACCOUNTED, protocol, " ")),
          protocol);
    }
  }

  @Test
  void aNumberIsNeverReplaced_andOnlyAnAccountedProtocolGetsOne() {
    // ЗДДС чл. 117а ал. 1: поправки в протокол не се разрешават.
    assertFalse(
        BgVatProtocolNumbering.isDue(move(MoveRepository.STATUS_ACCOUNTED, "VOP", "0000000001")));
    // Анулираният пази номера си, а неосчетоводеният още не получава.
    for (int status :
        new int[] {
          MoveRepository.STATUS_NEW,
          MoveRepository.STATUS_DAYBOOK,
          MoveRepository.STATUS_CANCELED,
          MoveRepository.STATUS_SIMULATED
        }) {
      assertFalse(BgVatProtocolNumbering.isDue(move(status, "VOP", null)), "статус " + status);
    }
    // Покупката по операция и статията без операция не са протоколи.
    for (String notProtocol : new String[] {"PURCHASE_FULL", "PURCHASE_ANNUAL", null, "FOO"}) {
      assertFalse(
          BgVatProtocolNumbering.isDue(move(MoveRepository.STATUS_ACCOUNTED, notProtocol, null)),
          String.valueOf(notProtocol));
    }
    assertFalse(BgVatProtocolNumbering.isDue(null));
  }

  @Test
  void aNumberIsEnteredByHand_onlyOnAnAccountedProtocolWithoutOne() {
    Move ok = move(MoveRepository.STATUS_ACCOUNTED, "VOP", null);
    assertEquals(null, BgVatProtocolNumbering.entryRefusal(ok, "0000000123"));
    assertEquals(null, BgVatProtocolNumbering.entryRefusal(ok, " ПР-2026-17 "));
    assertEquals(
        null,
        BgVatProtocolNumbering.entryRefusal(
            move(MoveRepository.STATUS_ACCOUNTED, "ANNUAL_79A", " "), "12345678901234567890"));

    assertEquals("Няма статия.", BgVatProtocolNumbering.entryRefusal(null, "1"));
    assertEquals(
        "Номерът на протокола се въвежда само на осчетоводена статия.",
        BgVatProtocolNumbering.entryRefusal(move(MoveRepository.STATUS_DAYBOOK, "VOP", null), "1"));
    assertEquals(
        "Статията не е ДДС протокол.",
        BgVatProtocolNumbering.entryRefusal(
            move(MoveRepository.STATUS_ACCOUNTED, "PURCHASE_FULL", null), "1"));
    // ЗДДС чл. 117а ал. 1: даденият номер не се мени — и ръчно също.
    assertEquals(
        "Протоколът вече има номер „0000000001“ — номерът не се мени (ЗДДС чл. 117а ал. 1).",
        BgVatProtocolNumbering.entryRefusal(
            move(MoveRepository.STATUS_ACCOUNTED, "VOP", "0000000001"), "0000000002"));
    assertEquals(
        "Въведете номера на издадения протокол.", BgVatProtocolNumbering.entryRefusal(ok, "  "));
    // Прил. № 12: „Номер на документ“ е символно поле от 20 знака.
    assertEquals(
        "Номерът е по-дълъг от 20 знака — „Номер на документ“ (02-05 / 03-06) е символно поле от"
            + " 20 знака.",
        BgVatProtocolNumbering.entryRefusal(ok, "123456789012345678901"));
  }

  @Test
  void theNumberIsTenArabicDigits() {
    assertTrue(BgVatProtocolNumbering.isWellFormed("0000000001"));
    assertTrue(BgVatProtocolNumbering.isWellFormed("1234567890"));
    assertFalse(BgVatProtocolNumbering.isWellFormed("000000001"));
    assertFalse(BgVatProtocolNumbering.isWellFormed("00000000001"));
    assertFalse(BgVatProtocolNumbering.isWellFormed("П-00000001"));
    assertFalse(BgVatProtocolNumbering.isWellFormed(null));
  }
}
