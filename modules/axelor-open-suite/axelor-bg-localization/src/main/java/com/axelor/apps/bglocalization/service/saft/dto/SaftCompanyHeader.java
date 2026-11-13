package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.ArrayList;
import java.util.List;

/** CompanyHeaderStructure (S.CMH.*) — дружеството, което подава файла. */
public final class SaftCompanyHeader {

  /** S.CMH.1 — ЕИК. */
  private String registrationNumber;

  /** S.CMH.2. */
  private String name;

  /** S.CMH.3 — поне един. */
  private final List<SaftAddress> addresses = new ArrayList<>();

  /** S.CMH.4 — поне един. */
  private final List<SaftContact> contacts = new ArrayList<>();

  /** S.CMH.5 — незадължителна. */
  private final List<SaftTaxRegistration> taxRegistrations = new ArrayList<>();

  /** S.CMH.6 — поне една. */
  private final List<SaftBankAccount> bankAccounts = new ArrayList<>();

  public String getRegistrationNumber() {
    return registrationNumber;
  }

  public SaftCompanyHeader registrationNumber(String value) {
    this.registrationNumber = value;
    return this;
  }

  public String getName() {
    return name;
  }

  public SaftCompanyHeader name(String value) {
    this.name = value;
    return this;
  }

  public List<SaftAddress> getAddresses() {
    return addresses;
  }

  public SaftCompanyHeader addAddress(SaftAddress value) {
    this.addresses.add(value);
    return this;
  }

  public List<SaftContact> getContacts() {
    return contacts;
  }

  public SaftCompanyHeader addContact(SaftContact value) {
    this.contacts.add(value);
    return this;
  }

  public List<SaftTaxRegistration> getTaxRegistrations() {
    return taxRegistrations;
  }

  public SaftCompanyHeader addTaxRegistration(SaftTaxRegistration value) {
    this.taxRegistrations.add(value);
    return this;
  }

  public List<SaftBankAccount> getBankAccounts() {
    return bankAccounts;
  }

  public SaftCompanyHeader addBankAccount(SaftBankAccount value) {
    this.bankAccounts.add(value);
    return this;
  }
}
