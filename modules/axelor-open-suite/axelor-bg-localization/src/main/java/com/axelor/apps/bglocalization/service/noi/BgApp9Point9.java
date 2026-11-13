package com.axelor.apps.bglocalization.service.noi;

import com.axelor.apps.bglocalization.db.BgSickLeavePeriodLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Т. 9 — РЕДОВЕТЕ НА Ф0, СВЕДЕНИ ДО РЕДА, КОЙТО СХЕМАТА ИСКА. Чиста функция, без база.
 *
 * <p><b>⚠️ СХЕМАТА НЯМА РАЗБИВКА ПО ПЛАТЕЦ, И ЗАДАНИЕТО ТУК СЕ РАЗМИНАВА С ПЪРВОИЗТОЧНИКА.</b>
 * Заданието на Ф2 иска „агрегация … в редовете-платец, които XSD-то очаква (осигурител / НОИ
 * разбивка)“. Такива редове в схемата НЯМА: {@code BSickLeave} носи ЕДНО число {@code Days} — „брой
 * работни дни в месеца с право на парично обезщетение И възнаграждение“ — тоест сбора на двете. Ф0
 * го е записал изрично в домейна си: „Официалният ред НЕ дели по платец; нашият е едно ниво по-фин
 * и се агрегира до неговия със СБОР“. Оттам тук се сумира, а не се пренася.
 *
 * <p><b>⚠️ И СБОРЪТ НЕ Е УДОБСТВО, А ИЗИСКВАНЕ.</b> Контролът към т. 9 допуска ДВА комплекта данни
 * за един месец САМО „в случаите на попълнена дата на прекратяване в т. 7“. Пренесени едно към
 * едно, двата реда на Ф0 (осигурител + НОИ) щяха да дадат два комплекта за всеки месец с първи два
 * дни по чл. 40 ал. 5 — тоест почти за всеки болничен, и файлът щеше да пада на контрол.
 *
 * <p><b>⚠️ ЧАСОВЕТЕ ОСТАВАТ ПРАЗНИ ВИНАГИ, И ТОВА Е ИЗМЕРЕНО, НЕ ПРЕДПОЛОЖЕНО.</b> Указанието към
 * т. 9 иска часове САМО при СИРВ и по ГРАФИКА на лицето. Ф0 пише {@code workHours} точно в ОБРАТНИЯ
 * случай — {@code BgSickLeaveService#workHoursFor} връща стойност само при {@code
 * WORK_TIME_MODE_DAILY} и {@code null} иначе. Тоест наличните часове са за режима, в който
 * образецът ги забранява, а за режима, в който ги иска, ги няма. Емитерът подава ДНИ (контролът
 * изрично ги допуска, когато часове не са попълнени) и ОБЯВЯВА липсата чрез {@link
 * Month#hoursWantedButMissing} — вместо да умножи дни по норма и да произведе правдоподобно грешно
 * число точно за случая, заради който колоната съществува.
 *
 * <p><b>⚠️ ДВЕТЕ ПРАВИЛА НА ОБРАЗЕЦА, КОИТО Ф0 НАРОЧНО НЕ ПРИЛОЖИ, СЕ ПРИЛАГАТ ТУК.</b> Ф0 записа:
 * „осемте часа са правило на ОБРАЗЕЦА при празно поле, а записани като факт, стават неразличими от
 * действително уговорени осем“ — и остави закръглянето и заместващите 8 часа за формата. Формата е
 * този клас. И двете оставят следа ({@link Month#legalHoursSubstituted}, {@link
 * Month#legalHoursRounded}), тоест приложеното правило е ВИДИМО в отчета, а не мълчаливо.
 */
public final class BgApp9Point9 {

  /** Заместващата стойност на указанието за лица без работно време, уговорено в часове. */
  public static final int SUBSTITUTE_LEGAL_HOURS = 8;

  /** Горната граница на {@code LegalWorkHours} по схемата. */
  public static final int MAX_LEGAL_HOURS = 8;

  /** Кодът за вид осигурен, който контролът освобождава от границата 1–8 часа. */
  public static final String INSURED_TYPE_WITHOUT_HOUR_LIMIT = "99";

  private BgApp9Point9() {}

  /** ЕДИН комплект данни за ЕДИН календарен месец — точно каквото {@code BSickLeave} носи. */
  public static final class Month {

    /** {@code SickNumber} — поредният номер на месеца в периода, от 1. */
    public final int sickNumber;

    /** {@code Year}. */
    public final int year;

    /** {@code Month} (1–12). */
    public final int month;

    /** {@code FromDay} — ПЪРВИЯТ КАЛЕНДАРЕН ден на листа в месеца. */
    public final int fromDay;

    /** {@code ToDay} — последният календарен ден на листа в месеца. */
    public final int toDay;

    /** {@code Days} — СБОРЪТ на работните дни от всички редове на Ф0 за месеца. */
    public final int days;

    /**
     * {@code PersonalLegalWorkHours} — 1..8, или {@code null}, когато схемата приема празно.
     *
     * <p>Празно е допустимо в два случая: месец изцяло след прекратяване, и вид осигурен 99 с норма
     * над 8 часа (контролът го освобождава, а типът на схемата не може да я носи).
     */
    public final Integer legalWorkHours;

    /** Дневната норма по договора, както Ф0 я е записал — за отчета и за свидетелите. */
    public final BigDecimal dailyHours;

    /** ⚠️ Стойността е ЗАМЕСТЕНА с 8 по правилото на указанието, не е прочетена от договора. */
    public final boolean legalHoursSubstituted;

    /** ⚠️ Стойността е ЗАКРЪГЛЕНА по правилото „остатък под 30 минути към пълния час“. */
    public final boolean legalHoursRounded;

    /** ⚠️ Указанието иска ЧАСОВЕ за този месец (СИРВ), а Ф0 няма график и не ги носи. */
    public final boolean hoursWantedButMissing;

    /** Колко реда на Ф0 са слети в този комплект — 1 или 2 при нормален болничен. */
    public final int sourceLineCount;

    Month(
        int sickNumber,
        int year,
        int month,
        int fromDay,
        int toDay,
        int days,
        Integer legalWorkHours,
        BigDecimal dailyHours,
        boolean legalHoursSubstituted,
        boolean legalHoursRounded,
        boolean hoursWantedButMissing,
        int sourceLineCount) {
      this.sickNumber = sickNumber;
      this.year = year;
      this.month = month;
      this.fromDay = fromDay;
      this.toDay = toDay;
      this.days = days;
      this.legalWorkHours = legalWorkHours;
      this.dailyHours = dailyHours;
      this.legalHoursSubstituted = legalHoursSubstituted;
      this.legalHoursRounded = legalHoursRounded;
      this.hoursWantedButMissing = hoursWantedButMissing;
      this.sourceLineCount = sourceLineCount;
    }

    /** Първият ден на комплекта като дата — за кръстосаните проверки на валидатора. */
    public LocalDate fromDate() {
      return LocalDate.of(year, month, fromDay);
    }

    /** Последният ден на комплекта като дата. */
    public LocalDate toDate() {
      return LocalDate.of(year, month, toDay);
    }

    @Override
    public String toString() {
      return String.format(
          "%02d.%d · %d–%d · %d дни · норма %s",
          month, year, fromDay, toDay, days, legalWorkHours == null ? "празна" : legalWorkHours);
    }
  }

  /**
   * СБОРЪТ по месеци, в хронологичен ред и с пореден номер от 1.
   *
   * <p>⚠️ Редовете идват от ЕДИН лист и се групират само по (година, месец). Групиране по месец
   * ПРЕЗ листове би слепило два болнични в един комплект — точно агрегатът, който Ф0 съществува, за
   * да разгради. Затова подателят подава редовете на СВОЯ лист и никой не проверява дали са от
   * един: емитерът чете {@code declaration.getSickLeave().getBgPeriodLineList()}, тоест въпросът не
   * може да възникне.
   *
   * @param lines редовете на Ф0 за ЕДИН лист; {@code null} и празен дават празен резултат
   * @param terminatedFrom т. 7, ако е попълнена — месеците изцяло след нея нямат норма
   * @param insuredTypeCode т. 4 — само за освобождаването на код 99 от границата 1–8
   */
  public static List<Month> aggregate(
      List<BgSickLeavePeriodLine> lines, LocalDate terminatedFrom, String insuredTypeCode) {

    if (lines == null || lines.isEmpty()) {
      return Collections.emptyList();
    }

    // LinkedHashMap + сортиране накрая: редът на входа не е гарантиран (o2m без order-by), а
    // поредният номер на месеца ТРЯБВА да е хронологичен — контролът иска „в хронология“.
    Map<Integer, Bucket> buckets = new LinkedHashMap<>();
    for (BgSickLeavePeriodLine line : lines) {
      if (line == null || line.getFromDay() == null) {
        continue;
      }
      LocalDate from = line.getFromDay();
      int key = from.getYear() * 100 + from.getMonthValue();
      Bucket bucket = buckets.get(key);
      if (bucket == null) {
        bucket = new Bucket(from.getYear(), from.getMonthValue());
        buckets.put(key, bucket);
      }
      bucket.add(line);
    }

    List<Bucket> ordered = new ArrayList<>(buckets.values());
    Collections.sort(ordered, Comparator.comparingInt(b -> b.year * 100 + b.month));

    List<Month> out = new ArrayList<>(ordered.size());
    int seq = 1;
    for (Bucket bucket : ordered) {
      out.add(bucket.toMonth(seq++, terminatedFrom, insuredTypeCode));
    }
    return out;
  }

  /**
   * Дневната норма, закръглена по указанието: „при остатък под 30 минути КЪМ ПЪЛНИЯ ЧАС“.
   *
   * <p>Тоест 7 ч. 29 мин. дава 7, а 7 ч. 30 мин. дава 8 — {@code HALF_UP} върху цели часове.
   */
  public static int roundLegalHours(BigDecimal dailyHours) {
    return dailyHours.setScale(0, RoundingMode.HALF_UP).intValue();
  }

  /** Събирачът на редовете за един месец. */
  private static final class Bucket {
    private final int year;
    private final int month;
    private int days;
    private LocalDate from;
    private LocalDate to;
    private BigDecimal dailyHours;
    private boolean anyHoursMissing;
    private int lineCount;

    Bucket(int year, int month) {
      this.year = year;
      this.month = month;
    }

    void add(BgSickLeavePeriodLine line) {
      lineCount++;
      // ⚠️ getWorkDays() е Integer с генериран getter — при NULL колона той връща 0, не null
      // (BgDefaultedGetterTest). Тоест null проверка тук би била мъртъв код; нулата се сумира и
      // това е верният отговор: ред с нула дни не мести числото.
      days += line.getWorkDays();
      if (from == null || line.getFromDay().isBefore(from)) {
        from = line.getFromDay();
      }
      if (line.getToDay() != null && (to == null || line.getToDay().isAfter(to))) {
        to = line.getToDay();
      }
      // ⚠️ БЕЗ ПРОВЕРКА ЗА null, И ТОВА НЕ Е ПРОПУСК. Генерираният getter за BigDecimal връща ZERO
      // при NULL колона (BgDefaultedGetterTest брои 404 такива getter-а) — тоест сравнение на
      // нормата с null е ВИНАГИ вярно и е мъртъв код. Празната норма пристига като нула и се
      // разпознава по signum().
      if (dailyHours == null && line.getDailyHours().signum() > 0) {
        dailyHours = line.getDailyHours();
      }
      // ⚠️ СЪЩИЯТ КАПАН, И ТУК ТОЙ ЩЕШЕ ДА ОБЪРНЕ ОТГОВОРА. Ф0 пише workHours само при подневно
      // отчитане и оставя колоната празна при СИРВ; празната колона се чете като ZERO, тоест
      // проверка за null не би хванала НИТО ЕДИН СИРВ месец и флагът „графикът липсва“ никога не
      // би се вдигнал. Признакът е нулата.
      if (line.getWorkHours().signum() == 0) {
        anyHoursMissing = true;
      }
    }

    Month toMonth(int seq, LocalDate terminatedFrom, String insuredTypeCode) {
      LocalDate monthStart = LocalDate.of(year, month, 1);
      boolean afterTermination = terminatedFrom != null && !monthStart.isBefore(terminatedFrom);

      Integer legal = null;
      boolean substituted = false;
      boolean rounded = false;

      if (!afterTermination) {
        if (dailyHours == null) {
          // Заместващото правило на указанието — обявено, не мълчаливо.
          legal = SUBSTITUTE_LEGAL_HOURS;
          substituted = true;
        } else {
          int value = roundLegalHours(dailyHours);
          rounded = dailyHours.stripTrailingZeros().scale() > 0;
          if (value > MAX_LEGAL_HOURS && INSURED_TYPE_WITHOUT_HOUR_LIMIT.equals(insuredTypeCode)) {
            // Контролът освобождава код 99 от границата, а типът на схемата не може да носи >8.
            // Празно е единственото, което схемата приема; разминаването се флагва от валидатора.
            legal = null;
          } else {
            legal = Integer.valueOf(value);
          }
        }
      }

      return new Month(
          seq,
          year,
          month,
          from.getDayOfMonth(),
          (to == null ? from : to).getDayOfMonth(),
          days,
          legal,
          dailyHours,
          substituted,
          rounded,
          anyHoursMissing,
          lineCount);
    }
  }
}
