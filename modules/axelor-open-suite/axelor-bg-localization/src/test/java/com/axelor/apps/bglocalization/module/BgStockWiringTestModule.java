package com.axelor.apps.bglocalization.module;

import com.axelor.app.AppModule;
import com.axelor.apps.account.module.AccountModule;
import com.axelor.apps.bankpayment.module.BankPaymentModule;
import com.axelor.apps.base.module.BaseModule;
import com.axelor.apps.bglocalization.service.stock.BgStockMoveRealizedHandler;
import com.axelor.apps.crm.module.CrmModule;
import com.axelor.apps.hr.module.HumanResourceModule;
import com.axelor.apps.purchase.module.PurchaseModule;
import com.axelor.apps.sale.module.SaleModule;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.module.StockModule;
import com.axelor.apps.supplychain.module.SupplychainModule;
import com.axelor.auth.AuthModule;
import com.axelor.db.JpaModule;
import com.google.inject.AbstractModule;
import com.google.inject.Singleton;
import com.google.inject.multibindings.Multibinder;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link BgLocalizationProductionWiringTestModule} плюс склада: StockModule и SupplychainModule с
 * модулите, от които зависят. Тук истинският {@link BgLocalizationModule} среща истинската верига
 * на {@code StockMoveService} (stock → supplychain), за да се докаже, че прихващането на складовите
 * движения действа в обекта, който Guice строи — не в ръчно сглобен. axelor-production не е в
 * тестовия classpath; веригата му продължава supplychain и се хваща от същото правило.
 */
public class BgStockWiringTestModule extends AbstractModule {
  @Override
  protected void configure() {
    install(new JpaModule("testUnit", true, true));
    install(new BaseModule());
    install(new AccountModule());
    install(new BankPaymentModule());
    install(new HumanResourceModule());
    install(new CrmModule());
    install(new SaleModule());
    install(new PurchaseModule());
    install(new StockModule());
    install(new SupplychainModule());
    install(new AuthModule());
    install(new AppModule());
    install(new BgLocalizationModule());
    // Обработчик след реализиране, закачен ОТ ДРУГ модул — както ще го закачи ИЗП.
    Multibinder.newSetBinder(binder(), BgStockMoveRealizedHandler.class)
        .addBinding()
        .to(RecordingRealizedHandler.class);
  }

  /** Записва движенията, за които е извикан. */
  @Singleton
  public static class RecordingRealizedHandler implements BgStockMoveRealizedHandler {
    public final List<StockMove> seen = new ArrayList<>();

    @Override
    public void afterRealize(StockMove move) {
      seen.add(move);
    }
  }
}
