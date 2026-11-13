package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ЕДНО ПЪЛНО, ВАЛИДНО УДОСТОВЕРЕНИЕ — общият вход на свидетелите на Прил. 9.
 *
 * <p><b>Защо е отделен клас, а не по едно сглобяване във всеки тест.</b> Свидетелите тук се делят
 * на два вида: „това минава“ и „това СПИРА, защото X липсва“. Вторият вид се пише като ВАРИАЦИЯ на
 * първия — махаш едно поле и очакваш точно един отказ. Ако всеки тест строеше свой запис, тестът за
 * отказ щеше да доказва, че НЕПЪЛЕН запис спира, вместо че ПЪЛЕН МИНУС ЕДНО поле спира; разликата е
 * между свидетел и съвпадение.
 *
 * <p><b>⚠️ Нула база.</b> Само POJO-та — модулът няма mock-подпора и всичко доказуемо тук е
 * доказуемо без Postgres. Точно затова емитерът и проверката на липсите са чисти функции.
 *
 * <p><b>Числата.</b> ЕГН 8505151039 минава контролната цифра по модул 11; IBAN
 * BG18RZBB91550123456789 минава ISO 13616. И двете са сметнати, не преписани — ако някой ги смени,
 * свидетелите за ЕГН и IBAN ще паднат, вместо да минат случайно.
 */
final class BgApp9Fixture {

  /** ЕГН с вярна контролна цифра. */
  static final String VALID_EGN = "8505151039";

  /** Български IBAN, който минава mod-97. */
  static final String VALID_IBAN = "BG18RZBB91550123456789";

  /** ЕИК на осигурителя — девет цифри. */
  static final String COMPANY_BULSTAT = "831919536";

  /**
   * ЕИК на РАЗРАБОТЧИКА на софтуера — „Флаг на източник“ (Source). Тестова стойност, не
   * подразбиране: в кода и в данните за App Install настройката е празна.
   */
  public static final String DEVELOPER_EIK = "100000019";

  private BgApp9Fixture() {}

  /** Пълна заглавна част — минава {@link BgApp9MandatoryPoints#ofHeader}. */
  static BgApp9EmitterContext context() {
    return BgApp9EmitterContext.builder()
        .flagDelegated("0")
        .flagKasa("0")
        .bulstatKasa(COMPANY_BULSTAT)
        .bulName("Никса ЕООД")
        .bulstatMember("")
        .nssiRo(Integer.valueOf(22), "София-град")
        .address(
            "София-град",
            "Столична",
            "София",
            "1000",
            "бул. Витоша 1",
            "029020506",
            "0888123456",
            "office@example.bg")
        .representative("Иван", "Георгиев", "Петров", "Управител", "София")
        .certificateDate(LocalDate.of(2026, 6, 10))
        .exportDate(LocalDate.of(2026, 6, 12))
        .savedAt(LocalDateTime.of(2026, 6, 12, 9, 30, 0))
        .source(DEVELOPER_EIK)
        .build();
  }

  /** Дружеството с партньор и адрес. */
  static Company company() {
    Company company = new Company();
    company.setName("Никса ЕООД");
    Partner partner = new Partner();
    partner.setRegistrationCode(COMPANY_BULSTAT);
    partner.setMainAddress(address());
    company.setPartner(partner);
    return company;
  }

  /**
   * Градът от българската номенклатура — същият запис като в истинска база: „София — Столична —
   * София (столица) — 1000“, ЕКАТТЕ 68134. Прил. 9 чете областта, общината и населеното място ОТ
   * НЕГО.
   */
  static City sofia() {
    City city = new City();
    city.setName("София");
    city.setMunicipality("Столична");
    city.setRegion("София (столица)");
    city.setZip("1000");
    city.setEkatte("68134");
    return city;
  }

  /** Адрес, който сочи град с ЕКАТТЕ — областта и населеното място са с minLength=1 в схемата. */
  static Address address() {
    Address address = new Address();
    address.setCity(sofia());
    address.setDepartment("София-град");
    address.setSubDepartment("Столична");
    address.setTownName("София");
    address.setZip("1000");
    address.setAddressL4("бул. Витоша 1");
    return address;
  }

  /** Служителят с трите имена, ЕГН и адрес. */
  static Employee employee() {
    Partner person = new Partner();
    person.setFirstName("Мария");
    person.setBgMiddleName("Иванова");
    person.setName("Стоянова");
    person.setMainAddress(address());
    person.setFixedPhone("029876543");
    person.setMobilePhone("0899112233");

    Employee employee = new Employee();
    employee.setContactPartner(person);
    employee.setBgPersonalIdNumber(VALID_EGN);
    employee.setBgPersonalIdType("EGN");
    employee.setBgBenefitIban(VALID_IBAN);
    return employee;
  }

  /** Договорът, който свързва лицето с дружеството. */
  static EmploymentContract contract() {
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee());
    contract.setPayCompany(company());
    return contract;
  }

  /**
   * Болничен лист 05.06.2026 – 12.06.2026, с идентификаторите на Ф0 и два реда по т. 9.
   *
   * <p>Два реда, защото болничният има ПЪРВИ ДВА дни за сметка на осигурителя (чл. 40 ал. 5 КСО) и
   * останалите за сметка на ДОО — тоест точно този разрез.
   */
  static BgSickLeave sickLeave() {
    return sickLeave("E", 2026, "0001234", LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 12));
  }

  /** Болничен лист по мярка — за свидетелите, които сравняват два листа. */
  static BgSickLeave sickLeave(
      String series, int year, String number, LocalDate from, LocalDate to) {

    BgSickLeave sick = new BgSickLeave();
    sick.setEmploymentContract(contract());
    sick.setFromDate(from);
    sick.setToDate(to);
    sick.setBgIncapacityStartDate(from);
    sick.setBgSeries(series);
    sick.setBgYear(Integer.valueOf(year));
    sick.setBgDocumentNumber(number);
    sick.setBgIssueDate(from);
    sick.setBgEmployerReceivedNo("ВХ-114");
    sick.setBgEmployerReceivedDate(to.plusDays(1));
    sick.setBgPeriodLineList(new ArrayList<BgSickLeavePeriodLine>());
    return sick;
  }

  /**
   * Един ред на Ф0 — платецът, месецът, границите и дните.
   *
   * @param workHours {@code null} значи СИРВ (Ф0 пише часове САМО при подневно отчитане)
   */
  static BgSickLeavePeriodLine line(
      BgSickLeave sick,
      LocalDate fromDay,
      LocalDate toDay,
      int workDays,
      String payer,
      BigDecimal dailyHours,
      BigDecimal workHours) {

    BgSickLeavePeriodLine line = new BgSickLeavePeriodLine();
    line.setSickLeave(sick);
    line.setPeriodYear(Integer.valueOf(fromDay.getYear()));
    line.setPeriodMonth(Integer.valueOf(fromDay.getMonthValue()));
    line.setFromDay(fromDay);
    line.setToDay(toDay);
    line.setWorkDays(Integer.valueOf(workDays));
    line.setPayer(payer);
    line.setDailyHours(dailyHours);
    line.setWorkHours(workHours);
    sick.getBgPeriodLineList().add(line);
    return line;
  }

  /** Стандартните два реда на един месец: два дни осигурител, четири дни ДОО. */
  static void standardLines(BgSickLeave sick) {
    LocalDate from = sick.getFromDate();
    LocalDate to = sick.getToDate();
    BigDecimal eight = new BigDecimal("8.00");
    line(sick, from, to, 2, "EMPLOYER", eight, new BigDecimal("16.00"));
    line(sick, from, to, 4, "NOI", eight, new BigDecimal("32.00"));
  }

  /** ПЪЛНО удостоверение — всяко задължително обстоятелство има отговор. */
  static BgApp9Declaration declaration() {
    BgSickLeave sick = sickLeave();
    standardLines(sick);
    return declarationFor(sick);
  }

  /** Пълно удостоверение за подаден лист. */
  static BgApp9Declaration declarationFor(BgSickLeave sick) {
    BgApp9Declaration d = new BgApp9Declaration();
    d.setSickLeave(sick);
    sick.setBgApp9Declaration(d);
    d.setStatusSelect("RESOLVED");
    d.setBgSubmissionTypeSelect("SUBMIT");
    d.setBgP1Insured("YES");
    d.setBgP1TermTypeSelect("INDEFINITE");
    d.setBgP2SixMonthStaySelect("MET");
    d.setBgP3TwelveMonthStaySelect("NOT_APPLICABLE");
    d.setBgP4InsuredTypeCode("01");
    d.setBgP5RelationshipCount(Integer.valueOf(1));
    d.setCertificateSeqNo(Integer.valueOf(1));
    d.setBgP26FirstSubmissionSelect("YES");
    d.setBenefitIban(VALID_IBAN);
    d.setResolverNotes("[изведено] т. 1 · Да — договорът е активен към деня на настъпването");
    return d;
  }

  /** Списък с едно удостоверение — най-честият вход на свидетелите. */
  static List<BgApp9Declaration> one(BgApp9Declaration d) {
    List<BgApp9Declaration> out = new ArrayList<>(1);
    out.add(d);
    return out;
  }
}
