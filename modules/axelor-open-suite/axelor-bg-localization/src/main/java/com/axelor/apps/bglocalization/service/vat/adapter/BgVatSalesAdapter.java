package com.axelor.apps.bglocalization.service.vat.adapter;

import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.field;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.formatAmount;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.formatDate;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.padLeft;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.padRight;
import static com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter.zeroPadLeft;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesTaxCodeMapping.ColumnTarget;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the sales diary <b>PRODAGBI.TXT</b> from SALES document DTOs. Works on DTOs only — never
 * on Axelor entities — so the legal formatting layer stays isolated from the ORM.
 *
 * <p>WS-3. Each sales document becomes one diary row: its per-rate/tax-code amount buckets are
 * routed to the NAP sales-journal columns via {@link BgVatSalesTaxCodeMapping} (kept authoritative
 * from the vendored COA config, not guessed), and the columns the collector already filled directly
 * ({@link BgVatDocumentDto#getSalesAmounts()} — protocols on a manual move) are added as they are.
 * Row totals кл.09/кл.10 are the sums of the routed base / VAT columns.
 *
 * <p><b>Isolated legal decision.</b> The <i>physical record layout</i> (field offsets, fixed
 * widths, date &amp; amount formatting) is a 424-char fixed-width record byte-verified against the
 * accepted PRODAGBI.TXT golden fixture (see the NAP VAT file specification §1/§3 and {@code
 * BgVatSalesGoldenTest}). It lives entirely in {@link #formatRow(BgVatSalesRowDto)} and the width
 * constants below, so a single edit realigns the file. The column <i>semantics</i> (which amount →
 * which кл.) are independently verifiable in the dry-run preview.
 */
@Singleton
public class BgVatSalesAdapter {

  // ---------------------------------------------------------------------------
  // Physical layout — FIXED-WIDTH, byte-verified against the accepted PRODAGBI.TXT golden fixture
  // (the NAP VAT file specification §1/§3). Record = 424 chars, no delimiter, CP1251 + CRLF on
  // write.
  // ---------------------------------------------------------------------------

  /** Period identifier format (ГГГГММ). */
  private static final DateTimeFormatter PERIOD_FORMAT = DateTimeFormatter.ofPattern("yyyyMM");

  // Fixed field widths (0-based [start:end) offsets in the NAP VAT file specification §1).
  private static final int W_COMPANY_VAT = 15; // [0:15]
  private static final int W_PERIOD = 6; // [15:21]
  private static final int W_BRANCH = 4; // [21:25]  клон (left)   ─┐ zone [21:40]
  private static final int W_SEQUENCE = 15; // [25:40] пореден № (right) ─┘
  private static final int W_DOC_TYPE = 2; // [40:42]  кл.03
  // Поле 02-05 „Номер на документа" — Символен(20), ляво, дословно (no zero padding, no separate
  // reserved field: the trailing blanks are simply the unused right part of this 20-char field).
  private static final int W_DOC_NUMBER = 20; // [42:62]  кл.04
  private static final int W_DATE = 10; // [62:72]  кл.05
  private static final int W_COUNTERPARTY_VAT = 15; // [72:87]  кл.06
  private static final int W_COUNTERPARTY_NAME = 50; // [87:137] кл.07
  private static final int W_SUBJECT = 30; // [137:167] кл.08 (+ к.8а code area — blank for now)
  private static final int W_AMOUNT = 15; // each of кл.09–кл.25
  private static final int W_SUPPLY_TYPE_163A = 2; // [422:424] поле 02-27 доставка по чл.163а/167а

  /** Exact record length; asserted after assembly so a layout bug fails loudly, not silently. */
  static final int RECORD_LENGTH = 424;

  /**
   * Base columns summed into кл.09 (поле 02-10, „Общ размер на ДО за облагане с ДДС"). Only the
   * columns that make up the taxable base total — к.22/к.23/к.24/к.25 (услуги в друга ДЧ, чл.69
   * ал.2, освободени, тристранни) are reported separately and are NOT part of к.09.
   */
  private static final Set<BgVatSalesColumn> BASE_COLUMNS =
      EnumSet.of(
          BgVatSalesColumn.K11_BASE_20,
          BgVatSalesColumn.K13_BASE_ICA,
          BgVatSalesColumn.K14_BASE_RC,
          BgVatSalesColumn.K17_BASE_9,
          BgVatSalesColumn.K19_BASE_ZERO_CH3,
          BgVatSalesColumn.K20_BASE_ICS,
          BgVatSalesColumn.K21_BASE_ART140_146_173);

  /** VAT columns summed into кл.10 (всичко начислен ДДС). */
  private static final Set<BgVatSalesColumn> VAT_COLUMNS =
      EnumSet.of(
          BgVatSalesColumn.K12_VAT_20,
          BgVatSalesColumn.K15_VAT_ICA_RC,
          BgVatSalesColumn.K16_VAT_SELF_SUPPLY,
          BgVatSalesColumn.K18_VAT_9);

  /**
   * The 17 value columns кл.09–кл.25 in physical file order (after the кл.01–кл.08 identification
   * fields). Matches the golden fixture's value area {@code [167:422]} = 17 × 15.
   */
  private static final List<BgVatSalesColumn> COLUMN_ORDER =
      Arrays.asList(
          BgVatSalesColumn.K09_TOTAL_BASE,
          BgVatSalesColumn.K10_TOTAL_VAT,
          BgVatSalesColumn.K11_BASE_20,
          BgVatSalesColumn.K12_VAT_20,
          BgVatSalesColumn.K13_BASE_ICA,
          BgVatSalesColumn.K14_BASE_RC,
          BgVatSalesColumn.K15_VAT_ICA_RC,
          BgVatSalesColumn.K16_VAT_SELF_SUPPLY,
          BgVatSalesColumn.K17_BASE_9,
          BgVatSalesColumn.K18_VAT_9,
          BgVatSalesColumn.K19_BASE_ZERO_CH3,
          BgVatSalesColumn.K20_BASE_ICS,
          BgVatSalesColumn.K21_BASE_ART140_146_173,
          BgVatSalesColumn.K22_BASE_EU_SERVICES,
          BgVatSalesColumn.K23_BASE_ART69_2,
          BgVatSalesColumn.K24_BASE_EXEMPT,
          BgVatSalesColumn.K25_BASE_TRIANGULAR);

  /** NAP document type for a credit note (кредитно известие) — its amounts are negated. */
  private static final String NAP_DOC_TYPE_CREDIT_NOTE = "03";

  // Invoice.operationTypeSelect values (see BgVatDocumentDto.operationType).
  private static final int OPERATION_CLIENT_SALE = 3;
  private static final int OPERATION_CLIENT_REFUND = 4;

  // ---------------------------------------------------------------------------
  // Public API
  // ---------------------------------------------------------------------------

  /** Serialize the given SALES documents into PRODAGBI.TXT data lines. */
  public List<String> buildLines(
      List<BgVatDocumentDto> salesDocuments, BgVatExportRequestDto request) {
    List<String> lines = new ArrayList<>();
    for (BgVatSalesRowDto row : buildRows(salesDocuments, request)) {
      lines.add(formatRow(row));
    }
    return lines;
  }

  /** Map each sales document to a structured diary row (used for both file output and preview). */
  public List<BgVatSalesRowDto> buildRows(
      List<BgVatDocumentDto> salesDocuments, BgVatExportRequestDto request) {
    List<BgVatSalesRowDto> rows = new ArrayList<>();
    if (salesDocuments == null) {
      return rows;
    }
    String periodCode = periodCode(request);
    int sequence = 0;
    for (BgVatDocumentDto document : salesDocuments) {
      sequence++;
      rows.add(toRow(document, request, periodCode, sequence));
    }
    return rows;
  }

  protected BgVatSalesRowDto toRow(
      BgVatDocumentDto document, BgVatExportRequestDto request, String periodCode, int sequence) {
    BgVatSalesRowDto row = new BgVatSalesRowDto();
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
    // 02-07: ИН за дневника (ДДС номер, ИН по § 1 или „999999999999999“), не само ДДС номерът.
    row.setCounterpartyVatNumber(document.getCounterpartyDiaryId());
    row.setCounterpartyName(document.getCounterpartyName());
    // 02-08: никога празно — portal.nra.bg отказва файла (BgVatSubject).
    row.setSubject(BgVatSubject.orFallback(document.getSubject()));

    boolean creditNote = isCreditNote(document);
    for (BgVatAmountDto amount : document.getAmounts()) {
      Optional<ColumnTarget> target = BgVatSalesTaxCodeMapping.forTaxCode(amount.getTaxCode());
      if (!target.isPresent()) {
        // Unmapped tax code — the validator blocks production for this; skip routing here so the
        // preview still renders the rest of the row.
        continue;
      }
      ColumnTarget columns = target.get();
      BigDecimal base = sign(amount.getBase(), creditNote);
      BigDecimal vat = sign(amount.getVatAmount(), creditNote);
      row.addAmount(columns.getBaseColumn(), base);
      if (columns.getVatColumn() != null) {
        row.addAmount(columns.getVatColumn(), vat);
      }
    }

    // Колоните, които колекторът е попълнил направо — протоколите по ръчна статия (ВОП, чл. 82,
    // чл. 163а, чл. 79 ал. 4). Знакът им идва от сметките, затова кредитното известие не ги обръща.
    //
    // ⚠️ ТЕ ВЛИЗАТ ПРЕДИ ТОТАЛИТЕ. к.09/к.10 се смятат от реда по-долу, а DEKLAR и VIES четат
    // кл.01/кл.20 и колоните само от реда. Пропуснати тук, протоколите излизаха в PRODAGBI с нули,
    // а кл.20 не броеше начисления данък, докато кл.41 броеше кредита: самоначислен ВОП 100/20
    // даваше кл.60 = 20 вместо нетна нула.
    for (Map.Entry<BgVatSalesColumn, BigDecimal> direct : document.getSalesAmounts().entrySet()) {
      row.addAmount(direct.getKey(), direct.getValue());
    }

    // Поле 02-27 (к.8а): кодът на документа — Invoice/Move.bgVatSupplyType, а при внос по чл. 167а
    // задължителният „03“ (ППЗДДС чл. 49а ал. 4 т. 1). Същият, който POKUPKI пише в 03-45. Преди
    // тук стоеше само кодът от данъчния код (днес винаги ""), тоест к.8а на PRODAGBI оставаше
    // празна и за протокола по чл. 163а (чл. 97а ал. 1: „01“/„02“ в „отчетните регистри“).
    row.setSupplyTypeArt163a(document.getSupplyTypeArt163a());
    // Поле 02-27 по данъчния код (currently always "" — see mapping TODO); непразен го бие.
    for (BgVatAmountDto amount : document.getAmounts()) {
      String code163a = BgVatSalesTaxCodeMapping.art163aSupplyType(amount.getTaxCode());
      if (!code163a.isEmpty()) {
        row.setSupplyTypeArt163a(code163a);
      }
    }

    // Сборът на документа по колона е с пълната точност (scale 3 в AOS); тук се закръгля до
    // стотинка. ⚠️ Без това BgVatSalesLine (@Digits fraction = 2) отказва записа на снимката, а
    // DEKLAR/VIES сумираха незакръглени редове — различно от сбора на колоните в PRODAGBI.
    row.getAmounts().replaceAll((column, value) -> BgVatFieldFormatter.roundAmount(value));

    // Row totals кл.09 / кл.10 = sums of the routed base / VAT columns — of the ROUNDED columns, so
    // the row adds up to itself in the file, the snapshot and DEKLAR.
    row.addAmount(BgVatSalesColumn.K09_TOTAL_BASE, sumColumns(row, BASE_COLUMNS));
    row.addAmount(BgVatSalesColumn.K10_TOTAL_VAT, sumColumns(row, VAT_COLUMNS));
    return row;
  }

  /**
   * Whether a collected document belongs to the SALES journal. True when it is a customer
   * sale/refund, or when any of its tax-code buckets is a recognised sales tax code (covers
   * invoice-less protocols: ВОП/чл.82 self-assessment).
   */
  public boolean isSalesDocument(BgVatDocumentDto document) {
    if (document == null || document.isPurchasesOnly()) {
      return false;
    }
    Integer op = document.getOperationType();
    if (op != null && (op == OPERATION_CLIENT_SALE || op == OPERATION_CLIENT_REFUND)) {
      return true;
    }
    // Ред само с идентификация (митническият документ по чл. 167а): сумите са празни по
    // определение, тъй че решава видът на документа — ППЗДДС Прил. № 12 чрез BgVatDocumentType.
    if (document.isIdentificationOnly()) {
      return BgVatDocumentType.allowedInSales(document.getNapDocumentType());
    }
    // Документ, който колекторът вече е насочил към ПРОДАЖБЕНА колона, Е продажбен — огледално на
    // filterPurchaseDocuments, който пита точно това за покупките.
    //
    // ⚠️ БЕЗ ТОВА протоколът по чл. 79 ал. 4 изчезва мълчаливо. Той няма фактура (operationType е
    // празен) и няма данъчен код (сумата се начислява направо по 4532), тъй че и двете проверки
    // по-горе казват „не“, а к.12 вече е попълнена. Резултатът е начислен данък, който не стига
    // до дневника — най-скъпият вид тишина. Същото важи и за самоначисляването по ВОП/чл. 163а,
    // когато е въведено с ръчна статия.
    if (!document.getSalesAmounts().isEmpty()) {
      return true;
    }
    for (BgVatAmountDto amount : document.getAmounts()) {
      if (BgVatSalesTaxCodeMapping.isSalesTaxCode(amount.getTaxCode())) {
        return true;
      }
    }
    return false;
  }

  /** Tax codes present on the document that have no confirmed sales-journal column mapping. */
  public List<String> unmappedTaxCodes(BgVatDocumentDto document) {
    List<String> unmapped = new ArrayList<>();
    if (document == null) {
      return unmapped;
    }
    for (BgVatAmountDto amount : document.getAmounts()) {
      String code = amount.getTaxCode();
      if (code != null && !code.trim().isEmpty() && !BgVatSalesTaxCodeMapping.isMapped(code)) {
        unmapped.add(code);
      }
    }
    return unmapped;
  }

  protected boolean isCreditNote(BgVatDocumentDto document) {
    if (NAP_DOC_TYPE_CREDIT_NOTE.equals(document.getNapDocumentType())) {
      return true;
    }
    Integer op = document.getOperationType();
    return op != null && op == OPERATION_CLIENT_REFUND;
  }

  // ---------------------------------------------------------------------------
  // Physical layout — ISOLATED (see class note). PROVISIONAL until confirmed.
  // ---------------------------------------------------------------------------

  protected String formatRow(BgVatSalesRowDto row) {
    StringBuilder sb = new StringBuilder(RECORD_LENGTH);
    // Identification header + кл.01, кл.03–кл.08. (кл.02 is not used by the sales journal.)
    sb.append(padRight(field(row.getCompanyVatNumber()), W_COMPANY_VAT)); // [0:15]  ЕИК по ДДС
    sb.append(padRight(field(row.getPeriodCode()), W_PERIOD)); // [15:21] отчетен период ГГГГММ
    sb.append(padRight(field(row.getBranch()), W_BRANCH)); // [21:25] клон (left)
    sb.append(padLeft(Integer.toString(row.getSequence()), W_SEQUENCE)); // [25:40] кл.01 № (right)
    sb.append(zeroPadLeft(field(row.getNapDocumentType()), W_DOC_TYPE)); // [40:42] кл.03 вид док
    sb.append(padRight(field(row.getDocumentNumber()), W_DOC_NUMBER)); // [42:62] кл.04 № (дословно)
    sb.append(padRight(formatDate(row.getDocumentDate()), W_DATE)); // [62:72] кл.05 дата
    sb.append(padRight(field(row.getCounterpartyVatNumber()), W_COUNTERPARTY_VAT)); // [72:87] кл.06
    sb.append(padRight(field(row.getCounterpartyName()), W_COUNTERPARTY_NAME)); // [87:137] кл.07
    sb.append(padRight(field(row.getSubject()), W_SUBJECT)); // [137:167] кл.08 (+ к.8а blank area)
    // Value columns кл.09–кл.25: 17 × 15, right-aligned, space-padded, dot, 2 decimals.
    for (BgVatSalesColumn column : COLUMN_ORDER) {
      sb.append(padLeft(formatAmount(row.getAmount(column)), W_AMOUNT)); // [167:422]
    }
    sb.append(
        padRight(field(row.getSupplyTypeArt163a()), W_SUPPLY_TYPE_163A)); // [422:424] поле 02-27

    String record = sb.toString();
    if (record.length() != RECORD_LENGTH) {
      throw new IllegalStateException(
          "PRODAGBI record must be "
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

  private BigDecimal sumColumns(BgVatSalesRowDto row, Set<BgVatSalesColumn> columns) {
    BigDecimal sum = BigDecimal.ZERO;
    for (BgVatSalesColumn column : columns) {
      sum = sum.add(row.getAmount(column));
    }
    return sum;
  }

  private static BigDecimal sign(BigDecimal value, boolean negate) {
    if (value == null) {
      return BigDecimal.ZERO;
    }
    return negate ? value.negate() : value;
  }
}
