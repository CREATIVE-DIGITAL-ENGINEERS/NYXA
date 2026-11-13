package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * АДРЕСИТЕ ВЪВ VIES.TXT — какво спира ГЕНЕРИРАНЕТО и какво влиза във файла.
 *
 * <p>ППЗДДС чл. 117, ал. 2: т. 1 — адрес за кореспонденция на регистрираното лице (ред VTR); т. 2 —
 * адрес за кореспонденция на подаващия (ред VDR: град, пощенски код, адрес). И двете се искат при
 * генериране — файлът ги носи, а празен ред не е „по-късно ще се попълни“.
 */
class BgVatReturnAddressGenerationTest {

  private final BgVatReturnController controller = new BgVatReturnController();

  private static Address address(String fullName) {
    Address a = new Address();
    a.setFullName(fullName);
    return a;
  }

  private static BgVatReturn vatReturn(Address companyAddress, Address correspondence) {
    Company company = new Company();
    company.setName("Нюкса Тест ЕООД");
    company.setAddress(companyAddress);
    company.setBgVatCorrespondenceAddress(correspondence);
    BgVatReturn r = new BgVatReturn();
    r.setCompany(company);
    r.setPeriod("202610");
    return r;
  }

  private static BgDeclarant declarant(String city, String postCode, String address) {
    BgDeclarant d = new BgDeclarant();
    d.setDeclarantEgn("8505151039");
    d.setDeclarantNames("Иван Георгиев Петров");
    d.setDeclarantCapacity("R");
    d.setDeclarantCity(city);
    d.setDeclarantPostCode(postCode);
    d.setDeclarantAddress(address);
    return d;
  }

  private static BgDeclarant fullDeclarant() {
    return declarant("София", "1000", "ул. Раковски 1");
  }

  @Test
  @DisplayName("VTR носи адреса за кореспонденция на дружеството")
  void theVtrRowCarriesTheAddress() {
    BgVatReturn r = vatReturn(address("гр. София, ул. Седалище 1"), null);
    BgVatExportRequestDto dto = controller.toRequest(r, fullDeclarant());
    assertEquals("гр. София, ул. Седалище 1", dto.getCompanyAddress());

    List<String> lines =
        new BgVatViesAdapter(new BgVatSalesAdapter())
            .buildLines(
                new ArrayList<>(),
                new BgVatViesAdapter.RegisteredPerson(
                    "BG831919536", dto.getCompanyName(), dto.getCompanyAddress()),
                new BgVatViesAdapter.Declarant(
                    dto.getDeclarantEgn(),
                    dto.getDeclarantNames(),
                    dto.getDeclarantCity(),
                    dto.getDeclarantPostCode(),
                    dto.getDeclarantAddress(),
                    dto.getDeclarantCapacity()),
                "10/2026");
    String vtr = lines.stream().filter(l -> l.startsWith("VTR")).findFirst().get();
    assertTrue(vtr.contains("гр. София, ул. Седалище 1"), vtr);
    assertTrue(controller.generationRefusals(r, dto).isEmpty());
  }

  @Test
  @DisplayName("Попълнен „Адрес за кореспонденция“ → той влиза във VTR, а не „Адрес“")
  void theCorrespondenceAddressGoesIntoTheFile() {
    BgVatReturn r = vatReturn(address("гр. София, седалище"), address("гр. Пловдив, ПК 22"));
    assertEquals(
        "гр. Пловдив, ПК 22", controller.toRequest(r, fullDeclarant()).getCompanyAddress());
  }

  @Test
  @DisplayName("Празен адрес на регистрираното лице → отказ при ГЕНЕРИРАНЕ, сочи полето")
  void anEmptyRegisteredAddressRefusesGeneration() {
    BgVatReturn r = vatReturn(null, null);
    List<String> refusals =
        controller.generationRefusals(r, controller.toRequest(r, fullDeclarant()));
    assertEquals(1, refusals.size(), refusals.toString());
    assertTrue(refusals.get(0).contains("Дружество › „Адрес“ (или „Адрес за кореспонденция“)"));
  }

  @Test
  @DisplayName("Адрес над 200 знака → отказ при генериране, не изрязан VTR")
  void anOverlongAddressRefusesGeneration() {
    StringBuilder longText = new StringBuilder();
    for (int i = 0; i < 201; i++) {
      longText.append('а');
    }
    BgVatReturn r = vatReturn(address(longText.toString()), null);
    List<String> refusals =
        controller.generationRefusals(r, controller.toRequest(r, fullDeclarant()));
    assertEquals(1, refusals.size(), refusals.toString());
    assertTrue(refusals.get(0).contains("201 знака"), refusals.get(0));
  }

  @Test
  @DisplayName("Празен адрес на подаващия (VDR) → отказ при генериране, назовава липсващите полета")
  void anEmptyDeclarantAddressRefusesGeneration() {
    BgVatReturn r = vatReturn(address("гр. София, ул. Седалище 1"), null);
    List<String> refusals =
        controller.generationRefusals(r, controller.toRequest(r, declarant(null, "1000", " ")));
    assertEquals(1, refusals.size(), refusals.toString());
    String refusal = refusals.get(0);
    assertTrue(refusal.contains("„Град“") && refusal.contains("„Адрес“"), refusal);
    assertTrue(refusal.contains("Дружество › „Декларатори“"), refusal);
  }
}
