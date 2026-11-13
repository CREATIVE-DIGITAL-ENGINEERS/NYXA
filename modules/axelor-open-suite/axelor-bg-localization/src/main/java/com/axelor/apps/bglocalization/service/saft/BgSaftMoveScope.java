package com.axelor.apps.bglocalization.service.saft;

import com.axelor.apps.account.db.repo.JournalTypeRepository;
import com.axelor.apps.account.db.repo.MoveRepository;
import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.base.db.Year;
import com.axelor.apps.base.db.repo.YearRepository;
import com.axelor.apps.napsubmissions.db.SaftReport;
import com.axelor.apps.napsubmissions.db.repo.SaftReportRepository;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * КОИ СТАТИИ ВЛИЗАТ В SAF-T — ЕДНО МЯСТО ЗА САЛДАТА (G2), ЗА GeneralLedgerEntries (G3) И ЗА
 * ПЛАЩАНИЯТА (G4).
 *
 * <p><b>⚠️ ЗАЩО ОТДЕЛЕН КЛАС.</b> НАП сверява началното и крайното салдо на всяка сметка срещу
 * оборотите ѝ в GeneralLedgerEntries (QA, март 2026, с. 30). Ако салдата и редовете на дневника
 * четат статиите с два различни предиката, равенството „начално + обороти = крайно“ пада във файл,
 * в който и двете части са верни поотделно. Затова предикатът е ЕДИН и G3 го преизползва дословно.
 *
 * <p><b>Обхватът:</b> статиите на дружеството със статус {@link MoveRepository#STATUS_ACCOUNTED},
 * без отбелязаните „да не се отчитат“. DAYBOOK НЕ влиза — същото решение като в {@code
 * BgVatDocumentCollector}.
 *
 * <p><b>⚠️ НАЧАЛНОТО САЛДО Е В РАМКИТЕ НА ФИНАНСОВАТА ГОДИНА.</b> Сумират се статиите на годината
 * преди началото на периода ПЛЮС откриващите статии ({@code functionalOriginSelect = 1}) до края
 * му. Така разходните и приходните сметки тръгват от нула всяка година, а балансовите носят салдото
 * си през откриващата статия, която годишното приключване на Axelor пише в първия ден на годината.
 * Откриващата статия е НАЧАЛНО САЛДО, не оборот — затова оборотът я изключва изрично.
 *
 * <p><b>⚠️ ДВЕ NULL-КОЛОНИ, КОИТО JPQL НЕ ПРОЩАВА.</b> {@code functionalOriginSelect} и {@code
 * ignoreInAccountingOk} могат да са NULL в базата (генерираните getter-и връщат 0 и false, тоест в
 * Java NULL не се вижда). В SQL {@code NULL <> 1} не е истина: „различно от откриваща“ без {@code
 * IS NULL} би изхвърлило от оборота всяка статия без функционален произход.
 */
public final class BgSaftMoveScope {

  /** Единственият статус, който влиза. */
  public static final int POSTED = MoveRepository.STATUS_ACCOUNTED;

  /** Откриващата статия на годината. */
  public static final int OPENING_ORIGIN = MoveRepository.FUNCTIONAL_ORIGIN_OPENING;

  /**
   * Осчетоводените статии на дружеството — за заявка, чийто корен е {@code MoveLine}. G3 строи
   * редовете на дневника върху същия фрагмент.
   */
  public static final String POSTED_LINES =
      "self.move.company.id = :companyId"
          + " AND self.move.statusSelect = :posted"
          + " AND (self.move.ignoreInAccountingOk = false"
          + " OR self.move.ignoreInAccountingOk IS NULL)";

  /** Същото за заявка, чийто корен е {@code Move}. */
  public static final String POSTED_MOVES =
      "self.company.id = :companyId"
          + " AND self.statusSelect = :posted"
          + " AND (self.ignoreInAccountingOk = false OR self.ignoreInAccountingOk IS NULL)";

  /** Началното салдо: годината преди периода плюс откриващите статии до края му. */
  public static final String OPENING_LINES =
      POSTED_LINES
          + " AND self.move.period.year.id = :yearId"
          + " AND (self.move.date < :fromDate"
          + " OR (self.move.functionalOriginSelect = :openingOrigin"
          + " AND self.move.date <= :toDate))";

  /** Оборотът в периода — без откриващите статии, които са вече в началното салдо. */
  public static final String TURNOVER_LINES =
      POSTED_LINES
          + " AND self.move.date >= :fromDate"
          + " AND self.move.date <= :toDate"
          + " AND (self.move.functionalOriginSelect IS NULL"
          + " OR self.move.functionalOriginSelect <> :openingOrigin)";

  /**
   * Редовете на статиите-ПЛАЩАНИЯ в периода (Payments, G4) — {@link #TURNOVER_LINES} плюс признака
   * на плащането, тоест същите статии като в дневника: TransactionID на плащането (SD.P.8) сочи
   * транзакция от GeneralLedgerEntries (QA, с. 66).
   *
   * <p><b>⚠️ ПЛАЩАНЕ Е СТАТИЯ ПО РАЗПЛАЩАТЕЛЕН ЖУРНАЛ, ИЛИ ПЛАЩАНЕ С РЕЖИМ.</b> Журналът е от вид
   * „Treasury“ (в BG_COA: BNK, CSH, FIS) — там пишат плащанията по фактури, ваучерите, банковите
   * нареждания, банковото извлечение и ръчните банкови и касови статии. Плащане с режим извън такъв
   * журнал (прихващане, подотчетно лице) се познава по функционалния произход „плащане“ ЗАЕДНО с
   * режима. Нито едното поотделно не стига: статията на фактурата носи режима на фактурата, а
   * статията на фиша е с произход „плащане“ без режим.
   */
  public static final String PAYMENT_LINES =
      TURNOVER_LINES
          + " AND (self.move.journal.journalType.technicalTypeSelect = "
          + JournalTypeRepository.TECHNICAL_TYPE_SELECT_TREASURY
          + " OR (self.move.functionalOriginSelect = "
          + MoveRepository.FUNCTIONAL_ORIGIN_PAYMENT
          + " AND self.move.paymentMode IS NOT NULL))";

  /**
   * Статиите на периода — същото като {@link #TURNOVER_LINES}, за заявка, чийто корен е {@code
   * Move}. Документите на периода стъпват на него: продуктите и данъците на фактурите (G2c), а
   * после и самите фактури (G5) — иначе документ и статията му биха влизали в различни месеци.
   */
  public static final String PERIOD_MOVES =
      POSTED_MOVES
          + " AND self.date >= :fromDate"
          + " AND self.date <= :toDate"
          + " AND (self.functionalOriginSelect IS NULL"
          + " OR self.functionalOriginSelect <> :openingOrigin)";

  /** Кодовете на данъците по редовете на статиите в периода. */
  public static final String TAXES_ON_LINES =
      "SELECT DISTINCT tax.code FROM MoveLine self JOIN self.taxLineSet taxLine"
          + " JOIN taxLine.tax tax WHERE "
          + TURNOVER_LINES;

  /** Редовете в периода без данък — за тях дневникът пише 000/000000 (TAX-IMP, бележка R5). */
  public static final String UNTAXED_LINES =
      "SELECT COUNT(self.id) FROM MoveLine self WHERE "
          + TURNOVER_LINES
          + " AND self.taxLineSet IS EMPTY";

  /** Кодовете на данъците по редовете на фактурите, чиито статии са в периода. */
  public static final String TAXES_ON_INVOICE_LINES =
      "SELECT DISTINCT tax.code FROM InvoiceLine line JOIN line.invoice invoice"
          + " JOIN invoice.move self JOIN line.taxLineSet taxLine JOIN taxLine.tax tax WHERE "
          + PERIOD_MOVES;

  /** Началното салдо по сметка, дебит положителен. */
  public static final String OPENING_BY_ACCOUNT =
      "SELECT self.account.id, SUM(self.debit - self.credit) FROM MoveLine self WHERE "
          + OPENING_LINES
          + " GROUP BY self.account.id";

  /** Дебитният и кредитният оборот в периода по сметка. */
  public static final String TURNOVER_BY_ACCOUNT =
      "SELECT self.account.id, SUM(self.debit), SUM(self.credit) FROM MoveLine self WHERE "
          + TURNOVER_LINES
          + " GROUP BY self.account.id";

  /**
   * Сметките на клиентите и доставчиците — подават се от извикващия (G2b), защото кои са те решава
   * мапингът към НАП, а не колона на сметката.
   */
  static final String PARTY_ACCOUNTS = " AND self.account.id IN (:accountIds)";

  /**
   * Началното салдо по (сметка, контрагент). ⚠️ LEFT JOIN: ред без контрагент остава с NULL, вместо
   * да изчезне — така липсата се вижда като находка, а не като тихо по-малко салдо.
   */
  public static final String OPENING_BY_ACCOUNT_AND_PARTNER =
      "SELECT self.account.id, partner.id, SUM(self.debit - self.credit)"
          + " FROM MoveLine self LEFT JOIN self.partner partner WHERE "
          + OPENING_LINES
          + PARTY_ACCOUNTS
          + " GROUP BY self.account.id, partner.id";

  /** Дебитният и кредитният оборот в периода по (сметка, контрагент). */
  public static final String TURNOVER_BY_ACCOUNT_AND_PARTNER =
      "SELECT self.account.id, partner.id, SUM(self.debit), SUM(self.credit)"
          + " FROM MoveLine self LEFT JOIN self.partner partner WHERE "
          + TURNOVER_LINES
          + PARTY_ACCOUNTS
          + " GROUP BY self.account.id, partner.id";

  /** Има ли осчетоводени статии преди началото на годината — тоест салдо, което да се пренесе. */
  public static final String POSTED_BEFORE_YEAR =
      "SELECT COUNT(self.id) FROM Move self WHERE " + POSTED_MOVES + " AND self.date < :yearFrom";

  /** Има ли откриваща статия в годината. */
  public static final String OPENING_MOVES_IN_YEAR =
      "SELECT COUNT(self.id) FROM Move self WHERE "
          + POSTED_MOVES
          + " AND self.period.year.id = :yearId"
          + " AND self.functionalOriginSelect = :openingOrigin";

  /**
   * Статиите с дата в периода, които НЕ са осчетоводени — чернова, дневник, симулирани (G6a). Не са
   * във файла; екранът ги показва като предупреждение.
   */
  public static final String UNPOSTED_MOVES =
      "SELECT self.id, self.reference, self.statusSelect FROM Move self"
          + " WHERE self.company.id = :companyId"
          + " AND self.date >= :fromDate"
          + " AND self.date <= :toDate"
          + " AND self.statusSelect IN ("
          + MoveRepository.STATUS_NEW
          + ", "
          + MoveRepository.STATUS_DAYBOOK
          + ", "
          + MoveRepository.STATUS_SIMULATED
          + ")"
          + " ORDER BY self.date, self.id";

  /** Статиите на периода, създадени след {@code :createdAfter} — последното генериране (G6a). */
  public static final String MOVES_CREATED_AFTER =
      "SELECT self.id, self.reference, self.statusSelect FROM Move self WHERE "
          + PERIOD_MOVES
          + " AND self.createdOn > :createdAfter"
          + " ORDER BY self.createdOn, self.id";

  private static final Pattern PARAMETER = Pattern.compile(":(\\w+)");

  private final Long companyId;
  private final Long yearId;
  private final LocalDate yearFrom;
  private final LocalDate fromDate;
  private final LocalDate toDate;

  BgSaftMoveScope(
      Long companyId, Long yearId, LocalDate yearFrom, LocalDate fromDate, LocalDate toDate) {
    this.companyId = companyId;
    this.yearId = yearId;
    this.yearFrom = yearFrom;
    this.fromDate = fromDate;
    this.toDate = toDate;
  }

  /**
   * Обхватът на месечен отчет. Отказва, вместо да гадае: без период няма какво да се отчита, а без
   * финансова година на същото дружество началното салдо няма откъде да започне.
   */
  public static BgSaftMoveScope of(SaftReport report) {
    if (report == null) {
      throw new IllegalArgumentException("SAF-T: няма отчет");
    }
    Company company = report.getCompany();
    if (company == null) {
      throw new IllegalArgumentException("SAF-T: отчетът няма дружество");
    }
    if (!SaftReportRepository.REPORT_TYPE_MONTHLY.equals(report.getReportTypeSelect())) {
      throw new IllegalArgumentException(
          "SAF-T: генерира се само месечен файл, а отчетът е „"
              + report.getReportTypeSelect()
              + "“");
    }
    Period period = report.getPeriod();
    if (period == null || period.getFromDate() == null || period.getToDate() == null) {
      throw new IllegalArgumentException("SAF-T: отчетът няма период с начало и край");
    }
    Year year = period.getYear();
    if (year == null || year.getFromDate() == null) {
      throw new IllegalArgumentException(
          "SAF-T: периодът не принадлежи на година — началното салдо няма откъде да започне");
    }
    if (year.getTypeSelect() != YearRepository.TYPE_FISCAL) {
      throw new IllegalArgumentException(
          "SAF-T: периодът е от година, която не е финансова (вид " + year.getTypeSelect() + ")");
    }
    if (year.getCompany() != null && !same(year.getCompany(), company)) {
      throw new IllegalArgumentException(
          "SAF-T: периодът е от финансовата година на друго дружество");
    }
    return new BgSaftMoveScope(
        company.getId(),
        year.getId(),
        year.getFromDate(),
        period.getFromDate(),
        period.getToDate());
  }

  /**
   * Именуваните параметри на заявката със стойностите им — САМО тези, които тя ползва. Hibernate
   * отказва параметър, който заявката не носи, тоест едно общо „свържи всичко“ не става.
   */
  public Map<String, Object> parameters(String jpql) {
    return parameters(jpql, Collections.emptyMap());
  }

  /**
   * Същото плюс параметрите на самата заявка, които обхватът не познава — например {@code
   * :accountIds} на контрагентите. Име, което обхватът вече носи, се отказва: така заявката не може
   * да подмени дружеството или периода.
   */
  public Map<String, Object> parameters(String jpql, Map<String, Object> extra) {
    Map<String, Object> all = new LinkedHashMap<>();
    all.put("companyId", companyId);
    all.put("posted", POSTED);
    all.put("openingOrigin", OPENING_ORIGIN);
    all.put("yearId", yearId);
    all.put("yearFrom", yearFrom);
    all.put("fromDate", fromDate);
    all.put("toDate", toDate);
    for (Map.Entry<String, Object> e : extra.entrySet()) {
      if (all.containsKey(e.getKey())) {
        throw new IllegalStateException(
            "SAF-T: параметърът :" + e.getKey() + " е на обхвата и не се подменя");
      }
      all.put(e.getKey(), e.getValue());
    }
    Map<String, Object> used = new LinkedHashMap<>();
    Matcher m = PARAMETER.matcher(jpql);
    while (m.find()) {
      String name = m.group(1);
      if (!all.containsKey(name)) {
        throw new IllegalStateException("SAF-T: заявката иска непознат параметър :" + name);
      }
      used.put(name, all.get(name));
    }
    return Collections.unmodifiableMap(used);
  }

  public LocalDate getFromDate() {
    return fromDate;
  }

  public LocalDate getToDate() {
    return toDate;
  }

  private static boolean same(Company a, Company b) {
    return a == b || (a.getId() != null && a.getId().equals(b.getId()));
  }
}
