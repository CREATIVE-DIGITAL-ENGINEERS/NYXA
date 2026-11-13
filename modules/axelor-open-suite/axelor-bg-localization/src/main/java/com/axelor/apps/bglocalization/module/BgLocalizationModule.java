package com.axelor.apps.bglocalization.module;

import com.axelor.app.AxelorModule;
import com.axelor.apps.account.db.repo.FixedAssetManagementRepository;
import com.axelor.apps.account.service.batch.BatchRealizeFixedAssetLine;
import com.axelor.apps.account.service.fixedasset.FixedAssetDateServiceImpl;
import com.axelor.apps.account.service.fixedasset.FixedAssetDisposalServiceImpl;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineFiscalComputationServiceImpl;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineFiscalServiceImpl;
import com.axelor.apps.account.service.fixedasset.FixedAssetServiceImpl;
import com.axelor.apps.account.service.invoice.workflow.ventilate.VentilateAdvancePaymentRefundState;
import com.axelor.apps.account.service.invoice.workflow.ventilate.VentilateAdvancePaymentState;
import com.axelor.apps.account.service.invoice.workflow.ventilate.VentilateState;
import com.axelor.apps.account.service.move.MoveDueService;
import com.axelor.apps.account.service.move.MoveExcessPaymentService;
import com.axelor.apps.aiocr.db.repo.OcrInvoiceRepository;
import com.axelor.apps.aiocr.db.repo.OcrMoveRepository;
import com.axelor.apps.aiocr.service.bankstatement.OcrBankStatementCreator;
import com.axelor.apps.aiocr.service.record.OcrRoundingLineCreditType;
import com.axelor.apps.bankpayment.db.repo.BankStatementManagementRepository;
import com.axelor.apps.bankpayment.service.bankstatement.BankStatementImportService;
import com.axelor.apps.base.db.repo.CompanyRepository;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.bglocalization.db.repo.BgAssetMaterialityIncreaseManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgAssetMaterialityIncreaseRepository;
import com.axelor.apps.bglocalization.db.repo.BgBankStatementManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgCompanyManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgCompensationManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgCompensationRepository;
import com.axelor.apps.bglocalization.db.repo.BgContractTerminationManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgContractTerminationRepository;
import com.axelor.apps.bglocalization.db.repo.BgDeclarantManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgDeclarantRepository;
import com.axelor.apps.bglocalization.db.repo.BgEmploymentContractManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgEndOfContractReasonManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetWriteOffManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetWriteOffRepository;
import com.axelor.apps.bglocalization.db.repo.BgInvoiceRepository;
import com.axelor.apps.bglocalization.db.repo.BgMoveRepository;
import com.axelor.apps.bglocalization.db.repo.BgPartnerRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipRepository;
import com.axelor.apps.bglocalization.db.repo.BgSelfInsuredRegistrationManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgSelfInsuredRegistrationRepository;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgStockCostingPolicyRepository;
import com.axelor.apps.bglocalization.db.repo.BgTerminationCompensationManagementRepository;
import com.axelor.apps.bglocalization.db.repo.BgTerminationCompensationRepository;
import com.axelor.apps.bglocalization.db.repo.BgUserRepository;
import com.axelor.apps.bglocalization.listener.BgLocalizationServerStartListener;
import com.axelor.apps.bglocalization.service.accounting.BgMoveValidatedByGuard;
import com.axelor.apps.bglocalization.service.bankstatement.BgBankStatementImportServiceImpl;
import com.axelor.apps.bglocalization.service.bankstatement.OcrBankStatementCreatorImpl;
import com.axelor.apps.bglocalization.service.batch.BgBatchRealizeFixedAssetLine;
import com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetDateServiceImpl;
import com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetDisposalServiceImpl;
import com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetLineTaxComputationServiceImpl;
import com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetLineTaxServiceImpl;
import com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetRequestGuard;
import com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetServiceImpl;
import com.axelor.apps.bglocalization.service.geocode.NominatimHttpClient;
import com.axelor.apps.bglocalization.service.geocode.NominatimHttpClientImpl;
import com.axelor.apps.bglocalization.service.ocr.BgOcrRoundingLineCreditType;
import com.axelor.apps.bglocalization.service.partner.BgPartnerRequestGuard;
import com.axelor.apps.bglocalization.service.partner.BgSequenceService;
import com.axelor.apps.bglocalization.service.payroll.BgHistoricalLeaveGuardCancelService;
import com.axelor.apps.bglocalization.service.payroll.BgHistoricalLeaveGuardRefuseService;
import com.axelor.apps.bglocalization.service.stock.BgStockMoveGuard;
import com.axelor.apps.bglocalization.service.stock.BgStockMoveRealizedHandler;
import com.axelor.apps.bglocalization.service.stock.BgStockPeriodCloseGuard;
import com.axelor.apps.bglocalization.service.stock.BgStockValuationOutbox;
import com.axelor.apps.bglocalization.service.vat.BgMoveDueService;
import com.axelor.apps.bglocalization.service.vat.BgMoveExcessPaymentService;
import com.axelor.apps.bglocalization.service.vat.BgVatAdvanceCancelGuard;
import com.axelor.apps.bglocalization.service.vat.BgVentilateAdvancePaymentRefundState;
import com.axelor.apps.bglocalization.service.vat.BgVentilateAdvancePaymentState;
import com.axelor.apps.bglocalization.service.vat.BgVentilateState;
import com.axelor.apps.bglocalization.service.vat.submission.LocalHttpMockVatSubmissionGateway;
import com.axelor.apps.bglocalization.service.vat.submission.VatSubmissionGateway;
import com.axelor.apps.bglocalization.service.vies.BgViesClient;
import com.axelor.apps.bglocalization.service.vies.BgViesRestClient;
import com.axelor.apps.hr.db.repo.EmploymentContractHRRepository;
import com.axelor.apps.hr.db.repo.EndOfContractReasonRepository;
import com.axelor.apps.hr.db.repo.PartnerHRRepository;
import com.axelor.apps.hr.db.repo.UserHRRepository;
import com.axelor.apps.hr.service.leave.LeaveRequestCancelServiceImpl;
import com.axelor.apps.hr.service.leave.LeaveRequestRefuseServiceImpl;
import com.google.inject.multibindings.Multibinder;

/**
 * Guice bindings for the Bulgarian localization module. Auto-discovered at boot by {@code
 * AppModule} (MetaScanner finds {@link AxelorModule} subtypes per module — no properties file
 * needed).
 *
 * <p>Binds the generated {@link BgDeclarantRepository} to the management override that enforces a
 * single default declarant per company.
 */
public class BgLocalizationModule extends AxelorModule {

  @Override
  protected void configure() {
    bind(com.axelor.apps.account.service.AccountingCurrencyPolicy.class)
        .to(com.axelor.apps.bglocalization.service.accounting.BgAccountingCurrencyPolicy.class);
    bind(BgDeclarantRepository.class).to(BgDeclarantManagementRepository.class);
    // SAF-T: версията във файла срещу версията за днес при входящия номер (QA II.2.4, с. 13) —
    // nyxa-nap-submissions не познава схемите и дава точка за разширение без проверка.
    bind(com.axelor.apps.napsubmissions.services.saft.SaftPayloadVersionCheck.class)
        .to(com.axelor.apps.bglocalization.service.saft.BgSaftPayloadVersionCheck.class);
    // G6b: проверката и генерирането на месечния SAF-T файл от екрана SaftReport.
    bind(com.axelor.apps.napsubmissions.services.saft.SaftFileEngine.class)
        .to(com.axelor.apps.bglocalization.service.saft.BgSaftFileEngine.class);
    // А другата половина на същия инвариант седи на ДРУЖЕСТВОТО, защото декларантите висят на
    // него като one-to-many с CascadeType.ALL: при запис на формата Дружество Hibernate записва
    // децата по cascade и репозиторията на декларанта НЕ се вика изобщо. Виж
    // BgCompanyManagementRepository — там се ОТКАЗВА, а не се гадае кой от вдигнатите е щракнатият.
    bind(CompanyRepository.class).to(BgCompanyManagementRepository.class);
    // Прекратен договор предлага обезщетенията си сам, на всеки път на записа.
    //
    // Връзва се HR версията, НЕ базовият EmploymentContractRepository: него го е вързал вече
    // HumanResourceModule и второ връзване на същия ключ гърми с BindingAlreadySet — Guice не
    // припокрива между модули. Веригата е санкционираният идиом в тази база: account връзва
    // InvoiceRepository → InvoiceManagementRepository, а supplychain връзва него →
    // InvoiceSupplychainRepository, който го наследява. Тук е същото, с едно звено повече:
    // EmploymentContractRepository → …HRRepository → …BgEmploymentContractManagementRepository.
    bind(EmploymentContractHRRepository.class).to(BgEmploymentContractManagementRepository.class);
    // ЕТЗ — прекратяване: записът за прекратяване носи ЕДНАТА дата и я пренася в договора;
    // след подаден ЕТЗ нито датата, нито основанието в речника се сменят (корекция). HR не
    // връзва EndOfContractReasonRepository, затова тук се връзва базовият ключ.
    bind(BgContractTerminationRepository.class).to(BgContractTerminationManagementRepository.class);
    bind(EndOfContractReasonRepository.class).to(BgEndOfContractReasonManagementRepository.class);
    // Условното правило „изключено обезщетение иска причина“ живее в save(), не в изгледа: изгледът
    // е любезност към човека пред екрана, а REST, импортът и партидата минават покрай него.
    // Обезщетението-документ си дава номер при първия запис — виж
    // BgCompensationManagementRepository.
    bind(BgCompensationRepository.class).to(BgCompensationManagementRepository.class);

    // Фишът си дава номер при първия запис — виж BgPaySlipManagementRepository.
    bind(BgPaySlipRepository.class).to(BgPaySlipManagementRepository.class);

    // Справките от вид 3000 (баланс, ОПР): ред X не е „изчислен“ само защото има редове X_701 —
    // виж BgAccountingReportValueServiceImpl. Веригата е като при репозиториите: AccountModule
    // връзва интерфейса към AOS реализацията, тук тя води към нашата.
    bind(com.axelor.apps.account.service.custom.AccountingReportValueServiceImpl.class)
        .to(
            com.axelor.apps.bglocalization.service.accounting.BgAccountingReportValueServiceImpl
                .class);
    // Справките по BG_COA за второ дружество в една база (Д16): изчислението сече сметките на
    // реда по код и само в дружеството на справката, а вносът ги свързва по дружество — виж
    // BgAccountingReportValueMoveLineServiceImpl и BgImportAccountingReportConfigLine.
    bind(com.axelor.apps.account.service.custom.AccountingReportValueMoveLineServiceImpl.class)
        .to(
            com.axelor.apps.bglocalization.service.accounting
                .BgAccountingReportValueMoveLineServiceImpl.class);
    bind(com.axelor.csv.script.ImportAccountingReportConfigLine.class)
        .to(
            com.axelor.apps.bglocalization.service.accounting.BgImportAccountingReportConfigLine
                .class);
    // ГОД към НСИ: заключената справка и редовете ѝ не се пишат отвън — виж
    // BgNsiGodReportManagementRepository.
    bind(com.axelor.apps.bglocalization.db.repo.BgNsiGodReportRepository.class)
        .to(com.axelor.apps.bglocalization.db.repo.BgNsiGodReportManagementRepository.class);
    bind(com.axelor.apps.bglocalization.db.repo.BgNsiGodLineRepository.class)
        .to(com.axelor.apps.bglocalization.db.repo.BgNsiGodLineManagementRepository.class);

    // Регистрацията на самоосигуряващото се лице си дава име за показване при всеки запис — виж
    // BgSelfInsuredRegistrationManagementRepository. Без него m2o полето на фиша показва празен ред
    // точно там, където то казва чий е фишът.
    bind(BgSelfInsuredRegistrationRepository.class)
        .to(BgSelfInsuredRegistrationManagementRepository.class);

    bind(BgTerminationCompensationRepository.class)
        .to(BgTerminationCompensationManagementRepository.class);

    // Срокът по Приложение № 9 се закача при всеки запис на листа, по който и вход да е дошъл —
    // виж BgSickLeaveManagementRepository.
    bind(BgSickLeaveRepository.class).to(BgSickLeaveManagementRepository.class);

    // Make the stock Bank Statement "Import" button also handle the BG Postbank XLSX format.
    bind(BankStatementImportService.class).to(BgBankStatementImportServiceImpl.class);
    // Route an OCR'd PDF bank statement (detected in the generic AI-OCR intake) into a reviewable
    // BankStatement instead of a Move — overrides the ai-ocr default no-op.
    bind(OcrBankStatementCreator.class).to(OcrBankStatementCreatorImpl.class);
    // Delete side of the OCR bank-statement lifecycle: deleting a generated statement keeps its
    // upload (flips it to "deleted from bank statement" → reprocessable).
    bind(BankStatementManagementRepository.class).to(BgBankStatementManagementRepository.class);
    // Опашката на веригата за фактури: всяко записване (форма, OCR, AI инструменти, импорт) минава
    // оттук, тъй че видът на документа по НАП (01 – Фактура) се попълва по ВСИЧКИ пътища, а не само
    // при създаване от AI. Самото правило си остава в BgNapInvoiceFieldContributor.
    bind(OcrInvoiceRepository.class).to(BgInvoiceRepository.class);
    // Видът данъчен кредит на автоматичния ред за закръгляване. Стои ТУК, защото полето
    // bgVatCreditType се добавя от този модул и ai-ocr (който е нагоре по веригата) изобщо не го
    // вижда в своя генериран InvoiceLine — виж OcrRoundingLineCreditType.
    bind(OcrRoundingLineCreditType.class).to(BgOcrRoundingLineCreditType.class);
    // ДДС протокол по ръчна статия получава номера си при осчетоводяване (ЗДДС чл. 117 ал. 2 т. 1).
    // Краят на веригата Account → BankPayment → Budget → Ocr.
    bind(OcrMoveRepository.class).to(BgMoveRepository.class);
    // Register the boot-time observer that upserts the BG Postbank file-format record (Axelor only
    // fires StartupEvent observers for explicitly-bound listeners — same as
    // BaseServerStartListener).
    bind(BgLocalizationServerStartListener.class);
    // ------------------------------------------------ Партньорът е на едно дружество
    //
    // Номерът на партньора идва от поредицата на НЕГОВОТО дружество. Всички пътища на AOS го искат
    // без дружество (getSequenceNumber("partner", …)), а базата тогава взема първата поредица с
    // този код. SequenceService е конкретен клас и никой не го връзва — връзването тук стига до
    // всички извикващи, без да се пипа нито един.
    bind(SequenceService.class).to(BgSequenceService.class);
    // Клиентът не избира дружеството на нов партньор и не го сменя масово — виж
    // BgPartnerRequestGuard (EventBus търси наблюдатели само сред Guice свързванията).
    bind(BgPartnerRequestGuard.class);
    // Копието на партньор не взема дружеството на оригинала — виж BgPartnerRepository. Опашката
    // на веригата PartnerRepository → Base → Account → HR (HumanResourceModule връзва HR версията).
    bind(PartnerHRRepository.class).to(BgPartnerRepository.class);
    // Нов потребител получава системен партньор без дружество — виж
    // BgUserRepository. Опашката на веригата UserRepository → Base → HR.
    bind(UserHRRepository.class).to(BgUserRepository.class);
    // ------------------------------------------------ БГ активи (ЗКПО чл. 50–59)
    //
    // Данъчният план по НОРМА (чл. 55 ал. 2), а не по брой вноски. Ключът е свободен: базата
    // инжектира FixedAssetLineFiscalComputationServiceImpl като КОНКРЕТЕН клас през
    // FixedAssetLineComputationServiceFactory и никой не го връзва — тъй че връзването тук е
    // първото и единственото. Виж BgFixedAssetLineTaxComputationServiceImpl защо трите
    // разминавания с базата са три отделни члена от закона.
    bind(FixedAssetLineFiscalComputationServiceImpl.class)
        .to(BgFixedAssetLineTaxComputationServiceImpl.class);

    // Активът си взима данъчната категория и норма от категорията при първия запис и изравнява
    // счетоводния срок с данъчния, когато сам твърди, че двата плана са равни — виж
    // BgFixedAssetManagementRepository.
    //
    // ⚠️ ТОВА НЕ Е МЯСТОТО, КОЕТО ПРАВИ ДАП-а ВЕРЕН. В потока „актив от фактура“ планът се
    // строи ПРЕДИ save(), тоест записът е закъснял. Верността идва от
    // BgTaxDepreciation.effectiveRate(), която чете нормата от актива, а при празна — от
    // категорията. Тук стойността само става видима и трайна.
    //
    // ⚠️ Не се пипа FixedAssetGenerationServiceImpl, макар copyInfos() да е по-прямото
    // място: SupplychainModule вече го е вързал към FixedAssetServiceSupplyChainImpl, а
    // axelor-supplychain НЕ е сред зависимостите на този модул (виж build.gradle) — тоест
    // удължаването на веригата иска нова зависимост към цял модул заради два setter-а.
    bind(FixedAssetManagementRepository.class).to(BgFixedAssetManagementRepository.class);

    // Отписване: данъчният план спира от НАЧАЛОТО на месеца на счетоводното отписване
    // (чл. 60 ал. 2), а не от самата дата, и месецът на отписване не носи данъчна амортизация.
    // Двете връзвания са едно правило, разделено на две места от базата: услугата за редове не
    // строи пропорционален ред за данъчния борд, а услугата за отписване реже борда един месец
    // по-рано. Ключът FixedAssetLineFiscalServiceImpl е свободен — базата го инжектира като
    // конкретен клас през FixedAssetLineServiceFactory и никой не го връзва.
    bind(FixedAssetLineFiscalServiceImpl.class).to(BgFixedAssetLineTaxServiceImpl.class);
    bind(FixedAssetDisposalServiceImpl.class).to(BgFixedAssetDisposalServiceImpl.class);
    // Статията за пропорционалния ред при извеждане — с амортизацията на реда, не с продажната
    // цена (AOS взема fixedAsset.disposalValue при isDisposal).
    bind(com.axelor.apps.account.service.fixedasset.FixedAssetLineMoveServiceImpl.class)
        .to(
            com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetLineMoveServiceImpl
                .class);
    // Статията за курсова разлика — с описание на редовете (BG_COA го изисква).
    bind(com.axelor.apps.account.service.reconcile.foreignexchange.ForeignExchangeGapServiceImpl
            .class)
        .to(
            com.axelor.apps.bglocalization.service.accounting.BgForeignExchangeGapServiceImpl
                .class);

    // Месечното начисляване догонва и ДАНЪЧНИЯ план. Базовият батч избира редове по
    // self.fixedAsset, което е пълно САМО за икономическите — данъчните се реализират единствено
    // като страничен ефект от икономически ред със същата дата. Разминат ли се двата плана,
    // ДАП-ът спира в мига, в който счетоводният се изчерпи, а чл. 60 ал. 3 т. 1 иска точно
    // обратното. Виж BgBatchRealizeFixedAssetLine.
    bind(BatchRealizeFixedAssetLine.class).to(BgBatchRealizeFixedAssetLine.class);

    // Регистърът по чл. 60/66 сам си държи isArt66Applied в съгласие с изключението и с двете
    // стойности — виж BgFixedAssetWriteOffManagementRepository.
    bind(BgFixedAssetWriteOffRepository.class).to(BgFixedAssetWriteOffManagementRepository.class);

    // SAF-T A1: отделеният при split актив сочи оригинала (bgSplitFrom) — виж
    // BgFixedAssetServiceImpl. Ключът е свободен: AccountModule връзва само интерфейса
    // FixedAssetService към FixedAssetServiceImpl, тъй че веригата минава оттук.
    bind(FixedAssetServiceImpl.class).to(BgFixedAssetServiceImpl.class);
    // SAF-T A1: масовото обновяване не пипа инвентарния номер, статуса, сметката, категорията,
    // „отделен от“ и вида — виж BgFixedAssetRequestGuard (EventBus търси наблюдатели само сред
    // Guice свързванията). Останалото пази BgFixedAssetListener на ниво Hibernate.
    bind(BgFixedAssetRequestGuard.class);

    // Пазач на вдигането на прага по чл. 60 ал. 3 т. 2 ЗКПО: записът трябва да е УВЕЛИЧЕНИЕ, инак
    // създава основание, което законът не дава — виж
    // BgAssetMaterialityIncreaseManagementRepository.
    bind(BgAssetMaterialityIncreaseRepository.class)
        .to(BgAssetMaterialityIncreaseManagementRepository.class);

    // Началото на данъчното амортизиране по чл. 58 ал. 1 (месеца на въвеждане или следващия).
    // Връзва се ИМПЛЕМЕНТАЦИЯТА, не интерфейсът: AccountModule вече е вързал
    // FixedAssetDateService → FixedAssetDateServiceImpl, тъй че веригата се удължава оттам.
    bind(FixedAssetDateServiceImpl.class).to(BgFixedAssetDateServiceImpl.class);

    // VAT submission transport: only the LOCAL MOCK gateway exists (no real NRA VAT API yet).
    bind(VatSubmissionGateway.class).to(LocalHttpMockVatSubmissionGateway.class);

    // BG address geocoding: real Nominatim client, shared as a singleton (NominatimHttpClientImpl
    // is itself @Singleton -- this binding is what makes that annotation take effect; without it
    // Guice never bound the interface at all, so BgAddressGeocodeService/BgCityCentroidService
    // failed to construct with "No implementation for NominatimHttpClient was bound").
    bind(NominatimHttpClient.class).to(NominatimHttpClientImpl.class);

    // Microinvest migration: refuse/cancel on a historical LeaveRequest would credit LeaveLine
    // days the import never debited. Binds the CONCRETE stock Impl classes (HumanResourceModule
    // already bound the interfaces to them), same chained-override idiom as the FixedAsset
    // bindings above -- see BgHistoricalLeaveGuardRefuseService/CancelService.
    bind(LeaveRequestRefuseServiceImpl.class).to(BgHistoricalLeaveGuardRefuseService.class);
    bind(LeaveRequestCancelServiceImpl.class).to(BgHistoricalLeaveGuardCancelService.class);

    // Живата проверка на чужд идент. № по ДДС в VIES. Интерфейсът е ШЕВ, а не украса: той е
    // единственото място, което пипа мрежата, и точно затова BgVatCheckService се тества със стъб,
    // вместо тестът да удря услугата на Европейската комисия. Изключва се без билд през
    // bg.vat.vies.enabled — виж BgViesRestClient.
    bind(BgViesClient.class).to(BgViesRestClient.class);

    // Склад (S0): отказ на реализирано движение и правилата при реализиране (код по „Stock
    // movement“ срещу посоката, дата на документа в отворен период). ПРИХВАЩАНЕ, а не bind на
    // наследник: веригата StockMoveServiceImpl → …SupplychainImpl → …ProductionImpl свършва в
    // axelor-production, който този модул не вижда. Прихващането хваща всеки наследник, който
    // Guice строи — виж BgStockMoveGuard.
    bindInterceptor(BgStockMoveGuard.classes(), BgStockMoveGuard.methods(), new BgStockMoveGuard());
    // Обработчиците след реализиране (ИЗП закача осчетоводяването тук). Празно множество, обявено
    // тук, за да е налично и без нито един обработчик — виж BgStockMoveRealizedHandler.
    // ИЗП: обработчикът след реализиране пише САМО задача „чака оценка“ (outbox); оценката и
    // статията са след commit, в BgStockValuationProcessor.
    Multibinder.newSetBinder(binder(), BgStockMoveRealizedHandler.class)
        .addBinding()
        .to(BgStockValuationOutbox.class);
    // „Валидирана от“ (Move.bgValidatedByUser) при всяко валидиране на статия.
    bindInterceptor(
        BgMoveValidatedByGuard.classes(),
        BgMoveValidatedByGuard.methods(),
        new BgMoveValidatedByGuard());
    // ИЗП, решение 4: периодът не се затваря, докато в него има неосчетоводени изписвания.
    bindInterceptor(
        BgStockPeriodCloseGuard.classes(),
        BgStockPeriodCloseGuard.methods(),
        new BgStockPeriodCloseGuard());
    // ИЗП: пазачът на политиката за изписване (LIFO при МСФО, смяна в средата на годината).
    bind(BgStockCostingPolicyRepository.class).to(BgStockCostingPolicyManagementRepository.class);

    // Авансовите фактури в страната (схема А): авансовата получава ДДС статия,
    // окончателната я приспада с ред, О.Д. на AOS за същия аванс не се прави, сума 0 → Дт 412 /
    // Кт 702. Състоянията и MoveExcessPaymentService нямат други наследници в приложението —
    // наследяване; анулирането — прихващане (веригата свършва в supplychain).
    bind(VentilateAdvancePaymentState.class).to(BgVentilateAdvancePaymentState.class);
    bind(VentilateAdvancePaymentRefundState.class).to(BgVentilateAdvancePaymentRefundState.class);
    bind(VentilateState.class).to(BgVentilateState.class);
    bind(MoveExcessPaymentService.class).to(BgMoveExcessPaymentService.class);
    // Покупките: при доставчик AOS приспада аванса през „дължимото“ — същото спиране.
    bind(MoveDueService.class).to(BgMoveDueService.class);
    bindInterceptor(
        BgVatAdvanceCancelGuard.classes(),
        BgVatAdvanceCancelGuard.methods(),
        new BgVatAdvanceCancelGuard());
  }
}
