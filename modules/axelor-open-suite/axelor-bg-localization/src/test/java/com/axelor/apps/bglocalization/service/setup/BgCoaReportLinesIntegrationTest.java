package com.axelor.apps.bglocalization.service.setup;

import static com.axelor.apps.bglocalization.service.setup.BgCoaReportColumnsIntegrationTest.books;
import static com.axelor.apps.bglocalization.service.setup.BgCoaReportColumnsIntegrationTest.compute;
import static com.axelor.apps.bglocalization.service.setup.BgCoaReportColumnsIntegrationTest.eq;
import static com.axelor.apps.bglocalization.service.setup.BgCoaReportColumnsIntegrationTest.posted;
import static com.axelor.apps.bglocalization.service.setup.BgCoaReportColumnsIntegrationTest.report;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.importFromTemplate;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.newCompany;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.packageLikeDataInit;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.tx;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ЧИСТ ВНОС НА BG_COA → РЕДОВЕТЕ НА БАЛАНСА, ОПР И СОБСТВЕНИЯ КАПИТАЛ ПО ОБРАЗЦИТЕ КЪМ СС 1 — С
 * ЧИСЛА.
 *
 * <p>104 → записан капитал, 114 → премии, 494 → нетекущ ред „Отсрочени данъци“, 243 → ДМА; ОПР без
 * извънредни статии (СС 1 Прил. № 2, МСС 1 пар. 87); отчетът за собствения капитал събира групите
 * на баланса. Структурата се пази и без база от {@code BgCoaReportCoverageTest}.
 *
 * <p><b>⚠️ ОТДЕЛЕН КЛАС, А НЕ ВТОРИ СЛУЧАЙ В {@link BgCoaReportColumnsIntegrationTest}.</b> AOS
 * свързва сметките на реда при вноса по код БЕЗ дружество ({@code
 * ImportAccountingReportConfigLine#setAccounts}: {@code self.code = :code}), а при изчислението
 * прави СЕЧЕНИЕ с намерените по {@code LIKE} ({@code
 * AccountingReportValueMoveLineServiceImpl#mergeSets} — {@code retainAll}). Второто дружество в
 * една база получава сметките на първото и справката дава нули. Тук дружеството е единствено:
 * integrationTest върви с forkEvery = 1, тоест класът има своя база. Дефектът е отделна задача.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgCoaReportLinesIntegrationTest extends JpaSupport {

  private final BgCoaReportColumnsIntegrationTest setup = new BgCoaReportColumnsIntegrationTest();

  @BeforeEach
  void prepare() {
    setup.applicationLocale();
    setup.hsqldbArrayToString();
  }

  @AfterEach
  void logout() {
    setup.logout();
  }

  @Test
  @DisplayName(
      "Чист внос: 104 и 114 по образеца, 494 нетекущ, 243 при ДМА, без извънредни статии,"
          + " капиталът се равнява с баланса")
  void чист_внос_редовете_по_образеца() throws Exception {
    Long templateId = packageLikeDataInit();
    Long companyId = tx(() -> newCompany("RFIX").getId());
    importFromTemplate(companyId, templateId);
    JPA.em().clear();
    Company company = JPA.em().find(Company.class, companyId);

    Long[] books = books(companyId);
    posted(companyId, books, "2026-02-01", "503", 600, 0, "101", 0, 600);
    posted(companyId, books, "2026-02-01", "503", 100, 0, "104", 0, 100);
    posted(companyId, books, "2026-02-01", "503", 300, 0, "114", 0, 300);
    posted(companyId, books, "2026-03-01", "494", 50, 0, "115", 0, 50);
    posted(companyId, books, "2026-04-01", "691", 20, 0, "243", 0, 20);
    posted(companyId, books, "2026-05-01", "503", 80, 0, "791", 0, 80);

    com.axelor.apps.bglocalization.testsupport.BgRoleLogin.login(company, "accounting.write");

    // Баланс (НСС): актив 503 1080 + 494 50 − 243 20 = 1110;
    // пасив 101 600 + 104 100 + 114 300 + 115 50 + резултат 60 = 1110.
    Map<String, BigDecimal> bs = compute(report(companyId, "28"));
    eq("700", bs, "P_ST1_1");
    eq("300", bs, "P_ST1_2");
    eq("50", bs, "P_ST1_4");
    eq("50", bs, "A_ST1_4");
    eq("-20", bs, "A_ST1_2");
    eq("1110", bs, "TOT_ASSETS");
    eq("0", bs, "CTRL");
    // ОПР (НСС): 791 в „Други приходи“, 691 в „Други разходи“.
    Map<String, BigDecimal> pl = compute(report(companyId, "27"));
    eq("80", pl, "I_ST1_2");
    eq("20", pl, "E_ST1_7");
    eq("80", pl, "TOT_INC");
    eq("20", pl, "TOT_EXP");
    eq("60", pl, "NET");
    // МСС.
    Map<String, BigDecimal> ifrsBs = compute(report(companyId, "46"));
    eq("700", ifrsBs, "MP_ST1_1");
    eq("300", ifrsBs, "MP_ST1_2");
    eq("50", ifrsBs, "MA_ST1_4");
    eq("1110", ifrsBs, "MA_TOT");
    eq("0", ifrsBs, "MB_CTRL");
    Map<String, BigDecimal> ifrsPl = compute(report(companyId, "45"));
    eq("80", ifrsPl, "MO_ST_54756");
    eq("20", ifrsPl, "MO_ST_21931");
    eq("60", ifrsPl, "MO_NET");
    // Собственият капитал — същите групи като в баланса.
    Map<String, BigDecimal> capital = compute(report(companyId, "47"));
    eq("700", capital, "SC_CLOSE_53810");
    eq("300", capital, "SC_CLOSE_55018");
    eq("50", capital, "SC_CLOSE_22505");
  }
}
