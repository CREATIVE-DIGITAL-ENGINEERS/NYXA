package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgAbsenceType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГЕНЕРАТОРЪТ — перото и дните върху истински фиш: идемпотентност, адитивност и границите му.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ, НЕ ПРАВИЛАТА.</b> Това е разликата между тест, който доказва
 * нещо, и тест, който проверява собствените си фикстури. {@link BgSickLeaveResolver} се вика
 * ИСТИНСКИ — дните и сумите тук са негови, не измислени. Подменени са само {@code absenceType}
 * (речникът с отсъствия), {@code holidaysIn} (производственият календар), {@code healthAbsenceBase}
 * и {@code healthEmployerRatePct} (иначе искат Postgres), {@code lineType} и {@code lineFrom}
 * (същото копиране, което {@code BgPaySlipService} прави). Оттам тестът тече без база, без Guice и
 * без Hibernate.
 *
 * <p>Всеки свидетел е чупен преднамерено, преди да остане зелен.
 */
class BgSickLeaveServiceTest {

  private static final LocalDate FROM = LocalDate.of(2026, 7, 1);
  private static final LocalDate TO = LocalDate.of(2026, 7, 31);
  private static final int WORKING_DAYS = 23;
  private static final BigDecimal SALARY = new BigDecimal("1805.00");
  private static final BigDecimal HEALTH_BASE = new BigDecimal("550.6600");
  private static final BigDecimal HEALTH_RATE = new BigDecimal("4.8000");

  // ═══ РЕЧНИКЪТ С ОТСЪСТВИЯ, КАКТО Е В bg_absence_type.csv ═════════════════

  private static BgAbsenceType absence(String code, String payer, String pct) {
    BgAbsenceType t = new BgAbsenceType();
    t.setCode(code);
    t.setPayer(payer);
    t.setCompensationPct(new BigDecimal(pct));
    t.setRequiresSickNote(true); // A4: вид на болничен лист, като в сийда
    t.setCountsAsInsuranceService(true);
    return t;
  }

  /** Четирите вида, които правилата различават — с платците и процентите от seed-а. */
  private static Map<String, BgAbsenceType> absenceDictionary() {
    Map<String, BgAbsenceType> out = new LinkedHashMap<>();
    out.put("sick_er", absence("sick_er", "employer", "70"));
    out.put("work_accident_er", absence("work_accident_er", "employer", "70"));
    out.put("maternity_birth", absence("maternity_birth", "doo", "90"));
    out.put("sick_doo", absence("sick_doo", "doo", "80"));
    return out;
  }

  // ═══ УСЛУГАТА С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  private static final class Service extends BgSickLeaveServiceOnDeclaredStage {

    /**
     * Базата на 195 (чл. 40 ал. 5 КСО) — постоянното месечно възнаграждение. ⚠️ Фикстурата няма
     * клас, степен и постоянни пера, тоест то Е основната заплата; самото {@code M} се мери в
     * {@code BgPaySlipRetroGeneratorsTest#theSickPayBaseCarriesThePermanentAdditions}.
     */
    @Override
    protected java.math.BigDecimal monthlyPermanentPay(
        com.axelor.apps.bglocalization.db.BgPaySlip slip,
        com.axelor.apps.bglocalization.service.payroll.BgPayrollInput input) {
      return slip.getBgBaseSalary();
    }

    BigDecimal healthBase = HEALTH_BASE;
    BigDecimal healthRate = HEALTH_RATE;
    Set<LocalDate> holidays = Collections.emptySet();

    /**
     * Речникът с отсъствия — ПОЛЕ, а не пресъздаван на всяко четене.
     *
     * <p>⚠️ Разликата не е стилова. Пресъздаван, речникът прави свидетеля „процентът идва от
     * речника“ НЕВЪЗМОЖЕН: сменената стойност се губи още при следващото повикване и тестът мери
     * фикстурата си, вместо посоката на данните.
     */
    Map<String, BgAbsenceType> dictionary = absenceDictionary();

    /** Речникът с типове пера — само редът, който този енджин пише. {@code null} = липсва. */
    BgPaySlipLineType sickPayType = lineTypeRow();

    @Override
    protected BgAbsenceType absenceType(String code) {
      return dictionary.get(code);
    }

    @Override
    protected Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
      return holidays;
    }

    /** Базата, ставката и сумата, с които шевът е извикан — {@code null}, ако не е. */
    BigDecimal writtenBase;

    BigDecimal writtenRate;
    BigDecimal writtenAmount;

    /**
     * ⚠️ ШЕВЪТ СЕ ЗАГЛУШАВА И СЕ ЗАПИСВА, а не просто се заглушава.
     *
     * <p>Заглушен само, той би направил свидетеля „вноската се смята“ зелен и за код, който НЕ я
     * записва — тоест точно границата, която този етап затваря, щеше да остане невидима. Записан,
     * той дава на теста как да провери, че перото се пише И с кои три числа.
     */
    @Override
    protected void writeSpecialHealthBase(
        com.axelor.apps.bglocalization.db.BgPaySlip slip,
        BigDecimal base,
        BigDecimal ratePercent,
        BigDecimal amount) {
      this.writtenBase = base;
      this.writtenRate = ratePercent;
      this.writtenAmount = amount;
    }

    @Override
    protected BigDecimal healthAbsenceBase(LocalDate date) {
      return healthBase;
    }

    @Override
    protected BigDecimal healthEmployerRatePct(BgPayrollInput input, LocalDate date) {
      return healthRate;
    }

    @Override
    protected BgPaySlipLineType lineType(String code) {
      return BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY.equals(code) ? sickPayType : null;
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      // Същото КОПИРАНЕ, което BgPaySlipService прави — речникът се чете веднъж, при създаване.
      BgPaySlipLine line = new BgPaySlipLine();
      line.setBgPaySlipLineType(type);
      line.setBgLineNumber(type.getNumber());
      line.setName(type.getName());
      line.setBgSign(type.getBgSign());
      line.setBgIsTaxable(type.getBgIsTaxable());
      line.setBgIsInsuranceIncome(type.getBgIsInsuranceIncome());
      line.setBgIsPermanentCharacter(type.getBgIsPermanentCharacter());
      line.setBgAmount(amount);
      return line;
    }
  }

  /** Редът на речника — с флаговете от bg_pay_slip_line_type.csv, тоест ТЕ са правилото. */
  private static BgPaySlipLineType lineTypeRow() {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setCode(BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY);
    t.setNumber(195);
    t.setName("Болничен от осигурител (чл. 40 ал. 5 КСО)");
    t.setBgSign("CREDIT");
    t.setBgIsTaxable(false);
    t.setBgIsInsuranceIncome(true);
    t.setBgIsPermanentCharacter(false);
    t.setBgCalculationBase("DERIVED");
    return t;
  }

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  private static Period july() {
    Period p = new Period();
    p.setName("07/PY2026");
    p.setFromDate(FROM);
    p.setToDate(TO);
    return p;
  }

  private static BgPaySlip slip() {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setPeriod(july());
    slip.setBgBaseSalary(SALARY);
    slip.setBgWorkingDaysInPeriod(WORKING_DAYS);
    slip.setBgWorkedDays(WORKING_DAYS);
    slip.setBgPaySlipLineList(new ArrayList<>());
    return slip;
  }

  private static BgPayrollInput.SickLeaveEpisode ep(
      String from, String to, boolean continuation, String code) {
    return new BgPayrollInput.SickLeaveEpisode(
        LocalDate.parse(from), LocalDate.parse(to), continuation, "БЛ-1", code);
  }

  private static BgPayrollInput input(BgPayrollInput.SickLeaveEpisode... episodes) {
    BgPayrollInput.Builder b =
        BgPayrollInput.builder()
            .insuredTypeCode("01")
            .laborCategory("3")
            .birthDate(LocalDate.of(1985, 4, 12))
            .insuranceServiceMonths(120)
            .periodFrom(FROM)
            .periodTo(TO)
            .parameterReferenceDate(FROM)
            .workingDaysInPeriod(WORKING_DAYS)
            .baseSalary(SALARY);
    for (BgPayrollInput.SickLeaveEpisode e : episodes) {
      b.sickLeaveEpisode(e);
    }
    return b.build();
  }

  private static BgPaySlipLine sickLine(BgPaySlip slip) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      BgPaySlipLineType type = line.getBgPaySlipLineType();
      if (type != null && BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY.equals(type.getCode())) {
        return line;
      }
    }
    return null;
  }

  private static BgSickLeaveOutcome outcomeFor(List<BgSickLeaveOutcome> outcomes, boolean sickPay) {
    // Първият изход е перото, вторият — здравната вноска. Редът е част от договора на generate().
    return outcomes.get(sickPay ? 0 : 1);
  }

  // ═══ 1 · ПЕРОТО ВЪРХУ ИСТИНСКИ ФИШ ════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Болен целия юли → перо 109,87 с осигурителен доход ДА и данък НЕ")
  void theLineIsWrittenWithTheRightFlags() {
    Service s = new Service();
    BgPaySlip slip = slip();

    List<BgSickLeaveOutcome> outcomes =
        s.generate(slip, input(ep("2026-07-01", "2026-07-31", false, "sick_er")));

    BgSickLeaveOutcome sickPay = outcomeFor(outcomes, true);
    assertEquals(
        BgSickLeaveOutcome.Status.COMPUTED, sickPay.status, String.valueOf(sickPay.detail));

    BgPaySlipLine line = sickLine(slip);
    assertNotNull(line, "перото трябва да е закачено за фиша");
    assertEquals(0, new BigDecimal("109.87").compareTo(line.getBgAmount()));
    assertEquals(195, line.getBgLineNumber().intValue());
    assertEquals("CREDIT", line.getBgSign());
    assertTrue(
        line.getBgIsInsuranceIncome(),
        "⚠️ ОСИГУРИТЕЛЕН ДОХОД по чл. 40 ал. 5 КСО — вноските се дължат върху тази сума и тя влиза в"
            + " Д1. Свален, флагът оставя лицето неосигурено за двата дни.");
    assertFalse(
        line.getBgIsTaxable(),
        "⚠️ НЕОБЛАГАЕМ по ЗДДФЛ чл. 24 ал. 2 т. 14 във вр. с чл. 13 ал. 1 т. 6. Двата флага"
            + " отговарят на РАЗЛИЧНИ закона — точно затова изглеждат противоречиви.");
    assertFalse(
        line.getBgIsPermanentCharacter(),
        "без постоянен характер по НСОРЗ чл. 15 — инак влиза в базата за платения отпуск");
    assertEquals(
        0,
        new BigDecimal("2").compareTo(line.getBgQuantity()),
        "количеството документира сумата: дните за сметка на осигурителя");
    assertEquals(
        0,
        new BigDecimal("78.4783").compareTo(line.getBgRate()),
        "ставката документира сумата: среднодневната база 1805,00 / 23");
  }

  @Test
  @DisplayName("⚠️ ИДЕМПОТЕНТНО · три извиквания дават ЕДНО перо и ЕДНИ дни, не три")
  void generateIsIdempotent() {
    Service s = new Service();
    BgPaySlip slip = slip();
    BgPayrollInput in = input(ep("2026-07-01", "2026-07-31", false, "sick_er"));

    s.generate(slip, in);
    s.generate(slip, in);
    s.generate(slip, in);

    assertEquals(
        1,
        slip.getBgPaySlipLineList().size(),
        "две пера с един код се събират в брутото и дават двоен болничен");
    assertEquals(0, new BigDecimal("109.87").compareTo(sickLine(slip).getBgAmount()));
    assertEquals(
        2, slip.getBgSickDaysEmployer().intValue(), "дните се ПРИСВОЯВАТ, не се натрупват");
    assertEquals(21, slip.getBgSickDaysDoo().intValue());
    assertEquals(
        0,
        slip.getBgWorkedDays().intValue(),
        "⚠️ Отработените дни се намаляват с min(), не с изваждане: второто извикване щеше да ги"
            + " направи отрицателни");
  }

  @Test
  @DisplayName("⚠️ АДИТИВНО · чуждите пера не се пипат")
  void generateDoesNotTouchOtherLines() {
    Service s = new Service();
    BgPaySlip slip = slip();

    BgPaySlipLineType manual = new BgPaySlipLineType();
    manual.setCode("pl_nosten_trud");
    manual.setNumber(130);
    manual.setName("Нощен труд");
    manual.setBgSign("CREDIT");
    BgPaySlipLine manualLine = new BgPaySlipLine();
    manualLine.setBgPaySlipLineType(manual);
    manualLine.setBgLineNumber(130);
    manualLine.setBgAmount(new BigDecimal("42.00"));
    manualLine.setPaySlip(slip);
    slip.getBgPaySlipLineList().add(manualLine);

    s.generate(slip, input(ep("2026-07-01", "2026-07-31", false, "sick_er")));

    assertEquals(2, slip.getBgPaySlipLineList().size());
    assertEquals(
        0,
        new BigDecimal("42.00").compareTo(manualLine.getBgAmount()),
        "ръчно въведеното перо трябва да преживее всяко prepare()");
  }

  // ═══ 2 · ДНИТЕ И ОТРАБОТЕНИТЕ ═════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ БОЛНИЧНИТЕ ДНИ НАМАЛЯВАТ ОТРАБОТЕНИТЕ — инак се плаща и заплата, и болничен")
  void sickDaysReduceTheWorkedDays() {
    Service s = new Service();
    BgPaySlip slip = slip();

    s.generate(slip, input(ep("2026-07-06", "2026-07-10", false, "sick_er")));

    assertEquals(2, slip.getBgSickDaysEmployer().intValue());
    assertEquals(3, slip.getBgSickDaysDoo().intValue());
    assertEquals(
        18,
        slip.getBgWorkedDays().intValue(),
        "⚠️ 23 работни минус 5 дни болничен. БЕЗ това намаляване основното възнаграждение остава за"
            + " цял месец и лицето получава и заплата, и болничен за същите дни.");
  }

  @Test
  @DisplayName("⚠️ ПО-НИСКА РЪЧНА стойност на отработените дни се УВАЖАВА — min, не присвояване")
  void aLowerManualWorkedDaysValueSurvives() {
    Service s = new Service();
    BgPaySlip slip = slip();
    // ТРЗ вече е приспаднал платен отпуск: 10 отработени от 23.
    slip.setBgWorkedDays(10);

    s.generate(slip, input(ep("2026-07-06", "2026-07-10", false, "sick_er")));

    assertEquals(
        10,
        slip.getBgWorkedDays().intValue(),
        "⚠️ 23 − 5 = 18 е ПОВЕЧЕ от въведените 10. Присвояване щеше да върне обратно приспаднатия"
            + " отпуск — мълчаливо, и фишът пак щеше да изглежда пресметнат.");
  }

  @Test
  @DisplayName("Без болничен нищо не се пипа — нито перо, нито отработени дни")
  void noEpisodesTouchesNothing() {
    Service s = new Service();
    BgPaySlip slip = slip();

    List<BgSickLeaveOutcome> outcomes = s.generate(slip, input());

    assertTrue(slip.getBgPaySlipLineList().isEmpty(), "перо без болничен е перо със сума 0,00");
    assertEquals(WORKING_DAYS, slip.getBgWorkedDays().intValue());
    assertEquals(0, slip.getBgSickDaysEmployer().intValue());
    assertEquals(BgSickLeaveOutcome.Status.NO_EMPLOYER_DAYS, outcomeFor(outcomes, true).status);
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ · при неизвестен стаж НЕ се пипа НИЩО — нито дните, нито отработените")
  void aRefusalWritesNothingAtAll() {
    Service s = new Service();
    BgPaySlip slip = slip();
    BgPayrollInput in =
        BgPayrollInput.builder()
            .insuredTypeCode("01")
            .laborCategory("3")
            .periodFrom(FROM)
            .periodTo(TO)
            .parameterReferenceDate(FROM)
            .workingDaysInPeriod(WORKING_DAYS)
            .baseSalary(SALARY)
            .sickLeaveEpisode(ep("2026-07-01", "2026-07-31", false, "sick_er"))
            .build();

    List<BgSickLeaveOutcome> outcomes = s.generate(slip, in);

    assertEquals(BgSickLeaveOutcome.Status.REFUSED, outcomeFor(outcomes, true).status);
    assertTrue(slip.getBgPaySlipLineList().isEmpty());
    assertEquals(
        0,
        slip.getBgSickDaysEmployer().intValue(),
        "⚠️ Записана нула при ОТКАЗ е най-лошият изход: тя изглежда като изчислена и махва"
            + " основанието за отказа от екрана.");
    assertEquals(
        WORKING_DAYS,
        slip.getBgWorkedDays().intValue(),
        "отработените дни също не се пипат — намалени при отказ, те биха обрязали заплатата без"
            + " никой да е сметнал болничен");
  }

  // ═══ 3 · ГРАНИЦИТЕ НА ГЕНЕРАТОРА ══════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Само ЧЕРНОВА · потвърденият фиш е замразен")
  void onlyDraftsAreTouched() {
    Service s = new Service();
    BgPaySlip slip = slip();
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);

    assertTrue(s.generate(slip, input(ep("2026-07-01", "2026-07-31", false, "sick_er"))).isEmpty());
    assertTrue(slip.getBgPaySlipLineList().isEmpty());
  }

  @Test
  @DisplayName("⚠️ КОРИГИРАЩ и ЗАЛИЧАВАЩ фиш се ПРОПУСКАТ — числата им идват от оригинала")
  void retroSlipsAreSkipped() {
    Service s = new Service();

    BgPaySlip correction = slip();
    correction.setBgIsCorrection(true);
    assertTrue(
        s.generate(correction, input(ep("2026-07-01", "2026-07-31", false, "sick_er"))).isEmpty(),
        "⚠️ Пресметнат наново болничен би пренаписал историята точно там, където ретро-механизмът"
            + " съществува, за да не я пренаписва — а епизодите може да са коригирани в НОИ"
            + " междувременно.");
    assertTrue(correction.getBgPaySlipLineList().isEmpty());

    BgPaySlip deletion = slip();
    deletion.setBgIsDeletion(true);
    assertTrue(
        s.generate(deletion, input(ep("2026-07-01", "2026-07-31", false, "sick_er"))).isEmpty());
  }

  @Test
  @DisplayName("Липсващ ред в речника с пера → ИЗКЛЮЧЕНИЕ с името на seed-скрипта, не тихо нищо")
  void aMissingLineTypeThrows() {
    Service s = new Service();
    s.sickPayType = null;
    BgPaySlip slip = slip();

    try {
      s.generate(slip, input(ep("2026-07-01", "2026-07-31", false, "sick_er")));
      org.junit.jupiter.api.Assertions.fail("очаквах IllegalStateException за липсващ тип перо");
    } catch (IllegalStateException e) {
      assertTrue(e.getMessage().contains("App Install"), e.getMessage());
    }
  }

  @Test
  @DisplayName("Майчинство → перо НЕ се създава, но дните ДОО се записват")
  void maternityWritesDaysButNoLine() {
    Service s = new Service();
    BgPaySlip slip = slip();

    List<BgSickLeaveOutcome> outcomes =
        s.generate(slip, input(ep("2026-07-01", "2026-07-31", false, "maternity_birth")));

    assertEquals(BgSickLeaveOutcome.Status.NO_EMPLOYER_DAYS, outcomeFor(outcomes, true).status);
    assertTrue(slip.getBgPaySlipLineList().isEmpty());
    assertEquals(0, slip.getBgSickDaysEmployer().intValue());
    assertEquals(23, slip.getBgSickDaysDoo().intValue());
    assertEquals(0, slip.getBgWorkedDays().intValue(), "майчинството също намалява отработените");
  }

  // ═══ 3б · РЕЧНИКЪТ Е ИЗТОЧНИКЪТ, А НЕ КОДЪТ ══════════════════════════════

  @Test
  @DisplayName("⚠️ ПРОЦЕНТЪТ ИДВА ОТ РЕЧНИКА · 70 → 85 мени сумата 109,87 → 133,41")
  void theCompensationPercentComesFromTheDictionaryNotFromTheCode() {
    Service s = new Service();
    BgPaySlip baseline = slip();
    s.generate(baseline, input(ep("2026-07-01", "2026-07-31", false, "sick_er")));
    assertEquals(
        0,
        new BigDecimal("109.87").compareTo(sickLine(baseline).getBgAmount()),
        "изходната точка: 1805,00 / 23 × 70 на сто × 2 дни");

    // ⚠️ ЕДИНСТВЕНАТА промяна е СТОЙНОСТ В РЕЧНИКА. Нито ред код не се пипа.
    s.dictionary.get("sick_er").setCompensationPct(new BigDecimal("85"));

    BgPaySlip after = slip();
    s.generate(after, input(ep("2026-07-01", "2026-07-31", false, "sick_er")));
    assertEquals(
        0,
        new BigDecimal("133.41").compareTo(sickLine(after).getBgAmount()),
        "⚠️ СВИДЕТЕЛЯТ НА ПОСОКАТА НА ДАННИТЕ. Сумата се сменя САМО защото се смени редът в"
            + " bg_absence_type.csv — 1805,00 / 23 × 85 на сто × 2 = 133,41. Останеше ли 109,87,"
            + " процентът е закован някъде в Java и речникът, който ТРЗ вижда на екрана, лъже за"
            + " това, което фишът смята.");
  }

  @Test
  @DisplayName("⚠️ ПЛАТЕЦЪТ ИДВА ОТ РЕЧНИКА · „employer“ → „doo“ маха перото, без да пипа код")
  void thePayerComesFromTheDictionaryNotFromTheCode() {
    Service s = new Service();
    s.dictionary.get("sick_er").setPayer("doo");

    BgPaySlip slip = slip();
    List<BgSickLeaveOutcome> outcomes =
        s.generate(slip, input(ep("2026-07-01", "2026-07-31", false, "sick_er")));

    assertEquals(
        BgSickLeaveOutcome.Status.NO_EMPLOYER_DAYS,
        outcomeFor(outcomes, true).status,
        "⚠️ Същият код на отсъствие, същият период, друг ПЛАТЕЦ в речника — и първите два дни"
            + " спират да са за сметка на осигурителя. Списък от кодове в Java би дал перо и тук.");
    assertNull(sickLine(slip), "перо не се създава, когато осигурителят не дължи дни");
    assertEquals(0, slip.getBgSickDaysEmployer().intValue());
    assertEquals(23, slip.getBgSickDaysDoo().intValue(), "всички работни дни минават към ДОО");
  }

  // ═══ 4 · ЗДРАВНАТА ВНОСКА ЗА ДНИТЕ ВНР ════════════════════════════════════

  @Test
  @DisplayName("⚠️ Здравната вноска за дните ВНР се СМЯТА и се ВРЪЩА — 24,13")
  void theHealthContributionIsComputedAndReturned() {
    Service s = new Service();
    BgPaySlip slip = slip();

    List<BgSickLeaveOutcome> outcomes =
        s.generate(slip, input(ep("2026-07-01", "2026-07-31", false, "sick_er")));

    BgSickLeaveOutcome health = outcomeFor(outcomes, false);
    assertEquals(BgSickLeaveOutcome.Status.COMPUTED, health.status, String.valueOf(health.detail));
    assertEquals(0, new BigDecimal("24.13").compareTo(health.amount));
  }

  @Test
  @DisplayName("⚠️ И НЕ СЕ ЗАПИСВА КАТО ПЕРО — обявена граница, не пропуск")
  void theHealthContributionIsNotWrittenAsALine() {
    Service s = new Service();
    BgPaySlip slip = slip();

    s.generate(slip, input(ep("2026-07-01", "2026-07-31", false, "sick_er")));

    assertEquals(
        1,
        slip.getBgPaySlipLineList().size(),
        "⚠️ Групата 5xx е ОГЛЕДАЛНА на bg_contribution_fund — по един ред за фонд и платец, точно 20,"
            + " и BgPaySlipLineTypeCatalogueTest пази съответствието. Двайсет и първи ред би скъсал"
            + " инварианта, а вписване в pl_vn_health_osig би слепило две вноски върху РАЗЛИЧНИ бази"
            + " в едно число. ⚠️ ЕДИН РЕД, макар групата 5xx ВЕЧЕ да се пълни от"
            + " BgContributionLineService: този енджин не я пипа и не бива да я пипа — той връща"
            + " числото, а перата са на другия генератор.");
    assertEquals(
        BgSickLeaveResolver.LINE_EMPLOYER_SICK_PAY,
        slip.getBgPaySlipLineList().get(0).getBgPaySlipLineType().getCode());
  }

  @Test
  @DisplayName("Липсващ HEALTH_ABSENCE_BASE → ОТКАЗ за здравната вноска, но перото пак се пише")
  void aMissingHealthBaseRefusesOnlyTheHealthPart() {
    Service s = new Service();
    s.healthBase = null;
    BgPaySlip slip = slip();

    List<BgSickLeaveOutcome> outcomes =
        s.generate(slip, input(ep("2026-07-01", "2026-07-31", false, "sick_er")));

    assertEquals(BgSickLeaveOutcome.Status.COMPUTED, outcomeFor(outcomes, true).status);
    assertNotNull(sickLine(slip), "болничният не зависи от здравната база");
    BgSickLeaveOutcome health = outcomeFor(outcomes, false);
    assertEquals(BgSickLeaveOutcome.Status.REFUSED, health.status);
    assertTrue(health.detail.contains("HEALTH_ABSENCE_BASE"), health.detail);
    assertNull(health.amount);
  }

  // ═══ 5 · КАЛЕНДАРЪТ Е ЕДИН ════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Празниците идват от СЪЩИЯ календар — интервалът покрива и епизод от минал месец")
  void theHolidayIntervalCoversEpisodesStartingBeforeThePeriod() {
    Service s = new Service();
    s.holidays = Collections.singleton(LocalDate.of(2026, 6, 30));
    BgPaySlip slip = slip();

    s.generate(slip, input(ep("2026-06-30", "2026-07-10", false, "sick_er")));

    assertEquals(
        2,
        slip.getBgSickDaysEmployer().intValue(),
        "⚠️ 30 юни е обявен за празник, тоест не е работен ден: първите два работни дни на епизода"
            + " стават 1 и 2 юли и ДВАТА падат в този период. Взети само празниците на периода,"
            + " 30 юни щеше да мине за работен и в юли щеше да падне само един ден.");
  }
}
