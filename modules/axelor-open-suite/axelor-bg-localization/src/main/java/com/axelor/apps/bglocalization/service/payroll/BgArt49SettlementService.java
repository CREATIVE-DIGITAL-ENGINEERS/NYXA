package com.axelor.apps.bglocalization.service.payroll;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgArt73Para6Annual;
import com.axelor.apps.bglocalization.db.BgArt73Para6Report;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.repo.BgArt73Para6ReportRepository;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipLineTypeRepository;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.service.art49.BgArt49Settlement;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import com.axelor.inject.Beans;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ГОДИШНОТО ИЗРАВНЯВАНЕ ПО ЧЛ. 49 СТАВА ПЕРО — вторият и последен ред на група 7xx.
 *
 * <p><b>⚠️ ТОЗИ ГЕНЕРАТОР НЕ СМЯТА НИЩО. ТОЙ КОНСУМИРА.</b> Годишната данъчна основа, облекченията
 * с петте им тавана, годишният данък и разликата по колона 19 са сметнени от {@code
 * BgArt49Recalculation} и ЗАПИСАНИ в {@code BgArt73Para6Annual} при генерирането на справката по
 * чл. 73 ал. 6. Тук се чете готовата колона 19 и се превръща в ред на фиша. Второ смятане би било
 * второ число за една и съща норма — и двете биха изглеждали еднакво правилни.
 *
 * <p><b>⚠️ И ЗАТОВА ТУК НЕ СЕ ПИТА {@code BgArt49MainEmployer}.</b> Правомощието по чл. 49 ал. 1
 * („работодател по ОСНОВНОТО трудово правоотношение към 31 декември“) е вече решено — веднъж, при
 * строенето на справката, и отговорът му е овеществен: {@code BgArt73Para6Annual} се създава САМО
 * за такова лице. Питан втори път тук, той щеше да чете договорите ПОНОВО, в друг момент от
 * времето, и можеше да отговори различно от реда, чието число ще се удържи. Съществуването на реда
 * Е отговорът — точно както домейнът му казва.
 *
 * <p><b>═══ ТРИТЕ СЪСТОЯНИЯ, И ТЕ НЕ СА ДВЕ ═══</b>
 *
 * <ul>
 *   <li><b>Ред има</b> → перото се пише (или се обявява законна нула при колона 19 = 0).
 *   <li><b>Ред няма, но СПРАВКАТА за годината съществува</b> → законна нула: това дружество не е
 *       работодател по основното ТПО на лицето към 31 декември и няма какво да изравнява. Никой не
 *       поправя нищо.
 *   <li><b>Справка за годината НЯМА</b> → <b>ОТКАЗ</b>. Не е същото като горното, колкото и еднакво
 *       да изглежда отвън — и в двата случая ред няма. Първото е отговор на закона, второто е
 *       „преизчисляването още не е правено“, а то Е ЗАДЪЛЖЕНИЕ по чл. 49 ал. 1 със срок 31 януари.
 *       Слети в едно, забравената справка щеше да се чете като „лицето няма право“ и никой нямаше
 *       да я генерира.
 * </ul>
 *
 * <p><b>⚠️ ОТКАЗЪТ НЕ ХВЪРЛЯ И НЕ Е ЗАЛОЖНИК НА ЯНУАРСКАТА ЗАПЛАТА.</b> Той е присъда ({@code
 * BgPaySlipLineOutcome#refused}), тоест влиза в {@code bgGeneratorRefusalCount} и се чете на фиша.
 * Хвърлен, той щеше да спре ЦЯЛАТА януарска ведомост заради справка, която по закон може да се
 * направи чак до 31 януари — тоест щеше да забрани нормалния ред на работа. Мълчал, щеше да остави
 * лице без дължимото му изравняване и никой нямаше да узнае. Присъдата е точно средното: видимо,
 * назовано, непречещо.
 *
 * <p><b>⚠️ ПЕРОТО Е OWN-PERIOD DEBIT/CREDIT 7xx, И ТОВА Е ЗАРАДИ Д6.</b> Указанието към т. 16 иска
 * „удържан данък по чл. 41а, чл. 42 <b>и чл. 49 ал. 5</b>“ ({@code BgD6Point#T16_TAX}), тоест
 * удържаната разлика ТРЯБВА да се помете от {@code BgD6Aggregator} — значи маркерът {@code
 * bgCorrectsPeriod} остава ПРАЗЕН. Възстановяването пада извън т. 16 от само себе си, защото
 * агрегаторът мете само DEBIT — и това също е вярното: чл. 49 ал. 7 прихваща върнатото от СЛЕДВАЩИ
 * вноски, не го декларира като отрицателен данък.
 *
 * <p><b>⚠️ И ЗАТОВА {@code taxFromLines} БЕШЕ СТЕСНЕН.</b> Двамата читатели на група 7xx искат
 * различни неща и законът го казва вместо нас: Д6 иска чл. 42 И чл. 49, а {@code refuseTaxMismatch}
 * сверява САМО месечния чл. 42 срещу заглавната сума. Без стеснението януарският фиш с изравняване
 * ставаше непотвърдим — пазач, който спира точно верните фишове.
 */
public class BgArt49SettlementService {

  /**
   * Стабилният код на перото.
   *
   * <p>⚠️ Един и същ низ живее в {@code bg_pay_slip_line_type.csv} и в {@link
   * BgPaySlipResolver#LINE_ART49_SETTLEMENT}, и съвпадението им е ЗАКОВАНО с тест — същият идиом
   * като {@code BgIncomeTaxService#LINE_INCOME_TAX}. ⚠️ Дефиницията е в РЕЗОЛВЕРА, не тук: {@code
   * taxFromLines} трябва да я знае, а той е слой ПОД генераторите. Тук е само препубликуване, за да
   * остане идиомът на генераторите еднакъв.
   */
  public static final String LINE_ART49_SETTLEMENT = BgPaySlipResolver.LINE_ART49_SETTLEMENT;

  // ═══ ГЕНЕРАТОРЪТ ════════════════════════════════════════════════════════

  /**
   * ЗАПИСВА ПЕРОТО НА ИЗРАВНЯВАНЕТО и връща какво е станало с него.
   *
   * <p><b>⚠️ ИДЕМПОТЕНТНО И АДИТИВНО</b> — перото се търси по СТАБИЛНИЯ КОД и намереното се
   * обновява на място; генераторът докосва само своя ред. Същият идиом като {@code
   * BgIncomeTaxService}.
   *
   * <p><b>⚠️ ПРАЗЕН СПИСЪК ЗА ВСЕКИ НЕЯНУАРСКИ ПЕРИОД — БЕЗ ПРИСЪДА.</b> Другите единайсет месеца
   * не са „случай, който този генератор е разгледал и е отхвърлил“: чл. 49 просто не се отнася до
   * тях. Присъда там щеше да пише единайсет реда годишно на всеки фиш в системата и щеше да направи
   * списъка с присъди нечетим точно за онзи, който го гледа заради нещо друго.
   */
  public List<BgPaySlipLineOutcome> generate(BgPaySlip slip) {
    if (!isEligibleDraft(slip)) {
      return Collections.emptyList();
    }
    Integer taxYear = settledTaxYear(slip);
    if (taxYear == null) {
      return Collections.emptyList();
    }
    return Collections.singletonList(apply(slip, taxYear));
  }

  /** Едното решение и, ако трябва, едното перо. */
  protected BgPaySlipLineOutcome apply(BgPaySlip slip, int taxYear) {
    Company company = companyOf(slip);
    Employee employee = employeeOf(slip);
    if (company == null || employee == null) {
      // ⚠️ ОТКАЗ, НЕ ПРЕСКАЧАНЕ. Фиш с трудов договор без дружество или без лице е непопълнен
      // запис, а не „лице без право на изравняване“: по него не може да се намери НИТО справката,
      // НИТО редът, тоест отговорът „няма какво да се изравни“ тук би бил изсмукан от липсата.
      return BgPaySlipLineOutcome.refused(
          LINE_ART49_SETTLEMENT,
          "фишът няма "
              + (company == null ? "дружество (payCompany на трудовия договор)" : "лице (employee)")
              + ", тоест справката по чл. 73 ал. 6 за "
              + taxYear
              + " не може да бъде намерена и годишното изравняване по чл. 49 НЕ се прилага."
              + " ⚠️ Това НЕ значи, че изравняване не се дължи — значи, че не може да се провери."
              + " Попълнете трудовия договор на фиша.");
    }

    BgArt73Para6Report report = report(company, taxYear);
    if (report == null) {
      // ⚠️ ОТКАЗ, НЕ НУЛА — виж класовия javadoc, трите състояния. Преизчисляването по чл. 49 ал. 1
      // е ЗАДЪЛЖЕНИЕ на работодателя по основното ТПО със срок 31 януари; липсващата справка значи
      // „още не е направено“, а не „не се дължи“.
      return BgPaySlipLineOutcome.refused(
          LINE_ART49_SETTLEMENT,
          "справката по чл. 73 ал. 6 за "
              + taxYear
              + " г. не е генерирана за това дружество — годишното изравняване по чл. 49 НЕ може да"
              + " се удържи или възстанови. ⚠️ Липсващата справка НЕ значи нулева разлика:"
              + " преизчисляването по чл. 49 ал. 1 е задължение на работодателя по основното ТПО и"
              + " срокът му е 31 януари. Генерирайте справката по чл. 73 ал. 6 за "
              + taxYear
              + " г., след което пуснете фиша наново.");
    }

    BgArt73Para6Annual annual = annualOf(report, employee);
    if (annual == null) {
      // ⚠️ ЗАКОННА НУЛА, не отказ. Справката я има и в нея за това лице НЯМА ред на преизчисляване
      // — а липсата на реда Е отговорът на чл. 49 ал. 1: към 31 декември това дружество не е
      // работодател по основното трудово правоотношение на лицето. Годишният му данък се определя
      // другаде (при другия работодател или с декларация по чл. 50) и перо ТУК би било твърдение,
      // което този работодател няма право да прави.
      return BgPaySlipLineOutcome.legalZero(
          LINE_ART49_SETTLEMENT,
          "справката по чл. 73 ал. 6 за "
              + taxYear
              + " г. е генерирана, но за това лице НЯМА ред на годишно преизчисляване — тоест към"
              + " 31 декември "
              + taxYear
              + " г. дружеството не е работодател по ОСНОВНОТО трудово правоотношение (чл. 49 ал."
              + " 1) и перо НЕ се създава. Годишният данък на лицето се определя при другия"
              + " работодател или с годишна данъчна декларация по чл. 50.");
    }

    // ⚠️ signum(), НЕ == null. Генерираният getter за decimal връща ZERO, тоест „още не е
    // попълнено“ и „нула“ пристигат тук като едно и също число — а за колона 19 те и ЗНАЧАТ
    // едно и също: няма какво да се изравнява. Написана с null, проверката е мъртъв код.
    BigDecimal settlement = annual.getSettlement();
    if (settlement.signum() == 0) {
      // ⚠️ ЗАКОННА НУЛА, И ТЯ Е РАЗЛИЧНА ОТ ГОРНАТА. Тук преизчисляване Е правено и разликата е
      // нула — авансово удържаното съвпада с годишния данък. Перо с 0,00 в група 7xx НЕ се създава
      // по същия довод като при чл. 42: то би заело ред на фиша и би твърдяло, че изравняване е
      // удържано. Разликата между двете нули живее в ТЕКСТА, а не в перо, защото перото не може да
      // я изрази — а присъдата може.
      return BgPaySlipLineOutcome.legalZero(
          LINE_ART49_SETTLEMENT,
          "лицето Е преизчислено по чл. 49 за "
              + taxYear
              + " г. и разликата по колона 19 е 0,00 — авансово удържаният данък по чл. 42 съвпада"
              + " с годишния по чл. 49 ал. 1, тоест нито се удържа (ал. 5), нито се възстановява"
              + " (ал. 6) и перо НЕ се създава.");
    }

    BigDecimal amount = settlement.abs().setScale(2, RoundingMode.HALF_UP);
    upsert(slip, amount, settlement, taxYear, annual.getAnnualTax());
    return BgPaySlipLineOutcome.computed(LINE_ART49_SETTLEMENT, amount, null, null);
  }

  /**
   * Записва перото — ново или обновено, със ЗНАК по колона 19.
   *
   * <p><b>⚠️ ЗНАКЪТ СЕ ПРЕПИСВА ВЪРХУ ВСЯКО МИНАВАНЕ, а не само при създаване.</b> Речникът дава
   * DEBIT (номиналната посока), а истинската идва от числото. Написан само при създаване, той щеше
   * да замръзне на посоката от ПЪРВОТО пускане: поправена справка, която обръща доплащане във
   * възстановяване, щеше да остави перо, което удържа сумата, вместо да я върне — с вярна абсолютна
   * стойност и обърнат знак, тоест с двойна грешка в нетото и без нищо, което да я покаже.
   *
   * <p>⚠️ ОСНОВАТА И СТАВКАТА ОСТАВАТ ПРАЗНИ, И ТОВА Е САМОТО ТВЪРДЕНИЕ — същият довод като при чл.
   * 41а. Колона 19 НЕ Е „основа × ставка“: тя е колона 18 МИНУС авансово удържаното, при това
   * ОТРЯЗАНА до цяла парична единица. Попълнени с колона 17 и годишната ставка, двете биха поканили
   * човек да ги умножи и да намери колона 18 — друго число, без обяснение защо не съвпада. Връзката
   * между трите числа се пише с ДУМИ, в бележката.
   */
  protected void upsert(
      BgPaySlip slip, BigDecimal amount, BigDecimal settlement, int taxYear, BigDecimal annualTax) {
    BgPaySlipLine line = lineWithCode(slip, LINE_ART49_SETTLEMENT);
    if (line == null) {
      BgPaySlipLineType type = lineType(LINE_ART49_SETTLEMENT);
      if (type == null) {
        // Речникът няма реда — App Install не е пускан. Мълчаливо прескачане би оставило лице без
        // дължимото му изравняване, при фиш, който изглежда напълно сметнат.
        throw new IllegalStateException(
            "типът перо „"
                + LINE_ART49_SETTLEMENT
                + "“ липсва в речника (bg_pay_slip_line_type.csv) — годишното изравняване по чл. 49"
                + " ЗДДФЛ ("
                + settlement.toPlainString()
                + ") не може да се запише като перо. Пусни App Install.");
      }
      line = lineFrom(type, amount);
      addLine(slip, line);
    }
    line.setBgAmount(amount);
    line.setBgSign(BgArt49Settlement.sign(settlement));
    line.setName(BgArt49Settlement.lineName(taxYear, settlement));
    line.setBgQuantity(null);
    line.setBgRate(null);
    line.setNote(note(settlement, taxYear, annualTax));
  }

  /** Бележката: откъде идва числото и как се сверява — с думи, не с две полета за умножаване. */
  protected String note(BigDecimal settlement, int taxYear, BigDecimal annualTax) {
    boolean withholding = BgArt49Settlement.isWithholding(settlement);
    return (withholding ? "Доплащане" : "Възстановяване")
        + " по чл. 49 ал. "
        + (withholding ? "5" : "6")
        + " ЗДДФЛ за "
        + taxYear
        + " г. Колона 19 от справката по чл. 73 ал. 6 = "
        + settlement.toPlainString()
        + " (дължим годишен данък, колона 18 = "
        + annualTax.toPlainString()
        + ", МИНУС авансово удържаното по чл. 42 за кодове 101 и 103, ОТРЯЗАНО до цяла парична"
        + " единица по указание т. 7). ⚠️ Числото НЕ се смята тук — чете се от справката."
        + (withholding
            ? " ⚠️ Влиза в т. 16 на Д6 за ЯНУАРИ и се внася до 25 февруари (чл. 65 ал. 11 изр. 2)."
            : " ⚠️ НЕ влиза в т. 16 на Д6: върнатото се прихваща от следващи вноски към бюджета"
                + " (чл. 49 ал. 7), а не се декларира като отрицателен данък.");
  }

  // ═══ ПРАВИЛАТА, КОИТО НЕ ПИПАТ БАЗАТА ═══════════════════════════════════

  /**
   * Чернова, която НЕ е коригираща и НЕ е заличаваща — единственото, върху което се пише.
   *
   * <p>⚠️ Ретро-фишовете се пропускат по същия довод като при останалите генератори: коригиращият
   * фиш носи числата на СВОЯ минал месец и храни декларация за него, а годишното изравняване е
   * събитие на ЯНУАРИ и няма какво да търси в коригиран август.
   */
  protected boolean isEligibleDraft(BgPaySlip slip) {
    if (slip == null || !BgPaySlipResolver.DRAFT.equals(slip.getStatusSelect())) {
      return false;
    }
    return !Boolean.TRUE.equals(slip.getBgIsCorrection())
        && !Boolean.TRUE.equals(slip.getBgIsDeletion());
  }

  /** Годината, която този фиш изравнява, или {@code null}, ако периодът му не е януарски. */
  protected Integer settledTaxYear(BgPaySlip slip) {
    Period period = slip.getPeriod();
    return period == null ? null : BgArt49Settlement.settledTaxYear(period.getFromDate());
  }

  /** Дружеството-работодател — от трудовия договор на фиша, както го чете и чл. 49 ал. 1. */
  protected Company companyOf(BgPaySlip slip) {
    EmploymentContract contract = slip.getEmploymentContract();
    return contract == null ? null : contract.getPayCompany();
  }

  /** Лицето — от трудовия договор. Самоосигуряващият се не стига дотук: чл. 49 е за ТПО. */
  protected Employee employeeOf(BgPaySlip slip) {
    EmploymentContract contract = slip.getEmploymentContract();
    return contract == null ? null : contract.getEmployee();
  }

  /**
   * Редът на преизчисляването за това лице в тази справка, или {@code null}.
   *
   * <p>⚠️ Търси се в ЗАРЕДЕНАТА справка, а не с втора заявка по (справка, лице): {@code annualList}
   * е o2m с unique-constraint (report, employee), тоест най-много един ред, и той вече е в паметта.
   */
  protected BgArt73Para6Annual annualOf(BgArt73Para6Report report, Employee employee) {
    List<BgArt73Para6Annual> annuals =
        report.getAnnualList() == null ? new ArrayList<>() : report.getAnnualList();
    for (BgArt73Para6Annual annual : annuals) {
      if (annual != null && employee.equals(annual.getEmployee())) {
        return annual;
      }
    }
    return null;
  }

  /** Перото на този фиш с този код на тип, или {@code null}. Кодът е стабилният идентификатор. */
  protected BgPaySlipLine lineWithCode(BgPaySlip slip, String code) {
    if (slip.getBgPaySlipLineList() == null) {
      return null;
    }
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (type != null && code.equals(type.getCode())) {
        return line;
      }
    }
    return null;
  }

  // ═══ ШЕВОВЕТЕ КЪМ БАЗАТА (подменят се в тестовете) ════════════════════════

  /**
   * СПРАВКАТА по чл. 73 ал. 6 за дружеството и годината, или {@code null}.
   *
   * <p>⚠️ Състоянието ѝ НЕ се филтрира. Справката има статус (GENERATED, подадена…), но чл. 49 ал.
   * 1 иска ПРЕИЗЧИСЛЯВАНЕ, а не подаване: годишният данък е определен в момента, в който редовете
   * са построени. Филтър по статус би отложил удържането до подаването в НАП — срок по чл. 73 ал.
   * 6, който е ДРУГ и по-късен от 31 януари.
   */
  protected BgArt73Para6Report report(Company company, int taxYear) {
    return Beans.get(BgArt73Para6ReportRepository.class)
        .all()
        .filter("self.company = :company AND self.taxYear = :year")
        .bind("company", company)
        .bind("year", taxYear)
        .fetchOne();
  }

  /** Редът на речника по стабилния си код, или {@code null}. */
  protected BgPaySlipLineType lineType(String code) {
    return Beans.get(BgPaySlipLineTypeRepository.class)
        .all()
        .filter("self.code = :c")
        .bind("c", code)
        .fetchOne();
  }

  /**
   * Ново перо от речника — ПРЕЗ {@code BgPaySlipService#lineFrom}, за да остане ЕДНО мястото,
   * където речникът се чете и замразява по копиране. ⚠️ Знакът, който идва оттам, е НОМИНАЛНИЯТ и
   * се преписва веднага в {@link #upsert}: тук правилото е числото, не речникът.
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
