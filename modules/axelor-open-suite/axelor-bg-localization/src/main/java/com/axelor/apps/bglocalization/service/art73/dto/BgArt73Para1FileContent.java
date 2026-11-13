package com.axelor.apps.bglocalization.service.art73.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * ЦЯЛАТА СПРАВКА ПО ЧЛ. 73 АЛ. 1, ГОТОВА ЗА ЗАПИСВАНЕ — Част І (платецът) плюс редовете на Част ІІ.
 *
 * <p><b>⚠️ ЧАСТ І Е ТУК, А НЕ НА ВСЕКИ РЕД, И ТОВА Е ОБЯВЕНО НЕИЗВЕСТНО.</b> Дали заповедта иска
 * ЗАГЛАВЕН запис с данните на платеца, или ги повтаря на всеки ред (както ДДС-дневниците повтарят
 * ЕИК и период на всеки запис — спецификацията на НАП за файловете по ДДС §1), е въпрос към
 * формата. Затова съдържанието ги носи ВЕДНЪЖ: оформлението решава дали ще ги изпише веднъж или сто
 * пъти, а събирачът не бива да предрешава това, като ги размножи предварително.
 *
 * <p>⚠️ РЕДЪТ НА РЕДОВЕТЕ е този на ЗАПИСАНИТЕ редове — виж {@code BgArt73Para1FileCollector}.
 */
public final class BgArt73Para1FileContent {

  private int taxYear;
  private String payerIdentifier;
  private String payerName;
  private String contactPhone;
  private String contactEmail;
  private String representativeName;
  private String representativeIdentifier;

  private final List<BgArt73Para1FileRow> rows = new ArrayList<>();

  /** Данъчната ГОДИНА, за която се подава — не годината на подаване. */
  public int getTaxYear() {
    return taxYear;
  }

  public void setTaxYear(int taxYear) {
    this.taxYear = taxYear;
  }

  /** Част І, т. 1 — ЕИК/Служебен № от регистъра на НАП. */
  public String getPayerIdentifier() {
    return payerIdentifier;
  }

  public void setPayerIdentifier(String payerIdentifier) {
    this.payerIdentifier = payerIdentifier;
  }

  /** Част І, т. 2. */
  public String getPayerName() {
    return payerName;
  }

  public void setPayerName(String payerName) {
    this.payerName = payerName;
  }

  /** Част І, т. 3.1. */
  public String getContactPhone() {
    return contactPhone;
  }

  public void setContactPhone(String contactPhone) {
    this.contactPhone = contactPhone;
  }

  /** Част І, т. 3.2. */
  public String getContactEmail() {
    return contactEmail;
  }

  public void setContactEmail(String contactEmail) {
    this.contactEmail = contactEmail;
  }

  /** Част І, т. 4.1 — попълва се САМО когато платецът е юридическо лице. */
  public String getRepresentativeName() {
    return representativeName;
  }

  public void setRepresentativeName(String representativeName) {
    this.representativeName = representativeName;
  }

  /** Част І, т. 4.2. */
  public String getRepresentativeIdentifier() {
    return representativeIdentifier;
  }

  public void setRepresentativeIdentifier(String representativeIdentifier) {
    this.representativeIdentifier = representativeIdentifier;
  }

  /** Редовете на Част ІІ, в реда, в който ще бъдат записани. */
  public List<BgArt73Para1FileRow> getRows() {
    return rows;
  }
}
