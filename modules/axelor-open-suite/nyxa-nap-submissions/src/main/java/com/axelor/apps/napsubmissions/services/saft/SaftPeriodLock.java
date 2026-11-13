package com.axelor.apps.napsubmissions.services.saft;

import com.axelor.apps.napsubmissions.common.submission.NapFilingLock;
import com.axelor.apps.napsubmissions.db.NapSubmission;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.NapSubmissionRepository;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import com.axelor.db.Query;
import com.axelor.i18n.I18n;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * ПЕРИОДЪТ НА ДРУЖЕСТВОТО Е ПОДАДЕН — ДРУГ ФАЙЛ ЗА НЕГО НЕ ТРЪГВА (G6b, T1).
 *
 * <p>{@link NapFilingLock} пази „един документ — едно подаване“ по реда на документа ({@code
 * relatedModel} + {@code relatedRecordId}), тоест в рамките на ЕДИН SaftReport. Тук е периодът:
 * всяко SAF-T подаване на същото дружество за същия период, по който и да е отчет. Периодът е
 * различен за трите вида ({@link Key}):
 *
 * <ul>
 *   <li><b>Месечният</b> — месецът ({@code period}), както от G6b.
 *   <li><b>Годишният</b> — календарната година ({@code reportYear}): той „няма как да бъде подаден
 *       на части“ (QA II.4.5, с. 21), тоест за една година има един файл.
 *   <li><b>При поискване</b> — искането на органа по приходите ({@code napRequestNumber}) и
 *       ПРИПОКРИВАЩИ СЕ дати. Едно искане може да се изпълни с два файла за два подпериода (QA
 *       II.4.5, с. 20–21), тоест 01.07–15.07 не заключва 16.07–31.07; ново искане е нов файл, дори
 *       за същите дати.
 * </ul>
 *
 * <ul>
 *   <li><b>Приет</b> — периодът е заключен: ново генериране, ново подаване и коригиращ файл се
 *       отказват. Коригиращият файл за приет период е извън обхвата (чакаме указания от НАП).
 *   <li><b>Подаден, чака изход</b> (и другите състояния от {@link NapFilingLock#FILED_STATUSES}) —
 *       първо се отбелязва изходът.
 *   <li><b>Отхвърлен</b>, техническа грешка, оттеглен — опити, не подавания: нов опит е позволен.
 * </ul>
 *
 * <p>⚠️ Годишният и при поискване се търсят през отчета на подаването ({@code self.saftReport}), не
 * през {@code NapSubmission.period}: там периодът е празен (той е месец).
 */
@Singleton
public class SaftPeriodLock {

  public static final String ACCEPTED_LOCKED =
      "Месецът е приет от НАП (вх. № %s). Коригиращ файл за приет месец не се генерира в Nyxa —"
          + " чакаме указания от НАП за коригиращите файлове.";

  public static final String PENDING_LOCKED =
      "Файлът за месеца е подаден (вх. № %s) и чака изход. Отбележете „Приет“ или „Отхвърлен“,"
          + " преди нов файл.";

  public static final String ANNUAL_ACCEPTED_LOCKED =
      "Годишният файл за %s е приет от НАП (вх. № %s). Коригиращ файл за приета година не се"
          + " генерира в Nyxa — чакаме указания от НАП за коригиращите файлове.";

  public static final String ANNUAL_PENDING_LOCKED =
      "Годишният файл за %s е подаден (вх. № %s) и чака изход. Отбележете „Приет“ или"
          + " „Отхвърлен“, преди нов файл.";

  public static final String ON_DEMAND_ACCEPTED_LOCKED =
      "Файлът при поискване за %s е приет от НАП (вх. № %s). Коригиращ файл не се генерира в Nyxa"
          + " — чакаме указания от НАП за коригиращите файлове.";

  public static final String ON_DEMAND_PENDING_LOCKED =
      "Файлът при поискване за %s е подаден (вх. № %s) и чака изход. Отбележете „Приет“ или"
          + " „Отхвърлен“, преди нов файл.";

  private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy");

  protected final NapSubmissionRepository submissionRepo;

  @Inject
  public SaftPeriodLock(NapSubmissionRepository submissionRepo) {
    this.submissionRepo = submissionRepo;
  }

  /**
   * Ключът на периода — условието върху подаванията (освен услугата, дружеството и статуса) и
   * параметрите му. Чиста стойност: от отчета, без заявки.
   */
  public static final class Key {

    public static final String MONTHLY = "self.period = :period";

    public static final String ANNUAL =
        "self.saftReport.reportTypeSelect = :reportType AND self.saftReport.reportYear = :year";

    /**
     * Припокриващи се дати; номерът на искането се сравнява след заявката ({@link #sameRequest}) —
     * без интервалите в краищата и без разлика в регистъра.
     */
    public static final String ON_DEMAND =
        "self.saftReport.reportTypeSelect = :reportType"
            + " AND self.saftReport.fromDate <= :toDate AND self.saftReport.toDate >= :fromDate";

    private final String where;
    private final Map<String, Object> parameters;
    private final String request;

    private Key(String where, Map<String, Object> parameters, String request) {
      this.where = where;
      this.parameters = Collections.unmodifiableMap(parameters);
      this.request = request;
    }

    /**
     * Ключът на отчета; {@code null}, когато периодът му още не е попълнен — няма какво да пази.
     */
    public static Key of(SaftReport report) {
      if (report == null || report.getCompany() == null) {
        return null;
      }
      Map<String, Object> parameters = new LinkedHashMap<>();
      String type = report.getReportTypeSelect();
      if (SaftReportRepository.REPORT_TYPE_ANNUAL.equals(type)) {
        // Генерираният getter на Axelor връща 0 за празно цяло число.
        if (year(report) == null) {
          return null;
        }
        parameters.put("reportType", type);
        parameters.put("year", year(report));
        return new Key(ANNUAL, parameters, null);
      }
      if (SaftReportRepository.REPORT_TYPE_ON_DEMAND.equals(type)) {
        String request = request(report.getNapRequestNumber());
        if (request == null || report.getFromDate() == null || report.getToDate() == null) {
          return null;
        }
        parameters.put("reportType", type);
        parameters.put("fromDate", report.getFromDate());
        parameters.put("toDate", report.getToDate());
        return new Key(ON_DEMAND, parameters, request);
      }
      if (report.getPeriod() == null) {
        return null;
      }
      parameters.put("period", report.getPeriod());
      return new Key(MONTHLY, parameters, null);
    }

    public String getWhere() {
      return where;
    }

    public Map<String, Object> getParameters() {
      return parameters;
    }

    /** Подаването е по същото искане — винаги, когато ключът не е по искане. */
    public boolean sameRequest(NapSubmission submission) {
      if (request == null) {
        return true;
      }
      SaftReport report = submission.getSaftReport();
      return report != null && request.equals(request(report.getNapRequestNumber()));
    }

    /** Номерът на искането, сравняван без интервали в краищата и без разлика в регистъра. */
    static String request(String number) {
      if (number == null || number.isBlank()) {
        return null;
      }
      return number.trim().toLowerCase(Locale.ROOT);
    }
  }

  /**
   * Подаването, което заключва периода на отчета; {@code null}, ако няма. Приетото е с предимство.
   */
  public NapSubmission filed(SaftReport report) {
    Key key = Key.of(report);
    if (key == null) {
      return null;
    }
    NapSubmission accepted = find(report, key, NapSubmissionRepository.STATUS_ACCEPTED);
    return accepted != null ? accepted : find(report, key, null);
  }

  /** Отказът за ново генериране / подаване; {@code null}, когато периодът е свободен. */
  public String refusal(SaftReport report) {
    return refusal(filed(report));
  }

  /** Само текстът — по подаването, което заключва, и по вида на неговия отчет. */
  public static String refusal(NapSubmission filed) {
    if (filed == null) {
      return null;
    }
    String number = number(filed);
    boolean accepted =
        NapSubmissionRepository.STATUS_ACCEPTED.equals(filed.getSubmissionStatusSelect());
    SaftReport report = filed.getSaftReport();
    String type = report == null ? null : report.getReportTypeSelect();
    if (SaftReportRepository.REPORT_TYPE_ANNUAL.equals(type)) {
      return String.format(
          I18n.get(accepted ? ANNUAL_ACCEPTED_LOCKED : ANNUAL_PENDING_LOCKED),
          label(report),
          number);
    }
    if (SaftReportRepository.REPORT_TYPE_ON_DEMAND.equals(type)) {
      return String.format(
          I18n.get(accepted ? ON_DEMAND_ACCEPTED_LOCKED : ON_DEMAND_PENDING_LOCKED),
          label(report),
          number);
    }
    return String.format(I18n.get(accepted ? ACCEPTED_LOCKED : PENDING_LOCKED), number);
  }

  /** Само приетият период (за коригиращия файл). */
  public boolean accepted(SaftReport report) {
    Key key = Key.of(report);
    return key != null && find(report, key, NapSubmissionRepository.STATUS_ACCEPTED) != null;
  }

  /**
   * За какво е отчетът, с думи — в находките и в отказите: месецът, годината или датите с искането.
   */
  public static String label(SaftReport report) {
    if (report == null) {
      return null;
    }
    String type = report.getReportTypeSelect();
    if (SaftReportRepository.REPORT_TYPE_ANNUAL.equals(type)) {
      Integer year = year(report);
      return year == null ? null : String.valueOf(year);
    }
    if (SaftReportRepository.REPORT_TYPE_ON_DEMAND.equals(type)) {
      LocalDate from = report.getFromDate();
      LocalDate to = report.getToDate();
      if (from == null || to == null) {
        return null;
      }
      String dates = DAY.format(from) + " – " + DAY.format(to);
      String request = report.getNapRequestNumber();
      return request == null || request.isBlank()
          ? dates
          : dates + " (" + I18n.get("искане №") + " " + request.trim() + ")";
    }
    return report.getPeriod() == null ? null : report.getPeriod().getName();
  }

  /** Годината на годишния отчет; {@code null} и за 0 — така Axelor чете празното цяло число. */
  static Integer year(SaftReport report) {
    int year = report.getReportYear();
    return year == 0 ? null : year;
  }

  protected NapSubmission find(SaftReport report, Key key, String status) {
    Query<NapSubmission> query =
        submissionRepo
            .all()
            .filter(
                "self.serviceCodeSelect = :saft AND self.company = :company"
                    + " AND self.submissionStatusSelect IN :statuses AND "
                    + key.getWhere())
            .bind("saft", NapSubmissionRepository.SERVICE_SAFT)
            .bind("company", report.getCompany())
            .bind("statuses", status == null ? NapFilingLock.FILED_STATUSES : List.of(status));
    key.getParameters().forEach(query::bind);
    return query.order("id").fetch().stream().filter(key::sameRequest).findFirst().orElse(null);
  }

  private static String number(NapSubmission s) {
    String n = s.getNapIncomingNumber();
    if (n == null || n.isBlank()) {
      n = s.getEntryNumber();
    }
    return n == null || n.isBlank() ? "—" : n;
  }
}
