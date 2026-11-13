package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.PeriodRepository;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПАЗАЧЪТ НА ЗАКЛЮЧЕНИЯ ПЕРИОД: заключеният месец не се пипа с редовен фиш.
 *
 * <p><b>Какво лови.</b> Потвърждаване, което НЕ гледа статуса на периода. Без него фиш за март може
 * да се потвърди през август — след като мартенските Д1/Д6 са подадени и платени — и разминаването
 * между подаденото и записаното няма нито един пазач. Тихо е, защото всяко от двете числа е вярно
 * за себе си: фишът показва вярна сметка, декларацията — вярна за момента, в който е подадена, а
 * истината е, че вече не съвпадат.
 *
 * <p><b>Двете посоки, и двете задължителни.</b> Отказът трябва да СПИРА редовния фиш върху заключен
 * период и да ПУСКА коригиращия и заличаващия — инак пазачът блокира точно единствения път, по
 * който заключеният месец може да се поправи, и корекцията става невъзможна със собствения си
 * предпазител.
 *
 * <p><b>Без база и без Guice</b> — по същия модел като {@code BgPaySlipGuardsTest}: правилото е в
 * {@link BgPaySlipResolver}, шевовете към базата са подменени тук.
 */
class BgPaySlipLockedPeriodTest {

  /**
   * Услугата с неутрални шевове: тук се изолира ЕДИН предпазител, останалите не бива да говорят.
   */
  private static class Service extends BgPaySlipService {

    /**
     * Генераторът на начисления пипа речника и каталога с параметри; тук твърдението е за
     * ретро-механизма, не за перата. Че {@code prepare()} наистина вика шева, се проверява в {@code
     * BgPaySlipServiceTest}, а какво пише той — в {@code BgEarningsGeneratorTest}.
     */
    @Override
    protected void generateEarnings(BgPaySlip slip) {}

    // ⚠️ Потвърждаването приема само фиш, пресметнат по calculate(). Тестовете тук мерят
    // пазачите на ПЕРИОДА, не генераторите — затова генераторите са празни, точно както при
    // BgPaySlipGuardsTest, а calculate() е истинският.
    @Override
    protected void generateSickLeave(BgPaySlip slip) {}

    @Override
    protected void freezeLeaveDays(BgPaySlip slip) {}

    @Override
    protected void generateArt177Leave(BgPaySlip slip) {}

    @Override
    protected void generateCompensations(BgPaySlip slip) {}

    @Override
    protected void generateContributions(BgPaySlip slip) {}

    @Override
    protected void generateIncomeTax(BgPaySlip slip) {}

    @Override
    protected void generateAnnualSettlement(BgPaySlip slip) {}

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
    protected void save(BgPaySlip slip) {}

    @Override
    protected void saveCompensation(BgCompensation doc) {}
  }

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

  /** Мартенски фиш, готов за потвърждаване, върху период със ЗАДАДЕН статус. */
  private static BgPaySlip readySlip(Service s, Integer periodStatus) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    Year year = new Year();
    year.setName("Заплатна 2026");
    year.setTypeSelect(2);
    Period period = new Period();
    period.setName("03/PY2026");
    period.setFromDate(LocalDate.of(2026, 3, 1));
    period.setToDate(LocalDate.of(2026, 3, 31));
    period.setYear(year);
    period.setStatusSelect(periodStatus);
    slip.setPeriod(period);
    slip.setBgPaySlipLineList(new ArrayList<>());

    s.addLine(
        slip,
        s.lineFrom(
            type(100, "Основно възнаграждение", "CREDIT", true, true), new BigDecimal("2000.00")));
    s.recompute(slip);
    s.addLine(
        slip,
        s.lineFrom(
            type(700, "Данък по чл. 42 ЗДДФЛ", "DEBIT", false, false), slip.getBgTaxAmount()));
    s.calculate(slip);
    return slip;
  }

  // ── 1 · отказът ─────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "⚠️ Редовен фиш върху ЗАКЛЮЧЕН период не се потвърждава, а съобщението сочи корекцията")
  void anOrdinarySlipOnAClosedPeriodIsRefused() {
    Service s = new Service();
    BgPaySlip slip = readySlip(s, PeriodRepository.STATUS_CLOSED);

    AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(slip));
    assertTrue(e.getMessage().contains("03/PY2026"), e.getMessage());
    assertTrue(e.getMessage().contains("ЗАКЛЮЧЕН"), e.getMessage());
    assertTrue(
        e.getMessage().contains("КОРЕКЦИЯ"),
        "отказът трябва да КАЖЕ изхода, инак операторът тръгва да отваря периода на ръка: "
            + e.getMessage());
    assertTrue(
        e.getMessage().contains("ЗАЛИЧАВАНЕ"),
        "и двата пътя се назовават — грешно издаденият фиш се заличава, не се коригира: "
            + e.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect(), "остава чернова");
  }

  // ── 2 · регресията ──────────────────────────────────────────────────────

  @Test
  @DisplayName("Върху ОТВОРЕН период същият фиш минава — пазачът не спира редовната работа")
  void theSameSlipPassesOnAnOpenPeriod() throws Exception {
    Service s = new Service();
    BgPaySlip slip = readySlip(s, PeriodRepository.STATUS_OPENED);
    s.confirm(slip);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect());
  }

  @Test
  @DisplayName(
      "⚠️ ПРАЗЕН статус на периода НЕ е заключен — заварените фишове продължават да минават")
  void anUnsetPeriodStatusIsNotLocked() throws Exception {
    Service s = new Service();
    // ⚠️ Заварените периоди и фикстурите на модула може да са без статус. Генерираният getter
    // за Integer връща 0, тоест „не съм пипал полето“ идва тук като
    // нула — а нулата НЕ е нито един от петте статуса. Ако пазачът четеше „всичко различно от
    // OPENED е заключено“, той щеше да спре точно тези фишове.
    BgPaySlip slip = readySlip(s, null);
    assertEquals(0, slip.getPeriod().getStatusSelect().intValue(), "непопълненият статус е нула");
    s.confirm(slip);
    assertEquals(BgPaySlipResolver.CONFIRMED, slip.getStatusSelect());
  }

  // ── 3 · единственият изход остава отворен ───────────────────────────────

  @Test
  @DisplayName("⚠️ КОРИГИРАЩ и ЗАЛИЧАВАЩ фиш минават върху заключен период — инак поправка няма")
  void aCorrectingOrDeletingSlipPassesOnAClosedPeriod() throws Exception {
    Service s = new Service();

    // ⚠️ И ДВАТА се проверяват на нивото на ПРАВИЛОТО, а не през confirm(), и това е нарочно.
    // Потвърждаването на ретро-фиш влече цялата машинария — смятане на делтата, търсене на текущия
    // отворен период и на перото 620 в речника, вписване в историята. Тя си има свидетели край до
    // край (BgPaySlipCorrectionTest, BgPaySlipVoidRecordTest), които подменят своите шевове към
    // базата. Тук въпросът е ДРУГ и по-тесен: пуска ли пазачът на заключения период ретро-фиша.
    // Вкарани през confirm(), тези твърдения щяха да падат заради шевове, които не мерят — тоест
    // червеното щеше да сочи другаде.
    BgPaySlip correcting = readySlip(s, PeriodRepository.STATUS_CLOSED);
    correcting.setBgIsCorrection(true);
    assertNull(
        BgPaySlipResolver.refuseLockedPeriod(correcting),
        "коригиращият фиш е ЕДИН от двата изхода от заключен месец — пазач, който спира и него,"
            + " прави поправката невъзможна със собствения си предпазител");

    BgPaySlip deleting = readySlip(s, PeriodRepository.STATUS_CLOSED);
    deleting.setBgIsDeletion(true);
    assertNull(BgPaySlipResolver.refuseLockedPeriod(deleting), "и заличаващият — вторият изход");

    // и обратната посока, на същото ниво: редовният фиш се спира
    BgPaySlip ordinary = readySlip(s, PeriodRepository.STATUS_CLOSED);
    assertNotNull(BgPaySlipResolver.refuseLockedPeriod(ordinary));
  }

  @Test
  @DisplayName("⚠️ ADJUSTING не е заключен — иначе корекцията се блокира от собствения си пазач")
  void anAdjustingPeriodIsNotLocked() throws Exception {
    Service s = new Service();
    BgPaySlip slip = readySlip(s, PeriodRepository.STATUS_ADJUSTING);
    s.confirm(slip);
    assertEquals(
        BgPaySlipResolver.CONFIRMED,
        slip.getStatusSelect(),
        "периодът е ВРЕМЕННО отворен точно за да може коригиращото копие да се потвърди");
  }

  // ── 3б · периодът в ЗАТВАРЯНЕ спира и двете ─────────────────────────────

  @Test
  @DisplayName("⚠️ Период в ЗАТВАРЯНЕ не приема нито редовен фиш, нито корекция")
  void aPeriodBeingClosedAcceptsNeitherAnOrdinarySlipNorACorrection() {
    for (int status :
        new int[] {
          PeriodRepository.STATUS_TEMPORARILY_CLOSED, PeriodRepository.STATUS_CLOSURE_IN_PROGRESS
        }) {
      Service s = new Service();
      BgPaySlip ordinary = readySlip(s, status);
      AxelorException e = assertThrows(AxelorException.class, () -> s.confirm(ordinary));
      assertTrue(e.getMessage().contains("СЧЕТОВОДСТВОТО"), status + " → " + e.getMessage());
      assertTrue(e.getMessage().contains("НИТО редовен фиш"), status + " → " + e.getMessage());

      // ⚠️ И КОРЕКЦИЯТА. Тук е ОБРАТНОТО на заключения период: там корекцията е изходът и пазачът я
      // пуска, тук тя е проблемът — иска да премести статуса в ADJUSTING, тоест да се пресече с
      // чужда работа по същия период.
      BgPaySlip correcting = readySlip(s, status);
      correcting.setBgIsCorrection(true);
      correcting.setBgCorrectionOf(readySlip(s, status));
      AxelorException c = assertThrows(AxelorException.class, () -> s.confirm(correcting));
      assertTrue(c.getMessage().contains("СЧЕТОВОДСТВОТО"), status + " → " + c.getMessage());
      assertEquals(BgPaySlipResolver.DRAFT, correcting.getStatusSelect());
    }
  }

  /**
   * ЕДИНСТВЕНИЯТ ФАЙЛ, КОЙТО МОЖЕ ДА ПИТА {@code PeriodService} — счетоводната статия.
   *
   * <p>⚠️ Списък, а не изключване на цялата проверка: втори файл тук значи, че някой е разпръснал
   * периодния въпрос, и тогава пак трябва да се реши кой пазач важи.
   */
  private static final List<String> PERIOD_SERVICE_ALLOWED =
      List.of("BgPaySlipMoveService.java", "BgStockMoveGuard.java");

  /** Кодът без коментари — въпросът е дали ВИКА, а не дали прозата СПОМЕНАВА. */
  private static String stripped(Path file) throws IOException {
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        .replaceAll("(?s)/\\*.*?\\*/", " ")
        .replaceAll("//[^\n]*", " ");
  }

  @Test
  @DisplayName("⚠️ РОДНИЯТ Axelor НЕ пази този път — затова пазачът е наш, и това е измерено")
  void theNativeAxelorGuardDoesNotCoverThisPath() throws IOException {
    // ⚠️ Проверено в кода, не предположено. PeriodServiceImpl#isClosedPeriod слага в
    // „неоторизирани“ САМО TEMPORARILY_CLOSED и CLOSED — CLOSURE_IN_PROGRESS изобщо не е в списъка,
    // — важи за счетоводните записи (Move) и се стига през testOpenPeriod. Този модул НЕ ГО ВИКА
    // за ФИША. Тестът пази точно това: щом някой ден модулът започне да вика родната услуга по
    // пътя на фиша, редът долу ще го намери и ще принуди автора да реши кой от двата пазача важи,
    // вместо да остави два.
    //
    // ═══ И ЕДНО ИЗКЛЮЧЕНИЕ ═══
    //
    // ⚠️ ДВЕ ПЕРИОДНИ СТЪЛБИ, А НЕ ДВА ПАЗАЧА НА ЕДНО НЕЩО. Заплатната статия
    // (BgPaySlipMoveService)
    // вика PeriodService — и това НЕ е второто мнение за периода на фиша. Питаните са различни:
    //
    //   • refuseClosingPeriod пита ЗАПЛАТНИЯ период на ФИША (year.typeSelect = TYPE_PAYROLL) и
    //     отговорът му ОТКАЗВА потвърждаването — фиш в затварящ се месец не се издава;
    //   • PeriodService в статията пита ФИСКАЛНИЯ период за ДАТАТА (TYPE_FISCAL) — при ретро-фиша
    //     отговорът е обявена причина статия да няма, а при изплащане (статията се осчетоводява)
    //     е отказ на „Изплати“ с изречение. Заплатната и финансовата година се
    //     затварят поотделно и от различни хора, тоест едната не може да отговаря за другата.
    //
    // Затова изключението е ЕДИН файл и е закована И употребата му: само TYPE_FISCAL, и НЕ през
    // getActivePeriod — той ХВЪРЛЯ за затворен период родното си съобщение, вместо изречението с
    // датата. Точно това е разликата между „пазач“ и „втори пазач“.
    Path main = Paths.get("src/main/java");
    List<String> callers = new ArrayList<>();
    try (Stream<Path> files = Files.walk(main)) {
      for (Path f :
          files.filter(x -> x.toString().endsWith(".java")).collect(Collectors.toList())) {
        // ⚠️ КОМЕНТАРИТЕ СЕ МАХАТ ПРЕДИ ТЪРСЕНЕТО: въпросът е дали кодът ВИКА родната услуга, а
        // не дали прозата я СПОМЕНАВА. Написан наивно, тестът би паднал заради собствената си
        // документация — javadoc-ът на refuseClosingPeriod цитира PeriodServiceImpl#isClosedPeriod
        // поименно, защото точно това е измерването, което го оправдава.
        String src =
            new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
                .replaceAll("(?s)/\\*.*?\\*/", " ")
                .replaceAll("//[^\n]*", " ");
        // ⚠️ ТЪРСИ СЕ ДОСТЪП ДО ЧЛЕН, НЕ СПОМЕНАВАНЕ НА ТИПА. Голото contains("PeriodService")
        // хваща и вносния ред, и параметъра на конструктор, който само ПРЕПРЕДАВА инжектираната
        // услуга на базовия клас — точно каквото правят BgFixedAssetDateServiceImpl и
        // BgFixedAssetLineTaxServiceImpl, защото базовите им конструктори я искат. Въпросът, който
        // пазачът задава, е „вика ли модулът услугата“ — тоест точка след името или поименният
        // метод.
        if (src.contains("periodService.")
            || src.contains("PeriodService.")
            || src.contains("isClosedPeriod")) {
          callers.add(f.getFileName().toString());
        }
      }
    }
    callers.removeAll(PERIOD_SERVICE_ALLOWED);
    assertTrue(
        callers.isEmpty(),
        "модулът вече вика родната услуга за периоди ("
            + callers
            + ") — тогава двата пазача трябва да се сведат до един, инак единият ще пусне онова,"
            + " което другият спира. Позволен е САМО "
            + PERIOD_SERVICE_ALLOWED
            + ", и то за ФИСКАЛНАТА стълба");

    // ⚠️ И ПОЗВОЛЕНИЯТ Е ЗАКОВАН, инак изключението е бланков чек: щом само един файл може да пита
    // родната услуга, трябва да е измеримо КАКВО пита и КАК.
    String moveService =
        stripped(
            Paths.get(
                "src/main/java/com/axelor/apps/bglocalization/service/accounting/"
                    + "BgPaySlipMoveService.java"));
    assertTrue(
        moveService.contains("TYPE_FISCAL"),
        "статията трябва да пита ФИСКАЛНАТА стълба — заплатната е на refuseClosingPeriod");
    assertFalse(
        moveService.contains("TYPE_PAYROLL"),
        "статията НЕ бива да пита заплатния период — това е въпросът на фиша, не на нейния");
    assertFalse(
        moveService.contains("getActivePeriod"),
        "getActivePeriod ХВЪРЛЯ родното си съобщение за затворен период; статията пита"
            + " getPeriod + isClosedPeriod и дава собственото си изречение с датата");

    // ═══ ВТОРО ИЗКЛЮЧЕНИЕ (склад S0) — пак ФИСКАЛНАТА стълба, пак друг въпрос ═══
    //
    // BgStockMoveGuard пита дали ДАТАТА НА СКЛАДОВИЯ ДОКУМЕНТ (StockMove.bgDocumentDate, SAF-T
    // SD.MG.6) е в отворен ФИСКАЛЕН период и при „няма“ или „затворен“ ОТКАЗВА реализирането.
    // Това не е второ мнение за периода на фиша: няма БГ пазач на
    // складовата дата, с който да се разминава, а статусът — включително ролевото „временно
    // затворен“ на PeriodServiceAccountImpl — има един източник и той е родната услуга. Собствена
    // JPQL би била ТРЕТА логика за периода. Заковано като горното: само TYPE_FISCAL и без
    // getActivePeriod (той хвърля родното си съобщение вместо БГ отказа с датата).
    String stockGuard =
        stripped(
            Paths.get(
                "src/main/java/com/axelor/apps/bglocalization/service/stock/"
                    + "BgStockMoveGuard.java"));
    assertTrue(stockGuard.contains("TYPE_FISCAL"), "складът пита ФИСКАЛНАТА стълба");
    assertFalse(stockGuard.contains("TYPE_PAYROLL"), "складът НЕ пита заплатния период");
    assertFalse(
        stockGuard.contains("getActivePeriod"),
        "складът пита getPeriod + isClosedPeriod и дава собствения си отказ с датата");
    // „Временно затворен“ и „в затваряне“ спират и складовата дата — през СЪЩИЯ предикат като
    // фиша, не трета логика за периода.
    assertTrue(
        stockGuard.contains("BgPaySlipResolver.isClosingPeriod"),
        "складът пита периода в затваряне през предиката на фиша");
    assertFalse(
        stockGuard.contains("STATUS_TEMPORARILY_CLOSED")
            || stockGuard.contains("STATUS_CLOSURE_IN_PROGRESS"),
        "складът не изброява статусите сам — второ копие на предиката");
    String resolver =
        stripped(
            Paths.get(
                "src/main/java/com/axelor/apps/bglocalization/service/BgPaySlipResolver.java"));
    assertTrue(
        resolver.contains("if (!isClosingPeriod(period))"),
        "отказът на фиша минава през същия предикат");

    // И самото правило: CLOSURE_IN_PROGRESS се отказва ТУК, макар родният списък да го няма.
    Period closing = new Period();
    closing.setName("03/PY2026");
    closing.setStatusSelect(PeriodRepository.STATUS_CLOSURE_IN_PROGRESS);
    assertNotNull(BgPaySlipResolver.refuseClosingPeriod(closing));
    Period tempClosed = new Period();
    tempClosed.setName("03/PY2026");
    tempClosed.setStatusSelect(PeriodRepository.STATUS_TEMPORARILY_CLOSED);
    assertNotNull(BgPaySlipResolver.refuseClosingPeriod(tempClosed));

    // а отворен, заключен и под корекция минават оттук — те имат СВОИ отговори
    for (int ok :
        new int[] {
          PeriodRepository.STATUS_OPENED,
          PeriodRepository.STATUS_CLOSED,
          PeriodRepository.STATUS_ADJUSTING
        }) {
      Period p = new Period();
      p.setStatusSelect(ok);
      assertNull(BgPaySlipResolver.refuseClosingPeriod(p), "статус " + ok);
    }
    assertNull(BgPaySlipResolver.refuseClosingPeriod(null));
    assertTrue(BgPaySlipResolver.isClosingPeriod(closing));
    assertTrue(BgPaySlipResolver.isClosingPeriod(tempClosed));
    assertFalse(BgPaySlipResolver.isClosingPeriod(null));
  }

  // ── 4 · самото правило, статус по статус ────────────────────────────────

  @Test
  @DisplayName("⚠️ Заключен е САМО CLOSED — останалите четири статуса не са")
  void onlyClosedCountsAsLocked() {
    assertTrue(locked(PeriodRepository.STATUS_CLOSED), "CLOSED е ключът (А)");
    assertFalse(locked(PeriodRepository.STATUS_OPENED), "OPENED");
    assertFalse(locked(PeriodRepository.STATUS_ADJUSTING), "ADJUSTING — прозорецът на корекцията");
    assertFalse(
        locked(PeriodRepository.STATUS_TEMPORARILY_CLOSED),
        "TEMPORARILY_CLOSED минава през ДРУГА врата: корекцията там се отказва с „координирайте се"
            + " със счетоводството“, а не с „ползвайте корекция“");
    assertFalse(locked(PeriodRepository.STATUS_CLOSURE_IN_PROGRESS), "CLOSURE_IN_PROGRESS — също");
    assertFalse(BgPaySlipResolver.isPeriodLocked(null), "липсващ период не е заключен период");
  }

  private static boolean locked(int status) {
    Period p = new Period();
    p.setStatusSelect(status);
    return BgPaySlipResolver.isPeriodLocked(p);
  }

  @Test
  @DisplayName("Празният фиш и фишът без период не стигат до този отказ")
  void theRefusalDoesNotSpeakWithoutAPeriod() {
    assertNull(BgPaySlipResolver.refuseLockedPeriod(null));
    assertNull(BgPaySlipResolver.refuseLockedPeriod(new BgPaySlip()), "фиш без период");
  }

  // ── 5 · ключ (Б) вече е построен, и вратата тук е ОБЯВЕНО половината ────

  private static final Path RESOLVER =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/service/BgPaySlipResolver.java");

  @Test
  @DisplayName("⚠️ Едноаргументната врата е ОБЯВЕНО само ключ (А), а пълното правило е назовано")
  void theOneArgDoorDeclaresItselfHalfTheRule() throws IOException {
    String source = new String(Files.readAllBytes(RESOLVER), StandardCharsets.UTF_8);
    int at = source.indexOf("boolean isPeriodLocked");
    assertTrue(at > 0, "isPeriodLocked изчезна от резолвера");

    // ⚠️ Взима се СОБСТВЕНИЯТ javadoc на функцията, а не „някъде наоколо“: границите се търсят от
    // подписа назад, тоест проверката оцелява при пренареждане на файла и при spotlessApply, който
    // пренася коментарите. Прозорец от N знака би минал зелено заради съседен коментар.
    int close = source.lastIndexOf("*/", at);
    int open = source.lastIndexOf("/**", close);
    assertTrue(open > 0 && close > open, "isPeriodLocked няма javadoc изобщо");
    String javadoc = source.substring(open, close);
    assertTrue(
        javadoc.contains("ПОЛОВИНАТА ОТ ПРАВИЛОТО"),
        "⚠️ Тази врата отговаря САМО за ключ (А) — статуса CLOSED. Ключ (Б) на спецификацията"
            + " (подадена Д1/Д6 за периода) е построен, но живее в двуаргументната врата, защото"
            + " иска заявка. Без записано разграничение следващият читател ще прочете половин"
            + " правило като цяло: период, който е OPENED, но чиято декларация е подадена, за тази"
            + " функция НЕ е заключен.");
    assertTrue(
        javadoc.contains("isPeriodLocked(Period, boolean)"),
        "⚠️ Половината правило трябва да СОЧИ другата половина. Иначе повикващ, който пита оттук,"
            + " няма как да разбере, че съществува втори ключ — и ще пропусне декларирания месец.");

    int full = source.indexOf("boolean isPeriodLocked(Period period, boolean declaredToNap)");
    assertTrue(full > at, "пълното правило (А ИЛИ Б) изчезна от резолвера");
  }
}
