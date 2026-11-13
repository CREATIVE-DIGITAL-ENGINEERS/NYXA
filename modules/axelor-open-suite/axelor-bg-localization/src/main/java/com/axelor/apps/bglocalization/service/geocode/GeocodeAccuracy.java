package com.axelor.apps.bglocalization.service.geocode;

/**
 * How much a resolved Address.latit/longit can be trusted, in decreasing order. Mirrors the {@code
 * bg.address.geocode.accuracy.select} selection stored in {@code Address.bgGeocodeAccuracySelect}.
 */
public enum GeocodeAccuracy {
  /** A validated Nominatim match that resolved down to a specific building/house number. */
  EXACT("exact"),
  /**
   * A validated Nominatim match that resolved to a street/neighborhood, not a specific building.
   */
  STREET("street"),
  /** The selected City's own trusted centroid -- always safe, never a wild/foreign guess. */
  CITY("city"),
  /** No safe coordinate could be established; Address.latit/longit must stay null. */
  NONE("none");

  private final String selectionValue;

  GeocodeAccuracy(String selectionValue) {
    this.selectionValue = selectionValue;
  }

  public String getSelectionValue() {
    return selectionValue;
  }
}
