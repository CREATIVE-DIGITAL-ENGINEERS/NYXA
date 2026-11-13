package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.service.saft.dto.SaftFileKind;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * ЗА КАКВО Е ФАЙЛЪТ — видът и периодът, от които идват Header-ът (S.SC.*, S.H.11) и името на файла.
 *
 * <p><b>Чиста стойност, нула заявки.</b> {@link #of(SaftReport)} я взима от отчета по вида му:
 * {@code period} (месечният), {@code reportYear} (годишният), {@code fromDate}/{@code toDate} (при
 * поискване, T1).
 *
 * <p><b>⚠️ ТРИТЕ ВИДА СА РАЗЛИЧНИ ПЕРИОДИ, НЕ РАЗЛИЧНИ ДЪЛЖИНИ.</b>
 *
 * <ul>
 *   <li><b>Месечният</b> е ЕДИН календарен месец — името му е {@code ЕИК_MM_YYYY} (Прил. 3 т. I.2),
 *       тоест период, който прекрачва месеца, няма вярно име.
 *   <li><b>Годишният</b> е ЦЯЛАТА календарна година: „няма как да бъде подаден на части“ (QA с.
 *       21). Затова се строи от година, а не от две дати — части не могат да се поискат.
 *   <li><b>При поискване</b> е произволен период, който органът по приходите определя, и може да е
 *       на два файла за два подпериода (QA с. 20–21).
 * </ul>
 *
 * <p><b>⚠️ КЛОНЪТ НА SelectionCriteria Е РЕШЕНИЕ, НЕ ИЗВОД.</b> Книгата (S.SC.3–8) допуска датите
 * ИЛИ периодите за всеки вид. Тук: месечният и годишният — с периоди (годишният 1–12 на
 * календарната година), при поискване — с дати, защото той е произволен период. Официалните годишен
 * и при-поискване образци пишат дати; годишният е с 2022-01-01…2022-01-31, тоест копиран от
 * месечен, а не пример за година.
 */
public final class BgSaftSelection {

  private final SaftFileKind kind;
  private final LocalDate fromDate;
  private final LocalDate toDate;

  private BgSaftSelection(SaftFileKind kind, LocalDate fromDate, LocalDate toDate) {
    this.kind = kind;
    this.fromDate = fromDate;
    this.toDate = toDate;
  }

  /**
   * Месечният файл — отчетният период на дружеството.
   *
   * @throws IllegalArgumentException без период с начало и край, или с период извън един календарен
   *     месец
   */
  public static BgSaftSelection monthly(Period period) {
    if (period == null || period.getFromDate() == null || period.getToDate() == null) {
      throw new IllegalArgumentException("SAF-T: Header без период с начало и край");
    }
    LocalDate from = period.getFromDate();
    LocalDate to = period.getToDate();
    if (to.isBefore(from) || !YearMonth.from(from).equals(YearMonth.from(to))) {
      throw new IllegalArgumentException(
          "SAF-T: месечният файл е за един календарен месец, а периодът е " + from + " – " + to);
    }
    return new BgSaftSelection(SaftFileKind.MONTHLY, from, to);
  }

  /** Годишният файл — от 1 януари до 31 декември на календарната година. */
  public static BgSaftSelection annual(int year) {
    if (year < 1000 || year > 9999) {
      throw new IllegalArgumentException(
          "SAF-T: годината на годишния файл е четирицифрена, а е " + year);
    }
    return new BgSaftSelection(
        SaftFileKind.ANNUAL, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
  }

  /**
   * Файлът при поискване — периодът, който органът по приходите е поискал.
   *
   * @throws IllegalArgumentException без двете дати или с край преди началото
   */
  public static BgSaftSelection onDemand(LocalDate from, LocalDate to) {
    if (from == null || to == null) {
      throw new IllegalArgumentException("SAF-T: файлът при поискване иска начална и крайна дата");
    }
    if (to.isBefore(from)) {
      throw new IllegalArgumentException(
          "SAF-T: крайната дата " + to + " е преди началната " + from);
    }
    return new BgSaftSelection(SaftFileKind.ON_DEMAND, from, to);
  }

  /**
   * От отчета, по вида му.
   *
   * @throws IllegalArgumentException без отчет, с непознат вид или без месеца / годината / датите,
   *     които видът иска
   */
  public static BgSaftSelection of(SaftReport report) {
    if (report == null) {
      throw new IllegalArgumentException("SAF-T: няма отчет");
    }
    String type = report.getReportTypeSelect();
    if (SaftReportRepository.REPORT_TYPE_ANNUAL.equals(type)) {
      // Празната година идва като 0 (генерираният getter) — annual() я отказва.
      return annual(report.getReportYear());
    }
    if (SaftReportRepository.REPORT_TYPE_ON_DEMAND.equals(type)) {
      return onDemand(report.getFromDate(), report.getToDate());
    }
    if (SaftReportRepository.REPORT_TYPE_MONTHLY.equals(type)) {
      return monthly(report.getPeriod());
    }
    throw new IllegalArgumentException("SAF-T: непознат вид на отчета „" + type + "“");
  }

  public SaftFileKind getKind() {
    return kind;
  }

  public LocalDate getFromDate() {
    return fromDate;
  }

  public LocalDate getToDate() {
    return toDate;
  }

  /** Клонът с периоди (S.SC.5–8) — месечният и годишният; при поискване пише дати. */
  public boolean byPeriods() {
    return kind != SaftFileKind.ON_DEMAND;
  }
}
