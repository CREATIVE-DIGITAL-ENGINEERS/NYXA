package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.d1.BgD1D6FilingRules.Kind;
import org.junit.jupiter.api.Test;

/** Коя комбинация Д1/Д6 се допуска — чистото правило зад третото меню. */
class BgD1D6FilingRulesTest {

  private Kind decide(boolean noBasisD1, boolean hasD1, boolean noBasisD6, boolean hasD6) {
    return BgD1D6FilingRules.decide(noBasisD1, hasD1, noBasisD6, hasD6).getKind();
  }

  @Test
  void d1AndD6Together() {
    Kind kind = decide(false, true, false, true);
    assertEquals(Kind.FILE_D1_AND_D6, kind);
    assertTrue(kind.canProceed());
  }

  @Test
  void d1OnlyRequiresExplicitNoBasisD6() {
    // Д1 избрана, Д6 нито избрана, нито отбелязана „няма основание“ — иска изрично решение.
    assertEquals(Kind.NEED_D6_DECISION, decide(false, true, false, false));
    assertFalse(Kind.NEED_D6_DECISION.canProceed());
  }

  @Test
  void d1OnlyAllowedWhenNoBasisD6() {
    Kind kind = decide(false, true, true, false);
    assertEquals(Kind.FILE_D1_ONLY, kind);
    assertTrue(kind.canProceed());
  }

  @Test
  void d6AloneBlockedWhenNoBasisD1() {
    Kind kind = decide(true, false, false, true);
    assertEquals(Kind.BLOCKED_D6_ONLY, kind);
    assertFalse(kind.canProceed());
  }

  @Test
  void d6AloneBlockedWhenNoD1Selected() {
    // Само Д6 избрана, без Д1 и без отметка — пак „само Д6“, пак блокирано.
    assertEquals(Kind.BLOCKED_D6_ONLY, decide(false, false, false, true));
  }

  @Test
  void contradictionWhenNoBasisD1ButD1Selected() {
    assertEquals(Kind.CONTRADICTION_D1, decide(true, true, false, false));
  }

  @Test
  void contradictionWhenNoBasisD6ButD6Selected() {
    assertEquals(Kind.CONTRADICTION_D6, decide(false, false, true, true));
  }

  @Test
  void nothingToFileWhenBothNoBasis() {
    assertEquals(Kind.NOTHING_TO_FILE, decide(true, false, true, false));
  }

  @Test
  void nothingToFileWhenEmpty() {
    assertEquals(Kind.NOTHING_TO_FILE, decide(false, false, false, false));
  }

  @Test
  void everyDecisionCarriesAMessage() {
    for (boolean a : new boolean[] {false, true}) {
      for (boolean b : new boolean[] {false, true}) {
        for (boolean c : new boolean[] {false, true}) {
          for (boolean d : new boolean[] {false, true}) {
            assertFalse(BgD1D6FilingRules.decide(a, b, c, d).getMessage().isEmpty());
          }
        }
      }
    }
  }
}
