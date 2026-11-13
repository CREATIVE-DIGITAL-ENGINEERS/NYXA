package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПОКУПНАТА ФАКТУРА С ОБРАТНО НАЧИСЛЯВАНЕ НЕТИРА ДДС ПО 4531 — затова протоколът по чл. 117 носи
 * своите Дт 4531 / Кт 4532, без да удвоява главната книга.
 *
 * <p>Axelor пише по един ред за всеки данъчен ред на фактурата ({@code
 * MoveLineCreateServiceImpl.createMoveLines}, сметката е {@code InvoiceLineTax.imputedAccount}):
 * собственият данък (напр. {@code BG_VOP_ACQ}) е ДЕБИТ, а данъкът за самоначисляването ({@code
 * BG_VOP_SELF}, отрицателен) — КРЕДИТ. Сметката на всеки е ПОКУПНАТА ДДС сметка на СОБСТВЕНИЯ му
 * данък според системата за ДДС и ДМА ({@code TaxInvoiceLine.getImputedAccount} → {@code
 * TaxAccountService.getAccount}). Затова се сверяват и трите колони на покупката: система 1,
 * система 2 и ДМА. Празната колона не е проблем за протокола — Axelor отказва фактурата сам; 4532
 * там би бил: фактурата сама би начислила данъка, а протоколът — втори път.
 */
class BgReverseChargeNettingSeedTest {

  private static final Path COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  /** Покупните ДДС сметки на данъка: система за ДДС 1, 2 и ДМА. */
  private static final List<String> PURCHASE_VAT_COLUMNS =
      Arrays.asList(
          "purchaseTaxVatSystem1Account_code",
          "purchaseTaxVatSystem2Account_code",
          "purchFixedAssetsTaxVatSystem1Account_code",
          "purchFixedAssetsTaxVatSystem2Account_code");

  private static List<String[]> rows(String file) throws IOException {
    List<String[]> rows = new ArrayList<>();
    for (String line : Files.readAllLines(COA.resolve(file), StandardCharsets.UTF_8)) {
      if (line.trim().isEmpty()) {
        continue;
      }
      String[] cells = line.split(";", -1);
      for (int i = 0; i < cells.length; i++) {
        cells[i] = cells[i].replaceAll("^\"|\"$", "");
      }
      rows.add(cells);
    }
    return rows;
  }

  private static int column(String[] header, String name) {
    int at = Arrays.asList(header).indexOf(name);
    assertFalse(at < 0, "колона " + name);
    return at;
  }

  @Test
  @DisplayName(
      "За всяка двойка с обратно начисляване и двата данъка са по 4531 при покупка — никога 4532")
  void двойките_нетират_по_4531() throws IOException {
    List<String[]> management = rows("account_accountManagement.csv");
    String[] mh = management.get(0);
    int taxCode = column(mh, "tax_code");
    Map<String, Map<String, String>> accounts = new HashMap<>();
    for (String[] row : management.subList(1, management.size())) {
      if (row[taxCode].isEmpty()) {
        continue;
      }
      Map<String, String> byColumn = new HashMap<>();
      for (String name : PURCHASE_VAT_COLUMNS) {
        byColumn.put(name, row[column(mh, name)]);
      }
      accounts.put(row[taxCode], byColumn);
    }

    List<String[]> equivs = rows("account_taxEquiv.csv");
    String[] eh = equivs.get(0);
    int to = column(eh, "toTaxSet");
    int reverse = column(eh, "reverseCharge");
    int self = column(eh, "reverseChargeTaxSet");
    int pairs = 0;
    for (String[] row : equivs.subList(1, equivs.size())) {
      if (!"true".equals(row[reverse]) || row[self].isEmpty()) {
        continue;
      }
      pairs++;
      for (String tax : new String[] {row[to], row[self]}) {
        Map<String, String> byColumn = accounts.get(tax);
        assertFalse(byColumn == null, "данъкът " + tax + " няма ред в account_accountManagement");
        assertEquals(
            "4531",
            byColumn.get("purchaseTaxVatSystem1Account_code"),
            tax + " — покупката по система 1 (дебитът/кредитът на фактурата)");
        for (String name : PURCHASE_VAT_COLUMNS) {
          assertNotEquals(
              "4532",
              byColumn.get(name),
              tax + " / " + name + ": 4532 би начислил данъка от фактурата И от протокола");
        }
      }
    }
    assertFalse(pairs == 0, "няма нито една двойка с обратно начисляване — тестът не мери нищо");
  }
}
