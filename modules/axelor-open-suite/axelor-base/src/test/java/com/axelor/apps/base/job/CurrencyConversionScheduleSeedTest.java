package com.axelor.apps.base.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.quartz.CronExpression;

/**
 * КУРСОВЕТЕ НА ЕЦБ СЕ ТЕГЛЯТ САМИ В НОВА БАЗА.
 *
 * <p>{@code data-init/input/meta_schedule.csv} носи задачата {@link CurrencyConversionJob} АКТИВНА,
 * в работни дни (понеделник–петък) в 17:30 — след като ЕЦБ публикува (около 16:00 ч.
 * централноевропейско време). AOS я носеше неактивна и на всеки 5 минути. Файлът се чете само при
 * създаване на база (data-init, без {@code search} — само вмъкване), тоест заварените бази не се
 * пипат.
 *
 * <p>В почивен ден на ЕЦБ (TARGET) в работен ден API-то връща 200 с празно тяло и услугата пита за
 * предходния ден — записва се последният публикуван курс, който вече е в сила, тоест нищо не се
 * мени. Справката за курс към дата без публикация е в {@code CurrencyServiceLastPublishedRateTest}.
 */
class CurrencyConversionScheduleSeedTest {

  private static final String SEED = "data-init/input/meta_schedule.csv";

  private static final String CONFIG = "data-init/input-config.xml";

  private static final String JOB = CurrencyConversionJob.class.getName();

  /** Понеделник, за да се види цяла седмица от понеделник до неделя, два пъти. */
  private static final LocalDate MONDAY = LocalDate.of(2026, 11, 9);

  @Test
  void задачата_е_активна_в_работни_дни_след_публикуването() throws Exception {
    String[] row = currencyJobRow();
    assertEquals("true", row[0], "задачата за курсовете не е активна в " + SEED);
    String cron = row[3];
    assertTrue(CronExpression.isValidExpression(cron), "невалиден cron: " + cron);

    // Часовата зона на JVM не е зададена в репото: проверката минава и за София, и за UTC.
    for (String zone : new String[] {"Europe/Sofia", "UTC"}) {
      ZoneId zoneId = ZoneId.of(zone);
      CronExpression expression = new CronExpression(cron);
      expression.setTimeZone(TimeZone.getTimeZone(zoneId));

      List<LocalDateTime> fires = new ArrayList<>();
      Date from = Date.from(MONDAY.atStartOfDay(zoneId).toInstant());
      Date until = Date.from(MONDAY.plusDays(14).atStartOfDay(zoneId).toInstant());
      Date next = expression.getNextValidTimeAfter(new Date(from.getTime() - 1));
      while (next != null && next.before(until)) {
        fires.add(LocalDateTime.ofInstant(next.toInstant(), zoneId));
        next = expression.getNextValidTimeAfter(next);
      }

      assertEquals(10, fires.size(), zone + ": очаквах по веднъж в 10 работни дни, а е " + fires);
      for (LocalDateTime fire : fires) {
        DayOfWeek day = fire.getDayOfWeek();
        assertTrue(
            day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY,
            zone + ": пуска се в почивен ден " + fire);
        // ЕЦБ публикува около 16:00 CET = 17:00 в София = 15:00 UTC.
        LocalDateTime published = fire.toLocalDate().atTime("UTC".equals(zone) ? 15 : 17, 0);
        assertTrue(
            !fire.isBefore(published),
            zone + ": " + fire + " е преди публикуването на ЕЦБ (" + published + ")");
      }
    }
  }

  @Test
  void файлът_се_зарежда_от_data_init() throws Exception {
    String config = read(CONFIG);
    assertTrue(
        config.contains("file=\"meta_schedule.csv\"")
            && config.contains("type=\"com.axelor.meta.db.MetaSchedule\""),
        CONFIG + " вече не зарежда meta_schedule.csv");
  }

  private static String[] currencyJobRow() throws Exception {
    List<String> lines = new ArrayList<>(List.of(read(SEED).split("\\r?\\n")));
    assertEquals("\"active\";\"name\";\"job\";\"cron\"", lines.remove(0), "заглавието на " + SEED);
    List<String[]> rows =
        lines.stream()
            .filter(l -> !l.isBlank())
            .map(l -> l.replace("\"", "").split(";", -1))
            .filter(r -> r.length > 2 && JOB.equals(r[2]))
            .collect(Collectors.toList());
    assertEquals(1, rows.size(), "очаквах точно един ред за " + JOB + " в " + SEED);
    return rows.get(0);
  }

  /**
   * От папката на модула, не от classpath-а: axelor-core носи свой {@code
   * data-init/input-config.xml} и редът на classpath-а щеше да реши кой файл е проверен.
   */
  private static String read(String resource) throws Exception {
    Path file = Paths.get("src/main/resources").resolve(resource);
    assertTrue(Files.isRegularFile(file), "не намирам " + file.toAbsolutePath());
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }
}
