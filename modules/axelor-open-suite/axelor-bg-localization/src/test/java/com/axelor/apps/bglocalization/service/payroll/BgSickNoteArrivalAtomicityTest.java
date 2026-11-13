package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.ICalendarEvent;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgPayrollParameter;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.apps.bglocalization.testsupport.BgTestApp;
import com.axelor.apps.bglocalization.web.BgSickLeaveController;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.LeaveLine;
import com.axelor.apps.hr.db.LeaveReason;
import com.axelor.apps.hr.db.LeaveRequest;
import com.axelor.apps.hr.db.repo.LeaveReasonRepository;
import com.axelor.apps.hr.db.repo.LeaveRequestRepository;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.AbstractModule;
import com.google.inject.util.Modules;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * „ОТРАЗИ ПРИСТИГНАЛ БОЛНИЧЕН“ — ВСИЧКО ИЛИ НИЩО, мерено през ИСТИНСКИЯ контролер.
 *
 * <p><b>Какво пази.</b> Бутонът не бива да отмени заявката „Непредставен болничен“, без да се родят
 * копие и месец за НОИ. Ако първият {@code save} направи flush извън транзакция („no transaction is
 * in progress“), следата в контролера би commit-нала вече отменената заявка. Затова тук:
 *
 * <ul>
 *   <li>се вика {@link BgSickLeaveController#applyArrival} от Guice — пътят на {@code
 *       action-method} — <b>без тестова транзакция около него</b>. Обвит в такава, тестът би скрил
 *       точно грешката, която пази;
 *   <li>всяка проверка чете през ОТДЕЛЕН EntityManager, тоест от базата, а не от кеша на
 *       натискането.
 * </ul>
 *
 * <p><b>⚠️ ЗАЩО КОНТЕКСТЪТ НЕ СЕ ЧИСТИ ПРЕДИ НАТИСКАНЕТО.</b> AOS {@code Partner} носи
 * {@code @Formula} със SQL за PostgreSQL, който HSQLDB не може да изпълни. Пътят стига до Partner
 * през {@code EmploymentContract.fullName} → {@code employee.getName()}. Изчистен ли е контекстът,
 * Partner се зарежда наново и натискането пада с {@code PropertyAccessException} — артефакт на
 * тестовата база, не на кода. Фикстурата остава заредена от записа си. Транзакционната граница,
 * която тестът мери, не зависи от това.
 *
 * <p><b>Месецът е ПРЕДХОДНИЯТ спрямо днес, не закован.</b> Срокът по Н-13 е 30 април на следващата
 * година и се мери с истинската дата. Закован декември 2026 би започнал да пада сам от 01.05.2027.
 * Замразените числа на оригинала не зависят от календара на месеца.
 *
 * <p><b>СМЕТКАТА НА РЪКА.</b> Норма по КАЛЕНДАРА на месеца (понеделник до петък без официалните
 * празници от сийда, {@link #calendarNorm}), заплата 1200,00, 3 дни непредставен болничен, после
 * лист пн–ср (2 дни за осигурителя + 1 за ДОО). ПЪЛНИТЕ ставки за вид 01, трета категория, роден
 * след 1959, с ДЗПО: лице 6,58 + 2,20 + 1,40 + 0,40 + 3,20 = 13,78 %; осигурител 8,22 + 2,80 + 2,10
 * + 0,60 + 4,80 = 18,52 %; ДОД 10 %; база по ЗЗО чл. 40 ал. 1 т. 5 — 620,20. Оригиналът е ЗАВАРЕН
 * (без режим): отразяването попълва режима от текущите данни като част от изричното действие (3А).
 * Сумите долу са за септември 2026 — норма 20 (07.09 и 22.09 са празници); assert-ите ги смятат по
 * същите закръгляния за месеца, в който тестът върви ({@link Expected}).
 *
 * <pre>
 * ОРИГИНАЛЪТ (потвърден; 3 дни непредставен болничен, извън осигуряването)
 *   100 Основно възнаграждение  1200,00 × 17 / 20 = 1020,00
 *   лице  67,12 + 22,44 + 14,28 + 4,08 + 32,64 = 140,56
 *   осиг. 83,84 + 28,56 + 21,42 + 6,12 + 48,96 = 188,90
 *   700 ДОД (1020,00 − 140,56) × 10 % = 87,944 = 87,94
 *   нето 1020,00 − 140,56 − 87,94 = 791,50 · разход 1020,00 + 188,90 = 1208,90
 *
 * КОПИЕТО (calculate, а не само сборове)
 *   195 Болничен от осигурител  1200,00 × 70 % × 2 / 20 = 84,00   (к-во 2, среднодневно 60,0000)
 *   516 ЗО по чл. 40 ал. 1 т. 5  620,20 × 4,8 % × 1 / 20 = 1,48848 = 1,49
 *       (база 620,20 / 20 = 31,01)
 *   осигурителен доход 1020,00 + 84,00 = 1104,00
 *   лице  72,64 + 24,29 + 15,46 + 4,42 + 35,33 = 152,14
 *   осиг. 90,75 + 30,91 + 23,18 + 6,62 + 52,99 = 204,45 (+ 1,49 по т. 5)
 *   700 ДОД (1020,00 − 152,14) × 10 % = 86,786 = 86,79 — 195 е необлагаемо, но вноските върху него
 *           намаляват основата (чл. 42 ал. 2 ЗДДФЛ)
 *   нето 1104,00 − 152,14 − 86,79 = 865,07 · разход 1104,00 + 204,45 + 1,49 = 1309,94
 *
 * РАЗЛИКАТА копие − оригинал
 *   бруто +84,00 · лични вноски +11,58 · вноски на осигурителя +17,04 (с 1,49 по т. 5)
 *   данък −1,15 · нето +73,57 · разход +101,04
 * </pre>
 *
 * <p><b>⚠️ КОПИЕТО СЕ ПРЕСМЯТА.</b> Пристигането вика calculate() върху копието в същата
 * транзакция: копието не носи 5xx и 700 на оригинала, а има вноски върху 195 и 516 по т. 5, и
 * нетото и делтата не са надценени с личните вноски върху 195.
 *
 * <p><b>Отмяната на заявката</b> минава през стандартния cancel на AOS: балансът на {@code
 * LeaveLine} се връща, а локалното календарно събитие се трие — и двете в същата транзакция.
 *
 * <p><b>⚠️ Т. 9 СЕ ПИШЕ</b> — по месеци, върху пристигналия лист, в транзакцията на действието: два
 * работни дни за осигурителя и един за ДОО. За целта {@code BgSickLeaveService#episodesOf} пази
 * id-то на листа.
 *
 * <p><b>Справочните данни са само редовете, които пътят наистина чете:</b> двата вида отсъствие,
 * типовете пера, петте фонда и ставките им за вид 01 (дословно сийда), базата по ЗЗО чл. 40 ал. 1
 * т. 5, ставката на данъка, видът осигурен 01 (без МОД — фикстурата е над всеки под) и таванът.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgSickNoteArrivalAtomicityTest.Wiring.class)
@Tag("integration")
class BgSickNoteArrivalAtomicityTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static final String INJECTED = "ИНЖЕКТИРАН ПРОВАЛ при записа на месеца за НОИ";
  private static final String REFUSED_AGAIN = "Повторно отразяване не е разрешено";

  /** Предходният месец — срокът по Н-13 се мери с истинската дата. */
  private static final YearMonth MONTH = YearMonth.now().minusMonths(1);

  /** Нормата по КАЛЕНДАРА на сийда за месеца — септември 2026 е 20, не 22. */
  private static final int NORM = calendarNorm(MONTH);

  private static final BigDecimal SALARY = new BigDecimal("1200.00");

  /** Производственото окабеляване с един подменен клас: репозиторият на листа с брава за (Б). */
  public static class Wiring extends AbstractModule {
    @Override
    protected void configure() {
      install(
          Modules.override(new BgLocalizationProductionWiringTestModule())
              .with(
                  binder ->
                      binder
                          .bind(BgSickLeaveRepository.class)
                          .to(FailingSickLeaveRepository.class)));
    }
  }

  /**
   * ИСТИНСКИЯТ репозиторий. Единствената разлика е бравата: вдигната, записът на листа хвърля ПРЕДИ
   * да запише. Това е ПОСЛЕДНИЯТ запис на действието (месецът за НОИ): след отмяната на заявката,
   * копието, периода в ADJUSTING, перата, recompute и записа на копието.
   */
  public static class FailingSickLeaveRepository extends BgSickLeaveRepository {
    static volatile boolean armed;
    static volatile boolean reached;

    /**
     * ⚠️ БРАВАТА Е НА ЗАПИСА НА МЕСЕЦА ЗА НОИ, а не на всеки запис на листа: разбивът записва листа
     * и по-рано — редовете по т. 9. Те трябва да бъдат записани И върнати, за да мери свидетелят
     * „провал по средата → нищо“.
     */
    @Override
    public BgSickLeave save(BgSickLeave entity) {
      if (armed && entity.getBgExportMonth() != null) {
        reached = true;
        throw new RuntimeException(INJECTED);
      }
      return super.save(entity);
    }
  }

  // ═══ (А) УСПЕШНИЯТ ПЪТ ═══════════════════════════════════════════════════

  @Test
  @DisplayName("(А) Успех: заявката е отменена, копието носи новия разбив и точните суми")
  @SuppressWarnings("unchecked")
  void asuccessfulPressDoesEverythingAtOnce() {
    Scenario s = scenario("A");

    ActionResponse response = press(s.sheet);

    assertFalse(has(response, "error"), "без отказ: " + response.getData());
    assertTrue(has(response, "view"), "и копието се отваря: " + response.getData());

    LeaveRequest leave = fresh(em -> em.find(LeaveRequest.class, s.leave.getId()));
    assertEquals(
        LeaveRequestRepository.STATUS_CANCELED, leave.getStatusSelect(), "заявката е Отменен");
    assertTrue(
        leave.getComments() != null && leave.getComments().contains(s.sheet.getBgDocumentNumber()),
        "следата сочи листа: " + leave.getComments());
    // Стандартната отмяна на AOS: консумираните 3 дни се връщат, събитието изчезва.
    LeaveLine balance = fresh(em -> em.find(LeaveLine.class, s.leaveLineId));
    assertMoney("3", balance.getQuantity(), "балансът: 0 + 3 върнати дни");
    assertMoney("0", balance.getDaysValidated(), "валидирани дни: 3 − 3");
    assertNull(leave.getIcalendarEvent(), "заявката вече не сочи събитие");
    assertNull(
        fresh(em -> em.find(ICalendarEvent.class, s.eventId)), "календарното събитие е изтрито");

    List<BgPaySlip> copies = copiesOf(s.original);
    assertEquals(1, copies.size(), "точно едно коригиращо копие");
    BgPaySlip copy = copies.get(0);
    assertEquals(BgPaySlipResolver.DRAFT, copy.getStatusSelect(), "и то в ЧЕРНОВА");
    // Изгледът отваря ТОЧНО създаденото копие, а не празна форма за нов фиш.
    Map<String, Object> view = valueOf(response, "view");
    assertEquals(
        copy.getId(),
        ((Map<String, Object>) view.get("context")).get("_showRecord"),
        "отваря се записът на копието: " + view);
    assertTrue(copy.getBgIsCorrection(), "коригиращо");

    // Дванайсетте замразени полета, които пристигането НЕ мести — идват от оригинала.
    BgPaySlip original = s.original;
    assertEquals(original.getBgWorkingDaysInPeriod(), copy.getBgWorkingDaysInPeriod(), "норма");
    assertMoney(original.getBgBaseSalary(), copy.getBgBaseSalary(), "заплата");
    assertMoney(original.getBgLeaveDays161(), copy.getBgLeaveDays161(), "16.1");
    assertMoney(original.getBgLeaveDays162(), copy.getBgLeaveDays162(), "16.2");
    assertMoney(original.getBgLeaveDays163(), copy.getBgLeaveDays163(), "16.3");
    assertMoney(original.getBgLeaveDays164(), copy.getBgLeaveDays164(), "16.4");
    assertMoney(original.getBgLeaveDays165(), copy.getBgLeaveDays165(), "16.5");
    assertMoney(original.getBgLeaveDays16A(), copy.getBgLeaveDays16A(), "16.А");
    assertEquals(original.getBgLeaveDaysFrozenAt(), copy.getBgLeaveDaysFrozenAt(), "frozenAt");
    assertEquals(original.getBgWorkedHoursKnown(), copy.getBgWorkedHoursKnown(), "часове: признак");
    assertMoney(
        original.getBgOtherPayerInsurableIncome(),
        copy.getBgOtherPayerInsurableIncome(),
        "чужд доход");
    assertEquals(original.getBgNoOtherPayerIncome(), copy.getBgNoOtherPayerIncome(), "без чужд");

    // Четирите, които пристигането мести В ЕДНА СТЪПКА — числата на BgSickNoteArrivalMoneyTest.
    assertEquals(NORM - 3, copy.getBgWorkedDays(), "отработените: норма − 3");
    assertEquals(2, copy.getBgSickDaysEmployer(), "два дни за осигурителя");
    assertEquals(1, copy.getBgSickDaysDoo(), "един за ДОО");
    assertMoney("0.00", copy.getBgLeaveDaysOutside(), "outside → 0");

    // ── 3А: заварен оригинал — режимът е попълнен при отразяването, с повод ──
    assertEquals("FILLED", copy.getBgRegimeSource(), "режимът — попълнен от текущите данни");
    assertEquals("01", copy.getBgRegimeInsuredTypeCode());
    assertTrue(
        copy.getBgRegimeChangeSummary() != null
            && copy.getBgRegimeChangeSummary()
                .startsWith(
                    "Режимът е попълнен при отразяване на болничен лист № "
                        + s.sheet.getBgDocumentNumber()),
        "следата казва повода: " + copy.getBgRegimeChangeSummary());

    // ── перата на копието (виж сметката на ръка в javadoc-а на класа) ──
    BgPaySlipLine sickPay = line(copy, BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY);
    assertNotNull(sickPay, "перо 195 по чл. 40 ал. 5 КСО");
    assertMoney(E.sick, sickPay.getBgAmount(), "195: 1200,00 × 70 % × 2 / норма");
    assertMoney("2", sickPay.getBgQuantity(), "195: два работни дни");
    assertAtStoredScale(
        E.rate.toPlainString(),
        sickPay.getBgRate(),
        "195: среднодневно 1200,00 / норма. Колоната пази 4 знака в PostgreSQL, а тестовата"
            + " HSQLDB схема — 2 (генерираните ентитети нямат @Column(scale)), затова се сравнява"
            + " с точността, която базата държи");
    assertMoney(E.base, line(copy, "pl_osnovna").getBgAmount(), "100 остава от оригинала");
    for (Map.Entry<String, BigDecimal> c : E.copyLines.entrySet()) {
      BgPaySlipLine l = standardLine(copy, c.getKey());
      assertNotNull(l, c.getKey() + " (откази на генераторите: " + copy.getBgGeneratorGaps() + ")");
      assertMoney(c.getValue(), l.getBgAmount(), c.getKey() + " наново, върху 100 + 195");
      assertMoney(E.ins, l.getBgQuantity(), c.getKey() + ": базата");
    }
    assertMoney(E.cTax, line(copy, "pl_dod").getBgAmount(), "700 наново");
    BgPaySlipLine zzo = healthAbsenceLine(copy);
    assertNotNull(zzo, "⚠️ перото 516 по ЗЗО чл. 40 ал. 1 т. 5 се ражда");
    assertMoney(E.zzo, zzo.getBgAmount(), "516 по т. 5: 620,20 × 4,8 % × 1 / норма");
    assertMoney(E.zzoBase, zzo.getBgQuantity(), "и базата: 620,20 / норма");

    // ── заглавните суми на копието ──
    assertMoney(E.ins, copy.getBgGrossTotal(), "бруто: 100 + 195");
    assertMoney(E.ins, copy.getBgInsuranceIncome(), "осигурителен доход: 100 + 195");
    assertMoney(E.base.subtract(E.cPersonal), copy.getBgTaxBase(), "данъчна основа: 100 − лични");
    assertMoney(E.cTax, copy.getBgTaxAmount(), "данък");
    assertMoney(E.cNet, copy.getBgNetTotal(), "нето");
    assertMoney(E.cCost, copy.getBgEmployerCost(), "разход: бруто + 516 + 516 по т. 5");

    // ── разликата копие − оригинал ──
    assertMoney(E.sick, copy.getBgGrossTotal().subtract(original.getBgGrossTotal()), "Δ бруто");
    assertMoney(
        E.cPersonal.subtract(E.personal),
        BgPaySlipResolver.employeeContributions(copy.getBgPaySlipLineList())
            .subtract(BgPaySlipResolver.employeeContributions(s.originalLines)),
        "⚠️ Δ лични вноски — вноските върху перо 195");
    assertMoney(
        E.cEmployer.subtract(E.employer).add(E.zzo),
        BgPaySlipResolver.employerContributions(copy.getBgPaySlipLineList())
            .subtract(BgPaySlipResolver.employerContributions(s.originalLines)),
        "⚠️ Δ вноски на осигурителя — върху 195 и перото 516 по т. 5");
    assertMoney(
        E.cTax.subtract(E.tax),
        copy.getBgTaxAmount().subtract(original.getBgTaxAmount()),
        "Δ данък");
    assertMoney(
        E.cNet.subtract(E.net),
        copy.getBgNetTotal().subtract(original.getBgNetTotal()),
        "Δ нето — 195 минус личните вноски върху него, плюс по-ниския данък");
    assertMoney(
        E.cCost.subtract(E.cost),
        copy.getBgEmployerCost().subtract(original.getBgEmployerCost()),
        "Δ разход");

    assertEquals(
        PeriodRepository.STATUS_ADJUSTING,
        periodStatus(s.period),
        "периодът е под корекция, докато копието чака човек");

    // Т. 9 на листа: пн и вт за осигурителя (чл. 40 ал. 5 КСО), ср за ДОО, месецът на листа.
    List<BgSickLeavePeriodLine> point9 = point9Of(s.sheet);
    assertEquals(2, point9.size(), "т. 9 има ред за осигурителя и ред за ДОО: " + point9);
    BgSickLeavePeriodLine employer = null;
    BgSickLeavePeriodLine noi = null;
    for (BgSickLeavePeriodLine l : point9) {
      if ("EMPLOYER".equals(l.getPayer())) {
        employer = l;
      } else if ("NOI".equals(l.getPayer())) {
        noi = l;
      }
    }
    assertNotNull(employer, "редът на осигурителя");
    assertNotNull(noi, "редът на ДОО");
    assertEquals(2, employer.getWorkDays().intValue(), "два работни дни за осигурителя");
    assertEquals(1, noi.getWorkDays().intValue(), "един за ДОО");
    assertEquals(MONTH.getYear(), employer.getPeriodYear().intValue(), "годината на листа");
    assertEquals(MONTH.getMonthValue(), employer.getPeriodMonth().intValue(), "месецът на листа");
    assertEquals(s.sheet.getFromDate(), employer.getFromDay(), "от началото на листа");
    assertEquals(s.sheet.getToDate(), noi.getToDay(), "до края му");

    BgSickLeave sheet = fresh(em -> em.find(BgSickLeave.class, s.sheet.getId()));
    assertEquals(
        LocalDate.now().withDayOfMonth(1),
        sheet.getBgExportMonth(),
        "месецът за Приложение № 9 е месецът на ПРЕДСТАВЯНЕ");
  }

  // ═══ (Б) ПРОВАЛ ПО СРЕДАТА ═══════════════════════════════════════════════

  @Test
  @DisplayName("(Б) Провал при последния запис → НИЩО не е записано, а следата в traceback я има")
  void afailureAtTheLastWriteLeavesNothingBehind() {
    Scenario s = scenario("B");
    Snapshot before = snapshot(s);

    ActionResponse response;
    FailingSickLeaveRepository.reached = false;
    FailingSickLeaveRepository.armed = true;
    try {
      response = press(s.sheet);
    } finally {
      FailingSickLeaveRepository.armed = false;
    }

    // ⚠️ СЪСТОЯНИЕТО СЕ ПРОВЕРЯВА ПЪРВО: без анотацията на apply точно тук излиза грешката —
    // заявката Отменен, без копие — а не по-общото „бравата не е стигната“.
    assertEquals(
        before,
        snapshot(s),
        "⚠️ НИЩО ОТ ПРЕДХОДНИТЕ СТЪПКИ НЕ Е ОСТАНАЛО — заявката, копието, периодът, историята на"
            + " корекцията, т. 9 и листът са както преди натискането. Заявката не остава Отменен"
            + " без копие.");
    assertTrue(
        FailingSickLeaveRepository.reached,
        "⚠️ ПРОВАЛЪТ Е НАКРАЯ, не в началото: бравата е при записа на месеца за НОИ, тоест след"
            + " отмяната на заявката, копието, периода в ADJUSTING, перата, recompute и записа на"
            + " копието. Не е ли стигнато дотук, свидетелят мери друго. Отговор: "
            + response.getData());
    assertTrue(
        String.valueOf(response.getData()).contains(INJECTED),
        "потребителят вижда грешката, не тишина: " + response.getData());

    LeaveRequest leave = fresh(em -> em.find(LeaveRequest.class, s.leave.getId()));
    assertEquals(
        LeaveRequestRepository.STATUS_VALIDATED, leave.getStatusSelect(), "заявката е валидирана");
    assertEquals(s.leaveComments, leave.getComments(), "и коментарът е непокътнат");
    assertTrue(copiesOf(s.original).isEmpty(), "копие няма");
    assertEquals(PeriodRepository.STATUS_CLOSED, periodStatus(s.period), "периодът е CLOSED");
    assertEquals(0L, adjustHistoryCount(s.period), "история на корекция не е започната");
    assertTrue(point9Of(s.sheet).isEmpty(), "т. 9 е празна");
    assertNull(
        fresh(em -> em.find(BgSickLeave.class, s.sheet.getId())).getBgExportMonth(),
        "месецът за НОИ е празен");
    assertEquals(
        1L,
        traceBackCount(INJECTED),
        "⚠️ СЛЕДАТА Е ЗАПИСАНА — тоест commit-ът на TraceBackService е минал СЛЕД rollback-а и не"
            + " е повлякъл нищо от натискането. Иначе този commit би записал отменената заявка.");
  }

  // ═══ (В) ВТОРО НАТИСКАНЕ СЛЕД УСПЕХ ══════════════════════════════════════

  @Test
  @DisplayName("(В) Второ натискане след успех → отказ с назования текст, нищо не мърда")
  void asecondPressAfterSuccessIsRefusedAndChangesNothing() {
    Scenario s = scenario("V");
    ActionResponse first = press(s.sheet);
    assertFalse(has(first, "error"), "първото натискане минава: " + first.getData());
    assertTrue(has(first, "view"), "и отваря копието: " + first.getData());
    Snapshot before = snapshot(s);

    ActionResponse second = press(s.sheet);

    assertTrue(has(second, "error"), "второто е отказ: " + second.getData());
    assertTrue(
        String.valueOf(second.getData()).contains(REFUSED_AGAIN), "с текста: " + second.getData());
    assertEquals(before, snapshot(s), "и НИЩО не е мръднало");
  }

  // ═══ (Г) ВЕЧЕ ЗАВЪРШЕНА КОРЕКЦИЯ НА СЪЩИЯ ОРИГИНАЛ ═══════════════════════

  @Test
  @DisplayName("(Г) Потвърдена корекция на същия оригинал → отказ, нищо не мърда")
  void anexistingConfirmedCorrectionIsRefusedAndChangesNothing() {
    Scenario s = scenario("G");
    JPA.runInTransaction(
        () -> {
          BgPaySlip done = new BgPaySlip();
          done.setEmploymentContract(JPA.em().find(EmploymentContract.class, s.contractId));
          done.setPeriod(JPA.em().find(Period.class, s.period.getId()));
          done.setReference("FISH-G-" + s.tag + "-К");
          done.setStatusSelect(BgPaySlipResolver.CONFIRMED);
          done.setBgIsCorrection(true);
          done.setBgCorrectionOf(JPA.em().find(BgPaySlip.class, s.original.getId()));
          JPA.em().persist(done);
        });
    Snapshot before = snapshot(s);

    ActionResponse response = press(s.sheet);

    assertTrue(has(response, "error"), "отказ: " + response.getData());
    assertTrue(
        String.valueOf(response.getData()).contains(REFUSED_AGAIN),
        "с текста: " + response.getData());
    assertEquals(before, snapshot(s), "и НИЩО не е мръднало");
    assertEquals(
        LeaveRequestRepository.STATUS_VALIDATED,
        fresh(em -> em.find(LeaveRequest.class, s.leave.getId())).getStatusSelect(),
        "заявката остава валидирана");
  }

  // ═══ (Д) ОТКАЗАН РАЗБИВ — ЛИЦЕ БЕЗ ДЕКЛАРИРАН СТАЖ ═══════════════════════

  @Test
  @DisplayName("(Д) Без деклариран стаж → отказ с причината, нищо не е отменено, копие няма")
  void anundeclaredInsuranceServiceRefusesAndChangesNothing() {
    Scenario s = scenario("D", false);
    Snapshot before = snapshot(s);

    ActionResponse response = press(s.sheet);

    assertTrue(has(response, "error"), "отказ, не тихо празно копие: " + response.getData());
    String text = String.valueOf(response.getData());
    assertTrue(text.contains("НЕ е отразен"), "казва, че не е отразено: " + text);
    assertTrue(text.contains("осигурителният стаж"), "казва причината: " + text);
    assertTrue(
        text.contains("Осигурителният стаж е деклариран"),
        "и какво да се попълни, с думите от екрана: " + text);
    assertEquals(
        before,
        snapshot(s),
        "⚠️ Заявката не се отменя и не се ражда копие без нито една промяна — нищо не мърда,"
            + " нито заявката, нито балансът, нито периодът.");
    assertEquals(
        LeaveRequestRepository.STATUS_VALIDATED,
        fresh(em -> em.find(LeaveRequest.class, s.leave.getId())).getStatusSelect(),
        "заявката остава валидирана");
    assertTrue(copiesOf(s.original).isEmpty(), "копие няма");
    assertEquals(PeriodRepository.STATUS_CLOSED, periodStatus(s.period), "периодът е CLOSED");
  }

  // ═══ (Е) БЕЗ РОЛЯ — ГЕЙТЪТ ═══════════════════════════════════════════════

  @Test
  @DisplayName(
      "(Е) Потребител без роля → шумен отказ, който назовава hr.payroll.write; нищо не мърда")
  void auserWithoutTheRoleIsRefusedLoudlyAndChangesNothing() {
    Scenario s = scenario("E");
    BgRoleLogin.logout();
    BgRoleLogin.login(s.company); // същото дружество, без роля
    Snapshot before = snapshot(s);

    ActionResponse response = press(s.sheet);

    String text = String.valueOf(response.getData());
    assertTrue(
        text.contains("apply an arrived sick note") && text.contains(BgRoleLogin.PAYROLL_WRITE),
        "отказът назовава действието и способността по каталога на ролите (Capabilities.PAYROLL_WRITE): "
            + text);
    assertEquals(before, snapshot(s), "и НИЩО не е мръднало");
    assertTrue(copiesOf(s.original).isEmpty(), "копие няма");
  }

  // ═══ НАТИСКАНЕТО ═════════════════════════════════════════════════════════

  /** Точно каквото прави {@code action-method}: контролерът от Guice, без транзакция. */
  private static ActionResponse press(BgSickLeave sheet) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", sheet.getId());
    context.put("_model", BgSickLeave.class.getName());
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(BgSickLeave.class.getName());
    request.setData(data);
    ActionResponse response = new ActionResponse();
    assertFalse(
        JPA.em().getTransaction().isActive(),
        "⚠️ Натискането тръгва БЕЗ транзакция, както в Axelor — иначе свидетелят е безсмислен");
    Beans.get(BgSickLeaveController.class).applyArrival(request, response);
    return response;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> valueOf(ActionResponse response, String key) {
    for (Object item : (List<Object>) response.getData()) {
      if (item instanceof Map && ((Map<String, Object>) item).containsKey(key)) {
        return (Map<String, Object>) ((Map<String, Object>) item).get(key);
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static boolean has(ActionResponse response, String key) {
    Object data = response.getData();
    if (!(data instanceof List)) {
      return false;
    }
    for (Object item : (List<Object>) data) {
      if (item instanceof Map && ((Map<String, Object>) item).containsKey(key)) {
        return true;
      }
    }
    return false;
  }

  private static void assertMoney(String expected, BigDecimal actual, String what) {
    assertMoney(new BigDecimal(expected), actual, what);
  }

  private static void assertMoney(BigDecimal expected, BigDecimal actual, String what) {
    assertNotNull(actual, what + ": липсва");
    assertEquals(
        0, expected.compareTo(actual), what + ": очаквано " + expected + ", намерено " + actual);
  }

  private static void assertAtStoredScale(String expected, BigDecimal actual, String what) {
    assertNotNull(actual, what + ": липсва");
    assertMoney(
        new BigDecimal(expected).setScale(actual.scale(), RoundingMode.HALF_UP), actual, what);
  }

  // ═══ ЧЕТЕНЕТО — ВИНАГИ ПРЕЗ ОТДЕЛЕН EntityManager ════════════════════════

  private static <T> T fresh(Function<EntityManager, T> read) {
    EntityManager em = JPA.em().getEntityManagerFactory().createEntityManager();
    try {
      return read.apply(em);
    } finally {
      em.close();
    }
  }

  private static List<BgPaySlip> copiesOf(BgPaySlip original) {
    return fresh(
        em -> {
          List<BgPaySlip> found =
              em.createQuery(
                      "SELECT s FROM BgPaySlip s WHERE s.bgCorrectionOf.id = :id ORDER BY s.id",
                      BgPaySlip.class)
                  .setParameter("id", original.getId())
                  .getResultList();
          for (BgPaySlip slip : found) {
            for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
              l.getBgPaySlipLineType().getCode(); // зарежда перата, докато EntityManager е отворен
            }
          }
          return found;
        });
  }

  private static int periodStatus(Period period) {
    return fresh(em -> em.find(Period.class, period.getId()).getStatusSelect());
  }

  private static long adjustHistoryCount(Period period) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT COUNT(h) FROM AdjustHistory h WHERE h.period.id = :id", Long.class)
                .setParameter("id", period.getId())
                .getSingleResult());
  }

  private static List<BgSickLeavePeriodLine> point9Of(BgSickLeave sheet) {
    return fresh(
        em ->
            em.createQuery(
                    "SELECT l FROM BgSickLeavePeriodLine l WHERE l.sickLeave.id = :id",
                    BgSickLeavePeriodLine.class)
                .setParameter("id", sheet.getId())
                .getResultList());
  }

  private static long traceBackCount(String marker) {
    return fresh(
        em ->
            em.createQuery("SELECT COUNT(t) FROM TraceBack t WHERE t.message LIKE :m", Long.class)
                .setParameter("m", "%" + marker + "%")
                .getSingleResult());
  }

  private static BgPaySlipLine line(BgPaySlip slip, String code) {
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (l.getBgPaySlipLineType() != null
          && code.equals(l.getBgPaySlipLineType().getCode())
          && l.getBgHealthBaseKind() == null) {
        return l;
      }
    }
    return null;
  }

  /**
   * Перото по код върху НОРМАЛНАТА база — 516 и 517 вече имат и редове по т. 5 и „само здравно“.
   */
  private static BgPaySlipLine standardLine(BgPaySlip slip, String code) {
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (l.getBgPaySlipLineType() != null
          && code.equals(l.getBgPaySlipLineType().getCode())
          && BgD1ContributionAggregator.BASE_STANDARD.equals(
              BgD1ContributionAggregator.healthBaseOf(l))) {
        return l;
      }
    }
    return null;
  }

  /**
   * НОРМАТА ПО КАЛЕНДАРА — понеделник до петък без официалните празници от {@code
   * bg_eventsPlanningLine.csv}. Тестовата база няма календар, а замразената норма на оригинала е
   * единственото, по което копието смята, тоест тя трябва да е истинската за месеца.
   */
  static int calendarNorm(YearMonth month) {
    java.util.Set<LocalDate> holidays = new java.util.HashSet<>();
    List<String> h = BgSeed.quotedHeader("bg_eventsPlanningLine.csv");
    for (String[] r : BgSeed.quotedRows("bg_eventsPlanningLine.csv")) {
      holidays.add(LocalDate.parse(BgSeed.col(h, r, "date")));
    }
    int n = 0;
    for (LocalDate d = month.atDay(1); !d.isAfter(month.atEndOfMonth()); d = d.plusDays(1)) {
      if (d.getDayOfWeek() != DayOfWeek.SATURDAY
          && d.getDayOfWeek() != DayOfWeek.SUNDAY
          && !holidays.contains(d)) {
        n++;
      }
    }
    return n;
  }

  /** Кодът на перото и ставката му в проценти — дословно сийда за вид 01, трета категория. */
  private static final String[][] EMPLOYEE_RATES = {
    {"pl_vn_pension_lice", "6.58"},
    {"pl_vn_upf_lice", "2.20"},
    {"pl_vn_ozm_lice", "1.40"},
    {"pl_vn_unemployment_lice", "0.40"},
    {"pl_vn_health_lice", "3.20"}
  };

  private static final String[][] EMPLOYER_RATES = {
    {"pl_vn_pension_osig", "8.22"},
    {"pl_vn_upf_osig", "2.80"},
    {"pl_vn_ozm_osig", "2.10"},
    {"pl_vn_unemployment_osig", "0.60"},
    {"pl_vn_health_osig", "4.80"}
  };

  /**
   * ⚠️ СЛЕД SALARY И ДВЕТЕ ТАБЛИЦИ СЪС СТАВКИ: статичните полета се пълнят по реда на
   * декларирането, а сметката ги чете.
   */
  private static final Expected E = new Expected(NORM);

  /** Сметката на ръка (javadoc-ът на класа) за нормата на месеца — същите закръгляния. */
  static final class Expected {
    final BigDecimal base;
    final Map<String, BigDecimal> lines = new LinkedHashMap<>();
    final BigDecimal personal;
    final BigDecimal employer;
    final BigDecimal tax;
    final BigDecimal net;
    final BigDecimal cost;
    final BigDecimal sick;
    final BigDecimal rate;
    final BigDecimal zzo;
    final BigDecimal zzoBase;
    final BigDecimal ins;
    final Map<String, BigDecimal> copyLines = new LinkedHashMap<>();
    final BigDecimal cPersonal;
    final BigDecimal cEmployer;
    final BigDecimal cTax;
    final BigDecimal cNet;
    final BigDecimal cCost;

    Expected(int norm) {
      BigDecimal n = BigDecimal.valueOf(norm);
      BigDecimal hundred = new BigDecimal("100");
      base = r2(SALARY.multiply(BigDecimal.valueOf(norm - 3)).divide(n, 10, RoundingMode.HALF_UP));
      personal = fill(lines, EMPLOYEE_RATES, base);
      employer = fill(lines, EMPLOYER_RATES, base);
      tax = r2(base.subtract(personal).multiply(BigDecimal.TEN).divide(hundred));
      net = base.subtract(personal).subtract(tax);
      cost = base.add(employer);
      sick =
          SALARY
              .multiply(new BigDecimal("70"))
              .multiply(new BigDecimal("2"))
              .divide(hundred.multiply(n), 2, RoundingMode.HALF_UP);
      rate = SALARY.divide(n, 4, RoundingMode.HALF_UP);
      zzo =
          new BigDecimal("620.20")
              .multiply(new BigDecimal("4.80"))
              .divide(hundred.multiply(n), 2, RoundingMode.HALF_UP);
      zzoBase = new BigDecimal("620.20").divide(n, 2, RoundingMode.HALF_UP);
      ins = base.add(sick);
      cPersonal = fill(copyLines, EMPLOYEE_RATES, ins);
      cEmployer = fill(copyLines, EMPLOYER_RATES, ins);
      cTax = r2(base.subtract(cPersonal).multiply(BigDecimal.TEN).divide(hundred));
      cNet = ins.subtract(cPersonal).subtract(cTax);
      cCost = ins.add(cEmployer).add(zzo);
    }

    /** Всяка вноска поотделно, закръглена (НЕВД чл. 2 ал. 4); връща сбора. */
    private static BigDecimal fill(Map<String, BigDecimal> out, String[][] rates, BigDecimal on) {
      BigDecimal sum = BigDecimal.ZERO;
      for (String[] r : rates) {
        BigDecimal amount = r2(on.multiply(new BigDecimal(r[1])).divide(new BigDecimal("100")));
        out.put(r[0], amount);
        sum = sum.add(amount);
      }
      return sum;
    }

    private static BigDecimal r2(BigDecimal x) {
      return x.setScale(2, RoundingMode.HALF_UP);
    }
  }

  private static BgPaySlipLine healthAbsenceLine(BgPaySlip slip) {
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (BgD1ContributionAggregator.BASE_ART40_1_5.equals(l.getBgHealthBaseKind())) {
        return l;
      }
    }
    return null;
  }

  /** Всичко, което натискането би могло да пипне — за „нищо не е мръднало“. */
  private static Snapshot snapshot(Scenario s) {
    return fresh(
        em -> {
          Snapshot out = new Snapshot();
          LeaveRequest leave = em.find(LeaveRequest.class, s.leave.getId());
          out.add("заявка: статус", leave.getStatusSelect());
          out.add("заявка: версия", leave.getVersion());
          out.add("заявка: коментар", leave.getComments());
          out.add(
              "заявка: събитие",
              leave.getIcalendarEvent() == null ? null : leave.getIcalendarEvent().getId());
          out.add("събитието съществува", em.find(ICalendarEvent.class, s.eventId) != null);
          LeaveLine balance = em.find(LeaveLine.class, s.leaveLineId);
          out.add("баланс: количество", balance.getQuantity().stripTrailingZeros());
          out.add("баланс: валидирани", balance.getDaysValidated().stripTrailingZeros());
          out.add("баланс: версия", balance.getVersion());
          BgSickLeave sheet = em.find(BgSickLeave.class, s.sheet.getId());
          out.add("лист: версия", sheet.getVersion());
          out.add("лист: месец за НОИ", sheet.getBgExportMonth());
          Period period = em.find(Period.class, s.period.getId());
          out.add("период: статус", period.getStatusSelect());
          out.add("период: версия", period.getVersion());
          out.add(
              "ретро-копия",
              em
                  .createQuery(
                      "SELECT s.id, s.version, s.statusSelect FROM BgPaySlip s"
                          + " WHERE s.bgCorrectionOf.id = :id OR s.bgDeletionOf.id = :id"
                          + " ORDER BY s.id",
                      Object[].class)
                  .setParameter("id", s.original.getId())
                  .getResultList()
                  .stream()
                  .map(Arrays::toString)
                  .collect(Collectors.toList()));
          out.add(
              "фишове на договора",
              em.createQuery(
                      "SELECT COUNT(s) FROM BgPaySlip s WHERE s.employmentContract.id = :id",
                      Long.class)
                  .setParameter("id", s.contractId)
                  .getSingleResult());
          out.add(
              "т. 9",
              em.createQuery(
                      "SELECT COUNT(l) FROM BgSickLeavePeriodLine l WHERE l.sickLeave.id = :id",
                      Long.class)
                  .setParameter("id", s.sheet.getId())
                  .getSingleResult());
          out.add(
              "история на корекции",
              em.createQuery(
                      "SELECT COUNT(h) FROM AdjustHistory h WHERE h.period.id = :id", Long.class)
                  .setParameter("id", s.period.getId())
                  .getSingleResult());
          return out;
        });
  }

  private static final class Snapshot {
    private final Map<String, Object> values = new LinkedHashMap<>();

    void add(String key, Object value) {
      values.put(key, value);
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof Snapshot && values.equals(((Snapshot) o).values);
    }

    @Override
    public int hashCode() {
      return values.hashCode();
    }

    @Override
    public String toString() {
      return values.toString();
    }
  }

  // ═══ ФИКСТУРАТА ══════════════════════════════════════════════════════════

  /** Едно лице, един затворен месец, един потвърден фиш, една заявка, един лист — по тест. */
  private static final class Scenario {
    String tag;
    Company company;
    Period period;
    Long contractId;
    LeaveRequest leave;
    String leaveComments;
    Long leaveLineId;
    Long eventId;
    BgSickLeave sheet;
    BgPaySlip original;
    List<BgPaySlipLine> originalLines;
  }

  private static Scenario scenario(String letter) {
    return scenario(letter, true);
  }

  private static Scenario scenario(String letter, boolean insuranceServiceDeclared) {
    Scenario s = new Scenario();
    s.tag = letter + System.nanoTime();
    YearMonth month = MONTH;
    // Втори понеделник → пн, вт, ср: три работни дни в един месец, далеч от 1 януари.
    LocalDate sickFrom =
        month.atDay(1).with(TemporalAdjusters.dayOfWeekInMonth(2, DayOfWeek.MONDAY));
    LocalDate sickTo = sickFrom.plusDays(2);

    JPA.runInTransaction(
        () -> {
          ReferenceData ref = referenceData();

          Company company = new Company();
          company.setName("Пристигане " + s.tag);
          company.setCode("PR" + s.tag);
          JPA.em().persist(company);
          // Копието на „Отрази пристигнал болничен“ иска номер от редицата на дружеството
          // (създава я BgTrzCompanySequenceListener при persist), а тя чете AppBase.
          BgTestApp.ensureAppBase();
          s.company = company;

          Year year = new Year();
          year.setName(month.getYear() + " " + s.tag);
          year.setCode("Y" + s.tag);
          year.setFromDate(LocalDate.of(month.getYear(), 1, 1));
          year.setToDate(LocalDate.of(month.getYear(), 12, 31));
          year.setCompany(company);
          year.setTypeSelect(YearRepository.TYPE_PAYROLL);
          JPA.em().persist(year);

          Period period = new Period();
          period.setName(
              String.format("%02d/%d %s", month.getMonthValue(), month.getYear(), s.tag));
          period.setFromDate(month.atDay(1));
          period.setToDate(month.atEndOfMonth());
          period.setYear(year);
          period.setStatusSelect(PeriodRepository.STATUS_CLOSED);
          JPA.em().persist(period);
          s.period = period;

          Partner partner = new Partner();
          partner.setName("Лице " + s.tag);
          partner.setCompany(company);
          JPA.em().persist(partner);
          Employee employee = new Employee();
          employee.setName("Лице " + s.tag);
          employee.setContactPartner(partner);
          employee.setBgLaborCategory("3");
          employee.setBgBornAfter1959(true); // кохорта с ДЗПО — ставките от сийда
          // 120 месеца стаж — както BgSickNoteArrivalMoneyTest. Без ДЕКЛАРИРАН стаж
          // BgSickLeaveResolver#eligibilityOf отказва разбива, а пристигането пак ражда копие.
          employee.setBgInsuranceServiceDeclared(insuranceServiceDeclared);
          employee.setBgInsuranceServiceMonths(insuranceServiceDeclared ? 120 : 0);
          // Стажът е КЪМ ДАТА — краят на месеца преди листа: месец за удостоверяване няма.
          employee.setBgInsuranceServiceAsOf(
              insuranceServiceDeclared ? MONTH.minusMonths(1).atEndOfMonth() : null);
          JPA.em().persist(employee);
          EmploymentContract contract = new EmploymentContract();
          contract.setEmployee(employee);
          contract.setPayCompany(company);
          contract.setBgInsuredTypeCode("01");
          contract.setBgHoursPerDay(new BigDecimal("8.00"));
          JPA.em().persist(contract);
          s.contractId = contract.getId();

          LeaveReason reason = new LeaveReason();
          reason.setName("Непредставен болничен " + s.tag);
          reason.setBgAbsenceType(ref.unsubmitted);
          // НЕ „изключителни дни“ — иначе стандартната отмяна не пипа баланса.
          reason.setLeaveReasonTypeSelect(LeaveReasonRepository.TYPE_SELECT_EVERY_YEAR);
          JPA.em().persist(reason);
          // Балансът СЛЕД валидирането на заявката: 3 дни са консумирани.
          LeaveLine balance = new LeaveLine();
          balance.setName(reason.getName());
          balance.setEmployee(employee);
          balance.setLeaveReason(reason);
          balance.setQuantity(BigDecimal.ZERO);
          balance.setDaysValidated(new BigDecimal("3"));
          balance.setDaysToValidate(BigDecimal.ZERO);
          JPA.em().persist(balance);
          s.leaveLineId = balance.getId();
          // Локално събитие (без отдалечен календар) — каквото валидирането на заявка ражда.
          ICalendarEvent event = new ICalendarEvent();
          event.setSubject(reason.getName() + " " + employee.getName());
          event.setStartDateTime(sickFrom.atTime(8, 0));
          event.setEndDateTime(sickTo.atTime(17, 0));
          event.setTypeSelect(4);
          JPA.em().persist(event);
          s.eventId = event.getId();
          LeaveRequest leave = new LeaveRequest();
          leave.setEmployee(employee);
          leave.setCompany(company);
          leave.setLeaveReason(reason);
          leave.setFromDateT(sickFrom.atStartOfDay());
          leave.setToDateT(sickTo.atTime(23, 59, 59));
          leave.setDuration(new BigDecimal("3"));
          leave.setInjectConsumeSelect(LeaveRequestRepository.SELECT_CONSUME);
          leave.setIcalendarEvent(event);
          leave.setStatusSelect(LeaveRequestRepository.STATUS_VALIDATED);
          leave.setBgIsHistoricalImport(false);
          leave.setComments("Въведена при затварянето на месеца.");
          JPA.em().persist(leave);
          s.leave = leave;
          s.leaveComments = leave.getComments();

          BgSickLeave sheet = new BgSickLeave();
          sheet.setEmploymentContract(contract);
          sheet.setBgAbsenceType(ref.sickEmployer);
          sheet.setFromDate(sickFrom);
          sheet.setToDate(sickTo);
          sheet.setBgIsContinuation(false);
          sheet.setBgDocumentNumber("БЛ-" + s.tag);
          sheet.setStatusSelect(BgPayrollInputBuilder.SICK_LEAVE_VALIDATED);
          sheet.setBgEmployerReceivedDate(LocalDate.now());
          JPA.em().persist(sheet);
          s.sheet = sheet;

          // Потвърденият фиш — перата и заглавията на ръчната сметка (javadoc на класа).
          BgPaySlip original = new BgPaySlip();
          original.setEmploymentContract(contract);
          original.setPeriod(period);
          original.setReference("FISH-" + s.tag);
          original.setStatusSelect(BgPaySlipResolver.CONFIRMED);
          original.setBgIsCorrection(false);
          original.setBgIsDeletion(false);
          original.setBgWorkingDaysInPeriod(NORM);
          original.setBgWorkedDays(NORM);
          original.setBgBaseSalary(SALARY);
          original.setBgLeaveDays161(BigDecimal.ZERO);
          original.setBgLeaveDays162(BigDecimal.ZERO);
          original.setBgLeaveDays163(BigDecimal.ZERO);
          original.setBgLeaveDays164(BigDecimal.ZERO);
          original.setBgLeaveDays165(BigDecimal.ZERO);
          original.setBgLeaveDays16A(BigDecimal.ZERO);
          original.setBgLeaveDaysOutside(new BigDecimal("3.00"));
          original.setBgLeaveDaysFrozenAt(month.atEndOfMonth().atStartOfDay());
          original.setBgNoOtherPayerIncome(true);
          original.setBgPaySlipLineList(new ArrayList<>());
          addLine(original, ref.salary, BgPaySlipResolver.CREDIT, E.base, true, true);
          for (Map.Entry<String, BigDecimal> c : E.lines.entrySet()) {
            BgPaySlipLineType type = ref.contributions.get(c.getKey());
            addLine(original, type, type.getBgSign(), c.getValue(), false, false);
          }
          addLine(original, ref.incomeTax, BgPaySlipResolver.DEBIT, E.tax, false, false);
          original.setBgGrossTotal(E.base);
          original.setBgInsuranceIncome(E.base);
          original.setBgTaxBase(E.base.subtract(E.personal));
          original.setBgTaxAmount(E.tax);
          original.setBgNetTotal(E.net);
          original.setBgEmployerCost(E.cost);
          JPA.em().persist(original);
          s.original = original;
          s.originalLines = new ArrayList<>(original.getBgPaySlipLineList());
        });
    // Натискането минава през CapabilityGate. Най-тясната роля по каталога на ролите, не
    // администратор.
    BgRoleLogin.login(s.company, BgRoleLogin.PAYROLL_WRITE);
    return s;
  }

  private static void addLine(
      BgPaySlip slip,
      BgPaySlipLineType type,
      String sign,
      BigDecimal amount,
      boolean insuranceIncome,
      boolean taxable) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgPaySlipLineType(type);
    line.setBgLineNumber(type.getNumber());
    line.setName(type.getName());
    line.setBgSign(sign);
    line.setBgIsInsuranceIncome(insuranceIncome);
    line.setBgIsTaxable(taxable);
    line.setBgContributionFund(type.getBgContributionFund());
    line.setBgAmount(amount);
    line.setPaySlip(slip);
    slip.getBgPaySlipLineList().add(line);
  }

  // ═══ СПРАВОЧНИТЕ ДАННИ — веднъж на схема, по кода от seed-а ═════════════

  private static final class ReferenceData {
    BgAbsenceType unsubmitted;
    BgAbsenceType sickEmployer;
    BgPaySlipLineType salary;
    BgPaySlipLineType incomeTax;

    /** Десетте типа пера на вноските — по код, дословно bg_pay_slip_line_type_contribution.csv. */
    final Map<String, BgPaySlipLineType> contributions = new LinkedHashMap<>();
  }

  /** Код на перо → номер, фонд и знак (bg_pay_slip_line_type_contribution.csv). */
  private static final Object[][] CONTRIBUTION_TYPES = {
    {"pl_vn_pension_osig", 500, "PENSION", BgPaySlipResolver.CREDIT},
    {"pl_vn_pension_lice", 501, "PENSION", BgPaySlipResolver.DEBIT},
    {"pl_vn_ozm_osig", 504, "OZM", BgPaySlipResolver.CREDIT},
    {"pl_vn_ozm_lice", 505, "OZM", BgPaySlipResolver.DEBIT},
    {"pl_vn_unemployment_osig", 506, "UNEMPLOYMENT", BgPaySlipResolver.CREDIT},
    {"pl_vn_unemployment_lice", 507, "UNEMPLOYMENT", BgPaySlipResolver.DEBIT},
    {"pl_vn_upf_osig", 510, "UPF", BgPaySlipResolver.CREDIT},
    {"pl_vn_upf_lice", 511, "UPF", BgPaySlipResolver.DEBIT},
    {"pl_vn_health_osig", 516, "HEALTH", BgPaySlipResolver.CREDIT},
    {"pl_vn_health_lice", 517, "HEALTH", BgPaySlipResolver.DEBIT}
  };

  /**
   * Фонд → ставка на осигурителя и на лицето, кохорта (bg_insurance_contribution.csv, IC-001..6).
   */
  private static final String[][] RATES_01 = {
    {"PENSION", "8.22", "6.58", "WITH_UPF"},
    {"UPF", "2.80", "2.20", "WITH_UPF"},
    {"OZM", "2.10", "1.40", "ANY"},
    {"UNEMPLOYMENT", "0.60", "0.40", "ANY"},
    {"HEALTH", "4.80", "3.20", "ANY"}
  };

  private static ReferenceData referenceData() {
    ReferenceData ref = new ReferenceData();
    ref.unsubmitted = absenceType("sicknote_unsubmitted");
    if (ref.unsubmitted != null) {
      ref.sickEmployer = absenceType("sick_er");
      ref.salary = lineType("pl_osnovna");
      ref.incomeTax = lineType("pl_dod");
      for (Object[] t : CONTRIBUTION_TYPES) {
        ref.contributions.put((String) t[0], lineType((String) t[0]));
      }
      return ref;
    }
    LocalDate since = LocalDate.of(2000, 1, 1);

    // bg_absence_type.csv, ред 21: извън осигуряването, без кофа в т. 16, категория „болничен“.
    BgAbsenceType unsubmitted = new BgAbsenceType();
    unsubmitted.setCode("sicknote_unsubmitted");
    unsubmitted.setName("Непредставен болничен");
    unsubmitted.setCategory(BgPayrollInputBuilder.ABSENCE_CATEGORY_SICK);
    unsubmitted.setIsPaid(false);
    unsubmitted.setPayer("none");
    unsubmitted.setCompensationPct(BigDecimal.ZERO);
    unsubmitted.setCountsAsLaborService(false);
    unsubmitted.setCountsAsInsuranceService(false);
    unsubmitted.setReducesWorkNorm(true);
    unsubmitted.setRequiresSickNote(true);
    unsubmitted.setActive(true);
    JPA.em().persist(unsubmitted);
    ref.unsubmitted = unsubmitted;

    // bg_absence_type.csv, ред 13: първите два работни дни за осигурителя, 70 на сто.
    BgAbsenceType sickEmployer = new BgAbsenceType();
    sickEmployer.setCode("sick_er");
    sickEmployer.setName("Болнични Р-л (общо заболяване)");
    sickEmployer.setCategory(BgPayrollInputBuilder.ABSENCE_CATEGORY_SICK);
    sickEmployer.setIsPaid(true);
    sickEmployer.setPayer("employer");
    sickEmployer.setCompensationPct(new BigDecimal("70"));
    sickEmployer.setCountsAsLaborService(true);
    sickEmployer.setCountsAsInsuranceService(true);
    sickEmployer.setReducesWorkNorm(true);
    sickEmployer.setRequiresSickNote(true);
    sickEmployer.setD1Code("16.А");
    sickEmployer.setActive(true);
    JPA.em().persist(sickEmployer);
    ref.sickEmployer = sickEmployer;

    Map<String, BgContributionFund> funds = new LinkedHashMap<>();
    for (String[] r : RATES_01) {
      BgContributionFund fund = new BgContributionFund();
      fund.setCode(r[0]);
      fund.setName(r[0]);
      fund.setActive(true);
      JPA.em().persist(fund);
      funds.put(r[0], fund);
    }

    // bg_pay_slip_line_type*.csv — кодът, номерът и знакът на всяко перо.
    ref.salary =
        newLineType(
            "pl_osnovna",
            100,
            "Основно възнаграждение",
            BgPaySlipResolver.CREDIT,
            true,
            true,
            null);
    newLineType(
        BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY,
        195,
        "Болничен от осигурител (чл. 40 ал. 5 КСО)",
        BgPaySlipResolver.CREDIT,
        true,
        false,
        null);
    for (Object[] t : CONTRIBUTION_TYPES) {
      ref.contributions.put(
          (String) t[0],
          newLineType(
              (String) t[0],
              (Integer) t[1],
              (String) t[0],
              (String) t[3],
              false,
              false,
              funds.get((String) t[2])));
    }
    ref.incomeTax =
        newLineType(
            "pl_dod", 700, "Данък по чл. 42 ЗДДФЛ", BgPaySlipResolver.DEBIT, false, false, null);

    // Ставките за вид 01, категория 3 — дословно сийда (ГВРС е законна нула и не ражда перо).
    for (String[] r : RATES_01) {
      BgInsuranceContribution row = new BgInsuranceContribution();
      row.setBgInsuredTypeCode("01");
      row.setLaborCategory("3");
      row.setFundCode(r[0]);
      row.setBgContributionFund(funds.get(r[0]));
      row.setEmployerRate(new BigDecimal(r[1]));
      row.setEmployeeRate(new BigDecimal(r[2]));
      row.setBgEmployerRateKind("VALUE");
      row.setBgEmployeeRateKind("VALUE");
      row.setBgCompensationTypeCode("STANDARD");
      // Веригата на вноските търси реда по кохорта, обхват и учителство — както в сийда.
      row.setBgUpfRegime(r[3]);
      row.setBgSolScope("ANY");
      row.setBgTeacherScope("ANY");
      row.setValidFrom(since);
      JPA.em().persist(row);
    }

    parameter(BgSickLeaveService.PARAM_HEALTH_ABSENCE_BASE, "620.2000", since);
    parameter("INCOME_TAX_RATE", "10.0000", since);
    // Копието минава през ИСТИНСКАТА верига на вноските — видът осигурен и таванът се четат.
    // МОД не важи за фикстурата (bgAppliesMod = false): заплатата е над всеки под, а таблицата по
    // НКИД/НКПД не е предмет на теста.
    parameter("MAX_INSURABLE_INCOME", "2300.0000", since);
    BgInsuredType employee01 = new BgInsuredType();
    employee01.setCode("01");
    employee01.setName("Работници и служители по трудово правоотношение");
    employee01.setValidFrom(since);
    employee01.setBgAppliesMod(false);
    JPA.em().persist(employee01);
    return ref;
  }

  private static BgAbsenceType absenceType(String code) {
    List<BgAbsenceType> found =
        JPA.em()
            .createQuery("SELECT t FROM BgAbsenceType t WHERE t.code = :c", BgAbsenceType.class)
            .setParameter("c", code)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  private static BgPaySlipLineType lineType(String code) {
    return JPA.em()
        .createQuery("SELECT t FROM BgPaySlipLineType t WHERE t.code = :c", BgPaySlipLineType.class)
        .setParameter("c", code)
        .getSingleResult();
  }

  private static BgPaySlipLineType newLineType(
      String code,
      int number,
      String name,
      String sign,
      boolean insuranceIncome,
      boolean taxable,
      BgContributionFund fund) {
    BgPaySlipLineType type = new BgPaySlipLineType();
    type.setCode(code);
    type.setNumber(number);
    type.setName(name);
    type.setBgSign(sign);
    type.setBgIsInsuranceIncome(insuranceIncome);
    type.setBgIsTaxable(taxable);
    type.setBgFormsGrossWage(number == 100);
    type.setBgContributionFund(fund);
    type.setActive(true);
    JPA.em().persist(type);
    return type;
  }

  private static void parameter(String code, String value, LocalDate since) {
    BgPayrollParameter p = new BgPayrollParameter();
    p.setCode(code);
    p.setParamValue(new BigDecimal(value));
    p.setValidFrom(since);
    JPA.em().persist(p);
  }
}
