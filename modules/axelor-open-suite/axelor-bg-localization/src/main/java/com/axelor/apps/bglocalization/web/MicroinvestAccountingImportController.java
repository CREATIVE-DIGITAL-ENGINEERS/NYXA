package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.MicroinvestAccountingImportWizard;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingCompanyGuard;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingImportReport;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingImportService;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestAccountingPreflightService;
import com.axelor.apps.bglocalization.service.migration.microinvest.accounting.MicroinvestVatPeriodCutoff;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Microinvest Delta Pro -> Nyxa accounting migration: two buttons on a transient wizard.
 *
 * <p>Deliberately a SEPARATE copy of {@code MicroinvestImportController} (payroll); the payroll
 * importer is in production and is not touched by this feature.
 *
 * <p>Preflight and Import run the same validation. The difference is that Preflight writes nothing
 * at all — not even an audit record — while Import persists the annual Moves atomically and then
 * attempts each settlement in its own transaction.
 */
@Singleton
public class MicroinvestAccountingImportController {

  /**
   * onNew/onLoad: names the company the run will write into, so the operator sees it BEFORE
   * uploading anything. The importer refuses to create a company at all; this is the matching
   * visible half of that promise.
   */
  public void showActiveCompany(ActionRequest request, ActionResponse response) {
    Company company = MicroinvestAccountingPreflightService.activeCompany();
    if (company == null) {
      response.setValue(
          "activeCompanyInfo",
          "⚠️ НЯМА активна компания. Изберете активна компания (Preferences -> Active company)"
              + " преди Preflight/Импорт. Импортът НЕ създава компания.");
      return;
    }
    response.setValue(
        "activeCompanyInfo",
        MicroinvestAccountingCompanyGuard.targetBanner(
            company.getName(),
            company.getPartner() == null ? null : company.getPartner().getRegistrationCode()));
  }

  public void preflight(ActionRequest request, ActionResponse response) {
    MicroinvestAccountingImportWizard form =
        request.getContext().asType(MicroinvestAccountingImportWizard.class);
    try {
      MetaFile sourceFile = form.getSourceFile();
      byte[] content = readUploadedFile(sourceFile);
      String lastFiledVatPeriod = form.getLastFiledVatPeriod();
      MicroinvestAccountingPreflightService.Result result =
          Beans.get(MicroinvestAccountingImportService.class)
              .preflightResult(content, lastFiledVatPeriod);
      applyReport(response, result.report, "Preflight (само четене — нищо не е записано)");
      // The confirmation shown before Import is worded HERE from the validated file, and
      // is tied to the exact MetaFile AND the exact company it was computed for. A blocked
      // Preflight yields no confirmation at all. Changing either one clears all three values.
      response.setValue(
          "importConfirmText", MicroinvestAccountingPreflightService.importConfirmation(result));
      response.setValue("preflightFileId", sourceFile.getId());
      response.setValue(
          "preflightCompanyId", result.company == null ? null : result.company.getId());
      // Обхватът също се заковава: импорт с друг период от проверения е друг импорт.
      response.setValue("preflightVatPeriod", result.report.lastFiledVatPeriod);
    } catch (Exception e) {
      response.setValue("reportText", null);
      response.setValue("importConfirmText", null);
      response.setValue("preflightFileId", null);
      response.setValue("preflightCompanyId", null);
      response.setValue("preflightVatPeriod", null);
      TraceBackService.trace(response, e);
    }
  }

  public void runImport(ActionRequest request, ActionResponse response) {
    MicroinvestAccountingImportWizard form =
        request.getContext().asType(MicroinvestAccountingImportWizard.class);
    try {
      MetaFile sourceFile = form.getSourceFile();
      Company activeCompany = MicroinvestAccountingPreflightService.activeCompany();
      // ⚠️ Before anything is read or written: no valid, current, unblocked Preflight means no
      // import. The write path is never entered.
      assertImportAllowed(
          form,
          sourceFile == null ? null : sourceFile.getId(),
          activeCompany == null ? null : activeCompany.getId());
      byte[] content = readUploadedFile(sourceFile);
      MicroinvestAccountingImportReport report =
          Beans.get(MicroinvestAccountingImportService.class)
              .runImport(content, sourceFile, form.getLastFiledVatPeriod());
      applyReport(response, report, "Импорт");
    } catch (Exception e) {
      response.setValue("reportText", null);
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Replays ONLY the settlements of a year whose Moves are already in the ledger.
   *
   * <p>Guarded exactly like Import — same file, same company, same VAT period, zero blockers — but
   * it never reaches the annual write transaction: no Move, MoveLine, Partner, opening balance or
   * FixedAsset is created or amended. See {@code
   * MicroinvestAccountingImportService#retrySettlements}.
   */
  public void retrySettlements(ActionRequest request, ActionResponse response) {
    MicroinvestAccountingImportWizard form =
        request.getContext().asType(MicroinvestAccountingImportWizard.class);
    try {
      MetaFile sourceFile = form.getSourceFile();
      Company activeCompany = MicroinvestAccountingPreflightService.activeCompany();
      assertImportAllowed(
          form,
          sourceFile == null ? null : sourceFile.getId(),
          activeCompany == null ? null : activeCompany.getId());
      byte[] content = readUploadedFile(sourceFile);
      MicroinvestAccountingImportReport report =
          Beans.get(MicroinvestAccountingImportService.class)
              .retrySettlements(content, form.getLastFiledVatPeriod());
      applyReport(response, report, "Повторно равнение (само равненията)");
    } catch (Exception e) {
      response.setValue("reportText", null);
      TraceBackService.trace(response, e);
    }
  }

  /**
   * Import is only reachable through a clean Preflight of THIS file, for THIS company.
   *
   * <p>Four separate refusals, in the order the operator can act on them:
   *
   * <ol>
   *   <li>Preflight found blockers — nothing can be imported until they are fixed, and there is no
   *       confirmation to agree to;
   *   <li>no Preflight at all — the target year/currency has not been validated;
   *   <li>the file changed afterwards — the confirmation described a different year;
   *   <li>the active company changed afterwards — the confirmation named a different company, and
   *       the import writes into whichever company is active NOW.
   * </ol>
   *
   * <p>Enforced here, not only in the view, so the write path is unreachable even if an action is
   * invoked directly.
   */
  public static void assertImportAllowed(
      MicroinvestAccountingImportWizard form, Long fileId, Long activeCompanyId) {
    // ⚠️ Ask for the VALUE, never for null: blockedCount, preflightFileId and preflightCompanyId
    // are generated getters over an <integer>/<long> field, so they return 0 / 0L when unset and
    // are never null. A "!= null" here reads like a guard and is a dead condition — which is
    // exactly how "no Preflight at all" stopped being checked below. Unset is 0.
    Integer blocked = form.getBlockedCount();
    if (blocked > 0) {
      throw new IllegalStateException(
          "Preflight намери "
              + blocked
              + " блокера. Отстранете ги и пуснете Preflight наново — импортът не записва нищо,"
              + " докато има блокер. Подробностите са в доклада от Preflight.");
    }
    String confirmText = form.getImportConfirmText();
    if (confirmText == null || confirmText.trim().isEmpty() || form.getPreflightFileId() == 0L) {
      throw new IllegalStateException(
          "Пуснете Preflight за този файл, преди да импортирате. Без Preflight годината и"
              + " счетоводната ѝ валута не са проверени.");
    }
    if (fileId == null || !form.getPreflightFileId().equals(fileId)) {
      throw new IllegalStateException(
          "Файлът е сменен след Preflight. Пуснете Preflight наново за текущия файл.");
    }
    if (activeCompanyId == null
        || form.getPreflightCompanyId() == 0L
        || !form.getPreflightCompanyId().equals(activeCompanyId)) {
      throw new IllegalStateException(
          "Активната компания е сменена след Preflight. Пуснете Preflight наново за текущата"
              + " компания.");
    }
    // ⚠️ Пети отказ: ОБХВАТЪТ. Преди всичко — валиден период изобщо; и след това дали е същият,
    // който Preflight е проверил. Сменен след Preflight, той би внесъл друг набор документи от
    // потвърдения — включително още неподадения месец.
    String periodRefusal = MicroinvestVatPeriodCutoff.refusal(form.getLastFiledVatPeriod());
    if (periodRefusal != null) {
      throw new IllegalStateException(periodRefusal);
    }
    String preflightPeriod = form.getPreflightVatPeriod();
    if (preflightPeriod == null
        || !preflightPeriod.trim().equals(form.getLastFiledVatPeriod().trim())) {
      throw new IllegalStateException(
          "Последният подаден ДДС период е сменен след Preflight. Пуснете Preflight наново за"
              + " текущия период.");
    }
  }

  private byte[] readUploadedFile(MetaFile metaFile) throws Exception {
    if (metaFile == null) {
      throw new IllegalArgumentException("Не е качен експортен файл.");
    }
    String fileName = metaFile.getFileName();
    // Server-side on purpose: x-accept on the form only restricts the browser's file dialog, and a
    // renamed non-JSON file would otherwise slip through.
    if (fileName == null || !fileName.toLowerCase().endsWith(".json")) {
      throw new IllegalArgumentException(
          "Очаква се .json файл (от инструмента за износ от Microinvest), а е подаден: "
              + fileName);
    }
    Path path = Beans.get(MetaFiles.class).getPath(metaFile);
    return Files.readAllBytes(path);
  }

  private void applyReport(
      ActionResponse response, MicroinvestAccountingImportReport report, String heading) {
    response.setValue("reportText", report.toText(heading));
    response.setValue("entriesFoundCount", report.entriesFound);
    response.setValue("sourceRecordCount", report.sourceRecords);
    response.setValue("createdCount", report.created);
    response.setValue("updatedCount", report.reused);
    response.setValue("reconciledCount", report.reconciled);
    response.setValue("unresolvedCount", report.unresolved);
    response.setValue("skippedCount", report.skipped);
    response.setValue("skippedAfterCutoffCount", report.skippedAfterCutoff);
    response.setValue("blockedCount", report.blocked);
    response.setValue("failedCount", report.failed);
    response.setValue("activeCompanyInfo", report.getTargetCompanyLine());
    response.setValue("sourceInfo", report.getSourceLine());
  }
}
