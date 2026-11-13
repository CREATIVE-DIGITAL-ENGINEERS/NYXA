package com.axelor.apps.bglocalization.service.demo;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoicePayment;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.repo.InvoicePaymentRepository;
import com.axelor.apps.account.service.payment.invoice.payment.InvoicePaymentCreateService;
import com.axelor.apps.account.service.payment.invoice.payment.InvoiceTermPaymentService;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.db.JPA;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * ПЛАЩАНИЯТА ПО ФАКТУРИТЕ — {@code payments.csv}: {@code date;doc;amount;bank;mode}.
 *
 * <p>Както бутонът „Регистрирай плащане“: плащане със сума, дата, начин на плащане и банкова сметка
 * на дружеството, разпределено по падежите на фактурата, после „Валидирай“ — Axelor съставя
 * статията по банката/касата и лентира 411/401. Сумата е във валутата на фактурата (празна — целият
 * остатък). {@code bank} е кодът от {@code banks.csv} ({@code EUR}, {@code USD}) или {@code CASH}
 * за каса; {@code mode} — кодът на начина на плащане: {@code INC_BT}/{@code OUT_BT} („Банков
 * превод“ на набора; по сметките във валута — {@code INC_BTV}/{@code OUT_BTV}, вж. {@link
 * BgDemoCompanyStep#BANK_TRANSFER_MODES}) или {@code INC_CSH}/{@code OUT_CSH} от BG_COA.
 *
 * <p>⚠️ Не „Постъпил/Изходящ банков превод“ на BG_COA (INC_WT/OUT_WT): там е вдигнато „генерирай
 * банково нареждане“ без формат на нареждането и всяко плащане пада (NullPointerException в
 * BankOrderCreateService). И не „Друго постъпление/плащане“ (INC_OTH/OUT_OTH): SAF-T не ги описва
 * (Nom_PaymentMethod няма „друго“).
 *
 * <p>Плащане в USD с курс, различен от курса на фактурата, дава курсова разлика (624/724) — по
 * правилата на Axelor, нищо не се смята тук.
 */
final class BgDemoPaymentStep {

  private BgDemoPaymentStep() {}

  static List<BgDemoEvent> events(BgDemoContext ctx) {
    List<BgDemoEvent> out = new ArrayList<>();
    for (BgDemoCsv.Row r : ctx.rows("payments")) {
      LocalDate date = ctx.dates().of(r.get("date"));
      out.add(
          new BgDemoEvent(
              date,
              "CASH".equals(r.get("bank")) ? BgDemoEvent.RANK_CASH : BgDemoEvent.RANK_PAYMENT,
              "плащане по " + r.get("doc"),
              () -> pay(ctx, r, date)));
    }
    return out;
  }

  static void pay(BgDemoContext ctx, BgDemoCsv.Row r, LocalDate date) throws Exception {
    BgDemoTx.run(
        () -> {
          Invoice invoice = ctx.find(Invoice.class, "invoice", r.get("doc"));
          PaymentMode mode =
              JPA.all(PaymentMode.class).filter("self.code = ?1", r.get("mode")).fetchOne();
          if (mode == null) {
            throw new IllegalArgumentException(r.where() + ": няма начин на плащане");
          }
          BankDetails bank =
              "CASH".equals(r.get("bank"))
                  ? null
                  : ctx.find(BankDetails.class, "bank", r.get("bank"));
          BigDecimal amount =
              r.has("amount")
                  ? r.decimal("amount")
                  : invoice.getInTaxTotal().subtract(invoice.getAmountPaid());
          InvoicePayment p =
              Beans.get(InvoicePaymentCreateService.class)
                  .createInvoicePayment(
                      invoice,
                      amount,
                      date,
                      invoice.getCurrency(),
                      mode,
                      InvoicePaymentRepository.TYPE_PAYMENT);
          p.setCompanyBankDetails(bank);
          // Описанието отива в редовете на статията (и на курсовата разлика); дневник „Банка“
          // го изисква.
          p.setDescription(r.opt("note").isEmpty() ? "Плащане по " + r.get("doc") : r.opt("note"));
          invoice.addInvoicePaymentListItem(p);
          Beans.get(InvoiceTermPaymentService.class).createInvoicePaymentTerms(p, null);
          // Записът на чернова валидира плащането (InvoicePaymentManagementRepository) —
          // както от формата „Регистрирай плащане“.
          Beans.get(InvoicePaymentRepository.class).save(p);
        });
  }
}
