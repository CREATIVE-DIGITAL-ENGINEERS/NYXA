package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.axelor.apps.bglocalization.service.accounting.BgAccountingCurrencyPolicy;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Entry;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.ExportRoot;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingSourceModels.Line;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Set;

final class MicroinvestFxPolicy {
  private MicroinvestFxPolicy() {}

  static boolean ambiguous(Entry entry, ExportRoot root) {
    Set<String> currencies = new HashSet<>();
    for (Line line : entry.lines) {
      if (line.currencyDetail != null
          && line.currencyDetail.currency != null
          && !MicroinvestAccountingSourceModels.isNonTransactionalCurrency(
              line.currencyDetail.currency, root)) {
        currencies.add(line.currencyDetail.currency);
        if (entry.currency == null
            || !line.currencyDetail.currency.equals(entry.currency)
            || entry.exchangeRate == null
            || entry.exchangeRate.signum() <= 0
            || line.currencyDetail.exchangeRate == null
            || line.currencyDetail.exchangeRate.compareTo(entry.exchangeRate) != 0
            || unsupportedRedenominationRate(line.currencyDetail, root)) {
          return true;
        }
      }
    }
    return entry.mixedCurrency || currencies.size() > 1;
  }

  private static boolean unsupportedRedenominationRate(
      MicroinvestAccountingSourceModels.CurrencyDetail detail, ExportRoot root) {
    if (!"EUR".equals(root.financialYear.baseCurrency) || !"BGN".equals(detail.currency)) {
      return false;
    }
    BigDecimal rate = detail.exchangeRate;
    if (rate == null) {
      return true;
    }
    BigDecimal inverse =
        BigDecimal.ONE.divide(BgAccountingCurrencyPolicy.BGN_PER_EUR, 6, RoundingMode.HALF_UP);
    BigDecimal tolerance = new BigDecimal("0.001");
    return rate.subtract(BgAccountingCurrencyPolicy.BGN_PER_EUR).abs().compareTo(tolerance) > 0
        && rate.subtract(inverse).abs().compareTo(tolerance) > 0;
  }
}
