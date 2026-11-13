package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЯДРОТО НА СПРАВКАТА ПО ЧЛ. 73 АЛ. 6 — доходът и данъкът по служител и по код.
 *
 * <p>Чист тест: без база и без Guice, по образеца на {@code BgD6AggregatorTest}. Свидетелят е
 * дванайсет месеца на едно лице; трите числа на образеца се сверяват срещу СБОРА на перата, а не
 * срещу заглавните суми на фиша.
 */
class BgArt73Para6AggregatorTest {

  /** Заплата 1500,00 лв. на месец, дванайсет месеца. */
  private static final BigDecimal MONTHLY_GROSS = new BigDecimal("1500.00");

  /** Лични осигуровки 13,78 на сто от 1500,00 = 206,70 лв. */
  private static final BigDecimal MONTHLY_CONTRIBUTIONS = new BigDecimal("206.70");

  /** Данък 10 на сто върху (1500,00 − 206,70) = 129,33 лв. */
  private static final BigDecimal MONTHLY_TAX = new BigDecimal("129.33");

  /** Вид осигурен 01 — работници и служители по чл. 4 ал. 1 т. 1 КСО. */
  private static final String NAETI = "01";

  /** Вид осигурен 10 — управители по договор за управление и контрол. */
  private static final String DUK = "10";

  /** Вид осигурен 92 — морски лица. */
  private static final String MORSKO = "92";

  /** Вид осигурен 14 — работещи БЕЗ трудови правоотношения, тоест гражданският договор. */
  private static final String GD = "14";

  /** Възнаграждение по граждански договор — 2000,00 лв. на фиш, два фиша за годината. */
  private static final BigDecimal GD_GROSS = new BigDecimal("2000.00");

  /** Вноски за сметка на лицето върху намалената по КСО чл. 6 ал. 4 база (1500,00) — 206,70 лв. */
  private static final BigDecimal GD_CONTRIBUTIONS = new BigDecimal("206.70");

  /** Авансов данък по чл. 43 ал. 1: (2000,00 − 500,00 − 206,70) × 10 на сто = 129,33 лв. */
  private static final BigDecimal GD_TAX = new BigDecimal("129.33");

  @Test
  @DisplayName(
      "СВИДЕТЕЛ · дванайсет месеца по 1500 лв. дават к.3 = 18000, к.4 = 2480,40, к.7 = 1551,96")
  void twelveMonthsOfOneEmployeeFillTheThreeColumns() {
    List<BgPaySlip> year = new ArrayList<>();
    for (int month = 1; month <= 12; month++) {
      year.add(
          slip(BgPaySlipResolver.CONFIRMED, MONTHLY_GROSS, MONTHLY_CONTRIBUTIONS, MONTHLY_TAX));
    }

    Map<String, BgArt73Para6Line> byCode = BgArt73Para6Aggregator.byCode(year, s -> false);

    assertEquals(1, byCode.size(), "едно лице без морски доход дава един ред — код 101");
    BgArt73Para6Line line = byCode.get(BgArt73Para6Aggregator.CODE_EMPLOYMENT);
    assertEquals(0, new BigDecimal("18000.00").compareTo(line.getIncome()), "колона 3: " + line);
    assertEquals(0, new BigDecimal("2480.40").compareTo(line.getInsurance()), "колона 4: " + line);
    assertEquals(0, new BigDecimal("1551.96").compareTo(line.getAdvanceTax()), "колона 7: " + line);
  }

  /**
   * ⚠️ МОРСКОТО ЛИЦЕ Е СВОЙ КОД, А НЕ СВОЯ СТАВКА В КОД 101. § 1 т. 54 от ДР го определя, а
   * образецът му дава ред 103 със СОБСТВЕН агрегат — ред 8.2 на справката смята годишната му основа
   * по чл. 25 ал. 3 (10 на сто от определената по ал. 1), докато ред 8.1 брои само код 101. Слети в
   * един код, двата дохода биха влезли в една годишна основа и морското лице би платило
   * десетократно.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · морското лице отива в код 103, а не в 101")
  void aSeafarerGetsCodeOneHundredAndThree() {
    List<BgPaySlip> year =
        Arrays.asList(
            slip(
                BgPaySlipResolver.CONFIRMED,
                MONTHLY_GROSS,
                MONTHLY_CONTRIBUTIONS,
                new BigDecimal("12.93")));

    Map<String, BgArt73Para6Line> byCode = BgArt73Para6Aggregator.byCode(year, s -> true);

    assertTrue(
        byCode.containsKey(BgArt73Para6Aggregator.CODE_SEAFARER),
        "очакван ред с код 103: " + byCode);
    assertFalse(
        byCode.containsKey(BgArt73Para6Aggregator.CODE_EMPLOYMENT),
        "морският доход НЕ бива да стои и под код 101: " + byCode);
    assertEquals(
        0,
        new BigDecimal("1500.00")
            .compareTo(byCode.get(BgArt73Para6Aggregator.CODE_SEAFARER).getIncome()));
  }

  /**
   * ⚠️ ДУК-договорът Е КОД 101, А НЕ 809. Описанието на код 101 в образеца изрично включва
   * „правоотношения по договори за управление и контрол, включително с членовете на управителни и
   * контролни органи на предприятия, ИЗВЪН СЛУЧАИТЕ ПО ЧЛ. 37 АЛ. 1 Т. 9“ — тоест местното лице по
   * ДУК е трудово правоотношение по § 1 т. 26 б. „з“, а код 809 остава за чуждестранното лице с
   * окончателен данък. Тук това се вижда като липса на разлика: ДУК-фишът не носи признак за морско
   * лице и минава по същия път като всеки друг трудов фиш.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · ДУК-договорът се събира под код 101 като всяко трудово правоотношение")
  void aManagementContractIsAlsoCodeOneHundredAndOne() {
    BgPaySlip duk =
        slip(
            BgPaySlipResolver.CONFIRMED,
            new BigDecimal("3000.00"),
            new BigDecimal("413.40"),
            new BigDecimal("258.66"));
    assertEquals(
        BgArt73Para6Aggregator.CODE_EMPLOYMENT, BgArt73Para6Aggregator.codeOf(duk, s -> false));
  }

  /**
   * ⚠️ ПИТАТ СЕ ДВЕ СЪСТОЯНИЯ, А НЕ СЕ ИЗКЛЮЧВА ЕДНО. Написано като „не е отменен“, правилото би
   * вкарало ЧЕРНОВИТЕ — а черновата е точно доходът, който още не е изплатен и още може да се
   * промени. Указание т. 2 иска информация за ИЗПЛАТЕНИТЕ през годината доходи.
   */
  @Test
  @DisplayName("СЪСТОЯНИЕ · черновата и отмененият фиш не влизат, потвърденият и платеният влизат")
  void onlyConfirmedAndPaidSlipsAreReported() {
    List<BgPaySlip> mixed =
        Arrays.asList(
            slip(BgPaySlipResolver.DRAFT, MONTHLY_GROSS, MONTHLY_CONTRIBUTIONS, MONTHLY_TAX),
            slip(BgPaySlipResolver.CANCELLED, MONTHLY_GROSS, MONTHLY_CONTRIBUTIONS, MONTHLY_TAX),
            slip(BgPaySlipResolver.CONFIRMED, MONTHLY_GROSS, MONTHLY_CONTRIBUTIONS, MONTHLY_TAX),
            slip(BgPaySlipResolver.PAID, MONTHLY_GROSS, MONTHLY_CONTRIBUTIONS, MONTHLY_TAX));

    BgArt73Para6Line line =
        BgArt73Para6Aggregator.byCode(mixed, s -> false)
            .get(BgArt73Para6Aggregator.CODE_EMPLOYMENT);

    assertEquals(
        0,
        new BigDecimal("3000.00").compareTo(line.getIncome()),
        "броят се ДВА фиша — потвърденият и платеният: " + line);
  }

  /**
   * ⚠️ ЛИПСВАЩ КОД ≠ НУЛЕВ ДОХОД. Лице без нито един отчетен фиш НЕ получава ред с нули: ред с код
   * 101 и доход 0,00 твърди пред НАП, че на лицето е изплатен нулев облагаем доход по трудово
   * правоотношение — твърдение, различно от „това лице няма такъв доход“.
   */
  @Test
  @DisplayName("ПРАЗНО · без отчетни фишове няма ред, а не ред с нула")
  void noSlipsMeansNoRowAtAll() {
    assertTrue(BgArt73Para6Aggregator.byCode(Collections.emptyList(), s -> false).isEmpty());
    assertTrue(BgArt73Para6Aggregator.byCode(null, s -> false).isEmpty());
    assertTrue(
        BgArt73Para6Aggregator.byCode(
                Arrays.asList(
                    slip(
                        BgPaySlipResolver.DRAFT,
                        MONTHLY_GROSS,
                        MONTHLY_CONTRIBUTIONS,
                        MONTHLY_TAX)),
                s -> false)
            .isEmpty());
  }

  /**
   * ⚠️ КОЛОНА 7 Е УДЪРЖАНОТО, А НЕ ИЗЧИСЛЕНОТО. Заглавното {@code bgTaxAmount} е единствената сума
   * на фиша, която не е сбор на пера, тоест единствената, която може да им противоречи. Указание т.
   * 7 иска „удържаният авансово данък“. Тук фишът носи разминаване нарочно: заглавната сума е
   * 999,99, а перото — 129,33. Справката трябва да покаже перото.
   */
  @Test
  @DisplayName("⚠️ КОЛОНА 7 · чете се от перото 7xx, а не от заглавната сума на фиша")
  void columnSevenComesFromTheLinesNotTheHeader() {
    BgPaySlip slip =
        slip(BgPaySlipResolver.CONFIRMED, MONTHLY_GROSS, MONTHLY_CONTRIBUTIONS, MONTHLY_TAX);
    slip.setBgTaxAmount(new BigDecimal("999.99"));

    BgArt73Para6Line line =
        BgArt73Para6Aggregator.byCode(Arrays.asList(slip), s -> false)
            .get(BgArt73Para6Aggregator.CODE_EMPLOYMENT);

    assertEquals(0, MONTHLY_TAX.compareTo(line.getAdvanceTax()), "колона 7: " + line);
  }

  /**
   * ⚠️ РЕТРО-ДЕЛТАТА НЕ ВЛИЗА В СУМИТЕ НА ФИША, КОЙТО Я НОСИ. И трите прочита минават през {@code
   * isOwnPeriod}: делта-редът е за ДРУГ месец и вече е отчетен при него. Броен и тук, същият доход
   * би влязъл в справката два пъти.
   */
  @Test
  @DisplayName("РЕТРО · делта-редът за друг период не се брои втори път")
  void aRetroDeltaLineIsNotCountedTwice() {
    BgPaySlip slip =
        slip(BgPaySlipResolver.CONFIRMED, MONTHLY_GROSS, MONTHLY_CONTRIBUTIONS, MONTHLY_TAX);
    BgPaySlipLine delta = line(110, "CREDIT", true, new BigDecimal("400.00"));
    // ⚠️ Маркерът е СТАНДАРТНИЯТ Period, а не булево: BgPaySlipResolver#isOwnPeriod пита
    // getBgCorrectsPeriod() == null. Вдигнат като флаг, той нямаше да спре нищо.
    delta.setBgCorrectsPeriod(new Period());
    slip.getBgPaySlipLineList().add(delta);

    BgArt73Para6Line line =
        BgArt73Para6Aggregator.byCode(Arrays.asList(slip), s -> false)
            .get(BgArt73Para6Aggregator.CODE_EMPLOYMENT);

    assertEquals(
        0,
        MONTHLY_GROSS.compareTo(line.getIncome()),
        "делтата за друг месец не се брои тук: " + line);
  }

  // ═══ ГРАНИЦА 4 · ГРАЖДАНСКИЯТ ДОГОВОР НЕ Е В ТОЗИ ОБРАЗЕЦ ══════════════════

  /**
   * ⚠️ ЕДИН ДОХОД — ЕДИН ОБРАЗЕЦ. Фишът по граждански договор поражда ред в регистъра {@code
   * BgIndividualIncomePayment} и оттам влиза в справката по чл. 73 ал. 1. Броен И тук, същият доход
   * се декларира ДВА пъти пред НАП — веднъж като трудов по ал. 6 и веднъж като извънтрудов по ал.
   * 1.
   *
   * <p>Числата са целта на този свидетел: дванайсет наети фиша по 1500,00 дават 18000,00, а двата
   * ГД фиша по 2000,00 НЕ ги вдигат до 22000,00. Същото за колони 4 и 7 — 2480,40 вместо 2893,80 и
   * 1551,96 вместо 1810,62.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · наети (01) + ГД (14) → к.3 остава 18000,00, а не 22000,00")
  void aCivilContractDoesNotInflateTheEmploymentRow() {
    List<BgPaySlip> year = new ArrayList<>();
    for (int month = 1; month <= 12; month++) {
      year.add(
          slip(
              BgPaySlipResolver.CONFIRMED,
              MONTHLY_GROSS,
              MONTHLY_CONTRIBUTIONS,
              MONTHLY_TAX,
              NAETI));
    }
    year.add(slip(BgPaySlipResolver.CONFIRMED, GD_GROSS, GD_CONTRIBUTIONS, GD_TAX, GD));
    year.add(slip(BgPaySlipResolver.PAID, GD_GROSS, GD_CONTRIBUTIONS, GD_TAX, GD));

    Map<String, BgArt73Para6Line> byCode = BgArt73Para6Aggregator.byCode(year, s -> false);

    assertEquals(1, byCode.size(), "ГД не бива да добавя нито свой ред, нито свой код: " + byCode);
    BgArt73Para6Line line = byCode.get(BgArt73Para6Aggregator.CODE_EMPLOYMENT);
    assertEquals(
        0,
        new BigDecimal("18000.00").compareTo(line.getIncome()),
        "колона 3 — 4000,00 по граждански договор НЕ влизат тук: " + line);
    assertEquals(
        0,
        new BigDecimal("2480.40").compareTo(line.getInsurance()),
        "колона 4 — 413,40 по граждански договор НЕ влизат тук: " + line);
    assertEquals(
        0,
        new BigDecimal("1551.96").compareTo(line.getAdvanceTax()),
        "колона 7 — 258,66 по граждански договор НЕ влизат тук: " + line);
  }

  /**
   * ⚠️ ПРАЗНА КАРТА, А НЕ РЕД С НУЛА. Лице само с граждански договори няма ЗАЩО да се появи в
   * справката по ал. 6: ред с код 101 и 0,00 би твърдял пред НАП, че на лицето е изплатен нулев
   * доход по ТРУДОВО правоотношение — а то няма такова. Доходът му от 4000,00 лв. се декларира по
   * ал. 1, и то е единственото му място.
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · само ГД фишове → справката по ал. 6 е ПРАЗНА за това лице")
  void aPersonWithOnlyCivilContractsGetsNoRowAtAll() {
    List<BgPaySlip> year =
        Arrays.asList(
            slip(BgPaySlipResolver.CONFIRMED, GD_GROSS, GD_CONTRIBUTIONS, GD_TAX, GD),
            slip(BgPaySlipResolver.PAID, GD_GROSS, GD_CONTRIBUTIONS, GD_TAX, GD));

    Map<String, BgArt73Para6Line> byCode = BgArt73Para6Aggregator.byCode(year, s -> false);

    assertTrue(byCode.isEmpty(), "очаква се нула реда, а не ред с нула: " + byCode);
  }

  /**
   * ⚠️ ОБРАТНАТА ПОЛОВИНА НА СВИДЕТЕЛЯ — без нея правило „изключвай всичко“ би минавало и трите
   * теста горе. Трите вида осигурен, които ОСТАВАТ, влизат с пълните си числа:
   *
   * <ul>
   *   <li>наети (01) → код 101, 1500,00;
   *   <li>ДУК (10) → пак код 101 по § 1 т. 26 б. „з“ — „правоотношенията по договори за управление
   *       и контрол“, — 3000,00;
   *   <li>морско лице (92) → код 103 по § 1 т. 54, 1500,00, с отделен агрегат.
   * </ul>
   */
  @Test
  @DisplayName("СВИДЕТЕЛ · наети (01) и ДУК (10) остават в 101 с 4500,00, морското (92) — в 103")
  void employmentAndManagementAndSeafarerAllStay() {
    BgPaySlip naet =
        slip(BgPaySlipResolver.CONFIRMED, MONTHLY_GROSS, MONTHLY_CONTRIBUTIONS, MONTHLY_TAX, NAETI);
    BgPaySlip duk =
        slip(
            BgPaySlipResolver.CONFIRMED,
            new BigDecimal("3000.00"),
            new BigDecimal("413.40"),
            new BigDecimal("258.66"),
            DUK);
    BgPaySlip morsko =
        slip(
            BgPaySlipResolver.CONFIRMED,
            MONTHLY_GROSS,
            MONTHLY_CONTRIBUTIONS,
            new BigDecimal("12.93"),
            MORSKO);

    Map<String, BgArt73Para6Line> byCode =
        BgArt73Para6Aggregator.byCode(
            Arrays.asList(naet, duk, morsko), s -> MORSKO.equals(insuredTypeOf(s)));

    assertEquals(2, byCode.size(), "очакват се точно два реда — 101 и 103: " + byCode);
    BgArt73Para6Line employment = byCode.get(BgArt73Para6Aggregator.CODE_EMPLOYMENT);
    assertEquals(
        0,
        new BigDecimal("4500.00").compareTo(employment.getIncome()),
        "наетият и ДУК-ът се събират под 101: " + employment);
    assertEquals(
        0,
        new BigDecimal("1500.00")
            .compareTo(byCode.get(BgArt73Para6Aggregator.CODE_SEAFARER).getIncome()),
        "морското лице остава в 103: " + byCode);
  }

  /**
   * ⚠️ ГЕЙТЪТ ПИТА ДОГОВОРА, А НЕ ПОГАЖДА. Фиш без договор (самоосигуряващо се лице) НЕ е
   * граждански договор — той е извън справката по друг път и по друга причина; празен вид осигурен
   * също не е. Стойност с интервали остава „14“, защото правилото минава през {@code
   * BgCivilContractResolver#isCivilContract}, който трима — второ, преписано определение би я
   * пропуснало.
   */
  @Test
  @DisplayName("ГЕЙТ · само вид осигурен 14 е граждански договор — празно, 01 и липсващ договор не")
  void onlyCodeFourteenIsACivilContract() {
    assertFalse(BgArt73Para6Aggregator.isCivilContractSlip(null), "null фиш");
    assertFalse(
        BgArt73Para6Aggregator.isCivilContractSlip(
            slip(BgPaySlipResolver.CONFIRMED, MONTHLY_GROSS, MONTHLY_CONTRIBUTIONS, MONTHLY_TAX)),
        "фиш без трудов договор — самоосигуряващо се лице");
    assertFalse(
        BgArt73Para6Aggregator.isCivilContractSlip(
            slip(
                BgPaySlipResolver.CONFIRMED,
                MONTHLY_GROSS,
                MONTHLY_CONTRIBUTIONS,
                MONTHLY_TAX,
                null)),
        "договор без попълнен вид осигурен");
    assertFalse(
        BgArt73Para6Aggregator.isCivilContractSlip(
            slip(
                BgPaySlipResolver.CONFIRMED,
                MONTHLY_GROSS,
                MONTHLY_CONTRIBUTIONS,
                MONTHLY_TAX,
                DUK)),
        "ДУК-ът е трудово правоотношение по § 1 т. 26 б. „з“");
    assertTrue(
        BgArt73Para6Aggregator.isCivilContractSlip(
            slip(BgPaySlipResolver.CONFIRMED, GD_GROSS, GD_CONTRIBUTIONS, GD_TAX, " 14 ")),
        "стойност с интервали остава код 14 — правилото трима");
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  /**
   * ⚠️ ФИШ С ДОГОВОР, НОСЕЩ ВИД ОСИГУРЕН — точно каквото гейтът чете. Подаден {@code null} код,
   * договорът СЪЩЕСТВУВА, но полето е празно: {@code bgInsuredTypeCode} не е {@code required} и
   * това е реалното състояние на заварен договор.
   */
  private static BgPaySlip slip(
      String status,
      BigDecimal gross,
      BigDecimal contributions,
      BigDecimal tax,
      String insuredTypeCode) {
    BgPaySlip slip = slip(status, gross, contributions, tax);
    EmploymentContract contract = new EmploymentContract();
    contract.setBgInsuredTypeCode(insuredTypeCode);
    slip.setEmploymentContract(contract);
    return slip;
  }

  /** Видът осигурен на фиша — колкото стига на предиката за морско лице в тестовете. */
  private static String insuredTypeOf(BgPaySlip slip) {
    return slip == null || slip.getEmploymentContract() == null
        ? null
        : slip.getEmploymentContract().getBgInsuredTypeCode();
  }

  private static BgPaySlip slip(
      String status, BigDecimal gross, BigDecimal contributions, BigDecimal tax) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(status);
    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(line(110, "CREDIT", true, gross));
    lines.add(line(510, "DEBIT", false, contributions));
    lines.add(line(700, "DEBIT", false, tax));
    slip.setBgPaySlipLineList(lines);
    return slip;
  }

  private static BgPaySlipLine line(int number, String sign, boolean taxable, BigDecimal amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setBgSign(sign);
    line.setBgIsTaxable(taxable);
    line.setBgAmount(amount);
    return line;
  }
}
