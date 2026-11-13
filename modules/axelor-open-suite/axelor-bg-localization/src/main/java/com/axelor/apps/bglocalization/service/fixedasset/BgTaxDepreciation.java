package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Аритметиката на данъчния амортизационен план (ДАП) по ЗКПО, като чиста функция.
 *
 * <p>Изнесена от {@link BgFixedAssetLineTaxComputationServiceImpl} нарочно: модулът няма
 * mock-подпора в тестовете, тъй че правило, което иска свидетел с ЧИСЛА, трябва да е достижимо без
 * Guice, без база и без {@code FixedAsset} с половин дузина запълнени полета.
 *
 * <p><b>Защо норма, а не брой вноски.</b> Базовият модел на Axelor мери плана в БРОЙ периоди и дели
 * {@code 1/N} ({@code AbstractFixedAssetLineComputationServiceImpl.computeDepreciationRate}). Чл.
 * 55 ал. 2 ЗКПО обаче дава ГОДИШЕН ПРОЦЕНТ и той е ТАВАН — „не може да превишава“. За кат. II (30%)
 * точният срок е 3⅓ години и с цели ГОДИШНИ вноски излиза или 25% (4 вноски, подбива признатия
 * разход), или 33,33% (3 вноски, НАДХВЪРЛЯ тавана и е данъчна грешка). Затова броят периоди се
 * извежда от нормата, а не обратното.
 *
 * <p><b>Защо в месеци.</b> Чл. 55 ал. 1 гледа годишна норма, но чл. 58 ал. 1 пуска амортизацията от
 * МЕСЕЦ, а чл. 59 я спира по МЕСЕЦИ — тоест естествената стъпка на ДАП-а е месецът. И точно в
 * месеци всяка норма по чл. 55 ал. 2 излиза ЦЯЛО число периоди:
 *
 * <pre>
 *   4% → 300 мес.  10% → 120 мес.  15% → 80 мес.  25% → 48 мес.
 *  30% →  40 мес.  50% →  24 мес. 100% → 12 мес.
 * </pre>
 *
 * <p>С месечна стъпка и норма 30% върху ДАС 10 000 планът е 250 €/месец и годишните суми са 3 000 /
 * 3 000 / 3 000 / 1 000 — последният период е остатъкът, защото генераторът в {@code
 * FixedAssetLineGenerationServiceImpl} върти, докато данъчната стойност стигне нула ({@code while
 * (accountingValue.signum() != 0)}), а {@code computeDepreciation} никога не дава повече от
 * оставащата стойност.
 */
public final class BgTaxDepreciation {

  /**
   * 100% × 12 месеца. Делителят, който превръща годишна норма в брой месечни периоди: при норма 30
   * → 1200/30 = 40 месеца.
   */
  private static final BigDecimal PERCENT_MONTHS = BigDecimal.valueOf(1200);

  /**
   * Мащабът на междинните деления. Съвпада с {@code FixedAssetServiceImpl.CALCULATION_SCALE}, но е
   * преписан, защото там е {@code protected} и не се вижда извън пакета на account.
   */
  public static final int CALCULATION_SCALE = 20;

  private BgTaxDepreciation() {}

  /**
   * Годишната данъчна норма (ГДАН), която важи за този актив: неговата собствена, а при празна —
   * тази на категорията му.
   *
   * <p><b>Защо с връщане към категорията, а не само от актива.</b> Планът се строи и по пътища,
   * които не минават през нито един запис: {@code FixedAssetGenerationServiceImpl} вика {@code
   * generateAndComputeLines(fixedAsset)} ПРЕДИ {@code fixedAssetRepo.save(...)}, тоест активът,
   * заведен от фактура, стига до изчислението с празна своя норма. Четенето от категорията прави
   * ДАП-а верен без да зависи от ред на записване.
   *
   * <p>Празната норма на актива значи „както казва категорията“, а не „нула“: генерираният getter
   * на BigDecimal връща ZERO и за незададена колона (виж {@code BgDefaultedGetterTest}), тъй че
   * двете стойности пристигат като едно и също число и разликата се чете само през {@code
   * signum()}.
   */
  public static BigDecimal effectiveRate(FixedAsset fixedAsset) {
    if (fixedAsset == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal own = fixedAsset.getBgTaxDepreciationRate();
    if (own.signum() != 0) {
      return own;
    }
    return fixedAsset.getFixedAssetCategory() == null
        ? BigDecimal.ZERO
        : fixedAsset.getFixedAssetCategory().getBgTaxDepreciationRate();
  }

  /** Носи ли активът (или категорията му) данъчна норма, тоест иска ли се ДАП по ЗКПО. */
  public static boolean hasTaxRate(FixedAsset fixedAsset) {
    return effectiveRate(fixedAsset).signum() != 0;
  }

  /**
   * Броят месеци, в които нормата изчерпва данъчната амортизируема стойност: 1200/ГДАН.
   *
   * @param annualRatePercent годишна данъчна норма в проценти (ГДАН)
   */
  public static BigDecimal monthsForRate(BigDecimal annualRatePercent) {
    if (annualRatePercent == null || annualRatePercent.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return PERCENT_MONTHS.divide(annualRatePercent, CALCULATION_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Броят периоди за плана, изразен в периодичността на актива.
   *
   * <p>Месечна периодичност (по подразбиране за БГ категориите) дава 1200/ГДАН периода; годишна
   * дава 100/ГДАН. И в двата случая нормата е една и съща — периодичността е избор за представяне,
   * не за размер на разхода. Затова тук се дели, вместо да се изисква конкретна периодичност: план
   * с крачка от 12 месеца и месечна сума би бил напълно грешен, а с това деление е просто по-грубо
   * разбит.
   *
   * @param periodicityInMonth месеци в един период; ≤ 0 се чете като 1
   */
  public static BigDecimal numberOfPeriods(
      BigDecimal annualRatePercent, Integer periodicityInMonth) {
    BigDecimal months = monthsForRate(annualRatePercent);
    if (months.signum() == 0) {
      return BigDecimal.ZERO;
    }
    int step = periodicityInMonth == null || periodicityInMonth <= 0 ? 1 : periodicityInMonth;
    return months.divide(BigDecimal.valueOf(step), CALCULATION_SCALE, RoundingMode.HALF_UP);
  }

  /**
   * Най-високата годишна норма, която чл. 55 допуска за дадена категория — АБСОЛЮТНИЯТ таван, тоест
   * с включените завишения по ал. 3, ал. 7 и ал. 8.
   *
   * <p><b>Защо абсолютният, а не нормата по ал. 2.</b> Три алинеи вдигат тавана над ал. 2, и
   * условията на всичките три са ФАКТИ ЗА АКТИВА, не за категорията:
   *
   * <ul>
   *   <li>кат. II: ал. 2 дава 30%, ал. 3 вдига до 50% за фабрично нови машини, част от първоначална
   *       инвестиция;
   *   <li>кат. IV: ал. 2 дава 50%, ал. 7 вдига до 100% за софтуер СУПТО по чл. 118 ал. 16 ЗДДС — и
   *       по т. 2 за компютрите и телефоните, НА КОИТО такъв софтуер е инсталиран;
   *   <li>кат. V: ал. 2 дава 25%, ал. 8 (нова, ДВ бр. 30 от 2026 г., в сила от 01.01.2026 г.) вдига
   *       до 50% за електрически автомобили.
   * </ul>
   *
   * <p>„Фабрично нов“, „в списъка по чл. 118 ал. 16“ и „електрически“ не се виждат от този слой и
   * база данни за тях няма. Проверка срещу 30%, 50% и 25% значи, че трите законни завишения биха се
   * обаждали като грешка при всяко вписване; проверка срещу абсолютния таван пропуска това, но
   * хваща истинските разминавания (кат. I с 40%, кат. VII с 50%).
   *
   * <p>Кат. VI е единствената без число в закона: ал. 6 дава 100/срока на правното ограничение, „но
   * не повече от 33⅓“ — тоест таванът ѝ е самото 33⅓ и той е твърд.
   *
   * @return тавана в проценти, или {@code null} за непозната/незададена категория
   */
  public static BigDecimal ceilingForCategory(String bgTaxCategorySelect) {
    if (bgTaxCategorySelect == null) {
      return null;
    }
    switch (bgTaxCategorySelect) {
      case "I":
        return new BigDecimal("4");
      case "II":
        // ал. 2 → 30; ал. 3 → до 50 за фабрично нови, част от първоначална инвестиция.
        return new BigDecimal("50");
      case "III":
        return new BigDecimal("10");
      case "IV":
        // ал. 2 → 50; ал. 7 → 100 за софтуер СУПТО по чл. 118 ал. 16 ЗДДС.
        return new BigDecimal("100");
      case "V":
        // ал. 2 → 25; ал. 8 → до 50 за електрически автомобили (от 01.01.2026 г.).
        return new BigDecimal("50");
      case "VI":
        // ал. 6: 100/срока на правото, но не повече от 33⅓.
        return new BigDecimal("33.34");
      case "VII":
        return new BigDecimal("15");
      default:
        return null;
    }
  }

  /**
   * Надхвърля ли нормата тавана по чл. 55 за своята категория. Непозната категория или празна норма
   * не се брои за надхвърляне — там просто няма с какво да се сравни.
   */
  public static boolean exceedsCeiling(String bgTaxCategorySelect, BigDecimal annualRatePercent) {
    BigDecimal ceiling = ceilingForCategory(bgTaxCategorySelect);
    return ceiling != null
        && annualRatePercent != null
        && annualRatePercent.signum() != 0
        && annualRatePercent.compareTo(ceiling) > 0;
  }
}
