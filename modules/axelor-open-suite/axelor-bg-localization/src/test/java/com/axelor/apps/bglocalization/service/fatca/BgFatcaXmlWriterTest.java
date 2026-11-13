package com.axelor.apps.bglocalization.service.fatca;

import static com.axelor.apps.bglocalization.service.fatca.BgFatcaTestData.GIIN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.common.BgXsdValidator;
import com.axelor.apps.bglocalization.service.fatca.adapter.BgFatcaXmlWriter;
import com.axelor.apps.bglocalization.service.fatca.adapter.BgFatcaXsd;
import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ПИСАЧЪТ СРЕЩУ СХЕМАТА — всеки вид файл минава FATCA XML v2.0.1 и носи това, което трябва. */
class BgFatcaXmlWriterTest {

  private static String valid(BgFatcaFileContent content) {
    byte[] bytes = BgFatcaXmlWriter.toBytes(content);
    List<BgDeclarationFileFinding> findings =
        BgXsdValidator.validate(
            bytes, BgFatcaXsd.RESOURCE, BgFatcaXsd.describe(), BgFatcaXsd.CODE_PREFIX);
    assertTrue(findings.isEmpty(), "схемата: " + findings);
    return new String(bytes, StandardCharsets.UTF_8);
  }

  @Test
  @DisplayName("Нулев отчет: NilReport, без AccountReport, версията от константата")
  void нулев_отчет() {
    String xml = valid(BgFatcaTestData.nil());
    // StAX пише декларацията с единични кавички — валиден XML, кодировката е UTF-8.
    assertTrue(xml.startsWith("<?xml version='1.0' encoding='UTF-8'?>"), xml);
    assertTrue(xml.contains("version=\"" + BgFatcaXsd.VERSION + "\""), xml);
    assertTrue(xml.contains("<ftc:NoAccountToReport>yes</ftc:NoAccountToReport>"), xml);
    assertTrue(xml.contains("<ftc:DocRefId>" + GIIN + ".nil-1</ftc:DocRefId>"), xml);
    assertFalse(xml.contains("AccountReport"), xml);
    assertTrue(xml.contains("<sfa:SendingCompanyIN>" + GIIN + "</sfa:SendingCompanyIN>"), xml);
    assertTrue(xml.contains("<sfa:TransmittingCountry>BG</sfa:TransmittingCountry>"), xml);
    assertTrue(xml.contains("<sfa:ReceivingCountry>US</sfa:ReceivingCountry>"), xml);
    assertTrue(xml.contains("<sfa:ReportingPeriod>2025-12-31</sfa:ReportingPeriod>"), xml);
    // Pub 5124: без части от секундата.
    assertTrue(xml.contains("<sfa:Timestamp>2026-10-27T10:15:30</sfa:Timestamp>"), xml);
    assertTrue(xml.contains("<ftc:DocTypeIndic>FATCA1</ftc:DocTypeIndic>"), xml);
    assertTrue(xml.contains("<sfa:Name>Тестова Банка АД</sfa:Name>"), xml);
  }

  @Test
  @DisplayName("Физическо лице: TIN от САЩ без тирета, име, рождена дата, само ненулевите плащания")
  void физическо_лице() {
    String xml = valid(BgFatcaTestData.nil().addAccount(BgFatcaTestData.individual(1)));
    assertFalse(xml.contains("NilReport"), xml);
    assertTrue(xml.contains("<sfa:TIN issuedBy=\"US\">000000001</sfa:TIN>"), xml);
    assertTrue(xml.contains("<sfa:FirstName>John</sfa:FirstName>"), xml);
    assertTrue(xml.contains("<sfa:BirthDate>1980-01-02</sfa:BirthDate>"), xml);
    assertTrue(
        xml.contains("<ftc:AccountBalance currCode=\"USD\">12345.68</ftc:AccountBalance>"), xml);
    assertTrue(xml.contains("<ftc:Type>FATCA502</ftc:Type>"), xml);
    assertFalse(xml.contains("FATCA501"), "нулевите дивиденти не се пишат: " + xml);
    assertFalse(xml.contains("AccountClosed"), xml);
  }

  @Test
  @DisplayName("Юридическо лице: Organisation + AcctHolderType; закрита сметка")
  void юридическо_лице() {
    BgFatcaFileContent c =
        BgFatcaTestData.nil()
            .addAccount(BgFatcaTestData.organisation(2, "FATCA104").setClosed(true))
            .addAccount(BgFatcaTestData.individual(3).setBirthDate(null).setCity(null));
    String xml = valid(c);
    assertTrue(xml.contains("<ftc:AcctHolderType>FATCA104</ftc:AcctHolderType>"), xml);
    assertTrue(xml.contains("<ftc:AccountClosed>true</ftc:AccountClosed>"), xml);
    assertTrue(xml.contains("<ftc:Type>FATCA503</ftc:Type>"), xml);
    assertEquals(2, count(xml, "<ftc:AccountReport>"));
  }

  @Test
  @DisplayName(
      "Закрита сметка: AccountClosed и салдото ПРЕДИ закриването, не 0 (Споразумение, чл. 2, т. 2,"
          + " буква d))")
  void закрита_сметка() {
    BgFatcaFileContent c =
        BgFatcaTestData.nil()
            .addAccount(
                BgFatcaTestData.individual(4)
                    .setClosed(true)
                    .setBalance(new java.math.BigDecimal("250")));
    assertTrue(BgFatcaFileValidator.validate(c, BgFatcaTestData.TODAY).isEmpty());
    String xml = valid(c);
    assertTrue(xml.contains("<ftc:AccountClosed>true</ftc:AccountClosed>"), xml);
    assertTrue(
        xml.contains("<ftc:AccountBalance currCode=\"USD\">250.00</ftc:AccountBalance>"), xml);
    // Редът на схемата: AccountClosed е между AccountNumber и AccountHolder.
    assertTrue(xml.indexOf("AccountNumber") < xml.indexOf("AccountClosed"), xml);
    assertTrue(xml.indexOf("AccountClosed") < xml.indexOf("AccountHolder"), xml);
  }

  @Test
  @DisplayName("Тестов файл: FATCA11 навсякъде")
  void тестов_файл() {
    String xml =
        valid(BgFatcaTestData.nil().setTestFile(true).addAccount(BgFatcaTestData.individual(1)));
    assertFalse(xml.contains("<ftc:DocTypeIndic>FATCA1</ftc:DocTypeIndic>"), xml);
    assertEquals(2, count(xml, "<ftc:DocTypeIndic>FATCA11</ftc:DocTypeIndic>"));
  }

  @Test
  @DisplayName("Институция без населено място: адресът е свободен текст и пак минава схемата")
  void адрес_без_град() {
    String xml = valid(BgFatcaTestData.nil().setFiCity(null));
    assertTrue(xml.contains("<sfa:AddressFree>1000 ул. Измислена 1</sfa:AddressFree>"), xml);
  }

  @Test
  @DisplayName("Схемата хваща грешен код на валута — втората врата работи")
  void схемата_хваща() {
    byte[] bytes =
        BgFatcaXmlWriter.toBytes(
            BgFatcaTestData.nil().addAccount(BgFatcaTestData.individual(1).setCurrencyCode("XXQ")));
    List<BgDeclarationFileFinding> findings =
        BgXsdValidator.validate(
            bytes, BgFatcaXsd.RESOURCE, BgFatcaXsd.describe(), BgFatcaXsd.CODE_PREFIX);
    assertFalse(findings.isEmpty());
    assertEquals("FATCA_SCHEMA_ERROR", findings.get(0).getCode(), findings.toString());
  }

  private static int count(String text, String needle) {
    int n = 0;
    for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
      n++;
    }
    return n;
  }
}
