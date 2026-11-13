package com.axelor.apps.bglocalization.service.nsi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.db.BgNsiGodLine;
import com.axelor.apps.bglocalization.db.BgNsiGodReport;
import com.axelor.apps.bglocalization.db.repo.BgNsiGodLineRepository;
import com.axelor.apps.bglocalization.db.repo.BgNsiGodReportRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgNsiGodController;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ГОД КЪМ НСИ ВЪРХУ БАЗА: ИЗМИСЛЕНО ДРУЖЕСТВО „ТЕСТ АЛФА“ И НЕГОВИТЕ СТАТИИ.
 *
 * <p>Статиите се записват направо като осчетоводени — тестът е за това КОИ статии влизат и как се
 * разпределят, не за осчетоводяването. Около тях: чернова, статия „да не се отчита“, закриваща
 * статия на същата година, закриване/откриване на предходната и чуждо дружество — нито една не бива
 * да промени числата.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgNsiGodIntegrationTest extends JpaSupport {

  private static final AtomicLong TAGS = new AtomicLong();
  private static final int POSTED = MoveRepository.STATUS_ACCOUNTED;

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

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

  // ─── данни ───

  private static final class Books {
    Long companyId;
    Long journalId;
    final Map<Integer, Long> periods = new HashMap<>();
    final Map<String, Long> accounts = new HashMap<>();
  }

  private static Books books(String name) {
    String tag = "N" + TAGS.incrementAndGet();
    Books b = new Books();
    JPA.runInTransaction(
        () -> {
          if (JPA.all(com.axelor.studio.db.AppBase.class).fetchOne() == null) {
            JPA.save(new com.axelor.studio.db.AppBase());
          }
          if (JPA.all(com.axelor.studio.db.AppAccount.class).fetchOne() == null) {
            JPA.save(new com.axelor.studio.db.AppAccount());
          }
          Currency currency = new Currency();
          currency.setName("Евро " + tag);
          currency.setCode("€");
          currency.setCodeISO("Q" + tag);
          currency = JPA.save(currency);
          Company company = new Company();
          company.setName(name + " " + tag);
          company.setCode("C-" + tag);
          company.setCurrency(currency);
          company = JPA.save(company);
          JournalType type = new JournalType();
          type.setName("Други " + tag);
          type.setCode("OTH-" + tag);
          type.setTechnicalTypeSelect(5);
          type = JPA.save(type);
          Journal journal = new Journal();
          journal.setName("Мемориален " + tag);
          journal.setCode("MEM-" + tag);
          journal.setJournalType(type);
          journal.setCompany(company);
          journal.setStatusSelect(1);
          b.journalId = JPA.save(journal).getId();
          for (int y = 2024; y <= 2026; y++) {
            Year year = new Year();
            year.setName(y + " " + tag);
            year.setCode("FY" + y + "-" + tag);
            year.setCompany(company);
            year.setTypeSelect(1);
            year.setFromDate(LocalDate.of(y, 1, 1));
            year.setToDate(LocalDate.of(y, 12, 31));
            year.setStatusSelect(1);
            year = JPA.save(year);
            Period period = new Period();
            period.setName(y + " " + tag);
            period.setYear(year);
            period.setFromDate(LocalDate.of(y, 1, 1));
            period.setToDate(LocalDate.of(y, 12, 31));
            period.setStatusSelect(1);
            b.periods.put(y, JPA.save(period).getId());
          }
          b.companyId = company.getId();
        });
    return b;
  }

  private static Account account(Books b, Company company, String code) {
    Long id = b.accounts.get(code);
    if (id != null) {
      return JPA.em().find(Account.class, id);
    }
    AccountType type = JPA.all(AccountType.class).filter("self.name = 'NSI-T'").fetchOne();
    if (type == null) {
      type = new AccountType();
      type.setName("NSI-T");
      type.setTechnicalTypeSelect("other");
      type = JPA.save(type);
    }
    Account a = new Account();
    a.setAccountType(type);
    a.setCode(code);
    a.setName(code);
    a.setCompany(company);
    a.setStatusSelect(1);
    a = JPA.save(a);
    b.accounts.put(code, a.getId());
    return a;
  }

  /** Статия: тройки „сметка, дебит, кредит“. */
  private static void move(
      Books b, String date, int status, int origin, boolean ignore, Object... lines) {
    JPA.runInTransaction(
        () -> {
          LocalDate d = LocalDate.parse(date);
          Company company = JPA.em().find(Company.class, b.companyId);
          Move move = new Move();
          move.setCompany(company);
          move.setJournal(JPA.em().find(Journal.class, b.journalId));
          move.setPeriod(JPA.em().find(Period.class, b.periods.get(d.getYear())));
          move.setDate(d);
          move.setStatusSelect(status);
          move.setFunctionalOriginSelect(origin);
          move.setIgnoreInAccountingOk(ignore);
          move.setTechnicalOriginSelect(1);
          for (int i = 0; i < lines.length; i += 3) {
            MoveLine line = new MoveLine();
            line.setAccount(account(b, company, (String) lines[i]));
            line.setDebit(new BigDecimal(lines[i + 1].toString()));
            line.setCredit(new BigDecimal(lines[i + 2].toString()));
            line.setDate(d);
            line.setCounter(i / 3 + 1);
            move.addMoveLineListItem(line);
          }
          JPA.save(move);
        });
  }

  private static void posted(Books b, String date, Object... lines) {
    move(b, date, POSTED, 0, false, lines);
  }

  /** Втори дневник на дружеството — „друга банкова сметка“, когато статията няма своя. */
  private static Long journal(Books b, String name) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          Journal first = JPA.em().find(Journal.class, b.journalId);
          Journal j = new Journal();
          j.setName(name + " " + first.getCode());
          j.setCode(name.substring(0, 2).toUpperCase() + "-" + first.getCode());
          j.setJournalType(first.getJournalType());
          j.setCompany(first.getCompany());
          j.setStatusSelect(1);
          id[0] = JPA.save(j).getId();
        });
    return id[0];
  }

  private static Long partner(Books b, String name) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          Company company = JPA.em().find(Company.class, b.companyId);
          com.axelor.apps.base.db.Partner p = new com.axelor.apps.base.db.Partner();
          p.setName(name);
          p.setIsCustomer(true);
          p.setPartnerTypeSelect(1);
          p.setCurrency(company.getCurrency());
          p.setCompany(company);
          id[0] = JPA.save(p).getId();
        });
    return id[0];
  }

  /** Осчетоводена статия в даден дневник: четворки „сметка, дебит, кредит, партньор или null“. */
  private static void postedIn(Books b, Long journalId, String date, Object... lines) {
    JPA.runInTransaction(
        () -> {
          LocalDate d = LocalDate.parse(date);
          Company company = JPA.em().find(Company.class, b.companyId);
          Move move = new Move();
          move.setCompany(company);
          move.setJournal(JPA.em().find(Journal.class, journalId));
          move.setPeriod(JPA.em().find(Period.class, b.periods.get(d.getYear())));
          move.setDate(d);
          move.setStatusSelect(POSTED);
          move.setTechnicalOriginSelect(1);
          for (int i = 0; i < lines.length; i += 4) {
            MoveLine line = new MoveLine();
            line.setAccount(account(b, company, (String) lines[i]));
            line.setDebit(new BigDecimal(lines[i + 1].toString()));
            line.setCredit(new BigDecimal(lines[i + 2].toString()));
            if (lines[i + 3] != null) {
              line.setPartner(
                  JPA.em().find(com.axelor.apps.base.db.Partner.class, (Long) lines[i + 3]));
            }
            line.setDate(d);
            line.setCounter(i / 4 + 1);
            move.addMoveLineListItem(line);
          }
          JPA.save(move);
        });
  }

  /** „Тест Алфа“: капитал от 2024, закриване 2024 / откриване 2025 и годината 2025. */
  private static Books alpha() {
    Books b = books("Тест Алфа ООД");
    posted(b, "2024-03-01", "503", 100000, 0, "101", 0, 100000);
    move(b, "2024-12-31", POSTED, 2, false, "9010", 100000, 0, "503", 0, 100000);
    move(b, "2024-12-31", POSTED, 2, false, "101", 100000, 0, "9010", 0, 100000);
    move(b, "2025-01-01", POSTED, 1, false, "503", 100000, 0, "9000", 0, 100000);
    move(b, "2025-01-01", POSTED, 1, false, "9000", 100000, 0, "101", 0, 100000);
    posted(b, "2025-02-10", "304", 40000, 0, "4531", 8000, 0, "401", 0, 48000);
    posted(b, "2025-03-15", "411", 60000, 0, "702", 0, 50000, "4532", 0, 10000);
    posted(b, "2025-03-15", "702", 30000, 0, "304", 0, 30000);
    posted(b, "2025-04-01", "702", 2000, 0, "4532", 400, 0, "411", 0, 2400);
    posted(b, "2025-05-31", "604", 20000, 0, "421", 0, 20000);
    posted(b, "2025-05-31", "605", 4000, 0, "455", 0, 4000);
    posted(b, "2025-06-01", "205", 60000, 0, "503", 0, 60000);
    posted(b, "2025-07-01", "503", 15000, 0, "703", 0, 15000);
    posted(b, "2025-08-01", "503", 30000, 0, "152", 0, 30000);
    posted(b, "2025-09-01", "503", 7000, 0, "412", 0, 7000);
    posted(b, "2025-10-14", "4532", 9600, 0, "4531", 0, 8000, "4539", 0, 1600);
    posted(b, "2025-12-31", "603", 5000, 0, "241", 0, 5000);
    posted(b, "2025-12-31", "123", 600, 0, "4521", 0, 600);
    // Закриването на 2025 — не влиза нито в баланса, нито в ОПР за 2025.
    move(b, "2025-12-31", POSTED, 2, false, "703", 15000, 0, "123", 0, 15000);
    // Чернова и „да не се отчита“ — не влизат.
    move(b, "2025-05-05", 1, 0, false, "503", 999000, 0, "709", 0, 999000);
    move(b, "2025-05-06", POSTED, 0, true, "503", 888000, 0, "709", 0, 888000);
    return b;
  }

  private static BgNsiGodReport report(Books b, int year, String unit) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgNsiGodReport r = new BgNsiGodReport();
          r.setCompany(JPA.em().find(Company.class, b.companyId));
          r.setReportYear(year);
          r.setUnitSelect(unit);
          r.setStandardSelect("NSS");
          r.setStatusSelect(BgNsiGodService.DRAFT);
          id[0] = Beans.get(BgNsiGodReportRepository.class).save(r).getId();
        });
    return fresh(id[0]);
  }

  private static BgNsiGodReport fresh(Long id) {
    JPA.em().clear();
    return Beans.get(BgNsiGodReportRepository.class).find(id);
  }

  private static BgNsiGodReport compute(BgNsiGodReport r) {
    Beans.get(BgNsiGodService.class).compute(r);
    BgNsiGodReport out = fresh(r.getId());
    // Точните суми са в мащаба на колоната (scale 2) — HSQLDB не го проверява, PostgreSQL — да.
    for (BgNsiGodLine l : out.getLineList()) {
      for (BigDecimal v : new BigDecimal[] {l.getAutoCurrent(), l.getAutoPrevious()}) {
        assertTrue(v == null || v.scale() <= 2, l.getCode() + ": " + v);
      }
    }
    return out;
  }

  private static BgNsiGodLine line(BgNsiGodReport r, String code) {
    return r.getLineList().stream().filter(l -> code.equals(l.getCode())).findFirst().get();
  }

  private static void value(BgNsiGodReport r, String code, long current, long previous) {
    BgNsiGodLine l = line(r, code);
    assertEquals(0, BigDecimal.valueOf(current).compareTo(l.getValueCurrent()), code + " текуща");
    assertEquals(
        0, BigDecimal.valueOf(previous).compareTo(l.getValuePrevious()), code + " предходна");
  }

  // ─── тестове ───

  @Test
  @DisplayName(
      "2025 в хил. лв.: баланс, ОПР, контроли; черновата, „да не се отчита“ и закриването не влизат")
  void година_2025() {
    Books a = alpha();
    Books other = books("Тест Бета ЕООД");
    posted(other, "2025-03-01", "503", 777000, 0, "101", 0, 777000);
    posted(other, "2025-03-02", "411", 555000, 0, "702", 0, 555000);

    BgNsiGodReport r = compute(report(a, 2025, BgNsiGodService.BGN_K));

    assertEquals(0, r.getErrorCount(), r.getMessages());
    // Баланс
    value(r, "03413", 92, 100);
    value(r, "03132", 10, 0);
    value(r, "03210", 58, 0);
    value(r, "02220", 60, 0);
    value(r, "02200АМ", -5, 0);
    value(r, "02200", 55, 0);
    value(r, "04500", 215, 100);
    value(r, "05120", 100, 100);
    value(r, "05600", 3, 0);
    value(r, "07202", 30, 0);
    value(r, "07301", 7, 0);
    value(r, "07401", 48, 0);
    value(r, "07801", 26, 0);
    value(r, "07831", 2, 0);
    value(r, "08500", 214, 100);
    // ОПР
    value(r, "15120", 48, 0);
    value(r, "15130", 15, 0);
    value(r, "10500", 30, 0);
    value(r, "10510", 30, 0);
    value(r, "10310", 20, 0);
    value(r, "10320", 4, 0);
    value(r, "10411", 5, 0);
    value(r, "13000", 59, 0);
    value(r, "18000", 63, 0);
    value(r, "14100", 4, 0);
    value(r, "14200", 1, 0);
    value(r, "14400", 3, 0);
    value(r, "14500", 63, 0);
    value(r, "19500", 63, 0);
    // Точната сума и откъде е
    assertEquals(0, new BigDecimal("57600").compareTo(line(r, "03210").getAutoCurrent()));
    assertTrue(line(r, "15120").getSourceAccounts().contains("702"));
    assertEquals("MANUAL", line(r, "06100").getMarkerSelect());
    // Контролите: закръглянето разделя актива (215) от пасива (214); служебният ред чака
    assertTrue(r.getMessages().contains("(04500)"), r.getMessages());
    assertTrue(r.getMessages().contains("амортизация"), r.getMessages());
    assertTrue(r.getPreviousSource().contains("Изчислена"), r.getPreviousSource());
  }

  @Test
  @DisplayName("Корекциите се пазят при ново изчисление и влизат в сборовете")
  void корекциите_се_пазят() {
    Books a = alpha();
    BgNsiGodReport r = compute(report(a, 2025, BgNsiGodService.BGN_K));
    JPA.runInTransaction(
        () -> {
          BgNsiGodReport s = fresh(r.getId());
          line(s, "02220").setCorrectionCurrent(new BigDecimal("-5"));
          line(s, "02200АМ").setCorrectionCurrent(new BigDecimal("5"));
          line(s, "03210").setCorrectionCurrent(new BigDecimal("-1"));
          line(s, "06100").setNote("няма");
          Beans.get(BgNsiGodReportRepository.class).save(s);
        });
    BgNsiGodReport again = compute(fresh(r.getId()));
    value(again, "02220", 55, 0);
    value(again, "02200АМ", 0, 0);
    value(again, "04500", 214, 100);
    assertEquals("няма", line(again, "06100").getNote());
    assertTrue(!again.getMessages().contains("(04500)"), again.getMessages());
  }

  @Test
  @DisplayName(
      "2026 в хил. евро: предходната година (2025, левове) се превалутира по фиксирания курс")
  void година_2026_в_евро() {
    Books a = alpha();
    BgNsiGodReport r = compute(report(a, 2026, BgNsiGodService.EUR_K));
    // 92 000 лв. / 1,95583 = 47 038,85 € → 47
    value(r, "03413", 47, 47);
    assertTrue(r.getPreviousSource().contains("1,95583"), r.getPreviousSource());
    assertTrue(r.getMessages().contains("образеца за 2025"), r.getMessages());
    assertEquals(2025, r.getFormVersion());
  }

  @Test
  @DisplayName("Сметка без ред → грешка, справката не се заключва")
  void грешка_спира_заключването() {
    Books a = books("Тест Гама АД");
    posted(a, "2025-03-01", "8000", 1000, 0, "101", 0, 1000);
    BgNsiGodReport r = compute(report(a, 2025, BgNsiGodService.BGN_K));
    assertTrue(r.getErrorCount() > 0);
    assertTrue(r.getMessages().contains("8000"), r.getMessages());
    assertThrows(
        IllegalStateException.class, () -> Beans.get(BgNsiGodService.class).lock(fresh(r.getId())));
  }

  @Test
  @DisplayName(
      "Заключена: не се изчислява, не се пише отвън; следващата година взима колоната от нея")
  void заключена() {
    Books a = alpha();
    BgNsiGodReport y2024 = compute(report(a, 2024, BgNsiGodService.BGN_K));
    assertEquals(0, y2024.getErrorCount(), y2024.getMessages());
    JPA.runInTransaction(
        () -> {
          BgNsiGodReport s = fresh(y2024.getId());
          line(s, "03413").setCorrectionCurrent(new BigDecimal("1"));
          Beans.get(BgNsiGodReportRepository.class).save(s);
        });
    compute(fresh(y2024.getId()));
    Beans.get(BgNsiGodService.class).lock(fresh(y2024.getId()));
    BgNsiGodReport locked = fresh(y2024.getId());
    assertEquals(BgNsiGodService.LOCKED, locked.getStatusSelect());

    assertThrows(
        IllegalStateException.class, () -> Beans.get(BgNsiGodService.class).compute(locked));
    assertThrows(
        PersistenceException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  BgNsiGodReport s = fresh(y2024.getId());
                  s.setIsbsNumber("X");
                  Beans.get(BgNsiGodReportRepository.class).save(s);
                }));
    assertThrows(
        PersistenceException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  BgNsiGodLine l = line(fresh(y2024.getId()), "03413");
                  l.setCorrectionCurrent(BigDecimal.TEN);
                  Beans.get(BgNsiGodLineRepository.class).save(l);
                }));

    // 2025 взима предходната колона от заключената — с корекцията ѝ (100 + 1).
    BgNsiGodReport y2025 = compute(report(a, 2025, BgNsiGodService.BGN_K));
    value(y2025, "03413", 92, 101);
    assertTrue(y2025.getPreviousSource().contains("заключената"), y2025.getPreviousSource());

    Beans.get(BgNsiGodService.class).unlock(fresh(y2024.getId()));
    assertEquals(BgNsiGodService.DRAFT, fresh(y2024.getId()).getStatusSelect());
  }

  @Test
  @DisplayName("Състоянието не се сменя отвън — „заключи“ без проверката за грешки не минава")
  void състоянието_не_се_сменя_отвън() {
    Books a = alpha();
    BgNsiGodReport r = compute(report(a, 2025, BgNsiGodService.BGN_K));
    assertThrows(
        PersistenceException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  BgNsiGodReport s = fresh(r.getId());
                  s.setStatusSelect(BgNsiGodService.LOCKED);
                  Beans.get(BgNsiGodReportRepository.class).save(s);
                }));
  }

  @Test
  @DisplayName(
      "Двойна природа: два партньора с противоположни салда по 411; банка с кредитно салдо")
  void двойна_природа_по_партньор_и_по_банка() {
    Books b = books("Тест Епсилон ООД");
    Long bankA = b.journalId;
    Long bankB = journal(b, "Банка Б");
    Long clientA = partner(b, "Клиент А");
    Long clientB = partner(b, "Клиент Б");
    postedIn(b, bankA, "2025-01-10", "503", 20000, 0, null, "101", 0, 20000, null);
    postedIn(b, bankA, "2025-02-10", "411", 10000, 0, clientA, "703", 0, 10000, null);
    // Клиент Б плаща авансово: 411 с кредитно салдо = получен аванс, не по-малко вземане от А.
    postedIn(b, bankA, "2025-03-10", "503", 3000, 0, null, "411", 0, 3000, clientB);
    // Плащане от банка Б на овърдрафт: кредитно салдо по 503 = задължение към банка.
    postedIn(b, bankB, "2025-04-10", "602", 8000, 0, null, "503", 0, 8000, null);

    BgNsiGodReport r = compute(report(b, 2025, BgNsiGodService.BGN_K));

    assertEquals(0, r.getErrorCount(), r.getMessages());
    value(r, "03210", 10, 0); // вземането от А, без нетиране с аванса на Б (нето би било 7)
    value(r, "07301", 3, 0); // авансът от Б
    value(r, "03413", 23, 0); // банка А (нето по 503 би било 15)
    value(r, "07201", 8, 0); // овърдрафтът на банка Б
    value(r, "04500", 33, 0);
    value(r, "08500", 33, 0);
  }

  @Test
  @DisplayName(
      "Насрещни сметки: 14200 — данъкът за годината и 494; 15300 — 613 срещу 60x/61x; 705 нето")
  void по_насрещна_сметка() {
    Books b = books("Тест Зета ООД");
    posted(b, "2025-01-10", "503", 100000, 0, "101", 0, 100000);
    // Данъкът за годината и отсроченият — срещу 123; корекцията за минали години — срещу 122.
    posted(b, "2025-12-31", "123", 7000, 0, "4521", 0, 7000);
    posted(b, "2025-12-31", "122", 2000, 0, "4521", 0, 2000);
    posted(b, "2025-12-31", "123", 1000, 0, "494", 0, 1000);
    // Придобиване по стопански начин: разходите прехвърлени в 613 — да; доставчикът — не.
    posted(b, "2025-06-01", "601", 4000, 0, "503", 0, 4000);
    posted(b, "2025-06-30", "613", 4000, 0, "601", 0, 4000);
    posted(b, "2025-06-30", "613", 3000, 0, "401", 0, 3000);
    posted(b, "2025-07-01", "205", 7000, 0, "613", 0, 7000);
    // Продажба на ДМА: приходът по 705, сторно на част от него — Дт 705.
    posted(b, "2025-08-01", "411", 5000, 0, "705", 0, 5000);
    posted(b, "2025-08-15", "705", 1000, 0, "411", 0, 1000);

    BgNsiGodReport r = compute(report(b, 2025, BgNsiGodService.BGN_K));

    assertEquals(0, r.getErrorCount(), r.getMessages());
    value(r, "14200", 8, 0); // 7000 + 1000, без 2000 срещу 122 (Кт оборот по 4521 би дал 9)
    value(r, "15300", 4, 0); // 4000 срещу 601, без 3000 от доставчика (Кт оборот би дал 7)
    value(r, "15430", 4, 0); // 5000 − 1000
    value(r, "10510", 0, 0); // 705 не е балансова стойност
  }

  // ─── роли ───

  private static ActionResponse call(Long id, String method) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", id);
    context.put("_model", BgNsiGodReport.class.getName());
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setData(data);
    request.setModel(BgNsiGodReport.class.getName());
    ActionResponse response = new ActionResponse();
    BgNsiGodController controller = Beans.get(BgNsiGodController.class);
    if ("compute".equals(method)) {
      controller.compute(request, response);
    } else {
      controller.lock(request, response);
    }
    JPA.em().clear();
    return response;
  }

  @Test
  @DisplayName("Роли: accounting.read не изчислява; accounting.write — да; чуждо дружество — не")
  void роли() {
    Books a = alpha();
    Books b = books("Тест Делта ООД");
    BgNsiGodReport r = report(a, 2025, BgNsiGodService.BGN_K);
    BgNsiGodReport foreign = report(b, 2025, BgNsiGodService.BGN_K);
    Company alphaCompany = JPA.em().find(Company.class, a.companyId);

    BgRoleLogin.login(alphaCompany, "accounting.read");
    call(r.getId(), "compute");
    BgRoleLogin.logout();
    assertNull(fresh(r.getId()).getComputedOn(), "accounting.read изчисли справката");

    BgRoleLogin.login(alphaCompany, "accounting.write");
    call(foreign.getId(), "compute");
    assertNull(fresh(foreign.getId()).getComputedOn(), "изчислена справка на чуждо дружество");
    call(r.getId(), "compute");
    BgRoleLogin.logout();
    assertNotNull(fresh(r.getId()).getComputedOn(), "accounting.write не изчисли справката");
  }
}
