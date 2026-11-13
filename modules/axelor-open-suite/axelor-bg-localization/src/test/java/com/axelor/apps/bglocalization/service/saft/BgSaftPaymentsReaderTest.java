package com.axelor.apps.bglocalization.service.saft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.repo.JournalTypeRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.hibernate.hql.internal.ast.HqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЧЕТЕНЕТО НА ПЛАЩАНИЯТА — ПРЕДИКАТЪТ НА ДНЕВНИКА ПЛЮС ПРИЗНАКА НА ПЛАЩАНЕТО, ПАРЧЕТАТА НА G3.
 *
 * <p>⚠️ Базата не се пита: заявката се сверява структурно и синтактично, а СЕМАНТИКАТА на признака
 * — с {@link BgSaftJpqlWhere} върху истинския ѝ текст: кои статии са плащания и кои, въпреки режима
 * или произхода си, не са.
 */
class BgSaftPaymentsReaderTest {

  @Test
  @DisplayName("Списъкът е предикатът на дневника ДОСЛОВНО плюс признака — без съединения")
  void същият_предикат() {
    assertEquals(
        "SELECT self.id FROM MoveLine self WHERE "
            + BgSaftMoveScope.PAYMENT_LINES
            + " ORDER BY self.id",
        BgSaftPaymentsReader.PAYMENT_LINE_IDS);
    assertTrue(BgSaftMoveScope.PAYMENT_LINES.startsWith(BgSaftMoveScope.TURNOVER_LINES + " AND "));
    assertFalse(BgSaftPaymentsReader.PAYMENT_LINE_IDS.contains(" JOIN "));
    BgSaftMoveScope scope = BgSaftMoveScope.of(BgSaftFixtures.april());
    assertEquals(
        Set.of("companyId", "posted", "fromDate", "toDate", "openingOrigin"),
        scope.parameters(BgSaftPaymentsReader.PAYMENT_LINE_IDS).keySet());
  }

  /** Читател без база: id-тата са подадени, парчетата и изчистванията се броят. */
  private static final class Counting extends BgSaftPaymentsReader {
    private final List<String> calls = new ArrayList<>();

    private Counting() {
      super(null);
    }

    @Override
    protected List<Long> lineIds(BgSaftMoveScope scope) {
      return LongStream.rangeClosed(1, BgSaftGlEntriesReader.CHUNK + 1)
          .boxed()
          .collect(Collectors.toList());
    }

    @Override
    protected List<BgSaftGlEntriesAssembler.LineRow> chunk(List<Long> chunk) {
      calls.add("chunk " + chunk.size());
      return chunk.stream().map(BgSaftGlEntriesAssembler.LineRow::new).collect(Collectors.toList());
    }

    @Override
    protected void clear() {
      calls.add("clear");
    }
  }

  @Test
  @DisplayName("Парчетата и изчистването са тези на дневника (FEC)")
  void парчетата() {
    Counting reader = new Counting();
    assertEquals(BgSaftGlEntriesReader.CHUNK + 1, reader.lines(null).size());
    assertEquals(List.of("chunk 10000", "clear", "chunk 1", "clear"), reader.calls);
    // Режимът идва в същото парче — LEFT JOIN, статия без режим не изчезва.
    assertTrue(BgSaftGlEntriesReader.LINES.contains(" LEFT JOIN move.paymentMode paymentMode"));
    assertTrue(BgSaftGlEntriesReader.LINES.contains(", paymentMode.code FROM "));
  }

  @Test
  @DisplayName("Заявките минават парсера на Hibernate — а счупената не минава")
  void синтаксисът() {
    parse(BgSaftPaymentsReader.PAYMENT_LINE_IDS);
    parse(BgSaftGlEntriesReader.LINES);
    assertThrows(
        Exception.class,
        () -> parse("SELECT self.id FROM MoveLine self WHERE self.move.paymentMode IS NOT"));
  }

  @Test
  @DisplayName("Свойствата по пътищата съществуват в модела")
  void свойствата() throws Exception {
    Class<?> move = Class.forName("com.axelor.apps.account.db.Move");
    move.getMethod("getPaymentMode");
    move.getMethod("getFunctionalOriginSelect");
    move.getMethod("getJournal");
    Class.forName("com.axelor.apps.account.db.Journal").getMethod("getJournalType");
    Class.forName("com.axelor.apps.account.db.JournalType").getMethod("getTechnicalTypeSelect");
    Class.forName("com.axelor.apps.account.db.PaymentMode").getMethod("getCode");
    Class<?> mapping = Class.forName("com.axelor.apps.bglocalization.db.BgSafPaymentMapping");
    for (String getter :
        List.of("getPaymentModeCode", "getMethodCode", "getMechanismCode", "getIsExcluded")) {
      mapping.getMethod(getter);
    }
  }

  // ─────────────── семантиката на признака ───────────────

  /** Статия за предиката: пътищата ѝ със стойности. */
  private static Map<String, Object> move(
      LocalDate date, int journalType, Integer origin, boolean paymentMode) {
    Map<String, Object> paths = new HashMap<>();
    paths.put("self.move.company.id", BgSaftFixtures.COMPANY_ID);
    paths.put("self.move.statusSelect", MoveRepository.STATUS_ACCOUNTED);
    paths.put("self.move.ignoreInAccountingOk", false);
    paths.put("self.move.date", date);
    paths.put("self.move.functionalOriginSelect", origin);
    paths.put("self.move.journal.journalType.technicalTypeSelect", journalType);
    paths.put("self.move.paymentMode", paymentMode ? "РЕЖИМ" : null);
    return paths;
  }

  @Test
  @DisplayName(
      "Плащане: разплащателен журнал или произход „плащане“ С режим — нито едното поотделно")
  void кои_статии_са_плащания() {
    LocalDate april = LocalDate.of(2026, 4, 10);
    int treasury = JournalTypeRepository.TECHNICAL_TYPE_SELECT_TREASURY;
    int other = JournalTypeRepository.TECHNICAL_TYPE_SELECT_OTHER;
    int payment = MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT;
    Map<String, Map<String, Object>> moves = new LinkedHashMap<>();
    moves.put("плащане по фактура в BNK", move(april, treasury, payment, true));
    moves.put("ръчна банкова статия без режим", move(april, treasury, null, false));
    moves.put("прихващане с режим в MOA", move(april, other, payment, true));
    moves.put(
        "фактура с режима ѝ",
        move(
            april,
            JournalTypeRepository.TECHNICAL_TYPE_SELECT_SALE,
            MoveRepository.FUNCTIONAL_ORIGIN_SALE,
            true));
    moves.put("фиш: произход „плащане“ без режим", move(april, other, payment, false));
    moves.put("банкова статия през март", move(LocalDate.of(2026, 3, 31), treasury, payment, true));
    moves.put(
        "откриваща статия по банката",
        move(april, treasury, MoveRepository.FUNCTIONAL_ORIGIN_OPENING, false));

    BgSaftMoveScope scope = BgSaftMoveScope.of(BgSaftFixtures.april());
    Map<String, Object> parameters = scope.parameters(BgSaftPaymentsReader.PAYMENT_LINE_IDS);
    String where = BgSaftJpqlWhere.where(BgSaftPaymentsReader.PAYMENT_LINE_IDS);
    List<String> selected = new ArrayList<>();
    moves.forEach(
        (name, paths) -> {
          if (BgSaftJpqlWhere.matches(where, parameters, path -> pathOf(paths, path))) {
            selected.add(name);
          }
        });
    assertEquals(
        List.of(
            "плащане по фактура в BNK",
            "ръчна банкова статия без режим",
            "прихващане с режим в MOA"),
        selected);
  }

  private static Object pathOf(Map<String, Object> paths, String path) {
    if (!paths.containsKey(path)) {
      throw new IllegalStateException("непознат път " + path);
    }
    return paths.get(path);
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
