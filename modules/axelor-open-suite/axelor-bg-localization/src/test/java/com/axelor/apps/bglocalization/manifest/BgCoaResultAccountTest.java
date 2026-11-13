package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.saft.BgNraNomenclatures;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.common.csv.CSVFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Сметките за резултат на BG_COA: годишното приключване пише резултата в 123, не в натрупания.
 *
 * <p>{@code AccountingCloseAnnualServiceImpl#generateResultMove} пише в началото на N+1: загуба Дт
 * {@code resultLossAccount} / Кт 9000, печалба Дт 9000 / Кт {@code resultProfitAccount}. С 122 и
 * 1222 резултатът прескачаше 123 и влизаше в „минали години“ ПРЕДИ решението за разпределение.
 * Прехвърлянето 123 → 122/121 е отделна ръчна статия по това решение.
 */
class BgCoaResultAccountTest {

  private static final Path COA =
      BgDomainColumns.modulesRoot()
          .resolve("axelor-open-suite/axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static final String CURRENT_YEAR = "123";

  @Test
  @DisplayName("resultProfitAccount и resultLossAccount на BG_COA са 123")
  void резултатът_минава_през_123() {
    Map<String, String> config = firstRow(COA.resolve("account_accountConfig_accounts.csv"));
    List<String> bad = new ArrayList<>();
    for (String col : List.of("resultProfitAccount_code", "resultLossAccount_code")) {
      if (!CURRENT_YEAR.equals(config.get(col))) {
        bad.add(col + " = " + config.get(col));
      }
    }
    assertEquals(
        List.of(),
        bad,
        "резултатът на годината отива в 123 „Печалби и загуби от текущата година“ до решението"
            + " за разпределение; 122/1222 са натрупаният резултат от МИНАЛИ години");
  }

  @Test
  @DisplayName("123 е в BG_COA с дословното име от NRA_Nom_Accounts")
  void сметка_123_е_по_НАП() {
    String name = null;
    try (CSVParser parser = parse(COA.resolve("account_account.csv"))) {
      for (CSVRecord r : parser) {
        if (CURRENT_YEAR.equals(r.get("code"))) {
          name = r.get("name");
        }
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    if (name == null) {
      fail("сметка 123 липсва в BG_COA — AccountConfig би сочил несъществуваща сметка");
    }
    assertEquals(BgNraNomenclatures.accounts().get(CURRENT_YEAR), CURRENT_YEAR + " " + name);
  }

  private static Map<String, String> firstRow(Path file) {
    try (CSVParser parser = parse(file)) {
      for (CSVRecord r : parser) {
        return new HashMap<>(r.toMap());
      }
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    fail("празен " + file);
    return null;
  }

  private static CSVParser parse(Path file) throws IOException {
    return CSVFile.DEFAULT
        .withDelimiter(';')
        .withFirstRecordAsHeader()
        .parse(file.toFile(), StandardCharsets.UTF_8);
  }
}
