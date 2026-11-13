package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.repo.TimesheetRepository;
import com.axelor.db.JPA;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

/**
 * Т. 16.7 И Т. 16.8 — <b>часовете</b>, прочетени от отчетите за време и от допълнителните часове.
 *
 * <p><b>⚠️ ИЗТОЧНИКЪТ НА ЧАСОВЕТЕ.</b> {@code BgPaySlip} замразява ДНИ ({@code bgWorkedDays},
 * {@code bgSickDays*}); от това не следва, че източник на часове няма — {@code
 * BgPayrollInputBuilder} чете ВАЛИДИРАНИ {@code TimesheetLine} и {@code ExtraHoursLine} за (лице,
 * период), тоест същите редове са достъпни и за декларацията. Този клас чете ТОЧНО тях, с дословно
 * същите два филтъра.
 *
 * <p><b>⚠️ ЗАЩО СЪЩИТЕ ФИЛТРИ, БУКВА ПО БУКВА.</b> Два различни филтъра над едни и същи отчети биха
 * дали различен брой часове на ФИША и на ДЕКЛАРАЦИЯТА му — тоест лицето получава пари по едно число
 * и се декларира с друго. Затова: иска се СТАТУС „валидиран“ (черновата е нечие намерение, не
 * документ) и се чете {@code hoursDuration}, а НЕ {@code duration} (второто е в единицата на
 * предпочитанието за отчитане и сборът му е сбор от несравними числа).
 *
 * <p><b>⚠️ И ЗАЩО {@code null} Е ОТГОВОР, А НУЛА НЕ Е.</b> Указанието към т. 16.7 иска „броят на
 * отработените часове през месеца“ — а „нула отработени часа“ за работещ човек е числото, което НАП
 * отхвърля. Инсталация без отчети за време не е инсталация без работа. Затова липсата на редове
 * дава {@code null} („не са четени“), а не нула, и точката остава ПРАЗНА с назована причина.
 */
@Singleton
public class BgD1HourSource {

  /**
   * Състояние „валидиран“ на {@code ExtraHours}.
   *
   * <p>⚠️ Огледално на {@code BgPayrollInputBuilder}: константата там е {@code
   * EXTRA_HOURS_VALIDATED = 3}. Втора стойност тук би значела, че фишът и декларацията четат
   * различни редове.
   */
  private static final int EXTRA_HOURS_VALIDATED = 3;

  /**
   * Класификациите на {@code ExtraHoursType.bgExtraHoursKind}, които СА извънреден труд по чл. 262
   * ал. 1 КТ.
   *
   * <p><b>⚠️ ЧЕТИРИ, И НИТО ЕДНА ПОВЕЧЕ.</b> Речникът класифицира и НОЩЕН ТРУД, и ВРЕМЕ НА
   * РАЗПОЛОЖЕНИЕ, и РАБОТА НА ОФИЦИАЛЕН ПРАЗНИК — и трите НЕ са извънреден труд:
   *
   * <ul>
   *   <li>нощната смяна по график е РЕДОВЕН труд с добавка по НСОРЗ чл. 8;
   *   <li>времето на разположение по НСОРЗ чл. 10 изобщо не е работно време, „докато лицето не бъде
   *       извикано на работа“;
   *   <li>работата на празник по чл. 264 КТ се заплаща двойно НЕЗАВИСИМО дали е извънреден труд —
   *       когато Е, тя носи И ред по чл. 262 ал. 1 т. 3, тоест часовете ѝ вече са преброени ТАМ.
   *       Броени и по двете пера, те щяха да излязат два пъти в т. 16.8.
   * </ul>
   */
  private static final List<String> OVERTIME_KINDS =
      java.util.Arrays.asList(
          "OVERTIME_WORKDAY", "OVERTIME_REST_DAY", "OVERTIME_HOLIDAY", "OVERTIME_SIRV");

  /** Часовете за един период, всеки със своето „не знам“. */
  public static final class Hours {

    private final BigDecimal worked;
    private final BigDecimal overtime;
    private final int timesheetDays;

    Hours(BigDecimal worked, BigDecimal overtime, int timesheetDays) {
      this.worked = worked;
      this.overtime = overtime;
      this.timesheetDays = timesheetDays;
    }

    /** Отработените часове от отчетите за време, или {@code null}, ако отчети няма. */
    public BigDecimal getWorked() {
      return worked;
    }

    /** Часовете извънреден труд, или {@code null}, ако валидирани редове няма. */
    public BigDecimal getOvertime() {
      return overtime;
    }

    /**
     * Броят РАЗЛИЧНИ ДНИ, за които отчетът за време носи часове.
     *
     * <p>⚠️ Нужен е за третата съставка на т. 16.7: часовете на ПЛАТЕНИЯ ОТПУСК. Указанието иска
     * „включително … броят на часовете за времето на платен отпуск“, а отчетът за време брои само
     * ОТРАБОТЕНОТО. Дните платен отпуск излизат като остатък от т. 16.1 минус отчетените дни.
     */
    public int getTimesheetDays() {
      return timesheetDays;
    }

    static Hours unknown() {
      return new Hours(null, null, 0);
    }
  }

  /**
   * ЧАСОВЕТЕ ЗА ТОЗИ ФИШ — ЗАМРАЗЕНИТЕ, ако ги има, инак от живия отчет.
   *
   * <p><b>⚠️ ЗАМРАЗЕНОТО ПЕЧЕЛИ, И ТОВА Е ЦЕЛИЯТ СМИСЪЛ НА КОЛОНИТЕ.</b> Ако декларацията чете
   * ЖИВИЯ отчет за време при всяко генериране, а ведомостта е платена по ДРУГ прочит на същите
   * редове, направен по-рано, валидиран отчет, редактиран между двете, мести т. 16.7 и оставя фиша
   * на старото число — тоест лицето се плаща по едно число и се декларира с друго, без нито един
   * признак, че нещо се е разминало.
   *
   * <p><b>⚠️ ЗАМРАЗЕНОТО ПЕЧЕЛИ КАТО ЦЯЛО, А НЕ ПО ПОЛЕТА.</b> Носи ли фишът поне един измерен
   * часови признак, той е минал през {@code prepare()} с този механизъм и ОСТАНАЛИТЕ му „не знам“
   * са ОТГОВОР, а не липса. Оттам и промяната в поведението: НЕКЛАСИФИЦИРАН вид допълнителни часове
   * прави и четирите кофи „не знам“ при сглобяването на входа, тоест т. 16.8 остава ПРАЗНА с
   * назована причина — докато дотук същият ред просто се прескачаше и точката излизаше с ПО-МАЛКО
   * часове, без никой да разбере. Празната точка с причина е по-добра от тихо занижената.
   *
   * <p>⚠️ Фиш БЕЗ нито един признак се чете от живия отчет, както досега: това са фишовете отпреди
   * колоните, а за тях замразено число просто не съществува. Същото важи и за лице без нито един
   * отчетен час — там двата пътя дават един и същ отговор („не са четени“).
   *
   * @param slip фишът, който носи замразените часове, или {@code null}
   * @param employee лицето по договора на фиша
   * @param from първи ден на периода на данните
   * @param to последен ден на периода
   */
  public Hours read(BgPaySlip slip, Employee employee, LocalDate from, LocalDate to) {
    Hours frozen = frozen(slip);
    return frozen != null ? frozen : read(employee, from, to);
  }

  /**
   * ЗАМРАЗЕНИТЕ ЧАСОВЕ НА ФИША, или {@code null}, ако фишът не носи нито един измерен признак.
   *
   * <p>⚠️ ПРИЗНАЦИТЕ РЕШАВАТ, А НЕ ЧИСЛАТА. Генерираният getter за decimal връща ZERO за NULL
   * колона, тоест по стойността „нула часа“ и „не е замразявано“ са неразличими. Затова всяко от
   * двете числа излиза {@code null}, когато СВОЯТ му признак е {@code false} — и нула остава
   * ОТГОВОР само там, където признакът я обявява за измерена.
   *
   * <p>⚠️ Извънредният труд е СБОР на четирите кофи, точно както {@link #OVERTIME_KINDS} го събира
   * от живия отчет: т. 16.8 иска един брой часове извънреден труд, не разбивка.
   */
  public static Hours frozen(BgPaySlip slip) {
    if (slip == null) {
      return null;
    }
    boolean workedKnown = Boolean.TRUE.equals(slip.getBgWorkedHoursKnown());
    boolean overtimeKnown = Boolean.TRUE.equals(slip.getBgOvertimeHoursKnown());
    if (!workedKnown && !overtimeKnown) {
      return null;
    }
    BigDecimal overtime = null;
    if (overtimeKnown) {
      overtime =
          slip.getBgOvertimeWorkdayHours()
              .add(slip.getBgOvertimeRestDayHours())
              .add(slip.getBgOvertimeHolidayHours())
              .add(slip.getBgOvertimeSirvHours());
    }
    return new Hours(
        workedKnown ? slip.getBgWorkedHours() : null, overtime, slip.getBgTimesheetDays());
  }

  /**
   * Часовете на едно лице за периода, от ЖИВИЯ отчет.
   *
   * @param employee лицето по договора на фиша
   * @param from първи ден на периода на данните
   * @param to последен ден на периода
   */
  public Hours read(Employee employee, LocalDate from, LocalDate to) {
    if (employee == null || from == null || to == null) {
      return Hours.unknown();
    }
    try {
      List<Object[]> timesheet = timesheetRows(employee, from, to);
      BigDecimal worked = null;
      int days = 0;
      if (!timesheet.isEmpty()) {
        worked = BigDecimal.ZERO;
        java.util.Set<LocalDate> dates = new java.util.LinkedHashSet<>();
        for (Object[] row : timesheet) {
          if (row[1] instanceof BigDecimal) {
            worked = worked.add((BigDecimal) row[1]);
          }
          if (row[0] instanceof LocalDate) {
            dates.add((LocalDate) row[0]);
          }
        }
        days = dates.size();
      }

      List<Object[]> extra = extraHoursRows(employee, from, to);
      BigDecimal overtime = null;
      if (!extra.isEmpty()) {
        overtime = BigDecimal.ZERO;
        for (Object[] row : extra) {
          String kind = row[1] == null ? null : String.valueOf(row[1]).trim();
          // ⚠️ НЕКЛАСИФИЦИРАН вид се ПРОПУСКА, а не се брои за извънреден: „неизвестен вид“ не е
          // „извънреден труд“. Сглобителят на фиша ОТКАЗВА в същия случай, защото там числото
          // движи ПАРИ; тук то движи една точка от декларация и отказът би спрял декларация за
          // десетки лица заради един непопълнен ред в речника.
          if (kind != null && OVERTIME_KINDS.contains(kind) && row[0] instanceof BigDecimal) {
            overtime = overtime.add((BigDecimal) row[0]);
          }
        }
      }
      return new Hours(worked, overtime, days);
    } catch (RuntimeException e) {
      // Липсваща база или липсващ модул за отчитане е СЪСТОЯНИЕ НА СРЕДАТА, не отговор „нула часа“.
      return Hours.unknown();
    }
  }

  /**
   * Редовете на ВАЛИДИРАНИТЕ отчети за време — дата и часове.
   *
   * <p>⚠️ Дословно същата заявка като {@code BgPayrollInputBuilder#workedHoursByDate}. Ред без
   * {@code timesheet} отпада от вътрешното съединение по същия довод: няма кой да го е валидирал.
   */
  protected List<Object[]> timesheetRows(Employee employee, LocalDate from, LocalDate to) {
    return JPA.em()
        .createQuery(
            "SELECT l.date, l.hoursDuration FROM TimesheetLine l"
                + " WHERE l.employee = :employee AND l.date BETWEEN :from AND :to"
                + " AND l.timesheet.statusSelect = :validated",
            Object[].class)
        .setParameter("employee", employee)
        .setParameter("from", from)
        .setParameter("to", to)
        .setParameter("validated", TimesheetRepository.STATUS_VALIDATED)
        .getResultList();
  }

  /** Редовете на ВАЛИДИРАНИТЕ допълнителни часове, с класификацията на вида си. */
  protected List<Object[]> extraHoursRows(Employee employee, LocalDate from, LocalDate to) {
    return JPA.em()
        .createQuery(
            "SELECT l.qty, t.bgExtraHoursKind FROM ExtraHoursLine l"
                + " LEFT JOIN l.extraHoursType t"
                + " WHERE l.employee = :employee AND l.date BETWEEN :from AND :to"
                + " AND l.extraHours.statusSelect = :validated"
                + " ORDER BY l.date, l.id",
            Object[].class)
        .setParameter("employee", employee)
        .setParameter("from", from)
        .setParameter("to", to)
        .setParameter("validated", EXTRA_HOURS_VALIDATED)
        .getResultList();
  }

  /**
   * Т. 16.7 — <b>отработените часове ОБЩО</b>, по указанието: отработени + извънреден труд +
   * часовете на платения отпуск.
   *
   * <p><b>ДОСЛОВНО:</b> „попълва се броят на отработените часове през месеца, ВКЛЮЧИТЕЛНО броят
   * изработени часове извънреден труд и броят на часовете за времето на платен отпуск. Когато при
   * изчисляването се получи остатък, равен или по-голям от половин час, се закръглява на пълен
   * час.“
   *
   * <p><b>⚠️ ТРЕТАТА СЪСТАВКА Е ИЗВЕДЕНА, А НЕ ПРОЧЕТЕНА, и това е най-слабото място тук.</b>
   * Часовете на платения отпуск нямат носител: отчетът за време брои ОТРАБОТЕНОТО, а заявките за
   * отпуск се мерят в ДНИ. Затова дните платен отпуск излизат като ОСТАТЪК — т. 16.1 (отработени и
   * други дни с вноски) минус дните, за които отчетът носи часове, — и се умножават по ДНЕВНОТО
   * ДОГОВОРЕНО работно време. Допускането е ЕДНО и е назовано: платеният отпуск се брои по
   * договореното дневно работно време, което е точно каквото КТ чл. 177 предполага.
   *
   * <p>⚠️ И ЗАКРЪГЛЯНЕТО Е ДАДЕНО ОТ УКАЗАНИЕТО, тоест не е избор: HALF_UP до ЦЯЛ час, а не
   * отсичане.
   *
   * @param insuredDays т. 16.1 — отработените и другите дни с осигурителни вноски
   * @param contractedHoursPerDay дневното договорено работно време, или {@code null}
   * @return часовете, или {@code null}, когато отчет за време няма
   */
  public static BigDecimal totalWorkedHours(
      Hours hours, int insuredDays, BigDecimal contractedHoursPerDay) {

    if (hours == null || hours.getWorked() == null) {
      return null;
    }
    BigDecimal total = hours.getWorked();
    if (hours.getOvertime() != null) {
      total = total.add(hours.getOvertime());
    }

    // ⚠️ ОСТАТЪКЪТ СЕ КЛАМПВА НА НУЛА: отчет за време с ПОВЕЧЕ дни от т. 16.1 значи, че двата
    // източника си противоречат, а отрицателен брой дни платен отпуск не съществува. Числото не се
    // изглажда мълчаливо — противоречието се обявява от извикващия.
    int paidLeaveDays = insuredDays - hours.getTimesheetDays();
    if (paidLeaveDays > 0 && contractedHoursPerDay != null && contractedHoursPerDay.signum() > 0) {
      total = total.add(contractedHoursPerDay.multiply(BigDecimal.valueOf(paidLeaveDays)));
    }
    return total.setScale(0, RoundingMode.HALF_UP);
  }

  /** Т. 16.8 — часовете извънреден труд, закръглени по същото правило. */
  public static BigDecimal overtimeHours(Hours hours) {
    if (hours == null || hours.getOvertime() == null) {
      return null;
    }
    return hours.getOvertime().setScale(0, RoundingMode.HALF_UP);
  }

  /** Празният изход — за свидетелите, които проверяват други точки. */
  public static Hours none() {
    return Hours.unknown();
  }

  /** Часовете, дадени наготово — за свидетели без база. */
  public static Hours of(BigDecimal worked, BigDecimal overtime, int timesheetDays) {
    return new Hours(worked, overtime, timesheetDays);
  }
}
