package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.hr.db.LeaveRequest;
import com.axelor.apps.hr.db.repo.LeaveRequestRepository;
import com.axelor.apps.hr.service.leave.LeaveRequestCancelService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.persist.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import javax.persistence.NoResultException;

/**
 * ПРИСТИГНАЛИЯТ БОЛНИЧЕН ЗА ВЕЧЕ ЗАТВОРЕН МЕСЕЦ — предложение към оператора, не тихо местене.
 *
 * <p><b>СЛУЧАЯТ.</b> Лицето е отсъствало, фишът за месеца е направен и потвърден, месецът е
 * затворен, а дните са отишли като „непредставен болничен“: без заплащане, извън осигуряването, с
 * прекъсване по КСО чл. 10 ал. 2 в т. 14/15. После листът пристига. Дните трябва да станат болнични
 * — първите два работни дни за сметка на осигурителя по <b>чл. 40 ал. 5 КСО</b>, останалите за
 * сметка на ДОО, — а това мени заплатата, осигуряването, данъка и кофите на т. 16.
 *
 * <p><b>⚠️ МОДЕЛЪТ Е „ПРЕДЛОЖЕНИЕ“, НЕ „АВТОМАТИЧНО“.</b> Услугата ПОДГОТВЯ коригиращото копие и
 * спира: не го потвърждава, не мести пари и не подава нищо. Потвърждаването е отделно човешко
 * натискане по редовния път ({@code BgPaySlipService#confirm}), и чак то слага нетната разлика в
 * текущия отворен фиш. Пълната автоматика би трябвало да мести период в ADJUSTING, да ражда
 * счетоводна статия и да изпреварва ключ (Б) — три стъпки, които по проект са човешки.
 *
 * <p><b>⚠️ И ЗАЩО ВРАТАТА Е ОТ ЛИСТА, А НЕ ОТ ФИША.</b> Човекът държи пристигналия документ в ръка;
 * той не знае наизуст кой месец е засегнат, а бутонът „Коригирай“ живее на фиша и иска операторът
 * сам да го намери. Тук листът намира фиша.
 *
 * <p><b>⚠️ КЛЮЧ (Б) НЕ Е ПРЕЧКА, А ПРИЧИНАТА.</b> {@code BgPaySlipResolver#refuseLockedPeriod}
 * връща {@code null} за ретро-фиш — подадената Д1/Д6 заключва РЕДОВНИЯ път и сочи корекцията като
 * изход. Тоест заключен период НЕ отказва този поток; той го прави единствения.
 *
 * <p>⚠️ Импортът на е-болничен от НОИ е извън обхвата: листът се въвежда на ръка както досега.
 */
public class BgSickNoteArrivalService {

  /** {@code hrs.leave.request.status.select}: 3 = валидирана. Огледално на сглобителя. */
  protected static final int LEAVE_VALIDATED = BgPayrollInputBuilder.LEAVE_VALIDATED;

  // ═══ ПЛАНЪТ ══════════════════════════════════════════════════════════════

  /**
   * КАКВО ЩЕ СЕ СЛУЧИ — текст за оператора, или назована причина да не се случи.
   *
   * <p>⚠️ Не пипа НИЩО. Всеки отказ тук е преди да се е родило копие, преди период да е местен и
   * преди заявка да е отменена — тоест спрян случай не е струвал нищо.
   */
  public String plan(BgSickLeave sickLeave) {
    String refusal = refusalFor(sickLeave);
    if (refusal != null) {
      return refusal;
    }
    BgPaySlip original = originalSlipFor(sickLeave);
    return BgSickNoteArrivalRules.plan(
        sickLeave.getBgDocumentNumber(),
        sickLeave.getFromDate(),
        sickLeave.getToDate(),
        original.getPeriod() == null ? null : original.getPeriod().getName(),
        cancellableLeave(sickLeave),
        exportMonthFor(sickLeave),
        paySlipService().fillPreview(original));
  }

  /** Отказът, или {@code null}. ⚠️ Един списък, един ред — планът и действието питат ЕДНО. */
  public String refusalFor(BgSickLeave sickLeave) {
    if (sickLeave == null || sickLeave.getId() == null) {
      return "Отразява се ЗАПИСАН болничен лист — запишете този първо.";
    }
    if (!BgPayrollInputBuilder.SICK_LEAVE_VALIDATED.equals(sickLeave.getStatusSelect())) {
      return "Болничният лист е в състояние „"
          + sickLeave.getStatusSelect()
          + "“. Отразява се само ВАЛИДИРАН лист: черновата е нечие намерение, а не документ, и"
          + " ведомостта също чете само валидирани (виж BgPayrollInputBuilder#sickLeaveEpisodesOf).";
    }
    String dates =
        BgSickNoteArrivalRules.refuseSpansTwoMonths(sickLeave.getFromDate(), sickLeave.getToDate());
    if (dates != null) {
      return dates;
    }
    String received =
        BgSickNoteArrivalRules.refuseReceivedDate(
            sickLeave.getBgEmployerReceivedDate(), sickLeave.getFromDate(), today());
    if (received != null) {
      return received;
    }
    BgPaySlip original = originalSlipFor(sickLeave);
    if (original == null) {
      return "За периода на болничния лист НЯМА потвърден фиш по този трудов договор. Отразяването"
          + " върху затворен месец тръгва от СЪЩЕСТВУВАЩ фиш — ако месецът още не е смятан,"
          + " направете фиша по редовния път и болничният ще влезе в него сам.";
    }
    // ⚠️ ПРЕДИ refuseRetroPeriod, и редът има значение: веднага след успешно отразяване периодът е
    // ADJUSTING, а „ВЕЧЕ е под корекция“ не казва, че ТОЗИ лист вече е в копието.
    String reflected =
        BgSickNoteArrivalRules.refuseExistingRetro(
            original.getReference(),
            original.getPeriod() == null ? null : original.getPeriod().getName(),
            existingRetroOf(original));
    if (reflected != null) {
      return reflected;
    }
    String source = BgPaySlipResolver.refuseRetroSource(original, false);
    if (source != null) {
      return source;
    }
    String period = BgPaySlipResolver.refuseRetroPeriod(original.getPeriod(), false);
    if (period != null) {
      return period;
    }
    String deadline = BgPaySlipResolver.refuseRetroDeadline(original.getPeriod(), today(), false);
    if (deadline != null) {
      return deadline;
    }
    return BgSickNoteArrivalRules.refuseInexactCover(
        sickLeave.getFromDate(),
        sickLeave.getToDate(),
        cancellableLeave(sickLeave),
        sickLeave.getBgDocumentNumber());
  }

  // ═══ ДЕЙСТВИЕТО ══════════════════════════════════════════════════════════

  /**
   * ОТРАЗЯВА ЛИСТА: коригиращо копие с новия разбив, отменена заявка, месец за НОИ.
   *
   * <p>⚠️ Редовете по т. 9 се пишат от разбива ({@code applyPeriodLines}) — по месеци, върху
   * пристигналия лист, в същата транзакция като копието.
   *
   * <p><b>Редът на стъпките е правило, не подредба.</b>
   *
   * <ol>
   *   <li><b>Отказите ПЪРВИ</b> — преди копие, преди преместен период, преди отменена заявка.
   *   <li><b>Заявката се отменя ПРЕДИ сглобяването на входа</b>, и това е задължително: докато тя
   *       стои валидирана редом с листа, {@code BgPayrollInputBuilder#sickNoteArrivalClash} отказва
   *       ЦЕЛИЯ вход (този отказ пази ръчния път) и копието би останало без дни.
   *   <li><b>Копието СЛЕД нея</b> — {@code correct()} мести периода в ADJUSTING последен.
   *   <li><b>Разбивът и сумите НАКРАЯ</b> — {@code recompute} чете перата, които разбивът току-що е
   *       написал. Отказана присъда на разбива спира ЦЯЛОТО действие ({@code
   *       BgSickNoteArrivalRules#refuseOutcomes}).
   * </ol>
   *
   * <p><b>⚠️ ВСИЧКО ИЛИ НИЩО — ЕДНА ТРАНЗАКЦИЯ, И ТЯ Е ТУК, А НЕ В КОНТРОЛЕРА.</b> Axelor не отваря
   * транзакция за {@code action-method}. Без анотацията първият {@code save} (отмяната на заявката)
   * прави flush извън транзакция и хвърля „no transaction is in progress“. После {@code
   * TraceBackService.trace} в контролера commit-ва мръсното, и заявката остава отменена без копие.
   * {@code correct()} е {@code @Transactional} сам и тук се ПРИСЪЕДИНЯВА (guice-persist пуска
   * вложения метод в активната транзакция), тоест не commit-ва отделно. При изключение rollback-ът
   * връща всичко, а Hibernate изчиства контекста, тоест следата в контролера не може да влачи нищо
   * след себе си. Свидетели: {@code BgSickNoteArrivalAtomicityTest} (през истинския контролер,
   * {@code integrationTest}) и {@code BgSickNoteArrivalTransactionGuardTest} (анотацията, в
   * обикновения {@code test}).
   *
   * <p>⚠️ Работи САМО защото услугата идва от Guice ({@code Beans.get} в контролера). Създадена с
   * {@code new}, анотацията мълчи.
   *
   * <p><b>⚠️ ОРИГИНАЛЪТ НЕ СЕ ПИПА.</b> Нито едно от действията тук не докосва потвърдения фиш:
   * числата му са замразени и Д1 за него е подадена. Отмяната на заявката обаче маха реда от ЖИВИТЕ
   * прочити, тоест т. 14/15 на ОРИГИНАЛА, ако някой ги построи наново, вече няма да носят
   * прекъсване — и това е ВЯРНО след пристигането (дните вече се зачитат за осигурителен стаж), но
   * е и обявена граница: интервалите не са замразени никъде.
   */
  @Transactional(rollbackOn = {Exception.class})
  public BgPaySlip apply(BgSickLeave sickLeave) {
    String refusal = refusalFor(sickLeave);
    if (refusal != null) {
      throw new IllegalStateException(refusal);
    }
    BgPaySlip original = originalSlipFor(sickLeave);

    cancelCoveredLeave(sickLeave);

    BgPaySlip copy;
    try {
      copy = paySlipService().correct(original);
    } catch (Exception e) {
      throw new IllegalStateException(
          "Коригиращото копие не можа да се отвори: " + e.getMessage(), e);
    }

    // ⚠️ ЗАВАРЕН ОРИГИНАЛ (3А): режимът и дните на карва се попълват от текущите данни ТУК, преди
    // разбива и пресмятането — като част от изричното действие. Планът ги е показал преди OK, а
    // следата казва повода. Без тях копието не се смята (BgPayrollRegime#missingRefusal), а друго
    // място, където човек да натисне „Попълни“, няма: копието се ражда и смята в тази транзакция.
    if (!paySlipService().fillPreview(original).isEmpty()) {
      try {
        paySlipService().fillFromCurrent(copy, currentUser(), fillReason(sickLeave));
      } catch (AxelorException e) {
        throw new IllegalStateException(
            "Режимът на копието не можа да се попълни: " + e.getMessage(), e);
      }
    }

    List<BgSickLeaveOutcome> outcomes =
        sickLeaveService().applyArrival(copy, paySlipService().payrollInputFor(copy));
    // ⚠️ ОТКАЗАНА ПРИСЪДА СПИРА ВСИЧКО — никога тихо. Иначе без деклариран стаж разбивът се
    // отказва, а заявката пак се отменя и се ражда копие без нито една промяна. Изключението връща
    // транзакцията — нищо не е отменено, копие няма.
    String refused =
        BgSickNoteArrivalRules.refuseOutcomes(
            sickLeave.getBgDocumentNumber(), outcomes, insuranceServiceHint(sickLeave));
    if (refused != null) {
      throw new IllegalStateException(refused);
    }
    // ⚠️ calculate, А НЕ recompute: копието получава производните пера — вноските върху 195,
    // перо 516 за дните ДОО, данъка — и отпечатък. Само сборовете (recompute) оставят 195 в
    // осигурителния доход с вноските на оригинала. В същата транзакция: отказ тук
    // връща и отменената заявка, и копието.
    paySlipService().calculate(copy);
    saveSlip(copy);

    applyExportMonth(sickLeave);
    return copy;
  }

  /**
   * ОТМЕНЯ — не трие, и то по СТАНДАРТНИЯ път на AOS. Статусът пада на „Отменен“, а следата остава
   * в коментара.
   *
   * <p><b>⚠️ ПРЕЗ {@code LeaveRequestCancelService}, А НЕ С ГОЛ СТАТУС.</b> Пряката смяна на
   * статуса прескача {@code manageCancelLeaves}: консумираните дни не се връщат в баланса на {@code
   * LeaveLine}, а календарното събитие остава. Стандартната отмяна е {@code @Transactional} и се
   * присъединява към транзакцията на {@link #apply}, тоест при провал по-късно и балансът, и
   * събитието се връщат. В Guice тя е {@code BgHistoricalLeaveGuardCancelService}, който отказва
   * исторически внесени заявки — те и без това не стигат дотук ({@link #cancellableLeave}).
   *
   * <p>⚠️ ИЗВЕСТНО ОГРАНИЧЕНИЕ: при синхронизиран отдалечен календар (CalDAV — потребител с
   * календар и събитие с uid) AOS трие събитието и ОТДАЛЕЧЕНО, а това rollback не връща. Повторното
   * натискане не блокира: липсващо отдалечено събитие е no-op, а грешка на връзката отива в
   * TraceBack ({@code ICalendarEventManagementRepository#remove}).
   *
   * <p><b>⚠️ СТАТУС, А НЕ ИЗТРИВАНЕ, И ПРЕПРАТКА БЕЗ НОВА КОЛОНА.</b> Изтритата заявка не оставя
   * нищо: утре никой не може да каже защо дните на лицето са се променили. Отменената излиза от
   * всичките четири живи прочита ({@code statusSelect = 3} е филтърът навсякъде) и остава видима на
   * екрана на отпуските. Препратката към листа отива в {@code comments} — съществуващо поле —
   * защото нова колона тук би била схема за една следа.
   */
  protected void cancelCoveredLeave(BgSickLeave sickLeave) {
    for (BgSickNoteArrivalRules.LeaveSpan span : cancellableLeave(sickLeave)) {
      LeaveRequest request = leaveRequestById(span.id);
      if (request == null) {
        continue;
      }
      // Коментарът се пише ПРЕДИ отмяната — стандартната отмяна записва заявката.
      request.setComments(
          (request.getComments() == null ? "" : request.getComments() + "\n")
              + "Отменена автоматично на "
              + BgSickNoteArrivalRules.day(today())
              + ": пристигна болничен лист "
              + (sickLeave.getBgDocumentNumber() == null
                      || sickLeave.getBgDocumentNumber().trim().isEmpty()
                  ? "без номер"
                  : "№ " + sickLeave.getBgDocumentNumber().trim())
              + " за "
              + BgSickNoteArrivalRules.day(sickLeave.getFromDate())
              + " – "
              + BgSickNoteArrivalRules.day(sickLeave.getToDate())
              + ", който покрива същите дни. Записът НЕ е изтрит, за да остане следата.");
      try {
        cancelLeaveRequest(request);
      } catch (AxelorException e) {
        throw new IllegalStateException(
            "Заявката за отпуск "
                + span
                + " не може да бъде отменена: "
                + e.getMessage()
                + " Болничният НЕ е отразен — нищо не е отменено и копие не е създадено.",
            e);
      }
    }
  }

  /**
   * НАСОКАТА КЪМ ОПЕРАТОРА, когато стажът не е деклариран — с думите от формата на служителя.
   *
   * <p>⚠️ Без деклариран стаж сглобителят подава {@code null} („не знам“) и разбивът по чл. 40 ал.
   * 5 КСО се отказва; текстът на резолвера назовава полето на модела, не на екрана.
   */
  protected String insuranceServiceHint(BgSickLeave sickLeave) {
    EmploymentContract contract = sickLeave == null ? null : sickLeave.getEmploymentContract();
    Employee employee = contract == null ? null : contract.getEmployee();
    if (employee == null || Boolean.TRUE.equals(employee.getBgInsuranceServiceDeclared())) {
      return null;
    }
    return "Попълнете на служителя „Осигурителният стаж е деклариран“ и „Признат осигурителен"
        + " стаж (месеци)“.";
  }

  /**
   * МЕСЕЦЪТ ЗА ПРИЛОЖЕНИЕ № 9 — попълва се САМО ако е празен.
   *
   * <p>⚠️ Попълненото е нечие решение и не се презаписва: полето съществува точно за да може човек
   * да насочи закъснелия лист към друг пакет. Празното значи „води се по периода си“ — а за
   * закъснял лист това е грешният месец, затова тук се предлага месецът на ПРЕДСТАВЯНЕ.
   */
  protected void applyExportMonth(BgSickLeave sickLeave) {
    if (sickLeave.getBgExportMonth() != null) {
      return;
    }
    LocalDate month = exportMonthFor(sickLeave);
    if (month == null) {
      return;
    }
    sickLeave.setBgExportMonth(month);
    saveSickLeave(sickLeave);
  }

  protected LocalDate exportMonthFor(BgSickLeave sickLeave) {
    LocalDate received = sickLeave.getBgEmployerReceivedDate();
    return received == null ? null : received.withDayOfMonth(1);
  }

  // ═══ ЧЕТЕНЕТО ОТ БАЗАТА — ШЕВОВЕ ═════════════════════════════════════════

  /**
   * ФИШЪТ НА ОНЗИ МЕСЕЦ — по договор и по ПЕРИОД, който съдържа началото на листа.
   *
   * <p>⚠️ Търси се по {@code fromDate} на ЛИСТА, а не по днешната дата: пристигането е сега,
   * отсъствието е било тогава. И само по СОБСТВЕНИЯ договор — лице с два договора има два фиша за
   * същия месец и листът поражда епизод за всеки от тях поотделно (чл. 40 ал. 5 иска всеки
   * осигурител да плати своите два дни).
   *
   * <p>⚠️ Ретро-фишовете се изключват: коригиращото копие носи СЪЩИЯ период и без филтъра заявката
   * би могла да върне него вместо оригинала.
   */
  protected BgPaySlip originalSlipFor(BgSickLeave sickLeave) {
    EmploymentContract contract = sickLeave.getEmploymentContract();
    if (contract == null || contract.getId() == null || sickLeave.getFromDate() == null) {
      return null;
    }
    try {
      return JPA.em()
          .createQuery(
              "SELECT s FROM BgPaySlip s"
                  + " WHERE s.employmentContract.id = :contract"
                  + " AND s.period.fromDate <= :day AND s.period.toDate >= :day"
                  + " AND s.bgIsCorrection = FALSE AND s.bgIsDeletion = FALSE"
                  + " AND s.statusSelect IN ('CONFIRMED', 'PAID')"
                  + " ORDER BY s.id",
              BgPaySlip.class)
          .setParameter("contract", contract.getId())
          .setParameter("day", sickLeave.getFromDate())
          .setMaxResults(1)
          .getSingleResult();
    } catch (NoResultException e) {
      return null;
    }
  }

  /**
   * РЕТРО-КОПИЯТА, КОИТО ВЕЧЕ СОЧАТ КЪМ ОРИГИНАЛА — коригиращи и заличаващи, без ОТКАЗАНИТЕ.
   *
   * <p>⚠️ Отказаното копие се изключва нарочно: {@code BgPaySlipService#cancel} връща периода в
   * CLOSED, тоест операторът е отхвърлил корекцията и повторното отразяване е законният изход.
   * Заявката вече е отменена, а попълненият месец за НОИ не се пипа.
   */
  protected List<BgSickNoteArrivalRules.RetroCopy> existingRetroOf(BgPaySlip original) {
    List<BgSickNoteArrivalRules.RetroCopy> out = new ArrayList<>();
    if (original == null || original.getId() == null) {
      return out;
    }
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT s.reference, s.statusSelect, s.bgIsDeletion FROM BgPaySlip s"
                    + " WHERE (s.bgCorrectionOf = :original OR s.bgDeletionOf = :original)"
                    + " AND s.statusSelect <> :cancelled"
                    + " ORDER BY s.id",
                Object[].class)
            .setParameter("original", original)
            .setParameter("cancelled", BgPaySlipResolver.CANCELLED)
            .getResultList();
    for (Object[] row : rows) {
      out.add(
          new BgSickNoteArrivalRules.RetroCopy(
              (String) row[0], (String) row[1], Boolean.TRUE.equals(row[2])));
    }
    return out;
  }

  /**
   * ЗАЯВКИТЕ, КОИТО ЛИСТЪТ ЗАМЕСТВА — валидирани, от вид ИЗВЪН осигуряването без своя кофа.
   *
   * <p>⚠️ Признакът е СТРУКТУРЕН, не по код: „празна кофа в т. 16 И не се зачита за осигурителен
   * стаж“ е същият предикат, по който сглобителят повишава деня в кофата „други“ ({@code
   * BgD1DayBuckets#outsideInsurance}). Днес това е точно един вид в речника — {@code
   * sicknote_unsubmitted}, — но списъкът НЕ се заковава тук: вид, добавен утре със същата форма,
   * описва същото твърдение („болничният не е представен“) и трябва да бъде заместен от листа по
   * същия начин.
   *
   * <p>⚠️ Историческите редове се изключват — {@code BgHistoricalLeaveFilter} важи и тук: внесеното
   * от миграция не се пипа.
   */
  protected List<BgSickNoteArrivalRules.LeaveSpan> cancellableLeave(BgSickLeave sickLeave) {
    List<BgSickNoteArrivalRules.LeaveSpan> out = new ArrayList<>();
    EmploymentContract contract = sickLeave.getEmploymentContract();
    Employee employee = contract == null ? null : contract.getEmployee();
    if (employee == null
        || employee.getId() == null
        || sickLeave.getFromDate() == null
        || sickLeave.getToDate() == null) {
      return out;
    }
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT l.id, l.fromDateT, l.toDateT, l.leaveReason.name"
                    + " FROM LeaveRequest l"
                    + " WHERE l.employee.id = :employee"
                    + " AND l.statusSelect = :validated"
                    + " AND l.bgIsHistoricalImport = FALSE"
                    + " AND l.leaveReason.bgAbsenceType IS NOT NULL"
                    + " AND (l.leaveReason.bgAbsenceType.d1Code IS NULL"
                    + "      OR l.leaveReason.bgAbsenceType.d1Code = '')"
                    + " AND l.leaveReason.bgAbsenceType.countsAsInsuranceService = FALSE"
                    + " AND l.leaveReason.bgAbsenceType.category = :sickCategory"
                    + " AND l.fromDateT <= :toEnd AND l.toDateT >= :fromStart"
                    + " ORDER BY l.fromDateT, l.id",
                Object[].class)
            .setParameter("employee", employee.getId())
            .setParameter("validated", LEAVE_VALIDATED)
            .setParameter("sickCategory", BgPayrollInputBuilder.ABSENCE_CATEGORY_SICK)
            .setParameter("fromStart", sickLeave.getFromDate().atStartOfDay())
            .setParameter("toEnd", sickLeave.getToDate().atTime(23, 59, 59))
            .getResultList();

    for (Object[] row : rows) {
      out.add(
          new BgSickNoteArrivalRules.LeaveSpan(
              (Long) row[0],
              row[1] == null ? null : ((LocalDateTime) row[1]).toLocalDate(),
              row[2] == null ? null : ((LocalDateTime) row[2]).toLocalDate(),
              (String) row[3]));
    }
    return out;
  }

  protected LeaveRequest leaveRequestById(Long id) {
    return id == null ? null : Beans.get(LeaveRequestRepository.class).find(id);
  }

  /** Стандартната отмяна на AOS — свой шев, за да вървят пазачите в unit тестовете без база. */
  protected void cancelLeaveRequest(LeaveRequest request) throws AxelorException {
    Beans.get(LeaveRequestCancelService.class).cancel(request);
  }

  protected void saveSickLeave(BgSickLeave sickLeave) {
    Beans.get(BgSickLeaveRepository.class).save(sickLeave);
  }

  protected void saveSlip(BgPaySlip slip) {
    Beans.get(BgPaySlipRepository.class).save(slip);
  }

  protected BgPaySlipService paySlipService() {
    return Beans.get(BgPaySlipService.class);
  }

  protected BgSickLeaveService sickLeaveService() {
    return Beans.get(BgSickLeaveService.class);
  }

  /** Поводът в одита на попълнения режим — „при отразяване на болничен лист № …“ (3А). */
  protected String fillReason(BgSickLeave sickLeave) {
    String number = sickLeave.getBgDocumentNumber();
    return "при отразяване на болничен лист "
        + (number == null || number.trim().isEmpty() ? "без номер" : "№ " + number.trim());
  }

  /** Потребителят за одита — шев: в свидетелите без сесия той е {@code null}. */
  protected User currentUser() {
    try {
      return AuthUtils.getUser();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** Днешната дата през шев — за да е измерима от свидетел. */
  protected LocalDate today() {
    return LocalDate.now();
  }
}
