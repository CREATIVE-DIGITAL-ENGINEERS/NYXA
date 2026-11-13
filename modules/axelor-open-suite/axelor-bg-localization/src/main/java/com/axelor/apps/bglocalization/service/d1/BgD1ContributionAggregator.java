package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ВНОСКИТЕ НА ФИША → ТОЧКИТЕ НА ДЕКЛАРАЦИЯТА: <b>СТАВКИТЕ</b> (проценти) на перата от група 5xx,
 * групирани по {@code BgContributionFund.bgD1Field} и <b>поотделно за всеки платец</b>.
 *
 * <p><b>⚠️ СТАВКИ, НЕ СУМИ — И ТОВА НЕ Е ИЗБОР.</b> Сбор на {@code bgAmount} би дал едно левово
 * число на точка. Файловият формат обявява полета 35–47 за „(процент)“, а указание т. 22.2 иска
 * „размерите на осигурителните вноски, ПООТДЕЛНО за осигурителя и осигуреното лице“ — тоест
 * значение имат и единицата, и разделянето. Левовите суми не се губят: те са предметът на
 * Декларация обр. 6.
 *
 * <p><b>⚠️ СТАВКАТА СЕ ЧЕТЕ ОТ ПЕРОТО, А НЕ СЕ ТЪРСИ ВТОРИ ПЪТ В {@code
 * bg_insurance_contribution}.</b> Заданието казваше „от осигурителната конфигурация“; тук е доводът
 * да е от перото, защото и двете четат ЕДНА И СЪЩА стойност, но по различно надеждни пътища:
 *
 * <ul>
 *   <li><b>Перото вече Я НОСИ.</b> {@code BgContributionLineService#upsert} пише {@code
 *       line.setBgRate(outcome.rate)} — точно ставката, от която е сметната сумата на това перо.
 *       Тоест въпросът „коя ставка“ вече има отговор, записан до самото число.
 *   <li><b>Втора заявка значи ВТОРИ ФИЛТЪР.</b> Изборът на ред минава през вид осигурен, категория
 *       труд, период, кохорта УПФ (WITH/WITHOUT), обхват СОЛ, учителски обхват и код на
 *       обезщетението — цялата верига живее в {@code BgPaySlipService#contributions}. Втори филтър
 *       над същата таблица дава ДРУГ ред за същия фиш; същият довод, с който {@code
 *       BgD1Emitter#insuredTypeFor} преписва филтъра на {@code BgPaySlipService} дословно.
 *   <li><b>Коригиращата Д1 иска ставките, валидни ТОГАВА.</b> 2026 г. има две осигурителни половини
 *       (от 01.08.2026 видове 05 и 24 минават на 6,4 + 1,6). Ретро-копието замразява ставката
 *       ({@code BgPaySlipService} копира {@code bgRate} на делта-реда), а нова заявка с днешна дата
 *       би върнала днешната ставка за минал месец — тихо и правдоподобно.
 * </ul>
 *
 * <p><b>⚠️ СТАВКИТЕ НА РАЗЛИЧНИ ФОНДОВЕ СЕ СЪБИРАТ; СТАВКИТЕ НА РАЗЛИЧНИ ПЛАТЦИ — НЕ.</b> Т. 22
 * иска ЕДИН процент за фондовете на ДОО без ТЗПБ, а те са четири (Пенсии, Пенсии по чл. 69, ОЗМ,
 * Безработица) — сборът им е стойността на полето. Осигурителят и лицето, обратно, имат СВОИ полета
 * (35 и 36) и събирането им е точно грешката, която този клас поправя.
 *
 * <p><b>⚠️ ЛИПСВАЩО ПЕРО ЗНАЧИ ЛИПСВАЩА СТАВКА, И ТОВА НЕ ВИНАГИ Е НУЛА.</b> Резолверът не строи
 * перо при нулева ставка (фонд ГВРС през 2026 г. е точно този случай) — там липсата Е нулата и
 * четенето е вярно. Но перо не се строи и когато сумата се закръгли до 0,00 върху много малка база,
 * а тогава ставката съществува и се губи. Затова {@code BgD1Validator} пита обратното: база в т. 21
 * без ставка в т. 22 е находка, не тишина.
 *
 * <p><b>⚠️ БАЗАТА Е ЧАСТ ОТ КЛЮЧА, НАВСЯКЪДЕ КЪДЕТО ЗДРАВНОТО СЕ СУМИРА.</b> Фонд HEALTH носи в
 * речника точка 22.1 — вярно за НОРМАЛНАТА база и невярно за другите две. Здравно перо с база по
 * ЗЗО чл. 40 ал. 1 т. 5 отива в т. 18, а перо върху „доход само за здравно“ — в т. 28. Групиране
 * само по {@code bgD1Field} би СЛЯЛО трите в т. 22.1: числото щеше да е сборът на три ставки върху
 * три различни бази, тоест ставка, която не е ничия, на точка, която не я иска. Пренасочването
 * живее в {@link #pointOf} — на едно място, за да не може да се пропусне на второ.
 *
 * <p><b>⚠️ ФИЛТЪРЪТ НА КОРЕКЦИЯТА Е ЗАДЪЛЖИТЕЛЕН.</b> Четат се само перата за собствения период
 * ({@link BgPaySlipResolver#isOwnPeriod}). Делта-редът от ретро-корекция носи ставка за ДРУГ месец;
 * тя вече е декларирана с коригиращата Д1 ЗА ОНЗИ МЕСЕЦ. ⚠️ И при ставките ефектът е по-остър,
 * отколкото при сумите: две пера на един фонд със СЪВПАДАЩИ ставки биха дали ДВОЙНАТА ставка —
 * 21,84 вместо 10,92 — тоест число, което не е ничия ставка.
 */
public final class BgD1ContributionAggregator {

  /** Мащабът на ставката: два знака, колкото иска и файлът (≤5.2), и колкото носи речникът. */
  public static final int RATE_SCALE = 2;

  /** Нормалният осигурителен доход (т. 21). Стойността на празната колона. */
  public static final String BASE_STANDARD = "STANDARD";

  /** Осигурителният доход по ЗЗО чл. 40 ал. 1 т. 5 — базата на т. 17, ставката в т. 18. */
  public static final String BASE_ART40_1_5 = "ART40_1_5";

  /** Доходът само за здравно осигуряване — базата на т. 27, ставката в т. 28. */
  public static final String BASE_HEALTH_ONLY = "HEALTH_ONLY";

  /**
   * ТРИТЕ ПОЗНАТИ БАЗИ. ⚠️ Съществува, за да е ПРОВЕРИМО кое е позната стойност.
   *
   * <p>{@link #healthBaseOf} чете празното като {@link #BASE_STANDARD} и връща всичко останало
   * КАКТО Е — тоест погрешно написана база („ART40_15“ без подчертавка) минава оттук и {@link
   * #pointOf} я оставя на т. 22.1 по подразбиране. ЗАГУБА НЯМА, и това е нарочно: тихото падане в
   * 22.1 е по-безопасно от изчезване. Но вноската стои на ГРЕШНА ТОЧКА, а числото изглежда наред от
   * двете страни — Д6 групира по фонд и не забелязва, Д1 показва по-голяма т. 22.1. Затова списъкът
   * е тук и {@code BgD1Validator} го чете.
   */
  public static final java.util.Set<String> KNOWN_BASES =
      Collections.unmodifiableSet(
          new java.util.LinkedHashSet<>(
              Arrays.asList(BASE_STANDARD, BASE_ART40_1_5, BASE_HEALTH_ONLY)));

  private BgD1ContributionAggregator() {}

  /**
   * Ставките по точки за ЕДИН платец: „точка от Д1 → сбор на ставките на перата ѝ“.
   *
   * <p>Връща само точките, за които ИМА пера с ненулева ставка. Липсваща точка значи „този фиш не
   * носи такава вноска от този платец“.
   *
   * @param sign {@link BgPaySlipResolver#CREDIT} за ОСИГУРИТЕЛЯ, {@link BgPaySlipResolver#DEBIT} за
   *     ОСИГУРЕНОТО ЛИЦЕ. ⚠️ Знакът Е платецът и това не е съвпадение: {@code
   *     BgContributionLineService} намира типа перо по ДВОЙКАТА фонд + знак именно защото знакът
   *     носи платеца.
   */
  public static Map<String, BigDecimal> ratesByPoint(List<BgPaySlipLine> lines, String sign) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    if (lines == null || sign == null) {
      return out;
    }
    for (BgPaySlipLine line : lines) {
      if (line == null || !BgPaySlipResolver.isOwnPeriod(line)) {
        continue;
      }
      if (BgPaySlipResolver.groupOf(line) != BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
        continue;
      }
      if (!sign.equals(line.getBgSign())) {
        continue;
      }
      String point = pointOf(line);
      if (point == null || point.isEmpty()) {
        continue;
      }
      // ⚠️ БЕЗ ПРОВЕРКА ЗА null: генерираният getter за BigDecimal връща ZERO за непопълнено поле
      // (виж BgDefaultedGetterTest), тоест условието би било мъртъв код. Нулевата ставка добавя
      // нула — верният принос на перо без ставка.
      BigDecimal rate = line.getBgRate();
      if (rate.signum() == 0) {
        continue;
      }
      out.merge(point, rate, BigDecimal::add);
    }
    // ⚠️ Мащабът се налага НА ИЗХОДА, върху сбора, а не на всяко събираемо. Речникът носи ставките
    // с мащаб 4 („8.2200“), а нито един от 265-те му реда не иска трети знак — тоест закръглянето е
    // ОТРЯЗВАНЕ НА НУЛИ, не промяна на стойност. Направено на всяко събираемо, то би било три
    // закръгляния на едно число, което е различна сметка без причина.
    Map<String, BigDecimal> scaled = new LinkedHashMap<>();
    for (Map.Entry<String, BigDecimal> e : out.entrySet()) {
      scaled.put(e.getKey(), e.getValue().setScale(RATE_SCALE, RoundingMode.HALF_UP));
    }
    return Collections.unmodifiableMap(scaled);
  }

  /**
   * СТАВКАТА НА ЕДНОПЛАТЕЦОВА ТОЧКА — сборът на двете страни за т. 22.2, 23, 25 и 30.
   *
   * <p>⚠️ ЗАЩО СБОР, А НЕ „ставката на осигурителя“. Файлът дава на тези точки ЕДНО поле, а
   * указанията им казват „за сметка на осигурителя/МОРСКОТО ЛИЦЕ“ (т. 22.2 и т. 25). За вид
   * осигурен 92 нулата е от страната на работодателя (КСО чл. 6 ал. 6, чл. 157 ал. 4) — тоест
   * ставката пристига по перото на ЛИЦЕТО. Прочит „винаги осигурителят“ би дал нула за морското
   * лице: единственият случай, за който точката е формулирана с наклонена черта.
   *
   * <p>⚠️ И ЗАТОВА СБОРЪТ НЕ Е ТИХ. По норма точно една от двете страни е ненулева. Ако и двете
   * носят ставка, сборът е число, което не е ничия ставка — {@code BgD1Validator} го обявява.
   */
  public static BigDecimal singlePayerRate(
      Map<String, BigDecimal> employerRates, Map<String, BigDecimal> insuredRates, String point) {
    BigDecimal employer = employerRates == null ? null : employerRates.get(point);
    BigDecimal insured = insuredRates == null ? null : insuredRates.get(point);
    if (employer == null && insured == null) {
      return null;
    }
    return (employer == null ? BigDecimal.ZERO : employer)
        .add(insured == null ? BigDecimal.ZERO : insured);
  }

  /**
   * Точката на едно перо, или {@code null}, ако то не сочи фонд.
   *
   * <p>⚠️ ПЪРВО ПЕРОТО, ПОСЛЕ ТИПЪТ. {@code BgPaySlipLine.bgContributionFund} съществува точно за
   * това и {@code BgContributionLineService#upsert} го попълва при създаването на перото — за да не
   * минава справка по фонд през речника, който междувременно може да се е променил. Типът е
   * РЕЗЕРВА, не равностоен източник: през него минават само пера, създадени преди тази препратка да
   * съществува.
   */
  public static String pointOf(BgPaySlipLine line) {
    if (line == null) {
      return null;
    }
    BgContributionFund fund = line.getBgContributionFund();
    if (fund == null) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      fund = type == null ? null : type.getBgContributionFund();
    }
    String point = fund == null ? null : fund.getBgD1Field();
    if (point == null) {
      return null;
    }
    // ⚠️ ДИСКРИМИНАТОРЪТ ПРЕНАСОЧВА ТОЧКАТА, А НЕ МЕНИ ФОНДА. Речникът дава на фонд HEALTH точка
    // 22.1 — и това е вярно за НОРМАЛНАТА база. Другите две бази носят СВОИ точки, а фондът остава
    // един (НЗОК е един). Тоест пренасочването живее ТУК, а не в bgD1Field на фонда: колоната там
    // е свойство на ФОНДА, а точката зависи и от БАЗАТА.
    if (BgD1Point.T22_1_HEALTH.equals(point)) {
      String base = healthBaseOf(line);
      if (BASE_ART40_1_5.equals(base)) {
        return BgD1Point.T18_HEALTH_ON_T17;
      }
      if (BASE_HEALTH_ONLY.equals(base)) {
        return BgD1Point.T28_HEALTH_ON_T27;
      }
    }
    return point;
  }

  /**
   * БАЗАТА на едно здравно перо. ⚠️ Празното се чете като {@link #BASE_STANDARD}.
   *
   * <p>Прочитът е на ЕДНО място нарочно. Всичките заварени пера носят празна колона, а миграция,
   * която ги допише със „STANDARD“, би пипнала живи фишове, за да каже онова, което празното вече
   * казва. Тоест подразбирането е решение на КОДА и стои тук, а не в схемата.
   */
  public static String healthBaseOf(BgPaySlipLine line) {
    if (line == null) {
      return BASE_STANDARD;
    }
    String kind = line.getBgHealthBaseKind();
    if (kind == null || kind.trim().isEmpty()) {
      return BASE_STANDARD;
    }
    return kind.trim();
  }

  /**
   * СУМИТЕ НА СПЕЦИАЛНИТЕ БАЗИ: „точка → база в левове“ за т. 17 и т. 27.
   *
   * <p><b>⚠️ БАЗАТА СЕ ЧЕТЕ ОТ {@code bgQuantity}, И ТОВА НЕ Е ЗАЕМАНЕ НА КОЛОНА.</b> За перата от
   * група 5xx количеството Е базата: {@code BgContributionLineService#generate} вика {@code
   * BgPaySlipLineOutcome.computed(code, contribution.getAmount(), contribution.getBase(),
   * contribution.getRate())} — вторият аргумент е сумата, третият е количеството, тоест базата.
   * Оттам и защо не се дели: {@code сума × 100 / ставка} е ВТОРА сметка и губи стотинки при
   * закръглена сума.
   *
   * <p><b>⚠️ И БАЗИТЕ НЕ СЕ СЪБИРАТ ПО ПЛАТЦИ.</b> Т. 17 и т. 27 са ЕДНА сума, а не сбор от две:
   * ЗЗО чл. 40 ал. 1 т. 5 дава вноската само на работодателя, а б. „б“ — „ИЗЦЯЛО за сметка на“ един
   * платец. Тоест по норма съществува точно ЕДНО перо на всяка от двете бази. Ако се появят две,
   * базата НЕ се удвоява — взима се първата и {@code BgD1Validator} обявява несъответствието:
   * удвоена база е число, което изглежда като двойно по-висок доход.
   */
  /**
   * БАЗАТА НА Т. 21 — <b>доходът, ВЪРХУ КОЙТО вноските наистина са начислени</b>, или {@code null}.
   *
   * <p><b>УКАЗАНИЕ Т. 21:</b> „Осигурителен доход, върху който СЕ ДЪЛЖАТ осигурителни вноски … —
   * попълва се доходът съгласно чл. 6, ал. 2 и 12 от КСО.“ А <b>КСО чл. 6 ал. 3</b> казва, че
   * вноските се дължат върху брутните възнаграждения, „но върху НЕ ПО-МАЛКО от минималния
   * осигурителен доход … и НЕ ПОВЕЧЕ ОТ МАКСИМАЛНИЯ месечен размер на осигурителния доход“. Тоест
   * т. 21 е базата СЛЕД пода и тавана, а не сборът на начисленията.
   *
   * <p><b>⚠️ ЧЕТЕ СЕ ОТ {@code bgQuantity} НА ПЕРАТА, И ТОВА Е ЕДИНСТВЕНИЯТ НОСИТЕЛ НА КАПНАТАТА
   * БАЗА.</b> Трите правдоподобни източника се разминават измеримо и два от трите са НЕкапнати:
   *
   * <ul>
   *   <li>{@code BgPaySlipResolver#insuranceIncomeBase} — сборът на перата. Собственият му javadoc
   *       казва „ОСИГУРИТЕЛЕН ДОХОД <b>преди под и таван</b>“. Това четеше т. 21 досега;
   *   <li>{@code BgPaySlip.bgInsuranceIncome} — заглавното поле. ⚠️ И ТО НЕ Е КАПНАТО в обичайния
   *       случай: {@code BgPaySlipService#insuranceIncomeHeader} връща именно сбора на перата и
   *       минава през базата на вноската САМО когато лицето е декларирало доход при ДРУГ платец
   *       (координацията по чл. 6 ал. 11). Тоест за едно правоотношение над тавана заглавното поле
   *       е същите 3000,00;
   *   <li><b>{@code bgQuantity} на перо от 5xx</b> — базата, с която вноската Е сметната. {@code
   *       BgContributionLineService#upsert} я пише заедно със сумата и ставката, а тя идва от
   *       {@code BgInsurableIncomeResult#getAmount()} — вече с наложен МОД под и таван.
   * </ul>
   *
   * <p><b>⚠️ САМО ПЕРАТА, КОИТО СТОЯТ ВЪРХУ Т. 21.</b> Указанията казват „върху сумите в т. 21“ за
   * т. 22, 22.1, 22.2, 23, 25 и 26. Извън тях остават т. 18 (базата ѝ е т. 17), т. 28 (базата ѝ е
   * т. 27) и <b>т. 30</b> — ГВРС се определя върху БРУТНАТА РАБОТНА ЗАПЛАТА (т. 29), не върху
   * осигурителния доход. Взето от произволно перо, т. 21 би получила базата на здравното при
   * неплатен отпуск — число от съвсем друг порядък.
   *
   * <p><b>⚠️ {@code putIfAbsent}, НЕ сбор — базата е ЕДНА.</b> Резолверът смята всички редове на
   * една заявка върху една и същa база; шестте точки я носят ШЕСТ пъти. Събрана, т. 21 щеше да е
   * шесткратна.
   *
   * <p>Връща {@code null}, когато НИТО ЕДНО перо не носи база — тоест когато нито една вноска не е
   * установила такава. Нула тук би била твърдение („осигурителен доход нула“), а {@code null} е
   * състояние на фиша и извикващият го обявява.
   */
  public static BigDecimal insuranceIncomeBaseFromLines(List<BgPaySlipLine> lines) {
    if (lines == null) {
      return null;
    }
    for (BgPaySlipLine line : lines) {
      if (line == null || !BgPaySlipResolver.isOwnPeriod(line)) {
        continue;
      }
      if (BgPaySlipResolver.groupOf(line) != BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
        continue;
      }
      if (!T21_DEPENDENT_POINTS.contains(pointOf(line))) {
        continue;
      }
      BigDecimal base = line.getBgQuantity();
      if (base.signum() == 0) {
        continue;
      }
      return base.setScale(2, RoundingMode.HALF_UP);
    }
    return null;
  }

  /**
   * Точките, чиято база Е т. 21 — дословно „върху сумите в т. 21“ от указанията.
   *
   * <p>⚠️ НЕ Е {@code BgD1Point#EMITTED_FROM_FUNDS}, макар да изглежда почти същото: там влиза и т.
   * 30 (ГВРС), а неговата база е т. 29 (брутната работна заплата по НСОРЗ чл. 16 ал. 2). Двете
   * съвпадат при обикновен фиш и се разминават при всеки фиш с обезщетение или с наложен таван —
   * тоест точно при фиша, за който този прочит съществува.
   */
  private static final Set<String> T21_DEPENDENT_POINTS =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(
              Arrays.asList(
                  BgD1Point.T22_DOO,
                  BgD1Point.T22_1_HEALTH,
                  BgD1Point.T22_2_TZPB,
                  BgD1Point.T23_TEACHER_PF,
                  BgD1Point.T25_PPF,
                  BgD1Point.T26_UPF)));

  public static Map<String, BigDecimal> specialBasesByPoint(List<BgPaySlipLine> lines) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    if (lines == null) {
      return out;
    }
    for (BgPaySlipLine line : lines) {
      if (line == null || !BgPaySlipResolver.isOwnPeriod(line)) {
        continue;
      }
      if (BgPaySlipResolver.groupOf(line) != BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
        continue;
      }
      String point = pointOf(line);
      String basePoint = null;
      if (BgD1Point.T18_HEALTH_ON_T17.equals(point)) {
        basePoint = BgD1Point.T17_ART40_1_5_INCOME;
      } else if (BgD1Point.T28_HEALTH_ON_T27.equals(point)) {
        basePoint = BgD1Point.T27_HEALTH_ONLY_INCOME;
      }
      if (basePoint == null) {
        continue;
      }
      BigDecimal base = line.getBgQuantity();
      if (base.signum() == 0) {
        continue;
      }
      // ⚠️ putIfAbsent, НЕ merge: виж javadoc-а — базата е една, не сбор по платци.
      out.putIfAbsent(basePoint, base.setScale(2, RoundingMode.HALF_UP));
    }
    return Collections.unmodifiableMap(out);
  }
}
