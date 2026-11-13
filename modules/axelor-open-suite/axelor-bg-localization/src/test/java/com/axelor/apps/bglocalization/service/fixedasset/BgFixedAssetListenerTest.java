package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.account.db.FixedAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Празният инвентарен номер се записва като NULL — уникалността не сравнява NULL-ите. */
class BgFixedAssetListenerTest {

  private static final String[] NAMES = {"name", BgFixedAssetGuard.SEQ, "statusSelect"};

  @Test
  @DisplayName("празен низ и само интервали → NULL в записваното състояние и в обекта")
  void blankBecomesNull() {
    for (String blank : new String[] {"", "   "}) {
      FixedAsset asset = new FixedAsset();
      asset.setFixedAssetSeq(blank);
      Object[] values = {"Машина", blank, 2};
      BgFixedAssetListener.blankSeqToNull(NAMES, values, asset);
      assertArrayEquals(new Object[] {"Машина", null, 2}, values);
      assertNull(asset.getFixedAssetSeq());
    }
  }

  @Test
  @DisplayName("непразен номер (и с интервали около него) и NULL — непокътнати")
  void nonBlankUntouched() {
    FixedAsset asset = new FixedAsset();
    asset.setFixedAssetSeq(" 001 ");
    Object[] values = {"Машина", " 001 ", 2};
    BgFixedAssetListener.blankSeqToNull(NAMES, values, asset);
    assertEquals(" 001 ", values[1]);
    assertEquals(" 001 ", asset.getFixedAssetSeq());

    Object[] none = {"Машина", null, 1};
    BgFixedAssetListener.blankSeqToNull(NAMES, none, new FixedAsset());
    assertNull(none[1]);
  }
}
