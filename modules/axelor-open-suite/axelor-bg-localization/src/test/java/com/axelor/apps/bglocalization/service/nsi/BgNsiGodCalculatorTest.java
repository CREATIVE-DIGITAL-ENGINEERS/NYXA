package com.axelor.apps.bglocalization.service.nsi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.nsi.BgNsiGodCalculator.Figures;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodCalculator.Result;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Правилата на редовете върху измислени цифри по сметки. */
class BgNsiGodCalculatorTest {

  private final BgNsiGodForm form = BgNsiGodForm.forYear(2025);
  private final Map<String, Figures> data = new HashMap<>();

  private Figures f(String account) {
    return data.computeIfAbsent(account, k -> new Figures());
  }

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  private Result run() {
    return BgNsiGodCalculator.compute(form, data);
  }

  private static void eq(String expected, Result r, String code) {
    BigDecimal actual = r.values.get(code);
    assertEquals(
        0,
        d(expected).compareTo(actual == null ? BigDecimal.ZERO : actual),
        code + ": очаквано " + expected + ", а е " + actual);
  }

  @Test
  @DisplayName("702: приходите са кредитният оборот, себестойността (статии с 30x) — в 10500/10510")
  void стоките_не_са_марж() {
    Figures s = f("702");
    s.tc = d("50000");
    s.td = d("32000"); // 30000 себестойност + 2000 кредитно известие
    s.tdStock = d("30000");
    Result r = run();
    eq("48000", r, "15120");
    eq("30000", r, "10500");
    eq("30000", r, "10510");
    assertTrue(r.errors.isEmpty(), r.errors.toString());
  }

  @Test
  @DisplayName(
      "706: продажбата е приход, себестойността — разход; 705 е нетен Кт оборот, без разход")
  void материали_и_дълготрайни_активи() {
    Figures m = f("706");
    m.tc = d("900");
    m.td = d("700");
    m.tdStock = d("700");
    Figures a = f("705");
    a.tc = d("5000");
    a.td = d("3000"); // в BG_COA балансовата стойност е в 60908, дебитът по 705 е сторно
    f("60908").td = d("1200");
    Result r = run();
    eq("900", r, "15420");
    eq("2000", r, "15430");
    eq("2900", r, "15400");
    eq("1900", r, "10510"); // 706 себестойност 700 + 60908 1200, без 705
  }

  @Test
  @DisplayName("14200: Кт 4521 и изменението на 494 срещу 123/69x; Дт 122 / Кт 4521 — вън")
  void данъкът_по_насрещна_сметка() {
    Figures tax = f("4521");
    tax.tc = d("900"); // 700 срещу 123 + 200 срещу 122 (минали години)
    tax.td = d("100");
    tax.against.put("123+69", new BigDecimal[] {d("100"), d("700")}); // сторно 100 срещу 123
    Figures deferred = f("494");
    deferred.against.put("123+69", new BigDecimal[] {BigDecimal.ZERO, d("150")});
    Result r = run();
    eq("750", r, "14200"); // 700 − 100 + 150
  }

  @Test
  @DisplayName("15300: само Дт 613 срещу 60x/61x, без доставеното от доставчици")
  void придобиване_по_стопански_начин() {
    Figures own = f("613");
    own.td = d("6000"); // 4000 срещу 60x + 2000 срещу 401
    own.tc = d("6000");
    own.against.put("60+61", new BigDecimal[] {d("4000"), BigDecimal.ZERO});
    Result r = run();
    eq("4000", r, "15300");
  }

  @Test
  @DisplayName("Д/К по знака: дебитното салдо — в актива, кредитното — в пасива")
  void дк_по_знака() {
    f("411").bal = d("57600");
    f("412").bal = d("-7000");
    f("401").bal = d("-48000");
    f("4011").bal = d("250"); // надплатен доставчик
    Result r = run();
    eq("57600", r, "03210");
    eq("7000", r, "07301");
    eq("48000", r, "07401");
    eq("250", r, "03240");
  }

  @Test
  @DisplayName("ДДС по подсметка: 4538 — в актива, 4539 — в пасива; само 4531/4532 се нетират")
  void ддс_по_подсметка() {
    f("4538").bal = d("500");
    f("4539").bal = d("-1600");
    f("4531").bal = d("800");
    f("4532").bal = d("-1000");
    Result r = run();
    eq("500", r, "03240");
    eq("1800", r, "07801"); // 4539 1600 + нетото на 4531/4532 200
    eq("1800", r, "07831");
  }

  @Test
  @DisplayName(
      "Най-дългият префикс печели: 6044 → 10500, 60604 → 14300, 6094 → 10320, 6051 и в „от тях“")
  void най_дългият_префикс() {
    f("6044").td = d("100");
    f("60604").td = d("40");
    f("6094").td = d("30");
    f("6051").td = d("2000");
    f("6041").td = d("9000");
    Result r = run();
    eq("9000", r, "10310");
    eq("100", r, "10500");
    eq("40", r, "14300");
    eq("2030", r, "10320");
    eq("2000", r, "10321");
  }

  @Test
  @DisplayName("Изменение на запасите: увеличение → 15200, намаление → 10100")
  void изменение_на_запасите() {
    Figures p = f("303");
    p.bal = d("5000");
    p.balPrev = d("2000");
    f("611").bal = d("-500");
    Result r = run();
    eq("2500", r, "15200");
    assertNull(r.values.get("10100"));

    data.clear();
    Figures q = f("303");
    q.bal = d("1000");
    q.balPrev = d("4000");
    Result r2 = run();
    eq("3000", r2, "10100");
    assertNull(r2.values.get("15200"));
  }

  @Test
  @DisplayName("Текущата печалба в баланса: 123 плюс неприключените 6xx/7xx, без 651 и 611")
  void текущата_печалба() {
    f("123").bal = d("600");
    f("702").bal = d("-18000");
    f("604").bal = d("20000");
    f("651").bal = d("300");
    f("611").bal = d("700");
    Result r = run();
    eq("-2600", r, "05600");
    eq("300", r, "04000");
    eq("700", r, "03120");
  }

  @Test
  @DisplayName("Сметка без ред и сметка на застраховател са ГРЕШКА; 9000/9010 се неутрализират")
  void грешките() {
    f("8000").bal = d("5");
    f("131").bal = d("10");
    f("9000").bal = d("-100");
    f("9010").bal = d("100");
    f("761").tc = d("1");
    Result r = run();
    assertEquals(3, r.errors.size(), r.errors.toString());
    assertTrue(r.errors.stream().anyMatch(e -> e.contains("8000")));
    assertTrue(r.errors.stream().anyMatch(e -> e.contains("131")));
    assertTrue(r.errors.stream().anyMatch(e -> e.contains("761")));

    f("9010").bal = d("90");
    assertTrue(run().errors.stream().anyMatch(e -> e.contains("9000+9010")));
  }

  @Test
  @DisplayName("Обороти по калкулационна сметка — предупреждение, не стойност")
  void калкулационните_сметки() {
    f("611").td = d("100");
    Result r = run();
    assertTrue(r.errors.isEmpty());
    assertFalse(r.warnings.isEmpty());
  }

  @Test
  @DisplayName("Закръгляне до хиляда: HALF_UP, отрицателното — симетрично")
  void закръгляне() {
    assertEquals(d("2"), BgNsiGodService.thousands(d("1500.00")));
    assertEquals(d("1"), BgNsiGodService.thousands(d("1499.99")));
    assertEquals(d("-2"), BgNsiGodService.thousands(d("-1500")));
    assertEquals(d("0"), BgNsiGodService.thousands(null));
  }

  @Test
  @DisplayName("По партньор: два клиента с противоположни салда по 411 не се нетират")
  void два_партньора_по_една_сметка() {
    Figures c = f("411");
    c.bal = d("700");
    c.balParts = java.util.List.of(d("1000"), d("-300"));
    Result r = run();
    eq("1000", r, "03210");
    eq("300", r, "07301");
  }

  @Test
  @DisplayName("По банкова сметка: кредитното салдо на една банка е задължение към банка (07201)")
  void банка_с_кредитно_салдо() {
    Figures bank = f("503");
    bank.bal = d("3000");
    bank.balParts = java.util.List.of(d("5000"), d("-2000"));
    Result r = run();
    eq("5000", r, "03413");
    eq("2000", r, "07201");
  }

  @Test
  @DisplayName(
      "Режимите на разделяне идват от образеца: 4xx по партньор, 503/504 по банка, 45x/46x по подсметка")
  void режимите_на_разделяне() {
    assertEquals(BgNsiGodForm.Split.PARTNER, form.splitMode("411"));
    assertEquals(BgNsiGodForm.Split.PARTNER, form.splitMode("4011"));
    assertEquals(BgNsiGodForm.Split.PARTNER, form.splitMode("499"));
    assertEquals(BgNsiGodForm.Split.BANK, form.splitMode("503"));
    assertEquals(BgNsiGodForm.Split.BANK, form.splitMode("5041"));
    assertEquals(BgNsiGodForm.Split.ACCOUNT, form.splitMode("4539"));
    assertEquals(BgNsiGodForm.Split.ACCOUNT, form.splitMode("461"));
    assertEquals(BgNsiGodForm.Split.ACCOUNT, form.splitMode("501"));
    assertEquals("p7", BgNsiGodService.part(BgNsiGodForm.Split.PARTNER, "7|3|9"));
    assertEquals("b3", BgNsiGodService.part(BgNsiGodForm.Split.BANK, "7|3|9"));
    // Без банкова сметка на статията и без еднозначна банкова сметка — по дневника, както досега.
    assertEquals("j9", BgNsiGodService.part(BgNsiGodForm.Split.BANK, "7||9"));
    assertEquals("", BgNsiGodService.part(BgNsiGodForm.Split.ACCOUNT, "7|3|9"));
  }

  @Test
  @DisplayName(
      "Банкова сметка: статията без банкова сметка (откриване, преоценка) отива към единствената"
          + " банкова сметка на дружеството с тази счетоводна сметка, не в отделна част по дневник")
  void статия_без_банкова_сметка_е_в_своята_банка() {
    // Дружеството: 11 → 503 (дневник 90), 12 → 504 (90), 13 → 5042 (90), 14 и 15 → 506 (91, 92).
    BgNsiGodService.Banks banks =
        new BgNsiGodService.Banks(
            java.util.List.of(
                new Object[] {11L, 90L, "503"},
                new Object[] {12L, 90L, "504"},
                new Object[] {13L, 90L, "5042"},
                new Object[] {14L, 91L, "506"},
                new Object[] {15L, 92L, "506"}));
    BgNsiGodForm.Split bank = BgNsiGodForm.Split.BANK;
    // Откриването (дневник 77) и плащанията (90) по 503 — една и съща банка.
    assertEquals("b11", BgNsiGodService.part(bank, "||77", "503", banks));
    assertEquals("b11", BgNsiGodService.part(bank, "||90", "503", banks));
    assertEquals("b11", BgNsiGodService.part(bank, "|11|90", "503", banks));
    assertEquals("b13", BgNsiGodService.part(bank, "||77", "5042", banks));
    // Две банкови сметки на 506 — решава дневникът, ако е само на едната.
    assertEquals("b15", BgNsiGodService.part(bank, "||92", "506", banks));
    assertEquals("j77", BgNsiGodService.part(bank, "||77", "506", banks));
    // Демо Търговия: 503 откриване +150 000, плащания −47 011,80 → едно дебитно салдо.
    Figures f = new Figures();
    Map<String, BigDecimal> parts = new java.util.LinkedHashMap<>();
    parts.merge(BgNsiGodService.part(bank, "||77", "503", banks), d("150000"), BigDecimal::add);
    parts.merge(BgNsiGodService.part(bank, "||90", "503", banks), d("-47011.80"), BigDecimal::add);
    f.bal = d("102988.20");
    f.balParts = new java.util.ArrayList<>(parts.values());
    Map<String, Figures> fig = new HashMap<>();
    fig.put("503", f);
    Result r = BgNsiGodCalculator.compute(form, fig);
    eq("102988.20", r, "03413");
    assertNull(r.values.get("07201"), "няма кредит от банка");
  }
}
