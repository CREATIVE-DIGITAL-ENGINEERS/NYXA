package com.axelor.apps.aiocr.service.readiness;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.repo.InvoiceLineRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.account.service.accountingsituation.AccountingSituationService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.invoice.InvoiceJournalService;
import com.axelor.apps.account.service.invoice.InvoiceToolService;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.PeriodService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.common.ObjectUtils;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Why Validate or Ventilate would refuse a supplier invoice, said before the click, in the user's
 * language and with the place to fix it.
 *
 * <p>Axelor refuses these deep inside validation or posting, with a message that names the rule
 * ("No period found or it has been closed…", "Bank details is required.") but not the field — and
 * sometimes in English. Each check here repeats one of those refusals exactly, on the same data, so
 * it can never refuse what Axelor would accept: the conditions are read from {@code ValidateState},
 * {@code VentilateState}, {@code InvoiceServiceImpl.checkPreconditions}, {@code
 * MoveSequenceService}, {@code PeriodService} and {@code MoveLineControlServiceImpl}. It changes
 * nothing.
 *
 * <p>The rules live in the pure {@link #blockers(Facts, Step, Function)} so they can be tested with
 * no database; {@link #gather} reads the facts from the invoice on the form and the company's
 * configuration, defensively — a fact that cannot be established is left unset, and an unset fact
 * never refuses.
 */
@Singleton
public class SupplierInvoiceButtonCheck {

  /** The button being pressed. Validate+Ventilate runs both. */
  public enum Step {
    VALIDATE,
    VENTILATE
  }

  public static final String PAYMENT_MODE_WRONG_DIRECTION =
      /*$$(*/ "The payment mode %s is not for this kind of invoice (incoming instead of outgoing, or the reverse). Choose another one in the field Payment mode." /*)*/;

  public static final String PARTNER_ACCOUNT_MISSING =
      /*$$(*/ "There is no supplier account for this supplier. Set it on the supplier (tab Accounting, Accounting situation) or as the company's default (Accounting configuration of the company)." /*)*/;

  public static final String PARTNER_ACCOUNT_FLAGS_OFF =
      /*$$(*/ "The supplier account %s must have Reconcilable and Use for partner balance switched on. Open the account in the chart of accounts and switch both on." /*)*/;

  public static final String JOURNAL_MISSING =
      /*$$(*/ "The company has no purchase journal for this kind of invoice. Set it in the Accounting configuration of the company, tab Journals." /*)*/;

  public static final String PAYMENT_CONDITION_MISSING =
      /*$$(*/ "The payment condition is missing. Fill in the field Payment condition." /*)*/;

  public static final String PAYMENT_MODE_MISSING =
      /*$$(*/ "The payment mode is missing. Fill in the field Payment mode." /*)*/;

  public static final String LINE_ACCOUNT_MISSING =
      /*$$(*/ "Line %s has no account. Open the line, tab Accounting, field Account." /*)*/;

  public static final String INVOICE_DATE_IN_FUTURE =
      /*$$(*/ "The invoice date %s is in the future. Correct the field Invoice date." /*)*/;

  public static final String ORIGIN_DATE_MISSING =
      /*$$(*/ "The date of the supplier's document is missing. Fill in the field Origin date." /*)*/;

  public static final String ORIGIN_DATE_IN_FUTURE =
      /*$$(*/ "The date of the supplier's document %s is in the future. Correct the field Origin date." /*)*/;

  public static final String PERIOD_MISSING =
      /*$$(*/ "There is no open accounting period for the invoice date %s. Open or create the period in the company's fiscal year (Accounting configuration, Fiscal years)." /*)*/;

  public static final String ACCOUNT_NOT_IN_JOURNAL =
      /*$$(*/ "Line %s: account %s is not allowed in journal %s. Add the account, or its account type, to the journal (fields Compatible Accounts / Compatible Account Types), or choose another account on the line." /*)*/;

  public static final String INVOICE_SEQUENCE_MISSING =
      /*$$(*/ "The company has no numbering for supplier invoices. Set it in the Accounting configuration of the company, tab Sequences." /*)*/;

  public static final String JOURNAL_SEQUENCE_MISSING =
      /*$$(*/ "Journal %s has no numbering for its entries. Open the journal and fill in the field Sequence." /*)*/;

  /** What the checks look at, nothing else. Unset means "not established", never "wrong". */
  public static class Facts {
    public boolean supplierInvoice;
    public String paymentModeWrongDirection;
    public boolean partnerAccountMissing;
    public String partnerAccountFlagsOff;
    public boolean journalMissing;
    public boolean paymentConditionMissing;
    public boolean paymentModeMissing;

    /** 1-based positions of the normal lines with no account. */
    public final List<Integer> linesWithoutAccount = new ArrayList<>();

    /** {@code {position, account code}} of each line whose account the journal does not accept. */
    public final List<String[]> linesNotInJournal = new ArrayList<>();

    public String journalCode;
    public LocalDate today;
    public LocalDate invoiceDate;
    public LocalDate originDate;

    /**
     * Set by the Ventilate button: when the supplier number or the origin date is empty, Axelor
     * asks for both in a pop-up before posting, so an empty origin date is not a refusal there. The
     * single Validate+Ventilate button has no pop-up.
     */
    public boolean popupAsksOriginDate;

    public boolean periodMissing;
    public boolean invoiceSequenceMissing;
    public boolean journalSequenceMissing;
  }

  /**
   * The refusals {@code step} would hit, in the order Axelor would hit them, already in the user's
   * language. Empty when nothing is known to stand in the way, and always empty for anything that
   * is not a supplier invoice or refund.
   */
  public List<String> blockers(Facts f, Step step, Function<String, String> translate) {
    List<String> out = new ArrayList<>();
    if (!f.supplierInvoice) {
      return out;
    }
    if (step == Step.VALIDATE) {
      if (f.paymentModeWrongDirection != null) {
        out.add(format(translate, PAYMENT_MODE_WRONG_DIRECTION, f.paymentModeWrongDirection));
      }
      if (f.journalMissing) {
        out.add(translate.apply(JOURNAL_MISSING));
      }
    }
    if (f.partnerAccountMissing) {
      out.add(translate.apply(PARTNER_ACCOUNT_MISSING));
    } else if (f.partnerAccountFlagsOff != null) {
      out.add(format(translate, PARTNER_ACCOUNT_FLAGS_OFF, f.partnerAccountFlagsOff));
    }
    if (step == Step.VALIDATE) {
      return out;
    }

    if (f.paymentConditionMissing) {
      out.add(translate.apply(PAYMENT_CONDITION_MISSING));
    }
    if (f.paymentModeMissing) {
      out.add(translate.apply(PAYMENT_MODE_MISSING));
    }
    for (Integer line : f.linesWithoutAccount) {
      out.add(format(translate, LINE_ACCOUNT_MISSING, String.valueOf(line)));
    }
    if (f.today != null && f.invoiceDate != null && f.invoiceDate.isAfter(f.today)) {
      out.add(format(translate, INVOICE_DATE_IN_FUTURE, f.invoiceDate.toString()));
    }
    if (f.originDate == null) {
      if (!f.popupAsksOriginDate) {
        out.add(translate.apply(ORIGIN_DATE_MISSING));
      }
    } else if (f.today != null && f.originDate.isAfter(f.today)) {
      out.add(format(translate, ORIGIN_DATE_IN_FUTURE, f.originDate.toString()));
    }
    if (f.periodMissing) {
      out.add(format(translate, PERIOD_MISSING, String.valueOf(f.invoiceDate)));
    }
    if (f.invoiceSequenceMissing) {
      out.add(translate.apply(INVOICE_SEQUENCE_MISSING));
    }
    if (f.journalSequenceMissing) {
      out.add(format(translate, JOURNAL_SEQUENCE_MISSING, f.journalCode));
    }
    for (String[] line : f.linesNotInJournal) {
      out.add(format(translate, ACCOUNT_NOT_IN_JOURNAL, line[0], line[1], f.journalCode));
    }
    return out;
  }

  /** The blockers of {@code step} for the invoice as it is on the form, in the user's language. */
  public List<String> blockers(Invoice invoice, Step step) {
    return blockers(gather(invoice), step, I18n::get);
  }

  /** Reads the facts. Read-only: nothing here saves, computes or numbers the invoice. */
  public Facts gather(Invoice invoice) {
    Facts f = new Facts();
    Integer type = invoice.getOperationTypeSelect();
    f.supplierInvoice =
        type != null
            && (type == InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
                || type == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND);
    if (!f.supplierInvoice || invoice.getCompany() == null) {
      f.supplierInvoice = false;
      return f;
    }

    // ValidateState: the payment mode's direction must match the invoice's.
    if (invoice.getPaymentMode() != null && invoice.getPaymentMode().getInOutSelect() != null) {
      try {
        boolean out = InvoiceToolService.isOutPayment(invoice);
        int direction = invoice.getPaymentMode().getInOutSelect();
        if ((out && direction == PaymentModeRepository.IN)
            || (!out && direction == PaymentModeRepository.OUT)) {
          f.paymentModeWrongDirection = invoice.getPaymentMode().getName();
        }
      } catch (Exception e) {
        // direction not established: say nothing
      }
    }

    // ValidateState.setPartnerAccount / VentilateState: the payable account and its two flags.
    try {
      Account payable =
          invoice.getPartnerAccount() != null
              ? invoice.getPartnerAccount()
              : Beans.get(AccountingSituationService.class).getPartnerAccount(invoice, false);
      if (payable == null) {
        f.partnerAccountMissing = true;
      } else if (!Boolean.TRUE.equals(payable.getReconcileOk())
          || !Boolean.TRUE.equals(payable.getUseForPartnerBalance())) {
        f.partnerAccountFlagsOff = payable.getCode();
      }
    } catch (Exception e) {
      f.partnerAccountMissing = true;
    }

    // ValidateState: the journal, from the invoice or the company configuration.
    Journal journal = invoice.getJournal();
    if (journal == null) {
      try {
        journal = Beans.get(InvoiceJournalService.class).getJournal(invoice);
      } catch (Exception e) {
        journal = null;
      }
      f.journalMissing = journal == null;
    }

    // InvoiceServiceImpl.checkPreconditions.
    f.paymentConditionMissing = invoice.getPaymentCondition() == null;
    f.paymentModeMissing = invoice.getPaymentMode() == null;

    // VentilateState.setDate.
    try {
      f.today = Beans.get(AppBaseService.class).getTodayDate(invoice.getCompany());
    } catch (Exception e) {
      f.today = null;
    }
    f.invoiceDate = invoice.getInvoiceDate();
    f.originDate = invoice.getOriginDate();

    // MoveCreateService: the move takes the invoice date (today when it is empty), and its period
    // must be open — PeriodService decides, including who may still post in a closed one.
    LocalDate moveDate = f.invoiceDate != null ? f.invoiceDate : f.today;
    if (moveDate != null) {
      try {
        Beans.get(PeriodService.class)
            .getActivePeriod(moveDate, invoice.getCompany(), YearRepository.TYPE_FISCAL);
      } catch (Exception e) {
        f.periodMissing = true;
        f.invoiceDate = moveDate;
      }
    }

    // VentilateState.setInvoiceId: the supplier invoice / refund numbering.
    try {
      AccountConfig config =
          Beans.get(AccountConfigService.class).getAccountConfig(invoice.getCompany());
      f.invoiceSequenceMissing =
          type == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND
              ? config.getSuppRefSequence() == null
              : config.getSuppInvSequence() == null;
    } catch (Exception e) {
      // no configuration: Axelor names that itself, and the journal check above already fails
    }

    // MoveSequenceService and MoveLineControlServiceImpl: the journal's numbering and the accounts
    // it accepts.
    if (journal != null) {
      f.journalCode = journal.getCode();
      f.journalSequenceMissing = journal.getSequence() == null;
    }
    readLines(f, invoice.getInvoiceLineList(), journal);
    return f;
  }

  /**
   * The lines' facts: which normal lines have no account, and whose account the journal does not
   * accept. Lines are named by their position on the form, counted from 1; title lines carry no
   * account and are skipped, as ventilation skips them.
   */
  protected static void readLines(Facts f, List<InvoiceLine> lines, Journal journal) {
    if (lines == null) {
      return;
    }
    for (int i = 0; i < lines.size(); i++) {
      InvoiceLine line = lines.get(i);
      if (line.getTypeSelect() != null
          && line.getTypeSelect() != InvoiceLineRepository.TYPE_NORMAL) {
        continue;
      }
      Account account = line.getAccount();
      if (account == null) {
        f.linesWithoutAccount.add(i + 1);
      } else if (journal != null && !journalAccepts(journal, account)) {
        f.linesNotInJournal.add(new String[] {String.valueOf(i + 1), account.getCode()});
      }
    }
  }

  /** {@code MoveLineControlServiceImpl.controlAccountingAccount}, without the throw. */
  protected static boolean journalAccepts(Journal journal, Account account) {
    if (!ObjectUtils.isEmpty(journal.getValidAccountSet())
        && journal.getValidAccountSet().contains(account)) {
      return true;
    }
    return !ObjectUtils.isEmpty(journal.getValidAccountTypeSet())
        && journal.getValidAccountTypeSet().contains(account.getAccountType());
  }

  private static String format(Function<String, String> translate, String key, String... args) {
    Object[] escaped = new Object[args.length];
    for (int i = 0; i < args.length; i++) {
      escaped[i] = escape(args[i]);
    }
    return String.format(translate.apply(key), escaped);
  }

  /** The message is shown as HTML; a name or code must not be read as markup. */
  private static String escape(String value) {
    if (value == null) {
      return "";
    }
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
