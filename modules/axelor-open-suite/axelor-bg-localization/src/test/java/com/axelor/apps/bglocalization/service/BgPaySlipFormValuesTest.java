package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КАКВО ОТ ФОРМАТА ВЛИЗА В „ПРЕИЗЧИСЛИ“ — без база, за да върви и в CI. Пътят с базата (статусът от
 * базата, дружеството, периодът) е в {@code BgPaySlipCalculateTransactionTest}.
 */
class BgPaySlipFormValuesTest {

  @Test
  @DisplayName(
      "Статусът, отпечатъкът и признаците на копието не влизат — и при редовен, и при копие")
  void systemFieldsNeverGetIn() {
    for (boolean retro : new boolean[] {false, true}) {
      Map<String, Object> in = new HashMap<>();
      in.put("id", 7L);
      in.put("version", 3);
      in.put("statusSelect", "CONFIRMED");
      in.put("bgCalcFingerprint", "подправен");
      in.put("bgIsCorrection", true);
      in.put("bgCorrectionOf", ref(5L));
      in.put("bgIsHistoricalImport", true);
      in.put("bgGeneratorRefusalCount", 0);
      in.put("note", "бележка");

      Map<String, Object> out = BgPaySlipFormValues.accepted(in, retro);

      for (String name :
          new String[] {
            "statusSelect",
            "bgCalcFingerprint",
            "bgIsCorrection",
            "bgCorrectionOf",
            "bgIsHistoricalImport",
            "bgGeneratorRefusalCount"
          }) {
        assertFalse(out.containsKey(name), "ретро=" + retro + ": " + name + " влезе");
      }
      assertEquals("бележка", out.get("note"), "обикновеното поле остава");
      assertEquals(7L, out.get("id"));
      assertEquals(3, out.get("version"), "версията на фиша остава — оптимистичната проверка");
    }
  }

  @Test
  @DisplayName("Замразеното не влиза в редовен фиш; в копие то е редакцията на оператора и остава")
  void frozenOnlyOnACopy() {
    Map<String, Object> in = new HashMap<>();
    in.put("bgRegimeInsuredTypeCode", "12");
    in.put("bgRegimeFrozenAt", "2026-03-01T10:00:00");
    in.put("bgLeaveDays161", "3");
    in.put("bgCapUsageFrozenAt", "2026-03-01T10:00:00");

    Map<String, Object> regular = BgPaySlipFormValues.accepted(in, false);
    Map<String, Object> copy = BgPaySlipFormValues.accepted(in, true);

    assertTrue(regular.isEmpty(), "редовен: нищо замразено не влиза — " + regular);
    assertEquals(in.keySet(), copy.keySet(), "копие: редакцията на оператора остава");
  }

  @Test
  @DisplayName(
      "Препратката само се избира: периодът с version и статус стига до JPA.edit като {id}")
  void referencesAreOnlyChosen() {
    Map<String, Object> period = new HashMap<>();
    period.put("id", 11L);
    period.put("version", 2);
    period.put("statusSelect", 1);
    Map<String, Object> in = new HashMap<>();
    in.put("period", period);

    Object out = BgPaySlipFormValues.accepted(in, false).get("period");

    assertEquals(ref(11L), out, "без version JPA.edit не редактира периода");
  }

  @Test
  @DisplayName(
      "Перата се редактират, но техните препратки — само се избират; колекциите им отпадат")
  @SuppressWarnings("unchecked")
  void linesKeepTheirValuesButOnlyChooseReferences() {
    Map<String, Object> type = new HashMap<>();
    type.put("id", 21L);
    type.put("version", 4);
    type.put("bgIsTaxable", false);
    Map<String, Object> line = new HashMap<>();
    line.put("id", 31L);
    line.put("version", 1);
    line.put("bgAmount", "150.00");
    line.put("bgPaySlipLineType", type);
    List<Object> lines = new ArrayList<>();
    lines.add(line);
    Map<String, Object> in = new HashMap<>();
    in.put("bgPaySlipLineList", lines);

    List<Object> out =
        (List<Object>) BgPaySlipFormValues.accepted(in, false).get("bgPaySlipLineList");

    Map<String, Object> outLine = (Map<String, Object>) out.get(0);
    assertEquals("150.00", outLine.get("bgAmount"), "сумата на перото е редакцията");
    assertEquals(1, outLine.get("version"), "версията на перото остава");
    assertEquals(ref(21L), outLine.get("bgPaySlipLineType"), "видът перо само се избира");
  }

  @Test
  @DisplayName("Нов свързан запис (препратка без id) → отказ, не тихо раждане")
  void aNewLinkedRecordIsRefused() {
    Map<String, Object> contract = new HashMap<>();
    contract.put("payCompany", ref(1L));
    Map<String, Object> in = new HashMap<>();
    in.put("employmentContract", contract);

    assertThrows(
        BgCalculationRefusedException.class, () -> BgPaySlipFormValues.accepted(in, false));
  }

  @Test
  @DisplayName("Статус от формата, различен от DRAFT → назован отказ; DRAFT или липсващ → нищо")
  void forgedStatus() {
    Map<String, Object> in = new HashMap<>();
    assertNull(BgPaySlipFormValues.forgedStatusRefusal(in));
    in.put("statusSelect", "DRAFT");
    assertNull(BgPaySlipFormValues.forgedStatusRefusal(in));
    in.put("statusSelect", "CONFIRMED");
    String refusal = BgPaySlipFormValues.forgedStatusRefusal(in);
    assertNotNull(refusal);
    assertTrue(refusal.contains("CONFIRMED"), refusal);
  }

  private static Map<String, Object> ref(Long id) {
    Map<String, Object> ref = new HashMap<>();
    ref.put("id", id);
    return ref;
  }
}
