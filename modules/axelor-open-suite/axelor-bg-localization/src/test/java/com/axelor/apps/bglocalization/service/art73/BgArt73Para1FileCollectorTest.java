package com.axelor.apps.bglocalization.service.art73;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgArt73Para1Record;
import com.axelor.apps.bglocalization.db.BgArt73Para1Report;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileContent;
import com.axelor.apps.bglocalization.service.art73.dto.BgArt73Para1FileRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СЪБИРАЧЪТ ЧЕТЕ ЗАПИСАНОТО — инвариантът „екран == файл“.
 *
 * <p>⚠️ Свидетелите тук не проверяват формата (него го няма), а КАКВО влиза във файла: че е взето
 * от ЗАПИСАНИТЕ редове, че подредбата е възпроизводима и че заличаващият ред не носи суми.
 */
class BgArt73Para1FileCollectorTest {

  private static BgIncomeType incomeType(String code, boolean aggregate, boolean personCount) {
    BgIncomeType type = new BgIncomeType();
    type.setCode(code);
    type.setIsAggregate(aggregate);
    type.setReportsPersonCount(personCount);
    return type;
  }

  private static BgArt73Para1Record record(Long id, String correctionCode, String code) {
    BgArt73Para1Record record = new BgArt73Para1Record();
    record.setId(id);
    record.setCorrectionCode(correctionCode);
    record.setIncomeType(incomeType(code, false, false));
    record.setIdentifierType("0");
    record.setIdentifier("8001010010");
    record.setFirstName("Иван");
    record.setMiddleName("Петров");
    record.setLastName("Георгиев");
    record.setGrossAmount(new BigDecimal("1200.00"));
    record.setInsuranceAmount(new BigDecimal("100.00"));
    record.setTaxAmount(new BigDecimal("110.00"));
    return record;
  }

  private static BgArt73Para1Report report(BgArt73Para1Record... records) {
    BgArt73Para1Report report = new BgArt73Para1Report();
    report.setTaxYear(2026);
    report.setPayerIdentifier("100000033");
    report.setPayerName("НЯКОЕ ЕООД");
    report.setContactPhone("0700 18 700");
    report.setContactEmail("office@example.bg");
    List<BgArt73Para1Record> list = new ArrayList<>(Arrays.asList(records));
    report.setRecordList(list);
    return report;
  }

  @Test
  @DisplayName("Част І се пренася ВЕДНЪЖ, а не се размножава на всеки ред")
  void част_едно_се_пренася_веднъж() {
    BgArt73Para1FileContent content =
        BgArt73Para1FileCollector.collect(report(record(1L, "0", "307")));
    assertEquals(2026, content.getTaxYear());
    assertEquals("100000033", content.getPayerIdentifier());
    assertEquals(1, content.getRows().size());
  }

  @Test
  @DisplayName("Редовете идват от ЗАПИСАНОТО и са подредени възпроизводимо по id")
  void подредбата_е_по_id() {
    BgArt73Para1Report report =
        report(record(30L, "0", "307"), record(10L, "0", "301"), record(20L, "0", "302"));
    List<BgArt73Para1FileRow> rows = BgArt73Para1FileCollector.collect(report).getRows();

    assertEquals(
        Arrays.asList("301", "302", "307"),
        Arrays.asList(
            rows.get(0).getIncomeCode(), rows.get(1).getIncomeCode(), rows.get(2).getIncomeCode()));
  }

  @Test
  @DisplayName("Заличаващият ред („8“) НЕ носи суми — указание за попълване т. 2")
  void заличаващият_ред_е_без_суми() {
    BgArt73Para1Record deletion = record(1L, "8", "307");
    BgArt73Para1FileRow row = BgArt73Para1FileCollector.row(deletion);

    assertFalse(row.carriesAmounts());
    assertNull(row.getGrossAmount());
    assertNull(row.getInsuranceAmount());
    assertNull(row.getTaxAmount());
    assertNull(row.getPersonCount());
    // ⚠️ Колони 1–8 и 10 ОСТАВАТ — редът, който заличава, пак трябва да каже КОГО и ЗА КАКВО.
    assertEquals("8001010010", row.getIdentifier());
    assertEquals("307", row.getIncomeCode());
  }

  @Test
  @DisplayName("Признаците на каталога се пренасят — не се гадаят от кода на дохода")
  void признаците_идват_от_каталога() {
    BgArt73Para1Record aggregate = record(1L, "0", "8141");
    aggregate.setIncomeType(incomeType("8141", true, true));
    BgArt73Para1FileRow row = BgArt73Para1FileCollector.row(aggregate);

    assertTrue(row.isAggregateRow());
    assertTrue(row.isReportsPersonCount());
  }

  @Test
  @DisplayName("Празна справка дава празно съдържание, а не изключение")
  void празната_справка_не_гърми() {
    assertEquals(0, BgArt73Para1FileCollector.collect(null).getRows().size());
    assertEquals(0, BgArt73Para1FileCollector.collect(report()).getRows().size());
  }

  @Test
  @DisplayName("Датата на раждане се пренася както е записана — тип „3“")
  void датата_на_раждане_се_пренася() {
    BgArt73Para1Record foreign = record(1L, "0", "307");
    foreign.setIdentifierType("3");
    foreign.setIdentifier(null);
    foreign.setBirthDate(LocalDate.of(1980, 1, 1));
    foreign.setIsForeign(Boolean.TRUE);
    foreign.setCountryCode("DE");

    BgArt73Para1FileRow row = BgArt73Para1FileCollector.row(foreign);
    assertEquals(LocalDate.of(1980, 1, 1), row.getBirthDate());
    assertNull(row.getIdentifier());
    assertTrue(row.isForeignPerson());
    assertEquals("DE", row.getCountryCode());
  }
}
