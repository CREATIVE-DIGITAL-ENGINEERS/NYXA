package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Документ, отразен в по-късен период: предупреждението на валидатора и аванс, приспаднат преди да
 * е отразен (Р11) — без база.
 */
class BgVatReportedLateTest {

  private static BgVatExportRequestDto june() {
    BgVatExportRequestDto request = new BgVatExportRequestDto();
    request.setPeriodFrom(LocalDate.of(2026, 6, 1));
    request.setPeriodTo(LocalDate.of(2026, 6, 30));
    return request;
  }

  private static BgVatDocumentDto document(LocalDate date, String reportingPeriod, int operation) {
    BgVatDocumentDto document = new BgVatDocumentDto();
    document.setDocumentNumber("0000000123");
    document.setDocumentDate(date);
    document.setReportingPeriod(reportingPeriod);
    document.setOperationType(operation);
    return document;
  }

  private static List<BgVatValidationErrorDto> findings(BgVatDocumentDto document) {
    return new BgVatValidator()
        .validateDocuments(List.of(document), june()).stream()
            .filter(
                f ->
                    f.getCode().equals(BgVatValidator.DOCUMENT_REPORTED_LATE_CODE)
                        || f.getCode().equals("DATE_PERIOD_INCONSISTENT"))
            .collect(Collectors.toList());
  }

  @Test
  @DisplayName(
      "Пропусната продажба от май, отразена в юни — DOCUMENT_REPORTED_LATE, чл. 126 ал. 3 т. 1")
  void пропусната_продажба() {
    List<BgVatValidationErrorDto> findings =
        findings(
            document(
                LocalDate.of(2026, 5, 28), "202606", InvoiceRepository.OPERATION_TYPE_CLIENT_SALE));
    assertEquals(1, findings.size(), findings.toString());
    assertEquals(BgVatValidator.DOCUMENT_REPORTED_LATE_CODE, findings.get(0).getCode());
    assertTrue(findings.get(0).getMessage().contains("05.2026"));
    assertTrue(findings.get(0).getMessage().contains("чл. 126 ал. 3 т. 1"));
    assertFalse(findings.get(0).getMessage().contains("чл. 72"));
  }

  @Test
  @DisplayName("Покупка с отложен кредит — същото предупреждение, с чл. 72 ал. 1 и чл. 124 ал. 4")
  void отложена_покупка() {
    List<BgVatValidationErrorDto> findings =
        findings(
            document(
                LocalDate.of(2026, 3, 10),
                "202606",
                InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE));
    assertEquals(BgVatValidator.DOCUMENT_REPORTED_LATE_CODE, findings.get(0).getCode());
    assertTrue(findings.get(0).getMessage().contains("чл. 72 ал. 1"));
  }

  @Test
  @DisplayName("Без изричен период дата извън периода — пак DATE_PERIOD_INCONSISTENT")
  void без_период() {
    List<BgVatValidationErrorDto> findings =
        findings(
            document(
                LocalDate.of(2026, 5, 28), null, InvoiceRepository.OPERATION_TYPE_CLIENT_SALE));
    assertEquals(1, findings.size());
    assertEquals("DATE_PERIOD_INCONSISTENT", findings.get(0).getCode());
  }

  /**
   * Находката в прегледа на декларацията е на български и с дати ДД.ММ.ГГГГ, не „Document date
   * 2026-05-28 is outside the selected period.“
   */
  @Test
  @DisplayName("DATE_PERIOD_INCONSISTENT е на български, с датите и периода като ДД.ММ.ГГГГ")
  void дата_извън_периода_на_български() {
    List<BgVatValidationErrorDto> findings =
        findings(
            document(
                LocalDate.of(2026, 5, 28), null, InvoiceRepository.OPERATION_TYPE_CLIENT_SALE));
    assertEquals(1, findings.size(), findings.toString());
    assertEquals(
        "Датата на документа 28.05.2026 е извън избрания период 01.06.2026 – 30.06.2026."
            + " Проверете датата или задайте „Период на отразяване по ЗДДС“ на документа.",
        findings.get(0).getMessage());
  }

  // ─────────────── Р11: авансът, приспаднат преди да е отразен ───────────────

  private static Invoice invoice(String number, LocalDate date, String reportingPeriod) {
    Invoice invoice = new Invoice();
    invoice.setInvoiceId(number);
    invoice.setInvoiceDate(date);
    invoice.setBgVatReportingPeriod(reportingPeriod);
    return invoice;
  }

  private static Invoice fin(Invoice advance, boolean deduction) {
    Invoice fin = invoice("0000000200", LocalDate.of(2026, 6, 10), null);
    InvoiceLine line = new InvoiceLine();
    line.setBgAdvanceDeduction(deduction);
    line.setBgAdvanceInvoice(advance);
    fin.setInvoiceLineList(new ArrayList<>(List.of(line)));
    return fin;
  }

  @Test
  @DisplayName(
      "Пропусната авансова от май, отразена в юли, приспадната от юнската — предупреждение")
  void приспаднат_преди_отразяването() {
    Invoice advance = invoice("0000000100", LocalDate.of(2026, 5, 20), "202607");
    List<String> early = BgVatAdvanceService.deductedBeforeReported(fin(advance, true));
    assertEquals(1, early.size());
    assertTrue(early.get(0).contains("0000000100 от 20.05.2026 (07.2026)"), early.get(0));
    assertTrue(early.get(0).contains("0000000200 от 10.06.2026 (06.2026)"), early.get(0));
  }

  @Test
  @DisplayName("Авансова в своя период преди окончателната — нищо; ред без полето — нищо")
  void в_ред() {
    Invoice advance = invoice("0000000100", LocalDate.of(2026, 5, 20), null);
    assertTrue(BgVatAdvanceService.deductedBeforeReported(fin(advance, true)).isEmpty());
    Invoice late = invoice("0000000100", LocalDate.of(2026, 5, 20), "202607");
    assertTrue(BgVatAdvanceService.deductedBeforeReported(fin(late, false)).isEmpty());
    // Същият период — не е „по-късно“.
    Invoice same = invoice("0000000100", LocalDate.of(2026, 5, 20), "202606");
    assertFalse(BgVatAdvanceService.reportedLater(same, fin(same, true)));
  }
}
