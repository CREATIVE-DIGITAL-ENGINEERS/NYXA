package com.axelor.apps.bglocalization.service.saft.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * OwnershipStructure (S.O.*) — собствеността на дружеството.
 *
 * <p>Петте елемента за крайното предприятие майка са 1..n в схемата и затова са списъци; при {@code
 * IsPartOfGroup} 1, 2 и 5 в тях стои „00“ (бележката към S.O.7).
 */
public final class SaftOwnership {

  /** S.O.1: 1–5; 5 = „Не“. */
  private String isPartOfGroup;

  /** S.O.7. */
  private final List<String> ultimateOwnerNameCyrillicBg = new ArrayList<>();

  /** S.O.8. */
  private final List<String> ultimateOwnerUicBg = new ArrayList<>();

  /** S.O.9. */
  private final List<String> ultimateOwnerNameCyrillicForeign = new ArrayList<>();

  /** S.O.10. */
  private final List<String> ultimateOwnerNameLatinForeign = new ArrayList<>();

  /** S.O.11 — ISO код или „00“. */
  private final List<String> countryForeign = new ArrayList<>();

  public String getIsPartOfGroup() {
    return isPartOfGroup;
  }

  public SaftOwnership isPartOfGroup(String value) {
    this.isPartOfGroup = value;
    return this;
  }

  public List<String> getUltimateOwnerNameCyrillicBg() {
    return ultimateOwnerNameCyrillicBg;
  }

  public SaftOwnership addUltimateOwnerNameCyrillicBg(String value) {
    this.ultimateOwnerNameCyrillicBg.add(value);
    return this;
  }

  public List<String> getUltimateOwnerUicBg() {
    return ultimateOwnerUicBg;
  }

  public SaftOwnership addUltimateOwnerUicBg(String value) {
    this.ultimateOwnerUicBg.add(value);
    return this;
  }

  public List<String> getUltimateOwnerNameCyrillicForeign() {
    return ultimateOwnerNameCyrillicForeign;
  }

  public SaftOwnership addUltimateOwnerNameCyrillicForeign(String value) {
    this.ultimateOwnerNameCyrillicForeign.add(value);
    return this;
  }

  public List<String> getUltimateOwnerNameLatinForeign() {
    return ultimateOwnerNameLatinForeign;
  }

  public SaftOwnership addUltimateOwnerNameLatinForeign(String value) {
    this.ultimateOwnerNameLatinForeign.add(value);
    return this;
  }

  public List<String> getCountryForeign() {
    return countryForeign;
  }

  public SaftOwnership addCountryForeign(String value) {
    this.countryForeign.add(value);
    return this;
  }
}
