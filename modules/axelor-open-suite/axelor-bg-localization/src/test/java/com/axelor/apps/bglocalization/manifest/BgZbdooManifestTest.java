package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Сверява ЗАРЕДЕНАТА БАЗА срещу манифеста на изданието.
 *
 * <p><b>Защо срещу базата, а не срещу CSV-тата.</b> App Install е upsert по {@code importId} — той
 * вкарва и обновява, но НЕ трие. Ред, махнат от файла, остава жив в базата: файлът казва едно,
 * изчислението намира друго. Проверка върху seed-а не би го хванала.
 *
 * <p><b>Защо отпечатък, а не брой редове.</b> Двете приложения на МОД имат по 744 суми и се
 * различават само по числата. Кое е заредено се познава единствено по минимума, максимума и
 * най-честата стойност.
 *
 * <p>Тестът се ПРОПУСКА, когато няма достъпна база — за да не оцветява в червено билд без среда.
 * Пуска се смислено след {@code restore-all} и App Install. Базата е тази на ПРИЛОЖЕНИЕТО и идва
 * само от {@code db.default.url} — виж {@link com.axelor.apps.bglocalization.testsupport.BgTestDb}.
 *
 * <p>Утре има ЗБДОО 2027 → втори файл до този и втори тест, а не промяна по тези. Тук няма общ
 * механизъм нарочно: един тест, който чете един файл.
 */
class BgZbdooManifestTest {

  private static final String MANIFEST = "/apps/init-data/manifest/zbdoo-2026.yml";

  private static Map<String, Object> manifest() throws Exception {
    try (InputStream in = BgZbdooManifestTest.class.getResourceAsStream(MANIFEST)) {
      Assumptions.assumeTrue(in != null, "манифестът " + MANIFEST + " не е в classpath-а");
      @SuppressWarnings("unchecked")
      Map<String, Object> m = new ObjectMapper(new YAMLFactory()).readValue(in, Map.class);
      return m;
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
              + " — заредените числа по ЗБДОО 2026 срещу манифеста НЕ е проверено.");
      return null;
    }
  }

  /** Един ред резултат като списък от низове, за да са съобщенията четими. */
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

  private static BigDecimal dec(String s) {
    return s == null ? null : new BigDecimal(s);
  }

  /**
   * Отпечатъкът на ЕДНО приложение на МОД: брой суми, минимум, максимум, най-честа стойност и колко
   * пъти се среща — все за редовете, които започват на {@code validFrom}.
   *
   * <p>Броят редове не различава двете приложения: и № 1, и № 1А имат по 86 реда и по 744 суми.
   * Различават се единствено по числата, и то точно тук се хваща сбъркано приложение.
   */
  private static void checkModPeriod(
      Connection c,
      String етикет,
      String validFrom,
      String суми,
      String мин,
      String макс,
      String найЧесто,
      String найЧестоБрой)
      throws Exception {
    String join =
        "FROM bglocalization_bg_min_insurance_income_amount a "
            + "JOIN bglocalization_bg_min_insurance_income p ON p.id = a.bg_min_insurance_income "
            + "WHERE p.valid_from = DATE '"
            + validFrom
            + "' ";
    check("МОД · " + етикет + " · суми", суми, one(c, "SELECT count(*) " + join));
    check(
        "МОД · " + етикет + " · мин",
        new BigDecimal(мин),
        dec(one(c, "SELECT min(amount) " + join)));
    check(
        "МОД · " + етикет + " · макс",
        new BigDecimal(макс),
        dec(one(c, "SELECT max(amount) " + join)));
    check(
        "МОД · " + етикет + " · най-честа стойност и брой",
        найЧесто + " | " + найЧестоБрой,
        one(
            c,
            "SELECT a.amount, count(*) "
                + join
                + "GROUP BY a.amount ORDER BY count(*) DESC LIMIT 1"));
  }

  @Test
  @DisplayName("Базата отговаря на манифеста на ЗБДОО 2026")
  void theDatabaseMatchesTheEditionManifest() throws Exception {
    Map<String, Object> man = manifest();
    assertEquals("ЗБДОО 2026", man.get("издание"), "друг манифест");

    try (Connection c = open()) {

      // ── МОД: отпечатък, не брой, и то за ВСЕКИ период поотделно ─────────
      // Двете приложения имат по 86 реда и по 744 суми. Сборът над цялата таблица не различава
      // нищо — включително не различава „заредени са и двете“ от „заредено е едно, два пъти“.
      check(
          "МОД · периоди",
          "2026-01-01 | 2026-07-31 | 86\n2026-08-01 | null | 86",
          String.join(
              "\n",
              query(
                  c,
                  "SELECT valid_from::text, valid_to::text, count(*) FROM "
                      + "bglocalization_bg_min_insurance_income GROUP BY 1,2 ORDER BY 1")));
      checkModPeriod(
          c, "№ 1А (01.08.2026 –)", "2026-08-01", "744", "620.20", "1532.00", "620.20", "246");
      checkModPeriod(
          c, "№ 1 (01.01 – 31.07.2026)", "2026-01-01", "744", "550.66", "901.41", "550.66", "728");
      // ⚠️ Приложение № 1 е ПЛОСКО: 728 от 744 стойности са прагът. Четирите числа отгоре не
      // различават плоска таблица от празна, затова се брои и отклонението от прага.
      check(
          "МОД · № 1 · стойности над прага и дейности, в които седят",
          "16 | 13",
          one(
              c,
              "SELECT count(*), count(DISTINCT p.bg_row) FROM "
                  + "bglocalization_bg_min_insurance_income_amount a "
                  + "JOIN bglocalization_bg_min_insurance_income p ON p.id = a.bg_min_insurance_income "
                  + "WHERE p.valid_from = DATE '2026-01-01' AND a.amount <> 550.66"));
      // Между двата периода няма нито застъпване, нито празнина.
      check(
          "МОД · долепени периоди",
          "0",
          one(
              c,
              "SELECT count(*) FROM bglocalization_bg_min_insurance_income a "
                  + "JOIN bglocalization_bg_min_insurance_income b ON b.bg_row = a.bg_row "
                  + "AND b.valid_from > a.valid_from "
                  + "WHERE a.valid_to IS NULL OR b.valid_from <> a.valid_to + 1"));

      // ── параметри с два периода ─────────────────────────────────────────
      check(
          "максимален осигурителен доход · два периода",
          "2026-01-01 | 2026-07-31 | 2111.6400\n2026-08-01 | null | 2300.0000",
          String.join(
              "\n",
              query(
                  c,
                  "SELECT valid_from::text, valid_to::text, param_value FROM "
                      + "bglocalization_bg_payroll_parameter WHERE code='MAX_INSURABLE_INCOME' "
                      + "ORDER BY valid_from")));
      check(
          "минимален за самоосигуряващи се · два периода",
          "2026-01-01 | 2026-07-31 | 550.6600\n2026-08-01 | null | 620.2000",
          String.join(
              "\n",
              query(
                  c,
                  "SELECT valid_from::text, valid_to::text, param_value FROM "
                      + "bglocalization_bg_payroll_parameter WHERE code='MIN_INCOME_SOL' "
                      + "ORDER BY valid_from")));

      // ── ТЗПБ ─────────────────────────────────────────────────────────────
      check(
          "ТЗПБ · редове общо", "174", one(c, "SELECT count(*) FROM bglocalization_bg_tzpb_rate"));
      check(
          "ТЗПБ · КИД кодове",
          "87",
          one(c, "SELECT count(DISTINCT kid_code) FROM bglocalization_bg_tzpb_rate"));
      check(
          "ТЗПБ · периоди",
          "2026-01-01 | 2026-07-31 | 87\n2026-08-01 | null | 87",
          String.join(
              "\n",
              query(
                  c,
                  "SELECT valid_from::text, valid_to::text, count(*) FROM "
                      + "bglocalization_bg_tzpb_rate GROUP BY 1,2 ORDER BY 1")));
      check(
          "ТЗПБ · седемте сменени ставки",
          "10 | 0.700 | 0.900\n19 | 0.900 | 1.100\n59 | 0.400 | 0.500\n71 | 0.700 | 0.500\n"
              + "82 | 0.400 | 0.500\n93 | 0.500 | 0.700\n99 | 0.500 | 0.400",
          String.join(
              "\n",
              query(
                  c,
                  "SELECT a.kid_code, a.rate, b.rate FROM bglocalization_bg_tzpb_rate a "
                      + "JOIN bglocalization_bg_tzpb_rate b ON b.kid_code=a.kid_code "
                      + "AND b.valid_from='2026-08-01' WHERE a.valid_from='2026-01-01' "
                      + "AND a.rate<>b.rate ORDER BY a.kid_code::int")));

      // ── ГВРС: нула за цялата 2026 ────────────────────────────────────────
      check(
          "ГВРС · ненулеви ставки (чл. 15 ал. 1 — вноски не се внасят)",
          "0",
          one(
              c,
              "SELECT count(*) FROM bglocalization_bg_insurance_contribution WHERE fund_code='GVRS' "
                  + "AND (employer_rate<>0 OR employee_rate<>0)"));

      // ── кодове 05 и 24 не започват преди 01.08.2026 ─────────────────────
      check(
          "кодове 05 и 24 · най-ранно начало без ТЗПБ (§ 6 т. 1 е в сила от 01.08.2026)",
          "2026-08-01",
          one(
              c,
              "SELECT min(valid_from)::text FROM bglocalization_bg_insurance_contribution "
                  + "WHERE bg_insured_type_code IN ('05','24') AND fund_code<>'TZPB'"));

      // ── обезщетението по чл. 106 ал. 2 ЗДСл: РАЗПРЕДЕЛЕНО, в два транша ──
      // ⚠️ НЕ „изцяло за сметка на платеца, с отворен период“ — такова разчитане е сгрешено:
      // КСО чл. 9 ал. 3 т. 5 казва за това обезщетение
      // само че вноските се внасят върху получаваното обезщетение и СЕ РАЗПРЕДЕЛЯТ по чл. 6 ал. 3
      // т. 8 и 9. Клаузата „за сметка на осигурителя/държавния бюджет“ е в т. 1-4 — вид осигурен
      // 18, не 27. За държавния служител разпределението е по чл. 6 ал. 5а, тоест същите два
      // транша като вид осигурен 05. Вж. manifest/zbdoo-2026.yml.
      check(
          "чл. 106 ал. 2 ЗДСл · редове",
          "6",
          one(
              c,
              "SELECT count(*) FROM bglocalization_bg_insurance_contribution "
                  + "WHERE bg_compensation_type_code='comp_zdsl_106_2'"));
      check(
          "чл. 106 ал. 2 ЗДСл · двата транша",
          "2026-08-01 | 2026-12-31 | 3\n2027-01-01 | null | 3",
          String.join(
              "\n",
              query(
                  c,
                  "SELECT valid_from::text, valid_to::text, count(*) FROM "
                      + "bglocalization_bg_insurance_contribution "
                      + "WHERE bg_compensation_type_code='comp_zdsl_106_2' "
                      + "GROUP BY 1,2 ORDER BY 1")));
      // ⚠️ РЕГРЕСИЯТА, ОТ КОЯТО СЕ ПАЗИ: нула при лицето значи „вноската е изцяло на
      // платеца“. Броят редове сам по себе си не я хваща — шест реда с нули при лицето биха
      // минали. Затова се пита ставката на ЛИЦЕТО, поименно, за първия транш.
      check(
          "чл. 106 ал. 2 ЗДСл · лична вноска при 01.08.2026 (вече НЕ е нула)",
          "PENSION | WITHOUT_UPF | 4.000\nPENSION | WITH_UPF | 3.000\nUPF | WITH_UPF | 1.000",
          String.join(
              "\n",
              query(
                  c,
                  "SELECT fund_code, bg_upf_regime, employee_rate FROM "
                      + "bglocalization_bg_insurance_contribution "
                      + "WHERE bg_compensation_type_code='comp_zdsl_106_2' "
                      + "AND valid_from = DATE '2026-08-01' ORDER BY fund_code, bg_upf_regime")));
      check(
          "чл. 106 ал. 2 ЗДСл · редове с нулева лична вноска",
          "0",
          one(
              c,
              "SELECT count(*) FROM bglocalization_bg_insurance_contribution "
                  + "WHERE bg_compensation_type_code='comp_zdsl_106_2' AND employee_rate = 0"));
    }
  }
}
