package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СОЦИАЛНИТЕ РАЗХОДИ — базовата половина: „ДОСТАВЕНИ ли са капачките и перата“.
 *
 * <p><b>Защо двете половини не са една.</b> {@code BgSocialExpenseSeedFileTest} пита дали ДАННИТЕ
 * са верни, {@code BgSocialExpenseCapResolverTest} — дали АЛГОРИТЪМЪТ ги прилага вярно; и двата
 * текат без Postgres. Този пита третото: стигнали ли са редовете до базата, тоест ще се дели ли
 * ваучер на ЖИВА среда. Слети, отговорът „App Install не е пускан“ би маскирал отговора „капачката
 * е сгрешена“.
 *
 * <p><b>Пропуска се при три състояния на средата</b>, всяко с обявена причина: няма достъпна база;
 * таблицата {@code bglocalization_bg_social_expense_cap} още я няма ({@code ddl=update} не е
 * минавал с новите домейни); таблицата я има, но е празна (App Install не е пускан след този
 * комит).
 *
 * <p><b>⚠️ ЛИПСВАЩАТА ТАБЛИЦА Е СЪСТОЯНИЕ НА СРЕДАТА, НЕ ДЕФЕКТ, и това е ПРЕЦЕДЕНТ, не мнение.</b>
 * Точно така се държат {@code BgGarnishmentScaleDbTest} и {@code BgSchemaColumnWidthTest}: {@code
 * ddl=update} създава нова таблица ЦЯЛА при първото вдигане на приложението с този код, а
 * миграционен файл за нея НЯМА нарочно (нито една от четиринайсетте заварени миграции не създава
 * таблица). Червено за „не си рестартирал“ приучава да не се гледа.
 *
 * <p><b>⚠️ ЗАЩО ТОЗИ ТЕСТ НЕ ПИПА БАЗАТА.</b> Той САМО ЧЕТЕ. App Install е координирано действие —
 * през базата работят петнайсет worktree-а наведнъж — и НЕ е работа на тест. Затова тук няма нито
 * един INSERT.
 */
class BgSocialExpenseDbTest {

  private static final String CAPS = "bglocalization_bg_social_expense_cap";
  private static final String EXCEPTIONS = "bglocalization_bg_social_expense_exception";
  private static final String TYPES = "bglocalization_bg_pay_slip_line_type";

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
              + " — твърдението „капачките и перата от 2xx са доставени“ НЕ е проверено. Файловата"
              + " половина (BgSocialExpenseSeedFileTest) и свидетелите на резолвера"
              + " (BgSocialExpenseCapResolverTest) текат и без база.");
      return null;
    }
  }

  private static String one(Connection c, String sql) throws Exception {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  /**
   * ⚠️ БУЛЕВА КОЛОНА СЕ ЧЕТЕ КАТО БУЛЕВА, НЕ КАТО НИЗ.
   *
   * <p>Асерция, която сглобява реда с {@code ||} и сравнява низове, зависи от ТРИ неща наведнъж,
   * нито едно от които не е дефект в ДАННИТЕ: {@code getString} върху сурова boolean колона дава
   * pgjdbc-евото „t“/„f“, {@code ::text} в SQL дава Postgres-кото „true“/„false“, а друг драйвер би
   * дал „1“/„0“. Заявка с {@code ::text} и очаквани „t“/„f“ пада върху вярна база. {@code
   * getBoolean} маха ЦЯЛОТО семейство грешки наведнъж.
   *
   * <p>⚠️ {@code null} се връща И при липсващ ред, И при SQL NULL в клетката — затова типът е
   * {@code Boolean}, не {@code boolean}. Голото {@code rs.getBoolean} превръща NULL в {@code false}
   * тихо, а тук разликата е съществена: заварени редове носят NULL там, където App Install пише
   * {@code false}.
   */
  private static Boolean bool(Connection c, String sql) throws Exception {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      if (!rs.next()) {
        return null;
      }
      boolean value = rs.getBoolean(1);
      return rs.wasNull() ? null : Boolean.valueOf(value);
    }
  }

  /**
   * ⚠️ Подредбата идва от ЯВА, НЕ от колацията на базата.
   *
   * <p>{@code ORDER BY code} подрежда според локала на базата: под {@code en_US.UTF-8} долната
   * черта се прескача и {@code pl_soc_vaucheri_*} излиза ПРЕД {@code pl_soc_v_natura_*}, а под
   * {@code C} — обратното. Сравнение срещу закован низ би било зелено само на едната от двете —
   * същата порода капан като {@code t} срещу {@code true}. Сортира се СПИСЪК, не множество, за да
   * остане видим евентуален ДУБЛИРАН ред — {@code TreeSet} би го изял тихо.
   */
  private static List<String> sorted(List<String> rows) {
    List<String> out = new ArrayList<>(rows);
    Collections.sort(out);
    return out;
  }

  private static List<String> query(Connection c, String sql) throws Exception {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      while (rs.next()) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
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

  private static void requireTable(Connection c, String table) throws Exception {
    String found =
        one(
            c,
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_name = '"
                + table
                + "'");
    if ("0".equals(found)) {
      BgSkip.because(
          "таблицата "
              + table
              + " още я НЯМА — ddl=update не е минавал с новите домейни BgSocialExpenseCap и"
              + " BgSocialExpenseException. Нова таблица Hibernate създава цяла при първото вдигане"
              + " на приложението с този код; миграционен файл за нея НЯМА нарочно (виж"
              + " db/migration/2026-08-26-bg-social-expenses.sql — той носи само трите нови КОЛОНИ"
              + " върху заварени таблици). Състояние на средата, не на кода.");
    }
  }

  @Test
  @DisplayName("БАЗА · трите капачки са доставени, с преобразуваните си евро стойности")
  void theCapsAreDelivered() throws Exception {
    try (Connection c = open()) {
      requireTable(c, CAPS);
      String rows = one(c, "SELECT count(*) FROM " + CAPS);
      if ("0".equals(rows)) {
        BgSkip.because(
            "таблицата "
                + CAPS
                + " я има, но е ПРАЗНА — App Install не е пускан след този комит. Дотогава всяко"
                + " перо от 2xx ще получава назован ОТКАЗ вместо разделяне, тоест средата е"
                + " безопасна — просто твърдението тук не е проверено. Пусни App Install.");
      }
      assertEquals("3", rows, "три капачки: DDO_HEALTH_LIFE, FOOD_VOUCHERS, ONE_OFF_AID");

      // ⚠️ ЧИСЛАТА И ТЕКСТЪТ СЕ ЧЕТАТ КАТО НИЗ, ФЛАГЪТ — КАТО BOOLEAN, и разделянето е нарочно
      // (виж bool()). Долепен към низа с ::text, флагът внасяше представяне на драйвера в едно
      // твърдение за ДАННИ и падаше с „t“ срещу „true“ върху напълно вярна база.
      assertEquals(
          "DDO_HEALTH_LIFE | 30.68 | 30.68 | MONTHLY",
          one(
              c,
              "SELECT code || ' | ' || bg_personal_cap_amount || ' | ' || bg_company_cap_amount"
                  + " || ' | ' || bg_periodicity FROM "
                  + CAPS
                  + " WHERE code = 'DDO_HEALTH_LIFE'"));
      assertEquals(
          Boolean.TRUE,
          bool(c, "SELECT bg_company_tax_applies FROM " + CAPS + " WHERE code = 'DDO_HEALTH_LIFE'"),
          "⚠️ Капачката носи и ФИРМЕНА ос: превишението е обект по ЗКПО чл. 204 ал. 1 т. 2. Свален,"
              + " данъкът върху разходите не се начислява на никого");
      assertEquals(
          "FOOD_VOUCHERS | 102.26 | 102.26 | MONTHLY",
          one(
              c,
              "SELECT code || ' | ' || bg_personal_cap_amount || ' | ' || bg_company_cap_amount"
                  + " || ' | ' || bg_periodicity FROM "
                  + CAPS
                  + " WHERE code = 'FOOD_VOUCHERS'"));
      assertEquals(
          Boolean.TRUE,
          bool(c, "SELECT bg_company_tax_applies FROM " + CAPS + " WHERE code = 'FOOD_VOUCHERS'"));

      // ⚠️ ЕДНОКРАТНАТА ПОМОЩ: празната фирмена клетка стига до базата като 0.00, НЕ като NULL —
      // същият измерен капан като при BgGarnishmentScale. Затова се пита signum()/= 0, а „няма
      // фирмена ос“ се носи от bg_company_tax_applies.
      assertEquals(
          "ONE_OFF_AID | 1227.10 | ANNUAL | 0",
          one(
              c,
              "SELECT code || ' | ' || bg_personal_cap_amount || ' | ' || bg_periodicity"
                  + " || ' | '"
                  + " || CASE WHEN bg_company_cap_amount IS NULL THEN 'NULL'"
                  + " ELSE trim(to_char(bg_company_cap_amount, '9999990')) END FROM "
                  + CAPS
                  + " WHERE code = 'ONE_OFF_AID'"),
          "⚠️ Ако тук излезе NULL вместо 0, подразбирането на decimal полетата се е сменило и"
              + " прочитите със signum() трябва да се препрочетат — не да се добавят null проверки");
      // ⚠️ ЕДИНСТВЕНАТА капачка БЕЗ фирмена ос — и това е разграничителят, не празната клетка
      // по-горе: еднократната помощ по ЗКПО чл. 204 ал. 1 т. 2 не е обект на данък върху
      // разходите. Тук се иска ИЗРИЧНО false, не „не е true“ — NULL значи заварен ред, който App
      // Install не е пипал, и bool() го отделя от false вместо да ги слее.
      assertEquals(
          Boolean.FALSE,
          bool(c, "SELECT bg_company_tax_applies FROM " + CAPS + " WHERE code = 'ONE_OFF_AID'"));
    }
  }

  @Test
  @DisplayName(
      "БАЗА · ⚠️ четиринайсетте пера под капачка носят FK-а си — инак се печатат неразделени")
  void theCappedLineTypesPointAtTheirCap() throws Exception {
    try (Connection c = open()) {
      requireTable(c, CAPS);
      String delivered =
          one(c, "SELECT count(*) FROM " + TYPES + " WHERE line_number BETWEEN 200 AND 299");
      if ("0".equals(delivered)) {
        BgSkip.because(
            "нито едно перо от 2xx го няма в "
                + TYPES
                + " — App Install не е пускан след този комит. Празнотата тук е стара база, не"
                + " сгрешен seed.");
      }
      assertEquals(
          "25", delivered, "двайсет и пет реда в 2xx: четиринайсет под капачка и единайсет булеви");
      assertEquals(
          "14",
          one(
              c,
              "SELECT count(*) FROM "
                  + TYPES
                  + " WHERE line_number BETWEEN 200 AND 299 AND bg_social_expense_cap IS NOT NULL"),
          "⚠️ Липсващ FK значи перо, което резолверът НЕ вижда: то се печата с цялата си сума в"
              + " реда „в рамките“ и превишението изчезва тихо");

      // По ДВЕ пера на позиция, и точно по три/една/три позиции на капачка.
      assertEquals(
          "[DDO_HEALTH_LIFE | 6, FOOD_VOUCHERS | 2, ONE_OFF_AID | 6]",
          query(
                  c,
                  "SELECT p.code, count(*) FROM "
                      + TYPES
                      + " t JOIN "
                      + CAPS
                      + " p ON p.id = t.bg_social_expense_cap GROUP BY p.code ORDER BY p.code")
              .toString(),
          "⚠️ Числото е 2 × брой ПОЗИЦИИ, не 2 × брой капачки: DDO_HEALTH_LIFE има три позиции"
              + " (доброволно пенсионно, доброволно здравно, „Живот“), ONE_OFF_AID също три"
              + " (раждане, брак, смърт), FOOD_VOUCHERS — една");

      // ⚠️ И ЕДНО, КОЕТО БРОЕНЕТО НЕ ХВАЩА: точното РАЗПРЕДЕЛЕНИЕ на осигурителния флаг. Осем
      // вдигнати флага минават и когато вдигнатите са грешните осем.
      //
      // ⚠️ ОЧАКВАНИТЕ СА ОСЕМ, НЕ ПЕТ. Заявката пита ЦЯЛАТА група 2xx, не само петте ПОД
      // КАПАЧКА; трите ИЗВЪН нея (261, 270, 271) също носят вдигнат флаг в seed-а. ПРАВНОТО ИМ
      // ОСНОВАНИЕ е НЕВД чл. 2 ал. 1 във вр. с КСО чл. 6 ал. 12:
      // вноски се дължат върху социалните разходи, дадени пряко на осигуреното лице „В ПАРИ ИЛИ В
      // НАТУРА“ — и двете форми са ИЗРИЧНО вътре; освобождават единствено изброените в чл. 2
      // ал. 2 и чл. 1 ал. 8, а нито един от трите не е в тези списъци. Същото се твърди файлово в
      // BgSocialExpenseSeedFileTest#inKindRemovesTheTaxButNotTheContributions и
      // #transportIsThreeHypothesesNotOne, тоест числото 8 има ДВА независими свидетеля.
      //
      // ⚠️ НЕ смъквайте обратно на пет и НЕ стеснявайте заявката с
      // `AND bg_social_expense_cap IS NOT NULL` — второто прави пет зелено, но маха покритието
      // на 261/270/271, а именно техните флагове носят вноските върху натурата.
      assertEquals(
          "[pl_soc_ne_v_natura_dohod, pl_soc_pomosht_brak_previshenie,"
              + " pl_soc_pomosht_brak_v_ramkite, pl_soc_transport_lek_avtomobil,"
              + " pl_soc_v_natura_oblozhen, pl_soc_vaucheri_previshenie,"
              + " pl_soc_zhivot_previshenie, pl_soc_zhivot_v_ramkite]",
          sorted(
                  query(
                      c,
                      "SELECT code FROM "
                          + TYPES
                          + " WHERE line_number BETWEEN 200 AND 299 AND bg_is_insurance_income"
                          + " ORDER BY code"))
              .toString(),
          "⚠️ ОСЕМ реда в 2xx са осигурителен доход — ПЕТ под капачка и ТРИ извън нея, всеки по"
              + " СВОЯ норма. ПОД КАПАЧКА: „Живот“ (204/205) — защото НЕВД чл. 2 ал. 2 не го"
              + " изброява; помощта при БРАК (222/223) — защото същият списък изброява раждането и"
              + " смъртта, но не брака; превишението на ваучерите (211) — защото чл. 2 ал. 3 т. 1"
              + " освобождава само „при условията на чл. 209а ал. 1“. ИЗВЪН КАПАЧКА, и трите по"
              + " НЕВД чл. 2 ал. 1 във вр. с КСО чл. 6 ал. 12: транспортът с ЛЕК АВТОМОБИЛ (261) —"
              + " ЗКПО чл. 210 ал. 2 отнема освобождаването НАПЪЛНО; придобивката В НАТУРА,"
              + " обложена по ЗКПО (270) — натурата е изрично вътре в чл. 2 ал. 1, макар ЗДДФЛ"
              + " чл. 24 ал. 2 т. 9 да маха данъка на лицето; и същата помощ, дадена НЕ в натура,"
              + " тоест В ПАРИ (271). Изравнени, флаговете махат вноски, които се дължат");
    }
  }

  @Test
  @DisplayName(
      "БАЗА · ⚠️ таблицата с изключенията съществува и е ПРАЗНА — това е нормалното състояние")
  void theExceptionTableExistsAndBeingEmptyIsFine() throws Exception {
    try (Connection c = open()) {
      requireTable(c, EXCEPTIONS);
      String rows = one(c, "SELECT count(*) FROM " + EXCEPTIONS);
      if (rows == null) {
        fail("заявката към " + EXCEPTIONS + " не върна ред — това не е база, на която да се вярва");
      }
      // ⚠️ ТУК НЕ СЕ ПРОВЕРЯВА БРОЙ, И ТОВА Е САМОТО ПРАВИЛО. Липсващ ред значи „условията са
      // изпълнени“, тоест празната таблица е нормалното състояние на всяка фирма без лоши месеци.
      // Очакван брой тук би превърнал отсъствието в дефект — точно обратното на замисъла.
      System.out.println(
          "ℹ таблицата с изключенията носи "
              + rows
              + " реда. Липсващ ред за (фирма, година, месец) значи ВСИЧКО НАРЕД — това е"
              + " ОБРАТНОТО на BgGarnishmentScale, където липсата е отказ.");
    }
  }

  @Test
  @DisplayName("БАЗА · контра-перото 630 и двата реда с целия размер в ЗКПО основата са доставени")
  void theCounterEntryAndTheTwoWholeAmountRowsAreDelivered() throws Exception {
    try (Connection c = open()) {
      String found =
          one(c, "SELECT count(*) FROM " + TYPES + " WHERE code = 'pl_soc_v_natura_predadeno'");
      if ("0".equals(found)) {
        BgSkip.because(
            "перото 630 го няма — App Install не е пускан след този комит. Без него стойността на"
                + " всяка придобивка в натура влиза в НЕТОТО за получаване (netTotal брои всяко"
                + " начисление извън 5xx), тоест фишът показва пари, които лицето не получава.");
      }
      assertEquals(
          "630 | PAYMENTS | DEBIT",
          one(
              c,
              "SELECT line_number || ' | ' || bg_group || ' | ' || bg_sign FROM "
                  + TYPES
                  + " WHERE code = 'pl_soc_v_natura_predadeno'"));
      // ⚠️ И ДВАТА флага са СВАЛЕНИ, защото 630 е КОНТРА-перо: то само ИЗВАЖДА от нетото сума,
      // която перото 270 вече е начислило с квалификацията си. Вдигнат кой да е от двата, същата
      // придобивка се облага и осигурява ДВА пъти — веднъж на 270 и веднъж на изваждането ѝ.
      assertEquals(
          Boolean.FALSE,
          bool(
              c,
              "SELECT bg_is_taxable FROM " + TYPES + " WHERE code = 'pl_soc_v_natura_predadeno'"));
      assertEquals(
          Boolean.FALSE,
          bool(
              c,
              "SELECT bg_is_insurance_income FROM "
                  + TYPES
                  + " WHERE code = 'pl_soc_v_natura_predadeno'"));

      assertEquals(
          "[pl_soc_transport_lek_avtomobil, pl_soc_v_natura_oblozhen]",
          query(c, "SELECT code FROM " + TYPES + " WHERE bg_is_company_expense_taxed ORDER BY code")
              .toString(),
          "⚠️ ТОЧНО ДВА реда, и то поименно: чл. 210 ал. 2 (транспорт с лек автомобил) и"
              + " придобивката в натура, обложена по ЗКПО. Заявката пита `WHERE"
              + " bg_is_company_expense_taxed`, НЕ `= false` за обратното — заварените редове носят"
              + " NULL, а NULL не е равно на нищо");
    }
  }
}
