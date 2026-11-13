package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.axelor.apps.bglocalization.service.saft.dto.SaftContact;
import com.axelor.apps.bglocalization.service.saft.dto.SaftHeader;
import com.axelor.apps.bglocalization.service.saft.dto.SaftOwnership;
import com.axelor.apps.bglocalization.service.saft.dto.SaftTaxRegistration;
import com.axelor.apps.bglocalization.service.zddfl.BgDeclarationFileFinding;
import com.axelor.message.db.EmailAddress;
import com.axelor.studio.db.AppBgLocalization;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Header-ът по книгата на НАП — подразбиранията, „00“-тата и предупрежденията. */
class BgSaftHeaderAssemblerTest {

  private static final LocalDate CREATED = LocalDate.of(2026, 5, 10);

  private static Period april() {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 4, 1));
    period.setToDate(LocalDate.of(2026, 4, 30));
    return period;
  }

  /** Попълнено дружество: нула находки. */
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
    company.setName("Тест ЕООД");
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

  /**
   * Header-ът на попълненото дружество, създаден на {@code createdOn} — за таблицата на версиите.
   */
  static SaftHeader headerOn(LocalDate createdOn) {
    return BgSaftHeaderAssembler.assemble(company(), app(), april(), createdOn).getHeader();
  }

  private static Set<String> codes(BgSaftHeaderAssembler.Result result) {
    return result.getFindings().stream()
        .map(BgDeclarationFileFinding::getCode)
        .collect(Collectors.toSet());
  }

  @Test
  @DisplayName("Попълнено дружество: нула находки и стойностите по книгата")
  void попълненото_дружество() {
    BgSaftHeaderAssembler.Result result =
        BgSaftHeaderAssembler.assemble(company(), app(), april(), CREATED);
    assertEquals(List.of(), result.getFindings());
    SaftHeader h = result.getHeader();
    assertEquals("1.0.2", h.getAuditFileVersion(), "S.H.1 от таблицата: 10.05.2026 е в 1.0.2");
    assertEquals("EUR", h.getDefaultCurrencyCode());
    assertEquals("M", h.getHeaderComment());
    assertEquals("A", h.getTaxAccountingBasis(), "празната основа е A");
    assertEquals(
        List.of(4, 2026, 4, 2026),
        List.of(
            h.getPeriodStart(), h.getPeriodStartYear(), h.getPeriodEnd(), h.getPeriodEndYear()));
    assertEquals(CREATED, h.getAuditFileDateCreated());
    assertEquals("123456789", h.getCompany().getRegistrationNumber());
    assertEquals("BG80BNBG96611020345678", h.getCompany().getBankAccounts().get(0).getIbanNumber());
  }

  @Test
  @DisplayName("S.TID: номерът по ДДС, VAT_TaxType 100010, ЕИК като TaxNumber, NRA — по книгата")
  void данъчната_регистрация_е_по_книгата() {
    SaftTaxRegistration t =
        BgSaftHeaderAssembler.assemble(company(), app(), april(), CREATED)
            .getHeader()
            .getCompany()
            .getTaxRegistrations()
            .get(0);
    assertEquals("BG123456789", t.getTaxRegistrationNumber());
    assertEquals("100010", t.getTaxType());
    assertEquals("123456789", t.getTaxNumber());
    assertEquals("NRA", t.getTaxAuthority());
  }

  @Test
  @DisplayName("Без номер по ДДС няма TaxRegistration — S.TID.1 е номерът по ДДС")
  void без_ддс_номер_няма_регистрация() {
    Company company = company();
    company.getPartner().setTaxNbr(null);
    assertTrue(
        BgSaftHeaderAssembler.assemble(company, app(), april(), CREATED)
            .getHeader()
            .getCompany()
            .getTaxRegistrations()
            .isEmpty());
  }

  @Test
  @DisplayName("Не в група (празно): IsPartOfGroup 5 и „00“ в петте елемента на майката")
  void не_в_група() {
    SaftOwnership o =
        BgSaftHeaderAssembler.assemble(company(), app(), april(), CREATED)
            .getHeader()
            .getOwnership();
    assertEquals("5", o.getIsPartOfGroup());
    for (List<String> values :
        List.of(
            o.getUltimateOwnerNameCyrillicBg(),
            o.getUltimateOwnerUicBg(),
            o.getUltimateOwnerNameCyrillicForeign(),
            o.getUltimateOwnerNameLatinForeign(),
            o.getCountryForeign())) {
      assertEquals(List.of("00"), values);
    }
  }

  @Test
  @DisplayName("Централа на група (1): попълнената майка НЕ се чете — „00“ по книгата")
  void централата_пише_нули() {
    Company company = company();
    company.setBgSaftIsPartOfGroup("1");
    company.setBgSaftUltimateOwnerNameBg("Майка АД");
    SaftOwnership o =
        BgSaftHeaderAssembler.assemble(company, app(), april(), CREATED).getHeader().getOwnership();
    assertEquals("1", o.getIsPartOfGroup());
    assertEquals(List.of("00"), o.getUltimateOwnerNameCyrillicBg());
  }

  @Test
  @DisplayName("Част от местна група (3) с българска майка: чуждестранните елементи са „00“")
  void българска_майка() {
    Company company = company();
    company.setBgSaftIsPartOfGroup("3");
    company.setBgSaftUltimateOwnerNameBg("Майка АД");
    company.setBgSaftUltimateOwnerEikBg("987654321");
    BgSaftHeaderAssembler.Result result =
        BgSaftHeaderAssembler.assemble(company, app(), april(), CREATED);
    SaftOwnership o = result.getHeader().getOwnership();
    assertEquals(List.of("Майка АД"), o.getUltimateOwnerNameCyrillicBg());
    assertEquals(List.of("987654321"), o.getUltimateOwnerUicBg());
    assertEquals(List.of("00"), o.getUltimateOwnerNameLatinForeign());
    assertEquals(List.of("00"), o.getCountryForeign());
    assertEquals(List.of(), result.getFindings());
  }

  @Test
  @DisplayName("Част от група (4) без майка: ГРЕШКА, не „00“ навсякъде")
  void група_без_майка_е_грешка() {
    Company company = company();
    company.setBgSaftIsPartOfGroup("4");
    BgSaftHeaderAssembler.Result result =
        BgSaftHeaderAssembler.assemble(company, app(), april(), CREATED);
    BgDeclarationFileFinding finding = result.getFindings().get(0);
    assertEquals("SAFT_HEADER_GROUP_OWNER_MISSING", finding.getCode());
    assertTrue(finding.isBlocking());
  }

  @Test
  @DisplayName(
      "Контактът е деклараторът: първата дума е име, останалото фамилия, длъжността — OtherTitles")
  void контактът_е_деклараторът() {
    SaftContact c =
        BgSaftHeaderAssembler.assemble(company(), app(), april(), CREATED)
            .getHeader()
            .getCompany()
            .getContacts()
            .get(0);
    assertEquals("Иван", c.getContactPerson().getFirstName());
    assertEquals("Петров Иванов", c.getContactPerson().getLastName());
    assertEquals(List.of("управител"), c.getContactPerson().getOtherTitles());
    assertEquals(List.of("+35921234567"), c.getTelephones());
    assertEquals(List.of("office@example.bg"), c.getEmails());
  }

  @Test
  @DisplayName("Разделянето на имената: една, две и повече думи")
  void разделяне_на_имената() {
    assertEquals(List.of("Иван", ""), List.of(BgSaftHeaderAssembler.splitNames("Иван")));
    assertEquals(
        List.of("Иван", "Иванов"), List.of(BgSaftHeaderAssembler.splitNames(" Иван  Иванов ")));
    assertEquals(
        List.of("Иван", "Петров Иванов"),
        List.of(BgSaftHeaderAssembler.splitNames("Иван Петров Иванов")));
    assertEquals(List.of("", ""), List.of(BgSaftHeaderAssembler.splitNames("  ")));
  }

  @Test
  @DisplayName("Голото дружество: празни стойности и предупреждения, ЕИК — грешка")
  void голото_дружество() {
    Company company = new Company();
    company.setName("Голо ООД");
    BgSaftHeaderAssembler.Result result =
        BgSaftHeaderAssembler.assemble(company, null, april(), CREATED);
    assertEquals(
        Set.of(
            "SAFT_HEADER_SOFTWARE_COMPANY_MISSING",
            "SAFT_HEADER_SOFTWARE_ID_UNCONFIRMED",
            "SAFT_HEADER_SOFTWARE_VERSION_MISSING",
            "SAFT_HEADER_EIK_MISSING",
            "SAFT_HEADER_ADDRESS_MISSING",
            "SAFT_HEADER_CONTACT_MISSING",
            "SAFT_HEADER_PHONE_MISSING",
            "SAFT_HEADER_EMAIL_MISSING"),
        codes(result));
    // IBAN-ът (S.CMH.6, задължителен) е ГРЕШКА на BgSaftIbanCheck, не тук — виж
    // BgSaftIbanCheckTest; Header-ът оставя празния за проверката.
    assertEquals("", h(result).getCompany().getBankAccounts().get(0).getIbanNumber());
    long errors =
        result.getFindings().stream().filter(BgDeclarationFileFinding::isBlocking).count();
    assertEquals(1, errors, "само липсващият ЕИК блокира");
    SaftHeader h = result.getHeader();
    assertEquals("", h.getSoftwareId());
    assertEquals("BG", h.getCompany().getAddresses().get(0).getCountry());
  }

  @Test
  @DisplayName("IBAN: без интервали и с главни букви — както го иска ISO 13616")
  void iban_нормализиран() {
    Company company = company();
    company.getDefaultBankDetails().setIban(" bg80 bnbg 9661 1020 3456 78 ");
    assertEquals(
        "BG80BNBG96611020345678",
        BgSaftHeaderAssembler.assemble(company, app(), april(), CREATED)
            .getHeader()
            .getCompany()
            .getBankAccounts()
            .get(0)
            .getIbanNumber());
  }

  private static SaftHeader h(BgSaftHeaderAssembler.Result result) {
    return result.getHeader();
  }

  @Test
  @DisplayName("IBAN: при липса на основна сметка — първата активна от списъка")
  void iban_от_списъка() {
    Company company = company();
    company.setDefaultBankDetails(null);
    BankDetails closed = new BankDetails();
    closed.setIban("BG00CLOSED");
    closed.setActive(false);
    BankDetails open = new BankDetails();
    open.setIban("BG11OPEN");
    open.setActive(true);
    company.setBankDetailsList(new ArrayList<>(List.of(closed, open)));
    assertEquals(
        "BG11OPEN",
        BgSaftHeaderAssembler.assemble(company, app(), april(), CREATED)
            .getHeader()
            .getCompany()
            .getBankAccounts()
            .get(0)
            .getIbanNumber());
  }

  @Test
  @DisplayName("Дори голото дружество дава Header, който минава схемата — липсите са в находките")
  void голият_header_минава_схемата() {
    Company company = new Company();
    company.setName("Голо ООД");
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.header(BgSaftHeaderAssembler.assemble(company, null, april(), CREATED).getHeader());
    assertEquals(List.of(), BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)));
  }

  @Test
  @DisplayName("Попълненото дружество в целия файл минава схемата")
  void попълненият_header_минава_схемата() {
    SaftAuditFile file = BgSaftFixtures.minimal();
    file.header(BgSaftHeaderAssembler.assemble(company(), app(), april(), CREATED).getHeader());
    assertEquals(List.of(), BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)));
  }

  // ─────────────── годишният файл и файлът при поискване ───────────────

  @Test
  @DisplayName("Годишен Header: периоди 1–12 на годината и латинско „A“ — без дати")
  void годишният_header() {
    SaftHeader h =
        BgSaftHeaderAssembler.assemble(company(), app(), BgSaftSelection.annual(2026), CREATED)
            .getHeader();
    assertEquals(
        List.of(1, 2026, 12, 2026),
        List.of(
            h.getPeriodStart(), h.getPeriodStartYear(), h.getPeriodEnd(), h.getPeriodEndYear()));
    assertNull(h.getSelectionStartDate());
    assertNull(h.getSelectionEndDate());
    assertEquals("A", h.getHeaderComment());
    SaftAuditFile file = BgSaftFixtures.minimalAnnual().header(h);
    assertEquals(List.of(), BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)));
  }

  @Test
  @DisplayName("Header при поискване: SelectionStartDate/EndDate и „D“ — без периоди")
  void header_при_поискване() {
    LocalDate from = LocalDate.of(2026, 7, 1);
    LocalDate to = LocalDate.of(2026, 7, 15);
    SaftHeader h =
        BgSaftHeaderAssembler.assemble(
                company(), app(), BgSaftSelection.onDemand(from, to), CREATED)
            .getHeader();
    assertEquals(from, h.getSelectionStartDate());
    assertEquals(to, h.getSelectionEndDate());
    assertNull(h.getPeriodStart());
    assertNull(h.getPeriodEndYear());
    assertEquals("D", h.getHeaderComment());
    SaftAuditFile file = BgSaftFixtures.minimalOnDemand().header(h);
    assertEquals(List.of(), BgSaftFixtures.validate(BgSaftXmlWriter.toBytes(file)));
  }

  @Test
  @DisplayName("Месечен Header с период извън един месец — отказ, а не грешно име на файла")
  void месечният_извън_месеца() {
    Period quarter = new Period();
    quarter.setFromDate(LocalDate.of(2026, 4, 1));
    quarter.setToDate(LocalDate.of(2026, 6, 30));
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftHeaderAssembler.assemble(company(), app(), quarter, CREATED));
    assertThrows(
        IllegalArgumentException.class,
        () -> BgSaftHeaderAssembler.assemble(company(), app(), (BgSaftSelection) null, CREATED));
  }
}
