package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** т. 1.1 на Прил. 9 и точките на чл. 68 — всички назовават чл. 68, тоест са срочни. */
class BgApp9Art68PointsTest {

  @Test
  @DisplayName(
      "Срочен по чл. 68: голото „68“, точките му и „121a_68“; не: 67, 111, 114, голото 121a")
  void everyArt68PointIsFixedTerm() {
    for (String type :
        new String[] {"68", "68_1_1", "68_1_2", "68_1_3", "68_1_4", "68_1_5", "68_6", "121a_68"}) {
      assertTrue(BgApp9ObstoyatelstvaResolver.isArt68(type), type);
    }
    for (String type : new String[] {"67", "111", "114", "121a", "duk", "gd", "", null}) {
      assertFalse(BgApp9ObstoyatelstvaResolver.isArt68(type), String.valueOf(type));
    }
  }
}
