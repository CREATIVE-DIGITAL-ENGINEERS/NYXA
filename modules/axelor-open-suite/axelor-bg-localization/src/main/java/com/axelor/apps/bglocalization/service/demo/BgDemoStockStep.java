package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.bglocalization.db.BgStockCostingPolicy;
import com.axelor.apps.bglocalization.db.BgStockValuationTask;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import com.axelor.apps.bglocalization.db.repo.BgStockValuationTaskRepository;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingOpeningService;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingPolicyService;
import com.axelor.apps.bglocalization.service.stock.BgStockValuationProcessor;
import com.axelor.apps.stock.db.StockConfig;
import com.axelor.apps.stock.db.StockCorrectionReason;
import com.axelor.apps.stock.db.StockLocation;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.db.StockMoveLine;
import com.axelor.apps.stock.db.repo.StockMoveRepository;
import com.axelor.apps.stock.service.StockMoveLineService;
import com.axelor.apps.stock.service.StockMoveService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * СКЛАДЪТ — един склад, средна претеглена цена, движенията по фактурите, инвентаризация с липса.
 *
 * <p>По реализираното в Nyxa (ИЗП, {@code service/stock}):
 *
 * <ol>
 *   <li>Политика за изписване на годината — средна претеглена след всяко постъпление ({@code
 *       WAP_PERPETUAL}), по дружество; правилата по подразбиране (30 → 702, 120 → 60905, 120 с
 *       виновно лице → 442).
 *   <li>Наличностите към 01.01 ({@code stock_opening.csv}: {@code product;qty;cost}) — вътрешно
 *       движение от инвентаризационната локация, реализирано ПРЕДИ активирането на политиката: така
 *       не се съставя втора статия по 304 (стойността е във встъпителната статия), а наличността
 *       става начални слоеве с дата 01.01.
 *   <li>За всяка осчетоводена фактура със складируеми стоки — движение със същата дата: от
 *       доставчика в склада (код 10, без статия — 304 е дебитирана от фактурата), от склада към
 *       клиента (код 30 — Дт 702 / Кт 304 по средната цена, статия в дневник „Изписване на МЗ“),
 *       връщане по кредитно известие — обратно в склада.
 *   <li>Инвентаризацията ({@code stock_count.csv}: {@code date;product;qty;reason}) — липсата е
 *       вътрешно движение от склада към инвентаризационната локация с дата на преброяването (код
 *       120 → 60905).
 * </ol>
 *
 * <p>Статиите по изписването съставя обработчикът на Nyxa ({@link BgStockValuationProcessor}) —
 * както задачата му на всяка минута; тук се вика веднага след движението.
 */
final class BgDemoStockStep {

  private BgDemoStockStep() {}

  /** Политиката и началните наличности — преди фактурите. */
  static void setup(BgDemoContext ctx) throws Exception {
    List<BgDemoCsv.Row> opening = ctx.rows("stock_opening");
    if (opening.isEmpty()) {
      return; // наборът няма склад (холдингът)
    }
    LocalDate jan1 = ctx.dates().first();
    Long policyId =
        BgDemoTx.call(
            () -> {
              Company company = ctx.company();
              Year year =
                  JPA.all(Year.class)
                      .filter(
                          "self.company = ?1 AND self.typeSelect = ?2 AND self.fromDate = ?3",
                          company,
                          YearRepository.TYPE_FISCAL,
                          jan1)
                      .fetchOne();
              BgStockCostingPolicy p = new BgStockCostingPolicy();
              p.setCompany(company);
              p.setYear(year);
              p.setCostingMethodSelect("WAP_PERPETUAL");
              p.setAverageScopeSelect("COMPANY");
              p.setPostingModeSelect("CURRENT");
              p.setMoveValidationSelect("AUTO");
              p.setStatusSelect(BgStockCostingPolicyRepository.STATUS_DRAFT);
              return Beans.get(BgStockCostingPolicyRepository.class).save(p).getId();
            });
    List<Line> lines = new ArrayList<>();
    for (BgDemoCsv.Row r : opening) {
      lines.add(new Line(r.get("product"), r.decimal("qty"), r.decimal("cost")));
    }
    move(ctx, jan1, null, Direction.OPENING, lines, null, null);
    BgDemoTx.run(
        () -> {
          BgStockCostingPolicy p = JPA.find(BgStockCostingPolicy.class, policyId);
          Beans.get(BgStockCostingOpeningService.class).createOpeningLayers(p, jan1);
          Beans.get(BgStockCostingPolicyService.class).activate(p);
        });
    ctx.log("Склад: политика „средна претеглена“, начални наличности " + lines.size() + " стоки");
  }

  /** Движението за осчетоводена фактура (вика се от стъпката на фактурите). */
  static void forInvoice(BgDemoContext ctx, Long invoiceId) throws Exception {
    if (ctx.rows("stock_opening").isEmpty()) {
      return;
    }
    Invoice invoice = JPA.find(Invoice.class, invoiceId);
    if (invoice.getOperationSubTypeSelect() == InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE) {
      return;
    }
    List<Line> lines = new ArrayList<>();
    for (InvoiceLine l : invoice.getInvoiceLineList()) {
      Product p = l.getProduct();
      if (l.getBgAdvanceDeduction()
          || p == null
          || !ProductRepository.PRODUCT_TYPE_STORABLE.equals(p.getProductTypeSelect())) {
        continue;
      }
      lines.add(
          new Line(
              p.getId(),
              l.getQty(),
              l.getCompanyExTaxTotal().divide(l.getQty(), 4, java.math.RoundingMode.HALF_UP)));
    }
    if (lines.isEmpty()) {
      return;
    }
    Direction d;
    switch (invoice.getOperationTypeSelect()) {
      case InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE:
        d = Direction.IN_SUPPLIER;
        break;
      case InvoiceRepository.OPERATION_TYPE_CLIENT_SALE:
        d = Direction.OUT_CUSTOMER;
        break;
      case InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND:
        d = Direction.IN_CUSTOMER;
        break;
      default:
        d = Direction.OUT_SUPPLIER;
    }
    Long origin = null;
    if (d == Direction.IN_CUSTOMER && invoice.getOriginalInvoice() != null) {
      // Връщането взема себестойността от изписването по оригиналната фактура.
      origin = ctx.stockMoveOf(invoice.getOriginalInvoice().getId());
    }
    Long moveId =
        move(ctx, invoice.getInvoiceDate(), invoice.getPartner().getId(), d, lines, null, origin);
    ctx.rememberStockMove(invoice.getId(), moveId);
  }

  /** Инвентаризацията — {@code stock_count.csv}. */
  static List<BgDemoEvent> countEvents(BgDemoContext ctx) {
    List<BgDemoEvent> out = new ArrayList<>();
    for (BgDemoCsv.Row r : ctx.rows("stock_count")) {
      LocalDate date = ctx.dates().of(r.get("date"));
      out.add(
          new BgDemoEvent(
              date,
              BgDemoEvent.RANK_STOCK,
              "инвентаризация " + r.get("product"),
              () -> {
                Product p = ctx.find(Product.class, "product", r.get("product"));
                List<Line> lines = new ArrayList<>();
                lines.add(new Line(p.getId(), r.decimal("qty"), BigDecimal.ZERO));
                move(ctx, date, null, Direction.SHORTAGE, lines, r.opt("reason"), null);
              }));
    }
    return out;
  }

  enum Direction {
    OPENING,
    IN_SUPPLIER,
    OUT_CUSTOMER,
    IN_CUSTOMER,
    OUT_SUPPLIER,
    SHORTAGE
  }

  static final class Line {
    final Object product;
    final BigDecimal qty;
    final BigDecimal unitCost;

    Line(Object product, BigDecimal qty, BigDecimal unitCost) {
      this.product = product;
      this.qty = qty;
      this.unitCost = unitCost;
    }
  }

  private static Long move(
      BgDemoContext ctx,
      LocalDate date,
      Long partnerId,
      Direction d,
      List<Line> lines,
      String reasonName,
      Long originMoveId)
      throws Exception {
    Long id =
        BgDemoTx.call(
            () -> {
              Company company = ctx.company();
              StockConfig cfg =
                  JPA.all(StockConfig.class).filter("self.company = ?1", company).fetchOne();
              StockLocation warehouse = cfg.getReceiptDefaultStockLocation();
              StockLocation from;
              StockLocation to;
              int type;
              switch (d) {
                case OPENING:
                  from = cfg.getInventoryVirtualStockLocation();
                  to = warehouse;
                  type = StockMoveRepository.TYPE_INTERNAL;
                  break;
                case IN_SUPPLIER:
                  from = cfg.getSupplierVirtualStockLocation();
                  to = warehouse;
                  type = StockMoveRepository.TYPE_INCOMING;
                  break;
                case OUT_CUSTOMER:
                  from = warehouse;
                  to = cfg.getCustomerVirtualStockLocation();
                  type = StockMoveRepository.TYPE_OUTGOING;
                  break;
                case IN_CUSTOMER:
                  from = cfg.getCustomerVirtualStockLocation();
                  to = warehouse;
                  type = StockMoveRepository.TYPE_INCOMING;
                  break;
                case OUT_SUPPLIER:
                  from = warehouse;
                  to = cfg.getSupplierVirtualStockLocation();
                  type = StockMoveRepository.TYPE_OUTGOING;
                  break;
                default:
                  from = warehouse;
                  to = cfg.getInventoryVirtualStockLocation();
                  type = StockMoveRepository.TYPE_INTERNAL;
              }
              StockMove move =
                  Beans.get(StockMoveService.class)
                      .createStockMove(null, null, company, from, to, date, date, null, type);
              move.setBgDocumentDate(date);
              if (originMoveId != null) {
                move.setReversionOriginStockMove(JPA.find(StockMove.class, originMoveId));
              }
              if (partnerId != null) {
                move.setPartner(JPA.find(Partner.class, partnerId));
              }
              if (reasonName != null && !reasonName.isEmpty()) {
                move.setStockCorrectionReason(
                    JPA.all(StockCorrectionReason.class)
                        .filter("self.name = ?1", reasonName)
                        .fetchOne());
              }
              for (Line l : lines) {
                Product p =
                    l.product instanceof Long
                        ? JPA.find(Product.class, (Long) l.product)
                        : ctx.find(Product.class, "product", (String) l.product);
                StockMoveLine sml =
                    Beans.get(StockMoveLineService.class)
                        .createStockMoveLine(
                            p,
                            p.getName(),
                            null,
                            l.qty,
                            l.unitCost,
                            l.unitCost,
                            p.getUnit(),
                            move,
                            StockMoveLineService.TYPE_NULL,
                            false,
                            BigDecimal.ZERO,
                            from,
                            to);
                sml.setCompanyUnitPriceUntaxed(l.unitCost);
              }
              StockMoveService service = Beans.get(StockMoveService.class);
              service.plan(move);
              service.copyQtyToRealQty(move);
              service.realize(move);
              return move.getId();
            });
    // Статията по изписването — както задачата на Nyxa на всяка минута.
    Beans.get(BgStockValuationProcessor.class).processPending();
    String error =
        BgDemoTx.call(
            () -> {
              BgStockValuationTask failed =
                  JPA.all(BgStockValuationTask.class)
                      .filter(
                          "self.stockMove.id = ?1 AND self.statusSelect = ?2",
                          id,
                          BgStockValuationTaskRepository.STATUS_ERROR)
                      .fetchOne();
              return failed == null ? null : failed.getErrorMessage();
            });
    if (error != null) {
      throw new IllegalStateException("Оценката на складовото движение отказа: " + error);
    }
    return id;
  }
}
