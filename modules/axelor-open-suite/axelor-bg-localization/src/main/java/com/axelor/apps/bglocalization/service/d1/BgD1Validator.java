package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * ПРАВИЛАТА НА НАРЕДБАТА КАТО ПРОВЕРКИ — по образеца на {@code BgVatValidator}.
 *
 * <p><b>Проверява САМО онова, което наредбата ДАВА.</b> Всяко правило тук е изречение от Н-13 с
 * посочен адрес. Правило без адрес би било наше мнение, облечено като изискване — и първото, което
 * ще спре верен фиш.
 *
 * <p><b>⚠️ НЕ ПРЕИЗЧИСЛЯВА.</b> Валидатор, който смята сам, е втори емитер: при разминаване не се
 * знае кой от двамата греши. Тук се проверяват СЛЕДИ — мащабът на ставката, наличието на стойност,
 * съвместимостта на точките — а не самите числа.
 *
 * <p><b>⚠️ И ЗАЩО § 2 АЛ. 2 НЕ Е ТУК.</b> „Вноската да е с два знака“ е следа от закръглянето по §
 * 2 ал. 2. Правилото е вярно, но НЕ ЗА ТОЗИ ЗАПИС: Д1 носи СТАВКИ (проценти), а § 2 ал. 2 говори за
 * закръгляне на ВНОСКИТЕ (пари). Левовите вноски живеят в Декларация обр. 6 и там правилото се
 * проверява ({@code BgD6Validator}). Оставено тук, то би гледало мащаба на процент и би минавало
 * зелено, докато никой не пази същинското.
 *
 * <p>На негово място стои правило за мащаба на СТАВКАТА (файлът дава на полета 35–47 размер ≤5.2) и
 * две нови проверки, които станаха възможни едва след разделянето по платец: еднополева точка с
 * ставка от ДВЕТЕ страни, и база в т. 21 без ставка в т. 22.
 */
@Singleton
public class BgD1Validator {

  /** Чл. 11 ал. 1 — без тези три декларацията не се приема изобщо. */
  public static final String CHL11_AL1 = "D1_CHL11_AL1_MISSING";

  /** Заб. към т. 12: при код 28 се попълват само единайсет точки. */
  public static final String CODE_28_EXTRA = "D1_CODE_28_EXTRA_FIELD";

  /** Заб. 3 към т. 16 + списъкът на т. 31: при код 90 се попълват само т. 31 и 31а. */
  public static final String CODE_90_EXTRA = "D1_CODE_90_EXTRA_FIELD";

  /** Заб. към т. 29/30: попълват се за лицата по ТРУДОВИ правоотношения. */
  public static final String GVRS_NOT_LABOUR = "D1_T30_NOT_LABOUR_RELATION";

  /** Полета 35–47 са „(процент)“ с размер ≤5.2 — ставка с трети знак не влиза във файла. */
  public static final String RATE_SCALE_TOO_LONG = "D1_RATE_SCALE_TOO_LONG";

  /**
   * Еднополева точка (22.2, 23, 25, 30) носи ставка И от осигурителя, И от лицето.
   *
   * <p>По норма точно една от двете страни е ненулева — т. 22.2 и т. 25 са „за сметка на
   * осигурителя/морското лице“, т. 23 и т. 30 са само на осигурителя. Сборът на две ненулеви страни
   * е число, което не е ничия ставка, а полето във файла е ЕДНО и не може да го раздели обратно.
   */
  public static final String SINGLE_PAYER_BOTH_SIDES = "D1_SINGLE_PAYER_POINT_HAS_BOTH_RATES";

  /**
   * Т. 21 носи осигурителен доход, а т. 22 не носи ставка.
   *
   * <p>⚠️ НАМИРА ЗАГУБЕНА СТАВКА, НЕ НУЛЕВА ВНОСКА. Перо не се създава при нулева СУМА — а сумата
   * може да се закръгли до 0,00 върху много малка база, при съвсем ненулева ставка. Тогава ставката
   * изчезва от записа тихо, защото четенето ѝ минава през перото. Обратната посока — база без
   * ставка — е единственият видим отпечатък на този случай.
   */
  public static final String BASE_WITHOUT_RATE = "D1_T21_BASE_WITHOUT_T22_RATE";

  /**
   * Т. 13 извън диапазона си, или ВЪТРЕШНО ПРОТИВОРЕЧИВА.
   *
   * <p>⚠️ ЕДИН КОД ЗА ТРИ ПРОВЕРКИ, защото и трите значат едно за човека: „поправѝ т. 13“. Три кода
   * биха приканили да се смята, че третата (поредност > дължина) е друг вид дефект — а тя е точно
   * същият, само невидим от диапазоните.
   */
  public static final String T13_OUT_OF_RANGE = "D1_T13_OUT_OF_RANGE";

  /**
   * Специална здравна база без своята ставка, или ставка без база.
   *
   * <p>Указанието на т. 18 е „ВЪРХУ СУМАТА в т. 17“; на т. 28 — „върху сумата в т. 27“. Едната без
   * другата е противоречие, не непълнота.
   */
  public static final String HEALTH_BASE_PAIR_BROKEN = "D1_HEALTH_BASE_PAIR_BROKEN";

  /**
   * Здравно перо с база, която не е нито една от трите познати.
   *
   * <p>⚠️ ХВАЩА ЕДИНСТВЕНАТА ГРЕШКА, КОЯТО НИТО ЕДНА ДРУГА ПРОВЕРКА НЕ ВИЖДА. Погрешно написан
   * дискриминатор („ART40_15“ без подчертавка) се чете като нормална база и вноската пада в т.
   * 22.1. Загуба НЯМА — и точно затова е невидима: Д6 групира по ФОНД и дава верния сбор, а Д1
   * показва по-голяма т. 22.1 и празна т. 18. И двете числа изглеждат законни, а вноската е на
   * грешна точка.
   */
  public static final String UNKNOWN_HEALTH_BASE = "D1_UNKNOWN_HEALTH_BASE";

  /** Заличаващ запис („З“) носи стойност извън деветте разрешени точки. */
  public static final String DELETION_EXTRA = "D1_DELETION_EXTRA_FIELD";

  /** Мащабът, който файлът дава на ставките: два знака. */
  private static final int RATE_SCALE = BgD1ContributionAggregator.RATE_SCALE;

  /**
   * Проверява един запис.
   *
   * @param gvrsEligibleCodes видовете осигурен, за които речникът на вноските дава ред за ГВРС —
   *     тоест ТРУДОВИТЕ правоотношения, както ги знае системата. ⚠️ Подава се, а не се изброява
   *     тук: кой код носи ГВРС е ДАННИ ({@code bg_insurance_contribution.csv}), а списък в Java би
   *     бил второ копие, което остарява при първата редакция на наредбата. {@code null} значи
   *     „речникът не е достъпен“ и правилото се ПРОПУСКА с обявена находка, вместо да мине зелено.
   */
  public List<BgD1FindingDto> validate(BgD1Record record, Set<String> gvrsEligibleCodes) {
    List<BgD1FindingDto> findings = new ArrayList<>();
    if (record == null) {
      return findings;
    }

    String slipRef = record.getPaySlip() == null ? null : record.getPaySlip().getReference();
    String code = trim(record.getT12InsuredTypeCode());

    // ⚠️ ДВЕТЕ КАРТИ СЕ ЧЕТАТ ОТ ФИША, А НЕ ОТ ЗАПИСА, и това е единственият начин правилото за
    // еднополевите точки да е проверимо: на записа т. 22.2 вече е СБОР на двете страни, тоест
    // „9,40 само от осигурителя“ и „4,70 + 4,70 от двете“ дават едно и също число. Липсващ фиш
    // (заличаващ запис) дава празни карти — правилото просто не се проверява, вместо да гърми.
    List<BgPaySlipLine> slipLines =
        record.getPaySlip() == null ? null : record.getPaySlip().getBgPaySlipLineList();
    Map<String, BigDecimal> employerRates =
        BgD1ContributionAggregator.ratesByPoint(slipLines, BgPaySlipResolver.CREDIT);
    Map<String, BigDecimal> insuredRates =
        BgD1ContributionAggregator.ratesByPoint(slipLines, BgPaySlipResolver.DEBIT);

    // ═══ ЧЛ. 11 АЛ. 1 — трите, без които декларацията не се приема ═══
    // „Не се приемат декларации …, които не съдържат ЕИК по БУЛСТАТ/ЕИК по ЗТРРЮЛНЦ, ЕГН, личен
    // номер, личен номер на чужденец (служебен номер) на осигуреното лице, месеца, за който се
    // отнасят данните, и подпис.“ Подписът е извън записа — той е на носителя.
    if (isBlank(record.getT4ObligedPersonCode())) {
      findings.add(
          f(
                  CHL11_AL1,
                  BgD1FindingDto.SEVERITY_ERROR,
                  "липсва код на задълженото лице (ЕИК по БУЛСТАТ/ЗТРРЮЛНЦ) — по чл. 11 ал. 1"
                      + " декларацията НЕ СЕ ПРИЕМА. ⚠️ Източникът е Partner.registrationCode на"
                      + " плащащото дружество, а НЕ taxNbr (номерът по ЗДДС).",
                  slipRef,
                  code)
              .at("4"));
    }
    if (isBlank(record.getT5PersonalId())) {
      findings.add(
          f(
                  CHL11_AL1,
                  BgD1FindingDto.SEVERITY_ERROR,
                  "липсва ЕГН/ЛН/ЛНЧ на осигуреното лице — по чл. 11 ал. 1 декларацията НЕ СЕ"
                      + " ПРИЕМА",
                  slipRef,
                  code)
              .at("5"));
    }
    if (isBlank(record.getT2Month())) {
      findings.add(
          f(
                  CHL11_AL1,
                  BgD1FindingDto.SEVERITY_ERROR,
                  "липсва месецът, за който се отнасят данните — по чл. 11 ал. 1 декларацията НЕ СЕ"
                      + " ПРИЕМА",
                  slipRef,
                  code)
              .at("2"));
    }

    Map<String, Object> filled = filledPoints(record);

    // ═══ ЗАЛИЧАВАНЕ — само деветте точки ═══
    // ⚠️ ПРОВЕРЯВА СЕ, МАКАР BUILDER-ЪТ ДА ИЗЛИЗА РАНО, и това не е дублет. Записът има живот извън
    // едно извикване на услугата: човек го сверява и може да го редактира на екрана, а ретро-фиш
    // може да е маркиран като заличаващ СЛЕД като записът е построен. Проверката пази ЗАПИСА, не
    // пътя до него — същият довод, с който правилата за кодове 28 и 90 съществуват отделно от
    // строенето.
    if (BgD1CorrectionRules.isDeletion(record)) {
      for (String point : new TreeSet<>(filled.keySet())) {
        if (!BgD1CorrectionRules.DELETION_FIELDS.contains(point)) {
          findings.add(
              f(
                      DELETION_EXTRA,
                      BgD1FindingDto.SEVERITY_ERROR,
                      "при заличаване (буква „З“) се попълват САМО т. 1, 2, 3, 4, 5, 5.1, 6, 7 и 12"
                          + " (указание т. 1). Тази точка носи стойност „"
                          + filled.get(point)
                          + "“. ⚠️ Заличаването твърди, че записът НЕ СЪЩЕСТВУВА — запис с данни"
                          + " твърди обратното, при това с валидни числа, които НАП приема.",
                      slipRef,
                      code)
                  .at(point));
        }
      }
      // ⚠️ И ОСТАНАЛИТЕ ПРАВИЛА НЕ СЕ ПУСКАТ ВЪРХУ ЗАЛИЧАВАЩ ЗАПИС. Те питат за данни, които той
      // НАРОЧНО няма: „база без ставка“ би паднало на всеки заличаващ запис, а т. 34 щеше да иска
      // формула за запис, който нищо не декларира. Валидатор, който гърми на верен вход, приучава
      // да не се гледа.
      return findings;
    }

    // ═══ КОД 28 — само единайсет точки ═══
    if (BgD1InsuredTypeRules.healthOnly(code)) {
      for (String point : new TreeSet<>(filled.keySet())) {
        if (!BgD1InsuredTypeRules.CODE_28_FIELDS.contains(point)) {
          findings.add(
              f(
                      CODE_28_EXTRA,
                      BgD1FindingDto.SEVERITY_ERROR,
                      "при вид осигурен 28 се попълват САМО т. 2, 3, 4, 5, 5.1, 6, 7, 14, 15, 27 и"
                          + " 28 (заб. към т. 12). Тази точка носи стойност „"
                          + filled.get(point)
                          + "“. Код 28 е лице само със здравни вноски върху особена база — запис с"
                          + " попълнена т. 21 или т. 22 не греши в аритметиката, а твърди нещо за"
                          + " режима на лицето, което не е вярно.",
                      slipRef,
                      code)
                  .at(point));
        }
      }
    }

    // ═══ КОД 90 — само т. 31 и 31а ═══
    if (BgD1InsuredTypeRules.taxOnly(code)) {
      for (String point : new TreeSet<>(filled.keySet())) {
        if (!BgD1Point.T31_TAXABLE_INCOME.equals(point) && !BgD1Point.T31A_TAX.equals(point)) {
          findings.add(
              f(
                      CODE_90_EXTRA,
                      BgD1FindingDto.SEVERITY_ERROR,
                      "при вид осигурен 90 се попълват само т. 31 и 31а, а т. 16 НЕ се попълва"
                          + " (заб. 3 към т. 16). Тази точка носи стойност „"
                          + filled.get(point)
                          + "“.",
                      slipRef,
                      code)
                  .at(point));
        }
      }
    }

    // ═══ Т. 29 И Т. 30 — само за трудови правоотношения ═══
    // ⚠️ ЗАБЕЛЕЖКАТА ГОВОРИ ЗА ДВЕТЕ ЗАЕДНО: „Точки 29 и 30 се попълват за лицата, работещи по
    // ТРУДОВИ правоотношения.“ Проверено само за т. 30, правилото би пуснало брутна работна заплата
    // за граждански договор — число, което изглежда напълно нормално.
    //
    // ⚠️ И НЕПРОЧЕТЕНИЯТ РЕЧНИК СЕ ОБЯВЯВА — СИМЕТРИЧНО НА Т. 30. Двете точки се решават от един и
    // същ въпрос, затова и т. 29, не само т. 30, казва „правилото НЕ е проверено“ при недостъпен
    // речник, вместо да минава мълчаливо зелена. Мълчанието е по-опасно
    // точно тук: т. 30 е ПРОЦЕНТ и празна за всеки, комуто ГВРС не се дължи, тоест хипотезата
    // рядко се среща, а т. 29 е СУМА и всяко трудово възнаграждение я пълни. Тоест случаят, който
    // остава непроверен, е честият.
    if (isPositive(record.getT29GrossWage()) && gvrsEligibleCodes == null) {
      findings.add(
          f(
                  GVRS_NOT_LABOUR,
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 29 (брутно трудово възнаграждение) се попълва само за лицата по ТРУДОВИ"
                      + " правоотношения (заб. към т. 29/30), но речникът на вноските не е"
                      + " достъпен, за да се провери дали този вид осигурен е такова. Правилото НЕ"
                      + " е проверено.",
                  slipRef,
                  code)
              .at(BgD1Point.T29_GROSS_WAGE));
    }
    if (isPositive(record.getT29GrossWage())
        && gvrsEligibleCodes != null
        && code != null
        && !gvrsEligibleCodes.contains(code)) {
      findings.add(
          f(
                  GVRS_NOT_LABOUR,
                  BgD1FindingDto.SEVERITY_ERROR,
                  "т. 29 (брутно трудово възнаграждение) носи стойност, но вид осигурен "
                      + code
                      + " не е трудово правоотношение — речникът на вноските не дава ред за фонд"
                      + " ГВРС за него. Заб. към т. 29/30: попълват се за лицата, работещи по"
                      + " ТРУДОВИ правоотношения. „Брутна РАБОТНА ЗАПЛАТА“ по граждански договор е"
                      + " твърдение за вида на правоотношението, а не сметка.",
                  slipRef,
                  code)
              .at(BgD1Point.T29_GROSS_WAGE));
    }
    if (isPositive(record.getT30GvrsRate())) {
      if (gvrsEligibleCodes == null) {
        findings.add(
            f(
                    GVRS_NOT_LABOUR,
                    BgD1FindingDto.SEVERITY_WARNING,
                    "т. 30 (ГВРС) се попълва само за лицата по ТРУДОВИ правоотношения (заб. към"
                        + " т. 29/30), но речникът на вноските не е достъпен, за да се провери дали"
                        + " този вид осигурен изобщо носи ГВРС. Правилото НЕ е проверено.",
                    slipRef,
                    code)
                .at(BgD1Point.T30_GVRS));
      } else if (code != null && !gvrsEligibleCodes.contains(code)) {
        findings.add(
            f(
                    GVRS_NOT_LABOUR,
                    BgD1FindingDto.SEVERITY_ERROR,
                    "т. 30 (ГВРС) носи стойност, но вид осигурен "
                        + code
                        + " не е трудово правоотношение — речникът на вноските не дава ред за фонд"
                        + " ГВРС за него. Заб. към т. 29/30: попълват се за лицата, работещи по"
                        + " трудови правоотношения.",
                    slipRef,
                    code)
                .at(BgD1Point.T30_GVRS));
      }
    }

    // ═══ МАЩАБЪТ НА СТАВКАТА — полета 35–47 са ≤5.2 ═══
    for (Map.Entry<String, BigDecimal> e : rates(record).entrySet()) {
      BigDecimal rate = e.getValue();
      if (rate != null && rate.stripTrailingZeros().scale() > RATE_SCALE) {
        findings.add(
            f(
                    RATE_SCALE_TOO_LONG,
                    BgD1FindingDto.SEVERITY_ERROR,
                    "ставката е „"
                        + rate.toPlainString()
                        + "“ — с повече от два знака. Файловият формат дава на полета 35–47 размер"
                        + " ≤5.2, а речникът с вноски не носи трети знак на нито един от редовете"
                        + " си. Трети знак тук значи, че някой е събрал ставки, които не се събират,"
                        + " или е записал ставка на ръка.",
                    slipRef,
                    code)
                .at(e.getKey()));
      }
    }

    // ═══ ЕДНОПОЛЕВИТЕ ТОЧКИ — точно ЕДИН платец ═══
    // ⚠️ Проверката пита ПЕРАТА, не записа: на записа двете страни вече са сбор и разликата е
    // изгладена. Затова тя минава през БАЗАТА на сбора — двете карти на агрегатора.
    for (String point : BgD1Point.SINGLE_PAYER_POINTS) {
      BigDecimal employer = employerRates == null ? null : employerRates.get(point);
      BigDecimal insured = insuredRates == null ? null : insuredRates.get(point);
      if (employer != null && insured != null && employer.signum() != 0 && insured.signum() != 0) {
        findings.add(
            f(
                    SINGLE_PAYER_BOTH_SIDES,
                    BgD1FindingDto.SEVERITY_ERROR,
                    "т. "
                        + point
                        + " има ЕДНО поле във файла, а фишът носи ставка и от осигурителя ("
                        + employer.toPlainString()
                        + "), и от лицето ("
                        + insured.toPlainString()
                        + "). Указанията ги дават на един платец: т. 22.2 и т. 25 — „за сметка на"
                        + " осигурителя/морското лице“, т. 23 и т. 30 — само на осигурителя. Сборът"
                        + " "
                        + employer.add(insured).toPlainString()
                        + " не е ничия ставка, а полето не може да го раздели обратно.",
                    slipRef,
                    code)
                .at(point));
      }
    }

    // ═══ НЕПОЗНАТА ЗДРАВНА БАЗА ═══
    // ⚠️ Проверката минава през ПЕРАТА, защото на записа следата вече я няма: перото с грешна база
    // е слязло в т. 22.1 и там е неразличимо от нормалното.
    for (BgPaySlipLine line :
        slipLines == null ? Collections.<BgPaySlipLine>emptyList() : slipLines) {
      if (line == null
          || BgPaySlipResolver.groupOf(line) != BgPaySlipResolver.GROUP_CONTRIBUTIONS) {
        continue;
      }
      String base = BgD1ContributionAggregator.healthBaseOf(line);
      if (!BgD1ContributionAggregator.KNOWN_BASES.contains(base)) {
        findings.add(
            f(
                    UNKNOWN_HEALTH_BASE,
                    BgD1FindingDto.SEVERITY_ERROR,
                    "перо от група 5xx носи база на здравната вноска „"
                        + base
                        + "“, която не е нито една от трите познати ("
                        + String.join(", ", BgD1ContributionAggregator.KNOWN_BASES)
                        + "). Вноската му е паднала в т. 22.1 по подразбиране — тоест НЕ Е"
                        + " ИЗГУБЕНА, но стои на ГРЕШНА ТОЧКА. ⚠️ Това е невидимо отвън: Д6"
                        + " групира по фонд и дава верния сбор, а Д1 показва по-голяма т. 22.1 и"
                        + " празна т. 18 — и двете числа изглеждат законни.",
                    slipRef,
                    code)
                .at(BgD1Point.T22_1_HEALTH));
      }
    }

    // ═══ ДВОЙКИТЕ БАЗА/СТАВКА НА СПЕЦИАЛНИТЕ ЗДРАВНИ ТОЧКИ ═══
    // ⚠️ ЕДНАТА БЕЗ ДРУГАТА НЕ Е НЕПЪЛНОТА, А ПРОТИВОРЕЧИЕ. Указанието на т. 18 е „ВЪРХУ СУМАТА в
    // т. 17“, а на т. 28 — „върху сумата в т. 27“. Ставка без база значи процент върху нищо; база
    // без ставка значи деклариран доход, върху който не се дължи вноска — а той се дължи по самата
    // норма, която базата описва. И двете минават всяка проверка за формат.
    checkBasePair(
        findings,
        record.getT17Art4015Income(),
        record.getT18HealthRateOnT17(),
        BgD1Point.T17_ART40_1_5_INCOME,
        BgD1Point.T18_HEALTH_ON_T17,
        "ЗЗО чл. 40 ал. 1 т. 5 (ВНР, майчинство, отглеждане на дете)",
        slipRef,
        code);
    checkBasePair(
        findings,
        record.getT27HealthOnlyIncome(),
        record.getT28HealthRateOnT27(),
        BgD1Point.T27_HEALTH_ONLY_INCOME,
        BgD1Point.T28_HEALTH_ON_T27,
        "ЗЗО чл. 40 ал. 1 т. 1 б. „б“ (неплатен отпуск, чл. 167а КТ)",
        slipRef,
        code);

    // ═══ Т. 13 — ДИАПАЗОНИТЕ И ВРЪЗКАТА МЕЖДУ ДВЕТЕ ПОЗИЦИИ ═══
    // ⚠️ ТРИ РАЗЛИЧНИ ТВЪРДЕНИЯ, И ТРЕТОТО НЕ СЛЕДВА ОТ ДРУГИТЕ ДВЕ. Указанието дава на позиция 1
    // диапазон 02–12, на позиция 2 — 01–12. Но поредността НЕ МОЖЕ да надхвърля дължината: „втори
    // месец от двумесечен период“ е валидно, „трети от двумесечен“ — не, а и двете минават всяка
    // проверка за диапазон поотделно. Точно това е стойност, която НАП приема и която описва
    // невъзможен отчетен период.
    Integer sirvMonths = number(record.getT13SirvPeriodMonths());
    Integer sirvIndex = number(record.getT13SirvMonthIndex());
    if (sirvMonths != null && (sirvMonths < 2 || sirvMonths > 12)) {
      findings.add(
          f(
                  T13_OUT_OF_RANGE,
                  BgD1FindingDto.SEVERITY_ERROR,
                  "т. 13 позиция 1 е „"
                      + record.getT13SirvPeriodMonths()
                      + "“, а указанието допуска от 02 до 12 („в съответствие с броя месеци в"
                      + " установения период на сумирано изчисляване“). ⚠️ Долната граница е 02, а не"
                      + " 01: самият код 16 значи „за ПОВЕЧЕ ОТ ЕДИН месец“.",
                  slipRef,
                  code)
              .at("13"));
    }
    if (sirvIndex != null && (sirvIndex < 1 || sirvIndex > 12)) {
      findings.add(
          f(
                  T13_OUT_OF_RANGE,
                  BgD1FindingDto.SEVERITY_ERROR,
                  "т. 13 позиция 2 е „"
                      + record.getT13SirvMonthIndex()
                      + "“, а указанието допуска от 01 до 12.",
                  slipRef,
                  code)
              .at("13"));
    }
    if (sirvMonths != null && sirvIndex != null && sirvIndex > sirvMonths) {
      findings.add(
          f(
                  T13_OUT_OF_RANGE,
                  BgD1FindingDto.SEVERITY_ERROR,
                  "т. 13 твърди месец "
                      + sirvIndex
                      + " от период с "
                      + sirvMonths
                      + " месеца. ⚠️ Всяка позиция поотделно Е в допустимия си диапазон — точно"
                      + " затова разминаването минава всяка проверка за диапазон и описва невъзможен"
                      + " отчетен период, който НАП приема като число.",
                  slipRef,
                  code)
              .at("13"));
    }

    // ═══ БАЗА БЕЗ СТАВКА — загубената ставка на закръглено до нула перо ═══
    if (isPositive(record.getT21InsuranceIncome())
        && !isPositive(record.getT22DooRateEmployer())
        && !isPositive(record.getT22DooRateInsured())
        && !BgD1InsuredTypeRules.healthOnly(code)
        && !BgD1InsuredTypeRules.taxOnly(code)) {
      findings.add(
          f(
                  BASE_WITHOUT_RATE,
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 21 носи осигурителен доход "
                      + record.getT21InsuranceIncome().toPlainString()
                      + ", а т. 22 не носи ставка за нито един платец. Вноски за фондовете на ДОО се"
                      + " дължат върху всеки осигурителен доход, тоест или фишът няма пера за 5xx,"
                      + " или сумата им се е закръглила до 0,00 и ставката е изчезнала заедно с"
                      + " перото. Провери групата 5xx на фиша.",
                  slipRef,
                  code)
              .at(BgD1Point.T22_DOO));
    }

    return findings;
  }

  /**
   * Точките на записа, които наистина носят стойност — „номер на точка → стойност“.
   *
   * <p>⚠️ Т. 1 и т. 12 нарочно ги НЯМА. Списъците на наредбата за кодове 28 и 90 изброяват полетата
   * с ДАННИ; кодът за корекция и самият вид осигурен са структурни — без т. 12 не се знае, че кодът
   * е 28, тоест правилото би отричало собствената си предпоставка.
   *
   * <p>⚠️ И нулата НЕ се брои за попълнена. Вноска 0,00 значи „няма такава вноска“, а не „вноската
   * е нула“ — иначе всеки запис би нарушавал списъка на код 28 със седем нулеви точки.
   */
  public static Map<String, Object> filledPoints(BgD1Record record) {
    Map<String, Object> out = new LinkedHashMap<>();
    if (record == null) {
      return out;
    }
    put(out, "2", record.getT2Month());
    put(out, "3", record.getT3Year());
    put(out, "4", record.getT4ObligedPersonCode());
    put(out, "5", record.getT5PersonalId());
    put(out, "5.1", record.getT51PersonalIdCode());
    put(out, "6", record.getT6Surname());
    put(out, "7", record.getT7Initials());
    put(out, "12.1", record.getT121QualificationGroup());
    put(out, "12.2", record.getT122ActivityCode());
    put(out, "12.3", record.getT123ActivityRow());
    // ⚠️ ЕДИН КЛЮЧ „13“ ЗА ДВЕТЕ ПОЗИЦИИ, по същия довод като при т. 14/15: списъците на наредбата
    // за кодове 28 и 90 изброяват ТОЧКИ, а т. 13 е една точка с две позиции.
    put(out, "13", firstFilled(record.getT13SirvPeriodMonths(), record.getT13SirvMonthIndex()));
    // ⚠️ ЕДИН КЛЮЧ „14“ ЗА ПЕТТЕ ПОЗИЦИИ. Списъците за кодове 28 и 90 изброяват ТОЧКИ, а т. 14 е
    // една точка с пет позиции. Пет ключа „14 (1)“…„14 (5)“ биха направили списъка на наредбата
    // несравним със записа — а той е самото правило.
    put(
        out,
        "14",
        firstFilled(
            record.getT14Day1(),
            record.getT14Day2(),
            record.getT14Day3(),
            record.getT14Day4(),
            record.getT14Day5()));
    put(
        out,
        "15",
        firstFilled(
            record.getT15Day1(),
            record.getT15Day2(),
            record.getT15Day3(),
            record.getT15Day4(),
            record.getT15Day5()));
    put(out, BgD1Point.T17_ART40_1_5_INCOME, record.getT17Art4015Income());
    put(out, BgD1Point.T27_HEALTH_ONLY_INCOME, record.getT27HealthOnlyIncome());
    put(out, BgD1Point.T21_INSURANCE_INCOME, record.getT21InsuranceIncome());
    put(out, BgD1Point.T29_GROSS_WAGE, record.getT29GrossWage());
    put(out, BgD1Point.T31_TAXABLE_INCOME, record.getT31TaxableIncome());
    put(out, "34", record.getT34NetPay());
    for (Map.Entry<String, BigDecimal> e : rates(record).entrySet()) {
      put(out, e.getKey(), e.getValue());
    }
    put(out, BgD1Point.T31A_TAX, record.getT31aTax());
    return out;
  }

  /**
   * Само вноските-точки, в реда на декларацията — <b>СТАВКИ</b>, не суми.
   *
   * <p>⚠️ ЕДИН КЛЮЧ ЗА ДВЕТЕ ПОЛОВИНИ НА ЕДНА ТОЧКА. Т. 22, 22.1 и 26 имат по ДВЕ полета във файла
   * (А и Б), но по ЕДИН номер в наредбата — а списъците за кодове 28 и 90 изброяват НОМЕРА. Затова
   * тук ключът е номерът, а стойността е онази от двете половини, която носи число (осигурителят
   * пръв, защото той е обичайният платец). Две записа „22 А“ и „22 Б“ биха направили списъка на
   * наредбата несравним със записа.
   */
  public static Map<String, BigDecimal> rates(BgD1Record record) {
    Map<String, BigDecimal> out = new LinkedHashMap<>();
    if (record == null) {
      return out;
    }
    out.put(
        BgD1Point.T22_DOO, either(record.getT22DooRateEmployer(), record.getT22DooRateInsured()));
    out.put(
        BgD1Point.T22_1_HEALTH,
        either(record.getT221HealthRateEmployer(), record.getT221HealthRateInsured()));
    out.put(BgD1Point.T18_HEALTH_ON_T17, record.getT18HealthRateOnT17());
    out.put(BgD1Point.T22_2_TZPB, record.getT222TzpbRate());
    out.put(BgD1Point.T23_TEACHER_PF, record.getT23TeacherPfRate());
    out.put(BgD1Point.T25_PPF, record.getT25PpfRate());
    out.put(
        BgD1Point.T26_UPF, either(record.getT26UpfRateEmployer(), record.getT26UpfRateInsured()));
    out.put(BgD1Point.T28_HEALTH_ON_T27, record.getT28HealthRateOnT27());
    out.put(BgD1Point.T30_GVRS, record.getT30GvrsRate());
    return out;
  }

  /**
   * Двойката „база + ставка“ на една специална здравна точка — и двете, или нито едната.
   *
   * <p>⚠️ ЕДИН МЕТОД ЗА ДВЕТЕ ДВОЙКИ, защото правилото е едно. Преписан два пъти, той щеше да носи
   * два различни текста за едно и също изискване — и вторият щеше да остарее пръв.
   */
  private static void checkBasePair(
      List<BgD1FindingDto> findings,
      BigDecimal base,
      BigDecimal rate,
      String basePoint,
      String ratePoint,
      String norm,
      String slipRef,
      String code) {

    boolean hasBase = isPositive(base);
    boolean hasRate = isPositive(rate);
    if (hasBase == hasRate) {
      return;
    }
    findings.add(
        f(
                HEALTH_BASE_PAIR_BROKEN,
                BgD1FindingDto.SEVERITY_ERROR,
                hasBase
                    ? "т. "
                        + basePoint
                        + " носи доход "
                        + base.toPlainString()
                        + ", а т. "
                        + ratePoint
                        + " не носи ставка. Деклариран доход, върху който не се дължи вноска — а по "
                        + norm
                        + " тя се дължи по самата норма, която базата описва."
                    : "т. "
                        + ratePoint
                        + " носи ставка "
                        + rate.toPlainString()
                        + ", а т. "
                        + basePoint
                        + " не носи доход. Указанието е „ВЪРХУ СУМАТА в т. "
                        + basePoint
                        + "“ — процент върху нищо. Основание: "
                        + norm
                        + ".",
                slipRef,
                code)
            .at(hasBase ? ratePoint : basePoint));
  }

  /** Първата непразна от подадените позиции, или {@code null}. */
  private static String firstFilled(String... values) {
    for (String v : values) {
      if (v != null && !v.trim().isEmpty()) {
        return v;
      }
    }
    return null;
  }

  /** Първата от двете половини, която носи число. ⚠️ НЕ СБОР: 10,92 и 8,38 не се събират. */
  private static BigDecimal either(BigDecimal employer, BigDecimal insured) {
    return isPositive(employer) ? employer : insured;
  }

  /**
   * Числото от низова позиция, или {@code null} за празно/нечисло.
   *
   * <p>⚠️ Не хвърля: позициите на т. 13 се въвеждат и НА РЪКА, тоест могат да носят каквото човек
   * напише. Изключение тук би спряло цялата декларация заради една сгрешена клетка на едно лице.
   */
  private static Integer number(String value) {
    String v = trim(value);
    if (v == null || v.isEmpty()) {
      return null;
    }
    try {
      return Integer.valueOf(v);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static void put(Map<String, Object> out, String point, Object value) {
    if (value instanceof BigDecimal) {
      if (isPositive((BigDecimal) value)) {
        out.put(point, ((BigDecimal) value).toPlainString());
      }
      return;
    }
    if (value != null && !value.toString().trim().isEmpty()) {
      out.put(point, value.toString().trim());
    }
  }

  /** ⚠️ {@code signum()}, не сравнение с null: генерираният getter за BigDecimal връща ZERO. */
  private static boolean isPositive(BigDecimal value) {
    return value != null && value.signum() != 0;
  }

  private static BgD1FindingDto f(
      String code, String severity, String message, String slipRef, String insuredCode) {
    return new BgD1FindingDto(code, severity, message).on(slipRef, insuredCode);
  }

  private static boolean isBlank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
