package com.axelor.apps.bglocalization.service.fatca;

import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * ИЗМИСЛЕНИ ДАННИ за FATCA тестовете. GIIN-ът е във формата на IRS, но измислен; US TIN-овете
 * започват с 000 — такава серия не се издава.
 */
final class BgFatcaTestData {

  static final String GIIN = "Z9Z9Z9.99999.SL.100";
  static final LocalDate TODAY = LocalDate.of(2026, 10, 27);

  private BgFatcaTestData() {}

  /** Отчет за 2025 г. без сметки — нулев. */
  static BgFatcaFileContent nil() {
    return new BgFatcaFileContent()
        .setReportYear(2025)
        .setGiin(GIIN)
        .setFiName("Тестова Банка АД")
        .setFiCountryCode("BG")
        .setFiStreet("ул. Измислена 1")
        .setFiPostCode("1000")
        .setFiCity("София")
        .setMessageRefId(GIIN + ".msg-1")
        .setFiDocRefId(GIIN + ".fi-1")
        .setNilDocRefId(GIIN + ".nil-1")
        .setTimestamp(LocalDateTime.of(2026, 10, 27, 10, 15, 30, 123_000_000));
  }

  static BgFatcaFileContent.Account individual(long id) {
    return new BgFatcaFileContent.Account()
        .setId(id)
        .setAccountNumber("TEST-ACC-" + id)
        .setHolderType(BgFatcaStatus.INDIVIDUAL)
        .setFirstName("John")
        .setLastName("Testholder")
        .setUsTin("000-00-000" + (id % 10))
        .setBirthDate(LocalDate.of(1980, 1, 2))
        .setCountryCode("US")
        .setCity("Springfield")
        .setAddressFree("1 Test Street")
        .setBalance(new BigDecimal("12345.678"))
        .setCurrencyCode("USD")
        .setInterest(new BigDecimal("10.50"))
        .setDividends(BigDecimal.ZERO)
        .setDocRefId(GIIN + ".acc-" + id);
  }

  static BgFatcaFileContent.Account organisation(long id, String acctHolderType) {
    return new BgFatcaFileContent.Account()
        .setId(id)
        .setAccountNumber("TEST-ORG-" + id)
        .setHolderType(BgFatcaStatus.ORGANISATION)
        .setOrganisationName("Test Holding LLC")
        .setAcctHolderType(acctHolderType)
        .setUsTin("000-00-0009")
        .setCountryCode("US")
        .setAddressFree("2 Test Avenue, Testville")
        .setBalance(new BigDecimal("1000"))
        .setCurrencyCode("EUR")
        .setGrossProceeds(new BigDecimal("250"))
        .setDocRefId(GIIN + ".acc-" + id);
  }
}
