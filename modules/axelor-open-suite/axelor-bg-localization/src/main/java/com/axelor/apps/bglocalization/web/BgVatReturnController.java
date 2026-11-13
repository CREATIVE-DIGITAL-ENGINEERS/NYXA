package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.BgVatCoefficient;
import com.axelor.apps.bglocalization.db.BgVatPackage;
import com.axelor.apps.bglocalization.db.BgVatPackageFile;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.db.BgVatSubmission;
import com.axelor.apps.bglocalization.db.repo.BgDeclarantRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatCoefficientRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatPackageFileRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatPackageRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatReturnRepository;
import com.axelor.apps.bglocalization.report.IReport;
import com.axelor.apps.bglocalization.service.BgIdentifierValidator;
import com.axelor.apps.bglocalization.service.vat.BgVatAdvanceService;
import com.axelor.apps.bglocalization.service.vat.BgVatDiarySnapshotService;
import com.axelor.apps.bglocalization.service.vat.BgVatExportService;
import com.axelor.apps.bglocalization.service.vat.BgVatGenerateReport;
import com.axelor.apps.bglocalization.service.vat.BgVatPackageService;
import com.axelor.apps.bglocalization.service.vat.BgVatPartyName;
import com.axelor.apps.bglocalization.service.vat.BgVatRegisteredAddress;
import com.axelor.apps.bglocalization.service.vat.BgVatReportPrintService;
import com.axelor.apps.bglocalization.service.vat.BgVatReturnCorrectionService;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportResultDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatGeneratedFileDto;
import com.axelor.apps.bglocalization.service.vat.submission.BgVatSubmissionService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.apps.report.engine.ReportSettings;
import com.axelor.i18n.I18n;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.axelor.meta.schema.actions.ActionView;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Controller for {@link BgVatReturn}: the "Генерирай" button builds the export request from the
 * record, asks {@link BgVatExportService} to produce all four NAP files, attaches each to its
 * MetaFile field, sets status = generated, and offers a ZIP of the four for download.
 *
 * <p>The button is wired as {@code save,action-...,save} so the record has an id before generation
 * and the attached files persist afterwards.
 */
@Singleton
public class BgVatReturnController {

  /** Заглавието на диалога след „Генерирай“. */
  private static final String GENERATE_TITLE = "Генериране на ДДС декларацията";

  /** Статусите на декларацията, която коригиращата поправя. */
  static final List<String> CORRECTABLE_STATUSES = List.of("submitted", "accepted", "rejected");

  public void generate(ActionRequest request, ActionResponse response) {
    try {
      BgVatReturn vatReturn = request.getContext().asType(BgVatReturn.class);
      CapabilityGate.requireRole("generate a VAT return", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgVatReturn.class, vatReturn.getId());
      CapabilityGate.requireActiveCompany(vatReturn.getCompany());
      String statusRefusal =
          Beans.get(BgVatReturnCorrectionService.class)
              .generateRefusal(vatReturn.getStatusSelect());
      if (statusRefusal != null) {
        response.setError(statusRefusal, GENERATE_TITLE);
        return;
      }

      // Resolve the declarant (record → company default → single), then snapshot it onto the
      // record.
      BgDeclarant declarant;
      try {
        declarant = resolveDeclarant(vatReturn, null);
      } catch (IllegalStateException ex) {
        response.setError(I18n.get(ex.getMessage())); // "Уточнете декларатор"
        return;
      }
      applyDeclarantSnapshot(response, declarant);

      BgVatExportRequestDto exportRequest = toRequest(vatReturn, declarant);

      // Block generation on an incomplete/invalid declarant — and on a registered person without a
      // usable correspondence address (ППЗДДС чл. 117, ал. 2, т. 1; VIES ред VTR).
      List<String> declarantErrors = generationRefusals(vatReturn, exportRequest);
      if (!declarantErrors.isEmpty()) {
        response.setError(String.join("\n", declarantErrors));
        return;
      }
      BgVatExportResultDto result = Beans.get(BgVatExportService.class).generate(exportRequest);
      // Авансите: стари авансови фактури без ДДС статия и окончателни без връзка с платен аванс.
      result
          .getWarnings()
          .addAll(
              Beans.get(BgVatAdvanceService.class)
                  .periodWarnings(
                      vatReturn.getCompany(),
                      exportRequest.getPeriodFrom(),
                      exportRequest.getPeriodTo()));

      // Защо дневниците са празни или непълни — иначе предупрежденията остават само в пакета.
      String dialog = BgVatGenerateReport.dialog(result);
      if (result.getGeneratedFiles().isEmpty()) {
        response.setInfo(dialog != null ? dialog : result.getMessage(), GENERATE_TITLE);
        return;
      }
      // Файл, който не се е съставил, или пакет без DEKLAR/PRODAGBI/POKUPKI — грешка, не
      // „генерирана“ декларация с липсващ файл.
      List<String> missing = BgVatGenerateReport.missingDiaryFiles(result);
      if (!missing.isEmpty() || !result.isSuccess()) {
        response.setError(BgVatGenerateReport.incompleteDialog(result, missing), GENERATE_TITLE);
        return;
      }

      // ПЪРВО всичко, което се записва — снимката на дневниците, пакетът, ZIP-ът. Падне ли нещо,
      // формата не получава НИЩО: нито файлове, нито статус „Генерирана“, нито клетки. Преди тук
      // стойностите вече бяха в отговора, грешката излизаше като английски текст, а следващото
      // `save` на бутона записваше „генерирана“ декларация без снимка и без пакет.
      MetaFile zip;
      try {
        // Freeze the diary rows (same collector pass that built the TXT) onto the record so the
        // BIRT reports read exactly the filed numbers (report == TXT). Record is already saved
        // (button: save → generate → save), so the id is present; regenerate replaces the rows.
        Beans.get(BgVatDiarySnapshotService.class)
            .snapshot(vatReturn.getId(), result.getSalesRows(), result.getPurchaseRows());
        // Snapshot the generated files into a new immutable package (supersedes the previous one;
        // the previous package's files are never modified). Preview/submission read these bytes.
        Beans.get(BgVatPackageService.class).createPackage(vatReturn, result, exportRequest);
        // Package the files as a single ZIP for download.
        zip = zipAll(result, vatReturn.getPeriod());
      } catch (Exception e) {
        TraceBackService.trace(e, GENERATE_TITLE);
        response.setError(saveFailure(e), GENERATE_TITLE);
        return;
      }

      // Чернова, генерирана наново, носи адреса по ДЕЙСТВАЩОТО правило — снимката се обновява тук,
      // заедно с файла, който го носи.
      response.setValue("companyAddress", exportRequest.getCompanyAddress());

      // Attach each produced file to its MetaFile field. VIES.TXT няма, когато няма данни за VIES
      // (ППЗДДС чл. 117 ал. 5, виж BgVatExportService.generateViesFile) — файлът от предишно
      // генериране не бива да остане закачен.
      response.setValue("viesFile", null);
      MetaFileRepository metaFileRepo = Beans.get(MetaFileRepository.class);
      for (BgVatGeneratedFileDto file : result.getGeneratedFiles()) {
        MetaFile metaFile = metaFileRepo.find(file.getMetaFileId());
        switch (file.getScope()) {
          case "sales":
            response.setValue("prodagbiFile", metaFile);
            break;
          case "purchases":
            response.setValue("pokupkiFile", metaFile);
            break;
          case "declaration":
            response.setValue("deklarFile", metaFile);
            break;
          case "vies":
            response.setValue("viesFile", metaFile);
            break;
          default:
            break;
        }
      }
      response.setValue("statusSelect", "generated");

      // Snapshot the COMPUTED declaration cells onto the record so the conditional activity of the
      // manual cells (к.33 when к.42≠0) becomes live and visible.
      Map<String, BigDecimal> cells = snapshotDeclarationCells(response, result);
      // Auto-fill the effective чл.92 cells (кл.71/82) from the fresh кл.50/60 + manual
      // кл.70/80/81.
      autoFillResult(response, vatReturn, cells.get("k50"), cells.get("k60"));

      if (dialog != null) {
        response.setInfo(dialog, GENERATE_TITLE);
      } else {
        response.setNotify(result.getMessage());
      }
      response.setView(
          ActionView.define("NAP файлове")
              .model(BgVatReturn.class.getName())
              .add(
                  "html",
                  "ws/rest/com.axelor.meta.db.MetaFile/"
                      + zip.getId()
                      + "/content/download?v="
                      + zip.getVersion())
              .param("download", "true")
              .map());
    } catch (Exception e) {
      TraceBackService.trace(e, GENERATE_TITLE);
      response.setError(
          "Генерирането спря с грешка и нищо не е записано: " + failureText(e), GENERATE_TITLE);
    }
  }

  /**
   * Записът на дневниците/пакета не мина. Проверката на сумите в снимката казва документ, колона и
   * причина; всичко друго — текстът на изключението, предшестван от това, което не е станало.
   */
  static String saveFailure(Throwable e) {
    return "Файловете са съставени, но дневниците и пакетът не са записани — декларацията остава"
        + " неготова и нищо не е изтеглено. "
        + failureText(e);
  }

  /** Най-вътрешното съобщение — ConstraintViolationException идва увита от транзакцията. */
  static String failureText(Throwable e) {
    Throwable deepest = e;
    while (deepest.getCause() != null && deepest.getCause() != deepest) {
      deepest = deepest.getCause();
    }
    return deepest.getMessage() != null ? deepest.getMessage() : deepest.toString();
  }

  // ---------------------------------------------------------------------------
  // WS-D: BIRT print / export of the persisted diary snapshot (report == TXT).
  // ---------------------------------------------------------------------------

  /** Print (PDF) — справка-декларация (1:1 по образеца на НАП). */
  public void printDeclaration(ActionRequest request, ActionResponse response) {
    printDiary(request, response, IReport.BG_VAT_DECLARATION, ReportSettings.FORMAT_PDF, "СД-ДДС");
  }

  /** Print (PDF) — дневник продажби. */
  public void printSalesDiary(ActionRequest request, ActionResponse response) {
    printDiary(
        request,
        response,
        IReport.BG_VAT_SALES_DIARY,
        ReportSettings.FORMAT_PDF,
        "Дневник-продажби");
  }

  /** Export (XLSX) — дневник продажби. */
  public void exportSalesDiary(ActionRequest request, ActionResponse response) {
    printDiary(
        request,
        response,
        IReport.BG_VAT_SALES_DIARY,
        ReportSettings.FORMAT_XLSX,
        "Дневник-продажби");
  }

  /** Print (PDF) — дневник покупки. */
  public void printPurchaseDiary(ActionRequest request, ActionResponse response) {
    printDiary(
        request,
        response,
        IReport.BG_VAT_PURCHASE_DIARY,
        ReportSettings.FORMAT_PDF,
        "Дневник-покупки");
  }

  /** Export (XLSX) — дневник покупки. */
  public void exportPurchaseDiary(ActionRequest request, ActionResponse response) {
    printDiary(
        request,
        response,
        IReport.BG_VAT_PURCHASE_DIARY,
        ReportSettings.FORMAT_XLSX,
        "Дневник-покупки");
  }

  /** Shared: render a diary report for the current record and open the file. */
  protected void printDiary(
      ActionRequest request,
      ActionResponse response,
      String rptdesign,
      String format,
      String outputBaseName) {
    try {
      BgVatReturn vatReturn = request.getContext().asType(BgVatReturn.class);
      if (vatReturn.getId() == null
          || vatReturn.getStatusSelect() == null
          || "draft".equals(vatReturn.getStatusSelect())) {
        response.setInfo(I18n.get("Първо генерирайте декларацията, за да има редове за отчета."));
        return;
      }
      String fileLink =
          Beans.get(BgVatReportPrintService.class)
              .print(vatReturn, rptdesign, format, outputBaseName);
      response.setView(
          ActionView.define(outputBaseName)
              .add("html", fileLink)
              .param("download", ReportSettings.FORMAT_PDF.equals(format) ? "false" : "true")
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Prefill on company/period change: registered-person address (from partner.mainAddress), кл.33
   * (from {@link BgVatCoefficient}) and the default per-company declarant. The declarant SNAPSHOT
   * is NOT written here — it is produced by the generator ({@link #resolveDeclarant}) at
   * generation.
   */
  public void prefill(ActionRequest request, ActionResponse response) {
    try {
      BgVatReturn vatReturn = request.getContext().asType(BgVatReturn.class);
      Company company = vatReturn.getCompany();
      if (company == null) {
        return;
      }
      CapabilityGate.requireActiveCompany(company);

      // Registered-person address: BgVatRegisteredAddress — „Адрес за кореспонденция“ на
      // дружеството, иначе „Адрес“. Партньорът НЕ се чете.
      response.setValue("companyAddress", BgVatRegisteredAddress.of(company));

      // Default declarant for the company (silent; the user may change it before generating).
      BgDeclarant defaultDeclarant =
          Beans.get(BgDeclarantRepository.class)
              .all()
              .filter("self.company = :c AND self.isDefault = true")
              .bind("c", company)
              .fetchOne();
      if (defaultDeclarant != null) {
        response.setValue("bgDeclarant", defaultDeclarant);
      }

      // кл.33 snapshot from BgVatCoefficient(company, year of period).
      Integer year = parseYear(vatReturn.getPeriod());
      if (year != null) {
        BgVatCoefficient coefficient =
            Beans.get(BgVatCoefficientRepository.class)
                .all()
                .filter("self.company = :c AND self.year = :y")
                .bind("c", company)
                .bind("y", year)
                .fetchOne();
        if (hasUsableCoefficient(coefficient)) {
          response.setValue("k33", coefficient.getCoefficient());
        } else {
          response.setValue("k33", BigDecimal.ZERO);
          response.setNotify(
              "Няма попълнен коефициент чл.73 за "
                  + year
                  + " — кл.33 остава 0. Въведете го в менюто Коефициенти чл.73.");
        }
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Има ли ред за коефициента по чл. 73 ЗДДС И попълнена ли е стойността му.
   *
   * <p>⚠️ ВТОРАТА ПОЛОВИНА НЕ Е ИЗЛИШНА. Написана като {@code getCoefficient() != null}, тя беше
   * мъртва: генерираният getter за decimal връща ZERO, не null. Следствието се виждаше отвън — ред
   * с непопълнен коефициент слагаше кл.33 = 0,00 БЕЗ предупреждение и декларацията изглеждаше
   * нормално попълнена. Сега такъв ред минава по клона „няма попълнен коефициент“.
   *
   * <p>Оттам и че нулата се брои за непопълнена: коефициент 0,00 и липсващ ред дават еднакъв кл.33,
   * тоест единствената разлика е дали човекът е предупреден. По-добре предупреден веднъж излишно.
   */
  static boolean hasUsableCoefficient(BgVatCoefficient coefficient) {
    return coefficient != null && coefficient.getCoefficient().signum() != 0;
  }

  private static Integer parseYear(String period) {
    if (period != null && period.matches("\\d{6}")) {
      return Integer.parseInt(period.substring(0, 4));
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // Workflow transitions (guarded — only the legal edges are allowed)
  // ---------------------------------------------------------------------------

  /**
   * generated → submitted (validated: НАП №, declarant, к.33 when к.42≠0, ≥1 file, a package
   * without blocking errors).
   */
  public void markSubmitted(ActionRequest request, ActionResponse response) {
    try {
      BgVatReturn vatReturn = request.getContext().asType(BgVatReturn.class);
      CapabilityGate.requireRole("record a VAT return as submitted", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgVatReturn.class, vatReturn.getId());
      String refusal = submitRefusal(vatReturn);
      if (refusal != null) {
        response.setError(I18n.get(refusal));
        return;
      }
      response.setValue("statusSelect", "submitted");
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Why the return cannot be marked as submitted; {@code null} when it can. */
  protected String submitRefusal(BgVatReturn vatReturn) {
    if (!"generated".equals(vatReturn.getStatusSelect())) {
      return "Само генерирана декларация може да се отбележи като подадена.";
    }
    if (vatReturn.getCorrectsPrevious() != null) {
      String correction = correctionRefusal(vatReturn, correctionTarget(vatReturn));
      if (correction != null) {
        return correction;
      }
    }
    List<String> missing = validateForSubmit(vatReturn);
    if (!missing.isEmpty()) {
      return "Не може да се подаде — липсва: " + String.join("; ", missing);
    }
    // Същата порта като sendToNap (BgVatSubmissionService.submit): пакет с ГРЕШКИ — номер на
    // документ или протокол, тип документ, VIES по чл. 15а… — не се подава и „на ръка“.
    BgVatPackage pkg = latestPackage(vatReturn);
    if (pkg != null && BgVatPackageRepository.READINESS_ERRORS.equals(pkg.getReadinessSelect())) {
      return "Пакетът има блокиращи грешки — коригирайте и генерирайте отново.";
    }
    // Require regeneration when the source VAT data changed after the package was prepared.
    if (isLatestPackageStale(vatReturn)) {
      return "Изходните ДДС данни са променени след генерирането — натиснете „Генерирай отново"
          + "\" преди подаване.";
    }
    return null;
  }

  /** Декларацията, която коригиращата поправя — записаната, не копието от контекста на формата. */
  protected BgVatReturn correctionTarget(BgVatReturn correction) {
    return Beans.get(BgVatReturnRepository.class).find(correction.getCorrectsPrevious().getId());
  }

  /**
   * КОРИГИРАЩАТА СОЧИ ПОДАДЕНИЯ ОРИГИНАЛ ЗА СЪЩИЯ ПЕРИОД И СЪЩОТО ДРУЖЕСТВО — иначе отказ (като
   * {@code BgD1DeclarationKind#refuseCorrectingTarget}). Корекцията е повторно подаване за същия
   * период (ЗДДС чл. 126 ал. 2); сочеща чужд месец или чуждо дружество, тя не поправя нищо. {@code
   * null} — може, или декларацията не е коригираща.
   */
  static String correctionRefusal(BgVatReturn correction, BgVatReturn target) {
    if (correction.getCorrectsPrevious() == null) {
      return null;
    }
    if (target == null) {
      return "Декларацията, която корекцията поправя (поле „Коригира декларация“), не е намерена.";
    }
    if (target.getCorrectsPrevious() != null) {
      return "Полето „Коригира декларация“ сочи друга коригираща. Корекцията сочи оригинала —"
          + " първата декларация за периода.";
    }
    if (!Objects.equals(correction.getPeriod(), target.getPeriod())) {
      return "Коригиращата е за период "
          + correction.getPeriod()
          + ", а декларацията, която поправя — за "
          + target.getPeriod()
          + ". Корекцията се подава за същия период (ЗДДС чл. 126 ал. 2).";
    }
    if (!sameCompany(correction, target)) {
      return "Коригиращата и декларацията, която поправя, са на различни дружества.";
    }
    if (target.getStatusSelect() == null
        || !CORRECTABLE_STATUSES.contains(target.getStatusSelect())) {
      return "Декларацията, която корекцията поправя, не е подадена — подайте нея, не корекция.";
    }
    return null;
  }

  private static boolean sameCompany(BgVatReturn a, BgVatReturn b) {
    Company ca = a.getCompany();
    Company cb = b.getCompany();
    if (ca == null || cb == null) {
      return ca == cb;
    }
    return ca == cb || (ca.getId() != null && ca.getId().equals(cb.getId()));
  }

  /**
   * Към кой запис сочи нова коригираща: към оригинала. Корекция от коригираща сочи нейния оригинал
   * — веригата не се удължава.
   */
  static BgVatReturn correctionRoot(BgVatReturn source) {
    return source.getCorrectsPrevious() != null ? source.getCorrectsPrevious() : source;
  }

  /** The latest prepared package; a failed lookup is not swallowed — the transition stops. */
  protected BgVatPackage latestPackage(BgVatReturn vatReturn) {
    return Beans.get(BgVatPackageService.class).findLatestPrepared(vatReturn);
  }

  /** submitted → accepted. */
  public void markAccepted(ActionRequest request, ActionResponse response) {
    transition(
        request,
        response,
        "submitted",
        "accepted",
        "Само подадена декларация може да се отбележи като приета.");
  }

  /** submitted → rejected. */
  public void markRejected(ActionRequest request, ActionResponse response) {
    transition(
        request,
        response,
        "submitted",
        "rejected",
        "Само подадена декларация може да се отбележи като отхвърлена.");
  }

  private void transition(
      ActionRequest request, ActionResponse response, String from, String to, String error) {
    try {
      BgVatReturn vatReturn = request.getContext().asType(BgVatReturn.class);
      CapabilityGate.requireRole("record the НАП outcome of a VAT return", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgVatReturn.class, vatReturn.getId());
      if (!from.equals(vatReturn.getStatusSelect())) {
        response.setError(I18n.get(error));
        return;
      }
      response.setValue("statusSelect", to);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * submitted/accepted/rejected → a NEW corrected return (status=corrected) linked to the original
   * via correctsPrevious. The original is left untouched (traceability). Manual cells + declarant
   * snapshot are copied so the user only edits the correction.
   *
   * <p>След срока по ЗДДС чл. 125 ал. 5 корекцията пак се създава, но с предупреждение ({@link
   * BgVatReturnCorrectionService#correctionWarning}).
   */
  public void createCorrection(ActionRequest request, ActionResponse response) {
    try {
      BgVatReturn context = request.getContext().asType(BgVatReturn.class);
      CapabilityGate.requireRole("correct a VAT return", Capabilities.NAP_WRITE);
      CapabilityGate.requireRead(BgVatReturn.class, context.getId());
      CapabilityGate.requireWrite(BgVatReturn.class, null);
      String status = context.getStatusSelect();
      if (!"submitted".equals(status) && !"accepted".equals(status) && !"rejected".equals(status)) {
        response.setError(
            "Корекция може да се създаде само от подадена/приета/отхвърлена декларация.");
        return;
      }
      BgVatReturnRepository repo = Beans.get(BgVatReturnRepository.class);
      BgVatReturn source = repo.find(context.getId());

      BgVatReturn correction = new BgVatReturn();
      correction.setCompany(source.getCompany());
      correction.setPeriod(source.getPeriod());
      correction.setStatusSelect("corrected");
      correction.setCorrectsPrevious(correctionRoot(source));
      correction.setBgDeclarant(source.getBgDeclarant());
      // Manual cells (user will adjust these on the correction).
      correction.setK33(source.getK33());
      correction.setK70(source.getK70());
      correction.setK71(source.getK71());
      correction.setK80(source.getK80());
      correction.setK81(source.getK81());
      correction.setK82(source.getK82());
      // Registered-person + declarant snapshot.
      correction.setCompanyAddress(source.getCompanyAddress());
      correction.setBgDeclarantEgn(source.getBgDeclarantEgn());
      correction.setBgDeclarantNames(source.getBgDeclarantNames());
      correction.setBgDeclarantCity(source.getBgDeclarantCity());
      correction.setBgDeclarantPostCode(source.getBgDeclarantPostCode());
      correction.setBgDeclarantAddress(source.getBgDeclarantAddress());
      correction.setBgDeclarantCapacity(source.getBgDeclarantCapacity());
      correction.setBgDeclarantPosition(source.getBgDeclarantPosition());

      correction = repo.save(correction);
      String warning = Beans.get(BgVatReturnCorrectionService.class).correctionWarning(source);

      response.setView(
          ActionView.define("Корекция на декларация")
              .model(BgVatReturn.class.getName())
              .add("form", "bg-vat-return-form")
              .add("grid", "bg-vat-return-grid")
              .context("_showRecord", correction.getId())
              .map());
      if (warning != null) {
        response.setInfo(warning, "Корекция след срока");
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Returns the human-readable list of what blocks submission (empty = OK). */
  protected List<String> validateForSubmit(BgVatReturn r) {
    List<String> missing = new ArrayList<>();
    if (isBlank(r.getNapEntryNumber())) {
      missing.add("НАП входящ №");
    }
    if (isBlank(r.getBgDeclarantEgn())
        || isBlank(r.getBgDeclarantNames())
        || isBlank(r.getBgDeclarantCity())
        || isBlank(r.getBgDeclarantPostCode())
        || isBlank(r.getBgDeclarantAddress())
        || isBlank(r.getBgDeclarantCapacity())) {
      missing.add("пълен декларатор (ЕГН/имена/град/пощ.код/адрес/качество)");
    }
    // No null guards on к.42/к.33: the generated decimal getters return ZERO, so signum() alone
    // carries the question ("is anything entered here?").
    if (r.getK42().signum() != 0 && r.getK33().signum() == 0) {
      missing.add("кл.33 (коефициент — задължителен при кл.42 ≠ 0)");
    }
    if (r.getProdagbiFile() == null
        && r.getPokupkiFile() == null
        && r.getDeklarFile() == null
        && r.getViesFile() == null) {
      missing.add("поне един генериран файл");
    }
    return missing;
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  /**
   * Resolve the declarant to use, by priority: (1) {@code declarantIdCtx} (API/batch escape hatch),
   * (2) the record's {@code bgDeclarant}, (3) the company's default declarant, (4) the company's
   * only declarant. Otherwise throws {@link IllegalStateException} with "Уточнете декларатор".
   */
  protected BgDeclarant resolveDeclarant(BgVatReturn vatReturn, Long declarantIdCtx) {
    BgDeclarantRepository repo = Beans.get(BgDeclarantRepository.class);
    if (declarantIdCtx != null) {
      BgDeclarant byId = repo.find(declarantIdCtx);
      if (byId != null) {
        return byId;
      }
    }
    if (vatReturn.getBgDeclarant() != null) {
      return vatReturn.getBgDeclarant();
    }
    Company company = vatReturn.getCompany();
    if (company != null) {
      BgDeclarant def =
          repo.all()
              .filter("self.company = :c AND self.isDefault = true")
              .bind("c", company)
              .fetchOne();
      if (def != null) {
        return def;
      }
      List<BgDeclarant> forCompany =
          repo.all().filter("self.company = :c").bind("c", company).fetch(2, 0);
      if (forCompany.size() == 1) {
        return forCompany.get(0);
      }
    }
    throw new IllegalStateException("Уточнете декларатор");
  }

  /** Write the resolved declarant 1:1 into the record's snapshot fields (persisted on save). */
  protected void applyDeclarantSnapshot(ActionResponse response, BgDeclarant d) {
    response.setValue("bgDeclarant", d);
    response.setValue("bgDeclarantEgn", d.getDeclarantEgn());
    response.setValue("bgDeclarantNames", d.getDeclarantNames());
    response.setValue("bgDeclarantCity", d.getDeclarantCity());
    response.setValue("bgDeclarantPostCode", d.getDeclarantPostCode());
    response.setValue("bgDeclarantAddress", d.getDeclarantAddress());
    response.setValue("bgDeclarantCapacity", d.getDeclarantCapacity());
    // Длъжността — за подписа на Прил. № 13 („Длъжност“), не „Качество“.
    response.setValue("bgDeclarantPosition", d.getDeclarantPosition());
  }

  protected BgVatExportRequestDto toRequest(BgVatReturn vatReturn, BgDeclarant declarant) {
    BgVatExportRequestDto dto = new BgVatExportRequestDto();
    Company company = vatReturn.getCompany();
    if (company != null) {
      dto.setCompanyId(company.getId());
      // Регистрираното име с правната форма — от партньора, не краткото Company.name (DEKLAR
      // позиции 16–65, VIES ред VTR).
      dto.setCompanyName(BgVatPartyName.ofCompany(company));
      Partner partner = company.getPartner();
      if (partner != null) {
        dto.setCompanyVatNumber(partner.getTaxNbr()); // ЕИК по ДДС
      }
    }
    // Registered-person address = the ONE rule (BgVatRegisteredAddress), read at generation: a
    // draft
    // generated again carries the address in force; the record snapshot is updated with the file.
    dto.setCompanyAddress(BgVatRegisteredAddress.of(company));

    // Period "ГГГГММ" → first/last day of the month.
    String period = vatReturn.getPeriod();
    if (period != null && period.matches("\\d{6}")) {
      int year = Integer.parseInt(period.substring(0, 4));
      int month = Integer.parseInt(period.substring(4, 6));
      LocalDate from = LocalDate.of(year, month, 1);
      dto.setPeriodFrom(from);
      dto.setPeriodTo(from.withDayOfMonth(from.lengthOfMonth()));
    }

    dto.setReportType("all"); // BgVatReturn always produces the full set of files
    dto.setEncoding("cp1251");
    dto.setDryRun(false);
    // Zero-declaration: always emit DEKLAR, PRODAGBI and POKUPKI, the empty ones too (ППЗДДС
    // чл. 114 ал. 3). VIES.TXT — само когато има редове за VIES (ППЗДДС чл. 117 ал. 5). Сверено
    // на 23.10.2026 с консолидирания текст на ППЗДДС (последно изм. ДВ бр. 54/2024).
    dto.setEmitEmptyFiles(true);

    // Manual DEKLAR cells captured on the record (computed cells are filled by the service in
    // 1б-2).
    dto.setCoefficientK33(vatReturn.getK33());
    dto.setCell70(vatReturn.getK70());
    dto.setCell71(vatReturn.getK71());
    dto.setCell80(vatReturn.getK80());
    dto.setCell81(vatReturn.getK81());
    dto.setCell82(vatReturn.getK82());

    // Declarant = the resolved BgDeclarant (record.bgDeclarant / company default / single).
    dto.setDeclarantEgn(declarant.getDeclarantEgn());
    dto.setDeclarantNames(declarant.getDeclarantNames());
    dto.setDeclarantCity(declarant.getDeclarantCity());
    dto.setDeclarantPostCode(declarant.getDeclarantPostCode());
    dto.setDeclarantAddress(declarant.getDeclarantAddress());
    dto.setDeclarantCapacity(declarant.getDeclarantCapacity());
    return dto;
  }

  /**
   * ВСИЧКО, КОЕТО СПИРА ГЕНЕРИРАНЕТО — адресът за кореспонденция на регистрираното лице ({@link
   * BgVatRegisteredAddress}: празен или над 200 знака) и деклараторът ({@link #validateDeclarant}).
   * Празно = генерира се.
   */
  protected List<String> generationRefusals(BgVatReturn vatReturn, BgVatExportRequestDto dto) {
    List<String> errors = new ArrayList<>();
    String addressRefusal = BgVatRegisteredAddress.refusal(vatReturn.getCompany());
    if (addressRefusal != null) {
      errors.add(addressRefusal);
    }
    errors.addAll(validateDeclarant(dto));
    return errors;
  }

  /**
   * Validate the RESOLVED declarant before generation (Задача 4/5/6). Minimum required: ЕГН/ЛНЧ,
   * имена, качество. ЕГН/ЛНЧ checksum and postal-code format are also enforced.
   */
  protected List<String> validateDeclarant(BgVatExportRequestDto dto) {
    List<String> errors = new ArrayList<>();
    List<String> absent = new ArrayList<>();
    if (isBlank(dto.getDeclarantEgn())) {
      absent.add("ЕГН/ЛНЧ");
    }
    if (isBlank(dto.getDeclarantNames())) {
      absent.add("имена");
    }
    if (isBlank(dto.getDeclarantCapacity())) {
      absent.add("качество");
    }
    if (!absent.isEmpty()) {
      // Деклараторът Е избран — липсва поле. DEKLAR.TXT носи „ЕГН/имена“ на подаващия
      // (getDeclarantSubmitter), а VIES — ЕГН, имената и качеството му (ред VDR); без тях файловете
      // излизат непълни.
      errors.add(
          "Деклараторът на декларацията няма "
              + String.join(", ", absent)
              + " — DEKLAR.TXT и VIES.TXT носят ЕГН/ЛНЧ, имената и качеството на подаващия и без"
              + " тях са непълни, затова не се генерират. Попълва се: Дружество › „Декларатори“;"
              + " или изберете друг декларатор в декларацията.");
      return errors;
    }
    if (!BgIdentifierValidator.isValidEgnOrLnch(dto.getDeclarantEgn())) {
      errors.add("Невалиден ЕГН/ЛНЧ на декларатора.");
    }
    if (!isBlank(dto.getDeclarantPostCode())
        && !BgIdentifierValidator.isValidPostalCode(dto.getDeclarantPostCode())) {
      errors.add("Невалиден пощенски код на декларатора (изисква 4 цифри, 1000–9999).");
    }
    // ⚠️ АДРЕСЪТ ЗА КОРЕСПОНДЕНЦИЯ НА ПОДАВАЩИЯ — при ГЕНЕРИРАНЕ, не чак при подаване: VIES.TXT
    // (ред VDR, полета 4–6) го носи, а ППЗДДС чл. 117, ал. 2, т. 2 го иска. Празен, файлът щеше да
    // тръгне с празни полета.
    List<String> missing = new ArrayList<>();
    if (isBlank(dto.getDeclarantCity())) {
      missing.add("„Град“");
    }
    if (isBlank(dto.getDeclarantPostCode())) {
      missing.add("„Пощенски код“");
    }
    if (isBlank(dto.getDeclarantAddress())) {
      missing.add("„Адрес“");
    }
    if (!missing.isEmpty()) {
      errors.add(
          "Деклараторът няма пълен адрес за кореспонденция (ППЗДДС чл. 117, ал. 2, т. 2; VIES ред"
              + " VDR) — липсва "
              + String.join(", ", missing)
              + ". Попълва се: Дружество › „Декларатори“ › "
              + String.join(", ", missing)
              + ".");
    }
    return errors;
  }

  private static final Charset CP1251 = Charset.forName("windows-1251");

  /**
   * COMPUTED declaration cells and their byte positions in the DEKLAR record (the NAP VAT file
   * specification §5, header 151 + DECLARATION_ORDER). Manual cells (кл.33/70/71/80/81/82) are
   * deliberately excluded — they are user input, already snapshotted. Each value cell is 15 wide.
   */
  private static final String[][] COMPUTED_CELLS = {
    {"k01", "151", "166"}, {"k20", "166", "181"}, {"k11", "181", "196"}, {"k21", "196", "211"},
    {"k12", "211", "226"}, {"k22", "226", "241"}, {"k23", "241", "256"}, {"k13", "256", "271"},
    {"k24", "271", "286"}, {"k14", "286", "301"}, {"k15", "301", "316"}, {"k16", "316", "331"},
    {"k17", "331", "346"}, {"k18", "346", "361"}, {"k19", "361", "376"}, {"k30", "376", "391"},
    {"k31", "391", "406"}, {"k41", "406", "421"}, {"k32", "421", "436"}, {"k42", "436", "451"},
    {"k43", "451", "466"}, {"k40", "470", "485"}, {"k50", "485", "500"}, {"k60", "500", "515"}
  };

  /**
   * Parse the computed cell values back out of the DEKLAR record the adapter already produced
   * (single source of truth = the file) at the §5 positions, and write them onto the record. No
   * adapter/DTO change — the values are read from {@link BgVatGeneratedFileDto#getContent()}.
   */
  protected Map<String, BigDecimal> snapshotDeclarationCells(
      ActionResponse response, BgVatExportResultDto result) {
    Map<String, BigDecimal> values = new LinkedHashMap<>();
    BgVatGeneratedFileDto declaration = null;
    for (BgVatGeneratedFileDto file : result.getGeneratedFiles()) {
      if ("declaration".equals(file.getScope())) {
        declaration = file;
        break;
      }
    }
    if (declaration == null || declaration.getContent() == null) {
      return values;
    }
    String record = new String(declaration.getContent(), CP1251).split("\r\n", -1)[0];
    if (record.length() < 590) {
      return values;
    }
    for (String[] cell : COMPUTED_CELLS) {
      BigDecimal value = parseAmount(record, Integer.parseInt(cell[1]), Integer.parseInt(cell[2]));
      values.put(cell[0], value);
      response.setValue(cell[0], value);
    }
    return values;
  }

  /**
   * Auto-fill the effective чл.92 result cells from кл.50/60 and the manual deductions кл.70/80/81
   * — each cell independently controlled: кл.71 by {@code bgAutoFillK71}, кл.82 by {@code
   * bgAutoFillK82}. When a flag is off the cell is left as manual input. кл.70/80/81 are NOT
   * touched. All inputs are the values snapshotted on the record (кл.50/60/70/80/81), not
   * re-fetched.
   */
  protected void autoFillResult(
      ActionResponse response, BgVatReturn vatReturn, BigDecimal k50, BigDecimal k60) {
    BigDecimal base50 = nz(k50);
    BigDecimal base60 = nz(k60);
    BigDecimal k70 = nz(vatReturn.getK70());
    BigDecimal k80 = nz(vatReturn.getK80());
    BigDecimal k81 = nz(vatReturn.getK81());
    if (Boolean.TRUE.equals(vatReturn.getBgAutoFillK71())) {
      response.setValue("k71", base50.subtract(k70).setScale(2, RoundingMode.HALF_UP)); // кл.71
    }
    if (Boolean.TRUE.equals(vatReturn.getBgAutoFillK82())) {
      response.setValue(
          "k82", base60.subtract(k80).subtract(k81).setScale(2, RoundingMode.HALF_UP)); // кл.82
    }
    warnResultDeductions(response, base50, base60, k70, k80, k81);
  }

  /**
   * Recompute the AUTO cells (кл.71/82, per-flag) when a manual deduction (кл.70/80/81) changes.
   * Uses кл.50/60 already snapshotted on the record; skips while draft (cells not yet snapshotted).
   */
  public void recomputeResult(ActionRequest request, ActionResponse response) {
    try {
      BgVatReturn vatReturn = request.getContext().asType(BgVatReturn.class);
      CapabilityGate.requireRole("recompute a VAT return", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgVatReturn.class, vatReturn.getId());
      if ("draft".equals(vatReturn.getStatusSelect())) {
        return;
      }
      autoFillResult(response, vatReturn, vatReturn.getK50(), vatReturn.getK60());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  // ---------------------------------------------------------------------------
  // VAT package: preview / download / staleness / (future) submission
  // ---------------------------------------------------------------------------

  private static final String STALE_BANNER =
      "⚠ Изходните данни са променени след генерирането — натиснете „Генерирай отново\".";

  /**
   * Open the read-only preview of the latest prepared package (all four files with size, record
   * count, SHA-256, status and safe text preview).
   *
   * <p>⚠️ НЕ ПИШЕ В ДЕКЛАРАЦИЯТА И В НЕЙНИТЕ ПАКЕТИ/ЛОГОВЕ. Формата остава отворена с {@code
   * _original} от отварянето; запис в пакета (stale) или нов ред в {@code bgVatPackageEventList}
   * прави следващия ѝ save OptimisticLockException ({@code JPA.verify}). Затова „остарял“ само се
   * смята, а събитието „previewed“ се пише към ПАКЕТА, без декларацията.
   */
  public void openPreview(ActionRequest request, ActionResponse response) {
    try {
      BgVatReturn vatReturn = request.getContext().asType(BgVatReturn.class);
      if (vatReturn.getId() == null) {
        response.setInfo(I18n.get("Първо запишете и генерирайте декларацията."));
        return;
      }
      // Записът от базата, не проксито на контекста: то носи само полетата на формата.
      vatReturn = Beans.get(BgVatReturnRepository.class).find(vatReturn.getId());
      BgVatPackageService service = Beans.get(BgVatPackageService.class);
      BgVatPackage pkg = service.findLatestPrepared(vatReturn);
      if (pkg == null) {
        response.setInfo(I18n.get("Няма подготвен пакет. Първо натиснете „Генерирай“."));
        return;
      }
      boolean stale = isStaleQuietly(vatReturn, pkg, service);
      service.logEvent(pkg, null, "previewed", "Отворен преглед на файловете.");
      if (stale) {
        response.setNotify(STALE_BANNER);
      }
      response.setView(
          ActionView.define("Преглед на файловете")
              .model(BgVatPackage.class.getName())
              .add("form", "bg-vat-package-form")
              .add("grid", "bg-vat-package-grid")
              .param("forceEdit", "false")
              .context("_showRecord", pkg.getId())
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** Download one package file (from the прегед grid button). Records a download event. */
  public void downloadPackageFile(ActionRequest request, ActionResponse response) {
    try {
      BgVatPackageFile ctx = request.getContext().asType(BgVatPackageFile.class);
      CapabilityGate.requireRead(BgVatPackageFile.class, ctx.getId());
      BgVatPackageFile file = Beans.get(BgVatPackageFileRepository.class).find(ctx.getId());
      if (file == null || file.getMetaFile() == null) {
        response.setInfo(I18n.get("Няма прикачен файл за изтегляне."));
        return;
      }
      MetaFile metaFile = file.getMetaFile();
      BgVatPackage pkg = file.getBgVatPackage();
      Beans.get(BgVatPackageService.class)
          .logEvent(
              pkg,
              pkg == null ? null : pkg.getBgVatReturn(),
              "downloaded",
              "Изтеглен файл " + file.getFileName() + ".");
      response.setView(
          ActionView.define(file.getFileName())
              .add(
                  "html",
                  "ws/rest/com.axelor.meta.db.MetaFile/"
                      + metaFile.getId()
                      + "/content/download?v="
                      + metaFile.getVersion())
              .param("download", "true")
              .map());
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Send the latest prepared package to the LOCAL MOCK server as a new immutable attempt. All
   * preconditions (usable gateway, ready non-stale package, four files, no pending attempt) are
   * enforced by {@link BgVatSubmissionService#submit}; a timeout yields SUBMISSION_OUTCOME_UNKNOWN
   * and is never auto re-POSTed.
   */
  public void sendToNap(ActionRequest request, ActionResponse response) {
    try {
      BgVatReturn vatReturn = request.getContext().asType(BgVatReturn.class);
      CapabilityGate.requireRole("send a VAT return to НАП", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgVatReturn.class, vatReturn.getId());
      if (vatReturn.getId() == null) {
        response.setError(I18n.get("Първо запишете и генерирайте декларацията."));
        return;
      }
      BgDeclarant declarant;
      try {
        declarant = resolveDeclarant(vatReturn, null);
      } catch (IllegalStateException ex) {
        response.setError(I18n.get(ex.getMessage()));
        return;
      }
      BgVatExportRequestDto exportRequest = toRequest(vatReturn, declarant);
      String scenario = vatReturn.getBgMockScenario();
      try {
        BgVatSubmission attempt =
            Beans.get(BgVatSubmissionService.class).submit(vatReturn, exportRequest, scenario);
        response.setNotify(
            "Изпратено към LOCAL MOCK — статус: "
                + attempt.getStatusSelect()
                + (attempt.getEntryNumber() != null
                    ? " (вх.№ " + attempt.getEntryNumber() + ")"
                    : ""));
        response.setReload(true);
      } catch (IllegalStateException ex) {
        response.setError(I18n.get(ex.getMessage()));
      }
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Re-check the status of the latest attempt against the LOCAL MOCK. Safe to retry; never
   * re-sends.
   */
  public void checkVatStatus(ActionRequest request, ActionResponse response) {
    try {
      BgVatReturn vatReturn = request.getContext().asType(BgVatReturn.class);
      CapabilityGate.requireRole("check the НАП status of a VAT return", Capabilities.NAP_WRITE);
      CapabilityGate.requireWrite(BgVatReturn.class, vatReturn.getId());
      BgVatSubmissionService service = Beans.get(BgVatSubmissionService.class);
      BgVatSubmission latest = service.findLatest(vatReturn);
      if (latest == null) {
        response.setInfo(I18n.get("Няма подавания за проверка."));
        return;
      }
      BgVatSubmission updated = service.refreshStatus(latest);
      response.setNotify(
          "Статус: "
              + updated.getStatusSelect()
              + (updated.getEntryNumber() != null
                  ? " (вх.№ " + updated.getEntryNumber() + ")"
                  : ""));
      response.setReload(true);
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** onLoad: compute the stale banner for the latest package (skipped while draft / no package). */
  public void checkPackageStale(ActionRequest request, ActionResponse response) {
    try {
      BgVatReturn vatReturn = request.getContext().asType(BgVatReturn.class);
      if (vatReturn.getId() == null || "draft".equals(vatReturn.getStatusSelect())) {
        return;
      }
      BgVatPackageService service = Beans.get(BgVatPackageService.class);
      BgVatPackage pkg = service.findLatestPrepared(vatReturn);
      if (pkg == null) {
        response.setValue("bgPackageStale", false);
        response.setValue("bgPackageInfo", I18n.get("Няма подготвен пакет за този период."));
        return;
      }
      // Само се смята: onLoad не пише под формата, която тъкмо се отваря (виж openPreview).
      boolean stale = isStaleQuietly(vatReturn, pkg, service);
      response.setValue("bgPackageStale", stale);
      response.setValue(
          "bgPackageInfo",
          stale
              ? STALE_BANNER
              : "Пакет от "
                  + pkg.getGeneratedOn()
                  + (Boolean.TRUE.equals(pkg.getZeroDeclaration()) ? " (нулева декларация)" : "")
                  + ".");
    } catch (Exception e) {
      TraceBackService.trace(response, e);
    }
  }

  /** True when the latest prepared package is stale (source data changed). Never throws. */
  protected boolean isLatestPackageStale(BgVatReturn vatReturn) {
    try {
      BgVatPackageService service = Beans.get(BgVatPackageService.class);
      BgVatPackage pkg = service.findLatestPrepared(vatReturn);
      return pkg != null && refreshStalenessQuietly(vatReturn, pkg, service);
    } catch (Exception e) {
      return false;
    }
  }

  /** Same as {@link #refreshStalenessQuietly} but read-only: nothing is persisted. */
  protected boolean isStaleQuietly(
      BgVatReturn vatReturn, BgVatPackage pkg, BgVatPackageService service) {
    try {
      BgDeclarant declarant = resolveDeclarant(vatReturn, null);
      return service.isStale(pkg, toRequest(vatReturn, declarant));
    } catch (IllegalStateException e) {
      return Boolean.TRUE.equals(pkg.getStale());
    }
  }

  /** Refresh staleness using the same request the generator uses; swallow declarant issues. */
  protected boolean refreshStalenessQuietly(
      BgVatReturn vatReturn, BgVatPackage pkg, BgVatPackageService service) {
    try {
      BgDeclarant declarant = resolveDeclarant(vatReturn, null);
      return service.refreshStaleness(pkg, toRequest(vatReturn, declarant));
    } catch (IllegalStateException e) {
      return Boolean.TRUE.equals(pkg.getStale());
    }
  }

  /** Light (non-blocking) check: кл.70 ≤ кл.50 and (кл.80 + кл.81) ≤ кл.60. */
  private void warnResultDeductions(
      ActionResponse response,
      BigDecimal k50,
      BigDecimal k60,
      BigDecimal k70,
      BigDecimal k80,
      BigDecimal k81) {
    List<String> warnings = new ArrayList<>();
    if (k70.compareTo(k50) > 0) {
      warnings.add("кл.70 (" + k70 + ") надвишава кл.50 (" + k50 + ")");
    }
    if (k80.add(k81).compareTo(k60) > 0) {
      warnings.add("кл.80+кл.81 (" + k80.add(k81) + ") надвишават кл.60 (" + k60 + ")");
    }
    if (!warnings.isEmpty()) {
      response.setNotify(String.join("; ", warnings));
    }
  }

  private static BigDecimal nz(BigDecimal value) {
    return value == null ? BigDecimal.ZERO : value;
  }

  private static BigDecimal parseAmount(String record, int start, int end) {
    String segment = record.substring(start, end).trim();
    if (segment.isEmpty()) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(segment);
    } catch (NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  protected MetaFile zipAll(BgVatExportResultDto result, String period) throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (ZipOutputStream zos = new ZipOutputStream(baos)) {
      for (BgVatGeneratedFileDto file : result.getGeneratedFiles()) {
        zos.putNextEntry(new ZipEntry(file.getFileName()));
        zos.write(file.getContent());
        zos.closeEntry();
      }
    }
    String zipName = "NAP_" + (period == null ? "export" : period) + ".zip";
    return Beans.get(MetaFiles.class).upload(new ByteArrayInputStream(baos.toByteArray()), zipName);
  }
}
