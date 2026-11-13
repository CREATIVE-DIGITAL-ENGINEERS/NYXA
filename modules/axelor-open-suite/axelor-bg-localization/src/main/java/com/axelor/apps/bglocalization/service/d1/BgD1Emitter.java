package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuranceContribution;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPayrollParameter;
import com.axelor.apps.bglocalization.db.repo.BgD1RecordRepository;
import com.axelor.apps.bglocalization.db.repo.BgInsuranceContributionRepository;
import com.axelor.apps.bglocalization.db.repo.BgInsuredTypeRepository;
import com.axelor.apps.bglocalization.db.repo.BgPayrollParameterRepository;
import com.axelor.apps.bglocalization.service.BgInsurableIncomeService;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.inject.Beans;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;

/**
 * ЕМИТЕРЪТ: фишовете на един месец → записите на една Декларация обр. № 1.
 *
 * <p>Тук живеят двете неща, които {@link BgD1RecordBuilder} нарочно няма — <b>търсенето в
 * речника</b> (темпорално, тоест иска заявка) и <b>отказът</b>. Строенето на самия запис е чиста
 * функция и се проверява без база; свързването с базата е тук и е тънко.
 *
 * <p><b>⚠️ ОТКАЗЪТ Е ИЗХОД, НЕ ИЗКЛЮЧЕНИЕ.</b> Фиш, който не бива да влезе в декларацията, оставя
 * ред в {@code bgRefusalReport} с НАЗОВАНА причина. Мълчаливо прескочен фиш е най-скъпият дефект
 * тук: декларацията изглежда пълна, а едно лице липсва — и това се открива от НАП, не от нас.
 */
@Singleton
public class BgD1Emitter {

  /** Кодът на фонда в речника на вноските. Огледален на BgContributionFund.code. */
  private static final String GVRS_FUND_CODE = "GVRS";

  private final BgD1Validator validator;
  private final BgD1DaySource daySource;
  private final BgD1HourSource hourSource;

  @Inject
  public BgD1Emitter(BgD1Validator validator, BgD1DaySource daySource, BgD1HourSource hourSource) {
    this.validator = validator;
    this.daySource = daySource;
    this.hourSource = hourSource;
  }

  /** Какво излезе от едно емитиране — записите и всичко, което трябва да види човек. */
  public static final class Outcome {
    private final List<BgD1Record> records = new ArrayList<>();
    private final List<BgD1FindingDto> refusals = new ArrayList<>();
    private final List<BgD1FindingDto> findings = new ArrayList<>();

    public List<BgD1Record> getRecords() {
      return records;
    }

    /** Фишовете, за които запис НЕ е произведен — с причина. */
    public List<BgD1FindingDto> getRefusals() {
      return refusals;
    }

    /** Находките на валидатора и обявените неустановени условия. */
    public List<BgD1FindingDto> getFindings() {
      return findings;
    }

    public String refusalReport() {
      return join(refusals);
    }

    public String findingReport() {
      return join(findings);
    }

    private static String join(List<BgD1FindingDto> list) {
      StringBuilder sb = new StringBuilder();
      for (BgD1FindingDto f : list) {
        if (sb.length() > 0) {
          sb.append('\n');
        }
        sb.append(f.describe());
      }
      return sb.toString();
    }
  }

  /**
   * Емитира записите за подадените фишове.
   *
   * <p>Периодът на ДАННИТЕ се взима от декларацията и се налага на всички записи — той пълни т. 2 и
   * т. 3. ⚠️ Той е и датата, за която се търси редът в речника: номенклатурата е темпорална и
   * коригираща Д1 за минал период ползва кодовете, валидни ТОГАВА.
   */
  public Outcome emit(BgD1Declaration declaration, List<BgPaySlip> slips) {
    Outcome outcome = new Outcome();
    if (declaration == null || slips == null) {
      return outcome;
    }

    Period dataPeriod = declaration.getBgDataPeriod();
    LocalDate on = dataPeriod == null ? null : dataPeriod.getFromDate();

    // ⚠️ БЕЗ ДАТА НЕ СЕ ТЪРСИ В РЕЧНИКА, И ОТКАЗЪТ СЕ НАЗОВАВА ТУК. Инак всеки фиш би получил
    // отказа „видът осигурен не е намерен в речника“ — правдоподобна причина, сочеща напълно
    // грешно място: речникът е наред, липсва периодът, за който да се търси в него. Причина, която
    // праща човек да поправя вярното, е по-скъпа от липсваща причина.
    if (on == null) {
      outcome
          .getRefusals()
          .add(
              new BgD1FindingDto(
                  "D1_NO_DATA_PERIOD",
                  BgD1FindingDto.SEVERITY_REFUSAL,
                  "периодът на данните няма начална дата — т. 2 и т. 3 нямат източник, а без месец"
                      + " декларацията не се приема (чл. 11 ал. 1). Номенклатурата на видовете"
                      + " осигурен е ТЕМПОРАЛНА и без дата не може да се търси в нея, тоест НИТО"
                      + " ЕДИН запис не може да бъде построен."));
      return outcome;
    }

    Set<String> gvrsCodes = gvrsEligibleCodes();

    // ⚠️ ТАВАНЪТ ЗА Т. 34 СЕ ЧЕТЕ ВЕДНЪЖ ЗА ЦЯЛАТА ДЕКЛАРАЦИЯ, а не на всеки запис: той е свойство
    // на ПЕРИОДА, не на лицето. Прочетен в цикъла, той би бил една и същa заявка, повторена по
    // веднъж на фиш — и щеше да е ТЕМПОРАЛНО същият, тоест повторението не носи нищо.
    BigDecimal netPayCap = netPayCapFor(on);

    for (BgPaySlip slip : slips) {
      if (slip == null) {
        continue;
      }
      // ⚠️ ДВА ВИДА РЕДОВЕ ОТ ЕДИН ФИШ. Редът по вида осигурен на носителя (01 …) е за СОБСТВЕНИЯ
      // период на фиша; редовете 27 са за всяка част на обезщетение по чл. 222 ал. 1 КТ, чийто
      // месец е периодът на декларацията — и фишът, който носи обезщетението, може да е от ДРУГ
      // месец (BgEffectivePaySlips#compensationCarriersFor). Виж BgCompensationParts.
      List<BgCompensationParts.Carried> carried = BgCompensationParts.carried(slip);
      if (ownPeriod(slip, dataPeriod)
          && !BgD1DeclarationKind.isSupplementary(declaration)
          && !BgCompensationParts.onlyCompensation(slip)) {
        emitRegular(outcome, declaration, slip, dataPeriod, on, gvrsCodes, netPayCap);
      }
      for (BgCompensationParts.Carried c : carried) {
        emitCompensationParts(outcome, declaration, slip, c, carried, dataPeriod, on, gvrsCodes);
      }
    }

    // ═══ ЕДИН ФАЙЛ, ЕДНО ТВЪРДЕНИЕ ЗА ЛИЦЕ ═══
    // ⚠️ ПРОВЕРЯВА СЕ НАКРАЯ, върху ЦЕЛИЯ набор, защото смесването е свойство на ФАЙЛА, не на
    // запис.
    // Проверено на всеки запис поотделно, правилото не може да го види: всеки от двата е допустим
    // сам за себе си.
    String mixed = BgD1CorrectionRules.refuseMixedCodes(outcome.getRecords());
    if (mixed != null) {
      outcome
          .getFindings()
          .add(new BgD1FindingDto("D1_MIXED_CODES", BgD1FindingDto.SEVERITY_ERROR, mixed).at("1"));
    }
    return outcome;
  }

  /** Редът по вида осигурен на носителя — заварената логика, изнесена от цикъла. */
  protected void emitRegular(
      Outcome outcome,
      BgD1Declaration declaration,
      BgPaySlip slip,
      Period dataPeriod,
      LocalDate on,
      Set<String> gvrsCodes,
      BigDecimal netPayCap) {
    // ⚠️ ВИДЪТ ОСИГУРЕН ИДВА ОТ НОСИТЕЛЯ, а не от договора. Самоосигуряващото се лице няма
    // договор — КСО чл. 6 ал. 8: вноските са „за сметка на осигурените лица“, тоест осигурител
    // не съществува — и отказът „няма вид осигурен (т. 12)“ би сочил напълно грешно място: кодът
    // е на регистрацията.
    BgD1Carrier carrier = BgD1Carrier.of(slip);
    EmploymentContract contract = carrier.getContract();
    String code = carrier.insuredTypeCode();
    BgInsuredType type = insuredTypeFor(code, on);

    String refusal = BgD1InsuredTypeRules.refusalReason(code, type);
    if (refusal != null) {
      outcome
          .getRefusals()
          .add(
              new BgD1FindingDto("D1_REFUSED", BgD1FindingDto.SEVERITY_REFUSAL, refusal)
                  .on(slip.getReference(), code));
      return;
    }

    // ⚠️ ДНИТЕ СЕ ЧЕТАТ ПРЕДИ ЗАПИСА, и отказите им НЕ спират декларацията. BgD1DaySource
    // връща и проблемите, които е срещнал; те стават НАХОДКИ, а не откази — една сгрешена заявка
    // за отпуск на едно лице не бива да спре декларация за десетки. Виж javadoc-а му.
    BgD1DaySource.Outcome days = daySource.read(slip, dataPeriod);
    for (String problem : days.problems) {
      outcome
          .getFindings()
          .add(
              new BgD1FindingDto("D1_DAYS_NOT_COUNTED", BgD1FindingDto.SEVERITY_WARNING, problem)
                  .on(slip.getReference(), code)
                  .at("16"));
    }
    for (String unknown : BgD1DayBuckets.unknownBuckets(days.entries)) {
      outcome
          .getFindings()
          .add(
              new BgD1FindingDto(
                      "D1_UNKNOWN_DAY_BUCKET",
                      BgD1FindingDto.SEVERITY_ERROR,
                      "видът отсъствие сочи кофа „"
                          + unknown
                          + "“, която не е нито една от шестте на т. 16 (16.1–16.5, 16.А)."
                          + " Дните му НЕ са включени никъде. ⚠️ Провери и БУКВАТА на т. 16.А —"
                          + " тя е КИРИЛСКА „А“ (U+0410); латинското „A“ е друг низ и дава точно"
                          + " тази находка.")
                  .on(slip.getReference(), code)
                  .at("16"));
    }

    // ⚠️ ЧАСОВЕТЕ СЕ ЧЕТАТ ЗА ЛИЦЕТО, а дните — за ФИША. Двете не са едно и също: отчетът за
    // време и допълнителните часове висят на Employee, а болничните дни са замразени на фиша. При
    // две правоотношения на едно лице това е ИЗВЕСТНО ОГРАНИЧЕНИЕ: часовете идват общо и биха
    // влезли и в двете декларации. Записано, за да не се сметне за уредено — TimesheetLine няма
    // препратка към трудов договор.
    // ⚠️ ЧАСОВЕТЕ СЕ ЧЕТАТ САМО ЗА НАЕТИЯ. За кодове 12, 13, 22 и 41 указанието към т. 16.7/16.8
    // ги оставя ПРАЗНИ (bgNoHours = true — измерено в seed-а), а източникът им — отчетът за време
    // — виси на трудово правоотношение. Заявка за самоосигуряващо се лице щеше да върне часовете
    // му по ДРУГО правоотношение, ако има такова, и да ги припише на този запис.
    BgD1HourSource.Hours hours =
        carrier.isSelfInsured()
            ? null
            // ⚠️ ФИШЪТ ВЛИЗА ПЪРВИ: носи ли замразени часове, те печелят пред живия отчет —
            // виж BgD1HourSource#read(BgPaySlip, ...). Без него декларацията чете отчет, който
            // може да е редактиран СЛЕД като фишът вече е платен по него.
            : hourSource.read(slip, carrier.employee(), on, dataPeriod.getToDate());

    BgD1Record record =
        BgD1RecordBuilder.build(
            slip,
            type,
            dataPeriod,
            // ⚠️ ГВРС-КОДОВЕТЕ СЛИЗАТ И ДО СТРОИТЕЛЯ, не само до валидатора. Те са отговорът на
            // „кои са трудовите правоотношения“ и т. 29 го иска ПРЕДИ да напише числото — инак
            // договорът за управление и контрол (код 10) получава брутна работна заплата, която
            // не съществува, и валидаторът я обявява за грешка върху верен фиш.
            seeingWithoutParts(
                slip,
                new BgD1Facts(
                    BgD1DayBuckets.sum(days.entries),
                    days.interruptions,
                    hours,
                    netPayCap,
                    gvrsCodes,
                    // ⚠️ ДНИТЕ ИЗВЪН ОСИГУРЯВАНЕТО ПЪТУВАТ ОТДЕЛНО ОТ КОФИТЕ, защото правят отделно
                    // твърдение: те се ВАДЯТ от т. 16.1, вместо да пълнят точка. Слети в картата на
                    // кофите, щяха да влязат в сбора по заб. 1 — тоест да се обявят за дни в
                    // осигуряване точно дните, през които то е прекъснато (същите дати, които
                    // days.interruptions подава на т. 14/15).
                    days.outsideInsuranceDays)),
            outcome.getFindings());
    // ⚠️ В РЕДОВНА ДЕКЛАРАЦИЯ ЗАПИСЪТ Е БЕЗ КОД, ДОРИ ЗА КОРИГИРАЩО КОПИЕ. Там копието е
    // ДЕЙСТВАЩИЯТ фиш на корекция, направена преди подаването: НАП не е виждала стария запис,
    // тоест „К“ би коригирал нещо, което тя не познава (чл. 9 ал. 4). Строителят чете кода от
    // признаците на фиша и не знае вида на декларацията — затова решението е тук.
    if (!BgD1DeclarationKind.isCorrecting(declaration)) {
      record.setT1CorrectionCode(null);
    }
    record.setBgD1Declaration(declaration);
    outcome.getRecords().add(record);
    outcome.getFindings().addAll(validator.validate(record, gvrsCodes));

    // ═══ ЧЛ. 9 АЛ. 1 Т. 1 — петте реквизита, които корекцията НЕ мени ═══
    // ⚠️ ПРЕДИШНИЯТ ЗАПИС ИДВА ОТ ФИША, не от нова заявка: BgPaySlip.bgCorrectionOf сочи
    // оригинала, а неговият запис е ЕДИНСТВЕНОТО, което НАП вече е приел за това лице и месец.
    // Заявка по петте реквизита би търсила по ключа, който тъкмо се проверява за промяна — тоест
    // при сменен ЕГН нищо не би се намерило и отказът щеше да е „предишният не е намерен“, което
    // сочи напълно грешно място.
    String correctionRefusal =
        BgD1CorrectionRules.refuseCorrection(record, previousRecordOf(slip, dataPeriod, code));
    if (correctionRefusal != null) {
      outcome
          .getFindings()
          .add(
              new BgD1FindingDto(
                      "D1_CHL9_AL1_IMMUTABLE", BgD1FindingDto.SEVERITY_ERROR, correctionRefusal)
                  .on(slip.getReference(), code)
                  .at("1"));
    }
  }

  /**
   * РЕДОВЕТЕ 27 НА ЕДНО ОБЕЗЩЕТЕНИЕ — по един за всяка негова част, чийто месец е периодът на
   * декларацията ({@link BgCompensationParts}).
   *
   * <p>⚠️ ЧАСТ, КОЯТО НЕ МОЖЕ ДА СЕ ИЗВЕДЕ, Е НАХОДКА ERROR, НЕ ТИХ ПРОПУСК: празен списък значи
   * или период без нито един работен ден, или календар, който не покрива годината — и в двата
   * случая лицето би изчезнало от Д1 за месеца, в който е без работа.
   */
  protected void emitCompensationParts(
      Outcome outcome,
      BgD1Declaration declaration,
      BgPaySlip slip,
      BgCompensationParts.Carried carried,
      List<BgCompensationParts.Carried> all,
      Period dataPeriod,
      LocalDate on,
      Set<String> gvrsCodes) {
    String code = BgCompensationParts.INSURED_TYPE;
    BgInsuredType type = insuredTypeFor(code, on);
    String refusal = BgD1InsuredTypeRules.refusalReason(code, type);
    if (refusal != null) {
      outcome
          .getRefusals()
          .add(
              new BgD1FindingDto("D1_REFUSED", BgD1FindingDto.SEVERITY_REFUSAL, refusal)
                  .on(slip.getReference(), code));
      return;
    }

    List<BgCompensationParts.Share> shares;
    try {
      shares = compensationShares(slip, carried, all);
    } catch (RuntimeException e) {
      outcome
          .getFindings()
          .add(
              new BgD1FindingDto(
                      "D1_T27_CALENDAR",
                      BgD1FindingDto.SEVERITY_ERROR,
                      "частите на обезщетението „"
                          + carried.reference()
                          + "“ не могат да се изведат: "
                          + e.getMessage())
                  .on(slip.getReference(), code)
                  .at("16"));
      return;
    }
    if (shares.isEmpty()) {
      outcome
          .getFindings()
          .add(
              new BgD1FindingDto(
                      "D1_T27_NO_PARTS",
                      BgD1FindingDto.SEVERITY_ERROR,
                      "обезщетението „"
                          + carried.reference()
                          + "“ няма нито една част: периодът от деня след прекратяването ("
                          + BgCompensationParts.periodStart(carried.getDoc().getBgEventDate())
                          + ") до същата дата следващия месец няма нито един работен ден, или"
                          + " документът няма дата на основанието. Редът 27 НЕ е построен —"
                          + " провери датата на прекратяване.")
                  .on(slip.getReference(), code)
                  .at("16"));
      return;
    }

    // ⚠️ ФИШЪТ И ЧАСТИТЕ ТРЯБВА ДА СА ВЪРХУ ЕДНА БАЗА: вноските са начислени върху сбора на частите
    // под общия таван с вид 01, а частите се изчисляват наново тук. Разминат ли се (фиш по вид 01
    // за
    // месеца е потвърден след носителя; заварен фиш отпреди тавана), остатъкът на последната част
    // би погълнал разликата тихо — затова е ERROR с указание, не сметка.
    BigDecimal due = BgCompensationParts.insurableTotal(segmentsOf(shares));
    BigDecimal charged = BgCompensationParts.chargedBase(carried);
    if (charged == null ? due.signum() != 0 : charged.compareTo(due) != 0) {
      outcome
          .getFindings()
          .add(
              new BgD1FindingDto(
                      "D1_T27_CEILING_STALE",
                      BgD1FindingDto.SEVERITY_ERROR,
                      "вноските по вид 27 върху обезщетението „"
                          + carried.reference()
                          + "“ са начислени върху "
                          + (charged == null ? "0.00" : charged.toPlainString())
                          + ", а под общия таван с вид 01 (КСО чл. 6 ал. 11) частите се полагат"
                          + " върху "
                          + due.toPlainString()
                          + ". Преизчисли фиша "
                          + slip.getReference()
                          + " и генерирай отново.")
                  .on(slip.getReference(), code)
                  .at(BgD1Point.T21_INSURANCE_INCOME));
    }

    for (BgCompensationParts.Share share : shares) {
      LocalDate partMonth = share.getSegment().getFrom();
      if (partMonth.getYear() != on.getYear() || partMonth.getMonthValue() != on.getMonthValue()) {
        continue;
      }
      BgD1Record record =
          BgD1RecordBuilder.buildCompensationPart(
              slip, type, dataPeriod, share, outcome.getFindings());
      if (!BgD1DeclarationKind.isCorrecting(declaration)) {
        record.setT1CorrectionCode(null);
      }
      record.setBgD1Declaration(declaration);
      outcome.getRecords().add(record);
      outcome.getFindings().addAll(validator.validate(record, gvrsCodes));

      String correctionRefusal =
          BgD1CorrectionRules.refuseCorrection(record, previousRecordOf(slip, dataPeriod, code));
      if (correctionRefusal != null) {
        outcome
            .getFindings()
            .add(
                new BgD1FindingDto(
                        "D1_CHL9_AL1_IMMUTABLE", BgD1FindingDto.SEVERITY_ERROR, correctionRefusal)
                    .on(slip.getReference(), code)
                    .at("1"));
      }
    }
  }

  /**
   * ЧАСТИТЕ НА ЕДНО ОБЕЗЩЕТЕНИЕ, с вноските и данъка им — шев към календара.
   *
   * @param all всички обезщетения по вид 27 на фиша: данъкът на фиша се дели между тях
   */
  protected List<BgCompensationParts.Share> compensationShares(
      BgPaySlip slip, BgCompensationParts.Carried carried, List<BgCompensationParts.Carried> all) {
    return BgCompensationCalendar.sharesOf(slip, carried, all);
  }

  private static List<BgCompensationParts.Segment> segmentsOf(
      List<BgCompensationParts.Share> shares) {
    List<BgCompensationParts.Segment> out = new ArrayList<>();
    for (BgCompensationParts.Share share : shares) {
      out.add(share.getSegment());
    }
    return out;
  }

  /** Фишът е от периода на декларацията — сравнено по идентификатор, а без него по началото. */
  static boolean ownPeriod(BgPaySlip slip, Period dataPeriod) {
    Period own = slip == null ? null : slip.getPeriod();
    if (own == null || dataPeriod == null) {
      // ⚠️ Без период на фиша — заварените свидетели строят фишове без него. Четат се като свои:
      // единственият път, по който чужд фиш стига дотук, е BgEffectivePaySlips, и той носи период.
      return true;
    }
    if (own.getId() != null && dataPeriod.getId() != null) {
      return own.getId().equals(dataPeriod.getId());
    }
    return own.getFromDate() != null && own.getFromDate().equals(dataPeriod.getFromDate());
  }

  /** Фактите на реда 01 за фиш, който носи и обезщетение по вид 27 — без перата и данъка му. */
  static BgD1Facts seeingWithoutParts(BgPaySlip slip, BgD1Facts facts) {
    if (BgCompensationParts.carried(slip).isEmpty()) {
      return facts;
    }
    return facts.seeing(
        BgCompensationParts.withoutPartLines(slip.getBgPaySlipLineList()),
        BgCompensationParts.taxWithoutParts(slip));
  }

  /**
   * Записът от ПОДАДЕНАТА преди това декларация за същото лице и месец, или {@code null}.
   *
   * <p>⚠️ ТЪРСИ СЕ ПРЕЗ ФИША, не по ключа. {@code BgPaySlip.bgCorrectionOf} сочи оригиналния фиш —
   * ретро-механизмът вече е установил кой е той, и по-надеждно, отколкото заявка по петте
   * реквизита: при сменен ЕГН заявката не би намерила нищо и отказът щеше да е „предишният не е
   * намерен“, тоест сочещ грешно място точно в случая, за който правилото съществува.
   *
   * <p>⚠️ Връща {@code null} и когато оригиналът НЕ Е декларирал: фиш без запис в приета Д1 не може
   * да се коригира (чл. 9 ал. 4). Двете причини се различават в текста на отказа.
   *
   * <p><b>⚠️ ТЪРСИ СЕ ПО ВЕРИГАТА, не само по оригинала.</b> Корекция, направена преди подаването,
   * влиза в редовната като ДЕЙСТВАЩ фиш — тоест подаденият запис сочи КОПИЕТО, не оригинала.
   * Следваща корекция на същия оригинал (или корекция на корекцията) трябва да намери точно него.
   * Веригата е оригиналът, неговите предци по {@code bgCorrectionOf} и всяко друго коригиращо копие
   * на тях; самият фиш не участва.
   */
  protected BgD1Record previousRecordOf(BgPaySlip slip, Period dataPeriod, String insuredCode) {
    BgPaySlip original = slip == null ? null : slip.getBgCorrectionOf();
    if (original == null || original.getId() == null) {
      return null;
    }
    List<BgPaySlip> chain = new ArrayList<>();
    for (BgPaySlip link = original;
        link != null && link.getId() != null && !chain.contains(link);
        link = link.getBgCorrectionOf()) {
      chain.add(link);
    }
    try {
      return Beans.get(BgD1RecordRepository.class)
          .all()
          .filter(
              "(self.paySlip IN :chain OR self.paySlip.bgCorrectionOf IN :chain)"
                  + " AND self.paySlip.id <> :selfId"
                  + " AND self.bgD1Declaration.bgDataPeriod = :period"
                  + " AND (self.t1CorrectionCode IS NULL OR self.t1CorrectionCode = '')"
                  // ⚠️ И ПО ВИДА ОСИГУРЕН: фиш с обезщетение по вид 27 има ДВА реда за месеца
                  // (BgCompensationParts) и корекцията на всеки сверява своя.
                  + " AND self.t12InsuredTypeCode = :code")
          .bind("chain", chain)
          .bind("selfId", slip.getId() == null ? -1L : slip.getId())
          .bind("period", dataPeriod)
          .bind("code", insuredCode)
          .order("-id")
          .fetchOne();
    } catch (RuntimeException e) {
      // Липсваща база е СЪСТОЯНИЕ НА СРЕДАТА, не отговор „предишният не съществува“. Тихият null
      // тук би обявил всяка корекция за недопустима.
      return null;
    }
  }

  /**
   * МАКСИМАЛНИЯТ МЕСЕЧЕН ОСИГУРИТЕЛЕН ДОХОД за тази дата — таванът на т. 34 по НПОПДОО чл. 32 ал.
   * 1.
   *
   * <p>⚠️ ЧЕТЕ СЕ СЪЩИЯТ ПАРАМЕТЪР, КОЙТО ОСИГУРИТЕЛНАТА БАЗА ВЕЧЕ ПОЛЗВА ({@code
   * MAX_INSURABLE_INCOME}, {@code BgInsurableIncomeService#CODE_MAX_INSURABLE_INCOME}) — една и
   * същa стойност от ЗБДОО, поискана веднъж от речника с параметри. Втори параметър със свое име би
   * бил второ копие на едно число, което се сменя два пъти в годината (чл. 9 т. 1 и т. 2 ЗБДОО за
   * 2026 г.) и щеше да се разминава точно на границата 31.07/01.08.
   *
   * <p>⚠️ ФИЛТЪРЪТ Е ТЕМПОРАЛЕН И ДОСЛОВНО СЪЩИЯТ като на {@code
   * BgInsurableIncomeService#parameterFor}: коригираща Д1 за минал месец иска стойността, валидна
   * ТОГАВА.
   *
   * <p>Връща {@code null}, когато параметърът липсва или базата не отговаря — тогава т. 34 остава
   * празна с НАЗОВАНА причина, вместо да мине без горна граница.
   */
  protected BigDecimal netPayCapFor(LocalDate on) {
    if (on == null) {
      return null;
    }
    try {
      BgPayrollParameter parameter =
          Beans.get(BgPayrollParameterRepository.class)
              .all()
              .filter(
                  "self.code = :c AND self.validFrom <= :d "
                      + "AND (self.validTo IS NULL OR self.validTo >= :d)")
              .bind("c", BgInsurableIncomeService.CODE_MAX_INSURABLE_INCOME)
              .bind("d", on)
              .fetchOne();
      return parameter == null ? null : parameter.getParamValue();
    } catch (RuntimeException e) {
      // Липсваща база е СЪСТОЯНИЕ НА СРЕДАТА, не отговор „няма таван“. Тихата нула тук би оставила
      // т. 34 без горна граница за всяко лице.
      return null;
    }
  }

  /**
   * Видовете осигурен, за които речникът на вноските дава ред за фонд ГВРС.
   *
   * <p>⚠️ Това Е отговорът на въпроса „кои са трудовите правоотношения“, както системата го знае, и
   * той се ЧЕТЕ, а не се преписва: фонд „Гарантирани вземания“ по устройство се дължи само по
   * трудово правоотношение. Списък в Java би бил второ копие на същото и би остарял при първата
   * редакция на ЗБДОО.
   *
   * <p>Връща {@code null}, когато базата не отговаря — тогава валидаторът ОБЯВЯВА, че правилото не
   * е проверено, вместо да мине зелен.
   */
  protected Set<String> gvrsEligibleCodes() {
    try {
      List<BgInsuranceContribution> rows =
          Beans.get(BgInsuranceContributionRepository.class)
              .all()
              .filter("self.fundCode = :f")
              .bind("f", GVRS_FUND_CODE)
              .fetch();
      Set<String> codes = new LinkedHashSet<>();
      for (BgInsuranceContribution row : rows) {
        String code = row.getBgInsuredTypeCode();
        if (code != null && !code.trim().isEmpty()) {
          codes.add(code.trim());
        }
      }
      return codes.isEmpty() ? null : codes;
    } catch (RuntimeException e) {
      // Липсваща база или неинсталиран речник е СЪСТОЯНИЕ НА СРЕДАТА, не отговор „не е трудово
      // правоотношение“. Тихото празно множество би обявило всяка т. 30 за нарушение.
      return null;
    }
  }

  /**
   * Редът от речника за този код, в сила за тази дата, или {@code null}.
   *
   * <p>⚠️ Дословно същият филтър като {@code BgPaySlipService#insuredTypeFor} — нарочно. Два
   * различни филтъра над една таблица биха дали два различни реда за един и същи фиш: единият за
   * долния праг, другият за декларацията.
   */
  protected BgInsuredType insuredTypeFor(String insuredTypeCode, LocalDate date) {
    if (insuredTypeCode == null || insuredTypeCode.trim().isEmpty() || date == null) {
      return null;
    }
    return Beans.get(BgInsuredTypeRepository.class)
        .all()
        .filter(
            "self.code = :c AND self.validFrom <= :d "
                + "AND (self.validTo IS NULL OR self.validTo >= :d)")
        .bind("c", insuredTypeCode.trim())
        .bind("d", date)
        .fetchOne();
  }
}
