package com.axelor.apps.bglocalization.service.accounting;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.move.MoveCreateService;
import com.axelor.apps.account.service.move.MoveReverseService;
import com.axelor.apps.account.service.move.MoveValidateService;
import com.axelor.apps.account.service.moveline.MoveLineCreateService;
import com.axelor.apps.account.service.reconcile.foreignexchange.ForeignExchangeGapServiceImpl;
import com.axelor.apps.account.service.reconcile.foreignexchange.ForeignExchangeGapToolService;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.service.CurrencyScaleService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.google.inject.Inject;
import java.math.BigDecimal;

/**
 * СТАТИЯТА ЗА КУРСОВА РАЗЛИКА С ОПИСАНИЕ НА РЕДОВЕТЕ.
 *
 * <p>Axelor съставя статията за курсова разлика при лентиране на валутно плащане с редове без
 * описание и без произход ({@code ForeignExchangeGapServiceImpl#miscOperationMoveCreation}). BG_COA
 * изисква описание на всеки ред ({@code AccountConfig.isDescriptionRequired = true}), тоест всяко
 * плащане по валутна фактура с курс, различен от курса на фактурата, падаше с „Description is
 * missing on move line(s)“. Тук двата реда получават описание „Курсова разлика по фактура №…“ и
 * произход — номера на фактурата; сметките и сумите са тези на Axelor.
 */
public class BgForeignExchangeGapServiceImpl extends ForeignExchangeGapServiceImpl {

  @Inject
  public BgForeignExchangeGapServiceImpl(
      AccountConfigService accountConfigService,
      MoveLineCreateService moveLineCreateService,
      MoveCreateService moveCreateService,
      MoveValidateService moveValidateService,
      MoveReverseService moveReverseService,
      AppBaseService appBaseService,
      ForeignExchangeGapToolService foreignExchangeGapToolService,
      CurrencyScaleService currencyScaleService) {
    super(
        accountConfigService,
        moveLineCreateService,
        moveCreateService,
        moveValidateService,
        moveReverseService,
        appBaseService,
        foreignExchangeGapToolService,
        currencyScaleService);
  }

  @Override
  protected void miscOperationMoveCreation(
      Move move, Partner partner, Account account, BigDecimal amount, boolean isDebit, int ref)
      throws AxelorException {
    MoveLine line =
        moveLineCreateService.createMoveLine(
            move,
            partner,
            account,
            amount,
            isDebit,
            move.getDate(),
            ref,
            origin(move),
            description(move));
    move.addMoveLineListItem(line);
  }

  static String origin(Move move) {
    Invoice invoice = move.getInvoice();
    return invoice == null ? null : invoice.getInvoiceId();
  }

  static String description(Move move) {
    Invoice invoice = move.getInvoice();
    if (invoice == null || invoice.getInvoiceId() == null) {
      return "Курсова разлика";
    }
    String number =
        invoice.getSupplierInvoiceNb() != null && !invoice.getSupplierInvoiceNb().isEmpty()
            ? invoice.getSupplierInvoiceNb()
            : invoice.getInvoiceId();
    return "Курсова разлика по фактура № " + number;
  }
}
