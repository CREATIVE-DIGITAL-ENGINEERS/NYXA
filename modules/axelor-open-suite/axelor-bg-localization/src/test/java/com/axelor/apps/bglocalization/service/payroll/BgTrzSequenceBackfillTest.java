package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.testsupport.BgTestApp;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * СТЪПКАТА ПРИ СТАРТ ({@link BgTrzSequenceBackfill}) — заварените дружества продължават от
 * НАЙ-ГОЛЕМИЯ СИ номер, по година; второ пускане не променя нищо; заварените номера не се пипат.
 *
 * <p>Сцената е заварена база отпреди броячите по дружество: двете дружества са без ТРЗ редици
 * (махнати след раждането, което днес ги създава), а документите им са записани покрай хранилищата,
 * с номерата от старата обща редица — разпръснати между дружествата, вкл. фиш на самоосигуряващ се
 * (дружеството — от основния договор на регистрацията) и резервен {@code FISH-RES-…}. Трето
 * дружество вече има своя редица: тя не се пипа.
 *
 * <p>⚠️ {@code @Tag("integration")}: пуска се през {@code integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgTrzSequenceBackfillTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private static final class Scene {
    Company a;
    Company b;
    Company c;
    Long cPaySlipSequenceId;
    EmploymentContract inA;
    Period march;
    final List<Long> slipIds = new ArrayList<>();
    final List<Long> compensationIds = new ArrayList<>();
  }

  private static Scene scene() {
    String tag = String.valueOf(System.nanoTime());
    Scene s = new Scene();
    tx(
        () -> {
          BgTestApp.ensureAppBase();
          s.a = company("Алфа", tag);
          s.b = company("Бета", tag);
          s.c = company("Вега", tag);
          JPA.em().flush();
          // Заварени отпреди броячите по дружество: без ТРЗ редици.
          JPA.em()
              .createQuery(
                  "DELETE FROM Sequence s WHERE s.company IN (:cs) AND s.codeSelect IN (:codes)")
              .setParameter("cs", List.of(s.a, s.b))
              .setParameter("codes", BgTrzSequences.CODES)
              .executeUpdate();

          s.inA = contract(s.a);
          EmploymentContract inB = contract(s.b);
          EmploymentContract mainInA = contract(s.a);
          s.march = period(tag);
          Period march = s.march;

          // Номерата от старата обща редица — разпръснати между А и Б.
          slip(s, "FISH-2025-0010", s.inA, null, march);
          slip(s, "FISH-2026-0001", s.inA, null, march);
          slip(s, "FISH-2026-0002", inB, null, march);
          slip(s, "FISH-2026-0003", s.inA, null, march);
          slip(s, "FISH-2026-0005", inB, null, march);
          slip(s, "FISH-2026-0007", null, registration(mainInA, tag), march);
          slip(s, "FISH-RES-2026-0009", s.inA, null, march);

          BgCompensationType type = compensationType(tag);
          compensation(s, "OBEZ-2026-0001", s.inA, type);
          compensation(s, "OBEZ-2026-0004", inB, type);

          s.cPaySlipSequenceId =
              JPA.all(Sequence.class)
                  .filter(
                      "self.company = ?1 AND self.codeSelect = ?2", s.c, BgTrzSequences.PAY_SLIP)
                  .fetchOne()
                  .getId();
          return null;
        });
    return s;
  }

  private static Company company(String name, String tag) {
    Company company = new Company();
    company.setName(name + " " + tag);
    company.setCode(name.substring(0, 1) + tag);
    return JPA.save(company);
  }

  private static EmploymentContract contract(Company company) {
    EmploymentContract contract = new EmploymentContract();
    contract.setPayCompany(company);
    return JPA.save(contract);
  }

  private static Period period(String tag) {
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
    period.setFromDate(LocalDate.of(2026, 3, 1));
    period.setToDate(LocalDate.of(2026, 3, 31));
    period.setStatusSelect(PeriodRepository.STATUS_OPENED);
    return JPA.save(period);
  }

  private static BgSelfInsuredRegistration registration(EmploymentContract main, String tag) {
    Partner partner = new Partner();
    partner.setName("Самоосигуряващ се " + tag);
    // Всеки партньор е на дружество — тук на платеца по основния договор.
    partner.setCompany(main.getPayCompany());
    partner = JPA.save(partner);
    Employee employee = new Employee();
    employee.setContactPartner(partner);
    employee.setMainEmploymentContract(main);
    employee = JPA.save(employee);
    BgSelfInsuredRegistration registration = new BgSelfInsuredRegistration();
    registration.setEmployee(employee);
    registration.setBgInsuredTypeCode("12");
    registration.setBgActivityGround("ART_4_3_2");
    registration.setBgObligedPersonCode("831919536");
    registration.setBgScopeYear(2026);
    return JPA.save(registration);
  }

  /** Заварен фиш — покрай хранилището, с номера от старата обща редица и БЕЗ company. */
  private static void slip(
      Scene s,
      String reference,
      EmploymentContract contract,
      BgSelfInsuredRegistration registration,
      Period period) {
    BgPaySlip slip = new BgPaySlip();
    slip.setReference(reference);
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    slip.setEmploymentContract(contract);
    slip.setBgSelfInsuredRegistration(registration);
    slip.setPeriod(period);
    JPA.em().persist(slip);
    s.slipIds.add(slip.getId());
  }

  private static BgCompensationType compensationType(String tag) {
    BgCompensationType type = new BgCompensationType();
    type.setCode("comp_" + tag);
    type.setName("Обезщетение " + tag);
    type.setLegalBasis("чл. 222 ал. 1 КТ");
    type.setBgDirection("EMPLOYER_TO_EMPLOYEE");
    type.setBgBaseVariesBy("NONE");
    return JPA.save(type);
  }

  private static void compensation(
      Scene s, String reference, EmploymentContract contract, BgCompensationType type) {
    BgCompensation doc = new BgCompensation();
    doc.setReference(reference);
    doc.setEmploymentContract(contract);
    doc.setBgCompensationType(type);
    doc.setBgEventDate(LocalDate.of(2026, 9, 30));
    JPA.em().persist(doc);
    s.compensationIds.add(doc.getId());
  }

  /**
   * Истинската стъпка. ⚠️ Номерата на сцената не се бият между тестовете в една схема: company на
   * заварените е празно, а дружествата на всеки тест са нови — максимумът се мери по платеца.
   */
  private static BgTrzSequenceBackfill backfill() {
    return Beans.get(BgTrzSequenceBackfill.class);
  }

  /** Следващият номер по година на редицата на дружеството. */
  private static Map<Integer, Long> nextByYear(Company company, String code) {
    return tx(
        () -> {
          Map<Integer, Long> out = new TreeMap<>();
          Sequence sequence =
              JPA.all(Sequence.class)
                  .filter("self.company = ?1 AND self.codeSelect = ?2", company, code)
                  .fetchOne();
          assertNotNull(sequence, company.getName() + " няма редица " + code);
          for (SequenceVersion v :
              JPA.all(SequenceVersion.class).filter("self.sequence = ?1", sequence).fetch()) {
            out.put(v.getStartDate().getYear(), v.getNextNum());
          }
          return out;
        });
  }

  private static List<String> references(Scene s) {
    return tx(
        () -> {
          List<String> out = new ArrayList<>();
          for (Long id : s.slipIds) {
            out.add(JPA.find(BgPaySlip.class, id).getReference());
          }
          for (Long id : s.compensationIds) {
            out.add(JPA.find(BgCompensation.class, id).getReference());
          }
          return out;
        });
  }

  @Test
  @DisplayName("заварени А и Б → редица от максимума си + 1 по година; второ пускане не мени нищо")
  void existingCompaniesContinueFromTheirOwnMaximum() {
    Scene s = scene();
    List<String> before = references(s);

    BgTrzSequenceBackfill.Result first = tx(() -> backfill().run());
    assertTrue(first.message().contains(s.a.getName()), first.message());

    Map<Integer, Long> aSlips = nextByYear(s.a, BgTrzSequences.PAY_SLIP);
    assertEquals(Long.valueOf(11), aSlips.get(2025), "А/2025: последен 0010 → 11");
    assertEquals(
        Long.valueOf(8), aSlips.get(2026), "А/2026: последен 0007 (самоосигуряващ се) → 8");
    assertEquals(Map.of(2026, 6L), nextByYear(s.b, BgTrzSequences.PAY_SLIP), "Б/2026: 0005 → 6");
    assertEquals(
        Map.of(2026, 2L), nextByYear(s.a, BgTrzSequences.COMPENSATION), "А обезщетения: 0001 → 2");
    assertEquals(
        Map.of(2026, 5L), nextByYear(s.b, BgTrzSequences.COMPENSATION), "Б обезщетения: 0004 → 5");

    // Третото дружество вече имаше своя редица — не е пипната.
    Sequence cSlips =
        tx(
            () ->
                JPA.all(Sequence.class)
                    .filter(
                        "self.company = ?1 AND self.codeSelect = ?2", s.c, BgTrzSequences.PAY_SLIP)
                    .fetchOne());
    assertEquals(s.cPaySlipSequenceId, cSlips.getId(), "заварената редица на В е същата");
    assertTrue(nextByYear(s.c, BgTrzSequences.PAY_SLIP).isEmpty(), "без добавени версии");

    // Второ пускане — нищо.
    BgTrzSequenceBackfill.Result second = tx(() -> backfill().run());
    assertTrue(second.created.isEmpty(), "второ пускане не създава: " + second.message());
    assertEquals(aSlips, nextByYear(s.a, BgTrzSequences.PAY_SLIP), "А е същата");
    assertEquals(Map.of(2026, 6L), nextByYear(s.b, BgTrzSequences.PAY_SLIP), "Б е същата");
    long sequences =
        tx(
            () ->
                JPA.all(Sequence.class)
                    .filter(
                        "self.company IN (?1) AND self.codeSelect IN (?2)",
                        List.of(s.a, s.b, s.c),
                        BgTrzSequences.CODES)
                    .count());
    assertEquals(6, sequences, "по една редица на код за трите дружества");

    assertEquals(before, references(s), "заварените номера не са пипнати");
  }

  @Test
  @DisplayName("след стъпката новият фиш в А е FISH-2026-0008 — продължение, не от 1")
  void theNextSlipContinuesTheCompanysNumbering() {
    Scene s = scene();
    tx(() -> backfill().run());
    BgRoleLogin.loginWrite(tx(() -> JPA.find(Company.class, s.a.getId())));
    BgPaySlip next =
        tx(
            () -> {
              BgPaySlip slip = new BgPaySlip();
              slip.setStatusSelect(BgPaySlipResolver.DRAFT);
              // Договорът на А с заварени фишове за март вече има редовен — нов договор в А.
              EmploymentContract fresh = new EmploymentContract();
              fresh.setPayCompany(JPA.find(Company.class, s.a.getId()));
              slip.setEmploymentContract(JPA.save(fresh));
              slip.setPeriod(JPA.find(Period.class, s.march.getId()));
              return Beans.get(BgPaySlipRepository.class).save(slip);
            });
    assertEquals("FISH-2026-0008", next.getReference());
  }

  @Test
  @DisplayName("максимумът: само FISH-ГГГГ-N; резервните и чуждите префикси не влизат")
  void onlyRealNumbersCountTowardsTheMaximum() {
    Map<Integer, Long> last =
        BgTrzSequenceBackfill.lastByYear(
            BgTrzSequences.PAY_SLIP,
            List.of(
                "FISH-2026-0003",
                "FISH-2026-0012",
                "FISH-RES-2026-0099",
                "OBEZ-2026-0500",
                "FISH-2025-0001",
                " FISH-2026-0004 "));
    assertEquals(Map.of(2025, 1L, 2026, 12L), last);
  }
}
