package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.account.service.fixedasset.FixedAssetDateServiceImpl;
import com.axelor.apps.base.service.PeriodService;
import com.axelor.apps.base.service.YearService;
import com.google.inject.Inject;
import java.time.LocalDate;

/**
 * Началото на данъчното амортизиране по чл. 58 ал. 1 ЗКПО.
 *
 * <p>Законът дава ИЗБОР: „от началото на месеца, в който данъчният амортизируем актив е въведен в
 * експлоатация, или от началото на следващия месец“. Изборът стои на актива като {@code
 * bgTaxStartSelect} и се прилага САМО върху данъчната дата — икономическата и IFRS датите остават
 * базови.
 *
 * <p><b>Защо тук, а не в изчислението на плана.</b> Датата се показва на екрана като {@code
 * fiscalFirstDepreciationDate} и се проверява от {@code
 * action-fixed-asset-validate-check-fiscal-first-depreciation-date}. Ако месецът се местеше вътре в
 * {@code computeStartDepreciationDate}, формата щеше да твърди един месец, а планът да тръгва от
 * друг — разминаване, което никой не вижда, докато не сравни ДАП-а с полето над него.
 *
 * <p><b>Защо се преизчислява от отправната дата, а не се добавя месец към готовия резултат.</b>
 * Базата връща КРАЯ на счетоводния период (виж {@code computeLastDayOfFiscalPeriod}), а {@code
 * LocalDate.plusMonths} върху край на месец не дава край на месец: 30 април + 1 месец е 30 май, не
 * 31 май. Затова месецът се добавя към ВХОДА и краят на периода се намира пак през базовия помощник
 * — така и нестандартни счетоводни периоди излизат верни.
 */
public class BgFixedAssetDateServiceImpl extends FixedAssetDateServiceImpl {

  @Inject
  public BgFixedAssetDateServiceImpl(PeriodService periodService, YearService yearService) {
    super(periodService, yearService);
  }

  @Override
  public void computeFiscalFirstDepreciationDate(FixedAsset fixedAsset) {
    super.computeFiscalFirstDepreciationDate(fixedAsset);

    if (fixedAsset == null
        || !BgTaxDepreciationStart.NEXT_MONTH.equals(fixedAsset.getBgTaxStartSelect())) {
      return;
    }
    LocalDate reference = fiscalReferenceDate(fixedAsset);
    if (reference == null) {
      return;
    }
    fixedAsset.setFiscalFirstDepreciationDate(
        computeFirstDepreciationDate(
            fixedAsset.getCompany(),
            reference.plusMonths(1),
            fixedAsset.getFiscalPeriodicityTypeSelect()));
  }

  /**
   * Отправната дата за данъчния план: датата на въвеждане в експлоатация, а при липса — датата на
   * придобиване.
   *
   * <p>Преписана от {@code FixedAssetDateServiceImpl.computeFiscalFirstDepreciationDate}, защото
   * базата я изчислява вградено и не я излага като метод. Държи се съзнателно едно към едно с нея:
   * разминаване тук би значело, че данъчният план тръгва от месец, различен от този, който базата
   * би избрала — тоест грешка, която изглежда като избор по чл. 58.
   */
  protected LocalDate fiscalReferenceDate(FixedAsset fixedAsset) {
    return fixedAsset.getFiscalFirstDepreciationDateInitSelect()
                == FixedAssetRepository.REFERENCE_FIRST_DEPRECIATION_FIRST_SERVICE_DATE
            && fixedAsset.getFirstServiceDate() != null
        ? fixedAsset.getFirstServiceDate()
        : fixedAsset.getAcquisitionDate();
  }
}
