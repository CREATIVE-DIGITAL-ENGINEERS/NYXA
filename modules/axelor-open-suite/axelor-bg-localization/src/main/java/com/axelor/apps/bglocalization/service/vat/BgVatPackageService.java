package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.bglocalization.db.BgVatPackage;
import com.axelor.apps.bglocalization.db.BgVatPackageEvent;
import com.axelor.apps.bglocalization.db.BgVatPackageFile;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.db.repo.BgVatPackageEventRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatPackageFileRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatPackageRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatReturnRepository;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportResultDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatGeneratedFileDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPreparedFile;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatPreparedPackage;
import com.axelor.meta.db.MetaFile;
import com.axelor.meta.db.repo.MetaFileRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.google.inject.persist.Transactional;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Persists prepared VAT/НАП packages and their append-only lifecycle log. The pure preparation
 * (hashes/sizes/record counts/readiness) is delegated to {@link BgVatPackageBuilder}; this service
 * only maps the result onto JPA entities and enforces immutability: every {@link
 * #createPackage(BgVatReturn, BgVatExportResultDto, BgVatExportRequestDto) createPackage} run makes
 * a NEW package, marks the previous one superseded, and never touches an older package's files.
 *
 * <p>Concrete {@code @Singleton} with JIT binding, matching the rest of the VAT service layer.
 */
@Singleton
public class BgVatPackageService {

  protected final BgVatPackageRepository packageRepo;
  protected final BgVatPackageFileRepository packageFileRepo;
  protected final BgVatPackageEventRepository packageEventRepo;
  protected final BgVatReturnRepository vatReturnRepo;
  protected final MetaFileRepository metaFileRepo;
  protected final BgVatDocumentCollector collector;

  @Inject
  public BgVatPackageService(
      BgVatPackageRepository packageRepo,
      BgVatPackageFileRepository packageFileRepo,
      BgVatPackageEventRepository packageEventRepo,
      BgVatReturnRepository vatReturnRepo,
      MetaFileRepository metaFileRepo,
      BgVatDocumentCollector collector) {
    this.packageRepo = packageRepo;
    this.packageFileRepo = packageFileRepo;
    this.packageEventRepo = packageEventRepo;
    this.vatReturnRepo = vatReturnRepo;
    this.metaFileRepo = metaFileRepo;
    this.collector = collector;
  }

  /**
   * Snapshot the just-generated files into a new immutable package. Supersedes any prior prepared
   * package for the same return (its files are left untouched). Returns the persisted package.
   */
  @Transactional
  public BgVatPackage createPackage(
      BgVatReturn vatReturnCtx, BgVatExportResultDto result, BgVatExportRequestDto request) {
    BgVatReturn vatReturn = vatReturnRepo.find(vatReturnCtx.getId());

    boolean hadPrior = supersedePriorPackages(vatReturn);

    BgVatPreparedPackage prepared = BgVatPackageBuilder.build(result);

    BgVatPackage pkg = new BgVatPackage();
    pkg.setBgVatReturn(vatReturn);
    pkg.setCompany(vatReturn.getCompany());
    pkg.setPeriod(vatReturn.getPeriod());
    pkg.setGeneratedOn(LocalDateTime.now());
    pkg.setStatusSelect(BgVatPackageRepository.STATUS_PREPARED);
    pkg.setReadinessSelect(prepared.getReadiness());
    pkg.setZeroDeclaration(prepared.isZeroDeclaration());
    pkg.setStale(Boolean.FALSE);
    pkg.setSourceFingerprint(computeSourceFingerprint(request));
    pkg.setDocumentsFound(prepared.getDocumentsFound());
    pkg.setErrorCount(prepared.getErrorCount());
    pkg.setWarningCount(prepared.getWarningCount());
    pkg.setTotalBase(prepared.getTotalBase());
    pkg.setTotalVat(prepared.getTotalVat());
    pkg.setTotalGross(prepared.getTotalGross());
    pkg.setK50(prepared.getK50());
    pkg.setK60(prepared.getK60());
    pkg.setSummary(prepared.getSummary());
    pkg = packageRepo.save(pkg);

    for (BgVatPreparedFile file : prepared.getFiles()) {
      BgVatPackageFile entity = new BgVatPackageFile();
      entity.setBgVatPackage(pkg);
      entity.setScope(file.getScope());
      entity.setFileName(file.getFileName());
      entity.setMetaFile(resolveMetaFile(result, file.getScope()));
      entity.setSha256(file.getSha256());
      entity.setByteSize(file.getByteSize());
      entity.setRecordCount(file.getRecordCount());
      entity.setFileStatusSelect(file.getStatus());
      entity.setValidationMessages(file.getValidationMessages());
      entity.setTextPreview(file.getTextPreview());
      entity.setFinalized(Boolean.TRUE);
      packageFileRepo.save(entity);
    }

    logEvent(
        pkg,
        vatReturn,
        hadPrior
            ? BgVatPackageEventRepository.EVENT_REGENERATED
            : BgVatPackageEventRepository.EVENT_PREPARED,
        (prepared.isZeroDeclaration() ? "Нулева декларация. " : "")
            + prepared.getFiles().size()
            + " файла, готовност: "
            + prepared.getReadiness());
    return pkg;
  }

  /** Mark every current prepared package for the return as superseded (files left intact). */
  protected boolean supersedePriorPackages(BgVatReturn vatReturn) {
    List<BgVatPackage> prior =
        packageRepo
            .all()
            .filter("self.bgVatReturn = :r AND self.statusSelect = :s")
            .bind("r", vatReturn)
            .bind("s", BgVatPackageRepository.STATUS_PREPARED)
            .fetch();
    for (BgVatPackage old : prior) {
      old.setStatusSelect(BgVatPackageRepository.STATUS_SUPERSEDED);
      packageRepo.save(old);
      logEvent(
          old, vatReturn, BgVatPackageEventRepository.EVENT_SUPERSEDED, "Заменен от нов пакет.");
    }
    return !prior.isEmpty();
  }

  private MetaFile resolveMetaFile(BgVatExportResultDto result, String scope) {
    for (BgVatGeneratedFileDto file : result.getGeneratedFiles()) {
      if (scope.equals(file.getScope()) && file.getMetaFileId() != null) {
        return metaFileRepo.find(file.getMetaFileId());
      }
    }
    return null;
  }

  /** Latest prepared (non-superseded) package for a return, or null. */
  public BgVatPackage findLatestPrepared(BgVatReturn vatReturn) {
    if (vatReturn == null || vatReturn.getId() == null) {
      return null;
    }
    return packageRepo
        .all()
        .filter("self.bgVatReturn.id = :r AND self.statusSelect = :s")
        .bind("r", vatReturn.getId())
        .bind("s", BgVatPackageRepository.STATUS_PREPARED)
        .order("-generatedOn")
        .order("-id")
        .fetchOne();
  }

  /**
   * Re-compute the source fingerprint and compare with the one stored at generation. Marks the
   * package stale when the underlying VAT data changed and logs the transition once.
   */
  @Transactional
  public boolean refreshStaleness(BgVatPackage pkgCtx, BgVatExportRequestDto request) {
    BgVatPackage pkg = packageRepo.find(pkgCtx.getId());
    if (pkg == null) {
      return false;
    }
    boolean stale = isStale(pkg, request);
    if (stale != Boolean.TRUE.equals(pkg.getStale())) {
      pkg.setStale(stale);
      packageRepo.save(pkg);
      if (stale) {
        logEvent(
            pkg,
            pkg.getBgVatReturn(),
            BgVatPackageEventRepository.EVENT_STALE_DETECTED,
            "Изходните ДДС данни са променени след генерирането — необходимо е повторно генериране.");
      }
    }
    return stale;
  }

  /**
   * Остарял ли е пакетът спрямо данните СЕГА — само чете, нищо не записва. За действия на отворена
   * декларация (onLoad, „Преглед на файловете“): запис в пакета или нов ред в логовете под
   * отворената форма прави следващия ѝ save OptimisticLockException ({@code JPA.verify} сверява
   * версиите и броя на редовете в {@code _original}).
   */
  public boolean isStale(BgVatPackage pkg, BgVatExportRequestDto request) {
    if (pkg == null || pkg.getSourceFingerprint() == null) {
      return false;
    }
    return !pkg.getSourceFingerprint().equals(computeSourceFingerprint(request));
  }

  /** Fingerprint of the collected source documents + the export scope/manual inputs. */
  public String computeSourceFingerprint(BgVatExportRequestDto request) {
    List<BgVatDocumentDto> documents = collector.collect(request);
    return BgVatSourceFingerprint.compute(documents, request);
  }

  /**
   * Записва събитие от дневника на пакета. Декларацията и пакетът се взимат от базата по id, както
   * в {@link #createPackage}: контролерите подават {@code Context.asType(BgVatReturn.class)} —
   * прокси (BgVatReturn$ByteBuddy) само с полетата на формата. Свързано направо със събитието, то
   * стигаше до persist като НОВА декларация и „Преглед на файловете“ падаше с ValidationException
   * „company must not be null“.
   */
  @Transactional
  public void logEvent(BgVatPackage pkg, BgVatReturn vatReturn, String eventType, String details) {
    BgVatPackageEvent event = new BgVatPackageEvent();
    event.setBgVatPackage(
        pkg == null || pkg.getId() == null ? null : packageRepo.find(pkg.getId()));
    event.setBgVatReturn(
        vatReturn == null || vatReturn.getId() == null
            ? null
            : vatReturnRepo.find(vatReturn.getId()));
    event.setEventTypeSelect(eventType);
    event.setDetails(details);
    packageEventRepo.save(event);
  }
}
