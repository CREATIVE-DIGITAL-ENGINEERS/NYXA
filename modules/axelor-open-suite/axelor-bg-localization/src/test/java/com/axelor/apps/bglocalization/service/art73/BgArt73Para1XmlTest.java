package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Para1FileLayout;
import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Xsd;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileRow;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛЪТ ПО {@code dec731} — знак по знак и срещу СХЕМАТА.
 *
 * <p>Това е свидетелят, който замени изключения „golden placeholder“: форматът дойде със заповед
 * {@link BgArt73Xsd#ORDER} и мястото на отказа зае проверка на съдържанието.
 *
 * <p>⚠️ Еталонът е РЪЧЕН: реален ПРИЕТ от НАП файл още нямаме. Затова тестът твърди две различни
 * неща — че писачът дава точно този текст (регресия на оформлението) и че БАЙТОВЕТЕ минават схемата
 * (независима проверка). Първото пада при всяка промяна в писача; второто — само когато промяната е
 * невалидна.
 */
class BgArt73Para1XmlTest {

  private final BgArt73Para1FileLayout layout = new BgArt73Para1FileLayout();

  private static BgArt73Para1FileRow person() {
    BgArt73Para1FileRow row = new BgArt73Para1FileRow();
    row.setCorrectionCode("0");
    row.setIdentifierType("0");
    row.setIdentifier("8001010013");
    row.setFirstName("Иван");
    row.setMiddleName("Петров");
    row.setLastName("Георгиев");
    row.setIncomeCode("307");
    row.setGrossAmount(new BigDecimal("1200"));
    row.setInsuranceAmount(new BigDecimal("100.5"));
    row.setTaxAmount(new BigDecimal("110"));
    return row;
  }

  private static BgArt73Para1FileContent content(BgArt73Para1FileRow... rows) {
    BgArt73Para1FileContent content = new BgArt73Para1FileContent();
    content.setTaxYear(2026);
    content.setPayerIdentifier("100000033");
    content.setPayerName("НЯКОЕ ЕООД");
    content.setContactPhone("0700 18 700");
    content.setContactEmail("office@example.bg");
    for (BgArt73Para1FileRow row : rows) {
      content.getRows().add(row);
    }
    return content;
  }

  @Test
  @DisplayName("Форматът Е потвърден — заповед З-ЦУ-1944/17.12.2019")
  void форматът_е_потвърден() {
    assertTrue(layout.isConfirmed());
    assertEquals("SPR73_1.xml", layout.fileName());
    assertEquals("WINDOWS-1251", layout.encoding());
  }

  @Test
  @DisplayName("Документът съвпада ЗНАК ПО ЗНАК с еталона")
  void документът_съвпада_с_еталона() {
    String expected =
        "<?xml version=\"1.0\" encoding=\"WINDOWS-1251\"?>\n"
            + "<dec731>\n"
            + "\t<isterm>0</isterm>\n"
            + "\t<year>2026</year>\n"
            + "\t<part1>\n"
            + "\t\t<eik>100000033</eik>\n"
            + "\t\t<name>НЯКОЕ ЕООД</name>\n"
            + "\t\t<phone>0700 18 700</phone>\n"
            + "\t\t<mail>office@example.bg</mail>\n"
            + "\t</part1>\n"
            + "\t<part2>\n"
            + "\t\t<rowsenum>\n"
            + "\t\t\t<correctioncode>0</correctioncode>\n"
            + "\t\t\t<foreign>0</foreign>\n"
            + "\t\t\t<identtype>0</identtype>\n"
            + "\t\t\t<ident>8001010013</ident>\n"
            + "\t\t\t<firstname>Иван</firstname>\n"
            + "\t\t\t<secondname>Петров</secondname>\n"
            + "\t\t\t<thirdname>Георгиев</thirdname>\n"
            + "\t\t\t<incomecode>307</incomecode>\n"
            + "\t\t\t<income>1200.00</income>\n"
            + "\t\t\t<healthins>100.50</healthins>\n"
            + "\t\t\t<tax>110.00</tax>\n"
            + "\t\t</rowsenum>\n"
            + "\t</part2>\n"
            + "</dec731>\n";
    assertEquals(expected, layout.document(content(person())));
  }

  @Test
  @DisplayName("⚠️ Байтовете минават СХЕМАТА dec731")
  void байтовете_минават_схемата() throws IOException {
    byte[] bytes =
        BgArt73Para1FileService.encode(layout.document(content(person())), "WINDOWS-1251");
    List<BgDeclarationFileFinding> findings = BgArt73XsdValidator.validate(bytes);
    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  @DisplayName("Байтовете СА в CP1251, не в UTF-8 — кирилицата е по един байт")
  void байтовете_са_в_cp1251() throws IOException {
    String document = layout.document(content(person()));
    byte[] bytes = BgArt73Para1FileService.encode(document, "WINDOWS-1251");
    // ⚠️ Прочетени като CP1251, байтовете дават ОБРАТНО същия текст; прочетени като UTF-8 — не.
    assertEquals(document, new String(bytes, Charset.forName("WINDOWS-1251")));
    assertFalse(document.equals(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("⚠️ Знак извън CP1251 е ОТКАЗ, а не мълчалива въпросителна")
  void непредставимият_знак_пада() {
    BgArt73Para1FileRow row = person();
    // ⚠️ Румънско „ș“: напълно законно име на чуждестранно лице. През getBytes() то би станало „?“
    // и файлът щеше да бъде ПРИЕТ с изкривено име.
    row.setLastName("Mureșan");
    BgArt73Para1FileContent content = content(row);

    IOException e =
        assertThrows(
            IOException.class,
            () -> BgArt73Para1FileService.encode(layout.document(content), "WINDOWS-1251"));
    assertTrue(e.getMessage().contains("WINDOWS-1251"), e.getMessage());
  }

  @Test
  @DisplayName("⚠️ Сборният ред НЕ носи колони 2–9 — пропуснати, не празни")
  void сборният_ред_мълчи_по_колони_2_9() throws IOException {
    BgArt73Para1FileRow aggregate = new BgArt73Para1FileRow();
    aggregate.setCorrectionCode("0");
    aggregate.setIncomeCode("8141");
    aggregate.setAggregateRow(true);
    aggregate.setReportsPersonCount(true);
    aggregate.setPersonCount(7);
    aggregate.setGrossAmount(new BigDecimal("450.00"));

    String document = layout.document(content(aggregate));
    assertFalse(document.contains("<foreign>"), document);
    assertFalse(document.contains("<identtype>"), document);
    assertFalse(document.contains("<ident>"), document);
    assertFalse(document.contains("<firstname>"), document);
    assertTrue(document.contains("<persons>7</persons>"), document);

    assertTrue(
        BgArt73XsdValidator.validate(BgArt73Para1FileService.encode(document, "WINDOWS-1251"))
            .isEmpty());
  }

  @Test
  @DisplayName("Датата на раждане е xs:date (ГГГГ-ММ-ДД) и изключва идентификатора")
  void датата_на_раждане_е_iso() throws IOException {
    BgArt73Para1FileRow foreign = person();
    foreign.setIdentifierType("3");
    foreign.setIdentifier(null);
    foreign.setBirthDate(LocalDate.of(1980, 1, 1));
    foreign.setForeignPerson(true);
    foreign.setCountryCode("DE");

    String document = layout.document(content(foreign));
    assertTrue(document.contains("<birthdate>1980-01-01</birthdate>"), document);
    assertFalse(document.contains("<ident>"), document);
    assertTrue(document.contains("<countrycode>DE</countrycode>"), document);
    assertTrue(
        BgArt73XsdValidator.validate(BgArt73Para1FileService.encode(document, "WINDOWS-1251"))
            .isEmpty());
  }

  @Test
  @DisplayName("Сумите са с ТОЧКА и два знака — никога експоненциален запис")
  void сумите_са_с_две_дробни() {
    BgArt73Para1FileRow big = person();
    big.setGrossAmount(new BigDecimal("12000000"));
    String document = layout.document(content(big));
    assertTrue(document.contains("<income>12000000.00</income>"), document);
    assertFalse(document.contains("E7"), document);
  }
}
