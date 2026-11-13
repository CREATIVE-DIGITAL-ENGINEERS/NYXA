package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGeneralLedger;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFiles;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxInformation;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GeneralLedgerEntries С ЧИСЛА — журнали, статии, редове, контролни суми, TaxInformation и
 * идентификаторите на контрагентите. Фикстурата е в {@link BgSaftFixtures#glLines()}.
 */
class BgSaftGlEntriesAssemblerTest {

  private static BgSaftGlEntriesAssembler.Result assembled() {
    return BgSaftFixtures.assembledGlEntriesResult();
  }

  private static List<SaftJournal> journals(SaftGeneralLedger ledger) {
    List<SaftJournal> journals = new ArrayList<>();
    ledger.getJournals().forEach(journals::add);
    return journals;
  }

  private static List<SaftTransaction> transactions(SaftJournal journal) {
    List<SaftTransaction> transactions = new ArrayList<>();
    journal.getTransactions().forEach(transactions::add);
    return transactions;
  }

  private static List<SaftTransactionLine> lines(SaftTransaction transaction) {
    List<SaftTransactionLine> lines = new ArrayList<>();
    transaction.getLines().forEach(lines::add);
    return lines;
  }

  private static SaftTransaction transaction(String id) {
    return transaction(assembled(), id);
  }

  private static SaftTransaction transaction(BgSaftGlEntriesAssembler.Result result, String id) {
    for (SaftJournal journal : journals(result.getGeneralLedger())) {
      for (SaftTransaction t : transactions(journal)) {
        if (t.getTransactionId().equals(id)) {
          return t;
        }
      }
    }
    throw new AssertionError("няма транзакция " + id);
  }

  private static Optional<BgDeclarationFileFinding> finding(
      BgSaftGlEntriesAssembler.Result result, String code) {
    return result.getFindings().stream().filter(f -> f.getCode().equals(code)).findFirst();
  }

  private static String tax(SaftTaxInformation t) {
    return t.getTaxType() + "/" + t.getTaxCode() + "/" + t.getTaxAmount().getAmount();
  }

  private static String amount(SaftTransactionLine l) {
    return (l.getDebit() ? "D " : "C ") + l.getAmount().getAmount().toPlainString();
  }

  @Test
  @DisplayName("Журналите по код, статиите по дата и номер, редовете по номер в статията")
  void структурата() {
    SaftGeneralLedger ledger = assembled().getGeneralLedger();
    List<SaftJournal> journals = journals(ledger);
    assertEquals(
        List.of("ACH", "BQ", "VEN"),
        journals.stream().map(SaftJournal::getJournalId).collect(Collectors.toList()));
    assertEquals("Покупки", journals.get(0).getDescription());
    assertNull(journals.get(0).getType(), "GL.7 не се подава");
    assertEquals(
        List.of("BQ/0001", "BQ/0002"),
        transactions(journals.get(1)).stream()
            .map(SaftTransaction::getTransactionId)
            .collect(Collectors.toList()));

    SaftTransaction sale = transaction("VEN/0001");
    assertEquals(
        List.of("1", "2", "3"),
        lines(sale).stream().map(SaftTransactionLine::getRecordId).collect(Collectors.toList()));
    assertEquals(
        List.of("411", "702", "4532"),
        lines(sale).stream().map(SaftTransactionLine::getAccountId).collect(Collectors.toList()));
    // 4111 няма свой ред в мапинга — AccountID е на родителя, TaxpayerAccountID е нашият код.
    assertEquals("4111", lines(sale).get(0).getTaxpayerAccountId());
    assertEquals(
        List.of("D 120.00", "C 100.00", "C 20.00"),
        lines(sale).stream()
            .map(BgSaftGlEntriesAssemblerTest::amount)
            .collect(Collectors.toList()));
    assertEquals("EUR", lines(sale).get(0).getAmount().getCurrencyCode());
    assertEquals(new BigDecimal("120.00"), lines(sale).get(0).getAmount().getCurrencyAmount());
  }

  @Test
  @DisplayName("Контролните суми: броят на статиите и сборовете на дебита и кредита")
  void контролните_суми() {
    SaftGeneralLedger ledger = assembled().getGeneralLedger();
    // Статията само по задбалансови сметки отпада — остават четири.
    assertEquals(4L, ledger.getNumberOfEntries());
    assertEquals(new BigDecimal("265.00"), ledger.getTotalDebit());
    assertEquals(new BigDecimal("265.00"), ledger.getTotalCredit());
    long transactions = journals(ledger).stream().mapToLong(j -> transactions(j).size()).sum();
    assertEquals(ledger.getNumberOfEntries(), transactions);
    assertEquals(
        Map.of(
            "302", new BigDecimal("60.00"),
            "401", new BigDecimal("15.00"),
            "4111", new BigDecimal("120.00"),
            "4531", new BigDecimal("10.00"),
            "4532", new BigDecimal("-20.00"),
            "503", new BigDecimal("-85.00"),
            "702", new BigDecimal("-100.00")),
        assembled().getNetByTaxpayerAccount());
  }

  @Test
  @DisplayName("Датите: документът, въвеждането, отчетната дата и периодът — по QA")
  void датите() {
    SaftTransaction sale = transaction("VEN/0001");
    assertEquals(
        LocalDate.of(2026, 4, 9), sale.getTransactionDate(), "GL.12 = датата на документа");
    assertEquals(LocalDate.of(2026, 4, 11), sale.getSystemEntryDate(), "GL.17 = въвеждането");
    assertEquals(LocalDate.of(2026, 4, 10), sale.getGlPostingDate(), "GL.18 = Move.date");
    assertEquals(4, sale.getPeriod());
    assertEquals(2026, sale.getPeriodYear());
    assertEquals("Продажба по ф-ра 1", sale.getDescription());
    // Без дата на документа — датата на статията; без описание — документът.
    SaftTransaction fee = transaction("BQ/0002");
    assertEquals(LocalDate.of(2026, 4, 28), fee.getTransactionDate());
    assertEquals("BANK-77", fee.getDescription());
    // Ред без описание наследява описанието на статията.
    assertEquals("BANK-77", lines(fee).get(0).getDescription());
    assertEquals("Вземане", lines(transaction("VEN/0001")).get(0).getDescription());
  }

  @Test
  @DisplayName("TaxInformation: фиксиран код, динамичен с подразбиране, без данък 000/000000")
  void данъчната_информация() {
    BgSaftGlEntriesAssembler.Result result = assembled();
    List<SaftTransactionLine> sale = lines(transaction("VEN/0001"));
    // Вземането е без данък — 000/000000, без находка.
    assertEquals(List.of("000/000000/0.00"), taxes(sale.get(0)));
    assertEquals(List.of("100/100211/0.00"), taxes(sale.get(1)));
    assertEquals(List.of("100/100211/0.00"), taxes(sale.get(2)));

    List<SaftTransactionLine> purchase = lines(transaction("ACH/0001"));
    assertEquals(List.of("100/100331/0.00"), taxes(purchase.get(0)), "подразбиращият се код");
    assertEquals(List.of("100/100331/0.00"), taxes(purchase.get(1)));
    assertEquals(
        "Данък, чиято клетка се смята на ред — подаден е подразбиращият се код от мапинга;"
            + " истинският се изчислява с документите (G5): BG_SRP_20 → 100331 (2 реда)",
        finding(result, "SAFT_GLE_TAX_DYNAMIC_DEFAULTED").get().getMessage());

    // „Да се потвърди“ — НИКОГА празен TaxCode (QA IV.2.3): 000/000000 и ГРЕШКА към статията;
    // без мапинг — 000/000000 с предупреждение.
    List<SaftTransactionLine> fee = lines(transaction("BQ/0002"));
    assertEquals(List.of("000/000000/0.00", "000/000000/0.00"), taxes(fee.get(0)));
    BgDeclarationFileFinding toConfirm = finding(result, "SAFT_GLE_TAX_TO_CONFIRM").get();
    assertTrue(toConfirm.isBlocking());
    assertTrue(toConfirm.getMessage().endsWith("BQ/0002 — BG_ZRS"), toConfirm.getMessage());
    assertEquals(BgSaftRef.MOVE, toConfirm.getTargetModel());
    assertTrue(finding(result, "SAFT_GLE_TAX_UNMAPPED").get().getMessage().endsWith("XX_TAX"));
    assertTrue(
        finding(result, "SAFT_GLE_TAX_AMOUNT_DEFERRED")
            .get()
            .getMessage()
            .contains("на 4 данъчни"));

    // На ВСЕКИ ред има поне един TaxInformation.
    for (SaftJournal journal : journals(result.getGeneralLedger())) {
      for (SaftTransaction t : transactions(journal)) {
        for (SaftTransactionLine l : lines(t)) {
          assertFalse(
              l.getTaxInformation().isEmpty(), t.getTransactionId() + "/" + l.getRecordId());
        }
      }
    }
  }

  @Test
  @DisplayName("CustomerID/SupplierID: контрагентът на реда, иначе „0“, а без контрагент — 10+ЕИК")
  void контрагентите() {
    SaftTransaction sale = transaction("VEN/0001");
    assertEquals("10130175000", sale.getCustomerId(), "тип 10 — ЕИК");
    assertEquals("0", sale.getSupplierId());
    for (SaftTransactionLine l : lines(sale)) {
      assertEquals("10130175000", l.getCustomerId(), "и редовете без контрагент");
      assertEquals("0", l.getSupplierId());
    }

    SaftTransaction purchase = transaction("ACH/0001");
    assertEquals("0", purchase.getCustomerId());
    assertEquals("11DE123456789", purchase.getSupplierId(), "тип 11 — ДДС номер в ЕС");

    // Плащане към двама доставчици: статията носи подаващия, редовете 401 — своя доставчик.
    SaftTransaction payment = transaction("BQ/0001");
    assertEquals(BgSaftFixtures.OWN_ID, payment.getCustomerId());
    assertEquals(BgSaftFixtures.OWN_ID, payment.getSupplierId());
    List<SaftTransactionLine> lines = lines(payment);
    assertEquals(
        List.of(
            "503 " + BgSaftFixtures.OWN_ID + "/" + BgSaftFixtures.OWN_ID,
            "401 0/11DE123456789",
            "401 0/15P30"),
        lines.stream()
            .map(l -> l.getAccountId() + " " + l.getCustomerId() + "/" + l.getSupplierId())
            .collect(Collectors.toList()));

    // Статия без контрагент — подаващият и в двете полета, не „0“.
    SaftTransaction fee = transaction("BQ/0002");
    assertEquals(BgSaftFixtures.OWN_ID, fee.getCustomerId());
    assertEquals(BgSaftFixtures.OWN_ID, fee.getSupplierId());
    assertFalse(finding(assembled(), "SAFT_GLE_PARTY_NOT_IN_MASTER").isPresent());
  }

  @Test
  @DisplayName("Отрицателен свидетел: изключена сметка с движение — редът отпада, с предупреждение")
  void изключената_сметка() {
    BgSaftGlEntriesAssembler.Result result = assembled();
    // По една находка на сметка, с указател към сметката (G6a).
    List<BgDeclarationFileFinding> excluded =
        result.getFindings().stream()
            .filter(f -> f.getCode().equals("SAFT_GLE_EXCLUDED_ACCOUNT_LINES"))
            .collect(Collectors.toList());
    assertEquals(2, excluded.size());
    assertFalse(excluded.get(0).isBlocking());
    assertTrue(excluded.get(0).getMessage().contains("1 статии без нито един останал ред"));
    assertTrue(excluded.get(0).getMessage().endsWith("9110 (1 реда)"));
    assertTrue(excluded.get(1).getMessage().endsWith("9190 (1 реда)"));
    assertEquals(BgSaftRef.ACCOUNT, excluded.get(0).getTargetModel());
    assertFalse(result.getNetByTaxpayerAccount().containsKey("9110"));
    // Статията по задбалансовите 9110/9190 отпада цялата — в нея няма какво да се балансира.
    assertFalse(finding(result, "SAFT_GLE_UNBALANCED_EXCLUDED").isPresent());
  }

  @Test
  @DisplayName("Отрицателен свидетел: трети знак — закръглено HALF_UP, с предупреждение")
  void закръглянето() {
    BgDeclarationFileFinding rounded = finding(assembled(), "SAFT_GLE_ROUNDED").get();
    assertTrue(rounded.getMessage().endsWith(": ACH/0001"), rounded.getMessage());
    assertEquals("D 50.00", amount(lines(transaction("ACH/0001")).get(0)));
  }

  @Test
  @DisplayName("Ред без номер в статията — RecordID е id на реда, с предупреждение")
  void ред_без_номер() {
    assertEquals("9004", lines(transaction("BQ/0002")).get(1).getRecordId());
    assertTrue(
        finding(assembled(), "SAFT_GLE_RECORD_ID_FROM_ID").get().getMessage().endsWith("BQ/0002"));
  }

  @Test
  @DisplayName(
      "Контрагент от дневника, когото го няма в Customers/Suppliers — дневникът не проверява,"
          + " проверката е централна (BgSaftReferenceCheck)")
  void контрагент_извън_таблиците() {
    BgSaftGlEntriesAssembler.Result result =
        BgSaftGlEntriesAssembler.assemble(
            BgSaftFixtures.glLines(),
            BgSaftFixtures.glChart(),
            BgSaftFixtures.glMappings(),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.glTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID);
    assertFalse(finding(result, "SAFT_GLE_PARTY_NOT_IN_MASTER").isPresent());
    assertEquals(
        List.of("10130175000", "11DE123456789", "15P30"),
        BgSaftReferenceCheck.check(
                new SaftAuditFile()
                    .masterFiles(new SaftMasterFiles())
                    .generalLedger(result.getGeneralLedger()),
                BgSaftFixtures.OWN_ID)
            .stream()
            .filter(f -> f.getCode().equals("SAFT_REF_PARTY_NOT_IN_MASTER"))
            .map(f -> f.getMessage().replaceAll(".* ", ""))
            .distinct()
            .sorted()
            .collect(Collectors.toList()));
  }

  @Test
  @DisplayName("Сметка без код по НАП — празен AccountID и ГРЕШКА; дневник без статии — ГРЕШКА")
  void без_код_и_празно() {
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>(BgSaftFixtures.glLines());
    lines.add(
        new BgSaftGlEntriesAssembler.LineRow(9100L)
            .counter(3)
            .debit(new BigDecimal("1"))
            .account(99L, "6999")
            .moveId(504L)
            .reference("BQ/0002")
            .date(LocalDate.of(2026, 4, 28))
            .journal(3L, "BQ", "Банка"));
    BgSaftGlEntriesAssembler.Result result =
        BgSaftGlEntriesAssembler.assemble(
            lines,
            BgSaftFixtures.glChart(),
            BgSaftFixtures.glMappings(),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.glTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID);
    BgDeclarationFileFinding unmapped = finding(result, "SAFT_GLE_UNMAPPED").get();
    assertTrue(unmapped.isBlocking());
    assertTrue(unmapped.getMessage().endsWith("6999 (извън сметкоплана на дружеството)"));
    // Добавеният ред разбалансира статията — ГРЕШКА към нея.
    BgDeclarationFileFinding unbalanced = finding(result, "SAFT_GLE_UNBALANCED").get();
    assertTrue(unbalanced.isBlocking());
    assertTrue(
        unbalanced.getMessage().endsWith("BQ/0002 (Дт − Кт = 1.00)"), unbalanced.getMessage());
    assertEquals(504L, unbalanced.getTargetId());

    BgSaftGlEntriesAssembler.Result empty =
        BgSaftGlEntriesAssembler.assemble(
            List.of(),
            BgSaftFixtures.glChart(),
            BgSaftFixtures.glMappings(),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.glTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID);
    // Решение 3 (14.10.2026): нулевата структура по QA IV.2.6, не ГРЕШКА.
    assertFalse(finding(empty, "SAFT_GLE_EMPTY").get().isBlocking());
    assertEquals(0L, empty.getGeneralLedger().getNumberOfEntries());
    assertEquals(new BigDecimal("0.00"), empty.getGeneralLedger().getTotalDebit());
    SaftTransaction zero = transactions(journals(empty.getGeneralLedger()).get(0)).get(0);
    assertEquals("1", zero.getTransactionId());
    assertEquals("0", zero.getCustomerId());
    assertEquals(LocalDate.of(1900, 1, 1), zero.getGlPostingDate());
    SaftTransactionLine zeroLine = lines(zero).get(0);
    assertEquals("998", zeroLine.getAccountId());
    assertEquals(List.of("000/000000/0.00"), taxes(zeroLine));
  }

  @Test
  @DisplayName("Нулевият дневник носи периода на файла (QA с. 98: Period 9, PeriodYear 2025)")
  void нулевият_дневник_носи_периода() {
    BgSaftGlEntriesAssembler.Result empty =
        BgSaftGlEntriesAssembler.assemble(
            List.of(),
            BgSaftFixtures.glChart(),
            BgSaftFixtures.glMappings(),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.glTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID,
            Map.of(),
            java.time.YearMonth.of(2026, 4));
    SaftTransaction zero = transactions(journals(empty.getGeneralLedger()).get(0)).get(0);
    assertEquals(4, zero.getPeriod());
    assertEquals(2026, zero.getPeriodYear());
  }

  @Test
  @DisplayName("Статия с период от друг месец — GL.10/11 по периода ѝ, с предупреждение (QA с. 46)")
  void статия_с_друг_период() {
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>(BgSaftFixtures.glLines());
    BgSaftGlEntriesAssembler.Result result =
        BgSaftGlEntriesAssembler.assemble(
            lines,
            BgSaftFixtures.glChart(),
            BgSaftFixtures.glMappings(),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.glTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID,
            Map.of(),
            java.time.YearMonth.of(2026, 3));
    BgDeclarationFileFinding other = finding(result, "SAFT_GLE_OTHER_PERIOD").get();
    assertFalse(other.isBlocking());
    assertTrue(other.getMessage().contains("(период 2026-04)"), other.getMessage());
    assertEquals(BgSaftRef.MOVE, other.getTargetModel());
  }

  @Test
  @DisplayName(
      "Данък, чийто код чака НАП (злато), в дневника — ГРЕШКА към статията и 000/000000, не тихо"
          + " 100219 (решение 6)")
  void чака_нап_в_дневника() {
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>(BgSaftFixtures.glLines());
    lines.add(
        new BgSaftGlEntriesAssembler.LineRow(9200L)
            .counter(1)
            .debit(new BigDecimal("0"))
            .account(3L, "702")
            .moveId(505L)
            .reference("VEN/0009")
            .date(LocalDate.of(2026, 4, 29))
            .periodFrom(LocalDate.of(2026, 4, 1))
            .journal(1L, "VEN", "Продажби")
            .taxCodes(List.of("BG_GOLD_ART163G")));
    BgSaftGlEntriesAssembler.Result result =
        BgSaftGlEntriesAssembler.assemble(
            lines,
            BgSaftFixtures.glChart(),
            BgSaftFixtures.glMappings(),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.glTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID);
    BgDeclarationFileFinding pending = finding(result, "SAFT_GLE_NAP_PENDING").get();
    assertTrue(pending.isBlocking());
    // Златото не е в запитването от 05.10 — без датата (решение от 15.10.2026).
    assertTrue(pending.getMessage().endsWith(BgSaftNapPending.OPINION), pending.getMessage());
    assertEquals(505L, pending.getTargetId());
    assertEquals(List.of("000/000000/0.00"), taxes(lines(transaction(result, "VEN/0009")).get(0)));
  }

  @Test
  @DisplayName("Подаващият е „10“ + ЕИК от Header-а")
  void подаващият() {
    assertEquals(
        "10123456789",
        BgSaftAuditFileService.ownId(
            new com.axelor.apps.bglocalization.service.saft.dto.SaftCompanyHeader()
                .registrationNumber(" 123456789 ")));
  }

  private static List<String> taxes(SaftTransactionLine line) {
    return line.getTaxInformation().stream()
        .map(BgSaftGlEntriesAssemblerTest::tax)
        .collect(Collectors.toList());
  }
}
