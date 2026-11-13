package com.axelor.apps.bglocalization.service.setup;

import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.importFromTemplate;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.newCompany;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.packageLikeDataInit;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.tx;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountingReport;
import com.axelor.apps.account.db.AccountingReportType;
import com.axelor.apps.account.db.AccountingReportValue;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.custom.AccountingReportValueService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ЧИСТ ВНОС НА BG_COA → БАЛАНСЪТ И ОПР СЕ ИЗЧИСЛЯВАТ.
 *
 * <p>До поправката шаблонът не закачаше колона към нито една справка от вид 3000: шестте реда
 * „Стойност“ (`amt`/`bal`) бяха без отчет, и `AccountingReportValueServiceImpl
 * #checkAccountingReportType` спираше с REPORT_TYPE_NO_COLUMN. Тук пътят е истинският — зипът от
 * data-init и бутонът „Импорт на счетоводни конфигурации“ — а после справката се смята така, както
 * от екрана.
 *
 * <p>⚠️ ПЕЧАТЪТ (BIRT) НЕ СЕ ПУСКА ТУК: {@code AccountingReportType3000.rptdesign} чете стойностите
 * със SQL за PostgreSQL ({@code TO_CHAR}, {@code INTERVAL '1 year'}) и иска параметрите на базата
 * ({@code db.default.*}), а тестът е под HSQLDB. Спирачката беше преди BIRT — в {@code
 * checkAccountingReportType} (колоната) и в изчислението ({@code R_INC}/{@code R_EXP}) — и тя е
 * покрита.
 *
 * <p>⚠️ Заварените бази НЕ се поправят оттук (само шаблонът).
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgCoaReportColumnsIntegrationTest extends JpaSupport {

  /**
   * Форматът на датата на периода в стойностите на справката идва от локала: на дружеството, иначе
   * {@code application.locale} (CompanyDateService#checkLocale). Тестовете на модула нямат
   * axelor-config.properties — стойността е като в src/main/resources/axelor-config.properties.
   * Задава се преди първото четене — настройките се кешират за JVM.
   */
  @BeforeEach
  void applicationLocale() {
    com.axelor.app.AppSettings.get()
        .getInternalProperties()
        .putIfAbsent("application.locale", "en");
  }

  @AfterEach
  void logout() {
    com.axelor.apps.bglocalization.testsupport.BgRoleLogin.logout();
  }

  private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
  private static final LocalDate TO = LocalDate.of(2026, 12, 31);

  /** {@code array_to_string} на PostgreSQL за формулата {@code Move.ocrUploadIds} в HSQLDB. */
  @BeforeEach
  void hsqldbArrayToString() {
    JPA.runInTransaction(
        () -> {
          Number present =
              (Number)
                  JPA.em()
                      .createNativeQuery(
                          "SELECT COUNT(*) FROM INFORMATION_SCHEMA.ROUTINES"
                              + " WHERE ROUTINE_NAME = 'ARRAY_TO_STRING'")
                      .getSingleResult();
          if (present.intValue() == 0) {
            JPA.em()
                .createNativeQuery(
                    "CREATE FUNCTION array_to_string(a BIGINT ARRAY, d VARCHAR(16))"
                        + " RETURNS VARCHAR(16000) BEGIN ATOMIC"
                        + " DECLARE r VARCHAR(16000) DEFAULT NULL; DECLARE i INT DEFAULT 1;"
                        + " WHILE i <= CARDINALITY(a) DO"
                        + " SET r = CASE WHEN r IS NULL THEN CAST(a[i] AS VARCHAR(32))"
                        + " ELSE r || d || CAST(a[i] AS VARCHAR(32)) END; SET i = i + 1;"
                        + " END WHILE; RETURN r; END")
                .executeUpdate();
          }
        });
  }

  static AccountingReportType reportType(Company company, String id) {
    return JPA.all(AccountingReportType.class)
        .filter("self.importId = ?1", company.getCode() + "-" + id)
        .fetchOne();
  }

  private static Account account(Long companyId, String code) {
    return JPA.all(Account.class)
        .filter("self.company.id = ?1 AND self.code = ?2", companyId, code)
        .fetchOne();
  }

  /** Година, период и журнал за статиите — вносът на шаблона не прави години. */
  static Long[] books(Long companyId) {
    return tx(
        () -> {
          Company company = JPA.em().find(Company.class, companyId);
          Year year = new Year();
          year.setName("2026 " + company.getCode());
          year.setCode("FY26-" + company.getCode());
          year.setCompany(company);
          year.setTypeSelect(1);
          year.setFromDate(FROM);
          year.setToDate(TO);
          year.setStatusSelect(1);
          year = JPA.save(year);
          Period period = new Period();
          period.setName("2026 " + company.getCode());
          period.setYear(year);
          period.setFromDate(FROM);
          period.setToDate(TO);
          period.setStatusSelect(1);
          period = JPA.save(period);
          JournalType type = new JournalType();
          type.setName("Тест " + company.getCode());
          type.setCode("T-" + company.getCode());
          type.setTechnicalTypeSelect(5);
          type = JPA.save(type);
          Journal journal = new Journal();
          journal.setName("Тест " + company.getCode());
          journal.setCode("TJ-" + company.getCode());
          journal.setJournalType(type);
          journal.setCompany(company);
          journal.setStatusSelect(1);
          journal = JPA.save(journal);
          return new Long[] {period.getId(), journal.getId()};
        });
  }

  /** Осчетоводена статия: тройки „сметка, дебит, кредит“. */
  static void posted(Long companyId, Long[] books, String date, Object... lines) {
    JPA.runInTransaction(
        () -> {
          Company company = JPA.em().find(Company.class, companyId);
          Move move = new Move();
          move.setCompany(company);
          move.setPeriod(JPA.em().find(Period.class, books[0]));
          move.setJournal(JPA.em().find(Journal.class, books[1]));
          move.setDate(LocalDate.parse(date));
          move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
          move.setTechnicalOriginSelect(1);
          for (int i = 0; i < lines.length; i += 3) {
            Account account = account(companyId, (String) lines[i]);
            assertNotNull(account, "сметка " + lines[i] + " липсва след вноса");
            MoveLine line = new MoveLine();
            line.setAccount(account);
            line.setDebit(new BigDecimal(lines[i + 1].toString()));
            line.setCredit(new BigDecimal(lines[i + 2].toString()));
            line.setDate(move.getDate());
            line.setCounter(i / 3 + 1);
            move.addMoveLineListItem(line);
          }
          JPA.save(move);
        });
  }

  static Long report(Long companyId, String type) {
    return tx(
        () -> {
          Company company = JPA.em().find(Company.class, companyId);
          AccountingReport r = new AccountingReport();
          r.setReportType(reportType(company, type));
          r.setCompany(company);
          r.setCompanySet(new HashSet<>(List.of(company)));
          r.setDateFrom(FROM);
          r.setDateTo(TO);
          r.setDate(TO);
          r.setExportTypeSelect("pdf");
          return JPA.save(r).getId();
        });
  }

  /** Стойностите по код на ред — след изчислението, преди печатът да ги изчисти. */
  static Map<String, BigDecimal> compute(Long reportId) throws Exception {
    JPA.em().clear();
    AccountingReport r = JPA.em().find(AccountingReport.class, reportId);
    try {
      Beans.get(AccountingReportValueService.class).computeReportValues(r);
    } catch (Exception e) {
      // Аномалиите на справката са в TraceBack — без тях грешката казва само „timeout“.
      JPA.em().clear();
      List<Object[]> traces =
          JPA.em()
              .createQuery(
                  "SELECT t.message, t.trace FROM TraceBack t"
                      + " WHERE t.ref = 'com.axelor.apps.account.db.AccountingReport'"
                      + " AND t.refId = :id",
                  Object[].class)
              .setParameter("id", reportId)
              .setMaxResults(15)
              .getResultList();
      StringBuilder sb = new StringBuilder(e.getMessage());
      traces.forEach(t -> sb.append("\n  · ").append(t[0]));
      if (!traces.isEmpty() && traces.get(traces.size() - 1)[1] != null) {
        String trace = traces.get(traces.size() - 1)[1].toString();
        sb.append("\n").append(trace, 0, Math.min(2500, trace.length()));
      }
      throw new AssertionError(sb.toString(), e);
    }
    JPA.em().clear();
    Map<String, BigDecimal> out = new HashMap<>();
    for (AccountingReportValue v :
        JPA.all(AccountingReportValue.class)
            .filter("self.accountingReport.id = ?1", reportId)
            .fetch()) {
      if (v.getAccountingReportConfigLine() != null && v.getResult() != null) {
        out.merge(v.getAccountingReportConfigLine().getCode(), v.getResult(), BigDecimal::add);
      }
    }
    return out;
  }

  static void eq(String expected, Map<String, BigDecimal> values, String code) {
    BigDecimal actual = values.get(code);
    assertNotNull(actual, code + " няма стойност; има: " + values.keySet());
    assertEquals(0, new BigDecimal(expected).compareTo(actual), code + " = " + actual);
  }

  @Test
  @DisplayName(
      "Чист внос: баланс и ОПР (НСС и МСС) и собственият капитал имат колона и се изчисляват с числа")
  void чист_внос_баланс_и_опр_се_изчисляват() throws Exception {
    Long templateId = packageLikeDataInit();
    Long companyId = tx(() -> newCompany("RCOL").getId());
    importFromTemplate(companyId, templateId);

    JPA.em().clear();
    Company company = JPA.em().find(Company.class, companyId);
    for (String id : List.of("27", "28", "45", "46", "47")) {
      AccountingReportType type = reportType(company, id);
      assertNotNull(type, "справка " + id + " липсва след вноса");
      assertEquals(
          1, type.getAccountingReportConfigLineColumnList().size(), "справка " + id + ": колоните");
    }

    Long[] books = books(companyId);
    posted(companyId, books, "2026-02-01", "503", 1000, 0, "101", 0, 1000);
    posted(companyId, books, "2026-03-01", "411", 600, 0, "703", 0, 500, "4532", 0, 100);
    posted(companyId, books, "2026-04-01", "604", 200, 0, "421", 0, 200);

    // Справката се смята от екрана — с влязъл потребител и активно дружество (форматът на
    // датата на периода е по дружеството на потребителя: DateService#getDateFormat).
    com.axelor.apps.bglocalization.testsupport.BgRoleLogin.login(company, "accounting.write");

    // Балансът по НСС: актив 503 + 411 = 1600; пасив 101 + 4532 + 421 + резултат 300 = 1600.
    Map<String, BigDecimal> bs = compute(report(companyId, "28"));
    eq("1600", bs, "TOT_ASSETS");
    eq("1600", bs, "TOT_LIAB");
    eq("0", bs, "CTRL");
    // ОПР по НСС.
    Map<String, BigDecimal> pl = compute(report(companyId, "27"));
    eq("500", pl, "TOT_INC");
    eq("200", pl, "TOT_EXP");
    eq("300", pl, "NET");
    // МСС.
    Map<String, BigDecimal> ifrsBs = compute(report(companyId, "46"));
    eq("1600", ifrsBs, "MA_TOT");
    eq("1600", ifrsBs, "MP_TOT");
    Map<String, BigDecimal> ifrsPl = compute(report(companyId, "45"));
    eq("300", ifrsPl, "MO_NET");
    // Собственият капитал — смята се без аномалии (числата му са извън тази поправка).
    assertTrue(!compute(report(companyId, "47")).isEmpty(), "справка 47 без стойности");
  }
}
