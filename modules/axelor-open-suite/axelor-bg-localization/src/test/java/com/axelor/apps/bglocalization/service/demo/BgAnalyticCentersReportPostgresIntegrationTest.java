package com.axelor.apps.bglocalization.service.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.account.db.AccountingReport;
import com.axelor.apps.account.db.AccountingReportType;
import com.axelor.apps.account.db.repo.AccountingReportRepository;
import com.axelor.apps.account.db.repo.AnalyticMoveLineRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.AccountingReportPrintService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.db.JPA;
import com.axelor.dms.db.DMSFile;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.hibernate.Session;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * СПРАВКА ПО ЦЕНТРОВЕ НА ДЕМО ТЪРГОВИЯТА — числата за 01–10 на годината, през отчета на Axelor.
 *
 * <p>„Счетоводство › Справки › Справка по центрове“ отваря отчетите 2000/2001 на Axelor. Тестът
 * налива демо търговията в ново дружество с BG_COA, създава „Аналитична оборотна ведомост“ (вид
 * 2000 на дружеството, от шаблона) за 01.01–31.10 и пуска ИСТИНСКАТА заявка на образеца {@code
 * AccountingReportType2000.rptdesign} (прочетена от classpath-а, както я подава BIRT). Сверява:
 *
 * <ul>
 *   <li>двете оси и четирите центъра имат суми;
 *   <li>сборът по всяка ос = оборотът (дебит − кредит) по 60x/70x на редовете с аналитично
 *       разпределение по тази ос, в периода — същото и по център;
 *   <li>„Аналитична главна книга“ (2001) дава същия сбор, само по сметки 60x/70x;
 *   <li>„Печат“ на Axelor рендира PDF-а, а в него са центровете и сборът по оста.
 * </ul>
 *
 * <p>Пуска се като {@link BgDemoDataPostgresIntegrationTest}: срещу СВОЯ PostgreSQL ({@code
 * -Ddemo.db.url/username/password}); без адрес се пропуска.
 */
@EnabledIfSystemProperty(named = "demo.db.url", matches = ".+")
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgDemoPostgresModule.class)
@Tag("integration")
class BgAnalyticCentersReportPostgresIntegrationTest {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  @Test
  void centersReportEqualsTurnoverOf60x70x() throws Exception {
    assumeTrue(System.getProperty("demo.db.url") != null, "без -Ddemo.db.url (своя база)");
    BgDemoInstallation.ensure();
    String tag = Long.toString(System.currentTimeMillis() % 100000);
    Long companyId = BgDemoInstallation.newCompany("Центрове " + tag, "DC" + tag);
    BgDemoInstallation.loginAdmin(companyId);
    int year = LocalDate.now().getYear();
    Beans.get(BgDemoDataService.class).load(companyId, BgDemoDataset.TRADE, year, false);
    LocalDate from = LocalDate.of(year, 1, 1);
    LocalDate to = LocalDate.of(year, 10, 31);

    // Справката — както от менюто: видът 2000 на дружеството (BG_COA), период 01–10.
    Long balanceId =
        report(companyId, AccountingReportRepository.REPORT_ANALYTIC_BALANCE, from, to);
    Long ledgerId =
        report(companyId, AccountingReportRepository.REPORT_ANALYTIC_GENERAL_LEDGER, from, to);
    AccountingReport balance = JPA.find(AccountingReport.class, balanceId);
    assertTrue(
        balance.getRef() != null && balance.getRef().startsWith("ANAREP"),
        "номер от поредицата analyticReport на BG_COA: " + balance.getRef());

    // По ос › център: истинската заявка на образеца 2000.
    Map<String, BigDecimal> byCenter = new TreeMap<>();
    Map<String, BigDecimal> byAxis = new TreeMap<>();
    for (Map<String, Object> row : run(dataSet("AccountingReportType2000"), balanceId)) {
      String axis = (String) row.get("analytic_axis_code");
      String center = (String) row.get("analytic_account_code");
      BigDecimal amount = (BigDecimal) row.get("amount");
      System.out.println(
          "  2000: "
              + axis
              + " › "
              + center
              + " › "
              + row.get("account_type_name")
              + " = "
              + amount);
      byCenter.merge(center, amount, BigDecimal::add);
      byAxis.merge(axis, amount, BigDecimal::add);
    }
    String prefix = JPA.find(Company.class, companyId).getCode() + "-";
    assertEquals(
        List.of(prefix + "COST", prefix + "PROFIT"), new ArrayList<>(byAxis.keySet()), "оси");
    assertEquals(
        List.of(prefix + "ADM", prefix + "GDS", prefix + "SAL", prefix + "SRV"),
        new ArrayList<>(byCenter.keySet()),
        "центрове");
    byCenter.forEach((c, a) -> assertTrue(a.signum() != 0, "нулев център " + c));

    // Независимо от отчета: оборотът по 60x/70x на редовете с аналитично разпределение.
    Map<String, BigDecimal> turnoverByAxis = turnover(companyId, from, to, "analyticAxis");
    Map<String, BigDecimal> turnoverByCenter = turnover(companyId, from, to, "analyticAccount");
    System.out.println("Центрове: по ос " + byAxis + "; оборот 60x/70x " + turnoverByAxis);
    assertEquals(turnoverByAxis.keySet(), byAxis.keySet());
    for (String axis : byAxis.keySet()) {
      assertEquals(
          0,
          turnoverByAxis.get(axis).compareTo(byAxis.get(axis)),
          "ос " + axis + ": отчет " + byAxis.get(axis) + " ≠ оборот " + turnoverByAxis.get(axis));
    }
    assertEquals(turnoverByCenter.keySet(), byCenter.keySet());
    for (String center : byCenter.keySet()) {
      assertEquals(
          0, turnoverByCenter.get(center).compareTo(byCenter.get(center)), "център " + center);
    }
    // Двете оси разпределят едни и същи редове изцяло — сборовете им съвпадат.
    assertEquals(
        0, byAxis.get(prefix + "COST").compareTo(byAxis.get(prefix + "PROFIT")), "COST ≠ PROFIT");

    // Аналитичната главна книга (2001): същото по редове, само сметки 60x/70x.
    Map<String, BigDecimal> ledgerByAxis = new TreeMap<>();
    List<String> foreignAccounts = new ArrayList<>();
    for (Map<String, Object> row : run(dataSet("AccountingReportType2001"), ledgerId)) {
      String account = String.valueOf(row.get("account"));
      if (!(account.startsWith("60") || account.startsWith("70"))) {
        foreignAccounts.add(account);
      }
      ledgerByAxis.merge(
          (String) row.get("analytic_axis_code"), (BigDecimal) row.get("amount"), BigDecimal::add);
    }
    assertEquals(List.of(), foreignAccounts, "редове извън 60x/70x в главната книга");
    for (String axis : byAxis.keySet()) {
      assertEquals(0, byAxis.get(axis).compareTo(ledgerByAxis.get(axis)), "2001 по ос " + axis);
    }

    // „Печат“ на Axelor: BIRT рендира образеца в PDF, приложен към справката.
    String link = Beans.get(AccountingReportPrintService.class).print(balance);
    assertNotNull(link, "„Печат“ без файл");
    DMSFile pdf =
        JPA.all(DMSFile.class)
            .filter(
                "self.relatedId = ?1 AND self.relatedModel = ?2 AND self.metaFile IS NOT NULL",
                balanceId,
                AccountingReport.class.getName())
            .order("-id")
            .fetchOne();
    assertNotNull(pdf, "PDF-ът не е приложен към справката");
    Path file = MetaFiles.getPath(pdf.getMetaFile());
    String text;
    try (PDDocument doc = Loader.loadPDF(file.toFile())) {
      text = new PDFTextStripper().getText(doc);
    }
    System.out.println("PDF " + file + ":\n" + text);
    for (String name : new String[] {"Администрация", "Продажби", "Стоки", "Услуги"}) {
      assertTrue(text.contains(name), "PDF без центъра „" + name + "“");
    }
    String digits = text.replaceAll("[\\s\\u00a0\\u202f.,']", "");
    String total =
        byAxis
            .get(prefix + "COST")
            .abs()
            .setScale(2, RoundingMode.HALF_UP)
            .unscaledValue()
            .toString();
    assertTrue(digits.contains(total), "PDF без сбора по ос " + byAxis.get(prefix + "COST"));
  }

  /** Отчет от вида {@code typeSelect} на дружеството (BG_COA), записан — получава номер. */
  private static Long report(Long companyId, int typeSelect, LocalDate from, LocalDate to) {
    AtomicReference<Long> id = new AtomicReference<>();
    JPA.runInTransaction(
        () -> {
          AccountingReportType type =
              JPA.all(AccountingReportType.class)
                  .filter(
                      "self.typeSelect = ?1 AND self.reportExportTypeSelect = 1"
                          + " AND self.company.id = ?2",
                      typeSelect,
                      companyId)
                  .fetchOne();
          assertNotNull(type, "BG_COA не е създал вид " + typeSelect + " за дружеството");
          AccountingReport r = new AccountingReport();
          r.setCompany(JPA.find(Company.class, companyId));
          r.setReportType(type);
          r.setDateFrom(from);
          r.setDateTo(to);
          r.setDate(to);
          r.setExportTypeSelect("pdf");
          id.set(Beans.get(AccountingReportRepository.class).save(r).getId());
        });
    return id.get();
  }

  /** Заявката на набора {@code AnalyticMoveLineDS} от образеца на Axelor на classpath-а. */
  private static String dataSet(String design) throws Exception {
    String xml;
    try (InputStream in =
        BgAnalyticCentersReportPostgresIntegrationTest.class.getResourceAsStream(
            "/reports/" + design + ".rptdesign")) {
      assertNotNull(in, design + ".rptdesign липсва в classpath-а");
      xml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Matcher m =
        Pattern.compile(
                "name=\"AnalyticMoveLineDS\".*?<xml-property name=\"queryText\"><!\\[CDATA\\[(.*?)\\]\\]>",
                Pattern.DOTALL)
            .matcher(xml);
    assertTrue(m.find(), "наборът AnalyticMoveLineDS в " + design);
    String sql = m.group(1);
    assertEquals(1, sql.chars().filter(c -> c == '?').count(), "един параметър (id на отчета)");
    return sql;
  }

  private static List<Map<String, Object>> run(String sql, Long reportId) {
    return JPA.em()
        .unwrap(Session.class)
        .doReturningWork(
            c -> {
              List<Map<String, Object>> rows = new ArrayList<>();
              try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setLong(1, reportId);
                try (ResultSet rs = ps.executeQuery()) {
                  int n = rs.getMetaData().getColumnCount();
                  while (rs.next()) {
                    Map<String, Object> row = new TreeMap<>();
                    for (int i = 1; i <= n; i++) {
                      row.put(rs.getMetaData().getColumnLabel(i).toLowerCase(), rs.getObject(i));
                    }
                    rows.add(row);
                  }
                }
              }
              return rows;
            });
  }

  /**
   * Дебит − кредит на редовете по 60x/70x на осчетоводените статии в периода, които имат реален
   * аналитичен ред по оста/центъра ({@code by} = analyticAxis | analyticAccount). Без отчета.
   */
  private static Map<String, BigDecimal> turnover(
      Long companyId, LocalDate from, LocalDate to, String by) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT x.code, SUM(ml.debit - ml.credit) FROM MoveLine ml, "
                    + ("analyticAxis".equals(by) ? "AnalyticAxis" : "AnalyticAccount")
                    + " x WHERE ml.move.company.id = :c AND ml.move.statusSelect = :s"
                    + " AND (ml.account.code LIKE '60%' OR ml.account.code LIKE '70%')"
                    + " AND ml.date BETWEEN :f AND :t"
                    + " AND EXISTS (SELECT 1 FROM AnalyticMoveLine a WHERE a.moveLine = ml"
                    + " AND a."
                    + by
                    + " = x AND a.typeSelect = :real)"
                    + " GROUP BY x.code",
                Object[].class)
            .setParameter("c", companyId)
            .setParameter("s", MoveRepository.STATUS_ACCOUNTED)
            .setParameter("f", from)
            .setParameter("t", to)
            .setParameter("real", AnalyticMoveLineRepository.STATUS_REAL_ACCOUNTING)
            .getResultList();
    Map<String, BigDecimal> out = new TreeMap<>();
    rows.forEach(r -> out.put((String) r[0], (BigDecimal) r[1]));
    assertFalse(out.isEmpty(), "няма редове по 60x/70x с аналитично разпределение");
    return out;
  }
}
