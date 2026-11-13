package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.hibernate.hql.internal.ast.HqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЧЕТЕНЕТО НА ДНЕВНИКА — НА ПАРЧЕТА, С ИЗЧИСТВАНЕ, ВЪРХУ ПРЕДИКАТА НА САЛДАТА.
 *
 * <p>⚠️ Базата не се пита: заявките се сверяват структурно и синтактично (парсерът на Hibernate, с
 * отрицателна контрола), а цикълът по парчета — през подклас, който брои извикванията. Числата на
 * статиите се проверяват в {@link BgSaftGlReconciliationTest}.
 */
class BgSaftGlEntriesReaderTest {

  /** Читател без база: id-тата са подадени, парчетата и изчистванията се броят. */
  private static final class Counting extends BgSaftGlEntriesReader {
    private final List<Long> ids;
    private final List<List<Long>> chunks = new ArrayList<>();
    private final List<String> calls = new ArrayList<>();

    private Counting(long count) {
      this.ids = LongStream.rangeClosed(1, count).boxed().collect(Collectors.toList());
    }

    @Override
    protected List<Long> lineIds(BgSaftMoveScope scope) {
      return ids;
    }

    @Override
    protected List<BgSaftGlEntriesAssembler.LineRow> chunk(List<Long> chunk) {
      chunks.add(new ArrayList<>(chunk));
      calls.add("chunk " + chunk.size());
      return chunk.stream().map(BgSaftGlEntriesAssembler.LineRow::new).collect(Collectors.toList());
    }

    @Override
    protected void clear() {
      calls.add("clear");
    }
  }

  @Test
  @DisplayName("Над прага заявката се реже на парчета и сесията се чисти след ВСЯКО")
  void над_прага() {
    Counting reader = new Counting(2L * BgSaftGlEntriesReader.CHUNK + 1);
    List<BgSaftGlEntriesAssembler.LineRow> lines = reader.lines(null);
    assertEquals(10_000, BgSaftGlEntriesReader.CHUNK);
    assertEquals(
        List.of("chunk 10000", "clear", "chunk 10000", "clear", "chunk 1", "clear"), reader.calls);
    // Нищо не се губи и не се повтаря, редът е този на id-тата.
    assertEquals(20_001, lines.size());
    List<Long> flat = new ArrayList<>();
    reader.chunks.forEach(flat::addAll);
    assertEquals(reader.ids, flat);
  }

  @Test
  @DisplayName("До прага — едно парче и едно изчистване; без редове — нито едно")
  void до_прага() {
    Counting small = new Counting(BgSaftGlEntriesReader.CHUNK);
    assertEquals(BgSaftGlEntriesReader.CHUNK, small.lines(null).size());
    assertEquals(List.of("chunk 10000", "clear"), small.calls);

    Counting none = new Counting(0);
    assertTrue(none.lines(null).isEmpty());
    assertTrue(none.calls.isEmpty());
  }

  @Test
  @DisplayName("Списъкът редове е предикатът на оборота ДОСЛОВНО — без съединения и без добавки")
  void същият_предикат() {
    assertEquals(
        "SELECT self.id FROM MoveLine self WHERE "
            + BgSaftMoveScope.TURNOVER_LINES
            + " ORDER BY self.id",
        BgSaftGlEntriesReader.LINE_IDS);
    // И салдата стъпват на същия фрагмент.
    assertTrue(
        BgSaftMoveScope.TURNOVER_BY_ACCOUNT.contains(" WHERE " + BgSaftMoveScope.TURNOVER_LINES));
    assertFalse(BgSaftGlEntriesReader.LINE_IDS.contains(" JOIN "));
  }

  @Test
  @DisplayName("Парчето: партньорът, периодът и журналът с LEFT JOIN — ред без тях не изчезва")
  void парчето() {
    String lines = BgSaftGlEntriesReader.LINES;
    assertTrue(lines.contains(" LEFT JOIN self.partner partner"));
    assertTrue(lines.contains(" LEFT JOIN move.period period"));
    assertTrue(lines.contains(" LEFT JOIN move.journal journal"));
    assertTrue(lines.endsWith(" WHERE self.id IN (:ids)"));
    assertTrue(BgSaftGlEntriesReader.LINE_TAXES.endsWith(" WHERE self.id IN (:ids)"));
    // Параметрите на парчето не са на обхвата — подават се само id-тата.
    BgSaftMoveScope scope = BgSaftMoveScope.of(BgSaftFixtures.april());
    assertThrows(IllegalStateException.class, () -> scope.parameters(lines));
    assertEquals(
        Set.of("companyId", "posted", "fromDate", "toDate", "openingOrigin"),
        scope.parameters(BgSaftGlEntriesReader.LINE_IDS).keySet());
  }

  @Test
  @DisplayName("Трите заявки минават парсера на Hibernate — а счупената не минава")
  void синтаксисът() {
    for (String hql :
        List.of(
            BgSaftGlEntriesReader.LINE_IDS,
            BgSaftGlEntriesReader.LINES,
            BgSaftGlEntriesReader.LINE_TAXES)) {
      parse(hql);
    }
    assertThrows(
        Exception.class, () -> parse("SELECT FROM MoveLine self WHERE AND self.id IN (:ids)"));
  }

  @Test
  @DisplayName("Свойствата по пътищата съществуват в модела")
  void свойствата() throws Exception {
    Class<?> moveLine = Class.forName("com.axelor.apps.account.db.MoveLine");
    for (String getter :
        List.of(
            "getCounter",
            "getDebit",
            "getCredit",
            "getDescription",
            "getAccount",
            "getPartner",
            "getMove",
            "getTaxLineSet")) {
      moveLine.getMethod(getter);
    }
    Class<?> move = Class.forName("com.axelor.apps.account.db.Move");
    for (String getter :
        List.of(
            "getReference",
            "getDescription",
            "getOrigin",
            "getDate",
            "getOriginDate",
            "getCreatedOn",
            "getPeriod",
            "getJournal")) {
      move.getMethod(getter);
    }
    Class.forName("com.axelor.apps.base.db.Period").getMethod("getFromDate");
    Class.forName("com.axelor.apps.account.db.Journal").getMethod("getCode");
    Class.forName("com.axelor.apps.account.db.Journal").getMethod("getName");
    Class.forName("com.axelor.apps.account.db.Account").getMethod("getCode");
    Class.forName("com.axelor.apps.account.db.TaxLine").getMethod("getTax");
    Class.forName("com.axelor.apps.account.db.Tax").getMethod("getCode");
  }

  @Test
  @DisplayName("G3b: ръчният код 600/700 на реда и подразбирането на сметката са в проекцията")
  void кодътНаРедаИНаСметката() throws Exception {
    assertTrue(
        BgSaftGlEntriesReader.LINES.contains(
            " journal.name, self.bgSaftTaxCode, account.bgSaftTaxCode, paymentMode.code FROM "));
    Class.forName("com.axelor.apps.account.db.MoveLine").getMethod("getBgSaftTaxCode");
    Class.forName("com.axelor.apps.account.db.Account").getMethod("getBgSaftTaxCode");
  }

  private static void parse(String hql) {
    HqlParser parser = HqlParser.getInstance(hql);
    try {
      parser.statement();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    parser.getParseErrorHandler().throwQueryException();
  }
}
