package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.axelor.apps.account.db.FixedAsset;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Split (SAF-T A1): всеки отделен актив сочи оригинала; оригиналът — никога себе си. */
class BgFixedAssetSplitLinkTest {

  @Test
  @DisplayName(
      "Отделените сочат оригинала; оригиналът в списъка не сочи себе си; null — без грешка")
  void връзката() {
    FixedAsset original = new FixedAsset();
    FixedAsset first = new FixedAsset();
    FixedAsset second = new FixedAsset();
    BgFixedAssetServiceImpl.linkSplit(Arrays.asList(first, null, second, original), original);
    assertSame(original, first.getBgSplitFrom());
    assertSame(original, second.getBgSplitFrom());
    assertNull(original.getBgSplitFrom());
    BgFixedAssetServiceImpl.linkSplit(null, original);
    BgFixedAssetServiceImpl.linkSplit(Arrays.asList(first), null);
    assertSame(original, first.getBgSplitFrom());
  }
}
