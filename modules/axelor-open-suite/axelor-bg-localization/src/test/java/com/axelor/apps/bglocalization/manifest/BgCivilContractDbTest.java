package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
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
 * ДВАТА НОВИ ВХОДА НА ГРАЖДАНСКИЯ ДОГОВОР — базовата половина: „стигнаха ли до схемата и какво пише
 * по ДЕЙСТВИТЕЛНИТЕ договори“.
 *
 * <p><b>Защо изобщо база.</b> Файловата половина ({@code BgCivilContractResolverTest}, {@code
 * BgGdRecordTest}) пита дали ПРАВИЛОТО е вярно и тече без Postgres. Тя обаче не може да знае това,
 * което само базата знае: че по договор с вид осигурен 14 стои празен вид доход — вход, чиято липса
 * по КСО чл. 6 ал. 4 е ОТКАЗ, тоест фиш, който не се сглобява.
 *
 * <p><b>Пропуска се при ДВЕ състояния на средата, всяко с обявена причина</b>: няма достъпна база,
 * или колоните още ги няма. Второто е нормалното състояние веднага след този клон: колоните идват
 * от {@code ddl=update} при първото пускане на приложението с новия домейн, а миграционен механизъм
 * модулът няма. Липсваща колона е състояние на СРЕДАТА, не дефект — затова тук се пропуска с
 * причина, вместо да се чака зелено, което не значи нищо.
 *
 * <p><b>⚠️ ПРАЗНА ТАБЛИЦА НЕ Е ПОВОД ЗА ПРОПУСКАНЕ.</b> Нула граждански договора е валидно
 * състояние и твърдението „нито един ГД не е без вид доход“ е вярно за него. Пропуснат заради
 * празнота, тестът щеше да мълчи точно докато някой въвежда първия договор.
 *
 * <p><b>⚠️ И ЕДНО, КОЕТО ТОЗИ ТЕСТ НАРОЧНО НЕ ПРОВЕРЯВА: реда IC-335-2026.</b> Структурната нула за
 * фонд ТЗПБ при код 14 е нов ред в {@code bg_insurance_contribution.csv} и стига до общата база
 * само през App Install, който този клон НЕ пуска. Твърдение за него тук би било червено, което
 * значи „не е пускан инсталаторът“, а не „кодът е грешен“ — и щеше да учи да не се гледа червено.
 * Файловата му половина е {@code BgGdRecordTest#theProfileChargesExactlyThreeFunds}.
 */
class BgCivilContractDbTest {

  private static final String T = "hr_employment_contract";

  /** Видът доход по ЗДДФЛ — носителят на разходната ставка по чл. 29. */
  private static final String COL_INCOME_TYPE = BgDomainColumns.columnName("bgIncomeType");

  /** Разграничителят между т. 5 и т. 6 на КСО чл. 4 ал. 3. */
  private static final String COL_OTHER_GROUND =
      BgDomainColumns.columnName("bgInsuredOnOtherGround");

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
              + " — твърдението „гражданските договори носят вид доход“ НЕ е проверено."
              + " Файловата половина (BgCivilContractResolverTest) тече и без база.");
      return null;
    }
  }

  private static void requireColumn(Connection c, String column) throws Exception {
    String found =
        one(
            c,
            "SELECT count(*) FROM information_schema.columns"
                + " WHERE table_name = '"
                + T
                + "' AND column_name = '"
                + column
                + "'");
    if ("0".equals(found)) {
      BgSkip.because(
          "колоната "
              + T
              + "."
              + column
              + " още я НЯМА — тоест ddl=update не е минавал с новия домейн. Това е състояние на"
              + " средата, не на кода: няма какво да се сверява, защото полето още не съществува."
              + " ⚠️ Името е ИЗВЕДЕНО от домейна през BgDomainColumns.columnName, а не преписано на"
              + " ръка: конвенцията на Axelor не слага долна черта след ЦИФРА и една догадка на"
              + " всеки двайсет полета е грешна — а грешното име тук би пропускало теста ЗАВИНАГИ,"
              + " с напълно правдоподобна причина.");
    }
  }

  @Test
  @DisplayName("СХЕМА · двете колони на гражданския договор съществуват и са на договора")
  void theTwoColumnsExist() throws Exception {
    try (Connection c = open()) {
      requireColumn(c, COL_INCOME_TYPE);
      requireColumn(c, COL_OTHER_GROUND);
    }
  }

  @Test
  @DisplayName("БАЗА · нито един договор с вид осигурен 14 не е без вид доход по ЗДДФЛ")
  void everyCivilContractCarriesAnIncomeType() throws Exception {
    try (Connection c = open()) {
      requireColumn(c, COL_INCOME_TYPE);

      List<String> without =
          query(
              c,
              "SELECT id, btrim(coalesce(bg_contract_type, '')) FROM "
                  + T
                  + " WHERE btrim(coalesce(bg_insured_type_code, '')) = '14'"
                  + " AND "
                  + COL_INCOME_TYPE
                  + " IS NULL ORDER BY id");

      if (!without.isEmpty()) {
        fail(
            "ГРАЖДАНСКИ ДОГОВОР БЕЗ ВИД ДОХОД ПО ЗДДФЛ ("
                + without.size()
                + " договор(а)): "
                + String.join(", ", without)
                + "\n  ⚠️ Фишът по такъв договор НЕ СЕ СГЛОБЯВА и това е нарочно. КСО чл. 6 ал. 4"
                + " дължи вноските върху възнаграждението СЛЕД намаляването му с разходите за"
                + " дейността, „определени по реда на ЗДДФЛ“ — без ставката базата излиза"
                + " ЗАВИШЕНА (пълното възнаграждение вместо намаленото), а не непълна. Отказът с"
                + " назована причина е единственият от двата изхода, който се вижда."
                + "\n  Поправката е на ДАННИТЕ: изберете кода по чл. 73 на договора — за обичайния"
                + " договор за услуга това са 307x (чл. 29 ал. 1 т. 3, 25 на сто).");
      }
    }
  }

  @Test
  @DisplayName("БАЗА · признакът за друго основание не е вдиган по договор, който не е ГД")
  void onlyCivilContractsCarryTheOtherGroundFlag() throws Exception {
    try (Connection c = open()) {
      requireColumn(c, COL_OTHER_GROUND);

      List<String> stray =
          query(
              c,
              "SELECT id, btrim(coalesce(bg_insured_type_code, '(празен)')) FROM "
                  + T
                  + " WHERE "
                  + COL_OTHER_GROUND
                  + " = true"
                  + " AND btrim(coalesce(bg_insured_type_code, '')) <> '14' ORDER BY id");

      if (!stray.isEmpty()) {
        fail(
            "ПРИЗНАКЪТ „осигурен на друго основание“ Е ВДИГНАТ ИЗВЪН КОД 14 ("
                + stray.size()
                + "): "
                + String.join(", ", stray)
                + "\n  ⚠️ Днес той не мени НИЩО за тези договори — прочита се само по пътя на"
                + " гражданския договор — и точно затова е находка, а не грешка: стойност, която"
                + " никой не чете, изглежда като решение и утре някой ще я вземе за такова."
                + " Разграничението т. 5 / т. 6 съществува само за лицата по КСО чл. 4 ал. 3;"
                + " наетият по чл. 4 ал. 1 се осигурява независимо от това дали е осигурен и"
                + " другаде."
                + "\n  ⚠️ Обратната посока НЕ се проверява и това е нарочно: НЕвдигнатият признак"
                + " по договор с код 14 е ВАЛИДНО състояние — то значи „лицето не е осигурено"
                + " другаде“, тоест прагът по т. 5 важи. Заварените редове носят NULL, който"
                + " генерираният getter връща като FALSE, и това е същият отговор.");
      }
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
}
