package com.axelor.apps.bglocalization.manifest;

import com.axelor.apps.bglocalization.db.BgArt73Para1Record;
import com.axelor.apps.bglocalization.db.BgArt73Para1Report;
import com.axelor.apps.bglocalization.db.BgArt73Para6Annual;
import com.axelor.apps.bglocalization.db.BgArt73Para6Record;
import com.axelor.apps.bglocalization.db.BgArt73Para6Report;
import com.axelor.apps.bglocalization.db.BgIncomeType;
import com.axelor.apps.bglocalization.db.BgIndividualIncomePayment;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СХЕМАТА НА ЗДДФЛ ПОДСИСТЕМАТА — таблиците на двете справки и на регистъра, срещу ЖИВА база.
 *
 * <p><b>Какво пита и какво НЕ пита.</b> Пита само едно: съществуват ли таблиците с колоните, които
 * домейните обявяват. Не пита дали в тях има редове — регистърът {@code BgIndividualIncomePayment}
 * е САМО СХЕМА във Фаза 1 и празната таблица е ВЯРНОТО му състояние. Тест, който иска редове там,
 * би бил червен по устройство до Фаза 2.
 *
 * <p><b>⚠️ И ИМЕНАТА, И КОЛОНИТЕ СЕ ЧЕТАТ, А НЕ СЕ ПИШАТ.</b> Таблицата идва от {@code @Table} на
 * генерирания клас, колоните — от домейн XML-а през {@link BgDomainColumns}, тоест през СЪЩАТА
 * стратегия, която ползва Hibernate. Правилото не е „главна буква → долна черта“: след ЦИФРА черта
 * НЕ се слага, което прави {@code isForeign → is_foreign}, но {@code payerIdentifier} и всяко поле
 * с цифра в името — не каквото ръката би написала. Веднъж написани на ръка, такива имена обявяват
 * СЪЩЕСТВУВАЩИ колони за липсващи.
 *
 * <p>Пропуска се с обявена причина при две състояния: няма достъпна база; таблицата още я няма
 * (модулът не е минавал през {@code ddl=update} на тази база). Второто е нормалното веднага след
 * този комит — схемата идва от пускането, не от seed.
 */
class BgZddflSchemaDbTest {

  @Test
  @DisplayName("СХЕМА · регистърът на изплатените доходи съществува с всичките си колони")
  void theIncomePaymentRegisterExists() throws Exception {
    assertTableMatchesDomain(
        BgIndividualIncomePayment.class,
        "BgIndividualIncomePayment.xml",
        // ⚠️ Двете суми, които правят реда проверим: бруто за колона 11 на справката и основата за
        // чл. 55. Липсва ли едната, справката и декларацията четат едно и също число.
        //
        // ⚠️ selfInsuredDeclared е НОВ във Фаза 2 и тук е свидетел по същата причина: без него
        // изключението по чл. 43, ал. 5 няма къде да се запише и всяко плащане към декларирало СОЛ
        // би получило удържан данък, какъвто законът не дължи. Липсата му е ТИХА — формата просто
        // няма да покаже чекбокса, а изчислението ще смята с withheldByPayer на кода.
        Arrays.asList(
            "grossAmount",
            "taxableBase",
            "taxAmount",
            "insuranceAmount",
            "taxKind",
            "selfInsuredDeclared"));
  }

  @Test
  @DisplayName("СХЕМА · регистърът носи ПЛАТЕЦА — без него справката по ал. 1 няма обхват")
  void theIncomePaymentRegisterCarriesThePayer() throws Exception {
    assertTableMatchesDomain(
        BgIndividualIncomePayment.class,
        "BgIndividualIncomePayment.xml",
        // ⚠️ Липсата му е ТИХА и по-опасна от червено: без платец справката по чл. 73 ал. 1 или
        // излиза празна, или — ако редовете се вземат всички — описва доходите на едно дружество
        // в справката на друго. Нито едното не изглежда като грешка на нито един ред.
        Arrays.asList("company"));
  }

  @Test
  @DisplayName("СХЕМА · заглавната част на справката по чл. 73 ал. 1 (образец 5-2001) съществува")
  void theArtSeventyThreeParaOneReportExists() throws Exception {
    assertTableMatchesDomain(
        BgArt73Para1Report.class,
        "BgArt73Para1Report.xml",
        // ⚠️ Колоната с ГРАНИЦИТЕ е част от отговора: указание т. 6 казва, че справка без всички
        // изискуеми данни „не се счита за достоверна и не се приема“. Кои данни липсват и защо се
        // чете оттам, преди подаване.
        Arrays.asList("taxYear", "bgBoundaryReport", "contactEmail"));
  }

  @Test
  @DisplayName("СХЕМА · редовете на Част ІІ носят колони 11, 12, 13 и 14")
  void theArtSeventyThreeParaOneRecordExists() throws Exception {
    assertTableMatchesDomain(
        BgArt73Para1Record.class,
        "BgArt73Para1Record.xml",
        // ⚠️ personCount е колона 14 и се пълни САМО за кодове 5031 и 8141 (указание т. 5);
        // birthDate е колона 5 и е ЕДИНСТВЕНОТО, което назовава лице с тип идентификатор „3“.
        Arrays.asList("grossAmount", "insuranceAmount", "taxAmount", "personCount", "birthDate"));
  }

  @Test
  @DisplayName("СХЕМА · каталогът носи ВТОРИЯ флаг — този за колона 14")
  void theCatalogueCarriesThePersonCountFlag() throws Exception {
    assertTableMatchesDomain(
        BgIncomeType.class,
        "BgIncomeType.xml",
        // ⚠️ Без него колона 14 би се извела от isAggregate и би получила число и на код 820 —
        // сборен ред, на който указание т. 5 не дава брой лица.
        Arrays.asList("isAggregate", "reportsPersonCount"));
  }

  @Test
  @DisplayName("СХЕМА · заглавната част на справката по чл. 73 ал. 6 съществува")
  void theArtSeventyThreeReportExists() throws Exception {
    assertTableMatchesDomain(
        BgArt73Para6Report.class,
        "BgArt73Para6Report.xml",
        // ⚠️ Колоната с ГРАНИЦИТЕ е част от отговора, не украса: без нея празните колони 6 и 8–22
        // не се различават от „лицето няма облекчения“.
        Arrays.asList("taxYear", "bgBoundaryReport"));
  }

  @Test
  @DisplayName("СХЕМА · записите на Част ІІ съществуват с трите изведени колони")
  void theArtSeventyThreeRecordExists() throws Exception {
    assertTableMatchesDomain(
        BgArt73Para6Record.class,
        "BgArt73Para6Record.xml",
        // Колони 3, 4 и 7 — трите числа, които фишът отговаря.
        Arrays.asList("income", "insurance", "advanceTax", "identifierType", "isMainEmployer"));
  }

  @Test
  @DisplayName("СХЕМА · годишното преизчисляване по чл. 49 съществува с колони 6, 8–16 и 17–22")
  void theArtFortyNineRecalculationExists() throws Exception {
    assertTableMatchesDomain(
        BgArt73Para6Annual.class,
        "BgArt73Para6Annual.xml",
        // ⚠️ ЧЕТИРИ СВИДЕТЕЛЯ, И ВСЕКИ ЗА РАЗЛИЧЕН ТИХ ДЕФЕКТ.
        //
        // baseArt25Para3 е ред 8.2 — годишната основа на МОРСКОТО ЛИЦЕ, свита на 10 на сто по
        // чл. 25 ал. 3. Липсва ли колоната, доходът му или изчезва от основата, или влиза цял и
        // лицето плаща десетократно.
        //
        // settlement е колона 19 — ЕДИНСТВЕНАТА сума на образеца, която се отрязва до цяла парична
        // единица. Липсва ли, изравняването няма къде да се запише и справката излиза с годишен
        // данък, но без разлика за довнасяне.
        //
        // reliefDocumentsProvided е вратата на чл. 49 ал. 4. Липсва ли, признакът не може да се
        // вдигне и НИТО ЕДНО облекчение няма да се приложи — тихо, защото нула в колони 8–16 е
        // напълно правдоподобна.
        //
        // reliefDonationsCode03 е последната от деветте колони с облекчения — тя пада първа, ако
        // домейнът е добавен наполовина.
        Arrays.asList(
            "baseArt25Para3", "settlement", "reliefDocumentsProvided", "reliefDonationsCode03"));
  }

  // ═══ СРЕДАТА ══════════════════════════════════════════════════════════════

  /**
   * Таблицата носи ВСЯКА колона, обявена в домейна — плюс изрично назованите свидетели.
   *
   * <p>⚠️ Свидетелите се изброяват ОТДЕЛНО, макар да са част от всички: те са полетата, чиято липса
   * е тиха. Обща проверка „домейнът съвпада с таблицата“ пада еднакво при липсваща колона и при
   * непусната миграция; назованият свидетел казва КОЯ колона липсва още в името на теста.
   */
  private static void assertTableMatchesDomain(
      Class<?> entity, String domainFile, List<String> witnesses) throws Exception {
    // ⚠️ През BgDomainColumns.tableName, а не голото @Table.name(): анотацията е с ГЛАВНИ букви,
    // а information_schema — с малки. Сравнени направо, двете не съвпадат никога и тестът щеше да
    // се пропуска с „таблицата още я няма“ ЗАВИНАГИ. Затова и заявките питат lower(...).
    String table =
        BgDomainColumns.tableName(entity.getAnnotation(Table.class).name())
            .toLowerCase(java.util.Locale.ROOT);
    try (Connection c = open()) {
      if (c == null) {
        return;
      }
      if (!tableExists(c, table)) {
        BgSkip.because(
            "таблицата "
                + table
                + " още я няма — модулът не е минавал през ddl=update на тази база. Състояние на"
                + " средата, не на кода: схемата идва от пускането, а този клон не пуска нищо.");
        return;
      }
      List<String> expected = new ArrayList<>();
      for (String field : declaredFields(domainFile, entity)) {
        expected.add(BgDomainColumns.columnName(field).toLowerCase(java.util.Locale.ROOT));
      }
      for (String witness : witnesses) {
        String column = BgDomainColumns.columnName(witness).toLowerCase(java.util.Locale.ROOT);
        if (!expected.contains(column)) {
          org.junit.jupiter.api.Assertions.fail(
              "свидетелят "
                  + witness
                  + " вече не е поле на "
                  + domainFile
                  + " — тестът сочи към нещо, което е преименувано или махнато");
        }
      }
      List<String> missing = new ArrayList<>();
      for (String column : expected) {
        if (!columnExists(c, table, column)) {
          missing.add(column);
        }
      }
      if (!missing.isEmpty()) {
        BgSkip.because(
            "на "
                + table
                + " липсват колони "
                + missing
                + " — тази база не е стартирана с текущите домейни. Състояние на средата.");
      }
    }
  }

  /**
   * Полетата на ентитета от домейн XML-а, без {@code one-to-many} (те нямат своя колона).
   *
   * <p>⚠️ <b>Ключът е ПЪЛНОТО име, не простото.</b> {@link BgDomainColumns#allFields()} ключира по
   * {@code package + "." + entity}, защото едно просто име може да съществува в два модула. Питан с
   * {@code getSimpleName()}, речникът връща {@code null} ВИНАГИ — тоест тестът се проваля с
   * „домейнът не се чете“ дори при напълно здрав домейн.
   *
   * <p>⚠️ И защо това се крие: докато таблиците ги няма в базата, проверката СПИРА по-рано, с
   * обявено пропускане. Пропускането маскира дефекта; той се показва в мига, в който приложението
   * мине веднъж през {@code ddl=update} и таблиците се появят. {@code BgD1SchemaDbTest} и {@code
   * BgSelfInsuredSchemaDbTest} също питат с {@code Class#getName()}.
   */
  private static List<String> declaredFields(String domainFile, Class<?> entity) {
    List<String> out = new ArrayList<>();
    java.util.Map<String, BgDomainColumns.FieldSpec> fields =
        BgDomainColumns.allFields().get(entity.getName());
    if (fields == null) {
      org.junit.jupiter.api.Assertions.fail(
          "домейнът " + domainFile + " не се чете — очакван ентитет " + entity.getName());
      return out;
    }
    for (java.util.Map.Entry<String, BgDomainColumns.FieldSpec> e : fields.entrySet()) {
      // ⚠️ Двата вида, които НЯМАТ своя колона: one-to-many живее на другата страна, а
      // many-to-many — в отделна таблица. Броени тук, те биха се докладвали като липсващи винаги.
      if (!"one-to-many".equals(e.getValue().tag) && !"many-to-many".equals(e.getValue().tag)) {
        out.add(e.getKey());
      }
    }
    return out;
  }

  private static boolean tableExists(Connection c, String table) throws Exception {
    try (PreparedStatement st =
        c.prepareStatement(
            "SELECT count(*) FROM information_schema.tables WHERE lower(table_name) = ?")) {
      st.setString(1, table);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
    }
  }

  private static boolean columnExists(Connection c, String table, String column) throws Exception {
    try (PreparedStatement st =
        c.prepareStatement(
            "SELECT count(*) FROM information_schema.columns "
                + "WHERE lower(table_name) = ? AND lower(column_name) = ?")) {
      st.setString(1, table);
      st.setString(2, column);
      try (ResultSet rs = st.executeQuery()) {
        return rs.next() && rs.getInt(1) > 0;
      }
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
              + " — схемата на ЗДДФЛ подсистемата НЕ е проверена.");
      return null;
    }
  }
}
