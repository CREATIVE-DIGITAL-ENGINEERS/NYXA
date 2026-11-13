package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.service.d1.BgD1InsuredTypeRules;
import com.axelor.apps.bglocalization.service.d1.BgD6Emitter;
import com.axelor.apps.bglocalization.service.d1.BgD6Point;
import com.axelor.apps.bglocalization.service.payroll.BgIncomeTaxService;
import com.axelor.apps.bglocalization.service.payroll.BgPaySlipLineOutcome;
import com.axelor.apps.bglocalization.service.selfinsured.BgSelfInsuredRules;
import com.axelor.apps.hr.db.Employee;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * АЛТЕРНАТИВНИЯТ ДАНЪК ПО ЧЛ. 41А ЗДДФЛ — <b>ДРУГ ДАНЪК, А НЕ ДРУГА СТАВКА</b>.
 *
 * <p><b>═══ НОРМАТА, ДОСЛОВНО ═══</b> Сверено срещу официалния ЗДДФЛ на 28.08.2026 г.
 *
 * <p><b>Чл. 41а ал. 1:</b> „Доходите на физическите лица, които не са търговци по смисъла на
 * Търговския закон, от събиране на диворастящи гъби и плодове, с изключение на такива от лечебните
 * растения, се облагат ЕДНОКРАТНО с алтернативен данък в размер на 30 лв. за всеки месец, през
 * който се извършва дейността.“
 *
 * <p><b>Чл. 41а ал. 2:</b> „Данъкът по ал. 1 се удържа от държавните предприятия по чл. 163, ал. 1
 * от Закона за горите непосредствено преди издаването на позволително …“
 *
 * <p><b>⚠️ ЗАЩО ТОЗИ ТЕСТ СЪЩЕСТВУВА.</b> Разликата между двата данъка НЕ СЕ ВИЖДА по вида на
 * числото. При облагаем доход от порядъка на 150 € десетте на сто по чл. 42 дават около 15 € —
 * тоест почти същото като фиксираните 15,34 €. Тих провал точно от вида, който този модул гони:
 * фишът излиза, декларацията се подава, а данъкът е определен по норма, която не се прилага за това
 * лице. Затова свидетелят държи РАЗЛИЧНИ по големина числа — основа 1000,00 €, при която чл. 42 би
 * дал 100,00 €, а чл. 41а дава 15,34 €.
 *
 * <p><b>⚠️ И ЕДНО, КОЕТО ТЕСТЪТ НЕ ТВЪРДИ.</b> 15,34 € НЕ Е ЦИТАТ. Официалният ЗДДФЛ на машината
 * още носи „30 лв.“ (ДВ бр. 106 от 2023 г.), а закон за въвеждането на еврото с изрична цифра за
 * чл. 41а на нея НЯМА. Числото е ИЗВЕДЕНО по официалния курс 1,95583: 30 ÷ 1,95583 = 15,3388… →
 * 15,34 до стотинка. Появи ли се изрична цифра в закона, тя е водещата — и се сменя на ЕДНО място,
 * в seed-а (ALT_TAX_ART41A), не тук.
 */
class BgAlternativeTaxArt41aTest {

  /** Месечният размер по чл. 41а — както е зареден в {@code bg_payroll_parameter.csv}. */
  private static final BigDecimal ALT_TAX = new BigDecimal("15.3400");

  /** Общата ставка по чл. 42 ал. 4 — 10 на сто. */
  private static final BigDecimal RATE_ART42 = new BigDecimal("10.0000");

  private static final LocalDate FROM = LocalDate.of(2026, 8, 1);
  private static final LocalDate TO = LocalDate.of(2026, 8, 31);

  // ═══ ШЕВОВЕТЕ КЪМ БАЗАТА, ПОДМЕНЕНИ ══════════════════════════════════════

  /**
   * ⚠️ ПОДМЕНЕН Е САМО ЧЕТЕНЕТО НА ПАРАМЕТРИТЕ. Разклоняването, сметката и попълването на
   * заглавната сума текат производствено — инак тестът щеше да е свидетел на самия себе си.
   */
  private static final class Service extends BgPaySlipService {
    BigDecimal altTax = ALT_TAX;

    /**
     * ⚠️ ВТОРИЯТ ШЕВ КЪМ БАЗАТА, И ТОЙ СЕ ОТВОРИ ЕДВА С ТАЗИ ПРОМЯНА. Дотук {@code
     * insuredTypeCodeOf} четеше само трудовия договор, тоест за фиш на самоосигуряващо се лице
     * връщаше {@code null} и {@code insuredTypeFor} излизаше по празния код БЕЗ да пита базата.
     * Оттам и данъчната ставка излизаше вярна по СЛУЧАЙНОСТ. Сега кодът се намира — и питането е
     * истинско.
     */
    @Override
    protected BgInsuredType insuredTypeFor(String insuredTypeCode, LocalDate date) {
      if (insuredTypeCode == null || insuredTypeCode.trim().isEmpty()) {
        return null;
      }
      BgInsuredType type = new BgInsuredType();
      type.setCode(insuredTypeCode);
      type.setBgIsSeafarer(false); // нито един от четирите кода не е морско лице
      return type;
    }

    @Override
    protected BigDecimal payrollParameter(String code, LocalDate date) {
      if (PARAM_ALT_TAX_ART41A.equals(code)) {
        return altTax;
      }
      if (PARAM_INCOME_TAX_RATE.equals(code)) {
        return RATE_ART42;
      }
      if (PARAM_DISABILITY_RELIEF.equals(code)) {
        return BigDecimal.ZERO;
      }
      return null;
    }
  }

  /** Речникът с типове пера е единственият шев на генератора към базата. */
  private static final class Generator extends BgIncomeTaxService {
    @Override
    protected BgPaySlipLineType lineType(String code) {
      BgPaySlipLineType type = new BgPaySlipLineType();
      type.setCode(code);
      type.setName("Данък по чл. 41а ЗДДФЛ");
      return type;
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgAmount(amount);
      line.setBgSign(BgPaySlipResolver.DEBIT);
      // Група 7xx — данъкът. Свидетелят пита за нея през BgPaySlipResolver, а не по име на поле.
      line.setBgLineNumber(700);
      return line;
    }
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  private static BgPaySlip slipFor(String insuredTypeCode) {
    Employee employee = new Employee();
    employee.setId(41L);
    employee.setBirthDate(LocalDate.of(1985, 4, 12));
    employee.setBgIsSelfInsured(true);

    BgSelfInsuredRegistration reg = new BgSelfInsuredRegistration();
    reg.setId(41L);
    reg.setEmployee(employee);
    reg.setBgIsSelfInsured(true);
    reg.setBgInsuredTypeCode(insuredTypeCode);
    reg.setBgScopeYear(2026);

    Period period = new Period();
    period.setFromDate(FROM);
    period.setToDate(TO);

    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgSelfInsuredRegistration(reg);
    slip.setPeriod(period);
    slip.setBgPaySlipLineList(new ArrayList<>());

    // ⚠️ ИСТИНСКО ПЕРО, А НЕ ЗАКОВАНА ЗАГЛАВНА СУМА. recompute() пресмята основата ОТ ПЕРАТА и
    // презаписва всяко число, сложено на ръка — тоест фиш без пера щеше да даде основа нула и
    // сравнението „15,34 срещу 100,00“ щеше да е между два нула-та.
    BgPaySlipLine earning = new BgPaySlipLine();
    earning.setBgLineNumber(100);
    earning.setBgSign(BgPaySlipResolver.CREDIT);
    earning.setBgIsTaxable(true);
    earning.setBgAmount(new BigDecimal("1000.00"));
    slip.getBgPaySlipLineList().add(earning);
    return slip;
  }

  /**
   * Перата от група 7xx — данъкът.
   *
   * <p>⚠️ БРОЯТ СЕ ПО ГРУПА, А НЕ ЦЕЛИЯТ СПИСЪК. Фишът носи и перото за начислението, без което
   * данъчната основа е нула; „целият списък е празен“ би бил свидетел на фикстурата, не на
   * генератора.
   */
  private static List<BgPaySlipLine> taxLines(BgPaySlip slip) {
    List<BgPaySlipLine> out = new ArrayList<>();
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (BgPaySlipResolver.groupOf(line) == 7) {
        out.add(line);
      }
    }
    return out;
  }

  // ═══ ТВЪРДЕНИЯТА ══════════════════════════════════════════════════════════

  /**
   * ⚠️ ПРИЗНАКЪТ Е ВИДЪТ ОСИГУРЕН, И РАЗДЕЛЯ ТОЧНО ЕДИН КОД ОТ ОСТАНАЛИТЕ.
   *
   * <p>Проверени са и двете посоки: код 41 го носи, а код 12 — най-близкият му съсед, също
   * самоосигуряващо се лице със същия носител — НЕ го носи. Проверка само в едната посока би минала
   * и при правило „всеки самоосигуряващ се плаща алтернативен данък“, което е невярно.
   */
  @Test
  @DisplayName("ПРИЗНАК · алтернативният данък е за код 41, а НЕ за всеки самоосигуряващ се")
  void onlyCode41CarriesTheAlternativeTax() {
    Service service = new Service();
    assertTrue(service.hasAlternativeTax(slipFor("41")), "код 41 — чл. 4а1 КСО, чл. 41а ЗДДФЛ");
    assertFalse(
        service.hasAlternativeTax(slipFor("12")),
        "код 12 е самоосигуряващо се лице, но данъкът му е по общия ред — чл. 41а изброява ЕДНА"
            + " дейност: събиране на диворастящи гъби и плодове");
    assertFalse(
        service.hasAlternativeTax(slipFor("96")),
        "и код 96 не е сред тях, въпреки че и той е с фиксирана база — база и данък са различни"
            + " въпроси");
    assertTrue(
        BgSelfInsuredRules.ALT_TAX_CODES.contains("41"),
        "списъкът живее при нормата си, в BgSelfInsuredRules");
  }

  /**
   * ⚠️ ЗАГЛАВНАТА СУМА Е ЧИСЛОТО ОТ НОРМАТА, А НЕ ДЕСЕТ НА СТО ОТ ОСНОВАТА.
   *
   * <p>Основата е нарочно 1000,00 €: по чл. 42 ал. 4 тя би дала 100,00 €, тоест повече от шест пъти
   * дължимото. Съвпаднеха ли двете числа, тестът нямаше да доказва нищо.
   */
  @Test
  @DisplayName("ЗАГЛАВИЕ · recompute пише 15,34 €, а не 10 на сто от основата от 1000 €")
  void theHeaderCarriesTheFlatAmountAndNotThePercentage() {
    Service service = new Service();

    BgPaySlip picker = slipFor("41");
    service.recompute(picker);
    assertEquals(
        0,
        ALT_TAX.compareTo(picker.getBgTaxAmount()),
        "чл. 41а ал. 1: „в размер на 30 лв. за всеки месец“ — един фиш е един месец");

    BgPaySlip ordinary = slipFor("12");
    service.recompute(ordinary);
    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(ordinary.getBgTaxAmount()),
        "съседът по общия ред остава на чл. 42 ал. 4 — 10 на сто от основата");

    assertEquals(
        0,
        new BigDecimal("1000.00").compareTo(picker.getBgTaxBase()),
        "⚠️ ОСНОВАТА ОСТАВА СМЕТНАТА И ПРИ ДВАТА ДАНЪКА: тя е факт за дохода, а не за данъка."
            + " Изтрита, тя щеше да скрие, че фишът изобщо е носил облагаеми начисления");
  }

  /**
   * ⚠️ ПЕРОТО НОСИ СУМАТА БЕЗ ОСНОВА И БЕЗ СТАВКА — и това е самото твърдение.
   *
   * <p>При чл. 42 двете колони са документацията на числото („откъде излезе“). При чл. 41а сумата
   * няма произход от сметка — тя Е числото от нормата. Попълнени, те биха описали изчисление, което
   * никой не е правил, и биха поканили човек да ги умножи едно по друго.
   */
  @Test
  @DisplayName("ПЕРО · група 7xx с 15,34 €, БЕЗ основа и БЕЗ ставка")
  void theLineCarriesNoBaseAndNoRate() {
    BgPaySlip slip = slipFor("41");
    BgPaySlipLineOutcome outcome = new Generator().generateAlternative(slip, ALT_TAX).get(0);

    assertEquals(BgPaySlipLineOutcome.Status.COMPUTED, outcome.status, outcome.detail);
    assertEquals(1, taxLines(slip).size(), "точно едно перо в група 7xx — данъкът");

    BgPaySlipLine line = taxLines(slip).get(0);
    assertEquals(0, ALT_TAX.compareTo(line.getBgAmount()));
    assertEquals(BgPaySlipResolver.DEBIT, line.getBgSign(), "данъкът се УДЪРЖА, а не се добавя");
    // ⚠️ ПИТА СЕ signum(), А НЕ assertNull, И ПРИЧИНАТА Е В ГЕНЕРИРАНИЯ КОД. Getter-ът за {@code
    // BigDecimal} в Axelor връща ZERO за непопълнена колона, тоест {@code assertNull} тук НЕ МОЖЕ
    // да мине, колкото и вярно да е записаното. Същият капан, който BgDefaultedGetterTest пази
    // срещу null-проверки в продукционния код — тук се вижда от другата страна.
    assertEquals(0, line.getBgQuantity().signum(), "основа НЯМА — чл. 41а не пита за нея");
    assertEquals(0, line.getBgRate().signum(), "ставка НЯМА — числото е фиксирано, не процент");
  }

  /**
   * ⚠️ ЛИПСВАЩИЯТ ПАРАМЕТЪР Е ОТКАЗ, А НЕ НУЛА И НЕ „ОБЩИТЕ 10 НА СТО“.
   *
   * <p>Замести ли се с общата ставка, фишът щеше да изглежда напълно сметнат и да носи данък, който
   * това лице не дължи. Прочетен като нула — щеше да мине без данък изобщо. И двете са тихи;
   * отказът не е.
   */
  @Test
  @DisplayName("ОТКАЗ · незареден ALT_TAX_ART41A → перо НЯМА и причината се назовава")
  void aMissingParameterRefusesInsteadOfFallingBackToArt42() {
    BgPaySlip slip = slipFor("41");
    BgPaySlipLineOutcome outcome = new Generator().generateAlternative(slip, null).get(0);

    assertEquals(BgPaySlipLineOutcome.Status.REFUSED, outcome.status);
    assertTrue(outcome.detail.contains("ALT_TAX_ART41A"), outcome.detail);
    assertTrue(taxLines(slip).isEmpty(), "перо за данък НЕ се създава");

    Service service = new Service();
    service.altTax = null;
    BgPaySlip header = slipFor("41");
    service.recompute(header);
    assertEquals(
        0,
        header.getBgTaxAmount().signum(),
        "⚠️ И ЗАГЛАВНАТА СУМА НЕ ПАДА НА 10 НА СТО — тя остава без число, а не с ЧУЖДО число."
            + " (Записаното е null; getter-ът за BigDecimal го връща като ZERO — виж бележката при"
            + " перото.) ⚠️ И ТОВА Е ГРАНИЦАТА НА ТОЗИ ИЗХОД: нула срещу липсващо перо СЪВПАДА,"
            + " тоест refuseTaxMismatch мълчи. Видимият отказ е на ГЕНЕРАТОРА, горе — точно както"
            + " при липсваща ставка по чл. 42.");
  }

  /**
   * ⚠️ Д6 Т. 8.1 = 7 — ЕДИН КОД ЗА ДВЕТЕ ЗАДЪЛЖЕНИЯ, И САМО ЗА ТОЗИ ВИД ОСИГУРЕН.
   *
   * <p><b>ДОСЛОВНО, Наредба Н-13 Приложение № 4, указание т. 8 буква „в“:</b> „за дължимите
   * осигурителни вноски за лицата, които извършват дейност по събиране на диворастящи гъби и
   * плодове, И ДАНЪК ПО ЧЛ. 41А от Закона за данъците върху доходите на физическите лица, се
   * попълва код - 7.“
   *
   * <p><b>⚠️ И ДВЕТЕ ПОСОКИ, защото грешният съсед е правдоподобен.</b> Код 5 („изплатено,
   * начислено и неизплатено … възнаграждение за месеца“) е верният избор за всеки друг фиш и е
   * първият в списъка на екрана. Оставена празна, точката щеше да се попълни с него по навик — а
   * декларация с код 5 твърди възнаграждение, каквото тук няма.
   */
  @Test
  @DisplayName("Д6 · т. 8.1 = 7 за код 41, и НЕ се пипа за код 12")
  void theD6PaymentKindIsSevenForTheForestPicker() {
    BgD6Declaration picker = new BgD6Declaration();
    picker.setBgSelfInsuredRegistration(slipFor("41").getBgSelfInsuredRegistration());
    new BgD6Emitter().emit(picker, Collections.emptyList());
    assertEquals(
        BgD6Point.PAYMENT_KIND_ART41A_FOREST,
        picker.getT81PaymentKind(),
        "указание т. 8 буква „в“ — вноските И данъкът по чл. 41а под ЕДИН код");

    BgD6Declaration ordinary = new BgD6Declaration();
    ordinary.setBgSelfInsuredRegistration(slipFor("12").getBgSelfInsuredRegistration());
    new BgD6Emitter().emit(ordinary, Collections.emptyList());
    assertNull(
        ordinary.getT81PaymentKind(),
        "⚠️ За всеки друг вид осигурен точката ОСТАВА на човека: буква „а“ дава 5 или 6 според това"
            + " КОГА е начислено, а това е факт за плащането, не за вида осигурен");
  }

  /**
   * ⚠️ Д1 Т. 31 ОСТАВА ПРАЗНА, А Т. 31А — НЕ. Двете не са една точка с два вида съдържание.
   *
   * <p>Указанието за т. 31 изброява 30 кода и <b>41 НЕ е сред тях</b>, тоест облагаемият доход на
   * събирача не се декларира. Указанието за т. 31а обаче се казва „Начислен месечен данък/данък по
   * чл. 41а от ЗДДФЛ“ и изрично добавя „както и размерът на АЛТЕРНАТИВНИЯ данък за доходи от
   * събиране на диворастящи гъби и плодове по чл. 41а“ — тоест точката се пълни ВЪПРЕКИ празната т.
   * 31.
   *
   * <p>⚠️ Проверява се ФЛАГЪТ В ДАННИТЕ, а не списък в Java: {@code BgD1RecordBuilder} пита точно
   * него, а темпоралната номенклатура може да смени отговора си за минал период.
   */
  @Test
  @DisplayName("Д1 · код 41 НЕ попълва т. 31, а т. 31а носи алтернативния данък")
  void code41LeavesPoint31EmptyWhilePoint31aCarriesTheTax() {
    BgInsuredType picker = new BgInsuredType();
    picker.setCode("41");
    picker.setBgFillsT31(false); // както е в bg_insured_type.csv, ред IT-41-2026
    assertFalse(
        BgD1InsuredTypeRules.fillsT31(picker),
        "указанието за т. 31 изброява 30 кода и 41 не е сред тях");

    // ⚠️ А т. 31а е БЕЗУСЛОВНА: BgD1RecordBuilder я пълни от заглавната сума на фиша, каквато и да
    // е нормата зад нея. Тоест щом заглавната сума вече Е алтернативният данък (виж горния
    // свидетел), точката го носи без нито едно ново разклонение.
    Service service = new Service();
    BgPaySlip slip = slipFor("41");
    service.recompute(slip);
    assertEquals(0, ALT_TAX.compareTo(slip.getBgTaxAmount()), "т. 31а ще прочете точно това число");
  }
}
