package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipLineTypeRepository;
import com.axelor.apps.bglocalization.service.BgContributionLine;
import com.axelor.apps.bglocalization.service.BgContributionResult;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.d1.BgD1ContributionAggregator;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ОСИГУРОВКИТЕ СТАВАТ ПЕРА — генераторът на група 5xx.
 *
 * <p><b>⚠️ БЕЗ ТОЗИ КЛАС ГРУПАТА 5xx СТОИ ПРАЗНА НА ВСЕКИ ФИШ.</b> Веригата ѝ е цяла и навързана —
 * {@code BgPaySlipService#contributions} сглобява входа, {@code BgInsurableIncomeService} налага
 * пода и тавана, {@code BgContributionResolver} смята по фонд и по платец — но сама по себе си тя
 * служи САМО като ПАЗАЧ при потвърждаване. Този клас превръща числата ѝ в двайсетте реда на
 * речника.
 *
 * <p><b>Границата на този енджин: НЕ СМЯТА НИЩО.</b> Сумите пристигат готови в {@link
 * BgContributionResult} и само се КОПИРАТ. Това не е пестене на код, а § 5.3: всяка вноска е
 * закръглена ПООТДЕЛНО по НЕВД чл. 2 ал. 4 още в резолвера, и точно това дава 15,15 вместо 15,14 на
 * златния фиш. Второ закръгляне тук — или, по-лошо, сбор и после закръгляне — би върнало грешното
 * число.
 *
 * <p><b>Как се намира типът перо — ПО ФОНД И ПО ЗНАК, тоест по ДВЕ неща.</b> Групата 5xx е
 * ОГЛЕДАЛНА на речника на фондовете: по един ред за всеки фонд и за всеки платец, точно 20. Оттам
 * ключът е двойката:
 *
 * <ul>
 *   <li>{@code BgPaySlipLineType.bgContributionFund.code} == {@code BgContributionLine.fundCode};
 *   <li>{@code bgSign} == CREDIT за платец ОСИГУРИТЕЛ, DEBIT за платец ЛИЦЕ.
 * </ul>
 *
 * <p>⚠️ И ЗНАКЪТ НЕ Е УКРАШЕНИЕ — той Е ключът към платеца, а после и самата аритметика на фиша.
 * {@code BgPaySlipResolver#taxBase} вади от данъчната основа САМО перата от 5xx със знак DEBIT;
 * {@code #netTotal} изключва CREDIT перата от 5xx от двете си страни; {@code #employerCost} събира
 * точно тях. Разменени двата знака, лицето плаща вноските на работодателя, данъчната основа пада с
 * 20,90 вместо с 15,15, а нетото излиза по-голямо от брутото — и фишът изглежда сметнат.
 *
 * <p>⚠️ Оттам следва и защо се търси по ПРЕПРАТКАТА към фонда, а не по кода на перото: код като
 * {@code pl_vn_pension_lice} е ЧЕТВЪРТОТО място, на което един и същи низ би живял (речник, файл,
 * код, тест). Препратката е една.
 *
 * <p><b>⚠️ ВИКА СЕ ПОСЛЕДЕН В {@code prepare()}, И ТОВА Е ПРАВИЛО.</b> Вноските се смятат върху
 * ПЪЛНИЯ осигурителен доход, а той е сбор на ПЕРАТА ({@code
 * BgPaySlipResolver#insuranceIncomeBase}). Пуснат преди начисленията, обезщетенията или болничния,
 * генераторът осигурява непълен фиш — и грешката е тиха, защото всички числа изглеждат пресметнати.
 */
public class BgContributionLineService {

  /** Легендата на групата в отказите — 5xx не е код на перо, а името на цялата група. */
  protected static final String GROUP_LABEL = "5xx";

  /**
   * Кодът на фонд ЗОВ в речника. Един фонд (НЗОК), три бази — виж {@link #upsertSpecialHealthBase}.
   */
  protected static final String HEALTH_FUND_CODE = "HEALTH";

  // ═══ ГЕНЕРАТОРЪТ ════════════════════════════════════════════════════════

  /**
   * ЗАПИСВА ПО ЕДНО ПЕРО ЗА ВСЯКА ВНОСКА (фонд × платец) и връща какво е станало с всяка.
   *
   * <p><b>Резултатът СЕ ПОДАВА, не се смята тук.</b> ⚠️ Това е нарочно и е важното решение на
   * класа: веригата на осигуровките живее в {@code BgPaySlipService#contributions} — вид осигурен,
   * режим на работното време, МОД, под и таван, избор на редове по ключа — и там ѝ е мястото.
   * Подадена, тя се смята ВЕДНЪЖ за фиша; прочетена наново тук, тя щеше да е ВТОРО извикване на
   * същата верига, свободно да се размине с първото (пазача при потвърждаване) по дата, по база и
   * по редове. А разминаване тук значи фиш, чиито пера казват едно, а пазачът му — друго.
   *
   * <p><b>Върнатият списък е част от договора, не отпадък.</b> Той носи и отказите — а те са
   * единственото, което различава „вноски не се дължат“ от „вноските не можаха да се сметнат“.
   *
   * <p><b>⚠️ ИДЕМПОТЕНТНО.</b> Второ и трето извикване не дублират: перото се търси по СТАБИЛНИЯ
   * КОД на типа и намереното се ОБНОВЯВА на място. Кодът, а не идентификаторът, защото при
   * незапазен фиш перата още нямат идентификатор. И кодът е достатъчен ТУК, за разлика от
   * обезщетенията: една двойка (фонд, платец) дава точно едно перо на един фиш.
   *
   * <p><b>⚠️ АДИТИВНО.</b> Нищо не се трие и нищо чуждо не се пипа — генераторът докосва САМО
   * редовете със своите кодове.
   *
   * <p><b>⚠️ КОРИГИРАЩОТО КОПИЕ СЕ ПИШЕ, ЗАЛИЧАВАЩОТО — НЕ.</b> Вноските на копието не излизат по
   * ДНЕШНИТЕ ставки за МИНАЛ месец: веригата в {@code BgPaySlipService#contributions} чете входа на
   * копието само от замразеното ({@code BgFrozenPayrollInput}) и параметрите по датата на ПЕРИОДА —
   * а копие с перо 195 и старите вноски би било грешно. Заличаващото копие носи числата на
   * оригинала дословно: те ИДЕНТИФИЦИРАТ записа, който НАП трябва да заличи, и не се смятат наново.
   *
   * @param slip фишът, върху който се пише. Само в статус ЧЕРНОВА
   * @param result резултатът от веригата на осигуровките. Може да е {@code null}
   */
  public List<BgPaySlipLineOutcome> generate(BgPaySlip slip, BgContributionResult result) {
    return generate(slip, result, null);
  }

  /**
   * ВНОСКИТЕ ВЪРХУ ЕДНО ОБЕЗЩЕТЕНИЕ ПО ВИД 27 — същият генератор, но перата носят препратката към
   * документа ({@code BgPaySlipLine.bgCompensation}).
   *
   * <p><b>⚠️ ПРЕПРАТКАТА Е ЧАСТ ОТ КЛЮЧА.</b> Обезщетението по чл. 222 ал. 1 КТ се осигурява по
   * СВОЙ режим (вид 27: само Пенсии и УПФ), а типовете пера са ОГЛЕДАЛНИ на фондовете — тоест
   * вноската за Пенсии по вид 27 е СЪЩИЯТ тип (501/502) като тази по вид 01 върху заплатата. По код
   * двете биха се презаписвали една друга; препратката ги разделя, а Д1 по нея знае кои ставки са
   * на реда 27 ({@code BgCompensationParts}).
   *
   * @param compensation документът на обезщетението, или {@code null} за обикновените вноски
   */
  public List<BgPaySlipLineOutcome> generate(
      BgPaySlip slip, BgContributionResult result, BgCompensation compensation) {
    if (!isEligibleDraft(slip)) {
      return Collections.emptyList();
    }
    if (result == null || !result.isResolved()) {
      return Collections.singletonList(refusalFor(result));
    }
    List<BgPaySlipLineOutcome> outcomes = new ArrayList<>();
    Set<String> produced = new HashSet<>();
    for (BgContributionLine contribution : result.getLines()) {
      // ⚠️ РЕЧНИКЪТ СЕ ПИТА ВЕДНЪЖ ЗА РЕД, и точно затова редът е такъв: първо проверките, които не
      // искат речник, после ЕДНО търсене, чийто резултат отива и в отговора, и в перото. Две
      // търсения за един ред са два прочита на едно и също нещо — по двайсет реда на фиш това е
      // четирийсет заявки вместо двайсет, и двата прочита са свободни да се разминат.
      BgPaySlipLineOutcome problem = problem(contribution);
      if (problem != null) {
        outcomes.add(problem);
        continue;
      }
      BgPaySlipLineType type = requireLineType(contribution);
      BgPaySlipLineOutcome outcome =
          BgPaySlipLineOutcome.computed(
              type.getCode(),
              contribution.getAmount(),
              contribution.getBase(),
              contribution.getRate());
      outcomes.add(outcome);
      upsert(slip, type, outcome, compensation);
      produced.add(type.getCode());
    }
    // ⚠️ ОТПАДНАЛОТО СЕ МАХА САМО СЛЕД ОТГОВОР, А НЕ СЛЕД ОТКАЗ. Тук резултатът е разрешен, тоест
    // той казва изрично кои фондове се дължат: фонд, който вече го няма, или вноска, станала
    // законна нула (problem() отгоре), иначе оставя стария си ред със старата сума — upsert само
    // добавя и обновява. При отказ (по-горе) редовете остават: отказът не е твърдение „не се
    // дължи“, а contributionRefusal спира потвърждаването така или иначе.
    // ⚠️ САМО БЕЛЯЗАНИТЕ и САМО НОРМАЛНАТА БАЗА. Ръчен ред със същия код не се пипа, а здравните
    // вноски върху специалните бази са на други генератори (болничен, неплатен отпуск).
    BgGeneratedLines.removeStale(
        slip,
        line ->
            BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
                && BgD1ContributionAggregator.BASE_STANDARD.equals(
                    BgD1ContributionAggregator.healthBaseOf(line))
                && sameCompensation(line, compensation)
                && !produced.contains(codeOf(line)));
    if (result.getStatus() == BgContributionResult.Status.RESOLVED_INCOMPLETE) {
      outcomes.add(incompleteNotice(result));
    }
    return outcomes;
  }

  /**
   * ЗАПИСВА ЗДРАВНА ВНОСКА ВЪРХУ <b>СПЕЦИАЛНА БАЗА</b> — перото, което Фаза 1 обяви за невъзможно.
   *
   * <p><b>Кое се затваря тук.</b> Двете здравни вноски по ЗЗО чл. 40 ал. 1 т. 5 (дните ВНР) и по
   * чл. 40 ал. 1 т. 1 б. „б“ (неплатен отпуск) се СМЯТАХА вярно и не се ЗАПИСВАХА — обявена граница
   * в {@code BgSickLeaveService#healthAbsence} и в {@code BgUnpaidLeaveHealthResolver}. Доводът
   * беше, че вписването им в перо 516 би слепило вноски върху РАЗЛИЧНИ бази в едно число: 5,27
   * върху 109,87 и 24,13 върху 502,78 стават 29,40 върху нищо.
   *
   * <p>Доводът беше верен и остава верен — затова базата вече Е НА ПЕРОТО ({@code
   * BgPaySlipLine.bgHealthBaseKind}) и трите вноски съществуват като ТРИ РЕДА от един и същи тип,
   * всяка със своята база в {@code bgQuantity} и своята ставка в {@code bgRate}. Слепването е
   * невъзможно по устройство, а не по договорка.
   *
   * <p><b>⚠️ ТИПЪТ ПЕРО СЕ НАМИРА ПО ДВОЙКАТА ФОНД + ЗНАК, както за всяка друга вноска</b> — фондът
   * е HEALTH, защото НЗОК е един. Тоест група 5xx остава ОГЛЕДАЛНА на речника с фондовете (двайсет
   * типа) и {@code BgPaySlipLineTypeCatalogueTest} не пада.
   *
   * <p><b>⚠️ ПЛАТЕЦЪТ СЕ ПОДАВА, НЕ СЕ ИЗВЕЖДА ОТ БАЗАТА.</b> За ЗЗО чл. 40 ал. 1 т. 5 той винаги е
   * работодателят, но за б. „б“ зависи от ПОВОДА на отпуска: лицето при отпуск по негово желание,
   * работодателят при чл. 167а КТ. Извод от базата би дал верен отговор в единия случай и грешен в
   * другия — най-лошата форма на грешка.
   *
   * @param healthBaseKind {@code ART40_1_5} или {@code HEALTH_ONLY} — виж {@code
   *     bg.health.base.kind.select}
   * @param employerBorne {@code true} за перото на осигурителя (CREDIT), {@code false} за лицето
   * @param base осигурителният доход, върху който се дължи вноската — влиза в {@code bgQuantity} и
   *     оттам пълни т. 17 или т. 27 на Декларация обр. 1
   * @param ratePercent ставката в проценти — влиза в {@code bgRate} и оттам в т. 18 или т. 28
   * @param amount вноската в левове — вече закръглена от резолвера, НЕ се смята тук
   */
  public BgPaySlipLineOutcome upsertSpecialHealthBase(
      BgPaySlip slip,
      String healthBaseKind,
      boolean employerBorne,
      BigDecimal base,
      BigDecimal ratePercent,
      BigDecimal amount) {

    if (!isEligibleDraft(slip)) {
      return BgPaySlipLineOutcome.legalZero(
          GROUP_LABEL,
          "фишът не е чернова или е заличаващо копие — специалната здравна вноска НЕ се пише."
              + " Заличаващото копие носи числата на оригинала дословно: те идентифицират записа.");
    }
    if (isZero(amount)) {
      // ⚠️ Нулата маха и стария СИ ред върху тази база — иначе той остава със сумата от
      // предишното пресмятане. Чужд и ръчен ред не се пипат.
      String zeroSign = employerBorne ? BgPaySlipResolver.CREDIT : BgPaySlipResolver.DEBIT;
      BgGeneratedLines.removeStale(
          slip,
          line ->
              BgPaySlipResolver.groupOf(line) == BgPaySlipResolver.GROUP_CONTRIBUTIONS
                  && zeroSign.equals(line.getBgSign())
                  && healthBaseKind.equals(BgD1ContributionAggregator.healthBaseOf(line)));
      return BgPaySlipLineOutcome.legalZero(
          GROUP_LABEL,
          "здравната вноска върху база „"
              + healthBaseKind
              + "“ е нула — перо НЕ се създава. Ред с 0,00 би твърдял, че вноска се дължи.");
    }

    String sign = employerBorne ? BgPaySlipResolver.CREDIT : BgPaySlipResolver.DEBIT;
    BgPaySlipLineType type = lineTypeFor(HEALTH_FUND_CODE, sign);
    if (type == null) {
      throw new IllegalStateException(
          "речникът с типове пера няма ред за фонд „"
              + HEALTH_FUND_CODE
              + "“ със знак "
              + sign
              + " — здравната вноска "
              + amount.toPlainString()
              + " върху база „"
              + healthBaseKind
              + "“ не може да стане перо. Виж"
              + " bg_pay_slip_line_type_contribution.csv и пусни App Install.");
    }

    String code = type.getCode();
    BgPaySlipLine line = BgGeneratedLines.find(slip, l -> matches(l, code, healthBaseKind));
    if (line == null) {
      line = lineFrom(type, amount);
      line.setBgContributionFund(type.getBgContributionFund());
      line.setBgHealthBaseKind(healthBaseKind);
      addLine(slip, line);
    }
    line.setBgIsGenerated(true);
    line.setBgAmount(amount);
    line.setBgQuantity(base);
    line.setBgRate(ratePercent);
    return BgPaySlipLineOutcome.computed(type.getCode(), amount, base, ratePercent);
  }

  /**
   * ПРИЧИНАТА ЕДНА ВНОСКА ДА НЕ СТАНЕ ПЕРО, или {@code null}, когато няма такава.
   *
   * <p>⚠️ ДВЕТЕ ПРОВЕРКИ ТУК НЕ ПИПАТ РЕЧНИКА, и това е нарочно: за вноска, която няма да стане
   * ред, речникът не бива да се пита изобщо. Инак нулевата вноска за фонд без ред в речника би
   * вдигнала изключение — тоест дупка в seed-а щеше да се обажда за перо, което и без това не се
   * създава.
   *
   * <p>⚠️ НУЛЕВА СУМА НЕ ПРАВИ РЕД, И ТОВА НЕ Е СЪЩОТО КАТО ОТКАЗ. Резолверът вече НЕ строи ред за
   * нулева ставка — фонд ГВРС е точно този случай, защото ЗБДОО чл. 15 ал. 1 спира вноската му за
   * 2026 г. и двете ѝ ставки са 0,0000 с вид LEGAL_ZERO. Тоест нула тук идва по ДРУГ път:
   * закръглена до нула сума върху много малка база. Ред с 0,00 би заел място на фиша и би твърдял,
   * че вноска се дължи; отказ би поискал някой да поправи нещо. Нито едното е вярно.
   */
  protected BgPaySlipLineOutcome problem(BgContributionLine contribution) {
    String sign = signFor(contribution);
    String fund = contribution.getFundCode();

    if (fund == null || fund.trim().isEmpty() || sign == null) {
      return BgPaySlipLineOutcome.refused(
          GROUP_LABEL,
          "вноска без фонд или без разпознаваем платец ("
              + contribution
              + ") — перо НЕ се създава, защото типът се намира по ДВОЙКАТА фонд и знак.");
    }
    if (isZero(contribution.getAmount())) {
      return BgPaySlipLineOutcome.legalZero(
          fund.trim() + "/" + sign,
          "вноската за фонд "
              + fund.trim()
              + " ("
              + payerLabel(contribution)
              + ") е нула — перо НЕ се създава. Ред с 0,00 би твърдял, че вноска се дължи.");
    }
    return null;
  }

  /**
   * РЕДЪТ НА РЕЧНИКА за тази вноска. ⚠️ ЛИПСВАЩИЯТ Е ИЗКЛЮЧЕНИЕ, не тихо прескачане.
   *
   * <p>Това не е дефект на данните, а дупка във файла с данни, и засяга ВСЕКИ фиш. Мълчаливо
   * прескачане би оставило вноска НЕУДЪРЖАНА — тоест лицето получава повече, отколкото му се дължи,
   * декларацията излиза с друга сума, и фишът изглежда напълно нормален. Същият избор като {@code
   * BgEarningsService#upsert} и {@code BgSickLeaveService#upsert}.
   */
  protected BgPaySlipLineType requireLineType(BgContributionLine contribution) {
    String fund = contribution.getFundCode().trim();
    String sign = signFor(contribution);
    BgPaySlipLineType type = lineTypeFor(fund, sign);
    if (type == null) {
      throw new IllegalStateException(
          "речникът с типове пера няма ред за фонд „"
              + fund
              + "“ със знак "
              + sign
              + " ("
              + payerLabel(contribution)
              + ") — осигурителната вноска "
              + contribution.getAmount().toPlainString()
              + " не може да стане перо. Групата 5xx е ОГЛЕДАЛНА на речника на фондовете: по един ред"
              + " за всеки фонд и за всеки платец. Виж"
              + " bg_pay_slip_line_type_contribution.csv и пусни App Install"
              + ".");
    }
    return type;
  }

  /**
   * Нула ли е сумата — с {@code null} за нула.
   *
   * <p>⚠️ ДВЕТЕ СЕ СЛИВАТ НАРОЧНО, и точно тук това е безопасно: {@link BgContributionLine} е ръчно
   * написан и Axelor-free, тоест сумата му НАИСТИНА може да е {@code null} (генерираните getter-и
   * са другата история — виж {@code BgDefaultedGetterTest}). А за този клас „нула“ и „няма сума“
   * водят до едно и също: ред НЕ се прави. Разлика, която не мени изхода, не бива да се пази с два
   * клона.
   *
   * <p>⚠️ Проверката живее в МЕТОД, а не на място: написана до {@code getAmount()}, тя изглежда
   * точно като мъртвото условие, срещу което модулът има тест — и следващият читател не може да
   * различи двете, без да отвори другия клас.
   */
  protected static boolean isZero(BigDecimal value) {
    return value == null || value.signum() == 0;
  }

  /**
   * ЗНАКЪТ ПО ПЛАТЕЦА — ОСИГУРИТЕЛ дава CREDIT, ЛИЦЕ дава DEBIT.
   *
   * <p>⚠️ Изборът е ИЗЧЕРПАТЕЛЕН по {@code switch}, а не {@code payer == EMPLOYER ? CREDIT :
   * DEBIT}. Второто превръща ВСЯКА бъдеща трета стойност на изброимото в „удръжка от лицето“
   * мълчаливо — тоест новият платец би започнал да се вади от заплатата на служителя. Тук трета
   * стойност дава {@code null} и оттам явен отказ.
   */
  protected String signFor(BgContributionLine contribution) {
    if (contribution == null || contribution.getPayer() == null) {
      return null;
    }
    switch (contribution.getPayer()) {
      case EMPLOYER:
        return BgPaySlipResolver.CREDIT;
      case INSURED:
        return BgPaySlipResolver.DEBIT;
      default:
        return null;
    }
  }

  /** „осигурител“ или „лице“ — за съобщенията, на езика на фиша. */
  protected String payerLabel(BgContributionLine contribution) {
    if (contribution == null || contribution.getPayer() == null) {
      return "непознат платец";
    }
    return contribution.getPayer() == BgContributionLine.Payer.EMPLOYER
        ? "за сметка на осигурителя"
        : "за сметка на лицето";
  }

  /**
   * ОТКАЗЪТ ЗА ЦЯЛАТА ГРУПА, когато веригата не е стигнала до редове.
   *
   * <p>⚠️ Носи СТАТУСА на резултата дословно, защото той е онова, което праща читателя на верния
   * файл: {@code INPUT_NOT_ASSEMBLED} значи „поправи договора“, {@code NO_CONTRIBUTIONS_FOUND} —
   * „поправи seed-а“, {@code MISSING_RATE} — „ставката никога не е установена от първичен
   * източник“. Свити до „вноските липсват“, всички щяха да пращат на едно и също място.
   */
  protected BgPaySlipLineOutcome refusalFor(BgContributionResult result) {
    if (result == null) {
      return BgPaySlipLineOutcome.refused(
          GROUP_LABEL,
          "веригата на осигуровките не е върнала резултат — нито едно перо от 5xx не се създава."
              + " ⚠️ Празна група тук НЕ значи „вноски не се дължат“.");
    }
    return BgPaySlipLineOutcome.refused(
        GROUP_LABEL,
        "осигуровките не са изчислени докрай — "
            + result.getStatus()
            + (result.getDetail() == null ? "" : ": " + result.getDetail())
            + ". Нито едно перо от 5xx не се създава, а фишът няма да се потвърди, докато това не се"
            + " уреди (виж BgPaySlipService#contributionRefusal).");
  }

  /**
   * РЕДОВЕТЕ СА ЗАПИСАНИ, НО ДАННИТЕ СЕ ЗНАЯТ ЗА НЕПЪЛНИ — обявено, не премълчано.
   *
   * <p>{@code RESOLVED_INCOMPLETE} е „сметнато и годно за ползване, но не за деклариране“: днес го
   * вдига кохорта WITH_UPF с пенсионен ред без ред за УПФ, тоест пет пункта, които тихо не се
   * начисляват. Перата се записват — те са верни за фондовете, които ги има, — а потвърждаването
   * остава блокирано от пазача. Изхвърлено, това състояние щеше да изглежда като чисто сметнат фиш.
   */
  protected BgPaySlipLineOutcome incompleteNotice(BgContributionResult result) {
    return BgPaySlipLineOutcome.divergent(
        GROUP_LABEL,
        result.total(),
        null,
        null,
        "перата от 5xx са записани (общо "
            + result.total().toPlainString()
            + "), но данните се знаят за НЕПЪЛНИ — "
            + result.getStatus()
            + (result.getDetail() == null ? "" : ": " + result.getDetail())
            + ". Фишът не се потвърждава в това състояние.");
  }

  /** Чернова, която НЕ е заличаваща — редовна или коригираща. */
  protected boolean isEligibleDraft(BgPaySlip slip) {
    if (slip == null || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      return false;
    }
    return !Boolean.TRUE.equals(slip.getBgIsDeletion());
  }

  /**
   * Записва перото — ново или обновено.
   *
   * <p>Количеството (базата) и ставката се пишат ЗАЕДНО със сумата: те са документацията ѝ.
   * Обновяването ги пренаписва и двете, инак на обновен ред щяха да останат числата от предишното
   * смятане и да обясняват друга сума. Същият идиом като {@code BgEarningsService#upsert}.
   *
   * <p>⚠️ ТИПЪТ СЕ ПОДАВА, не се търси пак: {@link #generate} вече го е намерил и второ търсене е
   * втори прочит на едно и също нещо.
   *
   * <p>⚠️ Препратката към фонда се слага и на ПЕРОТО, не само на типа: {@code
   * BgPaySlipLine.bgContributionFund} съществува точно за това и без нея справка по фонд трябва да
   * минава през типа, тоест през речника — който междувременно може да се е променил.
   */
  protected void upsert(BgPaySlip slip, BgPaySlipLineType type, BgPaySlipLineOutcome outcome) {
    upsert(slip, type, outcome, null);
  }

  /**
   * Същото, за вноските върху обезщетение по вид 27 — виж {@link #generate(BgPaySlip,
   * BgContributionResult, BgCompensation)}.
   */
  protected void upsert(
      BgPaySlip slip,
      BgPaySlipLineType type,
      BgPaySlipLineOutcome outcome,
      BgCompensation compensation) {
    BgPaySlipLine line =
        BgGeneratedLines.find(
            slip,
            l ->
                matches(l, outcome.lineCode, BgD1ContributionAggregator.BASE_STANDARD)
                    && sameCompensation(l, compensation));
    if (line == null) {
      line = lineFrom(type, outcome.amount);
      line.setBgContributionFund(type.getBgContributionFund());
      // ⚠️ ПРЕПРАТКАТА СЕ СЛАГА ПРЕДИ ЗАКАЧАНЕТО: тя е ключът на идемпотентността, както при
      // перото на обезщетението (BgCompensationLineService#upsert).
      line.setBgCompensation(compensation);
      addLine(slip, line);
    }
    // ⚠️ Белязан ВСЕКИ път, не само при създаване: осиновеният заварен ред (виж BgGeneratedLines)
    // става на генератора тук и от следващото пресмятане може да бъде махнат, ако фондът отпадне.
    line.setBgIsGenerated(true);
    line.setBgAmount(outcome.amount);
    line.setBgQuantity(outcome.quantity);
    line.setBgRate(outcome.rate);
  }

  /**
   * Отговаря ли перото на ключа КОД НА ТИП И БАЗА. Кой от отговарящите е на генератора, решава
   * {@link BgGeneratedLines#find}.
   *
   * <p><b>⚠️ КЛЮЧЪТ Е ДВОЕН, И ТОВА НЕ Е РАЗХЛАБВАНЕ.</b> Един код на тип не дава най-много едно
   * перо на фиш, защото здравното има ТРИ бази (виж {@code BgPaySlipLine.bgHealthBaseKind}):
   * нормалният осигурителен доход, доходът по ЗЗО чл. 40 ал. 1 т. 5 и доходът само за здравно.
   * Всяко от трите е ОТДЕЛНА вноска върху ОТДЕЛНА база, а типът перо е един и същи (516 за
   * осигурителя, 517 за лицето) — защото фондът е един.
   *
   * <p>⚠️ ЗАЩО НЕ ТРЕТИ И ЧЕТВЪРТИ ТИП ПЕРО: групата 5xx е ОГЛЕДАЛНА на речника с фондовете, точно
   * двайсет реда, и {@code BgPaySlipLineTypeCatalogueTest} пази съответствието едно към едно.
   * Двайсет и първи тип би описал нещо, което не е фонд, а база.
   *
   * <p>⚠️ И ЗАЩО ПРАЗНОТО СЕ ЧЕТЕ КАТО {@code STANDARD}: всичките заварени пера носят празна
   * колона. Търсене по точно съвпадение на низа би ги обявило за „друга база“ и ГЕНЕРАТОРЪТ ЩЕШЕ ДА
   * ГИ ДУБЛИРА при второ {@code prepare()} — тоест идемпотентността, която този метод пази, щеше да
   * падне тихо върху всеки съществуващ фиш.
   */
  protected static boolean matches(BgPaySlipLine line, String code, String healthBaseKind) {
    return code.equals(codeOf(line))
        && healthBaseKind.equals(BgD1ContributionAggregator.healthBaseOf(line));
  }

  private static String codeOf(BgPaySlipLine line) {
    BgPaySlipLineType type = line.getBgPaySlipLineType();
    return type == null ? null : type.getCode();
  }

  /**
   * Перото е на ТОВА обезщетение — или, при {@code null}, на никое (обикновена вноска).
   *
   * <p>⚠️ Сравнява се по обект, а при запазен документ — и по идентификатор: {@code prepare()} чете
   * документите наново, тоест втория път идват други инстанции на същия ред (същият довод като
   * {@code BgCompensationLineService#lineWithCompensation}).
   */
  static boolean sameCompensation(BgPaySlipLine line, BgCompensation compensation) {
    BgCompensation carried = line.getBgCompensation();
    if (compensation == null || carried == null) {
      return compensation == carried;
    }
    return carried == compensation
        || (carried.getId() != null && carried.getId().equals(compensation.getId()));
  }

  // ═══ ШЕВОВЕТЕ КЪМ БАЗАТА (подменят се в тестовете) ════════════════════════

  /**
   * Редът на речника за ДВОЙКАТА фонд и знак, или {@code null}.
   *
   * <p>⚠️ Само активните, по същия довод като {@code BgEarningsService#lineTypes}: закрит тип перо
   * е бил валиден за минали периоди и остава видим там, но нов фиш не бива да го носи.
   *
   * <p>⚠️ {@code fetchOne()}, а не {@code fetch().get(0)}: два реда за една двойка са дефект на
   * seed-а и трябва да се обадят, вместо единият да бъде избран наслуки. Съответствието едно към
   * едно е заковано и в двете половини — {@code BgPaySlipLineTypeSeedMappingTest} (файлове) и
   * {@code BgPaySlipLineTypeCatalogueTest} (база).
   */
  protected BgPaySlipLineType lineTypeFor(String fundCode, String sign) {
    return Beans.get(BgPaySlipLineTypeRepository.class)
        .all()
        .filter(
            "self.bgContributionFund.code = :fund AND self.bgSign = :sign AND self.active IS TRUE")
        .bind("fund", fundCode)
        .bind("sign", sign)
        .fetchOne();
  }

  /**
   * Ново перо от речника — ПРЕЗ {@code BgPaySlipService#lineFrom}, за да остане ЕДНО мястото,
   * където речникът се чете и замразява по копиране. Тук флаговете СА правилото: осигуровката НЕ е
   * доход, тоест и двата ѝ флага са лъжа, ако някой ги вдигне.
   */
  protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
    return Beans.get(BgPaySlipService.class).lineFrom(type, amount);
  }

  /** Закачането от двете страни на връзката — o2m с mappedBy не се пълни сам. */
  protected void addLine(BgPaySlip slip, BgPaySlipLine line) {
    if (slip.getBgPaySlipLineList() == null) {
      slip.setBgPaySlipLineList(new ArrayList<>());
    }
    line.setPaySlip(slip);
    slip.getBgPaySlipLineList().add(line);
  }
}
