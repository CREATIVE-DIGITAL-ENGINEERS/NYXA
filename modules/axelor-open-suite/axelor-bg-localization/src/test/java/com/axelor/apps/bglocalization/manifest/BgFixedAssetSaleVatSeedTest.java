package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИНВАРИАНТ: ДДС-то при продажба идва от ФАКТУРАТА, а не от отписването — и сметкопланът трябва
 * да го поддържа точно по този път.
 *
 * <p><b>Двата пътя до ДДС сметката са РАЗЛИЧНИ, и това е същината.</b> Ветилирането на фактура
 * минава през {@code AccountManagementServiceAccountImpl.getTaxAccount()}, който чете {@code
 * saleTaxVatSystem1Account} ДИРЕКТНО, според {@code MoveLine.vatSystemSelect}. Отписването пък
 * минаваше през {@code FixedAssetLineMoveServiceImpl.generateSaleMove}, който вземаше {@code
 * saleAccount} и я ПОДМЕНЯШЕ с ДДС сметката според {@code Account.vatSystemSelect} — тоест {@code
 * saleAccount} там беше превключвател, не приходна сметка.
 *
 * <p><b>Затова този тест закова две неща.</b> Първото: {@code saleTaxVatSystem1Account} трябва да
 * сочи съществуваща сметка, обявена за стоки — без нея ветилирането на клиентска фактура за
 * продажба на дълготраен актив пада с
 * „ACCOUNT_MANAGEMENT_SALE_TAX_VAT_SYSTEM_1_ACCOUNT_MISSING_TAX“. Второто: {@code saleAccount} на
 * редовете по данък трябва да е ПРАЗНА.
 *
 * <p><b>И защо празна, а не попълнена.</b> Тя обслужваше единствено {@code generateSaleMove}, а
 * българското отписване вече не прави sale Move: продажбата е облагаема доставка и се документира с
 * фактура по чл. 113 ЗДДС. Попълнена, стойността е мъртва конфигурация — и заблуждаваща, защото
 * полето се казва „приходна сметка“, а щеше да съдържа 4532. Празна, тя пази и другото: стоковият
 * бутон „Generate Sale Move“ отказва вместо да осчетоводи прихода втори път зад гърба на фактурата.
 *
 * <p><b>Файлов, нарочно.</b> Въпросът „може ли да се резолвира сметка“ има отговор от сметкоплана;
 * базата не помни какво е поискал файлът, а изключението идва едва при опит за ветилиране.
 */
class BgFixedAssetSaleVatSeedTest {

  private static final Path COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final Path MANAGEMENT = COA.resolve("account_accountManagement.csv");
  private static final Path ACCOUNTS = COA.resolve("account_account.csv");

  /** {@code AccountManagement.typeSelect}: 1 = продукт/фамилия, 2 = данък, 3 = начин на плащане. */
  private static final String TYPE_TAX = "2";

  /** {@code Account.vatSystemSelect}: 1 = стоки, 2 = услуги. */
  private static final String VAT_SYSTEM_GOODS = "1";

  @Test
  @DisplayName("всеки ред по данък носи ДДС сметка за продажба — иначе фактурата не се ветилира")
  void ддс_сметката_за_продажба_е_налице() throws IOException {
    List<String> липсващи = new ArrayList<>();
    for (Map<String, String> row : rows(MANAGEMENT)) {
      if (!TYPE_TAX.equals(row.get("typeSelect"))) {
        continue;
      }
      String vat1 = row.get("saleTaxVatSystem1Account_code");
      if (vat1 == null || vat1.isEmpty()) {
        липсващи.add(row.get("tax_code") + " (importId " + row.get("importId") + ")");
      }
    }
    assertTrue(
        липсващи.isEmpty(),
        "данъци без saleTaxVatSystem1Account: "
            + липсващи
            + ". getTaxAccount() чете точно това поле при ветилиране на клиентска фактура —"
            + " без него продажбата на дълготраен актив по чл. 113 ЗДДС не може да се осчетоводи");
  }

  @Test
  @DisplayName(
      "saleAccount на ред по данък е ПРАЗНА — приходът идва от фактурата, не от отписването")
  void превключвателят_остава_празен() throws IOException {
    List<String> попълнени = new ArrayList<>();
    for (Map<String, String> row : rows(MANAGEMENT)) {
      if (!TYPE_TAX.equals(row.get("typeSelect"))) {
        continue;
      }
      String sale = row.get("saleAccount_code");
      if (sale != null && !sale.isEmpty()) {
        попълнени.add(row.get("tax_code") + " = " + sale);
      }
    }
    assertTrue(
        попълнени.isEmpty(),
        "saleAccount на редове по данък трябва да е празна, а е: "
            + попълнени
            + ". Полето обслужва САМО превключвателя в generateSaleMove, а българското отписване не"
            + " прави sale Move — приходът и ДДС-то идват от клиентската фактура (чл. 113 ЗДДС)."
            + " Попълнена, тя е мъртва конфигурация и отваря път приходът да се осчетоводи втори"
            + " път зад гърба на фактурата");
  }

  @Test
  @DisplayName("ДДС сметката съществува и е обявена за стоки")
  void ддс_сметката_е_за_стоки() throws IOException {
    Map<String, String> vatSystemByCode = new LinkedHashMap<>();
    for (Map<String, String> account : rows(ACCOUNTS)) {
      vatSystemByCode.put(account.get("code"), account.get("vatSystemSelect"));
    }
    assertFalse(vatSystemByCode.isEmpty(), "не се чете " + ACCOUNTS);

    List<String> проблеми = new ArrayList<>();
    for (Map<String, String> row : rows(MANAGEMENT)) {
      if (!TYPE_TAX.equals(row.get("typeSelect"))) {
        continue;
      }
      String vat1 = row.get("saleTaxVatSystem1Account_code");
      if (vat1 == null || vat1.isEmpty()) {
        continue;
      }
      if (!vatSystemByCode.containsKey(vat1)) {
        проблеми.add(vat1 + " липсва в сметкоплана");
      } else if (!VAT_SYSTEM_GOODS.equals(vatSystemByCode.get(vat1))) {
        проблеми.add(
            vat1 + " е с vatSystemSelect=" + vatSystemByCode.get(vat1) + ", а не 1 (стоки)");
      }
    }
    assertTrue(
        проблеми.isEmpty(),
        "ДДС сметката за продажба трябва да съществува и да е за стоки: "
            + проблеми
            + ". Продажбата на дълготраен актив е доставка на СТОКА, тъй че ДДС-то отива в"
            + " saleTaxVatSystem1Account (набор 1). Сметка с vatSystemSelect=2 би значела услуги,"
            + " а вторият набор в българския сметкоплан е празен");
  }

  // ------------------------------------------------------------------ помощни

  private static List<Map<String, String>> rows(Path csv) throws IOException {
    assertTrue(Files.isRegularFile(csv), "не намирам " + csv);
    List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
    assertFalse(lines.isEmpty(), csv + " е празен");
    List<String> header = split(lines.get(0));
    List<Map<String, String>> out = new ArrayList<>();
    for (int i = 1; i < lines.size(); i++) {
      if (lines.get(i).trim().isEmpty()) {
        continue;
      }
      List<String> cells = split(lines.get(i));
      Map<String, String> row = new LinkedHashMap<>();
      for (int c = 0; c < header.size(); c++) {
        row.put(header.get(c), c < cells.size() ? cells.get(c) : "");
      }
      out.add(row);
    }
    return out;
  }

  /** Разделя ред с точка и запетая, зачитайки кавичките. */
  private static List<String> split(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (c == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (c == ';' && !quoted) {
        cells.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    cells.add(cell.toString());
    return cells;
  }
}
