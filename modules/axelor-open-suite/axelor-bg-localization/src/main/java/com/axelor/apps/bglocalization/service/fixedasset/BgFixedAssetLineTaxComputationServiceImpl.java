package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.service.fixedasset.FixedAssetFailOverControlService;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineFiscalComputationServiceImpl;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineToolService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.base.service.app.AppBaseService;
import com.google.inject.Inject;
import com.google.inject.servlet.RequestScoped;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Данъчният амортизационен план (ДАП) по ЗКПО — по НОРМА, не по брой вноски.
 *
 * <p>Axelor нарича този план „fiscal“ и той вече е точно това, което ЗКПО иска от ДАП-а:
 * <b>извънсчетоводен</b>. {@code FixedAssetLineMoveServiceImpl.realize} пази цялото генериране на
 * статии с {@code if (typeSelect != TYPE_SELECT_FISCAL)} — данъчните редове НЕ пораждат нито една
 * счетоводна статия. Затова тук не се строи нов вид ред, а се преизчислява съществуващият:
 * данъчният план остава видим в стандартния панел „Fiscal information“ и в {@code
 * fiscalFixedAssetLineList}.
 *
 * <p><b>Кое се сменя спрямо базата.</b> Три неща, и всяко е отделен член от закона:
 *
 * <ol>
 *   <li><b>Брой периоди ← нормата</b> (чл. 55 ал. 2). Базата чете {@code
 *       fiscalNumberOfDepreciation}; тук броят излиза от ГДАН — виж {@link BgTaxDepreciation}.
 *       Полето остава на актива и продължава да храни СЧЕТОВОДНИЯ план.
 *   <li><b>ДАС без остатъчна стойност</b> (чл. 53). Базата дели {@code grossValue − residualValue},
 *       което е счетоводната амортизируема стойност по СС 4 т. 2. Данъчната амортизируема стойност
 *       не приспада остатъчна стойност — при обикновено придобиване е историческата цена.
 *   <li><b>Без прорате по дни</b> (чл. 58 ал. 1 и чл. 59). ЗКПО брои цели МЕСЕЦИ: амортизацията
 *       тръгва от началото на месец и се спира от началото на месец. Базата обаче чете един общ
 *       флаг {@code FixedAssetCategory.isProrataTemporis} за всичките три плана, тъй че
 *       счетоводният план може да е с прорате, без това да мести данъчния.
 * </ol>
 *
 * <p><b>Какво НЕ се сменя.</b> Fail-over активите (внесени от друг софтуер с вече начислена
 * амортизация) минават по базовия път — там водещото е внесеното число, не нормата, и пресмятането
 * му отново по ГДАН би пренаписало чужда история.
 */
@RequestScoped
public class BgFixedAssetLineTaxComputationServiceImpl
    extends FixedAssetLineFiscalComputationServiceImpl {

  @Inject
  public BgFixedAssetLineTaxComputationServiceImpl(
      FixedAssetFailOverControlService fixedAssetFailOverControlService,
      AppBaseService appBaseService,
      FixedAssetLineToolService fixedAssetLineToolService) {
    super(fixedAssetFailOverControlService, appBaseService, fixedAssetLineToolService);
  }

  /**
   * Прилага ли се БГ пътят за този актив. Fail-over се изключва явно: внесената история е водеща.
   */
  protected boolean isBgTaxPlan(FixedAsset fixedAsset) {
    return BgTaxDepreciation.hasTaxRate(fixedAsset)
        && !fixedAssetFailOverControlService.isFailOver(fixedAsset);
  }

  /**
   * Единственият вход към генерирането на данъчния план — и затова единственото място, където
   * липсващата норма може да се обади с изречение вместо с тих празен план.
   *
   * <p>Празна норма при поискан данъчен план е конфигурационна грешка, не избор: активът или е
   * данъчен амортизируем (чл. 50) и има норма, или не е — и тогава планът му не съдържа „1“. Точно
   * такъв е случаят със земята: {@code depreciationPlanSelect = "0"} и генераторът никога не стига
   * дотук. Мълчаливо празен ДАП обаче не се различава от „не се дължи амортизация“, а разликата е
   * между подадена и НЕподадена ГДД.
   */
  @Override
  public Optional<FixedAssetLine> computeInitialPlannedFixedAssetLine(FixedAsset fixedAsset)
      throws AxelorException {
    if (fixedAsset != null
        && !BgTaxDepreciation.hasTaxRate(fixedAsset)
        && !fixedAssetFailOverControlService.isFailOver(fixedAsset)) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_MISSING_FIELD,
          "%s",
          String.format(
              "Активът „%s“ иска данъчен амортизационен план, но няма годишна данъчна норма"
                  + " (ГДАН) по чл. 55 ЗКПО. Попълнете нормата на актива или на категорията му;"
                  + " за категория VI (чл. 55 ал. 1 т. 6) нормата е по ал. 2 — 100/години на"
                  + " правното ограничение, но не повече от 33 1/3. Ако активът не е данъчен"
                  + " амортизируем по чл. 50, махнете данъчния план от „Амортизационен план“.",
              fixedAsset.getName()));
    }
    return super.computeInitialPlannedFixedAssetLine(fixedAsset);
  }

  /**
   * Брой периоди = 1200/ГДАН, разбит на периодичността на актива. Виж {@link BgTaxDepreciation}.
   */
  @Override
  protected BigDecimal getNumberOfDepreciation(FixedAsset fixedAsset) {
    if (!isBgTaxPlan(fixedAsset)) {
      return super.getNumberOfDepreciation(fixedAsset);
    }
    return BgTaxDepreciation.numberOfPeriods(
        BgTaxDepreciation.effectiveRate(fixedAsset), fixedAsset.getFiscalPeriodicityInMonth());
  }

  /**
   * Данъчна амортизируема стойност (ДАС) по чл. 53 — БЕЗ приспадане на остатъчна стойност.
   *
   * <p>Остатъчната стойност е счетоводна преценка (СС 4 т. 2) и намалява СЧЕТОВОДНАТА амортизируема
   * стойност. ЗКПО не я познава: щом остатъчната стойност е нула, двата плана съвпадат сами, а щом
   * не е — разминават се, и точно това е временната разлика по чл. 54.
   */
  @Override
  protected BigDecimal computeInitialDepreciationBase(FixedAsset fixedAsset) {
    if (!isBgTaxPlan(fixedAsset)) {
      return super.computeInitialDepreciationBase(fixedAsset);
    }
    // ⚠️ ДАС, а не просто отчетната стойност. Дотук двете съвпадаха (чл. 53 ал. 1: историческа
    // цена, БЕЗ приспадане на остатъчна), тъй че grossValue вършеше работа. Чл. 63 ги разделя —
    // „данъчната амортизируема стойност… се увеличава с последващите разходи" — а счетоводната
    // капитализация може да е ДРУГА сума. Празното поле пада обратно на grossValue, тъй че нито
    // един заварен план не се мести.
    return fixedAssetLineToolService.getCompanyScaledValue(
        BgSubsequentCostRules.amortizableValue(fixedAsset), fixedAsset);
  }

  /**
   * ЗКПО брои цели месеци (чл. 58 ал. 1, чл. 59), не дни — данъчният план никога не е с прорате.
   *
   * <p>Флагът {@code isProrataTemporis} е на КАТЕГОРИЯТА и е един за трите плана, тъй че без това
   * заглушаване счетоводното решение за прорате би мествало и данъчния план.
   */
  @Override
  protected Boolean isProrataTemporis(FixedAsset fixedAsset) {
    if (isBgTaxPlan(fixedAsset)) {
      return false;
    }
    return super.isProrataTemporis(fixedAsset);
  }

  /**
   * Срокът в месеци също излиза от нормата, за да не се разминава с броя периоди.
   *
   * <p>Изчислен нагоре: непълен последен месец пак е месец, в който се начислява амортизация.
   * Базовият {@code fiscalDurationInMonth} остава недокоснат — него го чете счетоводният план.
   */
  @Override
  protected Integer getDurationInMonth(FixedAsset fixedAsset) {
    if (!isBgTaxPlan(fixedAsset)) {
      return super.getDurationInMonth(fixedAsset);
    }
    return BgTaxDepreciation.monthsForRate(BgTaxDepreciation.effectiveRate(fixedAsset))
        .setScale(0, RoundingMode.CEILING)
        .intValue();
  }
}
