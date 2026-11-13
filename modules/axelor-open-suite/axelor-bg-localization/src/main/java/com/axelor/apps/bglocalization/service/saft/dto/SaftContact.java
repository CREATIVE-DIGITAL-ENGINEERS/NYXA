package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.ArrayList;
import java.util.List;

/** Контакт на дружеството (ContactHeaderStructure по книгата: S.CTH.*). */
public final class SaftContact {

  private SaftPersonName contactPerson;

  /** S.CTH.2 — по книгата задължителен за Header-а; XSD-то не го налага. */
  private final List<String> telephones = new ArrayList<>();

  /** S.CTH.4 — по книгата задължителен за Header-а; XSD-то не го налага. */
  private final List<String> emails = new ArrayList<>();

  public SaftPersonName getContactPerson() {
    return contactPerson;
  }

  public SaftContact contactPerson(SaftPersonName value) {
    this.contactPerson = value;
    return this;
  }

  public List<String> getTelephones() {
    return telephones;
  }

  public SaftContact addTelephone(String value) {
    this.telephones.add(value);
    return this;
  }

  public List<String> getEmails() {
    return emails;
  }

  public SaftContact addEmail(String value) {
    this.emails.add(value);
    return this;
  }
}
