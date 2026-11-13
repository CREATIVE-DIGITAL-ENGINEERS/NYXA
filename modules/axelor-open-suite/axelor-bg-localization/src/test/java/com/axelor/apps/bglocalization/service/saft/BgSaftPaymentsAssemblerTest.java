package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFiles;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPayment;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPaymentLine;
import com.axelor.apps.bglocalization.service.saft.dto.SaftPaymentSection;
import com.axelor.apps.bglocalization.service.saft.dto.SaftSourceDocuments;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Payments С ЧИСЛА — кои статии са плащания, паричните редове и посоката им, контролните суми,
 * методът и механизмът от ИСТИНСКИЯ мапинг и идентификаторите на контрагентите. Фикстурата е в
 * {@link BgSaftFixtures#payLines()}.
 */
class BgSaftPaymentsAssemblerTest {

  private static final String ALPHA = "10130175000";
  private static final String BETA = "11DE123456789";
  private static final String IVAN = "15P30";

  private static BgSaftPaymentsAssembler.Result assembled() {
    return BgSaftFixtures.assembledPaymentsResult();
  }

  private static List<SaftPayment> payments(SaftPaymentSection section) {
    List<SaftPayment> payments = new ArrayList<>();
    section.getPayments().forEach(payments::add);
    return payments;
  }

  private static SaftPayment payment(BgSaftPaymentsAssembler.Result result, String id) {
    return payments(result.getPayments()).stream()
        .filter(p -> p.getTransactionId().equals(id))
        .findFirst()
        .orElseThrow(() -> new AssertionError("няма плащане " + id));
  }

  /** „сметка индикатор сума клиент/доставчик“ — един ред на плащането. */
  private static String line(SaftPaymentLine l) {
    return l.getAccountId()
        + " "
        + l.getDebitCreditIndicator()
        + " "
        + l.getPaymentLineAmount().getAmount().toPlainString()
        + " "
        + l.getCustomerId()
        + "/"
        + l.getSupplierId();
  }

  private static List<String> lines(SaftPayment p) {
    return p.getLines().stream()
        .map(BgSaftPaymentsAssemblerTest::line)
        .collect(Collectors.toList());
  }

  private static Optional<BgDeclarationFileFinding> finding(
      BgSaftPaymentsAssembler.Result result, String code) {
    return result.getFindings().stream().filter(f -> f.getCode().equals(code)).findFirst();
  }

  private static List<String> codes(BgSaftPaymentsAssembler.Result result) {
    return result.getFindings().stream()
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toList());
  }

  @Test
  @DisplayName(
      "Плащанията по дата; преводът между собствени сметки и статията без паричен ред отпадат")
  void кои_статии_са_плащания() {
    List<String> ids =
        payments(assembled().getPayments()).stream()
            .map(SaftPayment::getTransactionId)
            .collect(Collectors.toList());
    assertEquals(
        List.of(
            "BQ/0003",
            "CSH/0001",
            "OFS/0001",
            "ADV/0001",
            "BQ/0004",
            "BQ/0005",
            "BQ/0001",
            "BQ/0002"),
        ids);
    assertTrue(
        finding(assembled(), "SAFT_PAY_INTERNAL_TRANSFER").get().getMessage().endsWith("CSH/0002"));
    assertTrue(
        finding(assembled(), "SAFT_PAY_NO_MEANS_LINE").get().getMessage().endsWith("BQ/0006"));
  }

  @Test
  @DisplayName("Контролните суми: NumberOfEntries е броят на РЕДОВЕТЕ (9), D 165.00, C 178.00")
  void контролните_суми() {
    SaftPaymentSection section = assembled().getPayments();
    assertEquals(9L, section.getNumberOfEntries(), "SD.P.1 — редове, не плащания (8)");
    assertEquals(new BigDecimal("165.00"), section.getTotalDebit());
    assertEquals(new BigDecimal("178.00"), section.getTotalCredit());
    // И сумите са тези, които файлът показва: сборът на редовете по индикатор.
    BigDecimal debit = BigDecimal.ZERO;
    BigDecimal credit = BigDecimal.ZERO;
    for (SaftPayment p : payments(section)) {
      for (SaftPaymentLine l : p.getLines()) {
        BigDecimal amount = l.getPaymentLineAmount().getAmount();
        if (l.getDebitCreditIndicator().equals("D")) {
          debit = debit.add(amount);
        } else {
          credit = credit.add(amount);
        }
      }
    }
    assertEquals(section.getTotalDebit(), debit);
    assertEquals(section.getTotalCredit(), credit);
  }

  @Test
  @DisplayName("Постъпление от клиент: 503 D 120.00 (трети знак, HALF_UP), Алфа и „0“, метод 03/42")
  void постъплението_от_клиент() {
    SaftPayment p = payment(assembled(), "BQ/0003");
    assertEquals(List.of("503 D 120.00 " + ALPHA + "/0"), lines(p));
    assertEquals("03", p.getPaymentMethod());
    assertEquals("42", p.getPaymentMechanism());
    assertEquals("BQ/0003", p.getPaymentRefNo());
    assertEquals(LocalDate.of(2026, 4, 5), p.getTransactionDate());
    assertEquals(4, p.getPeriod());
    assertEquals(2026, p.getPeriodYear());
    assertEquals("Плащане от Алфа", p.getDescription());
    SaftPaymentLine l = p.getLines().get(0);
    assertEquals("1", l.getLineNumber());
    assertEquals("1", l.getSourceDocumentId(), "документът на статията — номерът на фактурата");
    assertEquals("EUR", l.getPaymentLineAmount().getCurrencyCode());
    assertEquals(new BigDecimal("120.00"), l.getPaymentLineAmount().getCurrencyAmount());
    assertTrue(finding(assembled(), "SAFT_PAY_ROUNDED").get().getMessage().endsWith("BQ/0003"));
  }

  @Test
  @DisplayName("Плащане в брой на доставчик: 501 C 30.00, „0“ и ДДС номерът му, метод 01/10")
  void плащането_в_брой() {
    SaftPayment p = payment(assembled(), "CSH/0001");
    assertEquals(List.of("501 C 30.00 0/" + BETA), lines(p));
    assertEquals("01", p.getPaymentMethod());
    assertEquals("10", p.getPaymentMechanism());
    assertNull(p.getLines().get(0).getSourceDocumentId(), "статия без документ");
  }

  @Test
  @DisplayName(
      "Прихващане без пари: двата реда по сметките на контрагента, всеки със своя ID, 02/97")
  void прихващането() {
    SaftPayment p = payment(assembled(), "OFS/0001");
    assertEquals(List.of("401 D 40.00 0/" + ALPHA, "411 C 40.00 " + ALPHA + "/0"), lines(p));
    assertEquals("02", p.getPaymentMethod());
    assertEquals("97", p.getPaymentMechanism());
  }

  @Test
  @DisplayName("Плащане през подотчетно лице: редът е по 422 (QA с. 64–65), доставчикът, 02/99")
  void подотчетното_лице() {
    SaftPayment p = payment(assembled(), "ADV/0001");
    assertEquals(List.of("422 C 15.00 0/" + BETA), lines(p));
    assertEquals("02", p.getPaymentMethod());
    assertEquals("99", p.getPaymentMechanism());
  }

  @Test
  @DisplayName(
      "Изключен режим (INC_OTH), режим без мапинг и статия без режим → празен метод, ГРЕШКА"
          + " (решение 7): плащането влиза в Payments, тоест не може да е без метод")
  void неописуемият_метод() {
    BgSaftPaymentsAssembler.Result result = assembled();
    for (String id : List.of("BQ/0004", "BQ/0005", "BQ/0002")) {
      SaftPayment p = payment(result, id);
      assertEquals("", p.getPaymentMethod(), id + " — изявление, не измислица");
      assertNull(p.getPaymentMechanism(), id);
    }
    assertEquals(List.of("503 D 5.00 " + ALPHA + "/0"), lines(payment(result, "BQ/0004")));
    assertEquals(List.of("503 C 8.00 0/" + IVAN), lines(payment(result, "BQ/0005")));

    BgDeclarationFileFinding excluded = finding(result, "SAFT_PAY_METHOD_EXCLUDED").get();
    assertEquals(BgDeclarationFileFinding.SEVERITY_ERROR, excluded.getSeverity());
    assertTrue(excluded.getMessage().endsWith("BQ/0004 — режим INC_OTH"), excluded.getMessage());
    BgDeclarationFileFinding unmapped = finding(result, "SAFT_PAY_METHOD_UNMAPPED").get();
    assertEquals(BgDeclarationFileFinding.SEVERITY_ERROR, unmapped.getSeverity());
    assertTrue(unmapped.getMessage().endsWith("BQ/0005 — режим XX_PAY"), unmapped.getMessage());
    BgDeclarationFileFinding noMode = finding(result, "SAFT_PAY_NO_PAYMENT_MODE").get();
    assertEquals(BgDeclarationFileFinding.SEVERITY_ERROR, noMode.getSeverity());
    assertTrue(noMode.getMessage().endsWith("BQ/0002"), noMode.getMessage());
    assertEquals(BgSaftRef.MOVE, noMode.getTargetModel());
  }

  @Test
  @DisplayName(
      "Банковата статия на дневника: двама доставчици → подаващият в двете; без номер на ред")
  void статиите_на_дневника() {
    SaftPayment toSuppliers = payment(assembled(), "BQ/0001");
    assertEquals(
        List.of("503 C 75.00 " + BgSaftFixtures.OWN_ID + "/" + BgSaftFixtures.OWN_ID),
        lines(toSuppliers));
    assertEquals("03", toSuppliers.getPaymentMethod());
    assertTrue(
        finding(assembled(), "SAFT_PAY_SEVERAL_PARTIES").get().getMessage().endsWith("BQ/0001"));

    SaftPayment fee = payment(assembled(), "BQ/0002");
    assertEquals(
        List.of("503 C 10.00 " + BgSaftFixtures.OWN_ID + "/" + BgSaftFixtures.OWN_ID),
        lines(fee),
        "без контрагент — подаващият в двете (SD.P.22/23), като агрегираните продажби");
    assertNull(fee.getLines().get(0).getLineNumber(), "ред без номер — LineNumber не се пише");
    assertEquals("BANK-77", fee.getDescription(), "без описание — документът");
    assertEquals("BANK-77", fee.getLines().get(0).getSourceDocumentId());
  }

  @Test
  @DisplayName(
      "Находките на фикстурата — празният метод е ГРЕШКА (решение 7), останалото предупреждения")
  void находките() {
    BgSaftPaymentsAssembler.Result result = assembled();
    assertEquals(
        List.of(
            "SAFT_PAY_NO_PAYMENT_MODE",
            "SAFT_PAY_METHOD_UNMAPPED",
            "SAFT_PAY_METHOD_EXCLUDED",
            "SAFT_PAY_INTERNAL_TRANSFER",
            "SAFT_PAY_NO_MEANS_LINE",
            "SAFT_PAY_SEVERAL_PARTIES",
            "SAFT_PAY_ROUNDED"),
        codes(result));
    assertEquals(
        List.of("SAFT_PAY_NO_PAYMENT_MODE", "SAFT_PAY_METHOD_UNMAPPED", "SAFT_PAY_METHOD_EXCLUDED"),
        result.getFindings().stream()
            .filter(BgDeclarationFileFinding::isBlocking)
            .map(BgDeclarationFileFinding::getCode)
            .collect(Collectors.toList()));
    // Всяка находка сочи статията.
    assertTrue(
        result.getFindings().stream()
            .filter(f -> !f.getCode().equals("SAFT_PAY_MECHANISM_INVALID"))
            .allMatch(f -> BgSaftRef.MOVE.equals(f.getTargetModel())));
  }

  @Test
  @DisplayName("TransactionID, AccountID и ID-тата са тези на дневника, сглобен от СЪЩИТЕ редове")
  void същото_като_дневника() {
    BgSaftGlEntriesAssembler.Result ledger =
        BgSaftGlEntriesAssembler.assemble(
            BgSaftFixtures.payLines(),
            BgSaftFixtures.payChart(),
            BgSaftFixtures.payMappings(),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.glTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID);
    Map<String, SaftTransaction> transactions = new HashMap<>();
    for (SaftJournal journal : ledger.getGeneralLedger().getJournals()) {
      journal.getTransactions().forEach(t -> transactions.put(t.getTransactionId(), t));
    }
    for (SaftPayment p : payments(assembled().getPayments())) {
      SaftTransaction t = transactions.get(p.getTransactionId());
      assertTrue(t != null, "SD.P.8 сочи транзакция от дневника: " + p.getTransactionId());
      Set<String> accounts = new HashSet<>();
      for (SaftTransactionLine tl : t.getLines()) {
        accounts.add(tl.getAccountId());
      }
      for (SaftPaymentLine l : p.getLines()) {
        assertTrue(accounts.contains(l.getAccountId()), p.getTransactionId() + " " + line(l));
      }
    }
    // Плащане към един контрагент носи ID-то на транзакцията му.
    SaftTransaction receipt = transactions.get("BQ/0003");
    SaftPaymentLine l = payment(assembled(), "BQ/0003").getLines().get(0);
    assertEquals(receipt.getCustomerId(), l.getCustomerId());
    assertEquals(receipt.getSupplierId(), l.getSupplierId());
  }

  @Test
  @DisplayName(
      "CustomerID/SupplierID извън таблиците — плащанията не проверяват; проверката е централна"
          + " (BgSaftReferenceCheck, ГРЕШКА)")
  void контрагентите_в_таблиците() {
    assertFalse(finding(assembled(), "SAFT_PAY_PARTY_NOT_IN_MASTER").isPresent());
    // Таблиците на дневника нямат Алфа като доставчик — прихващането го сочи.
    BgSaftPaymentsAssembler.Result result =
        BgSaftPaymentsAssembler.assemble(
            BgSaftFixtures.payLines(),
            BgSaftFixtures.payChart(),
            BgSaftFixtures.payMappings(),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.seedPaymentMappings(),
            BgNraNomenclatures.paymentMechanisms(),
            BgSaftFixtures.OWN_ID);
    assertFalse(finding(result, "SAFT_PAY_PARTY_NOT_IN_MASTER").isPresent());
    List<BgDeclarationFileFinding> central =
        BgSaftReferenceCheck.check(
            new SaftAuditFile()
                .masterFiles(masterOf(BgSaftFixtures.glParties()))
                .sourceDocuments(new SaftSourceDocuments().payments(result.getPayments())),
            BgSaftFixtures.OWN_ID);
    assertTrue(
        central.stream()
            .anyMatch(
                f ->
                    f.getCode().equals(BgSaftReferenceCheck.PARTY)
                        && f.isBlocking()
                        && f.getMessage().endsWith("Payments SupplierID " + ALPHA)),
        central.toString());
  }

  private static SaftMasterFiles masterOf(BgSaftPartiesAssembler.Result parties) {
    SaftMasterFiles m = new SaftMasterFiles();
    parties.getCustomers().forEach(m::addCustomer);
    parties.getSuppliers().forEach(m::addSupplier);
    return m;
  }

  // ─────────────── отрицателните свидетели ───────────────

  private static BgSaftGlEntriesAssembler.LineRow row(
      long id, int counter, long account, String debit, String credit) {
    return new BgSaftGlEntriesAssembler.LineRow(id)
        .counter(counter)
        .debit(new BigDecimal(debit))
        .credit(new BigDecimal(credit))
        .account(account, null)
        .date(LocalDate.of(2026, 4, 10))
        .periodFrom(LocalDate.of(2026, 4, 1));
  }

  private static List<BgSaftGlEntriesAssembler.LineRow> move(
      long moveId, String reference, String mode, BgSaftGlEntriesAssembler.LineRow... lines) {
    List<BgSaftGlEntriesAssembler.LineRow> rows = new ArrayList<>();
    for (BgSaftGlEntriesAssembler.LineRow line : lines) {
      rows.add(line.moveId(moveId).reference(reference).paymentModeCode(mode));
    }
    return rows;
  }

  private static BgSaftPaymentsAssembler.Result assemble(
      List<BgSaftGlEntriesAssembler.LineRow> lines,
      Map<String, BgSaftPaymentsAssembler.MappingRow> mappings) {
    return BgSaftPaymentsAssembler.assemble(
        lines,
        BgSaftFixtures.payChart(),
        BgSaftFixtures.payMappings(),
        BgSaftFixtures.glPartners(),
        mappings,
        BgNraNomenclatures.paymentMechanisms(),
        BgSaftFixtures.OWN_ID);
  }

  @Test
  @DisplayName("Метод извън Nom_PaymentMethod → ГРЕШКА и празен метод; чужд механизъм → без него")
  void номенклатурата_пази_метода_и_механизма() {
    Map<String, BgSaftPaymentsAssembler.MappingRow> mappings = new HashMap<>();
    mappings.put("BAD_METHOD", new BgSaftPaymentsAssembler.MappingRow("04", "10", false));
    mappings.put("BAD_PAIR", new BgSaftPaymentsAssembler.MappingRow("03", "97", false));
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>();
    lines.addAll(
        move(1L, "M1", "BAD_METHOD", row(1L, 1, 8L, "10", "0"), row(2L, 2, 3L, "0", "10")));
    lines.addAll(move(2L, "M2", "BAD_PAIR", row(3L, 1, 8L, "20", "0"), row(4L, 2, 3L, "0", "20")));
    BgSaftPaymentsAssembler.Result result = assemble(lines, mappings);

    assertEquals("", payment(result, "M1").getPaymentMethod(), "04 го няма — не се пише");
    BgDeclarationFileFinding method = finding(result, "SAFT_PAY_METHOD_INVALID").get();
    assertTrue(method.isBlocking(), "НАП сверява SD.P.10 с номенклатурата");
    assertTrue(method.getMessage().endsWith("BAD_METHOD → „04“"), method.getMessage());

    SaftPayment pair = payment(result, "M2");
    assertEquals("03", pair.getPaymentMethod(), "методът сам по себе си е валиден");
    assertNull(pair.getPaymentMechanism(), "97 е „Прихващане“, не „Безкасово“ — не се пише");
    BgDeclarationFileFinding mechanism = finding(result, "SAFT_PAY_MECHANISM_INVALID").get();
    assertFalse(mechanism.isBlocking());
    assertTrue(mechanism.getMessage().endsWith("BAD_PAIR → 03/97"), mechanism.getMessage());
  }

  @Test
  @DisplayName(
      "Прихващане с доплащане в една статия: само паричният ред, без „401 D 150“ (QA с. 62)")
  void прихващането_с_доплащане() {
    BgSaftPaymentsAssembler.Result result =
        assemble(
            move(
                1L,
                "M1",
                "OUT_OFFSET",
                row(1L, 1, 7L, "150", "0").partnerId(10L),
                row(2L, 2, 2L, "0", "100").partnerId(10L),
                row(3L, 3, 8L, "0", "50")),
            BgSaftFixtures.seedPaymentMappings());
    assertEquals(List.of("503 C 50.00 " + ALPHA + "/" + ALPHA), lines(payment(result, "M1")));
    assertTrue(finding(result, "SAFT_PAY_MIXED_SETTLEMENT").get().getMessage().endsWith("M1"));
  }

  @Test
  @DisplayName(
      "Без плащане в месеца → нулевата структура по QA IV.2.6 (с. 97–98), не ГРЕШКА (решение 3)")
  void без_плащания() {
    BgSaftPaymentsAssembler.Result result =
        assemble(
            move(1L, "M1", null, row(1L, 1, 12L, "5", "0"), row(2L, 2, 8L, "0", "5")),
            BgSaftFixtures.seedPaymentMappings());
    assertEquals(0L, result.getPayments().getNumberOfEntries());
    assertEquals(new BigDecimal("0.00"), result.getPayments().getTotalDebit());
    BgDeclarationFileFinding empty = finding(result, "SAFT_PAY_EMPTY").get();
    assertFalse(empty.isBlocking());
    List<SaftPayment> zero = payments(result.getPayments());
    assertEquals(1, zero.size());
    SaftPayment p = zero.get(0);
    assertEquals("0", p.getPaymentRefNo());
    assertEquals(0, p.getPeriod());
    assertEquals(0, p.getPeriodYear());
    assertEquals("0", p.getTransactionId());
    assertEquals(LocalDate.of(1900, 1, 1), p.getTransactionDate());
    assertEquals("0", p.getPaymentMethod(), "вид „0“ в нулевата структура — по образеца на QA");
    assertEquals("0", p.getDescription());
    assertEquals(List.of("998 D 0.00 0/0"), lines(p));
  }

  @Test
  @DisplayName("Сторно с разменени страни излиза със знака на записването: 503 C, не отрицателно D")
  void сторното() {
    BgSaftPaymentsAssembler.Result result =
        assemble(
            move(
                1L,
                "M1",
                "INC_WT",
                row(1L, 1, 8L, "0", "120"),
                row(2L, 2, 2L, "120", "0").partnerId(10L)),
            BgSaftFixtures.seedPaymentMappings());
    assertEquals(List.of("503 C 120.00 " + ALPHA + "/0"), lines(payment(result, "M1")));
  }
}
