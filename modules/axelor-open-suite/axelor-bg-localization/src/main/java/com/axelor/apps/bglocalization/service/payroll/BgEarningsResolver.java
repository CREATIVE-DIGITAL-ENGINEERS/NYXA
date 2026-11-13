package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.service.BgWorkTimeModeRule;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * ЧИСТАТА ПОЛОВИНА НА ЕНДЖИНА ЗА НАЧИСЛЕНИЯ — стъпка 3 по § 6.3.1: „основна заплата × (отработено /
 * норма) + клас прослужено време“.
 *
 * <p><b>Какво е това и какво НЕ е.</b> Тук живее САМО аритметиката и правилата ѝ: от (база,
 * отработени, норма, години, процент за година) към суми. Няма база данни, няма {@code Beans.get},
 * няма Hibernate — оттам класът върви на всяка машина, винаги, и падне ли тест, паднала е сметката,
 * а не средата. Кой чете процента от каталога и кой записва перото е работа на {@code
 * BgEarningsService}; тук това не се появява.
 *
 * <p><b>Закръгляне по § 5.3.</b> Междинните стъпки вървят с ПЪЛНА точност и закръглянето става
 * ВЕДНЪЖ, накрая, до 2 знака по „половина нагоре“. Затова всяка формула е записана като едно
 * умножение и едно деление, а не като верига от закръглени междинни числа: {@code база × процент ×
 * години × отработени / (100 × норма)} дава един и същ резултат до стотинката независимо от реда на
 * факторите, докато закръглена наполовина междинна стойност дава различен.
 *
 * <p><b>⚠️ БАЗАТА НА КЛАСА Е ОСНОВНАТА ЗАПЛАТА, НЕ БРУТОТО.</b> Това е най-честата грешка при
 * перото и е записана изрично на три места, които трябва да си останат съгласувани: бележката на
 * {@code pl_tspo} в {@code bg_pay_slip_line_type.csv}, бележката на {@code SENIORITY_MIN_PCT} в
 * {@code bg_payroll_parameter.csv} и javadoc-ът на {@code BgPayrollInput#baseSalary}. Оттам и
 * подписът на {@link #seniorityAmount}: той приема ОСНОВНАТА и няма как да му се подаде бруто по
 * невнимание, защото параметър „бруто“ в него не съществува.
 *
 * <p><b>⚠️ „НЕ ЗНАМ“ НЕ Е „НУЛА“.</b> Генерираните getter-и на Axelor връщат {@code
 * BigDecimal.ZERO} и {@code 0}, тоест непопълнено поле пристига като нула. Затова методите с {@code
 * Integer} и {@code BigDecimal} параметри тук РАЗЛИЧАВАТ {@code null} („не е подадено“) от нула
 * („подадено е и е нула“), а тези с {@code int} се викат само след като разликата вече е направена.
 * Липсваща основна заплата и липсваща норма дават ОТКАЗ с обявена причина, не сума нула.
 */
public final class BgEarningsResolver {

  /** Знаците след десетичната запетая на всяко перо — § 5.3. */
  public static final int SCALE = 2;

  /** „Половина нагоре“ — § 5.3. Едно и също правило навсякъде в модула. */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** Стабилният код на основното възнаграждение (PLT-100). Дисчпачът различава по него. */
  public static final String LINE_BASE_SALARY = "pl_osnovna";

  /** Стабилният код на класа „прослужено време“ (PLT-110). */
  public static final String LINE_SENIORITY = "pl_tspo";

  /** Възнаграждение за научна степен (PLT-120) — НСОРЗ чл. 11. */
  public static final String LINE_ACADEMIC_DEGREE = "pl_nauchna_stepen";

  /** Нощен труд (PLT-130) — НСОРЗ чл. 8. */
  public static final String LINE_NIGHT_WORK = "pl_nosten_trud";

  /** Време на разположение (PLT-140) — НСОРЗ чл. 10. */
  public static final String LINE_ON_CALL = "pl_razpolozhenie";

  /** Извънреден труд, работен ден (PLT-150) — КТ чл. 262 ал. 1 т. 1. */
  public static final String LINE_OVERTIME_WORKDAY = "pl_izv_raboten";

  /** Извънреден труд, почивен ден (PLT-151) — КТ чл. 262 ал. 1 т. 2. */
  public static final String LINE_OVERTIME_REST_DAY = "pl_izv_pochiven";

  /** Извънреден труд, официален празник (PLT-152) — КТ чл. 262 ал. 1 т. 3. */
  public static final String LINE_OVERTIME_HOLIDAY = "pl_izv_praznik";

  /** Извънреден труд при СИРВ (PLT-153) — КТ чл. 262 ал. 1 т. 4. */
  public static final String LINE_OVERTIME_SIRV = "pl_izv_sirv";

  /** Работа на официален празник (PLT-160) — КТ чл. 264. */
  public static final String LINE_HOLIDAY_WORK = "pl_praznik";

  /**
   * ТРИТЕ ПЕРА, ЧИЯТО СУМА ВИСИ ОТ ДНИТЕ ОТПУСК — и затова СПИРАТ, щом дните не се знаят.
   *
   * <p><b>⚠️ ТРИ, А НЕ ДЕСЕТ, И РАЗЛИКАТА Е ИЗМЕРИМА.</b> Седемте часови пера делят на НОРМАТА и на
   * договорените часове, не на отработените дни — отпускът не мени нито едно от техните числа.
   * Спрени и те, един несъпоставен вид отпуск щеше да махне от фиша и нощния труд, който няма нищо
   * общо с него.
   *
   * <p><b>⚠️ И ЗАЩО СПИСЪК, А НЕ ПРОВЕРКА „ЧЕТЕ ЛИ ПЕРОТО art177LeaveDays“.</b> Второто е питане за
   * реализацията и мълчи в деня, в който четвърто перо започне да карвира. Списъкът се сверява от
   * {@code BgUnpaidLeaveCarveTest} срещу онова, което {@link #resolve} наистина подава.
   */
  public static final java.util.Set<String> CARVED_LINES =
      java.util.Collections.unmodifiableSet(
          new java.util.LinkedHashSet<>(
              java.util.Arrays.asList(LINE_BASE_SALARY, LINE_SENIORITY, LINE_ACADEMIC_DEGREE)));

  /** Образователна и научна степен „доктор“ — {@code bg.academic.degree.select}. */
  public static final String DEGREE_DOCTOR = "DOCTOR";

  /** Научна степен „доктор на науките“ — {@code bg.academic.degree.select}. */
  public static final String DEGREE_DOCTOR_OF_SCIENCES = "DOCTOR_OF_SCIENCES";

  /** Сумирано изчисляване на работното време — {@code bg.work.time.mode.select}. */
  public static final String WORK_TIME_MODE_SIRV = "2";

  /**
   * ШЕСТТЕ СТОЙНОСТИ НА {@code bgCalculationBase} — речникът {@code
   * bg.payslip.calculation.base.select}.
   *
   * <p>⚠️ Списъкът е ЗАКОВАН с тест ({@code BgEarningsDispatchTest}) срещу самия речник и срещу
   * {@code bg_pay_slip_line_type.csv}. Седма стойност, добавена в изгледа без ред тук, се обажда
   * при билда — а не при първия фиш, който я носи. Точно това е разликата между дисчпач, за който
   * се знае, че е изчерпателен, и дисчпач, за който се вярва.
   */
  public static final String CALC_CONTRACT_BASE_SALARY = "CONTRACT_BASE_SALARY";

  /** Фиксирана сума на месец — научна степен (НСОРЗ чл. 11). Източникът е на трудовия договор. */
  public static final String CALC_MONTHLY_FIXED = "MONTHLY_FIXED";

  /**
   * Фиксирана сума на час — нощен труд и разположение (НСОРЗ чл. 8 / 10). Ставката НЕ е от
   * заплатата.
   */
  public static final String CALC_HOURLY_FIXED = "HOURLY_FIXED";

  /**
   * Процентно увеличение върху часовата ставка — извънреден и празничен труд (КТ чл. 262 / 264).
   */
  public static final String CALC_HOURLY_RATE_UPLIFT = "HOURLY_RATE_UPLIFT";

  /** Въвежда се на ръка. Не е работа на този енджин и никога няма да бъде. */
  public static final String CALC_MANUAL_AMOUNT = "MANUAL_AMOUNT";

  /** Идва от друг каталог — осигуровки, обезщетения, ретро-делта. Други услуги го пишат. */
  public static final String CALC_DERIVED = "DERIVED";

  /**
   * ⚠️ РЕШЕНИЕ № 1 — НАИМЕНУВАН ШЕВ: ПРОРАТИРА ЛИ СЕ КЛАСЪТ ПРИ НЕПЪЛЕН МЕСЕЦ. ДА, ПО ЧЛ. 12 АЛ. 8.
   *
   * <p><b>Основанието е ИЗРИЧНО, не изведено.</b> НСОРЗ чл. 12 ал. 8 постановява, че допълнителното
   * трудово възнаграждение за трудов стаж и професионален опит „се заплаща за ДЕЙСТВИТЕЛНО
   * ОТРАБОТЕНО ВРЕМЕ в рамките на съответната месечна продължителност на работното време“. Тоест
   * {@code true} е ИЗИСКВАНЕ на наредбата, а не избор — пропорцията „отработено/норма“ е точно
   * „действително отработено време в рамките на месечната продължителност“.
   *
   * <p>⚠️ § 6.3.1 стъпка 3 сам по себе си НЕ решава въпроса: там пропорцията е записана до
   * основната и не е записана до класа, тоест буквалното четене би дало „класът се дължи цял“.
   * Спецификацията не е източникът на това правило — наредбата е. Бележката стои, защото четенето
   * само на § 6.3.1 води до ГРЕШНИЯ отговор и следващият читател ще мине по същия път.
   *
   * <p>Обратната проверка потвърждава същото: при цял неотработен месец (неплатен отпуск) правилото
   * „цял клас“ дава заплата 0,00 и клас 120,00, тоест лице без нито един отработен ден получава
   * пари за стаж — резултат, който чл. 12 ал. 8 изключва пряко.
   *
   * <p>Константата остава НАИМЕНУВАНА и се чете на ЕДНО място, макар че стойността ѝ е задължена от
   * наредбата: обръщането ѝ на {@code false} поваля двата свидетеля в {@code
   * BgEarningsResolverTest}, тоест правилото е проверимо, а не разпръснато като подразбиране из
   * формулата. Смени ли се някога ал. 8, се сменя един ред — не се търси къде е скрито делението.
   */
  public static final boolean SENIORITY_FOLLOWS_WORKED_TIME = true;

  /**
   * ЗНАЦИТЕ НА ЧАСОВАТА СТАВКА — ЧЕТИРИ, и това е ДОКУМЕНТАЦИЯ, не стъпка от сметката.
   *
   * <p>⚠️ Ставката НЕ участва в изчислението на сумата. По § 5.3 делението е ЕДНО и е ПОСЛЕДНО:
   * сумата се смята като {@code база × часове × процент / (дни × часове-на-ден × 100)}. Закръглена
   * на четири знака и после умножена по часовете, тя би дала различно число при достатъчно часове —
   * 0,00005 × 300 часа × 3 е вече цяла стотинка.
   *
   * <p>Четири, а не два, защото ставката се ПОКАЗВА до сумата в {@code BgPaySlipLine.bgRate}, а на
   * два знака 10,9972 става 11,00 и умножена наум обяснява ДРУГА сума — точно каквото
   * документацията не бива да прави.
   */
  public static final int HOURLY_RATE_SCALE = 4;

  /** КТ чл. 262 ал. 1 т. 1 — МИНИМАЛНОТО увеличение за извънреден труд в работен ден. ПРОЦЕНТ. */
  public static final BigDecimal OVERTIME_WORKDAY_MIN_UPLIFT_PCT = new BigDecimal("50");

  /** КТ чл. 262 ал. 1 т. 2 — минималното увеличение за извънреден труд в почивен ден. ПРОЦЕНТ. */
  public static final BigDecimal OVERTIME_REST_DAY_MIN_UPLIFT_PCT = new BigDecimal("75");

  /** КТ чл. 262 ал. 1 т. 3 — минималното увеличение за извънреден труд на празник. ПРОЦЕНТ. */
  public static final BigDecimal OVERTIME_HOLIDAY_MIN_UPLIFT_PCT = new BigDecimal("100");

  /** КТ чл. 262 ал. 1 т. 4 — минималното увеличение за извънреден труд при СИРВ. ПРОЦЕНТ. */
  public static final BigDecimal OVERTIME_SIRV_MIN_UPLIFT_PCT = new BigDecimal("50");

  /**
   * САМИЯТ ЧАС — сто на сто от часовата ставка. ПРОЦЕНТ.
   *
   * <p>⚠️ НАИМЕНУВАНА, ЗАЩОТО Е ВЪПРОС, А НЕ КОНСТАНТА НА ЗАКОНА. Увеличенията по чл. 262 ал. 1 са
   * УВЕЛИЧЕНИЯ — те се добавят към заплащането за самия час. Дължи ли се и самият час, зависи от
   * това дали той вече е платен от друго перо, и точно този въпрос различава четирите случая по-
   * долу. Написано като „1,5“ в кода, разликата изчезва и следващият читател не може да я намери.
   */
  public static final BigDecimal THE_HOUR_ITSELF_PCT = new BigDecimal("100");

  /**
   * КТ чл. 264 — УДВОЕНИЯТ размер за работа на официален празник, ОБЩО. ПРОЦЕНТ.
   *
   * <p>⚠️ ОБЩО, А НЕ УВЕЛИЧЕНИЕ, и разликата е сто на сто. Чл. 264 дословно: „заплаща се според
   * уговореното, но не по-малко от УДВОЕНИЯ РАЗМЕР на трудовото му възнаграждение“ — тоест 200 на
   * сто е ПЪЛНОТО дължимо, а не добавка върху пълното. Прочетено като увеличение, празничният час
   * би излязъл 300 на сто, а с извънредния отгоре — 400.
   */
  public static final BigDecimal HOLIDAY_WORK_TOTAL_PCT = new BigDecimal("200");

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private BgEarningsResolver() {}

  // ═══ ЧИСТА АРИТМЕТИКА ═══════════════════════════════════════════════════

  /**
   * ОСНОВНО ВЪЗНАГРАЖДЕНИЕ = основна × отработени / норма.
   *
   * <p>Пълен месец ({@code worked == norm}) дава ТОЧНО основната — не „почти“: делението е
   * единствено и последно, тоест 1000,00 × 21 / 21 е 1000,00, а не 999,99.
   *
   * @param baseSalary основната месечна заплата по договора. Единица: ЕВРО на месец
   * @param worked отработени дни. Единица: РАБОТНИ ДНИ. Нула е ЗАКОННА (цял месец отсъствие)
   * @param norm работни дни в периода — делителят. Единица: РАБОТНИ ДНИ. Трябва да е > 0
   */
  public static BigDecimal baseSalaryAmount(BigDecimal baseSalary, int worked, int norm) {
    return baseSalaryAmount(baseSalary, BigDecimal.valueOf(worked), norm);
  }

  /**
   * Както горното, но с ДРОБЕН брой платими дни — картината след карвирането по чл. 177.
   *
   * <p><b>⚠️ ПУБЛИЧНА ВРАТА, А НЕ РАЗШИРЕН ШЕВ.</b> Целочисленият вход отгоре ОСТАВА и делегира.
   * Разширен на място, той щеше да поиска промяна във всеки свидетел, който го вика с {@code int} —
   * а промяната тук е ДОБАВЯНЕ на случай, не смяна на съществуващия.
   *
   * <p><b>⚠️ ДРОБНИЯТ ДЕН НЕ Е УДОБСТВО.</b> КТ чл. 166 дава отпуск за кърмене в ЧАСОВЕ, а чл. 157
   * ал. 1 т. 6 — по един час дневно при предизвестие, „в размера по чл. 177“ (ал. 3 т. 2). Един час
   * при осемчасов ден е 0,125 дни; закръглен до цял, карвът или надвзима, или не взима нищо.
   *
   * @param paidDays платимите дни СЛЕД карвирането: {@code отработени − дни по чл. 177}. Единица:
   *     РАБОТНИ ДНИ. Нула е ЗАКОННА (цял месец отпуск)
   */
  public static BigDecimal baseSalaryAmount(BigDecimal baseSalary, BigDecimal paidDays, int norm) {
    return baseSalary.multiply(paidDays).divide(BigDecimal.valueOf(norm), SCALE, ROUNDING);
  }

  /**
   * КЛАС ПРОСЛУЖЕНО ВРЕМЕ = основна × (процент × години) / 100, пропорционално на отработеното.
   *
   * <p>⚠️ Първият параметър е ОСНОВНАТА, не брутото — виж класовия javadoc. Пропорцията се прилага
   * или не според {@link #SENIORITY_FOLLOWS_WORKED_TIME}, и това е единственото място, където
   * константата се чете.
   *
   * @param baseSalary основната месечна заплата. Единица: ЕВРО на месец
   * @param ratePctPerYear процент за ВСЯКА навършена година. Единица: ПРОЦЕНТ (0,6 значи 0,6 на
   *     сто)
   * @param years навършени пълни години признат стаж. Единица: ГОДИНИ. Нула е законна (първа
   *     година)
   * @param worked отработени дни. Единица: РАБОТНИ ДНИ
   * @param norm работни дни в периода. Единица: РАБОТНИ ДНИ. Трябва да е > 0
   */
  public static BigDecimal seniorityAmount(
      BigDecimal baseSalary, BigDecimal ratePctPerYear, int years, int worked, int norm) {
    return seniorityAmount(baseSalary, ratePctPerYear, years, BigDecimal.valueOf(worked), norm);
  }

  /**
   * Както горното, но с ДРОБЕН брой платими дни — виж {@link #baseSalaryAmount(BigDecimal,
   * BigDecimal, int)} за довода.
   *
   * @param paidDays платимите дни СЛЕД карвирането: {@code отработени − дни по чл. 177}
   */
  public static BigDecimal seniorityAmount(
      BigDecimal baseSalary, BigDecimal ratePctPerYear, int years, BigDecimal paidDays, int norm) {
    BigDecimal numerator = baseSalary.multiply(ratePctPerYear).multiply(BigDecimal.valueOf(years));
    if (!SENIORITY_FOLLOWS_WORKED_TIME) {
      return numerator.divide(HUNDRED, SCALE, ROUNDING);
    }
    return numerator
        .multiply(paidDays)
        .divide(HUNDRED.multiply(BigDecimal.valueOf(norm)), SCALE, ROUNDING);
  }

  /**
   * ПЛОСКО МЕСЕЧНО ПЕРО СЛЕД КАРВИРАНЕТО ПО ЧЛ. 177 — {@code сума × (норма − дни) / норма}.
   *
   * <p><b>⚠️ ДЕЛИТЕЛЯТ Е НОРМАТА, А НЕ ОТРАБОТЕНИТЕ, И ТОВА Е РАЗЛИКАТА ОТ ДВЕТЕ ПЕРА ОТГОРЕ.</b>
   * Плоското перо (научна степен по НСОРЗ чл. 11) се заплаща за ЦЕЛИЯ месец и не се пропорционира
   * от отсъствията — тоест то покрива всичките {@code норма} дни, а не само отработените. Делено на
   * отработените, перото щеше да ПОРАСНЕ в месец с болничен: 100,00 ÷ 18 × 18 остава 100,00, но
   * 100,00 ÷ 18 × (18−5) е 72,22, докато вярното е 100,00 ÷ 22 × (22−5) = 77,27.
   *
   * <p>⚠️ Основанието да се карвира изобщо е НСОРЗ чл. 15 ал. 2: допълнително възнаграждение с
   * постоянен характер е онова, което „се изплаща постоянно заедно с полагащото се за съответния
   * период основно възнаграждение и е в зависимост ЕДИНСТВЕНО ОТ ОТРАБОТЕНОТО ВРЕМЕ“. Влиза ли в
   * базата по чл. 17, то се връща през перото по чл. 177 и не бива да се плаща и през себе си.
   *
   * @param monthly пълната месечна сума на перото. Единица: ЕВРО на месец
   * @param leaveDays дните платен отпуск по чл. 177. Единица: РАБОТНИ ДНИ
   * @param norm работни дни в периода. Трябва да е &gt; 0
   */
  public static BigDecimal monthlyFixedAfterArt177Leave(
      BigDecimal monthly, BigDecimal leaveDays, int norm) {
    return monthlyFixedAfterLeaveCarve(monthly, leaveDays, null, norm);
  }

  /**
   * ПЛОСКО МЕСЕЧНО ПЕРО СЛЕД ДВАТА КАРВА — платеният отпуск по чл. 177 И неплатеният отпуск.
   *
   * <p><b>⚠️ ДВАТА ИЗВАЖДАЕМИ СА В ЕДИН ЧИСЛИТЕЛ, А НЕ В ДВЕ ПОСЛЕДОВАТЕЛНИ ДЕЛЕНИЯ.</b> {@code
   * сума × (норма − a − b) / норма} и {@code (сума × (норма − a) / норма) × (норма − b) / норма} НЕ
   * са едно и също число: второто дели два пъти и закръглява два пъти, тоест при 100,00 €, норма 22
   * и по 5 дни от двата вида дава 59,71 вместо верните 54,55. Едно деление, накрая — § 5.3.
   *
   * <p><b>⚠️ И ДВАТА КАРВА ВАДЯТ, НО ПО РАЗЛИЧНА ПРИЧИНА, И ТОВА ТРЯБВА ДА СЕ ЗНАЕ.</b> Дните по
   * чл. 177 се ВРЪЩАТ през перото {@code pl_otpusk_177} — карвът им не е удръжка, а преместване.
   * Дните неплатен отпуск НЕ се връщат никъде и не бива: КТ чл. 128 т. 2 задължава работодателя да
   * плаща уговореното възнаграждение „ЗА ИЗВЪРШЕНАТА РАБОТА“, а институтът по чл. 160 носи името
   * „НЕПЛАТЕН отпуск“ именно защото през тези дни възнаграждение не се дължи.
   *
   * @param art177Days дните платен отпуск по чл. 177. {@code null} или нула — няма такъв карв
   * @param unpaidCarveDays дните, които Д1 вече вади от т. 16.1 — неплатеният отпуск с кофа плюс
   *     дните извън осигуряването. {@code null} или нула — няма такъв карв
   */
  public static BigDecimal monthlyFixedAfterLeaveCarve(
      BigDecimal monthly, BigDecimal art177Days, BigDecimal unpaidCarveDays, int norm) {
    BigDecimal carved = carvedDays(art177Days, unpaidCarveDays);
    if (carved.signum() <= 0) {
      return monthly.setScale(SCALE, ROUNDING);
    }
    return monthly
        .multiply(BigDecimal.valueOf(norm).subtract(carved))
        .divide(BigDecimal.valueOf(norm), SCALE, ROUNDING);
  }

  /**
   * СБОРЪТ НА ДВАТА ИЗВАЖДАЕМИ — с {@code null} и с отрицателно, прочетени като НУЛА.
   *
   * <p>⚠️ Отрицателен брой дни отпуск не съществува; прочетен буквално, той щеше да УВЕЛИЧАВА
   * платимите дни, тоест сгрешен знак някъде нагоре щеше да се превърне в по-голяма заплата вместо
   * в отказ. Затова и двата изваждаеми минават през {@code signum() > 0}.
   */
  static BigDecimal carvedDays(BigDecimal art177Days, BigDecimal unpaidCarveDays) {
    BigDecimal sum = BigDecimal.ZERO;
    if (art177Days != null && art177Days.signum() > 0) {
      sum = sum.add(art177Days);
    }
    if (unpaidCarveDays != null && unpaidCarveDays.signum() > 0) {
      sum = sum.add(unpaidCarveDays);
    }
    return sum;
  }

  /**
   * ПЛАТИМИТЕ ДНИ СЛЕД КАРВА, или {@code null}, ако карвът не може да се приложи честно.
   *
   * <p>⚠️ {@code null} тук значи „дните отпуск надхвърлят отработените“ — състояние, което НЕ е
   * нула платими дни, а противоречие във входа: лице не може да е в платен отпуск повече дни,
   * отколкото изобщо са му зачетени. Върнато като нула, то би обнулило заплатата тихо.
   */
  public static BigDecimal paidDaysAfterArt177Carve(Integer workedDays, BigDecimal art177Days) {
    return paidDaysAfterLeaveCarve(workedDays, art177Days, null);
  }

  /**
   * ПЛАТИМИТЕ ДНИ СЛЕД ДВАТА КАРВА — {@code отработени − дни по чл. 177 − дни неплатен отпуск}.
   *
   * <p><b>⚠️ ВТОРИ ИЗВАЖДАЕМ, А НЕ ПОПРАВКА НА {@code workedDays}.</b> Дните неплатен отпуск НЕ се
   * вадят от отработените на фиша, защото Декларация обр. 1 вече ги вади: т. 16.1 се ИЗВЕЖДА като
   * {@code bgWorkedDays − т. 16.3 − т. 16.4 − т. 16.5} ({@code BgD1RecordBuilder#fillDays}).
   * Намалени и тук, същите дни щяха да излязат от декларацията ДВА пъти и т. 16.1 щеше да обяви
   * по-малко осигурителен стаж, отколкото лицето има.
   *
   * <p><b>⚠️ И ЗАТОВА ТУК ВЛИЗАТ ТОЧНО ДНИТЕ, КОИТО Д1 ВАДИ ОТ Т. 16.1 — НИТО ПОВЕЧЕ, НИТО
   * ПО-МАЛКО.</b> Те са СБОР НА ДВЕ ДИЗЮНКТНИ МНОЖЕСТВА и сборът се прави един етаж по-горе, в
   * {@code BgEarningsService#carveDaysOf}: неплатеният отпуск С КОФА (т. 16.5 по КТ чл. 160 ал. 3 и
   * т. 16.3 по чл. 167а) и дните ИЗВЪН ОСИГУРЯВАНЕТО (КСО чл. 10 ал. 2 — неплатеният над 30 работни
   * дни, неприсъствието, самоотлъчката и непредставеният болничен). Тук пристига вече едно число.
   *
   * <p>⚠️ Едно, а не две: разликовият карв по чл. 177 ({@code BgEarningsService#art177Inputs}) вика
   * този енджин ДВА ПЪТИ и изисква всичко освен дните по чл. 177 да стои НЕПОДВИЖНО между двете
   * извиквания. Второ изваждаемо, носено поотделно, е втори шанс за разминаване от вида 227,28
   * срещу 227,27.
   *
   * <p>⚠️ {@code null} значи „сборът на двата вида отпуск надхвърля отработените“ — противоречие
   * във входа, не нула платими дни.
   *
   * @param unpaidCarveDays дните, които Д1 вади от т. 16.1 — с кофа И извън осигуряването, вече
   *     сгънати в едно число. {@code null} или нула — няма карв
   */
  public static BigDecimal paidDaysAfterLeaveCarve(
      Integer workedDays, BigDecimal art177Days, BigDecimal unpaidCarveDays) {
    if (workedDays == null) {
      return null;
    }
    BigDecimal worked = BigDecimal.valueOf(workedDays);
    BigDecimal carved = carvedDays(art177Days, unpaidCarveDays);
    if (carved.signum() <= 0) {
      return worked;
    }
    BigDecimal left = worked.subtract(carved);
    return left.signum() < 0 ? null : left;
  }

  /**
   * НАВЪРШЕНИТЕ ПЪЛНИ ГОДИНИ признат стаж — броени ОТ КОТВЕНАТА ДАТА, не от сбора.
   *
   * <p><b>⚠️ РЕШЕНИЕ № 2 — НАИМЕНУВАН ШЕВ: В КОЙ МЕСЕЦ СЕ ВДИГА КЛАСЪТ. ИЗБРАНАТА ПОЛИТИКА: В
   * МЕСЕЦА НА НАВЪРШВАНЕ.</b> Мери се към {@code periodTo}, тоест към ПОСЛЕДНИЯ ден на периода:
   * година, навършена на 15 март, вдига класа ЗА МАРТ.
   *
   * <p><b>Защо това е политика, а не норма.</b> НСОРЗ чл. 12 ал. 10 определя само ИНТЕРВАЛА — че
   * размерът се изменя на период НЕ ПО-МАЛЪК от една година придобит трудов стаж и професионален
   * опит. Законът фиксира на колко често, но НЕ точния ден или месец на влизане в сила; това остава
   * на работодателя. Другото четимо правило — „от месеца СЛЕД навършването“ — също би спазвало ал.
   * 10, и точно затова изборът е решение, а не извод.
   *
   * <p>⚠️ Тъкмо този въпрос javadoc-ът на {@code BgPayrollInput#recognisedServiceStartDate}
   * предвижда като отворен и заради него контрактът носи ДАТА, а не сбор: от „13 години към
   * 30.11.2026“ месецът на навършване е неизразим. Смяната на политиката е смяна на този един
   * аргумент ({@code periodFrom} вместо {@code periodTo}), а свидетелят „годишнина на последния ден
   * брои“ казва кое от двете е в сила.
   *
   * <p>⚠️ Брои се от ДАТАТА, а НЕ от {@code BgPayrollInput#recognisedServiceYears}: сборът е
   * предходният стаж и WS-5 ще го сумира по-късно, а при разминаване печели датата — така казва и
   * самият контракт. Оттам и подписът: този метод НЕ приема сбор и няма как да го ползва по
   * невнимание.
   *
   * <p>Котва СЛЕД края на периода дава 0, а не отрицателно число: стаж, който още не е започнал, е
   * нула навършени години — и това е ИЗЧИСЛЕНА нула, не непопълнено поле.
   *
   * @param anchor началото на признатия стаж ({@code recognisedServiceStartDate}). Не {@code null}
   * @param periodTo последният ден на периода. Не {@code null}
   */
  public static int completedServiceYears(LocalDate anchor, LocalDate periodTo) {
    long years = ChronoUnit.YEARS.between(anchor, periodTo);
    return years < 0 ? 0 : (int) years;
  }

  /**
   * ПРОЦЕНТЪТ ЗА ГОДИНА, с наложен ЗАКОНОВ ПОД.
   *
   * <p>{@code SENIORITY_MIN_PCT} (0,6000) е ДОЛНА граница по ПМС № 147/2007, не стойност по
   * подразбиране: работодателят може да договори повече, но не и по-малко. Затова уговореният
   * процент не замества минимума, а се СРАВНЯВА с него и по-ниският се ВДИГА — мълчаливо приетият
   * по-нисък процент е недоплатено възнаграждение, което никой отчет не показва.
   *
   * <p>Непопълненият процент на дружеството пристига като нула (генериран getter) и затова се чете
   * като „не е уговорен“, а не като „уговорени са нула на сто“ — нула на сто е невъзможна уговорка
   * под законов минимум.
   *
   * @param companyRatePct {@code Company.bgSeniorityRatePct} или {@code null}
   * @param statutoryMinPct стойността на {@code SENIORITY_MIN_PCT} за периода. Не {@code null}
   */
  public static BigDecimal effectiveSeniorityRatePct(
      BigDecimal companyRatePct, BigDecimal statutoryMinPct) {
    if (companyRatePct == null || companyRatePct.signum() <= 0) {
      return statutoryMinPct;
    }
    return companyRatePct.compareTo(statutoryMinPct) < 0 ? statutoryMinPct : companyRatePct;
  }

  // ═══ ПРАВИЛАТА ЗА „НЕПОПЪЛНЕНО ≠ НУЛА“ ══════════════════════════════════

  /**
   * Основното перо — сума ИЛИ обявена липса.
   *
   * <p>Отказва при непопълнена основна заплата и при непопълнена норма, защото и двете дават нула
   * по пътя на генерирания getter, а нула тук значи „лицето няма заплата този месец“. Отработени
   * НУЛА дни при положителна норма, обратно, е ЗАКОНЕН случай (цял месец неплатен отпуск) и дава
   * сума 0,00.
   */
  public static BgEarningsOutcome baseSalaryLine(
      BigDecimal baseSalary, Integer workedDays, Integer workingDaysInPeriod) {
    return baseSalaryLine(baseSalary, workedDays, workingDaysInPeriod, null);
  }

  /**
   * Както горното, но с КАРВА по чл. 177 — дните платен отпуск излизат от основната заплата.
   *
   * <p><b>⚠️ ПУБЛИЧНА ВРАТА, А НЕ РАЗШИРЕН ШЕВ.</b> Триаргументният вход остава и делегира с {@code
   * null}, тоест всеки заварен повикващ получава БИТ ПО БИТ днешното число.
   *
   * <p><b>⚠️ КАРВЪТ НЕ Е УДРЪЖКА.</b> Дните не изчезват — те се плащат през {@code pl_otpusk_177}
   * по среднодневното от ПРЕДХОДНИЯ месец (КТ чл. 177 ал. 1). При непроменен състав на
   * възнаграждението двете се компенсират до стотинката; мръдне ли базовият месец, мърда и
   * заплащането — точно каквото нормата иска. Без карва отпускът щеше да се плати ДВА пъти.
   *
   * <p>⚠️ Количеството на реда остава {@code paidDays}, а не отработените: числото до сумата трябва
   * да обяснява ИМЕННО нея. Оставено на отработените, редът би твърдял 22 дни срещу сума за 17 и
   * следващият читател би сметнал друга ставка.
   *
   * @param art177LeaveDays дните платен отпуск по чл. 177. {@code null} или нула — няма карв
   */
  public static BgEarningsOutcome baseSalaryLine(
      BigDecimal baseSalary,
      Integer workedDays,
      Integer workingDaysInPeriod,
      BigDecimal art177LeaveDays) {
    return baseSalaryLine(baseSalary, workedDays, workingDaysInPeriod, art177LeaveDays, null);
  }

  /**
   * Както горното, но и с КАРВА ЗА НЕПЛАТЕНИЯ ОТПУСК — виж {@link #paidDaysAfterLeaveCarve}.
   *
   * <p><b>⚠️ ПУБЛИЧНА ВРАТА, А НЕ РАЗШИРЕН ШЕВ</b> — четириаргументният вход остава и делегира с
   * {@code null}, тоест всеки заварен повикващ получава БИТ ПО БИТ днешното число.
   *
   * <p><b>⚠️ ТОЗИ КАРВ НЕ СЕ ВРЪЩА НИКЪДЕ, И ТОВА Е РАЗЛИКАТА ОТ ЧЛ. 177.</b> Дните по чл. 177 се
   * плащат през {@code pl_otpusk_177} и двете се компенсират до стотинката; неплатеният отпуск няма
   * такова перо и не бива да има — КТ чл. 128 т. 2 дължи възнаграждение „за извършената работа“, а
   * чл. 160 нарича института „НЕПЛАТЕН отпуск“. Тоест тук фишът наистина плаща по-малко, и точно
   * това е поправката.
   *
   * @param unpaidCarveDays дните, които Д1 вади от т. 16.1 (с кофа + извън осигуряването). {@code
   *     null} или нула — няма карв
   */
  public static BgEarningsOutcome baseSalaryLine(
      BigDecimal baseSalary,
      Integer workedDays,
      Integer workingDaysInPeriod,
      BigDecimal art177LeaveDays,
      BigDecimal unpaidCarveDays) {
    String missing = missingCommonInput(baseSalary, workedDays, workingDaysInPeriod);
    if (missing != null) {
      return BgEarningsOutcome.refused(LINE_BASE_SALARY, missing);
    }
    BigDecimal paidDays = paidDaysAfterLeaveCarve(workedDays, art177LeaveDays, unpaidCarveDays);
    if (paidDays == null) {
      return BgEarningsOutcome.refused(
          LINE_BASE_SALARY, leaveOverrunRefusal(workedDays, art177LeaveDays, unpaidCarveDays));
    }
    return BgEarningsOutcome.computed(
        LINE_BASE_SALARY,
        baseSalaryAmount(baseSalary, paidDays, workingDaysInPeriod),
        paidDays,
        baseSalary);
  }

  /** Дните по чл. 177 надхвърлят зачетените — противоречие във входа, не нула заплата. */
  static String art177OverrunRefusal(Integer workedDays, BigDecimal art177LeaveDays) {
    return leaveOverrunRefusal(workedDays, art177LeaveDays, null);
  }

  /**
   * СБОРЪТ НА ДВАТА ВИДА ОТПУСК НАДХВЪРЛЯ ЗАЧЕТЕНИТЕ ДНИ — противоречие във входа, не нула заплата.
   *
   * <p>⚠️ Причината назовава И ДВАТА изваждаеми, дори когато единият е нула: операторът вижда числа
   * и трябва да може да сметне разликата сам. Съобщение само за единия би пратило човека да поправя
   * заявка, която не е сгрешената.
   */
  static String leaveOverrunRefusal(
      Integer workedDays, BigDecimal art177LeaveDays, BigDecimal unpaidCarveDays) {
    return "дните отпуск — "
        + nz(art177LeaveDays)
        + " платен по чл. 177 и "
        + nz(unpaidCarveDays)
        + " неплатен, зачетен за осигурителен стаж — надхвърлят отработените дни на фиша ("
        + workedDays
        + "). Отпуск не може да се ползва през дни, които изобщо не са зачетени — или заявката за"
        + " отпуск покрива дни извън периода, или болничният вече е свалил същите дни от"
        + " отработените, или отработените дни са били намалени РЪЧНО с отсъствията (те се пълнят с"
        + " нормата на месеца и отпускът НЕ се вади от тях). Провери заявките и болничните листове"
        + " за месеца. ⚠️ БЕЗ карв заплатата НЕ"
        + " се смята: отрицателни платими дни биха дали отрицателна основна заплата, а нула би"
        + " платила месеца като цял отпуск.";
  }

  /** {@code null} като „0“ в текста на причина — липсващото число не бива да се чете „null“. */
  private static String nz(BigDecimal value) {
    return value == null ? "0" : value.toPlainString();
  }

  /**
   * Перото за клас прослужено време — сума, ОБЯВЕНА ЛИПСА или ОТРЕЧЕНО ПРАВО.
   *
   * <p><b>⚠️ ПЪРВИЯТ ВЪПРОС Е ПРАВОТО, НЕ СМЕТКАТА.</b> НСОРЗ чл. 12 ал. 1 заплаща допълнителното
   * възнаграждение за трудов стаж и професионален опит „на работниците и служителите“, в процент
   * върху основната заплата, „определена с ИНДИВИДУАЛНИЯ ТРУДОВ ДОГОВОР“; чл. 2 ал. 2 стеснява
   * цялата наредба до работещите ПО ТРУДОВО ПРАВООТНОШЕНИЕ. Тоест при договор за управление и
   * контрол, при граждански договор и при самоосигуряващо се лице формулата долу не е „вярна, но
   * дава нула“ — тя изобщо не се прилага. Без този въпрос класът би се начислил на всеки, който има
   * основна заплата и котва на стажа, включително на ДУК.
   *
   * <p>⚠️ Правото НЕ се извежда от „има ли лицето фонд ГВРС“, макар т. 29/т. 30 на Д1 да четат
   * трудовото правоотношение точно оттам. Двете множества се разминават на ВИД ОСИГУРЕН 09
   * („членове на кооперации … БЕЗ трудово правоотношение“), който носи ред за ГВРС и няма ТПО:
   * преизползван, сигналът щеше да начисли клас точно там, където нормата го отрича. Оста е отделна
   * и изрична — {@code BgInsuredType.bgEntitledToLengthOfServiceBonus}.
   *
   * <p>При налично право отказва при същите две липси като основното, плюс при липсващ процент
   * („параметърът {@code SENIORITY_MIN_PCT} не е зареден за периода“) и при неизвестни години
   * („котвата на стажа липсва“). НУЛА навършени години е ИЗЧИСЛЕНА нула — първата година на стажа —
   * и дава сума 0,00.
   *
   * @param entitled {@code TRUE} за трудово правоотношение, {@code FALSE} за установено неТПО,
   *     {@code null} за „никой не е питан“ — виж {@code BgEarningsFacts#entitledToSeniorityBonus}
   * @param completedYears резултатът на {@link #completedServiceYears}, или {@code null}, ако
   *     котвата липсва
   * @param ratePctPerYear резултатът на {@link #effectiveSeniorityRatePct}, или {@code null}, ако
   *     параметърът не е зареден
   */
  public static BgEarningsOutcome seniorityLine(
      Boolean entitled,
      BigDecimal baseSalary,
      BigDecimal ratePctPerYear,
      Integer completedYears,
      Integer workedDays,
      Integer workingDaysInPeriod) {
    return seniorityLine(
        entitled,
        baseSalary,
        ratePctPerYear,
        completedYears,
        workedDays,
        workingDaysInPeriod,
        null);
  }

  /**
   * Както горното, но с КАРВА по чл. 177 — виж {@link #baseSalaryLine(BigDecimal, Integer, Integer,
   * BigDecimal)}.
   *
   * <p>⚠️ Класът СЕ карвира, макар чл. 12 ал. 8 вече да го проратира по отработеното време: той е
   * ДТВ с постоянен характер (чл. 15 ал. 1) и затова влиза в базата по чл. 17 ал. 1 т. 3, тоест
   * перото по чл. 177 го връща. Некарвиран, той щеше да е единственото перо, платено два пъти.
   */
  public static BgEarningsOutcome seniorityLine(
      Boolean entitled,
      BigDecimal baseSalary,
      BigDecimal ratePctPerYear,
      Integer completedYears,
      Integer workedDays,
      Integer workingDaysInPeriod,
      BigDecimal art177LeaveDays) {
    return seniorityLine(
        entitled,
        baseSalary,
        ratePctPerYear,
        completedYears,
        workedDays,
        workingDaysInPeriod,
        art177LeaveDays,
        null);
  }

  /**
   * Както горното, но и с КАРВА ЗА НЕПЛАТЕНИЯ ОТПУСК — виж {@link #baseSalaryLine(BigDecimal,
   * Integer, Integer, BigDecimal, BigDecimal)}.
   *
   * <p>⚠️ Класът СЕ карвира и за неплатения отпуск, при това по СВОЯ норма: чл. 12 ал. 8 го заплаща
   * „за действително отработено време“, а дните неплатен отпуск не са отработено време по никое
   * четене. Некарвиран, той щеше да плаща клас за дни, през които трудът е спрян.
   *
   * @param unpaidCarveDays дните, които Д1 вади от т. 16.1 (с кофа + извън осигуряването). {@code
   *     null} или нула — няма карв
   */
  public static BgEarningsOutcome seniorityLine(
      Boolean entitled,
      BigDecimal baseSalary,
      BigDecimal ratePctPerYear,
      Integer completedYears,
      Integer workedDays,
      Integer workingDaysInPeriod,
      BigDecimal art177LeaveDays,
      BigDecimal unpaidCarveDays) {
    // ⚠️ ПРАВОТО СЕ ПИТА ПЪРВО, ПРЕДИ ВСЯКА ЛИПСА, И РЕДЪТ НЕ Е СВОБОДЕН. Управителят по ДУК рядко
    // има попълнен признат стаж — питано второ, лицето щеше да получи отказ „попълни котвата на
    // стажа“ вместо отговора „класът не ти се дължи“, и операторът щеше да поправи вярното поле в
    // грешна посока. Липса се обявява само на онзи, на когото перото ИЗОБЩО се полага.
    if (Boolean.FALSE.equals(entitled)) {
      return BgEarningsOutcome.notEntitled(
          LINE_SENIORITY,
          "класът прослужено време не се дължи по това правоотношение. НСОРЗ чл. 12 ал. 1 дава"
              + " допълнителното възнаграждение за трудов стаж и професионален опит на"
              + " РАБОТНИЦИТЕ И СЛУЖИТЕЛИТЕ, в процент върху основната заплата, „определена с"
              + " индивидуалния трудов договор“, а чл. 2 ал. 2 стеснява цялата наредба до"
              + " работещите ПО ТРУДОВО ПРАВООТНОШЕНИЕ. Договорът за управление и контрол (вид"
              + " осигурен 10), гражданският договор (14) и самоосигуряващото се лице са извън"
              + " нормата. Признакът е ДАННИ — BgInsuredType.bgEntitledToLengthOfServiceBonus —"
              + " и се сменя там, не тук.");
    }
    if (entitled == null) {
      return BgEarningsOutcome.refused(
          LINE_SENIORITY,
          "не е установено дали правоотношението е трудово, а от това виси самото право на клас"
              + " (НСОРЗ чл. 12 ал. 1). Или видът осигурен не е попълнен по договора, или"
              + " речникът с видове осигурен няма ред, валиден за периода — пусни App Install"
              + " и попълни вида осигурен. БЕЗ отговор класът НЕ се"
              + " смята: прието за „да“, това начислява клас на управител по ДУК; прието за „не“,"
              + " отнема го на всеки нает. И двете биха минали мълчаливо.");
    }
    String missing = missingCommonInput(baseSalary, workedDays, workingDaysInPeriod);
    if (missing != null) {
      return BgEarningsOutcome.refused(LINE_SENIORITY, missing);
    }
    if (ratePctPerYear == null || rateIsUnusable(ratePctPerYear)) {
      return BgEarningsOutcome.refused(
          LINE_SENIORITY,
          "процентът за клас прослужено време не е определен — параметърът SENIORITY_MIN_PCT липсва за"
              + " периода. Зареди го през App Install; БЕЗ него класът НЕ"
              + " се смята като нула, защото законовият минимум по ПМС № 147/2007 е 0,6 на сто, а не"
              + " нула.");
    }
    if (completedYears == null) {
      return BgEarningsOutcome.refused(
          LINE_SENIORITY,
          "признатият стаж няма начална дата — попълни началото на договора и признатия предходен стаж"
              + " (bgPriorExperienceYears/Months/Days). БЕЗ котва навършените години са НЕИЗВЕСТНИ, а"
              + " не нула: нула би значело „лицето е в първата си година“.");
    }
    BigDecimal paidDays = paidDaysAfterLeaveCarve(workedDays, art177LeaveDays, unpaidCarveDays);
    if (paidDays == null) {
      return BgEarningsOutcome.refused(
          LINE_SENIORITY, leaveOverrunRefusal(workedDays, art177LeaveDays, unpaidCarveDays));
    }
    return BgEarningsOutcome.computed(
        LINE_SENIORITY,
        seniorityAmount(baseSalary, ratePctPerYear, completedYears, paidDays, workingDaysInPeriod),
        BigDecimal.valueOf(completedYears),
        ratePctPerYear);
  }

  /** Двете липси, общи за двете пера: без тях нито едната формула не е определена. */
  private static String missingCommonInput(
      BigDecimal baseSalary, Integer workedDays, Integer workingDaysInPeriod) {
    if (baseSalary == null || baseSalary.signum() <= 0) {
      return "основната заплата не е попълнена — попълни я на трудовия договор или на фиша. Нула тук"
          + " НЕ се чете като „заплата нула лева“: генерираният getter връща нула и за непопълнено"
          + " поле, а фиш с нула щеше да изглежда просто като договор без заплата.";
    }
    if (workingDaysInPeriod == null || workingDaysInPeriod <= 0) {
      return "работните дни в периода не са определени — прегледай производствения календар за"
          + " годината или впиши числото на ръка на фиша. БЕЗ делител пропорцията „отработено/норма“"
          + " не съществува, а не е равна на нула.";
    }
    if (workedDays == null || workedDays < 0) {
      return "отработените дни не са подадени — впиши ги на фиша. Празно НЕ е нула отработени дни:"
          + " нула значи цял месец отсъствие и е ЗАКОНЕН случай, който операторът заявява изрично.";
    }
    return null;
  }

  /** Процент нула или отрицателен не е процент — минимумът по ПМС № 147/2007 е 0,6 на сто. */
  private static boolean rateIsUnusable(BigDecimal ratePctPerYear) {
    return ratePctPerYear.signum() <= 0;
  }

  // ═══ ЧАСОВИЯТ ЕНДЖИН — ЧИСТА АРИТМЕТИКА ═════════════════════════════════

  /**
   * БАЗАТА ПО НСОРЗ ЧЛ. 7 — основната заплата ПЛЮС допълнителните възнаграждения с ПОСТОЯНЕН
   * характер, НЕПРОРАТИРАНА.
   *
   * <p><b>Основанието е дословно.</b> НСОРЗ чл. 7: „За положения извънреден труд се заплаща
   * увеличение в минималните размери по реда на чл. 262 от Кодекса на труда. Увеличението се
   * изчислява на базата на ОСНОВНАТА РАБОТНА ЗАПЛАТА И ДОПЪЛНИТЕЛНИТЕ ТРУДОВИ ВЪЗНАГРАЖДЕНИЯ С
   * ПОСТОЯНЕН ХАРАКТЕР на работника или служителя по индивидуалния трудов договор, освен ако е
   * договорено друго.“
   *
   * <p><b>Кои са „с постоянен характер“ — и то също е норма, не преценка.</b> НСОРЗ чл. 15 ал. 1:
   * „Допълнителните трудови възнаграждения по ЧЛ. 11 И 12 са с постоянен характер“ — тоест научната
   * степен и класът прослужено време влизат по силата на текста. Ал. 2 добавя и всяко друго, което
   * „се изплаща постоянно заедно с полагащото се за съответния период основно възнаграждение и е в
   * зависимост ЕДИНСТВЕНО от отработеното време“. Точно този критерий носи колоната {@code
   * bgIsPermanentCharacter} в {@code bg_pay_slip_line_type.csv} — оттам идва {@code
   * permanentAllowances}, а не от списък с кодове тук.
   *
   * <p><b>⚠️ НЕПРОРАТИРАНА, И ТОВА Е ДРУГО ЧИСЛО ОТ ПЕРОТО.</b> Класът НА ФИША се пропорционира по
   * чл. 12 ал. 8 („за действително отработено време“), но базата по чл. 7 е УГОВОРЕНОТО МЕСЕЧНО
   * възнаграждение „по индивидуалния трудов договор“ — тоест часовата ставка на човек, който е бил
   * половин месец болен, НЕ е наполовина. Взета от пропорционираните пера, ставката щеше да пада
   * точно в месеците с отсъствия и извънредният труд щеше да се плаща по-евтино заради болест.
   *
   * <p>⚠️ Всяка съставка е закръглена до 2 знака ПРЕДИ сбора, и това не нарушава § 5.3: те не са
   * междинни стойности, а самостоятелни месечни суми по договора. Единственото деление — онова на
   * часовата ставка — остава последно.
   *
   * @param baseSalary основната месечна заплата. Единица: ЕВРО на месец
   * @param entitledToSeniority {@code TRUE} за трудово правоотношение; при {@code FALSE} класът е
   *     нула и НЕ се иска процент
   * @param seniorityRatePct процентът за година с наложен под, или {@code null}
   * @param completedYears навършените пълни години, или {@code null}
   * @param degreeAmount сумата за научна степен, или {@code null} за „няма“
   * @param permanentAllowances другите постоянни ДТВ, вече на фиша, или {@code null} за нула
   */
  public static BigDecimal permanentPayBase(
      BigDecimal baseSalary,
      Boolean entitledToSeniority,
      BigDecimal seniorityRatePct,
      Integer completedYears,
      BigDecimal degreeAmount,
      BigDecimal permanentAllowances) {
    BigDecimal base = baseSalary.setScale(SCALE, ROUNDING);
    if (!Boolean.FALSE.equals(entitledToSeniority)
        && seniorityRatePct != null
        && completedYears != null) {
      base =
          base.add(
              baseSalary
                  .multiply(seniorityRatePct)
                  .multiply(BigDecimal.valueOf(completedYears))
                  .divide(HUNDRED, SCALE, ROUNDING));
    }
    if (degreeAmount != null) {
      base = base.add(degreeAmount);
    }
    if (permanentAllowances != null) {
      base = base.add(permanentAllowances);
    }
    return base;
  }

  /**
   * ЧАСОВАТА СТАВКА — база по чл. 7 / (работни дни × договорени часове на ден).
   *
   * <p>⚠️ ДОКУМЕНТАЦИЯ, НЕ СТЪПКА. Виж {@link #HOURLY_RATE_SCALE}: сумите се смятат с едно деление
   * накрая и НЕ минават през това число. То се показва до сумата, за да се вижда откъде идва.
   *
   * <p>⚠️ Делителят е ДОГОВОРЕНОТО работно време, а не законовите осем — виж {@code
   * BgEarningsFacts#contractHoursPerDay}.
   *
   * @param permanentPayBase резултатът на {@link #permanentPayBase}. Единица: ЕВРО на месец
   * @param workingDaysInPeriod работните дни в периода. Трябва да е &gt; 0
   * @param hoursPerDay договорените часове на ден. Трябва да е &gt; 0
   */
  public static BigDecimal hourlyRate(
      BigDecimal permanentPayBase, int workingDaysInPeriod, BigDecimal hoursPerDay) {
    return permanentPayBase.divide(
        BigDecimal.valueOf(workingDaysInPeriod).multiply(hoursPerDay), HOURLY_RATE_SCALE, ROUNDING);
  }

  /**
   * СУМАТА НА ЕДНО ЧАСОВО ПЕРО — едно умножение и ЕДНО деление, накрая (§ 5.3).
   *
   * <p>{@code база × часове × процент / (дни × часове-на-ден × 100)}. Записана така, формулата дава
   * един и същ резултат до стотинката независимо от реда на факторите, докато същата сметка през
   * закръглена часова ставка се разминава с нея при достатъчно часове.
   *
   * @param totalPct ПЪЛНИЯТ процент за часа, не увеличението: 150 за извънреден в работен ден (сам
   *     часът плюс увеличението по чл. 262 ал. 1 т. 1), 200 за празничен по чл. 264
   */
  public static BigDecimal hourlyLineAmount(
      BigDecimal permanentPayBase,
      int workingDaysInPeriod,
      BigDecimal hoursPerDay,
      BigDecimal hours,
      BigDecimal totalPct) {
    return permanentPayBase
        .multiply(hours)
        .multiply(totalPct)
        .divide(
            BigDecimal.valueOf(workingDaysInPeriod).multiply(hoursPerDay).multiply(HUNDRED),
            SCALE,
            ROUNDING);
  }

  /**
   * СТАВКАТА ЗА ЕДИН НОЩЕН ЧАС — ПО-ВИСОКИЯТ от процента върху МРЗ и абсолютния под.
   *
   * <p><b>Дословно НСОРЗ чл. 8</b> (изм. ДВ бр. 115 от 2025 г., в сила от 01.01.2026 г.): „За всеки
   * отработен нощен час или за част от него между 22,00 ч. и 6,00 ч. … се заплаща допълнително
   * трудово възнаграждение за нощен труд в размер НЕ ПО-МАЛЪК ОТ 0,15 на сто от минималната работна
   * заплата, установена за страната, НО НЕ ПО-МАЛКО ОТ 0,51 евро.“
   *
   * <p>⚠️ ДВАТА МИНИМУМА РАБОТЯТ ЗАЕДНО, тоест това е {@code max}, а не избор. При МРЗ 620,20 €
   * процентът дава 0,9303 € и подът не хапе; при МРЗ под 340 € подът е по-високият и хапе. Написано
   * само като процент, нормата тихо би се нарушила точно в годината, в която МРЗ падне или подът се
   * вдигне — а точно такава промяна е влязла с ДВ бр. 115 от 2025 г.
   *
   * <p>⚠️ Резултатът НЕ се закръглява: {@code МРЗ × процент / 100} е точно при всяка стойност
   * (делението на 100 само мести запетаята), а закръглен на два знака, 0,9303 би станал 0,93 и 200
   * нощни часа биха загубили 60 стотинки.
   *
   * @param minWage {@code MIN_WAGE} за периода. Единица: ЕВРО на месец
   * @param pctOfMinWage {@code NIGHT_PCT_OF_MIN_WAGE} (0,15). Единица: ПРОЦЕНТ
   * @param floorPerHour {@code NIGHT_FLOOR_EUR} (0,51). Единица: ЕВРО на час
   */
  public static BigDecimal nightHourRate(
      BigDecimal minWage, BigDecimal pctOfMinWage, BigDecimal floorPerHour) {
    BigDecimal fromPct = minWage.multiply(pctOfMinWage).movePointLeft(2);
    return fromPct.compareTo(floorPerHour) < 0 ? floorPerHour : fromPct;
  }

  /**
   * ПЪЛНИЯТ ПРОЦЕНТ ЗА ЕДИН ИЗВЪНРЕДЕН ЧАС, или {@code null}, ако уговорката НАДХВЪРЛЯ минимума.
   *
   * <p><b>⚠️ УГОВОРЕНО НАД МИНИМУМА Е ОТКАЗ, А НЕ ТИХО ЗАНИЖАВАНЕ ДО МИНИМУМА.</b> КТ чл. 262 ал. 1
   * дава МИНИМУМИ („не по-малко от“), а колективният или индивидуалният договор може да е уговорил
   * повече. Сметнато по минимума, надуговореното е НЕДОПЛАТЕНО възнаграждение, което никой отчет не
   * показва — същият дефект, който {@link #effectiveSeniorityRatePct} предотвратява от другата
   * страна.
   *
   * <p><b>⚠️ И ЗАЩО НЕ СЕ ПЛАЩА ПО УГОВОРЕНОТО.</b> {@code ExtraHoursType.increase} е поле на
   * базовия модул с {@code max = 100} — тоест то не може да изрази нито трите надника по чл. 262
   * ал. 1 т. 3 върху вече удвоения размер, нито каквото и да е над сто на сто. Освен това
   * стойността е на ВИДА допълнителни часове, а не на договора на лицето: две лица с различни КТД,
   * попаднали в един вид, биха получили един и същ процент. Поле, което не може да носи отговора,
   * не бива да го дава — затова изходът е ОТКАЗ и перото се въвежда на ръка.
   *
   * <p>Уговорка ПОД минимума не е отказ, а се ВДИГА до него: минимумът е закон и по-ниската
   * уговорка е нищожна, не грешка на оператора.
   *
   * @param statutoryMinUpliftPct минимумът по съответната точка на чл. 262 ал. 1. Единица: ПРОЦЕНТ
   * @param agreedUpliftPct уговореното ({@code ExtraHoursType.increase}), или {@code null}
   * @return увеличението, което се прилага — САМО то, без самия час, — или {@code null} при
   *     надуговорка. Дали до него се добавя и самият час, решава перото: виж {@link #overtimeLine}
   */
  public static BigDecimal overtimeUpliftPct(
      BigDecimal statutoryMinUpliftPct, BigDecimal agreedUpliftPct) {
    if (agreedUpliftPct != null && agreedUpliftPct.compareTo(statutoryMinUpliftPct) > 0) {
      return null;
    }
    return statutoryMinUpliftPct;
  }

  /**
   * ПЪЛНИЯТ ПРОЦЕНТ ЗА ЕДИН ПРАЗНИЧЕН ЧАС — 200 при подневно отчитане, 100 при СИРВ.
   *
   * <p><b>⚠️ ЕДНО И СЪЩО ПРАВО, ДВЕ РАЗЛИЧНИ ЧИСЛА, И РАЗЛИКАТА НЕ Е В НОРМАТА, А В ТОВА КОЛКО ПЪТИ
   * ЧАСЪТ ВЕЧЕ Е ПЛАТЕН.</b> КТ чл. 264 иска ОБЩО удвоения размер и в двата случая. Различава се
   * какво вече е платено:
   *
   * <ul>
   *   <li><b>Подневно отчитане.</b> Официалният празник НЕ е работен ден по производствения
   *       календар, тоест не влиза в {@code workingDaysInPeriod} и основното възнаграждение НЕ
   *       плаща този час. Перото дава целите 200 на сто и с това чл. 264 е изпълнен.
   *   <li><b>СИРВ (чл. 142 ал. 2).</b> Нормата се установява за ОТЧЕТНИЯ ПЕРИОД в часове и
   *       празничният час вече е влязъл в нея, тоест основното възнаграждение вече го е платило
   *       ВЕДНЪЖ. Перото ДОПЪЛВА до удвоения размер със сто на сто. Дадени му 200, часът щеше да
   *       излезе 300 на сто — надплащане, което изглежда като спазена норма.
   * </ul>
   *
   * <p>⚠️ ДОПУСКАНЕТО Е НАЗОВАНО: при СИРВ празничният час се смята за ВЛЯЗЪЛ в нормата за периода.
   * Това е вярно за обичайния график, но НЕ е проверено срещу графика на лицето — модулът няма
   * график. Затова липсващият режим е ОТКАЗ, а не подразбиране: без него не се знае дали часът е
   * платен веднъж, или нула пъти, а разликата е точно сто на сто от него.
   *
   * @param workTimeMode {@code BgPayrollInput#workTimeMode}; {@code null} или празно е „не знам“
   * @return пълният процент, или {@code null}, ако режимът не е обявен
   */
  public static BigDecimal holidayWorkTotalPct(String workTimeMode) {
    if (workTimeMode == null || workTimeMode.trim().isEmpty()) {
      return null;
    }
    return WORK_TIME_MODE_SIRV.equals(workTimeMode.trim())
        ? HOLIDAY_WORK_TOTAL_PCT.subtract(THE_HOUR_ITSELF_PCT)
        : HOLIDAY_WORK_TOTAL_PCT;
  }

  // ═══ ЧАСОВИЯТ ЕНДЖИН — ПЕРАТА ═══════════════════════════════════════════

  /**
   * НАУЧНА СТЕПЕН (НСОРЗ чл. 11) — ФИКСИРАНА МЕСЕЧНА СУМА, БЕЗ ПРОРАТИРАНЕ.
   *
   * <p><b>⚠️ НЕ СЕ ПРОРАТИРА, И ТОВА Е ТЕКСТЪТ НА НОРМАТА.</b> Чл. 11 ал. 1 дава „допълнително
   * МЕСЕЧНО възнаграждение в размер не по-малък от“ — фиксирана сума на месец. Пропорцията
   * „отработено/норма“ идва от чл. 12 ал. 8 и е написана САМО за класа прослужено време; пренесена
   * тук по аналогия, тя би отнела пари по правило, което не е за това перо. Лице с нула отработени
   * дни през месеца получава ПЪЛНАТА сума.
   *
   * <p><b>⚠️ ЕДНА СУМА, НЕ СБОР.</b> Чл. 11 ал. 2: „при наличие на повече от едно от основанията по
   * ал. 1 лицето има право на допълнителното възнаграждение с ПО-БЛАГОПРИЯТНИЯ размер“. Полето е
   * едно именно затова — две отметки биха направили сбора изразим.
   *
   * @param degree кодът от {@code bg.academic.degree.select}, или {@code null} за „никой не е
   *     питан“
   * @param doctorEur {@code DEGREE_DOCTOR_EUR} за периода, или {@code null}
   * @param dscEur {@code DEGREE_DSC_EUR} за периода, или {@code null}
   */
  public static BgEarningsOutcome academicDegreeLine(
      String degree, BigDecimal doctorEur, BigDecimal dscEur) {
    return academicDegreeLine(degree, doctorEur, dscEur, null, null);
  }

  /**
   * Както горното, но с КАРВА по чл. 177 — виж {@link #monthlyFixedAfterArt177Leave}.
   *
   * <p><b>⚠️ ЕДИНСТВЕНОТО ПЛОСКО ПЕРО, КОЕТО ЕНДЖИНЪТ САМ КАРВИРА.</b> Другите две с постоянен
   * характер ({@code pl_postoyanen_bonus}, {@code pl_postoyanna_premia}) са {@code MANUAL_AMOUNT} —
   * операторът ги въвежда и генераторът НЕ пипа чужди редове. Техният дял за отпусковите дни се
   * приспада вътре в перото по чл. 177; виж {@code BgArt177Resolver#art177Amount}.
   *
   * @param art177LeaveDays дните платен отпуск по чл. 177, или {@code null}
   * @param workingDaysInPeriod нормата — ДЕЛИТЕЛЯТ на карва. Иска се само когато има какво да се
   *     карвира
   */
  public static BgEarningsOutcome academicDegreeLine(
      String degree,
      BigDecimal doctorEur,
      BigDecimal dscEur,
      BigDecimal art177LeaveDays,
      Integer workingDaysInPeriod) {
    return academicDegreeLine(
        degree, doctorEur, dscEur, art177LeaveDays, null, workingDaysInPeriod);
  }

  /**
   * Както горното, но и с КАРВА ЗА НЕПЛАТЕНИЯ ОТПУСК.
   *
   * <p><b>⚠️ РЕШЕНИЕ № 7 — НАИМЕНУВАН ШЕВ: ПАДА ЛИ ПЛОСКОТО ПЕРО ПРИ НЕПЛАТЕН ОТПУСК. ДА.</b> И
   * това е ОБРАТНОТО на правилото над него, затова се пише тук, а не се подразбира. Перото НЕ се
   * проратира от отсъствия — лице, което цял месец е било в болничен, получава пълните 25,56 €,
   * защото чл. 11 ал. 1 дава „допълнително МЕСЕЧНО възнаграждение“ и трудовото правоотношение тече.
   * Неплатеният отпуск обаче не е отсъствие от работа при течащо заплащане: през тези дни
   * работодателят не дължи НИЩО по КТ чл. 128 т. 2 („уговореното трудово възнаграждение ЗА
   * ИЗВЪРШЕНАТА РАБОТА“), а самият институт по чл. 160 е наречен „неплатен“. Тоест въпросът не е
   * „проратира ли се перото по отработеното“, а „има ли изобщо месечно възнаграждение за тези дни“
   * — и отговорът е не.
   *
   * <p><b>⚠️ И ЗАТОВА ТУК НЯМА ВРЪЩАНЕ, ЗА РАЗЛИКА ОТ ЧЛ. 177.</b> Карвът по чл. 177 се връща през
   * {@code pl_otpusk_177}; този не се връща никъде и не бива. Некарвирано, перото щеше да надплаща
   * {@code размер × дни / норма} — при 100,00 €, норма 22 и 5 дни неплатен това са 22,73 € с вид на
   * пресметнати.
   *
   * <p><b>⚠️ ДВЕТЕ ДРУГИ ПОСТОЯННИ ПЕРА ОСТАВАТ ОБЯВЕНА ПРАЗНИНА.</b> {@code pl_postoyanen_bonus} и
   * {@code pl_postoyanna_premia} са {@code MANUAL_AMOUNT}: операторът ги въвежда на фиша, а
   * генераторът не пипа чужди редове. При чл. 177 делът им за отпусковите дни се приспада ВЪТРЕ в
   * надбавката ({@code BgArt177Resolver#upliftAmount}); при неплатен отпуск такова перо НЯМА и няма
   * откъде да се приспадне. Намалени направо на реда, те щяха да се намалят ПАК при второто {@code
   * prepare()} — въведената сума не се пази никъде ({@code BgPayrollInput#manualLines} е {@code
   * DECLARED_ABSENT}), тоест идемпотентност не е постижима без нов източник. Дотогава те плащат
   * пълния си месечен размер и надплащат {@code сума × дни / норма}; числото е заковано от {@code
   * BgUnpaidLeaveMoneyTest#theManualPermanentLineIsAKnownGap}, за да не мине за верен изход.
   *
   * @param unpaidCarveDays дните, които Д1 вади от т. 16.1 (с кофа + извън осигуряването), или
   *     {@code null}
   */
  public static BgEarningsOutcome academicDegreeLine(
      String degree,
      BigDecimal doctorEur,
      BigDecimal dscEur,
      BigDecimal art177LeaveDays,
      BigDecimal unpaidCarveDays,
      Integer workingDaysInPeriod) {
    String code = degree == null ? "" : degree.trim();
    if (code.isEmpty()) {
      return BgEarningsOutcome.sourceMissing(
          LINE_ACADEMIC_DEGREE,
          "източникът липсва: научната степен не е попълнена на трудовия договор (поле „Научна"
              + " степен (НСОРЗ чл. 11)“). Празно НЕ се чете като „лицето няма степен“ — нула тук"
              + " би твърдяла точно това, а истината е, че никой не е питан. Попълни я, ако"
              + " степента е свързана с изпълняваната работа (чл. 11 ал. 1).");
    }
    BigDecimal amount;
    String parameter;
    if (DEGREE_DOCTOR.equals(code)) {
      amount = doctorEur;
      parameter = "DEGREE_DOCTOR_EUR";
    } else if (DEGREE_DOCTOR_OF_SCIENCES.equals(code)) {
      amount = dscEur;
      parameter = "DEGREE_DSC_EUR";
    } else {
      return BgEarningsOutcome.refused(
          LINE_ACADEMIC_DEGREE,
          "непозната научна степен „"
              + code
              + "“ по трудовия договор. Речникът bg.academic.degree.select носи точно двете степени"
              + " на НСОРЗ чл. 11 ал. 1 — DOCTOR и DOCTOR_OF_SCIENCES. Непозната стойност е или"
              + " сгрешен запис, или ново основание, което иска и свой размер: и в двата случая"
              + " сумата е решение на човек, а не подразбиране.");
    }
    if (amount == null || amount.signum() <= 0) {
      return BgEarningsOutcome.refused(
          LINE_ACADEMIC_DEGREE,
          "размерът за научна степен не е определен — параметърът "
              + parameter
              + " липсва за периода. Зареди го през App Install; БЕЗ"
              + " него перото НЕ се смята като нула, защото НСОРЗ чл. 11 ал. 1 дава минимални"
              + " размери (7,67 и 25,56 евро от 01.01.2026 г.), а не нула.");
    }
    // ⚠️ Количеството е ЕДИН МЕСЕЦ, не отработените дни: перото не се пропорционира и числото до
    // сумата трябва да казва точно това. Отработени дни тук биха обещали пропорция, каквато няма.
    // ⚠️ И сумата слиза до два знака: каталогът пази ставките на четири (0,1500), а перо на фиш е
    // пари — § 5.3. Без това 7,6700 би стигнало до колона със скала 2 и щеше да се закръгли там,
    // тоест закръглянето щеше да става на място, което никой свидетел не гледа.
    BigDecimal monthly = amount.setScale(SCALE, ROUNDING);
    BigDecimal carvedDays = carvedDays(art177LeaveDays, unpaidCarveDays);
    if (carvedDays.signum() > 0) {
      if (workingDaysInPeriod == null || workingDaysInPeriod <= 0) {
        return BgEarningsOutcome.refused(
            LINE_ACADEMIC_DEGREE,
            "работните дни в периода не са определени, а месецът носи отпуск, който сваля плоското"
                + " перо — платен по чл. 177 или неплатен, зачетен за осигурителен стаж. Перото се"
                + " карвира с „(норма − дни отпуск) / норма“ и БЕЗ нормата тази"
                + " дроб не съществува. Прегледай производствения календар за годината или впиши"
                + " числото на ръка на фиша. ⚠️ Некарвирано, перото щеше да плати отпусковите дни"
                + " и през себе си, и през чл. 177, а неплатените — без никакво основание.");
      }
      BigDecimal carved =
          monthlyFixedAfterLeaveCarve(
              monthly, art177LeaveDays, unpaidCarveDays, workingDaysInPeriod);
      // ⚠️ Количеството слиза от ЕДИН МЕСЕЦ на дела на месеца, който перото наистина покрива.
      // Оставено на 1, редът щеше да твърди пълен месец срещу непълна сума.
      return BgEarningsOutcome.computed(
          LINE_ACADEMIC_DEGREE,
          carved,
          BigDecimal.valueOf(workingDaysInPeriod)
              .subtract(carvedDays)
              .divide(BigDecimal.valueOf(workingDaysInPeriod), HOURLY_RATE_SCALE, ROUNDING),
          documentedRate(monthly));
    }
    return BgEarningsOutcome.computed(
        LINE_ACADEMIC_DEGREE, monthly, BigDecimal.ONE, documentedRate(monthly));
  }

  /**
   * НОЩЕН ТРУД (НСОРЗ чл. 8) — часове × по-високата от двете минимални ставки.
   *
   * <p>⚠️ Ставката НЕ зависи от заплатата на лицето: чл. 8 я връзва с МИНИМАЛНАТА РАБОТНА ЗАПЛАТА
   * ЗА СТРАНАТА, тоест тя е една и съща за всички. Оттам и подписът — тук не влиза нито основната
   * заплата, нито часовете на ден.
   *
   * <p>⚠️ Нощният труд и извънредният се ТРУПАТ, когато съвпадат: те са РАЗЛИЧНИ основания —
   * единият заплаща времето на денонощието, другият превишението на работното време. Затова
   * часовете тук не се вадят от извънредните никъде.
   */
  public static BgEarningsOutcome nightWorkLine(
      BigDecimal nightHours, BigDecimal minWage, BigDecimal pctOfMinWage, BigDecimal floorPerHour) {
    if (nightHours == null) {
      return BgEarningsOutcome.sourceMissing(
          LINE_NIGHT_WORK,
          "източникът липсва: за периода няма декларация за нощен труд (BgDeclaredWorkHours). Перо"
              + " със сума нула тук би твърдяло, че лицето НЕ е работило нощем; истината е, че"
              + " часовете не са отчитани. Създай декларация за договора и периода — самият ред Е"
              + " твърдението, включително когато числото в него е нула.");
    }
    if (minWage == null
        || minWage.signum() <= 0
        || pctOfMinWage == null
        || pctOfMinWage.signum() <= 0
        || floorPerHour == null
        || floorPerHour.signum() <= 0) {
      return BgEarningsOutcome.refused(
          LINE_NIGHT_WORK,
          "ставката за нощен труд не е определена — липсва някой от MIN_WAGE,"
              + " NIGHT_PCT_OF_MIN_WAGE или NIGHT_FLOOR_EUR за периода. Зареди ги през App Install"
              + ". БЕЗ трите ставката НЕ се смята: НСОРЗ чл. 8 иска"
              + " ПО-ВИСОКИЯТ от процента върху МРЗ и абсолютния под, тоест липсва ли един от"
              + " двата минимума, не се знае кой е в сила.");
    }
    BigDecimal rate = nightHourRate(minWage, pctOfMinWage, floorPerHour);
    return BgEarningsOutcome.computed(
        LINE_NIGHT_WORK,
        nightHours.multiply(rate).setScale(SCALE, ROUNDING),
        nightHours,
        documentedRate(rate));
  }

  /**
   * ВРЕМЕ НА РАЗПОЛОЖЕНИЕ (НСОРЗ чл. 10) — часове × фиксирана ставка.
   *
   * <p>⚠️ Времето на разположение НЕ Е работно време и не се брои за извънреден труд, „докато
   * лицето не бъде извикано на работа“. Извиканото време се отчита като работа и влиза по своя път;
   * тук влиза само чакането.
   */
  public static BgEarningsOutcome onCallLine(BigDecimal onCallHours, BigDecimal eurPerHour) {
    if (onCallHours == null) {
      return BgEarningsOutcome.sourceMissing(
          LINE_ON_CALL,
          "източникът липсва: за периода няма декларация за време на разположение"
              + " (BgDeclaredWorkHours). Нула тук би твърдяла, че лицето не е било на разположение;"
              + " истината е, че часовете не са отчитани.");
    }
    if (eurPerHour == null || eurPerHour.signum() <= 0) {
      return BgEarningsOutcome.refused(
          LINE_ON_CALL,
          "ставката за време на разположение не е определена — параметърът ON_CALL_EUR_PER_HOUR"
              + " липсва за периода. Зареди го през App Install; БЕЗ"
              + " него перото НЕ се смята като нула, защото НСОРЗ чл. 10 дава минимум 0,05 евро на"
              + " час, а не нула.");
    }
    return BgEarningsOutcome.computed(
        LINE_ON_CALL,
        onCallHours.multiply(eurPerHour).setScale(SCALE, ROUNDING),
        onCallHours,
        documentedRate(eurPerHour));
  }

  /**
   * ЕДНО ОТ ЧЕТИРИТЕ ПЕРА ЗА ИЗВЪНРЕДЕН ТРУД (КТ чл. 262 ал. 1).
   *
   * <p><b>⚠️ НЕНОРМИРАНИЯТ РАБОТЕН ДЕН СЕ ПИТА ПЪРВО, И РЕДЪТ НЕ Е СВОБОДЕН.</b> КТ чл. 263 ал. 1
   * отрича заплащането на извънредния труд в РАБОТНИ ДНИ при ненормиран работен ден. Питано след
   * липсите, лице с ненормиран ден и непопълнени часове на ден щеше да получи отказ „попълни
   * работното време“ вместо отговора „това перо не ти се дължи“ — и операторът щеше да поправи
   * вярното поле в грешна посока. Същият идиом като при {@link #seniorityLine}.
   *
   * <p>⚠️ Отричането е {@code NOT_ENTITLED}, а не пропускане: заварен ред от предишно {@code
   * prepare()} трябва да СЕ МАХНЕ. Прескочен, той щеше да преживее отмятането на флага.
   *
   * <p><b>⚠️ „САМИЯТ ЧАС“ Е ОТДЕЛЕН АРГУМЕНТ, А НЕ ЗАКОВАНИ СТО НА СТО.</b> Увеличенията по чл. 262
   * ал. 1 са УВЕЛИЧЕНИЯ — те се добавят към заплащането ЗА ЧАСА. При три от четирите пера този час
   * не е платен от никого другиго (той е извън нормата) и влиза тук. При извънредния труд НА
   * ПРАЗНИК обаче часът вече е платен, и то двойно, от перото по чл. 264 — затова там аргументът е
   * НУЛА и перото носи само добавъчния надник. Зададен закован, същият час щеше да се плати четири
   * пъти вместо три.
   *
   * @param lineCode кодът на перото — решава и коя точка на чл. 262 ал. 1 се прилага
   * @param hours часовете от този вид, или {@code null} за „не са отчитани“
   * @param hourComponentPct самият час: {@link #THE_HOUR_ITSELF_PCT}, когато никой друг не го
   *     плаща, и НУЛА, когато перото по чл. 264 вече го е платило
   * @param statutoryMinUpliftPct минимумът по съответната точка
   * @param agreedUpliftPct уговореното ({@code ExtraHoursType.increase}), или {@code null}
   * @param suppressedByUnregulatedDay отрича ли чл. 263 това перо за ТОВА лице
   */
  public static BgEarningsOutcome overtimeLine(
      String lineCode,
      BigDecimal hours,
      BigDecimal hourComponentPct,
      BigDecimal statutoryMinUpliftPct,
      BigDecimal agreedUpliftPct,
      boolean suppressedByUnregulatedDay,
      BgEarningsFacts facts) {
    if (suppressedByUnregulatedDay) {
      return BgEarningsOutcome.notEntitled(
          lineCode,
          "извънредният труд в РАБОТНИ ДНИ не се заплаща при ненормиран работен ден. КТ чл. 263 ал."
              + " 1 дословно: „За извънреден труд, положен в работни дни от работници и служители с"
              + " ненормиран работен ден, не се заплаща трудово възнаграждение.“ Ал. 2 ЗАПАЗВА"
              + " заплащането за дните на седмичната почивка и на официалните празници, тоест"
              + " перата за почивен ден и за празник остават. Признакът е на договора —"
              + " bgIsUnregulatedWorkDay — и се сменя там, не тук.");
    }
    if (hours == null) {
      return BgEarningsOutcome.sourceMissing(
          lineCode,
          "източникът липсва: за периода няма ВАЛИДИРАНИ допълнителни часове от този вид, или видът"
              + " им не е класифициран (ExtraHoursType.bgExtraHoursKind). Перо със сума нула тук би"
              + " твърдяло, че такъв извънреден труд НЯМА; истината е, че не е отчетен. Валидирай"
              + " отчета за допълнителни часове и попълни класификацията на вида.");
    }
    String missing = missingHourlyInput(facts);
    if (missing != null) {
      return BgEarningsOutcome.refused(lineCode, missing);
    }
    BigDecimal uplift = overtimeUpliftPct(statutoryMinUpliftPct, agreedUpliftPct);
    if (uplift == null) {
      return BgEarningsOutcome.refused(
          lineCode,
          "уговореното увеличение ("
              + agreedUpliftPct.stripTrailingZeros().toPlainString()
              + " на сто) НАДХВЪРЛЯ законовия минимум ("
              + statutoryMinUpliftPct.stripTrailingZeros().toPlainString()
              + " на сто по КТ чл. 262 ал. 1), а енджинът не може да плати по него: полето"
              + " ExtraHoursType.increase е на ВИДА допълнителни часове, не на договора на лицето,"
              + " и има таван 100. Сметнато по минимума, надуговореното би било НЕДОПЛАТЕНО"
              + " възнаграждение, което никой отчет не показва. Въведи перото на ръка с уговорената"
              + " сума.");
    }
    return computedHourlyLine(lineCode, facts, hours, hourComponentPct.add(uplift));
  }

  /**
   * РАБОТА НА ОФИЦИАЛЕН ПРАЗНИК (КТ чл. 264) — удвоеният размер за ВСИЧКИ празнични часове.
   *
   * <p><b>⚠️ ЗА ВСИЧКИ, НЕЗАВИСИМО ДАЛИ СА ИЗВЪНРЕДНИ.</b> Чл. 264 дословно: „За работа през дните
   * на официалните празници, НЕЗАВИСИМО дали представлява извънреден труд или не … не по-малко от
   * удвоения размер“. Разделянето на нормален и извънреден празничен труд ТУК би оставило
   * половината часове на единична ставка.
   *
   * <p><b>⚠️ ПРАВИЛОТО ТРИ НАДНИКА.</b> Празник с нормален труд дава ДВА надника — само по чл. 264.
   * Празник с ИЗВЪНРЕДЕН труд дава ТРИ: тези два плюс още един по чл. 262 ал. 1 т. 3, който се
   * смята върху възнаграждението по трудовия договор (ал. 2), а НЕ върху вече удвоения размер.
   * Затова перото {@code pl_izv_praznik} носи само увеличението — самият час е платен тук. ВАС
   * решения № 3346/2017 и № 10983/2020.
   *
   * <p><b>⚠️ ПАЗАЧЪТ: ИЗВЪНРЕДНИТЕ ПРАЗНИЧНИ ЧАСОВЕ СА ПОДМНОЖЕСТВО.</b> Двата броя идват от
   * РАЗЛИЧНИ таблици — отчетът за време и допълнителните часове — и нищо в модела не ги свързва.
   * Повече извънредни празнични, отколкото празнични изобщо, значи, че двата източника си
   * противоречат: или календарът не е разпознал празник, или допълнителните часове са класифицирани
   * грешно. Сметнати въпреки това, трите надника биха се дали на часове, за които вторият надник не
   * е платен.
   */
  public static BgEarningsOutcome holidayWorkLine(BgEarningsFacts facts) {
    // ⚠️ НЕДОПУСТИМИЯТ РЕЖИМ СЕ ПИТА ПЪРВО, И РЕДЪТ НЕ Е СВОБОДЕН — същият идиом като при
    // overtimeLine. Двата клона по-долу се различават с точно СТО НА СТО от празничния час, а при
    // ненормиран работен ден режимът „СИРВ“ е забранен от КТ чл. 142 ал. 5, тоест не е известно
    // кой от двата е верният. Питано СЛЕД липсите, лице с недопустим договор щеше да получи
    // отказ „зареди производствения календар“ — и операторът щеше да поправя поле, което е наред.
    String forbidden = forbiddenWorkTimeModeRefusal(facts);
    if (forbidden != null) {
      return BgEarningsOutcome.refused(LINE_HOLIDAY_WORK, forbidden);
    }
    if (facts.holidayWorkHours == null) {
      return BgEarningsOutcome.sourceMissing(
          LINE_HOLIDAY_WORK,
          "източникът липсва: няма валидиран отчет за време за периода, ИЛИ производственият"
              + " календар не покрива годината. Второто е капанът — непокрита година няма нито един"
              + " официален празник в таблицата, тоест сборът би излязъл нула „не е работено на"
              + " празник“ при пълен отчет зад себе си, а увеличението по КТ чл. 264 просто нямаше"
              + " да се начисли. Зареди производствения календар за годината.");
    }
    String contradiction = holidayHoursContradiction(facts);
    if (contradiction != null) {
      return BgEarningsOutcome.refused(LINE_HOLIDAY_WORK, contradiction);
    }
    String missing = missingHourlyInput(facts);
    if (missing != null) {
      return BgEarningsOutcome.refused(LINE_HOLIDAY_WORK, missing);
    }
    BigDecimal totalPct = holidayWorkTotalPct(facts.workTimeMode);
    if (totalPct == null) {
      return BgEarningsOutcome.refused(
          LINE_HOLIDAY_WORK,
          "режимът на отчитане на работното време не е попълнен на договора (поле „Режим на"
              + " отчитане на работното време“), а от него зависи КОЛКО се дължи. При подневно"
              + " отчитане празникът не е работен ден, основното възнаграждение не го плаща и"
              + " перото дава целите 200 на сто по КТ чл. 264. При СИРВ часът вече е влязъл в"
              + " нормата за отчетния период и е платен веднъж, тоест перото ДОПЪЛВА със 100 на"
              + " сто. Прието наслуки, едното от двете надплаща с цял надник.");
    }
    return computedHourlyLine(LINE_HOLIDAY_WORK, facts, facts.holidayWorkHours, totalPct);
  }

  /**
   * ПРОТИВОРЕЧИЕТО МЕЖДУ ДВАТА ИЗТОЧНИКА НА ПРАЗНИЧНИ ЧАСОВЕ, или {@code null}, ако няма.
   *
   * <p>Проверява се само когато и ДВЕТЕ числа са известни: непрочетеният отчет за време не е
   * противоречие, а липса, и си има собствен изход.
   */
  static String holidayHoursContradiction(BgEarningsFacts facts) {
    if (facts.holidayWorkHours == null || facts.overtimeHolidayHours == null) {
      return null;
    }
    if (facts.overtimeHolidayHours.compareTo(facts.holidayWorkHours) <= 0) {
      return null;
    }
    return "двата източника за празнични часове си противоречат: извънредният труд на официален"
        + " празник е "
        + facts.overtimeHolidayHours.stripTrailingZeros().toPlainString()
        + " ч., а ВСИЧКИ отчетени празнични часове са "
        + facts.holidayWorkHours.stripTrailingZeros().toPlainString()
        + " ч. Извънредните празнични часове са ПОДМНОЖЕСТВО на празничните — повече от тях не"
        + " съществува. Или производственият календар не е разпознал ден като официален празник,"
        + " или редовете с допълнителни часове са класифицирани като OVERTIME_HOLIDAY погрешка."
        + " Сметнато въпреки това, правилото „три надника“ би дало третия надник на часове, за"
        + " които вторият не е платен.";
  }

  /**
   * НЕДОПУСТИМИЯТ РЕЖИМ НА ОТЧИТАНЕ, или {@code null}, когато двойката е допустима.
   *
   * <p><b>Правилото НЕ е тук — то е {@link BgWorkTimeModeRule}</b>, същото, което отказва записа на
   * договора. Тук е само прочитът на двете полета от фактите и адресът, на който се поправя. Второ
   * копие на нормата би се разминало при първата поправка, и то тихо.
   *
   * <p>⚠️ ЗАЩИТЕН ПАЗАЧ, А НЕ ОСНОВНАТА ПРЕГРАДА. Основната е на записа на договора — недопустимата
   * двойка не бива да съществува изобщо. Този тук хваща заварените: ред, записан преди пазача, ред
   * от seed или от миграция, ред, пристигнал по път, който пазачът не покрива. Без него фишът
   * просто ПЛАЩА по недопустим режим, а нищо в изхода не сочи договора.
   */
  private static String forbiddenWorkTimeModeRefusal(BgEarningsFacts facts) {
    return BgWorkTimeModeRule.forbiddenPairRefusal(facts.unregulatedWorkDay, facts.workTimeMode);
  }

  /**
   * СТАВКАТА, КАКТО СЕ ПОКАЗВА — без опашка от нули, но никога под два знака.
   *
   * <p>⚠️ Само за {@code BgPaySlipLine.bgRate}. Точната стойност участва в сметката преди това:
   * 0,15 на сто от 620,20 е точно 0,9303000000 по пътя на {@code movePointLeft}, а показана така,
   * тя изглежда като измерена до десети от милиардната. ⚠️ И долната граница НЕ е излишна: {@code
   * stripTrailingZeros} превръща 100 в {@code 1E+2}, тоест ставка, случайно кръгла, би се показала
   * в експоненциален запис.
   */
  static BigDecimal documentedRate(BigDecimal rate) {
    BigDecimal stripped = rate.stripTrailingZeros();
    return stripped.scale() < SCALE ? stripped.setScale(SCALE, ROUNDING) : stripped;
  }

  /** Сметнатото часово перо, с ЧАСОВАТА СТАВКА като документация до сумата. */
  private static BgEarningsOutcome computedHourlyLine(
      String lineCode, BgEarningsFacts facts, BigDecimal hours, BigDecimal totalPct) {
    BigDecimal base =
        permanentPayBase(
            facts.baseSalary,
            facts.entitledToSeniorityBonus,
            facts.seniorityRatePct,
            facts.completedServiceYears,
            degreeAmountOf(facts),
            facts.permanentAllowances);
    return BgEarningsOutcome.computed(
        lineCode,
        hourlyLineAmount(
            base, facts.workingDaysInPeriod, facts.contractHoursPerDay, hours, totalPct),
        hours,
        hourlyRate(base, facts.workingDaysInPeriod, facts.contractHoursPerDay));
  }

  /**
   * СУМАТА ЗА НАУЧНА СТЕПЕН КАТО СЪСТАВКА НА БАЗАТА, или {@code null}.
   *
   * <p>⚠️ Мълчалив {@code null} при липсващ параметър, а НЕ отказ, и това е нарочно: перото 120 си
   * има собствен изход, който обявява липсата гръмко. Повторено тук, същата липса щеше да събори и
   * останалите седем часови пера — тоест непопълненият размер за степен щеше да отнеме нощния труд
   * на човек без никаква степен.
   */
  private static BigDecimal degreeAmountOf(BgEarningsFacts facts) {
    BgEarningsOutcome degree =
        academicDegreeLine(facts.academicDegree, facts.degreeDoctorEur, facts.degreeDscEur);
    return degree.isComputed() ? degree.amount : null;
  }

  /**
   * ЛИПСИТЕ, ОБЩИ ЗА ЧАСОВИТЕ ПЕРА: без тях часовата ставка по НСОРЗ чл. 7 не е определена.
   *
   * <p>⚠️ Липсващият процент за клас е ОТКАЗ и тук, макар перото на класа да е друго: класът влиза
   * в базата по чл. 7, тоест без него ставката излиза ЗАНИЖЕНА, а не „същата без една добавка“. Не
   * се иска само от онзи, на когото класът изобщо не се дължи ({@code entitledToSeniorityBonus} =
   * {@code FALSE}) — там нулата е ОТГОВОР, а не липса.
   */
  private static String missingHourlyInput(BgEarningsFacts facts) {
    String common =
        missingCommonInput(facts.baseSalary, facts.workedDays, facts.workingDaysInPeriod);
    if (common != null) {
      return common;
    }
    if (facts.contractHoursPerDay == null || facts.contractHoursPerDay.signum() <= 0) {
      return "договорените часове на ден не са попълнени на трудовия договор (поле „Договорени часове"
          + " на ден“). БЕЗ тях часовата ставка не съществува: тя дели месечната база на месечната"
          + " продължителност на работното време, а тя е ДОГОВОРЕНАТА. Законовите осем по"
          + " подразбиране биха били верни за пълния щат и биха занижили НАПОЛОВИНА всяка добавка на"
          + " лицето на половин щат по КТ чл. 138 — правдоподобно и грешно.";
    }
    if (!Boolean.FALSE.equals(facts.entitledToSeniorityBonus)
        && (facts.seniorityRatePct == null || facts.seniorityRatePct.signum() <= 0)) {
      return "процентът за клас прослужено време не е определен — параметърът SENIORITY_MIN_PCT липсва"
          + " за периода, а класът е СЪСТАВКА на базата по НСОРЗ чл. 7 („основната работна заплата и"
          + " допълнителните трудови възнаграждения с постоянен характер“). Без него часовата ставка"
          + " излиза ЗАНИЖЕНА, а не просто „без една добавка“. Зареди го през App Install"
          + ".";
    }
    if (!Boolean.FALSE.equals(facts.entitledToSeniorityBonus)
        && facts.completedServiceYears == null) {
      return "признатият стаж няма начална дата, а класът прослужено време е съставка на базата по"
          + " НСОРЗ чл. 7 — попълни началото на договора и признатия предходен стаж"
          + " (bgPriorExperienceYears/Months/Days). БЕЗ котва навършените години са НЕИЗВЕСТНИ, а не"
          + " нула, и часовата ставка би излязла занижена.";
    }
    return null;
  }

  // ═══ ИЗЧЕРПАТЕЛНИЯТ ДИСЧПАЧ ═════════════════════════════════════════════

  /**
   * ОТКАЗЪТ НА ИЗТОЧНИКА НА ОТСЪСТВИЯТА — {@code null}, когато няма какво да се откаже.
   *
   * <p><b>⚠️ ТИХИЯТ ОТКАЗ Е ПО-ОПАСЕН ОТ ГРЕШНОТО ЧИСЛО, И ТОЧНО ТУК СЕ ВИЖДА ЗАЩО.</b> Когато
   * {@code BgPayrollInputBuilder#leaveDaysOf} откаже (несъпоставен вид отпуск, заявка в часове без
   * делител, заявка извън работните дни), той обезсилва ЦЯЛАТА тройка кофи — и трите пристигат
   * {@code null}. Прочетено като „нула дни отпуск“, това би дало ПЪЛЕН МЕСЕЦ заплата на лице, което
   * може да е било в неплатен отпуск целия месец: най-скъпата възможна грешка, при това с вид на
   * най-обикновен фиш. Затова липсата на дните е ОТКАЗ на трите карвирани пера, а не подразбираща
   * се нула.
   *
   * <p><b>⚠️ И ЗАТОВА ПРИЧИНАТА СЕ ПРЕНАСЯ ДОСЛОВНО, А НЕ СЕ ПРЕРАЗКАЗВА.</b> Текстовете в {@code
   * BgPayrollInputBuilder#REFUSALS} казват КОЕ ПОЛЕ да се попълни; преразказани тук, те щяха да са
   * второ описание на същия проблем, свободно да се размине с първото. Отказът излиза на екрана
   * през {@code BgPaySlipVerdicts#asGaps} и стига до {@code BgPaySlip.bgGeneratorGaps} — тоест до
   * човека, който може да го поправи.
   *
   * <p>⚠️ Различава се от „няма нито една заявка за отпуск“: тогава {@code leaveDaysRefusal} е
   * {@code null}, дните са {@code null} и перата се смятат както винаги. Инсталация без отпуски в
   * модула не бива да остане без заплата.
   */
  static BgEarningsOutcome carveSourceRefusal(String typeCode, BgEarningsFacts facts) {
    if (facts == null || facts.leaveDaysRefusal == null || !CARVED_LINES.contains(typeCode)) {
      return null;
    }
    return BgEarningsOutcome.refused(
        typeCode,
        "дните отсъствие за месеца НЕ СА ПРЕБРОЕНИ, а сумата на това перо виси от тях. "
            + facts.leaveDaysRefusal
            + " ⚠️ БЕЗ дните перото НЕ се смята: прието за „нула дни отпуск“, то би платило ПЪЛЕН"
            + " месец на лице, което може да е било в неплатен отпуск — и фишът щеше да изглежда"
            + " напълно обикновен. Поправката е в заявките за отпуск и в речника на отсъствията, не"
            + " на фиша.");
  }

  /**
   * КАКВО ПРАВИ ЕНДЖИНЪТ С ЕДИН ТИП ПЕРО — единствената врата навътре.
   *
   * <p><b>Изчерпателен по шестте стойности, и това е твърдение, не стил.</b> Всяка от тях получава
   * ЯВЕН изход; нито една не отпада тихо. Разликата личи в деня, в който някой добави тип перо с
   * нова база: мълчаливият дисчпач ще произведе фиш без това перо и никой няма да разбере, а този
   * ще откаже с името на непознатата стойност.
   *
   * <ul>
   *   <li>{@link #CALC_CONTRACT_BASE_SALARY} — ДВЕ подформули, различени по КОДА на типа: {@code
   *       pl_osnovna} и {@code pl_tspo}. Трети код с тази база е ОТКАЗ, а не мълчание: базата казва
   *       „процент върху основната“, но не казва КОЙ процент и за какво.
   *   <li>{@link #CALC_MONTHLY_FIXED} — ЕДНА подформула: {@code pl_nauchna_stepen} (НСОРЗ чл. 11).
   *       Втори код с тази база е ОТКАЗ: базата казва „фиксирана сума на месец“, но не казва КОЯ.
   *   <li>{@link #CALC_HOURLY_FIXED} — ДВЕ подформули със ставка, НЕЗАВИСЕЩА от заплатата: {@code
   *       pl_nosten_trud} (НСОРЗ чл. 8, процент от МРЗ с абсолютен под) и {@code pl_razpolozhenie}
   *       (чл. 10, фиксирани евро на час).
   *   <li>{@link #CALC_HOURLY_RATE_UPLIFT} — ПЕТ подформули върху ЧАСОВАТА СТАВКА по НСОРЗ чл. 7:
   *       четирите точки на КТ чл. 262 ал. 1 плюс работата на официален празник по чл. 264.
   *   <li>{@link #CALC_MANUAL_AMOUNT} и {@link #CALC_DERIVED} — НЕ СА работа на този енджин:
   *       първото се въвежда от оператора, второто идва от осигуровките, обезщетенията и
   *       ретро-делтата.
   *   <li>всичко останало, включително празно — ОТКАЗ с името на стойността.
   * </ul>
   *
   * <p><b>⚠️ ЧАСОВЕТЕ НЕ СА {@code DECLARED_ABSENT}.</b> Трите бази отгоре не връщат „източникът
   * липсва“ по § 13.1 за целия модул: входът носи часовете — {@code BgPayrollInputBuilder} пълни
   * всичките осем от реални източници — отчета за време, {@code BgDeclaredWorkHours} и
   * класифицираните {@code ExtraHoursLine}. Нито един запис в {@code DECLARED_ABSENT} не е час.
   *
   * <p>⚠️ „ИЗТОЧНИКЪТ ЛИПСВА“ НЕ Е НУЛА И НЕ Е МЪЛЧАНИЕ — и се произнася за КОНКРЕТНИЯ фиш, а не за
   * целия модул. Перо със сума 0,00 за нощен труд твърди, че лицето не е работило нощем; истината
   * при липсваща декларация е, че никой не е питан. Затова липсващият час дава {@code
   * SOURCE_MISSING} без ред, а ДЕКЛАРИРАНАТА нула дава ред със сума 0,00 — двете са различни
   * отговори и се различават НА ФИША, не само в доклада.
   */
  public static BgEarningsOutcome resolve(
      String calculationBase, String typeCode, BgEarningsFacts facts) {
    String base = calculationBase == null ? "" : calculationBase.trim();

    // ⚠️ ПЪРВО ОТКАЗЪТ НА ИЗТОЧНИКА НА ОТСЪСТВИЯТА, преди всяка формула — виж
    // carveSourceRefusal. Питан по-късно, той щеше да мине СЛЕД сметката, тоест перото щеше да е
    // сметнато за пълен месец и отказът щеше да е бележка до вече платена сума.
    BgEarningsOutcome leaveSourceRefused = carveSourceRefusal(typeCode, facts);
    if (leaveSourceRefused != null) {
      return leaveSourceRefused;
    }

    if (CALC_CONTRACT_BASE_SALARY.equals(base)) {
      if (LINE_BASE_SALARY.equals(typeCode)) {
        return baseSalaryLine(
            facts.baseSalary,
            facts.workedDays,
            facts.workingDaysInPeriod,
            facts.art177LeaveDays,
            facts.unpaidCarveDays);
      }
      if (LINE_SENIORITY.equals(typeCode)) {
        return seniorityLine(
            facts.entitledToSeniorityBonus,
            facts.baseSalary,
            facts.seniorityRatePct,
            facts.completedServiceYears,
            facts.workedDays,
            facts.workingDaysInPeriod,
            facts.art177LeaveDays,
            facts.unpaidCarveDays);
      }
      return BgEarningsOutcome.refused(
          typeCode,
          "типът перо „"
              + typeCode
              + "“ е обявен с база „процент върху основната заплата“, но енджинът няма подформула за"
              + " този код. Базата казва върху КАКВО се смята, не КОЛКО: процентът и поводът са на"
              + " самото перо. Добави подформулата в BgEarningsResolver.resolve или смени базата на"
              + " типа в bg_pay_slip_line_type.csv.");
    }

    if (CALC_MONTHLY_FIXED.equals(base)) {
      if (LINE_ACADEMIC_DEGREE.equals(typeCode)) {
        return academicDegreeLine(
            facts.academicDegree,
            facts.degreeDoctorEur,
            facts.degreeDscEur,
            facts.art177LeaveDays,
            facts.unpaidCarveDays,
            facts.workingDaysInPeriod);
      }
      return BgEarningsOutcome.refused(
          typeCode,
          "типът перо „"
              + typeCode
              + "“ е обявен с база „фиксирана сума на месец“, но енджинът няма подформула за този"
              + " код. Базата казва КАК се смята, не КОЛКО: размерът и поводът са на самото перо."
              + " Добави подформулата в BgEarningsResolver.resolve или смени базата на типа в"
              + " bg_pay_slip_line_type.csv.");
    }

    if (CALC_HOURLY_FIXED.equals(base)) {
      if (LINE_NIGHT_WORK.equals(typeCode)) {
        return nightWorkLine(
            facts.nightHours, facts.minWage, facts.nightPctOfMinWage, facts.nightFloorEur);
      }
      if (LINE_ON_CALL.equals(typeCode)) {
        return onCallLine(facts.onCallHours, facts.onCallEurPerHour);
      }
      return BgEarningsOutcome.refused(
          typeCode,
          "типът перо „"
              + typeCode
              + "“ е обявен с база „фиксирана сума на час“, но енджинът няма подформула за този код."
              + " Двете познати са нощният труд (НСОРЗ чл. 8) и времето на разположение (чл. 10) —"
              + " и двете със ставка, НЕЗАВИСЕЩА от заплатата на лицето. Трети такъв повод иска своя"
              + " ставка в bg_payroll_parameter и своя подформула в BgEarningsResolver.resolve, или"
              + " смени базата на типа в bg_pay_slip_line_type.csv.");
    }

    if (CALC_HOURLY_RATE_UPLIFT.equals(base)) {
      // ⚠️ ЧЕТИРИТЕ ТОЧКИ НА ЧЛ. 262 АЛ. 1 СА ЧЕТИРИ РЕДА, А НЕ ЕДИН С ТАБЛИЦА. Всяка носи СВОЙ
      // минимум, свой източник на часове и свой отговор на въпроса „плаща ли някой друг самия
      // час“ — а точно последното различава празничния извънреден труд от останалите три.
      if (LINE_OVERTIME_WORKDAY.equals(typeCode)) {
        // ⚠️ ПАЗАЧЪТ Е ПРЕДИ ОТРИЧАНЕТО ПО ЧЛ. 263 АЛ. 1, И РЕДЪТ ТУК РЕШАВА ДАЛИ РЕД СЕ ТРИЕ.
        // Отричането по-долу е NOT_ENTITLED, а BgEarningsService МАХА заварения ред при този
        // изход. Тоест питан втори, пазачът щеше да ТРИЕ мълчаливо перо за 164,96 € точно върху
        // конфигурацията, заради която съществува — а предпоставката на чл. 263 ал. 1 („лицето е
        // с ненормиран работен ден“) е именно СПОРНАТА: сгрешена ли е отметката, а лицето е чист
        // СИРВ, часовете са законно изработени и платими.
        String forbidden = forbiddenWorkTimeModeRefusal(facts);
        if (forbidden != null) {
          return BgEarningsOutcome.refused(LINE_OVERTIME_WORKDAY, forbidden);
        }
        return overtimeLine(
            LINE_OVERTIME_WORKDAY,
            facts.overtimeWorkdayHours,
            THE_HOUR_ITSELF_PCT,
            OVERTIME_WORKDAY_MIN_UPLIFT_PCT,
            facts.agreedOvertimeWorkdayPct,
            // ⚠️ ЕДИНСТВЕНОТО ПЕРО, КОЕТО ЧЛ. 263 АЛ. 1 ОТРИЧА при ДОПУСТИМА конфигурация. Ал. 2
            // изрично запазва почивния ден и празника, тоест трите му съседа НЕ се гейтват от
            // флага.
            facts.unregulatedWorkDay,
            facts);
      }
      if (LINE_OVERTIME_REST_DAY.equals(typeCode)) {
        // ⚠️ ЕДИНСТВЕНОТО ПЕРО ОТ ЧЕТИРИТЕ, КОЕТО НЕ СЕ ГЕЙТВА ОТ ЧЛ. 142 АЛ. 5 — и това е
        // РЕШЕНИЕ, не пропуск. Гейтва се перо, чиято СТОЙНОСТ зависи от спорния флаг; това не
        // зависи. Сметката е 100 + 75 на сто и не чете нито workTimeMode, нито unregulatedWorkDay
        // (виж computedHourlyLine), а чл. 263 ал. 2 дава на ненормирания точно размера по чл. 262
        // ал. 1 т. 2 — същите 75 на сто, както на всеки друг. Тоест при двата режима числото е
        // ЕДНО И СЪЩО и отказът тук не би пазил от нищо: той би спрял еднозначно платен труд, за
        // да съобщи дефект, който другите четири пера вече съобщават.
        return overtimeLine(
            LINE_OVERTIME_REST_DAY,
            facts.overtimeRestDayHours,
            THE_HOUR_ITSELF_PCT,
            OVERTIME_REST_DAY_MIN_UPLIFT_PCT,
            facts.agreedOvertimeRestDayPct,
            false,
            facts);
      }
      if (LINE_OVERTIME_HOLIDAY.equals(typeCode)) {
        // ⚠️ И ДВАТА ПАЗАЧА НА ЧЛ. 264 СА ТУК, ПО ЕДИН И СЪЩИ ДОВОД: празничният труд е ЕДНО
        // плащане, разцепено на два реда, и всяко обезсилване на едното обезсилва и другото.
        // Перото носи САМО третия надник, защото ZERO по-долу приема, че самият час вече е платен
        // от pl_praznik. Откаже ли pl_praznik — а при недопустимата двойка той отказва, защото
        // 200 на сто общо и 100 на сто допълване се различават по РЕЖИМА — тази предпоставка си
        // отива. Платено самò, перото дава третия надник върху часове, чийто ВТОРИ не е платен.
        // Тоест стойността му наистина зависи от спорния флаг, макар сметката му да не го чете.
        String forbidden = forbiddenWorkTimeModeRefusal(facts);
        if (forbidden != null) {
          return BgEarningsOutcome.refused(LINE_OVERTIME_HOLIDAY, forbidden);
        }
        String contradiction = holidayHoursContradiction(facts);
        if (contradiction != null) {
          return BgEarningsOutcome.refused(LINE_OVERTIME_HOLIDAY, contradiction);
        }
        return overtimeLine(
            LINE_OVERTIME_HOLIDAY,
            facts.overtimeHolidayHours,
            // ⚠️ НУЛА, НЕ СТО: самият празничен час вече е платен от pl_praznik по чл. 264, и то
            // ДВОЙНО. Тук влиза само третият надник по чл. 262 ал. 1 т. 3, сметнат върху
            // възнаграждението по трудовия договор (ал. 2), а не върху вече удвоения размер.
            BigDecimal.ZERO,
            OVERTIME_HOLIDAY_MIN_UPLIFT_PCT,
            facts.agreedOvertimeHolidayPct,
            false,
            facts);
      }
      if (LINE_OVERTIME_SIRV.equals(typeCode)) {
        // ⚠️ НЕДОПУСТИМИЯТ РЕЖИМ СЕ ПИТА ПРЕДИ ФОРМУЛАТА, И ОТГОВОРЪТ Е ОТКАЗ, НЕ СУМА. Перото
        // съществува само при СИРВ, а СИРВ при ненормиран работен ден е забранен от КТ чл. 142
        // ал. 5 — тоест носи ли договорът и двете, сгрешен е ДОГОВОРЪТ, не фишът.
        String forbidden = forbiddenWorkTimeModeRefusal(facts);
        if (forbidden != null) {
          return BgEarningsOutcome.refused(LINE_OVERTIME_SIRV, forbidden);
        }
        return overtimeLine(
            LINE_OVERTIME_SIRV,
            facts.overtimeSirvHours,
            THE_HOUR_ITSELF_PCT,
            OVERTIME_SIRV_MIN_UPLIFT_PCT,
            facts.agreedOvertimeSirvPct,
            false,
            facts);
      }
      if (LINE_HOLIDAY_WORK.equals(typeCode)) {
        return holidayWorkLine(facts);
      }
      return BgEarningsOutcome.refused(
          typeCode,
          "типът перо „"
              + typeCode
              + "“ е обявен с база „процентно увеличение върху часовата ставка“, но енджинът няма"
              + " подформула за този код. Базата казва ВЪРХУ КАКВО се смята, не КОЛКО: процентът и"
              + " поводът са на самото перо, а КТ дава минимуми по точки. Добави подформулата в"
              + " BgEarningsResolver.resolve или смени базата на типа в bg_pay_slip_line_type.csv.");
    }

    if (CALC_MANUAL_AMOUNT.equals(base)) {
      return BgEarningsOutcome.notThisEngine(
          typeCode, "въвежда се на ръка от оператора — енджинът за начисления не го докосва.");
    }

    if (CALC_DERIVED.equals(base)) {
      return BgEarningsOutcome.notThisEngine(
          typeCode,
          "производно перо — сумата му идва от друга услуга (осигуровки, данък, обезщетения,"
              + " ретро-делта), не от начисленията.");
    }

    return BgEarningsOutcome.refused(
        typeCode,
        "непознат начин на изчисление „"
            + base
            + "“ за тип перо „"
            + typeCode
            + "“. Дисчпачът е ИЗЧЕРПАТЕЛЕН по шестте стойности на"
            + " bg.payslip.calculation.base.select и не прескача тихо непозната: непрескочената"
            + " стойност е фиш без перо, за което никой не е разбрал. Или добави стойността в"
            + " BgEarningsResolver.resolve, или поправи bg_pay_slip_line_type.csv.");
  }
}
