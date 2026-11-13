package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.accounting.BgReportAccountMap.Acc;
import com.axelor.apps.bglocalization.service.accounting.BgReportAccountMap.Line;
import com.axelor.apps.bglocalization.service.accounting.BgReportAccountMap.Resolved;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Кое кой ред на справката взима — без база ({@link BgReportAccountMap}). */
class BgReportAccountMapTest {

  // Сметките на „Демо Търговия“: групови заглавия B040/C010/F010 и листата под тях.
  private static final List<Acc> ACCOUNTS =
      List.of(
          new Acc(1, "B040", null),
          new Acc(2, "503", 1L),
          new Acc(3, "504", 1L),
          new Acc(4, "5042", 3L), // открита от дружеството под 504
          new Acc(5, "50421", 4L), // и под нея още една
          new Acc(6, "C010", null),
          new Acc(7, "421", 6L),
          new Acc(8, "4211", 6L),
          new Acc(9, "F010", null),
          new Acc(10, "702", 9L),
          new Acc(11, "7021", 10L),
          new Acc(12, "706", 9L),
          new Acc(13, "60212", null),
          new Acc(14, "6021", null),
          new Acc(15, "9999", null));

  @Test
  @DisplayName(
      "Подсметка без свой ред влиза в реда на най-близкия изброен предшественик (5042, 50421 → 504)")
  void подсметката_отива_при_родителя() {
    Map<Long, Resolved> m =
        BgReportAccountMap.resolve(List.of(new Line(100, "503"), new Line(101, "504")), ACCOUNTS);
    assertEquals(Set.of(4L, 5L), m.get(101L).extra);
    assertTrue(m.get(100L).extra.isEmpty());
    assertTrue(m.get(101L).withStock.isEmpty() && m.get(101L).withoutStock.isEmpty());
  }

  @Test
  @DisplayName(
      "Сметка със свой ред не се пренася — 4211 остава в своя ред, дори родителят да е изброен")
  void сметка_със_свой_ред_не_се_мести() {
    List<Acc> accounts = List.of(new Acc(7, "421", null), new Acc(8, "4211", 7L));
    Map<Long, Resolved> m =
        BgReportAccountMap.resolve(List.of(new Line(200, "421"), new Line(201, "4211")), accounts);
    assertTrue(m.get(200L).extra.isEmpty(), "4211 не влиза втори път в 421");
    // 6021 съвпада с LIKE 602% — пряко, не е пренесена.
    Map<Long, Resolved> w = BgReportAccountMap.resolve(List.of(new Line(300, "602%")), ACCOUNTS);
    assertTrue(w.get(300L).extra.isEmpty());
  }

  @Test
  @DisplayName(
      "702@30 дели 702 по статии: редът с @30 — само със 30x, всеки друг ред с 702 — без тях;"
          + " подсметката 7021 се дели по същия начин")
  void складовите_статии_се_делят() {
    Map<Long, Resolved> m =
        BgReportAccountMap.resolve(
            List.of(
                new Line(400, "702"), // приходът
                new Line(401, "706"),
                new Line(402, "702@30,706@30"), // себестойността
                new Line(403, "703")),
            ACCOUNTS);
    assertEquals(Set.of(10L, 11L), m.get(400L).withoutStock);
    assertEquals(Set.of(12L), m.get(401L).withoutStock);
    assertEquals(Set.of(10L, 11L, 12L), m.get(402L).withStock);
    assertEquals(Set.of(11L), m.get(402L).extra);
    assertTrue(m.get(400L).withStock.isEmpty() && m.get(402L).withoutStock.isEmpty());
    assertTrue(m.get(403L).withoutStock.isEmpty(), "703 не е в ред с @30");
  }

  @Test
  @DisplayName("Справка без @30 (балансът) не дели нищо")
  void без_суфикс_нищо_не_се_дели() {
    Map<Long, Resolved> m =
        BgReportAccountMap.resolve(List.of(new Line(500, "702"), new Line(501, "706")), ACCOUNTS);
    assertTrue(m.get(500L).withoutStock.isEmpty());
    assertTrue(m.get(501L).withoutStock.isEmpty());
  }

  @Test
  @DisplayName("Токенът е LIKE: %, _ и суфиксът се махат преди търсенето")
  void токените() {
    assertEquals("702", BgReportAccountMap.pattern(" 702@30 "));
    assertTrue(BgReportAccountMap.isStock("706@30"));
    assertFalse(BgReportAccountMap.isStock("706"));
    assertTrue(BgReportAccountMap.like("601%", "6011"));
    assertTrue(BgReportAccountMap.like("60_", "609"));
    assertFalse(BgReportAccountMap.like("504", "5042"));
    assertFalse(BgReportAccountMap.like("5.4", "504"), "точката не е regex");
  }

  @Test
  @DisplayName("Условието в заявката: NOT IN / EXISTS със 30x; без деление — празно")
  void условието_в_заявката() {
    Map<Long, Resolved> m =
        BgReportAccountMap.resolve(
            List.of(new Line(400, "702"), new Line(402, "702@30")), ACCOUNTS);
    String revenue = BgAccountingReportValueMoveLineServiceImpl.stockScope(m.get(400L));
    String cogs = BgAccountingReportValueMoveLineServiceImpl.stockScope(m.get(402L));
    assertEquals(
        " AND (self.account.id NOT IN (10,11) OR NOT EXISTS (SELECT 1 FROM MoveLine bgStock"
            + " WHERE bgStock.move = self.move AND bgStock.account.code LIKE '30%'))",
        revenue);
    assertEquals(
        " AND (self.account.id NOT IN (10,11) OR EXISTS (SELECT 1 FROM MoveLine bgStock"
            + " WHERE bgStock.move = self.move AND bgStock.account.code LIKE '30%'))",
        cogs);
    assertEquals("", BgAccountingReportValueMoveLineServiceImpl.stockScope(null));
  }
}
