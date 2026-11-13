package com.axelor.apps.napsubmissions.services.saft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Текстът на заключения месец и правилата на резултата от проверката (без база). */
class SaftPeriodLockTest {

  private static NapSubmission filed(String status, String number) {
    NapSubmission s = new NapSubmission();
    s.setSubmissionStatusSelect(status);
    s.setNapIncomingNumber(number);
    return s;
  }

  @Test
  @DisplayName("Приет месец: отказ, който казва, че коригиращ файл не се генерира в Nyxa")
  void приет() {
    String text =
        SaftPeriodLock.refusal(filed(NapSubmissionRepository.STATUS_ACCEPTED, "30TST000000001"));
    assertTrue(text.contains("приет от НАП") && text.contains("30TST000000001"), text);
    assertTrue(text.contains("Коригиращ файл"), text);
  }

  @Test
  @DisplayName("Подаден, чака изход: първо „Приет“ или „Отхвърлен“")
  void чака_изход() {
    String text =
        SaftPeriodLock.refusal(filed(NapSubmissionRepository.STATUS_SUBMITTED, "30TST000000002"));
    assertTrue(text.contains("чака изход") && text.contains("30TST000000002"), text);
  }

  @Test
  @DisplayName("Няма подаване — няма отказ")
  void свободен() {
    assertNull(SaftPeriodLock.refusal((NapSubmission) null));
  }

  @Test
  @DisplayName("Резултат: без проверка не е генериран; файл с ГРЕШКА не се създава")
  void резултатът() {
    SaftCheckResult unavailable = SaftCheckResult.unavailable("няма модул");
    assertFalse(unavailable.isChecked());
    assertTrue(unavailable.hasErrors());
    assertFalse(unavailable.isGenerated());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            SaftCheckResult.file(
                List.of(SaftCheckResult.Finding.error("X", "грешка")),
                "f.xml",
                new byte[] {1},
                "00",
                "1.0.2"));
    assertTrue(
        SaftCheckResult.file(List.of(), "f.xml", new byte[] {1}, "00", "1.0.2").isGenerated());
  }
}
