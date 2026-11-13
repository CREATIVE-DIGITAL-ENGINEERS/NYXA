package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.Tax;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatAmountDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ТАБЛИЦАТА НА DEKLAR ЗА ПРОТОКОЛИТЕ — от ръчната статия до клетката.
 *
 * <p>Всеки случай минава целия път, по който минава и истинският файл: {@code collect()} на
 * колектора (с подадени статии вместо JPQL), филтрите на {@link BgVatExportService} за двата
 * дневника и {@link BgVatDeclarationAdapter}. Мери се РАЗЛИКАТА в клетките спрямо база без
 * протокола. Очакваната разлика на всяка клетка е до формулата ѝ от ППЗДДС Прил. № 12 (поле 01-XX =
 * сбор от полетата 02-XX / 03-XX на дневниците); клетка, която не е изброена, не бива да мърда.
 *
 * <p><b>Инвариантът:</b> самоначисляване с пълен данъчен кредит е НЕТНА НУЛА — кл.20 и кл.40 растат
 * с един и същ данък, тъй че кл.50 и кл.60 не мърдат. Проверява се върху две бази: една с данък за
 * внасяне (кл.50 &gt; 0) и една за възстановяване (кл.60 &gt; 0).
 *
 * <p>Полетата на дневниците (Прил. № 12): 02-10 = к.9, 02-20 = к.10, 02-11 = к.11, 02-21 = к.12,
 * 02-12 = к.13, 02-26 = к.14, 02-22 = к.15; 03-31 = к.10, 03-41 = к.11, 03-32 = к.12, 03-42 = к.13.
 */
class BgVatDeclarationProtocolTableTest {

  private static final LocalDate DATE = LocalDate.of(2026, 6, 30);

  /** Клетките на DEKLAR.TXT в реда на записа; всяка е 15 знака, кл.33 — 4. */
  private static final List<String> CELLS =
      Arrays.asList(
          "01", "20", "11", "21", "12", "22", "23", "13", "24", "14", "15", "16", "17", "18", "19",
          "30", "31", "41", "32", "42", "43", "33", "40", "50", "60", "70", "71", "80", "81", "82");

  /** Колекторът с подадени статии — истинските isVatRelevant() и toDocument(). */
  private static final class Collector extends BgVatDocumentCollector {
    private final List<Move> moves;

    Collector(List<Move> moves) {
      super(null);
      this.moves = moves;
    }

    @Override
    protected List<Move> findPostedMoves(BgVatExportRequestDto request) {
      return moves;
    }
  }

  private final BgVatSalesAdapter salesAdapter = new BgVatSalesAdapter();

  // ---------------------------------------------------------------------------
  // Статии
  // ---------------------------------------------------------------------------

  private static MoveLine line(String account, String debit, String credit) {
    Account a = new Account();
    a.setCode(account);
    MoveLine l = new MoveLine();
    l.setAccount(a);
    l.setDebit(new BigDecimal(debit));
    l.setCredit(new BigDecimal(credit));
    return l;
  }

  private static Move protocol(String operation, String rate, MoveLine... lines) {
    Partner supplier = new Partner();
    supplier.setFullName("Доставчик");
    supplier.setTaxNbr("DE123456789");
    Move m = new Move();
    m.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    m.setDate(DATE);
    m.setOrigin("П-1");
    m.setPartner(supplier);
    m.setNapDocumentTypeSelect("09");
    m.setBgVatMoveVatOperation(operation);
    if (rate != null) {
      m.setBgVatMoveVatRate(new BigDecimal(rate));
    }
    m.setMoveLineList(new ArrayList<>(Arrays.asList(lines)));
    return m;
  }

  /**
   * Базовият ред, както го оставя осчетоводяването: данъкът е на реда, а {@code taxRate} е в
   * ПРОЦЕНТИ ({@code MoveValidateServiceImpl}: {@code getTotalTaxRateInPercentage}).
   */
  private static MoveLine baseLine(String account, String debit) {
    return baseLine(account, debit, "20");
  }

  private static MoveLine baseLine(String account, String debit, String ratePercent) {
    Tax tax = new Tax();
    tax.setCode("20".equals(ratePercent) ? "BG_SRP_20" : "BG_SRP_" + ratePercent);
    TaxLine taxLine = new TaxLine();
    taxLine.setTax(tax);
    taxLine.setValue(new BigDecimal(ratePercent));
    MoveLine l = line(account, debit, "0");
    l.setTaxLineSet(new HashSet<>(Collections.singleton(taxLine)));
    l.setTaxRate(taxLine.getValue());
    return l;
  }

  /**
   * Самоначисляване 100/20 с основата в статията: Дт 601 (с данък) / Кт 401, Дт 4531 (данъчен
   * кредит) / Кт 4532 (начислен данък).
   */
  private static Move selfCharge(String operation) {
    return protocol(
        operation,
        "0.20",
        baseLine("601", "100"),
        line("401", "0", "100"),
        line("4531", "20", "0"),
        line("4532", "0", "20"));
  }

  // ---------------------------------------------------------------------------
  // Бази
  // ---------------------------------------------------------------------------

  private static BgVatDocumentDto sale(String base, String vat) {
    BgVatAmountDto a = new BgVatAmountDto();
    a.setTaxCode("BG_SRS_20");
    a.setBase(new BigDecimal(base));
    a.setVatAmount(new BigDecimal(vat));
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType("01");
    d.setOperationType(3);
    d.getAmounts().add(a);
    return d;
  }

  private static BgVatDocumentDto purchase(String base, String vat) {
    BgVatDocumentDto d = new BgVatDocumentDto();
    d.setNapDocumentType("01");
    d.setOperationType(1);
    d.addPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT, new BigDecimal(base));
    d.addPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT, new BigDecimal(vat));
    return d;
  }

  /** Една продажба и една покупка с пълен кредит — фонът, спрямо който се мерят разликите. */
  private static final class Base {
    final String name;
    final List<BgVatDocumentDto> sales;
    final List<BgVatDocumentDto> purchases;

    Base(String name, BgVatDocumentDto sale, BgVatDocumentDto purchase) {
      this.name = name;
      this.sales = Arrays.asList(sale);
      this.purchases = Arrays.asList(purchase);
    }
  }

  /** За внасяне: кл.20 = 2000, кл.40 = 100 → кл.50 = 1900. */
  private static Base payable() {
    return new Base("за внасяне", sale("10000", "2000"), purchase("500", "100"));
  }

  /** За възстановяване: кл.20 = 20, кл.40 = 2000 → кл.60 = 1980. */
  private static Base refundable() {
    return new Base("за възстановяване", sale("100", "20"), purchase("10000", "2000"));
  }

  // ---------------------------------------------------------------------------
  // Пътят до DEKLAR
  // ---------------------------------------------------------------------------

  private BgVatExportService exportService(List<Move> moves) {
    return new BgVatExportService(
        new Collector(moves),
        new BgVatValidator(),
        new BgVatPurchaseAdapter(),
        salesAdapter,
        new BgVatDeclarationAdapter(salesAdapter),
        new BgVatViesAdapter(salesAdapter),
        new BgVatFileWriter());
  }

  private static BgVatExportRequestDto request() {
    BgVatExportRequestDto r = new BgVatExportRequestDto();
    r.setCompanyId(1L);
    r.setCompanyVatNumber("BG100000033");
    r.setPeriodFrom(LocalDate.of(2026, 6, 1));
    r.setPeriodTo(DATE);
    return r;
  }

  private Map<String, BigDecimal> deklar(Base base, List<Move> moves) {
    BgVatExportService service = exportService(moves);
    List<BgVatDocumentDto> collected = service.collector.collect(request());
    List<BgVatDocumentDto> sales = new ArrayList<>(base.sales);
    sales.addAll(service.filterSalesDocuments(collected));
    List<BgVatDocumentDto> purchases = new ArrayList<>(base.purchases);
    purchases.addAll(service.filterPurchaseDocuments(collected));
    String record =
        new BgVatDeclarationAdapter(salesAdapter)
            .buildLines(
                sales,
                purchases,
                new BgVatDeclarationAdapter.Header("BG100000033", "Дружество", "202606", "Х"),
                new BgVatDeclarationAdapter.ManualCells(
                    new BigDecimal("0.50"), null, null, null, null, null))
            .get(0);
    Map<String, BigDecimal> cells = new LinkedHashMap<>();
    cells.put("брой продажби", new BigDecimal(record.substring(121, 136).trim()));
    cells.put("брой покупки", new BigDecimal(record.substring(136, 151).trim()));
    int at = 151;
    for (String cell : CELLS) {
      int width = "33".equals(cell) ? 4 : 15;
      cells.put(cell, new BigDecimal(record.substring(at, at + width).trim()));
      at += width;
    }
    assertEquals(590, at, "разположението на клетките");
    return cells;
  }

  /** Разликата на всяка клетка спрямо базата без протокола. */
  private Map<String, BigDecimal> deltas(Base base, Move... moves) {
    Map<String, BigDecimal> without = deklar(base, new ArrayList<>());
    Map<String, BigDecimal> with = deklar(base, Arrays.asList(moves));
    Map<String, BigDecimal> delta = new LinkedHashMap<>();
    for (String cell : without.keySet()) {
      delta.put(cell, with.get(cell).subtract(without.get(cell)));
    }
    return delta;
  }

  /** Очакваните разлики; всяка неизброена клетка е 0. */
  private static final class Expect {
    final Map<String, BigDecimal> cells = new LinkedHashMap<>();

    Expect cell(String cell, String delta) {
      cells.put(cell, new BigDecimal(delta));
      return this;
    }

    Expect merge(Expect other) {
      cells.putAll(other.cells);
      return this;
    }
  }

  private void assertDeltas(String name, Base base, Expect expect, Move... moves) {
    Map<String, BigDecimal> actual = deltas(base, moves);
    for (Map.Entry<String, BigDecimal> e : actual.entrySet()) {
      BigDecimal expected = expect.cells.getOrDefault(e.getKey(), BigDecimal.ZERO);
      assertEquals(
          0,
          expected.compareTo(e.getValue()),
          name
              + " / база "
              + base.name
              + ": кл."
              + e.getKey()
              + " очаквана разлика "
              + expected
              + ", а е "
              + e.getValue()
              + "; всички разлики: "
              + actual);
    }
  }

  /** Самоначисляване с пълен кредит: едни и същи разлики и при двете бази — кл.50/кл.60 са 0. */
  private void assertNetZero(String name, Expect expect, Move... moves) {
    assertEquals(null, expect.cells.get("50"), "инвариантът не допуска очаквания по кл.50");
    assertEquals(null, expect.cells.get("60"), "инвариантът не допуска очаквания по кл.60");
    assertDeltas(name, payable(), expect, moves);
    assertDeltas(name, refundable(), expect, moves);
  }

  /** Самоначисляване по к.13/к.14 + к.15 и пълен кредит по к.10/к.11 — 100/20. */
  private static Expect selfChargeCells() {
    return new Expect()
        .cell("брой продажби", "1")
        .cell("брой покупки", "1")
        // 01-01 = [02-10] = сбор на 02-11, 02-12, 02-13, 02-14, 02-15, 02-16, 02-26
        .cell("01", "100")
        // 01-20 = [02-20] = сбор на 02-21, 02-22, 02-23, 02-24
        .cell("20", "20")
        // 01-12 = [02-12] + [02-26]
        .cell("12", "100")
        // 01-22 = [02-22]
        .cell("22", "20")
        // 01-31 = [03-31]
        .cell("31", "100")
        // 01-41 = [03-41]
        .cell("41", "20")
        // 01-40 = [01-41] + [01-42] × [01-33] + [01-43]
        .cell("40", "20");
    // 01-50 = [01-20] − [01-40] ≥ 0 и 01-60 = [01-20] − [01-40] < 0: +20 − 20 → без промяна.
  }

  // ---------------------------------------------------------------------------
  // Случаите
  // ---------------------------------------------------------------------------

  @Test
  @DisplayName("Ръчен ВОП 100/20 с пълен кредит — нетна нула")
  void ръчен_вОП() {
    assertNetZero("ВОП", selfChargeCells(), selfCharge("VOP"));
  }

  /**
   * ВОП със СМЕСЕНИ ставки: 1000 × 20 % + 500 × 9 %, без ръчна ставка. Основата е по ставката на
   * всеки ред — 1500, а данъкът 200 + 45 = 245. Обърната през първата ставка, тя беше 245 / 0.20 =
   * 1225 в к.13 и в кл.12, а покупната страна носеше същата грешка в к.10 / кл.31.
   */
  private static Move mixedRateVop() {
    return protocol(
        "VOP",
        null,
        baseLine("304", "1000", "20"),
        baseLine("302", "500", "9"),
        line("401", "0", "1500"),
        line("4531", "245", "0"),
        line("4532", "0", "245"));
  }

  @Test
  @DisplayName("Ръчен ВОП 1000 × 20 % + 500 × 9 %: к.13 = 1500, к.15 = 245 — нетна нула")
  void ръчен_вОП_смесени_ставки() {
    Expect cells =
        new Expect()
            .cell("брой продажби", "1")
            .cell("брой покупки", "1")
            .cell("01", "1500") // [02-10]
            .cell("20", "245") // [02-20]
            .cell("12", "1500") // [02-12] + [02-26]
            .cell("22", "245") // [02-22]
            .cell("31", "1500") // [03-31]
            .cell("41", "245") // [03-41]
            .cell("40", "245"); // [01-41] + [01-42] × [01-33] + [01-43]
    assertNetZero("ВОП смесени ставки", cells, mixedRateVop());
  }

  @Test
  @DisplayName("Ръчен ВОП със смесени ставки в PRODAGBI: к.9 = к.13 = 1500, к.10 = к.15 = 245")
  void ръчен_вОП_смесени_ставки_в_дневника_за_продажби() {
    BgVatExportService service = exportService(Arrays.asList(mixedRateVop()));
    List<BgVatDocumentDto> sales =
        service.filterSalesDocuments(service.collector.collect(request()));
    List<String> lines = salesAdapter.buildLines(sales, request());

    assertEquals(1, lines.size());
    String row = lines.get(0);
    assertEquals("1500.00", row.substring(167, 182).trim()); // к.9
    assertEquals("245.00", row.substring(182, 197).trim()); // к.10
    assertEquals("1500.00", row.substring(227, 242).trim()); // к.13
    assertEquals("0.00", row.substring(242, 257).trim()); // к.14
    assertEquals("245.00", row.substring(257, 272).trim()); // к.15
  }

  /**
   * Протоколът САМО с 4531/4532 — без основа в статията. Двете сметки не допускат данък на реда
   * ({@code isTaxAuthorizedOnMoveLine = false}), тоест статията минава към дневниците само по ДДС
   * операцията си.
   */
  @Test
  @DisplayName("Ръчен ВОП само с 4531/4532 (основата се обръща от ставката) — нетна нула")
  void ръчен_вОП_само_с_данъчните_сметки() {
    assertNetZero(
        "ВОП без основа",
        selfChargeCells(),
        protocol("VOP", "0.20", line("4531", "20", "0"), line("4532", "0", "20")));
  }

  /**
   * Получена услуга от ЕС: ЗДДС чл. 82 ал. 2 т. 3 — „данъкът е изискуем от получателя по
   * доставката“. Самоначисляване по к.14/к.15, не по к.22 (к.22 са НАШИТЕ услуги към ЕС, кл.17).
   */
  @Test
  @DisplayName("Услуга от ЕС по чл. 82 ал. 2 т. 3, 100/20 с пълен кредит — нетна нула, не кл.17")
  void услуга_от_ЕС_по_чл82() {
    assertNetZero("услуга от ЕС", selfChargeCells(), selfCharge("SERVICE_EU"));
  }

  /**
   * Получената услуга не е НАША доставка към ЕС: полето „услуги“ на VIES (02-17, к.22) за
   * доставчика е 0. Преди тя влизаше там със 100 — декларирана като наша услуга към доставчика.
   */
  @Test
  @DisplayName("Услугата от ЕС не се декларира във VIES като наша услуга към доставчика")
  void услуга_от_ЕС_не_е_във_VIES() {
    BgVatExportService service = exportService(Arrays.asList(selfCharge("SERVICE_EU")));
    List<BgVatDocumentDto> sales =
        service.filterSalesDocuments(service.collector.collect(request()));
    assertEquals(1, sales.size(), "протоколът е в дневника за продажбите");

    List<String> vies =
        new BgVatViesAdapter(salesAdapter)
            .buildLines(
                sales,
                new BgVatViesAdapter.RegisteredPerson("BG100000033", "Дружество", "София"),
                new BgVatViesAdapter.Declarant("1", "Х", "София", "1000", "Х", "Х"),
                "06/2026");
    for (String vir : vies) {
      if (vir.startsWith("VIR")) {
        // VIR: таг 3 + № 5 + ИН 15, после ВОД / тристранни / услуги по 12 знака.
        assertEquals("0.00", vir.substring(47, 59).trim(), vir);
      }
    }
  }

  /**
   * Протокол по чл. 79 ал. 4 за корекцията по ал. 1 (брак): Дт разход / Кт 4532, както го съставя
   * {@code BgArt79ProtocolService}. ППЗДДС чл. 66 ал. 9: „Протоколите по ал. 3 се посочват в колона
   * 12 …на дневника за продажбите“.
   */
  @Test
  @DisplayName("чл. 79 ал. 1 — брак: 720 в к.12, тоест в кл.21 и кл.20, и до кл.50")
  void чл79_ал1_брак() {
    Move scrap = protocol("CORRECTION_79", null, line("609", "720", "0"), line("4532", "0", "720"));
    Expect cells =
        new Expect()
            .cell("брой продажби", "1")
            // 01-20 = [02-20] = сбор на 02-21, 02-22, 02-23, 02-24
            .cell("20", "720")
            // 01-21 = [02-21]; основа няма — кл.01 и кл.11 не мърдат
            .cell("21", "720");
    // 01-50 = [01-20] − [01-40] ≥ 0: 1900 + 720.
    assertDeltas("чл. 79 ал. 1", payable(), new Expect().merge(cells).cell("50", "720"), scrap);
    // 01-60 = [01-20] − [01-40] < 0: 1980 − 720.
    assertDeltas("чл. 79 ал. 1", refundable(), new Expect().merge(cells).cell("60", "-720"), scrap);
  }

  /**
   * Протокол по чл. 79 ал. 7 за приспадането по ал. 5: Дт 4531 / Кт приход. ППЗДДС чл. 66 ал. 9:
   * „…протоколите по ал. 4, 5 и 6 … се посочват в колона 11 „ДДС с право на пълен данъчен кредит“
   * на дневника за покупките“.
   */
  @Test
  @DisplayName("чл. 79 ал. 5 — приспадане: 480 в к.11, тоест в кл.41 и кл.40")
  void чл79_ал5_приспадане() {
    Move deduction =
        protocol("DEDUCTION_79", null, line("4531", "480", "0"), line("709", "0", "480"));
    Expect cells =
        new Expect()
            .cell("брой покупки", "1")
            // 01-41 = [03-41]; основа няма — кл.31 не мърда
            .cell("41", "480")
            // 01-40 = [01-41] + [01-42] × [01-33] + [01-43]
            .cell("40", "480");
    assertDeltas(
        "чл. 79 ал. 5", payable(), new Expect().merge(cells).cell("50", "-480"), deduction);
    assertDeltas(
        "чл. 79 ал. 5", refundable(), new Expect().merge(cells).cell("60", "480"), deduction);
  }

  /** Годишната корекция по чл. 79а ал. 4 — увеличение и намаление в същата к.11, със знак. */
  @Test
  @DisplayName("чл. 79а — увеличение +200 и намаление −300 в к.11: кл.41 −100")
  void чл79а_годишна_корекция() {
    Move up = protocol("ANNUAL_79A", null, line("4531", "200", "0"), line("709", "0", "200"));
    Move down = protocol("ANNUAL_79A", null, line("609", "300", "0"), line("4531", "0", "300"));
    Expect cells =
        new Expect()
            .cell("брой покупки", "2")
            // 01-41 = [03-41] = 200 − 300
            .cell("41", "-100")
            .cell("40", "-100");
    assertDeltas("чл. 79а", payable(), new Expect().merge(cells).cell("50", "100"), up, down);
  }

  /**
   * ВОП с ЧАСТИЧЕН кредит: кредитният тип „2“ на реда по 4531 праща основата в к.12, а пълния данък
   * в к.13 (ППЗДДС чл. 97а ал. 4 б. „б“ по аналогия; чл. 49а ал. 5 т. 1). Коефициентът се прилага в
   * декларацията — тук кл.33 = 0.50.
   */
  @Test
  @DisplayName("ВОП 100/20 с частичен кредит: к.12/к.13, кл.40 = кл.42 × кл.33 = 10")
  void ръчен_вОП_с_частичен_кредит() {
    MoveLine credit = line("4531", "20", "0");
    credit.setBgVatCreditType("2");
    Move vop = protocol("VOP", "0.20", credit, line("4532", "0", "20"));
    Expect cells =
        new Expect()
            .cell("брой продажби", "1")
            .cell("брой покупки", "1")
            .cell("01", "100") // [02-10]
            .cell("20", "20") // [02-20]
            .cell("12", "100") // [02-12] + [02-26]
            .cell("22", "20") // [02-22]
            // 01-32 = [03-32]
            .cell("32", "100")
            // 01-42 = [03-42]
            .cell("42", "20")
            // 01-40 = [01-41] + [01-42] × [01-33] + [01-43] = 20 × 0.50
            .cell("40", "10");
    // 01-50 = [01-20] − [01-40] ≥ 0: +20 − 10.
    assertDeltas("ВОП частичен", payable(), new Expect().merge(cells).cell("50", "10"), vop);
    // 01-60 = [01-20] − [01-40] < 0: 1980 − 10.
    assertDeltas("ВОП частичен", refundable(), new Expect().merge(cells).cell("60", "-10"), vop);
  }

  /**
   * Протокол по чл. 163а БЕЗ право на кредит: Дт разход (тип „3“) / Кт 4532. В покупките к.9 — „ДО
   * и данък … без право на данъчен кредит“ (03-30); кредит няма, тоест данъкът е за внасяне.
   */
  @Test
  @DisplayName("чл. 163а 100/20 без право на кредит: к.9 = 120 в кл.30, кл.50 +20")
  void чл163а_без_право_на_кредит() {
    MoveLine expense = line("609", "20", "0");
    expense.setBgVatCreditType("3");
    Move scrap = protocol("ART163A", "0.20", expense, line("4532", "0", "20"));
    scrap.setBgVatSupplyType("01");
    Expect cells =
        new Expect()
            .cell("брой продажби", "1")
            .cell("брой покупки", "1")
            .cell("01", "100") // [02-10]
            .cell("20", "20") // [02-20]
            .cell("12", "100") // [02-12] + [02-26]
            .cell("22", "20") // [02-22]
            // 01-30 = [03-30] + [03-44]
            .cell("30", "120");
    assertDeltas(
        "чл. 163а без кредит", payable(), new Expect().merge(cells).cell("50", "20"), scrap);
    assertDeltas(
        "чл. 163а без кредит", refundable(), new Expect().merge(cells).cell("60", "-20"), scrap);
  }

  /** Внос по чл. 167а: протоколът по чл. 57 ал. 6 с пълен кредит и митническият документ. */
  private static Move import167a() {
    MoveLine credit = line("4531", "20", "0");
    credit.setBgVatCreditType("1");
    Move m = protocol("IMPORT_167A", "0.20", credit, line("4532", "0", "20"));
    m.setOrigin("26BG002000123456R1"); // MRN на ЕАД
    m.setOriginDate(LocalDate.of(2026, 6, 10));
    m.setReference("#167");
    return m;
  }

  /**
   * Внос с отложено начисляване по чл. 167а — ППЗДДС чл. 49а. Протоколът е в ДВАТА дневника (основа
   * в к.9/к.11 на продажбите, ал. 4 т. 1), а митническият документ е втори ред във всеки от тях
   * само с колони 1–8 (ал. 4 т. 2 и ал. 5 т. 2), тоест броят документи расте с 2 и в двата.
   */
  @Test
  @DisplayName("Внос по чл. 167а 100/20 с пълен кредит — нетна нула, по два реда в дневниците")
  void внос_по_чл167а() {
    Expect cells =
        new Expect()
            .cell("брой продажби", "2")
            .cell("брой покупки", "2")
            // 01-01 = [02-10] = сбор на 02-11, …
            .cell("01", "100")
            // 01-20 = [02-20] = сбор на 02-21, …
            .cell("20", "20")
            // 01-11 = [02-11]
            .cell("11", "100")
            // 01-21 = [02-21]
            .cell("21", "20")
            // 01-31 = [03-31]
            .cell("31", "100")
            // 01-41 = [03-41]
            .cell("41", "20")
            // 01-40 = [01-41] + [01-42] × [01-33] + [01-43]
            .cell("40", "20");
    assertNetZero("чл. 167а", cells, import167a());
  }

  @Test
  @DisplayName("Внос по чл. 167а в PRODAGBI: протокол 09 с к.8а „03“ и ЕАД 07 само с колони 1–8")
  void внос_по_чл167а_в_дневника_за_продажби() {
    BgVatExportService service = exportService(Arrays.asList(import167a()));
    List<BgVatDocumentDto> sales =
        service.filterSalesDocuments(service.collector.collect(request()));
    List<String> lines = salesAdapter.buildLines(sales, request());

    assertEquals(2, lines.size());
    String protocol = lines.get(0);
    assertEquals("09", protocol.substring(40, 42));
    assertEquals("#167", protocol.substring(42, 62).trim());
    assertEquals("100.00", protocol.substring(167, 182).trim()); // к.9
    assertEquals("20.00", protocol.substring(182, 197).trim()); // к.10
    assertEquals("100.00", protocol.substring(197, 212).trim()); // к.11
    assertEquals("20.00", protocol.substring(212, 227).trim()); // к.12
    assertEquals("03", protocol.substring(422, 424)); // к.8а

    String customs = lines.get(1);
    assertEquals("07", customs.substring(40, 42));
    assertEquals("26BG002000123456R1", customs.substring(42, 62).trim());
    assertEquals("10/06/2026", customs.substring(62, 72));
    assertEquals("           0.00".repeat(17), customs.substring(167, 422));
    assertEquals("  ", customs.substring(422, 424));
  }

  /** ППЗДДС чл. 97а ал. 1: кодът „01“/„02“ е в к.8а „на отчетните регистри“ — и в продажбите. */
  @Test
  @DisplayName("Протоколът по чл. 163а носи к.8а „01“ и в PRODAGBI, не само в POKUPKI")
  void чл163а_к8а_в_дневника_за_продажби() {
    Move scrap = selfCharge("ART163A");
    scrap.setBgVatSupplyType("01");
    BgVatExportService service = exportService(Arrays.asList(scrap));
    List<BgVatDocumentDto> sales =
        service.filterSalesDocuments(service.collector.collect(request()));

    assertEquals("01", salesAdapter.buildLines(sales, request()).get(0).substring(422, 424));
  }

  @Test
  @DisplayName("Протокол по чл. 163а (Прил. № 2 част I, скрап) 100/20 с пълен кредит — нетна нула")
  void чл163а_скрап() {
    Move scrap = selfCharge("ART163A");
    scrap.setBgVatSupplyType("01");
    assertNetZero("чл. 163а", selfChargeCells(), scrap);
  }
}
