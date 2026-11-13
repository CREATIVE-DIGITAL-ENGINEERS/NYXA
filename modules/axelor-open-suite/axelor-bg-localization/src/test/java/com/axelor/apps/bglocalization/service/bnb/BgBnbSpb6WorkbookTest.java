package com.axelor.apps.bglocalization.service.bnb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Balance;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Period;
import com.axelor.apps.bglocalization.service.bnb.BgBnbSpb6.Result;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Файлът, прочетен обратно: жълто + „провери“, броят горе, редът за прага.
 *
 * <p>Чете се направо от частите на xlsx, не с POI: POI 3.9 пише бележките (VML), но не може да
 * прочете собствения си VML — „Content is not allowed in trailing section“, поправено в POI 3.10.
 * Excel и LibreOffice го четат; сверено с LibreOffice 03.11.2026.
 */
class BgBnbSpb6WorkbookTest {

  /** IndexedColors.YELLOW. */
  private static final String YELLOW = "13";

  private static Balance b(long id, String name, String country, String account, String closing) {
    Balance b = new Balance(account, id, name, false, country, "EUR");
    b.closing = new BigDecimal(closing);
    return b;
  }

  /** Един лист: стойностите и стиловете по адрес, бележките по адрес. */
  private static final class Xlsx {
    final List<String> sheets = new ArrayList<>();
    final Map<String, Map<String, String>> values = new HashMap<>();
    final Map<String, Map<String, Boolean>> yellow = new HashMap<>();
    final Map<String, Map<String, String>> comments = new HashMap<>();

    String text(String sheet, String ref) {
      return values.get(sheet).getOrDefault(ref, "");
    }

    long number(String sheet, String ref) {
      return (long) Double.parseDouble(text(sheet, ref));
    }
  }

  private static Xlsx write(Period period, List<Balance> balances) throws Exception {
    Result r = BgBnbSpb6.build(period, balances, (c, d) -> null);
    return read(BgBnbSpb6Workbook.write(r, "Тест ООД", "999999999"));
  }

  private static Xlsx read(byte[] bytes) throws Exception {
    Map<String, Document> parts = new TreeMap<>();
    try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
      ZipEntry e;
      while ((e = zip.getNextEntry()) != null) {
        if (e.getName().endsWith(".xml") || e.getName().endsWith(".rels")) {
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          zip.transferTo(out);
          parts.put(
              e.getName(),
              DocumentBuilderFactory.newInstance()
                  .newDocumentBuilder()
                  .parse(new ByteArrayInputStream(out.toByteArray())));
        }
      }
    }
    List<String> strings = new ArrayList<>();
    NodeList si = parts.get("xl/sharedStrings.xml").getElementsByTagName("si");
    for (int i = 0; i < si.getLength(); i++) {
      strings.add(si.item(i).getTextContent());
    }
    List<Boolean> styleYellow = new ArrayList<>();
    Document styles = parts.get("xl/styles.xml");
    NodeList fills = styles.getElementsByTagName("fill");
    Element cellXfs = (Element) styles.getElementsByTagName("cellXfs").item(0);
    NodeList xfs = cellXfs.getElementsByTagName("xf");
    for (int i = 0; i < xfs.getLength(); i++) {
      Element fill =
          (Element) fills.item(Integer.parseInt(((Element) xfs.item(i)).getAttribute("fillId")));
      NodeList fg = fill.getElementsByTagName("fgColor");
      styleYellow.add(
          fg.getLength() > 0 && YELLOW.equals(((Element) fg.item(0)).getAttribute("indexed")));
    }

    Xlsx x = new Xlsx();
    NodeList sheetList = parts.get("xl/workbook.xml").getElementsByTagName("sheet");
    for (int i = 0; i < sheetList.getLength(); i++) {
      // POI пише листовете като sheet1..sheetN по реда на създаване.
      String name = ((Element) sheetList.item(i)).getAttribute("name");
      x.sheets.add(name);
      Map<String, String> v = new HashMap<>();
      Map<String, Boolean> y = new HashMap<>();
      NodeList cells =
          parts.get("xl/worksheets/sheet" + (i + 1) + ".xml").getElementsByTagName("c");
      for (int k = 0; k < cells.getLength(); k++) {
        Element c = (Element) cells.item(k);
        String ref = c.getAttribute("r");
        NodeList vs = c.getElementsByTagName("v");
        String raw = vs.getLength() == 0 ? "" : vs.item(0).getTextContent();
        v.put(ref, "s".equals(c.getAttribute("t")) ? strings.get(Integer.parseInt(raw)) : raw);
        String s = c.getAttribute("s");
        y.put(ref, !s.isEmpty() && styleYellow.get(Integer.parseInt(s)));
      }
      x.values.put(name, v);
      x.yellow.put(name, y);
      Map<String, String> notes = new HashMap<>();
      Document rels = parts.get("xl/worksheets/_rels/sheet" + (i + 1) + ".xml.rels");
      if (rels != null) {
        NodeList rel = rels.getElementsByTagName("Relationship");
        for (int k = 0; k < rel.getLength(); k++) {
          String target = ((Element) rel.item(k)).getAttribute("Target");
          if (target.contains("comments")) {
            Document cm = parts.get("xl/" + target.replace("../", ""));
            NodeList list = cm.getElementsByTagName("comment");
            for (int j = 0; j < list.getLength(); j++) {
              Element c = (Element) list.item(j);
              notes.put(c.getAttribute("ref"), c.getTextContent());
            }
          }
        }
      }
      x.comments.put(name, notes);
    }
    return x;
  }

  @Test
  @DisplayName("Подразбиращите се клетки са жълти, с бележка „провери“; броят им е горе")
  void жълто_и_провери() throws Exception {
    List<Balance> in = new ArrayList<>();
    in.add(b(1, "Клиент DE", "DE", "411", "150000"));
    in.add(b(2, "Майка AT", "AT", "415", "3000"));
    in.add(b(3, "Длъжник NL", "NL", "498", "2000"));
    Xlsx x = write(Period.of(2026, 2), in);
    assertEquals(List.of("СПБ-6А", "СПБ-6Б", "Проверки", "Разбивка"), x.sheets);
    String a = "СПБ-6А";
    // 3 реда × (тип, сектор, матуритет) + описанието на F89
    assertEquals(BgBnbSpb6Workbook.DEFAULTS_LABEL + 10, x.text(a, "A6"));
    assertTrue(x.text(a, "A5").startsWith("ПОДАВА СЕ до 20.07.2026"), x.text(a, "A5"));

    List<String> yellowRefs = new ArrayList<>();
    x.yellow
        .get(a)
        .forEach(
            (ref, isYellow) -> {
              if (isYellow) {
                yellowRefs.add(ref);
              }
            });
    yellowRefs.sort(null);
    assertEquals(10, yellowRefs.size(), yellowRefs.toString());
    assertEquals(
        new java.util.TreeSet<>(yellowRefs), new java.util.TreeSet<>(x.comments.get(a).keySet()));
    for (String note : x.comments.get(a).values()) {
      assertTrue(note.startsWith(BgBnbSpb6Workbook.CHECK), note);
    }

    // Редът с данни 10 (FIRST_DATA_ROW = 9): F81 преди F89, после по държава — AT (свързаното), DE.
    assertEquals(9, BgBnbSpb6Workbook.FIRST_DATA_ROW);
    assertEquals("D4", x.text(a, "A10"));
    assertEquals("F81", x.text(a, "C10"));
    assertEquals("AT", x.text(a, "D10"));
    assertEquals(3, x.number(a, "J10"));
    assertTrue(
        x.comments.get(a).get("A10").contains("вероятно свързано лице – провери D1–D3"),
        x.comments.get(a).get("A10"));
    assertEquals("DE", x.text(a, "D11"));
    assertEquals(150, x.number(a, "J11"));
    assertEquals("Клиент DE", x.text(a, "K11"));
    assertTrue(x.comments.get(a).get("A11").contains("по подразбиране D4"));
    assertEquals("F89", x.text(a, "C12"));
    assertNotNull(x.comments.get(a).get("D14"), "описанието на F89 е жълто и с бележка");
    assertTrue(x.comments.get("СПБ-6Б").isEmpty());
  }

  @Test
  @DisplayName("Под прага: справката се прави, а горе пише, че не се подава")
  void под_прага() throws Exception {
    Xlsx x = write(Period.of(2026, 1), List.of(b(1, "Клиент DE", "DE", "411", "8000")));
    assertTrue(x.text("СПБ-6А", "A5").startsWith("НЕ СЕ ПОДАВА"), x.text("СПБ-6А", "A5"));
    assertEquals(8, x.number("СПБ-6А", "J10"));
  }

  @Test
  @DisplayName("Цялата година е работен лист — заглавието го казва")
  void годината_е_работен_лист() throws Exception {
    Xlsx x = write(Period.of(2026, 0), List.of(b(1, "Клиент DE", "DE", "411", "8000")));
    String title = x.text("СПБ-6А", "A3");
    assertTrue(title.startsWith("Работен лист за 2026 г."), title);
    assertTrue(title.contains("подава се тримесечно"), title);
  }
}
