package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.AddressTemplate;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.EventsPlanning;
import com.axelor.apps.base.db.EventsPlanningLine;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.napsubmissions.common.artifact.Institution;
import com.axelor.apps.napsubmissions.common.artifact.NapArtifactService;
import com.axelor.apps.napsubmissions.common.artifact.RegulatoryFile;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.repo.NapArtifactRepository;
import com.axelor.auth.db.Role;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppBgLocalization;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * ФИКСТУРАТА НА DB СВИДЕТЕЛИТЕ ЗА СРОКА И ОТМЕТКАТА — HSQLDB в паметта, истинското окабеляване.
 *
 * <p>⚠️ НИЩО НЕ СЕ ТРИЕ И НИЩО НЕ СЕ ЧИСТИ. Базата е една за целия JVM: изолацията идва от СВОЯ
 * фирма за всеки свидетел (по образеца на {@code BgD1KindQueryDbTest}). А {@code JPA.clear()} е
 * забранен: Partner има PostgreSQL {@code @Formula}, която HSQLDB отказва, тоест всеки SELECT на
 * Partner пада — фикстурите остават managed и Partner никога не се чете наново.
 *
 * <p>⚠️ ЛИСТЪТ СЕ ЗАПИСВА ПРЕЗ REPOSITORY-ТО — оттам минава закачането на срока ({@code
 * BgSickLeaveManagementRepository}). Свидетел, който записва с {@code persist}, би мерил лист без
 * срок.
 */
final class BgApp9DbFixture {

  final String tag = "A9" + System.nanoTime();
  private int sequence;

  // ═══ КАЛЕНДАРЪТ — от ИСТИНСКИЯ seed, веднъж за JVM ═══════════════════════

  static synchronized void loadCalendar() {
    Long rows =
        JPA.em()
            .createQuery(
                "SELECT COUNT(l) FROM EventsPlanningLine l WHERE l.year = 2026", Long.class)
            .getSingleResult();
    if (rows != null && rows > 0) {
      return;
    }
    List<String> lines;
    try {
      lines =
          Files.readAllLines(
              Paths.get("src/main/resources/apps/init-data/en/bg_eventsPlanningLine.csv"),
              StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    JPA.runInTransaction(
        () -> {
          EventsPlanning planning = new EventsPlanning();
          planning.setName("BG holidays (тест)");
          JPA.em().persist(planning);
          for (String line : lines.subList(1, lines.size())) {
            if (line.trim().isEmpty()) {
              continue;
            }
            String[] cells = line.split(";");
            EventsPlanningLine row = new EventsPlanningLine();
            row.setEventsPlanning(planning);
            row.setYear(Integer.valueOf(cells[1].trim()));
            row.setDate(LocalDate.parse(cells[2].trim()));
            row.setDescription(cells.length > 4 ? cells[4].trim() : null);
            JPA.em().persist(row);
          }
        });
  }

  // ═══ ДРУЖЕСТВО, ЛИЦЕ, ДОГОВОР ═══════════════════════════════════════════

  Address address() {
    AddressTemplate template = new AddressTemplate();
    template.setName("BG " + tag);
    template.setEngineSelect(1);
    template.setTemplateStr("$addressL4$");
    JPA.em().persist(template);
    Country country = new Country();
    country.setName("България " + tag + "-" + (++sequence));
    country.setAddressTemplate(template);
    JPA.em().persist(country);
    Address address = BgApp9Fixture.address();
    address.setCountry(country);
    // Градът от номенклатурата (ЕКАТТЕ) — оттам Прил. 9 чете областта и населеното място.
    address.getCity().setCountry(country);
    JPA.em().persist(address.getCity());
    JPA.em().persist(address);
    return address;
  }

  /** Дружество с ЕИК, адрес, декларатор и ТП на НОИ — всичко, което иска заглавната част. */
  Company company(String name, LocalDate trackFrom) {
    Company company = new Company();
    company.setName(name + " " + tag);
    company.setCode("C" + tag + "-" + (++sequence));
    company.setAddress(address());
    company.setBgApp9TrackFrom(trackFrom);
    JPA.em().persist(company);

    // Партньорът на дружеството е в самото дружество — затова СЛЕД него: Hibernate
    // вмъква по реда на persist, а партньорът сочи дружеството.
    Partner partner = new Partner();
    partner.setName(name + " " + tag);
    partner.setRegistrationCode(BgApp9Fixture.COMPANY_BULSTAT);
    // ⚠️ Партньорът на дружеството е БЕЗ адрес — както често е в заварена база. Адресът на
    // осигурителя е на дружеството (Company.address), единственият източник за Прил. 9.
    partner.setFixedPhone("029020506");
    partner.setMobilePhone("0888123456");
    partner.setCompany(company);
    JPA.em().persist(partner);
    company.setPartner(partner);
    developerEik(BgApp9Fixture.DEVELOPER_EIK);

    BgDeclarant declarant = new BgDeclarant();
    declarant.setCompany(company);
    declarant.setDeclarantNames("Иван Георгиев Петров");
    // ИСТИНСКИ декларатор: „Качество“ е кодът R (законен представител), а длъжността е текст —
    // точно както ги въвежда човек на екрана. Прил. 9 чете длъжността.
    declarant.setDeclarantCapacity("R");
    declarant.setDeclarantPosition("Управител");
    declarant.setDeclarantCity("София");
    // Прил. 9 чете декларатора ПО ПОДРАЗБИРАНЕ (isDefault), не първия по id.
    declarant.setIsDefault(true);
    JPA.em().persist(declarant);

    BgApp9Export header = new BgApp9Export();
    header.setName("ТП на НОИ " + tag);
    header.setCompany(company);
    header.setStatusSelect("DRAFT");
    header.setNssiRoCode(22);
    header.setNssiRoName("София-град");
    JPA.em().persist(header);
    return company;
  }

  /**
   * НАСТРОЙКАТА НА ИНСТАЛАЦИЯТА — ЕИК на разработчика на софтуера, „Флаг на източник“ (Source).
   * Намира се или се създава: базата е обща за JVM, а редът е един за инсталацията. Без нея всеки
   * износ се отказва поименно — няма тих резерв към ЕИК-а на дружеството.
   */
  static AppBgLocalization developerEik(String eik) {
    AppBgLocalization app = JPA.all(AppBgLocalization.class).fetchOne();
    if (app == null) {
      app = new AppBgLocalization();
    }
    app.setBgSoftwareDeveloperEik(eik);
    return JPA.save(app);
  }

  /** Деклараторът на дружеството — managed, за да може свидетелят да го промени. */
  static BgDeclarant declarantOf(Company company) {
    return JPA.all(BgDeclarant.class).filter("self.company = :c").bind("c", company).fetchOne();
  }

  EmploymentContract contract(Company company, String insuredType) {
    Partner person = new Partner();
    person.setFirstName("Мария");
    person.setBgMiddleName("Иванова");
    person.setName("Стоянова " + tag + "-" + (++sequence));
    person.setMainAddress(address());
    person.setFixedPhone("029876543");
    person.setMobilePhone("0899112233");
    person.setCompany(company);
    JPA.em().persist(person);

    Employee employee = new Employee();
    employee.setName(person.getName());
    employee.setContactPartner(person);
    employee.setBgPersonalIdNumber(BgApp9Fixture.VALID_EGN);
    employee.setBgPersonalIdType("EGN");
    employee.setBgBenefitIban(BgApp9Fixture.VALID_IBAN);
    JPA.em().persist(employee);

    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);
    contract.setPayCompany(company);
    contract.setBgInsuredTypeCode(insuredType);
    JPA.em().persist(contract);
    return contract;
  }

  /** Видът отсъствие — намира се или се създава: кодът е unique, а базата е обща за JVM. */
  static BgAbsenceType absenceType(String code, boolean requiresSickNote) {
    BgAbsenceType found =
        JPA.all(BgAbsenceType.class).filter("self.code = :code").bind("code", code).fetchOne();
    if (found != null) {
      return found;
    }
    BgAbsenceType type = new BgAbsenceType();
    type.setCode(code);
    type.setName(code);
    type.setCategory(2);
    type.setPayer("employer");
    type.setRequiresSickNote(requiresSickNote);
    // A4: вид на болничен лист се зачита за стаж (изключва „Непредставен болничен“).
    type.setCountsAsInsuranceService(true);
    JPA.em().persist(type);
    return type;
  }

  // ═══ БОЛНИЧНИЯТ — ПРЕЗ REPOSITORY-ТО ═════════════════════════════════════

  /** Валидиран лист за 05–12.10.2026 с дата на представяне; записан през repository-то. */
  BgSickLeave sickLeave(EmploymentContract contract, LocalDate received) {
    return sickLeave(
        contract, LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 12), received, "sick_er");
  }

  BgSickLeave sickLeave(
      EmploymentContract contract,
      LocalDate from,
      LocalDate to,
      LocalDate received,
      String absenceCode) {
    BgSickLeave sick = new BgSickLeave();
    sick.setEmploymentContract(contract);
    sick.setBgAbsenceType(absenceType(absenceCode, true));
    sick.setFromDate(from);
    sick.setToDate(to);
    sick.setStatusSelect("VALIDATED");
    sick.setBgIncapacityStartDate(from);
    sick.setBgSeries("E");
    sick.setBgYear(from.getYear());
    sick.setBgDocumentNumber(String.format("%07d", (System.nanoTime() / 1000) % 10_000_000L));
    sick.setBgIssueDate(from);
    sick.setBgEmployerReceivedNo("ВХ-" + (++sequence));
    sick.setBgEmployerReceivedDate(received);
    sick.setBgPeriodLineList(new ArrayList<BgSickLeavePeriodLine>());
    BigDecimal eight = new BigDecimal("8.00");
    BgApp9Fixture.line(sick, from, to, 2, "EMPLOYER", eight, new BigDecimal("16.00"));
    BgApp9Fixture.line(sick, from, to, 4, "NOI", eight, new BigDecimal("32.00"));
    return Beans.get(BgSickLeaveRepository.class).save(sick);
  }

  /** Пълно удостоверение за листа — всяко задължително обстоятелство има отговор. */
  static BgApp9Declaration declaration(BgSickLeave sick) {
    BgApp9Declaration d = BgApp9Fixture.declarationFor(sick);
    JPA.em().persist(d);
    return d;
  }

  /** Файл към НОИ през общото ядро — същата врата, през която минава генерирането. */
  NapArtifact artifact(String body) {
    try {
      return Beans.get(NapArtifactService.class)
          .createFromFile(
              new RegulatoryFile(
                  Institution.NOI,
                  "Pril9_" + tag + "_" + (++sequence) + ".xml",
                  "text/xml",
                  body.getBytes(StandardCharsets.UTF_8)),
              NapArtifactRepository.TYPE_PAYLOAD);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  // ═══ ПОТРЕБИТЕЛЯТ ════════════════════════════════════════════════════════

  User user(String code, boolean submitRole, Company... companies) {
    User user = new User();
    user.setCode(code + tag + "-" + (++sequence));
    user.setName(code + " " + tag);
    user.setPassword("secret-" + tag);
    user.setCompanySet(new HashSet<Company>());
    for (Company company : companies) {
      user.getCompanySet().add(company);
    }
    // Активното дружество е изолирана среда: първото подадено е активното.
    if (companies.length > 0) {
      user.setActiveCompany(companies[0]);
    }
    if (submitRole) {
      user.setRoles(new HashSet<Role>());
      user.getRoles().add(submitRole());
    }
    JPA.em().persist(user);
    return user;
  }

  /** Правото за запис по Прил. 9 — най-тясната роля, която отбелязва подаване. */
  static final String NOI_WRITE = "hr.noi.write";

  static Role submitRole() {
    Role found = JPA.all(Role.class).filter("self.name = :name").bind("name", NOI_WRITE).fetchOne();
    if (found != null) {
      return found;
    }
    Role role = new Role();
    role.setName(NOI_WRITE);
    JPA.em().persist(role);
    return role;
  }

  // ═══ ЧЕТЕНЕТО НА ИЗЧИСЛИМИТЕ КОЛОНИ — през SQL, не през managed обекта ═══

  /** {@code bgNoiSubmissionState} — формулата се смята в SELECT-а, не в паметта. */
  static int submissionState(BgSickLeave sick) {
    Integer state =
        JPA.em()
            .createQuery(
                "SELECT s.bgNoiSubmissionState FROM BgSickLeave s WHERE s.id = :id", Integer.class)
            .setParameter("id", sick.getId())
            .getSingleResult();
    return state == null ? 0 : state;
  }
}
