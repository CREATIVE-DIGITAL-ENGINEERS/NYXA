package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ВСИЧКО, КОЕТО {@link BgD1RecordBuilder} НЕ МОЖЕ ДА ПРОЧЕТЕ САМ — един носител вместо пет
 * параметъра.
 *
 * <p><b>Защо съществува.</b> Строителят на записа е чиста функция нарочно: така построяването се
 * проверява без база, без контекст и без App Install. Четири от точките му обаче искат ЗАЯВКИ —
 * дните по кофи (заявките за отпуск), прекъсванията на осигуряването (същите заявки, друг признак),
 * часовете (отчетите за време и допълнителните часове) и таванът на т. 34 (темпорален параметър).
 * Те пристигат отвън, от {@link BgD1Emitter}, който е единственият слой с достъп до базата.
 *
 * <p><b>⚠️ ЕДИН НОСИТЕЛ, А НЕ ЧЕТИРИ ПАРАМЕТЪРА, и това не е стил.</b> Четири последователни
 * необходими-но-може-и-да-липсват аргумента се разместват безшумно при първото добавяне на пети:
 * {@code Map} и {@code List} на съседни позиции се приемат от компилатора в двата реда. Именуван
 * носител прави разместването невъзможно.
 *
 * <p><b>⚠️ И {@code null} НАВСЯКЪДЕ ЗНАЧИ „НЕ Е ЧЕТЕНО“, А НЕ „НЯМА“.</b> Разликата е законова, не
 * педантична: празна т. 16 при непрочетени дни е СЪСТОЯНИЕ НА ИЗВИКВАНЕТО, а „нула дни в
 * осигуряване“ е ТВЪРДЕНИЕ за лицето, което НАП приема. Затова празният носител ({@link #none()})
 * оставя четирите точки празни, вместо да ги нулира.
 */
public final class BgD1Facts {

  private final Map<String, Integer> dayBuckets;
  private final List<LocalDate[]> interruptions;
  private final BgD1HourSource.Hours hours;
  private final BigDecimal netPayCap;
  private final Set<String> labourRelationCodes;
  private final int outsideInsuranceDays;

  /**
   * ПЕРАТА И ДАНЪКЪТ, КОИТО РЕДЪТ ВИЖДА — или {@code null} за „целият фиш“.
   *
   * <p>⚠️ Фиш, който носи и обезщетение по вид 27 ({@link BgCompensationParts}), дава ДВА реда, и
   * редът 01 не бива да види перата на реда 27: обезщетението не е нито осигурителен доход по вид
   * 01, нито брутна заплата, нито нето по НПОПДОО, а данъкът му е на неговия ред.
   */
  private final List<BgPaySlipLine> lines;

  private final BigDecimal tax;

  public BgD1Facts(
      Map<String, Integer> dayBuckets,
      List<LocalDate[]> interruptions,
      BgD1HourSource.Hours hours,
      BigDecimal netPayCap) {
    this(dayBuckets, interruptions, hours, netPayCap, null, 0);
  }

  /**
   * Същото, но и с кодовете на ТРУДОВИТЕ правоотношения — петата заявка.
   *
   * <p><b>⚠️ ЗАЩО ТОЗИ ФАКТ ИЗОБЩО СЛИЗА ДОТУК.</b> Т. 29 (брутна работна заплата) и т. 30 (ГВРС)
   * се попълват само за лицата по ТРУДОВИ правоотношения — заб. към т. 29/30 на Приложение № 1. Кои
   * са те, системата знае на ЕДНО място: речникът на вноските, по това дали дава ред за фонд ГВРС.
   * Заявката е на {@link BgD1Emitter#gvrsEligibleCodes}, защото само той има достъп до базата;
   * строителят на записа е чиста функция и я получава ГОТОВА, както получава дните, часовете и
   * тавана.
   *
   * <p><b>⚠️ И ЗАЩО НЕ СПИСЪК В JAVA.</b> Кодовете на трудовите правоотношения се менят с редакция
   * на ЗБДОО и на наредбата, не с комит. Второ копие тук би оцеляло след редакцията и би било вярно
   * на вид — точно рискът, който {@code BgD1InsuredTypeRules} описва за шестте флага.
   */
  public BgD1Facts(
      Map<String, Integer> dayBuckets,
      List<LocalDate[]> interruptions,
      BgD1HourSource.Hours hours,
      BigDecimal netPayCap,
      Set<String> labourRelationCodes) {
    this(dayBuckets, interruptions, hours, netPayCap, labourRelationCodes, 0);
  }

  /**
   * Същото, но и с ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО — изваждаемото на т. 16.1 по КСО чл. 10 ал. 2.
   *
   * <p><b>⚠️ ЗАЩО ЧИСЛОТО НЕ ДОЙДЕ КАТО ШЕСТА КОФА В {@code dayBuckets}.</b> Кофите на т. 16 са
   * ШЕСТ и заб. 1 иска сборът им дословно да пълни позиции 1 и 2; седми ключ в картата би влязъл в
   * {@link BgD1DayBuckets#total} и би обявил за дни В ОСИГУРЯВАНЕ точно дните, през които то е
   * прекъснато. Изваждаемото е отделно число, защото прави отделно твърдение.
   *
   * <p>⚠️ Нулата тук е СТОЙНОСТ („няма такива дни“), а не „не е четено“ — за разлика от {@code
   * null}-овете наоколо. Дните са преброени от {@link BgD1DaySource}, който пълни и кофите: ако той
   * не е викан, празни са и кофите, и т. 16 не се пълни изобщо.
   */
  public BgD1Facts(
      Map<String, Integer> dayBuckets,
      List<LocalDate[]> interruptions,
      BgD1HourSource.Hours hours,
      BigDecimal netPayCap,
      Set<String> labourRelationCodes,
      int outsideInsuranceDays) {
    this(
        dayBuckets,
        interruptions,
        hours,
        netPayCap,
        labourRelationCodes,
        outsideInsuranceDays,
        null,
        null);
  }

  private BgD1Facts(
      Map<String, Integer> dayBuckets,
      List<LocalDate[]> interruptions,
      BgD1HourSource.Hours hours,
      BigDecimal netPayCap,
      Set<String> labourRelationCodes,
      int outsideInsuranceDays,
      List<BgPaySlipLine> lines,
      BigDecimal tax) {
    this.dayBuckets = dayBuckets;
    this.interruptions = interruptions;
    this.hours = hours;
    this.netPayCap = netPayCap;
    this.labourRelationCodes = labourRelationCodes;
    this.outsideInsuranceDays = outsideInsuranceDays;
    this.lines = lines;
    this.tax = tax;
  }

  /** Същите факти, но редът вижда САМО тези пера и този данък — виж {@link #lines}. */
  public BgD1Facts seeing(List<BgPaySlipLine> lines, BigDecimal tax) {
    return new BgD1Facts(
        dayBuckets,
        interruptions,
        hours,
        netPayCap,
        labourRelationCodes,
        outsideInsuranceDays,
        lines,
        tax);
  }

  /** Перата, които редът вижда. */
  public List<BgPaySlipLine> linesOf(BgPaySlip slip) {
    if (lines != null) {
      return lines;
    }
    return slip == null ? null : slip.getBgPaySlipLineList();
  }

  /** Данъкът (т. 31а), който редът вижда. */
  public BigDecimal taxOf(BgPaySlip slip) {
    if (tax != null) {
      return tax;
    }
    return slip == null ? null : slip.getBgTaxAmount();
  }

  /** Празният носител — за свидетелите, които проверяват точки, независими от заявки. */
  public static BgD1Facts none() {
    return new BgD1Facts(null, null, null, null, null, 0);
  }

  /** Дните по кофи на т. 16, или {@code null} за „НЕ са четени“. */
  public Map<String, Integer> getDayBuckets() {
    return dayBuckets;
  }

  /** Интервалите, прекъсващи осигуряването (КСО чл. 10 ал. 2). Празният списък значи „няма“. */
  public List<LocalDate[]> getInterruptions() {
    return interruptions == null ? Collections.emptyList() : interruptions;
  }

  /**
   * ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО (КСО чл. 10 ал. 2) — вадят се от остатъка на т. 16.1. Нулата е
   * стойност.
   */
  public int getOutsideInsuranceDays() {
    return outsideInsuranceDays;
  }

  /** Часовете за периода, или {@code null} за „НЕ са четени“. */
  public BgD1HourSource.Hours getHours() {
    return hours;
  }

  /** Максималният месечен осигурителен доход, или {@code null} за „НЕ е прочетен“. */
  public BigDecimal getNetPayCap() {
    return netPayCap;
  }

  /**
   * ТРУДОВО ПРАВООТНОШЕНИЕ ЛИ Е ТОЗИ ВИД ОСИГУРЕН — отговорът, който т. 29 чака.
   *
   * <p><b>⚠️ НЕПРОЧЕТЕНИЯТ РЕЧНИК ОТГОВАРЯ „ДА“, И ТОВА Е ПОСОКАТА, КОЯТО СЕ ВИЖДА.</b> {@code
   * null} значи „не е четено“ (липсваща база, неинсталиран речник) — състояние на средата, не
   * твърдение за лицето. Прочетено като „не е трудово“, то би занулило т. 29 за ВСЕКИ нает тихо;
   * прочетено като „е трудово“, точката се пълни както досега и {@link BgD1Validator} обявява със
   * своя WARNING, че правилото НЕ е проверено. Едната грешка се вижда, другата не.
   *
   * <p>⚠️ Празният КОД също отговаря „да“ — фиш без вид осигурен изобщо не стига дотук: {@code
   * BgD1InsuredTypeRules#refusalReason} го отказва по-рано и с назована причина.
   */
  public boolean isLabourRelation(String insuredTypeCode) {
    if (labourRelationCodes == null) {
      return true;
    }
    return insuredTypeCode == null || labourRelationCodes.contains(insuredTypeCode.trim());
  }
}
