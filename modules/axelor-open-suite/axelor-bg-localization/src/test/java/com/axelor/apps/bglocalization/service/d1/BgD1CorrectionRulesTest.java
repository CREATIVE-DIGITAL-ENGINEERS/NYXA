package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЧЛ. 9 · КОРЕКЦИЯТА, ЗАЛИЧАВАНЕТО И ТРИТЕ НЕЩА, КОИТО ТЕ НЕ МОГАТ.
 *
 * <p>Трите правила се проверяват поотделно, защото се нарушават поотделно и всяко има СВОЯ
 * поправка: заличаване с данни, корекция с променен ключ, и файл с две твърдения за едно лице.
 */
class BgD1CorrectionRulesTest {

  private static final BgD1Validator VALIDATOR = new BgD1Validator();

  private static BgD1Record record(String correctionCode, String personalId, String insuredCode) {
    BgD1Record r = new BgD1Record();
    r.setT1CorrectionCode(correctionCode);
    r.setT2Month("05");
    r.setT3Year("2026");
    r.setT4ObligedPersonCode("831919536");
    r.setT5PersonalId(personalId);
    r.setT51PersonalIdCode("0");
    r.setT6Surname("Петров");
    r.setT7Initials("ИГ");
    r.setT12InsuredTypeCode(insuredCode);
    return r;
  }

  private static List<String> codes(List<BgD1FindingDto> findings) {
    return findings.stream().map(BgD1FindingDto::getCode).collect(Collectors.toList());
  }

  // ═══ ЗАЛИЧАВАНЕТО — ДЕВЕТ ТОЧКИ ═══

  @Test
  @DisplayName("„З“ · деветте точки СА точно девет, преброени по указание т. 1")
  void theDeletionSetIsExactlyNineFields() {
    assertEquals(
        new TreeSet<>(Arrays.asList("1", "12", "2", "3", "4", "5", "5.1", "6", "7")),
        new TreeSet<>(BgD1CorrectionRules.DELETION_FIELDS),
        "„В тези случаи се попълват т. 1, 2, 3, 4, 5, 5.1, 6, 7 и 12 от декларацията.“");
    assertEquals(9, BgD1CorrectionRules.DELETION_FIELDS.size());
    assertTrue(
        BgD1CorrectionRules.DELETION_FIELDS.contains("1"),
        "⚠️ Т. 1 Е В СПИСЪКА, за разлика от списъка на код 28. Тук тя е ЧАСТ ОТ ТВЪРДЕНИЕТО: без"
            + " буквата „З“ записът не е заличаващ, а редовен. При код 28 наредбата изброява полетата"
            + " с ДАННИ и кодът за корекция стои извън тях.");
  }

  @Test
  @DisplayName("„З“ · запис с данни отвъд деветте точки е ГРЕШКА, не непълнота")
  void aDeletionRecordWithDataIsRejected() {
    BgD1Record deletion = record(BgD1RecordBuilder.DELETION_Z, "7501014567", "01");
    deletion.setT21InsuranceIncome(new BigDecimal("2000.00"));
    deletion.setT22DooRateEmployer(new BigDecimal("10.92"));

    List<BgD1FindingDto> findings = VALIDATOR.validate(deletion, null);
    List<String> offending =
        findings.stream()
            .filter(f -> BgD1Validator.DELETION_EXTRA.equals(f.getCode()))
            .map(BgD1FindingDto::getPoint)
            .collect(Collectors.toList());

    assertTrue(
        offending.contains("21") && offending.contains("22"),
        "⚠️ Заличаването твърди, че записът НЕ СЪЩЕСТВУВА. Запис с данни твърди обратното, при това с"
            + " валидни числа, които НАП приема. Нарушени точки: "
            + offending);
  }

  @Test
  @DisplayName("„З“ · чистият заличаващ запис минава — и НЕ ражда находките за данни")
  void aCleanDeletionRecordPassesAndSilencesTheDataRules() {
    BgD1Record deletion = record(BgD1RecordBuilder.DELETION_Z, "7501014567", "01");

    List<BgD1FindingDto> findings = VALIDATOR.validate(deletion, null);
    assertTrue(
        !codes(findings).contains(BgD1Validator.DELETION_EXTRA),
        "деветте точки са попълнени и нищо повече: " + codes(findings));
    assertTrue(
        !codes(findings).contains(BgD1Validator.BASE_WITHOUT_RATE)
            && codes(findings).stream().noneMatch(c -> c.startsWith("D1_T34_")),
        "⚠️ И ОСТАНАЛИТЕ ПРАВИЛА МЪЛЧАТ. Те питат за данни, които заличаващият запис НАРОЧНО няма —"
            + " „база без ставка“ би паднало на ВСЕКИ заличаващ запис. Валидатор, който гърми на"
            + " верен вход, приучава да не се гледа. Находки: "
            + codes(findings));
  }

  // ═══ КОРЕКЦИЯТА — ПЕТТЕ НЕПРОМЕНИМИ РЕКВИЗИТА ═══

  @Test
  @DisplayName("⚠️ чл. 9 ал. 1 т. 1 · СМЯНА НА ЕГН → „заличи и подай нова“, а НЕ тиха корекция")
  void changingThePersonalIdDemandsDeletionAndANewDeclaration() {
    BgD1Record before = record(null, "7501014567", "01");
    BgD1Record correcting = record(BgD1RecordBuilder.CORRECTION_K, "8002026789", "01");

    String refusal = BgD1CorrectionRules.refuseCorrection(correcting, before);
    assertTrue(refusal != null, "смяна на ЕГН НЕ бива да минава като корекция");
    assertTrue(
        refusal.contains("т. 5 (ЕГН/ЛН/ЛНЧ)")
            && refusal.contains("7501014567")
            && refusal.contains("8002026789"),
        "⚠️ ОТКАЗЪТ КАЗВА КОЯ ОТ ПЕТТЕ Е СМЕНЕНА И НА КАКВО — иначе човек не знае какво да поправи."
            + " Слепен ключ „05|2026|…“ би дал едно и също съобщение за пет различни грешки. Текст: "
            + refusal);
    assertTrue(
        refusal.contains("ЗАЛИЧАВАЩА") && refusal.contains("НОВА"),
        "чл. 9 ал. 3 дава ИЗХОДА, не само забраната — отказът трябва да го назове: " + refusal);
    assertTrue(
        refusal.contains("ВТОРИ запис"),
        "⚠️ И ЗАЩО НЕ Е ПЕДАНТИЗЪМ: петте реквизита са КЛЮЧЪТ, по който НАП намира предишната"
            + " декларация. Корекция с променен ЕГН създава ВТОРИ запис за второ лице, а първият"
            + " остава жив. Текст: "
            + refusal);
  }

  @Test
  @DisplayName("чл. 9 ал. 1 т. 1 · и другите четири реквизита — месец, година, ЕИК, вид осигурен")
  void theOtherFourImmutableFieldsAreGuardedToo() {
    BgD1Record before = record(null, "7501014567", "01");

    BgD1Record otherMonth = record(BgD1RecordBuilder.CORRECTION_K, "7501014567", "01");
    otherMonth.setT2Month("04");
    assertTrue(
        BgD1CorrectionRules.refuseCorrection(otherMonth, before).contains("т. 2 (месец)"),
        "смяна на месеца мести цяла декларация в друг период");

    BgD1Record otherYear = record(BgD1RecordBuilder.CORRECTION_K, "7501014567", "01");
    otherYear.setT3Year("2025");
    assertTrue(BgD1CorrectionRules.refuseCorrection(otherYear, before).contains("т. 3 (година)"));

    BgD1Record otherCompany = record(BgD1RecordBuilder.CORRECTION_K, "7501014567", "01");
    otherCompany.setT4ObligedPersonCode("123456789");
    assertTrue(
        BgD1CorrectionRules.refuseCorrection(otherCompany, before)
            .contains("т. 4 (код на задълженото лице)"));

    BgD1Record otherInsuredType = record(BgD1RecordBuilder.CORRECTION_K, "7501014567", "12");
    assertTrue(
        BgD1CorrectionRules.refuseCorrection(otherInsuredType, before)
            .contains("т. 12 (вид осигурен)"),
        "⚠️ Видът осигурен решава КОИ ТОЧКИ изобщо се попълват — сменен, той прави коригиращата"
            + " декларация запис за друг режим на осигуряване");

    assertEquals(
        5,
        BgD1CorrectionRules.IMMUTABLE_FIELDS.size(),
        "точно пет: месец, година, ЕИК, ЕГН, вид осигурен. ⚠️ Т. 5.1 НЕ е сред тях — нормата"
            + " изброява САМАТА идентификация, не описанието ѝ.");
  }

  @Test
  @DisplayName("корекция с НЕПРОМЕНЕН ключ минава — тя е за онова, което ТРЯБВА да се поправя")
  void aCorrectionWithAnUnchangedKeyPasses() {
    BgD1Record before = record(null, "7501014567", "01");
    before.setT21InsuranceIncome(new BigDecimal("2000.00"));

    BgD1Record correcting = record(BgD1RecordBuilder.CORRECTION_K, "7501014567", "01");
    correcting.setT21InsuranceIncome(new BigDecimal("2200.00"));
    // Сменя се и нещо, което НЕ е в петте — фамилията. Тя СЕ коригира.
    correcting.setT6Surname("Петрова");

    assertNull(
        BgD1CorrectionRules.refuseCorrection(correcting, before),
        "⚠️ Правило, което иска ПЪЛНО съвпадение на целия запис, би направило корекцията невъзможна"
            + " — тя съществува точно за да мени данните. Забраната е върху ПЕТТЕ реквизита.");
  }

  @Test
  @DisplayName("корекция БЕЗ предишна декларация · отделна причина, не разминаване")
  void aCorrectionWithoutAPreviousDeclarationHasItsOwnReason() {
    BgD1Record correcting = record(BgD1RecordBuilder.CORRECTION_K, "7501014567", "01");
    String refusal = BgD1CorrectionRules.refuseCorrection(correcting, null);

    assertTrue(refusal != null && refusal.contains("чл. 9 ал. 4"), refusal);
    assertTrue(
        !refusal.contains("чл. 9 ал. 1 т. 1"),
        "⚠️ ДВЕ РАЗЛИЧНИ ПРИЧИНИ С РАЗЛИЧНА ПОПРАВКА: „ключът се разминава“ иска заличаване и нова, а"
            + " „предишната не е намерена“ иска ПЪРВО да се подаде редовна. Слети в едно съобщение,"
            + " те пращат човек да прави грешното нещо. Текст: "
            + refusal);
  }

  @Test
  @DisplayName("редовен и заличаващ запис НЕ минават през пазача на корекцията")
  void onlyCorrectingRecordsAreGuarded() {
    BgD1Record before = record(null, "7501014567", "01");
    BgD1Record regular = record(null, "8002026789", "01");
    BgD1Record deletion = record(BgD1RecordBuilder.DELETION_Z, "8002026789", "01");

    assertNull(
        BgD1CorrectionRules.refuseCorrection(regular, before),
        "редовната декларация не КОРИГИРА нищо — тя е първото твърдение");
    assertNull(
        BgD1CorrectionRules.refuseCorrection(deletion, before),
        "⚠️ И ЗАЛИЧАВАНЕТО НЕ СЕ ПАЗИ, защото ТО Е ИЗХОДЪТ по чл. 9 ал. 3. Пазено като корекция, то"
            + " би било забранено точно когато е предписано — тоест правилото щеше да блокира"
            + " собственото си решение.");
  }

  // ═══ ФАЙЛЪТ — ЕДНО ТВЪРДЕНИЕ ЗА ЛИЦЕ ═══

  @Test
  @DisplayName("⚠️ един файл · код 0 и код 1 за СЪЩОТО лице се отказват")
  void regularAndCorrectingForTheSamePersonAreRefused() {
    String refusal =
        BgD1CorrectionRules.refuseMixedCodes(
            Arrays.asList(
                record(null, "7501014567", "01"),
                record(BgD1RecordBuilder.CORRECTION_K, "7501014567", "01")));

    assertTrue(refusal != null && refusal.contains("7501014567"), String.valueOf(refusal));
    assertTrue(
        refusal.contains("не е определен от файла"),
        "двете са две твърдения за ЕДИН запис, а редът, в който НАП ще ги приложи, не е определен: "
            + refusal);
  }

  @Test
  @DisplayName("един файл · код 0 и код 8 за същото лице СЕ ДОПУСКАТ — това е чл. 9 ал. 3")
  void regularAndDeletionForTheSamePersonAreAllowed() {
    assertNull(
        BgD1CorrectionRules.refuseMixedCodes(
            Arrays.asList(
                record(BgD1RecordBuilder.DELETION_Z, "7501014567", "01"),
                record(null, "7501014567", "01"))),
        "⚠️ Заличаване плюс нова декларация е ТОЧНО предписаният от чл. 9 ал. 3 път. Двете твърдения"
            + " не си противоречат — те се отнасят до различни състояния на записа.");
  }

  @Test
  @DisplayName("един файл · код 0 и код 1 за РАЗЛИЧНИ лица се допускат")
  void regularAndCorrectingForDifferentPeopleAreAllowed() {
    assertNull(
        BgD1CorrectionRules.refuseMixedCodes(
            Arrays.asList(
                record(null, "7501014567", "01"),
                record(BgD1RecordBuilder.CORRECTION_K, "8002026789", "01"))),
        "забраната е ЗА СЪЩОТО ЛИЦЕ. Разширена до целия файл, тя би направила невъзможно едно"
            + " дружество да поправи един фиш, без да преподаде всички останали.");
    assertNull(BgD1CorrectionRules.refuseMixedCodes(null), "и null не гърми");
    assertNull(BgD1CorrectionRules.refuseMixedCodes(Collections.emptyList()));
  }

  @Test
  @DisplayName("трите състояния на т. 1 се четат ПО КОДОВА ТОЧКА, не по изглед")
  void theThreeStatesAreReadByCodePoint() {
    assertTrue(BgD1CorrectionRules.isRegular(record(null, "7501014567", "01")));
    assertTrue(BgD1CorrectionRules.isRegular(record("", "7501014567", "01")), "празното Е редовен");
    assertTrue(BgD1CorrectionRules.isCorrection(record(BgD1RecordBuilder.CORRECTION_K, "1", "01")));
    assertTrue(BgD1CorrectionRules.isDeletion(record(BgD1RecordBuilder.DELETION_Z, "1", "01")));

    // ⚠️ ЛАТИНСКИТЕ K И Z СЕ ИЗПИСВАТ ЕДНАКВО с кирилските и НЕ се разпознават.
    assertTrue(
        !BgD1CorrectionRules.isCorrection(record("K", "1", "01")),
        "латинското „K“ (U+004B) е ДРУГ низ — минава през всеки визуален преглед и през всеки diff");
    assertTrue(
        !BgD1CorrectionRules.isDeletion(record("Z", "1", "01")),
        "същото за латинското „Z“ (U+005A)");
    assertTrue(
        BgD1CorrectionRules.isRegular(record(null, "1", "01"))
            && !BgD1CorrectionRules.isRegular(record(BgD1RecordBuilder.CORRECTION_K, "1", "01")),
        "и трите състояния се изключват взаимно");
  }
}
