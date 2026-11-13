package com.axelor.apps.bglocalization.service.art49;

import com.axelor.apps.bglocalization.db.BgArt73Para6Record;
import com.axelor.apps.bglocalization.service.art73.BgArt73Para6Aggregator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.List;

/**
 * ГОДИШНОТО ПРЕИЗЧИСЛЯВАНЕ ПО ЧЛ. 49 — от редовете на т. 8 до колони 6 и 17–22.
 *
 * <p>Чиста функция: без база, без Guice, по образеца на {@code BgArt73Para6Aggregator}. Входът са
 * ВЕЧЕ ПОСТРОЕНИТЕ записи на Част ІІ — тоест ядрото от Фаза 1 — а не фишовете.
 *
 * <p><b>⚠️ ЗАЩО ОТ ЗАПИСИТЕ, А НЕ ОТ ФИШОВЕТЕ.</b> Колона 5 (осигурителни вноски, ВНЕСЕНИ В
 * ЧУЖБИНА) влиза в годишната основа по чл. 25 ал. 1 дословно, но НЕ идва от фиш — тя се въвежда от
 * човек по документ на чуждестранна осигурителна институция. Смятана от фишовете, годишната основа
 * би пропуснала точно това число и би излязла ПО-ВИСОКА, отколкото законът иска. Смятана от
 * записите, тя чете колона 5 такава, каквато човекът я е попълнил. Същият довод важи и за реда по
 * чл. 49 ал. 2 (доход от друг работодател по служебна бележка по чл. 45): той се добавя като
 * ОТДЕЛЕН запис и оттам влиза в основата, без нито един ред тук да се пипа.
 *
 * <p><b>═══ ФОРМУЛИТЕ, ДОСЛОВНО ОТ ОБРАЗЕЦА ═══</b>
 *
 * <ul>
 *   <li><b>Ред 8.1, к. 6</b> — „Годишна данъчна основа по чл. 25, ал. 1 (сборът от сумите с код 101
 *       в колона 3 – сбора от сумите с код 101 в колона 4 и/или колона 5)“.
 *   <li><b>Ред 8.2, к. 6</b> — „Годишна данъчна основа по чл. 25, ал. 3 (сборът от сумите с код 103
 *       в колона 3 – сбора от сумите с код 103 в колона 4 и/или колона 5 х 10%)“.
 *   <li><b>Ред 8.3, к. 6</b> — „Годишна данъчна основа по чл. 49 (сумата в колона 6 на ред 8.1 +
 *       сумата в колона 6 на ред 8.2)“.
 *   <li><b>К. 17</b> — „сумата в колона 6 на ред 8.3 – сборът от сумите в колони от 8 до 16 на ред
 *       8.3“.
 *   <li><b>К. 18</b> — „колона 17 х 10%“.
 *   <li><b>К. 19</b> — „Разлика между колона 18 и сбора от сумите в колона 7 за доходите с код 101
 *       и 103 (сумата се закръглява към всеки пълен лев)“.
 *   <li><b>К. 22</b> — „сборът от сумите, посочени в колона 7 + колона 20 – колона 21“.
 * </ul>
 *
 * <p><b>⚠️ КОД 102 ОТПАДА ОТ ОСНОВАТА ПО СИЛАТА НА ЧЛ. 49 АЛ. 8, А НЕ ЗАЩОТО ОБРАЗЕЦЪТ ГО
 * ПРОПУСКА.</b> Ал. 8, дословно: „При определянето на годишната данъчна основа и на годишния данък
 * по реда на предходните алинеи НЕ СЕ ВКЛЮЧВАТ доходите от трудови правоотношения по § 1, т. 26,
 * буква „и“ от допълнителните разпоредби.“ Точно затова в образеца колона 6 на ред с код 102 стои
 * „Х“, а формулите на 8.1 и 8.2 броят само кодове 101 и 103. Причината е в чл. 25 ал. 2: основата
 * на такова лице се намалява И с вноските за НЕГОВА сметка — число, което работодателят не знае.
 *
 * <p><b>⚠️ НО КОЛОНА 22 БРОИ И КОД 102, И ТОВА Е РАЗЛИКА, А НЕ НЕПОСЛЕДОВАТЕЛНОСТ.</b> Колона 19
 * казва изрично „за доходите с код 101 и 103“; колона 22 казва само „сборът от сумите, посочени в
 * колона 7“ — без ограничение. Двете изречения са в един и същ абзац на един и същ образец, тоест
 * разликата е написана нарочно: изравняването не пипа данъка по код 102 (лицето го приключва с
 * годишна данъчна декларация), но ОБЩО удържаният данък за трудови доходи включва и него.
 *
 * <p><b>⚠️ ЗАКРЪГЛЯНЕТО НА КОЛОНА 19 Е ОТРЯЗВАНЕ, А НЕ „ДО НАЙ-БЛИЗКОТО“ — И ТОВА НЕ Е В ЧЛ.
 * 49.</b> Чл. 49 няма алинея за закръгляне; търсене за „закръгл“ в целия ЗДДФЛ дава ЕДНО попадение
 * — чл. 48 ал. 8, който е за данъка по ГОДИШНАТА ДАНЪЧНА ДЕКЛАРАЦИЯ, не за преизчисляването от
 * работодателя. Правилото за колона 19 идва от УКАЗАНИЕ т. 7 към образеца и е дадено с два примера:
 * „Ако сумата в колона 19 е 1000,31 лв., се вписва 1000 лв. Ако тази сума е 1000,95 лв., СЪЩО се
 * вписва 1000 лв.“ Вторият пример е единственото, което отличава {@code RoundingMode.DOWN} от
 * {@code HALF_UP} — а думата „закръглява“ подвежда точно към второто.
 *
 * <p><b>⚠️ ОБЛЕКЧЕНИЯТА ВЛИЗАТ ОТВЪН, А НЕ СЕ ЧЕТАТ ТУК.</b> Кои са те и откъде идват стои в {@link
 * BgArt49Reliefs} и {@link BgArt49ReliefEntitlement}; тук е само аритметиката им — таваните по чл.
 * 19 и чл. 22 и ограничението до основата. Разделянето е нарочно: правото на облекчение е норма за
 * ЛИЦЕТО (документи, деца, решение на ТЕЛК), а таванът е дроб от основата и се проверява с числа.
 *
 * <p><b>⚠️ И ПАРИЧНАТА ЕДИНИЦА ВЕЧЕ НЕ Е ЛЕВЪТ.</b> Образецът за 2025 г. казва „пълен лев“, защото
 * тогава левът е бил валутата. От 01.01.2026 г. същият ЗДДФЛ говори за „всяко пълно ЕВРО“ (чл. 48
 * ал. 8, изм. ДВ бр. 70 от 2024 г.) и целият модул смята в евро. Затова тук се закръглява до цяла
 * ПАРИЧНА ЕДИНИЦА — правилото е за мащаба, не за валутата, и не се мени с нея.
 */
public final class BgArt49Recalculation {

  /** Паричен мащаб: две цифри след десетичния знак — „левове и стотинки“ по указание т. 7. */
  public static final int MONEY_SCALE = 2;

  /**
   * ДЕЛЪТ НА ЧЛ. 25 АЛ. 3 — 10 на сто, и това НЕ Е ставка.
   *
   * <p>Чл. 25 ал. 3, дословно: „Годишната данъчна основа за доходите, придобити като морско лице, е
   * 10 на сто от определената по ал. 1 годишна данъчна основа.“ Тоест десетте на сто СВИВАТ
   * ОСНОВАТА, а после върху свитата основа ляга обикновената ставка.
   *
   * <p><b>⚠️ ЗАТОВА НЕ СЕ ЧЕТЕ ОТ {@code INCOME_TAX_RATE_SEAFARER}, ВЪПРЕКИ ЧЕ ЧИСЛАТА СЕ
   * ВРЪЗВАТ.</b> 10 на сто основа × 10 на сто ставка = 1 на сто ефективно — точно ставката по чл.
   * 42 ал. 4 за морско лице, тоест двете норми се съгласуват и това е добрата проверка на
   * изведеното. Но съгласуват се РЕЗУЛТАТИТЕ, не величините: сменена ставката по чл. 42 ал. 4,
   * делът по чл. 25 ал. 3 остава 10 на сто. Прочетени от един параметър, двете биха се сменили
   * заедно и годишната основа на морското лице щеше да последва промяна в чужд член.
   */
  public static final BigDecimal SEAFARER_BASE_FRACTION = new BigDecimal("0.10");

  /** Указание т. 3: „8“ е ЗАЛИЧАВАЩ ред — по него се попълват само т. 1–6, тоест НИТО ЕДНА сума. */
  public static final String CORRECTION_DELETE = "8";

  /**
   * ЧЛ. 19 АЛ. 1 — 10 на сто, И ТО ДВА ПЪТИ ПО ОТДЕЛНО.
   *
   * <p>Дословно: основата се намалява „с направените през годината лични вноски за допълнително
   * доброволно осигуряване в общ размер до 10 на сто …, КАКТО И с внесените през годината лични
   * вноски за доброволно здравно осигуряване и премии/вноски по договори за застраховки „Живот“ в
   * общ размер до 10 на сто“. Две изброявания, всяко със свой таван от 10 на сто — оттам и двете
   * отделни колони 9 и 10 на образеца. Слети в един таван от 10 на сто или в един от 20, и в двата
   * случая някой ще ползва повече или по-малко, отколкото нормата дава.
   */
  public static final BigDecimal ART19_CAP_PERCENT = new BigDecimal("10");

  /** Чл. 22 ал. 1 т. 1 — дарения в полза на лица с код 01: до 5 на сто (колона 12). */
  public static final BigDecimal ART22_CODE01_PERCENT = new BigDecimal("5");

  /** Чл. 22 ал. 1 т. 2 — дарения за култура, код 02: до 15 на сто (колона 13). */
  public static final BigDecimal ART22_CODE02_PERCENT = new BigDecimal("15");

  /** Чл. 22 ал. 1 т. 3 — дарения в полза на лица с код 03: до 50 на сто (колона 14). */
  public static final BigDecimal ART22_CODE03_PERCENT = new BigDecimal("50");

  /**
   * ОБЩИЯТ ТАВАН НА ДАРЕНИЯТА — 65 на сто, и той НЕ Е сборът на трите.
   *
   * <p>5 + 15 + 50 = 70, а указание т. 7 казва: „Сборът от сумите, посочени в колони 12, 13 и 14 на
   * ред 8.3 НЕ МОЖЕ да превишава 65 на сто от сумата, посочена в колона 6 на същия ред.“ Тоест
   * трите тавана поотделно НЕ стигат — трябва и четвърти, общ. Изведен като сбор, той би пуснал 5
   * на сто повече.
   */
  public static final BigDecimal ART22_TOTAL_PERCENT = new BigDecimal("65");

  private BgArt49Recalculation() {}

  /**
   * ПРЕИЗЧИСЛЯВА едно лице от записите му на Част ІІ.
   *
   * <p><b>⚠️ НЕ ПИТА КОЙ Е РАБОТОДАТЕЛЯТ.</b> Правото да се преизчислява се решава ПРЕДИ това от
   * {@link BgArt49MainEmployer}; извикан за лице без основно ТПО, този метод би сметнал вярно
   * числа, които никой няма право да впише. Разделянето е нарочно: смятането е аритметика,
   * правомощието е норма, и слети в едно, второто изчезва при първото преместване на кода.
   *
   * @param rows записите на едно лице — редовете на т. 8 с колони 3, 4, 5 и 7; {@code null} се чете
   *     като „няма редове“
   * @param taxRatePercent ставката в ПРОЦЕНТИ (10 за 10 на сто), прочетена за годината от {@code
   *     BgPayrollParameter.INCOME_TAX_RATE} — не закована тук
   */
  public static BgArt49Result recalculate(
      List<BgArt73Para6Record> rows, BigDecimal taxRatePercent) {
    return recalculate(rows, taxRatePercent, null);
  }

  /**
   * ПРЕИЗЧИСЛЯВА едно лице ЗАЕДНО С ОБЛЕКЧЕНИЯТА ПО ЧЛ. 49 АЛ. 3.
   *
   * <p>⚠️ {@code null} за облекченията НЕ е „нула облекчения на лицето“, а „това повикване не пита
   * за тях“ — двете дават едно и също число, но само едното е твърдение. Твърдението се прави от
   * повикващия, който знае дали е питал.
   *
   * @param reliefs ПОИСКАНОТО; върнатият резултат носи ПРИЛОЖЕНОТО след таваните
   */
  public static BgArt49Result recalculate(
      List<BgArt73Para6Record> rows, BigDecimal taxRatePercent, BgArt49Reliefs reliefs) {
    List<BgArt73Para6Record> lines = rows == null ? Collections.emptyList() : rows;

    // Ред 8.1 — кодове 101. Ред 8.2 — кодове 103, свити на 10 на сто по чл. 25 ал. 3.
    BigDecimal base81 = netOf(lines, BgArt73Para6Aggregator.CODE_EMPLOYMENT);
    BigDecimal base82 =
        scale(netOf(lines, BgArt73Para6Aggregator.CODE_SEAFARER).multiply(SEAFARER_BASE_FRACTION));
    BigDecimal base83 = scale(base81.add(base82));

    // Колона 17 = колона 6 на ред 8.3 − сборът от колони 8 до 16. ⚠️ Таваните се мерят срещу
    // основата ПРЕДИ намаленията — указание т. 7 казва „от сумата в колона 6 на ред 8.3“ за всеки
    // от трите тавана на даренията. Мерени срещу вече намалената основа, те щяха да зависят от реда
    // на прилагане и два еднакви случая биха дали различни числа.
    BgArt49Reliefs applied = applyCaps(reliefs, base83);
    BigDecimal reliefTotal = scale(applied.total());
    BigDecimal taxableBase = scale(base83.subtract(reliefTotal));
    BigDecimal annualTax = scale(taxableBase.multiply(nz(taxRatePercent)).movePointLeft(2));

    // Колона 19 — само кодове 101 и 103, и ОТРЯЗАНА до цяла парична единица.
    BigDecimal advanceForSettlement =
        scale(
            sum(lines, BgArt73Para6Aggregator.CODE_EMPLOYMENT, Column.ADVANCE_TAX)
                .add(sum(lines, BgArt73Para6Aggregator.CODE_SEAFARER, Column.ADVANCE_TAX)));
    BigDecimal settlement = annualTax.subtract(advanceForSettlement).setScale(0, RoundingMode.DOWN);

    BigDecimal toWithhold = settlement.signum() > 0 ? scale(settlement) : scale(BigDecimal.ZERO);
    BigDecimal toRefund =
        settlement.signum() < 0 ? scale(settlement.negate()) : scale(BigDecimal.ZERO);

    // Колона 22 — сборът в колона 7 БЕЗ ограничение по код (виж описанието на класа).
    BigDecimal totalWithheld =
        scale(sum(lines, null, Column.ADVANCE_TAX).add(toWithhold).subtract(toRefund));

    return new BgArt49Result(
        base81,
        base82,
        base83,
        applied,
        reliefTotal,
        taxableBase,
        annualTax,
        advanceForSettlement,
        settlement,
        toWithhold,
        toRefund,
        totalWithheld);
  }

  /**
   * ПОИСКАНОТО → ПРИЛОЖЕНОТО: таваните по чл. 19 и чл. 22 и ограничението до основата.
   *
   * <p><b>⚠️ ПЪРВО ВРАТАТА НА ЧЛ. 49 АЛ. 4, ПОСЛЕ ТАВАНИТЕ.</b> „Алинея 3 се прилага, когато
   * работникът/служителят предостави на работодателя в срок от 30 ноември до 31 декември … следните
   * документи“. Без тях НИТО ЕДНА колона от 8 до 16 не се пълни — не защото лицето няма право, а
   * защото работодателят няма основание да го приложи. Лицето ги ползва с годишна данъчна
   * декларация по чл. 50, и точно затова тук се връща НУЛА, а не се пропуска проверката.
   *
   * <p><b>⚠️ ЧЕТИРИ ТАВАНА ЗА ДАРЕНИЯТА, А НЕ ТРИ.</b> Трите поотделно (5, 15 и 50 на сто) плюс
   * общият от 65 на сто — виж {@link #ART22_TOTAL_PERCENT}.
   *
   * <p><b>⚠️ И ПЕТИ, ОБЩ ЗА ВСИЧКИ ДЕВЕТ: ОСНОВАТА.</b> Чл. 22в ал. 4 и чл. 22г ал. 4 го казват
   * дословно за детските („ползва се ДО РАЗМЕРА на сумата от годишните данъчни основи“), а за
   * останалите го налага самата колона 17: тя е основата МИНУС сбора и отрицателна данъчна основа
   * не съществува.
   *
   * <p><b>⚠️ КОГАТО СБОРЪТ НЕ СЕ ПОБИРА, СЕ РЕЖЕ ПО РЕДА НА КОЛОНИТЕ — И ТОВА НЕ Е ПРОИЗВОЛ.</b>
   * Чл. 21 подрежда облекченията по това ОТ КОЯ ОСНОВА се вадят, а не помежду им; в чл. 49 основата
   * е една и подредба между тях законът не дава. Взет е редът на самия образец (8 → 16) и той има
   * следствие, което го прави за предпочитане пред всеки друг: последни остават колони 15 и 16, а
   * чл. 22в ал. 4 и чл. 22г ал. 4 дават НА ТЯХ изричен изход — превишението може да се ползва от
   * другия родител с декларация по чл. 50. Тоест отрязаното пада точно там, където законът е
   * предвидил лек.
   */
  public static BgArt49Reliefs applyCaps(BgArt49Reliefs requested, BigDecimal base) {
    BgArt49Reliefs out = new BgArt49Reliefs();
    if (requested == null || !requested.documentsProvided) {
      return out;
    }
    out.documentsProvided = true;
    BigDecimal b = scale(base);

    out.disability = scale(nz(requested.disability));
    out.voluntaryPension = capAt(requested.voluntaryPension, percentOf(b, ART19_CAP_PERCENT));
    out.voluntaryHealthLife = capAt(requested.voluntaryHealthLife, percentOf(b, ART19_CAP_PERCENT));
    out.serviceYears = scale(nz(requested.serviceYears));
    out.donationsCode01 = capAt(requested.donationsCode01, percentOf(b, ART22_CODE01_PERCENT));
    out.donationsCode02 = capAt(requested.donationsCode02, percentOf(b, ART22_CODE02_PERCENT));
    out.donationsCode03 = capAt(requested.donationsCode03, percentOf(b, ART22_CODE03_PERCENT));
    out.children = scale(nz(requested.children));
    out.disabledChildren = scale(nz(requested.disabledChildren));

    clampDonations(out, percentOf(b, ART22_TOTAL_PERCENT));
    clampToBase(out, b);
    return out;
  }

  /** Общият таван на колони 12, 13 и 14 — реже се по реда на колоните. */
  private static void clampDonations(BgArt49Reliefs reliefs, BigDecimal cap) {
    if (reliefs.donationsTotal().compareTo(cap) <= 0) {
      return;
    }
    BigDecimal left = cap;
    reliefs.donationsCode01 = capAt(reliefs.donationsCode01, left);
    left = left.subtract(reliefs.donationsCode01);
    reliefs.donationsCode02 = capAt(reliefs.donationsCode02, left);
    left = left.subtract(reliefs.donationsCode02);
    reliefs.donationsCode03 = capAt(reliefs.donationsCode03, left);
  }

  /** Сборът от колони 8 до 16 не надхвърля колона 6 на ред 8.3 — реже се по реда на колоните. */
  private static void clampToBase(BgArt49Reliefs reliefs, BigDecimal base) {
    if (reliefs.total().compareTo(base) <= 0) {
      return;
    }
    BigDecimal left = base;
    reliefs.disability = capAt(reliefs.disability, left);
    left = left.subtract(reliefs.disability);
    reliefs.voluntaryPension = capAt(reliefs.voluntaryPension, left);
    left = left.subtract(reliefs.voluntaryPension);
    reliefs.voluntaryHealthLife = capAt(reliefs.voluntaryHealthLife, left);
    left = left.subtract(reliefs.voluntaryHealthLife);
    reliefs.serviceYears = capAt(reliefs.serviceYears, left);
    left = left.subtract(reliefs.serviceYears);
    reliefs.donationsCode01 = capAt(reliefs.donationsCode01, left);
    left = left.subtract(reliefs.donationsCode01);
    reliefs.donationsCode02 = capAt(reliefs.donationsCode02, left);
    left = left.subtract(reliefs.donationsCode02);
    reliefs.donationsCode03 = capAt(reliefs.donationsCode03, left);
    left = left.subtract(reliefs.donationsCode03);
    reliefs.children = capAt(reliefs.children, left);
    left = left.subtract(reliefs.children);
    reliefs.disabledChildren = capAt(reliefs.disabledChildren, left);
  }

  /** Стойността, но не повече от тавана и не под нулата. */
  private static BigDecimal capAt(BigDecimal value, BigDecimal cap) {
    BigDecimal v = scale(nz(value));
    BigDecimal c = scale(cap);
    if (v.signum() < 0) {
      return scale(BigDecimal.ZERO);
    }
    return v.compareTo(c) > 0 ? c : v;
  }

  /** Процент от основата, закръглен до паричния мащаб. */
  private static BigDecimal percentOf(BigDecimal base, BigDecimal percent) {
    return scale(nz(base).multiply(percent).movePointLeft(2));
  }

  /**
   * „Сборът в колона 3 − сборът в колони 4 и 5“ за един код, но НЕ ПОД НУЛАТА.
   *
   * <p><b>⚠️ ПОДЪТ Е РЕШЕНИЕ, А НЕ ПРЕДПАЗЛИВОСТ.</b> Чл. 25 ал. 1 не пише „но не по-малко от
   * нула“, защото случаят не се получава при нормален фиш: осигуровките за сметка на лицето са част
   * от облагаемия му доход и не могат да го надхвърлят. Отрицателна стойност тук значи или
   * ретро-корекция през граница на година, или сгрешен ръчен запис в колона 5 — и в двата случая
   * отрицателна ГОДИШНА ДАНЪЧНА ОСНОВА е величина, каквато законът не познава. Пропусната надолу,
   * тя би направила колона 18 отрицателна и колона 19 би поискала от лицето да МУ СЕ ВЪРНЕ данък,
   * какъвто то не е плащало.
   */
  private static BigDecimal netOf(List<BgArt73Para6Record> rows, String code) {
    BigDecimal net =
        sum(rows, code, Column.INCOME)
            .subtract(sum(rows, code, Column.INSURANCE))
            .subtract(sum(rows, code, Column.INSURANCE_ABROAD));
    return scale(net.signum() < 0 ? BigDecimal.ZERO : net);
  }

  /** Коя колона се сумира. */
  private enum Column {
    INCOME,
    INSURANCE,
    INSURANCE_ABROAD,
    ADVANCE_TAX
  }

  /**
   * Сборът на една колона за един код — или за ВСИЧКИ кодове, когато кодът е {@code null}.
   *
   * <p>⚠️ Заличаващите редове (код корекция „8“) НЕ се броят. Указание т. 3: при заличаване се
   * попълват само т. 1–6, тоест сумите на такъв ред не са „нула“, а „не се попълва“. Днес те и без
   * това стоят на подразбирането 0 и сборът не би се променил — но щом някой ден заличаващият ред
   * запази старите суми за следа, мълчаливото им броене би удвоило дохода на лицето.
   */
  private static BigDecimal sum(List<BgArt73Para6Record> rows, String code, Column column) {
    BigDecimal total = BigDecimal.ZERO;
    for (BgArt73Para6Record row : rows) {
      if (row == null || CORRECTION_DELETE.equals(row.getCorrectionCode())) {
        continue;
      }
      String rowCode = row.getIncomeType() == null ? null : row.getIncomeType().getCode();
      if (code != null && !code.equals(rowCode)) {
        continue;
      }
      total = total.add(valueOf(row, column));
    }
    return total;
  }

  private static BigDecimal valueOf(BgArt73Para6Record row, Column column) {
    switch (column) {
      case INCOME:
        return nz(row.getIncome());
      case INSURANCE:
        return nz(row.getInsurance());
      case INSURANCE_ABROAD:
        return nz(row.getInsuranceAbroad());
      default:
        return nz(row.getAdvanceTax());
    }
  }

  static BigDecimal scale(BigDecimal value) {
    return nz(value).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
  }

  static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }
}
