package com.axelor.apps.napsubmissions.common.submission;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.services.saft.SaftPayloadVersionCheck;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВХОДЯЩИЯТ НОМЕР НА SAF-T СЕ ЗАПИСВА САМО ЗА ФАЙЛ ПО ВЕРСИЯТА ЗА ДНЕС.
 *
 * <p>Модулът не знае схемите — проверката е {@link SaftPayloadVersionCheck}, която
 * axelor-bg-localization заменя. Тук: отказът спира записа ПРЕДИ състоянието да се смени, а файл,
 * който минава, не пречи.
 */
class ManualSubmissionVersionTest {

  private static final byte[] PAYLOAD = "<AuditFile/>".getBytes(StandardCharsets.UTF_8);
  private static final LocalDate TODAY = LocalDate.of(2026, 11, 1);

  /** The portal's date of the incoming number — in the past, as the manual path requires. */
  private static final LocalDate PORTAL_DATE = LocalDate.of(2026, 10, 1);

  private static final class TestService extends ManualSubmissionService {
    TestService(SaftPayloadVersionCheck check) {
      // The real lock: these submissions name no related document, so it looks up nothing.
      super(null, null, null, check, new NapFilingLock(null));
    }

    @Override
    protected List<byte[]> payloads(NapSubmission submission) {
      return List.of(PAYLOAD);
    }

    @Override
    protected LocalDate today(NapSubmission submission) {
      return TODAY;
    }
  }

  private static NapSubmission saftSubmission() {
    NapSubmission submission = new NapSubmission();
    submission.setSaftReport(new SaftReport());
    submission.setSubmissionStatusSelect("draft");
    return submission;
  }

  @Test
  @DisplayName("Файл по старата версия: отказ с текста на проверката, нищо не се записва")
  void старата_версия_се_отказва() {
    String text = "Версията на SAF-T от 01.11.2026 е 1.0.3. Генерирайте файла отново.";
    SaftPayloadVersionCheck refusing =
        new SaftPayloadVersionCheck() {
          @Override
          public String refusal(byte[] payload, LocalDate today) {
            return PAYLOAD == payload && TODAY.equals(today) ? text : null;
          }
        };
    NapSubmission submission = saftSubmission();
    AxelorException e =
        assertThrows(
            AxelorException.class,
            () ->
                new TestService(refusing)
                    .recordManualSubmission(submission, "30TST0001", PORTAL_DATE));
    assertEquals(text, e.getMessage());
    assertEquals("draft", submission.getSubmissionStatusSelect(), "състоянието не мърда");
    assertNull(submission.getNapIncomingNumber(), "входящият номер не се записва");
  }

  @Test
  @DisplayName("Файл по днешната версия и подаване без SAF-T — проверката не пречи")
  void днешната_версия_минава() {
    TestService service = new TestService(new SaftPayloadVersionCheck());
    assertDoesNotThrow(() -> service.checkPayloadVersion(saftSubmission()));
    assertDoesNotThrow(() -> service.checkPayloadVersion(new NapSubmission()));
  }
}
