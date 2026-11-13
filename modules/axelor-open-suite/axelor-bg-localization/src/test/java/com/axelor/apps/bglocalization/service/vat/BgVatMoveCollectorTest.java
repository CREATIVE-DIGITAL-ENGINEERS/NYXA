package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Account;
import com.axelor.apps.account.db.AccountType;
import com.axelor.apps.account.db.Move;
import com.axelor.apps.account.db.MoveLine;
import com.axelor.apps.account.db.TaxLine;
import com.axelor.apps.account.db.repo.AccountTypeRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatCounterpartyId;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDocumentType;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatPurchaseColumn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesColumn;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatDocumentDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatValidationErrorDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Collector-level tests for the two-mode manual-move VAT routing (mixed line-level credit types +
 * document-level operation with reverse-computed base for protocols). Minimal Move/MoveLine/Account
 * POJOs, no DB / no Guice — {@code moveRepo} is unused by {@code enrichFromMove}.
 */
class BgVatMoveCollectorTest {

  private final BgVatDocumentCollector collector = new BgVatDocumentCollector(null);

  private MoveLine line(
      String accountCode, String debit, String taxRate, String taxAmount, String creditType) {
    MoveLine l = new MoveLine();
    if (accountCode != null) {
      Account a = new Account();
      a.setCode(accountCode);
      l.setAccount(a);
    }
    l.setDebit(debit == null ? BigDecimal.ZERO : new BigDecimal(debit));
    l.setCredit(BigDecimal.ZERO);
    if (taxRate != null) {
      l.setTaxRate(new BigDecimal(taxRate));
    }
    if (taxAmount != null) {
      l.setTaxAmount(new BigDecimal(taxAmount));
    }
    if (creditType != null) {
      l.setBgVatCreditType(creditType);
    }
    return l;
  }

  private MoveLine creditLine(String accountCode, String credit, String taxRate) {
    MoveLine l = new MoveLine();
    Account a = new Account();
    a.setCode(accountCode);
    l.setAccount(a);
    l.setDebit(BigDecimal.ZERO);
    l.setCredit(new BigDecimal(credit));
    if (taxRate != null) {
      l.setTaxRate(new BigDecimal(taxRate));
    }
    return l;
  }

  private BgVatDocumentDto enrich(Move move) {
    BgVatDocumentDto doc = new BgVatDocumentDto();
    collector.enrichFromMove(doc, move);
    return doc;
  }

  private static void eq(String expected, BigDecimal actual) {
    assertEquals(
        0, new BigDecimal(expected).compareTo(actual), "expected " + expected + " got " + actual);
  }

  // а) Обикновена ръчна покупка (PURCHASE_FULL): база 100 + 4531 20 → к.10=100, к.11=20.
  @Test
  void purchaseFull_operation_baseAndVatToK10K11() {
    Move move = new Move();
    move.setBgVatMoveVatOperation("PURCHASE_FULL");
    move.setMoveLineList(
        Arrays.asList(
            line("601", "100", "20", null, null), // базов ред (taxRate е в проценти)
            line("4531", "20", null, null, null))); // ДДС покупки
    BgVatDocumentDto doc = enrich(move);

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("0", doc.getPurchaseAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT));
  }

  // б) ВОП протокол: 4531=20, ставка 0.20, без базов ред → база 100 к.10, ДДС 20 к.11,
  //    изходяща страна к.13 „ДО на ВОП“ / к.15 на продажбите.
  @Test
  void vop_protocol_reverseComputesBase_andFillsBothSides() {
    Move move = new Move();
    move.setBgVatMoveVatOperation("VOP");
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    move.setMoveLineList(Arrays.asList(line("4531", "20", null, null, null)));
    BgVatDocumentDto doc = enrich(move);

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT)); // 20 / 0.20
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("100", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    eq("0", doc.getSalesAmount(BgVatSalesColumn.K14_BASE_RC)); // к.14 е за чл. 82 ал. 2–6
    eq("20", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
  }

  // в) Услуга от ЕО (SERVICE_EU): 4531=20, ставка 0.20 → к.10/к.11 покупки + к.14/к.15 продажби.
  //    ЗДДС чл. 82 ал. 2 т. 3: данъкът е изискуем от получателя. к.22 е за НАШИТЕ услуги към ЕС.
  @Test
  void serviceEu_protocol_isReverseCharge_K14K15() {
    Move move = new Move();
    move.setBgVatMoveVatOperation("SERVICE_EU");
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    move.setMoveLineList(Arrays.asList(line("4531", "20", null, null, null)));
    BgVatDocumentDto doc = enrich(move);

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("100", doc.getSalesAmount(BgVatSalesColumn.K14_BASE_RC));
    eq("20", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    eq("0", doc.getSalesAmount(BgVatSalesColumn.K22_BASE_EU_SERVICES));
  }

  // г) чл.163а протокол (ART163A, bgVatSupplyType="01") → к.10/к.11 + supplyTypeArt163a=="01".
  @Test
  void art163a_protocol_setsSupplyType() {
    Move move = new Move();
    move.setBgVatMoveVatOperation("ART163A");
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    move.setBgVatSupplyType("01");
    move.setMoveLineList(Arrays.asList(line("4531", "20", null, null, null)));
    BgVatDocumentDto doc = enrich(move);

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertEquals("01", doc.getSupplyTypeArt163a());
  }

  // д) СМЕСЕН ръчен запис (line-level): ред A "1" база100+ДДС20, ред B "3" база50
  //    → к.10=100, к.11=20 И к.9=50 на един ред (нещо, което single-operation инструментите не
  // могат).
  @Test
  void mixedManualEntry_splitsAcrossK10K11AndK9() {
    Move move = new Move();
    MoveLine a = line("601", "100", null, "20", "1"); // пълен ДК
    MoveLine b = line("602", "50", null, "0", "3"); // без ДК
    move.setMoveLineList(Arrays.asList(a, b));
    BgVatDocumentDto doc = enrich(move);

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("50", doc.getPurchaseAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT));
  }

  // ж) Протокол с изрична 4532 (изходящ ДДС): 4532 НЕ се брои като база за покупките,
  //    а като начислен данък → продажби. вход 4531=20, изход 4532=20, ставка 0.20.
  @Test
  void protocol_with4532Output_excludedFromBase_drivesSalesSide() {
    Move move = new Move();
    move.setBgVatMoveVatOperation("VOP");
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    move.setMoveLineList(
        Arrays.asList(
            line("4531", "20", null, null, null), // вход (дебит) → данъчен кредит
            creditLine("4532", "20", "20"))); // изход (кредит) → начислен данък
    BgVatDocumentDto doc = enrich(move);

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("100", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    eq("20", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    // 4532 не е третирана като база → няма фалшив warning за разминаване база.
    assertTrue(
        doc.getWarnings().stream().noneMatch(w -> w.startsWith("Разминаване")),
        "4532 must not count as base: " + doc.getWarnings());
  }

  // е) Разминаване на ставка (ръчна 0.09 ≠ единствената 20 % на редовете) → ГРЕШКА, както в SAF-T
  //    (BgSaftDiaryCells.rates): ставката на протокола е една (ЗДДС чл. 117 ал. 2 т. 6), а коя е
  //    вярната — не се знае.
  @Test
  void rateMismatch_isABlockingError_likeInSaft() {
    Move move = new Move();
    move.setBgVatProtocolNumber("0000000001");
    move.setBgVatMoveVatOperation("VOP");
    move.setBgVatMoveVatRate(new BigDecimal("0.09")); // ръчна
    move.setMoveLineList(
        Arrays.asList(
            line("601", "100", "20", null, null), // базов ред с изведена ставка 20 %
            line("4531", "20", null, null, null)));
    BgVatDocumentDto doc = enrich(move);

    assertEquals(1, doc.getErrors().size(), doc.getErrors().toString());
    assertEquals(
        BgVatValidator.PROTOCOL_MANUAL_RATE_CONFLICT_CODE, doc.getErrors().get(0).getCode());
    assertTrue(
        doc.getErrors()
            .get(0)
            .getMessage()
            .contains("ръчна ставка 0.09 ≠ ставката на редовете 0.2"),
        doc.getErrors().get(0).getMessage());
    // Записът пак се маршрутизира — ГРЕШКАТА блокира пакета, не колекцията.
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
  }

  // --- Кои статии влизат изобщо: isVatRelevant ---------------------------------

  /** Колекторът с подадени статии вместо JPQL — истинските isVatRelevant() и toDocument(). */
  private static List<BgVatDocumentDto> collect(Move... moves) {
    BgVatDocumentCollector probe =
        new BgVatDocumentCollector(null) {
          @Override
          protected List<Move> findPostedMoves(BgVatExportRequestDto request) {
            return Arrays.asList(moves);
          }
        };
    return probe.collect(new BgVatExportRequestDto());
  }

  /**
   * Редът, както го ражда {@code MoveLineCreateServiceImpl.createMoveLine} — пътят на
   * BgArt79ProtocolService и BgArt79aProtocolService: 13-аргументният конструктор и ПРАЗЕН
   * taxLineSet. Данък на реда няма и не може да има: 4531/4532 са с isTaxAuthorizedOnMoveLine =
   * false.
   */
  private static MoveLine serviceLine(String accountCode, String debit, String credit) {
    Account a = new Account();
    a.setCode(accountCode);
    BigDecimal d = new BigDecimal(debit);
    BigDecimal c = new BigDecimal(credit);
    LocalDate date = LocalDate.of(2026, 6, 30);
    MoveLine l =
        new MoveLine(
            null,
            null,
            a,
            date,
            date,
            1,
            d,
            c,
            "протокол",
            "ЧЛ79-1",
            BigDecimal.ONE,
            d.subtract(c),
            date);
    l.setTaxLineSet(new HashSet<>());
    return l;
  }

  private static Move serviceProtocol(String operation, MoveLine... lines) {
    Move move = new Move();
    move.setStatusSelect(MoveRepository.STATUS_ACCOUNTED);
    move.setNapDocumentTypeSelect("09");
    move.setBgVatMoveVatOperation(operation);
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    move.setMoveLineList(Arrays.asList(lines));
    return move;
  }

  @Test
  void collect_keepsProtocols_thatCarryOnlyTheVatAccounts() {
    List<BgVatDocumentDto> docs =
        collect(
            serviceProtocol(
                "CORRECTION_79", serviceLine("609", "720", "0"), serviceLine("4532", "0", "720")),
            serviceProtocol(
                "DEDUCTION_79", serviceLine("4531", "480", "0"), serviceLine("709", "0", "480")),
            serviceProtocol(
                "ANNUAL_79A", serviceLine("4531", "200", "0"), serviceLine("709", "0", "200")),
            serviceProtocol("VOP", serviceLine("4531", "20", "0"), serviceLine("4532", "0", "20")));

    assertEquals(4, docs.size(), "нито един протокол не бива да изпада");
    eq("720", docs.get(0).getSalesAmount(BgVatSalesColumn.K12_VAT_20));
    eq("480", docs.get(1).getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("200", docs.get(2).getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("20", docs.get(3).getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
  }

  @Test
  void collect_keepsALineCreditType_withoutAnyTax() {
    // Получена доставка без данък, въведена с кредитен тип „3“ на реда (режим 1).
    Move move = new Move();
    move.setMoveLineList(
        Arrays.asList(line("602", "50", null, null, "3"), creditLine("401", "50", null)));

    List<BgVatDocumentDto> docs = collect(move);

    assertEquals(1, docs.size());
    eq("50", docs.get(0).getPurchaseAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT));
  }

  @Test
  void collect_stillDropsAMoveWithoutVat() {
    // Плащане: без фактура, без ДДС операция, без кредитен тип и без данък.
    Move payment = new Move();
    payment.setMoveLineList(
        Arrays.asList(line("401", "120", null, null, null), creditLine("503", "120", null)));

    assertTrue(collect(payment).isEmpty());
  }

  /** Сметка с вид по Axelor ({@code AccountType.technicalTypeSelect}). */
  private static MoveLine typed(MoveLine line, String technicalType) {
    AccountType type = new AccountType();
    type.setTechnicalTypeSelect(technicalType);
    line.getAccount().setAccountType(type);
    return line;
  }

  @Test
  void collect_dropsAPayment_whosePartnerLineCarriesThePaidVatShare() {
    // Плащане по фактура със 120 (100 + 20 ДДС): Axelor пише дела на платения данък в taxAmount на
    // реда 411 (MoveLineTaxServiceImpl.computeTaxAmount от TaxPaymentMoveLine). Не е ДДС документ.
    Move payment = new Move();
    payment.setMoveLineList(
        Arrays.asList(
            line("503", "120", null, null, null),
            typed(creditLine("411", "120", null), AccountTypeRepository.TYPE_RECEIVABLE)));
    payment.getMoveLineList().get(1).setTaxAmount(new BigDecimal("20"));

    assertTrue(collect(payment).isEmpty(), "плащането не е ред в дневниците");
  }

  @Test
  void collect_dropsAnExpensePayment_whoseEmployeeLineCarriesTheTax() {
    // ExpensePaymentServiceImpl: taxAmount на реда на служителя = ДДС по разхода. Пак не е ДДС.
    Move payment = new Move();
    payment.setMoveLineList(
        Arrays.asList(
            typed(line("422", "60", null, "10", null), AccountTypeRepository.TYPE_PAYABLE),
            creditLine("501", "60", null)));

    assertTrue(collect(payment).isEmpty());
  }

  @Test
  void collect_keepsATaxAmount_onATaxAccount() {
    // Ръчен ред по 4531 със сума на данъка — ДДС ред, остава (режим 3, основата не е определена).
    Move move = new Move();
    move.setMoveLineList(
        Arrays.asList(
            typed(line("4531", "20", "20", "20", null), AccountTypeRepository.TYPE_TAX),
            creditLine("401", "20", null)));

    List<BgVatDocumentDto> docs = collect(move);

    assertEquals(1, docs.size());
    eq("20", docs.get(0).getTotalVat());
  }

  @Test
  void collect_keepsATaxLineSet_onAnyAccount() {
    // Данък на реда (taxLineSet) решава, каквато и да е сметката — както преди.
    MoveLine base = line("602", "100", "20", null, null);
    base.setTaxLineSet(new HashSet<>(Arrays.asList(new TaxLine())));
    Move move = new Move();
    move.setMoveLineList(Arrays.asList(base, creditLine("401", "100", null)));

    assertEquals(1, collect(move).size());
  }

  // --- Ставката: на реда е в ПРОЦЕНТИ, ръчната — дроб ------------------------

  // MoveLine.taxRate го пише Axelor при осчетоводяване: getTotalTaxRateInPercentage(taxLineSet),
  // тоест 20 за BG_SRP_20. Ръчната bgVatMoveVatRate е „напр. 0.20“.
  private Move art163a(String manualRate, MoveLine... lines) {
    Move move = new Move();
    move.setBgVatProtocolNumber("0000000001");
    move.setBgVatMoveVatOperation("ART163A");
    if (manualRate != null) {
      move.setBgVatMoveVatRate(new BigDecimal(manualRate));
    }
    move.setMoveLineList(Arrays.asList(lines));
    return move;
  }

  @Test
  void derivedRate_isThePercentOnTheLine_dividedBy100() {
    BgVatDocumentDto doc =
        enrich(
            art163a(
                null, line("601", "100", "20", null, null), line("4531", "20", null, null, null)));

    // 20 / 0.20 = 100 — не 20 / 20 = 1.00.
    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("100", doc.getSalesAmount(BgVatSalesColumn.K14_BASE_RC));
    assertTrue(
        doc.getWarnings().stream().noneMatch(w -> w.startsWith("Разминаване")),
        doc.getWarnings().toString());
  }

  @Test
  void manualFraction_andLinePercent_doNotDisagree() {
    BgVatDocumentDto doc =
        enrich(
            art163a(
                "0.20",
                line("601", "100", "20", null, null),
                line("4531", "20", null, null, null)));

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    assertTrue(
        doc.getWarnings().stream().noneMatch(w -> w.startsWith("Разминаване ставка")),
        "0.20 и 20 % са една ставка: " + doc.getWarnings());
  }

  @Test
  void manualRateInPercent_isDividedBy100_withWarning() {
    BgVatDocumentDto doc = enrich(art163a("20", line("4531", "20", null, null, null)));

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("100", doc.getSalesAmount(BgVatSalesColumn.K14_BASE_RC));
    assertTrue(
        doc.getWarnings()
            .contains("Ставката на протокола е въведена в проценти (20) — приета като 0.2"),
        doc.getWarnings().toString());
    assertTrue(doc.getErrors().isEmpty(), "20 % е законова ставка");
  }

  @Test
  void manualRate_nineAndZero_areStatutory() {
    assertTrue(enrich(art163a("0.09", line("4531", "9", null, null, null))).getErrors().isEmpty());
    assertTrue(enrich(art163a("9", line("4531", "9", null, null, null))).getErrors().isEmpty());
    assertTrue(enrich(art163a(null, line("4531", "20", null, null, null))).getErrors().isEmpty());
  }

  @Test
  void manualRate_notStatutory_isABlockingError() {
    for (String rate : new String[] {"0.15", "15", "0.2001"}) {
      BgVatDocumentDto doc = enrich(art163a(rate, line("4531", "20", null, null, null)));
      doc.setDocumentNumber("П-" + rate);

      assertEquals(1, doc.getErrors().size(), rate);
      assertEquals(BgVatValidator.PROTOCOL_RATE_INVALID_CODE, doc.getErrors().get(0).getCode());
      assertTrue(
          new BgVatValidator()
              .validateDocuments(Arrays.asList(doc), null).stream()
                  .anyMatch(
                      f ->
                          BgVatValidator.PROTOCOL_RATE_INVALID_CODE.equals(f.getCode())
                              && BgVatValidationErrorDto.SEVERITY_ERROR.equals(f.getSeverity())
                              && ("П-" + rate).equals(f.getDocumentRef())),
          rate);
    }
  }

  // --- Смесени ставки: основата по ставката на ВСЕКИ ред, не по първата ---------
  // ЗДДС чл. 117 ал. 2 т. 5 и т. 8: основата и данъкът на протокола. 1000 × 20 % + 500 × 9 % е
  // основа
  // 1500 и данък 245 — обърнат през първата ставка, данъкът даваше 245 / 0.20 = 1225 в к.13.

  private Move vop(MoveLine... lines) {
    Move move = new Move();
    move.setBgVatProtocolNumber("0000000001");
    move.setBgVatMoveVatOperation("VOP");
    move.setMoveLineList(Arrays.asList(lines));
    return move;
  }

  private static void assertNoMismatch(BgVatDocumentDto doc) {
    assertTrue(
        doc.getWarnings().stream().noneMatch(w -> w.startsWith("Разминаване")),
        doc.getWarnings().toString());
    assertTrue(doc.getErrors().isEmpty(), doc.getErrors().toString());
  }

  @Test
  void mixedRates_withBaseLines_baseIsTheSumOfTheBaseLines() {
    BgVatDocumentDto doc =
        enrich(
            vop(
                line("304", "1000", "20", null, null),
                line("302", "500", "9", null, null),
                creditLine("401", "1500", null),
                line("4531", "245", null, null, null),
                creditLine("4532", "245", null)));

    eq("1500", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    eq("245", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    eq("1500", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("245", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertNoMismatch(doc);
  }

  @Test
  void mixedRates_onTheVatLines_baseIsReverseComputedPerRate() {
    // Без базови редове: всеки ред по 4532 носи ставката си → 200 / 0.20 + 45 / 0.09.
    BgVatDocumentDto doc =
        enrich(
            vop(
                line("4531", "200", "20", null, null),
                line("4531", "45", "9", null, null),
                creditLine("4532", "200", "20"),
                creditLine("4532", "45", "9")));

    eq("1500", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    eq("245", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    eq("1500", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("245", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertNoMismatch(doc);
  }

  @Test
  void mixedRates_onlyTheInputSideCarriesThem_baseIsReverseComputedFromIt() {
    // Самоначисляването е равно по построение: 4531 носи ставките, 4532 е един ред без ставка.
    BgVatDocumentDto doc =
        enrich(
            vop(
                line("4531", "200", "20", null, null),
                line("4531", "45", "9", null, null),
                creditLine("4532", "245", null)));

    eq("1500", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    eq("245", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    assertNoMismatch(doc);
  }

  @Test
  void mixedRates_taxOffTheBaseLines_warnsAndKeepsTheBase() {
    BgVatDocumentDto doc =
        enrich(
            vop(
                line("304", "1000", "20", null, null),
                line("302", "500", "9", null, null),
                creditLine("401", "1500", null),
                line("4531", "250", null, null, null),
                creditLine("4532", "250", null)));

    eq("1500", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    eq("250", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    assertTrue(
        doc.getWarnings()
            .contains(
                "Разминаване данък на протокол със смесени ставки: по ставките на базовите редове"
                    + " 245.00 ≠ начислен 250"),
        doc.getWarnings().toString());
  }

  @Test
  void mixedRates_withoutACarrierPerRate_isABlockingError() {
    // Ставките са само по част от редовете по 4531/4532 и базови редове няма — основата не се
    // определя по ставка, а през една ставка би била грешна.
    BgVatDocumentDto doc =
        enrich(
            vop(
                line("4531", "200", "20", null, null),
                line("4531", "45", null, null, null),
                creditLine("4532", "200", null),
                creditLine("4532", "45", "9")));

    assertEquals(1, doc.getErrors().size(), doc.getErrors().toString());
    assertEquals(BgVatValidator.PROTOCOL_MIXED_RATE_BASE_CODE, doc.getErrors().get(0).getCode());
    eq("0", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    eq("245", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
  }

  @Test
  void mixedRates_withAManualRate_isABlockingError_likeInSaft() {
    // Ръчната ставка е ЕДНА за протокола (ЗДДС чл. 117 ал. 2 т. 6); до смесени ставки по редовете
    // тя
    // е нееднозначна. SAF-T я отказва (BgSaftDiaryCells.rates) — дневникът също, а не с
    // предупреждение и основа 245 / 0.20 = 1225.
    Move move =
        vop(
            line("304", "1000", "20", null, null),
            line("302", "500", "9", null, null),
            creditLine("401", "1500", null),
            line("4531", "245", null, null, null),
            creditLine("4532", "245", null));
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    BgVatDocumentDto doc = enrich(move);

    assertEquals(1, doc.getErrors().size(), doc.getErrors().toString());
    assertEquals(BgVatValidator.PROTOCOL_MANUAL_RATE_MIXED_CODE, doc.getErrors().get(0).getCode());
    assertTrue(
        doc.getErrors()
            .get(0)
            .getMessage()
            .contains("ръчна ставка 0.2 до смесени ставки по редовете 0.2, 0.09"),
        doc.getErrors().get(0).getMessage());
    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validateDocuments(List.of(doc), new BgVatExportRequestDto());
    assertTrue(
        findings.stream()
            .anyMatch(
                f ->
                    BgVatValidator.PROTOCOL_MANUAL_RATE_MIXED_CODE.equals(f.getCode())
                        && BgVatValidationErrorDto.SEVERITY_ERROR.equals(f.getSeverity())),
        findings.toString());
  }

  // --- Изричната основа на протокола (Move.bgVatProtocolBase) -------------------
  // ЗДДС чл. 117 ал. 2 т. 5 „данъчната основа“. Обратното смятане данък / ставка губи центове:
  // 1234.57 × 20 % = 246.91, а 246.91 / 0.20 = 1234.55.

  @Test
  void explicitBase_winsOverTheReverseComputation() {
    Move move = vop(line("4531", "246.91", null, null, "1"), creditLine("4532", "246.91", null));
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    move.setBgVatProtocolBase(new BigDecimal("1234.57"));
    BgVatDocumentDto doc = enrich(move);

    eq("1234.57", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    eq("246.91", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    eq("1234.57", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("246.91", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertNoMismatch(doc);
  }

  @Test
  void withoutAnExplicitBase_theCentsAreLost_asBefore() {
    // Свидетелят на горния тест: същата статия без изрична основа.
    Move move = vop(line("4531", "246.91", null, null, "1"), creditLine("4532", "246.91", null));
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    BgVatDocumentDto doc = enrich(move);

    eq("1234.55", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
  }

  // Основа, изведена от данъка без нищо, което да я свери — не тихо (стотинките се губят).
  @Test
  void derivedBase_withoutBaseLines_isAWarning_withTheDerivedValue() {
    Move move = vop(line("4531", "246.91", null, null, "1"), creditLine("4532", "246.91", null));
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    BgVatDocumentDto doc = enrich(move);

    eq("1234.55", doc.getDerivedBase());
    List<BgVatValidationErrorDto> findings =
        new BgVatValidator().validateDocuments(List.of(doc), new BgVatExportRequestDto());
    BgVatValidationErrorDto finding =
        findings.stream()
            .filter(f -> BgVatValidator.PROTOCOL_BASE_DERIVED_CODE.equals(f.getCode()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(findings.toString()));
    assertEquals(BgVatValidationErrorDto.SEVERITY_WARNING, finding.getSeverity());
    assertTrue(finding.getMessage().contains("1234.55"), finding.getMessage());
    assertTrue(
        finding.getMessage().contains("въведете основата в протокола"), finding.getMessage());
  }

  @Test
  void derivedBase_isSilentOnlyWhenSomethingConfirmsIt() {
    // Изрична основа — нищо не се извежда.
    Move explicit =
        vop(line("4531", "246.91", null, null, "1"), creditLine("4532", "246.91", null));
    explicit.setBgVatMoveVatRate(new BigDecimal("0.20"));
    explicit.setBgVatProtocolBase(new BigDecimal("1234.57"));
    assertEquals(null, enrich(explicit).getDerivedBase());
    // Базови редове — изведената се сверява с тях (и разминаването вече предупреждава).
    Move withLines =
        vop(
            line("304", "1000", "20", null, "1"),
            creditLine("401", "1000", null),
            line("4531", "200", null, null, null),
            creditLine("4532", "200", null));
    assertEquals(null, enrich(withLines).getDerivedBase());
  }

  @Test
  void explicitBase_thatDisagreesWithTheTax_warns() {
    Move move = vop(line("4531", "200", null, null, "1"), creditLine("4532", "200", null));
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    move.setBgVatProtocolBase(new BigDecimal("1100"));
    BgVatDocumentDto doc = enrich(move);

    eq("1100", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    assertTrue(
        doc.getWarnings()
            .contains("Разминаване на протокол: основа 1100 × ставка 0.2 = 220.00 ≠ данък 200"),
        doc.getWarnings().toString());
  }

  @Test
  void singleRate_onSeveralBaseLines_isUnchanged() {
    // Регресия: една ставка по няколко реда — основата пак е данък / ставка.
    BgVatDocumentDto doc =
        enrich(
            vop(
                line("304", "1000", "20", null, null),
                line("302", "500", "20", null, null),
                creditLine("401", "1500", null),
                line("4531", "300", null, null, null),
                creditLine("4532", "300", null)));

    eq("1500.00", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    eq("300", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    assertEquals(2, doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA).scale(), "данък / ставка");
    assertNoMismatch(doc);
  }

  // --- Протокол: покупната страна по кредитния тип на РЕДОВЕТЕ ----------------
  // ППЗДДС чл. 97а ал. 4 б. „б“ и чл. 49а ал. 5 т. 1: „данъчната основа в колони 9, 10 или 12 и
  // начисления данък в колони 11 или 13“. Основата и данъкът са тези на протокола (4532 / ставка).

  private static Move protocol(String operation, String rate, MoveLine... lines) {
    Move move = new Move();
    move.setBgVatProtocolNumber("0000000001"); // осчетоводен протокол — номерът е даден
    move.setBgVatMoveVatOperation(operation);
    move.setBgVatMoveVatRate(new BigDecimal(rate));
    move.setMoveLineList(Arrays.asList(lines));
    return move;
  }

  @Test
  void protocol_partialCreditOnTheVatLine_goesToK12K13_andKeepsTheSalesSide() {
    BgVatDocumentDto doc =
        enrich(
            protocol(
                "VOP",
                "0.20",
                line("4531", "20", null, null, "2"),
                creditLine("4532", "20", null)));

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT));
    // к.13 е ПЪЛНИЯТ данък — коефициентът се прилага в DEKLAR (кл.42 × кл.33).
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT));
    eq("0", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    // Кредитният тип на реда НЕ прави протокола обикновена покупка (режим 1).
    eq("100", doc.getSalesAmount(BgVatSalesColumn.K13_BASE_ICA));
    eq("20", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    assertTrue(doc.getWarnings().isEmpty(), doc.getWarnings().toString());
  }

  @Test
  void protocol_withoutCredit_foldsBaseAndTaxIntoK09() {
    // Без право на кредит 4531 не се дебитира: Дт разход / Кт 4532.
    BgVatDocumentDto doc =
        enrich(
            protocol(
                "ART163A",
                "0.20",
                line("609", "20", null, null, "3"),
                creditLine("4532", "20", null)));

    eq("120", doc.getPurchaseAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT)); // ДО + данък
    eq("0", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("0", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("100", doc.getSalesAmount(BgVatSalesColumn.K14_BASE_RC));
    eq("20", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
  }

  @Test
  void protocol_mixedCreditTypesOnBaseLines_isOneRowSplitByLine() {
    // Две базови реда: 60 с пълен кредит, 40 без кредит; данъкът 20 се дели 12 / 8.
    BgVatDocumentDto doc =
        enrich(
            protocol(
                "ART163A",
                "0.20",
                line("601", "60", "20", null, "1"),
                line("602", "40", "20", null, "3"),
                creditLine("401", "100", null),
                line("4531", "12", null, null, null),
                line("609", "8", null, null, null),
                creditLine("4532", "20", null)));

    eq("60", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("12", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("48", doc.getPurchaseAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT)); // 40 + 8
    eq("100", doc.getSalesAmount(BgVatSalesColumn.K14_BASE_RC));
    eq("20", doc.getSalesAmount(BgVatSalesColumn.K15_VAT_ICA_RC));
    assertTrue(doc.getWarnings().isEmpty(), doc.getWarnings().toString());
  }

  @Test
  void protocol_sharesAddUpExactly_theLastCarrierTakesTheRemainder() {
    // Основа 3 / 0.09 = 33.33; три равни дяла по 11.11 — без изгубена стотинка.
    BgVatDocumentDto doc =
        enrich(
            protocol(
                "ART163A",
                "0.09",
                line("4531", "1", null, null, "1"),
                line("4531", "1", null, null, "2"),
                line("609", "1", null, null, "3"),
                creditLine("4532", "3", null)));

    eq("11.11", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("1", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    eq("11.11", doc.getPurchaseAmount(BgVatPurchaseColumn.K12_BASE_PARTIAL_CREDIT));
    eq("1", doc.getPurchaseAmount(BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT));
    eq("12.11", doc.getPurchaseAmount(BgVatPurchaseColumn.K09_BASE_NO_CREDIT)); // 11.11 + 1
    eq("33.33", doc.getSalesAmount(BgVatSalesColumn.K14_BASE_RC));
  }

  @Test
  void protocol_carrierWithoutCreditType_isFullCredit_withAWarning() {
    BgVatDocumentDto doc =
        enrich(
            protocol(
                "VOP",
                "0.20",
                line("4531", "20", null, null, null),
                creditLine("4532", "20", null)));

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertTrue(
        doc.getWarnings().contains("Ред без вид данъчен кредит — приет пълен ДК (move line null)"),
        doc.getWarnings().toString());
  }

  @Test
  void protocol_withoutAnyCarrier_isFullCredit_withAWarning() {
    // Дт разход без кредитен тип / Кт 4532: нито 4531, нито ред с тип.
    BgVatDocumentDto doc =
        enrich(
            protocol(
                "VOP",
                "0.20",
                line("609", "20", null, null, null),
                creditLine("4532", "20", null)));

    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertTrue(
        doc.getWarnings().contains("Протокол без вид данъчен кредит — приет пълен ДК"),
        doc.getWarnings().toString());
  }

  @Test
  void art79Deduction_withACreditTypeOnTheLine_staysInK11() {
    // Колоната на чл. 79 е по ППЗДДС чл. 66 ал. 9, не по кредитния тип; и протоколът не става
    // обикновена покупка, в която данъкът по 4531 се чете като основа.
    Move move = new Move();
    move.setBgVatMoveVatOperation("DEDUCTION_79");
    move.setMoveLineList(
        Arrays.asList(line("4531", "480", null, null, "2"), creditLine("709", "480", null)));

    BgVatDocumentDto doc = enrich(move);

    eq("480", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertEquals(1, doc.getPurchaseAmounts().size(), doc.getPurchaseAmounts().toString());
  }

  // --- Внос с отложено начисляване по чл. 167а (ППЗДДС чл. 49а) ---------------

  private static final String MRN = "26BG002000123456R1";

  private Move import167a() {
    Partner supplier = new Partner();
    supplier.setName("Shenzhen Parts Ltd");
    // fullName на Axelor е „код - име“ — в дневника отива само името (BgVatPartyName).
    supplier.setFullName("P0042 - Shenzhen Parts Ltd");
    Move move = new Move();
    move.setPartner(supplier);
    move.setNapDocumentTypeSelect("09");
    move.setReference("#42");
    move.setBgVatProtocolNumber("0000000042");
    move.setOrigin(MRN);
    move.setOriginDate(LocalDate.of(2026, 6, 10));
    move.setDate(LocalDate.of(2026, 6, 20));
    move.setBgVatMoveVatOperation("IMPORT_167A");
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    move.setMoveLineList(
        Arrays.asList(line("4531", "20", null, null, "1"), creditLine("4532", "20", null)));
    return move;
  }

  @Test
  void import167a_protocol_isInTheSalesDiaryToo_withCode03() {
    BgVatDocumentDto doc = enrich(import167a());

    // ал. 4 т. 1: основата в колони 9 и 11, данъкът в 10 и 12 на дневника за продажби.
    eq("100", doc.getSalesAmount(BgVatSalesColumn.K11_BASE_20));
    eq("20", doc.getSalesAmount(BgVatSalesColumn.K12_VAT_20));
    eq("0", doc.getSalesAmount(BgVatSalesColumn.K14_BASE_RC));
    // ал. 5 т. 1: в покупките по кредитния тип.
    eq("100", doc.getPurchaseAmount(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    eq("20", doc.getPurchaseAmount(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    assertEquals("03", doc.getSupplyTypeArt163a());
    // Протоколът по чл. 57 ал. 6 е отделен документ: носи своя номер, НЕ този на ЕАД.
    assertEquals("0000000042", doc.getDocumentNumber());
    assertTrue(doc.getWarnings().isEmpty(), doc.getWarnings().toString());
  }

  @Test
  void import167a_collect_addsTheCustomsDocumentAsASecondRow() {
    List<BgVatDocumentDto> docs = collect(import167a());

    assertEquals(2, docs.size());
    BgVatDocumentDto customs = docs.get(1);
    // ал. 4 т. 2 и ал. 5 т. 2: митническият документ — само колони 1–8, в двата дневника.
    assertTrue(customs.isIdentificationOnly());
    assertEquals("07", customs.getNapDocumentType());
    assertEquals(MRN, customs.getDocumentNumber());
    assertEquals(LocalDate.of(2026, 6, 10), customs.getDocumentDate());
    assertEquals("Shenzhen Parts Ltd", customs.getCounterpartyName());
    // „Вид на стоката“ — по операцията (внос = стоки), и на протокола, и на митническия документ.
    assertEquals("стоки", docs.get(0).getSubject());
    assertEquals("стоки", customs.getSubject());
    assertTrue(customs.getSalesAmounts().isEmpty());
    assertTrue(customs.getPurchaseAmounts().isEmpty());
    assertTrue(customs.getAmounts().isEmpty());
    // к.8а е след колона 8 — остава празна.
    assertEquals(null, customs.getSupplyTypeArt163a());
    assertTrue(new BgVatSalesAdapter().isSalesDocument(customs));
    assertTrue(BgVatDocumentType.allowedInPurchases(customs.getNapDocumentType()));
  }

  /**
   * Чуждестранен доставчик без ДДС номер в ДЧ: „999999999999999“ и в протокола, и в митническия
   * документ — ППЗДДС Прил. № 12, общите изисквания. VIES не получава номер.
   */
  @Test
  void import167a_foreignSupplier_isTheNoIdCode_inBothRows() {
    Move move = import167a();
    Country china = new Country();
    china.setAlpha2Code("CN");
    Address address = new Address();
    address.setCountry(china);
    move.getPartner().setMainAddress(address);
    move.getPartner().setPartnerTypeSelect(1);
    move.getPartner().setTaxNbr("CN91440300MA5ABCDE1X");

    List<BgVatDocumentDto> docs = collect(move);

    assertEquals(2, docs.size());
    for (BgVatDocumentDto doc : docs) {
      assertEquals(BgVatCounterpartyId.NO_ID, doc.getCounterpartyDiaryId());
      assertEquals(null, doc.getCounterpartyVatNumber());
    }
    for (int i = 0; i < docs.size(); i++) {
      assertEquals(
          BgVatCounterpartyId.NO_ID,
          new BgVatSalesAdapter().buildRows(docs, null).get(i).getCounterpartyVatNumber());
      assertEquals(
          BgVatCounterpartyId.NO_ID,
          new BgVatPurchaseAdapter().buildRows(docs, null).get(i).getCounterpartyVatNumber());
    }
    assertFalse(
        new BgVatValidator()
            .validateDocuments(docs, new BgVatExportRequestDto()).stream()
                .anyMatch(f -> "PARTNER_TAX_NUMBER_MISSING".equals(f.getCode())));
  }

  @Test
  void import167a_withoutTheCustomsDocument_isABlockingError() {
    for (boolean noNumber : new boolean[] {true, false}) {
      Move move = import167a();
      if (noNumber) {
        move.setOrigin(" ");
      } else {
        move.setOriginDate(null);
      }

      List<BgVatDocumentDto> docs = collect(move);

      assertEquals(1, docs.size(), "без MRN втори ред няма");
      assertEquals(1, docs.get(0).getErrors().size());
      assertEquals(
          BgVatValidator.CUSTOMS_DOCUMENT_MISSING_CODE, docs.get(0).getErrors().get(0).getCode());
    }
  }

  @Test
  void import167a_anotherSupplyType_isReplacedWith03_andWarns() {
    Move move = import167a();
    move.setBgVatSupplyType("01");

    BgVatDocumentDto doc = enrich(move);

    assertEquals("03", doc.getSupplyTypeArt163a());
    assertTrue(
        doc.getWarnings()
            .contains(
                "Вид на доставката „01“ не е за внос по чл. 167а — приет „03“ (ППЗДДС чл. 49а ал. 4"
                    + " т. 1)"),
        doc.getWarnings().toString());
  }

  // --- Номерът на протокола (ЗДДС чл. 117 ал. 2 т. 1 „номер и дата“) ----------

  private Move vopWithSupplierInvoice(String protocolNumber) {
    Move move = new Move();
    move.setOrigin("INV-778"); // номерът на фактурата на доставчика
    move.setReference("#9");
    move.setBgVatProtocolNumber(protocolNumber);
    move.setBgVatMoveVatOperation("VOP");
    move.setBgVatMoveVatRate(new BigDecimal("0.20"));
    move.setMoveLineList(
        Arrays.asList(line("4531", "20", null, null, "1"), creditLine("4532", "20", null)));
    return move;
  }

  @Test
  void protocol_carriesItsOwnNumber_notTheSupplierInvoiceNumber() {
    BgVatDocumentDto doc = enrich(vopWithSupplierInvoice("0000000007"));

    assertEquals("0000000007", doc.getDocumentNumber());
    assertTrue(doc.getWarnings().isEmpty(), doc.getWarnings().toString());
  }

  @Test
  void protocol_withoutANumber_isABlockingError_andKeepsTheOldOneOnlyToIdentifyIt() {
    BgVatDocumentDto doc = enrich(vopWithSupplierInvoice(null));

    // Прил. № 12: „Номер на документ“ се попълва задължително — без номер протоколът не се подава.
    assertEquals(1, doc.getErrors().size(), doc.getErrors().toString());
    assertEquals(BgVatValidator.PROTOCOL_NUMBER_MISSING_CODE, doc.getErrors().get(0).getCode());
    assertTrue(
        doc.getErrors().get(0).getMessage().startsWith("ДДС протоколът „INV-778“ няма номер"));
    assertTrue(doc.getWarnings().isEmpty(), doc.getWarnings().toString());
    // Досегашният номер остава в реда само за да се познае документът в прегледа.
    assertEquals("INV-778", doc.getDocumentNumber());
    // И портата на PRODAGBI го спира, без да го изброява втори път.
    assertTrue(
        new BgVatValidator()
            .validateSalesForProduction(Arrays.asList(doc), null).stream()
                .anyMatch(f -> BgVatValidator.PROTOCOL_NUMBER_MISSING_CODE.equals(f.getCode())));
    assertTrue(BgVatValidator.isDocumentLevel(BgVatValidator.PROTOCOL_NUMBER_MISSING_CODE));
  }

  @Test
  void protocol_withoutAnyNumber_isTheSameError() {
    Move move = vopWithSupplierInvoice(null);
    move.setOrigin(null);
    move.setReference(null);

    BgVatDocumentDto doc = enrich(move);

    assertEquals(BgVatValidator.PROTOCOL_NUMBER_MISSING_CODE, doc.getErrors().get(0).getCode());
    assertTrue(doc.getErrors().get(0).getMessage().startsWith("ДДС протоколът няма номер"));
  }

  @Test
  void protocol_numberThatIsNotTenDigits_warns() {
    BgVatDocumentDto doc = enrich(vopWithSupplierInvoice("П-0007"));

    assertEquals("П-0007", doc.getDocumentNumber());
    assertEquals(
        Arrays.asList(
            "Номерът на ДДС протокола „П-0007“ не е 10 арабски цифри — проверете поредицата"
                + " „bgVatProtocol“ на дружеството"),
        doc.getWarnings());
  }

  @Test
  void import167a_withoutANumber_isTheMoveReference_notTheMrn() {
    Move move = import167a();
    move.setBgVatProtocolNumber(null);

    BgVatDocumentDto doc = enrich(move);

    assertEquals("#42", doc.getDocumentNumber());
    assertTrue(
        doc.getErrors().stream()
            .anyMatch(
                e ->
                    BgVatValidator.PROTOCOL_NUMBER_MISSING_CODE.equals(e.getCode())
                        && e.getMessage().startsWith("ДДС протоколът „#42“")),
        doc.getErrors().toString());
  }

  @Test
  void art79Protocol_carriesItsNumberToo() {
    Move move = new Move();
    move.setOrigin("ЧЛ79-5");
    move.setBgVatProtocolNumber("0000000011");
    move.setBgVatMoveVatOperation("DEDUCTION_79");
    move.setMoveLineList(
        Arrays.asList(line("4531", "480", null, null, null), creditLine("709", "480", null)));

    BgVatDocumentDto doc = enrich(move);

    // ППЗДДС чл. 66 ал. 4 т. 1: протоколът по чл. 79 ал. 5 и 6 съдържа „номер, дата“.
    assertEquals("0000000011", doc.getDocumentNumber());
  }

  // --- чл. 79 ал. 4 — протокол за корекция ------------------------------------

  @Test
  void art79Correction_chargesK12_withNoTaxBaseAndNoPurchaseSide() {
    // Протоколът е Дт 691 / Кт 4532: начислен данък без насрещна доставка.
    Move move = new Move();
    move.setBgVatMoveVatOperation("CORRECTION_79");
    move.setNapDocumentTypeSelect("09");
    move.setMoveLineList(
        Arrays.asList(line("691", "720", null, null, null), creditLine("4532", "720", null)));

    BgVatDocumentDto doc = enrich(move);

    // к.12 = „начислен данък (20 %), предвиден в закона в други случаи“ (ППЗДДС Прил. № 10).
    eq("720", doc.getSalesAmounts().get(BgVatSalesColumn.K12_VAT_20));
    // Данъчна основа НЕ се пише — доставка няма.
    assertFalse(doc.getSalesAmounts().containsKey(BgVatSalesColumn.K11_BASE_20));
    // И НИКАКЪВ входящ данъчен кредит: инак протоколът си връща сам сумата, която начислява.
    assertTrue(doc.getPurchaseAmounts().isEmpty(), "корекцията не поражда данъчен кредит");
    eq("720", doc.getTotalVat());
  }

  @Test
  void art79Correction_withoutChargedVatAccount_warnsInsteadOfPostingZero() {
    Move move = new Move();
    move.setBgVatMoveVatOperation("CORRECTION_79");
    move.setMoveLineList(Arrays.asList(line("691", "720", null, null, null)));

    BgVatDocumentDto doc = enrich(move);

    assertTrue(doc.getSalesAmounts().isEmpty());
    assertTrue(
        doc.getWarnings().stream().anyMatch(w -> w.contains("чл. 79 ал. 4")),
        "липсващият 4532 ред трябва да се вижда, а не да мине като нула: " + doc.getWarnings());
  }

  @Test
  void art79Deduction_landsInK11_notK14_andNotK13() {
    // Протоколът по ал. 7 е Дт 4531 / Кт приход: приспадане без получена доставка.
    Move move = new Move();
    move.setBgVatMoveVatOperation("DEDUCTION_79");
    move.setNapDocumentTypeSelect("09");
    move.setMoveLineList(
        Arrays.asList(line("4531", "480", null, null, null), creditLine("709", "480", null)));

    BgVatDocumentDto doc = enrich(move);

    eq("480", doc.getPurchaseAmounts().get(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    // к.14 е заета дословно от „Годишна корекция по чл. 73, ал. 8“ — това не е тя.
    assertFalse(doc.getPurchaseAmounts().containsKey(BgVatPurchaseColumn.K14_ANNUAL_ADJUSTMENT));
    // к.13 би пуснала сумата през кл.42 × кл.33 и би я намалила ВТОРИ път след (1 − К₀).
    assertFalse(doc.getPurchaseAmounts().containsKey(BgVatPurchaseColumn.K13_VAT_PARTIAL_CREDIT));
    // Данъчна основа не се пише — получена доставка няма.
    assertFalse(doc.getPurchaseAmounts().containsKey(BgVatPurchaseColumn.K10_BASE_FULL_CREDIT));
    // И нищо не влиза в продажбите: приспадането не е начислен данък.
    assertTrue(doc.getSalesAmounts().isEmpty());
  }

  // --- чл. 79а/79б ал. 4 — ГОДИШНАТА корекция -------------------------------

  @Test
  void art79aAnnual_increase_landsInK11_withPositiveSign() {
    // Увеличение: Дт 4531 / Кт насрещна — кредитът расте.
    Move move = new Move();
    move.setBgVatMoveVatOperation("ANNUAL_79A");
    move.setNapDocumentTypeSelect("09");
    move.setMoveLineList(
        Arrays.asList(line("4531", "200", null, null, null), creditLine("709", "200", null)));

    BgVatDocumentDto doc = enrich(move);

    // ППЗДДС чл. 66 ал. 9: протоколите по ал. 5 и 6 се посочват в колона 11 на дневника за
    // покупките.
    eq("200", doc.getPurchaseAmounts().get(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    // к.14 е озаглавена „Годишна корекция по чл. 73, ал. 8“ — друга материя въпреки думата
    // „годишна“.
    assertFalse(doc.getPurchaseAmounts().containsKey(BgVatPurchaseColumn.K14_ANNUAL_ADJUSTMENT));
    assertTrue(doc.getSalesAmounts().isEmpty());
  }

  @Test
  void art79aAnnual_decrease_landsInSameK11_withNegativeSign() {
    // Намаление: Кт 4531 — кредитът се връща. Колекторът брои Σ(дебит − кредит), тъй че сумата
    // излиза ОТРИЦАТЕЛНА и к.11 носи знака (−) по ал. 4 т. 2.
    Move move = new Move();
    move.setBgVatMoveVatOperation("ANNUAL_79A");
    move.setNapDocumentTypeSelect("09");
    move.setMoveLineList(
        Arrays.asList(line("609", "300", null, null, null), creditLine("4531", "300", null)));

    BgVatDocumentDto doc = enrich(move);

    eq("-300", doc.getPurchaseAmounts().get(BgVatPurchaseColumn.K11_VAT_FULL_CREDIT));
    // ⚠️ Намалението НЕ е начислен данък: в дневника за продажбите не влиза нищо.
    assertTrue(doc.getSalesAmounts().isEmpty());
  }

  @Test
  void art79aAnnual_withoutVatAccountLine_warnsInsteadOfPostingZero() {
    Move move = new Move();
    move.setBgVatMoveVatOperation("ANNUAL_79A");
    move.setMoveLineList(Arrays.asList(line("609", "300", null, null, null)));

    BgVatDocumentDto doc = enrich(move);

    assertTrue(doc.getPurchaseAmounts().isEmpty());
    assertTrue(
        doc.getWarnings().stream().anyMatch(w -> w.contains("чл. 79а/79б ал. 4")),
        "липсващият 4531 ред трябва да се вижда, а не да мине като нула: " + doc.getWarnings());
  }
}
