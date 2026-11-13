package com.axelor.apps.bglocalization.service.fatca.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * СЪДЪРЖАНИЕТО НА ЕДИН FATCA ФАЙЛ — без ентитита, за да се проверява без база.
 *
 * <p>Идентификаторите ({@code messageRefId}, {@code docRefId}) се раждат в колектора, а не в
 * писача: писачът е функция на съдържанието и пише едни и същи байтове за едно и също съдържание.
 */
public final class BgFatcaFileContent {

  private int reportYear;
  private boolean testFile;
  private String giin;
  private String fiName;
  private String fiCountryCode;
  private String fiStreet;
  private String fiPostCode;
  private String fiCity;
  private String messageRefId;
  private String fiDocRefId;
  private String nilDocRefId;
  private LocalDateTime timestamp;
  private final List<Account> accounts = new ArrayList<>();

  public int getReportYear() {
    return reportYear;
  }

  public BgFatcaFileContent setReportYear(int reportYear) {
    this.reportYear = reportYear;
    return this;
  }

  /** DocTypeIndic FATCA11 вместо FATCA1 — само за тестовата услуга. */
  public boolean isTestFile() {
    return testFile;
  }

  public BgFatcaFileContent setTestFile(boolean testFile) {
    this.testFile = testFile;
    return this;
  }

  public String getGiin() {
    return giin;
  }

  public BgFatcaFileContent setGiin(String giin) {
    this.giin = giin;
    return this;
  }

  public String getFiName() {
    return fiName;
  }

  public BgFatcaFileContent setFiName(String fiName) {
    this.fiName = fiName;
    return this;
  }

  public String getFiCountryCode() {
    return fiCountryCode;
  }

  public BgFatcaFileContent setFiCountryCode(String fiCountryCode) {
    this.fiCountryCode = fiCountryCode;
    return this;
  }

  public String getFiStreet() {
    return fiStreet;
  }

  public BgFatcaFileContent setFiStreet(String fiStreet) {
    this.fiStreet = fiStreet;
    return this;
  }

  public String getFiPostCode() {
    return fiPostCode;
  }

  public BgFatcaFileContent setFiPostCode(String fiPostCode) {
    this.fiPostCode = fiPostCode;
    return this;
  }

  public String getFiCity() {
    return fiCity;
  }

  public BgFatcaFileContent setFiCity(String fiCity) {
    this.fiCity = fiCity;
    return this;
  }

  public String getMessageRefId() {
    return messageRefId;
  }

  public BgFatcaFileContent setMessageRefId(String messageRefId) {
    this.messageRefId = messageRefId;
    return this;
  }

  public String getFiDocRefId() {
    return fiDocRefId;
  }

  public BgFatcaFileContent setFiDocRefId(String fiDocRefId) {
    this.fiDocRefId = fiDocRefId;
    return this;
  }

  /** DocRefId на NilReport — само при нулев отчет. */
  public String getNilDocRefId() {
    return nilDocRefId;
  }

  public BgFatcaFileContent setNilDocRefId(String nilDocRefId) {
    this.nilDocRefId = nilDocRefId;
    return this;
  }

  public LocalDateTime getTimestamp() {
    return timestamp;
  }

  public BgFatcaFileContent setTimestamp(LocalDateTime timestamp) {
    this.timestamp = timestamp;
    return this;
  }

  public List<Account> getAccounts() {
    return accounts;
  }

  public BgFatcaFileContent addAccount(Account account) {
    accounts.add(account);
    return this;
  }

  /** Нулев отчет: без сметки се пише NilReport. */
  public boolean isNil() {
    return accounts.isEmpty();
  }

  /** Една отчетна сметка (AccountReport). */
  public static final class Account {

    private Long id;
    private String accountNumber;
    private boolean closed;
    private String holderType;
    private String firstName;
    private String lastName;
    private LocalDate birthDate;
    private String organisationName;
    private String acctHolderType;
    private String usTin;
    private String countryCode;
    private String city;
    private String addressFree;
    private BigDecimal balance;
    private String currencyCode;
    private BigDecimal dividends;
    private BigDecimal interest;
    private BigDecimal grossProceeds;
    private BigDecimal other;
    private String docRefId;

    /** id на реда в базата — за находките; във файла не влиза. */
    public Long getId() {
      return id;
    }

    public Account setId(Long id) {
      this.id = id;
      return this;
    }

    public String getAccountNumber() {
      return accountNumber;
    }

    public Account setAccountNumber(String accountNumber) {
      this.accountNumber = accountNumber;
      return this;
    }

    public boolean isClosed() {
      return closed;
    }

    public Account setClosed(boolean closed) {
      this.closed = closed;
      return this;
    }

    /** {@code INDIVIDUAL} / {@code ORGANISATION} ({@code bg.fatca.holder.type.select}). */
    public String getHolderType() {
      return holderType;
    }

    public Account setHolderType(String holderType) {
      this.holderType = holderType;
      return this;
    }

    public boolean isIndividual() {
      return "INDIVIDUAL".equals(holderType);
    }

    public String getFirstName() {
      return firstName;
    }

    public Account setFirstName(String firstName) {
      this.firstName = firstName;
      return this;
    }

    public String getLastName() {
      return lastName;
    }

    public Account setLastName(String lastName) {
      this.lastName = lastName;
      return this;
    }

    public LocalDate getBirthDate() {
      return birthDate;
    }

    public Account setBirthDate(LocalDate birthDate) {
      this.birthDate = birthDate;
      return this;
    }

    public String getOrganisationName() {
      return organisationName;
    }

    public Account setOrganisationName(String organisationName) {
      this.organisationName = organisationName;
      return this;
    }

    public String getAcctHolderType() {
      return acctHolderType;
    }

    public Account setAcctHolderType(String acctHolderType) {
      this.acctHolderType = acctHolderType;
      return this;
    }

    public String getUsTin() {
      return usTin;
    }

    public Account setUsTin(String usTin) {
      this.usTin = usTin;
      return this;
    }

    public String getCountryCode() {
      return countryCode;
    }

    public Account setCountryCode(String countryCode) {
      this.countryCode = countryCode;
      return this;
    }

    public String getCity() {
      return city;
    }

    public Account setCity(String city) {
      this.city = city;
      return this;
    }

    public String getAddressFree() {
      return addressFree;
    }

    public Account setAddressFree(String addressFree) {
      this.addressFree = addressFree;
      return this;
    }

    public BigDecimal getBalance() {
      return balance;
    }

    public Account setBalance(BigDecimal balance) {
      this.balance = balance;
      return this;
    }

    public String getCurrencyCode() {
      return currencyCode;
    }

    public Account setCurrencyCode(String currencyCode) {
      this.currencyCode = currencyCode;
      return this;
    }

    public BigDecimal getDividends() {
      return dividends;
    }

    public Account setDividends(BigDecimal dividends) {
      this.dividends = dividends;
      return this;
    }

    public BigDecimal getInterest() {
      return interest;
    }

    public Account setInterest(BigDecimal interest) {
      this.interest = interest;
      return this;
    }

    public BigDecimal getGrossProceeds() {
      return grossProceeds;
    }

    public Account setGrossProceeds(BigDecimal grossProceeds) {
      this.grossProceeds = grossProceeds;
      return this;
    }

    public BigDecimal getOther() {
      return other;
    }

    public Account setOther(BigDecimal other) {
      this.other = other;
      return this;
    }

    public String getDocRefId() {
      return docRefId;
    }

    public Account setDocRefId(String docRefId) {
      this.docRefId = docRefId;
      return this;
    }

    /** Името за находките: номерът на сметката, а ако го няма — id на реда. */
    public String label() {
      if (accountNumber != null && !accountNumber.trim().isEmpty()) {
        return "сметка " + accountNumber.trim();
      }
      return "сметка без номер (ред " + id + ")";
    }
  }
}
