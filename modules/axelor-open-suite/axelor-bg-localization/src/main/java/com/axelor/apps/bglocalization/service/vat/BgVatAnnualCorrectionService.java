package com.axelor.apps.bglocalization.service.vat;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgSubsequentCost;
import com.axelor.apps.bglocalization.db.BgVatAnnualCorrection;
import com.axelor.apps.bglocalization.db.BgVatAnnualCorrectionLine;
import com.axelor.apps.bglocalization.db.BgVatCoefficient;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetWriteOffRepository;
import com.axelor.apps.bglocalization.db.repo.BgSubsequentCostRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatAnnualCorrectionLineRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatAnnualCorrectionRepository;
import com.axelor.apps.bglocalization.db.repo.BgVatCoefficientRepository;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79Acquisition;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.AssetKind;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79CorrectionRules.CreditType;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.Input;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.NidUse;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.Result;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aAnnualCorrectionRules.TaxableUse;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers.Carrier;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCarriers.Regime;
import com.axelor.apps.bglocalization.service.fixedasset.BgArt79aCorrectionWindow;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Годишната корекция по <b>чл. 79а/79б ЗДДС</b> — обхождането, което ПРЕДЛАГА редове.
 *
 * <p><b>Системата предлага, счетоводителят потвърждава.</b> Три от входовете са преценка, а не
 * факт: използването през годината (чл. 79в т. 1/2), дали годината е спряла по ал. 8, и дали
 * увеличението да се пропусне по ал. 6. Затова обхождането пише ЧЕРНОВА и нищо не се осчетоводява,
 * докато шапката не бъде потвърдена.
 *
 * <p><b>Идемпотентно.</b> Повторното предлагане за същата година намира съществуващата шапка по
 * {@code (company, year)} и ОПРЕСНЯВА редовете ѝ, вместо да роди втора. Редовете, които вече носят
 * статия, не се пипат — двоен протокол значи двойно движение на данъчния кредит, а дневникът не
 * показва, че двата са за едно и също.
 */
public class BgVatAnnualCorrectionService {

  /**
   * Филтърът за ДЪРЖАНИТЕ активи — изнесен, защото носи правило, а не само заявка.
   *
   * <p>Модулът няма mock-подпора в тестовете, тъй че JPQL се сверява структурно; изнесен като
   * константа, изразът поне има един свидетел, вместо да е вграден в извикване.
   */
  static final String HELD_ASSETS_FILTER =
      "self.company = :c AND self.statusSelect IN (:validated, :depreciated)"
          + " AND self.bgVatAssetKindSelect IS NOT NULL"
          + " AND NOT EXISTS (SELECT 1 FROM BgSubsequentCost c WHERE c.generatedFixedAsset = self)";

  /**
   * Огледалният филтър — държаните активи БЕЗ класификация по ЗДДС.
   *
   * <p>Не е диагностика, а граница на изведеното. Обхождането влиза през {@link
   * #HELD_ASSETS_FILTER}, тъй че некласифицираният актив не стига дори до цикъла и „нула реда“
   * излиза неразличимо от „няма корекции“. Броенето им превръща мълчанието в обявена липса на вход.
   */
  static final String UNCLASSIFIED_ASSETS_FILTER =
      "self.company = :c AND self.statusSelect IN (:validated, :depreciated)"
          + " AND self.bgVatAssetKindSelect IS NULL"
          + " AND NOT EXISTS (SELECT 1 FROM BgSubsequentCost c WHERE c.generatedFixedAsset = self)";

  /** Чернова — предложена, но още неосчетоводена. */
  public static final String STATUS_DRAFT = "DRAFT";

  /** Потвърдена — редовете могат да породят протоколи. */
  public static final String STATUS_CONFIRMED = "CONFIRMED";

  protected final BgVatAnnualCorrectionRepository correctionRepository;
  protected final BgVatAnnualCorrectionLineRepository lineRepository;
  protected final BgVatCoefficientRepository coefficientRepository;
  protected final FixedAssetRepository fixedAssetRepository;
  protected final BgFixedAssetWriteOffRepository writeOffRepository;
  protected final BgSubsequentCostRepository subsequentCostRepository;

  @Inject
  public BgVatAnnualCorrectionService(
      BgVatAnnualCorrectionRepository correctionRepository,
      BgVatAnnualCorrectionLineRepository lineRepository,
      BgVatCoefficientRepository coefficientRepository,
      FixedAssetRepository fixedAssetRepository,
      BgFixedAssetWriteOffRepository writeOffRepository,
      BgSubsequentCostRepository subsequentCostRepository) {
    this.correctionRepository = correctionRepository;
    this.lineRepository = lineRepository;
    this.coefficientRepository = coefficientRepository;
    this.fixedAssetRepository = fixedAssetRepository;
    this.writeOffRepository = writeOffRepository;
    this.subsequentCostRepository = subsequentCostRepository;
  }

  // ------------------------------------------------------------------ предлагане

  /**
   * Предлага редовете за една година — по един за всеки ДЪРЖАН актив в прозореца.
   *
   * <p>Границите на изведеното се записват върху шапката: кои активи са прегледани, кои са отказани
   * и по какво основание. Обявената граница е разликата между „няма корекции“ и „не е погледнато“.
   */
  @Transactional(rollbackOn = {Exception.class})
  public BgVatAnnualCorrection propose(Company company, int year) {
    BgVatAnnualCorrection correction = findOrCreate(company, year);

    List<FixedAsset> assets = heldAssets(company, year);
    int proposed = 0;
    int refused = 0;
    int outOfWindow = 0;
    int lockedByArt79 = 0;
    int carriersSeen = 0;
    int missingFacts = 0;
    List<String> notes = new ArrayList<>();

    for (FixedAsset asset : assets) {
      AssetKind kind = kindOf(asset);
      if (kind == null || asset.getBgVatCreditYear() == 0) {
        // Класифициран, но без година на кредита (или с нечетима класификация): нито знаменател,
        // нито начало на срок. Броим го — пропускът е липсващ ВХОД, не липсваща корекция.
        missingFacts++;
        continue;
      }

      // Чл. 79а ал. 7 / чл. 79б ал. 6 — активът е напуснал и чл. 79 вече го е поел.
      if (hasArt79WriteOff(asset, year)) {
        lockedByArt79++;
        continue;
      }

      Set<Integer> suspended = suspendedYearsBefore(asset, year);
      int assetStart = termStartYear(asset, kind);

      // ⚠️ ЕДИН АКТИВ, N НОСИТЕЛЯ. Придобиването плюс по един на всяко подобрение — чл. 79а ал. 9
      // казва дословно „се извършват ОТДЕЛНИ корекции“, а ППЗДДС чл. 66 ал. 5 иска скаларни
      // реквизити. Сумирани в един ред, двата носителя биха дали един непопълним протокол.
      for (Carrier carrier : carriersOf(asset, kind, suspended)) {
        carriersSeen++;
        if (carrier.regime() == Regime.NONE) {
          // Разходът не поражда носител по ЗДДС — причината се записва, редът не се губи мълчаливо.
          BgVatAnnualCorrectionLine marked = findOrCreateLine(correction, asset, carrier);
          if (marked.getCorrectionMove() == null) {
            snapshotCarrier(marked, asset, carrier);
            marked.setCorrectionAmount(BigDecimal.ZERO);
            marked.setLegalGround(null);
            marked.setRefusalReason(carrier.refusalReason());
            lineRepository.save(marked);
            refused++;
          }
          continue;
        }
        if (!inWindow(carrier, assetStart, kind, suspended, year)) {
          outOfWindow++;
          continue;
        }

        BgVatAnnualCorrectionLine line = findOrCreateLine(correction, asset, carrier);
        if (line.getCorrectionMove() != null) {
          // Вече осчетоводен ред — не се пипа.
          continue;
        }
        snapshotCarrier(line, asset, carrier);
        defaultYearFacts(line, company, year);
        recompute(line);
        lineRepository.save(line);

        if (line.getRefusalReason() != null && !line.getRefusalReason().trim().isEmpty()) {
          refused++;
        } else {
          proposed++;
        }
      }
    }

    int unclassified = countUnclassifiedAssets(company);

    notes.add("Прегледани държани активи: " + assets.size());
    notes.add(
        "⚠️ Извън обхождането — без класификация по чл. 79 ал. 3 (празно поле „Класификация“ на"
            + " актива): "
            + unclassified);
    notes.add(
        "⚠️ Пропуснати — с класификация, но без година на упражняване на данъчния кредит: "
            + missingFacts);
    notes.add("Прегледани носители на начислен ДДС: " + carriersSeen);
    notes.add("Предложени редове: " + proposed);
    notes.add("Отказани (с обявена причина): " + refused);
    notes.add(
        "Извън прозореца по чл. 80 ал. 1 т. 3/т. 4 или преди началото на срока: " + outOfWindow);
    notes.add("Поети от чл. 79 (отписване през годината, ал. 7): " + lockedByArt79);
    correction.setBgBoundaryReport(String.join("\n", notes));
    return correctionRepository.save(correction);
  }

  /** Шапката за (дружество, година) — една и съща при всяко повторно предлагане. */
  protected BgVatAnnualCorrection findOrCreate(Company company, int year) {
    BgVatAnnualCorrection existing =
        correctionRepository
            .all()
            .filter("self.company = :c AND self.year = :y")
            .bind("c", company)
            .bind("y", year)
            .fetchOne();
    if (existing != null) {
      return existing;
    }
    BgVatAnnualCorrection created = new BgVatAnnualCorrection();
    created.setCompany(company);
    created.setYear(year);
    created.setStatusSelect(STATUS_DRAFT);
    return correctionRepository.save(created);
  }

  /**
   * Редът за ЕДИН носител на актива.
   *
   * <p>⚠️ Търси се по {@code carrierKey}, а не по {@code subsequentCost}. PostgreSQL НЕ дедуплицира
   * NULL-и в уникално ограничение, тъй че ключ върху нулевия разход би оставил точно реда за
   * ПРИДОБИВАНЕТО — най-честият — незащитен срещу дубликат. Низовият ключ винаги има стойност.
   */
  protected BgVatAnnualCorrectionLine findOrCreateLine(
      BgVatAnnualCorrection correction, FixedAsset asset, Carrier carrier) {
    String key = carrierKey(carrier);
    BgVatAnnualCorrectionLine existing =
        lineRepository
            .all()
            .filter("self.annualCorrection = :c AND self.fixedAsset = :a AND self.carrierKey = :k")
            .bind("c", correction)
            .bind("a", asset)
            .bind("k", key)
            .fetchOne();
    if (existing != null) {
      return existing;
    }
    BgVatAnnualCorrectionLine created = new BgVatAnnualCorrectionLine();
    created.setAnnualCorrection(correction);
    created.setFixedAsset(asset);
    created.setCarrierKey(key);
    return created;
  }

  /** „ACQUISITION“ за носителя при придобиване, „COST:&lt;id&gt;“ за всяко подобрение. */
  public static String carrierKey(Carrier carrier) {
    Long costId = carrier.subsequentCostId();
    return costId == null ? "ACQUISITION" : "COST:" + costId;
  }

  /**
   * Носителите на актива — придобиването плюс по един на всеки последващ разход.
   *
   * <p>Спрените години се подават веднъж: ал. 8 говори за „стоката, съответно недвижимия имот“, тъй
   * че спирането е на АКТИВА и важи за всички негови носители едновременно.
   */
  protected List<Carrier> carriersOf(FixedAsset asset, AssetKind kind, Set<Integer> suspended) {
    BgArt79aCarriers.AssetFacts assetFacts =
        new BgArt79aCarriers.AssetFacts(
            kind,
            creditTypeOf(asset),
            asset.getBgVatChargedOnAcquisition(),
            asset.getBgVatCreditYear(),
            asset.getBgVatFirstUseYear() == 0 ? null : asset.getBgVatFirstUseYear(),
            nullIfZero(asset.getBgVatCoefficientK0()),
            nullIfZero(asset.getBgVatProportionNid0()));

    List<BgArt79aCarriers.CostFacts> costs = new ArrayList<>();
    for (BgSubsequentCost cost : subsequentCostsOf(asset)) {
      costs.add(
          new BgArt79aCarriers.CostFacts(
              cost.getId(),
              cost.getBgVatIsImprovement(),
              cost.getBgVatAssetIsBuilding(),
              cost.getBgVatResultsInNewBuilding(),
              kindOfCost(cost, kind),
              creditType(cost.getBgVatCreditTypeSelect()),
              cost.getBgVatChargedOnCost(),
              cost.getBgVatCreditYear(),
              cost.getBgVatFirstUseYear() == 0 ? null : cost.getBgVatFirstUseYear(),
              nullIfZero(cost.getBgVatCoefficientK0()),
              nullIfZero(cost.getBgVatProportionNid0())));
    }
    return BgArt79aCarriers.of(assetFacts, costs, suspended);
  }

  protected List<BgSubsequentCost> subsequentCostsOf(FixedAsset asset) {
    return subsequentCostRepository
        .all()
        .filter("self.fixedAsset = :a")
        .bind("a", asset)
        .order("id")
        .fetch();
  }

  /** Класификацията на носителя на подобрението; празна → наследява тази на актива. */
  protected AssetKind kindOfCost(BgSubsequentCost cost, AssetKind assetKind) {
    return assetKind;
  }

  /**
   * В прозореца ли е годината — за носителя по чл. 67 ал. 4 границата е ЧУЖДА.
   *
   * <p>Придобиването и новата сграда по ал. 9 имат свой пълен срок; носителят по ППЗДДС чл. 67 ал.
   * 4 язди остатъка от срока на ЗАВАРЕНИЯ актив, защото ал. 10 му отказва нов.
   */
  protected boolean inWindow(
      Carrier carrier, int assetStart, AssetKind kind, Set<Integer> suspended, int year) {
    if (carrier.regime() == Regime.REMAINDER_ART67_4) {
      return BgArt79aCorrectionWindow.containsWithinIncumbentTerm(
          carrier.startYear(), assetStart, kind.periodYears(), suspended, year);
    }
    return BgArt79aCorrectionWindow.contains(
        carrier.startYear(), carrier.periodYears(), suspended, year);
  }

  // ------------------------------------------------------------------ фактите

  /**
   * Държаните активи на дружеството — заведените и амортизиращите, но не черновите и не отписаните.
   *
   * <p>Ал. 1 говори за актив, който лицето ПОЛЗВА през годината; чернова още не е заведена, а
   * прехвърленият вече е излязъл и се поема от чл. 79.
   *
   * <p>⚠️ <b>ПОРОДЕНИТЕ ПО ЗКПО ЧЛ. 64 АКТИВИ СЕ ИЗКЛЮЧВАТ — инак корекцията излиза ДВА ПЪТИ.</b>
   * Чл. 64 ал. 1 ЗКПО завежда последващия разход като САМОСТОЯТЕЛЕН данъчен амортизируем актив, но
   * за ЗДДС носителят си остава на заварения имот: чл. 79а ал. 9 т. 3 и ППЗДДС чл. 67 ал. 4 говорят
   * за срока на СЪЩИЯ актив. Обходен и като отделен актив, и като носител на подобрението, същият
   * начислен данък би се коригирал два пъти — и двата реда биха изглеждали правдоподобно.
   */
  protected List<FixedAsset> heldAssets(Company company, int year) {
    return fixedAssetRepository
        .all()
        .filter(HELD_ASSETS_FILTER)
        .bind("c", company)
        .bind("validated", FixedAssetRepository.STATUS_VALIDATED)
        .bind("depreciated", FixedAssetRepository.STATUS_DEPRECIATED)
        .fetch();
  }

  /**
   * Държаните активи БЕЗ класификация по ЗДДС — числото, което прави нулата четима.
   *
   * <p>⚠️ Брои се ПРИСЪСТВИЕ на празно поле, не отсъствие на ред: активът може да е напълно редовен
   * и просто да не носи начислен ДДС при придобиването. Затова числото е граница в отчета, а не
   * предупреждение — съставителят решава кое от двете е.
   */
  protected int countUnclassifiedAssets(Company company) {
    return (int)
        fixedAssetRepository
            .all()
            .filter(UNCLASSIFIED_ASSETS_FILTER)
            .bind("c", company)
            .bind("validated", FixedAssetRepository.STATUS_VALIDATED)
            .bind("depreciated", FixedAssetRepository.STATUS_DEPRECIATED)
            .count();
  }

  /**
   * Началото на срока — двете начала по ал. 3, според това дали кредитът е упражнен.
   *
   * <p>⚠️ {@code bgVatCreditYear} носи ЕДНО от двете: годината на упражняване на кредита, а когато
   * кредит не е упражняван (буква „а“) — годината, през която изтича срокът по чл. 72 ал. 1. Двете
   * са взаимно изключващи се по конструкция и видът кредит казва кое от тях е налице, тъй че едно
   * поле е честно. Второ поле би позволило и двете да са попълнени, без нищо да казва кое важи.
   */
  protected int termStartYear(FixedAsset asset, AssetKind kind) {
    int anchor = asset.getBgVatCreditYear();
    Integer firstUse = asset.getBgVatFirstUseYear() == 0 ? null : asset.getBgVatFirstUseYear();
    boolean creditTaken = creditTypeOf(asset) != CreditType.NONE;
    return creditTaken
        ? BgArt79aCorrectionWindow.periodStartYear(kind, anchor, 0, firstUse)
        : BgArt79aCorrectionWindow.periodStartYear(kind, 0, anchor, firstUse);
  }

  /**
   * Спрелите години ПРЕДИ тази — четат се от собствените редове на актива.
   *
   * <p>Годишният процес минава през всяка година от прозореца, тъй че редовете за актива вече са
   * пълният му летопис. Оттам и че спирането няма своя таблица: същият факт на две места може да си
   * противоречи.
   */
  protected Set<Integer> suspendedYearsBefore(FixedAsset asset, int year) {
    List<BgVatAnnualCorrectionLine> previous =
        lineRepository
            .all()
            .filter(
                "self.fixedAsset = :a AND self.isSuspendedYear = TRUE"
                    + " AND self.annualCorrection.year < :y")
            .bind("a", asset)
            .bind("y", year)
            .fetch();
    Set<Integer> years = new HashSet<>();
    for (BgVatAnnualCorrectionLine line : previous) {
      years.add(line.getAnnualCorrection().getYear());
    }
    return years;
  }

  /**
   * Има ли отписване по чл. 79 през същата година — <b>чл. 79а ал. 7 / чл. 79б ал. 6</b>.
   *
   * <p>Дословно: „За календарната година, през която са настъпили обстоятелствата по ал. 1,
   * корекция по този член НЕ СЕ ИЗВЪРШВА за стоки … които са или биха били дълготрайни активи,
   * когато не са налични към края на тази календарна година и са възникнали обстоятелства за
   * извършване на корекция по чл. 79.“
   *
   * <p>⚠️ Без тази проверка активът би получил И годишна корекция за 1/n, И еднократна по чл. 79
   * ал. 3 за целия остатък — двойно за една и съща година.
   */
  protected boolean hasArt79WriteOff(FixedAsset asset, int year) {
    return writeOffRepository
            .all()
            .filter("self.fixedAsset = :a AND self.taxYear = :y")
            .bind("a", asset)
            .bind("y", year)
            .count()
        > 0;
  }

  /**
   * Снима фактите на НОСИТЕЛЯ върху реда — те не се четат наживо при печат.
   *
   * <p>Активът се преоценява, подобрява и мени вид използване; протоколът за дадена година трябва
   * да покаже числата, с които е бил съставен. Записва се и знаменателят, и остатъкът R — те са
   * параметрите, по които решението по ППЗДДС чл. 67 ал. 4 е проверимо отвън.
   */
  protected void snapshotCarrier(
      BgVatAnnualCorrectionLine line, FixedAsset asset, Carrier carrier) {
    line.setSubsequentCost(subsequentCostRef(carrier));
    line.setCarrierRegimeSelect(carrier.regime().name());
    line.setAssetKindSelect(
        carrier.kind() == null ? asset.getBgVatAssetKindSelect() : carrier.kind().name());
    line.setCreditTypeSelect(carrier.creditType() == null ? null : carrier.creditType().name());
    line.setVatCharged(carrier.vatCharged());
    line.setPeriodYears(carrier.periodYears());
    line.setRemainingYears(carrier.remainingYears());
    line.setTermStartYear(carrier.startYear());
    line.setK0(carrier.k0());
    line.setProportion0(carrier.proportion0());
  }

  protected BgSubsequentCost subsequentCostRef(Carrier carrier) {
    Long id = carrier.subsequentCostId();
    return id == null ? null : subsequentCostRepository.find(id);
  }

  /**
   * Подразбирането за фактите на ГОДИНАТА — и то е удобство, не източник на истина.
   *
   * <p>Смесено използване и ПрНИДх = 1 са обичайният случай; чистите случаи по чл. 79в т. 1/2 и
   * пропорционалното ползване са преценка, която счетоводителят обявява сам.
   */
  protected void defaultYearFacts(BgVatAnnualCorrectionLine line, Company company, int year) {
    if (line.getTaxableUseSelect() == null || line.getTaxableUseSelect().trim().isEmpty()) {
      line.setTaxableUseSelect(TaxableUse.MIXED.name());
    }
    if (line.getNidUseSelect() == null || line.getNidUseSelect().trim().isEmpty()) {
      line.setNidUseSelect(NidUse.ONLY_NID.name());
    }
    if (line.getKx().signum() == 0) {
      BigDecimal finalCoefficient = finalCoefficientFor(company, year);
      if (finalCoefficient != null) {
        line.setKx(finalCoefficient);
      }
    }
  }

  /**
   * Кх — ОКОНЧАТЕЛНИЯТ коефициент по <b>чл. 73 ал. 6</b> за годината на изменението.
   *
   * <p>⚠️ НЕ прогнозният по ал. 5 ({@code BgVatCoefficient.coefficient}), който пълни кл.33 през
   * цялата година. Определенията под чл. 79а ал. 3 искат „коефициентът по чл. 73 за ГОДИНАТА на
   * настъпване на изменението“, а корекцията се прави в последния данъчен период на същата година —
   * тоест след преизчисляването по ал. 6. Взет прогнозният, Кх щеше да описва оборота на
   * ПРЕДХОДНАТА година.
   *
   * @return окончателният коефициент, или {@code null}, когато още не е преизчислен
   */
  protected BigDecimal finalCoefficientFor(Company company, int year) {
    BgVatCoefficient row =
        coefficientRepository
            .all()
            .filter("self.company = :c AND self.year = :y")
            .bind("c", company)
            .bind("y", year)
            .fetchOne();
    if (row == null || row.getFinalCoefficient().signum() == 0) {
      return null;
    }
    return row.getFinalCoefficient();
  }

  // ------------------------------------------------------------------ изчисляване

  /**
   * Преизчислява ЕДИН ред от собствените му снимани входове.
   *
   * <p>Извиква се и от обхождането, и от бутона „Преизчисли“ след ръчна намеса в Кх или в
   * използването. Отказите се записват като ПРИЧИНА върху реда, а не като изключение: празен ред
   * без причина е неразличим от „няма корекция“.
   */
  public void recompute(BgVatAnnualCorrectionLine line) {
    line.setRefusalReason(null);

    if (line.getIsSuspendedYear()) {
      line.setCorrectionAmount(BigDecimal.ZERO);
      line.setLegalGround(null);
      line.setAppliedKx(null);
      line.setAppliedProportionX(null);
      line.setRefusalReason(
          "Чл. 79а ал. 8 / чл. 79б ал. 7 — през годината активът не се използва за дейностите по"
              + " чл. 69 и 70: срокът спира да тече и корекция за нея не се извършва.");
      return;
    }

    AssetKind kind = AssetKind.valueOf(line.getAssetKindSelect());
    CreditType creditType = creditType(line.getCreditTypeSelect());
    TaxableUse taxableUse = TaxableUse.valueOf(line.getTaxableUseSelect());
    NidUse nidUse =
        line.getNidUseSelect() == null || line.getNidUseSelect().trim().isEmpty()
            ? null
            : NidUse.valueOf(line.getNidUseSelect());

    if (taxableUse == TaxableUse.MIXED && line.getKx().signum() == 0) {
      line.setCorrectionAmount(BigDecimal.ZERO);
      line.setLegalGround(null);
      line.setAppliedKx(null);
      line.setAppliedProportionX(null);
      line.setRefusalReason(
          "Няма окончателен коефициент по чл. 73 ал. 6 за "
              + line.getAnnualCorrection().getYear()
              + " — Кх не може да се определи. Въведете го в Коефициенти чл.73 или обявете чист"
              + " случай по чл. 79в т. 1/т. 2.");
      return;
    }

    Input in =
        new Input(
            kind,
            creditType,
            line.getVatCharged(),
            line.getAnnualCorrection().getYear(),
            nullIfZero(line.getK0()),
            nullIfZero(line.getProportion0()),
            taxableUse,
            nullIfZero(line.getKx()),
            nidUse,
            nullIfZero(line.getProportionX()),
            false,
            false,
            false,
            // ⚠️ ЗНАМЕНАТЕЛЯТ Е СНИМАН НА РЕДА, не изведен наново от класификацията. При
            // носителя по ППЗДДС чл. 67 ал. 4 той пак е законовият (20/5), но е обявен явно,
            // за да е четим в протокола и обратим при официално становище с другия прочит.
            line.getPeriodYears() == 0 ? null : line.getPeriodYears());

    try {
      Result result = BgArt79aAnnualCorrectionRules.correction(in);
      line.setCorrectionAmount(result.amount());
      line.setLegalGround(result.legalGround());
      // ⚠️ Приложеното се пише в СВОИТЕ полета, не върху въведеното: закованият по чл. 79в 1 или 0
      // би изял коефициента на счетоводителя и връщането към „смесено“ би смятало с него.
      line.setAppliedKx(result.appliedKx());
      line.setAppliedProportionX(result.appliedProportionX());
      if (result.refusalReason() != null) {
        line.setRefusalReason(result.refusalReason());
      }
      if (!result.canBeSkipped()) {
        // Намалението е задължително — отметката по ал. 6 не важи за него.
        line.setIsSkippedUnderPara6(false);
      }
    } catch (IllegalArgumentException e) {
      line.setCorrectionAmount(BigDecimal.ZERO);
      line.setLegalGround(null);
      line.setAppliedKx(null);
      line.setAppliedProportionX(null);
      line.setRefusalReason(e.getMessage());
    }
  }

  /** {@code InvoiceLine}-подобният код на вида кредит — празното значи „не е приспадан“. */
  protected CreditType creditType(String value) {
    if (value == null || value.trim().isEmpty()) {
      return CreditType.NONE;
    }
    return CreditType.valueOf(value);
  }

  /**
   * Класификацията и видът кредит се четат от ЕДНО място — {@link BgArt79Acquisition}.
   *
   * <p>⚠️ Двата потока по чл. 79 (отписване и годишни) четяха един и същи актив през два отделни
   * преписа на едно и също правило. Разминаването не е хипотетично: четецът на отписването
   * подразбира вида кредит от покупната фактура, а този тук не го правеше — един и същи актив
   * можеше да е „частично приспаднат“ при брак и „без кредит“ през декември.
   */
  protected AssetKind kindOf(FixedAsset asset) {
    return BgArt79Acquisition.kindOf(asset);
  }

  protected CreditType creditTypeOf(FixedAsset asset) {
    return BgArt79Acquisition.creditTypeOf(asset);
  }

  /**
   * Нула значи „непопълнено“ за генерираните getter-и.
   *
   * <p>⚠️ Axelor връща {@code ZERO}, не {@code null}, тъй че разликата между „нула“ и „няма
   * стойност“ се прави тук — виж {@code BgDefaultedGetterTest}.
   */
  protected static BigDecimal nullIfZero(BigDecimal value) {
    return value == null || value.signum() == 0 ? null : value;
  }
}
