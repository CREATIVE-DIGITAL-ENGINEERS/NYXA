package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgEmployeeChild;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import com.axelor.apps.bglocalization.db.repo.BgApp9DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgEmployeeChildRepository;
import com.axelor.apps.bglocalization.service.payroll.BgHistoricalLeaveFilter;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import com.axelor.apps.bglocalization.service.payroll.BgSickBenefitVerdictRules;
import com.axelor.apps.bglocalization.service.payroll.BgSickBenefitVerdictService;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * СГЛОБИТЕЛЯТ И — ЗАДЪЛЖИТЕЛНО — КОНСУМАТОРЪТ НА ПРИСЪДИТЕ.
 *
 * <p><b>Двете му задачи.</b> Първо, събира {@link BgApp9Facts} от базата, за да може {@link
 * BgApp9ObstoyatelstvaResolver} да остане чиста функция. Второ — и това е причината класът да
 * съществува отделно — ЗАПИСВА върнатите присъди в {@code BgApp9Declaration.resolverNotes}.
 *
 * <p><b>⚠️ ЗАЩО ВТОРОТО Е ПОДЧЕРТАНО.</b> Правило, което връща назован отказ, е безполезно, ако
 * повикващият пренебрегне върнатия {@code List<Outcome>}: енджинът отказва точно както трябва, а
 * операторът никога не научава защо перото липсва. Тук това е невъзможно по построение: {@link
 * #resolveInto} е ЕДИНСТВЕНИЯТ път до стойностите и той пише присъдите в същата транзакция, в която
 * пише и тях. Генераторите на фиша имат свой консуматор по образеца на този клас — {@code
 * BgPaySlipVerdicts}.
 *
 * <p><b>⚠️ НУЛА ИЗНОС.</b> Нито файл, нито XML, нито XSD, нито подаване към НОИ — това е Ф2.
 *
 * <p><b>⚠️ ПАРИТЕ НЕ СЕ ПИПАТ.</b> Нито един ред тук не докосва фиш, перо или редовете по т. 9:
 * {@code BgSickLeavePeriodLine} се чете САМО за да се преброи (свидетелят на т. 9) и никога не се
 * пише. Инвариантът на Ф0 стои.
 */
@Singleton
public class BgApp9DeclarationService {

  /**
   * Валидирана заявка за отпуск. Същата стойност и по същия довод като в {@code
   * BgUnpaidLeaveHealthService}: трети различен филтър над едни и същи заявки би дал трети отговор.
   */
  protected static final int LEAVE_VALIDATED = 3;

  /** Състоянията, в които удостоверение се смята за ПОДАДЕНО — гейтът на т. 26. */
  protected static final String STATUS_SUBMITTED = "SUBMITTED";

  /** Състоянието след успешно извеждане. */
  protected static final String STATUS_RESOLVED = "RESOLVED";

  /** Отказът за невалидиран лист — един текст за екрана и за свидетеля. */
  public static final String NOT_VALIDATED_REFUSAL =
      "Удостоверение по Прил. 9 се създава, опреснява и изнася само за валидиран болничен лист."
          + " Валидирайте листа и опитайте отново.";

  /** Видът подаване на ПЪРВОТО удостоверение (лицевата страна на образеца: „Представяне“). */
  static final String SUBMISSION_TYPE_SUBMIT = "SUBMIT";

  /**
   * Отказът за невалидиран лист — или {@code null}. ЕДНО място за трите входа: създаване,
   * опресняване и износ ({@code BgApp9ExportService}).
   */
  public static String unvalidatedRefusal(BgSickLeave sickLeave) {
    return sickLeave != null
            && BgPayrollInputBuilder.SICK_LEAVE_VALIDATED.equals(sickLeave.getStatusSelect())
        ? null
        : NOT_VALIDATED_REFUSAL;
  }

  /**
   * СЪЗДАВА удостоверението, ако го няма, и ИЗВЕЖДА обстоятелствата — единственият път до НОВО
   * удостоверение (бутонът „Създай удостоверение (Прил. 9)“ и „Изведи/опресни“ на болничния).
   *
   * <p>⚠️ САМО ЗА ВАЛИДИРАН ЛИСТ — и създаване, и опресняване, и това е в услугата, не само в
   * изгледа: черновата е намерение, не документ, и не се дължи по Прил. 9 ({@code
   * BgApp9Obligation}). Лист, върнат в чернова след създаването, вече НЕ се опреснява. Отказът е
   * ПРЕДИ транзакцията, тоест нищо не се записва.
   *
   * @throws IllegalStateException с {@link #NOT_VALIDATED_REFUSAL}, когато листът не е валидиран
   */
  public BgApp9Resolution createOrResolve(BgSickLeave sickLeave) {
    Objects.requireNonNull(sickLeave, "BgSickLeave е задължителен.");
    String refusal = unvalidatedRefusal(sickLeave);
    if (refusal != null) {
      throw new IllegalStateException(refusal);
    }
    return writeCreateOrResolve(sickLeave);
  }

  /** Записът — създаване (ако трябва), извеждане и присъди, всичко или нищо. */
  @Transactional(rollbackOn = Exception.class)
  protected BgApp9Resolution writeCreateOrResolve(BgSickLeave sickLeave) {
    BgApp9DeclarationRepository declarations = Beans.get(BgApp9DeclarationRepository.class);
    BgApp9Declaration declaration = sickLeave.getBgApp9Declaration();
    if (declaration == null) {
      declaration = new BgApp9Declaration();
      declaration.setSickLeave(sickLeave);
      declaration.setStatusSelect("DRAFT");
      declaration = declarations.save(declaration);
      sickLeave.setBgApp9Declaration(declaration);
    }
    // ВИДЪТ ПОДАВАНЕ: празен и неподадено → „Представяне“. Първото удостоверение за лист НЯМА друга
    // допустима стойност — корекция и заличаване се отнасят до вече подадено. Подаденото не се
    // пипа:
    // там изборът е на човека (корекция/заличаване).
    if (blank(declaration.getBgSubmissionTypeSelect())
        && !STATUS_SUBMITTED.equals(declaration.getStatusSelect())) {
      declaration.setBgSubmissionTypeSelect(SUBMISSION_TYPE_SUBMIT);
    }
    BgApp9Resolution resolution = resolveInto(declaration);
    declarations.save(declaration);
    return resolution;
  }

  /**
   * ИЗВЕЖДА обстоятелствата върху записа И ЗАПИСВА ПРИСЪДИТЕ.
   *
   * <p>⚠️ Изведените стойности се ПРЕЗАПИСВАТ на всяко извикване, включително с {@code null}: второ
   * извеждане след поправка на договора трябва да МАХНЕ стойност, която вече не следва от данните.
   * Оставена, тя щеше да е присъда от преди поправката, която свидетелят вече отрича.
   *
   * <p>⚠️ РЪЧНИТЕ ПОЛЕТА НЕ СЕ ПИПАТ — нито едно от т. 8, 10, 11, 12, 14–24 не се появява по-долу.
   * Резолверът дори не може да ги върне (виж {@link BgApp9Resolution}).
   *
   * @param declaration записът, върху който се пише. Иска запазен {@code sickLeave}
   * @return присъдите, за да може повикващият да ги ПОКАЖЕ, а не само да ги запише
   */
  public BgApp9Resolution resolveInto(BgApp9Declaration declaration) {
    Objects.requireNonNull(declaration, "BgApp9Declaration е задължителен.");
    BgSickLeave sickLeave = declaration.getSickLeave();
    if (sickLeave == null) {
      throw new IllegalStateException(
          "Удостоверението няма болничен лист — обстоятелствата се извеждат ОТ листа.");
    }

    // ПРИСЪДАТА ЗА ПРАВОТО — замразената на листа, или сметната ВЕДНЪЖ сега (към началото на
    // неработоспособността) и замразена. Точки 2 и 3 я четат; отказът ѝ отива в тях със същия
    // текст.
    BgSickBenefitVerdictRules.Verdict verdict = benefitVerdict(sickLeave);
    // Без отказ — през едноаргументния шев (подменим в свидетелите); присъдата се чете от листа.
    BgApp9Resolution resolution =
        BgApp9ObstoyatelstvaResolver.resolve(
            verdict != null && verdict.isRefused()
                ? factsOf(sickLeave, verdict.refusal)
                : factsOf(sickLeave));

    declaration.setBgP1Insured(resolution.p1Insured);
    declaration.setBgP1TermTypeSelect(resolution.p1TermType);
    declaration.setBgP1TermEndDate(resolution.p1TermEndDate);
    declaration.setBgP1SolRegTypeSelect(resolution.p1SolRegType);
    declaration.setBgP1SolRegEndDate(resolution.p1SolRegEndDate);
    declaration.setBgP2SixMonthStaySelect(resolution.p2SixMonthStay);
    declaration.setBgP3TwelveMonthStaySelect(resolution.p3TwelveMonthStay);
    declaration.setBgP4InsuredTypeCode(resolution.p4InsuredTypeCode);
    declaration.setBgP5RelationshipCount(resolution.p5RelationshipCount);
    declaration.setCertificateSeqNo(resolution.certificateSeqNo);
    declaration.setBgP7TerminatedFrom(resolution.p7TerminatedFrom);
    declaration.setBgP13Child(childOf(resolution.p13ChildId));
    declaration.setBenefitIban(resolution.benefitIban);
    declaration.setBgP26FirstSubmissionSelect(resolution.p26FirstSubmission);

    // ═══ ТУК ПРИСЪДИТЕ СЕ ИЗКАРВАТ, А НЕ СЕ ИЗХВЪРЛЯТ ═══════════════════════
    declaration.setResolverNotes(resolution.asNotes());
    declaration.setBgResolvedAt(LocalDateTime.now());
    if (STATUS_SUBMITTED.equals(declaration.getStatusSelect())) {
      // Подадено удостоверение НЕ пада обратно в „изведено“: подаването е факт, извеждането —
      // изчисление върху него. Стойностите и присъдите се обновяват, състоянието остава.
      return resolution;
    }
    declaration.setStatusSelect(STATUS_RESOLVED);
    return resolution;
  }

  /**
   * СЪБИРА ФАКТИТЕ — единственото място в Ф1, което пипа базата за обстоятелствата.
   *
   * <p>⚠️ Разделено от резолвера нарочно: модулът няма mock-подпора, тоест правило със заявка вътре
   * може да бъде сверено само структурно. Тук няма НИТО ЕДНО правило — само четене.
   */
  public BgApp9Facts factsOf(BgSickLeave sickLeave) {
    return factsOf(sickLeave, null);
  }

  /**
   * Шевът към присъдата за правото — замразената, или сметната веднъж и замразена. Подменим в
   * свидетелите, които мерят други точки и нямат Guice.
   */
  protected BgSickBenefitVerdictRules.Verdict benefitVerdict(BgSickLeave sickLeave) {
    return Beans.get(BgSickBenefitVerdictService.class).ensureFrozen(sickLeave);
  }

  /**
   * @param verdictRefusal защо присъдата за правото липсва, или {@code null}
   */
  public BgApp9Facts factsOf(BgSickLeave sickLeave, String verdictRefusal) {
    BgApp9Facts.Builder builder = BgApp9Facts.builder();
    builder.benefitVerdict(
        sickLeave.getBgBenefitVerdict(), sickLeave.getBgBenefitVerdictWhy(), verdictRefusal);

    builder
        .incapacityStartDate(sickLeave.getBgIncapacityStartDate())
        .sickPeriod(sickLeave.getFromDate(), sickLeave.getToDate())
        .employerReceived(
            sickLeave.getBgEmployerReceivedNo(), sickLeave.getBgEmployerReceivedDate());

    if (sickLeave.getBgAbsenceType() != null) {
      builder.absenceType(
          sickLeave.getBgAbsenceType().getCategory(),
          sickLeave.getBgAbsenceType().getCode(),
          sickLeave.getBgAbsenceType().getName());
    }

    // Т. 9 — САМО БРОЕНЕ. Числата са на Ф0 и нищо тук не ги мени.
    List<BgSickLeavePeriodLine> lines = sickLeave.getBgPeriodLineList();
    int lineCount = 0;
    int lineWorkDays = 0;
    if (lines != null) {
      lineCount = lines.size();
      for (BgSickLeavePeriodLine line : lines) {
        lineWorkDays += line.getWorkDays();
      }
    }
    builder.periodLines(lineCount, lineWorkDays);

    EmploymentContract contract = sickLeave.getEmploymentContract();
    Employee employee = contract == null ? null : contract.getEmployee();
    if (contract != null) {
      builder
          .contractType(contract.getBgContractType())
          .contractDates(contract.getStartDate(), contract.getEndDate())
          .terminationDate(contract.getBgTerminationDate())
          .insuredTypeCode(contract.getBgInsuredTypeCode())
          .insuredOnOtherGround(contract.getBgInsuredOnOtherGround());
    }
    if (employee != null) {
      builder
          .selfInsured(employee.getBgIsSelfInsured())
          .insuranceService(
              employee.getBgInsuranceServiceDeclared(), employee.getBgInsuranceServiceMonths())
          // Само за изключението по чл. 40 ал. 1 изр. 2 КСО (ненавършили 18 години).
          .birthDate(employee.getBirthDate())
          .benefitIban(employee.getBgBenefitIban());

      List<BgEmployeeChild> children = childrenOf(employee);
      builder.children(
          children.size(),
          children.size() == 1 ? children.get(0).getId() : null,
          children.size() == 1 ? children.get(0).getFullName() : null);

      builder.earlierApp9Submitted(hasEarlierSubmittedApp9(employee, sickLeave));
    }

    // Т. 5 и т. 6 — броени при СЪЩИЯ осигурител, към деня на настъпването.
    LocalDate riskDay = sickLeave.getBgIncapacityStartDate();
    if (contract != null && contract.getPayCompany() != null && riskDay != null) {
      List<Long> siblings = relationshipIdsAt(employee, contract, riskDay);
      if (!siblings.isEmpty()) {
        int ordinal = siblings.indexOf(contract.getId()) + 1;
        builder.relationships(siblings.size(), ordinal == 0 ? null : ordinal);
      }
    }

    if (employee != null && sickLeave.getFromDate() != null && sickLeave.getToDate() != null) {
      builder
          .overlappingPaidLeave(
              leaveIntervals(employee, sickLeave.getFromDate(), sickLeave.getToDate(), true))
          .leaveNotCountedAsInsuranceService(
              leaveIntervals(employee, sickLeave.getFromDate(), sickLeave.getToDate(), false));
    }

    return builder.build();
  }

  /**
   * ПРАВООТНОШЕНИЯТА ПРИ СЪЩИЯ ОСИГУРИТЕЛ към деня на настъпването, подредени по идентификатор.
   *
   * <p><b>⚠️ ОСТА Е {@code payCompany}, НЕ ЛИЦЕТО.</b> Указанието към т. 5 иска „броят на
   * правоотношенията или основанията, по които лицето е осигурено при ЕДИН ОСИГУРИТЕЛ (същия ЕИК)“.
   * Договор при друго дружество се брои в ЧУЖДО удостоверение.
   *
   * <p><b>⚠️ ПОДРЕДБАТА Е ПО ИДЕНТИФИКАТОР, И ТОВА Е ИЗБОР, НЕ СЛУЧАЙНОСТ.</b> Т. 6 иска пореден
   * номер в 1..т.5, тоест иска СТАБИЛЕН ред: същият договор трябва да получава същия номер при
   * всяко извеждане, инак второто извеждане би сменило номер, който по указанията НЕ СЕ КОРИГИРА.
   * Датата на започване не е стабилна (два договора може да започват в един ден), а идентификаторът
   * е. ⚠️ И се подрежда в JAVA, не с ORDER BY: подредбата на базата зависи от collation, а тук
   * ключът е число — но същият навик, който хвана {@code v_natura} преди {@code vaucheri}, важи и
   * тук, защото списъкът се сравнява по позиция.
   */
  protected List<Long> relationshipIdsAt(
      Employee employee, EmploymentContract contract, LocalDate riskDay) {
    if (employee == null) {
      return Collections.emptyList();
    }
    List<Long> ids =
        JPA.em()
            .createQuery(
                "SELECT c.id FROM EmploymentContract c"
                    + " WHERE c.employee = :employee AND c.payCompany = :company"
                    + " AND c.startDate IS NOT NULL AND c.startDate <= :riskDay"
                    + " AND (c.bgTerminationDate IS NULL OR c.bgTerminationDate >= :riskDay)"
                    + " AND (c.bgTerminationDate IS NOT NULL OR c.endDate IS NULL"
                    + " OR c.endDate >= :riskDay)",
                Long.class)
            .setParameter("employee", employee)
            .setParameter("company", contract.getPayCompany())
            .setParameter("riskDay", riskDay)
            .getResultList();
    List<Long> sorted = new ArrayList<>(ids);
    Collections.sort(sorted);
    return sorted;
  }

  /**
   * Има ли ПОДАДЕНО удостоверение по Приложение № 9 за това лице ПРЕДИ този лист — гейтът на т. 26.
   *
   * <p>⚠️ „Подадено“, не „съществуващо“: бележка *** казва „само при ПЪРВОТО ПРЕДСТАВЯНЕ“, а
   * чернова не е представяне. Записът за ТОЗИ лист се изключва — той е този, който се извежда.
   */
  protected Boolean hasEarlierSubmittedApp9(Employee employee, BgSickLeave sickLeave) {
    // ⚠️ Условието за «не този лист» се СГЛОБЯВА, а не се подава като параметър, който може да е
    // null. „:param IS NULL OR …“ е разпространен, но зависи от диалекта и от типизирането на
    // празния параметър; при незапазен лист (id == null) той тихо би върнал друг отговор.
    String base =
        "SELECT COUNT(d) FROM BgApp9Declaration d"
            + " WHERE d.sickLeave.employmentContract.employee = :employee"
            + " AND d.statusSelect = :submitted";
    Long count;
    if (sickLeave.getId() == null) {
      count =
          JPA.em()
              .createQuery(base, Long.class)
              .setParameter("employee", employee)
              .setParameter("submitted", STATUS_SUBMITTED)
              .getSingleResult();
    } else {
      count =
          JPA.em()
              .createQuery(base + " AND d.sickLeave.id <> :sickLeaveId", Long.class)
              .setParameter("employee", employee)
              .setParameter("submitted", STATUS_SUBMITTED)
              .setParameter("sickLeaveId", sickLeave.getId())
              .getSingleResult();
    }
    return count != null && count > 0;
  }

  /**
   * ИНТЕРВАЛИТЕ НА ОТПУСКИТЕ, пресичащи периода по листа — вход за ПРЕДЛОЖЕНИЯТА по т. 12 и т. 8.
   *
   * <p>⚠️ Същият филтър като {@code BgPayrollInputBuilder#leaveRows}, {@code BgD1DaySource} и
   * {@code BgUnpaidLeaveHealthService}: валидирани заявки, припокриващи периода. Четвърти различен
   * филтър над едни и същи заявки би дал четвърти отговор за един и същи лист.
   *
   * @param paid {@code true} за платените (предложение по т. 12), {@code false} за онези, които НЕ
   *     СЕ ЗАЧИТАТ за осигурителен стаж (предложение по т. 8)
   */
  /** Exposed (not just inlined) so a test can assert the historical-leave filter is present. */
  public static String leaveIntervalsJpql(boolean paid) {
    String condition =
        paid
            ? "t.isPaid = TRUE"
            : "(t.countsAsInsuranceService IS NULL OR t.countsAsInsuranceService = FALSE)";
    return "SELECT t.name, l.fromDateT, l.toDateT"
        + " FROM LeaveRequest l"
        + " JOIN l.leaveReason r"
        + " JOIN r.bgAbsenceType t"
        + " WHERE l.employee = :employee AND l.statusSelect = :validated"
        + " AND l.fromDateT <= :toEnd AND l.toDateT >= :fromStart"
        + BgHistoricalLeaveFilter.EXCLUDE_HISTORICAL
        + " AND "
        + condition;
  }

  protected List<String> leaveIntervals(
      Employee employee, LocalDate from, LocalDate to, boolean paid) {
    List<Object[]> rows =
        JPA.em()
            .createQuery(leaveIntervalsJpql(paid), Object[].class)
            .setParameter("employee", employee)
            .setParameter("validated", LEAVE_VALIDATED)
            .setParameter("fromStart", from.atStartOfDay())
            .setParameter("toEnd", to.atTime(23, 59, 59))
            .getResultList();

    List<String> intervals = new ArrayList<>();
    for (Object[] row : rows) {
      intervals.add(
          row[0]
              + " ("
              + (row[1] == null ? "?" : ((LocalDateTime) row[1]).toLocalDate())
              + " – "
              + (row[2] == null ? "?" : ((LocalDateTime) row[2]).toLocalDate())
              + ")");
    }
    Collections.sort(intervals);
    return intervals;
  }

  /** Децата на лицето, подредени стабилно — редът решава кое е „единственото“ при точно едно. */
  protected List<BgEmployeeChild> childrenOf(Employee employee) {
    return Beans.get(BgEmployeeChildRepository.class)
        .all()
        .filter("self.employee = :employee")
        .bind("employee", employee)
        .order("id")
        .fetch();
  }

  /** ШЕВЪТ КЪМ БАЗАТА за детето по т. 13 — резолверът връща идентификатор, не запис. */
  protected BgEmployeeChild childOf(Long childId) {
    if (childId == null) {
      return null;
    }
    return Beans.get(BgEmployeeChildRepository.class).find(childId);
  }

  private static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
