package com.axelor.apps.bglocalization.service.etz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContractTermination;
import com.axelor.apps.hr.db.EmploymentContract;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРЕКРАТЯВАНЕТО БЕЗ БАЗА — номенклатурите едно към едно, видовете договор, файлът за прекратяване,
 * отказът по 1в и срокът.
 *
 * <p>Таблицата на т. 27 по-долу е преписана от Наредбата (НВРЗ, Приложение № 1, т. 27 — „При
 * прекратяване на трудово правоотношение се попълва код“, консолидиран текст на НАП ред.
 * 24.06.2026), код по код. Тя е второто, независимо копие: ако някой код в {@link BgEtzGrounds} се
 * размести, тестът го хваща.
 */
class BgEtzTerminationPathTest {

  /** НВРЗ, Прил. № 1, т. 27, трудово правоотношение — 64 кода, дословно до пунктуацията. */
  private static final Map<String, String> ORDINANCE_T27 = new LinkedHashMap<>();

  static {
    String[] rows = {
      "001 чл. 71 ал. 1 КТ",
      "002 чл. 325 ал. 1 т. 1 КТ",
      "003 чл. 325 ал. 1 т. 2 КТ",
      "004 чл. 325 ал. 1 т. 3 КТ",
      "005 чл. 325 ал. 1 т. 4 КТ",
      "006 чл. 325 ал. 1 т. 5 КТ",
      "007 чл. 325 ал. 1 т. 6 КТ",
      "008 чл. 325 ал. 1 т. 8 КТ",
      "009 чл. 325 ал. 1 т. 9 КТ",
      "010 чл. 325 ал. 1 т. 10 КТ",
      "011 чл. 325 ал. 1 т. 11 КТ",
      "012 чл. 325 ал. 1 т. 12 КТ",
      "013 чл. 325 ал. 2 КТ",
      "014 чл. 326 ал. 1 КТ",
      "015 чл. 327 ал. 1 т. 1 КТ",
      "016 чл. 327 ал. 1 т. 2 КТ",
      "017 чл. 327 ал. 1 т. 3 КТ",
      "018 чл. 327 ал. 1 т. 3а КТ",
      "019 чл. 327 ал. 1 т. 4 КТ",
      "020 чл. 327 ал. 1 т. 6 КТ",
      "021 чл. 327 ал. 1 т. 7 КТ",
      "022 чл. 327 ал. 1 т. 7а КТ",
      "023 чл. 327 ал. 1 т. 8 КТ",
      "024 чл. 327 ал. 1 т. 9 КТ",
      "025 чл. 327 ал. 1 т. 10 КТ",
      "026 чл. 327 ал. 1 т. 11 КТ",
      "027 чл. 327 ал. 1 т. 12 КТ",
      "028 чл. 328 ал. 1 т. 1 КТ",
      "029 чл. 328 ал. 1 т. 2 КТ",
      "030 чл. 328 ал. 1 т. 3 КТ",
      "031 чл. 328 ал. 1 т. 4 КТ",
      "032 чл. 328 ал. 1 т. 5 КТ",
      "033 чл. 328 ал. 1 т. 6 КТ",
      "034 чл. 328 ал. 1 т. 7 КТ",
      "035 чл. 328 ал. 1 т. 8 КТ",
      "036 чл. 328 ал. 1 т. 10 КТ",
      "037 чл. 328 ал. 1 т. 10а КТ",
      "038 чл. 328 ал. 1 т. 10б КТ",
      "039 чл. 328 ал. 1 т. 10в КТ",
      "040 чл. 328 ал. 1 т. 11 КТ",
      "041 чл. 328 ал. 1 т. 12 КТ",
      "042 чл. 328 ал. 2 КТ",
      "043 чл. 330 ал. 1 КТ",
      "044 чл. 330 ал. 2 т. 1 КТ",
      "045 чл. 330 ал. 2 т. 2 КТ",
      "046 чл. 330 ал. 2 т. 3 КТ",
      "047 чл. 330 ал. 2 т. 5 КТ",
      "048 чл. 330 ал. 2 т. 6 КТ",
      "049 чл. 330 ал. 2 т. 7 КТ",
      "050 чл. 330 ал. 2 т. 8 КТ",
      "051 чл. 330 ал. 2 т. 9 КТ",
      "052 чл. 330 ал. 2 т. 10 КТ",
      "053 чл. 330 ал. 2 т. 11 КТ",
      "054 чл. 331 КТ",
      "055 чл. 334 ал. 1 КТ",
      "056 чл. 337 КТ",
      "057 чл. 338 КТ",
      "058 чл. 19а ал. 2 от Закона за администрацията",
      "059 чл. 19а ал. 3 от Закона за администрацията",
      "060 чл. 340а ал. 5 т. 1 от Закона за съдебната власт",
      "061 чл. 340а ал. 5 т. 2 от Закона за съдебната власт",
      "062 чл. 340а ал. 5 т. 3 от Закона за съдебната власт",
      "063 чл. 340а ал. 6 от Закона за съдебната власт",
      "064 друго"
    };
    for (String row : rows) {
      ORDINANCE_T27.put(row.substring(0, 3), row.substring(4));
    }
  }

  // ── т. 27: 64 кода, едно към едно ────────────────────────────────────────

  @Test
  @DisplayName("т. 27, трудово: 64 кода по Наредбата; текстът на всеки съвпада с таблицата в кода")
  void endCodesAreTheOrdinance() {
    assertEquals(64, ORDINANCE_T27.size());
    assertEquals(ORDINANCE_T27.keySet(), BgEtzGrounds.endCodesKt().keySet(), "същите 64 кода");
    for (Map.Entry<String, String> e : ORDINANCE_T27.entrySet()) {
      assertEquals(e.getValue(), BgEtzGrounds.endCodesKt().get(e.getKey()), "код " + e.getKey());
    }
  }

  @Test
  @DisplayName(
      "Изборът на основание покрива ВСЕКИ от 64-те кода точно веднъж, и стойността назовава същия"
          + " член, който кодът носи")
  void everyEndCodeHasExactlyOneGroundValue() {
    Map<String, List<String>> valuesByCode = new LinkedHashMap<>();
    for (Map.Entry<String, String> e : BgEtzGrounds.terminationGroundCodes().entrySet()) {
      if (BgEtzGrounds.isEndCode(e.getValue(), "01")) {
        valuesByCode.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
      }
    }
    assertEquals(ORDINANCE_T27.keySet(), valuesByCode.keySet(), "всеки код има стойност");
    for (Map.Entry<String, List<String>> e : valuesByCode.entrySet()) {
      assertEquals(1, e.getValue().size(), "код " + e.getKey() + " → " + e.getValue());
      String value = e.getValue().get(0);
      String text = ORDINANCE_T27.get(e.getKey());
      if (BgEtzGrounds.OTHER.equals(value)) {
        assertEquals("064", e.getKey());
        continue;
      }
      assertTrue(
          text.startsWith(articleOf(value) + " "),
          "стойност „" + value + "“ → " + e.getKey() + " „" + text + "“");
    }
  }

  @Test
  @DisplayName("Selects.xml: всяка стойност от таблицата е опция в избора, и обратно")
  void theSelectionCarriesTheTable() throws Exception {
    Set<String> options = options("bg.termination.ground.select");
    Set<String> mapped = new TreeSet<>(BgEtzGrounds.terminationGroundCodes().keySet());
    for (String value : mapped) {
      assertTrue(options.contains(value), "липсва опция „" + value + "“");
    }
    Set<String> extra = new TreeSet<>(options);
    extra.removeAll(mapped);
    // Заварените сборни и ЗВО: в избора са, превод нямат и отказват.
    assertEquals(new TreeSet<>(Set.of("327_1", "330_2", "58_1_3")), extra);
  }

  @Test
  @DisplayName(
      "Акт и основание се сверяват: „19a_2“ иска ZA; „other“ → 064; ЗВО отказва и сочи 064;"
          + " стойност извън т. 27 — отказ")
  void theActIsChecked() {
    assertEquals("058", BgEtzGrounds.terminationGroundFor("19a_2", "ZA", "01"));
    assertEquals("063", BgEtzGrounds.terminationGroundFor("340a_6", "ZSV", "01"));
    assertEquals("064", BgEtzGrounds.terminationGroundFor(BgEtzGrounds.OTHER, "KT", "01"));
    assertEquals("029", BgEtzGrounds.terminationGroundFor("328_1_2", "KT", "01"));
    String wrongAct =
        assertThrows(
                BgEtzGrounds.BgEtzUnmappedGroundException.class,
                () -> BgEtzGrounds.terminationGroundFor("19a_2", "KT", "01"))
            .getMessage();
    assertTrue(wrongAct.contains("ZA"), wrongAct);
    String zvo =
        assertThrows(
                BgEtzGrounds.BgEtzUnmappedGroundException.class,
                () -> BgEtzGrounds.terminationGroundFor("58_1_3", "ZVO", "01"))
            .getMessage();
    assertTrue(zvo.contains("064 — друго"), zvo);
    assertThrows(
        BgEtzGrounds.BgEtzUnmappedGroundException.class,
        () -> BgEtzGrounds.terminationGroundFor("999", "KT", "01"),
        "основание извън т. 27");
    // Голият код не е основание: „029“ минава последната проверка (в т. 27 е), затова отказът
    // трябва да дойде от таблицата, не от нея.
    assertThrows(
        BgEtzGrounds.BgEtzUnmappedGroundException.class,
        () -> BgEtzGrounds.terminationGroundFor("029", "KT", "01"),
        "код на мястото на основание");
    assertThrows(
        BgEtzGrounds.BgEtzUnmappedGroundException.class,
        () -> BgEtzGrounds.terminationGroundFor("327_1", "KT", "01"),
        "заварената сборна стойност");
  }

  // ── т. 12: точките на чл. 68 и 121а ──────────────────────────────────────

  @Test
  @DisplayName(
      "Вид договор: точките на чл. 68 → 002–006, чл. 68 ал. 6 → 002, 121а вр. 68 → 017; голите"
          + " „68“ и „121a“ отказват и казват да се избере точката")
  void contractTypesNameTheirPoint() throws Exception {
    Map<String, String> expected = new LinkedHashMap<>();
    expected.put("68_1_1", "002");
    expected.put("68_6", "002");
    expected.put("68_1_2", "003");
    expected.put("68_1_3", "004");
    expected.put("68_1_4", "005");
    expected.put("68_1_5", "006");
    expected.put("121a_68", "017");
    Set<String> options = options("bg.employment.contract.type.select");
    for (Map.Entry<String, String> e : expected.entrySet()) {
      assertEquals(e.getValue(), BgEtzGrounds.contractGroundFor(e.getKey(), "01"), e.getKey());
      assertEquals("KT", BgEtzGrounds.legalFamilyOfContractType(e.getKey()), e.getKey());
      assertTrue(options.contains(e.getKey()), "опция „" + e.getKey() + "“");
    }
    for (String legacy : new String[] {"68", "121a"}) {
      String text =
          assertThrows(
                  BgEtzGrounds.BgEtzUnmappedGroundException.class,
                  () -> BgEtzGrounds.contractGroundFor(legacy, "01"))
              .getMessage();
      assertTrue(text.contains("изберете"), legacy + ": " + text);
    }
  }

  // ── файлът за прекратяване ───────────────────────────────────────────────

  @Test
  @DisplayName(
      "Прекратяване с изричните т. 25/30: т. 26 = датата, т. 27 = 029,"
          + " без т. 18, 0 грешки срещу схемата")
  void aTerminationCarriesTheDateAndTheGround() {
    BgEtzFixture fixture = new BgEtzFixture();
    EmploymentContract contract = fixture.contract();
    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file =
        new BgEtzResolver()
            .resolve(
                BgEtzFacts.of(contract),
                BgEtzEventKind.TERMINATION,
                gaps,
                fixture.terminationInput());
    assertTrue(gaps.isEmpty(), BgEtzScreens.describe(gaps));
    BgEtzRecord row = file.getRows().get(0);
    // xs:date — схемата печели срещу „ддммгггг“ на Наредбата (etz-design.md § 3).
    assertEquals("2026-09-30", row.get(BgEtz62Field.ENDDATE), "т. 26 — датата на прекратяване");
    assertEquals("029", row.get(BgEtz62Field.ENDREASON), "т. 27 — чл. 328 ал. 1 т. 2 КТ");
    assertNull(row.get(BgEtz62Field.SALARY), "прекратяването не носи т. 18");
    assertEquals(
        0, new BgEtzValidator().validate(new BgEtzEmitter().emit(file), file).size(), "XSD");
    assertNull(
        BgEtzExportService.beforeEuroRefusal(file, LocalDate.of(2025, 12, 15)),
        "1в не засяга запис без т. 18");
  }

  @Test
  @DisplayName(
      "1в: сключване преди 01.01.2026 с т. 18 → отказ с дословния текст; от 01.01.2026 — не")
  void beforeTheEuroWithSalaryIsRefused() {
    EmploymentContract contract = new BgEtzFixture().contract();
    contract.setEndOfContractReason(null);
    contract.setBgTerminationDate(null);
    BgEtzFile file =
        new BgEtzResolver()
            .resolve(BgEtzFacts.of(contract), BgEtzEventKind.CONCLUSION, new ArrayList<>());
    assertNotNull(file.getRows().get(0).get(BgEtz62Field.SALARY));
    assertEquals(
        "Събитието е преди 01.01.2026 – подай ръчно в портала на НАП",
        BgEtzExportService.beforeEuroRefusal(file, LocalDate.of(2025, 12, 31)));
    assertNull(BgEtzExportService.beforeEuroRefusal(file, LocalDate.of(2026, 1, 1)));
  }

  @Test
  @DisplayName("Срокът е 7 календарни дни от прекратяването (чл. 62 ал. 3 КТ)")
  void theDeadlineIsSevenDays() {
    BgContractTermination t = new BgContractTermination();
    t.setTerminationDate(LocalDate.of(2026, 11, 27));
    assertEquals(LocalDate.of(2026, 12, 4), BgEtzTerminationService.dueDate(t));
    t.setTerminationDate(null);
    assertNull(BgEtzTerminationService.dueDate(t));
  }

  // ── помощници ────────────────────────────────────────────────────────────

  /** „325_1_2“ → „чл. 325 ал. 1 т. 2“; „19a_2“ → „чл. 19а ал. 2“; a/b/v → а/б/в. */
  private static String articleOf(String value) {
    String[] parts = value.split("_");
    StringBuilder sb = new StringBuilder("чл. ").append(cyr(parts[0]));
    if (parts.length > 1) {
      sb.append(" ал. ").append(cyr(parts[1]));
    }
    if (parts.length > 2) {
      sb.append(" т. ").append(cyr(parts[2]));
    }
    return sb.toString();
  }

  private static String cyr(String s) {
    return s.replace('a', 'а').replace('b', 'б').replace('v', 'в');
  }

  private static Set<String> options(String selection) throws Exception {
    String xml;
    try (InputStream in =
        BgEtzTerminationPathTest.class.getClassLoader().getResourceAsStream("views/Selects.xml")) {
      assertNotNull(in, "views/Selects.xml");
      xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Matcher block =
        Pattern.compile(
                "<selection name=\"" + Pattern.quote(selection) + "\">(.*?)</selection>",
                Pattern.DOTALL)
            .matcher(xml);
    assertTrue(block.find(), selection);
    Set<String> values = new TreeSet<>();
    Matcher option = Pattern.compile("<option value=\"([^\"]+)\"").matcher(block.group(1));
    while (option.find()) {
      values.add(option.group(1));
    }
    return values;
  }
}
