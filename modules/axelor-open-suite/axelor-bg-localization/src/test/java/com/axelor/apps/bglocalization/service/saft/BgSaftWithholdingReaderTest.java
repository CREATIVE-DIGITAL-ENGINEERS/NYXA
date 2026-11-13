package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgSaftWithholdingRouter.InvoiceMove;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.hibernate.hql.internal.ast.HqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ВХОДОВЕТЕ НА G3b — заявките структурно и синтактично (парсерът на Hibernate, с отрицателна
 * контрола), а превръщането на редовете им в модела на рутера — с числа, без база.
 */
class BgSaftWithholdingReaderTest {

  @Test
  @DisplayName("Шестте заявки минават парсера на Hibernate — а счупената не минава")
  void синтаксисът() {
    for (String hql :
        Arrays.asList(
            BgSaftWithholdingReader.MAPPINGS,
            BgSaftWithholdingReader.SLIPS,
            BgSaftWithholdingReader.SEAFARER_TYPES,
            BgSaftWithholdingReader.INVOICE_LINES,
            BgSaftWithholdingReader.FOREIGN_REFS,
            BgSaftWithholdingReader.PAYMENT_MOVES)) {
      parse(hql);
    }
    assertThrows(Exception.class, () -> parse("SELECT FROM BgPaySlip self WHERE AND"));
  }

  @Test
  @DisplayName("Документите се търсят само по статиите на дневника; фактурите — само за покупка")
  void обхватът() {
    assertTrue(BgSaftWithholdingReader.SLIPS.endsWith(" WHERE self.bgPayrollMove.id IN (:moves)"));
    assertTrue(
        BgSaftWithholdingReader.INVOICE_LINES.contains(
            " WHERE move.id IN (:moves) AND invoice.operationTypeSelect = :purchase"));
    // Ред без вид доход не носи код — JOIN, не LEFT JOIN; сметката и партньорът — LEFT JOIN.
    assertTrue(
        BgSaftWithholdingReader.INVOICE_LINES.contains(" JOIN self.bgIncomeType incomeType"));
    assertFalse(
        BgSaftWithholdingReader.INVOICE_LINES.contains(" LEFT JOIN self.bgIncomeType incomeType"));
    assertTrue(BgSaftWithholdingReader.INVOICE_LINES.contains(" LEFT JOIN self.account account"));
    assertTrue(
        BgSaftWithholdingReader.INVOICE_LINES.contains(" LEFT JOIN invoice.partner partner"));
    assertTrue(BgSaftWithholdingReader.SEAFARER_TYPES.contains("self.bgIsSeafarer = TRUE"));
  }

  @Test
  @DisplayName("Свойствата по пътищата съществуват в модела")
  void свойствата() throws Exception {
    getters(
        "com.axelor.apps.bglocalization.db.BgSafWithholdingMapping",
        "getIncomeTypeCode",
        "getSafTaxCode",
        "getRuleSelect");
    getters(
        "com.axelor.apps.bglocalization.db.BgPaySlip",
        "getBgPayrollMove",
        "getBgPaySlipLineList",
        "getBgSocialExpenseTaxBase",
        "getReference");
    getters("com.axelor.apps.bglocalization.db.BgInsuredType", "getCode", "getBgIsSeafarer");
    getters(
        "com.axelor.apps.account.db.InvoiceLine",
        "getInvoice",
        "getBgIncomeType",
        "getAccount",
        "getExTaxTotal");
    getters(
        "com.axelor.apps.account.db.Invoice",
        "getMove",
        "getInvoiceId",
        "getInvoiceDate",
        "getPartner",
        "getOperationTypeSelect");
    getters("com.axelor.apps.base.db.Partner", "getBgSelfInsuredDeclaredOn");
    getters(
        "com.axelor.apps.bglocalization.db.BgIncomeType",
        "getCode",
        "getTaxPattern",
        "getExpenseRate",
        "getFinalTaxRate",
        "getAdvanceTaxRate",
        "getWithheldByPayer");
    getters(
        "com.axelor.apps.bglocalization.db.BgIndividualIncomePayment",
        "getSource",
        "getSourceRef",
        "getIsForeign");
    getters("com.axelor.apps.account.db.InvoicePayment", "getInvoice");
  }

  @Test
  @DisplayName(
      "Редовете на фактурите → статии: по една на id, СОЛ по датата, чуждестранно по набора")
  void статиитеОтФактури() {
    List<Object[]> rows =
        Arrays.asList(
            row(10L, "Ф-1", "2026-03-10", null, "60206", "3073", "1000.00"),
            row(10L, "Ф-1", "2026-03-10", null, "60202", "401", "500.00"),
            row(11L, "Ф-2", "2026-03-10", "2026-03-11", "60206", "3073", "200.00"),
            row(12L, null, "2026-03-10", "2026-03-01", "60206", "3073", "300.00"));

    Map<Long, InvoiceMove> moves =
        BgSaftWithholdingReader.invoiceMoves(rows, Collections.singleton(11L));

    assertEquals(Arrays.asList(10L, 11L, 12L), new ArrayList<>(moves.keySet()));
    BgSaftGlEntriesAssembler.LineRow l1 = line(1, 10, "60206", "1000.00");
    BgSaftGlEntriesAssembler.LineRow l2 = line(2, 10, "60202", "500.00");
    BgSaftGlEntriesAssembler.LineRow c1 = credit(3, 10, "401", "1500.00");
    BgSaftGlEntriesAssembler.LineRow l4 = line(4, 11, "60206", "200.00");
    BgSaftGlEntriesAssembler.LineRow c4 = credit(5, 11, "401", "200.00");
    BgSaftGlEntriesAssembler.LineRow l6 = line(6, 12, "60206", "300.00");
    BgSaftGlEntriesAssembler.LineRow c6 = credit(7, 12, "401", "300.00");

    Map<String, BgSaftWithholdingRouter.Mapping> mappings = new java.util.HashMap<>();
    mappings.put("3073", new BgSaftWithholdingRouter.Mapping("700500", "CODE"));
    mappings.put("401", new BgSaftWithholdingRouter.Mapping("700500", "CODE"));
    BgSaftWithholdingRouter.Result result =
        BgSaftWithholdingRouter.route(
            Arrays.asList(l1, l2, c1, l4, c4, l6, c6),
            Collections.emptyMap(),
            moves,
            mappings,
            BgNraNomenclatures.taxCodes());

    // 10: 3073 → 1000 × 0.75 × 10 % = 75; 401 → 500 × 0.9 × 10 % = 45 (шаблон B).
    assertEquals("700500/75.00", only(result, 1));
    assertEquals("700500/45.00", only(result, 2));
    assertEquals("700500/0.00", only(result, 3));
    // 11: декларацията е от СЛЕДВАЩИЯ ден — не заварва фактурата; чуждестранно → предупреждение.
    assertEquals("700500/15.00", only(result, 4));
    // 12: СОЛ, деклариран преди фактурата → 000.
    assertEquals(Collections.emptyList(), result.of(6L));
    List<String> codes =
        result.getFindings().stream().map(f -> f.getCode()).collect(Collectors.toList());
    assertEquals(Arrays.asList("SAFT_GLE_WHT_SELF_INSURED", "SAFT_GLE_WHT_FOREIGN"), codes);
    assertTrue(result.getFindings().get(0).getMessage().contains("#12"));
  }

  @Test
  @DisplayName("Декларацията по чл. 43 ал. 5 заварва фактура от СЪЩИЯ ден, не от предишния")
  void декларацията() {
    LocalDate day = LocalDate.of(2026, 3, 10);
    assertTrue(BgSaftWithholdingReader.declaredBy(day, day));
    assertTrue(BgSaftWithholdingReader.declaredBy(day.minusDays(1), day));
    assertFalse(BgSaftWithholdingReader.declaredBy(day.plusDays(1), day));
    assertFalse(BgSaftWithholdingReader.declaredBy(null, day));
    assertFalse(BgSaftWithholdingReader.declaredBy(day, null));
  }

  @Test
  @DisplayName("Референциите на регистъра → id на плащане; чуждите и счупените отпадат")
  void референциите() {
    assertEquals(
        Arrays.asList(5L, 77L),
        BgSaftWithholdingReader.paymentIds(
            Arrays.asList(
                "InvoicePayment#5",
                "BgPaySlip#9",
                "InvoicePayment#x",
                null,
                " InvoicePayment#77 ")));
  }

  @Test
  @DisplayName("IN се реже на парчета по 10 000, без повторения")
  void парчетата() {
    List<Long> ids = LongStream.rangeClosed(1, 20_001).boxed().collect(Collectors.toList());
    ids.add(1L);
    List<List<Long>> chunks = BgSaftWithholdingReader.chunks(ids);
    assertEquals(
        Arrays.asList(10_000, 10_000, 1),
        chunks.stream().map(List::size).collect(Collectors.toList()));
    assertTrue(BgSaftWithholdingReader.chunks(Collections.emptyList()).isEmpty());
  }

  // ─────────────── помощни ───────────────

  private static Object[] row(
      Long move,
      String number,
      String invoiceDate,
      String declaredOn,
      String account,
      String incomeType,
      String gross) {
    boolean business = "3073".equals(incomeType);
    return new Object[] {
      move,
      number,
      LocalDate.parse(invoiceDate),
      declaredOn == null ? null : LocalDate.parse(declaredOn),
      account,
      incomeType,
      business ? "A" : "B",
      new BigDecimal(business ? "25" : "10"),
      BigDecimal.ZERO,
      new BigDecimal("10"),
      Boolean.TRUE,
      new BigDecimal(gross)
    };
  }

  private static BgSaftGlEntriesAssembler.LineRow line(
      long id, long move, String account, String debit) {
    return new BgSaftGlEntriesAssembler.LineRow(id)
        .moveId(move)
        .account(id, account)
        .debit(new BigDecimal(debit))
        .credit(BigDecimal.ZERO);
  }

  private static BgSaftGlEntriesAssembler.LineRow credit(
      long id, long move, String account, String credit) {
    return new BgSaftGlEntriesAssembler.LineRow(id)
        .moveId(move)
        .account(id, account)
        .debit(BigDecimal.ZERO)
        .credit(new BigDecimal(credit));
  }

  private static String only(BgSaftWithholdingRouter.Result result, long id) {
    assertEquals(1, result.of(id).size(), "редът носи точно един код");
    return result.of(id).get(0).getTaxCode()
        + "/"
        + result.of(id).get(0).getTaxAmount().getAmount();
  }

  private static void getters(String type, String... names) throws Exception {
    Class<?> c = Class.forName(type);
    for (String name : names) {
      c.getMethod(name);
    }
  }

  private static void parse(String hql) {
    HqlParser parser = HqlParser.getInstance(hql);
    try {
      parser.statement();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    parser.getParseErrorHandler().throwQueryException();
  }
}
