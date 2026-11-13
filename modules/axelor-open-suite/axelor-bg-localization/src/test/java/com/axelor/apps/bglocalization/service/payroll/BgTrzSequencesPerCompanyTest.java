package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.repo.BgCompensationRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.testsupport.BgTestApp;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.data.Listener;
import com.axelor.data.csv.CSVImporter;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * БРОЯЧИТЕ В ТРЗ — ПО ДРУЖЕСТВО, ПО ИСТИНСКИЯ ПЪТ: дружеството се създава през хранилището му
 * (закачката за нова фирма), фишът и обезщетението — през своите (номерът, дружеството, пазачите),
 * с вписан администратор {@code all.write} в дружеството на документа.
 *
 * <p>Мутациите, които трябва да хванат, са в шева {@link BgTrzSequenceService#sequenceFor}: „обща
 * редица“ ({@code getSequence(код, null)}) обърква броенето на А и Б; „тихо връщане към общата“
 * дава номер на дружество без редица вместо отказ — затова отказът тук се мери при НАЛИЧНА обща
 * редица, точно каквато заварените бази имат от {@code base_sequence.csv}.
 *
 * <p>⚠️ {@code @Tag("integration")}: пуска се през {@code integrationTest} (HSQLDB, схемата от
 * домейна — тоест и ограничението (company, reference)).
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgTrzSequencesPerCompanyTest extends JpaSupport {

  private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private static String tag() {
    return String.valueOf(System.nanoTime());
  }

  /** Нова фирма — ПРЕЗ ХРАНИЛИЩЕТО (формата, REST). */
  private static Company newCompany(String name) {
    return tx(
        () -> {
          BgTestApp.ensureAppBase();
          String tag = tag();
          Currency currency = new Currency();
          currency.setName("Евро " + tag);
          currency.setCode("€");
          currency.setCodeISO("T" + tag);
          currency = JPA.save(currency);
          Company company = new Company();
          company.setName(name + " " + tag);
          company.setCode(name.substring(0, 1) + tag);
          company.setCurrency(currency);
          return Beans.get(CompanyRepository.class).save(company);
        });
  }

  /**
   * Заварена фирма отпреди броячите по дружество и преди SQL-а — БЕЗ ТРЗ редици. Днес всяка нова
   * фирма ги получава при раждането си, затова състоянието се прави, като се махнат.
   */
  private static Company legacyCompany(String name) {
    return tx(
        () -> {
          String tag = tag();
          Company company = new Company();
          company.setName(name + " " + tag);
          company.setCode("L" + tag);
          company = JPA.save(company);
          JPA.em().flush();
          JPA.em()
              .createQuery(
                  "DELETE FROM Sequence s WHERE s.company = :c AND s.codeSelect IN (:codes)")
              .setParameter("c", company)
              .setParameter("codes", BgTrzSequences.CODES)
              .executeUpdate();
          return company;
        });
  }

  private static EmploymentContract contract(Company company) {
    return tx(
        () -> {
          EmploymentContract contract = new EmploymentContract();
          contract.setPayCompany(JPA.find(Company.class, company.getId()));
          return JPA.save(contract);
        });
  }

  private static Period march() {
    return tx(
        () -> {
          String tag = tag();
          Year year = new Year();
          year.setName("Заплатна 2026 " + tag);
          year.setCode("PY-" + tag);
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setTypeSelect(2);
          year = JPA.save(year);
          Period period = new Period();
          period.setName("03/2026 " + tag);
          period.setYear(year);
          period.setFromDate(MARCH);
          period.setToDate(LocalDate.of(2026, 3, 31));
          period.setStatusSelect(PeriodRepository.STATUS_OPENED);
          return JPA.save(period);
        });
  }

  /** Нов фиш през истинското хранилище, от администратор в дружеството на договора. */
  private static BgPaySlip slip(EmploymentContract contract, Period period) {
    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(tx(() -> JPA.find(Company.class, contract.getPayCompany().getId())));
    return tx(
        () -> {
          BgPaySlip slip = new BgPaySlip();
          slip.setStatusSelect(BgPaySlipResolver.DRAFT);
          slip.setEmploymentContract(JPA.find(EmploymentContract.class, contract.getId()));
          slip.setPeriod(JPA.find(Period.class, period.getId()));
          return Beans.get(BgPaySlipRepository.class).save(slip);
        });
  }

  private static BgCompensationType compensationType() {
    return tx(
        () -> {
          String tag = tag();
          BgCompensationType type = new BgCompensationType();
          type.setCode("comp_" + tag);
          type.setName("Обезщетение " + tag);
          type.setLegalBasis("чл. 222 ал. 1 КТ");
          type.setBgDirection("EMPLOYER_TO_EMPLOYEE");
          type.setBgBaseVariesBy("NONE");
          return JPA.save(type);
        });
  }

  /** Нов документ-обезщетение през истинското хранилище. */
  private static BgCompensation compensation(EmploymentContract contract, BgCompensationType type) {
    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(tx(() -> JPA.find(Company.class, contract.getPayCompany().getId())));
    return tx(
        () -> {
          BgCompensation doc = new BgCompensation();
          doc.setEmploymentContract(JPA.find(EmploymentContract.class, contract.getId()));
          doc.setBgCompensationType(JPA.find(BgCompensationType.class, type.getId()));
          doc.setBgEventDate(LocalDate.of(2026, 9, 30));
          return Beans.get(BgCompensationRepository.class).save(doc);
        });
  }

  /**
   * Общата редица БЕЗ дружество — каквато заварените бази носят от {@code base_sequence.csv}. Тук
   * е, за да има към какво да се „върне тихо“ мутацията; кодът не бива да я докосва.
   */
  private static void sharedSequenceExists(String code) {
    tx(
        () -> {
          long shared =
              JPA.all(Sequence.class)
                  .filter("self.codeSelect = ?1 AND self.company IS NULL", code)
                  .count();
          if (shared == 0) {
            Sequence sequence = BgTrzSequences.newFor(null, code);
            sequence.setName("Обща (заварена) " + code);
            JPA.save(sequence);
          }
          return null;
        });
  }

  private static List<Sequence> sequencesOf(Company company) {
    return tx(
        () ->
            JPA.all(Sequence.class)
                .filter(
                    "self.company = ?1 AND self.codeSelect IN (?2, ?3)",
                    company,
                    BgTrzSequences.PAY_SLIP,
                    BgTrzSequences.COMPENSATION)
                .order("codeSelect")
                .fetch());
  }

  // ── 1 · А и Б броят поотделно ─────────────────────────────────────────────

  @Test
  @DisplayName(
      "фишове в А и Б, вписани на смени → всяко дружество брои от 1, дружеството е записано")
  void eachCompanyCountsItsOwnPaySlips() {
    sharedSequenceExists(BgTrzSequences.PAY_SLIP);
    Company a = newCompany("Алфа");
    Company b = newCompany("Бета");
    EmploymentContract inA = contract(a);
    EmploymentContract inB = contract(b);
    Period period = march();

    // Един редовен фиш за договор и период — вторият фиш на дружеството е на друг договор.
    BgPaySlip a1 = slip(inA, period);
    BgPaySlip b1 = slip(inB, period);
    BgPaySlip a2 = slip(contract(a), period);
    BgPaySlip b2 = slip(contract(b), period);

    assertEquals("FISH-2026-0001", a1.getReference(), "първият в А");
    assertEquals(
        "FISH-2026-0001", b1.getReference(), "първият в Б — свой брояч, не продължение на А");
    assertEquals("FISH-2026-0002", a2.getReference(), "вторият в А не вижда Б");
    assertEquals("FISH-2026-0002", b2.getReference(), "вторият в Б не вижда А");
    assertEquals(a.getId(), a1.getCompany().getId());
    assertEquals(b.getId(), b1.getCompany().getId());
  }

  @Test
  @DisplayName("обезщетения в А и Б → всяко дружество брои от 1")
  void eachCompanyCountsItsOwnCompensations() {
    sharedSequenceExists(BgTrzSequences.COMPENSATION);
    Company a = newCompany("Алфа");
    Company b = newCompany("Бета");
    BgCompensationType type = compensationType();
    EmploymentContract inA = contract(a);
    EmploymentContract inB = contract(b);

    BgCompensation a1 = compensation(inA, type);
    BgCompensation b1 = compensation(inB, type);
    BgCompensation a2 = compensation(inA, type);

    assertEquals("OBEZ-2026-0001", a1.getReference());
    assertEquals("OBEZ-2026-0001", b1.getReference());
    assertEquals("OBEZ-2026-0002", a2.getReference());
    assertEquals(b.getId(), b1.getCompany().getId());
  }

  // ── 2 · Дружество без редица → отказ, не обща ────────────────────────────

  @Test
  @DisplayName(
      "дружество без редица → отказ, който назовава дружеството и вида; общата не се ползва")
  void aCompanyWithoutItsSequenceIsRefusedByName() {
    sharedSequenceExists(BgTrzSequences.PAY_SLIP);
    sharedSequenceExists(BgTrzSequences.COMPENSATION);
    Company legacy = legacyCompany("Заварена");
    EmploymentContract contract = contract(legacy);
    Period period = march();

    PersistenceException slipRefusal =
        assertThrows(PersistenceException.class, () -> slip(contract, period));
    String text = slipRefusal.getMessage();
    assertTrue(text.contains(legacy.getName()), "назовава дружеството: " + text);
    assertTrue(text.contains("Фиш за заплата"), "назовава вида документ: " + text);
    assertTrue(text.contains("bgPaySlip"), text);

    PersistenceException compRefusal =
        assertThrows(PersistenceException.class, () -> compensation(contract, compensationType()));
    assertTrue(compRefusal.getMessage().contains(legacy.getName()), compRefusal.getMessage());
    assertTrue(
        compRefusal.getMessage().contains("Обезщетение при прекратяване"),
        compRefusal.getMessage());

    long written =
        tx(
            () ->
                JPA.all(BgPaySlip.class)
                        .filter("self.employmentContract.id = ?1", contract.getId())
                        .count()
                    + JPA.all(BgCompensation.class)
                        .filter("self.employmentContract.id = ?1", contract.getId())
                        .count());
    assertEquals(0, written, "нищо не е записано");
  }

  // ── 3 · Ново дружество → редиците се създават сами ────────────────────────

  @Test
  @DisplayName("ново дружество → двете ТРЗ редици са негови, годишни, FISH-/OBEZ-%YYYY-, 4 цифри")
  void aNewCompanyGetsItsSequences() {
    Company company = newCompany("Нова");
    List<Sequence> sequences = sequencesOf(company);
    assertEquals(2, sequences.size(), "по една за фиш и обезщетение: " + sequences);
    Sequence comp = sequences.get(0);
    Sequence slip = sequences.get(1);
    assertEquals(BgTrzSequences.COMPENSATION, comp.getCodeSelect());
    assertEquals("OBEZ-%YYYY-", comp.getPrefixe());
    assertEquals(BgTrzSequences.PAY_SLIP, slip.getCodeSelect());
    assertEquals("FISH-%YYYY-", slip.getPrefixe());
    for (Sequence s : sequences) {
      assertEquals(4, s.getPadding());
      assertEquals(1, s.getToBeAdded());
      assertTrue(s.getYearlyResetOk(), "годишно нулиране");
      assertTrue(s.getName().contains(company.getName()), s.getName());
    }
    // Повторен запис на заредената фирма (Axelor прави persist при всеки JPA.save) не ражда нищо.
    tx(
        () -> {
          Company loaded = JPA.find(Company.class, company.getId());
          loaded.setName(loaded.getName() + " (2)");
          return Beans.get(CompanyRepository.class).save(loaded);
        });
    tx(() -> JPA.save(JPA.find(Company.class, company.getId())));
    assertEquals(2, sequencesOf(company).size(), "второ записване не създава нищо");
  }

  /** Нова фирма с име и код, без хранилището. */
  private static Company bare(String name) {
    String tag = tag();
    Company company = new Company();
    company.setName(name + " " + tag);
    company.setCode(name.substring(0, 1) + tag);
    return company;
  }

  private static void assertHasBoth(Company company, String path) {
    List<Sequence> sequences = sequencesOf(company);
    assertEquals(
        2,
        sequences.size(),
        path + ": двете ТРЗ редици на дружеството (без хранилището): " + sequences);
    assertEquals(BgTrzSequences.COMPENSATION, sequences.get(0).getCodeSelect());
    assertEquals(BgTrzSequences.PAY_SLIP, sequences.get(1).getCodeSelect());
  }

  @Test
  @DisplayName("ново дружество през em.persist / JPA.save (код, фикстура) → редиците са там")
  void aCompanyPersistedWithoutTheRepositoryGetsItsSequences() {
    Company viaEm =
        tx(
            () -> {
              Company c = bare("Персист");
              JPA.em().persist(c);
              return c;
            });
    assertHasBoth(viaEm, "em.persist");
    Company viaSave = tx(() -> JPA.save(bare("Сейв")));
    assertHasBoth(viaSave, "JPA.save");
  }

  @Test
  @DisplayName("ново дружество през merge → редиците са на записаното копие")
  void aMergedNewCompanyGetsItsSequences() {
    Company merged = tx(() -> JPA.em().merge(bare("Мърдж")));
    assertNotNull(merged.getId());
    assertHasBoth(merged, "merge");
  }

  @Test
  @DisplayName("ново дружество, вложено в договор и записано с каскадата при flush → редиците")
  void aCompanyCascadedOnFlushGetsItsSequences() {
    EmploymentContract managed = tx(() -> JPA.save(new EmploymentContract()));
    Long companyId =
        tx(
            () -> {
              EmploymentContract contract = JPA.find(EmploymentContract.class, managed.getId());
              contract.setPayCompany(bare("Каскада"));
              JPA.em().flush();
              return contract.getPayCompany().getId();
            });
    assertNotNull(companyId, "фирмата е записана по каскадата");
    assertHasBoth(tx(() -> JPA.find(Company.class, companyId)), "каскада при flush");
  }

  @Test
  @DisplayName(
      "ново дружество от вноса на Axelor (CSVImporter — data-init, App Install) → редиците")
  void anImportedCompanyGetsItsSequences() throws Exception {
    String code = "CSV" + tag();
    Path dir = Files.createTempDirectory("bg-trz-company-import");
    Files.write(
        dir.resolve("base_company.csv"),
        ("importId;code;name\n" + code + ";" + code + ";Внос " + code + "\n")
            .getBytes(StandardCharsets.UTF_8));
    Path config = dir.resolve("config.xml");
    Files.write(
        config,
        ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<csv-inputs xmlns=\"http://axelor.com/xml/ns/data-import\""
                + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
                + " xsi:schemaLocation=\"http://axelor.com/xml/ns/data-import"
                + " http://axelor.com/xml/ns/data-import/data-import_7.4.xsd\">\n"
                + "  <input file=\"base_company.csv\" separator=\";\""
                + " type=\"com.axelor.apps.base.db.Company\" search=\"self.importId = :importId\"/>\n"
                + "</csv-inputs>\n")
            .getBytes(StandardCharsets.UTF_8));
    List<String> errors = new ArrayList<>();
    CSVImporter importer = new CSVImporter(config.toString(), dir.toString());
    importer.addListener(
        new Listener() {
          @Override
          public void imported(Model bean) {}

          @Override
          public void imported(Integer total, Integer success) {}

          @Override
          public void handle(Model bean, Exception e) {
            errors.add(bean + ": " + e);
          }
        });
    importer.run();
    assertEquals(new ArrayList<String>(), errors, "вносът минава");
    Company imported = tx(() -> JPA.all(Company.class).filter("self.code = ?1", code).fetchOne());
    assertNotNull(imported, "фирмата е внесена");
    assertHasBoth(imported, "CSVImporter");
  }

  // ── 4 · Заварените номера не се пипат ─────────────────────────────────────

  @Test
  @DisplayName(
      "заварен фиш (вкл. FISH-RES) → номерът остава, дружеството се записва; новите не го пипат")
  void legacyNumbersStayUnchanged() {
    Company a = newCompany("Алфа");
    EmploymentContract inA = contract(a);
    // Един редовен фиш за договор и период — затова заварените и новият са на различни договори.
    EmploymentContract inA2 = contract(a);
    Period period = march();
    Long[] ids =
        tx(
            () -> {
              BgPaySlip old = new BgPaySlip();
              old.setReference("FISH-2026-0042");
              old.setStatusSelect(BgPaySlipResolver.DRAFT);
              old.setEmploymentContract(JPA.find(EmploymentContract.class, inA.getId()));
              old.setPeriod(JPA.find(Period.class, period.getId()));
              JPA.em().persist(old);
              BgPaySlip res = new BgPaySlip();
              res.setReference("FISH-RES-2026-0003");
              res.setStatusSelect(BgPaySlipResolver.DRAFT);
              res.setEmploymentContract(JPA.find(EmploymentContract.class, inA2.getId()));
              res.setPeriod(JPA.find(Period.class, period.getId()));
              JPA.em().persist(res);
              return new Long[] {old.getId(), res.getId()};
            });
    BgRoleLogin.loginWrite(tx(() -> JPA.find(Company.class, a.getId())));
    for (Long id : ids) {
      tx(() -> Beans.get(BgPaySlipRepository.class).save(JPA.find(BgPaySlip.class, id)));
    }
    slip(contract(a), period);

    BgPaySlip old = tx(() -> JPA.find(BgPaySlip.class, ids[0]));
    BgPaySlip res = tx(() -> JPA.find(BgPaySlip.class, ids[1]));
    assertEquals("FISH-2026-0042", old.getReference());
    assertEquals("FISH-RES-2026-0003", res.getReference());
    assertNotNull(old.getCompany(), "заварен фиш получава дружеството си при следващия запис");
    assertEquals(a.getId(), old.getCompany().getId());
  }

  @Test
  @DisplayName("номериран в А фиш, преместен към договор в Б → отказ; номерът не мени дружество")
  void aNumberedSlipDoesNotMoveToAnotherCompany() {
    Company a = newCompany("Алфа");
    Company b = newCompany("Бета");
    EmploymentContract inA = contract(a);
    EmploymentContract inB = contract(b);
    BgPaySlip numbered = slip(inA, march());

    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(tx(() -> JPA.find(Company.class, b.getId())));
    PersistenceException refusal =
        assertThrows(
            PersistenceException.class,
            () ->
                tx(
                    () -> {
                      BgPaySlip slip = JPA.find(BgPaySlip.class, numbered.getId());
                      slip.setEmploymentContract(JPA.find(EmploymentContract.class, inB.getId()));
                      return Beans.get(BgPaySlipRepository.class).save(slip);
                    }));
    assertTrue(refusal.getMessage().contains(a.getName()), refusal.getMessage());
    assertTrue(refusal.getMessage().contains(b.getName()), refusal.getMessage());
  }

  /** Съобщенията по веригата на причините — отказът при flush идва обвит. */
  private static String messages(Throwable e) {
    StringBuilder out = new StringBuilder();
    for (Throwable t = e; t != null; t = t.getCause()) {
      out.append(t.getMessage()).append(" | ");
    }
    return out.toString();
  }

  @Test
  @DisplayName("REST сменя наведнъж договора И company към Б → отказ по старото състояние")
  void restChangingContractAndCompanyTogetherIsRefused() {
    Company a = newCompany("Алфа");
    Company b = newCompany("Бета");
    EmploymentContract inA = contract(a);
    EmploymentContract inB = contract(b);
    BgPaySlip numbered = slip(inA, march());

    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(tx(() -> JPA.find(Company.class, b.getId())));
    // Както Resource.save: JPA.manage → flush ПРЕДИ repository.save — хранилището вижда вече
    // съвпадащи договор и company; пазачът по старото състояние — не.
    RuntimeException refusal =
        assertThrows(
            RuntimeException.class,
            () ->
                tx(
                    () -> {
                      BgPaySlip slip = JPA.find(BgPaySlip.class, numbered.getId());
                      slip.setEmploymentContract(JPA.find(EmploymentContract.class, inB.getId()));
                      slip.setCompany(JPA.find(Company.class, b.getId()));
                      JPA.em().flush();
                      return Beans.get(BgPaySlipRepository.class).save(slip);
                    }));
    String text = messages(refusal);
    assertTrue(text.contains(a.getName()) && text.contains(b.getName()), text);
    assertEquals(
        a.getId(),
        tx(() -> JPA.find(BgPaySlip.class, numbered.getId()).getCompany().getId()),
        "дружеството на номера е непроменено");
  }

  @Test
  @DisplayName("company, подадено различно от платеца (REST на нов фиш) → отказ преди номера")
  void aCompanyOtherThanThePayerIsRefused() {
    Company a = newCompany("Алфа");
    Company b = newCompany("Бета");
    EmploymentContract inA = contract(a);
    Period period = march();
    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(tx(() -> JPA.find(Company.class, a.getId())));
    PersistenceException refusal =
        assertThrows(
            PersistenceException.class,
            () ->
                tx(
                    () -> {
                      BgPaySlip slip = new BgPaySlip();
                      slip.setStatusSelect(BgPaySlipResolver.DRAFT);
                      slip.setEmploymentContract(JPA.find(EmploymentContract.class, inA.getId()));
                      slip.setPeriod(JPA.find(Period.class, period.getId()));
                      slip.setCompany(JPA.find(Company.class, b.getId()));
                      return Beans.get(BgPaySlipRepository.class).save(slip);
                    }));
    assertTrue(refusal.getMessage().contains("платецът"), refusal.getMessage());
    assertTrue(refusal.getMessage().contains(b.getName()), refusal.getMessage());
  }

  @Test
  @DisplayName("изчистено company на номериран фиш покрай хранилището → отказ при flush")
  void clearingTheNumberingCompanyIsRefused() {
    Company a = newCompany("Алфа");
    BgPaySlip numbered = slip(contract(a), march());
    RuntimeException refusal =
        assertThrows(
            RuntimeException.class,
            () ->
                tx(
                    () -> {
                      JPA.find(BgPaySlip.class, numbered.getId()).setCompany(null);
                      JPA.em().flush();
                      return null;
                    }));
    assertTrue(messages(refusal).contains(a.getName()), messages(refusal));
  }

  // ── 5 · Базата: (дружество, номер) ───────────────────────────────────────

  @Test
  @DisplayName("един и същ номер в едно дружество → отказ от базата; в две дружества → допустим")
  void theDatabaseRefusesADuplicateWithinOneCompany() {
    Company a = newCompany("Алфа");
    Company b = newCompany("Бета");
    EmploymentContract inA = contract(a);
    EmploymentContract inB = contract(b);
    Period period = march();
    String number = "FISH-2026-9" + tag().substring(0, 3);

    // В две дружества — минава: ограничението е (company, reference), не глобално.
    tx(
        () -> {
          JPA.em().persist(raw(number, a, inA, period));
          JPA.em().persist(raw(number, b, inB, period));
          JPA.em().flush();
          return null;
        });

    // Второ в същото дружество — пада в БАЗАТА (покрай хранилището, без проверката на Axelor).
    PersistenceException duplicate =
        assertThrows(
            PersistenceException.class,
            () ->
                tx(
                    () -> {
                      JPA.em().persist(raw(number, a, inA, period));
                      JPA.em().flush();
                      return null;
                    }));
    assertNotNull(duplicate);
  }

  private static BgPaySlip raw(
      String number, Company company, EmploymentContract contract, Period period) {
    BgPaySlip slip = new BgPaySlip();
    slip.setReference(number);
    slip.setCompany(JPA.find(Company.class, company.getId()));
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setEmploymentContract(JPA.find(EmploymentContract.class, contract.getId()));
    slip.setPeriod(JPA.find(Period.class, period.getId()));
    return slip;
  }
}
