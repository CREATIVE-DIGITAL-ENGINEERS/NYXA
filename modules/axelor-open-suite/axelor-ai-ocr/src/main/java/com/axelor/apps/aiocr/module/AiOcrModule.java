package com.axelor.apps.aiocr.module;

import com.axelor.app.AxelorModule;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadAuditManagementRepository;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadAuditRepository;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadManagementRepository;
import com.axelor.apps.aiocr.db.repo.AiOcrUploadRepository;
import com.axelor.apps.aiocr.db.repo.OcrAddressPostcodeRepository;
import com.axelor.apps.aiocr.db.repo.OcrInvoiceRepository;
import com.axelor.apps.aiocr.db.repo.OcrInvoiceRequestGuard;
import com.axelor.apps.aiocr.db.repo.OcrMoveRepository;
import com.axelor.apps.aiocr.listener.AiOcrServerStartListener;
import com.axelor.apps.aiocr.service.bank.OcrBankDetailsFullNameComputeServiceImpl;
import com.axelor.apps.aiocr.service.bank.OcrBankServiceImpl;
import com.axelor.apps.aiocr.service.invoice.OcrInvoiceServiceImpl;
import com.axelor.apps.aiocr.service.storage.OcrMetaFiles;
import com.axelor.apps.base.db.repo.AddressBaseRepository;
import com.axelor.apps.base.service.BankDetailsFullNameComputeServiceImpl;
import com.axelor.apps.base.service.BankServiceImpl;
import com.axelor.apps.budget.db.repo.BudgetInvoiceRepository;
import com.axelor.apps.budget.db.repo.MoveBudgetManagementRepository;
import com.axelor.apps.businessproject.service.InvoiceServiceProjectImpl;
import com.axelor.meta.MetaFiles;

/**
 * Guice bindings for the AI-OCR module. Auto-discovered at boot by {@code AppModule} (MetaScanner
 * finds {@link AxelorModule} subtypes per module).
 *
 * <p>Binds the startup/shutdown listener — Axelor fires {@code StartupEvent}/{@code ShutdownEvent}
 * observers only for **explicitly bound** listeners (cf. {@code BaseServerStartListener}).
 */
public class AiOcrModule extends AxelorModule {

  @Override
  protected void configure() {
    bind(AiOcrServerStartListener.class);
    // Protect ZIP carrier (archive) records from accidental deletion.
    bind(AiOcrUploadRepository.class).to(AiOcrUploadManagementRepository.class);
    // Write guard for the permanent Upload Audit trail: AiOcrUploadAuditService is the only
    // sanctioned writer (see AiOcrUploadAuditManagementRepository).
    bind(AiOcrUploadAuditRepository.class).to(AiOcrUploadAuditManagementRepository.class);
    // Extend the tail of the Invoice repo chain so deleting a canceled supplier invoice also wipes
    // its OCR uploads/files (clears the AiOcrUpload.invoice FK). Nothing else binds the tail key,
    // so
    // the whole chain still resolves through this class.
    bind(BudgetInvoiceRepository.class).to(OcrInvoiceRepository.class);
    // Refuse mass-update of the protected OCR document-total / correction / audit columns: the JPQL
    // UPDATE behind Resource.updateMass loads no rows, so the Hibernate PreUpdate listener
    // (OcrInvoiceImmutabilityListener) cannot see it. @Observes methods only fire on bound classes.
    bind(OcrInvoiceRequestGuard.class);
    // Same for the Move chain tail: deleting an OCR-produced Move from the Moves module unlinks the
    // AiOcrUpload.move references first (keeping the upload + source file), preserving stock
    // posted-move protections in super.remove.
    bind(MoveBudgetManagementRepository.class).to(OcrMoveRepository.class);
    // Reference protection for physical file deletion / in-place overwrite. Once an upload and the
    // Invoice/Move it produced share ONE MetaFile instead of two identical copies, the platform's
    // own counting (DMSFile + MetaAttachment only) would delete the shared blob when the invoice's
    // attachment goes away. MetaFiles is a plain unbound class that nobody instantiates directly,
    // so this is the single funnel in front of every physical delete and byte overwrite.
    bind(MetaFiles.class).to(OcrMetaFiles.class);
    // Extend the tail of the Address repo chain (nothing else binds AddressBaseRepository) so every
    // Address save — UI, REST/API, imports — is checked against the BG exact-postcode dataset when
    // the selected City has one (see OcrAddressPostcodeRepository).
    bind(AddressBaseRepository.class).to(OcrAddressPostcodeRepository.class);
    // Extend the TAIL of the InvoiceService chain so the supplier-invoice total is reconciled
    // against the document total before validation, and ventilation is refused when it cannot be.
    // Every caller — the form buttons, the AI tools, batches — goes through this one service, so
    // there is a single server-side gate and no tool contract changes.
    //
    // ⚠️ THE TAIL IS InvoiceServiceProjectImpl, NOT InvoiceServiceSupplychainImpl. The full chain
    // is InvoiceService -> InvoiceServiceImpl (account) -> InvoiceServiceSupplychainImpl
    // (supplychain) -> InvoiceServiceProjectImpl (BusinessProjectModule). Binding the supplychain
    // key would be a SECOND binding of a key BusinessProjectModule already binds — Guice refuses
    // duplicate non-identical bindings across modules — and it would also bypass the project
    // layer's cancel() override.
    bind(InvoiceServiceProjectImpl.class).to(OcrInvoiceServiceImpl.class);
    // A bank created from a bare BIC has no name, and stock code then names it "BNBGBGSD - null" —
    // a label every bank account at that bank inherits on the invoice. BaseModule binds the
    // interfaces to these two classes and nothing else binds the classes, so these are the tails.
    bind(BankServiceImpl.class).to(OcrBankServiceImpl.class);
    bind(BankDetailsFullNameComputeServiceImpl.class)
        .to(OcrBankDetailsFullNameComputeServiceImpl.class);
    // NB: OcrBankStatementCreator is intentionally NOT bound here. axelor-bg-localization binds the
    // real implementation (it owns the BG Bank Statement fields + bank-import logic); binding a
    // default here too would be a duplicate Guice binding. When no backend is bound (ai-ocr running
    // without bg-localization), the intake looks it up defensively and marks the upload for review
    // rather than booking a Move.
  }
}
