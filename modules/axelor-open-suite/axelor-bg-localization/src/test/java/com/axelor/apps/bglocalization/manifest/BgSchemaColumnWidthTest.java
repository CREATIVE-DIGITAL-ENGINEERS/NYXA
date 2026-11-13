package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns.Width;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.persistence.Table;
import javax.persistence.Transient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * БАЗОВАТА половина на теста за колоните: отговаря ли ДЕЙСТВИТЕЛНАТА колона на декларираната.
 *
 * <p><b>Защо файловата половина не стига.</b> {@code BgSeedColumnWidthTest} доказва, че стойността
 * в seed-а се побира в колоната, КАКТО Е ДЕКЛАРИРАНА. Нищо в него не доказва, че базата е такава. И
 * не може: {@code ddl=update} добавя колони, но НЕ разширява съществуваща — затова всяко {@code
 * large="true"} върху заварено поле иска ръчна миграция.
 *
 * <p>Следствието е състояние, което може да живее безкрайно: {@code large="true"} в домейн XML-а и
 * {@code varchar(255)} в базата, едновременно. Билдът е зелен — файловият тест мери срещу
 * декларацията и е прав. Hibernate мълчи — типът на колоната не му е грешка. Единственият симптом е
 * ред, който изчезва при App Install, защото вносителят отхвърля целия ред с „value too long“ и
 * продължава със следващия.
 *
 * <p><b>Тоест този тест пита едно нещо:</b> пусната ли е миграцията върху ТАЗИ база. Пропуснатата
 * миграция е точно толкова дефект, колкото и липсващата.
 *
 * <p><b>Обхватът е целият модул</b>, а не едно поле: един и същи дефект засяга всяко текстово поле
 * (например {@code BgCompensationType.note}, {@code BgInsuranceContribution.bgNote}, {@code
 * BgPayrollParameter.description}), затова не се гони поредното поле.
 *
 * <p><b>Само по-тясното е дефект.</b> Колона, по-ШИРОКА от декларираната, не е: тя приема всичко,
 * което декларацията позволява. Такова разминаване се среща след връщане на {@code large="true"} и
 * не поражда изчезващи редове.
 *
 * <p>Декларацията се чете от домейн XML-ите през {@link BgDomainColumns} — същия прочит, който
 * ползва и файловата половина. Имената на таблиците идват от {@code @Table} на генерирания клас,
 * тоест от онова, което Hibernate наистина ползва, а не от предположение за представка.
 */
class BgSchemaColumnWidthTest {

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
              + " — твърдението „действителната колона отговаря на декларацията“ НЕ е проверено."
              + " Файловата половина (BgSeedColumnWidthTest) тече и без база, но тя мери срещу"
              + " ДЕКЛАРАЦИЯТА и не може да улови непусната миграция.");
      return null;
    }
  }

  /** Действителният тип на всяка колона: {@code таблица.колона → „text“ или брой знаци}. */
  private static Map<String, Width> actualColumns(Connection c) throws Exception {
    Map<String, Width> out = new TreeMap<>();
    String sql =
        "SELECT lower(table_name), lower(column_name), data_type, character_maximum_length"
            + " FROM information_schema.columns WHERE table_schema = current_schema()";
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        String key = rs.getString(1) + "." + rs.getString(2);
        int length = rs.getInt(4);
        out.put(key, Width.of(rs.wasNull() ? null : length));
      }
    }
    if (out.isEmpty()) {
      fail("information_schema не върна нито една колона — това не е база, на която да се вярва");
    }
    return out;
  }

  /** Таблиците на схемата, за да се различи „липсва колоната“ от „липсва цялата таблица“. */
  private static Set<String> actualTables(Connection c) throws Exception {
    Set<String> out = new TreeSet<>();
    try (Statement st = c.createStatement();
        ResultSet rs =
            st.executeQuery(
                "SELECT lower(table_name) FROM information_schema.tables"
                    + " WHERE table_schema = current_schema()")) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  @Test
  @DisplayName("БАЗА · ⚠️ ИНВАРИАНТ: действителната колона не е по-тясна от декларираната")
  void noActualColumnIsNarrowerThanDeclared() throws Exception {
    Map<String, Map<String, Width>> declared = BgDomainColumns.inThisModule();
    assertTrue(
        declared.size() > 10,
        "домейн XML-ите на модула не са прочетени — само " + declared.size() + " entity-та");

    try (Connection c = open()) {
      if (c == null) {
        return; // BgSkip вече е прекъснал теста
      }

      Map<String, Width> actual = actualColumns(c);
      Set<String> tables = actualTables(c);

      List<String> narrower = new ArrayList<>();
      List<String> missingColumns = new ArrayList<>();
      Set<String> missingTables = new TreeSet<>();
      int checked = 0;
      int tablesFound = 0;

      for (Map.Entry<String, Map<String, Width>> entry : declared.entrySet()) {
        String fqn = entry.getKey();
        if (entry.getValue().isEmpty()) {
          continue; // entity без низови полета — няма какво да се мери
        }

        Class<?> type;
        try {
          type = Class.forName(fqn);
        } catch (ClassNotFoundException e) {
          fail(
              "домейн XML обявява "
                  + fqn
                  + ", а такъв клас няма в classpath-а — генерирането е изостанало от XML-а");
          return;
        }

        Table table = type.getAnnotation(Table.class);
        if (table == null) {
          continue; // не е таблица (напр. вграден тип) — извън обхвата на този въпрос
        }
        String tableName = BgDomainColumns.tableName(table.name());

        if (!tables.contains(tableName)) {
          missingTables.add(tableName + " (" + fqn + ")");
          continue;
        }
        tablesFound++;

        for (Map.Entry<String, Width> field : entry.getValue().entrySet()) {
          if (isTransient(type, field.getKey())) {
            continue; // @Transient — няма колона, по декларация
          }
          String column = BgDomainColumns.columnName(field.getKey());
          Width want = field.getValue();
          Width have = actual.get(tableName + "." + column);

          if (have == null) {
            missingColumns.add(
                tableName + "." + column + " — декларирана " + want + ", а колоната липсва");
            continue;
          }
          checked++;

          if (isNarrower(have, want)) {
            narrower.add(
                String.format(
                    "%s.%s · действително %s срещу декларирано %s", tableName, column, have, want));
          }
        }
      }

      if (tablesFound == 0) {
        BgSkip.because(
            "нито една таблица на модула не съществува в тази база — схемата не е създавана, тоест"
                + " приложението не е вдигано с този код. Твърдението „действителната колона"
                + " отговаря на декларацията“ НЕ е проверено.");
      }
      assertTrue(
          checked > 50,
          "таблиците на модула ги има ("
              + tablesFound
              + "), а сверени са само "
              + checked
              + " колони — свързването на имена е спряло да намира колони, тоест мълча вместо да меря");

      // ⚠️ ЛИПСВАЩАТА колона НЕ е падане, а бележка, и разликата е нарочна. По-тясната колона е
      // ДЕФЕКТ: ddl=update няма да я поправи никога, стои си и мълчи, и се маха само с ръчна
      // миграция. Липсващата е СЪСТОЯНИЕ НА СРЕДАТА: ddl=update я добавя при първото вдигане на
      // приложението с този код. Червено за второто би значело „не си рестартирал“ — а червено,
      // което не значи нищо, приучава да не се гледа. Затова се ПЕЧАТА, вижда се в отчета, и не
      // подменя отговора на въпроса, който този тест задава.
      if (!missingColumns.isEmpty() || !missingTables.isEmpty()) {
        System.out.println(
            "⚠️ НЕПРОВЕРЕНИ колони — базата още не ги съдържа, тоест ddl=update не е минавал с този"
                + " код. Ширината им НЕ е проверена; вдигни приложението и пусни пак.");
        for (String m : missingColumns) {
          System.out.println("   " + m);
        }
        for (String t : missingTables) {
          System.out.println("   цялата таблица липсва: " + t);
        }
      }

      if (!narrower.isEmpty()) {
        fail(
            "колони, ПО-ТЕСНИ от декларираното — миграцията НЕ е пусната върху тази база ("
                + narrower.size()
                + "):\n  "
                + String.join("\n  ", narrower)
                + "\n\nddl=update няма да ги поправи: той добавя колони, но не мени типа на"
                + " съществуваща. Иска се миграция в src/main/resources/db/migration/, пусната ПРЕДИ"
                + " следващия App Install — иначе вносителят ще отхвърли дългите редове мълчаливо и"
                + " таблицата ще излезе с по-малко редове, отколкото казва CSV-то.");
      }
    }
  }

  private static boolean isNarrower(Width have, Width want) {
    if (want.isText()) {
      return !have.isText(); // декларирано text, а колоната има таван
    }
    if (have.isText()) {
      return false; // по-широко от искането — не е дефект
    }
    return have.chars < want.chars;
  }

  private static boolean isTransient(Class<?> type, String field) {
    for (Class<?> k = type; k != null; k = k.getSuperclass()) {
      try {
        Field f = k.getDeclaredField(field);
        return f.getAnnotation(Transient.class) != null;
      } catch (NoSuchFieldException ignored) {
        // полето може да е обявено в родител — търси нататък
      }
    }
    return false;
  }
}
