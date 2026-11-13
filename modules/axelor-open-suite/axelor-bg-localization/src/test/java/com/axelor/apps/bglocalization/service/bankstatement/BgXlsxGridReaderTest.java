package com.axelor.apps.bglocalization.service.bankstatement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Offline unit tests for the dependency-free XLSX grid reader (XML → grid, no file I/O). */
class BgXlsxGridReaderTest {

  private static final String SHARED =
      "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
          + "<si><t>Разход</t></si>"
          + "<si><t>Приход</t></si>"
          + "<si><r><t>rich</t></r><r><t>text</t></r></si>"
          + "</sst>";

  private static final String SHEET =
      "<x:worksheet xmlns:x=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
          + "<x:sheetData>"
          + "<x:row r=\"1\"><x:c r=\"A1\" t=\"s\"><x:v>0</x:v></x:c>"
          + "<x:c r=\"C1\"><x:v>12.5</x:v></x:c></x:row>"
          + "<x:row r=\"2\"><x:c r=\"B2\" t=\"inlineStr\"><x:is><x:t>inline</x:t></x:is></x:c></x:row>"
          + "</x:sheetData></x:worksheet>";

  @Test
  void parsesSharedStringsIncludingRichText() {
    List<String> ss = BgXlsxGridReader.parseSharedStrings(SHARED);
    assertEquals(3, ss.size());
    assertEquals("Разход", ss.get(0));
    assertEquals("Приход", ss.get(1));
    assertEquals("richtext", ss.get(2)); // rich-text runs concatenated
  }

  @Test
  void parsesSheetResolvingSharedAndInlineAndRawValues() {
    List<String> ss = BgXlsxGridReader.parseSharedStrings(SHARED);
    List<String[]> grid = BgXlsxGridReader.parseSheet(SHEET, ss);
    assertEquals(2, grid.size());
    // Row 1: A=shared "Разход", B empty (gap), C=raw number "12.5".
    assertEquals("Разход", grid.get(0)[0]);
    assertEquals("", grid.get(0)[1]);
    assertEquals("12.5", grid.get(0)[2]);
    // Row 2: A empty, B=inline string.
    assertEquals("", grid.get(1)[0]);
    assertEquals("inline", grid.get(1)[1]);
  }

  @Test
  void columnIndexFromCellReference() {
    assertEquals(0, BgXlsxGridReader.columnIndex("A1"));
    assertEquals(2, BgXlsxGridReader.columnIndex("C1"));
    assertEquals(26, BgXlsxGridReader.columnIndex("AA1"));
    assertEquals(27, BgXlsxGridReader.columnIndex("AB12"));
    assertEquals(-1, BgXlsxGridReader.columnIndex(""));
  }

  @Test
  void looksLikeXlsxByZipMagic() {
    assertTrue(BgXlsxGridReader.looksLikeXlsx(new byte[] {0x50, 0x4B, 0x03, 0x04, 0x00}));
    assertFalse(BgXlsxGridReader.looksLikeXlsx("%PDF-1.7".getBytes())); // PDF renamed to .xlsx
    assertFalse(BgXlsxGridReader.looksLikeXlsx(new byte[] {0x00}));
  }
}
