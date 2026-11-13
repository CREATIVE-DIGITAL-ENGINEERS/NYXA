package com.axelor.apps.bglocalization.service.setup;

import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.COA;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.importFromTemplate;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.newCompany;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.packageLikeDataInit;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.tx;
import static com.axelor.apps.bglocalization.testsupport.BgCoaCompanyFixture.txChecked;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.Journal;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import com.axelor.apps.base.service.administration.SequenceService;
import com.axelor.apps.bglocalization.module.BgLocalizationProductionWiringTestModule;
import com.axelor.db.JPA;
import com.axelor.db.JpaSupport;
import com.axelor.inject.Beans;
import com.axelor.test.GuiceExtension;
import com.axelor.test.GuiceModules;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * ВТОРОТО дружество в една база получава версиите на поредиците от 2000-01-01 и номерира документи
 * с дата ПРЕДИ деня, в който е създадено.
 *
 * <p><b>Дефектът.</b> Входът {@code base_sequenceVersion.csv} в {@code chart-config.xml} записваше
 * {@code importId} на версията БЕЗ префикс на дружеството ({@code V1}, {@code V10}…), а {@code
 * base_sequence_version.import_id} е глобално уникален. Първото дружество в базата минаваше. У
 * всяко следващо всеки ред падаше на уникалността; CSVImporter връща назад само реда и продължава,
 * тоест сметкопланът и поредиците влизаха, но версиите оставаха от деня на създаването ({@code
 * SequenceVersionGeneratorServiceImpl.findStartDateFromPreviousVersion}). Документ с по-ранна дата
 * не намира версия, Axelor прави нова от днес с {@code nextNum = 1} и всеки следващ такъв документ
 * получава СЪЩИЯ номер — при осчетоводяване „The generated sequence … already exists“. Точно това е
 * историческият внос (Microinvest) за второ дружество на нова среда.
 *
 * <p><b>Какво е истинско.</b> Двете дружества минават по пътя на бутона: зипът от data-init →
 * {@code installProcess} върху целия {@code chart-config.xml}. Номерата идват от {@code
 * SequenceService#getSequenceNumber} с дата — същото извикване, което прави осчетоводяването на
 * статия ({@code MoveSequenceService}).
 *
 * <p><b>Какво е подменено.</b> Проверката за вече издаден номер ({@code isSequenceAlreadyExisting})
 * чете {@code Move}, а HSQLDB не може да избере {@code Move} (формулата {@code ocrUploadIds}).
 * Затова тук се подава {@code objectClass = null} и се сверява, че двата номера са РАЗЛИЧНИ —
 * еднаквият номер е онова, което проверката после би отказала.
 *
 * <p>⚠️ {@code @Tag("integration")} — пуска се с {@code gradlew integrationTest}.
 */
@ExtendWith(GuiceExtension.class)
@GuiceModules(BgLocalizationProductionWiringTestModule.class)
@Tag("integration")
class BgSequenceVersionSecondCompanyIntegrationTest extends JpaSupport {

  private static final LocalDate SINCE = LocalDate.of(2000, 1, 1);

  @Test
  @DisplayName(
      "Две дружества: и второто има версии от 2000-01-01 и номерира документи с минала дата")
  void второто_дружество_номерира_документи_с_дата_преди_създаването_си() throws Exception {
    Long templateId = packageLikeDataInit();
    Long first = tx(() -> newCompany("FIRST").getId());
    importFromTemplate(first, templateId);
    Long second = tx(() -> newCompany("SECOND").getId());
    importFromTemplate(second, templateId);

    Map<String, String> shipped = shippedVersions();
    // Всичко наведнъж: без поправката червеният пробег показва и четирите последствия.
    assertAll(
        () -> assertVersionsFrom2000(first, "C-FIRST", shipped),
        () -> assertVersionsFrom2000(second, "C-SECOND", shipped),
        () -> {
          JPA.em().clear();
          assertEquals(
              0L,
              JPA.all(SequenceVersion.class).filter("self.sequence IS NULL").count(),
              "версия без поредица — ред в base_sequenceVersion.csv сочи несъществуваща поредица");
        },
        () -> assertBackdatedNumbersAreDistinct(second));
  }

  /** (importId на поредицата → importId на версията), както ги носи BG_COA. */
  private static Map<String, String> shippedVersions() throws Exception {
    Map<String, String> out = new LinkedHashMap<>();
    List<String> lines =
        Files.readAllLines(COA.resolve("base_sequenceVersion.csv"), StandardCharsets.UTF_8);
    for (String line : lines.subList(1, lines.size())) {
      if (line.isBlank()) {
        continue;
      }
      String[] cells = line.replace("\"", "").split(";");
      out.put(cells[1], cells[0]);
    }
    assertFalse(out.isEmpty(), "base_sequenceVersion.csv е празен — тестът не би видял нищо");
    return out;
  }

  /**
   * Всяка поредица от CSV-то има точно една версия, тя е от 2000-01-01 и носи importId на
   * дружеството. Датата и importId-то се броят поотделно: датата е последствието, importId-то —
   * причината.
   */
  private static void assertVersionsFrom2000(
      Long companyId, String companyCode, Map<String, String> shipped) {
    JPA.em().clear();
    List<String> wrongDate = new ArrayList<>();
    List<String> wrongImportId = new ArrayList<>();
    for (Map.Entry<String, String> row : shipped.entrySet()) {
      List<SequenceVersion> versions =
          JPA.all(SequenceVersion.class)
              .filter(
                  "self.sequence.importId = ?1 AND self.sequence.company.id = ?2",
                  companyCode + "-" + row.getKey(),
                  companyId)
              .fetch();
      if (versions.size() != 1 || !SINCE.equals(versions.get(0).getStartDate())) {
        wrongDate.add(row.getValue() + describe(versions));
      } else if (!(companyCode + "-" + row.getValue()).equals(versions.get(0).getImportId())) {
        wrongImportId.add(row.getValue() + describe(versions));
      }
    }
    assertTrue(
        wrongDate.isEmpty() && wrongImportId.isEmpty(),
        () ->
            companyCode
                + ": от "
                + shipped.size()
                + " поредици "
                + wrongDate.size()
                + " без версия от "
                + SINCE
                + " "
                + first(wrongDate)
                + " и "
                + wrongImportId.size()
                + " с importId без префикс на дружеството "
                + first(wrongImportId));
  }

  private static List<String> first(List<String> items) {
    return items.subList(0, Math.min(5, items.size()));
  }

  private static String describe(List<SequenceVersion> versions) {
    List<String> out = new ArrayList<>();
    for (SequenceVersion v : versions) {
      out.add(v.getImportId() + "@" + v.getStartDate());
    }
    return out.toString();
  }

  /**
   * Всеки дневник на дружеството: два документа с дати отпреди създаването получават различни
   * номера и не се ражда нова версия.
   */
  private static void assertBackdatedNumbersAreDistinct(Long companyId) {
    JPA.em().clear();
    List<Long> sequenceIds = new ArrayList<>();
    for (Journal journal :
        JPA.all(Journal.class)
            .filter("self.company.id = ?1 AND self.sequence IS NOT NULL", companyId)
            .fetch()) {
      sequenceIds.add(journal.getSequence().getId());
    }
    assertFalse(sequenceIds.isEmpty(), "дружеството е без дневници — вносът не е минал");

    LocalDate firstDay = LocalDate.now().minusYears(1).withDayOfMonth(1);
    List<String> clashes = new ArrayList<>();
    for (Long sequenceId : sequenceIds) {
      List<String> numbers = new ArrayList<>();
      txChecked(
          () -> {
            Sequence sequence = JPA.find(Sequence.class, sequenceId);
            SequenceService service = Beans.get(SequenceService.class);
            numbers.add(service.getSequenceNumber(sequence, firstDay, null, null, null));
            numbers.add(
                service.getSequenceNumber(sequence, firstDay.plusDays(1), null, null, null));
          });
      long versions =
          JPA.all(SequenceVersion.class).filter("self.sequence.id = ?1", sequenceId).count();
      if (numbers.get(0).equals(numbers.get(1)) || versions != 1) {
        clashes.add(numbers + " версии=" + versions);
      }
    }
    assertTrue(
        clashes.isEmpty(),
        () ->
            clashes.size()
                + " от "
                + sequenceIds.size()
                + " дневника дават еднакъв номер на документи с минала дата или раждат нова"
                + " версия: "
                + clashes);
  }
}
