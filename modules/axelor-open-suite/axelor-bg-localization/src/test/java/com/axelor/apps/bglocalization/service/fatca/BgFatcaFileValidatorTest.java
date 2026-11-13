package com.axelor.apps.bglocalization.service.fatca;

import static com.axelor.apps.bglocalization.service.fatca.BgFatcaTestData.TODAY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.fatca.adapter.BgFatcaXsd;
import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationPlannedFile;
import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ОТКАЗИТЕ ПРЕДИ ФАЙЛА — по един за всяко задължително правило, с назована сметка. */
class BgFatcaFileValidatorTest {

  private static List<String> blocking(BgFatcaFileContent c) {
    return BgFatcaFileValidator.validate(c, TODAY).stream()
        .filter(BgDeclarationFileFinding::isBlocking)
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toList());
  }

  private static BgFatcaFileContent with(BgFatcaFileContent.Account a) {
    return BgFatcaTestData.nil().addAccount(a);
  }

  @Test
  @DisplayName("Пълните данни нямат блокиращи находки")
  void чисто() {
    assertTrue(blocking(BgFatcaTestData.nil()).isEmpty());
    assertTrue(blocking(with(BgFatcaTestData.individual(1))).isEmpty());
    assertTrue(blocking(with(BgFatcaTestData.organisation(2, "FATCA101"))).isEmpty());
  }

  @Test
  @DisplayName("GIIN: липсва, грешен формат; чужда държава е само предупреждение")
  void giin() {
    assertEquals(List.of("FATCA_GIIN_MISSING"), blocking(BgFatcaTestData.nil().setGiin(null)));
    assertEquals(
        List.of("FATCA_GIIN_FORMAT"), blocking(BgFatcaTestData.nil().setGiin("Z9Z9Z9-99999")));
    List<BgDeclarationFileFinding> foreign =
        BgFatcaFileValidator.validate(BgFatcaTestData.nil().setGiin("Z9Z9Z9.99999.SL.840"), TODAY);
    assertTrue(foreign.stream().noneMatch(BgDeclarationFileFinding::isBlocking), "" + foreign);
    assertEquals("FATCA_GIIN_NOT_BG", foreign.get(0).getCode());
  }

  @Test
  @DisplayName("Годината: още отворена или преди 2014")
  void година() {
    assertEquals(List.of("FATCA_YEAR_OPEN"), blocking(BgFatcaTestData.nil().setReportYear(2026)));
    assertEquals(
        List.of("FATCA_YEAR_TOO_EARLY"), blocking(BgFatcaTestData.nil().setReportYear(2013)));
  }

  @Test
  @DisplayName("Институцията: без наименование и без адрес")
  void институцията() {
    assertEquals(List.of("FATCA_FI_NAME_MISSING"), blocking(BgFatcaTestData.nil().setFiName(" ")));
    assertEquals(
        List.of("FATCA_FI_ADDRESS_MISSING"),
        blocking(BgFatcaTestData.nil().setFiCity(null).setFiStreet(null)));
  }

  @Test
  @DisplayName("Физическо лице без US TIN и без рождена дата")
  void tin_или_рождена_дата() {
    assertEquals(
        List.of("FATCA_TIN_OR_BIRTHDATE"),
        blocking(with(BgFatcaTestData.individual(1).setUsTin(null).setBirthDate(null))));
    assertTrue(blocking(with(BgFatcaTestData.individual(1).setUsTin(null))).isEmpty());
    assertTrue(blocking(with(BgFatcaTestData.individual(1).setBirthDate(null))).isEmpty());
  }

  @Test
  @DisplayName("FATCA102 спира с назована причина — непълен файл не се пише")
  void контролиращи_лица() {
    List<BgDeclarationFileFinding> findings =
        BgFatcaFileValidator.validate(with(BgFatcaTestData.organisation(7, "FATCA102")), TODAY);
    assertEquals(1, findings.size(), findings.toString());
    BgDeclarationFileFinding f = findings.get(0);
    assertEquals("FATCA_SUBSTANTIAL_OWNERS_UNSUPPORTED", f.getCode());
    assertTrue(f.isBlocking());
    assertTrue(f.getMessage().contains("TEST-ORG-7"), f.getMessage());
    assertEquals(Long.valueOf(7), f.getTargetId());
  }

  @Test
  @DisplayName("Сметка: номер, дубликат, вид титуляр, имена, адрес, салдо, валута, плащания")
  void сметка() {
    assertEquals(
        List.of("FATCA_ACCOUNT_NUMBER_MISSING"),
        blocking(with(BgFatcaTestData.individual(1).setAccountNumber(null))));
    assertEquals(
        List.of("FATCA_ACCOUNT_DUPLICATE"),
        blocking(
            with(BgFatcaTestData.individual(1))
                .addAccount(BgFatcaTestData.individual(2).setAccountNumber("TEST-ACC-1"))));
    assertEquals(
        List.of("FATCA_HOLDER_TYPE_MISSING"),
        blocking(with(BgFatcaTestData.individual(1).setHolderType(null))));
    assertEquals(
        List.of("FATCA_NAME_MISSING"),
        blocking(with(BgFatcaTestData.individual(1).setLastName(null))));
    assertEquals(
        List.of("FATCA_ORG_NAME_MISSING"),
        blocking(with(BgFatcaTestData.organisation(2, "FATCA101").setOrganisationName(""))));
    assertEquals(
        List.of("FATCA_ACCT_HOLDER_TYPE_MISSING"),
        blocking(with(BgFatcaTestData.organisation(2, null))));
    assertEquals(
        List.of("FATCA_US_TIN_REQUIRED"),
        blocking(with(BgFatcaTestData.organisation(2, "FATCA104").setUsTin(null))));
    assertEquals(
        List.of("FATCA_US_TIN_FORMAT"),
        blocking(with(BgFatcaTestData.individual(1).setUsTin("12345"))));
    assertEquals(
        List.of("FATCA_COUNTRY_MISSING"),
        blocking(with(BgFatcaTestData.individual(1).setCountryCode(null))));
    assertEquals(
        List.of("FATCA_ADDRESS_MISSING"),
        blocking(with(BgFatcaTestData.individual(1).setCity(null).setAddressFree(null))));
    assertEquals(
        List.of("FATCA_BALANCE_NEGATIVE"),
        blocking(with(BgFatcaTestData.individual(1).setBalance(new BigDecimal("-1")))));
    assertEquals(
        List.of("FATCA_CURRENCY_MISSING"),
        blocking(with(BgFatcaTestData.individual(1).setCurrencyCode(null))));
    assertEquals(
        List.of("FATCA_PAYMENT_NEGATIVE"),
        blocking(with(BgFatcaTestData.individual(1).setOther(new BigDecimal("-5")))));
  }

  @Test
  @DisplayName("Поредиците, които IRS отхвърля: --, /*, &#")
  void забранени_знаци() {
    assertEquals(
        List.of("FATCA_FORBIDDEN_CHARS"),
        blocking(with(BgFatcaTestData.individual(1).setAddressFree("1 Test -- Street"))));
    assertEquals(
        List.of("FATCA_FORBIDDEN_CHARS"), blocking(BgFatcaTestData.nil().setFiName("Банка /* АД")));
    assertEquals(
        List.of("FATCA_FORBIDDEN_CHARS"),
        blocking(with(BgFatcaTestData.individual(1).setFirstName("J&#111;hn"))));
  }

  @Test
  @DisplayName("Тестовият файл е предупреждение, не отказ")
  void тестов_файл() {
    List<BgDeclarationFileFinding> f =
        BgFatcaFileValidator.validate(BgFatcaTestData.nil().setTestFile(true), TODAY);
    assertEquals(1, f.size());
    assertEquals("FATCA_TEST_FILE", f.get(0).getCode());
    assertFalse(f.get(0).isBlocking());
  }

  @Test
  @DisplayName("Планът: при отказ няма байтове и всички причини са в съобщението")
  void планът_отказва() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () ->
                BgFatcaSubmissionPlan.plan(
                    with(BgFatcaTestData.organisation(7, "FATCA102")).setGiin(null), TODAY));
    assertTrue(e.getMessage().contains("FATCA_GIIN_MISSING"), e.getMessage());
    assertTrue(e.getMessage().contains("FATCA_SUBSTANTIAL_OWNERS_UNSUPPORTED"), e.getMessage());
  }

  @Test
  @DisplayName("Планът: валиден файл с име, брой сметки и байтове")
  void планът_пише() {
    BgDeclarationPlannedFile file =
        BgFatcaSubmissionPlan.plan(with(BgFatcaTestData.individual(1)), TODAY);
    assertEquals(BgFatcaXsd.fileName(2025, BgFatcaTestData.GIIN), file.getFileName());
    assertEquals("FATCA_2025_Z9Z9Z9.99999.SL.100.xml", file.getFileName());
    assertEquals(1, file.getNumRecords());
    assertTrue(file.getBytes().length > 0);
  }

  @Test
  @DisplayName("US TIN: 9 цифри, с или без тирета")
  void us_tin() {
    assertTrue(BgFatcaFileValidator.isUsTin("000-00-0001"));
    assertTrue(BgFatcaFileValidator.isUsTin("000000001"));
    assertFalse(BgFatcaFileValidator.isUsTin("00-000-001X"));
    assertFalse(BgFatcaFileValidator.isUsTin("0000000001"));
  }

  @Test
  @DisplayName("GIIN: нормализира се до главни букви; България е 100")
  void giin_формат() {
    assertTrue(BgFatcaGiin.isValid(" z9z9z9.99999.sl.100 "));
    assertEquals("Z9Z9Z9.99999.SL.100", BgFatcaGiin.normalize(" z9z9z9.99999.sl.100 "));
    assertTrue(BgFatcaGiin.isBulgarian("Z9Z9Z9.99999.SL.100"));
    assertFalse(BgFatcaGiin.isValid("Z9Z9Z9.99999.S1.100"));
    assertFalse(BgFatcaGiin.isValid("Z9Z9Z9.9999.SL.100"));
    assertFalse(BgFatcaGiin.isValid(null));
  }
}
