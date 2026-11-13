package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗАЛИЧАВАНЕТО — симетричният близнак на корекцията (спецификация § 5).
 *
 * <p><b>Кога заличаване, а не корекция.</b> Корекцията казва „числата за онзи месец са други“;
 * заличаването казва „ТОЗИ ЗАПИС НЕ БИВА ДА СЪЩЕСТВУВА“ — грешно лице, грешен период, дубликат. По
 * Н-13 механизмът е двуетапен: запис с код „заличаване“ за грешния запис, после — ако трябва — нов
 * с верни данни за верния период (това е нормалният поток и не е част от заличаването).
 *
 * <p><b>⚠️ КОЕТО ПРАВИ АРИТМЕТИКАТА ДРУГА, А НЕ „СЪЩАТА С ДРУГ ЗНАК“.</b> Заличаващият фиш носи
 * СЪЩИТЕ числа като оригинала — те идентифицират записа за НАП. Тоест правилото на корекцията „ново
 * нето минус старо“ тук дава НУЛА и лицето би задържало пари, които не му се дължат. Верният
 * отговор е „минус ЦЯЛОТО оригинално нето“. Двата потока имат едно име, едно копие и една
 * машинария, а се различават по три неща: флага, делтата и името на реда — и точно това мери този
 * тест.
 *
 * <p>Сценарият е този на § 3, но огледален: мартенският фиш е издаден ПО ГРЕШКА и трябва да
 * изчезне. Числата са същите, за да се вижда, че разликата е в потока, а не в данните.
 */
class BgPaySlipVoidRecordTest {

  /** Март, грешно издаденият фиш: база 2000, лични 13,78 %, ДОД 10 %, нето 1551,96. */
  private static final BigDecimal MARCH_BASE = new BigDecimal("2000.00");

  private static final BigDecimal MARCH_PERSONAL = new BigDecimal("275.60");
  private static final BigDecimal MARCH_EMPLOYER = new BigDecimal("378.40");
  private static final BigDecimal MARCH_TAX = new BigDecimal("172.44");
  private static final BigDecimal MARCH_TAX_BASE = new BigDecimal("1724.40");
  private static final BigDecimal MARCH_NET = new BigDecimal("1551.96");

  /** Май, текущият месец — различна база нарочно, за да не съвпадне нищо по случайност. */
  private static final BigDecimal MAY_BASE = new BigDecimal("2100.00");

  private static final BigDecimal MAY_PERSONAL = new BigDecimal("289.38");
  private static final BigDecimal MAY_EMPLOYER = new BigDecimal("397.32");
  private static final BigDecimal MAY_TAX = new BigDecimal("181.06");
  private static final BigDecimal MAY_TAX_BASE = new BigDecimal("1810.62");
  private static final BigDecimal MAY_NET = new BigDecimal("1629.56");

  /** 1629,56 − 1551,96: цялото мартенско нето се връща от майската заплата. */
  private static final BigDecimal MAY_NET_AFTER = new BigDecimal("77.60");

  // ── услугата без база ───────────────────────────────────────────────────

  private static class Service extends BgPaySlipService {

    /**
     * Производните пера на коригиращото копие (5xx, 516 по т. 5, 700, карвът на 180/181) имат свой
     * свидетел — {@code BgPaySlipRetroGeneratorsTest}. Тук се мери механиката на ретро-потока
     * (делтата, периодът, ключовете), не числата на генераторите; calculate() минава истински —
     * отказите, сборовете и отпечатъкът.
     */
    @Override
    protected void prepareCorrection(BgPaySlip copy) {}

    /**
     * Генераторът на начисления пипа речника и каталога с параметри; тук твърдението е за
     * ретро-механизма, не за перата. Че {@code prepare()} наистина вика шева, се проверява в {@code
     * BgPaySlipServiceTest}, а какво пише той — в {@code BgEarningsGeneratorTest}.
     */
    @Override
    protected void generateEarnings(BgPaySlip slip) {}

    Period currentPeriod;
    BgPaySlip currentDraft;
    BgPaySlipLineType deltaType = deltaLineType();

    final List<BgPaySlip> savedSlips = new ArrayList<>();
    final List<String> adjustLog = new ArrayList<>();

    @Override
    protected BgWorkingDayService.Count periodWorkingDays(BgPaySlip slip) {
      return new BgWorkingDayService() {
        @Override
        public java.util.Set<Integer> coveredYears() {
          return new java.util.HashSet<>(java.util.Arrays.asList(2026, 2027));
        }

        @Override
        protected java.util.Set<LocalDate> holidaysIn(LocalDate from, LocalDate to) {
          return Collections.emptySet();
        }
      }.count(LocalDate.of(2026, 3, 1), 31);
    }

    @Override
    protected BgContributionResult contributions(BgPaySlip slip) {
      return BgContributionResult.resolved(Collections.emptyList());
    }

    @Override
    protected BgMinIncomeResult minIncome(BgPaySlip slip) {
      return BgMinIncomeResult.notApplicable("неутрално за този тест");
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.0000");
    }

    @Override
    protected String otherSlipCarrying(BgCompensation doc, BgPaySlip slip) {
      return null;
    }

    @Override
    protected void save(BgPaySlip slip) {
      savedSlips.add(slip);
    }

    @Override
    protected void saveCompensation(BgCompensation doc) {}

    @Override
    protected Period currentOpenPeriod(BgPaySlip slip) {
      return currentPeriod;
    }

    @Override
    protected BgPaySlip openDraftSlip(EmploymentContract contract, Period period) {
      return currentDraft;
    }

    @Override
    protected BgPaySlipLineType correctionLineType() {
      return deltaType;
    }

    @Override
    protected void savePeriod(Period period) {}

    @Override
    protected void logAdjustStart(Period period) {
      adjustLog.add("start:" + period.getName());
    }

    @Override
    protected void logAdjustEnd(Period period) {
      adjustLog.add("end:" + period.getName());
    }

    @Override
    protected LocalDate today() {
      return LocalDate.of(2026, 5, 20);
    }
  }

  // ── фикстури ────────────────────────────────────────────────────────────

  private static BgPaySlipLineType type(
      int number, String name, String sign, boolean taxable, boolean insurable) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setNumber(number);
    t.setName(name);
    t.setBgSign(sign);
    t.setBgIsTaxable(taxable);
    t.setBgIsInsuranceIncome(insurable);
    t.setBgIsPermanentCharacter(false);
    return t;
  }

  private static BgPaySlipLineType deltaLineType() {
    BgPaySlipLineType t =
        type(620, "Доплащане/удръжка по корекция за минал период", "CREDIT", false, false);
    t.setCode(BgPaySlipService.CORRECTION_LINE_CODE);
    t.setBgGroup("PAYMENTS");
    return t;
  }

  private static Period period(String name, LocalDate from, LocalDate to, int status) {
    Year year = new Year();
    year.setName("Заплатна " + from.getYear());
    year.setTypeSelect(2);
    Period p = new Period();
    p.setName(name);
    p.setFromDate(from);
    p.setToDate(to);
    p.setYear(year);
    p.setStatusSelect(status);
    return p;
  }

  private static Period march(int status) {
    return period("03/PY2026", LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), status);
  }

  private static Period may() {
    return period(
        "05/PY2026",
        LocalDate.of(2026, 5, 1),
        LocalDate.of(2026, 5, 31),
        PeriodRepository.STATUS_OPENED);
  }

  private static final EmploymentContract IVANOV = new EmploymentContract();

  private static BgPaySlip monthSlip(
      Service s,
      Period p,
      BigDecimal base,
      BigDecimal personal,
      BigDecimal employer,
      BigDecimal tax) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setEmploymentContract(IVANOV);
    slip.setPeriod(p);
    slip.setBgPaySlipLineList(new ArrayList<>());
    s.addLine(slip, s.lineFrom(type(100, "Основно възнаграждение", "CREDIT", true, true), base));
    s.addLine(
        slip,
        s.lineFrom(type(501, "Осигуровки — за сметка на лицето", "DEBIT", false, false), personal));
    s.addLine(
        slip,
        s.lineFrom(
            type(551, "Осигуровки — за сметка на работодателя", "CREDIT", false, false), employer));
    s.addLine(slip, s.lineFrom(type(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT", false, false), tax));
    s.recompute(slip);
    return slip;
  }

  /** Грешно издаденият мартенски фиш: потвърден, изплатен, вече подаден с Д1/Д6. */
  private static BgPaySlip wrongMarch(Service s, Period p) {
    BgPaySlip slip = monthSlip(s, p, MARCH_BASE, MARCH_PERSONAL, MARCH_EMPLOYER, MARCH_TAX);
    slip.setReference("FISH-2026-0101");
    // ⚠️ Оригиналът е пресметнат по общия път, тоест режимът му е замразен — истинската първа
    // стъпка на calculate(). Без нея копието би било копие на заварен фиш и щеше да отказва.
    s.freezeRegime(slip);
    slip.setStatusSelect(BgPaySlipResolver.PAID);
    return slip;
  }

  private static Service scene(BgPaySlip[] out) {
    Service s = new Service();
    out[0] = wrongMarch(s, march(PeriodRepository.STATUS_CLOSED));
    s.currentPeriod = may();
    s.currentDraft = monthSlip(s, s.currentPeriod, MAY_BASE, MAY_PERSONAL, MAY_EMPLOYER, MAY_TAX);
    out[1] = s.currentDraft;
    return s;
  }

  // ── 1 · копието и оригиналът ─────────────────────────────────────────────

  @Test
  @DisplayName("voidRecord() дава заличаващо копие с ЧИСЛАТА НА ОРИГИНАЛА, а оригиналът остава")
  void voidRecordOpensADeletingCopyAndKeepsTheOriginal() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip original = scene[0];

    BgPaySlip copy = s.voidRecord(original);

    assertTrue(copy.getBgIsDeletion(), "копието е обявено за заличаващо");
    assertSame(original, copy.getBgDeletionOf(), "и сочи грешния фиш");
    assertFalse(copy.getBgIsCorrection(), "⚠️ и НЕ е коригиращо — двата флага се изключват");
    assertNull(copy.getBgCorrectionOf(), "нито носи препратка за корекция");
    assertEquals(BgPaySlipResolver.DRAFT, copy.getStatusSelect());
    assertSame(original.getPeriod(), copy.getPeriod(), "същият период — март");

    // ⚠️ ЧИСЛАТА СА ТЕЗИ НА ОРИГИНАЛА, перо по перо. Те не са „новите верни“ — те ИДЕНТИФИЦИРАТ
    // записа, който НАП трябва да заличи, тоест те СА данните на заличаващата декларация.
    assertEquals(4, copy.getBgPaySlipLineList().size());
    assertEquals(MARCH_BASE, copy.getBgGrossTotal(), "бруто");
    assertEquals(MARCH_BASE, copy.getBgInsuranceIncome(), "осигурителен доход");
    assertEquals(MARCH_TAX_BASE, copy.getBgTaxBase(), "данъчна основа");
    assertEquals(MARCH_TAX, copy.getBgTaxAmount(), "данък");
    assertEquals(MARCH_NET, copy.getBgNetTotal(), "нето");
    assertEquals(new BigDecimal("2378.40"), copy.getBgEmployerCost(), "разход за работодателя");

    // ⚠️ ОРИГИНАЛЪТ ОСТАВА И НЕ СЕ ПИПА. Той е доказателството какво е било подадено и платено.
    assertEquals(BgPaySlipResolver.PAID, original.getStatusSelect(), "не се отказва");
    assertEquals(MARCH_NET, original.getBgNetTotal(), "и не се пренаписва");
    assertEquals(4, original.getBgPaySlipLineList().size(), "перата му са непокътнати");
    for (BgPaySlipLine line : copy.getBgPaySlipLineList()) {
      assertSame(copy, line.getPaySlip(), "перата на копието са НОВИ записи, закачени за копието");
    }
  }

  @Test
  @DisplayName("voidRecord() слага периода в ADJUSTING и ЛОГВА прехода — както correct()")
  void voidRecordMovesThePeriodToAdjusting() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    s.voidRecord(scene[0]);

    assertEquals(
        PeriodRepository.STATUS_ADJUSTING, scene[0].getPeriod().getStatusSelect().intValue());
    assertTrue(s.adjustLog.contains("start:03/PY2026"), s.adjustLog.toString());
  }

  // ── 2 · ОТРИЦАТЕЛНАТА делта ─────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ § 5 · заличаването слага ОТРИЦАТЕЛНА делта — цялото нето се връща от май")
  void deletingPutsANegativeDeltaIntoTheCurrentSlip() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip may = scene[1];

    assertEquals(MAY_NET, may.getBgNetTotal(), "майското нето ПРЕДИ заличаването");

    BgPaySlip copy = s.voidRecord(scene[0]);
    s.confirm(copy);

    assertEquals(5, may.getBgPaySlipLineList().size(), "точно ЕДИН нов ред в май");
    BgPaySlipLine delta = may.getBgPaySlipLineList().get(4);
    assertEquals(620, delta.getBgLineNumber().intValue(), "перото на корекцията, същото");
    assertEquals(BgPaySlipResolver.DEBIT, delta.getBgSign(), "УДЪРЖА се");
    assertEquals(
        MARCH_NET,
        delta.getBgAmount(),
        "⚠️ ЦЯЛОТО оригинално нето, а не разлика: заличеният запис изчезва, не се смалява");
    assertEquals(
        "Сторно за 03/2026",
        delta.getName(),
        "⚠️ „Сторно“, не „Удръжка“ — удръжка се чете като „част от месеца се връща“, а тук се връща"
            + " целият");
    assertSame(scene[0].getPeriod(), delta.getBgCorrectsPeriod(), "маркерът сочи МАРТ");
    assertFalse(delta.getBgIsInsuranceIncome());
    assertFalse(delta.getBgIsTaxable());

    // текущото нето ПАДА точно с върнатото
    assertEquals(MAY_NET_AFTER, may.getBgNetTotal(), "1629,56 − 1551,96 = 77,60");
    assertEquals(
        MARCH_NET.negate(),
        may.getBgNetTotal().subtract(MAY_NET),
        "майското нето пада точно с оригиналното нето, нито стотинка повече");
  }

  @Test
  @DisplayName("⚠️ Майските Д1/Д6 остават ЧИСТИ и при заличаване — маркерът върши същата работа")
  void theCurrentDeclarationStaysCleanOnDeletionToo() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip may = scene[1];

    s.confirm(s.voidRecord(scene[0]));

    assertEquals(MAY_BASE, may.getBgInsuranceIncome(), "осигурителният доход НЕ мърда");
    assertEquals(MAY_TAX_BASE, may.getBgTaxBase(), "данъчната основа НЕ мърда");
    assertEquals(MAY_TAX, may.getBgTaxAmount(), "данъкът НЕ мърда");
    assertEquals(MAY_BASE, may.getBgGrossTotal(), "брутото НЕ мърда");
    assertEquals(new BigDecimal("2497.32"), may.getBgEmployerCost(), "разходът НЕ мърда");
  }

  @Test
  @DisplayName("⚠️ ПРАВИЛОТО НА КОРЕКЦИЯТА тук би дало НУЛА — затова признакът се пита")
  void theCorrectionRuleWouldYieldZeroHere() {
    // Заличаващият фиш носи числата на оригинала, тоест „ново минус старо“ е нула. Ако делтата се
    // извеждаше от числата вместо от флага, лицето щеше да задържи 1551,96, които не му се дължат.
    assertEquals(
        new BigDecimal("0.00"),
        BgPaySlipResolver.retroDelta(false, MARCH_NET, MARCH_NET),
        "правилото на КОРЕКЦИЯТА върху същите числа");
    assertEquals(
        MARCH_NET.negate(),
        BgPaySlipResolver.retroDelta(true, MARCH_NET, MARCH_NET),
        "правилото на ЗАЛИЧАВАНЕТО върху същите числа");

    // и обратното: заличаване на фиш с нулево нето не дължи нищо
    assertEquals(
        new BigDecimal("0.00"),
        BgPaySlipResolver.retroDelta(true, BigDecimal.ZERO, BigDecimal.ZERO),
        "цял месец неплатен отпуск — няма какво да се връща");
  }

  @Test
  @DisplayName("Нулево оригинално нето не слага ред, но периодът пак се затваря")
  void aZeroOriginalNetAddsNoLine() throws Exception {
    Service s = new Service();
    BgPaySlip original =
        monthSlip(
            s,
            march(PeriodRepository.STATUS_CLOSED),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO);
    s.freezeRegime(original);
    original.setStatusSelect(BgPaySlipResolver.PAID);
    s.currentPeriod = may();
    s.currentDraft = monthSlip(s, s.currentPeriod, MAY_BASE, MAY_PERSONAL, MAY_EMPLOYER, MAY_TAX);

    s.confirm(s.voidRecord(original));

    assertEquals(4, s.currentDraft.getBgPaySlipLineList().size(), "ред за нула не се слага");
    assertEquals(
        PeriodRepository.STATUS_CLOSED,
        original.getPeriod().getStatusSelect().intValue(),
        "но заличаващата декларация се ражда и периодът се затваря — тя е за ЗАПИСА, не за парите");
  }

  // ── 3 · и трите изхода от ADJUSTING ─────────────────────────────────────

  @Test
  @DisplayName("Периодът се връща в CLOSED при потвърждаване на заличаването, и краят е вписан")
  void confirmingTheDeletionReturnsThePeriodToClosed() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    s.confirm(s.voidRecord(scene[0]));

    assertEquals(PeriodRepository.STATUS_CLOSED, scene[0].getPeriod().getStatusSelect().intValue());
    assertEquals(
        java.util.Arrays.asList("start:03/PY2026", "end:03/PY2026"),
        s.adjustLog,
        "историята носи и началото, и края");
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ на заличаващото копие връща CLOSED и НЕ слага нищо в май")
  void cancellingTheDeletionAlsoClosesThePeriod() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    BgPaySlip copy = s.voidRecord(scene[0]);
    copy.setBgCancelReason("фишът все пак е верен — грешката беше в друг период");
    s.cancel(copy);

    assertEquals(BgPaySlipResolver.CANCELLED, copy.getStatusSelect());
    assertEquals(PeriodRepository.STATUS_CLOSED, scene[0].getPeriod().getStatusSelect().intValue());
    assertEquals(4, scene[1].getBgPaySlipLineList().size(), "в май НЯМА сторно");
    assertEquals(MAY_NET, scene[1].getBgNetTotal());
    assertEquals(
        BgPaySlipResolver.PAID, scene[0].getStatusSelect(), "и оригиналът пак е непокътнат");
  }

  // ── 4 · не плаща сам ────────────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ ЗАЛИЧАВАЩИЯТ ФИШ НЕ СЕ ИЗПЛАЩА — инак плаща второ онова, което трябва да върне")
  void theDeletingSlipIsNeverPaid() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    BgPaySlip copy = s.voidRecord(scene[0]);
    s.confirm(copy);
    copy.setBgPaidDate(LocalDate.of(2026, 5, 25));

    AxelorException e = assertThrows(AxelorException.class, () -> s.pay(copy));
    assertTrue(e.getMessage().contains("Заличаващият"), e.getMessage());
    assertTrue(e.getMessage().contains("НЕ се изплаща"), e.getMessage());
    assertTrue(
        e.getMessage().contains("трябва да върне"),
        "съобщението казва ЗАЩО е по-грубо при заличаване: " + e.getMessage());
    assertEquals(BgPaySlipResolver.CONFIRMED, copy.getStatusSelect());
  }

  @Test
  @DisplayName(
      "Двойното обезщетение НЕ спира заличаващия фиш — същото изключение като при корекция")
  void theDoubleCompensationGuardSkipsTheDeletingSlip() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip original = scene[0];

    // Обезщетение, вече платено с оригинала. Копието носи същата препратка — пазачът би обявил
    // всяко заличаване за второ плащане и заличаването щеше да е невъзможно.
    BgCompensation doc = new BgCompensation();
    doc.setReference("OBEZ-2026-0001");
    doc.setId(42L);
    original.getBgPaySlipLineList().get(0).setBgCompensation(doc);

    BgPaySlip copy = s.voidRecord(original);
    assertSame(
        doc,
        copy.getBgPaySlipLineList().get(0).getBgCompensation(),
        "препратката СЕ КОПИРА — тя е част от одитната следа");
    assertNull(
        s.doubleCompensationRefusal(copy),
        "⚠️ и въпреки това пазачът мълчи. Безопасно е ТОЧНО ЗАЩОТО заличаващият фиш не се изплаща:"
            + " документ, който не плаща, не може да плати втори път.");
  }

  // ── 5 · пазачите ────────────────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ Заличаващият фиш НЕ СЕ РЕДАКТИРА — числата му идентифицират записа за НАП")
  void anAlteredDeletionIsRefused() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);

    BgPaySlip copy = s.voidRecord(scene[0]);
    // Операторът „поправя“ заплатата в заличаващото копие — тоест мисли, че прави корекция.
    copy.getBgPaySlipLineList().get(0).setBgAmount(new BigDecimal("1900.00"));
    s.recompute(copy);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(copy));
    assertTrue(e.getMessage().contains("разминал"), e.getMessage());
    assertTrue(
        e.getMessage().contains("КОРЕКЦИЯ"),
        "отказът сочи верния поток, а не само че е сбъркан: " + e.getMessage());
    assertEquals(4, scene[1].getBgPaySlipLineList().size(), "и в май не е седнало нищо");
  }

  @Test
  @DisplayName("⚠️ Фиш И коригиращ, И заличаващ се отказва — двата флага искат различна делта")
  void aSlipThatIsBothCorrectionAndDeletionIsRefused() {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip confused = monthSlip(s, may(), MAY_BASE, MAY_PERSONAL, MAY_EMPLOYER, MAY_TAX);
    confused.setBgIsCorrection(true);
    confused.setBgCorrectionOf(scene[0]);
    confused.setBgIsDeletion(true);
    confused.setBgDeletionOf(scene[0]);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(confused));
    assertTrue(e.getMessage().contains("И за коригиращ, И за заличаващ"), e.getMessage());
    assertNotNull(BgPaySlipResolver.refuseBothRetroFlags(confused));
  }

  @Test
  @DisplayName("Заличаващ признак без препратка се отказва, и съобщението сочи voidRecord()")
  void aDeletionFlagWithoutAnOriginalIsRefused() {
    Service s = new Service();
    BgPaySlip orphan = monthSlip(s, may(), MAY_BASE, MAY_PERSONAL, MAY_EMPLOYER, MAY_TAX);
    orphan.setBgIsDeletion(true);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(orphan));
    assertTrue(e.getMessage().contains("ЗАЛИЧАВАЩ"), e.getMessage());
    assertTrue(e.getMessage().contains("voidRecord()"), e.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, orphan.getStatusSelect(), "и фишът не е замразен");
  }

  @Test
  @DisplayName("⚠️ РЕТРО-фиш не се заличава — той не е плащал, отказва се")
  void aRetroSlipCannotBeDeleted() throws Exception {
    BgPaySlip[] scene = new BgPaySlip[2];
    Service s = scene(scene);
    BgPaySlip correcting = s.correct(scene[0]);
    correcting.setStatusSelect(BgPaySlipResolver.CONFIRMED);

    AxelorException e = assertThrows(AxelorException.class, () -> s.voidRecord(correcting));
    assertTrue(e.getMessage().contains("РЕДОВЕН фиш"), e.getMessage());
    assertTrue(e.getMessage().contains("ОТКАЖЕТЕ"), e.getMessage());
  }

  @Test
  @DisplayName("Заличава се само ПОТВЪРДЕН или ИЗПЛАТЕН фиш, и периодът трябва да е ЗАКЛЮЧЕН")
  void theSameSourceAndPeriodGuardsApply() {
    Service s = new Service();

    BgPaySlip draft = wrongMarch(s, march(PeriodRepository.STATUS_CLOSED));
    draft.setStatusSelect(BgPaySlipResolver.DRAFT);
    AxelorException e = assertThrows(AxelorException.class, () -> s.voidRecord(draft));
    assertTrue(e.getMessage().contains("Заличава се само"), e.getMessage());

    // и всеки статус на периода дава СВОЙ отговор, с думата на ЗАЛИЧАВАНЕТО
    BgPaySlip open = wrongMarch(s, march(PeriodRepository.STATUS_OPENED));
    AxelorException o = assertThrows(AxelorException.class, () -> s.voidRecord(open));
    assertTrue(o.getMessage().contains("заличаване не е нужно"), o.getMessage());

    for (int closing :
        new int[] {
          PeriodRepository.STATUS_TEMPORARILY_CLOSED, PeriodRepository.STATUS_CLOSURE_IN_PROGRESS
        }) {
      BgPaySlip inClosure = wrongMarch(s, march(closing));
      AxelorException c = assertThrows(AxelorException.class, () -> s.voidRecord(inClosure));
      assertTrue(c.getMessage().contains("СЧЕТОВОДСТВОТО"), closing + " → " + c.getMessage());
    }

    BgPaySlip adjusting = wrongMarch(s, march(PeriodRepository.STATUS_ADJUSTING));
    AxelorException a = assertThrows(AxelorException.class, () -> s.voidRecord(adjusting));
    assertTrue(a.getMessage().contains("ВЕЧЕ е под корекция"), a.getMessage());
  }

  // ── 6 · симетрията, изрично ─────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ Двата потока се различават по ТРИ неща и нищо друго")
  void theTwoFlowsDifferInExactlyThreeThings() throws Exception {
    BgPaySlip[] one = new BgPaySlip[2];
    Service correcting = scene(one);
    BgPaySlip[] two = new BgPaySlip[2];
    Service deleting = scene(two);

    BgPaySlip correctionCopy = correcting.correct(one[0]);
    BgPaySlip deletionCopy = deleting.voidRecord(two[0]);

    // 1 · флагът
    assertTrue(correctionCopy.getBgIsCorrection() && !correctionCopy.getBgIsDeletion());
    assertTrue(deletionCopy.getBgIsDeletion() && !deletionCopy.getBgIsCorrection());

    // ВСИЧКО ОСТАНАЛО в копието е едно и също — договор, период, замразени входове, числа, пера.
    assertSame(correctionCopy.getEmploymentContract(), deletionCopy.getEmploymentContract());
    assertEquals(correctionCopy.getBgNetTotal(), deletionCopy.getBgNetTotal());
    assertEquals(correctionCopy.getBgInsuranceIncome(), deletionCopy.getBgInsuranceIncome());
    assertEquals(
        correctionCopy.getBgPaySlipLineList().size(), deletionCopy.getBgPaySlipLineList().size());
    assertEquals(correctionCopy.getStatusSelect(), deletionCopy.getStatusSelect());

    // 2 · делтата и 3 · името — само те се разминават
    correcting.calculate(correctionCopy);
    correcting.confirm(correctionCopy); // нулева делта: копието не е редактирано
    deleting.confirm(deletionCopy);
    assertEquals(4, one[1].getBgPaySlipLineList().size(), "корекция без промяна: няма ред");
    assertEquals(5, two[1].getBgPaySlipLineList().size(), "заличаване: винаги има сторно");
    assertEquals("Сторно за 03/2026", two[1].getBgPaySlipLineList().get(4).getName());

    // и двата затварят периода
    assertEquals(PeriodRepository.STATUS_CLOSED, one[0].getPeriod().getStatusSelect().intValue());
    assertEquals(PeriodRepository.STATUS_CLOSED, two[0].getPeriod().getStatusSelect().intValue());
  }
}
