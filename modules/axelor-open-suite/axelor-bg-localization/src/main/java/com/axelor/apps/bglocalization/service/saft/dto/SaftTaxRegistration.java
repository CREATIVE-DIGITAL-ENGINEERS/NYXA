package com.axelor.apps.bglocalization.service.saft.dto;

import java.time.LocalDate;

/**
 * TaxIDStructure (S.TID.*).
 *
 * <p>⚠️ ПО КНИГАТА, НЕ ПО ОБРАЗЕЦА: S.TID.1 {@code TaxRegistrationNumber} е номерът по ДДС, S.TID.3
 * {@code TaxNumber} е данъчният номер (ЕИК), а S.TID.2 {@code TaxType} е от номенклатурата
 * VAT_TaxType (100010/100020/100030) — НЕ транзакционният TaxType от TAX-IMP.
 */
public final class SaftTaxRegistration {

  /** S.TID.1 — номерът по ДДС. */
  private String taxRegistrationNumber;

  /** S.TID.2 — VAT_TaxType. */
  private String taxType;

  /** S.TID.3 — ЕИК. */
  private String taxNumber;

  /** S.TID.4 — „NRA“. */
  private String taxAuthority;

  /** S.TID.5. */
  private LocalDate taxVerificationDate;

  public String getTaxRegistrationNumber() {
    return taxRegistrationNumber;
  }

  public SaftTaxRegistration taxRegistrationNumber(String value) {
    this.taxRegistrationNumber = value;
    return this;
  }

  public String getTaxType() {
    return taxType;
  }

  public SaftTaxRegistration taxType(String value) {
    this.taxType = value;
    return this;
  }

  public String getTaxNumber() {
    return taxNumber;
  }

  public SaftTaxRegistration taxNumber(String value) {
    this.taxNumber = value;
    return this;
  }

  public String getTaxAuthority() {
    return taxAuthority;
  }

  public SaftTaxRegistration taxAuthority(String value) {
    this.taxAuthority = value;
    return this;
  }

  public LocalDate getTaxVerificationDate() {
    return taxVerificationDate;
  }

  public SaftTaxRegistration taxVerificationDate(LocalDate value) {
    this.taxVerificationDate = value;
    return this;
  }
}
