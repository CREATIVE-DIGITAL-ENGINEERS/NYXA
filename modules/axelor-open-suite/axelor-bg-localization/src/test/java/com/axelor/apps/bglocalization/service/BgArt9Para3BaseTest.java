package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * WS-3c · ОСИГУРИТЕЛНАТА БАЗА НА ЧЛ. 213, ЧЛ. 214 И ЧЛ. 225 КТ Е ПОСЛЕДНОТО БРУТНО, НЕ СУМАТА.
 *
 * <p><b>Какво заключава този клас.</b> Две неща, които днес се бъркат, защото се четат от един и
 * същи ред на фиша:
 *
 * <ol>
 *   <li>КОЛКО СЕ ПЛАЩА — брутното трудово възнаграждение за времето без работа;
 *   <li>ВЪРХУ КАКВО СЕ ОСИГУРЯВА — последното брутно, на месец от признатия период (КСО чл. 9 ал. 3
 *       т. 1 за чл. 213 и чл. 214, т. 2 за чл. 225 ал. 1).
 * </ol>
 *
 * <p>Всеки тест по-долу е построен така, че ДВЕТЕ ЧИСЛА ЯВНО СЕ РАЗЛИЧАВАТ — иначе твърдението би
 * минавало зелено и при сгрешена база. Точно това ги прави проверими: при чл. 217 и чл. 222 ал. 1
 * (т. 4 и т. 5 на същата алинея) двете числа СЪВПАДАТ по устройство и там няма какво да се мери.
 *
 * <p>Огледалната половина — че самата сума НЕ е осигурителен доход — е НЕВД чл. 1 ал. 8 т. 7, която
 * изброява чл. 200, 213, 214, … 225, 226, чл. 232 ал. 2 и чл. 331 КТ поименно.
 */
class BgArt9Para3BaseTest {

  private static final BigDecimal LAST_GROSS = new BigDecimal("2000.00");

  // ── 1 · кой вид по коя точка ────────────────────────────────────────────

  @Test
  @DisplayName(
      "1 · чл. 213 и чл. 214 са по т. 1, чл. 225 ал. 1 — по т. 2; чл. 217 и 222 ал. 1 — не")
  void eachCompensationCarriesItsOwnPointOfArticleNine() {
    assertEquals(BgArt9Para3Base.POINT_1, BgArt9Para3Base.pointFor("comp_kt_213_1"));
    assertEquals(BgArt9Para3Base.POINT_1, BgArt9Para3Base.pointFor("comp_kt_213_2"));
    assertEquals(BgArt9Para3Base.POINT_1, BgArt9Para3Base.pointFor("comp_kt_214"));
    assertEquals(BgArt9Para3Base.POINT_2, BgArt9Para3Base.pointFor("comp_kt_225_1"));

    // ⚠️ ТЕЗИ ДВАТА СА КОНТРАПРИМЕРЪТ, не пропуск. Т. 4 внася „върху ПОЛАГАЩОТО СЕ обезщетение“
    // (чл. 217), т. 5 — „върху ПОЛУЧАВАНОТО обезщетение“ (чл. 222 ал. 1). За тях базата Е сумата на
    // перото и днешното ѝ държане е вярно; вкарани в списъка, те щяха да получат друга база.
    assertNull(BgArt9Para3Base.pointFor("comp_kt_217"), "чл. 217 е по т. 4 — база е самата сума");
    assertNull(
        BgArt9Para3Base.pointFor("comp_kt_222_1"), "чл. 222 ал. 1 е по т. 5 — база е самата сума");
    assertNull(BgArt9Para3Base.pointFor("comp_zdsl_106_2"), "и чл. 106 ал. 2 ЗДСл е по т. 5");
    assertNull(BgArt9Para3Base.pointFor(null));

    assertTrue(BgArt9Para3Base.basedOnLastGross("comp_kt_225_1"));
    assertFalse(BgArt9Para3Base.basedOnLastGross("comp_kt_217"));
  }

  // ── 2 · базата е последното брутно, не изплатеното ──────────────────────

  @Test
  @DisplayName("⚠️ 2 · ГЕЙТ · базата е ПОСЛЕДНОТО БРУТНО × месеци, а не изплатената сума")
  void theBaseIsTheLastGrossAndNotTheAmountPaid() {
    // Чл. 225 ал. 1 КТ, три месеца без работа, последно брутно 2000,00.
    BigDecimal months = new BigDecimal("3");

    // Дължимото по закон и изплатеното СЪВПАДАТ, докато никой не е договорил повече:
    BigDecimal statutory =
        BgCompensationResolver.compute(BgCompensationResolver.BTV_MONTHS, months, LAST_GROSS, null);
    assertEquals(new BigDecimal("6000.00"), statutory);

    // ⚠️ НО ЧЛ. 228 АЛ. 2 КТ ДОПУСКА ПО-ГОЛЯМ РАЗМЕР: „Размерите на обезщетенията по чл. 215, 218,
    // 222 и 225 се прилагат, доколкото в акт на Министерския съвет, в колективен трудов договор или
    // в трудовия договор не са предвидени ПО-ГОЛЕМИ размери.“ КТД с 3000,00 на месец плаща 9000,00.
    BigDecimal paidUnderCollectiveAgreement = new BigDecimal("9000.00");

    // Осигурителната база обаче НЕ помръдва: КСО чл. 9 ал. 3 т. 2 казва „върху последното брутно
    // ВЪЗНАГРАЖДЕНИЕ“, а не „върху обезщетението“. Тоест 6000,00, не 9000,00.
    BigDecimal base = BgArt9Para3Base.periodBase(LAST_GROSS, months, null);
    assertEquals(new BigDecimal("6000.00"), base);
    assertNotEquals(
        0,
        paidUnderCollectiveAgreement.compareTo(base),
        "⚠️ ако тези две числа някога съвпаднат, тестът вече не мери нищо — примерът е нарочно с"
            + " КТД над законовия размер");

    // и на месец
    assertEquals(new BigDecimal("2000.00"), BgArt9Para3Base.monthlyBase(LAST_GROSS, null));
  }

  // ── 3 · втората хипотеза на нормата: разликата ──────────────────────────

  @Test
  @DisplayName(
      "3 · било ли е лицето осигурявано — базата е РАЗЛИКАТА, и то само ако доходът е по-малък")
  void whenTheEmployeeWasInsuredElsewhereOnlyTheDifferenceIsDue() {
    // ЦИТАТ: „ако лицето е било осигурявано, осигурителните вноски се внасят върху РАЗЛИКАТА между
    // последното брутно възнаграждение и осигурителния доход за периода, АКО ТОЗИ ДОХОД Е
    // ПО-МАЛЪК“.
    assertEquals(
        new BigDecimal("800.00"),
        BgArt9Para3Base.monthlyBase(LAST_GROSS, new BigDecimal("1200.00")),
        "2000,00 − 1200,00");

    // Равен доход не изпълнява условието „ако този доход е по-малък“ — нищо не се дължи.
    assertEquals(
        new BigDecimal("0.00"),
        BgArt9Para3Base.monthlyBase(LAST_GROSS, LAST_GROSS),
        "равен доход → законна нула, не отрицателно число");
    assertEquals(
        new BigDecimal("0.00"),
        BgArt9Para3Base.monthlyBase(LAST_GROSS, new BigDecimal("2500.00")),
        "по-голям доход → пак нула, а не −500,00");

    // ⚠️ И ТУК Е НАЙ-ЯВНАТА РАЗЛИКА С ДНЕШНОТО ПОВЕДЕНИЕ: изплащат се 6000,00, а се осигурява върху
    // 2400,00.
    BigDecimal paid = new BigDecimal("6000.00");
    BigDecimal base =
        BgArt9Para3Base.periodBase(LAST_GROSS, new BigDecimal("3"), new BigDecimal("1200.00"));
    assertEquals(new BigDecimal("2400.00"), base);
    assertNotEquals(0, paid.compareTo(base), "две различни числа за едни и същи три месеца");
  }

  // ── 4 · null значи „не може да се определи“, не нула ────────────────────

  @Test
  @DisplayName("4 · неизвестното последно брутно и липсващият период дават null, а не нула")
  void unknownInputsProduceNullAndNotZero() {
    assertNull(BgArt9Para3Base.monthlyBase(null, null), "без последно брутно няма база");
    assertNull(BgArt9Para3Base.periodBase(LAST_GROSS, null, null), "без месеци няма период");
    assertNull(BgArt9Para3Base.periodBase(LAST_GROSS, BigDecimal.ZERO, null), "нула месеца също");

    // ⚠️ Разликата с точка 3: там нулата Е отговор на нормата („този доход не е по-малък“), тук
    // липсата е липса. Слети, непопълнена база би минала за месец без задължение.
    assertNotNull(BgArt9Para3Base.monthlyBase(LAST_GROSS, LAST_GROSS));
  }

  // ── 5 · пазачът на фиша ─────────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ 5 · ГЕЙТ · фиш с обезщетение по чл. 225 НЕ се потвърждава — базата му е чужда")
  void aPaySlipCarryingSuchACompensationIsRefused() {
    Service s = new Service();
    // ⚠️ КТД НАД ЗАКОНОВИЯ РАЗМЕР (чл. 228 ал. 2 КТ): три месеца без работа при последно брутно
    // 2000,00 дават законови 6000,00, а договорените 3000,00 на месец — 9000,00 изплатени. Точно
    // това разминаване прави твърдението проверимо: при равни числа тестът минава и със сгрешена
    // база.
    BgPaySlip slip = slipWithCompensation("comp_kt_225_1", "чл. 225 ал. 1 КТ", "9000.00", "3");

    // ⚠️ ТОВА Е ДИАГНОЗАТА, ЗАКОВАНА: перото носи bgIsInsuranceIncome = true (речникът, ред 907),
    // тоест цялата изплатена сума днес Е осигурителната база на фиша.
    assertEquals(
        new BigDecimal("9000.00"),
        BgPaySlipResolver.insuranceIncomeBase(slip.getBgPaySlipLineList()),
        "днес базата е СУМАТА — и точно това е дефектът");
    assertNotEquals(
        0,
        BgPaySlipResolver.insuranceIncomeBase(slip.getBgPaySlipLineList())
            .compareTo(BgArt9Para3Base.periodBase(LAST_GROSS, new BigDecimal("3"), null)),
        "⚠️ примерът е нарочно с КТД над законовия размер, за да са двете числа различни");

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(e.getMessage().contains("КСО чл. 9 ал. 3 т. 2"), e.getMessage());
    assertTrue(e.getMessage().contains("ПОСЛЕДНОТО БРУТНО"), e.getMessage());
    assertTrue(e.getMessage().contains("НЕВД чл. 1 ал. 8 т. 7"), e.getMessage());
    assertTrue(e.getMessage().contains("вид осигурен 18"), e.getMessage());
    assertTrue(e.getMessage().contains("OBEZ-2026-1"), "казва КОЙ документ: " + e.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect(), "остава чернова");
  }

  @Test
  @DisplayName("5а · и трите по т. 1 се отказват със СВОЯТА точка, не с тази на чл. 225")
  void theThreeUnderPointOneNameTheirOwnPoint() {
    for (String[] each :
        new String[][] {
          {"comp_kt_213_1", "чл. 213 ал. 1 КТ"},
          {"comp_kt_213_2", "чл. 213 ал. 2 КТ"},
          {"comp_kt_214", "чл. 214 КТ"}
        }) {
      Service s = new Service();
      BgPaySlip slip = slipWithCompensation(each[0], each[1], "6000.00", "3");
      AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip), each[0]);
      assertTrue(e.getMessage().contains("КСО чл. 9 ал. 3 т. 1"), each[0] + ": " + e.getMessage());
      assertTrue(e.getMessage().contains(each[1]), each[0] + ": " + e.getMessage());
    }
  }

  @Test
  @DisplayName("5б · чл. 222 ал. 1 и чл. 217 минават — базата им Е самата сума")
  void theCompensationsWhoseBaseIsTheAmountItselfAreNotTouched() throws Exception {
    for (String[] each :
        new String[][] {{"comp_kt_222_1", "чл. 222 ал. 1 КТ"}, {"comp_kt_217", "чл. 217 КТ"}}) {
      Service s = new Service();
      BgPaySlip slip = slipWithCompensation(each[0], each[1], "2000.00", "1");
      assertNull(s.art9Para3Refusal(slip), each[0] + " не бива да се докосва");
      s.calculate(slip);
      s.confirm(slip);
      assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect(), each[0]);
    }
  }

  @Test
  @DisplayName("5в · КОРИГИРАЩИЯТ фиш се пропуска — иначе грешката остава заключена в месеца си")
  void theRetroSlipIsSkippedSoAConfirmedErrorCanStillBeCorrected() {
    Service s = new Service();
    BgPaySlip retro = slipWithCompensation("comp_kt_225_1", "чл. 225 ал. 1 КТ", "6000.00", "3");
    retro.setBgIsCorrection(Boolean.TRUE);
    assertNull(
        s.art9Para3Refusal(retro),
        "корекцията е ЕДИНСТВЕНИЯТ изход за вече потвърден фиш със завишена вноска");

    BgPaySlip deletion = slipWithCompensation("comp_kt_225_1", "чл. 225 ал. 1 КТ", "6000.00", "3");
    deletion.setBgIsDeletion(Boolean.TRUE);
    assertNull(s.art9Para3Refusal(deletion), "и заличаването също");
  }

  @Test
  @DisplayName("⚠️ 5г · WS-5 · без въведен период отказът КАЗВА, че базата не може да се сметне")
  void withoutTheRecognisedPeriodTheRefusalSaysSoInsteadOfInventingANumber() {
    // ⚠️ ЧЛ. 213 И ЧЛ. 214 СА BTV_ACTUAL_PERIOD: каталогът им няма baseMonths, аритметика за тях
    // няма (BgCompensationResolver#compute връща null) и сумата се въвежда на ръка. Тоест броят
    // месеци от признатия период НЕ СЪЩЕСТВУВА като поле — нито изведен, нито въведен. Отказът
    // затова назовава липсата, вместо да умножи по подразбрано число.
    Service s = new Service();
    BgPaySlip slip = slipWithCompensation("comp_kt_214", "чл. 214 КТ", "6000.00", "0");

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(
        e.getMessage().contains("НЕ може да се сметне от този документ"),
        "⚠️ не бива да излиза число: " + e.getMessage());
    assertTrue(
        e.getMessage().contains("липсват и признатият период по месеци"),
        "казва точно кой вход липсва: " + e.getMessage());
    assertNull(
        BgArt9Para3Base.periodBase(LAST_GROSS, BigDecimal.ZERO, null),
        "и правилото отдолу също не подразбира нищо");
  }

  // ── фикстури ────────────────────────────────────────────────────────────

  private static BgPaySlip slipWithCompensation(
      String code, String legalBasis, String amount, String months) {

    BgPaySlip s = new BgPaySlip();
    s.setStatusSelect(BgPaySlipResolver.DRAFT);
    Year year = new Year();
    year.setName("Заплатна 2026");
    year.setTypeSelect(2);
    Period p = new Period();
    p.setName("03/PY2026");
    p.setFromDate(LocalDate.of(2026, 3, 1));
    p.setToDate(LocalDate.of(2026, 3, 31));
    p.setYear(year);
    s.setPeriod(p);
    s.setBgPaySlipLineList(new ArrayList<>());

    BgCompensationType type = new BgCompensationType();
    type.setCode(code);
    type.setName(legalBasis);
    type.setLegalBasis(legalBasis);

    BgCompensation doc = new BgCompensation();
    doc.setReference("OBEZ-2026-1");
    doc.setBgCompensationType(type);
    doc.setBgBaseMonthGross(LAST_GROSS);
    doc.setBgQuantity(new BigDecimal(months));
    doc.setBgFinalAmount(new BigDecimal(amount));

    // Перото, както генераторът го прави: речникът казва „осигурителен доход“ и то влиза в базата.
    BgPaySlipLineType lineType = new BgPaySlipLineType();
    lineType.setNumber(907);
    lineType.setName(legalBasis);
    lineType.setBgSign(BgPaySlipResolver.CREDIT);
    lineType.setBgIsTaxable(Boolean.TRUE);
    lineType.setBgIsInsuranceIncome(Boolean.TRUE);
    lineType.setBgIsPermanentCharacter(Boolean.FALSE);

    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgPaySlipLineType(lineType);
    line.setBgLineNumber(lineType.getNumber());
    line.setName(lineType.getName());
    line.setBgSign(lineType.getBgSign());
    line.setBgIsTaxable(lineType.getBgIsTaxable());
    line.setBgIsInsuranceIncome(lineType.getBgIsInsuranceIncome());
    line.setBgAmount(new BigDecimal(amount));
    line.setBgCompensation(doc);
    line.setPaySlip(s);
    s.getBgPaySlipLineList().add(line);
    return s;
  }

  /** Фишът без база и без Guice — същите шевове, които {@code BgPaySlipGuardsTest} подменя. */
  private static class Service extends BgPaySlipService {

    @Override
    protected void generateEarnings(BgPaySlip slip) {}

    @Override
    protected void generateSickLeave(BgPaySlip slip) {}

    /**
     * ⚠️ ЗАМРАЗЯВАНЕТО НА ДНИТЕ ОТПУСК — заглушено по същия довод като съседите: то пита базата за
     * заявките за отпуск през {@code payrollInput}. Декларация обр. 1 ги чете оттам вместо да ги
     * смята наново; този свидетел не мери нито едното.
     */
    @Override
    protected void freezeLeaveDays(BgPaySlip slip) {}

    @Override
    protected void generateCompensations(BgPaySlip slip) {}

    @Override
    protected void generateContributions(BgPaySlip slip) {}

    @Override
    protected void generateIncomeTax(BgPaySlip slip) {}

    // ⚠️ Двете стъпки на prepare(), които calculate() също пуска.
    @Override
    protected void generateArt177Leave(BgPaySlip slip) {}

    @Override
    protected void generateAnnualSettlement(BgPaySlip slip) {}

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return new BgWorkingDayService.Count(21, Collections.emptyList());
    }

    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      return BgContributionResult.resolved(Collections.emptyList());
    }

    @Override
    protected BgMinIncomeResult minIncome(BgPaySlip slip) {
      return BgMinIncomeResult.notApplicable("неутрално за този клас");
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return BigDecimal.ZERO;
    }

    @Override
    protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
      return null;
    }

    @Override
    protected void save(BgPaySlip slip) {}

    @Override
    protected void saveCompensation(BgCompensation doc) {}
  }
}
