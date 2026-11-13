package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.AccountRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.MappingRow;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGlAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftJournal;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransaction;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTransactionLine;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Изричните редове на SAF-T мапинга — върху ИСТИНСКИЯ seed и ИСТИНСКИЯ сметкоплан BG_COA, през
 * assembler-ите на генератора.
 *
 * <p><b>Какво беше грешно.</b> Навиването по префикс прати сметки в НАП код със същите цифри, но
 * друг смисъл: командировките (6044) в заплатите (604), непокритата загуба (1222) в
 * неразпределената печалба (122), осигуровките (4551–4556) в данъците при източника (455),
 * факторингът (4621/4622) в доброволното осигуряване (462). 4538/4539 бяха разменени (в BG_COA 4538
 * беше „за внасяне“, в НАП — „за възстановяване“); счетоводителят потвърди смисъла на НАП,
 * поправиха се имената в BG_COA, а мапингът е IDENTITY.
 *
 * <p><b>4538/4539 са IDENTITY.</b> 4538 = „…за възстановяване“ (Debit, активна), 4539 = „…за
 * внасяне“ (Credit, пасивна) — и в BG_COA, и в НАП. Сметките на ДДС в {@code
 * account_accountManagement.csv} са 4531/4532.
 */
class BgSaftAccountMappingSeedTest {

  private static final String SEED = "bg_saf_account_mapping.csv";

  private static final Path ACCOUNTS =
      BgDomainColumns.modulesRoot()
          .resolve(
              "axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA"
                  + "/account_account.csv");

  /** Сметка → {стар НАП код, нов НАП код, ДОСЛОВНОТО име на новия от NRA_Nom_Accounts}. */
  private static final Map<String, String[]> CHANGED = new LinkedHashMap<>();

  static {
    CHANGED.put("6044", new String[] {"604", "60901", "Разходи за командировки"});
    CHANGED.put("1222", new String[] {"122", "121", "Непокрита загуба от минали години"});
    for (String code : List.of("4551", "4553", "4554", "4555", "4556")) {
      CHANGED.put(
          code, new String[] {"455", "461", "Разчети за задължително социално осигуряване"});
    }
    CHANGED.put("4552", new String[] {"455", "463", "Разчети за здравно осигуряване"});
    CHANGED.put("4621", new String[] {"462", "498", "Други дебитори"});
    CHANGED.put("4622", new String[] {"462", "499", "Други кредитори"});
    // Идентитети, чийто код в НАП значи друго (PR C): мапингът се сменя, кодът и името — не.
    CHANGED.put("408", new String[] {"408", "409", "Други задължения към доставчици"});
    CHANGED.put("456", new String[] {"456", "469", "Други разчети с осигурители"});
    CHANGED.put("481", new String[] {"481", "483", "Разчети по предстоящо плащане"});
    CHANGED.put("103", new String[] {"103", "101", "Основен капитал, изискващ регистрация"});
    CHANGED.put("455", new String[] {"455", "461", "Разчети за задължително социално осигуряване"});
    CHANGED.put("615", new String[] {"615", "614", "Административни разходи"});
    CHANGED.put("622", new String[] {"622", "629", "Други финансови разходи"});
    CHANGED.put("692", new String[] {"692", "60909", "Други разходи"});
    CHANGED.put("792", new String[] {"792", "709", "Други приходи от дейността"});
    CHANGED.put("722", new String[] {"722", "721", "Приходи от лихви"});
    CHANGED.put("723", new String[] {"723", "722", "Приходи от съучастия"});
    CHANGED.put(
        "725", new String[] {"725", "723", "Приходи от операции с финансови активи и инструменти"});
    CHANGED.put("462", new String[] {"462", "499", "Други кредитори"});
  }

  @Test
  @DisplayName("всеки изричен ред сочи НАП сметка с дословно същото име като в теста")
  void изричните_редове_са_по_НАП() {
    Map<String, String> seed = seedNraCodes();
    List<String> bad = new ArrayList<>();
    for (Map.Entry<String, String[]> e : CHANGED.entrySet()) {
      String code = e.getKey();
      String nra = e.getValue()[1];
      String inSeed = seed.get(code);
      String full = BgNraNomenclatures.accounts().get(nra);
      if (!nra.equals(inSeed)) {
        bad.add(code + ": в seed-а е " + inSeed + ", а не " + nra);
      } else if (!(nra + " " + e.getValue()[2]).equals(full)) {
        bad.add(code + " → " + nra + ": в NRA_Nom_Accounts е „" + full + "“");
      }
    }
    assertEquals(List.of(), bad);
  }

  @Test
  @DisplayName("статия по 6044 → AccountID 60901, TaxpayerAccountID 6044")
  void статия_по_командировки() {
    Coa coa = coa();
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>();
    lines.add(line(9001L, 1, coa.ids.get("6044"), "6044", "100", null));
    lines.add(line(9002L, 2, coa.ids.get("503"), "503", null, "100"));

    BgSaftGlEntriesAssembler.Result result =
        BgSaftGlEntriesAssembler.assemble(
            lines,
            coa.rows(),
            mappings(seedNraCodes()),
            BgSaftFixtures.glPartners(),
            BgSaftFixtures.glTaxMappings(),
            BgNraNomenclatures.taxCodes(),
            BgSaftFixtures.OWN_ID);

    SaftTransactionLine travel = lineFor(result, "6044");
    assertEquals("60901", travel.getAccountId(), "командировките не са заплати (604)");
    assertEquals("6044", travel.getTaxpayerAccountId());
  }

  @Test
  @DisplayName("внасяне → AccountID 4539; възстановяване → AccountID 4538 (IDENTITY)")
  void ДДС_за_внасяне_и_за_възстановяване() {
    Coa coa = coa();
    Map<Long, BgSaftBalance> balances = new LinkedHashMap<>();
    for (Long id : coa.ids.values()) {
      balances.put(id, new BgSaftBalance(BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO));
    }
    BgSaftGlAccountsAssembler.Result result =
        BgSaftGlAccountsAssembler.assemble(
            coa.rows(),
            mappings(seedNraCodes()),
            BgNraNomenclatures.accounts().keySet(),
            balances,
            false,
            false);

    // Смисълът по име в BG_COA е същият като кода в НАП: мапингът е IDENTITY.
    assertEquals(
        "4539", accountIdOf(result, coa.codeNamed("Данък върху добавената стойност за внасяне")));
    assertEquals(
        "4538",
        accountIdOf(result, coa.codeNamed("Данък върху добавената стойност за възстановяване")));
    assertEquals(
        "4539 Данък върху добавената стойност за внасяне",
        BgNraNomenclatures.accounts().get("4539"));
  }

  @Test
  @DisplayName("салдата по AccountID: местят се САМО изричните сметки, сборът е същият")
  void салдата_по_AccountID() {
    Coa coa = coa();
    Map<String, String> after = seedNraCodes();
    Map<String, String> before = new HashMap<>(after);
    for (Map.Entry<String, String[]> e : CHANGED.entrySet()) {
      before.put(e.getKey(), e.getValue()[0]);
    }

    // Различно салдо на всяка сметка, за да се види кое накъде отива.
    Map<Long, BgSaftBalance> balances = new LinkedHashMap<>();
    Map<String, BigDecimal> byCode = new HashMap<>();
    int n = 0;
    for (Map.Entry<String, Long> a : coa.ids.entrySet()) {
      BigDecimal opening = new BigDecimal(++n);
      balances.put(a.getValue(), new BgSaftBalance(opening, BigDecimal.ZERO, BigDecimal.ZERO));
      byCode.put(a.getKey(), opening);
    }

    Map<String, BigDecimal> was = byAccountId(coa, before, balances);
    Map<String, BigDecimal> now = byAccountId(coa, after, balances);

    Map<String, BigDecimal> expected = new TreeMap<>();
    for (Map.Entry<String, String[]> e : CHANGED.entrySet()) {
      BigDecimal amount = byCode.get(e.getKey()).setScale(2);
      expected.merge(e.getValue()[0], amount.negate(), BigDecimal::add);
      expected.merge(e.getValue()[1], amount, BigDecimal::add);
    }
    expected.values().removeIf(v -> v.signum() == 0);

    Map<String, BigDecimal> moved = new TreeMap<>();
    for (String id : union(was, now)) {
      BigDecimal d =
          now.getOrDefault(id, BigDecimal.ZERO).subtract(was.getOrDefault(id, BigDecimal.ZERO));
      if (d.signum() != 0) {
        moved.put(id, d);
      }
    }
    assertEquals(expected, moved, "разлика по AccountID извън изричните редове");
    assertEquals(sum(was), sum(now), "сборът на салдата се промени");
  }

  // ═══════════════════════════ ПОМОЩНИ ═══════════════════════════

  private static Map<String, BigDecimal> byAccountId(
      Coa coa, Map<String, String> nraCodes, Map<Long, BgSaftBalance> balances) {
    BgSaftGlAccountsAssembler.Result result =
        BgSaftGlAccountsAssembler.assemble(
            coa.rows(),
            mappings(nraCodes),
            BgNraNomenclatures.accounts().keySet(),
            balances,
            false,
            false);
    Map<String, BigDecimal> out = new TreeMap<>();
    for (SaftGlAccount a : result.getAccounts()) {
      out.merge(a.getAccountId(), a.getClosingBalance(), BigDecimal::add);
    }
    return out;
  }

  private static List<String> union(Map<String, ?> a, Map<String, ?> b) {
    List<String> out = new ArrayList<>(a.keySet());
    for (String k : b.keySet()) {
      if (!a.containsKey(k)) {
        out.add(k);
      }
    }
    return out;
  }

  private static BigDecimal sum(Map<String, BigDecimal> m) {
    return m.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private static String accountIdOf(BgSaftGlAccountsAssembler.Result result, String code) {
    return result.getAccounts().stream()
        .filter(a -> a.getTaxpayerAccountId().equals(code))
        .findFirst()
        .orElseThrow(() -> new AssertionError("няма ред за " + code))
        .getAccountId();
  }

  private static SaftTransactionLine lineFor(BgSaftGlEntriesAssembler.Result result, String code) {
    for (SaftJournal j : result.getGeneralLedger().getJournals()) {
      for (SaftTransaction t : j.getTransactions()) {
        for (SaftTransactionLine l : t.getLines()) {
          if (code.equals(l.getTaxpayerAccountId())) {
            return l;
          }
        }
      }
    }
    throw new AssertionError("няма ред по " + code + " в GeneralLedgerEntries");
  }

  private static BgSaftGlEntriesAssembler.LineRow line(
      long id, int counter, Long account, String code, String debit, String credit) {
    if (account == null) {
      fail("сметка " + code + " липсва в BG_COA");
    }
    return new BgSaftGlEntriesAssembler.LineRow(id)
        .counter(counter)
        .debit(debit == null ? null : new BigDecimal(debit))
        .credit(credit == null ? null : new BigDecimal(credit))
        .account(account, code)
        .moveId(900L)
        .reference("OD/0001")
        .date(LocalDate.of(2026, 4, 15))
        .createdOn(LocalDate.of(2026, 4, 15))
        .periodFrom(LocalDate.of(2026, 4, 1))
        .journal(1L, "OD", "Разни");
  }

  /** Истинският seed, през {@link BgSeed} — той пази и равенството en/fr байт по байт. */
  private static Map<String, String> seedNraCodes() {
    List<String> h = BgSeed.header(SEED);
    Map<String, String> out = new HashMap<>();
    for (String[] r : BgSeed.rows(SEED)) {
      boolean excluded = Boolean.parseBoolean(BgSeed.col(h, r, "isExcluded"));
      out.put(BgSeed.col(h, r, "accountCode"), excluded ? null : BgSeed.col(h, r, "nraCode"));
    }
    return out;
  }

  /** {@code null} = изключена (структурна) сметка. */
  private static Map<String, MappingRow> mappings(Map<String, String> nraCodes) {
    Map<String, MappingRow> out = new HashMap<>();
    nraCodes.forEach((code, nra) -> out.put(code, new MappingRow(nra, nra == null)));
    return out;
  }

  /** Сметкопланът BG_COA с родителите като id — както го чете {@code BgSaftLedgerReader}. */
  private static Coa coa() {
    Coa coa = new Coa();
    try (CSVParser parser =
        CSVFile.DEFAULT
            .withDelimiter(';')
            .withFirstRecordAsHeader()
            .parse(ACCOUNTS.toFile(), StandardCharsets.UTF_8)) {
      for (CSVRecord r : parser) {
        coa.raw.add(
            new String[] {r.get("code"), r.get("parent_code"), r.get("name"), r.get("bgApSelect")});
        coa.ids.put(r.get("code"), (long) coa.raw.size());
      }
    } catch (IOException e) {
      throw new IllegalStateException("не се чете " + ACCOUNTS, e);
    }
    return coa;
  }

  private static final class Coa {
    final List<String[]> raw = new ArrayList<>();
    final Map<String, Long> ids = new LinkedHashMap<>();

    List<AccountRow> rows() {
      List<AccountRow> out = new ArrayList<>();
      for (String[] r : raw) {
        out.add(new AccountRow(ids.get(r[0]), r[0], r[2], r[3], ids.get(r[1])));
      }
      return out;
    }

    String codeNamed(String name) {
      List<String> found = new ArrayList<>();
      for (String[] r : raw) {
        if (name.equals(r[2])) {
          found.add(r[0]);
        }
      }
      if (found.size() != 1) {
        fail("очаквах точно една сметка „" + name + "“ в BG_COA, а са " + found);
      }
      return found.get(0);
    }
  }
}
