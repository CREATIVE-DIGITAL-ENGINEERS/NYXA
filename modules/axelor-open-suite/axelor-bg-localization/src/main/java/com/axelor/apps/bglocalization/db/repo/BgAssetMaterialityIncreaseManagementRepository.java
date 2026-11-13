package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.bglocalization.db.BgAssetMaterialityIncrease;
import com.axelor.apps.bglocalization.service.fixedasset.BgAssetThresholdRules;
import javax.persistence.PersistenceException;

/**
 * Отказва запис, който не е УВЕЛИЧЕНИЕ на прага.
 *
 * <p>Чл. 60 ал. 3 т. 2 ЗКПО важи за отписване „в резултат на <b>увеличение</b> в стойностния праг
 * на същественост“. Свален праг не поражда отписване — той по-скоро ВКАРВА активи в обхвата на чл.
 * 50/51, а не ги изважда. Запис със свален или непроменен праг би създал основание, което законът
 * не дава, и после би държал данъчния борд нерязан по чл. 60 ал. 2 без причина.
 *
 * <p>Отказ, а не предупреждение: следствието е невидимо. Активът просто остава в ДАП и разликата се
 * появява години по-късно, в намалението по чл. 66 ал. 2 — тогава никой не помни защо.
 */
public class BgAssetMaterialityIncreaseManagementRepository
    extends BgAssetMaterialityIncreaseRepository {

  @Override
  public BgAssetMaterialityIncrease save(BgAssetMaterialityIncrease entity) {
    if (entity != null) {
      refuseUnlessIncrease(entity);
    }
    return super.save(entity);
  }

  protected void refuseUnlessIncrease(BgAssetMaterialityIncrease entity) {
    boolean tangible =
        BgAssetThresholdRules.isIncrease(
            entity.getPreviousTangibleThresholdEur(), entity.getNewTangibleThresholdEur());
    boolean intangible =
        BgAssetThresholdRules.isIncrease(
            entity.getPreviousIntangibleThresholdEur(), entity.getNewIntangibleThresholdEur());
    if (tangible || intangible) {
      return;
    }
    throw new PersistenceException(
        "Записът не описва УВЕЛИЧЕНИЕ на стойностния праг на същественост. Чл. 60 ал. 3 т. 2 ЗКПО"
            + " важи само за отписване „в резултат на увеличение в стойностния праг на"
            + " същественост“ — свален или непроменен праг не поражда основание и не бива да държи"
            + " данъчния амортизационен план нерязан. Вдигнете поне един от двата прага (материален"
            + " или нематериален) над стария.");
  }
}
