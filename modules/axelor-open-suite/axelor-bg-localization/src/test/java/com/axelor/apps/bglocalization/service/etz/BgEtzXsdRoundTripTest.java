package com.axelor.apps.bglocalization.service.etz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРОИЗВЕДЕНИЯТ ФАЙЛ МИНАВА СРЕЩУ ИСТИНСКАТА СХЕМА С <b>0 ГРЕШКИ</b>.
 *
 * <p>Това е свидетелят, който Фаза 1 не можеше да има: схемите вече са закрепени и валидацията е
 * реална. Той пази едновременно структурата (имена, ред, вложеност), типовете ({@code xs:date},
 * {@code xs:float}, шаблоните за 3/4/5/8 цифри) и кодировката — парсерът чете декларацията от
 * самите байтове.
 */
class BgEtzXsdRoundTripTest {

  private final BgEtzFixture fixture = new BgEtzFixture();
  private final BgEtzResolver resolver = new BgEtzResolver();
  private final BgEtzEmitter emitter = new BgEtzEmitter();
  private final BgEtzValidator validator = new BgEtzValidator();

  @Test
  @DisplayName("Сключване (тип 1): 0 пречки и 0 грешки срещу etz_employ_restrict.xsd")
  void conclusionValidatesCleanly() {
    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file =
        resolver.resolve(BgEtzFacts.of(fixture.contract()), BgEtzEventKind.CONCLUSION, gaps);

    assertTrue(
        gaps.isEmpty(),
        "пречки при напълно попълнен договор:\n" + BgEtzExportService.describeGaps(gaps));

    byte[] bytes = emitter.emit(file);
    List<BgEtzFinding> findings = validator.validate(bytes, file);

    assertEquals(
        0,
        findings.size(),
        "файлът не мина схемата:\n"
            + BgEtzExportService.describeFindings(findings)
            + "\n--- файлът ---\n"
            + new String(bytes, BgEtzXmlWriter.CHARSET));
  }

  @Test
  @DisplayName("Прекратяване (тип 3): 0 пречки и 0 грешки, когато т. 25 и т. 30 са подадени")
  void terminationValidatesCleanly() {
    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file =
        resolver.resolve(
            BgEtzFacts.of(fixture.contract()),
            BgEtzEventKind.TERMINATION,
            gaps,
            fixture.terminationInput());

    assertTrue(gaps.isEmpty(), "пречки:\n" + BgEtzExportService.describeGaps(gaps));

    byte[] bytes = emitter.emit(file);
    List<BgEtzFinding> findings = validator.validate(bytes, file);

    assertEquals(
        0,
        findings.size(),
        "файлът не мина схемата:\n"
            + BgEtzExportService.describeFindings(findings)
            + "\n--- файлът ---\n"
            + new String(bytes, BgEtzXmlWriter.CHARSET));
  }

  @Test
  @DisplayName("Кореновият елемент и типът на документа са според събитието")
  void rootAndDocumentTypePerEvent() {
    String conclusion = emit(BgEtzEventKind.CONCLUSION, null);
    assertTrue(conclusion.contains("<etzemploy>"), conclusion);
    assertTrue(conclusion.contains("<documenttype>1</documenttype>"), conclusion);

    String termination = emit(BgEtzEventKind.TERMINATION, fixture.terminationInput());
    assertTrue(termination.contains("<documenttype>3</documenttype>"), termination);
  }

  @Test
  @DisplayName("Датите излизат по ISO, а не по „ддммгггг“ — иначе xs:date ги отхвърля")
  void datesAreIsoNotDdmmyyyy() {
    String xml = emit(BgEtzEventKind.CONCLUSION, null);

    assertTrue(xml.contains("<contractdate>2026-03-02</contractdate>"), xml);
    assertTrue(xml.contains("<begindate>2026-03-09</begindate>"), xml);
    assertFalse(xml.contains("02032026"), "датата е по човешкия формат на Наредбата: " + xml);
  }

  @Test
  @DisplayName("Контролен опит: „ддммгггг“ НАИСТИНА пада на схемата")
  void ddmmyyyyWouldBeRejected() {
    // Без този опит твърдението „ISO, защото схемата иска xs:date“ е само прочит на XSD-то.
    String xml = emit(BgEtzEventKind.CONCLUSION, null).replace("2026-03-02", "02032026");
    List<BgEtzFinding> findings =
        validator.validate(xml.getBytes(BgEtzXmlWriter.CHARSET), BgEtzXsd.Schema.EMPLOY);

    assertFalse(findings.isEmpty(), "схемата прие „02032026“ като дата — прочитът е грешен");
    assertTrue(
        BgEtzExportService.describeFindings(findings).contains("contractdate"),
        BgEtzExportService.describeFindings(findings));
  }

  @Test
  @DisplayName("Кодовете по т. 12 и т. 27 са преведените, а не номерата на членовете")
  void codesComeFromTheOrdinanceTable() {
    // чл. 67 → 001, а чл. 328 ал. 1 т. 2 → 029. „328“ никъде не се появява като код.
    String xml = emit(BgEtzEventKind.TERMINATION, fixture.terminationInput());

    assertTrue(xml.contains("<contracttype>001</contracttype>"), xml);
    assertTrue(xml.contains("<endreason>029</endreason>"), xml);
    assertFalse(xml.contains("<endreason>328</endreason>"), xml);
  }

  @Test
  @DisplayName("Кодът за ЕГН е 0, а не нашата „1“ — двете номенклатури не съвпадат")
  void egnTypeIsTranslated() {
    String xml = emit(BgEtzEventKind.CONCLUSION, null);
    assertTrue(xml.contains("<egntype>0</egntype>"), xml);
    assertFalse(xml.contains("<egntype>1</egntype>"), xml);
  }

  @Test
  @DisplayName("Файлът е WINDOWS-1251 в байтове, не UTF-8")
  void fileIsCp1251() {
    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file =
        resolver.resolve(BgEtzFacts.of(fixture.contract()), BgEtzEventKind.CONCLUSION, gaps);
    byte[] bytes = emitter.emit(file);

    assertTrue(
        indexOf(bytes, "Георгиев".getBytes(BgEtzXmlWriter.CHARSET)) > 0,
        "фамилията липсва в CP1251 байтове");
    assertEquals(
        -1,
        indexOf(bytes, "Георгиев".getBytes(StandardCharsets.UTF_8)),
        "файлът съдържа UTF-8 байтове");
    assertTrue(
        new String(bytes, BgEtzXmlWriter.CHARSET)
            .startsWith("<?xml version=\"1.0\" encoding=\"WINDOWS-1251\"?>"));
  }

  @Test
  @DisplayName("Празен елемент не се пише — <begindate></begindate> не е валидна дата")
  void emptyElementsAreOmitted() {
    // Презимето го има; ако го махнем, тагът трябва да ОТСЪСТВА, а не да е празен.
    var contract = fixture.contract();
    contract.getEmployee().getContactPartner().setBgMiddleName(null);

    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file = resolver.resolve(BgEtzFacts.of(contract), BgEtzEventKind.CONCLUSION, gaps);
    String xml = new String(emitter.emit(file), BgEtzXmlWriter.CHARSET);

    assertFalse(xml.contains("<secondname></secondname>"), xml);
    assertFalse(xml.contains("<secondname>"), xml);
    assertTrue(
        gaps.isEmpty(), "презимето е незадължително: " + BgEtzExportService.describeGaps(gaps));
  }

  private String emit(BgEtzEventKind kind, BgEtzTerminationInput input) {
    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file = resolver.resolve(BgEtzFacts.of(fixture.contract()), kind, gaps, input);
    return new String(emitter.emit(file), BgEtzXmlWriter.CHARSET);
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i + needle.length <= haystack.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }
}
