package com.axelor.apps.aiocr.listener;

import com.axelor.apps.account.db.repo.InvoiceRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.service.OcrFileQuarantineService;
import com.axelor.apps.aiocr.service.ocr.BgCityImportService;
import com.axelor.apps.aiocr.service.ocr.BgCityPostcodeImportService;
import com.axelor.apps.aiocr.service.process.AiOcrProcessingExecutor;
import com.axelor.apps.aiocr.service.security.CompanyScopePermissionMigration;
import com.axelor.apps.aiocr.service.security.ObsoleteAiOcrMetadataCleanup;
import com.axelor.event.Observes;
import com.axelor.events.ShutdownEvent;
import com.axelor.events.StartupEvent;
import com.axelor.inject.Beans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boot/shutdown hooks for AI-OCR background processing. Auto-fired by Axelor only for **explicitly
 * bound** observers (see {@code AiOcrModule}).
 *
 * <p>On startup: run crash/restart recovery — reset stale {@code processing} docs to {@code
 * queued}, resubmit carriers left mid-extraction, and start the dispatcher so any outstanding
 * {@code queued} documents drain. On shutdown: stop the executor gracefully (queued DB work stays
 * recoverable next boot).
 */
public class AiOcrServerStartListener {

  private static final Logger LOG = LoggerFactory.getLogger(AiOcrServerStartListener.class);

  public void onStartup(@Observes StartupEvent event) {
    // Converge the permission model on active-company isolation. Runs before anything else here
    // because it is the control that protects every other surface, and it runs on EVERY boot rather
    // than only at install: data-init would only ever fix a fresh database. Idempotent and
    // self-reporting; it never fails the boot.
    Beans.get(CompanyScopePermissionMigration.class).migrateOnStartup();

    // Remove MetaMenu/MetaAction/MetaView/Permission rows left behind by features whose source
    // has since been removed (Monitored Folders, the OCR Storage Report, the pre-AiOcrUpload
    // review screens, Process all). Axelor's ViewLoader never deletes such rows on its own, so
    // this runs after it (ViewLoader finishes before StartupEvent fires) and after the permission
    // migration above, which unlinks the retired permissions from their role before this deletes
    // them. Idempotent and self-reporting; it never fails the boot.
    Beans.get(ObsoleteAiOcrMetadataCleanup.class).cleanupOnStartup();

    // Load the BG settlement dataset into stock City (idempotent/resumable; reconciles legacy test
    // cities on first run). Never block boot on a data problem.
    try {
      Beans.get(BgCityImportService.class).importAll();
    } catch (Exception e) {
      LOG.error("BG city import failed; will retry on next startup.", e);
    }
    // Exact postcode validation data, keyed by City.ekatte (must run after the City import above).
    try {
      Beans.get(BgCityPostcodeImportService.class).importAll();
    } catch (Exception e) {
      LOG.error("BG city postcode import failed; will retry on next startup.", e);
    }
    Beans.get(AiOcrProcessingExecutor.class).recoverOnStartup();

    // Pre-create the file quarantine directory used by the rollback-safe invoice-delete workflow.
    Beans.get(OcrFileQuarantineService.class).ensureQuarantineDir();
    // Confirm both repositories resolve through our overrides (the delete guards live there, not in
    // the UI): InvoiceRepository → OcrInvoiceRepository (chain tail), AiOcrUploadRepository →
    // AiOcrUploadManagementRepository. If either logs a different class, a binding is bypassing the
    // guards.
    LOG.info(
        "AI-OCR delete guards: InvoiceRepository -> {}, MoveRepository -> {}, AiOcrUploadRepository"
            + " -> {}",
        Beans.get(InvoiceRepository.class).getClass().getName(),
        Beans.get(MoveRepository.class).getClass().getName(),
        Beans.get(AiOcrUploadRepository.class).getClass().getName());
  }

  public void onShutdown(@Observes ShutdownEvent event) {
    Beans.get(AiOcrProcessingExecutor.class).shutdown();
  }
}
