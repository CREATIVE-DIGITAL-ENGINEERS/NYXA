package com.axelor.apps.bglocalization.db.repo;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.FixedAssetType;
import com.axelor.apps.account.db.repo.FixedAssetManagementRepository;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.account.db.repo.FixedAssetTypeRepository;
import com.axelor.apps.account.service.app.AppAccountService;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.service.BarcodeGeneratorService;
import com.axelor.apps.bglocalization.db.BgVatCoefficient;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79Acquisition;
import com.axelor.apps.bglocalization.service.fixedasset.BgAssetSuspensionRules;
import com.axelor.apps.bglocalization.service.fixedasset.BgAssetThresholdRules;
import com.axelor.apps.bglocalization.service.fixedasset.BgFixedAssetKinds;
import com.axelor.apps.bglocalization.service.fixedasset.BgTaxDepreciation;
import com.axelor.apps.bglocalization.service.fixedasset.BgTaxDepreciationStart;
import com.axelor.studio.db.AppBgLocalization;
import com.axelor.studio.db.repo.AppBgLocalizationRepository;
import com.google.inject.Inject;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

/**
 * Записва българските данъчни полета на актива от категорията му, фактите за ДДС от покупната му
 * фактура, и пази инварианта на „Is Equal To Fiscal Depreciation“.
 *
 * <p><b>Защо в repository, а не в изгледа.</b> Изгледът е учтивост към човека пред екрана, а активи
 * се създават и през фактура, през статия, през REST, през импорт и през партида. Същият довод, по
 * който в този модул стоят {@code BgPaySlipManagementRepository} и {@code
 * BgCompensationManagementRepository}.
 *
 * <p><b>И защо това НЕ е мястото, което прави ДАП-а верен.</b> В потока „актив от фактура“ {@code
 * FixedAssetGenerationServiceImpl} строи плана ПРЕДИ {@code fixedAssetRepo.save(...)}, тоест тук е
 * вече късно. Верността идва от друго място: {@link BgTaxDepreciation#effectiveRate(FixedAsset)}
 * чете нормата от актива, а при празна — от КАТЕГОРИЯТА, тъй че планът е верен и без нито един
 * запис. Тук се прави само стойността видима и трайна, за да не се налага всяка справка да я
 * извежда пак.
 */
public class BgFixedAssetManagementRepository extends FixedAssetManagementRepository {

  protected final AppBgLocalizationRepository appBgLocalizationRepository;
  protected final BgVatCoefficientRepository bgVatCoefficientRepository;

  @Inject
  public BgFixedAssetManagementRepository(
      AppAccountService appAcccountService,
      BarcodeGeneratorService barcodeGeneratorService,
      AppBgLocalizationRepository appBgLocalizationRepository,
      BgVatCoefficientRepository bgVatCoefficientRepository) {
    super(appAcccountService, barcodeGeneratorService);
    this.appBgLocalizationRepository = appBgLocalizationRepository;
    this.bgVatCoefficientRepository = bgVatCoefficientRepository;
  }

  @Override
  public FixedAsset save(FixedAsset fixedAsset) {
    if (fixedAsset != null) {
      carryTaxFieldsFromCategory(fixedAsset);
      carryComputationMethodFromCategory(fixedAsset);
      carryVatFactsFromInvoiceLine(fixedAsset);
      applyTaxThresholdGate(fixedAsset);
      computeArt59Suspension(fixedAsset);
      carryAssetKindFromAccount(fixedAsset);
      alignPlansWithTaxRate(fixedAsset);
    }
    return super.save(fixedAsset);
  }

  /**
   * SAF-T A1: празният вид на актива — от сметката му (21x → НМА, иначе ДМА; {@link
   * BgFixedAssetKinds#defaultFor}). Само празното се попълва: изборът на човека (инвестиционен
   * имот, по МСФО — държан за продажба, право на ползване) не се пипа. По пътя на REST записът
   * стига тук след flush-а, тоест видът може да остане празен — годишният SAF-T извежда същото по
   * подразбиране и не разчита на това поле да е попълнено.
   */
  protected void carryAssetKindFromAccount(FixedAsset fixedAsset) {
    if (fixedAsset.getBgAssetKindSelect() != null && !fixedAsset.getBgAssetKindSelect().isEmpty()) {
      return;
    }
    fixedAsset.setBgAssetKindSelect(
        BgFixedAssetKinds.defaultFor(
            fixedAsset.getPurchaseAccount() == null
                ? null
                : fixedAsset.getPurchaseAccount().getCode()));
  }

  /**
   * Извежда датите и числото по чл. 59 ЗКПО от обявеното „не се използва от“.
   *
   * <p><b>Изчислява и показва — НЕ пренаписва редове на ДАП.</b> Спирането се задейства на
   * тринайсетия месец, тъй че дванадесетте обърнати месеца по ал. 2 почти винаги пресичат вече
   * подадена годишна декларация. Това е корекция за МИНАЛ ПЕРИОД и решението дали да мине с
   * коригираща декларация е на съставителя — тих пренапис на декларирана история не е поправка, а
   * загубена следа. Когато годината още е отворена, същото число влиза направо в текущото
   * изчисление. И в двата случая полето се чете от справката за ГДД, където пропускът би станал
   * занижен данък.
   *
   * <p><b>Ал. 4 се проверява ПЪРВА.</b> „Не се преустановява начисляването на данъчни амортизации
   * за активите по чл. 60, ал. 3“ — тоест за напълно амортизираните за счетоводни цели и за
   * отписаните заради вдигнат праг. И двете групи остават в ДАП по ал. 4 и амортизацията им ТЕЧЕ;
   * изчислено спиране върху тях би влязло в пряко противоречие със закона, а не просто щеше да е
   * неточно.
   */
  protected void computeArt59Suspension(FixedAsset fixedAsset) {
    LocalDate idleSince = fixedAsset.getBgIdleSince();
    LocalDate returned = fixedAsset.getBgReturnedToServiceOn();

    fixedAsset.setBgTaxSuspensionFrom(
        BgAssetSuspensionRules.suspensionFor(fixedAsset, idleSince, returned));
    fixedAsset.setBgArt59Al2Reversal(
        BgAssetSuspensionRules.reversalFor(fixedAsset, idleSince, returned));
    fixedAsset.setBgArt59Al2Year(
        BgAssetSuspensionRules.reversalYearFor(fixedAsset, idleSince, returned));
  }

  /**
   * Гейтът по чл. 50 / чл. 51 ЗКПО: актив под прага НЕ е данъчен амортизируем актив, тъй че данъчен
   * план не му се строи.
   *
   * <p><b>Защо просто се сваля FISCAL от плана, вместо да се пише проверка в услуга.</b> {@code
   * FixedAssetLineGenerationServiceImpl.generateAndComputeFiscalFixedAssetLines} започва с {@code
   * if (depreciationPlanSelect.contains(DEPRECIATION_PLAN_FISCAL))} и излиза, ако го няма. Тоест
   * базата вече има вратата — не ѝ трябва override, трябва ѝ вярната стойност. Този ред е и
   * причината моят {@code BgFixedAssetLineTaxComputationServiceImpl} да не хвърли „данъчен план без
   * норма“: генераторът излиза преди него.
   *
   * <p><b>Гейтът е САМО данъчен.</b> Икономическият план не се пипа: активът под ДАНЪЧНИЯ праг може
   * напълно законно да е капитализиран по счетоводната политика на клиента и да се амортизира.
   * Смесването на двата прага е грешката, която този метод пази — затова и маркерът се казва „извън
   * ДАП“, не „не е актив“.
   *
   * <p>Прилага се само за активи с българска данъчна норма, тоест за тези, на които изобщо строим
   * ДАП. Нормата вече е пренесена от категорията от {@link #carryTaxFieldsFromCategory}, тъй че
   * редът на двете извиквания има значение.
   */
  protected void applyTaxThresholdGate(FixedAsset fixedAsset) {
    if (!BgTaxDepreciation.hasTaxRate(fixedAsset)) {
      return;
    }
    BigDecimal threshold = decisiveThreshold(fixedAsset);
    fixedAsset.setBgTaxThresholdApplied(threshold);

    boolean below = BgAssetThresholdRules.isBelowThreshold(fixedAsset.getGrossValue(), threshold);
    fixedAsset.setBgIsBelowTaxThreshold(below);
    if (!below) {
      return;
    }
    fixedAsset.setDepreciationPlanSelect(
        BgAssetThresholdRules.withoutFiscalPlan(fixedAsset.getDepreciationPlanSelect()));
  }

  /**
   * По-ниската от политиковия и законовия праг — „по-ниската стойност от“ по чл. 50 ал. 1.
   *
   * <p>Политиковият праг е ДВА, защото законът различава „прага… за дълготрайния МАТЕРИАЛЕН актив“
   * (чл. 50 ал. 1 т. 1) от „прага… за дълготрайния НЕМАТЕРИАЛЕН актив“ (чл. 51 ал. 1 т. 1 б. „г“
   * аа), и избира се по техническия вид на категорията.
   *
   * <p>⚠️ Ключът е {@code technicalTypeSelect}, не кодът на вида. Българският сметкоплан носи ДВА
   * набора видове — „IMMO TAN/IMMO INTAN“ и „FA_TA/FA_IN“, осем реда в {@code
   * account_fixedAssetType.csv} — тъй че сравнение по код би хванало само половината инсталации.
   * Числото е контракт на базата ({@code
   * FixedAssetTypeRepository.FIXED_ASSET_CATEGORY_TECHNICAL_TYPE_SELECT_*}).
   *
   * <p>⚠️ При НЕИЗВЕСТЕН вид политиковият праг НЕ се гадае. И двата политикови прага са избор за
   * конкретен вид активи; прилагането на грешния би изхвърлило от ДАП актив, който му принадлежи.
   * Затова остава само законовата сума — тя е една и същa за материални и нематериални
   * („седемстотин лева“ и в чл. 50 ал. 1 т. 2, и в чл. 51 ал. 1 т. 1 б. „г“ бб) — а приложената
   * стойност се записва в {@code bgTaxThresholdApplied}, за да е видимо какво е било сверено.
   */
  protected BigDecimal decisiveThreshold(FixedAsset fixedAsset) {
    AppBgLocalization app = appBgLocalizationRepository.all().fetchOne();
    BigDecimal statutory = app == null ? null : app.getBgAssetStatutoryThresholdEur();
    return BgAssetThresholdRules.decisiveThreshold(policyThreshold(fixedAsset), statutory);
  }

  protected BigDecimal policyThreshold(FixedAsset fixedAsset) {
    Company company = fixedAsset.getCompany();
    FixedAssetCategory category = fixedAsset.getFixedAssetCategory();
    FixedAssetType type = category == null ? null : category.getFixedAssetType();
    if (company == null || type == null || type.getTechnicalTypeSelect() == null) {
      return null;
    }
    int technicalType = type.getTechnicalTypeSelect();
    if (technicalType
        == FixedAssetTypeRepository.FIXED_ASSET_CATEGORY_TECHNICAL_TYPE_SELECT_TANGIBLE_ASSET) {
      return company.getBgTangibleAssetMaterialityEur();
    }
    if (technicalType
        == FixedAssetTypeRepository.FIXED_ASSET_CATEGORY_TECHNICAL_TYPE_SELECT_INTANGIBLE_ASSET) {
      return company.getBgIntangibleAssetMaterialityEur();
    }
    return null;
  }

  /**
   * Пренася категория и норма по чл. 55 от категорията, но САМО когато активът не носи свои.
   *
   * <p>Празната норма на актива значи „както казва категорията“, а не „нула“ — генерираният getter
   * на BigDecimal връща ZERO и за незададена колона (виж {@code BgDefaultedGetterTest}), тъй че
   * двете пристигат като едно и също число и разликата се чете само през {@code signum()}.
   * Непразната норма е СЪЗНАТЕЛЕН избор за този актив (чл. 55 ал. 2 дава таван, не задължение) и не
   * се пренаписва от категорията.
   */
  protected void carryTaxFieldsFromCategory(FixedAsset fixedAsset) {
    FixedAssetCategory category = fixedAsset.getFixedAssetCategory();
    if (category == null) {
      return;
    }
    if (fixedAsset.getBgTaxDepreciationRate().signum() == 0
        && category.getBgTaxDepreciationRate().signum() != 0) {
      fixedAsset.setBgTaxDepreciationRate(category.getBgTaxDepreciationRate());
    }
    if (fixedAsset.getBgTaxCategorySelect() == null) {
      fixedAsset.setBgTaxCategorySelect(category.getBgTaxCategorySelect());
    }
    if (fixedAsset.getBgTaxStartSelect() == null) {
      fixedAsset.setBgTaxStartSelect(BgTaxDepreciationStart.SERVICE_MONTH);
    }
  }

  /**
   * Пренася фактите за ДДС от покупната фактура върху актива — <b>входът на чл. 79 ал. 3 ЗДДС</b>.
   *
   * <p><b>Защо тук, а не в изгледа.</b> Същият довод като при данъчните полета, но с още едно
   * острие: годишната корекция по чл. 79а/79б обхожда активите със ЗАЯВКА ({@code
   * BgVatAnnualCorrectionService.HELD_ASSETS_FILTER}), тоест филтърът пита КОЛОНИТЕ. Поле,
   * попълнено само от {@code action-attrs}, го няма нито по REST, нито по импорт, нито при актив,
   * породен от фактура — а точно този е най-честият път за ДМА.
   *
   * <p><b>Пише само в празно и не гади.</b> Правилото живее в {@link BgArt79Acquisition} — едно
   * място и за четенето при отписване, и за записа тук. Класификацията по ал. 3 остава празна
   * НАРОЧНО: тя е единственият гейт на целия режим и е преценка на счетоводителя.
   *
   * <p>⚠️ <b>К₀ иска годината ПЪРВО.</b> Коефициентът по чл. 73 е „за годината, през която е
   * упражнено право на данъчен кредит“, тъй че лутането е двустъпково: фактурата казва годината,
   * годината избира реда в таблицата. Обърнат, редът би търсил коефициент за година 0.
   */
  protected void carryVatFactsFromInvoiceLine(FixedAsset fixedAsset) {
    BgArt79Acquisition.applyInvoiceDefaults(fixedAsset);
    BgArt79Acquisition.applyCoefficientDefault(fixedAsset, vatCoefficientFor(fixedAsset));
  }

  /**
   * Коефициентът по чл. 73 за годината на упражняване на кредита.
   *
   * <p><b>ОКОНЧАТЕЛНИЯТ по ал. 6 е верният.</b> Годината на кредита е минала, тъй че тя вече е
   * преизчислена по реалните обороти; прогнозният по ал. 5 описва оборота на ПРЕДХОДНАТА година и
   * би вкарал чуждо число във формулата на ал. 3.
   *
   * <p>⚠️ <b>Прогнозният е РЕЗЕРВ, не равностоен избор.</b> Той се взима само когато окончателният
   * още го няма — типично за актив, заведен в течение на същата година. Тогава записаното К₀ е
   * подразбиране, което подлежи на проверка: помощта на полето го казва, а въведеното от
   * счетоводителя число никога не се презаписва оттук.
   */
  protected BigDecimal vatCoefficientFor(FixedAsset fixedAsset) {
    Company company = fixedAsset.getCompany();
    int creditYear = fixedAsset.getBgVatCreditYear();
    if (company == null || creditYear == 0) {
      return null;
    }
    BgVatCoefficient row =
        bgVatCoefficientRepository
            .all()
            .filter("self.company = :c AND self.year = :y")
            .bind("c", company)
            .bind("y", creditYear)
            .fetchOne();
    if (row == null) {
      return null;
    }
    return row.getFinalCoefficient().signum() != 0
        ? row.getFinalCoefficient()
        : row.getCoefficient();
  }

  /**
   * Пренася метода на изчисление от категорията, когато активът е дошъл без него.
   *
   * <p><b>Защо изобщо трябва.</b> {@code
   * AbstractFixedAssetLineComputationServiceImpl.computeProrataTemporisAcquisitionDate} вика {@code
   * getComputationMethodSelect(fixedAsset).equals(...)} БЕЗ проверка за null, и то по пътя на всяко
   * първо изчисление — {@code computeProrataTemporis} се извиква безусловно, дори когато прорате е
   * изключено. Актив, създаден през REST или импорт, стига дотам с празен метод и цялото генериране
   * пада с гол {@code NullPointerException}.
   *
   * <p><b>Защо от категорията, а не константа.</b> Методът е част от същия набор параметри, който
   * категорията вече дава на плана; за българските категории {@code l10n/chart-config.xml} го
   * заковава на {@code linear} с изричен bind, тъй че стойността е известна и еднаква. Твърд {@code
   * linear} тук би бил верен днес и мълчаливо грешен в деня, в който някоя категория стане
   * дегресивна.
   *
   * <p>Само за активи с българска данъчна норма: без нея това е чужд актив и наборът му се пълни по
   * базовия път.
   */
  protected void carryComputationMethodFromCategory(FixedAsset fixedAsset) {
    if (!BgTaxDepreciation.hasTaxRate(fixedAsset)) {
      return;
    }
    FixedAssetCategory category = fixedAsset.getFixedAssetCategory();
    String method = category == null ? null : category.getComputationMethodSelect();
    if (method == null || method.isEmpty()) {
      method = FixedAssetRepository.COMPUTATION_METHOD_LINEAR;
    }
    if (fixedAsset.getComputationMethodSelect() == null) {
      fixedAsset.setComputationMethodSelect(method);
    }
    if (fixedAsset.getFiscalComputationMethodSelect() == null) {
      fixedAsset.setFiscalComputationMethodSelect(method);
    }
  }

  /**
   * Изравнява плановете с данъчната норма: данъчния ВИНАГИ, счетоводния — само когато активът сам
   * твърди, че двата са равни.
   *
   * <p><b>Защо данъчният не чака флага.</b> {@code isEqualToFiscalDepreciation} се вдига от ИЗГЛЕДА
   * ({@code action-account-attrs-fill-fiscal-panel}) и от {@code copyFiscalPlanToEconomicPlan()} по
   * пътя „актив от фактура“ — тоест по всеки друг път (REST, импорт, партида) той остава false,
   * защото генерираният getter на Boolean връща FALSE и за незададена колона (виж {@code
   * BgDefaultedGetterTest}) и „никой не е решавал“ не се различава от „решено е, че не са равни“.
   * Актив, създаден през REST с план „1,2“ и категория с норма 30%, иначе излиза с {@code
   * fiscalNumberOfDepreciation = 0} и годишна периодичност — тоест БЕЗ данъчен план. А ДАП-ът е
   * законовото задължение и не бива да зависи от това дали някой е отварял формата.
   *
   * <p><b>Защо счетоводният ЧАКА флага.</b> Обратната посока е избор, не задължение: счетоводният
   * срок е преценка по счетоводна политика. Свален флаг значи „водя ги различно“ и се уважава.
   *
   * <p><b>Какво.</b> Броят периоди и срокът излизат от нормата: 1200/ГДАН месеца. Без това смяната
   * на нормата на един актив (например от 30% на 25%, което чл. 55 ал. 2 позволява, защото нормата
   * е таван) би преместила ДАП-а на 48 месеца, а счетоводният план би останал на 40 — тихо
   * разминаване, което никой не е избирал.
   *
   * <p><b>Странична поправка.</b> {@code copyFiscalPlanToEconomicPlan()} пише {@code
   * setDurationInMonth(getFiscalPeriodicityTypeSelect())} — взима ПЕРИОДИЧНОСТТА вместо срока и
   * оставя 1 или 12. При месечна периодичност (както са БГ категориите) счетоводният срок излизаше
   * „1 месец“ на екрана за всеки актив, заведен от фактура. Тук стойността се пренаписва вярно;
   * базовият файл не се пипа.
   */
  protected void alignPlansWithTaxRate(FixedAsset fixedAsset) {
    String plan = fixedAsset.getDepreciationPlanSelect();
    if (plan == null || !plan.contains(DEPRECIATION_PLAN_FISCAL)) {
      return;
    }
    BigDecimal rate = BgTaxDepreciation.effectiveRate(fixedAsset);
    if (rate.signum() == 0) {
      return;
    }

    // Месечна стъпка: чл. 58 ал. 1 пуска амортизацията от МЕСЕЦ, чл. 59 я спира по МЕСЕЦИ, и
    // точно в месеци всяка норма по чл. 55 ал. 2 излиза цяло число периоди.
    int months = BgTaxDepreciation.monthsForRate(rate).setScale(0, RoundingMode.CEILING).intValue();

    fixedAsset.setFiscalPeriodicityTypeSelect(FixedAssetRepository.PERIODICITY_TYPE_MONTH);
    fixedAsset.setFiscalPeriodicityInMonth(1);
    fixedAsset.setFiscalNumberOfDepreciation(months);
    fixedAsset.setFiscalDurationInMonth(months);

    if (Boolean.TRUE.equals(fixedAsset.getIsEqualToFiscalDepreciation())
        && plan.contains(DEPRECIATION_PLAN_ECONOMIC)) {
      fixedAsset.setPeriodicityTypeSelect(FixedAssetRepository.PERIODICITY_TYPE_MONTH);
      fixedAsset.setPeriodicityInMonth(1);
      fixedAsset.setNumberOfDepreciation(months);
      fixedAsset.setDurationInMonth(months);
    }
  }
}
