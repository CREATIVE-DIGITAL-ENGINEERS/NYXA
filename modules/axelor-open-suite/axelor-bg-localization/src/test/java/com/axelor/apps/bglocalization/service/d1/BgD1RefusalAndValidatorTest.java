package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОТКАЗИТЕ С НАЗОВАНА ПРИЧИНА и правилата, които наредбата ДАВА.
 *
 * <p><b>Защо отказът е свидетел, а не подробност.</b> Мълчаливо прескочен фиш е най-скъпият дефект
 * в този емитер: декларацията изглежда пълна, а едно лице липсва в нея — и това се открива от НАП,
 * не от нас. Затова всеки отказ тук се проверява ДВА пъти: че е отказ, и че причината е ЧЕТИМА и
 * назовава нормата.
 */
class BgD1RefusalAndValidatorTest {

  private static final BgD1Validator VALIDATOR = new BgD1Validator();

  /** Речникът дава ГВРС за трудовите правоотношения — тук същото, но нарочно тясно. */
  private static final Set<String> GVRS_CODES =
      new HashSet<>(Arrays.asList("01", "02", "03", "04"));

  private static BgInsuredType type(String code) {
    BgInsuredType t = new BgInsuredType();
    t.setCode(code);
    return t;
  }

  /** Минималният ВАЛИДЕН запис — за да е ясно, че червеното идва от онова, което тестът мени. */
  private static BgD1Record valid(String insuredCode) {
    BgD1Record r = new BgD1Record();
    r.setT2Month("05");
    r.setT3Year("2026");
    r.setT4ObligedPersonCode("831919536");
    r.setT5PersonalId("7501014567");
    r.setT51PersonalIdCode("0");
    r.setT6Surname("Петров");
    r.setT7Initials("ИГ");
    r.setT12InsuredTypeCode(insuredCode);
    return r;
  }

  /**
   * Перо на вноска, което носи СТАВКА (не сума) — точно както го пише {@code
   * BgContributionLineService#upsert}.
   *
   * <p>⚠️ {@code bgLineNumber} е в диапазона 5xx, защото {@code BgPaySlipResolver#groupOf} чете
   * ПЪРВАТА ЦИФРА на номера — перо с номер извън 5xx би било невидимо за агрегатора и тестът щеше
   * да мине зелен, без нищо да е събрано.
   */
  private static BgPaySlipLine contributionLine(String sign, String ratePercent, String point) {
    BgContributionFund fund = new BgContributionFund();
    fund.setBgD1Field(point);

    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(508);
    line.setBgSign(sign);
    line.setBgAmount(new BigDecimal("9.40"));
    line.setBgRate(new BigDecimal(ratePercent));
    line.setBgContributionFund(fund);
    return line;
  }

  private static List<String> codes(List<BgD1FindingDto> findings) {
    return findings.stream().map(BgD1FindingDto::getCode).collect(Collectors.toList());
  }

  private static List<String> pointsWith(List<BgD1FindingDto> findings, String code) {
    return findings.stream()
        .filter(f -> code.equals(f.getCode()))
        .map(BgD1FindingDto::getPoint)
        .collect(Collectors.toList());
  }

  // ═══ ОТКАЗИТЕ ═══

  @Test
  @DisplayName("чл. 4 ал. 10 · кодове 15, 88 и 89 се ОТКАЗВАТ — данните им подава НОИ")
  void theThreeNoiCodesAreRefusedWithANamedReason() {
    for (String code : Arrays.asList("15", "88", "89")) {
      String reason = BgD1InsuredTypeRules.refusalReason(code, type(code));
      assertNotNull(
          reason,
          "вид осигурен "
              + code
              + " трябва да се ОТКАЗВА: по чл. 4 ал. 10 т. 1 данните за него подава Националният"
              + " осигурителен институт, не работодателят. Мълчаливо произведен запис за него е"
              + " дублирано подаване.");
      assertTrue(
          reason.contains("чл. 4 ал. 10"),
          "причината трябва да НАЗОВАВА нормата, а не да е код за грешка. Получено: " + reason);
      assertTrue(
          reason.contains("Националния осигурителен институт")
              || reason.contains("осигурителен институт"),
          "причината трябва да казва КОЙ ги подава вместо нас. Получено: " + reason);
    }
  }

  @Test
  @DisplayName(
      "чл. 4 ал. 10 · речникът ги НОСИ, и това е вярно — не сме им подател, но кодът им е валиден")
  void theNoiCodesRemainInTheDictionaryOnPurpose() {
    // Отказът е на ЕМИТЕРА, не на речника: коригираща Д1 за минал период може да ги срещне, а
    // лицето с такъв код има вид осигурен независимо кой го декларира.
    assertNull(
        BgD1InsuredTypeRules.refusalReason("01", type("01")),
        "обикновен код не бива да се отказва");
    assertTrue(
        BgD1InsuredTypeRules.NOI_SUBMITTED_CODES.containsAll(Arrays.asList("15", "88", "89")),
        "трите кода са едно множество и се четат от едно място");
    assertEquals(3, BgD1InsuredTypeRules.NOI_SUBMITTED_CODES.size());
  }

  @Test
  @DisplayName("несъпоставен вид осигурен · ОТКАЗ, не тихо подразбиране")
  void anUnmappedInsuredTypeIsRefusedRatherThanDefaulted() {
    String reason = BgD1InsuredTypeRules.refusalReason("77", null);
    assertNotNull(
        reason,
        "код, който го няма в речника за този период, няма известен режим. Подразбирания биха"
            + " дали правдоподобен запис за лице, което не познаваме — най-тихата възможна грешка.");
    assertTrue(reason.contains("речника"), "причината сочи КЪДЕ липсва: " + reason);
  }

  @Test
  @DisplayName("липсващ вид осигурен · ОТКАЗ — без т. 12 не се знае кои точки изобщо се попълват")
  void aMissingInsuredTypeIsRefused() {
    assertNotNull(BgD1InsuredTypeRules.refusalReason(null, null));
    assertNotNull(BgD1InsuredTypeRules.refusalReason("   ", null));
  }

  // ═══ ЧЛ. 11 АЛ. 1 ═══

  @Test
  @DisplayName("чл. 11 ал. 1 · без ЕИК, без ЕГН или без месец декларацията НЕ се приема")
  void theThreeMandatoryFieldsOfArticle11() {
    BgD1Record noEik = valid("01");
    noEik.setT4ObligedPersonCode(null);
    assertTrue(
        codes(VALIDATOR.validate(noEik, GVRS_CODES)).contains(BgD1Validator.CHL11_AL1),
        "„Не се приемат декларации …, които не съдържат ЕИК по БУЛСТАТ/ЕИК по ЗТРРЮЛНЦ…“");

    BgD1Record noEgn = valid("01");
    noEgn.setT5PersonalId("  ");
    assertTrue(
        codes(VALIDATOR.validate(noEgn, GVRS_CODES)).contains(BgD1Validator.CHL11_AL1),
        "празен низ е същото като липса — чл. 11 ал. 1 гледа съдържанието, не колоната");

    BgD1Record noMonth = valid("01");
    noMonth.setT2Month(null);
    assertTrue(
        codes(VALIDATOR.validate(noMonth, GVRS_CODES)).contains(BgD1Validator.CHL11_AL1),
        "„…месеца, за който се отнасят данните…“");

    assertTrue(
        VALIDATOR.validate(valid("01"), GVRS_CODES).isEmpty(),
        "пълният запис не бива да ражда находки — инак валидаторът приучава да не се гледа");
  }

  // ═══ КОД 28 ═══

  @Test
  @DisplayName("код 28 · попълват се САМО единайсетте точки; т. 21 и т. 22 са нарушение")
  void code28AllowsOnlyItsElevenFields() {
    BgD1Record record = valid("28");
    record.setT21InsuranceIncome(new BigDecimal("2000.00"));
    record.setT22DooRateEmployer(new BigDecimal("10.92"));

    List<BgD1FindingDto> findings = VALIDATOR.validate(record, GVRS_CODES);
    List<String> offending = pointsWith(findings, BgD1Validator.CODE_28_EXTRA);

    assertTrue(
        offending.contains("21") && offending.contains("22"),
        "„Когато в т. 12 „Вид осигурен“ е попълнен код 28, се попълват т. 2, 3, 4, 5, 5.1, 6, 7,"
            + " 14, 15, 27 и 28.“ Код 28 е лице САМО със здравни вноски върху особена база —"
            + " попълнена т. 21 не греши в аритметиката, а твърди нещо за режима на лицето."
            + " Нарушени точки: "
            + offending);
    assertEquals(
        11,
        BgD1InsuredTypeRules.CODE_28_FIELDS.size(),
        "единайсет точки, преброени по списъка на наредбата");
  }

  @Test
  @DisplayName("код 28 · идентификационните точки НЕ са нарушение — те са в списъка")
  void code28DoesNotComplainAboutItsOwnAllowedFields() {
    List<BgD1FindingDto> findings = VALIDATOR.validate(valid("28"), GVRS_CODES);
    assertTrue(
        pointsWith(findings, BgD1Validator.CODE_28_EXTRA).isEmpty(),
        "т. 2, 3, 4, 5, 5.1, 6 и 7 са ИЗБРОЕНИ за код 28. Валидатор, който ги обявява за"
            + " нарушение, спира верния запис.");
  }

  // ═══ КОД 90 ═══

  @Test
  @DisplayName("код 90 · само т. 31 и 31а; всичко друго е нарушение, а т. 16 не се попълва")
  void code90AllowsOnlyTheTwoTaxPoints() {
    BgD1Record record = valid("90");
    record.setT31aTax(new BigDecimal("176.42"));
    record.setT21InsuranceIncome(new BigDecimal("2000.00"));

    List<String> offending =
        pointsWith(VALIDATOR.validate(record, GVRS_CODES), BgD1Validator.CODE_90_EXTRA);

    assertTrue(
        offending.contains("21"),
        "при код 90 се попълват само т. 31 и 31а (заб. 3 към т. 16 + списъкът на т. 31)."
            + " Нарушени: "
            + offending);
    assertTrue(
        !offending.contains(BgD1Point.T31A_TAX),
        "⚠️ т. 31а Е разрешена. Ако тя се появи тук, сравнението е паднало върху хомоглиф:"
            + " кирилското „а“ (U+0430) срещу латинското „a“ (U+0061) — двата низа се изписват"
            + " еднакво, а equals() е невярно.");
  }

  @Test
  @DisplayName("т. 31а · номерът е с КИРИЛСКО „а“ — U+0430, не латинско a")
  void the31aPointNumberIsCyrillic() {
    assertEquals(0x0430, BgD1Point.T31A_TAX.charAt(2), "„31а“ трябва да завършва на U+0430");
    assertTrue(!"31a".equals(BgD1Point.T31A_TAX), "латинското „31a“ е ДРУГ низ");
  }

  // ═══ Т. 29/30 ═══

  @Test
  @DisplayName("т. 30 · ГВРС само за трудови правоотношения — иначе грешка с назована норма")
  void gvrsOnlyForLabourRelations() {
    BgD1Record record = valid("12"); // не е трудово правоотношение
    record.setT30GvrsRate(new BigDecimal("0.60"));

    List<BgD1FindingDto> findings = VALIDATOR.validate(record, GVRS_CODES);
    assertTrue(
        codes(findings).contains(BgD1Validator.GVRS_NOT_LABOUR),
        "„Точки 29 и 30 се попълват за лицата, работещи по трудови правоотношения.“");

    BgD1Record labour = valid("01");
    labour.setT30GvrsRate(new BigDecimal("0.60"));
    assertTrue(
        !codes(VALIDATOR.validate(labour, GVRS_CODES)).contains(BgD1Validator.GVRS_NOT_LABOUR),
        "код 01 Е трудово правоотношение и носи ГВРС — правилото не бива да го спира");
  }

  @Test
  @DisplayName("т. 30 · без достъпен речник правилото се ОБЯВЯВА за непроверено, не минава зелено")
  void anUnavailableDictionaryIsDeclaredNotAssumed() {
    BgD1Record record = valid("12");
    record.setT30GvrsRate(new BigDecimal("0.60"));

    List<BgD1FindingDto> findings = VALIDATOR.validate(record, null);
    BgD1FindingDto finding =
        findings.stream()
            .filter(f -> BgD1Validator.GVRS_NOT_LABOUR.equals(f.getCode()))
            .findFirst()
            .orElse(null);

    assertNotNull(
        finding,
        "липсващ речник е СЪСТОЯНИЕ НА СРЕДАТА, не отговор „не е трудово правоотношение“ — но и"
            + " не е повод правилото да замълчи");
    assertEquals(
        BgD1FindingDto.SEVERITY_WARNING,
        finding.getSeverity(),
        "непроверено правило е предупреждение, не грешка: то не твърди, че записът е грешен");
    assertTrue(finding.getMessage().contains("НЕ е проверено"), finding.getMessage());
  }

  // ═══ МАЩАБЪТ НА СТАВКАТА (полета 35–47 са ≤5.2) ═══

  @Test
  @DisplayName("ставка с трети знак пада — файлът дава на полета 35–47 размер ≤5.2")
  void aRateWithThreeDecimalsIsRejected() {
    BgD1Record record = valid("01");
    record.setT22DooRateEmployer(new BigDecimal("10.925"));

    List<BgD1FindingDto> findings = VALIDATOR.validate(record, GVRS_CODES);
    assertTrue(
        codes(findings).contains(BgD1Validator.RATE_SCALE_TOO_LONG),
        "трети знак в ставката не влиза във файла, а и нито един от 265-те реда на"
            + " bg_insurance_contribution не иска трети знак. Находки: "
            + codes(findings));
  }

  @Test
  @DisplayName("закръглената ставка минава, включително цяло число процента")
  void aProperlyScaledRatePasses() {
    BgD1Record record = valid("01");
    record.setT21InsuranceIncome(new BigDecimal("2000.00"));
    record.setT22DooRateEmployer(new BigDecimal("10.92"));
    record.setT221HealthRateEmployer(new BigDecimal("5"));
    assertTrue(
        !codes(VALIDATOR.validate(record, GVRS_CODES)).contains(BgD1Validator.RATE_SCALE_TOO_LONG),
        "„5“ и „10.92“ са в рамките на два знака — валидатор, който иска ТОЧНО два знака в записа,"
            + " би спрял верни числа");
  }

  // ═══ ЕДНОПОЛЕВИТЕ ТОЧКИ — ТОЧНО ЕДИН ПЛАТЕЦ ═══

  @Test
  @DisplayName("т. 22.2 · ставка от ДВЕТЕ страни е грешка — полето е ЕДНО и не се дели обратно")
  void aSinglePayerPointWithBothSidesIsRejected() {
    // ⚠️ Правилото се проверява през ПЕРАТА, не през записа: на записа т. 22.2 вече е сборът им.
    BgPaySlip slip = new BgPaySlip();
    slip.setReference("FISH-2026-05-0009");
    slip.setBgPaySlipLineList(
        Arrays.asList(
            contributionLine(BgPaySlipResolver.CREDIT, "0.50", BgD1Point.T22_2_TZPB),
            contributionLine(BgPaySlipResolver.DEBIT, "0.90", BgD1Point.T22_2_TZPB)));

    BgD1Record record = valid("01");
    record.setPaySlip(slip);
    record.setT21InsuranceIncome(new BigDecimal("2000.00"));
    record.setT22DooRateEmployer(new BigDecimal("10.92"));
    record.setT222TzpbRate(new BigDecimal("1.40"));

    List<BgD1FindingDto> findings = VALIDATOR.validate(record, GVRS_CODES);
    assertTrue(
        codes(findings).contains(BgD1Validator.SINGLE_PAYER_BOTH_SIDES),
        "1,40 не е ничия ставка: осигурителят носи 0,50, лицето 0,90, а полето 39 е едно."
            + " Находки: "
            + codes(findings));
  }

  @Test
  @DisplayName("т. 22.2 · МОРСКОТО ЛИЦЕ носи ставката по перото на ЛИЦЕТО — и това НЕ е нарушение")
  void theSeafarerSideAloneIsFine() {
    BgPaySlip slip = new BgPaySlip();
    slip.setBgPaySlipLineList(
        Arrays.asList(contributionLine(BgPaySlipResolver.DEBIT, "1.10", BgD1Point.T22_2_TZPB)));

    BgD1Record record = valid("92");
    record.setPaySlip(slip);
    record.setT21InsuranceIncome(new BigDecimal("2000.00"));
    record.setT22DooRateEmployer(new BigDecimal("10.92"));
    record.setT222TzpbRate(new BigDecimal("1.10"));

    assertTrue(
        !codes(VALIDATOR.validate(record, GVRS_CODES))
            .contains(BgD1Validator.SINGLE_PAYER_BOTH_SIDES),
        "КСО чл. 6 ал. 6 и чл. 157 ал. 4 слагат нулата от страната на РАБОТОДАТЕЛЯ за вид 92."
            + " Правило „ставката е винаги на осигурителя“ би обявило точно този запис за грешен.");
  }

  // ═══ БАЗА БЕЗ СТАВКА — ЗАГУБЕНАТА СТАВКА ═══

  @Test
  @DisplayName("т. 21 с доход, но т. 22 без ставка → находка: перото се е закръглило до нула")
  void aBaseWithoutARateIsFlagged() {
    BgD1Record record = valid("01");
    record.setT21InsuranceIncome(new BigDecimal("2000.00"));

    List<BgD1FindingDto> findings = VALIDATOR.validate(record, GVRS_CODES);
    assertTrue(
        codes(findings).contains(BgD1Validator.BASE_WITHOUT_RATE),
        "вноски за ДОО се дължат върху всеки осигурителен доход. Ставката се чете от перото, а перо"
            + " не се създава при сума 0,00 — тоест ставката може да изчезне ТИХО. Обратната"
            + " посока е единственият ѝ отпечатък. Находки: "
            + codes(findings));
  }

  @Test
  @DisplayName("код 28 и код 90 НЕ раждат тази находка — при тях т. 22 не се попълва по норма")
  void code28And90AreExemptFromTheBaseWithoutRateRule() {
    for (String code : new String[] {"28", "90"}) {
      BgD1Record record = valid(code);
      record.setT21InsuranceIncome(new BigDecimal("2000.00"));
      assertTrue(
          !codes(VALIDATOR.validate(record, GVRS_CODES)).contains(BgD1Validator.BASE_WITHOUT_RATE),
          "вид осигурен "
              + code
              + " не носи т. 22 по самото указание — находка „база без ставка“ би пратила човек да"
              + " поправя нещо, което наредбата забранява да се попълва");
    }
  }

  // ═══ ДВОЙКИТЕ БАЗА/СТАВКА НА СПЕЦИАЛНИТЕ ЗДРАВНИ ТОЧКИ ═══

  @Test
  @DisplayName("т. 17 без т. 18 · деклариран доход, върху който не се дължи вноска — грешка")
  void aSpecialBaseWithoutItsRateIsRejected() {
    BgD1Record record = valid("01");
    record.setT21InsuranceIncome(new BigDecimal("2000.00"));
    record.setT22DooRateEmployer(new BigDecimal("10.92"));
    record.setT17Art4015Income(new BigDecimal("502.78"));

    List<BgD1FindingDto> findings = VALIDATOR.validate(record, GVRS_CODES);
    assertTrue(
        codes(findings).contains(BgD1Validator.HEALTH_BASE_PAIR_BROKEN),
        "по ЗЗО чл. 40 ал. 1 т. 5 вноската се дължи по САМАТА норма, която базата описва. Доход без"
            + " ставка е деклариран доход, върху който нищо не се дължи — а той минава всяка"
            + " проверка за формат. Находки: "
            + codes(findings));
  }

  @Test
  @DisplayName("т. 28 без т. 27 · процент върху нищо — указанието е „ВЪРХУ СУМАТА в т. 27“")
  void aSpecialRateWithoutItsBaseIsRejected() {
    BgD1Record record = valid("01");
    record.setT21InsuranceIncome(new BigDecimal("2000.00"));
    record.setT22DooRateEmployer(new BigDecimal("10.92"));
    record.setT28HealthRateOnT27(new BigDecimal("8.00"));

    List<BgD1FindingDto> findings = VALIDATOR.validate(record, GVRS_CODES);
    assertTrue(
        codes(findings).contains(BgD1Validator.HEALTH_BASE_PAIR_BROKEN),
        "находки: " + codes(findings));
  }

  @Test
  @DisplayName("двете заедно минават · и двете липсващи също — правилото не иска попълване")
  void bothOrNeitherPasses() {
    BgD1Record complete = valid("01");
    complete.setT21InsuranceIncome(new BigDecimal("2000.00"));
    complete.setT22DooRateEmployer(new BigDecimal("10.92"));
    complete.setT17Art4015Income(new BigDecimal("502.78"));
    complete.setT18HealthRateOnT17(new BigDecimal("4.80"));
    assertTrue(
        !codes(VALIDATOR.validate(complete, GVRS_CODES))
            .contains(BgD1Validator.HEALTH_BASE_PAIR_BROKEN),
        "пълната двойка е верният запис");

    BgD1Record neither = valid("01");
    neither.setT21InsuranceIncome(new BigDecimal("2000.00"));
    neither.setT22DooRateEmployer(new BigDecimal("10.92"));
    assertTrue(
        !codes(VALIDATOR.validate(neither, GVRS_CODES))
            .contains(BgD1Validator.HEALTH_BASE_PAIR_BROKEN),
        "⚠️ Лице без болничен и без неплатен отпуск НЕ попълва т. 17/18 и т. 27/28. Правило, което"
            + " иска попълване, би обявило всеки нормален фиш за грешен — и точно това е начинът"
            + " един валидатор да приучи, че находките не се гледат.");
  }

  @Test
  @DisplayName("код 28 · т. 27 и т. 28 СА в разрешените единайсет и НЕ раждат находка")
  void code28MayFillTwentySevenAndTwentyEight() {
    BgD1Record record = valid("28");
    record.setT27HealthOnlyIncome(new BigDecimal("310.10"));
    record.setT28HealthRateOnT27(new BigDecimal("8.00"));

    List<BgD1FindingDto> findings = VALIDATOR.validate(record, GVRS_CODES);
    assertTrue(
        !pointsWith(findings, BgD1Validator.CODE_28_EXTRA).contains("27"),
        "„Когато в т. 12 е попълнен код 28, се попълват т. 2, 3, 4, 5, 5.1, 6, 7, 14, 15, 27 и 28.“"
            + " Т. 27 и 28 СА в списъка — те са единственото, което код 28 носи. Находки: "
            + pointsWith(findings, BgD1Validator.CODE_28_EXTRA));
    assertTrue(!pointsWith(findings, BgD1Validator.CODE_28_EXTRA).contains("28"));
  }

  // ═══ ОБЯВЕНОТО НЕУСТАНОВЕНО УСЛОВИЕ ═══

  @Test
  @DisplayName("код 94 · чл. 6 ал. 3 КСО няма носител → точките остават празни и това се ОБЯВЯВА")
  void code94DeclaresTheUndecidableCondition() {
    BgInsuredType t94 = type("94");
    t94.setBgAppliesMod(Boolean.FALSE);
    t94.setBgAppliesModConditional(Boolean.TRUE);

    assertTrue(
        BgD1InsuredTypeRules.modIsUndecidable("94", t94),
        "заб. 2 към т. 12 пълни т. 12.1/12.2/12.3 за код 94 САМО когато доходът се определя по"
            + " реда на чл. 6 ал. 3 КСО. Този признак няма носител в модела — тоест отговор „да“"
            + " или „не“ би бил измислен.");

    BgInsuredType t01 = type("01");
    t01.setBgAppliesMod(Boolean.TRUE);
    assertTrue(
        !BgD1InsuredTypeRules.modIsUndecidable("01", t01),
        "кодовете с безусловен МОД не са неопределими");
  }
}
