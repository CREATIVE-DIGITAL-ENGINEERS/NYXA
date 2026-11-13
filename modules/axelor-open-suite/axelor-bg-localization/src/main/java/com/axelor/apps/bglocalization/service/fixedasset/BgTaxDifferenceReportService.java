package com.axelor.apps.bglocalization.service.fixedasset;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgFixedAssetWriteOff;
import com.axelor.apps.bglocalization.db.BgTaxDifferenceLine;
import com.axelor.apps.bglocalization.db.BgTaxDifferenceReport;
import com.axelor.apps.bglocalization.db.repo.BgFixedAssetWriteOffRepository;
import com.axelor.apps.bglocalization.db.repo.BgTaxDifferenceReportRepository;
import com.google.inject.Inject;
import com.google.inject.persist.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Строи справката за данъчни временни разлики от амортизируеми активи за една година.
 *
 * <p>Смятането е в {@link BgTaxDifferenceRules} — тук е само обхождането и записът. Разделението е
 * нарочно: модулът няма mock-подпора, тъй че всяко правило, оставено в услуга, се проверява само на
 * живо.
 */
public class BgTaxDifferenceReportService {

  protected final FixedAssetRepository fixedAssetRepository;
  protected final BgFixedAssetWriteOffRepository writeOffRepository;
  protected final BgTaxDifferenceReportRepository reportRepository;

  @Inject
  public BgTaxDifferenceReportService(
      FixedAssetRepository fixedAssetRepository,
      BgFixedAssetWriteOffRepository writeOffRepository,
      BgTaxDifferenceReportRepository reportRepository) {
    this.fixedAssetRepository = fixedAssetRepository;
    this.writeOffRepository = writeOffRepository;
    this.reportRepository = reportRepository;
  }

  /**
   * Изчислява справката наново и я записва.
   *
   * <p>Редовете се трият и се строят отново, а не се допълват: справката е СНИМКА към момента на
   * изчисляване. Допълването би оставило редове от предишен прогон, които вече не отговарят на
   * данните — и разликата не би се видяла никъде.
   */
  @Transactional(rollbackOn = {Exception.class})
  public BgTaxDifferenceReport compute(BgTaxDifferenceReport report) {
    // ⚠️ getTaxYear() е Integer getter и връща 0 за незададена колона, НЕ null — затова
    // проверката е за нула. Сравнението с null е мъртво условие и мълчи.
    if (report == null || report.getCompany() == null || report.getTaxYear() == 0) {
      return report;
    }
    int year = report.getTaxYear();
    Company company = report.getCompany();

    if (report.getLineList() != null) {
      report.getLineList().clear();
    }
    addArt54Lines(report, company, year);
    addArt66Lines(report, company, year);
    addArt59Lines(report, company, year);
    addArt60Al3Lines(report, company, year);

    report.setComputedOn(LocalDate.now());
    summarise(report);
    return reportRepository.save(report);
  }

  /**
   * Чл. 54 — годишната разлика per актив.
   *
   * <p>При подразбирането на модула (счетоводен план = данъчен) разликата е НУЛА за всеки актив, и
   * нулевите редове НЕ се записват: справка от сто нули не се чете. Ненулева е само когато
   * счетоводният полезен срок е избран различен от данъчния.
   */
  protected void addArt54Lines(BgTaxDifferenceReport report, Company company, int year) {
    for (FixedAsset asset : assetsOf(company)) {
      BigDecimal accounting = chargedInYear(asset.getFixedAssetLineList(), year);
      BigDecimal tax = chargedInYear(asset.getFiscalFixedAssetLineList(), year);
      if (accounting.signum() == 0 && tax.signum() == 0) {
        // Актив без НИКАКВО начисляване през годината не пълни справката с нули.
        continue;
      }
      if (accounting.signum() != 0) {
        BgTaxDifferenceLine line =
            line(report, asset, BgTaxDifferenceRules.Kind.ART54_ACCOUNTING, accounting, year);
        line.setLegalGround(
            "Чл. 54 ал. 2 ЗКПО: „Счетоводните разходи за амортизации не се признават за данъчни"
                + " цели. При определяне на данъчния финансов резултат счетоводният финансов"
                + " резултат се увеличава със счетоводните амортизации, независимо от това дали"
                + " отчитането им води до намаляване на счетоводния финансов резултат за годината“ →"
                + " "
                + BgGddMapping.ART54_ACCOUNTING.label());
      }
      if (tax.signum() != 0) {
        BgTaxDifferenceLine line =
            line(report, asset, BgTaxDifferenceRules.Kind.ART54_TAX, tax, year);
        line.setLegalGround(
            "Чл. 54 ал. 1 ЗКПО: „При определяне на данъчния финансов резултат се признават"
                + " годишните данъчни амортизации, определени по реда на тази глава“ → "
                + BgGddMapping.ART54_TAX.label());
      }
    }
  }

  /**
   * Чл. 66 — +СБС и −ДБС, всяко в своята година.
   *
   * <p>Два отделни прохода, защото питат различни неща. Плюсът се чете от записите в регистъра с
   * тази данъчна година. Минусът — само когато активът наистина е излязъл от ДАП: или защото чл. 60
   * ал. 2 се е приложил при отписването, или защото данъчният план е приключил ПРЕЗ ТАЗИ година при
   * актив, задържан по чл. 60 ал. 3 (ал. 4).
   */
  protected void addArt66Lines(BgTaxDifferenceReport report, Company company, int year) {
    for (BgFixedAssetWriteOff writeOff : writeOffsOf(company, year)) {
      BigDecimal increase = BgTaxDifferenceRules.art66Increase(writeOff);
      if (increase.signum() != 0) {
        BgTaxDifferenceLine line =
            line(
                report,
                writeOff.getFixedAsset(),
                BgTaxDifferenceRules.Kind.ART66_AL1,
                increase,
                year);
        boolean deferred = !Boolean.TRUE.equals(writeOff.getIsArt60Al2Applied());
        line.setLegalGround(
            "Чл. 66 ал. 1 ЗКПО: „Когато активът се отписва от счетоводния амортизационен план…"
                + " счетоводният финансов резултат се увеличава със счетоводната балансова стойност"
                + " на актива.“ → "
                + BgGddMapping.ART66_INCREASE.label()
                + (deferred
                    ? " ⚠️ Намалението с ДБС по ал. 2 НЕ идва в тази година: активът е задържан в"
                        + " ДАП по чл. 60 ал. 3 и излиза по реда на ал. 1 при пълно ДАНЪЧНО"
                        + " амортизиране (ал. 4). Очаквайте го в годината, в която данъчният план"
                        + " приключи."
                    : ""));
      }
      BigDecimal decrease = BgTaxDifferenceRules.art66Decrease(writeOff);
      if (decrease.signum() != 0) {
        BgTaxDifferenceLine line =
            line(
                report,
                writeOff.getFixedAsset(),
                BgTaxDifferenceRules.Kind.ART66_AL2,
                decrease,
                year);
        line.setLegalGround(
            "Чл. 66 ал. 2 ЗКПО: „Когато активът се отписва от данъчния амортизационен план…"
                + " счетоводният финансов резултат се намалява с данъчната стойност на актива.“ → "
                + BgGddMapping.ART66_DECREASE.label());
      }
    }
    addDeferredArt66Decrease(report, company, year);
  }

  /**
   * −ДБС за активите, задържани в ДАП по чл. 60 ал. 3, чийто ДАНЪЧЕН план приключва точно сега.
   *
   * <p>⚠️ Без този проход намалението изчезва завинаги. Записът в регистъра носи данъчната година
   * на СЧЕТОВОДНОТО отписване, тъй че при изключение по ал. 3 той никога не съвпада с годината, в
   * която активът излиза от ДАП — а именно тя е годината на ал. 2.
   */
  protected void addDeferredArt66Decrease(BgTaxDifferenceReport report, Company company, int year) {
    for (BgFixedAssetWriteOff writeOff : writeOffsBefore(company, year)) {
      if (Boolean.TRUE.equals(writeOff.getIsArt60Al2Applied())
          || !BgTaxDifferenceRules.applies(writeOff)) {
        continue;
      }
      FixedAsset asset = writeOff.getFixedAsset();
      if (!BgTaxDifferenceRules.taxPlanFinishedInYear(asset, year)) {
        continue;
      }
      BigDecimal dbs = writeOff.getTaxValue();
      if (dbs.signum() == 0) {
        continue;
      }
      BgTaxDifferenceLine line =
          line(report, asset, BgTaxDifferenceRules.Kind.ART66_AL2, dbs, year);
      line.setLegalGround(
          "Чл. 66 ал. 2 ЗКПО, отложено по чл. 60 ал. 3/ал. 4: активът е отписан счетоводно през"
              + " "
              + writeOff.getTaxYear()
              + " г., но е останал в данъчния амортизационен план и излиза от него сега — при"
              + " пълно данъчно амортизиране.");
    }
  }

  /**
   * Чл. 59 ал. 2 — потвърдените обръщания и ВИДИМИТЕ редове, които чакат потвърждение.
   *
   * <p>⚠️ Непотвърденото се ПОКАЗВА, не се пропуска. Забравено потвърждение е пропусната
   * задължителна корекция, тоест по-нисък данъчен резултат от дължимия — скрит данъчен пасив.
   * Тихото пропускане прави дупката невидима; същата асиметрия, по която ДДС гардрейлът отказва да
   * свали корекция без доказателство.
   *
   * <p>Годината е {@code bgArt59Al2Year} и НЕ е годината на потвърждаване: ал. 2 казва „за
   * годината, през която е изтекъл дванадесетмесечният срок“. Потвърждаване след подадена за нея
   * декларация значи КОРИГИРАЩА — редът го маркира.
   */
  protected void addArt59Lines(BgTaxDifferenceReport report, Company company, int year) {
    for (FixedAsset asset : assetsOf(company)) {
      Integer reversalYear = BgTaxDifferenceRules.art59Year(asset);
      if (reversalYear == null || reversalYear != year) {
        continue;
      }
      boolean pending = BgTaxDifferenceRules.isArt59Pending(asset);
      BigDecimal amount =
          pending ? asset.getBgArt59Al2Reversal() : BgTaxDifferenceRules.art59Reversal(asset);
      if (amount.signum() == 0) {
        continue;
      }
      BgTaxDifferenceLine line =
          line(
              report,
              asset,
              pending
                  ? BgTaxDifferenceRules.Kind.ART59_AL2_PENDING
                  : BgTaxDifferenceRules.Kind.ART59_AL2,
              amount,
              year);
      line.setIsPendingConfirmation(pending);
      line.setRequiresAmendedReturn(
          !pending
              && asset.getBgArt59Al2ConfirmedOn() != null
              && asset.getBgArt59Al2ConfirmedOn().getYear() > year);
      line.setLegalGround(
          "Чл. 59 ал. 2 ЗКПО: „При определянето на данъчния финансов резултат за годината, през"
              + " която е изтекъл дванадесетмесечният срок по ал. 1, годишната данъчна амортизация…"
              + " се намалява с размера на начислената данъчна амортизация за актива през"
              + " дванадесетте месеца, през които активът не се е използвал.“"
              + (pending
                  ? " ⚠️ ЧАКА ПОТВЪРЖДЕНИЕ. Докато не бъде потвърдено, намалението не е отразено —"
                      + " а то е задължително, тъй че пропускът занижава данъка."
                  : Boolean.TRUE.equals(line.getRequiresAmendedReturn())
                      ? " ⚠️ Потвърдено СЛЕД годината — иска КОРИГИРАЩА ГДД за " + year + " г."
                      : ""));
    }
  }

  /**
   * Чл. 60 ал. 3 т. 2 — информативен ред със сума НУЛА.
   *
   * <p>Нулата е смисълът: редът казва, че активът е излязъл от счетоводния план заради вдигнат
   * праг, но данъчната амортизация ПРОДЪЛЖАВА и няма прекъсване на плана. Липсата на ред е
   * неразличима от „няма такъв актив“.
   */
  protected void addArt60Al3Lines(BgTaxDifferenceReport report, Company company, int year) {
    for (FixedAsset asset : assetsOf(company)) {
      if (asset.getBgMaterialityIncrease() == null) {
        continue;
      }
      if (chargedInYear(asset.getFiscalFixedAssetLineList(), year).signum() == 0) {
        continue;
      }
      BgTaxDifferenceLine line =
          line(report, asset, BgTaxDifferenceRules.Kind.ART60_AL3_T2, BigDecimal.ZERO, year);
      // Видът на реда (ART60_AL3_T2) вече казва всичко; отделен флаг би повторил същото твърдение
      // на второ място и щеше да може да се разминава с него.
      line.setLegalGround(
          "Чл. 60 ал. 3 т. 2 ЗКПО: „Алинея 2 не се прилага при отписване на активи… в резултат на"
              + " увеличение в стойностния праг на същественост.“ Активът остава в данъчния"
              + " амортизационен план и се отписва от него по реда на ал. 1 (ал. 4) — данъчната"
              + " амортизация продължава да се начислява.");
    }
  }

  // ── помощни ──────────────────────────────────────────────────────────────

  protected List<FixedAsset> assetsOf(Company company) {
    return fixedAssetRepository
        .all()
        .filter("self.company = :company AND self.bgTaxDepreciationRate > 0")
        .bind("company", company)
        .fetch();
  }

  protected List<BgFixedAssetWriteOff> writeOffsOf(Company company, int year) {
    return writeOffRepository
        .all()
        .filter("self.company = :company AND self.taxYear = :year")
        .bind("company", company)
        .bind("year", year)
        .fetch();
  }

  protected List<BgFixedAssetWriteOff> writeOffsBefore(Company company, int year) {
    return writeOffRepository
        .all()
        .filter("self.company = :company AND self.taxYear < :year")
        .bind("company", company)
        .bind("year", year)
        .fetch();
  }

  /**
   * Начисленото за годината по РЕАЛИЗИРАНИ редове.
   *
   * <p>Плановите редове са бъдеще и не влизат в данъчния финансов резултат — същият капан свали чл.
   * 60 ал. 3 т. 1 при WS-A3.
   */
  protected BigDecimal chargedInYear(List<FixedAssetLine> lines, int year) {
    if (lines == null) {
      return BigDecimal.ZERO;
    }
    BigDecimal total = BigDecimal.ZERO;
    for (FixedAssetLine line : lines) {
      if (line == null
          || line.getDepreciationDate() == null
          || line.getStatusSelect() != FixedAssetLineRepository.STATUS_REALIZED
          || line.getDepreciationDate().getYear() != year) {
        continue;
      }
      total = total.add(line.getDepreciation());
    }
    return total;
  }

  protected BgTaxDifferenceLine line(
      BgTaxDifferenceReport report, FixedAsset asset, String kind, BigDecimal amount, int year) {
    BgTaxDifferenceLine line = new BgTaxDifferenceLine();
    line.setFixedAsset(asset);
    line.setKindSelect(kind);
    line.setAmount(amount.setScale(2, java.math.RoundingMode.HALF_UP));
    line.setTaxYear(year);
    report.addLineListItem(line);
    return line;
  }

  /**
   * Сборовете по посока — увеличения и намаления поотделно.
   *
   * <p>⚠️ Не се сумира „нето“. Посоката е свойство на ОСНОВАНИЕТО (чл. 66 ал. 1 увеличава, ал. 2
   * намалява), а декларацията иска двете страни поотделно. Едно нето число не отива в никоя колона.
   * Чл. 54 носи естествения си знак и се разпределя по него.
   */
  protected void summarise(BgTaxDifferenceReport report) {
    BigDecimal increase = BigDecimal.ZERO;
    BigDecimal decrease = BigDecimal.ZERO;
    int pending = 0;
    if (report.getLineList() != null) {
      for (BgTaxDifferenceLine line : report.getLineList()) {
        String kind = line.getKindSelect();
        BigDecimal amount = line.getAmount();
        if (Boolean.TRUE.equals(line.getIsPendingConfirmation())) {
          pending++;
          continue;
        }
        if (BgTaxDifferenceRules.Kind.ART66_AL1.equals(kind)
            || BgTaxDifferenceRules.Kind.ART54_ACCOUNTING.equals(kind)) {
          // Колона А: шифри 7020 и 7010.
          increase = increase.add(amount);
        } else if (BgTaxDifferenceRules.Kind.ART66_AL2.equals(kind)
            || BgTaxDifferenceRules.Kind.ART54_TAX.equals(kind)) {
          // Колона Б: шифри 8020 и 8010.
          decrease = decrease.add(amount);
        } else if (BgTaxDifferenceRules.Kind.ART59_AL2.equals(kind)) {
          // ⚠️ ИЗВАЖДА СЕ, не се прибавя — и това е поправка на посоката.
          // Чл. 59 ал. 2 казва, че „ГОДИШНАТА ДАНЪЧНА АМОРТИЗАЦИЯ … СЕ НАМАЛЯВА с размера на
          // начислената данъчна амортизация за актива през дванадесетте месеца". Тоест обръщането
          // не е самостоятелно намаление на финансовия резултат, а корекция ВЪТРЕ в числото за
          // шифър 8010: по-малко призната данъчна амортизация значи ПО-МАЛКО намаление и ПО-ВИСОК
          // данъчен резултат.
          //
          // Прибавено към намаленията (както беше), то увеличаваше намалението — тоест ЗАНИЖАВАШЕ
          // данъчния резултат с двойния размер на обръщането. Грешката е точно в опасната посока:
          // по-нисък деклариран данък, който не се вижда никъде.
          decrease = decrease.subtract(amount);
        }
      }
    }
    report.setTotalIncrease(increase);
    report.setTotalDecrease(decrease);
    report.setPendingCount(pending);
  }
}
