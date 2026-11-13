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
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПАЗАЧЪТ НА СЪХРАНЕНИЯ ВИД ОСИГУРЕН — базовата половина: „вярно ли е ЗАПИСАНО В БАЗАТА“.
 *
 * <p><b>Защо двете половини не са една.</b> {@code BgInsuredTypeHomeFileTest} пита дали ПРАВИЛАТА
 * са верни и дали списъците съвпадат със seed-а; той тече винаги и без Postgres. Този пита нещо,
 * което файловете не могат да знаят: какво пише по ДЕЙСТВИТЕЛНИТЕ договори. Слети в един тест,
 * отговорът „няма база“ би маскирал отговора „по договор №14 стои самоосигуряващ код“.
 *
 * <p><b>⚠️ ПРАВИЛАТА НЕ СЕ ПРЕПИСВАТ.</b> Сверката минава през {@code
 * BgInsuredTypeHomeFileTest#disagreements}, същия метод, който проверява и синтетичните случаи. Две
 * копия на едно правило се разминават при първата поправка и разминаването е тихо — зеленото на
 * едната половина изглежда точно като зеленото на другата.
 *
 * <p><b>Пропуска се при ДВЕ състояния на средата, всяко с обявена причина</b>: няма достъпна база,
 * или колоната още я няма (миграцията {@code db/migration/2026-08-18-bg-insured-type-home.sql} не е
 * пускана и {@code ddl=update} не е минавал). ⚠️ Празна таблица с договори НЕ е повод за пропускане
 * — нула договора е валидно състояние и твърдението „нито един договор не е разминат“ е вярно за
 * него.
 */
class BgInsuredTypeHomeDbTest {

  private static final String T = "hr_employment_contract";

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
              + " — твърдението „съхранените видове осигурен са съгласувани“ НЕ е проверено."
              + " Файловата половина (BgInsuredTypeHomeFileTest) тече и без база.");
      return null;
    }
  }

  /** Колоната съществува ли изобщо — инак въпросът е за схемата, не за данните. */
  private static void requireColumn(Connection c, String column) throws Exception {
    String found =
        one(
            c,
            "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = '"
                + T
                + "' AND column_name = '"
                + column
                + "'");
    if ("0".equals(found)) {
      BgSkip.because(
          "колоната "
              + T
              + "."
              + column
              + " още я НЯМА — тоест нито db/migration/2026-08-18-bg-insured-type-home.sql е"
              + " пускан, нито ddl=update е минавал с новия домейн. Това е състояние на средата, не"
              + " на кода: няма какво да се сверява, защото домът още не е построен.");
    }
  }

  @Test
  @DisplayName("БАЗА · нито един договор не носи вид осигурен, разминат с изводимите сигнали")
  void noStoredContractDisagreesWithTheDerivableSignals() throws Exception {
    try (Connection c = open()) {
      requireColumn(c, "bg_insured_type_code");
      requireColumn(c, "bg_seafarer_unemployment_choice");

      // ⚠️ Категорията труд е на ЛИЦЕТО (Employee.bgLaborCategory), не на договора — точно това
      // измерва и сглобителят. LEFT JOIN, а не INNER: договор без служител е дефект в данните,
      // но не е дефект във ВИДА ОСИГУРЕН и не бива да се губи от сверката.
      List<String> rows =
          query(
              c,
              "SELECT ct.id, ct.bg_insured_type_code, e.bg_labor_category,"
                  + " coalesce(ct.bg_is_teacher_position, false),"
                  + " coalesce(ct.bg_seafarer_unemployment_choice, false)"
                  + " FROM "
                  + T
                  + " ct LEFT JOIN hr_employee e ON e.id = ct.employee"
                  + " WHERE ct.bg_insured_type_code IS NOT NULL"
                  + " AND btrim(ct.bg_insured_type_code) <> ''"
                  + " ORDER BY ct.id");

      List<String> problems = new ArrayList<>();
      for (String row : rows) {
        String[] cols = row.split(" \\| ", -1);
        problems.addAll(
            BgInsuredTypeHomeFileTest.disagreements(
                "договор #" + cols[0],
                cols[1],
                "null".equals(cols[2]) ? null : cols[2],
                "t".equalsIgnoreCase(cols[3]) || "true".equalsIgnoreCase(cols[3]),
                "t".equalsIgnoreCase(cols[4]) || "true".equalsIgnoreCase(cols[4])));
      }

      if (!problems.isEmpty()) {
        fail(
            "СЪХРАНЕН ВИД ОСИГУРЕН, РАЗМИНАТ С ИЗВОДИМИТЕ СИГНАЛИ ("
                + problems.size()
                + " разминаване(ия) по "
                + rows.size()
                + " договора):\n  "
                + String.join("\n  ", problems)
                + "\n\n  ⚠️ Разминаването НЕ се поправя от кода. Сгрешеният вид осигурен избира"
                + " ДРУГ набор ставки и дава вноска, която изглежда точно като вярна — тоест"
                + " мълчаливата поправка би скрила точно записа, който трябва да се види."
                + " Поправете данните по договора.");
      }
    }
  }

  @Test
  @DisplayName("БАЗА · всеки съхранен код е от номенклатурата — селекцията не пази чужди стойности")
  void everyStoredCodeExistsInTheNomenclature() throws Exception {
    try (Connection c = open()) {
      requireColumn(c, "bg_insured_type_code");

      // ⚠️ Сверява се срещу СЕЛЕКЦИЯТА през файловата половина, а не срещу таблицата
      // bglocalization_bg_insured_type: тя се пълни само през App Install и празнотата ѝ би
      // обявила ВСЕКИ съхранен код за чужд. Въпросът тук е за договорите, не за seed-а.
      TreeSet<String> known = new TreeSet<>();
      for (String[] row :
          com.axelor.apps.bglocalization.testsupport.BgSeed.quotedRows("bg_insured_type.csv")) {
        known.add(row[1]);
      }

      List<String> stored =
          query(
              c,
              "SELECT DISTINCT btrim(bg_insured_type_code) FROM "
                  + T
                  + " WHERE bg_insured_type_code IS NOT NULL"
                  + " AND btrim(bg_insured_type_code) <> '' ORDER BY 1");

      TreeSet<String> foreign = new TreeSet<>(stored);
      foreign.removeAll(known);
      if (!foreign.isEmpty()) {
        fail(
            "ДОГОВОР С ВИД ОСИГУРЕН ИЗВЪН НОМЕНКЛАТУРАТА: "
                + foreign
                + "\n  ⚠️ Стойностите на селекциите НЕ се проверяват от сървъра — известен риск,"
                + " записан и при bg.insurance.fund.select. Тоест чужда стойност не пречи нито на"
                + " импорта, нито на записа, а само на четенето: гридът показва гола стойност, а"
                + " BgContributionResolver не намира нито един ред.\n"
                + "  Възможна е и другата посока: кодът е БИЛ валиден, а редакция на"
                + " номенклатурата го е премахнала (ДВ бр. 15/2024 премахна 07, 21, 23 и 93). Тогава"
                + " договорът е за МИНАЛ период и въпросът е дали seed-ът трябва да носи и"
                + " историческите кодове.");
      }
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
