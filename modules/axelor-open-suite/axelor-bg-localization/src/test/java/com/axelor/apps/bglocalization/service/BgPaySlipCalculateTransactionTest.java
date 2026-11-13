package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.repo.BgPayrollParameterRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipCalculateTxTestModule.FailingCalculation;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.web.BgPaySlipController;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.rpc.ObjectMapperProvider;
import com.axelor.rpc.Request;
import com.axelor.rpc.Resource;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.util.Types;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ПРЕСМЯТАНЕТО НА ЕКРАНА НЕ ПИШЕ НИЩО; „ПРЕИЗЧИСЛИ“ ЗАПИСВА ФИША И Т. 9 ЗАЕДНО — ИЛИ НИЩО.
 *
 * <p>Капанът: „Преизчисли“, което не записва фиша (табът „Фиш*“, версия 0 в базата, старите суми),
 * докато редовете по т. 9 на болничния лист се записват веднага — т. 9 без записан фиш зад нея,
 * оцеляла и след „Откажи промените“. Затова:
 *
 * <ul>
 *   <li>onChange ({@code BgPaySlipController#calculate}) не пише в базата нищо, а отпечатъкът на
 *       екрана се изтрива;
 *   <li>„Преизчисли“ ({@code BgPaySlipController#recalculate}) пресмята и записва черновата заедно
 *       с т. 9, в една транзакция; провал където и да е — нищо.
 * </ul>
 *
 * <p>Т. 9 тук е маркерът на {@link FailingCalculation#generateSickLeave} — шевът, през който
 * истинският генератор записва листа. Че истинският генератор НЕ пише по пътя на екрана, мери
 * {@code BgPaySlipRetroGeneratorsTest#theScreenCalculationWritesNoPoint9}. Свидетелите минават през
 * ИСТИНСКИЯ контролер и ИСТИНСКИЯ Guice, без тестова транзакция около извикването, и четат базата
 * след {@code JPA.clear()}.
 *
 * <p>⚠️ {@code @Tag("integration")}: {@code ./gradlew build} НЕ го пуска — пуска се през {@code
 * integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgPaySlipCalculateTxTestModule.class)
@Tag("integration")
class BgPaySlipCalculateTransactionTest extends JpaSupport {

  private static final AtomicInteger FIXTURE = new AtomicInteger();

  /** Отговорите така, както са стигнали до формата — виж {@link #serialized}. */
  private static final Map<ActionResponse, Map<String, Object>> SCREEN = new IdentityHashMap<>();

  /** Отпечатъкът на фикстурата — от „предишно Преизчисли“. */
  private static final String STORED_FINGERPRINT = "отпечатък отпреди";

  /**
   * ⚠️ „Преизчисли“ е зад CapabilityGate: фикстурата влиза с all.write (админ write) на дружеството
   * на фиша — {@link #savedDraft}. Свидетелите на самия гейт са в BgTrzSecurityGatesTest.
   */
  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  @BeforeEach
  void clean() {
    FailingCalculation.failAfterWrite = false;
    FailingCalculation.failAtSave = false;
    FailingCalculation.touchLines = false;
    FailingCalculation.STARTED.set(0);
    SCREEN.clear();
    JPA.runInTransaction(
        () ->
            JPA.em()
                .createQuery("DELETE FROM BgPayrollParameter p WHERE p.code = :c")
                .setParameter("c", FailingCalculation.MARKER)
                .executeUpdate());
    JPA.clear();
  }

  // ═══ onChange — НИЩО В БАЗАТА ════════════════════════════════════════════════════

  @Test
  @DisplayName("onChange: т. 9 не се пише, фишът не се пише, отпечатъкът на екрана се изтрива")
  void theScreenCalculationWritesNothing() {
    Fixture f = savedDraft();
    Map<String, Object> context = new HashMap<>();
    context.put("id", f.slipId);

    ActionResponse response = onChange(context);

    assertEquals(0, point9Written(), "⚠️ т. 9 записана без записан фиш: " + response.getData());
    BgPaySlip slip = slipInDb(f);
    assertEquals(f.version, slip.getVersion(), "фишът не е записван");
    assertEquals(STORED_FINGERPRINT, slip.getBgCalcFingerprint(), "и отпечатъкът му в базата");
    assertTrue(values(response).containsKey("bgCalcFingerprint"), "" + values(response));
    assertNull(
        values(response).get("bgCalcFingerprint"),
        "⚠️ на екрана остана отпечатък: „Запиши“ след onChange щеше да се потвърди без т. 9");
  }

  @Test
  @DisplayName("onChange, който пипа перата, НЕ пише перата на записания фиш")
  void theScreenCalculationDoesNotWriteTheSlip() {
    Fixture f = savedDraft();
    FailingCalculation.touchLines = true;
    Map<String, Object> context = new HashMap<>();
    context.put("id", f.slipId);

    ActionResponse response = onChange(context);

    BgPaySlipLine reloaded = lineInDb(f);
    assertMoney(
        "100.00", reloaded.getBgAmount(), "⚠️ onChange записа перото: " + response.getData());
    assertEquals(0, point9Written());
  }

  @Test
  @DisplayName("onChange: всяко перо стига до екрана С №, посока, име и сума — пипнато или не")
  void theScreenResponseCarriesEveryLineValue() throws Exception {
    Fixture f = savedDraft();
    Map<String, Object> context = new HashMap<>();
    context.put("id", f.slipId);

    // Непипнато перо не бива да идва само с id и версия — без сума.
    Map<String, Object> untouched = onlyLine(linesOnScreen(onChange(context)));
    assertEquals(f.lineId, ((Number) untouched.get("id")).longValue());
    assertEquals(Integer.valueOf(185), intOf(untouched.get("bgLineNumber")), "№: " + untouched);
    assertEquals(BgPaySlipResolver.CREDIT, untouched.get("bgSign"), "посока: " + untouched);
    assertEquals("Еднократен бонус", untouched.get("name"), "име: " + untouched);
    assertMoney("100.00", money(untouched.get("bgAmount")), "сума: " + untouched);

    // Пипнато от генератор: не бива да идва само с име и сума, без № и посока.
    FailingCalculation.touchLines = true;
    Map<String, Object> touched = onlyLine(linesOnScreen(onChange(context)));
    assertEquals(Integer.valueOf(185), intOf(touched.get("bgLineNumber")), "№: " + touched);
    assertEquals(BgPaySlipResolver.CREDIT, touched.get("bgSign"), "посока: " + touched);
    assertMoney("999.00", money(touched.get("bgAmount")), "новата сума: " + touched);
  }

  // ═══ „ПРЕИЗЧИСЛИ“ — ФИШЪТ И Т. 9 ЗАЕДНО ═══════════════════════════════════════════

  @Test
  @DisplayName("„Преизчисли“: промяната на екрана, сборовете, отпечатъкът и т. 9 — записани заедно")
  void theButtonSavesTheSlipAndPoint9Together() {
    Fixture f = savedDraft();

    ActionResponse response = press(editedTo("150.00", f));

    assertEquals(1, point9Written(), "т. 9 записана: " + response.getData());
    BgPaySlipLine line = lineInDb(f);
    assertMoney("150.00", line.getBgAmount(), "промяната на екрана е в базата");
    assertTrue(
        line.getBgIsTaxable(),
        "⚠️ записът не мина през репозитория на фиша: третирането не е преписано от речника — без"
            + " него и номерът на новия фиш, и одитът на режима на копието отпадат");
    // ⚠️ Фишът се чете СЛЕД перото: lineInDb чисти контекста, а отпечатъкът чете перата лениво.
    BgPaySlip slip = slipInDb(f);
    assertTrue(slip.getVersion() > f.version, "фишът е записан: версия " + slip.getVersion());
    assertMoney("150.00", slip.getBgGrossTotal(), "и сборът — пресметнат и записан");
    assertNotNull(slip.getBgCalcFingerprint());
    assertEquals(
        Collections.emptyList(),
        BgPaySlipFingerprint.changes(slip.getBgCalcFingerprint(), slip),
        "отпечатъкът описва ЗАПИСАНИЯ фиш — потвърждаването го приема");
    assertEquals(f.slipId, ((Number) values(response).get("id")).longValue());
    assertEquals(Boolean.TRUE, dataOf(response).get("reload"), "формата се презарежда от базата");
  }

  @Test
  @DisplayName("„Преизчисли“ на НОВ фиш: фишът се ражда записан, с номер, отпечатък и т. 9")
  void theButtonSavesANewSlip() {
    Fixture f = savedDraft();
    Map<String, Object> context = new HashMap<>();
    context.put("statusSelect", BgPaySlipResolver.DRAFT);
    context.put("period", ref(f.periodId));
    // ⚠️ С договора, както го праща формата: фиш без дружество не се записва изобщо.
    // ⚠️ ДРУГ ДОГОВОР В СЪЩОТО ДРУЖЕСТВО: на договора на фикстурата вече има редовна чернова за
    // март, а втори редовен фиш за същия договор и период се отказва при запис.
    context.put("employmentContract", ref(otherContract(f)));
    // Номерът е даден, за да не мери свидетелят редицата на AOS в HSQLDB — тя не е предметът му.
    context.put("reference", "FISH-TX-NEW-" + f.slipId);

    ActionResponse response = press(context);

    Object id = values(response).get("id");
    assertNotNull(id, "новият фиш връща идентификатора си към формата: " + response.getData());
    JPA.clear();
    BgPaySlip created = JPA.em().find(BgPaySlip.class, ((Number) id).longValue());
    assertNotNull(created, "фишът е в базата");
    assertEquals(BgPaySlipResolver.DRAFT, created.getStatusSelect());
    assertEquals("FISH-TX-NEW-" + f.slipId, created.getReference());
    assertNotNull(created.getBgCalcFingerprint());
    assertEquals(1, point9Written(), "и т. 9 — в същата транзакция");
  }

  @Test
  @DisplayName("„Преизчисли“, провал в генератор СЛЕД т. 9 → нищо: нито т. 9, нито промяната")
  void aFailureInTheMiddleLeavesNothing() {
    Fixture f = savedDraft();
    FailingCalculation.failAfterWrite = true;

    ActionResponse response = press(editedTo("150.00", f));

    assertNothingWritten(f, response);
    assertTrue(
        String.valueOf(response.getData()).contains("провал по средата на пресмятането"),
        "провалът се вижда на екрана: " + response.getData());
  }

  @Test
  @DisplayName("„Преизчисли“, провал СЛЕД записа на фиша → нищо: rollback-ът връща и двата")
  void aFailureAfterTheSaveLeavesNothing() {
    Fixture f = savedDraft();
    FailingCalculation.failAtSave = true;

    ActionResponse response = press(editedTo("150.00", f));

    assertNothingWritten(f, response);
    assertTrue(
        String.valueOf(response.getData()).contains("провал след записа на фиша"),
        "провалът се вижда на екрана: " + response.getData());
  }

  // ═══ ПАЗАЧИТЕ: СТАТУС, ПЕРИОД, ДРУЖЕСТВО — ПРЕДИ ПРЕСМЯТАНЕТО ══════════════════════
  //
  // Всеки отказ се мери с ТРИ неща: шумният текст, непокътнатият фиш и т. 9 — и това, че
  // пресмятането изобщо НЕ Е ЗАПОЧНАЛО (FailingCalculation.STARTED). Без третото пазачът в
  // репозитория щеше да прикрие махнат пазач в „Преизчисли“: rollback-ът връща т. 9, но
  // генераторите вече са чели листовете и договора — при чуждо дружество, неговите.

  @Test
  @DisplayName(
      "„Преизчисли“ със statusSelect=DRAFT върху ПОТВЪРДЕН фиш → отказ; фиш и т. 9 — непокътнати")
  void aConfirmedSlipIsNotRecalculatedByAForgedStatus() {
    Fixture f = savedSlip(BgPaySlipResolver.CONFIRMED, null);

    ActionResponse response = press(editedTo("150.00", f));

    String text = String.valueOf(response.getData());
    assertTrue(
        text.contains("Преизчислява се само чернова")
            && text.contains(f.reference)
            && text.contains(BgPaySlipResolver.CONFIRMED),
        "шумен отказ с номера и статуса: " + text);
    assertUntouched(f, text);
    assertEquals(BgPaySlipResolver.CONFIRMED, slipInDb(f).getStatusSelect(), "статусът остава");
  }

  @Test
  @DisplayName("„Преизчисли“ на чернова в ЗАКЛЮЧЕН период (CLOSED) → отказ; т. 9 не е записана")
  void aDraftInALockedPeriodIsNotRecalculated() {
    Fixture f = savedSlip(BgPaySlipResolver.DRAFT, PeriodRepository.STATUS_CLOSED);

    ActionResponse response = press(editedTo("150.00", f));

    String text = String.valueOf(response.getData());
    assertTrue(
        text.contains("не се записва и не се преизчислява") && text.contains("ЗАКЛЮЧЕН"),
        "шумен отказ за заключения период: " + text);
    assertUntouched(f, text);
  }

  @Test
  @DisplayName("„Преизчисли“ на чернова в ЗАТВАРЯЩ СЕ период (и двата статуса) → отказ")
  void aDraftInAClosingPeriodIsNotRecalculated() {
    for (int status :
        new int[] {
          PeriodRepository.STATUS_CLOSURE_IN_PROGRESS, PeriodRepository.STATUS_TEMPORARILY_CLOSED
        }) {
      FailingCalculation.STARTED.set(0);
      Fixture f = savedSlip(BgPaySlipResolver.DRAFT, status);

      ActionResponse response = press(editedTo("150.00", f));

      String text = String.valueOf(response.getData());
      assertTrue(
          text.contains("не се записва и не се преизчислява")
              && text.contains("в ход на счетоводно затваряне"),
          "статус " + status + " — шумен отказ: " + text);
      assertUntouched(f, text);
      BgRoleLogin.logout();
    }
  }

  @Test
  @DisplayName("Стандартният запис / REST на чернова в заключен или затварящ се период → отказ")
  void aDraftInALockedOrClosingPeriodIsNotSavedThroughRest() {
    for (int status :
        new int[] {
          PeriodRepository.STATUS_CLOSED,
          PeriodRepository.STATUS_CLOSURE_IN_PROGRESS,
          PeriodRepository.STATUS_TEMPORARILY_CLOSED
        }) {
      Fixture f = savedSlip(BgPaySlipResolver.DRAFT, status);
      Map<String, Object> data = new HashMap<>();
      data.put("id", f.slipId);
      data.put("version", f.version);
      data.put("note", "редакция в затворен месец");

      Throwable refused = restSave(data);

      assertNotNull(refused, "статус " + status + ": черновата се записа през REST");
      assertTrue(
          causes(refused).contains("не се записва и не се преизчислява"),
          "статус " + status + " — отказът е от пазача на периода: " + causes(refused));
      assertEquals(
          f.version, slipInDb(f).getVersion(), "статус " + status + ": фишът е непокътнат");
      BgRoleLogin.logout();
    }
  }

  @Test
  @DisplayName("„Преизчисли“ на НОВ фиш с договор на дружество Б при активно А → отказ")
  void aNewSlipOnAnotherCompanysContractIsRefused() {
    Fixture f = savedDraft();
    Long foreignContract = otherCompanyContract();
    Map<String, Object> context = new HashMap<>();
    context.put("statusSelect", BgPaySlipResolver.DRAFT);
    context.put("period", ref(f.periodId));
    context.put("employmentContract", ref(foreignContract));
    context.put("reference", "FISH-TX-FOREIGN-" + f.slipId);

    ActionResponse response = press(context);

    String text = String.valueOf(response.getData());
    assertTrue(text.contains("You can only work on your active company"), "отказ: " + text);
    assertEquals(0, FailingCalculation.STARTED.get(), "пресмятането не е започнало: " + text);
    assertEquals(0, point9Written(), "т. 9 не е записана: " + text);
    assertEquals(
        0L,
        JPA.em()
            .createQuery(
                "SELECT COUNT(s) FROM BgPaySlip s WHERE s.employmentContract.id = :c", Long.class)
            .setParameter("c", foreignContract)
            .getSingleResult()
            .longValue(),
        "фиш върху чуждия договор не е роден");
  }

  @Test
  @DisplayName("„Преизчисли“ сменя договора на фиша към дружество Б → отказ; договорът остава")
  void movingTheSlipToAnotherCompanysContractIsRefused() {
    Fixture f = savedDraft();
    Long foreignContract = otherCompanyContract();
    Map<String, Object> context = editedTo("150.00", f);
    context.put("employmentContract", ref(foreignContract));

    ActionResponse response = press(context);

    String text = String.valueOf(response.getData());
    assertTrue(text.contains("You can only work on your active company"), "отказ: " + text);
    assertUntouched(f, text);
    assertEquals(
        f.contractId,
        slipInDb(f).getEmploymentContract().getId(),
        "фишът е на договора си: " + text);
  }

  @Test
  @DisplayName("all.read натиска „Преизчисли“ на чернова в отворен период → отказ от гейта")
  void aReaderCannotRecalculate() {
    Fixture f = savedDraft();
    BgRoleLogin.logout();
    BgRoleLogin.loginRead(JPA.em().find(Company.class, f.companyId));

    ActionResponse response = press(editedTo("150.00", f));

    String text = String.valueOf(response.getData());
    assertTrue(
        text.contains("You are not allowed to recalculate a pay slip"), "отказ на гейта: " + text);
    assertUntouched(f, text);
  }

  // ═══ помощни ═════════════════════════════════════════════════════════════════════

  /** Нищо не е пипнато и пресмятането не е започнало. */
  private static void assertUntouched(Fixture f, String text) {
    assertEquals(0, FailingCalculation.STARTED.get(), "⚠️ пресмятането започна: " + text);
    assertEquals(0, point9Written(), "⚠️ т. 9 е записана: " + text);
    BgPaySlip slip = slipInDb(f);
    assertEquals(f.version, slip.getVersion(), "⚠️ фишът е записан: " + text);
    assertEquals(STORED_FINGERPRINT, slip.getBgCalcFingerprint(), "⚠️ отпечатъкът е сменен");
    assertMoney("100.00", lineInDb(f).getBgAmount(), "⚠️ промяната от екрана оцеля");
  }

  /** Дружество Б с един договор — идентификаторът на договора. Активното остава А. */
  private static Long otherCompanyContract() {
    int n = FIXTURE.incrementAndGet();
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          Company company = new Company();
          company.setName("Фирма Б TX " + n);
          company.setCode("CTXB" + n);
          JPA.em().persist(company);
          EmploymentContract contract = new EmploymentContract();
          contract.setPayCompany(company);
          JPA.em().persist(contract);
          id[0] = contract.getId();
        });
    JPA.clear();
    return id[0];
  }

  private static Map<String, Object> ref(Long id) {
    Map<String, Object> ref = new HashMap<>();
    ref.put("id", id);
    return ref;
  }

  /**
   * {@code POST /ws/rest/BgPaySlip/<id>} — ИСТИНСКИЯТ {@link Resource#save} от инжектора, с
   * проверките на правата и с {@code JPA.manage}. Това е и пътят на бутона „Запиши“ на формата.
   */
  @SuppressWarnings("unchecked")
  private static Throwable restSave(Map<String, Object> data) {
    Request request = new Request();
    request.setModel(BgPaySlip.class.getName());
    request.setData(data);
    JPA.clear();
    try {
      ((Resource<BgPaySlip>)
              Beans.get(Injector.class)
                  .getInstance(
                      Key.get(Types.newParameterizedType(Resource.class, BgPaySlip.class))))
          .save(request);
      return null;
    } catch (Throwable t) {
      return t;
    } finally {
      JPA.clear();
    }
  }

  private static String causes(Throwable t) {
    StringBuilder sb = new StringBuilder();
    for (Throwable c = t; c != null; c = c.getCause()) {
      sb.append(c.getMessage()).append(" | ");
    }
    return sb.toString();
  }

  private void assertNothingWritten(Fixture f, ActionResponse response) {
    assertEquals(0, point9Written(), "⚠️ т. 9 оцеля след провала: " + response.getData());
    BgPaySlip slip = slipInDb(f);
    assertEquals(f.version, slip.getVersion(), "⚠️ фишът записан въпреки провала");
    assertMoney("100.00", lineInDb(f).getBgAmount(), "⚠️ промяната от екрана оцеля");
    assertEquals(STORED_FINGERPRINT, slip.getBgCalcFingerprint());
  }

  private static final class Fixture {
    Long slipId;
    Long companyId;
    Long contractId;
    Long lineId;
    Long periodId;
    Long typeId;
    Integer version;
    String reference;
  }

  /**
   * Записана чернова за март 2026 с едно перо 185 за 100,00 и отпечатък от „предишно“ пресмятане.
   * Видът на перото е облагаем, а записаното перо — НЕ: третирането се преписва от речника чак при
   * запис през репозитория на фиша ({@code BgPaySlipManagementRepository#fillTreatmentFromType}).
   */
  private static Fixture savedDraft() {
    return savedSlip(BgPaySlipResolver.DRAFT, null);
  }

  /**
   * Същото, но фишът е в статус {@code status}, а периодът — в {@code periodStatus} ({@code null} =
   * подразбирането, отворен). Записва се с {@code persist}, покрай репозитория: свидетелите мерят
   * пазачите, а не я раждат през тях.
   */
  /** Втори договор в дружеството на фикстурата — без фиш за периода. */
  private static Long otherContract(Fixture f) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          EmploymentContract contract = new EmploymentContract();
          contract.setPayCompany(JPA.em().find(Company.class, f.companyId));
          JPA.em().persist(contract);
          id[0] = contract.getId();
        });
    return id[0];
  }

  private static Fixture savedSlip(String status, Integer periodStatus) {
    int n = FIXTURE.incrementAndGet();
    Fixture f = new Fixture();
    JPA.runInTransaction(
        () -> {
          Year year = new Year();
          year.setName("Заплатна 2026 · " + n);
          year.setCode("PY2026-TX" + n);
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setTypeSelect(2);
          JPA.em().persist(year);
          Period period = new Period();
          period.setName("03/PY2026-TX" + n);
          period.setYear(year);
          period.setFromDate(LocalDate.of(2026, 3, 1));
          period.setToDate(LocalDate.of(2026, 3, 31));
          if (periodStatus != null) {
            period.setStatusSelect(periodStatus);
          }
          JPA.em().persist(period);
          // ⚠️ Дружество и договор: „Преизчисли“ е зад CapabilityGate, а правото на
          // all.write върху BgPaySlip е по employmentContract.payCompany = активното дружество.
          Company company = new Company();
          company.setName("Фирма TX " + n);
          company.setCode("CTX" + n);
          JPA.em().persist(company);
          EmploymentContract contract = new EmploymentContract();
          contract.setPayCompany(company);
          JPA.em().persist(contract);
          f.companyId = company.getId();
          f.contractId = contract.getId();
          BgPaySlipLineType type = new BgPaySlipLineType();
          type.setCode("TX_BONUS_" + n);
          type.setNumber(9000 + n);
          type.setName("Еднократен бонус · " + n);
          type.setBgIsTaxable(true);
          JPA.em().persist(type);
          BgPaySlip slip = new BgPaySlip();
          slip.setReference("FISH-TX-" + n);
          slip.setStatusSelect(status);
          f.reference = slip.getReference();
          slip.setPeriod(period);
          slip.setEmploymentContract(contract);
          slip.setBgCalcFingerprint(STORED_FINGERPRINT);
          slip.setBgPaySlipLineList(new ArrayList<>());
          BgPaySlipLine line = new BgPaySlipLine();
          line.setBgLineNumber(185);
          line.setName("Еднократен бонус");
          line.setBgSign(BgPaySlipResolver.CREDIT);
          line.setBgAmount(new BigDecimal("100.00"));
          line.setBgPaySlipLineType(type);
          line.setPaySlip(slip);
          slip.getBgPaySlipLineList().add(line);
          JPA.em().persist(slip);
          JPA.em().persist(line);
          f.slipId = slip.getId();
          f.lineId = line.getId();
          f.periodId = period.getId();
          f.typeId = type.getId();
        });
    JPA.clear();
    f.version = JPA.em().find(BgPaySlip.class, f.slipId).getVersion();
    JPA.clear();
    // Вход с админ write (all.write), НЕ технически администратор — гейтът е истинският.
    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(JPA.em().find(Company.class, f.companyId));
    return f;
  }

  /** Контекстът на формата така, както го праща Axelor: фишът с версия, перото — с новата сума. */
  private static Map<String, Object> editedTo(String amount, Fixture f) {
    Map<String, Object> line = new HashMap<>();
    line.put("id", f.lineId);
    line.put("version", lineInDb(f).getVersion());
    line.put("bgLineNumber", 185);
    line.put("name", "Еднократен бонус");
    line.put("bgSign", BgPaySlipResolver.CREDIT);
    line.put("bgAmount", amount);
    Map<String, Object> type = new HashMap<>();
    type.put("id", f.typeId);
    line.put("bgPaySlipLineType", type);
    line.put("bgIsTaxable", false);
    List<Object> lines = new ArrayList<>();
    lines.add(line);
    Map<String, Object> context = new HashMap<>();
    context.put("id", f.slipId);
    context.put("version", f.version);
    context.put("statusSelect", BgPaySlipResolver.DRAFT);
    context.put("bgPaySlipLineList", lines);
    return context;
  }

  private static ActionResponse onChange(Map<String, Object> context) {
    ActionResponse response = new ActionResponse();
    assertNoTransaction();
    Beans.get(BgPaySlipController.class).calculate(request(context), response);
    return serialized(response);
  }

  private static ActionResponse press(Map<String, Object> context) {
    ActionResponse response = new ActionResponse();
    assertNoTransaction();
    Beans.get(BgPaySlipController.class).recalculate(request(context), response);
    return serialized(response);
  }

  /**
   * ⚠️ ОТГОВОРЪТ СЕ СЕРИАЛИЗИРА ВЕДНАГА, както Axelor го прави в края на заявката — с ОТВОРЕН
   * контекст. Проксито на перото носи препратката към вида му като ленив обект, а проверките долу
   * чистят контекста ({@code JPA.clear()}); сериализиран след тях, отговорът пада с {@code
   * LazyInitializationException}, който формата никога не вижда.
   */
  @SuppressWarnings("unchecked")
  private static ActionResponse serialized(ActionResponse response) {
    try {
      ObjectMapper json = new ObjectMapperProvider().get();
      SCREEN.put(response, json.readValue(json.writeValueAsString(dataOf(response)), Map.class));
    } catch (Exception e) {
      throw new AssertionError("отговорът не се сериализира: " + response.getData(), e);
    }
    return response;
  }

  private static void assertNoTransaction() {
    assertFalse(
        JPA.em().getTransaction().isActive(),
        "⚠️ Действието тръгва БЕЗ транзакция, както в Axelor — иначе свидетелят е безсмислен");
  }

  private static ActionRequest request(Map<String, Object> context) {
    context.put("_model", BgPaySlip.class.getName());
    ActionRequest request = new ActionRequest();
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    request.setData(data);
    request.setModel(BgPaySlip.class.getName());
    return request;
  }

  private static long point9Written() {
    JPA.clear();
    return Beans.get(BgPayrollParameterRepository.class)
        .all()
        .filter("self.code = :c")
        .bind("c", FailingCalculation.MARKER)
        .count();
  }

  private static BgPaySlip slipInDb(Fixture f) {
    JPA.clear();
    return JPA.em().find(BgPaySlip.class, f.slipId);
  }

  private static BgPaySlipLine lineInDb(Fixture f) {
    JPA.clear();
    return JPA.em().find(BgPaySlipLine.class, f.lineId);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> dataOf(ActionResponse response) {
    Object data = response.getData();
    if (data instanceof List) {
      for (Object item : (List<Object>) data) {
        if (item instanceof Map) {
          return (Map<String, Object>) item;
        }
      }
    }
    return new HashMap<>();
  }

  /** Стойностите така, както стигат до формата — през сериализатора на Axelor, не от обекта. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> values(ActionResponse response) {
    Map<String, Object> data = SCREEN.get(response);
    Object values = data == null ? null : data.get("values");
    return values instanceof Map ? (Map<String, Object>) values : new HashMap<>();
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> linesOnScreen(ActionResponse response) {
    Object lines = values(response).get("bgPaySlipLineList");
    assertNotNull(lines, "перата не стигнаха до екрана: " + response.getData());
    return (List<Map<String, Object>>) lines;
  }

  private static Map<String, Object> onlyLine(List<Map<String, Object>> lines) {
    assertEquals(1, lines.size(), "" + lines);
    return lines.get(0);
  }

  private static Integer intOf(Object value) {
    return value == null ? null : Integer.valueOf(((Number) value).intValue());
  }

  private static BigDecimal money(Object value) {
    return value == null ? null : new BigDecimal(String.valueOf(value));
  }

  private static void assertMoney(String expected, BigDecimal actual, String what) {
    assertNotNull(actual, what);
    assertEquals(0, new BigDecimal(expected).compareTo(actual), what + ": " + actual);
  }
}
