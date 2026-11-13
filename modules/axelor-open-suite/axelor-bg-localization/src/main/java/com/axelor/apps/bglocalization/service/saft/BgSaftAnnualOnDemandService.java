package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.BankDetailsService;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAssetTransactions;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftFileKind;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFilesAnnual;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFilesOnDemand;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMovementOfGoods;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.studio.db.AppBgLocalization;
import com.axelor.studio.db.repo.AppBgLocalizationRepository;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;

/**
 * ОТ {@link SaftReport} ДО МОДЕЛА НА ГОДИШНИЯ ФАЙЛ И НА ФАЙЛА ПРИ ПОИСКВАНЕ (T1) — общата основа:
 * видът, Header-ът, името, IBAN-ът и сроковете. Съдържанието идва от пистите.
 *
 * <p><b>⚠️ ОТДЕЛНО ОТ МЕСЕЧНИЯ.</b> {@link BgSaftAuditFileService#compose} е изцяло месечен
 * (дневник, салда, фактури, плащания) и SHA-256 на месечния файл е закован ({@code
 * BgSaftMonthlyRegressionTest}). Тук се преизползват само чистите му части — {@link
 * BgSaftHeaderAssembler}, {@link BgSaftIbanCheck}, {@link BgSaftFileName} — без да се пипат.
 *
 * <p><b>⚠️ СЪДЪРЖАНИЕТО ОЩЕ ГО НЯМА — ГРЕШКА, НЕ ПРАЗЕН ФАЙЛ.</b> Празното {@code <Assets/>} и
 * нулевата структура на AssetTransactions минават схемата (QA IV.2.6), но за дружество с активи са
 * НЕВЕРЕН файл, който би стигнал до портала. Затова {@link #content} в T1 дава ГРЕШКА „идва с
 * пистата“ ({@link #ANNUAL_CONTENT_PENDING}, {@link #ON_DEMAND_CONTENT_PENDING}) и файл не излиза.
 * Пистата на активите (A3–A5) и тази на склада (S3) заменят метода със своите секции.
 *
 * <p><b>⚠️ ПЕРИОДЪТ ТРЯБВА ДА Е ИЗТЕКЪЛ.</b> Годишният файл е за ЦЯЛАТА година („няма как да бъде
 * подаден на части“) и се подава „до 30.06. на годината, следваща годината, за която се отнася
 * файлът“ (QA на НАП, версия 2, II.4.5, с. 21), тоест преди края на годината е непълен. При
 * поискване — данните до крайната дата трябва да съществуват към деня на генериране.
 */
@Singleton
public class BgSaftAnnualOnDemandService {

  public static final String ANNUAL_CONTENT_PENDING = "SAFT_ANNUAL_CONTENT_PENDING";
  public static final String ON_DEMAND_CONTENT_PENDING = "SAFT_ON_DEMAND_CONTENT_PENDING";
  public static final String YEAR_NOT_ENDED = "SAFT_ANNUAL_YEAR_NOT_ENDED";
  public static final String PERIOD_NOT_ENDED = "SAFT_ON_DEMAND_PERIOD_NOT_ENDED";

  protected final AppBgLocalizationRepository appRepo;
  protected final BankDetailsService bankDetailsService;

  @Inject
  public BgSaftAnnualOnDemandService(
      AppBgLocalizationRepository appRepo, BankDetailsService bankDetailsService) {
    this.appRepo = appRepo;
    this.bankDetailsService = bankDetailsService;
  }

  /**
   * Моделът на файла и находките — дружеството, видът и периодът идват от отчета.
   *
   * @throws IllegalArgumentException месечен отчет или отчет без годината / датите си
   */
  public BgSaftAuditFileService.Result assemble(SaftReport report, LocalDate createdOn) {
    if (report == null || report.getCompany() == null) {
      throw new IllegalArgumentException("SAF-T: отчет без дружество");
    }
    Inputs in = new Inputs();
    in.company = report.getCompany();
    in.app = appRepo.all().fetchOne();
    in.selection = BgSaftSelection.of(report);
    in.createdOn = createdOn;
    in.ibanRefusal = BgSaftIbanCheck.refusal(bankDetailsService);
    in.content = content(report, in.selection);
    return compose(in);
  }

  /**
   * Секциите на файла. T1 няма читатели — ГРЕШКА „идва с пистата“; пистите A и S заменят метода.
   */
  protected Content content(SaftReport report, BgSaftSelection selection) {
    return Content.pending(selection.getKind());
  }

  /** Всичко, което сглобяването чете — за да е {@link #compose} чиста функция. */
  public static final class Inputs {
    Company company;
    AppBgLocalization app;
    BgSaftSelection selection;
    LocalDate createdOn;
    Content content;

    /** Защо един IBAN е невалиден — {@link BgSaftIbanCheck#refusal}; {@code null} = валиден. */
    Function<String, String> ibanRefusal;
  }

  /** Секциите на годишния файл или на файла при поискване и находките по тях. */
  public static final class Content {
    private final SaftFileKind kind;
    private final SaftMasterFilesAnnual masterFilesAnnual;
    private final SaftAssetTransactions assetTransactions;
    private final SaftMasterFilesOnDemand masterFilesOnDemand;
    private final SaftMovementOfGoods movementOfGoods;
    private final List<BgDeclarationFileFinding> findings;

    private Content(
        SaftFileKind kind,
        SaftMasterFilesAnnual masterFilesAnnual,
        SaftAssetTransactions assetTransactions,
        SaftMasterFilesOnDemand masterFilesOnDemand,
        SaftMovementOfGoods movementOfGoods,
        List<BgDeclarationFileFinding> findings) {
      this.kind = kind;
      this.masterFilesAnnual = masterFilesAnnual;
      this.assetTransactions = assetTransactions;
      this.masterFilesOnDemand = masterFilesOnDemand;
      this.movementOfGoods = movementOfGoods;
      this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
    }

    /** Годишният: Assets и AssetTransactions. */
    public static Content annual(
        SaftMasterFilesAnnual masterFiles,
        SaftAssetTransactions transactions,
        List<BgDeclarationFileFinding> findings) {
      return new Content(SaftFileKind.ANNUAL, masterFiles, transactions, null, null, findings);
    }

    /** При поискване: MasterFilesOnDemand и MovementOfGoods. */
    public static Content onDemand(
        SaftMasterFilesOnDemand masterFiles,
        SaftMovementOfGoods goods,
        List<BgDeclarationFileFinding> findings) {
      return new Content(SaftFileKind.ON_DEMAND, null, null, masterFiles, goods, findings);
    }

    /** Още без читатели — ГРЕШКА, файл не излиза. */
    public static Content pending(SaftFileKind kind) {
      if (kind == SaftFileKind.ANNUAL) {
        return new Content(
            kind,
            null,
            null,
            null,
            null,
            List.of(
                BgDeclarationFileFinding.error(
                    ANNUAL_CONTENT_PENDING,
                    "Годишният SAF-T файл още не се генерира: секциите Assets и AssetTransactions"
                        + " (дълготрайните активи) идват с пистата на активите. Отчетът, годината"
                        + " и заключването при подаване вече работят.")));
      }
      if (kind == SaftFileKind.ON_DEMAND) {
        return new Content(
            kind,
            null,
            null,
            null,
            null,
            List.of(
                BgDeclarationFileFinding.error(
                    ON_DEMAND_CONTENT_PENDING,
                    "SAF-T файлът при поискване още не се генерира: наличностите и движенията на"
                        + " запасите (PhysicalStock, MovementOfGoods) идват с пистата на склада."
                        + " Отчетът, искането и заключването при подаване вече работят.")));
      }
      throw new IllegalArgumentException("SAF-T: месечният файл не е от тази услуга");
    }

    public SaftFileKind getKind() {
      return kind;
    }

    public List<BgDeclarationFileFinding> getFindings() {
      return findings;
    }
  }

  /** Сглобяването без база — от прочетеното в {@link Inputs}. */
  static BgSaftAuditFileService.Result compose(Inputs in) {
    SaftFileKind kind = in.selection.getKind();
    if (kind == SaftFileKind.MONTHLY) {
      throw new IllegalArgumentException(
          "SAF-T: месечният файл се сглобява от BgSaftAuditFileService");
    }
    if (in.content == null || in.content.getKind() != kind) {
      throw new IllegalArgumentException("SAF-T: съдържанието не е за вида на файла " + kind);
    }
    List<BgDeclarationFileFinding> findings =
        new ArrayList<>(periodEnded(in.selection, in.createdOn));

    BgSaftHeaderAssembler.Result header =
        BgSaftHeaderAssembler.assemble(in.company, in.app, in.selection, in.createdOn);
    for (BgDeclarationFileFinding finding : header.getFindings()) {
      findings.add(BgSaftAuditFileService.headerTarget(finding, in.company, in.app));
    }
    // S.CMH.6 / S.BA.1 — IBAN на подаващия е задължителен във всеки вид (Header е общ).
    for (BgDeclarationFileFinding finding :
        BgSaftIbanCheck.check(header.getHeader().getCompany(), in.ibanRefusal)) {
      findings.add(BgSaftAuditFileService.headerTarget(finding, in.company, in.app));
    }
    BgSaftFileName.Result fileName =
        BgSaftFileName.of(header.getHeader().getCompany().getRegistrationNumber(), in.selection);
    findings.addAll(fileName.getFindings());
    findings.addAll(in.content.getFindings());

    SaftAuditFile file = new SaftAuditFile().kind(kind).header(header.getHeader());
    if (kind == SaftFileKind.ANNUAL) {
      file.masterFilesAnnual(in.content.masterFilesAnnual)
          .assetTransactions(in.content.assetTransactions);
    } else {
      file.masterFilesOnDemand(in.content.masterFilesOnDemand)
          .movementOfGoods(in.content.movementOfGoods);
    }
    return BgSaftAuditFileService.Result.of(file, findings, fileName.getFileName());
  }

  /**
   * ГРЕШКА, ако периодът още не е изтекъл: годишният — преди 1 януари на следващата година; при
   * поискване — крайната дата е след деня на генериране.
   */
  static List<BgDeclarationFileFinding> periodEnded(
      BgSaftSelection selection, LocalDate createdOn) {
    List<BgDeclarationFileFinding> findings = new ArrayList<>();
    LocalDate to = selection.getToDate();
    if (selection.getKind() == SaftFileKind.ANNUAL) {
      if (createdOn == null || !createdOn.isAfter(to)) {
        findings.add(
            BgDeclarationFileFinding.error(
                YEAR_NOT_ENDED,
                "Годината "
                    + to.getYear()
                    + " още не е приключила — годишният файл е за цялата календарна година (QA"
                    + " II.4.5) и се генерира след 31 декември."));
      }
    } else if (createdOn == null || createdOn.isBefore(to)) {
      findings.add(
          BgDeclarationFileFinding.error(
              PERIOD_NOT_ENDED,
              "Крайната дата на поискания период ("
                  + to
                  + ") е след деня на генериране — данните до нея още ги няма."));
    }
    return findings;
  }
}
