package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.service.PeriodServiceImpl;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Решение 4: периодът не се затваря, докато има неосчетоводени изписвания. */
class BgStockPeriodCloseGuardTest {

  @Test
  @DisplayName("отказът назовава периода, броя и до пет движения")
  void текстът() {
    assertNull(BgStockPeriodCloseGuard.refusal("03/2026", List.of()));
    String one = BgStockPeriodCloseGuard.refusal("03/2026", List.of("EXP000001"));
    assertTrue(
        one.contains("03/2026") && one.contains("1 неосчетоводени") && one.contains("EXP000001"),
        one);
    String many =
        BgStockPeriodCloseGuard.refusal(
            "03/2026", List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7"));
    assertTrue(many.contains("7 неосчетоводени") && many.contains("A5, …"), many);
    assertFalse(many.contains("A6"), many);
  }

  @Test
  @DisplayName("прихващат се четирите метода за затваряне с аргумент Period — и само те")
  void методите() throws Exception {
    for (String name :
        List.of("close", "closeTemporarily", "validateClosure", "validateTempClosure")) {
      assertTrue(
          BgStockPeriodCloseGuard.methods()
              .matches(PeriodServiceImpl.class.getMethod(name, Period.class)),
          name);
    }
    assertFalse(
        BgStockPeriodCloseGuard.methods()
            .matches(PeriodServiceImpl.class.getMethod("adjust", Period.class)),
        "корекцията на период не е затваряне");
    assertTrue(BgStockPeriodCloseGuard.classes().matches(PeriodServiceImpl.class));
  }
}
