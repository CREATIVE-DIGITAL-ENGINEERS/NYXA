package com.axelor.apps.bglocalization.service.vat;

import com.axelor.db.Model;
import com.axelor.db.Query;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * КОЙ ДОКУМЕНТ Е В ДАНЪЧНИЯ ПЕРИОД — едно правило за колектора на дневниците и за справките на
 * авансите: изричният „Период на отразяване по ЗДДС“ на фактурата ({@link BgVatReportingPeriod}),
 * иначе датата (на статията или на фактурата).
 *
 * <p>Параметрите са {@code :from}/{@code :to} (датите на периода) и {@code :periodFrom}/{@code
 * :periodTo} (същото като {@code ГГГГММ}; низовете се сравняват като числа, защото са с еднаква
 * дължина) — {@link #bind}.
 *
 * <p>⚠️ <b>Без неявно съединяване.</b> {@code self.invoice.bgVatReportingPeriod} в JPQL е INNER
 * JOIN и би изпуснал всяка статия без фактура — протоколите, ръчните статии, митата. Затова
 * изричният период се чете в подзаявка, а {@code self.invoice.id} е самият външен ключ.
 *
 * <p>⚠️ {@code NULL <> ''} не е истина: празният период и {@code NULL} не вкарват фактурата в
 * подзаявката „отразена другаде“.
 */
public final class BgVatPeriodScope {

  /** Фактурите, отразени изрично в периода. */
  static final String REPORTED_HERE =
      "SELECT rp.id FROM Invoice rp WHERE rp.bgVatReportingPeriod >= :periodFrom"
          + " AND rp.bgVatReportingPeriod <= :periodTo";

  /** Фактурите, отразени изрично в друг период. */
  static final String REPORTED_ELSEWHERE =
      "SELECT rp.id FROM Invoice rp WHERE rp.bgVatReportingPeriod <> ''"
          + " AND (rp.bgVatReportingPeriod < :periodFrom OR rp.bgVatReportingPeriod > :periodTo)";

  /**
   * Статия (корен {@code Move}, псевдоним {@code self}) в периода: с дата в него, ако фактурата ѝ
   * не е отразена другаде, или на фактура, отразена в него. Статиите на авансите и статията при
   * окончателна със сума 0 носят {@code Move.invoice} — следват фактурата си.
   */
  public static final String MOVES =
      "((self.date >= :from AND self.date <= :to"
          + " AND (self.invoice IS NULL OR self.invoice.id NOT IN ("
          + REPORTED_ELSEWHERE
          + ")))"
          + " OR self.invoice.id IN ("
          + REPORTED_HERE
          + "))";

  private BgVatPeriodScope() {}

  /**
   * Фактура (корен {@code Invoice}, псевдоним {@code self}) в периода: без изричен период — по
   * {@code date} (напр. {@code self.invoiceDate} или {@code self.move.date}), с изричен — по него.
   */
  public static String invoices(String date) {
    return "((COALESCE(self.bgVatReportingPeriod, '') = '' AND "
        + date
        + " >= :from AND "
        + date
        + " <= :to)"
        + " OR (self.bgVatReportingPeriod >= :periodFrom AND self.bgVatReportingPeriod <= :periodTo))";
  }

  /** Четирите параметъра на периода. */
  public static Map<String, Object> parameters(LocalDate from, LocalDate to) {
    Map<String, Object> parameters = new LinkedHashMap<>();
    parameters.put("from", from);
    parameters.put("to", to);
    parameters.put("periodFrom", BgVatReportingPeriod.of(from));
    parameters.put("periodTo", BgVatReportingPeriod.of(to));
    return parameters;
  }

  /** Връзва четирите параметъра на периода към заявката. */
  public static <T extends Model> Query<T> bind(Query<T> query, LocalDate from, LocalDate to) {
    parameters(from, to).forEach(query::bind);
    return query;
  }
}
