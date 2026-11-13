package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGlAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НАЧАЛНО САЛДО (G2a) + ОБОРОТ В ДНЕВНИКА (G3) = КРАЙНО САЛДО (G2a) — ЗА ВСЯКА СМЕТКА.
 *
 * <p><b>⚠️ КАКВО ДОКАЗВА.</b> Статиите се избират с ИСТИНСКИЯ текст на заявките — салдата с {@link
 * BgSaftMoveScope#OPENING_BY_ACCOUNT} и {@link BgSaftMoveScope#TURNOVER_BY_ACCOUNT}, дневникът с
 * {@link BgSaftGlEntriesReader#LINE_IDS} — оценен от {@link BgSaftJpqlWhere} върху статии в
 * паметта, а сметките и дневникът се сглобяват от истинските assembler-и. Ако G3 избира статиите с
 * друг предикат, равенството пада за сметката, която разликата засяга.
 *
 * <p><b>⚠️ ФИКСТУРАТА Е ПЪЛНА С КАПАНИ</b>, всеки от които един грешен предикат би хванал:
 * откриваща статия ВЪТРЕ в периода (начално салдо, не оборот), статии с NULL и с 0 функционален
 * произход, DAYBOOK, игнорирана, на друго дружество, от предходен месец на годината, от предишна
 * година, след периода, и последният ден на периода.
 *
 * <p><b>⚠️ ОТРИЦАТЕЛНИТЕ КОНТРОЛИ СА ЗАДЪЛЖИТЕЛНИ.</b> Същата реконсилиация с два правдоподобни
 * грешни предиката трябва да падне — иначе зеленото би значило само, че сравнението не вижда нищо.
 */
class BgSaftGlReconciliationTest {

  private static final long COMPANY = BgSaftFixtures.COMPANY_ID;
  private static final long YEAR = BgSaftFixtures.YEAR_ID;
  private static final String OWN_ID = "10123456789";

  // ─────────────── фикстурата ───────────────

  /** Статия — само полетата, по които заявките филтрират, плюс заглавието на транзакцията. */
  private static final class Mv {
    private final long id;
    private long company = COMPANY;
    private int status = MoveRepository.STATUS_ACCOUNTED;
    private Boolean ignore;
    private final LocalDate date;
    private Integer origin;
    private long year = YEAR;
    private final String journal;

    private Mv(long id, LocalDate date, String journal) {
      this.id = id;
      this.date = date;
      this.journal = journal;
    }

    private Mv ignore(Boolean value) {
      this.ignore = value;
      return this;
    }

    private Mv origin(Integer value) {
      this.origin = value;
      return this;
    }

    private Mv status(int value) {
      this.status = value;
      return this;
    }

    private Mv company(long value) {
      this.company = value;
      return this;
    }

    private Mv year(long value) {
      this.year = value;
      return this;
    }
  }

  /** Ред на статия. */
  private static final class Ln {
    private static long seq = 1000;
    private final long id = ++seq;
    private final Mv move;
    private final long account;
    private final Long partner;
    private final BigDecimal debit;
    private final BigDecimal credit;

    private Ln(Mv move, long account, Long partner, String debit, String credit) {
      this.move = move;
      this.account = account;
      this.partner = partner;
      this.debit = new BigDecimal(debit);
      this.credit = new BigDecimal(credit);
    }

    /** Стойността на път от заявката — непознат път гърми, вместо да мине тихо. */
    private Object path(String path) {
      switch (path) {
        case "self.move.company.id":
          return move.company;
        case "self.move.statusSelect":
          return move.status;
        case "self.move.ignoreInAccountingOk":
          return move.ignore;
        case "self.move.date":
          return move.date;
        case "self.move.functionalOriginSelect":
          return move.origin;
        case "self.move.period.year.id":
          return move.year;
        case "self.account.id":
          return account;
        default:
          throw new IllegalStateException("фикстурата не познава пътя " + path);
      }
    }

    private BgSaftGlEntriesAssembler.LineRow row(int counter) {
      return new BgSaftGlEntriesAssembler.LineRow(id)
          .counter(counter)
          .debit(debit)
          .credit(credit)
          .account(account, CODES.get(account))
          .partnerId(partner)
          .moveId(move.id)
          .reference(move.journal + "/" + move.id)
          .moveDescription("Статия " + move.id)
          .date(move.date)
          .createdOn(move.date)
          .periodFrom(move.date.withDayOfMonth(1))
          .journal((long) move.journal.hashCode(), move.journal, move.journal);
    }
  }

  private static final Map<Long, String> CODES = new LinkedHashMap<>();

  static {
    CODES.put(1L, "411");
    CODES.put(2L, "4111");
    CODES.put(3L, "702");
    CODES.put(4L, "4532");
    CODES.put(5L, "302");
    CODES.put(6L, "4531");
    CODES.put(7L, "401");
    CODES.put(8L, "503");
    CODES.put(9L, "101");
  }

  private static LocalDate d(int month, int day) {
    return LocalDate.of(2026, month, day);
  }

  private static final List<Ln> LINES = new ArrayList<>();

  private static void move(Mv move, Ln... lines) {
    Collections.addAll(LINES, lines);
  }

  static {
    // Продажба на клиент, NULL произход.
    Mv sale = new Mv(101, d(4, 10), "VEN");
    move(
        sale,
        new Ln(sale, 2, 10L, "120", "0"),
        new Ln(sale, 3, null, "0", "100"),
        new Ln(sale, 4, null, "0", "20"));
    // Покупка от доставчик, ignore=false и произход 0.
    Mv purchase = new Mv(102, d(4, 12), "ACH").ignore(false).origin(0);
    move(
        purchase,
        new Ln(purchase, 5, null, "50", "0"),
        new Ln(purchase, 6, null, "10", "0"),
        new Ln(purchase, 7, 20L, "0", "60"));
    // Откриваща статия в първия ден на годината — начално салдо.
    Mv opening = new Mv(103, d(1, 1), "OD").origin(MoveRepository.FUNCTIONAL_ORIGIN_OPENING);
    move(opening, new Ln(opening, 2, 10L, "80", "0"), new Ln(opening, 9, null, "0", "80"));
    // ⚠️ Откриваща статия ВЪТРЕ в периода — начално салдо, НЕ оборот.
    Mv lateOpening = new Mv(104, d(4, 1), "OD").origin(MoveRepository.FUNCTIONAL_ORIGIN_OPENING);
    move(
        lateOpening,
        new Ln(lateOpening, 8, null, "30", "0"),
        new Ln(lateOpening, 9, null, "0", "30"));
    // Предходен месец на същата година — начално салдо.
    Mv march = new Mv(105, d(3, 15), "BQ");
    move(march, new Ln(march, 8, null, "200", "0"), new Ln(march, 2, 10L, "0", "200"));
    // Нито в салдата, нито в дневника.
    Mv daybook = new Mv(106, d(4, 20), "OD").status(MoveRepository.STATUS_DAYBOOK);
    move(daybook, new Ln(daybook, 8, null, "999", "0"), new Ln(daybook, 9, null, "0", "999"));
    Mv ignored = new Mv(107, d(4, 21), "OD").ignore(true);
    move(ignored, new Ln(ignored, 8, null, "777", "0"), new Ln(ignored, 9, null, "0", "777"));
    Mv foreign = new Mv(108, d(4, 22), "OD").company(8L);
    move(foreign, new Ln(foreign, 8, null, "555", "0"), new Ln(foreign, 9, null, "0", "555"));
    Mv may = new Mv(110, d(5, 1), "OD");
    move(may, new Ln(may, 8, null, "444", "0"), new Ln(may, 9, null, "0", "444"));
    Mv lastYear = new Mv(111, LocalDate.of(2025, 12, 15), "OD").year(25L);
    move(lastYear, new Ln(lastYear, 8, null, "333", "0"), new Ln(lastYear, 9, null, "0", "333"));
    // Последният ден на периода — оборот.
    Mv lastDay = new Mv(109, d(4, 30), "BQ");
    move(lastDay, new Ln(lastDay, 8, null, "120", "0"), new Ln(lastDay, 2, 10L, "0", "120"));
    // Друг (неоткриващ) функционален произход — оборот.
    Mv other = new Mv(112, d(4, 25), "OD").origin(2);
    move(other, new Ln(other, 7, 30L, "45", "0"), new Ln(other, 8, null, "0", "45"));
    // Плащане към двама доставчици с една статия.
    Mv payment = new Mv(113, d(4, 26), "BQ");
    move(
        payment,
        new Ln(payment, 8, null, "0", "75"),
        new Ln(payment, 7, 20L, "25", "0"),
        new Ln(payment, 7, 30L, "50", "0"));
  }

  private static List<BgSaftGlAccountsAssembler.AccountRow> chart() {
    return List.of(
        new BgSaftGlAccountsAssembler.AccountRow(1L, "411", "Клиенти", "1", null),
        new BgSaftGlAccountsAssembler.AccountRow(2L, "4111", "Клиенти в страната", "1", 1L),
        new BgSaftGlAccountsAssembler.AccountRow(3L, "702", "Приходи от продажби", "2", null),
        new BgSaftGlAccountsAssembler.AccountRow(4L, "4532", "ДДС за продажбите", "2", null),
        new BgSaftGlAccountsAssembler.AccountRow(5L, "302", "Материали", "1", null),
        new BgSaftGlAccountsAssembler.AccountRow(6L, "4531", "ДДС за покупките", "1", null),
        new BgSaftGlAccountsAssembler.AccountRow(7L, "401", "Доставчици", "2", null),
        new BgSaftGlAccountsAssembler.AccountRow(8L, "503", "Разплащателна сметка", "1", null),
        new BgSaftGlAccountsAssembler.AccountRow(9L, "101", "Основен капитал", "2", null));
  }

  private static Map<String, BgSaftGlAccountsAssembler.MappingRow> mappings() {
    Map<String, BgSaftGlAccountsAssembler.MappingRow> mappings = new LinkedHashMap<>();
    for (String code : List.of("411", "702", "4532", "302", "4531", "401", "503", "101")) {
      mappings.put(code, new BgSaftGlAccountsAssembler.MappingRow(code, false));
    }
    return mappings;
  }

  private static Map<Long, BgSaftPartiesAssembler.PartyRow> partners() {
    return Map.of(
        10L,
        new BgSaftPartiesAssembler.PartyRow(10L)
            .partnerSeq("P10")
            .partnerTypeSelect(1)
            .name("Алфа ООД")
            .registrationCode("130175000")
            .city("София")
            .country("BG"),
        20L,
        new BgSaftPartiesAssembler.PartyRow(20L)
            .partnerSeq("P20")
            .partnerTypeSelect(1)
            .name("Beta GmbH")
            .taxNbr("DE123456789")
            .city("Berlin")
            .country("DE"),
        30L,
        new BgSaftPartiesAssembler.PartyRow(30L)
            .partnerSeq("P30")
            .partnerTypeSelect(2)
            .firstName("Иван")
            .name("Иванов")
            .city("Пловдив")
            .country("BG"));
  }

  // ─────────────── двете части на файла ───────────────

  /** Редовете, които заявката избира — оценени по текста ѝ. */
  private static List<Ln> select(String jpql, Map<String, Object> extra) {
    BgSaftMoveScope scope = BgSaftMoveScope.of(BgSaftFixtures.april());
    Map<String, Object> parameters = scope.parameters(jpql, extra);
    String where = BgSaftJpqlWhere.where(jpql);
    return LINES.stream()
        .filter(line -> BgSaftJpqlWhere.matches(where, parameters, line::path))
        .collect(Collectors.toList());
  }

  private static List<Ln> select(String jpql) {
    return select(jpql, Collections.emptyMap());
  }

  /** GeneralLedgerAccounts — началното салдо и оборотът по сметка, както ги смята базата за G2a. */
  private static List<SaftGlAccount> glAccounts() {
    Map<Long, BgSaftBalance> balances = new LinkedHashMap<>();
    for (Ln line : select(BgSaftMoveScope.OPENING_BY_ACCOUNT)) {
      balances.merge(
          line.account,
          BgSaftBalance.opening(line.debit.subtract(line.credit)),
          BgSaftBalance::plus);
    }
    for (Ln line : select(BgSaftMoveScope.TURNOVER_BY_ACCOUNT)) {
      balances.merge(
          line.account, BgSaftBalance.turnover(line.debit, line.credit), BgSaftBalance::plus);
    }
    return BgSaftGlAccountsAssembler.assemble(
            chart(), mappings(), BgNraNomenclatures.accounts().keySet(), balances, false, true)
        .getAccounts();
  }

  /** Customers и Suppliers — по (сметка, партньор), с техните заявки. */
  private static BgSaftPartiesAssembler.Result parties() {
    Map<String, Object> extra =
        Map.of(
            "accountIds",
            new ArrayList<>(BgSaftPartiesAssembler.partyAccounts(chart(), mappings()).keySet()));
    Map<BgSaftPartiesAssembler.Key, BgSaftBalance> balances = new LinkedHashMap<>();
    for (Ln line : select(BgSaftMoveScope.OPENING_BY_ACCOUNT_AND_PARTNER, extra)) {
      balances.merge(
          new BgSaftPartiesAssembler.Key(line.account, line.partner),
          BgSaftBalance.opening(line.debit.subtract(line.credit)),
          BgSaftBalance::plus);
    }
    for (Ln line : select(BgSaftMoveScope.TURNOVER_BY_ACCOUNT_AND_PARTNER, extra)) {
      balances.merge(
          new BgSaftPartiesAssembler.Key(line.account, line.partner),
          BgSaftBalance.turnover(line.debit, line.credit),
          BgSaftBalance::plus);
    }
    return BgSaftPartiesAssembler.assemble(chart(), mappings(), partners(), balances);
  }

  /** GeneralLedgerEntries от редовете, които избира подаденият предикат. */
  private static BgSaftGlEntriesAssembler.Result glEntries(String lineIds) {
    List<BgSaftGlEntriesAssembler.LineRow> rows = new ArrayList<>();
    Map<Long, Integer> counters = new TreeMap<>();
    for (Ln line : select(lineIds)) {
      rows.add(line.row(counters.merge(line.move.id, 1, Integer::sum)));
    }
    return BgSaftGlEntriesAssembler.assemble(
        rows,
        chart(),
        mappings(),
        partners(),
        Collections.emptyMap(),
        BgNraNomenclatures.taxCodes(),
        OWN_ID);
  }

  /** Дебит минус кредит по TaxpayerAccountID — от самите елементи на дневника. */
  private static Map<String, BigDecimal> netFromFile(BgSaftGlEntriesAssembler.Result entries) {
    Map<String, BigDecimal> net = new TreeMap<>();
    for (SaftJournal journal : entries.getGeneralLedger().getJournals()) {
      for (SaftTransaction transaction : journal.getTransactions()) {
        for (SaftTransactionLine line : transaction.getLines()) {
          BigDecimal amount = line.getAmount().getAmount();
          net.merge(
              line.getTaxpayerAccountId(),
              line.getDebit() ? amount : amount.negate(),
              BigDecimal::add);
        }
      }
    }
    return net;
  }

  /** Сметките, за които „крайно − начално = оборот в дневника“ НЕ е вярно. */
  private static Set<String> mismatched(List<SaftGlAccount> accounts, Map<String, BigDecimal> net) {
    Set<String> bad = new HashSet<>();
    Set<String> known = new HashSet<>();
    for (SaftGlAccount account : accounts) {
      known.add(account.getTaxpayerAccountId());
      BigDecimal expected = account.getClosingBalance().subtract(account.getOpeningBalance());
      if (expected.compareTo(net.getOrDefault(account.getTaxpayerAccountId(), BigDecimal.ZERO))
          != 0) {
        bad.add(account.getTaxpayerAccountId());
      }
    }
    for (String code : net.keySet()) {
      if (!known.contains(code)) {
        bad.add(code);
      }
    }
    return bad;
  }

  // ─────────────── тестовете ───────────────

  @Test
  @DisplayName("За всяка сметка: крайно − начално салдо (G2a) = дебит − кредит в дневника (G3)")
  void реконсилиацията_държи() {
    List<SaftGlAccount> accounts = glAccounts();
    BgSaftGlEntriesAssembler.Result entries = glEntries(BgSaftGlEntriesReader.LINE_IDS);
    Map<String, BigDecimal> net = netFromFile(entries);

    // Числата, а не само равенството: салдата са тези, които фикстурата предвижда.
    Map<String, String[]> expected = new TreeMap<>();
    expected.put("101", new String[] {"-110.00", "-110.00"});
    expected.put("302", new String[] {"0.00", "50.00"});
    expected.put("401", new String[] {"0.00", "60.00"});
    expected.put("4111", new String[] {"-120.00", "-120.00"});
    expected.put("4531", new String[] {"0.00", "10.00"});
    expected.put("4532", new String[] {"0.00", "-20.00"});
    expected.put("503", new String[] {"230.00", "230.00"});
    expected.put("702", new String[] {"0.00", "-100.00"});
    Map<String, String[]> actual = new TreeMap<>();
    accounts.forEach(
        a ->
            actual.put(
                a.getTaxpayerAccountId(),
                new String[] {
                  a.getOpeningBalance().toPlainString(), a.getClosingBalance().toPlainString()
                }));
    assertEquals(expected.keySet(), actual.keySet());
    expected.forEach((code, v) -> assertEquals(List.of(v), List.of(actual.get(code)), code));

    assertEquals(Set.of(), mismatched(accounts, net));
    assertEquals(List.of(), BgSaftGlEntriesAssembler.reconcile(accounts, net));
    // Удобната карта на assembler-а казва същото като самите елементи на файла.
    assertEquals(net, entries.getNetByTaxpayerAccount());

    // Пет статии в периода: без откриващата от 01.04 и без седемте извън обхвата.
    assertEquals(5L, entries.getGeneralLedger().getNumberOfEntries());
    assertEquals(new BigDecimal("420.00"), entries.getGeneralLedger().getTotalDebit());
    assertEquals(new BigDecimal("420.00"), entries.getGeneralLedger().getTotalCredit());
  }

  @Test
  @DisplayName("Всеки CustomerID/SupplierID от дневника го има в Customers/Suppliers")
  void идентификаторите_са_в_таблиците() {
    BgSaftGlEntriesAssembler.Result entries = glEntries(BgSaftGlEntriesReader.LINE_IDS);
    assertTrue(
        entries.getFindings().stream()
            .noneMatch(f -> f.getCode().equals("SAFT_GLE_PARTY_NOT_IN_MASTER")),
        entries.getFindings().toString());
    BgSaftPartiesAssembler.Result parties = parties();
    Set<String> master = new HashSet<>(Set.of(BgSaftGlEntriesAssembler.NO_PARTY, OWN_ID));
    parties.getCustomers().forEach(p -> master.add(p.getPartyId()));
    parties.getSuppliers().forEach(p -> master.add(p.getPartyId()));
    for (SaftJournal journal : entries.getGeneralLedger().getJournals()) {
      for (SaftTransaction t : journal.getTransactions()) {
        assertTrue(master.contains(t.getCustomerId()), t.getCustomerId());
        assertTrue(master.contains(t.getSupplierId()), t.getSupplierId());
        for (SaftTransactionLine l : t.getLines()) {
          assertTrue(master.contains(l.getCustomerId()), l.getCustomerId());
          assertTrue(master.contains(l.getSupplierId()), l.getSupplierId());
        }
      }
    }
  }

  @Test
  @DisplayName(
      "Отрицателна контрола: предикат БЕЗ изключването на откриващите — реконсилиацията пада")
  void без_изключване_на_откриващите_пада() {
    String nearMiss =
        "SELECT self.id FROM MoveLine self WHERE "
            + BgSaftMoveScope.POSTED_LINES
            + " AND self.move.date >= :fromDate AND self.move.date <= :toDate ORDER BY self.id";
    List<SaftGlAccount> accounts = glAccounts();
    BgSaftGlEntriesAssembler.Result entries = glEntries(nearMiss);
    // Откриващата статия от 01.04 влиза и в началното салдо, и в оборота.
    assertEquals(Set.of("101", "503"), mismatched(accounts, netFromFile(entries)));
    List<BgDeclarationFileFinding> findings =
        BgSaftGlEntriesAssembler.reconcile(accounts, entries.getNetByTaxpayerAccount());
    // По една находка на сметка (G6a): 101 и 503.
    assertEquals(2, findings.size());
    assertTrue(findings.stream().allMatch(f -> f.getCode().equals("SAFT_GLE_GLA_MISMATCH")));
    assertTrue(
        findings.stream()
            .anyMatch(f -> f.getMessage().endsWith("503 (салдата: 0.00, дневникът: 30.00)")),
        findings.toString());
  }

  @Test
  @DisplayName(
      "Отрицателна контрола: „<> откриваща“ без IS NULL губи статиите — реконсилиацията пада")
  void без_is_null_пада() {
    String nearMiss =
        BgSaftGlEntriesReader.LINE_IDS.replace(
            "(self.move.functionalOriginSelect IS NULL"
                + " OR self.move.functionalOriginSelect <> :openingOrigin)",
            "self.move.functionalOriginSelect <> :openingOrigin");
    assertFalse(nearMiss.equals(BgSaftGlEntriesReader.LINE_IDS), "подмяната трябва да е станала");
    Set<String> bad = mismatched(glAccounts(), netFromFile(glEntries(nearMiss)));
    // Продажбата, последният ден и плащането са с NULL произход и изчезват от дневника.
    assertTrue(bad.containsAll(Set.of("702", "4532", "503", "401")), bad.toString());
  }
}
