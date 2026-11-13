package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛОВАТА половина за скаларните параметри: вярно ли е ЗАПИСАНО. Без Postgres, без App Install.
 *
 * <p>Двойник на {@code BgPayrollParameterManifestTest}, който пита ДОСТАВЕНО ли е. Разликата между
 * двата въпроса е точно разликата между „сгрешена ставка“ и „не е пускан App Install“ — а слети в
 * един тест, второто маскира първото.
 */
class BgPayrollParameterSeedFileTest {

  private static final String MANIFEST = "/apps/init-data/manifest/payroll-parameter.yml";
  private static final String FILE = "bg_payroll_parameter.csv";

  @SuppressWarnings("unchecked")
  private static Map<String, Object> expected() throws Exception {
    try (InputStream in = BgPayrollParameterSeedFileTest.class.getResourceAsStream(MANIFEST)) {
      Assumptions.assumeTrue(in != null, "манифестът " + MANIFEST + " не е в classpath-а");
      Map<String, Object> m = new ObjectMapper(new YAMLFactory()).readValue(in, Map.class);
      return (Map<String, Object>) m.get("очаквано");
    }
  }

  private static BigDecimal dec(Object v) {
    return new BigDecimal(String.valueOf(v)).setScale(4);
  }

  private static void check(String what, Object want, Object got) {
    if (!String.valueOf(want).equals(String.valueOf(got))) {
      fail(
          "манифест „"
              + what
              + "“ не е изпълнен.\n  очаква се : "
              + want
              + "\n  намерено  : "
              + got);
    }
  }

  /** Редовете, подредени по код и после по начална дата — както ги чете и базовият двойник. */
  private static Map<String, List<String[]>> byCode() {
    List<String> h = BgSeed.header(FILE);
    Map<String, List<String[]>> out = new LinkedHashMap<>();
    List<String[]> rows = new ArrayList<>(BgSeed.rows(FILE));
    rows.sort((a, b) -> BgSeed.col(h, a, "validFrom").compareTo(BgSeed.col(h, b, "validFrom")));
    for (String[] r : rows) {
      out.computeIfAbsent(BgSeed.col(h, r, "code"), k -> new ArrayList<>()).add(r);
    }
    return out;
  }

  @Test
  @DisplayName("ФАЙЛ · записаните параметри отговарят на манифеста")
  @SuppressWarnings("unchecked")
  void theSeedFileMatchesTheManifest() throws Exception {
    Map<String, Object> exp = expected();
    List<String> h = BgSeed.header(FILE);
    List<String[]> rows = BgSeed.rows(FILE);
    Map<String, List<String[]>> byCode = byCode();

    check("редове", exp.get("редове"), rows.size());
    check("различни кода", exp.get("различни_кода"), byCode.size());

    Map<String, Object> codes = (Map<String, Object>) exp.get("кодове");

    // Кодове в манифеста, които ги няма в seed-а — и обратното. Без тази проверка добавен код би
    // минал незабелязано, стига броят да съвпадне случайно.
    Set<String> onlyInSeed = new LinkedHashSet<>(byCode.keySet());
    onlyInSeed.removeAll(codes.keySet());
    Set<String> onlyInManifest = new LinkedHashSet<>(codes.keySet());
    onlyInManifest.removeAll(byCode.keySet());
    if (!onlyInSeed.isEmpty() || !onlyInManifest.isEmpty()) {
      fail(
          "кодовете се разминават.\n  само в seed-а    : "
              + onlyInSeed
              + "\n  само в манифеста : "
              + onlyInManifest);
    }

    for (Map.Entry<String, Object> e : codes.entrySet()) {
      Map<String, Object> want = (Map<String, Object>) e.getValue();
      List<String[]> got = byCode.get(e.getKey());

      check("версии на " + e.getKey(), want.get("версии"), got.size());

      List<String> wantedValues = new ArrayList<>();
      for (Object v : (List<Object>) want.get("стойности")) {
        wantedValues.add(dec(v).toPlainString());
      }
      List<String> gotValues = new ArrayList<>();
      for (String[] r : got) {
        gotValues.add(dec(BgSeed.col(h, r, "paramValue")).toPlainString());
      }
      check(
          "стойности на " + e.getKey(),
          String.join(", ", wantedValues),
          String.join(", ", gotValues));

      for (String[] r : got) {
        check("мерна единица на " + e.getKey(), want.get("единица"), BgSeed.col(h, r, "unit"));
      }
    }

    // Границата на двете полугодия — една и съща за четирите двойки.
    Map<String, Object> pairs = (Map<String, Object>) exp.get("двойки");
    for (String code : (List<String>) pairs.get("кодове")) {
      List<String> periods = new ArrayList<>();
      for (String[] r : byCode.get(code)) {
        String to = BgSeed.col(h, r, "validTo");
        periods.add(BgSeed.col(h, r, "validFrom") + " | " + (to.isEmpty() ? "null" : to));
      }
      check(
          "периодите на " + code,
          "2026-01-01 | 2026-07-31\n2026-08-01 | null",
          String.join("\n", periods));
    }

    // ⚠️ Всеки ред носи основание. Стойност без произход е число, което никой не може да защити.
    List<String> withoutBasis = new ArrayList<>();
    for (String[] r : rows) {
      if (BgSeed.col(h, r, "description").trim().isEmpty()) {
        withoutBasis.add(BgSeed.col(h, r, "code") + " от " + BgSeed.col(h, r, "validFrom"));
      }
    }
    if (!withoutBasis.isEmpty()) {
      fail(
          "параметри БЕЗ основание ("
              + withoutBasis.size()
              + "):\n  "
              + String.join("\n  ", withoutBasis));
    }
    check("редове с описание", exp.get("редове_с_описание"), rows.size() - withoutBasis.size());

    // Двата пода, които се разминават седем месеца — за да не ги „поправи“ някой ден.
    Map<String, Object> floors = (Map<String, Object>) exp.get("двата_пода");
    check(
        "МРЗ от 01.01.2026",
        dec(floors.get("MIN_WAGE_от_01_01")).toPlainString(),
        dec(BgSeed.col(h, byCode.get("MIN_WAGE").get(0), "paramValue")).toPlainString());
    check(
        "МОД за самоосигуряващи се до 31.07.2026",
        dec(floors.get("MIN_INCOME_SOL_до_31_07")).toPlainString(),
        dec(BgSeed.col(h, byCode.get("MIN_INCOME_SOL").get(0), "paramValue")).toPlainString());
  }

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ двете данъчни ставки важат ЕДНОВРЕМЕННО — различават се по доход, не по период")
  void theTwoIncomeTaxRatesOverlapInsteadOfSucceedingEachOther() {
    List<String> h = BgSeed.header(FILE);
    Map<String, List<String[]>> byCode = byCode();

    // ЗАЩО ОТДЕЛЕН ТЕСТ. Четири кода в този каталог имат по ДВЕ версии, които се СМЕНЯТ на
    // 01.08.2026 — там втората отменя първата. Данъчните ставки изглеждат същото, а не са:
    // чл. 42 ал. 4 ЗДДФЛ дава 10 на сто за обикновения доход и 1 на сто „за доходите, придобити
    // като морско лице“, и двете важат за ЕДИН И СЪЩ ден. Свие ли ги някой в един код с две версии
    // по validFrom, ще получи еднозначно грешен отговор за всяка дата.
    List<String> lines = new ArrayList<>();
    for (String code : new String[] {"INCOME_TAX_RATE", "INCOME_TAX_RATE_SEAFARER"}) {
      List<String[]> got = byCode.get(code);
      if (got == null) {
        fail("липсва код " + code + " в " + FILE);
      }
      for (String[] r : got) {
        String to = BgSeed.col(h, r, "validTo");
        lines.add(
            code
                + " | "
                + dec(BgSeed.col(h, r, "paramValue")).toPlainString()
                + " | "
                + BgSeed.col(h, r, "validFrom")
                + " | "
                + (to.isEmpty() ? "null" : to));
      }
    }

    check(
        "двете данъчни ставки",
        "INCOME_TAX_RATE | 10.0000 | 2026-01-01 | null\n"
            + "INCOME_TAX_RATE_SEAFARER | 1.0000 | 2026-01-01 | null",
        String.join("\n", lines));

    // ⚠️ И ЧЕ НАИСТИНА СЕ ПРИПОКРИВАТ. Горното би минало и ако някой ден датите се разминат така,
    // че двете да не важат едновременно — тогава редът за морско лице би бил мъртъв, а тестът
    // зелен.
    String[] a = byCode.get("INCOME_TAX_RATE").get(0);
    String[] b = byCode.get("INCOME_TAX_RATE_SEAFARER").get(0);
    String aTo = BgSeed.col(h, a, "validTo");
    String bTo = BgSeed.col(h, b, "validTo");
    boolean overlap =
        BgSeed.col(h, a, "validFrom").compareTo(bTo.isEmpty() ? "9999-12-31" : bTo) <= 0
            && BgSeed.col(h, b, "validFrom").compareTo(aTo.isEmpty() ? "9999-12-31" : aTo) <= 0;
    if (!overlap) {
      fail(
          "двете данъчни ставки НЕ се припокриват по период — при чл. 42 ал. 4 ЗДДФЛ те важат"
              + " едновременно и се различават по вида доход, а не по дата");
    }
  }
}
