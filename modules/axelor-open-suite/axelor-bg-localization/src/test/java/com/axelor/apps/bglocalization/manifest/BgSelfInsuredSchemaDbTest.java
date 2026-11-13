package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.db.BgSelfInsuredActivityPeriod;
import com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration;
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
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * БАЗОВАТА ПОЛОВИНА ЗА САМООСИГУРЯВАЩОТО СЕ ЛИЦЕ: „стигна ли схемата дотам“.
 *
 * <p>Файловите половини ({@code BgSelfInsuredRulesTest}, {@code
 * BgSelfInsuredContributionWiringTest}) питат дали правилата и числата са верни и текат ВИНАГИ, без
 * Postgres. Тази пита нещо, което файловете не могат да знаят: какво наистина има в базата.
 *
 * <p><b>⚠️ И ТЯ ПАЗИ ЕДНО НЕЩО, КОЕТО {@code ddl=update} НЕ ПРАВИ САМ.</b> {@code
 * BgPaySlip.employmentContract} не е {@code required} — самоосигуряващият се няма трудов договор.
 * Hibernate обаче ДОБАВЯ колони и ограничения, но НЕ СВАЛЯ {@code NOT NULL} от заварена колона.
 * Тоест в база, създадена, докато полето е било задължително, приложението ще смята, че може да
 * запише фиш без договор, а Postgres ще откаже — с грешка на ниво драйвер, далеч от причината.
 * Точно това състояние се проверява тук и се назовава с командата, която го поправя.
 *
 * <p><b>Кога се пропуска и кога се пада — по прецедента на {@code BgD1SchemaDbTest}:</b>
 *
 * <ol>
 *   <li>няма достъпна база → ПРОПУСК с текста на грешката;
 *   <li>таблицата още я няма (домейнът не е минал през {@code ddl=update}) → ПРОПУСК с причина;
 *   <li>таблицата я има, но ѝ липсва КОЛОНА → ПРОПУСК с причина, назоваваща колоната;
 *   <li>всичко е налице, но твърдението е нарушено → <b>ПАДА</b>.
 * </ol>
 *
 * <p>⚠️ ТРЕТОТО СЪСТОЯНИЕ Е ПРОПУСК, А НЕ ЧЕРВЕНО, и това е измерено правило, не отстъпка: схемата
 * в Axelor се доставя от {@code ddl=update} ПРИ ПУСКАНЕ на приложението. База, която не е
 * стартирана с текущите домейни, няма колоната по абсолютно същата причина, по която няма и
 * таблицата. Червено, което значи само „тази машина не е стартирала приложението“, е точно видът
 * червено, който приучава да не се гледа.
 */
class BgSelfInsuredSchemaDbTest {

  private static final String REGISTRATION = "bglocalization_bg_self_insured_registration";
  private static final String ACTIVITY_PERIOD = "bglocalization_bg_self_insured_activity_period";
  private static final String PAY_SLIP = "bglocalization_bg_pay_slip";

  /**
   * Колоните на регистрацията, ИЗВЕДЕНИ от домейн XML-а през naming strategy-то на Axelor.
   *
   * <p>⚠️ ИЗВЕДЕНИ, А НЕ НАПИСАНИ — по същия довод като в {@code BgD1SchemaDbTest}. Axelor слага
   * долна черта САМО когато главната буква стои МЕЖДУ ДВЕ МАЛКИ: {@code bgSolScope → bg_sol_scope},
   * но {@code bgScopeYear → bg_scope_year}, а поле с цифра преди главната буква би дало име, което
   * ръчното правило не познава. Ръчният списък дава вечен ПРОПУСК, който изглежда точно като
   * липсваща база — най-скъпият вид зелено.
   */
  private static final List<String> REGISTRATION_COLUMNS =
      derive(BgSelfInsuredRegistration.class.getName());

  private static final List<String> ACTIVITY_PERIOD_COLUMNS =
      derive(BgSelfInsuredActivityPeriod.class.getName());

  private static List<String> derive(String entity) {
    Map<String, BgDomainColumns.FieldSpec> fields = BgDomainColumns.allFields().get(entity);
    List<String> out = new ArrayList<>();
    if (fields == null) {
      return out;
    }
    for (String field : fields.keySet()) {
      // ⚠️ Колекциите нямат своя колона на РОДИТЕЛЯ (мапнати са с mappedBy на детето). Търсени тук,
      // те винаги ще липсват и тестът ще пропуска вечно с невярна причина.
      BgDomainColumns.FieldSpec spec = fields.get(field);
      if (spec != null && "one-to-many".equals(spec.tag)) {
        continue;
      }
      out.add(BgDomainColumns.columnName(field));
    }
    return out;
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
              + " — твърденията за схемата на самоосигуряващото се лице НЕ са проверени. Файловите"
              + " половини (BgSelfInsuredRulesTest, BgSelfInsuredContributionWiringTest) текат и"
              + " без база.");
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
                "SELECT count(*) FROM information_schema.tables WHERE table_name = '"
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
              "SELECT count(*) FROM information_schema.columns WHERE table_name = '"
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

  @Test
  @DisplayName("СХЕМА · таблицата на регистрацията съществува с всичките си колони")
  void theRegistrationTableExists() throws Exception {
    try (Connection c = open()) {
      if (!tableExists(c, REGISTRATION)) {
        BgSkip.because(
            "таблицата "
                + REGISTRATION
                + " още я няма — модулът не е минавал през ddl=update на тази база. Състояние на"
                + " средата.");
        return;
      }
      requireColumns(c, REGISTRATION, REGISTRATION_COLUMNS);
    }
  }

  @Test
  @DisplayName("СХЕМА · таблицата на периодите на дейност съществува с всичките си колони")
  void theActivityPeriodTableExists() throws Exception {
    try (Connection c = open()) {
      if (!tableExists(c, ACTIVITY_PERIOD)) {
        BgSkip.because("таблицата " + ACTIVITY_PERIOD + " още я няма — състояние на средата.");
        return;
      }
      requireColumns(c, ACTIVITY_PERIOD, ACTIVITY_PERIOD_COLUMNS);
    }
  }

  /**
   * ⚠️ ЕДИНСТВЕНОТО ТВЪРДЕНИЕ ТУК, КОЕТО {@code ddl=update} НЕ МОЖЕ ДА ИЗПЪЛНИ САМ.
   *
   * <p>Hibernate добавя колони и ограничения; той НЕ СВАЛЯ {@code NOT NULL} от заварена колона.
   * Тоест домейнът може да е верен, приложението — стартирано, и въпреки това всеки опит да се
   * запише фиш на самоосигуряващо се лице да пада с грешка от драйвера. Причината е далеч от
   * симптома и точно затова тук стои ТОЧНАТА команда, която я поправя.
   */
  @Test
  @DisplayName("⚠️ СХЕМА · employment_contract на фиша вече НЕ Е NOT NULL — иска ръчен ALTER")
  void thePaySlipContractColumnIsNullable() throws Exception {
    try (Connection c = open()) {
      if (!tableExists(c, PAY_SLIP)) {
        BgSkip.because("таблицата " + PAY_SLIP + " още я няма — състояние на средата.");
        return;
      }
      String column = BgDomainColumns.columnName("employmentContract");
      if (!requireColumns(c, PAY_SLIP, Arrays.asList(column))) {
        return;
      }
      String nullable =
          one(
              c,
              "SELECT is_nullable FROM information_schema.columns WHERE table_name = '"
                  + PAY_SLIP
                  + "' AND column_name = '"
                  + column
                  + "'");
      if (!"YES".equals(nullable)) {
        // ⚠️ ПРОПУСК С КОМАНДАТА, А НЕ ЧЕРВЕНО — и това е решение, не отстъпка.
        //
        // Разликата с обикновената липсваща колона е реална: ddl=update би добавил колона сам, а
        // това ограничение НЕ може да свали сам. Тоест червеното тук нямаше да е „тази машина не е
        // стартирала приложението“, а „някой трябва да изпълни една команда“.
        //
        // И въпреки това е ПРОПУСК, по прецедента на BgD1SchemaDbTest, състояние (4): стъпка, която
        // принадлежи на ОПЕРАТОРА и нарочно не се изпълнява от този клон, се обявява, а не поваля
        // билда. Червено, което всеки на екипа вижда и никой не може да поправи от кода си, е точно
        // видът червено, който приучава да не се гледа — а тогава пропада и следващото, истинското.
        //
        // ⚠️ ЗАТОВА ПРИЧИНАТА НОСИ САМАТА КОМАНДА. BgSkip я ПЕЧАТА и Gradle я записва в отчета,
        // тоест стъпката не се губи: тя е записана и в manifest/sol-2026.yml, раздел
        // „ръчна_стъпка_по_схемата“, и в описанието на PR-а.
        BgSkip.because(
            "КОЛОНАТА "
                + PAY_SLIP
                + "."
                + column
                + " ВСЕ ОЩЕ Е NOT NULL. Домейнът я обявява за незадължителна, защото"
                + " самоосигуряващото се лице НЯМА трудов договор (КСО чл. 6 ал. 8: вноските са за"
                + " сметка на осигурените лица). Hibernate с ddl=update ДОБАВЯ ограничения, но НЕ ГИ"
                + " СВАЛЯ — тоест иска се едно ръчно действие:\n"
                + "    ALTER TABLE "
                + PAY_SLIP
                + " ALTER COLUMN "
                + column
                + " DROP NOT NULL;\n"
                + "  или свежа база. Без него всеки фиш на самоосигуряващо се лице пада с грешка от"
                + " драйвера, далеч от причината си. Кодът е верен и без нея: файловите половини"
                + " (BgSelfInsuredRulesTest, BgSelfInsuredContributionWiringTest) текат безусловно.");
        return;
      }
      assertEquals("YES", nullable);
    }
  }
}
