package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
import com.axelor.apps.bglocalization.service.d1.BgD1Facts;
import com.axelor.apps.bglocalization.service.d1.BgD1Point;
import com.axelor.apps.bglocalization.service.d1.BgD1RecordBuilder;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Т. 29 НА ДЕКЛАРАЦИЯ ОБР. 1 — ФЛАГЪТ, КОЙТО НИКОЙ НЕ ПРЕНАСЯШЕ.
 *
 * <p><b>Нормата.</b> Наредба Н-13, Приложение № 1, указание т. 29, ДОСЛОВНО: „<i>Брутно трудово
 * възнаграждение — попълва се сумата на брутното трудово възнаграждение, определено съгласно
 * Кодекса на труда, Наредбата за структурата и организацията на работната заплата … колективен
 * трудов договор и други нормативни актове, определящи вида и размерите на работните заплати и
 * допълнителните трудови възнаграждения.</i>“ И забележката към т. 29/30, ДОСЛОВНО: „<i>Точки 29 и
 * 30 се попълват за лицата, работещи по трудови правоотношения.</i>“
 *
 * <p><b>Кое перо влиза.</b> НСОРЗ чл. 16 ал. 2 изброява седем пера — основната работна заплата,
 * възнаграждението над нея, допълнителните трудови възнаграждения, чл. 259, чл. 266 ал. 1, чл. 267
 * ал. 1 и 3, чл. 268 ал. 2 и 3 КТ и възнаграждението за платен годишен отпуск. Отговорът перо по
 * перо вече живее в речника: {@code BgPaySlipLineType.bgFormsGrossWage} е {@code true} за 16 от
 * 43-те реда на {@code bg_pay_slip_line_type.csv} и {@code false} за останалите — включително за
 * перо 195 (болничен от осигурител по чл. 40 ал. 5 КСО) и перо 175 (компенсация по чл. 138а КТ),
 * които са в група 1xx и НЕ са работна заплата.
 *
 * <p><b>⚠️ И ТОЧНО ТОЗИ ОТГОВОР НЕ СТИГАШЕ ДО ПЕРОТО.</b> {@code BgPaySlipService#lineFrom} е
 * ЕДИНСТВЕНОТО място, където речникът се чете и стойностите му се замразяват върху реда. То
 * копираше шест неща и пропускаше седмото — {@code bgFormsGrossWage}. Домейнът на {@code
 * BgPaySlipLine} описва полето като „КОПИЕ от {@code BgPaySlipLineType.bgFormsGrossWage}“, тоест
 * намерението е ЗАПИСАНО; липсваше самото копиране. Следствието: генерираният {@code boolean}
 * getter връща подразбирането {@code false}, {@link BgPaySlipResolver#grossWageTotal} не намира
 * нито едно перо, и т. 29 излизаше ПРАЗНА за ВСЕКИ — включително за наетия по трудово
 * правоотношение, комуто се ДЪЛЖИ.
 *
 * <p><b>⚠️ ЗАЩО НЕ СЕ ВИЖДА.</b> Празната т. 29 е валидно число за мнозинството кодове (заб. към т.
 * 29/30 я иска празна за всички извън трудово правоотношение), а валидаторът вдига находка само
 * когато точката е ПОПЪЛНЕНА за нетрудово правоотношение. Тоест дефектът произвежда точно
 * състоянието, което нито едно правило не проверява — и декларацията минава зелена без числото,
 * което НОИ ползва.
 */
class BgGrossWageFlagTest {

  private static final LocalDate MARCH_2026 = LocalDate.of(2026, 3, 1);

  /**
   * Видовете осигурен, за които речникът на вноските дава ред за фонд ГВРС — тоест ТРУДОВИТЕ
   * правоотношения, както ги знае системата. Код 12 (самоосигуряващ се) НЕ е сред тях.
   */
  private static final Set<String> LABOUR_CODES =
      new LinkedHashSet<>(Arrays.asList("01", "02", "03", "04", "16"));

  /** Услугата няма състояние за тези два метода; {@code copyOf} е {@code protected}. */
  private static class Service extends BgPaySlipService {
    BgPaySlipLine copy(BgPaySlipLine line) {
      return copyOf(line);
    }
  }

  // ═══ строители ═══

  private static Period march() {
    Period p = new Period();
    p.setFromDate(MARCH_2026);
    p.setToDate(LocalDate.of(2026, 3, 31));
    return p;
  }

  /**
   * Ред от речника с типове пера, както App Install го зарежда.
   *
   * <p>{@code formsGrossWage} е ПАРАМЕТЪР, а не константа: свидетелят трябва да различи „копира се“
   * от „вдига се на true“, а това се вижда само когато и двете стойности минат по пътя.
   */
  private static BgPaySlipLineType type(
      int number, String name, String sign, boolean taxable, boolean formsGrossWage) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(taxable);
    t.setBgIsInsuranceIncome(true);
    t.setBgIsPermanentCharacter(false);
    t.setBgFormsGrossWage(formsGrossWage);
    return t;
  }

  private static BgInsuredType insuredType(String code) {
    BgInsuredType t = new BgInsuredType();
    t.setCode(code);
    t.setBgAppliesMod(Boolean.TRUE);
    return t;
  }

  private static EmploymentContract contract() {
    Partner companyPartner = new Partner();
    companyPartner.setName("Никса ЕООД");
    companyPartner.setRegistrationCode("831919536");

    Employee employee = new Employee();
    employee.setBgPersonalIdNumber("7501014567");
    employee.setBgPersonalIdType("1");

    EmploymentContract c = new EmploymentContract();
    c.setStartDate(LocalDate.of(2020, 1, 15));
    c.setBgInsuredTypeCode("01");
    c.setEmployee(employee);
    return c;
  }

  /**
   * Фишът на НАЕТОТО ЛИЦЕ, минат ПРЕЗ {@code lineFrom} — тоест точно както го прави производството.
   *
   * <p><b>⚠️ ЧЕТИРИ ПЕРА, И ВСЯКО ОТПАДА ПО СВОЯ ПРИЧИНА.</b> Това е, което прави числото
   * различимо:
   *
   * <ul>
   *   <li>100 основно 1800,00 и 110 клас 200,00 — <b>влизат</b> (чл. 16 ал. 2 т. 1 и т. 3);
   *   <li>195 болничен от осигурител 150,00 — CREDIT и облагаемо, но НЕ е работна заплата ({@code
   *       bgFormsGrossWage = false});
   *   <li>700 данък 172,44 — отпада по ПОСОКА (DEBIT).
   * </ul>
   *
   * Тоест т. 29 = <b>2000,00</b>, докато сборът на всички CREDIT пера е <b>2150,00</b>. Свидетел с
   * едно перо не би различил двете и щеше да мине зелен, ако флагът се игнорира.
   */
  private static BgPaySlip employeeSlip(Service s) {
    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-2026-03-0001");
    slip.setBgPaySlipLineList(new ArrayList<>());
    slip.setEmploymentContract(contract());
    slip.setPeriod(march());

    s.addLine(
        slip, s.lineFrom(type(100, "Основно възнаграждение", "CREDIT", true, true), bd("1800.00")));
    s.addLine(
        slip,
        s.lineFrom(
            type(110, "Допълнително възнаграждение за трудов стаж", "CREDIT", true, true),
            bd("200.00")));
    s.addLine(
        slip,
        s.lineFrom(
            type(195, "Болничен от осигурител (чл. 40 ал. 5 КСО)", "CREDIT", false, false),
            bd("150.00")));
    s.addLine(
        slip, s.lineFrom(type(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT", false, false), bd("172.44")));
    return slip;
  }

  private static BigDecimal bd(String v) {
    return new BigDecimal(v);
  }

  private static BgD1Record build(BgPaySlip slip, BgInsuredType type) {
    return BgD1RecordBuilder.build(slip, type, march(), BgD1Facts.none(), new ArrayList<>());
  }

  // ═══ 1 · ДУПКАТА: входът ═════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ lineFrom · перото ЗАПАЗВА bgFormsGrossWage от речника — и true, и false")
  void lineFromCarriesTheGrossWageFlagBothWays() {
    Service s = new Service();

    BgPaySlipLine wage =
        s.lineFrom(type(100, "Основно възнаграждение", "CREDIT", true, true), bd("1800.00"));
    BgPaySlipLine sick =
        s.lineFrom(type(195, "Болничен от осигурител", "CREDIT", false, false), bd("150.00"));

    assertTrue(
        Boolean.TRUE.equals(wage.getBgFormsGrossWage()),
        "⚠️ Пропуснат тук, флагът не се появява никъде другаде по ТОЗИ път и генерираният"
            + " getter връща подразбирането false — оттам т. 29 е празна. ⚠️ НО lineFrom НЕ Е"
            + " ЕДИНСТВЕНОТО МЯСТО: BgPaySlipController#lineFromType вика тъкмо него, но към"
            + " формата може да върне само част от стойностите. Гаранцията за ръчно добавеното от"
            + " екрана перо е в BgPaySlipManagementRepository#fillTreatmentFromType — виж"
            + " свидетелите му.");
    assertTrue(
        Boolean.FALSE.equals(sick.getBgFormsGrossWage()),
        "⚠️ И ОБРАТНАТА ПОСОКА, за да не мине фиксът „вдигни го винаги“. Перо 195 е болничният за"
            + " сметка на осигурителя по чл. 40 ал. 5 КСО — то е в група 1xx, CREDIT е и е"
            + " начислено, но НЕ е работна заплата по НСОРЗ чл. 16 ал. 2. Вдигнат тук, флагът щеше"
            + " да завиши т. 29 със 150,00 — правдоподобно число на верния ред.");
  }

  @Test
  @DisplayName("⚠️ copyOf · ретро копието носи и ВЪВЕДЕНАТА сума, не само платената")
  void theRetroCopyKeepsTheEnteredAmount() {
    Service s = new Service();
    BgPaySlipLine original =
        s.lineFrom(type(180, "Постоянен бонус", "CREDIT", true, true), bd("77.27"));
    original.setBgEnteredAmount(bd("100.00"));

    BgPaySlipLine copy = s.copy(original);

    assertEquals(
        bd("100.00"),
        copy.getBgEnteredAmount(),
        "⚠️ ДВЕТЕ ЧИСЛА НА ЕДИН РЕД СА РАЗЛИЧНИ ВЪПРОСИ и копието трябва да носи и двата: „Сума“ е"
            + " платеното този месец (77,27 = 100 × 17/22), „Въведена“ е уговореният месечен размер."
            + " Пропусната, въведената щеше да е празна на копието, а"
            + " BgEarningsService#enteredAmountOf чете празното като „улови го от bgAmount“ — тоест"
            + " вече КАРВИРАНАТА сума щеше да мине за пълна месечна и да се карвира втори път."
            + " Днес ретро-фишът не минава през генератора (generate излиза на bgIsCorrection),"
            + " тоест това не се задейства — но полето е ИЗТОЧНИК, а източник, който копието губи,"
            + " е дефект, чакащ първия, който махне онзи изход.");
    assertEquals(bd("77.27"), copy.getBgAmount(), "и платеното остава каквото е било");
  }

  @Test
  @DisplayName("⚠️ copyOf · ретро копието пази флага — инак корекцията противоречи на оригинала")
  void theRetroCopyKeepsTheFlag() {
    Service s = new Service();
    BgPaySlipLine original =
        s.lineFrom(type(100, "Основно възнаграждение", "CREDIT", true, true), bd("1800.00"));

    BgPaySlipLine copy = s.copy(original);

    assertTrue(
        Boolean.TRUE.equals(copy.getBgFormsGrossWage()),
        "⚠️ ВТОРОТО МЯСТО, КЪДЕТО СЕ РАЖДА ПЕРО, и то с обявено правило: copyOf носи бележката"
            + " „копието трябва да е това, което оригиналът е БИЛ“ — точно затова то НЕ пита"
            + " речника наново. Пропуснат и тук, флагът прави коригиращата Д1 за същия месец да"
            + " заяви т. 29 = 0,00 там, където оригиналната е заявила 2000,00. Двете декларации"
            + " са за ЕДИН и същи месец и НАП вижда разминаването, не ние.");
  }

  // ═══ 2 · СВИДЕТЕЛЯТ ПО СТОЙНОСТ: наетият по трудово правоотношение ═══════

  @Test
  @DisplayName("т. 29 · наето лице (код 01) → 2000,00, а не празно и не 2150,00")
  void theEmployeeGetsTheGrossWageInPoint29() {
    Service s = new Service();
    BgPaySlip slip = employeeSlip(s);

    assertEquals(
        0,
        bd("2000.00").compareTo(BgPaySlipResolver.grossWageTotal(slip.getBgPaySlipLineList())),
        "агрегатът по НСОРЗ чл. 16 ал. 2: 1800,00 основна (т. 1) + 200,00 клас (т. 3).");

    BgD1Record record = build(slip, insuredType("01"));

    assertEquals(
        0,
        bd("2000.00").compareTo(record.getT29GrossWage()),
        "Наредба Н-13, указание т. 29 — „сумата на брутното трудово възнаграждение“, а заб. към"
            + " т. 29/30 я дължи за лицата по ТРУДОВИ правоотношения. Код 01 е точно такова.\n"
            + "  0,00 е състоянието ПРЕДИ фикса — флагът не стигаше до перото;\n"
            + "  2150,00 би било „всичко CREDIT“, тоест с болничния по чл. 40 ал. 5 вътре;\n"
            + "  1800,00 би било само основната, без класа по чл. 16 ал. 2 т. 3.");
  }

  // ═══ 3 · РЕГРЕСИЯ-ПАЗАЧ: неТПО остава празна ════════════════════════════

  /**
   * Фиш на САМООСИГУРЯВАЩО СЕ ЛИЦЕ — само вноски, нито едно начисление.
   *
   * <p>⚠️ И това НЕ е удобна фикстура, а самият път: {@code BgPaySlipService#prepare} излиза при
   * {@code isSelfInsured} и вика САМО {@code generateContributions} — енджинът за начисления никога
   * не тръгва за СОЛ. Отделно, речникът на вноските ({@code
   * bg_pay_slip_line_type_contribution.csv}) изобщо НЯМА колона {@code bgFormsGrossWage}, тоест
   * двайсетте му реда четат подразбирането false.
   */
  private static BgPaySlip selfInsuredSlip() {
    BgSelfInsuredRegistration reg = new BgSelfInsuredRegistration();
    reg.setBgIsSelfInsured(Boolean.TRUE);
    reg.setBgInsuredTypeCode("12");
    reg.setBgObligedPersonCode("831919536");
    reg.setBgSelectedIncome(bd("1000.00"));

    Employee employee = new Employee();
    employee.setBgPersonalIdNumber("7501015678");
    employee.setBgPersonalIdType("1");
    employee.setBgIsSelfInsured(Boolean.TRUE);
    reg.setEmployee(employee);

    BgContributionFund pension = new BgContributionFund();
    pension.setCode("PENSION");
    pension.setBgD1Field(BgD1Point.T22_DOO);

    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(501);
    line.setBgSign(BgPaySlipResolver.DEBIT);
    line.setBgAmount(bd("148.00"));
    line.setBgQuantity(bd("1000.00"));
    line.setBgRate(bd("14.80"));
    line.setBgContributionFund(pension);
    line.setBgIsInsuranceIncome(Boolean.FALSE);
    line.setBgIsTaxable(Boolean.FALSE);

    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(line);

    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-SOL-2026-03-0007");
    slip.setBgPaySlipLineList(lines);
    slip.setBgSelfInsuredRegistration(reg);
    slip.setPeriod(march());
    return slip;
  }

  @Test
  @DisplayName("т. 29 · САМООСИГУРЯВАЩ СЕ (код 12) остава ПРАЗНА — фиксът не пропуква заб. 29/30")
  void theSelfInsuredKeepsPoint29Empty() {
    BgD1Record record = build(selfInsuredSlip(), insuredType("12"));

    assertEquals(
        0,
        record.getT29GrossWage().signum(),
        "заб. към т. 29/30: точките се попълват за лицата по ТРУДОВИ правоотношения. КСО чл. 6"
            + " ал. 8 прави вноските на самоосигуряващия се „за сметка на осигурените лица“ —"
            + " работодател няма, работна заплата няма, т. 29 няма.\n"
            + "  ⚠️ И ПРАЗНОТО ТУК Е СТРУКТУРНО, не случайно: prepare() излиза при isSelfInsured и"
            + " вика само generateContributions, а речникът на вноските изобщо няма колона"
            + " bgFormsGrossWage. Тоест числото не може да се появи дори когато lineFrom вече"
            + " пренася флага — точно това прави този свидетел пазач, а не повторение.");

    // ⚠️ signum(), НЕ assertNull: генерираният decimal getter връща ZERO за непопълнена колона,
    // тоест „не е писано“ и „нула“ са неразличими на модела. Свидетел с assertNull пада винаги.
    assertEquals(
        0,
        BgPaySlipResolver.grossWageTotal(selfInsuredSlip().getBgPaySlipLineList()).signum(),
        "и на нивото на агрегата: нито едно перо на СОЛ фиша не носи bgFormsGrossWage.");
  }

  @Test
  @DisplayName("т. 29 · и ГЕЙТЪТ държи: перо с вдигнат флаг при неТПО пак не пълни точката")
  void theLabourRelationGateStillHoldsWithAWageLinePresent() {
    Service s = new Service();
    BgPaySlip slip = selfInsuredSlip();
    // ⚠️ НЕВЪЗМОЖЕН ФИШ, И ТОВА Е ЦЕЛТА. Производството не може да го произведе — prepare() не
    // вика енджина за начисления при isSelfInsured. Свидетелят го сглобява НАРОЧНО, за да пита
    // втория въпрос: ако входът някога стигне дотук, ще го спре ли НОРМАТА. Проверен само с
    // истинския СОЛ фиш, пазачът щеше да е зелен и когато гейтът е счупен — защото там няма какво
    // да се спира.
    s.addLine(
        slip, s.lineFrom(type(100, "Основно възнаграждение", "CREDIT", true, true), bd("1800.00")));

    assertEquals(
        0,
        bd("1800.00").compareTo(BgPaySlipResolver.grossWageTotal(slip.getBgPaySlipLineList())),
        "входът Е налице — перото носи флага след фикса; тоест ако т. 29 остане празна, това е"
            + " решение на нормата, а не липса на данни.");

    BgD1Record record =
        BgD1RecordBuilder.build(
            slip,
            insuredType("12"),
            march(),
            new BgD1Facts(null, null, null, null, LABOUR_CODES),
            new ArrayList<>());

    assertEquals(
        0,
        record.getT29GrossWage().signum(),
        "заб. към т. 29/30: точките се попълват за лицата по ТРУДОВИ правоотношения. Код 12 не е"
            + " такова — речникът на вноските не му дава ред за фонд ГВРС, — и строителят пита"
            + " точно това, ПРЕДИ да напише числото.\n"
            + "  ⚠️ 1800,00 тук значи, че фиксът на входа е пропукал гейта: точката се пълни от"
            + " флага на перото и никой не пита за вида на правоотношението.");
  }
}
