package com.axelor.apps.bglocalization.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * КЛИЕНТСКИТЕ ВИДОВЕ ОТПУСК — базовата половина: „наистина ли се ЗАРЕДИХА съпоставени“.
 *
 * <p><b>Защо двете половини не са една.</b> {@code BgClientLeaveReasonImportTest} проверява
 * ФАЙЛОВЕТЕ и тече без Postgres — той доказва, че вносът е верен, но не и че е СТАНАЛ. Точно тази
 * разлика има цена тук: вносителят на Axelor ПРЕМЪЛЧАВА несъществуващо поле ({@code
 * XMLBinder#populate} прави {@code if (property == null) continue}), тоест внос преди WS-5 да е
 * слят създава редовете БЕЗ съпоставяне и БЕЗ нито едно съобщение. Файловата половина ще е зелена,
 * а базата — пълна с несъпоставени видове.
 *
 * <p><b>⚠️ БРОЯТ НЕ СЕ ЗАКОВАВА.</b> Твърденията са за КАЧЕСТВОТО на заредените редове, не за броя
 * им: 28 е числото на ТОЗИ клиент, а тестът пътува с модула. Заковано, то щеше да пада при всеки
 * друг клиент — тоест щеше да мери инсталацията, вместо съпоставянето.
 *
 * <p><b>Пропуска се с ОБЯВЕНА причина</b> при три състояния на средата: няма достъпна база; няма
 * колона {@code bg_absence_type} (WS-5 не е минал); няма нито един клиентски ред (вносът още не е
 * пуснат — виж бележката за Keycloak в отчета на клона).
 */
class BgClientLeaveReasonDbTest {

  private static final String T = "hr_leave_reason";

  /** Префиксът на importId, с който този клон внася — вж. {@code leave_reason.xml}. */
  private static final String PREFIX = "lr\\_%";

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
              + " — твърдението „заредените видове отпуск са съпоставени“ НЕ е проверено."
              + " Файловата половина (BgClientLeaveReasonImportTest) тече и без база.");
      return null;
    }
  }

  private static void requireColumn(Connection c) throws Exception {
    if ("0"
        .equals(
            one(
                c,
                "SELECT count(*) FROM information_schema.columns WHERE table_name = '"
                    + T
                    + "' AND column_name = 'bg_absence_type'"))) {
      BgSkip.because(
          "колоната "
              + T
              + ".bg_absence_type още я НЯМА — WS-5"
              + " (db/migration/2026-08-24-bg-leave-reason-absence-type.sql) не е минавал срещу"
              + " тази база. Няма какво да се сверява: домът на съпоставянето не е построен.");
    }
  }

  /** Пропуска, когато вносът още не е пуснат — празнотата е състояние на средата, не дефект. */
  private static void requireClientRows(Connection c) throws Exception {
    if ("0"
        .equals(one(c, "SELECT count(*) FROM " + T + " WHERE import_id LIKE '" + PREFIX + "'"))) {
      BgSkip.because(
          "нито един клиентски вид отпуск (import_id LIKE 'lr_%') не е зареден в базата — App"
              + " Install на bg-localization още не е пускан срещу нея. Файловете са готови:"
              + " вносът е apps/init-data/bg-localization-leave-reason-config.xml и тече СЛЕД"
              + " каталога, тоест зареждането чака инсталацията, а не поправка.");
    }
  }

  @Test
  @DisplayName("БАЗА · нито един зареден клиентски вид отпуск не е останал БЕЗ съпоставяне")
  void everyLoadedClientReasonIsMapped() throws Exception {
    try (Connection c = open()) {
      requireColumn(c);
      requireClientRows(c);

      List<String> unmapped =
          query(
              c,
              "SELECT id, import_id, name FROM "
                  + T
                  + " WHERE import_id LIKE '"
                  + PREFIX
                  + "' AND bg_absence_type IS NULL ORDER BY id");
      if (!unmapped.isEmpty()) {
        fail(
            "зареден клиентски вид отпуск БЕЗ съпоставяне ("
                + unmapped.size()
                + "):\n  "
                + String.join("\n  ", unmapped)
                + "\n\n  ⚠️ ДВЕ РАЗЛИЧНИ ПРИЧИНИ ДАВАТ ТОЧНО ТАЗИ СЛЕДА, и двете мълчат."
                + "\n  (1) Внос, пуснат ПРЕДИ WS-5: XMLBinder премълчава несъществуващо поле"
                + " (`if (property == null) continue`), тоест редовете са създадени, а привръзката"
                + " — не."
                + "\n  (2) Внос, пуснат ПРЕДИ речника: leave_reason.xml, пуснат от кореновия"
                + " data-init на БООТ, тече преди bg_absence_type, който се създава от App Install"
                + " минути по-късно; ненамерен m2o оставя връзката празна, без съобщение."
                + "\n  Поправка и за двете: пуснете вноса ПАК срещу пълна база — той е"
                + " идемпотентен по importId и ще допълни съпоставянето, вместо да дублира"
                + " редовете. ⚠️ На заварена инсталация „пак“ иска ТРИ действия, не едно:"
                + " installApp излиза на първия си ред при active = true, а unInstallApp сваля"
                + " САМО active и оставя init_data_loaded вдигнат. Тоест: UPDATE studio_app SET"
                + " active = false, init_data_loaded = false WHERE code = 'bg-localization';"
                + " после App Install. Виж началните данни — там е и признакът, че"
                + " импортът НЕ е станал: status 0 за под секунда.");
      }
    }
  }

  @Test
  @DisplayName("БАЗА · всяко съпоставяне сочи ДЕЙСТВИТЕЛЕН ред от речника")
  void everyMappingPointsAtARealDictionaryRow() throws Exception {
    try (Connection c = open()) {
      requireColumn(c);
      requireClientRows(c);

      // Външният ключ вече го пази, но само ако е създаден: миграцията го добавя условно, а
      // ddl=update не го добавя изобщо. Затова се сверява и тук.
      List<String> dangling =
          query(
              c,
              "SELECT r.id, r.import_id FROM "
                  + T
                  + " r LEFT JOIN bglocalization_bg_absence_type t ON t.id = r.bg_absence_type"
                  + " WHERE r.import_id LIKE '"
                  + PREFIX
                  + "' AND r.bg_absence_type IS NOT NULL"
                  + " AND (t.id IS NULL OR t.code IS NULL OR btrim(t.code) = '')"
                  + " ORDER BY r.id");
      assertEquals(
          new ArrayList<String>(),
          dangling,
          "съпоставяне към несъществуващ или безкод ред от речника — изглежда попълнено и не води"
              + " до никъде");
    }
  }

  @Test
  @DisplayName("⚠️ БАЗА · съпоставянията са ПОТВЪРДЕНИ — нито едно не чака решение на човек")
  void everyMappingIsConfirmedNotDefaulted() throws Exception {
    try (Connection c = open()) {
      requireColumn(c);
      requireClientRows(c);

      // ⚠️ ::text НАРОЧНО — JDBC връща boolean като „t“/„f“, а тук стойността се ЧЕТЕ.
      List<String> awaiting =
          query(
              c,
              "SELECT id, import_id, coalesce(bg_absence_type_defaulted, false)::text FROM "
                  + T
                  + " WHERE import_id LIKE '"
                  + PREFIX
                  + "' AND coalesce(bg_absence_type_defaulted, false) = true ORDER BY id");
      assertEquals(
          new ArrayList<String>(),
          awaiting,
          "клиентски вид отпуск с вдигнат флаг „подразбирано“ — този списък е сверен срещу КСО и"
              + " потвърден от човек, тоест не бива да чака повторно решение");
    }
  }

  /**
   * Видовете отпуск, които нормата мери в ЧАСОВЕ — същото изброяване като {@code
   * BgClientLeaveReasonImportTest.onlyTheNursingRowDeclaresHours}: чл. 166 (кърмене) и чл. 157 ал.
   * 1 т. 6 / ал. 2. Сглобителят ги превръща в дни през договорените часове на ден.
   */
  private static final String HOUR_REASONS = "'lr_paid_157_art177_h', 'lr_nursing_166'";

  @Test
  @DisplayName("⚠️ БАЗА · единицата е ДНИ, освен изброените по норма видове в ЧАСОВЕ")
  void everyLoadedReasonIsMeasuredInDays() throws Exception {
    try (Connection c = open()) {
      requireColumn(c);
      requireClientRows(c);

      List<String> notDays =
          query(
              c,
              "SELECT id, import_id, coalesce(unit_select::text, 'NULL') FROM "
                  + T
                  + " WHERE import_id LIKE '"
                  + PREFIX
                  + "' AND import_id NOT IN ("
                  + HOUR_REASONS
                  + ") AND coalesce(unit_select, 0) <> 1 ORDER BY id");
      assertEquals(
          new ArrayList<String>(),
          notDays,
          "клиентски вид отпуск, който НЕ е в дни (hr.leave.reason.unit.select: 1 = дни) и НЕ е"
              + " сред изброените по норма видове в часове. Нов вид в часове се добавя и тук, и в"
              + " BgClientLeaveReasonImportTest — иначе е недоглеждане");
    }
  }

  @Test
  @DisplayName("⚠️ БАЗА · изброените видове в ЧАСОВЕ наистина са в часове")
  void theHourReasonsAreMeasuredInHours() throws Exception {
    try (Connection c = open()) {
      requireColumn(c);
      requireClientRows(c);

      List<String> wrong =
          query(
              c,
              "SELECT id, import_id, coalesce(unit_select::text, 'NULL') FROM "
                  + T
                  + " WHERE import_id IN ("
                  + HOUR_REASONS
                  + ") AND coalesce(unit_select, 0) <> 2 ORDER BY id");
      assertEquals(
          new ArrayList<String>(),
          wrong,
          "вид отпуск, който нормата мери в часове (чл. 166, чл. 157 ал. 1 т. 6 / ал. 2), е"
              + " зареден в друга единица — операторът би въвеждал части от деня");
    }
  }

  // ═══ ЧЕТЕНЕТО ═════════════════════════════════════════════════════════════

  private static String one(Connection c, String sql) throws Exception {
    List<String> r = query(c, sql);
    return r.isEmpty() ? null : r.get(0);
  }

  private static List<String> query(Connection c, String sql) throws Exception {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      int n = rs.getMetaData().getColumnCount();
      while (rs.next()) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= n; i++) {
          if (i > 1) {
            sb.append(" | ");
          }
          sb.append(rs.getString(i));
        }
        out.add(sb.toString());
      }
    }
    return out;
  }
}
