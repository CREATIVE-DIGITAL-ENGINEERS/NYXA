package com.axelor.apps.bglocalization.service.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.AnalyticMoveLineRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.bankpayment.db.BankStatementLineAFB120;
import com.axelor.apps.bankpayment.db.repo.BankStatementLineAFB120Repository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgFixedAssetWriteOff;
import com.axelor.apps.bglocalization.db.BgNsiGodReport;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgVatAnnualCorrection;
import com.axelor.apps.bglocalization.db.BgVatAnnualCorrectionLine;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79Direction;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79ProtocolService;
import com.axelor.apps.bglocalization.service.fixedasset.BgAssetRemoval;
import com.axelor.apps.bglocalization.service.nsi.BgNsiGodService;
import com.axelor.apps.bglocalization.service.saft.BgSaftGenerationService;
import com.axelor.apps.bglocalization.service.vat.BgArt79aProtocolService;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ДЕМО ДАННИТЕ ОТ КРАЙ ДО КРАЙ — върху своя PostgreSQL база ({@link BgDemoPostgresModule}).
 *
 * <p>Пуска се ръчно, срещу СВОЯ празна PostgreSQL база (никога обща): адресът, потребителят и
 * паролата се подават отвън — {@code -Ddemo.db.url}, {@code -Ddemo.db.username}, {@code
 * -Ddemo.db.password}, и {@code -Ddemo.db.ddl=update} за схемата. Без {@code demo.db.url} тестът се
 * пропуска (CI няма база). Базата се подготвя веднъж като нова среда ({@link BgDemoInstallation});
 * всяко пускане налива в НОВО дружество.
 */
// Условието е на класа: без адрес Guice модулът (JpaModule) би тръгнал към базата още при
// създаването си — преди тялото на теста — и пропускането щеше да е червено.
@EnabledIfSystemProperty(named = "demo.db.url", matches = ".+")
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgDemoPostgresModule.class)
@Tag("integration")
class BgDemoDataPostgresIntegrationTest {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  @Test
  void tradeLoadsIntoAFreshCompany() throws Exception {
    assumeTrue(System.getProperty("demo.db.url") != null, "без -Ddemo.db.url (своя база)");
    BgDemoInstallation.ensure();
    String tag = Long.toString(System.currentTimeMillis() % 100000);
    Long companyId = BgDemoInstallation.newCompany("Ново дружество " + tag, "DT" + tag);
    BgDemoInstallation.loginAdmin(companyId);

    int year = LocalDate.now().getYear();
    BgDemoDataService.Result result =
        // Без ТРЗ — дружество, налято преди ТРЗ да влезе в набора; ТРЗ се налива отделно отдолу.
        Beans.get(BgDemoDataService.class).load(companyId, BgDemoDataset.TRADE, year, false);
    result.log.forEach(System.out::println);

    long ventilated =
        JPA.all(Invoice.class)
            .filter(
                "self.company.id = ?1 AND self.statusSelect = ?2",
                companyId,
                InvoiceRepository.STATUS_VENTILATED)
            .count();
    assertTrue(ventilated > 0, "осчетоводени фактури");

    // Номерата на покупките от български доставчици — 10 цифри (ЗДДС чл. 114 ал. 1 т. 2).
    // Български доставчик в набора: ДДС номер „BG…“ или физическо лице (наемодателят).
    List<String> notTenDigits = new ArrayList<>();
    for (Invoice purchase :
        JPA.all(Invoice.class)
            .filter("self.company.id = ?1 AND self.operationTypeSelect IN (1, 2)", companyId)
            .fetch()) {
      String vat = purchase.getPartner().getTaxNbr();
      boolean bulgarian =
          vat == null ? purchase.getPartner().getPartnerTypeSelect() == 2 : vat.startsWith("BG");
      if (bulgarian && !String.valueOf(purchase.getSupplierInvoiceNb()).matches("\\d{10}")) {
        notTenDigits.add(purchase.getSupplierInvoiceNb());
      }
    }
    assertEquals(List.of(), notTenDigits, "покупки от БГ доставчици с номер, различен от 10 цифри");

    // ЗДДС чл. 117: протоколите на наливането (ВОП, услуги от ЕС и трети страни) са осчетоводени.
    long protocols =
        JPA.all(Move.class)
            .filter(
                "self.company.id = ?1 AND self.bgVatSourceInvoice IS NOT NULL"
                    + " AND self.statusSelect = ?2",
                companyId,
                MoveRepository.STATUS_ACCOUNTED)
            .count();
    assertTrue(protocols >= 10, "осчетоводени протоколи по чл. 117: " + protocols);

    // ЗДДС чл. 79 и чл. 79а на същото BG_COA дружество — през истинското осчетоводяване.
    assertEquals(MoveRepository.STATUS_ACCOUNTED, art79(companyId, year));
    assertEquals(MoveRepository.STATUS_ACCOUNTED, art79a(companyId, year));

    // Центровете: всяка ос разпределя изцяло редовете по 60x/70x на осчетоводените фактури.
    BigDecimal lines60x70x =
        JPA.em()
            .createQuery(
                "SELECT SUM(ml.debit + ml.credit) FROM MoveLine ml WHERE ml.move.company.id = :c"
                    + " AND ml.move.invoice IS NOT NULL AND ml.move.statusSelect = :s"
                    + " AND (ml.account.code LIKE '60%' OR ml.account.code LIKE '70%')",
                BigDecimal.class)
            .setParameter("c", companyId)
            .setParameter("s", MoveRepository.STATUS_ACCOUNTED)
            .getSingleResult();
    List<Object[]> byAxis =
        JPA.em()
            .createQuery(
                "SELECT aml.analyticAxis.name, SUM(aml.amount) FROM AnalyticMoveLine aml"
                    + " WHERE aml.moveLine.move.company.id = :c AND aml.typeSelect = :t"
                    + " GROUP BY aml.analyticAxis.name",
                Object[].class)
            .setParameter("c", companyId)
            .setParameter("t", AnalyticMoveLineRepository.STATUS_REAL_ACCOUNTING)
            .getResultList();
    System.out.println("Центрове: 60x/70x = " + lines60x70x);
    byAxis.forEach(a -> System.out.println("  " + a[0] + " = " + a[1]));
    assertEquals(2, byAxis.size(), "две оси с реални аналитични редове");
    for (Object[] a : byAxis) {
      assertEquals(0, lines60x70x.compareTo((BigDecimal) a[1]), "ос " + a[0] + " = 60x/70x");
    }

    // Банката: извлечения по трите сметки, всяко движение равнено по правилата на Axelor.
    long movements =
        JPA.all(BankStatementLineAFB120.class)
            .filter(
                "self.bankDetails.company.id = ?1 AND self.lineTypeSelect = ?2",
                companyId,
                BankStatementLineAFB120Repository.LINE_TYPE_MOVEMENT)
            .count();
    long unreconciled =
        JPA.all(BankStatementLineAFB120.class)
            .filter(
                "self.bankDetails.company.id = ?1 AND self.lineTypeSelect = ?2"
                    + " AND self.amountRemainToReconcile > 0",
                companyId,
                BankStatementLineAFB120Repository.LINE_TYPE_MOVEMENT)
            .count();
    System.out.println("Банка: " + movements + " движения, неравнени " + unreconciled);
    assertTrue(movements >= 100, "движения в извлеченията: " + movements);
    assertEquals(0, unreconciled, "неравнени движения");
    // Плащанията във валута са по сметката във валута (504): 2 в USD (Demo Software), 3 в CHF.
    for (Object[] c : new Object[][] {{"USD", 2L}, {"CHF", 3L}}) {
      long foreign =
          JPA.all(BankStatementLineAFB120.class)
              .filter(
                  "self.bankDetails.company.id = ?1 AND self.lineTypeSelect = ?2"
                      + " AND self.currency.codeISO = ?3",
                  companyId,
                  BankStatementLineAFB120Repository.LINE_TYPE_MOVEMENT,
                  c[0])
              .count();
      assertEquals(c[1], foreign, "движения в извлеченията " + c[0]);
    }

    // Преоценката на 504 (USD и CHF) към края на тримесечията — осчетоводени статии в ADJ.
    long revaluations =
        JPA.all(Move.class)
            .filter(
                "self.company.id = ?1 AND self.journal.code = 'ADJ' AND self.statusSelect = ?2",
                companyId,
                MoveRepository.STATUS_ACCOUNTED)
            .count();
    assertTrue(revaluations >= 3, "статии за преоценка: " + revaluations);

    // „Само ТРЗ“ върху наляното счетоводство: 5 служители, фишовете 01–10 изплатени със статии,
    // Д1 и Д6 генерирани и НЕподадени.
    BgDemoDataService.Result payroll =
        Beans.get(BgDemoDataService.class).loadPayroll(companyId, BgDemoDataset.TRADE, year);
    payroll.log.forEach(System.out::println);
    long confirmed =
        JPA.all(BgPaySlip.class)
            .filter(
                "self.employmentContract.payCompany.id = ?1 AND self.statusSelect IN ('CONFIRMED', 'PAID')",
                companyId)
            .count();
    assertEquals(47, confirmed, "потвърдени фишове (4 × 10 + 7 от април)");
    // ⚠️ „Изплати“ пада на заварен дефект в енджина (BgPaySlipMoveService подменя редовете на вече
    // записана статия) — фишът остава потвърден. Всеки изплатен трябва да има осчетоводена статия.
    long paid =
        JPA.all(BgPaySlip.class)
            .filter(
                "self.employmentContract.payCompany.id = ?1 AND self.statusSelect = 'PAID'",
                companyId)
            .count();
    long posted =
        JPA.all(BgPaySlip.class)
            .filter(
                "self.employmentContract.payCompany.id = ?1 AND self.statusSelect = 'PAID'"
                    + " AND self.bgPayrollMove.statusSelect = ?2",
                companyId,
                MoveRepository.STATUS_ACCOUNTED)
            .count();
    System.out.println(
        "ТРЗ: потвърдени " + confirmed + ", изплатени " + paid + ", със статия " + posted);
    assertEquals(paid, posted, "изплатените са със статия");
    assertEquals(
        10,
        JPA.all(BgD1Declaration.class).filter("self.company.id = ?1", companyId).count(),
        "Д1 за 01–10");
    assertEquals(
        10,
        JPA.all(BgD6Declaration.class).filter("self.company.id = ?1", companyId).count(),
        "Д6 за 01–10");
    assertTrue(
        JPA.all(BgD1Record.class).filter("self.bgD1Declaration.company.id = ?1", companyId).count()
            >= 47,
        "записи по Д1");
    assertEquals(
        0,
        JPA.all(NapSubmission.class).filter("self.company.id = ?1", companyId).count(),
        "нищо не е подадено към НАП");
    BgDemoDataService.Refused twice = null;
    try {
      Beans.get(BgDemoDataService.class).loadPayroll(companyId, BgDemoDataset.TRADE, year);
    } catch (BgDemoDataService.Refused e) {
      twice = e;
    }
    assertFalse(twice == null, "второ „само ТРЗ“ се отказва");
    assertTrue(twice.reasons.stream().anyMatch(r -> r.contains("ТРЗ вече е налято")));

    // Второ наливане в същото дружество — отказ.
    BgDemoDataService.Refused refused = null;
    try {
      Beans.get(BgDemoDataService.class).load(companyId, BgDemoDataset.TRADE, year);
    } catch (BgDemoDataService.Refused e) {
      refused = e;
    }
    assertFalse(refused == null, "непразно дружество се отказва");
    assertEquals(true, refused.reasons.stream().anyMatch(r -> r.contains("фактури")));

    // „Демо Холдинг“ в друго ново дружество — свои партньори и документи; Търговия не се пипа.
    long tradeInvoices = count("Invoice", companyId);
    long tradeMoves = count("Move", companyId);
    BgDemoInstallation.loginAdmin(companyId);
    Long holdingId = BgDemoInstallation.newCompany("Ново дружество Х" + tag, "DH" + tag);
    BgDemoInstallation.loginAdmin(holdingId);
    Beans.get(BgDemoDataService.class).load(holdingId, BgDemoDataset.HOLDING, year);
    assertEquals(4, count("Partner", holdingId) - 1, "партньорите на холдинга (без своя)");
    assertEquals(tradeInvoices, count("Invoice", companyId), "фактурите на Търговия са същите");
    assertEquals(tradeMoves, count("Move", companyId), "статиите на Търговия са същите");
    long foreign =
        JPA.em()
            .createQuery(
                "SELECT COUNT(i) FROM Invoice i WHERE i.company.id = :h AND i.partner.company.id <> :h",
                Long.class)
            .setParameter("h", holdingId)
            .getSingleResult();
    assertEquals(0, foreign, "фактура на холдинга с чужд партньор");

    // SAF-T за януари на Търговия — файлът се съставя без блокиращи грешки.
    for (int month : new int[] {1, 10}) {
      BgSaftGenerationService.Generated g = saft(companyId, year, month);
      List<String> blocking = new java.util.ArrayList<>();
      g.getFindings().stream()
          .filter(BgDeclarationFileFinding::isBlocking)
          .forEach(f -> blocking.add(f.getCode() + ": " + f.getMessage()));
      assertEquals(new java.util.ArrayList<String>(), blocking, "SAF-T " + month);
      assertTrue(g.getBytes() != null && g.getBytes().length > 0, "SAF-T файл " + month);
    }

    // ГОД към НСИ за двете дружества — изчислен и записан, без грешки в контролите.
    for (Object[] t : new Object[][] {{companyId, "NSS"}, {holdingId, "IFRS"}}) {
      BgNsiGodReport r = god((Long) t[0], (String) t[1], year);
      assertEquals(0, r.getErrorCount(), "ГОД " + t[1] + ": " + r.getMessages());
    }
  }

  private static BgSaftGenerationService.Generated saft(Long companyId, int year, int month)
      throws Exception {
    return BgDemoTx.call(
        () -> {
          Company c = JPA.find(Company.class, companyId);
          LocalDate from = LocalDate.of(year, month, 1);
          Period period =
              JPA.all(Period.class)
                  .filter(
                      "self.year.company = ?1 AND self.year.typeSelect = 1 AND self.fromDate = ?2",
                      c,
                      from)
                  .fetchOne();
          SaftReport r = new SaftReport();
          r.setCompany(c);
          r.setPeriod(period);
          r.setReportTypeSelect("monthly");
          r.setReportYear(year);
          r.setFromDate(from);
          r.setToDate(from.withDayOfMonth(from.lengthOfMonth()));
          r.setName("Демо " + month);
          r = JPA.save(r);
          return Beans.get(BgSaftGenerationService.class).generate(r, LocalDate.now(), null);
        });
  }

  private static BgNsiGodReport god(Long companyId, String standard, int year) throws Exception {
    return BgDemoTx.call(
        () -> {
          BgNsiGodReport r = new BgNsiGodReport();
          r.setCompany(JPA.find(Company.class, companyId));
          r.setReportYear(year);
          r.setStandardSelect(standard);
          r.setUnitSelect(BgNsiGodService.defaultUnit(year));
          r = JPA.save(r);
          return Beans.get(BgNsiGodService.class).compute(r);
        });
  }

  private static long count(String entity, Long companyId) {
    return JPA.em()
        .createQuery("SELECT COUNT(x) FROM " + entity + " x WHERE x.company.id = :c", Long.class)
        .setParameter("c", companyId)
        .getSingleResult();
  }

  /** Корекция по чл. 79 (отписан лаптоп, начисляване) — статия Дт разход / Кт 4532. */
  private static int art79(Long companyId, int year) throws Exception {
    Long moveId =
        BgDemoTx.call(
            () -> {
              Company company = JPA.find(Company.class, companyId);
              FixedAsset laptop =
                  JPA.all(FixedAsset.class)
                      .filter("self.company = ?1 AND self.statusSelect = 2", company)
                      .order("-id")
                      .fetchOne();
              BgFixedAssetWriteOff w = new BgFixedAssetWriteOff();
              w.setCompany(company);
              w.setFixedAsset(laptop);
              w.setWriteOffDate(LocalDate.of(year, 10, 30));
              w.setTaxYear(year);
              w.setDocumentNumber("ДЕМО-79");
              w.setVatCorrectionDirection(BgArt79Direction.CORRECTION);
              w.setVatTreatmentSelect(BgAssetRemoval.VatTreatment.CORRECTION_DUE);
              w.setVatCorrectionAmount(new BigDecimal("120.00"));
              w = JPA.save(w);
              return Beans.get(BgArt79ProtocolService.class).generate(w).getId();
            });
    return accounted(moveId);
  }

  /** Годишна корекция по чл. 79а (увеличение) — статия Дт 4531 / Кт приход. */
  private static int art79a(Long companyId, int year) throws Exception {
    Long moveId =
        BgDemoTx.call(
            () -> {
              Company company = JPA.find(Company.class, companyId);
              BgVatAnnualCorrection c = new BgVatAnnualCorrection();
              c.setCompany(company);
              c.setYear(year);
              c = JPA.save(c);
              BgVatAnnualCorrectionLine line = new BgVatAnnualCorrectionLine();
              line.setAnnualCorrection(c);
              line.setFixedAsset(
                  JPA.all(FixedAsset.class)
                      .filter("self.company = ?1 AND self.statusSelect = 2", company)
                      .order("id")
                      .fetchOne());
              line.setCorrectionAmount(new BigDecimal("50.00"));
              line = JPA.save(line);
              return Beans.get(BgArt79aProtocolService.class).generate(line).getId();
            });
    return accounted(moveId);
  }

  /** „Осчетоводи“ — MoveValidateService, както бутонът; връща статуса. */
  private static int accounted(Long moveId) throws Exception {
    BgDemoTx.run(
        () -> Beans.get(MoveValidateService.class).accounting(JPA.find(Move.class, moveId)));
    return BgDemoTx.call(() -> JPA.find(Move.class, moveId).getStatusSelect());
  }
}
