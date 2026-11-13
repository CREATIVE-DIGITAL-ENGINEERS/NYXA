package com.axelor.apps.bglocalization.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.EventsPlanning;
import com.axelor.apps.base.db.EventsPlanningLine;
import com.axelor.apps.bglocalization.db.BgVatReturn;
import com.axelor.apps.bglocalization.db.repo.BgVatReturnRepository;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.service.vat.BgVatFiledPeriods;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.rpc.ActionRequest;
import com.axelor.rpc.ActionResponse;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * КОРИГИРАЩАТА ПРЕЗ ИСТИНСКИЯ КОНТРОЛЕР И ИСТИНСКАТА БАЗА: контролерът само вика услугата — тук се
 * доказва, че я вика. Пазачът на „Генерирай“ и предупреждението на „Създай корекция“ (с празника от
 * календара на дружеството), плюс отворената коригираща за протоколите по чл. 117.
 *
 * <p>Днешният ден е истинският ({@code AppBaseService.getTodayDate}); периодите са избрани така, че
 * срокът им вече е изтекъл.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgVatCorrectionFlowIntegrationTest extends JpaSupport {

  @AfterEach
  void logout() {
    BgRoleLogin.logout();
  }

  private static Company company(String tag, LocalDate holiday) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          Company company = new Company();
          company.setName("Корекция " + tag);
          company.setCode("VC" + tag);
          company.setTimezone("Europe/Sofia");
          if (holiday != null) {
            EventsPlanning planning = new EventsPlanning();
            planning.setName("Празници " + tag);
            JPA.em().persist(planning);
            EventsPlanningLine line = new EventsPlanningLine();
            line.setEventsPlanning(planning);
            line.setDate(holiday);
            line.setYear(holiday.getYear());
            JPA.em().persist(line);
            company.setPublicHolidayEventsPlanning(planning);
          }
          JPA.em().persist(company);
          id[0] = company.getId();
        });
    return JPA.em().find(Company.class, id[0]);
  }

  private static BgVatReturn vatReturn(
      Company company, String period, String status, BgVatReturn corrects) {
    Long[] id = new Long[1];
    JPA.runInTransaction(
        () -> {
          BgVatReturn r = new BgVatReturn();
          r.setCompany(JPA.em().find(Company.class, company.getId()));
          r.setPeriod(period);
          r.setStatusSelect(status);
          r.setCorrectsPrevious(
              corrects == null ? null : JPA.em().find(BgVatReturn.class, corrects.getId()));
          JPA.em().persist(r);
          id[0] = r.getId();
        });
    return JPA.em().find(BgVatReturn.class, id[0]);
  }

  /** Действието на формата върху записа — контекстът е като от формата; отговорът като текст. */
  private static String call(BiConsumer<ActionRequest, ActionResponse> action, BgVatReturn record) {
    Map<String, Object> context = new HashMap<>();
    context.put("id", record.getId());
    context.put("version", record.getVersion());
    context.put("_model", BgVatReturn.class.getName());
    context.put("statusSelect", record.getStatusSelect());
    context.put("period", record.getPeriod());
    context.put("company", Map.of("id", record.getCompany().getId()));
    Map<String, Object> data = new HashMap<>();
    data.put("context", context);
    ActionRequest request = new ActionRequest();
    request.setModel(BgVatReturn.class.getName());
    request.setData(data);
    ActionResponse response = new ActionResponse();
    JPA.runInTransaction(() -> action.accept(request, response));
    return String.valueOf(response.getData());
  }

  private static BgVatReturnController controller() {
    return Beans.get(BgVatReturnController.class);
  }

  /** Записът, който „Създай корекция“ отваря ({@code _showRecord} в отговора). */
  private static BgVatReturn opened(String data) {
    Matcher m = Pattern.compile("_showRecord=(\\d+)").matcher(data);
    assertTrue(m.find(), data);
    JPA.em().clear();
    return JPA.em().find(BgVatReturn.class, Long.valueOf(m.group(1)));
  }

  @Test
  @DisplayName("„Генерирай“ на подадена декларация — контролерът отказва през услугата")
  void генерирай_на_подадена() {
    Company company = company("G" + System.nanoTime(), null);
    BgRoleLogin.loginWrite(company);
    BgVatReturn submitted = vatReturn(company, "202606", "submitted", null);

    String data = call(controller()::generate, submitted);

    assertTrue(data.contains("error"), data);
    assertTrue(data.contains("За поправка натиснете „Създай корекция“."), data);
  }

  @Test
  @DisplayName(
      "„Създай корекция“ след срока: предупреждение със срока, преместен през празника от календара")
  void корекция_след_срока_с_празник() {
    // 14.09.2026 е понеделник; по календара на дружеството — празник → срокът е 15.09.2026.
    Company company = company("H" + System.nanoTime(), LocalDate.of(2026, 9, 14));
    BgRoleLogin.loginWrite(company);
    BgVatReturn original = vatReturn(company, "202608", "submitted", null);

    String data = call(controller()::createCorrection, original);

    assertTrue(data.contains("Срокът за подаване за 08.2026 изтече на 15.09.2026."), data);
    BgVatReturn correction = opened(data);
    assertNotNull(correction, "корекцията се създава и след срока");
    assertEquals("corrected", correction.getStatusSelect());
    assertEquals(original.getId(), correction.getCorrectsPrevious().getId());
  }

  @Test
  @DisplayName("Без календар — само почивните: 14.06.2026 е неделя → срокът е 15.06.2026")
  void корекция_без_календар() {
    Company company = company("W" + System.nanoTime(), null);
    BgRoleLogin.loginWrite(company);
    BgVatReturn original = vatReturn(company, "202605", "accepted", null);

    String data = call(controller()::createCorrection, original);

    assertTrue(data.contains("Срокът за подаване за 05.2026 изтече на 15.06.2026."), data);
  }

  @Test
  @DisplayName("„Създай корекция“ от отхвърлена — без предупреждение, корекцията сочи оригинала")
  void корекция_от_отхвърлена() {
    Company company = company("R" + System.nanoTime(), null);
    BgRoleLogin.loginWrite(company);
    BgVatReturn original = vatReturn(company, "202605", "submitted", null);
    BgVatReturn rejected = vatReturn(company, "202605", "rejected", original);

    String data = call(controller()::createCorrection, rejected);

    assertFalse(data.contains("Срокът за подаване"), data);
    BgVatReturn correction = opened(data);
    assertNotNull(correction, data);
    assertEquals(
        original.getId(),
        correction.getCorrectsPrevious().getId(),
        "веригата не се удължава — сочи оригинала, не отхвърлената");
  }

  @Test
  @DisplayName(
      "Протоколите: коригираща в corrected/generated отваря периода; в submitted/accepted — не")
  void отворената_коригираща() {
    Company company = company("P" + System.nanoTime(), null);
    BgVatReturnRepository repo = Beans.get(BgVatReturnRepository.class);
    BgVatReturn original = vatReturn(company, "202606", "submitted", null);
    assertFalse(BgVatFiledPeriods.hasOpenCorrection(repo, company, "202606"), "само оригиналът");

    BgVatReturn correction = vatReturn(company, "202606", "corrected", original);
    for (String status : new String[] {"corrected", "generated", "submitted", "accepted"}) {
      JPA.runInTransaction(
          () -> JPA.em().find(BgVatReturn.class, correction.getId()).setStatusSelect(status));
      boolean open = status.equals("corrected") || status.equals("generated");
      assertEquals(open, BgVatFiledPeriods.hasOpenCorrection(repo, company, "202606"), status);
    }
    assertFalse(BgVatFiledPeriods.hasOpenCorrection(repo, company, "202607"), "друг период");

    // Втора РЕДОВНА декларация за подадения период (без correctsPrevious) не отваря нищо, и
    // генерирана.
    vatReturn(company, "202606", "generated", null);
    assertFalse(BgVatFiledPeriods.hasOpenCorrection(repo, company, "202606"), "редовна");
  }
}
