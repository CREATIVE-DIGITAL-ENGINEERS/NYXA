package com.axelor.apps.bglocalization.service.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.bglocalization.db.BgArt73Para1Report;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import com.axelor.apps.bglocalization.service.saft.BgSaftAnnualOnDemandService;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationStatus;
import com.axelor.apps.bglocalization.service.zddfl.BgIndividualIncomePaymentService;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgArt73Para1ReportController;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.SaftValidationIssue;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.apps.napsubmissions.services.saft.SaftCheckResult;
import com.axelor.apps.napsubmissions.services.saft.SaftFileEngine;
import com.axelor.apps.napsubmissions.web.SaftReportController;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * БУТОНИТЕ „ПРОВЕРИ“ / „ГЕНЕРИРАЙ“ НА ЗАПИСАН ОТЧЕТ — през истинска PostgreSQL база и истинските
 * читатели, по пътя на екрана: нов запис → действието от контролера.
 *
 * <p>Пази два случая, които падат само срещу истинска база:
 *
 * <ul>
 *   <li><b>Месечен SAF-T</b> — „PersistentObjectException: detached entity passed to persist:
 *       SaftReport“ и при „Провери“, и при „Генерирай“. Читателите на SAF-T четат на парчета и
 *       чистят сесията ({@code JPA.clear()}) — но САМО когато месецът има осчетоводени редове.
 *       Отчетът, зареден от контролера, остава откачен, а новата находка ({@code
 *       SaftValidationIssue}) каскадира persist към него. На празен месец всичко минава — затова
 *       тук има статия в месеца. Екранният тест на G6b (HSQLDB) подменя входа на генератора и
 *       читателите не се изпълняват там.
 *   <li><b>Справка по чл. 73 ал. 1</b> — „HibernateException: A collection with
 *       cascade="all-delete-orphan" was no longer referenced by the owning entity instance:
 *       BgArt73Para1Report.recordList“, ако строенето подмени списъка с редове на ЗАПИСАНА справка
 *       ({@code setRecordList}), а бутонът работи само върху записана — тоест би падал винаги.
 * </ul>
 *
 * <p>Пуска се ръчно срещу СВОЯ PostgreSQL база ({@code -Ddemo.db.url/username/password}, {@code
 * -Ddemo.db.ddl=update}); без адрес се пропуска (CI няма база). Дружеството е ново при всяко
 * пускане ({@link BgDemoInstallation}, BG_COA), лицето е измислено.
 */
@EnabledIfSystemProperty(named = "demo.db.url", matches = ".+")
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgDemoPostgresModule.class)
@Tag("integration")
class BgReportGeneratePostgresIntegrationTest {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  @Test
  void saftMonthlyCheckAndGenerateWorkOnAMonthWithPostedMoves() throws Exception {
    BgDemoContext ctx = company("SAF-T бутони ");
    LocalDate month = LocalDate.of(ctx.dates().year(), 1, 1);
    Long moveId = BgDemoTx.call(() -> postedMove(ctx.company(), month.withDayOfMonth(15)));
    assertEquals(
        MoveRepository.STATUS_ACCOUNTED,
        BgDemoTx.call(() -> JPA.find(Move.class, moveId).getStatusSelect()),
        "предпоставка: осчетоводена статия в месеца — без нея читателите не чистят сесията");

    Long reportId =
        BgDemoTx.call(
            () -> {
              SaftReport report = new SaftReport();
              report.setName("SAF-T " + month + " " + System.nanoTime());
              report.setCompany(ctx.company());
              report.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_MONTHLY);
              report.setPeriod(fiscalPeriod(ctx.companyId(), month));
              return JPA.save(report).getId();
            });
    checkAndGenerate(reportId, null);
  }

  /**
   * Годишният (писта A — активи): днес без читатели, отказва с „съдържанието идва с пистата“.
   * Свидетелят пази пътя от екрана — записан отказ с находка, без изключение — и за деня, в който
   * читателите на активите дойдат (обработката в {@code SaftReportWorkflowService} е обща).
   */
  @Test
  void saftAnnualCheckAndGenerateStoreTheRefusal() throws Exception {
    BgDemoContext ctx = company("SAF-T годишен ");
    int year = ctx.dates().year() - 1;
    Long reportId =
        BgDemoTx.call(
            () -> {
              SaftReport report = new SaftReport();
              report.setName("SAF-T " + year + " " + System.nanoTime());
              report.setCompany(ctx.company());
              report.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_ANNUAL);
              report.setReportYear(year);
              return JPA.save(report).getId();
            });
    checkAndGenerate(reportId, BgSaftAnnualOnDemandService.ANNUAL_CONTENT_PENDING);
  }

  /** При поискване (писта S — склад): същото, по искане на НАП за минал месец. */
  @Test
  void saftOnDemandCheckAndGenerateStoreTheRefusal() throws Exception {
    BgDemoContext ctx = company("SAF-T при поискване ");
    LocalDate from = LocalDate.of(ctx.dates().year(), 1, 1);
    Long reportId =
        BgDemoTx.call(
            () -> {
              SaftReport report = new SaftReport();
              report.setName("SAF-T искане " + System.nanoTime());
              report.setCompany(ctx.company());
              report.setReportTypeSelect(SaftReportRepository.REPORT_TYPE_ON_DEMAND);
              report.setNapRequestNumber("ИСК-" + System.nanoTime() % 100000);
              report.setNapRequestDate(from.plusMonths(1));
              report.setFromDate(from);
              report.setToDate(from.withDayOfMonth(from.lengthOfMonth()));
              return JPA.save(report).getId();
            });
    checkAndGenerate(reportId, BgSaftAnnualOnDemandService.ON_DEMAND_CONTENT_PENDING);
  }

  /**
   * „Провери“ и „Генерирай“ от контролера: резултатът и находките са записани, изключение няма;
   * S.H.1 (1.0.2 / 1.0.3) е по датата на генериране на дружеството ({@link BgSaftXsd#forDate}).
   *
   * @param pendingCode находката, която отказът трябва да носи; {@code null} — без изискване
   */
  private static void checkAndGenerate(Long reportId, String pendingCode) throws Exception {
    SaftReportController controller = Beans.get(SaftReportController.class);

    // „Провери“: статусите и находките се записват — отказът връщаше транзакцията и отчетът
    // оставаше „Чернова“, без нито една находка.
    ActionResponse checked = press(SaftReport.class, reportId, controller::validate);
    SaftReport afterCheck = BgDemoTx.call(() -> JPA.find(SaftReport.class, reportId));
    assertNoException(checked);
    assertNotEquals(
        SaftReportRepository.LIFECYCLE_DRAFT,
        afterCheck.getLifecycleStatusSelect(),
        "„Провери“ записа резултата; отговор: " + checked.getData());
    assertNotEquals(
        SaftReportRepository.READINESS_NOT_CHECKED, afterCheck.getReadinessStatusSelect());
    assertFindingsStored(reportId, afterCheck, checked);

    // „Генерирай“: файл или отказ с находки — но не изключение.
    ActionResponse generated = press(SaftReport.class, reportId, controller::generate);
    SaftReport afterGenerate = BgDemoTx.call(() -> JPA.find(SaftReport.class, reportId));
    assertNoException(generated);
    assertTrue(
        SaftReportRepository.LIFECYCLE_GENERATED.equals(afterGenerate.getLifecycleStatusSelect())
            || SaftReportRepository.LIFECYCLE_FAILED.equals(
                afterGenerate.getLifecycleStatusSelect()),
        "„Генерирай“ записа резултата („generated“ или „failed“), а е „"
            + afterGenerate.getLifecycleStatusSelect()
            + "“; отговор: "
            + generated.getData());
    assertFindingsStored(reportId, afterGenerate, generated);
    if (pendingCode != null) {
      assertEquals(SaftReportRepository.LIFECYCLE_FAILED, afterGenerate.getLifecycleStatusSelect());
      long pending =
          BgDemoTx.call(
              () ->
                  JPA.all(SaftValidationIssue.class)
                      .filter(
                          "self.saftReport.id = ?1 AND self.sectionFieldId = ?2",
                          reportId,
                          pendingCode)
                      .count());
      assertEquals(1, pending, "записаната находка " + pendingCode);
    }

    // Версията на схемата — от двигателя, по датата на дружеството, за всеки вид еднаква.
    SaftCheckResult version =
        BgDemoTx.call(
            () -> {
              SaftReport report = JPA.find(SaftReport.class, reportId);
              LocalDate today = Beans.get(AppBaseService.class).getTodayDate(report.getCompany());
              SaftCheckResult result = Beans.get(SaftFileEngine.class).run(report, today, null);
              assertEquals(
                  BgSaftXsd.forDate(today).getAuditFileVersion(),
                  result.getAuditFileVersion(),
                  "S.H.1 по датата на генериране " + today);
              System.out.println(
                  "SAF-T "
                      + report.getReportTypeSelect()
                      + " на "
                      + today
                      + ": S.H.1 "
                      + result.getAuditFileVersion());
              return result;
            });
    assertTrue(version.getAuditFileVersion() != null, "S.H.1 е известна");
  }

  @Test
  void art73Para1GenerateAndRegenerateOnASavedReport() throws Exception {
    BgDemoContext ctx = company("Чл. 73 бутони ");
    int year = ctx.dates().year();
    BgDemoTx.run(() -> incomePayment(ctx.company(), LocalDate.of(year, 3, 10)));
    Long reportId =
        BgDemoTx.call(
            () -> {
              BgArt73Para1Report report = new BgArt73Para1Report();
              report.setCompany(ctx.company());
              report.setTaxYear(year);
              report.setStatusSelect(BgDeclarationStatus.DRAFT);
              return JPA.save(report).getId();
            });
    BgArt73Para1ReportController controller = Beans.get(BgArt73Para1ReportController.class);

    ActionResponse generated = press(BgArt73Para1Report.class, reportId, controller::generate);
    assertNoException(generated);
    List<Long> first = records(reportId);
    assertEquals(1, first.size(), "един ред за лицето; отговор: " + generated.getData());
    assertEquals(
        BgDeclarationStatus.GENERATED,
        BgDemoTx.call(() -> JPA.find(BgArt73Para1Report.class, reportId).getStatusSelect()));

    // Пресъздаването строи НАНОВО върху същата записана справка: старите редове изчезват, не се
    // долепват (без setRecordList, който на записана справка пада).
    ActionResponse regenerated = press(BgArt73Para1Report.class, reportId, controller::regenerate);
    assertNoException(regenerated);
    List<Long> second = records(reportId);
    assertEquals(1, second.size(), "пак един ред; отговор: " + regenerated.getData());
    assertTrue(
        second.stream().noneMatch(first::contains),
        "старият ред е изтрит, не е оставен до новия: " + first + " → " + second);
  }

  // ─────────────── подготовка ───────────────

  /** Ново дружество (BG_COA, фискална година с месечни периоди, реквизити), admin с него. */
  private static BgDemoContext company(String name) throws Exception {
    assumeTrue(System.getProperty("demo.db.url") != null, "без -Ddemo.db.url (своя база)");
    BgDemoInstallation.ensure();
    String tag = Long.toString(System.nanoTime() % 1000000);
    Long companyId = BgDemoInstallation.newCompany(name + tag, "RG" + tag);
    BgDemoInstallation.loginAdmin(companyId);
    BgDemoContext ctx =
        new BgDemoContext(companyId, BgDemoDataset.TRADE, LocalDate.now().getYear());
    BgDemoCompanyStep.run(ctx);
    return ctx;
  }

  private static Period fiscalPeriod(Long companyId, LocalDate month) {
    Period period =
        JPA.all(Period.class)
            .filter(
                "self.year.company.id = ?1 AND self.year.typeSelect = ?2 AND self.fromDate = ?3",
                companyId,
                YearRepository.TYPE_FISCAL,
                month)
            .fetchOne();
    assertTrue(period != null, "фискален период за " + month);
    return period;
  }

  /** Теглене от разплащателната сметка в касата (501/503), осчетоводено като ръчно въведено. */
  private static Long postedMove(Company company, LocalDate date) throws Exception {
    Journal journal =
        JPA.all(Journal.class)
            .filter("self.company = ?1 AND self.code = 'BNK'", company)
            .fetchOne();
    Move move =
        Beans.get(MoveCreateService.class)
            .createMove(
                journal,
                company,
                company.getCurrency(),
                null,
                date,
                date,
                null,
                null,
                MoveRepository.TECHNICAL_ORIGIN_ENTRY,
                MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT,
                "ТЕГЛ-1",
                "Теглене в брой",
                null);
    BigDecimal amount = new BigDecimal("100.00");
    MoveLineCreateService lines = Beans.get(MoveLineCreateService.class);
    MoveLine cash =
        lines.createMoveLine(
            move,
            null,
            BgDemoPartnerStep.account(company, "501"),
            amount,
            amount,
            BigDecimal.ONE,
            true,
            date,
            date,
            date,
            1,
            "ТЕГЛ-1",
            "Теглене в брой");
    MoveLine bank =
        lines.createMoveLine(
            move,
            null,
            BgDemoPartnerStep.account(company, "503"),
            amount,
            amount,
            BigDecimal.ONE,
            false,
            date,
            date,
            date,
            2,
            "ТЕГЛ-1",
            "Теглене в брой");
    move.addMoveLineListItem(cash);
    move.addMoveLineListItem(bank);
    Long id = Beans.get(MoveRepository.class).save(move).getId();
    Beans.get(MoveValidateService.class).accounting(JPA.find(Move.class, id));
    return id;
  }

  /** Авторски хонорар (вид 304, справка 73-1) на измислено лице с ЕГН — един ред в Част ІІ. */
  private static void incomePayment(Company company, LocalDate date) {
    Partner person = new Partner();
    person.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_INDIVIDUAL);
    person.setIsContact(false);
    person.setFirstName("Иван");
    person.setName("Хонорарев Демов");
    person.setRegistrationCode("7501011016");
    person.setCompany(company);
    person = JPA.save(person);
    BgIncomeType type = JPA.all(BgIncomeType.class).filter("self.code = '304'").fetchOne();
    assertTrue(type != null, "вид доход 304 от сийда");
    BgIndividualIncomePayment payment = new BgIndividualIncomePayment();
    payment.setCompany(company);
    payment.setPartner(person);
    payment.setIncomeType(type);
    payment.setPaymentDate(date);
    payment.setGrossAmount(new BigDecimal("1000.00"));
    payment.setIdentifierType("0");
    payment.setSource(BgIndividualIncomePaymentService.SOURCE_MANUAL);
    payment.setCorrectionCode(BgIndividualIncomePaymentService.CORRECTION_PRIMARY);
    Beans.get(BgIndividualIncomePaymentService.class).computeInto(payment);
    JPA.save(payment);
  }

  // ─────────────── проверки ───────────────

  /** Отказът на генератора е FAILED с находки в базата; иначе — нищо за проверка. */
  private static void assertFindingsStored(
      Long reportId, SaftReport report, ActionResponse response) throws Exception {
    if (!SaftReportRepository.LIFECYCLE_FAILED.equals(report.getLifecycleStatusSelect())) {
      return;
    }
    long issues =
        BgDemoTx.call(
            () ->
                JPA.all(SaftValidationIssue.class)
                    .filter("self.saftReport.id = ?1", reportId)
                    .count());
    assertTrue(issues > 0, "FAILED без записани находки; отговор: " + response.getData());
  }

  /** Изключение от контролера стига до екрана като текст — тук не трябва да има такъв. */
  private static void assertNoException(ActionResponse response) {
    String data = String.valueOf(response.getData());
    for (String marker :
        new String[] {
          "Exception", "detached entity", "no longer referenced", "LazyInitialization"
        }) {
      assertTrue(!data.contains(marker), "изключение на екрана („" + marker + "“): " + data);
    }
  }

  private static List<Long> records(Long reportId) throws Exception {
    return BgDemoTx.call(
        () ->
            JPA.em()
                .createQuery(
                    "SELECT r.id FROM BgArt73Para1Record r WHERE r.report.id = :id ORDER BY r.id",
                    Long.class)
                .setParameter("id", reportId)
                .getResultList());
  }

  /** Бутонът от формата: контекстът на записа (id и версия) към контролера, както от екрана. */
  private static ActionResponse press(
      Class<? extends Model> model, Long id, BgDemoForm.Action action) throws Exception {
    Map<String, Object> context = new HashMap<>();
    context.put("_model", model.getName());
    context.put("id", id);
    context.put("version", BgDemoTx.call(() -> JPA.find(model, id).getVersion()));
    ActionRequest request = new ActionRequest();
    request.setModel(model.getName());
    request.setData(Map.of("context", context));
    ActionResponse response = new ActionResponse();
    action.call(request, response);
    JPA.em().clear();
    return response;
  }
}
