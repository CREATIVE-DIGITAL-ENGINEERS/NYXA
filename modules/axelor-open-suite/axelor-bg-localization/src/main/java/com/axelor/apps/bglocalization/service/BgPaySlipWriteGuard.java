package com.axelor.apps.bglocalization.service;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import java.util.List;
import javax.persistence.FlushModeType;

/**
 * ПАЗАЧИТЕ НА ЗАПИСА НА ФИША — едни за „Преизчисли“ ({@code BgPaySlipService#calculateAndSave}) и
 * за репозитория ({@code BgPaySlipManagementRepository#save}), тоест и за стандартния запис, и за
 * REST.
 *
 * <ul>
 *   <li><b>Дружеството</b> — фишът е на активното дружество. Гейтът на „Преизчисли“ пита ЗАПИСАНИЯ
 *       ред преди редакцията, а при нов фиш (без id) правото на Axelor изобщо не пита условието на
 *       каталога ({@code AuthSecurity#isPermitted}). Смяна на договора към друго дружество и нов
 *       фиш с чужд договор иначе минават през бутона. (Стандартният запис и REST ги хваща и
 *       проверката на Axelor СЛЕД записа в {@code Resource#save}; тук правилото е изрично и за
 *       всеки път.)
 *   <li><b>Периодът</b> — чернова в заключен (CLOSED или подадена Д1/Д6) или в затварящ се период
 *       не се записва. Отказите само на потвърждаването не стигат: черновата би се записала и
 *       „Преизчисли“ би писал т. 9 по листовете в месец, който вече е деклариран или се затваря.
 * </ul>
 */
public final class BgPaySlipWriteGuard {

  private BgPaySlipWriteGuard() {}

  // ── записаното в базата ─────────────────────────────────────────────────

  /** Какво пише в базата за фиша — преди редакцията от формата. */
  public static final class Stored {
    public final String reference;
    public final String status;
    public final boolean retro;

    public Stored(String reference, String status, boolean retro) {
      this.reference = reference;
      this.status = status;
      this.retro = retro;
    }
  }

  /**
   * ЗАПИСАНОТО — номерът, статусът и признакът „ретро“, или {@code null}, ако редът го няма.
   *
   * <p>⚠️ Вярно е САМО ПРЕДИ редакцията — затова го вика „Преизчисли“, преди {@code JPA.edit}, в
   * началото на транзакцията си. {@link FlushModeType#COMMIT} пази и от flush на нещо мръсно в
   * контекста. В репозитория то НЕ се ползва: {@code Resource#save} флъшва ({@code JPA.manage})
   * преди {@code repository.save}, тоест там „записаното“ вече е новото.
   */
  public static Stored stored(Long id) {
    if (id == null) {
      return null;
    }
    List<Object[]> rows =
        JPA.em()
            .createQuery(
                "SELECT s.reference, s.statusSelect, s.bgIsCorrection, s.bgIsDeletion"
                    + " FROM BgPaySlip s WHERE s.id = :id",
                Object[].class)
            .setParameter("id", id)
            .setFlushMode(FlushModeType.COMMIT)
            .getResultList();
    if (rows.isEmpty()) {
      return null;
    }
    Object[] row = rows.get(0);
    return new Stored(
        (String) row[0],
        (String) row[1],
        Boolean.TRUE.equals(row[2]) || Boolean.TRUE.equals(row[3]));
  }

  // ── дружеството ─────────────────────────────────────────────────────────

  /**
   * ДРУЖЕСТВОТО НА ФИША — по правилото на каталога на правата ({@code nx_permission.csv},
   * BgPaySlip): платецът по трудовия договор, а за самоосигуряващия се — платецът по основния
   * договор на лицето от регистрацията му. Не трето правило: каталогът решава кой ВИЖДА фиша, тук
   * се решава в кое дружество се ЗАПИСВА, и двете трябва да дават едно и също.
   */
  public static Company companyOf(BgPaySlip slip) {
    if (slip == null) {
      return null;
    }
    BgSelfInsuredRegistration registration = slip.getBgSelfInsuredRegistration();
    if (registration != null) {
      Employee employee = registration.getEmployee();
      EmploymentContract main = employee == null ? null : employee.getMainEmploymentContract();
      return main == null ? null : main.getPayCompany();
    }
    EmploymentContract contract = slip.getEmploymentContract();
    return contract == null ? null : contract.getPayCompany();
  }

  /**
   * Фиш БЕЗ ДРУЖЕСТВО — причина за отказ, или {@code null}. Не се записва изобщо: без дружество
   * нито каталогът може да каже чий е, нито пазачът на активното дружество има какво да сравни.
   */
  public static String noCompanyRefusal(BgPaySlip slip) {
    if (companyOf(slip) != null) {
      return null;
    }
    return "Фиш "
        + label(slip)
        + " не се записва: няма дружество — нито трудов договор с попълнен платец, нито регистрация"
        + " на самоосигуряващо се лице, чийто основен договор има платец. Изберете договора (или"
        + " регистрацията) и повторете.";
  }

  // ── периодът ────────────────────────────────────────────────────────────

  /**
   * ЧЕРНОВА В ЗАКЛЮЧЕН ИЛИ ЗАТВАРЯЩ СЕ ПЕРИОД — причина за отказ, или {@code null}.
   *
   * <p>Двата отказа са ТЕЗИ НА ПОТВЪРЖДАВАНЕТО, в същия ред ({@code BgPaySlipService#confirm}):
   * първо затварянето (то спира и корекцията), после заключването (то пуска корекцията). Тук не се
   * пресъздава нито едно от двете правила.
   *
   * @param retro „коригиращо или заличаващо копие“ — при „Преизчисли“ ОТ БАЗАТА
   */
  public static String refusePeriod(BgPaySlip slip, boolean declaredToNap, boolean retro) {
    if (slip == null) {
      return null;
    }
    String why = BgPaySlipResolver.refuseClosingPeriod(slip.getPeriod());
    if (why == null) {
      why = BgPaySlipResolver.refuseLockedPeriod(slip, declaredToNap, retro);
    }
    return why == null
        ? null
        : "Черновата " + label(slip) + " не се записва и не се преизчислява. " + why;
  }

  static String label(BgPaySlip slip) {
    String reference = slip == null ? null : slip.getReference();
    return reference == null || reference.trim().isEmpty() ? "(нов фиш)" : reference;
  }
}
