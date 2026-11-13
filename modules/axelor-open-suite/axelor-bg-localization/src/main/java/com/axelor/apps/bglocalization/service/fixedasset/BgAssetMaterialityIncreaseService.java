package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.account.db.repo.FixedAssetTypeRepository;
import com.axelor.apps.bglocalization.db.BgAssetMaterialityIncrease;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Прилага СЪБИТИЕТО „вдигане на стойностния праг на същественост“ по чл. 60 ал. 3 т. 2 ЗКПО.
 *
 * <p>Дословно: „(3) Алинея 2 не се прилага при отписване на активи: … 2. в резултат на
 * <b>увеличение в стойностния праг на същественост</b>.“ Плюс <b>ал. 4</b>: „Активите по ал. 3 се
 * отписват от данъчния амортизационен план по реда на ал. 1“, тоест при пълно ДАНЪЧНО амортизиране
 * — дотогава данъчната амортизация ТЕЧЕ.
 *
 * <p><b>Какво прави и какво НЕ прави.</b> Маркира кои активи е засегнало вдигането — това е
 * основанието, което после държи данъчния борд нерязан. НЕ отписва нищо счетоводно: разписването по
 * счетоводната политика е решение и действие на клиента, а тази маркировка само записва данъчното
 * му следствие.
 */
public class BgAssetMaterialityIncreaseService {

  protected final FixedAssetRepository fixedAssetRepository;

  @Inject
  public BgAssetMaterialityIncreaseService(FixedAssetRepository fixedAssetRepository) {
    this.fixedAssetRepository = fixedAssetRepository;
  }

  /**
   * Маркира засегнатите активи и връща броя им.
   *
   * <p>⚠️ <b>Само активи, които СА БИЛИ в ДАП.</b> Актив без FISCAL в плана никога не е влизал в
   * данъчния амортизационен план — за него няма какво да „не се отписва“ по ал. 3, тъй че
   * маркирането му би приложило разпоредба без предмет. Активите, които винаги са били под прага,
   * са точно този случай: те са извън ДАП по чл. 50/51, а не по чл. 60 ал. 3 т. 2.
   *
   * <p>⚠️ Двата прага се разглеждат ПООТДЕЛНО, по техническия вид на категорията. Дружество може да
   * вдигне само материалния праг и тогава нематериалните активи не се засягат — чл. 50 ал. 1 т. 1 и
   * чл. 51 ал. 1 т. 1 б. „г“ аа) говорят за два различни прага и законът допуска да са различни.
   */
  @Transactional(rollbackOn = {Exception.class})
  public int applyToAssets(BgAssetMaterialityIncrease increase) {
    if (increase == null || increase.getCompany() == null) {
      return 0;
    }
    List<FixedAsset> marked = new ArrayList<>();
    marked.addAll(
        mark(
            increase,
            FixedAssetTypeRepository.FIXED_ASSET_CATEGORY_TECHNICAL_TYPE_SELECT_TANGIBLE_ASSET,
            increase.getPreviousTangibleThresholdEur(),
            increase.getNewTangibleThresholdEur()));
    marked.addAll(
        mark(
            increase,
            FixedAssetTypeRepository.FIXED_ASSET_CATEGORY_TECHNICAL_TYPE_SELECT_INTANGIBLE_ASSET,
            increase.getPreviousIntangibleThresholdEur(),
            increase.getNewIntangibleThresholdEur()));
    for (FixedAsset asset : marked) {
      asset.setBgMaterialityIncrease(increase);
      fixedAssetRepository.save(asset);
    }
    return marked.size();
  }

  /**
   * Кандидатите за един технически вид.
   *
   * <p>Заявката стеснява по дружество, вид и стойност; решението „засегнат ли е“ остава в чистата
   * {@link BgAssetThresholdRules#isCaughtByIncrease}, за да има свидетел с числа — модулът няма
   * mock-подпора, тъй че правило, скрито в JPQL, се сверява само структурно.
   */
  protected List<FixedAsset> mark(
      BgAssetMaterialityIncrease increase,
      int technicalType,
      BigDecimal previousThreshold,
      BigDecimal newThreshold) {
    if (!BgAssetThresholdRules.isIncrease(previousThreshold, newThreshold)) {
      return new ArrayList<>();
    }
    List<FixedAsset> candidates =
        fixedAssetRepository
            .all()
            .filter(
                "self.company = :company"
                    + " AND self.fixedAssetCategory.fixedAssetType.technicalTypeSelect = :type"
                    + " AND self.grossValue < :threshold"
                    + " AND self.bgMaterialityIncrease IS NULL"
                    + " AND self.statusSelect != :transferred")
            .bind("company", increase.getCompany())
            .bind("type", technicalType)
            .bind("threshold", newThreshold)
            .bind("transferred", FixedAssetRepository.STATUS_TRANSFERRED)
            .fetch();

    List<FixedAsset> affected = new ArrayList<>();
    for (FixedAsset asset : candidates) {
      String plan = asset.getDepreciationPlanSelect();
      if (plan == null || !plan.contains(FixedAssetRepository.DEPRECIATION_PLAN_FISCAL)) {
        continue;
      }
      if (BgAssetThresholdRules.isCaughtByIncrease(
          asset.getGrossValue(), previousThreshold, newThreshold)) {
        affected.add(asset);
      }
    }
    return affected;
  }
}
