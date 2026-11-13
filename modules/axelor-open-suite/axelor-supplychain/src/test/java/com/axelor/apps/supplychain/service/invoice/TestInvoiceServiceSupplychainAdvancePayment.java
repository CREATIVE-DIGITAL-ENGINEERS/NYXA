package com.axelor.apps.supplychain.service.invoice;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.invoice.InvoiceServiceImpl;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.purchase.db.PurchaseOrder;
import com.axelor.apps.sale.db.SaleOrder;
import com.axelor.apps.supplychain.service.app.AppSupplychainService;
import com.axelor.db.Query;
import com.axelor.inject.Beans;
import com.google.inject.Guice;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * The advance payment invoices proposed for an invoice coming from an order: every named parameter
 * of the query must be bound. The purchase order filter uses {@code :_purchaseOrderList} but the
 * value used to be bound as {@code _purchaseOrder}, so an invoice from a purchase order failed when
 * the advance payments were looked up (Nyxa PR 2 of the advance payment work).
 */
class TestInvoiceServiceSupplychainAdvancePayment {

  private static final Pattern PARAMETER = Pattern.compile(":(\\w+)");

  private String filter;
  private final Set<String> bound = new LinkedHashSet<>();

  @BeforeAll
  static void guice() {
    AppSupplychainService app = mock(AppSupplychainService.class);
    when(app.isApp("supplychain")).thenReturn(true);
    Guice.createInjector(
        binder -> {
          binder.bind(AppSupplychainService.class).toInstance(app);
          binder.bind(Beans.class).asEagerSingleton();
        });
  }

  @SuppressWarnings("unchecked")
  private InvoiceServiceSupplychainImpl service() throws Exception {
    Query<Invoice> query = mock(Query.class);
    when(query.filter(anyString()))
        .thenAnswer(
            call -> {
              filter = call.getArgument(0);
              return query;
            });
    when(query.bind(anyString(), any()))
        .thenAnswer(
            call -> {
              bound.add(call.getArgument(0));
              return query;
            });
    when(query.fetch()).thenReturn(new ArrayList<>());
    InvoiceRepository invoiceRepo = mock(InvoiceRepository.class);
    when(invoiceRepo.all()).thenReturn(query);

    AccountConfig config = new AccountConfig();
    config.setGenerateMoveForInvoicePayment(true);
    AccountConfigService accountConfigService = mock(AccountConfigService.class);
    when(accountConfigService.getAccountConfig(any())).thenReturn(config);

    InvoiceServiceSupplychainImpl service =
        mock(
            InvoiceServiceSupplychainImpl.class,
            withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
    set(service, "invoiceRepo", invoiceRepo);
    set(service, "accountConfigService", accountConfigService);
    return service;
  }

  private static void set(Object target, String name, Object value) throws Exception {
    Field field = InvoiceServiceImpl.class.getDeclaredField(name);
    field.setAccessible(true);
    field.set(target, value);
  }

  private Set<String> parameters() {
    Set<String> names = new LinkedHashSet<>();
    Matcher m = PARAMETER.matcher(filter);
    while (m.find()) {
      names.add(m.group(1));
    }
    return names;
  }

  private static Invoice invoice(int operationType) {
    Company company = new Company();
    company.setId(1L);
    Invoice invoice = new Invoice();
    invoice.setCompany(company);
    invoice.setCurrency(new Currency());
    invoice.setOperationTypeSelect(operationType);
    invoice.setStockMoveSet(Collections.emptySet());
    return invoice;
  }

  @Test
  void purchaseOrderAdvancePaymentQueryBindsEveryParameter() throws Exception {
    Invoice invoice = invoice(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    PurchaseOrder order = new PurchaseOrder();
    order.setId(7L);
    invoice.setPurchaseOrder(order);

    service().getDefaultAdvancePaymentInvoice(invoice);

    Assertions.assertTrue(filter.contains(":_purchaseOrderList"), filter);
    Assertions.assertEquals(parameters(), bound, filter);
  }

  @Test
  void saleOrderAdvancePaymentQueryBindsEveryParameter() throws Exception {
    Invoice invoice = invoice(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    SaleOrder order = new SaleOrder();
    order.setId(8L);
    invoice.setSaleOrder(order);

    service().getDefaultAdvancePaymentInvoice(invoice);

    Assertions.assertTrue(filter.contains(":_saleOrderList"), filter);
    Assertions.assertEquals(parameters(), bound, filter);
  }
}
