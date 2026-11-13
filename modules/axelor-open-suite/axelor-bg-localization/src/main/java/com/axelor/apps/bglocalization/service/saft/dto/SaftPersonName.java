package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * PersonNameStructure — лицето за контакт.
 *
 * <p>⚠️ {@code OtherTitles} е ЗАДЪЛЖИТЕЛНО и по книгата на НАП значи „Длъжност“ (S.PN.8).
 */
public final class SaftPersonName {

  /** Първо име (S.PN.2). */
  private String firstName;

  /** Фамилия (S.PN.5). */
  private String lastName;

  /** Длъжност (S.PN.8) — поне една. */
  private final List<String> otherTitles = new ArrayList<>();

  public String getFirstName() {
    return firstName;
  }

  public SaftPersonName firstName(String value) {
    this.firstName = value;
    return this;
  }

  public String getLastName() {
    return lastName;
  }

  public SaftPersonName lastName(String value) {
    this.lastName = value;
    return this;
  }

  public List<String> getOtherTitles() {
    return otherTitles;
  }

  public SaftPersonName addOtherTitle(String value) {
    this.otherTitles.add(value);
    return this;
  }
}
