package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgAssetPlanLine;
import com.axelor.apps.bglocalization.db.BgAssetPlanReport;
import com.axelor.apps.bglocalization.db.BgSubsequentCost;
import com.axelor.apps.bglocalization.db.repo.BgAssetPlanReportRepository;
import com.axelor.apps.bglocalization.db.repo.BgSubsequentCostRepository;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * Строи годишния амортизационен план — данъчния (ДАП) или счетоводния.
 *
 * <p><b>Нищо не се пресмята наново.</b> Редовете идват от вече реализираните линии на актива,
 * построени от {@code BgFixedAssetLineTaxComputationServiceImpl} (WS-A2). Втора аритметика тук би
 * дала втори отговор на същия въпрос и щеше да се разминава точно в месеците, където чл. 58 ал. 1 и
 * чл. 60 ал. 2 режат.
 *
 * <p><b>И справката е ВЪТРЕШНА.</b> Чл. 92 ал. 3 ЗКПО иска към годишната декларация само „годишният
 * отчет за дейността“; копие от данъчния амортизационен план се подава единствено при преобразуване
 * на дружества (чл. 141 ал. 6). Тъй че тук няма layout задължения — виж {@code BgGddMapping}.
 */
public class BgAssetPlanReportService {

  /** Данъчният план. */
  public static final String PLAN_TAX = "TAX";

  /** Счетоводният план. */
  public static final String PLAN_ACCOUNTING = "ACCOUNTING";

  protected final FixedAssetRepository fixedAssetRepository;
  protected final BgAssetPlanReportRepository reportRepository;
  protected final BgSubsequentCostRepository subsequentCostRepository;

  @Inject
  public BgAssetPlanReportService(
      FixedAssetRepository fixedAssetRepository,
      BgAssetPlanReportRepository reportRepository,
      BgSubsequentCostRepository subsequentCostRepository) {
    this.fixedAssetRepository = fixedAssetRepository;
    this.reportRepository = reportRepository;
    this.subsequentCostRepository = subsequentCostRepository;
  }

  @Transactional(rollbackOn = {Exception.class})
  public BgAssetPlanReport compute(BgAssetPlanReport report) {
    // ⚠️ getTaxYear() е Integer getter и връща 0 за незададена колона, НЕ null — затова
    // проверката е за нула. Сравнението с null е мъртво условие и мълчи.
    if (report == null || report.getCompany() == null || report.getTaxYear() == 0) {
      return report;
    }
    int year = report.getTaxYear();
    boolean tax = !PLAN_ACCOUNTING.equals(report.getPlanSelect());

    if (report.getLineList() != null) {
      report.getLineList().clear();
    }
    for (FixedAsset asset : assetsOf(report.getCompany())) {
      LocalDate leftOn = leftPlanOn(asset, tax);
      if (leftOn != null && leftOn.getYear() < year) {
        continue; // изведен в предходна година — не е в плана
      }
      List<FixedAssetLine> lines =
          tax ? asset.getFiscalFixedAssetLineList() : asset.getFixedAssetLineList();
      BigDecimal charge = sum(lines, year, true, leftOn);
      BigDecimal accumulated = sum(lines, year, false, leftOn);
      if (charge.signum() == 0 && accumulated.signum() == 0 && leftOn == null) {
        continue;
      }
      addLine(report, asset, tax, year, lines, charge, accumulated);
      if (leftOn != null) {
        left(report, asset, tax, leftOn);
      }
    }
    sortByCategory(report);
    report.setComputedOn(LocalDate.now());
    summarise(report);
    return reportRepository.save(report);
  }

  protected void addLine(
      BgAssetPlanReport report,
      FixedAsset asset,
      boolean tax,
      int year,
      List<FixedAssetLine> lines,
      BigDecimal charge,
      BigDecimal accumulated) {
    BgAssetPlanLine line = new BgAssetPlanLine();
    line.setFixedAsset(asset);

    // Амортизируемата стойност: за данъчния план ДАС по чл. 53 ал. 1 (историческа цена, БЕЗ
    // приспадане на остатъчна стойност), за счетоводния — отчетната.
    BigDecimal base = tax ? BgSubsequentCostRules.amortizableValue(asset) : asset.getGrossValue();

    // ⚠️ ДВЕТЕ КОЛОНИ ВЕЧЕ НОСЯТ РАЗЛИЧНИ ЧИСЛА. По чл. 63 ДАС на наличен в ДАП актив се увеличава
    // с последващите разходи „от началото на месеца, през който са завършени“ — тъй че стойността
    // в началото на годината и в края ѝ се разминават точно с приложените през годината
    // увеличения. „В началото“ се извежда НАЗАД от текущата ДАС, вместо да се пази втора стойност,
    // която би могла да се разсинхронизира.
    BigDecimal increasedThisYear = tax ? art63IncreasesIn(asset, year) : BigDecimal.ZERO;
    line.setAmortizableValueAtStart(declared(base.subtract(increasedThisYear)));
    line.setAmortizableValueAtEnd(declared(base));
    line.setChargeForYear(declared(charge));
    line.setAccumulated(declared(accumulated));
    line.setCarryingValueAtEnd(declared(base.subtract(accumulated)));

    if (tax) {
      line.setBgTaxCategorySelect(asset.getBgTaxCategorySelect());
      line.setDepreciationRate(BgTaxDepreciation.effectiveRate(asset));
      line.setIsRetainedByArt60Al3(
          BgAssetWriteOffRules.isArt60Al3Excluded(asset) ? Boolean.TRUE : Boolean.FALSE);
      line.setIsSuspendedByArt59(asset.getBgTaxSuspensionFrom() != null);
      if (Boolean.TRUE.equals(line.getIsRetainedByArt60Al3())) {
        line.setNote(
            "Активът е отписан от счетоводния план, но ОСТАВА в данъчния — чл. 60 ал. 3 ЗКПО."
                + " Излиза от него по реда на ал. 1, при пълно данъчно амортизиране (ал. 4), тъй че"
                + " данъчната амортизация продължава.");
      } else if (Boolean.TRUE.equals(line.getIsSuspendedByArt59())) {
        line.setNote(
            "Начисляването е преустановено по чл. 59 ал. 1 от "
                + asset.getBgTaxSuspensionFrom()
                + " — активът НЕ се отписва от данъчния амортизационен план.");
      }
    }
    report.addLineListItem(line);
  }

  /**
   * Подрежда редовете по данъчна категория I–VII.
   *
   * <p>Подрежда се В JAVA, не с ORDER BY. Кодовете са низове („I“…„VII“), а сортирането в базата
   * зависи от collation-а — под en_US.UTF-8 и под C дава различен ред. Същият капан е назован в
   * {@code BgPaySlipLineType} сортирането.
   */
  protected void sortByCategory(BgAssetPlanReport report) {
    if (report.getLineList() == null) {
      return;
    }
    List<String> order = java.util.Arrays.asList("I", "II", "III", "IV", "V", "VI", "VII");
    report
        .getLineList()
        .sort(
            Comparator.comparingInt(
                (BgAssetPlanLine l) -> {
                  int i = order.indexOf(l.getBgTaxCategorySelect());
                  return i < 0 ? order.size() : i;
                }));
  }

  /**
   * Сборът на увеличенията по чл. 63, приложени върху този актив ПРЕЗ годината.
   *
   * <p>Брои се по {@code effectiveFrom}, не по датата на записа: чл. 63 изр. 2 връзва действието с
   * „началото на месеца, през който са завършени последващите разходи“, а не с това кога някой е
   * натиснал бутона.
   */
  protected BigDecimal art63IncreasesIn(FixedAsset asset, int year) {
    List<BgSubsequentCost> costs =
        subsequentCostRepository
            .all()
            .filter(
                "self.fixedAsset = :asset AND self.isApplied = TRUE"
                    + " AND self.routingSelect = :routing"
                    + " AND YEAR(self.effectiveFrom) = :year")
            .bind("asset", asset)
            .bind("routing", BgSubsequentCostRules.Routing.ART63)
            .bind("year", year)
            .fetch();
    BigDecimal total = BigDecimal.ZERO;
    for (BgSubsequentCost cost : costs) {
      total = total.add(cost.getAmount());
    }
    return total;
  }

  protected List<FixedAsset> assetsOf(Company company) {
    return fixedAssetRepository
        .all()
        .filter("self.company = :company")
        .bind("company", company)
        .fetch();
  }

  /**
   * Последният ден, в който активът е в плана, или null — още е в него.
   *
   * <p>Счетоводният план: датата на извеждане (пропорционалният ред на AOS е с нея). Данъчният: по
   * чл. 60 ал. 2 ЗКПО активът се отписва от ДАП „в началото на месеца, през който се отписва за
   * счетоводни цели“ — последният му ден е краят на ПРЕДХОДНИЯ месец. По ал. 3 (напълно амортизиран
   * за счетоводни цели, увеличен праг на същественост) остава в ДАП — тогава null.
   */
  protected LocalDate leftPlanOn(FixedAsset asset, boolean tax) {
    LocalDate disposal = asset.getDisposalDate();
    if (disposal == null) {
      return null;
    }
    if (!tax) {
      return disposal;
    }
    if (BgAssetWriteOffRules.isArt60Al3Excluded(asset)) {
      return null;
    }
    return disposal.withDayOfMonth(1).minusDays(1);
  }

  /**
   * Изведеният през годината актив: в края на годината не е в плана — амортизируемата стойност и
   * стойността в края са нула, а бележката казва кога е изведен.
   */
  protected void left(BgAssetPlanReport report, FixedAsset asset, boolean tax, LocalDate leftOn) {
    BgAssetPlanLine line = report.getLineList().get(report.getLineList().size() - 1);
    line.setAmortizableValueAtEnd(declared(BigDecimal.ZERO));
    line.setCarryingValueAtEnd(declared(BigDecimal.ZERO));
    String text =
        tax
            ? "Отписан от ДАП от началото на месеца на счетоводното отписване ("
                + asset.getDisposalDate()
                + ") — чл. 60 ал. 2 ЗКПО; данъчна амортизация до "
                + leftOn
                + "."
            : "Изведен на " + asset.getDisposalDate() + " — амортизация до датата на извеждане.";
    line.setNote(line.getNote() == null ? text : line.getNote() + " " + text);
  }

  /**
   * Сборът по РЕАЛИЗИРАНИ редове — за годината или до края ѝ.
   *
   * <p>Редовете след извеждането ({@link #leftPlanOn}) не се броят, дори да са останали реализирани
   * в регистъра.
   *
   * <p>Плановите редове са бъдеще. Сбор с тях би показал план, който още не е начислен, като вече
   * начислен — и точно това направи чл. 60 ал. 3 т. 1 да се прилага по погрешка при WS-A3.
   */
  protected BigDecimal sum(
      List<FixedAssetLine> lines, int year, boolean exactYear, LocalDate leftOn) {
    if (lines == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal total = BigDecimal.ZERO;
    for (FixedAssetLine line : lines) {
      if (line == null
          || line.getDepreciationDate() == null
          || line.getStatusSelect() != FixedAssetLineRepository.STATUS_REALIZED
          || (leftOn != null && line.getDepreciationDate().isAfter(leftOn))) {
        continue;
      }
      int y = line.getDepreciationDate().getYear();
      if (exactYear ? y == year : y <= year) {
        total = total.add(line.getDepreciation());
      }
    }
    return total;
  }

  protected void summarise(BgAssetPlanReport report) {
    BigDecimal start = BigDecimal.ZERO;
    BigDecimal charge = BigDecimal.ZERO;
    BigDecimal end = BigDecimal.ZERO;
    if (report.getLineList() != null) {
      for (BgAssetPlanLine line : report.getLineList()) {
        start = start.add(nz(line.getAmortizableValueAtStart()));
        charge = charge.add(nz(line.getChargeForYear()));
        end = end.add(nz(line.getCarryingValueAtEnd()));
      }
    }
    report.setTotalValueAtStart(start);
    report.setTotalCharge(charge);
    report.setTotalValueAtEnd(end);
  }

  /**
   * Свежда до два знака.
   *
   * <p>Паричните полета на актива и линиите са {@code scale="3"}, а тези на справката — {@code
   * scale="2"}; без изричното свеждане {@code @Digits} отхвърля записа с „numeric value out of
   * bounds“. Измерено при WS-A3 на регистъра.
   */
  protected BigDecimal declared(BigDecimal amount) {
    return nz(amount).setScale(2, RoundingMode.HALF_UP);
  }

  protected BigDecimal nz(BigDecimal v) {
    return v == null ? BigDecimal.ZERO : v;
  }
}
