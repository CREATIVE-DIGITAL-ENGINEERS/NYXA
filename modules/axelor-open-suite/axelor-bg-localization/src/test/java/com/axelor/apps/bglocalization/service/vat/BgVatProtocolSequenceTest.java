package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Sequence;
import com.axelor.apps.base.db.SequenceVersion;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Поредицата на ДДС протоколите: тази, която стартирането създава за заварено дружество, е СЪЩАТА
 * като реда в шаблона BG_COA, който получава всяко ново дружество. И сиракът {@code napProtocol} го
 * няма в шаблона.
 */
class BgVatProtocolSequenceTest {

  /** Шаблонът е в axelor-account; тестът тече от каталога на модула. */
  private static final Path TEMPLATE =
      Paths.get("../axelor-account/src/main/resources/l10n/l10n_bgr/BG_COA");

  private static List<String> rows(String file) throws Exception {
    return Files.readAllLines(TEMPLATE.resolve(file), StandardCharsets.UTF_8);
  }

  private static String[] templateRow() throws Exception {
    List<String[]> found =
        rows("base_sequence.csv").stream()
            .map(line -> line.split(";", -1))
            .filter(f -> f.length > 1 && "\"bgVatProtocol\"".equals(f[1]))
            .collect(Collectors.toList());
    assertEquals(1, found.size(), "редът bgVatProtocol в шаблона");
    return found.get(0);
  }

  private static String unquote(String value) {
    return value.replace("\"", "");
  }

  @Test
  void theSequenceIsTenDigits_withoutPrefix_withoutReset_withOneBackdatedVersion() {
    Company company = new Company();
    Sequence sequence = BgVatProtocolSequence.newFor(company);

    assertSame(company, sequence.getCompany());
    assertEquals("bgVatProtocol", sequence.getCodeSelect());
    assertEquals(10, sequence.getPadding());
    assertNull(sequence.getPrefixe());
    assertNull(sequence.getSuffixe());
    assertEquals(1, sequence.getToBeAdded());
    assertFalse(sequence.getYearlyResetOk());
    assertFalse(sequence.getMonthlyResetOk());
    assertEquals(1, sequence.getSequenceVersionList().size());
    SequenceVersion version = sequence.getSequenceVersionList().get(0);
    assertEquals(LocalDate.of(2000, 1, 1), version.getStartDate());
    assertEquals(1L, version.getNextNum());
    assertSame(sequence, version.getSequence());
    assertNull(sequence.getImportId(), "дружество без код");
  }

  /** importId е като на шаблона — BG_COA, приложен по-късно, я намира и не прави втора. */
  @Test
  void theImportIdIsTheTemplates() throws Exception {
    Company company = new Company();
    company.setCode("ACME");
    assertEquals("ACME-145", BgVatProtocolSequence.newFor(company).getImportId());
    assertEquals(BgVatProtocolSequence.TEMPLATE_IMPORT_ID, unquote(templateRow()[0]));

    // chart-config.xml: търсене по importId и дружество, а importId е „<код>-<importId>“.
    String config =
        new String(
            Files.readAllBytes(TEMPLATE.resolve("../../chart-config.xml")), StandardCharsets.UTF_8);
    int at = config.indexOf("<input file=\"base_sequence.csv\"");
    String input = config.substring(at, config.indexOf("</input>", at));
    assertTrue(
        input.contains("search=\"self.importId = :importId AND self.company.id = :_companyId\""),
        input);
    assertTrue(
        input.contains(
            "<bind column=\"importId\" to=\"importId\" eval=\"_companyCode + '-' + importId\"/>"),
        input);
  }

  @Test
  void theTemplateRowIsTheSameSequence() throws Exception {
    // importId;codeSelect;name;sequenceTypeSelect;sequenceLettersTypeSelect;nextNum;padding;
    // prefixe;suffixe;toBeAdded;yearlyResetOk
    String[] row = templateRow();
    assertEquals(BgVatProtocolSequence.NAME, unquote(row[2]));
    assertEquals("NUMBERS", unquote(row[3]));
    assertEquals(String.valueOf(BgVatProtocolSequence.PADDING), unquote(row[6]));
    assertEquals("", unquote(row[7]), "без префикс");
    assertEquals("", unquote(row[8]), "без суфикс");
    assertEquals("1", unquote(row[9]));
    assertEquals("0", unquote(row[10]), "без годишно нулиране");

    String importId = unquote(row[0]);
    assertTrue(
        rows("base_sequenceVersion.csv")
            .contains("\"V" + importId + "\";\"" + importId + "\";\"2000-01-01\""),
        "версията на реда е датирана назад, като на останалите");
  }

  @Test
  void theOrphanNapProtocolIsGoneFromTheTemplate() throws Exception {
    assertTrue(
        rows("base_sequence.csv").stream().noneMatch(line -> line.contains("napProtocol")),
        "napProtocol не се ползва от нищо — остава само bgVatProtocol");
    assertTrue(rows("base_sequenceVersion.csv").stream().noneMatch(l -> l.startsWith("\"V143\"")));
  }
}
