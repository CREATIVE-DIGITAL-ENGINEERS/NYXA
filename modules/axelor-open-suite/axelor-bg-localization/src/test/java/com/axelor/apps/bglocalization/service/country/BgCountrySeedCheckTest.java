package com.axelor.apps.bglocalization.service.country;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.country.BgCountrySeedCheck.CountryRow;
import com.axelor.apps.bglocalization.service.country.BgCountrySeedCheck.Snapshot;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Правилото на проверката при старт — без база. Сценариите са състояния на заварени бази. */
class BgCountrySeedCheckTest {

  private static CountryRow bg(long id, String importId, String rnt) {
    return new CountryRow(id, importId, "BG", "BGR", rnt);
  }

  private static CountryRow la(long id, String importId, String rnt) {
    return new CountryRow(id, importId, "LA", "LAO", rnt);
  }

  @Test
  @DisplayName("чисто: BG веднъж с 30 и RNT-BG, bg_BG към нея, LA без шаблон → нищо")
  void cleanStateHasNoViolations() {
    Snapshot s = new Snapshot(List.of(bg(35, "30", "RNT-BG"), la(5, "99", null)), true, 35L);
    assertEquals(List.of(), BgCountrySeedCheck.violations(s));
    assertNull(BgCountrySeedCheck.summary(BgCountrySeedCheck.violations(s)));
  }

  @Test
  @DisplayName("без Лаос (кореновият списък от 5 държави) също е чисто")
  void noLaosIsFine() {
    Snapshot s = new Snapshot(List.of(bg(5, "30", "RNT-BG")), true, 5L);
    assertEquals(List.of(), BgCountrySeedCheck.violations(s));
  }

  @Test
  @DisplayName("заварена база: RNT-BG на LA, BG без шаблон → и двете нарушения")
  void legacyStateIsReported() {
    Snapshot s = new Snapshot(List.of(la(5, "99", "RNT-BG"), bg(35, "30", null)), true, 35L);
    List<String> v = BgCountrySeedCheck.violations(s);
    assertEquals(2, v.size(), v.toString());
    assertTrue(v.get(0).contains("без шаблона RNT-BG"), v.get(0));
    assertTrue(v.get(1).contains("закачен за държава LA (id 5)"), v.get(1));
    String summary = BgCountrySeedCheck.summary(v);
    assertTrue(summary.contains(BgCountrySeedCheck.CHECK_FILE), summary);
  }

  @Test
  @DisplayName("заварена база: BG още с importId 99 → предупреждава преди вноса да я презапише")
  void legacyImportIdIsReported() {
    Snapshot s = new Snapshot(List.of(bg(5, "99", "RNT-BG")), true, 5L);
    List<String> v = BgCountrySeedCheck.violations(s);
    assertEquals(1, v.size(), v.toString());
    assertTrue(v.get(0).contains("importId 99"), v.get(0));
  }

  @Test
  @DisplayName("две Българии, липсваща и сбъркана bg_BG, LA с чужд alpha3")
  void otherBreakages() {
    assertTrue(
        BgCountrySeedCheck.violations(
                new Snapshot(List.of(bg(1, "30", "RNT-BG"), bg(2, null, null)), true, 1L))
            .get(0)
            .contains("2 пъти"));
    assertTrue(
        BgCountrySeedCheck.violations(new Snapshot(List.of(bg(1, "30", "RNT-BG")), false, null))
            .get(0)
            .contains("bg_BG липсва"));
    assertTrue(
        BgCountrySeedCheck.violations(new Snapshot(List.of(bg(1, "30", "RNT-BG")), true, null))
            .get(0)
            .contains("без държава"));
    assertTrue(
        BgCountrySeedCheck.violations(
                new Snapshot(List.of(bg(1, "30", "RNT-BG"), la(5, "99", null)), true, 5L))
            .get(0)
            .contains("сочи държава id 5"));
    assertTrue(
        BgCountrySeedCheck.violations(
                new Snapshot(
                    List.of(bg(1, "30", "RNT-BG"), new CountryRow(5, "99", "LA", "BGR", null)),
                    true,
                    1L))
            .get(0)
            .contains("alpha3 BGR"));
    assertTrue(
        BgCountrySeedCheck.violations(new Snapshot(List.of(), true, null))
            .get(0)
            .contains("липсва в номенклатурата"));
  }

  @Test
  @DisplayName("локализацията по подразбиране: само bg_BG мълчи")
  void defaultLocalizationWarning() {
    assertNull(BgCountrySeedCheck.defaultPartnerLocalizationWarning("bg_BG"));
    assertTrue(BgCountrySeedCheck.defaultPartnerLocalizationWarning("en_GB").contains("en_GB"));
    assertTrue(BgCountrySeedCheck.defaultPartnerLocalizationWarning(null).contains("(празно)"));
  }
}
