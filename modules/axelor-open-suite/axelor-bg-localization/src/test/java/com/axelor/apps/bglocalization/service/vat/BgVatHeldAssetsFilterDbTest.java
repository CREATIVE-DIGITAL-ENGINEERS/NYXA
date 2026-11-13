package com.axelor.apps.bglocalization.service.vat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КАПАН 1 — двойното броене през ЗКПО чл. 64, проверено срещу ИСТИНСКАТА схема.
 *
 * <p>Чл. 64 ал. 1 ЗКПО завежда последващия разход като САМОСТОЯТЕЛЕН данъчен амортизируем актив
 * ({@code BgSubsequentCost.generatedFixedAsset}). За ЗДДС обаче носителят си остава на ЗАВАРЕНИЯ
 * имот — чл. 79а ал. 9 т. 3 и ППЗДДС чл. 67 ал. 4 говорят за срока на СЪЩИЯ актив. Ако породеният
 * бъде обходен като отделен актив, същият начислен данък влиза ДВА пъти в к.11 на дневника за
 * покупките и декларацията излиза удвоена — тихо, защото и двата реда изглеждат правдоподобно.
 *
 * <p><b>⚠️ КАКВО ТОЗИ ТЕСТ НЕ ПРАВИ.</b> Не кара {@code BgVatAnnualCorrectionService#heldAssets}
 * през JPA. Заявката минава през {@code JPA.em()}, който иска Guice и Hibernate — среда, която нито
 * един {@code *DbTest} в модула не вдига (същата уговорка е записана и в {@code
 * BgPayrollInputSourcesDbTest}). Затова JPQL-ът НЕ се преписва, а се <b>ПРЕВЕЖДА</b> от самата
 * константа {@link BgVatAnnualCorrectionService#HELD_ASSETS_FILTER} и преведеното се пуска срещу
 * базата.
 *
 * <p><b>Защо превод, а не втора заявка на ръка.</b> Преписана, SQL-версията щеше да остане зелена и
 * след като JPQL-ът смени смисъла си — точно видът тест, който пази себе си вместо кода. Преводът
 * стъпва на оригинала, а формата му е закована отделно в {@code
 * BgVatAnnualCorrectionCarrierTest#heldAssetsFilterIsExactlyAsExpected}: смени ли се филтърът,
 * първо пада онзи еталон, тъй че преводът не може да остане зелен върху остарял израз.
 *
 * <p><b>Какво пък доказва пускането срещу базата:</b> че колоните, които филтърът назовава,
 * СЪЩЕСТВУВАТ и че изразът е изпълним. Заявка срещу липсваща колона или сгрешена подзаявка е зелена
 * във всяка файлова проверка и пада чак в лицето на оператора.
 *
 * <p><b>Пропуска се с обявена причина</b> при две състояния на средата: няма достъпна база, или
 * таблиците още ги няма ({@code ddl=update} не е минавал с новия домейн). ⚠️ ПРАЗНА таблица НЕ е
 * повод за пропускане — нула породени актива е валидно състояние и твърденията остават верни за
 * него.
 */
class BgVatHeldAssetsFilterDbTest {

  private static final String FIXED_ASSET = "account_fixed_asset";
  private static final String SUBSEQUENT_COST = "bglocalization_bg_subsequent_cost";

  private static Connection open() {
    String url = BgTestDb.url();
    try {
      Class.forName("org.postgresql.Driver");
      return DriverManager.getConnection(url, BgTestDb.user(), BgTestDb.password());
    } catch (Exception e) {
      BgSkip.because(
          "няма достъпна база ("
              + url
              + "): "
              + e.getMessage()
              + " — изпълнимостта на филтъра на heldAssets() НЕ е проверена. Формата му остава"
              + " закована от BgVatAnnualCorrectionCarrierTest, който тече и без база.");
      return null;
    }
  }

  private static void requireTable(Connection connection, String table) throws Exception {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "SELECT count(*) FROM information_schema.tables"
                + " WHERE table_schema = 'public' AND table_name = ?")) {
      statement.setString(1, table);
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        if (rs.getInt(1) == 0) {
          BgSkip.because(
              "таблицата "
                  + table
                  + " още я няма — ddl=update не е минавал с този домейн. Капан 1 остава"
                  + " непроверен срещу схемата.");
        }
      }
    }
  }

  // ------------------------------------------------------------------ преводът

  private static final Pattern ALIASED_FIELD = Pattern.compile("\\b(self|c)\\.([A-Za-z0-9]+)");
  private static final Pattern NAMED_PARAM = Pattern.compile(":[A-Za-z][A-Za-z0-9]*");

  /**
   * JPQL → SQL за ТОЗИ филтър: тесен превод с обявена таблица на съответствията.
   *
   * <p>Нарочно не е общ преводач — общият би бил свой източник на грешки. Покрива точно четирите
   * хода, които този израз иска: entity → таблица, {@code = self} → външния ключ, {@code
   * alias.поле} → {@code alias.колона} (camelCase → snake_case, както Axelor именува), и именуван
   * параметър → позиционен.
   *
   * <p>⚠️ Ако утре филтърът поиска ход извън тези четири, преводът ще даде SQL, който базата ще
   * отхвърли — тоест провалът ще е шумен, а не мълчалив. Точно това се иска.
   */
  static String toSql(String jpql) {
    String sql = jpql.replace("BgSubsequentCost", SUBSEQUENT_COST).replace("= self)", "= fa.id)");
    Matcher matcher = ALIASED_FIELD.matcher(sql);
    StringBuffer out = new StringBuffer();
    while (matcher.find()) {
      String alias = "self".equals(matcher.group(1)) ? "fa" : matcher.group(1);
      matcher.appendReplacement(
          out, Matcher.quoteReplacement(alias + "." + snake(matcher.group(2))));
    }
    matcher.appendTail(out);
    return NAMED_PARAM.matcher(out.toString()).replaceAll("?");
  }

  /** camelCase → snake_case, както Axelor кръщава колоните. */
  static String snake(String name) {
    StringBuilder sb = new StringBuilder();
    for (char ch : name.toCharArray()) {
      if (Character.isUpperCase(ch)) {
        sb.append('_');
      }
      sb.append(ch);
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  @Test
  @DisplayName("Преводът JPQL → SQL дава точно очаквания израз")
  void translationIsExact() {
    assertEquals(
        "fa.company = ? AND fa.status_select IN (?, ?)"
            + " AND fa.bg_vat_asset_kind_select IS NOT NULL"
            + " AND NOT EXISTS (SELECT 1 FROM "
            + SUBSEQUENT_COST
            + " c WHERE c.generated_fixed_asset = fa.id)",
        toSql(BgVatAnnualCorrectionService.HELD_ASSETS_FILTER));
  }

  // ------------------------------------------------------------------ срещу базата

  @Test
  @DisplayName("Филтърът на heldAssets() е ИЗПЪЛНИМ срещу истинската схема")
  void filterRunsAgainstTheRealSchema() throws Exception {
    try (Connection connection = open()) {
      requireTable(connection, FIXED_ASSET);
      requireTable(connection, SUBSEQUENT_COST);

      String sql =
          "SELECT count(*) FROM "
              + FIXED_ASSET
              + " fa WHERE "
              + toSql(BgVatAnnualCorrectionService.HELD_ASSETS_FILTER);
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        // Кое дружество е без значение: тестът пита дали изразът РАБОТИ, не колко реда връща.
        statement.setLong(1, 0L);
        statement.setInt(2, FixedAssetRepository.STATUS_VALIDATED);
        statement.setInt(3, FixedAssetRepository.STATUS_DEPRECIATED);
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          // Нула е верният отговор за несъществуващо дружество — броят не е твърдението.
          rs.getLong(1);
        }
      } catch (Exception e) {
        fail(
            "филтърът на heldAssets() не е изпълним срещу схемата: "
                + e.getMessage()
                + "\nSQL: "
                + sql);
      }
    }
  }

  @Test
  @DisplayName("Огледалният филтър също е ИЗПЪЛНИМ — броячът на границите не бива да гърми")
  void mirrorFilterRunsAgainstTheRealSchema() throws Exception {
    try (Connection connection = open()) {
      requireTable(connection, FIXED_ASSET);
      requireTable(connection, SUBSEQUENT_COST);

      String sql =
          "SELECT count(*) FROM "
              + FIXED_ASSET
              + " fa WHERE "
              + toSql(BgVatAnnualCorrectionService.UNCLASSIFIED_ASSETS_FILTER);
      try (PreparedStatement statement = connection.prepareStatement(sql)) {
        statement.setLong(1, 0L);
        statement.setInt(2, FixedAssetRepository.STATUS_VALIDATED);
        statement.setInt(3, FixedAssetRepository.STATUS_DEPRECIATED);
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          rs.getLong(1);
        }
      } catch (Exception e) {
        // ⚠️ Този брояч се смята ВЪТРЕ в propose(). Неизпълним, той не дава грешно число, а поваля
        // цялото предлагане — тоест диагностиката би счупила потока, който обяснява.
        fail("огледалният филтър не е изпълним срещу схемата: " + e.getMessage() + "\nSQL: " + sql);
      }
    }
  }

  @Test
  @DisplayName("ЗКПО чл. 64 — нито един породен актив не носи ДДС класификация")
  void generatedAssetsAreNeverVatCarriers() throws Exception {
    try (Connection connection = open()) {
      requireTable(connection, FIXED_ASSET);
      requireTable(connection, SUBSEQUENT_COST);

      String sql =
          "SELECT count(*) FROM "
              + FIXED_ASSET
              + " fa WHERE fa.bg_vat_asset_kind_select IS NOT NULL"
              + " AND EXISTS (SELECT 1 FROM "
              + SUBSEQUENT_COST
              + " c WHERE c.generated_fixed_asset = fa.id)";
      try (PreparedStatement statement = connection.prepareStatement(sql);
          ResultSet rs = statement.executeQuery()) {
        rs.next();
        long offenders = rs.getLong(1);
        assertEquals(
            0L,
            offenders,
            "породеният по ЗКПО чл. 64 актив е ДДС-инертен по конструкция: BgSubsequentCostService"
                + " #applyArt64 изброява полетата, които сетва, и нито едно не е bgVat*. Намерен"
                + " такъв актив с попълнена ДДС класификация значи, че някой я е въвел през импорт"
                + " или REST — и тогава корекцията на подобрението би влязла в к.11 и по"
                + " заварения, и по породения.");
      }
    }
  }
}
