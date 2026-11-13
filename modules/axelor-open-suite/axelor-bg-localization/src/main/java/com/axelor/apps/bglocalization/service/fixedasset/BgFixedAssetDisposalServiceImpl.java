package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AssetDisposalReason;
import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.account.service.fixedasset.FixedAssetDerogatoryLineService;
import com.axelor.apps.account.service.fixedasset.FixedAssetDisposalServiceImpl;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineGenerationService;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineMoveService;
import com.axelor.apps.account.service.fixedasset.FixedAssetLineService;
import com.axelor.apps.account.service.fixedasset.FixedAssetRecordService;
import com.axelor.apps.account.service.fixedasset.FixedAssetService;
import com.axelor.apps.account.service.fixedasset.factory.FixedAssetLineServiceFactory;
import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.repo.TraceBackRepository;
import com.axelor.apps.bglocalization.db.BgFixedAssetWriteOff;
import com.axelor.apps.bglocalization.db.BgSubsequentCost;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetWriteOffRepository;
import com.axelor.apps.bglocalization.db.repo.BgSubsequentCostRepository;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Отписване на актив по български правила — чл. 60 и чл. 66 ЗКПО.
 *
 * <p>Върши две неща над базата, и те са различни по вид:
 *
 * <ol>
 *   <li><b>Отрязва данъчния план по чл. 60 ал. 2</b>, а не по датата на счетоводното отписване.
 *       Базата реже четирите борда с ЕДНА И СЪЩА дата; ЗКПО иска данъчният да спре от НАЧАЛОТО на
 *       месеца, тоест един месец по-рано от счетоводния.
 *   <li><b>Записва преобразуването по чл. 66</b> в регистър, защото то е извънсчетоводно и не
 *       оставя следа в нито една статия — а влиза в ГДД.
 * </ol>
 *
 * <p>Счетоводната част (Дт 241/242 + Дт 60908 или 60905 / Кт 20x) НЕ се пипа: базовият {@code
 * FixedAssetLineMoveServiceImpl.generateDisposalMove} вече строи точно българската схема, а
 * разграничението продажба/бракуване идва от {@code transferredReason} (CESSION → {@code
 * realisedAssetsValueAccount} = 609, SCRAPPING → {@code approExtraordDepreciationExpenseAccount} =
 * 60905).
 */
public class BgFixedAssetDisposalServiceImpl extends FixedAssetDisposalServiceImpl {

  protected BgFixedAssetWriteOffRepository bgFixedAssetWriteOffRepository;
  protected BgSubsequentCostRepository bgSubsequentCostRepository;
  protected BgRemovalAccountResolver removalAccountResolver;

  @Inject
  public BgFixedAssetDisposalServiceImpl(
      FixedAssetRepository fixedAssetRepo,
      FixedAssetLineMoveService fixedAssetLineMoveService,
      FixedAssetService fixedAssetService,
      FixedAssetLineServiceFactory fixedAssetLineServiceFactory,
      FixedAssetLineGenerationService fixedAssetLineGenerationService,
      FixedAssetDerogatoryLineService fixedAssetDerogatoryLineService,
      FixedAssetLineService fixedAssetLineService,
      FixedAssetRecordService fixedAssetRecordService,
      BgFixedAssetWriteOffRepository bgFixedAssetWriteOffRepository,
      BgSubsequentCostRepository bgSubsequentCostRepository,
      BgRemovalAccountResolver removalAccountResolver) {
    super(
        fixedAssetRepo,
        fixedAssetLineMoveService,
        fixedAssetService,
        fixedAssetLineServiceFactory,
        fixedAssetLineGenerationService,
        fixedAssetDerogatoryLineService,
        fixedAssetLineService,
        fixedAssetRecordService);
    this.bgFixedAssetWriteOffRepository = bgFixedAssetWriteOffRepository;
    this.bgSubsequentCostRepository = bgSubsequentCostRepository;
    this.removalAccountResolver = removalAccountResolver;
  }

  /**
   * Единственият вход за отписване на един актив — и затова мястото, където причината решава дали
   * отписване изобщо се дължи.
   *
   * <p><b>Приходът НЕ идва оттук.</b> {@code generateSaleMove} се заковава на {@code false}:
   * продажбата на дълготраен актив е облагаема доставка и по чл. 113 ЗДДС се документира с ФАКТУРА,
   * която сама осчетоводява Дт 411 / Кт 705 + Кт 4532. Оставен вдигнат, флагът прави същото в
   * журнал SAL и приходът влиза ДВА пъти — а дневникът за продажби получава два реда за една
   * доставка, защото {@code BgVatDocumentCollector.isVatRelevant} приема всеки Move с данъчна
   * линия. При това редът от Move-а излиза с номер на АКТИВА вместо номер на фактура ({@code
   * enrichFromMove} чете {@code move.getOrigin()}), тоест не може да се подаде.
   *
   * <p><b>Две причини отказват отписване, и двете защото законът го забранява.</b>
   *
   * <ul>
   *   <li><b>Консервация</b> — <b>чл. 59 ал. 1 ЗКПО</b>, целият, дословно: „Начисляването на
   *       данъчни амортизации се преустановява, когато съответният актив временно не се използва
   *       (не носи икономическа изгода) през период, по-дълъг от дванадесет месеца. Начисляването
   *       се преустановява от началото на месеца, следващ месеца, през който е изтекъл срокът по
   *       изречение първо, и се възобновява от началото на месеца на връщането на актива в
   *       експлоатация. <b>Данъчният амортизируем актив не се отписва от данъчния амортизационен
   *       план.</b>“ Базовият съветник обаче няма вид извеждане без статия: {@code
   *       disposalTypeSelect} има само три стойности и всичките водят до Кт 20x. Тоест пуснато
   *       напред, това би произвело точно отписването, което последното изречение забранява.
   *   <li><b>Напълно данъчно амортизиран</b> — <b>чл. 60 ал. 1</b>, дословно: „Активът се отписва
   *       от данъчния амортизационен план, когато е напълно амортизиран за данъчни цели.“ Това е
   *       правилният член, а не чл. 58: чл. 58 е озаглавен „Начисляване на данъчни амортизации“ и
   *       урежда началото (ал. 1) и формулата (ал. 3), не края. А краят настъпва сам заради тавана
   *       по <b>чл. 53 ал. 3</b>: „Начислената данъчна амортизация не може да превишава данъчната
   *       амортизируема стойност на актива.“ Тоест счетоводна статия не се дължи, а активът може
   *       още да е на баланс (счетоводният срок често е по-дълъг). Пуснато напред, това би отписало
   *       от книгите актив, който само е приключил данъчния си план.
   * </ul>
   *
   * <p><b>Продажба за скрап върви оттук, не по бракуването.</b> Причината {@code BG_SCRAPSALE} е
   * {@code disposalTypeSelect = 2} и слот 609 — активът излиза срещу престация, тъй че събитието е
   * доставка и се документира с фактура. Особеното е само в начисляването: по <b>чл. 163а ал. 2
   * ЗДДС</b> „Данъкът за доставките по ал. 1 е изискуем от получателя - регистрирано по този закон
   * лице“, защото <b>Приложение № 2, част първа, т. 5</b> е „Отпадъци от черни и цветни метали“, а
   * <b>§ 1 т. 77 ДР</b> ги определя така, че включват „бракуваните машини, съоръжения, детайли и
   * конструкции“. Корекция по чл. 79 НЕ се поражда и изключение по чл. 80 ал. 2 не се цитира —
   * изключение от корекция има смисъл само ако корекционно събитие е настъпило. Затова 60905 остава
   * само за реално унищожен актив БЕЗ насрещна престация.
   *
   * <p>⚠️ {@code @Transactional} СЕ ПРЕПИСВА, НЕ СЕ НАСЛЕДЯВА. Базовият {@code fullDisposal} е
   * анотиран, но Guice AOP прихваща най-външното ПРОКСИРАНО извикване — {@code super.fullDisposal}
   * е пряко извикване и интерсепторът не се задейства. Без анотацията тук външната граница изчезва
   * и остават само вътрешните ({@code computeDisposal}, {@code recordWriteOff}), които се комитват
   * поотделно. Тогава отписването пада с „Cannot rollback transaction in current status
   * [COMMITTED]“ — тоест част от работата вече е записана, а останалата се опитва да се върне.
   */
  @Override
  @Transactional(rollbackOn = {Exception.class})
  public FixedAsset fullDisposal(
      FixedAsset fixedAsset,
      LocalDate disposalDate,
      int disposalQtySelect,
      BigDecimal disposalQty,
      Boolean generateSaleMove,
      Set<TaxLine> saleTaxLineSet,
      Integer disposalTypeSelect,
      BigDecimal disposalAmount,
      AssetDisposalReason assetDisposalReason,
      String comments)
      throws AxelorException {

    refuseIfNotAWriteOff(fixedAsset, assetDisposalReason);

    boolean bgAsset = BgTaxDepreciation.hasTaxRate(fixedAsset);
    return super.fullDisposal(
        fixedAsset,
        disposalDate,
        disposalQtySelect,
        disposalQty,
        bgAsset ? Boolean.FALSE : generateSaleMove,
        bgAsset ? Collections.emptySet() : saleTaxLineSet,
        disposalTypeSelect,
        disposalAmount,
        assetDisposalReason,
        comments);
  }

  /** Отказва отписване, когато причината обявява, че отписване не се дължи. */
  protected void refuseIfNotAWriteOff(FixedAsset fixedAsset, AssetDisposalReason reason)
      throws AxelorException {
    if (reason == null) {
      return;
    }
    String kind = reason.getBgRemovalKindSelect();
    if (BgAssetRemoval.Kind.SUSPENSION.equals(kind)) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "%s",
          String.format(
              "Причина „%s“ не е отписване. По чл. 59 ал. 1 ЗКПО активът НЕ СЕ ОТПИСВА от данъчния"
                  + " амортизационен план — начисляването само се преустановява от началото на"
                  + " месеца, следващ месеца, в който изтича дванадесетмесечният срок на"
                  + " неползване, и се възобновява от началото на месеца на връщането в"
                  + " експлоатация. Активът „%s“ остава на баланс. Спирането по чл. 59 се въвежда"
                  + " отделно (WS-A4), не през съветника за отписване.",
              reason.getName(), fixedAsset == null ? "" : fixedAsset.getName()));
    }
    if (BgAssetRemoval.Kind.TAX_PLAN_ONLY.equals(kind)) {
      throw new AxelorException(
          TraceBackRepository.CATEGORY_INCONSISTENCY,
          "%s",
          String.format(
              "Причина „%s“ не поражда счетоводно отписване. По чл. 60 ал. 1 ЗКПО активът се"
                  + " отписва от данъчния амортизационен план, когато е напълно амортизиран за"
                  + " данъчни цели — а данъчният план се изчерпва сам, по тавана на чл. 53 ал. 3"
                  + " („начислената данъчна амортизация не може да превишава данъчната"
                  + " амортизируема стойност на актива“), без статия. Активът „%s“"
                  + " може още да е на баланс, ако счетоводният му срок е по-дълъг. За отписване от"
                  + " книгите изберете действителното основание — продажба, брак, липси или"
                  + " непреодолима сила.",
              reason.getName(), fixedAsset == null ? "" : fixedAsset.getName()));
    }
  }

  /**
   * Реже бордовете — счетоводния по датата на отписване, ДАНЪЧНИЯ един месец по-рано.
   *
   * <p>Чл. 60 ал. 2: активът се отписва от данъчния амортизационен план „в началото на месеца, през
   * който се отписва за счетоводни цели“. Значи последният данъчен ред е този на месеца ПРЕДИ
   * месеца на отписване — виж {@link BgAssetWriteOffRules#taxWriteOffCutDate}.
   *
   * <p><b>Чл. 60 ал. 3</b> — дословно: „Алинея 2 не се прилага при отписване на активи: 1. (изм. -
   * ДВ, бр. 110 от 2007 г., в сила от 01.01.2008 г.) които са напълно амортизирани за счетоводни
   * цели; 2. в резултат на увеличение в стойностния праг на същественост.“ И в двата случая активът
   * остава в ДАП и излиза по реда на ал. 1, при пълно данъчно амортизиране (ал. 4) — тоест
   * данъчният борд не се пипа изобщо.
   *
   * <p><b>И ДВЕТЕ точки, не само първата.</b> Т. 2 се задейства от ВДИГАНЕ НА ПРАГА, а не от „актив
   * под прага“: новопридобит актив под прага изобщо не влиза в ДАП по чл. 50/51 и няма какво да се
   * реже, докато тук говорим за актив, който ВЕЧЕ Е В ПЛАНА и се разписва счетоводно, защото
   * дружеството е повишило прага си. Проверка само на т. 1 би отрязала данъчния борд на точно тези
   * активи — тоест би приложила ал. 2 там, където законът я изключва.
   *
   * <p>Проверката за пълно счетоводно амортизиране се прави ПРЕДИ рязането на счетоводния борд —
   * след него разликата между „изчерпан план“ и „план, отрязан сега“ вече не се вижда.
   */
  @Override
  protected FixedAsset filterListsByDates(FixedAsset fixedAsset, LocalDate date) {
    Objects.requireNonNull(fixedAsset);
    if (!BgTaxDepreciation.hasTaxRate(fixedAsset) || date == null) {
      return super.filterListsByDates(fixedAsset, date);
    }

    boolean art60Al3 = BgAssetWriteOffRules.isArt60Al3Excluded(fixedAsset);

    fixedAssetLineService.filterListByDate(fixedAsset.getFixedAssetLineList(), date);
    fixedAssetLineService.filterListByDate(fixedAsset.getIfrsFixedAssetLineList(), date);
    fixedAssetDerogatoryLineService.filterListByDate(
        fixedAsset.getFixedAssetDerogatoryLineList(), date);

    if (!art60Al3) {
      fixedAssetLineService.filterListByDate(
          fixedAsset.getFiscalFixedAssetLineList(), BgAssetWriteOffRules.taxWriteOffCutDate(date));
    }
    return fixedAsset;
  }

  /**
   * След базовото отписване записва реда в регистъра по чл. 60/66.
   *
   * <p>Записва се СЛЕД super, защото и двете суми се четат от вече отрязаните бордове: СБС от
   * счетоводния, ДБС от данъчния. Преди рязането ДБС още съдържа месеца, който чл. 60 ал. 2 отнема.
   *
   * <p><b>При частична продажба записът е за НОВИЯ актив.</b> {@code
   * FixedAssetService.splitFixedAsset} отделя продаваната част в отделен {@code FixedAsset} и
   * базата отписва него, а първоначалният продължава да се амортизира. Запис за първоначалния би
   * твърдял, че цялият актив е излязъл.
   */
  @Override
  protected FixedAsset computeDisposal(
      FixedAsset fixedAsset,
      LocalDate disposalDate,
      BigDecimal disposalQty,
      BigDecimal disposalAmount,
      int transferredReason,
      AssetDisposalReason assetDisposalReason,
      String comments)
      throws AxelorException {

    FixedAsset createdFixedAsset =
        super.computeDisposal(
            fixedAsset,
            disposalDate,
            disposalQty,
            disposalAmount,
            transferredReason,
            assetDisposalReason,
            comments);

    FixedAsset writtenOff = createdFixedAsset != null ? createdFixedAsset : fixedAsset;
    if (BgTaxDepreciation.hasTaxRate(writtenOff) && disposalDate != null) {
      recordWriteOff(writtenOff, disposalDate, transferredReason);
      cascadeArt64(writtenOff, disposalDate);
      repointChargeAccount(writtenOff, transferredReason);
    }
    return createdFixedAsset;
  }

  /**
   * Съставя и записва реда в регистъра. Отделен метод, за да е достижим от тест и от преизчисление.
   */
  @Transactional(rollbackOn = {Exception.class})
  /**
   * Чл. 64 ал. 4 — породеният от последващи разходи актив следва стария при СЧЕТОВОДНОТО му
   * отписване.
   *
   * <p>Дословно: „Когато активът, във връзка с който са извършени последващите разходи, се отпише
   * от счетоводния амортизационен план, <b>преди данъчният амортизируем актив по ал. 1 да е напълно
   * амортизиран</b>, последният се отписва от данъчния амортизационен план при условията и по реда
   * на <b>чл. 60</b>.“
   *
   * <p><b>Защо автоматично, а не с напомняне.</b> Породеният актив е отделен запис със свой план и
   * нищо на екрана не го свързва с отписвания стар — човек, който отписва машина, няма причина да
   * се сети, че преди две години е капитализирал ремонт по нея. Пропуснато, отписването оставя
   * данъчен план, който продължава да се начислява за актив, чийто носител вече го няма.
   *
   * <p><b>„По реда на чл. 60“</b> значи ал. 2: активът излиза от ДАП „в началото на месеца, през
   * който се отписва за счетоводни цели“ — тоест последният данъчен ред е този на ПРЕДХОДНИЯ месец.
   * Датата идва от {@link BgAssetWriteOffRules#taxWriteOffCutDate}, същата, която реже и при
   * обикновено отписване.
   *
   * <p>⚠️ Вече напълно данъчно амортизиран породен актив НЕ се пипа — ал. 4 казва „ПРЕДИ да е
   * напълно амортизиран“, а такъв излиза сам по ал. 1.
   */
  protected void cascadeArt64(FixedAsset writtenOffAsset, LocalDate disposalDate) {
    List<BgSubsequentCost> costs =
        bgSubsequentCostRepository
            .all()
            .filter(
                "self.fixedAsset = :asset AND self.isApplied = TRUE"
                    + " AND self.routingSelect = :routing"
                    + " AND self.generatedFixedAsset IS NOT NULL")
            .bind("asset", writtenOffAsset)
            .bind("routing", BgSubsequentCostRules.Routing.ART64)
            .fetch();

    for (BgSubsequentCost cost : costs) {
      FixedAsset generated = cost.getGeneratedFixedAsset();
      if (!BgAssetWriteOffRules.requiresArt64Cascade(generated)) {
        continue;
      }
      // Чл. 60 ал. 2: последният данъчен ред е този на месеца ПРЕДИ счетоводното отписване.
      fixedAssetLineService.filterListByDate(
          generated.getFiscalFixedAssetLineList(),
          BgAssetWriteOffRules.taxWriteOffCutDate(disposalDate));
      generated.setDepreciationPlanSelect(
          BgAssetThresholdRules.withoutFiscalPlan(generated.getDepreciationPlanSelect()));

      // Записът в регистъра, за да може справката по чл. 66 (WS-A5) да прочете −ДБС в годината, в
      // която активът наистина излиза от плана. Без него намалението няма къде да се появи.
      BgFixedAssetWriteOff writeOff =
          recordWriteOff(generated, disposalDate, FixedAssetRepository.TRANSFERED_REASON_SCRAPPING);
      writeOff.setComments(
          String.format(
              "Отписан по чл. 64 ал. 4 ЗКПО, защото активът „%s“, във връзка с който са извършени"
                  + " последващите разходи, се отписва от счетоводния амортизационен план на %s,"
                  + " преди този актив да е напълно амортизиран. Отписването е по реда на чл. 60.",
              writtenOffAsset.getName(), disposalDate));
      fixedAssetRepo.save(generated);
    }
  }

  /**
   * Пренасочва дебитния ред на статията към сметката, мапната за причината — например възстановима
   * липса към вземане вместо към извънреден разход.
   *
   * <p><b>Защо СЛЕД super, а не вместо него.</b> Базата избира сметката вътре в {@code
   * FixedAssetLineMoveServiceImpl.generateDisposalMove} — <b>156 реда без нито един protected
   * шев</b>. Override значи копиране на всичките, които после ще се разминават тихо с базата при
   * всяко ѝ обновяване. Затова статията се оставя да се създаде и се пренасочва ЕДИН ред. Това е
   * съзнателен компромис, не недоглеждане: цената му е, че огледалото на базовия избор ({@link
   * BgRemovalAccountRules#baseChargeAccount}) трябва да го следва.
   *
   * <p><b>Липсващ мапинг НЕ е грешка.</b> Тогава нищо не се пренасочва и сметката остава онази,
   * която базата е избрала — 60905 за липси. Това е и причината обектът да няма seed: сметка 442
   * изобщо я няма в сметкоплана на модула, а мапинг към несъществуваща сметка би бил догадка.
   *
   * <p>⚠️ <b>ДДС третирането НЕ се пипа.</b> Тук се мени САМО дебитната сметка. Дали се дължи
   * корекция по чл. 79 ЗДДС идва от причината и остава „подлежи“ за липсите — възстановими или не.
   * Наличието на отговорно лице не е доказване по чл. 80 ал. 2 т. 1 б. „г“; то по-скоро сочи
   * обратното.
   */
  protected void repointChargeAccount(FixedAsset fixedAsset, int transferredReason) {
    AssetDisposalReason reason = fixedAsset.getAssetDisposalReason();
    Move disposalMove = fixedAsset.getDisposalMove();
    if (reason == null || disposalMove == null) {
      return;
    }
    Account target = resolveRemovalAccount(fixedAsset, reason);
    if (target == null) {
      return;
    }
    Account expected =
        BgRemovalAccountRules.baseChargeAccount(
            fixedAsset.getFixedAssetCategory(), transferredReason);
    MoveLine chargeLine =
        BgRemovalAccountRules.chargeLineToRepoint(disposalMove.getMoveLineList(), expected);
    if (chargeLine == null
        || !BgRemovalAccountRules.shouldRepoint(target, chargeLine.getAccount())) {
      return;
    }
    chargeLine.setAccount(target);
  }

  /**
   * Коя сметка замества избраната от базата — фирменото припокриване, после кодът от причината.
   * Резолвърът е общ с протокола по чл. 79 ({@link BgRemovalAccountResolver}).
   */
  protected Account resolveRemovalAccount(FixedAsset fixedAsset, AssetDisposalReason reason) {
    return removalAccountResolver.removalAccount(fixedAsset.getCompany(), reason);
  }

  protected BgFixedAssetWriteOff recordWriteOff(
      FixedAsset fixedAsset, LocalDate disposalDate, int transferredReason) {

    BgFixedAssetWriteOff writeOff = new BgFixedAssetWriteOff();
    writeOff.setFixedAsset(fixedAsset);
    writeOff.setCompany(fixedAsset.getCompany());
    writeOff.setWriteOffDate(disposalDate);
    writeOff.setTaxYear(disposalDate.getYear());
    writeOff.setReasonSelect(reasonFor(transferredReason));

    BigDecimal sbs = BgAssetWriteOffRules.accountingCarryingValue(fixedAsset);
    BigDecimal dbs = BgAssetWriteOffRules.taxValue(fixedAsset);
    // ДАС по чл. 53 ал. 1 — историческата цена; провизии и дарения не се моделират в този етап.
    BigDecimal das = fixedAsset.getGrossValue();

    writeOff.setAccountingCarryingValue(declared(sbs));
    writeOff.setTaxValue(declared(dbs));
    writeOff.setTaxAmortizableValue(declared(das));
    writeOff.setTaxDepreciationCharged(declared(das.subtract(dbs)));

    boolean art60Al3Point1 = BgAssetWriteOffRules.isFullyDepreciatedForAccounting(fixedAsset);
    writeOff.setIsArt60Al2Applied(!art60Al3Point1);
    writeOff.setTaxPlanCutDate(
        art60Al3Point1 ? null : BgAssetWriteOffRules.taxWriteOffCutDate(disposalDate));

    writeOff.setArt66ExceptionSelect(BgArt66Exception.NONE);
    writeOff.setIsArt66Applied(BgAssetWriteOffRules.art66Applies(BgArt66Exception.NONE, sbs, dbs));

    carryVatTreatmentFromReason(writeOff, fixedAsset.getAssetDisposalReason());
    computeArt79Correction(writeOff, fixedAsset, disposalDate);
    computeArt79Deduction(writeOff, fixedAsset, disposalDate);

    writeOff.setDisposalMove(fixedAsset.getDisposalMove());
    writeOff.setSaleMove(fixedAsset.getSaleAccountMove());

    return bgFixedAssetWriteOffRepository.save(writeOff);
  }

  /**
   * Изчислява корекцията по <b>чл. 79 ал. 3 ЗДДС</b> и я записва върху отписването.
   *
   * <p><b>Само при {@code CORRECTION_DUE}.</b> Гардрейлът по чл. 80 ал. 2 е горе, на причината и на
   * {@code BgFixedAssetWriteOffManagementRepository} — тук не се преоткрива. Обявено „не подлежи“
   * изобщо не стига дотук, а продажбата и обратното начисляване по чл. 163а имат свои третирания.
   *
   * <p><b>Изчислява се и когато излиза НУЛА, и това е нарочно.</b> Изтекъл 20-/5-годишен срок дава
   * 0 с основание по чл. 80 ал. 1 т. 3/т. 4 — записано, то е ОТГОВОР на въпроса „защо не е начислен
   * данък“. Празно поле на същото място е само липса на отговор.
   *
   * <p><b>Липсващите факти не се догаждат.</b> Актив без класификация по ал. 3 или без година на
   * упражняване на кредита не дава вход за формулата ({@link BgArt79Acquisition#inputFor} връща
   * {@code null}) и полетата остават празни, а причината се записва в бележките. Догадка тук сменя
   * 1/20 с 1/5 или мести целия срок с година.
   *
   * <p>⚠️ Невалидна по чл. 79в комбинация (К₀ = 0 при частичен кредит, ПрНИД₀ = 0 или 1,
   * пропорционална услуга) НЕ поваля отписването: калкулаторът хвърля, а тук грешката се превръща в
   * бележка. Иначе една недовъведена ДДС клетка би блокирала ЗКПО поток, който няма нищо общо с
   * нея.
   */
  protected void computeArt79Correction(
      BgFixedAssetWriteOff writeOff, FixedAsset fixedAsset, LocalDate disposalDate) {
    if (!BgAssetRemoval.VatTreatment.CORRECTION_DUE.equals(writeOff.getVatTreatmentSelect())) {
      return;
    }
    BgArt79CorrectionRules.Input input = BgArt79Acquisition.inputFor(fixedAsset, disposalDate);
    if (input == null) {
      appendComment(
          writeOff,
          "Корекция по чл. 79 ал. 3 не е изчислена: активът няма класификация по ал. 3 или година"
              + " на упражняване на данъчния кредит.");
      return;
    }
    BgArt79CorrectionRules.Result result;
    try {
      result = BgArt79CorrectionRules.correction(input);
    } catch (IllegalArgumentException e) {
      appendComment(writeOff, "Корекция по чл. 79 ал. 3 не е изчислена: " + e.getMessage());
      return;
    }
    writeOff.setVatCorrectionDirection(BgArt79Direction.CORRECTION);
    writeOff.setVatCorrectionAmount(result.amount());
    writeOff.setVatCorrectionYears(result.years());
    writeOff.setVatCorrectionPeriodYears(result.periodYears());
    writeOff.setVatCorrectionStartYear(result.periodStartYear());
    writeOff.setVatCorrectionK0(result.appliedK0());
    writeOff.setVatCorrectionProportion(result.appliedProportion());
    writeOff.setVatCorrectionGround(result.legalGround());
    if (result.timeBarGround() != null) {
      writeOff.setVatGroundSelect(result.timeBarGround());
      appendComment(
          writeOff,
          "Корекция не се дължи — изтекъл срок по чл. 80 ал. 1 ("
              + result.timeBarGround()
              + "): срокът тече от "
              + result.periodStartYear()
              + " и обхваща "
              + result.periodYears()
              + " години.");
    }
  }

  /**
   * Изчислява приспадането по <b>чл. 79 ал. 5</b> при ОБЛАГАЕМА доставка на актива.
   *
   * <p>Обратната посока на същата граница: активът не напуска облагаемата дейност, а излиза с
   * облагаема доставка, тъй че неползваната при придобиването част от кредита става приспадаема.
   *
   * <p><b>Тригерът е продажба плюс първоначален К₀ &lt; 1.</b> Продажбата е тази, за която
   * причината вече е казала, че не е корекционно събитие — затова тук не се пита за {@code
   * CORRECTION_DUE}, а за вида на извеждането. Актив, приспаднал изцяло, няма неползвана част и
   * калкулаторът връща нула, без да се вдига шум.
   *
   * <p>⚠️ <b>„Облагаема“ не се проверява тук.</b> Ал. 5 иска доставката да е облагаема, а дали
   * фактурата е с начислен данък се вижда на самата фактура, не на отписването. Записаната сума е
   * ПРАВО, което съставителят упражнява с протокола — ал. 7 дава за това периода на доставката или
   * някой от следващите 12. Затова изчислението стои, а начисляването е отделно действие.
   */
  protected void computeArt79Deduction(
      BgFixedAssetWriteOff writeOff, FixedAsset fixedAsset, LocalDate disposalDate) {
    if (!BgFixedAssetWriteOffRepository.REASON_SALE.equals(writeOff.getReasonSelect())) {
      return;
    }
    BgArt79CorrectionRules.Input input = BgArt79Acquisition.inputFor(fixedAsset, disposalDate);
    if (input == null) {
      return;
    }
    BgArt79CorrectionRules.Result result;
    try {
      result = BgArt79CorrectionRules.deduction(input);
    } catch (IllegalArgumentException e) {
      appendComment(writeOff, "Приспадане по чл. 79 ал. 5 не е изчислено: " + e.getMessage());
      return;
    }
    if (result.amount().signum() == 0) {
      return;
    }
    writeOff.setVatCorrectionDirection(BgArt79Direction.DEDUCTION);
    writeOff.setVatCorrectionAmount(result.amount());
    writeOff.setVatCorrectionYears(result.years());
    writeOff.setVatCorrectionPeriodYears(result.periodYears());
    writeOff.setVatCorrectionStartYear(result.periodStartYear());
    writeOff.setVatCorrectionK0(result.appliedK0());
    writeOff.setVatCorrectionProportion(result.appliedProportion());
    writeOff.setVatCorrectionGround(result.legalGround());
    appendComment(
        writeOff,
        "Право на приспадане по чл. 79 ал. 5 — "
            + result.amount()
            + " EUR. Упражнява се с протокол по ал. 7 в периода на доставката или в един от"
            + " следващите 12 данъчни периода.");
  }

  /** Добавя ред към забележките, без да трие вече записаното. */
  protected void appendComment(BgFixedAssetWriteOff writeOff, String line) {
    String existing = writeOff.getComments();
    writeOff.setComments(
        existing == null || existing.trim().isEmpty() ? line : existing + "\n" + line);
  }

  /**
   * Пренася ПРЕДЛОЖЕНОТО третиране по ЗДДС от причината.
   *
   * <p><b>Предложение, не присъда.</b> За брак, липси и непреодолима сила причината предлага {@code
   * CORRECTION_DUE} — чл. 79 ал. 1 ЗДДС дължи данък „при унищожаване, кражба, установяване на
   * загуба или на липси или при бракуване на стоката“. Освобождаванията по чл. 80 ал. 2
   * съществуват, но всичките висят под условие в отварящото изречение на точката („доказано или
   * потвърдено унищожаване …“), а документи този слой не вижда. Затова тук се записва основанието
   * КАТО ПРЕДЛОЖЕНИЕ заедно с условието за доказване, а свалянето на „не подлежи“ минава през
   * {@code BgFixedAssetWriteOffManagementRepository}, който отказва без доказателство.
   *
   * <p>Липсваща причина не се тълкува: тогава остава подразбиранието на полето ({@code
   * CORRECTION_DUE}), защото по-безопасната посока е начисляване. Обявено „не подлежи“ без
   * основание е скрит данъчен пасив, а излишно начислена корекция стои на видно място в дневника.
   */
  protected void carryVatTreatmentFromReason(
      BgFixedAssetWriteOff writeOff, AssetDisposalReason reason) {
    writeOff.setAssetDisposalReason(reason);
    if (reason == null) {
      return;
    }
    if (reason.getBgVatTreatmentSelect() != null) {
      writeOff.setVatTreatmentSelect(reason.getBgVatTreatmentSelect());
    }
    writeOff.setVatGroundSelect(reason.getBgVatGroundSelect());
    writeOff.setVatProofNote(reason.getBgVatProofNote());
  }

  /**
   * Свежда сума до мащаба на декларацията — две цифри след десетичния знак.
   *
   * <p><b>Защо изрично, а не по подразбиране.</b> Базата държи паричните полета на актива и на
   * редовете му с {@code precision="20" scale="3"} — тоест 2 500 идва като {@code 2500.000}, с ТРИ
   * цифри. Полетата на регистъра са с две, защото това е мащабът, в който сумите влизат в годишната
   * данъчна декларация. Записан направо, {@code 2500.000} поваля {@code @Digits(integer = 18,
   * fraction = 2)} с „numeric value out of bounds“.
   *
   * <p>Закръглянето е безобидно за тези числа: те вече са точни суми в стотинки и третата цифра е
   * нула. HALF_UP е за случая, в който някога не е — по-добре явно правило, отколкото изключение.
   */
  protected BigDecimal declared(BigDecimal amount) {
    return amount == null ? BigDecimal.ZERO : amount.setScale(2, RoundingMode.HALF_UP);
  }

  /**
   * Превежда основанието на базата в българското. Продажбата и бракуването се различават и по
   * разходна сметка (60908 срещу 60905), и по третиране по ЗДДС, затова са отделни стойности.
   *
   * <p>⚠️ Константите се четат от REPOSITORY-то, а не от entity-то: генераторът слага {@code
   * <extra-code>} на един {@code <entity>} в генерирания repository клас. Същото е и в базата —
   * {@code FixedAssetRepository.TRANSFERED_REASON_*} идват от {@code domains/FixedAsset.xml}.
   */
  protected String reasonFor(int transferredReason) {
    switch (transferredReason) {
      case FixedAssetRepository.TRANSFERED_REASON_CESSION:
      case FixedAssetRepository.TRANSFERED_REASON_PARTIAL_CESSION:
      case FixedAssetRepository.TRANSFERED_REASON_ONGOING_CESSION:
        return BgFixedAssetWriteOffRepository.REASON_SALE;
      case FixedAssetRepository.TRANSFERED_REASON_SCRAPPING:
        return BgFixedAssetWriteOffRepository.REASON_SCRAPPING;
      default:
        return BgFixedAssetWriteOffRepository.REASON_OTHER;
    }
  }
}
