package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.service.BankDetailsServiceImpl;
import com.axelor.apps.bglocalization.service.saft.dto.SaftBankAccount;
import com.axelor.apps.bglocalization.service.saft.dto.SaftCompanyHeader;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * IBAN НА ПОДАВАЩИЯ (S.CMH.6 / S.BA.1) — ЗАДЪЛЖИТЕЛЕН И ВАЛИДЕН ПО ISO 13616.
 *
 * <p>Книгата, лист „5. Structures“: S.CMH.6 BankAccountStructure — Mandatory; S.BA.1 IBANNumber —
 * Mandatory, „Валидиране срещу IBAN-ISO13616-1997“ (за BG: 22 знака, 4a,6n,8c). Протокол
 * 30TST000014738: празният {@code <IBANNumber/>} е отказан („посочен невалиден IBAN“).
 *
 * <p>Проверката е на Axelor — {@code BankDetailsService.validateIban} (iban4j: дължина по държава,
 * знаци, контролни цифри mod-97); тук е истинската реализация, не заместител.
 */
class BgSaftIbanCheckTest {

  private static final Function<String, String> REFUSAL =
      BgSaftIbanCheck.refusal(new BankDetailsServiceImpl());

  private static SaftCompanyHeader company(String... ibans) {
    SaftCompanyHeader company = new SaftCompanyHeader();
    for (String iban : ibans) {
      company.addBankAccount(new SaftBankAccount().ibanNumber(iban));
    }
    return company;
  }

  private static List<String> codes(SaftCompanyHeader company) {
    return BgSaftIbanCheck.check(company, REFUSAL).stream()
        .peek(f -> assertTrue(f.isBlocking(), f.toString()))
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toList());
  }

  @Test
  @DisplayName("Валиден BG и FR IBAN — нула находки")
  void валидният() {
    assertEquals(List.of(), codes(company("BG80BNBG96611020345678")));
    assertEquals(List.of(), codes(company("FR1420041010050500013M02606")));
  }

  @Test
  @DisplayName("Без сметка или с празен IBAN — ГРЕШКА: S.CMH.6 е задължителна")
  void липсващият() {
    assertEquals(List.of("SAFT_HEADER_IBAN_MISSING"), codes(company()));
    assertEquals(List.of("SAFT_HEADER_IBAN_MISSING"), codes(company("")));
    assertEquals(List.of("SAFT_HEADER_IBAN_MISSING"), codes(company("  ")));
  }

  @Test
  @DisplayName("Контролни цифри, дължина и вид на знаците — ГРЕШКА с IBAN-а в съобщението")
  void невалидният() {
    // контролните цифри (mod-97)
    assertEquals(List.of("SAFT_HEADER_IBAN_INVALID"), codes(company("BG81BNBG96611020345678")));
    // 21 знака вместо 22
    assertEquals(List.of("SAFT_HEADER_IBAN_INVALID"), codes(company("BG80BNBG9661102034567")));
    // цифра там, където BG иска буква (кода на банката, 4a)
    assertEquals(List.of("SAFT_HEADER_IBAN_INVALID"), codes(company("BG80BNB196611020345678")));
    BgDeclarationFileFinding f =
        BgSaftIbanCheck.check(company("BG81BNBG96611020345678"), REFUSAL).get(0);
    assertTrue(f.getMessage().contains("BG81BNBG96611020345678"), f.getMessage());
  }

  @Test
  @DisplayName("Нормализацията: без интервали, главни букви — преди проверката и във файла")
  void нормализацията() {
    assertEquals(
        "BG80BNBG96611020345678", BgSaftIbanCheck.normalize(" bg80 bnbg 9661 1020 3456 78 "));
    assertNull(BgSaftIbanCheck.normalize("   "));
    assertNull(
        BgSaftIbanCheck.refusal(new BankDetailsServiceImpl()).apply("BG80BNBG96611020345678"));
  }
}
