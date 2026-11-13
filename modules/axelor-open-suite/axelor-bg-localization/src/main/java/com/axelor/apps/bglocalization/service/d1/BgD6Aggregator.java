package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ФИШОВЕТЕ НА ЕДНО ДРУЖЕСТВО → ЕДНО ЧИСЛО НА ФОНД. Чиста функция: без база, без Guice.
 *
 * <p><b>⚠️ СУМИТЕ СЕ ЧЕТАТ ОТ ПЕРАТА, А НЕ СЕ СМЯТАТ ОТ БАЗА × СТАВКА.</b> Заданието остави двата
 * пътя отворени; ето защо е избран първият.
 *
 * <ul>
 *   <li><b>Числото вече е сметнато ВЕДНЪЖ и е ЗАКРЪГЛЕНО.</b> § 2 ал. 2 иска закръгляне ПООТДЕЛНО
 *       за всяка вноска и за всеки платец; резолверът го прави при създаването на перото. Сметка
 *       {@code база × ставка / 100} тук е ВТОРО закръгляне на друго ниво и дава друга стотинка — на
 *       златния фиш разликата е между 15,15 и 15,14.
 *   <li><b>Ставката на Д1 е СБОР на ставки.</b> Т. 22 носи ЕДИН процент за четири фонда. Обратната
 *       сметка от нея не може да върне вноската по фонд, а Д6 иска точно нея — и то с ТЗПБ в друга
 *       точка от тази на Д1.
 *   <li><b>Равенството по чл. 2 ал. 4 трябва да е ПРОВЕРИМО, не гарантирано по устройство.</b> Ако
 *       Д6 се смяташе от базата и ставките на Д1, двете декларации щяха да са едно и също число,
 *       написано два пъти — тоест равенството би било тъждество и нямаше да пази нищо. Прочетени от
 *       перата, те са ДВА независими пътя до едно число, и разликата им Е находка.
 * </ul>
 *
 * <p><b>⚠️ ФИЛТЪРЪТ НА КОРЕКЦИЯТА Е ЗАДЪЛЖИТЕЛЕН И ТУК</b> ({@link BgPaySlipResolver#isOwnPeriod})
 * — четвъртият агрегат на декларациите. Делта-редът от ретро-корекция носи вноски за ДРУГ месец; те
 * вече са декларирани с коригиращата Д6 ЗА ОНЗИ МЕСЕЦ.
 *
 * <p><b>⚠️ И ЗНАКЪТ НЕ СЕ ПРИЛАГА, А СЕ ПРЕНЕБРЕГВА.</b> Указание т. 10 иска „сумата на ДЪЛЖИМИТЕ
 * осигурителни вноски“ — цялата вноска, независимо кой я носи. DEBIT значи „удържа се от лицето“,
 * не „отрицателна вноска“: сметка, която вади личната част, дава разликата между двете —
 * правдоподобно число на верния ред, което не е нито едната вноска, нито сборът им.
 */
public final class BgD6Aggregator {

  private BgD6Aggregator() {}

  /**
   * Сумите по точки на Д6 за подадените фишове: „точка → сбор в левове“.
   *
   * <p>Връща само точките, за които ИМА пера. Липсваща точка значи „нито един фиш не носи такава
   * вноска“ — различно от „вноската е нула“.
   */
  public static Map<String, BigDecimal> byPoint(List<BgPaySlip> slips) {
    return byPoint(slips, false);
  }

  /**
   * Същото, БЕЗ перата на обезщетенията по вид 27 ({@link BgCompensationParts#isPartLine}) —
   * вноските им влизат в Д6 по части ({@link BgD6CompensationLedger}), не върху цялата сума.
   */
  public static Map<String, BigDecimal> byPointWithoutCompensationParts(List<BgPaySlip> slips) {
    return byPoint(slips, true);
  }

  private static Map<String, BigDecimal> byPoint(
      List<BgPaySlip> slips, boolean withoutCompensationParts) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    if (slips == null) {
      return out;
    }
    for (BgPaySlip slip : slips) {
      if (slip == null) {
        continue;
      }
      for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
        if (line == null || !BgPaySlipResolver.isOwnPeriod(line)) {
          continue;
        }
        if (withoutCompensationParts && BgCompensationParts.isPartLine(line)) {
          continue;
        }
        int group = BgPaySlipResolver.groupOf(line);

        // ═══ ВНОСКИТЕ (5xx) — по ФОНД, не по точка от Д1 ═══
        if (group == BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
          String point = BgD6Point.forFund(fundCodeOf(line));
          if (point != null) {
            out.merge(point, line.getBgAmount(), BigDecimal::add);
          }
          continue;
        }

        // ═══ ДАНЪКЪТ (7xx) → т. 16 ═══
        // ⚠️ САМО DEBIT: групата 7xx носи удържания от лицето данък. Указание т. 16 иска „сумата на
        // УДЪРЖАНИЯ през съответния месец данък“ — тоест точно тези пера.
        if (group == BgPaySlipResolver.GROUP_TAX
            && BgPaySlipResolver.DEBIT.equals(line.getBgSign())) {
          out.merge(BgD6Point.T16_TAX, line.getBgAmount(), BigDecimal::add);
        }
      }
    }
    return Collections.unmodifiableMap(out);
  }

  /**
   * СБОРЪТ НА ТРИТЕ ЗДРАВНИ ТОЧКИ НА Д1 — за проверката по чл. 2 ал. 4.
   *
   * <p>⚠️ ВТОРИ, НЕЗАВИСИМ ПЪТ ДО СЪЩОТО ЧИСЛО, и точно затова съществува. {@link #byPoint} стига
   * до т. 14 през ФОНДА (всяко перо на HEALTH, независимо от базата); този метод стига дотам през
   * ТОЧКИТЕ НА Д1 (22.1 + 18 + 28). Двата пътя са различни и трябва да дадат едно число — ако не
   * дадат, някоя база е паднала извън трите и вноската ѝ е изчезнала от Д1, без да изчезне от Д6.
   *
   * <p>Това е и единствената проверка, която ХВАЩА погрешно написан дискриминатор: перо с база
   * „ART40_15“ (без подчертавка) не отива в нито една точка на Д1, а в Д6 влиза нормално.
   */
  public static BigDecimal healthViaD1Points(List<BgPaySlip> slips) {
    BigDecimal total = BigDecimal.ZERO;
    if (slips == null) {
      return total;
    }
    for (BgPaySlip slip : slips) {
      if (slip == null) {
        continue;
      }
      for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
        if (line == null || !BgPaySlipResolver.isOwnPeriod(line)) {
          continue;
        }
        if (BgPaySlipResolver.groupOf(line) != BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
          continue;
        }
        String d1Point = BgD1ContributionAggregator.pointOf(line);
        if (BgD1Point.T22_1_HEALTH.equals(d1Point)
            || BgD1Point.T18_HEALTH_ON_T17.equals(d1Point)
            || BgD1Point.T28_HEALTH_ON_T27.equals(d1Point)) {
          total = total.add(line.getBgAmount());
        }
      }
    }
    return total;
  }

  /**
   * СБОРЪТ НА ЧАСТИЧНИТЕ ПЛАЩАНИЯ през месеца — <b>КАНДИДАТЪТ за базата на Д6 т. 17</b>.
   *
   * <p><b>ДОСЛОВНО, ЗДДФЛ ЧЛ. 42 АЛ. 6:</b> „Когато през съответния месец са направени само
   * частични плащания за този или за друг месец, работодателят внася данъка по ал. 4, определен
   * върху БРУТНИЯ РАЗМЕР НА СУМАТА ОТ ЧАСТИЧНИТЕ ПЛАЩАНИЯ.“
   *
   * <p><b>⚠️ ТОВА НЕ Е Т. 17, А КАНДИДАТ ЗА НЕЯ, и разликата е цяла хипотеза.</b> Сумата тук е
   * определима — перата 600 („Аванс“) и 610 („Частично плащане по банков път“) са точно частичните
   * плащания. Определима НЕ е предпоставката: ал. 6 се прилага само когато през месеца са направени
   * САМО частични плащания, а ал. 8 я ИЗКЛЮЧВА, „когато пълният размер на начислените … доходи за
   * този месец е изплатен до 25-о число на СЛЕДВАЩИЯ месец“ — тоест факт от БЪДЕЩЕТО спрямо фиша.
   * Никаква сметка върху перата не може да го знае.
   *
   * <p>Затова сумата се ПОКАЗВА в находката, а точката остава за човека: той знае дали месецът е
   * приключил само с частични плащания. Попълнена автоматично, т. 17 щеше да носи данък, който при
   * плащане до 25-о число НЕ се дължи — и щеше да се приспадне два пъти по ал. 7.
   *
   * <p>⚠️ САМО DEBIT перата от 6xx: групата носи и перо 620 („доплащане по корекция за минал
   * период“), което е CREDIT и е НАЧИСЛЕНИЕ, не плащане.
   */
  public static BigDecimal partialPayments(List<BgPaySlip> slips) {
    BigDecimal total = BigDecimal.ZERO;
    if (slips == null) {
      return total;
    }
    for (BgPaySlip slip : slips) {
      if (slip == null) {
        continue;
      }
      for (BgPaySlipLine line : safe(slip.getBgPaySlipLineList())) {
        if (line == null || !BgPaySlipResolver.isOwnPeriod(line)) {
          continue;
        }
        if (BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_PAYMENTS
            && BgPaySlipResolver.DEBIT.equals(line.getBgSign())) {
          total = total.add(line.getBgAmount());
        }
      }
    }
    return total;
  }

  /** Кодът на фонда на едно перо. ⚠️ Първо перото, после типът — същият ред като при Д1. */
  static String fundCodeOf(BgPaySlipLine line) {
    BgContributionFund fund = line.getBgContributionFund();
    if (fund == null) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      fund = type == null ? null : type.getBgContributionFund();
    }
    return fund == null ? null : fund.getCode();
  }

  private static List<BgPaySlipLine> safe(List<BgPaySlipLine> lines) {
    return lines == null ? Collections.emptyList() : lines;
  }
}
