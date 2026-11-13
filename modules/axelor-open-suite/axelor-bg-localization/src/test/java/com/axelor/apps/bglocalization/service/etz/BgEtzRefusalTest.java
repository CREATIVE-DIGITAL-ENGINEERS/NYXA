package com.axelor.apps.bglocalization.service.etz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.hr.db.EmploymentContract;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГЕЙТОВЕТЕ ОТКАЗВАТ — <b>и отказите назовават причината</b>.
 *
 * <p>Схемата вече е истинска, тоест изкушението да се разчита само на нея е по-голямо. Тези
 * свидетели пазят двете неща, които схемата НЕ хваща: непреведен код и липсващ по УСЛОВИЕ реквизит.
 */
class BgEtzRefusalTest {

  private final BgEtzFixture fixture = new BgEtzFixture();
  private final BgEtzResolver resolver = new BgEtzResolver();

  // ── номенклатурите ────────────────────────────────────────────────────────

  @Test
  @DisplayName("Преводът на т. 12 и т. 27 е от Наредбата, не от номера на члена")
  void codesAreTranslatedNotDerived() {
    assertEquals("001", BgEtzGrounds.contractGroundFor("67", "01"));
    assertEquals("011", BgEtzGrounds.contractGroundFor("111", "01"));
    assertEquals("012", BgEtzGrounds.contractGroundFor("114", "01"));

    assertEquals("001", BgEtzGrounds.terminationGroundFor("71_1", "KT", "01"));
    assertEquals("002", BgEtzGrounds.terminationGroundFor("325_1_1", "KT", "01"));
    assertEquals("029", BgEtzGrounds.terminationGroundFor("328_1_2", "KT", "01"));
    assertEquals("048", BgEtzGrounds.terminationGroundFor("330_2_6", "KT", "01"));
    assertEquals("054", BgEtzGrounds.terminationGroundFor("331", "KT", "01"));
    // ЗДСл води към ДРУГАТА подноменклатура — 1xx, не 0xx.
    assertEquals("111", BgEtzGrounds.terminationGroundFor("106_1_1", "ZDSL", "02"));
    assertEquals("112", BgEtzGrounds.terminationGroundFor("106_1_2", "ZDSL", "02"));
  }

  @Test
  @DisplayName("Един и същ код значи различно в двете полета — затова таблиците са две")
  void sameCodeDiffersBetweenFields() {
    assertEquals(
        "безсрочен трудов договор по чл. 67 ал. 1 т. 1 КТ",
        BgEtzGrounds.contractCodesKt().get("001"));
    assertEquals("чл. 71 ал. 1 КТ", BgEtzGrounds.endCodesKt().get("001"));
  }

  @Test
  @DisplayName("СБОРНА стойност отказва — не се избира един от групата")
  void ambiguousGroundsRefuse() {
    for (String ground : new String[] {"327_1", "330_2"}) {
      BgEtzGrounds.BgEtzUnmappedGroundException e =
          assertThrows(
              BgEtzGrounds.BgEtzUnmappedGroundException.class,
              () -> BgEtzGrounds.terminationGroundFor(ground, "KT", "01"),
              ground + " мина без отказ");
      assertTrue(e.getMessage().contains("СБОРНА"), e.getMessage());
    }
    for (String type : new String[] {"68", "121a"}) {
      BgEtzGrounds.BgEtzUnmappedGroundException e =
          assertThrows(
              BgEtzGrounds.BgEtzUnmappedGroundException.class,
              () -> BgEtzGrounds.contractGroundFor(type, "01"));
      assertTrue(e.getMessage().contains("НЕ СЕ ПРЕВЕЖДА"), e.getMessage());
    }
  }

  @Test
  @DisplayName("ДУК и ГД изобщо нямат място в регистъра")
  void nonEmploymentContractsRefuse() {
    for (String type : new String[] {"duk", "gd"}) {
      BgEtzGrounds.BgEtzUnmappedGroundException e =
          assertThrows(
              BgEtzGrounds.BgEtzUnmappedGroundException.class,
              () -> BgEtzGrounds.contractGroundFor(type, "01"));
      assertTrue(e.getMessage().contains("НЯМА място в"), e.getMessage());
    }
  }

  @Test
  @DisplayName("ЗВО няма собствен код по т. 27 — „064 друго“ НЕ се слага автоматично")
  void zvoRefusesRatherThanFallingBackToOther() {
    BgEtzGrounds.BgEtzUnmappedGroundException e =
        assertThrows(
            BgEtzGrounds.BgEtzUnmappedGroundException.class,
            () -> BgEtzGrounds.terminationGroundFor("58_1_3", "ZVO", "01"));
    assertTrue(e.getMessage().contains("064"), e.getMessage());
    assertTrue(e.getMessage().contains("ИЗЯВЛЕНИЕ"), e.getMessage());
  }

  @Test
  @DisplayName("Всеки преведен код наистина е в номенклатурата на своя вид правоотношение")
  void everyMappedCodeExistsInItsSubNomenclature() {
    assertTrue(BgEtzGrounds.isContractCode("001", "01"));
    assertFalse(BgEtzGrounds.isContractCode("001", "02"), "0xx не е в служебната номенклатура");
    assertTrue(BgEtzGrounds.isContractCode("100", "02"));
    assertTrue(BgEtzGrounds.isEndCode("064", "01"));
    assertTrue(BgEtzGrounds.isEndCode("128", "02"));
    assertFalse(BgEtzGrounds.isEndCode("999", "01"));
  }

  // ── условната задължителност ──────────────────────────────────────────────

  @Test
  @DisplayName("Липсваща ЕКАТТЕ при тип 1 е ОТКАЗ — схемата я пропуска, приложението не")
  void missingEkatteIsRefusedEvenThoughSchemaAllowsIt() {
    EmploymentContract contract = fixture.contract();
    contract.setBgWorkplaceEkatte(null);

    List<BgEtzGap> gaps = new ArrayList<>();
    BgEtzFile file = resolver.resolve(BgEtzFacts.of(contract), BgEtzEventKind.CONCLUSION, gaps);

    assertTrue(
        BgEtzExportService.describeGaps(gaps).contains("ЕКАТТЕ"),
        "ЕКАТТЕ трябва да е обявена като липса:\n" + BgEtzExportService.describeGaps(gaps));

    // ⚠️ Контролът: същият файл МИНАВА схемата, защото codeekatte е minOccurs="0".
    // Тоест без BgEtzMandatory тази липса щеше да е невидима.
    List<BgEtzFinding> findings =
        new BgEtzValidator().validate(new BgEtzEmitter().emit(file), file);
    assertEquals(
        0,
        findings.size(),
        "контролът на теста: схемата НЕ хваща липсващата ЕКАТТЕ — затова условието е в"
            + " BgEtzMandatory");
  }

  @Test
  @DisplayName("Прекратяване без т. 25 и т. 30 обявява точно тях")
  void terminationWithoutExplicitInputNamesTheTwoGaps() {
    List<BgEtzGap> gaps = new ArrayList<>();
    resolver.resolve(BgEtzFacts.of(fixture.contract()), BgEtzEventKind.TERMINATION, gaps, null);

    String text = BgEtzExportService.describeGaps(gaps);
    assertTrue(text.contains("т. 25"), text);
    assertTrue(text.contains("т. 30"), text);
    assertTrue(text.contains("BgEtzTerminationInput"), text);
  }

  @Test
  @DisplayName("Работното време извън 1–8 при дневен код е кръстосана грешка")
  void workingHoursCrossCheck() {
    BgEtzRecord row = new BgEtzRecord();
    row.put(BgEtz62Field.EMPLOYTYPE, "01");
    row.put(BgEtz62Field.DOCUMENTTYPE, "1");
    row.put(BgEtz62Field.CODEHOURS, "1");
    row.put(BgEtz62Field.WORKINGHOURS, "12");

    String text = BgEtzExportService.describeGaps(BgEtzMandatory.check62(row));
    assertTrue(text.contains("1–8"), text);
  }

  @Test
  @DisplayName("Отпуск при служебно правоотношение (02) НЕ се попълва")
  void vacationNotAllowedForCivilService() {
    BgEtzRecord row = new BgEtzRecord();
    row.put(BgEtz62Field.EMPLOYTYPE, "02");
    row.put(BgEtz62Field.DOCUMENTTYPE, "1");
    row.put(BgEtz62Field.VACATIONDAYS, "20");

    String text = BgEtzExportService.describeGaps(BgEtzMandatory.check62(row));
    assertTrue(text.contains("НЕ се попълва при вид правоотношение 02"), text);
  }

  // ── събитията, които моделът още не носи ──────────────────────────────────

  @Test
  @DisplayName("Изменение и чл. 123 отказват с назована причина, а не мълчат")
  void unsupportedEventsRefuseWithReason() {
    for (BgEtzEventKind kind :
        new BgEtzEventKind[] {BgEtzEventKind.AMENDMENT, BgEtzEventKind.EMPLOYER_CHANGE}) {
      BgEtzResolver.BgEtzUnsupportedEventException e =
          assertThrows(
              BgEtzResolver.BgEtzUnsupportedEventException.class,
              () -> resolver.resolve(BgEtzFacts.of(fixture.contract()), kind, new ArrayList<>()));
      // ⚠️ Във Фаза 2 отказът казваше „моделът чака решение“. Моделът вече е тук, тоест същият
      // отказ сега значи друго: тези две събития искат КОНКРЕТЕН запис (анекс или трансфер),
      // защото договорът носи само последното си състояние.
      assertTrue(
          e.getMessage().contains("resolveAmendment") || e.getMessage().contains("resolveTransfer"),
          e.getMessage());
    }
  }

  @Test
  @DisplayName("Основанието на промяната по чл. 123 е 1–11 и няма подразбиране")
  void correctionTypeIsBounded() {
    assertEquals(11, BgEtzCorrectionType.values().length);
    assertEquals("8", BgEtzCorrectionType.EIK_CHANGE.asValue());
    assertThrows(IllegalArgumentException.class, () -> BgEtzCorrectionType.of(0));
    assertThrows(IllegalArgumentException.class, () -> BgEtzCorrectionType.of(12));
  }

  // ── планът на подаването ──────────────────────────────────────────────────

  @Test
  @DisplayName("Името на файла носи ЕИК — по препоръката на приложенията")
  void fileNameCarriesTheEik() {
    assertEquals(
        "ETZ831919536.XML",
        BgEtzSubmissionPlan.fileNameFor(BgEtzEventKind.CONCLUSION, BgEtzFixture.EIK));
    assertEquals(
        "ETZ123_831919536.XML",
        BgEtzSubmissionPlan.fileNameFor(BgEtzEventKind.EMPLOYER_CHANGE, BgEtzFixture.EIK));
  }

  @Test
  @DisplayName("Празните байтове са отказ, не подаване с празен файл")
  void emptyBytesRefused() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> BgEtzSubmissionPlan.plan(BgEtzEventKind.CONCLUSION, new byte[0], "1"));
    assertTrue(e.getMessage().contains("празен"), e.getMessage());
  }

  @Test
  @DisplayName("Файл без нито един ред е отказ — схемата иска поне един <rowenum>")
  void emptyFileRefused() {
    IllegalStateException e =
        assertThrows(
            IllegalStateException.class,
            () -> new BgEtzEmitter().emit(new BgEtzFile(BgEtzEventKind.CONCLUSION)));
    assertTrue(e.getMessage().contains("rowenum"), e.getMessage());
  }
}
