package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Para6FileLayout;
import com.axelor.apps.bglocalization.service.art73.adapter.BgArt73Para6Xsd;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileAnnual;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FileIncomeRow;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para6FilePersonRow;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛЪТ ПО АЛ. 6 — ВЛОЖЕНАТА СТРУКТУРА, БАЙТОВЕТЕ И СХЕМАТА {@code dec736}.
 *
 * <p><b>⚠️ ЕТАЛОНЪТ Е РЪЧЕН, И ТОВА Е ОБЯВЕНО.</b> Реален ПРИЕТ от НАП {@code SPR73_6} няма. Затова
 * тестът пази ДВЕ различни неща: еталонът пази ОФОРМЛЕНИЕТО от регресия, а независимата проверка е
 * валидацията на БАЙТОВЕТЕ срещу закрепената схема. Появи ли се приет файл — слага се в {@code
 * src/test/resources/nap/} и се сверява байт по байт, както при ДДС.
 *
 * <p>⚠️ Случаят нарочно е НАЙ-ТРУДНИЯТ, който заповедта описва: едно лице с ДВА работодателя, от
 * които единият е преобразуващо се предприятие (т. 16), плюс пълен блок по чл. 49 с ОТРИЦАТЕЛНА
 * разлика за изравняване.
 */
class BgArt73Para6XmlTest {

  private final BgArt73Para6FileLayout layout = new BgArt73Para6FileLayout();

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static BgArt73Para6FileIncomeRow row(
      String code, String eik, String name, String income, String tax, String predecessor) {
    BgArt73Para6FileIncomeRow row = new BgArt73Para6FileIncomeRow();
    row.setIncomeCode(code);
    row.setEmployerIdentifier(eik);
    row.setEmployerName(name);
    row.setIncome(bd(income));
    row.setAdvanceTax(bd(tax));
    row.setPredecessorIdentifier(predecessor);
    return row;
  }

  /** Лице с два работодателя и пълен блок по чл. 49. */
  private static BgArt73Para6FilePersonRow person() {
    BgArt73Para6FilePersonRow person = new BgArt73Para6FilePersonRow();
    person.setCorrectionCode("0");
    person.setFirstName("Иван");
    person.setMiddleName("Петров");
    person.setLastName("Георгиев");
    person.setIdentifierType("0");
    person.setIdentifier("8001010013");
    person.setMainEmployer(true);

    BgArt73Para6FileIncomeRow own =
        row("101", "100000033", "НЯКОЕ ЕООД", "24000.00", "2280.00", null);
    own.setInsuranceBg(bd("1920.00"));
    person.getIncomeRows().add(own);

    // ⚠️ Т. 16: доходът от преобразуващото се дружество е ОТДЕЛЕН ред със същия код.
    person
        .getIncomeRows()
        .add(row("101", "100000033", "НЯКОЕ ЕООД", "6000.00", "570.00", "831234567"));

    person.setTaxBaseArt25Para1(bd("28080.00"));

    BgArt73Para6FileAnnual annual = new BgArt73Para6FileAnnual();
    annual.setTaxBase(bd("28080.00"));
    annual.setReliefChildren(bd("6000.00"));
    annual.setTaxBaseAfterReliefs(bd("22080.00"));
    annual.setTax(bd("2208.00"));
    // ⚠️ ОТРИЦАТЕЛНА: удържано е повече от дължимото, тоест разликата се ВЪЗСТАНОВЯВА.
    annual.setSettlement(bd("-642.00"));
    annual.setRefundedByJanuary(bd("642.00"));
    person.setAnnual(annual);

    person.setTotalTaxWithheld(bd("2208.00"));
    return person;
  }

  private static BgArt73Para6FileContent content(BgArt73Para6FilePersonRow... persons) {
    BgArt73Para6FileContent content = new BgArt73Para6FileContent();
    content.setTaxYear(2026);
    content.setPayerIdentifier("100000033");
    content.setPayerName("НЯКОЕ ЕООД");
    content.setContactPhone("0700 18 700");
    content.setContactEmail("office@example.bg");
    for (BgArt73Para6FilePersonRow person : persons) {
      content.getPersons().add(person);
    }
    return content;
  }

  @Test
  @DisplayName("Форматът Е потвърден — заповед З-ЦУ-1877/05.12.2019")
  void форматът_е_потвърден() {
    assertTrue(layout.isConfirmed());
    assertEquals("SPR73_6.xml", layout.fileName());
    assertEquals("WINDOWS-1251", layout.encoding());
  }

  @Test
  @DisplayName("Документът съвпада ЗНАК ПО ЗНАК с еталона — лице с 2 работодателя и чл. 49")
  void документът_съвпада_с_еталона() {
    String expected =
        "<?xml version=\"1.0\" encoding=\"WINDOWS-1251\"?>\n"
            + "<dec736>\n"
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
            + "\t\t\t<firstname>Иван</firstname>\n"
            + "\t\t\t<secondname>Петров</secondname>\n"
            + "\t\t\t<thirdname>Георгиев</thirdname>\n"
            + "\t\t\t<identtype>0</identtype>\n"
            + "\t\t\t<ident>8001010013</ident>\n"
            + "\t\t\t<ismainemployer>1</ismainemployer>\n"
            + "\t\t\t<incomedata>\n"
            + "\t\t\t\t<incomerows>\n"
            + "\t\t\t\t\t<rowsenum>\n"
            + "\t\t\t\t\t\t<incomecode>101</incomecode>\n"
            + "\t\t\t\t\t\t<employereik>100000033</employereik>\n"
            + "\t\t\t\t\t\t<employername>НЯКОЕ ЕООД</employername>\n"
            + "\t\t\t\t\t\t<income>24000.00</income>\n"
            + "\t\t\t\t\t\t<healthinsbg>1920.00</healthinsbg>\n"
            + "\t\t\t\t\t\t<advancetax>2280.00</advancetax>\n"
            + "\t\t\t\t\t</rowsenum>\n"
            + "\t\t\t\t\t<rowsenum>\n"
            + "\t\t\t\t\t\t<incomecode>101</incomecode>\n"
            + "\t\t\t\t\t\t<employereik>100000033</employereik>\n"
            + "\t\t\t\t\t\t<employername>НЯКОЕ ЕООД</employername>\n"
            + "\t\t\t\t\t\t<income>6000.00</income>\n"
            + "\t\t\t\t\t\t<advancetax>570.00</advancetax>\n"
            + "\t\t\t\t\t\t<eiktransfrom>831234567</eiktransfrom>\n"
            + "\t\t\t\t\t</rowsenum>\n"
            + "\t\t\t\t</incomerows>\n"
            + "\t\t\t\t<taxbase251>28080.00</taxbase251>\n"
            + "\t\t\t\t<taxbase49>\n"
            + "\t\t\t\t\t<taxbase>28080.00</taxbase>\n"
            + "\t\t\t\t\t<taxreductionchildren>6000.00</taxreductionchildren>\n"
            + "\t\t\t\t\t<taxbase491>22080.00</taxbase491>\n"
            + "\t\t\t\t\t<tax>2208.00</tax>\n"
            + "\t\t\t\t\t<diff18>-642.00</diff18>\n"
            + "\t\t\t\t\t<sum19refund>642.00</sum19refund>\n"
            + "\t\t\t\t</taxbase49>\n"
            + "\t\t\t\t<sumtaxdeducted>2208.00</sumtaxdeducted>\n"
            + "\t\t\t</incomedata>\n"
            + "\t\t</rowsenum>\n"
            + "\t</part2>\n"
            + "</dec736>\n";
    assertEquals(expected, layout.document(content(person())));
  }

  @Test
  @DisplayName("⚠️ Байтовете минават СХЕМАТА dec736")
  void байтовете_минават_схемата() throws IOException {
    byte[] bytes = encode(layout.document(content(person())));
    List<BgDeclarationFileFinding> findings =
        BgArt73XsdValidator.validate(bytes, BgArt73Para6Xsd.RESOURCE, BgArt73Para6Xsd.describe());
    assertTrue(findings.isEmpty(), findings.toString());
  }

  @Test
  @DisplayName("⚠️ Файлът на ал. 6 НЕ минава схемата на ал. 1 — двете не са взаимозаменяеми")
  void файлът_не_минава_чуждата_схема() throws IOException {
    byte[] bytes = encode(layout.document(content(person())));
    assertFalse(BgArt73XsdValidator.validate(bytes).isEmpty(), "dec731 не описва dec736");
  }

  @Test
  @DisplayName("Байтовете СА в CP1251, не в UTF-8 — кирилицата е по един байт")
  void байтовете_са_в_cp1251() throws IOException {
    String document = layout.document(content(person()));
    byte[] bytes = encode(document);
    assertEquals(document, new String(bytes, Charset.forName("WINDOWS-1251")));
    assertFalse(document.equals(new String(bytes, StandardCharsets.UTF_8)));
  }

  @Test
  @DisplayName("⚠️ Знак извън CP1251 е ОТКАЗ, а не мълчалива въпросителна")
  void непредставимият_знак_пада() {
    BgArt73Para6FilePersonRow person = person();
    // ⚠️ Румънско „ș“: напълно законно име. През getBytes() то би станало „?“ и файлът щеше да
    // бъде ПРИЕТ с изкривено име.
    person.setLastName("Mureșan");
    String document = layout.document(content(person));

    IOException e = assertThrows(IOException.class, () -> encode(document));
    assertTrue(e.getMessage().contains("WINDOWS-1251"), e.getMessage());
  }

  @Test
  @DisplayName("⚠️ Лице без доходи ПРОПУСКА целия incomedata, вместо да го изпише празен")
  void лице_без_доходи_няма_блок() throws IOException {
    BgArt73Para6FilePersonRow bare = new BgArt73Para6FilePersonRow();
    bare.setCorrectionCode("8");
    bare.setFirstName("Мария");
    bare.setLastName("Иванова");
    bare.setIdentifierType("0");
    bare.setIdentifier("8001010013");

    String document = layout.document(content(bare));
    assertFalse(document.contains("<incomedata>"), document);
    assertFalse(document.contains("<sumtaxdeducted>"), document);
    // ⚠️ Празен блок би паднал на схемата заради собственото си задължително дете.
    byte[] bytes = encode(document);
    assertTrue(
        BgArt73XsdValidator.validate(bytes, BgArt73Para6Xsd.RESOURCE, BgArt73Para6Xsd.describe())
            .isEmpty(),
        "лице без доходи е структурно валидно");
  }

  @Test
  @DisplayName("⚠️ Трите облекчения НА РЕД не се пишат — „Ред и формат“, редове 8/9/10 = N")
  void редовите_облекчения_липсват() {
    String document = layout.document(content(person()));
    int rowLevel = document.indexOf("<incomerows>");
    int annual = document.indexOf("<taxbase49>");
    String incomeRows = document.substring(rowLevel, annual);
    assertFalse(incomeRows.contains("taxreduction"), incomeRows);
    // ⚠️ В блока по чл. 49 обаче ГИ ИМА — те са ГОДИШНИТЕ по чл. 49 ал. 3, друго нещо.
    assertTrue(document.substring(annual).contains("taxreductionchildren"), document);
  }

  private static byte[] encode(String document) throws IOException {
    return BgArt73Para1FileService.encode(document, "WINDOWS-1251");
  }
}
