package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.bglocalization.db.BgSubsequentCost;
import com.axelor.apps.bglocalization.db.repo.BgSubsequentCostRepository;
import com.axelor.studio.db.AppBgLocalization;
import com.axelor.studio.db.repo.AppBgLocalizationRepository;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Прилага последващ разход по чл. 63 или чл. 64 ЗКПО.
 *
 * <p>Правилата са в {@link BgSubsequentCostRules} — тук е само мутацията. Разделението е нарочно:
 * модулът няма mock-подпора, а точно тези числа трябва да имат свидетел.
 */
public class BgSubsequentCostService {

  protected final FixedAssetRepository fixedAssetRepository;
  protected final BgSubsequentCostRepository subsequentCostRepository;
  protected final AppBgLocalizationRepository appBgLocalizationRepository;

  @Inject
  public BgSubsequentCostService(
      FixedAssetRepository fixedAssetRepository,
      BgSubsequentCostRepository subsequentCostRepository,
      AppBgLocalizationRepository appBgLocalizationRepository) {
    this.fixedAssetRepository = fixedAssetRepository;
    this.subsequentCostRepository = subsequentCostRepository;
    this.appBgLocalizationRepository = appBgLocalizationRepository;
  }

  /** Определя правния път и датата, без да прилага нищо. */
  public BgSubsequentCost resolve(BgSubsequentCost cost) {
    if (cost == null || cost.getFixedAsset() == null) {
      return cost;
    }
    FixedAsset asset = cost.getFixedAsset();
    cost.setRoutingSelect(BgSubsequentCostRules.routing(asset, cost.getClassificationSelect()));
    cost.setEffectiveFrom(BgSubsequentCostRules.effectiveFrom(cost.getCompletionDate()));
    cost.setAmortizableValueBefore(BgSubsequentCostRules.amortizableValue(asset));
    if (BgSubsequentCostRules.Routing.ART63.equals(cost.getRoutingSelect())) {
      cost.setAmortizableValueAfter(
          BgSubsequentCostRules.increasedAmortizableValue(asset, cost.getAmount()));
    } else {
      cost.setAmortizableValueAfter(cost.getAmortizableValueBefore());
    }
    return cost;
  }

  /**
   * Прилага разхода.
   *
   * <p>Текущият разход не мени нищо: чл. 63 и чл. 64 говорят само за разходи, които „водят до
   * бъдещи икономически изгоди". Ремонтът е текущ разход и остава такъв — тъй че тук няма какво да
   * се направи, и това не е пропуск, а правилният резултат.
   */
  @Transactional(rollbackOn = {Exception.class})
  public BgSubsequentCost apply(BgSubsequentCost cost) {
    resolve(cost);
    if (cost == null || Boolean.TRUE.equals(cost.getIsApplied())) {
      return cost;
    }
    String routing = cost.getRoutingSelect();
    if (BgSubsequentCostRules.Routing.ART63.equals(routing)) {
      applyArt63(cost);
    } else if (BgSubsequentCostRules.Routing.ART64.equals(routing)) {
      applyArt64(cost);
    }
    cost.setIsApplied(Boolean.TRUE);
    cost.setAppliedOn(LocalDate.now());
    return subsequentCostRepository.save(cost);
  }

  /**
   * Чл. 63 — увеличава ДАС на наличния актив и преизчислява ОСТАТЪКА от плана.
   *
   * <p><b>Миналото не се пипа.</b> Реализираните данъчни редове остават както са: чл. 62 ал. 2 изр.
   * 2 казва „не се коригира начислената данъчна амортизация за предходните години", а и тук няма
   * какво да се коригира — разходът е нов факт. Махат се само ПЛАНОВИТЕ редове от датата нататък и
   * се строят наново по новата ДАС.
   *
   * <p><b>Таванът е чл. 53 ал. 3</b> — начисленото не може да превиши ДАС — и графикът от {@link
   * BgSubsequentCostRules#remainingSchedule} спира точно там, с остатък в последната вноска.
   */
  protected void applyArt63(BgSubsequentCost cost) {
    FixedAsset asset = cost.getFixedAsset();
    LocalDate from = cost.getEffectiveFrom();
    BigDecimal newValue = cost.getAmortizableValueAfter();

    asset.setBgTaxAmortizableValue(newValue);

    List<FixedAssetLine> lines = asset.getFiscalFixedAssetLineList();
    if (lines == null) {
      lines = new ArrayList<>();
      asset.setFiscalFixedAssetLineList(lines);
    }
    BigDecimal charged = BigDecimal.ZERO;
    List<FixedAssetLine> kept = new ArrayList<>();
    for (FixedAssetLine line : lines) {
      if (line == null || line.getDepreciationDate() == null) {
        continue;
      }
      boolean realized = line.getStatusSelect() == FixedAssetLineRepository.STATUS_REALIZED;
      if (realized) {
        kept.add(line);
        charged = charged.add(line.getDepreciation());
      } else if (line.getDepreciationDate().isBefore(from)) {
        // Планов ред ПРЕДИ датата на действие: не се пипа — той е част от миналото на плана.
        kept.add(line);
        charged = charged.add(line.getDepreciation());
      }
    }
    lines.clear();
    lines.addAll(kept);

    List<BigDecimal> schedule =
        BgSubsequentCostRules.remainingSchedule(
            newValue, charged, BgTaxDepreciation.effectiveRate(asset));
    LocalDate date = from;
    for (BigDecimal charge : schedule) {
      FixedAssetLine line = new FixedAssetLine();
      line.setDepreciationDate(date.withDayOfMonth(date.lengthOfMonth()));
      line.setDepreciation(charge);
      line.setStatusSelect(FixedAssetLineRepository.STATUS_PLANNED);
      line.setTypeSelect(FixedAssetLineRepository.TYPE_SELECT_FISCAL);
      charged = charged.add(charge);
      line.setCumulativeDepreciation(charged);
      line.setAccountingValue(newValue.subtract(charged));
      lines.add(line);
      date = date.plusMonths(1);
    }
    fixedAssetRepository.save(asset);
  }

  /**
   * Чл. 64 — поражда САМОСТОЯТЕЛЕН данъчен амортизируем актив.
   *
   * <p>Ал. 3: „За целите на чл. 55 данъчният амортизируем актив се разпределя <b>в категорията, в
   * която е бил разпределен активът</b>, във връзка с който са извършени последващите разходи.“ —
   * тъй че категорията и нормата се НАСЛЕДЯВАТ, не се избират.
   *
   * <p>⚠️ Прагът по чл. 50/51 е приложен, но е ТЪЛКУВАНЕ: чл. 64 ал. 1 казва императивно „се
   * завежда“, без препратка към праг. Виж {@link BgSubsequentCostRules#entersTaxPlan}.
   *
   * <p>⚠️ И ал. 4 остава за WS следваща стъпка: „Когато активът… се отпише от счетоводния
   * амортизационен план, преди данъчният амортизируем актив по ал. 1 да е напълно амортизиран,
   * последният се отписва от данъчния амортизационен план при условията и по реда на чл. 60.“ —
   * тоест породеният актив трябва да последва стария при счетоводното му отписване. Тук се създава
   * връзката ({@code generatedFixedAsset}), която ще позволи това да се направи.
   */
  protected void applyArt64(BgSubsequentCost cost) {
    FixedAsset source = cost.getFixedAsset();
    FixedAsset created = new FixedAsset();
    created.setName(
        String.format(
            "%s — последващи разходи по чл. 64 (%s)", source.getName(), cost.getCompletionDate()));
    created.setCompany(source.getCompany());
    created.setFixedAssetCategory(source.getFixedAssetCategory());
    created.setGrossValue(cost.getAmount());
    created.setBgTaxAmortizableValue(cost.getAmount());
    created.setBgTaxCategorySelect(BgSubsequentCostRules.inheritedCategory(source));
    created.setBgTaxDepreciationRate(BgTaxDepreciation.effectiveRate(source));
    created.setBgTaxStartSelect(source.getBgTaxStartSelect());
    created.setAcquisitionDate(cost.getCompletionDate());
    created.setFirstServiceDate(cost.getEffectiveFrom());
    created.setStatusSelect(FixedAssetRepository.STATUS_DRAFT);

    BigDecimal threshold = statutoryThreshold();
    boolean below = !BgSubsequentCostRules.entersTaxPlan(cost.getAmount(), threshold);
    cost.setIsBelowThreshold(below);
    created.setBgIsBelowTaxThreshold(below);
    created.setBgTaxThresholdApplied(threshold);
    created.setDepreciationPlanSelect(
        below
            ? FixedAssetRepository.DEPRECIATION_PLAN_NONE
            : FixedAssetRepository.DEPRECIATION_PLAN_FISCAL);

    cost.setGeneratedFixedAsset(fixedAssetRepository.save(created));
  }

  /**
   * Законовата сума по чл. 50 ал. 1 т. 2 / чл. 51 ал. 1 т. 1 б. „г“ бб).
   *
   * <p>Чете се само законовата, не и политиковата: политиката има ДВА прага по вид актив, а
   * породеният по чл. 64 наследява категорията на стария и не носи свой технически вид. Прилагането
   * на грешния праг би изхвърлило от ДАП актив, който му принадлежи — затова се ползва онзи, който
   * е един и същ за материални и нематериални.
   */
  protected BigDecimal statutoryThreshold() {
    AppBgLocalization app = appBgLocalizationRepository.all().fetchOne();
    return app == null ? null : app.getBgAssetStatutoryThresholdEur();
  }
}
