package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ДЛЪЖНОСТТА НА ДЛЪЖНОСТНОТО ЛИЦЕ В ПРИЛ. 9 — от полето „Длъжност“ на декларатора, не от
 * „Качество“.
 *
 * <p>Деклараторът е ИСТИНСКИ: „Качество“ = R (законен представител), както го въвежда човек. „R“
 * като длъжност XSD-то отхвърля ({@code cvc-minLength-valid}). Генерирането е истинското: емитер,
 * офлайн XSD и контроли.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgApp9DeclarantPositionDbTest extends JpaSupport {

  private BgApp9DbFixture fx;

  @BeforeEach
  void setUp() {
    fx = new BgApp9DbFixture();
    BgApp9DbFixture.loadCalendar();
  }

  /** Лист + пълно удостоверение при дружество с декларатор R / „Управител“. */
  private BgApp9Declaration declaration(AtomicReference<Company> company) {
    AtomicReference<BgApp9Declaration> out = new AtomicReference<>();
    JPA.runInTransaction(
        () -> {
          company.set(fx.company("Длъжност", LocalDate.of(2026, 9, 1)));
          BgSickLeave sick =
              fx.sickLeave(fx.contract(company.get(), "01"), LocalDate.of(2026, 10, 13));
          out.set(BgApp9DbFixture.declaration(sick));
        });
    return out.get();
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

  private static void setPosition(Company company, String position) {
    JPA.runInTransaction(() -> BgApp9DbFixture.declarantOf(company).setDeclarantPosition(position));
  }

  @Test
  @DisplayName("Истински декларатор (R + „Управител“): файлът минава XSD и носи длъжността, не „R“")
  void aRealDeclarantPassesTheSchema() throws Exception {
    AtomicReference<Company> company = new AtomicReference<>();
    BgApp9Declaration d = declaration(company);

    BgApp9Emission emission = generate(d);

    // Неотказана емисия = минали ДВЕТЕ врати: липсите и офлайн XSD + контроли (виж
    // BgApp9ExportService#generateForDeclaration — при грешка на валидатора файл не се ражда).
    assertFalse(emission.isRefused(), emission.gapReport());
    assertTrue(d.getBgNoiArtifact() != null, "файлът е закачен");
    String xml =
        new String(
            Files.readAllBytes(MetaFiles.getPath(d.getBgNoiArtifact().getMetaFile())),
            StandardCharsets.UTF_8);
    assertTrue(xml.contains("<Position>Управител</Position>"), "длъжността е от полето „Длъжност“");
    assertFalse(xml.contains("<Position>R</Position>"), "кодът на „Качество“ не е длъжност");
  }

  @Test
  @DisplayName("Празна длъжност → отказ ПОИМЕННО, сочи полето „Длъжност“; файл не се ражда")
  void anEmptyPositionIsRefusedByName() {
    AtomicReference<Company> company = new AtomicReference<>();
    BgApp9Declaration d = declaration(company);
    setPosition(company.get(), null);

    BgApp9Emission emission = generate(d);

    assertRefusedOnPosition(emission);
    assertNull(d.getBgNoiArtifact(), "без тихо попълване и без файл");
    assertEquals("RESOLVED", d.getStatusSelect(), "статусът не мърда");
  }

  @Test
  @DisplayName("Длъжност от 1 знак → отказ поименно, не неразбираемо „cvc-minLength“ от XSD-то")
  void aOneLetterPositionIsRefusedByNameNotBySchema() {
    AtomicReference<Company> company = new AtomicReference<>();
    BgApp9Declaration d = declaration(company);
    setPosition(company.get(), "У");

    BgApp9Emission emission = generate(d);

    assertRefusedOnPosition(emission);
    assertNull(d.getBgNoiArtifact());
  }

  private static void assertRefusedOnPosition(BgApp9Emission emission) {
    assertTrue(emission.isRefused());
    BgApp9Gap gap = null;
    for (BgApp9Gap g : emission.getGaps()) {
      if ("Representative/Position".equals(g.tag)) {
        gap = g;
      }
    }
    assertTrue(gap != null, "очаквах липса за длъжността: " + emission.gapReport());
    assertEquals("длъжностно лице · длъжност", gap.clause);
    assertTrue(gap.reason.contains("„Длъжност“"), "сочи полето, което се попълва: " + gap.reason);
  }
}
