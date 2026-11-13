package com.axelor.apps.bglocalization.service.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BgLegacySeedLabels} и файловете са едно и също: новият текст за заварените записи е точно
 * този, който новата инсталация получава от CSV-тата, а старият вече го няма в тях.
 */
class BgLegacySeedLabelsTest {

  private static final Path BG_COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  @Test
  @DisplayName("дневниците, условието за плащане и 913 — новото е в BG_COA, старото го няма")
  void bgCoaMatches() throws IOException {
    Map<String, String> journals =
        column(BG_COA.resolve("account_journal.csv"), "code", "descriptionModel");
    BgLegacySeedLabels.JOURNAL_DESCRIPTIONS.forEach(
        (code, change) -> assertEquals(change[1], journals.get(code), "дневник " + code));
    Map<String, String> conditions =
        column(BG_COA.resolve("account_paymentCondition.csv"), "code", "name");
    BgLegacySeedLabels.PAYMENT_CONDITION_NAMES.forEach(
        (code, change) -> assertEquals(change[1], conditions.get(code), "условие " + code));
    Map<String, String> accounts = column(BG_COA.resolve("account_account.csv"), "code", "name");
    BgLegacySeedLabels.ACCOUNT_NAMES.forEach(
        (code, change) -> assertEquals(change[1], accounts.get(code), "сметка " + code));
  }

  @Test
  @DisplayName(
      "правилата за банково съпоставяне — новите имена са тези от init-data на bank-payment")
  void bankStatementQueriesMatch() throws IOException {
    Map<String, String> byId =
        column(
            BgDomainColumns.modulesRoot()
                .resolve(
                    "axelor-open-suite/axelor-bank-payment/src/main/resources/apps/init-data/en"
                        + "/bankpayment_bankStatementQuery.csv"),
            "importId",
            "name");
    for (String name : byId.values()) {
      assertTrue(
          BgLegacySeedLabels.BANK_STATEMENT_QUERY_NAMES.containsValue(name),
          "„" + name + "“ няма стар английски двойник в BgLegacySeedLabels");
    }
    for (String old : BgLegacySeedLabels.BANK_STATEMENT_QUERY_NAMES.keySet()) {
      assertTrue(!byId.containsValue(old), "старото име „" + old + "“ е още във файла");
    }
  }

  @Test
  @DisplayName("сменя се само точно старият текст")
  void onlyTheExactOldText() {
    String[] change = BgLegacySeedLabels.JOURNAL_DESCRIPTIONS.get("SAL");
    assertEquals("Фактура за продажба", BgLegacySeedLabels.renamed(change, "Cust. Inv."));
    assertNull(BgLegacySeedLabels.renamed(change, "Cust. Inv. (ръчно)"), "редактираният остава");
    assertNull(BgLegacySeedLabels.renamed(change, null));
    assertNull(BgLegacySeedLabels.renamed(null, "Cust. Inv."));
    String[] account = BgLegacySeedLabels.ACCOUNT_NAMES.get("913");
    assertTrue(account[0].startsWith("M") && account[1].startsWith("М"), "латинско → кирилско М");
    assertTrue(!account[1].matches(".*[A-Za-z].*"), "новото име е без латиница: " + account[1]);
  }

  private static Map<String, String> column(Path file, String key, String value)
      throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    try (CSVParser parser =
        CSVFile.DEFAULT
            .withDelimiter(';')
            .withFirstRecordAsHeader()
            .parse(file.toFile(), StandardCharsets.UTF_8)) {
      for (CSVRecord r : parser) {
        out.put(r.get(key), r.get(value));
      }
    }
    return out;
  }
}
