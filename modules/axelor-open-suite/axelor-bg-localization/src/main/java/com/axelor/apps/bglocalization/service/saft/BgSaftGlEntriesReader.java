package com.axelor.apps.bglocalization.service.saft;

import com.axelor.db.JPA;
import com.google.inject.Singleton;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.persistence.TypedQuery;

/**
 * ЕДИНСТВЕНОТО МЯСТО, КОЕТО ЧЕТЕ БАЗАТА ЗА GeneralLedgerEntries — всичко надолу работи върху
 * стойности.
 *
 * <p><b>⚠️ СТАТИИТЕ СА ТЕЗИ НА САЛДАТА.</b> Списъкът редове е {@link
 * BgSaftMoveScope#TURNOVER_LINES} дословно — същият фрагмент, от който G2a смята оборота по сметка.
 * НАП сверява „начално салдо + обороти = крайно салдо“ за всяка сметка (QA, с. 30); с друг предикат
 * равенството пада във файл, в който и двете части са верни поотделно.
 *
 * <p><b>⚠️ ТУК ЧЕТЕНЕТО НА ПАРЧЕТА Е НУЖНО</b> (за разлика от салдата, които са {@code GROUP BY} в
 * базата): дневникът изнася ВСЕКИ ред. По модела на FEC ({@code MoveLineExportServiceImpl}) първо
 * се взимат само id-тата, после се чете по {@link #CHUNK} реда и след всяко парче {@code
 * JPA.clear()} — така сесията не натрупва нито един обект. Самите парчета са проекции със
 * стойности, не {@code MoveLine}.
 *
 * <p><b>⚠️ РЕДЪТ СЕ ПОДРЕЖДА В JAVA, НЕ В БАЗАТА.</b> Заявката за id-тата сортира само по id, без
 * съединения: всяко съединение в нея (журнал, период) би могло да изгуби ред, който салдата броят.
 * Журналите и статиите се подреждат от assembler-а.
 */
@Singleton
public class BgSaftGlEntriesReader {

  /** Редовете на дневника — {@link BgSaftMoveScope#TURNOVER_LINES} и нищо повече. */
  public static final String LINE_IDS =
      "SELECT self.id FROM MoveLine self WHERE "
          + BgSaftMoveScope.TURNOVER_LINES
          + " ORDER BY self.id";

  /**
   * Стойностите на едно парче редове. ⚠️ LEFT JOIN на партньора, периода, журнала и режима на
   * плащане: ред без тях остава, вместо да изчезне от дневника. Режимът е за Payments (G4) — {@link
   * BgSaftPaymentsReader} чете през същото парче.
   *
   * <p>G3b: ръчният код 600/700 на реда и подразбирането на сметката — за {@link
   * BgSaftWithholdingRouter}.
   */
  static final String LINES =
      "SELECT self.id, self.counter, self.debit, self.credit, self.description, account.id,"
          + " account.code, partner.id, move.id, move.reference, move.description, move.origin,"
          + " move.date, move.originDate, move.createdOn, period.fromDate, journal.id,"
          + " journal.code, journal.name, self.bgSaftTaxCode, account.bgSaftTaxCode,"
          + " paymentMode.code"
          + " FROM MoveLine self JOIN self.move move JOIN self.account account"
          + " LEFT JOIN self.partner partner"
          + " LEFT JOIN move.period period LEFT JOIN move.journal journal"
          + " LEFT JOIN move.paymentMode paymentMode"
          + " WHERE self.id IN (:ids)";

  /** Кодовете на данъците по редовете на едно парче. */
  static final String LINE_TAXES =
      "SELECT self.id, tax.code FROM MoveLine self JOIN self.taxLineSet taxLine"
          + " JOIN taxLine.tax tax WHERE self.id IN (:ids)";

  /** Колко реда се четат наведнъж — същото като {@code EXPORT_LINES_LIMIT} на FEC. */
  static final int CHUNK = 10_000;

  /** Редовете на дневника за периода, в реда на id-тата им. */
  public List<BgSaftGlEntriesAssembler.LineRow> lines(BgSaftMoveScope scope) {
    List<Long> ids = lineIds(scope);
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>(ids.size());
    for (int from = 0; from < ids.size(); from += CHUNK) {
      lines.addAll(chunk(ids.subList(from, Math.min(ids.size(), from + CHUNK))));
      clear();
    }
    return lines;
  }

  /** Само id-тата — едно число на ред. */
  protected List<Long> lineIds(BgSaftMoveScope scope) {
    TypedQuery<Long> query = JPA.em().createQuery(LINE_IDS, Long.class);
    scope.parameters(LINE_IDS).forEach(query::setParameter);
    return query.getResultList();
  }

  /** Едно парче: стойностите на редовете и данъците им. */
  protected List<BgSaftGlEntriesAssembler.LineRow> chunk(List<Long> ids) {
    Map<Long, List<String>> taxes = new HashMap<>();
    for (Object[] r : rows(LINE_TAXES, ids)) {
      if (r[1] != null) {
        taxes.computeIfAbsent(id(r[0]), k -> new ArrayList<>()).add(r[1].toString());
      }
    }
    List<BgSaftGlEntriesAssembler.LineRow> lines = new ArrayList<>(ids.size());
    for (Object[] r : rows(LINES, ids)) {
      Long lineId = id(r[0]);
      lines.add(
          new BgSaftGlEntriesAssembler.LineRow(lineId)
              .counter(r[1] == null ? null : ((Number) r[1]).intValue())
              .debit(decimal(r[2]))
              .credit(decimal(r[3]))
              .description((String) r[4])
              .account(id(r[5]), (String) r[6])
              .partnerId(r[7] == null ? null : id(r[7]))
              .moveId(id(r[8]))
              .reference((String) r[9])
              .moveDescription((String) r[10])
              .origin((String) r[11])
              .date((LocalDate) r[12])
              .originDate((LocalDate) r[13])
              .createdOn(r[14] == null ? null : ((LocalDateTime) r[14]).toLocalDate())
              .periodFrom((LocalDate) r[15])
              .journal(r[16] == null ? null : id(r[16]), (String) r[17], (String) r[18])
              .saftTaxCode((String) r[19])
              .accountSaftTaxCode((String) r[20])
              .paymentModeCode((String) r[21])
              .taxCodes(taxes.getOrDefault(lineId, Collections.emptyList())));
    }
    return lines;
  }

  /** Изчиства сесията след всяко парче. */
  protected void clear() {
    JPA.clear();
  }

  private static List<Object[]> rows(String jpql, List<Long> ids) {
    TypedQuery<Object[]> query = JPA.em().createQuery(jpql, Object[].class);
    query.setParameter("ids", ids);
    return query.getResultList();
  }

  private static Long id(Object value) {
    return ((Number) value).longValue();
  }

  private static BigDecimal decimal(Object value) {
    if (value == null || value instanceof BigDecimal) {
      return (BigDecimal) value;
    }
    return new BigDecimal(value.toString());
  }
}
