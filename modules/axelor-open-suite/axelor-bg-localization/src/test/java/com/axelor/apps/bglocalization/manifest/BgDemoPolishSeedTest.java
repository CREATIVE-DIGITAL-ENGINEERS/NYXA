package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НАЧАЛНИТЕ ДАННИ, КОИТО СЕ ВИЖДАТ В ДЕМО ДАННИТЕ.
 *
 * <ul>
 *   <li>„Cust. Inv.“/„Suppl. Inv.“ в описанията на редовете от движенията — моделът на описанието
 *       на дневниците в BG_COA ({@code account_journal.csv}, колона {@code descriptionModel});
 *   <li>„30 days net“ на фактурата — условието за плащане NET30 ({@code
 *       account_paymentCondition.csv});
 *   <li>сметка 913 „Mатериални…“ — първото „M“ е латинско;
 *   <li>„Origin and amount equal“ на банковото съпоставяне — правилата на axelor-bank-payment
 *       ({@code bankpayment_bankStatementQuery.csv}).
 * </ul>
 *
 * Заварените бази получават същото при старта (BgLegacySeedLabels).
 */
class BgDemoPolishSeedTest {

  private static final Path BG_COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Path BANK_STATEMENT_QUERIES =
      BgDomainColumns.modulesRoot()
          .resolve(
              "axelor-open-suite/axelor-bank-payment/src/main/resources/apps/init-data/en"
                  + "/bankpayment_bankStatementQuery.csv");

  /** Дума, в която латиница и кирилица се редуват — „Mатериални“ с латинско M. */
  private static final Pattern MIXED_SCRIPT =
      Pattern.compile(
          "\\b\\w*(?:[A-Za-z][\\p{IsCyrillic}]|[\\p{IsCyrillic}][A-Za-z])\\w*\\b",
          Pattern.UNICODE_CHARACTER_CLASS);

  @Test
  @DisplayName("дневниците на BG_COA: описанието на редовете е на български")
  void journalDescriptionsAreBulgarian() throws IOException {
    Map<String, String> byCode = new LinkedHashMap<>();
    for (CSVRecord r : read(BG_COA.resolve("account_journal.csv"))) {
      byCode.put(r.get("code"), r.get("descriptionModel"));
    }
    assertEquals("Фактура за продажба", byCode.get("SAL"));
    assertEquals("Кредитно известие за продажба", byCode.get("RFS"));
    assertEquals("Фактура за покупка", byCode.get("PCH"));
    assertEquals("Кредитно известие за покупка", byCode.get("RFP"));
    assertEquals("Начален баланс", byCode.get("RPB"));
    List<String> latin =
        byCode.entrySet().stream()
            // Английска дума (2+ латински букви); „Z-отчет“ на фискалното устройство е термин.
            .filter(e -> e.getValue().matches(".*[A-Za-z]{2,}.*"))
            .map(e -> e.getKey() + " „" + e.getValue() + "“")
            .collect(Collectors.toList());
    assertTrue(latin.isEmpty(), "описание на дневник с латиница: " + latin);
  }

  @Test
  @DisplayName("условието за плащане NET30 е на български — излиза на фактурата")
  void paymentConditionIsBulgarian() throws IOException {
    List<CSVRecord> rows = read(BG_COA.resolve("account_paymentCondition.csv"));
    assertEquals(1, rows.size());
    assertEquals("NET30", rows.get(0).get("code"));
    assertEquals("30 дни от датата на фактурата", rows.get(0).get("name"));
  }

  @Test
  @DisplayName("BG_COA: нито една дума не смесва латиница и кирилица (913 „Mатериални“)")
  void noMixedScriptWordInBgCoa() throws IOException {
    List<String> found = new ArrayList<>();
    List<Path> files;
    try (Stream<Path> list = Files.list(BG_COA)) {
      files = list.filter(p -> p.toString().endsWith(".csv")).sorted().collect(Collectors.toList());
    }
    for (Path file : files) {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        Matcher m = MIXED_SCRIPT.matcher(lines.get(i));
        while (m.find()) {
          found.add(file.getFileName() + ":" + (i + 1) + " „" + m.group() + "“");
        }
      }
    }
    assertTrue(found.isEmpty(), "смесена латиница/кирилица: " + found);

    Map<String, String> names = new LinkedHashMap<>();
    for (CSVRecord r : read(BG_COA.resolve("account_account.csv"))) {
      names.put(r.get("code"), r.get("name"));
    }
    assertEquals("Материални активи, приети на съхранение", names.get("913"));
  }

  @Test
  @DisplayName("правилата за банково съпоставяне са на български („Еднакви произход и сума“)")
  void bankStatementQueriesAreBulgarian() throws IOException {
    Map<String, String> byId = new LinkedHashMap<>();
    for (CSVRecord r : read(BANK_STATEMENT_QUERIES)) {
      byId.put(r.get("importId"), r.get("name"));
    }
    assertEquals("Еднакви произход и сума", byId.get("40"));
    List<String> english =
        byId.entrySet().stream()
            .filter(e -> e.getValue().matches(".*[A-Za-z]{3,}.*"))
            .map(e -> e.getKey() + " „" + e.getValue() + "“")
            .collect(Collectors.toList());
    assertTrue(english.isEmpty(), "правило с английско име: " + english);
  }

  private static List<CSVRecord> read(Path file) throws IOException {
    try (CSVParser parser =
        CSVFile.DEFAULT
            .withDelimiter(';')
            .withFirstRecordAsHeader()
            .parse(file.toFile(), StandardCharsets.UTF_8)) {
      return parser.getRecords();
    }
  }
}
