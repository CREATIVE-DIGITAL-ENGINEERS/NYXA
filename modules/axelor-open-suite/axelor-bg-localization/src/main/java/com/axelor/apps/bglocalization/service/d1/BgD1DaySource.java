package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.d1.BgD1DayBuckets.Entry;
import com.axelor.apps.bglocalization.service.payroll.BgHistoricalLeaveFilter;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.db.JPA;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ДНИТЕ НА ЕДИН ФИШ, разпределени по кофите на т. 16 — <b>единственото място, което пита
 * базата</b>.
 *
 * <p>Разделението спрямо {@link BgD1DayBuckets} е същото като между {@code BgD1Emitter} и {@code
 * BgD1RecordBuilder}: сметката е чиста функция и се проверява без база; заявката живее тук и е
 * тънка.
 *
 * <p><b>⚠️ ДВА ИЗТОЧНИКА, И ТЕ НЕ СА РАВНОСТОЙНИ.</b>
 *
 * <ul>
 *   <li><b>Отпуските</b> се четат от {@code LeaveRequest} през съпоставянето {@code
 *       LeaveReason.bgAbsenceType.d1Code}. Заявката е ОГЛЕДАЛНА на {@code
 *       BgPayrollInputBuilder#leaveRows} — нарочно, до параметрите: два различни филтъра над едни и
 *       същи заявки биха дали различен брой дни на фиша и на декларацията му.
 *   <li><b>Болничните</b> НЕ се четат от заявките, а от ЗАМРАЗЕНИТЕ полета на фиша ({@code
 *       bgSickDaysEmployer} и {@code bgSickDaysDoo}). ⚠️ И това не е пряк път: епизодите пътуват
 *       като {@code BgPayrollInput.sickLeaveEpisodes}, а разделянето им на „за сметка на
 *       работодателя“ и „за сметка на ДОО“ вече е СМЕТНАТО от {@code BgSickLeaveResolver#split} и
 *       записано на фиша. Точно това разделяне Е разликата между т. 16.А и т. 16.2. Прочетено
 *       наново от епизодите, то щеше да е втора сметка на едно число — и коригиращата Д1 за минал
 *       месец щеше да го сметне по днешните правила.
 * </ul>
 *
 * <p><b>⚠️ ОТКАЗИТЕ НА СГЛОБИТЕЛЯ НЕ СЕ ПОВТАРЯТ ТУК, И ТОВА Е РЕШЕНИЕ.</b> {@code
 * BgPayrollInputBuilder} отказва целите кофи при заявка в часове, при заявка, пресичаща границата
 * на периода, и при половин ден. Тук същите случаи водят до НАЗОВАНА НАХОДКА и до пропускане на
 * РЕДА, а не до отказ на цялата декларация. Доводът е различната цена: там отказът спира ЕДИН фиш
 * преди да е платен, тук би спрял декларация за ДЕСЕТКИ лица заради една сгрешена заявка на едно от
 * тях. Затова декларацията се произвежда, а липсващите дни се ОБЯВЯВАТ поименно.
 */
@Singleton
public class BgD1DaySource {

  /** Състояние „валидирана“ на заявката за отпуск. Огледално на BgPayrollInputBuilder. */
  private static final int LEAVE_VALIDATED = 3;

  /** {@code LeaveReason.unitSelect} = ДНИ. Часовете не се събират с дни. */
  private static final int LEAVE_UNIT_DAYS = 1;

  /** Какво излезе от четенето: кофите-входове и онова, което не можа да се преброи. */
  public static final class Outcome {
    public final List<Entry> entries = new ArrayList<>();
    public final List<String> problems = new ArrayList<>();

    /**
     * ИНТЕРВАЛИТЕ, КОИТО ПРЕКЪСВАТ ОСИГУРЯВАНЕТО — <b>КСО чл. 10 ал. 2</b>, всеки като {@code {от,
     * до}}, орязан до периода.
     *
     * <p>Дословно: „Осигуряването се прекъсва през периодите, които НЕ СЕ ЗАЧИТАТ за осигурителен
     * стаж, независимо че дейността … не е прекратена.“ Тоест разграничителят е {@code
     * BgAbsenceType.countsAsInsuranceService}, а не празният {@code d1Code}: последният описва
     * „няма кофа в т. 16“ и днес двете съвпадат за петте вида, но описват РАЗЛИЧНИ неща и биха се
     * разминали при първата редакция на речника.
     *
     * <p>⚠️ ДАТИТЕ СЕ ОРЯЗВАТ ДО ПЕРИОДА, докато ДНИТЕ не могат: {@code duration} описва ЦЯЛАТА
     * заявка и частта ѝ в този месец не е извлечима от него, а границите на интервала са. Затова
     * заявка, пресичаща границата, дава НАХОДКА за дните и въпреки това участва тук.
     */
    public final List<LocalDate[]> interruptions = new ArrayList<>();

    /**
     * ЧАСОВЕТЕ за периода — отработени и извънреден труд, или {@code null} за „не са четени“.
     *
     * <p>⚠️ {@code null} е РАЗЛИЧНО от нула и разликата е законова: „нула отработени часа“ е
     * твърдение за лицето, а НАП го отхвърля за работещ човек. Инсталация, която не води отчети за
     * време, не е инсталация без отработени часове.
     */
    public BgD1HourSource.Hours hours;

    /**
     * ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО — изваждаемото на т. 16.1, <b>КСО чл. 10 ал. 2</b>.
     *
     * <p><b>⚠️ ОТДЕЛНО ПОЛЕ, А НЕ СЕДМА КОФА, и това не е стил.</b> {@link BgD1DayBuckets#BUCKETS}
     * са шест, защото заб. 1 към т. 16 иска дословно „сборът от дните, отразени в т. 16.1 до 16.5 и
     * т. 16.А“ — седми ключ би влязъл в {@link BgD1DayBuckets#total} и би надул позиции 1 и 2 с
     * дни, които НЕ са в осигуряване. А сентинел в {@link #entries} би бил обявен от {@link
     * BgD1DayBuckets#unknownBuckets} за непозната кофа, тоест за грешка.
     *
     * <p>⚠️ Нулата тук е СТОЙНОСТ („преброено, няма такива дни“), не „не е четено“ — точно както
     * при кофите. Дните са цели: т. 16 се попълва „в две позиции“.
     */
    public int outsideInsuranceDays;
  }

  /**
   * Дните на фиша по кофи.
   *
   * @param slip фишът — носи замразените болнични дни
   * @param dataPeriod периодът на ДАННИТЕ; той и само той определя кои заявки се броят
   */
  public Outcome read(BgPaySlip slip, Period dataPeriod) {
    Outcome out = new Outcome();
    if (slip == null || dataPeriod == null) {
      return out;
    }
    LocalDate from = dataPeriod.getFromDate();
    LocalDate to = dataPeriod.getToDate();
    if (from == null || to == null) {
      return out;
    }

    // ═══ БОЛНИЧНИТЕ — от замразените полета на фиша ═══
    // ⚠️ БЕЗ ПРОВЕРКА ЗА null: генерираният getter за Integer връща 0 (BgDefaultedGetterTest).
    int employerSickDays = slip.getBgSickDaysEmployer();
    if (employerSickDays > 0) {
      out.entries.add(new Entry(BgD1DayBuckets.B_16_A, employerSickDays));
    }
    // ⚠️ ДНИТЕ ДОО СЕ ДЕЛЯТ НА ДВЕ КОФИ ПО ЗАМРАЗЕНАТА ПРИСЪДА. т. 16.2 е за дните С право на
    // обезщетение; без право (стаж под 6 месеца, чл. 40 ал. 1 КСО) те са в т. 16.4 — неплатени дни
    // ВН, зачетени за стаж (КСО чл. 9 ал. 2 т. 2). Присъдата е на ФИША (bgSickDaysNoBenefit), не се
    // смята тук наново: днешният стаж може да е друг. min — ТРЗ може да е намалил дните ДОО на
    // ръка.
    int dooSickDays = slip.getBgSickDaysDoo();
    int sickWithoutBenefit = sickWithoutBenefit(slip);
    if (dooSickDays - sickWithoutBenefit > 0) {
      out.entries.add(new Entry(BgD1DayBuckets.B_16_2, dooSickDays - sickWithoutBenefit));
    }
    if (sickWithoutBenefit > 0) {
      out.entries.add(new Entry(BgD1DayBuckets.B_16_4, sickWithoutBenefit));
    }

    // ═══ ОТПУСКИТЕ — ОТ ЗАМРАЗЕНИТЕ ПОЛЕТА НА ФИША, когато ги има ═══
    // ⚠️ ДНИТЕ НЕ СЕ СМЯТАТ ТУК НАНОВО. „Огледална“ заявка над същите заявки за отпуск, които
    // плащат и фиша, се държи само докато никой не пипне едната половина. Разминат ли се правилата
    // (часова заявка, заявка през границата на периода, дробен ден), заявка 28.12–08.01 сваля 4 дни
    // от декемврийската заплата и НУЛА от декемврийската т. 16.5 — декларация, която обявява дни с
    // вноски, за които вноски няма.
    //
    // Затова фишът замразява дните по кофа (BgPaySlipService#freezeLeaveDays) и тук те само се
    // ЧЕТАТ. Същият идиом, по който болничните се четат отгоре, и по същия довод.
    boolean frozen = slip.getBgLeaveDaysFrozenAt() != null;
    if (frozen) {
      addFrozen(out, BgD1DayBuckets.B_16_1, slip.getBgLeaveDays161());
      addFrozen(out, BgD1DayBuckets.B_16_2, slip.getBgLeaveDays162());
      addFrozen(out, BgD1DayBuckets.B_16_3, slip.getBgLeaveDays163());
      addFrozen(out, BgD1DayBuckets.B_16_4, slip.getBgLeaveDays164());
      addFrozen(out, BgD1DayBuckets.B_16_5, slip.getBgLeaveDays165());
      addFrozen(out, BgD1DayBuckets.B_16_A, slip.getBgLeaveDays16A());
      out.outsideInsuranceDays += wholeDays(slip.getBgLeaveDaysOutside());
    }

    // ═══ ЗАЯВКИТЕ — за ПРЕКЪСВАНИЯТА, и за дните САМО когато няма замразено ═══
    // ⚠️ ЦИКЪЛЪТ ОСТАВА И ПРИ ЗАМРАЗЕНИ ДНИ, защото прекъсванията по КСО чл. 10 ал. 2 (т. 14/15)
    // НЕ са замразени: те са ИНТЕРВАЛИ, не броеве, и фишът няма къде да ги пази.
    EmploymentContract contract = slip.getEmploymentContract();
    Employee employee = contract == null ? null : contract.getEmployee();
    if (employee == null) {
      return out;
    }
    for (Object[] row : leaveRows(employee, from, to)) {
      LocalDate leaveFrom = row[0] == null ? null : ((LocalDateTime) row[0]).toLocalDate();
      LocalDate leaveTo = row[1] == null ? null : ((LocalDateTime) row[1]).toLocalDate();
      BigDecimal duration = row[2] instanceof BigDecimal ? (BigDecimal) row[2] : BigDecimal.ZERO;
      Integer unit = (Integer) row[3];
      String bucket = (String) row[4];
      String typeName = (String) row[5];
      Boolean countsAsInsuranceService = (Boolean) row[6];

      String label = typeName == null ? "(без съпоставен вид отсъствие)" : typeName;

      // ═══ КСО ЧЛ. 10 АЛ. 2 — ПРЕКЪСВАНЕТО СЕ ЧЕТЕ ПРЕДИ ВСЯКА ПРОВЕРКА ЗА ДНИ ═══
      // ⚠️ И ТОВА Е НАРОЧНО, не подредба. Проверките по-долу отказват ДНИТЕ на заявката (мерена в
      // часове, пресичаща границата, дробна) — но ГРАНИЦИТЕ на интервала са известни във всеки от
      // тези случаи. Заявка, чиито дни не могат да се преброят, ВСЕ ПАК прекъсва осигуряването, а
      // прескочена тук, тя щеше да слее два отрязъка в един и да премълчи прекъсване между тях.
      //
      // ⚠️ ЕДИНСТВЕНОТО ИЗКЛЮЧЕНИЕ Е ЧАСОВАТА ЗАЯВКА: отсъствие за част от деня НЕ прави деня
      // неосигурен. Прочетено като прекъсване, то би нарязало месеца на всеки два часа отпуск.
      if (Boolean.FALSE.equals(countsAsInsuranceService)
          && leaveFrom != null
          && leaveTo != null
          && unit != null
          && unit == LEAVE_UNIT_DAYS) {
        out.interruptions.add(new LocalDate[] {leaveFrom, leaveTo});
      }

      if (typeName == null) {
        // Липсващо съпоставяне значи „не се знае в коя кофа“ — и тогава дните изчезват от
        // декларацията. ⚠️ Различно от празен d1Code на СЪПОСТАВЕН вид, който е СТОЙНОСТ.
        out.problems.add(
            "заявка за отпуск "
                + leaveFrom
                + " – "
                + leaveTo
                + " не е съпоставена към вид отсъствие (LeaveReason.bgAbsenceType е празно) —"
                + " не се знае в коя кофа на т. 16 влизат дните ѝ. Броени в която и да е кофа, те"
                + " щяха да сменят точка, а НЕ да липсват: т. 16.1 намалява осигурителния стаж"
                + " по друг ред от т. 16.5.");
        continue;
      }
      // ⚠️ ЕДИН ПРЕДИКАТ, ДВА ЧИТАТЕЛЯ: същият метод чете и BgPayrollInputBuilder#leaveDaysOf,
      // когато сваля същите дни от заплатата. Множеството, което заплатата карвира, и
      // множеството, което т. 16.1 вади, трябва да СЪВПАДАТ — инак фишът плаща 17 дни, а
      // декларацията обявява 22 дни в осигуряване.
      boolean outside = BgD1DayBuckets.outsideInsurance(bucket, countsAsInsuranceService);
      if (BgD1DayBuckets.insuranceFlagsConflict(bucket, countsAsInsuranceService)) {
        // ⚠️ НАХОДКА, А НЕ ОТКАЗ — заварената асиметрия със сглобителя (виж javadoc-а на класа):
        // там противоречивият речник спира ЕДИН фиш преди да е платен, тук отказ би спрял
        // декларация за десетки лица. Денят НЕ влиза в изваждаемото: изваден при непразна кофа,
        // той би излязъл от т. 16.1 ДВА пъти.
        out.problems.add(
            "видът отсъствие „"
                + typeName
                + "“ си ПРОТИВОРЕЧИ: кофата в т. 16 („"
                + (bucket == null ? "" : bucket.trim())
                + "“) и признакът „зачита се за осигурителен стаж“ ("
                + (countsAsInsuranceService == null ? "непопълнен" : countsAsInsuranceService)
                + ") сочат в различни посоки. По КСО чл. 10 ал. 2 вторият решава дали периодът"
                + " ПРЕКЪСВА осигуряването (т. 14/15), а първият — в коя точка на т. 16 влизат"
                + " дните; несъгласни, двете правят декларацията вътрешно противоречива. Дните НЕ"
                + " са извадени от т. 16.1. Поправка: попълнете кофата или махнете отметката"
                + " „зачита се за осигурителен стаж“ на вида отсъствие.");
        if (bucket == null || bucket.trim().isEmpty()) {
          // Празна кофа и твърдение за признат стаж: няма къде да се броят дните. Обявено е.
          continue;
        }
        // Непразна кофа: дните остават В НЕЯ и вървят надолу като всички други. Точката, която
        // речникът изрично сочи, печели пред признака, който ѝ противоречи.
      }
      if (frozen) {
        // ⚠️ ДНИТЕ ВЕЧЕ СА ПРОЧЕТЕНИ. Оттук нататък този ред няма какво да добави към т. 16 — и
        // НЯМА ДА ОТКАЖЕ. Трите отказа отдолу не важат за сглобителя на фиша: при него часова
        // заявка СЕ брои (÷ договорен ден), заявка през границата СЕ реже по работни дни, дробен
        // ден СЕ носи. Приложени тук, те биха вдигнали WARNING върху числа, които фишът смята
        // вярно.
        continue;
      }
      // ⚠️ ПРАЗНАТА КОФА ПРИ НЕПРИЗНАТ СТАЖ Е СТОЙНОСТ и оттук нататък дните ѝ вървят като всички
      // останали — през същите проверки за часове, граница и цял ден, тоест сгрешена заявка за
      // самоотлъчка получава същата находка, която получава и сгрешеният платен отпуск.
      if (unit == null || unit != LEAVE_UNIT_DAYS) {
        out.problems.add(
            "заявка „"
                + label
                + "“ ("
                + leaveFrom
                + " – "
                + leaveTo
                + ") е измерена в ЧАСОВЕ, а т. 16 брои РАБОТНИ ДНИ. Дните ѝ НЕ са включени —"
                + " събрани направо, часове и дни дават сбор от несравними числа.");
        continue;
      }
      if (leaveFrom == null || leaveTo == null || leaveFrom.isBefore(from) || leaveTo.isAfter(to)) {
        out.problems.add(
            "заявка „"
                + label
                + "“ ("
                + leaveFrom
                + " – "
                + leaveTo
                + ") пресича границата на периода. duration описва ЦЯЛАТА заявка, не частта в този"
                + " месец — тоест броят ѝ дни тук би бил завишен. Дните ѝ НЕ са включени; разпредели"
                + " заявката по месеци или впиши дните на ръка.");
        continue;
      }
      Integer days = wholeDaysOrNull(duration);
      if (days == null) {
        out.problems.add(
            "заявка „"
                + label
                + "“ носи "
                + duration.toPlainString()
                + " дни — т. 16 се попълва в ЦЕЛИ дни („в две позиции“). Закръглянето е ПРАВИЛО, не"
                + " прочит, и не се прави тук. Дните ѝ НЕ са включени.");
        continue;
      }
      if (outside) {
        out.outsideInsuranceDays += days;
      } else if (bucket != null) {
        out.entries.add(new Entry(bucket.trim(), days));
      }
    }
    return out;
  }

  /**
   * Редовете на ВАЛИДИРАНИТЕ заявки, засягащи периода — с кофата от речника.
   *
   * <p>⚠️ Филтърът е ПРИПОКРИВАНЕ, а не „вътре в периода“, за да СТИГНЕ дотук и заявката, пресичаща
   * границата: тя трябва да предизвика находка, а не да бъде премълчана от заявката към базата.
   * Същият избор като в {@code BgPayrollInputBuilder#leaveRows}.
   */
  /** Exposed (not just inlined) so a test can assert the historical-leave filter is present. */
  public static final String LEAVE_ROWS_JPQL =
      "SELECT l.fromDateT, l.toDateT, l.duration, r.unitSelect, t.d1Code, t.name,"
          + " t.countsAsInsuranceService"
          + " FROM LeaveRequest l"
          + " JOIN l.leaveReason r"
          + " LEFT JOIN r.bgAbsenceType t"
          + " WHERE l.employee = :employee AND l.statusSelect = :validated"
          + " AND l.fromDateT <= :toEnd AND l.toDateT >= :fromStart"
          + BgHistoricalLeaveFilter.EXCLUDE_HISTORICAL
          + " ORDER BY l.fromDateT, l.id";

  protected List<Object[]> leaveRows(Employee employee, LocalDate from, LocalDate to) {
    return JPA.em()
        .createQuery(LEAVE_ROWS_JPQL, Object[].class)
        .setParameter("employee", employee)
        .setParameter("validated", LEAVE_VALIDATED)
        .setParameter("fromStart", from.atStartOfDay())
        .setParameter("toEnd", to.atTime(23, 59, 59))
        .getResultList();
  }

  /** Добавя замразена кофа, ако носи дни. Нулевата НЕ се добавя — тя е липса на входове. */
  private static void addFrozen(Outcome out, String bucket, BigDecimal days) {
    int whole = wholeDays(days);
    if (whole > 0) {
      out.entries.add(new Entry(bucket, whole));
    }
  }

  /**
   * ЗАМРАЗЕНИТЕ ДРОБНИ ДНИ → ЦЕЛИ, <b>HALF_UP</b>.
   *
   * <p><b>⚠️ ЗАКРЪГЛЯНЕТО Е ТУК, А НЕ ПРИ ЗАМРАЗЯВАНЕТО, И ТОВА Е РЕШЕНИЕ.</b> Т. 16 се попълва в
   * ЦЕЛИ дни — файловият формат дава на всяка кофа {@code Numeric <=2}, тоест дробното е
   * неизразимо. Но то е ФОРМАТНО изискване на декларацията, не истина за отсъствието: фишът
   * наистина е платил 4,5 дни. Закръглено при замразяването, две заявки по половин ден в един месец
   * щяха да дадат 1 + 1 = 2 вместо верните 1.
   *
   * <p><b>⚠️ ЗАЩО HALF_UP, А НЕ ОТКАЗ.</b> При отказ за дробния сбор дните му изчезват — тоест т.
   * 16.1 излиза по-голяма с точно толкова, мълчаливо. Указанието дава правило за превръщане на
   * часове в дни само за СУМИРАНО изчисляване (заб. 2 към т. 16: „при остатък, равен или по-голям
   * от половината на дневното договорено работно време, закръгляването се извършва към по-голямото
   * число“). Извън СИРВ норма няма, но посоката на нормата е ясна и е приложена по аналогия. ⚠️
   * Тайбрекът на заб. 2 („при всеки ДВА остатъка, равни на половината, единият се закръглява към
   * по-малкото“) НЕ е тук: той е състояние през целия месец и е задача на СИРВ, която ще чете
   * същите замразени полета.
   */
  static int wholeDays(BigDecimal days) {
    if (days == null || days.signum() <= 0) {
      return 0;
    }
    return days.setScale(0, RoundingMode.HALF_UP).intValueExact();
  }

  /** Цялото число дни, или {@code null} при дробен сбор. */
  static Integer wholeDaysOrNull(BigDecimal days) {
    if (days == null) {
      return null;
    }
    try {
      return days.setScale(0).intValueExact();
    } catch (ArithmeticException e) {
      return null;
    }
  }

  /**
   * От замразените дни ДОО на фиша — колко са БЕЗ ПРАВО на обезщетение (т. 16.4, не т. 16.2).
   *
   * <p>⚠️ ЕДНО МЯСТО ЗА ДВАМА ЧИТАТЕЛИ: тук (кофите) и {@code BgD1RecordBuilder#fillDays}, който ги
   * изключва от изваждаемото на т. 16.1 — болничните дни вече са извадени от отработените на фиша,
   * а отпуските в т. 16.4 не са. Ограничено отгоре от дните ДОО: ТРЗ може да ги е намалил на ръка.
   */
  public static int sickWithoutBenefit(BgPaySlip slip) {
    if (slip == null) {
      return 0;
    }
    // ⚠️ БЕЗ ПРОВЕРКА ЗА null: генерираният getter за Integer връща 0 — заварените фишове са 0.
    return Math.max(0, Math.min(slip.getBgSickDaysNoBenefit(), slip.getBgSickDaysDoo()));
  }
}
