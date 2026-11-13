package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.service.BgWorkingDayService;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * ЕДИНСТВЕНИЯТ ПИСАЧ на срока по Приложение № 9 върху болничния лист и ЕДИНСТВЕНИЯТ, който пита
 * производствения календар за него.
 *
 * <p>Присъдата е на {@link BgApp9Obligation}, датата — на {@link BgApp9Deadline}; тук се събират
 * фактите от базата и се пишат петте полета ({@code bgNoiObligationSelect}, {@code bgNoiDueDate},
 * {@code bgNoiDueSoonFrom}, {@code bgNoiDueWarning}). Без правило — само четене и запис.
 *
 * <p><b>Кога.</b> При всеки запис на листа ({@code BgSickLeaveManagementRepository}), при отваряне
 * на „Прил. 9 за подаване“ и с бутона „Опресни сроковете“ — последните два, защото срокът зависи и
 * от неща ИЗВЪН листа: границата на дружеството и заредените години на календара.
 */
public class BgApp9DeadlineService {

  protected final BgWorkingDayService workingDays;

  @Inject
  public BgApp9DeadlineService(BgWorkingDayService workingDays) {
    this.workingDays = workingDays;
  }

  /** Пише присъдата и срока върху листа. Не записва — повикващият го прави. */
  public boolean stamp(BgSickLeave sickLeave) {
    return stamp(sickLeave, workingDays.coveredYears());
  }

  /**
   * Същото, с вече прочетени заредени години — за опресняването на много листове наведнъж.
   *
   * @return дали нещо се е променило
   */
  protected boolean stamp(BgSickLeave sickLeave, Set<Integer> coveredYears) {
    BgApp9Obligation.Verdict verdict = BgApp9Obligation.decide(factsOf(sickLeave));

    LocalDate due = null;
    LocalDate soon = null;
    String warning = null;
    if (verdict.owes()) {
      BgApp9Deadline.Result result =
          BgApp9Deadline.of(
              verdict.kind,
              verdict.anchor,
              workingDays.officialHolidaysIn(
                  BgApp9Deadline.calendarFrom(verdict.anchor),
                  BgApp9Deadline.calendarTo(verdict.anchor)),
              coveredYears);
      due = result.dueDate;
      soon = result.dueSoonFrom;
      warning = result.warning;
    }

    String group = verdict.group.name();
    boolean changed =
        !Objects.equals(group, sickLeave.getBgNoiObligationSelect())
            || !Objects.equals(due, sickLeave.getBgNoiDueDate())
            || !Objects.equals(soon, sickLeave.getBgNoiDueSoonFrom())
            || !Objects.equals(warning, sickLeave.getBgNoiDueWarning());
    if (changed) {
      sickLeave.setBgNoiObligationSelect(group);
      sickLeave.setBgNoiDueDate(due);
      sickLeave.setBgNoiDueSoonFrom(soon);
      sickLeave.setBgNoiDueWarning(warning);
    }
    return changed;
  }

  /** Фактите — само четене, нито едно правило. */
  public BgApp9Obligation.Facts factsOf(BgSickLeave sickLeave) {
    BgAbsenceType type = sickLeave.getBgAbsenceType();
    EmploymentContract contract = sickLeave.getEmploymentContract();
    Company company = contract == null ? null : contract.getPayCompany();
    return BgApp9Obligation.facts()
        .status(sickLeave.getStatusSelect())
        .absenceType(
            type == null ? null : type.getCode(),
            type != null && Boolean.TRUE.equals(type.getRequiresSickNote()))
        .insuredType(contract == null ? null : contract.getBgInsuredTypeCode())
        .received(sickLeave.getBgEmployerReceivedDate())
        .from(sickLeave.getFromDate())
        .appeal(
            Boolean.TRUE.equals(sickLeave.getBgAppealedByEmployer()),
            sickLeave.getBgAppealClosedDate())
        .historical(Boolean.TRUE.equals(sickLeave.getBgIsHistoricalImport()))
        .trackFrom(company == null ? null : company.getBgApp9TrackFrom())
        .submitted(
            sickLeave.getBgApp9Declaration() != null
                && STATUS_SUBMITTED.equals(sickLeave.getBgApp9Declaration().getStatusSelect()));
  }

  /** Подадено удостоверение — със или без входящ № (заварено). */
  static final String STATUS_SUBMITTED = "SUBMITTED";

  /**
   * ОПРЕСНЯВА ЕДИН ЛИСТ ВЕДНАГА — при отметка „подадено“, при отмяната ѝ и при „Изведи т. 9“, не
   * само при запис на листа. Пише само при промяна.
   */
  public void restamp(BgSickLeave sickLeave) {
    if (sickLeave != null && stamp(sickLeave)) {
      JPA.save(sickLeave);
    }
  }

  /**
   * ОПРЕСНЯВА всички листове на дадените дружества.
   *
   * <p>⚠️ Пише само променените: опресняване без промяна не вдига версията на записа и не пречи на
   * човек, който в същия момент редактира лист.
   *
   * @return колко листа са се променили
   */
  @Transactional(rollbackOn = Exception.class)
  public int refresh(Collection<Long> companyIds) {
    if (companyIds == null || companyIds.isEmpty()) {
      return 0;
    }
    List<BgSickLeave> rows =
        JPA.all(BgSickLeave.class)
            .filter("self.employmentContract.payCompany.id IN :companyIds")
            .bind("companyIds", companyIds)
            .fetch();
    Set<Integer> covered = workingDays.coveredYears();
    int changed = 0;
    for (BgSickLeave row : rows) {
      if (stamp(row, covered)) {
        changed++;
      }
    }
    // ⚠️ UPDATE, НЕ save: кога е опреснено е факт за съобщението на списъка, не промяна на
    // дружеството — версията му не мърда и не пречи на човек, който в същия момент го редактира.
    JPA.em()
        .createQuery(
            "UPDATE Company c SET c.bgApp9DeadlinesRefreshedOn = :now WHERE c.id IN :companyIds")
        .setParameter("now", LocalDateTime.now())
        .setParameter("companyIds", companyIds)
        .executeUpdate();
    return changed;
  }

  /** Броячът в менюто — {@link BgApp9DueScope#urgentDomain} и нищо друго. */
  public long countUrgent(User user, LocalDate today) {
    return JPA.all(BgSickLeave.class)
        .filter(BgApp9DueScope.urgentDomain(BgApp9DueScope.activeCompanyIds(user)))
        .bind("today", today)
        .count();
  }

  private static final DateTimeFormatter REFRESHED_STAMP =
      DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

  /**
   * Кога сроковете на дружеството са опреснени за последно — за потребител БЕЗ право за запис,
   * който отваря списъка, без да опреснява. Часът е в зоната на дружеството ({@link
   * BgApp9ExportService#zoneOf}), не UTC на сървъра.
   */
  public String refreshedNote(Company company) {
    LocalDateTime on = company == null ? null : company.getBgApp9DeadlinesRefreshedOn();
    if (on == null) {
      return "Сроковете още не са опреснявани — нужна е роля за запис по Прил. 9 ("
          + String.join(", ", new TreeSet<>(Capabilities.NOI_WRITE))
          + "). Списъкът е отворен без опресняване.";
    }
    return "Сроковете са опреснени на "
        + on.atZone(ZoneId.systemDefault())
            .withZoneSameInstant(BgApp9ExportService.zoneOf(company))
            .format(REFRESHED_STAMP)
        + ". Списъкът е отворен без опресняване — за него е нужна роля за запис.";
  }
}
