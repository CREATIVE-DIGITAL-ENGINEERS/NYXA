package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlEntriesAssembler.LineRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftWithholdingRouter.IncomeLine;
import com.axelor.apps.bglocalization.service.saft.BgSaftWithholdingRouter.InvoiceMove;
import com.axelor.apps.bglocalization.service.saft.BgSaftWithholdingRouter.Mapping;
import com.axelor.apps.bglocalization.service.saft.BgSaftWithholdingRouter.PayrollMove;
import com.axelor.apps.bglocalization.service.saft.BgSaftWithholdingRouter.Result;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * G3b — кодовете 600/700 на реда от дневника, с числа. Правилата са от Прил. 3 т. IV.8.2 и QA с.
 * 51–56; решенията по отворените въпроси — от 12.10.2026.
 *
 * <p>Записът на реда е „вид/код/сума“; празен списък значи, че рутерът не дава нищо и редът остава
 * с ДДС кода си или с 000/000000 (решава assembler-ът).
 */
class BgSaftWithholdingRouterTest {

  private static final Map<String, Mapping> MAPPINGS = new HashMap<>();

  static {
    MAPPINGS.put("3073", new Mapping("700500", Mapping.CODE));
    MAPPINGS.put("806", new Mapping("700080", Mapping.CODE));
    MAPPINGS.put("804", new Mapping(null, Mapping.AMBIGUOUS));
    MAPPINGS.put("501", new Mapping(null, Mapping.NONE));
  }

  // ═══════════════════════════ ФИШ ═══════════════════════════

  @Test
  @DisplayName("трудов фиш: 700500 на разходните редове по облагаемото, 0.00 на 4211, нищо другаде")
  void трудовФиш() {
    List<BgPaySlipLine> slip =
        Arrays.asList(
            wage(100, "2000.00"),
            wage(101, "300.00"),
            contribution(500, "PENSION", "CREDIT", "200.00"),
            contribution(501, "PENSION", "DEBIT", "100.00"),
            line(700, "DEBIT", "180.00"));
    List<LineRow> move =
        Arrays.asList(
            row(1, "6041", "2000.00", null),
            row(2, "6042", "300.00", null),
            row(3, "6051", "200.00", null),
            row(4, "4211", null, "2300.00"),
            row(5, "4211", "280.00", null),
            row(6, "4551", null, "300.00"),
            row(7, "4541", null, "180.00"));

    Result result = route(move, payroll(slip, "01", false, "0.00"), noInvoices());

    assertEquals(Collections.singletonList("700/700500/156.52"), codes(result, 1));
    assertEquals(Collections.singletonList("700/700500/23.48"), codes(result, 2));
    assertEquals(Collections.emptyList(), codes(result, 3));
    assertEquals(Collections.singletonList("700/700500/0.00"), codes(result, 4));
    assertEquals(Collections.emptyList(), codes(result, 5), "удържането на данъка не е разходът");
    assertEquals(Collections.emptyList(), codes(result, 6));
    assertEquals(Collections.emptyList(), codes(result, 7), "редът на самия данък остава 000");
    assertEquals(Collections.emptyList(), result.getFindings());
  }

  @Test
  @DisplayName("морско лице → 700520 (ЗДДФЛ чл. 42 ал. 4, 1 на сто)")
  void морскоЛице() {
    List<BgPaySlipLine> slip = Arrays.asList(wage(100, "1000.00"), line(700, "DEBIT", "10.00"));
    List<LineRow> move =
        Arrays.asList(
            row(1, "6041", "1000.00", null),
            row(2, "4211", null, "1000.00"),
            row(3, "4211", "10.00", null),
            row(4, "4541", null, "10.00"));

    Result result = route(move, payroll(slip, "01", true, "0.00"), noInvoices());

    assertEquals(Collections.singletonList("700/700520/10.00"), codes(result, 1));
    assertEquals(Collections.singletonList("700/700520/0.00"), codes(result, 2));
  }

  @Test
  @DisplayName(
      "социални разходи: 6094 носи 600020 (10 % от ЗКПО основата), 600030 и 700500; 4211 — трите"
          + " с 0.00")
  void социалниРазходи() {
    List<BgPaySlipLine> slip =
        Arrays.asList(
            wage(100, "1000.00"),
            untaxed(200, "30.68"),
            line(201, "CREDIT", "19.32"),
            untaxed(260, "50.00"),
            line(271, "CREDIT", "100.00"),
            untaxed(230, "40.00"),
            line(700, "DEBIT", "111.93"));
    List<LineRow> move =
        Arrays.asList(
            row(1, "6041", "1000.00", null),
            row(2, "6094", "240.00", null),
            row(3, "4211", null, "1240.00"),
            row(4, "4211", "111.93", null),
            row(5, "4541", null, "111.93"));

    Result result = route(move, payroll(slip, "01", false, "19.32"), noInvoices());

    // 111.93 по облагаемото възнаграждение: 1000 срещу 100 (перо 271) — най-големият остатък.
    assertEquals(Collections.singletonList("700/700500/101.75"), codes(result, 1));
    assertEquals(
        Arrays.asList("600/600020/1.93", "600/600030/0.00", "700/700500/10.18"), codes(result, 2));
    assertEquals(
        Arrays.asList("600/600020/0.00", "600/600030/0.00", "700/700500/0.00"), codes(result, 3));
    assertEquals(Collections.emptyList(), codes(result, 4));
    assertEquals(Collections.emptyList(), result.getFindings());
  }

  @Test
  @DisplayName("под капачката: 600020 с 0.00 (QA с. 54), без превишение")
  void подКапачката() {
    List<BgPaySlipLine> slip =
        Arrays.asList(wage(100, "1000.00"), untaxed(204, "30.00"), line(700, "DEBIT", "90.00"));
    List<LineRow> move =
        Arrays.asList(
            row(1, "6041", "1000.00", null),
            row(2, "6094", "30.00", null),
            row(3, "4211", null, "1030.00"),
            row(4, "4211", "90.00", null),
            row(5, "4541", null, "90.00"));

    Result result = route(move, payroll(slip, "01", false, "0.00"), noInvoices());

    assertEquals(Collections.singletonList("600/600020/0.00"), codes(result, 2));
  }

  @Test
  @DisplayName("паричната помощ под личната капачка: 700500 с 0.00 (решение 5)")
  void паричнаПомощ() {
    List<BgPaySlipLine> slip =
        Arrays.asList(wage(100, "1000.00"), untaxed(220, "500.00"), line(700, "DEBIT", "90.00"));
    List<LineRow> move =
        Arrays.asList(
            row(1, "6041", "1000.00", null),
            row(2, "6094", "500.00", null),
            row(3, "4211", null, "1500.00"),
            row(4, "4211", "90.00", null),
            row(5, "4541", null, "90.00"));

    Result result = route(move, payroll(slip, "01", false, "0.00"), noInvoices());

    assertEquals(Collections.singletonList("700/700500/90.00"), codes(result, 1));
    assertEquals(Collections.singletonList("700/700500/0.00"), codes(result, 2));
  }

  @Test
  @DisplayName("непознато перо 2xx → ГРЕШКА, без кодове за статията")
  void непознатоСоциалноПеро() {
    List<BgPaySlipLine> slip = Arrays.asList(wage(100, "1000.00"), line(299, "CREDIT", "10.00"));
    List<LineRow> move =
        Arrays.asList(
            row(1, "6041", "1000.00", null),
            row(2, "6094", "10.00", null),
            row(3, "4211", null, "1010.00"));

    Result result = route(move, payroll(slip, "01", false, "0.00"), noInvoices());

    assertEquals(
        Collections.singletonList("ERROR SAFT_GLE_WHT_PAYROLL_UNKNOWN_LINE"), kinds(result));
    assertEquals(Collections.emptyList(), codes(result, 1));
    assertEquals(Collections.emptyList(), codes(result, 2));
  }

  @Test
  @DisplayName("фиш, чийто план днес отказва → ПРЕДУПРЕЖДЕНИЕ, без кодове")
  void планътОтказва() {
    List<BgPaySlipLine> slip = Arrays.asList(wage(100, "1000.00"), line(300, "CREDIT", "50.00"));
    List<LineRow> move =
        Arrays.asList(row(1, "6041", "1050.00", null), row(2, "4211", null, "1050.00"));

    Result result = route(move, payroll(slip, "01", false, "0.00"), noInvoices());

    assertEquals(
        Collections.singletonList("WARNING SAFT_GLE_WHT_PAYROLL_PLAN_REFUSED"), kinds(result));
    assertEquals(Collections.emptyList(), codes(result, 1));
  }

  @Test
  @DisplayName("статия, която се разминава с плана на фиша → ПРЕДУПРЕЖДЕНИЕ, кодовете остават")
  void разминаване() {
    List<BgPaySlipLine> slip = Arrays.asList(wage(100, "1000.00"), line(700, "DEBIT", "90.00"));
    List<LineRow> move =
        Arrays.asList(
            row(1, "6041", "1200.00", null),
            row(2, "4211", null, "1200.00"),
            row(3, "4211", "90.00", null),
            row(4, "4541", null, "90.00"));

    Result result = route(move, payroll(slip, "01", false, "0.00"), noInvoices());

    assertEquals(Collections.singletonList("WARNING SAFT_GLE_WHT_PAYROLL_DRIFT"), kinds(result));
    assertEquals(Collections.singletonList("700/700500/90.00"), codes(result, 1));
  }

  // ═══════════════════════════ ФАКТУРА ═══════════════════════════

  @Test
  @DisplayName("услуга от ФЛ (3073): 700500 с начисления данък на разхода, 0.00 на 401")
  void фактураСтопанскаДейност() {
    List<LineRow> move =
        Arrays.asList(row(1, "60206", "1000.00", null), row(2, "401", null, "1000.00"));

    Result result =
        route(
            move,
            noPayroll(),
            invoice(false, false, income("60206", "3073", "A", "25", "0", "10", "1000.00")));

    // 1000 − 25 % = 750 основа, 10 на сто = 75.
    assertEquals(Collections.singletonList("700/700500/75.00"), codes(result, 1));
    assertEquals(Collections.singletonList("700/700500/0.00"), codes(result, 2));
    assertEquals(Collections.emptyList(), result.getFindings());
  }

  @Test
  @DisplayName("окончателен данък (806): 700080, 10 на сто от брутото")
  void фактураОкончателен() {
    List<LineRow> move =
        Arrays.asList(row(1, "60206", "1000.00", null), row(2, "401", null, "1000.00"));

    Result result =
        route(
            move,
            noPayroll(),
            invoice(false, false, income("60206", "806", "C", "0", "10", "0", "1000.00")));

    assertEquals(Collections.singletonList("700/700080/100.00"), codes(result, 1));
  }

  @Test
  @DisplayName("два реда по една сметка: данъкът се дели по най-големия остатък")
  void дваРедаПоЕднаСметка() {
    List<LineRow> move =
        Arrays.asList(
            row(1, "60206", "600.00", null),
            row(2, "60206", "401.00", null),
            row(3, "401", null, "1001.00"));

    Result result =
        route(
            move,
            noPayroll(),
            invoice(false, false, income("60206", "3073", "A", "25", "0", "10", "1001.00")));

    // 1001 × 0.75 = 750.75, 10 % = 75.08 (HALF_UP) → 45.00 и 30.08 (остатъкът отива на втория).
    assertEquals(Collections.singletonList("700/700500/45.00"), codes(result, 1));
    assertEquals(Collections.singletonList("700/700500/30.08"), codes(result, 2));
  }

  @Test
  @DisplayName("804 без ръчен код → ГРЕШКА; с ръчен код на разхода — кодът е ръчният")
  void двусмисленВидДоход() {
    List<LineRow> move =
        Arrays.asList(row(1, "60202", "1000.00", null), row(2, "401", null, "1000.00"));
    Map<Long, InvoiceMove> inv =
        invoice(false, false, income("60202", "804", "C", "0", "10", "0", "1000.00"));

    Result bare = route(move, noPayroll(), inv);
    assertEquals(Collections.singletonList("ERROR SAFT_GLE_WHT_AMBIGUOUS"), kinds(bare));
    assertEquals(Collections.emptyList(), codes(bare, 1));

    List<LineRow> manual =
        Arrays.asList(
            row(1, "60202", "1000.00", null).saftTaxCode("700300"), row(2, "401", null, "1000.00"));
    Result coded = route(manual, noPayroll(), inv);
    assertEquals(Collections.emptyList(), coded.getFindings());
    assertEquals(Collections.singletonList("700/700300/100.00"), codes(coded, 1));
    assertEquals(Collections.singletonList("700/700300/0.00"), codes(coded, 2));
  }

  @Test
  @DisplayName("СОЛ с декларация по чл. 43 ал. 5 → 000 и ПРЕДУПРЕЖДЕНИЕ „да се потвърди с НАП“")
  void самоосигуряващоСе() {
    List<LineRow> move =
        Arrays.asList(row(1, "60206", "1000.00", null), row(2, "401", null, "1000.00"));

    Result result =
        route(
            move,
            noPayroll(),
            invoice(true, false, income("60206", "3073", "A", "25", "0", "10", "1000.00")));

    assertEquals(Collections.emptyList(), codes(result, 1));
    assertEquals(Collections.emptyList(), codes(result, 2));
    assertEquals(Collections.singletonList("WARNING SAFT_GLE_WHT_SELF_INSURED"), kinds(result));
  }

  @Test
  @DisplayName("чуждестранно лице → вътрешният код и ПРЕДУПРЕЖДЕНИЕ за СИДДО")
  void чуждестранноЛице() {
    List<LineRow> move =
        Arrays.asList(row(1, "60206", "1000.00", null), row(2, "401", null, "1000.00"));

    Result result =
        route(
            move,
            noPayroll(),
            invoice(false, true, income("60206", "806", "C", "0", "10", "0", "1000.00")));

    assertEquals(Collections.singletonList("700/700080/100.00"), codes(result, 1));
    assertEquals(Collections.singletonList("WARNING SAFT_GLE_WHT_FOREIGN"), kinds(result));
  }

  @Test
  @DisplayName("вид доход без данък при източника (NONE) → нищо и без находка")
  void безДанъкПриИзточника() {
    List<LineRow> move =
        Arrays.asList(row(1, "60206", "1000.00", null), row(2, "401", null, "1000.00"));

    Result result =
        route(
            move,
            noPayroll(),
            invoice(false, false, income("60206", "501", "D", "0", "0", "0", "1000.00")));

    assertEquals(Collections.emptyList(), codes(result, 1));
    assertEquals(Collections.emptyList(), result.getFindings());
  }

  @Test
  @DisplayName("вид доход без ред в картата → ГРЕШКА")
  void видДоходБезРед() {
    List<LineRow> move =
        Arrays.asList(row(1, "60206", "1000.00", null), row(2, "401", null, "1000.00"));

    Result result =
        route(
            move,
            noPayroll(),
            invoice(false, false, income("60206", "9999", "A", "25", "0", "10", "1000.00")));

    assertEquals(Collections.singletonList("ERROR SAFT_GLE_WHT_INCOME_UNMAPPED"), kinds(result));
  }

  // ═══════════════════════════ РЪЧЕН КОД ═══════════════════════════

  @Test
  @DisplayName("представителни: 600010 на разхода и на 6029 с 10 %, 0.00 на 401; ДДС-редът — нищо")
  void представителни() {
    List<LineRow> move =
        Arrays.asList(
            row(1, "60909", "1000.00", null).accountSaftTaxCode("600010"),
            row(2, "6029", "200.00", null),
            row(3, "401", null, "1200.00"));

    Result result = route(move, noPayroll(), noInvoices());

    assertEquals(Collections.singletonList("600/600010/100.00"), codes(result, 1));
    assertEquals(Collections.singletonList("600/600010/20.00"), codes(result, 2));
    assertEquals(Collections.singletonList("600/600010/0.00"), codes(result, 3));
  }

  @Test
  @DisplayName("редът бие сметката; празно = автоматичната логика")
  void редътБиеСметката() {
    List<LineRow> move =
        Arrays.asList(
            row(1, "60909", "1000.00", null).accountSaftTaxCode("600010").saftTaxCode("600040"),
            row(2, "456", null, "1000.00"));

    Result result = route(move, noPayroll(), noInvoices());

    assertEquals(Collections.singletonList("600/600040/30.00"), codes(result, 1));
    assertEquals(Collections.singletonList("600/600040/0.00"), codes(result, 2));
  }

  @Test
  @DisplayName("код без ставка (глоби) → 0.00; код извън 600/700 → ГРЕШКА и нищо")
  void безСтавкаИНевалиден() {
    List<LineRow> fine =
        Arrays.asList(
            row(1, "60902", "500.00", null).saftTaxCode("600080"), row(2, "456", null, "500.00"));
    assertEquals(
        Collections.singletonList("600/600080/0.00"),
        codes(route(fine, noPayroll(), noInvoices()), 1));

    List<LineRow> wrong =
        Arrays.asList(
            row(1, "60909", "500.00", null).saftTaxCode("100211"), row(2, "401", null, "500.00"));
    Result result = route(wrong, noPayroll(), noInvoices());
    assertEquals(Collections.emptyList(), codes(result, 1));
    assertEquals(Collections.emptyList(), codes(result, 2));
    assertEquals(Collections.singletonList("ERROR SAFT_GLE_WHT_CODE_INVALID"), kinds(result));
  }

  @Test
  @DisplayName("статия без нищо от горното → празен резултат")
  void обикновенаСтатия() {
    List<LineRow> move =
        Arrays.asList(row(1, "60212", "100.00", null), row(2, "401", null, "100.00"));
    Result result = route(move, noPayroll(), noInvoices());
    assertTrue(result.getByLine().isEmpty());
    assertEquals(Collections.emptyList(), result.getFindings());
  }

  // ═══════════════════════════ ПОМОЩНИ ═══════════════════════════

  private static final long MOVE = 77L;

  private static Result route(
      List<LineRow> move, Map<Long, PayrollMove> payroll, Map<Long, InvoiceMove> invoices) {
    return BgSaftWithholdingRouter.route(
        move, payroll, invoices, MAPPINGS, BgNraNomenclatures.taxCodes());
  }

  private static LineRow row(long id, String account, String debit, String credit) {
    return new LineRow(id)
        .moveId(MOVE)
        .reference("ST-77")
        .account(id + 1000, account)
        .debit(debit == null ? BigDecimal.ZERO : new BigDecimal(debit))
        .credit(credit == null ? BigDecimal.ZERO : new BigDecimal(credit));
  }

  private static Map<Long, PayrollMove> payroll(
      List<BgPaySlipLine> lines, String insuredType, boolean seafarer, String socialBase) {
    return Collections.singletonMap(
        MOVE,
        new PayrollMove("ФИШ-1", new ArrayList<>(lines), insuredType, seafarer)
            .socialExpenseTaxBase(new BigDecimal(socialBase)));
  }

  private static Map<Long, PayrollMove> noPayroll() {
    return Collections.emptyMap();
  }

  private static Map<Long, InvoiceMove> noInvoices() {
    return Collections.emptyMap();
  }

  private static Map<Long, InvoiceMove> invoice(
      boolean selfInsured, boolean foreign, IncomeLine... lines) {
    return Collections.singletonMap(
        MOVE, new InvoiceMove("ФАКТ-1", selfInsured, foreign, Arrays.asList(lines)));
  }

  private static IncomeLine income(
      String account,
      String code,
      String pattern,
      String expenseRate,
      String finalRate,
      String advanceRate,
      String gross) {
    return new IncomeLine(
        account,
        code,
        pattern,
        new BigDecimal(expenseRate),
        new BigDecimal(finalRate),
        new BigDecimal(advanceRate),
        true,
        new BigDecimal(gross));
  }

  private static BgPaySlipLine wage(int number, String amount) {
    BgPaySlipLine line = line(number, "CREDIT", amount);
    line.setBgFormsGrossWage(true);
    return line;
  }

  private static BgPaySlipLine untaxed(int number, String amount) {
    BgPaySlipLine line = line(number, "CREDIT", amount);
    line.setBgIsTaxable(false);
    return line;
  }

  private static BgPaySlipLine contribution(int number, String fund, String sign, String amount) {
    BgPaySlipLine line = line(number, sign, amount);
    BgContributionFund f = new BgContributionFund();
    f.setCode(fund);
    f.setName(fund);
    line.setBgContributionFund(f);
    return line;
  }

  private static BgPaySlipLine line(int number, String sign, String amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setName("перо " + number);
    line.setBgSign(sign);
    line.setBgAmount(new BigDecimal(amount));
    line.setBgIsTaxable(true);
    return line;
  }

  private static List<String> codes(Result result, long lineId) {
    return result.of(lineId).stream()
        .map(t -> t.getTaxType() + "/" + t.getTaxCode() + "/" + t.getTaxAmount().getAmount())
        .collect(Collectors.toList());
  }

  private static List<String> kinds(Result result) {
    return result.getFindings().stream()
        .map(BgSaftWithholdingRouterTest::kind)
        .collect(Collectors.toList());
  }

  private static String kind(BgDeclarationFileFinding f) {
    return f.getSeverity() + " " + f.getCode();
  }
}
