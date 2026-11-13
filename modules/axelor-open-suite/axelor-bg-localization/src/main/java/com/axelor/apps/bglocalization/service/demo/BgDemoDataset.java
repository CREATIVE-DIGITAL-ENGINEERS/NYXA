package com.axelor.apps.bglocalization.service.demo;

import java.util.Locale;

/** Двата демо набора. Всеки е папка в {@code src/main/resources/demo-data/}. */
public enum BgDemoDataset {
  /** „Демо Търговия“ ООД — НСС, по ДДС, пълният набор (фактури, банка, ДМА, склад, ТРЗ). */
  TRADE("trade"),
  /** „Демо Холдинг“ АД — МСФО, по ДДС, начални салда и по няколко документа на месец. */
  HOLDING("holding");

  private final String folder;

  BgDemoDataset(String folder) {
    this.folder = folder;
  }

  public String folder() {
    return folder;
  }

  /** От стойността на избора в диалога ({@code trade}/{@code holding}); непознатото е грешка. */
  public static BgDemoDataset of(String value) {
    if (value != null) {
      for (BgDemoDataset d : values()) {
        if (d.folder.equals(value.trim().toLowerCase(Locale.ROOT))) {
          return d;
        }
      }
    }
    throw new IllegalArgumentException("Непознат демо набор: " + value);
  }
}
