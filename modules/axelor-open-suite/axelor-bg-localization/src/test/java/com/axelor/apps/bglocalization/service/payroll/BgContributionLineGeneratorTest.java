package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgContributionLine;
import com.axelor.apps.bglocalization.service.BgContributionLine.Payer;
import com.axelor.apps.bglocalization.service.BgContributionResult;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГЕНЕРАТОРЪТ НА ГРУПА 5xx — вноската става перо, а знакът избира платеца.
 *
 * <p><b>⚠️ РЕЧНИКЪТ СЕ ЧЕТЕ ОТ SEED-А, НЕ СЕ ИЗМИСЛЯ.</b> Типовете пера се сглобяват от {@code
 * bg_pay_slip_line_type_contribution.csv} — тоест кодовете, номерата и знаците са РЕАЛНИТЕ. Смени
 * ли се файлът, тестът се разминава и пада, вместо да продължи да проверява собствените си
 * фикстури.
 *
 * <p>Подменени са само двата шева към базата: избирането на типа и създаването на перото. Тече без
 * Postgres, без Guice и без Hibernate.
 */
class BgContributionLineGeneratorTest {

  private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate TO = LocalDate.of(2026, 3, 31);

  private static final String CSV = "bg_pay_slip_line_type_contribution.csv";

  // ═══ РЕЧНИКЪТ, ПРОЧЕТЕН ОТ SEED-А ═════════════════════════════════════════

  /** Ключ „ФОНД/ЗНАК“ → ред на речника, сглобен от CSV-то. */
  private static Map<String, BgPaySlipLineType> catalogueFromSeed() {
    List<String> h = BgSeed.quotedHeader(CSV);
    Map<String, BgPaySlipLineType> out = new LinkedHashMap<>();
    for (String[] r : BgSeed.quotedRows(CSV)) {
      String fundCode = BgSeed.col(h, r, "bgContributionFund");
      String sign = BgSeed.col(h, r, "bgSign");

      BgContributionFund fund = new BgContributionFund();
      fund.setCode(fundCode);
      fund.setName(fundCode);

      BgPaySlipLineType t = new BgPaySlipLineType();
      t.setCode(BgSeed.col(h, r, "code"));
      t.setNumber(Integer.parseInt(BgSeed.col(h, r, "number").trim()));
      t.setName(BgSeed.col(h, r, "name"));
      t.setBgSign(sign);
      t.setBgIsTaxable("true".equalsIgnoreCase(BgSeed.col(h, r, "bgIsTaxable")));
      t.setBgIsInsuranceIncome("true".equalsIgnoreCase(BgSeed.col(h, r, "bgIsInsuranceIncome")));
      t.setBgIsPermanentCharacter(false);
      t.setBgContributionFund(fund);
      t.setActive(true);
      out.put(fundCode + "/" + sign, t);
    }
    return out;
  }

  // ═══ ЕНДЖИНЪТ С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  private static final class Service extends BgContributionLineService {

    final Map<String, BgPaySlipLineType> catalogue = catalogueFromSeed();

    /** ⚠️ Ключовете, по които генераторът е питал — така двойката фонд+знак е ВИДИМА в теста. */
    final List<String> asked = new ArrayList<>();

    @Override
    protected BgPaySlipLineType lineTypeFor(String fundCode, String sign) {
      asked.add(fundCode + "/" + sign);
      return catalogue.get(fundCode + "/" + sign);
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
      // ДОСЛОВНО копие на BgPaySlipService#lineFrom — замразяване чрез копиране.
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

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  private static BgContributionLine contribution(
      String fund, Payer payer, String base, String rate, String amount) {
    return new BgContributionLine(
        fund,
        payer,
        base == null ? null : new BigDecimal(base),
        rate == null ? null : new BigDecimal(rate),
        amount == null ? null : new BigDecimal(amount));
  }

  /** Двете вноски за фонд „Пенсии“ върху база 1000,00 — осигурител 8,22%, лице 6,58%. */
  private static BgContributionResult pensionPair() {
    return BgContributionResult.resolved(
        Arrays.asList(
            contribution("PENSION", Payer.EMPLOYER, "1000.00", "8.2200", "82.20"),
            contribution("PENSION", Payer.INSURED, "1000.00", "6.5800", "65.80")));
  }

  private static BgPaySlip slip() {
    BgPaySlip s = new BgPaySlip();
    s.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period p = new Period();
    p.setName("03/PY2026");
    p.setFromDate(FROM);
    p.setToDate(TO);
    s.setPeriod(p);
    s.setBgPaySlipLineList(new ArrayList<>());
    return s;
  }

  private static BgPaySlipLine lineOf(BgPaySlip slip, int number) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgLineNumber() == number) {
        return line;
      }
    }
    return null;
  }

  // ═══ 1 · ЗНАКЪТ ИЗБИРА ПЛАТЕЦА ════════════════════════════════════════════

  /**
   * ⚠️ FAIL-FIRST: преди {@link BgContributionLineService} да съществува, група 5xx стоеше празна
   * на всеки фиш и този тест падаше на {@code assertNotNull}.
   *
   * <p>⚠️ ДВАТА ЗНАКА СА САМАТА АРИТМЕТИКА, не етикет. Разменени, лицето плаща вноските на
   * работодателя: данъчната основа пада с 82,20 вместо с 65,80, а нетото се мени с 16,40.
   */
  @Test
  @DisplayName("⚠️ осигурител → CREDIT (500), лице → DEBIT (501); типът се търси по ФОНД И ЗНАК")
  void thePayerChoosesTheSign() {
    Service s = new Service();
    BgPaySlip slip = slip();

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, pensionPair());

    assertEquals(2, outcomes.size());
    assertEquals(
        Arrays.asList("PENSION/CREDIT", "PENSION/DEBIT"),
        s.asked,
        "⚠️ Ключът е ДВОЙКАТА фонд и знак. Търсене само по фонд би върнало единия ред наслуки.");

    BgPaySlipLine employer = lineOf(slip, 500);
    BgPaySlipLine insured = lineOf(slip, 501);
    assertNotNull(employer, "перото на осигурителя трябва да е на фиша");
    assertNotNull(insured, "перото на лицето трябва да е на фиша");

    assertEquals("pl_vn_pension_osig", employer.getBgPaySlipLineType().getCode());
    assertEquals("CREDIT", employer.getBgSign());
    assertEquals(0, new BigDecimal("82.20").compareTo(employer.getBgAmount()));

    assertEquals("pl_vn_pension_lice", insured.getBgPaySlipLineType().getCode());
    assertEquals("DEBIT", insured.getBgSign());
    assertEquals(0, new BigDecimal("65.80").compareTo(insured.getBgAmount()));

    assertTrue(
        !employer.getBgIsTaxable() && !employer.getBgIsInsuranceIncome(),
        "⚠️ осигуровката НЕ е доход — и двата флага са false, от речника");
    assertTrue(!insured.getBgIsTaxable() && !insured.getBgIsInsuranceIncome());
    assertEquals(
        "PENSION",
        insured.getBgContributionFund().getCode(),
        "фондът се закача и на ПЕРОТО, за да не минава справката през речника");
  }

  /**
   * ⚠️ БАЗАТА И СТАВКАТА СА ДОКУМЕНТАЦИЯТА НА СУМАТА. Без тях перото е число без произход и 82,20
   * не може да се провери от фиша.
   */
  @Test
  @DisplayName("количеството е БАЗАТА, ставката е процентът — сумата се обяснява от фиша")
  void theBaseAndRateDocumentTheAmount() {
    Service s = new Service();
    BgPaySlip slip = slip();

    s.generate(slip, pensionPair());
    BgPaySlipLine employer = lineOf(slip, 500);

    assertEquals(0, new BigDecimal("1000.00").compareTo(employer.getBgQuantity()), "осиг. база");
    assertEquals(0, new BigDecimal("8.2200").compareTo(employer.getBgRate()), "ставка в проценти");
  }

  /**
   * ⚠️ АРИТМЕТИКАТА НА ФИША ЗАВИСИ ОТ ЗНАЦИТЕ — свидетелят я мери, а не я приема.
   *
   * <p>Върху едно облагаемо начисление от 1000,00: данъчната основа вади САМО вноската на лицето,
   * нетото не вижда тази на работодателя, а разходът за работодателя събира точно нея.
   */
  @Test
  @DisplayName("⚠️ данъчна основа 934,20 · нето 934,20 · разход 1082,20 — знаците решават и трите")
  void theSlipArithmeticFollowsTheSigns() {
    Service s = new Service();
    BgPaySlip slip = slip();
    BgPaySlipLineType salary = new BgPaySlipLineType();
    salary.setCode("pl_osnovna");
    salary.setNumber(100);
    salary.setName("Основно възнаграждение");
    salary.setBgSign("CREDIT");
    salary.setBgIsTaxable(true);
    salary.setBgIsInsuranceIncome(true);
    salary.setBgIsPermanentCharacter(true);
    s.addLine(slip, s.lineFrom(salary, new BigDecimal("1000.00")));

    s.generate(slip, pensionPair());
    List<BgPaySlipLine> lines = slip.getBgPaySlipLineList();

    assertEquals(
        0,
        new BigDecimal("1000.00").compareTo(BgPaySlipResolver.grossTotal(lines)),
        "вноските НЕ са в брутото — те не са начисление");
    assertEquals(
        0,
        new BigDecimal("934.20").compareTo(BgPaySlipResolver.taxBase(lines)),
        "1000,00 − 65,80 (само вноската на ЛИЦЕТО)");
    assertEquals(
        0,
        new BigDecimal("934.20").compareTo(BgPaySlipResolver.netTotal(lines)),
        "⚠️ вноските на РАБОТОДАТЕЛЯ не влизат в нетото — те не са доход на лицето");
    assertEquals(
        0,
        new BigDecimal("1082.20").compareTo(BgPaySlipResolver.employerCost(lines)),
        "1000,00 + 82,20");
    assertEquals(
        0,
        new BigDecimal("1000.00").compareTo(BgPaySlipResolver.insuranceIncomeBase(lines)),
        "осигурителният доход не се мени от самите вноски");
  }

  // ═══ 2 · ИДЕМПОТЕНТНОСТ И АДИТИВНОСТ ══════════════════════════════════════

  @Test
  @DisplayName("⚠️ ИДЕМПОТЕНТНО · три пъти generate() дава два реда със същите суми")
  void generateIsIdempotent() {
    Service s = new Service();
    BgPaySlip slip = slip();

    s.generate(slip, pensionPair());
    s.generate(slip, pensionPair());
    s.generate(slip, pensionPair());

    assertEquals(2, slip.getBgPaySlipLineList().size(), "шест реда значат, че ключът не работи");
    assertEquals(
        0,
        new BigDecimal("82.20").compareTo(lineOf(slip, 500).getBgAmount()),
        "⚠️ ПРИСВОЯВА СЕ, не се натрупва — 246,60 тук значи адитивен upsert");
  }

  /**
   * ⚠️ ОБНОВЯВАНЕТО ПРЕНАПИСВА И БАЗАТА, И СТАВКАТА. Иначе на обновен ред остават числата от
   * предишното смятане и обясняват ДРУГА сума — перо, което сам себе си опровергава.
   */
  @Test
  @DisplayName("⚠️ второ смятане с друга база пренаписва сумата, базата И ставката")
  void anUpdateRewritesTheDocumentationToo() {
    Service s = new Service();
    BgPaySlip slip = slip();
    s.generate(slip, pensionPair());

    s.generate(
        slip,
        BgContributionResult.resolved(
            Collections.singletonList(
                contribution("PENSION", Payer.EMPLOYER, "2000.00", "8.2200", "164.40"))));

    BgPaySlipLine employer = lineOf(slip, 500);
    assertEquals(0, new BigDecimal("164.40").compareTo(employer.getBgAmount()));
    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(employer.getBgQuantity()),
        "⚠️ базата трябва да е новата — стара база обяснява стара сума");
    assertEquals(0, new BigDecimal("8.2200").compareTo(employer.getBgRate()));
  }

  @Test
  @DisplayName("⚠️ АДИТИВНО · ръчните и чуждите пера преживяват generate()")
  void foreignLinesSurvive() {
    Service s = new Service();
    BgPaySlip slip = slip();
    BgPaySlipLineType garnishment = new BgPaySlipLineType();
    garnishment.setCode("pl_zapor");
    garnishment.setNumber(820);
    garnishment.setName("Запор");
    garnishment.setBgSign("DEBIT");
    garnishment.setBgIsTaxable(false);
    garnishment.setBgIsInsuranceIncome(false);
    BgPaySlipLine manual = s.lineFrom(garnishment, new BigDecimal("120.00"));
    s.addLine(slip, manual);

    s.generate(slip, pensionPair());

    assertEquals(3, slip.getBgPaySlipLineList().size());
    assertTrue(slip.getBgPaySlipLineList().contains(manual));
    assertEquals(0, new BigDecimal("120.00").compareTo(manual.getBgAmount()));
  }

  // ═══ 3 · НУЛАТА НЕ ПРАВИ РЕД ══════════════════════════════════════════════

  /**
   * ⚠️ LEGAL_ZERO НЕ Е ОТКАЗ. Фонд ГВРС не идва дотук — резолверът не строи ред за нулева ставка
   * (ЗБДОО чл. 15 ал. 1). Нула тук значи закръглена до нула сума, и тя пак не бива да е перо.
   */
  @Test
  @DisplayName("⚠️ нулева сума НЕ прави ред — и не е отказ, а законна нула")
  void aZeroAmountProducesNoLine() {
    Service s = new Service();
    BgPaySlip slip = slip();

    List<BgPaySlipLineOutcome> outcomes =
        s.generate(
            slip,
            BgContributionResult.resolved(
                Arrays.asList(
                    contribution("GVRS", Payer.EMPLOYER, "1000.00", "0.0000", "0.00"),
                    contribution("PENSION", Payer.INSURED, "1000.00", "6.5800", "65.80"))));

    assertEquals(2, outcomes.size());
    assertEquals(
        BgPaySlipLineOutcome.Status.LEGAL_ZERO,
        outcomes.get(0).status,
        "⚠️ нито COMPUTED (би направил ред с 0,00), нито REFUSED (би поискал поправка)");
    assertTrue(!outcomes.get(0).isRefusal(), "никой не поправя нищо");
    assertNull(outcomes.get(0).amount, "⚠️ сума при нула-без-ред би се сумирала някъде");
    assertEquals(1, slip.getBgPaySlipLineList().size(), "само перото на пенсиите");
    assertNull(lineOf(slip, 518), "ГВРС няма ред и НЕ бива да получи такъв с 0,00");
    assertTrue(
        !s.asked.contains("GVRS/CREDIT"),
        "⚠️ речникът дори не се пита за нулева вноска — иначе липсващ ред би вдигнал изключение");
  }

  // ═══ 4 · ГРАНИЦИТЕ: РЕТРО И СТАТУС ════════════════════════════════════════

  @Test
  @DisplayName("коригиращото копие се ПИШЕ, заличаващото — не")
  void theCorrectionIsWrittenAndTheDeletionIsSkipped() {
    // Коригиращото: резултатът идва от веригата по ЗАМРАЗЕНОТО на копието и параметрите на
    // ПЕРИОДА (BgFrozenPayrollInput), тоест не е „днешните ставки за минал месец“.
    Service s = new Service();
    BgPaySlip correction = slip();
    correction.setBgIsCorrection(true);
    assertFalse(s.generate(correction, pensionPair()).isEmpty(), "коригиращото: перата се пишат");
    assertFalse(correction.getBgPaySlipLineList().isEmpty(), "коригиращото носи вноските си");

    // Заличаващото носи числата на оригинала дословно — те идентифицират записа.
    Service t = new Service();
    BgPaySlip deletion = slip();
    deletion.setBgIsDeletion(true);
    assertTrue(t.generate(deletion, pensionPair()).isEmpty(), "заличаващото: пропуска се");
    assertTrue(deletion.getBgPaySlipLineList().isEmpty(), "⚠️ заличаващото не се пипа");
  }

  @Test
  @DisplayName("потвърден фиш не стига до генератора")
  void aConfirmedSlipIsSkipped() {
    Service s = new Service();
    BgPaySlip slip = slip();
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);

    assertTrue(s.generate(slip, pensionPair()).isEmpty());
    assertTrue(s.asked.isEmpty());
  }

  // ═══ 5 · ОТКАЗИТЕ ═════════════════════════════════════════════════════════

  /**
   * ⚠️ ОТКАЗЪТ НОСИ СТАТУСА ДОСЛОВНО, защото той праща читателя на верния файл: {@code
   * INPUT_NOT_ASSEMBLED} → договора, {@code NO_CONTRIBUTIONS_FOUND} → seed-а, {@code MISSING_RATE}
   * → първичния източник на ставката.
   */
  @Test
  @DisplayName("⚠️ незавършена верига → ЕДИН отказ със статуса си, и НИТО едно перо")
  void anUnresolvedChainRefusesWithItsStatus() {
    for (BgContributionResult.Status status :
        Arrays.asList(
            BgContributionResult.Status.INPUT_NOT_ASSEMBLED,
            BgContributionResult.Status.NO_CONTRIBUTIONS_FOUND,
            BgContributionResult.Status.MISSING_RATE,
            BgContributionResult.Status.MISSING_MANDATORY_FUND)) {
      Service s = new Service();
      BgPaySlip slip = slip();

      List<BgPaySlipLineOutcome> outcomes =
          s.generate(slip, BgContributionResult.failure(status, "подробността"));

      assertEquals(1, outcomes.size(), status + ": един отказ за цялата група");
      assertEquals(BgPaySlipLineOutcome.Status.REFUSED, outcomes.get(0).status, status.toString());
      assertTrue(
          outcomes.get(0).detail.contains(status.toString()),
          "⚠️ статусът трябва да е В съобщението: " + outcomes.get(0).detail);
      assertTrue(outcomes.get(0).detail.contains("подробността"), outcomes.get(0).detail);
      assertTrue(slip.getBgPaySlipLineList().isEmpty(), status + ": нито едно перо");
    }
  }

  @Test
  @DisplayName(
      "⚠️ null резултат е ОТКАЗ, не празна група — „вноски не се дължат“ е друго твърдение")
  void aNullResultIsARefusal() {
    Service s = new Service();
    BgPaySlip slip = slip();

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, null);

    assertEquals(1, outcomes.size());
    assertEquals(BgPaySlipLineOutcome.Status.REFUSED, outcomes.get(0).status);
    assertTrue(slip.getBgPaySlipLineList().isEmpty());
  }

  /**
   * ⚠️ RESOLVED_INCOMPLETE: ПЕРАТА СЕ ЗАПИСВАТ, но състоянието се ОБЯВЯВА.
   *
   * <p>Днес го вдига кохорта WITH_UPF с пенсионен ред без ред за УПФ — пет пункта, които тихо не се
   * начисляват. Перата са верни за фондовете, които ги има, а потвърждаването остава блокирано от
   * пазача. Изхвърлено, това състояние би изглеждало като чисто сметнат фиш.
   */
  @Test
  @DisplayName("⚠️ RESOLVED_INCOMPLETE записва перата И добавя обявено разминаване")
  void anIncompleteResultStillWritesLinesAndSaysSo() {
    Service s = new Service();
    BgPaySlip slip = slip();

    List<BgPaySlipLineOutcome> outcomes =
        s.generate(
            slip,
            BgContributionResult.resolvedIncomplete(
                pensionPair().getLines(), "липсва УПФ ред при кохорта WITH_UPF"));

    assertEquals(3, outcomes.size(), "две пера плюс обявеното състояние");
    assertEquals(2, slip.getBgPaySlipLineList().size(), "⚠️ перата СЕ ЗАПИСВАТ");
    BgPaySlipLineOutcome notice = outcomes.get(2);
    assertEquals(BgPaySlipLineOutcome.Status.DIVERGENT, notice.status);
    assertTrue(notice.detail.contains("RESOLVED_INCOMPLETE"), notice.detail);
    assertTrue(notice.detail.contains("УПФ"), notice.detail);
    assertEquals(0, new BigDecimal("148.00").compareTo(notice.amount), "82,20 + 65,80");
  }

  /**
   * ⚠️ ЛИПСВАЩ РЕД В РЕЧНИКА Е ИЗКЛЮЧЕНИЕ, НЕ ТИХО ПРЕСКАЧАНЕ. Прескочена вноска значи неудържана
   * вноска: лицето получава повече, отколкото му се дължи, а декларацията излиза с друга сума.
   */
  @Test
  @DisplayName("⚠️ фонд без ред в речника вдига ИЗКЛЮЧЕНИЕ, което сочи файла, знака и App Install")
  void aFundWithoutADictionaryRowThrows() {
    Service s = new Service();
    s.catalogue.remove("PENSION/DEBIT"); // само редът на ЛИЦЕТО липсва
    BgPaySlip slip = slip();

    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> s.generate(slip, pensionPair()));

    assertTrue(e.getMessage().contains("PENSION"), e.getMessage());
    assertTrue(e.getMessage().contains("DEBIT"), "знакът е част от ключа: " + e.getMessage());
    assertTrue(e.getMessage().contains("за сметка на лицето"), e.getMessage());
    assertTrue(e.getMessage().contains("bg_pay_slip_line_type_contribution.csv"), e.getMessage());
    assertTrue(e.getMessage().contains("App Install"), e.getMessage());
  }

  @Test
  @DisplayName("вноска без фонд или без платец се отказва, вместо да стане безтипов ред")
  void aContributionWithoutAFundOrPayerIsRefused() {
    for (BgContributionLine broken :
        Arrays.asList(
            contribution(null, Payer.EMPLOYER, "1000.00", "8.2200", "82.20"),
            contribution("  ", Payer.EMPLOYER, "1000.00", "8.2200", "82.20"),
            contribution("PENSION", null, "1000.00", "8.2200", "82.20"))) {
      Service s = new Service();
      BgPaySlip slip = slip();

      List<BgPaySlipLineOutcome> outcomes =
          s.generate(slip, BgContributionResult.resolved(Collections.singletonList(broken)));

      assertEquals(BgPaySlipLineOutcome.Status.REFUSED, outcomes.get(0).status, broken.toString());
      assertTrue(slip.getBgPaySlipLineList().isEmpty(), broken.toString());
    }
  }

  /**
   * ⚠️ ИЗБОРЪТ НА ЗНАК Е ИЗЧЕРПАТЕЛЕН, а не „ако не е осигурител, значи е лице“.
   *
   * <p>Тройката, която пази това, е тук: днес изброимото има точно две стойности, и свидетелят мери
   * че двете дават РАЗЛИЧНИ знака. Тернарен израз би превърнал всяка бъдеща трета стойност в
   * удръжка от заплатата на лицето — мълчаливо.
   */
  @Test
  @DisplayName("двата платеца дават различни знака, а липсващият платец — нито един")
  void theSignMappingIsExhaustive() {
    Service s = new Service();

    assertEquals(
        BgPaySlipResolver.CREDIT,
        s.signFor(contribution("PENSION", Payer.EMPLOYER, "1", "1", "1")));
    assertEquals(
        BgPaySlipResolver.DEBIT, s.signFor(contribution("PENSION", Payer.INSURED, "1", "1", "1")));
    assertNull(s.signFor(contribution("PENSION", null, "1", "1", "1")));
    assertNull(s.signFor(null));
  }

  // ═══ 6 · ЦЕЛИЯТ РЕЧНИК Е ДОСТИЖИМ ═════════════════════════════════════════

  /**
   * ⚠️ ВСИЧКИТЕ ДВАЙСЕТ РЕДА СА ДОСТИЖИМИ ПО ДВОЙКАТА (ФОНД, ЗНАК) — иначе някой фонд щеше да
   * работи само за единия си платец, и то без нито един отказ, докато точно той не се появи на фиш.
   */
  @Test
  @DisplayName("⚠️ и двайсетте реда на 5xx се намират по фонд+знак — нито един не е недостижим")
  void everySeedRowIsReachable() {
    Service s = new Service();
    BgPaySlip slip = slip();
    List<BgContributionLine> all = new ArrayList<>();
    for (String key : catalogueFromSeed().keySet()) {
      String[] parts = key.split("/");
      all.add(
          contribution(
              parts[0],
              "CREDIT".equals(parts[1]) ? Payer.EMPLOYER : Payer.INSURED,
              "1000.00",
              "1.0000",
              "10.00"));
    }

    List<BgPaySlipLineOutcome> outcomes = s.generate(slip, BgContributionResult.resolved(all));

    assertEquals(20, outcomes.size(), "10 фонда × 2 платеца");
    for (BgPaySlipLineOutcome o : outcomes) {
      assertEquals(
          BgPaySlipLineOutcome.Status.COMPUTED,
          o.status,
          "недостижим ред: " + o.lineCode + " — " + o.detail);
    }
    assertEquals(20, slip.getBgPaySlipLineList().size());
  }
}
