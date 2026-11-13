package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.rpc.ActionResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * „ДЛЪЖНОСТ“ В ПОДПИСА НА СПРАВКА-ДЕКЛАРАЦИЯТА (Прил. № 13: „Долуподписаният …, Длъжност, Подпис“)
 * — длъжността на декларатора, не кодът на „Качество“ (R/A). Същото смесване беше поправено в Прил.
 * 9 (#265); там полето „Длъжност“ на декларатора вече съществува.
 */
class BgVatDeclarantPositionTest {

  @Test
  @DisplayName("Печатът пише под „Длъжност“ параметъра на длъжността, не на „Качество“")
  void theReportPrintsThePositionUnderLength() throws IOException {
    String design =
        new String(
            Files.readAllBytes(Paths.get("src/main/resources/reports/BgVatDeclaration.rptdesign")),
            StandardCharsets.UTF_8);
    assertTrue(
        design.contains("Длъжност: <value-of>params[\"DeclarantPosition\"].value</value-of>"),
        "„Длъжност“ чете DeclarantPosition");
    assertFalse(
        design.contains("Длъжност: <value-of>params[\"DeclarantCapacity\"]"),
        "„Качество“ не е длъжност");
  }

  @Test
  @DisplayName("Снимката на декларатора носи длъжността — за подписа на печата")
  void theSnapshotCarriesThePosition() {
    Map<String, Object> values = new HashMap<>();
    ActionResponse response =
        new ActionResponse() {
          @Override
          public void setValue(String name, Object value) {
            values.put(name, value);
          }
        };
    BgDeclarant d = new BgDeclarant();
    d.setDeclarantCapacity("R");
    d.setDeclarantPosition("Управител");

    new BgVatReturnController().applyDeclarantSnapshot(response, d);

    assertEquals("Управител", values.get("bgDeclarantPosition"));
    assertEquals("R", values.get("bgDeclarantCapacity"), "качеството остава за своята точка");
  }
}
