package com.axelor.apps.bglocalization.service.zddfl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.meta.db.MetaFile;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗАКАЧАНЕТО — ТОВА ОТ НЕГО, КОЕТО СЕ ПРОВЕРЯВА БЕЗ БАЗА.
 *
 * <p><b>⚠️ МОДУЛЪТ НЯМА mock-подпора</b>, тоест записването в {@code NapSubmission} и {@code
 * NapArtifact} не може да бъде разиграно тук. Проверимо обаче е всичко, което пада ПРЕДИ базата да
 * е пипната — и точно затова тези отказите са подредени така: услугата се строи с {@code null}
 * хранилища и пак трябва да откаже, защото до тях не се стига.
 */
class BgDeclarationSubmissionServiceTest {

  /** ⚠️ null хранилища: тестът е верен САМО ако отказът пада преди първото им докосване. */
  private static BgDeclarationSubmissionService service() {
    return new BgDeclarationSubmissionService(null, null, null);
  }

  @Test
  @DisplayName("⚠️ Свидетелят е ВХОДЯЩИЯТ НОМЕР, а не файлът — отказът говори за номера")
  void отказът_е_за_номера_не_за_файла() {
    // ⚠️ Нито файл, нито номер. Ако файлът все още беше вратата, съобщението щеше да е за него.
    BgDeclarationSubmissionSpec spec =
        new BgDeclarationSubmissionSpec().setName("Справка чл. 73 ал. 1 / 2026");

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> service().attach(spec));
    assertTrue(e.getMessage().contains("входящ"), e.getMessage());
    assertFalse(e.getMessage().contains("генериран файл"), e.getMessage());
  }

  @Test
  @DisplayName("⚠️ Наличен файл НЕ замества номера — подаване без свидетел пак е отказ")
  void файлът_не_замества_номера() {
    BgDeclarationSubmissionSpec spec =
        new BgDeclarationSubmissionSpec()
            .setName("Справка чл. 73 ал. 1 / 2026")
            .setMetaFile(new MetaFile())
            .setFileName("SPRAVKA.TXT");

    assertThrows(IllegalStateException.class, () -> service().attach(spec));
  }

  @Test
  @DisplayName("Липсващият файл САМ ПО СЕБЕ СИ вече не е отказ")
  void липсващият_файл_не_е_отказ() {
    // ⚠️ Пълен свидетел, нула файл: тук отказът за „няма файл“ вече го НЯМА. Пътят продължава до
    // хранилищата (които тестът подава като null), тоест грешката НЕ е IllegalStateException —
    // точно това е разликата, която този свидетел пази.
    BgDeclarationSubmissionSpec spec =
        new BgDeclarationSubmissionSpec()
            .setName("Справка чл. 73 ал. 1 / 2026")
            .setIncomingNumber("12345")
            .setIncomingDate(LocalDate.of(2027, 2, 20));

    assertThrows(NullPointerException.class, () -> service().attach(spec));
  }

  @Test
  @DisplayName("Типът на файла е ИЗВЕДЕН от разширението, а не избран")
  void типът_идва_от_разширението() {
    assertEquals("txt", BgDeclarationSubmissionService.fileTypeOf("EMPL2021.TXT"));
    assertEquals("xml", BgDeclarationSubmissionService.fileTypeOf("deklaraciq.xml"));
    assertEquals("csv", BgDeclarationSubmissionService.fileTypeOf("spravka.CSV"));
  }

  @Test
  @DisplayName("⚠️ Непознато разширение дава null, а не измислена стойност на селекцията")
  void непознатото_разширение_мълчи() {
    assertNull(BgDeclarationSubmissionService.fileTypeOf("spravka.dat"));
    assertNull(BgDeclarationSubmissionService.fileTypeOf("spravka"));
    assertNull(BgDeclarationSubmissionService.fileTypeOf("spravka."));
    assertNull(BgDeclarationSubmissionService.fileTypeOf(null));
  }

  @Test
  @DisplayName("Заключва ФАЙЛЪТ, а не само състоянието")
  void файлът_заключва_записа() {
    assertFalse(BgDeclarationStatus.isLocked(BgDeclarationStatus.DRAFT, false));
    assertFalse(BgDeclarationStatus.isLocked(BgDeclarationStatus.GENERATED, false));

    // ⚠️ Произведен файл заключва дори при състояние „чернова“: редовете зад него са раздадени.
    assertTrue(BgDeclarationStatus.isLocked(BgDeclarationStatus.DRAFT, true));
    assertTrue(BgDeclarationStatus.isLocked(BgDeclarationStatus.SUBMITTED, false));
    assertTrue(BgDeclarationStatus.isLocked(BgDeclarationStatus.ACCEPTED, false));
    assertTrue(BgDeclarationStatus.isLocked(BgDeclarationStatus.REJECTED, false));
  }

  @Test
  @DisplayName("Свидетелят на подаването се проверява и самостоятелно")
  void свидетелят_се_проверява_самостоятелно() {
    assertThrows(
        IllegalStateException.class, () -> BgDeclarationStatus.requireIncomingNumber(null, null));
    assertThrows(
        IllegalStateException.class,
        () -> BgDeclarationStatus.requireIncomingNumber("  ", LocalDate.of(2027, 3, 1)));
    assertThrows(
        IllegalStateException.class,
        () -> BgDeclarationStatus.requireIncomingNumber("12345", null));

    BgDeclarationStatus.requireIncomingNumber("12345", LocalDate.of(2027, 3, 1));
  }

  @Test
  @DisplayName("Ръчният изход иска СВИДЕТЕЛ — входящ номер и дата")
  void ръчният_изход_иска_свидетел() {
    assertThrows(
        IllegalStateException.class,
        () -> BgDeclarationStatus.requireManualOutcome(false, "12345", LocalDate.of(2027, 3, 1)));
    assertThrows(
        IllegalStateException.class,
        () -> BgDeclarationStatus.requireManualOutcome(true, null, LocalDate.of(2027, 3, 1)));
    assertThrows(
        IllegalStateException.class,
        () -> BgDeclarationStatus.requireManualOutcome(true, "  ", LocalDate.of(2027, 3, 1)));
    assertThrows(
        IllegalStateException.class,
        () -> BgDeclarationStatus.requireManualOutcome(true, "12345", null));

    // Пълният набор минава.
    BgDeclarationStatus.requireManualOutcome(true, "12345", LocalDate.of(2027, 3, 1));
  }

  @Test
  @DisplayName("Находката различава блокиращо от предупреждение")
  void находката_знае_тежестта_си() {
    assertTrue(BgDeclarationFileFinding.error("E", "съобщение").isBlocking());
    assertFalse(BgDeclarationFileFinding.warning("W", "съобщение").isBlocking());
  }
}
