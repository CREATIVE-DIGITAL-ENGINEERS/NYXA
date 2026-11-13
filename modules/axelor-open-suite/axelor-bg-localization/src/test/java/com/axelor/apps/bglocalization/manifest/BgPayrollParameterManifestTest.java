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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Сверява заредените скаларни параметри срещу манифеста им.
 *
 * <p><b>Какво лови и защо съществува.</b> Редакция на seed, която НЕ е влязла, изглежда точно като
 * редакция, която е влязла: App Install връща успех и при нула променени реда. Например филтър,
 * който търси importId с префикс „PP-“, пропуска реда на {@code SENIORITY_MIN_PCT} ({@code
 * CLASS-2026}): CSV-то остава непроменено и импортът минава чисто. Хваща го само проверка в базата.
 *
 * <p><b>Проверката за непразно описание</b> е тази, която хваща такъв случай директно: параметър
 * без основание е число, което никой не може да защити пред проверка.
 *
 * <p>Пропуска се, когато няма достъпна база.
 */
class BgPayrollParameterManifestTest {

  private static final String MANIFEST = "/apps/init-data/manifest/payroll-parameter.yml";
  private static final String T = "bglocalization_bg_payroll_parameter";

  @SuppressWarnings("unchecked")
  private static Map<String, Object> expected() throws Exception {
    try (InputStream in = BgPayrollParameterManifestTest.class.getResourceAsStream(MANIFEST)) {
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
              + " — твърдението „данните са в базата“ НЕ е проверено. Файловата половина"
              + " (BgPayrollParameterSeedFileTest) тече и без база.");
      return null;
    }
  }

  /**
   * Пропуска САМО когато каталогът е празен за целия модул.
   *
   * <p>Празно значи „App Install не е пускан“ — състояние на средата, не на кода. Всичко останало,
   * включително „има редове, но не тези“, минава нататък и ПАДА: това е импортиран, но остарял или
   * грешен seed. Ако предикатът не различаваше двете, пропускането щеше да гълта истинска повреда в
   * данните и да върне капан 9 — този път със зелено.
   */
  private static void requireCatalogueImported(Connection c) throws Exception {
    if ("0".equals(query(c, "SELECT count(*) FROM " + T).get(0))) {
      BgSkip.because(
          "таблицата "
              + T
              + " е ПРАЗНА — App Install не е пускан, това твърдение не е проверено."
              + " Промяна в apps/init-data/ достига до базата само през uninstall → restore-all →"
              + " СВАЛЯНЕ на active и init_data_loaded → App Install (вдигането на версията"
              + " презарежда изгледи, не данни; uninstall НЕ сваля init_data_loaded, затова"
              + " то се прави с psql). Install под секунда не е внесъл нищо.");
    }
  }

  private static final String STALE =
      "\n  ⚠️ Каталогът НЕ е празен, тоест App Install е минавал — но не след тази промяна. Това е"
          + " ОСТАРЯЛ или ГРЕШЕН импорт, не липсващ: пусни App Install наново (uninstall НЕ"
          + " сваля init_data_loaded, а без свалянето install-ът връща status 0 за под секунда и не"
          + " внася нищо) и провери пак.";

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
  @DisplayName("БАЗА · заредените параметри отговарят на манифеста")
  @SuppressWarnings("unchecked")
  void theLoadedParametersMatchTheManifest() throws Exception {
    Map<String, Object> exp = expected();

    try (Connection c = open()) {
      requireCatalogueImported(c);

      String total = query(c, "SELECT count(*) FROM " + T).get(0);
      if (!String.valueOf(exp.get("редове")).equals(total)) {
        fail(
            "манифест „редове“ не е изпълнен.\n  очаква се : "
                + exp.get("редове")
                + "\n  намерено  : "
                + total
                + STALE);
      }
      check(
          "различни кода",
          exp.get("различни_кода"),
          query(c, "SELECT count(DISTINCT code) FROM " + T).get(0));

      // Всеки код: колко версии и с какви стойности, подредени по начална дата.
      Map<String, Object> codes = (Map<String, Object>) exp.get("кодове");
      for (Map.Entry<String, Object> e : codes.entrySet()) {
        Map<String, Object> want = (Map<String, Object>) e.getValue();
        List<String> found =
            query(
                c,
                "SELECT param_value FROM "
                    + T
                    + " WHERE code = '"
                    + e.getKey()
                    + "' ORDER BY valid_from");
        check("версии на " + e.getKey(), want.get("версии"), found.size());

        List<Object> values = (List<Object>) want.get("стойности");
        List<String> wanted = new ArrayList<>();
        for (Object v : values) {
          wanted.add(new java.math.BigDecimal(String.valueOf(v)).setScale(4).toPlainString());
        }
        check("стойности на " + e.getKey(), String.join(", ", wanted), String.join(", ", found));

        check(
            "мерна единица на " + e.getKey(),
            want.get("единица"),
            query(c, "SELECT DISTINCT unit FROM " + T + " WHERE code = '" + e.getKey() + "'")
                .get(0));
      }

      // Границата на двете полугодия — една и съща за четирите двойки.
      Map<String, Object> pairs = (Map<String, Object>) exp.get("двойки");
      for (String code : (List<String>) pairs.get("кодове")) {
        check(
            "периодите на " + code,
            "2026-01-01 | 2026-07-31\n2026-08-01 | null",
            String.join(
                "\n",
                query(
                    c,
                    "SELECT valid_from::text, valid_to::text FROM "
                        + T
                        + " WHERE code = '"
                        + code
                        + "' ORDER BY valid_from")));
      }

      // ⚠️ Проверката, която щеше да хване празната редакция директно.
      List<String> withoutBasis =
          query(
              c,
              "SELECT code || ' от ' || valid_from FROM "
                  + T
                  + " WHERE description IS NULL OR trim(description) = '' ORDER BY code");
      if (!withoutBasis.isEmpty()) {
        fail(
            "параметри БЕЗ основание в описанието ("
                + withoutBasis.size()
                + "): стойност без произход е число, което никой не може да защити.\n  "
                + String.join("\n  ", withoutBasis));
      }
      check(
          "редове с описание",
          exp.get("редове_с_описание"),
          query(
                  c,
                  "SELECT count(*) FROM "
                      + T
                      + " WHERE description IS NOT NULL AND trim(description) <> ''")
              .get(0));

      // Двата пода, които се разминават седем месеца — за да не се „поправи“ някой ден.
      Map<String, Object> floors = (Map<String, Object>) exp.get("двата_пода");
      check(
          "МРЗ от 01.01.2026",
          new java.math.BigDecimal(String.valueOf(floors.get("MIN_WAGE_от_01_01")))
              .setScale(4)
              .toPlainString(),
          query(c, "SELECT param_value FROM " + T + " WHERE code = 'MIN_WAGE'").get(0));
      check(
          "МОД за самоосигуряващи се до 31.07.2026",
          new java.math.BigDecimal(String.valueOf(floors.get("MIN_INCOME_SOL_до_31_07")))
              .setScale(4)
              .toPlainString(),
          query(
                  c,
                  "SELECT param_value FROM "
                      + T
                      + " WHERE code = 'MIN_INCOME_SOL' AND valid_from = DATE '2026-01-01'")
              .get(0));
    }
  }

  @Test
  @DisplayName("БАЗА · ⚠️ двете данъчни ставки важат ЕДНОВРЕМЕННО — по доход, не по период")
  void theTwoIncomeTaxRatesOverlapInsteadOfSucceedingEachOther() throws Exception {
    try (Connection c = open()) {
      requireCatalogueImported(c);

      // ЗАЩО ОТДЕЛЕН ТЕСТ. Четири от кодовете в този каталог имат по ДВЕ версии, които се СМЕНЯТ на
      // 01.08.2026 — там втората отменя първата. Данъчните ставки изглеждат същото (два реда, един
      // каталог), а не са: чл. 42 ал. 4 ЗДДФЛ дава 10 на сто за обикновения доход и 1 на сто „за
      // доходите, придобити като морско лице“, и двете важат за ЕДИН И СЪЩ ден. Който ги свие в
      // един код с две версии по validFrom, ще получи еднозначно грешен отговор за всяка дата.
      List<String> rows =
          query(
              c,
              "SELECT code, param_value, valid_from::text, coalesce(valid_to::text, 'null') FROM "
                  + T
                  + " WHERE code IN ('INCOME_TAX_RATE', 'INCOME_TAX_RATE_SEAFARER') ORDER BY code");

      String want =
          "INCOME_TAX_RATE | 10.0000 | 2026-01-01 | null\n"
              + "INCOME_TAX_RATE_SEAFARER | 1.0000 | 2026-01-01 | null";
      if (!want.equals(String.join("\n", rows))) {
        fail(
            "манифест „двете данъчни ставки“ не е изпълнен.\n  очаква се : "
                + want
                + "\n  намерено  : "
                + String.join("\n", rows)
                + STALE);
      }

      // ⚠️ И ЧЕ НАИСТИНА СЕ ПРИПОКРИВАТ. Горната проверка би минала и ако някой ден датите се
      // разминат така, че двете да не важат едновременно — тогава редът за морско лице би бил
      // мъртъв, а тестът зелен.
      String overlapping =
          query(
                  c,
                  "SELECT count(*) FROM "
                      + T
                      + " a JOIN "
                      + T
                      + " b ON a.code = 'INCOME_TAX_RATE' "
                      + "AND b.code = 'INCOME_TAX_RATE_SEAFARER' "
                      + "WHERE a.valid_from <= coalesce(b.valid_to, DATE '9999-12-31') "
                      + "AND b.valid_from <= coalesce(a.valid_to, DATE '9999-12-31')")
              .get(0);
      if ("0".equals(overlapping)) {
        fail(
            "двете данъчни ставки НЕ се припокриват по период — при чл. 42 ал. 4 ЗДДФЛ те важат"
                + " едновременно и се различават по вида доход, а не по дата");
      }
    }
  }

  @Test
  @DisplayName("Манифестът е за този каталог")
  void theManifestIsTheRightOne() throws Exception {
    try (InputStream in = BgPayrollParameterManifestTest.class.getResourceAsStream(MANIFEST)) {
      Assumptions.assumeTrue(in != null);
      @SuppressWarnings("unchecked")
      Map<String, Object> m = new ObjectMapper(new YAMLFactory()).readValue(in, Map.class);
      assertEquals("Скаларни параметри за заплати, 2026 г.", m.get("издание"), "друг манифест");
    }
  }
}
