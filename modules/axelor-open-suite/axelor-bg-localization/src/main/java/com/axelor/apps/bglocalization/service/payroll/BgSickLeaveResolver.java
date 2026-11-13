package com.axelor.apps.bglocalization.service.payroll;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * ЧИСТАТА ПОЛОВИНА НА ЕНДЖИНА ЗА БОЛНИЧНИ — чл. 40 ал. 5 КСО (WS-3c).
 *
 * <p><b>Какво е това и какво НЕ е.</b> Тук живее САМО аритметиката и правилата ѝ: от (епизоди,
 * празници, период, стаж, дата на раждане) към ДНИ, и от (база, дни, норма, процент) към СУМИ. Няма
 * база данни, няма {@code Beans.get}, няма Hibernate — оттам класът върви на всяка машина, винаги,
 * и падне ли тест, паднала е сметката, а не средата. Кой чете речника на отсъствията, кой чете
 * производствения календар и кой записва перото е работа на {@code BgSickLeaveService}; тук това не
 * се появява.
 *
 * <p><b>Закръгляне по § 5.3.</b> Междинните стъпки вървят с ПЪЛНА точност и закръглянето става
 * ВЕДНЪЖ, накрая, до 2 знака по „половина нагоре“. Затова всяка формула е записана като едно
 * умножение и едно деление: {@code база × процент × дни / (100 × норма)}. Верига от закръглени
 * междинни числа дава друг резултат — при 1805,00 / 23 = 78,478260…, закръглено на 78,48 и после
 * умножено, сумата излиза 109,87 или 109,88 според реда на стъпките.
 *
 * <p><b>⚠️ КАКВО ТОЗИ КЛАС НЕ СМЯТА, с причина.</b> Сумата, която НОИ плаща — 80 на сто по чл. 41
 * КСО (90 при трудова злополука), върху осигурителния доход за 18 месеца по Приложение 9 — НЕ е тук
 * и НЕ е перо на фиша. НОИ я плаща на лицето директно; работодателят подава само данни. Това е
 * WS-7. От дните за ДОО тук се ползва единствено броят им — за здравната вноска по ЗЗО чл. 40 ал. 1
 * т. 5 и за показване на фиша.
 *
 * <p><b>⚠️ „НЕ ЗНАМ“ НЕ Е „НУЛА“.</b> Генерираните getter-и на Axelor връщат {@code
 * BigDecimal.ZERO} и {@code 0}, тоест непопълнено поле пристига като нула. Затова методите с {@code
 * Integer} и {@code BigDecimal} параметри тук РАЗЛИЧАВАТ {@code null} („не е подадено“) от нула
 * („подадено е и е нула“), а тези с {@code int} се викат само след като разликата вече е направена.
 */
public final class BgSickLeaveResolver {

  /** Знаците след десетичната запетая на всяко перо — § 5.3. */
  public static final int SCALE = 2;

  /** „Половина нагоре“ — § 5.3. Едно и също правило навсякъде в модула. */
  public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

  /** Стабилният код на перото за болничен за сметка на осигурителя (PLT-195). */
  public static final String LINE_EMPLOYER_SICK_PAY = "pl_bolnichen_rabotodatel";

  /**
   * ДВА РАБОТНИ ДНИ, НЕ ТРИ — чл. 40 ал. 5 КСО.
   *
   * <p>Чл. 40 ал. 5 КСО (изм. ДВ бр. 106/2023, в сила от 01.01.2024): осигурителят изплаща за
   * първите ДВА работни дни от временната неработоспособност 70 на сто от среднодневното брутно
   * възнаграждение за месеца, в който е настъпила неработоспособността, но не по-малко от 70 на сто
   * от среднодневното уговорено възнаграждение. Преди 2024 г. дните бяха три. Текстът е сверен (A5,
   * 10.2026); същото е записано в {@code BgPayrollInput#sickLeaveEpisodes} и в {@code
   * BgWorkTimeFacts}.
   *
   * <p>Числото стои НАИМЕНУВАНО и се чете на ЕДНО място точно защото се е сменяло веднъж и ще се
   * смени пак: тогава се сменя един ред, а не се търси „2“ из формулите.
   */
  public static final int EMPLOYER_PAID_WORKING_DAYS = 2;

  /**
   * ШЕСТ МЕСЕЦА ОСИГУРИТЕЛЕН СТАЖ — прагът за правото на парично обезщетение.
   *
   * <p>Под него осигурителят НЕ плаща първите дни. Мери се в ПЪЛНИ МЕСЕЦИ, а не в години, точно
   * защото прагът е шест месеца: в години той е дроб, а дроб в поле за цели числа е тиха грешка.
   * Същият довод е записан и в javadoc-а на {@code BgPayrollInput#insuranceServiceMonths}.
   */
  public static final int MIN_INSURANCE_SERVICE_MONTHS = 6;

  /**
   * ВЪЗРАСТТА, под която шестмесечният праг НЕ важи. Единица: ГОДИНИ.
   *
   * <p><b>⚠️ ПРАВИЛОТО Е НЕСВЕРЕНО, И ТОВА Е ЗАПИСАНО ПРЕДИ ДА СЕ ПОЛЗВА.</b> Javadoc-ът на {@code
   * BgPayrollInput#birthDate} го казва дословно: „източникът е двусмислен дали изключението важи
   * изобщо, или само при трудова злополука и професионална болест“. Тук е закодирано ПО-ШИРОКОТО
   * четене — изключението важи за всеки вид неработоспособност — защото такова е указанието, по
   * което работим, и защото по-тясното четене отказва обезщетение на непълнолетно лице, тоест
   * грешката му се плаща от лицето.
   *
   * <p>Стои НАИМЕНУВАНО и се чете на ЕДНО място, за да се обърне с един ред, ако дословният текст
   * се окаже по-тесен. Свидетелят му е {@code BgSickLeaveResolverTest}.
   */
  public static final int AGE_WITHOUT_SERVICE_REQUIREMENT = 18;

  /**
   * ВИДОВЕТЕ, ПРИ КОИТО ШЕСТМЕСЕЧНИЯТ ПРАГ НЕ ВАЖИ — трудова злополука и професионална болест.
   *
   * <p>⚠️ КОДОВЕ, а не категория, и разликата е в парите. {@code BgAbsenceType.category} = 3
   * („Злополука“) носи И трудовата, И БИТОВАТА злополука ({@code home_accident_er}), а прагът пада
   * само при първата. Филтър по категория щеше да освободи от праг и битовата злополука — по-щедро
   * от закона, и то тихо.
   *
   * <p><b>ПРОФЕСИОНАЛНАТА БОЛЕСТ ИМА СВОЙ ВИД В РЕЧНИКА.</b> Без него тя би влязла като ОБЩО
   * ЗАБОЛЯВАНЕ и прагът ѝ би важал — по-строго от закона, и то тихо. Затова {@code
   * bg_absence_type.csv} освен {@code work_accident_er} и {@code work_accident_doo} носи и {@code
   * occupational_disease_er} (70 на сто, платец осигурителят) и {@code occupational_disease_doo}
   * (90 на сто, платец ДОО) — редове, ОГЛЕДАЛНИ на трудовата злополука по всяка друга колона, и
   * двата кода влизат тук.
   *
   * <p><b>Защо СЪЩИЯТ механизъм, а не втори.</b> Чл. 40 ал. 2 КСО не иска шестте месеца нито при
   * трудова злополука, нито при професионална болест: прагът по чл. 40 ал. 1 е за осигурените за
   * ОБЩО ЗАБОЛЯВАНЕ И МАЙЧИНСТВО, а двете тук са рискът ТЗПБ — друг риск, друг фонд. Чл. 41 ал. 1
   * КСО ги слага в едно и също изречение и с една и съща ставка („90 на сто … при трудова злополука
   * или професионална болест“), а чл. 40 ал. 5 дава първите {@link #EMPLOYER_PAID_WORKING_DAYS}
   * работни дни на осигурителя при 70 на сто без оглед на риска. Тоест разликата между двете е
   * ИМЕТО на риска, не сметката — затова процентът и платецът идват от речника ({@code
   * BgAbsenceType.payer} / {@code compensationPct}), а тук влиза само кодът.
   *
   * <p>⚠️ КАТЕГОРИЯТА ИМ Е 3 („Злополука“) — същата, като на трудовата, защото оста, по която
   * речникът групира, е РИСКЪТ (фонд ТЗПБ), не буквалният смисъл на етикета. Етикетът е за екрана;
   * прагът пада по този набор от КОДОВЕ и точно затова той не се извежда от категорията.
   */
  public static final Set<String> CODES_WITHOUT_SERVICE_REQUIREMENT =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              Arrays.asList(
                  "work_accident_er",
                  "work_accident_doo",
                  "occupational_disease_er",
                  "occupational_disease_doo")));

  /**
   * КАТЕГОРИЯТА „МАЙЧИНСТВО-БАЩИНСТВО“ по {@code bg.absence.category.select} — рискът, за който
   * присъдата на ТОЗИ клас НЕ се смята.
   *
   * <p>⚠️ Майчинството има свой праг — дванайсет месеца по чл. 48а КСО, без изключението за
   * ненавършили 18 години — и той е друг въпрос с друг вход. Тук то следва своето правило:
   * осигурителят не плаща първите дни, дните са за ДОО. Всичко ОСТАНАЛО на болничен лист (болест,
   * гледане на болен, битова и трудова злополука, професионална болест, карантина като общо
   * заболяване) минава през правото по {@link #MIN_INSURANCE_SERVICE_MONTHS}. Същата стойност е и
   * {@code BgApp9ObstoyatelstvaResolver.CATEGORY_MATERNITY} — там т. 3 вместо т. 2.
   */
  public static final int RISK_MATERNITY_CATEGORY = 4;

  /**
   * ВИДОВЕТЕ, КОИТО ТВЪРДЯТ „БЕЗ ПРАВО НА ОБЕЗЩЕТЕНИЕ“ — вход, а не присъда.
   *
   * <p>Ако изборът на такъв вид беше присъдата (платец „none“, осигурителят не плаща, дните в т.
   * 16.2), лице със стаж от години би губило тихо 70-те процента по чл. 40 ал. 5, а при лице без
   * стаж Д1 би обявила право, което няма. Затова присъдата се изчислява и видът се СВЕРЯВА с нея:
   * при „има право“ — отказ. Видовете остават в речника за историята (заварените листове и
   * миграцията), но НЕ се предлагат за нови листове — {@code bg-sick-leave-views.xml} изключва
   * точно тези кодове, а {@code BgSickLeaveNoBenefitPickerTrapTest} пази двете места да не се
   * разминат.
   */
  public static final Set<String> CODES_CLAIMING_NO_BENEFIT =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(Arrays.asList("sick_no_benefit_er", "sick_no_benefit_doo")));

  /**
   * <b>⚠️ РЕШЕНИЕ № 1 — НАИМЕНУВАН ШЕВ: КОЯ Е БАЗАТА НА „СРЕДНОДНЕВНОТО БРУТНО ЗА МЕСЕЦА“.</b>
   * ПОСТОЯННОТО МЕСЕЧНО ВЪЗНАГРАЖДЕНИЕ, РАЗДЕЛЕНО НА РАБОТНИТЕ ДНИ — И ДВЕТЕ ЗА МЕСЕЦА НА
   * НАСТЪПВАНЕ.
   *
   * <p><b>Защо това е решение, а не прочит.</b> Чл. 40 ал. 5 КСО казва „70 на сто от среднодневното
   * брутно възнаграждение за месеца, в който е настъпила неработоспособността, но не по-малко от 70
   * на сто от среднодневното уговорено възнаграждение“. Нормата НЕ казва как се получава
   * среднодневното: ако то се брои от ДЕЙСТВИТЕЛНО начисленото бруто, лице, болно ЦЕЛИЯ месец, има
   * бруто нула и болничен нула — резултат, който самата алинея изключва със своя долен праг.
   *
   * <p>Затова базата е УГОВОРЕНОТО месечно възнаграждение, делено на работните дни в периода —
   * точно както прави и Микроинвест на сверения фиш от 07.2026 г.: 1805,00 / 23 = 78,478260…, и
   * оттам 70 на сто × 2 дни = 109,87. Делителят е РАБОТНИ ДНИ, същият, който ползват обезщетенията
   * ({@code BgCompensationResolver}) и начисленията ({@code BgEarningsResolver}) — три различни
   * делителя за едно понятие „среднодневно“ са три различни числа на един фиш.
   *
   * <p><b>⚠️ „УГОВОРЕНОТО“ Е ПОСТОЯННОТО, НЕ САМО ОСНОВНАТА.</b> Основата е основната заплата плюс
   * всички допълнителни плащания с постоянен характер за месеца (клас, професионален опит, научна
   * степен, ръчните постоянни пера). Сглобява се в {@code BgSickLeaveService#onsetBases} от същото
   * {@code M}, на което стъпва и надбавката по чл. 177. Сверката от 07.2026 не се мести: фишът там
   * няма клас.
   *
   * <p><b>⚠️ МЕСЕЦЪТ Е НА НАСТЪПВАНЕТО, НЕ НА ФИША.</b> И числителят, и знаменателят са от фиша за
   * месеца, в който е започнал непрекъснатият период — виж {@link
   * BgSickLeaveDays#employerDaysByOnset} и {@link #sickPayLine(BgSickLeaveFacts, BgSickLeaveDays,
   * Map)}. Лист от 30.09 плаща 01.10 по септемврийската база.
   *
   * <p>⚠️ Долният праг по ал. 5 ОСТАВА заложен като {@code max} в {@link #sickPayAmount}, макар
   * днес да съвпада с базата. Изтрит „защото е излишен“, той трябва да се измисля отново в деня, в
   * който базата стане действителното бруто — а тогава вече ще има фишове, сметнати без него.
   */
  public static final boolean DAILY_BASE_IS_CONTRACTED_SALARY = true;

  /**
   * <b>⚠️ РЕШЕНИЕ № 2 — НАИМЕНУВАН ШЕВ: КОЯ Е СТАВКАТА НА ЗДРАВНАТА ВНОСКА ЗА ДНИТЕ ВНР.</b> ЧАСТТА
   * НА РАБОТОДАТЕЛЯ ОТ ЗДРАВНАТА ВНОСКА, ПРОЧЕТЕНА ОТ ТАБЛИЦАТА СЪС СТАВКИ.
   *
   * <p>ЗЗО чл. 40 ал. 1 т. 5 определя БАЗАТА — пълният минимален осигурителен доход за
   * самоосигуряващите се лица ({@code HEALTH_ABSENCE_BASE}: 550,66 до 31.07.2026 г., 620,20 от
   * 01.08.2026 г.) — и КОЙ плаща: вноската е за сметка на работодателя. Ставката НЕ се въвежда
   * наново: тя е ставката на фонд {@code HEALTH} за платеца РАБОТОДАТЕЛ от {@code
   * bg_insurance_contribution} за вида осигурен, категорията труд и периода — за трета категория,
   * вид 01, 2026 г. това е 4,8000 на сто.
   *
   * <p><b>Защо това е решение, а не изчитане.</b> „За сметка на работодателя“ може да се прочете и
   * като „целите 8 на сто, но платени от работодателя“. Възпроизведеното число решава спора: 550,66
   * × 21 / 23 × 4,8 на сто = 24,13 — точно сумата на сверения фиш от 07.2026 г. При 8 на сто същата
   * база дава 40,22, тоест числото на фиша изключва това четене.
   *
   * <p><b>⚠️ ДОСЛОВНИЯТ ТЕКСТ ВЕЧЕ Е ЧЕТЕН ОТ ПЪРВИЧЕН ИЗТОЧНИК</b> (ЗЗО, редакция ДВ бр. 68 от
   * 2026 г., в сила от 01.08.2026 г.) и потвърждава прочита ДУМА ПО ДУМА: „…минималният
   * осигурителен доход за самоосигуряващите се лица; вноските са за сметка на работодателя или
   * ведомството и са РАВНИ НА ДЪЛЖИМАТА ОТ НЕГО ЧАСТ ОТ ВНОСКАТА…“. Тоест частта на работодателя е
   * самата норма, а не извод от фиша — сверката срещу практиката остава като втори, независим
   * свидетел.
   *
   * <p><b>⚠️ И ЕДНО НОВО ИЗРЕЧЕНИЕ ОТ СЪЩАТА РЕДАКЦИЯ, КОЕТО ЗАТВАРЯ СЪСЕДНИЯ ВЪПРОС:</b> „За
   * възнаграждението по чл. 40, ал. 5 от Кодекса за социално осигуряване осигурителните вноски се
   * дължат по реда на т. 1.“ Тоест двете здравни числа на един болничен фиш са РАЗЛИЧНИ вноски и не
   * се дублират: върху сумата по чл. 40 ал. 5 КСО (болничния за сметка на осигурителя) върви
   * РЕДОВНОТО ЗОВ по т. 1 — 4,8 / 3,2 върху самото възнаграждение, — а върху дните ДОО върви тази
   * тук — частта на работодателя върху {@code HEALTH_ABSENCE_BASE}. Слети в едно перо, те стават
   * непроверими; това е и първият довод да не се пишат в {@code pl_vn_health_osig} (516).
   *
   * <p>⚠️ Хипотезата на т. 5 е ПО-ШИРОКА от болничния: същата точка изброява и отпуска за
   * отглеждане на дете до 2 г. по чл. 164 ал. 1 и 3 КТ, при осиновяване по чл. 164б ал. 1 и 5 КТ и
   * от бащата по чл. 164в КТ. Днес енджинът стига до тях само доколкото те влизат в епизодите
   * ({@code dooDays}) — дупката е във ВХОДА (WS-5), не в тази формула.
   *
   * <p>Ставката не е константа в кода точно защото е данни: от 01.08.2026 г. до 31.12.2026 г. вид
   * 05 и вид 24 имат съотношение 80/20, тоест 6,4 на сто за работодателя. Число в кода щеше да
   * сметне 4,8 и за тях.
   */
  public static final boolean HEALTH_ABSENCE_RATE_IS_EMPLOYER_SHARE = true;

  /**
   * <b>⚠️ РЕШЕНИЕ № 3 — НАИМЕНУВАН ШЕВ: КОГА ДВА БОЛНИЧНИ ЛИСТА СА ЕДИН ЕПИЗОД.</b> При вдигнат
   * флаг „продължение“ ИЛИ при съседни дати.
   *
   * <p>Двата дни по чл. 40 ал. 5 са за ЦЕЛИЯ непрекъснат период на неработоспособност, а не за
   * всеки лист. Флагът {@code continuation} е основният разграничител и идва от самия болничен
   * лист. Съседството по дати е ВТОРА мрежа за случая, в който флагът не е вдигнат, а вторият лист
   * започва в деня след края на първия — тогава непрекъснатостта е ФАКТ и не зависи от отметка.
   *
   * <p>⚠️ Празник или събота и неделя между двата листа НЕ ги слепват без флаг. Посоката е избрана
   * нарочно: слепването НАМАЛЯВА дните за сметка на работодателя, тоест греши в посока „по-малко
   * пари за лицето“, и то без нищо на екрана. Разделянето греши в обратната посока и се вижда като
   * два пъти по два дни на фиша. При наистина един епизод листът носи флага — за това е.
   */
  public static final boolean ADJACENT_DATES_CONTINUE_THE_SPELL = true;

  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private BgSickLeaveResolver() {}

  // ═══ РАЗДЕЛЯНЕТО НА ДНИТЕ ════════════════════════════════════════════════

  /**
   * ЕПИЗОДИ → ДНИ: колко работни дни са за сметка на осигурителя и колко остават за ДОО.
   *
   * <p><b>Стъпките, в този ред и с тази причина.</b>
   *
   * <ol>
   *   <li><b>Слепване в НЕПРЕКЪСНАТИ ПЕРИОДИ.</b> Виж {@link #ADJACENT_DATES_CONTINUE_THE_SPELL}.
   *       От гол брой дни това е неизразимо — точно затова контрактът носи епизоди.
   *   <li><b>ПРАВОТО — по РИСКА на ПЪРВИЯ епизод на периода, НЕ по платеца.</b> Шестмесечният праг
   *       с двете изключения: трудова злополука и професионална болест ({@link
   *       #CODES_WITHOUT_SERVICE_REQUIREMENT}) и ненавършени 18 години към началото на периода
   *       ({@link #AGE_WITHOUT_SERVICE_REQUIREMENT}). Важи и за лист ДОО, и за гледане на болен.
   *       Майчинството е извън тази присъда ({@link #RISK_MATERNITY_CATEGORY}). Вид, който твърди
   *       „без право“, при сметнато „има право“ → отказ. Виж {@link #eligibilityOf}.
   *   <li><b>Платецът на първите дни — по ВИДА на ПЪРВИЯ епизод</b>, и само при право. Видът на
   *       продължението не ги мени. При {@code payer != employer} (лист ДОО, гледане на болен,
   *       майчинство) осигурителят плаща НУЛА.
   *   <li><b>Първите {@link #EMPLOYER_PAID_WORKING_DAYS} РАБОТНИ дни на периода</b>, броени от
   *       НАЧАЛОТО МУ — после се пита колко от тях падат в ТОЗИ период на фиша. Оттам болничен,
   *       който започва в края на юли и продължава в август, дава по един ден в двата фиша, а не по
   *       два.
   *   <li><b>Дните за ДОО</b> = всички работни дни на периодите, падащи в периода на фиша, МИНУС
   *       дните за сметка на осигурителя. Тоест „работни дни неработоспособност БЕЗ осигурителен
   *       доход“ — точно базата, която ЗЗО чл. 40 ал. 1 т. 5 иска.
   *   <li><b>Същите дни, срязани по (лист × месец × платец)</b> — разрезът на т. 9 от Приложение №
   *       9. ⚠️ ВТОРИ ИЗГЛЕД, НЕ ВТОРА СМЕТКА: двата броя горе се изчисляват точно както преди
   *       срезовете да съществуват и НЕ се извеждат от тях. Срезовете вървят в СЪЩИЯ обход, по
   *       същия признак за работен ден и по същите дати на първите дни — оттам не могат да
   *       отговарят на друг въпрос. Че наистина не отговарят, се пита от {@link
   *       BgSickLeaveDays#linesReconcile()}.
   * </ol>
   *
   * <p><b>⚠️ ПРАЗЕН СПИСЪК Е ЗАКОНЕН СЛУЧАЙ, НЕ ЛИПСА.</b> Този месец лицето не е боледувало —
   * връща се нула дни, а не отказ.
   *
   * <p>Списъкът идва от {@code BgPayrollInputBuilder#sickLeaveEpisodesOf}, който чете ВАЛИДИРАНИТЕ
   * листове по договор и период.
   *
   * @param episodes епизодите, вече съпоставени с речника. {@code null} или празен — законно нула
   * @param holidays официалните празници, засягащи периодите. {@code null} се чете като „няма“
   * @param periodFrom първи ден на периода на фиша. Не {@code null}
   * @param periodTo последен ден на периода на фиша. Не {@code null}
   * @param insuranceServiceMonths осигурителен стаж в ПЪЛНИ МЕСЕЦИ, или {@code null} („не знам“)
   * @param birthDate датата на раждане, или {@code null} — нужна само за изключението под 18 г.
   */
  public static BgSickLeaveDays split(
      List<BgSickLeaveEpisode> episodes,
      Set<LocalDate> holidays,
      LocalDate periodFrom,
      LocalDate periodTo,
      Integer insuranceServiceMonths,
      LocalDate birthDate) {
    return split(episodes, holidays, periodFrom, periodTo, insuranceServiceMonths, birthDate, null);
  }

  /**
   * Същото, но правото по стажа идва от ЗАМРАЗЕНАТА ПРИСЪДА на първия лист от периода.
   *
   * <p><b>⚠️ ИЗТОЧНИК, НЕ ЧИСЛО.</b> Резолверът остава чист: той не чете базата, а пита {@code
   * rightSource} за ПЪРВИЯ епизод на периода — и само когато въпросът за правото наистина възниква.
   * {@code BgSickLeaveService} отговаря със замразената присъда на листа (или я смята веднъж, към
   * началото на неработоспособността, и я замразява). Тогава {@code insuranceServiceMonths} и
   * {@code birthDate} не се четат: днешният стаж не решава правото на лист, започнал преди месеци.
   *
   * @param rightSource присъдата за първия епизод на периода. ⚠️ В производство НЕ връща {@code
   *     null}: {@code BgSickLeaveService#benefitVerdictOf} отказва за епизод без лист. {@code null}
   *     (и целият параметър {@code null} — шестаргументният {@code split}) пускат старото правило
   *     по подадения стаж САМО за свидетелите, които мерят дни и пари, не правото ({@code
   *     BgSickLeaveServiceOnDeclaredStage}).
   */
  public static BgSickLeaveDays split(
      List<BgSickLeaveEpisode> episodes,
      Set<LocalDate> holidays,
      LocalDate periodFrom,
      LocalDate periodTo,
      Integer insuranceServiceMonths,
      LocalDate birthDate,
      Function<BgSickLeaveEpisode, BgSickBenefitVerdictRules.Verdict> rightSource) {

    if (periodFrom == null || periodTo == null || periodTo.isBefore(periodFrom)) {
      return BgSickLeaveDays.blocked(
          "периодът на фиша не е определен (от "
              + periodFrom
              + " до "
              + periodTo
              + ") — без него не се знае кои дни от болничния падат в ТОЗИ месец. Празният период НЕ"
              + " се чете като „целия болничен е тук“.");
    }
    if (episodes == null || episodes.isEmpty()) {
      return BgSickLeaveDays.none(
          "няма подадени епизоди временна неработоспособност — този месец лицето не е боледувало."
              + " ⚠️ И ТОВА ВЕЧЕ Е ТВЪРДЕНИЕ ЗА ДАННИТЕ, А НЕ ЗА МОДЕЛА: епизодите идват от"
              + " ВАЛИДИРАНИТЕ болнични листове (BgPayrollInputBuilder#sickLeaveEpisodesOf), тоест"
              + " празният списък значи, че за този договор и период няма нито един лист в статус"
              + " „Валидиран“. Лист в ЧЕРНОВА не се брои — провери статуса, преди да заключиш, че"
              + " болничен няма.");
    }

    List<BgSickLeaveEpisode> sorted = new ArrayList<>();
    for (BgSickLeaveEpisode e : episodes) {
      if (e == null || e.fromDate == null || e.toDate == null) {
        return BgSickLeaveDays.blocked(
            "епизод без начална или крайна дата — попълни ги на болничния лист. Липсваща дата НЕ се"
                + " чете като „един ден“: от нея зависи и броят дни, и в кой месец падат.");
      }
      if (e.toDate.isBefore(e.fromDate)) {
        return BgSickLeaveDays.blocked(
            "епизод с край преди началото си ("
                + e.fromDate
                + " – "
                + e.toDate
                + ") — поправи датите на болничния лист.");
      }
      sorted.add(e);
    }
    sorted.sort(
        (a, b) -> {
          int byStart = a.fromDate.compareTo(b.fromDate);
          return byStart != 0 ? byStart : a.toDate.compareTo(b.toDate);
        });

    Set<LocalDate> nonWorking = holidays == null ? Collections.<LocalDate>emptySet() : holidays;
    List<Spell> spells = spellsOf(sorted);
    for (Spell spell : spells) {
      if (spell.first.continuation) {
        return BgSickLeaveDays.blocked(headlessContinuation(spell.first));
      }
    }

    int employerDays = 0;
    int allDaysInPeriod = 0;
    int withoutBenefitDays = 0;
    Map<YearMonth, Integer> employerDaysByOnset = new LinkedHashMap<>();
    List<String> witness = new ArrayList<>();
    SliceBook book = new SliceBook();

    for (Spell spell : spells) {
      int spellDaysInPeriod =
          workingDaysBetween(max(spell.start, periodFrom), min(spell.end, periodTo), nonWorking);
      allDaysInPeriod += spellDaysInPeriod;

      Eligibility eligibility =
          eligibilityOf(spell, insuranceServiceMonths, birthDate, rightSource);
      if (eligibility.problem != null) {
        return BgSickLeaveDays.blocked(eligibility.problem);
      }
      if (!eligibility.eligible) {
        witness.add(
            label(spell) + ": осигурителят не дължи първите дни — " + eligibility.why + ".");
        if (eligibility.withoutBenefit) {
          // ⚠️ ПОДМНОЖЕСТВО НА ДНИТЕ ДОО, НЕ ТРЕТИ ПЛАТЕЦ. Дните остават в dooDays — здравната
          // вноска по ЗЗО чл. 40 ал. 1 т. 5 и т. 9 на Приложение № 9 ги четат там, — а този брой
          // казва само колко от тях са БЕЗ ПРАВО на обезщетение: Д1 ги обявява в т. 16.4, не в
          // 16.2.
          withoutBenefitDays += spellDaysInPeriod;
          witness.add(
              label(spell)
                  + ": "
                  + spellDaysInPeriod
                  + " работни дни в периода са БЕЗ право на обезщетение (чл. 40 ал. 1 КСО) — т. 16.4"
                  + " на Д1.");
        }
        // ⚠️ И БЕЗ ПРАВО НА ПЪРВИТЕ ДНИ ПЕРИОДЪТ ИМА РЕДОВЕ ПО Т. 9. Майчинството и болничният без
        // право на обезщетение от осигурителя пак се удостоверяват пред НОИ — просто всичките им
        // дни са на НОИ. Пропуснат тук, целият лист щеше да изчезне от т. 9 точно при видовете, при
        // които обезщетението е изцяло на института.
        book.collect(spell, Collections.<LocalDate>emptySet(), periodFrom, periodTo, nonWorking);
        continue;
      }

      List<LocalDate> opening = openingWorkingDays(spell, nonWorking);
      int inThisPeriod = 0;
      for (LocalDate day : opening) {
        if (!day.isBefore(periodFrom) && !day.isAfter(periodTo)) {
          inThisPeriod++;
        }
      }
      employerDays += inThisPeriod;
      // ⚠️ МЕСЕЦЪТ НА НАСТЪПВАНЕ Е НАЧАЛОТО НА ВЕРИГАТА, не месецът на деня: базата по ал. 5 е за
      // „месеца, в който е настъпила неработоспособността“. Лист от събота 31.10 плаща 02–03.11 по
      // октомври, макар и двата дни да са в ноември.
      YearMonth onset = YearMonth.from(spell.start);
      if (inThisPeriod > 0) {
        employerDaysByOnset.merge(onset, inThisPeriod, Integer::sum);
      }
      book.collect(spell, new LinkedHashSet<>(opening), periodFrom, periodTo, nonWorking);
      witness.add(
          label(spell)
              + ": "
              + inThisPeriod
              + " от първите "
              + EMPLOYER_PAID_WORKING_DAYS
              + " работни дни падат в този период"
              + (inThisPeriod > 0 && onset.isBefore(YearMonth.from(periodFrom))
                  ? " — по базата на "
                      + monthLabel(onset)
                      + ", месеца на настъпване (чл. 40 ал. 5 КСО)."
                  : "."));
    }

    int dooDays = allDaysInPeriod - employerDays;
    if (dooDays < 0) {
      // Не бива да се случи: дните за сметка на осигурителя са ПОДМНОЖЕСТВО на дните в периода.
      // Отказ, а не отрицателно число, което после ще се сумира от някого.
      return BgSickLeaveDays.blocked(
          "разделянето даде повече дни за сметка на осигурителя ("
              + employerDays
              + ") от всички работни дни неработоспособност в периода ("
              + allDaysInPeriod
              + ") — това е дефект в броенето, не входни данни.");
    }
    List<BgSickLeavePeriodSlice> lines = book.slices();
    if (book.uncoveredEmployerDays > 0 || book.uncoveredDooDays > 0) {
      // Обявено, не премълчано: това са дни, които СМЕТКАТА брои, а Приложение № 9 няма къде да
      // сложи, защото не принадлежат на никой лист. Виж BgSickLeaveDays#uncoveredEmployerDays.
      witness.add(
          "⚠️ "
              + (book.uncoveredEmployerDays + book.uncoveredDooDays)
              + " работни дни в периода не се покриват от НИТО ЕДИН болничен лист — лист, отметнат"
              + " като продължение, е слепен през календарна пролука. Дните ВЛИЗАТ в сметката"
              + " (както и досега), но ред по т. 9 на Приложение № 9 за тях не може да се създаде:"
              + " удостоверението се подава за ЛИСТ. Провери датите на продължението.");
    }
    // ⚠️ СВЕРКАТА СЕ ПРАВИ ПРЕДИ ОБЕКТА, НЕ ВЪРХУ НЕГО. Добавено към witness след конструирането,
    // изречението щеше да стигне до изхода само защото списъкът се споделя по референция — тоест
    // щеше да изчезне мълчаливо в деня, в който някой го обвие в unmodifiableList.
    int employerLineDays = 0;
    int dooLineDays = 0;
    for (BgSickLeavePeriodSlice line : lines) {
      if (line.isEmployerPaid()) {
        employerLineDays += line.workDays;
      } else {
        dooLineDays += line.workDays;
      }
    }
    if (employerLineDays + book.uncoveredEmployerDays != employerDays
        || dooLineDays + book.uncoveredDooDays != dooDays) {
      // ⚠️ НЕ Е ОТКАЗ. Виж BgSickLeaveDays#linesReconcile: сгрешен РАЗРЕЗ не бива да спира ПЕРО.
      witness.add(
          "⚠️ ДЕФЕКТ В РАЗРЕЗА ПО Т. 9: срезовете дават "
              + employerLineDays
              + " + "
              + book.uncoveredEmployerDays
              + " дни на осигурителя и "
              + dooLineDays
              + " + "
              + book.uncoveredDooDays
              + " на ДОО, а сметката дава "
              + employerDays
              + " и "
              + dooDays
              + ". Сумите на фиша са СМЕТНАТИ ОТ АГРЕГАТИТЕ и са верни; невярно е само"
              + " разпределението по листа, тоест Приложение № 9 не бива да се подава от този"
              + " разрез. Дефект в BgSickLeaveResolver, не входни данни.");
    }
    return BgSickLeaveDays.of(
        employerDays,
        dooDays,
        withoutBenefitDays,
        witness,
        lines,
        book.uncoveredEmployerDays,
        book.uncoveredDooDays,
        employerDaysByOnset);
  }

  /**
   * СЪБИРАЧЪТ НА СРЕЗОВЕТЕ — ден по ден, в ключ (лист, година, месец, платец).
   *
   * <p><b>⚠️ НЕ СМЯТА ДНИ ЗАНОВО.</b> Обхожда точно интервала, който {@link #workingDaysBetween}
   * вече е преброил, с точно същия признак за работен ден, и пита {@link #openingWorkingDays} за
   * същите дати, които вече са дали {@code employerDays}. Тоест срезовете не могат да отговорят на
   * друг въпрос от агрегатите — а че наистина не отговарят, се пита от {@link
   * BgSickLeaveDays#linesReconcile()}.
   */
  private static final class SliceBook {

    private final Map<String, Bucket> buckets = new LinkedHashMap<>();

    int uncoveredEmployerDays;
    int uncoveredDooDays;

    void collect(
        Spell spell,
        Set<LocalDate> employerDays,
        LocalDate periodFrom,
        LocalDate periodTo,
        Set<LocalDate> nonWorking) {

      LocalDate from = max(spell.start, periodFrom);
      LocalDate to = min(spell.end, periodTo);
      for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
        if (!isWorkingDay(day, nonWorking)) {
          continue;
        }
        boolean employerPaid = employerDays.contains(day);
        BgSickLeaveEpisode owner = spell.ownerOf(day);
        if (owner == null) {
          if (employerPaid) {
            uncoveredEmployerDays++;
          } else {
            uncoveredDooDays++;
          }
          continue;
        }
        add(owner, day, employerPaid, periodFrom, periodTo);
      }
    }

    private void add(
        BgSickLeaveEpisode owner,
        LocalDate day,
        boolean employerPaid,
        LocalDate periodFrom,
        LocalDate periodTo) {

      String payer =
          employerPaid ? BgSickLeavePeriodSlice.PAYER_EMPLOYER : BgSickLeavePeriodSlice.PAYER_NOI;
      String key =
          owner.sickLeaveId
              + "|"
              + owner.documentNumber
              + "|"
              + owner.fromDate
              + "|"
              + day.getYear()
              + "|"
              + day.getMonthValue()
              + "|"
              + payer;

      Bucket bucket = buckets.get(key);
      if (bucket == null) {
        // ⚠️ ГРАНИЦИТЕ СА КАЛЕНДАРНИ И ИДВАТ ОТ ЛИСТА, НЕ ОТ ДНИТЕ В КОФАТА. Указанието към т. 9:
        // „първият и последният КАЛЕНДАРЕН ден на периода по болничния лист за съответния месец“.
        // Взети като min/max на работните дни в кофата, болничен от събота щеше да почва от
        // понеделник — и двата реда на месеца (осигурител и НОИ) щяха да носят РАЗЛИЧНИ граници,
        // при положение че официалният ред за месеца е техният сбор.
        LocalDate monthStart = day.withDayOfMonth(1);
        LocalDate monthEnd = day.withDayOfMonth(day.lengthOfMonth());
        bucket =
            new Bucket(
                owner,
                max(max(owner.fromDate, monthStart), periodFrom),
                min(min(owner.toDate, monthEnd), periodTo),
                payer);
        buckets.put(key, bucket);
      }
      bucket.workDays++;
    }

    List<BgSickLeavePeriodSlice> slices() {
      List<BgSickLeavePeriodSlice> out = new ArrayList<>();
      for (Bucket b : buckets.values()) {
        out.add(
            new BgSickLeavePeriodSlice(
                b.owner.sickLeaveId,
                b.owner.documentNumber,
                b.owner.absenceTypeCode,
                b.fromDay,
                b.toDay,
                b.workDays,
                b.payer));
      }
      // ⚠️ ПОДРЕЖДА СЕ В JAVA, НЕ В БАЗАТА. Редът е част от изгледа (месец, после платец) и
      // подреден
      // от ORDER BY, той щеше да зависи от collation-а на инсталацията — под en_US.UTF-8 и под C
      // низовете се нареждат различно. Компараторът тук дава един и същ ред на всяка машина.
      out.sort(
          (a, b) -> {
            int byDay = a.fromDay.compareTo(b.fromDay);
            if (byDay != 0) {
              return byDay;
            }
            int byDoc =
                String.valueOf(a.documentNumber).compareTo(String.valueOf(b.documentNumber));
            return byDoc != 0 ? byDoc : a.payer.compareTo(b.payer);
          });
      return out;
    }

    private static final class Bucket {

      final BgSickLeaveEpisode owner;
      final LocalDate fromDay;
      final LocalDate toDay;
      final String payer;
      int workDays;

      Bucket(BgSickLeaveEpisode owner, LocalDate fromDay, LocalDate toDay, String payer) {
        this.owner = owner;
        this.fromDay = fromDay;
        this.toDay = toDay;
        this.payer = payer;
      }
    }
  }

  /**
   * ПЕРИОД, КОЙТО ЗАПОЧВА С ПРОДЪЛЖЕНИЕ — скъсана верига.
   *
   * <p>⚠️ ОТКАЗ, НЕ НОВ ПЕРИОД. Нов период би накарал осигурителя да плати първите два дни ВТОРИ
   * път — точно при продължение от 1-во число, чийто първи лист е в предходния месец. Сглобителят
   * тегли веригата назад по връзката ({@code BgPayrollInputBuilder#sickLeaveEpisodesOf});
   * продължение без предходния си лист значи, че веригата не може да се намери, и началото — от
   * което зависят първите дни и правото — е неизвестно.
   */
  private static String headlessContinuation(BgSickLeaveEpisode e) {
    return "болничният лист "
        + (e.documentNumber == null || e.documentNumber.trim().isEmpty()
            ? "без номер"
            : "№ " + e.documentNumber.trim())
        + " ("
        + e.fromDate
        + " – "
        + e.toDate
        + ") е ПРОДЪЛЖЕНИЕ, но предходният му лист не е намерен: връзката „Продължава лист“"
        + " липсва, предходният лист не е валидиран или е по друг договор. Без началото на"
        + " веригата не се знае дали първите два дни по чл. 40 ал. 5 КСО вече са платени — а"
        + " продължение, прочетено като нов болничен, ги плаща втори път. Посочете предходния"
        + " лист на продължението.";
  }

  /** Слепва епизодите в непрекъснати периоди — виж {@link #ADJACENT_DATES_CONTINUE_THE_SPELL}. */
  private static List<Spell> spellsOf(List<BgSickLeaveEpisode> sorted) {
    List<Spell> spells = new ArrayList<>();
    Spell current = null;
    for (BgSickLeaveEpisode e : sorted) {
      if (current == null) {
        current = new Spell(e);
        spells.add(current);
        continue;
      }
      boolean adjacent =
          ADJACENT_DATES_CONTINUE_THE_SPELL && !e.fromDate.isAfter(current.end.plusDays(1));
      if (e.continuation || adjacent) {
        current.extend(e);
      } else {
        current = new Spell(e);
        spells.add(current);
      }
    }
    return spells;
  }

  /**
   * Има ли право на първите дни за сметка на осигурителя този непрекъснат период.
   *
   * <p><b>⚠️ РЕДЪТ НА ВЪПРОСИТЕ Е ПРАВИЛОТО: първо ПРАВОТО, после ПЛАТЕЦЪТ.</b> Питан първи,
   * платецът би върнал „не“ за всеки период, започнал с лист ДОО — самостоятелен {@code sick_doo},
   * гледане на болен, битова злополука ДОО, — БЕЗ да мине през прага на стажа. Тоест лице под шест
   * месеца стаж би получило дни в т. 16.2 („с право на обезщетение“) само защото листът му е от
   * вида ДОО. Прагът по чл. 40 ал. 1 КСО е за РИСКА, а не за това кой плаща първите два дни:
   *
   * <ol>
   *   <li><b>Майчинството</b> ({@link #RISK_MATERNITY_CATEGORY}) — своя праг по чл. 48а, не тази
   *       присъда. Само платецът казва дали осигурителят дължи нещо (не дължи).
   *   <li><b>ПРАВОТО</b> по риска временна неработоспособност — {@link #rightOf}: изключенията
   *       (ТЗПБ, под 18 г.), после прагът. Липсващ вход → отказ.
   *   <li><b>ВИДЪТ Е ТВЪРДЕНИЕ, НЕ ИЗТОЧНИК.</b> Лист с вид от {@link #CODES_CLAIMING_NO_BENEFIT}
   *       твърди, че право НЯМА. Сметнатата присъда „има право“ го опровергава → ШУМЕН ОТКАЗ, не
   *       тихо предпочитане на едното. Обратната посока не е разминаване: {@code sick_er} / {@code
   *       sick_doo} при присъда „без право“ са законни — дните отиват в т. 16.4, перо няма.
   *   <li><b>ПЛАТЕЦЪТ</b> — само при право: осигурителят плаща първите дни, ако видът е негов.
   * </ol>
   */
  private static Eligibility eligibilityOf(
      Spell spell,
      Integer insuranceServiceMonths,
      LocalDate birthDate,
      Function<BgSickLeaveEpisode, BgSickBenefitVerdictRules.Verdict> rightSource) {

    if (spell.first.payer == null) {
      return Eligibility.blocked(
          "видът отсъствие „"
              + spell.first.absenceTypeCode
              + "“ не е намерен в речника (bg_absence_type.csv) — не се знае кой плаща първите дни."
              + " Непознат вид НЕ се чете като „платец няма“: това е липсващ ред в речника. Пусни"
              + " App Install или поправи кода на болничния лист.");
    }
    if (isMaternityRisk(spell.first)) {
      return spell.first.employerPaysOpeningDays()
          ? Eligibility.yes()
          : Eligibility.no(
              "видът „"
                  + spell.first.absenceTypeCode
                  + "“ е от риска майчинство с платец „"
                  + spell.first.payer
                  + "“, тоест първите дни не са за сметка на осигурителя");
    }

    Eligibility right = rightOf(spell, insuranceServiceMonths, birthDate, rightSource);
    if (right.problem != null) {
      return right;
    }
    String claim = claimedWithoutBenefit(spell);
    if (right.eligible) {
      if (claim != null) {
        return Eligibility.blocked(
            "листът е с вид „"
                + claim
                + "“, тоест ТВЪРДИ, че лицето няма право на обезщетение, а сметнатата присъда е С"
                + " ПРАВО ("
                + right.why
                + "). Видът е вход, присъдата се изчислява — при разминаване нито едното не се"
                + " предпочита тихо. Ако правото наистина липсва, поправи стажа или датата на"
                + " раждане при служителя; иначе смени вида на листа на обикновения (болничен Р-л или"
                + " ДОО). ⚠️ Видовете „без право на обезщетение“ не се предлагат за нови листове:"
                + " присъдата вече се смята.");
      }
      if (!spell.first.employerPaysOpeningDays()) {
        return Eligibility.no(
            "видът „"
                + spell.first.absenceTypeCode
                + "“ е с платец „"
                + spell.first.payer
                + "“, тоест първите дни не са за сметка на осигурителя (лист ДОО, гледане на болен)."
                + " Правото на обезщетение е налице: "
                + right.why);
      }
      return Eligibility.yes();
    }
    return right;
  }

  /**
   * ПРАВОТО ПО РИСКА „ВРЕМЕННА НЕРАБОТОСПОСОБНОСТ“ — с право, без право (чл. 40 ал. 1 КСО) или
   * отказ.
   *
   * <p>Не пита платеца: виж {@link #eligibilityOf}. Изключенията са преди прага, инак той би
   * поискал стаж, който нормата не изисква — и би отказал при липсващ вход на лице, което ИМА
   * правото.
   *
   * <p><b>ЗАМРАЗЕНАТА ПРИСЪДА НА ПЪРВИЯ ЛИСТ Е ОТГОВОРЪТ.</b> Когато {@code rightSource} е подаден
   * (пътят на фиша), правото идва от присъдата към началото на неработоспособността — изключенията
   * (ТЗПБ, под 18 г.) са вече в нея; днешният стаж и днешната възраст не се питат. Сверката вид ↔
   * присъда и платецът в {@link #eligibilityOf} остават след нея. Правилото по подадения стаж
   * отдолу е само за свидетелите без лист ({@code BgSickLeaveServiceOnDeclaredStage}).
   *
   * @return {@code yes} с причина в {@code why}; {@code withoutBenefit}; или {@code blocked}
   */
  private static Eligibility rightOf(
      Spell spell,
      Integer insuranceServiceMonths,
      LocalDate birthDate,
      Function<BgSickLeaveEpisode, BgSickBenefitVerdictRules.Verdict> rightSource) {
    BgSickBenefitVerdictRules.Verdict verdict =
        rightSource == null ? null : rightSource.apply(spell.first);
    if (verdict != null) {
      if (verdict.isRefused()) {
        return Eligibility.blocked(verdict.refusal);
      }
      return verdict.hasRight()
          ? Eligibility.yes(verdict.why)
          : Eligibility.withoutBenefit(verdict.why + " — присъдата е към началото на листа");
    }
    if (CODES_WITHOUT_SERVICE_REQUIREMENT.contains(spell.first.absenceTypeCode)) {
      return Eligibility.yes(
          "трудова злополука или професионална болест — без изискване за стаж (чл. 40 ал. 2 КСО)");
    }
    if (insuranceServiceMonths != null && insuranceServiceMonths >= MIN_INSURANCE_SERVICE_MONTHS) {
      return Eligibility.yes(
          "осигурителен стаж "
              + insuranceServiceMonths
              + " месеца ≥ "
              + MIN_INSURANCE_SERVICE_MONTHS
              + " (чл. 40 ал. 1 КСО)");
    }
    if (birthDate != null
        && ChronoUnit.YEARS.between(birthDate, spell.start) < AGE_WITHOUT_SERVICE_REQUIREMENT) {
      return Eligibility.yes(
          "лицето не е навършило "
              + AGE_WITHOUT_SERVICE_REQUIREMENT
              + " години към началото на неработоспособността (чл. 40 ал. 1 изр. 2 КСО)");
    }
    if (insuranceServiceMonths == null) {
      return Eligibility.blocked(
          "осигурителният стаж не е деклариран при служителя (Employee.bgInsuranceServiceMonths)"
              + " — без него НЕ се знае дали лицето има"
              + " право на обезщетение по чл. 40 ал. 1 КСО. Въпросът се задава за ВСЕКИ лист от риска"
              + " временна неработоспособност (болест, гледане на болен, битова злополука), независимо"
              + " кой плаща първите дни. Празно НЕ е нула месеца: нула значи „лицето е в първия си"
              + " месец осигуряване“ и е установен факт. Отметнете при служителя „Осигурителният стаж"
              + " е деклариран“ и въведете месеците.");
    }
    if (birthDate == null) {
      return Eligibility.blocked(
          "осигурителният стаж е "
              + insuranceServiceMonths
              + " месеца, тоест под "
              + MIN_INSURANCE_SERVICE_MONTHS
              + ", а датата на раждане липсва — не се знае дали важи изключението за ненавършили"
              + " 18 години. Попълни Employee.birthDate. БЕЗ нея отговорът не е нула дни, а"
              + " НЕИЗВЕСТЕН.");
    }
    return Eligibility.withoutBenefit(
        "осигурителният стаж е "
            + insuranceServiceMonths
            + " месеца, под изискваните "
            + MIN_INSURANCE_SERVICE_MONTHS
            + " по чл. 40 ал. 1 КСО, и лицето е навършило "
            + AGE_WITHOUT_SERVICE_REQUIREMENT
            + " години");
  }

  /** Рискът майчинство — по категорията от речника, не по кода. Виж {@link #eligibilityOf}. */
  private static boolean isMaternityRisk(BgSickLeaveEpisode episode) {
    return episode.category != null && episode.category == RISK_MATERNITY_CATEGORY;
  }

  /**
   * Кодът на ПЪРВИЯ лист от периода, който ТВЪРДИ „без право на обезщетение“, или {@code null}.
   *
   * <p>⚠️ ВСЕКИ лист, не само първият: продължение {@code sick_no_benefit_doo} след лист {@code
   * sick_er} при лице с право е същото разминаване, само по-скрито.
   */
  private static String claimedWithoutBenefit(Spell spell) {
    for (BgSickLeaveEpisode e : spell.members) {
      if (CODES_CLAIMING_NO_BENEFIT.contains(e.absenceTypeCode)) {
        return e.absenceTypeCode;
      }
    }
    return null;
  }

  /**
   * ПЪРВИТЕ {@link #EMPLOYER_PAID_WORKING_DAYS} РАБОТНИ ДНИ на непрекъснат период — като ДАТИ.
   *
   * <p>Дати, а не брой, точно защото после се пита в КОЙ период падат. Брой би бил достатъчен само
   * за болничен, който изцяло стои в един месец — тоест би работил, докато не стигне до половин
   * епизод.
   */
  static List<LocalDate> openingWorkingDays(Spell spell, Set<LocalDate> holidays) {
    List<LocalDate> out = new ArrayList<>();
    LocalDate day = spell.start;
    while (!day.isAfter(spell.end) && out.size() < EMPLOYER_PAID_WORKING_DAYS) {
      if (isWorkingDay(day, holidays)) {
        out.add(day);
      }
      day = day.plusDays(1);
    }
    return out;
  }

  /**
   * РАБОТНИТЕ ДНИ в затворен интервал.
   *
   * <p>⚠️ Същото правило като {@code BgCompensationResolver.workingDaysIn}: събота и неделя не са
   * работни, официалните празници се изваждат. Не се преизползва пряко, защото онзи метод приема
   * НАЧАЛО И БРОЙ КАЛЕНДАРНИ ДНИ, а тук интервалите се режат по границите на периода и краищата им
   * са дати. Превръщането „дати → брой календарни дни“ на всяко повикване е точно видът аритметика,
   * от която идват грешките с ± един ден.
   */
  static int workingDaysBetween(LocalDate from, LocalDate to, Set<LocalDate> holidays) {
    if (from == null || to == null || to.isBefore(from)) {
      return 0;
    }
    int days = 0;
    for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
      if (isWorkingDay(day, holidays)) {
        days++;
      }
    }
    return days;
  }

  private static boolean isWorkingDay(LocalDate day, Set<LocalDate> holidays) {
    if (day.getDayOfWeek() == DayOfWeek.SATURDAY || day.getDayOfWeek() == DayOfWeek.SUNDAY) {
      return false;
    }
    return holidays == null || !holidays.contains(day);
  }

  private static LocalDate max(LocalDate a, LocalDate b) {
    return a.isAfter(b) ? a : b;
  }

  private static LocalDate min(LocalDate a, LocalDate b) {
    return a.isBefore(b) ? a : b;
  }

  private static String label(Spell spell) {
    return "епизод "
        + spell.first.absenceTypeCode
        + " "
        + spell.start
        + " – "
        + spell.end
        + (spell.parts > 1 ? " (" + spell.parts + " листа, слепени в един период)" : "");
  }

  /** Месецът така, както го пише човек: {@code 09.2026}. */
  static String monthLabel(YearMonth month) {
    return String.format("%02d.%d", month.getMonthValue(), month.getYear());
  }

  // ═══ ЧИСТА АРИТМЕТИКА ════════════════════════════════════════════════════

  /**
   * БОЛНИЧЕН ЗА СМЕТКА НА ОСИГУРИТЕЛЯ = база × процент × дни / (100 × норма), с наложен ПОД по ал.
   * 5.
   *
   * <p>Едно умножение и едно деление, тоест ЕДНО закръгляне — § 5.3. Подът се смята по същата
   * формула върху УГОВОРЕНОТО и по-голямото от двете печели; днес двете съвпадат по решение № 1, но
   * {@code max} остава — виж {@link #DAILY_BASE_IS_CONTRACTED_SALARY}.
   *
   * @param monthlyBase месечната база на среднодневното брутно. Единица: ЕВРО на месец
   * @param contractedMonthly уговореното месечно възнаграждение за пода. {@code null} — без под
   * @param compensationPct процентът от речника. Единица: ПРОЦЕНТ (70 значи 70 на сто)
   * @param employerDays работни дни за сметка на осигурителя. Единица: РАБОТНИ ДНИ
   * @param workingDaysInPeriod работни дни в периода — делителят. Трябва да е > 0
   */
  public static BigDecimal sickPayAmount(
      BigDecimal monthlyBase,
      BigDecimal contractedMonthly,
      BigDecimal compensationPct,
      int employerDays,
      int workingDaysInPeriod) {
    BigDecimal fromBase = share(monthlyBase, compensationPct, employerDays, workingDaysInPeriod);
    if (contractedMonthly == null || contractedMonthly.signum() <= 0) {
      return fromBase;
    }
    BigDecimal floor = share(contractedMonthly, compensationPct, employerDays, workingDaysInPeriod);
    return fromBase.max(floor);
  }

  /**
   * ЗДРАВНА ВНОСКА ЗА ДНИТЕ ВНР = база × дни / норма × ставка / 100 — ЗЗО чл. 40 ал. 1 т. 5.
   *
   * <p>Едно умножение и едно деление, тоест ЕДНО закръгляне — § 5.3. Базата НЕ е заплатата на
   * лицето, а законов параметър ({@code HEALTH_ABSENCE_BASE}); ставката е частта на РАБОТОДАТЕЛЯ —
   * виж {@link #HEALTH_ABSENCE_RATE_IS_EMPLOYER_SHARE}.
   *
   * @param healthAbsenceBase {@code HEALTH_ABSENCE_BASE} за периода. Единица: ЕВРО на месец
   * @param dooDays работни дни неработоспособност БЕЗ осигурителен доход. Единица: РАБОТНИ ДНИ
   * @param workingDaysInPeriod работни дни в периода — делителят. Трябва да е > 0
   * @param employerRatePct ставката на фонд HEALTH за платеца работодател. Единица: ПРОЦЕНТ
   */
  public static BigDecimal healthAbsenceAmount(
      BigDecimal healthAbsenceBase,
      int dooDays,
      int workingDaysInPeriod,
      BigDecimal employerRatePct) {
    return share(healthAbsenceBase, employerRatePct, dooDays, workingDaysInPeriod);
  }

  /**
   * ПРОПОРЦИОНАЛНАТА БАЗА на здравната вноска по ЗЗО чл. 40 ал. 1 т. 5 — това, което пълни т. 17.
   *
   * <p><b>⚠️ ОТДЕЛЕН ВХОД, А НЕ КОЛИЧЕСТВОТО НА ПЕРОТО.</b> {@code healthAbsenceLine} връща в
   * {@code quantity} ДНИТЕ (21) — заковано е от свидетел и е вярната документация на сумата за
   * човека, който гледа фиша. Декларацията обаче иска БАЗАТА в левове (502,78): т. 18 е „размерът
   * на вноската ВЪРХУ СУМАТА В Т. 17“. Двете са различни величини и не могат да делят една колона.
   *
   * <p><b>⚠️ И ЗАЩО СЪЩАТА ФОРМУЛА, А НЕ ОБРАТНА СМЕТКА.</b> Базата НЕ се получава от {@code сума ×
   * 100 / ставка}: сумата вече е закръглена и делението връща 502,7833…, тоест ДРУГО число.
   * Единственият верен път е същият, по който е сметната сумата — {@code месечно × дни / норма} — и
   * затова живее тук, до нея, а не в услугата.
   *
   * <p>550,66 × 21 / 23 = 502,78 за златния фиш, а 502,78 × 4,8 % = 24,13.
   */
  public static BigDecimal healthAbsenceProratedBase(
      BigDecimal healthAbsenceBase, int dooDays, int workingDaysInPeriod) {
    if (healthAbsenceBase == null || workingDaysInPeriod <= 0) {
      return null;
    }
    return healthAbsenceBase
        .multiply(BigDecimal.valueOf(dooDays))
        .divide(BigDecimal.valueOf(workingDaysInPeriod), SCALE, ROUNDING);
  }

  /** {@code месечно × процент × дни / (100 × норма)} — едно деление, едно закръгляне. */
  private static BigDecimal share(
      BigDecimal monthly, BigDecimal pct, int days, int workingDaysInPeriod) {
    return monthly
        .multiply(pct)
        .multiply(BigDecimal.valueOf(days))
        .divide(HUNDRED.multiply(BigDecimal.valueOf(workingDaysInPeriod)), SCALE, ROUNDING);
  }

  /**
   * {@code Σ числител_i / знаменател_i} с ЕДНО деление и едно закръгляне (§ 5.3): общият знаменател
   * е произведението на знаменателите. При един член това е точно {@link #share}.
   */
  private static BigDecimal sumOfShares(
      List<BigDecimal> numerators, List<BigDecimal> denominators) {
    BigDecimal common = BigDecimal.ONE;
    for (BigDecimal d : denominators) {
      common = common.multiply(d);
    }
    BigDecimal numerator = BigDecimal.ZERO;
    for (int i = 0; i < numerators.size(); i++) {
      BigDecimal others = BigDecimal.ONE;
      for (int j = 0; j < denominators.size(); j++) {
        if (j != i) {
          others = others.multiply(denominators.get(j));
        }
      }
      numerator = numerator.add(numerators.get(i).multiply(others));
    }
    return numerator.divide(common, SCALE, ROUNDING);
  }

  /** СРЕДНОДНЕВНАТА база — само за документиране на сумата ({@code bgRate}), не за сметката. */
  static BigDecimal dailyBase(BigDecimal monthly, int workingDaysInPeriod) {
    return monthly.divide(BigDecimal.valueOf(workingDaysInPeriod), 4, ROUNDING);
  }

  // ═══ ПРАВИЛАТА ЗА „НЕПОПЪЛНЕНО ≠ НУЛА“ ══════════════════════════════════

  /**
   * ПЕРОТО НА БОЛНИЧНИЯ — сума ИЛИ обявена причина да няма сума.
   *
   * <p>Отказва при непопълнена база, непопълнена норма и непопълнен процент в речника: и трите
   * пристигат като нула по пътя на генерирания getter, а нула тук значи „болничен нула евро“ —
   * сума, която фишът показва като законна. НУЛА ДНИ за сметка на осигурителя, обратно, е ЗАКОНЕН
   * случай и НЕ дава перо — виж {@link BgSickLeaveOutcome.Status#NO_EMPLOYER_DAYS}.
   *
   * <p>⚠️ ЕДНА БАЗА ЗА ВСИЧКИ ДНИ — тази на {@code facts}, без оглед на месеца на настъпване. Само
   * за свидетелите на аритметиката. Фишът минава през {@link #sickPayLine(BgSickLeaveFacts,
   * BgSickLeaveDays, Map)}: там всеки ден се смята по месеца, в който е настъпила
   * неработоспособността.
   */
  public static BgSickLeaveOutcome sickPayLine(BgSickLeaveFacts facts, BgSickLeaveDays days) {
    BgSickLeaveOutcome notPayable = noSickPayDays(days);
    if (notPayable != null) {
      return notPayable;
    }

    String missing = missingCommonInput(facts);
    if (missing != null) {
      return BgSickLeaveOutcome.refused(LINE_EMPLOYER_SICK_PAY, missing);
    }
    notPayable = noCompensationPct(facts, days);
    if (notPayable != null) {
      return notPayable;
    }

    return BgSickLeaveOutcome.computed(
        LINE_EMPLOYER_SICK_PAY,
        sickPayAmount(
            facts.monthlyBase,
            facts.contractedMonthly,
            facts.compensationPct,
            days.employerDays,
            facts.workingDaysInPeriod),
        BigDecimal.valueOf(days.employerDays),
        dailyBase(facts.monthlyBase, facts.workingDaysInPeriod));
  }

  /**
   * ПЕРОТО НА БОЛНИЧНИЯ ПО МЕСЕЦА НА НАСТЪПВАНЕ — чл. 40 ал. 5 КСО дословно.
   *
   * <p><b>Основание.</b> „70 на сто от среднодневното брутно възнаграждение за МЕСЕЦА, В КОЙТО Е
   * НАСТЪПИЛА неработоспособността, но не по-малко от 70 на сто от среднодневното уговорено
   * възнаграждение“. Всеки ден на осигурителя се смята по базата на месеца, в който е започнал
   * непрекъснатият му период ({@link BgSickLeaveDays#employerDaysByOnset}), а базата идва от {@code
   * bases}: числител и знаменател от фиша за онзи месец. Лист от сряда 30.09 плаща 01.10 на
   * октомврийския фиш по СЕПТЕМВРИЙСКАТА база.
   *
   * <p>⚠️ ЕДНО ЗАКРЪГЛЯНЕ И ПРИ ДВА МЕСЕЦА (§ 5.3). Дни от два периода на един фиш (продължение от
   * миналия месец и нов лист в този) се събират с пълна точност и се закръглят веднъж, накрая. При
   * един месец числото съвпада с {@link #sickPayAmount}.
   *
   * <p>⚠️ ОТКАЗ, НЕ ПРИБЛИЖЕНИЕ. Месец без база (няма потвърден фиш за него) отказва ЦЯЛОТО перо с
   * причината, която носи {@link BgSickLeaveOnsetBase#refusal}. Базата на текущия фиш на мястото на
   * липсващата е точно дефектът, който този метод маха.
   *
   * @param bases базата за всеки месец на настъпване от {@code days}
   */
  public static BgSickLeaveOutcome sickPayLine(
      BgSickLeaveFacts facts, BgSickLeaveDays days, Map<YearMonth, BgSickLeaveOnsetBase> bases) {
    BgSickLeaveOutcome notPayable = noSickPayDays(days);
    if (notPayable != null) {
      return notPayable;
    }
    if (facts == null) {
      return BgSickLeaveOutcome.refused(LINE_EMPLOYER_SICK_PAY, missingCommonInput(null));
    }
    notPayable = noCompensationPct(facts, days);
    if (notPayable != null) {
      return notPayable;
    }

    int byOnset = 0;
    for (int d : days.employerDaysByOnset.values()) {
      byOnset += d;
    }
    if (byOnset != days.employerDays) {
      return BgSickLeaveOutcome.refused(
          LINE_EMPLOYER_SICK_PAY,
          "дните за сметка на осигурителя ("
              + days.employerDays
              + ") не са разпределени по месец на настъпване ("
              + byOnset
              + ") — а базата по чл. 40 ал. 5 КСО е за месеца, в който е настъпила"
              + " неработоспособността. Дефект в повикващия (разбив без месеца на настъпване), не"
              + " входни данни.");
    }

    List<BigDecimal> numerators = new ArrayList<>();
    List<BigDecimal> denominators = new ArrayList<>();
    BgSickLeaveOnsetBase single = null;
    for (Map.Entry<YearMonth, Integer> e : days.employerDaysByOnset.entrySet()) {
      BgSickLeaveOnsetBase base = bases == null ? null : bases.get(e.getKey());
      if (base == null) {
        return BgSickLeaveOutcome.refused(
            LINE_EMPLOYER_SICK_PAY,
            "няма база за месеца на настъпване "
                + monthLabel(e.getKey())
                + " — дефект в повикващия: всеки месец на настъпване трябва да получи база или"
                + " отказ.");
      }
      if (base.refusal != null) {
        return BgSickLeaveOutcome.refused(LINE_EMPLOYER_SICK_PAY, base.refusal);
      }
      if (base.monthlyBase == null || base.monthlyBase.signum() <= 0) {
        return BgSickLeaveOutcome.refused(
            LINE_EMPLOYER_SICK_PAY,
            "базата за " + monthLabel(e.getKey()) + " (месеца на настъпване): " + missingBase());
      }
      if (base.workingDays == null || base.workingDays <= 0) {
        return BgSickLeaveOutcome.refused(
            LINE_EMPLOYER_SICK_PAY,
            "базата за "
                + monthLabel(e.getKey())
                + " (месеца на настъпване): "
                + missingWorkingDays());
      }
      // Подът по ал. 5 е със същия знаменател, тоест max на числителите е max на дробите.
      BigDecimal monthly = base.monthlyBase;
      if (base.contractedMonthly != null && base.contractedMonthly.compareTo(monthly) > 0) {
        monthly = base.contractedMonthly;
      }
      numerators.add(
          monthly.multiply(facts.compensationPct).multiply(BigDecimal.valueOf(e.getValue())));
      denominators.add(HUNDRED.multiply(BigDecimal.valueOf(base.workingDays)));
      single = days.employerDaysByOnset.size() == 1 ? base : null;
    }

    return BgSickLeaveOutcome.computed(
        LINE_EMPLOYER_SICK_PAY,
        sumOfShares(numerators, denominators),
        BigDecimal.valueOf(days.employerDays),
        // ⚠️ Среднодневното документира сумата само при ЕДИН месец на настъпване; при два то е две
        // числа и едно от тях на реда би обяснявало друга сума.
        single == null ? null : dailyBase(single.monthlyBase, single.workingDays));
  }

  /** Отговорът, когато перо 195 изобщо няма да има дни — или {@code null}, ако ги има. */
  private static BgSickLeaveOutcome noSickPayDays(BgSickLeaveDays days) {
    if (days == null) {
      return BgSickLeaveOutcome.refused(
          LINE_EMPLOYER_SICK_PAY,
          "разделянето на дните не е направено — енджинът е извикан без резултат от"
              + " BgSickLeaveResolver.split. Това е дефект в повикващия, не входни данни.");
    }
    if (!days.isResolved()) {
      return BgSickLeaveOutcome.refused(LINE_EMPLOYER_SICK_PAY, days.problem);
    }
    if (days.employerDays == 0) {
      return BgSickLeaveOutcome.noEmployerDays(
          LINE_EMPLOYER_SICK_PAY,
          "нула работни дни за сметка на осигурителя този период. "
              + days.witnessAsText()
              + " Перо НЕ се създава: сума 0,00 щеше да твърди, че болничен ИМА и е нулев.");
    }
    return null;
  }

  /** Отговорът при липсващ или нулев процент в речника — или {@code null}, ако е положителен. */
  private static BgSickLeaveOutcome noCompensationPct(
      BgSickLeaveFacts facts, BgSickLeaveDays days) {
    if (facts.compensationPct == null) {
      return BgSickLeaveOutcome.refused(
          LINE_EMPLOYER_SICK_PAY,
          "процентът на обезщетението не е определен — колоната compensationPct на вида отсъствие в"
              + " bg_absence_type.csv е празна. БЕЗ него болничният НЕ се смята като нула: чл. 40"
              + " ал. 5 КСО дължи 70 на сто, а не нула на сто.");
    }
    if (facts.compensationPct.signum() <= 0) {
      return BgSickLeaveOutcome.noEmployerDays(
          LINE_EMPLOYER_SICK_PAY,
          "видът отсъствие е с обезщетение "
              + facts.compensationPct.toPlainString()
              + " на сто, тоест без парично обезщетение от осигурителя. "
              + days.witnessAsText());
    }
    return null;
  }

  /**
   * ЗДРАВНАТА ВНОСКА ЗА ДНИТЕ ВНР — сума ИЛИ обявена причина да няма сума.
   *
   * <p>⚠️ Отказва при липсващ параметър {@code HEALTH_ABSENCE_BASE} и при липсваща ставка, вместо
   * да сметне нула: липсваща вноска за сметка на работодателя е НЕДЕКЛАРИРАНА вноска, а нула на
   * фиша изглежда като „не се дължи“.
   *
   * <p>Кодът на изхода е този на перото на болничния, а не на осигурителен ред — виж javadoc-а на
   * {@code BgSickLeaveService#healthAbsence}: тази сума ДНЕС не се записва като перо и затова не
   * получава свой код.
   */
  public static BgSickLeaveOutcome healthAbsenceLine(BgSickLeaveFacts facts, BgSickLeaveDays days) {
    if (days == null || !days.isResolved()) {
      return BgSickLeaveOutcome.refused(
          LINE_EMPLOYER_SICK_PAY,
          days == null
              ? "разделянето на дните не е направено — енджинът е извикан без резултат от"
                  + " BgSickLeaveResolver.split."
              : days.problem);
    }
    if (days.dooDays == 0) {
      return BgSickLeaveOutcome.noEmployerDays(
          LINE_EMPLOYER_SICK_PAY,
          "нула работни дни неработоспособност без осигурителен доход — здравна вноска по ЗЗО чл. 40"
              + " ал. 1 т. 5 не се дължи този период. "
              + days.witnessAsText());
    }
    if (facts.workingDaysInPeriod == null || facts.workingDaysInPeriod <= 0) {
      return BgSickLeaveOutcome.refused(LINE_EMPLOYER_SICK_PAY, missingWorkingDays());
    }
    if (facts.healthAbsenceBase == null || facts.healthAbsenceBase.signum() <= 0) {
      return BgSickLeaveOutcome.refused(
          LINE_EMPLOYER_SICK_PAY,
          "параметърът HEALTH_ABSENCE_BASE не е зареден за периода — базата на здравната вноска по ЗЗО"
              + " чл. 40 ал. 1 т. 5 е пълният минимален осигурителен доход за самоосигуряващите се"
              + " лица (550,66 до 31.07.2026 г., 620,20 от 01.08.2026 г.). Зареди го през App Install"
              + ". БЕЗ него вноската НЕ е нула — тя е недекларирана.");
    }
    if (facts.healthEmployerRatePct == null || facts.healthEmployerRatePct.signum() <= 0) {
      return BgSickLeaveOutcome.refused(
          LINE_EMPLOYER_SICK_PAY,
          "ставката на фонд HEALTH за платеца РАБОТОДАТЕЛ не е намерена за този вид осигурен,"
              + " категория труд и период в bg_insurance_contribution.csv. Ставката НЕ се въвежда в"
              + " кода: от 01.08.2026 г. вид 05 и вид 24 са 6,4 на сто, а не 4,8 — число в кода щеше"
              + " да сметне 4,8 и за тях.");
    }

    return BgSickLeaveOutcome.computed(
        LINE_EMPLOYER_SICK_PAY,
        healthAbsenceAmount(
            facts.healthAbsenceBase,
            days.dooDays,
            facts.workingDaysInPeriod,
            facts.healthEmployerRatePct),
        BigDecimal.valueOf(days.dooDays),
        facts.healthEmployerRatePct);
  }

  /** Двете липси, без които формулата на болничния не е определена. */
  private static String missingCommonInput(BgSickLeaveFacts facts) {
    if (facts == null) {
      return "числата за болничния не са сглобени — енджинът е извикан без BgSickLeaveFacts. Дефект в"
          + " повикващия, не входни данни.";
    }
    if (facts.monthlyBase == null || facts.monthlyBase.signum() <= 0) {
      return missingBase();
    }
    if (facts.workingDaysInPeriod == null || facts.workingDaysInPeriod <= 0) {
      return missingWorkingDays();
    }
    return null;
  }

  private static String missingBase() {
    return "основната заплата не е попълнена — попълни я на трудовия договор или на фиша. Нула тук"
        + " НЕ се чете като „заплата нула евро“: генерираният getter връща нула и за непопълнено"
        + " поле, а болничен 0,00 щеше да изглежда като законно изчислен.";
  }

  private static String missingWorkingDays() {
    return "работните дни в периода не са определени — прегледай производствения календар за годината"
        + " или впиши числото на ръка на фиша. БЕЗ делител среднодневното възнаграждение не"
        + " съществува, а не е равно на нула.";
  }

  // ═══ ВЪТРЕШНИ НОСАЧИ ═════════════════════════════════════════════════════

  /** Един НЕПРЕКЪСНАТ период неработоспособност — един или няколко слепени листа. */
  static final class Spell {

    /** ПЪРВИЯТ епизод — неговият вид решава правото на първите дни. */
    final BgSickLeaveEpisode first;

    /**
     * ВСИЧКИ листа на този период, в реда, в който са слепени.
     *
     * <p>⚠️ Слепването е за ПАРИТЕ — първите два работни дни се броят от началото на целия период,
     * не на всеки лист. Разрезът по т. 9 на Приложение № 9 обаче е ПО ЛИСТ (чл. 8 ал. 1 НПОПДОО:
     * „за всеки болничен лист … отделни удостоверения“). Тоест същият период трябва да се чете и
     * слято, и по листа — оттам списъкът тук, до {@link #first}.
     */
    final List<BgSickLeaveEpisode> members = new ArrayList<>();

    final LocalDate start;
    LocalDate end;
    int parts = 1;

    Spell(BgSickLeaveEpisode first) {
      this.first = first;
      this.start = first.fromDate;
      this.end = first.toDate;
      this.members.add(first);
    }

    void extend(BgSickLeaveEpisode next) {
      if (next.toDate.isAfter(end)) {
        end = next.toDate;
      }
      members.add(next);
      parts++;
    }

    /**
     * Листът, на който принадлежи този ден — ПЪРВИЯТ, който го покрива.
     *
     * <p>⚠️ ПЪРВИЯТ, а не „някой“: два листа законно покриват един ден (собствен болничен и гледане
     * на болно дете не се изключват), а денят е ЕДИН и в сметката влиза веднъж. Раздаден на всеки
     * покриващ го лист, сборът на срезовете щеше да надхвърли дните и т. 9 щеше да декларира дни,
     * които никой не е бил болен. {@code null} значи, че денят е в календарна пролука на
     * продължение — виж {@code BgSickLeaveDays#uncoveredEmployerDays}.
     */
    BgSickLeaveEpisode ownerOf(LocalDate day) {
      for (BgSickLeaveEpisode e : members) {
        if (!day.isBefore(e.fromDate) && !day.isAfter(e.toDate)) {
          return e;
        }
      }
      return null;
    }
  }

  /** Право на първите дни: да, не с причина, или отказ с причина. */
  private static final class Eligibility {

    final boolean eligible;
    final String why;
    final String problem;

    /**
     * Няма право на обезщетение ИЗОБЩО — нито от осигурителя, нито от ДОО (КСО чл. 40 ал. 1).
     *
     * <p>⚠️ НЕ Е СЪЩОТО КАТО {@code !eligible}. „Не“ казва само, че ПЪРВИТЕ ДНИ не са за сметка на
     * осигурителя — а при майчинство или лист с платец ДОО обезщетение ИМА, просто от НОИ. Тук е
     * единствено прагът на стажа, след изключенията за трудова злополука, професионална болест и
     * ненавършили 18 години. Оттам т. 16.4 на Д1 вместо т. 16.2.
     */
    final boolean withoutBenefit;

    private Eligibility(boolean eligible, String why, String problem, boolean withoutBenefit) {
      this.eligible = eligible;
      this.why = why;
      this.problem = problem;
      this.withoutBenefit = withoutBenefit;
    }

    static Eligibility yes() {
      return new Eligibility(true, null, null, false);
    }

    /** С право — и ОТКЪДЕ, за да може отказът при разминаване с вида да го назове. */
    static Eligibility yes(String why) {
      return new Eligibility(true, why, null, false);
    }

    static Eligibility no(String why) {
      return new Eligibility(false, why, null, false);
    }

    /** Под прага на стажа по чл. 40 ал. 1 КСО — без първите дни И без обезщетение от ДОО. */
    static Eligibility withoutBenefit(String why) {
      return new Eligibility(false, why, null, true);
    }

    static Eligibility blocked(String problem) {
      return new Eligibility(false, null, problem, false);
    }
  }
}
