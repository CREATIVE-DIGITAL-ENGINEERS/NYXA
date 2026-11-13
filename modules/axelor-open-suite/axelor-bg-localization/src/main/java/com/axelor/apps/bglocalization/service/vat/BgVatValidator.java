package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatCounterpartyId;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesTaxCodeMapping;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesTaxCodeMapping.ColumnTarget;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportResultDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Validates DTOs/results before file output. In WS-1 only the NAP-readiness gate is implemented;
 * every real check is stubbed (returns empty) with a TODO.
 */
@Singleton
public class BgVatValidator {

  public static final String NAP_DOCUMENT_TYPE_MISSING_CODE = "NAP_DOCUMENT_TYPE_MISSING";
  public static final String NAP_DOCUMENT_TYPE_MISSING_MESSAGE =
      "Missing NAP document type (napDocumentTypeSelect). Required for production NAP export — set it "
          + "on the Invoice, or on the Move for documents without an invoice.";

  // WS-3 sales-production blocking gates.
  public static final String COMPANY_VAT_NUMBER_MISSING_CODE = "COMPANY_VAT_NUMBER_MISSING";
  public static final String UNMAPPED_TAX_CODE_CODE = "UNMAPPED_SALES_TAX_CODE";
  public static final String MISSING_BASE_CODE = "SALES_BASE_MISSING";
  public static final String VAT_ON_ZERO_RATED_CODE = "SALES_VAT_ON_ZERO_RATED";

  /**
   * Ръчната ставка на протокола не е законова (0, 0.09 или 0.20) — колекторът я намира на
   * документа, тъй като от нея се обръща основата.
   */
  public static final String PROTOCOL_RATE_INVALID_CODE = "VAT_PROTOCOL_RATE_INVALID";

  /**
   * Протокол със смесени ставки по редовете, чиято основа не се определя по ставка — нито всеки ред
   * по 4532/4531 носи ставката си, нито има базови редове. Обърната през една ставка, основата би
   * била грешна в к.13/к.14 и в декларацията.
   */
  public static final String PROTOCOL_MIXED_RATE_BASE_CODE = "VAT_PROTOCOL_MIXED_RATE_BASE";

  /**
   * Протокол с ръчна ставка ({@code Move.bgVatMoveVatRate}) до смесени ставки по редовете. Ставката
   * на протокола е една (ЗДДС чл. 117 ал. 2 т. 6), а коя е вярната — не се знае. SAF-T я отказва
   * ({@code BgSaftDiaryCells.rates}); дневникът — също, с една и съща причина.
   */
  public static final String PROTOCOL_MANUAL_RATE_MIXED_CODE = "VAT_PROTOCOL_MANUAL_RATE_MIXED";

  /**
   * Протокол с ръчна ставка, различна от ЕДИНСТВЕНАТА ставка по редовете (0.09 до 20 %). Същата
   * причина като {@link #PROTOCOL_MANUAL_RATE_MIXED_CODE}: ставката е една (ЗДДС чл. 117 ал. 2 т.
   * 6), а SAF-T отказва протокола ({@code BgSaftDiaryCells.rates}).
   */
  public static final String PROTOCOL_MANUAL_RATE_CONFLICT_CODE =
      "VAT_PROTOCOL_MANUAL_RATE_CONFLICT";

  /**
   * Внос по чл. 167а без номер или дата на митническия документ (MRN в {@code Move.origin} / {@code
   * originDate}) — вторият ред по ППЗДДС чл. 49а ал. 4 т. 2 и ал. 5 т. 2 не може да се състави.
   */
  public static final String CUSTOMS_DOCUMENT_MISSING_CODE = "VAT_CUSTOMS_DOCUMENT_MISSING";

  /**
   * ДДС протокол без собствен номер ({@code Move.bgVatProtocolNumber}). ЗДДС чл. 117 ал. 2 т. 1
   * иска „номер и дата“, а ППЗДДС Прил. № 12: „Полето "Номер на документ" … се попълва
   * задължително“.
   */
  public static final String PROTOCOL_NUMBER_MISSING_CODE = "VAT_PROTOCOL_NUMBER_MISSING";

  /**
   * Покупна фактура с обратно начисляване без осчетоводен протокол по ЗДДС чл. 117 за всеки свой
   * случай — или със случай, за който Nyxa протокол не съставя (ГРЕШКИТЕ на {@code
   * BgVatInvoiceProtocolCase}). Заменя {@link #UNMAPPED_TAX_CODE_CODE} за такава фактура: данъците
   * ѝ с обратно начисляване не стигат до дневника от фактурата, а от протокола.
   */
  public static final String INVOICE_PROTOCOL_MISSING_CODE = "VAT_INVOICE_PROTOCOL_MISSING";

  /**
   * Закъснял протокол, издаден извън периода, в който е отразен — ПРЕДУПРЕЖДЕНИЕ вместо общото
   * „дата извън периода“: ППЗДДС чл. 81 ал. 5 иска точно това.
   */
  public static final String PROTOCOL_LATE_CODE = "VAT_PROTOCOL_LATE";

  /**
   * Документ от по-ранен месец, отразен в този период нарочно („Период на отразяване по ЗДДС“) —
   * ПРЕДУПРЕЖДЕНИЕ вместо общото „дата извън периода“: пропуснат документ (ЗДДС чл. 126 ал. 3 т. 1)
   * или покупка с отложен кредит (чл. 72 ал. 1, чл. 124 ал. 4).
   */
  public static final String DOCUMENT_REPORTED_LATE_CODE = "DOCUMENT_REPORTED_LATE";

  /** Датата на документа е извън избрания период, а документът няма изричен период по ЗДДС. */
  public static final String DATE_PERIOD_INCONSISTENT_CODE = "DATE_PERIOD_INCONSISTENT";

  private static final DateTimeFormatter BG_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  /**
   * Основата на протокола е изведена от данъка (данък / ставка) без изрична основа и без базови
   * редове, които да я сверят — ПРЕДУПРЕЖДЕНИЕ: обратното смятане губи стотинки (185.19 / 0.20 =
   * 925.95, а основата е 925.93).
   */
  public static final String PROTOCOL_BASE_DERIVED_CODE = "VAT_PROTOCOL_BASE_DERIVED";

  /**
   * Документ без номер. ППЗДДС Прил. № 12: „Полето "Номер на документ" е символен тип. … Полето се
   * попълва задължително“ (02-05 / 03-06) — за всеки запис, не само за протокола.
   */
  public static final String DOCUMENT_NUMBER_MISSING_CODE = "DOCUMENT_NUMBER_MISSING";

  /**
   * Прехвърляне под режим складиране на стоки до поискване (ЗДДС чл. 15а) — к.8а 41/43/46/48. VIES
   * ги иска (ППЗДДС чл. 117 ал. 2 т. 12–16, ал. 5 т. 5–8), но Nyxa секциите им не съставя (решение
   * от 27.10.2026): ПРЕДУПРЕЖДЕНИЕ, че се попълват ръчно — не мълчалив пропуск и не спрян пакет.
   */
  public static final String VIES_CALL_OFF_STOCK_MANUAL_CODE = "VIES_CALL_OFF_STOCK_MANUAL";

  /** Началото на предупреждението за складиране до поискване. */
  public static final String VIES_CALL_OFF_STOCK_MANUAL_MESSAGE =
      "VIES за складиране до поискване се попълва ръчно";

  /**
   * ДДС номер на контрагента „BG“ + 10 цифри, които минават проверката за ЕГН — {@link
   * BgVatCounterpartyId#looksLikeEgn}. ПРЕДУПРЕЖДЕНИЕ.
   */
  public static final String COUNTERPARTY_VAT_NUMBER_LIKE_EGN_CODE =
      "COUNTERPARTY_VAT_NUMBER_LIKE_EGN";

  /**
   * Покупка — фактура или известие (вид 01/02/03) от български доставчик с номер, различен от точно
   * 10 цифри (ЗДДС чл. 114 ал. 1 т. 2 вр. чл. 115 ал. 4). ПРЕДУПРЕЖДЕНИЕ: номерът е на доставчика,
   * пише се дословно и не се променя автоматично — сверява се с документа.
   */
  public static final String PURCHASE_DOCUMENT_NUMBER_NOT_10_DIGITS_CODE =
      "PURCHASE_DOCUMENT_NUMBER_NOT_10_DIGITS";

  /** Видовете документи с 10-цифрен номер по ЗДДС чл. 114: фактура, дебитно и кредитно известие. */
  private static final Set<String> TEN_DIGIT_DOCUMENT_TYPES =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("01", "02", "03")));

  /** к.8а на прехвърлянията по чл. 15а ({@code bg.vat.supply.type.select}). */
  private static final Set<String> CALL_OFF_STOCK_CODES =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("41", "43", "46", "48")));

  /**
   * к.8а на прехвърляне под режим складиране на стоки до поискване (ЗДДС чл. 15а) — 41/43/46/48.
   * Едно правило за дневника (VIES) и за SAF-T: кодовете 100230–100233 нямат маршрут.
   */
  public static boolean isCallOffStockSupplyType(String supplyType) {
    return supplyType != null && CALL_OFF_STOCK_CODES.contains(supplyType.trim());
  }

  /** Кодовете, които колекторът записва на самия документ ({@link BgVatDocumentDto#getErrors}). */
  private static final Set<String> DOCUMENT_ERROR_CODES =
      Collections.unmodifiableSet(
          new HashSet<>(
              Arrays.asList(
                  PROTOCOL_RATE_INVALID_CODE,
                  PROTOCOL_MIXED_RATE_BASE_CODE,
                  PROTOCOL_MANUAL_RATE_MIXED_CODE,
                  PROTOCOL_MANUAL_RATE_CONFLICT_CODE,
                  CUSTOMS_DOCUMENT_MISSING_CODE,
                  PROTOCOL_NUMBER_MISSING_CODE,
                  INVOICE_PROTOCOL_MISSING_CODE)));

  /**
   * Validate collected documents before they are turned into diary rows.
   *
   * <p>WS-2: per-document data-quality findings (WARNING severity) — missing counterparty tax
   * number, missing document number, missing document date, and date/period consistency. The NAP
   * document-type gate is handled separately via {@link #napReadinessError()}.
   */
  public List<BgVatValidationErrorDto> validateDocuments(
      List<BgVatDocumentDto> documents, BgVatExportRequestDto request) {
    List<BgVatValidationErrorDto> findings = new ArrayList<>();
    if (documents == null) {
      return findings;
    }

    LocalDate from = request != null ? request.getPeriodFrom() : null;
    LocalDate to = request != null ? request.getPeriodTo() : null;

    for (BgVatDocumentDto document : documents) {
      String ref = document.getDocumentNumber();

      // Blocking error for production: NAP document type must be set (Invoice or Move).
      if (isBlank(document.getNapDocumentType())) {
        findings.add(error(NAP_DOCUMENT_TYPE_MISSING_CODE, NAP_DOCUMENT_TYPE_MISSING_MESSAGE, ref));
      }
      findings.addAll(documentErrors(document, ref));

      if (isBlank(document.getCounterpartyDiaryId())) {
        findings.add(
            warning(
                "PARTNER_TAX_NUMBER_MISSING",
                "Липсва ИН на контрагента (02-07 / 03-08): българско юридическо лице без ДДС номер"
                    + " (Invoice.partnerTaxNbr / Partner.taxNbr) и без ЕИК"
                    + " (Partner.registrationCode).",
                ref));
      }
      if (BgVatCounterpartyId.looksLikeEgn(document.getCounterpartyVatNumber())) {
        // Предупреждение, не грешка: служебният номер по чл. 94 ал. 1 може да мине проверката.
        findings.add(
            warning(
                COUNTERPARTY_VAT_NUMBER_LIKE_EGN_CODE,
                "ДДС номерът на контрагента е „BG“ + 10 цифри, които минават проверката за ЕГН —"
                    + " вероятно ЕГН или стар ДДС номер на физическо лице; да се провери. От"
                    + " 01.01.2024 той е „BG“ + служебният номер от регистъра по чл. 94 ал. 1 ЗДДС"
                    + " (§ 1 т. 1 б. „в“ ДР ППЗДДС).",
                ref));
      }
      findings.addAll(documentNumberMissing(document, ref));
      String supplyType = document.getSupplyTypeArt163a();
      if (isCallOffStockSupplyType(supplyType)) {
        findings.add(
            warning(
                VIES_CALL_OFF_STOCK_MANUAL_CODE,
                VIES_CALL_OFF_STOCK_MANUAL_MESSAGE
                    + ": операция по режим складиране на стоки до поискване (к.8а "
                    + supplyType.trim()
                    + ", ЗДДС чл. 15а) — Nyxa не съставя раздел „Г“ на VIES декларацията (ППЗДДС"
                    + " чл. 117, ал. 2, т. 12–16); добавете операцията ръчно преди подаване.",
                ref));
      }
      if (document.getDerivedBase() != null) {
        findings.add(
            warning(
                PROTOCOL_BASE_DERIVED_CODE,
                "Основата на ДДС протокола е изведена от данъка: "
                    + document.getDerivedBase().toPlainString()
                    + " (данък / ставка) — обратното смятане губи стотинки; въведете основата в"
                    + " протокола („Данъчна основа на протокола“, ЗДДС чл. 117 ал. 2 т. 5).",
                ref));
      }
      if (document.getDocumentDate() == null) {
        findings.add(warning("DOCUMENT_DATE_MISSING", "Missing document date.", ref));
      } else if (document.isIssuedLate()) {
        findings.add(
            warning(
                PROTOCOL_LATE_CODE,
                "Закъснял ДДС протокол, издаден на "
                    + document.getDocumentDate()
                    + ": отразен е в периода на изискуемостта, а не на издаването — ППЗДДС чл. 81,"
                    + " ал. 5; корекцията на подаден период е по ЗДДС чл. 126, ал. 3, т. 2.",
                ref));
      } else if (document.getReportingPeriod() != null
          && from != null
          && document.getDocumentDate().isBefore(from)) {
        findings.add(
            warning(
                DOCUMENT_REPORTED_LATE_CODE,
                "Документ от "
                    + BgVatReportingPeriod.label(
                        BgVatReportingPeriod.of(document.getDocumentDate()))
                    + ", отразен в този период („Период на отразяване по ЗДДС“"
                    + (Integer.valueOf(1).equals(document.getOperationType())
                        ? " — покупка, ЗДДС чл. 72 ал. 1 и чл. 124 ал. 4, или пропуснат документ,"
                            + " чл. 126 ал. 3 т. 1)."
                        : " — пропуснат документ, ЗДДС чл. 126 ал. 3 т. 1)."),
                ref));
      } else if (from != null
          && to != null
          && (document.getDocumentDate().isBefore(from)
              || document.getDocumentDate().isAfter(to))) {
        findings.add(
            warning(
                DATE_PERIOD_INCONSISTENT_CODE,
                dateOutsidePeriodMessage(document.getDocumentDate(), from, to),
                ref));
      }
    }
    return findings;
  }

  /**
   * Текстът на {@link #DATE_PERIOD_INCONSISTENT_CODE} — на български, с датите във вида на
   * документите (ДД.ММ.ГГГГ), както е и останалият преглед на декларацията.
   */
  static String dateOutsidePeriodMessage(LocalDate date, LocalDate from, LocalDate to) {
    return "Датата на документа "
        + date.format(BG_DATE)
        + " е извън избрания период "
        + from.format(BG_DATE)
        + " – "
        + to.format(BG_DATE)
        + ". Проверете датата или задайте „Период на отразяване по ЗДДС“ на документа.";
  }

  /**
   * POKUPKI: документ вид 01/02/03 от български доставчик с номер, различен от 10 цифри — само
   * ПРЕДУПРЕЖДЕНИЕ (пакетът е „С предупреждения“, не спира, номерът не се пипа). Празният номер е
   * {@link #DOCUMENT_NUMBER_MISSING_CODE}, не тук.
   *
   * <p>Български е доставчикът с ИН в дневника „BG…“ или само с цифри (ИН по § 1 т. 1 ДР ППЗДДС —
   * ЕИК, БУЛСТАТ, служебен номер), без „{@value BgVatCounterpartyId#NO_ID}“.
   */
  public List<BgVatValidationErrorDto> validatePurchaseDocumentNumbers(
      List<BgVatDocumentDto> purchaseDocuments) {
    List<BgVatValidationErrorDto> findings = new ArrayList<>();
    if (purchaseDocuments == null) {
      return findings;
    }
    for (BgVatDocumentDto document : purchaseDocuments) {
      String number = document.getDocumentNumber();
      if (isBlank(number)
          || !TEN_DIGIT_DOCUMENT_TYPES.contains(documentType(document.getNapDocumentType()))
          || !isBulgarianCounterparty(document.getCounterpartyDiaryId())
          || number.trim().matches("\\d{10}")) {
        continue;
      }
      findings.add(
          warning(
              PURCHASE_DOCUMENT_NUMBER_NOT_10_DIGITS_CODE,
              "Номерът „"
                  + number.trim()
                  + "“ на документ вид "
                  + documentType(document.getNapDocumentType())
                  + " от български доставчик не е 10 цифри (ЗДДС чл. 114 ал. 1 т. 2 вр. чл. 115"
                  + " ал. 4) — сверете го с документа на доставчика; в POKUPKI се пише дословно.",
              number.trim()));
    }
    return findings;
  }

  private static String documentType(String type) {
    if (isBlank(type)) {
      return "";
    }
    String t = type.trim();
    return t.length() == 1 ? "0" + t : t;
  }

  private static boolean isBulgarianCounterparty(String diaryId) {
    if (isBlank(diaryId)) {
      return false;
    }
    String id = diaryId.trim().toUpperCase();
    if (BgVatCounterpartyId.NO_ID.equals(id)) {
      return false;
    }
    return id.startsWith("BG") || id.matches("\\d+");
  }

  /**
   * Blocking gate for PRODAGBI (sales) production. Returns ERROR findings that must be resolved
   * before a file is written:
   *
   * <ul>
   *   <li>reporting company VAT number missing (header field);
   *   <li>NAP document type missing on a document;
   *   <li>a tax code with no confirmed sales-journal column mapping (special schemes are left
   *       unmapped on purpose — see {@link BgVatSalesTaxCodeMapping});
   *   <li>a bucket that carries output VAT but has no taxable base (base column would be empty);
   *   <li>a bucket that carries VAT under a zero-rated mapping (data inconsistency).
   * </ul>
   */
  public List<BgVatValidationErrorDto> validateSalesForProduction(
      List<BgVatDocumentDto> salesDocuments, BgVatExportRequestDto request) {
    List<BgVatValidationErrorDto> findings = new ArrayList<>();

    if (request == null || isBlank(request.getCompanyVatNumber())) {
      findings.add(
          error(
              COMPANY_VAT_NUMBER_MISSING_CODE,
              "Missing reporting company VAT number (Company → Partner → tax number). It is the "
                  + "identification field of every diary row.",
              null));
    }

    if (salesDocuments == null) {
      return findings;
    }

    for (BgVatDocumentDto document : salesDocuments) {
      String ref = document.getDocumentNumber();

      if (isBlank(document.getNapDocumentType())) {
        findings.add(error(NAP_DOCUMENT_TYPE_MISSING_CODE, NAP_DOCUMENT_TYPE_MISSING_MESSAGE, ref));
      }
      findings.addAll(documentErrors(document, ref));
      findings.addAll(documentNumberMissing(document, ref));

      for (BgVatAmountDto amount : document.getAmounts()) {
        String code = amount.getTaxCode();
        Optional<ColumnTarget> target = BgVatSalesTaxCodeMapping.forTaxCode(code);
        if (!target.isPresent()) {
          findings.add(
              error(
                  UNMAPPED_TAX_CODE_CODE,
                  "Tax code '"
                      + nvl(code)
                      + "' has no confirmed PRODAGBI column mapping. Add it to "
                      + "BgVatSalesTaxCodeMapping (after accountant/CTO confirmation) or exclude the document.",
                  ref));
          continue;
        }

        ColumnTarget columns = target.get();
        boolean hasVat = amount.getVatAmount() != null && amount.getVatAmount().signum() != 0;
        boolean hasBase = amount.getBase() != null && amount.getBase().signum() != 0;

        if (columns.getVatColumn() == null && hasVat) {
          findings.add(
              error(
                  VAT_ON_ZERO_RATED_CODE,
                  "Tax code '" + nvl(code) + "' is mapped as zero-rated but carries VAT.",
                  ref));
        }
        if (columns.getVatColumn() != null && hasVat && !hasBase) {
          findings.add(
              error(
                  MISSING_BASE_CODE,
                  "Tax code '"
                      + nvl(code)
                      + "' carries VAT but has no taxable base (кл. base column "
                      + "would be empty). Non-invoice moves must supply the base in WS-3+.",
                  ref));
        }
      }
    }
    return findings;
  }

  /**
   * ГРЕШКА за документ без номер — освен когато колекторът вече е казал същото по-точно (протокол
   * без собствен номер, {@link #PROTOCOL_NUMBER_MISSING_CODE}).
   */
  private List<BgVatValidationErrorDto> documentNumberMissing(
      BgVatDocumentDto document, String ref) {
    List<BgVatValidationErrorDto> findings = new ArrayList<>();
    boolean protocolSaidSo =
        document.getErrors().stream()
            .anyMatch(e -> PROTOCOL_NUMBER_MISSING_CODE.equals(e.getCode()));
    if (isBlank(document.getDocumentNumber()) && !protocolSaidSo) {
      findings.add(
          error(
              DOCUMENT_NUMBER_MISSING_CODE,
              "Липсва „Номер на документ“ (02-05 / 03-06) — по ППЗДДС Прил. № 12 полето се"
                  + " попълва задължително.",
              ref));
    }
    return findings;
  }

  /** The collector's blocking findings on {@code document}, as ERRORs carrying its reference. */
  private List<BgVatValidationErrorDto> documentErrors(BgVatDocumentDto document, String ref) {
    List<BgVatValidationErrorDto> findings = new ArrayList<>();
    for (BgVatValidationErrorDto found : document.getErrors()) {
      findings.add(error(found.getCode(), found.getMessage(), ref));
    }
    return findings;
  }

  /**
   * {@code true} for a finding that {@link #validateDocuments} already lists for every document —
   * the production gates repeat it only to block the file, and must not list it twice.
   */
  public static boolean isDocumentLevel(String code) {
    return NAP_DOCUMENT_TYPE_MISSING_CODE.equals(code)
        || DOCUMENT_NUMBER_MISSING_CODE.equals(code)
        || DOCUMENT_ERROR_CODES.contains(code);
  }

  private static String nvl(String value) {
    return value == null ? "" : value;
  }

  private BgVatValidationErrorDto warning(String code, String message, String documentRef) {
    BgVatValidationErrorDto finding =
        new BgVatValidationErrorDto(code, BgVatValidationErrorDto.SEVERITY_WARNING, message);
    finding.setDocumentRef(documentRef);
    return finding;
  }

  private BgVatValidationErrorDto error(String code, String message, String documentRef) {
    BgVatValidationErrorDto finding =
        new BgVatValidationErrorDto(code, BgVatValidationErrorDto.SEVERITY_ERROR, message);
    finding.setDocumentRef(documentRef);
    return finding;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * Validate the assembled result (control sums) before writing files.
   *
   * <p>Stub in WS-1 — returns no findings.
   */
  public List<BgVatValidationErrorDto> validateResult(BgVatExportResultDto result) {
    // TODO WS-2/WS-3: declaration control sums must reconcile against the generated diaries;
    //   verify CP1251 encoding and CRLF line endings of the produced files.
    return new ArrayList<>();
  }
}
