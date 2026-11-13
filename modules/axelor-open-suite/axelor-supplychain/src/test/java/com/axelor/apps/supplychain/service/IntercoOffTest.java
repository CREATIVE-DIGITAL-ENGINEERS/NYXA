package com.axelor.apps.supplychain.service;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.purchase.db.PurchaseOrder;
import com.axelor.apps.sale.db.SaleOrder;
import com.axelor.apps.supplychain.service.app.AppSupplychainService;
import com.axelor.apps.supplychain.service.saleorder.SaleOrderIntercoServiceImpl;
import com.axelor.studio.db.AppSupplychain;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Interco is off: a document of one company never names another company as its counterpart, and
 * nothing is generated in another company, even with the app settings switched on and a partner
 * that is another company's own partner.
 */
class IntercoOffTest {

  private IntercoServiceImpl intercoService;
  private Partner otherCompanyPartner;

  @BeforeEach
  void prepare() {
    // Real methods without the application: anything that would look further than the switch
    // fails here instead of passing.
    intercoService = mock(IntercoServiceImpl.class, CALLS_REAL_METHODS);
    Company other = new Company("Other company", "OTHER");
    other.setId(2L);
    otherCompanyPartner = new Partner();
    otherCompanyPartner.setId(102L);
    other.setPartner(otherCompanyPartner);
  }

  @Test
  void noCompanyIsTheIntercoCounterpart() {
    Assertions.assertNull(intercoService.findIntercoCompany(otherCompanyPartner));
  }

  @Test
  void nothingIsGeneratedInAnotherCompany() throws AxelorException {
    PurchaseOrder purchaseOrder = new PurchaseOrder();
    purchaseOrder.setSupplierPartner(otherCompanyPartner);
    purchaseOrder.setInterco(true);
    Assertions.assertNull(intercoService.generateIntercoSaleFromPurchase(purchaseOrder));

    SaleOrder saleOrder = new SaleOrder();
    saleOrder.setClientPartner(otherCompanyPartner);
    saleOrder.setInterco(true);
    Assertions.assertNull(intercoService.generateIntercoPurchaseFromSale(saleOrder));

    Invoice invoice = new Invoice();
    invoice.setPartner(otherCompanyPartner);
    invoice.setInterco(true);
    Assertions.assertNull(intercoService.generateIntercoInvoice(invoice));
  }

  @Test
  void saleOrderIsNeverInterco() throws AxelorException {
    AppSupplychain app = new AppSupplychain();
    app.setIntercoFromSale(true);
    AppSupplychainService appService = mock(AppSupplychainService.class);
    when(appService.getAppSupplychain()).thenReturn(app);
    SaleOrder saleOrder = new SaleOrder();
    saleOrder.setClientPartner(otherCompanyPartner);

    new SaleOrderIntercoServiceImpl(appService, intercoService).getInterco(saleOrder);

    Assertions.assertFalse(saleOrder.getInterco());
  }
}
