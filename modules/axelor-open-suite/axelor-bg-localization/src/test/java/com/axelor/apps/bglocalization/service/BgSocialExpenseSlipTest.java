package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.AxelorException;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.db.BgSocialExpenseCap;
import com.axelor.apps.bglocalization.db.repo.BgPaySlipManagementRepository;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СОЦИАЛНИТЕ РАЗХОДИ НА ЦЕЛИЯ ФИШ — двете основи едновременно, по истинския seed.
 *
 * <p>{@code BgSocialExpenseCapResolverTest} мери деленето, а {@code BgSocialExpenseSeedFileTest} —
 * флаговете. Никой от двата не сглобява ФИШ и не пита двете основи заедно, а точно там те могат да
 * се разминат: превишението на ДДО (201) не бива да е облагаемо за лицето И в основата по ЗКПО.
 *
 * <p>Типовете перо и капачките се четат от {@code bg_pay_slip_line_type_social.csv} и {@code
 * bg_social_expense_cap.csv}, не се сглобяват на ръка — инак тестът щеше да мери флаговете, които
 * сам си е сложил. Подменено е само четенето от базата в {@link BgSocialExpenseService}: условията
 * са изпълнени (няма ред в {@code BgSocialExpenseException}, три предходни фиша няма).
 */
class BgSocialExpenseSlipTest {

  private static final String SOCIAL = "bg_pay_slip_line_type_social.csv";
  private static final String CAPS = "bg_social_expense_cap.csv";

  /** Курсът по чл. 12 ал. 1 от Закона за въвеждане на еврото. */
  private static final BigDecimal RATE = new BigDecimal("1.95583");

  /** Ставката на данъка върху разходите — ЗКПО чл. 216 ал. 1. Кодът смята само ОСНОВАТА. */
  private static final BigDecimal COMPANY_TAX_RATE = new BigDecimal("0.10");

  /** Услугата с истинската сметка; условията са изпълнени, без заявка към базата. */
  private static class AllMetSocialExpenseService extends BgSocialExpenseService {
    @Override
    public BgSocialExpenseConditions conditionsFor(BgPaySlip slip) {
      return BgSocialExpenseConditions.allMet();
    }
  }

  private static class Service extends BgPaySlipService {
    @Override
    protected BgSocialExpenseService socialExpenseService() {
      return new AllMetSocialExpenseService();
    }

    @Override
    protected BigDecimal disabilityRelief(BgPaySlip slip) {
      return BigDecimal.ZERO;
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return new BigDecimal("10.0000");
    }
  }

  /** Достъп до пазача на репозитория без JPA. */
  private static class Repository extends BgPaySlipManagementRepository {
    String refusal(BgPaySlip slip) {
      return refuseNewManualDerivedSocialExpense(slip);
    }
  }

  // ── seed ───────────────────────────────────────────────────────────────

  private static Map<String, BgSocialExpenseCap> caps() {
    List<String> h = BgSeed.quotedHeader(CAPS);
    Map<String, BgSocialExpenseCap> out = new LinkedHashMap<>();
    for (String[] r : BgSeed.quotedRows(CAPS)) {
      BgSocialExpenseCap cap = new BgSocialExpenseCap();
      cap.setCode(BgSeed.col(h, r, "code"));
      cap.setBgPersonalCapAmount(new BigDecimal(BgSeed.col(h, r, "bgPersonalCapAmount")));
      String company = BgSeed.col(h, r, "bgCompanyCapAmount").trim();
      cap.setBgCompanyCapAmount(company.isEmpty() ? null : new BigDecimal(company));
      cap.setBgCompanyTaxApplies(bool(h, r, "bgCompanyTaxApplies"));
      cap.setBgPeriodicity(BgSeed.col(h, r, "bgPeriodicity"));
      cap.setBgWholeAmountOnConditionFail(bool(h, r, "bgWholeAmountOnConditionFail"));
      cap.setBgRequiresNoPublicDebts(bool(h, r, "bgRequiresNoPublicDebts"));
      cap.setBgRequiresSalaryNotBelowAverage(bool(h, r, "bgRequiresSalaryNotBelowAverage"));
      cap.setBgRequiresLicensedOperator(bool(h, r, "bgRequiresLicensedOperator"));
      out.put(cap.getCode(), cap);
    }
    return out;
  }

  /**
   * Типовете перо от 2xx под капачка, по номер. ⚠️ Една инстанция на капачка за всички редове,
   * които сочат към нея: услугата групира по капачка, а несъхранени обекти са равни само на себе
   * си.
   */
  private static Map<Integer, BgPaySlipLineType> socialTypes() {
    Map<String, BgSocialExpenseCap> caps = caps();
    List<String> h = BgSeed.quotedHeader(SOCIAL);
    Map<Integer, BgPaySlipLineType> out = new LinkedHashMap<>();
    for (String[] r : BgSeed.quotedRows(SOCIAL)) {
      BgPaySlipLineType t = new BgPaySlipLineType();
      t.setCode(BgSeed.col(h, r, "code"));
      t.setNumber(Integer.parseInt(BgSeed.col(h, r, "number").trim()));
      t.setName(BgSeed.col(h, r, "name"));
      t.setBgGroup(BgSeed.col(h, r, "bgGroup"));
      t.setBgSign(BgSeed.col(h, r, "bgSign"));
      t.setBgIsTaxable(bool(h, r, "bgIsTaxable"));
      t.setBgIsInsuranceIncome(bool(h, r, "bgIsInsuranceIncome"));
      t.setBgIsPermanentCharacter(bool(h, r, "bgIsPermanentCharacter"));
      t.setBgCalculationBase(BgSeed.col(h, r, "bgCalculationBase"));
      t.setBgIsCapExcess(bool(h, r, "bgIsCapExcess"));
      t.setBgSocialExpenseCap(caps.get(BgSeed.col(h, r, "bgSocialExpenseCap")));
      out.put(t.getNumber(), t);
    }
    return out;
  }

  private static Boolean bool(List<String> h, String[] r, String column) {
    return Boolean.valueOf(BgSeed.col(h, r, column).trim());
  }

  /** Левовете от закона, превалутирани по чл. 12–13 — точно както капачката в seed-а. */
  private static BigDecimal leva(String amount) {
    return new BigDecimal(amount).divide(RATE, 2, RoundingMode.HALF_UP);
  }

  /** Заплатата — облагаемо 1xx перо, за да има основа, в която социалният разход да НЕ влезе. */
  private static BgPaySlipLine salary(String amount) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgLineNumber(100);
    l.setName("Основна заплата");
    l.setBgSign(BgPaySlipResolver.CREDIT);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgIsTaxable(true);
    l.setBgIsInsuranceIncome(true);
    return l;
  }

  private static BgPaySlip draft(BgPaySlipService service, BgPaySlipLine... lines) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgPaySlipLineList(new ArrayList<>());
    for (BgPaySlipLine l : lines) {
      service.addLine(slip, l);
    }
    return slip;
  }

  // ── примерът ──────────────────────────────────────────────────────────

  @Test
  @DisplayName(
      "100 лв. ДДО + 250 лв. ваучери → основа по чл. 42 +0, основа по ЗКПО 46,01 €, данък 4,60 €")
  void theExampleTaxesTheExcessOnceAtTheCompanyAndNeverAtThePerson() {
    Service service = new Service();
    Map<Integer, BgPaySlipLineType> types = socialTypes();

    BigDecimal ddo = leva("100");
    BigDecimal vouchers = leva("250");
    assertEquals(new BigDecimal("51.13"), ddo, "100 лв. ÷ 1,95583");
    assertEquals(new BigDecimal("127.82"), vouchers, "250 лв. ÷ 1,95583");

    BgPaySlip without = draft(service, salary("1000.00"));
    service.recompute(without);

    // ⚠️ ЦЯЛОТО начислено на реда „в рамките“ — така е устроено: редът „превишение“ е изход.
    BgPaySlip with =
        draft(
            service,
            salary("1000.00"),
            service.lineFrom(types.get(200), ddo),
            service.lineFrom(types.get(210), vouchers));
    service.recompute(with);

    assertEquals(
        without.getBgTaxBase(),
        with.getBgTaxBase(),
        "⚠️ ОСНОВАТА ПО ЧЛ. 42 ЗДДФЛ НЕ РАСТЕ: социалният разход до прага е освободен по т. 12 и т."
            + " 1 б. „е“, а превишението е обложено по ЗКПО — т. 9");
    assertEquals(new BigDecimal("1000.00"), with.getBgTaxBase());
    assertEquals(
        without.getBgInsuranceIncome(),
        with.getBgInsuranceIncome(),
        "200 и 210 не са осигурителен доход (НЕВД чл. 2 ал. 2 и ал. 3 т. 1)");

    BigDecimal companyBase = with.getBgSocialExpenseTaxBase();
    assertEquals(
        new BigDecimal("46.01"),
        companyBase,
        "(51,13 − 30,68) + (127,82 − 102,26) = 20,45 + 25,56 — ЗКПО чл. 213 ал. 2 и чл. 214 ал. 2");
    assertEquals(
        new BigDecimal("4.60"),
        companyBase.multiply(COMPANY_TAX_RATE).setScale(2, RoundingMode.HALF_UP),
        "данък върху разходите 10 на сто — ЗКПО чл. 216 ал. 1");
  }

  @Test
  @DisplayName("⚠️ редът „превишение“ е ИЗХОД — не се подава на резолвера, макар да е необлагаем")
  void theExcessLineIsAnOutputNotAnInput() {
    // Решение 1: „вход или изход на капачката“ се чете от bgIsCapExcess, не от bgIsTaxable. Откакто
    // 201 е необлагаем (т. 9), прочит по bgIsTaxable би го подал като ВХОД заедно с 200 и
    // основата по ЗКПО щеше да е (51,13 + 20,45) − 30,68 = 40,90 вместо 20,45.
    Service service = new Service();
    Map<Integer, BgPaySlipLineType> types = socialTypes();
    BgPaySlip slip =
        draft(
            service,
            service.lineFrom(types.get(200), new BigDecimal("51.13")),
            service.lineFrom(types.get(201), new BigDecimal("20.45")));

    assertEquals(
        new BigDecimal("20.45"),
        new AllMetSocialExpenseService().monthlyTaxBase(slip),
        "превишението се смята от реда „в рамките“ (51,13 − 30,68); редът 201 е изходът, не вход");
  }

  @Test
  @DisplayName("ред 201, създаден от речника, вече е НЕоблагаем и е превишение по признак")
  void theDdoExcessLineIsNoLongerTaxableAtThePerson() {
    Service service = new Service();
    Map<Integer, BgPaySlipLineType> types = socialTypes();
    for (int number : new int[] {201, 203, 205}) {
      BgPaySlipLine line = service.lineFrom(types.get(number), new BigDecimal("20.45"));
      assertFalse(line.getBgIsTaxable(), number + " — ЗДДФЛ чл. 24 ал. 2 т. 9");
      assertTrue(types.get(number).getBgIsCapExcess(), number + " — превишение над капачката");
    }
    assertTrue(
        types.get(211).getBgIsTaxable(),
        "211 остава облагаем — отворен въпрос (т. 1 б. „е“ срещу т. 9)");
  }

  // ── ръчното превишение се отказва ─────────────────────────────────────

  @Test
  @DisplayName("⚠️ ръчен 201 → чистият пазач назовава реда")
  void theManualExcessIsNamed() {
    Service service = new Service();
    Map<Integer, BgPaySlipLineType> types = socialTypes();
    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(service.lineFrom(types.get(200), new BigDecimal("30.68")));
    assertNull(
        BgSocialExpenseCapResolver.refuseManualDerivedSocialExpense(lines),
        "редът „в рамките“ е входът — той се въвежда");

    lines.add(service.lineFrom(types.get(201), new BigDecimal("20.45")));
    String refusal = BgSocialExpenseCapResolver.refuseManualDerivedSocialExpense(lines);
    assertNotNull(refusal);
    assertTrue(refusal.contains("201"), "отказът назовава номера: " + refusal);
    assertTrue(refusal.contains("в рамките на капачката"), "и казва къде е мястото на сумата");
  }

  @Test
  @DisplayName("⚠️ ръчен 201 → „Преизчисли“ отказва, без да пипне реда")
  void calculateRefusesTheManualExcessWithoutDroppingIt() {
    Service service = new Service();
    Map<Integer, BgPaySlipLineType> types = socialTypes();
    BgPaySlip slip =
        draft(
            service,
            salary("1000.00"),
            service.lineFrom(types.get(200), new BigDecimal("30.68")),
            service.lineFrom(types.get(201), new BigDecimal("20.45")));

    BgCalculationRefusedException e =
        assertThrows(BgCalculationRefusedException.class, () -> service.calculate(slip));
    assertTrue(e.getMessage().contains("201"), e.getMessage());
    assertEquals(3, slip.getBgPaySlipLineList().size(), "⚠️ редът НЕ се трие тихо");
  }

  @Test
  @DisplayName("⚠️ ръчен 201 в стара чернова → „Потвърди“ отказва с назован ред")
  void confirmRefusesTheManualExcess() {
    Service service = new Service();
    Map<Integer, BgPaySlipLineType> types = socialTypes();
    BgPaySlip slip =
        draft(
            service,
            salary("1000.00"),
            service.lineFrom(types.get(210), new BigDecimal("102.26")),
            service.lineFrom(types.get(211), new BigDecimal("25.56")));

    AxelorException e = assertThrows(AxelorException.class, () -> service.confirm(slip));
    assertTrue(e.getMessage().contains("211"), e.getMessage());
    assertEquals(BgPaySlipResolver.DRAFT, slip.getStatusSelect(), "фишът остава чернова");
  }

  @Test
  @DisplayName("⚠️ запис: НОВ ръчен 201 се отказва, заварен ред и копие — не")
  void theRepositoryRefusesOnlyNewManualExcessLines() {
    Service service = new Service();
    Repository repository = new Repository();
    Map<Integer, BgPaySlipLineType> types = socialTypes();

    BgPaySlipLine excess = service.lineFrom(types.get(201), new BigDecimal("20.45"));
    BgPaySlip slip = draft(service, salary("1000.00"), excess);
    String refusal = repository.refusal(slip);
    assertNotNull(refusal, "нов ред „превишение“ не се записва");
    assertTrue(refusal.contains("201"), refusal);

    excess.setId(77L);
    assertNull(
        repository.refusal(slip),
        "⚠️ заварената чернова трябва да може да се запише — включително за да се махне редът;"
            + " спират я „Преизчисли“ и „Потвърди“");

    excess.setId(null);
    slip.setBgIsCorrection(true);
    assertNull(repository.refusal(slip), "копието носи редовете на оригинала, не нов вход");

    slip.setBgIsCorrection(false);
    slip.setStatusSelect(BgPaySlipResolver.CONFIRMED);
    assertNull(repository.refusal(slip), "само чернова");
  }
}
