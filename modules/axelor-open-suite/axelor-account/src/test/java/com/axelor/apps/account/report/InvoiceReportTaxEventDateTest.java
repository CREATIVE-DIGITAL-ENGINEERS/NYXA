package com.axelor.apps.account.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * ФАКТУРАТА ПЕЧАТА „Дата на данъчното събитие: ДД.ММ.ГГГГ“ — ЗДДС чл. 114 ал. 1 т. 10.
 *
 * <p>Датата идва от процедурата {@code invoice_proc} в {@code Invoice.rptdesign}: изрично
 * попълнената „Дата на данъчното събитие“ на фактурата ({@code bg_vat_tax_event_date}); иначе при
 * авансова фактура ({@code operation_sub_type_select = 2}) — най-ранното валидирано плащане (чл. 25
 * ал. 7); иначе датата на издаване. Същото правило ползва модулът за ДДС ({@code
 * COALESCE(bgVatTaxEventDate, invoiceDate)}).
 *
 * <p>Изразът се взима ДОСЛОВНО от двата клона на процедурата и се изпълнява в HSQLDB върху малки
 * таблици със същите колони — проверява се текстът, който BIRT пуска, а не негово копие. Самият
 * печат (BIRT срещу PostgreSQL) не се пуска тук.
 */
class InvoiceReportTaxEventDateTest {

  private static final Path REPORT = Paths.get("src/main/resources/reports/Invoice.rptdesign");

  /** Изразът под коментара „ЗДДС чл. 114 ал. 1 т. 10“ до {@code as "bgTaxEventDate"}. */
  private static final Pattern EXPRESSION =
      Pattern.compile(
          "-- ЗДДС чл\\. 114 ал\\. 1 т\\. 10[^\\n]*\\n\\s*(.*?)\\s+as \"bgTaxEventDate\"",
          Pattern.DOTALL);

  private static final DateTimeFormatter PRINTED = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private static String report;
  private static Connection db;

  @BeforeAll
  static void setUp() throws Exception {
    assertTrue(Files.isRegularFile(REPORT), "не намирам " + REPORT.toAbsolutePath());
    report = new String(Files.readAllBytes(REPORT), StandardCharsets.UTF_8);
    db = DriverManager.getConnection("jdbc:hsqldb:mem:invoiceTaxEventDate", "SA", "");
    try (Statement st = db.createStatement()) {
      st.execute(
          "CREATE TABLE account_invoice (id BIGINT PRIMARY KEY, invoice_date DATE,"
              + " bg_vat_tax_event_date DATE, operation_sub_type_select INT)");
      st.execute(
          "CREATE TABLE account_invoice_payment (id BIGINT PRIMARY KEY, invoice BIGINT,"
              + " payment_date DATE, status_select INT)");
    }
    // 1: фактура, издадена 02.11, с данъчно събитие 28.10 — свидетелят от заданието.
    invoice(1, "2026-11-02", "2026-10-28", 1);
    // 2: фактура без отделна дата на събитието — датата на издаване.
    invoice(2, "2026-11-02", null, 1);
    // 3: авансова, издадена 03.11; плащания 01.11 и 30.10 (валидирани), 20.10 (анулирано).
    invoice(3, "2026-11-03", null, 2);
    payment(31, 3, "2026-11-01", 1);
    payment(32, 3, "2026-10-30", 1);
    payment(33, 3, "2026-10-20", 2);
    // 4: авансова без валидирано плащане — датата на издаване.
    invoice(4, "2026-11-04", null, 2);
    payment(41, 4, "2026-10-29", 0);
    // 5: авансова с изрично попълнена дата — тя печели.
    invoice(5, "2026-11-05", "2026-11-01", 2);
    payment(51, 5, "2026-10-31", 1);
  }

  @AfterAll
  static void tearDown() throws Exception {
    if (db != null) {
      try (Statement st = db.createStatement()) {
        st.execute("SHUTDOWN");
      }
      db.close();
    }
  }

  @Test
  void датата_на_събитието_се_печата_вместо_датата_на_издаване() throws Exception {
    for (String expression : expressions()) {
      assertEquals("28.10.2026", printed(expression, 1), "дата на събитие ≠ дата на издаване");
      assertEquals("02.11.2026", printed(expression, 2), "без дата на събитие");
      assertEquals("30.10.2026", printed(expression, 3), "аванс — първото валидирано плащане");
      assertEquals("04.11.2026", printed(expression, 4), "аванс без валидирано плащане");
      assertEquals("01.11.2026", printed(expression, 5), "аванс с изрична дата");
    }
  }

  @Test
  void колоната_е_последна_в_процедурата_и_в_набора() {
    assertTrue(
        report.contains("\"displayExpenseOnPrinting\" boolean,\n    \"bgTaxEventDate\" date\n) AS"),
        "bgTaxEventDate трябва да е последната колона на RETURNS table на invoice_proc");
    assertTrue(
        Pattern.compile(
                "<property name=\"position\">78</property>\\s*"
                    + "<property name=\"name\">bgTaxEventDate</property>\\s*"
                    + "<property name=\"nativeName\">bgTaxEventDate</property>\\s*"
                    + "<property name=\"dataType\">date</property>")
            .matcher(report)
            .find(),
        "InvoiceDB няма bgTaxEventDate на позиция 78");
    assertTrue(
        report.contains("dataSetRow[\"bgTaxEventDate\"]"),
        "главната таблица не свързва bgTaxEventDate");
  }

  @Test
  void двата_варианта_на_заглавката_печатат_ДД_ММ_ГГГГ() throws Exception {
    Document doc;
    try (InputStream in = Files.newInputStream(REPORT)) {
      DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
      f.setNamespaceAware(true);
      doc = f.newDocumentBuilder().parse(in);
    }
    NodeList data = doc.getElementsByTagNameNS("*", "data");
    int found = 0;
    for (int i = 0; i < data.getLength(); i++) {
      Element e = (Element) data.item(i);
      if (!"bgTaxEventDate".equals(property(e, "resultSetColumn"))) {
        continue;
      }
      found++;
      assertTrue(
          e.getTextContent().contains("dd.MM.yyyy"), "формат на датата: " + e.getTextContent());
      Element label =
          (Element) ((Element) e.getParentNode()).getElementsByTagNameNS("*", "text").item(0);
      assertTrue(
          label.getTextContent().contains("'Invoice.taxEventDate'")
              && label.getTextContent().contains("Дата на данъчното събитие"),
          "етикетът пред датата");
    }
    assertEquals(2, found, "очаквах датата в двата варианта на заглавката (AddressPositionSelect)");
  }

  @Test
  void преводът_е_на_български() throws Exception {
    String bg =
        new String(
            Files.readAllBytes(Paths.get("src/main/resources/i18n/messages_bg.csv")),
            StandardCharsets.UTF_8);
    assertTrue(
        bg.contains("\"Invoice.taxEventDate\",\"Дата на данъчното събитие\""), "messages_bg");
  }

  private static List<String> expressions() {
    List<String> out = new ArrayList<>();
    Matcher m = EXPRESSION.matcher(report);
    while (m.find()) {
      out.add(m.group(1));
    }
    assertEquals(2, out.size(), "изразът трябва да е в двата клона на invoice_proc");
    assertEquals(out.get(0), out.get(1), "двата клона на invoice_proc смятат датата различно");
    return out;
  }

  private static String printed(String expression, long invoiceId) throws Exception {
    String sql = "SELECT " + expression + " FROM account_invoice AS Invoice WHERE Invoice.id = ?";
    try (PreparedStatement ps = db.prepareStatement(sql)) {
      ps.setLong(1, invoiceId);
      try (ResultSet rs = ps.executeQuery()) {
        assertTrue(rs.next(), "няма фактура " + invoiceId);
        return rs.getDate(1).toLocalDate().format(PRINTED);
      }
    }
  }

  private static String property(Element e, String name) {
    NodeList props = e.getElementsByTagNameNS("*", "property");
    for (int i = 0; i < props.getLength(); i++) {
      Element p = (Element) props.item(i);
      if (name.equals(p.getAttribute("name"))) {
        return p.getTextContent().trim();
      }
    }
    return null;
  }

  private static void invoice(long id, String issued, String taxEvent, int subType)
      throws Exception {
    try (PreparedStatement ps =
        db.prepareStatement("INSERT INTO account_invoice VALUES (?, ?, ?, ?)")) {
      ps.setLong(1, id);
      ps.setDate(2, Date.valueOf(LocalDate.parse(issued)));
      if (taxEvent == null) {
        ps.setNull(3, java.sql.Types.DATE);
      } else {
        ps.setDate(3, Date.valueOf(LocalDate.parse(taxEvent)));
      }
      ps.setInt(4, subType);
      ps.executeUpdate();
    }
  }

  private static void payment(long id, long invoiceId, String date, int status) throws Exception {
    try (PreparedStatement ps =
        db.prepareStatement("INSERT INTO account_invoice_payment VALUES (?, ?, ?, ?)")) {
      ps.setLong(1, id);
      ps.setLong(2, invoiceId);
      ps.setDate(3, Date.valueOf(LocalDate.parse(date)));
      ps.setInt(4, status);
      ps.executeUpdate();
    }
  }
}
