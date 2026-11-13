package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Твърди, че FK-ът към речника на фондовете и низът {@code fundCode} НИКОГА не се разминават.
 *
 * <p><b>Защо изобщо съществува.</b> В този клон един и същ факт се пази два пъти: низът, който
 * изчислението чете днес ({@code BgContributionResolver} сравнява точно "PENSION", "UPF", "PPF",
 * "TZPB"), и FK-ът към {@code BgContributionFund}, който носи получателя, закръглянето и полетата в
 * Д1. Два записа на едно и също се разминават — това е въпрос на време, не на дисциплина. Низът се
 * маха чак когато FK-ът се докаже, и този тест е доказателството.
 *
 * <p><b>Защо срещу базата, а не срещу CSV-то.</b> В seed-а разминаване е невъзможно по устройство:
 * и двете полета се пълнят от една и съща колона. Опасните редове са другите — въведените на ръка в
 * живата база и заварените, попълнени от миграцията. Проверка върху seed-а щеше да ги пропусне
 * всичките.
 *
 * <p>Тестът се ПРОПУСКА, когато няма достъпна база — за да не оцветява в червено билд без среда.
 * Базата е тази на ПРИЛОЖЕНИЕТО и идва само от {@code db.default.url} — виж {@link
 * com.axelor.apps.bglocalization.testsupport.BgTestDb}.
 */
class BgContributionFundConsistencyTest {

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
              + " — съвпадението на FK-а към фонда с низа fundCode НЕ е проверено.");
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

  @Test
  @DisplayName("FK-ът към фонда и низът fundCode казват едно и също за всеки ред")
  void theForeignKeyNeverDisagreesWithTheString() throws Exception {
    try (Connection c = open()) {

      // 0 · ⚠️ ТЕСТ, КОЙТО МИНАВА ПРИ ПРАЗНА ТАБЛИЦА, НЕ Е ТЕСТ. Трите проверки по-долу са „няма
      // ред, за който да е вярно обратното“ — а това е вярно и когато редове изобщо няма. Затова
      // първо се доказва, че има какво да се проверява: празна база трябва да се обади тук, вместо
      // да мине като зелено.
      String ставки =
          query(c, "SELECT count(*) FROM bglocalization_bg_insurance_contribution").get(0);
      String фондове = query(c, "SELECT count(*) FROM bglocalization_bg_contribution_fund").get(0);
      if ("0".equals(ставки) || "0".equals(фондове)) {
        fail(
            "няма какво да се проверява: ставки = "
                + ставки
                + ", фондове = "
                + фондове
                + " (минал ли е App Install?)");
      }

      // 1 · нито един ред без FK. Колоната е NOT NULL след миграцията, но тестът не разчита на
      // това: среда, на която вторият файл още не е пуснат, трябва да се обади тук, а не при
      // първото
      // изчисление.
      List<String> без =
          query(
              c,
              "SELECT import_id, fund_code FROM bglocalization_bg_insurance_contribution "
                  + "WHERE bg_contribution_fund IS NULL ORDER BY import_id");
      if (!без.isEmpty()) {
        fail(
            "редове със ставки без FK към фонд: "
                + без.size()
                + "\n  "
                + String.join("\n  ", без)
                + "\n  (пуснат ли е 2026-08-13-bg-insurance-contribution-fund-fk-2-zaklyuchvane.sql?)");
      }

      // 2 · и там, където FK има, той сочи СЪЩИЯ фонд, който казва низът.
      List<String> разминати =
          query(
              c,
              "SELECT c.import_id, c.fund_code, f.code FROM bglocalization_bg_insurance_contribution c "
                  + "JOIN bglocalization_bg_contribution_fund f ON f.id = c.bg_contribution_fund "
                  + "WHERE f.code <> c.fund_code ORDER BY c.import_id");
      if (!разминати.isEmpty()) {
        fail(
            "FK и низ се разминават за "
                + разминати.size()
                + " реда (importId | низ | FK):\n  "
                + String.join("\n  ", разминати));
      }

      // 3 · и обратното: всеки фонд, който данните ползват, го има в речника. Липсващ ред тук
      // значи,
      // че редът със ставката е останал без FK — но с ясно съобщение кой фонд липсва.
      List<String> непознати =
          query(
              c,
              "SELECT DISTINCT c.fund_code FROM bglocalization_bg_insurance_contribution c "
                  + "WHERE NOT EXISTS (SELECT 1 FROM bglocalization_bg_contribution_fund f "
                  + "WHERE f.code = c.fund_code) ORDER BY 1");
      if (!непознати.isEmpty()) {
        fail("фондове в ставките, които ги няма в речника: " + String.join(", ", непознати));
      }
    }
  }
}
