package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Името на файла по Прил. 3 т. I.2 — по един за всеки вид, и ЕИК само от девет цифри. */
class BgSaftFileNameTest {

  private static final String EIK = "123456789";

  private static Period april() {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 4, 1));
    period.setToDate(LocalDate.of(2026, 4, 30));
    return period;
  }

  private static String name(String eik, BgSaftSelection selection) {
    BgSaftFileName.Result result = BgSaftFileName.of(eik, selection);
    assertEquals(List.of(), result.getFindings());
    return result.getFileName();
  }

  @Test
  @DisplayName("Месечен: ЕИК_MM_YYYY.xml — месецът с водеща нула")
  void месечният() {
    assertEquals("123456789_04_2026.xml", name(EIK, BgSaftSelection.monthly(april())));
  }

  @Test
  @DisplayName("Годишен: ЕИК_YYYY.xml")
  void годишният() {
    assertEquals("123456789_2026.xml", name(EIK, BgSaftSelection.annual(2026)));
  }

  @Test
  @DisplayName("При поискване: ЕИК_DD_MM_YYYY_DD_MM_YYYY.xml — началната и крайната дата")
  void при_поискване() {
    assertEquals(
        "123456789_01_07_2026_15_07_2026.xml",
        name(EIK, BgSaftSelection.onDemand(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 15))));
    assertEquals(
        "123456789_16_12_2026_05_01_2027.xml",
        name(EIK, BgSaftSelection.onDemand(LocalDate.of(2026, 12, 16), LocalDate.of(2027, 1, 5))));
  }

  @Test
  @DisplayName("Интервалите около ЕИК не влизат в името")
  void интервалите() {
    assertEquals("123456789_2026.xml", name(" 123456789 ", BgSaftSelection.annual(2026)));
  }

  private static void error(String eik, String code) {
    BgSaftFileName.Result result = BgSaftFileName.of(eik, BgSaftSelection.annual(2026));
    assertNull(result.getFileName(), "име, което НАП ще откаже, не се предлага");
    assertEquals(1, result.getFindings().size());
    BgDeclarationFileFinding f = result.getFindings().get(0);
    assertEquals(code, f.getCode());
    assertTrue(f.isBlocking(), "ГРЕШКА, не предупреждение");
  }

  @Test
  @DisplayName("13-цифрен номер на клон — ГРЕШКА: файлът е на търговеца (QA II.1.10)")
  void клонът_е_грешка() {
    error("1234567890001", "SAFT_FILE_NAME_BRANCH_EIK");
  }

  @Test
  @DisplayName("Без ЕИК или с ЕИК, който не е от девет цифри — ГРЕШКА")
  void невалидният_еик_е_грешка() {
    error(null, "SAFT_FILE_NAME_EIK_MISSING");
    error("  ", "SAFT_FILE_NAME_EIK_MISSING");
    error("12345678", "SAFT_FILE_NAME_EIK_INVALID");
    error("BG123456789", "SAFT_FILE_NAME_EIK_INVALID");
    error("1234567890", "SAFT_FILE_NAME_EIK_INVALID");
  }

  @Test
  @DisplayName("Без вид и период — отказ")
  void без_вид() {
    assertThrows(IllegalArgumentException.class, () -> BgSaftFileName.of(EIK, null));
  }
}
