package com.axelor.apps.bglocalization.service.bnb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Amount;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Balance;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Period;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Rates;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Result;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Row;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Status;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Правилата на СПБ-6А/6Б върху измислени салда — без база. */
class BgBnbSpb6Test {

  private static final Period Q1 = Period.of(2026, 1);

  /** USD = 0,9 €, PLN = 0,25 €; другите — няма курс. */
  private static final Rates RATES =
      (currency, date) -> {
        switch (currency) {
          case "USD":
            return new BigDecimal("0.9");
          case "PLN":
            return new BigDecimal("0.25");
          default:
            return null;
        }
      };

  private final List<Balance> balances = new ArrayList<>();
  private long nextId = 1;

  private Balance b(
      String partner,
      String country,
      String account,
      String currency,
      String opening,
      String closing) {
    Balance b = new Balance(account, nextId++, partner, false, country, currency);
    b.opening = new BigDecimal(opening);
    b.closing = new BigDecimal(closing);
    balances.add(b);
    return b;
  }

  private Result run() {
    return BgBnbSpb6.build(Q1, balances, RATES);
  }

  private static String line(Row r) {
    return String.join(" ", r.type, r.sector, r.kind, r.country, r.currency, r.maturity)
        + " "
        + r.openingK()
        + " "
        + r.closingK();
  }

  private static List<String> lines(List<Row> rows) {
    return rows.stream().map(BgBnbSpb6Test::line).collect(Collectors.toList());
  }

  @Test
  @DisplayName(
      "Примерът от указанията (СПБ-6А, I тримесечие): 51→130, 44→210, 6→0; PL сливат L и S")
  void примерът_от_указанията() {
    b("Инвеститор DE", "DE", "411", "EUR", "51129", "130000");
    b("Свързано PL", "PL", "411", "PLN", "18520", "0");
    b("Свързано PL", "PL", "411", "PLN", "0", "253100");
    b("Клиент US 1", "US", "411", "USD", "14598", "208800");
    b("Клиент US 2", "US", "411", "USD", "29690", "1000");
    b("Клиент US 3", "US", "411", "EUR", "5750", "450");
    Result r = run();
    // Типът (D1/D3) и матуритетът (L) не са в счетоводството: по подразбиране D4 и S, затова
    // двата полски реда на образеца стават един — 19 → 253.
    assertEquals(
        List.of(
            "D4 S11 F81 DE EUR S 51 130",
            "D4 S11 F81 PL PLN S 19 253",
            "D4 S11 F81 US EUR S 6 0",
            "D4 S11 F81 US USD S 44 210"),
        lines(r.a.rows));
    assertTrue(r.b.rows.isEmpty());
    assertEquals(List.of("Клиент US 1", "Клиент US 2"), new ArrayList<>(r.a.rows.get(3).partners));
  }

  @Test
  @DisplayName("Знакът решава формата: надплатеното по 411 в края е задължение (6Б)")
  void знакът_решава_формата() {
    b("Клиент DE", "DE", "411", "EUR", "10000", "-5000");
    Result r = run();
    assertEquals(List.of("D4 S11 F81 DE EUR S 10 0"), lines(r.a.rows));
    assertEquals(List.of("D4 S11 F81 DE EUR S 0 5"), lines(r.b.rows));
  }

  @Test
  @DisplayName("Два партньора с противоположни салда не се нетират: 60 във 6А и 40 във 6Б, не 20")
  void противоположните_салда_не_се_нетират() {
    b("Клиент AT", "AT", "411", "EUR", "0", "60000");
    b("Друг клиент AT", "AT", "411", "EUR", "0", "-40000");
    Result r = run();
    assertEquals(List.of("D4 S11 F81 AT EUR S 0 60"), lines(r.a.rows));
    assertEquals(List.of("D4 S11 F81 AT EUR S 0 40"), lines(r.b.rows));
  }

  @Test
  @DisplayName("Доставчиците (401) и получените аванси (412) са задължения F81, 499 — F89")
  void задълженията() {
    b("Доставчик IT", "IT", "4011", "EUR", "-20000", "-30000");
    b("Клиент FR", "FR", "412", "EUR", "0", "-7000");
    b("Кредитор NL", "NL", "499", "EUR", "-1000", "-2000");
    Result r = run();
    assertEquals(
        List.of("D4 S11 F81 FR EUR S 0 7", "D4 S11 F81 IT EUR S 20 30", "D4 S11 F89 NL EUR S 1 2"),
        lines(r.b.rows));
    assertTrue(r.a.rows.isEmpty());
    assertTrue(r.b.hasOther());
    assertEquals(3 * 3 + 1, r.b.defaults());
  }

  @Test
  @DisplayName("Салдо по 405/415 — партньорът е на свой ред, белязан „вероятно свързано лице“")
  void свързаното_лице_е_на_свой_ред() {
    Balance mother = b("Майка DE", "DE", "415", "EUR", "0", "1000");
    Balance sales = new Balance("411", mother.partnerId, "Майка DE", false, "DE", "EUR");
    sales.closing = new BigDecimal("5000");
    balances.add(sales);
    b("Клиент DE", "DE", "411", "EUR", "0", "8000");
    Result r = run();
    assertEquals(2, r.a.rows.size());
    Row related = r.a.rows.stream().filter(x -> x.likelyRelated).findFirst().orElseThrow();
    assertEquals(List.of("Майка DE"), new ArrayList<>(related.partners));
    assertEquals(6, related.closingK());
    Row other = r.a.rows.stream().filter(x -> !x.likelyRelated).findFirst().orElseThrow();
    assertEquals(8, other.closingK());
  }

  @Test
  @DisplayName("Местните са вън; без държава, заем и непозната 4xx — в проверките; 45x — мълчи")
  void кое_не_влиза() {
    b("Клиент BG", "BG", "411", "EUR", "0", "900000");
    b("Без адрес", null, "411", "EUR", "0", "1000");
    b("Банка DE", "DE", "152", "EUR", "0", "-500000");
    b("Доставчик CZ", "CZ", "409", "EUR", "0", "-3000");
    b("Доставчик PL", "PL", "4532", "EUR", "0", "700");
    Result r = run();
    assertTrue(r.a.rows.isEmpty());
    assertTrue(r.b.rows.isEmpty());
    assertEquals(3, r.warnings.size(), r.warnings.toString());
    assertTrue(r.warnings.get(0).startsWith("Без държава"), r.warnings.get(0));
    assertTrue(r.warnings.get(1).startsWith("Финансов кредит"), r.warnings.get(1));
    assertTrue(r.warnings.get(2).contains("409"), r.warnings.get(2));
    assertTrue(r.details.isEmpty());
  }

  @Test
  @DisplayName("Прагът: точно 100 000 € в края — подава се; 99 999,99 и в двата края — не")
  void прагът() {
    b("Клиент DE", "DE", "411", "EUR", "0", "100000");
    assertEquals(Status.FILE, run().a.status());
    balances.clear();
    b("Клиент DE", "DE", "411", "EUR", "99999.99", "99999.99");
    Result r = run();
    assertEquals(Status.BELOW, r.a.status());
    assertEquals(0, new BigDecimal("99999.99").compareTo(r.a.closingEur));
  }

  @Test
  @DisplayName("Прагът е в евро по курса: 120 000 USD × 0,9 = 108 000 € — подава се")
  void прагът_е_по_курса() {
    b("Клиент US", "US", "411", "USD", "0", "120000");
    Result r = run();
    assertEquals(Status.FILE, r.a.status());
    assertEquals(0, new BigDecimal("108000").compareTo(r.a.closingEur));
  }

  @Test
  @DisplayName(
      "Без курс: под прага по наличното — „не е проверен“; над прага по наличното — подава се")
  void без_курс() {
    b("Клиент CH", "CH", "411", "CHF", "0", "50000");
    b("Клиент DE", "DE", "411", "EUR", "0", "50000");
    Result r = run();
    assertEquals(Status.UNKNOWN, r.a.status());
    assertNull(r.a.closingEur);
    assertEquals("[CHF към 2026-03-31]", r.a.missingRates.toString());
    balances.clear();
    b("Клиент CH", "CH", "411", "CHF", "0", "50000");
    b("Клиент DE", "DE", "411", "EUR", "150000", "0");
    assertEquals(Status.FILE, run().a.status());
  }

  @Test
  @DisplayName("Ред 0 и в началото, и в края (под 500) не се посочва, но влиза в прага")
  void нулевият_ред_отпада_но_се_брои() {
    b("Клиент SE", "SE", "411", "EUR", "400", "300");
    b("Клиент DE", "DE", "411", "EUR", "0", "99800");
    Result r = run();
    assertEquals(List.of("D4 S11 F81 DE EUR S 0 100"), lines(r.a.rows));
    assertEquals(Status.FILE, r.a.status());
  }

  @Test
  @DisplayName("Физическото лице е S14")
  void физическото_лице() {
    Balance b = new Balance("411", 99, "Лице GR", true, "GR", "EUR");
    b.closing = new BigDecimal("2000");
    balances.add(b);
    assertEquals(List.of("D4 S14 F81 GR EUR S 0 2"), lines(run().a.rows));
  }

  @Test
  @DisplayName("Хилядите са HALF_UP: 1 500 → 2, 1 499 → 1, 500 → 1, 499 → 0, −1 500 → −2")
  void хилядите() {
    assertEquals(2, BgBnbSpb6.thousands(new BigDecimal("1500")));
    assertEquals(1, BgBnbSpb6.thousands(new BigDecimal("1499.99")));
    assertEquals(1, BgBnbSpb6.thousands(new BigDecimal("500")));
    assertEquals(0, BgBnbSpb6.thousands(new BigDecimal("499.99")));
    assertEquals(-2, BgBnbSpb6.thousands(new BigDecimal("-1500")));
  }

  @Test
  @DisplayName("Видът е по най-дългия префикс; 409, 453 и 152 нямат вид")
  void видът() {
    assertEquals("F81", BgBnbSpb6.kindOf("411"));
    assertEquals("F81", BgBnbSpb6.kindOf("41101"));
    assertEquals("F81", BgBnbSpb6.kindOf("402"));
    assertEquals("F89", BgBnbSpb6.kindOf("4981"));
    assertNull(BgBnbSpb6.kindOf("409"));
    assertNull(BgBnbSpb6.kindOf("453"));
    assertNull(BgBnbSpb6.kindOf("152"));
  }

  @Test
  @DisplayName("Периодът: I тр. 2026 — 31.12.2025 до 31.03.2026, срок 20.04; IV — 25.01; годината")
  void периодът() {
    assertEquals(LocalDate.of(2025, 12, 31), Q1.opening);
    assertEquals(LocalDate.of(2026, 3, 31), Q1.closing);
    assertEquals(LocalDate.of(2026, 4, 20), Q1.deadline());
    Period q3 = Period.of(2026, 3);
    assertEquals(LocalDate.of(2026, 6, 30), q3.opening);
    assertEquals(LocalDate.of(2026, 9, 30), q3.closing);
    assertEquals(LocalDate.of(2026, 10, 20), q3.deadline());
    assertEquals(LocalDate.of(2027, 1, 25), Period.of(2026, 4).deadline());
    Period year = Period.of(2026, 0);
    assertEquals(LocalDate.of(2025, 12, 31), year.opening);
    assertEquals(LocalDate.of(2026, 12, 31), year.closing);
    assertNull(year.deadline());
    assertThrows(IllegalArgumentException.class, () -> Period.of(2025, 4));
    assertThrows(IllegalArgumentException.class, () -> Period.of(2026, 5));
  }

  private static BigDecimal[] v(String a, String b) {
    return new BigDecimal[] {new BigDecimal(a), new BigDecimal(b)};
  }

  @Test
  @DisplayName(
      "Валутата: левовете → евро по 1,95583; EUR до 2025 е валутна сума; USD без сума — проблем")
  void валутата() {
    Amount none = BgBnbSpb6.inCurrency(null, v("0", "0"), v("195583", "100"));
    assertEquals("EUR", none.currency);
    assertEquals(0, new BigDecimal("100100.00").compareTo(none.value));

    Amount bgn = BgBnbSpb6.inCurrency("BGN", v("1", "0"), v("1955.83", "0"));
    assertEquals("EUR", bgn.currency);
    assertEquals(0, new BigDecimal("1000.00").compareTo(bgn.value));

    Amount eur = BgBnbSpb6.inCurrency("EUR", v("50", "999"), v("97.79", "20"));
    assertEquals(0, new BigDecimal("70").compareTo(eur.value));

    Amount usd = BgBnbSpb6.inCurrency("USD", v("1000", "200"), v("1700", "180"));
    assertEquals("USD", usd.currency);
    assertEquals(0, new BigDecimal("1200").compareTo(usd.value));
    assertNull(usd.problem);

    Amount lost = BgBnbSpb6.inCurrency("USD", v("0", "0"), v("0", "500"));
    assertTrue(lost.problem != null && lost.problem.contains("USD"), String.valueOf(lost.problem));
  }
}
