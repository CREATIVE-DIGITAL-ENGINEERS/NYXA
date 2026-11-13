package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * АДРЕСЪТ ЗА КОРЕСПОНДЕНЦИЯ НА РЕГИСТРИРАНОТО ЛИЦЕ — едното правило за ДДС (ППЗДДС чл. 117, ал. 2,
 * т. 1; Прил. № 13, секция А; Прил. № 15, VTR поле 4).
 */
class BgVatRegisteredAddressTest {

  private static Address address(String fullName) {
    Address a = new Address();
    a.setFullName(fullName);
    return a;
  }

  private static Company company(Address address, Address correspondence) {
    Company c = new Company();
    c.setAddress(address);
    c.setBgVatCorrespondenceAddress(correspondence);
    return c;
  }

  @Test
  @DisplayName("Попълнен „Адрес за кореспонденция“ → той, а не „Адрес“")
  void theCorrespondenceAddressWins() {
    Company c = company(address("гр. София, ул. Седалище 1"), address("гр. Пловдив, ПК 22"));
    assertEquals("гр. Пловдив, ПК 22", BgVatRegisteredAddress.of(c));
    assertNull(BgVatRegisteredAddress.refusal(c));
  }

  @Test
  @DisplayName("Празен „Адрес за кореспонденция“ → „Адрес“ на дружеството (изричното правило)")
  void anEmptyCorrespondenceAddressMeansTheCompanyAddress() {
    assertEquals(
        "гр. София, ул. Седалище 1",
        BgVatRegisteredAddress.of(company(address("гр. София, ул. Седалище 1"), null)));
    assertEquals(
        "гр. София, ул. Седалище 1",
        BgVatRegisteredAddress.of(company(address("гр. София, ул. Седалище 1"), address("  "))));
  }

  @Test
  @DisplayName("⚠️ Партньорът НЕ се чете: дружество без адреси → отказ, който назовава полето")
  void thePartnerIsNotRead() {
    Company c = company(null, null);
    Partner p = new Partner();
    p.setMainAddress(address("гр. Варна, адрес на партньора"));
    c.setPartner(p);
    assertNull(BgVatRegisteredAddress.of(c));
    String refusal = BgVatRegisteredAddress.refusal(c);
    assertTrue(refusal.contains("Дружество › „Адрес“ (или „Адрес за кореспонденция“)"), refusal);
  }

  @Test
  @DisplayName("Над 200 знака → отказ, който назовава полето; точно 200 минава (без тихо рязане)")
  void moreThan200CharactersIsRefusedByName() {
    String exactly200 = repeat('а', 200);
    assertNull(BgVatRegisteredAddress.refusal(company(address(exactly200), null)));

    String refusal = BgVatRegisteredAddress.refusal(company(null, address(repeat('б', 201))));
    assertTrue(refusal.contains("201 знака"), refusal);
    assertTrue(refusal.contains("„Адрес за кореспонденция“"), "назовава полето, от което идва");
  }

  @Test
  @DisplayName("Новият ред във „Пълно име“ се брои като интервал — както във файла")
  void newlinesCountAsTheFileWritesThem() {
    assertEquals(
        "гр. София ул. Раковски 1",
        BgVatRegisteredAddress.of(company(address("гр. София\nул. Раковски 1"), null)));
  }

  private static String repeat(char c, int n) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < n; i++) {
      sb.append(c);
    }
    return sb.toString();
  }
}
