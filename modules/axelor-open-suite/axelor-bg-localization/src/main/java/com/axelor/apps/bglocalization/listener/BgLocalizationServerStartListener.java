package com.axelor.apps.bglocalization.listener;

import com.axelor.apps.bankpayment.db.BankStatementFileFormat;
import com.axelor.apps.bankpayment.db.repo.BankStatementFileFormatRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Localization;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.db.repo.LocalizationRepository;
import com.axelor.apps.base.db.repo.SequenceRepository;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.base.service.app.AppBaseService;
import com.axelor.apps.base.service.datainit.DataInitSyncService;
import com.axelor.apps.bglocalization.db.BgFixedAssetRealizationSettings;
import com.axelor.apps.bglocalization.service.BgCompanyReportLocale;
import com.axelor.apps.bglocalization.service.accounting.BgAutoPostingUserService;
import com.axelor.apps.bglocalization.service.app.BgAppConfigLinkService;
import com.axelor.apps.bglocalization.service.bankstatement.BgBankStatementImportService;
import com.axelor.apps.bglocalization.service.batch.BgFixedAssetRealizationSettingsService;
import com.axelor.apps.bglocalization.service.country.BgCountrySeedCheckService;
import com.axelor.apps.bglocalization.service.currency.BgExchangeRatePairs;
import com.axelor.apps.bglocalization.service.currency.BgExchangeRateSchedule;
import com.axelor.apps.bglocalization.service.napartifact.BgNapArtifactCompanyBackfill;
import com.axelor.apps.bglocalization.service.partner.BgPartnerSequenceSetupService;
import com.axelor.apps.bglocalization.service.payroll.BgTrzSequenceBackfill;
import com.axelor.apps.bglocalization.service.setup.BgLegacySeedLabels;
import com.axelor.apps.bglocalization.service.stock.BgStockCostingSetupService;
import com.axelor.apps.bglocalization.service.stock.BgStockSetupService;
import com.axelor.apps.bglocalization.service.stock.BgStockValuationSchedule;
import com.axelor.apps.bglocalization.service.vat.BgVatProtocolNumbering;
import com.axelor.apps.bglocalization.service.vat.BgVatProtocolSequence;
import com.axelor.event.Observes;
import com.axelor.events.StartupEvent;
import com.axelor.inject.Beans;
import com.axelor.studio.db.AppBase;
import com.axelor.studio.db.repo.AppBaseRepository;
import com.google.inject.persist.Transactional;
import java.lang.invoke.MethodHandles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Boot-time initialization for the Bulgarian localization module. Auto-discovered by Axelor's event
 * framework (no explicit binding needed — it scans for {@link Observes} methods).
 *
 * <p>Ensures the BG Postbank XLSX {@link BankStatementFileFormat} record exists so it shows in
 * Accounting → Bank Statements → File format. This runs on every startup (unlike data-init CSV,
 * which only imports on a fresh install), so the record also appears in already-existing databases
 * after a rebuild. The upsert is idempotent — keyed on the {@code statementFileFormatSelect} code —
 * so repeated startups never create duplicates.
 *
 * <p><b>Data-init synchronization is no longer done here.</b> This module's three data-init configs
 * — the {@code apps/init-data} payroll/HR reference-data CSVs, the stock {@code data-init} CSVs
 * (units of measure, unit conversions, the localization object-permission grant) and the {@code
 * LeaveReason} {@code <xml-inputs>} seed — are declared in {@code ModuleResourceSyncRegistry} and
 * re-imported by the central {@code ModuleResourceSyncService} on startup, still gated by {@code
 * datainit.sync.modules} and running through {@link DataInitSyncService}.
 *
 * <p>This listener only fills {@code AppBase.defaultPartnerLocalization} with the Bulgarian
 * localization when it is still empty — see {@link #ensureDefaultPartnerLocalization()} for why
 * that is done here rather than from a seed CSV.
 */
public class BgLocalizationServerStartListener {

  private final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

  /**
   * Кодът на българската локализация, както го носи редът от {@code base_localization.csv}
   * (axelor-base data-init). Резолв по КОД, никога по id: числото е локално за инстанцията и на
   * друга база сочи чужда държава.
   */
  private static final String BG_LOCALIZATION_CODE = "bg_BG";

  private static final String FORMAT_NAME = "Postbank XLSX – Движения по сметка";
  private static final String FORMAT_DESCRIPTION =
      "Български формат за импорт на банкови извлечения Postbank/Юробанк (.xlsx – Движения по "
          + "сметка). Обработва се от BgBankStatementImportServiceImpl (override на стандартния "
          + "импорт на банкови извлечения).";

  public void onStartup(@Observes StartupEvent event) {
    ensurePostbankFileFormat();
    ensureVatProtocolSequences();
    ensureDefaultPartnerLocalization();
    ensureCompanyReportLocale();
    ensureFixedAssetRealizationSettingsAndSchedule();
    ensureStockSetup();
    ensureAppConfigLink();
    ensureNapArtifactCompanies();
    ensurePartnerSequences();
    ensureTrzSequences();
    ensureLegacySeedLabels();
    checkCountrySeed();
  }

  /**
   * Английските и сгрешените текстове от началните данни в заварените записи — виж {@link
   * BgLegacySeedLabels}. Сменя само точно стария текст; повторният старт не прави нищо. Провал тук
   * се записва и стартирането продължава.
   */
  public void ensureLegacySeedLabels() {
    try {
      log.info(Beans.get(BgLegacySeedLabels.class).run());
    } catch (Exception e) {
      log.error("Legacy seed labels: поправката на заварените текстове при старта не мина.", e);
    }
  }

  /**
   * България в номенклатурата на държавите — виж {@link BgCountrySeedCheckService}. Само чете (и
   * пише известие до администраторите при нарушение); не спира стартирането. Последна, за да види
   * базата след всички стъпки отгоре и след DataInitSync (той е на {@code @Priority(-100)}).
   */
  public void checkCountrySeed() {
    Beans.get(BgCountrySeedCheckService.class).run();
  }

  /**
   * Поредицата {@code partner} на всяко дружество — виж {@link BgPartnerSequenceSetupService}.
   * Създава само липсващото (продължава над всеки заварен номер); схемата не пипа — уникалността на
   * номера идва от файл през пайплайна за писане, а проверката е в {@code BgPartnerSchemaCheck}.
   * Пише в лога какво е направила; повторният старт не прави нищо. Провал тук се записва и
   * стартирането продължава — но тогава нов партньор в дружество без поредица получава отказ.
   */
  public void ensurePartnerSequences() {
    try {
      log.info(Beans.get(BgPartnerSequenceSetupService.class).ensureAll().message());
    } catch (Exception e) {
      log.error("Partner sequences: поредиците по дружество при старта не минаха.", e);
    }
  }

  /**
   * ТРЗ редиците на заварените дружества — продължени от най-големия им номер ({@link
   * BgTrzSequenceBackfill}). Само за дружество БЕЗ редица с този код; заварената не се пипа, тоест
   * повторният старт не прави нищо. Новите дружества ги получават при раждането си ({@code
   * BgTrzCompanySequenceListener}). Пише в лога какво е създало. Провал тук се записва и
   * стартирането продължава: фишът на такова дружество се отказва поименно, не се номерира от
   * общото.
   */
  public void ensureTrzSequences() {
    try {
      log.info(Beans.get(BgTrzSequenceBackfill.class).run().message());
    } catch (Exception e) {
      log.error("ТРЗ редици по дружество: стъпката при старта не мина.", e);
    }
  }

  /**
   * Празното дружество на заварените файлове за НАП/НОИ — виж {@link BgNapArtifactCompanyBackfill}.
   * Каталогът ги чете по {@code company}; без тази стъпка ги вижда само admin. Пипа само празното и
   * само еднозначното, тоест повторният старт не прави нищо. Пише в лога колко е попълнила и колко
   * остават празни. Не е ядрото на стартирането: провал тук се записва и стартирането продължава.
   */
  public void ensureNapArtifactCompanies() {
    try {
      log.info(Beans.get(BgNapArtifactCompanyBackfill.class).run().message());
    } catch (Exception e) {
      log.error("NapArtifact.company: попълването на празното при старта не мина.", e);
    }
  }

  /**
   * Записът на настройките на заварена база — свързва се с приложението, ако {@code app} е празно,
   * за да го намира „Конфигуриране“ на картата. Тясно и идемпотентно — виж {@link
   * BgAppConfigLinkService}. Всичко, освен „вече свързан“, се записва като WARNING. Не е ядрото на
   * стартирането: провал тук се записва и стартирането продължава.
   */
  public void ensureAppConfigLink() {
    try {
      BgAppConfigLinkService.Result result = Beans.get(BgAppConfigLinkService.class).ensureLinked();
      if (result.message != null) {
        log.warn(result.message);
      }
    } catch (Exception e) {
      log.error("AppBgLocalization: свързването с приложението не мина.", e);
    }
  }

  /**
   * БГ настройката на склада за заварените бази — виж {@link BgStockSetupService}. Попълва само
   * празното: видът на запаса на БГ семействата (по код), складовата сметка 30x на редовете им в
   * AccountManagement и шаблонът на склада на всяко дружество (поредици, StockConfig, локации).
   */
  public void ensureStockSetup() {
    BgStockSetupService setup = Beans.get(BgStockSetupService.class);
    setup.ensureFamilyInventoryTypes();
    setup.ensureFamilyStockAccounts();
    setup.ensureTemplates();
    // ИЗП: журналът „Изписване на МЗ“ и AccountConfig.bgStockIssueJournal на заварените дружества.
    Beans.get(BgStockCostingSetupService.class).ensureIssueJournals();
    // ИЗП: 120 с виновно лице е 442 — заварените редове точно с 498 се местят.
    Beans.get(BgStockCostingSetupService.class).ensureShortagePartnerAccounts();
    // Служебният потребител за автоматично осчетоводяване и празната настройка на
    // дружествата (попълнената не се пипа).
    Beans.get(BgAutoPostingUserService.class).ensureServiceUser();
    Beans.get(BgAutoPostingUserService.class).ensureCompanyPostingUsers();
    // ИЗП: графикът на оценката (на всяка минута). Не е ядрото на стартирането — провал тук се
    // записва и стартирането продължава.
    try {
      Beans.get(BgStockValuationSchedule.class).ensure();
    } catch (Exception e) {
      log.error(
          "Stock valuation: could not create the {} schedule.",
          BgStockValuationSchedule.SCHEDULE_NAME,
          e);
    }
    // Валутните курсове на ЕЦБ, всеки работен ден. Същото — провалът не спира стартирането.
    try {
      Beans.get(BgExchangeRateSchedule.class).ensure();
    } catch (Exception e) {
      log.error(
          "Exchange rates: could not create the {} schedule.",
          BgExchangeRateSchedule.SCHEDULE_NAME,
          e);
    }
    // Двойките от nyxa.exchange-rates.pairs и опресняването на остарелите курсове — във фонова
    // нишка, за да не чака стартирането ЕЦБ.
    try {
      Beans.get(BgExchangeRatePairs.class).runInBackground();
    } catch (Exception e) {
      log.error("Exchange rates: could not start the startup update.", e);
    }
  }

  @Transactional(rollbackOn = Exception.class)
  public void ensurePostbankFileFormat() {
    BankStatementFileFormatRepository repo = Beans.get(BankStatementFileFormatRepository.class);
    BankStatementFileFormat existing =
        repo.all()
            .filter("self.statementFileFormatSelect = :code")
            .bind("code", BgBankStatementImportService.FORMAT_SELECT)
            .fetchOne();
    if (existing != null) {
      return; // already present — idempotent, no duplicate
    }
    BankStatementFileFormat format = new BankStatementFileFormat();
    format.setStatementFileFormatSelect(BgBankStatementImportService.FORMAT_SELECT);
    format.setName(FORMAT_NAME);
    format.setDescription(FORMAT_DESCRIPTION);
    repo.save(format);
    log.info(
        "Created BG Postbank XLSX bank statement file format ({}).",
        BgBankStatementImportService.FORMAT_SELECT);
  }

  /**
   * Поредицата на ДДС протоколите ({@code bgVatProtocol}) за всяко дружество, което я няма.
   *
   * <p>Новите дружества я получават от шаблона BG_COA при прилагането му. Заварените — вече
   * настроени преди реда в шаблона — само оттук. Номерът на протокола се дава от нея при
   * осчетоводяване ({@code BgMoveRepository}); без нея протоколът остава без номер и дневникът дава
   * ГРЕШКА.
   *
   * <p>⚠️ САМО АКО ЛИПСВА — като {@link #ensurePostbankFileFormat()}. Съществуваща поредица с този
   * код не се пипа: нито номерът, нито версията, нито форматът. Дружество, което вече е издало
   * протоколи с номера, нагласява следващия номер РЪЧНО — оттук не се знае докъде е стигнало.
   *
   * <p>Създадената поредица носи importId-а на шаблона ({@link BgVatProtocolSequence#importIdFor}):
   * BG_COA, приложен по-късно върху същото дружество, я обновява, вместо да създаде втора.
   */
  @Transactional(rollbackOn = Exception.class)
  public void ensureVatProtocolSequences() {
    SequenceService sequenceService = Beans.get(SequenceService.class);
    SequenceRepository sequenceRepo = Beans.get(SequenceRepository.class);
    for (Company company : Beans.get(CompanyRepository.class).all().fetch()) {
      if (sequenceService.getSequence(BgVatProtocolNumbering.SEQUENCE_CODE, company) != null) {
        continue;
      }
      sequenceRepo.save(BgVatProtocolSequence.newFor(company));
      log.info(
          "Created the VAT protocol sequence ({}) for company {} — set its next number by hand if"
              + " the company has already issued protocols.",
          BgVatProtocolNumbering.SEQUENCE_CODE,
          company.getCode());
    }
  }

  /**
   * Задава „Български (България)“ като локализация по подразбиране на нов партньор — но само ако
   * настройката още е празна.
   *
   * <p><b>Защо изобщо.</b> {@code action-partner-record-set-default} копира {@code
   * AppBase.defaultPartnerLocalization} в новия партньор на onNew (axelor-base/views/Partner.xml),
   * а onNew форкът превръща българската локализация в „ЕИК/Булстат“, скрити siren и nic и видим
   * идент. № по ДДС — още при отваряне на формата, без запис. Без стойността всеки нов доставчик
   * тръгва генеричен, докато потребителят не сложи локализацията на ръка.
   *
   * <p>⚠️ <b>ЗАЩО НЕ SEED.</b> {@code studio_appBase} е ЕДИН споделен конфигурационен ред за цялото
   * приложение. CSV входът е upsert: не намери ли реда, {@code CSVBinder#find} прави {@code
   * newInstance()} и се ражда ВТОРИ AppBase — а {@code AppBaseService#getAppBase()} е {@code
   * Query.of(AppBase.class).fetchOne()}, тоест оттам нататък коя настройка важи зависи от реда на
   * връщане. Тук не се създава нищо: липсва ли редът, не се прави нищо.
   *
   * <p>⚠️ <b>САМО ПРИ ПРАЗНА СТОЙНОСТ.</b> Листенерът се пуска на ВСЕКИ boot. Безусловното задаване
   * щеше да връща избора на администратора обратно при всеки деплой — мълчаливо, защото екранът
   * просто показва пак България. Запълва се празнота, не се налага политика.
   *
   * <p>⚠️ Липсващият ред за {@code bg_BG} НЕ Е грешка — той идва от кореновия data-init на
   * axelor-base, който не минава при всяко разгръщане. Тогава само се пише предупреждение, за да не
   * е тихо.
   */
  @Transactional(rollbackOn = Exception.class)
  public void ensureDefaultPartnerLocalization() {
    AppBase appBase = Beans.get(AppBaseService.class).getAppBase();
    Localization bulgarian =
        Beans.get(LocalizationRepository.class)
            .all()
            .filter("self.code = :code")
            .bind("code", BG_LOCALIZATION_CODE)
            .fetchOne();

    if (appBase != null && appBase.getDefaultPartnerLocalization() == null && bulgarian == null) {
      log.warn(
          "Default partner localization is empty and no '{}' localization row exists; a new partner"
              + " will start without one. Run the axelor-base data-init to create it.",
          BG_LOCALIZATION_CODE);
    }
    if (!applyDefaultPartnerLocalization(appBase, bulgarian)) {
      return;
    }
    Beans.get(AppBaseRepository.class).save(appBase);
    log.info("Set default partner localization to '{}'.", BG_LOCALIZATION_CODE);
  }

  /**
   * Езикът и часовата зона на справките на заварените дружества — виж {@link
   * BgCompanyReportLocale}. Пише в данни при старта, но само празните полета; повторният старт не
   * променя нищо. При всеки старт пише в лога колко дружества е попълнило (и 0). Новите дружества
   * ги получават при записа си ({@code BgCompanyManagementRepository}). Провал тук се записва и
   * стартирането продължава — справките тогава остават с „1/1/2026“ и UTC час.
   */
  public void ensureCompanyReportLocale() {
    try {
      BgCompanyReportLocale.Summary summary = fillCompanyReportLocale();
      if (summary.localizationsLeftEmpty > 0) {
        log.warn(summary.message());
      } else {
        log.info(summary.message());
      }
    } catch (Exception e) {
      log.error("Company report locale: попълването при старта не мина.", e);
    }
  }

  @Transactional(rollbackOn = Exception.class)
  protected BgCompanyReportLocale.Summary fillCompanyReportLocale() {
    Localization bulgarian =
        Beans.get(LocalizationRepository.class)
            .all()
            .filter("self.code = :code")
            .bind("code", BgCompanyReportLocale.LOCALIZATION_CODE)
            .fetchOne();
    // Без repository.save: дружествата са управлявани в транзакцията и промяната се записва при
    // commit. BgCompanyManagementRepository.save отказва заварени дружества по други правила
    // (декларатори, склад) — тук те не са въпросът и не бива да спират попълването на останалите.
    // Дружество без празнота не се променя (Hibernate не пише непроменен запис, версията остава).
    return BgCompanyReportLocale.fillAll(
        Beans.get(CompanyRepository.class).all().fetch(), bulgarian);
  }

  /**
   * Самото решение, отделено от базата, за да има свидетел без Guice.
   *
   * @return {@code true} само когато стойността наистина е сложена сега
   */
  boolean applyDefaultPartnerLocalization(AppBase appBase, Localization bulgarian) {
    if (appBase == null || bulgarian == null) {
      return false;
    }
    if (appBase.getDefaultPartnerLocalization() != null) {
      return false;
    }
    appBase.setDefaultPartnerLocalization(bulgarian);
    return true;
  }

  /**
   * Ensures the "Depreciation Automation" settings row exists (disabled, no companies, on first
   * boot) and syncs the derived {@code bg-fixed-asset-realization-monthly} MetaSchedule row — every
   * startup, not just the first, so an already-existing database (or one where the MetaSchedule row
   * was edited directly through generic Job Management) is brought back in line with the settings
   * entity, the sole source of truth for that schedule. See {@link
   * BgFixedAssetRealizationSettingsService}.
   *
   * <p>This schedule is optional automation, not core startup: any failure synchronizing or
   * persisting it (e.g. a future regression reintroducing an oversized column value) is logged in
   * full and swallowed here so it can never take down the rest of the ERP's startup. This does not
   * apply to the interactive settings save ({@code BgFixedAssetRealizationSettingsController}),
   * which still lets such failures reach the admin normally.
   */
  public void ensureFixedAssetRealizationSettingsAndSchedule() {
    ensureFixedAssetRealizationSettingsAndSchedule(
        Beans.get(BgFixedAssetRealizationSettingsService.class));
  }

  // Package-private overload so the sync logic is testable with a fake service, without needing
  // to statically mock Beans.get(...) (no precedent for that in this codebase).
  void ensureFixedAssetRealizationSettingsAndSchedule(
      BgFixedAssetRealizationSettingsService settingsService) {
    try {
      BgFixedAssetRealizationSettings settings = settingsService.findOrCreate();
      settingsService.syncSchedule(settings);
    } catch (Exception e) {
      log.error(
          "Fixed asset realization: could not sync the '{}' schedule on startup; this optional "
              + "automation is skipped for now and startup continues.",
          BgFixedAssetRealizationSettingsService.SCHEDULE_NAME,
          e);
    }
  }
}
