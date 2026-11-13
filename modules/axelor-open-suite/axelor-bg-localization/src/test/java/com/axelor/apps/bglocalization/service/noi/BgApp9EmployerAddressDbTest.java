package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.meta.MetaFiles;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * АДРЕСЪТ НА ОСИГУРИТЕЛЯ — от {@code Company.address} (полето „Адрес“ на дружеството), единствен
 * източник, без резерв към партньора.
 *
 * <p>Случаят: дружеството има адрес „София“ (ЕКАТТЕ 68134), а партньорът му няма нито един адрес.
 * Прил. 9 не чете партньора, затова не отказва „няма адрес на осигурителя“.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgApp9EmployerAddressDbTest extends JpaSupport {

  private BgApp9DbFixture fx;

  @BeforeEach
  void setUp() {
    fx = new BgApp9DbFixture();
    BgApp9DbFixture.loadCalendar();
  }

  private <T> T tx(Supplier<T> work) {
    AtomicReference<T> out = new AtomicReference<>();
    JPA.runInTransaction(() -> out.set(work.get()));
    return out.get();
  }

  private BgApp9Declaration declarationFor(Company company) {
    return tx(
        () -> {
          BgSickLeave sick = fx.sickLeave(fx.contract(company, "01"), LocalDate.of(2026, 10, 13));
          return BgApp9DbFixture.declaration(sick);
        });
  }

  private static BgApp9Emission generate(BgApp9Declaration declaration) {
    AtomicReference<BgApp9Emission> out = new AtomicReference<>();
    JPA.runInTransaction(
        () -> {
          try {
            out.set(Beans.get(BgApp9ExportService.class).generateForDeclaration(declaration));
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
    return out.get();
  }

  @Test
  @DisplayName("Дружество с адрес, партньор без адрес → файлът се генерира и носи София (столица)")
  void theCompanyAddressIsEnoughWhenThePartnerHasNone() throws Exception {
    Company company = tx(() -> fx.company("Нюкса Тест", LocalDate.of(2026, 9, 1)));
    assertNull(company.getPartner().getMainAddress(), "котвата: партньорът е без адрес");
    BgApp9Declaration d = declarationFor(company);

    BgApp9Emission emission = generate(d);

    assertFalse(emission.isRefused(), emission.gapReport());
    String xml =
        new String(
            Files.readAllBytes(MetaFiles.getPath(d.getBgNoiArtifact().getMetaFile())),
            StandardCharsets.UTF_8);
    assertTrue(xml.contains("<BulRegion>София (столица)</BulRegion>"), "областта на осигурителя");
    assertTrue(xml.contains("<BulCity>София</BulCity>"));
  }

  @Test
  @DisplayName(
      "Празен „Адрес“ на дружеството → отказ, който сочи точното поле; партньорът НЕ се чете")
  void anEmptyCompanyAddressIsRefusedNamingTheFieldEvenIfThePartnerHasOne() {
    Company company =
        tx(
            () -> {
              Company c = fx.company("Без адрес", LocalDate.of(2026, 9, 1));
              c.setAddress(null);
              // Партньорът ИМА адрес — и това не бива да спаси файла тихо.
              c.getPartner().setMainAddress(fx.address());
              return c;
            });
    BgApp9Declaration d = declarationFor(company);

    BgApp9Emission emission = generate(d);

    assertTrue(emission.isRefused());
    BgApp9Gap gap =
        emission.getGaps().stream()
            .filter(g -> "BulAddressFull".equals(g.tag))
            .findFirst()
            .orElse(null);
    assertNotNull(gap, emission.gapReport());
    assertTrue(gap.reason.contains("Дружество › поле „Адрес“"), gap.reason);
    assertNull(d.getBgNoiArtifact(), "файл не се ражда");
  }
}
