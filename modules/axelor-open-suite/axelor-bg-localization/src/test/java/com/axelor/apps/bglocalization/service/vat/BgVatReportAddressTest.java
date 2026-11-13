package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * АДРЕСЪТ ЗА КОРЕСПОНДЕНЦИЯ НА РЕГИСТРИРАНОТО ЛИЦЕ ВЪРХУ ПЕЧАТНИЯ ОБРАЗЕЦ (Прил. № 13, секция А).
 *
 * <p>⚠️ ЗАЩО ИЗОБЩО Е ТЕСТ. Празната снимка е нормално състояние, а BIRT отхвърля празното за
 * задължителен параметър — дотук целият отчет падаше с ParameterValidationException още преди
 * първата заявка. Празно → празен низ, не изключение.
 *
 * <p>⚠️ РЕДЪТ. Снимката е ПЪРВА, защото отчетът твърди „report == TXT“: показва подаденото, не
 * днешното. Без снимка — {@code BgVatRegisteredAddress}, ЕДИНСТВЕНОТО правило за ДДС: „Адрес за
 * кореспонденция“ на дружеството, иначе „Адрес“. ⚠️ СМЯНА СПРЯМО ДОСЕГА: партньорът на дружеството
 * вече НЕ се чете. Тестът заковаваше „партньор първи, Company.address втори“ — тоест печатът и VIES
 * (който четеше само партньора) даваха два отговора на един въпрос; сега отговорът е един.
 */
class BgVatReportAddressTest {

  private final BgVatReportPrintService service = new BgVatReportPrintService();

  private static Address address(String fullName) {
    Address a = new Address();
    a.setFullName(fullName);
    return a;
  }

  private static Company company(
      Address partnerAddress, Address companyAddress, Address correspondence) {
    Company c = new Company();
    if (partnerAddress != null) {
      Partner p = new Partner();
      p.setMainAddress(partnerAddress);
      c.setPartner(p);
    }
    c.setAddress(companyAddress);
    c.setBgVatCorrespondenceAddress(correspondence);
    return c;
  }

  private static BgVatReturn vatReturn(String snapshot, Company company) {
    BgVatReturn r = new BgVatReturn();
    r.setCompanyAddress(snapshot);
    r.setCompany(company);
    return r;
  }

  @Test
  @DisplayName("Снимката бие живия адрес — отчетът показва ПОДАДЕНОТО, не днешното")
  void theSnapshotWins() {
    Company c =
        company(
            address("гр. Пловдив, ул. Нова 2"),
            address("гр. Варна, бул. Стар 3"),
            address("гр. Бургас, ПК 5"));
    assertEquals(
        "гр. София, ул. Раковски 1",
        service.companyAddress(vatReturn("гр. София, ул. Раковски 1", c)));
  }

  @Test
  @DisplayName("Празна снимка → „Адрес“ на дружеството, НЕ партньорският (смяна спрямо досега)")
  void anEmptySnapshotUsesTheCompanyAddressNotThePartner() {
    Company c =
        company(address("гр. Пловдив, ул. Нова 2"), address("гр. Варна, бул. Стар 3"), null);
    assertEquals("гр. Варна, бул. Стар 3", service.companyAddress(vatReturn(null, c)));
    assertEquals("гр. Варна, бул. Стар 3", service.companyAddress(vatReturn("", c)));
    assertEquals("гр. Варна, бул. Стар 3", service.companyAddress(vatReturn("   ", c)));
  }

  @Test
  @DisplayName("Празна снимка и попълнен „Адрес за кореспонденция“ → той")
  void theCorrespondenceAddressWinsOverTheCompanyAddress() {
    Company c = company(null, address("гр. Варна, бул. Стар 3"), address("гр. Бургас, ПК 5"));
    assertEquals("гр. Бургас, ПК 5", service.companyAddress(vatReturn(null, c)));
  }

  @Test
  @DisplayName("⚠️ Само партньорски адрес → празно: партньорът не е източник")
  void aPartnerOnlyAddressIsNotUsed() {
    assertEquals(
        "", service.companyAddress(vatReturn(null, company(address("гр. Пловдив"), null, null))));
  }

  @Test
  @DisplayName("⚠️ Фирма БЕЗ никакъв адрес дава празно, а НЕ изключение — отчетът пак се рендира")
  void aCompanyWithoutAnyAddressRendersEmpty() {
    assertEquals("", service.companyAddress(vatReturn(null, company(null, null, null))));
    assertEquals("", service.companyAddress(vatReturn(null, null)));
  }
}
