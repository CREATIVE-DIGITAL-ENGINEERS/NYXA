package com.axelor.apps.bglocalization.service.noi;

import java.util.Arrays;

/** Емисии за свидетели извън пакета — фабриките на {@link BgApp9Emission} са пакетни. */
public final class BgApp9EmissionFixture {

  private BgApp9EmissionFixture() {}

  /** Отказ с една липса в заглавната част. */
  public static BgApp9Emission refusedByHeader(String reason) {
    return BgApp9Emission.refused(
        Arrays.asList(BgApp9Gap.header("адрес на осигурителя", "BulAddressFull", reason)));
  }

  /** Готов файл с един документ, а един лист е оставен вън заради т. 9. */
  public static BgApp9Emission emittedWithOneExcluded(String excludedDocument) {
    return BgApp9Emission.emitted("<BPril9/>", 1, "Pril9_08102026_1.xml")
        .withExcluded(
            Arrays.asList(
                BgApp9Gap.of(
                    excludedDocument,
                    "т. 9 · работни дни по месеци",
                    "BSickLeave",
                    "няма редове по т. 9")));
  }
}
