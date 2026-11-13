package com.axelor.apps.bglocalization.service.d1.adapter;

import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.service.d1.BgD1CorrectionRules;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * ЕДИН ЗАПИС → ЕДИН РЕД ОТ {@code EMPL2021.TXT} — 53 полета, разделени със ЗАПЕТАЯ (54 само за
 * осигурителна каса).
 *
 * <p><b>⚠️ ТОВА Е ФОРМАТЪТ, НЕ СМИСЪЛЪТ.</b> {@link BgD1RecordAdapter} дава карта „точка → низ“ и
 * спира там, защото ПОДРЕДБАТА и ШИРИНИТЕ идват от ЗАПОВЕД по чл. 8 ал. 5, а не от наредбата. Този
 * клас знае само нея. Разделението се държи: смисълът се сверява срещу Приложение № 1, формата —
 * срещу заповедта, и всяко от двете има свой свидетел.
 *
 * <p><b>ПРАВИЛАТА НА ФОРМАТА, ВСЯКО СЪС СВОЯ СЛЕДА В КОДА:</b>
 *
 * <ul>
 *   <li><b>Разделител ЗАПЕТАЯ</b>, не фиксирана ширина. Оттам и защо {@code Character} полетата НЕ
 *       бива да носят запетая вътре — тя би добавила поле и разместила всичко след себе си;
 *   <li><b>{@code Character} в ДВОЙНИ КАВИЧКИ</b>, само ГЛАВНИ букви на кирилица, без кавички и
 *       запетаи вътре;
 *   <li><b>{@code Numeric} без стойност → „0“</b>, не празно. Празно числово поле е дефект на
 *       формата, а не липса на данни. ⚠️ Сумите и ставките обаче излизат „0.00“ — виж {@link #sum};
 *   <li><b>КОД КОРЕКЦИЯ Е ЦИФРА</b>: редовни → 0, „К“ → 1, „З“ → 8. ⚠️ Записът пази БУКВИТЕ, защото
 *       наредбата ги дава като букви („попълва се буква „К““); файлът иска цифри. Преводът е ТУК и
 *       никъде другаде.
 * </ul>
 *
 * <p><b>⚠️ ЗАЛИЧАВАЩИЯТ РЕД НОСИ ВСИЧКИТЕ 53 ПОЛЕТА, А НЕ ДЕВЕТ.</b> Указание т. 1 казва кои точки
 * се ПОПЪЛВАТ; форматът е разделен със запетая и иска всичките си полета, инак следващото се чете
 * едно наляво. Тоест деветте носят стойност, а останалите излизат като „0“, „0.00“ или празни
 * кавички. Ред с девет полета би бил отхвърлен целият — а изглежда „по указанието“.
 *
 * <p><b>⚠️ СВЕРЕНО С ДВА ФАЙЛА, ПРИЕТИ ОТ НАП (10.2026):</b> коригираща (код 1) и заличаваща (код
 * 8) Д1, анонимизирани, в {@code src/test/resources/d1/etalon/}. Оттам и петте поправки спрямо
 * предишния ред — 53 полета, „0.00“, месец и вид осигурен без водеща нула, часове с три позиции, т.
 * 13 „0000“. Свидетелят е {@code BgD1EtalonFileTest}. Краят на реда НЕ е взет от тези еталони: те
 * са с LF и без CTRL+Z (минали през пренос, който ги е нормализирал).
 *
 * <p><b>⚠️ СВЕРЕНО И С ПОДАДЕНИ ФАЙЛОВЕ НА MICROINVEST (09.2026 и 01.2025):</b> Windows-1251, CRLF
 * след всеки ред и CTRL+Z след последния — тоест точно каквото заповедта иска и {@code
 * BgD1FileService} пише. Оттам и поле 51 = „000“. Свидетелят е {@code BgMicroinvestEtalonFileTest}
 * — целият файл, байт по байт.
 */
@Singleton
public class BgD1FileAdapter {

  /** Името на файла — от заповедта по чл. 8 ал. 5. */
  public static final String FILE_NAME = "EMPL2021.TXT";

  /** Разделителят. Форматът е РАЗДЕЛЕН, не с фиксирана ширина. */
  public static final char SEPARATOR = ',';

  /** Кодът за редовна декларация във файла. */
  public static final String CODE_REGULAR = "0";

  /** Кодът за коригираща декларация — буква „К“ на ниво запис. */
  public static final String CODE_CORRECTION = "1";

  /** Кодът за заличаваща декларация — буква „З“ на ниво запис. */
  public static final String CODE_DELETION = "8";

  /**
   * Поле 51 — код на осигурителна каса.
   *
   * <p>⚠️ „999“ значи, че подателят Е осигурителна каса, и ТОГАВА поле 54 носи неговия ЕИК. Нула
   * значи обикновен осигурител. Този модул подава за работодател, тоест нула — но стойността е
   * НАЗОВАНА, а не гол литерал, защото при първия клиент-каса ще трябва да се смени точно тя.
   *
   * <p>⚠️ ТРИ НУЛИ, БЕЗ КАВИЧКИ: така я носи подаденият в НАП файл на Microinvest ({@code
   * …,481.27,000,0,"…"}), в ширината на „999“. Други еталони носят „0“ — НАП е приел и двете, а
   * изборът следва Microinvest.
   */
  public static final String NOT_AN_INSURANCE_FUND = "000";

  /** Поле 51 за осигурителна каса — тогава и само тогава редът носи поле 54. */
  public static final String INSURANCE_FUND = "999";

  /**
   * Редът на един запис — 54 полета в реда на заповедта.
   *
   * @param sourceFlag поле 53: ЕИК на разработчика или на осигурителната каса. Може да е {@code
   *     null}
   */
  public String toLine(BgD1Record record, String sourceFlag) {
    List<String> f = new ArrayList<>(54);
    if (record == null) {
      return "";
    }

    f.add(bare(record.getT2Month())); //                     1 · месец, без водеща нула
    f.add(num(record.getT3Year())); //                       2 · година
    f.add(chr(record.getT4ObligedPersonCode())); //          3 · ЕИК
    f.add(chr(record.getT5PersonalId())); //                 4 · ЕГН/ЛНЧ
    f.add(num(record.getT51PersonalIdCode())); //            5 · код за ЕГН/ЛНЧ
    f.add(chr(record.getT6Surname())); //                    6 · фамилия
    f.add(chr(record.getT7Initials())); //                   7 · инициали
    f.add(bare(record.getT12InsuredTypeCode())); //          8 · вид осигурен, без водеща нула

    // 9–18 · ПЕТ ДВОЙКИ възникване/прекъсване. ⚠️ ВСИЧКИТЕ ДЕСЕТ полета излизат, включително
    // празните: липсващо поле би преместило всяко следващо едно наляво, тоест „последен ден 15“ би
    // се прочело като „възникване 15“.
    f.add(num(record.getT14Day1()));
    f.add(num(record.getT15Day1()));
    f.add(num(record.getT14Day2()));
    f.add(num(record.getT15Day2()));
    f.add(num(record.getT14Day3()));
    f.add(num(record.getT15Day3()));
    f.add(num(record.getT14Day4()));
    f.add(num(record.getT15Day4()));
    f.add(num(record.getT14Day5()));
    f.add(num(record.getT15Day5()));

    f.add(chr(t16(record))); //                             19 · т. 16, ЧЕТИРИ позиции в ЕДНО поле
    f.add(num(record.getT161WorkedDays())); //              20 · т. 16.1
    f.add(num(record.getT162SickDays())); //                21 · т. 16.2
    f.add(num(record.getT163ChildCareDays())); //           22 · т. 16.3
    f.add(num(record.getT164NoContributionDays())); //      23 · т. 16.4
    f.add(num(record.getT165UnpaidLeaveDays())); //         24 · т. 16.5
    f.add(num(record.getT16aEmployerSickDays())); //        25 · т. 16.А
    f.add(hours(record.getT167WorkedHours())); //           26 · т. 16.7, три позиции
    f.add(hours(record.getT168OvertimeHours())); //         27 · т. 16.8, три позиции

    f.add(num(record.getT121QualificationGroup())); //      28 · т. 12.1
    f.add(chr(record.getT122ActivityCode())); //            29 · т. 12.2, КИД, Char =4
    f.add(num(record.getT123ActivityRow())); //             30 · т. 12.3

    f.add(chr(t13(record))); //                              31 · т. 13, ДВЕ позиции в ЕДНО поле

    f.add(sum(record.getT17Art4015Income())); //            32 · т. 17
    f.add(rate(record.getT18HealthRateOnT17())); //         33 · т. 18
    f.add(sum(record.getT21InsuranceIncome())); //          34 · т. 21
    f.add(rate(record.getT22DooRateEmployer())); //         35 · т. 22 А
    f.add(rate(record.getT22DooRateInsured())); //          36 · т. 22 Б
    f.add(rate(record.getT221HealthRateEmployer())); //     37 · т. 22.1 А
    f.add(rate(record.getT221HealthRateInsured())); //      38 · т. 22.1 Б
    f.add(rate(record.getT222TzpbRate())); //               39 · т. 22.2
    f.add(rate(record.getT23TeacherPfRate())); //           40 · т. 23
    f.add(rate(record.getT25PpfRate())); //                 41 · т. 25
    f.add(rate(record.getT26UpfRateEmployer())); //         42 · т. 26 А
    f.add(rate(record.getT26UpfRateInsured())); //          43 · т. 26 Б
    f.add(sum(record.getT27HealthOnlyIncome())); //         44 · т. 27
    f.add(rate(record.getT28HealthRateOnT27())); //         45 · т. 28
    f.add(sum(record.getT29GrossWage())); //                46 · т. 29
    f.add(rate(record.getT30GvrsRate())); //                47 · т. 30
    f.add(sum(record.getT31TaxableIncome())); //            48 · т. 31
    f.add(sum(record.getT31aTax())); //                     49 · т. 31а
    f.add(sum(record.getT34NetPay())); //                   50 · т. 34

    f.add(NOT_AN_INSURANCE_FUND); //                        51 · код осиг. каса
    f.add(correctionCode(record)); //                       52 · код корекция — ЦИФРА
    f.add(chr(sourceFlag)); //                              53 · флаг за източник
    // 54 · ЕИК на осигурителната каса — САМО при поле 51 = 999 („В тези случаи в поле 54 …“).
    // ⚠️ Не винаги, като празни кавички: така излизат 54 полета срещу 53 в приетите от НАП файлове.
    if (INSURANCE_FUND.equals(NOT_AN_INSURANCE_FUND)) {
      f.add(chr(null));
    }

    return String.join(String.valueOf(SEPARATOR), f);
  }

  /**
   * КОДЪТ КОРЕКЦИЯ КАТО ЦИФРА — единственото място, където буквата се превежда.
   *
   * <p>⚠️ ЗАПИСЪТ ПАЗИ БУКВАТА, ФАЙЛЪТ ИСКА ЦИФРА, и това не е излишен слой: наредбата казва
   * „попълва се буква „К““ и човекът, който сверява хартиения образец, вижда буква. Заповедта по
   * чл. 8 ал. 5 иска 0/1/8. Слети в едно, или записът лъже за наредбата, или файлът — за заповедта.
   */
  public String correctionCode(BgD1Record record) {
    if (BgD1CorrectionRules.isDeletion(record)) {
      return CODE_DELETION;
    }
    if (BgD1CorrectionRules.isCorrection(record)) {
      return CODE_CORRECTION;
    }
    return CODE_REGULAR;
  }

  /**
   * ПОЛЕ 19 — четирите позиции на т. 16, слепени в ЕДИН низ с ширина 4.
   *
   * <p>⚠️ ПУБЛИЧЕН, като {@link #chr}, {@link #num}, {@link #sum} и {@link #rate} — и това не е
   * разхлабване за удобство на теста. Тези пет метода СА договорът на класа: всяко от петте правила
   * на формата („Character в кавички“, „Numeric без стойност → 0“, две различни ширини за суми и
   * ставки, четирите позиции на поле 19) е проверимо ПООТДЕЛНО само през тях. Проверени единствено
   * през целия ред, те се сверяват веднъж — а правило, което важи за 20 полета, заслужава свой
   * свидетел, не да се крие в един дълъг низ.
   *
   * <p>⚠️ ЕДИНСТВЕНОТО ПОЛЕ С ФИКСИРАНА ШИРИНА в целия файл, и затова единственото, в което
   * празната позиция става „0“: тук позициите НЯМАТ свой разделител. Празна т. 16 (код 90) остава
   * ПРАЗНА — „0000“ е попълнена точка, която твърди нула дни в осигуряване.
   */
  public String t16(BgD1Record record) {
    String days = trim(record.getT16Days());
    String legal = trim(record.getT16LegalHours());
    String contracted = trim(record.getT16ContractedHours());
    if (isEmpty(days) && isEmpty(legal) && isEmpty(contracted)) {
      return null;
    }
    String d = isEmpty(days) ? "00" : (days.length() == 1 ? "0" + days : days);
    return d + digit(legal) + digit(contracted);
  }

  private static String digit(String value) {
    if (isEmpty(value)) {
      return "0";
    }
    return value.substring(value.length() - 1);
  }

  /**
   * ПОЛЕ 31 — двете позиции на т. 13, слепени в ЕДИН низ с ширина 4.
   *
   * <p><b>⚠️ ВТОРОТО ПОЛЕ С ФИКСИРАНА ШИРИНА в целия файл, след поле 19.</b> Указанието дава на т.
   * 13 „две позиции“ — брой месеци в отчетния период (02–12) и поредност на месеца (01–12), — а
   * форматът дава на полето {@code Char} с размер РАВЕН НА 4. Тоест позициите тук НЯМАТ свой
   * разделител и празната позиция трябва да стане „00“, за да не се смъкне другата.
   *
   * <p><b>⚠️ И ЗАТОВА ЧАСТИЧНО ПОПЪЛНЕНАТА Т. 13 ИЗЛИЗА, А НЕ СЕ ИЗХВЪРЛЯ.</b> Позиция 1 има
   * автоматичен източник, позиция 2 — не (виж {@code BgD1RecordBuilder#fillSirvCode}). Запис само с
   * позиция 1 дава „0300“: тримесечен период, неизвестен месец. Изхвърлено, полето би премълчало и
   * дължината на периода — а тя Е известна.
   *
   * <p><b>⚠️ ПРАЗНА Т. 13 ИЗЛИЗА „0000“.</b> Доводът, че „0000“ е попълнена точка с нулев отчетен
   * период, се опровергава от две места: заповедта казва за поле 31 „От 0 до 12. ЗАДЪЛЖИТЕЛНО СЕ
   * ПОПЪЛВАТ 4 ПОЗИЦИИ“, а приетата от НАП коригираща Д1 за вид осигурен 01 носи точно {@code
   * "0000"}. Тоест нула месеца е „без сумирано отчитане“, не празна точка. Поле 19 (т. 16) НЕ
   * следва това: там празното остава празно и заличаващият еталон го потвърждава ({@code ""}).
   */
  public String t13(BgD1Record record) {
    String months = trim(record.getT13SirvPeriodMonths());
    String index = trim(record.getT13SirvMonthIndex());
    return twoDigits(months) + twoDigits(index);
  }

  /** Две цифри с водеща нула; празното става „00“ — позицията няма свой разделител. */
  private static String twoDigits(String value) {
    if (isEmpty(value)) {
      return "00";
    }
    String v = value.trim();
    if (v.length() == 1) {
      return "0" + v;
    }
    return v.length() > 2 ? v.substring(v.length() - 2) : v;
  }

  /**
   * {@code Character} поле: в ДВОЙНИ КАВИЧКИ, само ГЛАВНИ букви на кирилица, без кавички и запетаи.
   *
   * <p>⚠️ ЗАПЕТАЯТА И КАВИЧКАТА СЕ МАХАТ, А НЕ СЕ ЕКРАНИРАТ. Форматът не описва екраниране, а
   * разделителят Е запетаята: „ПЕТРОВ, ЖР.“ би добавило поле и разместило всичките 20 след него.
   * Махането губи знак от името; екранирането губи ЦЕЛИЯ ред и всичко след него.
   *
   * <p>⚠️ ГЛАВНИТЕ БУКВИ СЕ НАЛАГАТ ТУК, не на записа: записът носи фамилията както е по документ
   * за самоличност и точно тя се печата на хартиения образец. Форматът иска главни — това е
   * свойство на файла.
   */
  public String chr(String value) {
    String v = trim(value);
    if (v == null) {
      return "\"\"";
    }
    String clean = v.replace("\"", "").replace(",", "").replace("\r", "").replace("\n", "");
    return "\"" + clean.toUpperCase(new java.util.Locale("bg", "BG")) + "\"";
  }

  /**
   * {@code Numeric} поле от низ: празното става „0“.
   *
   * <p>⚠️ БЕЗ КАВИЧКИ, за разлика от {@link #chr}. Числово поле в кавички е низ — НАП го отхвърля,
   * а на екрана двете изглеждат почти еднакво.
   */
  public String num(String value) {
    String v = trim(value);
    return isEmpty(v) ? "0" : v;
  }

  /**
   * {@code Numeric} поле с ВОДЕЩА НУЛА В ЗАПИСА — месец и вид осигурен: във файла БЕЗ нея.
   *
   * <p>Записът пази „09“ и „01“ — така ги вижда човекът на хартията и по тях се сверява ключът на
   * корекцията ({@code BgD1CorrectionRules#keyOf}). Файлът е числов: приетите от НАП Д1 носят „9“ и
   * „1“, а заповедта дава на месеца „От 1 до 12“. Превежда се тук, както кодът корекция.
   */
  public String bare(String value) {
    String v = trim(value);
    if (isEmpty(v)) {
      return "0";
    }
    return v.replaceFirst("^0+(?=\\d)", "");
  }

  /**
   * ЧАСОВЕТЕ (т. 16.7, 16.8) — {@code Numeric <= 3}, ненулевото с ТРИ позиции: „080“.
   *
   * <p>Така ги носи приетата от НАП коригираща Д1. Нулата остава „0“ — пак по еталона (поле 27).
   */
  public String hours(String value) {
    String v = trim(value);
    if (isEmpty(v) || v.matches("0+")) {
      return "0";
    }
    if (v.matches("\\d{1,2}")) {
      return String.format("%03d", Integer.parseInt(v));
    }
    return v;
  }

  /** {@code Numeric} поле от сума: два знака, положително, празното → „0.00“. */
  public String sum(BigDecimal value) {
    return decimal(value);
  }

  /**
   * {@code Numeric} поле от СТАВКА: два знака.
   *
   * <p>⚠️ ОТДЕЛЕН МЕТОД ОТ {@link #sum}, макар днес да прави същото: файлът дава на сумите размер
   * ≤7.2 или ≤9.2, а на ставките ≤5.2. Един метод за двете би пренесъл тавана на сумата върху
   * процента там, където никой няма да го търси.
   */
  public String rate(BigDecimal value) {
    return decimal(value);
  }

  /**
   * ⚠️ НУЛАТА Е „0.00“, НЕ „0“ — и в празните суми и ставки на заличаващия ред. Така ги носят и
   * двата приети от НАП файла (19 полета от 32 до 50); дотук излизаше „0“.
   */
  private static String decimal(BigDecimal value) {
    if (value == null || value.signum() == 0) {
      return "0.00";
    }
    // ⚠️ .abs(): „Всички суми са само положителни“. Отрицателно число тук е дефект по-нагоре, но
    // във файла то би счупило разбора, а не само сметката.
    return value.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static boolean isEmpty(String value) {
    return value == null || value.isEmpty();
  }

  private static String trim(String value) {
    if (value == null) {
      return null;
    }
    String v = value.trim();
    return v.isEmpty() ? null : v;
  }
}
