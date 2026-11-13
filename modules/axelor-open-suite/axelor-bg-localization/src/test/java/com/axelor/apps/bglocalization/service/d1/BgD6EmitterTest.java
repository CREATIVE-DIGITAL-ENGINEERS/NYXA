package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ДЕКЛАРАЦИЯ ОБР. 6: агрегатът на дружеството, и трите места, където групирането ѝ НЕ е огледално
 * на Д1.
 *
 * <p><b>Двата фиша са РАЗЛИЧНИ нарочно.</b> При еднакви фишове сбор на два и сбор на един се
 * различават точно наполовина — а „наполовина“ изглежда като друга ставка, не като липсващ фиш. С
 * различни числа всяка грешка в подбора дава сума, която не прилича на нищо.
 */
class BgD6EmitterTest {

  private static final BgD6Emitter EMITTER = new BgD6Emitter();

  private static BgContributionFund fund(String code, String d1Point) {
    BgContributionFund f = new BgContributionFund();
    f.setCode(code);
    f.setBgD1Field(d1Point);
    return f;
  }

  /** Перо на вноска: сума, фонд и (по избор) база на здравното. */
  private static BgPaySlipLine line(
      int number, String sign, String amount, BgContributionFund f, String healthBase) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(number);
    l.setBgSign(sign);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgContributionFund(f);
    l.setBgHealthBaseKind(healthBase);
    return l;
  }

  /** Перо на ДАНЪК — група 7xx, знак DEBIT. */
  private static BgPaySlipLine taxLine(String amount) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(700);
    l.setBgSign(BgPaySlipResolver.DEBIT);
    l.setBgAmount(new BigDecimal(amount));
    return l;
  }

  /**
   * ФИШ 1 · пълен набор: ДОО (три фонда, двата платеца), ТЗПБ, УПФ, ППФ, УчПФ, ГВРС, данък и ТРИТЕ
   * здравни бази.
   */
  private static BgPaySlip slipOne() {
    BgContributionFund pension = fund("PENSION", BgD1Point.T22_DOO);
    BgContributionFund ozm = fund("OZM", BgD1Point.T22_DOO);
    BgContributionFund unemployment = fund("UNEMPLOYMENT", BgD1Point.T22_DOO);
    BgContributionFund tzpb = fund("TZPB", BgD1Point.T22_2_TZPB);
    BgContributionFund upf = fund("UPF", BgD1Point.T26_UPF);
    BgContributionFund ppf = fund("PPF", BgD1Point.T25_PPF);
    BgContributionFund teacher = fund("TEACHER_PF", BgD1Point.T23_TEACHER_PF);
    BgContributionFund gvrs = fund("GVRS", BgD1Point.T30_GVRS);
    BgContributionFund health = fund("HEALTH", BgD1Point.T22_1_HEALTH);

    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(line(500, BgPaySlipResolver.CREDIT, "164.40", pension, null));
    lines.add(line(501, BgPaySlipResolver.DEBIT, "131.60", pension, null));
    lines.add(line(504, BgPaySlipResolver.CREDIT, "42.00", ozm, null));
    lines.add(line(505, BgPaySlipResolver.DEBIT, "28.00", ozm, null));
    lines.add(line(506, BgPaySlipResolver.CREDIT, "12.00", unemployment, null));
    lines.add(line(507, BgPaySlipResolver.DEBIT, "8.00", unemployment, null));
    lines.add(line(508, BgPaySlipResolver.CREDIT, "9.40", tzpb, null));
    lines.add(line(510, BgPaySlipResolver.CREDIT, "56.00", upf, null));
    lines.add(line(511, BgPaySlipResolver.DEBIT, "44.00", upf, null));
    lines.add(line(514, BgPaySlipResolver.CREDIT, "84.00", ppf, null));
    lines.add(line(518, BgPaySlipResolver.CREDIT, "23.60", teacher, null));
    lines.add(line(520, BgPaySlipResolver.CREDIT, "1.20", gvrs, null));

    // Трите здравни бази — по едно перо на всяка.
    lines.add(line(516, BgPaySlipResolver.CREDIT, "96.00", health, null));
    lines.add(line(517, BgPaySlipResolver.DEBIT, "64.00", health, null));
    lines.add(
        line(
            516,
            BgPaySlipResolver.CREDIT,
            "24.13",
            health,
            BgD1ContributionAggregator.BASE_ART40_1_5));
    lines.add(
        line(
            517,
            BgPaySlipResolver.DEBIT,
            "24.81",
            health,
            BgD1ContributionAggregator.BASE_HEALTH_ONLY));

    lines.add(taxLine("176.42"));

    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-2026-05-0001");
    slip.setBgPaySlipLineList(lines);
    return slip;
  }

  /** ФИШ 2 · само ДОО, здравно и данък — с РАЗЛИЧНИ числа. */
  private static BgPaySlip slipTwo() {
    BgContributionFund pension = fund("PENSION", BgD1Point.T22_DOO);
    BgContributionFund health = fund("HEALTH", BgD1Point.T22_1_HEALTH);

    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(line(500, BgPaySlipResolver.CREDIT, "82.20", pension, null));
    lines.add(line(501, BgPaySlipResolver.DEBIT, "65.80", pension, null));
    lines.add(line(516, BgPaySlipResolver.CREDIT, "48.00", health, null));
    lines.add(line(517, BgPaySlipResolver.DEBIT, "32.00", health, null));
    lines.add(taxLine("88.21"));

    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-2026-05-0002");
    slip.setBgPaySlipLineList(lines);
    return slip;
  }

  private static BgD6Declaration declaration() {
    Partner partner = new Partner();
    partner.setName("Никса ЕООД");
    partner.setRegistrationCode("831919536");
    partner.setTaxNbr("BG831919536");

    Company company = new Company();
    company.setPartner(partner);

    BgD6Declaration d = new BgD6Declaration();
    d.setCompany(company);
    return d;
  }

  private static List<String> codes(List<BgD1FindingDto> findings) {
    List<String> out = new ArrayList<>();
    for (BgD1FindingDto f : findings) {
      out.add(f.getCode());
    }
    return out;
  }

  // ═══ СВИДЕТЕЛИТЕ ══════════════════════════════════════════════════════════

  @Test
  @DisplayName("т. 14 · СБОРЪТ на здравните вноски на ДВА фиша ПРЕЗ ТРИТЕ БАЗИ")
  void point14SumsAllHealthContributionsAcrossBothSlipsAndAllThreeBases() {
    BgD6Declaration d = declaration();
    EMITTER.emit(d, Arrays.asList(slipOne(), slipTwo()));

    // Фиш 1: 96,00 + 64,00 (нормални) + 24,13 (чл. 40-5) + 24,81 (само здравно) = 208,94
    // Фиш 2: 48,00 + 32,00 = 80,00
    assertEquals(
        0,
        new BigDecimal("288.94").compareTo(d.getT14Health()),
        "указание т. 14: „сумата на дължимите осигурителни вноски за здравно осигуряване за ВСИЧКИ"
            + " осигурени лица“ — без разграничение по база.\n"
            + "  ⚠️ 240,00 би било САМО нормалните бази, тоест точно грешката „взех т. 22.1 от Д1“;\n"
            + "  208,94 би било само първия фиш.");
  }

  @Test
  @DisplayName("⚠️ т. 10 · ТЗПБ Е ВЪТРЕ — Д6 групира по-широко от Д1 т. 22")
  void point10IncludesTzpbUnlikeD1() {
    BgD6Declaration d = declaration();
    EMITTER.emit(d, Collections.singletonList(slipOne()));

    // 164,40 + 131,60 + 42,00 + 28,00 + 12,00 + 8,00 = 386,00 (ДОО без ТЗПБ) + 9,40 (ТЗПБ)
    assertEquals(
        0,
        new BigDecimal("395.40").compareTo(d.getT10Doo()),
        "указание т. 10: „сумата на дължимите осигурителни вноски за фондовете на ДОО за всички"
            + " осигурени лица“ — БЕЗ изключение.\n"
            + "  ⚠️ 386,00 е числото, което пренасяне едно към едно от Д1 би дало: т. 22 е"
            + " формулирана „за фондовете на ДОО БЕЗ ТЗПБ“ и дава на ТЗПБ СВОЯ точка (22.2)."
            + " Разликата е от порядъка на половин процент — правдоподобна, и точно заради нея"
            + " равенството по чл. 2 ал. 4 не би се затворило.");
  }

  @Test
  @DisplayName("⚠️ т. 12 и т. 13 · УПФ и ППФ са РАЗМЕНЕНИ спрямо номерата в Д1")
  void theDzpoPointsAreSwappedRelativeToD1() {
    BgD6Declaration d = declaration();
    EMITTER.emit(d, Collections.singletonList(slipOne()));

    assertEquals(
        0,
        new BigDecimal("100.00").compareTo(d.getT12Upf()),
        "т. 12 на Д6 е УПФ (56,00 + 44,00), а в Д1 УПФ е т. 26");
    assertEquals(
        0,
        new BigDecimal("84.00").compareTo(d.getT13Ppf()),
        "⚠️ т. 13 на Д6 е ППФ, а в Д1 ППФ е т. 25 — тоест по-МАЛКИЯТ номер. Пренесени ПО НОМЕР"
            + " вместо по ФОНД, двете се разменят, а сумите им са от един и същи порядък.");
  }

  @Test
  @DisplayName("т. 11, т. 15, т. 16 · УчПФ, ГВРС и данъкът, всяко на своя ред")
  void theRemainingPointsLandWhereTheyBelong() {
    BgD6Declaration d = declaration();
    EMITTER.emit(d, Arrays.asList(slipOne(), slipTwo()));

    assertEquals(0, new BigDecimal("23.60").compareTo(d.getT11TeacherPf()), "УчПФ → т. 11");
    assertEquals(0, new BigDecimal("1.20").compareTo(d.getT15Gvrs()), "ГВРС → т. 15");
    assertEquals(
        0,
        new BigDecimal("264.63").compareTo(d.getT16Tax()),
        "176,42 + 88,21 — удържаният данък на ДВАТА фиша. Указание т. 16: „сумата на УДЪРЖАНИЯ през"
            + " съответния месец данък“.");
  }

  @Test
  @DisplayName("т. 1 · ЕИК по БУЛСТАТ, а НЕ ДДС номерът — същият капан като при т. 4 на Д1")
  void point1IsTheBulstatCodeNotTheVatNumber() {
    BgD6Declaration d = declaration();
    EMITTER.emit(d, Collections.singletonList(slipOne()));
    assertEquals("831919536", d.getT1ObligedPersonCode());
    assertTrue(
        !"BG831919536".equals(d.getT1ObligedPersonCode()),
        "ДДС-стекът чете taxNbr и по инерция т. 1 би носила ДДС номера");
    assertEquals("Никса ЕООД", d.getT2Name(), "т. 2 е наименованието на задълженото лице");
  }

  @Test
  @DisplayName("ЧЛ. 2 АЛ. 4 · двата независими пътя до т. 14 дават ЕДНО число")
  void theTwoIndependentPathsToPoint14Agree() {
    BgD6Declaration d = declaration();
    List<BgPaySlip> slips = Arrays.asList(slipOne(), slipTwo());
    EMITTER.emit(d, slips);

    assertTrue(
        !codes(EMITTER.checkEquality(d, slips, Collections.emptyList()))
            .contains("D6_HEALTH_EQUALITY_BROKEN"),
        "Д6 стига до т. 14 през ФОНДА, а Д1 — през БАЗАТА на перото. Двата пътя са различни НАРОЧНО"
            + " и трябва да дадат едно число.");
    assertEquals(
        0,
        d.getT14Health().compareTo(BgD6Aggregator.healthViaD1Points(slips)),
        "и числата се сравняват директно, не само през находката");
  }

  @Test
  @DisplayName("⚠️ ПОГРЕШНО НАПИСАНА БАЗА · вноската НЕ се губи — тя пада на ГРЕШНАТА точка")
  void aMistypedBaseFallsBackToPoint22_1InsteadOfDisappearing() {
    BgPaySlip broken = slipOne();
    for (BgPaySlipLine l : broken.getBgPaySlipLineList()) {
      if (BgD1ContributionAggregator.BASE_ART40_1_5.equals(l.getBgHealthBaseKind())) {
        // Липсва подчертавката. Низът минава всяка проверка за формат и всеки визуален преглед.
        l.setBgHealthBaseKind("ART40_15");
      }
    }
    List<BgPaySlip> slips = Collections.singletonList(broken);

    BgD6Declaration d = declaration();
    EMITTER.emit(d, slips);

    // ⚠️ И ДВЕТЕ СТРАНИ ДАВАТ ЕДНО И СЪЩО ЧИСЛО, тоест равенството по чл. 2 ал. 4 СЕ ЗАТВАРЯ.
    assertEquals(
        0,
        new BigDecimal("208.94").compareTo(d.getT14Health()),
        "Д6 групира по ФОНД и не пита за базата — тя е непокътната");
    assertEquals(
        0,
        new BigDecimal("208.94").compareTo(BgD6Aggregator.healthViaD1Points(slips)),
        "⚠️ И ПРЕЗ ТОЧКИТЕ НА Д1 СБОРЪТ Е СЪЩИЯТ, защото непознатата база пада в т. 22.1 ПО"
            + " ПОДРАЗБИРАНЕ. Загуба НЯМА — тихото падане е по-безопасно от изчезване, — но"
            + " вноската стои на ГРЕШНА ТОЧКА: 24,13 се озовава в т. 22.1 вместо в т. 18.");
    assertTrue(
        !codes(EMITTER.checkEquality(d, slips, Collections.emptyList()))
            .contains("D6_HEALTH_EQUALITY_BROKEN"),
        "⚠️ ЗАТОВА РАВЕНСТВОТО НЕ Я ХВАЩА, и това е измерено, не предположено: сборовете"
            + " съвпадат. Проверката, която НАИСТИНА я вижда, е"
            + " BgD1Validator.UNKNOWN_HEALTH_BASE, защото само тя пита ПЕРОТО за стойността му.");
  }

  @Test
  @DisplayName("⚠️ ЧЛ. 2 АЛ. 4 · равенството хваща онова, което дискриминаторът НЕ може да скрие")
  void theEqualityCheckCatchesALostFund() {
    // Перо на здравно БЕЗ препратка към фонд — тоест изпаднало от групирането и на двете страни.
    BgPaySlip broken = slipTwo();
    List<BgPaySlipLine> lines = new ArrayList<>(broken.getBgPaySlipLineList());
    BgPaySlipLine orphan = new BgPaySlipLine();
    orphan.setBgLineNumber(516);
    orphan.setBgSign(BgPaySlipResolver.CREDIT);
    orphan.setBgAmount(new BigDecimal("12.34"));
    lines.add(orphan);
    broken.setBgPaySlipLineList(lines);

    BgD6Declaration d = declaration();
    EMITTER.emit(d, Collections.singletonList(broken));
    assertEquals(
        0,
        new BigDecimal("80.00").compareTo(d.getT14Health()),
        "перо без фонд не влиза в НИТО ЕДНА точка — и на двете страни. Тоест 12,34 е извън"
            + " декларацията, а нито Д1, нито Д6 го знае. Равенството също не го вижда: то сравнява"
            + " ДВА ПЪТЯ НАД ЕДНИ И СЪЩИ ПЕРА, а това перо е изпаднало и от двата.");
    assertTrue(
        !codes(EMITTER.checkEquality(d, Collections.singletonList(broken), Collections.emptyList()))
            .contains("D6_HEALTH_EQUALITY_BROKEN"),
        "⚠️ ГРАНИЦАТА НА САМАТА ПРОВЕРКА, ЗАПИСАНА ЧЕСТНО: равенството по чл. 2 ал. 4 пази СЪГЛАСИЕТО"
            + " между двете декларации, НЕ пълнотата им. Перо без фонд се хваща другаде — от"
            + " BgD1Validator.T21_BASE_WITHOUT_T22_RATE и от пазачите при потвърждаване на фиша.");
  }

  @Test
  @DisplayName(
      "т. 17 · ХИПОТЕЗАТА няма носител → остава празна и това се ОБЯВЯВА при всяко емитиране")
  void point17DeclaresItsMissingCarrier() {
    BgD6Declaration d = declaration();
    BgD6Emitter.Outcome outcome = EMITTER.emit(d, Collections.singletonList(slipOne()));

    assertEquals(
        0,
        d.getT17AdvanceTax().signum(),
        "⚠️ signum(), не assertNull: генерираният getter за BigDecimal връща ZERO");
    assertTrue(
        codes(outcome.getFindings()).contains("D6_T17_NO_CARRIER"),
        "указанието разделя двете точки изрично: „Не се включва данъкът по т. 17.“ Тоест празната"
            + " т. 17 НЕ е нула и не бива да се слее с т. 16. Находки: "
            + codes(outcome.getFindings()));
    assertTrue(
        outcome.findingReport().contains("чл. 42 ал. 8"),
        "⚠️ И ПРИЧИНАТА НАЗОВАВА КОЙ ФАКТ ЛИПСВА, а не просто „няма носител“: ал. 8 изключва"
            + " хипотезата, ако пълният размер е изплатен до 25-о число на СЛЕДВАЩИЯ месец — тоест"
            + " признакът зависи от събитие СЛЕД този фиш и никаква сметка върху перата не го знае."
            + " Доклад: "
            + outcome.findingReport());
  }

  @Test
  @DisplayName("⚠️ т. 17 · КАНДИДАТЪТ за база СЕ ПОКАЗВА — човекът не попълва наслуки")
  void point17NamesTheCandidateBase() {
    BgPaySlip slip = slipOne();
    List<BgPaySlipLine> lines = new ArrayList<>(slip.getBgPaySlipLineList());
    // Перо 610 „Частично плащане по банков път“ — точно частичното плащане по чл. 42 ал. 6.
    BgPaySlipLine partial = new BgPaySlipLine();
    partial.setBgLineNumber(610);
    partial.setBgSign(BgPaySlipResolver.DEBIT);
    partial.setBgAmount(new BigDecimal("500.00"));
    lines.add(partial);
    slip.setBgPaySlipLineList(lines);

    BgD6Emitter.Outcome outcome = EMITTER.emit(declaration(), Collections.singletonList(slip));

    assertTrue(
        outcome.findingReport().contains("500.00"),
        "⚠️ СУМАТА Е ОПРЕДЕЛИМА, ХИПОТЕЗАТА — НЕ, и двете не бива да се смесват. Ал. 6 иска данъка"
            + " върху БРУТНИЯ размер на частичните плащания; сборът на перата 600/610 Е той. Показан,"
            + " той спестява на човека втора сметка; попълнен автоматично, той щеше да носи данък,"
            + " който при плащане до 25-о число НЕ се дължи и се приспада два пъти по ал. 7."
            + " Доклад: "
            + outcome.findingReport());
    assertEquals(
        0,
        BgD6Aggregator.partialPayments(Collections.singletonList(slip))
            .compareTo(new BigDecimal("500.00")),
        "⚠️ САМО DEBIT перата от 6xx: групата носи и перо 620 („доплащане по корекция за минал"
            + " период“), което е CREDIT и е НАЧИСЛЕНИЕ, не плащане.");
  }

  @Test
  @DisplayName("ФИЛТЪР НА КОРЕКЦИЯТА · делта-ред за друг месец НЕ влиза в агрегата")
  void theRetroDeltaStaysOutOfTheAggregate() {
    BgPaySlip slip = slipTwo();
    List<BgPaySlipLine> lines = new ArrayList<>(slip.getBgPaySlipLineList());
    BgPaySlipLine delta =
        line(500, BgPaySlipResolver.CREDIT, "40.75", fund("PENSION", BgD1Point.T22_DOO), null);
    com.axelor.apps.base.db.Period march = new com.axelor.apps.base.db.Period();
    march.setFromDate(java.time.LocalDate.of(2026, 3, 1));
    delta.setBgCorrectsPeriod(march);
    lines.add(delta);
    slip.setBgPaySlipLineList(lines);

    BgD6Declaration d = declaration();
    EMITTER.emit(d, Collections.singletonList(slip));
    assertEquals(
        0,
        new BigDecimal("148.00").compareTo(d.getT10Doo()),
        "82,20 + 65,80. Мартенската делта вече е декларирана с коригиращата Д6 ЗА МАРТ — броена и"
            + " тук, същата вноска се дължи два пъти. 188,75 е числото, което грешката би дала.");
  }

  @Test
  @DisplayName("непознат фонд НЕ пада в т. 10 по подразбиране — той просто не се групира")
  void anUnknownFundIsNotSilentlyCountedAsDoo() {
    assertEquals(
        null,
        BgD6Point.forFund("SOME_NEW_FUND"),
        "⚠️ „Всичко неразпознато е ДОО“ би скрило нов фонд в НАЙ-ГОЛЯМОТО число на декларацията,"
            + " където разликата не се вижда.");
    assertEquals(BgD6Point.T10_DOO, BgD6Point.forFund("TZPB"), "а ТЗПБ Е ДОО за Д6");
    assertEquals(
        6,
        BgD6Point.CONTRIBUTION_POINTS.size(),
        "шест точки с вноски; данъчните две не са вноски: " + BgD6Point.CONTRIBUTION_POINTS);
  }

  @Test
  @DisplayName("нула фишове · всички точки остават празни, и нито една не става нула")
  void noSlipsLeaveEveryPointEmpty() {
    Map<String, BigDecimal> byPoint = BgD6Aggregator.byPoint(Collections.emptyList());
    assertTrue(
        byPoint.isEmpty(),
        "липсваща точка значи „нито един фиш не носи такава вноска“ — различно от „вноската е нула“."
            + " Декларация с нули за дружество без фишове е ПОДАДЕНО задължение нула, а не липса на"
            + " задължение.");
    assertTrue(BgD6Aggregator.byPoint(null).isEmpty(), "и null не гърми");
  }
}
