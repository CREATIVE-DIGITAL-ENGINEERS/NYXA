package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НОМЕРЪТ НА БОЛНИЧНИЯ ЛИСТ — дванайсетте знака и това, което НЕ минава за тях.
 *
 * <p>Схемата иска {@code length = 12} и нищо повече; структурата („буква + година + седем цифри с
 * водещи нули“) е в ОПИСАНИЕТО, не в типа. Тоест номер със сгрешена структура минава XSD и пада при
 * НОИ — точно случаят, заради който проверката е тук, преди емисията.
 */
class BgApp9ChartNumberTest {

  @Test
  @DisplayName("Трите полета се слепват със седем цифри и водещи нули")
  void composesFromTheThreeFields() {
    assertEquals("E20260001234", BgApp9ChartNumber.compose("E", Integer.valueOf(2026), "1234"));
    assertEquals("E20260000001", BgApp9ChartNumber.compose("E", Integer.valueOf(2026), "1"));
    assertEquals("E20269999999", BgApp9ChartNumber.compose("E", Integer.valueOf(2026), "9999999"));
  }

  @Test
  @DisplayName("Вече слепнат номер се приема както е — операторът често го преписва наведнъж")
  void acceptsAnAlreadyComposedNumber() {
    assertEquals("E20260001234", BgApp9ChartNumber.compose(null, null, "E20260001234"));
    // ⚠️ Двусмислица няма по построение: седемцифрен пореден номер не може да съвпадне с образеца
    // „буква + 4 цифри + 7 цифри“, тоест двата входа не се застъпват.
    assertEquals("E20260001234", BgApp9ChartNumber.compose("E", Integer.valueOf(2026), "0001234"));
  }

  @Test
  @DisplayName("⚠️ Кирилското А и латинското A са РАЗЛИЧНИ знака и ДВАТА се приемат")
  void bothCyrillicAndLatinPrefixesAreAccepted() {
    assertTrue(BgApp9ChartNumber.isWellFormed("А2026" + "0001234"), "кирилско А");
    assertTrue(BgApp9ChartNumber.isWellFormed("A2026" + "0001234"), "латинско A");
    assertTrue(BgApp9ChartNumber.isWellFormed("Б2026" + "0001234"), "кирилско Б");
    assertTrue(BgApp9ChartNumber.isWellFormed("B2026" + "0001234"), "латинско B");
    assertTrue(BgApp9ChartNumber.isWellFormed("E2026" + "0001234"), "латинско E");
    assertTrue(BgApp9ChartNumber.isWellFormed("P2026" + "0001234"), "латинско P");
  }

  @Test
  @DisplayName("⚠️ Кирилското Е и Р НЕ се приемат — описанието ги иска САМО латински")
  void cyrillicEAndRAreRejected() {
    // Двойките са неразличими за окото: U+0415 срещу U+0045 и U+0420 срещу U+0050. Точно затова
    // наборът е с кодови точки, а не с букви — иначе разликата между приет и отхвърлен файл щеше да
    // зависи от това коя клавиатура е ползвал операторът.
    assertFalse(BgApp9ChartNumber.isWellFormed("Е2026" + "0001234"), "кирилско Е");
    assertFalse(BgApp9ChartNumber.isWellFormed("Р2026" + "0001234"), "кирилско Р");
  }

  @Test
  @DisplayName("Недопустима буква, къс номер и празни полета дават null, а не частичен низ")
  void malformedInputGivesNullNotAPartialString() {
    assertNull(BgApp9ChartNumber.compose("Z", Integer.valueOf(2026), "1234"), "буква извън набора");
    assertNull(BgApp9ChartNumber.compose("E", null, "1234"), "липсва година");
    assertNull(BgApp9ChartNumber.compose(null, Integer.valueOf(2026), "1234"), "липсва серия");
    assertNull(BgApp9ChartNumber.compose("E", Integer.valueOf(2026), null), "липсва номер");
    assertNull(BgApp9ChartNumber.compose("E", Integer.valueOf(2026), "12345678"), "над 7 цифри");
    assertNull(BgApp9ChartNumber.compose("E", Integer.valueOf(2026), "12A4"), "не са цифри");
    assertNull(BgApp9ChartNumber.compose("EE", Integer.valueOf(2026), "1234"), "серия от 2 знака");
  }

  @Test
  @DisplayName("Съобщението казва КОЕ липсва и КЪДЕ се попълва")
  void theMessageIsActionable() {
    String message = BgApp9ChartNumber.describeMissing(null, null, null);
    assertTrue(message.contains("липсва серия"), message);
    assertTrue(message.contains("липсва година"), message);
    assertTrue(message.contains("липсва номер"), message);
    assertTrue(message.contains("формата на болничния лист"), message);

    String badSeries = BgApp9ChartNumber.describeMissing("Z", Integer.valueOf(2026), "1234");
    assertTrue(badSeries.contains("не е сред допустимите"), badSeries);
  }

  @Test
  @DisplayName("⚠️ При кирилско Е съобщението КАЗВА, че буквата е кирилска — иначе е неразличима")
  void theMessageNamesTheLookAlikeTrap() {
    // Оператор с българска подредба пише U+0415 и вижда отказ за буква, която на екрана изглежда
    // точно като допустимата латинска. Без този ред той би сменял година, номер и всичко друго,
    // преди да се сети за серията.
    String message = BgApp9ChartNumber.describeMissing("\u0415", Integer.valueOf(2026), "1234");
    assertTrue(message.contains("КИРИЛСКА"), message);
    assertTrue(message.contains("подредбата на клавиатурата"), message);

    String latin = BgApp9ChartNumber.describeMissing("Z", Integer.valueOf(2026), "1234");
    assertFalse(latin.contains("КИРИЛСКА"), "съобщението не бива да гадае: " + latin);
  }

  @Test
  @DisplayName("Дължината е точно 12 — нито 11, нито 13")
  void lengthIsExactlyTwelve() {
    assertEquals(12, BgApp9ChartNumber.LENGTH);
    assertFalse(BgApp9ChartNumber.isWellFormed("E2026000123"));
    assertFalse(BgApp9ChartNumber.isWellFormed("E202600012345"));
    assertFalse(BgApp9ChartNumber.isWellFormed(null));
  }

  // ═══ Целият е-болничен в „Номер“ ═══
  // Буквите са с Unicode escape НАРОЧНО: в изходния текст кирилското Е (U+0415) и латинското E
  // (U+0045) са неразличими, а свидетелят е точно за разликата между тях.

  @Test
  @DisplayName("Е-болничен с ЛАТИНСКО E в „Номер“ минава без серия и година")
  void electronicNumberWithLatinEPasses() {
    String latin = "\u0045" + "20260000001";
    assertEquals(latin, BgApp9ChartNumber.compose(null, null, latin));
    assertEquals(latin, BgApp9ChartNumber.compose("\u0045", Integer.valueOf(2026), latin));
  }

  @Test
  @DisplayName("⚠️ КИРИЛСКО Е в целия номер: „буквата е кирилска“, не „липсва серия“")
  void cyrillicEInTheWholeNumberNamesTheKeyboardLayout() {
    String cyrillic = "\u0415" + "20260000001";
    assertNull(BgApp9ChartNumber.compose(null, null, cyrillic), "проверката остава същата");

    String message = BgApp9ChartNumber.describeMissing(null, null, cyrillic);
    assertTrue(
        message.contains("буквата е кирилска – сменете подредбата на клавиатурата на латиница"),
        message);
    assertFalse(message.contains("липсва серия"), "праща към серия, която не трябва: " + message);
    assertFalse(message.contains("не са цифри"), message);
    assertTrue(message.contains("формата на болничния лист"), message);

    String cyrillicR = BgApp9ChartNumber.describeMissing(null, null, "\u0420" + "20260000001");
    assertTrue(cyrillicR.contains("буквата е кирилска"), cyrillicR);
  }

  @Test
  @DisplayName("⚠️ Неразделящ интервал в целия номер: отказът казва НЕВИДИМ знак и кой")
  void nonBreakingSpaceInTheWholeNumberIsNamed() {
    String trailing = "\u0045" + "20260000001" + "\u00A0";
    assertNull(BgApp9ChartNumber.compose(null, null, trailing), "проверката остава същата");

    String message = BgApp9ChartNumber.describeMissing(null, null, trailing);
    assertTrue(message.contains("НЕВИДИМ знак"), message);
    assertTrue(message.contains("неразделящ интервал (U+00A0) на позиция 13"), message);
    assertFalse(message.contains("липсва серия"), message);
    assertFalse(message.contains("кирилска"), "латинското E не е кирилско: " + message);

    String inside = BgApp9ChartNumber.describeMissing(null, null, "\u0045" + "2026\u00A00000001");
    assertTrue(inside.contains("на позиция 6"), inside);

    String both = BgApp9ChartNumber.describeMissing(null, null, "\u0415" + "20260000001\u00A0");
    assertTrue(both.contains("буквата е кирилска"), both);
    assertTrue(both.contains("НЕВИДИМ знак"), both);
  }

  @Test
  @DisplayName("Хартиеният лист — както днес; невидим знак в краткия номер също се назовава")
  void paperSheetIsUnchanged() {
    assertEquals(
        "\u0411" + "20260001234",
        BgApp9ChartNumber.compose("\u0411", Integer.valueOf(2026), "1234"));
    String noSeries = BgApp9ChartNumber.describeMissing(null, Integer.valueOf(2026), "1234");
    assertTrue(noSeries.contains("липсва серия"), noSeries);
    assertTrue(
        BgApp9ChartNumber.describeMissing("\u0415", Integer.valueOf(2026), "1234")
            .contains("КИРИЛСКА"));

    String nbsp = BgApp9ChartNumber.describeMissing("\u0045", Integer.valueOf(2026), "1234\u00A0");
    assertNull(BgApp9ChartNumber.compose("\u0045", Integer.valueOf(2026), "1234\u00A0"));
    assertTrue(nbsp.contains("неразделящ интервал"), nbsp);
  }

  @Test
  @DisplayName("Друга буква в целия номер: отказ по образеца, без да гадае за кирилица")
  void otherLetterInTheWholeNumberIsRefusedByTheForm() {
    String message = BgApp9ChartNumber.describeMissing(null, null, "Z20260000001");
    assertTrue(message.contains("номерът започва с „Z“"), message);
    assertFalse(message.contains("кирилска"), message);
  }
}
