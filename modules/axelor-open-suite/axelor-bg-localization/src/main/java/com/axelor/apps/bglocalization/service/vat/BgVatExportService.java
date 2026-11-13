package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDocumentType;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatFieldFormatter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExclusionsDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportResultDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatGeneratedFileDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPurchaseRowDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatSalesRowDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.inject.Inject;

/**
 * Orchestrator for the Bulgarian VAT/NAP "generating diaries" process.
 *
 * <p>Pipeline: collect (WS-2) → validate → adapt → write → MetaFile download.
 *
 * <ul>
 *   <li><b>Dry run</b> → collects posted documents, maps sales documents to NAP columns, and
 *       returns a per-column preview. No file is written.
 *   <li><b>Production, sales (WS-3)</b> → gates on the sales validator; when clean, builds
 *       PRODAGBI.TXT via {@link BgVatSalesAdapter}, encodes it (CP1251/CRLF) and returns it as a
 *       downloadable {@link MetaFile}.
 *   <li><b>Production, purchases/declaration/VIES</b> → still blocked (not implemented yet).
 * </ul>
 */
@Singleton
public class BgVatExportService {

  protected final BgVatDocumentCollector collector;
  protected final BgVatValidator validator;
  protected final BgVatPurchaseAdapter purchaseAdapter;
  protected final BgVatSalesAdapter salesAdapter;
  protected final BgVatDeclarationAdapter declarationAdapter;
  protected final BgVatViesAdapter viesAdapter;
  protected final BgVatFileWriter fileWriter;

  private static final String DRY_RUN_MESSAGE =
      "Пробен преглед: документите са събрани и разпределени по колоните на дневника за продажби;"
          + " файл не е записан.";

  /**
   * Празен резултат — защо точно, казват редовете от {@link BgVatGenerateReport#exclusionLines}.
   */
  static final String NO_DOCUMENTS_WARNING =
      "Няма осчетоводени документи с ДДС за дружеството в периода. Търсят се по датата на"
          + " статията; статиите „в дневник“ (DAYBOOK) и черновите не влизат.";

  /** Report-type codes ({@code BgVatExportRequestDto#reportType}). */
  private static final String REPORT_SALES = "sales";

  private static final String REPORT_PURCHASES = "purchases";
  private static final String REPORT_DECLARATION = "declaration";
  private static final String REPORT_VIES = "vies";
  private static final String REPORT_ALL = "all";

  /*
   * Имената, под които файловете се свалят (поотделно и в ZIP-а) и се пазят в пакета — С МАЛКИ
   * БУКВИ: компонентът на портала на НАП под Linux ги търси точно като deklar.txt,
   * prodagbi.txt, pokupki.txt и vies.txt, а файловата система там различава главни от малки.
   * Форматът (Прил. № 12 към ППЗДДС) и текстовете за човек си остават с имената DEKLAR/PRODAGBI/…
   */
  public static final String SALES_FILE_NAME = "prodagbi.txt";
  public static final String PURCHASE_FILE_NAME = "pokupki.txt";
  public static final String DECLARATION_FILE_NAME = "deklar.txt";
  public static final String VIES_FILE_NAME = "vies.txt";

  /** Prominent notice that the physical file layout is not yet НАП-confirmed. */
  static final String SALES_FORMAT_DISCLAIMER =
      "PRODAGBI.TXT е генериран. Колоните на НАП идват от настройките на BG сметкоплана, а"
          + " физическият формат на файла (ред, разделители и запис на полетата) е ПРЕДВАРИТЕЛЕН и"
          + " трябва да се свери с актуалното Приложение № 12 към ППЗДДС преди подаване.";

  /**
   * Без редове за VIES файл няма (ППЗДДС чл. 117 ал. 5, виж {@link #generateViesFile}) — казва се
   * във формата.
   */
  public static final String VIES_NOT_FILED =
      "Няма данни за VIES декларация за периода — не се подава.";

  /** Код на грешката, когато файл не е съставен заради изключение. */
  public static final String FILE_GENERATION_FAILED_CODE = "FILE_GENERATION_FAILED";

  /**
   * Обичайните бележки, които сами по себе си не са повод за диалог след „Генерирай“ — те са при
   * всяко генериране с продажби или без ВОД.
   */
  public static boolean isRoutineNotice(String warning) {
    return SALES_FORMAT_DISCLAIMER.equals(warning) || VIES_NOT_FILED.equals(warning);
  }

  @Inject
  public BgVatExportService(
      BgVatDocumentCollector collector,
      BgVatValidator validator,
      BgVatPurchaseAdapter purchaseAdapter,
      BgVatSalesAdapter salesAdapter,
      BgVatDeclarationAdapter declarationAdapter,
      BgVatViesAdapter viesAdapter,
      BgVatFileWriter fileWriter) {
    this.collector = collector;
    this.validator = validator;
    this.purchaseAdapter = purchaseAdapter;
    this.salesAdapter = salesAdapter;
    this.declarationAdapter = declarationAdapter;
    this.viesAdapter = viesAdapter;
    this.fileWriter = fileWriter;
  }

  private static final int SAMPLE_LIMIT = 5;

  public BgVatExportResultDto generate(BgVatExportRequestDto request) {
    BgVatExportResultDto result = new BgVatExportResultDto();
    result.setReportType(request.getReportType());
    result.setCompanyName(request.getCompanyName());
    result.setPeriodLabel(formatPeriod(request));

    // Guard: need a company + period to scope the query safely. Split the two cases with clear,
    // user-facing messages (NOT the misleading foundation/dry-run text).
    boolean missingPeriod = request.getPeriodFrom() == null || request.getPeriodTo() == null;
    boolean missingCompany = request.getCompanyId() == null;
    if (missingPeriod || missingCompany) {
      String message =
          missingPeriod ? "Изберете данъчен период преди генериране." : "Липсва дружество.";
      result
          .getValidationErrors()
          .add(
              new BgVatValidationErrorDto(
                  "MISSING_PARAMS", BgVatValidationErrorDto.SEVERITY_ERROR, message));
      result.setSuccess(false);
      result.setMessage(message);
      return result;
    }

    // WS-2: real collection (posted moves for company + period, grouped by document).
    List<BgVatDocumentDto> documents = collector.collect(request);
    result.setDocumentsFound(documents.size());
    computeTotals(result, documents);

    // Per-document findings: blocking NAP-document-type errors + data-quality warnings.
    result.getValidationErrors().addAll(validator.validateDocuments(documents, request));

    // Explain an empty result set (team decision: ACCOUNTED-only; DAYBOOK is excluded by design).
    if (documents.isEmpty()) {
      result.getWarnings().add(NO_DOCUMENTS_WARNING);
    }
    // Защо статии и документи с ДДС не са в дневниците — и при празен, и при непълен резултат.
    BgVatExclusionsDto exclusions = collector.excludedMoves(request);
    classifyDropped(
        documents, filterSalesDocuments(documents), filterPurchaseDocuments(documents), exclusions);
    result.setExclusions(exclusions);
    result.getWarnings().addAll(BgVatGenerateReport.exclusionLines(exclusions));

    buildSampleSummaries(result, documents);

    // WS-3: PRODAGBI (sales) is implemented. Purchases/declaration/VIES remain out of scope.
    boolean salesScope = isSalesScope(request.getReportType());
    List<BgVatDocumentDto> salesDocuments =
        salesScope ? filterSalesDocuments(documents) : new ArrayList<>();
    if (salesScope) {
      buildSalesPreview(result, salesDocuments, request);
    }

    if (request.isDryRun()) {
      result.setSuccess(true);
      result
          .getWarnings()
          .add(
              "Пробен преглед — файл не е записан. Отстранете грешките по-долу преди истинското"
                  + " генериране.");
      result.setMessage(DRY_RUN_MESSAGE);
      return result;
    }

    // ---- Production ----
    // Recompute full scoped lists (salesDocuments above is preview-only and may be empty).
    salesDocuments = filterSalesDocuments(documents);
    List<BgVatDocumentDto> purchaseDocuments = filterPurchaseDocuments(documents);
    String reportType = request.getReportType();

    if (isSalesScope(reportType)) {
      generateSalesFile(result, salesDocuments, request);
    }
    if (isPurchaseScope(reportType)) {
      generatePurchaseFile(result, purchaseDocuments, request);
    }
    if (isDeclarationScope(reportType)) {
      generateDeclarationFile(result, salesDocuments, purchaseDocuments, request);
    }
    if (isViesScope(reportType)) {
      generateViesFile(result, salesDocuments, request);
    }

    if (result.getGeneratedFiles().isEmpty()) {
      result.setSuccess(false);
      result.setMessage(
          "Няма генерирани файлове за вид на доклада \""
              + reportType
              + "\" — проверете грешките/наличните документи.");
      return result;
    }
    boolean fileFailed =
        result.getValidationErrors().stream()
            .anyMatch(e -> FILE_GENERATION_FAILED_CODE.equals(e.getCode()));
    result.setSuccess(!fileFailed);
    String message = result.getGeneratedFiles().size() + " NAP файл(а) генериран(и).";
    if (result.getWarnings().contains(VIES_NOT_FILED)) {
      message += " " + VIES_NOT_FILED;
    }
    result.setMessage(message);
    return result;
  }

  /**
   * Файл не е съставен заради изключение — ГРЕШКА, не предупреждение: пакетът без него е непълен
   * (ППЗДДС чл. 114 ал. 1, изм. ДВ бр. 24/2017), а формата не бива да казва „генерирана“.
   */
  private static void fileFailed(BgVatExportResultDto result, String message) {
    result
        .getValidationErrors()
        .add(
            new BgVatValidationErrorDto(
                FILE_GENERATION_FAILED_CODE, BgVatValidationErrorDto.SEVERITY_ERROR, message));
  }

  // ---------------------------------------------------------------------------
  // Per-scope file generation (each uploads a MetaFile and records it on the result)
  // ---------------------------------------------------------------------------

  /** PRODAGBI.TXT — gate → build → encode → MetaFile. Skips (no file) on blocking errors. */
  protected void generateSalesFile(
      BgVatExportResultDto result,
      List<BgVatDocumentDto> salesDocuments,
      BgVatExportRequestDto request) {
    List<BgVatValidationErrorDto> gate =
        validator.validateSalesForProduction(salesDocuments, request);
    for (BgVatValidationErrorDto finding : gate) {
      // Document-level errors are already listed by validateDocuments; avoid duplicating them.
      if (!BgVatValidator.isDocumentLevel(finding.getCode())) {
        result.getValidationErrors().add(finding);
      }
    }
    boolean hasBlocking =
        gate.stream().anyMatch(f -> BgVatValidationErrorDto.SEVERITY_ERROR.equals(f.getSeverity()));
    if (salesDocuments.isEmpty()) {
      // Zero-declaration: emit an empty PRODAGBI.TXT (0 rows) so the НАП package is always
      // complete.
      if (request.isEmitEmptyFiles()) {
        emitEmptyFile(result, "sales", SALES_FILE_NAME, request);
      }
      return;
    }
    if (hasBlocking) {
      result
          .getWarnings()
          .add(
              "PRODAGBI.TXT НЕ е генериран — има блокиращи грешки (вид документ/данъчен"
                  + " код/база/ЕИК).");
      return;
    }
    try {
      // Snapshot source for the BIRT дневник продажби report. buildRows is a pure, deterministic
      // transform of the SAME salesDocuments the file is built from (buildLines delegates to
      // buildRows) → these rows equal the TXT rows byte-for-byte (invariant: report == TXT). No
      // second collector pass.
      List<BgVatSalesRowDto> rows = salesAdapter.buildRows(salesDocuments, request);
      result.getSalesRows().addAll(rows);
      List<String> lines = salesAdapter.buildLines(salesDocuments, request);
      uploadFile(
          result, "sales", SALES_FILE_NAME, fileWriter.toBytes(lines, request.getEncoding()));
      result.getWarnings().add(SALES_FORMAT_DISCLAIMER);
    } catch (Exception e) {
      fileFailed(result, "Неуспешно генериране на PRODAGBI.TXT: " + e.getMessage());
    }
  }

  /** POKUPKI.TXT — merges the collector's per-column purchase amounts onto identification rows. */
  protected void generatePurchaseFile(
      BgVatExportResultDto result,
      List<BgVatDocumentDto> purchaseDocuments,
      BgVatExportRequestDto request) {
    // Номерът на фактурата от БГ доставчик — 10 цифри; иначе само предупреждение („Готовност“).
    result
        .getValidationErrors()
        .addAll(validator.validatePurchaseDocumentNumbers(purchaseDocuments));
    if (purchaseDocuments.isEmpty()) {
      // Zero-declaration: emit an empty POKUPKI.TXT (0 rows) so the НАП package is always complete.
      if (request.isEmitEmptyFiles()) {
        emitEmptyFile(result, "purchases", PURCHASE_FILE_NAME, request);
      }
      return;
    }
    try {
      List<BgVatPurchaseRowDto> rows = purchaseAdapter.buildRows(purchaseDocuments, request);
      for (int i = 0; i < purchaseDocuments.size(); i++) {
        BgVatDocumentDto doc = purchaseDocuments.get(i);
        BgVatPurchaseRowDto row = rows.get(i);
        for (Map.Entry<BgVatPurchaseColumn, BigDecimal> e :
            doc.getPurchaseDiaryAmounts().entrySet()) {
          row.addAmount(e.getKey(), e.getValue());
        }
        row.setSupplyTypeArt163a(doc.getSupplyTypeArt163a());
      }
      // Snapshot source for the BIRT дневник покупки report — the SAME enriched rows object that
      // produces POKUPKI.TXT below (invariant: report == TXT, single collection).
      result.getPurchaseRows().addAll(rows);
      List<String> lines = purchaseAdapter.buildLines(rows);
      uploadFile(
          result,
          "purchases",
          PURCHASE_FILE_NAME,
          fileWriter.toBytes(lines, request.getEncoding()));
    } catch (Exception e) {
      fileFailed(result, "Неуспешно генериране на POKUPKI.TXT: " + e.getMessage());
    }
  }

  /** DEKLAR.TXT — aggregates the sales + purchase diaries into the declaration record. */
  protected void generateDeclarationFile(
      BgVatExportResultDto result,
      List<BgVatDocumentDto> salesDocuments,
      List<BgVatDocumentDto> purchaseDocuments,
      BgVatExportRequestDto request) {
    try {
      BgVatDeclarationAdapter.Header header =
          new BgVatDeclarationAdapter.Header(
              request.getCompanyVatNumber(),
              request.getCompanyName(),
              periodYyyyMm(request),
              request.getDeclarantSubmitter());
      BgVatDeclarationAdapter.ManualCells manual =
          new BgVatDeclarationAdapter.ManualCells(
              request.getCoefficientK33(),
              request.getCell70(),
              request.getCell71(),
              request.getCell80(),
              request.getCell81(),
              request.getCell82());
      List<String> lines =
          declarationAdapter.buildLines(salesDocuments, purchaseDocuments, header, manual);
      uploadFile(
          result,
          "declaration",
          DECLARATION_FILE_NAME,
          fileWriter.toBytes(lines, request.getEncoding()));
    } catch (Exception e) {
      fileFailed(result, "Неуспешно генериране на DEKLAR.TXT: " + e.getMessage());
    }
  }

  /** VIES.TXT — Variant 1 (VHR·VDR·VTR·TTR·VIR) from EU intra-community sales. */
  protected void generateViesFile(
      BgVatExportResultDto result,
      List<BgVatDocumentDto> salesDocuments,
      BgVatExportRequestDto request) {
    try {
      BgVatViesAdapter.RegisteredPerson person =
          new BgVatViesAdapter.RegisteredPerson(
              request.getCompanyVatNumber(), request.getCompanyName(), request.getCompanyAddress());
      BgVatViesAdapter.Declarant declarant =
          new BgVatViesAdapter.Declarant(
              request.getDeclarantEgn(),
              request.getDeclarantNames(),
              request.getDeclarantCity(),
              request.getDeclarantPostCode(),
              request.getDeclarantAddress(),
              request.getDeclarantCapacity());
      List<String> lines =
          viesAdapter.buildLines(salesDocuments, person, declarant, periodMmYyyy(request));
      // Без нито един ред VIR (ВОД, тристранна, услуги по чл. 21 ал. 2 ЗДДС) VIES.TXT НЕ се
      // съставя — и при нулева декларация. Секциите VHR/VDR/VTR/TTR не са редове.
      //
      // Основанието е ППЗДДС чл. 117 ал. 5: „VIES декларация не се подава в случаите, когато за
      // съответния данъчен период регистрираното лице едновременно:“ — т. 1–4 (ВОД; услуги по
      // чл. 21 ал. 2; посредник в тристранна операция; пропуснати данни по чл. 126 ал. 3 т. 1
      // ЗДДС),
      // т. 5–8 (складиране до поискване). Встъпителният текст — изм. ДВ бр. 15/2012; т. 5–8 — нови
      // с ДВ бр. 25/2020. Сверено на 23.10.2026 с консолидирания текст на ППЗДДС (последно изм.
      // ДВ бр. 54/2024).
      //
      // ЗДДС чл. 125 ал. 2 (доп. ДВ бр. 96/2019; сверен с lex.bg, редакция до ДВ бр. 69/2026) е
      // задължението ДА се подава от лицето, което „е извършило“ такива доставки; условията за
      // неподаване ги няма в закона.
      //
      // т. 4 (пропуснати данни за предходен период) — редът с периода на изискуемостта (т. 11,
      // BgVatViesPeriod), тоест VIR има и тогава. т. 5–8 (складиране до поискване) Nyxa не
      // съставя — ПРЕДУПРЕЖДЕНИЕ VIES_CALL_OFF_STOCK_MANUAL от валидатора, попълва се ръчно.
      long virCount = lines.stream().filter(line -> line.startsWith("VIR")).count();
      if (virCount == 0) {
        result.getWarnings().add(VIES_NOT_FILED);
        return;
      }
      uploadFile(result, "vies", VIES_FILE_NAME, fileWriter.toBytes(lines, request.getEncoding()));
    } catch (Exception e) {
      fileFailed(result, "Неуспешно генериране на VIES.TXT: " + e.getMessage());
    }
  }

  /**
   * Zero-declaration helper: emit an empty diary file (0 data rows) for {@code scope}. Used only
   * when {@link BgVatExportRequestDto#isEmitEmptyFiles()} so the НАП package is always complete.
   * ППЗДДС чл. 114 ал. 3 и Приложение № 12, „Общи изисквания“: без отразени документи
   * POKUPKI/PRODAGBI „няма да съдържат нито един запис“ (консолидиран текст, последно изм. ДВ бр.
   * 54/2024, сверен 23.10.2026). DEKLAR.TXT не минава оттук — той е винаги един запис.
   */
  private void emitEmptyFile(
      BgVatExportResultDto result, String scope, String fileName, BgVatExportRequestDto request) {
    try {
      uploadFile(
          result,
          scope,
          fileName,
          fileWriter.toBytes(Collections.emptyList(), request.getEncoding()));
    } catch (Exception e) {
      fileFailed(result, "Неуспешно генериране на празен " + fileName + ": " + e.getMessage());
    }
  }

  /** Качва файла като MetaFile и го записва в резултата. {@code protected} — шевът на тестовете. */
  protected BgVatGeneratedFileDto uploadFile(
      BgVatExportResultDto result, String scope, String fileName, byte[] bytes) throws IOException {
    MetaFile metaFile =
        Beans.get(MetaFiles.class).upload(new ByteArrayInputStream(bytes), fileName);
    BgVatGeneratedFileDto generated =
        new BgVatGeneratedFileDto(scope, fileName, metaFile.getId(), metaFile.getVersion(), bytes);
    result.getGeneratedFiles().add(generated);
    // Keep the single-file fields pointing at the last file (back-compat for existing wizard).
    result.setGeneratedFileName(fileName);
    result.setMetaFileId(metaFile.getId());
    result.setMetaFileVersion(metaFile.getVersion());
    return generated;
  }

  private String periodYyyyMm(BgVatExportRequestDto request) {
    LocalDate d = request.getPeriodTo() != null ? request.getPeriodTo() : request.getPeriodFrom();
    return d == null ? "" : d.format(DateTimeFormatter.ofPattern("yyyyMM"));
  }

  private String periodMmYyyy(BgVatExportRequestDto request) {
    LocalDate d = request.getPeriodTo() != null ? request.getPeriodTo() : request.getPeriodFrom();
    return d == null ? "" : d.format(DateTimeFormatter.ofPattern("MM/yyyy"));
  }

  protected boolean isSalesScope(String reportType) {
    return REPORT_SALES.equalsIgnoreCase(reportType) || REPORT_ALL.equalsIgnoreCase(reportType);
  }

  protected boolean isPurchaseScope(String reportType) {
    return REPORT_PURCHASES.equalsIgnoreCase(reportType) || REPORT_ALL.equalsIgnoreCase(reportType);
  }

  protected boolean isDeclarationScope(String reportType) {
    return REPORT_DECLARATION.equalsIgnoreCase(reportType)
        || REPORT_ALL.equalsIgnoreCase(reportType);
  }

  protected boolean isViesScope(String reportType) {
    return REPORT_VIES.equalsIgnoreCase(reportType) || REPORT_ALL.equalsIgnoreCase(reportType);
  }

  protected List<BgVatDocumentDto> filterSalesDocuments(List<BgVatDocumentDto> documents) {
    return documents.stream().filter(salesAdapter::isSalesDocument).collect(Collectors.toList());
  }

  /**
   * Purchase documents = those the collector routed into POKUPKI columns (purchase invoices and
   * manual VAT protocols both populate {@code purchaseAmounts}), plus the identification-only rows
   * (the customs document of a чл. 167а import) that the document type allows in POKUPKI.
   */
  protected List<BgVatDocumentDto> filterPurchaseDocuments(List<BgVatDocumentDto> documents) {
    return documents.stream()
        .filter(
            d ->
                !d.getPurchaseAmounts().isEmpty()
                    || (d.isIdentificationOnly()
                        && BgVatDocumentType.allowedInPurchases(d.getNapDocumentType())))
        .collect(Collectors.toList());
  }

  /**
   * Документите, които събирачът е взел, но които не са в нито един дневник, по причина. Фактура с
   * обратно начисляване БЕЗ грешка е покрита от протокола си по чл. 117 ЗДДС — не е изпаднала.
   */
  protected void classifyDropped(
      List<BgVatDocumentDto> documents,
      List<BgVatDocumentDto> salesDocuments,
      List<BgVatDocumentDto> purchaseDocuments,
      BgVatExclusionsDto exclusions) {
    for (BgVatDocumentDto document : documents) {
      // DTO-то няма equals — contains е по идентичност, тоест точно този документ.
      if (salesDocuments.contains(document) || purchaseDocuments.contains(document)) {
        continue;
      }
      String label =
          document.getDocumentNumber() == null || document.getDocumentNumber().trim().isEmpty()
              ? "без номер"
              : document.getDocumentNumber().trim();
      if (document.isReverseChargeInvoice()) {
        if (!document.getErrors().isEmpty()) {
          exclusions.getReverseChargeDocuments().add(label);
        }
      } else if (document.isZeroAmountPurchase()) {
        exclusions.getZeroAmountDocuments().add(label);
      } else {
        exclusions.getOtherDroppedDocuments().add(label);
      }
    }
  }

  /** Per-column preview of the first sales rows so the mapping can be verified without a file. */
  protected void buildSalesPreview(
      BgVatExportResultDto result,
      List<BgVatDocumentDto> salesDocuments,
      BgVatExportRequestDto request) {
    List<BgVatSalesRowDto> rows = salesAdapter.buildRows(salesDocuments, request);
    int limit = Math.min(SAMPLE_LIMIT, rows.size());
    for (int i = 0; i < limit; i++) {
      BgVatSalesRowDto row = rows.get(i);
      StringBuilder sb = new StringBuilder();
      sb.append("PRODAGBI row #")
          .append(row.getSequence())
          .append(" | doc=")
          .append(blankToDash(row.getDocumentNumber()))
          .append(" | type=")
          .append(blankToDash(row.getNapDocumentType()))
          .append(" | partner=")
          .append(blankToDash(row.getCounterpartyVatNumber()))
          .append(" | cols:");
      for (BgVatSalesColumn column : BgVatSalesColumn.values()) {
        BigDecimal value = row.getAmount(column);
        if (value != null && value.signum() != 0) {
          sb.append(" к").append(column.getNapCode()).append("=").append(value);
        }
      }
      result.getSampleDocuments().add(sb.toString());
    }
  }

  protected void computeTotals(BgVatExportResultDto result, List<BgVatDocumentDto> documents) {
    BigDecimal base = BigDecimal.ZERO;
    BigDecimal vat = BigDecimal.ZERO;
    BigDecimal gross = BigDecimal.ZERO;
    // По документ до стотинка, после сборът — както колоните на дневниците; BgVatPackage пази
    // тоталите с 2 знака (@Digits fraction = 2), а сумите на AOS са със scale 3.
    for (BgVatDocumentDto document : documents) {
      base = base.add(BgVatFieldFormatter.roundAmount(document.getTotalBase()));
      vat = vat.add(BgVatFieldFormatter.roundAmount(document.getTotalVat()));
      gross = gross.add(BgVatFieldFormatter.roundAmount(document.getTotalGross()));
    }
    result.setTotalBase(base);
    result.setTotalVat(vat);
    result.setTotalGross(gross);
  }

  protected void buildSampleSummaries(
      BgVatExportResultDto result, List<BgVatDocumentDto> documents) {
    int limit = Math.min(SAMPLE_LIMIT, documents.size());
    for (int i = 0; i < limit; i++) {
      BgVatDocumentDto d = documents.get(i);
      // Show BOTH tax-number candidates and the resolved diary value so real Bulgarian data can be
      // verified: Invoice.partnerTaxNbr, Partner.taxNbr, and the value chosen for the diary.
      result
          .getSampleDocuments()
          .add(
              String.format(
                  "[%s] %s | %s | partner=%s | inv.partnerTaxNbr=%s | partner.taxNbr=%s | "
                      + "diaryTaxNo=%s | viesVat=%s | base/vat/gross=%s/%s/%s | napType=%s",
                  blankToDash(d.getJournalCode()),
                  blankToDash(d.getDocumentNumber()),
                  d.getDocumentDate() == null ? "-" : d.getDocumentDate().toString(),
                  blankToDash(d.getCounterpartyName()),
                  blankToDash(d.getInvoicePartnerTaxNbr()),
                  blankToDash(d.getPartnerTaxNbr()),
                  blankToDash(d.getCounterpartyDiaryId()),
                  blankToDash(d.getCounterpartyVatNumber()),
                  d.getTotalBase(),
                  d.getTotalVat(),
                  d.getTotalGross(),
                  d.getNapDocumentType() == null ? "MISSING" : d.getNapDocumentType()));
    }
  }

  protected String formatPeriod(BgVatExportRequestDto request) {
    return String.valueOf(request.getPeriodFrom()) + " .. " + request.getPeriodTo();
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static String blankToDash(String value) {
    return value == null || value.trim().isEmpty() ? "-" : value;
  }
}
