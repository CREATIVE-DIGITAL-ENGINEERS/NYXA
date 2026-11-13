package com.axelor.apps.aiocr.module;

import com.axelor.app.AppModule;
import com.axelor.apps.account.module.AccountModule;
import com.axelor.apps.bankpayment.module.BankPaymentModule;
import com.axelor.apps.base.module.BaseModule;
import com.axelor.apps.budget.module.BudgetModule;
import com.axelor.apps.businessproject.module.BusinessProjectModule;
import com.axelor.apps.contract.module.ContractModule;
import com.axelor.apps.crm.module.CrmModule;
import com.axelor.apps.hr.module.HumanResourceModule;
import com.axelor.apps.purchase.module.PurchaseModule;
import com.axelor.apps.sale.module.SaleModule;
import com.axelor.apps.supplychain.module.SupplychainModule;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.google.inject.AbstractModule;

/**
 * DB-backed test wiring for axelor-ai-ocr. Installs the full Invoice repository binding chain
 * (Account → … → Budget) plus {@link AiOcrModule} so {@code InvoiceRepository} resolves to {@code
 * OcrInvoiceRepository} exactly as at runtime.
 */
public class AiOcrTestModule extends AbstractModule {
  @Override
  protected void configure() {
    install(new JpaModule("testUnit", true, true));
    install(new BaseModule());
    install(new AccountModule());
    install(new BankPaymentModule());
    install(new BusinessProjectModule());
    install(new ContractModule());
    install(new HumanResourceModule());
    install(new SupplychainModule());
    install(new SaleModule());
    install(new PurchaseModule());
    install(new AuthModule());
    install(new AppModule());
    install(new CrmModule());
    install(new BudgetModule());
    install(new AiOcrModule());
  }
}
