package com.axelor.apps.bglocalization.service.migration.microinvest.accounting;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The accounting export file, one financial year per file, exactly as The Microinvest export tool
 * writes it.
 *
 * <p>Same shape as the payroll {@code MicroinvestSourceModels} and for the same reasons: plain
 * public-field POJOs, no annotations, no naming strategy — the exporter emits property names that
 * match these field names character for character. Collections are initialised inline so an absent
 * JSON array yields an empty list rather than null.
 *
 * <p>The parser tolerates unknown properties so adding exporter sections cannot break older files.
 * Fixed assets are intentionally retained as {@link JsonNode} values: their nested source plans are
 * mapped to the standard FixedAsset models without introducing a parallel source entity graph.
 */
public final class MicroinvestAccountingSourceModels {

  private MicroinvestAccountingSourceModels() {}

  public static final class ExportRoot {
    public String schemaVersion;
    public Source source = new Source();
    public Company company = new Company();
    public FinancialYear financialYear = new FinancialYear();
    public List<Account> accounts = new ArrayList<>();
    public List<Partner> partners = new ArrayList<>();
    public List<VatTerm> vatTerms = new ArrayList<>();
    public List<DocumentType> documentTypes = new ArrayList<>();
    public List<Entry> entries = new ArrayList<>();
    public List<OpeningBalance> openingBalances = new ArrayList<>();
    public List<Settlement> settlements = new ArrayList<>();
    public List<JsonNode> fixedAssets = new ArrayList<>();
    public Counts counts = new Counts();
    public Checksums checksums = new Checksums();
    public String contentChecksum;
    public List<Warning> warnings = new ArrayList<>();
  }

  public static final class Source {
    public String product;
    public String productVersion;
    public String database;
    public String server;
    public LocalDateTime exportedAt;
    public String exporter;
  }

  public static final class Company {
    public String name;
    public String bulstat;
    public String vatId;
    public boolean isVatRegistered;
  }

  public static final class FinancialYear {
    public Integer sourceId;
    public Integer year;
    public LocalDate startDate;
    public boolean isConcluded;
    public boolean isLocked;

    /** Book currency of the exported financial year. Derived by the exporter, never hard-coded. */
    public String baseCurrency;
  }

  public static final class Account {
    public String number;
    public String name;
    public boolean isEuro;
  }

  public static final class Partner {
    public Integer sourceId;
    public String name;
    public String contactName;
    public String bulstat;
    public String vatNumber;
    public boolean isVatRegistered;
    public boolean isDeleted;
    public Integer dueDays;
    public String email;
    public List<Address> addresses = new ArrayList<>();
    public List<BankAccount> bankAccounts = new ArrayList<>();
  }

  public static final class Address {
    public Integer sourceId;
    public String street;
    public String street2;
    public String city;
    public String postalCode;
    public String phone;
    public boolean isPrimary;
    public String countryCode;
  }

  public static final class BankAccount {
    public Integer sourceId;
    public String iban;
    public String bic;
    public String bankName;
  }

  public static final class VatTerm {
    public Integer sourceId;
    public String direction;
    public String name;
    public Integer vatRate;
    public Integer typeIdentifier;
  }

  public static final class DocumentType {
    public Integer sourceId;
    public String napCode;
    public String shortName;
    public String name;
    public Integer typeIdentifier;
  }

  public static final class Entry {
    public Integer sourceId;
    public String number;

    /** Accountings.AccountingDate — the ACCOUNTING date, never Accountings.Date. */
    public LocalDate date;

    public LocalDate dueDate;

    /** SALE / PURCHASE / UNDEFINED. */
    public String direction;

    /** VAT_TERM / ACCOUNT_GROUP / HEADER / NONE — how {@link #direction} was decided. */
    public String directionSource;

    public String documentNumber;
    public LocalDate documentDate;
    public String napDocumentType;
    public String documentType;
    public String description;
    public String reference;

    /** The single document currency, or null when the entry is in the base currency. */
    public String currency;

    /** Set only when every foreign-currency line agrees; null when rates differ per line. */
    public BigDecimal exchangeRate;

    /** True when the source entry mixes two currencies — cannot become one Move. */
    public boolean mixedCurrency;

    public RawRelations rawRelations = new RawRelations();
    public List<Line> lines = new ArrayList<>();
  }

  public static final class Line {
    public Integer sourceId;
    public String accountNumber;
    public Integer partnerSourceId;
    public BigDecimal debit;
    public BigDecimal credit;

    /**
     * The exporter's account before the approved Nyxa mapping; set by {@code
     * MicroinvestAccountMapping}.
     */
    public String sourceAccountNumber;

    /** True when a negative source amount was turned into its absolute value on the other side. */
    public boolean negativeNormalized;

    public Integer vatTermId;
    public Integer vatRate;
    public CurrencyDetail currencyDetail;
  }

  public static final class CurrencyDetail {
    public String currency;
    public BigDecimal currencyAmount;
    public BigDecimal exchangeRate;
  }

  /** Source relationships kept verbatim for audit; not consumed by the importer. */
  public static final class RawRelations {
    public LocalDateTime enteredAt;
    public Integer branchId;
    public Integer viesId;
    public Integer icaType;
    public Integer icaAccountingId;
    public Integer importedPeriodId;
    public Integer sequenceId;
    public Integer termId;
    public Integer referenceId;
    public Integer optionalReferenceId;
    public Integer paragraphId;
    public Integer fileId;
    public String deliveryDescription;
    public boolean isInitialCorrespondence;
  }

  public static final class OpeningBalance {
    public Integer sourceId;

    /**
     * AccountingDetails.AccountingDetailID — the real grain. A balance record holds a debit slot
     * and a credit slot, and 34 records in this source carry a non-zero value in both halves on the
     * same account, so neither {@code sourceId} nor {@code (sourceId, accountNumber)} is unique.
     */
    public Integer sourceDetailId;

    /**
     * Accountings.AccountingID of the opening entry this row belongs to.
     *
     * <p>Settlements address entries by this id, and 12 of the 307 in FY2026 settle an opening
     * position against a movement of the year, so without it those cannot be resolved by exact id.
     */
    public Integer accountingId;

    public String accountNumber;
    public String sourceAccountNumber;
    public boolean negativeNormalized;
    public Integer partnerSourceId;
    public BigDecimal debit;
    public BigDecimal credit;
  }

  public static final class Settlement {
    public Integer sourceId;
    public Integer paymentEntryId;
    public Integer settledEntryId;
    public Integer documentId;
    public String accountNumber;
    public BigDecimal amount;
    public BigDecimal currencyAmount;
    public Integer paymentYear;
    public Integer settledYear;
  }

  public static final class Counts {
    public int accounts;
    public int partners;
    public int entries;
    public int lines;
    public int openingBalances;
    public int settlements;
    public int fixedAssets;
  }

  public static final class Checksums {
    public BigDecimal totalDebit;
    public BigDecimal totalCredit;
    public boolean balanced;
    public BigDecimal openingDebit;
    public BigDecimal openingCredit;
  }

  public static final class Warning {
    public String code;
    public Integer sourceId;
  }

  public static boolean isNonTransactionalCurrency(String currency, ExportRoot root) {
    return currency != null
        && root != null
        && root.financialYear != null
        && currency.equals(root.financialYear.baseCurrency);
  }
}
