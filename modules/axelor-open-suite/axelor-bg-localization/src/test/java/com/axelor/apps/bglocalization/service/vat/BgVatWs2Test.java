package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure (no DB / no Guice) unit tests for the WS-2 guarantees the team asked to lock in:
 * ACCOUNTED-only collection (no DAYBOOK), missing NAP document type is a blocking error, and the
 * file writer produces CP1251 + CRLF without a UTF-8 BOM.
 */
class BgVatWs2Test {

  @Test
  void collectorUsesAccountedOnly_excludesDaybook() {
    List<Integer> statuses = new BgVatDocumentCollector(null).acceptedMoveStatuses();
    assertEquals(Collections.singletonList(MoveRepository.STATUS_ACCOUNTED), statuses);
    assertFalse(statuses.contains(MoveRepository.STATUS_DAYBOOK));
  }

  @Test
  void missingNapDocumentType_isBlockingError() {
    BgVatDocumentDto doc = new BgVatDocumentDto();
    doc.setDocumentNumber("INV-1");
    doc.setDocumentDate(LocalDate.of(2026, 6, 15));
    doc.setCounterpartyVatNumber("BG123456789");
    // napDocumentType intentionally left null

    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validateDocuments(Arrays.asList(doc), new BgVatExportRequestDto());

    assertTrue(
        findings.stream()
            .anyMatch(
                f ->
                    BgVatValidator.NAP_DOCUMENT_TYPE_MISSING_CODE.equals(f.getCode())
                        && BgVatValidationErrorDto.SEVERITY_ERROR.equals(f.getSeverity())),
        "a document without napDocumentTypeSelect must raise a blocking ERROR");
  }

  @Test
  void presentNapDocumentType_producesNoNapError() {
    BgVatDocumentDto doc = new BgVatDocumentDto();
    doc.setDocumentNumber("INV-2");
    doc.setDocumentDate(LocalDate.of(2026, 6, 15));
    doc.setCounterpartyVatNumber("BG123456789");
    doc.setNapDocumentType("01");

    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validateDocuments(Arrays.asList(doc), new BgVatExportRequestDto());

    assertFalse(
        findings.stream()
            .anyMatch(f -> BgVatValidator.NAP_DOCUMENT_TYPE_MISSING_CODE.equals(f.getCode())));
  }

  /** ППЗДДС Прил. № 12: „Номер на документ“ се попълва задължително — за всеки запис. */
  @Test
  void missingDocumentNumber_isBlockingError_inBothGates() {
    for (String blank : new String[] {null, "", "   "}) {
      BgVatDocumentDto doc = new BgVatDocumentDto();
      doc.setDocumentNumber(blank);
      doc.setDocumentDate(LocalDate.of(2026, 6, 15));
      doc.setCounterpartyVatNumber("BG123456789");
      doc.setNapDocumentType("01");

      List<BgVatValidationErrorDto> findings =
          new BgVatValidator().validateDocuments(Arrays.asList(doc), new BgVatExportRequestDto());
      List<BgVatValidationErrorDto> gate =
          new BgVatValidator()
              .validateSalesForProduction(Arrays.asList(doc), new BgVatExportRequestDto());

      for (List<BgVatValidationErrorDto> list : Arrays.asList(findings, gate)) {
        assertTrue(
            list.stream()
                .anyMatch(
                    f ->
                        BgVatValidator.DOCUMENT_NUMBER_MISSING_CODE.equals(f.getCode())
                            && BgVatValidationErrorDto.SEVERITY_ERROR.equals(f.getSeverity())),
            "„" + blank + "“: " + list);
      }
    }
    // Портата го повтаря само за да спре файла — validateDocuments вече го е изброил.
    assertTrue(BgVatValidator.isDocumentLevel(BgVatValidator.DOCUMENT_NUMBER_MISSING_CODE));
  }

  @Test
  void presentDocumentNumber_raisesNoNumberError() {
    BgVatDocumentDto doc = new BgVatDocumentDto();
    doc.setDocumentNumber("0000000004");
    doc.setNapDocumentType("01");

    assertFalse(
        new BgVatValidator()
            .validateDocuments(Arrays.asList(doc), new BgVatExportRequestDto()).stream()
                .anyMatch(f -> BgVatValidator.DOCUMENT_NUMBER_MISSING_CODE.equals(f.getCode())));
  }

  /** Протокол без собствен номер вече носи по-точната грешка — без втора за същото. */
  @Test
  void protocolWithoutNumber_isNotReportedTwice() {
    BgVatDocumentDto doc = new BgVatDocumentDto();
    doc.setNapDocumentType("09");
    doc.addError(BgVatValidator.PROTOCOL_NUMBER_MISSING_CODE, "ДДС протоколът няма номер");

    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validateDocuments(Arrays.asList(doc), new BgVatExportRequestDto());

    assertEquals(
        1,
        findings.stream()
            .filter(
                f ->
                    BgVatValidator.PROTOCOL_NUMBER_MISSING_CODE.equals(f.getCode())
                        || BgVatValidator.DOCUMENT_NUMBER_MISSING_CODE.equals(f.getCode()))
            .count(),
        findings.toString());
  }

  /**
   * Складиране до поискване (чл. 15а) е във VIES (ППЗДДС чл. 117 ал. 2 т. 12–16), но Nyxa раздела
   * не съставя — ПРЕДУПРЕЖДЕНИЕ, че се попълва ръчно (решение от 27.10.2026), не мълчалив пропуск.
   */
  @Test
  void callOffStock_isAWarning_toFillViesByHand() {
    for (String code : new String[] {"41", "43", "46", "48", " 41 "}) {
      BgVatDocumentDto doc = new BgVatDocumentDto();
      doc.setDocumentNumber("0000000005");
      doc.setNapDocumentType("01");
      doc.setSupplyTypeArt163a(code);

      assertTrue(
          new BgVatValidator()
              .validateDocuments(Arrays.asList(doc), new BgVatExportRequestDto()).stream()
                  .anyMatch(
                      f ->
                          BgVatValidator.VIES_CALL_OFF_STOCK_MANUAL_CODE.equals(f.getCode())
                              && BgVatValidationErrorDto.SEVERITY_WARNING.equals(f.getSeverity())),
          code);
    }
    for (String code : new String[] {null, "01", "02", "03", "07", "08"}) {
      BgVatDocumentDto doc = new BgVatDocumentDto();
      doc.setDocumentNumber("0000000005");
      doc.setNapDocumentType("01");
      doc.setSupplyTypeArt163a(code);

      assertFalse(
          new BgVatValidator()
              .validateDocuments(Arrays.asList(doc), new BgVatExportRequestDto()).stream()
                  .anyMatch(
                      f -> BgVatValidator.VIES_CALL_OFF_STOCK_MANUAL_CODE.equals(f.getCode())),
          code);
    }
  }

  /**
   * „BG“ + ЕГН като ДДС номер на контрагента е ПРЕДУПРЕЖДЕНИЕ: вероятно ЕГН или стар ДДС номер на
   * физическо лице (от 01.01.2024 — „BG“ + служебният номер по чл. 94 ал. 1 ЗДДС).
   */
  @Test
  void aVatNumberThatIsBgPlusAnEgn_isAWarning_notAnError() {
    BgVatDocumentDto doc = new BgVatDocumentDto();
    doc.setDocumentNumber("0000000006");
    doc.setNapDocumentType("01");
    doc.setCounterpartyVatNumber("BG7501010010");

    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validateDocuments(Arrays.asList(doc), new BgVatExportRequestDto());

    List<BgVatValidationErrorDto> flagged = new java.util.ArrayList<>();
    for (BgVatValidationErrorDto f : findings) {
      if (BgVatValidator.COUNTERPARTY_VAT_NUMBER_LIKE_EGN_CODE.equals(f.getCode())) {
        flagged.add(f);
      }
    }
    assertEquals(1, flagged.size(), findings.toString());
    assertEquals(BgVatValidationErrorDto.SEVERITY_WARNING, flagged.get(0).getSeverity());
    assertTrue(flagged.get(0).getMessage().contains("да се провери"));

    doc.setCounterpartyVatNumber("BG123456786");
    assertFalse(
        new BgVatValidator()
            .validateDocuments(Arrays.asList(doc), new BgVatExportRequestDto()).stream()
                .anyMatch(
                    f -> BgVatValidator.COUNTERPARTY_VAT_NUMBER_LIKE_EGN_CODE.equals(f.getCode())));
  }

  @Test
  void fileWriterIsCp1251AndCrlfWithoutBom() throws Exception {
    BgVatFileWriter writer = new BgVatFileWriter();

    // UTF-8 path: must NOT start with a BOM, and must end with CRLF.
    byte[] utf8 = writer.toBytes(Arrays.asList("тест"), BgVatFileWriter.ENCODING_UTF8);
    boolean hasBom =
        utf8.length >= 3
            && (utf8[0] & 0xFF) == 0xEF
            && (utf8[1] & 0xFF) == 0xBB
            && (utf8[2] & 0xFF) == 0xBF;
    assertFalse(hasBom, "UTF-8 output must not start with a BOM");
    assertTrue(
        new String(utf8, StandardCharsets.UTF_8).endsWith("\r\n"), "line must end with CRLF");

    // CP1251 path: Cyrillic 'А' = 0xC0, 'Б' = 0xC1, then CRLF (0x0D 0x0A).
    byte[] cp = writer.toBytes(Arrays.asList("АБ"), BgVatFileWriter.ENCODING_CP1251);
    assertEquals(0xC0, cp[0] & 0xFF);
    assertEquals(0xC1, cp[1] & 0xFF);
    assertEquals(0x0D, cp[2] & 0xFF);
    assertEquals(0x0A, cp[3] & 0xFF);

    assertEquals(Charset.forName("windows-1251"), writer.resolveCharset("cp1251"));
    assertEquals(StandardCharsets.UTF_8, writer.resolveCharset("utf8"));
  }
}
