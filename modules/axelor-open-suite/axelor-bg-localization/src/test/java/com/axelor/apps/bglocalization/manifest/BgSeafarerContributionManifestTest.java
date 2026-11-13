package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.InputStream;
import java.math.BigDecimal;
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
 * БАЗОВАТА половина за морските лица: ДОСТАВЕНО ли е. Двойник на {@code BgSeafarerSeedFileTest}.
 *
 * <p><b>Разделението не е козметично.</b> Файловият тест пита „вярно ли е записано“ и тече винаги.
 * Този пита „стигна ли до базата“ и има точно един начин да отговори „не знам“: когато каталогът е
 * ПРАЗЕН, тоест App Install изобщо не е пускан. Тогава се пропуска с обявена причина, вместо да
 * оцветява билда в червено по повод, който няма нищо общо с кода.
 *
 * <p><b>⚠️ И ЕДИН НАЧИН ДА КАЖЕ „НЕ“.</b> Празна таблица и таблица с ГРЕШНИ редове НЕ са едно и
 * също. Първото е „не е импортирано“, второто е „импортирано е грешно“ — и ако пропускането не ги
 * различава, то ще гълта истинска повреда в данните и ще върне капан 9, само че този път със
 * зелено. Затова предикатът гледа броя редове в целия каталог, а не липсата на ТЪРСЕНИТЕ редове:
 * каталог с редове, но без нашите, е ПАДАНЕ.
 */
class BgSeafarerContributionManifestTest {

  private static final String MANIFEST = "/apps/init-data/manifest/seafarers-2026.yml";
  private static final String T = "bglocalization_bg_insurance_contribution";
  private static final String T_TYPES = "bglocalization_bg_insured_type";
  private static final String CODE = "92";

  private static final Map<String, String> DEFAULT_TOTAL = new LinkedHashMap<>();

  static {
    DEFAULT_TOTAL.put("1", "38.3000");
    DEFAULT_TOTAL.put("2", "33.3000");
    DEFAULT_TOTAL.put("3", "23.3000");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> manifest() throws Exception {
    try (InputStream in = BgSeafarerContributionManifestTest.class.getResourceAsStream(MANIFEST)) {
      Assumptions.assumeTrue(in != null, "манифестът " + MANIFEST + " не е в classpath-а");
      return new ObjectMapper(new YAMLFactory()).readValue(in, Map.class);
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
              + " (BgSeafarerSeedFileTest) тече и без база.");
      return null;
    }
  }

  /**
   * Пропуска САМО когато каталогът е празен за целия модул.
   *
   * <p>Празно значи „App Install не е пускан“ — състояние на средата, не на кода. Всичко останало,
   * включително „има редове, но не тези“, минава нататък и ПАДА: това е импортиран, но грешен или
   * остарял seed, а той трябва да се вижда.
   */
  private static void requireCatalogueImported(Connection c, String table) throws Exception {
    if ("0".equals(one(c, "SELECT count(*) FROM " + table))) {
      BgSkip.because(
          "таблицата "
              + table
              + " е ПРАЗНА — App Install не е пускан, това твърдение не е"
              + " проверено. Промяна в apps/init-data/ достига до базата само през uninstall →"
              + " restore-all → СВАЛЯНЕ на active и init_data_loaded → App Install (вдигането на"
              + " версията презарежда изгледи, не данни; uninstall НЕ сваля init_data_loaded,"
              + " затова то се сваля ръчно с psql).");
    }
  }

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

  private static BigDecimal dec(Object v) {
    return new BigDecimal(String.valueOf(v)).setScale(4);
  }

  private static String staleSeed(int found, int wanted) {
    return "\n  ⚠️ Каталогът НЕ е празен, тоест App Install е минавал — но не след тази промяна."
        + " Намерени "
        + found
        + " от "
        + wanted
        + " реда. Това е ОСТАРЯЛ или ГРЕШЕН импорт, не"
        + " липсващ: свали init_data_loaded и пусни App Install (uninstall НЕ сваля init_data_loaded, а без"
        + " свалянето install-ът връща status 0 за под секунда и не внася нищо) и провери пак."
        + "\n  ⚠️ Ако редовете пак са 0 СЛЕД истински импорт — виж лога на приложението за"
        + " „Unable to import record“: вносителят отхвърля ред по ред и продължава.";
  }

  // ─── Б1 · двайсет и трите реда ───────────────────────────────────────────────────────────────

  @Test
  @DisplayName("БАЗА · заредените редове за морско лице отговарят на манифеста")
  @SuppressWarnings("unchecked")
  void theLoadedRowsMatchTheManifest() throws Exception {
    Map<String, Object> expected = (Map<String, Object>) manifest().get("редове");
    List<Map<String, Object>> want = (List<Map<String, Object>>) expected.get("ставки");

    try (Connection c = open()) {
      requireCatalogueImported(c, T);

      int total =
          Integer.parseInt(
              one(c, "SELECT count(*) FROM " + T + " WHERE bg_insured_type_code = '" + CODE + "'"));
      if (total != want.size()) {
        fail(
            "редове за вид осигурен "
                + CODE
                + " в базата: "
                + total
                + ", а манифестът очаква "
                + want.size()
                + staleSeed(total, want.size()));
      }

      List<String> problems = new ArrayList<>();
      for (Map<String, Object> w : want) {
        String id = String.valueOf(w.get("importId"));
        // ⚠️ Мащабът се ИЗРАВНЯВА, а не се сравнява като текст. Колоната е numeric(6,3) — толкова
        // декларира и домейнът — тоест базата връща „19.800“, а манифестът пази „19.8000“, защото
        // CSV-то е с четири знака и файловата половина се сверява срещу него. Голото сравнение на
        // низове обявяваше всичките верни реда за разминати заради нула на края. Числото е едно и
        // също; сравнява се то, не изписването му.
        List<String> got =
            query(
                c,
                "SELECT labor_category, fund_code, bg_upf_regime, bg_sol_scope,"
                    + " employer_rate::numeric(10,4), employee_rate::numeric(10,4) FROM "
                    + T
                    + " WHERE import_id = '"
                    + id
                    + "'");
        if (got.isEmpty()) {
          problems.add(id + ": липсва в базата");
          continue;
        }
        String wanted =
            String.format(
                "%s | %s | %s | %s | %s | %s",
                w.get("кат"),
                w.get("фонд"),
                w.get("режим"),
                w.get("обхват"),
                dec(0).toPlainString(),
                dec(w.get("лице")).toPlainString());
        if (!wanted.equals(got.get(0))) {
          problems.add(id + ":\n      очаква се : " + wanted + "\n      намерено  : " + got.get(0));
        }
      }
      if (!problems.isEmpty()) {
        fail("заредените редове се разминават с манифеста:\n  " + String.join("\n  ", problems));
      }
    }
  }

  // ─── Б2 · подразбиращият се избор по чл. 4а ал. 2 ────────────────────────────────────────────

  @Test
  @DisplayName("БАЗА · ⚠️ морско лице БЕЗ записан избор получава 23,3 / 38,3 / 33,3")
  void aSeafarerWithoutAnElectionIsNotChargedForUnemployment() throws Exception {
    try (Connection c = open()) {
      requireCatalogueImported(c, T);

      // Повтаря филтъра на BgInsuranceCalculationService за заявка БЕЗ записан избор: solScope не е
      // ART4A_AL2, тоест редът за безработица не влиза. ТЗПБ се вади от сбора, защото справката на
      // НАП дава ДОО и ДЗПО — 19,8 + 3,5 = 23,3 точно, без остатък за него.
      // ⚠️ ЗОВ СЪЩО СЕ ВАДИ, И НЕ ЗАЩОТО ЛИЦЕТО НЕ ГО ПЛАЩА: здравната вноска е по ЗЗО чл. 40 ал. 1
      // т. 2а — друг закон и друга справка, тоест осемте на сто не са нито ДОО, нито ДЗПО. Трите
      // константи тук са сверени срещу справката за ДОО и ДЗПО и остават верни точно защото ЗОВ е
      // извън тях. Че и ЗОВ е изцяло за сметка на лицето, се проверява в Б1 — employer_rate = 0 на
      // всеки ред. Огледален на изключването във файловата половина (BgSeafarerSeedFileTest А2);
      // разминат ли се двата филтъра, същият сбор ще дава два различни отговора.
      List<String> wrong = new ArrayList<>();
      for (Map.Entry<String, String> e : DEFAULT_TOTAL.entrySet()) {
        for (String cohort : new String[] {"WITH_UPF", "WITHOUT_UPF"}) {
          String sum =
              one(
                  c,
                  "SELECT coalesce(sum(employee_rate), 0) FROM "
                      + T
                      + " WHERE bg_insured_type_code = '"
                      + CODE
                      + "'"
                      + " AND labor_category = '"
                      + e.getKey()
                      + "'"
                      + " AND bg_sol_scope = 'ANY'"
                      + " AND fund_code NOT IN ('TZPB', 'HEALTH')"
                      + " AND (bg_upf_regime = 'ANY' OR bg_upf_regime = '"
                      + cohort
                      + "')");
          if (new BigDecimal(sum).compareTo(new BigDecimal(e.getValue())) != 0) {
            wrong.add(
                "категория "
                    + e.getKey()
                    + ", кохорта "
                    + cohort
                    + ": "
                    + sum
                    + " вместо "
                    + e.getValue());
          }
        }
      }

      // И изрично: нито един ЗАДЪЛЖИТЕЛЕН фонд не бива да е излязъл от подразбирането, и обратното
      // —
      // безработицата не бива да е в него.
      List<String> inDefault =
          query(
              c,
              "SELECT fund_code FROM "
                  + T
                  + " WHERE bg_insured_type_code = '"
                  + CODE
                  + "' AND bg_sol_scope = 'ANY' AND fund_code = 'UNEMPLOYMENT'");
      if (!inDefault.isEmpty()) {
        wrong.add(
            "фонд Безработица е в подразбиращия се набор — чл. 4а ал. 2 КСО го прави ИЗБОР, а"
                + " неупражненият избор не поражда задължение");
      }

      if (!wrong.isEmpty()) {
        fail("подразбиращият се избор по чл. 4а ал. 2 е сгрешен:\n  " + String.join("\n  ", wrong));
      }
    }
  }

  // ─── Б3 · долният праг ───────────────────────────────────────────────────────────────────────

  @Test
  @DisplayName("БАЗА · морското лице има долен праг MIN_INCOME_SOL")
  void theSeafarerHasTheSelfInsuredFloor() throws Exception {
    try (Connection c = open()) {
      requireCatalogueImported(c, T_TYPES);

      List<String> got =
          query(
              c,
              "SELECT bg_applies_mod, bg_floor_type FROM "
                  + T_TYPES
                  + " WHERE code = '"
                  + CODE
                  + "'");
      if (got.isEmpty()) {
        fail(
            "вид осигурен "
                + CODE
                + " липсва в базата, а каталогът с видове осигурени НЕ е празен"
                + staleSeed(0, 1));
      }

      String expected = "f | MIN_INCOME_SOL";
      if (!expected.equals(got.get(0))) {
        fail(
            "вид осигурен "
                + CODE
                + " (bgAppliesMod | bgFloorType).\n  очаква се : "
                + expected
                + "\n  намерено  : "
                + got.get(0)
                + "\n  ⚠️ NONE значи БЕЗ никакъв долен праг: морско лице може да се осигури под"
                + " 550,66 (съотв. 620,20) евро, без никой да се обади. Чл. 4а ал. 1 КСО иска доход"
                + " МЕЖДУ минималния и максималния за самоосигуряващите се."
                + (got.get(0).endsWith("NONE") ? staleSeed(0, 1) : ""));
      }
    }
  }

  @Test
  @DisplayName("Манифестът е за този каталог")
  void theManifestIsTheRightOne() throws Exception {
    Map<String, Object> m = manifest();
    if (!"Морски лица (КСО чл. 4а), 2026 г.".equals(m.get("издание"))) {
      fail("друг манифест: " + m.get("издание"));
    }
  }
}
