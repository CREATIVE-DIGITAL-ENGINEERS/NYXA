package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.AccountManagement;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.JournalType;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.ProductFamily;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.service.PeriodService;
import com.axelor.apps.bglocalization.db.BgStockCostLayer;
import com.axelor.apps.bglocalization.db.BgStockCostingAccountRule;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.db.BgStockMoveLineValuation;
import com.axelor.apps.bglocalization.db.BgStockValuationTask;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import com.axelor.apps.bglocalization.db.repo.BgStockValuationTaskRepository;
import com.axelor.apps.bglocalization.module.BgStockWiringTestModule;
import com.axelor.apps.bglocalization.service.accounting.BgAutoPostingUserService;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import com.google.inject.Injector;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ИЗП срещу истинска база (HSQLDB) и истинските връзки на Guice: outbox → процесор → слой → статия.
 *
 * <p>Движенията се записват направо като реализирани (статус 3): реализирането на Axelor и
 * прихващането му доказва {@code BgStockMoveGuardWiringTest}; тук се доказва какво става СЛЕД него.
 *
 * <p>⚠️ Подмени на средата (виж паметта „IT със статии на HSQLDB“): проверката за вече даден номер
 * на статията е изключена в AppBase (SELECT върху Move не върви на HSQLDB заради PostgreSQL
 * формулата {@code Move.ocrUploadIds}); по същата причина статията се чете само през редовете ѝ
 * (MoveLine), не като Move.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgStockWiringTestModule.class)
@Tag("integration")
class BgStockValuationIntegrationTest extends JpaSupport {

  private static final LocalDate MARCH = LocalDate.of(2026, 3, 10);

  private static <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private static BigDecimal d(String v) {
    return new BigDecimal(v);
  }

  /** Всичко за едно дружество: сметки, семейство, продукт, локации, журнал, година, политика. */
  static final class Fixture {
    Company company;
    Product product;
    StockLocation warehouse;
    StockLocation supplier;
    StockLocation customer;
    Account stock;
    Account sale;
    BgStockCostingPolicy policy;
    Period period;
    Journal journal;
  }

  private static void ensureAppConfigs() {
    if (Beans.get(com.axelor.studio.db.repo.AppBaseRepository.class).all().fetchOne() == null) {
      com.axelor.studio.db.AppBase appBase = new com.axelor.studio.db.AppBase();
      appBase.setCheckExistingSequenceOnGeneration(false);
      JPA.save(appBase);
    }
    if (Beans.get(com.axelor.studio.db.repo.AppAccountRepository.class).all().fetchOne() == null) {
      JPA.save(new com.axelor.studio.db.AppAccount());
    }
    // НЯМА потребител „admin“ нарочно: задачата валидира като служебния
    // потребител на дружеството, не като admin — и не пада обратно на него.
  }

  /** Видът на сметката — валидирането на Axelor го чете (MoveLineTaxServiceImpl). */
  private static com.axelor.apps.account.db.AccountType type(String technical) {
    com.axelor.apps.account.db.AccountType t =
        JPA.all(com.axelor.apps.account.db.AccountType.class)
            .filter("self.technicalTypeSelect = ?1", technical)
            .fetchOne();
    if (t == null) {
      t = new com.axelor.apps.account.db.AccountType();
      t.setName(technical);
      t.setTechnicalTypeSelect(technical);
      t = JPA.save(t);
    }
    return t;
  }

  private static Account account(Company company, String code, int position, String technical) {
    Account a = new Account();
    a.setAccountType(type(technical));
    a.setCode(code);
    a.setName(code);
    a.setCompany(company);
    a.setCommonPosition(position);
    a.setStatusSelect(1); // активна — неактивна сметка не се валидира
    return JPA.save(a);
  }

  private static StockLocation location(Company company, String name, int type) {
    StockLocation l = new StockLocation();
    l.setCompany(company);
    l.setName(name);
    l.setTypeSelect(type);
    return JPA.save(l);
  }

  private static Fixture fixture(String tag) {
    return tx(
        () -> {
          ensureAppConfigs();
          Fixture f = new Fixture();
          Currency currency = new Currency();
          currency.setName("Евро " + tag);
          currency.setCode("€");
          currency.setCodeISO("V" + tag);
          currency = JPA.save(currency);
          Company company = new Company();
          company.setName("ИЗП " + tag);
          company.setCode("V-" + tag);
          company.setCurrency(currency);
          company = JPA.save(company);
          f.company = company;

          f.stock = account(company, "304", 1, "currentAsset");
          f.sale = account(company, "702", 2, "income");
          Account shortage = account(company, "60905", 1, "charge");

          ProductFamily family = new ProductFamily();
          family.setCode("GOODS-" + tag);
          family.setName("Стоки " + tag);
          family.setBgSaftInventoryType("30");
          family = JPA.save(family);
          AccountManagement am = new AccountManagement();
          am.setTypeSelect(1);
          am.setCompany(company);
          am.setProductFamily(family);
          am.setBgStockAccount(f.stock);
          JPA.save(am);

          Product product = new Product();
          product.setCode("P-" + tag);
          product.setName("Стока " + tag);
          product.setProductTypeSelect("storable");
          product.setProductFamily(family);
          f.product = JPA.save(product);

          f.warehouse = location(company, "Основен склад " + tag, 1);
          f.supplier = location(company, "Доставчици " + tag, 3);
          f.customer = location(company, "Клиенти " + tag, 3);
          StockConfig stockConfig = new StockConfig();
          stockConfig.setCompany(company);
          stockConfig.setSupplierVirtualStockLocation(f.supplier);
          stockConfig.setCustomerVirtualStockLocation(f.customer);
          JPA.save(stockConfig);

          JournalType type = new JournalType();
          type.setName("Други " + tag);
          type.setCode("OTH-" + tag);
          type.setTechnicalTypeSelect(5);
          type = JPA.save(type);
          Journal journal = new Journal();
          journal.setName("Изписване на МЗ " + tag);
          journal.setCode("STK-" + tag);
          journal.setJournalType(type);
          journal.setCompany(company);
          journal.setStatusSelect(1); // активен — неактивен журнал не валидира
          journal.setValidAccountSet(new HashSet<>(Arrays.asList(f.stock, f.sale, shortage)));
          // Поредицата на журнала: валидирането дава номер на статията.
          com.axelor.apps.base.db.Sequence sequence = new com.axelor.apps.base.db.Sequence();
          sequence.setCompany(company);
          sequence.setName("Изписване " + tag);
          sequence.setCodeSelect("move");
          sequence.setPrefixe("STK" + tag);
          sequence.setPadding(5);
          sequence.setToBeAdded(1);
          com.axelor.apps.base.db.SequenceVersion version =
              new com.axelor.apps.base.db.SequenceVersion();
          version.setStartDate(LocalDate.of(2000, 1, 1));
          version.setNextNum(1L);
          sequence.addSequenceVersionListItem(version);
          journal.setSequence(JPA.save(sequence));
          journal = JPA.save(journal);
          f.journal = journal;
          AccountConfig accountConfig = new AccountConfig();
          accountConfig.setCompany(company);
          accountConfig.setBgStockIssueJournal(journal);
          accountConfig.setBgAutoPostingUser(
              Beans.get(BgAutoPostingUserService.class).ensureServiceUser());
          accountConfig = JPA.save(accountConfig);
          company.setAccountConfig(accountConfig);

          Year year = new Year();
          year.setName("2026 " + tag);
          year.setCode("FY-" + tag);
          year.setCompany(company);
          year.setTypeSelect(1);
          year.setFromDate(LocalDate.of(2026, 1, 1));
          year.setToDate(LocalDate.of(2026, 12, 31));
          year.setStatusSelect(1);
          year = JPA.save(year);
          Period period = new Period();
          period.setName("03/2026 " + tag);
          period.setYear(year);
          period.setFromDate(LocalDate.of(2026, 3, 1));
          period.setToDate(LocalDate.of(2026, 3, 31));
          period.setStatusSelect(1);
          f.period = JPA.save(period);
          Period rest = new Period();
          rest.setName("остатък 2026 " + tag);
          rest.setYear(year);
          rest.setFromDate(LocalDate.of(2026, 1, 1));
          rest.setToDate(LocalDate.of(2026, 2, 28));
          rest.setStatusSelect(1);
          JPA.save(rest);

          BgStockCostingPolicy policy = new BgStockCostingPolicy();
          policy.setCompany(company);
          policy.setYear(year);
          policy.setCostingMethodSelect(BgStockCostingRules.WAP_PERPETUAL);
          policy.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);
          policy.setPostingModeSelect(BgStockCostingRules.POSTING_CURRENT);
          policy.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
          BgStockCostingAccountRule sale30 = new BgStockCostingAccountRule();
          sale30.setMovementCode("30");
          sale30.setInventoryType("30");
          sale30.setAccount(f.sale);
          policy.addAccountRuleListItem(sale30);
          BgStockCostingAccountRule shortage120 = new BgStockCostingAccountRule();
          shortage120.setMovementCode("120");
          shortage120.setAccount(shortage);
          policy.addAccountRuleListItem(shortage120);
          f.policy = JPA.save(policy);
          JPA.save(company);
          return f;
        });
  }

  /** Реализирано движение с един ред — като след реализиране от Axelor. */
  private static StockMove move(
      Fixture f, String seq, StockLocation from, StockLocation to, String qty, String price) {
    return tx(
        () -> {
          StockMove m = new StockMove();
          m.setCompany(JPA.find(Company.class, f.company.getId()));
          m.setStockMoveSeq(seq);
          m.setStatusSelect(3);
          // След JPA.clear() в процесора обектите на фикстурата са откачени — прикачват се наново.
          StockLocation src = JPA.find(StockLocation.class, from.getId());
          StockLocation dst = JPA.find(StockLocation.class, to.getId());
          m.setFromStockLocation(src);
          m.setToStockLocation(dst);
          m.setBgDocumentDate(MARCH);
          m.setRealDate(MARCH);
          StockMoveLine line = new StockMoveLine();
          line.setProduct(JPA.find(Product.class, f.product.getId()));
          line.setQty(d(qty));
          line.setRealQty(d(qty));
          line.setCompanyUnitPriceUntaxed(price == null ? null : d(price));
          line.setFromStockLocation(src);
          line.setToStockLocation(dst);
          m.addStockMoveLineListItem(line);
          return JPA.save(m);
        });
  }

  /** Обработчикът след реализиране — в транзакция, както в прихващането. */
  private static void realized(StockMove move) {
    tx(
        () -> {
          Beans.get(BgStockValuationOutbox.class)
              .afterRealize(JPA.find(StockMove.class, move.getId()));
          return null;
        });
  }

  private static BgStockValuationTask task(StockMove move) {
    return tx(
        () ->
            JPA.all(BgStockValuationTask.class)
                .filter("self.stockMove.id = ?1", move.getId())
                .fetchOne());
  }

  private static BgStockCostLayer layer(Fixture f) {
    return tx(
        () ->
            JPA.all(BgStockCostLayer.class)
                .filter(
                    "self.company.id = ?1 AND self.product.id = ?2",
                    f.company.getId(),
                    f.product.getId())
                .fetchOne());
  }

  private static BgStockMoveLineValuation valuation(StockMove move) {
    return tx(
        () ->
            JPA.all(BgStockMoveLineValuation.class)
                .filter("self.stockMove.id = ?1", move.getId())
                .fetchOne());
  }

  /** Кой е валидирал статията и кой я е създал — проекция (SELECT на Move не върви на HSQLDB). */
  private static String[] moveUsers(StockMove stockMove) {
    Object[] row =
        tx(
            () ->
                JPA.em()
                    .createQuery(
                        "SELECT v.code, c.code FROM BgStockValuationTask t"
                            + " LEFT JOIN t.move.bgValidatedByUser v LEFT JOIN t.move.createdBy c"
                            + " WHERE t.stockMove.id = :id",
                        Object[].class)
                    .setParameter("id", stockMove.getId())
                    .getSingleResult());
    return new String[] {(String) row[0], (String) row[1]};
  }

  /** Статусът на статията — проекция: SELECT на Move като обект не върви на HSQLDB. */
  private static Integer moveStatus(StockMove stockMove) {
    return tx(
        () ->
            JPA.em()
                .createQuery(
                    "SELECT t.move.statusSelect FROM BgStockValuationTask t"
                        + " WHERE t.stockMove.id = :id",
                    Integer.class)
                .setParameter("id", stockMove.getId())
                .getSingleResult());
  }

  // ── outbox ──────────────────────────────────────────────────────────────

  @Test
  @DisplayName("rollback на реализирането — няма нито задача „чака оценка“, нито оценка")
  void rollback_маха_задачата() {
    Fixture f = fixture("RB");
    StockMove receipt = move(f, "RB-1", f.supplier, f.warehouse, "10", "5.00");
    assertThrows(
        IllegalStateException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  Beans.get(BgStockValuationOutbox.class)
                      .afterRealize(JPA.find(StockMove.class, receipt.getId()));
                  throw new IllegalStateException("реализирането се връща назад");
                }));
    JPA.clear();
    assertEquals(null, task(receipt), "задачата си отиде с транзакцията");
    Beans.get(BgStockValuationProcessor.class).processPending();
    assertEquals(null, valuation(receipt), "и оценка няма");
    assertEquals(null, layer(f));
  }

  @Test
  @DisplayName("дружество без активна политика — обработчикът не пише нищо")
  void без_политика_нищо() {
    Fixture f = fixture("OFF");
    tx(
        () -> {
          BgStockCostingPolicy p = JPA.find(BgStockCostingPolicy.class, f.policy.getId());
          p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_DRAFT);
          return JPA.save(p);
        });
    StockMove receipt = move(f, "OFF-1", f.supplier, f.warehouse, "10", "5.00");
    realized(receipt);
    assertEquals(null, task(receipt));
  }

  // ── задължителният пример ───────────────────────────────────────────────

  @Test
  @DisplayName("пример: 100 × 10,00 + 50 × 12,00, изход 120 → 1 280,00; остатък 30 / 320,00")
  void задължителният_пример() {
    Fixture f = fixture("WAP");
    StockMove r1 = move(f, "WAP-R1", f.supplier, f.warehouse, "100", "10.00");
    StockMove r2 = move(f, "WAP-R2", f.supplier, f.warehouse, "50", "12.00");
    StockMove out = move(f, "WAP-D1", f.warehouse, f.customer, "120", "25.00");
    realized(r1);
    realized(r2);
    realized(out);

    assertEquals(3, Beans.get(BgStockValuationProcessor.class).processPending());

    assertEquals(d("1000.00"), valuation(r1).getAmount());
    assertEquals(d("600.00"), valuation(r2).getAmount());
    BgStockMoveLineValuation issue = valuation(out);
    assertEquals("30", issue.getMovementCode());
    assertEquals("OUT", issue.getDirectionSelect());
    assertEquals(d("1280.00"), issue.getAmount(), "1 600 / 150 × 120, не продажната цена");
    BgStockCostLayer layer = layer(f);
    assertEquals(0, d("30").compareTo(layer.getQty()));
    assertEquals(d("320.00"), layer.getStockValue());

    // Само изписването има статия (10 няма — модел A): Дт 702 / Кт 304 по 1 280,00.
    assertEquals(null, task(r1).getMove());
    Long moveId =
        tx(
            () ->
                JPA.em()
                    .createQuery(
                        "SELECT v.move.id FROM BgStockMoveLineValuation v WHERE v.id = :id",
                        Long.class)
                    .setParameter("id", issue.getId())
                    .getSingleResult());
    assertNotNull(moveId);
    // Проекция по колони, не обекти: MoveLine, зареден наново в чужда транзакция, гърми при
    // commit в getter-а на name (средата на теста, не кодът под тест).
    List<Object[]> lines =
        tx(
            () ->
                JPA.em()
                    .createQuery(
                        "SELECT ml.account.id, ml.debit, ml.credit FROM MoveLine ml"
                            + " WHERE ml.move.id = :id",
                        Object[].class)
                    .setParameter("id", moveId)
                    .getResultList());
    assertEquals(2, lines.size());
    BigDecimal credit304 = BigDecimal.ZERO;
    BigDecimal debit702 = BigDecimal.ZERO;
    for (Object[] line : lines) {
      Long account = (Long) line[0];
      BigDecimal debit = (BigDecimal) line[1];
      BigDecimal credit = (BigDecimal) line[2];
      if (account.equals(f.stock.getId())) {
        credit304 = credit304.add(credit).subtract(debit);
      } else if (account.equals(f.sale.getId())) {
        debit702 = debit702.add(debit).subtract(credit);
      }
    }
    assertEquals(0, d("1280.00").compareTo(credit304), "Кт 304");
    assertEquals(0, d("1280.00").compareTo(debit702), "Дт 702");

    // Статията се валидира автоматично — черновата не влиза в салдата и в SAF-T.
    assertEquals(3, moveStatus(out), "STATUS_ACCOUNTED");
    // Валидира служебният потребител; създател — пак той, от одита на Axelor.
    String[] users = moveUsers(out);
    assertEquals(BgAutoPostingUserService.USER_CODE, users[0], "bgValidatedByUser");
    assertEquals(BgAutoPostingUserService.USER_CODE, users[1], "createdBy от одита");
    assertEquals(
        null, com.axelor.auth.AuthUtils.getUser(), "нишката не носи идентичност след задачата");

    // Сверка: Σ кредити по 30x от изписването = Σ изменение на складовата стойност по движенията.
    BigDecimal received = valuation(r1).getAmount().add(valuation(r2).getAmount());
    assertEquals(0, received.subtract(layer.getStockValue()).compareTo(credit304));
  }

  // ── FIFO и LIFO ──────────────────────────────────────────────────

  private static void method(Fixture f, String method) {
    tx(
        () -> {
          Company c = JPA.find(Company.class, f.company.getId());
          c.setBgAccountingFramework(BgStockCostingRules.NSS);
          JPA.save(c);
          BgStockCostingPolicy p = JPA.find(BgStockCostingPolicy.class, f.policy.getId());
          p.setCostingMethodSelect(method);
          return JPA.save(p);
        });
  }

  private static List<BgStockCostLayer> openLayers(Fixture f) {
    return tx(
        () ->
            JPA.all(BgStockCostLayer.class)
                .filter(
                    "self.company.id = ?1 AND self.product.id = ?2 AND self.qty > 0",
                    f.company.getId(),
                    f.product.getId())
                .order("id")
                .fetch());
  }

  @Test
  @DisplayName("FIFO през целия път: изход 1 240,00; остава слой 30 / 360,00")
  void fifo_примерът() {
    Fixture f = fixture("FIF");
    method(f, BgStockCostingRules.FIFO);
    StockMove r1 = move(f, "FIF-R1", f.supplier, f.warehouse, "100", "10.00");
    StockMove r2 = move(f, "FIF-R2", f.supplier, f.warehouse, "50", "12.00");
    StockMove out = move(f, "FIF-D1", f.warehouse, f.customer, "120", "25.00");
    realized(r1);
    realized(r2);
    realized(out);
    assertEquals(3, Beans.get(BgStockValuationProcessor.class).processPending());
    assertEquals(d("1240.00"), valuation(out).getAmount());
    assertEquals(BgStockCostingRules.FIFO, valuation(out).getCostingMethodSelect());
    List<BgStockCostLayer> left = openLayers(f);
    assertEquals(1, left.size());
    assertEquals(0, d("30").compareTo(left.get(0).getQty()));
    assertEquals(d("360.00"), left.get(0).getStockValue());
  }

  @Test
  @DisplayName("LIFO през целия път (НСС): изход 1 300,00; остава слой 30 / 300,00")
  void lifo_примерът() {
    Fixture f = fixture("LIF");
    method(f, BgStockCostingRules.LIFO);
    StockMove r1 = move(f, "LIF-R1", f.supplier, f.warehouse, "100", "10.00");
    StockMove r2 = move(f, "LIF-R2", f.supplier, f.warehouse, "50", "12.00");
    StockMove out = move(f, "LIF-D1", f.warehouse, f.customer, "120", "25.00");
    realized(r1);
    realized(r2);
    realized(out);
    assertEquals(3, Beans.get(BgStockValuationProcessor.class).processPending());
    assertEquals(d("1300.00"), valuation(out).getAmount());
    List<BgStockCostLayer> left = openLayers(f);
    assertEquals(1, left.size());
    assertEquals(d("300.00"), left.get(0).getStockValue());
  }

  @Test
  @DisplayName("FIFO: трансферът носи слоевете си в другия склад по себестойност")
  void fifo_трансферът() {
    Fixture f = fixture("FTR");
    method(f, BgStockCostingRules.FIFO);
    StockLocation second =
        tx(
            () -> {
              StockLocation l = new StockLocation();
              l.setCompany(JPA.find(Company.class, f.company.getId()));
              l.setName("Втори склад FTR");
              l.setTypeSelect(1);
              return JPA.save(l);
            });
    StockMove r1 = move(f, "FTR-R1", f.supplier, f.warehouse, "100", "10.00");
    StockMove r2 = move(f, "FTR-R2", f.supplier, f.warehouse, "50", "12.00");
    StockMove t1 = move(f, "FTR-T1", f.warehouse, second, "120", null);
    StockMove out = move(f, "FTR-D1", second, f.customer, "110", "25.00");
    realized(r1);
    realized(r2);
    realized(t1);
    realized(out);
    assertEquals(4, Beans.get(BgStockValuationProcessor.class).processPending());
    assertEquals(d("1240.00"), valuation(t1).getAmount());
    // Вторият склад получи 100 × 10,00 и 20 × 12,00 като два слоя: изходът 110 по FIFO е
    // 1 000,00 + 10 × 12,00 = 1 120,00.
    assertEquals(d("1120.00"), valuation(out).getAmount());
  }

  @Test
  @DisplayName(
      "смяна FIFO → средна от началото на годината: един слой със същата стойност, без статия")
  void смяната_на_метода_от_новата_година() {
    Fixture f = fixture("CHG");
    method(f, BgStockCostingRules.FIFO);
    StockMove r1 = move(f, "CHG-R1", f.supplier, f.warehouse, "100", "10.00");
    StockMove r2 = move(f, "CHG-R2", f.supplier, f.warehouse, "50", "12.00");
    realized(r1);
    realized(r2);
    Beans.get(BgStockValuationProcessor.class).processPending();
    tx(
        () -> {
          Company c = JPA.find(Company.class, f.company.getId());
          Year y = new Year();
          y.setName("2027 CHG");
          y.setCode("FY27-CHG");
          y.setCompany(c);
          y.setTypeSelect(1);
          y.setFromDate(LocalDate.of(2027, 1, 1));
          y.setToDate(LocalDate.of(2027, 12, 31));
          y.setStatusSelect(1);
          y = JPA.save(y);
          Period p = new Period();
          p.setName("2027 CHG");
          p.setYear(y);
          p.setFromDate(LocalDate.of(2027, 1, 1));
          p.setToDate(LocalDate.of(2027, 12, 31));
          p.setStatusSelect(1);
          JPA.save(p);
          BgStockCostingPolicy old = JPA.find(BgStockCostingPolicy.class, f.policy.getId());
          BgStockCostingPolicy next = new BgStockCostingPolicy();
          next.setCompany(c);
          next.setYear(y);
          next.setCostingMethodSelect(BgStockCostingRules.WAP_PERPETUAL);
          next.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);
          next.setPostingModeSelect(BgStockCostingRules.POSTING_CURRENT);
          next.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
          for (BgStockCostingAccountRule r : old.getAccountRuleList()) {
            BgStockCostingAccountRule copy = new BgStockCostingAccountRule();
            copy.setMovementCode(r.getMovementCode());
            copy.setInventoryType(r.getInventoryType());
            copy.setWithResponsiblePartner(r.getWithResponsiblePartner());
            copy.setAccount(r.getAccount());
            next.addAccountRuleListItem(copy);
          }
          return JPA.save(next);
        });
    StockMove out =
        tx(
            () -> {
              StockMove m =
                  JPA.find(
                      StockMove.class,
                      move(f, "CHG-D1", f.warehouse, f.customer, "120", "25.00").getId());
              m.setBgDocumentDate(LocalDate.of(2027, 1, 15));
              m.setRealDate(LocalDate.of(2027, 1, 15));
              return JPA.save(m);
            });
    realized(out);
    Beans.get(BgStockValuationProcessor.class).processPending();
    // Средната от двата FIFO слоя: 1 600 / 150 × 120 = 1 280,00 — същото като средна от начало.
    assertEquals(d("1280.00"), valuation(out).getAmount());
    assertEquals(BgStockCostingRules.WAP_PERPETUAL, valuation(out).getCostingMethodSelect());
    List<BgStockCostLayer> left = openLayers(f);
    assertEquals(1, left.size(), "слоевете са слети в един");
    assertEquals(d("320.00"), left.get(0).getStockValue());
  }

  @Test
  @DisplayName(
      "средна → FIFO от новата година: ЕДИН начален слой по средната, стойността непроменена")
  void средната_става_един_слой_за_fifo() {
    Fixture f = fixture("AVF");
    StockMove r1 = move(f, "AVF-R1", f.supplier, f.warehouse, "100", "10.00");
    StockMove r2 = move(f, "AVF-R2", f.supplier, f.warehouse, "50", "12.00");
    realized(r1);
    realized(r2);
    Beans.get(BgStockValuationProcessor.class).processPending();
    List<BgStockCostLayer> average = openLayers(f);
    assertEquals(1, average.size(), "средната е един слой");
    assertEquals(0, d("150").compareTo(average.get(0).getQty()));
    assertEquals(d("1600.00"), average.get(0).getStockValue());

    nextYear(f, BgStockCostingRules.FIFO);
    StockMove r3 = dated(move(f, "AVF-R3", f.supplier, f.warehouse, "10", "20.00"), 2027);
    realized(r3);
    Beans.get(BgStockValuationProcessor.class).processPending();
    List<BgStockCostLayer> fifo = openLayers(f);
    assertEquals(2, fifo.size(), "началният слой по средната + новото постъпление");
    assertEquals(average.get(0).getId(), fifo.get(0).getId(), "началният слой е същият ред");
    assertEquals(d("1600.00"), fifo.get(0).getStockValue(), "стойността му е непроменена");

    StockMove out = dated(move(f, "AVF-D1", f.warehouse, f.customer, "160", "25.00"), 2027);
    realized(out);
    Beans.get(BgStockValuationProcessor.class).processPending();
    // FIFO изписва първо началния слой цял (1 600,00), после 10 × 20,00.
    assertEquals(d("1800.00"), valuation(out).getAmount());
    assertEquals(BgStockCostingRules.FIFO, valuation(out).getCostingMethodSelect());
    assertEquals(0, openLayers(f).size(), "всичко е изписано");
  }

  private static void nextYear(Fixture f, String method) {
    tx(
        () -> {
          Company c = JPA.find(Company.class, f.company.getId());
          c.setBgAccountingFramework(BgStockCostingRules.NSS);
          JPA.save(c);
          Year y = new Year();
          y.setName("2027 " + f.company.getCode());
          y.setCode("FY27-" + f.company.getCode());
          y.setCompany(c);
          y.setTypeSelect(1);
          y.setFromDate(LocalDate.of(2027, 1, 1));
          y.setToDate(LocalDate.of(2027, 12, 31));
          y.setStatusSelect(1);
          y = JPA.save(y);
          Period p = new Period();
          p.setName("2027 " + f.company.getCode());
          p.setYear(y);
          p.setFromDate(LocalDate.of(2027, 1, 1));
          p.setToDate(LocalDate.of(2027, 12, 31));
          p.setStatusSelect(1);
          JPA.save(p);
          BgStockCostingPolicy old = JPA.find(BgStockCostingPolicy.class, f.policy.getId());
          BgStockCostingPolicy next = new BgStockCostingPolicy();
          next.setCompany(c);
          next.setYear(y);
          next.setCostingMethodSelect(method);
          next.setAverageScopeSelect(BgStockCostingRules.SCOPE_WAREHOUSE);
          next.setPostingModeSelect(BgStockCostingRules.POSTING_CURRENT);
          next.setStatusSelect(BgStockCostingPolicyRepository.STATUS_ACTIVE);
          for (BgStockCostingAccountRule r : old.getAccountRuleList()) {
            BgStockCostingAccountRule copy = new BgStockCostingAccountRule();
            copy.setMovementCode(r.getMovementCode());
            copy.setInventoryType(r.getInventoryType());
            copy.setWithResponsiblePartner(r.getWithResponsiblePartner());
            copy.setAccount(r.getAccount());
            next.addAccountRuleListItem(copy);
          }
          return JPA.save(next);
        });
  }

  private static StockMove dated(StockMove move, int year) {
    return tx(
        () -> {
          StockMove m = JPA.find(StockMove.class, move.getId());
          m.setBgDocumentDate(LocalDate.of(year, 1, 15));
          m.setRealDate(LocalDate.of(year, 1, 15));
          return JPA.save(m);
        });
  }

  // ── конкретно определена стойност (СС 2 т. 9.1) ───────────────────

  /** Политиката на фикстурата → конкретна стойност; продуктът — с проследяване. */
  private static void specific(Fixture f) {
    tx(
        () -> {
          com.axelor.apps.stock.db.TrackingNumberConfiguration cfg =
              new com.axelor.apps.stock.db.TrackingNumberConfiguration();
          cfg.setName("Партиди " + f.company.getCode());
          cfg = JPA.save(cfg);
          Product p = JPA.find(Product.class, f.product.getId());
          p.setTrackingNumberConfiguration(cfg);
          JPA.save(p);
          BgStockCostingPolicy policy = JPA.find(BgStockCostingPolicy.class, f.policy.getId());
          policy.setCostingMethodSelect(BgStockCostingRules.SPECIFIC);
          return JPA.save(policy);
        });
  }

  private static com.axelor.apps.stock.db.TrackingNumber lot(Product product, String seq) {
    return tx(
        () -> {
          com.axelor.apps.stock.db.TrackingNumber t = new com.axelor.apps.stock.db.TrackingNumber();
          t.setTrackingNumberSeq(seq);
          t.setProduct(JPA.find(Product.class, product.getId()));
          return JPA.save(t);
        });
  }

  /** Движение с партида на реда. */
  private static StockMove moveLot(
      Fixture f,
      String seq,
      StockLocation from,
      StockLocation to,
      String qty,
      String price,
      com.axelor.apps.stock.db.TrackingNumber lot) {
    StockMove m = move(f, seq, from, to, qty, price);
    return tx(
        () -> {
          StockMove fresh = JPA.find(StockMove.class, m.getId());
          fresh
              .getStockMoveLineList()
              .get(0)
              .setTrackingNumber(
                  lot == null
                      ? null
                      : JPA.find(com.axelor.apps.stock.db.TrackingNumber.class, lot.getId()));
          return JPA.save(fresh);
        });
  }

  @Test
  @DisplayName("конкретна стойност: всяка партида излиза по своята цена")
  void конкретна_стойност_по_партиди() {
    Fixture f = fixture("SPA");
    specific(f);
    com.axelor.apps.stock.db.TrackingNumber a = lot(f.product, "A-1");
    com.axelor.apps.stock.db.TrackingNumber b = lot(f.product, "B-1");
    StockMove ra = moveLot(f, "SPA-R1", f.supplier, f.warehouse, "100", "10.00", a);
    StockMove rb = moveLot(f, "SPA-R2", f.supplier, f.warehouse, "50", "12.00", b);
    StockMove oa = moveLot(f, "SPA-D1", f.warehouse, f.customer, "100", "25.00", a);
    StockMove ob = moveLot(f, "SPA-D2", f.warehouse, f.customer, "20", "25.00", b);
    for (StockMove m : List.of(ra, rb, oa, ob)) {
      realized(m);
    }
    assertEquals(4, Beans.get(BgStockValuationProcessor.class).processPending());
    assertEquals(d("1000.00"), valuation(oa).getAmount());
    assertEquals(d("240.00"), valuation(ob).getAmount());
    assertEquals(BgStockCostingRules.SPECIFIC, valuation(ob).getCostingMethodSelect());
    List<BgStockCostLayer> left = openLayers(f);
    assertEquals(1, left.size());
    assertEquals(b.getId(), left.get(0).getTrackingNumber().getId());
    assertEquals(d("360.00"), left.get(0).getStockValue());
  }

  @Test
  @DisplayName("ключът е партидата (id), не текстът: същият номер при два продукта — две партиди")
  void ключът_е_партидата() {
    Fixture f = fixture("SPK");
    specific(f);
    Product other =
        tx(
            () -> {
              Product p = new Product();
              p.setCode("P2-SPK");
              p.setName("Друга стока SPK");
              p.setProductTypeSelect("storable");
              p.setProductFamily(JPA.find(Product.class, f.product.getId()).getProductFamily());
              p.setTrackingNumberConfiguration(
                  JPA.find(Product.class, f.product.getId()).getTrackingNumberConfiguration());
              return JPA.save(p);
            });
    com.axelor.apps.stock.db.TrackingNumber mine = lot(f.product, "L-1");
    com.axelor.apps.stock.db.TrackingNumber theirs = lot(other, "L-1");
    StockMove r1 = moveLot(f, "SPK-R1", f.supplier, f.warehouse, "10", "10.00", mine);
    StockMove r2 =
        tx(
            () -> {
              StockMove m =
                  JPA.find(
                      StockMove.class,
                      moveLot(f, "SPK-R2", f.supplier, f.warehouse, "10", "20.00", theirs).getId());
              m.getStockMoveLineList().get(0).setProduct(JPA.find(Product.class, other.getId()));
              return JPA.save(m);
            });
    StockMove out = moveLot(f, "SPK-D1", f.warehouse, f.customer, "5", "25.00", mine);
    realized(r1);
    realized(r2);
    realized(out);
    assertEquals(3, Beans.get(BgStockValuationProcessor.class).processPending());
    assertEquals(d("50.00"), valuation(out).getAmount(), "по 10,00 — своята партида L-1");
  }

  @Test
  @DisplayName("партида, приета два пъти: вътре в партидата — FIFO по датата на постъплението")
  void fifo_вътре_в_партидата() {
    Fixture f = fixture("SPF");
    specific(f);
    com.axelor.apps.stock.db.TrackingNumber l = lot(f.product, "F-1");
    // Постъплението на 05.03 по 14,00 се реализира ПРЕДИ това на 01.03 по 10,00 (заведено късно):
    // FIFO по ДАТАТА на постъплението взима първо 01.03, не реда на обработка.
    StockMove later = onDay(moveLot(f, "SPF-R2", f.supplier, f.warehouse, "10", "14.00", l), 5);
    StockMove earlier = onDay(moveLot(f, "SPF-R1", f.supplier, f.warehouse, "10", "10.00", l), 1);
    StockMove out = onDay(moveLot(f, "SPF-D1", f.warehouse, f.customer, "15", "25.00", l), 9);
    realized(later);
    realized(earlier);
    realized(out);
    assertEquals(3, Beans.get(BgStockValuationProcessor.class).processPending());
    assertEquals(d("170.00"), valuation(out).getAmount(), "10 × 10,00 (01.03) + 5 × 14,00 (05.03)");
  }

  private static StockMove onDay(StockMove move, int day) {
    return tx(
        () -> {
          StockMove m = JPA.find(StockMove.class, move.getId());
          m.setBgDocumentDate(LocalDate.of(2026, 3, day));
          m.setRealDate(LocalDate.of(2026, 3, day));
          return JPA.save(m);
        });
  }

  @Test
  @DisplayName("партидата не взима от слоя на чужда партида — недостиг е ERROR")
  void чуждата_партида_не_се_пипа() {
    Fixture f = fixture("SFX");
    method(f, BgStockCostingRules.FIFO);
    com.axelor.apps.stock.db.TrackingNumber a = lot(f.product, "FX-A");
    com.axelor.apps.stock.db.TrackingNumber b = lot(f.product, "FX-B");
    realized(moveLot(f, "SFX-R1", f.supplier, f.warehouse, "10", "10.00", a));
    realized(moveLot(f, "SFX-R2", f.supplier, f.warehouse, "10", "12.00", b));
    Beans.get(BgStockValuationProcessor.class).processPending();
    tx(
        () -> {
          com.axelor.apps.stock.db.TrackingNumberConfiguration cfg =
              new com.axelor.apps.stock.db.TrackingNumberConfiguration();
          cfg.setName("Партиди SFX");
          cfg = JPA.save(cfg);
          Product p = JPA.find(Product.class, f.product.getId());
          p.setTrackingNumberConfiguration(cfg);
          return JPA.save(p);
        });
    nextYear(f, BgStockCostingRules.SPECIFIC);
    StockMove out = dated(moveLot(f, "SFX-D1", f.warehouse, f.customer, "15", "25.00", b), 2027);
    realized(out);
    Beans.get(BgStockValuationProcessor.class).processPending();
    BgStockValuationTask t = task(out);
    assertEquals(BgStockValuationTaskRepository.STATUS_ERROR, t.getStatusSelect());
    assertTrue(t.getErrorMessage().contains("Недостатъчна наличност"), t.getErrorMessage());
    assertEquals(2, openLayers(f).size(), "слоевете на двете партиди са непокътнати");
  }

  @Test
  @DisplayName("конкретна стойност без партида на реда → ERROR с причина")
  void без_партида_на_реда() {
    Fixture f = fixture("SPN");
    specific(f);
    com.axelor.apps.stock.db.TrackingNumber l = lot(f.product, "N-1");
    StockMove r1 = moveLot(f, "SPN-R1", f.supplier, f.warehouse, "10", "10.00", l);
    StockMove out = moveLot(f, "SPN-D1", f.warehouse, f.customer, "2", "25.00", null);
    realized(r1);
    realized(out);
    Beans.get(BgStockValuationProcessor.class).processPending();
    BgStockValuationTask t = task(out);
    assertEquals(BgStockValuationTaskRepository.STATUS_ERROR, t.getStatusSelect());
    assertTrue(t.getErrorMessage().contains("партида"), t.getErrorMessage());
  }

  @Test
  @DisplayName("трансферът носи слоевете заедно с партидата")
  void трансферът_носи_партидата() {
    Fixture f = fixture("SPT");
    specific(f);
    StockLocation second =
        tx(
            () -> {
              StockLocation l = new StockLocation();
              l.setCompany(JPA.find(Company.class, f.company.getId()));
              l.setName("Втори склад SPT");
              l.setTypeSelect(1);
              return JPA.save(l);
            });
    com.axelor.apps.stock.db.TrackingNumber a = lot(f.product, "T-A");
    com.axelor.apps.stock.db.TrackingNumber b = lot(f.product, "T-B");
    StockMove ra = moveLot(f, "SPT-R1", f.supplier, f.warehouse, "10", "10.00", a);
    StockMove rb = moveLot(f, "SPT-R2", f.supplier, f.warehouse, "10", "30.00", b);
    // И двете партиди минават във втория склад: изгубена ли е партидата при трансфера, изходът
    // на T-B би излязъл по средната на двете (20,00), а не по своята цена.
    StockMove ta = moveLot(f, "SPT-T0", f.warehouse, second, "10", null, a);
    StockMove t1 = moveLot(f, "SPT-T1", f.warehouse, second, "10", null, b);
    StockMove out = moveLot(f, "SPT-D1", second, f.customer, "4", "50.00", b);
    for (StockMove m : List.of(ra, rb, ta, t1, out)) {
      realized(m);
    }
    assertEquals(5, Beans.get(BgStockValuationProcessor.class).processPending());
    assertEquals(d("100.00"), valuation(ta).getAmount());
    assertEquals(d("300.00"), valuation(t1).getAmount());
    assertEquals(
        d("120.00"), valuation(out).getAmount(), "4 × 30,00 — партида T-B във втория склад");
  }

  @Test
  @DisplayName("смяна FIFO → конкретна: цената на партидата е от FIFO слоя ѝ, не средната")
  void цената_на_партидата_от_fifo() {
    Fixture f = fixture("SFF");
    method(f, BgStockCostingRules.FIFO);
    com.axelor.apps.stock.db.TrackingNumber a = lot(f.product, "FF-A");
    com.axelor.apps.stock.db.TrackingNumber b = lot(f.product, "FF-B");
    StockMove ra = moveLot(f, "SFF-R1", f.supplier, f.warehouse, "10", "10.00", a);
    StockMove rb = moveLot(f, "SFF-R2", f.supplier, f.warehouse, "10", "12.00", b);
    realized(ra);
    realized(rb);
    Beans.get(BgStockValuationProcessor.class).processPending();
    tx(
        () -> {
          com.axelor.apps.stock.db.TrackingNumberConfiguration cfg =
              new com.axelor.apps.stock.db.TrackingNumberConfiguration();
          cfg.setName("Партиди SFF");
          cfg = JPA.save(cfg);
          Product p = JPA.find(Product.class, f.product.getId());
          p.setTrackingNumberConfiguration(cfg);
          return JPA.save(p);
        });
    nextYear(f, BgStockCostingRules.SPECIFIC);
    StockMove out = dated(moveLot(f, "SFF-D1", f.warehouse, f.customer, "5", "25.00", b), 2027);
    realized(out);
    Beans.get(BgStockValuationProcessor.class).processPending();
    assertEquals(
        d("60.00"), valuation(out).getAmount(), "5 × 12,00 — партидата FF-B, не средната 11,00");
  }

  @Test
  @DisplayName("смяна средна → конкретна: партида без собствена цена взима средната на обхвата")
  void партида_без_цена_взима_средната() {
    Fixture f = fixture("SFA");
    com.axelor.apps.stock.db.TrackingNumber a = lot(f.product, "FA-A");
    com.axelor.apps.stock.db.TrackingNumber b = lot(f.product, "FA-B");
    StockMove ra = moveLot(f, "SFA-R1", f.supplier, f.warehouse, "100", "10.00", a);
    StockMove rb = moveLot(f, "SFA-R2", f.supplier, f.warehouse, "50", "12.00", b);
    realized(ra);
    realized(rb);
    Beans.get(BgStockValuationProcessor.class).processPending();
    tx(
        () -> {
          com.axelor.apps.stock.db.TrackingNumberConfiguration cfg =
              new com.axelor.apps.stock.db.TrackingNumberConfiguration();
          cfg.setName("Партиди SFA");
          cfg = JPA.save(cfg);
          Product p = JPA.find(Product.class, f.product.getId());
          p.setTrackingNumberConfiguration(cfg);
          return JPA.save(p);
        });
    nextYear(f, BgStockCostingRules.SPECIFIC);
    StockMove out = dated(moveLot(f, "SFA-D1", f.warehouse, f.customer, "30", "25.00", b), 2027);
    realized(out);
    Beans.get(BgStockValuationProcessor.class).processPending();
    assertEquals(
        d("320.00"), valuation(out).getAmount(), "1 600 / 150 × 30 — средната (решение 6)");
  }

  // ── валидирането на статията ────────────────────────────────────────────

  @Test
  @DisplayName("„Валидиране на статиите“ = ръчно — статията остава чернова, задачата е готова")
  void ръчно_валидиране() {
    Fixture f = fixture("MAN");
    tx(
        () -> {
          BgStockCostingPolicy p = JPA.find(BgStockCostingPolicy.class, f.policy.getId());
          p.setMoveValidationSelect(BgStockCostingRules.VALIDATION_MANUAL);
          return JPA.save(p);
        });
    StockMove r1 = move(f, "MAN-R1", f.supplier, f.warehouse, "10", "5.00");
    StockMove out = move(f, "MAN-D1", f.warehouse, f.customer, "4", "9.00");
    realized(r1);
    realized(out);
    assertEquals(2, Beans.get(BgStockValuationProcessor.class).processPending());
    assertEquals(1, moveStatus(out), "STATUS_NEW — чернова");
  }

  @Test
  @DisplayName("неуспешно валидиране → задачата е ERROR с причината, оценката и слоят не се пипат")
  void неуспешното_валидиране_е_error() {
    Fixture f = fixture("VAL");
    StockMove r1 = move(f, "VAL-R1", f.supplier, f.warehouse, "10", "5.00");
    realized(r1);
    Beans.get(BgStockValuationProcessor.class).processPending();
    // Журнал без поредица: Axelor не може да даде номер и отказва валидирането.
    tx(
        () -> {
          Journal j = JPA.find(Journal.class, f.journal.getId());
          j.setSequence(null);
          return JPA.save(j);
        });
    StockMove out = move(f, "VAL-D1", f.warehouse, f.customer, "4", "9.00");
    realized(out);
    Beans.get(BgStockValuationProcessor.class).processPending();

    BgStockValuationTask t = task(out);
    assertEquals(BgStockValuationTaskRepository.STATUS_ERROR, t.getStatusSelect());
    assertTrue(
        t.getErrorMessage().startsWith("Статията за изписване не се валидира")
            && !t.getErrorMessage().contains("inactive"),
        "причината е поредицата на журнала: " + t.getErrorMessage());
    assertEquals(null, valuation(out), "без валидирана статия няма оценка");
    assertEquals(0, d("10").compareTo(layer(f).getQty()), "слоят е непокътнат");
  }

  // ── служебният потребител ───────────────────────────────────────────────

  private static void postingUser(Fixture f, com.axelor.auth.db.User user) {
    tx(
        () -> {
          AccountConfig c =
              JPA.all(AccountConfig.class)
                  .filter("self.company.id = ?1", f.company.getId())
                  .fetchOne();
          c.setBgAutoPostingUser(
              user == null ? null : JPA.find(com.axelor.auth.db.User.class, user.getId()));
          return JPA.save(c);
        });
  }

  /** Приемане и изход, обработени; връща задачата на изхода и следващото движение. */
  private static StockMove[] receiptOutLater(Fixture f, String tag) {
    StockMove r1 = move(f, tag + "-R1", f.supplier, f.warehouse, "10", "5.00");
    realized(r1);
    Beans.get(BgStockValuationProcessor.class).processPending();
    StockMove out = move(f, tag + "-D1", f.warehouse, f.customer, "4", "9.00");
    StockMove later = move(f, tag + "-D2", f.warehouse, f.customer, "1", "9.00");
    realized(out);
    realized(later);
    Beans.get(BgStockValuationProcessor.class).processPending();
    return new StockMove[] {out, later};
  }

  @Test
  @DisplayName("празна настройка → ERROR с причина; следващото движение на продукта чака")
  void празната_настройка() {
    Fixture f = fixture("NOU");
    postingUser(f, null);
    StockMove[] m = receiptOutLater(f, "NOU");
    BgStockValuationTask t = task(m[0]);
    assertEquals(BgStockValuationTaskRepository.STATUS_ERROR, t.getStatusSelect());
    assertTrue(
        t.getErrorMessage().contains("Потребител за автоматично осчетоводяване"),
        t.getErrorMessage());
    assertEquals(
        BgStockValuationTaskRepository.STATUS_PENDING, task(m[1]).getStatusSelect(), "чака");
    assertEquals(null, com.axelor.auth.AuthUtils.getUser());
  }

  @Test
  @DisplayName("изтрит (архивиран) потребител → ERROR с причина, без преход към друг")
  void архивираният_потребител() {
    Fixture f = fixture("ARC");
    tx(
        () -> {
          com.axelor.auth.db.User u =
              JPA.all(com.axelor.auth.db.User.class)
                  .filter("self.code = ?1", BgAutoPostingUserService.USER_CODE)
                  .fetchOne();
          u.setArchived(true);
          return JPA.save(u);
        });
    try {
      StockMove[] m = receiptOutLater(f, "ARC");
      BgStockValuationTask t = task(m[0]);
      assertEquals(BgStockValuationTaskRepository.STATUS_ERROR, t.getStatusSelect());
      assertTrue(t.getErrorMessage().contains("неактивен"), t.getErrorMessage());
    } finally {
      tx(
          () -> {
            com.axelor.auth.db.User u =
                JPA.all(com.axelor.auth.db.User.class)
                    .filter("self.code = ?1", BgAutoPostingUserService.USER_CODE)
                    .fetchOne();
            u.setArchived(false);
            return JPA.save(u);
          });
    }
  }

  @Test
  @DisplayName("потребител без право за статии → ERROR с причина")
  void без_права() {
    Fixture f = fixture("NOR");
    com.axelor.auth.db.User clerk =
        tx(
            () -> {
              com.axelor.auth.db.Group g = new com.axelor.auth.db.Group();
              g.setCode("no-rights-NOR");
              g.setName("Без права NOR");
              g = JPA.save(g);
              com.axelor.auth.db.User u = new com.axelor.auth.db.User();
              u.setCode("clerk-NOR");
              u.setName("Служител без права");
              u.setPassword("x");
              u.setGroup(g);
              return JPA.save(u);
            });
    postingUser(f, clerk);
    StockMove[] m = receiptOutLater(f, "NOR");
    BgStockValuationTask t = task(m[0]);
    assertEquals(BgStockValuationTaskRepository.STATUS_ERROR, t.getStatusSelect());
    assertTrue(t.getErrorMessage().contains("няма право"), t.getErrorMessage());
  }

  @Test
  @DisplayName("две дружества: статиите на всяко минават, независимо от активното дружество")
  void две_дружества() {
    Fixture a = fixture("TWA");
    Fixture b = fixture("TWB");
    tx(
        () -> {
          com.axelor.auth.db.User u =
              JPA.all(com.axelor.auth.db.User.class)
                  .filter("self.code = ?1", BgAutoPostingUserService.USER_CODE)
                  .fetchOne();
          u.setActiveCompany(JPA.find(Company.class, a.company.getId()));
          return JPA.save(u);
        });
    StockMove ra = move(a, "TWA-R1", a.supplier, a.warehouse, "10", "5.00");
    StockMove rb = move(b, "TWB-R1", b.supplier, b.warehouse, "10", "7.00");
    StockMove oa = move(a, "TWA-D1", a.warehouse, a.customer, "2", "9.00");
    StockMove ob = move(b, "TWB-D1", b.warehouse, b.customer, "2", "9.00");
    realized(ra);
    realized(rb);
    realized(oa);
    realized(ob);
    assertEquals(4, Beans.get(BgStockValuationProcessor.class).processPending());
    assertEquals(3, moveStatus(oa));
    assertEquals(3, moveStatus(ob), "дружество B, при активно дружество A");
    assertEquals(BgAutoPostingUserService.USER_CODE, moveUsers(ob)[0]);
    assertEquals(d("14.00"), valuation(ob).getAmount());
  }

  // ── грешката не спира реализирането ─────────────────────────────────────

  @Test
  @DisplayName(
      "липсваща сметка → задачата е ERROR, движението остава реализирано, слоят — непипнат")
  void липсващата_сметка_е_error() {
    Fixture f = fixture("ERR");
    StockMove receipt = move(f, "ERR-R1", f.supplier, f.warehouse, "10", "5.00");
    realized(receipt);
    Beans.get(BgStockValuationProcessor.class).processPending();

    StockMove gift = move(f, "ERR-G1", f.warehouse, f.customer, "2", null);
    tx(
        () -> {
          StockMove m = JPA.find(StockMove.class, gift.getId());
          m.setBgSaftMovementCode("150"); // безвъзмездно — политиката няма ред за 150
          return JPA.save(m);
        });
    realized(gift);
    Beans.get(BgStockValuationProcessor.class).processPending();

    BgStockValuationTask failed = task(gift);
    assertEquals(BgStockValuationTaskRepository.STATUS_ERROR, failed.getStatusSelect());
    assertTrue(failed.getErrorMessage().contains("код 150"), failed.getErrorMessage());
    assertEquals(3, (int) tx(() -> JPA.find(StockMove.class, gift.getId()).getStatusSelect()));
    assertEquals(0, d("10").compareTo(layer(f).getQty()), "транзакцията на оценката е върната");
    assertEquals(null, valuation(gift));

    // Следващо движение със същия продукт чака — средната не прескача дупка.
    StockMove later = move(f, "ERR-D2", f.warehouse, f.customer, "1", "9.00");
    realized(later);
    Beans.get(BgStockValuationProcessor.class).processPending();
    BgStockValuationTask waiting = task(later);
    assertEquals(BgStockValuationTaskRepository.STATUS_PENDING, waiting.getStatusSelect());
    assertTrue(waiting.getErrorMessage().contains("ERR-G1"), waiting.getErrorMessage());

    // Периодът не се затваря, докато има неосчетоводени изписвания.
    AxelorException closing =
        assertThrows(
            AxelorException.class,
            () ->
                Beans.get(PeriodService.class)
                    .closeTemporarily(tx(() -> JPA.find(Period.class, f.period.getId()))));
    assertTrue(closing.getMessage().contains("ERR-G1"), closing.getMessage());

    // Дружеството добавя реда; повторната обработка минава, после и чакащото.
    tx(
        () -> {
          BgStockCostingPolicy p = JPA.find(BgStockCostingPolicy.class, f.policy.getId());
          BgStockCostingAccountRule gift150 = new BgStockCostingAccountRule();
          gift150.setMovementCode("150");
          gift150.setAccount(
              JPA.all(Account.class)
                  .filter("self.code = '60905' AND self.company.id = ?1", f.company.getId())
                  .fetchOne());
          p.addAccountRuleListItem(gift150);
          return JPA.save(p);
        });
    Beans.get(BgStockValuationProcessor.class).retry(failed.getId());
    assertEquals(BgStockValuationTaskRepository.STATUS_DONE, task(gift).getStatusSelect());
    assertEquals(d("10.00"), valuation(gift).getAmount(), "2 от 10 × 5,00");
    Beans.get(BgStockValuationProcessor.class).processPending();
    assertEquals(BgStockValuationTaskRepository.STATUS_DONE, task(later).getStatusSelect());
  }

  @Test
  @DisplayName("недостатъчна наличност в слоя → ERROR, не отрицателен слой")
  void недостигът_е_error() {
    Fixture f = fixture("SHT");
    StockMove out = move(f, "SHT-D1", f.warehouse, f.customer, "5", "9.00");
    realized(out);
    Beans.get(BgStockValuationProcessor.class).processPending();
    BgStockValuationTask t = task(out);
    assertEquals(BgStockValuationTaskRepository.STATUS_ERROR, t.getStatusSelect());
    assertTrue(t.getErrorMessage().contains("Недостатъчна наличност"), t.getErrorMessage());
  }

  // ── трансферът ──────────────────────────────────────────────────────────

  @Test
  @DisplayName("код 80 към подизпълнителя: стойността минава в неговия слой, без статия")
  void трансферът_към_подизпълнителя() {
    Fixture f = fixture("TRF");
    StockLocation subcontractor =
        tx(
            () -> {
              StockLocation l = new StockLocation();
              l.setCompany(JPA.find(Company.class, f.company.getId()));
              l.setName("Подизпълнител TRF");
              l.setTypeSelect(3);
              l.setBgSaftWarehouse(true);
              l = JPA.save(l);
              StockConfig c =
                  JPA.all(StockConfig.class)
                      .filter("self.company.id = ?1", f.company.getId())
                      .fetchOne();
              c.setVirtualOutsourcingStockLocation(l);
              JPA.save(c);
              return l;
            });
    StockMove receipt = move(f, "TRF-R1", f.supplier, f.warehouse, "10", "5.00");
    StockMove out = move(f, "TRF-T1", f.warehouse, subcontractor, "4", null);
    realized(receipt);
    realized(out);
    assertEquals(2, Beans.get(BgStockValuationProcessor.class).processPending());

    BgStockMoveLineValuation transfer = valuation(out);
    assertEquals("80", transfer.getMovementCode());
    assertEquals("TRANSFER", transfer.getDirectionSelect());
    assertEquals(d("20.00"), transfer.getAmount());
    assertEquals(null, task(out).getMove(), "трансферът няма статия");
    List<BgStockCostLayer> layers =
        tx(
            () ->
                JPA.all(BgStockCostLayer.class)
                    .filter("self.company.id = ?1", f.company.getId())
                    .order("stockValue")
                    .fetch());
    assertEquals(2, layers.size());
    assertEquals(d("20.00"), layers.get(0).getStockValue(), "при подизпълнителя");
    assertEquals(d("30.00"), layers.get(1).getStockValue(), "в склада");
  }

  // ── връзките на Guice ───────────────────────────────────────────────────

  @Test
  @DisplayName("Guice: обработчикът на ИЗП е закачен; услугата на периода е прихваната")
  void връзките_на_guice() {
    Injector injector = Beans.get(Injector.class);
    Set<BgStockMoveRealizedHandler> handlers = injector.getInstance(BgStockMoveGuard.HANDLERS);
    assertTrue(
        handlers.stream().anyMatch(h -> h instanceof BgStockValuationOutbox),
        "BgLocalizationModule закача outbox-а: " + handlers);
    PeriodService periods = Beans.get(PeriodService.class);
    assertTrue(
        BgStockPeriodCloseGuard.classes().matches(periods.getClass()),
        "наследник на PeriodServiceImpl: " + periods.getClass().getName());
    assertTrue(
        periods.getClass().getName().contains("$$EnhancerByGuice$$"),
        "Guice е подкласирал услугата заради прихващането: " + periods.getClass().getName());
  }
}
