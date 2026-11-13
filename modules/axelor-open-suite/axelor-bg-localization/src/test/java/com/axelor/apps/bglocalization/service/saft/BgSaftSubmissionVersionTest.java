package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXsd;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВЕРСИЯТА Е ПО МОМЕНТА НА ПОДАВАНЕ, А NYXA НЕ ЗНАЕ КОГА ФАЙЛЪТ ЩЕ БЪДЕ КАЧЕН.
 *
 * <p>QA „Въпроси и отговори“ на НАП (март 2026 г., версия 2), II.2.4, с. 13: файловете „следва да
 * съответстват на XSD-схемата, която е актуална към момента на подаване“; Заповед №
 * З-ЦУ-30-1797/13.10.2026 — подаванията от 01.11.2026 са по 1.0.3. Таблицата {@link
 * BgSaftXsd#VERSIONS} избира по датата на генериране; при изтегляне и при входящия номер версията
 * във файла се сверява с версията за ДНЕС.
 */
class BgSaftSubmissionVersionTest {

  private static final LocalDate OCT_31 = LocalDate.of(2026, 10, 31);
  private static final LocalDate NOV_1 = LocalDate.of(2026, 11, 1);

  /** Файлът, какъвто генераторът го дава на {@code day}: S.H.1 по таблицата за този ден. */
  private static byte[] generatedOn(LocalDate day) {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getHeader()
        .auditFileVersion(BgSaftXsd.forDate(day).getAuditFileVersion())
        .auditFileDateCreated(day);
    return BgSaftXmlWriter.toBytes(file);
  }

  @Test
  @DisplayName("Генериран на 31.10.2026, изтеглен на 01.11.2026 — отказ: генерирайте отново")
  void преходът_се_отказва() {
    assertEquals("1.0.2", BgSaftSubmissionVersion.auditFileVersion(generatedOn(OCT_31)));
    assertEquals(
        "Версията на SAF-T от 01.11.2026 е 1.0.3. Генерирайте файла отново.",
        BgSaftSubmissionVersion.refusal(generatedOn(OCT_31), NOV_1));
  }

  @Test
  @DisplayName("Генериран и изтеглен в един и същи период — минава")
  void същият_период_минава() {
    assertNull(BgSaftSubmissionVersion.refusal(generatedOn(NOV_1), NOV_1));
    assertNull(BgSaftSubmissionVersion.refusal(generatedOn(OCT_31), OCT_31));
    assertNull(BgSaftSubmissionVersion.refusal(generatedOn(NOV_1), LocalDate.of(2027, 3, 1)));
  }

  @Test
  @DisplayName("Файл без S.H.1 или със стария „007“ — отказ със същия текст")
  void без_версия_се_отказва() {
    String header =
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?><nsSAFT:AuditFile xmlns:nsSAFT=\""
            + BgSaftXsd.NAMESPACE
            + "\"><nsSAFT:Header>";
    byte[] old =
        (header
                + "<nsSAFT:AuditFileVersion>007</nsSAFT:AuditFileVersion></nsSAFT:Header>"
                + "</nsSAFT:AuditFile>")
            .getBytes(StandardCharsets.UTF_8);
    byte[] none = (header + "</nsSAFT:Header></nsSAFT:AuditFile>").getBytes(StandardCharsets.UTF_8);
    String expected = "Версията на SAF-T от 01.04.2026 е 1.0.2. Генерирайте файла отново.";
    assertEquals(expected, BgSaftSubmissionVersion.refusal(old, OCT_31));
    assertEquals(expected, BgSaftSubmissionVersion.refusal(none, OCT_31));
    assertEquals(
        expected,
        BgSaftSubmissionVersion.refusal("не е XML".getBytes(StandardCharsets.UTF_8), OCT_31));
  }

  @Test
  @DisplayName("Точката за разширение на nyxa-nap-submissions вика същата проверка")
  void проверката_на_подаването() {
    BgSaftPayloadVersionCheck check = new BgSaftPayloadVersionCheck();
    assertEquals(
        "Версията на SAF-T от 01.11.2026 е 1.0.3. Генерирайте файла отново.",
        check.refusal(generatedOn(OCT_31), NOV_1));
    assertNull(check.refusal(generatedOn(NOV_1), NOV_1));
  }
}
