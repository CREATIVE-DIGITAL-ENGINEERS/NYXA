package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Нищо автоматично не пише в 691 — условието, при което 691 носи името си по НАП.
 *
 * <p>По NRA_Nom_Accounts 691 е „Разходи от отписване на кредитни експозиции“. До PR „Активи — 691“
 * тя беше сметката на всяко отписване на ДМА, което не е продажба (категориите, {@code
 * approExtraordDepreciationExpenseAccount}). Сега бракът и непреодолимата сила отиват на 60908,
 * липсата — на 60905, и 691 остава само за ръчни статии по смисъла си. Сочи ли я пак някоя
 * настройка, отписване на ДМА ще излезе в SAF-T като отписана кредитна експозиция.
 *
 * <p>Обхождат се ВСИЧКИ файлове на BG_COA освен самия сметкоплан и справките (те я четат, не пишат)
 * и причините за отписване от началните данни на модула.
 */
class BgCoa691NotPostedTest {

  private static final String CODE = "691";

  private static final Path COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  /** Сметкопланът я дефинира, справките я четат — нито едното не е осчетоводяване. */
  private static final Set<String> READERS =
      Set.of("account_account.csv", "account_accountingReportConfigLine.csv");

  @Test
  @DisplayName("нито една настройка на BG_COA не сочи 691")
  void настройките_не_сочат_691() {
    List<String> found = new ArrayList<>();
    try (DirectoryStream<Path> files = Files.newDirectoryStream(COA, "*.csv")) {
      for (Path file : files) {
        if (READERS.contains(file.getFileName().toString())) {
          continue;
        }
        try (CSVParser parser =
            CSVFile.DEFAULT
                .withDelimiter(';')
                .withFirstRecordAsHeader()
                .parse(file.toFile(), StandardCharsets.UTF_8)) {
          for (CSVRecord r : parser) {
            for (Map.Entry<String, String> cell : r.toMap().entrySet()) {
              if (CODE.equals(cell.getValue().trim())) {
                found.add(file.getFileName() + " ред " + r.getRecordNumber() + " " + cell.getKey());
              }
            }
          }
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    assertEquals(
        List.of(),
        found,
        "691 е „Разходи от отписване на кредитни експозиции“ по НАП — настройка, която я сочи,"
            + " осчетоводява там автоматично. Брак/непреодолима сила → 60908, липса → 60905");
  }

  @Test
  @DisplayName("нито една причина за отписване не носи 691 като сметка по подразбиране")
  void причините_не_сочат_691() {
    String file = "bg_asset_disposal_reason.csv";
    List<String> h = BgSeed.quotedHeader(file);
    if (!h.contains("bgRemovalAccountCode")) {
      fail("няма колона bgRemovalAccountCode в " + file + ": " + h);
    }
    List<String> found = new ArrayList<>();
    for (String[] row : BgSeed.quotedRows(file)) {
      if (CODE.equals(BgSeed.col(h, row, "bgRemovalAccountCode").trim())) {
        found.add(BgSeed.col(h, row, "code"));
      }
    }
    assertEquals(List.of(), found, "причина с 691 би я осчетоводила при отписване на ДМА");
  }
}
