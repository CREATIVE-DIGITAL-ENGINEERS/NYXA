package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.ProductFamily;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.db.BgStockCostLayer;
import com.axelor.apps.bglocalization.db.BgStockCostingFamilyOverride;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import com.axelor.apps.bglocalization.module.BgStockWiringTestModule;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockLocationLine;
import com.axelor.apps.stock.db.TrackingNumber;
import com.axelor.apps.stock.db.TrackingNumberConfiguration;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import javax.persistence.PersistenceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Конкретно определена стойност (СС 2 т. 9.1) — правилата на ПОЛИТИКАТА, срещу истинска база: отказ
 * за семейство без проследяване (правило 3), отказ при активиране с наличност без партида (правило
 * 4) и началните слоеве по партиди.
 *
 * <p>Конкретната стойност е изключение за едно семейство, а методът по подразбиране е средна — така
 * проверката обхваща само продуктите на това семейство, не всички продукти в базата на теста.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgStockWiringTestModule.class)
@Tag("integration")
class BgStockCostingSpecificPolicyIntegrationTest extends JpaSupport {

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  static final class Fixture {
    Company company;
    Year year;
    ProductFamily family;
    Product product;
    StockLocation warehouse;
  }

  private static Fixture fixture(String tag, boolean tracked) {
    return tx(
        () -> {
          if (Beans.get(com.axelor.studio.db.repo.AppBaseRepository.class).all().fetchOne()
              == null) {
            // Празно множество, а не null: ProductCompanyService го обхожда (в приложението редът
            // идва от базата и колекцията никога не е null).
            com.axelor.studio.db.AppBase appBase = new com.axelor.studio.db.AppBase();
            appBase.setCompanySpecificProductFieldsSet(new java.util.HashSet<>());
            JPA.save(appBase);
          }
          Fixture f = new Fixture();
          Currency currency = new Currency();
          currency.setName("Евро " + tag);
          currency.setCode("€");
          currency.setCodeISO("K" + tag);
          currency = JPA.save(currency);
          Company company = new Company();
          company.setName("Партиди " + tag);
          company.setCode("K-" + tag);
          company.setCurrency(currency);
          f.company = JPA.save(company);
          Year year = new Year();
          year.setName("2026 " + tag);
          year.setCode("FY-K" + tag);
          year.setCompany(f.company);
          year.setTypeSelect(1);
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setStatusSelect(1);
          f.year = JPA.save(year);
          ProductFamily family = new ProductFamily();
          family.setCode("LOTS-" + tag);
          family.setName("Партидни " + tag);
          family.setBgSaftInventoryType("30");
          f.family = JPA.save(family);
          Product product = new Product();
          product.setCode("PK-" + tag);
          product.setName("Стока с партиди " + tag);
          product.setProductTypeSelect("storable");
          product.setProductFamily(f.family);
          if (tracked) {
            TrackingNumberConfiguration cfg = new TrackingNumberConfiguration();
            cfg.setName("Партиди " + tag);
            product.setTrackingNumberConfiguration(JPA.save(cfg));
          }
          f.product = JPA.save(product);
          StockLocation w = new StockLocation();
          w.setCompany(f.company);
          w.setName("Склад " + tag);
          w.setTypeSelect(1);
          f.warehouse = JPA.save(w);
          return f;
        });
  }

  private static BgStockCostingPolicy policyWithSpecificFamily(Fixture f) {
    BgStockCostingPolicy p = new BgStockCostingPolicy();
    p.setCompany(JPA.find(Company.class, f.company.getId()));
    p.setYear(JPA.find(Year.class, f.year.getId()));
    p.setCostingMethodSelect(BgStockCostingRules.WAP_PERPETUAL);
    p.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);
    p.setPostingModeSelect(BgStockCostingRules.POSTING_CURRENT);
    BgStockCostingFamilyOverride o = new BgStockCostingFamilyOverride();
    o.setProductFamily(JPA.find(ProductFamily.class, f.family.getId()));
    o.setCostingMethodSelect(BgStockCostingRules.SPECIFIC);
    p.addFamilyOverrideListItem(o);
    return p;
  }

  private static TrackingNumber lot(Fixture f, String seq) {
    return tx(
        () -> {
          TrackingNumber t = new TrackingNumber();
          t.setTrackingNumberSeq(seq);
          t.setProduct(JPA.find(Product.class, f.product.getId()));
          return JPA.save(t);
        });
  }

  /** Основният ред на наличността и детайлните по партиди. */
  private static void stock(Fixture f, String qty, String avg, Object... lotsAndQty) {
    tx(
        () -> {
          StockLocation w = JPA.find(StockLocation.class, f.warehouse.getId());
          Product p = JPA.find(Product.class, f.product.getId());
          StockLocationLine main = new StockLocationLine();
          main.setStockLocation(w);
          main.setProduct(p);
          main.setCurrentQty(d(qty));
          main.setAvgPrice(d(avg));
          JPA.save(main);
          for (int i = 0; i < lotsAndQty.length; i += 2) {
            StockLocationLine detail = new StockLocationLine();
            detail.setDetailsStockLocation(w);
            detail.setProduct(p);
            detail.setTrackingNumber(
                JPA.find(TrackingNumber.class, ((TrackingNumber) lotsAndQty[i]).getId()));
            detail.setCurrentQty(d((String) lotsAndQty[i + 1]));
            JPA.save(detail);
          }
          return null;
        });
  }

  @Test
  @DisplayName(
      "правило 3: конкретна стойност за семейство без проследяване — политиката се отказва")
  void семейство_без_проследяване() {
    Fixture f = fixture("NT", false);
    PersistenceException e =
        assertThrows(
            PersistenceException.class,
            () ->
                tx(
                    () ->
                        Beans.get(BgStockCostingPolicyRepository.class)
                            .save(policyWithSpecificFamily(f))));
    assertTrue(
        e.getMessage().contains("PK-NT") && e.getMessage().contains("проследяване"),
        e.getMessage());
  }

  @Test
  @DisplayName("правило 3: семейство с проследяване — политиката се записва")
  void семейство_с_проследяване() {
    Fixture f = fixture("TR", true);
    BgStockCostingPolicy saved =
        tx(() -> Beans.get(BgStockCostingPolicyRepository.class).save(policyWithSpecificFamily(f)));
    assertTrue(saved.getId() != null);
  }

  @Test
  @DisplayName("правило 4: наличност без партида → активирането се отказва със списък")
  void наличност_без_партида() {
    Fixture f = fixture("UD", true);
    TrackingNumber l1 = lot(f, "UD-1");
    stock(f, "10", "5.00", l1, "6");
    BgStockCostingPolicy policy =
        tx(() -> Beans.get(BgStockCostingPolicyRepository.class).save(policyWithSpecificFamily(f)));
    AxelorException e =
        assertThrows(
            AxelorException.class,
            () ->
                Beans.get(BgStockCostingPolicyService.class)
                    .activate(tx(() -> JPA.find(BgStockCostingPolicy.class, policy.getId()))));
    assertTrue(
        e.getMessage().contains("PK-UD")
            && e.getMessage().contains("Склад UD")
            && e.getMessage().contains("4"),
        e.getMessage());
    assertEquals(
        BgStockCostingPolicyRepository.STATUS_DRAFT,
        (int) tx(() -> JPA.find(BgStockCostingPolicy.class, policy.getId()).getStatusSelect()),
        "политиката остава чернова");
  }

  @Test
  @DisplayName("наличност, разпределена по партиди → начален слой за всяка партида по средната")
  void началните_слоеве_по_партиди() throws AxelorException {
    Fixture f = fixture("OP", true);
    TrackingNumber l1 = lot(f, "OP-1");
    TrackingNumber l2 = lot(f, "OP-2");
    stock(f, "10", "5.00", l1, "6", l2, "4");
    BgStockCostingPolicy policy =
        tx(() -> Beans.get(BgStockCostingPolicyRepository.class).save(policyWithSpecificFamily(f)));
    Beans.get(BgStockCostingPolicyService.class)
        .activate(tx(() -> JPA.find(BgStockCostingPolicy.class, policy.getId())));
    List<BgStockCostLayer> layers =
        tx(
            () ->
                JPA.all(BgStockCostLayer.class)
                    .filter("self.company.id = ?1", f.company.getId())
                    .order("trackingNumber.trackingNumberSeq")
                    .fetch());
    assertEquals(2, layers.size());
    assertEquals(l1.getId(), layers.get(0).getTrackingNumber().getId());
    assertEquals(d("30.00"), layers.get(0).getStockValue(), "6 × 5,00");
    assertEquals(l2.getId(), layers.get(1).getTrackingNumber().getId());
    assertEquals(d("20.00"), layers.get(1).getStockValue(), "4 × 5,00");
  }
}
