package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.bglocalization.db.BgIncomePaymentFeedWizard;
import com.axelor.apps.bglocalization.service.zddfl.BgIncomePaymentFeedMapper;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ „ПРАЗНО ТРИМЕСЕЧИЕ“ ИДВА ОТ ФОРМАТА КАТО НУЛА, НЕ КАТО {@code null} — и това не е дреболия.
 *
 * <p><b>КАПАНЪТ, ИЗМЕРЕН.</b> Генерираният getter за {@code Integer} връща 0 за непопълнена колона
 * ({@code BgDefaultedGetterTest} пази точно това). Тоест „не съм избрал тримесечие“ и „нулево
 * тримесечие“ са НЕРАЗЛИЧИМИ по модела. Подадена направо на обхвата, нулата дава {@code
 * firstDayOf(година, 0)} = 1 януари МИНУС три месеца — прогон върху ОКТОМВРИ НА ПРЕДХОДНАТА ГОДИНА,
 * при напълно зелен екран и правдоподобен резултат.
 *
 * <p>Оттам и този тест: той не проверява „контролерът работи“, а точно превода нула → {@code null}
 * и какво би станало без него.
 */
class BgIncomePaymentFeedScopeTest {

  @Test
  @DisplayName("непопълненото тримесечие се чете като ЦЯЛАТА година, а не като тримесечие нула")
  void emptyQuarterBecomesTheWholeYear() {
    BgIncomePaymentFeedWizard form = new BgIncomePaymentFeedWizard();

    assertEquals(
        0,
        (int) form.getQuarter(),
        "ако това вече не е нула, генераторът е сменил подразбирането и преводът иска нов прочит");
    assertNull(
        BgIncomePaymentFeedController.scopeQuarter(form),
        "нулата трябва да стигне надолу като null — стойността, която обхватът разбира");
  }

  @Test
  @DisplayName("избраното тримесечие минава непокътнато")
  void chosenQuarterPassesThrough() {
    BgIncomePaymentFeedWizard form = new BgIncomePaymentFeedWizard();
    form.setQuarter(2);

    assertEquals(2, (int) BgIncomePaymentFeedController.scopeQuarter(form));
  }

  /**
   * ⚠️ ЦЕНАТА НА ПРОПУСНАТИЯ ПРЕВОД, ИЗПИСАНА С ДАТИ. Нулата не пада никъде — тя дава ЗАКОНЕН
   * период, само че на предходната година. Точно затова преводът има свой свидетел, а не се пази от
   * „ще се види“.
   */
  @Test
  @DisplayName("нула, подадена като тримесечие, би дала октомври на ПРЕДХОДНАТА година")
  void zeroWouldSilentlyShiftTheScopeBackAQuarter() {
    assertEquals(LocalDate.of(2025, 10, 1), BgIncomePaymentFeedMapper.firstDayOf(2026, 0));
    assertEquals(LocalDate.of(2026, 1, 1), BgIncomePaymentFeedMapper.firstDayOf(2026, null));
  }
}
