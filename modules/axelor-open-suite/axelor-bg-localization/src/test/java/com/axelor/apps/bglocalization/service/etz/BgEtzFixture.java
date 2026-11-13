package com.axelor.apps.bglocalization.service.etz;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.EndOfContractReason;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ОБЩИЯТ СТЕНД — истински записи, нула mock-ове.
 *
 * <p>Модулът няма mock-подпора: правило, което иска жива Guice или база, се проверява само
 * структурно. Затова стендът строи ИСТИНСКИ {@link EmploymentContract} — обикновени JPA обекти,
 * създадени с {@code new}.
 *
 * <p><b>⚠️ ДОГОВОРЪТ Е НАРОЧНО ПЪЛЕН.</b> Целта не е да се покаже, че липсващи данни дават пречки —
 * това е очевидно. Целта е файлът да мине СРЕЩУ ИСТИНСКАТА СХЕМА с 0 грешки, тоест стендът трябва
 * да носи всеки реквизит, който Приложение № 1 иска при съответния тип на документа.
 */
final class BgEtzFixture {

  static final String EIK = "831919536";
  static final String EGN = "7501011234";
  static final String EKATTE = "68134";
  static final String NKPD = "24136001";
  static final String KID = "6201";

  /** Напълно попълнен безсрочен трудов договор по чл. 67 КТ. */
  EmploymentContract contract() {
    Partner companyPartner = new Partner();
    companyPartner.setRegistrationCode(EIK);

    BgNkid nkid = new BgNkid();
    nkid.setCode(KID);

    Company company = new Company();
    company.setName("ТЕСТОВИ РАБОТОДАТЕЛ ЕООД");
    company.setPartner(companyPartner);
    company.setBgNkid(nkid);

    Partner person = new Partner();
    person.setFirstName("Иван");
    person.setBgMiddleName("Петров");
    person.setName("Георгиев");

    Employee employee = new Employee();
    employee.setContactPartner(person);
    employee.setBgPersonalIdNumber(EGN);
    employee.setBgPersonalIdType("1");

    BgNkpd nkpd = new BgNkpd();
    nkpd.setCode(NKPD);

    EndOfContractReason reason = new EndOfContractReason();
    reason.setBgLegalFamily("KT");
    reason.setBgTerminationGround("328_1_2");

    EmploymentContract contract = new EmploymentContract();
    contract.setPayCompany(company);
    contract.setEmployee(employee);
    contract.setBgNkpd(nkpd);
    contract.setEndOfContractReason(reason);
    contract.setBgContractType("67");
    contract.setSignatureDate(LocalDate.of(2026, 3, 2));
    contract.setStartDate(LocalDate.of(2026, 3, 9));
    contract.setBgTerminationDate(LocalDate.of(2026, 9, 30));
    contract.setBgBaseSalary(new BigDecimal("1500.00"));
    contract.setBgHoursPerDay(new BigDecimal("8"));
    contract.setBgAnnualLeaveDays(new BigDecimal("20"));
    contract.setBgWorkplaceEkatte(EKATTE);
    return contract;
  }

  /** Търси байтова поредица — за да се мери КОДИРОВКАТА, а не текстът. */
  static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  /** Изричните реквизити на прекратяването — тези, които договорът не пази. */
  BgEtzTerminationInput terminationInput() {
    return new BgEtzTerminationInput().setUsedVacationDays(12).setRecognisedExperience("000606");
  }
}
