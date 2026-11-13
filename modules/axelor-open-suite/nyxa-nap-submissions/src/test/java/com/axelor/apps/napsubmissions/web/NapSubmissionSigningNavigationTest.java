package com.axelor.apps.napsubmissions.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НАВИГАЦИЯ БЕЗ ТРЕТИ ЕКРАН. „Подпиши и изпрати“ вече не отваря отделна страница — вгражда
 * подписващия поток ВЪВ формата чрез релативен (same-origin) URL, който се показва в iframe. Тук се
 * проверява точно това, което решава дали е вграждане или нов екран: адресът е релативен (ws/...)
 * към същия REST ресурс, носи подаването и избрания метод, и никога не сочи външна страница.
 */
class NapSubmissionSigningNavigationTest {

  private final NapSubmissionController controller = new NapSubmissionController();

  @Test
  @DisplayName("URL за подписване е релативен (same-origin) — вгражда се, не отваря нов екран")
  void signingUrlIsRelativeSoItEmbedsInsteadOfOpeningANewScreen() {
    String url = controller.browserSigningUrl(42L, NapSubmissionRepository.QES_METHOD_CLOUD);

    assertTrue(url.startsWith("ws/nyxa/nap/browser-signing/page"), url);
    assertFalse(url.startsWith("http"), url);
    assertFalse(url.startsWith("//"), url);
    assertTrue(url.contains("submissionId=42"), url);
    assertTrue(url.contains("method=cloud"), url);
  }

  @Test
  @DisplayName("И двата метода дават същия вграден ресурс, само методът се сменя")
  void bothMethodsReuseTheSameEmbeddedResource() {
    String usb = controller.browserSigningUrl(7L, NapSubmissionRepository.QES_METHOD_LOCAL_USB);
    String cloud = controller.browserSigningUrl(7L, NapSubmissionRepository.QES_METHOD_CLOUD);

    assertTrue(usb.startsWith("ws/nyxa/nap/browser-signing/page?submissionId=7"), usb);
    assertTrue(usb.endsWith("method=local_usb"), usb);
    assertTrue(cloud.endsWith("method=cloud"), cloud);
  }
}
