package com.axelor.apps.aiaccounting.service.invoice.spi;

import java.util.Collections;
import java.util.List;

/**
 * What one editable invoice field is, in the terms a JSON schema and a person both need.
 *
 * <p>Used for the fields a localisation or another module contributes through {@link
 * AiInvoiceFieldContributor}, so the edit tools can declare them in their schema without knowing
 * anything about what they mean.
 */
public final class AiInvoiceFieldSpec {

  /** The JSON type the schema declares for this field. */
  public enum Type {
    STRING("string"),
    INTEGER("integer"),
    NUMBER("number"),
    BOOLEAN("boolean");

    private final String json;

    Type(String json) {
      this.json = json;
    }

    public String json() {
      return json;
    }
  }

  /** Which invoice directions the field applies to. */
  public enum Applies {
    SUPPLIER_ONLY,
    CUSTOMER_ONLY,
    BOTH
  }

  private final String name;
  private final Type type;
  private final String description;
  private final List<String> allowedValues;
  private final boolean clearable;
  private final Applies applies;

  public AiInvoiceFieldSpec(
      String name,
      Type type,
      String description,
      List<String> allowedValues,
      boolean clearable,
      Applies applies) {
    this.name = name;
    this.type = type;
    this.description = description;
    this.allowedValues =
        allowedValues == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new java.util.ArrayList<>(allowedValues));
    this.clearable = clearable;
    this.applies = applies == null ? Applies.BOTH : applies;
  }

  public String getName() {
    return name;
  }

  public Type getType() {
    return type;
  }

  public String getDescription() {
    return description;
  }

  /**
   * The exact values a selection field accepts, or empty when it is free text.
   *
   * <p>Declared as an enum in the schema when present, so the model is told the codes rather than
   * left to guess them from a label — and rejected in Java when it sends one that is not here.
   */
  public List<String> getAllowedValues() {
    return allowedValues;
  }

  /** Whether an explicit null is allowed to empty this field. */
  public boolean isClearable() {
    return clearable;
  }

  public Applies getApplies() {
    return applies;
  }

  /** Whether this field may be edited on an invoice of the given direction. */
  public boolean appliesToSupplier() {
    return applies != Applies.CUSTOMER_ONLY;
  }

  /** Whether this field may be edited on an invoice of the given direction. */
  public boolean appliesToCustomer() {
    return applies != Applies.SUPPLIER_ONLY;
  }
}
