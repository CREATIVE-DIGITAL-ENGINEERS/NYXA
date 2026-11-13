package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgApp9Export;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * КОЙ ЕИК В КОЕ ПОЛЕ — сверено с Приложение № 1 на НОИ (03/2024), лист {@code Pril9_Boln}, и с
 * коментарите в {@code Pril9.xsd}:
 *
 * <ul>
 *   <li>{@code BULSTATKasa} — „ЕИК/код по БУЛСТАТ на осигурител/дружество/самоосигуряващ се/
 *       осигурителна каса“, „контролира се винаги да е попълнен“ → при {@code FlagKasa=0} е ЕИК-ът
 *       на ДРУЖЕСТВОТО;
 *   <li>{@code BULSTAT} — „…член на осигурителна каса“: „Попълва се, когато представянето е чрез
 *       осигурителна каса (FlagKasa=1)“ → при {@code FlagKasa=0} е ПРАЗЕН;
 *   <li>{@code Source} — „код по БУЛСТАТ на разработчик на софтуера – 9, 11 или 13 цифри“ → ЕИК-ът
 *       на РАЗРАБОТЧИКА, не на дружеството.
 * </ul>
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgApp9InsurerSourceDbTest extends JpaSupport {

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

  private BgApp9Declaration declarationOf(Company company) {
    return tx(
        () -> {
          BgSickLeave sick = fx.sickLeave(fx.contract(company, "01"), LocalDate.of(2026, 10, 13));
          return BgApp9DbFixture.declaration(sick);
        });
  }

  private BgApp9Emission generate(BgApp9Declaration declaration) {
    return tx(
        () -> {
          try {
            return Beans.get(BgApp9ExportService.class).generateForDeclaration(declaration);
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private BgApp9Emission generate(BgApp9Export export) {
    return tx(
        () -> {
          try {
            return Beans.get(BgApp9ExportService.class).generate(export);
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }

  private static String xmlOf(com.axelor.apps.napsubmissions.db.NapArtifact artifact)
      throws Exception {
    return new String(
        Files.readAllBytes(MetaFiles.getPath(artifact.getMetaFile())), StandardCharsets.UTF_8);
  }

  /** Стойността на елемент; празен ({@code <X></X>} или {@code <X/>}) → „“. */
  private static String leaf(String xml, String tag) {
    Matcher m = Pattern.compile("<" + tag + ">([^<]*)</" + tag + ">").matcher(xml);
    if (m.find()) {
      return m.group(1);
    }
    return xml.contains("<" + tag + "/>") ? "" : null;
  }

  @Test
  @DisplayName(
      "FlagKasa=0 → BULSTATKasa = ЕИК на дружеството, BULSTAT празен, Source = разработчикът")
  void eachEikGoesToItsField() throws Exception {
    Company company = tx(() -> fx.company("Без каса", LocalDate.of(2026, 9, 1)));
    BgApp9Declaration d = declarationOf(company);

    BgApp9Emission emission = generate(d);

    assertFalse(emission.isRefused(), emission.gapReport());
    String xml = xmlOf(d.getBgNoiArtifact());
    assertEquals("0", leaf(xml, "FlagKasa"));
    assertEquals(BgApp9Fixture.COMPANY_BULSTAT, leaf(xml, "BULSTATKasa"), "осигурителят");
    assertEquals("", leaf(xml, "BULSTAT"), "член на каса — само при FlagKasa=1");
    assertEquals(BgApp9Fixture.DEVELOPER_EIK, leaf(xml, "Source"), "разработчикът на софтуера");
  }

  @Test
  @DisplayName("Празна настройка → отказ, който назовава настройката; дружеството не я подменя")
  void anEmptySettingIsRefusedNotReplacedByTheCompany() {
    Company company = tx(() -> fx.company("Без разработчик", LocalDate.of(2026, 9, 1)));
    BgApp9Declaration d = declarationOf(company);
    tx(() -> BgApp9DbFixture.developerEik(null));
    try {
      BgApp9Emission emission = generate(d);

      assertTrue(emission.isRefused());
      BgApp9Gap gap =
          emission.getGaps().stream().filter(g -> "Source".equals(g.tag)).findFirst().orElse(null);
      assertNotNull(gap, emission.gapReport());
      assertTrue(gap.reason.contains(BgApp9MandatoryPoints.SOURCE_WHERE), gap.reason);
      assertNull(d.getBgNoiArtifact(), "ЕИК-ът на дружеството НЕ подменя празния флаг");
    } finally {
      tx(() -> BgApp9DbFixture.developerEik(BgApp9Fixture.DEVELOPER_EIK));
    }
  }

  @Test
  @DisplayName(
      "Износ със стар флаг = ЕИК на дружеството → файлът носи разработчика, а полето се поправя")
  void anOldCompanyFlagOnTheExportNoLongerWins() throws Exception {
    Company company = tx(() -> fx.company("Стар флаг", LocalDate.of(2026, 9, 1)));
    declarationOf(company);
    BgApp9Export export =
        tx(
            () -> {
              BgApp9Export e = new BgApp9Export();
              e.setName("Октомври " + fx.tag);
              e.setCompany(company);
              e.setStatusSelect("DRAFT");
              e.setNssiRoCode(22);
              e.setNssiRoName("София-град");
              e.setBgPeriodFrom(LocalDate.of(2026, 10, 1));
              e.setBgPeriodTo(LocalDate.of(2026, 10, 31));
              // Каквото би стояло, ако някой го е въвел на ръка по стария help текст.
              e.setBgSourceFlag(BgApp9Fixture.COMPANY_BULSTAT);
              JPA.em().persist(e);
              return e;
            });

    BgApp9Emission emission = generate(export);

    assertFalse(emission.isRefused(), emission.gapReport());
    assertEquals(BgApp9Fixture.DEVELOPER_EIK, leaf(xmlOf(export.getBgNoiArtifact()), "Source"));
    assertEquals(
        BgApp9Fixture.DEVELOPER_EIK,
        export.getBgSourceFlag(),
        "полето показва какво е излязло във файла");
  }
}
