package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.util.Iterator;
import java.util.function.Predicate;

/**
 * КОИ РЕДОВЕ СА НА ГЕНЕРАТОРА — общото правило на двата генератора, които трият.
 *
 * <p>Вноските 5xx ({@link BgContributionLineService}) и данъкът 700 ({@link BgIncomeTaxService})
 * махат ред, който СА създали и вече не произвеждат: отпаднал фонд, законна нула. Признакът е
 * {@code BgPaySlipLine.bgIsGenerated}, а не кодът: операторът може да добави 517 или 700 на ръка и
 * този ред не бива да изчезне при следващото пресмятане.
 *
 * <p><b>⚠️ ОСИНОВЯВАНЕТО Е САМО ЗА ЗАВАРЕНИЯ ФИШ.</b> Редовете, писани преди признака, го нямат.
 * Ако генераторът ги подмине, при първото пресмятане след внедряването всяка заварена чернова щеше
 * да получи ВТОРИ комплект вноски и втори данък — тихо, защото и заглавието, и перата щяха да ги
 * сумират еднакво. Затова, докато фишът няма отпечатък ({@code BgPaySlip.bgCalcFingerprint}),
 * генераторът взема заварения ред със своя код, по самия код. След първото пресмятане осиновяване
 * няма и ръчният ред остава ръчен.
 */
public final class BgGeneratedLines {

  private BgGeneratedLines() {}

  /** Фишът не е минавал през calculate() — заварените редове със същия код са на генератора. */
  public static boolean adoptsUnmarked(BgPaySlip slip) {
    String fingerprint = slip == null ? null : slip.getBgCalcFingerprint();
    return fingerprint == null || fingerprint.trim().isEmpty();
  }

  /**
   * Редът на генератора, който отговаря на ключа, или — само при заварен фиш — първият непознат ред
   * със същия ключ. {@code null}, ако няма нито един.
   *
   * <p>⚠️ Белязаният ред печели винаги: ръчен ред със същия код до него НЕ се пипа.
   */
  public static BgPaySlipLine find(BgPaySlip slip, Predicate<BgPaySlipLine> key) {
    if (slip == null || slip.getBgPaySlipLineList() == null) {
      return null;
    }
    BgPaySlipLine unmarked = null;
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (!key.test(line)) {
        continue;
      }
      if (Boolean.TRUE.equals(line.getBgIsGenerated())) {
        return line;
      }
      if (unmarked == null) {
        unmarked = line;
      }
    }
    return adoptsUnmarked(slip) ? unmarked : null;
  }

  /**
   * Маха БЕЛЯЗАНИТЕ редове за собствения период, които отговарят на {@code stale}. Ръчен ред — без
   * признак — не се маха никога, и ретро-делтата (перо с {@code bgCorrectsPeriod}) също: тя е пари
   * за друг месец и не е на нито един от двата генератора.
   */
  public static void removeStale(BgPaySlip slip, Predicate<BgPaySlipLine> stale) {
    if (slip == null || slip.getBgPaySlipLineList() == null) {
      return;
    }
    Iterator<BgPaySlipLine> it = slip.getBgPaySlipLineList().iterator();
    while (it.hasNext()) {
      BgPaySlipLine line = it.next();
      if (Boolean.TRUE.equals(line.getBgIsGenerated())
          && BgPaySlipResolver.isOwnPeriod(line)
          && stale.test(line)) {
        it.remove();
      }
    }
  }
}
