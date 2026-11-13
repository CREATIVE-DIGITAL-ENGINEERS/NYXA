package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * БАЗОВАТА ПОЛОВИНА за заявките на отчетите: „<b>изпълнява ли се</b>“.
 *
 * <p>{@link BgReportQueryColumnTest} пита дали имената са ИЗВЕДЕНИ вярно и тече без база, на всяка
 * машина. Този пита нещо, което файлът не може да знае: приема ли ги Postgres. Слети в един тест,
 * отговорът „няма база“ би маскирал отговора „заявката е невалидна“ — същото разделение като при
 * {@code BgD1SchemaDbTest}.
 *
 * <p><b>⚠️ ЗАЩО ВЪОБЩЕ ИМА ВТОРА ПОЛОВИНА, СЛЕД КАТО ИМЕНАТА СА ИЗВЕДЕНИ.</b> Изведеното име е
 * вярно за ДОМЕЙНА; заявката обаче има и друго съдържание, което никой прочит на XML не проверява —
 * име на ТАБЛИЦА, синтаксис, ключови думи, които трябва да са в кавички ({@code "sequence"}), и
 * съвместимост на типа на параметъра. Единственият честен свидетел за тях е самият Postgres.
 *
 * <p>⚠️ Заявката се изпълнява с <b>{@code LIMIT 0}</b> и в отменена транзакция: целта е разборът и
 * планът, а не редовете. Тоест тестът не зависи от данни и не може да ги промени.
 *
 * <p><b>⚠️ ПАРАМЕТРИТЕ СЕ ВРЪЗВАТ ПО ОБЯВЕНИЯ СИ ТИП, А НЕ ВСИЧКИ КАТО ЧИСЛО.</b> Дотук тестът
 * правеше {@code ps.setLong(1, 0L)} — един параметър, числов, „и в четирите отчета". Това беше
 * вярно за отчетите на ЕДИН запис: техният параметър е {@code id} на носителя. Регистър над цял
 * период не е такъв — разчетно-платежната ведомост получава СПИСЪК от номера като низ и го чете два
 * пъти: веднъж за филтъра, веднъж за подредбата. Вързан като число, вторият параметър дава „No
 * value specified for parameter 2" или разминаване на типовете, тоест ЧЕРВЕНО ЗА ТЕСТА, а не за
 * заявката. Типът се чете от самия образец ({@code <property name="dataType">} на параметъра на
 * набора данни), защото точно него BIRT подава на JDBC.
 */
class BgReportQueryDbTest {

  private static final String REPORTS = "src/main/resources/reports";

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
              + " — заявките на отчетите НЕ са изпълнени. Файловата половина"
              + " (BgReportQueryColumnTest) тече и без база и вече твърди, че имената на колоните са"
              + " изведени вярно.");
      return null;
    }
  }

  @Test
  @DisplayName("СХЕМА · всяка заявка на отчет се РАЗБИРА от Postgres (LIMIT 0, без редове)")
  void everyReportQueryParses() throws Exception {
    try (Connection c = open()) {
      List<String> failed = new ArrayList<>();
      List<String> skipped = new ArrayList<>();
      int ran = 0;

      for (Path design : designs()) {
        String text = new String(Files.readAllBytes(design), StandardCharsets.UTF_8);
        for (String block : dataSets(text)) {
          Matcher m = queries(block);
          if (!m.find()) {
            continue;
          }
          String query = m.group(1).trim();
          String table = BgReportQueryColumnTest.tableOf(query);
          if (table == null || !tableExists(c, table)) {
            skipped.add(design.getFileName() + " → " + table);
            continue;
          }
          ran++;
          try {
            // ⚠️ Стойностите не значат нищо — редове не се искат, иска се РАЗБОРЪТ. Значение има
            // само ТИПЪТ: сгрешен, Postgres отказва самата подготовка на заявката.
            try (PreparedStatement ps =
                c.prepareStatement("select * from (" + query + ") q limit 0")) {
              bind(ps, parameterTypes(block, query));
              try (ResultSet rs = ps.executeQuery()) {
                rs.next();
              }
            }
          } catch (Exception e) {
            failed.add(design.getFileName() + ": " + e.getMessage());
          }
        }
      }

      if (ran == 0) {
        BgSkip.because(
            "нито една заявка не можа да се изпълни — таблиците още ги няма на тази база (не е"
                + " минавала през ddl=update с текущите домейни): "
                + skipped
                + ". ОЧАКВАНО състояние по време на този клон: App Install нарочно НЕ се пуска.");
        return;
      }
      assertTrue(
          failed.isEmpty(),
          "⚠️ Невалидната заявка на отчет НЕ пада никъде другаде: SQL се проверява само при"
              + " РЕНДИРАНЕ, тоест когато човек натисне „Печат“ и получи изключение вместо PDF.\n  "
              + String.join("\n  ", failed));
    }
  }

  private static boolean tableExists(Connection c, String table) throws Exception {
    try (Statement s = c.createStatement();
        ResultSet rs =
            s.executeQuery(
                "SELECT count(*) FROM information_schema.tables WHERE table_name = '"
                    + table
                    + "'")) {
      return rs.next() && rs.getInt(1) > 0;
    }
  }

  /**
   * Обявените типове на параметрите на един набор данни, по позиция.
   *
   * <p>⚠️ БРОЯТ СЕ СВЕРЯВА С БРОЯ НА „?“ В ЗАЯВКАТА, а не се приема наготово. Образец, който
   * обявява два параметъра, а SQL-ът иска три (или обратното), днес не пада никъде: BIRT свързва
   * колкото е обявено, а остатъкът гърми при „Печат“. Тук несъответствието е отказ с назована
   * причина.
   */
  private static List<String> parameterTypes(String dataSetBlock, String query) {
    List<String> out = new ArrayList<>();
    // ⚠️ САМО блокът с параметрите: колоните на РЕЗУЛТАТА също обявяват dataType и биха се
    // преброили за параметри — тоест типовете щяха да се изместят с цяла редица.
    int from = dataSetBlock.indexOf("<list-property name=\"parameters\">");
    int to = from < 0 ? -1 : dataSetBlock.indexOf("</list-property>", from);
    if (from >= 0 && to > from) {
      Matcher m =
          java.util.regex.Pattern.compile("<property name=\"dataType\">([a-z]+)</property>")
              .matcher(dataSetBlock.substring(from, to));
      while (m.find()) {
        out.add(m.group(1));
      }
    }
    int placeholders = query.length() - query.replace("?", "").length();
    if (placeholders != out.size()) {
      throw new IllegalStateException(
          "заявката иска "
              + placeholders
              + " параметъра, а наборът данни обявява "
              + out.size()
              + " — BIRT връзва обявените, а SQL чака своите");
    }
    return out;
  }

  /** ⚠️ Числово за число, низ за низ. Стойността е без значение — типът НЕ е. */
  private static void bind(PreparedStatement ps, List<String> types) throws Exception {
    for (int i = 0; i < types.size(); i++) {
      if ("string".equals(types.get(i))) {
        ps.setString(i + 1, "");
      } else {
        ps.setLong(i + 1, 0L);
      }
    }
  }

  /** Наборите данни на един образец — всеки със СВОЯТА заявка и СВОИТЕ параметри. */
  private static List<String> dataSets(String text) {
    List<String> out = new ArrayList<>();
    Matcher m =
        java.util.regex.Pattern.compile(
                "<oda-data-set\\b.*?</oda-data-set>", java.util.regex.Pattern.DOTALL)
            .matcher(text);
    while (m.find()) {
      out.add(m.group());
    }
    return out;
  }

  private static Matcher queries(String text) {
    return java.util.regex.Pattern.compile(
            "<xml-property\\s+name=\"queryText\">\\s*<!\\[CDATA\\[(.*?)]]>\\s*</xml-property>",
            java.util.regex.Pattern.DOTALL)
        .matcher(text);
  }

  private static List<Path> designs() throws IOException {
    Path dir = BgDomainColumns.moduleRoot().resolve(REPORTS);
    try (Stream<Path> paths = Files.list(dir)) {
      List<Path> out = new ArrayList<>();
      paths.filter(p -> p.getFileName().toString().endsWith(".rptdesign")).forEach(out::add);
      out.sort(Path::compareTo);
      return out;
    }
  }
}
