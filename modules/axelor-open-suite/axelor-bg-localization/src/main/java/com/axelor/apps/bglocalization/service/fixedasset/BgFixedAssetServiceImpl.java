package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.account.service.fixedasset.FixedAssetDerogatoryLineService;
import com.axelor.apps.account.service.fixedasset.FixedAssetGenerationService;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineGenerationService;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineService;
import com.axelor.apps.account.service.fixedasset.FixedAssetServiceImpl;
import com.axelor.apps.account.service.fixedasset.factory.FixedAssetLineComputationServiceFactory;
import com.axelor.apps.account.service.moveline.MoveLineComputeAnalyticService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.service.CurrencyScaleService;
import com.axelor.apps.base.service.DateService;
import com.google.inject.Inject;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * SPLIT: ОТДЕЛЕНИЯТ АКТИВ ЗНАЕ ОТ КОЙ Е ОТДЕЛЕН (SAF-T A1).
 *
 * <p>Отделеният актив получава нов инвентарен номер — това AOS вече го прави ({@code
 * FixedAssetGenerationServiceImpl.copyFixedAsset}). Връзка към оригинала обаче няма: {@code
 * associatedFixedAssetsSet} е двупосочно M2M, редактируемо във формата, тоест не доказва нищо. Тук
 * всеки отделен актив получава {@code bgSplitFrom} = оригинала; после полето не се мени ({@link
 * BgFixedAssetGuard}).
 *
 * <p><b>Защо {@code updateValuesAfterSplit}.</b> Само той вижда и оригинала, и новите, и е общ за
 * ръчния split и за частичното извеждане (продажба/брак на част). Ключът {@code
 * FixedAssetServiceImpl} е свободен — AOS връзва само интерфейса към него.
 *
 * <p>⚠️ Удвояването на БГ сумите при split (ДАС, НДДС, чл. 59 ал. 2) НЕ се поправя тук; годишният
 * SAF-T дава ГРЕШКА за разделените активи, докато не се поправи.
 */
public class BgFixedAssetServiceImpl extends FixedAssetServiceImpl {

  @Inject
  public BgFixedAssetServiceImpl(
      FixedAssetRepository fixedAssetRepo,
      MoveLineComputeAnalyticService moveLineComputeAnalyticService,
      FixedAssetDerogatoryLineService fixedAssetDerogatoryLineService,
      FixedAssetLineService fixedAssetLineService,
      FixedAssetLineComputationServiceFactory fixedAssetLineComputationServiceFactory,
      FixedAssetGenerationService fixedAssetGenerationService,
      FixedAssetLineGenerationService fixedAssetLineGenerationService,
      DateService dateService,
      CurrencyScaleService currencyScaleService) {
    super(
        fixedAssetRepo,
        moveLineComputeAnalyticService,
        fixedAssetDerogatoryLineService,
        fixedAssetLineService,
        fixedAssetLineComputationServiceFactory,
        fixedAssetGenerationService,
        fixedAssetLineGenerationService,
        dateService,
        currencyScaleService);
  }

  @Override
  protected void updateValuesAfterSplit(
      List<FixedAsset> newFixedAssetList,
      FixedAsset fixedAsset,
      BigDecimal prorata,
      BigDecimal remainingProrata,
      BigDecimal amount,
      BigDecimal newAmount,
      int splitType,
      LocalDate splittingDate,
      String comments)
      throws AxelorException {
    super.updateValuesAfterSplit(
        newFixedAssetList,
        fixedAsset,
        prorata,
        remainingProrata,
        amount,
        newAmount,
        splitType,
        splittingDate,
        comments);
    linkSplit(newFixedAssetList, fixedAsset);
  }

  /** Всеки отделен актив сочи оригинала; самият оригинал — никога себе си. */
  static void linkSplit(List<FixedAsset> separated, FixedAsset original) {
    if (separated == null || original == null) {
      return;
    }
    for (FixedAsset asset : separated) {
      if (asset != null && asset != original) {
        asset.setBgSplitFrom(original);
      }
    }
  }
}
