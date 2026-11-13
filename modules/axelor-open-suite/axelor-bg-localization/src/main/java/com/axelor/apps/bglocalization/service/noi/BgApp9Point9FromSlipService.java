package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import com.axelor.apps.bglocalization.db.repo.BgApp9DeclarationRepository;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ШЕВЪТ КЪМ БАЗАТА за „Изведи т. 9 от потвърдения фиш“ — чете потвърдените фишове и другите
 * листове, подава ги на {@link BgApp9Point9FromSlip} и записва редовете с одитна следа.
 *
 * <p><b>⚠️ ИЗРИЧНО ДЕЙСТВИЕ, БЕЗ BACKFILL.</b> Нищо не върви само: човек натиска бутона на ЕДИН
 * лист. Заварените листове с празна т. 9 остават празни, докато някой не реши за всеки от тях.
 *
 * <p><b>⚠️ САМО ПРАЗНА Т. 9.</b> Лист, който вече има редове, не се пипа — те са от пресмятането на
 * фиша или от „Отрази пристигнал болничен“ и са по-точни (носят границите по платец).
 *
 * <p><b>КОЙ ФИШ.</b> За всеки месец: фишовете на договора на листа в статус „Потвърден“ или
 * „Изплатен“, без заличаващите и без заличените с потвърден заличаващ фиш. При няколко —
 * НАЙ-НОВИЯТ: коригиращото копие се създава след оригинала и носи ПЪЛНИТЕ верни числа за месеца.
 */
@Singleton
public class BgApp9Point9FromSlipService {

  /** Отказът, когато т. 9 вече има редове. */
  static String notEmptyRefusal(int lines) {
    return "т. 9 на листа вече има "
        + lines
        + " реда — действието попълва само ПРАЗНА т. 9. Редовете не се презаписват.";
  }

  /** Предлага ли се действието: валидиран лист, празна т. 9 и потвърден фиш за ВСЕКИ месец. */
  public boolean isOffered(BgSickLeave sick) {
    if (sick == null
        || !BgPayrollInputBuilder.SICK_LEAVE_VALIDATED.equals(sick.getStatusSelect())
        || hasPoint9(sick)
        || sick.getEmploymentContract() == null) {
      return false;
    }
    for (LocalDate month : BgApp9Point9FromSlip.monthsOf(sick.getFromDate(), sick.getToDate())) {
      if (confirmedSlipFor(sick.getEmploymentContract(), month) == null) {
        return false;
      }
    }
    return !BgApp9Point9FromSlip.monthsOf(sick.getFromDate(), sick.getToDate()).isEmpty();
  }

  /** Какво ЩЕ се запише — или защо не. Не пише нищо. */
  public BgApp9Point9FromSlip.Plan plan(BgSickLeave sick) {
    List<String> refusals = new ArrayList<>();
    String unvalidated = BgApp9DeclarationService.unvalidatedRefusal(sick);
    if (unvalidated != null) {
      refusals.add(unvalidated);
      return BgApp9Point9FromSlip.Plan.refused(refusals);
    }
    if (hasPoint9(sick)) {
      refusals.add(notEmptyRefusal(sick.getBgPeriodLineList().size()));
      return BgApp9Point9FromSlip.Plan.refused(refusals);
    }
    EmploymentContract contract = sick.getEmploymentContract();
    if (contract == null) {
      refusals.add("листът няма трудов договор — няма чий фиш да се прочете.");
      return BgApp9Point9FromSlip.Plan.refused(refusals);
    }
    Map<LocalDate, BgApp9Point9FromSlip.MonthSource> sources = new LinkedHashMap<>();
    for (LocalDate month : BgApp9Point9FromSlip.monthsOf(sick.getFromDate(), sick.getToDate())) {
      BgPaySlip slip = confirmedSlipFor(contract, month);
      if (slip == null) {
        continue;
      }
      sources.put(
          month,
          new BgApp9Point9FromSlip.MonthSource(
              slip.getReference() == null ? "фиш №" + slip.getId() : slip.getReference(),
              Boolean.TRUE.equals(slip.getBgIsCorrection()),
              slip.getBgSickDaysEmployer(),
              slip.getBgSickDaysDoo(),
              slip.getBgRegimeHoursPerDay(),
              slip.getBgRegimeWorkTimeMode(),
              otherSheetsIn(sick, month)));
    }
    return BgApp9Point9FromSlip.plan(sick.getFromDate(), sick.getToDate(), sources);
  }

  /**
   * ИЗВЕЖДА И ЗАПИСВА — всичко или нищо.
   *
   * @return планът; при отказ нищо не е записано
   */
  @Transactional(rollbackOn = Exception.class)
  public BgApp9Point9FromSlip.Plan derive(Long sickLeaveId, User user) {
    BgSickLeave sick =
        sickLeaveId == null ? null : Beans.get(BgSickLeaveRepository.class).find(sickLeaveId);
    if (sick == null) {
      List<String> refusals = new ArrayList<>();
      refusals.add("болничният лист не е намерен — запишете го и опитайте отново.");
      return BgApp9Point9FromSlip.Plan.refused(refusals);
    }
    BgApp9Point9FromSlip.Plan plan = plan(sick);
    if (plan.isRefused()) {
      return plan;
    }
    if (sick.getBgPeriodLineList() == null) {
      sick.setBgPeriodLineList(new ArrayList<BgSickLeavePeriodLine>());
    }
    for (BgApp9Point9FromSlip.Slice slice : plan.slices) {
      BgSickLeavePeriodLine line = new BgSickLeavePeriodLine();
      line.setPeriodYear(slice.fromDay.getYear());
      line.setPeriodMonth(slice.fromDay.getMonthValue());
      line.setFromDay(slice.fromDay);
      line.setToDay(slice.toDay);
      line.setWorkDays(slice.workDays);
      line.setDailyHours(slice.dailyHours);
      line.setWorkHours(slice.workHours);
      line.setPayer(slice.payer);
      // addBgPeriodLineListItem закача и обратната връзка — sickLeave е required.
      sick.addBgPeriodLineListItem(line);
    }
    sick.setBgPoint9DerivedBy(user);
    sick.setBgPoint9DerivedOn(LocalDateTime.now());
    sick.setBgPoint9DerivedFrom(plan.audit);
    // Записът през repository-то опреснява и групата и срока (BgSickLeaveManagementRepository).
    Beans.get(BgSickLeaveRepository.class).save(sick);
    // И ПРИСЪДИТЕ НА РЕЗОЛВЕРА: иначе присъдата за т. 9 остава старият отказ „листът няма нито
    // един ред по т. 9“, докато някой не натисне „Изведи/опресни“. Само за НЕПОДАДЕНО
    // удостоверение — подаденото не се преизвежда (виж BgApp9SubmissionService#restamp).
    // Удостоверението — със заявка, не през обратната страна на one-to-one: тя не се пълни сама.
    BgApp9Declaration declaration =
        Beans.get(BgApp9DeclarationRepository.class)
            .all()
            .filter("self.sickLeave = :sick")
            .bind("sick", sick)
            .fetchOne();
    if (declaration != null && !"SUBMITTED".equals(declaration.getStatusSelect())) {
      Beans.get(BgApp9DeclarationService.class).resolveInto(declaration);
      Beans.get(BgApp9DeclarationRepository.class).save(declaration);
    }
    return plan;
  }

  /**
   * Потвърденият фиш на договора за месеца — най-новият, без заличаващите и заличените.
   *
   * <p>⚠️ Периодът се търси по ПРЕСИЧАНЕ с месеца, не по равенство на датите: периодът е {@code
   * Period} и нищо в схемата не го задължава да е точно месец.
   */
  protected BgPaySlip confirmedSlipFor(EmploymentContract contract, LocalDate month) {
    LocalDate monthEnd = month.withDayOfMonth(month.lengthOfMonth());
    List<BgPaySlip> found =
        JPA.em()
            .createQuery(
                "SELECT s FROM BgPaySlip s"
                    + " WHERE s.employmentContract = :contract"
                    + " AND s.statusSelect IN (:confirmed, :paid)"
                    + " AND (s.bgIsDeletion IS NULL OR s.bgIsDeletion = false)"
                    + " AND s.period.fromDate <= :monthEnd AND s.period.toDate >= :monthStart"
                    + " AND NOT EXISTS (SELECT d FROM BgPaySlip d WHERE d.bgDeletionOf = s"
                    + "   AND d.statusSelect IN (:confirmed, :paid))"
                    + " ORDER BY s.id DESC",
                BgPaySlip.class)
            .setParameter("contract", contract)
            .setParameter("confirmed", BgPaySlipResolver.CONFIRMED)
            .setParameter("paid", BgPaySlipResolver.PAID)
            .setParameter("monthStart", month)
            .setParameter("monthEnd", monthEnd)
            .setMaxResults(1)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /** Статусът „Отказан“ на болничния лист ({@code bg.sick.leave.status.select}). */
  static final String SICK_LEAVE_CANCELLED = "CANCELLED";

  /**
   * Другите НЕОТКАЗАНИ листове на договора, които засягат месеца — номерата им.
   *
   * <p>⚠️ ВСИЧКИ ОСВЕН ОТКАЗАНИТЕ, НЕ САМО ВАЛИДИРАНИТЕ. Лист, върнат в чернова СЛЕД пресмятането
   * на фиша, е оставил дните си в потвърдения фиш. Броени само валидираните, той изчезваше от
   * проверката и дните му се отнасяха изцяло към ТОЗИ лист.
   */
  protected List<String> otherSheetsIn(BgSickLeave sick, LocalDate month) {
    LocalDate monthEnd = month.withDayOfMonth(month.lengthOfMonth());
    List<BgSickLeave> others =
        JPA.em()
            .createQuery(
                "SELECT l FROM BgSickLeave l"
                    + " WHERE l.employmentContract = :contract AND l.id <> :id"
                    + " AND (l.statusSelect IS NULL OR l.statusSelect <> :cancelled)"
                    + " AND l.fromDate <= :monthEnd AND l.toDate >= :monthStart"
                    + " ORDER BY l.fromDate",
                BgSickLeave.class)
            .setParameter("contract", sick.getEmploymentContract())
            .setParameter("id", sick.getId())
            .setParameter("cancelled", SICK_LEAVE_CANCELLED)
            .setParameter("monthStart", month)
            .setParameter("monthEnd", monthEnd)
            .getResultList();
    List<String> out = new ArrayList<>();
    for (BgSickLeave other : others) {
      String number =
          BgApp9ChartNumber.compose(
              other.getBgSeries(), other.getBgYear(), other.getBgDocumentNumber());
      out.add(number == null || number.isEmpty() ? "лист №" + other.getId() : number);
    }
    return out;
  }

  private static boolean hasPoint9(BgSickLeave sick) {
    return sick.getBgPeriodLineList() != null && !sick.getBgPeriodLineList().isEmpty();
  }
}
