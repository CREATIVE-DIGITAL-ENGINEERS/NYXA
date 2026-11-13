package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Последващи разходи по чл. 63 и чл. 64 ЗКПО, като чисти функции.
 *
 * <p>Сверено дословно на 11.09.2026 г. срещу консолидирания ЗКПО (посл. изм. и доп. ДВ бр. 30 от 27
 * март 2026 г.).
 *
 * <p><b>⚠️ ДАТАТА НЕ Е 1 ЯНУАРИ, и това е най-лесната грешка тук.</b> Чл. 62 ал. 2 наистина
 * отразява промените „към 1 януари на годината, в която са установени обстоятелствата“ — но целият
 * чл. 62 е РЕТРОСПЕКТИВЕН по замисъл: ал. 3 иска новите стойности да са равни на онези, „които биха
 * били определени, <b>ако обстоятелствата… са били известни през предходните години</b>“. Тоест ал.
 * 2 урежда случая, в който нещо е ТРЯБВАЛО да се знае по-рано.
 *
 * <p>Последващият разход не е такъв случай — той е НОВ факт на текущата година. За него важи <b>чл.
 * 62 ал. 5</b>: „Когато установените обстоятелства <b>не налагат промяна в стойностите на актива за
 * предходни години</b>, промяната в стойностите се отразява в данъчния амортизационен план <b>към
 * момента на установяване на обстоятелството през текущата година</b>.“ А <b>чл. 63 изр. 2</b>
 * прави момента точен: „Данъчната амортизируема стойност се увеличава <b>от началото на месеца,
 * през който са завършени последващите разходи</b>.“
 *
 * <p><b>⚠️ И КОРЕКЦИЯ ПО ЧЛ. 62 АЛ. 4 НЕ СЕ ПРАВИ.</b> Ал. 4 коригира текущата амортизация „с
 * разликата между начислената… през предходните години и тази, която би била начислена за тези
 * години, ако обстоятелствата… са били известни“. При последващ разход такава разлика НЕ СЪЩЕСТВУВА
 * — разходът не е съществувал през предходните години. Приложена тук, ал. 4 би създала корекция от
 * нищото.
 *
 * <p>Корекцията при ОТКРИВАНЕ НА ГРЕШКА (чл. 62 ал. 2 + ал. 3, към 1 януари) е ОТДЕЛЕН правен режим
 * и нарочно не е вариант на същото действие — смесени, двата биха споделили поле, чиято стойност
 * мени правния резултат без да го обяснява.
 */
public final class BgSubsequentCostRules {

  private BgSubsequentCostRules() {}

  /** Класификация на разхода — само капиталовият минава по чл. 63/64. */
  public static final class Classification {
    /**
     * Капиталов: „води до бъдещи икономически изгоди, свързани с актива“ по смисъла на счетоводното
     * законодателство (чл. 63, чл. 64 ал. 1).
     */
    public static final String CAPITAL = "CAPITAL";

    /**
     * Текущ (ремонт, поддръжка) — признава се като разход и НЕ мени ДАС.
     *
     * <p>⚠️ Класификацията е ПРЕЦЕНКА на съставителя по счетоводната политика, не се извежда от
     * сумата. И двата члена препращат към „счетоводното законодателство“ за критерия, а не дават
     * свой — тъй че системата пита, вместо да налучква.
     */
    public static final String CURRENT = "CURRENT";

    private Classification() {}
  }

  /** По кой член минава капиталовият разход. */
  public static final class Routing {
    /** Активът е НАЛИЧЕН в ДАП → увеличава се неговата ДАС (чл. 63). */
    public static final String ART63 = "ART63";

    /**
     * Активът е ОТПИСАН от ДАП, но не и счетоводно → поражда се САМОСТОЯТЕЛЕН актив (чл. 64 ал. 1).
     */
    public static final String ART64 = "ART64";

    /** Текущ разход — не минава по никой от двата. */
    public static final String NONE = "NONE";

    private Routing() {}
  }

  /**
   * Датата, от която важи увеличението — началото на месеца на ЗАВЪРШВАНЕ на разходите.
   *
   * <p>Една и съща за двата члена: чл. 63 изр. 2 за наличния актив и чл. 64 ал. 2 („се завежда в
   * данъчния амортизационен план от началото на месеца, през който са завършени последващите
   * разходи“) за самостоятелния.
   */
  public static LocalDate effectiveFrom(LocalDate completionDate) {
    return completionDate == null ? null : completionDate.withDayOfMonth(1);
  }

  /**
   * По кой член минава разходът за този актив.
   *
   * <p>Разграничителят е буквален: чл. 63 иска актив „<b>наличен</b> в данъчния амортизационен
   * план“, а чл. 64 ал. 1 — актив, който „е <b>отписан</b> от данъчния амортизационен план, но не е
   * отписан за счетоводни цели“. Тоест въпросът е за присъствието в ДАП, не за състоянието на
   * актива.
   *
   * <p>⚠️ Актив, отписан И счетоводно, не попада в нито един от двата — последващи разходи по него
   * нямат предмет. Затова се връща {@link Routing#NONE}, а не чл. 64.
   */
  public static String routing(FixedAsset fixedAsset, String classification) {
    if (fixedAsset == null || !Classification.CAPITAL.equals(classification)) {
      return Routing.NONE;
    }
    boolean writtenOffForAccounting =
        fixedAsset.getStatusSelect() == FixedAssetRepository.STATUS_TRANSFERRED;
    if (writtenOffForAccounting) {
      return Routing.NONE;
    }
    return isInTaxPlan(fixedAsset) ? Routing.ART63 : Routing.ART64;
  }

  /**
   * Наличен ли е активът в данъчния амортизационен план.
   *
   * <p>Мери се по плана, а не по наличието на редове: актив, изваден от ДАП по чл. 60, запазва
   * начислените си редове, тъй че броенето им би казало „наличен“ за актив, който е излязъл.
   */
  public static boolean isInTaxPlan(FixedAsset fixedAsset) {
    if (fixedAsset == null) {
      return false;
    }
    String plan = fixedAsset.getDepreciationPlanSelect();
    return plan != null && plan.contains(FixedAssetRepository.DEPRECIATION_PLAN_FISCAL);
  }

  /**
   * Данъчната амортизируема стойност на актива — собствената, ако е зададена, иначе отчетната.
   *
   * <p>⚠️ ДАС е СОБСТВЕНА величина, а не същата като счетоводната отчетна стойност. До WS-A6 двете
   * съвпадаха (чл. 53 ал. 1: историческа цена, без приспадане на остатъчна), тъй че {@code
   * grossValue} вършеше работа. Чл. 63 обаче ги разделя: счетоводната капитализация по политиката
   * на клиента и данъчното увеличение по чл. 63 може да са различни суми — и точно оттам се раждат
   * временните разлики по чл. 54, които справката от WS-A5 вече чете.
   *
   * <p>Празната стойност значи „както казва отчетната“ и пази заварените активи непроменени.
   */
  public static BigDecimal amortizableValue(FixedAsset fixedAsset) {
    if (fixedAsset == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal own = fixedAsset.getBgTaxAmortizableValue();
    return own.signum() > 0 ? own : fixedAsset.getGrossValue();
  }

  /** ДАС след увеличението по чл. 63. */
  public static BigDecimal increasedAmortizableValue(FixedAsset fixedAsset, BigDecimal amount) {
    BigDecimal current = amortizableValue(fixedAsset);
    return current.add(amount == null ? BigDecimal.ZERO : amount);
  }

  /**
   * Месечната данъчна амортизация след промяната.
   *
   * <p>Чл. 58 ал. 3: {@code ГДА = ДАС × ГДАН × М/12}. На месец това е ДАС × ГДАН / 1200 — същата
   * аритметика, която {@link BgTaxDepreciation} ползва за броя периоди, само погледната откъм
   * сумата.
   *
   * <p>⚠️ НОРМАТА НЕ СЕ МЕНИ, мени се БАЗАТА. Нормата е таван по чл. 55 ал. 2 и е избор за актива;
   * последващият разход не е основание да се пипа. Тоест планът не се „свива“ — той става по-дълъг
   * или по-скъп на месец според това какво е избрано, а таванът на чл. 53 ал. 3 го спира.
   */
  public static BigDecimal monthlyCharge(
      BigDecimal amortizableValue, BigDecimal annualRatePercent) {
    if (amortizableValue == null || annualRatePercent == null || annualRatePercent.signum() == 0) {
      return BigDecimal.ZERO;
    }
    return amortizableValue
        .multiply(annualRatePercent)
        .divide(BigDecimal.valueOf(1200), 2, RoundingMode.HALF_UP);
  }

  /**
   * Остатъчният график след увеличението — по един запис на месец, последният поема остатъка.
   *
   * <p>Връща сумите, а не редове на плана: така правилото има свидетел с ЧИСЛА, без Guice и без
   * база. Услугата отгоре ги превръща в {@code FixedAssetLine}.
   *
   * <p><b>Таванът е чл. 53 ал. 3</b> — „Начислената данъчна амортизация не може да превишава
   * данъчната амортизируема стойност на актива“. Затова графикът спира, когато сборът на вече
   * начисленото и новите вноски стигне новата ДАС, а последната вноска е ОСТАТЪКЪТ, не пълна
   * месечна.
   *
   * @param newAmortizableValue ДАС след увеличението
   * @param alreadyCharged начислената данъчна амортизация ДО датата на промяната
   * @param annualRatePercent ГДАН
   */
  public static List<BigDecimal> remainingSchedule(
      BigDecimal newAmortizableValue, BigDecimal alreadyCharged, BigDecimal annualRatePercent) {
    List<BigDecimal> schedule = new ArrayList<>();
    BigDecimal monthly = monthlyCharge(newAmortizableValue, annualRatePercent);
    if (monthly.signum() == 0) {
      return schedule;
    }
    BigDecimal remaining =
        (newAmortizableValue == null ? BigDecimal.ZERO : newAmortizableValue)
            .subtract(alreadyCharged == null ? BigDecimal.ZERO : alreadyCharged);
    // Пазач срещу безкраен цикъл при абсурдна конфигурация — същият довод, по който базовият
    // generateComputedPlannedFixedAssetLines брои до 1000.
    int guard = 0;
    while (remaining.signum() > 0 && guard < 1000) {
      BigDecimal charge = remaining.compareTo(monthly) <= 0 ? remaining : monthly;
      schedule.add(charge);
      remaining = remaining.subtract(charge);
      guard++;
    }
    return schedule;
  }

  /**
   * Категорията на самостоятелния актив по чл. 64 — НАСЛЕДЕНА, не избрана.
   *
   * <p>Чл. 64 ал. 3, дословно: „За целите на чл. 55 данъчният амортизируем актив се разпределя <b>в
   * категорията, в която е бил разпределен активът</b>, във връзка с който са извършени
   * последващите разходи.“ Тоест нито съставителят, нито категорията на разхода решават — решава
   * категорията на стария актив, и заедно с нея нормата по чл. 55 ал. 2.
   */
  public static String inheritedCategory(FixedAsset sourceAsset) {
    return sourceAsset == null ? null : sourceAsset.getBgTaxCategorySelect();
  }

  /**
   * Влиза ли самостоятелният актив по чл. 64 в ДАП, или пада под прага.
   *
   * <p>⚠️ <b>ДА СЕ ПОТВЪРДИ.</b> Чл. 64 ал. 1 казва императивно „<b>се завежда</b> самостоятелен
   * данъчен амортизируем актив“, без да препраща към праг. Чл. 50 и чл. 51 пък определят какво Е
   * данъчен амортизируем актив — а чл. 64 ползва точно този термин. Двата прочита дават различен
   * резултат за разход от 300 EUR: по единия се завежда актив, по другия не.
   *
   * <p>Тук е приложен прагът, защото терминът е дефиниран другаде и чл. 64 не го предефинира — но
   * това е ТЪЛКУВАНЕ, не цитат, и стои маркирано. ⚠️ И забележете асиметрията в самия закон: <b>чл.
   * 63 няма никакъв праг</b> — ДАС на наличен актив се увеличава с разхода, колкото и малък да е.
   */
  public static boolean entersTaxPlan(BigDecimal amount, BigDecimal threshold) {
    return !BgAssetThresholdRules.isBelowThreshold(amount, threshold);
  }
}
