package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Сверява ЗАРЕДЕНИЯ производствен календар срещу манифеста му.
 *
 * <p><b>Какво лови.</b> Липсващ ред не чупи нищо: срокът просто получава с един работен ден повече
 * и обезщетението излиза по-голямо. Няма изключение, няма червен ред в лога — има само число, което
 * е с единица встрани. Затова редовете се броят и се изброяват поименно.
 *
 * <p><b>Защо и датите, а не само броят.</b> Тринайсет реда с разместени дати са пак тринайсет реда.
 * Изместените дни са отделен списък по същата причина: те са мястото, където правилото по чл. 154
 * ал. 2 се прилага, тоест мястото, където се греши.
 *
 * <p>Пропуска се, когато няма достъпна база.
 */
class BgHolidayManifestTest {

  private static final String MANIFEST = "/apps/init-data/manifest/holidays.yml";

  @SuppressWarnings("unchecked")
  private static Map<String, Object> expected() throws Exception {
    try (InputStream in = BgHolidayManifestTest.class.getResourceAsStream(MANIFEST)) {
      Assumptions.assumeTrue(in != null, "манифестът " + MANIFEST + " не е в classpath-а");
      Map<String, Object> m = new ObjectMapper(new YAMLFactory()).readValue(in, Map.class);
      return (Map<String, Object>) m.get("очаквано");
    }
  }

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
              + " — производственият календар срещу манифеста НЕ е проверено.");
      return null;
    }
  }

  private static List<String> query(Connection c, String sql) throws Exception {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      int n = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
          if (i > 1) sb.append(" | ");
          sb.append(rs.getString(i));
        }
        out.add(sb.toString());
      }
    }
    return out;
  }

  private static void check(String ред, Object очаквано, Object намерено) {
    if (!String.valueOf(очаквано).equals(String.valueOf(намерено))) {
      fail(
          "манифест „"
              + ред
              + "“ не е изпълнен.\n  очаква се : "
              + очаквано
              + "\n  намерено  : "
              + намерено);
    }
  }

  @Test
  @DisplayName("Производственият календар в базата отговаря на манифеста")
  @SuppressWarnings("unchecked")
  void theLoadedCalendarMatchesTheManifest() throws Exception {
    Map<String, Object> exp = expected();

    try (Connection c = open()) {

      // ⚠️ Празната таблица минава всяка проверка от вида „няма ред, за който да е вярно
      // обратното“.
      String total = query(c, "SELECT count(*) FROM base_events_planning_line").get(0);
      if ("0".equals(total)) {
        fail(
            "производственият календар е ПРАЗЕН — минал ли е App Install след uninstall?"
                + " Без него всеки срок се брои само по понеделник–петък и обезщетенията излизат"
                + " завишени.");
      }

      for (Map.Entry<String, Object> entry : exp.entrySet()) {
        if (!entry.getKey().matches("\\d{4}")) {
          continue; // най_късна_покрита_година и подобните не са година с редове
        }
        String year = entry.getKey();
        Map<String, Object> yearExp = (Map<String, Object>) entry.getValue();

        check(
            year + " · брой редове",
            yearExp.get("редове"),
            query(c, "SELECT count(*) FROM base_events_planning_line WHERE year = " + year).get(0));

        List<Map<String, Object>> dates = (List<Map<String, Object>>) yearExp.get("дати");
        List<String> wanted = new ArrayList<>();
        for (Map<String, Object> d : dates) {
          wanted.add(String.valueOf(d.get("дата")));
        }
        List<String> found =
            query(
                c,
                "SELECT date_val::text FROM base_events_planning_line WHERE year = "
                    + year
                    + " ORDER BY date_val");
        check(year + " · датите", String.join(", ", wanted), String.join(", ", found));

        // Изместените се разпознават по описанието — то е мястото, където правилото по ал. 2 се
        // вижда от човек. Броят им се сверява отделно от общия брой.
        List<Map<String, Object>> moved = (List<Map<String, Object>>) yearExp.get("изместени");
        List<String> movedWanted = new ArrayList<>();
        for (Map<String, Object> m : moved) {
          movedWanted.add(String.valueOf(m.get("на")));
        }
        List<String> movedFound =
            query(
                c,
                "SELECT date_val::text FROM base_events_planning_line WHERE year = "
                    + year
                    + " AND description LIKE '%изместен%' ORDER BY date_val");
        check(
            year + " · изместени дни",
            String.join(", ", movedWanted),
            String.join(", ", movedFound));
      }

      // ⚠️ Ключът за покритието е year, а датата е това, което се брои. Ако двете се разминат,
      // покритието ще твърди едно, а аритметиката ще прави друго — и никой няма да се обади.
      List<String> mismatched =
          query(
              c,
              "SELECT import_id, year, date_val::text FROM base_events_planning_line"
                  + " WHERE year <> EXTRACT(YEAR FROM date_val) ORDER BY date_val");
      if (!mismatched.isEmpty()) {
        fail(
            "редове, чието поле year не съвпада с годината на датата ("
                + mismatched.size()
                + "):\n  "
                + String.join("\n  ", mismatched));
      }

      Map<String, String> byYear = new LinkedHashMap<>();
      for (String row :
          query(c, "SELECT year, count(*) FROM base_events_planning_line GROUP BY 1 ORDER BY 1")) {
        String[] p = row.split(" \\| ");
        byYear.put(p[0], p[1]);
      }
      check("покрити години", "2026, 2027", String.join(", ", byYear.keySet()));
    }
  }

  @Test
  @DisplayName("Манифестът е за този календар")
  void theManifestIsTheRightOne() throws Exception {
    try (InputStream in = BgHolidayManifestTest.class.getResourceAsStream(MANIFEST)) {
      Assumptions.assumeTrue(in != null);
      @SuppressWarnings("unchecked")
      Map<String, Object> m = new ObjectMapper(new YAMLFactory()).readValue(in, Map.class);
      assertEquals("Официални празници (България)", m.get("издание"), "друг манифест");
    }
  }
}
