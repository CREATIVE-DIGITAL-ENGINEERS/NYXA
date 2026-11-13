package com.axelor.apps.bglocalization.service.d1.adapter;

import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.field;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.formatAmount;

import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.service.d1.BgD1Point;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ПОЛЕ → НИЗ за един запис от Декларация обр. № 1 — по образеца на {@code BgVatDeclarationAdapter}.
 *
 * <p><b>⚠️ ДОТУК И НИ КРАЧКА ПОВЕЧЕ.</b> ДДС-адаптерът стига до РЕД с фиксирана ширина (590 знака,
 * подредба по Приложение № 12 на ППЗДДС). Този спира на СТОЙНОСТИТЕ и това не е недовършена работа,
 * а границата на онова, което е известно:
 *
 * <ul>
 *   <li><b>Стойността</b> на всяко поле я дава НАРЕДБАТА — „две позиции“, „четири позиции“, „буква
 *       К“, суми в левове и стотинки. Тя е тук и е проверима срещу Приложение № 1.
 *   <li><b>Подредбата и ширините</b> ги дава ЗАПОВЕД на изпълнителния директор на НАП по <b>чл. 8
 *       ал. 5</b>. Тя не е в наредбата, не е в репото и не е в бъндъла на Declarations API — там
 *       съдържанието на файла е непрозрачен base64 блоб с {@code fileType: "txt"}.
 * </ul>
 *
 * <p>Оттам и формата на изхода: <b>карта „точка → низ“</b>, а не ред. Карта не се преструва, че
 * знае подредба. Ред с измислена подредба щеше да изглежда като готов формат и да бъде подаден.
 *
 * <p><b>Какво остава, когато заповедта дойде.</b> Малко: нов {@code BgD1FileAdapter}, който взима
 * тази карта, подрежда я и я подплънва през вече наличните {@code BgVatFieldFormatter#padRight} /
 * {@code padLeft}, и {@code BgVatFileWriter} (CP1251 + CRLF, без BOM) я записва. Двата се
 * ПРЕИЗПОЛЗВАТ, не се копират — тук те не се пипат изобщо.
 *
 * <p><b>⚠️ И ЗАЩО ВЪПРЕКИ ТОВА СЪЩЕСТВУВА ДНЕС.</b> Защото превръщането „число → низ“ вече има
 * правила, които наредбата ДАВА: месецът е в две позиции с водеща нула, сумите са с два знака,
 * свободният текст не бива да носи CR/LF/TAB. Ако това чака заповедта, то ще бъде написано набързо
 * заедно с нея — а тогава ще изглежда като част от формата и никой няма да го свери срещу
 * Приложение № 1.
 */
@Singleton
public class BgD1RecordAdapter {

  /**
   * Наставката на половината „А“ (осигурител) — интервал и <b>КИРИЛСКО</b> „А“ (U+0410).
   *
   * <p>⚠️ Латинското „A“ (U+0041) се изписва еднакво и сравнението {@code "22 A".equals("22 А")} е
   * НЕВЯРНО. Същият капан, заради който т. 31а живее като именувана константа в {@code BgD1Point}.
   */
  public static final String SUFFIX_EMPLOYER = " \u0410";

  /** Наставката на половината „Б“ (осигурено лице) — КИРИЛСКО „Б“ (U+0411). */
  public static final String SUFFIX_INSURED = " \u0411";

  /**
   * Стойностите на записа като низове, ключирани по НОМЕР НА ТОЧКА от Приложение № 1.
   *
   * <p>⚠️ Празните точки ОСТАВАТ в картата, с празен низ. Липсващ ключ и празна стойност са
   * различни твърдения: първото значи „тази точка не съществува за този запис“, второто —
   * „съществува и е празна“, което за код 28 и код 90 е самото правило. Карта, която мълчи за
   * празните, не може да бъде сверена срещу списък.
   */
  public Map<String, String> toFields(BgD1Record record) {
    Map<String, String> out = new LinkedHashMap<>();
    if (record == null) {
      return out;
    }

    out.put("1", field(record.getT1CorrectionCode()));
    out.put("2", month(record.getT2Month()));
    out.put("3", field(record.getT3Year()));
    out.put("4", field(record.getT4ObligedPersonCode()));
    out.put("5", field(record.getT5PersonalId()));
    out.put("5.1", field(record.getT51PersonalIdCode()));
    out.put("6", field(record.getT6Surname()));
    out.put("7", field(record.getT7Initials()));
    out.put("12", field(record.getT12InsuredTypeCode()));
    out.put("12.1", field(record.getT121QualificationGroup()));
    out.put("12.2", field(record.getT122ActivityCode()));
    out.put("12.3", field(record.getT123ActivityRow()));

    // ⚠️ ПЕТТЕ ДВОЙКИ ИЗЛИЗАТ ВСИЧКИ, включително празните, и ключът носи позицията: „14 (2)“.
    // Полета 9–18 са десет и сериализаторът ги иска десет — липсващ ключ би преместил всяка
    // следваща стойност едно поле наляво, тоест „последен ден 15“ би станало „възникване 15“.
    out.put("14 (1)", day(record.getT14Day1()));
    out.put("15 (1)", day(record.getT15Day1()));
    out.put("14 (2)", day(record.getT14Day2()));
    out.put("15 (2)", day(record.getT15Day2()));
    out.put("14 (3)", day(record.getT14Day3()));
    out.put("15 (3)", day(record.getT15Day3()));
    out.put("14 (4)", day(record.getT14Day4()));
    out.put("15 (4)", day(record.getT15Day4()));
    out.put("14 (5)", day(record.getT14Day5()));
    out.put("15 (5)", day(record.getT15Day5()));

    // ⚠️ ПОЛЕ 19 Е ЕДНО ПОЛЕ ОТ ЧЕТИРИ ПОЗИЦИИ, и слепването става ТУК, защото е ФОРМА, не смисъл.
    // Записът пази четирите числа поотделно — заб. 1 иска позиции 1–2 да са сборът на кофите, а
    // сбор не се сверява срещу слепен низ. Тук те стават „2188“: 21 дни, 8 часа по закон, 8 по
    // договор. Прочетено като число, същото поле значи две хиляди сто осемдесет и осем дни.
    out.put("16", t16(record));
    out.put("16.1", count(record.getT161WorkedDays()));
    out.put("16.2", count(record.getT162SickDays()));
    out.put("16.3", count(record.getT163ChildCareDays()));
    out.put("16.4", count(record.getT164NoContributionDays()));
    out.put("16.5", count(record.getT165UnpaidLeaveDays()));
    out.put(BgD1Point.T16_A_EMPLOYER_SICK, count(record.getT16aEmployerSickDays()));
    out.put("16.7", count(record.getT167WorkedHours()));
    out.put("16.8", count(record.getT168OvertimeHours()));

    // ⚠️ БАЗАТА Е СУМА, СТАВКАТА Е ПРОЦЕНТ — и на двете двойки. Едно форматиране за двете би
    // пренесло тавана на сумата (≤7.2) върху процента (≤5.2) там, където никой няма да го търси.
    out.put(BgD1Point.T17_ART40_1_5_INCOME, amount(record.getT17Art4015Income()));
    out.put(BgD1Point.T18_HEALTH_ON_T17, rate(record.getT18HealthRateOnT17()));

    out.put(BgD1Point.T21_INSURANCE_INCOME, amount(record.getT21InsuranceIncome()));

    // ⚠️ ТРИТЕ ТОЧКИ С ДВЕ ПОЛОВИНИ носят ключове „22 А“ и „22 Б“, с ИНТЕРВАЛ и КИРИЛСКА буква.
    // Наредбата номерира точката, файлът — полето; двойката А/Б е единственото място, където двете
    // се разминават. Ключ „22“ за две различни стойности би загубил едната мълчаливо.
    out.put(BgD1Point.T22_DOO + SUFFIX_EMPLOYER, rate(record.getT22DooRateEmployer()));
    out.put(BgD1Point.T22_DOO + SUFFIX_INSURED, rate(record.getT22DooRateInsured()));
    out.put(BgD1Point.T22_1_HEALTH + SUFFIX_EMPLOYER, rate(record.getT221HealthRateEmployer()));
    out.put(BgD1Point.T22_1_HEALTH + SUFFIX_INSURED, rate(record.getT221HealthRateInsured()));
    out.put(BgD1Point.T22_2_TZPB, rate(record.getT222TzpbRate()));
    out.put(BgD1Point.T23_TEACHER_PF, rate(record.getT23TeacherPfRate()));
    out.put(BgD1Point.T25_PPF, rate(record.getT25PpfRate()));
    out.put(BgD1Point.T26_UPF + SUFFIX_EMPLOYER, rate(record.getT26UpfRateEmployer()));
    out.put(BgD1Point.T26_UPF + SUFFIX_INSURED, rate(record.getT26UpfRateInsured()));
    out.put(BgD1Point.T27_HEALTH_ONLY_INCOME, amount(record.getT27HealthOnlyIncome()));
    out.put(BgD1Point.T28_HEALTH_ON_T27, rate(record.getT28HealthRateOnT27()));
    out.put(BgD1Point.T29_GROSS_WAGE, amount(record.getT29GrossWage()));
    out.put(BgD1Point.T30_GVRS, rate(record.getT30GvrsRate()));
    out.put(BgD1Point.T31_TAXABLE_INCOME, amount(record.getT31TaxableIncome()));
    out.put(BgD1Point.T31A_TAX, amount(record.getT31aTax()));
    out.put("34", amount(record.getT34NetPay()));

    return Collections.unmodifiableMap(out);
  }

  /**
   * Месецът в ДВЕ позиции — „попълва се в две позиции месецът, за който се отнасят данните“.
   *
   * <p>⚠️ Подплънката е тук, а не при построяването: записът пази смисъла, адаптерът — формата. Ако
   * някой ден т. 2 се въведе на ръка като „5“, тя пак излиза „05“.
   */
  private static String month(String value) {
    String v = field(value);
    return v.isEmpty() ? "" : (v.length() == 1 ? "0" + v : v);
  }

  /**
   * Ден в ДВЕ ПОЗИЦИИ. Празният ден остава ПРАЗЕН НИЗ тук, не „0“.
   *
   * <p>⚠️ Разликата спрямо сумите е нарочна и е ГРАНИЦА МЕЖДУ ДВА СЛОЯ. Файловият формат иска
   * „Numeric полета без стойност → 0“, но това е правило на СЕРИАЛИЗАЦИЯТА, не на записа: тази
   * карта се чете и от печатния образец и от човек, а „ден 0“ там е низ, който не значи нищо.
   * Нулата се слага, когато редът се сглобява (BgD1FileAdapter), и там правилото е на едно място за
   * всичките 54 полета — вместо да е разпиляно по вида на всяка точка.
   */
  private static String day(String value) {
    return month(value);
  }

  /**
   * ПОЛЕ 19 — четирите позиции на т. 16, слепени: дни (2) + законоустановени часове (1) +
   * договорени часове (1).
   *
   * <p>⚠️ ПРАЗНАТА Т. 16 ДАВА ПРАЗЕН НИЗ, А НЕ „0000“, и разликата е самото правило за код 90:
   * „когато в т. 12 е попълнен код 90, т. 16 НЕ се попълва“. „0000“ е ПОПЪЛНЕНА точка, която твърди
   * нула дни в осигуряване — валидно число, което НАП приема, и точно затова грешката е скъпа.
   *
   * <p>⚠️ Часовете излизат с ЕДНА цифра, защото позициите 3 и 4 са по един знак. Работно време над
   * 9 часа не се вмества в полето — но и не съществува: чл. 136 ал. 3 КТ дава максимум 8 часа
   * нормална продължителност, а удълженото по чл. 136а е до 10 и НЕ е „законоустановено за
   * длъжността“. Затова тук се реже до един знак, а несъответствието се вижда като находка на
   * валидатора, не се изглажда.
   */
  private static String t16(BgD1Record record) {
    String days = field(record.getT16Days());
    String legal = field(record.getT16LegalHours());
    String contracted = field(record.getT16ContractedHours());
    if (days.isEmpty() && legal.isEmpty() && contracted.isEmpty()) {
      return "";
    }
    return (days.length() == 1 ? "0" + days : days) + oneDigit(legal) + oneDigit(contracted);
  }

  /**
   * Часовете в ЕДНА позиция.
   *
   * <p>⚠️ Празното дава „0“ ТУК, а не остава празно, защото полето е с ФИКСИРАНА ширина 4: празна
   * позиция би скъсила низа и следващото поле щеше да се прочете едно наляво. Това е единственото
   * място в адаптера, където празното става нула — защото е единственото поле, чиито позиции нямат
   * свой разделител.
   */
  private static String oneDigit(String value) {
    String v = field(value);
    if (v.isEmpty()) {
      return "0";
    }
    return v.length() == 1 ? v : v.substring(v.length() - 1);
  }

  /**
   * Брой (дни или часове) като низ. Празното остава ПРАЗНО, не „0“ — по същия довод като {@link
   * #day}: нулата е правило на сериализацията, не на записа.
   */
  private static String count(String value) {
    return field(value);
  }

  /**
   * Сума с два знака.
   *
   * <p>⚠️ Празната вноска излиза „0.00“, не празен низ, и това е РАЗЛИКА спрямо текстовите полета.
   * Числово поле в декларацията винаги носи число; празнотата му се изразява с нула. Обратното —
   * празен низ на мястото на сума — е дефект на формата, а не липса на данни.
   *
   * <p>⚠️ Десетичният разделител е ТОЧКА, както го дава {@code BgVatFieldFormatter#formatAmount} и
   * както е във всички файлове към НАП, които този модул вече произвежда. Ако заповедта по чл. 8
   * ал. 5 поиска запетая, смяната е на ЕДНО място и е видима.
   */
  private static String amount(BigDecimal value) {
    return formatAmount(value);
  }

  /**
   * СТАВКА с два знака — същата форма като сумата, различен смисъл.
   *
   * <p>⚠️ ОТДЕЛЕН МЕТОД, МАКАР ДНЕС ДА ПРАВИ СЪЩОТО, и това не е дублет. Файлът дава на сумите
   * размер ≤7.2 или ≤9.2, а на ставките ≤5.2 — тоест двете форми вече се различават по таван и ще
   * се разминат напълно, когато сериализаторът наложи ширини. Един метод за двете би пренесъл
   * ширината на сумата върху процента там, където никой няма да я търси.
   *
   * <p>⚠️ И празната ставка излиза „0.00“, не празен низ: „Numeric полета без стойност → 0“.
   */
  private static String rate(BigDecimal value) {
    return formatAmount(value);
  }
}
