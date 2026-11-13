package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Export;
import com.axelor.apps.bglocalization.db.BgDeclarant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * АДРЕСЪТ НА ОСИГУРИТЕЛЯ В ЗАГЛАВНАТА ЧАСТ — {@link BgApp9ExportService#contextFor} без база.
 *
 * <p>Същото като {@code BgApp9EmployerAddressDbTest}, но тук върви и в CI ({@code gradlew build}):
 * трите четения от базата (последният износ, деклараторът, ЕИК-ът на разработчика) са подменени, а
 * адресът минава по истинския път — {@code Company.address} → {@link BgApp9Place}.
 */
class BgApp9EmployerContextTest {

  /** Услугата без база: трите четения от базата са подменени. */
  private static final BgApp9ExportService SERVICE =
      new BgApp9ExportService(null, null) {
        @Override
        protected BgApp9Export latestExportOf(Company company) {
          throw new AssertionError("износът е подаден — последният не се търси");
        }

        @Override
        public BgDeclarant declarantOf(Company company) {
          throw new AssertionError("деклараторът е на износа — този по подразбиране не се търси");
        }

        @Override
        protected String softwareDeveloperEik() {
          return BgApp9Fixture.DEVELOPER_EIK;
        }
      };

  private static BgApp9Export exportOf(Company company) {
    BgDeclarant declarant = new BgDeclarant();
    declarant.setDeclarantNames("Иван Георгиев Петров");
    declarant.setDeclarantPosition("Управител");
    declarant.setDeclarantCity("София");
    BgApp9Export export = new BgApp9Export();
    export.setCompany(company);
    export.setNssiRoCode(22);
    export.setNssiRoName("София-град");
    export.setBgDeclarant(declarant);
    return export;
  }

  @Test
  @DisplayName("Дружество с „Адрес“, партньор без адрес → заглавната част носи София (столица)")
  void theCompanyAddressFillsTheHeaderWhenThePartnerHasNone() {
    Company company = BgApp9Fixture.company();
    company.setAddress(BgApp9Fixture.address());
    company.getPartner().setMainAddress(null);

    BgApp9EmitterContext context = SERVICE.contextFor(company, exportOf(company));

    assertNull(context.bulAddressProblem, context.bulAddressProblem);
    assertEquals("София (столица)", context.bulRegion);
    assertEquals("Столична", context.bulSubRegion);
    assertEquals("София", context.bulCity);
    assertEquals(BgApp9Fixture.COMPANY_BULSTAT, context.bulstatKasa);
    assertEquals(BgApp9Fixture.DEVELOPER_EIK, context.source);
  }

  @Test
  @DisplayName(
      "Празен „Адрес“ на дружеството → назован отказ към полето; адресът на партньора НЕ се"
          + " чете")
  void anEmptyCompanyAddressIsRefusedEvenIfThePartnerHasOne() {
    Company company = BgApp9Fixture.company();
    company.setAddress(null);
    // Партньорът ИМА адрес (BgApp9Fixture.company) — и той не бива да спаси заглавната част тихо.

    BgApp9EmitterContext context = SERVICE.contextFor(company, exportOf(company));

    assertTrue(
        context.bulAddressProblem != null
            && context.bulAddressProblem.contains(BgApp9Place.EMPLOYER_WHERE),
        String.valueOf(context.bulAddressProblem));
    assertNull(context.bulRegion, "без резерв към партньора");
    assertNull(context.bulCity);
  }
}
