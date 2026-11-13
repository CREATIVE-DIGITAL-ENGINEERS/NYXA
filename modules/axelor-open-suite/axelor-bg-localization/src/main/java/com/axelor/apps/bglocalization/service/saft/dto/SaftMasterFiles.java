package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.ArrayList;
import java.util.List;

/** MasterFilesMonthly — таблиците на файла. Всяка, освен незадължителните, иска поне един ред. */
public final class SaftMasterFiles {

  private final List<SaftGlAccount> glAccounts = new ArrayList<>();

  private final List<SaftParty> customers = new ArrayList<>();

  private final List<SaftParty> suppliers = new ArrayList<>();

  private final List<SaftTaxTableEntry> taxTableEntries = new ArrayList<>();

  private final List<SaftUom> uoms = new ArrayList<>();

  private final List<SaftProduct> products = new ArrayList<>();

  public List<SaftGlAccount> getGlAccounts() {
    return glAccounts;
  }

  public SaftMasterFiles addGlAccount(SaftGlAccount value) {
    this.glAccounts.add(value);
    return this;
  }

  public List<SaftParty> getCustomers() {
    return customers;
  }

  public SaftMasterFiles addCustomer(SaftParty value) {
    this.customers.add(value);
    return this;
  }

  public List<SaftParty> getSuppliers() {
    return suppliers;
  }

  public SaftMasterFiles addSupplier(SaftParty value) {
    this.suppliers.add(value);
    return this;
  }

  public List<SaftTaxTableEntry> getTaxTableEntries() {
    return taxTableEntries;
  }

  public SaftMasterFiles addTaxTableEntry(SaftTaxTableEntry value) {
    this.taxTableEntries.add(value);
    return this;
  }

  public List<SaftUom> getUoms() {
    return uoms;
  }

  public SaftMasterFiles addUom(SaftUom value) {
    this.uoms.add(value);
    return this;
  }

  public List<SaftProduct> getProducts() {
    return products;
  }

  public SaftMasterFiles addProduct(SaftProduct value) {
    this.products.add(value);
    return this;
  }
}
