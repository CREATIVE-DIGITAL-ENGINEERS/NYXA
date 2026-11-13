package com.axelor.apps.bglocalization.service.vat.adapter;

import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.field;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.formatAmount;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.formatDate;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.padLeft;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.padRight;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.zeroPadLeft;

import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPurchaseRowDto;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Builds the purchase diary <b>POKUPKI.TXT</b> — fixed-width, byte-verified against the accepted
 * POKUPKI_1.txt golden fixture (спецификацията на НАП за файловете по ДДС §2 and Приложение №12
 * полета 03-XX). Record = 274 chars, no delimiter, CP1251 + CRLF on write. Mirror of {@code
 * BgVatSalesAdapter}; works on DTOs only.
 *
 * <p>The header (0–166) is identical to the sales diary. The value area is 7 columns к.09–к.15 and
 * there is NO summing column — к.09 is reported directly.
 *
 * <p><b>Not implemented here (needs sign-off):</b> the full/partial/no-tax-credit routing (чл.73
 * proportion) is tax logic, not file layout. This adapter accepts rows whose per-column amounts are
 * already filled and lays them out; {@link #buildRows} only assembles the identification fields.
 */
@Singleton
public class BgVatPurchaseAdapter {

  // ---------------------------------------------------------------------------
  // Physical layout — FIXED-WIDTH (Приложение №12, полета 03-XX). Record = 274 chars.
  // ---------------------------------------------------------------------------

  private static final int W_COMPANY_VAT = 15; // [0:15]   03-00 ИН по ДДС
  private static final int W_PERIOD = 6; // [15:21]  03-01 отчетен период ГГГГММ
  private static final int W_BRANCH = 4; // [21:25]  03-02 клон (left)
  private static final int W_SEQUENCE = 15; // [25:40]  03-03 пореден № (right)
  private static final int W_DOC_TYPE = 2; // [40:42]  03-04 вид документа
  private static final int W_DOC_NUMBER = 20; // [42:62]  03-06 № док — Символен(20), ляво, дословно
  private static final int W_DATE = 10; // [62:72]  03-07 дата
  private static final int W_COUNTERPARTY_VAT = 15; // [72:87]  03-08 ИН на доставчика
  private static final int W_COUNTERPARTY_NAME = 50; // [87:137] 03-09 име на доставчика
  private static final int W_SUBJECT = 30; // [137:167] 03-10 предмет
  private static final int W_AMOUNT = 15; // each of к.09–к.15
  private static final int W_SUPPLY_TYPE_163A = 2; // [272:274] поле 03-45 доставка по чл.163а/167а

  /** Exact record length; asserted after assembly so a layout bug fails loudly, not silently. */
  static final int RECORD_LENGTH = 274;

  /** Period identifier format (ГГГГММ). */
  private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

  /** The 7 value columns к.09–к.15 in physical file order. */
  private static final List<BgVatPurchaseColumn> COLUMN_ORDER =
      Arrays.asList(
          BgVatPurchaseColumn.K09_BASE_NO_CREDIT,
          BgVatPurchaseColumn.K10_BASE_FULL_CREDIT,
          BgVatPurchaseColumn.K11_VAT_FULL_CREDIT,
          BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT,
          BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT,
          BgVatPurchaseColumn.K14_ANNUAL_ADJUSTMENT,
          BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ);

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /** Serialize already-column-filled purchase rows into POKUPKI.TXT data lines. */
  public List<String> buildLines(List<BgVatPurchaseRowDto> rows) {
    List<String> lines = new ArrayList<>();
    if (rows == null) {
      return lines;
    }
    for (BgVatPurchaseRowDto row : rows) {
      lines.add(formatRow(row));
    }
    return lines;
  }

  /**
   * Assemble the identification fields of a purchase row from each document. Amounts are left
   * empty: the per-column credit routing (пълен/частичен/без данъчен кредит по чл.73) is deferred
   * tax logic and must be filled in before output.
   *
   * <p>TODO (accountant/CTO sign-off): implement the чл.73 proportion routing that populates
   * к.09–к.15, then wire it here.
   */
  public List<BgVatPurchaseRowDto> buildRows(
      List<BgVatDocumentDto> purchaseDocuments, BgVatExportRequestDto request) {
    List<BgVatPurchaseRowDto> rows = new ArrayList<>();
    if (purchaseDocuments == null) {
      return rows;
    }
    String periodCode = periodCode(request);
    int sequence = 0;
    for (BgVatDocumentDto document : purchaseDocuments) {
      sequence++;
      rows.add(toRow(document, request, periodCode, sequence));
    }
    return rows;
  }

  protected BgVatPurchaseRowDto toRow(
      BgVatDocumentDto document, BgVatExportRequestDto request, String periodCode, int sequence) {
    BgVatPurchaseRowDto row = new BgVatPurchaseRowDto();
    row.setCompanyVatNumber(request != null ? request.getCompanyVatNumber() : null);
    row.setPeriodCode(periodCode);
    row.setBranch(
        request != null && request.getBranchNumber() != null
            ? request.getBranchNumber()
            : BgVatFieldFormatter.DEFAULT_BRANCH);
    row.setSequence(sequence);
    row.setNapDocumentType(document.getNapDocumentType());
    row.setDocumentNumber(document.getDocumentNumber());
    row.setDocumentDate(document.getDocumentDate());
    // 03-08: ИН за дневника (ДДС номер, ИН по § 1 или „999999999999999“), не само ДДС номерът.
    row.setCounterpartyVatNumber(document.getCounterpartyDiaryId());
    row.setCounterpartyName(document.getCounterpartyName());
    // 03-10: никога празно — portal.nra.bg отказва файла (BgVatSubject).
    row.setSubject(BgVatSubject.orFallback(document.getSubject()));
    // Amounts (к.09–к.15) and чл.163а code intentionally NOT populated here — see class note.
    return row;
  }

  // ---------------------------------------------------------------------------
  // Physical layout — ISOLATED. 274-char fixed-width record.
  // ---------------------------------------------------------------------------

  protected String formatRow(BgVatPurchaseRowDto row) {
    StringBuilder sb = new StringBuilder(RECORD_LENGTH);
    // Identification header 03-00…03-10 (identical to the sales diary).
    sb.append(
        padRight(field(row.getCompanyVatNumber()), W_COMPANY_VAT)); // [0:15]   03-00 ИН по ДДС
    sb.append(padRight(field(row.getPeriodCode()), W_PERIOD)); // [15:21]  03-01 период
    sb.append(padRight(field(row.getBranch()), W_BRANCH)); // [21:25]  03-02 клон (left)
    sb.append(padLeft(Integer.toString(row.getSequence()), W_SEQUENCE)); // [25:40]  03-03 № (right)
    sb.append(zeroPadLeft(field(row.getNapDocumentType()), W_DOC_TYPE)); // [40:42]  03-04 вид док
    sb.append(
        padRight(field(row.getDocumentNumber()), W_DOC_NUMBER)); // [42:62]  03-06 № (дословно)
    sb.append(padRight(formatDate(row.getDocumentDate()), W_DATE)); // [62:72]  03-07 дата
    sb.append(padRight(field(row.getCounterpartyVatNumber()), W_COUNTERPARTY_VAT)); // [72:87] 03-08
    sb.append(
        padRight(field(row.getCounterpartyName()), W_COUNTERPARTY_NAME)); // [87:137] 03-09 име
    sb.append(padRight(field(row.getSubject()), W_SUBJECT)); // [137:167] 03-10 предмет
    // Value columns к.09–к.15: 7 × 15, right-aligned, space-padded, dot, 2 decimals (може
    // отрицателна).
    for (BgVatPurchaseColumn column : COLUMN_ORDER) {
      sb.append(padLeft(formatAmount(row.getAmount(column)), W_AMOUNT)); // [167:272]
    }
    sb.append(padRight(field(row.getSupplyTypeArt163a()), W_SUPPLY_TYPE_163A)); // [272:274] 03-45

    String record = sb.toString();
    if (record.length() != RECORD_LENGTH) {
      throw new IllegalStateException(
          "POKUPKI record must be "
              + RECORD_LENGTH
              + " chars but was "
              + record.length()
              + " (row seq "
              + row.getSequence()
              + ")");
    }
    return record;
  }

  // ---------------------------------------------------------------------------
  // Helpers
  // ---------------------------------------------------------------------------

  protected String periodCode(BgVatExportRequestDto request) {
    LocalDate basis = request != null ? request.getPeriodTo() : null;
    if (basis == null && request != null) {
      basis = request.getPeriodFrom();
    }
    return basis == null ? "" : basis.format(PERIOD_FORMAT);
  }
}
