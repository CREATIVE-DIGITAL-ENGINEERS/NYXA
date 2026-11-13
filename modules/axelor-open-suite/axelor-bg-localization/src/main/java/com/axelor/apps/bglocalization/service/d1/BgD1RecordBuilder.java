package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.bglocalization.service.payroll.BgSickLeaveResolver;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ЕДИН ФИШ → ЕДИН ЗАПИС от Декларация обр. № 1.
 *
 * <p><b>Статичен и без Guice нарочно.</b> Всичко, което му трябва, се ПОДАВА — фишът, видът
 * осигурен от речника, дружеството и периодът. Тоест построяването на запис се проверява без база,
 * без контекст и без App Install, точно както {@code BgPaySlipResolver} прави за агрегатите на
 * фиша. Търсенето на реда в речника (то е темпорално и иска заявка) живее едно ниво по-горе, в
 * {@code BgD1Emitter}.
 *
 * <p><b>⚠️ КАКВО НЕ ПРАВИ.</b> Не смята. Числата вече ги има — {@code insuranceIncomeBase} за т.
 * 21, СТАВКИТЕ на перата за т. 22 … т. 30, {@code bgTaxAmount} за т. 31а — и всяко второ смятане
 * тук би било втори отговор на въпрос, който вече има един. Тази услуга ПРЕНАСЯ и ПРЕВЕЖДА.
 *
 * <p><b>⚠️ И ЕДНО НЕЩО, КОЕТО ПРАВИ НАРОЧНО: СЪБИРА СТАВКИ НА РАЗЛИЧНИ ФОНДОВЕ.</b> Т. 22 е един
 * процент за четири фонда. Това не е сметка върху пари — то е самата структура на декларацията, и
 * тя живее в {@code BgD1ContributionAggregator}, не тук.
 */
public final class BgD1RecordBuilder {

  /** Кодът на т. 5.1 при ЕГН — наредбата казва „0“. */
  public static final String ID_CODE_EGN = "0";

  /**
   * Кодът на т. 5.1 при ЛН/ЛНЧ/служебен номер — наредбата казва „2“.
   *
   * <p>⚠️ Съвпада по стойност с {@code bg.personal.id.type.select} = „2“, и това съвпадение е
   * СЛУЧАЙНО. Другата половина на превода не съвпада: нашата селекция дава „1“ за ЕГН, наредбата
   * иска „0“. Тоест препращане на суровата стойност е вярно в единия случай и невярно в другия —
   * най-лошата възможна форма на грешка, защото половината записи изглеждат наред.
   */
  public static final String ID_CODE_FOREIGN = "2";

  /** „1“ = ЕГН по {@code bg.personal.id.type.select}. Превежда се на „0“. */
  private static final String OUR_ID_TYPE_EGN = "1";

  /** Буква „К“ (U+041A) — КИРИЛИЦА. Виж bg.d1.correction.code.select. */
  public static final String CORRECTION_K = "К";

  /** Буква „З“ (U+0417) — КИРИЛИЦА. */
  public static final String DELETION_Z = "З";

  private BgD1RecordBuilder() {}

  /**
   * Построява записа. Извикващият вече е проверил, че фишът НЕ е отказан ({@link
   * BgD1InsuredTypeRules#refusalReason}).
   *
   * @param slip фишът-източник
   * @param type редът от речника за вида осигурен на фиша, за периода на данните
   * @param dataPeriod периодът на ДАННИТЕ — той и само той пълни т. 2 и т. 3
   * @param findings находките се ДОБАВЯТ тук; методът не хвърля за непълни данни
   */
  public static BgD1Record build(
      BgPaySlip slip, BgInsuredType type, Period dataPeriod, List<BgD1FindingDto> findings) {
    return build(slip, type, dataPeriod, BgD1Facts.none(), findings);
  }

  /**
   * Същото, но с вече преброените дни по кофи на т. 16.
   *
   * <p>⚠️ ДВА ВХОДА ЗА ЕДНА УСЛУГА, и по-краткият НЕ Е удобство. Дните искат ЗАЯВКА (заявките за
   * отпуск на лицето за периода) и затова не могат да се четат тук — този клас е чиста функция и се
   * проверява без база. Кофите пристигат отвън, от {@link BgD1DaySource}, който е единственото
   * място с достъп до базата.
   *
   * <p>⚠️ {@code null} кофи значи „дните НЕ са четени“, а не „нула дни“. И двете дават празна т.
   * 16, но първото е състояние на извикването, второто — твърдение за лицето. Затова краткият вход
   * съществува: той е за свидетелите, които проверяват ОСТАНАЛИТЕ точки, и им дава право да не
   * знаят за дните.
   */
  public static BgD1Record build(
      BgPaySlip slip,
      BgInsuredType type,
      Period dataPeriod,
      Map<String, Integer> dayBuckets,
      List<BgD1FindingDto> findings) {
    return build(slip, type, dataPeriod, dayBuckets, null, findings);
  }

  /**
   * Същото, но и с ТАВАНА за т. 34 — максималният месечен осигурителен доход за периода.
   *
   * <p><b>⚠️ ТРЕТИЯТ ВХОД, И ПО СЪЩИЯ ДОВОД КАТО КОФИТЕ НА Т. 16.</b> Таванът е ТЕМПОРАЛЕН
   * параметър ({@code MAX_INSURABLE_INCOME}, две стойности само за 2026 г.) и искa ЗАЯВКА; този
   * клас е чиста функция. Заявката живее в {@link BgD1Emitter}.
   *
   * <p>⚠️ {@code null} таван значи „НЕ е прочетен“, а не „няма таван“. Т. 34 тогава остава празна с
   * назована находка — виж {@link BgD1NetPayResolver#PROBLEM_CAP_MISSING}. Затова петаргументният
   * вход остава: той е за свидетелите на ОСТАНАЛИТЕ точки и им дава право да не знаят за тавана.
   */
  public static BgD1Record build(
      BgPaySlip slip,
      BgInsuredType type,
      Period dataPeriod,
      Map<String, Integer> dayBuckets,
      BigDecimal netPayCap,
      List<BgD1FindingDto> findings) {
    return build(
        slip, type, dataPeriod, new BgD1Facts(dayBuckets, null, null, netPayCap), findings);
  }

  /**
   * ПЪЛНИЯТ ВХОД: записът плюс ВСИЧКО, което иска заявка — дни, прекъсвания, часове, таван.
   *
   * <p>⚠️ Четирите пристигат в ЕДИН носител ({@link BgD1Facts}) нарочно: четири съседни
   * необходими-но-може-и-да-липсват аргумента се разместват безшумно при първото добавяне на пети.
   * Виж javadoc-а на носителя.
   *
   * <p>⚠️ И всяко {@code null} в него значи „НЕ е четено“, а не „няма“. Разликата е законова:
   * празна т. 16 при непрочетени дни е състояние на извикването, а „нула дни в осигуряване“ е
   * твърдение за лицето — и НАП приема второто.
   */
  public static BgD1Record build(
      BgPaySlip slip,
      BgInsuredType type,
      Period dataPeriod,
      BgD1Facts facts,
      List<BgD1FindingDto> findings) {

    if (facts == null) {
      facts = BgD1Facts.none();
    }
    Map<String, Integer> dayBuckets = facts.getDayBuckets();
    BigDecimal netPayCap = facts.getNetPayCap();

    BgD1Record record = new BgD1Record();
    record.setPaySlip(slip);

    // ⚠️ ЕДНА ВРАТА ЗА ДВАТА НОСИТЕЛЯ. Шест неща тук идват от трудовия договор, а пет от тях имат
    // СЪВСЕМ ДРУГ източник при самоосигуряващото се лице. Разпръснато като тернарен израз на шест
    // места, разклонението се разминава при първото добавяне на седмо поле: едно от шестте остава
    // на стария източник и записът излиза наполовина от единия носител, наполовина от другия —
    // всяко число поотделно правдоподобно. Виж BgD1Carrier.
    BgD1Carrier carrier = BgD1Carrier.of(slip);
    EmploymentContract contract = carrier.getContract();
    Employee employee = carrier.employee();
    String insuredCode = carrier.insuredTypeCode();

    // ═══ т. 1 — БУКВА, не число ═══
    // Заличаването се пита ПЪРВО: фиш, маркиран и като коригиращ, и като заличаващ, е дефект, но
    // заличаването е по-силното твърдение и по-безопасният прочит. Самият конфликт се отказва
    // по-нагоре (BgPaySlipResolver#refuseBothRetroFlags), тоест дотук той не стига.
    if (slip != null && Boolean.TRUE.equals(slip.getBgIsDeletion())) {
      record.setT1CorrectionCode(DELETION_Z);
    } else if (slip != null && Boolean.TRUE.equals(slip.getBgIsCorrection())) {
      record.setT1CorrectionCode(CORRECTION_K);
    }

    // ═══ т. 2 / т. 3 — от периода на ДАННИТЕ, не на подаването ═══
    LocalDate from = dataPeriod == null ? null : dataPeriod.getFromDate();
    if (from == null) {
      findings.add(
          finding(
              "D1_PERIOD_MISSING",
              BgD1FindingDto.SEVERITY_ERROR,
              "периодът на данните няма начална дата — т. 2 и т. 3 нямат източник, а без месец"
                  + " декларацията не се приема (чл. 11 ал. 1)",
              slip,
              insuredCode));
    } else {
      record.setT2Month(String.format("%02d", from.getMonthValue()));
      record.setT3Year(String.valueOf(from.getYear()));
    }

    // ═══ т. 4 — КОДЪТ НА ЗАДЪЛЖЕНОТО ЛИЦЕ, и двата носителя дават РАЗЛИЧНО число ═══
    // При наетия това е ЕИК по БУЛСТАТ на ПЛАЩАЩОТО дружество — задължено по наредбата е онова,
    // което плаща по договора; при няколко дружества активното е свойство на потребителя, не на
    // фиша. При самоосигуряващия се дружество НЯМА и кодът се въвежда на регистрацията: указанието
    // към т. 4 иска „ЕИК по БУЛСТАТ, ЕИК по ЗТРРЮЛНЦ, служебен номер“ и НЕ различава случаите.
    // Виж BgD1Carrier#obligedPersonCode.
    Company company = carrier.company();
    record.setT4ObligedPersonCode(carrier.obligedPersonCode());

    // ═══ т. 5 / 5.1 / 6 / 7 — лицето ═══
    record.setT5PersonalId(employee == null ? null : trim(employee.getBgPersonalIdNumber()));
    record.setT51PersonalIdCode(employee == null ? null : idCodeOf(employee.getBgPersonalIdType()));

    Partner person = employee == null ? null : employee.getContactPartner();
    if (person != null) {
      record.setT6Surname(trim(person.getName()));
      record.setT7Initials(initials(person.getFirstName(), person.getBgMiddleName()));
    }

    // ═══ т. 12 — видът осигурен ═══
    record.setT12InsuredTypeCode(insuredCode);

    // ═══ ЗАЛИЧАВАЩИЯТ ЗАПИС СПИРА ТУК — ДЕВЕТ ТОЧКИ И НИЩО ПОВЕЧЕ ═══
    // Указание т. 1 дословно: „Попълва се буква „З“, когато се подава декларация за заличаване на
    // подадена вече декларация. В тези случаи се попълват т. 1, 2, 3, 4, 5, 5.1, 6, 7 и 12 от
    // декларацията.“
    //
    // ⚠️ ИЗЛИЗА СЕ РАНО, А НЕ СЕ ИЗЧИСТВА НАКРАЯ. Двете дават еднакъв запис днес и се разминават
    // при
    // първата нова точка: изчистване накрая иска списък, който трябва да се допълва при всяко
    // разширение — тоест бъдещата т. 33 ще остане попълнена в заличаващ запис и никой няма да го
    // забележи. Ранното излизане няма този проблем по устройство.
    //
    // ⚠️ И ЗАЩО ТОЧНО ТУК: деветте точки СВЪРШВАТ с т. 12. Всичко под този ред е данни за периода —
    // дни, доход, вноски, данък, — а заличаването твърди, че записът НЕ СЪЩЕСТВУВА. Запис с нули би
    // твърдял, че лицето е било осигурено с нула доход.
    if (BgD1CorrectionRules.isDeletion(record)) {
      return record;
    }

    // ═══ т. 12.1 / 12.3 — само за кодовете, които прилагат МОД ═══
    if (BgD1InsuredTypeRules.appliesMod(type)) {
      // ⚠️ Групата се чете БЕЗ проверка за null върху числото — генерираният getter за Integer
      // връща 0, не null (BgDefaultedGetterTest). А тук това дори не е компромис: нула Е валидна
      // квалификационна група (BgNkpd.qualificationGroup е min=0 max=9). Тоест въпросът „има ли
      // група“ се решава от НАЛИЧИЕТО НА РЕДА по НКПД, а не от стойността му — единственото място,
      // където двете се различават.
      BgNkpd nkpd = contract == null ? null : contract.getBgNkpd();
      record.setT121QualificationGroup(
          nkpd == null ? null : String.valueOf(nkpd.getQualificationGroup()));

      // т. 12.2 и т. 12.3 идват от ЕДИН И СЪЩ ред по НКИД — дейността на ОСИГУРИТЕЛЯ. Двете точки
      // обаче искат РАЗЛИЧНИ негови колони: 12.2 иска самия код по КИД 2008, а 12.3 — реда, на
      // който тази дейност стои в приложението към ЗБДОО. Взети от една колона, те щяха да са
      // еднакви — и това би изглеждало като подредба, а не като грешка.
      BgNkid nkid = company == null ? null : company.getBgNkid();
      record.setT122ActivityCode(nkid == null ? null : activityCode(nkid.getCode()));
      record.setT123ActivityRow(nkid == null ? null : trim(nkid.getBgMinIncomeRowCode()));
    } else if (BgD1InsuredTypeRules.modIsUndecidable(insuredCode, type)) {
      // Обявена граница — виж BgD1InsuredTypeRules#modIsUndecidable.
      findings.add(
          finding(
                  "D1_ART6_AL3_UNDECIDABLE",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "вид осигурен 94 попълва т. 12.1/12.2/12.3 САМО когато осигурителният доход се"
                      + " определя по реда на чл. 6 ал. 3 КСО (заб. 2 към т. 12). Този признак няма"
                      + " носител в модела днес, тоест условието НЕ е проверено — точките остават"
                      + " празни и това е решение, не сметка. Провери го на ръка преди подаване.",
                  slip,
                  insuredCode)
              .at("12.1"));
    }

    // ═══ т. 13 — кодът за сумирано изчисляване, САМО за вид осигурен 16 ═══
    fillSirvCode(record, contract, insuredCode, findings, slip);

    // ═══ т. 14 / т. 15 — границите на осигуряването в този месец ═══
    // ⚠️ ПРЕКЪСВАНИЯТА СЕ СЪБИРАТ ОТ ДВА ИЗТОЧНИКА, И ТЕ НЕ СЕ ЗАМЕСТВАТ. Заявките за отпуск
    // (BgD1DaySource) прекъсват осигуряването на НАЕТИЯ; пролуките между периодите на дейност
    // прекъсват осигуряването на САМООСИГУРЯВАЩИЯ СЕ. Едно лице има само единия носител, тоест
    // единият списък винаги е празен — но събрани, двата не изискват трети клон тук.
    List<LocalDate[]> interruptions = new ArrayList<>(facts.getInterruptions());
    interruptions.addAll(carrier.gaps());
    fillInsuranceBoundaries(
        record, carrier, dataPeriod, interruptions, findings, slip, insuredCode);

    // ═══ т. 16 и кофите ѝ — само когато дните НАИСТИНА са четени ═══
    if (dayBuckets != null) {
      fillDays(
          record,
          slip,
          contract,
          type,
          dayBuckets,
          facts.getOutsideInsuranceDays(),
          facts.getHours(),
          findings,
          insuredCode);
    }

    // ═══ т. 21 — осигурителният доход, КАПНАТ, само за собствения период ═══
    // ⚠️ ТУК СТОЕШЕ ГРЕШКА, И ТЯ БЕШЕ ОБОСНОВАНА С НЕВЯРНО ТВЪРДЕНИЕ. Коментарът дотук казваше:
    // „чете се от резолвера, а не от BgPaySlip.bgInsuranceIncome: заглавното поле е РЕЗУЛТАТ, върху
    // който услугата вече е наложила пода (МОД) и тавана“. Второто изречение НЕ е вярно:
    // BgPaySlipService#insuranceIncomeHeader връща СБОРА НА ПЕРАТА и минава през базата на вноската
    // САМО когато лицето е декларирало доход при друг платец (чл. 6 ал. 11). Тоест и двата
    // разглеждани източника бяха НЕкапнати и т. 21 излизаше 3000,00 при таван 2111,64.
    //
    // ⚠️ ЗАЩО ТОВА НЕ Е КОЗМЕТИКА: указание т. 21 иска дохода, „ВЪРХУ КОЙТО СЕ ДЪЛЖАТ“ вноски, а
    // КСО чл. 6 ал. 3 го ограничава „не повече от максималния месечен размер“. НАП сверява
    // т. 21 × ставката от т. 22 срещу левовете в Д6: 3000,00 × 10,92 % дава 327,60 срещу
    // действителни 230,59 — разлика 97,01 лв. на едно лице за един месец, и декларацията пада.
    //
    // Капнатата база има ЕДИН носител — bgQuantity на перата от 5xx, което стоят ВЪРХУ т. 21.
    // Виж BgD1ContributionAggregator#insuranceIncomeBaseFromLines защо не са другите два.
    fillInsuranceIncome(record, slip, facts.linesOf(slip), findings, insuredCode);

    fillRates(record, facts.linesOf(slip));

    // ═══ т. 29 — брутната работна заплата по НСОРЗ чл. 16 ал. 2 ═══
    // ⚠️ САМО ЗА ТРУДОВИ ПРАВООТНОШЕНИЯ (заб. към т. 29/30). Кои са те се ЧЕТЕ от речника с вноски
    // (кой вид осигурен носи ред за фонд ГВРС) — правилото се пази на едно място и слиза дотук
    // през BgD1Facts, както слизат дните, часовете и таванът.
    //
    // ⚠️ НЕ САМО ФЛАГЪТ НА ПЕРОТО, и правилото не се оставя само на валидатора СЛЕД построяването.
    // Разликата не е стилова: за вид осигурен 10 (договор за управление и контрол) перото за
    // възнаграждение носи същия флаг като заплатата на наетия, тоест по флага точката би се
    // попълнила и валидаторът би вдигнал D1_T30_NOT_LABOUR_RELATION с тежест ERROR върху НАПЪЛНО
    // ВЕРЕН фиш. Операторът няма какво да поправи от екрана — видът на правоотношението трябва да
    // се пита, преди да се напише числото.
    //
    // ⚠️ ВАЛИДАТОРЪТ ОСТАВА, и това не е дублиране. Той пази РЪЧНО въведен запис и записа, дошъл
    // при непрочетен речник — двата случая, които този ред не вижда.
    record.setT29GrossWage(
        slip == null || !facts.isLabourRelation(insuredCode)
            ? null
            : BgPaySlipResolver.grossWageTotal(facts.linesOf(slip)));

    // ═══ т. 31 — начисленият облагаем доход ═══
    // ⚠️ ФЛАГЪТ РЕШАВА, НЕ ЧИСЛОТО. Указанието изброява 30 кода; за останалите точката остава
    // ПРАЗНА, макар доходът да е напълно определим. Същият избор като при т. 12.1/12.2/12.3.
    //
    // ⚠️ И ЕДИН ОТ ТРИЙСЕТТЕ НОСИ УСЛОВИЕ ОТГОРЕ НА ИЗБРОЯВАНЕТО. За код 14 указанието дава т. 31
    // САМО на акционера с над 5 на сто от капитала, а признакът няма носител в модела — виж
    // BgD1InsuredTypeRules#taxPointsUndecidable. Тоест ФЛАГЪТ Е ВЕРЕН и остава вдигнат в речника, а
    // тук се пита ВТОРИ въпрос. Без него обикновеният граждански договор би излязъл с т. 31 =
    // брутото и т. 31а = авансовия данък по чл. 43 — данък, който вече е деклариран по чл. 55 и в
    // справката по чл. 73 ал. 1.
    boolean taxPointsUndecidable = BgD1InsuredTypeRules.taxPointsUndecidable(insuredCode);
    if (BgD1InsuredTypeRules.fillsT31(type) && !taxPointsUndecidable) {
      record.setT31TaxableIncome(
          slip == null ? null : BgPaySlipResolver.accruedTaxableTotal(facts.linesOf(slip)));
    }

    // ═══ т. 34 — нетното по НПОПДОО чл. 32 ═══
    // ⚠️ ФЛАГЪТ РЕШАВА ДАЛИ ТОЧКАТА СЕ ПОЛАГА, СМЕТКАТА — КОЛКО Е. За 20-те кода с bgNoNetPay
    // точката НЕ се попълва по самата забележка към т. 34 и находка НЕ се вдига: „липсва нето“ би
    // пратило човек да попълва нещо, което наредбата забранява.
    // Формулата и всяко нейно четене живеят в BgD1NetPayResolver — тук се пренася числото и се
    // назовава причината, когато го няма.
    if (!BgD1InsuredTypeRules.noNetPay(type)) {
      fillNetPay(record, slip, facts, netPayCap, findings, insuredCode);
    }

    // ═══ т. 31а — начисленият данък ═══
    // Морското лице носи 1 на сто вместо 10 по чл. 42 ал. 4 ЗДДФЛ, но това е решено още на фиша
    // (BgPaySlipService#incomeTaxRate чете bgIsSeafarer). Тук числото само се пренася — второ
    // решение за ставката би било второ мнение по един и същи въпрос.
    //
    // ⚠️ И ТОЧКАТА НЯМА СВОЙ ФЛАГ В РЕЧНИКА — това е вярно, а не пропуск. За разлика от т. 31
    // указанието ѝ НЕ изброява кодове: то казва „начислен месечен данък“ и добавя алтернативния по
    // чл. 41а. Затова т. 31а се пълни и там, където т. 31 е празна — код 41 (събирачи на гъби и
    // плодове) е точно такъв, а bgFillsT31 не го носи. Гейт по bgFillsT31 щеше да изпразни точката
    // на алтернативния данък и да заличи отпечатъка му в декларацията.
    //
    // ⚠️ ЕДИНСТВЕНОТО ИЗКЛЮЧЕНИЕ Е УСЛОВНИЯТ КОД 14, и то идва от указанието на Т. 31, не от списък
    // тук: същият акционер по б. „и“ и „л“ носи двете точки заедно, а извън него данъкът по
    // граждански договор е авансов по чл. 43 и Д1 не го иска.
    if (!taxPointsUndecidable) {
      record.setT31aTax(facts.taxOf(slip));
    } else {
      // Обявена граница — виж BgD1InsuredTypeRules#taxPointsUndecidable.
      findings.add(
          finding(
                  "D1_T31_SHAREHOLDER_UNDECIDABLE",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "вид осигурен 14 попълва т. 31 и 31а САМО когато лицето е акционер, притежаващ"
                      + " повече от 5 на сто от капитала на акционерното дружество, за доходи по §"
                      + " 1 т. 26 б. „и“ и „л“ (указанието към т. 31). Този признак няма носител в"
                      + " модела днес, тоест условието НЕ е проверено — двете точки остават празни"
                      + " и това е решение, не сметка. За обикновения граждански договор празното е"
                      + " и верният изход: данъкът му е АВАНСОВ по чл. 43 ЗДДФЛ, декларира се по"
                      + " чл. 55 и в справката по чл. 73 ал. 1, а не като месечен данък по чл. 42"
                      + " ал. 4 в Д1. Осигурителните точки на записа НЕ са засегнати. Провери го на"
                      + " ръка преди подаване.",
                  slip,
                  insuredCode)
              .at(BgD1Point.T31_TAXABLE_INCOME));
    }

    return record;
  }

  /**
   * РЕДЪТ 27 — ЕДНА ЧАСТ от обезщетението по чл. 222 ал. 1 КТ ({@link BgCompensationParts}).
   *
   * <p><b>Какво носи (образецът на Microinvest, 03.2025):</b> т. 12 = 27; т. 14/15 — границите на
   * частта в месеца (24/31); т. 16 и т. 16.1 — работните ѝ дни (06); т. 21 = т. 31 = сумата на
   * частта (307,74); ставките — САМО на вноските върху обезщетението (Пенсии 8,22/6,58 и УПФ
   * 2,80/2,20); т. 29 — НИЩО, т. 31а — данъкът на частта (28,07), т. 34 — нищо.
   *
   * <p><b>⚠️ Т. 29 Е ПРАЗНА, И ТОВА Е ПРАВИЛО, НЕ ПРОПУСК.</b> Обезщетението не е брутна работна
   * заплата по НСОРЗ чл. 16 ал. 2 — и правоотношението вече е прекратено.
   *
   * <p><b>⚠️ СТАВКИТЕ СА НА ВНОСКИТЕ ВЪРХУ ОБЕЗЩЕТЕНИЕТО</b> ({@link
   * BgCompensationParts.Share#sourceLines}), не на целия фиш: фишът на месеца на прекратяването
   * носи и вноските по вид 01 върху заплатата.
   *
   * @param type редът от речника за вид 27, за периода на данните
   * @param share частта, чийто месец е периодът на данните
   */
  public static BgD1Record buildCompensationPart(
      BgPaySlip slip,
      BgInsuredType type,
      Period dataPeriod,
      BgCompensationParts.Share share,
      List<BgD1FindingDto> findings) {

    BgD1Record record = new BgD1Record();
    record.setPaySlip(slip);
    BgD1Carrier carrier = BgD1Carrier.of(slip);
    EmploymentContract contract = carrier.getContract();
    Employee employee = carrier.employee();
    String insuredCode = BgCompensationParts.INSURED_TYPE;
    BgCompensationParts.Segment segment = share.getSegment();

    // ═══ т. 1 — същото правило като в build ═══
    if (slip != null && Boolean.TRUE.equals(slip.getBgIsDeletion())) {
      record.setT1CorrectionCode(DELETION_Z);
    } else if (slip != null && Boolean.TRUE.equals(slip.getBgIsCorrection())) {
      record.setT1CorrectionCode(CORRECTION_K);
    }

    // ═══ т. 2 / т. 3 — МЕСЕЦЪТ НА ЧАСТТА, и той е периодът на данните ═══
    record.setT2Month(String.format("%02d", segment.getFrom().getMonthValue()));
    record.setT3Year(String.valueOf(segment.getFrom().getYear()));
    LocalDate from = dataPeriod == null ? null : dataPeriod.getFromDate();
    if (from != null
        && (from.getYear() != segment.getFrom().getYear()
            || from.getMonthValue() != segment.getFrom().getMonthValue())) {
      findings.add(
          finding(
                  "D1_T27_PART_OUTSIDE_PERIOD",
                  BgD1FindingDto.SEVERITY_ERROR,
                  "частта на обезщетението „"
                      + share.reference()
                      + "“ е за "
                      + segment
                      + ", а декларацията е за друг месец. Редът 27 се подава в Д1 на МЕСЕЦА НА"
                      + " ЧАСТТА — тук е построен по грешка.",
                  slip,
                  insuredCode)
              .at("2"));
    }

    // ═══ т. 4 … т. 7 — същите източници като в build ═══
    record.setT4ObligedPersonCode(carrier.obligedPersonCode());
    record.setT5PersonalId(employee == null ? null : trim(employee.getBgPersonalIdNumber()));
    record.setT51PersonalIdCode(employee == null ? null : idCodeOf(employee.getBgPersonalIdType()));
    Partner person = employee == null ? null : employee.getContactPartner();
    if (person != null) {
      record.setT6Surname(trim(person.getName()));
      record.setT7Initials(initials(person.getFirstName(), person.getBgMiddleName()));
    }

    record.setT12InsuredTypeCode(insuredCode);
    if (BgD1CorrectionRules.isDeletion(record)) {
      return record;
    }

    // ═══ т. 14 / т. 15 — границите на частта в месеца ═══
    setT14(record, 0, twoPositions(segment.getFrom().getDayOfMonth()));
    setT15(record, 0, twoPositions(segment.getTo().getDayOfMonth()));

    // ═══ т. 16 и т. 16.1 — работните дни на частта; останалите кофи са нула ═══
    // ⚠️ Т. 16.1 брои „всички дни с осигурителни вноски на основание чл. 9, ал. 3 от КСО“ — тоест
    // точно работните дни, по които частта е разделена.
    int days = segment.getWorkingDays();
    record.setT161WorkedDays(text(days));
    record.setT162SickDays(text(0));
    record.setT163ChildCareDays(text(0));
    record.setT164NoContributionDays(text(0));
    record.setT165UnpaidLeaveDays(text(0));
    record.setT16aEmployerSickDays(text(0));
    record.setT16Days(twoPositions(days));
    if (BgD1InsuredTypeRules.zeroWorkTime(type)) {
      record.setT16LegalHours(ZERO_HOURS);
      record.setT16ContractedHours(ZERO_HOURS);
    } else {
      BigDecimal contracted = contract == null ? null : contract.getBgHoursPerDay();
      if (contracted != null && contracted.signum() != 0) {
        record.setT16ContractedHours(text(contracted.setScale(0, RoundingMode.HALF_UP).intValue()));
      }
      BigDecimal legal = contract == null ? null : contract.getBgLegalHoursPerDay();
      if (legal != null && legal.signum() != 0) {
        record.setT16LegalHours(text(legal.setScale(0, RoundingMode.HALF_UP).intValue()));
      }
    }

    // ═══ т. 21 и ставките — САМО вноските върху обезщетението ═══
    // ⚠️ Т. 21 Е ЧАСТТА ПОД ОБЩИЯ ТАВАН С ВИД 01 (КСО чл. 6 ал. 11), а т. 31 — цялата част:
    // доходът,
    // „върху който се дължат“ вноски, е само остатъкът до тавана (BgCompensationParts#capped).
    record.setT21InsuranceIncome(segment.getInsurable());
    if (segment.getInsurable().signum() == 0) {
      findings.add(
          finding(
                  "D1_T27_CAP_EXHAUSTED_BY_01",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 21 на реда 27 („"
                      + share.reference()
                      + "“, "
                      + segment.month()
                      + ") е НУЛА по чл. 6 ал. 11 КСО: осигурителният доход по вид 01 на лицето"
                      + " за месеца изчерпва максималния месечен размер, а обезщетенията по КТ"
                      + " идват след него. Вноски по вид 27 за частта не се дължат; т. 31 носи"
                      + " частта.",
                  slip,
                  BgCompensationParts.INSURED_TYPE)
              .at(BgD1Point.T21_INSURANCE_INCOME));
    }
    fillRates(record, share.sourceLines());

    // ═══ т. 31 / т. 31а — частта и нейният данък; т. 29 и т. 34 остават празни ═══
    if (BgD1InsuredTypeRules.fillsT31(type)) {
      record.setT31TaxableIncome(segment.getAmount());
    }
    record.setT31aTax(share.getTax());
    return record;
  }

  /**
   * Т. 17/18, т. 22 … т. 30 — СТАВКИТЕ от перата, които редът вижда, поотделно по платец.
   *
   * <p>⚠️ Изнесено от {@link #build}, за да го ползва и редът 27 ({@link #buildCompensationPart}):
   * неговите ставки са на вноските върху обезщетението, а не на целия фиш.
   */
  static void fillRates(BgD1Record record, List<BgPaySlipLine> lines) {
    // ═══ т. 22 … т. 30 — вноските като СТАВКИ, поотделно по платец ═══
    // ⚠️ ДВЕ КАРТИ, НЕ ЕДНА: полетата на файла идват по двойки (35/36, 37/38, 42/43), а указание
    // т. 22.2 иска „размерите … поотделно за осигурителя и осигуреното лице“. Слети в едно число,
    // те не могат да се разделят обратно.
    List<BgPaySlipLine> slipLines = lines;
    Map<String, BigDecimal> employerRates =
        BgD1ContributionAggregator.ratesByPoint(slipLines, BgPaySlipResolver.CREDIT);
    Map<String, BigDecimal> insuredRates =
        BgD1ContributionAggregator.ratesByPoint(slipLines, BgPaySlipResolver.DEBIT);

    // ═══ т. 17 / 18 и т. 27 / 28 — здравното върху СПЕЦИАЛНИТЕ бази ═══
    // ⚠️ БАЗИТЕ ИДВАТ ОТ ДРУГА КАРТА, защото са СУМИ, а ставките — проценти. Едно извикване не може
    // да върне двете: точка 17 е поле ≤7.2 в левове, точка 18 е поле ≤5.2 в проценти, и те дори не
    // са една и съща величина. Слети, те щяха да се разминат по мащаб и по смисъл.
    Map<String, BigDecimal> specialBases =
        BgD1ContributionAggregator.specialBasesByPoint(slipLines);
    record.setT17Art4015Income(specialBases.get(BgD1Point.T17_ART40_1_5_INCOME));
    record.setT27HealthOnlyIncome(specialBases.get(BgD1Point.T27_HEALTH_ONLY_INCOME));

    // ⚠️ И ТУК СБОРЪТ НА ДВЕТЕ СТРАНИ Е ВЕРЕН, но по ДРУГА причина от еднополевите точки: т. 18 е
    // само на осигуряващия, т. 28 е „ИЗЦЯЛО за сметка на“ ЕДИН платец — който при неплатен отпуск
    // по
    // желание е ЛИЦЕТО, а при чл. 167а е РАБОТОДАТЕЛЯТ. Тоест и тук точно една страна е ненулева, а
    // прочит „винаги осигурителят“ би дал нула за неплатения отпуск по желание.
    record.setT18HealthRateOnT17(
        BgD1ContributionAggregator.singlePayerRate(
            employerRates, insuredRates, BgD1Point.T18_HEALTH_ON_T17));
    record.setT28HealthRateOnT27(
        BgD1ContributionAggregator.singlePayerRate(
            employerRates, insuredRates, BgD1Point.T28_HEALTH_ON_T27));

    record.setT22DooRateEmployer(employerRates.get(BgD1Point.T22_DOO));
    record.setT22DooRateInsured(insuredRates.get(BgD1Point.T22_DOO));
    record.setT221HealthRateEmployer(employerRates.get(BgD1Point.T22_1_HEALTH));
    record.setT221HealthRateInsured(insuredRates.get(BgD1Point.T22_1_HEALTH));
    record.setT26UpfRateEmployer(employerRates.get(BgD1Point.T26_UPF));
    record.setT26UpfRateInsured(insuredRates.get(BgD1Point.T26_UPF));

    // Четирите еднополеви точки. Виж BgD1ContributionAggregator#singlePayerRate защо е СБОР, а не
    // „ставката на осигурителя“: за морското лице (вид 92) ставката идва по перото на ЛИЦЕТО.
    record.setT222TzpbRate(
        BgD1ContributionAggregator.singlePayerRate(
            employerRates, insuredRates, BgD1Point.T22_2_TZPB));
    record.setT23TeacherPfRate(
        BgD1ContributionAggregator.singlePayerRate(
            employerRates, insuredRates, BgD1Point.T23_TEACHER_PF));
    record.setT25PpfRate(
        BgD1ContributionAggregator.singlePayerRate(employerRates, insuredRates, BgD1Point.T25_PPF));
    record.setT30GvrsRate(
        BgD1ContributionAggregator.singlePayerRate(
            employerRates, insuredRates, BgD1Point.T30_GVRS));
  }

  /**
   * Т. 34 — пренася числото на {@link BgD1NetPayResolver} и НАЗОВАВА всяка причина да го няма.
   *
   * <p><b>⚠️ ТРИТЕ ПРАЗНИ ТОЧКИ НЕ СА ЕДНА.</b> „Таванът не е прочетен“, „възнаграждението не е
   * начислено“ и „удръжките надхвърлят възнаграждението“ дават еднакво празна колона и искат ТРИ
   * различни действия от човека — да зареди параметъра, да впише минималния осигурителен доход по
   * чл. 6 ал. 2 т. 3 КСО, или да провери фиша. Един общ текст „т. 34 остава празна“ би пратил и
   * трима на грешното място.
   *
   * <p><b>⚠️ И НАЛОЖЕНИЯТ ТАВАН СЕ ОБЯВЯВА, МАКАР ЧИСЛОТО ДА Е ВЯРНО.</b> Рязаното нето е ПО-МАЛКО
   * от сметнатото и точно с това мени обезщетението, което НОИ ще плати. Тежестта е WARNING, а не
   * ERROR: записът е приемлив за НАП, но ЕДНО обстоятелство може да го обърне — чл. 6 ал. 16 КСО
   * изключва тавана и няма носител в модела. Тоест находката е единственото място, където човек
   * научава, че тук е взето решение.
   */
  static void fillNetPay(
      BgD1Record record,
      BgPaySlip slip,
      BgD1Facts facts,
      BigDecimal netPayCap,
      List<BgD1FindingDto> findings,
      String insuredCode) {

    BgD1NetPayResolver.Outcome outcome =
        BgD1NetPayResolver.resolve(facts.linesOf(slip), facts.taxOf(slip), netPayCap);
    record.setT34NetPay(outcome.getNetPay());

    String problem = outcome.getProblem();
    if (problem == null) {
      if (outcome.isCapped()) {
        findings.add(
            finding(
                    "D1_T34_CAPPED",
                    BgD1FindingDto.SEVERITY_WARNING,
                    "т. 34 е РЯЗАНА на максималния месечен осигурителен доход: "
                        + BgD1NetPayResolver.describe(outcome)
                        + ". Чл. 32 ал. 1 НПОПДОО: нетното възнаграждение „не може да бъде повече от"
                        + " максималния месечен осигурителен доход, определен със ЗБДОО за"
                        + " съответната година, освен в случаите по чл. 6 ал. 16 КСО“. ⚠️ Ако лицето"
                        + " е в хипотезата на чл. 6 ал. 16 КСО (доход от повече от едно"
                        + " правоотношение, деклариран пред НОИ), таванът НЕ се прилага и точката се"
                        + " поправя на ръка.",
                    slip,
                    insuredCode)
                .at("34"));
      }
      return;
    }

    if (BgD1NetPayResolver.PROBLEM_CAP_MISSING.equals(problem)) {
      findings.add(
          finding(
                  "D1_T34_CAP_NOT_READ",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 34 остава празна: максималният месечен осигурителен доход за периода не е"
                      + " прочетен (параметър MAX_INSURABLE_INCOME). Сметката без таван е "
                      + BgD1NetPayResolver.describe(outcome)
                      + ". ⚠️ Записана без горна граница, тя е ВЯРНА за обикновения фиш и НЕВЯРНА за"
                      + " високоплатеното лице — а двете изглеждат еднакво пресметнати. Зареди"
                      + " параметъра за периода и генерирай наново.",
                  slip,
                  insuredCode)
              .at("34"));
      return;
    }

    if (BgD1NetPayResolver.PROBLEM_NOT_ACCRUED.equals(problem)) {
      findings.add(
          finding(
                  "D1_T34_NOT_ACCRUED",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 34 остава празна: фишът не носи НАЧИСЛЕНО възнаграждение (нито едно перо с"
                      + " признак „осигурителен доход“). Чл. 32 ал. 1 изр. 2 НПОПДОО: „Когато"
                      + " възнаграждението не е начислено, до начисляването му за нетно"
                      + " възнаграждение се взема предвид минималният месечен осигурителен доход по"
                      + " чл. 6 ал. 2 т. 3 КСО или минималната месечна работна заплата за страната,"
                      + " ако нямат определен минимален осигурителен доход.“ ⚠️ Кое от двете е"
                      + " вярното зависи от това дали за ДЛЪЖНОСТТА има определен минимален"
                      + " осигурителен доход — впиши стойността на ръка, вместо да се избира тук.",
                  slip,
                  insuredCode)
              .at("34"));
      return;
    }

    findings.add(
        finding(
                "D1_T34_NEGATIVE",
                BgD1FindingDto.SEVERITY_ERROR,
                "т. 34 излиза ОТРИЦАТЕЛНА и затова остава празна: "
                    + BgD1NetPayResolver.describe(outcome)
                    + ". Двата източника си противоречат — базата брои доходите от ТРУДОВА ДЕЙНОСТ"
                    + " (чл. 32 ал. 4), а данъкът е върху ОБЛАГАЕМИЯ доход, който е по-широк"
                    + " (обезщетение по КТ е облагаемо и не е доход от трудова дейност). ⚠️ Нула НЕ"
                    + " се записва: „нула нето“ е валидно число, върху което НОИ би сметнал"
                    + " обезщетение за работил човек.",
                slip,
                insuredCode)
            .at("34"));
  }

  /**
   * Т. 21 — капнатата база, и НАЗОВАНАТА причина, когато я няма.
   *
   * <p><b>⚠️ ТРИ СТЕПЕНИ, И ВСЯКА ОТГОВАРЯ НА РАЗЛИЧЕН ФИШ.</b>
   *
   * <ol>
   *   <li><b>База на перо от 5xx</b> ({@code bgQuantity}) — обичайният случай. Това Е базата, върху
   *       която вноските са сметнати: с наложен МОД под и таван;
   *   <li><b>{@code bgInsuranceIncome} при ДЕКЛАРИРАН чужд доход</b> — координацията по КСО чл. 6
   *       ал. 11. При изчерпан от друг платец таван базата е НУЛА, всяка вноска се закръгля до 0,00
   *       и {@code BgContributionLineService} нарочно НЕ прави перо — тоест първата степен е
   *       безсилна ТОЧНО за фиша, за който съществува. Заглавното поле обаче е минало през базата
   *       на вноската-ОБЕКТ и я носи;
   *   <li><b>Сборът на перата</b> — най-доброто налично число, с НАЗОВАНА причина, че не е минало
   *       през тавана.
   * </ol>
   *
   * <p><b>⚠️ ВТОРАТА СТЕПЕН ПИТА ЗА КООРДИНАЦИЯТА, А НЕ ЗА СТОЙНОСТТА.</b> {@code
   * bgInsuranceIncome} е капнато САМО когато {@code BgPaySlipResolver#otherPayerInsurableIncome} не
   * е {@code null}; инак {@code BgPaySlipService#insuranceIncomeHeader} връща сбора на перата.
   * Прочетено безусловно, полето би било ТРЕТИ некапнат източник, който изглежда като капнат. И
   * обратно: нулата не може да е разграничителят, защото координираната база ЛЕГИТИМНО е нула при
   * изчерпан таван.
   *
   * <p><b>⚠️ ЗАЩО НЕ НУЛА И ЗАЩО НЕ ОТКАЗ на третата степен.</b> Нула би била твърдение
   * („осигурителен доход нула“), което НАП приема като число; отказ на целия запис би загубил т.
   * 29, 31, 31а и всичко останало, което е напълно определимо.
   *
   * <p><b>⚠️ ЕДНА НАСЛЕДЕНА ГРАНИЦА, ЗАПИСАНА, А НЕ ПРЕМЪЛЧАНА.</b> Когато стоящата стойност за
   * чуждия доход живее на ДОГОВОРА, а екранът е сглобен от контекста на формата без него, {@code
   * insuranceIncomeHeader} САМ пада на сбора на перата (виж javadoc-а му). Т. 21 наследява това:
   * втората степен ще върне некапнато число, без да го обяви. Границата е на заглавното поле и се
   * лекува там — детектор тук е невъзможен, защото „равно на сбора“ значи И „нищо не е било за
   * капване“.
   *
   * <p>⚠️ Заварената находка {@code BgD1Validator#BASE_WITHOUT_RATE} хваща съседния случай (т. 21
   * носи доход, а т. 22 няма ставка) и остава непокътната — двете описват различни фишове.
   */
  static void fillInsuranceIncome(
      BgD1Record record,
      BgPaySlip slip,
      List<BgPaySlipLine> lines,
      List<BgD1FindingDto> findings,
      String insuredCode) {

    // ⚠️ ПЕРАТА СЕ ПОДАВАТ: фиш с обезщетение по вид 27 подава тук перата БЕЗ реда 27.
    BigDecimal capped = BgD1ContributionAggregator.insuranceIncomeBaseFromLines(lines);
    if (capped != null) {
      record.setT21InsuranceIncome(capped);
      return;
    }

    // ═══ ВТОРА СТЕПЕН — КООРДИНАЦИЯТА ПО КСО ЧЛ. 6 АЛ. 11 ═══
    // ⚠️ И ТОЧНО ТУК ЗАГЛАВНОТО ПОЛЕ НАИСТИНА НОСИ КАПНАТАТА БАЗА. Разграничителят е ЕДИН и НЕ е
    // стойността: BgPaySlipService#insuranceIncomeHeader минава през базата на ВНОСКАТА само когато
    // лицето е декларирало доход при друг платец, а инак връща сбора на перата.
    //
    // ⚠️ И ЗАЩО ИМЕННО ТОЗИ СЛУЧАЙ СТИГА ДОТУК: при изчерпан от друг платец таван базата е НУЛА,
    // всяка вноска се закръгля до 0,00 и BgContributionLineService нарочно НЕ прави перо („ред с
    // 0,00 би твърдял, че вноска се дължи“). Тоест перо с база НЯМА — първата степен е безсилна
    // точно за фиша, за който тя съществува. Вноската като ОБЕКТ обаче я има и носи базата си, а
    // заглавното поле е следата от нея.
    //
    // ⚠️ НУЛАТА ТУК Е ЗАКОННА, НЕ ЛИПСА, и затова се записва такава, каквато е. Сборът на перата на
    // нейно място щеше да декларира 3000,00 доход, върху който НИТО ЕДНА стотинка вноска не е
    // внесена.
    if (BgPaySlipResolver.otherPayerInsurableIncome(slip) != null) {
      BigDecimal coordinated = slip.getBgInsuranceIncome();
      record.setT21InsuranceIncome(coordinated);
      if (coordinated.signum() == 0) {
        findings.add(
            finding(
                    "D1_T21_CAP_EXHAUSTED_ELSEWHERE",
                    BgD1FindingDto.SEVERITY_WARNING,
                    "т. 21 е НУЛА, и това е ЗАКОННАТА нула на чл. 6 ал. 11 КСО: лицето е декларирало"
                        + " осигурителен доход при друг платец, който изчерпва целия максимален"
                        + " месечен размер — тоест по ТОВА правоотношение вноски не се дължат."
                        + " ⚠️ Обявява се, защото празна т. 21 до попълнени т. 29 и т. 31 изглежда"
                        + " като пропуск, а не е. Сверѝ декларирания чужд доход преди подаване.",
                    slip,
                    insuredCode)
                .at(BgD1Point.T21_INSURANCE_INCOME));
      }
      return;
    }

    // ═══ ТРЕТА СТЕПЕН — най-доброто налично число, с НАЗОВАНА причина ═══
    BigDecimal fromLines = BgPaySlipResolver.insuranceIncomeBase(lines);
    record.setT21InsuranceIncome(fromLines);
    if (fromLines.signum() == 0) {
      // Няма ни доход, ни вноски — празната т. 21 е съгласна със себе си и не иска обяснение.
      return;
    }
    findings.add(
        finding(
                "D1_T21_NOT_CAPPED",
                BgD1FindingDto.SEVERITY_WARNING,
                "т. 21 носи "
                    + fromLines.toPlainString()
                    + " — СБОРЪТ НА ПЕРАТА, тоест число, което НЕ е минало през тавана и пода."
                    + " Причината: нито едно перо от 5xx не носи база (bgQuantity), тоест нито една"
                    + " вноска не е установила такава. ⚠️ Указание т. 21 иска дохода, „ВЪРХУ КОЙТО СЕ"
                    + " ДЪЛЖАТ“ вноски, а КСО чл. 6 ал. 3 го ограничава „не повече от максималния"
                    + " месечен размер“. Ако лицето е над тавана, това число е ГРЕШНО и"
                    + " реконсилиацията т. 21 × ставка ↔ Д6 няма да се затвори. Генерирай вноските"
                    + " на фиша и повтори; ако таванът е изчерпан от ДРУГ платец, впиши т. 21 на"
                    + " ръка.",
                slip,
                insuredCode)
            .at(BgD1Point.T21_INSURANCE_INCOME));
  }

  /**
   * Т. 16 И КОФИТЕ Ѝ — дните, вече преброени по кофи, стават точки.
   *
   * <p><b>⚠️ Т. 16.1 СЕ ИЗВЕЖДА, А НЕ СЕ ЧЕТЕ, и това е най-рискованото число тук.</b> Указанието
   * иска „отработените дни в осигуряване, дните в платен отпуск, дните, за които е упражнявана
   * дейност…“ — тоест отработено ПЛЮС платен отпуск в едно число. На фиша това няма едно поле:
   * {@code bgWorkedDays} тръгва като работните дни на периода и се НАМАЛЯВА от болничния, но НЕ и
   * от отпуските (виж {@code BgSickLeaveService#applyDays}: „ако ТРЗ вече е приспаднал и друго
   * отсъствие, болничният не го връща обратно“ — тоест приспадането е РЪЧНО и не е гарантирано).
   *
   * <p>Оттам следват две четения, всяко правдоподобно: „т. 16.1 = bgWorkedDays“ и „т. 16.1 =
   * bgWorkedDays минус отпуските, които не са платени“. Избрано е ВТОРОТО, като ОСТАТЪК:
   *
   * <pre>т. 16.1 = bgWorkedDays − (т. 16.3 + т. 16.4 + т. 16.5) − дните ИЗВЪН осигуряването</pre>
   *
   * <p>Доводът е кой ден в коя кофа стои. Болничните (16.2 и 16.А) вече са извадени от {@code
   * bgWorkedDays} от болничния енджин, тоест изваждането им втори път би отчело едни и същи дни два
   * пъти НАДОЛУ. Отпуските за отглеждане на дете (16.3), болничният без обезщетение (16.4) и
   * неплатеният отпуск (16.5), обратно, НЕ са извадени — а те не са нито отработени, нито платен
   * отпуск. Платеният отпуск (кофа 16.1) остава ВЪТРЕ в остатъка, което е точно каквото указанието
   * иска.
   *
   * <p><b>⚠️ ЧЕТВЪРТИЯТ ИЗВАЖДАЕМ НЯМА КОФА.</b> Петте вида в {@link BgD1DayBuckets#UNBUCKETED} —
   * неплатеният отпуск над 30 работни дни (по желание и по производствена необходимост),
   * неприсъствието, самоотлъчката и непредставеният болничен — носят празен {@code d1Code} и {@code
   * countsAsInsuranceService = false}. Неизвадени, остатъкът би ги ПРЕБРОИЛ като дни в осигуряване,
   * при положение че {@link BgD1InsuranceSegments} обявява за същите дати ПРЕКЪСВАНЕ на
   * осигуряването в т. 14/15: т. 15 казва „последен ден в осигуряване 4.12“, а т. 16.1 би броила и
   * 7–11.12. Две точки на един запис, които си противоречат.
   *
   * <p>Основанието е дословно: т. 16.1 иска „<b>дните с осигурителни вноски</b>“, а през тези дни
   * възнаграждение не се дължи (КТ чл. 128 т. 2 — „за ИЗВЪРШЕНАТА работа“), тоест няма осигурителен
   * доход и няма вноска. КСО чл. 9 ал. 2 т. 3 зачита неплатения отпуск ДО 30 работни дни; над тях —
   * не. И КСО чл. 10 ал. 2: „осигуряването СЕ ПРЕКЪСВА през периодите, които не се зачитат за
   * осигурителен стаж“.
   *
   * <p>⚠️ Сборът в позиции 1 и 2 пада САМ — т. 16.1 е негово събираемо, а изваждаемото НЕ Е седма
   * кофа. Вписано като кофа, то щеше да влезе в {@link BgD1DayBuckets#total} и да върне същите дни
   * обратно в „дни в осигуряване — общо“.
   *
   * <p><b>⚠️ И ЗАЩО ОТРИЦАТЕЛНИЯТ ОСТАТЪК Е НАХОДКА, А НЕ НУЛА.</b> Ако сборът на отпуските надмине
   * отработените дни, двата източника СИ ПРОТИВОРЕЧАТ — най-вероятно защото ТРЗ вече е приспаднал
   * отпуска на ръка и той се вади втори път. Изгладен до нула, конфликтът изчезва и декларацията
   * тръгва с т. 16.1 = 0 за човек, който е работил. Затова числото се клампва (нула дни е валиден
   * отговор за НАП), но противоречието се ОБЯВЯВА.
   *
   * <p><b>⚠️ КОД 90 НЕ ПОПЪЛВА Т. 16 ИЗОБЩО</b> — заб. 3 дословно: „Когато в т. 12 Вид осигурен е
   * попълнен код 90, т. 16 не се попълва.“ Проверката е ПЪРВА, преди всяко число: попълнена и после
   * изчистена, точката би оставила кофите си пълни.
   */
  static void fillDays(
      BgD1Record record,
      BgPaySlip slip,
      EmploymentContract contract,
      BgInsuredType type,
      Map<String, Integer> byBucket,
      int outsideInsuranceDays,
      BgD1HourSource.Hours hours,
      List<BgD1FindingDto> findings,
      String insuredCode) {

    if (BgD1InsuredTypeRules.taxOnly(insuredCode)) {
      // Заб. 3 към т. 16. Нищо не се пълни — нито кофите, нито позициите.
      return;
    }
    if (byBucket == null) {
      byBucket = BgD1DayBuckets.sum(null);
    }

    int childCare = days(byBucket, BgD1DayBuckets.B_16_3);
    int noContribution = days(byBucket, BgD1DayBuckets.B_16_4);
    int unpaid = days(byBucket, BgD1DayBuckets.B_16_5);

    record.setT162SickDays(text(days(byBucket, BgD1DayBuckets.B_16_2)));
    record.setT163ChildCareDays(text(childCare));
    record.setT164NoContributionDays(text(noContribution));
    record.setT165UnpaidLeaveDays(text(unpaid));
    record.setT16aEmployerSickDays(text(days(byBucket, BgD1DayBuckets.B_16_A)));

    // ⚠️ signum/сравнение с 0, не с null: генерираният getter за Integer връща 0.
    // ⚠️ ОТРИЦАТЕЛЕН БРОЙ ДНИ ИЗВЪН ОСИГУРЯВАНЕТО НЕ СЪЩЕСТВУВА и се чете като нула — по същия
    // довод като в BgEarningsResolver#carvedDays: сгрешен знак нагоре по веригата би УВЕЛИЧИЛ
    // т. 16.1, тоест би обявил повече осигурени дни, отколкото месецът има.
    int outside = Math.max(0, outsideInsuranceDays);
    int workedDays = slip == null ? 0 : slip.getBgWorkedDays();
    // ⚠️ БОЛНИЧНИТЕ ДНИ БЕЗ ПРАВО НА ОБЕЗЩЕТЕНИЕ СА В Т. 16.4, НО НЕ СЕ ВАДЯТ ОТ ОТРАБОТЕНИТЕ ВТОРИ
    // ПЪТ: BgSickLeaveService#applyDays вече ги е свалил от bgWorkedDays, както всички болнични
    // дни.
    // Изваждаемото е само ОТПУСКНАТА част на т. 16.4 — тя стои в отработените на фиша.
    int leaveNoContribution =
        Math.max(
            0, noContribution - Math.min(noContribution, BgD1DaySource.sickWithoutBenefit(slip)));
    int worked = workedDays - childCare - leaveNoContribution - unpaid - outside;
    if (worked < 0) {
      findings.add(
          finding(
                  "D1_T161_NEGATIVE_REMAINDER",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 16.1 излиза отрицателна: отработени дни "
                      + workedDays
                      + " минус отпуските ("
                      + childCare
                      + " за дете + "
                      + leaveNoContribution
                      + " без вноски + "
                      + unpaid
                      + " неплатен + "
                      + outside
                      + " извън осигуряването) = "
                      + worked
                      + ". Двата източника си противоречат — най-вероятно отпускът вече е приспаднат"
                      + " РЪЧНО от отработените дни на фиша и се вади втори път. Записана е нула,"
                      + " защото отрицателен брой дни не съществува, но числото трябва да се провери"
                      + " НА РЪКА преди подаване.",
                  slip,
                  insuredCode)
              .at(BgD1DayBuckets.B_16_1));
      worked = 0;
    }
    record.setT161WorkedDays(text(worked));

    sickDaysWithoutTheirLine(slip, findings, insuredCode);

    // ═══ ПОЗИЦИИ 1 И 2 — СБОРЪТ, дословно по заб. 1 ═══
    Map<String, Integer> withWorked = new LinkedHashMap<>(byBucket);
    withWorked.put(BgD1DayBuckets.B_16_1, worked);
    int total = BgD1DayBuckets.total(withWorked);
    record.setT16Days(twoPositions(total));

    int workingDaysInPeriod = slip == null ? 0 : slip.getBgWorkingDaysInPeriod();
    if (workingDaysInPeriod > 0 && total > workingDaysInPeriod) {
      findings.add(
          finding(
                  "D1_T16_EXCEEDS_PERIOD",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 16 дава "
                      + total
                      + " дни в осигуряване, а периодът има "
                      + workingDaysInPeriod
                      + " работни дни. Сборът по заб. 1 не бива да надхвърля месеца — най-вероятно"
                      + " едно отсъствие е преброено в две кофи, или заявка за отпуск се припокрива"
                      + " с болничен епизод.",
                  slip,
                  insuredCode)
              .at("16"));
    }

    // ═══ ПОЗИЦИЯ 4 — ДНЕВНОТО ДОГОВОРЕНО работно време ═══
    // ⚠️ Закръглянето е ДАДЕНО от указанието и затова е тук, а не мнение: „когато при изчисляването
    // се получи остатък, равен или по-голям от половин час, се закръглява на пълен час“ — тоест
    // HALF_UP до цял час, а не отсичане.
    if (BgD1InsuredTypeRules.zeroWorkTime(type)) {
      // Седемнайсетте кода на заб. към т. 16 попълват 0 в позиции 3 и 4. Нулата тук е СТОЙНОСТ.
      record.setT16LegalHours(ZERO_HOURS);
      record.setT16ContractedHours(ZERO_HOURS);
      return;
    }

    BigDecimal contracted = contract == null ? null : contract.getBgHoursPerDay();
    if (contracted != null && contracted.signum() != 0) {
      record.setT16ContractedHours(text(contracted.setScale(0, RoundingMode.HALF_UP).intValue()));
    }

    // ═══ ПОЗИЦИЯ 3 — ЗАКОНОУСТАНОВЕНОТО работно време ЗА ДЪЛЖНОСТТА ═══
    // ⚠️ ЧЕТЕ СЕ ОТ ДОГОВОРА, А НЕ СЕ КОПИРА ОТ ПОЗИЦИЯ 4, И РАЗЛИКАТА Е НЕВИДИМА ОТ ЧИСЛОТО. За
    // лице на пълен щат двете са 8 и всяко копиране изглежда вярно; разминават се при НАМАЛЕНО
    // работно време по чл. 137 КТ (7 или 6 часа за длъжността) и при непълно по чл. 138 (4 по
    // договор при 8 по закон). Носителят е нов (EmploymentContract.bgLegalHoursPerDay) и стои ТАМ,
    // защото законоустановеното работно време се сменя при смяна на ДЛЪЖНОСТТА, а не всеки месец —
    // на записа то трябваше да се въвежда наново за всяко лице при всяко генериране, а генерирането
    // е ЗАМЯНА и изтрива ръчно въведеното.
    BigDecimal legal = contract == null ? null : contract.getBgLegalHoursPerDay();
    if (legal != null && legal.signum() != 0) {
      record.setT16LegalHours(text(legal.setScale(0, RoundingMode.HALF_UP).intValue()));
    } else {
      findings.add(
          finding(
                  "D1_T16_LEGAL_WORKTIME_MISSING",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 16 позиция 3 (законоустановеното работно време за ДЛЪЖНОСТТА) остава празна:"
                      + " договорът няма попълнено „Законоустановено работно време за длъжността“."
                      + " ⚠️ НЕ Е копие на позиция 4: те съвпадат при пълен щат и се разминават при"
                      + " намалено работно време по чл. 137 КТ (7 или 6 часа) и при непълно по чл."
                      + " 138. Попълни го НА ДОГОВОРА — там се сменя при смяна на длъжността, а тук"
                      + " би трябвало да се въвежда всеки месец наново.",
                  slip,
                  insuredCode)
              .at("16"));
    }

    // ═══ Т. 16.7 / 16.8 — ЧАСОВЕТЕ, от отчетите за време и от допълнителните часове ═══
    // ⚠️ Двайсет и трите кода с bgNoHours НЕ ги попълват по норма — за тях празното е СТОЙНОСТ и
    // находка не се вдига. За останалите източникът СЪЩЕСТВУВА и е същият, който сглобителят на
    // фиша чете: ВАЛИДИРАНИ TimesheetLine и ExtraHoursLine (виж BgD1HourSource защо дословно
    // същите филтри). Нула НЕ се записва: „нула отработени часа“ за работещ човек е числото, което
    // НАП отхвърля — липсата на отчети дава ПРАЗНА точка с назована причина.
    if (!BgD1InsuredTypeRules.noHours(type)) {
      fillHours(record, hours, worked, contracted, findings, slip, insuredCode);
    }
  }

  /**
   * Т. 16.7 и Т. 16.8 — часовете, с ТРИТЕ си различни празни изхода.
   *
   * <p><b>⚠️ ДВЕТЕ ТОЧКИ СЕ ПЪЛНЯТ ПООТДЕЛНО, макар да идват от един прочит.</b> Т. 16.8 иска САМО
   * извънредния труд и той има свой източник ({@code ExtraHoursLine} с класификация); т. 16.7 иска
   * отработените ПЛЮС извънредния ПЛЮС часовете на платения отпуск. Инсталация, която води отчети
   * за време и НЕ води извънреден труд, може да попълни първата и не втората — слети в едно
   * условие, двете щяха да изчезнат заедно.
   *
   * <p><b>⚠️ И ЕДНО ДОПУСКАНЕ, КОЕТО СЕ ОБЯВЯВА.</b> Часовете на платения отпуск нямат носител:
   * отчетът за време брои ОТРАБОТЕНОТО, а отпускът се мери в ДНИ. Извеждат се като остатък (т. 16.1
   * минус отчетените дни) по ДНЕВНОТО ДОГОВОРЕНО работно време. Когато остатъкът е положителен, а
   * договореното работно време липсва, точката НЕ се попълва: сбор без третата си съставка е
   * по-МАЛКО число, изглеждащо точно толкова пресметнато.
   */
  static void fillHours(
      BgD1Record record,
      BgD1HourSource.Hours hours,
      int insuredDays,
      BigDecimal contractedHoursPerDay,
      List<BgD1FindingDto> findings,
      BgPaySlip slip,
      String insuredCode) {

    BigDecimal overtime = BgD1HourSource.overtimeHours(hours);
    if (overtime != null) {
      record.setT168OvertimeHours(text(overtime.intValue()));
    }

    if (hours == null || hours.getWorked() == null) {
      findings.add(
          finding(
                  "D1_T167_T168_HOURS_MISSING",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 16.7 остава празна: за това лице и период НЯМА нито един ВАЛИДИРАН отчет за"
                      + " време. ⚠️ Черновата не е вход за ведомост — същият филтър, с който"
                      + " сглобителят на фиша чете часовете. Нула НЕ се записва: „нула отработени"
                      + " часа“ е твърдение, което НАП отхвърля за работещо лице."
                      + (overtime == null
                          ? " Т. 16.8 също остава празна — валидирани редове с допълнителни часове"
                              + " няма."
                          : " Т. 16.8 обаче Е попълнена от валидираните допълнителни часове:"
                              + " двата източника са различни и липсват поотделно."),
                  slip,
                  insuredCode)
              .at("16.7"));
      return;
    }

    int paidLeaveDays = insuredDays - hours.getTimesheetDays();
    if (paidLeaveDays > 0
        && (contractedHoursPerDay == null || contractedHoursPerDay.signum() == 0)) {
      findings.add(
          finding(
                  "D1_T167_PAID_LEAVE_HOURS_UNKNOWN",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 16.7 остава празна, макар отчет за време да ИМА: т. 16.1 дава "
                      + insuredDays
                      + " дни с вноски, а отчетът носи часове само за "
                      + hours.getTimesheetDays()
                      + " от тях — тоест "
                      + paidLeaveDays
                      + " дни платен отпуск, чиито часове се извеждат по ДНЕВНОТО ДОГОВОРЕНО работно"
                      + " време. То не е попълнено на договора. ⚠️ Записан без третата си съставка,"
                      + " сборът е по-МАЛКО число, изглеждащо точно толкова пресметнато — а"
                      + " указанието иска „включително … броят на часовете за времето на платен"
                      + " отпуск“.",
                  slip,
                  insuredCode)
              .at("16.7"));
      return;
    }

    if (paidLeaveDays < 0) {
      findings.add(
          finding(
                  "D1_T167_MORE_DAYS_THAN_INSURED",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "отчетът за време носи часове за "
                      + hours.getTimesheetDays()
                      + " дни, а т. 16.1 дава само "
                      + insuredDays
                      + " дни с осигурителни вноски. Двата източника си противоречат — най-вероятно"
                      + " отчетът покрива дни извън осигуряването (или отпускът е приспаднат ръчно"
                      + " от отработените дни на фиша). Часовете на платен отпуск НЕ се добавят"
                      + " (отрицателен брой дни не съществува), но т. 16.7 трябва да се провери НА"
                      + " РЪКА преди подаване.",
                  slip,
                  insuredCode)
              .at("16.7"));
    }

    BigDecimal total = BgD1HourSource.totalWorkedHours(hours, insuredDays, contractedHoursPerDay);
    if (total != null) {
      record.setT167WorkedHours(text(total.intValue()));
    }
  }

  /**
   * Т. 13 — КОДЪТ ЗА СУМИРАНО ИЗЧИСЛЯВАНЕ, <b>САМО за вид осигурен 16</b>.
   *
   * <p><b>УКАЗАНИЕТО ДОСЛОВНО:</b> „когато в т. 12 е попълнен код за вид осигурен 16, в двете
   * позиции се попълва: Позиция 1: от 02 до 12 в съответствие с БРОЯ МЕСЕЦИ в установения период на
   * сумирано изчисляване на работното време. Позиция 2: от 01 до 12 според ПОРЕДНОСТТА НА МЕСЕЦА от
   * периода на сумирано изчисляване, за който се подават данните.“
   *
   * <p><b>⚠️ УСЛОВИЕТО Е КОДЪТ, НЕ РЕЖИМЪТ НА ДОГОВОРА.</b> Изкушението е да се пита {@code
   * bgWorkTimeMode == 2} („СИРВ“) — той описва същия факт. Но точката се пълни по УКАЗАНИЕТО, а то
   * изброява ВИД ОСИГУРЕН 16. Лице на СИРВ, декларирано с друг код (например 04 по допълнителен
   * трудов договор), НЕ носи т. 13 — попълнена, тя твърди нещо за КОДА, не за работното време.
   * Обратното също: код 16 без попълнен отчетен период на договора е НЕПЪЛНИ ДАННИ и се обявява, а
   * не се запълва с догадка.
   *
   * <p><b>⚠️ ПОЗИЦИЯ 2 НЯМА ИЗТОЧНИК, И ТОВА НЕ Е ТРУДНОСТ, А ЛИПСА.</b> Поредността иска НАЧАЛОТО
   * на отчетния период — акт на работодателя по КТ чл. 142 ал. 2, който може да започне в който и
   * да е месец. Изведена от календарната година („период от 3 месеца → януари е първи“), тя е вярна
   * за половината работодатели и невярна за другата, при това с ВАЛИДНА цифра, която НАП приема.
   * Затова се въвежда на ръка и празнотата се обявява.
   */
  static void fillSirvCode(
      BgD1Record record,
      EmploymentContract contract,
      String insuredCode,
      List<BgD1FindingDto> findings,
      BgPaySlip slip) {

    if (!BgD1InsuredTypeRules.SIRV_CODE.equals(insuredCode)) {
      return;
    }

    // ⚠️ signum/сравнение с 0, не с null: генерираният getter за Integer връща 0.
    int months = contract == null ? 0 : contract.getBgSirvPeriodMonths();
    if (months < 2 || months > 12) {
      findings.add(
          finding(
                  "D1_T13_SIRV_PERIOD_MISSING",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "вид осигурен 16 (сумирано изчисляване за ПОВЕЧЕ ОТ ЕДИН месец) иска т. 13, а"
                      + " договорът носи отчетен период „"
                      + months
                      + "“ месеца — указанието допуска от 02 до 12. Точката остава празна. ⚠️ Попълни"
                      + " „Отчетен период при СИРВ (месеци)“ на договора: стойност под 2 противоречи"
                      + " на самия код 16.",
                  slip,
                  insuredCode)
              .at("13"));
    } else {
      record.setT13SirvPeriodMonths(twoPositions(months));
    }

    findings.add(
        finding(
                "D1_T13_MONTH_INDEX_MISSING",
                BgD1FindingDto.SEVERITY_WARNING,
                "т. 13 позиция 2 (поредността на месеца от периода на сумирано изчисляване) остава"
                    + " празна: тя иска НАЧАЛОТО на отчетния период, а то е акт на работодателя по"
                    + " КТ чл. 142 ал. 2 и няма носител в модела. ⚠️ Изведена от календарната година,"
                    + " цифрата е ВАЛИДНА и невярна за всеки работодател, чийто период не започва"
                    + " през януари. Попълни я на ръка на записа преди подаване.",
                slip,
                insuredCode)
            .at("13"));
  }

  /**
   * Нулата, която НАРЕДБАТА иска за 17-те кода с {@code bgZeroWorkTime} — „попълват 0“.
   *
   * <p>⚠️ ИМЕНУВАНА, за да се вижда, че е ТВЪРДЕНИЕ, а не липса. Празният низ на същото място значи
   * „няма носител“ и се обявява с находка; „0“ значи „нула е отговорът“.
   */
  private static final String ZERO_HOURS = "0";

  /** Число → низ. ⚠️ Без подплънка: кофите се попълват „в две позиции“ от сериализатора. */
  private static String text(int value) {
    return String.valueOf(value);
  }

  /** Дните в една кофа, с нула за липсваща. */
  private static int days(Map<String, Integer> byBucket, String bucket) {
    Integer value = byBucket == null ? null : byBucket.get(bucket);
    return value == null ? 0 : value;
  }

  /**
   * ПЪРВАТА ДВОЙКА на т. 14/15, и ОБЯВЕНАТА ГРАНИЦА за останалите четири.
   *
   * <p><b>т. 14</b> — „първият календарен ден в осигуряване“. Попълва се САМО когато осигуряването
   * е възникнало ВЪТРЕ в месеца: договор, започнал на 12-о число, дава „12“. За лице, осигурено
   * през целия месец, точката остава празна — и това е разликата, която НАП чете. Записан като „01“
   * за всеки месец, той би твърдял, че осигуряването е възниквало наново дванайсет пъти в годината.
   *
   * <p><b>т. 15</b> — „последният календарен ден в осигуряване при всяко прекъсване или
   * прекратяване“. ⚠️ ЧЕТЕ {@code bgTerminationDate}, А НЕ {@code endDate}, и точно тук разликата
   * между двете се превръща в грешна декларация: при СРОЧЕН договор {@code endDate} е УГОВОРЕНИЯТ
   * край и се попълва още при подписването — тоест може да стои в бъдещето или в друг месец.
   * Прочетен като т. 15, той обявява прекратяване, което не е станало. {@code bgTerminationDate}
   * съществува точно за да носи действителното прекратяване (виж домейна на EmploymentContract).
   *
   * <p><b>⚠️ ПОЗИЦИИ 2–5 НЯМАТ НОСИТЕЛ, И ТОВА СЕ ОБЯВЯВА, НЕ СЕ ПРЕМЪЛЧАВА.</b> Формата допуска до
   * пет прекъсвания в един месец; моделът днес знае само НАЧАЛОТО и КРАЯ на договора, тоест може да
   * опише най-много едно. Междинно прекъсване и възобновяване на осигуряването (не отпуск —
   * ПРЕКЪСВАНЕ на самото осигуряване) няма къде да живее. Затова колоните ги ИМА — форматът ги иска
   * и сериализаторът трябва да ги подаде като нули, — но пълни се само първата двойка. Празна
   * позиция 2 и „нямаше второ прекъсване“ изглеждат еднакво, и точно затова разликата е записана.
   */
  static void fillInsuranceBoundaries(
      BgD1Record record,
      BgD1Carrier carrier,
      Period dataPeriod,
      List<LocalDate[]> interruptions,
      List<BgD1FindingDto> findings,
      BgPaySlip slip,
      String insuredCode) {

    if (carrier == null || !carrier.isPresent() || dataPeriod == null) {
      return;
    }
    LocalDate from = dataPeriod.getFromDate();
    LocalDate to = dataPeriod.getToDate();
    if (from == null || to == null) {
      return;
    }

    // ⚠️ ЗА САМООСИГУРЯВАЩИЯ СЕ ТОВА Е НАЧАЛОТО НА ДЕЙНОСТТА, не датата на регистрацията:
    // осигуряването тръгва от УПРАЖНЯВАНЕТО (КСО чл. 4 ал. 3 — „лицата, упражняващи трудова
    // дейност като…“). Регистриран, но неупражняващ дейност човек не дължи вноски.
    LocalDate start = carrier.insuranceStart();
    boolean arisesInside = start != null && !start.isBefore(from) && !start.isAfter(to);

    // ⚠️ bgTerminationDate ПЪРВО и endDate само като резерва — не обратното. Виж javadoc-а.
    LocalDate end = carrier.insuranceEnd();
    boolean endsInside = end != null && !end.isBefore(from) && !end.isAfter(to);

    BgD1InsuranceSegments.Outcome outcome =
        BgD1InsuranceSegments.of(
            from, to, arisesInside ? start : null, endsInside ? end : null, interruptions);

    List<BgD1InsuranceSegments.Segment> segments = outcome.getSegments();
    for (int i = 0; i < segments.size(); i++) {
      BgD1InsuranceSegments.Segment segment = segments.get(i);

      // ⚠️ Т. 14 СЕ ПОПЪЛВА САМО КОГАТО ОСИГУРЯВАНЕТО НАИСТИНА Е ВЪЗНИКНАЛО ИЛИ ВЪЗОБНОВЕНО.
      // Първият отрязък носи ден само ако договорът започва ВЪТРЕ в месеца; всеки следващ Е
      // възобновяване по устройство. Записано „01“ за всеки месец, полето би твърдяло, че
      // осигуряването е възниквало наново дванайсет пъти в годината.
      if (i > 0 || arisesInside) {
        setT14(record, i, twoPositions(segment.getFrom().getDayOfMonth()));
      }

      // ⚠️ И Т. 15 — само при ПРЕКЪСВАНЕ или ПРЕКРАТЯВАНЕ. Всеки отрязък освен последния е следван
      // от прекъсване; последният носи ден само ако договорът е прекратен вътре в месеца.
      if (i < segments.size() - 1 || endsInside) {
        setT15(record, i, twoPositions(segment.getTo().getDayOfMonth()));
      }
    }

    // ⚠️ ОРЯЗВАНЕТО Е ЗАГУБА НА ДАННИ И СЕ ОБЯВЯВА. Форматът носи ПЕТ двойки; шести отрязък няма
    // къде да отиде. Премълчано, това дава декларация, която изглежда пълна и премълчава прекъсване
    // — точно случаят, за който позициите съществуват.
    if (outcome.isTruncated()) {
      findings.add(
          finding(
                  "D1_T14_T15_MORE_THAN_FIVE",
                  BgD1FindingDto.SEVERITY_ERROR,
                  "осигуряването има "
                      + outcome.getTotal()
                      + " отрязъка през този месец, а полета 9–18 носят само ПЕТ двойки. Записани са"
                      + " първите пет; останалите ИЗЧЕЗВАТ от декларацията. ⚠️ Проверете заявките за"
                      + " отпуск на лицето: толкова прекъсвания в един месец обикновено значат"
                      + " раздробени заявки за едно и също отсъствие, които се сливат само когато са"
                      + " ДОПИРАЩИ СЕ или припокриващи се.",
                  slip,
                  insuredCode)
              .at("14"));
    }
  }

  /**
   * КОДЪТ ПО КИД 2008 в четири цифри — точката се МАХА.
   *
   * <p>Речникът носи „01.11“ (всичките 651 реда на {@code bg_nkid.csv} са точно „NN.NN“), а поле 29
   * е Char с размер РАВЕН НА 4. Тоест препратената стойност е с един знак по-дълга от полето.
   *
   * <p>⚠️ Маха се точката, а НЕ се реже до четири знака. „01.11“, отрязан отляво, дава „01.1“ — низ
   * със същата дължина и напълно друг смисъл, който минава всяка проверка за дължина.
   */
  public static String activityCode(String nkidCode) {
    String c = trim(nkidCode);
    if (c == null || c.isEmpty()) {
      return null;
    }
    return c.replace(".", "");
  }

  /**
   * Т. 14 на позиция {@code index} (0-базирана) — ПЕТ КОЛОНИ, един вход.
   *
   * <p>⚠️ {@code switch}, а НЕ отражение по име („setT14Day“ + (index+1)). Отражението би приело
   * шеста позиция и щеше да падне по ВРЕМЕ НА ИЗПЪЛНЕНИЕ, при това с изключение, което не назовава
   * формата; {@code switch} с {@code default: throw} прави границата на ПЕТТЕ видима на едно място
   * и невъзможна за подминаване. Броят е ЗАКОВАН от полета 9–18, не е отворен списък.
   */
  private static void setT14(BgD1Record record, int index, String value) {
    switch (index) {
      case 0:
        record.setT14Day1(value);
        break;
      case 1:
        record.setT14Day2(value);
        break;
      case 2:
        record.setT14Day3(value);
        break;
      case 3:
        record.setT14Day4(value);
        break;
      case 4:
        record.setT14Day5(value);
        break;
      default:
        throw new IllegalArgumentException(
            "полета 9–18 носят ПЕТ двойки; позиция " + (index + 1) + " не съществува във формата");
    }
  }

  /** Т. 15 на позиция {@code index} (0-базирана). Виж {@link #setT14} защо е {@code switch}. */
  private static void setT15(BgD1Record record, int index, String value) {
    switch (index) {
      case 0:
        record.setT15Day1(value);
        break;
      case 1:
        record.setT15Day2(value);
        break;
      case 2:
        record.setT15Day3(value);
        break;
      case 3:
        record.setT15Day4(value);
        break;
      case 4:
        record.setT15Day5(value);
        break;
      default:
        throw new IllegalArgumentException(
            "полета 9–18 носят ПЕТ двойки; позиция " + (index + 1) + " не съществува във формата");
    }
  }

  /** Две позиции с водеща нула — „попълва се в две позиции“. */
  private static String twoPositions(int day) {
    return String.format("%02d", day);
  }

  /**
   * Превежда нашия тип идентификатор в кода на т. 5.1.
   *
   * <p>⚠️ ПРЕВОД, НЕ ПРЕПРАЩАНЕ. Наредбата: „0 — ако лицето е с единен граждански номер; 2 — ако
   * лицето е с личен номер на чужденец, личен номер или служебен номер“. Нашата селекция носи „1“
   * за ЕГН. Празно се чете като ЕГН — обичайният случай за български работодател, и същият прочит,
   * който {@code BgIdentifierValidator} прави.
   */
  public static String idCodeOf(String ourIdType) {
    String t = trim(ourIdType);
    if (t == null || t.isEmpty() || OUR_ID_TYPE_EGN.equals(t)) {
      return ID_CODE_EGN;
    }
    return ID_CODE_FOREIGN;
  }

  /**
   * Инициалите по т. 7: „първата буква от името и първата буква от презимето“.
   *
   * <p>⚠️ Липсващото презиме дава ЕДНА буква, не запълва с точка и не пада. Наредбата иска
   * инициали, а лице без вписано презиме има един инициал — измислен втори знак е данни, които
   * никой не е казал.
   */
  public static String initials(String firstName, String middleName) {
    StringBuilder sb = new StringBuilder(2);
    appendFirstLetter(sb, firstName);
    appendFirstLetter(sb, middleName);
    return sb.length() == 0 ? null : sb.toString();
  }

  private static void appendFirstLetter(StringBuilder sb, String value) {
    String v = trim(value);
    if (v != null && !v.isEmpty()) {
      sb.append(v.charAt(0));
    }
  }

  /**
   * ДНИТЕ БОЛНИЧЕН И ПЕРАТА ИМ ТРЯБВА ДА КАЗВАТ ЕДНО — <b>находка, не отказ</b>.
   *
   * <p><b>Какво сверява.</b> Двата броя на фиша носят т. 16.А и т. 16.2 и нямат друг източник; на
   * същия фиш перата носят парите за тях — {@code pl_bolnichen_rabotodatel} (чл. 40 ал. 5 КСО) за
   * дните на осигурителя и вноската по ЗЗО чл. 40 ал. 1 т. 5 за дните на ДОО. Разминат ли се,
   * декларацията твърди дни, зад които на фиша няма нито стотинка, или обратното.
   *
   * <p><b>⚠️ ЗАЩО ПЕРО 516 СЕ ТЪРСИ ПО {@code bgHealthBaseKind}, А НЕ ПО НОМЕР.</b> Редът се пише
   * от {@code BgContributionLineService#upsertSpecialHealthBase} и е от СЪЩИЯ тип като нормалната
   * здравна вноска — различава ги само базата-дискриминатор {@link
   * BgD1ContributionAggregator#BASE_ART40_1_5}. Търсен по номер, проверката щеше да намира и
   * обикновената здравна вноска и никога да не пали.
   *
   * <p><b>⚠️ И ЗАЩО ПРЕДУПРЕЖДЕНИЕ, А НЕ ОТКАЗ ПРИ ПОТВЪРЖДАВАНЕ.</b> Три довода, всеки
   * самостоятелен:
   *
   * <ul>
   *   <li><b>„дни &gt; 0 без перо“ е ЗАКОННО състояние.</b> {@code BgSickLeaveService#applyDays}
   *       пише дните ПРЕДИ резолверът да реши дали има сума; при отказ (непопълнена база, липсващ
   *       {@code HEALTH_ABSENCE_BASE}, непопълнен процент) фишът носи дни и няма перо — и това вече
   *       е обявено в {@code bgGeneratorGaps}. Отказ тук би спрял фиш, чийто дефект е назован.
   *   <li><b>мъртъв ъгъл с read-only панела.</b> На коригиращо копие перата СА редактируеми, а
   *       дните — не. Махне ли операторът перо 195 законно, отказ би бил незагасим от нито една
   *       страна.
   *   <li><b>цената на грешката.</b> Отказът спира ЕДИН фиш преди да е платен; тук находката стои
   *       върху декларация за десетки лица и не бива да я спира заради едно разминаване.
   * </ul>
   *
   * <p>Същата асиметрия и същият довод като при {@code BgD1DaySource}: там противоречивият речник е
   * находка, а в сглобителя — отказ.
   */
  static void sickDaysWithoutTheirLine(
      BgPaySlip slip, List<BgD1FindingDto> findings, String insuredCode) {
    if (slip == null) {
      return;
    }
    // ⚠️ signum/сравнение с 0, не с null: генерираният getter за Integer връща 0.
    int employerDays = slip.getBgSickDaysEmployer();
    int dooDays = slip.getBgSickDaysDoo();
    boolean hasSickPay = false;
    boolean hasHealthAbsence = false;
    for (BgPaySlipLine line :
        slip.getBgPaySlipLineList() == null
            ? Collections.<BgPaySlipLine>emptyList()
            : slip.getBgPaySlipLineList()) {
      if (line == null) {
        continue;
      }
      BgPaySlipLineType lineType = line.getBgPaySlipLineType();
      if (lineType != null
          && BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY.equals(lineType.getCode())) {
        hasSickPay = true;
      }
      if (BgD1ContributionAggregator.BASE_ART40_1_5.equals(trim(line.getBgHealthBaseKind()))) {
        hasHealthAbsence = true;
      }
    }

    if ((employerDays > 0) != hasSickPay) {
      findings.add(
          finding(
                  "D1_T16A_DAYS_LINE_MISMATCH",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 16.А обявява "
                      + employerDays
                      + " дни за сметка на осигурителя, а перото „Болничен от осигурител (чл. 40 ал."
                      + " 5 КСО)“ "
                      + (hasSickPay ? "СЪЩЕСТВУВА" : "ЛИПСВА")
                      + " на фиша. Двете описват едни и същи дни: точката ги декларира, перото ги"
                      + " плаща. Провери дали генераторът е отказал (виж присъдите на фиша) или"
                      + " перото е махнато на ръка — при коригиращ фиш дните идват от оригинала и НЕ"
                      + " се преизчисляват.",
                  slip,
                  insuredCode)
              .at(BgD1DayBuckets.B_16_A));
    }
    // ⚠️ ПРАВИЛОТО Е ЗА ВСИЧКИ ДНИ ДОО — и тези в т. 16.2, и болничните БЕЗ ПРАВО на обезщетение в
    // т. 16.4. Вноската по ЗЗО чл. 40 ал. 1 т. 5 е за сметка на работодателя и при двата вида дни.
    int withoutBenefit = BgD1DaySource.sickWithoutBenefit(slip);
    if ((dooDays > 0) != hasHealthAbsence) {
      findings.add(
          finding(
                  "D1_T162_DAYS_LINE_MISMATCH",
                  BgD1FindingDto.SEVERITY_WARNING,
                  "т. 16.2 обявява "
                      + (dooDays - withoutBenefit)
                      + (withoutBenefit > 0
                          ? " и т. 16.4 — " + withoutBenefit + " (без право на обезщетение)"
                          : "")
                      + " дни за сметка на ДОО, а перото със здравната вноска по ЗЗО чл. 40 ал. 1 т."
                      + " 5 (база "
                      + BgD1ContributionAggregator.BASE_ART40_1_5
                      + ") "
                      + (hasHealthAbsence ? "СЪЩЕСТВУВА" : "ЛИПСВА")
                      + ". Обезщетението за тези дни НОИ плаща на лицето директно и НЕ е перо, но"
                      + " вноската върху тях е за сметка на работодателя и е — тоест дни без нея са"
                      + " недекларирана вноска, а вноска без дни е сума без основание.",
                  slip,
                  insuredCode)
              .at(dooDays > withoutBenefit ? BgD1DayBuckets.B_16_2 : BgD1DayBuckets.B_16_4));
    }
  }

  private static BgD1FindingDto finding(
      String code, String severity, String message, BgPaySlip slip, String insuredCode) {
    return new BgD1FindingDto(code, severity, message)
        .on(slip == null ? null : slip.getReference(), insuredCode);
  }

  private static String trim(String value) {
    return value == null ? null : value.trim();
  }
}
