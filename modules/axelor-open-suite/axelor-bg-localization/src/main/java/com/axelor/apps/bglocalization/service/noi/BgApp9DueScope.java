package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.base.db.Company;
import com.axelor.auth.db.User;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * ОБХВАТЪТ НА „ПРИЛ. 9 ЗА ПОДАВАНЕ“ — ЕДИНСТВЕНИЯТ филтър, и за списъка, и за брояча в менюто.
 *
 * <p><b>⚠️ ЗАЩО ЕДНО МЯСТО.</b> Броячът на HR ({@code HRMenuTagService#countRecordsTag}) брои през
 * {@code JPA.all}, тоест покрай правата, а списъкът зад него се филтрира от екрана — двете цифри се
 * разминават при първия ограничен потребител. Тук броячът е СЪЩИЯТ домейн плюс две условия; друг
 * път до обхвата няма.
 *
 * <p><b>Само АКТИВНОТО дружество</b> ({@code User.activeCompany}), не всички от {@code
 * User.companySet}: активното дружество е изолирана среда — потребителят вижда и пипа само неговите
 * данни, колкото и други фирми да има в профила си. {@code companySet} казва само кое може да
 * избере за активно. Без активно дружество — празно множество, а вратите (списъкът, опресняването)
 * отказват шумно: по-добре нищо, отколкото чужди фирми.
 */
public final class BgApp9DueScope {

  /** Изчислимата колона {@code BgSickLeave.bgNoiSubmissionState}. */
  public static final int STATE_NOT_GENERATED = 1;

  public static final int STATE_GENERATED = 2;
  public static final int STATE_SUBMITTED = 3;
  public static final int STATE_SUBMITTED_LEGACY = 4;

  private BgApp9DueScope() {}

  /** Обхватът на списъка: фирмите на потребителя и видимите групи на {@link BgApp9Obligation}. */
  public static String listDomain(Collection<Long> companyIds) {
    if (companyIds == null || companyIds.isEmpty()) {
      return "self.id IS NULL";
    }
    return "self.employmentContract.payCompany.id IN ("
        + joinIds(companyIds)
        + ") AND self.bgNoiObligationSelect IN ("
        + quoted(listedGroups())
        + ")";
  }

  /**
   * Броячът: обхватът + „дължи се“ + „още не е подадено“ + „лентата е стигната“ (просрочено, днес
   * или до 3 работни дни). Параметър: {@code :today}.
   */
  public static String urgentDomain(Collection<Long> companyIds) {
    return listDomain(companyIds)
        + " AND self.bgNoiObligationSelect = '"
        + BgApp9Obligation.Group.OWES.name()
        + "' AND self.bgNoiSubmissionState IN ("
        + STATE_NOT_GENERATED
        + ", "
        + STATE_GENERATED
        + ") AND self.bgNoiDueSoonFrom <= :today";
  }

  /**
   * АКТИВНОТО дружество на потребителя — единственото в обхвата. Празно, когато няма активно;
   * тогава вратите отказват шумно ({@link #NO_ACTIVE_COMPANY}).
   */
  public static Set<Long> activeCompanyIds(User user) {
    Set<Long> ids = new TreeSet<>();
    Company active = user == null ? null : user.getActiveCompany();
    if (active != null && active.getId() != null) {
      ids.add(active.getId());
    }
    return ids;
  }

  /** Отказът, когато потребителят няма активно дружество. */
  public static final String NO_ACTIVE_COMPANY =
      "Нямате активно дружество — изберете го (Предпочитания › Активно дружество). „Прил. 9 за"
          + " подаване“ показва и опреснява САМО листовете на активното дружество.";

  static List<String> listedGroups() {
    List<String> out = new ArrayList<>();
    for (BgApp9Obligation.Group group : BgApp9Obligation.Group.values()) {
      if (group.listed) {
        out.add(group.name());
      }
    }
    return out;
  }

  private static String joinIds(Collection<Long> ids) {
    StringBuilder sb = new StringBuilder();
    for (Long id : new TreeSet<>(ids)) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append(id.longValue());
    }
    return sb.toString();
  }

  private static String quoted(List<String> values) {
    StringBuilder sb = new StringBuilder();
    for (String value : values) {
      if (sb.length() > 0) {
        sb.append(", ");
      }
      sb.append('\'').append(value).append('\'');
    }
    return sb.toString();
  }
}
