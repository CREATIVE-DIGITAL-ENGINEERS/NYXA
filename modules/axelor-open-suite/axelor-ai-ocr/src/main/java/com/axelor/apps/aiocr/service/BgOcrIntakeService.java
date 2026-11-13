package com.axelor.apps.aiocr.service;

import com.axelor.app.AppSettings;
import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Journal;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.PaymentCondition;
import com.axelor.apps.account.db.PaymentMode;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountRepository;
import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.JournalRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.account.db.repo.PaymentConditionLineRepository;
import com.axelor.apps.account.db.repo.PaymentConditionRepository;
import com.axelor.apps.account.db.repo.PaymentModeRepository;
import com.axelor.apps.account.db.repo.TaxRepository;
import com.axelor.apps.account.service.AccountManagementAccountService;
import com.axelor.apps.account.service.config.AccountConfigService;
import com.axelor.apps.account.service.invoice.InvoiceControlService;
import com.axelor.apps.account.service.invoice.InvoiceJournalService;
import com.axelor.apps.account.service.invoice.InvoiceLineService;
import com.axelor.apps.account.service.invoice.InvoiceToolService;
import com.axelor.apps.aiaccountant.security.AiExecutionContext;
import com.axelor.apps.aiaccountant.service.AiBlockerCode;
import com.axelor.apps.aiaccounting.service.invoice.InvoiceLineUnitDefaultService;
import com.axelor.apps.aiaccounting.service.invoice.spi.AiInvoiceFieldRegistry;
import com.axelor.apps.aiocr.db.AiOcrUpload;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.db.repo.OcrInvoiceWriteGuard;
import com.axelor.apps.aiocr.service.ai.BgOcrAiCorrection;
import com.axelor.apps.aiocr.service.ai.BgOcrAiCorrectionService;
import com.axelor.apps.aiocr.service.ai.BgOcrLineRepairService;
import com.axelor.apps.aiocr.service.audit.AiOcrUploadAuditService;
import com.axelor.apps.aiocr.service.bankstatement.OcrBankStatementCreator;
import com.axelor.apps.aiocr.service.ingest.AiOcrFileHash;
import com.axelor.apps.aiocr.service.ingest.BgOcrPdfSegmenter;
import com.axelor.apps.aiocr.service.ocr.AiOcrUploadStatus;
import com.axelor.apps.aiocr.service.ocr.BgBankStatementOcrClassifier;
import com.axelor.apps.aiocr.service.ocr.BgOcrAddressParser;
import com.axelor.apps.aiocr.service.ocr.BgOcrBankDetails;
import com.axelor.apps.aiocr.service.ocr.BgOcrDocumentClassifier;
import com.axelor.apps.aiocr.service.ocr.BgOcrJournalSelector;
import com.axelor.apps.aiocr.service.ocr.BgOcrPaymentMethod;
import com.axelor.apps.aiocr.service.ocr.BgOcrReviewChecks;
import com.axelor.apps.aiocr.service.ocr.BgOcrTaxEventDate;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatMapping;
import com.axelor.apps.aiocr.service.ocr.BgOcrVatRateInference;
import com.axelor.apps.aiocr.service.ocr.BgPlaceMatcher;
import com.axelor.apps.aiocr.service.ocr.BgPlaceNormalizer;
import com.axelor.apps.aiocr.service.ocr.CompanyLegalIdentity;
import com.axelor.apps.aiocr.service.ocr.OcrExtractorRouter;
import com.axelor.apps.aiocr.service.ocr.OcrPerfTrace;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractLine;
import com.axelor.apps.aiocr.service.ocr.dto.OcrExtractResult;
import com.axelor.apps.aiocr.service.optimize.PdfOptimizationService;
import com.axelor.apps.aiocr.service.process.AiOcrProcessingExecutor;
import com.axelor.apps.aiocr.service.readiness.OcrInvoiceReadiness;
import com.axelor.apps.aiocr.service.readiness.OcrSupplierInvoiceReadinessService;
import com.axelor.apps.aiocr.service.record.OcrBlocked;
import com.axelor.apps.aiocr.service.record.OcrInvoiceTaxSummaryService;
import com.axelor.apps.aiocr.service.record.OcrRoundingLineService;
import com.axelor.apps.aiocr.service.storage.OcrFileSharingService;
import com.axelor.apps.aiocr.service.supplier.SupplierEik;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Bank;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.PartnerAddress;
import com.axelor.apps.base.db.Product;
import com.axelor.apps.base.db.repo.AddressRepository;
import com.axelor.apps.base.db.repo.BankDetailsRepository;
import com.axelor.apps.base.db.repo.BankRepository;
import com.axelor.apps.base.db.repo.CityRepository;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.CountryRepository;
import com.axelor.apps.base.db.repo.CurrencyRepository;
import com.axelor.apps.base.db.repo.PartnerAddressRepository;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.base.db.repo.ProductRepository;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.base.service.BankDetailsService;
import com.axelor.apps.base.service.PeriodService;
import com.axelor.apps.base.service.address.AddressService;
import com.axelor.apps.base.service.address.CityService;
import com.axelor.apps.base.service.tax.TaxService;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.EntityHelper;
import com.axelor.db.JPA;
import com.axelor.db.Model;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns an {@link AiOcrUpload} into a DRAFT Supplier Invoice (or a FAILED/DUPLICATE job). This is
 * the only place OCR data becomes accounting data; it reuses the suite's invoice services and never
 * validates or ventilates. See the plan doc for the full flow.
 */
@Singleton
public class BgOcrIntakeService {

  /**
   * OCR completeness flag carried on the invoice in a SEPARATE field ({@code ocrReviewStatus},
   * selection {@code ai.ocr.invoice.review.status.select}) — the stock {@code statusSelect} stays
   * Draft. TO_REVIEW = a key field is missing (Validate is disabled); COMPLETE = ready. Recomputed
   * on the invoice form's onLoad, onSave and on every change of a field it depends on.
   */
  public static final String OCR_REVIEW_TO_REVIEW = "toReview";

  public static final String OCR_REVIEW_COMPLETE = "complete";

  // The keys of missingReviewFields / reviewWarnings. They are what ocrMissingFields stores and
  // what
  // the AI tools quote, so they stay English and stable; the form shows them through
  // OcrReviewMessages, in the user's language.
  public static final String REVIEW_PARTNER = "supplier (partner)";
  public static final String REVIEW_ADDRESS = "address";
  public static final String REVIEW_CURRENCY = "currency";
  public static final String REVIEW_TOTAL = "total";
  public static final String REVIEW_BANK_DETAILS = "bank details";
  public static final String REVIEW_PAYMENT_CONDITION = "payment condition";

  private static final Logger LOG = LoggerFactory.getLogger(BgOcrIntakeService.class);

  protected final OcrExtractorRouter extractor;
  protected final AiOcrUploadRepository uploadRepo;
  protected final InvoiceRepository invoiceRepo;
  protected final PartnerRepository partnerRepo;
  protected final TaxRepository taxRepo;

  @Inject
  public BgOcrIntakeService(
      OcrExtractorRouter extractor,
      AiOcrUploadRepository uploadRepo,
      InvoiceRepository invoiceRepo,
      PartnerRepository partnerRepo,
      TaxRepository taxRepo) {
    this.extractor = extractor;
    this.uploadRepo = uploadRepo;
    this.invoiceRepo = invoiceRepo;
    this.partnerRepo = partnerRepo;
    this.taxRepo = taxRepo;
  }

  /**
   * Claim (atomically) and process one upload. Safe to call from several workers: the guarded
   * UPDATE ensures exactly one claims a QUEUED job; the rest no-op.
   *
   * <p>This method does <b>not</b> swallow exceptions raised while building the draft. If the
   * extractor or the invoice/partner build throws, the exception propagates and this transaction
   * rolls back (reverting the claim). The caller ({@link com.axelor.apps.aiocr.job.AiOcrPollJob})
   * is responsible for recording the terminal FAILED status via {@link #markFailed(Long, String)},
   * which runs in its own transaction — recording it here would fail, because a partial write
   * earlier in this doomed transaction (e.g. a rejected Partner insert) leaves the session unable
   * to flush a further save.
   */
  public void process(Long uploadId) throws Exception {
    if (!claim(uploadId)) {
      return; // someone else claimed it, or it is not QUEUED
    }
    // The claim above committed PROCESSING + extractionStartedOn in its OWN transaction, so the UI
    // sees "Processing" (and the real start time) immediately, BEFORE the long OCR work below runs
    // in this separate transaction. This is what keeps a big multi-page PDF from appearing "Queued"
    // for the whole ~minutes-long Azure call.
    processClaimed(uploadId);
  }

  /**
   * Run the OCR pipeline for an already-claimed (PROCESSING) upload. Deliberately NOT
   * {@code @Transactional}: it orchestrates short committed steps around the LONG Azure OCR call so
   * that the call runs with <b>no open transaction holding the carrier row</b>. That is what lets
   * the per-chunk progress commits below be seen live, and it is why the Refresh button no longer
   * blocks on a 10–12 minute row lock (nor collides on the optimistic version). Also the entry
   * point for the manual "Process"/"Reprocess" buttons, which claim on the request thread (button
   * hides at once) and hand the claimed id to the background executor.
   */
  public void processClaimed(Long uploadId) throws Exception {
    // A split child carries cached data from the ONE Azure call over the parent PDF — route it in
    // its own transaction (never call Azure again for a normal child).
    if (routeCachedChild(uploadId)) {
      return;
    }

    // Normalize + byte-identical dedup in a SHORT committed transaction, so the carrier row is not
    // left write-locked during the OCR call. Returns the file to OCR, or null when the upload was
    // blocked (duplicate) or has no usable file.
    long prepareStartNs = System.nanoTime();
    MetaFile source = prepareForOcr(uploadId);
    OcrPerfTrace.since("prepareMs", prepareStartNs);
    if (source == null) {
      return;
    }

    // LONG OCR — outside any transaction. Progress is committed after each Azure page-chunk (short
    // bulk updates) so the carrier's progress bar advances while extraction runs and Refresh reads
    // the latest committed value instantly.
    final Long id = uploadId;
    long ocrStartNs = System.nanoTime();
    OcrExtractResult result;
    try {
      result = extractor.extract(source, (done, total) -> persistOcrProgress(id, done, total));
    } finally {
      OcrPerfTrace.since("ocrMs", ocrStartNs);
    }

    // Route/split in its own transaction.
    long routeStartNs = System.nanoTime();
    try {
      routeExtracted(uploadId, result);
    } finally {
      OcrPerfTrace.since("routeMs", routeStartNs);
    }
  }

  /**
   * If the upload is a split/bank-statement child (carries {@code cachedOcrJson}), route it from
   * that cached single-Azure-call data in this transaction and return true; otherwise false.
   */
  @Transactional
  protected boolean routeCachedChild(Long uploadId) throws Exception {
    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null || isBlankStr(upload.getCachedOcrJson())) {
      return false;
    }
    if (AiOcrUploadStatus.SEGMENT_TYPE_BANK_STATEMENT.equals(upload.getSegmentType())) {
      processBankStatementChild(upload);
    } else {
      processSplitChild(upload);
    }
    return true;
  }

  /**
   * Losslessly normalize the manual PDF (PDFBox only) and run the L1 byte-identical dedup guard, in
   * a short committed transaction. Returns the (normalized) file to OCR, or {@code null} when the
   * upload was blocked as a duplicate or has no usable original file.
   */
  @Transactional
  protected MetaFile prepareForOcr(Long uploadId) {
    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null) {
      return null;
    }
    normalizeOriginalPdf(upload);
    if (blockIfByteIdenticalDuplicate(upload)) {
      return null;
    }
    MetaFile source = upload.getOriginalFile();
    if (source == null) {
      return null;
    }
    // The long OCR call below runs OUTSIDE this transaction. upload.getOriginalFile() can be a LAZY
    // Hibernate proxy; reading its path/name once the session has closed then throws "could not
    // initialize proxy [MetaFile#…] - no Session" — intermittently, because a proxy already
    // initialized in the persistence context happened to work while a fresh one did not. Re-fetch
    // the concrete, fully-loaded MetaFile so its (eager) path/name stay readable while detached.
    return Beans.get(com.axelor.meta.db.repo.MetaFileRepository.class).find(source.getId());
  }

  /**
   * Persist the carrier's OCR-page progress ({@code processed pages / total pages}) after an Azure
   * chunk, in its OWN transaction so it commits immediately and a Refresh reads it instantly.
   * During OCR the only writer of this row is this method (sequential, one chunk at a time, no
   * children yet), so an entity save is safe from optimistic contention AND keeps the shared
   * persistence context in sync — so the later routing save preserves the bar instead of resetting
   * it to a stale value. Kept SEPARATE from the child-record rollup ({@link
   * AiOcrCarrierService#recompute}); only advances a row that is genuinely still processing.
   */
  @Transactional
  public void persistOcrProgress(Long uploadId, int pagesDone, int pagesTotal) {
    if (uploadId == null || pagesTotal <= 0) {
      return;
    }
    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null || !AiOcrUploadStatus.PROCESSING.equals(upload.getStatusSelect())) {
      return;
    }
    upload.setProgressPercent(
        Math.max(0, Math.min(100, (int) Math.floor(100.0 * pagesDone / pagesTotal))));
    uploadRepo.save(upload);
  }

  /**
   * Map the extraction result to an Invoice/Move/Bank-Statement (or a split carrier), in its own
   * transaction after the long OCR has finished. Preserves the exact routing order: extraction
   * failure → bank statement → PDF split → invoice/move.
   */
  @Transactional
  protected void routeExtracted(Long uploadId, OcrExtractResult result) throws Exception {
    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null) {
      return;
    }
    persistMeta(upload, result);

    // Extraction failure preserves the existing FAILED behavior BEFORE any classification/routing
    // (a blank rawText on a genuinely failed extraction must not be routed anywhere).
    if (result.isExtractionFailed()) {
      fail(upload, "Extraction failed: " + String.join("; ", result.getErrors()));
      return;
    }

    // Recover the invoice number from the "Фактура" context when Azure's structured InvoiceId is
    // blank, so the duplicate key and the draft both carry it. Hardened to avoid dates/PO/refs.
    if (isBlankStr(result.getInvoiceNumber())) {
      String recovered = invoiceNoFromText(result.getRawText());
      if (recovered != null) {
        result.setInvoiceNumber(recovered);
      }
    }

    // Recover the supplier ЕИК from the text when Azure produced no VAT (common for BG ЕООД shown
    // only with "ЕИК") so the duplicate key and partner resolution both have a supplier identity.
    enrichSupplierEik(result, upload);

    // Strong bank-statement detection runs FIRST — with priority over BOTH the Phase A document
    // splitter AND invoice detection. A statement's transaction narratives routinely quote
    // "Фактура"/"Ф-ра"/"Фак.", so the document TITLE (ОТЧЕТ ПО СМЕТКА / ACCOUNT STATEMENT / BANK
    // STATEMENT / БАНКОВО ИЗВЛЕЧЕНИЕ) decides. Without this order the invoice-word-driven PDF
    // segmenter below would carve a genuine statement into bogus "invoice" children before the
    // statement flow was ever consulted. Once positively identified as a statement, invoice words
    // have no effect.
    if (routeBankStatementIfDetected(upload, result)) {
      return; // a bank statement → BankStatement record, never an Invoice or a Move
    }

    // Not a bank statement. Multi-document PDF? Segment from THIS single OCR result and split into
    // per-document children (no second Azure call). A single invoice or a lone non-invoice document
    // falls through to the normal routing below.
    if (maybeStartPdfSplit(upload, result)) {
      return;
    }

    // Language-independent routing, INDEPENDENT of field confidence. An invoice is recognized by
    // the
    // BG word "фактура" OR by strong structured evidence any supplier invoice carries in any
    // language
    // (invoice number + supplier + a positive total/line items, or an explicit international
    // "Invoice" keyword). A recognized customs/import declaration (H1 SAD — MRN etc.) is a
    // non-invoice document and routes to Move. A document with a STRONG/weak non-invoice marker
    // (bank/payment/contract/receipt) is never promoted to an invoice on foreign evidence. Anything
    // else (genuinely ambiguous) is never silently made an invoice — it stays a Move as before.
    String rawText = result.getRawText();
    BgOcrDocumentClassifier.Classification cls = BgOcrDocumentClassifier.classify(rawText);
    if (isCustomsDeclaration(rawText)) {
      // Recognized customs / import declaration (H1 SAD) — a non-invoice accounting document →
      // Move.
      routeMove(upload, result, null);
    } else if (cls == BgOcrDocumentClassifier.Classification.INVOICE
        || (cls != BgOcrDocumentClassifier.Classification.NON_INVOICE
            && hasForeignInvoiceEvidence(result))) {
      // Confidently recognized invoice (BG "фактура" or strong foreign evidence). Now decide BG vs
      // foreign SUPPLIER from strong supplier-side signals (VAT/IBAN country, corroborated address)
      // — the document language never decides this.
      if (classifySupplierOrigin(result, ourCompanyBgId(upload)) == SupplierOrigin.FOREIGN) {
        // Confident EU/foreign supplier invoice → Accounting Move (not the BG Supplier Invoice
        // flow), stamped with NAP document type = Фактура (01) for the diary.
        result.setNapDocumentType(NAP_DOC_TYPE_INVOICE);
        routeMove(
            upload,
            result,
            "EU/foreign supplier invoice — booked as a Move (NAP document type: Фактура).",
            NAP_DOC_TYPE_INVOICE);
      } else {
        // Bulgarian or unclear supplier → existing conservative Supplier Invoice flow. When no
        // supplier can be safely matched by ЕИК, routeInvoice keeps the upload Needs review with a
        // clear message (never a Move).
        routeInvoice(upload, result);
      }
    } else if (cls == BgOcrDocumentClassifier.Classification.NON_INVOICE) {
      // Confidently recognized NON-invoice accounting document (bank/payment/contract/receipt) →
      // Move, as before.
      routeMove(upload, result, null);
    } else {
      // Genuinely ambiguous / unknown document — never silently booked as a Move. Keep the upload
      // Needs review with no Invoice and no Move for a human to classify.
      upload.setInvoice(null);
      upload.setMove(null);
      upload.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
      upload.setExtractionErrors(
          "Document type could not be determined (not clearly an invoice or a recognized accounting"
              + " document). Review and classify it manually. No invoice or move was created.");
      upload.setProcessedOn(LocalDateTime.now());
      uploadRepo.save(upload);
    }
  }

  // An explicit international "invoice" title (any of several languages), as a standalone token.
  private static final Pattern INVOICE_KEYWORD_INTL =
      Pattern.compile(
          "(?<![\\p{L}])(?:invoice|tax\\s+invoice|factuur|facture|factura|fattura|rechnung)"
              + "(?![\\p{L}])|发票|發票|インボイス",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  // Recognized customs / import declaration markers (an EU SAD / H1). MRN is the near-universal
  // signal; the phrases cover BG/EN wording. None of these appear on an ordinary supplier invoice.
  private static final Pattern CUSTOMS_DECLARATION =
      Pattern.compile(
          "(?<![\\p{L}])mrn(?![\\p{L}])"
              + "|митническа\\s+декларация"
              + "|customs\\s+declaration"
              + "|единен\\s+административен\\s+документ",
          Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

  /** A recognized customs / import declaration (H1 SAD) — a non-invoice document. */
  static boolean isCustomsDeclaration(String rawText) {
    return rawText != null && CUSTOMS_DECLARATION.matcher(rawText).find();
  }

  /**
   * Strong, language-independent evidence that the document is a supplier invoice — so a genuine
   * English/EU/foreign invoice (which never contains the Bulgarian word "фактура") is still routed
   * to the Supplier Invoice flow instead of being booked as a Move. Evidence is either the
   * invoice's own structured fields (an invoice number + a supplier + a positive total or line
   * items), or an explicit international "Invoice" title corroborated by a number or a total. A
   * customs declaration (no invoice number) and a bank/payment document (handled earlier /
   * classified NON_INVOICE) do not qualify, so ambiguous documents are never silently turned into
   * invoices.
   */
  static boolean hasForeignInvoiceEvidence(OcrExtractResult r) {
    if (r == null) {
      return false;
    }
    boolean hasNumber = !isBlankStr(r.getInvoiceNumber());
    boolean hasSupplier =
        !isBlankStr(r.getSupplierName())
            || !isBlankStr(r.getSupplierVat())
            || !isBlankStr(r.getSupplierEik());
    boolean hasTotal = r.getTotalGross() != null && r.getTotalGross().signum() > 0;
    boolean hasItems = r.getLines() != null && !r.getLines().isEmpty();
    if (hasNumber && hasSupplier && (hasTotal || hasItems)) {
      return true;
    }
    String text = r.getRawText() == null ? "" : r.getRawText();
    return INVOICE_KEYWORD_INTL.matcher(text).find() && (hasNumber || hasTotal);
  }

  /** The NAP "вид на документа" internal value for an invoice (01 – Фактура). */
  static final String NAP_DOC_TYPE_INVOICE = "01";

  /** Where the SUPPLIER of a confidently-detected invoice is established from strong signals. */
  enum SupplierOrigin {
    BULGARIAN,
    FOREIGN,
    UNCLEAR
  }

  // ISO 3166-1 alpha-2 codes, plus the two pseudo-codes EU VAT numbers use (EL = Greece, XI =
  // Northern Ireland). Used only to validate a 2-letter VAT/IBAN/BIC country prefix.
  private static final Set<String> ISO_COUNTRIES = buildIsoCountries();

  private static Set<String> buildIsoCountries() {
    Set<String> s = new HashSet<>();
    for (String c : Locale.getISOCountries()) {
      s.add(c);
    }
    s.add("EL");
    s.add("XI");
    return s;
  }

  // A VAT number as a 2-letter country prefix followed by its alphanumeric body (e.g. HU12345678,
  // NL123456789B01). A plain, prefix-less ЕИК has no leading letters, so it never matches here.
  private static final Pattern VAT_COUNTRY = Pattern.compile("^([A-Z]{2})[0-9A-Z]{2,}$");

  // Country names (EN + native + BG-Cyrillic) → ISO code, for the SOFT supplier-address signal
  // only.
  // Bulgaria is included so a clearly-BG address is recognized and never treated as foreign.
  private static final Map<String, String> ADDRESS_COUNTRY = buildAddressCountry();

  private static Map<String, String> buildAddressCountry() {
    Map<String, String> m = new HashMap<>();
    putCountry(m, "BG", "BULGARIA", "БЪЛГАРИЯ");
    putCountry(m, "DE", "GERMANY", "DEUTSCHLAND", "ГЕРМАНИЯ");
    putCountry(m, "HU", "HUNGARY", "MAGYARORSZ", "УНГАРИЯ");
    putCountry(m, "NL", "NETHERLANDS", "NEDERLAND", "НИДЕРЛАНДИЯ", "ХОЛАНДИЯ");
    putCountry(m, "RO", "ROMANIA", "РУМЪНИЯ");
    putCountry(m, "FR", "FRANCE", "ФРАНЦИЯ");
    putCountry(m, "IT", "ITALY", "ITALIA", "ИТАЛИЯ");
    putCountry(m, "ES", "SPAIN", "ESPANA", "ИСПАНИЯ");
    putCountry(m, "AT", "AUSTRIA", "АВСТРИЯ");
    putCountry(m, "PL", "POLAND", "POLSKA", "ПОЛША");
    putCountry(m, "GR", "GREECE", "ГЪРЦИЯ");
    putCountry(m, "CZ", "CZECH", "ЧЕХИЯ");
    putCountry(m, "SK", "SLOVAKIA", "СЛОВАКИЯ");
    putCountry(m, "BE", "BELGIUM", "БЕЛГИЯ");
    putCountry(m, "GB", "UNITED KINGDOM", "ВЕЛИКОБРИТАНИЯ");
    putCountry(m, "IE", "IRELAND", "ИРЛАНДИЯ");
    putCountry(m, "PT", "PORTUGAL", "ПОРТУГАЛИЯ");
    putCountry(m, "SE", "SWEDEN", "ШВЕЦИЯ");
    putCountry(m, "DK", "DENMARK", "ДАНИЯ");
    // Common non-EU trading partners (a foreign supplier invoice is still a Move, not a BG one).
    putCountry(m, "CN", "CHINA", "КИТАЙ");
    putCountry(m, "TR", "TURKEY", "TURKIYE", "ТУРЦИЯ");
    putCountry(m, "CH", "SWITZERLAND", "ШВЕЙЦАРИЯ");
    putCountry(m, "US", "UNITED STATES", "USA", "САЩ");
    putCountry(m, "RS", "SERBIA", "СЪРБИЯ");
    putCountry(m, "NO", "NORWAY", "НОРВЕГИЯ");
    putCountry(m, "UA", "UKRAINE", "УКРАЙНА");
    return m;
  }

  private static void putCountry(Map<String, String> m, String code, String... names) {
    for (String n : names) {
      m.put(n, code);
    }
  }

  /** The ISO country prefix of a VAT number (HU12345678 → HU), or null when it has none. */
  static String vatCountry(String vat) {
    if (vat == null) {
      return null;
    }
    String v = vat.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    Matcher m = VAT_COUNTRY.matcher(v);
    if (!m.matches()) {
      return null;
    }
    String cc = m.group(1);
    return ISO_COUNTRIES.contains(cc) ? cc : null;
  }

  /** The ISO country of an IBAN (its first two letters), or null when it is not IBAN-shaped. */
  static String ibanCountry(String iban) {
    if (iban == null) {
      return null;
    }
    String v = iban.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    if (v.length() < 4 || !Character.isLetter(v.charAt(0)) || !Character.isLetter(v.charAt(1))) {
      return null;
    }
    // IBAN = 2-letter country + 2 check digits + BBAN.
    if (!Character.isDigit(v.charAt(2)) || !Character.isDigit(v.charAt(3))) {
      return null;
    }
    String cc = v.substring(0, 2);
    return ISO_COUNTRIES.contains(cc) ? cc : null;
  }

  /** The ISO country encoded in a BIC/SWIFT (chars 5-6), or null when absent/invalid. */
  static String bicCountry(String swift) {
    if (swift == null) {
      return null;
    }
    String v = swift.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    if (v.length() < 6 || !Character.isLetter(v.charAt(4)) || !Character.isLetter(v.charAt(5))) {
      return null;
    }
    String cc = v.substring(4, 6);
    return ISO_COUNTRIES.contains(cc) ? cc : null;
  }

  /** A recognized country in the SUPPLIER address free text (soft signal), or null. */
  private static String supplierAddressCountry(OcrExtractResult r) {
    String hay =
        ((r.getSupplierAddress() == null ? "" : r.getSupplierAddress())
                + " "
                + (r.getSupplierAddressRecipient() == null ? "" : r.getSupplierAddressRecipient()))
            .toUpperCase(Locale.ROOT);
    if (hay.trim().isEmpty()) {
      return null;
    }
    for (Map.Entry<String, String> e : ADDRESS_COUNTRY.entrySet()) {
      if (hay.contains(e.getKey())) {
        return e.getValue();
      }
    }
    return null;
  }

  /**
   * Once a document is confidently an invoice, establish whether its SUPPLIER is Bulgarian or
   * foreign — using only supplier-side signals, never the document language and never the
   * buyer/customer identity. Decisive signals are the supplier's VAT country prefix and the
   * supplier's IBAN country; the buyer being our own BG company and a non-BG BIC are corroboration
   * for the softer address signal. Contradictory identity, or no strong evidence, yields {@code
   * UNCLEAR} so routing stays conservative and never guesses.
   *
   * @param ourCompanyBgId the canonical ЕИК of the WeAccount company that owns the upload (or null)
   */
  static SupplierOrigin classifySupplierOrigin(OcrExtractResult r, String ourCompanyBgId) {
    if (r == null) {
      return SupplierOrigin.UNCLEAR;
    }
    String supVatCc = vatCountry(r.getSupplierVat());
    boolean supVatForeign = supVatCc != null && !"BG".equals(supVatCc);
    // A BG supplier VAT is either "BG…", or a prefix-less number that is a plausible BG ЕИК.
    boolean supVatBg =
        "BG".equals(supVatCc)
            || (supVatCc == null && SupplierEik.canonical(r.getSupplierVat()) != null);
    boolean supEikBg = SupplierEik.canonical(r.getSupplierEik()) != null;

    String supIbanCc = ibanCountry(r.getIban());
    boolean supIbanForeign = supIbanCc != null && !"BG".equals(supIbanCc);
    boolean supIbanBg = "BG".equals(supIbanCc);

    boolean bgIdentity = supVatBg || supEikBg || supIbanBg;
    boolean foreignIdentity = supVatForeign || supIbanForeign;

    // Contradictory supplier identity (e.g. a foreign VAT together with a BG ЕИК) → do not guess.
    if (bgIdentity && foreignIdentity) {
      return SupplierOrigin.UNCLEAR;
    }
    if (bgIdentity) {
      return SupplierOrigin.BULGARIAN;
    }
    if (foreignIdentity) {
      return SupplierOrigin.FOREIGN;
    }

    // No decisive VAT/IBAN country. Use the supplier-address country, but only when corroborated
    // (the buyer is our BG company, or a non-BG bank BIC) so ambiguous documents stay conservative.
    String addrCc = supplierAddressCountry(r);
    if (addrCc != null && !"BG".equals(addrCc)) {
      boolean buyerIsOurBgCompany =
          ourCompanyBgId != null
              && (ourCompanyBgId.equals(SupplierEik.canonical(r.getCustomerVat()))
                  || ourCompanyBgId.equals(SupplierEik.canonical(r.getCustomerEik())));
      String bicCc = bicCountry(r.getSwift());
      boolean foreignBic = bicCc != null && !"BG".equals(bicCc);
      if (buyerIsOurBgCompany || foreignBic) {
        return SupplierOrigin.FOREIGN;
      }
    }
    return SupplierOrigin.UNCLEAR;
  }

  /** The canonical ЕИК of the WeAccount company that owns the upload, or null. */
  protected String ourCompanyBgId(AiOcrUpload upload) {
    Company c = upload == null ? null : upload.getCompany();
    if (c == null) {
      c = resolveCompany(upload);
    }
    if (c == null || c.getPartner() == null) {
      return null;
    }
    String id = SupplierEik.canonical(c.getPartner().getRegistrationCode());
    return id != null ? id : SupplierEik.canonical(c.getPartner().getTaxNbr());
  }

  /**
   * When the OCR text is a bank statement (see {@link BgBankStatementOcrClassifier}), route it to
   * Bank Statements instead of an invoice/move. Uses the existing carrier/child model: a
   * <b>multi-statement</b> PDF (top-level upload) becomes a carrier that spawns one child per
   * statement (each holding only that statement's slice), while a <b>single</b> statement — or a
   * split child that is itself a statement — creates exactly one BankStatement linked to this
   * upload. Idempotent: {@code createForOwner} skips an owner that already has a statement, so
   * retries never destroy a record. Returns {@code true} when it handled the upload; a detected
   * bank statement is <b>never</b> allowed to fall through to a Move.
   */
  protected boolean routeBankStatementIfDetected(AiOcrUpload upload, OcrExtractResult result) {
    if (!BgBankStatementOcrClassifier.isBankStatement(result.getRawText())) {
      return false;
    }
    OcrBankStatementCreator creator;
    try {
      creator = Beans.get(OcrBankStatementCreator.class);
    } catch (RuntimeException e) {
      // No bank-statement backend — keep it for review, NEVER book it as a Move.
      upload.setInvoice(null);
      upload.setMove(null);
      upload.setExtractionErrors(
          "Detected as a bank statement, but no Bank Statement backend is available — review"
              + " manually (do NOT book it as a Move).");
      upload.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
      upload.setProcessedOn(LocalDateTime.now());
      uploadRepo.save(upload);
      return true;
    }

    // A top-level PDF holding SEVERAL statements → carrier + one child per statement (existing
    // carrier/child model). Only a normal top-level upload can become a carrier (never a child).
    String rawJson = result.getRawJson();
    if (upload.getImportArchive() == null && !isBlankStr(rawJson)) {
      List<int[]> ranges = safeDetectRanges(creator, rawJson);
      if (ranges.size() > 1) {
        startBankStatementSplit(upload, rawJson);
        return true;
      }
    }

    // Single statement (a lone PDF, or a split child that is a statement): one BankStatement linked
    // to THIS upload.
    upload.setInvoice(null);
    upload.setMove(null);
    Long statementId = null;
    try {
      statementId = creator.createForOwner(upload, rawJson, 1, Integer.MAX_VALUE);
    } catch (RuntimeException e) {
      LOG.warn("Bank-statement creation failed for upload #{}: {}", upload.getId(), e.getMessage());
    }
    if (statementId == null) {
      upload.setExtractionErrors(
          "Detected as a bank statement, but no Bank Statement could be created — review manually"
              + " (do NOT book it as a Move).");
      upload.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
    } else {
      upload.setExtractionErrors("Routed to Bank Statement #" + statementId + " (review).");
      upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    }
    upload.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(upload);
    return true;
  }

  private static List<int[]> safeDetectRanges(OcrBankStatementCreator creator, String rawJson) {
    try {
      List<int[]> ranges = creator.detectStatementRanges(rawJson);
      return ranges == null ? java.util.Collections.emptyList() : ranges;
    } catch (RuntimeException e) {
      return java.util.Collections.emptyList();
    }
  }

  /**
   * Convert this upload into a "bank statements" split carrier (keeps the full PDF) and hand off to
   * the background splitter, which creates one child per statement. Mirrors {@link
   * #maybeStartPdfSplit}.
   */
  private void startBankStatementSplit(AiOcrUpload upload, String rawJson) {
    upload.setInvoice(null);
    upload.setMove(null);
    upload.setSplitSourceJson(rawJson);
    // This record is a carrier after all: splitSourceJson now owns the payload persistMeta staged.
    upload.setOcrResultJson(null);
    upload.setCarrierKind(AiOcrUploadStatus.CARRIER_KIND_PDF_BANK_STATEMENT);
    upload.setStatusSelect(AiOcrUploadStatus.EXTRACTING);
    if (upload.getExtractionStartedOn() == null) {
      upload.setExtractionStartedOn(LocalDateTime.now());
    }
    if (upload.getExtractionAttemptId() == null) {
      upload.setExtractionAttemptId(java.util.UUID.randomUUID().toString());
    }
    uploadRepo.save(upload);

    final Long carrierId = upload.getId();
    JPA.em()
        .unwrap(Session.class)
        .getTransaction()
        .registerSynchronization(
            new javax.transaction.Synchronization() {
              @Override
              public void beforeCompletion() {
                // no-op
              }

              @Override
              public void afterCompletion(int status) {
                if (status == javax.transaction.Status.STATUS_COMMITTED) {
                  Beans.get(com.axelor.apps.aiocr.service.process.AiOcrProcessingExecutor.class)
                      .submitBankStatementSplit(carrierId);
                }
              }
            });
  }

  /**
   * Process a bank-statement split child: re-parse its page range from the carrier's stored OCR
   * JSON and create exactly one BankStatement linked to this child (no second Azure call).
   * Idempotent.
   */
  private void processBankStatementChild(AiOcrUpload child) {
    String carrierJson =
        child.getImportArchive() != null ? child.getImportArchive().getSplitSourceJson() : null;
    int[] range = parsePageRange(child.getCachedOcrJson());
    Long statementId = null;
    try {
      statementId =
          Beans.get(OcrBankStatementCreator.class)
              .createForOwner(child, carrierJson, range[0], range[1]);
    } catch (RuntimeException e) {
      LOG.warn("Bank-statement child #{} creation failed: {}", child.getId(), e.getMessage());
    }
    child.setInvoice(null);
    child.setMove(null);
    if (statementId == null) {
      child.setExtractionErrors("Bank statement could not be created — review manually.");
      child.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
    } else {
      child.setExtractionErrors("Routed to Bank Statement #" + statementId + " (review).");
      child.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    }
    child.setEngineUsed("split-cache");
    child.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(child);
  }

  /**
   * Read {@code {"fromPage":X,"toPage":Y}} from a child's cachedOcrJson; defaults to the whole PDF.
   */
  private static int[] parsePageRange(String json) {
    int from = 1;
    int to = Integer.MAX_VALUE;
    if (json != null) {
      java.util.regex.Matcher mf =
          java.util.regex.Pattern.compile("\"fromPage\"\\s*:\\s*(\\d+)").matcher(json);
      java.util.regex.Matcher mt =
          java.util.regex.Pattern.compile("\"toPage\"\\s*:\\s*(\\d+)").matcher(json);
      if (mf.find()) {
        from = Integer.parseInt(mf.group(1));
      }
      if (mt.find()) {
        to = Integer.parseInt(mt.group(1));
      }
    }
    return new int[] {from, to};
  }

  /**
   * Route a split child from its cached per-segment OCR result (the single Azure call over the
   * parent PDF), applying the same text recovery as a normal upload. The segment type decides the
   * target: a recognized non-invoice document → Move; an ambiguous section → invoice draft forced
   * to "To review"; otherwise a normal invoice draft.
   */
  private void processSplitChild(AiOcrUpload upload) throws Exception {
    OcrExtractResult cached =
        com.axelor.apps.aiocr.service.ocr.OcrResultJson.fromJson(upload.getCachedOcrJson());
    String segmentType = upload.getSegmentType();
    boolean invoiceSegment =
        !AiOcrUploadStatus.SEGMENT_TYPE_MOVE.equals(segmentType)
            && !AiOcrUploadStatus.SEGMENT_TYPE_REVIEW.equals(segmentType);

    OcrExtractResult result = cached;
    // Empty-invoice fix. When Azure MERGES several invoices into one documents[] entry, the segment
    // cannot be enriched from that mixed object without leaking fields, so the cached per-segment
    // result is text-only (invoice number + raw text, no supplier/totals/lines). Rather than
    // fabricate or leak values, re-OCR this invoice segment's OWN physical slice — a single clean
    // invoice — so it populates the SAME fields a separately-uploaded invoice would. OCR-once is
    // preserved when the cache is already structurally complete (Azure returned a clean per-invoice
    // document), so the extra call happens only for the merged-document case.
    if (invoiceSegment && !isStructurallyComplete(cached)) {
      OcrExtractResult fresh = null;
      try {
        fresh = extractor.extract(upload.getOriginalFile());
      } catch (RuntimeException e) {
        LOG.warn(
            "Re-OCR of split invoice slice #{} failed ({}); using the cached text-only result.",
            upload.getId(),
            e.getMessage());
      }
      if (fresh != null && !fresh.isExtractionFailed() && isStructurallyComplete(fresh)) {
        // Keep the segment's authoritative invoice number when the slice OCR did not surface one.
        if (isBlankStr(fresh.getInvoiceNumber()) && cached != null) {
          fresh.setInvoiceNumber(cached.getInvoiceNumber());
        }
        result = fresh;
      }
    }

    if (result == null) {
      fail(upload, "Split child has no usable OCR result.");
      return;
    }
    if (isBlankStr(result.getEngineUsed())) {
      result.setEngineUsed("split-cache");
    }
    persistMeta(upload, result);

    if (isBlankStr(result.getInvoiceNumber())) {
      String recovered = invoiceNoFromText(result.getRawText());
      if (recovered != null) {
        result.setInvoiceNumber(recovered);
      }
    }
    enrichSupplierEik(result, upload);

    if (AiOcrUploadStatus.SEGMENT_TYPE_MOVE.equals(segmentType)) {
      if (routeBankStatementIfDetected(upload, result)) {
        return; // a split segment that is itself a bank statement → Bank Statement, not a Move
      }
      routeMove(upload, result, null);
    } else if (AiOcrUploadStatus.SEGMENT_TYPE_REVIEW.equals(segmentType)) {
      routeInvoice(upload, result, true);
    } else {
      routeInvoice(upload, result, false);
    }
  }

  /**
   * True when an OCR result carries real structured invoice content — a supplier identity, a
   * positive total, or at least one line. A merged-document split segment that could not be
   * enriched carries only an invoice number + raw text and is NOT complete, so its physical slice
   * is re-OCR'd instead of producing a near-empty invoice.
   */
  private static boolean isStructurallyComplete(OcrExtractResult r) {
    if (r == null) {
      return false;
    }
    boolean supplier =
        !isBlankStr(r.getSupplierName())
            || !isBlankStr(r.getSupplierEik())
            || !isBlankStr(r.getSupplierVat());
    boolean totals =
        (r.getTotalGross() != null && r.getTotalGross().signum() > 0)
            || (r.getTotalBase() != null && r.getTotalBase().signum() > 0);
    boolean lines = r.getLines() != null && !r.getLines().isEmpty();
    return supplier || totals || lines;
  }

  /**
   * Whether this PDF upload must become a pdfSplit carrier rather than stay a single document.
   *
   * <p>Two rules, and which one applies depends only on where the PDF came from:
   *
   * <ul>
   *   <li><b>A direct upload</b> keeps today's rule exactly: it splits when the PDF genuinely holds
   *       more than one document ({@code needsSplit}). A directly uploaded PDF carrying one invoice
   *       stays ONE terminal record — no carrier, no child, nothing about that flow changes.
   *   <li><b>A PDF extracted from an archive</b> ({@code fromArchive}) becomes a carrier as soon as
   *       it holds an invoice at all. The archive already established a hierarchy, and the ZIP
   *       carrier -> PDF carrier -> invoice child shape has to hold for one invoice exactly as it
   *       does for five: the middle row is the extracted document, the leaf is the invoice read out
   *       of it. Without this a one-invoice PDF collapsed the middle and the leaf into a single
   *       row, so the same archive produced a two-level tree or a three-level one depending on how
   *       many invoices happened to be inside.
   * </ul>
   *
   * <p>{@code recordSegments} is the guard that keeps the promotion honest: splitting a plan that
   * would produce no child leaves a finished carrier with zero children, which the rollup reads as
   * failed. An archive entry that yields nothing to record therefore stays a plain document, and a
   * duplicate or failed entry never reaches here at all — those are created terminal by the
   * extractor and are never processed. Costs no extra OCR either way: the promotion reuses the
   * {@code rawJson} of the call already made, and each child is routed from its cached slice of it.
   *
   * <p>Pure and static so the rule can be unit-tested without Guice, JPA or an OCR result.
   */
  static boolean mustBecomeSplitCarrier(
      boolean fromArchive, boolean needsSplit, boolean hasInvoiceAnchor, int recordSegments) {
    if (recordSegments < 1) {
      return false; // nothing to make a child from; splitting would strand the carrier childless
    }
    return needsSplit || (fromArchive && hasInvoiceAnchor);
  }

  /**
   * When {@code result} (a single Azure OCR call over a PDF) contains several documents, turn this
   * upload into a "split PDF" carrier and hand off to the background splitter. Returns true when a
   * split was started (the caller must stop routing this upload).
   *
   * <p>A plain ZIP-extracted child (no {@code segmentType}, not yet a carrier) MAY become a nested
   * pdfSplit carrier here — the same multi-invoice detection a direct upload gets. Only an
   * already-split segment ({@code segmentType} set by {@link AiOcrPdfSplitService}) or a row that
   * is already itself a carrier ({@code carrierKind} set) is exempt, to avoid re-splitting or
   * re-entering. Non-PDF uploads are never split.
   *
   * <p>A PDF that came out of an archive is held to the stronger rule in {@link
   * #mustBecomeSplitCarrier}: it becomes a carrier as soon as it holds an invoice, even a single
   * one. See that method for why.
   */
  private boolean maybeStartPdfSplit(AiOcrUpload upload, OcrExtractResult result) {
    if (upload.getSegmentType() != null
        || upload.getCarrierKind() != null
        || isBlankStr(result.getRawJson())) {
      return false;
    }
    if (!isPdf(upload.getOriginalFile())) {
      return false;
    }
    BgOcrPdfSegmenter.SegmentPlan plan = new BgOcrPdfSegmenter().segment(result.getRawJson());
    if (!mustBecomeSplitCarrier(
        upload.getImportArchive() != null,
        plan.needsSplit,
        plan.hasInvoiceAnchor,
        plan.recordSegmentCount())) {
      return false;
    }
    upload.setSplitSourceJson(result.getRawJson());
    // This record is a carrier after all: splitSourceJson now owns the payload persistMeta staged.
    upload.setOcrResultJson(null);
    upload.setCarrierKind(AiOcrUploadStatus.CARRIER_KIND_PDF_SPLIT);
    upload.setStatusSelect(AiOcrUploadStatus.EXTRACTING);
    if (upload.getExtractionStartedOn() == null) {
      upload.setExtractionStartedOn(LocalDateTime.now());
    }
    if (upload.getExtractionAttemptId() == null) {
      upload.setExtractionAttemptId(java.util.UUID.randomUUID().toString());
    }
    uploadRepo.save(upload);

    // Submit AFTER this transaction commits, so the background split reads the stored
    // splitSourceJson / carrierKind (never a half-written carrier).
    final Long carrierId = upload.getId();
    JPA.em()
        .unwrap(Session.class)
        .getTransaction()
        .registerSynchronization(
            new javax.transaction.Synchronization() {
              @Override
              public void beforeCompletion() {
                // no-op
              }

              @Override
              public void afterCompletion(int status) {
                if (status == javax.transaction.Status.STATUS_COMMITTED) {
                  Beans.get(com.axelor.apps.aiocr.service.process.AiOcrProcessingExecutor.class)
                      .submitSplit(carrierId);
                }
              }
            });
    return true;
  }

  private static boolean isPdf(MetaFile file) {
    if (file == null) {
      return false;
    }
    String name = file.getFileName();
    if (name != null && name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
      return true;
    }
    String type = file.getFileType();
    return type != null && type.toLowerCase(Locale.ROOT).contains("pdf");
  }

  /**
   * Losslessly normalize/compress the uploaded PDF with Apache PDFBox (no image downsampling, no
   * JPEG recompression, no rasterization — text/vectors/fonts/images/page size/page count are all
   * preserved) and persist the normalized bytes IN PLACE as the upload's {@code originalFile}, so
   * OCR, splitting and the stored source all use the smaller normalized PDF instead of a large
   * original plus a throw-away optimized copy. The display filename is kept; {@code
   * originalSizeBytes}/{@code optimizedSizeBytes} record the before/after sizes and {@code
   * fileHash} is cleared so the byte-identical guard rehashes the persisted (normalized) bytes.
   *
   * <p>Scope: the manual single-file upload path only (and a split carrier's first pass, which
   * starts as a manual upload). Archive/split children keep their own bytes + ingest hashes. The
   * replacement is in place (same MetaFile row/path), so a later rollback in this transaction still
   * leaves a valid, equivalent PDF at the same path — never a dangling reference — and the {@code
   * optimizedSizeBytes} guard makes a retry re-run deterministically self-correct.
   */
  protected void normalizeOriginalPdf(AiOcrUpload upload) {
    if (upload.getImportArchive() != null
        || upload.getArchiveFile() != null
        || upload.getSegmentType() != null
        || !isBlankStr(upload.getCachedOcrJson())
        || upload.getOptimizedSizeBytes() != null) {
      return; // not the manual single-file path, or already normalized on a previous pass
    }
    MetaFile file = upload.getOriginalFile();
    if (!isPdf(file)) {
      return;
    }
    PdfOptimizationService optimizer = Beans.get(PdfOptimizationService.class);
    if (!optimizer.isEnabled()) {
      return;
    }
    Path src = MetaFiles.getPath(file);
    if (src == null || !Files.exists(src)) {
      return;
    }
    long normalizeStartNs = System.nanoTime();
    PdfOptimizationService.OptimizationResult r = optimizer.normalizeLossless(src);
    OcrPerfTrace.since("pdfNormalizeMs", normalizeStartNs);
    try {
      upload.setOriginalSizeBytes(r.originalBytes);
      if (r.optimized
          && r.optimizedFile != null
          && r.optimizedBytes > 0
          && r.optimizedBytes <= r.originalBytes) {
        // Replace the persisted blob IN PLACE (same MetaFile, same path, same filename). The
        // document was closed by normalizeLossless before we touch its path.
        Files.move(r.optimizedFile, src, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        file.setFileSize(r.optimizedBytes);
        Beans.get(com.axelor.meta.db.repo.MetaFileRepository.class).save(file);
        upload.setOptimizedSizeBytes(r.optimizedBytes);
        upload.setFileHash(null); // rehash the persisted normalized bytes for the L1 dedup
      } else {
        // Not smaller / disabled / could not normalize — keep the original bytes, mark processed.
        upload.setOptimizedSizeBytes(r.originalBytes);
      }
      uploadRepo.save(upload);
    } catch (Exception e) {
      LOG.warn(
          "Could not persist the normalized PDF for upload #{}: {}",
          upload.getId(),
          e.getMessage());
      upload.setOptimizedSizeBytes(r.originalBytes);
      uploadRepo.save(upload);
    } finally {
      if (r.optimizedFile != null) {
        try {
          Files.deleteIfExists(r.optimizedFile);
        } catch (IOException ignore) {
          // best-effort cleanup of the derived temp file
        }
      }
    }
  }

  /** Persist the audit/AI metadata from an extraction onto the upload record. */
  private void persistMeta(AiOcrUpload upload, OcrExtractResult result) {
    upload.setEngineUsed(result.getEngineUsed());
    upload.setConfidence(result.getConfidence());
    upload.setBlobUrl(result.getBlobUrl());
    upload.setRawExtractedJson(snapshot(result));
    // Retain the real Azure analyzeResult for a PLAIN document, so an ordinary invoice is as
    // inspectable after the fact as a split child or a carrier. Only the extractor ever sets
    // rawJson, so a split child (whose per-segment result carries none) is untouched here. If this
    // document later turns out to be a carrier, startSplit/startBankStatementSplit moves the
    // payload to splitSourceJson and clears this field, so the JSON is never stored twice.
    if (result.getRawJson() != null && !result.getRawJson().trim().isEmpty()) {
      upload.setOcrResultJson(result.getRawJson());
    }
    // Informational (non-error) note: some oversized pages were transiently optimized so the
    // document could be processed. Kept SEPARATE from extractionErrors; the stored/attached PDF is
    // unchanged. The client sees a short, jargon-free summary (no provider/limit/DPI/quality
    // wording — that stays in the INFO log); only set when optimization actually happened.
    String info = compressionInformation(result.getPageCompressions());
    if (info != null) {
      upload.setExtractionInformation(info);
    }
    // The pre-OCR PDF size metadata is owned by normalizeOriginalPdf (the lossless normalization
    // step), so only overwrite it when the extraction actually carries values — never clobber the
    // normalized before/after sizes with nulls.
    if (result.getOriginalSizeBytes() != null) {
      upload.setOriginalSizeBytes(result.getOriginalSizeBytes());
    }
    if (result.getOptimizedSizeBytes() != null) {
      upload.setOptimizedSizeBytes(result.getOptimizedSizeBytes());
    }
  }

  /**
   * Short, client-friendly summary of the transient page optimization applied so the document could
   * be processed — shown in the upload's Extraction information field. Deliberately hides all
   * internal/provider vocabulary (no OCR/Azure provider, request limits, chunking, DPI, JPEG
   * quality, or stitching — those stay in the INFO log). Returns {@code null} when nothing was
   * compressed.
   *
   * <p>Example: {@code "Pages 1, 2 and 6 were optimized for processing: 7.0→3.9 MB, 10.0→3.7 MB,
   * 5.1→3.8 MB. The original document was preserved unchanged."}
   */
  static String compressionInformation(List<OcrExtractResult.PageCompression> pages) {
    if (pages == null || pages.isEmpty()) {
      return null;
    }
    List<String> pageLabels = new ArrayList<>();
    List<String> sizeLabels = new ArrayList<>();
    for (OcrExtractResult.PageCompression p : pages) {
      pageLabels.add(String.valueOf(p.page));
      sizeLabels.add(mb1(p.originalBytes) + "→" + mb1(p.compressedBytes) + " MB");
    }
    boolean single = pages.size() == 1;
    return (single ? "Page " : "Pages ")
        + joinWithAnd(pageLabels)
        + (single ? " was" : " were")
        + " optimized for processing: "
        + String.join(", ", sizeLabels)
        + ". The original document was preserved unchanged.";
  }

  /** Join like {@code "1, 2 and 6"} (or {@code "1"} / {@code "1 and 2"}). */
  private static String joinWithAnd(List<String> items) {
    int n = items.size();
    if (n == 1) {
      return items.get(0);
    }
    if (n == 2) {
      return items.get(0) + " and " + items.get(1);
    }
    return String.join(", ", items.subList(0, n - 1)) + " and " + items.get(n - 1);
  }

  /** Megabytes with one decimal, for the client-facing size summary. */
  private static String mb1(long bytes) {
    return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
  }

  /**
   * Build + save a DRAFT supplier invoice from the extraction (duplicate-aware).
   * Complete-instead-of- fail: the draft is created even if critical fields are
   * missing/low-confidence, with a clear note on what to fill. Never validated/ventilated/posted.
   */
  protected void routeInvoice(AiOcrUpload upload, OcrExtractResult result) throws Exception {
    routeInvoice(upload, result, false);
  }

  /**
   * As {@link #routeInvoice(AiOcrUpload, OcrExtractResult)}, but {@code forceReview} always flags
   * the draft "To review" regardless of field completeness — used for an ambiguous split segment
   * that is not clearly a recognized non-invoice document (so it becomes a reviewable invoice
   * draft, never a silent Move).
   */
  /**
   * Post-OCR AI reconciliation, looked up defensively so ai-ocr keeps working when the assistant is
   * absent, disabled or failing. Never throws and never returns null: on any problem the carrier
   * holds the ORIGINAL extraction, so intake behaves exactly as it did before AI existed.
   *
   * <p>The call runs inside an explicit system scope. Intake executes on the plain thread pools in
   * {@code AiOcrProcessingExecutor} — no Shiro subject is bound — and {@code AiToolDispatcher}
   * refuses every tool when there is neither a user nor a system context. Without this wrapper the
   * correction model is told to consult {@code find_supplier} and friends and gets {@code no_user}
   * for each one, so it reasons with no ERP grounding at all.
   *
   * <p>The scope is opened at exactly this one level: {@code AiExecutionContext.callAsSystem}
   * refuses to nest, and it is confined to the resolved company so an internal caller cannot range
   * across the database just because it has no user.
   *
   * <p>If the company cannot be resolved, the AI pass is skipped entirely rather than run without
   * one. A scope naming no company would be a scope with no boundary, and there is no version of
   * that which is safe in a multi-company database — so the document keeps its deterministic
   * extraction, exactly as it would if the assistant were switched off.
   */
  protected BgOcrAiCorrection aiCorrect(
      AiOcrUpload upload, OcrExtractResult result, boolean supplierResolved) {
    try {
      Company company = resolveCompany(upload);
      if (company == null || company.getId() == null) {
        LOG.warn(
            "Upload {} has no resolvable company; skipping AI correction rather than running it"
                + " unscoped.",
            upload.getId());
        return BgOcrAiCorrection.none(result);
      }
      Long companyId = company.getId();
      return AiExecutionContext.callAsSystem(
          companyId,
          "ocr-intake",
          () ->
              Beans.get(BgOcrAiCorrectionService.class).correct(upload, result, supplierResolved));
    } catch (Exception e) {
      LOG.error("AI correction unavailable; keeping the deterministic extraction.", e);
      return BgOcrAiCorrection.none(result);
    }
  }

  /**
   * Post-draft line repair, looked up defensively in the same shape as {@link #aiCorrect}: never
   * throws, never returns null, and on any problem the draft is left exactly as built.
   *
   * <p>Runs in its own system scope, distinct from the pre-pass's, so an unattended write is
   * attributable to this stage in the audit trail.
   */
  protected BgOcrAiCorrection aiRepairLines(
      AiOcrUpload upload, Invoice draft, OcrExtractResult result) {
    try {
      OcrInvoiceReadiness readiness =
          Beans.get(OcrSupplierInvoiceReadinessService.class)
              .check(draft, readinessNotes(result, null));
      if (readiness.getLineIssues().isEmpty()) {
        return BgOcrAiCorrection.none(result);
      }
      Company company = draft.getCompany();
      if (company == null || company.getId() == null) {
        LOG.warn(
            "Draft invoice {} has no company; skipping AI line repair rather than running it"
                + " unscoped.",
            draft.getId());
        return BgOcrAiCorrection.none(result);
      }
      Long companyId = company.getId();
      return AiExecutionContext.callAsSystem(
          companyId,
          "ocr-draft-repair",
          () -> Beans.get(BgOcrLineRepairService.class).repair(draft, result, readiness));
    } catch (Exception e) {
      LOG.error("Line repair unavailable; keeping the draft as built.", e);
      return BgOcrAiCorrection.none(result);
    }
  }

  protected void routeInvoice(AiOcrUpload upload, OcrExtractResult result, boolean forceReview)
      throws Exception {
    // Direction check FIRST — before supplier matching, duplicate handling, or anything that
    // touches Invoice/Move. The extracted supplier/customer identities can say this document is
    // not a purchase at all: the company's own EIK/VAT was found on the VENDOR side (a sale this
    // company issued) or the customer identity extracted matches neither side of this company. In
    // either case nothing downstream may run — OCR intake only ever builds a SUPPLIER invoice, so
    // proceeding would silently create a wrong one. Never clears upload.invoice/upload.move: at
    // this point on a first-time upload there is nothing to clear, and on a reprocess this must not
    // detach a record from something already filed elsewhere.
    // An outgoing sale THIS company issued is handled on the sale side instead of being left for a
    // human to reroute. Answers false for every other document, so the supplier path below — and
    // the direction gate's own refusals — behave exactly as before.
    if (routeCustomerInvoiceIfOutgoing(upload, result)) {
      return;
    }
    if (!checkDirectionOrReview(upload, result)) {
      return;
    }
    // Supplier resolution is STRICTLY match-by-EIK and NEVER creates a Partner (suppliers are
    // maintained by the accountant); name/address/IBAN are never identity keys. When the EIK does
    // not resolve to exactly one existing Partner we CANNOT create the invoice draft — the stock
    // Invoice requires a partner (@NotNull) — so the upload is flagged Needs review with the
    // specific
    // reason and keeps its original document for the accountant to create/select the supplier. No
    // Partner and no Invoice are created.
    SupplierMatch supplierMatch = matchSupplier(result);

    // AI reconciliation runs HERE, after every deterministic step (including enrichSupplierEik in
    // routeExtracted) has had its chance, and BEFORE the supplier-unmatched exit below — that exit
    // creates no Invoice at all, so a hook placed after the draft is built would never see the most
    // valuable failure. Corrections are validated in Java and land on the extraction, so everything
    // downstream (buildDraft, docKey, applyOcrAddress, applyOcrBankDetails, missingReviewFields)
    // benefits from one call. Disabled by default; on any failure `ai` carries the untouched
    // result.
    BgOcrAiCorrection ai = aiCorrect(upload, result, supplierMatch.partner != null);
    result = ai.getResult();
    if (ai.isAiApplied()) {
      // Identity fields may have changed — re-resolve once, then accept whatever we get.
      supplierMatch = matchSupplier(result);
    }

    if (supplierMatch.partner == null) {
      upload.setInvoice(null);
      upload.setMove(null);
      upload.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
      upload.setExtractionErrors(supplierMatch.note);
      upload.setExtractionInformation(ai.toNotes());
      upload.setProcessedOn(LocalDateTime.now());
      uploadRepo.save(upload);
      return;
    }
    buildAndFinishInvoiceDraft(upload, result, supplierMatch.partner, ai, forceReview, true);
  }

  /**
   * Whether {@code routeInvoice} may continue toward a SUPPLIER invoice, or whether the extracted
   * identities say this document runs the other way (or nowhere this company can confirm).
   *
   * <p>This is the only place OCR intake decides direction, and it decides it before anything else
   * in {@code routeInvoice} runs — supplier matching, the AI reconciliation pass, duplicate
   * detection and {@code buildAndFinishInvoiceDraft} all assume, correctly, that a SUPPLIER invoice
   * is what is being built; nothing past this point knows how to build the other kind.
   *
   * <p>Fail-closed, in this order — an automatic supplier invoice is created only when both hold:
   *
   * <ol>
   *   <li>the company has a usable ЕИК/ДДС of its own, so it can actually be recognised on the
   *       document ({@link BgOcrReviewChecks#hasUsableIdentity});
   *   <li>the shared {@link BgOcrReviewChecks#resolveDirection} rule, comparing that identity
   *       against the canonical extracted vendor and customer identifiers ONLY, answers {@link
   *       BgOcrReviewChecks.Direction#SUPPLIER_INVOICE}.
   * </ol>
   *
   * Company-is-the-vendor keeps its existing outgoing/customer-invoice message and manual reroute
   * path ({@code OcrRerouteService.requireCustomer}); matching neither party, both parties, or an
   * unusable company identity all end as Needs review with the reason, and create nothing.
   *
   * <p>Read-only against {@code upload}/{@code result}, and does not touch {@code upload.invoice}
   * or {@code upload.move} even when it blocks — see {@link #routeInvoice}'s own note on why.
   *
   * @return true to continue routing as a supplier invoice; false when the upload has already been
   *     saved as Needs review and {@code routeInvoice} must stop
   */
  protected boolean checkDirectionOrReview(AiOcrUpload upload, OcrExtractResult result) {
    Company company = resolveCompany(upload);
    if (company == null) {
      // Nothing to compare the extracted identities against — the existing "no company resolvable"
      // failure further down (in buildDraft) is the right place for that, not this check.
      return true;
    }
    // Fail closed on a company that cannot be recognised on the document at all. Without an ЕИК/ДДС
    // of its own there is nothing to compare, and {@link BgOcrReviewChecks#resolveDirection} would
    // answer SUPPLIER_INVOICE by default — which silently treats the SELECTED company as this
    // document's buyer. A document between two other parties would then be booked as this company's
    // purchase. The identity comes ONLY from Company.partner (see CompanyLegalIdentity): it is
    // never guessed, never taken from a same-named supplier, and never filled in from the document.
    CompanyLegalIdentity identity = CompanyLegalIdentity.of(company);
    if (!identity.isUsable()) {
      return reviewDirection(upload, identity.missingDiagnostic());
    }

    BgOcrReviewChecks.Direction direction =
        BgOcrReviewChecks.resolveDirection(
            result.getSupplierEik(),
            result.getSupplierVat(),
            result.getCustomerEik(),
            result.getCustomerVat(),
            identity.registrationCode(),
            identity.taxNbr());
    if (direction == BgOcrReviewChecks.Direction.SUPPLIER_INVOICE) {
      return true;
    }

    String note;
    if (direction == BgOcrReviewChecks.Direction.CUSTOMER_INVOICE) {
      note =
          "This document identifies "
              + identity.describeCompany()
              + " as the SELLER, not the buyer — it is an outgoing/sale invoice. No supplier"
              + " invoice was created; use the 'Reroute to Customer Invoice' action on this record"
              + " to book it on the sale side.";
    } else {
      note =
          "Could not confirm which side of this document is "
              + identity.describeCompany()
              + " — the extracted identities do not clearly match either as buyer or seller. "
              + "Verify and enter manually rather than risk a wrong invoice.";
    }
    return reviewDirection(upload, note);
  }

  /**
   * Stop routing: flag the upload Needs review with {@code note} and create nothing.
   *
   * <p>The note is appended, never overwritten: an earlier pass (a previous reprocess, an AI
   * correction note) may already have written something here, and this is one more fact about the
   * upload, not a replacement of what came before.
   *
   * @return always {@code false}, so callers can {@code return reviewDirection(...)}
   */
  private boolean reviewDirection(AiOcrUpload upload, String note) {
    String existing = upload.getExtractionErrors();
    upload.setExtractionErrors(
        (existing == null || existing.trim().isEmpty()) ? note : existing + " | " + note);
    upload.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
    upload.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(upload);
    return false;
  }

  /**
   * Build the draft supplier invoice for an already-resolved supplier and finish the upload.
   *
   * <p>Split out of {@link #routeInvoice(AiOcrUpload, OcrExtractResult, boolean)} so that the
   * deterministic "supplier is known → draft exists" half of intake has exactly one implementation.
   * The chat can reach it through {@link #createDraftForSupplier(Long, Partner)} once the
   * accountant has created the supplier that was missing at intake time; that path must produce a
   * byte-for-byte ordinary OCR draft, which is only guaranteed if it runs this same code rather
   * than a second copy of it.
   *
   * @param ai what the pre-draft AI pass changed, for the audit note; {@link
   *     BgOcrAiCorrection#none} when there was no pass
   * @param runAiLineRepair false to skip the post-save AI line-repair call — used by the chat path,
   *     which runs inside a confirmed write's transaction (and its rehearsal), where a network call
   *     would be made twice and would hold the row for its duration
   */
  protected void buildAndFinishInvoiceDraft(
      AiOcrUpload upload,
      OcrExtractResult result,
      Partner partner,
      BgOcrAiCorrection ai,
      boolean forceReview,
      boolean runAiLineRepair)
      throws Exception {
    Invoice draft = buildDraft(result, partner, upload);
    String taxEventNote = applyStatedTaxEventDate(draft, result);

    Company company = draft.getCompany();
    Long companyId = company != null ? company.getId() : null;
    String normalizedNo = normalizeInvoiceNo(result.getInvoiceNumber());
    String key = docKey(result, companyId);

    if (key != null) {
      // Serialize concurrent workers/reprocesses on the same document (transaction-level advisory
      // lock on the key), THEN — holding the lock — recheck for an already-existing invoice with
      // this
      // key: a sibling upload that still links one, or a live/canceled supplier invoice for this
      // partner. This makes (re)processing idempotent: at most one invoice per key.
      lockDocKey(key);
      upload.setDocKey(key);
      Invoice existing =
          findExistingInvoiceForKey(key, upload.getId(), companyId, partner, normalizedNo);
      if (existing != null) {
        markSemanticDuplicate(upload, existing, result);
        return;
      }
    } else if (Boolean.TRUE.equals(Beans.get(InvoiceControlService.class).isDuplicate(draft))) {
      // No reliable semantic key — fall back to the stock duplicate check (unchanged behavior).
      Invoice existing = findExisting(draft);
      upload.setInvoice(existing);
      StringBuilder dupMsg = new StringBuilder("Duplicate of an existing supplier invoice");
      if (existing != null) {
        dupMsg.append(" (#").append(existing.getId());
        if (existing.getSupplierInvoiceNb() != null) {
          dupMsg.append(", No ").append(existing.getSupplierInvoiceNb());
        }
        dupMsg.append(")");
      }
      dupMsg.append(" — no draft created. Delete/cancel that invoice to re-import this document.");
      upload.setExtractionErrors(dupMsg.toString());
      upload.setStatusSelect(AiOcrUploadStatus.DUPLICATE);
      upload.setProcessedOn(LocalDateTime.now());
      uploadRepo.save(upload);
      return;
    }
    // Saved here so the lines get ids: the repair pass below addresses lines by id.
    // Intake is the trusted writer of the extracted OCR document-total columns. Flush INSIDE the
    // trusted scope so the Hibernate PreInsert fires while the marker is set (OcrInvoiceWriteGuard)
    // —
    // a flush at commit would run after the scope closed and the listener would clear the figures.
    OcrInvoiceWriteGuard.runTrusted(
        () -> {
          invoiceRepo.save(draft);
          JPA.em().flush();
        });

    // Second AI pass, now that lines exist and have ids. Only reachable once the draft is real, so
    // it cannot waste a call on a document that turned out to be a duplicate (those exits are all
    // above). It repairs line products/accounts — never amounts, never taxes.
    BgOcrAiCorrection lineRepair =
        runAiLineRepair ? aiRepairLines(upload, draft, result) : BgOcrAiCorrection.none(result);
    if (lineRepair.isAiApplied()) {
      // The readiness note written at the end of buildDraft is now stale — it still says these
      // lines have no account. Recompute it before appending anything, or the record would
      // contradict itself.
      setReadinessNotes(draft, result, null);
    }

    // Completeness gate: the stock statusSelect stays Draft; a SEPARATE ocrReviewStatus flags an
    // invoice missing a key field (partner, address, currency, total) as "To review"
    // (Validate is disabled) and the upload is flagged Needs review, so nobody validates
    // half-filled
    // data. Filling the fields + saving clears the flag (via the onSave hook) and syncs the upload.
    List<String> missing = missingReviewFields(draft);
    boolean toReview = forceReview || !missing.isEmpty();
    draft.setOcrReviewStatus(toReview ? OCR_REVIEW_TO_REVIEW : OCR_REVIEW_COMPLETE);
    draft.setOcrMissingFields(missingFieldsLabel(missing));
    // Record what AI changed (and what it refused to change) so a reviewer can always tell
    // machine-corrected fields from OCR-read ones. Null when AI did nothing — no empty notes.
    if (!ai.isEmpty()) {
      draft.setAiValidationNotes(appendNote(draft.getAiValidationNotes(), ai.toNotes()));
    }
    if (!lineRepair.isEmpty()) {
      draft.setAiValidationNotes(appendNote(draft.getAiValidationNotes(), lineRepair.toNotes()));
    }
    // After the readiness recompute above, which rewrites the notes from scratch.
    draft.setAiValidationNotes(appendNote(draft.getAiValidationNotes(), taxEventNote));
    OcrInvoiceWriteGuard.runTrusted(
        () -> {
          invoiceRepo.save(draft);
          JPA.em().flush();
        });
    upload.setInvoice(draft);
    upload.setMove(null);
    attachSourceDocument(upload, draft);
    // A split invoice may carry supporting receipts: zero or more "Стокова разписка" slices matched
    // to it by content, and/or a POS/fiscal receipt. Attach each slice to THIS invoice under its
    // own
    // file name, creating no record of its own.
    if (upload.getStockReceiptFileSet() != null) {
      for (MetaFile receipt : upload.getStockReceiptFileSet()) {
        attachExtraFile(upload, draft, receipt);
      }
    }
    attachExtraFile(upload, draft, upload.getPosReceiptFile());
    // The supplier is always known here (an unmatched EIK never reaches this method), so the
    // message
    // just lists any remaining fields the accountant must fill.
    String note;
    if (!missing.isEmpty()) {
      note = "Incomplete — open the invoice and fill: " + String.join("; ", missing) + ".";
    } else if (forceReview) {
      note = "Ambiguous document section — verify this is an invoice.";
    } else {
      note = null;
    }
    upload.setExtractionErrors(note);
    upload.setStatusSelect(toReview ? AiOcrUploadStatus.NEEDS_REVIEW : AiOcrUploadStatus.COMPLETED);
    upload.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(upload);
  }

  /**
   * Create the draft supplier invoice for an upload that never got one because no Partner matched
   * its ЕИК at intake time.
   *
   * <p>This is the "the accountant has now created the supplier" case. Intake deliberately creates
   * nothing when the ЕИК does not resolve — the stock {@code Invoice} requires a partner — and
   * leaves the upload Needs review with its document intact. Requeuing the whole pipeline would
   * mean another Azure call and an asynchronous wait; that cannot be part of a confirmed, atomic
   * chat write.
   *
   * <p>So it re-derives the extraction from the analyzeResult JSON the first call already stored
   * (see {@code AzureDocumentIntelligenceExtractor#fromStoredAnalyzeResult}) and hands it to {@link
   * #buildAndFinishInvoiceDraft} — the same method ordinary intake uses. No second Azure call, no
   * second draft-building implementation, and the resulting record is indistinguishable from one
   * built at intake.
   *
   * <p>Authorisation is the caller's: this runs both unattended and from the chat, so it checks
   * only that the operation makes structural sense. {@code SupplierInvoiceEditService} applies the
   * company scope and permission checks before calling.
   *
   * @return the created draft invoice
   * @throws IllegalStateException when the upload already has an invoice or a move, or when no
   *     stored OCR result exists to rebuild from
   */
  @Transactional
  public Invoice createDraftForSupplier(Long uploadId, Partner partner) throws Exception {
    if (partner == null) {
      throw new IllegalArgumentException(
          "A supplier is required before the invoice draft can be created.");
    }
    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null) {
      throw new IllegalArgumentException("No OCR record with id " + uploadId + ".");
    }
    if (upload.getInvoice() != null) {
      throw new IllegalStateException(
          "OCR record "
              + uploadId
              + " already has invoice "
              + upload.getInvoice().getId()
              + "; it does not need to be created.");
    }
    if (upload.getMove() != null) {
      throw new IllegalStateException(
          "OCR record "
              + uploadId
              + " was booked as an accounting Move, not a supplier invoice, so no supplier invoice"
              + " draft can be created for it.");
    }
    OcrExtractResult result = rehydrateExtraction(upload);

    // The same deterministic recovery routeExtracted runs before routing, so the rebuilt draft
    // carries the invoice number and supplier identity it would have had at intake rather than only
    // what Azure's structured fields happened to contain.
    if (isBlankStr(result.getInvoiceNumber())) {
      String recovered = invoiceNoFromText(result.getRawText());
      if (recovered != null) {
        result.setInvoiceNumber(recovered);
      }
    }
    enrichSupplierEik(result, upload);

    // The same company/direction validation intake applies, now that the identities are recovered.
    // Intake's own gate lives in routeInvoice and this path never passes through it — which is how
    // a document whose VENDOR is this company became this company's supplier invoice, the supplier
    // being a second Partner row carrying the company's own ЕИК.
    guardSupplierInvoiceCreation(upload, result, partner);

    // forceReview=false: the document was already classified as an invoice when it was first
    // routed, and nothing about creating the supplier makes that classification less certain.
    // runAiLineRepair=false: this executes inside a confirmed write's transaction (and its
    // rehearsal), where a network call would run twice and hold the row for its duration.
    buildAndFinishInvoiceDraft(
        upload, result, partner, BgOcrAiCorrection.none(result), false, false);
    LOG.info(
        "OCR record {}: draft supplier invoice {} created for supplier {} from the stored OCR"
            + " result (no new extraction).",
        uploadId,
        upload.getInvoice() == null ? null : upload.getInvoice().getId(),
        partner.getId());
    return upload.getInvoice();
  }

  /**
   * The company/direction validation for creating a supplier invoice OUTSIDE ordinary intake —
   * {@code createIfMissing} on {@code edit_supplier_invoice}, and anything else that reaches {@link
   * #createDraftForSupplier}.
   *
   * <h2>Why this exists separately from {@link #checkDirectionOrReview}</h2>
   *
   * That gate lives in {@code routeInvoice} and flags the upload Needs review. This path has no
   * upload status to move — it runs inside a caller's transaction and must REFUSE, so the caller's
   * write rolls back and nothing is created. Same rule, same shared {@link BgOcrReviewChecks}
   * decision, different failure shape. The refusals are {@link OcrBlocked} so the assistant reads
   * the kind from a code instead of the sentence.
   *
   * <p>Checks, in order — each one is something the caller could otherwise assert into existence:
   *
   * <ol>
   *   <li>the company has a legal identity of its own ({@link CompanyLegalIdentity} — from {@code
   *       Company.partner} alone, never a same-named supplier);
   *   <li>the named supplier is not the company's own linked Partner;
   *   <li>the named supplier does not carry the company's own ЕИК/ДДС on a different Partner row —
   *       this is what stops the company's legal identity, handed in as {@code supplierEik}, from
   *       being accepted as a supplier;
   *   <li>the document's direction is SUPPLIER_INVOICE for this company. CUSTOMER_INVOICE is sent
   *       to the reroute action by name; AMBIGUOUS (including "this company is neither party") is
   *       refused rather than guessed;
   *   <li>the supplier matches the vendor identity actually extracted from the document, when one
   *       was extracted at all.
   * </ol>
   *
   * <p>Editing an invoice that already exists never reaches this: {@link #createDraftForSupplier}
   * is the creation path only, and a legitimate draft stays editable exactly as before.
   *
   * @throws OcrBlocked when a supplier invoice must not be created for this document
   */
  protected void guardSupplierInvoiceCreation(
      AiOcrUpload upload, OcrExtractResult result, Partner supplier) {
    CompanyLegalIdentity identity = CompanyLegalIdentity.of(resolveCompany(upload));
    if (!identity.isUsable()) {
      throw new OcrBlocked(AiBlockerCode.MISSING_COMPANY_IDENTITY, identity.missingDiagnostic());
    }

    // (2) + (3) The company is never its own supplier — by record, then by identifier.
    if (identity.isOwnPartner(supplier)) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_A_SUPPLIER_INVOICE,
          "Partner #"
              + supplier.getId()
              + " is the Partner linked to "
              + identity.describeCompany()
              + ", so it is this company itself and cannot be the supplier on its own purchase."
              + " If this document is an invoice the company ISSUED, use the 'Reroute to Customer"
              + " Invoice' action on the OCR record instead. Nothing was created.");
    }
    if (identity.isSameParty(supplier.getRegistrationCode(), supplier.getTaxNbr())) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_A_SUPPLIER_INVOICE,
          "Partner #"
              + supplier.getId()
              + " ("
              + supplier.getFullName()
              + ") carries the ЕИК/ДДС of "
              + identity.describeCompany()
              + " itself, so it cannot be the supplier on this company's purchase — an ЕИК that is"
              + " the company's own legal identity is never a supplier identity. If this document"
              + " is an invoice the company ISSUED, use the 'Reroute to Customer Invoice' action on"
              + " the OCR record instead. Nothing was created.");
    }

    // (4) The shared direction rule, on the same identifiers intake uses.
    BgOcrReviewChecks.Direction direction =
        BgOcrReviewChecks.resolveDirection(
            result.getSupplierEik(),
            result.getSupplierVat(),
            result.getCustomerEik(),
            result.getCustomerVat(),
            identity.registrationCode(),
            identity.taxNbr());
    if (direction == BgOcrReviewChecks.Direction.CUSTOMER_INVOICE) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_A_SUPPLIER_INVOICE,
          "This document identifies "
              + identity.describeCompany()
              + " as the SELLER, not the buyer — it is an outgoing/sale invoice, not a purchase."
              + " Use the 'Reroute to Customer Invoice' action on this OCR record. No supplier"
              + " invoice was created.");
    }
    if (direction != BgOcrReviewChecks.Direction.SUPPLIER_INVOICE) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_A_SUPPLIER_INVOICE,
          "Could not confirm which side of this document is "
              + identity.describeCompany()
              + " — the extracted identities match it as neither buyer nor seller (or as both)."
              + " A supplier invoice is not created on a guess. No supplier invoice was created.");
    }

    // (5) The supplier must be the vendor the document actually names, when it names one.
    String vendorEik = SupplierEik.canonical(result.getSupplierEik());
    if (vendorEik == null) {
      vendorEik = SupplierEik.canonical(result.getSupplierVat());
    }
    if (vendorEik != null
        && !BgOcrReviewChecks.sameParty(
            supplier.getRegistrationCode(), supplier.getTaxNbr(), vendorEik, null)) {
      throw new OcrBlocked(
          AiBlockerCode.NOT_A_SUPPLIER_INVOICE,
          "The vendor read from this document has ЕИК "
              + vendorEik
              + ", but Partner #"
              + supplier.getId()
              + " ("
              + supplier.getFullName()
              + ") does not carry that ЕИК/ДДС. A supplier invoice is only created for the party"
              + " the document names as its issuer. Correct the supplier, or correct the extracted"
              + " vendor identity first. Nothing was created.");
    }
  }

  /**
   * Rebuild this upload's extraction from whichever field stored it.
   *
   * <p>Exactly one of the three is populated per record, and they hold two different shapes: a
   * plain upload and a carrier keep Azure's raw {@code analyzeResult}, while a split child keeps
   * its per-segment {@code OcrExtractResult} written by {@code OcrResultJson}. Feeding one to the
   * other's reader would silently produce an empty result, so the shape is chosen explicitly rather
   * than guessed. A bank-statement child's {@code cachedOcrJson} is only a {@code {"fromPage":..}}
   * marker and is not an extraction at all.
   */
  public OcrExtractResult rehydrateExtraction(AiOcrUpload upload) {
    String cached = upload.getCachedOcrJson();
    if (!isBlankStr(cached) && !cached.contains("\"fromPage\"")) {
      return com.axelor.apps.aiocr.service.ocr.OcrResultJson.fromJson(cached);
    }
    String raw =
        !isBlankStr(upload.getOcrResultJson())
            ? upload.getOcrResultJson()
            : upload.getSplitSourceJson();
    if (isBlankStr(raw)) {
      throw new IllegalStateException(
          "OCR record "
              + upload.getId()
              + " has no stored OCR result, so its invoice cannot be built without re-reading the"
              + " document. Run Reprocess on the record first, then try again.");
    }
    return com.axelor.apps.aiocr.service.ocr.AzureDocumentIntelligenceExtractor
        .fromStoredAnalyzeResult(raw);
  }

  /**
   * The key fields an OCR supplier invoice must have before it can be validated: partner, address,
   * currency and a positive total — what the journal entry and the VAT ledger cannot do without.
   * Used both to gate the OCR draft ("To review" vs Draft; Validate is read-only while anything is
   * missing) and, on the invoice form's onLoad/onChange/onSave, to release it once complete.
   *
   * <p>Bank details and the payment condition are NOT here: they are needed to pay the invoice, not
   * to book it, so a missing one is a {@link #reviewWarnings} entry and never locks Validate.
   */
  public static List<String> missingReviewFields(Invoice invoice) {
    List<String> missing = new ArrayList<>();
    if (invoice == null) {
      return missing;
    }
    if (invoice.getPartner() == null) {
      missing.add(REVIEW_PARTNER);
    }
    if (invoice.getAddress() == null) {
      missing.add(REVIEW_ADDRESS);
    }
    if (invoice.getCurrency() == null) {
      missing.add(REVIEW_CURRENCY);
    }
    java.math.BigDecimal gross = invoice.getInTaxTotal();
    if (gross == null || gross.signum() <= 0) {
      missing.add(REVIEW_TOTAL);
    }
    return missing;
  }

  /**
   * What an OCR supplier invoice still lacks for paying it, as opposed to booking it: bank details
   * (only for a payment mode that settles through a bank account, see {@link
   * #paymentRequiresBankDetails}) and the payment condition. Shown on the form as a warning; never
   * part of {@link #missingReviewFields}, so it never makes the draft "To review".
   */
  public static List<String> reviewWarnings(Invoice invoice) {
    List<String> warnings = new ArrayList<>();
    if (invoice == null) {
      return warnings;
    }
    if (paymentRequiresBankDetails(invoice) && invoice.getBankDetails() == null) {
      warnings.add(REVIEW_BANK_DETAILS);
    }
    if (invoice.getPaymentCondition() == null) {
      warnings.add(REVIEW_PAYMENT_CONDITION);
    }
    return warnings;
  }

  /**
   * Bank details are needed ONLY for payment options that settle through a bank account: an
   * outgoing bank transfer ({@code Изходящ банков превод}) or a SEPA direct debit ({@code SEPA
   * Директен дебит}). Card / cash / other / fiscal-cash options — including a receipt-style invoice
   * paid {@code Плащане с кредитна карта} — need no bank account, so a missing IBAN is not even a
   * warning for them. When no payment option is chosen yet, the account is assumed to be needed.
   */
  public static boolean paymentRequiresBankDetails(Invoice invoice) {
    PaymentMode paymentMode = invoice == null ? null : invoice.getPaymentMode();
    Integer type = paymentMode == null ? null : paymentMode.getTypeSelect();
    if (type == null) {
      return true;
    }
    return type == PaymentModeRepository.TYPE_TRANSFER || type == PaymentModeRepository.TYPE_DD;
  }

  /**
   * Comma-joined label of the missing fields for the invoice OCR panel; null when nothing missing.
   */
  public static String missingFieldsLabel(List<String> missing) {
    return missing == null || missing.isEmpty() ? null : String.join(", ", missing);
  }

  /**
   * Keep the linked upload's status in sync with the invoice's completeness after the user edits an
   * OCR invoice: Completed when nothing is missing, Needs review otherwise. Never touches OCR or
   * creates records.
   */
  @Transactional
  public void syncUploadReview(Long invoiceId, boolean complete) {
    if (invoiceId == null) {
      return;
    }
    // Non-unique link: an invoice can carry several uploads (a re-scan, a duplicate, a page split
    // out of a ZIP). The newest is the one intake last worked on and the only one whose review
    // status still describes this invoice; the superseded ones keep the status they ended on.
    AiOcrUpload upload =
        uploadRepo
            .all()
            .filter("self.invoice.id = :id")
            .bind("id", invoiceId)
            .order("-id")
            .fetchOne();
    if (upload == null) {
      return;
    }
    if (complete) {
      upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
      upload.setExtractionErrors(null);
    } else {
      upload.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
    }
    uploadRepo.save(upload);
  }

  /**
   * Guarantee a bank account selected/created on a supplier invoice belongs to the invoice partner:
   * a freshly created BankDetails (via "+ Create") has no owner, so we set it to the partner. The
   * dropdown domain already restricts EXISTING choices to the partner's own accounts.
   */
  @Transactional
  public void ensureBankDetailsPartner(Long bankDetailsId, Long partnerId) {
    if (bankDetailsId == null || partnerId == null) {
      return;
    }
    BankDetails bd = Beans.get(BankDetailsRepository.class).find(bankDetailsId);
    Partner partner = partnerRepo.find(partnerId);
    if (bd != null && partner != null && bd.getPartner() == null) {
      bd.setPartner(partner);
      Beans.get(BankDetailsRepository.class).save(bd);
    }
  }

  /**
   * Build + save a DRAFT accounting Move from the extraction; {@code note} is optional record text.
   */
  protected void routeMove(AiOcrUpload upload, OcrExtractResult result, String note) {
    routeMove(upload, result, note, null);
  }

  /**
   * As {@link #routeMove(AiOcrUpload, OcrExtractResult, String)}, but also stamps the BG
   * localization NAP document type (вид на документа) on the Move when {@code napDocTypeSelect} is
   * given — used to book a confident EU/foreign supplier invoice as a Move with NAP type = Фактура.
   * The field is added to {@code Move} by the bg-localization module (not a compile dependency of
   * ai-ocr), so it is set reflectively via {@link Mapper}; if it is absent at runtime (e.g. a build
   * without bg-localization) the stamp is skipped without failing the routing.
   */
  protected void routeMove(
      AiOcrUpload upload, OcrExtractResult result, String note, String napDocTypeSelect) {
    Move move = buildMove(result, upload);
    if (napDocTypeSelect != null) {
      setMoveNapDocumentType(move, napDocTypeSelect);
      move.setDescription(
          truncateTo255(
              "AI-OCR EU/foreign supplier invoice booked as a Move"
                  + (upload.getFileName() != null ? ": " + upload.getFileName() : "")
                  + " (NAP document type: Фактура). Review the VAT treatment (протокол/ВОП) before"
                  + " posting."));
    }
    Beans.get(MoveRepository.class).save(move);
    upload.setInvoice(null);
    upload.setMove(move);
    attachSourceDocument(upload, move);
    upload.setExtractionErrors(note);
    upload.setStatusSelect(AiOcrUploadStatus.COMPLETED);
    upload.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(upload);
  }

  /**
   * Set {@code Move.napDocumentTypeSelect} (a bg-localization field) reflectively so ai-ocr need
   * not depend on bg-localization. No-op with a warning when the property is absent at runtime.
   */
  private static void setMoveNapDocumentType(Move move, String napDocTypeSelect) {
    try {
      com.axelor.db.mapper.Mapper.of(Move.class)
          .set(move, "napDocumentTypeSelect", napDocTypeSelect);
    } catch (RuntimeException e) {
      LOG.warn(
          "Could not set NAP document type on the OCR move (field not available at runtime): {}",
          e.toString());
    }
  }

  /** The BUYER of an outgoing document, or the reason it could not be established. */
  public static final class CustomerResolution {

    private final Partner customer;
    private final String refusal;

    private CustomerResolution(Partner customer, String refusal) {
      this.customer = customer;
      this.refusal = refusal;
    }

    static CustomerResolution of(Partner customer) {
      return new CustomerResolution(customer, null);
    }

    static CustomerResolution refused(String reason) {
      return new CustomerResolution(null, reason);
    }

    public boolean isResolved() {
      return customer != null;
    }

    public Partner customer() {
      return customer;
    }

    /** The user-facing reason, or null when resolved. */
    public String refusal() {
      return refusal;
    }
  }

  /**
   * The BUYER of a sale this company issued, or a refusal — the ONE implementation both the
   * automatic intake and the manual "Reroute to Customer Invoice" button use, so the two can never
   * disagree about who may be invoiced.
   *
   * <p>Fail-closed at every step, in this order — a customer invoice is only ever created when all
   * four hold:
   *
   * <ol>
   *   <li>this company has a readable ЕИК/VAT of its own, taken ONLY from the Partner linked by
   *       {@code Company.partner} ({@link CompanyLegalIdentity}) — never from a supplier record
   *       that happens to share its name or its ЕИК; nothing to compare against otherwise;
   *   <li>the document's VENDOR side is this company — decided by the shared {@link
   *       BgOcrReviewChecks#resolveDirection} rule, which also rejects "company on both sides" and
   *       "a customer was extracted that is nobody we know";
   *   <li>the buyer has a readable ЕИК/VAT of its own, taken ONLY from the customer block — never
   *       from the vendor block, and never from a name;
   *   <li>that ЕИК resolves to exactly one partner <b>of this company</b>. Zero, several, or the
   *       company's own partner → refused. No partner is ever created.
   * </ol>
   *
   * @param company the company the document is being filed under, resolved by the caller
   * @param partnerLookup the company-scoped ЕИК lookup ({@link #matchPartnersForCompany}), passed
   *     in so each caller keeps its own test seam while sharing this logic
   */
  public CustomerResolution resolveCustomerForSale(
      Company company,
      OcrExtractResult result,
      BiFunction<String, Long, List<Partner>> partnerLookup) {
    // The identity comes ONLY from Company.partner — never from a supplier record that shares the
    // company's name or even its ЕИК. The diagnostic names the exact record to correct, because
    // "the active company has no EIK/VAT configured" left an administrator looking at a Suppliers
    // list that did show the number and no way to tell why it did not count.
    CompanyLegalIdentity identity = CompanyLegalIdentity.of(company);
    if (!identity.isUsable()) {
      return CustomerResolution.refused(identity.missingDiagnostic() + " Nothing was changed.");
    }
    Partner companyPartner = identity.partner();
    String companyRegistration = identity.registrationCode();
    String companyVat = identity.taxNbr();

    // (2) The document's VENDOR must be this company — the literal EIK/VAT comparison.
    List<String> ourIds = canonicalIdList(companyRegistration, companyVat);
    List<String> vendorIds = canonicalIdList(result.getSupplierEik(), result.getSupplierVat());
    if (Collections.disjoint(ourIds, vendorIds)) {
      return CustomerResolution.refused(
          "This document does not identify "
              + company.getName()
              + " as the issuer/vendor (vendor EIK/VAT read: "
              + describeIdPair(result.getSupplierEik(), result.getSupplierVat())
              + ", this company: "
              + describeIdPair(companyRegistration, companyVat)
              + "). A customer invoice is only created when this company is the seller. Nothing was"
              + " changed.");
    }

    // (3) The buyer identity comes ONLY from the customer block — never the vendor's, never a name.
    String customerEik = SupplierEik.canonical(result.getCustomerEik());
    if (customerEik == null) {
      customerEik = SupplierEik.canonical(result.getCustomerVat());
    }
    if (customerEik == null) {
      return CustomerResolution.refused(
          "No customer EIK/VAT could be read from this document. A customer invoice is never"
              + " created from a name alone, and customers are never created automatically."
              + " Nothing was changed.");
    }
    if (ourIds.contains(customerEik)) {
      return CustomerResolution.refused(
          "This document carries "
              + company.getName()
              + "'s own EIK/VAT on BOTH sides, so who issued it cannot be established. Nothing was"
              + " changed.");
    }

    // The existing shared direction rule, as the final cross-check on the same identifiers.
    if (BgOcrReviewChecks.resolveDirection(
            result.getSupplierEik(),
            result.getSupplierVat(),
            result.getCustomerEik(),
            result.getCustomerVat(),
            companyRegistration,
            companyVat)
        != BgOcrReviewChecks.Direction.CUSTOMER_INVOICE) {
      return CustomerResolution.refused(
          "The identities on this document do not consistently show "
              + company.getName()
              + " as the seller. Nothing was changed.");
    }

    // (4) Exactly one partner of THIS company, and never the company itself.
    List<Partner> matches = partnerLookup.apply(customerEik, company.getId());
    if (matches.size() > 1) {
      return CustomerResolution.refused(
          "Several partners share EIK " + customerEik + "; pick the customer manually.");
    }
    if (matches.isEmpty()) {
      // Deliberately says "available to", not "does not exist": a Partner with this ЕИК may well
      // exist under another company, and confirming that from here would report a record this
      // company is not allowed to see. What the accountant can act on is stated instead — the two
      // things that make a Partner usable here, and that nothing is created automatically.
      return CustomerResolution.refused(
          "No partner with EIK "
              + customerEik
              + " is available to "
              + identity.describeCompany()
              + ", so the buyer on this document cannot be resolved. A partner is available to a"
              + " company when its 'Companies' set includes that company or is empty (shared)."
              + " Open Application Config → Referential → Partners to check or add the buyer — no"
              + " customer is ever created automatically. Nothing was changed.");
    }
    Partner customer = matches.get(0);
    if (companyPartner != null
        && companyPartner.getId() != null
        && companyPartner.getId().equals(customer.getId())) {
      return CustomerResolution.refused(
          "The buyer read from this document is the active company itself; it cannot be invoiced to"
              + " itself. Nothing was changed.");
    }
    return CustomerResolution.of(customer);
  }

  /** The company-scoped ЕИК lookup both customer-invoice entry points resolve the buyer with. */
  protected List<Partner> matchPartnersForCompany(String canonicalEik, Long companyId) {
    return SupplierEik.matchForCompany(partnerRepo, canonicalEik, companyId).fetch();
  }

  /** The canonical ЕИК forms of one party's two identifiers, blanks dropped. */
  private static List<String> canonicalIdList(String eik, String vat) {
    List<String> ids = new ArrayList<>();
    for (String raw : Arrays.asList(eik, vat)) {
      String canonical = SupplierEik.canonical(raw);
      if (canonical != null && !ids.contains(canonical)) {
        ids.add(canonical);
      }
    }
    return ids;
  }

  private static String describeIdPair(String eik, String vat) {
    String value = SupplierEik.canonical(eik);
    if (value == null) {
      value = SupplierEik.canonical(vat);
    }
    return value == null ? "none" : value;
  }

  /**
   * Take over routing when the document is a sale THIS company issued, instead of leaving it for a
   * human to press "Reroute to Customer Invoice".
   *
   * <p>Runs as the first step of {@link #routeInvoice}, before {@link #checkDirectionOrReview}, and
   * answers {@code false} for everything that is not an outgoing invoice — so the supplier path
   * below it, and the direction gate's own messages, are untouched. Everything it does on the
   * outgoing side is the SAME production code the manual reroute runs: {@link
   * #recoverIssuerEikForCustomerReroute} for the identity Azure did not return, {@link
   * #resolveCustomerForSale} for the buyer, and {@link #routeCustomerInvoice} for the draft.
   *
   * @return true when this document was handled here (a draft was created, or the upload was
   *     flagged Needs review with the reason) and {@code routeInvoice} must stop
   */
  protected boolean routeCustomerInvoiceIfOutgoing(AiOcrUpload upload, OcrExtractResult result)
      throws Exception {
    Company company = resolveCompany(upload);
    if (company == null) {
      return false; // no identity to compare against; the existing failures own that case
    }
    CompanyLegalIdentity identity = CompanyLegalIdentity.of(company);
    if (!identity.isUsable()) {
      return false; // checkDirectionOrReview writes the "repair this record" diagnostic
    }
    List<String> ourIds = canonicalIdList(identity.registrationCode(), identity.taxNbr());

    // (1) Azure fills the vendor block only from VendorTaxId, which a BG invoice printing just
    // "ЕИК …" / "ДДС № BG…" leaves empty. Recover it — but only when a buyer WAS read and that
    // buyer is somebody else, so this can never fabricate a vendor for an ordinary purchase.
    if (isBlankStr(result.getSupplierEik()) && isBlankStr(result.getSupplierVat())) {
      String buyerId = SupplierEik.canonical(result.getCustomerEik());
      if (buyerId == null) {
        buyerId = SupplierEik.canonical(result.getCustomerVat());
      }
      if (buyerId != null && !ourIds.contains(buyerId)) {
        recoverIssuerEikForCustomerReroute(result);
      }
    }

    if (BgOcrReviewChecks.resolveDirection(
            result.getSupplierEik(),
            result.getSupplierVat(),
            result.getCustomerEik(),
            result.getCustomerVat(),
            identity.registrationCode(),
            identity.taxNbr())
        != BgOcrReviewChecks.Direction.CUSTOMER_INVOICE) {
      return false; // incoming, third-party or ambiguous — unchanged behaviour below
    }

    // (6) Idempotency: a reprocess of a record that already produced its invoice must never build a
    // second one. The supplier path gets this from the docKey lock; on the sale side the upload's
    // own link is the reliable fact, because the number printed on the document is the issuer's.
    if (upload.getInvoice() != null) {
      LOG.info(
          "OCR record {}: outgoing document already linked to invoice {} — no second draft created.",
          upload.getId(),
          upload.getInvoice().getId());
      return true;
    }

    CustomerResolution buyer =
        resolveCustomerForSale(company, result, this::matchPartnersForCompany);
    if (!buyer.isResolved()) {
      reviewDirection(upload, buyer.refusal());
      return true;
    }
    routeCustomerInvoice(
        upload,
        result,
        buyer.customer(),
        "Recognised as an outgoing sale and booked as a DRAFT customer invoice — review it before"
            + " validating.");
    return true;
  }

  /**
   * Build + save a DRAFT <b>customer (sale)</b> invoice from the extraction, for the EXPLICIT
   * manual reroute only.
   *
   * <p>Automatic intake never calls this: {@link #checkDirectionOrReview} still refuses to turn a
   * document into a customer invoice on its own, because deciding that this company is the seller
   * from OCR evidence is exactly the guess that must not be made silently. A human pressing
   * "Reroute to Customer Invoice" has made that decision, and this method carries it out.
   *
   * <p>It reuses {@link #buildDraft} — one draft-building implementation for every OCR invoice —
   * but it does <b>not</b> simply flip {@code operationTypeSelect} afterwards. Every value
   * buildDraft derives from "the counterparty" would otherwise have been derived from the VENDOR
   * block, which on a sale this company issued is <em>this company itself</em>. So the extraction's
   * counterparty side is swapped to the BUYER first ({@link #swapCounterpartyToCustomerSide}), and
   * the values that are genuinely direction-dependent are recomputed afterwards, in this order:
   *
   * <ol>
   *   <li>operation type = client sale — set BEFORE every stock service below, because each of them
   *       reads it to decide the direction;
   *   <li>the number printed on the document is NOT a supplier invoice number: a sale invoice's own
   *       number is issued by this company at ventilation, so it is moved to the external
   *       reference;
   *   <li>journal: re-resolved by the stock {@link InvoiceJournalService} (customer sales journal),
   *       replacing the {@code PCH} purchase journal buildDraft set;
   *   <li>line accounts: re-resolved on the SALE side through the same stock {@link
   *       AccountManagementAccountService} call {@link #resolveLineProductAndAccount} uses, and
   *       cleared when the product configures none — a purchase account must never survive here;
   *   <li>line taxes: re-resolved from the BG <b>sale</b> codes ({@link
   *       BgOcrVatMapping#saleTaxCodeForRate}) and the VAT summary rebuilt from them — an input-VAT
   *       code on a sale would misstate the VAT ledger;
   *   <li>payment mode and condition: re-asked from the stock {@code InvoiceToolService}, which
   *       picks the IN side once the operation type is a sale;
   *   <li>bank details: reset to the CUSTOMER's own default, so nothing selected from the
   *       document's IBAN (which belongs to this company, the issuer) can remain.
   * </ol>
   *
   * <p>The draft is ALWAYS left "To review" and the upload Needs review — a rerouted sale invoice
   * has been assembled from a document this system read as a purchase, so nothing here may be
   * treated as ready to validate.
   *
   * @param customer the buyer, already resolved and verified by the caller ({@code
   *     OcrRerouteService}): matched company-scoped by ЕИК, never by name, never this company
   */
  protected void routeCustomerInvoice(AiOcrUpload upload, OcrExtractResult result, Partner customer)
      throws Exception {
    routeCustomerInvoice(
        upload,
        result,
        customer,
        "Rerouted to a DRAFT customer (sale) invoice — review it before validating.");
  }

  /**
   * As {@link #routeCustomerInvoice(AiOcrUpload, OcrExtractResult, Partner)}, but with the note the
   * record carries — the only thing that differs between the manual reroute and the automatic
   * outgoing intake, which must otherwise run byte-for-byte the same code.
   */
  protected void routeCustomerInvoice(
      AiOcrUpload upload, OcrExtractResult result, Partner customer, String note) throws Exception {
    if (customer == null) {
      throw new IllegalArgumentException(
          "A customer is required before the customer invoice draft can be created.");
    }
    Company company = resolveCompany(upload);
    Partner companyPartner = company == null ? null : company.getPartner();
    if (companyPartner != null
        && companyPartner.getId() != null
        && companyPartner.getId().equals(customer.getId())) {
      // Defense in depth: the caller already refuses this, and it must never become possible to
      // invoice the company to itself through a second entry point.
      throw new IllegalArgumentException(
          "The customer of a sale invoice cannot be the issuing company itself.");
    }

    String buyerAddress = result.getCustomerAddress();
    swapCounterpartyToCustomerSide(result, companyPartner);

    Invoice draft = buildDraft(result, customer, upload);
    draft.setDeliveryAddressStr(buyerAddress);
    applySaleDirection(draft, result, customer);
    OcrInvoiceWriteGuard.runTrusted(
        () -> {
          invoiceRepo.save(draft);
          JPA.em().flush();
        });

    upload.setInvoice(draft);
    upload.setMove(null);
    attachSourceDocument(upload, draft);
    upload.setExtractionErrors(note);
    upload.setStatusSelect(AiOcrUploadStatus.NEEDS_REVIEW);
    upload.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(upload);
  }

  /**
   * Turn a draft that {@link #buildDraft} produced in its purchase shape into a correct SALE draft.
   *
   * <p>Split out of {@link #routeCustomerInvoice} so the direction switch is one testable unit: it
   * performs steps (1)–(7) of that method's contract, in that exact order, because every stock
   * service it calls reads {@code operationTypeSelect} to decide which side of the transaction it
   * is defaulting for. Each step either resolves a sale-side value or clears the purchase-side one
   * — nothing incompatible is left behind for a reviewer to notice.
   */
  protected void applySaleDirection(Invoice draft, OcrExtractResult result, Partner customer) {
    // (1) Direction first: every stock service called below reads the operation type.
    draft.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_CLIENT_SALE);
    // (2) The document number is the SELLER's number, which on this invoice is ours-to-be-issued.
    String documentNo = draft.getSupplierInvoiceNb();
    draft.setSupplierInvoiceNb(null);
    if (!isBlankStr(documentNo)) {
      draft.setExternalReference(truncateTo255(documentNo));
    }
    draft.setPartnerTaxNbr(firstNonBlank(customer.getTaxNbr(), customer.getRegistrationCode()));

    List<String> notes = new ArrayList<>();
    // (3) Sales journal, from the company's AccountConfig via the stock resolver. Cleared first, so
    // the PCH purchase journal buildDraft set can never survive when no sales journal resolves.
    draft.setJournal(null);
    if (draft.getInTaxTotal() != null) {
      try {
        draft.setJournal(Beans.get(InvoiceJournalService.class).getJournal(draft));
      } catch (Exception e) {
        draft.setJournal(null);
      }
    }
    if (draft.getJournal() == null) {
      notes.add(
          "No customer sales journal could be resolved for this company; set the journal manually.");
    }
    // (4) + (5) Sale-side accounts and output VAT, then the VAT summary rebuilt from them.
    reresolveSaleLineAccounts(draft, notes);
    reresolveSaleLineTaxes(draft, result, notes);
    syncTaxSummary(draft, notes);
    // (6) Direction-dependent payment defaults: an OUT payment mode read from the document
    // describes a purchase and must not stay on a sale invoice.
    draft.setPaymentMode(null);
    try {
      draft.setPaymentMode(InvoiceToolService.getPaymentMode(draft));
    } catch (Exception e) {
      // no deterministic payment mode for this direction — the review note below covers it
    }
    try {
      PaymentCondition condition = InvoiceToolService.getPaymentCondition(draft);
      if (condition != null) {
        draft.setPaymentCondition(condition);
      }
    } catch (Exception e) {
      // keep whatever buildDraft resolved
    }
    // (7) The two bank-account fields, with their stock meanings kept straight.
    //
    // Invoice.bankDetails is the PARTNER's account in stock Axelor for BOTH directions — its form
    // domain is built by BankDetailsDomainServiceAccount from the partner's own active bank details
    // and its default is the partner's default account. It is NOT the payment destination of a
    // sale. Here it is left EMPTY on purpose: the account printed on this document belongs to the
    // ISSUER (this company), so whatever applyOcrBankDetails matched must not survive, and the
    // buyer's own default account is not something this document evidences either. Stock only
    // requires the field for a client invoice paid by direct debit, and the draft is To review.
    //
    // Invoice.companyBankDetails is where the money is received. It is resolved by the stock
    // BankDetailsService for this company, the (now IN) payment mode and this partner — which
    // restricts it to accounts the payment mode authorises for the company — replacing the blind
    // company default enrichHeader set. Null when stock cannot safely choose one; never guessed.
    draft.setBankDetails(null);
    draft.setCompanyBankDetails(resolveCompanyBankDetails(draft));
    if (draft.getCompanyBankDetails() == null) {
      notes.add(
          "No company bank account could be resolved for this payment mode; set the company bank"
              + " account manually before validating.");
    }
    notes.add(
        "Created by manual rerouting from an OCR document read as a purchase. Verify the customer,"
            + " the invoice/tax dates, the address, the journal, the line accounts and the VAT"
            + " treatment before validating.");

    draft.setOcrReviewStatus(OCR_REVIEW_TO_REVIEW);
    draft.setOcrMissingFields(missingFieldsLabel(missingReviewFields(draft)));
    draft.setAiValidationNotes(appendNote(draft.getAiValidationNotes(), String.join(" ", notes)));
  }

  /**
   * The company bank account a sale is received on, from the stock {@link BankDetailsService}
   * resolution used by ordinary Axelor invoices ({@code getDefaultCompanyBankDetails}): the account
   * on the partner's accounting situation when the payment mode authorises it for this company,
   * else the single authorised account, else nothing. Returns {@code null} rather than a guess —
   * including when the service is unavailable — and never an account of the partner's.
   *
   * <p>Only ever called on the customer-reroute path; the supplier path keeps the company default
   * {@link #enrichHeader} sets, unchanged. Overridable as a test seam.
   */
  protected BankDetails resolveCompanyBankDetails(Invoice invoice) {
    try {
      return Beans.get(BankDetailsService.class)
          .getDefaultCompanyBankDetails(
              invoice.getCompany(),
              invoice.getPaymentMode(),
              invoice.getPartner(),
              invoice.getOperationTypeSelect());
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * Put the BUYER on the extraction's counterparty ("supplier") side, and this company on its
   * customer side, before a customer invoice is built from it.
   *
   * <p>This is the correctness core of the customer reroute. {@link #buildDraft} reads the {@code
   * supplier*} fields as "the party this invoice is with" — partner VAT number, the address it
   * resolves/creates for the invoice, the IBAN it looks up, the identity cross-check. On a sale
   * this company issued, that block holds <b>this company</b>, so feeding it unchanged would put
   * our own address and our own bank account on an invoice whose partner is the buyer. Swapping
   * first is what makes reuse of buildDraft correct instead of merely convenient; flipping fields
   * afterwards could not undo an address row already created from the wrong block.
   *
   * <p>The IBAN/SWIFT are cleared rather than swapped: the account printed on a sale invoice is the
   * issuer's, and the buyer's account is simply not on the document. Azure has no structured
   * customer address sub-fields, so those are cleared too — {@code applyOcrAddress} then falls back
   * to parsing the buyer address text, and leaves the address empty when it cannot (never
   * invented).
   *
   * <p>Mutates {@code result} in place: the only caller is the manual reroute, whose extraction is
   * a private, transient rehydration that is discarded when the transaction ends.
   */
  protected void swapCounterpartyToCustomerSide(OcrExtractResult result, Partner companyPartner) {
    String buyerName = result.getCustomerName();
    String buyerEik = result.getCustomerEik();
    String buyerVat = result.getCustomerVat();
    String buyerAddress = result.getCustomerAddress();
    String buyerAddressRecipient = result.getCustomerAddressRecipient();

    result.setSupplierName(buyerName);
    result.setSupplierEik(buyerEik);
    result.setSupplierVat(buyerVat);
    result.setSupplierAddress(buyerAddress);
    result.setSupplierAddressRecipient(buyerAddressRecipient);
    result.setSupplierAddrCity(null);
    result.setSupplierAddrStreet(null);
    result.setSupplierAddrNumber(null);
    result.setSupplierAddrZip(null);
    result.setIban(null);
    result.setSwift(null);

    // The company is the other side now, so the identity cross-check inside buildDraft compares the
    // real parties instead of raising a bogus "vendor/customer swap" warning on every reroute.
    result.setCustomerName(companyPartner == null ? null : companyPartner.getName());
    result.setCustomerEik(companyPartner == null ? null : companyPartner.getRegistrationCode());
    result.setCustomerVat(companyPartner == null ? null : companyPartner.getTaxNbr());
    result.setCustomerAddress(null);
    result.setCustomerAddressRecipient(null);
  }

  /**
   * Replace every line's purchase (input VAT) tax with its BG <b>sale</b> counterpart, resolved
   * through the same stock {@link TaxService#getTaxLine} call the purchase path uses.
   *
   * <p>A line whose rate has no mapped sale code, or whose sale tax/version is not configured in
   * this database, is left with NO tax rather than the input-VAT one {@link #buildInvoiceLine} set:
   * an output transaction carrying a purchase VAT code posts to the wrong side of the VAT ledger,
   * which is exactly the kind of error nobody spots on review.
   */
  protected void reresolveSaleLineTaxes(
      Invoice invoice, OcrExtractResult result, List<String> notes) {
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null || lines.isEmpty()) {
      return;
    }
    int unresolved = 0;
    for (InvoiceLine line : lines) {
      String saleCode = BgOcrVatMapping.saleTaxCodeForRate(line.getTaxRate());
      line.setTaxCode(null);
      line.setTaxLineSet(new HashSet<>());
      if (saleCode == null) {
        unresolved++;
        continue;
      }
      Tax tax = taxRepo.all().filter("self.code = :c").bind("c", saleCode).fetchOne();
      TaxLine taxLine = null;
      if (tax != null) {
        try {
          taxLine =
              Beans.get(TaxService.class)
                  .getTaxLine(tax, result == null ? null : result.getInvoiceDate());
        } catch (Exception e) {
          // no active version for this date — counted as unresolved below, never a wrong tax
          taxLine = null;
        }
      }
      if (taxLine == null) {
        unresolved++;
        continue;
      }
      line.setTaxCode(saleCode);
      HashSet<TaxLine> taxLineSet = new HashSet<>();
      taxLineSet.add(taxLine);
      line.setTaxLineSet(taxLineSet);
    }
    if (unresolved > 0 && notes != null) {
      notes.add(
          "No sale VAT code could be set on "
              + unresolved
              + " line(s) (unmapped rate, or the sale tax is not configured); the purchase VAT was"
              + " removed — set the tax manually.");
    }
  }

  /**
   * Re-resolve every line's account on the SALE side of the product's account management (the same
   * stock call {@link #resolveLineProductAndAccount} makes with {@code isPurchase = true}). A line
   * whose product resolves no sale account is left with NO account rather than the purchase one
   * buildDraft set — a wrong account is worse than an empty one on a draft that must be reviewed.
   */
  protected void reresolveSaleLineAccounts(Invoice invoice, List<String> notes) {
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null || lines.isEmpty()) {
      return;
    }
    int unresolved = 0;
    for (InvoiceLine line : lines) {
      Account account = null;
      if (line.getProduct() != null && invoice.getCompany() != null) {
        try {
          account =
              Beans.get(AccountManagementAccountService.class)
                  .getProductAccount(
                      line.getProduct(),
                      invoice.getCompany(),
                      invoice.getFiscalPosition(),
                      false,
                      false);
        } catch (Exception e) {
          account = null;
        }
      }
      line.setAccount(account);
      if (account == null) {
        unresolved++;
      }
    }
    if (unresolved > 0 && notes != null) {
      notes.add(
          "No sale account could be resolved for "
              + unresolved
              + " line(s); set the accounts manually.");
    }
  }

  /**
   * Fills {@code bgVatTaxEventDate} on a supplier draft from the date the document itself states
   * ({@link BgOcrTaxEventDate}), and nothing else — never the invoice date, the due date or the
   * upload date. Written through the AI field registry, the same path {@code edit_supplier_invoice}
   * uses, because the field belongs to bg-localization, which is not a compile dependency here.
   *
   * @return a note for the accountant when the document's answer needs a human, else null
   */
  protected String applyStatedTaxEventDate(Invoice draft, OcrExtractResult result) {
    BgOcrTaxEventDate.Reading reading = BgOcrTaxEventDate.read(result.getRawText());
    if (reading.status() != BgOcrTaxEventDate.Status.STATED) {
      return reading.reviewNote();
    }
    try {
      if (applyTaxEventDateField(draft, reading.date())) {
        return null;
      }
    } catch (RuntimeException e) {
      LOG.warn("Could not set the tax-event date on the OCR invoice: {}", e.toString());
    }
    return "The document states «дата на данъчното събитие» "
        + reading.date()
        + ", but it could not be filled in. Enter it by hand.";
  }

  /** Writes the field through its owning module; false when no module owns it. A test seam. */
  protected boolean applyTaxEventDateField(Invoice draft, LocalDate date) {
    return Beans.get(AiInvoiceFieldRegistry.class)
            .applyHeaderField(
                draft,
                "bgVatTaxEventDate",
                com.fasterxml.jackson.databind.node.TextNode.valueOf(date.toString()))
        != null;
  }

  /**
   * Defaults {@code Invoice.napDocumentTypeSelect} (a bg-localization field, {@code
   * bg.nap.document.type.select}) to "01 – Фактура" when blank, reflectively for the same reason
   * {@link #setMoveNapDocumentType} is: the field is merged onto {@code Invoice} by
   * bg-localization, which is not a compile dependency of ai-ocr. Only ever called on a freshly
   * built {@code buildDraft()} invoice, which is always {@code OPERATION_TYPE_SUPPLIER_PURCHASE},
   * so there is no refund/credit-note or customer case to exclude here.
   *
   * <p>The same default is also applied, non-reflectively, by {@code BgNapInvoiceFieldContributor}
   * for an invoice the AI Accountant creates by hand ({@code create_supplier_invoice}) — this is
   * the OCR-intake half of that same rule, not a second one.
   */
  private static void defaultInvoiceNapDocumentTypeIfBlank(Invoice invoice) {
    try {
      com.axelor.db.mapper.Mapper mapper = com.axelor.db.mapper.Mapper.of(Invoice.class);
      Object current = mapper.get(invoice, "napDocumentTypeSelect");
      if (current == null || current.toString().trim().isEmpty()) {
        mapper.set(invoice, "napDocumentTypeSelect", "01");
      }
    } catch (RuntimeException e) {
      LOG.warn(
          "Could not default NAP document type on the OCR invoice (field not available at"
              + " runtime): {}",
          e.toString());
    }
  }

  /**
   * Attach the original uploaded document (the OCR source file) to the generated invoice/move as a
   * standard DMS attachment, so the accountant can preview it from the record's paper-clip
   * (Attachments) icon without hunting through the source ZIP/folder. Best-effort: a failure here
   * never blocks the intake. Skips silently when there is no source file, and avoids creating a
   * second identical attachment on the same record (e.g. on reprocess).
   */
  protected void attachSourceDocument(AiOcrUpload upload, Model entity) {
    MetaFile file = upload != null ? upload.getOriginalFile() : null;
    if (file == null || entity == null || entity.getId() == null) {
      return;
    }
    attachDocument(upload, entity, file, "Could not attach source document for preview.");
  }

  /**
   * Attach an EXTRA document (e.g. a split invoice's following stock receipt) to the record,
   * mirroring {@link #attachSourceDocument} but for a given {@link MetaFile}. The file's own name
   * (distinct from the invoice slice) keeps it a separate attachment rather than a suppressed
   * duplicate. Best-effort: never blocks intake.
   */
  protected void attachExtraFile(AiOcrUpload upload, Model entity, MetaFile file) {
    if (file == null || entity == null || entity.getId() == null) {
      return;
    }
    attachDocument(
        upload, entity, file, "Could not attach the stock-receipt document for preview.");
  }

  /**
   * Attach one of the upload's own files to the generated record.
   *
   * <p><b>The same {@link MetaFile} is shared, not copied.</b> Both consumers need byte-identical
   * content — it is literally the one file the upload already owns — so sharing is exact by
   * construction: no digest comparison, and no risk of equating a page slice with its carrier. The
   * display name goes on the {@code DMSFile}; the shared {@code MetaFile}'s own {@code fileName} is
   * never rewritten, because another record is showing it.
   *
   * <p>No copy of the blob is needed: the platform's reference count ({@code DMSFile} + {@code
   * MetaAttachment} only) does not know about {@code AiOcrUpload.originalFile}, but {@code
   * OcrMetaFiles} counts every mapped relation and retains a file a business record still holds, so
   * deleting the invoice does not physically delete the shared source.
   *
   * <p>Sharing is skipped — and the old independent copy made instead — when another record already
   * holds this file as a DMS attachment (see {@link OcrFileSharingService}); the platform's
   * owner-blind attachment bookkeeping cannot support two DMS consumers of one file.
   */
  protected void attachDocument(
      AiOcrUpload upload, Model entity, MetaFile file, String failureNote) {
    try {
      Path srcPath = MetaFiles.getPath(file);
      if (srcPath == null || !Files.exists(srcPath)) {
        return; // nothing to preview — never fabricate an attachment
      }
      String fileName = file.getFileName();
      String objectName = EntityHelper.getEntityClass(entity).getName();
      if (alreadyAttached(objectName, entity.getId(), file, fileName)) {
        return; // idempotent: reprocess must not add a second attachment
      }
      MetaFiles metaFiles = Beans.get(MetaFiles.class);
      if (Beans.get(OcrFileSharingService.class).canShareWith(file, objectName, entity.getId())) {
        metaFiles.attach(file, fileName, entity); // share the upload's own MetaFile
        return;
      }
      LOG.info(
          "MetaFile id={} is already attached to another record; attaching an independent copy to"
              + " {}#{} instead of sharing it.",
          file.getId(),
          objectName,
          entity.getId());
      MetaFile copy = metaFiles.upload(srcPath.toFile());
      copy.setFileName(fileName);
      metaFiles.attach(copy, fileName, entity);
    } catch (Exception e) {
      // Non-fatal: the draft is already created; the preview attachment is a convenience only.
      if (upload != null) {
        String prev = upload.getExtractionErrors();
        upload.setExtractionErrors(
            (prev == null || prev.isEmpty() ? "" : prev + "\n") + failureNote);
      }
    }
  }

  /**
   * Whether this record already carries this document. Matched on the file itself OR the display
   * name: the name check preserves the previous idempotency (a reprocess that produced an
   * independent copy under the same name), and the file check covers the shared case.
   */
  protected boolean alreadyAttached(
      String objectName, Long entityId, MetaFile file, String fileName) {
    Long existing =
        JPA.em()
            .createQuery(
                "SELECT count(d) FROM DMSFile d WHERE d.relatedModel = :n AND d.relatedId = :id"
                    + " AND (d.fileName = :fn OR d.metaFile = :file)",
                Long.class)
            .setParameter("n", objectName)
            .setParameter("id", entityId)
            .setParameter("fn", fileName)
            .setParameter("file", file)
            .getSingleResult();
    return existing != null && existing > 0;
  }

  /**
   * Record a terminal FAILED status in a <b>fresh transaction</b>, after {@link #process(Long)} has
   * thrown and rolled back. Uses a bulk UPDATE (no entity load) so it is immune to the stale
   * persistence context / optimistic-lock state left by the rolled-back attempt. Without this, a
   * job that fails mid-build reverts to QUEUED and the poller retries it forever.
   */
  @Transactional
  public void markFailed(Long uploadId, String reason) {
    int updated =
        JPA.em()
            .createQuery(
                "UPDATE AiOcrUpload s SET s.statusSelect = :failed, s.extractionErrors = :reason, "
                    + "s.processedOn = :now WHERE s.id = :id AND s.statusSelect <> :completed")
            .setParameter("failed", AiOcrUploadStatus.FAILED)
            .setParameter("reason", reason)
            .setParameter("now", LocalDateTime.now())
            .setParameter("id", uploadId)
            .setParameter("completed", AiOcrUploadStatus.COMPLETED)
            .executeUpdate();
    if (updated > 0) {
      // Bulk UPDATE bypasses JPA entity lifecycle, so the Upload Audit trail needs an explicit
      // sync — atomic with the update above (same transaction): a persistence failure here rolls
      // both back (see AiOcrUploadAuditService's class javadoc).
      Beans.get(AiOcrUploadAuditService.class).syncByIds(List.of(uploadId));
    }
  }

  /** Group codes allowed to retry (internal roles). Clients cannot retry. */
  protected static final java.util.Set<String> INTERNAL_GROUP_CODES =
      new java.util.HashSet<>(java.util.Arrays.asList("ai_ocr_internal", "admins"));

  /**
   * Reset a FAILED job for another attempt (accountant/internal action only).
   *
   * <p>Clears every stale result/attempt field from the failed run immediately, in this same
   * transaction — not just {@code extractionErrors}, but also the confidence/engine/progress fields
   * {@code persistMeta}/{@code persistOcrProgress} may have written before the failure, and {@code
   * extractionStartedOn} (which {@link #claim} only stamps via {@code COALESCE} when it is null, so
   * a stale value here would otherwise survive into the new attempt's display). A FAILED row never
   * has {@code invoice}/{@code move} set — {@link #fail} runs before either is ever populated — so
   * those, and every structural/identity field ({@code originalFile}, {@code importArchive}, {@code
   * segmentType}, {@code carrierKind}, ...), are left untouched.
   */
  @Transactional
  public void retry(Long uploadId) {
    if (!isInternalUser()) {
      throw new IllegalStateException("Retry is restricted to internal users.");
    }
    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null || !AiOcrUploadStatus.FAILED.equals(upload.getStatusSelect())) {
      return;
    }
    upload.setExtractionErrors(null);
    upload.setExtractionInformation(null);
    upload.setRawExtractedJson(null);
    upload.setBlobUrl(null);
    upload.setConfidence(null);
    upload.setEngineUsed(null);
    upload.setExtractionStartedOn(null);
    upload.setExtractionFinishedOn(null);
    upload.setProcessedOn(null);
    upload.setProgressPercent(null);
    upload.setRetryCount(nz(upload.getRetryCount()) + 1);
    upload.setStatusSelect(AiOcrUploadStatus.QUEUED);
    upload.setLockedOn(null);
    uploadRepo.save(upload);
  }

  /**
   * Reprocess an upload whose produced invoice was deleted from Suppl. Invoices: clear the stale
   * invoice/move links and requeue the still-stored original document, so {@link #process(Long)}
   * can build a fresh draft. Guarded to the {@code deletedFromSupplInvoices} status so it can only
   * be triggered for those records.
   */
  @Transactional
  public void reprocess(Long uploadId) {
    if (!isInternalUser()) {
      throw new IllegalStateException("Reprocess is restricted to internal users.");
    }
    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null) {
      return;
    }
    String status = upload.getStatusSelect();
    if (!AiOcrUploadStatus.DELETED_FROM_SUPPL_INVOICES.equals(status)
        && !AiOcrUploadStatus.DELETED_FROM_MOVES.equals(status)
        && !AiOcrUploadStatus.DELETED_FROM_BANK_STATEMENTS.equals(status)) {
      return; // only records whose invoice/move/bank-statement was deleted may be reprocessed
    }
    // The preserved source must still exist — never requeue into a guaranteed "file not found".
    MetaFile source = upload.getOriginalFile();
    Path sourcePath = source != null ? MetaFiles.getPath(source) : null;
    if (sourcePath == null || !Files.exists(sourcePath)) {
      throw new IllegalStateException(
          "Cannot reprocess: the original source document is no longer available in storage.");
    }
    // Clear ONLY the stale generated links + result fields; PRESERVE originalFile/archiveFile so
    // the
    // re-run reads the same preserved source. process() re-routes to a fresh Invoice OR Move.
    upload.setInvoice(null);
    upload.setMove(null);
    upload.setExtractionErrors(null);
    upload.setExtractionInformation(null);
    upload.setProcessedOn(null);
    upload.setConfidence(null);
    upload.setEngineUsed(null);
    upload.setRawExtractedJson(null);
    upload.setBlobUrl(null);
    upload.setExtractionStartedOn(null);
    upload.setExtractionFinishedOn(null);
    upload.setProgressPercent(null);
    upload.setRetryCount(nz(upload.getRetryCount()) + 1);
    upload.setStatusSelect(AiOcrUploadStatus.QUEUED);
    upload.setLockedOn(null);
    uploadRepo.save(upload);
  }

  // ---------------------------------------------------------------------------
  // Smart retry: one decision, reused by the per-record Retry button and "Retry all eligible".
  // ---------------------------------------------------------------------------

  /** The smart-retry action a single upload calls for, decided by {@link #decideRetryMode}. */
  public enum RetryMode {
    /** No usable stored extraction — queue a fresh Azure re-extraction of the original file. */
    REEXTRACT,
    /** A usable stored OCR result exists — rerun local parsing/routing from it, no Azure call. */
    REPROCESS,
    /** Already in flight, successful, missing its file, deleted/archived, or otherwise unsafe. */
    NOT_ELIGIBLE
  }

  /**
   * True when {@code upload} already carries an OCR result that can be routed again WITHOUT a new
   * Azure call — the same stored-JSON fields {@link #rehydrateExtraction} reads. Mirrors that
   * method's own usability check (kept separate because that one must throw for a genuinely missing
   * result, while this one is a plain yes/no used to pick a retry mode).
   */
  public boolean hasUsableStoredExtraction(AiOcrUpload upload) {
    if (upload == null) {
      return false;
    }
    String cached = upload.getCachedOcrJson();
    if (!isBlankStr(cached) && !cached.contains("\"fromPage\"")) {
      return true;
    }
    String raw =
        !isBlankStr(upload.getOcrResultJson())
            ? upload.getOcrResultJson()
            : upload.getSplitSourceJson();
    return !isBlankStr(raw);
  }

  /**
   * Decide how a single upload should be retried. Eligible only from {@code failed} or {@code
   * needsReview} (a missing-supplier row is {@code needsReview}, never a separate status — see
   * {@link #matchSupplier}), and only while its original file still exists in storage. Never
   * inspects {@code extractionErrors} text: the presence of a usable stored OCR result is the only
   * signal, which is also what keeps this decision correct for a missing-supplier row (extraction
   * succeeded, so it is always {@link RetryMode#REPROCESS}, never a new Azure call).
   */
  public RetryMode decideRetryMode(AiOcrUpload upload) {
    if (upload == null) {
      return RetryMode.NOT_ELIGIBLE;
    }
    String status = upload.getStatusSelect();
    if (!AiOcrUploadStatus.FAILED.equals(status)
        && !AiOcrUploadStatus.NEEDS_REVIEW.equals(status)) {
      return RetryMode.NOT_ELIGIBLE;
    }
    MetaFile source = upload.getOriginalFile();
    if (source == null) {
      return RetryMode.NOT_ELIGIBLE;
    }
    Path sourcePath = MetaFiles.getPath(source);
    if (sourcePath == null || !Files.exists(sourcePath)) {
      return RetryMode.NOT_ELIGIBLE;
    }
    return hasUsableStoredExtraction(upload) ? RetryMode.REPROCESS : RetryMode.REEXTRACT;
  }

  /**
   * Atomically move a {@code failed}/{@code needsReview} row to {@code queued} for a fresh Azure
   * re-extraction, clearing the same stale attempt fields {@link #retry} always has (never the
   * structural/identity fields). A bulk UPDATE guarded by the current status, exactly like {@link
   * #claim}, so a race between two Retry clicks (or Retry vs. Retry-all) lets only one through.
   *
   * @return true only for the caller that won the transition
   */
  @Transactional
  public boolean claimForReextract(Long uploadId) {
    int updated =
        JPA.em()
            .createQuery(
                "UPDATE AiOcrUpload s SET s.statusSelect = :queued, s.extractionErrors = null, "
                    + "s.extractionInformation = null, s.rawExtractedJson = null, s.blobUrl = null, "
                    + "s.confidence = null, s.engineUsed = null, s.extractionStartedOn = null, "
                    + "s.extractionFinishedOn = null, s.processedOn = null, s.progressPercent = null, "
                    + "s.retryCount = COALESCE(s.retryCount, 0) + 1, s.lockedOn = null "
                    + "WHERE s.id = :id AND (s.statusSelect = :failed OR s.statusSelect = :needsReview)")
            .setParameter("queued", AiOcrUploadStatus.QUEUED)
            .setParameter("id", uploadId)
            .setParameter("failed", AiOcrUploadStatus.FAILED)
            .setParameter("needsReview", AiOcrUploadStatus.NEEDS_REVIEW)
            .executeUpdate();
    if (updated == 1) {
      Beans.get(AiOcrUploadAuditService.class).syncByIds(List.of(uploadId));
    }
    return updated == 1;
  }

  /**
   * Atomically move a {@code failed}/{@code needsReview} row straight to {@code processing} for
   * LOCAL reprocessing of its already-stored OCR result — no Azure call. Mirrors {@link #claim}
   * (same guarded bulk-UPDATE shape) but sourced from failed/needsReview instead of queued, so a
   * race between two Retry clicks (or Retry vs. Retry-all) lets only one through. The stored OCR
   * JSON fields are deliberately left untouched — {@link #localReprocess} still needs them.
   *
   * @return true only for the caller that won the transition
   */
  @Transactional
  public boolean claimForLocalReprocess(Long uploadId) {
    int updated =
        JPA.em()
            .createQuery(
                "UPDATE AiOcrUpload s SET s.statusSelect = :processing, s.lockedOn = :now, "
                    + "s.extractionErrors = null, s.retryCount = COALESCE(s.retryCount, 0) + 1 "
                    + "WHERE s.id = :id AND (s.statusSelect = :failed OR s.statusSelect = :needsReview)")
            .setParameter("processing", AiOcrUploadStatus.PROCESSING)
            .setParameter("now", LocalDateTime.now())
            .setParameter("id", uploadId)
            .setParameter("failed", AiOcrUploadStatus.FAILED)
            .setParameter("needsReview", AiOcrUploadStatus.NEEDS_REVIEW)
            .executeUpdate();
    if (updated == 1) {
      Beans.get(AiOcrUploadAuditService.class).syncByIds(List.of(uploadId));
    }
    return updated == 1;
  }

  /**
   * Read the already-claimed (PROCESSING) upload's stored OCR result in a short committed
   * transaction, mirroring {@link #prepareForOcr}'s reasoning: the entity must not cross into
   * {@link #routeExtracted}'s own transaction as a proxy from a closed session.
   */
  @Transactional
  protected OcrExtractResult loadStoredExtraction(Long uploadId) {
    AiOcrUpload upload = uploadRepo.find(uploadId);
    if (upload == null) {
      throw new IllegalStateException("OCR record " + uploadId + " no longer exists.");
    }
    return rehydrateExtraction(upload);
  }

  /**
   * Reprocess an already-claimed (PROCESSING) upload from its OWN stored OCR result — parsing,
   * routing and supplier matching run again exactly as they did the first time, but WITHOUT a new
   * Azure call. Entry point the background executor runs for {@link RetryMode#REPROCESS}, mirroring
   * {@link #processClaimed}'s shape (short read step, then the existing routing transaction).
   */
  public void localReprocess(Long uploadId) throws Exception {
    OcrExtractResult result = loadStoredExtraction(uploadId);
    routeExtracted(uploadId, result);
  }

  /**
   * Smart retry entry point for a single upload — the ONE place both the per-record Retry button
   * and "Retry all eligible" call, so they can never disagree. Decides the mode, then atomically
   * claims and queues the matching background work; never calls Azure or resolves/creates a
   * supplier itself (a missing supplier stays {@code needsReview} until a human creates it).
   *
   * @return the mode actually queued, or {@link RetryMode#NOT_ELIGIBLE} when the row was not
   *     eligible, or lost a race to another retry/claim between the decision and the atomic claim
   *     (so at most one run is ever queued for a given row)
   */
  public RetryMode retryOne(Long uploadId) {
    if (!isInternalUser()) {
      throw new IllegalStateException("Retry is restricted to internal users.");
    }
    AiOcrUpload upload = uploadRepo.find(uploadId);
    RetryMode mode = decideRetryMode(upload);
    switch (mode) {
      case REEXTRACT:
        if (!claimForReextract(uploadId)) {
          return RetryMode.NOT_ELIGIBLE;
        }
        Beans.get(AiOcrProcessingExecutor.class).wake();
        return RetryMode.REEXTRACT;
      case REPROCESS:
        if (!claimForLocalReprocess(uploadId)) {
          return RetryMode.NOT_ELIGIBLE;
        }
        Beans.get(AiOcrProcessingExecutor.class).submitLocalReprocess(uploadId);
        return RetryMode.REPROCESS;
      default:
        return RetryMode.NOT_ELIGIBLE;
    }
  }

  // ---------------------------------------------------------------------------

  /**
   * Atomic claim: QUEUED → PROCESSING for exactly one worker, committed in its OWN transaction
   * (this method is invoked outside any surrounding transaction — from {@link #process(Long)} and
   * from the manual button controller). It also stamps {@code extractionStartedOn} with the REAL
   * start time (the moment work begins, before the long OCR), so the progress panel no longer shows
   * a start time that equals the finish time. Returns {@code true} only for the single worker that
   * flips a QUEUED row, so repeated Process clicks (or a concurrent dispatcher) never start it
   * twice.
   */
  /**
   * Make a manually uploaded row runnable: {@code uploaded → queued}, in its own committed
   * transaction.
   *
   * <p>A manual upload is saved as {@code uploaded} (see {@code AiOcrUploadManagementRepository}),
   * which the DB-backed dispatcher deliberately does not drain — so attaching a file starts nothing
   * and the Process / Extract archive button stays where the user can reach it. This is the one
   * step that arms the row, and it is status-guarded, so it is a <b>no-op for a row that is already
   * {@code queued}</b> (every pre-existing row, and every pipeline child) and for anything further
   * along its lifecycle.
   *
   * @return true when this call armed the row
   */
  @Transactional
  public boolean enqueue(Long uploadId) {
    int updated =
        JPA.em()
            .createQuery(
                "UPDATE AiOcrUpload s SET s.statusSelect = :queued "
                    + "WHERE s.id = :id AND s.statusSelect = :uploaded")
            .setParameter("queued", AiOcrUploadStatus.QUEUED)
            .setParameter("uploaded", AiOcrUploadStatus.UPLOADED)
            .setParameter("id", uploadId)
            .executeUpdate();
    if (updated == 1) {
      // Bulk UPDATE bypasses JPA entity lifecycle, so the Upload Audit trail needs an explicit
      // sync — atomic with the update above (same transaction): a persistence failure here rolls
      // both back (see AiOcrUploadAuditService's class javadoc).
      Beans.get(AiOcrUploadAuditService.class).syncByIds(List.of(uploadId));
    }
    return updated == 1;
  }

  @Transactional
  public boolean claim(Long uploadId) {
    int updated =
        JPA.em()
            .createQuery(
                "UPDATE AiOcrUpload s SET s.statusSelect = :processing, s.lockedOn = :now, "
                    + "s.extractionStartedOn = COALESCE(s.extractionStartedOn, :now) "
                    + "WHERE s.id = :id AND s.statusSelect = :queued")
            .setParameter("processing", AiOcrUploadStatus.PROCESSING)
            .setParameter("now", LocalDateTime.now())
            .setParameter("id", uploadId)
            .setParameter("queued", AiOcrUploadStatus.QUEUED)
            .executeUpdate();
    if (updated == 1) {
      // Atomic with the claim above (same transaction) — see AiOcrUploadAuditService.
      Beans.get(AiOcrUploadAuditService.class).syncByIds(List.of(uploadId));
    }
    return updated == 1;
  }

  protected void fail(AiOcrUpload upload, String reason) {
    upload.setExtractionErrors(reason);
    upload.setStatusSelect(AiOcrUploadStatus.FAILED);
    upload.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(upload);
  }

  /**
   * The invoice company comes from the upload. When the upload has none (e.g. the uploading user
   * had no active company), fall back to the user's active company, then to the sole configured
   * company. Returns null only when it is genuinely ambiguous (0 or several companies, no active).
   */
  protected Company resolveCompany(AiOcrUpload upload) {
    if (upload.getCompany() != null) {
      return upload.getCompany();
    }
    User user = AuthUtils.getUser();
    if (user != null && user.getActiveCompany() != null) {
      return user.getActiveCompany();
    }
    List<Company> companies = Beans.get(CompanyRepository.class).all().fetch();
    return companies.size() == 1 ? companies.get(0) : null;
  }

  /** Outcome of matching a supplier strictly by EIK: the single Partner, or a review reason. */
  protected static final class SupplierMatch {
    final Partner partner; // the single matched Partner, or null when not matched
    final String note; // review message when not matched (null when exactly one matched)

    SupplierMatch(Partner partner, String note) {
      this.partner = partner;
      this.note = note;
    }
  }

  /**
   * Match the supplier STRICTLY by ЕИК (Bulgarian company id) and NEVER create a Partner — supplier
   * companies are maintained by the accountant. A BG VAT number is just {@code BG} + ЕИК, so it is
   * normalized to the same key. Supplier NAME, ADDRESS and IBAN are never identity keys (they may
   * be used only for validation/display once a Partner is matched).
   *
   * <ul>
   *   <li>exactly one Partner with this ЕИК → use it;
   *   <li>no readable ЕИК → not matched (never match by name);
   *   <li>no Partner with this ЕИК → not matched, "Supplier with EIK X was not found…";
   *   <li>several Partners with this ЕИК → not matched (do not guess); the duplicates are reported.
   * </ul>
   */
  protected SupplierMatch matchSupplier(OcrExtractResult result) {
    String eik = eikKey(result);
    if (eik == null) {
      return new SupplierMatch(
          null,
          "Supplier EIK could not be read from the document; select the supplier manually."
              + " No partner was created.");
    }
    List<Partner> matches = SupplierEik.match(partnerRepo, eik).fetch();
    if (matches.size() == 1) {
      return new SupplierMatch(matches.get(0), null);
    }
    if (matches.isEmpty()) {
      return new SupplierMatch(
          null,
          "Supplier with EIK "
              + eik
              + " was not found; no partner was created automatically. Select the supplier, or"
              + " create it from this document once someone has confirmed the details.");
    }
    StringBuilder ids = new StringBuilder();
    for (Partner p : matches) {
      if (ids.length() > 0) {
        ids.append(", ");
      }
      ids.append("#").append(p.getId());
    }
    return new SupplierMatch(
        null,
        "Multiple suppliers found with EIK "
            + eik
            + " (Partner "
            + ids
            + "); none selected — pick the correct one manually.");
  }

  /**
   * The supplier's ЕИК as the single matching key: the extracted ЕИК, else the ЕИК embedded in the
   * VAT number ({@code BG} + ЕИК). Returns the canonical digits-only ЕИК (9–13 digits) or {@code
   * null} — never a name-derived value. Normalization (drop {@code BG}/"ЕИК:" label, spaces and
   * punctuation) is the shared {@link SupplierEik#canonical(String)} rule so the intake and the
   * assistant's lookup tool resolve suppliers identically.
   */
  static String eikKey(OcrExtractResult result) {
    if (result == null) {
      return null;
    }
    String eik = SupplierEik.canonical(result.getSupplierEik());
    return eik != null ? eik : SupplierEik.canonical(result.getSupplierVat());
  }

  protected Invoice buildDraft(OcrExtractResult result, Partner partner, AiOcrUpload upload)
      throws Exception {
    Invoice invoice = new Invoice();
    invoice.setOperationTypeSelect(InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE);
    invoice.setOperationSubTypeSelect(InvoiceRepository.OPERATION_SUB_TYPE_DEFAULT);
    // Every draft built here is a SUPPLIER PURCHASE (never a refund/credit note — those are raised
    // against an existing invoice, not by intake), so "01 – Фактура" is always the safe default.
    defaultInvoiceNapDocumentTypeIfBlank(invoice);
    invoice.setStatusSelect(InvoiceRepository.STATUS_DRAFT);
    Company company = resolveCompany(upload);
    if (company == null) {
      // No company on the upload and none resolvable (no active company / not exactly one company).
      // Fail with a clear message instead of a JPA "company must not be null" persist crash.
      throw new IllegalStateException(
          "Cannot build the OCR draft: the upload has no company and none could be resolved. "
              + "Set the upload's company or the user's active company.");
    }
    invoice.setCompany(company);
    invoice.setPartner(partner);
    invoice.setInvoiceDate(result.getInvoiceDate());
    // originDate = the date printed on the supplier's document. Azure's InvoiceDate is the only
    // reliable structured date; the BG tax-event date ("Дата на данъчното събитие") lives in free
    // text and is filled separately, only when the text states it — see applyStatedTaxEventDate.
    invoice.setOriginDate(result.getInvoiceDate());
    invoice.setSupplierInvoiceNb(result.getInvoiceNumber());
    invoice.setPartnerTaxNbr(
        firstNonBlank(result.getSupplierVat(), partner != null ? partner.getTaxNbr() : null));
    invoice.setExternalReference(result.getCustomerId());
    invoice.setDeliveryAddressStr(result.getCustomerAddress());
    if (result.getDueDate() != null) {
      // Display value for review; the suite recomputes the due date from the payment condition on
      // ventilation.
      invoice.setDueDate(result.getDueDate());
    }
    if (result.getInvoiceDate() != null
        && result.getDueDate() != null
        && !result.getDueDate().isBefore(result.getInvoiceDate())) {
      invoice.setPaymentDelay(
          Math.toIntExact(ChronoUnit.DAYS.between(result.getInvoiceDate(), result.getDueDate())));
    }

    // Review notes gathered during the build (currency mismatch, identity cross-check). Routed into
    // the readiness notes at the end — never blocking.
    List<String> reviewNotes = new ArrayList<>();

    // Header enrichment via standard Axelor defaulting services (safe: sets a value only when the
    // service resolves one; never hard-codes an id or picks the first DB record).
    enrichHeader(invoice, partner, result, reviewNotes);

    // Set the supplier-purchase journal (exact code PCH, this company, purchase type). Never the
    // first record / never created; 0 or >1 candidates leave it empty with a review warning.
    applyPurchaseJournal(invoice, company, reviewNotes);

    // Cross-check the extracted supplier/recipient against the company that owns the upload
    // (vendor/customer swap, or a document addressed to a different company). Read-only.
    Partner companyPartner = company.getPartner();
    reviewNotes.addAll(
        BgOcrReviewChecks.identityWarnings(
            result.getSupplierEik(),
            result.getSupplierVat(),
            result.getCustomerEik(),
            result.getCustomerVat(),
            companyPartner != null ? companyPartner.getRegistrationCode() : null,
            companyPartner != null ? companyPartner.getTaxNbr() : null));

    // Payment method comes from Azure PaymentTerm when it contains method wording, then falls back
    // to full OCR text. Conflicting wording is never selected.
    applyOcrPaymentMethod(invoice, result, reviewNotes);
    // A fiscal / receipt-style invoice (ФИСКАЛЕН БОН / касова бележка) is a POS card payment with
    // no
    // bank account: default its payment option to "Плащане с кредитна карта" so the absent bank
    // details never forces review or blocks Validate.
    applyFiscalReceiptPaymentMode(invoice, result, reviewNotes);
    applyOcrBankDetails(invoice, result, reviewNotes);

    // AI metadata (non-accounting). Validation/readiness notes are set at the end of buildDraft.
    invoice.setAiGenerated(true);
    invoice.setAiConfidence(result.getConfidence());
    invoice.setAiReviewStatus("pending");

    boolean receiptStyle = isFiscalReceiptStyle(result.getRawText());
    // Dual-currency receipts print both a EUR and a BGN/ЛЕВА total; Azure's InvoiceTotal sometimes
    // picks the ЛЕВА value (e.g. 140.72 BGN instead of 71.95 EUR). For a receipt, override the
    // header totals from the explicitly EUR-labelled amounts so the invoice is booked in EUR.
    if (receiptStyle) {
      applyReceiptEurTotals(result);
    }
    // Repair a VAT that was read in BGN while base/gross are in EUR (base + VAT ≠ gross, explained
    // by the 1.95583 peg) — for both receipt and normal invoices, so the booked VAT is in EUR.
    reconcileEurVat(result);
    // Dual-currency normal invoices (BGN + EUR printed in parallel): when Azure's SubTotal/
    // InvoiceTotal picked the BGN column, re-read net/gross from the explicitly EUR-labelled
    // amounts.
    applyEurTotalsFromText(result);
    // Recover a VAT rate the document proves but does not print on its lines — BEFORE any line is
    // built, so the tax code, the tax summary and the header all come out of one consistent set of
    // figures. Does nothing unless the document's own totals prove exactly one rate.
    recoverMissingLineVatRates(result, receiptStyle, reviewNotes);
    int added = 0;
    for (OcrExtractLine src : result.getLines()) {
      // Receipt-style fiscal breakdown: a fiscal bon prints a label:value column (ед.цена /
      // количество / сума / отстъпка / #в т.ч. ДДС / #в т.ч. АКЦИЗ / #НЕТО / ОБЩА СУМА / курс /
      // payment totals) which Azure parses as separate line "items". Those are summary/payment/tax
      // rows, never purchased products — drop them so only real items remain.
      if (receiptStyle && isFiscalSummaryLine(src.getDescription())) {
        continue;
      }
      invoice.addInvoiceLineListItem(buildInvoiceLine(invoice, src, result));
      added++;
    }
    // A fiscal receipt whose only "items" were those summary rows now has zero lines: synthesize
    // ONE
    // real product line from the recovered product name + the invoice totals, so the invoice
    // carries
    // a single genuine item (e.g. "Бензин А-95Н") instead of the numeric summary noise.
    if (receiptStyle
        && added == 0
        && result.getTotalGross() != null
        && result.getTotalGross().signum() > 0) {
      invoice.addInvoiceLineListItem(buildReceiptProductLine(invoice, result));
    }

    // Header totals from OCR (for display in the inbox); the suite recomputes on Validate.
    invoice.setExTaxTotal(result.getTotalBase());
    invoice.setTaxTotal(result.getTotalVat());
    invoice.setInTaxTotal(result.getTotalGross());
    // ...and the SAME gross kept where the recompute cannot reach it. inTaxTotal above is the
    // printed figure only until the first compute overwrites it with the line-derived one; from
    // then on nothing could tell a supplier's rounding from a wrong line section. This column is
    // the authoritative document total the validation gate reconciles against
    // (OcrRoundingLineService) and it is written HERE and nowhere else — an invoice keyed by hand
    // has no document, so it has no trusted total and is never reconciled or blocked.
    invoice.setOcrDocumentTotal(result.getTotalGross());
    // ...and the two halves, when the document states a coherent breakdown. The marker is what
    // makes them usable: without it a NULL column and a declared zero are the same figure through
    // Axelor's defaulted decimal getters, so every invoice that predates this would look like it
    // declared net 0.00 / VAT 0.00.
    applyDocumentBreakdown(invoice, result);
    invoice.setAmountRemaining(result.getTotalGross());
    if (sameCurrency(invoice)) {
      invoice.setCompanyExTaxTotal(result.getTotalBase());
      invoice.setCompanyTaxTotal(result.getTotalVat());
      invoice.setCompanyInTaxTotal(result.getTotalGross());
      invoice.setCompanyInTaxTotalRemaining(result.getTotalGross());
    } else {
      reviewNotes.add(
          "Company-currency totals were not copied from OCR because the invoice currency differs.");
    }

    // Company-currency line amounts, then the VAT summary — in that order, because the summary's
    // company-currency base is built from the lines'.
    applyCompanyLineAmounts(invoice, reviewNotes);
    syncTaxSummary(invoice, reviewNotes);

    flagImplausibleDocumentTotal(invoice, reviewNotes);
    setReadinessNotes(invoice, result, reviewNotes);
    return invoice;
  }

  /**
   * Warns, without guessing, when the extracted document total cannot be the grand total.
   *
   * <h2>What intake can and cannot decide</h2>
   *
   * A grand total can never be LESS than the sum of the lines it totals (beyond per-line rounding).
   * When Azure's {@code InvoiceTotal} comes out below the summed line gross — upload 70: 80.75
   * against 86.72 — the extracted figure is provably a subtotal or a stray line amount, and that
   * much intake can say reliably. What it must NOT do is pick the real total: a receipt prints
   * several candidate sums, and deriving the trusted total from the lines would make the invoice
   * agree with itself, defeating the only independent check on the line section. So this flags the
   * document for a human and leaves the extracted value in place; the validation gate then blocks
   * until an accountant corrects it ({@link
   * com.axelor.apps.aiocr.service.record.OcrDocumentTotalCorrectionService}).
   *
   * <p>Deliberately one-directional: a total ABOVE the line sum is an ordinary discount/charge case
   * that the existing reconciliation already reports, not evidence of a subtotal.
   */
  protected void flagImplausibleDocumentTotal(Invoice invoice, List<String> reviewNotes) {
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null || lines.isEmpty()) {
      return;
    }
    BigDecimal lineGrossSum = BigDecimal.ZERO;
    for (InvoiceLine line : lines) {
      lineGrossSum =
          lineGrossSum.add(line.getInTaxTotal() == null ? BigDecimal.ZERO : line.getInTaxTotal());
    }
    BigDecimal extractedGross = invoice.getOcrDocumentTotal();
    if (!documentTotalBelowLineSum(extractedGross, lineGrossSum, lines.size())) {
      return;
    }
    reviewNotes.add(
        "The extracted document total ("
            + extractedGross
            + ") is below the sum of the line items ("
            + lineGrossSum
            + "), which a grand total can never be — it is most likely a subtotal, not the invoice"
            + " total. Check the original document and use “Correct document total”. The"
            + " extracted value is kept as evidence and validation will block until it is corrected.");
  }

  /**
   * True when the extracted gross is below the summed line gross by more than per-line rounding
   * could explain — the signature of a subtotal mistaken for the invoice total. Pure, for testing.
   */
  static boolean documentTotalBelowLineSum(
      BigDecimal extractedGross, BigDecimal lineGrossSum, int lineCount) {
    if (extractedGross == null
        || extractedGross.signum() <= 0
        || lineGrossSum == null
        || lineGrossSum.signum() <= 0) {
      return false;
    }
    BigDecimal allowance =
        new BigDecimal("0.01").multiply(BigDecimal.valueOf(Math.max(1, lineCount)));
    return lineGrossSum.subtract(extractedGross).compareTo(allowance) > 0;
  }

  /**
   * Fills each line's {@code companyExTaxTotal} / {@code companyInTaxTotal}.
   *
   * <p>These were never set on an intake-built line, and they are not cosmetic: {@code
   * InvoiceGenerator.computeInvoice} sets the invoice's {@code companyExTaxTotal} to the <em>sum of
   * the lines'</em> at validation, and {@code TaxInvoiceLine} builds each VAT row's {@code
   * companyExTaxBase} from the same field. Left at their default zero, a validated invoice ended up
   * with company-currency totals of 0.00 and a VAT summary whose company amounts were 0.00 — which
   * is what the accounting move is posted from. Only invoices whose lines had been rewritten by the
   * assistant escaped it, because {@link
   * com.axelor.apps.aiocr.service.record.OcrInvoiceLineEditService} already set them.
   *
   * <p>Converted by {@link InvoiceLineService#getCompanyExTaxTotal}, which for a purchase uses the
   * origin date — the same call the suite makes. When the invoice and company currencies are the
   * same the conversion is the identity, so the usual case cannot fail.
   */
  public void applyCompanyLineAmounts(Invoice invoice, List<String> reviewNotes) {
    List<InvoiceLine> lines = invoice.getInvoiceLineList();
    if (lines == null || lines.isEmpty()) {
      return;
    }
    InvoiceLineService invoiceLineService = Beans.get(InvoiceLineService.class);
    for (InvoiceLine line : lines) {
      try {
        line.setCompanyExTaxTotal(
            invoiceLineService.getCompanyExTaxTotal(nz(line.getExTaxTotal()), invoice));
        line.setCompanyInTaxTotal(
            invoiceLineService.getCompanyExTaxTotal(nz(line.getInTaxTotal()), invoice));
      } catch (Exception e) {
        // A missing conversion rate must not lose the draft, but it must not pass unnoticed either:
        // the company totals would post as zero.
        if (reviewNotes != null) {
          reviewNotes.add(
              "Line amounts could not be converted to the company currency ("
                  + e.getMessage()
                  + "); the company-currency totals will be wrong until a conversion rate exists.");
        }
        LOG.warn("Company-currency conversion failed for an OCR line: {}", e.getMessage());
        return;
      }
    }
  }

  /**
   * Builds the invoice's VAT summary ({@code invoiceLineTaxList}) from the lines just assembled, so
   * the draft carries the same "Tax" section a draft prepared in the form carries.
   *
   * <p>Without it Axelor asks "There is no taxes on this invoice. Do you want to proceed?" on
   * Validate — see {@link OcrInvoiceTaxSummaryService} for why that happened and why the header
   * totals are deliberately left as the supplier printed them.
   */
  protected void syncTaxSummary(Invoice invoice, List<String> reviewNotes) {
    OcrInvoiceTaxSummaryService.Result taxes =
        Beans.get(OcrInvoiceTaxSummaryService.class).sync(invoice);
    if (taxes.isFailed() && reviewNotes != null) {
      reviewNotes.add(
          "The VAT summary could not be built ("
              + taxes.getFailure()
              + "). Axelor rebuilds it when the invoice is validated, so validation will fail the"
              + " same way until the company's VAT account configuration is corrected.");
      return;
    }
    // Finish the draft HERE, not at Validate: align the VAT to the printed figure and book the
    // net rounding cent on 609, so the accountant opens an invoice whose totals and lines are
    // already final. Best-effort — a reason instead of an exception keeps the draft saveable, and
    // the validation gate still refuses anything that is not reconciled.
    String blocked = Beans.get(OcrRoundingLineService.class).prepareDraft(invoice);
    if (blocked != null && reviewNotes != null) {
      reviewNotes.add(blocked);
    }
  }

  /**
   * Assign the supplier-purchase journal: the EXISTING journal with code {@code PCH}, owned by this
   * company, of the purchase journal type ({@link BgOcrJournalSelector#PURCHASE_TECHNICAL_TYPE}).
   * Exactly one match → assign; none or several → leave empty and add a review warning. Never the
   * first record, never a newly created journal.
   */
  protected void applyPurchaseJournal(Invoice invoice, Company company, List<String> reviewNotes) {
    if (company == null) {
      return;
    }
    List<Journal> matches =
        Beans.get(JournalRepository.class)
            .all()
            .filter(
                "self.code = :code AND self.company = :company "
                    + "AND self.journalType.technicalTypeSelect = :type")
            .bind("code", BgOcrJournalSelector.PURCHASE_JOURNAL_CODE)
            .bind("company", company)
            .bind("type", BgOcrJournalSelector.PURCHASE_TECHNICAL_TYPE)
            .fetch();
    switch (BgOcrJournalSelector.decide(matches.size())) {
      case SELECT:
        invoice.setJournal(matches.get(0));
        break;
      case WARN_MULTIPLE:
        reviewNotes.add(
            "Several '"
                + BgOcrJournalSelector.PURCHASE_JOURNAL_CODE
                + "' purchase journals match this company; none selected — set the journal"
                + " manually.");
        break;
      default:
        reviewNotes.add(
            "No '"
                + BgOcrJournalSelector.PURCHASE_JOURNAL_CODE
                + "' purchase journal is configured for this company; set the journal manually.");
        break;
    }
  }

  /**
   * Build a standard DRAFT accounting {@link Move} for a NON-invoice document (the OCR text has no
   * "фактура"). Phase-1 minimum: company (from the upload) + the company's manual
   * miscellaneous-operations journal (required by the Move model) + an OCR reference in
   * origin/description + STATUS_NEW (unposted). No lines are guessed and nothing is posted; the
   * accountant classifies it. The original file stays linked on the upload ({@code originalFile}).
   */
  protected Move buildMove(OcrExtractResult result, AiOcrUpload upload) {
    Company company = resolveCompany(upload);
    if (company == null) {
      throw new IllegalStateException(
          "Cannot build the OCR move: the upload has no company and none could be resolved.");
    }
    Journal journal = null;
    try {
      AccountConfig accountConfig = Beans.get(AccountConfigService.class).getAccountConfig(company);
      journal = accountConfig != null ? accountConfig.getManualMiscOpeJournal() : null;
    } catch (Exception e) {
      // no AccountConfig — handled by the null check below
    }
    if (journal == null) {
      // Move.journal is required; without a manual misc-operations journal the Move cannot persist.
      throw new IllegalStateException(
          "Cannot build the OCR move: no manual miscellaneous-operations journal is configured for"
              + " company "
              + company.getName()
              + " (AccountConfig.manualMiscOpeJournal). Configure it to create draft moves for"
              + " non-invoice documents.");
    }
    Move move = new Move();
    move.setCompany(company);
    move.setJournal(journal);
    move.setStatusSelect(MoveRepository.STATUS_NEW);
    if (company.getCurrency() != null) {
      move.setCurrency(company.getCurrency());
      move.setCompanyCurrency(company.getCurrency());
    }
    // Move.date + Move.period are required (@NotNull). Resolve the open fiscal period for today via
    // the standard PeriodService; if none is configured/open, fail clearly (no draft-Move
    // fabricated
    // without a valid accounting period).
    LocalDate moveDate = LocalDate.now();
    move.setDate(moveDate);
    try {
      move.setPeriod(
          Beans.get(PeriodService.class)
              .getActivePeriod(moveDate, company, YearRepository.TYPE_FISCAL));
    } catch (Exception e) {
      throw new IllegalStateException(
          "Cannot build the OCR move: no open fiscal period for "
              + moveDate
              + " in company "
              + company.getName()
              + ". Configure a fiscal year and open period to create draft moves for non-invoice"
              + " documents.",
          e);
    }
    // Origin can come from an OCR-extracted invoice number, which may be long/garbage — cap it to
    // the column width (255) so a stray extraction never fails the whole Move insert.
    String ref = firstNonBlank(upload.getFileName(), result.getInvoiceNumber());
    if (ref != null) {
      move.setOrigin(truncateTo255(ref));
    }
    move.setDescription(
        truncateTo255(
            "AI-OCR non-invoice document"
                + (upload.getFileName() != null ? ": " + upload.getFileName() : "")
                + " (no 'фактура' detected in the OCR text — review and classify)."));

    // Payment mode from the OCR text, by stable code: "POS"/"ПОС" → INC_POS, otherwise the
    // bank-transfer default OUT_WT. Never null — a missing OUT_WT raises instead, see
    // resolveMovePaymentMode.
    move.setPaymentMode(resolveMovePaymentMode(result.getRawText()));
    // Payment condition: the company's AccountConfig default, else the standard "Immediate" one.
    PaymentCondition paymentCondition = resolveDefaultPaymentCondition(company);
    if (paymentCondition != null) {
      move.setPaymentCondition(paymentCondition);
    }
    // Preserve the extracted item rows as descriptive detail, and (when possible) add ONE debit
    // move line for the document NET total — never one ledger line per OCR item.
    addMoveLines(move, result);
    return move;
  }

  /**
   * Draft-move lines from OCR, done the accounting-safe way:
   *
   * <ul>
   *   <li>The extracted item rows are preserved as descriptive detail on the move (OCR details, NOT
   *       accounting entries — never one ledger line per OCR row).
   *   <li>At most ONE debit {@link MoveLine} is created, for the document's NET total, on the
   *       account configured by {@code aiocr.move.line.account.code} resolved <b>for the move's
   *       company only</b> (no global/guessed fallback). No counter-line is created (the balancing
   *       account is unknown; the accountant completes the entry).
   *   <li>If the account is not configured/found for the company, or there is no reliable NET
   *       amount, no move line is created and a clear warning is added to the move description.
   * </ul>
   */
  protected void addMoveLines(Move move, OcrExtractResult result) {
    String code = AppSettings.get().get("aiocr.move.line.account.code", "");
    if (code == null || code.trim().isEmpty()) {
      warnOnMove(move, "Move line not created: configure 'aiocr.move.line.account.code'.");
      return;
    }
    Account account = resolveCompanyAccountByCode(code.trim(), move.getCompany());
    if (account == null) {
      warnOnMove(
          move,
          "Move line not created: account code '"
              + code.trim()
              + "' is not configured for this company.");
      return;
    }
    BigDecimal net = documentNet(result);
    if (net == null || net.signum() <= 0) {
      warnOnMove(move, "Move line not created: no reliable NET amount was extracted.");
      return;
    }
    MoveLine moveLine = new MoveLine();
    moveLine.setMove(move);
    moveLine.setAccount(account);
    moveLine.setDate(move.getDate());
    moveLine.setDebit(net);
    moveLine.setCredit(BigDecimal.ZERO);
    moveLine.setCurrencyAmount(net);
    moveLine.setCounter(1);
    moveLine.setOrigin(move.getOrigin());
    moveLine.setDescription(
        truncateTo255(
            firstNonBlank(result.getSupplierName(), "OCR document")
                + (result.getInvoiceNumber() != null ? " " + result.getInvoiceNumber() : "")));
    move.addMoveLineListItem(moveLine);
  }

  /** The document NET total: extracted SubTotal when present, else the sum of line NET amounts. */
  static BigDecimal documentNet(OcrExtractResult result) {
    if (result == null) {
      return null;
    }
    if (result.getTotalBase() != null && result.getTotalBase().signum() > 0) {
      return result.getTotalBase();
    }
    BigDecimal sum = null;
    if (result.getLines() != null) {
      for (OcrExtractLine line : result.getLines()) {
        if (line.getNetAmount() != null) {
          sum = sum == null ? line.getNetAmount() : sum.add(line.getNetAmount());
        }
      }
    }
    return sum != null && sum.signum() > 0 ? sum : null;
  }

  /** Append a short warning to the move description, keeping it within the 255-char column. */
  private static void warnOnMove(Move move, String warning) {
    String base = move.getDescription() == null ? "" : move.getDescription();
    move.setDescription(truncateTo255(base + "\n" + warning));
  }

  /** Cap a string to the 255-char varchar column width (null-safe). */
  static String truncateTo255(String s) {
    if (s == null) {
      return null;
    }
    return s.length() <= 255 ? s : s.substring(0, 255);
  }

  /**
   * Resolve an Account by exact code for the given company ONLY (no global fallback); null if none.
   */
  protected Account resolveCompanyAccountByCode(String code, Company company) {
    return Beans.get(AccountRepository.class)
        .all()
        .filter("self.code = :code AND self.company = :company")
        .bind("code", code)
        .bind("company", company)
        .fetchOne();
  }

  private static final java.util.regex.Pattern POS_MARKER =
      java.util.regex.Pattern.compile(
          "\\b(POS|ПОС)\\b",
          java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);

  /**
   * Resolve the Move payment mode from the OCR text: when the text mentions POS/ПОС, the POS
   * terminal mode {@code INC_POS}; otherwise the outgoing bank transfer {@code OUT_WT}.
   *
   * <p><b>Resolved by stable code, never by name.</b> {@code name} is a translatable column, so a
   * lookup by {@code self.name} (e.g. "POS терминал", "Друго плащане") returns nothing — and the
   * Move is then built with <em>no payment mode at all</em>, silently — in any installation whose
   * payment modes are not named in Bulgarian. The codes are stable in a way the names are not:
   * {@code INC_POS} and {@code OUT_WT} are what {@code l10n_bgr/BG_COA/account_paymentMode.csv}
   * seeds.
   *
   * <p>{@code INC_POS} is an incoming mode ({@code inOutSelect = 1}); that is what the name lookup
   * selected too, so the direction is preserved exactly and only the lookup key changes.
   *
   * <p>The default for a document whose payment method could not be classified is the outgoing bank
   * transfer — the same answer {@link #resolveOutgoingPaymentMode} gives for {@code TYPE_OTHER}, so
   * the invoice and Move paths cannot disagree. Deliberately not {@code OUT_OTH}, which still
   * exists: see {@link #outgoingTypeForLookup} for why "other" is not a default.
   *
   * @throws IllegalStateException when {@code OUT_WT} itself is missing. That is a configuration
   *     error, not a document problem, and it is raised rather than swallowed because this path has
   *     no review-note channel of its own — the same choice the missing-fiscal-period guard in
   *     {@link #buildMove} already makes. Never a null, never a first-row fallback, never another
   *     transfer mode such as {@code OUT_DD}, and never a mode created on the fly.
   */
  protected PaymentMode resolveMovePaymentMode(String rawText) {
    if (rawText != null && POS_MARKER.matcher(rawText).find()) {
      PaymentMode pos = findPaymentModeByCode(POS_PAYMENT_MODE_CODE);
      if (pos != null) {
        return pos;
      }
      // POS was detected but the mode is not configured. Falling through to the bank-transfer
      // default is deliberate: it is the project's stated default for an unclassifiable document,
      // and it is a configured record rather than a guess. Only OUT_WT's absence is fatal.
      LOG.warn(
          "OCR move: POS payment mode '{}' is not configured; falling back to the bank-transfer"
              + " default '{}'.",
          POS_PAYMENT_MODE_CODE,
          OUT_BANK_TRANSFER_CODE);
    }
    PaymentMode transfer = findPaymentModeByCode(OUT_BANK_TRANSFER_CODE);
    if (transfer == null) {
      throw new IllegalStateException(missingBankTransferModeMessage());
    }
    return transfer;
  }

  /** Stable code of the outgoing bank transfer — the default payment mode for OCR documents. */
  protected static final String OUT_BANK_TRANSFER_CODE = "OUT_WT";

  /** Stable code of the POS-terminal payment mode (an incoming mode in the Bulgarian chart). */
  protected static final String POS_PAYMENT_MODE_CODE = "INC_POS";

  /**
   * The one message both OCR paths use when {@code OUT_WT} is absent, so a reader meets the same
   * words whichever document type produced it.
   */
  protected static String missingBankTransferModeMessage() {
    return "The outgoing bank-transfer payment mode '"
        + OUT_BANK_TRANSFER_CODE
        + "' is missing or not configured. It is the default payment mode for OCR documents whose"
        + " payment method cannot be classified, so nothing can be assigned without it. Configure"
        + " it under Accounting → Configuration → Financial → Payment modes. Do not substitute an"
        + " \"Other\" payment mode: NAP's SAF-T payment nomenclature has no residual \"other\""
        + " method and such a payment cannot be exported at all.";
  }

  /**
   * A payment mode by its exact stable {@code code}; null when none is configured.
   *
   * <p>Archived modes are excluded: a mode hidden from selection is not configuration a new
   * document may be given. Isolated from the rules above so a test can drive the real resolution
   * logic without a JPA runtime; everything that decides anything lives in the callers.
   */
  protected PaymentMode findPaymentModeByCode(String code) {
    return Beans.get(PaymentModeRepository.class)
        .all()
        .filter("self.code = :code AND (self.archived IS NULL OR self.archived = FALSE)")
        .bind("code", code)
        .fetchOne();
  }

  /**
   * Codes that identify an "Immediate / Upon receipt" payment condition: {@code UPRECPT} is the
   * stock Axelor demo-data code, {@code NET0} the one configured here. Matched by code — never by
   * {@code name}, which is translatable and therefore unstable.
   */
  protected static final List<String> IMMEDIATE_PAYMENT_CONDITION_CODES =
      Arrays.asList("UPRECPT", "NET0");

  /**
   * The company's configured default payment condition ({@code AccountConfig.defPaymentCondition}),
   * falling back to the standard Immediate condition. Null only when neither is configured.
   */
  protected PaymentCondition resolveDefaultPaymentCondition(Company company) {
    if (company != null) {
      try {
        PaymentCondition configured =
            Beans.get(AccountConfigService.class)
                .getAccountConfig(company)
                .getDefPaymentCondition();
        if (configured != null) {
          return configured;
        }
      } catch (Exception e) {
        LOG.warn(
            "No AccountConfig payment condition for company {}: {}",
            company.getName(),
            e.getMessage());
      }
    }
    return resolveImmediatePaymentCondition();
  }

  /**
   * The standard "Immediate" payment condition, identified structurally rather than by its
   * translated display name: first by a known immediate code, then by shape — a single NET / days
   * line payable in 0 days for the full 100%.
   */
  protected PaymentCondition resolveImmediatePaymentCondition() {
    PaymentConditionRepository repo = Beans.get(PaymentConditionRepository.class);
    PaymentCondition byCode =
        repo.all()
            .filter("upper(self.code) in :codes")
            .bind("codes", IMMEDIATE_PAYMENT_CONDITION_CODES)
            .order("id")
            .fetchOne();
    if (byCode != null) {
      return byCode;
    }
    return repo.all()
        .filter(
            "(select count(l) from PaymentConditionLine l where l.paymentCondition = self) = 1"
                + " and exists (select l from PaymentConditionLine l where l.paymentCondition ="
                + " self and l.typeSelect = :net and l.periodTypeSelect = :days and l.paymentTime ="
                + " 0 and l.paymentPercentage = 100)")
        .bind("net", PaymentConditionLineRepository.TYPE_NET)
        .bind("days", PaymentConditionLineRepository.PERIOD_TYPE_DAYS)
        .order("id")
        .fetchOne();
  }

  /**
   * Populate header fields from standard Axelor defaulting services. Each value is set only when
   * the service returns one — no hard-coded ids, no first-record fallback. Journal and the partner
   * payable account are intentionally left for the standard Validate step (it resolves them from
   * AccountConfig/AccountingSituation); we only report their readiness.
   */
  protected void enrichHeader(
      Invoice invoice, Partner partner, OcrExtractResult result, List<String> reviewNotes) {
    Company company = invoice.getCompany();
    // Business rule: OCR-created supplier invoices are ALWAYS in EUR. Bulgaria is on the euro, and
    // a
    // currency "detected" on a noisy fiscal-receipt scan (a stray "$"/"USD", or the lev columns)
    // must never override that. The OCR currency is deliberately ignored; only when EUR itself is
    // not configured do we fall back to the company/partner currency, with a review note.
    Currency eur =
        Beans.get(CurrencyRepository.class).all().filter("upper(self.codeISO) = 'EUR'").fetchOne();
    Currency configuredCurrency =
        partner != null && partner.getCurrency() != null
            ? partner.getCurrency()
            : company != null ? company.getCurrency() : null;
    invoice.setCurrency(eur != null ? eur : configuredCurrency);
    if (eur == null && reviewNotes != null) {
      reviewNotes.add(
          "EUR currency is not configured; the default currency was used. Please add EUR.");
    }
    if (partner != null && partner.getFiscalPosition() != null) {
      invoice.setFiscalPosition(partner.getFiscalPosition());
    }
    if (partner != null) {
      invoice.setPartnerTaxNbr(firstNonBlank(result.getSupplierVat(), partner.getTaxNbr()));
      invoice.setHeadOfficeAddress(partner.getHeadOfficeAddress());
      invoice.setProformaComments(partner.getProformaComments());
      invoice.setNote(partner.getInvoiceComments());
      invoice.setGroupProductsOnPrintings(partner.getGroupProductsOnPrintings());
      if (partner.getInvoicesCopySelect() != null && partner.getInvoicesCopySelect() > 0) {
        invoice.setInvoicesCopySelect(partner.getInvoicesCopySelect());
      }
      try {
        BankDetails defaultBankDetails =
            Beans.get(BankDetailsRepository.class).findDefaultByPartner(partner);
        if (defaultBankDetails != null && Boolean.TRUE.equals(defaultBankDetails.getActive())) {
          invoice.setBankDetails(defaultBankDetails);
        }
      } catch (Exception e) {
        // Optional partner default; an exact OCR IBAN match is attempted later.
      }
    }
    try {
      PaymentMode paymentMode = InvoiceToolService.getPaymentMode(invoice);
      if (paymentMode != null) {
        invoice.setPaymentMode(paymentMode);
      }
    } catch (Exception e) {
      // no deterministic payment mode — readiness flags it
    }
    // Partner → AccountConfig default first (unchanged). Only when that yields nothing do we fall
    // back to the standard Immediate condition, so a draft is never left unpostable in silence.
    PaymentCondition paymentCondition = null;
    try {
      paymentCondition = InvoiceToolService.getPaymentCondition(invoice);
    } catch (Exception e) {
      LOG.warn(
          "No partner/company default payment condition for invoice from partner {}: {}",
          partner == null ? null : partner.getName(),
          e.getMessage());
    }
    if (paymentCondition == null) {
      paymentCondition = resolveDefaultPaymentCondition(company);
    }
    if (paymentCondition != null) {
      invoice.setPaymentCondition(paymentCondition);
    }
    applyOcrAddress(invoice, partner, result, reviewNotes);
    if (company != null) {
      invoice.setCompanyBankDetails(company.getDefaultBankDetails());
      invoice.setPrintingSettings(company.getPrintingSettings());
      if (company.getTaxNumberList() != null && company.getTaxNumberList().size() == 1) {
        invoice.setCompanyTaxNumber(company.getTaxNumberList().get(0));
      } else if (company.getTaxNumberList() != null
          && company.getTaxNumberList().size() > 1
          && result.getCustomerVat() != null) {
        reviewNotes.add(
            "Several company tax numbers are configured; verify the one matching OCR customer VAT '"
                + result.getCustomerVat()
                + "'.");
      }
      try {
        AccountConfigService accountConfigService = Beans.get(AccountConfigService.class);
        AccountConfig accountConfig = accountConfigService.getAccountConfig(company);
        invoice.setInAti(accountConfigService.getInvoiceInAti(accountConfig));
      } catch (Exception e) {
        // inAti stays default
      }
    }
  }

  /**
   * Set the supplier payment mode from the OCR text when it is unambiguous. The detected method
   * type ({@link BgOcrPaymentMethod}) is resolved to an active OUT {@link PaymentMode}; the
   * invoice's mode is set only when exactly one such mode exists. The document value replaces a
   * conflicting supplier/company default; zero or several candidates leave that default unchanged.
   */
  protected void applyOcrPaymentMethod(
      Invoice invoice, OcrExtractResult result, List<String> reviewNotes) {
    if (result == null) {
      return;
    }
    String structuredTerm = result.getPaymentTerm();
    Set<Integer> types = BgOcrPaymentMethod.detectPaymentTypes(structuredTerm);
    if (types.isEmpty()) {
      types = BgOcrPaymentMethod.detectPaymentTypes(result.getRawText());
    }
    if (types.size() > 1) {
      reviewNotes.add(
          "OCR payment wording mentions several methods; payment mode was left unchanged.");
      return;
    }
    if (types.isEmpty()) {
      if (structuredTerm != null && !structuredTerm.trim().isEmpty()) {
        reviewNotes.add(
            "OCR payment term: '" + structuredTerm.trim() + "' - verify the payment condition.");
      }
      return;
    }
    Integer type = types.iterator().next();
    String label = BgOcrPaymentMethod.label(type);
    if (invoice.getPaymentMode() != null && type.equals(invoice.getPaymentMode().getTypeSelect())) {
      return;
    }
    PaymentMode resolved = resolveOutgoingPaymentMode(type);
    if (resolved != null) {
      if (invoice.getPaymentMode() != null) {
        reviewNotes.add(
            "The unambiguous OCR payment method ('"
                + label
                + "') replaced the supplier/company default.");
      }
      invoice.setPaymentMode(resolved);
    } else if (type == PaymentModeRepository.TYPE_OTHER) {
      // The default could not be applied. Named as the configuration fault it is: every OCR
      // document in this installation lands here, and no edit to this invoice will help. The
      // invoice keeps whatever mode it already had; when it had none, the readiness report's
      // "Payment mode missing" ventilate blocker stops it being treated as ready.
      reviewNotes.add(
          "OCR could not classify the payment method, and the bank-transfer default could not be"
              + " applied. "
              + missingBankTransferModeMessage());
    } else {
      reviewNotes.add(
          "OCR suggests payment method '"
              + label
              + "' but no matching outgoing payment mode is configured — set the payment mode"
              + " manually.");
    }
  }

  /**
   * The outgoing {@link PaymentMode} to use for a detected payment type. Several OUT modes can
   * share one type in the BG chart (cash = "Плащане в брой" {@code OUT_CSH} + "Изходящ касов"
   * {@code OUT_FIS}; transfer = "Изходящ банков превод" {@code OUT_WT} + "SEPA Директен дебит"
   * {@code OUT_DD}). Requiring a UNIQUE match therefore left an explicit cash invoice on the
   * bank-transfer default. Instead, prefer the canonical stock code for the type; fall back to the
   * lowest-id match so the choice is always deterministic. Null when no outgoing mode of that type
   * exists — or, for the {@code TYPE_OTHER} default, when the exact {@code OUT_WT} is absent.
   */
  protected PaymentMode resolveOutgoingPaymentMode(int typeSelect) {
    // A detected type says what the document claims; TYPE_OTHER says only that nothing was
    // detected. The first may settle for the lowest-id mode of its own type, the second may not —
    // "we could not tell" must never be answered with SEPA direct debit because it happens to carry
    // the same typeSelect as the transfer the redirect asked for.
    boolean defaulted = typeSelect == PaymentModeRepository.TYPE_OTHER;
    int effectiveType = outgoingTypeForLookup(typeSelect);
    List<PaymentMode> matches = fetchOutgoingPaymentModes(effectiveType);
    String preferred = preferredOutPaymentCode(effectiveType);
    if (preferred != null) {
      for (PaymentMode m : matches) {
        if (preferred.equalsIgnoreCase(m.getCode())) {
          return m; // exact stable code wins over row order, whatever else shares the type
        }
      }
    }
    if (defaulted) {
      // Only OUT_WT is an acceptable default. Null here is reported by the caller as a named
      // configuration blocker, and the invoice keeps whatever payment mode it already had.
      return null;
    }
    if (matches.isEmpty()) {
      return null;
    }
    return matches.get(0); // deterministic: lowest id
  }

  /**
   * The outgoing payment modes carrying a given type, lowest id first.
   *
   * <p>Archived modes are excluded — a mode hidden from selection is not configuration a new
   * document may be given. Split out of {@link #resolveOutgoingPaymentMode} so the selection rules
   * can be exercised against real candidate lists without a JPA runtime.
   */
  protected List<PaymentMode> fetchOutgoingPaymentModes(int typeSelect) {
    return Beans.get(PaymentModeRepository.class)
        .all()
        .filter(
            "self.typeSelect = :t AND self.inOutSelect = :out"
                + " AND (self.archived IS NULL OR self.archived = FALSE)")
        .bind("t", typeSelect)
        .bind("out", PaymentModeRepository.OUT)
        .order("id")
        .fetch();
  }

  /**
   * An OCR document nobody could classify is looked up as an outgoing bank transfer, not as
   * "other".
   *
   * <p>{@code OUT_OTH} ("Друго плащане") exists in the Bulgarian chart and carries {@code
   * TYPE_OTHER}, so without this redirect it is what {@link #resolveOutgoingPaymentMode} returns —
   * by type, unprompted. That is the wrong default for a document the extractor could not read:
   * "other" is a positive statement by an accountant about how something was paid, and NAP's SAF-T
   * payment nomenclature ({@code nomenclatures/nra/nom_paymentmethod.csv}) has three methods and
   * nine method-mechanism pairs, none of them a residual "other" — such a payment cannot be
   * described in the export at all. A bank transfer is both the common case and exportable.
   *
   * <p>This changes no existing record. {@code INC_OTH} / {@code OUT_OTH} stay in the seed and stay
   * selectable by hand; the redirect only governs what OCR <em>proposes</em> on a NEW document.
   *
   * <p><b>The redirect names a type, which is not by itself an answer.</b> Two OUT modes carry
   * {@code TYPE_TRANSFER} — {@code OUT_WT} and the SEPA direct debit {@code OUT_DD} — so the
   * redirect alone would let a document nobody could classify be settled by direct debit whenever
   * {@code OUT_WT} sorted second or was missing. {@link #resolveOutgoingPaymentMode} therefore
   * accepts nothing but the exact {@code OUT_WT} once this redirect has fired.
   *
   * <p>Measured: {@code BgOcrPaymentMethod#detectPaymentTypes} only ever emits TRANSFER, CASH,
   * BANK_CARD and CHEQUE, so nothing in today's OCR path reaches TYPE_OTHER. The branch exists
   * because {@link #resolveOutgoingPaymentMode} is {@code protected} and the next caller will not
   * know that.
   */
  private static int outgoingTypeForLookup(int typeSelect) {
    return typeSelect == PaymentModeRepository.TYPE_OTHER
        ? PaymentModeRepository.TYPE_TRANSFER
        : typeSelect;
  }

  /**
   * Canonical stock outgoing payment-mode code for a detected type; null when none is canonical.
   *
   * <p>No TYPE_OTHER case: it is redirected to TYPE_TRANSFER by {@link #outgoingTypeForLookup}
   * before the lookup, so a branch here would be unreachable — and returning {@code OUT_OTH} from
   * it is exactly the default this correction removes.
   */
  private static String preferredOutPaymentCode(int typeSelect) {
    switch (typeSelect) {
      case PaymentModeRepository.TYPE_CASH:
        return "OUT_CSH";
      case PaymentModeRepository.TYPE_TRANSFER:
        return "OUT_WT";
      case PaymentModeRepository.TYPE_BANK_CARD:
        return "OUT_CC";
      default:
        return null;
    }
  }

  /**
   * Stores the document's printed net and VAT beside its gross, and marks them usable.
   *
   * <p>Only a breakdown that holds together is recorded: all three figures present, a positive
   * gross, {@code net + VAT = gross} to within two rounding steps, AND an implied VAT rate that
   * could be a real one. A document whose own summary does not add up proves nothing about its
   * components, and half a breakdown is worse than none — the component check would then refuse
   * invoices for a discrepancy the document itself contains.
   *
   * <p>Balancing is necessary but not sufficient. Azure can pair a stray {@code SubTotal} (one
   * line's net) with a subtotal {@code InvoiceTotal}: the two still add up, to the wrong gross,
   * with a VAT that is many times the net. For example net 4.98, VAT 75.77, gross 80.75: {@code
   * 4.98 + 75.77 = 80.75} exactly, yet the implied rate is ~1521%. No BG rate exceeds 20%, so a
   * breakdown whose VAT is more than {@link #MAX_PLAUSIBLE_VAT_RATE} of its net (or whose net is
   * not positive against a positive gross) is extraction noise, not a summary, and must never be
   * marked trusted — otherwise the component check reconciles the invoice against nonsense.
   *
   * <p>The marker is set last and only on success, so it means exactly "these two columns were
   * written from a coherent document summary".
   */
  protected void applyDocumentBreakdown(Invoice invoice, OcrExtractResult result) {
    BigDecimal net = result.getTotalBase();
    BigDecimal vat = result.getTotalVat();
    BigDecimal gross = result.getTotalGross();
    if (net == null || vat == null || gross == null || gross.signum() <= 0) {
      return;
    }
    if (net.add(vat).subtract(gross).abs().compareTo(new BigDecimal("0.02")) > 0) {
      return;
    }
    // A positive gross with a non-positive net, or a VAT larger than any real rate could produce,
    // is a mis-paired extraction that merely happens to balance — not a document breakdown.
    if (net.signum() <= 0
        || vat.signum() < 0
        || vat.compareTo(net.multiply(MAX_PLAUSIBLE_VAT_RATE)) > 0) {
      return;
    }
    invoice.setOcrDocumentNetTotal(net);
    invoice.setOcrDocumentVatTotal(vat);
    invoice.setOcrDocumentBreakdownAvailable(true);
  }

  /**
   * The largest VAT-to-net ratio a real document breakdown can show. BG rates are 0/9/20%; the
   * ceiling is set above 20% only to leave room for per-line rounding on a tiny net, never high
   * enough to admit a mis-paired net and subtotal (upload 70's ~1521%).
   */
  private static final BigDecimal MAX_PLAUSIBLE_VAT_RATE = new BigDecimal("0.30");

  /**
   * Gives every rate-less line the one rate the document's summary proves, or leaves them alone.
   *
   * <h2>Why this is here and not left to the accountant</h2>
   *
   * A document whose items print no VAT column still states its VAT in the summary, and that
   * summary is arithmetic, not a hint: for invoice #12, net 146.57 with VAT 29.31 is reproduced by
   * 20% and by no other supported rate. Before this, the missing rate became an explicit 0.00 (see
   * {@code AzureDocumentIntelligenceExtractor.normalizeAzureLineAmounts}), every line was mapped to
   * {@code BG_ZRP}, and a 20% invoice was booked with no VAT at all — which the validation gate
   * then refused, correctly but only after the fact, leaving ten lines to retype by hand.
   *
   * <h2>What it will not touch</h2>
   *
   * <ul>
   *   <li><b>Any line the document actually stated a rate or a VAT amount for.</b> The test is
   *       {@link OcrExtractLine#isVatEvidenceExplicit()}, and it is applied to ALL monetary lines:
   *       one line with real evidence means this document prints its VAT somewhere, so a mixed-rate
   *       invoice is never flattened onto a single recovered rate.
   *   <li><b>A genuinely zero-rated document.</b> No VAT in the summary, nothing to recover, and
   *       {@code BG_ZRP} stays.
   *   <li><b>Anything ambiguous or contradictory.</b> {@link BgOcrVatRateInference} refuses, the
   *       lines keep exactly what was extracted, and the reason goes into the review notes.
   * </ul>
   *
   * <p>Supplier purchases only: this runs inside {@code buildDraft}, which builds supplier
   * invoices. The customer-invoice reroute converts an already-built draft and is untouched.
   */
  protected void recoverMissingLineVatRates(
      OcrExtractResult result, boolean receiptStyle, List<String> reviewNotes) {
    if (result == null || result.getLines() == null || result.getLines().isEmpty()) {
      return;
    }
    List<OcrExtractLine> monetary = new ArrayList<>();
    for (OcrExtractLine line : result.getLines()) {
      if (receiptStyle && isFiscalSummaryLine(line.getDescription())) {
        continue; // a fiscal bon's summary rows are not lines and are dropped below anyway
      }
      if (line.getNetAmount() == null || line.getNetAmount().signum() == 0) {
        continue; // nothing to tax
      }
      if (line.isVatEvidenceExplicit()) {
        return; // the document states VAT somewhere; never second-guess it
      }
      monetary.add(line);
    }
    if (monetary.isEmpty()) {
      return;
    }

    BigDecimal lineNetSum = BigDecimal.ZERO;
    for (OcrExtractLine line : monetary) {
      lineNetSum = lineNetSum.add(line.getNetAmount());
    }

    BgOcrVatRateInference inference =
        BgOcrVatRateInference.infer(
            result.getTotalBase(), result.getTotalVat(), result.getTotalGross(), lineNetSum);
    if (!inference.isInferred()) {
      if (inference.isDocumentZeroRated()) {
        // The one refusal that IS an answer: the document states zero VAT, so the lines are
        // zero-rated on purpose. Written as a decision rather than left to a fallback, because a
        // fallback would silently zero-rate an unknown.
        for (OcrExtractLine line : monetary) {
          line.setDerivedVatRate(BigDecimal.ZERO);
          line.setDerivedVatAmount(BigDecimal.ZERO);
          line.setGrossAmount(line.getNetAmount());
        }
        return;
      }
      // Everything else stays UNKNOWN — never zero. A line with no rate gets no tax code, the
      // readiness check reports it and the validation blocker refuses, so an ambiguous,
      // mixed-rate or contradictory document reaches the accountant as "to review" instead of
      // being quietly booked without VAT.
      if (reviewNotes != null) {
        reviewNotes.add(inference.getReason());
      }
      if (result.getDiscrepancies() != null) {
        result.getDiscrepancies().add(inference.getReason());
      }
      return;
    }

    BigDecimal rate = inference.getRate();
    for (OcrExtractLine line : monetary) {
      // Derived, not read: the rate stays distinguishable from one the document printed.
      line.setDerivedVatRate(rate);
      BigDecimal vat =
          line.getNetAmount()
              .multiply(rate)
              .divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
      line.setDerivedVatAmount(vat);
      line.setGrossAmount(line.getNetAmount().add(vat));
    }
    if (reviewNotes != null) {
      reviewNotes.add(inference.getReason());
    }
    if (result.getDiscrepancies() != null) {
      result.getDiscrepancies().add(inference.getReason());
    }
  }

  /** Build one supplier-invoice line from an OCR line (product/account + tax resolution). */
  protected InvoiceLine buildInvoiceLine(
      Invoice invoice, OcrExtractLine src, OcrExtractResult result) throws Exception {
    InvoiceLine line = new InvoiceLine();
    line.setInvoice(invoice);
    line.setProductName(firstNonBlank(src.getDescription(), src.getProductCode()));
    line.setDescription(src.getDescription());
    line.setQty(src.getQty() != null ? src.getQty() : BigDecimal.ONE);
    line.setPrice(src.getUnitPrice());
    line.setPriceDiscounted(src.getUnitPrice());
    line.setInTaxPrice(unitGrossPrice(src));
    line.setExTaxTotal(src.getNetAmount());
    line.setInTaxTotal(
        src.getGrossAmount() != null
            ? src.getGrossAmount()
            : nz(src.getNetAmount()).add(nz(src.getVatAmount())));
    line.setTaxRate(src.getVatRate());

    // Line product/account resolution (exact supplier product code → per-company generic OCR
    // purchase product). Description-only if none configured — the readiness checker flags it.
    resolveLineProductAndAccount(line, src, invoice);

    // Tax resolution. No failure here may be silent: a line that simply carries no tax lets the
    // invoice reach ocrReviewStatus=complete while being unventilatable. The
    // mapping deliberately does not guess an unmapped rate (special regimes: reverse charge, ВОП,
    // import, exempt) — but the loss must at least be visible, so each failure adds a discrepancy.
    String taxCode = BgOcrVatMapping.taxCodeForRate(src.getVatRate());
    String lineLabel =
        firstNonBlank(firstNonBlank(src.getDescription(), src.getProductCode()), "line");
    if (taxCode == null) {
      result
          .getDiscrepancies()
          .add(
              "No tax set on '"
                  + lineLabel
                  + "': VAT rate "
                  + (src.getVatRate() == null ? "missing" : src.getVatRate() + "%")
                  + " is not one of the mapped BG purchase rates (20/9/0). If this is a special"
                  + " regime (reverse charge, ВОП, import, exempt), set the tax manually.");
    } else {
      line.setTaxCode(taxCode);
      Tax tax = taxRepo.all().filter("self.code = :c").bind("c", taxCode).fetchOne();
      if (tax == null) {
        result
            .getDiscrepancies()
            .add(
                "No tax set on '"
                    + lineLabel
                    + "': tax code "
                    + taxCode
                    + " is not configured in this database (l10n_bgr chart of accounts).");
      } else {
        TaxLine taxLine = Beans.get(TaxService.class).getTaxLine(tax, result.getInvoiceDate());
        if (taxLine == null) {
          result
              .getDiscrepancies()
              .add(
                  "No tax set on '"
                      + lineLabel
                      + "': tax "
                      + taxCode
                      + " has no active version covering "
                      + (result.getInvoiceDate() == null
                          ? "the (missing) invoice date"
                          : result.getInvoiceDate().toString())
                      + ".");
        } else {
          HashSet<TaxLine> taxLineSet = new HashSet<>();
          taxLineSet.add(taxLine);
          line.setTaxLineSet(taxLineSet);
        }
      }
    }
    // The unit, LAST — after product resolution has had its chance to supply the product's own.
    //
    // ⚠️ ЗАЩО ИЗОБЩО СЕ НАЛАГА. Доказано върху жива фактура (№17, 11 реда): всичките десет OCR реда
    // се вързаха към генеричния продукт OCR-GEN, чието base_product.unit е NULL, а единственото
    // присвояване по този път е зад `if (product.getUnit() != null)` в
    // resolveLineProductAndAccount.
    // Няма единица на продукта → няма присвояване → и нито един признак, че нещо липсва.
    // Единайсетият
    // ред (закръглението) няма и продукт и стигна дотам по същия начин.
    //
    // ⚠️ И ЗАЩО НЕ СТИГА onNew ДЕЙСТВИЕТО ВЪВ ФОРМАТА: intake-ът работи в нишка без потребител и
    // без
    // изглед — нито едно view действие не се изпълнява тук. Стойността по подразбиране в UI е
    // удобство
    // за този, който пише на ръка; гаранцията за продукцията е това извикване.
    unitDefaults().applyTo(line);
    return line;
  }

  /** Seam: the production-path tests drive the real {@link #buildInvoiceLine} with a stub here. */
  protected InvoiceLineUnitDefaultService unitDefaults() {
    return Beans.get(InvoiceLineUnitDefaultService.class);
  }

  /**
   * Fiscal-receipt summary/payment/tax row labels (the label:value breakdown a fiscal bon prints,
   * which Azure mis-parses as line items). Matched conservatively and ONLY for receipt-style
   * invoices, so a real product on a normal invoice is never dropped. OCR mangles some Cyrillic to
   * look-alike Latin ("НЕТО"→"HETO", "СТ-СТ"→"CT-CT"), so both spellings are listed.
   */
  private static final String[] FISCAL_SUMMARY_LABELS = {
    "ед.цена",
    "ед. цена",
    "единична цена",
    "количество",
    "сума",
    "отстъпка",
    "в т.ч",
    "в т. ч",
    "ддс",
    "дс*",
    "акциз",
    "нето",
    "heto",
    "нето ст",
    "нето стойност",
    "ct-ct",
    "ст-ст",
    "обща сума",
    "обменен курс",
    "курс",
    "всичко",
    "кредитни",
    "дебитни",
    "карти",
    "в брой",
    "в кеш",
    "b=",
    "b ="
  };

  /**
   * True when an OCR "item" description is actually a fiscal receipt summary/payment/tax row (or a
   * bare number), not a purchased product. Conservative: applied only for receipt-style invoices.
   */
  static boolean isFiscalSummaryLine(String description) {
    if (description == null) {
      return true; // no description → not a real product line on a receipt
    }
    String t =
        description
            .toLowerCase(Locale.ROOT)
            .replace("#", "")
            .replace("*", "")
            .replace("№", "")
            .trim();
    if (t.isEmpty() || t.matches("[0-9.,%\\s:/-]+")) {
      return true; // blank or pure-number/symbol row
    }
    for (String label : FISCAL_SUMMARY_LABELS) {
      if (t.startsWith(label)) {
        return true;
      }
      // OCR frequently drops (or mangles) the FIRST glyph of a fiscal-summary label, so the row
      // arrives as e.g. "д.цена" (ед.цена), "оличество" (количество) or "ума*В" (сума*В). Match the
      // label with its first character removed too, so these truncated summary rows are still
      // dropped instead of becoming bogus product lines. Guarded to labels of >=4 chars so the
      // truncated form stays distinctive enough not to catch a real product.
      if (label.length() >= 4 && t.startsWith(label.substring(1))) {
        return true;
      }
    }
    return false;
  }

  /**
   * Synthesize the single real product line for a fiscal receipt whose OCR "items" were all summary
   * rows: the recovered product name (e.g. "Бензин А-95Н") with the invoice's own base/gross
   * totals.
   */
  protected InvoiceLine buildReceiptProductLine(Invoice invoice, OcrExtractResult result)
      throws Exception {
    BigDecimal base = result.getTotalBase();
    BigDecimal gross = result.getTotalGross();
    BigDecimal vat = result.getTotalVat();
    OcrExtractLine synthetic = new OcrExtractLine();
    String name = recoverReceiptProductName(result.getRawText());
    synthetic.setDescription(firstNonBlank(name, "Стока/услуга (фискален бон)"));
    synthetic.setQty(BigDecimal.ONE);
    synthetic.setUnitPrice(base);
    synthetic.setNetAmount(base != null ? base : gross);
    synthetic.setGrossAmount(gross);
    synthetic.setVatAmount(vat);
    // Standard 20% BG VAT when the receipt's own base/VAT confirm it; otherwise leave unset.
    if (base != null && base.signum() > 0 && vat != null && vat.signum() > 0) {
      synthetic.setVatRate(new BigDecimal("20"));
    }
    return buildInvoiceLine(invoice, synthetic, result);
  }

  /**
   * Recover a fiscal receipt's product name from the OCR text: the first non-blank line after the
   * "Фактура №…" line that is not a fiscal device/marker line (leading "#", the summary table start
   * "ед.цена"/"д.цена", or a known summary label). Returns {@code null} when none is found.
   */
  static String recoverReceiptProductName(String rawText) {
    if (rawText == null) {
      return null;
    }
    String[] lines = rawText.split("\\r?\\n");
    int start = -1;
    for (int i = 0; i < lines.length; i++) {
      String t = lines[i].trim().toLowerCase(Locale.ROOT);
      if (t.startsWith("фактура") || t.startsWith("фактура №") || t.startsWith("фактура n")) {
        start = i;
        break;
      }
    }
    if (start < 0) {
      return null;
    }
    for (int i = start + 1; i < lines.length; i++) {
      String t = lines[i].trim();
      if (t.isEmpty() || t.startsWith("#")) {
        continue; // fiscal-device transaction/loyalty noise lines
      }
      String lower = t.toLowerCase(Locale.ROOT);
      if (lower.startsWith("ед.цена")
          || lower.startsWith("ед. цена")
          || lower.startsWith("д.цена")) {
        return null; // reached the summary table without a product name
      }
      if (isFiscalSummaryLine(t)) {
        continue;
      }
      // A plausible product name has at least one letter and is not a pure number/code.
      if (t.matches(".*[A-Za-zА-Яа-яЁё].*")) {
        return t;
      }
    }
    return null;
  }

  /**
   * Override a receipt's header totals with the amounts explicitly labelled in EUR, so the invoice
   * is booked in EUR even when Azure's InvoiceTotal picked the parallel BGN/ЛЕВА value. A BG fiscal
   * receipt prints both currencies:
   *
   * <pre>
   *   ОБЩА СУМА ЕВРО    71.95     ← EUR gross (used)
   *   ОБЩА СУМА ЛЕВА   140.72     ← BGN gross (ignored)
   *   нето стойност     59.96     ← EUR net (used)
   * </pre>
   *
   * Only overrides when an unambiguous EUR gross is found (the label immediately followed by a
   * number); the EUR net is taken from "нето стойност" when present (VAT = gross − net), otherwise
   * the existing base/VAT are scaled by the same gross ratio so they, too, are expressed in EUR.
   * Receipts whose only EUR total sits in a "… €"-labelled invoice section (p2/p6) carry no bare
   * "ОБЩА СУМА ЕВРО &lt;number&gt;" line, so nothing is overridden and Azure's (already-EUR) total
   * stands. Never trusts Azure's ЛЕВА value over a clearly labelled EUR total.
   */
  protected void applyReceiptEurTotals(OcrExtractResult result) {
    if (result == null) {
      return;
    }
    BigDecimal[] eur = receiptEurGrossNet(result.getRawText());
    BigDecimal grossEur = eur[0];
    if (grossEur == null || grossEur.signum() <= 0) {
      return; // no clearly-labelled EUR total on this receipt — keep Azure's value
    }
    BigDecimal netEur = eur[1];
    BigDecimal oldGross = result.getTotalGross();
    result.setTotalGross(grossEur);
    if (netEur != null && netEur.signum() > 0 && netEur.compareTo(grossEur) <= 0) {
      result.setTotalBase(netEur);
      result.setTotalVat(grossEur.subtract(netEur));
    } else if (oldGross != null && oldGross.signum() > 0) {
      // Scale the existing (BGN) base/VAT to EUR by the same gross ratio (≈ 1/1.95583).
      BigDecimal scale = grossEur.divide(oldGross, 6, RoundingMode.HALF_UP);
      if (result.getTotalBase() != null) {
        result.setTotalBase(
            result.getTotalBase().multiply(scale).setScale(2, RoundingMode.HALF_UP));
      }
      if (result.getTotalVat() != null) {
        result.setTotalVat(result.getTotalVat().multiply(scale).setScale(2, RoundingMode.HALF_UP));
      }
    }
  }

  /** BG euro peg: 1 EUR = 1.95583 BGN (fixed). */
  private static final BigDecimal BGN_PER_EUR = new BigDecimal("1.95583");

  /**
   * Accounting-consistency repair for an invoice whose taxable base and gross are in EUR but whose
   * VAT was read in BGN. Some BG invoices print the net/total in € while the VAT line is in лв.;
   * Azure then returns {@code TotalTax} as the BGN amount, so {@code base + VAT ≠ gross} (e.g. base
   * 8.75, VAT 3.42, gross 10.50 → 8.75 + 3.42 = 12.17 ≠ 10.50). When the mismatch is explained
   * exactly by the peg — i.e. the stored VAT ≈ (gross − base) × 1.95583 — the VAT is the BGN value
   * of the real EUR VAT, so it is replaced with {@code gross − base}. Conservative: applied only
   * when base/VAT/gross are all present and positive, the totals do NOT already reconcile, and the
   * stored VAT matches the pegged EUR VAT within tolerance. Genuinely-reconciling invoices and
   * unrelated discrepancies are left untouched (the readiness checker still flags those).
   */
  static void reconcileEurVat(OcrExtractResult result) {
    if (result == null) {
      return;
    }
    BigDecimal base = result.getTotalBase();
    BigDecimal vat = result.getTotalVat();
    BigDecimal gross = result.getTotalGross();
    if (base == null
        || vat == null
        || gross == null
        || base.signum() <= 0
        || vat.signum() <= 0
        || gross.signum() <= 0) {
      return;
    }
    BigDecimal eurVat = gross.subtract(base);
    if (eurVat.signum() <= 0) {
      return; // gross ≤ base — not a VAT-currency case
    }
    if (base.add(vat).subtract(gross).abs().compareTo(new BigDecimal("0.02")) <= 0) {
      return; // already reconciles in one currency — nothing to repair
    }
    BigDecimal pegged = eurVat.multiply(BGN_PER_EUR);
    BigDecimal tol = eurVat.multiply(new BigDecimal("0.02")).max(new BigDecimal("0.03"));
    if (vat.subtract(pegged).abs().compareTo(tol) <= 0) {
      result.setTotalVat(eurVat.setScale(2, RoundingMode.HALF_UP));
    }
  }

  // "<amount> EUR" / "<amount> €" — an amount the document explicitly labels as euro.
  private static final Pattern EUR_LABELLED_AMOUNT =
      Pattern.compile("(\\d[\\d ]*[.,]\\d{2})\\s*(?:€|EUR|eur)");

  /**
   * Book net/VAT/gross from the amounts explicitly labelled in EUR when a NORMAL invoice prints BGN
   * and EUR values in parallel and Azure's SubTotal/InvoiceTotal picked the BGN column. The ДУКОВ
   * invoices print e.g. "164.40 EUR" (net), "32.88 EUR" (VAT) and "197.28 EUR" (gross) beside
   * "Данъчна основа: BGN 321.54" / "Сума за плащане: BGN 385.85"; Azure returned the BGN net/gross
   * but the EUR VAT, so base + VAT ≠ gross.
   *
   * <p>When the totals do NOT already reconcile and the text carries explicit "&lt;amount&gt; EUR"
   * / "&lt;amount&gt; €" values, pick the EUR (net, gross) pair whose {@code net + current VAT =
   * gross} (the largest such gross), so the whole invoice is booked in EUR. Reconciliation is the
   * safety check: nothing changes unless a consistent EUR pair is found, so a BGN-only invoice (no
   * EUR tokens) and a EUR-only invoice (already reconciles) are both left untouched. Does not trust
   * Azure's SubTotal/InvoiceTotal when they conflict with the labelled EUR amounts.
   */
  static void applyEurTotalsFromText(OcrExtractResult result) {
    if (result == null) {
      return;
    }
    BigDecimal base = result.getTotalBase();
    BigDecimal vat = result.getTotalVat();
    BigDecimal gross = result.getTotalGross();
    if (vat == null || vat.signum() <= 0) {
      return; // need a VAT anchor to reconcile against
    }
    BigDecimal tol = new BigDecimal("0.02");
    if (base != null && gross != null && base.add(vat).subtract(gross).abs().compareTo(tol) <= 0) {
      return; // already reconciles in one currency — nothing to repair
    }
    List<BigDecimal> eur = eurLabelledAmounts(result.getRawText());
    if (eur.size() < 2) {
      return;
    }
    BigDecimal netEur = null;
    BigDecimal grossEur = null;
    for (BigDecimal g : eur) {
      for (BigDecimal n : eur) {
        if (n.compareTo(g) >= 0) {
          continue;
        }
        if (n.add(vat).subtract(g).abs().compareTo(tol) <= 0
            && (grossEur == null || g.compareTo(grossEur) > 0)) {
          netEur = n;
          grossEur = g;
        }
      }
    }
    if (netEur != null) {
      result.setTotalBase(netEur);
      result.setTotalGross(grossEur);
    }
  }

  /** The distinct positive amounts a document explicitly labels in EUR, in order of appearance. */
  private static List<BigDecimal> eurLabelledAmounts(String rawText) {
    List<BigDecimal> out = new ArrayList<>();
    if (rawText == null) {
      return out;
    }
    Matcher m = EUR_LABELLED_AMOUNT.matcher(rawText);
    while (m.find()) {
      BigDecimal v = parseReceiptAmount(m.group(1));
      if (v != null && !out.contains(v)) {
        out.add(v);
      }
    }
    return out;
  }

  /**
   * The EUR gross + net a fiscal receipt labels, as {@code [gross, net]} (either may be null). Both
   * null → the caller keeps Azure's totals.
   *
   * <p>A BG fiscal receipt prints two parallel "ОБЩА СУМА" totals — EUR and BGN/ЛЕВА — pegged at
   * {@code BGN = EUR × 1.95583}. The naive "number immediately after «ОБЩА СУМА ЕВРО»" is unsafe:
   *
   * <ul>
   *   <li>the value physically following the EUR label is sometimes the BGN amount (OCR reading
   *       order), so a receipt whose EUR total is 57.18 was booked as 111.83;
   *   <li>an "обменен курс …" annotation can sit between the label and its amount;
   *   <li>OCR often drops/mangles the label's leading glyph ("ОБЩА СУМА ЕВРО" → "Б©А СУМА ЕВРО",
   *       "нето стойност" → "ето стойност").
   * </ul>
   *
   * So the labels are matched by their distinctive tail ({@code сума евро}/{@code сума лева}/{@code
   * стойност}) via CONTAINS, the amount is taken from the next couple of lines (skipping the курс
   * line, stopping before the next total label), and when both EUR and BGN totals are found and
   * differ by the fixed peg, the EUR gross is the SMALLER of the two — regardless of which label
   * OCR attached each value to.
   */
  static BigDecimal[] receiptEurGrossNet(String rawText) {
    if (rawText == null) {
      return new BigDecimal[] {null, null};
    }
    String[] lines = rawText.split("\\r?\\n");
    BigDecimal eurTotal = amountAfterContains(lines, "сума евро", 2);
    BigDecimal levaTotal = amountAfterContains(lines, "сума лева", 2);
    BigDecimal grossEur;
    if (eurTotal != null && levaTotal != null) {
      BigDecimal lo = eurTotal.min(levaTotal);
      BigDecimal hi = eurTotal.max(levaTotal);
      BigDecimal ratio = lo.signum() > 0 ? hi.divide(lo, 4, RoundingMode.HALF_UP) : BigDecimal.ONE;
      // A clean EUR/BGN pair differs by the peg 1.95583; then the EUR total is always the smaller.
      grossEur =
          (ratio.compareTo(new BigDecimal("1.90")) >= 0
                  && ratio.compareTo(new BigDecimal("2.02")) <= 0)
              ? lo
              : eurTotal; // not a clean peg pair → trust the EUR-labelled value
    } else if (eurTotal != null) {
      grossEur = eurTotal;
    } else {
      grossEur = amountAfterContains(lines, "всичко", 1); // fuel receipts repeat the EUR gross here
      if (grossEur == null) {
        grossEur = amountAfterContains(lines, "сичко", 1); // OCR-truncated ВСИЧКО
      }
    }
    BigDecimal netEur = amountAfterContains(lines, "стойност", 2); // (нето) стойност — EUR net
    return new BigDecimal[] {grossEur, netEur};
  }

  /**
   * The first positive amount on one of the up-to {@code window} lines that follow the first line
   * whose normalized text CONTAINS {@code needle}. Annotation lines in between (e.g. "обменен курс
   * …") start with letters and parse to null, so they are skipped; the scan stops at the NEXT total
   * label ("обща"/"сума") so a label never borrows the adjacent total's amount. Returns {@code
   * null} when the needle is absent or no amount is found in the window.
   */
  private static BigDecimal amountAfterContains(String[] lines, String needle, int window) {
    for (int i = 0; i < lines.length - 1; i++) {
      String norm = lines[i].replace("#", "").replace("*", " ").trim().toLowerCase(Locale.ROOT);
      if (!norm.contains(needle)) {
        continue;
      }
      for (int j = i + 1; j <= i + window && j < lines.length; j++) {
        String nj = lines[j].replace("#", "").replace("*", " ").trim().toLowerCase(Locale.ROOT);
        if (nj.contains("обща") || nj.contains("сума")) {
          break; // reached the next total label — do not cross into its amount
        }
        BigDecimal v = parseReceiptAmount(lines[j]);
        if (v != null) {
          return v;
        }
      }
    }
    return null;
  }

  /**
   * Parse a receipt amount token ("71.95", "1 457.50", "4.19#", "10.50 €"); null when not numeric.
   */
  private static BigDecimal parseReceiptAmount(String s) {
    if (s == null) {
      return null;
    }
    String t = s.trim().replace(" ", "").replace(" ", "");
    // Keep the leading numeric run (drop a trailing "#", "€", "B", "лв." etc.).
    Matcher m = Pattern.compile("^-?\\d[\\d.,]*").matcher(t);
    if (!m.find()) {
      return null;
    }
    String num = m.group();
    if (num.contains(",") && num.contains(".")) {
      num = num.replace(",", ""); // "1,457.50" → thousands comma
    } else {
      num = num.replace(",", "."); // "71,95" → decimal comma
    }
    try {
      BigDecimal v = new BigDecimal(num);
      return v.signum() > 0 ? v : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** Legally-mandated fiscal-receipt phrases printed by a Bulgarian fiscal device (ФУ). */
  private static final String[] FISCAL_RECEIPT_MARKERS = {
    "фискален бон", "касова бележка", "касов бон"
  };

  /**
   * A wall-clock time of day (HH:MM:SS). A fiscal device stamps the exact time it issued the
   * receipt; a software-generated invoice prints only a date. Date separators are "-"/"." (never
   * "::"), so this never matches a date or an amount.
   */
  private static final Pattern FISCAL_CLOCK = Pattern.compile("\\b\\d{1,2}:\\d{2}:\\d{2}\\b");

  /**
   * True only when the OCR text is CONFIDENTLY an invoice printed in fiscal-receipt (касов/фискален
   * бон) style. Verified page-by-page against the real fixture, every fiscal receipt — and none of
   * the normal invoices — carries BOTH of these independent signals, so both are required:
   *
   * <ul>
   *   <li>a legally-mandated fiscal-receipt phrase ("фискален бон" / "касова бележка" / "касов
   *       бон"), and
   *   <li>a fiscal-device wall-clock timestamp (HH:MM:SS), which real fiscal receipts print and
   *       software invoices do not.
   * </ul>
   *
   * <p>Requiring two signals keeps it conservative: the phrase alone (e.g. quoted in a normal
   * invoice's legal terms) or a bare time never classifies, so an uncertain document keeps normal
   * invoice behavior. Neither "paid in cash" nor a missing IBAN is treated as evidence — a normal
   * invoice can be paid in cash (and a fiscal receipt paid by card), and the absence of an IBAN
   * says nothing about receipt style.
   */
  static boolean isFiscalReceiptStyle(String rawText) {
    if (rawText == null) {
      return false;
    }
    String t = rawText.toLowerCase(Locale.ROOT);
    boolean fiscalPhrase = false;
    for (String marker : FISCAL_RECEIPT_MARKERS) {
      if (t.contains(marker)) {
        fiscalPhrase = true;
        break;
      }
    }
    return fiscalPhrase && FISCAL_CLOCK.matcher(rawText).find();
  }

  /**
   * A fiscal / receipt-style invoice is a POS payment settled by card (or cash) with no bank
   * account. Default its payment option to {@code Плащане с кредитна карта} (the unique outgoing
   * bank-card payment mode) so its absent bank details never forces "To review" or blocks Validate.
   * Skipped when the OCR text unambiguously names a different (non-card) method, or a bank-card
   * mode is already selected.
   */
  protected void applyFiscalReceiptPaymentMode(
      Invoice invoice, OcrExtractResult result, List<String> reviewNotes) {
    if (result == null || !isFiscalReceiptStyle(result.getRawText())) {
      return;
    }
    Set<Integer> detected =
        BgOcrPaymentMethod.detectPaymentTypes(
            firstNonBlank(result.getPaymentTerm(), result.getRawText()));
    if (detected.size() == 1 && !detected.contains(PaymentModeRepository.TYPE_BANK_CARD)) {
      return; // the receipt clearly states a different method (e.g. cash) — respect it
    }
    PaymentMode current = invoice.getPaymentMode();
    if (current != null
        && current.getTypeSelect() != null
        && current.getTypeSelect() == PaymentModeRepository.TYPE_BANK_CARD) {
      return; // already a bank-card payment mode
    }
    PaymentMode card = uniqueOutgoingPaymentMode(PaymentModeRepository.TYPE_BANK_CARD);
    if (card != null) {
      invoice.setPaymentMode(card);
    } else {
      reviewNotes.add(
          "Receipt-style invoice: no unique outgoing 'Плащане с кредитна карта' payment mode is"
              + " configured — set the payment option manually.");
    }
  }

  /** The single active OUT {@link PaymentMode} of this type, or {@code null} when none/several. */
  protected PaymentMode uniqueOutgoingPaymentMode(int typeSelect) {
    List<PaymentMode> matches =
        Beans.get(PaymentModeRepository.class)
            .all()
            .filter("self.typeSelect = :t AND self.inOutSelect = :out")
            .bind("t", typeSelect)
            .bind("out", PaymentModeRepository.OUT)
            .fetch();
    return matches.size() == 1 ? matches.get(0) : null;
  }

  /**
   * Select supplier bank details only when the normalized OCR IBAN has one active exact match owned
   * by the resolved supplier. A configured default remains in place when the OCR IBAN is unknown.
   */
  /**
   * Resolve/create the supplier invoicing address. The standard Supplier Invoice address dropdown
   * is filtered to the supplier's own {@code partnerAddressList} entries flagged {@code
   * isInvoicingAddr} (see action-invoice-attrs-domain-address); so a match/creation must be
   * supplier-owned. The OCR text is always kept in {@code addressStr} as a fallback.
   */
  protected void applyOcrAddress(
      Invoice invoice, Partner partner, OcrExtractResult result, List<String> reviewNotes) {
    // Do NOT pre-fill invoice.addressStr with the raw OCR text: the "second" address field must be
    // filled by the suite AFTER a structured address is chosen/created (see setInvoiceAddress).
    // Leaving it empty when we cannot resolve a city means the user picks/creates the address in
    // the
    // dropdown and the app fills the text — instead of a filled-but-unstructured address.
    boolean receiptStyle = result != null && isFiscalReceiptStyle(result.getRawText());
    String raw;
    String azCity;
    String azStreet;
    String azNumber;
    String azZip;
    if (receiptStyle) {
      // On a fiscal receipt Azure frequently mislabels the CUSTOMER block as the vendor (VendorName
      // is often the fiscal-device brand and VendorAddressRecipient the buyer, e.g. VendorName
      // "ELTRADE", VendorAddress the customer's address). Take the supplier address from THIS
      // segment's own text — the first explicit-marker city line, printed at the top of the receipt
      // above the customer — and ignore Azure's (customer) structured hints. Read only this child's
      // slice, so another invoice/page's address can never be borrowed. Fall back to Azure's
      // VendorAddress only when the segment carries no explicit supplier line.
      raw =
          firstNonBlank(
              BgOcrAddressParser.firstSupplierAddressLine(result.getRawText()),
              result.getSupplierAddress());
      azCity = azStreet = azNumber = azZip = null;
    } else {
      raw = result != null ? result.getSupplierAddress() : null;
      azCity = result != null ? result.getSupplierAddrCity() : null;
      azStreet = result != null ? result.getSupplierAddrStreet() : null;
      azNumber = result != null ? result.getSupplierAddrNumber() : null;
      azZip = result != null ? result.getSupplierAddrZip() : null;
    }
    if (partner == null || raw == null || raw.trim().isEmpty() || invoice.getAddress() != null) {
      return; // no supplier/text, or an address is already set — never overwrite.
    }

    // Prefer Azure's structured VendorAddress (road / houseNumber / city / postcode); fall back to
    // the BG "city on the first line, street on the next" heuristic + marker parsing.
    BgOcrAddressParser.Parsed parsed =
        BgOcrAddressParser.buildSupplier(raw, azCity, azStreet, azNumber, azZip);
    // City fallback: BG invoices often keep the city in a SEPARATE "Град: <City>" field rather than
    // inside the vendor address line (e.g. VendorAddress = "ж.к. Гевгелийски 7, вх. А" with the
    // city
    // only under "Град\nСофия"). When the address block yields no city, borrow the single
    // unambiguous labeled city from the full OCR text — a real value from the document, never a
    // guess
    // (skipped when several distinct cities appear, e.g. differing vendor/customer cities).
    if (parsed.cityName == null || parsed.cityName.trim().isEmpty()) {
      String labelledCity = BgOcrAddressParser.singleLabeledCity(result.getRawText());
      if (labelledCity != null && !labelledCity.trim().isEmpty()) {
        parsed.cityName = labelledCity.trim();
      }
    }

    // Business rule: NEVER invent placeholder values. If the OCR did not give us a usable, complete
    // address, leave the structured address EMPTY (only the raw text stays in addressStr) and
    // record
    // exactly which parts are missing — so the user fills them in and the standard Validate step
    // can
    // block on the empty field, instead of being misled by a plausible-looking default.
    if (!parsed.usable()) {
      reviewNotes.add(
          "Supplier address could not be structured (no recognizable city); left empty — fill it"
              + " in before validating.");
      return;
    }

    // Country by EXACT ISO only (BG for the default flow). Never guess another country.
    Country country = resolveCountryByIso(parsed.countryIso);
    if (country == null) {
      reviewNotes.add(
          "Country '"
              + parsed.countryIso
              + "' is not configured; supplier address left empty — fill it in.");
      return;
    }

    // SELECT an existing City from the imported BG dataset (matched on the normalized BG/EN name,
    // narrowed by any OCR region). Never create a city here — an unknown or ambiguous name yields
    // null and the address is left empty (invoice → To review).
    City city =
        resolveExistingBgCity(
            parsed.cityName,
            regionFromRawText(result.getRawText()),
            municipalityFromRawText(result.getRawText()));
    if (city == null) {
      reviewNotes.add(
          "Supplier city not found (or ambiguous) in the settlements dataset; address left empty —"
              + " fill it in before validating.");
      return;
    }

    // Effective postcode: the OCR-extracted П.К. wins; otherwise take it from the selected City
    // (the dataset postcode). No fixed fallback — a City with no zip leaves the address incomplete.
    String zip = firstNonBlank(parsed.zip, city.getZip());
    String street = parsed.street;

    // Do not create an incomplete address with guessed values: if street or postcode is missing,
    // leave the structured address empty and tell the user precisely what to fill.
    if (street == null || street.trim().isEmpty() || zip == null || zip.trim().isEmpty()) {
      reviewNotes.add(
          "Supplier address is incomplete ("
              + (street == null || street.trim().isEmpty() ? "no street" : "no postcode")
              + "); left empty — fill it in before validating.");
      return;
    }

    // Reuse an existing supplier invoicing address that matches the FINAL values (city + effective
    // zip + street + number) so the invoice points at the Partner's own address when one matches.
    // This only READS the Partner — OCR never adds or edits a Partner address. Multiple hits are
    // duplicates → reuse the first.
    for (PartnerAddress pa :
        Beans.get(PartnerAddressRepository.class)
            .all()
            .filter("self.partner = :p AND self.isInvoicingAddr = true")
            .bind("p", partner)
            .fetch()) {
      Address ad = pa.getAddress();
      if (ad != null && addressMatches(ad, city, zip, street, parsed.number)) {
        setInvoiceAddress(invoice, ad, raw);
        return;
      }
    }

    // No matching Partner address: build a structured address for THIS invoice only. It is saved as
    // a standalone Address and set on the invoice — it is NOT linked to the Partner, so OCR never
    // creates or modifies Partner master data (the accountant maintains the supplier's addresses).
    Address address = new Address();
    address.setCountry(country);
    address.setCity(city);
    address.setZip(zip);
    address.setStreetName(street);
    if (parsed.number != null) {
      address.setBuildingNumber(parsed.number);
    }
    // Populate the template line fields so the rendered fullName/formattedFullName are non-empty.
    address.setAddressL4(street);
    String l6 = (zip + " " + (parsed.cityName != null ? parsed.cityName : "")).trim();
    if (!l6.isEmpty()) {
      address.setAddressL6(l6);
    }
    String fullName = Beans.get(AddressService.class).computeFullName(address);
    address.setFullName(fullName == null || fullName.trim().isEmpty() ? raw.trim() : fullName);
    Beans.get(AddressRepository.class).save(address);

    setInvoiceAddress(invoice, address, raw);
  }

  /**
   * Set {@code Invoice.address} and regenerate {@code addressStr} via the SAME standard service the
   * UI uses on address change ({@code AddressService.computeAddressStr}); fall back to the address
   * full name, then the raw OCR text, so the field is never left blank.
   */
  private void setInvoiceAddress(Invoice invoice, Address address, String rawFallback) {
    invoice.setAddress(address);
    String s = Beans.get(AddressService.class).computeAddressStr(address);
    if (s == null || s.trim().isEmpty()) {
      s = address.getFullName();
    }
    if ((s == null || s.trim().isEmpty()) && rawFallback != null) {
      s = rawFallback.trim();
    }
    invoice.setAddressStr(s);
  }

  /**
   * True when an existing address equals the target (city + effective zip + street + number),
   * compared normalized (trim/collapse-spaces/case-insensitive). Used to reuse a supplier's
   * existing invoicing address instead of creating an identical duplicate on every invoice.
   */
  protected boolean addressMatches(
      Address ad, City city, String zip, String street, String number) {
    String adCity = ad.getCity() != null ? ad.getCity().getName() : null;
    String cityName = city != null ? city.getName() : null;
    return BgOcrAddressParser.norm(adCity).equals(BgOcrAddressParser.norm(cityName))
        && BgOcrAddressParser.norm(ad.getZip()).equals(BgOcrAddressParser.norm(zip))
        && BgOcrAddressParser.norm(ad.getStreetName()).equals(BgOcrAddressParser.norm(street))
        && BgOcrAddressParser.norm(ad.getBuildingNumber()).equals(BgOcrAddressParser.norm(number));
  }

  /** Resolve a country by EXACT ISO-2 code only (never the first record, never by name guess). */
  public Country resolveCountryByIso(String iso) {
    if (iso == null || iso.trim().isEmpty()) {
      return null;
    }
    return Beans.get(CountryRepository.class)
        .all()
        .filter("upper(self.alpha2Code) = :iso")
        .bind("iso", iso.trim().toUpperCase())
        .fetchOne();
  }

  /**
   * SELECT an existing City for a Bulgarian settlement name — never create one. Matches the OCR
   * city against the imported dataset on the normalized BG name OR English name, then narrows
   * duplicates by the OCR region (област) and municipality (община). Returns the single unambiguous
   * City, or null when the name is unknown or several settlements remain (caller then leaves the
   * address empty → invoice "To review"). EKATTE is stored on City but not expected on invoices.
   */
  public City resolveExistingBgCity(String name, String region, String municipality) {
    if (name == null || name.trim().isEmpty()) {
      return null;
    }
    // Normalize the OCR input in memory (case/whitespace/"гр."/punctuation + Latin→Cyrillic
    // homoglyph folding) and compare it to the canonical City.name / City.nameEn. The dataset names
    // are clean, so a lower-cased DB comparison matches the normalized OCR key.
    String cyr = BgPlaceNormalizer.normalizeCyrillic(name);
    String lat = BgPlaceNormalizer.normalizeLatin(name);
    List<City> candidates =
        Beans.get(CityRepository.class)
            .all()
            .filter("lower(self.name) = :cyr OR lower(self.nameEn) = :lat")
            .bind("cyr", cyr)
            .bind("lat", lat)
            .fetch();
    return BgPlaceMatcher.resolveCity(candidates, region, municipality);
  }

  /**
   * First "Област &lt;name&gt;" in the OCR text (used to disambiguate duplicate settlement names).
   */
  private static String regionFromRawText(String rawText) {
    return firstLabelledValue(rawText, "Област");
  }

  /**
   * First "Община &lt;name&gt;" in the OCR text (used to disambiguate duplicate settlement names).
   */
  private static String municipalityFromRawText(String rawText) {
    return firstLabelledValue(rawText, "Община");
  }

  private static String firstLabelledValue(String rawText, String label) {
    if (rawText == null) {
      return null;
    }
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile(
                label + "\\s*:?\\s*([А-ЯЁ][А-Яа-яЁё .-]{1,40})",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE)
            .matcher(rawText);
    return m.find() ? m.group(1).trim() : null;
  }

  /**
   * Reuse the single matching City (name+zip+country), else create one via the standard service.
   */
  protected City resolveOrCreateCity(
      String name, String zip, Country country, List<String> reviewNotes) {
    if (name == null || name.trim().isEmpty()) {
      return null;
    }
    StringBuilder filter = new StringBuilder("lower(self.name) = :n");
    if (zip != null) {
      filter.append(" AND self.zip = :z");
    }
    if (country != null) {
      filter.append(" AND self.country = :c");
    }
    com.axelor.db.Query<City> query =
        Beans.get(CityRepository.class).all().filter(filter.toString());
    query = query.bind("n", name.trim().toLowerCase());
    if (zip != null) {
      query = query.bind("z", zip);
    }
    if (country != null) {
      query = query.bind("c", country);
    }
    List<City> cities = query.fetch();
    if (cities.size() == 1) {
      return cities.get(0);
    }
    if (cities.size() > 1) {
      reviewNotes.add(
          "Several cities match '" + name + "'; the new address was left without a city.");
      return null;
    }
    return Beans.get(CityService.class).createAndSaveCity(name.trim(), zip, country);
  }

  protected void applyOcrBankDetails(
      Invoice invoice, OcrExtractResult result, List<String> reviewNotes) {
    Partner supplier = invoice.getPartner();
    String iban = BgOcrBankDetails.normalizeIban(result != null ? result.getIban() : null);
    // Text recovery: when Azure produced no structured PaymentDetails IBAN, scan the OCR text for a
    // labelled ("IBAN:" / "Банкова сметка") or strict BG IBAN (tolerating spaces, ignoring bold).
    if (!BgOcrBankDetails.isValidIban(iban) && result != null) {
      String recovered = BgOcrBankDetails.findIbanInText(result.getRawText());
      if (recovered != null) {
        iban = recovered;
        result.setIban(recovered);
      }
    }
    if (supplier == null || !BgOcrBankDetails.isValidIban(iban)) {
      return;
    }
    BankDetailsRepository bankDetailsRepo = Beans.get(BankDetailsRepository.class);
    List<BankDetails> supplierMatches =
        bankDetailsRepo
            .all()
            .filter(
                "REPLACE(UPPER(self.iban), ' ', '') = :iban "
                    + "AND self.partner = :partner AND self.active = TRUE")
            .bind("iban", iban)
            .bind("partner", supplier)
            .fetch();
    boolean ownedByOther =
        supplierMatches.isEmpty()
            && bankDetailsRepo
                    .all()
                    .filter(
                        "REPLACE(UPPER(self.iban), ' ', '') = :iban "
                            + "AND (self.partner != :partner OR self.partner IS NULL)")
                    .bind("iban", iban)
                    .bind("partner", supplier)
                    .count()
                > 0;
    Bank bank = supplierMatches.isEmpty() && !ownedByOther ? resolveBank(result.getSwift()) : null;

    switch (BgOcrBankDetails.decide(
        true, iban, supplierMatches.size(), ownedByOther, bank != null)) {
      case SELECT:
        BankDetails selected = supplierMatches.get(0);
        invoice.setBankDetails(selected);
        warnSwiftMismatch(selected, result, reviewNotes);
        break;
      case WARN_MULTIPLE:
        reviewNotes.add(
            "OCR IBAN ending "
                + ibanTail(iban)
                + " matches several active bank-detail records for this supplier; none selected —"
                + " verify.");
        break;
      case WARN_OTHER_OWNER:
        reviewNotes.add(
            "OCR IBAN ending "
                + ibanTail(iban)
                + " belongs to another partner/company; not reused — verify the supplier bank"
                + " details.");
        break;
      case CREATE:
        // OCR must NOT create supplier bank accounts (Partner master data). Surface the new IBAN so
        // the accountant can add it manually; leave the invoice Bank Details empty (→ To review).
        reviewNotes.add(
            "OCR found a new supplier IBAN ending "
                + ibanTail(iban)
                + " that is not on the supplier; not added automatically — add the bank account to"
                + " the partner manually if it is correct.");
        break;
      case WARN_NO_BANK:
        reviewNotes.add(
            "OCR IBAN ending "
                + ibanTail(iban)
                + " is new but no BIC/SWIFT was available to identify the bank; add the bank"
                + " details manually.");
        break;
      default:
        break;
    }

    // Multiple-IBAN fallback (normal invoices only). A normal invoice can list several supplier
    // accounts (e.g. two banks). When the primary IBAN above could not be turned into bank details
    // (no owned match and no resolvable bank) and there is no stronger evidence to choose between
    // them, take the LAST valid IBAN in THIS segment and record supplier-owned bank details for it,
    // rather than leaving Bank Details empty. Never runs for receipt-style invoices (they need no
    // bank account) and never fabricates an IBAN when the segment contains none — so a genuinely
    // IBAN-less invoice still goes To review.
    if (invoice.getBankDetails() == null
        && result != null
        && !isFiscalReceiptStyle(result.getRawText())) {
      List<String> allIbans = BgOcrBankDetails.findAllBgIbans(result.getRawText());
      if (allIbans.size() >= 2) {
        applyLastIbanFallback(
            invoice, supplier, allIbans.get(allIbans.size() - 1), result, reviewNotes);
      }
    }
  }

  /**
   * From a chosen fallback IBAN, reuse an existing active supplier-owned bank-detail record on the
   * invoice when one exists (read-only). A new IBAN is NOT recorded on the Partner — OCR never
   * creates supplier bank accounts — it is only surfaced in a review note for the accountant.
   */
  protected void applyLastIbanFallback(
      Invoice invoice,
      Partner supplier,
      String iban,
      OcrExtractResult result,
      List<String> reviewNotes) {
    if (supplier == null || !BgOcrBankDetails.isValidIban(iban)) {
      return;
    }
    BankDetailsRepository bankDetailsRepo = Beans.get(BankDetailsRepository.class);
    BankDetails existing =
        bankDetailsRepo
            .all()
            .filter(
                "REPLACE(UPPER(self.iban), ' ', '') = :iban "
                    + "AND self.partner = :partner AND self.active = TRUE")
            .bind("iban", iban)
            .bind("partner", supplier)
            .fetchOne();
    if (existing != null) {
      invoice.setBankDetails(existing);
      reviewNotes.add(
          "Several supplier IBANs were found; used the last one ending "
              + ibanTail(iban)
              + " — verify it is the correct account.");
      return;
    }
    reviewNotes.add(
        "Several supplier IBANs were found; the last one ending "
            + ibanTail(iban)
            + " is not on the supplier — add the bank account to the partner manually if correct.");
  }

  /** Find a Bank by its BIC/SWIFT code, creating a minimal one when absent. Null if no BIC. */
  public Bank resolveBank(String swift) {
    String code = swift == null ? null : swift.replaceAll("\\s+", "").toUpperCase();
    if (code == null || code.isEmpty()) {
      return null;
    }
    BankRepository bankRepo = Beans.get(BankRepository.class);
    Bank bank = bankRepo.all().filter("upper(self.code) = :c").bind("c", code).fetchOne();
    if (bank != null) {
      return bank;
    }
    Bank created = new Bank();
    created.setCode(code);
    created.setBankName(code);
    created.setBankDetailsTypeSelect(BankRepository.BANK_IDENTIFIER_TYPE_IBAN);
    return bankRepo.save(created);
  }

  private static void warnSwiftMismatch(
      BankDetails bankDetails, OcrExtractResult result, List<String> reviewNotes) {
    String swift = result.getSwift();
    if (swift != null
        && bankDetails.getBank() != null
        && bankDetails.getBank().getCode() != null
        && !bankDetails.getBank().getCode().toUpperCase().contains(swift.toUpperCase())) {
      reviewNotes.add("OCR SWIFT '" + swift + "' differs from the selected bank details.");
    }
  }

  private static String ibanTail(String iban) {
    return iban.substring(Math.max(0, iban.length() - 4));
  }

  /**
   * Resolve a line's Product then its purchase account, deterministically: (1) a Product whose code
   * equals the OCR supplier product code; else (2) the invoice company's own {@code
   * aiOcrGenericPurchaseProduct} (a per-company field — each Company configures its own; no global
   * default, no first-record fallback). If neither resolves, the line stays description-only and
   * the readiness checker reports the missing account. The purchase account is then resolved by the
   * standard {@code AccountManagementAccountService} for THIS company (so the product must be valid
   * for the company, else the account stays null and readiness flags it).
   */
  protected void resolveLineProductAndAccount(
      InvoiceLine line, OcrExtractLine src, Invoice invoice) {
    Company company = invoice.getCompany();
    if (company == null) {
      return;
    }
    Product product = resolveProduct(src, company);
    if (product == null) {
      return; // description-only
    }
    line.setProduct(product);
    // Keep the real OCR product/service description as the line name; only fall back to the
    // resolved product's name when OCR gave us nothing. Overwriting the name with the (per-company)
    // generic OCR purchase product would make every line read "OCR Generic Purchase" even when
    // Azure returned a real description (e.g. "Почистване лек автомобил", "Секретен ключ", the
    // fuel/product name). The product link + purchase account are still set
    // for accounting; only the displayed name is preserved.
    if (isBlankStr(line.getProductName()) && product.getName() != null) {
      line.setProductName(product.getName());
    }
    if (product.getUnit() != null) {
      line.setUnit(product.getUnit());
    }
    try {
      Account account =
          Beans.get(AccountManagementAccountService.class)
              .getProductAccount(product, company, invoice.getFiscalPosition(), true, false);
      if (account != null) {
        line.setAccount(account);
      }
    } catch (Exception e) {
      // account not resolvable from the product's AccountManagement — readiness flags it
    }
  }

  /**
   * Deterministic product resolution, in three tiers:
   *
   * <ol>
   *   <li>a Product whose code equals the OCR supplier product code;
   *   <li>a Product whose <b>name</b> equals the OCR line description, case-insensitively, and only
   *       when exactly one matches;
   *   <li>the invoice company's configured generic OCR purchase product ({@code
   *       Company.aiOcrGenericPurchaseProduct}).
   * </ol>
   *
   * <p>Tier 2 exists because most Bulgarian supplier invoices print no product code at all, so tier
   * 1 could never fire and every line fell to the generic product — or, where no generic product is
   * configured, to no product and therefore no expense account. It is an exact match, never fuzzy,
   * and it refuses to choose when several products share a name: no guessing, and no first-record
   * fallback (see the project rule). Company scoped throughout.
   */
  protected Product resolveProduct(OcrExtractLine src, Company company) {
    return resolveLineProduct(src.getProductCode(), src.getDescription(), company);
  }

  /**
   * The same three tiers, addressable without an {@link OcrExtractLine}.
   *
   * <p>Public because the intake pipeline is no longer the only writer of invoice lines: a
   * correction applied from the chat creates and replaces lines too, and a line written there must
   * end up with the same product and therefore the same expense account as one written here.
   * Duplicating the tiers in the line editor would let the two drift, and the drift would show up
   * as an invoice that cannot be ventilated only when it came from the assistant.
   *
   * @param supplierProductCode the code printed on the document, or null when there is none
   * @param description the line description, matched against {@code Product.name} in tier 2
   * @param company the invoice company, whose {@code aiOcrGenericPurchaseProduct} is tier 3
   */
  public Product resolveLineProduct(
      String supplierProductCode, String description, Company company) {
    if (supplierProductCode != null && !supplierProductCode.trim().isEmpty()) {
      Product byCode =
          Beans.get(ProductRepository.class)
              .all()
              .filter("self.code = :c")
              .bind("c", supplierProductCode.trim())
              .fetchOne();
      if (byCode != null) {
        return byCode;
      }
    }
    Product byName = resolveProductByName(description);
    if (byName != null) {
      return byName;
    }
    return company != null ? company.getAiOcrGenericPurchaseProduct() : null;
  }

  /**
   * Exact, case-insensitive product-name match. Returns null unless exactly one product matches —
   * an ambiguous name is left for the accountant rather than resolved arbitrarily.
   */
  protected Product resolveProductByName(String description) {
    if (description == null || description.trim().isEmpty()) {
      return null;
    }
    List<Product> matches =
        Beans.get(ProductRepository.class)
            .all()
            .filter("LOWER(self.name) = :n AND (self.archived IS NULL OR self.archived = FALSE)")
            .bind("n", description.trim().toLowerCase())
            .fetch(2);
    return matches.size() == 1 ? matches.get(0) : null;
  }

  /**
   * Compute the readiness report and route OCR warnings/discrepancies into aiValidationNotes.
   *
   * <p>Returns the report it computed so a caller that changes the invoice afterwards can re-run it
   * and replace the note: a repair pass that fixes three line accounts must not leave "3 of 3
   * line(s) have no account" sitting on the record.
   */
  protected OcrInvoiceReadiness setReadinessNotes(
      Invoice invoice, OcrExtractResult result, List<String> extraNotes) {
    OcrInvoiceReadiness readiness =
        Beans.get(OcrSupplierInvoiceReadinessService.class)
            .check(invoice, readinessNotes(result, extraNotes));
    invoice.setAiValidationNotes(readiness.toNotes());
    return readiness;
  }

  /** The OCR-side notes fed into the readiness report; shared so a re-run says the same things. */
  protected List<String> readinessNotes(OcrExtractResult result, List<String> extraNotes) {
    List<String> ocrNotes = new ArrayList<>();
    if (result.getNapDocumentType() != null) {
      ocrNotes.add("Suggested NAP document type: " + result.getNapDocumentType());
    }
    ocrNotes.addAll(result.getWarnings());
    ocrNotes.addAll(result.getDiscrepancies());
    if (extraNotes != null) {
      ocrNotes.addAll(extraNotes);
    }
    return ocrNotes;
  }

  /**
   * Append a note without losing what is already there. buildDraft's {@link #setReadinessNotes} has
   * already written the readiness summary by this point, so the AI audit trail is added to it
   * rather than replacing it.
   */
  protected static String appendNote(String existing, String addition) {
    if (addition == null || addition.trim().isEmpty()) {
      return existing;
    }
    if (existing == null || existing.trim().isEmpty()) {
      return addition;
    }
    return existing + "\n\n" + addition;
  }

  /** Find the existing invoice a duplicate matches, to link it on the upload. */
  protected Invoice findExisting(Invoice draft) {
    return invoiceRepo
        .all()
        .filter(
            "self.supplierInvoiceNb = :nb AND self.partner = :partner "
                + "AND self.operationTypeSelect = :op AND self.statusSelect != :canceled")
        .bind("nb", draft.getSupplierInvoiceNb())
        .bind("partner", draft.getPartner())
        .bind("op", InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE)
        .bind("canceled", InvoiceRepository.STATUS_CANCELED)
        .fetchOne();
  }

  protected String snapshot(OcrExtractResult r) {
    // Phase-1 manual audit snapshot; Phase 2 stores the real /extract HTTP body.
    return "engine="
        + r.getEngineUsed()
        + "; confidence="
        + r.getConfidence()
        + "; supplier="
        + r.getSupplierName()
        + " (EIK "
        + r.getSupplierEik()
        + ")"
        + "; invoiceNo="
        + r.getInvoiceNumber()
        + "; date="
        + r.getInvoiceDate()
        + "; base/vat/gross="
        + r.getTotalBase()
        + "/"
        + r.getTotalVat()
        + "/"
        + r.getTotalGross()
        + "; lines="
        + r.getLines().size()
        + "; blobUrl="
        + r.getBlobUrl()
        + (r.getErrors().isEmpty() ? "" : "; errors=" + String.join("|", r.getErrors()))
        + lineDetail(r);
  }

  /**
   * Extracted item rows preserved as descriptive detail in the large audit field (never as lines).
   */
  private static String lineDetail(OcrExtractResult r) {
    if (r.getLines() == null || r.getLines().isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder("\nOCR lines:");
    for (OcrExtractLine line : r.getLines()) {
      sb.append("\n- ").append(firstNonBlank(line.getDescription(), "(no description)"));
      if (line.getNetAmount() != null) {
        sb.append(" | net ").append(line.getNetAmount().toPlainString());
      }
    }
    return sb.toString();
  }

  /** True when the current user belongs to an internal group (or is admin). */
  protected boolean isInternalUser() {
    User user = AuthUtils.getUser();
    if (user == null) {
      return false;
    }
    if (user.getGroup() != null && INTERNAL_GROUP_CODES.contains(user.getGroup().getCode())) {
      return true;
    }
    if (user.getRoles() != null) {
      for (com.axelor.auth.db.Role role : user.getRoles()) {
        if ("AiOcrInternal".equals(role.getName()) || "Admin".equals(role.getName())) {
          return true;
        }
      }
    }
    return false;
  }

  // ---- L1 byte-identical guard (company + SHA-256)
  // -----------------------------------------------

  /**
   * Block a manual re-upload of byte-identical content BEFORE OCR. Returns {@code true} (and flags
   * the upload DUPLICATE) when the same company already has an upload with this content hash. No-op
   * for archive/folder children (they arrive with a {@code fileHash} already checked at ingest) and
   * when the physical file is unavailable.
   */
  protected boolean blockIfByteIdenticalDuplicate(AiOcrUpload upload) {
    MetaFile file = upload.getOriginalFile();
    if (upload.getFileHash() != null || file == null) {
      return false;
    }
    String hash;
    try {
      Path path = MetaFiles.getPath(file);
      if (path == null || !Files.exists(path)) {
        return false; // nothing to hash — proceed to OCR as before
      }
      hash = AiOcrFileHash.sha256(path);
    } catch (IOException e) {
      LOG.warn(
          "AI-OCR L1: could not hash upload #{} — skipping byte-identical check",
          upload.getId(),
          e);
      return false;
    }
    upload.setFileHash(hash);
    Long companyId = upload.getCompany() != null ? upload.getCompany().getId() : null;
    AiOcrUpload prior = findByteIdenticalDuplicate(upload, hash, companyId);
    LOG.info(
        "AI-OCR L1 byte-identical check: upload #{} company={} hash={} status={} -> {}",
        upload.getId(),
        companyId,
        hash,
        upload.getStatusSelect(),
        prior == null ? "unique" : "duplicate of #" + prior.getId());
    if (prior == null) {
      uploadRepo.save(upload); // persist the hash so a later identical upload can match this one
      return false;
    }
    // Carry BOTH generated-document links, not just the invoice: the duplicate exists to point at
    // the document that was already imported, and for a non-invoice document that is the Move. Safe
    // for deletion by construction — OcrUploadFileCleanupService.producedMoves and producedInvoices
    // both skip DUPLICATE rows precisely because "a duplicate only links a document it did not
    // create", and detachDocumentRefs nulls the FKs for the whole tree regardless of status.
    upload.setInvoice(prior.getInvoice());
    upload.setMove(prior.getMove());
    upload.setStatusSelect(AiOcrUploadStatus.DUPLICATE);
    upload.setExtractionErrors(
        "Duplicate of an already-imported file (same company + content hash) — upload #"
            + prior.getId()
            + ".");
    upload.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(upload);
    return true;
  }

  /**
   * A same-company upload carrying the same content hash and still representing a live document.
   * Excludes this row and rows with no live artifact (cancelled / duplicate / deleted-from-*), so a
   * reprocess or a legitimate re-import after deletion is never blocked at L1.
   */
  protected AiOcrUpload findByteIdenticalDuplicate(
      AiOcrUpload upload, String hash, Long companyId) {
    if (hash == null) {
      return null;
    }
    // Every candidate, not fetchOne: one content hash can legitimately belong to SEVERAL live rows
    // now that a whole-document split child shares its carrier's file (and therefore its hash), and
    // an unordered fetchOne over that set would pick either row from one run to the next. The
    // filter is unchanged — same company scoping, same excluded statuses, so what counts as a
    // duplicate is exactly what it was. Ordered by id only to give the ranking a stable input; the
    // CHOICE is made by pickDuplicateSource, never by the ordering. The set is small by
    // construction (rows holding byte-identical content in one company, duplicates already
    // excluded) and the (file_hash, company) index serves it directly.
    List<AiOcrUpload> candidates =
        uploadRepo
            .all()
            .filter(
                "self.fileHash = :hash AND self.id != :self AND self.statusSelect NOT IN :ignored "
                    + "AND ((:companyId IS NULL AND self.company IS NULL) OR self.company.id = :companyId)")
            .bind("hash", hash)
            .bind("self", upload.getId() == null ? -1L : upload.getId())
            .bind(
                "ignored",
                java.util.Arrays.asList(
                    AiOcrUploadStatus.CANCELLED,
                    AiOcrUploadStatus.DUPLICATE,
                    AiOcrUploadStatus.DELETED_FROM_SUPPL_INVOICES,
                    AiOcrUploadStatus.DELETED_FROM_MOVES))
            .bind("companyId", companyId)
            .order("id")
            .fetch();
    return pickDuplicateSource(candidates);
  }

  /**
   * Which of several byte-identical uploads a new duplicate should point at.
   *
   * <h3>Why there can be several</h3>
   *
   * A PDF extracted from an archive becomes a {@code pdfSplit} carrier, and when its single invoice
   * covers the whole document the invoice child SHARES the carrier's MetaFile — and with it the
   * content hash, because the bytes really are the same bytes. Both rows are live and neither is an
   * excluded status, so a later direct upload of that same PDF matches two rows. They are not
   * interchangeable: the carrier is a structural container with no accounting document, the leaf is
   * the document the user actually cares about.
   *
   * <h3>The order, and why it is meaning and not identity</h3>
   *
   * <ol>
   *   <li>a real document that produced an Invoice or a Move — linking it is what lets the
   *       duplicate preserve that document;
   *   <li>a real document that has not produced one yet ({@code needsReview}, {@code failed}, a
   *       queued re-scan). Still preferred over any carrier: it is a document, and it is the row a
   *       user would open;
   *   <li>a carrier that somehow carries a document — ranked above a bare carrier only so the
   *       document is never dropped;
   *   <li>a structural carrier. Last resort: chosen only when nothing else matches, which is also
   *       the pre-existing behaviour when a carrier is the sole live holder of a hash.
   * </ol>
   *
   * <p>{@code id} breaks a tie WITHIN a rank and only there — the oldest of equally meaningful
   * rows, i.e. the original import. That is a stable total order, not the selection rule: no rank
   * is ever decided by an id, so this is not a "newest row wins" guess.
   *
   * <p>A ZIP carrier never reaches here at all: it carries no {@code fileHash} (it is extracted,
   * not OCR'd, so it never passes through the hashing guard), so re-uploading a ZIP matches only
   * its own carrier row and there is nothing to disambiguate.
   *
   * <p>Pure and static: unit-testable without Guice, JPA or a query.
   */
  static AiOcrUpload pickDuplicateSource(List<AiOcrUpload> candidates) {
    AiOcrUpload best = null;
    int bestRank = Integer.MAX_VALUE;
    for (AiOcrUpload candidate : candidates == null ? List.<AiOcrUpload>of() : candidates) {
      if (candidate == null) {
        continue;
      }
      int rank = duplicateSourceRank(candidate);
      if (best == null || rank < bestRank || (rank == bestRank && isOlder(candidate, best))) {
        best = candidate;
        bestRank = rank;
      }
    }
    return best;
  }

  /** Rank for {@link #pickDuplicateSource}; lower wins. */
  static int duplicateSourceRank(AiOcrUpload upload) {
    boolean structural = upload.getCarrierKind() != null;
    boolean hasDocument = upload.getInvoice() != null || upload.getMove() != null;
    if (!structural) {
      return hasDocument ? 0 : 1;
    }
    return hasDocument ? 2 : 3;
  }

  /** Tie-break within one rank: the older row, null ids last so they never win by accident. */
  private static boolean isOlder(AiOcrUpload candidate, AiOcrUpload current) {
    Long a = candidate.getId();
    Long b = current.getId();
    if (a == null) {
      return false;
    }
    return b == null || a < b;
  }

  // ---- Semantic duplicate key (company | supplier | docType | invoiceNo)
  // -------------------------

  /** Advisory-lock namespace for OCR semantic-duplicate serialization. */
  private static final int NS_OCR_DUP = 0x0C_A1;

  private static final Pattern VAT_PATTERN = Pattern.compile("^([A-Z]{2})(\\d{6,12})$");

  private static final Pattern FAKTURA_NUMBER =
      Pattern.compile(
          "(?iuU)(?:фактур\\p{L}{0,2}|ф-?ра)[^\\p{L}\\p{N}]{0,20}(?:№|N[oº]?|#)?\\s*"
              + "([0-9][0-9A-Za-zА-Яа-я/\\-]{0,30})");

  /**
   * Supplier ЕИК/Булстат read from raw text (identity label + 9 or 13 digits). Two label families,
   * because a BG invoice prints the same company id under either:
   *
   * <ul>
   *   <li><b>Registration labels</b> — ЕИК / БУЛСТАТ / UIC, followed by the bare ЕИК.
   *   <li><b>VAT labels</b> — ДДС № / ИН по ЗДДС / VAT, followed by the VAT number, which for a BG
   *       company is {@code BG} + that same ЕИК (hence the optional {@code BG} prefix below; the
   *       captured value stays digits-only, matching {@link SupplierEik#canonical}). Many invoices
   *       print ONLY this line — no "ЕИК" anywhere — and Azure then returns no {@code VendorTaxId},
   *       so without this family the supplier identity is lost entirely.
   * </ul>
   *
   * <p>A VAT label counts only when a number marker (№ / No / # / :) or the {@code BG} prefix
   * follows it: bare "ДДС" also heads the tax lines ("ДДС 20%", "в т.ч. ДДС") and must never be
   * read as an identity. The digits are fenced by {@code (?<!\d)}/{@code (?!\d)} so a longer number
   * (a bank account, a fiscal device number) is never sliced down to a 9-digit "ЕИК", and the gaps
   * are bounded so a label can never reach across the page to an unrelated number.
   */
  private static final Pattern EIK_IN_TEXT =
      Pattern.compile(
          "(?iuU)(?:(?:ЕИК|БУЛСТАТ|BULSTAT|UIC)\\s{0,4}[/:.]?"
              + "|(?:ИН\\s{0,3}по\\s{0,3}ЗДДС|ЗДДС|ДДС|VAT)\\s{0,4}"
              + "(?:[№#]|N[o°]\\.?|:|(?=\\s{0,4}BG\\d)))"
              + "\\s{0,4}(?:BG)?\\s{0,4}(?<!\\d)(\\d{9}(?:\\d{4})?)(?!\\d)");

  /**
   * The semantic duplicate key {@code company | supplier | docType | invoiceNo}, or {@code null}
   * when the company, a validated supplier identifier, or the invoice number is missing. A {@code
   * null} key skips ONLY semantic detection (review status unchanged); the stock fallback still
   * runs. Gating is by validity/unambiguity, not OCR confidence — a wrong OCR read fails to match
   * (a missed duplicate) rather than blocking a legitimate new invoice.
   */
  protected String docKey(OcrExtractResult result, Long companyId) {
    if (companyId == null) {
      return null;
    }
    String supplierId = normalizeSupplierId(result.getSupplierEik(), result.getSupplierVat());
    if (supplierId == null) {
      return null;
    }
    String invoiceNo = normalizeInvoiceNo(result.getInvoiceNumber());
    if (invoiceNo == null) {
      return null;
    }
    return companyId + "|" + supplierId + "|" + docType(result) + "|" + invoiceNo;
  }

  /**
   * Fill {@code supplierEik} from the raw text when Azure produced no VAT/EIK (common for BG ЕООД
   * shown only with "ЕИК"). Picks the single ЕИК that is not the customer's / owning company's — so
   * the supplier identity (and thus the duplicate key and partner) is recovered without guessing.
   */
  protected void enrichSupplierEik(OcrExtractResult result, AiOcrUpload upload) {
    Set<String> excluded = new HashSet<>();
    addCanonId(excluded, result.getCustomerEik());
    addCanonId(excluded, result.getCustomerVat());
    Company company = upload == null ? null : upload.getCompany();
    if (company != null && company.getPartner() != null) {
      addCanonId(excluded, company.getPartner().getRegistrationCode());
      addCanonId(excluded, company.getPartner().getTaxNbr());
    }
    // Never mistake the BUYER's identity for the supplier. Azure (especially on a foreign invoice)
    // sometimes puts the customer's / own-company's ЕИК/ДДС in the vendor fields; drop it so the
    // supplier is never matched on the wrong id (the draft then goes Needs review instead of
    // locking
    // onto the buyer's partner).
    if (isExcludedId(result.getSupplierEik(), excluded)) {
      result.setSupplierEik(null);
    }
    if (isExcludedId(result.getSupplierVat(), excluded)) {
      result.setSupplierVat(null);
    }
    // Recover the supplier ЕИК from the text when Azure produced none (common for BG ЕООД shown
    // only
    // with "ЕИК"), or when the wrong (buyer) id was just cleared above.
    if (isBlankStr(result.getSupplierEik())) {
      String eik = supplierEikFromText(result.getRawText(), excluded);
      if (eik != null) {
        result.setSupplierEik(eik);
      }
    }
  }

  /**
   * Recover the ISSUER's ЕИК from the raw text for the manual Customer Invoice reroute, when Azure
   * produced no {@code VendorTaxId} at all (the normal case for a BG invoice that prints only "ЕИК
   * …" / "ДДС № BG…").
   *
   * <p>Deliberately NOT {@link #enrichSupplierEik}: that one excludes the active company's own ЕИК,
   * because on an incoming document the supplier is by definition someone else. On an outgoing one
   * the issuer IS this company, so excluding it would delete the single number the reroute's
   * identity check looks for — which is why "vendor EIK/VAT read: none" was reported on a document
   * that plainly carries it. Here only the BUYER's ids are excluded.
   *
   * <p>Same regex family and same exactly-one-candidate rule as the supplier path ({@link
   * #supplierEikFromText}): zero or several candidates leave the fields untouched, so the caller
   * still refuses fail-closed. Never consults names or the Partner table. Runs only when Azure read
   * no vendor identity of any kind, so a value it did read is never overridden.
   */
  public void recoverIssuerEikForCustomerReroute(OcrExtractResult result) {
    if (result == null
        || !isBlankStr(result.getSupplierEik())
        || !isBlankStr(result.getSupplierVat())) {
      return;
    }
    Set<String> excluded = new HashSet<>();
    addCanonId(excluded, result.getCustomerEik());
    addCanonId(excluded, result.getCustomerVat());
    String eik = supplierEikFromText(result.getRawText(), excluded);
    if (eik != null) {
      result.setSupplierEik(eik);
    }
  }

  /** True when a supplier id, canonicalized, is one of the excluded (customer/own-company) ids. */
  private static boolean isExcludedId(String raw, Set<String> excluded) {
    String c = canonicalSupplierId(canonAlnum(raw));
    return c != null && excluded.contains(c);
  }

  private static void addCanonId(Set<String> set, String raw) {
    String c = canonicalSupplierId(canonAlnum(raw));
    if (c != null) {
      set.add(c);
    }
  }

  /**
   * The single supplier ЕИК in the text after removing excluded (customer/company) ids, else null.
   */
  protected static String supplierEikFromText(String rawText, Set<String> excluded) {
    if (rawText == null) {
      return null;
    }
    Set<String> found = new LinkedHashSet<>();
    Matcher m = EIK_IN_TEXT.matcher(rawText);
    while (m.find()) {
      String d = m.group(1);
      if (excluded == null || !excluded.contains(d)) {
        found.add(d);
      }
    }
    return found.size() == 1 ? found.iterator().next() : null;
  }

  /** Canonical supplier identifier: BG ЕИК/Булстат/VAT → digits; foreign VAT keeps its prefix. */
  protected static String normalizeSupplierId(String eikRaw, String vatRaw) {
    String fromEik = canonicalSupplierId(canonAlnum(eikRaw));
    String fromVat = canonicalSupplierId(canonAlnum(vatRaw));
    if (fromEik != null && fromVat != null) {
      return fromEik.equals(fromVat) ? fromEik : null; // ambiguous → no key
    }
    return fromEik != null ? fromEik : fromVat;
  }

  private static String canonAlnum(String s) {
    if (s == null) {
      return null;
    }
    String c = s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    return c.isEmpty() ? null : c;
  }

  private static String canonicalSupplierId(String c) {
    if (c == null) {
      return null;
    }
    if (c.chars().allMatch(Character::isDigit)) {
      return (c.length() == 9 || c.length() == 13) ? c : null; // BG ЕИК/Булстат
    }
    Matcher m = VAT_PATTERN.matcher(c);
    if (!m.matches()) {
      return null;
    }
    String country = m.group(1);
    String digits = m.group(2);
    if ("BG".equals(country)) {
      return (digits.length() >= 9 && digits.length() <= 13) ? digits : null; // strip BG prefix
    }
    return country + digits; // foreign VAT — preserve the country prefix
  }

  /** Normalized invoice number for comparison only (raw value is preserved for display). */
  public static String normalizeInvoiceNo(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.trim().replaceFirst("(?iuU)^\\s*(фактура|invoice|№|N[oº]?|#)\\s*[:.]?\\s*", "");
    s = s.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-ZА-Я]", "");
    return s.isEmpty() ? null : s; // leading zeros preserved
  }

  /** Coarse document type for the key; defaults to INVOICE so BG keys stay stable. */
  protected static String docType(OcrExtractResult result) {
    String hay =
        ((result.getNapDocumentType() == null ? "" : result.getNapDocumentType())
                + "\n"
                + (result.getRawText() == null ? "" : result.getRawText()))
            .toLowerCase(Locale.ROOT);
    if (hay.contains("кредитно известие") || hay.contains("credit note")) {
      return "CREDIT_NOTE";
    }
    if (hay.contains("дебитно известие") || hay.contains("debit note")) {
      return "DEBIT_NOTE";
    }
    return "INVOICE";
  }

  /**
   * Recover the invoice number from the "Фактура" line when Azure's InvoiceId is blank. Small
   * window (the Фактура line only), rejects lines carrying a date/PO/contract/customer/ЕИК/ДДС
   * reference, and returns {@code null} when zero or more than one distinct candidate is found.
   */
  protected static String invoiceNoFromText(String rawText) {
    if (rawText == null || rawText.isEmpty()) {
      return null;
    }
    Set<String> candidates = new LinkedHashSet<>();
    for (String line : rawText.split("\\R")) {
      String lower = line.toLowerCase(Locale.ROOT);
      boolean anchored =
          lower.contains("фактур") || lower.contains("ф-ра") || lower.contains("фра");
      if (!anchored || hasOtherRefMarker(lower)) {
        continue;
      }
      Matcher m = FAKTURA_NUMBER.matcher(line);
      if (m.find()) {
        String cand = normalizeInvoiceNo(m.group(1));
        if (cand != null) {
          candidates.add(cand);
        }
      }
    }
    return candidates.size() == 1 ? candidates.iterator().next() : null;
  }

  private static boolean hasOtherRefMarker(String lowerLine) {
    return lowerLine.contains("дата")
        || lowerLine.contains(" г.")
        || lowerLine.contains("договор")
        || lowerLine.contains("поръчка")
        || lowerLine.contains("клиент")
        || lowerLine.contains("еик")
        || lowerLine.contains("ддс")
        || lowerLine.matches(".*\\b20\\d\\d\\b.*")
        || lowerLine.matches(".*\\d{1,2}[./]\\d{1,2}[./]\\d{2,4}.*");
  }

  private void lockDocKey(String key) {
    JPA.em()
        .unwrap(Session.class)
        .doWork(
            conn -> {
              try (PreparedStatement ps =
                  conn.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
                ps.setInt(1, NS_OCR_DUP);
                ps.setInt(2, key.hashCode());
                ps.execute();
              }
            });
  }

  /**
   * The invoice that already exists for this semantic key, or {@code null} if none — the single
   * source of truth for "is this a duplicate?". Checks, in order:
   *
   * <ol>
   *   <li>a SIBLING upload with the same {@code docKey} (excluding ONLY this upload by id) that
   *       still links an invoice — this is what makes reprocess idempotent and prevents a duplicate
   *       even when the partner resolves differently; a sibling whose invoice was deleted links
   *       {@code null} and therefore does not block a legitimate re-import;
   *   <li>a live/canceled supplier invoice for this company + partner whose number matches.
   * </ol>
   */
  protected Invoice findExistingInvoiceForKey(
      String key, Long selfId, Long companyId, Partner partner, String normalizedNo) {
    AiOcrUpload sibling =
        uploadRepo
            .all()
            .filter("self.docKey = :key AND self.id != :self AND self.invoice IS NOT NULL")
            .bind("key", key)
            .bind("self", selfId == null ? -1L : selfId)
            // Several siblings can legitimately carry one docKey (a re-scan, a reprocess, a page
            // split out of the same ZIP), so this must not be an unordered fetchOne. They normally
            // all link the SAME invoice, which is the point of the key — the order therefore does
            // not change the answer, it only makes the answer repeatable. Oldest first: the
            // original importer of this document.
            .order("id")
            .fetchOne();
    if (sibling != null && sibling.getInvoice() != null) {
      return sibling.getInvoice();
    }
    return findSemanticDuplicateInvoice(companyId, partner, normalizedNo);
  }

  /**
   * An existing supplier invoice for this company + partner whose number matches (normalized).
   * Canceled invoices are matched too — a canceled duplicate is still reported, never silently
   * re-imported.
   */
  protected Invoice findSemanticDuplicateInvoice(
      Long companyId, Partner partner, String normalizedNo) {
    if (companyId == null || partner == null || normalizedNo == null) {
      return null;
    }
    List<Invoice> candidates =
        invoiceRepo
            .all()
            .filter(
                "self.partner = :partner AND self.company.id = :company "
                    + "AND self.operationTypeSelect = :op AND self.supplierInvoiceNb IS NOT NULL")
            .bind("partner", partner)
            .bind("company", companyId)
            .bind("op", InvoiceRepository.OPERATION_TYPE_SUPPLIER_PURCHASE)
            .fetch();
    for (Invoice inv : candidates) {
      if (normalizedNo.equals(normalizeInvoiceNo(inv.getSupplierInvoiceNb()))) {
        return inv;
      }
    }
    return null;
  }

  /**
   * Flag the upload DUPLICATE and link it to the already-existing invoice. Never creates or mutates
   * accounting data — a re-import of the linked (or canceled) invoice stays an explicit user
   * action.
   */
  protected void markSemanticDuplicate(
      AiOcrUpload upload, Invoice dupInvoice, OcrExtractResult result) {
    String supplierId = normalizeSupplierId(result.getSupplierEik(), result.getSupplierVat());
    upload.setInvoice(dupInvoice);
    boolean canceled = InvoiceRepository.STATUS_CANCELED == nz(dupInvoice.getStatusSelect());
    StringBuilder msg =
        new StringBuilder("Duplicate — invoice ")
            .append(firstNonBlank(result.getInvoiceNumber(), "?"))
            .append(", supplier ")
            .append(supplierId == null ? "?" : supplierId)
            .append(" already imported (invoice #")
            .append(dupInvoice.getId());
    if (canceled) {
      msg.append(", CANCELED");
    }
    msg.append("). ")
        .append(
            canceled
                ? "Re-import of a canceled invoice — re-import only via an explicit action."
                : "Delete/cancel that invoice to re-import this document.");
    upload.setExtractionErrors(msg.toString());
    upload.setStatusSelect(AiOcrUploadStatus.DUPLICATE);
    upload.setProcessedOn(LocalDateTime.now());
    uploadRepo.save(upload);
  }

  private static boolean isBlankStr(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static BigDecimal unitGrossPrice(OcrExtractLine line) {
    if (line.getUnitPrice() != null && line.getVatRate() != null) {
      return line.getUnitPrice()
          .multiply(BigDecimal.ONE.add(line.getVatRate().movePointLeft(2)))
          .stripTrailingZeros();
    }
    if (line.getGrossAmount() != null && line.getQty() != null && line.getQty().signum() > 0) {
      return line.getGrossAmount()
          .divide(line.getQty(), 6, java.math.RoundingMode.HALF_UP)
          .stripTrailingZeros();
    }
    return line.getUnitPrice();
  }

  private static String firstNonBlank(String first, String second) {
    return first != null && !first.trim().isEmpty() ? first.trim() : second;
  }

  private static boolean sameCurrency(Invoice invoice) {
    return invoice.getCompany() != null
        && invoice.getCompany().getCurrency() != null
        && invoice.getCurrency() != null
        && invoice.getCompany().getCurrency().getCodeISO() != null
        && invoice
            .getCompany()
            .getCurrency()
            .getCodeISO()
            .equalsIgnoreCase(invoice.getCurrency().getCodeISO());
  }

  private static int nz(Integer v) {
    return v == null ? 0 : v;
  }

  private static BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
