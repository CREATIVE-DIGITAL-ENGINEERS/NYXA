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
 * ЖИВАТА БАЗА: ТРЗ редиците са ПО ДРУЖЕСТВО и миграцията за номерирането по дружество е минала.
 *
 * <p>Обща редица bgCompensation без дружество и резервен брояч OBEZ-RES-… няма: документ на
 * дружество без своя редица се отказва поименно. Затова се проверява:
 *
 * <ol>
 *   <li>всяко дружество има точно по една редица bgPaySlip и bgCompensation с правилния формат
 *       (новите — при раждането си, заварените — при старта, {@code BgTrzSequenceBackfill});
 *   <li>фиш или обезщетение с номер, но без дружество, няма (миграцията ги е попълнила);
 *   <li>глобално уникално ограничение САМО по reference няма — инак номерата на второто дружество
 *       се сблъскват с чуждите.
 * </ol>
 *
 * <p>Пропуска се, когато няма достъпна база. Пада на база, на която {@code
 * db/migration/2026-10-21-trz-numbering-per-company.sql} още не е минала — и така трябва: точно
 * тогава номерирането във второ дружество се къса на глобалния ключ.
 */
class BgTrzSequencePerCompanyManifestTest {

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
              + " — ТРЗ редиците по дружество НЕ са проверени.");
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
  @DisplayName("всяко дружество има своите ТРЗ редици; документ без дружество и глобален ключ няма")
  void everyCompanyHasItsOwnTrzSequences() throws Exception {
    try (Connection c = open()) {
      List<String> problems = new ArrayList<>();
      boolean notStartedYet = false;

      // (1) По една редица на код за всяко дружество, във формата на BgTrzSequences#newFor.
      // ⚠️ yearly_reset_ok се CAST-ва: JDBC връща булево като „t“, не „true“.
      List<String> wrong =
          query(
              c,
              "SELECT co.code, k.code, count(s.id),"
                  + " string_agg(s.prefixe || '/' || s.padding || '/' || s.to_be_added || '/'"
                  + " || s.yearly_reset_ok::text, ', ')"
                  + " FROM base_company co"
                  + " CROSS JOIN (VALUES ('bgPaySlip', 'FISH-%YYYY-'),"
                  + " ('bgCompensation', 'OBEZ-%YYYY-')) AS k(code, prefix)"
                  + " LEFT JOIN base_sequence s ON s.company = co.id AND s.code_select = k.code"
                  + " GROUP BY co.code, k.code, k.prefix"
                  + " HAVING count(s.id) <> 1"
                  + " OR bool_or(s.prefixe IS DISTINCT FROM k.prefix OR s.padding <> 4"
                  + " OR s.to_be_added <> 1 OR NOT s.yearly_reset_ok)"
                  + " ORDER BY 1, 2");
      // Никое дружество още няма своите редици: новият код не е стартирал на тази база (редиците
      // идват при старта, BgTrzSequenceBackfill — проверен в BgTrzSequenceBackfillTest). Това е
      // състоянието на локалната база веднага след сливане, а не дефект — сборката не бива да
      // зависи от него. Част от дружествата без редица обаче е дефект и си пада.
      boolean backfillNotRunYet =
          !wrong.isEmpty() && wrong.stream().allMatch(row -> row.split(" \\| ")[2].equals("0"));
      if (backfillNotRunYet
          && query(
                  c,
                  "SELECT 1 FROM base_sequence WHERE code_select IN"
                      + " ('bgPaySlip', 'bgCompensation') AND company IS NOT NULL LIMIT 1")
              .isEmpty()) {
        wrong = new ArrayList<>();
        notStartedYet = true;
      }
      if (!wrong.isEmpty()) {
        problems.add(
            "дружества без своя ТРЗ редица или с друг формат (дружество | код | брой | формат):\n  "
                + String.join("\n  ", wrong));
      }

      // (2) Номерирани документи без дружество.
      List<String> orphans =
          query(
              c,
              "SELECT 'фиш', count(*) FROM bglocalization_bg_pay_slip"
                  + " WHERE company IS NULL AND reference IS NOT NULL"
                  + " UNION ALL SELECT 'обезщетение', count(*) FROM bglocalization_bg_compensation"
                  + " WHERE company IS NULL AND reference IS NOT NULL");
      for (String row : orphans) {
        if (!row.endsWith(" | 0")) {
          problems.add("документи с номер без дружество: " + row);
        }
      }

      // (3) Глобално уникално ограничение само по reference.
      List<String> global =
          query(
              c,
              "SELECT con.conrelid::regclass::text, con.conname FROM pg_constraint con"
                  + " JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attname = 'reference'"
                  + " WHERE con.contype = 'u' AND con.conkey = ARRAY[a.attnum]"
                  + " AND con.conrelid IN ('bglocalization_bg_pay_slip'::regclass,"
                  + " 'bglocalization_bg_compensation'::regclass)");
      if (!global.isEmpty()) {
        problems.add("глобално уникално по reference още стои: " + String.join(" · ", global));
      }

      if (!problems.isEmpty()) {
        fail(
            "номерирането по дружество не е довършено на тази база — редиците идват при старта"
                + " (BgTrzSequenceBackfill),"
                + " а колоната и ключът — от миграцията"
                + " db/migration/2026-10-21-trz-numbering-per-company.sql:\n"
                + String.join("\n", problems));
      }
      if (notStartedYet) {
        BgSkip.because(
            "ТРЗ редиците по дружество още ги няма на тази база: приложението не е стартирало с"
                + " новия код (BgTrzSequenceBackfill ги прави при старта). Схемата е проверена;"
                + " редиците — след първия старт.");
      }
    }
  }
}
