package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.util.Collections;

/**
 * GeneralLedgerEntries.
 *
 * <p>⚠️ ЖУРНАЛИТЕ СА {@code Iterable}, НЕ {@code List}: месечният файл на средно дружество носи
 * стотици хиляди редове и извличането ще ги подава на парчета. Затова броят и сумите са ПОДАДЕНИ, а
 * не сметнати от writer-а — те стоят ПРЕДИ журналите във файла.
 */
public final class SaftGeneralLedger {

  private Long numberOfEntries;

  private BigDecimal totalDebit;

  private BigDecimal totalCredit;

  /** Поне един. */
  private Iterable<SaftJournal> journals = Collections.emptyList();

  public Long getNumberOfEntries() {
    return numberOfEntries;
  }

  public SaftGeneralLedger numberOfEntries(Long value) {
    this.numberOfEntries = value;
    return this;
  }

  public BigDecimal getTotalDebit() {
    return totalDebit;
  }

  public SaftGeneralLedger totalDebit(BigDecimal value) {
    this.totalDebit = value;
    return this;
  }

  public BigDecimal getTotalCredit() {
    return totalCredit;
  }

  public SaftGeneralLedger totalCredit(BigDecimal value) {
    this.totalCredit = value;
    return this;
  }

  public Iterable<SaftJournal> getJournals() {
    return journals;
  }

  public SaftGeneralLedger journals(Iterable<SaftJournal> value) {
    this.journals = value;
    return this;
  }
}
