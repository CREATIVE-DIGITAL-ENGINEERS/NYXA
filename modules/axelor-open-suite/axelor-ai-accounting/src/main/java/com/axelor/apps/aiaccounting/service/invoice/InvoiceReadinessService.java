package com.axelor.apps.aiaccounting.service.invoice;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.service.PaymentConditionService;
import com.axelor.apps.account.service.accountingsituation.AccountingSituationService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceReadinessContributorRegistry;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.repo.SequenceVersionRepository;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * What still blocks an invoice from being validated or posted, for either direction.
 *
 * <h2>Read-only, and honest about what it could not check</h2>
 *
 * Nothing here validates, posts, or changes anything. Where a check cannot be run — no accounting
 * configuration for the company, a service that is not bound — the signal is left unset and no
 * blocker is invented from it. Reporting "not configured" because a lookup failed would send
 * somebody to fix a setting that is already correct.
 *
 * <h2>Each side gets its own journal, sequence and account — not the supplier ones</h2>
 *
 * This is the part that could quietly have been half-right. Axelor picks the journal from {@code
 * operationTypeSelect} <em>and</em> the sign of the gross total ({@code
 * InvoiceJournalServiceImpl.getJournal}): a supplier purchase with a negative total uses the
 * supplier credit-note journal, a customer sale with a negative total the customer credit-note one.
 * The invoice sequence is chosen the same way at ventilation ({@code VentilateState.getSequence}),
 * from four separate configured sequences. So both are selected here by exactly that rule rather
 * than by the side alone, and the receivable/payable account comes from {@code
 * AccountingSituationService.getPartnerAccount(invoice, …)}, which reads the direction off the
 * invoice itself.
 *
 * <h2>The rules are pure; only the signals touch the database</h2>
 *
 * {@link #evaluate(Signals)} is a function of its input, so the rules can be tested without a Guice
 * or JPA runtime. {@link #check(Invoice, String)} gathers the signals defensively and then calls it
 * — the same split the OCR readiness service uses, and for the same reason.
 */
@Singleton
public class InvoiceReadinessService {

  /** Pure input to the rules. Every field is a fact about the invoice or the configuration. */
  public static class Signals {
    public InvoiceSide side = InvoiceSide.CUSTOMER;
    public boolean partnerPresent;
    public boolean currencyPresent;
    public boolean invoiceDatePresent;
    public boolean originDatePresent;
    public boolean supplierInvoiceNbPresent;
    public boolean paymentModePresent;
    public boolean paymentConditionPresent;
    public String paymentConditionProblem;
    public int lineCount;
    public int linesMissingAccount;
    public int linesMissingTax;
    public boolean journalOnInvoice;
    public Boolean journalConfigured;
    public String journalConfigProblem;
    public Boolean sequenceConfigured;
    public String sequenceConfigProblem;
    public boolean sequenceVersionMissing;
    public String sequenceLabel;
    public String sequenceRefDate;
    public Boolean partnerAccountResolvable;
    public boolean partnerAccountReconcilable;
    public String partnerAccountLabel;
    public BigDecimal inTaxTotal;
    public final List<String> extraBlockers = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();
  }

  /** The rules. A function of the signals and nothing else. */
  public InvoiceReadiness evaluate(Signals s) {
    List<String> validate = new ArrayList<>();
    List<String> ventilate = new ArrayList<>();
    List<String> warnings = new ArrayList<>(s.warnings);

    if (!s.partnerPresent) {
      validate.add(
          s.side == InvoiceSide.CUSTOMER
              ? "The invoice has no customer."
              : "The invoice has no supplier.");
    }
    if (!s.currencyPresent) {
      validate.add("The invoice has no currency.");
    }
    if (!s.invoiceDatePresent) {
      validate.add("The invoice has no invoice date.");
    }
    if (s.lineCount == 0) {
      validate.add("The invoice has no lines, so there is nothing to validate.");
    }
    if (s.linesMissingAccount > 0) {
      validate.add(
          s.linesMissingAccount
              + " line(s) have no account. Every line needs one before the invoice can be"
              + " validated — set the product, or the account directly.");
    }
    if (s.linesMissingTax > 0) {
      warnings.add(
          s.linesMissingTax
              + " line(s) carry no tax. That is legitimate for an exempt supply and a mistake"
              + " otherwise; it is not checked further here.");
    }
    // Supplier-only, and exactly what the form requires at validation: the number and date the
    // OTHER party put on the document. A customer invoice has neither, because this company has not
    // issued its number yet — that happens at ventilation.
    if (s.side == InvoiceSide.SUPPLIER) {
      if (!s.supplierInvoiceNbPresent) {
        validate.add("The supplier's own invoice number is missing.");
      }
      if (!s.originDatePresent) {
        validate.add("The supplier invoice's origin date is missing.");
      }
    }
    if (!s.paymentConditionPresent) {
      validate.add("The invoice has no payment condition.");
    } else if (s.paymentConditionProblem != null) {
      validate.add("The payment condition cannot be used: " + s.paymentConditionProblem);
    }
    if (Boolean.FALSE.equals(s.journalConfigured) && !s.journalOnInvoice) {
      validate.add(
          "No journal is configured for this kind of invoice in the company's accounting"
              + " configuration"
              + (s.journalConfigProblem == null ? "" : " (" + s.journalConfigProblem + ")")
              + ". Validation is what assigns the journal, so it cannot run until somebody"
              + " configures it.");
    }
    if (Boolean.FALSE.equals(s.partnerAccountResolvable)) {
      validate.add(
          (s.side == InvoiceSide.CUSTOMER ? "No receivable" : "No payable")
              + " account can be resolved for this partner and company. That is a partner or"
              + " company configuration decision.");
    } else if (Boolean.TRUE.equals(s.partnerAccountResolvable) && !s.partnerAccountReconcilable) {
      validate.add(
          "The partner account "
              + (s.partnerAccountLabel == null ? "" : s.partnerAccountLabel + " ")
              + "does not have both 'reconcile' and 'use for partner balance' turned on.");
    }
    for (String extra : s.extraBlockers) {
      validate.add(extra);
    }

    // Posting runs through validation, so everything above blocks it too.
    ventilate.addAll(validate);

    if (!s.paymentModePresent) {
      ventilate.add("The invoice has no payment mode, which posting requires.");
    }
    if (Boolean.FALSE.equals(s.sequenceConfigured)) {
      ventilate.add(
          "No invoice sequence is configured for this kind of invoice"
              + (s.sequenceConfigProblem == null ? "" : " (" + s.sequenceConfigProblem + ")")
              + ". Posting is what assigns the invoice number, so it cannot run until somebody"
              + " configures it.");
    }
    if (s.sequenceVersionMissing) {
      ventilate.add(
          "The sequence "
              + (s.sequenceLabel == null ? "" : s.sequenceLabel + " ")
              + "has no version covering "
              + s.sequenceRefDate
              + ", so no number can be assigned for that date.");
    }
    if (s.inTaxTotal != null && s.inTaxTotal.signum() == 0) {
      warnings.add("The invoice totals zero, so posting it will produce no accounting move.");
    }

    return new InvoiceReadiness(validate, ventilate, warnings);
  }

  /** Gathers the signals from the real services, then evaluates. Read-only, never throws. */
  public InvoiceReadiness check(Invoice invoice, String task) {
    Signals s = new Signals();
    if (invoice == null) {
      return evaluate(s);
    }
    s.side = InvoiceSide.SUPPLIER.covers(invoice) ? InvoiceSide.SUPPLIER : InvoiceSide.CUSTOMER;
    s.partnerPresent = invoice.getPartner() != null;
    s.currencyPresent = invoice.getCurrency() != null;
    s.invoiceDatePresent = invoice.getInvoiceDate() != null;
    s.originDatePresent = invoice.getOriginDate() != null;
    s.supplierInvoiceNbPresent = notBlank(invoice.getSupplierInvoiceNb());
    s.paymentModePresent = invoice.getPaymentMode() != null;
    s.paymentConditionPresent = invoice.getPaymentCondition() != null;
    s.journalOnInvoice = invoice.getJournal() != null;
    s.inTaxTotal = invoice.getInTaxTotal();

    if (s.paymentConditionPresent) {
      PaymentCondition condition = invoice.getPaymentCondition();
      try {
        // Axelor's own rule, called rather than copied: whatever it enforces at ventilation is
        // exactly what is reported here.
        Beans.get(PaymentConditionService.class).checkPaymentCondition(condition);
      } catch (Exception e) {
        String message = e.getMessage();
        s.paymentConditionProblem =
            message == null || message.trim().isEmpty()
                ? "it has no usable payment-condition lines."
                : message.trim();
      }
    }

    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines != null) {
      s.lineCount = lines.size();
      for (InvoiceLine line : lines) {
        if (line.getAccount() == null) {
          s.linesMissingAccount++;
        }
        if (line.getTaxLineSet() == null || line.getTaxLineSet().isEmpty()) {
          s.linesMissingTax++;
        }
      }
    }

    Company company = invoice.getCompany();
    if (company != null) {
      AccountConfig accountConfig = null;
      try {
        accountConfig = Beans.get(AccountConfigService.class).getAccountConfig(company);
      } catch (Exception e) {
        // No accounting configuration for this company at all. Left unset rather than reported as
        // four separate missing settings.
      }
      if (accountConfig != null) {
        gatherJournal(invoice, accountConfig, s);
        gatherSequence(invoice, accountConfig, s);
      }
    }

    try {
      Account partnerAccount =
          Beans.get(AccountingSituationService.class).getPartnerAccount(invoice, false);
      s.partnerAccountResolvable = partnerAccount != null;
      if (partnerAccount != null) {
        s.partnerAccountLabel =
            (partnerAccount.getCode() == null ? "" : partnerAccount.getCode())
                + (partnerAccount.getName() == null ? "" : " " + partnerAccount.getName());
        s.partnerAccountReconcilable =
            Boolean.TRUE.equals(partnerAccount.getReconcileOk())
                && Boolean.TRUE.equals(partnerAccount.getUseForPartnerBalance());
      }
    } catch (Exception e) {
      // Unknown rather than absent: an unbound service is not evidence of a missing account.
    }

    s.extraBlockers.addAll(
        Beans.get(AiInvoiceReadinessContributorRegistry.class).extraBlockers(invoice, task));

    return evaluate(s);
  }

  /**
   * The journal Axelor would assign, by operation type AND the sign of the gross total.
   *
   * <p>The same switch as {@code InvoiceJournalServiceImpl.getJournal}, because that is the code
   * that will actually run at validation. Choosing by side alone would report a credit note's
   * journal as configured when the one it needs is not.
   */
  protected void gatherJournal(Invoice invoice, AccountConfig config, Signals s) {
    AccountConfigService service = Beans.get(AccountConfigService.class);
    boolean negative = invoice.getInTaxTotal() != null && invoice.getInTaxTotal().signum() < 0;
    Integer type = invoice.getOperationTypeSelect();
    if (type == null) {
      return;
    }
    try {
      Journal journal;
      switch (type) {
        case InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE:
          journal =
              negative
                  ? service.getSupplierCreditNoteJournal(config)
                  : service.getSupplierPurchaseJournal(config);
          break;
        case InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND:
          journal =
              negative
                  ? service.getSupplierPurchaseJournal(config)
                  : service.getSupplierCreditNoteJournal(config);
          break;
        case InvoiceRepository.OPERATION_TYPE_CLIENT_SALE:
          journal =
              negative
                  ? service.getCustomerCreditNoteJournal(config)
                  : service.getCustomerSalesJournal(config);
          break;
        case InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND:
          journal =
              negative
                  ? service.getCustomerSalesJournal(config)
                  : service.getCustomerCreditNoteJournal(config);
          break;
        default:
          return;
      }
      s.journalConfigured = journal != null;
    } catch (Exception e) {
      s.journalConfigured = Boolean.FALSE;
      s.journalConfigProblem = shortMessage(e);
    }
  }

  /**
   * The sequence ventilation would draw the invoice number from, and whether it covers the date.
   *
   * <p>Four configured sequences, chosen by operation type exactly as {@code
   * VentilateState.getSequence} chooses them. The version check is the trap worth reporting early:
   * a configured sequence with no version covering the invoice date fails at posting with a message
   * about the sequence, long after the work is done.
   */
  protected void gatherSequence(Invoice invoice, AccountConfig config, Signals s) {
    Integer type = invoice.getOperationTypeSelect();
    if (type == null) {
      return;
    }
    Sequence sequence;
    switch (type) {
      case InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE:
        sequence = config.getSuppInvSequence();
        break;
      case InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND:
        sequence = config.getSuppRefSequence();
        break;
      case InvoiceRepository.OPERATION_TYPE_CLIENT_SALE:
        sequence = config.getCustInvSequence();
        break;
      case InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND:
        sequence = config.getCustRefSequence();
        break;
      default:
        return;
    }
    s.sequenceConfigured = sequence != null;
    LocalDate refDate = invoice.getInvoiceDate();
    if (sequence == null || refDate == null) {
      return;
    }
    try {
      if (Beans.get(SequenceVersionRepository.class).findByDate(sequence, refDate) == null) {
        s.sequenceVersionMissing = true;
        s.sequenceLabel =
            sequence.getFullName() == null ? sequence.getName() : sequence.getFullName();
        s.sequenceRefDate = refDate.toString();
      }
    } catch (Exception e) {
      // Unknown, so say nothing. Never refuse a posting on a check that could not run.
    }
  }

  private static String shortMessage(Exception e) {
    String message = e.getMessage();
    return message == null || message.trim().isEmpty() ? null : message.trim();
  }

  private static boolean notBlank(String value) {
    return value != null && !value.trim().isEmpty();
  }
}
