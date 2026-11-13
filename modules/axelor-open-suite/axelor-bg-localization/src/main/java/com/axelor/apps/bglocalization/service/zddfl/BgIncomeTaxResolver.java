package com.axelor.apps.bglocalization.service.zddfl;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * ОТ БРУТО ДО ДАНЪК ПО ЗДДФЛ — шестте шаблона, всеки срещу своята норма.
 *
 * <p><b>Чист клас, без база и без Axelor.</b> Правилата се проверяват с числа (виж {@code
 * BgIncomeTaxResolverTest}), а записът се пълни от {@code BgIndividualIncomePaymentService}. Така
 * „1000 при 25 на сто дава 75“ е твърдение, което не иска нито схема, нито екран.
 *
 * <p><b>⚠️ ШАБЛОНЪТ ИЗБИРА ФОРМУЛАТА, СТАВКАТА САМО Я ЗАХРАНВА.</b> Кодът 3073 и кодът 401 носят
 * една и съща авансова ставка — 10 на сто — и въпреки това дават различни числа при едно и също
 * бруто, защото разходите им са различни И защото основите им се четат от различни членове. Логика,
 * водена от ставката, би слепила двата случая.
 *
 * <p><b>⚠️ НАЙ-ВАЖНАТА АСИМЕТРИЯ В ТОЗИ ФАЙЛ: A ВАДИ ОСИГУРОВКИТЕ, B — НЕ.</b> Тя не е избор, а
 * разлика в двата члена и е ПРОЧЕТЕНА, не изведена по симетрия:
 *
 * <ul>
 *   <li>Чл. 43 ал. 1, дословно: авансовият данък по чл. 29 се дължи „върху РАЗЛИКАТА между
 *       облагаемия доход и вноските, които самоосигуряващото се лице е задължено да прави за своя
 *       сметка …, а ако лицето не е самоосигуряващо се — между облагаемия доход и УДЪРЖАНИТЕ
 *       задължителни осигурителни вноски“. Тоест основата при A е бруто − разходи − ЗОВ.
 *   <li>Чл. 44 ал. 4, дословно: когато платецът е предприятие или самоосигуряващо се лице,
 *       „размерът на дължимия авансов данък се определя, като ОБЛАГАЕМИЯТ ДОХОД ПО ЧЛ. 31 се умножи
 *       по данъчна ставка 10 на сто“. Тоест при B платецът НЕ вади осигуровки — ал. 4 преурежда
 *       сметката на ал. 1 именно за случая, в който удържа той.
 * </ul>
 *
 * Изравнени „за последователност“, двете биха дали грешен данък на едната страна при всяко плащане
 * с ненулева колона 12.
 *
 * <p><b>⚠️ ГРАНИЦИ, КОИТО ТОЗИ КЛАС НЕ ПРЕМИНАВА И НЕ СЕ ПРАВИ, ЧЕ ПРЕМИНАВА.</b>
 *
 * <ul>
 *   <li><b>Чл. 43 ал. 2, чл. 44 ал. 2 и чл. 44а ал. 3 — 50 и над 50 на сто намалена
 *       работоспособност.</b> Авансов данък се дължи едва след като облагаемият доход на лицето ОТ
 *       ВСИЧКИ ИЗТОЧНИЦИ от началото на годината надхвърли 7920 лв. Прагът иска съвкупен доход по
 *       всички платци, какъвто този платец няма. Необявен, той би бил тихо надвзет данък; тук е
 *       обявен и остава ръчна корекция на реда.
 *   <li><b>Чл. 44а ал. 1 облага авансово само ПАРИЧНИТЕ доходи по чл. 35.</b> Доход в натура по
 *       същия член не се облага авансово от платеца. Признакът „парично/натура“ не съществува на
 *       реда и не се въвежда тук — шаблон E смята като за паричен доход.
 *   <li><b>Годишното изравняване по чл. 49</b> и справката по чл. 73 са отделни теми (Фаза 3).
 * </ul>
 */
public final class BgIncomeTaxResolver {

  // ═══ ШАБЛОНИТЕ ══════════════════════════════════════════════════════════════
  /** Стопанска дейност и услуги — чл. 29, авансов по чл. 43. */
  public static final String PATTERN_BUSINESS = "A";

  /** Наем и предоставяне за ползване — чл. 31, авансов по чл. 44. */
  public static final String PATTERN_RENT = "B";

  /** Окончателен данък, глава шеста — чл. 37 и чл. 38, ставка по чл. 46. */
  public static final String PATTERN_FINAL = "C";

  /** Прехвърляне на права или имущество — чл. 33, платецът НЕ облага. */
  public static final String PATTERN_TRANSFER = "D";

  /** Други източници — чл. 35, авансов по чл. 44а. */
  public static final String PATTERN_OTHER = "E";

  /** Необлагаем доход — чл. 13, само запис. */
  public static final String PATTERN_EXEMPT = "F";

  /** Трудови правоотношения — чл. 24 и чл. 42. НЕ минават през този регистър. */
  public static final String PATTERN_EMPLOYMENT = "TR";

  // ═══ ВИДОВЕТЕ ДАНЪК ═════════════════════════════════════════════════════════
  public static final String TAX_ADVANCE = "ADVANCE";
  public static final String TAX_FINAL = "FINAL";
  public static final String TAX_NONE = "NONE";

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final int SCALE = 2;

  private BgIncomeTaxResolver() {}

  /** Смята разходите, основата и данъка за едно плащане — или отказва с назована причина. */
  public static BgIncomeTaxResult resolve(BgIncomeTaxRequest in) {
    if (in.taxPattern == null || in.taxPattern.trim().isEmpty()) {
      return BgIncomeTaxResult.refuse(
          "Видът доход няма шаблон на облагане — без него не се знае по кой член се смята.");
    }
    if (in.grossAmount == null) {
      return BgIncomeTaxResult.refuse("Брутният размер не е попълнен.");
    }
    if (in.grossAmount.signum() < 0) {
      return BgIncomeTaxResult.refuse(
          "Брутният размер е отрицателен ("
              + in.grossAmount.toPlainString()
              + ") — изплатен доход не може да е под нула. Заличаване се подава с код корекция „8“.");
    }

    String pattern = in.taxPattern.trim();
    BigDecimal gross = scaled(in.grossAmount);
    BigDecimal insurance = scaled(nvl(in.insuranceAmount));

    switch (pattern) {
      case PATTERN_BUSINESS:
        return business(in, gross, insurance);
      case PATTERN_RENT:
        return rent(in, gross);
      case PATTERN_FINAL:
        return finalTax(in, gross);
      case PATTERN_OTHER:
        return otherSources(in, gross);
      case PATTERN_TRANSFER:
        return transfer(gross);
      case PATTERN_EXEMPT:
        return exempt();
      case PATTERN_EMPLOYMENT:
        // ⚠️ Не е дефект и не е „още неподдържано“: трудовият доход СЕ ОТЧИТА, но по друг път.
        // Справката по чл. 73 ал. 6 се събира ПРЯКО от фишовете (BgArt73Para6Aggregator), защото
        // фишът вече носи и трите числа на образеца. Ред тук би бил ВТОРО им копие — втора истина,
        // която утре се разминава с фиша.
        return BgIncomeTaxResult.refuse(
            "Трудов доход (шаблон „Тр“) не се въвежда в този регистър. Той влиза в справката по"
                + " чл. 73, ал. 6 направо от фишовете; регистърът е за ИЗВЪНТРУДОВИТЕ доходи.");
      default:
        return BgIncomeTaxResult.refuse("Непознат шаблон на облагане: „" + pattern + "“.");
    }
  }

  // ═══ A · ЧЛ. 29 — стопанска дейност и услуги ════════════════════════════════

  /**
   * Разходи по чл. 29 ал. 1 (60/40/25), основа по чл. 43 ал. 1 (МИНУС осигуровките), данък по чл.
   * 43 ал. 3 — и изключението на ал. 5 и 6 за декларирало СОЛ.
   */
  private static BgIncomeTaxResult business(
      BgIncomeTaxRequest in, BigDecimal gross, BigDecimal insurance) {
    String missing = requireRate(in.expenseRate, "нормативните разходи", "чл. 29, ал. 1");
    if (missing != null) {
      return BgIncomeTaxResult.refuse(missing);
    }
    missing = requireRate(in.advanceTaxRate, "авансовата ставка", "чл. 43, ал. 3");
    if (missing != null) {
      return BgIncomeTaxResult.refuse(missing);
    }

    BigDecimal expense = percentOf(gross, in.expenseRate);
    // ⚠️ Клампва се на нула: ЗОВ могат да надхвърлят облагаемия доход при малко плащане към лице с
    // висок осигурителен доход. Отрицателна основа не е „данък за връщане“ — тя е нула основа, а
    // разликата се урежда в годишната данъчна основа на лицето.
    BigDecimal base = max0(gross.subtract(expense).subtract(insurance));

    if (in.selfInsuredDeclared) {
      // Чл. 43 ал. 5 и 6 — платецът НЕ удържа, но редът се записва с бруто в колона 11.
      return BgIncomeTaxResult.of(
          expense,
          base,
          zero(),
          TAX_NONE,
          "Данък не се удържа: получателят е самоосигуряващо се лице и е декларирал писмено по"
              + " чл. 43, ал. 5, че ще внася данъка сам (чл. 43, ал. 6). Основата е показана за"
              + " сведение — редът влиза в справката по чл. 73, ал. 1 с брутото в колона 11.");
    }
    if (!in.withheldByPayer) {
      return BgIncomeTaxResult.of(
          expense,
          base,
          zero(),
          TAX_NONE,
          "Кодът е заведен като „данъкът не се удържа от платеца“ — виж чл. 43, ал. 6.");
    }
    return BgIncomeTaxResult.of(
        expense, base, percentOf(base, in.advanceTaxRate), TAX_ADVANCE, null);
  }

  // ═══ B · ЧЛ. 31 — наем ══════════════════════════════════════════════════════

  /**
   * Разходи 10 на сто по чл. 31 ал. 1, основа = облагаемият доход по чл. 31, данък по чл. 44 ал. 4.
   *
   * <p>⚠️ БЕЗ приспадане на осигуровки — виж класовия коментар. И БЕЗ изключението за СОЛ: чл. 44
   * няма алинея, съответна на чл. 43 ал. 5, тоест самоосигуряващ се наемодател НЕ е изключение и
   * платецът му удържа като на всеки друг.
   */
  private static BgIncomeTaxResult rent(BgIncomeTaxRequest in, BigDecimal gross) {
    String missing = requireRate(in.expenseRate, "нормативните разходи", "чл. 31, ал. 1");
    if (missing != null) {
      return BgIncomeTaxResult.refuse(missing);
    }
    missing = requireRate(in.advanceTaxRate, "авансовата ставка", "чл. 44, ал. 4");
    if (missing != null) {
      return BgIncomeTaxResult.refuse(missing);
    }

    BigDecimal expense = percentOf(gross, in.expenseRate);
    BigDecimal base = max0(gross.subtract(expense));

    if (!in.withheldByPayer) {
      return BgIncomeTaxResult.of(
          expense,
          base,
          zero(),
          TAX_NONE,
          "Кодът е заведен като „данъкът не се удържа от платеца“ (чл. 44, ал. 4 се прилага само"
              + " когато платецът е предприятие или самоосигуряващо се лице).");
    }
    return BgIncomeTaxResult.of(
        expense, base, percentOf(base, in.advanceTaxRate), TAX_ADVANCE, null);
  }

  // ═══ C · ЧЛ. 37 и ЧЛ. 38 — окончателен данък ════════════════════════════════

  /**
   * Основата е БРУТНАТА сума (чл. 38 говори за „брутната сума на облагаемите доходи“), ставката е
   * по чл. 46 и идва от каталога — 10, 7 или 5 на сто.
   */
  private static BgIncomeTaxResult finalTax(BgIncomeTaxRequest in, BigDecimal gross) {
    String missing = requireRate(in.finalTaxRate, "ставката на окончателния данък", "чл. 46");
    if (missing != null) {
      return BgIncomeTaxResult.refuse(missing);
    }
    // ⚠️ Разходи НЯМА и това не е пропуск: глава шеста облага брутната сума. Нормативните разходи
    // по чл. 29 и чл. 31 са институт на ОБЛАГАЕМИЯ доход, а окончателният данък не минава през
    // него.
    if (!in.withheldByPayer) {
      return BgIncomeTaxResult.of(
          zero(),
          gross,
          zero(),
          TAX_NONE,
          "Кодът е заведен като „данъкът не се удържа от платеца“.");
    }
    return BgIncomeTaxResult.of(zero(), gross, percentOf(gross, in.finalTaxRate), TAX_FINAL, null);
  }

  // ═══ E · ЧЛ. 35 — други източници ═══════════════════════════════════════════

  /**
   * Чл. 35: „Облагаемият доход е БРУТНАТА СУМА на облагаемите доходи“ — тоест нормативни разходи
   * няма. Данъкът е по чл. 44а ал. 1: облагаемият доход по чл. 35 × 10 на сто, без приспадане на
   * осигуровки.
   */
  private static BgIncomeTaxResult otherSources(BgIncomeTaxRequest in, BigDecimal gross) {
    String missing = requireRate(in.advanceTaxRate, "авансовата ставка", "чл. 44а, ал. 1");
    if (missing != null) {
      return BgIncomeTaxResult.refuse(missing);
    }
    if (!in.withheldByPayer) {
      return BgIncomeTaxResult.of(
          zero(),
          gross,
          zero(),
          TAX_NONE,
          "Кодът е заведен като „данъкът не се удържа от платеца“ (чл. 44а, ал. 1 иска платецът да"
              + " е предприятие или самоосигуряващо се лице).");
    }
    return BgIncomeTaxResult.of(
        zero(), gross, percentOf(gross, in.advanceTaxRate), TAX_ADVANCE, null);
  }

  // ═══ D · ЧЛ. 33 — прехвърляне на права или имущество ════════════════════════

  /**
   * Платецът записва БРУТО и НЕ облага.
   *
   * <p>⚠️ Основата е нула, защото НЕ Е ИЗВЕСТНА, а не защото е нула. Чл. 33 облага положителната
   * разлика между продажната цена и ЦЕНАТА НА ПРИДОБИВАНЕ (ал. 1 и ал. 3), намалена с 10 на сто
   * разходи; цената на придобиване е факт за ЛИЦЕТО и платецът я няма. Затова 10-те на сто на чл.
   * 33 не са заведени като expenseRate в каталога — приложени върху бруто, те биха дали „бруто ×
   * 0,9“: правдоподобно число, което не е основата на нито една сделка.
   */
  private static BgIncomeTaxResult transfer(BigDecimal gross) {
    return BgIncomeTaxResult.of(
        zero(),
        zero(),
        zero(),
        TAX_NONE,
        "Доход по чл. 33: платецът не удържа данък. Записва се брутният размер (колона 11);"
            + " основата иска цената на придобиване, която се знае от лицето, и данъкът се определя"
            + " в неговата годишна данъчна декларация. Нулевата основа тук значи „не се определя от"
            + " платеца“, а не „сделка без печалба“.");
  }

  // ═══ F · ЧЛ. 13 — необлагаем доход ══════════════════════════════════════════

  /** Необлагаем доход: няма основа и няма данък — остава само редът в справката. */
  private static BgIncomeTaxResult exempt() {
    return BgIncomeTaxResult.of(
        zero(),
        zero(),
        zero(),
        TAX_NONE,
        "Необлагаем доход по чл. 13 — данък не се дължи. Редът се записва, защото справката по"
            + " чл. 73, ал. 1 иска и необлагаемите доходи.");
  }

  // ═══ СМЕТКАТА ═══════════════════════════════════════════════════════════════

  /** {@code сума × процент / 100}, закръглено до стотинка нагоре при равни половинки. */
  private static BigDecimal percentOf(BigDecimal amount, BigDecimal rate) {
    return amount.multiply(rate).divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Нулата не е ставка.
   *
   * <p>⚠️ Каталогът днес пълни и трите ставки за всеки шаблон, който ги ползва — 21 кода A с
   * авансови 10 и разходи 25/40/60, 3 кода B с 10 и 10, 22 кода C с 10, 7 или 5. Тоест тази
   * проверка НЕ пада на заварените данни; тя пази срещу нов ред в CSV-то с празна клетка, който
   * иначе би дал данък нула и щеше да мине за законен резултат.
   */
  private static String requireRate(BigDecimal rate, String what, String article) {
    if (rate == null || rate.signum() == 0) {
      return "Видът доход не носи "
          + what
          + " ("
          + article
          + ") — това е липсваща стойност, а не нула. Проверете реда в номенклатурата на видовете"
          + " доходи за датата на плащането.";
    }
    if (rate.signum() < 0) {
      return "Отрицателна ставка за " + what + " (" + article + "): " + rate.toPlainString();
    }
    return null;
  }

  private static BigDecimal max0(BigDecimal v) {
    return v.signum() < 0 ? zero() : v;
  }

  private static BigDecimal zero() {
    return BigDecimal.ZERO.setScale(SCALE);
  }

  private static BigDecimal nvl(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }

  private static BigDecimal scaled(BigDecimal v) {
    return v.setScale(SCALE, RoundingMode.HALF_UP);
  }
}
