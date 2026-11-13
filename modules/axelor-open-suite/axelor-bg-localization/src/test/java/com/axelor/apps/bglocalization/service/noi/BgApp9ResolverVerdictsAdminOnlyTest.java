package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.web.BgApp9DeclarationController;
import com.axelor.rpc.ActionResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * „ПРИСЪДИ НА РЕЗОЛВЕРА“ — само за администратор.
 *
 * <p>Панелът (на удостоверението и във вградения редактор на болничния) и съобщението „Изведени
 * точки: … присъди“ са техническият изход на резолвера; тук само се скриват от оператора.
 */
class BgApp9ResolverVerdictsAdminOnlyTest {

  private static final String ADMIN_ONLY =
      "if=\"__user__.code == 'admin' || __user__.group?.code == 'admins'\"";

  /** Контролерът с подменено „администратор ли е“ — останалото е истинският {@code report}. */
  private static final class Controller extends BgApp9DeclarationController {
    private final boolean admin;

    Controller(boolean admin) {
      this.admin = admin;
    }

    @Override
    protected boolean showsResolverVerdicts() {
      return admin;
    }

    void reportTo(ActionResponse response, BgApp9Resolution resolution) {
      report(response, resolution);
    }
  }

  private static BgApp9Resolution resolution() {
    return new BgApp9Resolution(
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        Arrays.asList(
            BgApp9Outcome.derived("p2", "1", "свидетел"), BgApp9Outcome.refused("p3", "липсва")));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> item(ActionResponse response) {
    return (Map<String, Object>) ((List<?>) response.getData()).get(0);
  }

  @Test
  @DisplayName("Операторът: без „Изведени точки: … присъди“, само презареждане")
  void theOperatorGetsNoVerdictMessage() {
    ActionResponse response = new ActionResponse();
    new Controller(false).reportTo(response, resolution());
    assertNull(item(response).get("info"), "съобщение: " + item(response));
    assertEquals(Boolean.TRUE, item(response).get("reload"));
  }

  @Test
  @DisplayName("Администраторът: съобщението е непроменено")
  void theAdministratorStillGetsIt() {
    ActionResponse response = new ActionResponse();
    new Controller(true).reportTo(response, resolution());
    // ActionResponse#setInfo пази {message=…}, не голия текст.
    Object raw = item(response).get("info");
    String info = String.valueOf(raw instanceof Map ? ((Map<?, ?>) raw).get("message") : raw);
    assertTrue(info.startsWith("Изведени точки: 1 от 2 присъди."), info);
    assertTrue(info.contains("ОТКАЗИ: 1"), info);
  }

  @Test
  @DisplayName("И двата панела „Присъди на резолвера“ са с условие „само администратор“")
  void bothVerdictPanelsAreAdminOnly() throws Exception {
    assertPanel("src/main/resources/views/bg-app9-views.xml", "verdictPanel");
    assertPanel("src/main/resources/views/bg-sick-leave-views.xml", "app9VerdictPanel");
  }

  private static void assertPanel(String file, String name) throws Exception {
    String xml = new String(Files.readAllBytes(Paths.get(file)), StandardCharsets.UTF_8);
    Matcher m = Pattern.compile("<panel name=\"" + name + "\"[^>]*>").matcher(xml);
    assertTrue(m.find(), file + ": няма панел " + name);
    assertTrue(m.group().contains(ADMIN_ONLY), file + " / " + name + ": " + m.group());
  }
}
