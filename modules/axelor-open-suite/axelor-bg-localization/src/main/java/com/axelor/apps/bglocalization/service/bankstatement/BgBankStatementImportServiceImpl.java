package com.axelor.apps.bglocalization.service.bankstatement;

import com.axelor.apps.bankpayment.db.BankStatement;
import com.axelor.apps.bankpayment.db.BankStatementFileFormat;
import com.axelor.apps.bankpayment.service.bankstatement.BankStatementImportService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.inject.Beans;
import java.io.IOException;

/**
 * Extends the stock {@link BankStatementImportService} so the standard Bank Statement "Import"
 * button also handles the Bulgarian Postbank XLSX format. When the chosen file format is the BG
 * Postbank one, the file is routed to {@link BgBankStatementImportService}; every other format
 * keeps the stock behaviour. Bound over the stock service in {@code BgLocalizationModule}.
 */
public class BgBankStatementImportServiceImpl extends BankStatementImportService {

  @Override
  public void runImport(BankStatement bankStatement, boolean alertIfFormatNotSupported)
      throws IOException, AxelorException {
    BankStatementFileFormat format = bankStatement.getBankStatementFileFormat();
    if (format != null
        && BgBankStatementImportService.FORMAT_SELECT.equals(
            format.getStatementFileFormatSelect())) {
      try {
        Beans.get(BgBankStatementImportService.class).importInto(bankStatement.getId());
      } catch (IOException | AxelorException e) {
        throw e;
      } catch (Exception e) {
        throw new AxelorException(
            TraceBackRepository.CATEGORY_INCONSISTENCY,
            "%s",
            e.getMessage() == null ? e.toString() : e.getMessage());
      }
      return;
    }
    super.runImport(bankStatement, alertIfFormatNotSupported);
  }
}
