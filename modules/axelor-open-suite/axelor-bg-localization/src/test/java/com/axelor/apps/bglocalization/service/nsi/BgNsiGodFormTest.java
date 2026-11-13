package com.axelor.apps.bglocalization.service.nsi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Kind;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodForm.Row;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОБРАЗЕЦЪТ ЗА 2025 СРЕЩУ ФОРМУЛЯРА НА НСИ.
 *
 * <p>Кодовете по-долу са извадени с pdftotext от Приложение № 2 (2025-НФ2), nsi.bg, Last-Modified
 * 17.03.2026, прочетено на 27.10.2026 — таблици 4 и 5, в реда на формуляра. Не са преписвани на
 * ръка.
 */
class BgNsiGodFormTest {

  static final List<String> PDF_2025 =
      Arrays.asList(
          ("01000,02110,02120,02130,02140,02141,02150,02100,02210,02211,02212,02220,02230,02240,"
                  + "02241,02250,02200,02310,02311,02320,02330,02331,02340,02350,02351,02352,02353,"
                  + "02354,02360,02370,02300,02400,02000,03110,03120,03121,03130,03131,03132,03140,"
                  + "03100,03210,03211,03220,03221,03230,03231,03240,03241,03200,03310,03320,03330,"
                  + "03331,03332,03300,03410,03411,03412,03413,03414,03415,03420,03421,03422,03423,"
                  + "03424,03400,03000,04000,04500,05100,05110,05111,05112,05120,05121,05200,05300,"
                  + "05310,05410,05420,05430,05440,05441,05400,05510,05520,05500,05600,05601,05700,"
                  + "05000,06100,06200,06210,06300,06000,07100,07101,07102,07110,07111,07112,07200,"
                  + "07201,07202,07300,07301,07302,07400,07401,07402,07500,07501,07502,07600,07601,"
                  + "07602,07700,07701,07702,07800,07801,07802,07810,07811,07812,07820,07821,07822,"
                  + "07830,07831,07832,07900,07901,07902,07000,07001,07002,08000,08001,08002,08500,"
                  + "10100,10200,10210,10220,10300,10310,10311,10320,10321,10400,10410,10411,10413,"
                  + "10412,10420,10500,10510,10520,10000,11100,11110,11200,11210,11220,11201,11000,"
                  + "14000,13000,14100,14200,14300,14400,14500,15100,15110,15120,15130,15131,15132,"
                  + "15133,15200,15300,15310,15400,15410,15411,15420,15430,15000,16100,16110,16200,"
                  + "16210,16300,16310,16320,16330,16000,19000,18000,19100,19200,19500")
              .split(","));

  /** Кодовете без сметка в BG_COA — попълват се ръчно. */
  static final Set<String> NO_ACCOUNT =
      new TreeSet<>(
          Arrays.asList(
              "02140", "02141", "02241", "02311", "02320", "02331", "02340", "02354", "03230",
              "03310", "03331", "03332", "05121", "05420", "05430", "05601", "06100", "07110",
              "07700", "10311", "11201", "11210", "15411"));

  private final BgNsiGodForm form = BgNsiGodForm.forYear(2025);

  private List<String> formCodes() {
    return form.rows.stream()
        .filter(r -> r.hasValue() && !r.isService())
        .map(r -> r.code)
        .collect(Collectors.toList());
  }

  @Test
  @DisplayName("Всички 207 кода на таблици 4 и 5 — същите и в същия ред като във формуляра")
  void кодовете_са_тези_на_формуляра() {
    assertEquals(2025, form.version);
    assertEquals(PDF_2025, formCodes());
  }

  @Test
  @DisplayName("Отчетна година без собствен образец взима последния по-ранен")
  void година_без_образец() {
    assertEquals(2025, BgNsiGodForm.forYear(2026).version);
    assertEquals(2025, BgNsiGodForm.forYear(2019).version);
  }

  @Test
  @DisplayName(
      "Кодовете без сметка в сметкоплана са „попълва се ръчно“ (сборните — по редовете си)")
  void редовете_без_сметка_са_ръчни() {
    for (String code : NO_ACCOUNT) {
      Row row = form.row(code);
      assertNotNull(row, code);
      assertTrue(
          row.kind == Kind.MANUAL
              || (row.kind == Kind.SUM && parts(row).stream().allMatch(this::manual)),
          code + " е " + row.kind);
    }
  }

  @Test
  @DisplayName("Контролите от формуляра: 04500, 08500, 13000, 18000, 14500, 19500")
  void контролите_на_формуляра() {
    assertEquals("01000+02000+03000+04000", form.row("04500").formula);
    assertEquals("05000+06000+07000+08000", form.row("08500").formula);
    assertEquals("10000+11000", form.row("13000").formula);
    assertEquals("15000+16000", form.row("18000").formula);
    assertEquals("13000+14200+14300+14400", form.row("14500").formula);
    assertEquals("18000+19200", form.row("19500").formula);
  }

  @Test
  @DisplayName("Всяка формула сочи съществуващ код; „в т.ч.“ не влиза в сбор")
  void формулите_сочат_редове_от_формуляра() {
    Set<String> codes = new LinkedHashSet<>();
    form.rows.forEach(r -> codes.add(r.code));
    for (Row row : form.rows) {
      if (row.kind != Kind.SUM) {
        continue;
      }
      for (String part : parts(row)) {
        assertTrue(codes.contains(part), row.code + " сочи " + part);
        Row target = form.row(part);
        assertTrue(row.isSub() || !target.isSub(), row.code + " сумира „в т.ч.“ " + part);
      }
    }
    // Изчисляването на всички сборове с нули — без кръгови формули и без непознати кодове.
    Map<String, BigDecimal> leaves = new HashMap<>();
    form.rows.stream()
        .filter(r -> r.kind == Kind.ACC || r.kind == Kind.MANUAL)
        .forEach(r -> leaves.put(r.code, BigDecimal.ZERO));
    Map<String, BigDecimal> all = BgNsiGodCalculator.totals(form, leaves);
    assertEquals(formCodes().size() + 2, all.size());
  }

  @Test
  @DisplayName(
      "Печалбата и загубата: Всичко отляво = Всичко отдясно при печалба, загуба и данък > печалба")
  void всичко_се_равнява() {
    for (int[] c : new int[][] {{100, 60, 10, 0}, {50, 80, 0, 0}, {100, 95, 10, 2}}) {
      Map<String, BigDecimal> leaves = new HashMap<>();
      form.rows.stream()
          .filter(r -> r.kind == Kind.ACC || r.kind == Kind.MANUAL)
          .forEach(r -> leaves.put(r.code, BigDecimal.ZERO));
      leaves.put("15110", BigDecimal.valueOf(c[0]));
      leaves.put("10210", BigDecimal.valueOf(c[1]));
      leaves.put("14200", BigDecimal.valueOf(c[2]));
      leaves.put("14300", BigDecimal.valueOf(c[3]));
      Map<String, BigDecimal> v = BgNsiGodCalculator.totals(form, leaves);
      assertEquals(0, v.get("14500").compareTo(v.get("19500")), Arrays.toString(c));
      BigDecimal net = BigDecimal.valueOf(c[0] - c[1] - c[2] - c[3]);
      assertEquals(0, v.get("14400").subtract(v.get("19200")).compareTo(net), Arrays.toString(c));
    }
  }

  private boolean manual(String code) {
    Row row = form.row(code);
    return row.kind == Kind.MANUAL;
  }

  private static List<String> parts(Row row) {
    List<String> out = new ArrayList<>();
    for (String p :
        row.formula.replace("POS(", "").replace("NEG(", "").replace(")", "").split("[+-]")) {
      out.add(p);
    }
    return out;
  }
}
