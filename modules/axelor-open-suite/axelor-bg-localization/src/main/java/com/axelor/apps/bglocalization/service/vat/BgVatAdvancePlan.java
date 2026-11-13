package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.InvoiceLineTax;
import com.axelor.apps.account.db.InvoicePayment;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.InvoicePaymentRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * АВАНСОВАТА ФАКТУРА В СТРАНАТА — правилата, без база (продажби и покупки).
 *
 * <p>ЗДДС чл. 25 ал. 7: при авансово плащане „данъкът става изискуем при получаване на плащането
 * (за размера на плащането), с изключение на получено плащане във връзка с вътреобщностна
 * доставка“; чл. 113 ал. 4 — фактура до 5 дни от плащането; чл. 124 ал. 2 — издаденият документ в
 * дневника за продажби за периода на издаване. Днес AOS не съставя статия за авансовата фактура
 * ({@code VentilateAdvancePaymentState}: „we don't create the move“), тоест ДДС-ът ѝ не стига до
 * PRODAGBI и DEKLAR.
 *
 * <p>Схема А (решение от 28.10.2026): плащането остава Дт 503 / Кт 412 (AOS); авансовата фактура —
 * Дт 412 / Кт 4532 за ДДС; окончателната — с ред за приспадане (Дт 412 основата, ДДС-ът нетно), без
 * О.Д. на AOS за същия аванс; окончателна със сума 0 — Дт 412 / Кт 702.
 *
 * <p>Редът за приспадане се познава по {@code InvoiceLine.bgAdvanceDeduction} и {@code
 * bgAdvanceInvoice}. Текстът му — „Приспадане на аванс по ф-ра № … от …“ — е само за печат: минава
 * през превода и операторът може да го редактира.
 *
 * <p>Покупките — огледално: авансовата от доставчик — Дт 4531 / Кт 402; окончателната — с ред за
 * приспадане (Кт 402 основата); сума 0 — Дт 60x / Кт 402. Правото на данъчен кредит възниква с
 * плащането (ЗДДС чл. 68 ал. 1 т. 2 и ал. 2 във вр. с чл. 25 ал. 7) — неплатената авансова е само
 * предупреждение ({@link #unpaidAt}). Ред на авансова без право на кредит (тип „3“) — без статия:
 * ДДС-ът ѝ не е за 4531 (граница, справката я показва).
 *
 * <p>В обхвата са доставките в страната с ДДС. Аванс без ДДС (ВОД, тристранна, услуга към ЕС)
 * остава както е.
 */
public final class BgVatAdvancePlan {

  /** Текстът на реда за приспадане — ключ за превода; № и дата на авансовата. */
  public static final String DEDUCTION_LABEL = "Приспадане на аванс по ф-ра № %s от %s";

  private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  private BgVatAdvancePlan() {}

  /**
   * Една ставка на авансовата: данъкът, основата и ДДС — във валутата на фактурата и на
   * дружеството.
   */
  public static final class Rate {
    private final TaxLine taxLine;
    private final BigDecimal base;
    private final BigDecimal vat;
    private final BigDecimal companyBase;
    private final BigDecimal companyVat;

    Rate(
        TaxLine taxLine,
        BigDecimal base,
        BigDecimal vat,
        BigDecimal companyBase,
        BigDecimal companyVat) {
      this.taxLine = taxLine;
      this.base = base;
      this.vat = vat;
      this.companyBase = companyBase;
      this.companyVat = companyVat;
    }

    public TaxLine getTaxLine() {
      return taxLine;
    }

    public BigDecimal getBase() {
      return base;
    }

    public BigDecimal getVat() {
      return vat;
    }

    public BigDecimal getCompanyBase() {
      return companyBase;
    }

    public BigDecimal getCompanyVat() {
      return companyVat;
    }
  }

  /** Ред за приспадане на окончателната фактура — сумите със знак минус. */
  public static final class Deduction {
    private final Invoice advance;
    private final String creditType;
    private final TaxLine taxLine;
    private final BigDecimal base;
    private final BigDecimal vat;
    private final BigDecimal companyBase;
    private final BigDecimal companyVat;

    Deduction(Invoice advance, Rate rate, String creditType) {
      this.advance = advance;
      this.creditType = creditType;
      this.taxLine = rate.taxLine;
      this.base = rate.base.negate();
      this.vat = rate.vat.negate();
      this.companyBase = rate.companyBase.negate();
      this.companyVat = rate.companyVat.negate();
    }

    public Invoice getAdvance() {
      return advance;
    }

    /** Кредитният тип на аванса (покупка) — колекторът рутира реда по него; продажба — празно. */
    public String getCreditType() {
      return creditType;
    }

    public TaxLine getTaxLine() {
      return taxLine;
    }

    public BigDecimal getBase() {
      return base;
    }

    public BigDecimal getVat() {
      return vat;
    }

    public BigDecimal getCompanyBase() {
      return companyBase;
    }

    public BigDecimal getCompanyVat() {
      return companyVat;
    }
  }

  /** Ред на статия: сметка, сума (положителна) и страна. */
  public static final class Posting {
    private final Account account;
    private final BigDecimal amount;
    private final boolean debit;

    Posting(Account account, BigDecimal amount, boolean debit) {
      this.account = account;
      this.amount = amount;
      this.debit = debit;
    }

    public Account getAccount() {
      return account;
    }

    public BigDecimal getAmount() {
      return amount;
    }

    public boolean isDebit() {
      return debit;
    }
  }

  /** Покупка или КИ от доставчик. */
  public static boolean isPurchase(Invoice invoice) {
    int op = invoice.getOperationTypeSelect();
    return op == InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE
        || op == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND;
  }

  /** Авансова фактура от доставчик или КИ към такава (подвид „аванс“). */
  public static boolean isPurchaseAdvance(Invoice invoice) {
    return invoice != null
        && invoice.getOperationSubTypeSelect() == InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE
        && isPurchase(invoice);
  }

  /** Авансова фактура — на клиент или от доставчик. */
  public static boolean isAdvance(Invoice invoice) {
    return isSalesAdvance(invoice) || isPurchaseAdvance(invoice);
  }

  /** Окончателна фактура — продажба или покупка, не авансова и не КИ. */
  public static boolean isFinal(Invoice invoice) {
    if (invoice == null
        || invoice.getOperationSubTypeSelect() == InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE) {
      return false;
    }
    int op = invoice.getOperationTypeSelect();
    return op == InvoiceRepository.OPERATION_TYPE_CLIENT_SALE
        || op == InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE;
  }

  /** Авансова фактура на клиент или КИ към такава (подвид „аванс“). */
  public static boolean isSalesAdvance(Invoice invoice) {
    if (invoice == null
        || invoice.getOperationSubTypeSelect() != InvoiceRepository.OPERATION_SUB_TYPE_ADVANCE) {
      return false;
    }
    int op = invoice.getOperationTypeSelect();
    return op == InvoiceRepository.OPERATION_TYPE_CLIENT_SALE
        || op == InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND;
  }

  /** КИ към авансова фактура — статията ѝ е обратната. */
  public static boolean isRefund(Invoice invoice) {
    int op = invoice.getOperationTypeSelect();
    return op == InvoiceRepository.OPERATION_TYPE_CLIENT_REFUND
        || op == InvoiceRepository.OPERATION_TYPE_SUPPLIER_REFUND;
  }

  /**
   * Получава ли авансовата ДДС статия: продажба или покупка (или КИ към тях) с ДДС. Аванс по ВОД
   * (чл. 25 ал. 7 — изключението) и обратното начисляване са без ДДС, тоест без статия. Авансова от
   * доставчик с ред без право на кредит (тип „3“) — също без статия (граница).
   */
  public static boolean needsVatMove(Invoice invoice) {
    if (!isAdvance(invoice) || totalVat(invoice).signum() == 0) {
      return false;
    }
    return !isPurchase(invoice)
        || lines(invoice).stream().noneMatch(l -> CREDIT_NONE.equals(trim(l.getBgVatCreditType())));
  }

  /** Кредитният тип „без право на данъчен кредит“ ({@code bg.vat.credit.type.select}). */
  static final String CREDIT_NONE = "3";

  /** Ставките на авансовата — от {@code InvoiceLineTax}, без обратното начисляване. */
  public static List<Rate> rates(Invoice invoice) {
    List<Rate> rates = new ArrayList<>();
    if (invoice.getInvoiceLineTaxList() == null) {
      return rates;
    }
    for (InvoiceLineTax tax : invoice.getInvoiceLineTaxList()) {
      if (tax.getReverseCharged()) {
        continue;
      }
      BigDecimal base = nz(tax.getExTaxBase());
      BigDecimal vat = nz(tax.getTaxTotal());
      BigDecimal companyBase = firstNonNull(tax.getCompanyExTaxBase(), base);
      BigDecimal companyVat = firstNonNull(tax.getCompanyTaxTotal(), vat);
      if (base.signum() == 0 && vat.signum() == 0) {
        continue;
      }
      rates.add(new Rate(tax.getTaxLine(), base, vat, companyBase, companyVat));
    }
    rates.sort(Comparator.comparing(r -> r.taxLine == null ? 0L : nzId(r.taxLine.getId())));
    return rates;
  }

  /** ДДС на авансовата във валутата на дружеството. */
  public static BigDecimal totalVat(Invoice invoice) {
    return rates(invoice).stream()
        .map(Rate::getCompanyVat)
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /**
   * Отказ при осчетоводяване: авансова с ДДС, а дружеството не съставя статия при плащане на
   * фактура ({@code AccountConfig.generateMoveForInvoicePayment = false}). Тогава плащането няма Кт
   * 412 — ДДС статията на аванса и приспадането в окончателната няма срещу какво да се лентират, а
   * AOS приспада аванса по свой път ({@code copyAdvancePaymentToInvoice}).
   */
  public static boolean refusedWithoutPaymentMoves(
      Invoice invoice, boolean generateMoveForInvoicePayment) {
    return !generateMoveForInvoicePayment && needsVatMove(invoice);
  }

  /**
   * Страната на сметката за аванси в ДДС статията. Продажба: Дт 412 / Кт 4532. Покупка: Дт 4531 /
   * Кт 402. КИ — обратно.
   */
  public static boolean advanceAccountIsDebit(Invoice invoice) {
    return isPurchase(invoice) == isRefund(invoice);
  }

  /**
   * Неплатена до края на периода авансова от доставчик: потвърдените плащания с дата до {@code end}
   * не покриват сумата ѝ. Правото на кредит възниква с плащането (ЗДДС чл. 68 ал. 1 т. 2 и ал. 2).
   */
  public static boolean unpaidAt(Invoice advance, LocalDate end) {
    BigDecimal paid = BigDecimal.ZERO;
    if (advance.getInvoicePaymentList() != null) {
      for (InvoicePayment p : advance.getInvoicePaymentList()) {
        if (p.getStatusSelect() == InvoicePaymentRepository.STATUS_VALIDATED
            && p.getPaymentDate() != null
            && !p.getPaymentDate().isAfter(end)) {
          paid = paid.add(nz(p.getAmount()));
        }
      }
    }
    return paid.compareTo(nz(advance.getInTaxTotal())) < 0;
  }

  /**
   * Текстът на реда по шаблон ({@link #DEDUCTION_LABEL} или преведеният): „Приспадане на аванс по
   * ф-ра № 0000000123 от 05.06.2026“.
   */
  public static String deductionLabel(Invoice advance, String template) {
    String date = advance.getInvoiceDate() == null ? "" : DATE.format(advance.getInvoiceDate());
    return String.format(template, nvl(advance.getInvoiceId()), date);
  }

  /** Редовете за приспадане на една авансова — по един на ставка. */
  public static List<Deduction> deductions(Invoice advance) {
    List<Deduction> out = new ArrayList<>();
    for (Rate rate : rates(advance)) {
      out.add(new Deduction(advance, rate, isPurchase(advance) ? creditType(advance, rate) : null));
    }
    return out;
  }

  /** Кредитният тип на редовете на авансовата с този данък — когато е един; иначе празно. */
  private static String creditType(Invoice advance, Rate rate) {
    Set<String> types = new LinkedHashSet<>();
    for (InvoiceLine line : lines(advance)) {
      if (taxLines(line).contains(rate.taxLine)) {
        types.add(trim(line.getBgVatCreditType()));
      }
    }
    return types.size() == 1 ? types.iterator().next() : null;
  }

  /** Ред за приспадане — по полето, не по текста. */
  public static boolean isDeduction(InvoiceLine line) {
    return Boolean.TRUE.equals(line.getBgAdvanceDeduction());
  }

  /** Ред за приспадане на точно тази авансова. */
  static boolean deducts(InvoiceLine line, Invoice advance) {
    return isDeduction(line) && same(line.getBgAdvanceInvoice(), advance);
  }

  /** Има ли окончателната поне един ред за приспадане. */
  public static boolean hasDeductions(Invoice invoice) {
    return lines(invoice).stream().anyMatch(BgVatAdvancePlan::isDeduction);
  }

  /** Авансовите, които окончателната приспада с ред — от {@code bgAdvanceInvoice} на редовете. */
  public static List<Invoice> deductedAdvances(Invoice invoice) {
    List<Invoice> out = new ArrayList<>();
    for (InvoiceLine line : lines(invoice)) {
      Invoice advance = line.getBgAdvanceInvoice();
      if (isDeduction(line) && advance != null && out.stream().noneMatch(a -> same(a, advance))) {
        out.add(advance);
      }
    }
    out.sort(Comparator.comparing(a -> nvl(a.getInvoiceId())));
    return out;
  }

  /**
   * Какво не е приспаднато: за всяка авансова с ДДС статия и всяка нейна ставка — сборът на
   * редовете ѝ за приспадане трябва да е точно минус основата. Празно, когато всичко е наред.
   */
  public static List<String> missingDeductions(Invoice invoice, Collection<Invoice> advances) {
    List<String> missing = new ArrayList<>();
    for (Invoice advance : advances) {
      Map<String, BigDecimal> present = new LinkedHashMap<>();
      for (InvoiceLine line : lines(invoice)) {
        if (!deducts(line, advance)) {
          continue;
        }
        for (TaxLine taxLine : taxLines(line)) {
          present.merge(key(taxLine), nz(line.getExTaxTotal()), BigDecimal::add);
        }
      }
      for (Rate rate : rates(advance)) {
        BigDecimal expected = rate.base.negate();
        BigDecimal actual = present.getOrDefault(key(rate.taxLine), BigDecimal.ZERO);
        if (expected.compareTo(actual) != 0) {
          missing.add(
              "ф-ра № "
                  + nvl(advance.getInvoiceId())
                  + " ("
                  + percent(rate.taxLine)
                  + "): очаквано "
                  + money(expected)
                  + ", на фактурата "
                  + money(actual));
        }
      }
    }
    return missing;
  }

  /**
   * Окончателна със сума 0: основата по сметките на редовете. Продажба — Кт приходната, Дт 412 за
   * реда за приспадане; покупка — Дт разходната, Кт 402. ДДС-ът е начислен с авансовата.
   */
  public static List<Posting> zeroFinalPostings(Invoice invoice) {
    boolean purchase = isPurchase(invoice);
    Map<Account, BigDecimal> byAccount = new LinkedHashMap<>();
    for (InvoiceLine line : lines(invoice)) {
      BigDecimal amount = firstNonNull(line.getCompanyExTaxTotal(), line.getExTaxTotal());
      if (line.getAccount() == null || nz(amount).signum() == 0) {
        continue;
      }
      byAccount.merge(line.getAccount(), amount, BigDecimal::add);
    }
    List<Posting> out = new ArrayList<>();
    byAccount.forEach(
        (account, amount) -> {
          if (amount.signum() != 0) {
            out.add(new Posting(account, amount.abs(), (amount.signum() < 0) != purchase));
          }
        });
    return out;
  }

  private static List<InvoiceLine> lines(Invoice invoice) {
    return invoice.getInvoiceLineList() == null
        ? Collections.emptyList()
        : invoice.getInvoiceLineList();
  }

  private static Collection<TaxLine> taxLines(InvoiceLine line) {
    return line.getTaxLineSet() == null ? Collections.emptySet() : line.getTaxLineSet();
  }

  /** Същата фактура: по id, когато и двете имат (от базата), иначе по самоличност. */
  private static boolean same(Invoice a, Invoice b) {
    if (a == null || b == null) {
      return false;
    }
    if (a.getId() != null && b.getId() != null) {
      return a.getId().equals(b.getId());
    }
    return a == b;
  }

  /** Данъкът по id, когато има такова (обектите от базата), иначе по самоличност. */
  private static String key(TaxLine taxLine) {
    if (taxLine == null) {
      return "-";
    }
    return taxLine.getId() != null
        ? "id:" + taxLine.getId()
        : "obj:" + System.identityHashCode(taxLine);
  }

  private static String percent(TaxLine taxLine) {
    if (taxLine == null || taxLine.getValue() == null) {
      return "без данък";
    }
    return taxLine.getValue().stripTrailingZeros().toPlainString() + " %";
  }

  private static String money(BigDecimal value) {
    return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }

  private static long nzId(Long id) {
    return id == null ? 0L : id;
  }

  private static String trim(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private static String nvl(String value) {
    return value == null ? "" : value;
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal firstNonNull(BigDecimal first, BigDecimal second) {
    return Objects.requireNonNullElse(first, nz(second));
  }
}
