package com.axelor.apps.bglocalization.service.art73.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * ЦЯЛАТА СПРАВКА ПО ЧЛ. 73 АЛ. 6, ГОТОВА ЗА ЗАПИСВАНЕ — Част І (платецът) плюс ЛИЦАТА на Част ІІ.
 *
 * <p><b>⚠️ РЕДОВЕТЕ СА ЛИЦА, НЕ ПЛАЩАНИЯ.</b> Огледално на {@code BgArt73Para1FileContent}, но
 * съдържанието на Част ІІ е с едно ниво по-плитко: {@link BgArt73Para6FilePersonRow} носи своите
 * редове доход вложени. Виж {@code BgArt73Para6FileCollector} за групирането.
 *
 * <p>⚠️ Част І е ТУК, а не на всеки ред — оформлението решава колко пъти ще я изпише.
 */
public final class BgArt73Para6FileContent {

  private int taxYear;
  private String payerIdentifier;
  private String payerName;
  private String contactPhone;
  private String contactEmail;
  private String representativeName;
  private String representativeIdentifier;

  private final List<BgArt73Para6FilePersonRow> persons = new ArrayList<>();

  /** Данъчната ГОДИНА, за която се подава — не годината на подаване. */
  public int getTaxYear() {
    return taxYear;
  }

  public void setTaxYear(int taxYear) {
    this.taxYear = taxYear;
  }

  /** Част І, т. 1 — ЕИК/Служебен № на РАБОТОДАТЕЛЯ, който подава справката. */
  public String getPayerIdentifier() {
    return payerIdentifier;
  }

  public void setPayerIdentifier(String payerIdentifier) {
    this.payerIdentifier = payerIdentifier;
  }

  /** Част І, т. 2 — наименование. */
  public String getPayerName() {
    return payerName;
  }

  public void setPayerName(String payerName) {
    this.payerName = payerName;
  }

  /** Част І, т. 3.1 — телефон за контакт. */
  public String getContactPhone() {
    return contactPhone;
  }

  public void setContactPhone(String contactPhone) {
    this.contactPhone = contactPhone;
  }

  /** Част І, т. 3.2 — e-mail за контакт. */
  public String getContactEmail() {
    return contactEmail;
  }

  public void setContactEmail(String contactEmail) {
    this.contactEmail = contactEmail;
  }

  /** Част І, т. 4.1 — три имена на представляващия. */
  public String getRepresentativeName() {
    return representativeName;
  }

  public void setRepresentativeName(String representativeName) {
    this.representativeName = representativeName;
  }

  /** Част І, т. 4.2 — ЕГН/ЛН/ЛНЧ/Сл. № на представляващия; схемата иска ТОЧНО 10 знака. */
  public String getRepresentativeIdentifier() {
    return representativeIdentifier;
  }

  public void setRepresentativeIdentifier(String representativeIdentifier) {
    this.representativeIdentifier = representativeIdentifier;
  }

  /** Лицата на Част ІІ, в реда на ЗАПИСАНИТЕ редове — виж {@code BgArt73Para6FileCollector}. */
  public List<BgArt73Para6FilePersonRow> getPersons() {
    return persons;
  }
}
