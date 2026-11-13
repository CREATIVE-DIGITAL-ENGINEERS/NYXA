package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.service.noi.BgApp9DeadlineService;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveChainRules;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveTypeRules;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import java.util.List;
import java.util.Objects;
import javax.persistence.FlushModeType;
import javax.validation.ValidationException;

/**
 * Закача срока по Приложение № 9 при ВСЕКИ запис на болничния лист — отказва вид, който не е вид на
 * болничен лист (A4), и пази веригата от продължения (A2).
 *
 * <p><b>Защо на ниво repository, а не в изгледа.</b> Същият довод като при {@code
 * BgSelfInsuredRegistrationManagementRepository}: формата е само един от входовете. Отразеният
 * пристигнал лист, пресмятането на фиша (редовете по т. 9) и импортът записват през repository-то,
 * без да минат през екрана — и всеки от тях може да смени датата, от която тече срокът.
 *
 * <p>⚠️ Тук няма правило: присъдата и датата са на {@link BgApp9DeadlineService}, който е и
 * единственият им писач. Правилото за вида е на {@link BgSickLeaveTypeRules}, а за веригата — на
 * {@link BgSickLeaveChainRules}.
 */
public class BgSickLeaveManagementRepository extends BgSickLeaveRepository {

  @Override
  public BgSickLeave save(BgSickLeave entity) {
    guardCompany(entity);
    refuseAbsenceType(entity);
    guardContinuation(entity);
    Beans.get(BgApp9DeadlineService.class).stamp(entity);
    return super.save(entity);
  }

  /**
   * ДОГОВОРЪТ Е НА АКТИВНОТО ДРУЖЕСТВО.
   *
   * <p>⚠️ ЗАЩО ТУК, А НЕ САМО В ПРАВАТА. Условието на правата ({@code nx_permission.csv}, {@code
   * self.employmentContract.payCompany = __user__.activeCompany}) се проверява само върху ЗАВАРЕН
   * запис: при създаване записът още няма id и Axelor не пита условието, а администраторът изобщо
   * не минава през правата. Без проверката тук нов лист с договор на друго дружество минава през
   * {@code /ws/rest} и през формата.
   *
   * <p>Пита се само когато листът е нов или договорът му е сменен — системните записи (т. 9 при
   * „Преизчисли“, сроковете) не сменят договора и не зависят от това кое дружество е активно в
   * сесията. Без потребител (фонова задача) или без активно дружество проверка няма: няма спрямо
   * какво. Внесените от миграция листове са история и се пропускат.
   */
  private static void guardCompany(BgSickLeave entity) {
    if (entity == null
        || entity.getEmploymentContract() == null
        || Boolean.TRUE.equals(entity.getBgIsHistoricalImport())) {
      return;
    }
    User user = AuthUtils.getUser();
    Company active = user == null ? null : user.getActiveCompany();
    if (active == null || !contractChanged(entity)) {
      return;
    }
    String refusal = companyRefusal(entity.getEmploymentContract().getPayCompany(), active);
    if (refusal != null) {
      throw new ValidationException("Болничният лист не е записан: " + refusal);
    }
  }

  /** Отказът, или {@code null}, когато договорът е на активното дружество. */
  static String companyRefusal(Company contractCompany, Company active) {
    if (active == null || Objects.equals(idOf(contractCompany), idOf(active))) {
      return null;
    }
    return "трудовият договор е на дружество „"
        + (contractCompany == null ? "(без дружество)" : contractCompany.getName())
        + "“, а активното дружество е „"
        + active.getName()
        + "“. Болничен се записва само по договор на активното дружество.";
  }

  /** Нов лист или сменен договор — четено без autoflush, както {@link #chainInputChanged}. */
  private static boolean contractChanged(BgSickLeave entity) {
    if (entity.getId() == null) {
      return true;
    }
    List<Long> stored =
        JPA.em()
            .createQuery(
                "SELECT c.id FROM BgSickLeave s LEFT JOIN s.employmentContract c WHERE s.id = :id",
                Long.class)
            .setParameter("id", entity.getId())
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    return stored.isEmpty() || !Objects.equals(stored.get(0), idOf(entity.getEmploymentContract()));
  }

  /**
   * ВИДЪТ НА ЛИСТА — само вид, който се удостоверява с болничен лист (A4).
   *
   * <p>⚠️ Домейнът на формата стеснява ИЗБОРА, но не и записа през {@code /ws/rest} или от код —
   * затова отказът е тук. Внесените от миграция листове се пропускат: те са история с вида, който
   * миграцията е избрала (днес само {@code sick_er}), а не вход на оператор. Пътят на фиша ги
   * сверява отделно ({@code BgSickLeaveService#absenceTypeRefusal}).
   */
  private static void refuseAbsenceType(BgSickLeave entity) {
    if (entity == null || Boolean.TRUE.equals(entity.getBgIsHistoricalImport())) {
      return;
    }
    String refusal = BgSickLeaveTypeRules.refusal(entity.getBgAbsenceType());
    if (refusal != null) {
      // Без I18n.get: съобщението се СГЛОБЯВА от името и кода на вида.
      throw new ValidationException("Болничният лист не е записан: " + refusal);
    }
  }

  /**
   * ВРЪЗКАТА Е ВХОДЪТ, ОТМЕТКАТА Е ПРОИЗВОДНА (A2).
   *
   * <p>Попълнена връзка вдига отметката. Правилата се питат, когато ВХОДЪТ НА ВЕРИГАТА е нов или
   * сменен — връзка, договор, дружество (на договора), вид, начална и крайна дата, статус, отметка.
   * ⚠️ НЕ при всеки запис: листовете се записват и от системата — редовете по т. 9 при
   * „Преизчисли“, „Опресни сроковете“ — и заварен лист (отпреди връзката) би спрял тези пътища и
   * там, където сметката е вярна. Там, където веригата НАИСТИНА е скъсана, отказва фишът ({@code
   * BgSickLeaveResolver#split}: период, започващ с продължение). Внесените от миграция листове са
   * история и не се проверяват.
   */
  private static void guardContinuation(BgSickLeave entity) {
    if (entity == null || Boolean.TRUE.equals(entity.getBgIsHistoricalImport())) {
      return;
    }
    if (entity.getBgContinuationOf() != null) {
      entity.setBgIsContinuation(Boolean.TRUE);
    }
    if (!chainInputChanged(entity)) {
      return;
    }
    String refusal = BgSickLeaveChainRules.refusal(entity);
    if (refusal != null) {
      // Без I18n.get: съобщението се СГЛОБЯВА от датите и вида на двата листа.
      throw new ValidationException("Болничният лист не е записан: " + refusal);
    }
  }

  /**
   * Нов ли е листът, или е сменен някой от входовете на веригата спрямо записаното.
   *
   * <p>⚠️ ЧЕТЕ СЕ БЕЗ AUTOFLUSH ({@code FlushModeType.COMMIT}): иначе заявката първо би записала
   * самите промени, които сравнява, и винаги би върнала „без промяна“.
   */
  private static boolean chainInputChanged(BgSickLeave entity) {
    if (entity.getId() == null) {
      return true;
    }
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT p.id, t.id, c.id, s.fromDate, s.bgIsContinuation, co.id, s.toDate,"
                    + " s.statusSelect FROM BgSickLeave s"
                    + " LEFT JOIN s.bgContinuationOf p LEFT JOIN s.bgAbsenceType t"
                    + " LEFT JOIN s.employmentContract c LEFT JOIN c.payCompany co"
                    + " WHERE s.id = :id",
                Object[].class)
            .setParameter("id", entity.getId())
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    if (rows.isEmpty()) {
      return true;
    }
    Object[] stored = rows.get(0);
    return !Objects.equals(stored[0], idOf(entity.getBgContinuationOf()))
        || !Objects.equals(stored[1], idOf(entity.getBgAbsenceType()))
        || !Objects.equals(stored[2], idOf(entity.getEmploymentContract()))
        || !Objects.equals(stored[3], entity.getFromDate())
        || !Objects.equals(
            Boolean.TRUE.equals(stored[4]), Boolean.TRUE.equals(entity.getBgIsContinuation()))
        || !Objects.equals(
            stored[5],
            entity.getEmploymentContract() == null
                ? null
                : idOf(entity.getEmploymentContract().getPayCompany()))
        || !Objects.equals(stored[6], entity.getToDate())
        || !Objects.equals(stored[7], entity.getStatusSelect());
  }

  private static Long idOf(Model model) {
    return model == null ? null : model.getId();
  }
}
