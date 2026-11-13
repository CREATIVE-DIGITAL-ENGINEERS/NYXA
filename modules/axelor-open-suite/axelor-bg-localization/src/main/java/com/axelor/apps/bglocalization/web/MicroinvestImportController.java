package com.axelor.apps.bglocalization.web;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.exception.TraceBackService;
import com.axelor.apps.bglocalization.db.MicroinvestImportWizard;
import com.axelor.apps.bglocalization.service.migration.microinvest.MicroinvestCompanyGuard;
import com.axelor.apps.bglocalization.service.migration.microinvest.MicroinvestImportReport;
import com.axelor.apps.bglocalization.service.migration.microinvest.MicroinvestPilotImportService;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.meta.db.MetaFile;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.google.inject.Singleton;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Microinvest -&gt; Nyxa migration, Phase 1 pilot: two buttons on a transient wizard, same pattern
 * as {@link BgPayrollLedgerController} -- works on the form's context, never calls save() on the
 * wizard itself. The uploaded export file follows the same upload pattern as {@code
 * ImportConfiguration.dataMetaFile} (a {@code MetaFile} reference, read via {@link MetaFiles}).
 *
 * <p>Preflight and Import run the exact same logic in {@link MicroinvestPilotImportService}; the
 * only difference is whether it actually writes. Run Preflight first and read the report before
 * running Import.
 */
@Singleton
public class MicroinvestImportController {

  /**
   * onNew/onLoad: names the company the run will write into, so the operator sees it BEFORE
   * uploading anything -- otherwise nothing on the form says where the data is going. The importer
   * refuses to create a company at all; this is the matching visible half of that promise.
   */
  public void showActiveCompany(ActionRequest request, ActionResponse response) {
    Company company = MicroinvestPilotImportService.activeCompany();
    if (company == null) {
      response.setValue(
          "activeCompanyInfo",
          "⚠️ НЯМА активна компания. Изберете активна компания (Preferences -> Active company)"
              + " преди Preflight/Import. Импортът НЕ създава компания.");
      return;
    }
    response.setValue(
        "activeCompanyInfo",
        MicroinvestCompanyGuard.targetBanner(
            company.getName(),
            company.getPartner() == null ? null : company.getPartner().getRegistrationCode()));
  }

  public void preflight(ActionRequest request, ActionResponse response) {
    MicroinvestImportWizard form = request.getContext().asType(MicroinvestImportWizard.class);
    CapabilityGate.requireRole("import payroll from Microinvest", Capabilities.PAYROLL_WRITE);
    CapabilityGate.requireOwnFile(form.getSourceFile());
    try {
      byte[] content = readUploadedFile(form.getSourceFile());
      MicroinvestImportReport report =
          Beans.get(MicroinvestPilotImportService.class).preflight(content);
      applyReport(response, report, "Preflight");
    } catch (Exception e) {
      response.setValue("reportText", null);
      TraceBackService.trace(response, e);
    }
  }

  public void runImport(ActionRequest request, ActionResponse response) {
    MicroinvestImportWizard form = request.getContext().asType(MicroinvestImportWizard.class);
    CapabilityGate.requireRole("import payroll from Microinvest", Capabilities.PAYROLL_WRITE);
    CapabilityGate.requireOwnFile(form.getSourceFile());
    try {
      byte[] content = readUploadedFile(form.getSourceFile());
      MicroinvestImportReport report =
          Beans.get(MicroinvestPilotImportService.class).runImport(content);
      applyReport(response, report, "Import");
    } catch (Exception e) {
      response.setValue("reportText", null);
      TraceBackService.trace(response, e);
    }
  }

  private byte[] readUploadedFile(MetaFile metaFile) throws Exception {
    if (metaFile == null) {
      throw new IllegalArgumentException("No export file uploaded.");
    }
    String fileName = metaFile.getFileName();
    if (fileName == null || !fileName.toLowerCase().endsWith(".json")) {
      throw new IllegalArgumentException(
          "Expected a .json export file (from the Microinvest export tool), got: " + fileName);
    }
    Path path = Beans.get(MetaFiles.class).getPath(metaFile);
    return Files.readAllBytes(path);
  }

  private void applyReport(
      ActionResponse response, MicroinvestImportReport report, String heading) {
    response.setValue("reportText", report.toText(heading));
    response.setValue("employeesFoundCount", report.employeesFound);
    response.setValue("createdCount", report.created);
    response.setValue("updatedCount", report.reused);
    response.setValue("skippedCount", report.skipped);
    response.setValue("failedCount", report.failed);
    response.setValue("blockedCount", report.blocked);
    response.setValue("sourceRecordCount", report.sourceRecords);
    response.setValue("activeCompanyInfo", report.getTargetCompanyLine());
  }
}
