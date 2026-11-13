package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.account.db.repo.MoveLineRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.service.FindFixedAssetService;
import com.axelor.apps.account.service.fixedasset.FixedAssetDateService;
import com.axelor.apps.account.service.fixedasset.FixedAssetDerogatoryLineMoveService;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineMoveServiceImpl;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineService;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineComputeAnalyticService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.BatchRepository;
import com.axelor.apps.base.service.BankDetailsService;
import com.axelor.apps.base.service.CurrencyScaleService;
import com.axelor.apps.base.service.tax.AccountManagementService;
import com.google.inject.Inject;
import java.math.BigDecimal;

/**
 * АМОРТИЗАЦИЯТА В МЕСЕЦА НА ИЗВЕЖДАНЕ Е ПРОПОРЦИОНАЛНИЯТ РЕД, НЕ ПРОДАЖНАТА ЦЕНА.
 *
 * <p>При извеждане AOS строи пропорционален ред до датата ({@code generateProrataDepreciationLine})
 * и го реализира с {@code isDisposal = true}. {@code FixedAssetLineMoveServiceImpl#generateMove}
 * тогава взима сумата на статията Дт 603 / Кт 241 от {@code fixedAsset.getDisposalValue()} —
 * продажната цена — вместо от амортизацията на реда. На „Демо Търговия“: обзавеждане, продадено на
 * 22.09 за 5 200,00, пропорционален ред 73,33 → статия 5 200,00; ОПР показва амортизации 14 020,00
 * при план 8 893,33, а 241 се разминава с регистъра точно с 5 126,67. При брак (стойност 0)
 * статията е нулева.
 *
 * <p>Флагът {@code isDisposal} остава (той пази датата на реда вместо края на месеца); подменя се
 * само сумата — продажната цена се връща на актива веднага след статията.
 */
public class BgFixedAssetLineMoveServiceImpl extends FixedAssetLineMoveServiceImpl {

  @Inject
  public BgFixedAssetLineMoveServiceImpl(
      FixedAssetLineRepository fixedAssetLineRepo,
      MoveCreateService moveCreateService,
      MoveRepository moveRepo,
      MoveLineRepository moveLineRepo,
      FixedAssetDerogatoryLineMoveService fixedAssetDerogatoryLineMoveService,
      FixedAssetRepository fixedAssetRepo,
      MoveLineComputeAnalyticService moveLineComputeAnalyticService,
      FixedAssetLineService fixedAssetLineService,
      MoveValidateService moveValidateService,
      MoveLineCreateService moveLineCreateService,
      BatchRepository batchRepository,
      BankDetailsService bankDetailsService,
      FixedAssetDateService fixedAssetDateService,
      CurrencyScaleService currencyScaleService,
      FindFixedAssetService findFixedAssetService,
      AccountManagementService accountManagementService) {
    super(
        fixedAssetLineRepo,
        moveCreateService,
        moveRepo,
        moveLineRepo,
        fixedAssetDerogatoryLineMoveService,
        fixedAssetRepo,
        moveLineComputeAnalyticService,
        fixedAssetLineService,
        moveValidateService,
        moveLineCreateService,
        batchRepository,
        bankDetailsService,
        fixedAssetDateService,
        currencyScaleService,
        findFixedAssetService,
        accountManagementService);
  }

  @Override
  public Move generateMove(FixedAssetLine fixedAssetLine, boolean isSimulated, boolean isDisposal)
      throws AxelorException {
    FixedAsset fixedAsset = isDisposal ? findFixedAssetService.getFixedAsset(fixedAssetLine) : null;
    if (fixedAsset == null) {
      return super.generateMove(fixedAssetLine, isSimulated, isDisposal);
    }
    BigDecimal disposalValue = fixedAsset.getDisposalValue();
    fixedAsset.setDisposalValue(depreciationOf(fixedAssetLine));
    try {
      return super.generateMove(fixedAssetLine, isSimulated, true);
    } finally {
      fixedAsset.setDisposalValue(disposalValue);
    }
  }

  /** Генерираният getter връща нула, не null (BgDefaultedGetterTest). */
  static BigDecimal depreciationOf(FixedAssetLine line) {
    return line.getDepreciation();
  }
}
