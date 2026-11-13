package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СЛЕД OK В „ОТРАЗИ ПРИСТИГНАЛ БОЛНИЧЕН“ СЕ ОТВАРЯ ТОЧНО СЪЗДАДЕНОТО КОПИЕ.
 *
 * <p>Действие, което отваря формата с домейн по препратката към оригинала и без запис за показване,
 * кара Axelor да отвори празна форма за НОВ фиш в нов таб, а копието остава незнайно къде. Изгледът
 * е чиста функция на копието, тоест се мери без база и без контейнер.
 */
class BgSickLeaveControllerCopyViewTest {

  @Test
  @DisplayName("Изгледът отваря копието по идентификатора му, във формата на фиша")
  @SuppressWarnings("unchecked")
  void theArrivalOpensExactlyTheCreatedCopy() {
    BgPaySlip original = new BgPaySlip();
    original.setId(41L);
    BgPaySlip copy = new BgPaySlip();
    copy.setId(77L);
    copy.setBgIsCorrection(true);
    copy.setBgCorrectionOf(original);

    Map<String, Object> view = BgSickLeaveController.copyView(copy);
    Map<String, Object> context = (Map<String, Object>) view.get("context");
    List<Map<String, Object>> views = (List<Map<String, Object>>) view.get("views");

    assertEquals(77L, context.get("_showRecord"), "показва се ЗАПИСЪТ на копието, не нова форма");
    assertEquals("self.id = :copyId", view.get("domain"), "и само той");
    assertEquals(77L, context.get("copyId"));
    assertEquals(BgPaySlip.class.getName(), view.get("model"));
    assertEquals("form", views.get(0).get("type"), "формата първа");
    assertEquals("bg-payslip-form", views.get(0).get("name"));
    assertFalse(
        context.containsKey("originalId"),
        "⚠️ НЕ по препратката към оригинала: тя би показала и отказани копия от предишни опити");
  }
}
