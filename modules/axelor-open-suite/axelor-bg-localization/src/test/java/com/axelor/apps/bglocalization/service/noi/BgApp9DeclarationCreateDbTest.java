package com.axelor.apps.bglocalization.service.noi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.db.BgApp9Declaration;
import com.axelor.apps.bglocalization.db.BgSickLeave;
import com.axelor.apps.bglocalization.db.repo.BgSickLeaveRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * НОВО УДОСТОВЕРЕНИЕ ПО ПРИЛ. 9 — САМО ЗА ВАЛИДИРАН ЛИСТ, и това го пази УСЛУГАТА, не само бутонът.
 *
 * <p>Пътят е истинският: {@link BgApp9DeclarationService#createOrResolve} — същото, което викат
 * „Създай удостоверение (Прил. 9)“ и „Изведи/опресни“ на болничния.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgApp9DeclarationCreateDbTest extends JpaSupport {

  private BgApp9DbFixture fx;

  @BeforeEach
  void setUp() {
    fx = new BgApp9DbFixture();
    BgApp9DbFixture.loadCalendar();
  }

  private BgSickLeave sheet(String status) {
    AtomicReference<BgSickLeave> out = new AtomicReference<>();
    JPA.runInTransaction(
        () -> {
          Company company = fx.company("Създаване", LocalDate.of(2026, 9, 1));
          BgSickLeave sick = fx.sickLeave(fx.contract(company, "01"), LocalDate.of(2026, 10, 13));
          sick.setStatusSelect(status);
          out.set(Beans.get(BgSickLeaveRepository.class).save(sick));
        });
    return out.get();
  }

  private static long declarationsOf(BgSickLeave sick) {
    return JPA.em()
        .createQuery("SELECT COUNT(d) FROM BgApp9Declaration d WHERE d.sickLeave = :s", Long.class)
        .setParameter("s", sick)
        .getSingleResult();
  }

  @Test
  @DisplayName("Чернова на лист → отказ с ясен текст; удостоверение НЕ се създава")
  void aDraftSheetIsRefused() {
    BgSickLeave draft = sheet("DRAFT");

    IllegalStateException refusal =
        assertThrows(
            IllegalStateException.class,
            () -> Beans.get(BgApp9DeclarationService.class).createOrResolve(draft));

    assertEquals(
        "Удостоверение по Прил. 9 се създава, опреснява и изнася само за валидиран болничен лист."
            + " Валидирайте листа и опитайте отново.",
        refusal.getMessage());
    assertNull(draft.getBgApp9Declaration(), "листът не сочи удостоверение");
    assertEquals(0L, declarationsOf(draft), "нищо не е записано");
  }

  @Test
  @DisplayName("Валидиран лист → удостоверението се създава и обстоятелствата се извеждат")
  void aValidatedSheetGetsItsCertificate() {
    BgSickLeave validated = sheet("VALIDATED");

    BgApp9Resolution resolution =
        Beans.get(BgApp9DeclarationService.class).createOrResolve(validated);

    assertNotNull(resolution);
    BgApp9Declaration created = validated.getBgApp9Declaration();
    assertNotNull(created, "удостоверението е създадено");
    assertSame(validated, created.getSickLeave());
    assertEquals("RESOLVED", created.getStatusSelect(), "обстоятелствата са изведени");
    assertEquals(1L, declarationsOf(validated));
  }
}
