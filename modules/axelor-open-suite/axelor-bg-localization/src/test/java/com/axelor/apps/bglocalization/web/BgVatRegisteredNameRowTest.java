package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.repo.PartnerRepository;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.service.vat.BgVatFileWriter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatDeclarationAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatSalesAdapter;
import com.axelor.apps.bglocalization.service.vat.adapter.BgVatViesAdapter;
import com.axelor.apps.bglocalization.service.vat.dto.BgVatExportRequestDto;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ИМЕТО НА РЕГИСТРИРАНОТО ЛИЦЕ В DEKLAR.TXT (позиции 16–65) И ВЪВ VIES.TXT (ред VTR) — от партньора
 * на дружеството, с правната форма, не {@code Company.name} — краткото вътрешно име без „ООД“, с
 * което portal.nra.bg не зарежда файловете. Фиксирани очаквани редове (590 / 368 знака във VTR),
 * CP1251 + CRLF. Измислени данни.
 */
class BgVatRegisteredNameRowTest {

  private static final Charset CP1251 = Charset.forName("windows-1251");
  private static final String ZERO = " ".repeat(11) + "0.00";

  private final BgVatReturnController controller = new BgVatReturnController();

  private static String left(String value, int width) {
    return value + " ".repeat(width - value.length());
  }

  private static BgVatReturn vatReturn() {
    Partner partner = new Partner();
    partner.setPartnerTypeSelect(PartnerRepository.PARTNER_TYPE_COMPANY);
    partner.setName("Демо Търговия ООД");
    partner.setFullName("P0001 - Демо Търговия ООД");
    partner.setTaxNbr("BG000000107");
    Address address = new Address();
    address.setFullName("гр. Демо, ул. Примерна 1");
    Company company = new Company();
    company.setName("Демо"); // краткото вътрешно име — не е за файла
    company.setPartner(partner);
    company.setAddress(address);
    BgVatReturn r = new BgVatReturn();
    r.setCompany(company);
    r.setPeriod("202610");
    return r;
  }

  private static BgDeclarant declarant() {
    BgDeclarant d = new BgDeclarant();
    d.setDeclarantEgn("0000000000");
    d.setDeclarantNames("Демо Декларатор");
    d.setDeclarantCapacity("R");
    d.setDeclarantCity("гр. Демо");
    d.setDeclarantPostCode("0000");
    d.setDeclarantAddress("ул. Примерна 1");
    return d;
  }

  @Test
  @DisplayName("DEKLAR: регистрираното име на дружеството с правната форма — фиксиран ред")
  void declarationCarriesTheRegisteredName() throws Exception {
    BgVatExportRequestDto dto = controller.toRequest(vatReturn(), declarant());
    assertEquals("Демо Търговия ООД", dto.getCompanyName());

    List<String> lines =
        new BgVatDeclarationAdapter(new BgVatSalesAdapter())
            .buildLines(
                Collections.emptyList(),
                Collections.emptyList(),
                new BgVatDeclarationAdapter.Header(
                    dto.getCompanyVatNumber(),
                    dto.getCompanyName(),
                    "202610",
                    dto.getDeclarantSubmitter()),
                new BgVatDeclarationAdapter.ManualCells(
                    BigDecimal.ZERO, null, null, null, null, null));

    String expected =
        left("BG000000107", 15)
            + left("Демо Търговия ООД", 50)
            + "202610"
            + left("0000000000/Демо Декларатор", 50)
            + " ".repeat(14)
            + "0"
            + " ".repeat(14)
            + "0"
            + ZERO.repeat(21)
            + "0.00"
            + ZERO.repeat(8);
    assertEquals(590, expected.length());
    assertEquals(Collections.singletonList(expected), lines);
    assertArrayEquals(
        (expected + "\r\n").getBytes(CP1251), new BgVatFileWriter().toBytes(lines, "cp1251"));
  }

  @Test
  @DisplayName("VIES: редът VTR носи регистрираното име — фиксиран ред")
  void viesVtrCarriesTheRegisteredName() throws Exception {
    BgVatExportRequestDto dto = controller.toRequest(vatReturn(), declarant());

    List<String> lines =
        new BgVatViesAdapter(new BgVatSalesAdapter())
            .buildLines(
                Collections.emptyList(),
                new BgVatViesAdapter.RegisteredPerson(
                    dto.getCompanyVatNumber(), dto.getCompanyName(), dto.getCompanyAddress()),
                new BgVatViesAdapter.Declarant(
                    dto.getDeclarantEgn(),
                    dto.getDeclarantNames(),
                    dto.getDeclarantCity(),
                    dto.getDeclarantPostCode(),
                    dto.getDeclarantAddress(),
                    dto.getDeclarantCapacity()),
                "10/2026");

    String expected =
        "VTR"
            + left("BG000000107", 15)
            + left("Демо Търговия ООД", 150)
            + left("гр. Демо, ул. Примерна 1", 200);
    assertEquals(368, expected.length());
    String vtr = lines.stream().filter(l -> l.startsWith("VTR")).findFirst().get();
    assertEquals(expected, vtr);
    byte[] file = new BgVatFileWriter().toBytes(lines, "cp1251");
    String decoded = new String(file, CP1251);
    assertEquals(true, decoded.contains(expected + "\r\n"));
  }

  @Test
  @DisplayName("Дружество без партньор — Company.name, за да не остане полето празно")
  void companyWithoutPartnerFallsBackToItsName() {
    BgVatReturn r = vatReturn();
    r.getCompany().setPartner(null);
    assertEquals("Демо", controller.toRequest(r, declarant()).getCompanyName());
  }
}
