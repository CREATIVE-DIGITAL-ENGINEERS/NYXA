package com.axelor.apps.bglocalization.service.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.YearService;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.report.IReport;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.BgD1DeclarationKind;
import com.axelor.apps.bglocalization.service.d1.BgD1GenerationService;
import com.axelor.apps.bglocalization.service.d1.BgD1ReportPrintService;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgPaySlipController;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.report.ReportGenerator;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.validation.ValidationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ВИДИМИТЕ ПРОВАЛИ В ТРЗ — през истинска PostgreSQL база.
 *
 * <ul>
 *   <li><b>Д1 „Печат (PDF)“</b> не бива да пада с „Required parameter RefusalReport is blank“ на
 *       декларация без отказани фишове. Тук — PDF-ът на Д1 с данни, по пътя на приложението ({@link
 *       ReportGenerator}, JDBC връзката на JPA) и с параметрите на бутона ({@link
 *       BgD1ReportPrintService#params}).
 *   <li><b>„Служител“ показва име, не дата</b>: демо служител с празно {@code Partner.fullName} има
 *       и празно име, а името на договора се свежда до началната дата. Тук — служител, налят от
 *       самата демо стъпка ({@link BgDemoPayrollStep#employee}).
 *   <li><b>Изборът на договор в болничния</b> — само договорите на активното дружество (domain-ът
 *       от изгледа, изпълнен като заявка), и <b>отказ на сървъра</b> за болничен с договор на друго
 *       дружество ({@code BgSickLeaveManagementRepository#guardCompany}).
 * </ul>
 *
 * <p>Пуска се ръчно срещу СВОЯ PostgreSQL база ({@code -Ddemo.db.url/username/password}, {@code
 * -Ddemo.db.ddl=update}); без адрес се пропуска (CI няма база). Лицата и дружествата са измислени.
 */
@EnabledIfSystemProperty(named = "demo.db.url", matches = ".+")
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgDemoPostgresModule.class)
@Tag("integration")
class BgTrzVisibleDefectsPostgresIntegrationTest {

  private static final Path SICK_LEAVE_VIEWS =
      Paths.get("src/main/resources/views/bg-sick-leave-views.xml");

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  @Test
  void d1PrintWithDataGivesAPdfWhenThereAreNoRefusals() throws Exception {
    Fixture f = fixture("Печат Д1 ");
    Long slipId = confirmedSlip(f);
    assertNotNull(slipId);
    Long d1 =
        BgDemoTx.call(
            () -> {
              BgD1Declaration d = new BgD1Declaration();
              d.setCompany(f.ctx.company());
              d.setBgDataPeriod(JPA.find(Period.class, f.periodId));
              d.setBgDeclarationKind(BgD1DeclarationKind.REGULAR);
              return JPA.save(d).getId();
            });
    Beans.get(BgD1GenerationService.class).generate(d1);
    JPA.em().clear();

    BgD1Declaration declaration = JPA.find(BgD1Declaration.class, d1);
    long records = JPA.all(BgD1Record.class).filter("self.bgD1Declaration.id = ?1", d1).count();
    assertTrue(records > 0, "Д1 с данни: записи " + records);
    Map<String, Object> params = Beans.get(BgD1ReportPrintService.class).params(declaration);
    // ⚠️ ТОЧНО ТОВА ПОВАЛЯ ЗАДЪЛЖИТЕЛЕН ПАРАМЕТЪР: без отказани фишове → празен доклад.
    assertEquals("", params.get("RefusalReport"), "доклад за откази: " + params);

    params.put("__locale", "bg");
    File pdf =
        Beans.get(ReportGenerator.class)
            .generate(IReport.BG_D1_DECLARATION, "pdf", params, new Locale("bg"));
    byte[] bytes = Files.readAllBytes(pdf.toPath());
    Path dir = Paths.get("build", "d1-pdf");
    Files.createDirectories(dir);
    Files.write(dir.resolve("d1-" + d1 + ".pdf"), bytes);
    assertEquals(
        "%PDF", new String(bytes, 0, 4, StandardCharsets.ISO_8859_1), "PDF, не HTML с грешка");
    String text;
    try (PDDocument doc = Loader.loadPDF(bytes)) {
      assertTrue(doc.getNumberOfPages() > 0, "страници");
      text = new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
    }
    // Данните на записа стигат до листа: ЕГН-то на лицето (т. 5) и дружеството.
    assertTrue(text.contains(f.egn), "ЕГН " + f.egn + " в PDF-а:\n" + text);
    assertTrue(text.contains(f.ctx.company().getName()), "дружеството в PDF-а:\n" + text);
    assertFalse(text.contains("ОТКАЗАНИ ФИШОВЕ"), "без откази няма такъв ред:\n" + text);
  }

  @Test
  void theDemoEmployeeAndItsContractCarryThePersonsName() throws Exception {
    Fixture f = fixture("Име на договора ");
    JPA.em().clear();
    EmploymentContract contract = JPA.find(EmploymentContract.class, f.contractId);
    Employee employee = contract.getEmployee();
    String first = f.row.get("firstName");
    String last = f.row.get("lastName");
    assertNotNull(employee.getContactPartner().getFullName(), "пълното име на лицето");
    assertTrue(
        employee.getName() != null
            && employee.getName().contains(first)
            && employee.getName().contains(last),
        "името на служителя (колоната „Служител“): " + employee.getName());
    // Името на договора — от БАЗАТА (то се показва в полето), не от getter-а в паметта.
    String stored =
        (String)
            JPA.em()
                .createNativeQuery("SELECT full_name FROM hr_employment_contract WHERE id = ?1")
                .setParameter(1, f.contractId)
                .getSingleResult();
    assertTrue(
        stored != null && stored.contains(first) && stored.contains(last),
        "името на договора (полето „Трудов договор“) не е само дата: " + stored);
  }

  @Test
  void theSickLeaveContractPickerShowsOnlyTheActiveCompanysContracts() throws Exception {
    Fixture f = fixture("Избор договор ");
    Long other = otherCompanyContract(f);
    String domain = sickLeaveContractDomain();
    // Както Criteria: domain-ът от изгледа, параметрите — от ScriptBindings (тук {@code __user__}).
    List<Long> visible =
        JPA.all(EmploymentContract.class).filter(domain).bind(new HashMap<>()).fetch().stream()
            .map(EmploymentContract::getId)
            .collect(Collectors.toList());
    assertTrue(visible.contains(f.contractId), "договорът на активното дружество: " + visible);
    assertFalse(visible.contains(other), "договорът на друго дружество: " + visible);
    for (Long id : visible) {
      assertEquals(
          f.companyId,
          JPA.find(EmploymentContract.class, id).getPayCompany().getId(),
          "само активното дружество; договор " + id);
    }
  }

  @Test
  void aSickLeaveOnAnotherCompanysContractIsRefusedOnTheServer() throws Exception {
    Fixture f = fixture("Пазач болничен ");
    Long other = otherCompanyContract(f);
    LocalDate from = LocalDate.of(f.ctx.dates().first().getYear(), 1, 12);

    ValidationException refused =
        assertThrows(ValidationException.class, () -> BgDemoTx.call(() -> sickLeave(other, from)));
    assertTrue(
        refused.getMessage().contains("активното дружество"),
        "отказът казва защо: " + refused.getMessage());
    assertEquals(
        0L,
        (long)
            BgDemoTx.call(
                () ->
                    JPA.all(BgSickLeave.class)
                        .filter("self.employmentContract.id = ?1", other)
                        .count()),
        "нищо не е записано");

    // Същият лист по договор на активното дружество се записва.
    Long saved = BgDemoTx.call(() -> sickLeave(f.contractId, from));
    assertNotNull(saved, "болничен по договор на активното дружество");
  }

  // ═══ фикстура ═══════════════════════════════════════════════════════════════════════════

  private static final class Fixture {
    Long companyId;
    Long contractId;
    Long periodId;
    String egn;
    BgDemoCsv.Row row;
    BgDemoContext ctx;
  }

  /** Ново BG_COA дружество, активно за admin, с ПЪРВИЯ служител от демо набора — през стъпката. */
  private static Fixture fixture(String companyName) throws Exception {
    assumeTrue(System.getProperty("demo.db.url") != null, "без -Ddemo.db.url (своя база)");
    BgDemoInstallation.ensure();
    Fixture f = new Fixture();
    String tag = Long.toString(System.nanoTime() % 1000000);
    f.companyId = BgDemoInstallation.newCompany(companyName + tag, "VD" + tag);
    BgDemoInstallation.loginAdmin(f.companyId);
    int year = LocalDate.now().getYear();
    f.ctx = new BgDemoContext(f.companyId, BgDemoDataset.TRADE, year);
    BgDemoCompanyStep.run(f.ctx);
    f.row = f.ctx.rows("employees").get(0);
    f.egn = f.row.get("egn");
    // ⚠️ Самата демо стъпка — точно тя раждаше лицето без пълно име.
    BgDemoTx.run(() -> BgDemoPayrollStep.employee(f.ctx, f.row));
    f.contractId =
        BgDemoTx.call(
            () -> f.ctx.find(EmploymentContract.class, "contract", f.row.get("code")).getId());
    f.periodId = BgDemoTx.call(() -> payrollPeriod(f.ctx.company(), LocalDate.of(year, 1, 1)));
    return f;
  }

  /** Фиш за януари: чернова → „Преизчисли“ → „Потвърди“, през контролера, както от екрана. */
  private static Long confirmedSlip(Fixture f) throws Exception {
    Long slipId =
        BgDemoTx.call(
            () -> {
              BgPaySlip slip = new BgPaySlip();
              slip.setEmploymentContract(JPA.find(EmploymentContract.class, f.contractId));
              slip.setPeriod(JPA.find(Period.class, f.periodId));
              slip.setReference("ФИШ-Д1-" + f.companyId);
              slip.setStatusSelect(BgPaySlipResolver.DRAFT);
              slip.setBgIsCorrection(false);
              slip.setBgIsDeletion(false);
              slip.setBgAutoFillBaseSalary(true);
              slip.setBgNoOtherPayerIncome(true);
              slip.setBgPaySlipLineList(new ArrayList<>());
              return JPA.save(slip).getId();
            });
    BgPaySlipController controller = Beans.get(BgPaySlipController.class);
    press(slipId, controller::recalculate);
    press(slipId, controller::confirm);
    assertEquals(
        BgPaySlipResolver.CONFIRMED,
        BgDemoTx.call(() -> JPA.find(BgPaySlip.class, slipId).getStatusSelect()),
        "потвърден фиш");
    return slipId;
  }

  /** Друго дружество (без сметкоплан — не трябва) с договор на друго лице. */
  private static Long otherCompanyContract(Fixture f) throws Exception {
    return BgDemoTx.call(
        () -> {
          Company other = new Company();
          String tag = Long.toString(System.nanoTime() % 1000000);
          other.setName("Друго дружество " + tag);
          other.setCode("VO" + tag);
          other = JPA.save(other);
          Partner person = new Partner();
          person.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_INDIVIDUAL);
          person.setIsContact(true);
          person.setFirstName("Иван");
          person.setName("Другов Демов");
          person = Beans.get(PartnerRepository.class).save(person);
          Employee e = new Employee();
          e.setContactPartner(person);
          e.setBgPersonalIdNumber("8001010007");
          e.setBgPersonalIdType("1");
          e = JPA.save(e);
          EmploymentContract c = new EmploymentContract();
          c.setEmployee(e);
          c.setPayCompany(other);
          c.setStartDate(LocalDate.of(f.ctx.dates().first().getYear() - 1, 1, 1));
          c.setBgInsuredTypeCode("01");
          return JPA.save(c).getId();
        });
  }

  /** Болничен за 12–16.01, записан през repository-то (както формата и {@code /ws/rest}). */
  private static Long sickLeave(Long contractId, LocalDate from) {
    BgSickLeave sick = new BgSickLeave();
    sick.setEmploymentContract(JPA.find(EmploymentContract.class, contractId));
    sick.setBgAbsenceType(
        JPA.all(BgAbsenceType.class)
            .filter(
                "self.requiresSickNote = true AND self.countsAsInsuranceService = true"
                    + " AND self.code NOT IN ('sick_no_benefit_er','sick_no_benefit_doo')")
            .order("code")
            .fetchOne());
    assertNotNull(sick.getBgAbsenceType(), "вид отсъствие с болничен лист от сийда");
    sick.setFromDate(from);
    sick.setToDate(from.plusDays(4));
    sick.setStatusSelect("DRAFT");
    sick.setBgDocumentNumber(String.format("%07d", (System.nanoTime() / 1000) % 10_000_000L));
    return Beans.get(BgSickLeaveRepository.class).save(sick).getId();
  }

  /** Domain-ът на полето „Трудов договор“ във формата на болничния — дословно от изгледа. */
  private static String sickLeaveContractDomain() throws Exception {
    String xml = new String(Files.readAllBytes(SICK_LEAVE_VIEWS), StandardCharsets.UTF_8);
    Matcher m =
        Pattern.compile(
                "<field name=\"employmentContract\" canEdit=\"false\"\\s+grid-view=\"[^\"]+\"\\s+"
                    + "domain=\"([^\"]+)\"")
            .matcher(xml);
    assertTrue(m.find(), "полето employmentContract във формата на болничния има domain");
    return m.group(1).replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&");
  }

  /** Заплатната година с месечни периоди (услугата на Base, както бутонът) — периодът на месеца. */
  private static Long payrollPeriod(Company company, LocalDate from) throws Exception {
    YearService years = Beans.get(YearService.class);
    Year year =
        years.createYear(
            company,
            "ТРЗ " + from.getYear(),
            "TRZ" + from.getYear() + "-" + company.getCode(),
            from,
            from.withMonth(12).withDayOfMonth(31),
            YearRepository.DURATION_MONTH,
            YearRepository.TYPE_PAYROLL);
    years.generatePeriodsForYear(year);
    return JPA.all(Period.class)
        .filter("self.year = ?1 AND self.fromDate = ?2", year, from)
        .fetchOne()
        .getId();
  }

  /** Бутонът от формата на фиша; отказ ({@code error}) спира теста. */
  private static void press(Long slipId, BgDemoForm.Action action) throws Exception {
    Map<String, Object> context = new HashMap<>();
    context.put("_model", BgPaySlip.class.getName());
    context.put("id", slipId);
    context.put("version", BgDemoTx.call(() -> JPA.find(BgPaySlip.class, slipId).getVersion()));
    ActionRequest request = new ActionRequest();
    request.setModel(BgPaySlip.class.getName());
    request.setData(Map.of("context", context));
    ActionResponse response = new ActionResponse();
    action.call(request, response);
    JPA.em().clear();
    Object error = BgDemoForm.item(response).get("error");
    if (error != null) {
      throw new IllegalStateException(String.valueOf(error));
    }
  }
}
