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
 * Пази съответствието ЕДНО КЪМ ЕДНО между речника с редове на фиша и двата каталога, от които е
 * генериран: видовете обезщетения и осигурителните фондове.
 *
 * <p><b>Какво лови.</b> Групите 5xx и 9xx не са писани на ръка — те са отражение на два готови
 * каталога. Отражението се разминава мълчаливо по три начина: добавен вид обезщетение без ред на
 * фиша (обезщетението няма къде да се отпечата), ред на фиша без каталог зад себе си (отпечатва се
 * празен), и разминало се име (фишът казва едно, каталогът друго). Нито едно от трите не чупи
 * импорта.
 *
 * <p><b>Защо не е част от манифестния тест.</b> Манифестът брои — този сверява. Числата съвпадат и
 * при разместени връзки: 25 реда срещу 25 обезщетения минава и когато два реда сочат към един и
 * същи вид, а трети към нищо.
 *
 * <p>Пропуска се, когато няма достъпна база.
 */
class BgPaySlipLineTypeCatalogueTest {

  private static final String T = "bglocalization_bg_pay_slip_line_type";

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
              + " — съответствието едно към едно между речниците и редовете на фиша НЕ е проверено.");
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

  private static void empty(Connection c, String какво, String sql) throws Exception {
    List<String> r = query(c, sql);
    if (!r.isEmpty()) {
      fail(какво + " — " + r.size() + " случая:\n  " + String.join("\n  ", r));
    }
  }

  @Test
  @DisplayName("Всеки вид обезщетение има точно един ред на фиша, със същото име")
  void everyCompensationTypeHasExactlyOnePaySlipLine() throws Exception {
    try (Connection c = open()) {
      String видове = query(c, "SELECT count(*) FROM bglocalization_bg_compensation_type").get(0);
      if ("0".equals(видове)) {
        fail("каталогът на обезщетенията е празен — минал ли е App Install?");
      }

      empty(
          c,
          "вид обезщетение БЕЗ ред на фиша (няма къде да се отпечата)",
          "SELECT t.code FROM bglocalization_bg_compensation_type t WHERE NOT EXISTS ("
              + "SELECT 1 FROM "
              + T
              + " l WHERE l.bg_compensation_type = t.id) ORDER BY t.code");

      empty(
          c,
          "вид обезщетение с ПОВЕЧЕ ОТ ЕДИН ред на фиша",
          "SELECT t.code || ' → ' || count(*) FROM bglocalization_bg_compensation_type t "
              + "JOIN "
              + T
              + " l ON l.bg_compensation_type = t.id "
              + "GROUP BY t.code HAVING count(*) > 1 ORDER BY t.code");

      empty(
          c,
          "ред на фиша от група COMPENSATIONS БЕЗ вид обезщетение зад себе си (печата се празен)",
          "SELECT code FROM "
              + T
              + " WHERE bg_group = 'COMPENSATIONS' "
              + "AND bg_compensation_type IS NULL ORDER BY line_number");

      empty(
          c,
          "името на реда се е разминало с името на вида обезщетение",
          "SELECT l.code || ': „' || l.name || '“ ≠ „' || t.name || '“' FROM "
              + T
              + " l "
              + "JOIN bglocalization_bg_compensation_type t ON t.id = l.bg_compensation_type "
              + "WHERE l.name <> t.name ORDER BY l.line_number");
    }
  }

  @Test
  @DisplayName("Всеки фонд има точно два реда на фиша — по един за всеки платец")
  void everyFundHasExactlyOneLinePerPayer() throws Exception {
    try (Connection c = open()) {
      String фондове = query(c, "SELECT count(*) FROM bglocalization_bg_contribution_fund").get(0);
      if ("0".equals(фондове)) {
        fail("речникът на фондовете е празен — минал ли е App Install?");
      }

      empty(
          c,
          "фонд без пълния чифт редове (осигурител CREDIT и лице DEBIT)",
          "SELECT f.code || ' → ' || coalesce(string_agg(l.bg_sign, ', ' ORDER BY l.bg_sign), 'нищо') "
              + "FROM bglocalization_bg_contribution_fund f "
              + "LEFT JOIN "
              + T
              + " l ON l.bg_contribution_fund = f.id "
              + "GROUP BY f.code HAVING count(l.id) <> 2 "
              + "OR count(*) FILTER (WHERE l.bg_sign = 'CREDIT') <> 1 "
              + "OR count(*) FILTER (WHERE l.bg_sign = 'DEBIT') <> 1 ORDER BY f.code");

      empty(
          c,
          "ред на фиша от група CONTRIBUTIONS БЕЗ фонд зад себе си (печата се празен)",
          "SELECT code FROM "
              + T
              + " WHERE bg_group = 'CONTRIBUTIONS' "
              + "AND bg_contribution_fund IS NULL ORDER BY line_number");

      // Осигуровката не е доход: нито се облага, нито е осигурителен доход сама по себе си.
      empty(
          c,
          "ред за осигуровка, обявен за облагаем или за осигурителен доход",
          "SELECT code FROM "
              + T
              + " WHERE bg_group = 'CONTRIBUTIONS' "
              + "AND (bg_is_taxable = true OR bg_is_insurance_income = true) ORDER BY line_number");
    }
  }

  @Test
  @DisplayName("Удръжките не са нито облагаеми, нито осигурителен доход")
  void deductionsAreNeitherTaxableNorInsurable() throws Exception {
    try (Connection c = open()) {
      empty(
          c,
          "удръжка (8xx), обявена за облагаема или за осигурителен доход — тя е от НЕТНОТО",
          "SELECT code FROM "
              + T
              + " WHERE bg_group = 'DEDUCTIONS' "
              + "AND (bg_is_taxable = true OR bg_is_insurance_income = true) ORDER BY line_number");
    }
  }
}
