package com.axelor.apps.bglocalization.service.payroll;

import java.util.Map;

/**
 * ТЕСТОВА БАЗА: услугата за болничния, чието право идва по ДЕКЛАРИРАНИЯ СТАЖ на входа, а не от
 * замразената присъда на листа.
 *
 * <p><b>Само за свидетели, които мерят дни, пари и т. 9</b> — с епизоди без лист зад себе си или с
 * подменени листове и без Guice. В производство такъв път НЯМА: {@link
 * BgSickLeaveService#benefitVerdictOf} отказва за епизод без лист, а присъдата идва единствено от
 * листа. Правото към началото на неработоспособността мери {@code BgSickBenefitVerdictTest} по
 * реалния път.
 *
 * <p>⚠️ Едно място за всички свидетели, а не по едно подменяне във всеки: така „старият път“ е
 * достижим от точно един клас, и той е в тестовите източници.
 */
public abstract class BgSickLeaveServiceOnDeclaredStage extends BgSickLeaveService {

  @Override
  protected BgSickBenefitVerdictRules.Verdict benefitVerdictOf(
      BgSickLeaveEpisode first, Map<Long, BgSickBenefitVerdictRules.Verdict> fresh) {
    return null;
  }
}
