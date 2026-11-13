package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.City;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОБЛАСТ, ОБЩИНА И НАСЕЛЕНО МЯСТО ОТ ГРАДА (ЕКАТТЕ).
 *
 * <p>Случаят: адресът сочи „София — Столична — София (столица) — 1000“ (ЕКАТТЕ 68134), а свободните
 * полета на адреса (област, населено място) са празни. Областта не се чете от {@code
 * Address.department} или {@code City.department} — и двете са празни и износът би отказал с „поне
 * ОБЛАСТ и НАСЕЛЕНО МЯСТО“.
 */
class BgApp9PlaceTest {

  /** Адрес от заварена база: град от номенклатурата, свободните полета празни. */
  private static Address sampleAddress() {
    Address address = new Address();
    address.setCity(BgApp9Fixture.sofia());
    address.setAddressL4("бул. Витоша 1");
    return address;
  }

  @Test
  @DisplayName(
      "Адрес от номенклатурата дава област „София (столица)“, община „Столична“, място „София“")
  void theTest07AddressYieldsRegionMunicipalityAndSettlement() {
    BgApp9Place place = BgApp9Place.of(sampleAddress(), "осигурителя");
    assertTrue(place.isUsable(), String.valueOf(place.refusal));
    assertEquals("София (столица)", place.region);
    assertEquals("Столична", place.municipality);
    assertEquals("София", place.settlement);
  }

  @Test
  @DisplayName("Градът решава, свободният текст на адреса — не: друга „област“ в адреса не влиза")
  void theCityWinsOverFreeText() {
    Address address = sampleAddress();
    address.setDepartment("Пловдив");
    address.setTownName("Асеновград");
    BgApp9Place place = BgApp9Place.of(address, "осигурителя");
    assertEquals("София (столица)", place.region);
    assertEquals("София", place.settlement);
  }

  @Test
  @DisplayName("Без адрес, без град и без ЕКАТТЕ — отказ ПОИМЕННО, нито една догадка")
  void eachMissingPieceIsRefusedByName() {
    BgApp9Place none = BgApp9Place.of(null, "лицето");
    assertFalse(none.isUsable());
    assertTrue(none.refusal.contains("няма адрес на лицето"), none.refusal);

    Address freeTextOnly = new Address();
    freeTextOnly.setDepartment("София-град");
    freeTextOnly.setTownName("София");
    BgApp9Place noCity = BgApp9Place.of(freeTextOnly, "лицето");
    assertFalse(noCity.isUsable(), "свободният текст не се чете");
    assertTrue(noCity.refusal.contains("изберете „Град“"), noCity.refusal);
    assertNull(noCity.region);

    Address foreign = new Address();
    City nonBg = new City();
    nonBg.setName("Ниш");
    foreign.setCity(nonBg);
    BgApp9Place noEkatte = BgApp9Place.of(foreign, "осигурителя");
    assertFalse(noEkatte.isUsable());
    assertTrue(noEkatte.refusal.contains("няма ЕКАТТЕ"), noEkatte.refusal);
    assertTrue(noEkatte.refusal.contains("Ниш"), "назовава града");
  }

  @Test
  @DisplayName("Отказът назовава точното поле и формата, където се попълва")
  void theRefusalNamesTheExactField() {
    BgApp9Place employer = BgApp9Place.of(null, "осигурителя", BgApp9Place.EMPLOYER_WHERE);
    assertTrue(employer.refusal.contains("Дружество › поле „Адрес“"), employer.refusal);
    Address noCity = new Address();
    BgApp9Place person = BgApp9Place.of(noCity, "лицето", BgApp9Place.PERSON_WHERE);
    assertTrue(person.refusal.contains("Служител › „Контакт“"), person.refusal);
  }

  @Test
  @DisplayName("Заглавната част: причината от четеца влиза в отказа за BulAddressFull")
  void theHeaderGapCarriesTheReadersReason() {
    BgApp9EmitterContext context =
        BgApp9EmitterContext.builder()
            .flagDelegated("0")
            .flagKasa("0")
            .bulstatKasa(BgApp9Fixture.COMPANY_BULSTAT)
            .bulName("Никса ЕООД")
            .bulstatMember("")
            .nssiRo(Integer.valueOf(22), "София-град")
            .address(null, null, null, null, null, null, null, null)
            .addressProblem("населеното място „Ниш“ на осигурителя няма ЕКАТТЕ код")
            .representative("Иван", "Георгиев", "Петров", "Управител", "София")
            .build();
    BgApp9Gap gap =
        BgApp9MandatoryPoints.ofHeader(context).stream()
            .filter(g -> "BulAddressFull".equals(g.tag))
            .findFirst()
            .get();
    assertTrue(gap.reason.contains("няма ЕКАТТЕ"), gap.reason);
  }

  @Test
  @DisplayName("Лицето: адрес с град от номенклатурата минава; без град — отказ поименно")
  void thePersonsAddressGoesThroughTheSameReader() {
    BgApp9Declaration ok = BgApp9Fixture.declaration();
    assertFalse(hasPersonAddressGap(ok), "адресът на фикстурата сочи град с ЕКАТТЕ");

    BgApp9Declaration bad = BgApp9Fixture.declaration();
    bad.getSickLeave()
        .getEmploymentContract()
        .getEmployee()
        .getContactPartner()
        .getMainAddress()
        .setCity(null);
    assertTrue(hasPersonAddressGap(bad));
  }

  private static boolean hasPersonAddressGap(BgApp9Declaration declaration) {
    List<BgApp9Point9.Month> months = new ArrayList<>();
    for (BgApp9Gap gap : BgApp9MandatoryPoints.ofDocument(declaration, months)) {
      if ("Insured/InsuredAddress".equals(gap.tag)) {
        return true;
      }
    }
    return false;
  }
}
