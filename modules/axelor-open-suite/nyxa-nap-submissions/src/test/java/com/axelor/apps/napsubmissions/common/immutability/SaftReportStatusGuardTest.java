package com.axelor.apps.napsubmissions.common.immutability;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.napsubmissions.db.NapArtifact;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * РИСК 6: SAF-T ОТЧЕТЪТ НЕ МИНАВА В READY ПРЯКО — правилото на {@link SaftReportStatusGuard} по
 * старото и новото състояние (Hibernate пазачът е само обвивка; пътят през REST е в интеграционния
 * тест на bg-localization).
 */
class SaftReportStatusGuardTest {

  private static final String[] NAMES = {
    "name",
    "company",
    "period",
    "reportTypeSelect",
    "lifecycleStatusSelect",
    "readinessStatusSelect",
    "generatedArtifact",
    "schemaVersion"
  };

  private static <T extends com.axelor.db.Model> T ref(T model, long id) {
    model.setId(id);
    return model;
  }

  private static final Company A = ref(new Company(), 1L);
  private static final Company B = ref(new Company(), 2L);
  private static final Period OCT = ref(new Period(), 10L);
  private static final Period NOV = ref(new Period(), 11L);
  private static final NapArtifact FILE = ref(new NapArtifact(), 7L);
  private static final NapArtifact FILE2 = ref(new NapArtifact(), 8L);

  private static Object[] state(
      Company company,
      Period period,
      String lifecycle,
      String readiness,
      NapArtifact file,
      String schema) {
    return new Object[] {"SAFT-1", company, period, "monthly", lifecycle, readiness, file, schema};
  }

  private static Object[] draft() {
    return state(A, OCT, "draft", "not_checked", null, null);
  }

  @Test
  @DisplayName("Извън работния поток: draft → ready, readiness → ready, файл, S.H.1 — отказ")
  void статусът_не_се_пише_пряко() {
    Object[] before = draft();
    for (Object[] after :
        new Object[][] {
          state(A, OCT, "ready", "not_checked", null, null),
          state(A, OCT, "draft", "ready", null, null),
          state(A, OCT, "draft", "not_checked", FILE, null),
          state(A, OCT, "draft", "not_checked", null, "1.0.3")
        }) {
      RegulatoryImmutabilityException e =
          assertThrows(
              RegulatoryImmutabilityException.class,
              () -> SaftReportStatusGuard.checkUpdate(NAMES, before, after, false));
      assertTrue(e.getMessage().contains("„Провери“ и „Генерирай“"), e.getMessage());
    }
  }

  @Test
  @DisplayName("В работния поток: статусът, файлът и S.H.1 се пишат")
  void работният_поток_пише() {
    assertDoesNotThrow(
        () ->
            SaftReportStatusGuard.checkUpdate(
                NAMES, draft(), state(A, OCT, "generated", "ready", FILE, "1.0.2"), true));
  }

  @Test
  @DisplayName("Името и другите полета — свободни и извън потока")
  void другите_полета_са_свободни() {
    Object[] after = draft();
    after[0] = "SAFT-2";
    assertDoesNotThrow(() -> SaftReportStatusGuard.checkUpdate(NAMES, draft(), after, false));
  }

  @Test
  @DisplayName("Дружеството не се сменя след създаването — и от работния поток")
  void дружеството_е_неизменно() {
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            SaftReportStatusGuard.checkUpdate(
                NAMES, draft(), state(B, OCT, "draft", "not_checked", null, null), true));
  }

  @Test
  @DisplayName("Месецът — свободен до генерирането, заключен след него")
  void месецът_след_генериране() {
    assertDoesNotThrow(
        () ->
            SaftReportStatusGuard.checkUpdate(
                NAMES, draft(), state(A, NOV, "draft", "not_checked", null, null), false));
    Object[] generated = state(A, OCT, "generated", "ready", FILE, "1.0.2");
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            SaftReportStatusGuard.checkUpdate(
                NAMES, generated, state(A, NOV, "generated", "ready", FILE, "1.0.2"), true));
  }

  @Test
  @DisplayName("Подмяна на генерирания файл извън потока — отказ")
  void файлът_не_се_подменя() {
    Object[] generated = state(A, OCT, "generated", "ready", FILE, "1.0.2");
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            SaftReportStatusGuard.checkUpdate(
                NAMES, generated, state(A, OCT, "generated", "ready", FILE2, "1.0.2"), false));
  }

  @Test
  @DisplayName("Нов отчет: само draft / not_checked / без файл — освен от работния поток")
  void нов_отчет() {
    assertDoesNotThrow(() -> SaftReportStatusGuard.checkInsert(NAMES, draft(), false));
    assertDoesNotThrow(
        () ->
            SaftReportStatusGuard.checkInsert(NAMES, state(A, OCT, null, null, null, null), false));
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            SaftReportStatusGuard.checkInsert(
                NAMES, state(A, OCT, "ready", "ready", null, null), false));
    assertThrows(
        RegulatoryImmutabilityException.class,
        () ->
            SaftReportStatusGuard.checkInsert(
                NAMES, state(A, OCT, "draft", "not_checked", FILE, null), false));
    assertDoesNotThrow(
        () ->
            SaftReportStatusGuard.checkInsert(
                NAMES, state(A, OCT, "ready", "ready", null, null), true));
  }

  @Test
  @DisplayName("Обхватът на потока е само за тялото си и се връща след изключение")
  void обхватът() throws Exception {
    assertFalse(SaftReportStatusGuard.allowed());
    assertTrue(SaftReportStatusGuard.allow(SaftReportStatusGuard::allowed));
    assertThrows(
        IllegalStateException.class,
        () ->
            SaftReportStatusGuard.allow(
                () -> {
                  throw new IllegalStateException("x");
                }));
    assertFalse(SaftReportStatusGuard.allowed());
  }

  // ─────────────── годишният и при поискване (T1) ───────────────

  private static final String[] T1_NAMES = {
    "company",
    "reportTypeSelect",
    "reportYear",
    "fromDate",
    "toDate",
    "napRequestNumber",
    "napRequestDate",
    "generatedArtifact"
  };

  private static Object[] t1(NapArtifact file) {
    return new Object[] {
      A,
      "on_demand",
      2025,
      LocalDate.of(2026, 7, 1),
      LocalDate.of(2026, 7, 15),
      "ИСК-1",
      LocalDate.of(2026, 8, 3),
      file
    };
  }

  @Test
  @DisplayName(
      "Годината, датите и искането — свободни до генерирането, заключени след него (и в потока)")
  void годината_датите_и_искането_след_генериране() {
    Object[][] changes = {
      {2, 2026},
      {3, LocalDate.of(2026, 7, 2)},
      {4, LocalDate.of(2026, 7, 31)},
      {5, "ИСК-2"},
      {6, LocalDate.of(2026, 8, 4)}
    };
    for (Object[] change : changes) {
      int index = (Integer) change[0];
      Object[] draftAfter = t1(null);
      draftAfter[index] = change[1];
      assertDoesNotThrow(
          () -> SaftReportStatusGuard.checkUpdate(T1_NAMES, t1(null), draftAfter, false),
          T1_NAMES[index] + " преди генериране");
      Object[] generatedAfter = t1(FILE);
      generatedAfter[index] = change[1];
      RegulatoryImmutabilityException e =
          assertThrows(
              RegulatoryImmutabilityException.class,
              () -> SaftReportStatusGuard.checkUpdate(T1_NAMES, t1(FILE), generatedAfter, true),
              T1_NAMES[index] + " след генериране");
      assertTrue(e.getMessage().contains(T1_NAMES[index]), e.getMessage());
    }
  }
}
