package com.axelor.apps.bglocalization.service.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.PartnerService;
import com.axelor.apps.base.service.YearService;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgPaySlipController;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * „ИЗПЛАТИ“ НА ФИШ ПРЕЗ ИСТИНСКА PostgreSQL БАЗА — изплатен фиш и балансирана статия по заплатата.
 *
 * <p>{@code BgPaySlipMoveService.write} не бива да подменя списъка с редовете ({@code
 * setMoveLineList}) на статия, която {@code MoveCreateServiceImpl#createMove} вече е записал:
 * тогава Hibernate отказва при flush („A collection with cascade="all-delete-orphan" was no longer
 * referenced by the owning entity instance“), изплащането се връща цялото, фишът остава потвърден,
 * а контролерът показва отказа като info — тоест нито един фиш не може да се изплати. Unit
 * тестовете не го хващат: те викат записа с подменени услуги, без JPA.
 *
 * <p>И статията не бива да остава ЧЕРНОВА — неосчетоводена статия значи изплатени ведомости без
 * разход за заплати в оборотната ведомост, ОПР, SAF-T и ГОД. Затова: осчетоводена статия с номер от
 * поредицата на журнала; затворен период или провал на осчетоводяването връща изплащането цяло, без
 * висяща чернова; и потребител само с {@code hr.payroll.write} изплаща, без право за запис върху
 * статии (G13 по журнала).
 *
 * <p>Тук е до демо теста, защото ползва същата изолирана инсталация ({@link BgDemoInstallation}) и
 * същата подготовка на дружеството ({@link BgDemoCompanyStep}: BG_COA, фискална година, реквизити).
 * Пуска се ръчно срещу СВОЯ PostgreSQL база ({@code -Ddemo.db.url/username/password}, {@code
 * -Ddemo.db.ddl=update}); без адрес се пропуска (CI няма база). Лицето е измислено.
 */
@EnabledIfSystemProperty(named = "demo.db.url", matches = ".+")
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgDemoPostgresModule.class)
@Tag("integration")
class BgPaySlipPayPostgresIntegrationTest {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  @Test
  void payTurnsAConfirmedSlipIntoAPaidOneWithAPostedNumberedPayrollMove() throws Exception {
    Fixture f = confirmedSlip("Изплащане ТРЗ ");
    ActionResponse response = press(f.slipId, f.controller::pay, Map.of("bgPaidDate", f.paid));

    JPA.em().clear();
    BgPaySlip slip = JPA.find(BgPaySlip.class, f.slipId);
    assertEquals(
        BgPaySlipResolver.PAID, slip.getStatusSelect(), "изплатен; отговор: " + response.getData());
    Move move = slip.getBgPayrollMove();
    assertNotNull(move, "статия по заплатата; бележка: " + slip.getBgPayrollMoveNote());
    assertEquals(f.companyId, move.getCompany().getId(), "статията е на дружеството");
    assertEquals(f.paid, move.getDate(), "датата на изплащане");
    Journal journal = f.ctx.company().getAccountConfig().getBgPayrollJournal();
    assertEquals(journal.getId(), move.getJournal().getId(), "заплатният журнал");

    // ⚠️ ОСЧЕТОВОДЕНА, НЕ ЧЕРНОВА. Статусът го решава AOS: BG_COA е без дневник
    // (accountingDaybook = false), тоест MoveValidateService#accounting дава „Осчетоводена“.
    assertFalse(
        Boolean.TRUE.equals(f.ctx.company().getAccountConfig().getAccountingDaybook()),
        "предпоставка: дружеството не е в режим „дневник“");
    assertEquals(
        MoveRepository.STATUS_ACCOUNTED,
        move.getStatusSelect(),
        "осчетоводена статия, не „Ново движение“");
    // Номерът — от поредицата на журнала за ТОВА дружество (BG_COA: PAY%YY, 5 цифри).
    String yy = String.format("%02d", f.paid.getYear() % 100);
    assertTrue(
        move.getReference() != null && move.getReference().matches("PAY" + yy + "\\d{5}"),
        "номер от поредицата на журнала: " + move.getReference());
    assertNotNull(journal.getSequence(), "журналът има поредица");
    assertEquals(
        f.companyId, journal.getSequence().getCompany().getId(), "поредицата е на дружеството");

    // Редовете — от базата, не от паметта: точно те бяха изгубени при подменения списък.
    List<MoveLine> lines =
        JPA.all(MoveLine.class).filter("self.move.id = ?1", move.getId()).order("counter").fetch();
    assertTrue(lines.size() >= 2, "редове на статията: " + lines.size());
    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    int net = 0;
    for (MoveLine line : lines) {
      debit = debit.add(line.getDebit());
      credit = credit.add(line.getCredit());
      if ("4211".equals(line.getAccount().getCode())) {
        net++;
        // Нетото към лицето носи партньора на служителя — по него се съгласува плащането.
        assertNotNull(line.getPartner(), "ред по 4211 с партньор");
        assertEquals(f.personId, line.getPartner().getId(), "партньорът е лицето");
      }
    }
    assertTrue(net > 0, "статията има редове по 4211");
    assertTrue(debit.signum() > 0, "оборот по дебита");
    assertEquals(0, debit.compareTo(credit), "балансирана: дебит " + debit + ", кредит " + credit);

    // Повторно „Изплати“ на изплатен фиш: PAID → PAID не е отказ (refuseTransition пуска „същото
    // към същото“), но шевът излиза на първия ред — фишът вече има статия. Втора НЕ се ражда.
    Long moveId = move.getId();
    pressRaw(f.slipId, f.controller::pay, Map.of("bgPaidDate", f.paid));
    JPA.em().clear();
    BgPaySlip twice = JPA.find(BgPaySlip.class, f.slipId);
    assertEquals(BgPaySlipResolver.PAID, twice.getStatusSelect());
    assertEquals(moveId, twice.getBgPayrollMove().getId(), "същата статия");
    assertEquals(1, movesOf(f), "една статия по фиша");
  }

  @Test
  void aClosedFiscalPeriodRefusesThePaymentAndLeavesNoMove() throws Exception {
    Fixture f = confirmedSlip("Затворен период ТРЗ ");
    BgDemoTx.run(
        () -> {
          Period fiscal =
              JPA.all(Period.class)
                  .filter(
                      "self.year.company.id = ?1 AND self.year.typeSelect = ?2"
                          + " AND self.fromDate <= ?3 AND self.toDate >= ?3",
                      f.companyId,
                      YearRepository.TYPE_FISCAL,
                      f.paid)
                  .fetchOne();
          assertNotNull(fiscal, "фискален период за датата на изплащане");
          fiscal.setStatusSelect(PeriodRepository.STATUS_CLOSED);
        });

    ActionResponse response = pressRaw(f.slipId, f.controller::pay, Map.of("bgPaidDate", f.paid));

    String error = errorOf(response);
    assertNotNull(error, "„Изплати“ трябва да откаже; отговор: " + response.getData());
    assertTrue(error.startsWith("Фишът не се изплаща: "), "ясен отказ на екрана: " + error);
    assertTrue(error.contains("ЗАТВОРЕН"), error);
    assertNotPaidAndWithoutMove(f);
  }

  @Test
  void aFailedAccountingRollsThePaymentBackWithoutADraftMove() throws Exception {
    Fixture f = confirmedSlip("Отказ осчетоводяване ТРЗ ");
    // AOS отказва статия с произход, който журналът не позволява (checkFunctionalOriginSelect) —
    // тоест ПРОВАЛ ВЪТРЕ В accounting, след като createMove вече е записал черновата.
    BgDemoTx.run(
        () ->
            JPA.find(
                    Journal.class, f.ctx.company().getAccountConfig().getBgPayrollJournal().getId())
                .setAuthorizedFunctionalOriginSelect("6,7"));

    ActionResponse response = pressRaw(f.slipId, f.controller::pay, Map.of("bgPaidDate", f.paid));

    String error = errorOf(response);
    assertNotNull(error, "„Изплати“ трябва да откаже; отговор: " + response.getData());
    assertTrue(
        error.startsWith("Фишът не се изплаща: статията по заплатата не може да се осчетоводи"),
        "ясен отказ на екрана: " + error);
    assertNotPaidAndWithoutMove(f);
  }

  @Test
  void aPayrollOnlyUserPaysAndPostsWithoutWriteOnMoves() throws Exception {
    Fixture f = confirmedSlip("Само ТРЗ ");
    // G13 (ManualValidationGuard): статия, осчетоводена в същата транзакция, иска запис върху
    // документа-източник. hr.payroll.write пише фишове, но НЕ статии — заплатната статия се
    // разпознава по журнала и иска запис върху фиша.
    BgRoleLogin.login(f.ctx.company(), BgRoleLogin.PAYROLL_WRITE);

    ActionResponse response = pressRaw(f.slipId, f.controller::pay, Map.of("bgPaidDate", f.paid));

    assertNull(errorOf(response), "без отказ: " + response.getData());
    JPA.em().clear();
    BgPaySlip slip = JPA.find(BgPaySlip.class, f.slipId);
    assertEquals(BgPaySlipResolver.PAID, slip.getStatusSelect());
    assertNotNull(slip.getBgPayrollMove(), slip.getBgPayrollMoveNote());
    assertEquals(MoveRepository.STATUS_ACCOUNTED, slip.getBgPayrollMove().getStatusSelect());
  }

  /** Текстът на отказа от екрана ({@code error} е {@code {message=…}}), или {@code null}. */
  private static String errorOf(ActionResponse response) {
    Object error = BgDemoForm.item(response).get("error");
    if (error instanceof Map) {
      Object message = ((Map<?, ?>) error).get("message");
      return message == null ? String.valueOf(error) : String.valueOf(message);
    }
    return error == null ? null : String.valueOf(error);
  }

  /** Фишът остава потвърден, без препратка, и в базата няма статия по него — нито чернова. */
  private static void assertNotPaidAndWithoutMove(Fixture f) throws Exception {
    JPA.em().clear();
    BgPaySlip slip = JPA.find(BgPaySlip.class, f.slipId);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect(), "фишът НЕ е изплатен");
    assertNull(slip.getBgPayrollMove(), "без препратка към статия");
    assertEquals(0, movesOf(f), "няма статия по фиша — нито чернова");
  }

  /** Статиите на дружеството с произход номера на фиша — всички статуси. */
  private static long movesOf(Fixture f) throws Exception {
    return BgDemoTx.call(
        () ->
            JPA.all(Move.class)
                .filter("self.company.id = ?1 AND self.origin = ?2", f.companyId, f.reference)
                .count());
  }

  /** Ново дружество (BG_COA), лице с трудов договор и потвърден фиш за януари — преди „Изплати“. */
  private static Fixture confirmedSlip(String companyName) throws Exception {
    assumeTrue(System.getProperty("demo.db.url") != null, "без -Ddemo.db.url (своя база)");
    BgDemoInstallation.ensure();
    Fixture f = new Fixture();
    String tag = Long.toString(System.nanoTime() % 1000000);
    f.companyId = BgDemoInstallation.newCompany(companyName + tag, "PP" + tag);
    BgDemoInstallation.loginAdmin(f.companyId);
    int year = LocalDate.now().getYear();
    f.ctx = new BgDemoContext(f.companyId, BgDemoDataset.TRADE, year);
    BgDemoCompanyStep.run(f.ctx);

    LocalDate from = LocalDate.of(year, 1, 1);
    f.paid = from.withDayOfMonth(from.lengthOfMonth());
    Long contractId = BgDemoTx.call(() -> contract(f.ctx.company(), from));
    f.personId =
        BgDemoTx.call(
            () ->
                JPA.find(EmploymentContract.class, contractId)
                    .getEmployee()
                    .getContactPartner()
                    .getId());
    Long periodId = BgDemoTx.call(() -> payrollPeriod(f.ctx.company(), from));
    f.reference = "ФИШ-ИЗПЛ-" + tag;
    f.slipId =
        BgDemoTx.call(
            () -> {
              BgPaySlip slip = new BgPaySlip();
              slip.setEmploymentContract(JPA.find(EmploymentContract.class, contractId));
              slip.setPeriod(JPA.find(Period.class, periodId));
              slip.setReference(f.reference);
              slip.setStatusSelect(BgPaySlipResolver.DRAFT);
              slip.setBgIsCorrection(false);
              slip.setBgIsDeletion(false);
              slip.setBgAutoFillBaseSalary(true);
              slip.setBgNoOtherPayerIncome(true);
              slip.setBgPaySlipLineList(new ArrayList<>());
              return JPA.save(slip).getId();
            });

    f.controller = Beans.get(BgPaySlipController.class);
    press(f.slipId, f.controller::recalculate, Map.of());
    press(f.slipId, f.controller::confirm, Map.of());
    assertEquals(BgPaySlipResolver.CONFIRMED, status(f.slipId), "потвърден преди „Изплати“");
    BgDemoTx.run(() -> JPA.find(BgPaySlip.class, f.slipId).setBgPaidDate(f.paid));
    return f;
  }

  private static final class Fixture {
    Long companyId;
    Long slipId;
    Long personId;
    String reference;
    LocalDate paid;
    BgDemoContext ctx;
    BgPaySlipController controller;
  }

  private static String status(Long slipId) throws Exception {
    return BgDemoTx.call(() -> JPA.find(BgPaySlip.class, slipId).getStatusSelect());
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

  /**
   * Измислено лице (ЕГН с вярна контролна цифра, IBAN с DEMO) с трудов договор, вид осигурен 01.
   */
  private static Long contract(Company company, LocalDate from) throws Exception {
    Partner person = new Partner();
    person.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_INDIVIDUAL);
    person.setIsContact(true);
    person.setFirstName("Мария");
    person.setName("Петрова Демова");
    person.setCompany(company);
    Address address = BgDemoCompanyStep.address("ул. „Демо“ 11", "1000", "София", "BG");
    Beans.get(PartnerService.class).addPartnerAddress(person, address, true, false, false);
    person.setMainAddress(address);
    person = JPA.save(person);
    BankDetails iban = new BankDetails();
    iban.setIban("BG05DEMO96611000000051");
    iban.setBank(BgDemoCompanyStep.bank("DEMOBGSF", "Демо Банк АД", "BG"));
    iban.setPartner(person);
    iban.setOwnerName("Мария Петрова Демова");
    iban.setActive(true);
    iban.setIsDefault(true);
    person.addBankDetailsListItem(JPA.save(iban));

    Employee e = new Employee();
    e.setName("Мария Петрова Демова");
    e.setContactPartner(person);
    e.setBgPersonalIdNumber("7501011016");
    e.setBgPersonalIdType("1");
    e.setBirthDate(LocalDate.of(1975, 1, 1));
    e.setBgBornAfter1959(true);
    e.setBgLaborCategory("3");
    e.setBgBenefitIban("BG05DEMO96611000000051");
    e.setBgPersonnelNumber("E01");
    e.setBgInsuranceServiceDeclared(true);
    e.setBgInsuranceServiceMonths(26 * 12);
    e.setBgInsuranceServiceAsOf(from.minusDays(1));
    e = JPA.save(e);

    EmploymentContract c = new EmploymentContract();
    c.setEmployee(e);
    c.setPayCompany(company);
    c.setStartDate(from.minusYears(5).withMonth(3));
    c.setBgPriorExperienceYears(20);
    c.setBgPriorExperienceMonths(0);
    c.setBgPriorExperienceDays(0);
    c.setBgInsuredTypeCode("01");
    c.setBgNkpd(JPA.all(BgNkpd.class).filter("self.code = ?1", "11207023").fetchOne());
    assertNotNull(c.getBgNkpd(), "код по НКПД 11207023 от сийда");
    c.setBgHoursPerDay(new BigDecimal("8.00"));
    c.setBgLegalHoursPerDay(new BigDecimal("8.00"));
    c.setBgDaysPerWeek(new BigDecimal("5.00"));
    c.setBgBaseSalary(new BigDecimal("3000.00"));
    return JPA.save(c).getId();
  }

  /**
   * Бутонът от формата — контекстът на записа (с версията и полетата от екрана) към контролера.
   * Грешка ({@code error}) спира теста; отказът на „Изплати“ идваше като info и се хваща по
   * статуса.
   */
  private static ActionResponse press(
      Long slipId, BgDemoForm.Action action, Map<String, Object> screen) throws Exception {
    ActionResponse response = pressRaw(slipId, action, screen);
    Object error = BgDemoForm.item(response).get("error");
    if (error != null) {
      throw new IllegalStateException(String.valueOf(error));
    }
    return response;
  }

  /** Същото, но отказът ({@code error}) се връща на теста, не го спира. */
  private static ActionResponse pressRaw(
      Long slipId, BgDemoForm.Action action, Map<String, Object> screen) throws Exception {
    Map<String, Object> context = new HashMap<>(screen);
    context.put("_model", BgPaySlip.class.getName());
    context.put("id", slipId);
    context.put("version", BgDemoTx.call(() -> JPA.find(BgPaySlip.class, slipId).getVersion()));
    ActionRequest request = new ActionRequest();
    request.setModel(BgPaySlip.class.getName());
    request.setData(Map.of("context", context));
    ActionResponse response = new ActionResponse();
    action.call(request, response);
    JPA.em().clear();
    return response;
  }
}
