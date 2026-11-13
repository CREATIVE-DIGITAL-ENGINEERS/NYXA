package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Началните настройки на счетоводството, които ново дружество получава от шаблона BG_COA.
 *
 * <p>Шаблонът се прилага с бутона в конфигурацията на сметките на дружеството ({@code
 * l10n/chart-config.xml} върху CSV-тата на BG_COA) — веднъж, после бутонът става само за четене.
 * Заварените дружества не се пипат: при стартиране нищо не чете тези файлове.
 *
 * <p>Тестът е файлов, тоест върви и в CI. Пътят през истинския внос върху ново дружество е {@code
 * BgAccountConfigTemplateIntegrationTest}.
 */
class BgCoaAccountConfigDefaultsTest {

  private static final Path L10N =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n");

  private static final Path COA = L10N.resolve("l10n_bgr/BG_COA");

  /**
   * Файл → колона → стойност. 609 от заданието е групова сметка (тип view) и никой журнал не я
   * допуска; на нейно място е листовата 60909 „Други разходи“.
   */
  static final Map<String, Map<String, String>> EXPECTED = new LinkedHashMap<>();

  static {
    EXPECTED.put(
        "account_accountConfig_accounts.csv",
        Map.of(
            "resultProfitAccount_code", "123",
            "resultLossAccount_code", "123",
            "cashPositionVariationDebitAccount_code", "60909",
            "cashPositionVariationCreditAccount_code", "709",
            "bgVatChargedAccount_code", "4532",
            "bgVatDeductibleAccount_code", "4531",
            "bgVatAnnualCorrectionExpenseAccount_code", "60909",
            "bgVatCorrectionIncomeAccount_code", "709"));
    EXPECTED.put("account_accountConfig_journal.csv", Map.of("bgVatCorrectionJournal_code", "VAT"));
    EXPECTED.put(
        "account_accountConfig_misc.csv",
        Map.of(
            "allowedTaxGap", "0.01",
            "isDescriptionRequired", "true",
            "partnerAccountGenerationModeSelect", "0"));
    EXPECTED.put(
        "account_accountConfig_payments.csv",
        Map.of(
            "inPaymentMode.code", "INC_WT",
            "outPaymentMode.code", "OUT_WT",
            "irrecoverableReasonPassage", "Ръчно прехвърляне към несъбираеми вземания"));
    // „Аванс“: авансова фактура от поръчка иска продукта — без него AOS гърми.
    EXPECTED.put(
        "account_accountConfig_invoicing.csv",
        Map.of(
            "autoReconcileOnInvoice", "false",
            "autoReconcileOnPayment", "false",
            "advancePaymentProduct.importId", "BG-ADVANCE"));
  }

  /** Колоните на ДДС протоколите и журналът, в който лягат техните статии. */
  private static final List<String> VAT_PROTOCOL_ACCOUNTS =
      Arrays.asList(
          "bgVatChargedAccount_code",
          "bgVatDeductibleAccount_code",
          "bgVatAnnualCorrectionExpenseAccount_code",
          "bgVatCorrectionIncomeAccount_code");

  @Test
  @DisplayName("Началните настройки на BG_COA са очакваните")
  void стойностите_са_по_прегледа() {
    List<String> bad = new ArrayList<>();
    EXPECTED.forEach(
        (file, columns) -> {
          Map<String, String> row = firstRow(COA.resolve(file));
          columns.forEach(
              (column, value) -> {
                if (!value.equals(row.get(column))) {
                  bad.add(file + " " + column + " = " + row.get(column) + ", очаквах " + value);
                }
              });
        });
    assertEquals(List.of(), bad);
  }

  @Test
  @DisplayName("Всяка сметка от настройките е в BG_COA, листова и активна")
  void сметките_са_листови() {
    Map<String, Map<String, String>> accounts = rowsBy(COA.resolve("account_account.csv"), "code");
    Map<String, String> technicalType = new HashMap<>();
    rowsBy(COA.resolve("account_accountType.csv"), "importId")
        .forEach((id, r) -> technicalType.put(id, r.get("technicalTypeSelect")));
    Map<String, String> config = firstRow(COA.resolve("account_accountConfig_accounts.csv"));
    List<String> bad = new ArrayList<>();
    for (String column : EXPECTED.get("account_accountConfig_accounts.csv").keySet()) {
      String code = config.get(column);
      Map<String, String> account = accounts.get(code);
      if (account == null) {
        bad.add(column + " → " + code + ": няма я в account_account.csv");
        continue;
      }
      String type = technicalType.get(account.get("accountType.importId"));
      if ("view".equals(type)) {
        bad.add(column + " → " + code + ": групова сметка (view), никой журнал не я допуска");
      }
      if (!"1".equals(account.get("statusSelect"))) {
        bad.add(column + " → " + code + ": не е активна");
      }
    }
    assertEquals(List.of(), bad);
  }

  @Test
  @DisplayName("Дневникът на ДДС протоколите допуска сметките на протоколите")
  void дневникът_допуска_сметките() {
    Map<String, String> journalConfig = firstRow(COA.resolve("account_accountConfig_journal.csv"));
    Map<String, String> journal =
        rowsBy(COA.resolve("account_journal.csv"), "code")
            .get(journalConfig.get("bgVatCorrectionJournal_code"));
    assertTrue(journal != null, "дневникът на ДДС протоколите липсва в account_journal.csv");
    List<String> allowed = Arrays.asList(journal.get("validAccountTypes").split("\\|"));
    Map<String, Map<String, String>> accounts = rowsBy(COA.resolve("account_account.csv"), "code");
    Map<String, String> config = firstRow(COA.resolve("account_accountConfig_accounts.csv"));
    List<String> bad = new ArrayList<>();
    for (String column : VAT_PROTOCOL_ACCOUNTS) {
      String code = config.get(column);
      String type = accounts.get(code).get("accountType.importId");
      if (!allowed.contains(type)) {
        bad.add(code + " (тип " + type + ") не е в " + allowed);
      }
    }
    assertEquals(List.of(), bad);
  }

  /**
   * Колона, която не стига до поле, се пренебрегва МЪЛЧАЛИВО: {@code xxx_code} е само параметър на
   * изричен bind в chart-config.xml, а друга колона, която не е поле, отива в {@code
   * handleDummyBind}. Тоест липсващ bind значи празна настройка при зелен внос.
   */
  @Test
  @DisplayName("Всяка колона от прегледа стига до поле на AccountConfig")
  void колоните_стигат_до_поле() throws IOException {
    String config =
        new String(Files.readAllBytes(L10N.resolve("chart-config.xml")), StandardCharsets.UTF_8);
    Map<String, BgDomainColumns.FieldSpec> fields =
        BgDomainColumns.allFields().get("com.axelor.apps.account.db.AccountConfig");
    List<String> bad = new ArrayList<>();
    EXPECTED.forEach(
        (file, columns) -> {
          String input = inputOf(config, file);
          for (String column : columns.keySet()) {
            if (column.endsWith("_code")) {
              String field = column.substring(0, column.length() - "_code".length());
              Pattern bind =
                  Pattern.compile(
                      "<bind[^>]*to=\""
                          + Pattern.quote(field)
                          + "\"[^>]*:"
                          + Pattern.quote(column)
                          + "\\b",
                      Pattern.DOTALL);
              if (!bind.matcher(input).find()) {
                bad.add(file + " " + column + ": няма <bind to=\"" + field + "\"> по него");
              }
            } else {
              String field = column.contains(".") ? column.split("\\.")[0] : column;
              if (!fields.containsKey(field)) {
                bad.add(file + " " + column + ": AccountConfig няма поле " + field);
              }
            }
          }
        });
    assertEquals(List.of(), bad);
  }

  private static String inputOf(String config, String file) {
    Matcher m =
        Pattern.compile("<input file=\"" + Pattern.quote(file) + "\".*?</input>", Pattern.DOTALL)
            .matcher(config);
    if (!m.find()) {
      fail("chart-config.xml няма <input> за " + file);
    }
    return m.group();
  }

  private static Map<String, String> firstRow(Path file) {
    try (CSVParser parser = parse(file)) {
      for (CSVRecord r : parser) {
        return new HashMap<>(r.toMap());
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    fail("празен " + file);
    return null;
  }

  private static Map<String, Map<String, String>> rowsBy(Path file, String key) {
    Map<String, Map<String, String>> out = new HashMap<>();
    try (CSVParser parser = parse(file)) {
      for (CSVRecord r : parser) {
        out.put(r.get(key), new HashMap<>(r.toMap()));
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    return out;
  }

  private static CSVParser parse(Path file) throws IOException {
    return CSVFile.DEFAULT
        .withDelimiter(';')
        .withFirstRecordAsHeader()
        .parse(file.toFile(), StandardCharsets.UTF_8);
  }
}
