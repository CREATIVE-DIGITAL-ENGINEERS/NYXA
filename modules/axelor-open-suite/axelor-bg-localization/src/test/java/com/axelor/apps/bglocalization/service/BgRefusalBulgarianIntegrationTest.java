package com.axelor.apps.bglocalization.service;

import static com.axelor.apps.bglocalization.testsupport.BgRoleLogin.READ_ROLE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.apps.bglocalization.testsupport.BgRoleLogin;
import com.axelor.apps.napsubmissions.common.submission.NapFilingGate;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.nyxasecurity.gate.Capabilities;
import com.axelor.apps.nyxasecurity.gate.CapabilityGate;
import com.axelor.apps.nyxasecurity.gate.Refusals;
import com.axelor.auth.AuthUtils;
import com.axelor.auth.db.User;
import com.axelor.db.JPA;
import com.axelor.db.JpaSecurity;
import com.axelor.db.JpaSupport;
import com.axelor.i18n.I18nBundle;
import com.axelor.inject.Beans;
import com.axelor.meta.db.MetaTranslation;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.shiro.authz.UnauthorizedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ОТКАЗИТЕ НА БЪЛГАРСКИ ПО ИСТИНСКИЯ ПЪТ: потребител с език „bg“, преводите от трите {@code
 * messages_bg.csv} (nyxa-security, bg-localization, nyxa-nap-submissions) в {@code
 * MetaTranslation}, както ги внася инсталацията, и {@code I18n} както в приложението.
 *
 * <p>Доказва, че отказът се превежда там, където се хвърля (гейтът, слушателят при flush, гейтът на
 * подаванията, проверката на платформата), а не само, че преводите съществуват — това е в {@code
 * RefusalsTest} и {@link com.axelor.apps.bglocalization.i18n.BgRefusalTranslationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgRefusalBulgarianIntegrationTest extends JpaSupport {

  private static final List<Path> TRANSLATIONS =
      List.of(
          Paths.get("../nyxa-security/src/main/resources/i18n/messages_bg.csv"),
          Paths.get("src/main/resources/i18n/messages_bg.csv"),
          Paths.get("../nyxa-nap-submissions/src/main/resources/i18n/messages_bg.csv"));

  private Long companyId;
  private Long otherCompanyId;
  private Long submissionId;
  private final List<Long> translationIds = new ArrayList<>();

  @BeforeEach
  void scene() {
    String tag = String.valueOf(System.nanoTime());
    JPA.runInTransaction(
        () -> {
          companyId = company("Откази А " + tag, "RA" + tag).getId();
          otherCompanyId = company("Откази Б " + tag, "RB" + tag).getId();
          NapSubmission submission = new NapSubmission();
          submission.setName("Подаване " + tag);
          submission.setCompany(JPA.em().find(Company.class, companyId));
          submission.setServiceCodeSelect(NapSubmissionRepository.SERVICE_NOI_APP9);
          submission.setChannelSelect(NapSubmissionRepository.CHANNEL_MANUAL_PORTAL);
          submission.setSubmissionStatusSelect(NapSubmissionRepository.STATUS_DRAFT);
          JPA.em().persist(submission);
          submissionId = submission.getId();
          // Ако базата вече носи превод (уникално по ключ + език), той остава — тестът го сверява.
          java.util.Set<String> present = new java.util.HashSet<>();
          for (MetaTranslation t :
              JPA.em()
                  .createQuery(
                      "SELECT t FROM MetaTranslation t WHERE t.language = 'bg'",
                      MetaTranslation.class)
                  .getResultList()) {
            present.add(t.getKey());
          }
          for (Map.Entry<String, String> row : bulgarian().entrySet()) {
            // Тестовата HSQLDB държи 255 знака; по-дългите редове на bg-localization не са откази.
            if (present.contains(row.getKey())
                || row.getKey().length() > 255
                || row.getValue().length() > 255) {
              continue;
            }
            MetaTranslation t = new MetaTranslation();
            t.setKey(row.getKey());
            t.setMessage(row.getValue());
            t.setLanguage("bg");
            JPA.em().persist(t);
            translationIds.add(t.getId());
          }
        });
    I18nBundle.invalidate();
    // Езикът се записва ПРЕДИ входа: в HSQLDB запис на User от влязъл потребител виси на слушателя
    // на studio (той чете auth_user в отделна нишка).
    Long userId =
        BgRoleLogin.createUser(JPA.em().find(Company.class, companyId), "Тест откази", READ_ROLE);
    String[] code = new String[1];
    JPA.runInTransaction(
        () -> {
          User user = JPA.em().find(User.class, userId);
          user.setLanguage("bg");
          code[0] = user.getCode();
        });
    BgRoleLogin.loginExisting(code[0]);
  }

  @AfterEach
  void cleanUp() {
    BgRoleLogin.logout();
    JPA.runInTransaction(
        () -> {
          for (Long id : translationIds) {
            MetaTranslation t = JPA.em().find(MetaTranslation.class, id);
            if (t != null) {
              JPA.em().remove(t);
            }
          }
        });
    I18nBundle.invalidate();
  }

  @Test
  @DisplayName("гейтът: действието и ролите на български, с кода в скоби")
  void gateNamesActionAndRolesInBulgarian() {
    UnauthorizedException refused =
        assertThrows(
            UnauthorizedException.class,
            () -> CapabilityGate.requireRole("generate a VAT return", Capabilities.NAP_WRITE));
    assertEquals(
        "Нямате право за „генериране на справка-декларация по ЗДДС“. Нужна е роля „НАП — запис“"
            + " (accounting.nap.write) или „Всички области — запис“ (all.write) или"
            + " „Администратор на клиента“ (client.admin). Обърнете се към администратора.",
        refused.getMessage());
  }

  @Test
  @DisplayName("гейтът: чуждо дружество — на български")
  void gateRefusesOtherCompanyInBulgarian() {
    UnauthorizedException refused =
        assertThrows(
            UnauthorizedException.class,
            () ->
                CapabilityGate.requireActiveCompany(JPA.em().find(Company.class, otherCompanyId)));
    assertEquals("Можете да работите само в активната си компания.", refused.getMessage());
  }

  @Test
  @DisplayName("гейтът на подаванията: действието и ролята на НОИ на български")
  void napFilingGateInBulgarian() {
    NapSubmission submission = JPA.em().find(NapSubmission.class, submissionId);
    UnauthorizedException refused =
        assertThrows(
            UnauthorizedException.class, () -> NapFilingGate.require("submit to NAP", submission));
    assertTrue(
        refused
            .getMessage()
            .startsWith(
                "Нямате право за „подаване към НАП“. Нужна е роля „НОИ — запис“ (hr.noi.write)"),
        refused.getMessage());
    UnauthorizedException missing =
        assertThrows(
            UnauthorizedException.class, () -> NapFilingGate.require("submit to NAP", null));
    assertEquals("Подаването не съществува. Обновете екрана.", missing.getMessage());
  }

  @Test
  @DisplayName("слушателят при flush: неназначено дружество — на български")
  void listenerRefusesInBulgarian() {
    String code = AuthUtils.getUser().getCode();
    RuntimeException refused =
        assertThrows(
            RuntimeException.class,
            () ->
                JPA.runInTransaction(
                    () ->
                        AuthUtils.getUser()
                            .setActiveCompany(JPA.em().find(Company.class, otherCompanyId))));
    Refusals.Refusal refusal = refusal(refused);
    assertNotNull(refusal, "отказ на слушателя: " + refused);
    assertEquals(
        "Можете да превключите само към компания, на която сте назначени (потребител "
            + code
            + ").",
        refusal.getMessage());
  }

  @Test
  @DisplayName("платформата: запис без право — на български")
  void platformRefusesInBulgarian() {
    UnauthorizedException refused =
        assertThrows(
            UnauthorizedException.class,
            () ->
                Beans.get(JpaSecurity.class)
                    .check(JpaSecurity.CAN_WRITE, NapSubmission.class, submissionId));
    assertEquals(
        "Нямате право да променяте този запис. Обърнете се към администратора.",
        refused.getMessage());
  }

  // ------------------------------------------------------------------ helpers

  private static Refusals.Refusal refusal(Throwable thrown) {
    for (Throwable t = thrown; t != null; t = t.getCause()) {
      if (t instanceof Refusals.Refusal) {
        return (Refusals.Refusal) t;
      }
    }
    return null;
  }

  private static Company company(String name, String code) {
    Company company = new Company();
    company.setName(name);
    company.setCode(code);
    JPA.em().persist(company);
    return company;
  }

  /** Редовете на трите файла: ключ → българският текст (първият печели, както при вноса). */
  private static Map<String, String> bulgarian() {
    Map<String, String> out = new LinkedHashMap<>();
    for (Path file : TRANSLATIONS) {
      List<String> lines;
      try {
        lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
      for (String line : lines.subList(1, lines.size())) {
        List<String> cells = csv(line);
        if (cells.size() >= 2 && !cells.get(1).isEmpty()) {
          out.putIfAbsent(cells.get(0), cells.get(1));
        }
      }
    }
    return out;
  }

  private static List<String> csv(String line) {
    List<String> cells = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char c = line.charAt(i);
      if (quoted) {
        if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else if (c == '"') {
          quoted = false;
        } else {
          cell.append(c);
        }
      } else if (c == '"') {
        quoted = true;
      } else if (c == ',') {
        cells.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(c);
      }
    }
    cells.add(cell.toString());
    return cells;
  }
}
