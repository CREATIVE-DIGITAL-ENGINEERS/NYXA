package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.account.service.fixedasset.FixedAssetDerogatoryLineService;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineFiscalComputationServiceImpl;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineFiscalServiceImpl;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.CurrencyScaleService;
import com.axelor.apps.base.service.PeriodService;
import com.axelor.apps.base.service.YearService;
import com.google.inject.Inject;
import java.time.LocalDate;

/**
 * Данъчният амортизационен план при отписване — чл. 60 ЗКПО.
 *
 * <p><b>Какво сменя.</b> Базовият {@code
 * AbstractFixedAssetLineServiceImpl.generateProrataDepreciationLine} пренаписва първия планов ред
 * на ДНЕВНО пропорционална вноска с дата = датата на отписване, и го прави за всеки план поотделно,
 * без да различава счетоводния от данъчния. За данъчния план това противоречи на чл. 60 ал. 2:
 * активът се отписва от ДАП „в началото на месеца, през който се отписва за счетоводни цели“ —
 * тоест месецът на отписване НЕ носи данъчна амортизация, нито пълна, нито частична. Затова тук не
 * се строи пропорционален ред; отрязването на борда прави {@link BgFixedAssetDisposalServiceImpl},
 * по датата от {@link BgAssetWriteOffRules#taxWriteOffCutDate}.
 *
 * <p><b>Защо null, а не ред с нула.</b> Ред с нулева амортизация би влязъл в ДАП с датата на
 * отписване и справката по чл. 52 щеше да показва месец, в който амортизация не се начислява —
 * тоест щеше да твърди обратното на чл. 60 ал. 2. Липсата на ред е самото твърдение.
 *
 * <p><b>Чл. 60 ал. 3 т. 1.</b> Когато активът е напълно амортизиран за СЧЕТОВОДНИ цели, ал. 2 не се
 * прилага: активът остава в ДАП и се отписва оттам по реда на ал. 1, тоест при пълно данъчно
 * амортизиране (ал. 4). И в този случай пропорционален ред не се строи — но бордът не се отрязва.
 *
 * <p><b>Заварените активи.</b> Без българска данъчна норма нищо не се сменя: активът минава по
 * базовия път, защото тогава планът му не е построен по чл. 55 и чл. 60 не му е мярка.
 */
public class BgFixedAssetLineTaxServiceImpl extends FixedAssetLineFiscalServiceImpl {

  @Inject
  public BgFixedAssetLineTaxServiceImpl(
      FixedAssetLineRepository fixedAssetLineRepository,
      FixedAssetDerogatoryLineService fixedAssetDerogatoryLineService,
      YearService yearService,
      PeriodService periodService,
      FixedAssetLineFiscalComputationServiceImpl fixedAssetLineFiscalComputationService,
      CurrencyScaleService currencyScaleService) {
    super(
        fixedAssetLineRepository,
        fixedAssetDerogatoryLineService,
        yearService,
        periodService,
        fixedAssetLineFiscalComputationService,
        currencyScaleService);
  }

  @Override
  public FixedAssetLine generateProrataDepreciationLine(
      FixedAsset fixedAsset, LocalDate disposalDate) throws AxelorException {
    if (!BgTaxDepreciation.hasTaxRate(fixedAsset)) {
      return super.generateProrataDepreciationLine(fixedAsset, disposalDate);
    }
    return null;
  }
}
