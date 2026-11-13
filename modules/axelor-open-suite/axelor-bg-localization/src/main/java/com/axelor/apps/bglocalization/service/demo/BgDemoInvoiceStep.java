package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.FiscalPosition;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.invoice.InvoiceService;
import com.axelor.apps.account.service.invoice.InvoiceToolService;
import com.axelor.apps.account.service.invoice.generator.InvoiceGenerator;
import com.axelor.apps.account.service.invoice.generator.InvoiceLineGenerator;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.service.vat.BgVatAdvanceService;
import com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolCase;
import com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolService;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ФАКТУРИТЕ — {@code invoices.csv}, по ред на документ (един документ = поредни редове със същия
 * {@code doc}).
 *
 * <p>Колони: {@code
 * doc;kind;date;taxEvent;partner;product;qty;price;tax;credit;income;refundOf;note} и по желание
 * {@code account} (сметката на реда), {@code asset} (категория ДМА — редът става „ДМА“ и фактурата
 * създава актив) и {@code fiscalPosition} (друга позиция за документа).
 *
 * <ul>
 *   <li>{@code kind}: {@code SALE}, {@code PURCHASE}, {@code SALE_REFUND}, {@code PURCHASE_REFUND},
 *       {@code SALE_ADVANCE} (авансова фактура, ЗДДС чл. 25 ал. 7 — по реализираното в Nyxa, схема
 *       А), {@code SALE_FINAL} (окончателна, която приспада авансите на партньора).
 *   <li>{@code date} — дата на издаване (за покупка: дата на документа на доставчика); {@code
 *       taxEvent} — дата на данъчното събитие, ако е друга.
 *   <li>{@code tax} — подменя данъка от семейството и фискалната позиция (празно — както би го
 *       избрал Axelor); {@code credit} — вид данъчен кредит на покупката (1/2/3); {@code income} —
 *       вид доход по ЗДДФЛ за доставчик физическо лице (наемът → справката по чл. 73).
 * </ul>
 *
 * <p>Пътят е този на ръчната работа: заглавие като от формата (фискалната позиция — от партньора),
 * редове с данъка от управлението на сметките, {@code InvoiceService.compute}, запис, „Валидирай“ и
 * „Осчетоводи“.
 */
final class BgDemoInvoiceStep {

  private BgDemoInvoiceStep() {}

  /** Документите на набора, групирани по {@code doc}, в реда на файла. */
  static Map<String, List<BgDemoCsv.Row>> documents(BgDemoContext ctx) {
    Map<String, List<BgDemoCsv.Row>> docs = new LinkedHashMap<>();
    for (BgDemoCsv.Row r : ctx.rows("invoices")) {
      docs.computeIfAbsent(r.get("doc"), k -> new ArrayList<>()).add(r);
    }
    return docs;
  }

  static List<BgDemoEvent> events(BgDemoContext ctx) {
    List<BgDemoEvent> out = new ArrayList<>();
    for (Map.Entry<String, List<BgDemoCsv.Row>> e : documents(ctx).entrySet()) {
      BgDemoCsv.Row head = e.getValue().get(0);
      LocalDate date = ctx.dates().of(head.get("date"));
      boolean purchase = head.get("kind").startsWith("PURCHASE");
      out.add(
          new BgDemoEvent(
              date,
              purchase ? BgDemoEvent.RANK_PURCHASE : BgDemoEvent.RANK_SALE,
              "фактура " + e.getKey(),
              () -> post(ctx, e.getKey(), e.getValue())));
    }
    return out;
  }

  static int operationType(String kind) {
    switch (kind) {
      case "SALE":
      case "SALE_ADVANCE":
      case "SALE_FINAL":
        return InvoiceRepository.OPERATION_TYPE_CLIENT_SALE;
      case "SALE_REFUND":
        return InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND;
      case "PURCHASE":
        return InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE;
      case "PURCHASE_REFUND":
        return InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND;
      default:
        throw new IllegalArgumentException("Непознат вид документ: " + kind);
    }
  }

  static void post(BgDemoContext ctx, String doc, List<BgDemoCsv.Row> rows) throws Exception {
    BgDemoCsv.Row head = rows.get(0);
    String kind = head.get("kind");
    Long id = BgDemoTx.call(() -> draft(ctx, doc, kind, rows).getId());
    InvoiceService invoices = Beans.get(InvoiceService.class);
    if ("SALE_ADVANCE".equals(kind)) {
      // Авансовата се валидира (не се осчетоводява като окончателна) — по схема А в Nyxa ДДС
      // статията ѝ се прави при валидиране; приспада я окончателната.
      BgDemoTx.run(() -> invoices.validate(JPA.find(Invoice.class, id)));
    } else {
      if ("SALE_FINAL".equals(kind)) {
        BgDemoTx.run(
            () -> Beans.get(BgVatAdvanceService.class).deduct(JPA.find(Invoice.class, id)));
        BgDemoTx.run(() -> invoices.compute(JPA.find(Invoice.class, id)));
      }
      BgDemoTx.run(() -> invoices.validateAndVentilate(JPA.find(Invoice.class, id)));
      BgDemoStockStep.forInvoice(ctx, id);
      protocol(id);
    }
    ctx.put("invoice", doc, JPA.find(Invoice.class, id));
  }

  /**
   * Протоколът по чл. 117 ЗДДС за покупка с обратно начисляване (ВОП, услуга от ЕС, услуга от трета
   * страна по чл. 82) — бутонът „Състави протокол по чл. 117“ на фактурата, с датата по
   * подразбиране на услугата.
   */
  private static void protocol(Long invoiceId) throws Exception {
    BgDemoTx.run(
        () -> {
          Invoice invoice = JPA.find(Invoice.class, invoiceId);
          if (!InvoiceToolService.isPurchase(invoice)
              || invoice.getInvoiceLineList().stream()
                  .allMatch(l -> BgVatInvoiceProtocolCase.reverseChargeTaxCodes(l).isEmpty())) {
            return;
          }
          BgVatInvoiceProtocolService.Result result =
              Beans.get(BgVatInvoiceProtocolService.class).generate(invoice, null);
          if (!result.getErrors().isEmpty()) {
            throw new IllegalStateException(String.join("; ", result.getErrors()));
          }
        });
  }

  private static Invoice draft(BgDemoContext ctx, String doc, String kind, List<BgDemoCsv.Row> rows)
      throws AxelorException {
    BgDemoCsv.Row head = rows.get(0);
    Partner partner = ctx.find(Partner.class, "partner", head.get("partner"));
    int op = operationType(kind);
    boolean purchase =
        op == InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
            || op == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND;

    InvoiceGenerator header =
        new InvoiceGenerator(
            op,
            ctx.company(),
            null,
            null,
            null,
            partner,
            null,
            partner.getCurrency(),
            null,
            doc,
            purchase ? doc : null,
            false,
            null,
            null,
            null,
            null) {
          @Override
          public Invoice generate() throws AxelorException {
            return createInvoiceHeader();
          }
        };
    Invoice invoice = header.generate();
    // Празни списъци, както ги дава формата (лентирането на известие чете плащанията).
    if (invoice.getInvoicePaymentList() == null) {
      invoice.setInvoicePaymentList(new ArrayList<>());
    }
    LocalDate date = ctx.dates().of(head.get("date"));
    invoice.setInvoiceDate(date);
    // Вид на документа за НАП — полето във формата: фактура или кредитно известие.
    invoice.setNapDocumentTypeSelect(
        op == InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND
                || op == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND
            ? "03"
            : "01");
    invoice.setFiscalPosition(partner.getFiscalPosition());
    if (!head.opt("fiscalPosition").isEmpty()) {
      // Друга позиция за този документ (износ към партньор, който иначе доставя с обратно
      // начисляване) — както полето „Фискална позиция“ във фактурата.
      invoice.setFiscalPosition(
          JPA.all(FiscalPosition.class)
              .filter("self.code = ?1", head.opt("fiscalPosition"))
              .fetchOne());
    }
    if ("SALE_ADVANCE".equals(kind)) {
      invoice.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE);
    }
    if (purchase) {
      invoice.setSupplierInvoiceNb(doc);
      invoice.setOriginDate(date);
    }
    if (head.has("taxEvent")) {
      invoice.setBgVatTaxEventDate(ctx.dates().of(head.get("taxEvent")));
    }
    if (head.has("refundOf")) {
      invoice.setOriginalInvoice(ctx.find(Invoice.class, "invoice", head.get("refundOf")));
    }
    if (head.has("note")) {
      invoice.setNote(head.get("note"));
    }

    List<InvoiceLine> lines = new ArrayList<>();
    int seq = 0;
    for (BgDemoCsv.Row r : rows) {
      lines.add(line(ctx, invoice, r, seq++));
    }
    invoice.setInvoiceLineList(new ArrayList<>());
    lines.forEach(invoice::addInvoiceLineListItem);
    BgDemoAnalyticStep.distribute(ctx, invoice);
    Beans.get(InvoiceService.class).compute(invoice);
    return Beans.get(InvoiceRepository.class).save(invoice);
  }

  private static InvoiceLine line(BgDemoContext ctx, Invoice invoice, BgDemoCsv.Row r, int seq)
      throws AxelorException {
    Product product = ctx.find(Product.class, "product", r.get("product"));
    BigDecimal price = r.decimal("price");
    BigDecimal qty = r.decimal("qty");
    java.util.Set<TaxLine> forced = null;
    if (r.has("tax")) {
      Tax tax = JPA.all(Tax.class).filter("self.code = ?1", r.get("tax")).fetchOne();
      if (tax == null || tax.getActiveTaxLine() == null) {
        throw new IllegalArgumentException(r.where() + ": няма данък " + r.get("tax"));
      }
      forced = new java.util.HashSet<>(Collections.singleton(tax.getActiveTaxLine()));
    }
    java.util.Set<TaxLine> taxLines = forced;
    InvoiceLineGenerator gen =
        new InvoiceLineGenerator(
            invoice,
            product,
            product.getName(),
            price,
            price,
            price,
            r.opt("note"),
            qty,
            product.getUnit(),
            taxLines,
            seq,
            BigDecimal.ZERO,
            0,
            null,
            null,
            false) {
          @Override
          public List<InvoiceLine> creates() throws AxelorException {
            return Collections.singletonList(createInvoiceLine());
          }
        };
    InvoiceLine line = gen.creates().get(0);
    if (r.has("credit")) {
      line.setBgVatCreditType(r.get("credit"));
    }
    if (!r.opt("account").isEmpty()) {
      Account account = BgDemoPartnerStep.account(invoice.getCompany(), r.opt("account"));
      if (account.getVatSystemSelect() == AccountRepository.VAT_SYSTEM_DEFAULT) {
        // Сметките за ДМА в BG_COA (20x) са без режим на ДДС, а Axelor го иска за ред с данък —
        // както 304/60x/70x на шаблона: „стоки“. Само за сметката на това дружество.
        account.setVatSystemSelect(AccountRepository.VAT_SYSTEM_GOODS);
        JPA.save(account);
      }
      line.setAccount(account);
    }
    if (!r.opt("asset").isEmpty()) {
      // Ред „ДМА“ с категория — при осчетоводяването Axelor създава активите (чернови).
      line.setFixedAssets(true);
      line.setFixedAssetCategory(BgDemoAssetStep.category(invoice.getCompany(), r.opt("asset")));
    }
    if (r.has("income")) {
      line.setBgIncomeType(
          JPA.all(BgIncomeType.class).filter("self.code = ?1", r.get("income")).fetchOne());
      if (line.getBgIncomeType() == null) {
        throw new IllegalArgumentException(r.where() + ": няма вид доход " + r.get("income"));
      }
    }
    return line;
  }
}
