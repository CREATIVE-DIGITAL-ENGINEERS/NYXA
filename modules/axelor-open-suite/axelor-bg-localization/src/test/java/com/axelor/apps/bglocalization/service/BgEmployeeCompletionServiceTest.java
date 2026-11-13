package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The "Завършете" step of the staged Employee form, now shared by the button and the Microinvest
 * importer -- see {@link BgEmployeeCompletionService}'s javadoc for the trace from the button to
 * {@code action-employee-record-finish-sbs} and its single {@code stepByStepSelect = 5}.
 *
 * <p>Pure logic: {@code applyCompletion} is deliberately a static function over an in-memory
 * Employee, so what "finished" means is testable without Guice, JPA or the form.
 */
class BgEmployeeCompletionServiceTest {

  private static Employee importedEmployee(Company company, LocalDate... contractStarts) {
    Employee employee = new Employee();
    Partner partner = new Partner();
    employee.setContactPartner(partner);
    List<EmploymentContract> contracts = new ArrayList<>();
    for (LocalDate start : contractStarts) {
      EmploymentContract contract = new EmploymentContract();
      contract.setEmployee(employee);
      contract.setPayCompany(company);
      contract.setStartDate(start);
      contracts.add(contract);
    }
    employee.setEmploymentContractList(contracts);
    return employee;
  }

  @Test
  @DisplayName("a freshly imported employee is finished: step 5, company link, main contract")
  void completesAnImportedEmployee() {
    Company company = new Company();
    company.setName("ТЕСТЕКС ООД");
    Employee employee =
        importedEmployee(company, LocalDate.of(2021, 3, 1), LocalDate.of(2023, 9, 1));

    assertTrue(BgEmployeeCompletionService.isIncomplete(employee), "default step is 1, not 5");
    assertTrue(BgEmployeeCompletionService.applyCompletion(employee, company, null));

    assertEquals(BgEmployeeCompletionService.STEP_COMPLETED, (int) employee.getStepByStepSelect());
    assertFalse(BgEmployeeCompletionService.isIncomplete(employee));
    assertTrue(employee.getContactPartner().getIsEmployee());
    assertTrue(employee.getContactPartner().getCompanySet().contains(company));
    // Latest-starting contract becomes the main one; hireDate is the earliest.
    assertEquals(LocalDate.of(2023, 9, 1), employee.getMainEmploymentContract().getStartDate());
    assertEquals(LocalDate.of(2021, 3, 1), employee.getHireDate());
  }

  @Test
  @DisplayName("an already-completed employee is left exactly as it is")
  void leavesACompletedEmployeeUnchanged() {
    Company company = new Company();
    Employee employee = importedEmployee(company, LocalDate.of(2021, 3, 1));
    employee.setStepByStepSelect(BgEmployeeCompletionService.STEP_COMPLETED);
    EmploymentContract chosenByHand = new EmploymentContract();
    chosenByHand.setPayCompany(company);
    employee.setMainEmploymentContract(chosenByHand);
    employee.getContactPartner().addCompanySetItem(company);
    employee.getContactPartner().setIsEmployee(true);
    employee.setHireDate(LocalDate.of(2019, 1, 1));

    assertFalse(BgEmployeeCompletionService.applyCompletion(employee, company, null));
    assertSame(chosenByHand, employee.getMainEmploymentContract());
    assertEquals(LocalDate.of(2019, 1, 1), employee.getHireDate());
  }

  @Test
  @DisplayName(
      "a re-imported employee that is still stuck mid-wizard is finished -- the repeat-run case")
  void completesAnExistingIncompleteEmployee() {
    Company company = new Company();
    Employee employee = importedEmployee(company, LocalDate.of(2022, 5, 1));
    employee.setStepByStepSelect(3); // a prior import left it inside the wizard

    assertTrue(BgEmployeeCompletionService.isIncomplete(employee));
    assertTrue(BgEmployeeCompletionService.applyCompletion(employee, company, null));
    assertEquals(BgEmployeeCompletionService.STEP_COMPLETED, (int) employee.getStepByStepSelect());
  }

  @Test
  @DisplayName("another company's contract never becomes this company's main contract")
  void doesNotAdoptAnotherCompanysContract() {
    Company active = new Company();
    active.setName("ТЕСТЕКС ООД");
    Company other = new Company();
    other.setName("ДРУГА ФИРМА ЕООД");

    Employee employee = importedEmployee(active, LocalDate.of(2022, 5, 1));
    EmploymentContract foreign = new EmploymentContract();
    foreign.setPayCompany(other);
    foreign.setStartDate(LocalDate.of(2024, 1, 1)); // later, but not ours
    employee.getEmploymentContractList().add(foreign);

    BgEmployeeCompletionService.applyCompletion(employee, active, null);
    assertEquals(LocalDate.of(2022, 5, 1), employee.getMainEmploymentContract().getStartDate());
    assertEquals(active, employee.getMainEmploymentContract().getPayCompany());
  }

  /**
   * The point of extracting the service at all: both callers go through it, so the two can never
   * drift. Asserted against the sources/view because "the button and the importer agree" is a
   * wiring fact, not a computation.
   */
  @Test
  @DisplayName("both the form button and the importer go through this one service")
  void bothCallersUseTheSharedService() throws IOException {
    String view = read("src/main/resources/views/Employee-bg.xml");
    assertTrue(
        view.contains("action-bg-localization-employee-method-finish-sbs"),
        "finishBtn must be routed to the shared service");
    assertTrue(
        view.contains("method=\"finishStepByStep\""), "the action-method must call the controller");

    String controller =
        read("src/main/java/com/axelor/apps/bglocalization/web/EmployeeBgController.java");
    assertTrue(controller.contains("BgEmployeeCompletionService.class"), controller);

    String importer =
        read(
            "src/main/java/com/axelor/apps/bglocalization/service/migration/microinvest/"
                + "MicroinvestPilotImportService.java");
    assertTrue(
        importer.contains("employeeCompletionService.complete("),
        "the importer must finish employees through the same service, not by setting the step");
    assertFalse(
        importer.contains("setStepByStepSelect"),
        "the importer must not carry its own copy of the completion rule");
  }

  private static String read(String relativePath) throws IOException {
    Path path = Paths.get(relativePath);
    assertTrue(Files.exists(path), "file not found: " + path.toAbsolutePath());
    return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
  }
}
