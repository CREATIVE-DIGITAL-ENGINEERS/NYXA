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
 * Сверява ЗАРЕДЕНАТА БАЗА срещу манифеста на речника с редове на фиша.
 *
 * <p><b>Защо срещу базата.</b> App Install връща {@code status: 0} и когато е внесъл всичко, и
 * когато не е направил нищо — при активно приложение излиза още на първия ред. „Импортирано“ и „не
 * направено“ изглеждат еднакво отвън, затова тук се броят редове, а не се чете лог.
 *
 * <p><b>Защо очакваните числа са в YAML, а не в кода.</b> Утре има втора вълна редове (2xx и 3xx) —
 * тогава се сменя един файл с данни, а не тест. Същият идиом като {@code BgZbdooManifestTest}.
 *
 * <p>Тестът се ПРОПУСКА, когато няма достъпна база. Базата е тази на ПРИЛОЖЕНИЕТО и идва само от
 * {@code db.default.url} — виж {@link com.axelor.apps.bglocalization.testsupport.BgTestDb}.
 */
class BgPaySlipLineTypeManifestTest {

  private static final String MANIFEST = "/apps/init-data/manifest/payslip-line-type.yml";
  private static final String T = "bglocalization_bg_pay_slip_line_type";

  @SuppressWarnings("unchecked")
  private static Map<String, Object> expected() throws Exception {
    try (InputStream in = BgPaySlipLineTypeManifestTest.class.getResourceAsStream(MANIFEST)) {
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
              + " — речникът с редове на фиша срещу манифеста му НЕ е проверено.");
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

  private static String one(Connection c, String sql) throws Exception {
    List<String> r = query(c, sql);
    return r.isEmpty() ? null : r.get(0);
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
  @DisplayName("Базата отговаря на манифеста на речника с редове на фиша")
  @SuppressWarnings("unchecked")
  void theDatabaseMatchesTheManifest() throws Exception {
    Map<String, Object> exp = expected();

    try (Connection c = open()) {

      // ⚠️ Първо: има ли изобщо какво да се проверява. Всичко по-долу би минало и над празна
      // таблица, ако се брояха само съответствия.
      String общо = one(c, "SELECT count(*) FROM " + T);
      if ("0".equals(общо)) {
        fail("речникът с редове на фиша е ПРАЗЕН — минал ли е App Install след uninstall?");
      }

      check("редове общо", exp.get("редове_общо"), общо);
      check(
          "най-малък номер",
          exp.get("най_малък_номер"),
          one(c, "SELECT min(line_number) FROM " + T));
      check(
          "най-голям номер",
          exp.get("най_голям_номер"),
          one(c, "SELECT max(line_number) FROM " + T));
      check(
          "уникални кодове",
          exp.get("уникални_кодове"),
          one(c, "SELECT count(DISTINCT code) FROM " + T));
      check(
          "уникални номера",
          exp.get("уникални_номера"),
          one(c, "SELECT count(DISTINCT line_number) FROM " + T));

      // Групите — всяка поотделно, включително двете, които трябва да са ПРАЗНИ. Нула, която е
      // записана като очаквана, е решение; нула, за която никой не пита, е пропуск.
      Map<String, Object> групи = (Map<String, Object>) exp.get("по_група");
      Map<String, String> намерени = new LinkedHashMap<>();
      for (String ред : query(c, "SELECT bg_group, count(*) FROM " + T + " GROUP BY 1")) {
        String[] p = ред.split(" \\| ");
        намерени.put(p[0], p[1]);
      }
      for (Map.Entry<String, Object> e : групи.entrySet()) {
        check("група " + e.getKey(), e.getValue(), намерени.getOrDefault(e.getKey(), "0"));
      }
      check(
          "групи, които се срещат в базата, но не и в манифеста",
          "[]",
          намерени.keySet().stream()
              .filter(k -> !групи.containsKey(k))
              .sorted()
              .collect(java.util.stream.Collectors.toList())
              .toString());

      // Постоянният характер по НСОРЗ чл. 15 — брой И поименно. Броят сам би минал и при разместени
      // флагове.
      Map<String, Object> пост = (Map<String, Object>) exp.get("постоянен_характер");
      check(
          "постоянен характер · брой",
          пост.get("брой"),
          one(c, "SELECT count(*) FROM " + T + " WHERE bg_is_permanent_character = true"));
      check(
          "постоянен характер · кодове",
          String.join(", ", (List<String>) пост.get("кодове")),
          String.join(
              ", ",
              query(
                  c,
                  "SELECT code FROM "
                      + T
                      + " WHERE bg_is_permanent_character = true ORDER BY line_number")));

      check(
          "редове с попълнен фонд",
          exp.get("с_попълнен_фонд"),
          one(c, "SELECT count(*) FROM " + T + " WHERE bg_contribution_fund IS NOT NULL"));
      check(
          "редове с попълнен вид обезщетение",
          exp.get("с_попълнен_вид_обезщетение"),
          one(c, "SELECT count(*) FROM " + T + " WHERE bg_compensation_type IS NOT NULL"));
    }
  }

  @Test
  @DisplayName("⚠️ Перото на болничния по чл. 40 ал. 5 КСО е ДОСТАВЕНО, с двата си флага")
  @SuppressWarnings("unchecked")
  void theEmployerSickLeaveLineIsDelivered() throws Exception {
    Map<String, Object> ред = (Map<String, Object>) expected().get("bolnichen_rabotodatel");
    Assumptions.assumeTrue(ред != null, "манифестът няма блок „bolnichen_rabotodatel“");
    String код = String.valueOf(ред.get("код"));

    try (Connection c = open()) {
      // ⚠️ Броят е ПРЕДИ флаговете: липсващият ред би дал null на всяка следваща проверка и
      // съобщението щеше да говори за флаг, вместо за незареден ред.
      check(
          "перото „" + код + "“ е в базата (пуснат ли е App Install след добавянето му?)",
          "1",
          one(c, "SELECT count(*) FROM " + T + " WHERE code = '" + код + "'"));
      check(
          "перото „" + код + "“ · номер",
          ред.get("номер"),
          one(c, "SELECT line_number FROM " + T + " WHERE code = '" + код + "'"));
      check(
          "перото „" + код + "“ · група",
          ред.get("група"),
          one(c, "SELECT bg_group FROM " + T + " WHERE code = '" + код + "'"));
      // ⚠️ Двата флага се питат ПООТДЕЛНО, защото отговарят на РАЗЛИЧНИ закона: осигурителен доход
      // по чл. 40 ал. 5 КСО, необлагаем по ЗДДФЛ чл. 24 ал. 2 т. 14. Питани заедно, разминаването
      // на единия щеше да се чете като разминаване на другия.
      //
      // ⚠️ ::text НЕ Е УКРАСА. rs.getString() върху boolean колона на PostgreSQL връща „t“ и „f“,
      // не
      // „true“ и „false“ — тоест сравнението мълчаливо пада с очаквано true срещу намерено t.
      // Явното
      // кастване дава „true“/„false“ и пази NULL като NULL, тоест „колоната е празна“ остава
      // различимо от „колоната е false“. Заварените проверки по-горе не се спъват в това, защото
      // питат с WHERE, а не четат стойността.
      check(
          "перото „" + код + "“ · осигурителен доход (чл. 40 ал. 5 КСО)",
          "true",
          one(c, "SELECT bg_is_insurance_income::text FROM " + T + " WHERE code = '" + код + "'"));
      check(
          "перото „" + код + "“ · НЕоблагаем (ЗДДФЛ чл. 24 ал. 2 т. 14)",
          "false",
          one(c, "SELECT bg_is_taxable::text FROM " + T + " WHERE code = '" + код + "'"));
      check(
          "перото „" + код + "“ · без постоянен характер (НСОРЗ чл. 15)",
          "false",
          one(
              c,
              "SELECT bg_is_permanent_character::text FROM " + T + " WHERE code = '" + код + "'"));
    }
  }

  @Test
  @DisplayName("Манифестът е за този речник")
  void theManifestIsTheRightOne() throws Exception {
    try (InputStream in = BgPaySlipLineTypeManifestTest.class.getResourceAsStream(MANIFEST)) {
      Assumptions.assumeTrue(in != null);
      @SuppressWarnings("unchecked")
      Map<String, Object> m = new ObjectMapper(new YAMLFactory()).readValue(in, Map.class);
      assertEquals("Речник на редовете на фиша, начален набор", m.get("издание"), "друг манифест");
    }
  }
}
