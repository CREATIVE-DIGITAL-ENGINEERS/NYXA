package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.hibernate.hql.internal.ast.HqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BgSaftInvoicesReaderTest {

  @Test
  @DisplayName("Фактурите са тези на статиите на дневника — PERIOD_MOVES дословно, като G2c")
  void същият_предикат() {
    assertEquals(
        "SELECT invoice.id FROM Invoice invoice JOIN invoice.move self WHERE "
            + BgSaftMoveScope.PERIOD_MOVES
            + " ORDER BY invoice.id",
        BgSaftInvoicesReader.INVOICE_IDS);
    // Продуктите на фактурите (G2c) стъпват на същото съединение и същия предикат.
    assertTrue(
        BgSaftMasterDataReader.INVOICED_PRODUCTS.contains(
            "JOIN invoice.move self JOIN line.product product"));
    assertTrue(BgSaftMasterDataReader.INVOICED_PRODUCTS.endsWith(BgSaftMoveScope.PERIOD_MOVES));
    assertTrue(BgSaftInvoicesReader.VAT_MOVES.contains(BgSaftMoveScope.PERIOD_MOVES));

    BgSaftMoveScope scope = BgSaftMoveScope.of(BgSaftFixtures.april());
    Set<String> period = Set.of("companyId", "posted", "fromDate", "toDate", "openingOrigin");
    assertEquals(period, scope.parameters(BgSaftInvoicesReader.INVOICE_IDS).keySet());
    assertEquals(period, scope.parameters(BgSaftInvoicesReader.VAT_MOVES).keySet());
  }

  @Test
  @DisplayName("Статиите с ДДС без фактура: операция, кредитен тип ИЛИ данък — не isVatRelevant")
  void статиите_без_фактура() {
    String jpql = BgSaftInvoicesReader.VAT_MOVES;
    assertTrue(jpql.contains(" AND self.invoice IS NULL"));
    // Протоколът по чл. 79 има операция, но редовете му нямат данък — влиза по операцията.
    assertTrue(jpql.contains("self.bgVatMoveVatOperation <> ''"));
    assertTrue(jpql.contains("line.bgVatCreditType <> ''"));
    assertTrue(jpql.contains("line.taxLineSet IS NOT EMPTY"));
    assertTrue(jpql.contains("line.taxAmount <> 0"));
  }

  /** Читател без база: id-тата са подадени, парчетата и изчистванията се броят. */
  private static final class Counting extends BgSaftInvoicesReader {
    private final List<String> calls = new ArrayList<>();

    private Counting() {
      super(null, null);
    }

    @Override
    protected List<Long> invoiceIds(BgSaftMoveScope scope) {
      return LongStream.rangeClosed(1, BgSaftGlEntriesReader.CHUNK + 1)
          .boxed()
          .collect(Collectors.toList());
    }

    @Override
    protected void chunk(List<Long> ids, BgSaftInvoicesAssembler.Source source) {
      calls.add("chunk " + ids.size());
      ids.forEach(id -> source.add(new BgSaftInvoicesAssembler.InvoiceRow(id).partner(id, null)));
    }

    @Override
    protected List<Long> moveIds(String jpql, BgSaftMoveScope scope) {
      calls.add(jpql == BgSaftInvoicesReader.VAT_MOVES ? "vatMoves" : "diaryOnly");
      return List.of();
    }

    @Override
    protected BgSaftCashAccounting cashAccounting(BgSaftMoveScope scope) {
      calls.add("cashAccounting");
      return BgSaftCashAccounting.NONE;
    }

    @Override
    protected void clear() {
      calls.add("clear");
    }

    @Override
    protected void reportingPeriods(BgSaftMoveScope scope, BgSaftInvoicesAssembler.Source source) {
      calls.add("reportingPeriods");
    }
  }

  @Test
  @DisplayName("Парчетата и изчистването са тези на дневника (FEC)")
  void парчетата() {
    Counting reader = new Counting();
    BgSaftInvoicesAssembler.Source source = reader.source(null);
    assertEquals(
        List.of(
            "cashAccounting",
            "chunk 10000",
            "clear",
            "chunk 1",
            "clear",
            "vatMoves",
            "diaryOnly",
            "reportingPeriods"),
        reader.calls);
    assertEquals(BgSaftGlEntriesReader.CHUNK + 1, source.partnerIds().size());
  }

  @Test
  @DisplayName("Заявките минават парсера на Hibernate — а счупената не минава")
  void синтаксисът() {
    for (String jpql :
        List.of(
            BgSaftInvoicesReader.INVOICE_IDS,
            BgSaftInvoicesReader.INVOICES,
            BgSaftInvoicesReader.LINES,
            BgSaftInvoicesReader.LINE_TAXES,
            BgSaftInvoicesReader.INVOICE_TAXES,
            BgSaftInvoicesReader.VAT_MOVES,
            BgSaftInvoicesReader.DIARY_ONLY_MOVES,
            BgSaftInvoicesReader.MOVES,
            BgSaftInvoicesReader.CASH_ACCOUNTING,
            BgSaftInvoicesReader.REPORTED_ELSEWHERE,
            BgSaftInvoicesReader.REPORTED_HERE)) {
      parse(jpql);
    }
    assertThrows(
        Exception.class,
        () -> parse("SELECT invoice.id FROM Invoice invoice WHERE invoice.move IS"));
  }

  @Test
  @DisplayName("Свойствата по пътищата съществуват в модела")
  void свойствата() throws Exception {
    Map<String, List<String>> getters =
        Map.of(
            "com.axelor.apps.account.db.Invoice",
            List.of(
                "getInvoiceId",
                "getSupplierInvoiceNb",
                "getOperationTypeSelect",
                "getNapDocumentTypeSelect",
                "getInvoiceDate",
                "getOriginDate",
                "getPartner",
                "getPartnerAccount",
                "getMove",
                "getBgVatSupplyType"),
            "com.axelor.apps.account.db.InvoiceLine",
            List.of(
                "getInvoice",
                "getSequence",
                "getTypeSelect",
                "getProduct",
                "getProductName",
                "getDescription",
                "getQty",
                "getCompanyExTaxTotal",
                "getCompanyInTaxTotal",
                "getAccount",
                "getBgVatCreditType",
                "getTaxLineSet"),
            "com.axelor.apps.account.db.InvoiceLineTax",
            List.of("getInvoice", "getTaxLine", "getCompanyTaxTotal", "getReverseCharged"),
            "com.axelor.apps.account.db.TaxLine",
            List.of("getTax", "getValue"),
            "com.axelor.apps.account.db.Move",
            List.of(
                "getInvoice",
                "getBgVatMoveVatOperation",
                "getBgVatMoveVatRate",
                "getPeriod",
                "getReference",
                "getDescription",
                "getPartner",
                "getIgnoreInAccountingOk",
                "getFunctionalOriginSelect"),
            "com.axelor.apps.account.db.MoveLine",
            List.of(
                "getMove",
                "getBgVatCreditType",
                "getTaxLineSet",
                "getTaxAmount",
                "getTaxRate",
                "getCounter",
                "getAccount",
                "getPartner",
                "getDebit",
                "getCredit"),
            "com.axelor.apps.bglocalization.db.BgSafVatCellCode",
            List.of("getSafTaxCode", "getCellCode", "getCellValue"));
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : getters.entrySet()) {
      Class<?> type = Class.forName(entry.getKey());
      for (String getter : entry.getValue()) {
        try {
          type.getMethod(getter);
        } catch (NoSuchMethodException e) {
          missing.add(entry.getKey() + "#" + getter);
        }
      }
    }
    assertEquals(List.of(), missing);
    assertFalse(BgSaftInvoicesReader.LINES.contains(" JOIN line.taxLineSet"), "данъците — отделно");
  }

  @Test
  @DisplayName("DIARY_ONLY_MOVES е точно обхватът на дневника минус този на GL — без застъпване")
  void извън_GL() {
    String vat = BgSaftInvoicesReader.WITH_VAT;
    assertTrue(BgSaftInvoicesReader.VAT_MOVES.contains(vat));
    assertTrue(BgSaftInvoicesReader.DIARY_ONLY_MOVES.contains(vat));
    String gl = BgSaftJpqlWhere.where(BgSaftInvoicesReader.VAT_MOVES).replace(vat, "");
    String diaryOnly =
        BgSaftJpqlWhere.where(BgSaftInvoicesReader.DIARY_ONLY_MOVES).replace(vat, "");
    BgSaftMoveScope scope = BgSaftMoveScope.of(BgSaftFixtures.april());
    Map<String, Object> parameters = new java.util.HashMap<>();
    parameters.putAll(scope.parameters(BgSaftInvoicesReader.VAT_MOVES));
    parameters.putAll(scope.parameters(BgSaftInvoicesReader.DIARY_ONLY_MOVES));
    Object opening = parameters.get("openingOrigin");
    java.time.LocalDate from = (java.time.LocalDate) parameters.get("fromDate");
    int cases = 0;
    for (Boolean ignored : java.util.Arrays.asList(null, false, true)) {
      for (Object origin : java.util.Arrays.asList(null, opening, 99)) {
        for (java.time.LocalDate date : List.of(from, from.minusDays(1))) {
          Map<String, Object> row =
              Map.of(
                  "self.company.id", parameters.get("companyId"),
                  "self.statusSelect", parameters.get("posted"),
                  "self.date", date);
          java.util.function.Function<String, Object> path =
              p ->
                  p.equals("self.ignoreInAccountingOk")
                      ? ignored
                      : p.equals("self.functionalOriginSelect") ? origin : row.get(p);
          boolean inGl = BgSaftJpqlWhere.matches(gl, parameters, path);
          boolean outside = BgSaftJpqlWhere.matches(diaryOnly, parameters, path);
          boolean inDiary = date.equals(from); // обхватът на дневника: дружество, статус, дата
          assertFalse(inGl && outside, ignored + "/" + origin);
          assertEquals(inDiary, inGl || outside, ignored + "/" + origin + "/" + date);
          cases++;
        }
      }
    }
    assertEquals(18, cases);
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
