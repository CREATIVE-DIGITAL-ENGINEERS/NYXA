package com.axelor.apps.bglocalization.service;

import static com.axelor.apps.bglocalization.testsupport.BgRoleLogin.READ_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountManagement;
import com.axelor.apps.account.db.ClosureAssistant;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.service.ClosureAssistantService;
import com.axelor.apps.account.service.move.attributes.MoveAttrsService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.service.CompanyService;
import com.axelor.apps.base.service.PaymentModeService;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.service.employee.EmployeeFetchService;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ЕДНО АКТИВНО ДРУЖЕСТВО И В ЧУЖДИЯ КОД. Потребителят е назначен на А и Б, работи в А. Там, където
 * Axelor предлага всички дружества на базата или всички назначени, той вижда и записва само в А —
 * по истинските услуги, не по копие на правилото.
 *
 * <p>Б е назначено нарочно: правилото е „активното“, не „назначените“. Неназначено дружество отпада
 * още по-рано.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgActiveCompanyOnlyIntegrationTest extends JpaSupport {

  private Long companyId;
  private Long otherCompanyId;
  private Long employeeId;
  private Long otherEmployeeId;

  @BeforeEach
  void scene() {
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Company company = company("Активно А " + tag, "AA" + tag);
          companyId = company.getId();
          Company other = company("Друго Б " + tag, "AB" + tag);
          otherCompanyId = other.getId();
          employeeId = employee(company, "A" + tag).getId();
          otherEmployeeId = employee(other, "B" + tag).getId();
        });
    BgRoleLogin.login(company(), Set.of(other()), READ_ROLE);
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
    // Служителите, заредени от заявка, иначе остават в контекста: при commit на следващата сцена
    // flush чете Employee.name → Partner, който HSQLDB не зарежда (@Formula на Partner).
    JPA.em().clear();
  }

  @Test
  @DisplayName("Начин на плащане, нов: ред за сметки само за активното дружество")
  void paymentModeAccountLinesOnlyForTheActiveCompany() {
    List<AccountManagement> lines =
        Beans.get(PaymentModeService.class).getAccountManagementDefaults();

    assertEquals(
        List.of(companyId),
        lines.stream().map(l -> l.getCompany().getId()).collect(Collectors.toList()));
  }

  @Test
  @DisplayName("Статия: изборът на дружество предлага само активното")
  void moveCompanyPickerOffersOnlyTheActiveCompany() {
    Map<String, Map<String, Object>> attrs = new HashMap<>();

    Beans.get(MoveAttrsService.class).addCompanyDomain(new Move(), attrs);

    assertEquals("self.id IN (" + companyId + ")", attrs.get("company").get("domain"));
  }

  @Test
  @DisplayName("Дружество по подразбиране: изпратеното от клиента Б се пренебрегва — записът е в А")
  void defaultCompanyIgnoresAnotherCompanySentByTheClient() {
    CompanyService service = Beans.get(CompanyService.class);

    assertEquals(companyId, service.getDefaultCompany(otherCompanyId).getId());
    assertEquals(companyId, service.getDefaultCompany(null).getId());
  }

  @Test
  @DisplayName("Разход: поканени колеги само от активното дружество")
  void invitedCollaboratorsOnlyFromTheActiveCompany() {
    List<Long> ids =
        Beans.get(EmployeeFetchService.class)
            .getInvitedCollaborators(LocalDate.of(2026, 10, 21), null)
            .stream()
            .map(Employee::getId)
            .collect(Collectors.toList());

    assertTrue(ids.contains(employeeId), "служителят на активното дружество липсва: " + ids);
    assertFalse(ids.contains(otherEmployeeId), "служител на Б е предложен: " + ids);
  }

  @Test
  @DisplayName("Приключване: без активно дружество — никое, не произволно назначено")
  void closureAssistantNeverPicksAnArbitraryAssignedCompany() {
    ClosureAssistantService service = Beans.get(ClosureAssistantService.class);
    assertEquals(companyId, service.updateCompany(new ClosureAssistant()).getCompany().getId());

    BgRoleLogin.logout();
    BgRoleLogin.login(null, Set.of(other()), READ_ROLE);

    assertNull(service.updateCompany(new ClosureAssistant()).getCompany());
  }

  private Company company() {
    return JPA.em().find(Company.class, companyId);
  }

  private Company other() {
    return JPA.em().find(Company.class, otherCompanyId);
  }

  private static Company company(String name, String code) {
    Company company = new Company();
    company.setName(name);
    company.setCode(code);
    JPA.em().persist(company);
    AccountConfig config = new AccountConfig();
    config.setCompany(company);
    JPA.em().persist(config);
    company.setAccountConfig(config);
    return company;
  }

  /** Служител с потребител и договор в дружеството — поканим колега в разход. */
  private static Employee employee(Company company, String tag) {
    Partner contact = new Partner();
    contact.setName("Колега " + tag);
    contact.setCompany(company);
    JPA.em().persist(contact);
    Employee employee = new Employee();
    employee.setContactPartner(contact);
    employee.setHireDate(LocalDate.of(2020, 1, 1));
    JPA.em().persist(employee);
    EmploymentContract contract = new EmploymentContract();
    contract.setEmployee(employee);
    contract.setPayCompany(company);
    JPA.em().persist(contract);
    employee.setMainEmploymentContract(contract);
    User user = new User("emp-" + tag, "Колега " + tag);
    user.setPassword("x");
    // axelor-base saves a new user blocked (default="true"); a blocked colleague is never invited.
    user.setBlocked(false);
    user.setEmployee(employee);
    JPA.em().persist(user);
    employee.setUser(user);
    return employee;
  }
}
