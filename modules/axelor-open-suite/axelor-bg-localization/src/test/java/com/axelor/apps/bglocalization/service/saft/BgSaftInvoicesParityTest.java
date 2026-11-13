package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountConfig;
import com.axelor.apps.account.db.Invoice;
import com.axelor.apps.account.db.InvoiceLine;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoice;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftInvoiceSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.vat.BgVatDocumentCollector;
import com.axelor.apps.bglocalization.service.vat.BgVatExportService;
import com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolFixtures;
import com.axelor.apps.bglocalization.service.vat.BgVatInvoiceProtocolService;
import com.axelor.apps.bglocalization.service.vat.BgVatMoveDiaryRows;
import com.axelor.apps.bglocalization.service.vat.BgVatValidator;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseTaxCodeMapping;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЕДНО ПРАВИЛО, ДВЕ МЕСТА — И ТЕСТ, ЧЕ СА ЕДНО И СЪЩО.
 *
 * <p>Колекторът на Блок Б остава непипнат, затова правилата, които SAF-T споделя с дневниците, са
 * преписани в {@link BgSaftInvoicesAssembler} — и тук се сверяват с колектора върху едни и същи
 * фактури: номерът на документа (S.I.1 = номерът в PRODAGBI/POKUPKI) и дали покупката влиза в
 * дневника за покупки (от това зависи 100330 срещу 000000 за ред без данък). TransactionID (S.I.18)
 * се сверява с GL.9 на дневника (G3).
 *
 * <p><b>ПРОТОКОЛИТЕ (G5b)</b> не преписват правило: SAF-T чете реда, който ИСТИНСКИЯТ колектор
 * прави от статията. Тук статиите минават през колектора и през четеца на SAF-T, а всеки документ
 * се сверява с реда си в дневника — номер (02-05/03-06), дата (02-06/03-07), вид, основа и данък,
 * код ↔ колона. Изключенията от паритета са изброени, всяко с нормата си.
 */
class BgSaftInvoicesParityTest {

  /** Само за да се стигне до {@code protected enrichFromInvoice}. Репозиторият не се ползва. */
  private static final class Collector extends BgVatDocumentCollector {
    Collector() {
      super(null);
    }

    BgVatDocumentDto of(Invoice invoice) {
      BgVatDocumentDto document = new BgVatDocumentDto();
      enrichFromInvoice(document, invoice);
      return document;
    }
  }

  private static final Collector COLLECTOR = new Collector();

  @Test
  @DisplayName("S.I.1 е номерът, който Блок Б пише в дневника — за всяка комбинация")
  void номерът() {
    String[][] cases = {
      {null, "S-1"}, {"", "S-1"}, {"   ", "S-1"}, {"INV-77", "PUR-1"}, {" INV-78 ", "PUR-2"}
    };
    for (String[] c : cases) {
      Invoice invoice = new Invoice();
      invoice.setSupplierInvoiceNb(c[0]);
      invoice.setInvoiceId(c[1]);
      assertEquals(
          COLLECTOR.of(invoice).getDocumentNumber(),
          BgSaftInvoicesAssembler.documentNumber(c[0], c[1]),
          Arrays.toString(c));
    }
  }

  private static InvoiceLine line(String exTax, String inTax, String creditType) {
    InvoiceLine line = new InvoiceLine();
    line.setCompanyExTaxTotal(new BigDecimal(exTax));
    line.setCompanyInTaxTotal(new BigDecimal(inTax));
    line.setBgVatCreditType(creditType);
    return line;
  }

  private static BgSaftInvoicesAssembler.LineRow row(InvoiceLine line) {
    return new BgSaftInvoicesAssembler.LineRow(1L, 1L)
        .amounts(line.getCompanyExTaxTotal(), line.getCompanyInTaxTotal())
        .creditType(line.getBgVatCreditType());
  }

  @Test
  @DisplayName("„Влиза ли в POKUPKI“ е предикатът на колектора — и при нулеви и сгънати редове")
  void дневникът_за_покупки() {
    List<List<InvoiceLine>> cases =
        List.of(
            List.of(line("100", "120", "1")),
            List.of(line("0", "0", "1")),
            List.of(line("0", "0", null), line("0", "0", "2")),
            // к.9 сгъва ДО + ДДС: 10 + (−10) = 0 — колоната не се пълни.
            List.of(line("10", "0", "3")),
            List.of(line("-10", "-10", "3")),
            List.of(line("0", "5", "2")),
            List.of(line("5", "5", null)));
    for (int operation : new int[] {1, 2, 3, 4}) {
      for (List<InvoiceLine> lines : cases) {
        Invoice invoice = new Invoice();
        invoice.setOperationTypeSelect(operation);
        invoice.setInvoiceLineList(new ArrayList<>(lines));
        List<BgSaftInvoicesAssembler.LineRow> rows = new ArrayList<>();
        lines.forEach(l -> rows.add(row(l)));
        boolean collector = !COLLECTOR.of(invoice).getPurchaseAmounts().isEmpty();
        assertEquals(
            collector,
            BgSaftInvoicesAssembler.inPurchaseDiary(operation, rows),
            "операция " + operation + ", редове " + rows.size());
      }
    }
  }

  @Test
  @DisplayName("Покупен данък с неподвижна колона: кодът на SAF-T е клетката на колоната в Блок Б")
  void неподвижната_колона() {
    Map<String, BgSaftTaxTableAssembler.MappingRow> mappings =
        BgSaftInvoiceFixtures.seedTaxMappings();
    Map<String, String> cells = BgSaftInvoiceFixtures.seedCellCodes();
    for (String tax : BgVatPurchaseTaxCodeMapping.taxCodes()) {
      String diary =
          cells.get(BgSaftVatCell.ofPurchase(BgVatPurchaseTaxCodeMapping.forTaxCode(tax).get()));
      for (String credit : new String[] {null, "1", "2", "3"}) {
        BgSaftInvoiceTaxRouter.Route route =
            BgSaftInvoiceTaxRouter.route(false, tax, credit, mappings.get(tax));
        String saft =
            route.getKind() == BgSaftInvoiceTaxRouter.Kind.CELL
                ? cells.get(route.getValue())
                : route.getValue();
        assertEquals(diary, saft, tax + "/" + credit);
      }
    }
  }

  @Test
  @DisplayName("S.I.18 е GL.9: номерът на статията без интервалите, а без номер — id-то ѝ")
  void транзакцията() {
    LocalDate april = LocalDate.of(2026, 4, 1);
    List<BgSaftGlEntriesAssembler.LineRow> lines =
        List.of(
            new BgSaftGlEntriesAssembler.LineRow(1L)
                .counter(1)
                .debit(BigDecimal.TEN)
                .account(3L, "702")
                .periodFrom(april)
                .moveId(77L)
                .reference("  T-9  "),
            new BgSaftGlEntriesAssembler.LineRow(2L)
                .counter(1)
                .debit(BigDecimal.TEN)
                .account(3L, "702")
                .periodFrom(april)
                .moveId(78L)
                .reference(null));
    BgSaftGlEntriesAssembler.Result gl =
        BgSaftGlEntriesAssembler.assemble(
            lines,
            BgSaftFixtures.glChart(),
            BgSaftFixtures.glMappings(),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.glTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID);
    Set<String> journal = new TreeSet<>();
    for (SaftJournal j : gl.getGeneralLedger().getJournals()) {
      for (SaftTransaction t : j.getTransactions()) {
        journal.add(t.getTransactionId());
      }
    }
    Set<String> invoices =
        new TreeSet<>(
            List.of(
                BgSaftInvoicesAssembler.transactionReference("  T-9  ", 77L),
                BgSaftInvoicesAssembler.transactionReference(null, 78L)));
    assertEquals(journal, invoices);
    assertEquals(new TreeSet<>(List.of("78", "T-9")), invoices);
  }

  // ═══════════════ G5b: протоколите и статиите с ДДС без фактура ═══════════════

  private static final LocalDate APRIL_20 = LocalDate.of(2026, 4, 20);
  private static final BgVatDocumentCollector REAL = new BgVatDocumentCollector(null);
  private static final BgVatSalesAdapter SALES = new BgVatSalesAdapter();
  private static final BgVatMoveDiaryRows ROWS =
      new BgVatMoveDiaryRows(
          REAL, new BgVatExportService(REAL, null, null, SALES, null, null, null), SALES);
  private static final BgSaftInvoicesReader READER = new BgSaftInvoicesReader(null, ROWS);

  /** Доставчик извън ЕС — за вноса по чл. 167а; не е в Suppliers. */
  private static final long CHINA = 40L;

  /**
   * Код ↔ колони в дневника — независимо от продукционния код: основата и данъкът на всеки ред на
   * SAF-T са в тези колони. 100330 при протокол е частта от к.9 (основа + данък).
   */
  private static final Map<String, List<Enum<?>>> COLUMNS = columns();

  private static Map<String, List<Enum<?>>> columns() {
    Map<String, List<Enum<?>>> m = new LinkedHashMap<>();
    m.put("100212", List.of(BgVatSalesColumn.K13_BASE_ICA, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put("100220", List.of(BgVatSalesColumn.K13_BASE_ICA, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put("100226", List.of(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put("100227", List.of(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put("100228", List.of(BgVatSalesColumn.K14_BASE_RC, BgVatSalesColumn.K15_VAT_ICA_RC));
    m.put("100229", List.of(BgVatSalesColumn.K11_BASE_20, BgVatSalesColumn.K12_VAT_20));
    m.put("100211", List.of(BgVatSalesColumn.K11_BASE_20, BgVatSalesColumn.K12_VAT_20));
    m.put(
        "100331",
        List.of(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    m.put(
        "100332",
        List.of(
            BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT,
            BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT));
    m.put("100330", List.of(BgVatPurchaseColumn.K09_BASE_NO_CREDIT));
    m.put("100344", List.of(BgVatPurchaseColumn.K15_BASE_TRIANGULAR_ACQ));
    // Годишната корекция по чл. 73 ал. 8 — само данък, основа 0.
    m.put("100242", List.of(BgVatPurchaseColumn.K14_ANNUAL_ADJUSTMENT));
    return m;
  }

  /**
   * Покупната страна на протокола по чл. 163а и чл. 167а — ЕДНА клетка: основата на протокола и
   * ползваният кредит (к.11 + к.13), отговорът на НАП по имейл от 19.10.2026. Не е сбор от колони.
   */
  private static final Set<String> CREDIT_USED = Set.of("100345", "100346", "100347");

  // ─────────────── статиите ───────────────

  private static Account account(long id, String code) {
    Account a = new Account();
    a.setId(id);
    a.setCode(code);
    return a;
  }

  private static Partner partner(long id, String taxNbr) {
    Partner p = new Partner();
    p.setId(id);
    p.setFullName("Партньор " + id);
    p.setTaxNbr(taxNbr);
    return p;
  }

  private static MoveLine line(long account, String code, String debit, String credit) {
    MoveLine l = new MoveLine();
    l.setAccount(account(account, code));
    l.setDebit(new BigDecimal(debit));
    l.setCredit(new BigDecimal(credit));
    return l;
  }

  /** Базовият ред, както го оставя осчетоводяването: данъкът е на реда, ставката в ПРОЦЕНТИ. */
  private static MoveLine base(
      long account, String code, String debit, String rate, String credit) {
    Tax tax = new Tax();
    tax.setCode("BG_SRP_20");
    TaxLine taxLine = new TaxLine();
    taxLine.setTax(tax);
    taxLine.setValue(new BigDecimal(rate));
    MoveLine l = line(account, code, debit, "0");
    l.setTaxLineSet(new HashSet<>(Collections.singleton(taxLine)));
    l.setTaxRate(taxLine.getValue());
    l.setBgVatCreditType(credit);
    return l;
  }

  private static MoveLine vatIn(String amount) {
    return line(BgSaftInvoiceFixtures.ACCOUNT_VAT_PURCHASES, "4531", amount, "0");
  }

  private static MoveLine vatOut(String amount) {
    return line(BgSaftInvoiceFixtures.ACCOUNT_VAT_SALES, "4532", "0", amount);
  }

  private static Move move(long id, String operation, Partner partner, MoveLine... lines) {
    Period period = new Period();
    period.setFromDate(BgSaftInvoiceFixtures.PERIOD);
    Move m = new Move();
    m.setId(id);
    m.setReference("MOV-" + id);
    m.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    m.setDate(APRIL_20);
    m.setPeriod(period);
    m.setPartner(partner);
    m.setNapDocumentTypeSelect("09");
    m.setBgVatMoveVatOperation(operation);
    m.setBgVatProtocolNumber(String.format("%010d", id));
    m.setMoveLineList(new ArrayList<>(Arrays.asList(lines)));
    return m;
  }

  /** ВОП/чл. 82/163а 100 при ставка {@code rate}: Дт 304 / Кт 401, Дт 4531 / Кт 4532. */
  private static Move selfCharge(long id, String operation, String rate, String tax) {
    return move(
        id,
        operation,
        partner(BgSaftInvoiceFixtures.BETA, "DE123456789"),
        base(BgSaftInvoiceFixtures.ACCOUNT_GOODS, "304", "100", rate, "1"),
        line(BgSaftInvoiceFixtures.ACCOUNT_SUPPLIERS, "401", "0", "100"),
        vatIn(tax),
        vatOut(tax));
  }

  /**
   * Внос по чл. 167а 100/20: Дт 304 / Кт 458 (митата и разчетът с митницата), Дт 4531 / Кт 4532.
   */
  private static Move import167a(long id, String rate, String tax) {
    Move m =
        move(
            id,
            "IMPORT_167A",
            partner(CHINA, null),
            base(BgSaftInvoiceFixtures.ACCOUNT_GOODS, "304", "100", rate, "1"),
            line(BgSaftInvoiceFixtures.ACCOUNT_CUSTOMS, "458", "0", "100"),
            vatIn(tax),
            vatOut(tax));
    m.setOrigin("26BG001000000123R4");
    m.setOriginDate(BgSaftInvoiceFixtures.APRIL_12);
    return m;
  }

  // ─────────────── сглобяването ───────────────

  private static BgSaftInvoicesAssembler.Result saft(Move... moves) {
    return saft(BgSaftInvoiceFixtures.seedCellCodes(), moves);
  }

  private static BgSaftInvoicesAssembler.Result saft(Map<String, String> cellCodes, Move... moves) {
    return saft(cellCodes, BgSaftCashAccounting.NONE, moves);
  }

  private static BgSaftInvoicesAssembler.Result saft(
      Map<String, String> cellCodes, BgSaftCashAccounting cash, Move... moves) {
    BgSaftInvoicesAssembler.Source source =
        new BgSaftInvoicesAssembler.Source().cashAccounting(cash);
    for (Move m : moves) {
      READER.diaryDocuments(m).forEach(source::add);
    }
    Map<Long, BgSaftPartiesAssembler.PartyRow> partners =
        new HashMap<>(BgSaftFixtures.glPartners());
    partners.put(
        CHINA,
        new BgSaftPartiesAssembler.PartyRow(CHINA)
            .partnerSeq("P40")
            .partnerTypeSelect(1)
            .name("Shenzhen Ltd")
            .city("Shenzhen")
            .country("CN"));
    return BgSaftInvoicesAssembler.assemble(
        source,
        BgSaftInvoiceFixtures.chart(),
        BgSaftInvoiceFixtures.mappings(),
        partners,
        BgSaftInvoiceFixtures.seedTaxMappings(),
        cellCodes,
        BgNraNomenclatures.taxCodes(),
        BgNraNomenclatures.invoiceTypes(),
        BgSaftInvoiceFixtures.OWN);
  }

  private static List<String> errors(BgSaftInvoicesAssembler.Result result) {
    return result.getFindings().stream()
        .filter(BgDeclarationFileFinding::isBlocking)
        .map(f -> f.getCode() + ": " + f.getMessage())
        .collect(Collectors.toList());
  }

  private static BgDeclarationFileFinding finding(
      BgSaftInvoicesAssembler.Result result, String code) {
    return BgSaftTestFindings.merged(result.getFindings(), code)
        .orElseThrow(() -> new AssertionError("няма находка " + code + ": " + errors(result)));
  }

  private static SaftInvoice only(SaftInvoiceSection section, String number) {
    List<SaftInvoice> found = new ArrayList<>();
    for (SaftInvoice invoice : section.getInvoices()) {
      if (number.equals(invoice.getInvoiceNo())) {
        found.add(invoice);
      }
    }
    assertEquals(1, found.size(), "документ " + number);
    return found.get(0);
  }

  private static boolean has(SaftInvoiceSection section, String number) {
    for (SaftInvoice invoice : section.getInvoices()) {
      if (number.equals(invoice.getInvoiceNo())) {
        return true;
      }
    }
    return false;
  }

  private static SaftTaxInformation tax(SaftInvoiceLine line) {
    assertEquals(1, line.getTaxInformation().size());
    return line.getTaxInformation().get(0);
  }

  /** Кодовете на редовете на документа, по реда им. */
  private static List<String> codes(SaftInvoice invoice) {
    return invoice.getLines().stream().map(l -> tax(l).getTaxCode()).collect(Collectors.toList());
  }

  private static BigDecimal signed(SaftInvoiceLine line, BigDecimal value) {
    return "D".equals(line.getDebitCreditIndicator()) ? value : value.negate();
  }

  /**
   * ПАРИТЕТЪТ: всеки ред на статията в дневника срещу документа си в SAF-T — номер, дата, вид, а по
   * код основата и данъкът срещу колоните му. Изключенията — изрично, с нормата.
   */
  private static void parity(Move move, BgSaftInvoicesAssembler.Result result) {
    List<BgVatMoveDiaryRows.Row> rows = ROWS.rows(move);
    assertFalse(rows.isEmpty(), "статията е в дневника");
    for (BgVatMoveDiaryRows.Row row : rows) {
      BgVatDocumentDto doc = row.getDocument();
      String number = doc.getDocumentNumber();
      if (doc.isIdentificationOnly()) {
        // ⚠️ ИЗКЛЮЧЕНИЕ: ЕАД по чл. 167а е в дневника за продажби само с колони 1–8 (ППЗДДС чл. 49а
        // ал. 4 т. 2), но НЕ е в SalesInvoices (QA с. 60).
        assertTrue(row.isSales(), "ЕАД е в PRODAGBI");
        assertFalse(has(result.getSales(), number), "ЕАД не е в SalesInvoices");
        // В PurchaseInvoices е със 100330 (QA с. 60), а основата е тази на протокола — в дневника
        // ЕАД няма суми (ППЗДДС чл. 49а ал. 5 т. 2), а данъкът е на протокола.
        SaftInvoice ead = only(result.getPurchases(), number);
        assertEquals("07", ead.getInvoiceType());
        assertEquals(doc.getDocumentDate(), ead.getInvoiceDate());
        assertEquals(List.of("100330"), codes(ead));
        BigDecimal protocolBase = rows.get(0).getSalesColumns().get(BgVatSalesColumn.K11_BASE_20);
        assertEquals(protocolBase, ead.getLines().get(0).getInvoiceLineAmount().getAmount());
        assertEquals(0, tax(ead.getLines().get(0)).getTaxAmount().getAmount().signum());
        continue;
      }
      if (row.isSales()) {
        check(
            only(result.getSales(), number), doc, move, new HashMap<>(row.getSalesColumns()), null);
      } else {
        assertFalse(has(result.getSales(), number), number + " не е в SalesInvoices");
      }
      if (row.isPurchases()) {
        check(
            only(result.getPurchases(), number),
            doc,
            move,
            new HashMap<>(row.getPurchaseColumns()),
            protocolBase(rows));
      } else {
        assertFalse(has(result.getPurchases(), number), number + " не е в PurchaseInvoices");
      }
    }
  }

  /** Основата на протокола — продажбената му страна (к.14 при чл. 163а, к.11 при чл. 167а). */
  private static BigDecimal protocolBase(List<BgVatMoveDiaryRows.Row> rows) {
    BigDecimal base = BigDecimal.ZERO;
    for (BgVatMoveDiaryRows.Row row : rows) {
      if (row.isSales() && !row.getDocument().isIdentificationOnly()) {
        base =
            base.add(
                row.getSalesColumns().getOrDefault(BgVatSalesColumn.K14_BASE_RC, BigDecimal.ZERO));
        base =
            base.add(
                row.getSalesColumns().getOrDefault(BgVatSalesColumn.K11_BASE_20, BigDecimal.ZERO));
      }
    }
    return base;
  }

  private static void check(
      SaftInvoice invoice,
      BgVatDocumentDto doc,
      Move move,
      Map<Enum<?>, BigDecimal> columns,
      BigDecimal protocolBase) {
    String number = doc.getDocumentNumber();
    assertEquals(doc.getDocumentDate(), invoice.getInvoiceDate(), number + " дата");
    assertEquals(doc.getNapDocumentType(), invoice.getInvoiceType(), number + " вид");
    assertEquals(move.getReference(), invoice.getTransactionId(), number + " S.I.18 = GL.9");
    // Основата и данъкът по код — срещу колоните на кода.
    Map<String, BigDecimal[]> byCode = new LinkedHashMap<>();
    for (SaftInvoiceLine line : invoice.getLines()) {
      BigDecimal[] sums =
          byCode.computeIfAbsent(
              tax(line).getTaxCode(), k -> new BigDecimal[] {BigDecimal.ZERO, BigDecimal.ZERO});
      sums[0] = sums[0].add(signed(line, line.getInvoiceLineAmount().getAmount()));
      sums[1] = sums[1].add(signed(line, tax(line).getTaxAmount().getAmount()));
    }
    Map<Enum<?>, BigDecimal> used = new HashMap<>();
    for (Map.Entry<String, BigDecimal[]> entry : byCode.entrySet()) {
      if (CREDIT_USED.contains(entry.getKey())) {
        // Основата = основата на протокола; данъкът = ползваният кредит (к.11 + к.13).
        BigDecimal credit =
            columns
                .getOrDefault(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, BigDecimal.ZERO)
                .add(
                    columns.getOrDefault(
                        BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT, BigDecimal.ZERO));
        assertNotNull(protocolBase, number + ": " + entry.getKey() + " без продажбена страна");
        assertEquals(0, protocolBase.compareTo(entry.getValue()[0]), number + " основа");
        assertEquals(0, credit.compareTo(entry.getValue()[1]), number + " ползван кредит");
        for (BgVatPurchaseColumn column :
            new BgVatPurchaseColumn[] {
              BgVatPurchaseColumn.K09_BASE_NO_CREDIT,
              BgVatPurchaseColumn.K10_BASE_FULL_CREDIT,
              BgVatPurchaseColumn.K11_VAT_FULL_CREDIT,
              BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT,
              BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT
            }) {
          used.put(column, columns.getOrDefault(column, BigDecimal.ZERO));
        }
        continue;
      }
      List<Enum<?>> cols = COLUMNS.get(entry.getKey());
      assertNotNull(cols, number + ": код " + entry.getKey() + " без колона в теста");
      BigDecimal base = entry.getValue()[0];
      BigDecimal tax = entry.getValue()[1];
      if (cols.size() == 1) {
        // к.9 сгъва основа + данък (ППЗДДС Прил. № 11); к.15 на покупките е само основа.
        used.merge(cols.get(0), base.add(tax), BigDecimal::add);
      } else {
        used.merge(cols.get(0), base, BigDecimal::add);
        used.merge(cols.get(1), tax, BigDecimal::add);
      }
    }
    for (Enum<?> column : columns.keySet()) {
      used.putIfAbsent(column, BigDecimal.ZERO);
    }
    for (Map.Entry<Enum<?>, BigDecimal> entry : used.entrySet()) {
      BigDecimal diary = columns.getOrDefault(entry.getKey(), BigDecimal.ZERO);
      assertEquals(
          0,
          diary.compareTo(entry.getValue()),
          number + " " + entry.getKey() + ": дневник " + diary + ", SAF-T " + entry.getValue());
    }
  }

  // ─────────────── видовете протоколи ───────────────

  @Test
  @DisplayName("ВОП 100/20 → Sales 100212 + Purchase 100331; SupplierInfo и в двете; 4532/4531")
  void вопът() {
    Move vop = selfCharge(101, "VOP", "20", "20");
    BgSaftInvoicesAssembler.Result result = saft(vop);
    assertEquals(List.of(), errors(result));
    parity(vop, result);

    SaftInvoice sales = only(result.getSales(), "0000000101");
    assertEquals(List.of("100212"), codes(sales));
    assertEquals(SaftInvoice.PartyRole.SUPPLIER, sales.getPartyRole(), "QA с. 58");
    assertEquals("11DE123456789", sales.getPartyId());
    assertEquals("4532", sales.getAccountId());
    assertEquals("4531", sales.getLines().get(0).getAccountId());
    assertEquals("0", sales.getLines().get(0).getProductCode());
    assertEquals(
        new BigDecimal("100.00"), sales.getLines().get(0).getInvoiceLineAmount().getAmount());
    assertEquals(new BigDecimal("20.00"), tax(sales.getLines().get(0)).getTaxAmount().getAmount());
    assertEquals(new BigDecimal("20"), tax(sales.getLines().get(0)).getTaxPercentage());

    SaftInvoice purchase = only(result.getPurchases(), "0000000101");
    assertEquals(List.of("100331"), codes(purchase));
    assertNull(purchase.getPartyRole());
    assertEquals("11DE123456789", purchase.getPartyId());
    assertEquals("4531", purchase.getAccountId());
    assertEquals("4532", purchase.getLines().get(0).getAccountId());
    assertTrue(finding(result, "SAFT_PROT_PRODUCTS").getMessage().contains("0000000101"));
    assertFalse(finding(result, "SAFT_PROT_PRODUCTS").isBlocking());
  }

  @Test
  @DisplayName("ВОП 9 % → 100220 — ставката е на редовете, а к.15/к.13 само я контролира")
  void вопът_9() {
    Move vop = selfCharge(102, "VOP", "9", "9");
    BgSaftInvoicesAssembler.Result result = saft(vop);
    assertEquals(List.of(), errors(result));
    parity(vop, result);
    assertEquals(List.of("100220"), codes(only(result.getSales(), "0000000102")));
    assertEquals(List.of("100331"), codes(only(result.getPurchases(), "0000000102")));
  }

  @Test
  @DisplayName("ВОП: ставка, която не се определя еднозначно, или к.15 извън ставката — ГРЕШКА")
  void вопът_без_ставка() {
    // Ръчна 0.09 срещу 20 % на редовете.
    Move ambiguous = selfCharge(103, "VOP", "20", "20");
    ambiguous.setBgVatMoveVatRate(new BigDecimal("0.09"));
    // Ръчна ставка до смесени ставки по редовете: нито дневникът, нито SAF-T знае коя ставка е
    // вярната — ГРЕШКА и в двата, с една и съща причина.
    Move mixed = mixedVop(104);
    mixed.setBgVatMoveVatRate(new BigDecimal("0.20"));
    BgSaftInvoicesAssembler.Result result = saft(ambiguous, mixed);
    BgDeclarationFileFinding code = finding(result, "SAFT_PROT_CODE");
    assertTrue(code.isBlocking());
    assertTrue(code.getMessage().contains("0000000103 — ВОП (100212 срещу 100220): ставката не е"));
    assertTrue(
        code.getMessage()
            .contains(
                "0000000104 — ВОП (100212 срещу 100220): ставката не е еднозначна — ръчна 0.2 до"
                    + " няколко ставки"));
    // Дневникът — същата ГРЕШКА на документа, видима и тук: и при една ставка на редовете, и при
    // смесени.
    assertEquals(
        List.of(BgVatValidator.PROTOCOL_MANUAL_RATE_CONFLICT_CODE),
        ROWS.rows(ambiguous).get(0).getDocument().getErrors().stream()
            .map(e -> e.getCode())
            .collect(java.util.stream.Collectors.toList()));
    assertEquals(
        List.of(BgVatValidator.PROTOCOL_MANUAL_RATE_MIXED_CODE),
        ROWS.rows(mixed).get(0).getDocument().getErrors().stream()
            .map(e -> e.getCode())
            .collect(java.util.stream.Collectors.toList()));
    BgDeclarationFileFinding diary = finding(result, "SAFT_PROT_DIARY");
    assertTrue(diary.isBlocking());
    assertTrue(
        diary
            .getMessage()
            .contains(
                "0000000104 — VAT_PROTOCOL_MANUAL_RATE_MIXED: ДДС протокол с ръчна ставка 0.2"),
        diary.getMessage());
    assertEquals(0L, result.getSales().getNumberOfEntries());
    assertEquals(0L, result.getPurchases().getNumberOfEntries());
  }

  // ─────────────── протоколът от фактура = ръчният протокол ───────────────

  /**
   * Протокол, съставен от покупна фактура ({@link BgVatInvoiceProtocolService}), и неговият ръчен
   * еквивалент (същата статия без изрична основа, основание и връзка) дават ЕДНИ И СЪЩИ SAF-T
   * документи — SalesInvoices и PurchaseInvoices, поле по поле.
   */
  private static BgSaftInvoicesAssembler.Result[] fromInvoiceAndByHand(InvoiceLine line)
      throws Exception {
    return fromInvoiceAndByHand(line, APRIL_20);
  }

  private static Move protocolOf(InvoiceLine line, LocalDate invoiceDate) throws Exception {
    Partner supplier =
        BgVatInvoiceProtocolFixtures.supplier(BgSaftInvoiceFixtures.BETA, "DE123456789", "DE");
    Invoice invoice =
        BgVatInvoiceProtocolFixtures.invoice(
            1,
            supplier,
            invoiceDate,
            BgSaftInvoiceFixtures.APRIL_12,
            BgSaftInvoiceFixtures.APRIL_10,
            line);
    AccountConfig config = invoice.getCompany().getAccountConfig();
    config.setBgVatDeductibleAccount(account(BgSaftInvoiceFixtures.ACCOUNT_VAT_PURCHASES, "4531"));
    config.setBgVatChargedAccount(account(BgSaftInvoiceFixtures.ACCOUNT_VAT_SALES, "4532"));
    BgVatInvoiceProtocolService.Result generated =
        new BgVatInvoiceProtocolFixtures.InMemoryService().generate(invoice, null);
    assertEquals(List.of(), generated.getErrors());
    Move protocol = generated.getCreated().get(0);
    Period period = new Period();
    period.setFromDate(BgSaftInvoiceFixtures.PERIOD);
    protocol.setPeriod(period);
    return protocol;
  }

  private static BgSaftInvoicesAssembler.Result[] fromInvoiceAndByHand(
      InvoiceLine line, LocalDate invoiceDate) throws Exception {
    Partner supplier =
        BgVatInvoiceProtocolFixtures.supplier(BgSaftInvoiceFixtures.BETA, "DE123456789", "DE");
    Invoice invoice =
        BgVatInvoiceProtocolFixtures.invoice(
            1,
            supplier,
            invoiceDate,
            BgSaftInvoiceFixtures.APRIL_12,
            BgSaftInvoiceFixtures.APRIL_10,
            line);
    AccountConfig config = invoice.getCompany().getAccountConfig();
    config.setBgVatDeductibleAccount(account(BgSaftInvoiceFixtures.ACCOUNT_VAT_PURCHASES, "4531"));
    config.setBgVatChargedAccount(account(BgSaftInvoiceFixtures.ACCOUNT_VAT_SALES, "4532"));
    BgVatInvoiceProtocolService.Result generated =
        new BgVatInvoiceProtocolFixtures.InMemoryService().generate(invoice, null);
    assertEquals(List.of(), generated.getErrors());
    Move protocol = generated.getCreated().get(0);
    Period period = new Period();
    period.setFromDate(BgSaftInvoiceFixtures.PERIOD);
    protocol.setPeriod(period);
    Move manual = BgVatInvoiceProtocolFixtures.manualEquivalent(protocol);

    BgSaftInvoicesAssembler.Result fromInvoice = saft(protocol);
    BgSaftInvoicesAssembler.Result byHand = saft(manual);
    assertEquals(List.of(), errors(fromInvoice));
    parity(protocol, fromInvoice);
    assertEquals(describe(byHand.getSales()), describe(fromInvoice.getSales()), "SalesInvoices");
    assertEquals(
        describe(byHand.getPurchases()), describe(fromInvoice.getPurchases()), "PurchaseInvoices");
    return new BgSaftInvoicesAssembler.Result[] {fromInvoice, byHand};
  }

  @Test
  @DisplayName(
      "Протокол от фактура = ръчен протокол в SAF-T: ВОП → 100212, чл. 82 → 100226, 163а „01“ → 100227")
  void протоколът_от_фактура() throws Exception {
    BgSaftInvoicesAssembler.Result vop =
        fromInvoiceAndByHand(
            BgVatInvoiceProtocolFixtures.reverseCharge(
                "BG_VOP_ACQ", "BG_VOP_SELF", "20", "1000", "1", false))[0];
    SaftInvoice sale = only(vop.getSales(), "0000000001");
    assertEquals(List.of("100212"), codes(sale));
    assertEquals(
        0,
        new BigDecimal("1000")
            .compareTo(sale.getLines().get(0).getInvoiceLineAmount().getAmount()));
    assertEquals(
        0, new BigDecimal("200").compareTo(tax(sale.getLines().get(0)).getTaxAmount().getAmount()));

    BgSaftInvoicesAssembler.Result service =
        fromInvoiceAndByHand(
            BgVatInvoiceProtocolFixtures.reverseCharge(
                "BG_EU_S_RCM_P", "BG_RCM_ART82_S", "20", "1000", "1", true))[0];
    assertEquals(List.of("100226"), codes(only(service.getSales(), "0000000001")));

    // 163а (отговорът на НАП по имейл от 19.10.2026): протоколът от фактура = ръчният, и в двете
    // секции — Sales 100227/100228 по к.8а, Purchase 100346/100345 с ползвания кредит.
    BgSaftInvoicesAssembler.Result scrap =
        fromInvoiceAndByHand(
            BgVatInvoiceProtocolFixtures.reverseCharge(
                "BG_RCM_SCRAP_P", "BG_RCM_SCRAP_S", "20", "500", "1", false))[0];
    assertEquals(List.of("100227"), codes(only(scrap.getSales(), "0000000001")));
    SaftInvoice scrapPurchase = only(scrap.getPurchases(), "0000000001");
    assertEquals(List.of("100346"), codes(scrapPurchase));
    assertEquals(
        0,
        new BigDecimal("100")
            .compareTo(tax(scrapPurchase.getLines().get(0)).getTaxAmount().getAmount()),
        "пълен кредит — целият данък");
    BgSaftInvoicesAssembler.Result grain =
        fromInvoiceAndByHand(
            BgVatInvoiceProtocolFixtures.reverseCharge(
                "BG_RCM_GRAIN_P", "BG_RCM_GRAIN_S", "20", "500", "1", false))[0];
    assertEquals(List.of("100228"), codes(only(grain.getSales(), "0000000001")));
    assertEquals(List.of("100345"), codes(only(grain.getPurchases(), "0000000001")));
  }

  @Test
  @DisplayName(
      "Закъснял протокол от фактура = ръчен със същата дата на издаване (ППЗДДС чл. 81 ал. 5)")
  void закъснелият_протокол_от_фактура() throws Exception {
    // Изискуемост 12.04 (фактурата на доставчика), срок 27.04, фактурата е въведена 05.05.
    BgSaftInvoicesAssembler.Result late =
        fromInvoiceAndByHand(
            BgVatInvoiceProtocolFixtures.reverseCharge(
                "BG_VOP_ACQ", "BG_VOP_SELF", "20", "1000", "1", false),
            LocalDate.of(2026, 5, 5))[0];
    SaftInvoice sale = only(late.getSales(), "0000000001");
    assertEquals(LocalDate.of(2026, 5, 5), sale.getInvoiceDate(), "датата на издаване");
    assertEquals(List.of("100212"), codes(sale));
  }

  @Test
  @DisplayName(
      "Ръчен протокол без изрична основа и без базови редове — ПРЕДУПРЕЖДЕНИЕ с изведената основа")
  void изведената_основа() {
    Move manual =
        move(
            150,
            "VOP",
            partner(BgSaftInvoiceFixtures.BETA, "DE123456789"),
            vatIn("185.19"),
            vatOut("185.19"));
    manual.getMoveLineList().get(0).setBgVatCreditType("1");
    manual.setBgVatMoveVatRate(new BigDecimal("0.20"));
    BgSaftInvoicesAssembler.Result result = saft(manual);
    assertEquals(List.of(), errors(result));
    BgDeclarationFileFinding finding = finding(result, "SAFT_PROT_BASE_DERIVED");
    assertFalse(finding.isBlocking());
    assertTrue(finding.getMessage().contains("0000000150 — 925.95"), finding.getMessage());

    manual.setBgVatProtocolBase(new BigDecimal("925.93"));
    assertTrue(
        saft(manual).getFindings().stream()
            .noneMatch(f -> f.getCode().equals("SAFT_PROT_BASE_DERIVED")));
  }

  /** Всички полета на SAF-T секцията, рекурсивно — за сравнение поле по поле. */
  private static String describe(Object value) {
    StringBuilder out = new StringBuilder();
    describe(value, out);
    return out.toString();
  }

  private static void describe(Object value, StringBuilder out) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof BigDecimal) {
      out.append(((BigDecimal) value).stripTrailingZeros().toPlainString());
    } else if (value instanceof java.util.Collection) {
      out.append('[');
      for (Object item : (java.util.Collection<?>) value) {
        describe(item, out);
        out.append(',');
      }
      out.append(']');
    } else if (value.getClass().getName().startsWith("com.axelor.apps.bglocalization")
        && !value.getClass().isEnum()) {
      out.append(value.getClass().getSimpleName()).append('{');
      for (java.lang.reflect.Field field : value.getClass().getDeclaredFields()) {
        if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
          continue;
        }
        field.setAccessible(true);
        out.append(field.getName()).append('=');
        try {
          describe(field.get(value), out);
        } catch (IllegalAccessException e) {
          throw new IllegalStateException(e);
        }
        out.append(';');
      }
      out.append('}');
    } else {
      out.append(value);
    }
  }

  /** ВОП 1000 × 20 % + 500 × 9 %: Дт 304 / Дт 302 / Кт 401, Дт 4531 / Кт 4532 по 245. */
  private static Move mixedVop(long id) {
    return move(
        id,
        "VOP",
        partner(BgSaftInvoiceFixtures.BETA, "DE123456789"),
        base(BgSaftInvoiceFixtures.ACCOUNT_GOODS, "304", "1000", "20", "1"),
        base(BgSaftInvoiceFixtures.ACCOUNT_MATERIALS, "302", "500", "9", "1"),
        line(BgSaftInvoiceFixtures.ACCOUNT_SUPPLIERS, "401", "0", "1500"),
        vatIn("245"),
        vatOut("245"));
  }

  @Test
  @DisplayName("ВОП 1000 × 20 % + 500 × 9 % → Sales 100212 (1000/200) + 100220 (500/45)")
  void вопът_смесени_ставки() {
    Move vop = mixedVop(105);
    BgSaftInvoicesAssembler.Result result = saft(vop);
    assertEquals(List.of(), errors(result));
    parity(vop, result);

    // Дневникът: к.13 = 1500 — сборът по ставките, не 245 / 0.20 = 1225.
    Map<BgVatSalesColumn, BigDecimal> diary = ROWS.rows(vop).get(0).getSalesColumns();
    assertEquals(0, new BigDecimal("1500").compareTo(diary.get(BgVatSalesColumn.K13_BASE_ICA)));
    assertEquals(0, new BigDecimal("245").compareTo(diary.get(BgVatSalesColumn.K15_VAT_ICA_RC)));

    SaftInvoice sales = only(result.getSales(), "0000000105");
    assertEquals(List.of("100212", "100220"), codes(sales));
    SaftInvoiceLine at20 = sales.getLines().get(0);
    assertEquals(new BigDecimal("1000.00"), at20.getInvoiceLineAmount().getAmount());
    assertEquals(new BigDecimal("200.00"), tax(at20).getTaxAmount().getAmount());
    assertEquals(new BigDecimal("20"), tax(at20).getTaxPercentage());
    SaftInvoiceLine at9 = sales.getLines().get(1);
    assertEquals(new BigDecimal("500.00"), at9.getInvoiceLineAmount().getAmount());
    assertEquals(new BigDecimal("45.00"), tax(at9).getTaxAmount().getAmount());
    assertEquals(new BigDecimal("9"), tax(at9).getTaxPercentage());

    assertEquals(List.of("100331"), codes(only(result.getPurchases(), "0000000105")));
  }

  @Test
  @DisplayName("Смесени ставки с изрядни клетки → ред по ставка, сборовете са клетките на дневника")
  void смесените_ставки() {
    BgSaftDiaryCells.Rates rates =
        BgSaftDiaryCells.rates(
            List.of(
                new BgSaftDiaryCells.RateLine(new BigDecimal("20"), new BigDecimal("100"), false),
                new BgSaftDiaryCells.RateLine(new BigDecimal("9"), new BigDecimal("100"), false),
                new BgSaftDiaryCells.RateLine(BigDecimal.ZERO, new BigDecimal("29"), true)),
            BigDecimal.ZERO);
    Map<BgVatSalesColumn, BigDecimal> columns = new HashMap<>();
    columns.put(BgVatSalesColumn.K13_BASE_ICA, new BigDecimal("200.00"));
    columns.put(BgVatSalesColumn.K15_VAT_ICA_RC, new BigDecimal("29.00"));
    BgSaftDiaryCells.Coding coding = BgSaftDiaryCells.sales(columns, null, "VOP", rates);
    assertEquals(List.of(), coding.getErrors());
    assertEquals(2, coding.getCells().size());
    assertEquals("02-12", coding.getCells().get(0).getKey());
    assertEquals(new BigDecimal("100.00"), coding.getCells().get(0).getBase());
    assertEquals(new BigDecimal("20.00"), coding.getCells().get(0).getTax());
    assertEquals("02-20", coding.getCells().get(1).getKey());
    assertEquals(new BigDecimal("100.00"), coding.getCells().get(1).getBase());
    assertEquals(new BigDecimal("9.00"), coding.getCells().get(1).getTax());
  }

  @Test
  @DisplayName("Чл. 82 (услуга от ЕС и от трета страна) → Sales 100226 + Purchase по кредита")
  void чл_82() {
    Move eu = selfCharge(111, "SERVICE_EU", "20", "20");
    Move noneu = selfCharge(112, "SERVICE_NONEU", "20", "20");
    noneu.getMoveLineList().get(0).setBgVatCreditType("2");
    BgSaftInvoicesAssembler.Result result = saft(eu, noneu);
    assertEquals(List.of(), errors(result));
    parity(eu, result);
    parity(noneu, result);
    assertEquals(List.of("100226"), codes(only(result.getSales(), "0000000111")));
    assertEquals(List.of("100331"), codes(only(result.getPurchases(), "0000000111")));
    assertEquals(List.of("100332"), codes(only(result.getPurchases(), "0000000112")));
  }

  /** Протокол по чл. 163а с к.8а и вид кредит на базовия ред. */
  private static Move art163a(long id, String part, String creditType) {
    Move m = selfCharge(id, "ART163A", "20", "20");
    m.setBgVatSupplyType(part);
    m.getMoveLineList().get(0).setBgVatCreditType(creditType);
    return m;
  }

  private static SaftInvoice art163aPurchase(Move m, String salesCode, String purchaseCode) {
    BgSaftInvoicesAssembler.Result result = saft(m);
    assertEquals(List.of(), errors(result));
    parity(m, result);
    String number = m.getBgVatProtocolNumber();
    assertEquals(List.of(salesCode), codes(only(result.getSales(), number)));
    SaftInvoice purchase = only(result.getPurchases(), number);
    assertEquals(List.of(purchaseCode), codes(purchase));
    assertEquals(
        new BigDecimal("100.00"), purchase.getLines().get(0).getInvoiceLineAmount().getAmount());
    return purchase;
  }

  @Test
  @DisplayName("Чл. 163а, част I, пълен кредит → Sales 100227 + Purchase 100346, TaxAmount = 20")
  void чл_163а_част_I_пълен_кредит() {
    SaftInvoice purchase = art163aPurchase(art163a(121, "01", "1"), "100227", "100346");
    assertEquals(
        new BigDecimal("20.00"), tax(purchase.getLines().get(0)).getTaxAmount().getAmount());
  }

  @Test
  @DisplayName("Чл. 163а, част II, пълен кредит → Sales 100228 + Purchase 100345, TaxAmount = 20")
  void чл_163а_част_II_пълен_кредит() {
    SaftInvoice purchase = art163aPurchase(art163a(122, "02", "1"), "100228", "100345");
    assertEquals(
        new BigDecimal("20.00"), tax(purchase.getLines().get(0)).getTaxAmount().getAmount());
  }

  @Test
  @DisplayName("Чл. 163а, частичен кредит → 100346, TaxAmount = к.13 (ползваният кредит)")
  void чл_163а_частичен_кредит() {
    Move m = art163a(125, "01", "2");
    SaftInvoice purchase = art163aPurchase(m, "100227", "100346");
    BigDecimal k13 =
        ROWS.rows(m).stream()
            .filter(BgVatMoveDiaryRows.Row::isPurchases)
            .map(r -> r.getPurchaseColumns().get(BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT))
            .findFirst()
            .orElseThrow();
    assertEquals(0, k13.compareTo(tax(purchase.getLines().get(0)).getTaxAmount().getAmount()));
  }

  @Test
  @DisplayName("Чл. 163а без право на кредит → 100346, TaxAmount = 0 (не некредитираният данък)")
  void чл_163а_без_кредит() {
    SaftInvoice purchase = art163aPurchase(art163a(126, "01", "3"), "100227", "100346");
    assertEquals(0, tax(purchase.getLines().get(0)).getTaxAmount().getAmount().signum());
  }

  /** Получен протокол по чл. 151в ал. 3 (вид 91) като ръчна покупка с пълен кредит. */
  private static Move cashProtocol(long id) {
    Move m =
        move(
            id,
            "PURCHASE_FULL",
            partner(BgSaftInvoiceFixtures.BETA, "DE123456789"),
            base(BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "100", "20", "1"),
            line(BgSaftInvoiceFixtures.ACCOUNT_SUPPLIERS, "401", "0", "120"),
            vatIn("20"));
    m.setNapDocumentTypeSelect("91");
    m.setOrigin("K91-" + id);
    m.setOriginDate(APRIL_20);
    return m;
  }

  @Test
  @DisplayName(
      "Получен протокол по чл. 151в (вид 91) като статия: извън режима → Purchase 91/100331; по"
          + " режима → ГРЕШКА „да се потвърди“")
  void касова_протокол_91_статия() {
    Move m = cashProtocol(151);
    BgSaftInvoicesAssembler.Result result = saft(m);
    assertEquals(List.of(), errors(result));
    parity(m, result);
    assertEquals(0L, result.getSales().getNumberOfEntries());
    assertEquals(1L, result.getPurchases().getNumberOfEntries());
    SaftInvoice purchase = result.getPurchases().getInvoices().iterator().next();
    assertEquals("91", purchase.getInvoiceType());
    assertEquals(List.of("100331"), codes(purchase));

    BgSaftInvoicesAssembler.Result inRegime =
        saft(
            BgSaftInvoiceFixtures.seedCellCodes(),
            BgSaftCashAccounting.of(java.time.LocalDate.of(2026, 1, 1), null),
            cashProtocol(152));
    BgDeclarationFileFinding pending = finding(inRegime, "SAFT_PROT_NAP_PENDING");
    assertTrue(
        pending.getMessage().contains(BgSaftNapPending.OWN_CASH_ACCOUNTING), pending.getMessage());
    assertEquals(0L, inRegime.getPurchases().getNumberOfEntries());
  }

  @Test
  @DisplayName("Чл. 163а без к.8а и общият протокол по чл. 163б ал. 1 т. 2 — ГРЕШКА")
  void чл_163а_грешките() {
    Move noSupplyType = selfCharge(123, "ART163A", "20", "20");
    Move general = selfCharge(124, "ART163A", "20", "20");
    general.setBgVatSupplyType("01");
    general.getMoveLineList().get(0).setPartner(partner(BgSaftInvoiceFixtures.IVANOV, null));
    general.getMoveLineList().get(1).setPartner(partner(BgSaftInvoiceFixtures.ALFA, null));
    BgSaftInvoicesAssembler.Result result = saft(noSupplyType, general);
    assertTrue(
        finding(result, "SAFT_PROT_CODE")
            .getMessage()
            .contains("0000000123 — протокол по чл. 163а без к.8а"));
    assertTrue(finding(result, "SAFT_PROT_GENERAL").getMessage().contains("0000000124"));
    assertTrue(finding(result, "SAFT_PROT_GENERAL").isBlocking());
    assertEquals(0L, result.getSales().getNumberOfEntries());
  }

  @Test
  @DisplayName("Внос по чл. 167а → ТРИ документа: Sales 100229, Purchase 100347, ЕАД 100330")
  void вносът_167а() {
    Move imp = import167a(131, "20", "20");
    BgSaftInvoicesAssembler.Result result = saft(imp);
    assertEquals(List.of(), errors(result));
    parity(imp, result);

    SaftInvoice protocol = only(result.getSales(), "0000000131");
    assertEquals(List.of("100229"), codes(protocol));
    assertEquals("4532", protocol.getAccountId());
    SaftInvoice purchase = only(result.getPurchases(), "0000000131");
    assertEquals(List.of("100347"), codes(purchase));
    assertEquals(
        new BigDecimal("20.00"), tax(purchase.getLines().get(0)).getTaxAmount().getAmount());
    // ЕАД: S.I.4 е разчетът с митницата, S.I.30 — ДДС по покупки (Прил. 3 т. IV.7).
    SaftInvoice ead = only(result.getPurchases(), "26BG001000000123R4");
    assertEquals("458", ead.getAccountId());
    assertEquals("4531", ead.getLines().get(0).getAccountId());
    assertEquals(BgSaftInvoiceFixtures.APRIL_12, ead.getInvoiceDate());
    assertEquals(1L, result.getSales().getNumberOfEntries());
    assertEquals(2L, result.getPurchases().getNumberOfEntries());
    // Доставчикът е по типологията 10–16 (не 999… от дневника); в Suppliers влиза по референция
    // от протокола (решение 2 от 14.10.2026), не с находка „не е в master“.
    assertFalse(ead.getPartyId().startsWith("999"));
    assertTrue(
        result.getReferences().stream()
            .anyMatch(
                r ->
                    Long.valueOf(CHINA).equals(r.partnerId())
                        && r.side() == BgSaftPartiesAssembler.Side.SUPPLIER));
  }

  @Test
  @DisplayName(
      "Внос по чл. 167а с частичен кредит и без кредит → 100347, TaxAmount = ползваният; без"
          + " ГРЕШКА за ставката")
  void вносът_167а_кредитът() {
    for (String[] c : new String[][] {{"2", "partial"}, {"3", "none"}}) {
      Move imp = import167a(134, "20", "20");
      imp.getMoveLineList().get(0).setBgVatCreditType(c[0]);
      BgSaftInvoicesAssembler.Result result = saft(imp);
      assertEquals(List.of(), errors(result), c[1]);
      parity(imp, result);
      SaftInvoice purchase = only(result.getPurchases(), "0000000134");
      assertEquals(List.of("100347"), codes(purchase), c[1]);
      // parity() сверява TaxAmount с к.11 + к.13 на дневника; без кредит той е 0.
      if ("3".equals(c[0])) {
        assertEquals(0, tax(purchase.getLines().get(0)).getTaxAmount().getAmount().signum());
      }
    }
  }

  @Test
  @DisplayName(
      "ЕАД без сметка за разчет с митница — S.I.4/S.I.30 по аналогия с протокола + ПРЕДУПРЕЖДЕНИЕ")
  void ЕАД_без_митница() {
    // Обичайната статия на протокола по чл. 167а: само Дт 4531 / Кт 4532, ставката е ръчна.
    MoveLine credit = vatIn("20");
    credit.setBgVatCreditType("1");
    Move imp = move(133, "IMPORT_167A", partner(CHINA, null), credit, vatOut("20"));
    imp.setBgVatMoveVatRate(new BigDecimal("0.20"));
    imp.setOrigin("26BG001000000456R7");
    imp.setOriginDate(BgSaftInvoiceFixtures.APRIL_12);
    BgSaftInvoicesAssembler.Result result = saft(imp);
    // Прил. 3 т. IV.7: сметката за разчет с митница при ЕАД е „допуска се“, не задължение.
    assertEquals(List.of(), errors(result));
    parity(imp, result);
    SaftInvoice ead = only(result.getPurchases(), "26BG001000000456R7");
    assertEquals("4531", ead.getAccountId());
    assertEquals("4532", ead.getLines().get(0).getAccountId());
    BgDeclarationFileFinding warning = finding(result, "SAFT_PROT_EAD_NO_CUSTOMS_ACCOUNT");
    assertFalse(warning.isBlocking());
    assertTrue(warning.getMessage().contains("по аналогия с протокола, да се потвърди"));
    assertTrue(warning.getMessage().contains("26BG001000000456R7 (ЕАД)"));
    // Със сметка за митница предупреждение няма (виж вносът_167а).
    assertTrue(
        saft(import167a(131, "20", "20")).getFindings().stream()
            .noneMatch(f -> f.getCode().equals("SAFT_PROT_EAD_NO_CUSTOMS_ACCOUNT")));
  }

  @Test
  @DisplayName("Внос по чл. 167а при 9 % — ГРЕШКА: 100229 е за 20 % (колони 11/12)")
  void вносът_9() {
    BgSaftInvoicesAssembler.Result result = saft(import167a(132, "9", "9"));
    assertTrue(
        finding(result, "SAFT_PROT_CODE")
            .getMessage()
            .contains("0000000132 — внос по чл. 167а със ставка 0.09"));
  }

  @Test
  @DisplayName("Чл. 79 ал. 4 → Sales 100211, основа 0 (QA с. 80); подаващият; не е в покупките")
  void чл_79_корекция() {
    Move correction =
        move(
            141,
            "CORRECTION_79",
            null,
            line(BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "20", "0"),
            vatOut("20"));
    BgSaftInvoicesAssembler.Result result = saft(correction);
    assertEquals(List.of(), errors(result));
    parity(correction, result);
    SaftInvoice sales = only(result.getSales(), "0000000141");
    assertEquals(List.of("100211"), codes(sales));
    assertEquals(BgSaftFixtures.OWN_ID, sales.getPartyId(), "Прил. 3 т. IV.7 — подаващият");
    assertNull(sales.getPartyRole(), "CustomerInfo");
    assertEquals("4532", sales.getAccountId());
    assertEquals("60201", sales.getLines().get(0).getAccountId());
    SaftInvoiceLine line = sales.getLines().get(0);
    assertEquals(new BigDecimal("0.00"), line.getInvoiceLineAmount().getAmount());
    assertEquals(new BigDecimal("20.00"), tax(line).getTaxAmount().getAmount());
    assertEquals("D", line.getDebitCreditIndicator());
    // Totals БЕЗ TaxAmount (QA IV.2.5): основата е 0, тоест и TotalDebit е 0 — не 20.
    assertEquals(new BigDecimal("0.00"), result.getSales().getTotalDebit());
    assertEquals(0L, result.getPurchases().getNumberOfEntries());
    // Подаващият не се търси в Suppliers/Customers.
    assertTrue(
        result.getFindings().stream()
            .noneMatch(f -> f.getCode().equals("SAFT_PROT_NOT_IN_MASTER")));
  }

  @Test
  @DisplayName(
      "Чл. 79 ал. 1–3 при 9 % → 100213, не 100211 с предупреждение (QA с. 80: „в зависимост от"
          + " приложимата ставка“; решение 4 от 14.10.2026)")
  void чл_79_корекция_9() {
    Move correction =
        move(
            146,
            "CORRECTION_79",
            null,
            line(BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "9", "0"),
            vatOut("9"));
    correction.setBgVatMoveVatRate(new BigDecimal("0.09"));
    BgSaftInvoicesAssembler.Result result = saft(correction);
    assertEquals(List.of(), errors(result));
    SaftInvoice sales = only(result.getSales(), "0000000146");
    assertEquals(List.of("100213"), codes(sales));
    assertEquals(new BigDecimal("9.00"), tax(sales.getLines().get(0)).getTaxAmount().getAmount());
    assertTrue(
        result.getFindings().stream().noneMatch(f -> f.getCode().equals("SAFT_PROT_CODE_WARNING")),
        result.getFindings().toString());
  }

  // ─────────────── дневникът взима кода от документа на статията ───────────────

  /** Ред на дневника за статия {@code moveId} — с данъка на реда, без кода по мапинга. */
  private static BgSaftGlEntriesAssembler.LineRow glLine(
      long id, int counter, long moveId, long account, String code, String debit, String tax) {
    BigDecimal amount = new BigDecimal(debit);
    return new BgSaftGlEntriesAssembler.LineRow(id)
        .counter(counter)
        .debit(amount.signum() >= 0 ? amount : BigDecimal.ZERO)
        .credit(amount.signum() < 0 ? amount.negate() : BigDecimal.ZERO)
        .account(account, code)
        .moveId(moveId)
        .reference("MOV-" + moveId)
        .date(APRIL_20)
        .periodFrom(BgSaftInvoiceFixtures.PERIOD)
        .journal(9L, "OD", "Разни")
        .taxCodes(tax == null ? List.of() : List.of(tax));
  }

  private static Map<String, String> glCodes(BgSaftGlEntriesAssembler.Result gl) {
    Map<String, String> codes = new LinkedHashMap<>();
    gl.getGeneralLedger()
        .getJournals()
        .forEach(
            j ->
                j.getTransactions()
                    .forEach(
                        t ->
                            t.getLines()
                                .forEach(
                                    l ->
                                        codes.put(
                                            t.getTransactionId() + " " + l.getTaxpayerAccountId(),
                                            l.getTaxInformation().get(0).getTaxType()
                                                + "/"
                                                + l.getTaxInformation().get(0).getTaxCode()))));
    return codes;
  }

  @Test
  @DisplayName(
      "Данък без код в мапинга (BG_PROTO_117, BG_ADJ_ART79) — дневникът взима кода на протокола от"
          + " същата статия: същият TaxCode като в секцията, нула ГРЕШКИ (решение от 15.10.2026)")
  void дневникът_взима_кода_на_протокола() {
    // Протокол по чл. 117 за услуга от ЕС (100226 в продажбите, 100331 в покупките) и корекция по
    // чл. 79 ал. 1–3 (100211, основа 0).
    Move eu = selfCharge(111, "SERVICE_EU", "20", "20");
    Move correction =
        move(
            141,
            "CORRECTION_79",
            null,
            line(BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "20", "0"),
            vatOut("20"));
    BgSaftInvoicesAssembler.Result invoices = saft(eu, correction);
    assertEquals(List.of(), errors(invoices));
    assertEquals(List.of("100226"), codes(only(invoices.getSales(), "0000000111")));
    assertEquals(List.of("100331"), codes(only(invoices.getPurchases(), "0000000111")));
    assertEquals(List.of("100211"), codes(only(invoices.getSales(), "0000000141")));

    List<BgSaftGlEntriesAssembler.LineRow> lines =
        List.of(
            glLine(1, 1, 111, BgSaftInvoiceFixtures.ACCOUNT_GOODS, "304", "100", null),
            glLine(2, 2, 111, BgSaftInvoiceFixtures.ACCOUNT_SUPPLIERS, "401", "-100", null),
            glLine(
                3,
                3,
                111,
                BgSaftInvoiceFixtures.ACCOUNT_VAT_PURCHASES,
                "4531",
                "20",
                "BG_PROTO_117"),
            glLine(
                4, 4, 111, BgSaftInvoiceFixtures.ACCOUNT_VAT_SALES, "4532", "-20", "BG_PROTO_117"),
            glLine(5, 1, 141, BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "20", "BG_ADJ_ART79"),
            glLine(
                6, 2, 141, BgSaftInvoiceFixtures.ACCOUNT_VAT_SALES, "4532", "-20", "BG_ADJ_ART79"));
    BgSaftGlEntriesAssembler.Result gl =
        BgSaftGlEntriesAssembler.assemble(
            lines,
            BgSaftInvoiceFixtures.chart(),
            BgSaftInvoiceFixtures.mappings(),
            BgSaftFixtures.glPartners(),
            BgSaftInvoiceFixtures.seedTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID,
            Map.of(),
            null,
            invoices.getDocumentCodes());
    assertEquals(
        List.of(),
        gl.getFindings().stream()
            .filter(BgDeclarationFileFinding::isBlocking)
            .map(BgDeclarationFileFinding::toString)
            .collect(Collectors.toList()));
    Map<String, String> codes = glCodes(gl);
    assertEquals("100/100331", codes.get("MOV-111 4531"), codes.toString());
    assertEquals("100/100226", codes.get("MOV-111 4532"), codes.toString());
    assertEquals("100/100211", codes.get("MOV-141 4532"), codes.toString());
    assertEquals("100/100211", codes.get("MOV-141 602"), codes.toString());
  }

  @Test
  @DisplayName(
      "T4: редовете на протокола БЕЗ данък (както ги пише приложението) — 4531/4532 взимат кода на"
          + " протокола по S.I.4, разходът остава 000/000000, нула ГРЕШКИ (решение от 21.10.2026)")
  void протокол_без_данък_на_реда() {
    Move eu = selfCharge(111, "SERVICE_EU", "20", "20");
    Move correction =
        move(
            141,
            "CORRECTION_79",
            null,
            line(BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "20", "0"),
            vatOut("20"));
    BgSaftInvoicesAssembler.Result invoices = saft(eu, correction);
    assertEquals(List.of(), errors(invoices));

    List<BgSaftGlEntriesAssembler.LineRow> lines =
        List.of(
            glLine(1, 1, 111, BgSaftInvoiceFixtures.ACCOUNT_VAT_PURCHASES, "4531", "20", null),
            glLine(2, 2, 111, BgSaftInvoiceFixtures.ACCOUNT_VAT_SALES, "4532", "-20", null),
            glLine(3, 1, 141, BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "20", null),
            glLine(4, 2, 141, BgSaftInvoiceFixtures.ACCOUNT_VAT_SALES, "4532", "-20", null),
            // Статия без документ: същите сметки, без код.
            glLine(5, 1, 999, BgSaftInvoiceFixtures.ACCOUNT_VAT_PURCHASES, "4531", "20", null),
            glLine(6, 2, 999, BgSaftInvoiceFixtures.ACCOUNT_VAT_SALES, "4532", "-20", null));
    BgSaftGlEntriesAssembler.Result gl =
        BgSaftGlEntriesAssembler.assemble(
            lines,
            BgSaftInvoiceFixtures.chart(),
            BgSaftInvoiceFixtures.mappings(),
            BgSaftFixtures.glPartners(),
            BgSaftInvoiceFixtures.seedTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID,
            Map.of(),
            null,
            invoices.getDocumentCodes());
    assertEquals(
        List.of(),
        gl.getFindings().stream()
            .filter(BgDeclarationFileFinding::isBlocking)
            .map(BgDeclarationFileFinding::toString)
            .collect(Collectors.toList()));
    Map<String, String> codes = glCodes(gl);
    assertEquals("100/100331", codes.get("MOV-111 4531"), codes.toString());
    assertEquals("100/100226", codes.get("MOV-111 4532"), codes.toString());
    assertEquals("000/000000", codes.get("MOV-141 602"), codes.toString());
    assertEquals("100/100211", codes.get("MOV-141 4532"), codes.toString());
    assertEquals("000/000000", codes.get("MOV-999 4531"), codes.toString());
    assertEquals("000/000000", codes.get("MOV-999 4532"), codes.toString());
  }

  @Test
  @DisplayName(
      "Данък без код и без документ на статията — ГРЕШКА остава: няма откъде да се изведе кодът")
  void без_документ_грешка_остава() {
    BgSaftGlEntriesAssembler.Result gl =
        BgSaftGlEntriesAssembler.assemble(
            List.of(
                glLine(
                    1,
                    1,
                    999,
                    BgSaftInvoiceFixtures.ACCOUNT_VAT_SALES,
                    "4532",
                    "-20",
                    "BG_PROTO_117"),
                glLine(2, 2, 999, BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "20", null)),
            BgSaftInvoiceFixtures.chart(),
            BgSaftInvoiceFixtures.mappings(),
            BgSaftFixtures.glPartners(),
            BgSaftInvoiceFixtures.seedTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID,
            Map.of(),
            null,
            saft().getDocumentCodes());
    assertTrue(
        gl.getFindings().stream()
            .anyMatch(f -> f.isBlocking() && f.getCode().equals("SAFT_GLE_TAX_TO_CONFIRM")),
        gl.getFindings().toString());
  }

  @Test
  @DisplayName(
      "Протокол с покупен ред без вид данъчен кредит — пълен кредит като в дневника, с"
          + " ПРЕДУПРЕЖДЕНИЕ (досега тихо 100331)")
  void протокол_без_вид_кредит() {
    Move vop =
        move(
            151,
            "VOP",
            partner(BgSaftInvoiceFixtures.BETA, "DE123456789"),
            base(BgSaftInvoiceFixtures.ACCOUNT_GOODS, "304", "100", "20", null),
            line(BgSaftInvoiceFixtures.ACCOUNT_SUPPLIERS, "401", "0", "100"),
            vatIn("20"),
            vatOut("20"));
    BgSaftInvoicesAssembler.Result result = saft(vop);
    assertEquals(List.of(), errors(result));
    assertEquals(List.of("100331"), codes(only(result.getPurchases(), "0000000151")));
    BgDeclarationFileFinding defaulted = finding(result, "SAFT_PROT_CREDIT_DEFAULTED");
    assertFalse(defaulted.isBlocking());
    assertTrue(defaulted.getMessage().contains("0000000151"), defaulted.getMessage());
  }

  @Test
  @DisplayName("Чл. 79 ал. 7 и чл. 79а/79б → Purchase 100331 по к.11, основа 0, знакът от статията")
  void чл_79_покупките() {
    Move deduction =
        move(
            151,
            "DEDUCTION_79",
            null,
            vatIn("15"),
            line(BgSaftInvoiceFixtures.ACCOUNT_INCOME, "702", "0", "15"));
    Move decrease =
        move(
            152,
            "ANNUAL_79A",
            null,
            line(BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "10", "0"),
            line(BgSaftInvoiceFixtures.ACCOUNT_VAT_PURCHASES, "4531", "0", "10"));
    BgSaftInvoicesAssembler.Result result = saft(deduction, decrease);
    assertEquals(List.of(), errors(result));
    parity(deduction, result);
    parity(decrease, result);
    // Извод по паритет: QA не разглежда изрично чл. 79 ал. 5–7 и 79а/79б; дневникът ги пише в к.11
    // (ППЗДДС чл. 66 ал. 9), тоест 100331.
    SaftInvoice plus = only(result.getPurchases(), "0000000151");
    assertEquals(List.of("100331"), codes(plus));
    assertEquals(BgSaftFixtures.OWN_ID, plus.getPartyId());
    assertEquals("4531", plus.getAccountId());
    assertEquals("702", plus.getLines().get(0).getAccountId());
    SaftInvoice minus = only(result.getPurchases(), "0000000152");
    SaftInvoiceLine line = minus.getLines().get(0);
    assertEquals("C", line.getDebitCreditIndicator(), "намалението — C с положителен знак");
    assertEquals(new BigDecimal("10.00"), tax(line).getTaxAmount().getAmount());
    assertEquals(0L, result.getSales().getNumberOfEntries());
  }

  @Test
  @DisplayName(
      "Ръчна покупка (PURCHASE_FULL) — през същата връзка; S.I.4 е разчетът, S.I.30 разходът")
  void ръчната_покупка() {
    Move purchase =
        move(
            161,
            "PURCHASE_FULL",
            partner(BgSaftInvoiceFixtures.IVANOV, null),
            base(BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "100", "20", null),
            vatIn("20"),
            line(BgSaftInvoiceFixtures.ACCOUNT_SUPPLIERS, "401", "0", "120"));
    purchase.setBgVatProtocolNumber(null);
    purchase.setOrigin("F-555");
    BgSaftInvoicesAssembler.Result result = saft(purchase);
    assertEquals(List.of(), errors(result));
    parity(purchase, result);
    SaftInvoice invoice = only(result.getPurchases(), "F-555");
    assertEquals(List.of("100331"), codes(invoice));
    assertEquals("401", invoice.getAccountId());
    assertEquals("60201", invoice.getLines().get(0).getAccountId());
  }

  /** Годишна корекция по чл. 73 ал. 8 (−5): Дт 602 / Кт 4531. */
  private static Move annual(long id) {
    Move annual =
        move(
            id,
            "PURCHASE_ANNUAL",
            null,
            line(BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "5", "0"),
            line(BgSaftInvoiceFixtures.ACCOUNT_VAT_PURCHASES, "4531", "0", "5"));
    annual.setBgVatProtocolNumber(null);
    annual.setOrigin("ГК-2026");
    return annual;
  }

  @Test
  @DisplayName(
      "Годишна корекция по чл. 73 ал. 8 (к.14 = поле 03-43) → 100242, основа 0, подаващият")
  void годишната_корекция() {
    Move annual = annual(171);
    BgSaftInvoicesAssembler.Result result = saft(annual);
    assertEquals(List.of(), errors(result));
    parity(annual, result);
    SaftInvoice invoice = only(result.getPurchases(), "ГК-2026");
    assertEquals(List.of("100242"), codes(invoice));
    assertEquals(BgSaftFixtures.OWN_ID, invoice.getPartyId());
    // Без доставка — сметките са по правилото на протокола: 4531 и насрещната ѝ.
    assertEquals("4531", invoice.getAccountId());
    assertEquals("60201", invoice.getLines().get(0).getAccountId());
    SaftInvoiceLine line = invoice.getLines().get(0);
    assertEquals(new BigDecimal("0.00"), line.getInvoiceLineAmount().getAmount());
    // Намалението (−5) — C с положителна сума (QA с. 78).
    assertEquals("C", line.getDebitCreditIndicator());
    assertEquals(new BigDecimal("5.00"), tax(line).getTaxAmount().getAmount());
    assertEquals(0L, result.getSales().getNumberOfEntries());
  }

  @Test
  @DisplayName(
      "Отрицателни свидетели: клетка без ред в BgSafVatCellCode, MODE 3, протокол без номер —"
          + " ГРЕШКА, файл няма")
  void грешките_на_статиите() {
    // Таблицата без реда на 03-43: клетката е без код — ГРЕШКА, не празен таг.
    Map<String, String> withoutAnnual = new HashMap<>(BgSaftInvoiceFixtures.seedCellCodes());
    assertEquals("100242", withoutAnnual.remove("03-43"));
    Move noOperation =
        move(
            172,
            null,
            partner(BgSaftInvoiceFixtures.IVANOV, null),
            base(BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "100", "20", null),
            vatIn("20"),
            line(BgSaftInvoiceFixtures.ACCOUNT_SUPPLIERS, "401", "0", "120"));
    Move noNumber = selfCharge(173, "VOP", "20", "20");
    noNumber.setBgVatProtocolNumber(null);
    BgSaftInvoicesAssembler.Result result = saft(withoutAnnual, annual(171), noOperation, noNumber);
    assertTrue(
        finding(result, "SAFT_PROT_TAX_UNKNOWN").getMessage().contains("клетка 03-43 я няма"));
    assertTrue(finding(result, "SAFT_PROT_NO_BASE").getMessage().contains("MOV-172"));
    assertTrue(
        finding(result, "SAFT_PROT_DIARY").getMessage().contains("VAT_PROTOCOL_NUMBER_MISSING"));
    for (String code : List.of("SAFT_PROT_TAX_UNKNOWN", "SAFT_PROT_NO_BASE", "SAFT_PROT_DIARY")) {
      assertTrue(finding(result, code).isBlocking(), code);
    }
    assertEquals(0L, result.getSales().getNumberOfEntries());
    assertEquals(0L, result.getPurchases().getNumberOfEntries());
  }

  @Test
  @DisplayName("к.16 (лични нужди) — ГРЕШКА: дневникът няма основата (ППЗДДС чл. 113а)")
  void личните_нужди() {
    Map<BgVatSalesColumn, BigDecimal> columns = new HashMap<>();
    columns.put(BgVatSalesColumn.K16_VAT_SELF_SUPPLY, new BigDecimal("20.00"));
    BgSaftDiaryCells.Coding coding =
        BgSaftDiaryCells.sales(
            columns, null, null, BgSaftDiaryCells.rates(List.of(), BigDecimal.ZERO));
    assertEquals(1, coding.getErrors().size());
    assertTrue(coding.getErrors().get(0).contains("к.16"));
    assertTrue(coding.getCells().isEmpty());
  }

  @Test
  @DisplayName("Статия, която дневникът носи, а GL не — ПРЕДУПРЕЖДЕНИЕ, не тишина")
  void извън_обхвата_на_GL() {
    BgSaftInvoicesAssembler.Source source = new BgSaftInvoicesAssembler.Source();
    source.outOfScope("MOV-9 (VOP)");
    BgSaftInvoicesAssembler.Result result = BgSaftInvoiceFixtures.assemble(source);
    BgDeclarationFileFinding warning = finding(result, "SAFT_PROT_OUT_OF_GL");
    assertFalse(warning.isBlocking());
    assertTrue(warning.getMessage().contains("MOV-9 (VOP)"));
  }

  @Test
  @DisplayName("Всички протоколи заедно минават схемата")
  void схемата_с_протоколите() {
    BgSaftInvoicesAssembler.Result result =
        saft(
            selfCharge(101, "VOP", "20", "20"),
            selfCharge(111, "SERVICE_EU", "20", "20"),
            import167a(131, "20", "20"),
            move(
                141,
                "CORRECTION_79",
                null,
                line(BgSaftInvoiceFixtures.ACCOUNT_EXPENSE, "602", "20", "0"),
                vatOut("20")));
    assertEquals(List.of(), errors(result));
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.getSourceDocuments()
        .salesInvoices(result.getSales())
        .purchaseInvoices(result.getPurchases());
    assertEquals(List.of(), BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)));
  }
}
