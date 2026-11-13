package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.AccountRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.MappingRow;
import com.axelor.apps.bglocalization.service.saft.BgSaftGlAccountsAssembler.Result;
import com.axelor.apps.bglocalization.service.saft.dto.SaftGlAccount;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** GeneralLedgerAccounts с числа: мапинг, навиване, изключване, обхват по QA, знак и закръгляне. */
class BgSaftGlAccountsAssemblerTest {

  private final List<AccountRow> chart = new ArrayList<>();
  private final Map<String, MappingRow> mappings = new HashMap<>();
  private final Map<Long, BgSaftBalance> balances = new LinkedHashMap<>();

  private long account(long id, String code, String ap, Long parent) {
    chart.add(new AccountRow(id, code, "Сметка " + code, ap, parent));
    return id;
  }

  private void mapped(String code, String nraCode) {
    mappings.put(code, new MappingRow(nraCode, false));
  }

  private void excluded(String code) {
    mappings.put(code, new MappingRow(null, true));
  }

  private void balance(long id, String opening, String debit, String credit) {
    balances.put(
        id,
        new BgSaftBalance(new BigDecimal(opening), new BigDecimal(debit), new BigDecimal(credit)));
  }

  private Result assemble() {
    return assemble(false, false);
  }

  private Result assemble(boolean postedBeforeYear, boolean openingMoveInYear) {
    return BgSaftGlAccountsAssembler.assemble(
        chart,
        mappings,
        BgNraNomenclatures.accounts().keySet(),
        balances,
        postedBeforeYear,
        openingMoveInYear);
  }

  private static SaftGlAccount row(Result result, String taxpayerAccountId) {
    return result.getAccounts().stream()
        .filter(a -> a.getTaxpayerAccountId().equals(taxpayerAccountId))
        .findFirst()
        .orElseThrow(() -> new AssertionError("няма ред за " + taxpayerAccountId));
  }

  private static Optional<BgDeclarationFileFinding> finding(Result result, String code) {
    return result.getFindings().stream().filter(f -> f.getCode().equals(code)).findFirst();
  }

  private static List<String> codes(Result result) {
    return result.getFindings().stream()
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toList());
  }

  @Test
  @DisplayName("Собствен код: салдо със знак, крайното = начално + дебит − кредит, нула находки")
  void собствен_код() {
    mapped("411", "411");
    balance(account(1, "411", "1", null), "100", "50", "30");
    mapped("401", "401");
    balance(account(2, "401", "2", null), "-200", "0", "100");

    Result result = assemble();
    assertEquals(List.of(), result.getFindings());
    SaftGlAccount customers = row(result, "411");
    assertEquals("411", customers.getAccountId());
    assertEquals("Сметка 411", customers.getAccountDescription());
    assertEquals("Active", customers.getAccountType());
    assertEquals(new BigDecimal("100.00"), customers.getOpeningBalance());
    assertEquals(new BigDecimal("120.00"), customers.getClosingBalance());
    // Кредитното салдо е отрицателно — writer-ът го пише като CreditBalance.
    assertEquals(new BigDecimal("-200.00"), row(result, "401").getOpeningBalance());
    assertEquals(new BigDecimal("-300.00"), row(result, "401").getClosingBalance());
    assertEquals("Passive", row(result, "401").getAccountType());
  }

  @Test
  @DisplayName("Навиване: 1221 и 1222 са два реда с AccountID 122 и собствени салда")
  void навиване() {
    mapped("1221", "122");
    mapped("1222", "122");
    balance(account(1, "1222", "2", null), "-5", "0", "0");
    balance(account(2, "1221", "2", null), "-7", "0", "1");

    Result result = assemble();
    assertEquals(List.of(), result.getFindings());
    assertEquals(2, result.getAccounts().size());
    assertEquals("1221", result.getAccounts().get(0).getTaxpayerAccountId(), "подредени по код");
    assertEquals("122", result.getAccounts().get(0).getAccountId());
    assertEquals("122", result.getAccounts().get(1).getAccountId());
    assertEquals(new BigDecimal("-8.00"), row(result, "1221").getClosingBalance());
    assertEquals(new BigDecimal("-5.00"), row(result, "1222").getClosingBalance());
  }

  @Test
  @DisplayName("Изключена сметка не излиза; с оборот — предупреждение, само със салдо — мълчи")
  void изключена() {
    excluded("9100");
    balance(account(1, "9100", null, null), "0", "40", "0");
    excluded("9200");
    balance(account(2, "9200", null, null), "500", "0", "0");
    mapped("411", "411");
    balance(account(3, "411", "1", null), "0", "40", "0");

    Result result = assemble();
    assertEquals(1, result.getAccounts().size());
    BgDeclarationFileFinding warning = finding(result, "SAFT_GLA_EXCLUDED_WITH_MOVEMENT").get();
    assertFalse(warning.isBlocking());
    assertTrue(warning.getMessage().contains("9100"));
    assertFalse(warning.getMessage().contains("9200"), "салдо без оборот не е движение");
  }

  @Test
  @DisplayName("Без собствен ред: кодът на първия мапнат родител, с предупреждение")
  void през_родител() {
    mapped("411", "411");
    long parent = account(1, "411", "1", null);
    balance(account(2, "4111", "1", parent), "0", "10", "0");

    Result result = assemble();
    assertEquals("411", row(result, "4111").getAccountId());
    assertEquals(List.of("SAFT_GLA_MAPPED_VIA_PARENT"), codes(result));
    assertTrue(finding(result, "SAFT_GLA_MAPPED_VIA_PARENT").get().getMessage().contains("4111"));
  }

  @Test
  @DisplayName("Първият мапнат родител е изключен — сметката е изключена с него")
  void изключен_родител() {
    excluded("B020");
    long node = account(1, "B020", null, null);
    balance(account(2, "4991", "1", node), "0", "10", "0");

    Result result = assemble();
    assertTrue(result.getAccounts().isEmpty());
    assertTrue(
        finding(result, "SAFT_GLA_EXCLUDED_WITH_MOVEMENT").get().getMessage().contains("B020"));
  }

  @Test
  @DisplayName("Без мапнат родител — ГРЕШКА и празен AccountID, който схемата отказва")
  void без_мапинг() {
    balance(account(1, "ZZZ", "1", null), "0", "10", "0");
    // Цикъл в родителите не бива да зацикли.
    account(2, "X", "1", 3L);
    balance(account(3, "Y", "1", 2L), "1", "0", "0");

    Result result = assemble();
    assertEquals("", row(result, "ZZZ").getAccountId());
    assertEquals("", row(result, "Y").getAccountId());
    BgDeclarationFileFinding error = finding(result, "SAFT_GLA_UNMAPPED").get();
    assertTrue(error.isBlocking());
    assertTrue(error.getMessage().contains("ZZZ"));
  }

  @Test
  @DisplayName("Код, който го няма в номенклатурата, или празен неизключен ред — ГРЕШКА")
  void невалиден_код() {
    mapped("4999", "777");
    balance(account(1, "4999", "1", null), "0", "10", "0");
    mapped("4998", "");
    balance(account(2, "4998", "1", null), "0", "10", "0");

    Result result = assemble();
    // По една находка на сметка, с указател към нея (G6a).
    List<BgDeclarationFileFinding> errors =
        result.getFindings().stream()
            .filter(f -> f.getCode().equals("SAFT_GLA_NRA_CODE_INVALID"))
            .collect(Collectors.toList());
    assertEquals(2, errors.size());
    assertTrue(errors.stream().allMatch(BgDeclarationFileFinding::isBlocking));
    assertTrue(
        errors.stream()
            .anyMatch(
                f ->
                    f.getMessage().endsWith("4999 → „777“")
                        && BgSaftRef.ACCOUNT.equals(f.getTargetModel())
                        && Long.valueOf(1).equals(f.getTargetId())));
    assertTrue(
        errors.stream()
            .anyMatch(f -> f.getMessage().endsWith("4998 → „“") && f.getTargetId() == 2L));
  }

  @Test
  @DisplayName("Обхват по QA: салдо ≠ 0 в началото или края, или оборот — иначе сметката не излиза")
  void обхват() {
    mapped("503", "503");
    balance(account(1, "503", "1", null), "0", "0", "0");
    mapped("702", "702");
    balance(account(2, "702", "2", null), "0", "10", "10");
    mapped("101", "101");
    balance(account(3, "101", "2", null), "-1000", "0", "0");
    mapped("411", "411");
    balance(account(4, "411", "1", null), "0", "5", "0");
    mapped("401", "401");
    account(5, "401", "2", null);

    Result result = assemble();
    List<String> emitted =
        result.getAccounts().stream()
            .map(SaftGlAccount::getTaxpayerAccountId)
            .collect(Collectors.toList());
    assertEquals(List.of("101", "411", "702"), emitted);
    assertEquals(BigDecimal.ZERO.setScale(2), row(result, "702").getClosingBalance());
  }

  @Test
  @DisplayName("Три знака → два, HALF_UP; находка само когато сума е променена")
  void закръгляне() {
    mapped("411", "411");
    balance(account(1, "411", "1", null), "10.005", "0.004", "0");
    mapped("401", "401");
    balance(account(2, "401", "2", null), "-120.000", "0", "0");

    Result result = assemble();
    assertEquals(new BigDecimal("10.01"), row(result, "411").getOpeningBalance());
    assertEquals(new BigDecimal("10.01"), row(result, "411").getClosingBalance());
    assertEquals(new BigDecimal("-120.00"), row(result, "401").getOpeningBalance());
    BgDeclarationFileFinding warning = finding(result, "SAFT_GLA_ROUNDED").get();
    assertTrue(warning.getMessage().contains("411"));
    assertFalse(warning.getMessage().contains("401"), "-120.000 не е променено от закръглянето");
  }

  @Test
  @DisplayName(
      "А/П ос → Active/Passive/Bifunctional в ASCII; празна → Bifunctional с предупреждение")
  void видът() {
    assertEquals("Active", BgSaftGlAccountsAssembler.accountType("1"));
    assertEquals("Passive", BgSaftGlAccountsAssembler.accountType("2"));
    assertEquals("Bifunctional", BgSaftGlAccountsAssembler.accountType("3"));
    assertEquals(null, BgSaftGlAccountsAssembler.accountType(null));
    assertEquals(null, BgSaftGlAccountsAssembler.accountType(""));
    for (String type : List.of("Active", "Passive", "Bifunctional")) {
      assertTrue(type.chars().allMatch(c -> c < 128), "само ASCII: " + type);
    }

    mapped("123", "123");
    balance(account(1, "123", null, null), "-3", "0", "0");
    Result result = assemble();
    assertEquals("Bifunctional", row(result, "123").getAccountType());
    assertTrue(
        finding(result, "SAFT_GLA_ACCOUNT_TYPE_DEFAULTED").get().getMessage().contains("123"));
  }

  @Test
  @DisplayName("Осчетоводявания от минали години без откриваща статия — предупреждение")
  void откриваща_статия() {
    mapped("411", "411");
    balance(account(1, "411", "1", null), "0", "1", "0");
    assertTrue(finding(assemble(true, false), "SAFT_GLA_NO_OPENING_MOVE").isPresent());
    assertFalse(finding(assemble(true, true), "SAFT_GLA_NO_OPENING_MOVE").isPresent());
    assertFalse(
        finding(assemble(false, false), "SAFT_GLA_NO_OPENING_MOVE").isPresent(),
        "първата година няма какво да пренася");
  }

  @Test
  @DisplayName("Нито една сметка — ГРЕШКА: схемата иска поне една")
  void празно() {
    BgDeclarationFileFinding error = finding(assemble(), "SAFT_GLA_EMPTY").get();
    assertTrue(error.isBlocking());
  }

  @Test
  @DisplayName("Сума по сметка извън сметкоплана на дружеството — ГРЕШКА, не тих пропуск")
  void чужда_сметка() {
    balance(99, "0", "10", "0");
    Result result = assemble();
    assertTrue(finding(result, "SAFT_GLA_UNMAPPED").get().getMessage().contains("#99"));
  }
}
