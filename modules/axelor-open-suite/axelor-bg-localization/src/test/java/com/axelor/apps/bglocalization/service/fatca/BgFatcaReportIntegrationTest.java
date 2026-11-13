package com.axelor.apps.bglocalization.service.fatca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Address;
import com.axelor.apps.base.db.AddressTemplate;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Country;
import com.axelor.apps.base.db.Currency;
import com.axelor.apps.bglocalization.db.BgFatcaAccount;
import com.axelor.apps.bglocalization.db.BgFatcaProfile;
import com.axelor.apps.bglocalization.db.BgFatcaReport;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.common.BgXsdValidator;
import com.axelor.apps.bglocalization.service.fatca.adapter.BgFatcaXsd;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.napsubmissions.common.submission.NapFilingGate;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import org.apache.shiro.authz.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * FATCA ОТЧЕТЪТ ПО ИСТИНСКИЯ ПЪТ: база, каталогът на ролите, артефактът и ръчното подаване. Данните
 * са измислени.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgFatcaReportIntegrationTest extends JpaSupport {

  private static final String GIIN = BgFatcaTestData.GIIN;
  private static final LocalDate TODAY = BgFatcaTestData.TODAY;

  private Long companyId;
  private Long otherCompanyId;
  private Long profileId;
  private Long reportId;
  private Long accountId;

  @BeforeEach
  void scene() {
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          Country bg = country("Тест BG " + tag, "BG");
          Country us = country("Тест US " + tag, "US");
          Currency usd = usd();

          Company a = company("FATCA А " + tag, "FA" + tag);
          Address address = new Address();
          address.setTownName("София");
          address.setStreetName("ул. Измислена 1");
          address.setCountry(bg);
          JPA.em().persist(address);
          a.setAddress(address);
          companyId = a.getId();
          otherCompanyId = company("FATCA Б " + tag, "FB" + tag).getId();

          BgFatcaProfile profile = new BgFatcaProfile();
          profile.setCompany(a);
          profile.setStatusSelect(BgFatcaStatus.REPORTING_FI);
          profile.setGiin(GIIN);
          JPA.em().persist(profile);
          profileId = profile.getId();

          BgFatcaReport report = new BgFatcaReport();
          report.setCompany(a);
          report.setReportYear(2025);
          JPA.em().persist(report);
          reportId = report.getId();

          BgFatcaAccount account = new BgFatcaAccount();
          account.setReport(report);
          account.setAccountNumber("TEST-ACC-" + tag);
          account.setHolderTypeSelect(BgFatcaStatus.INDIVIDUAL);
          account.setFirstName("John");
          account.setLastName("Testholder");
          account.setUsTin("000-00-0001");
          account.setCountry(us);
          account.setCity("Springfield");
          account.setBalance(new BigDecimal("100.00"));
          account.setCurrency(usd);
          account.setInterestAmount(new BigDecimal("1.25"));
          JPA.em().persist(account);
          accountId = account.getId();
        });
    BgRoleLogin.loginWrite(JPA.em().find(Company.class, companyId));
  }

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static BgFatcaReportService service() {
    return Beans.get(BgFatcaReportService.class);
  }

  private BgFatcaReport report() {
    JPA.em().clear();
    return JPA.em().find(BgFatcaReport.class, reportId);
  }

  @Test
  @DisplayName(
      "Чернова → генериран → подаден: файл по схемата, артефакт на дружеството, подаване fatca")
  void цикълът() throws Exception {
    BgFatcaReport generated = service().generate(report(), TODAY);
    assertEquals(BgFatcaStatus.GENERATED, generated.getStatusSelect());

    BgFatcaReport r = report();
    NapArtifact artifact = r.getGeneratedArtifact();
    assertNotNull(artifact);
    assertEquals(companyId, artifact.getCompany().getId());
    assertEquals(artifact.getSha256(), r.getFileSha256());
    assertEquals(Integer.valueOf(1), r.getFileAccountCount());
    assertEquals(GIIN, r.getFiGiin());
    assertTrue(r.getMessageRefId().startsWith(GIIN + "."), r.getMessageRefId());
    assertTrue(JPA.em().find(BgFatcaAccount.class, accountId).getDocRefId().startsWith(GIIN + "."));

    byte[] bytes = Files.readAllBytes(MetaFiles.getPath(r.getGeneratedFile()));
    assertTrue(
        BgXsdValidator.validate(
                bytes, BgFatcaXsd.RESOURCE, BgFatcaXsd.describe(), BgFatcaXsd.CODE_PREFIX)
            .isEmpty());
    String xml = new String(bytes, StandardCharsets.UTF_8);
    assertTrue(xml.contains("<sfa:MessageRefId>" + r.getMessageRefId() + "</sfa:MessageRefId>"));
    assertTrue(xml.contains("<sfa:City>София</sfa:City>"), xml);

    NapSubmission submission =
        service().markSubmitted(report(), " ТЕСТ-0001 ", TODAY.minusDays(1), TODAY);
    r = report();
    assertEquals(BgFatcaStatus.SUBMITTED, r.getStatusSelect());
    assertEquals("ТЕСТ-0001", r.getNapIncomingNumber());
    NapSubmission s = JPA.em().find(NapSubmission.class, submission.getId());
    assertEquals(NapSubmissionRepository.SERVICE_FATCA, s.getServiceCodeSelect());
    assertEquals(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL, s.getChannelSelect());
    assertEquals(NapSubmissionRepository.STATUS_SUBMITTED, s.getSubmissionStatusSelect());
    assertEquals("ТЕСТ-0001", s.getNapIncomingNumber());
    assertEquals(LocalDate.of(2026, 6, 30), s.getReferenceDate());
    assertEquals(BgFatcaReport.class.getName(), s.getRelatedModel());
    assertEquals(companyId, s.getCompany().getId());
    assertEquals(Capabilities.NAP_WRITE, NapFilingGate.capabilityFor(s));

    assertThrows(IllegalStateException.class, () -> service().returnToDraft(report()));
    assertThrows(IllegalStateException.class, () -> service().generate(report(), TODAY));
  }

  @Test
  @DisplayName("Отбелязване: без номер, с бъдеща дата, преди файла и за тестов файл — отказ")
  void отбелязването_отказва() throws Exception {
    assertThrows(
        IllegalStateException.class,
        () -> service().markSubmitted(report(), "ТЕСТ-1", TODAY, TODAY),
        "чернова без файл");
    service().generate(report(), TODAY);
    assertThrows(
        IllegalStateException.class, () -> service().markSubmitted(report(), " ", TODAY, TODAY));
    assertThrows(
        IllegalStateException.class,
        () -> service().markSubmitted(report(), "ТЕСТ-1", TODAY.plusDays(1), TODAY));
    assertEquals(BgFatcaStatus.GENERATED, report().getStatusSelect());

    service().returnToDraft(report());
    JPA.runInTransaction(() -> JPA.em().find(BgFatcaReport.class, reportId).setTestFile(true));
    service().generate(report(), TODAY);
    IllegalStateException test =
        assertThrows(
            IllegalStateException.class,
            () -> service().markSubmitted(report(), "ТЕСТ-1", TODAY, TODAY));
    assertTrue(test.getMessage().contains("FATCA11"), test.getMessage());
  }

  @Test
  @DisplayName("Върни в чернова: файлът се забравя, артефактът остава")
  void върни_в_чернова() throws Exception {
    service().generate(report(), TODAY);
    Long artifactId = report().getGeneratedArtifact().getId();
    BgFatcaReport r = service().returnToDraft(report());
    assertEquals(BgFatcaStatus.DRAFT, r.getStatusSelect());
    r = report();
    assertNull(r.getGeneratedFile());
    assertNull(r.getMessageRefId());
    assertNull(JPA.em().find(BgFatcaAccount.class, accountId).getDocRefId());
    assertNotNull(JPA.em().find(NapArtifact.class, artifactId), "одитната следа остава");
  }

  @Test
  @DisplayName("Без сметки — нулев отчет (NilReport)")
  void нулев_отчет() throws Exception {
    // Контекстът още държи отчета от сцената (без зареден списък) — изчиства се преди триенето.
    JPA.em().clear();
    JPA.runInTransaction(() -> JPA.em().remove(JPA.em().find(BgFatcaAccount.class, accountId)));
    service().generate(report(), TODAY);
    BgFatcaReport r = report();
    assertEquals(Integer.valueOf(0), r.getFileAccountCount());
    String xml =
        new String(
            Files.readAllBytes(MetaFiles.getPath(r.getGeneratedFile())), StandardCharsets.UTF_8);
    assertTrue(xml.contains("<ftc:NoAccountToReport>yes</ftc:NoAccountToReport>"), xml);
  }

  @Test
  @DisplayName("FATCA102 спира генерирането — без файл, без артефакт, отчетът остава чернова")
  void fatca102_спира() {
    JPA.runInTransaction(
        () -> {
          BgFatcaAccount a = JPA.em().find(BgFatcaAccount.class, accountId);
          a.setHolderTypeSelect(BgFatcaStatus.ORGANISATION);
          a.setOrganisationName("Test Holding LLC");
          a.setAcctHolderTypeSelect("FATCA102");
        });
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> service().generate(report(), TODAY));
    assertTrue(e.getMessage().contains("FATCA_SUBSTANTIAL_OWNERS_UNSUPPORTED"), e.getMessage());
    BgFatcaReport r = report();
    assertEquals(BgFatcaStatus.DRAFT, r.getStatusSelect());
    assertNull(r.getGeneratedArtifact());
  }

  @Test
  @DisplayName("НФЧП не подава отчет — генерирането отказва")
  void нфчп_не_подава() {
    JPA.runInTransaction(
        () ->
            JPA.em()
                .find(BgFatcaProfile.class, profileId)
                .setStatusSelect(BgFatcaStatus.ACTIVE_NFFE));
    IllegalStateException e =
        assertThrows(IllegalStateException.class, () -> service().generate(report(), TODAY));
    assertTrue(e.getMessage().contains("финансова институция за отчитане"), e.getMessage());
    assertNull(report().getGeneratedArtifact());
  }

  @Test
  @DisplayName("Изолация: потребител на Б не чете и не пише отчета на А")
  void изолация() {
    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(JPA.em().find(Company.class, otherCompanyId));
    BgFatcaReport r = report();
    assertThrows(
        UnauthorizedException.class, () -> CapabilityGate.requireActiveCompany(r.getCompany()));
    JpaSecurity security = Beans.get(JpaSecurity.class);
    assertThrows(
        UnauthorizedException.class,
        () -> security.check(JpaSecurity.CAN_READ, BgFatcaReport.class, reportId));
    assertThrows(
        UnauthorizedException.class,
        () -> security.check(JpaSecurity.CAN_WRITE, BgFatcaReport.class, reportId));
    assertThrows(
        UnauthorizedException.class,
        () -> security.check(JpaSecurity.CAN_WRITE, BgFatcaAccount.class, accountId));
    assertThrows(
        UnauthorizedException.class,
        () -> security.check(JpaSecurity.CAN_READ, BgFatcaProfile.class, profileId));
  }

  @Test
  @DisplayName("Роля само за четене: чете, но гейтът и платформата отказват запис")
  void само_четене() {
    BgRoleLogin.logout();
    BgRoleLogin.loginRead(JPA.em().find(Company.class, companyId));
    JpaSecurity security = Beans.get(JpaSecurity.class);
    security.check(JpaSecurity.CAN_READ, BgFatcaReport.class, reportId);
    assertThrows(
        UnauthorizedException.class,
        () -> CapabilityGate.requireRole("generate a FATCA report file", Capabilities.NAP_WRITE));
    assertThrows(
        UnauthorizedException.class,
        () -> security.check(JpaSecurity.CAN_WRITE, BgFatcaReport.class, reportId));
  }

  @Test
  @DisplayName("Едно дружество, една година, един отчет; друго дружество — свой")
  void уникалност() {
    assertThrows(
        RuntimeException.class,
        () ->
            JPA.runInTransaction(
                () -> {
                  BgFatcaReport second = new BgFatcaReport();
                  second.setCompany(JPA.em().find(Company.class, companyId));
                  second.setReportYear(2025);
                  JPA.em().persist(second);
                  JPA.em().flush();
                }));
    // Отказалият запис не бива да остане в контекста и да се опита пак при следващия flush.
    JPA.em().clear();
    BgRoleLogin.logout();
    BgRoleLogin.loginWrite(JPA.em().find(Company.class, otherCompanyId));
    JPA.runInTransaction(
        () -> {
          BgFatcaReport other = new BgFatcaReport();
          other.setCompany(JPA.em().find(Company.class, otherCompanyId));
          other.setReportYear(2025);
          JPA.em().persist(other);
        });
  }

  private static Company company(String name, String code) {
    Company company = new Company();
    company.setName(name);
    company.setCode(code);
    JPA.em().persist(company);
    return company;
  }

  private static Country country(String name, String alpha2) {
    AddressTemplate template = new AddressTemplate();
    template.setName(name);
    template.setEngineSelect(1);
    template.setTemplateStr("$addressL4$");
    JPA.em().persist(template);
    Country country = new Country();
    country.setName(name);
    country.setAlpha2Code(alpha2);
    country.setAddressTemplate(template);
    JPA.em().persist(country);
    return country;
  }

  private static Currency usd() {
    Currency usd =
        JPA
            .em()
            .createQuery("SELECT c FROM Currency c WHERE c.codeISO = 'USD'", Currency.class)
            .getResultList()
            .stream()
            .findFirst()
            .orElse(null);
    if (usd == null) {
      usd = new Currency();
      usd.setName("Тест USD");
      usd.setCode("$");
      usd.setCodeISO("USD");
      JPA.em().persist(usd);
    }
    return usd;
  }
}
