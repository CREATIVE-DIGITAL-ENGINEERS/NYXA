package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.noi.BgApp9EmissionFixture;
import com.axelor.rpc.ActionResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * РЕЗУЛТАТЪТ ОТ НЕУСПЕШНИЯ ОПИТ СЕ ВИЖДА.
 *
 * <p>Клиентът (axelor-front, executor.ts) при „error“ показва диалога и спира ПРЕДИ да прочете
 * „reload“: отказът се записва в базата, а формата продължава да показва „Какво липсва“ от
 * предишния опит. Затова презареждането се подава като ДЕЙСТВИЕТО на грешката.
 */
class BgApp9ExportControllerRespondTest {

  @SuppressWarnings("unchecked")
  private static Map<String, Object> data(ActionResponse response) {
    return (Map<String, Object>) response.getItem(0);
  }

  @Test
  @DisplayName("Отказ → грешка, чието действие презарежда формата")
  void aRefusalReloadsTheFormThroughTheErrorAction() {
    ActionResponse response = new ActionResponse();

    BgApp9ExportController.respond(
        response, BgApp9EmissionFixture.refusedByHeader("няма адрес на осигурителя"));

    @SuppressWarnings("unchecked")
    Map<String, Object> error = (Map<String, Object>) data(response).get("error");
    assertNotNull(error, "отказът е грешка, не информация");
    assertEquals(BgApp9ExportController.RELOAD_ACTION, error.get("action"));
    assertTrue(String.valueOf(error.get("message")).contains("няма адрес на осигурителя"));
  }

  @Test
  @DisplayName("Действието за презареждане съществува в изгледите и вика reload")
  void theReloadActionIsDeclared() throws Exception {
    String xml =
        new String(
            Files.readAllBytes(Paths.get("src/main/resources/views/bg-app9-views.xml")),
            StandardCharsets.UTF_8);
    int at = xml.indexOf("<action-method name=\"" + BgApp9ExportController.RELOAD_ACTION + "\">");
    assertTrue(at >= 0, "действието липсва в bg-app9-views.xml");
    String block = xml.substring(at, xml.indexOf("</action-method>", at));
    assertTrue(block.contains("BgApp9ExportController\" method=\"reload\""), block);
  }

  @Test
  @DisplayName("Готов файл с изключен лист → съобщението казва „Изключени: 1 лист — …“")
  void theSuccessMessageNamesTheExcludedSheet() {
    ActionResponse response = new ActionResponse();

    BgApp9ExportController.respond(
        response, BgApp9EmissionFixture.emittedWithOneExcluded("лист 0909043 · Стоянова"));

    assertNull(data(response).get("error"));
    @SuppressWarnings("unchecked")
    Map<String, Object> info = (Map<String, Object>) data(response).get("info");
    String message = String.valueOf(info.get("message"));
    assertTrue(message.contains("Изключени: 1 лист — лист 0909043 · Стоянова"), message);
    assertEquals(Boolean.TRUE, data(response).get("reload"));
  }
}
