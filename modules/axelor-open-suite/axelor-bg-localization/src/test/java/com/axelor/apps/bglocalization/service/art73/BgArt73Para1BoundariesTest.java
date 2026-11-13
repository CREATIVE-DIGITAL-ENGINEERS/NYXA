package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГРАНИЦИТЕ НА СПРАВКАТА ПО ЧЛ. 73 АЛ. 1 — че са НАПИСАНИ, и то с числа.
 *
 * <p><b>Защо това изобщо се тества.</b> Указание т. 6 към образеца: „Справка, която не съдържа
 * всички изискуеми данни, не се счита за достоверна и не се приема!“, а по чл. 82 ЗДДФЛ за неверни
 * или непълни данни се носи отговорност. Текстът с границите е това, което човекът чете, преди да
 * поеме тази отговорност — тоест той е ЧАСТ ОТ ОТГОВОРА, а не украса, и мълчаливото му изчезване е
 * дефект като всеки друг.
 */
class BgArt73Para1BoundariesTest {

  @Test
  @DisplayName(
      "⚠️ ДИВИДЕНТИ · правилото за годината на РЕШЕНИЕТО е назовано дословно, с брой редове")
  void theDividendExceptionIsNamedWithItsRowCount() {
    BgArt73Para1Boundaries.Facts facts = new BgArt73Para1Boundaries.Facts();
    facts.rows = 5;
    facts.dividendRows = 2;

    String text = BgArt73Para1Boundaries.report(facts);

    assertTrue(
        text.contains("РЕШЕНИЕТО"),
        "указание т. 4 слага дивидентите в справката за годината на решението — текстът трябва да"
            + " го каже");
    assertTrue(
        text.contains("2 реда с кодове 814 или 8141"),
        "предупреждение без ЧИСЛО не праща никого никъде: " + text);
  }

  @Test
  @DisplayName("МЪЛЧАНИЕ · без дивиденти в справката текстът не вдига тревога")
  void withoutDividendRowsTheWarningStaysSilent() {
    BgArt73Para1Boundaries.Facts facts = new BgArt73Para1Boundaries.Facts();
    facts.rows = 3;

    String text = BgArt73Para1Boundaries.report(facts);

    assertFalse(
        text.contains("реда с кодове 814 или 8141"),
        "тревога, която стои и когато няма какво да се провери, престава да се чете");
    assertTrue(text.contains("няма такива редове"), text);
  }

  @Test
  @DisplayName("⚠️ БЕЗ ПЛАТЕЦ · редовете, които НЕ са приписани на подателя, се назовават с брой")
  void rowsWithoutAPayerAreNamedAndNotSwallowed() {
    BgArt73Para1Boundaries.Facts facts = new BgArt73Para1Boundaries.Facts();
    facts.rows = 0;
    facts.withoutPayer = 12;

    String text = BgArt73Para1Boundaries.report(facts);

    assertTrue(
        text.contains("12 реда за тази година БЕЗ посочен платец"),
        "празна справка при 12 непосочени реда изглежда като „нищо не е въвеждано“: " + text);
  }

  @Test
  @DisplayName("ЧИСТО · когато нищо не е останало извън справката, това се КАЗВА")
  void aCleanReportSaysSoInsteadOfStayingSilent() {
    BgArt73Para1Boundaries.Facts facts = new BgArt73Para1Boundaries.Facts();
    facts.rows = 7;

    String text = BgArt73Para1Boundaries.report(facts);

    assertTrue(
        text.contains("нищо — всеки ред на регистъра за годината е намерил мястото си"),
        "мълчанието тук е неразличимо от липсващ раздел: " + text);
  }

  @Test
  @DisplayName("СРОК · 28 февруари и корекцията до 30 септември стоят в текста")
  void bothDeadlinesAreWritten() {
    String text = BgArt73Para1Boundaries.report(new BgArt73Para1Boundaries.Facts());

    assertTrue(text.contains("28 февруари"), "срокът за подаване по указание т. 2");
    assertTrue(text.contains("30 СЕПТЕМВРИ"), "срокът за корекция по указание т. 2");
    assertTrue(
        text.contains("чл. 162 от ЗКПО"),
        "указание т. 3: при заличаване/прекратяване срокът е друг");
  }

  @Test
  @DisplayName("⚠️ ТРАНСПОРТ · текстът казва, че закачането НЕ е подаване")
  void theAttachmentIsDeclaredNotToBeASubmission() {
    String text = BgArt73Para1Boundaries.report(new BgArt73Para1Boundaries.Facts());

    assertTrue(text.contains("НЕ Е ПОДАВАНЕ"), text);
    assertTrue(text.contains("ръчно на портала"), text);
  }
}
