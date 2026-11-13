package com.axelor.apps.bglocalization.service.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * НАСТРОЙКИТЕ НА ЗАПЛАТИТЕ, КОИТО BG_COA НОСИ — четени от истинските CSV файлове.
 *
 * <p><b>⚠️ ЗАЩО ТОВА Е ПАЗАЧ, А НЕ ФОРМАЛНОСТ.</b> Изплащането на фиш ОТКАЗВА при празен заплатен
 * журнал. Дружество, на което BG_COA не го е вързал, значи ведомост, която не може да се изплати
 * още от първия ден — и грешката е в шаблона, не в ръцете на счетоводителя.
 *
 * <p>⚠️ Банковата сметка на платеца НЕ се носи: тя е на клиента.
 */
class BgPayrollConfigSeedTest {

  private static final Path COA =
      Paths.get("../axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Path CHART_CONFIG =
      Paths.get("../axelor-account/src/main/resources/l10n/chart-config.xml");

  private static final Path FILE_FORMATS =
      Paths.get(
          "../axelor-bank-payment/src/main/resources/data-init/input/"
              + "bankpayment_bankOrderFileFormat.csv");

  private static final String PAYROLL_MODE = "OUT_PAY";

  @Test
  @DisplayName("BG_COA връзва AccountConfig.bgPayrollJournal към PAY, а PAY е журнал от тип PAY")
  void журналът_е_вързан() throws IOException {
    assertEquals(
        "PAY",
        rows(COA.resolve("account_accountConfig_journal.csv")).get(0).get("bgPayrollJournal_code"));
    Map<String, String> pay = byColumn(rows(COA.resolve("account_journal.csv")), "code", "PAY");
    assertNotNull(pay, "журнал PAY липсва в account_journal.csv");
    assertEquals("PAY", pay.get("journalType.code"));
    assertTrue(
        chart().contains("<bind to=\"bgPayrollJournal\" if=\"bgPayrollJournal_code\""),
        "chart-config.xml връзва колоната — без bind тя се чете и се хвърля");
  }

  /**
   * ⚠️ ПО ИМЕ, ЗАЩОТО ТАКА СЕ ВРЪЗВА. Колоната {@code bankOrderFileFormat.name} се търси по име при
   * вноса; правописна разлика с upstream реда дава режим БЕЗ формат, тоест празен списък в полето
   * на настройката (домейнът му иска pain.001.001.03.sct) — без грешка при вноса.
   */
  @Test
  @DisplayName("OUT_PAY носи pain.001.001.03.sct по точното име на upstream реда, без статия")
  void режимът_носи_формата() throws IOException {
    Map<String, String> mode =
        byColumn(rows(COA.resolve("account_paymentMode.csv")), "code", PAYROLL_MODE);
    assertNotNull(mode, "режимът за заплати липсва в account_paymentMode.csv");

    Map<String, String> format = byColumn(rows(FILE_FORMATS), "importId", "6");
    assertNotNull(format, "upstream редът на формата (importId 6) липсва");
    assertEquals("pain.001.001.03.sct", format.get("orderFileFormatSelect"));
    assertEquals(format.get("name"), mode.get("bankOrderFileFormat.name"));

    assertEquals("2", mode.get("inOutSelect"), "изходящ");
    assertEquals("0", mode.get("accountingTriggerSelect"), "без статия — тя е на фиша");
    assertEquals("false", mode.get("canSelectInPaymentSession"), "не е за сесии с доставчици");
    assertEquals("1", mode.get("orderTypeSelect"), "SEPA кредитен превод");
    assertTrue(!mode.get("bankOrderExportFolderPath").isEmpty(), "без папка файлът не се записва");
  }

  @Test
  @DisplayName("BG_COA връзва bgPayrollPaymentMode към OUT_PAY, а OUT_WT остава без формат")
  void режимът_е_вързан_а_out_wt_не_е_пипнат() throws IOException {
    Map<String, String> payments = rows(COA.resolve("account_accountConfig_payments.csv")).get(0);
    assertEquals(PAYROLL_MODE, payments.get("bgPayrollPaymentMode.code"));
    assertEquals("OUT_WT", payments.get("outPaymentMode.code"));

    // ⚠️ ОБРАТНИЯТ СВИДЕТЕЛ. Форматът на OUT_WT би сменил и нарежданията към доставчици.
    Map<String, String> wire =
        byColumn(rows(COA.resolve("account_paymentMode.csv")), "code", "OUT_WT");
    assertEquals("", wire.get("bankOrderFileFormat.name"));
  }

  // ── подпората ───────────────────────────────────────────────────────────

  private static String chart() throws IOException {
    return new String(Files.readAllBytes(CHART_CONFIG), StandardCharsets.UTF_8);
  }

  private static Map<String, String> byColumn(
      List<Map<String, String>> rows, String column, String value) {
    return rows.stream().filter(r -> value.equals(r.get(column))).findFirst().orElse(null);
  }

  /** Редовете на CSV с „;“ и кавички — достатъчно за тези файлове (без „;“ вътре в стойност). */
  private static List<Map<String, String>> rows(Path file) throws IOException {
    List<String> all = Files.readAllLines(file, StandardCharsets.UTF_8);
    List<String> header = split(all.get(0));
    List<Map<String, String>> out = new ArrayList<>();
    for (String line : all.subList(1, all.size())) {
      if (line.trim().isEmpty()) {
        continue;
      }
      List<String> cells = split(line);
      Map<String, String> row = new LinkedHashMap<>();
      for (int i = 0; i < header.size(); i++) {
        row.put(header.get(i), i < cells.size() ? cells.get(i) : "");
      }
      out.add(row);
    }
    return out;
  }

  private static List<String> split(String line) {
    return Arrays.stream(line.split(";", -1))
        .map(
            c ->
                c.startsWith("\"") && c.endsWith("\"") && c.length() >= 2
                    ? c.substring(1, c.length() - 1)
                    : c)
        .collect(Collectors.toList());
  }
}
