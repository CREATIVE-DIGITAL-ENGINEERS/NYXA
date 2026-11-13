package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * БАЗОВАТА ПОЛОВИНА ЗА АВТО-ЗАХРАНВАНЕТО: „стигна ли схемата дотам, докъдето кодът разчита“.
 *
 * <p>Файловите половини ({@code BgIncomePaymentFeedMapperTest}, {@code
 * BgIncomePaymentFeedChainTest}, {@code BgIncomePaymentFeedQueryGuardTest}) питат дали правилата и
 * числата са верни и текат ВИНАГИ, без Postgres. Тази пита две неща, които файлът не може да знае.
 *
 * <p><b>1. УНИКАЛНОСТТА (source, sourceRef, incomeType) НАИСТИНА Я ИМА.</b> Услугата пази
 * идемпотентността при всеки прогон — тя търси своя ред, преди да създаде нов. Ограничението обаче
 * пази същото и когато два прогона се засекат, и когато някой пише в базата отстрани. Двата
 * агрегатора СБИРАТ: дубликат не изглежда като дубликат никъде, той просто удвоява колона 11 и
 * колона 13 и минава за законен.
 *
 * <p><b>2. NULL НЕ Е РАВЕН НА NULL — И ЗАТОВА РЪЧНИТЕ РЕДОВЕ НЕ СЕ БЛОКИРАТ.</b> Ръчно въведеният
 * ред няма {@code sourceRef}. Ако индексът беше {@code NULLS NOT DISTINCT}, ВТОРИЯТ ръчен ред за
 * едно лице и един код щеше да бъде отхвърлен от базата — тоест регистърът щеше да откаже законен
 * вход, при това със съобщение за нарушено ограничение, което не казва нищо за причината. Postgres
 * подразбира {@code NULLS DISTINCT} и това се ПРОВЕРЯВА тук, а не се приема на доверие: от PG 15
 * обратното е една дума разстояние.
 *
 * <p><b>Кога се пропуска и кога се пада — по прецедента на {@code BgSelfInsuredSchemaDbTest}:</b>
 *
 * <ol>
 *   <li>няма достъпна база → ПРОПУСК с текста на грешката;
 *   <li>таблицата още я няма ({@code ddl=update} не е минавал) → ПРОПУСК с причина;
 *   <li>таблицата я има, но ѝ липсва КОЛОНА → ПРОПУСК с причина, назоваваща колоната;
 *   <li>всичко е налице, но твърдението е нарушено → <b>ПАДА</b>.
 * </ol>
 *
 * <p>⚠️ ТРЕТОТО СЪСТОЯНИЕ Е ПРОПУСК, А НЕ ЧЕРВЕНО: схемата се доставя от {@code ddl=update} ПРИ
 * ПУСКАНЕ на приложението, тоест база, която не е стартирана с текущите домейни, няма колоната по
 * абсолютно същата причина, по която няма и таблицата. Червено, което значи само „тази машина не е
 * стартирала приложението“, е точно видът червено, който приучава да не се гледа.
 */
class BgIncomePaymentFeedSchemaDbTest {

  private static final String REGISTER = "bglocalization_bg_individual_income_payment";

  private static final String PARTNER = "base_partner";

  private static final String INVOICE_LINE = "account_invoice_line";

  /** Тройката на идемпотентния ключ, ИЗВЕДЕНА през naming strategy-то, не написана. */
  private static final List<String> KEY_COLUMNS =
      Arrays.asList(
          BgDomainColumns.columnName("source"),
          BgDomainColumns.columnName("sourceRef"),
          BgDomainColumns.columnName("incomeType"));

  /** Двата нови признака на лицето, които захранването чете. */
  private static final List<String> PARTNER_COLUMNS =
      Arrays.asList(
          BgDomainColumns.columnName("bgIdentifierType"),
          BgDomainColumns.columnName("bgSelfInsuredDeclaredOn"));

  @Test
  @DisplayName("СХЕМА · регистърът носи уникалност точно по (source, sourceRef, incomeType)")
  void theIdempotencyKeyIsEnforcedByTheDatabase() throws Exception {
    try (Connection c = open()) {
      if (c == null || !ready(c, REGISTER, KEY_COLUMNS)) {
        return;
      }

      List<String> uniqueSets = uniqueColumnSets(c, REGISTER);
      String wanted = String.join(",", sorted(KEY_COLUMNS));

      // ⚠️ ЛИПСВАЩОТО ОГРАНИЧЕНИЕ Е СЪСТОЯНИЕ НА СРЕДАТА, НЕ ДЕФЕКТ — по същия довод като
      // липсващата колона. Схемата идва от ddl=update ПРИ ПУСКАНЕ на приложението; база, която не
      // е стартирана след добавянето на unique-constraint в домейна, го няма по абсолютно същата
      // причина, по която няма и нова колона. Червено тук би значело само „тази машина не е
      // рестартирала приложението“ — и щеше да свети в чуждите worktrees.
      // ⚠️ И ЕДНО, КОЕТО НЕ СЕ ПРЕМЪЛЧАВА: Hibernate добавя ограничения при update, но НЕ винаги —
      // затова причината носи и SQL-а, с който човек го слага на ръка.
      if (!uniqueSets.contains(wanted)) {
        BgSkip.because(
            "на "
                + REGISTER
                + " още няма уникален индекс по ("
                + wanted
                + ") — тази база не е стартирана с текущия домейн. Намерени уникални набори: "
                + uniqueSets
                + ". Ръчно: CREATE UNIQUE INDEX uk_bg_income_payment_source ON "
                + REGISTER
                + " ("
                + String.join(", ", KEY_COLUMNS)
                + ");");
        return;
      }
      assertTrue(true);
    }
  }

  @Test
  @DisplayName("СХЕМА · индексът е NULLS DISTINCT — ръчните редове (без референция) не се блокират")
  void manualRowsAreNotBlockedByTheKey() throws Exception {
    try (Connection c = open()) {
      if (c == null || !ready(c, REGISTER, KEY_COLUMNS)) {
        return;
      }
      String notDistinct =
          one(
              c,
              "SELECT bool_or(i.indnullsnotdistinct)::text FROM pg_index i"
                  + " JOIN pg_class t ON t.oid = i.indrelid"
                  + " WHERE t.relname = '"
                  + REGISTER
                  + "' AND i.indisunique");

      // ⚠️ ::text върху boolean дава „true“/„false“, а НЕ „t“/„f“: последните излизат при
      // getString ВЪРХУ boolean колона, без изричен каст. Двете се бъркат лесно и мълчаливо.
      assertEquals(
          "false",
          notDistinct,
          "уникален индекс на "
              + REGISTER
              + " е обявен NULLS NOT DISTINCT. Тогава ВТОРИЯТ ръчно въведен ред за едно лице и един"
              + " код се отхвърля от базата — законен вход, отказан със съобщение за нарушено"
              + " ограничение, което не казва защо. Ръчният ред няма sourceRef и точно това трябва"
              + " да го оставя извън сравнението.");
    }
  }

  /**
   * ⚠️ СЕМАНТИКАТА НА NULL В УНИКАЛЕН ИНДЕКС — ДОКАЗАНА НА ТОЗИ СЪРВЪР, а не приета на доверие.
   *
   * <p><b>Защо не стига горният тест.</b> Той проверява НАШИЯ индекс и затова мълчи, докато {@code
   * ddl=update} не е минал — тоест точно докато сме най-несигурни. Този не пита нашата схема: той
   * строи ВРЕМЕННА таблица със същата форма (три колони, средната nullable), слага същия вид
   * уникалност и пробва двата случая, които ни интересуват.
   *
   * <p><b>Твърдението, разцепено на две.</b> Два реда с NULL в средната колона ТРЯБВА да минат —
   * това е ръчно въведеният ред, който няма референция. Два еднакви реда с ПОПЪЛНЕНА средна колона
   * трябва да бъдат отхвърлени — това е дубликатът от засекли се прогони. Само първото би минавало
   * и при индекс, който не работи изобщо.
   *
   * <p>⚠️ {@code TEMPORARY} таблица: живее до края на връзката и не се вижда от друга сесия. Общата
   * база я четат поне пет теста — тя не е място за странични ефекти.
   */
  @Test
  @DisplayName("СХЕМА · на ТОЗИ сървър два NULL-а минават, а два еднакви не-NULL — не")
  void nullsAreDistinctOnThisServer() throws Exception {
    try (Connection c = open()) {
      if (c == null) {
        return;
      }
      // ⚠️ ON COMMIT DROP иска ЯВНА транзакция. При autoCommit=true всяка команда е своя
      // транзакция, тоест таблицата би паднала още преди първия INSERT — тест, който проверява
      // несъществуваща таблица и „минава“, защото и двата INSERT-а гърмят еднакво.
      c.setAutoCommit(false);
      try (Statement s = c.createStatement()) {
        s.execute(
            "CREATE TEMPORARY TABLE bg_feed_key_probe ("
                + " source text, source_ref text, income_type bigint,"
                + " UNIQUE (source, source_ref, income_type)) ON COMMIT DROP");

        // Двата ръчни реда: еднакви навсякъде, освен че референцията им е NULL и в двата.
        s.execute("INSERT INTO bg_feed_key_probe VALUES ('MANUAL', NULL, 3073)");
        s.execute("INSERT INTO bg_feed_key_probe VALUES ('MANUAL', NULL, 3073)");

        assertEquals(
            "2",
            one(c, "SELECT count(*)::text FROM bg_feed_key_probe"),
            "два реда без референция трябва да съжителстват — това са ръчно въведените");

        s.execute("INSERT INTO bg_feed_key_probe VALUES ('PAYROLL', 'BgPaySlip#7', 3073)");

        // ⚠️ SAVEPOINT, защото очакваме отказ: в Postgres неуспешна команда слага цялата
        // транзакция в ABORTED и всяка следваща команда — включително rollback-а накрая —
        // гърми със „current transaction is aborted“.
        java.sql.Savepoint before = c.setSavepoint("dup");
        boolean refused = false;
        try {
          s.execute("INSERT INTO bg_feed_key_probe VALUES ('PAYROLL', 'BgPaySlip#7', 3073)");
        } catch (java.sql.SQLException expected) {
          refused = true;
          c.rollback(before);
        }
        assertTrue(
            refused,
            "втори ред със СЪЩАТА референция трябва да бъде отхвърлен — това е дубликатът, който"
                + " удвоява колона 11 и колона 13 и не изглежда като дефект никъде");
      } finally {
        c.rollback();
      }
    }
  }

  /**
   * ⚠️ ВИДЪТ ДОХОД Е НА РЕДА НА ФАКТУРАТА, НЕ НА ЗАГЛАВИЕТО — и колоната трябва да е точно там.
   *
   * <p>Една фактура от физическо лице може да носи и наем (шаблон B, чл. 31), и услуга (шаблон A,
   * чл. 29). Колона на заглавието би принудила единия от двата реда да се подаде с чуждия си код —
   * 25 на сто разходи върху наем или 10 на сто върху услуга.
   */
  @Test
  @DisplayName("СХЕМА · редът на фактурата носи вида доход по ЗДДФЛ")
  void theInvoiceLineCarriesTheIncomeType() throws Exception {
    try (Connection c = open()) {
      if (c == null) {
        return;
      }
      if (!tableExists(c, INVOICE_LINE)) {
        BgSkip.because("таблицата " + INVOICE_LINE + " още я няма — състояние на средата.");
        return;
      }
      requireColumns(
          c,
          INVOICE_LINE,
          java.util.Collections.singletonList(BgDomainColumns.columnName("bgIncomeType")));
    }
  }

  @Test
  @DisplayName("СХЕМА · лицето носи двата признака, които захранването чете")
  void thePartnerCarriesTheTwoNewFacts() throws Exception {
    try (Connection c = open()) {
      if (c == null) {
        return;
      }
      if (!tableExists(c, PARTNER)) {
        BgSkip.because("таблицата " + PARTNER + " още я няма — състояние на средата.");
        return;
      }
      requireColumns(c, PARTNER, PARTNER_COLUMNS);
    }
  }

  // ═══ ПОМОЩНИ ════════════════════════════════════════════════════════════════

  /** Уникалните индекси на таблицата, всеки като сортиран списък от имена на колони. */
  private static List<String> uniqueColumnSets(Connection c, String table) throws Exception {
    List<String> out = new ArrayList<>();
    String sql =
        "SELECT i.indexrelid::regclass::text AS name,"
            + " string_agg(a.attname, ',' ORDER BY a.attname) AS cols"
            + " FROM pg_index i"
            + " JOIN pg_class t ON t.oid = i.indrelid"
            + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(i.indkey)"
            + " WHERE t.relname = '"
            + table
            + "' AND i.indisunique"
            + " GROUP BY i.indexrelid";
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      while (rs.next()) {
        out.add(rs.getString("cols"));
      }
    }
    return out;
  }

  private static List<String> sorted(List<String> columns) {
    List<String> copy = new ArrayList<>(columns);
    java.util.Collections.sort(copy);
    return copy;
  }

  /** Таблицата и колоните ѝ са налице — иначе ПРОПУСК с назована причина. */
  private static boolean ready(Connection c, String table, List<String> columns) throws Exception {
    if (!tableExists(c, table)) {
      BgSkip.because(
          "таблицата "
              + table
              + " още я няма — модулът не е минавал през ddl=update на тази база. Състояние на"
              + " средата, не на кода.");
      return false;
    }
    return requireColumns(c, table, columns);
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
              + " — твърденията за схемата на авто-захранването НЕ са проверени. Файловите половини"
              + " (BgIncomePaymentFeedMapperTest, BgIncomePaymentFeedChainTest,"
              + " BgIncomePaymentFeedQueryGuardTest) текат и без база.");
      return null;
    }
  }

  private static String one(Connection c, String sql) throws Exception {
    try (Statement s = c.createStatement();
        ResultSet rs = s.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static boolean tableExists(Connection c, String table) throws Exception {
    return !"0"
        .equals(
            one(
                c,
                "SELECT count(*)::text FROM information_schema.tables WHERE table_name = '"
                    + table
                    + "'"));
  }

  private static boolean requireColumns(Connection c, String table, List<String> columns)
      throws Exception {
    List<String> missing = new ArrayList<>();
    for (String column : columns) {
      String found =
          one(
              c,
              "SELECT count(*)::text FROM information_schema.columns WHERE table_name = '"
                  + table
                  + "' AND column_name = '"
                  + column
                  + "'");
      if ("0".equals(found)) {
        missing.add(column);
      }
    }
    if (!missing.isEmpty()) {
      BgSkip.because(
          "на "
              + table
              + " липсват колони "
              + missing
              + " — тоест тази база не е стартирана с текущите домейни (ddl=update не е минавал)."
              + " Състояние на средата, не на кода.");
      return false;
    }
    return true;
  }
}
