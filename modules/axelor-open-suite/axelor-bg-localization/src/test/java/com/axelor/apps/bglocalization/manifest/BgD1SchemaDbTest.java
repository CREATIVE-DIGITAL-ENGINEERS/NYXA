package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgInsuredType;
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
 * БАЗОВАТА ПОЛОВИНА за Д1: „стигна ли дотам“.
 *
 * <p>Файловата половина ({@code BgD1FlagsAgainstNaredbaTest}, {@code BgD1FundToPointTest}) пита
 * дали е ВЯРНО ЗАПИСАНО и тече винаги, без Postgres. Тази пита нещо, което файловете не могат да
 * знаят: какво наистина има в общата база. Слети в един тест, отговорът „няма база“ би маскирал
 * отговора „колоната я няма“.
 *
 * <p><b>Кога се пропуска и кога се пада:</b>
 *
 * <ol>
 *   <li>няма достъпна база → ПРОПУСК с текста на грешката;
 *   <li>таблицата още я няма (домейнът не е минал през {@code ddl=update}) → ПРОПУСК с причина;
 *   <li>таблицата я има, но ѝ липсва КОЛОНА → ПРОПУСК с причина, назоваваща колоната;
 *   <li>колоните ги има и редовете ги има, но носят стари стойности → ПРОПУСК с причина: това иска
 *       App Install, а той нарочно НЕ се пуска по време на този клон (вдигнат манифест чупи чужди
 *       worktrees);
 *   <li>колоните ги има, редовете ги има и са ПРЕСНИ, но твърдението е нарушено → <b>ПАДА</b>.
 * </ol>
 *
 * <p><b>⚠️ ЛИПСВАЩАТА КОЛОНА Е СЪСТОЯНИЕ НА СРЕДАТА.</b> „Липсваща колона НИКОГА не е състояние на
 * средата и винаги пада“ звучи строго и е невярно за тази база: схемата в Axelor се доставя от
 * {@code ddl=update} ПРИ ПУСКАНЕ на приложението — тоест със същото действие, което носи и данните.
 * База, която не е стартирана с текущите домейни, няма колоната по абсолютно същата причина, по
 * която няма и таблицата.
 *
 * <p>Такова правило пада на всяка база, която не е стартирана с текущите домейни, макар файловата
 * половина да е зелена. Червено, което значи само „тази машина не е стартирала приложението“, е
 * точно видът червено, който приучава да не се гледа.
 *
 * <p>Прецедентът в самия модул казва същото: {@code BgInsuredTypeHomeDbTest#requireColumn} пропуска
 * с текста „колоната още я НЯМА — тоест нито миграцията е пускана, нито ddl=update е минавал“.
 *
 * <p><b>И това НЕ отслабва нито едно твърдение</b>, защото всяко от тях има файлова половина, която
 * тече безусловно на всяка машина: {@code BgD1FlagsAgainstNaredbaTest} за флаговете, {@code
 * BgD1FundToPointTest} за точките на фондовете, {@code BgD1PhaseBoundariesTest} за границите.
 */
class BgD1SchemaDbTest {

  private static final String INSURED_TYPE = "bglocalization_bg_insured_type";
  private static final String FUND = "bglocalization_bg_contribution_fund";
  private static final String DECLARATION = "bglocalization_bg_d1_declaration";
  private static final String RECORD = "bglocalization_bg_d1_record";

  /**
   * Шестте флага, които емитерът ЧЕТЕ.
   *
   * <p><b>⚠️ ИМЕНАТА СА ИЗВЕДЕНИ, А НЕ НАПИСАНИ.</b> Ръчно написаното име лесно греши: {@code
   * bgFillsT31} дава колона <b>{@code bg_fillst31}</b>, не {@code bg_fills_t31} — Axelor слага
   * долна черта САМО когато главната буква стои МЕЖДУ ДВЕ МАЛКИ, а тук след „T“ идва ЦИФРА.
   *
   * <p>⚠️ И ТАКАВА ГРЕШКА НЕ ПАДА: липсваща колона ПРОПУСКА теста с причина („тази база не е минала
   * през ddl=update“). Тоест грешното име дава вечен пропуск, който изглежда точно като липсваща
   * база — най-скъпият вид зелено. Изведени, имената не могат да се разминат с Hibernate.
   */
  private static final List<String> FLAG_COLUMNS = derive(BgInsuredType.class.getName());

  /**
   * Колоните на записа — ВСИЧКИТЕ, изведени от домейна.
   *
   * <p>⚠️ Ръчно написаните имена с цифра след „t“ лесно грешат ({@code t1_correction_code} вместо
   * {@code t1correction_code}), а тогава тестът пропуска винаги и причината му („колоните липсват“)
   * сочи базата.
   *
   * <p>⚠️ И ИЗВЕЖДАНЕТО НОСИ ВТОРА ПОЛЗА: нова колона на записа влиза в проверката САМА, без някой
   * да я допише тук. Ръчен списък оставя новите полета непроверени и това не се вижда.
   */
  private static final List<String> RECORD_COLUMNS = derive(BgD1Record.class.getName());

  /**
   * Имената на колоните на едно entity, ИЗВЕДЕНИ от домейн XML-а през naming strategy-то на Axelor.
   *
   * <p>⚠️ СЪЩАТА функция, която Hibernate ползва ({@code BgDomainColumns#columnName} обвива {@code
   * PhysicalNamingStrategyImpl}). Тоест тук няма второ правило, което да се разминe с първото.
   *
   * <p>⚠️ Наследените колони (id, version, created_on…) НЕ влизат: те не се обявяват в домейна и
   * проверката им не казва нищо за този модул.
   */
  private static List<String> derive(String entity) {
    Map<String, BgDomainColumns.FieldSpec> fields = BgDomainColumns.allFields().get(entity);
    List<String> out = new ArrayList<>();
    if (fields == null) {
      return out;
    }
    for (String field : fields.keySet()) {
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
              + " — твърденията за схемата на Д1 НЕ са проверени. Файловите половини"
              + " (BgD1FlagsAgainstNaredbaTest, BgD1FundToPointTest) текат и без база.");
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

  private static List<String> missingColumns(Connection c, String table, List<String> columns)
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
    return missing;
  }

  /**
   * Изисква колоните; при липса ПРОПУСКА с причина, назоваваща точно кои липсват.
   *
   * @return {@code true}, ако всички са налице и проверката може да продължи
   */
  private static boolean requireColumns(Connection c, String table, List<String> columns)
      throws Exception {
    List<String> missing = missingColumns(c, table, columns);
    if (!missing.isEmpty()) {
      BgSkip.because(
          "на "
              + table
              + " липсват колони "
              + missing
              + " — тоест тази база не е стартирана с текущите домейни (ddl=update не е минавал)."
              + " Състояние на средата, не на кода: колоните ги има в domains/ и файловите половини"
              + " на тези твърдения текат безусловно.");
      return false;
    }
    return true;
  }

  @Test
  @DisplayName("СХЕМА · шестте флага съществуват като колони — емитерът чете точно тях")
  void theSixFlagColumnsExist() throws Exception {
    try (Connection c = open()) {
      if (!tableExists(c, INSURED_TYPE)) {
        BgSkip.because(
            "таблицата "
                + INSURED_TYPE
                + " още я няма — модулът не е минавал през ddl=update на тази база. Състояние на"
                + " средата.");
        return;
      }
      requireColumns(c, INSURED_TYPE, FLAG_COLUMNS);
    }
  }

  @Test
  @DisplayName("СХЕМА · bg_d1_field съществува и НИТО ЕДИН фонд не е без точка")
  void everyFundHasItsD1PointInTheDatabase() throws Exception {
    try (Connection c = open()) {
      if (!tableExists(c, FUND)) {
        BgSkip.because("таблицата " + FUND + " още я няма — състояние на средата.");
        return;
      }
      // ⚠️ „bgd1field“, НЕ „bg_d1_field“ — след цифра долна черта НЕ се слага. Сверено в живата
      // обща база: колоната наистина се нарича bgd1field. Ръчно написаното име даваше вечен
      // пропуск.
      String d1FieldColumn = BgDomainColumns.columnName("bgD1Field");
      if (!requireColumns(c, FUND, Arrays.asList(d1FieldColumn))) {
        return;
      }

      String rows = one(c, "SELECT count(*) FROM " + FUND);
      if ("0".equals(rows)) {
        BgSkip.because(
            "речникът на фондовете е празен — App Install не е пускан на тази база. Състояние на"
                + " средата: няма какво да се сверява.");
        return;
      }
      String without =
          one(c, "SELECT count(*) FROM " + FUND + " WHERE " + d1FieldColumn + " IS NULL");
      assertEquals(
          "0",
          without,
          "фонд без точка в Д1: вноските по него ще изпаднат от декларацията мълчаливо —"
              + " агрегаторът пропуска перата без точка, а сумата просто няма да я има.");
    }
  }

  @Test
  @DisplayName("СХЕМА · таблиците на Д1 носят всички колони, които Фаза 1 произвежда")
  void theD1TablesCarryEveryEmittedColumn() throws Exception {
    try (Connection c = open()) {
      if (!tableExists(c, RECORD) || !tableExists(c, DECLARATION)) {
        BgSkip.because(
            "таблиците "
                + DECLARATION
                + " / "
                + RECORD
                + " още ги няма — новите домейни не са минали през ddl=update. Това е ОЧАКВАНО"
                + " състояние по време на този клон: App Install нарочно НЕ се пуска, за да не"
                + " вдигне манифеста на общата база и да не счупи чужди worktrees.");
        return;
      }
      requireColumns(c, RECORD, RECORD_COLUMNS);
    }
  }

  /**
   * ⚠️ ТЕСТЪТ ОБЪРНА ПОСОКАТА СИ, И ТОВА Е ИЗМЕРИМИЯТ РЕЗУЛТАТ ОТ ЕТАПИ 1–3.
   *
   * <p>Дотук той твърдеше, че осемте отложени точки НЯМАТ колони — структурна граница, защото
   * празна колона е неразличима от сметната нула. Днес и осемте ги ИМА: т. 16 и кофите ѝ (Етап 1),
   * т. 17/18 и т. 27/28 (Етап 2), т. 29, т. 31 и т. 34 (Етап 3).
   *
   * <p>Затова твърдението се обръща: {@code BgD1Point.NOT_YET_EMITTED} трябва да е ПРАЗЕН, а всяка
   * точка, която някой ден пак влезе в него, НЕ бива да има колона. Тоест тестът пази СЪГЛАСИЕТО
   * между кода и схемата в двете посоки, вместо да пази един замразен списък.
   */
  @Test
  @DisplayName("СХЕМА · онова, което кодът обявява за НЕемитирано, НЯМА колона — в двете посоки")
  void theDeferredListAgreesWithTheSchema() throws Exception {
    try (Connection c = open()) {
      if (!tableExists(c, RECORD)) {
        BgSkip.because("таблицата " + RECORD + " още я няма — състояние на средата.");
        return;
      }
      for (String point : com.axelor.apps.bglocalization.service.d1.BgD1Point.NOT_YET_EMITTED) {
        String prefix = "t" + point.replace(".", "") + "%";
        String found =
            one(
                c,
                "SELECT count(*) FROM information_schema.columns WHERE table_name = '"
                    + RECORD
                    + "' AND column_name LIKE '"
                    + prefix
                    + "'");
        assertEquals(
            "0",
            found,
            "таблицата носи колона „"
                + prefix
                + "“, а т. "
                + point
                + " е обявена за НЕемитирана. Ако наистина е построена, свали я от"
                + " BgD1Point.NOT_YET_EMITTED и от BgD1PhaseBoundariesTest — ЗАЕДНО.");
      }
    }
  }

  /** Таблицата на Д6 — един ред на дружество и месец, без дъщерни записи. */
  private static final String D6 = "bglocalization_bg_d6_declaration";

  /** Колоните на Д6 — изведени от домейна, по същия довод като при записа на Д1. */
  private static final List<String> D6_COLUMNS = derive(BgD6Declaration.class.getName());

  @Test
  @DisplayName("СХЕМА · таблицата на Д6 носи осемте числа и идентификацията")
  void theD6TableCarriesEveryEmittedColumn() throws Exception {
    try (Connection c = open()) {
      if (!tableExists(c, D6)) {
        BgSkip.because(
            "таблицата "
                + D6
                + " още я няма — новият домейн не е минал през ddl=update. ⚠️ ОЧАКВАНО състояние по"
                + " време на този клон: App Install нарочно НЕ се пуска, за да не вдигне манифеста"
                + " на общата база и да не счупи чужди worktrees. Прецедентът е същият като при"
                + " таблиците на Д1.");
        return;
      }
      requireColumns(c, D6, D6_COLUMNS);
    }
  }

  @Test
  @DisplayName("ДОСТАВКА · код 92 носи bg_no_net_pay = true, или казва, че App Install не е пускан")
  void code92IsDeliveredWithTheCorrectedFlag() throws Exception {
    try (Connection c = open()) {
      if (!tableExists(c, INSURED_TYPE)) {
        BgSkip.because("таблицата " + INSURED_TYPE + " още я няма — състояние на средата.");
        return;
      }
      String rows = one(c, "SELECT count(*) FROM " + INSURED_TYPE + " WHERE code = '92'");
      if ("0".equals(rows)) {
        BgSkip.because(
            "код 92 го няма в базата — App Install не е пускан. Състояние на средата; верността на"
                + " стойността се проверява безусловно в BgD1FlagsAgainstNaredbaTest.");
        return;
      }

      // ⚠️ ::text, а не getString върху boolean — JDBC връща „t“/„f“, не „true“/„false“.
      String delivered =
          one(c, "SELECT bg_no_net_pay::text FROM " + INSURED_TYPE + " WHERE code = '92' LIMIT 1");

      if (!"true".equals(delivered)) {
        BgSkip.because(
            "код 92 стои с bg_no_net_pay = "
                + delivered
                + ", а seed-ът вече казва true. Поправката е ПРОМЯНА НА СТОЙНОСТ в съществуващ ред"
                + " (App Install е upsert по importId), тоест не вдига манифест — но иска App"
                + " Install, за да стигне до базата. Състояние на средата: файловата половина вече"
                + " твърди верността безусловно.");
        return;
      }

      // Доставено е. Тогава твърдението е СТРОГО: нито един ред за код 92 не бива да е останал със
      // старата стойност (номенклатурата е темпорална и кодът може да има повече от един ред).
      String stale =
          one(
              c,
              "SELECT count(*) FROM "
                  + INSURED_TYPE
                  + " WHERE code = '92' AND bg_no_net_pay IS DISTINCT FROM true");
      assertEquals(
          "0",
          stale,
          "код 92 има ред с bg_no_net_pay, различно от true. Забележката към т. 34 изброява 92 —"
              + " тоест нетно възнаграждение НЕ се попълва за морското лице. ⚠️ IS DISTINCT FROM,"
              + " а не <> : NULL никога не е равно на нищо и обикновеното сравнение би пропуснало"
              + " точно непопълнения ред.");
    }
  }
}
