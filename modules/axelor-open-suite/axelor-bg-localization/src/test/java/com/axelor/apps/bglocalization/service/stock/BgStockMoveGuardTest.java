package com.axelor.apps.bglocalization.service.stock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.CancelReason;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.module.BgLocalizationModule;
import com.axelor.apps.stock.db.StockCorrection;
import com.axelor.apps.stock.db.StockCorrectionReason;
import com.axelor.apps.stock.db.StockMove;
import com.axelor.apps.stock.service.StockMoveServiceImpl;
import com.axelor.apps.supplychain.service.StockMoveServiceSupplychainImpl;
import com.google.inject.spi.Element;
import com.google.inject.spi.Elements;
import com.google.inject.spi.InterceptorBinding;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Прихващачът на отказа и реализирането: кои методи хваща, на кои класове, и че е обявен в
 * истинския {@link BgLocalizationModule}. Поведението през истинската Guice връзка доказва {@code
 * BgStockMoveGuardWiringTest}.
 */
class BgStockMoveGuardTest {

  private static StockMove move(int status) {
    StockMove m = new StockMove();
    m.setStatusSelect(status);
    return m;
  }

  @Test
  @DisplayName("реализирано движение не се отказва — ясно съобщение с пътя напред")
  void отказ_на_реализирано() {
    AxelorException e =
        assertThrows(AxelorException.class, () -> BgStockMoveGuard.refuseCancelOfRealized(move(3)));
    assertEquals(BgStockMoveGuard.CANCEL_REFUSAL, e.getMessage());
    assertTrue(e.getMessage().contains("обратен складов документ"));
    assertDoesNotThrow(() -> BgStockMoveGuard.refuseCancelOfRealized(move(2)), "планирано");
    assertDoesNotThrow(() -> BgStockMoveGuard.refuseCancelOfRealized(move(1)), "чернова");
  }

  @Test
  @DisplayName("ръчният код бие кода на причината за корекция")
  void предимство_на_кода() {
    StockMove m = move(2);
    StockCorrectionReason reason = new StockCorrectionReason();
    reason.setBgStockMovementCode("160");
    m.setStockCorrectionReason(reason);
    assertEquals("160", BgStockMoveGuard.movementCode(m));
    m.setBgSaftMovementCode("180");
    assertEquals("180", BgStockMoveGuard.movementCode(m));
    assertNull(BgStockMoveGuard.movementCode(move(2)));
  }

  private static StockMove shortage(boolean requiresPartner) {
    StockMove m = move(2);
    StockCorrectionReason reason = new StockCorrectionReason();
    reason.setName("Липса с виновно лице");
    reason.setBgStockMovementCode("120");
    reason.setBgRequiresPartner(requiresPartner);
    m.setStockCorrectionReason(reason);
    return m;
  }

  @Test
  @DisplayName("„Липса с виновно лице“ без лице — отказ; лицето идва от корекцията")
  void виновното_лице() {
    StockMove noPerson = shortage(true);
    String refusal = BgStockMoveGuard.requirePartner(noPerson);
    assertTrue(refusal != null && refusal.contains("Липса с виновно лице"), refusal);

    StockMove fromCorrection = shortage(true);
    StockCorrection correction = new StockCorrection();
    Partner person = new Partner();
    correction.setBgResponsiblePartner(person);
    fromCorrection.setStockCorrection(correction);
    assertNull(BgStockMoveGuard.requirePartner(fromCorrection));
    assertSame(person, fromCorrection.getPartner(), "лицето отива на движението — там го чете ИЗП");

    assertNull(BgStockMoveGuard.requirePartner(shortage(false)), "причина без изискване");
    assertNull(BgStockMoveGuard.requirePartner(move(2)), "без причина");
  }

  @Test
  @DisplayName("обработчиците след реализиране: само при реализирано, грешката не спира другите")
  void обработчиците() {
    List<String> calls = new ArrayList<>();
    BgStockMoveRealizedHandler failing =
        m -> {
          calls.add("failing");
          throw new IllegalStateException("неосчетоводимо");
        };
    BgStockMoveRealizedHandler recording = m -> calls.add("recording");

    BgStockMoveGuard.afterRealize(move(2), List.of(failing, recording));
    assertEquals(List.of(), calls, "нереализирано — нищо не се вика");

    assertDoesNotThrow(() -> BgStockMoveGuard.afterRealize(move(3), List.of(failing, recording)));
    assertEquals(List.of("failing", "recording"), calls);
  }

  @Test
  @DisplayName("методите: трите на отказа и realizeStockMove, само с първи аргумент StockMove")
  void методите() throws Exception {
    Class<StockMoveServiceImpl> impl = StockMoveServiceImpl.class;
    List<Method> matched =
        java.util.Arrays.stream(impl.getDeclaredMethods())
            .filter(BgStockMoveGuard.methods()::matches)
            .collect(Collectors.toList());
    List<String> names =
        matched.stream().map(Method::getName).distinct().sorted().collect(Collectors.toList());
    assertEquals(List.of("cancel", "realizeStockMove", "setCancelStatus"), names);
    assertTrue(
        BgStockMoveGuard.methods()
            .matches(impl.getDeclaredMethod("cancel", StockMove.class, CancelReason.class)));
    assertFalse(
        BgStockMoveGuard.methods().matches(impl.getDeclaredMethod("realize", StockMove.class)),
        "realize вика realizeStockMove — хваща се там, веднъж");
  }

  @Test
  @DisplayName("класовете: базовата услуга и всеки наследник — supplychain, а оттам и production")
  void класовете() {
    assertTrue(BgStockMoveGuard.classes().matches(StockMoveServiceImpl.class));
    assertTrue(BgStockMoveGuard.classes().matches(StockMoveServiceSupplychainImpl.class));
    assertFalse(BgStockMoveGuard.classes().matches(Object.class));
  }

  @Test
  @DisplayName("истинският BgLocalizationModule обявява прихващането на складовите движения")
  void модулът_го_обявява() throws Exception {
    List<Element> elements = Elements.getElements(new BgLocalizationModule());
    List<InterceptorBinding> stock =
        elements.stream()
            .filter(InterceptorBinding.class::isInstance)
            .map(InterceptorBinding.class::cast)
            .filter(b -> b.getClassMatcher().matches(StockMoveServiceSupplychainImpl.class))
            .collect(Collectors.toList());
    assertEquals(1, stock.size(), "едно прихващане на StockMoveService в модула");
    InterceptorBinding binding = stock.get(0);
    assertTrue(
        binding
            .getMethodMatcher()
            .matches(
                StockMoveServiceImpl.class.getDeclaredMethod(
                    "realizeStockMove", StockMove.class, boolean.class)));
    assertTrue(
        binding
            .getMethodMatcher()
            .matches(
                StockMoveServiceImpl.class.getDeclaredMethod("setCancelStatus", StockMove.class)));
    assertTrue(binding.getInterceptors().get(0) instanceof BgStockMoveGuard);
  }
}
