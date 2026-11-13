package com.axelor.apps.bglocalization.service.demo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.axelor.apps.account.db.FixedAssetCategory;
import com.axelor.apps.account.db.Journal;
import com.axelor.db.JPA;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ДНЕВНИКЪТ НА КАТЕГОРИИТЕ ДМА ОТ BG_COA — на собственото дружество.
 *
 * <p>Ако {@code l10n/chart-config.xml} не върже колоната {@code journal.code} към дружеството,
 * платформата я търси само по код и второ дружество с BG_COA получава и в 15-те си категории ДМА
 * дневника MOA на ПЪРВОТО. Активите вземат дневника от категорията, а амортизацията — от актива;
 * „Сметка“ отказва статията: „компанията на движението е различна от тази на дневника“.
 *
 * <p>Тук: BG_COA за две нови дружества по пътя на екрана ({@link BgDemoInstallation#newCompany}) и
 * за всяко — 15 категории, всяка с дневник MOA на същото дружество. Пуска се ръчно срещу СВОЯ база
 * ({@code -Ddemo.db.url/username/password}, {@code -Ddemo.db.ddl=update}); без адрес се пропуска
 * (CI няма база).
 */
@EnabledIfSystemProperty(named = "demo.db.url", matches = ".+")
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgDemoPostgresModule.class)
@Tag("integration")
class BgCoaFixedAssetCategoryJournalPostgresIntegrationTest {

  /** Категориите ДМА в {@code l10n_bgr/BG_COA/account_fixedAssetCategory.csv}. */
  private static final int BG_COA_CATEGORIES = 15;

  @Test
  void everyCategoryOfEachCompanyGetsThatCompanysJournal() throws Exception {
    assumeTrue(System.getProperty("demo.db.url") != null, "без -Ddemo.db.url (своя база)");
    BgDemoInstallation.ensure();
    String tag = Long.toString(System.currentTimeMillis() % 100000);
    Long first = BgDemoInstallation.newCompany("Дневник ДМА А " + tag, "JA" + tag);
    Long second = BgDemoInstallation.newCompany("Дневник ДМА Б " + tag, "JB" + tag);

    JPA.em().clear();
    List<String> wrong = new ArrayList<>();
    for (Long companyId : List.of(first, second)) {
      List<FixedAssetCategory> categories =
          JPA.all(FixedAssetCategory.class)
              .filter("self.company.id = ?1", companyId)
              .order("importId")
              .fetch();
      assertEquals(BG_COA_CATEGORIES, categories.size(), "категории ДМА на дружество " + companyId);
      for (FixedAssetCategory c : categories) {
        Journal j = c.getJournal();
        assertNotNull(j, "категорията " + c.getImportId() + " е без дневник");
        if (!companyId.equals(j.getCompany().getId())) {
          wrong.add(
              c.getImportId()
                  + " → "
                  + j.getCode()
                  + " на дружество "
                  + j.getCompany().getId()
                  + " (а не "
                  + companyId
                  + ")");
        }
        assertEquals("MOA", j.getCode(), "дневникът на " + c.getImportId());
      }
    }
    assertEquals(List.of(), wrong, "категории с дневник на друго дружество");
  }
}
