package com.axelor.apps.bglocalization.service.bankstatement;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * Reusable, dependency-free reader that turns an {@code .xlsx} (Office Open XML) workbook into a
 * plain grid of cell strings for its first worksheet. An {@code .xlsx} is just a ZIP of XML, so
 * this uses only the JDK ({@code java.util.zip} + {@code javax.xml}) — no Apache POI, no OCR.
 *
 * <p>It is deliberately bank-agnostic: it resolves shared/inline strings and reads raw numeric/text
 * values, returning one {@code String[]} per row indexed by column (A = 0). All bank-specific
 * knowledge lives in the per-bank parser, so this reader can back other statement layouts later.
 *
 * <p>Dates and amounts in the supported Postbank export are stored as text and plain numbers, so no
 * cell-style / date-serial handling is required. If a future layout stores dates as numeric
 * serials, number-format handling would be added here (not in the parser).
 */
public class BgXlsxGridReader {

  /** Per-entry uncompressed cap (defensive against a malformed/hostile archive). */
  private static final long MAX_ENTRY_BYTES = 64L * 1024 * 1024;

  /**
   * @return true when the bytes start with the ZIP magic ("PK\3\4") — a prerequisite for xlsx.
   */
  public static boolean looksLikeXlsx(byte[] head) {
    return head != null
        && head.length >= 4
        && head[0] == 0x50
        && head[1] == 0x4B
        && head[2] == 0x03
        && head[3] == 0x04;
  }

  /**
   * Read the first worksheet of the workbook as a grid of cell strings (row-major, column A = 0).
   *
   * @throws IOException when the stream is not a readable {@code .xlsx} (e.g. a PDF renamed to
   *     {@code .xlsx}) or the expected parts are absent.
   */
  public List<String[]> read(InputStream in) throws IOException {
    String sharedStringsXml = null;
    String firstSheetXml = null;
    String firstSheetName = null;
    boolean isZip = false;

    try (ZipInputStream zip = new ZipInputStream(in)) {
      ZipEntry entry;
      while ((entry = zip.getNextEntry()) != null) {
        isZip = true;
        String name = entry.getName();
        if ("xl/sharedStrings.xml".equals(name)) {
          sharedStringsXml = readEntry(zip);
        } else if (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml")) {
          // Keep the lexicographically-first worksheet (sheet1.xml before sheet2.xml, ...).
          if (firstSheetName == null || name.compareTo(firstSheetName) < 0) {
            firstSheetName = name;
            firstSheetXml = readEntry(zip);
          }
        }
      }
    }

    if (!isZip) {
      throw new IOException(
          "Not an .xlsx file (no ZIP content). PDF statements are not supported.");
    }
    if (firstSheetXml == null) {
      throw new IOException("The .xlsx has no worksheet (xl/worksheets/sheet*.xml).");
    }
    List<String> sharedStrings = parseSharedStrings(sharedStringsXml);
    return parseSheet(firstSheetXml, sharedStrings);
  }

  private static String readEntry(ZipInputStream zip) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    long total = 0;
    while ((n = zip.read(buf)) != -1) {
      total += n;
      if (total > MAX_ENTRY_BYTES) {
        throw new IOException("XLSX part exceeds the size limit.");
      }
      out.write(buf, 0, n);
    }
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  /**
   * Parse {@code xl/sharedStrings.xml} into the ordered shared-string table (may be null/empty).
   */
  static List<String> parseSharedStrings(String xml) {
    List<String> strings = new ArrayList<>();
    if (xml == null || xml.trim().isEmpty()) {
      return strings;
    }
    Document doc = parseXml(xml);
    NodeList siList = doc.getElementsByTagNameNS("*", "si");
    for (int i = 0; i < siList.getLength(); i++) {
      // An <si> may hold a single <t> or several <r><t> rich-text runs — concatenate all <t>.
      strings.add(concatText((Element) siList.item(i)));
    }
    return strings;
  }

  /**
   * Parse a worksheet XML into a row-major grid; each row is a String[] indexed by column (A=0).
   */
  static List<String[]> parseSheet(String sheetXml, List<String> sharedStrings) {
    List<String[]> grid = new ArrayList<>();
    if (sheetXml == null || sheetXml.trim().isEmpty()) {
      return grid;
    }
    Document doc = parseXml(sheetXml);
    NodeList rows = doc.getElementsByTagNameNS("*", "row");
    for (int i = 0; i < rows.getLength(); i++) {
      Element row = (Element) rows.item(i);
      List<String> cells = new ArrayList<>();
      NodeList cList = row.getElementsByTagNameNS("*", "c");
      for (int j = 0; j < cList.getLength(); j++) {
        Element c = (Element) cList.item(j);
        int col = columnIndex(c.getAttribute("r"));
        String value = cellValue(c, sharedStrings);
        while (cells.size() <= col) {
          cells.add("");
        }
        cells.set(col, value);
      }
      grid.add(cells.toArray(new String[0]));
    }
    return grid;
  }

  /** Resolve the text of one cell, honouring shared-string / inline-string / raw-value shapes. */
  private static String cellValue(Element c, List<String> sharedStrings) {
    String type = c.getAttribute("t");
    if ("s".equals(type)) {
      String raw = firstChildText(c, "v");
      if (raw == null || raw.isEmpty()) {
        return "";
      }
      try {
        int idx = Integer.parseInt(raw.trim());
        return idx >= 0 && idx < sharedStrings.size() ? sharedStrings.get(idx) : "";
      } catch (NumberFormatException e) {
        return "";
      }
    }
    if ("inlineStr".equals(type)) {
      NodeList is = c.getElementsByTagNameNS("*", "is");
      return is.getLength() > 0 ? concatText((Element) is.item(0)) : "";
    }
    // "str" (formula string), numeric, boolean, date-as-number: use the raw <v> text.
    String v = firstChildText(c, "v");
    return v == null ? "" : v;
  }

  /** Concatenate the text of every descendant {@code <t>} element (rich-text runs included). */
  private static String concatText(Element parent) {
    StringBuilder sb = new StringBuilder();
    NodeList tList = parent.getElementsByTagNameNS("*", "t");
    for (int i = 0; i < tList.getLength(); i++) {
      sb.append(tList.item(i).getTextContent());
    }
    return sb.toString();
  }

  private static String firstChildText(Element parent, String localName) {
    NodeList list = parent.getElementsByTagNameNS("*", localName);
    for (int i = 0; i < list.getLength(); i++) {
      Node n = list.item(i);
      // Only direct-ish children (skip a <v> nested in some unexpected subtree).
      if (n.getParentNode() == parent) {
        return n.getTextContent();
      }
    }
    return list.getLength() > 0 ? list.item(0).getTextContent() : null;
  }

  /** "AB12" → zero-based column index of "AB"; -1 when no letters present. */
  static int columnIndex(String cellRef) {
    if (cellRef == null || cellRef.isEmpty()) {
      return -1;
    }
    int col = 0;
    boolean any = false;
    for (int i = 0; i < cellRef.length(); i++) {
      char ch = cellRef.charAt(i);
      if (ch >= 'A' && ch <= 'Z') {
        col = col * 26 + (ch - 'A' + 1);
        any = true;
      } else if (ch >= 'a' && ch <= 'z') {
        col = col * 26 + (ch - 'a' + 1);
        any = true;
      } else {
        break;
      }
    }
    return any ? col - 1 : -1;
  }

  private static Document parseXml(String xml) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setNamespaceAware(true);
      // XXE hardening — the workbook is untrusted input.
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setExpandEntityReferences(false);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IllegalStateException("Could not parse XLSX XML: " + e.getMessage(), e);
    }
  }
}
