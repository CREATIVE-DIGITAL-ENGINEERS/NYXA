package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.BankDetails;
import com.axelor.apps.base.db.City;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import com.axelor.apps.bglocalization.service.saft.adapter.BgSaftXmlWriter;
import com.axelor.apps.bglocalization.service.saft.dto.SaftAuditFile;
import com.axelor.apps.bglocalization.service.saft.dto.SaftMasterFiles;
import com.axelor.apps.bglocalization.service.saft.dto.SaftProduct;
import com.axelor.apps.bglocalization.service.saft.dto.SaftSourceDocuments;
import com.axelor.message.db.EmailAddress;
import com.axelor.studio.db.AppBgLocalization;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * МЕСЕЧНИЯТ ФАЙЛ НЕ СЕ ПРОМЕНЯ ОТ ГОДИШНИЯ И ОТ ФАЙЛА ПРИ ПОИСКВАНЕ — байт по байт.
 *
 * <p><b>⚠️ ОТПЕЧАТЪЦИТЕ СА НА МЕСЕЧНИЯ ФАЙЛ.</b> Writer-ът и Header-ът носят вид на файла и втори
 * клон на SelectionCriteria; месечният трябва да излиза точно такъв, какъвто е без тях. XSD
 * проверката не стига — тя приема и пренаредени незадължителни елементи, и другия клон на choice-а.
 *
 * <p><b>Два файла:</b> минималният и „богатият“ — Header от {@link BgSaftHeaderAssembler} с всички
 * незадължителни елементи, таблиците, дневникът и плащанията от assembler-ите, нулевата структура
 * на покупките. Заедно минават всеки клон на месечния writer, който извличането днес ползва.
 *
 * <p>Смени ли се отпечатък СЪЗНАТЕЛНО (нов елемент в месечния файл), числото тук се вдига в същия
 * PR, с причината в коментара — никога „за да мине“.
 */
class BgSaftMonthlyRegressionTest {

  /**
   * {@code toBytes(BgSaftFixtures.minimal())} — 9995 байта. S.H.1 е „1.0.2“, не „007“ — с „007“ в
   * таблицата минава отпечатъкът {@code 74b5f794…2a36} (9993 байта).
   */
  private static final String MINIMAL_SHA256 =
      "a522aba6ab22c46f26a5528b0b804af36acb1dc073295882ad6de9d44614edd9";

  /**
   * {@link #rich()} — 34 973 байта. S.H.1 е „1.0.2“, не „007“ — с „007“ в таблицата минава
   * отпечатъкът {@code 1f724849…867a} (34 971 байта). Единственият ред на дневника с данък „да се
   * потвърди“ (BG_ZRS) е 000/000000 с ГРЕШКА, а не {@code <TaxType>100</TaxType><TaxCode/>} —
   * празен TaxCode НАП отказва (QA IV.2.3, с. 95).
   */
  private static final String RICH_SHA256 =
      "8ad9554fc0a92cae89fb45c455ada1b820f54cfcdcda6b06b8db15b656a8dd64";

  @Test
  @DisplayName("Минималният месечен файл е байт по байт същият като преди F0")
  void минималният_не_се_променя() {
    byte[] xml = BgSaftXmlWriter.toBytes(BgSaftFixtures.minimal());
    assertEquals(List.of(), BgSaftFixtures.validate(xml));
    assertEquals(MINIMAL_SHA256, sha256(xml), "дължина " + xml.length);
  }

  @Test
  @DisplayName("Богатият месечен файл е байт по байт същият като преди F0")
  void богатият_не_се_променя() {
    byte[] xml = BgSaftXmlWriter.toBytes(rich());
    assertEquals(List.of(), BgSaftFixtures.validate(xml));
    assertEquals(RICH_SHA256, sha256(xml), "дължина " + xml.length);
  }

  /** Header от assembler-а, таблиците, дневникът и плащанията от assembler-ите. */
  static SaftAuditFile rich() {
    SaftMasterFiles m = new SaftMasterFiles();
    BgSaftFixtures.assembledGlAccounts().forEach(m::addGlAccount);
    BgSaftPartiesAssembler.Result parties = BgSaftFixtures.assembledParties();
    parties.getCustomers().forEach(m::addCustomer);
    parties.getSuppliers().forEach(m::addSupplier);
    BgSaftFixtures.assembledTaxTable().forEach(m::addTaxTableEntry);
    List<SaftProduct> products = BgSaftFixtures.assembledProducts();
    BgSaftFixtures.assembledUoms(products).forEach(m::addUom);
    products.forEach(m::addProduct);
    return new SaftAuditFile()
        .header(
            BgSaftHeaderAssembler.assemble(company(), app(), april(), BgSaftFixtures.DAY)
                .getHeader())
        .masterFiles(m)
        .generalLedger(BgSaftFixtures.assembledGlEntries())
        .sourceDocuments(
            new SaftSourceDocuments()
                .salesInvoices(BgSaftFixtures.invoices())
                .payments(BgSaftFixtures.assembledPayments())
                .purchaseInvoices(
                    BgSaftInvoiceFixtures.assemble(new BgSaftInvoicesAssembler.Source())
                        .getPurchases()));
  }

  static String sha256(byte[] bytes) {
    try {
      StringBuilder hex = new StringBuilder();
      for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
        hex.append(String.format("%02x", b));
      }
      return hex.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Period april() {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 4, 1));
    period.setToDate(LocalDate.of(2026, 4, 30));
    return period;
  }

  /** Попълнено дружество — всеки незадължителен елемент на Header-а има източник. */
  private static Company company() {
    Country bg = new Country();
    bg.setAlpha2Code("BG");
    City sofia = new City();
    sofia.setName("София");
    Address address = new Address();
    address.setStreetName("бул. Витоша");
    address.setBuildingNumber("1");
    address.setZip("1000");
    address.setCity(sofia);
    address.setCountry(bg);

    EmailAddress email = new EmailAddress();
    email.setAddress("office@example.bg");
    Partner partner = new Partner();
    partner.setRegistrationCode("123456789");
    partner.setTaxNbr("BG123456789");
    partner.setFixedPhone("+35921234567");
    partner.setEmailAddress(email);

    BankDetails bank = new BankDetails();
    bank.setIban("BG80BNBG96611020345678");

    BgDeclarant declarant = new BgDeclarant();
    declarant.setDeclarantNames("Иван Петров Иванов");
    declarant.setDeclarantCapacity("управител");
    declarant.setIsDefault(true);

    Company company = new Company();
    company.setName("Тест & Син ЕООД");
    company.setPartner(partner);
    company.setAddress(address);
    company.setDefaultBankDetails(bank);
    List<BgDeclarant> declarants = new ArrayList<>();
    declarants.add(declarant);
    company.setBgDeclarantList(declarants);
    return company;
  }

  private static AppBgLocalization app() {
    AppBgLocalization app = new AppBgLocalization();
    app.setSaftSoftwareCompanyName("Софтуер ООД");
    app.setSaftSoftwareId("Nyxa");
    app.setSaftSoftwareVersion("1.0");
    return app;
  }
}
