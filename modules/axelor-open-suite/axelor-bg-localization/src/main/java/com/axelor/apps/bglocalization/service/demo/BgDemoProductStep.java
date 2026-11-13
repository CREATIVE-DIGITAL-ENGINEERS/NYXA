package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.AccountManagement;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.repo.AccountManagementRepository;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.ProductFamily;
import com.axelor.apps.base.db.Unit;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.util.Collections;
import java.util.HashSet;

/**
 * СТОКИТЕ И УСЛУГИТЕ — {@code products.csv}: {@code
 * code;name;family;type;unit;salePrice;purchasePrice;saleTax;purchaseTax}.
 *
 * <p>⚠️ Продуктът в Axelor (и в Nyxa) е ОБЩ — няма дружество. За да не се смесят двата набора (и
 * второ наливане в друго празно дружество), кодът получава префикс — кода на дружеството: {@code
 * DEMOT-P01}. Сметките и данъците идват от семейството (BG_COA: GOODS → 702/304, SERV → 703/602…,
 * BOOK9 → 9 %), както при ръчно въведен продукт.
 */
final class BgDemoProductStep {

  private BgDemoProductStep() {}

  private static Tax tax(BgDemoCsv.Row r, String column) {
    Tax t = JPA.all(Tax.class).filter("self.code = ?1", r.get(column)).fetchOne();
    if (t == null) {
      throw new IllegalArgumentException(r.where() + ": няма данък " + r.get(column));
    }
    return t;
  }

  static void run(BgDemoContext ctx) {
    String prefix = ctx.company().getCode() + "-";
    int n = 0;
    for (BgDemoCsv.Row r : ctx.rows("products")) {
      JPA.runInTransaction(() -> product(ctx, prefix, r));
      n++;
    }
    ctx.log("Стоки и услуги: " + n);
  }

  private static void product(BgDemoContext ctx, String prefix, BgDemoCsv.Row r) {
    Product p = new Product();
    p.setCode(prefix + r.get("code"));
    p.setName(r.get("name"));
    p.setProductFamily(
        JPA.all(ProductFamily.class).filter("self.code = ?1", r.get("family")).fetchOne());
    if (p.getProductFamily() == null) {
      throw new IllegalArgumentException(r.where() + ": няма семейство " + r.get("family"));
    }
    boolean storable = "storable".equals(r.get("type"));
    p.setProductTypeSelect(
        storable
            ? ProductRepository.PRODUCT_TYPE_STORABLE
            : ProductRepository.PRODUCT_TYPE_SERVICE);
    p.setUnit(JPA.all(Unit.class).filter("self.name = ?1", r.get("unit")).fetchOne());
    if (p.getUnit() == null) {
      throw new IllegalArgumentException(r.where() + ": няма мерна единица " + r.get("unit"));
    }
    p.setSalePrice(r.decimal("salePrice"));
    p.setPurchasePrice(r.decimal("purchasePrice"));
    p.setCostPrice(r.decimal("purchasePrice"));
    p.setSellable(true);
    p.setPurchasable(true);
    p.setSaleCurrency(ctx.company().getCurrency());
    p.setPurchaseCurrency(ctx.company().getCurrency());
    p.setProcurementMethodSelect(ProductRepository.PROCUREMENT_METHOD_BUY);
    p = Beans.get(ProductRepository.class).save(p);
    ctx.put("product", r.get("code"), p);

    // Данъците — в счетоводния раздел на стоката, за дружеството (както потребителят ги задава).
    // ⚠️ Семействата на BG_COA носят saleTax_code/purchaseTax_code в CSV-то, но chart-config.xml
    // не ги връзва — без това всеки ред би паднал с „Tax configuration is missing“.
    AccountManagement am = new AccountManagement();
    am.setTypeSelect(AccountManagementRepository.TYPE_PRODUCT);
    am.setProduct(p);
    am.setCompany(ctx.company());
    am.setSaleTaxSet(new HashSet<>(Collections.singleton(tax(r, "saleTax"))));
    am.setPurchaseTaxSet(new HashSet<>(Collections.singleton(tax(r, "purchaseTax"))));
    p.addAccountManagementListItem(am);
    JPA.save(am);
  }
}
