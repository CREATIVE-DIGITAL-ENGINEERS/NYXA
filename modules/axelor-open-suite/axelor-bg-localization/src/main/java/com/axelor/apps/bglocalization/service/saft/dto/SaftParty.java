package com.axelor.apps.bglocalization.service.saft.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Клиент или доставчик в MasterFiles — CompanyStructure плюс идентификатор, сметка и салда.
 *
 * <p>Салдата са СО ЗНАК (дебит положителен); writer-ът избира елемента Debit/Credit според знака.
 */
public final class SaftParty {

  private String registrationNumber;

  private String name;

  /** Поне един. */
  private final List<SaftAddress> addresses = new ArrayList<>();

  private final List<SaftTaxRegistration> taxRegistrations = new ArrayList<>();

  /** Y / N. */
  private String relatedParty;

  /** CustomerID или SupplierID. */
  private String partyId;

  /** Сметката по сметкоплана на НАП. */
  private String accountId;

  /** Начално салдо със знак. */
  private BigDecimal openingBalance;

  /** Крайно салдо със знак. */
  private BigDecimal closingBalance;

  public String getRegistrationNumber() {
    return registrationNumber;
  }

  public SaftParty registrationNumber(String value) {
    this.registrationNumber = value;
    return this;
  }

  public String getName() {
    return name;
  }

  public SaftParty name(String value) {
    this.name = value;
    return this;
  }

  public List<SaftAddress> getAddresses() {
    return addresses;
  }

  public SaftParty addAddress(SaftAddress value) {
    this.addresses.add(value);
    return this;
  }

  public List<SaftTaxRegistration> getTaxRegistrations() {
    return taxRegistrations;
  }

  public SaftParty addTaxRegistration(SaftTaxRegistration value) {
    this.taxRegistrations.add(value);
    return this;
  }

  public String getRelatedParty() {
    return relatedParty;
  }

  public SaftParty relatedParty(String value) {
    this.relatedParty = value;
    return this;
  }

  public String getPartyId() {
    return partyId;
  }

  public SaftParty partyId(String value) {
    this.partyId = value;
    return this;
  }

  public String getAccountId() {
    return accountId;
  }

  public SaftParty accountId(String value) {
    this.accountId = value;
    return this;
  }

  public BigDecimal getOpeningBalance() {
    return openingBalance;
  }

  public SaftParty openingBalance(BigDecimal value) {
    this.openingBalance = value;
    return this;
  }

  public BigDecimal getClosingBalance() {
    return closingBalance;
  }

  public SaftParty closingBalance(BigDecimal value) {
    this.closingBalance = value;
    return this;
  }
}
