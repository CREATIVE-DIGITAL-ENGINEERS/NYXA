package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.service.payroll.BgPayrollInputBuilder;
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
 * ВХОДНИЯТ СЛОЙ — базовата половина: „построен ли е ДОМЪТ на всеки нов източник“.
 *
 * <p><b>Защо двете половини не са една.</b> {@code BgPayrollInputBuilderTest} пита дали сглобителят
 * ЧЕТЕ вярно и тече без Postgres — шевовете му са подменени. Точно затова той НЕ може да види
 * единственото, което остава: дали колоната, която заявката назовава, изобщо съществува. Заявка
 * срещу липсваща колона е зелена във файловата половина и пада в лицето на оператора.
 *
 * <p><b>⚠️ КАКВО ТОЗИ ТЕСТ НЕ ПРАВИ.</b> Не кара {@code BgPayrollInputBuilder} през JPA. Шевовете
 * му минават през {@code JPA.em()}, а той иска Guice и Hibernate — среда, която нито един {@code
 * *DbTest} в модула не вдига. Затова сверката е за СХЕМАТА и за СЪХРАНЕНИТЕ ДАННИ: имената на
 * колоните се четат от базата, а стойността, по която сглобителят филтрира, се чете от самия
 * сглобител ({@link BgPayrollInputBuilder#SICK_LEAVE_VALIDATED}), а не се преписва тук. Преписана,
 * тя щеше да остане зелена и след като сглобителят смени филтъра си.
 *
 * <p><b>Пропуска се с ОБЯВЕНА причина</b> при две състояния на средата: няма достъпна база, или
 * миграциите не са пускани. ⚠️ ПРАЗНА таблица НЕ е повод за пропускане — нула болнични е валидно
 * състояние и твърденията остават верни за него.
 */
class BgPayrollInputSourcesDbTest {

  private static final String SICK = "bglocalization_bg_sick_leave";
  private static final String EMPLOYEE = "hr_employee";
  private static final String LEAVE_REASON = "hr_leave_reason";
  private static final String EXTRA_HOURS_TYPE = "hr_extra_hours_type";
  private static final String DECLARED = "bglocalization_bg_declared_work_hours";

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
              + " — твърденията за ДОМА на новите източници НЕ са проверени. Файловата половина"
              + " (BgPayrollInputBuilderTest) тече и без база.");
      return null;
    }
  }

  private static void requireTable(Connection c, String table, String migration) throws Exception {
    if ("0"
        .equals(
            one(
                c,
                "SELECT count(*) FROM information_schema.tables WHERE table_name = '"
                    + table
                    + "'"))) {
      BgSkip.because(
          "таблицата "
              + table
              + " още я НЯМА — тоест нито db/migration/"
              + migration
              + " е пускан, нито ddl=update е минавал с новия домейн. Това е състояние на средата,"
              + " не на кода: домът още не е построен.");
    }
  }

  private static void requireColumn(Connection c, String table, String column, String migration)
      throws Exception {
    if ("0"
        .equals(
            one(
                c,
                "SELECT count(*) FROM information_schema.columns WHERE table_name = '"
                    + table
                    + "' AND column_name = '"
                    + column
                    + "'"))) {
      BgSkip.because(
          "колоната "
              + table
              + "."
              + column
              + " още я НЯМА — нито db/migration/"
              + migration
              + " е пускан, нито ddl=update е минавал.");
    }
  }

  // ═══ БОЛНИЧНИЯТ ЕПИЗОД ════════════════════════════════════════════════════

  @Test
  @DisplayName("БАЗА · таблицата на болничния носи ВСЯКА колона, която заявката на сглобителя иска")
  void theSickLeaveTableCarriesEveryColumnTheAssemblerQueries() throws Exception {
    try (Connection c = open()) {
      requireTable(c, SICK, "2026-08-24-bg-sick-leave.sql");

      // Имената са тези от JPQL-а на BgPayrollInputBuilder#sickLeaveEpisodesOf, преведени по
      // правилото на Axelor (camelCase → snake_case). Липсва ли едно, заявката пада при първия
      // истински фиш — а файловата половина си остава зелена.
      List<String> missing = new ArrayList<>();
      for (String column :
          new String[] {
            "employment_contract",
            "bg_absence_type",
            "from_date",
            "to_date",
            "bg_is_continuation",
            "bg_continuation_of",
            "bg_document_number",
            "status_select"
          }) {
        if ("0"
            .equals(
                one(
                    c,
                    "SELECT count(*) FROM information_schema.columns WHERE table_name = '"
                        + SICK
                        + "' AND column_name = '"
                        + column
                        + "'"))) {
          missing.add(column);
        }
      }
      assertEquals(
          new ArrayList<String>(),
          missing,
          "заявката на сглобителя назовава колони, които таблицата НЕ носи — тоест всеки фиш с"
              + " болничен ще падне, а файловата половина няма как да го види");
    }
  }

  @Test
  @DisplayName("БАЗА · съхранените състояния са само от селекцията — и валидираното е измежду тях")
  void everyStoredStatusIsFromTheSelection() throws Exception {
    try (Connection c = open()) {
      requireTable(c, SICK, "2026-08-24-bg-sick-leave.sql");

      List<String> unknown =
          query(
              c,
              "SELECT DISTINCT status_select FROM "
                  + SICK
                  + " WHERE status_select NOT IN ('DRAFT', 'VALIDATED', 'CANCELLED')");
      assertEquals(
          new ArrayList<String>(),
          unknown,
          "съхранено състояние извън bg.sick.leave.status.select — филтърът на сглобителя не го"
              + " познава и записът МЪЛЧАЛИВО няма да влезе в нито един фиш");

      // ⚠️ Стойността се чете ОТ СГЛОБИТЕЛЯ, не се преписва тук: преписана, тя щеше да остане
      // зелена и след като той смени филтъра си.
      assertEquals(
          "VALIDATED",
          BgPayrollInputBuilder.SICK_LEAVE_VALIDATED,
          "сглобителят филтрира по състояние, което селекцията не предлага — тогава НИТО ЕДИН"
              + " болничен не може да влезе във фиш, и то без нито едно съобщение");
    }
  }

  @Test
  @DisplayName("БАЗА · всеки съхранен болничен сочи ДЕЙСТВИТЕЛЕН вид отсъствие с код")
  void everyStoredEpisodePointsAtARealAbsenceType() throws Exception {
    try (Connection c = open()) {
      requireTable(c, SICK, "2026-08-24-bg-sick-leave.sql");

      // Сглобителят чете s.bgAbsenceType.code и го подава като absenceTypeCode; после
      // BgSickLeaveService#episodesOf го разрешава ОБРАТНО срещу речника. Ред без код прекъсва
      // точно тази верига — и то тихо: type == null дава платец null, тоест „не плаща никой“.
      List<String> broken =
          query(
              c,
              "SELECT s.id FROM "
                  + SICK
                  + " s LEFT JOIN bglocalization_bg_absence_type t ON t.id = s.bg_absence_type"
                  + " WHERE t.id IS NULL OR t.code IS NULL OR btrim(t.code) = ''"
                  + " ORDER BY s.id");
      if (!broken.isEmpty()) {
        fail(
            "болничен епизод без ЧЕТИМ код на вида ("
                + broken.size()
                + " ред(а): "
                + broken
                + ").\n  ⚠️ Кодът е единственото, което пътува до BgSickLeaveService — без него"
                + " платецът и процентът излизат null, тоест епизодът мълчаливо не струва нищо.");
      }
    }
  }

  @Test
  @DisplayName("БАЗА · нито един епизод не свършва преди да е започнал")
  void noStoredEpisodeEndsBeforeItStarts() throws Exception {
    try (Connection c = open()) {
      requireTable(c, SICK, "2026-08-24-bg-sick-leave.sql");

      // Обърнат интервал минава през филтъра за припокриване по НЕОЧАКВАН начин и дава отрицателен
      // брой дни надолу по веригата. Схемата не може да го спре, затова се сверява тук.
      List<String> inverted =
          query(c, "SELECT id FROM " + SICK + " WHERE to_date < from_date ORDER BY id");
      assertEquals(
          new ArrayList<String>(),
          inverted,
          "епизод с край преди началото — броят дни по чл. 40 ал. 5 КСО излиза отрицателен");
    }
  }

  // ═══ ОСИГУРИТЕЛНИЯТ СТАЖ ══════════════════════════════════════════════════

  @Test
  @DisplayName("БАЗА · домът на осигурителния стаж е построен — стойност И флаг")
  void theInsuranceServiceHomeExists() throws Exception {
    try (Connection c = open()) {
      requireColumn(
          c, EMPLOYEE, "bg_insurance_service_months", "2026-08-24-bg-insurance-service-months.sql");
      requireColumn(
          c,
          EMPLOYEE,
          "bg_insurance_service_declared",
          "2026-08-24-bg-insurance-service-months.sql");
    }
  }

  @Test
  @DisplayName(
      "⚠️ БАЗА · попълнен стаж без вдигнат флаг е НЕДОСТИЖИМ — сглобителят няма да го чете")
  void aFilledInServiceWithoutTheFlagIsUnreachable() throws Exception {
    try (Connection c = open()) {
      requireColumn(
          c, EMPLOYEE, "bg_insurance_service_months", "2026-08-24-bg-insurance-service-months.sql");

      // ⚠️ ::text НАРОЧНО. JDBC връща boolean като „t“/„f“, а тестът, който ЧЕТЕ стойността (а не
      // пита с WHERE), се спъва в това — виж jdbc-boolean-getstring-returns-t-f.
      List<String> orphans =
          query(
              c,
              "SELECT id, bg_insurance_service_months, coalesce(bg_insurance_service_declared,"
                  + " false)::text FROM "
                  + EMPLOYEE
                  + " WHERE bg_insurance_service_months IS NOT NULL"
                  + " AND bg_insurance_service_months <> 0"
                  + " AND coalesce(bg_insurance_service_declared, false) = false"
                  + " ORDER BY id");
      if (!orphans.isEmpty()) {
        fail(
            "служител с попълнен осигурителен стаж и СВАЛЕН флаг ("
                + orphans.size()
                + "): "
                + orphans
                + "\n\n  ⚠️ Сглобителят чете стойността САМО когато флагът я обявява за"
                + " декларирана — тоест този стаж е въведен и НЕ участва никъде. Числото стои на"
                + " екрана и не влиза в нито едно изчисление, което е по-лошо от празно поле."
                + "\n  Поправка: вдигнете флага или изчистете стойността.");
      }
    }
  }

  // ═══ СЪПОСТАВЯНЕТО НА ОТПУСКИТЕ ══════════════════════════════════════════

  @Test
  @DisplayName("БАЗА · домът на съпоставянето „вид отпуск → вид отсъствие“ е построен")
  void theLeaveMappingHomeExists() throws Exception {
    try (Connection c = open()) {
      requireColumn(
          c, LEAVE_REASON, "bg_absence_type", "2026-08-24-bg-leave-reason-absence-type.sql");
      requireColumn(
          c,
          LEAVE_REASON,
          "bg_absence_type_defaulted",
          "2026-08-24-bg-leave-reason-absence-type.sql");
    }
  }

  @Test
  @DisplayName("⚠️ БАЗА · РЕШЕНИЯ ЗА ЧОВЕК: кои видове отпуск още чакат съпоставяне")
  void theUnmappedLeaveReasonsAreNamed() throws Exception {
    try (Connection c = open()) {
      requireColumn(
          c, LEAVE_REASON, "bg_absence_type", "2026-08-24-bg-leave-reason-absence-type.sql");

      // ⚠️ ТОЗИ ТЕСТ НЕ ПАДА ЗА НЕСЪПОСТАВЕН ВИД, И ТОВА Е РЕШЕНИЕ. Съпоставянето зависи от това
      // КАК КЛИЕНТЪТ е кръстил видовете си отпуск — то е операционен избор, не свойство на кода, и
      // няма как да бъде „поправено“ от този клон. Червено тук би значело „кодът е грешен“, а
      // верният прочит е „човек още не е решил“.
      //
      // ⚠️ Затова тестът ИЗБРОЯВА, вместо да пада: несъпоставеният вид не е дефект, но е ПРИЧИНА
      // сглобителят да откаже, и мълчанието за него е точно това, което го прави невидим до
      // първия паднал фиш.
      List<String> unmapped =
          query(
              c,
              "SELECT id, name FROM "
                  + LEAVE_REASON
                  + " WHERE bg_absence_type IS NULL ORDER BY id");
      if (!unmapped.isEmpty()) {
        System.out.println(
            "ℹ РЕШЕНИЯ ЗА ЧОВЕК — видове отпуск БЕЗ съпоставяне към BgAbsenceType ("
                + unmapped.size()
                + "):\n  "
                + String.join("\n  ", unmapped)
                + "\n  Валидирана заявка по някой от тях кара BgPayrollInputBuilder да ОТКАЖЕ и"
                + " трите кофи (paid/unpaid/otherAbsenceDays).");
      }

      // ⚠️ ПАДА обаче за съпоставяне, което сочи В ПРАЗНОТО: това вече Е дефект в данните.
      List<String> broken =
          query(
              c,
              "SELECT r.id, r.name FROM "
                  + LEAVE_REASON
                  + " r LEFT JOIN bglocalization_bg_absence_type t ON t.id = r.bg_absence_type"
                  + " WHERE r.bg_absence_type IS NOT NULL AND t.id IS NULL ORDER BY r.id");
      assertEquals(
          new ArrayList<String>(),
          broken,
          "вид отпуск, съпоставен към НЕСЪЩЕСТВУВАЩ вид отсъствие — съпоставянето изглежда"
              + " попълнено и не води до никъде");
    }
  }

  @Test
  @DisplayName("⚠️ БАЗА · РЕШЕНИЯ ЗА ТРЗ: кои съпоставяния са ПОДРАЗБИРАНИ и чакат потвърждение")
  void theDefaultedMappingsAreNamed() throws Exception {
    try (Connection c = open()) {
      requireColumn(
          c,
          LEAVE_REASON,
          "bg_absence_type_defaulted",
          "2026-08-24-bg-leave-reason-absence-type.sql");

      // ⚠️ ::text НАРОЧНО — JDBC връща boolean като „t“/„f“, а тук стойността се ЧЕТЕ, не се пита
      // с WHERE. Виж jdbc-boolean-getstring-returns-t-f.
      List<String> defaulted =
          query(
              c,
              "SELECT r.id, r.name, t.code, coalesce(r.bg_absence_type_defaulted, false)::text"
                  + " FROM "
                  + LEAVE_REASON
                  + " r JOIN bglocalization_bg_absence_type t ON t.id = r.bg_absence_type"
                  + " WHERE coalesce(r.bg_absence_type_defaulted, false) = true ORDER BY r.id");
      if (!defaulted.isEmpty()) {
        System.out.println(
            "ℹ РЕШЕНИЯ ЗА ТРЗ — съпоставяния, сложени ПО ПОДРАЗБИРАНЕ и НЕпотвърдени ("
                + defaulted.size()
                + "):\n  "
                + String.join("\n  ", defaulted)
                + "\n  Потвърждаването е сваляне на флага bgAbsenceTypeDefaulted, не мълчание.");
      }
    }
  }

  // ═══ ИЗВЪНРЕДНИЯТ ТРУД И ДЕКЛАРИРАНИТЕ ЧАСОВЕ ════════════════════════════

  @Test
  @DisplayName("БАЗА · домовете на класификацията и на декларираните часове са построени")
  void theHoursHomesExist() throws Exception {
    try (Connection c = open()) {
      requireColumn(
          c,
          EXTRA_HOURS_TYPE,
          "bg_extra_hours_kind",
          "2026-08-24-bg-extra-hours-kind-and-declared.sql");
      requireTable(c, DECLARED, "2026-08-24-bg-extra-hours-kind-and-declared.sql");

      List<String> missing = new ArrayList<>();
      for (String column :
          new String[] {
            "employment_contract", "period_from", "period_to", "bg_night_hours", "bg_on_call_hours"
          }) {
        if ("0"
            .equals(
                one(
                    c,
                    "SELECT count(*) FROM information_schema.columns WHERE table_name = '"
                        + DECLARED
                        + "' AND column_name = '"
                        + column
                        + "'"))) {
          missing.add(column);
        }
      }
      assertEquals(
          new ArrayList<String>(),
          missing,
          "заявката за декларираните часове назовава колони, които таблицата НЕ носи");
    }
  }

  @Test
  @DisplayName("БАЗА · всяка съхранена класификация е от селекцията — чужда стойност би ОТКАЗАЛА")
  void everyStoredKindIsFromTheSelection() throws Exception {
    try (Connection c = open()) {
      requireColumn(
          c,
          EXTRA_HOURS_TYPE,
          "bg_extra_hours_kind",
          "2026-08-24-bg-extra-hours-kind-and-declared.sql");

      List<String> unknown =
          query(
              c,
              "SELECT DISTINCT bg_extra_hours_kind FROM "
                  + EXTRA_HOURS_TYPE
                  + " WHERE bg_extra_hours_kind IS NOT NULL AND bg_extra_hours_kind NOT IN"
                  + " ('OVERTIME_WORKDAY', 'OVERTIME_REST_DAY', 'OVERTIME_HOLIDAY',"
                  + " 'OVERTIME_SIRV')");
      assertEquals(
          new ArrayList<String>(),
          unknown,
          "класификация извън bg.extra.hours.kind.select — сглобителят я третира като"
              + " НЕкласифицирана и ОТКАЗВА и четирите кофи, тоест видът изглежда попълнен и не"
              + " върши работа");
    }
  }

  @Test
  @DisplayName("⚠️ БАЗА · РЕШЕНИЯ ЗА ЧОВЕК: кои видове допълнителни часове чакат класификация")
  void theUnclassifiedExtraHoursTypesAreNamed() throws Exception {
    try (Connection c = open()) {
      requireColumn(
          c,
          EXTRA_HOURS_TYPE,
          "bg_extra_hours_kind",
          "2026-08-24-bg-extra-hours-kind-and-declared.sql");

      // ⚠️ Изброява, не пада — по същия довод като видовете отпуск: класификацията зависи от това
      // как клиентът е кръстил видовете си и не може да бъде „поправена“ от този клон.
      List<String> unclassified =
          query(
              c,
              "SELECT id, coalesce(name, '∅'), coalesce(increase::text, '∅') FROM "
                  + EXTRA_HOURS_TYPE
                  + " WHERE bg_extra_hours_kind IS NULL ORDER BY id");
      if (!unclassified.isEmpty()) {
        System.out.println(
            "ℹ РЕШЕНИЯ ЗА ЧОВЕК — видове допълнителни часове БЕЗ класификация ("
                + unclassified.size()
                + "), формат id | име | increase:\n  "
                + String.join("\n  ", unclassified)
                + "\n  ⚠️ Класификацията НЕ се извежда от increase: процентите по чл. 262 ал. 1 КТ"
                + " са минимуми и КТД може да е уговорил повече.");
      }
    }
  }

  @Test
  @DisplayName("⚠️ БАЗА · нито една декларация не покрива период, който свършва преди да започне")
  void noDeclarationEndsBeforeItStarts() throws Exception {
    try (Connection c = open()) {
      requireTable(c, DECLARED, "2026-08-24-bg-extra-hours-kind-and-declared.sql");

      List<String> inverted =
          query(c, "SELECT id FROM " + DECLARED + " WHERE period_to < period_from ORDER BY id");
      assertEquals(
          new ArrayList<String>(),
          inverted,
          "декларация с край преди началото — сглобителят търси ТОЧНО съвпадение на периода и тя"
              + " никога няма да бъде намерена");
    }
  }

  @Test
  @DisplayName("⚠️ БАЗА · една декларация на договор и период — две биха удвоили добавката")
  void oneDeclarationPerContractAndPeriod() throws Exception {
    try (Connection c = open()) {
      requireTable(c, DECLARED, "2026-08-24-bg-extra-hours-kind-and-declared.sql");

      List<String> duplicates =
          query(
              c,
              "SELECT employment_contract, period_from, period_to, count(*) FROM "
                  + DECLARED
                  + " GROUP BY employment_contract, period_from, period_to HAVING count(*) > 1");
      assertEquals(
          new ArrayList<String>(),
          duplicates,
          "две декларации за един договор и период — сглобителят ги СУМИРА, тоест добавката по"
              + " НСОРЗ чл. 8 се удвоява безшумно");
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
