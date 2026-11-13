package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxEquiv;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.CurrencyConversionLine;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Product;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Фактури с обратно начисляване и {@link BgVatInvoiceProtocolService} в паметта.
 *
 * <p>Подменено е САМО записването: празната статия и редовете ({@code MoveCreateService} / {@code
 * MoveLineCreateService} в приложението), осчетоводяването с номера и проверките за период и вече
 * съставен протокол. Случаят, датите, групите, сумите и полетата на статията са истинските. Пътят
 * през базата е в {@code BgVatProtocolReachabilityIntegrationTest}.
 */
public final class BgVatInvoiceProtocolFixtures {

  public static final long ACCOUNT_VAT_PURCHASES = 4531L;
  public static final long ACCOUNT_VAT_SALES = 4532L;
  public static final long ACCOUNT_GOODS = 304L;
  public static final long ACCOUNT_SERVICES = 602L;

  private BgVatInvoiceProtocolFixtures() {}

  // ---------------------------------------------------------------------------
  // Сметки, валута, дружество, доставчик
  // ---------------------------------------------------------------------------

  public static Account account(long id, String code) {
    Account account = new Account();
    account.setId(id);
    account.setCode(code);
    account.setName(code);
    return account;
  }

  public static Currency currency(String iso) {
    Currency currency = new Currency();
    currency.setCode(iso);
    currency.setCodeISO(iso);
    currency.setName(iso);
    return currency;
  }

  public static final Currency EUR = currency("EUR");
  public static final Currency USD = currency("USD");

  public static Company company() {
    Company company = new Company();
    company.setId(1L);
    company.setName("Дружество");
    company.setCurrency(EUR);
    AccountConfig config = new AccountConfig();
    Journal journal = new Journal();
    journal.setCode("VAT");
    config.setBgVatCorrectionJournal(journal);
    config.setBgVatDeductibleAccount(account(ACCOUNT_VAT_PURCHASES, "4531"));
    config.setBgVatChargedAccount(account(ACCOUNT_VAT_SALES, "4532"));
    company.setAccountConfig(config);
    return company;
  }

  public static Partner supplier(long id, String vatNumber, String country) {
    Partner partner = new Partner();
    partner.setId(id);
    partner.setName("Доставчик " + id);
    partner.setFullName("Доставчик " + id);
    partner.setPartnerTypeSelect(1);
    partner.setTaxNbr(vatNumber);
    Country c = new Country();
    c.setAlpha2Code(country);
    Address address = new Address();
    address.setCountry(c);
    partner.setMainAddress(address);
    return partner;
  }

  // ---------------------------------------------------------------------------
  // Данъци и редове
  // ---------------------------------------------------------------------------

  public static Tax tax(String code, String percent) {
    Tax tax = new Tax();
    tax.setCode(code);
    TaxLine line = new TaxLine();
    line.setTax(tax);
    line.setValue(new BigDecimal(percent));
    tax.setActiveTaxLine(line);
    return tax;
  }

  /** Ред с обратно начисляване: собственият данък и данъкът за самоначисляването. */
  public static InvoiceLine reverseCharge(
      String ownTax,
      String selfTax,
      String percent,
      String base,
      String creditType,
      Boolean service) {
    Tax own = tax(ownTax, percent);
    Tax self = tax(selfTax, percent);
    TaxEquiv equiv = new TaxEquiv();
    equiv.setReverseCharge(true);
    equiv.setReverseChargeTaxSet(new HashSet<>(Collections.singleton(self)));
    InvoiceLine line = new InvoiceLine();
    line.setProductName(Boolean.TRUE.equals(service) ? "Услуга" : "Стока");
    if (service != null) {
      Product product = new Product();
      product.setProductTypeSelect(service ? "service" : "storable");
      line.setProduct(product);
    }
    line.setQty(BigDecimal.ONE);
    line.setExTaxTotal(new BigDecimal(base));
    line.setCompanyExTaxTotal(new BigDecimal(base));
    // Както Axelor (InvoiceLineServiceImpl): сумата с данък е по СОБСТВЕНИЯ данък на реда, без
    // оглед на обратното начисляване — 1000 при 20 % дава 1200.
    BigDecimal inTax =
        new BigDecimal(base).multiply(BigDecimal.ONE.add(new BigDecimal(percent).movePointLeft(2)));
    line.setInTaxTotal(inTax);
    line.setCompanyInTaxTotal(inTax);
    line.setTaxLineSet(new HashSet<>(Collections.singleton(own.getActiveTaxLine())));
    line.setTaxEquiv(equiv);
    line.setBgVatCreditType(creditType);
    boolean isService = Boolean.TRUE.equals(service);
    line.setAccount(
        account(isService ? ACCOUNT_SERVICES : ACCOUNT_GOODS, isService ? "602" : "304"));
    return line;
  }

  /** Ред без обратно начисляване със собствен данък. */
  public static InvoiceLine plain(String ownTax, String percent, String base, String creditType) {
    InvoiceLine line = new InvoiceLine();
    line.setProductName("Ред");
    line.setExTaxTotal(new BigDecimal(base));
    line.setCompanyExTaxTotal(new BigDecimal(base));
    BigDecimal inTax =
        new BigDecimal(base).multiply(BigDecimal.ONE.add(new BigDecimal(percent).movePointLeft(2)));
    line.setCompanyInTaxTotal(inTax);
    line.setInTaxTotal(inTax);
    line.setTaxLineSet(
        new HashSet<>(Collections.singleton(tax(ownTax, percent).getActiveTaxLine())));
    line.setBgVatCreditType(creditType);
    line.setAccount(account(ACCOUNT_GOODS, "304"));
    return line;
  }

  /**
   * Осчетоводена покупна фактура в евро. Данъчните редове са както ги прави Axelor ({@code
   * TaxInvoiceLine}): собственият данък и самоначисляването с ОТРИЦАТЕЛЕН данък и {@code
   * reverseCharged = true}.
   */
  public static Invoice invoice(
      long id,
      Partner partner,
      LocalDate invoiceDate,
      LocalDate originDate,
      LocalDate taxEvent,
      InvoiceLine... lines) {
    Invoice invoice = new Invoice();
    invoice.setId(id);
    invoice.setCompany(company());
    invoice.setCurrency(EUR);
    invoice.setPartner(partner);
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    invoice.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_DEFAULT);
    invoice.setStatusSelect(InvoiceRepository.STATUS_VENTILATED);
    invoice.setNapDocumentTypeSelect("01");
    invoice.setSupplierInvoiceNb("INV-" + id);
    invoice.setInvoiceId("SUP-" + id);
    invoice.setInvoiceDate(invoiceDate);
    invoice.setOriginDate(originDate);
    invoice.setBgVatTaxEventDate(taxEvent);
    invoice.setInvoiceLineList(new ArrayList<>());
    invoice.setInvoiceLineTaxList(new ArrayList<>());
    invoice.setBgVatProtocolMoveList(new ArrayList<>());
    for (InvoiceLine line : lines) {
      line.setInvoice(invoice);
      invoice.getInvoiceLineList().add(line);
      for (TaxLine own : line.getTaxLineSet()) {
        invoice.addInvoiceLineTaxListItem(lineTax(own, line.getCompanyExTaxTotal(), false));
      }
      if (line.getTaxEquiv() != null) {
        for (Tax self : line.getTaxEquiv().getReverseChargeTaxSet()) {
          invoice.addInvoiceLineTaxListItem(
              lineTax(self.getActiveTaxLine(), line.getCompanyExTaxTotal(), true));
        }
      }
    }
    return invoice;
  }

  private static InvoiceLineTax lineTax(TaxLine taxLine, BigDecimal base, boolean reverseCharged) {
    InvoiceLineTax row = new InvoiceLineTax();
    row.setTaxLine(taxLine);
    row.setExTaxBase(base);
    row.setCompanyExTaxBase(base);
    BigDecimal tax = base.multiply(taxLine.getValue()).movePointLeft(2);
    row.setTaxTotal(reverseCharged ? tax.negate() : tax);
    row.setCompanyTaxTotal(reverseCharged ? tax.negate() : tax);
    row.setReverseCharged(reverseCharged);
    return row;
  }

  /** Статията на фактурата — така я вижда колекторът ({@code Move.invoice}). */
  public static Move invoiceMove(Invoice invoice) {
    Move move = new Move();
    move.setId(500L + invoice.getId());
    move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    move.setDate(invoice.getInvoiceDate());
    move.setCompany(invoice.getCompany());
    move.setPartner(invoice.getPartner());
    move.setInvoice(invoice);
    move.setMoveLineList(new ArrayList<>());
    invoice.setMove(move);
    return move;
  }

  /**
   * Ръчният протокол, еквивалентен на съставения от фактурата: същите статия, редове, номер,
   * описание, операция, ставка и к.8а — без изричната основа, основанието и връзката.
   */
  public static Move manualEquivalent(Move generated) {
    Move manual = new Move();
    manual.setId(generated.getId() + 10_000);
    manual.setReference(generated.getReference());
    manual.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    manual.setJournal(generated.getJournal());
    manual.setCompany(generated.getCompany());
    manual.setCurrency(generated.getCurrency());
    manual.setPartner(generated.getPartner());
    manual.setDate(generated.getDate());
    manual.setOrigin(generated.getOrigin());
    manual.setOriginDate(generated.getOriginDate());
    manual.setDescription(generated.getDescription());
    manual.setPeriod(generated.getPeriod());
    manual.setNapDocumentTypeSelect(generated.getNapDocumentTypeSelect());
    manual.setBgVatMoveVatOperation(generated.getBgVatMoveVatOperation());
    manual.setBgVatMoveVatRate(generated.getBgVatMoveVatRate());
    manual.setBgVatSupplyType(generated.getBgVatSupplyType());
    manual.setBgVatProtocolNumber(generated.getBgVatProtocolNumber());
    manual.setBgVatProtocolDate(generated.getBgVatProtocolDate());
    manual.setMoveLineList(new ArrayList<>());
    for (MoveLine line : generated.getMoveLineList()) {
      MoveLine copy = new MoveLine();
      copy.setAccount(line.getAccount());
      copy.setDebit(line.getDebit());
      copy.setCredit(line.getCredit());
      copy.setDate(line.getDate());
      copy.setCounter(line.getCounter());
      copy.setBgVatCreditType(line.getBgVatCreditType());
      copy.setOrigin(line.getOrigin());
      copy.setDescription(line.getDescription());
      copy.setMove(manual);
      manual.getMoveLineList().add(copy);
    }
    return manual;
  }

  // ---------------------------------------------------------------------------
  // Услугата в паметта
  // ---------------------------------------------------------------------------

  /** {@link BgVatInvoiceProtocolService} с подменено записване. */
  public static class InMemoryService extends BgVatInvoiceProtocolService {
    public final List<Move> manual = new ArrayList<>();
    public final Set<String> filedPeriods = new LinkedHashSet<>();
    public final Set<String> openCorrections = new LinkedHashSet<>();
    public final Set<LocalDate> closedDates = new LinkedHashSet<>();
    private final Map<Long, Account> accounts = new HashMap<>();
    private long nextId = 1000;
    private int nextNumber = 1;
    public boolean withSequence = true;

    public InMemoryService() {
      super(null, null, null, null, null, null, null, null);
      accounts.put(ACCOUNT_GOODS, BgVatInvoiceProtocolFixtures.account(ACCOUNT_GOODS, "304"));
      accounts.put(ACCOUNT_SERVICES, BgVatInvoiceProtocolFixtures.account(ACCOUNT_SERVICES, "602"));
    }

    @Override
    protected Move newMove(
        Journal journal, Invoice invoice, LocalDate date, String origin, String description) {
      Move move = new Move();
      move.setJournal(journal);
      move.setCompany(invoice.getCompany());
      move.setCurrency(invoice.getCompany().getCurrency());
      move.setCompanyCurrency(invoice.getCompany().getCurrency());
      move.setPartner(invoice.getPartner());
      move.setDate(date);
      move.setOriginDate(invoice.getOriginDate());
      move.setOrigin(origin);
      move.setDescription(description);
      move.setFiscalPosition(invoice.getFiscalPosition());
      move.setTechnicalOriginSelect(MoveRepository.TECHNICAL_ORIGIN_AUTOMATIC);
      move.setFunctionalOriginSelect(MoveRepository.FUNCTIONAL_ORIGIN_SALE);
      move.setStatusSelect(MoveRepository.STATUS_NEW);
      move.setMoveLineList(new ArrayList<>());
      return move;
    }

    @Override
    protected MoveLine newLine(
        Move move,
        Account account,
        BigDecimal amount,
        boolean debit,
        LocalDate date,
        int counter,
        String origin,
        String description) {
      MoveLine line = new MoveLine();
      line.setAccount(account);
      line.setDebit(debit ? amount : BigDecimal.ZERO);
      line.setCredit(debit ? BigDecimal.ZERO : amount);
      line.setDate(date);
      line.setCounter(counter);
      line.setOrigin(origin);
      line.setDescription(description);
      return line;
    }

    @Override
    protected Account findAccount(Long id) {
      return accounts.get(id);
    }

    /** Записът — и обратната страна на връзката, каквато я вижда презареденото копие. */
    @Override
    protected Move save(Move move) {
      if (move.getId() == null) {
        move.setId(nextId++);
        move.setReference("MOV-" + move.getId());
      }
      Invoice invoice = move.getBgVatSourceInvoice();
      if (invoice != null && !invoice.getBgVatProtocolMoveList().contains(move)) {
        invoice.getBgVatProtocolMoveList().add(move);
      }
      return move;
    }

    /** Като {@code BgMoveRepository.assignProtocolNumber} при осчетоводяване. */
    @Override
    protected void account(Move move) {
      move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
      if (withSequence && BgVatProtocolNumbering.isDue(move)) {
        move.setBgVatProtocolNumber(String.format("%010d", nextNumber++));
      }
    }

    @Override
    protected void link(Move protocol, Invoice invoice) {
      protocol.setBgVatSourceInvoice(invoice);
      invoice.getBgVatProtocolMoveList().add(protocol);
    }

    @Override
    protected List<Move> protocolsOf(Invoice invoice) {
      return new ArrayList<>(invoice.getBgVatProtocolMoveList());
    }

    @Override
    protected Move manualProtocolFor(Invoice invoice, BgVatInvoiceProtocolPlan.Group group) {
      for (Move move : manual) {
        if (move.getBgVatSourceInvoice() == null
            && move.getPartner() == invoice.getPartner()
            && invoice.getSupplierInvoiceNb().equals(move.getOrigin())
            && BgVatInvoiceProtocolPlan.sameProtocol(
                group,
                move.getBgVatMoveVatOperation(),
                move.getBgVatMoveVatRate(),
                move.getBgVatProtocolBasis())) {
          return move;
        }
      }
      return null;
    }

    @Override
    protected boolean isFiled(Company company, String yyyymm) {
      return filedPeriods.contains(yyyymm);
    }

    @Override
    protected boolean hasOpenCorrection(Company company, String yyyymm) {
      return openCorrections.contains(yyyymm);
    }

    @Override
    protected boolean isClosed(Company company, LocalDate date) {
      return closedDates.contains(date);
    }

    /** Курсовете (ЕЦБ) в паметта. */
    public final List<CurrencyConversionLine> rates = new ArrayList<>();

    /** {@code 1 start = rate end} от датата {@code from}. */
    public void rate(Currency start, Currency end, LocalDate from, String rate) {
      CurrencyConversionLine line = new CurrencyConversionLine();
      line.setStartCurrency(start);
      line.setEndCurrency(end);
      line.setFromDate(from);
      line.setExchangeRate(new BigDecimal(rate));
      rates.add(line);
    }

    /** Същото правило като заявката: последният ред с дата до деня. */
    @Override
    protected CurrencyConversionLine latest(Currency start, Currency end, LocalDate date) {
      CurrencyConversionLine best = null;
      for (CurrencyConversionLine line : rates) {
        if (line.getStartCurrency() == start
            && line.getEndCurrency() == end
            && !line.getFromDate().isAfter(date)
            && (best == null || line.getFromDate().isAfter(best.getFromDate()))) {
          best = line;
        }
      }
      return best;
    }
  }
}
