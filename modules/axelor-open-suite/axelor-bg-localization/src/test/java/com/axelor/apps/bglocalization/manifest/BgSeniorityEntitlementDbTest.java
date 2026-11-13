package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРАВОТО НА КЛАС ПРОСЛУЖЕНО ВРЕМЕ — базовата половина: „ДОСТАВЕНО ли е“.
 *
 * <p><b>Защо двете половини не са една.</b> {@code BgSeniorityEntitlementTest} пита дали оста е
 * ВЯРНА и вярно ЗАПИСАНА; той тече винаги и без Postgres. Този пита нещо, което файловете не могат
 * да знаят: стигнал ли е признакът до базата, тоест ще спре ли класът на ДУК на ЖИВА среда. Слети в
 * един тест, отговорът „App Install не е пускан“ би маскирал отговора „оста е сгрешена“.
 *
 * <p><b>Пропуска се при ТРИ състояния на средата, всяко с обявена причина</b>: няма достъпна база;
 * колоната още я няма ({@code ddl=update} не е минавал с новия домейн); колоната я има, но НИТО
 * ЕДИН ред не е отбелязан (App Install не е пускан след този комит).
 *
 * <p><b>⚠️ ТРЕТОТО СЪСТОЯНИЕ Е НОРМАЛНОТО ВЕДНАГА СЛЕД ТОЗИ КОМИТ И НЕ Е ДЕФЕКТ</b> — но НЕ по
 * същата причина като при {@code bgIsSeafarer}. Там до App Install средата запазва днешното си
 * поведение. ТУК Е ОБРАТНОТО И ТОВА Е ЗАПИСАНО, ЗА ДА НЕ ИЗНЕНАДА: до App Install всички редове
 * стоят {@code NULL}, генерираният getter връща {@code FALSE} и класът спира за ВСИЧКИ, включително
 * за наетите. Посоката е избрана: липсващият клас се вижда от първия фиш и се въвежда на ръка, а
 * неправомерно начисленият не се вижда никога. Деплойът затова носи App Install, а не само комит.
 *
 * <p>⚠️ Името на колоната НЕ се пише на ръка — извежда се от домейн XML-а през {@link
 * BgDomainColumns#columnName}, със същата стратегия, с която Hibernate я създава. Написано на ръка,
 * то мълчаливо се разминава при първата цифра в името на поле.
 */
class BgSeniorityEntitlementDbTest {

  private static final String TYPES = "bglocalization_bg_insured_type";
  private static final String FIELD = "bgEntitledToLengthOfServiceBonus";

  /** Наето лице по трудов договор — има право. */
  private static final String EMPLOYEE = "01";

  /** Договор за управление и контрол — няма. */
  private static final String DUK = "10";

  /** Кооперативен член БЕЗ трудово правоотношение — носи ГВРС, но няма право. */
  private static final String COOP_NO_CONTRACT = "09";

  private static String column() {
    return BgDomainColumns.columnName(FIELD);
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
              + " — твърдението „класът спира на ДУК на жива среда“ НЕ е проверено. Файловата"
              + " половина (BgSeniorityEntitlementTest) тече и без база.");
      return null;
    }
  }

  private static String one(Connection c, String sql) throws Exception {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static void requireDeliveredSeed(Connection c) throws Exception {
    String present =
        one(
            c,
            "SELECT count(*) FROM information_schema.columns WHERE table_name = '"
                + TYPES
                + "' AND column_name = '"
                + column()
                + "'");
    if ("0".equals(present)) {
      BgSkip.because(
          "колоната "
              + TYPES
              + "."
              + column()
              + " още я НЯМА — ddl=update не е минавал с новия домейн. Състояние на средата, не на"
              + " кода.");
    }

    String marked = one(c, "SELECT count(*) FROM " + TYPES + " WHERE " + column());
    if ("0".equals(marked)) {
      BgSkip.because(
          "колоната "
              + TYPES
              + "."
              + column()
              + " я има, но НИТО ЕДИН ред не е отбелязан — App Install не е пускан след този комит."
              + " Източникът е bg_insured_type.csv, а заварените редове носят NULL, което се чете"
              + " като „няма право“. Пусни App Install.");
    }
  }

  /**
   * ⚠️ Заявката пита {@code WHERE <колона>}, а НИКОГА {@code = false}: до App Install редовете
   * стоят NULL, а NULL не е TRUE и не е FALSE — второто би пропуснало всички.
   */
  private static boolean entitled(Connection c, String code) throws Exception {
    String v =
        one(
            c,
            "SELECT coalesce("
                + column()
                + ", false)::text FROM "
                + TYPES
                + " WHERE code = '"
                + code
                + "' AND valid_from <= DATE '2026-03-01'"
                + " AND (valid_to IS NULL OR valid_to >= DATE '2026-03-01') LIMIT 1");
    assertNotNull(v, "видът осигурен " + code + " няма ред, валиден за 01.03.2026 г., в " + TYPES);
    // ⚠️ ::text, не getString на boolean: JDBC дава „t“/„f“ на boolean колона, не „true“/„false“.
    return "true".equals(v);
  }

  @Test
  @DisplayName("БАЗА · наетият (01) носи правото, ДУК (10) и кооп. член без ТПО (09) — не")
  void theLiveDictionaryCarriesTheAxis() throws Exception {
    try (Connection c = open()) {
      requireDeliveredSeed(c);

      assertEquals(true, entitled(c, EMPLOYEE), "вид осигурен 01 загуби правото на клас");
      assertEquals(false, entitled(c, DUK), "вид осигурен 10 (ДУК) получи право на клас");
      assertEquals(
          false,
          entitled(c, COOP_NO_CONTRACT),
          "вид осигурен 09 получи право на клас — той НОСИ ред за фонд ГВРС и точно затова е"
              + " критичният тест на оста: ГВРС не е трудово правоотношение");
    }
  }
}
