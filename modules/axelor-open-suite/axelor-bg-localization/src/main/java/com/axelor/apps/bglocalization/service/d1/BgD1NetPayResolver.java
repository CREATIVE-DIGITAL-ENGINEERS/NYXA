package com.axelor.apps.bglocalization.service.d1;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Т. 34 — <b>МЕСЕЧНОТО НЕТНО ВЪЗНАГРАЖДЕНИЕ ПО НПОПДОО</b> (ПМС 188/2015), чл. 32.
 *
 * <p><b>ДОСЛОВНО, ЧЛ. 32 АЛ. 1</b> (ред. ДВ бр. 57 от 2018 г.): „За работещите по трудови и по
 * служебни правоотношения и приравнените към тях правоотношения месечното нетно възнаграждение се
 * определя, като изплатеното или начисленото месечно възнаграждение се намали с определените със
 * закон задължителни осигурителни вноски за сметка на лицата и с данъците по Закона за данъците
 * върху доходите на физическите лица и не може да бъде повече от максималния месечен осигурителен
 * доход, определен със Закона за бюджета на държавното обществено осигуряване за съответната
 * година, освен в случаите по чл. 6, ал. 16 КСО.“
 *
 * <p><b>ДОСЛОВНО, ЧЛ. 32 АЛ. 4:</b> „Изплатеното или начисленото месечно възнаграждение по ал. 1 –
 * 3, от което се определя нетното възнаграждение, включва брутното трудово възнаграждение и други
 * допълнителни доходи от трудова дейност за съответния месец.“
 *
 * <p><b>ДОСЛОВНО, ЧЛ. 32 АЛ. 5:</b> „При определяне на месечното нетно възнаграждение се вземат
 * предвид и средствата, предоставени от работодателя за сметка на социалните разходи, както и
 * определените върху тях със закон задължителни осигурителни вноски за сметка на лицата и дължимият
 * данък …“
 *
 * <p>Тоест формулата е ТРИЧЛЕННА и таванът е върху РЕЗУЛТАТА, не върху базата:
 *
 * <pre>т. 34 = min(възнаграждение − лични вноски − данък, максимален месечен осигурителен доход)
 * </pre>
 *
 * <p><b>⚠️ ЗАЩО БАЗАТА Е {@link BgPaySlipResolver#insuranceIncomeBase}, А НЕ НОВ АГРЕГАТ.</b> Ал. 4
 * описва „брутното трудово възнаграждение и други допълнителни доходи от трудова дейност“, а ал. 5
 * добавя социалните разходи. Това е дословно същото множество, което КСО чл. 6 ал. 2 нарича
 * осигурителен доход („всички възнаграждения … и други доходи от трудова дейност“) и което НЕВД чл.
 * 2 ал. 1 разширява със средствата за социални разходи. В модела то вече има ЕДИН носител — флагът
 * {@code BgPaySlipLine.bgIsInsuranceIncome} на речника с типове пера, сверен ред по ред. Втори
 * агрегат със свой филтър би бил второ мнение по един и същи въпрос и щеше да се разминава с
 * първото точно там, където речникът е бил редактиран.
 *
 * <p><b>⚠️ И ЗАЩО НЕ Е Т. 29 (БРЗ) И НЕ Е Т. 31 (ОБЛАГАЕМ ДОХОД).</b> Т. 29 е ПО-ТЯСНА от ал. 4:
 * НСОРЗ чл. 16 ал. 2 не включва нито болничния по чл. 40 ал. 5 КСО, нито социалните разходи, които
 * ал. 5 изрично иска. Т. 31, обратно, е ПО-ШИРОКА: тя брои всяко облагаемо плащане, включително
 * обезщетения по КТ, които НЕ са доход от трудова дейност (НЕВД чл. 1 ал. 8) — за фиш с обезщетение
 * по чл. 222 разликата между двете е цялото обезщетение и всяко от трите числа изглежда законно на
 * реда на т. 34.
 *
 * <p><b>⚠️ И НАЙ-ВАЖНОТО: НЕ Е {@code bgNetTotal}.</b> „Нето за получаване“ е СЛЕД заеми, издръжка
 * и запор. Нетното по НПОПДОО е база, от която НОИ смята паричните обезщетения — слети, лице със
 * запор би получило по-малко обезщетение за болест заради дълг към трето лице. Затова тази услуга
 * съществува отделно, вместо т. 34 да чете готовото поле на фиша.
 *
 * <p><b>Чиста функция, без база и без Guice</b> — по образеца на {@link BgD1DayBuckets}. Таванът е
 * ТЕМПОРАЛЕН параметър ({@code MAX_INSURABLE_INCOME}) и затова се ПОДАВА: заявката за него живее в
 * {@link BgD1Emitter}, който е единственият слой с достъп до базата.
 */
public final class BgD1NetPayResolver {

  /** Таванът не е прочетен или го няма за периода — т. 34 остава празна. */
  public static final String PROBLEM_CAP_MISSING = "CAP_MISSING";

  /**
   * Възнаграждение НЕ Е начислено — хипотезата на чл. 32 ал. 1, ИЗРЕЧЕНИЕ ВТОРО.
   *
   * <p>Дословно: „Когато възнаграждението не е начислено, до начисляването му за нетно
   * възнаграждение се взема предвид минималният месечен осигурителен доход по чл. 6, ал. 2, т. 3
   * КСО или минималната месечна работна заплата за страната, ако нямат определен минимален
   * осигурителен доход.“
   *
   * <p>⚠️ Изборът между ДВЕТЕ стойности зависи от това дали за длъжността ИМА определен минимален
   * осигурителен доход — въпрос, на който отговаря {@code BgMinIncomeResolver} и на който той може
   * и да не отговори (липсващ НКПД, ненамерен ред по Приложение № 1 към ЗБДОО). Тоест тук не се
   * избира: това е РЕШЕНИЕ с два правдоподобни изхода, а сгрешеният сяда на реда, от който НОИ
   * смята обезщетението. Точката остава празна и хипотезата се ОБЯВЯВА.
   */
  public static final String PROBLEM_NOT_ACCRUED = "NOT_ACCRUED";

  /** Удръжките надхвърлят възнаграждението — двата източника си противоречат. */
  public static final String PROBLEM_NEGATIVE = "NEGATIVE";

  private BgD1NetPayResolver() {}

  /** Какво излезе от сметката — числото и всяка от съставките му, за да е сверимо от човек. */
  public static final class Outcome {

    private final BigDecimal netPay;
    private final BigDecimal base;
    private final BigDecimal employeeContributions;
    private final BigDecimal tax;
    private final BigDecimal cap;
    private final boolean capped;
    private final String problem;

    private Outcome(
        BigDecimal netPay,
        BigDecimal base,
        BigDecimal employeeContributions,
        BigDecimal tax,
        BigDecimal cap,
        boolean capped,
        String problem) {
      this.netPay = netPay;
      this.base = base;
      this.employeeContributions = employeeContributions;
      this.tax = tax;
      this.cap = cap;
      this.capped = capped;
      this.problem = problem;
    }

    /**
     * Нетното възнаграждение, или {@code null}, когато НЕ е определено.
     *
     * <p>⚠️ {@code null}, а не нула, и точно тук е цялата разлика: „0,00“ е валидно число, което
     * НАП приема, а НОИ би сметнал обезщетение върху него.
     */
    public BigDecimal getNetPay() {
      return netPay;
    }

    /** Възнаграждението по ал. 4 и ал. 5 — базата, преди двете удръжки. */
    public BigDecimal getBase() {
      return base;
    }

    /** Задължителните осигурителни вноски за сметка на ЛИЦЕТО. */
    public BigDecimal getEmployeeContributions() {
      return employeeContributions;
    }

    /** Данъкът по ЗДДФЛ. */
    public BigDecimal getTax() {
      return tax;
    }

    /** Максималният месечен осигурителен доход за периода, или {@code null}, ако не е прочетен. */
    public BigDecimal getCap() {
      return cap;
    }

    /** Наложил ли се е таванът — тоест нетното е РЯЗАНО, а не сметнато. */
    public boolean isCapped() {
      return capped;
    }

    /** Причината точката да остане празна или числото да е спорно, или {@code null}. */
    public String getProblem() {
      return problem;
    }
  }

  /**
   * Нетното по чл. 32 за един фиш.
   *
   * @param slip фишът-източник; {@code null} дава празен изход с {@link #PROBLEM_NOT_ACCRUED}
   * @param cap максималният месечен осигурителен доход за периода на данните. ⚠️ {@code null} е
   *     ОТКАЗ, а не „без таван“: неограничено, нетното на високоплатено лице излиза над тавана и
   *     изглежда точно толкова пресметнато, колкото и вярното число
   */
  public static Outcome resolve(BgPaySlip slip, BigDecimal cap) {
    return resolve(
        slip == null ? null : slip.getBgPaySlipLineList(),
        slip == null ? BigDecimal.ZERO : slip.getBgTaxAmount(),
        cap);
  }

  /**
   * Същото върху ПОДАДЕНИ пера и данък — редът 01 на фиш, който носи и обезщетение по вид 27, вижда
   * само своите ({@link BgD1Facts#linesOf}): обезщетението не е възнаграждение по чл. 32.
   */
  public static Outcome resolve(List<BgPaySlipLine> lines, BigDecimal slipTax, BigDecimal cap) {

    BigDecimal base = BgPaySlipResolver.insuranceIncomeBase(lines);
    BigDecimal contributions = BgPaySlipResolver.employeeContributions(lines);

    // ⚠️ ЗАГЛАВНИЯТ ДАНЪК, А НЕ СБОРЪТ НА ПЕРАТА ОТ 7xx — и това не е непроверен прочит. Чл. 32 ал.
    // 1 стъпва на „изплатеното ИЛИ НАЧИСЛЕНОТО“, тоест на НАЧИСЛЕНОТО основание; заглавната сума е
    // начисленият данък по чл. 42 ал. 4 ЗДДФЛ и е същото число, което пълни т. 31а. Че тя не се
    // разминава с удържаното, пази BgPaySlipResolver#refuseTaxMismatch при потвърждаване на фиша —
    // а той сравнява точно срещу taxFromLines, филтриран по собствен период. Тоест заглавната сума
    // НЕ е нефилтрирано четене.
    BigDecimal tax = slipTax;
    if (tax == null) {
      tax = BigDecimal.ZERO;
    }

    // ═══ ЧЛ. 32 АЛ. 1, ИЗРЕЧЕНИЕ ВТОРО — възнаграждението НЕ Е начислено ═══
    // ⚠️ ПИТА СЕ ПЪРВО, преди тавана: без начислено възнаграждение формулата няма от какво да вади,
    // а нула минус вноските дава отрицателно число, тоест ДРУГА причина за същата празна точка.
    if (base.signum() == 0) {
      return new Outcome(null, base, contributions, tax, cap, false, PROBLEM_NOT_ACCRUED);
    }

    if (cap == null || cap.signum() <= 0) {
      return new Outcome(null, base, contributions, tax, cap, false, PROBLEM_CAP_MISSING);
    }

    BigDecimal net = base.subtract(contributions).subtract(tax).setScale(2, RoundingMode.HALF_UP);

    // ⚠️ ОТРИЦАТЕЛНОТО НЕТО Е НАХОДКА, А НЕ НУЛА. Базата брои доходите от трудова дейност, а
    // данъкът е върху ОБЛАГАЕМИЯ доход, който може да е по-широк (обезщетение по КТ е облагаемо и
    // не е доход от трудова дейност). Излезе ли отрицателно, двата източника си противоречат.
    // Изгладено до нула, противоречието изчезва, а точката твърди „нула нето“ за работил човек.
    if (net.signum() < 0) {
      return new Outcome(null, base, contributions, tax, cap, false, PROBLEM_NEGATIVE);
    }

    // ═══ ТАВАНЪТ Е ВЪРХУ НЕТОТО, НЕ ВЪРХУ БАЗАТА ═══
    // ⚠️ Подлогът на „не може да бъде повече от максималния месечен осигурителен доход“ в ал. 1 е
    // МЕСЕЧНОТО НЕТНО ВЪЗНАГРАЖДЕНИЕ, а не възнаграждението, от което се определя. Приложен върху
    // базата, таванът дава по-МАЛКО нето (защото вноските и данъкът се вадят след рязането) — при
    // база 3000, вноски 275,60, данък 176,42 и таван 2111,64 разликата е 1659,62 срещу 2111,64.
    if (net.compareTo(cap) > 0) {
      return new Outcome(
          cap.setScale(2, RoundingMode.HALF_UP), base, contributions, tax, cap, true, null);
    }
    return new Outcome(net, base, contributions, tax, cap, false, null);
  }

  /** Сметката, изписана с числата — за текста на находката и за екрана. */
  public static String describe(Outcome outcome) {
    if (outcome == null) {
      return "";
    }
    return "възнаграждение "
        + plain(outcome.getBase())
        + " − лични осигурителни вноски "
        + plain(outcome.getEmployeeContributions())
        + " − данък "
        + plain(outcome.getTax())
        + (outcome.getCap() == null ? "" : ", таван " + plain(outcome.getCap()))
        + (outcome.isCapped() ? " (наложен)" : "");
  }

  private static String plain(BigDecimal value) {
    return value == null ? "—" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
  }
}
