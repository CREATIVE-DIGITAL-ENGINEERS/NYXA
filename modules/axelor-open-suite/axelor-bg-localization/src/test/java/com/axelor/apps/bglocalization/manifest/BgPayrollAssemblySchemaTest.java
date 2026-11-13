package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * БАЗОВАТА ПОЛОВИНА на сглобителя: заявките, които той пуска, отговарят на ДЕЙСТВИТЕЛНАТА схема.
 *
 * <p><b>Защо отделен тест.</b> Файловата половина ({@code BgPayrollAssemblyTest}, {@code
 * BgPayrollInputBuilderTest}) тече без база и отговаря на въпроса „вярно ли е записано“ — шевовете
 * към базата там са подменени, тоест JPQL-ът НЕ се изпълнява нито веднъж. Точно това е положението
 * и на заявките в {@code BgContributionPreviewController}, за които javadoc-ът им казва, че формата
 * е „първото им изпълнение“. Заявка без изпълнение е низ: тя не пада при преименувана колона, а при
 * първото натискане на бутона в производство.
 *
 * <p><b>Трите състояния на пропускането.</b> Няма връзка → пропуска се с текста на грешката.
 * Таблица без нито един ред там, където редове идват от App Install → пропуска се с причина, защото
 * това е състояние на средата, не на кода. Редове ИМА, но не тези → ПАДА: това е импортиран, но
 * грешен или остарял seed, и той трябва да се вижда. Схемата е трето: липсваща колона НИКОГА не е
 * състояние на средата — тя е разминаване между кода и модела и винаги пада.
 */
class BgPayrollAssemblySchemaTest {

  /**
   * ⚠️ КОЛОНИТЕ, КОИТО СГЛОБИТЕЛЯТ ЧЕТЕ, по таблици. Носачът е име на колона, не изречение:
   * преименувана колона проваля търсенето, вместо да остави разминати имена в проза.
   *
   * <p>Имената са ФИЗИЧЕСКИ (както Hibernate ги изнася), защото това е въпросът — какво има в
   * базата, а не какво пише в домейна. {@code date_val} е точно такъв случай: полето се казва
   * {@code date}, а колоната не може, защото {@code date} е запазена дума.
   */
  private static final Map<String, List<String>> READS = new LinkedHashMap<>();

  static {
    READS.put(
        "hr_timesheet_line", Arrays.asList("employee", "date_val", "hours_duration", "timesheet"));
    READS.put("hr_timesheet", Arrays.asList("status_select"));
    READS.put(
        "bglocalization_bg_compensation",
        Arrays.asList("employment_contract", "status_select", "bg_payout_period"));
    READS.put("base_events_planning_line", Arrays.asList("year", "date_val"));
  }

  /** Годината, за която сглобителят ще смята днес. Без нея знаменателят по НЕВД го няма. */
  private static final int YEAR_IN_USE = 2026;

  private static Connection open() {
    String url = BgTestDb.url();
    String user = BgTestDb.user();
    String pass = BgTestDb.password();
    try {
      Class.forName("org.postgresql.Driver");
      return DriverManager.getConnection(url, user, pass);
    } catch (Exception e) {
      BgSkip.because(
          "няма достъпна база ("
              + url
              + "): "
              + e.getMessage()
              + " — твърдението „заявките на сглобителя отговарят на схемата“ НЕ е проверено."
              + " Файловата половина (BgPayrollAssemblyTest и BgPayrollInputBuilderTest) тече и без"
              + " база.");
      return null;
    }
  }

  private static String one(Connection c, String sql) throws SQLException {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static List<String> query(Connection c, String sql) throws SQLException {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  @Test
  @DisplayName("БАЗА · ⚠️ Всяка колона, която сглобителят чете, съществува в действителната схема")
  void everyColumnTheAssemblerReadsExists() throws Exception {
    try (Connection c = open()) {
      // ⚠️ КОТВАТА: information_schema наистина е прочетена. Без нея празен резултат минава като
      // „нищо не липсва“ и тестът е зелен, без да е погледнал схемата.
      String columns =
          one(
              c,
              "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema()");
      if (columns == null || "0".equals(columns)) {
        fail("information_schema не върна нито една колона — това не е база, на която да се вярва");
      }

      List<String> missing = new ArrayList<>();
      for (Map.Entry<String, List<String>> table : READS.entrySet()) {
        List<String> present =
            query(
                c,
                "SELECT lower(column_name) FROM information_schema.columns"
                    + " WHERE table_schema = current_schema() AND lower(table_name) = '"
                    + table.getKey()
                    + "'");
        if (present.isEmpty()) {
          missing.add("таблицата " + table.getKey() + " НЯМА нито една колона (или я няма изобщо)");
          continue;
        }
        for (String column : table.getValue()) {
          if (!present.contains(column)) {
            missing.add(table.getKey() + "." + column + " — намерени: " + present);
          }
        }
      }

      if (!missing.isEmpty()) {
        fail(
            "СГЛОБИТЕЛЯТ ЧЕТЕ КОЛОНА, КОЯТО В БАЗАТА ГО НЯМА:\n  "
                + String.join("\n  ", missing)
                + "\n\n  ⚠️ Това НЕ е състояние на средата и НЕ се пропуска: заявките на"
                + " BgPayrollInputBuilder не се изпълняват в нито един тест без база, тоест"
                + " разминаването с модела би излязло чак при първото натискане на бутона в"
                + " производство.\n"
                + "  Поправка: сверете JPQL-а в BgPayrollInputBuilder.workedTime и"
                + " .compensationDocumentIds с домейните — или обновете списъка READS, ако колоната"
                + " наистина е преименувана.");
      }
    }
  }

  @Test
  @DisplayName("БАЗА · ⚠️ Заявките на сглобителя се ИЗПЪЛНЯВАТ срещу схемата, а не само се четат")
  void theAssemblerQueriesActuallyRun() throws Exception {
    try (Connection c = open()) {
      // Еквивалентът на JPQL-а за отработеното време. LIMIT 0: интересува ни дали заявката е
      // ВАЛИДНА, не какво връща — данните са предмет на другите тестове.
      List<String> statements =
          Arrays.asList(
              "SELECT l.date_val, l.hours_duration FROM hr_timesheet_line l"
                  + " JOIN hr_timesheet t ON t.id = l.timesheet"
                  + " WHERE l.employee = 0 AND l.date_val BETWEEN '2026-03-01' AND '2026-03-31'"
                  + " AND t.status_select = 3 LIMIT 0",
              "SELECT c.id FROM bglocalization_bg_compensation c"
                  + " WHERE c.employment_contract = 0 AND c.status_select = 'CONFIRMED'"
                  + " AND c.bg_payout_period BETWEEN '2026-03-01' AND '2026-03-31'"
                  + " ORDER BY c.id LIMIT 0");
      for (String sql : statements) {
        try (Statement st = c.createStatement()) {
          st.executeQuery(sql).close();
        } catch (SQLException e) {
          fail(
              "ЗАЯВКА НА СГЛОБИТЕЛЯ НЕ СЕ ИЗПЪЛНЯВА срещу действителната схема:\n  "
                  + sql
                  + "\n  "
                  + e.getMessage()
                  + "\n  ⚠️ Това е разминаване между кода и модела, не липсващи данни.");
        }
      }
    }
  }

  @Test
  @DisplayName("БАЗА · ⚠️ Годината, за която сглобителят брои дни, е ПОКРИТА от календара")
  void theYearTheAssemblerCountsForIsCovered() throws Exception {
    try (Connection c = open()) {
      String rows = one(c, "SELECT count(*) FROM base_events_planning_line");
      if ("0".equals(rows)) {
        BgSkip.because(
            "таблицата base_events_planning_line е ПРАЗНА — App Install не е пускан, това твърдение"
                + " не е проверено. Промяна в apps/init-data/ достига до базата само през uninstall →"
                + " restore-all → СВАЛЯНЕ на active и init_data_loaded → App Install (вдигането на"
                + " версията презарежда изгледи, не данни).");
      }

      // ⚠️ Редове ИМА, но не за тази година → ПАДА. Празната таблица е среда, непокритата година е
      // дефект в данните: без нея BgPayrollInputBuilder маха знаменателя и пропорцията по НЕВД
      // просто не се прилага — тихо, защото резултатът изглежда като „пълен месец“.
      String forYear =
          one(c, "SELECT count(*) FROM base_events_planning_line WHERE year = " + YEAR_IN_USE);
      if ("0".equals(forYear)) {
        fail(
            "производственият календар има "
                + rows
                + " реда, но НИТО ЕДИН за "
                + YEAR_IN_USE
                + " г.\n  ⚠️ Тогава BgWorkingDayService.Count не е надеждна, сглобителят маха"
                + " работните дни в периода и пропорцията по НЕВД чл. 1 НЕ се прилага. Резултатът"
                + " изглежда като пълен месец и нищо на екрана не казва, че е незнание.\n"
                + "  Поправка: допълнете apps/init-data/en/bg_eventsPlanningLine.csv и fr/ и минете"
                + " uninstall → App Install.");
      }
    }
  }
}
