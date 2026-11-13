package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.BgEffectivePaySlips;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeService;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgWorkingDayService;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * КАЛЕНДАРЪТ ЗА ЧАСТИТЕ НА ОБЕЗЩЕТЕНИЕТО ПО ВИД 27 — единственото място, където {@link
 * BgCompensationParts} стига до базата. Ползват го и Д1, и Д6: две копия на „работни дни“ биха
 * разделили едно обезщетение по два начина.
 */
public final class BgCompensationCalendar {

  private BgCompensationCalendar() {}

  /**
   * Работните дни по календара на базата.
   *
   * <p>⚠️ НЕПОКРИТА ГОДИНА Е ОТКАЗ: число, изброено без празниците ѝ, дели обезщетението грешно и
   * изглежда точно толкова пресметнато (образецът 04.2025 има 18.04 и 21.04 — без тях частите са
   * други).
   */
  public static BgCompensationParts.WorkingDays fromDatabase() {
    BgWorkingDayService calendar = Beans.get(BgWorkingDayService.class);
    return (from, to) -> {
      BgWorkingDayService.Count count =
          calendar.count(from, (int) ChronoUnit.DAYS.between(from, to) + 1);
      if (!count.isReliable()) {
        throw new IllegalStateException(
            "календарът на празниците не покрива "
                + count.uncoveredYearsAsText()
                + " — работните дни между "
                + from
                + " и "
                + to
                + " не могат да се преброят. Зареди календара за годината и генерирай отново.");
      }
      return count.getWorkingDays();
    };
  }

  /**
   * МЯСТОТО ПОД ОБЩИЯ ТАВАН за обезщетенията по вид 27 на един фиш-носител — КСО чл. 6 ал. 11, по
   * базата: MAX_INSURABLE_INCOME за месеца минус осигурителния доход по вид 01 на ЛИЦЕТО в СЪЩОТО
   * ДРУЖЕСТВО за месеца ({@link BgCompensationParts#regularBase}).
   *
   * <ul>
   *   <li><b>Месецът на носителя</b> — базата на самия носител (вноските по вид 01 на фиша вече са
   *       пера, когато се начисляват тези по вид 27) плюс действащите фишове на лицето по ДРУГИ
   *       договори в дружеството. Същият договор не се брои втори път: оригиналът на коригиращо
   *       копие е на него.
   *   <li><b>Друг месец</b> (април при прекратяване 23.03) — действащите фишове на лицето в
   *       дружеството за месеца. Обикновено няма — правоотношението е прекратено.
   * </ul>
   *
   * <p>⚠️ ЧУЖД ОСИГУРИТЕЛ НЕ СЕ БРОИ: доход при друг платец (декларация по ал. 11) е извън обхвата
   * на тази стъпка — виж „Известно“ в PR-а.
   */
  public static BgCompensationParts.Room roomFor(BgPaySlip carrier) {
    return month -> {
      BigDecimal cap = ceilingOn(month.atDay(1));
      return cap == null ? null : cap.subtract(regularBaseIn(carrier, month));
    };
  }

  /** Таванът на осигурителния доход за деня, или {@code null}. Същата заявка като на фиша. */
  static BigDecimal ceilingOn(LocalDate day) {
    List<BigDecimal> found =
        JPA.em()
            .createQuery(
                "SELECT p.paramValue FROM BgPayrollParameter p WHERE p.code = :code"
                    + " AND p.validFrom <= :date AND (p.validTo IS NULL OR p.validTo >= :date)",
                BigDecimal.class)
            .setParameter("code", BgInsurableIncomeService.CODE_MAX_INSURABLE_INCOME)
            .setParameter("date", day)
            .getResultList();
    return found.isEmpty() ? null : found.get(0);
  }

  /** Осигурителният доход по вид 01 на лицето на носителя, в дружеството му, за месеца. */
  static BigDecimal regularBaseIn(BgPaySlip carrier, YearMonth month) {
    EmploymentContract contract = carrier.getEmploymentContract();
    boolean own =
        carrier.getPeriod() != null
            && carrier.getPeriod().getFromDate() != null
            && YearMonth.from(carrier.getPeriod().getFromDate()).equals(month);
    BigDecimal base = own ? BgCompensationParts.regularBase(carrier) : BigDecimal.ZERO;
    if (contract == null || contract.getEmployee() == null || contract.getPayCompany() == null) {
      return base;
    }
    LocalDate day = month.atDay(1);
    List<BgPaySlip> slips =
        JPA.em()
            .createQuery(
                "SELECT s FROM BgPaySlip s WHERE s.employmentContract.employee = :employee"
                    + " AND s.employmentContract.payCompany = :company"
                    + " AND s.period.fromDate <= :day AND s.period.toDate >= :day AND "
                    + BgEffectivePaySlips.effective("s"),
                BgPaySlip.class)
            .setParameter("employee", contract.getEmployee())
            .setParameter("company", contract.getPayCompany())
            .setParameter("day", day)
            .setParameter(BgEffectivePaySlips.STATUSES, BgPaySlipResolver.DECLARABLE_STATUSES)
            .getResultList();
    for (BgPaySlip other : slips) {
      if (Objects.equals(other.getId(), carrier.getId())
          || (own && Objects.equals(other.getEmploymentContract(), contract))) {
        continue;
      }
      base = base.add(BgCompensationParts.regularBase(other));
    }
    return base;
  }

  /** Частите на едно обезщетение от фиша — по календара и по тавана на базата. */
  public static List<BgCompensationParts.Share> sharesOf(
      BgPaySlip slip, BgCompensationParts.Carried carried, List<BgCompensationParts.Carried> all) {
    return BgCompensationParts.sharesOf(slip, carried, all, fromDatabase(), roomFor(slip));
  }
}
