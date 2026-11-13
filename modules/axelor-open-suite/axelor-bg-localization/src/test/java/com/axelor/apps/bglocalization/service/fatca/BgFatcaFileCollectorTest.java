package com.axelor.apps.bglocalization.service.fatca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgFatcaAccount;
import com.axelor.apps.bglocalization.db.BgFatcaProfile;
import com.axelor.apps.bglocalization.db.BgFatcaReport;
import com.axelor.apps.bglocalization.service.fatca.dto.BgFatcaFileContent;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ОТ ЗАПИСАНОТО КЪМ СЪДЪРЖАНИЕТО — без база: ентититата са обикновени обекти. */
class BgFatcaFileCollectorTest {

  private static Supplier<String> ids() {
    AtomicInteger n = new AtomicInteger();
    return () -> "id" + n.incrementAndGet();
  }

  private static Company company() {
    Country bg = new Country();
    bg.setAlpha2Code("bg");
    City city = new City();
    city.setName("София");
    Address address = new Address();
    address.setStreetName("ул. Измислена 1");
    address.setZip("1000");
    address.setCity(city);
    address.setCountry(bg);
    Partner partner = new Partner();
    partner.setName("Тестова Банка АД");
    Company company = new Company();
    company.setName("ТБ");
    company.setPartner(partner);
    company.setAddress(address);
    return company;
  }

  private static BgFatcaProfile profile() {
    BgFatcaProfile p = new BgFatcaProfile();
    p.setStatusSelect(BgFatcaStatus.REPORTING_FI);
    p.setGiin(" z9z9z9.99999.sl.100 ");
    return p;
  }

  private static BgFatcaAccount account(long id) {
    Country us = new Country();
    us.setAlpha2Code("us");
    Currency usd = new Currency();
    usd.setCodeISO("usd");
    BgFatcaAccount a = new BgFatcaAccount();
    a.setId(id);
    a.setAccountNumber("TEST-ACC-" + id);
    a.setHolderTypeSelect(BgFatcaStatus.INDIVIDUAL);
    a.setCountry(us);
    a.setCurrency(usd);
    a.setBalance(BigDecimal.TEN);
    return a;
  }

  @Test
  @DisplayName(
      "Нулев отчет: GIIN нормализиран, идентификаторите с префикс GIIN, NilReport има DocRefId")
  void нулев() {
    BgFatcaReport report = new BgFatcaReport();
    report.setCompany(company());
    report.setReportYear(2025);
    BgFatcaFileContent c =
        BgFatcaFileCollector.collect(report, profile(), ids(), LocalDateTime.of(2026, 1, 1, 0, 0));
    assertEquals("Z9Z9Z9.99999.SL.100", c.getGiin());
    assertEquals("Z9Z9Z9.99999.SL.100.id1", c.getMessageRefId());
    assertEquals("Z9Z9Z9.99999.SL.100.id2", c.getFiDocRefId());
    assertEquals("Z9Z9Z9.99999.SL.100.id3", c.getNilDocRefId());
    assertTrue(c.isNil());
    assertEquals("Тестова Банка АД", c.getFiName());
    assertEquals("BG", c.getFiCountryCode());
    assertEquals("София", c.getFiCity());
    assertEquals("ул. Измислена 1", c.getFiStreet());
  }

  @Test
  @DisplayName("Сметките по id, с ISO кодове с главни букви; NilReport няма")
  void сметки() {
    BgFatcaReport report = new BgFatcaReport();
    report.setCompany(company());
    report.setReportYear(2025);
    report.setTestFile(true);
    List<BgFatcaAccount> accounts = new ArrayList<>();
    accounts.add(account(9));
    accounts.add(account(4));
    report.setAccountList(accounts);
    BgFatcaFileContent c =
        BgFatcaFileCollector.collect(report, profile(), ids(), LocalDateTime.of(2026, 1, 1, 0, 0));
    assertTrue(c.isTestFile());
    assertEquals(2, c.getAccounts().size());
    assertEquals("TEST-ACC-4", c.getAccounts().get(0).getAccountNumber());
    assertEquals("US", c.getAccounts().get(0).getCountryCode());
    assertEquals("USD", c.getAccounts().get(0).getCurrencyCode());
    assertEquals("Z9Z9Z9.99999.SL.100.id3", c.getAccounts().get(0).getDocRefId());
    assertNull(c.getNilDocRefId());
  }

  @Test
  @DisplayName("Без адрес на дружеството: държавата е BG, градът липсва — валидаторът ще откаже")
  void без_адрес() {
    Company company = company();
    company.setAddress(null);
    BgFatcaReport report = new BgFatcaReport();
    report.setCompany(company);
    report.setReportYear(2025);
    BgFatcaFileContent c =
        BgFatcaFileCollector.collect(report, profile(), ids(), LocalDateTime.of(2026, 1, 1, 0, 0));
    assertEquals("BG", c.getFiCountryCode());
    assertNull(c.getFiCity());
  }
}
