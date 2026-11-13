package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * „ГЕНЕРИРАЙ“ НЕ ГЪЛТА ГРЕШКИ: деклараторът без ЕГН получава точната причина, не „няма попълнен
 * декларатор“, а провалът при запис на дневниците не излиза като английски текст на изключението.
 */
class BgVatReturnGenerateFailureTest {

  private final BgVatReturnController controller = new BgVatReturnController();

  private static BgVatExportRequestDto declarant(String egn, String names, String capacity) {
    BgVatExportRequestDto dto = new BgVatExportRequestDto();
    dto.setDeclarantEgn(egn);
    dto.setDeclarantNames(names);
    dto.setDeclarantCapacity(capacity);
    dto.setDeclarantCity("София");
    dto.setDeclarantPostCode("1000");
    dto.setDeclarantAddress("ул. Раковски 1");
    return dto;
  }

  @Test
  @DisplayName("избран декларатор без ЕГН: кое липсва и че DEKLAR.TXT е непълен")
  void деклараторът_без_егн() {
    List<String> errors =
        controller.validateDeclarant(declarant(null, "Иван Георгиев Петров", "A"));

    assertEquals(1, errors.size(), errors.toString());
    String error = errors.get(0);
    assertTrue(error.startsWith("Деклараторът на декларацията няма ЕГН/ЛНЧ —"), error);
    assertTrue(error.contains("DEKLAR.TXT"), error);
    assertTrue(error.contains("непълни"), error);
    assertFalse(error.contains("firm default"), error);
  }

  @Test
  @DisplayName("всички липсващи полета — изброени")
  void всички_липсващи() {
    String error = controller.validateDeclarant(declarant(" ", "", null)).get(0);

    assertTrue(error.contains("няма ЕГН/ЛНЧ, имена, качество"), error);
  }

  @Test
  @DisplayName("пълен декларатор — без грешка")
  void пълен_декларатор() {
    assertTrue(
        controller
            .validateDeclarant(declarant("8505151039", "Иван Георгиев Петров", "A"))
            .isEmpty());
  }

  @Test
  @DisplayName("провал при запис: какво НЕ е станало + най-вътрешната причина")
  void провал_при_запис() {
    Exception wrapped =
        new RuntimeException(
            "javax.persistence.RollbackException",
            new IllegalStateException(
                "Дневниците не могат да се запишат — дневник за покупки, документ № 0000009001,"
                    + " кл.11 = 16.667 — повече от 2 знака след десетичната запетая"));

    String text = BgVatReturnController.saveFailure(wrapped);

    assertTrue(text.startsWith("Файловете са съставени, но дневниците и пакетът не са записани"));
    assertTrue(text.contains("нищо не е изтеглено"), text);
    assertTrue(text.endsWith("кл.11 = 16.667 — повече от 2 знака след десетичната запетая"), text);
    assertFalse(text.contains("RollbackException"), text);
  }

  @Test
  @DisplayName("изключение без съобщение — името му, не „null“")
  void без_съобщение() {
    assertEquals(
        "java.lang.NullPointerException",
        BgVatReturnController.failureText(new NullPointerException()));
  }
}
