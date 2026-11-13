package com.axelor.apps.bglocalization.service.nsi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgNsiGodLine;
import java.math.BigDecimal;
import java.util.Set;
import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ТОЧНАТА СУМА НА РЕДА НА ГОД Е В МАЩАБА НА КОЛОНАТА — без база (върви в CI).
 *
 * <p>Сумите по сметки идват от {@code MoveLine.debit/credit} (scale 3 в AOS), а {@code
 * BgNsiGodLine.autoCurrent/autoPrevious} са scale 2. На база с проверка на колоните (production)
 * изчисляването падаше при запис с „numeric value out of bounds (&lt;18 digits&gt;.&lt;2 digits&gt;
 * expected)“; HSQLDB тестовете са без нея и не го виждаха.
 */
class BgNsiGodLineScaleTest {

  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  private static Set<ConstraintViolation<BgNsiGodLine>> violations(BigDecimal auto) {
    BgNsiGodLine line = new BgNsiGodLine();
    line.setAutoCurrent(auto);
    line.setAutoPrevious(auto);
    return VALIDATOR.validateProperty(line, "autoCurrent");
  }

  @Test
  @DisplayName("контролът: сумата от статиите (scale 3) не минава проверката на колоната")
  void theLedgerAmountFailsTheColumn() {
    assertFalse(violations(new BigDecimal("8400.000")).isEmpty());
  }

  @Test
  @DisplayName("exact(): сумата в центове минава и е същата")
  void theExactAmountPasses() {
    BigDecimal exact = BgNsiGodService.exact(new BigDecimal("8400.000"));
    assertTrue(violations(exact).isEmpty());
    assertEquals(0, new BigDecimal("8400").compareTo(exact));
    assertEquals(2, exact.scale());
  }

  @Test
  @DisplayName("exact(): половин цент — нагоре (HALF_UP), празно — нула")
  void halfUpAndNull() {
    assertEquals(new BigDecimal("0.01"), BgNsiGodService.exact(new BigDecimal("0.005")));
    assertEquals(new BigDecimal("-0.01"), BgNsiGodService.exact(new BigDecimal("-0.005")));
    assertEquals(new BigDecimal("0.00"), BgNsiGodService.exact(null));
  }
}
