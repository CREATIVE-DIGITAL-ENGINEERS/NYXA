package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИМЕНАТА НА КОЛОНИТЕ В ОТЧЕТИТЕ СА <b>ИЗВЕДЕНИ</b>, А НЕ НАПИСАНИ — и този тест е причината да
 * може да се твърди.
 *
 * <p><b>КАПАНЪТ, ИЗМЕРЕН.</b> Axelor превежда име на поле в име на колона през {@code
 * PhysicalNamingStrategyImpl}, а правилото му НЕ е „главна буква → долна черта“: чертата се вмъква
 * САМО когато главната буква стои <b>МЕЖДУ ДВЕ МАЛКИ</b>. След ЦИФРА черта НЕ се слага. Тоест:
 *
 * <ul>
 *   <li>{@code t1CorrectionCode} → <b>{@code t1correction_code}</b>, а НЕ {@code
 *       t1_correction_code};
 *   <li>{@code bgD1Declaration} → <b>{@code bgd1declaration}</b>;
 *   <li>{@code t34NetPay} → <b>{@code t34net_pay}</b>;
 *   <li>{@code bgFillsT31} → <b>{@code bg_fillst31}</b> (главната „T“ е следвана от ЦИФРА);
 *   <li>но {@code t31aTax} → {@code t31a_tax}, защото там „T“ стои между „a“ и „a“.
 * </ul>
 *
 * <p>Тоест ЕДНА и същa наивна догадка дава верен резултат за едно поле и грешен за двайсет — и
 * разликата НЕ се вижда от кода: SQL се проверява само при РЕНДИРАНЕ на отчета, тоест когато човек
 * натисне „Печат“ и получи изключение. Отчет с ръчно написани имена може да носи десетки колони, от
 * които НИТО ЕДНА не съществува, и пак да изглежда наред.
 *
 * <p><b>Правилото, което този тест налага.</b> Всяко име, което заявката на отчет чете от таблица,
 * трябва да е ИЗВЕДЕНО от домейн XML-а през {@link BgDomainColumns#columnName} — същата функция,
 * която Hibernate ползва. Псевдонимите (частта след {@code as}) са свободни: те са имената, с които
 * BIRT връзките работят.
 *
 * <p><b>⚠️ И ТЕЧЕ БЕЗ БАЗА, НА ВСЯКА МАШИНА.</b> Това е нарочно: базовата половина („колоната
 * наистина съществува“) зависи от {@code ddl=update} и се ПРОПУСКА, когато приложението не е
 * стартирано. Ако единствената проверка е тя, дефектът остава невидим точно на машините, на които
 * се пише кодът.
 */
class BgReportQueryColumnTest {

  private static final String REPORTS = "src/main/resources/reports";

  /** Заявката на отчета, извадена от {@code <xml-property name="queryText">}. */
  private static final Pattern QUERY =
      Pattern.compile(
          "<xml-property\\s+name=\"queryText\">\\s*<!\\[CDATA\\[(.*?)]]>\\s*</xml-property>",
          Pattern.DOTALL);

  /**
   * Таблиците, чиито колони този тест може да изведе — тоест собствените на модула.
   *
   * <p>⚠️ Ключът е ИМЕТО НА ТАБЛИЦАТА, стойността — FQN на entity-то. Изведено от името на
   * entity-то би било трето копие на правилото за таблици; тук връзката е една и е видима.
   */
  private static final Map<String, String> TABLES = new LinkedHashMap<>();

  static {
    TABLES.put("bglocalization_bg_d1_record", "com.axelor.apps.bglocalization.db.BgD1Record");
    TABLES.put("bglocalization_bg_pay_slip", "com.axelor.apps.bglocalization.db.BgPaySlip");
    TABLES.put(
        "bglocalization_bg_pay_slip_line", "com.axelor.apps.bglocalization.db.BgPaySlipLine");
    TABLES.put(
        "bglocalization_bg_d6_declaration", "com.axelor.apps.bglocalization.db.BgD6Declaration");
    TABLES.put("bglocalization_bg_vat_return", "com.axelor.apps.bglocalization.db.BgVatReturn");
    TABLES.put(
        "bglocalization_bg_vat_sales_line", "com.axelor.apps.bglocalization.db.BgVatSalesLine");
    TABLES.put(
        "bglocalization_bg_vat_purchase_line",
        "com.axelor.apps.bglocalization.db.BgVatPurchaseLine");
    // ⚠️ ДРУГАТА СТРАНА НА ФИША. Всеки отчет над фишове, който иска лицето, минава и през
    // регистрацията: носителят е XOR — трудов договор ИЛИ регистрация на самоосигуряващ се, — и
    // заявка, която чете само договора, оставя всеки ред на СОЛ безименен.
    TABLES.put(
        "bglocalization_bg_self_insured_registration",
        "com.axelor.apps.bglocalization.db.BgSelfInsuredRegistration");
  }

  /**
   * ТАБЛИЦИТЕ НА ЧУЖДИ МОДУЛИ, В КОИТО ОТЧЕТ МОЖЕ ДА ВЛИЗА — <b>списък, а не мълчание</b>.
   *
   * <p><b>⚠️ ЗАЩО ВЪОБЩЕ СЪЩЕСТВУВА.</b> Печатният ФИШ носи личните данни като ПАРАМЕТРИ и няма
   * нужда от join — за ЕДИН човек това работи. Ведомостта е регистър на ДЕСЕТКИ души: имената им не
   * могат да пътуват като параметри и заявката ѝ влиза в {@code hr_employee} и {@code
   * base_partner}.
   *
   * <p><b>⚠️ КОЛОНИТЕ ИМ НЕ МОГАТ ДА СЕ ИЗВЕДАТ И ТОВА НЕ Е ПОПРАВИМО ТУК.</b> {@code
   * BgDomainColumns} чете домейните на ТОЗИ модул; {@code base_partner.first_name} е обявена в
   * axelor-base и нейният XML не е на този classpath. Тоест за тях файловата половина не може да
   * твърди нищо — единственият честен свидетел е {@code BgReportQueryDbTest}, който ИЗПЪЛНЯВА
   * заявката срещу Postgres.
   *
   * <p><b>⚠️ И ЗАТОВА Е СПИСЪК, А НЕ „прескачай непознатите“.</b> Прескачането щеше да мълчи и за
   * сгрешено име на СОБСТВЕНА таблица — тоест точно дефектът, който този тест съществува да хване,
   * щеше да се промъкне през първия печатен грешен псевдоним. Списъкът иска НОВИЯТ чужд join да
   * бъде обявен тук съзнателно, с реда, който го въвежда.
   */
  private static final Set<String> FOREIGN =
      new LinkedHashSet<>(
          java.util.Arrays.asList(
              "hr_employee",
              "hr_employment_contract",
              "base_partner",
              "base_period",
              "base_company"));

  /** Колоните, които Axelor слага на ВСЯКО entity — те не се обявяват в домейн XML. */
  private static final Set<String> INHERITED =
      new LinkedHashSet<>(
          java.util.Arrays.asList(
              "id",
              "version",
              "archived",
              "attrs",
              "created_on",
              "updated_on",
              "created_by",
              "updated_by",
              "import_id",
              "import_origin",
              "process_instance_id",
              "sequence",
              "name",
              "note",
              "code"));

  @Test
  @DisplayName("⚠️ всяко име на колона в заявка на отчет СЪЩЕСТВУВА, изведено през naming strategy")
  void everyQueriedColumnExists() throws IOException {
    List<String> missing = new ArrayList<>();
    int checked = 0;

    for (Path design : designs()) {
      String text = new String(Files.readAllBytes(design), StandardCharsets.UTF_8);
      Matcher m = QUERY.matcher(text);
      while (m.find()) {
        String query = m.group(1);
        String table = tableOf(query);
        if (table == null) {
          missing.add(
              design.getFileName()
                  + ": заявката няма разпознаваем „from <таблица>“ — тестът не може да я провери, а"
                  + " непроверена заявка е точно състоянието, което той съществува да предотврати");
          continue;
        }
        String entity = TABLES.get(table);
        if (entity == null) {
          missing.add(
              design.getFileName()
                  + ": таблица „"
                  + table
                  + "“ не е в списъка TABLES на този тест. ⚠️ Допълни го — иначе новият отчет остава"
                  + " непроверен и наивно написаните имена минават зелени.");
          continue;
        }
        Set<String> known = columnsOf(entity);
        for (String column : queriedColumns(query)) {
          checked++;
          if (!known.contains(column) && !INHERITED.contains(column)) {
            missing.add(
                design.getFileName()
                    + " · "
                    + table
                    + ": колона „"
                    + column
                    + "“ НЕ съществува. Изведените имена на това entity: "
                    + new TreeSet<>(known));
          }
        }

        // ⚠️ ВТОРАТА ПОЛОВИНА: заявките с ПСЕВДОНИМИ на таблици. Разборът отгоре чете само голи
        // имена ({@code [a-z0-9_]+}) — тоест „person.first_name“ не му прилича на колона и се
        // ПРОПУСКА. За заявка с join това значи, че тестът минава зелен, без да е проверил нито
        // едно име: точно състоянието, което той съществува да предотврати, само че по-тихо.
        Map<String, String> aliasTables = aliases(query);
        for (String[] reference : qualifiedColumns(query)) {
          String alias = reference[0];
          String column = reference[1];
          String aliasTable = aliasTables.get(alias);

          if (aliasTable == null) {
            missing.add(
                design.getFileName()
                    + ": псевдонимът „"
                    + alias
                    + "“ (в „"
                    + alias
                    + "."
                    + column
                    + "“) не е обявен нито като таблица, нито като подзаявка — тоест заявката чете"
                    + " нещо, което този тест не може да проследи");
            continue;
          }
          if (DERIVED.equals(aliasTable) || FOREIGN.contains(aliasTable)) {
            // ⚠️ Изчислените колони на подзаявка и колоните на чужд модул нямат домейн ТУК.
            // Свидетелят за тях е BgReportQueryDbTest, който изпълнява заявката.
            continue;
          }
          String aliasEntity = TABLES.get(aliasTable);
          if (aliasEntity == null) {
            missing.add(
                design.getFileName()
                    + ": таблица „"
                    + aliasTable
                    + "“ (псевдоним „"
                    + alias
                    + "“) не е нито в TABLES, нито в FOREIGN. ⚠️ Допълни единия от двата — иначе"
                    + " новият join остава непроверен и наивно написаните имена минават зелени.");
            continue;
          }
          checked++;
          Set<String> aliasKnown = columnsOf(aliasEntity);
          if (!aliasKnown.contains(column) && !INHERITED.contains(column)) {
            missing.add(
                design.getFileName()
                    + " · "
                    + aliasTable
                    + ": колона „"
                    + column
                    + "“ (като „"
                    + alias
                    + "."
                    + column
                    + "“) НЕ съществува. Изведените имена на това entity: "
                    + new TreeSet<>(aliasKnown));
          }
        }
      }
    }

    assertTrue(checked > 0, "нито едно име не беше проверено — тестът не намери заявки в отчетите");
    assertTrue(
        missing.isEmpty(),
        "⚠️ Отчет със сгрешено име на колона НЕ пада при компилация и НЕ пада в никой друг тест: SQL"
            + " се проверява само при РЕНДИРАНЕ, тоест когато човек натисне „Печат“. Правилото на"
            + " Axelor: долна черта САМО когато главната буква стои МЕЖДУ ДВЕ МАЛКИ (след цифра"
            + " НЕ се слага). Виж класовия javadoc за петте измерени примера.\n  "
            + String.join("\n  ", missing));
  }

  @Test
  @DisplayName("⚠️ и ОБРАТНАТА посока: наивното „t1_correction_code“ НЕ е валидно име")
  void theNaiveGuessIsProvablyWrong() {
    // Този свидетел не проверява отчет — той закрепва самото ПРАВИЛО, за да не се „поправи“
    // обратно.
    assertTrue(
        columnsOf("com.axelor.apps.bglocalization.db.BgD1Record").contains("t1correction_code"),
        "изведеното име на t1CorrectionCode е t1correction_code");
    assertFalse(
        columnsOf("com.axelor.apps.bglocalization.db.BgD1Record").contains("t1_correction_code"),
        "⚠️ ТОВА Е НАИВНАТА ДОГАДКА. Ако този ред падне, значи naming strategy-то на"
            + " платформата се е сменило — тогава се сменят заявките, а не тестът.");
    assertTrue(
        columnsOf("com.axelor.apps.bglocalization.db.BgD1Record").contains("t31a_tax"),
        "⚠️ И ЕДНО, КОЕТО НАИВНАТА ДОГАДКА ПОГАЖДА: при t31aTax главната „T“ стои между „a“ и „a“,"
            + " тоест чертата СЕ слага. Едно вярно от двайсет и две е най-лошото възможно"
            + " съотношение — то прави правилото да изглежда разбрано.");
  }

  /**
   * ⚠️ ЗАПОМНЕНО, ЗАЩОТО {@link BgDomainColumns#allFields} ПРЕПРОЧИТА ВСИЧКИ ДОМЕЙН XML-И ПРИ ВСЯКО
   * ИЗВИКВАНЕ. Докато въпросът се задаваше по веднъж на ЗАЯВКА, това не личеше; проверката на
   * псевдонимите го задава по веднъж на КОЛОНА и същият тест мина от секунди към минути.
   */
  private static final Map<String, Set<String>> COLUMNS_CACHE = new LinkedHashMap<>();

  /** Имената на колоните на едно entity, изведени от домейна. */
  private static Set<String> columnsOf(String entity) {
    Set<String> cached = COLUMNS_CACHE.get(entity);
    if (cached != null) {
      return cached;
    }
    Set<String> computed = readColumnsOf(entity);
    COLUMNS_CACHE.put(entity, computed);
    return computed;
  }

  /**
   * ⚠️ И САМИЯТ ПРОЧИТ НА ДОМЕЙНИТЕ СЕ ПРАВИ ВЕДНЪЖ. {@link BgDomainColumns#allFields} разбира
   * ВСЕКИ домейн XML на репото при всяко извикване — измерено, около три секунди. Един прочит на
   * entity прави теста линеен в броя РАЗЛИЧНИ таблици, вместо в броя проверени имена.
   */
  private static Map<String, Map<String, BgDomainColumns.FieldSpec>> allFields;

  private static Set<String> readColumnsOf(String entity) {
    if (allFields == null) {
      allFields = BgDomainColumns.allFields();
    }
    Map<String, Map<String, BgDomainColumns.FieldSpec>> all = allFields;
    Map<String, BgDomainColumns.FieldSpec> fields = all.get(entity);
    Set<String> out = new LinkedHashSet<>();
    if (fields == null) {
      return out;
    }
    for (String field : fields.keySet()) {
      out.add(BgDomainColumns.columnName(field));
    }
    return out;
  }

  /** Името на таблицата от {@code from <таблица>}, или {@code null}. */
  static String tableOf(String query) {
    Matcher m = Pattern.compile("\\bfrom\\s+([a-z0-9_]+)", Pattern.CASE_INSENSITIVE).matcher(query);
    return m.find() ? m.group(1) : null;
  }

  /** Стойността в картата на псевдонимите за „това е подзаявка, не таблица“. */
  static final String DERIVED = "(подзаявка)";

  /**
   * ПСЕВДОНИМ → ТАБЛИЦА за една заявка.
   *
   * <p>Чете двете форми, с които заявките на този модул са написани: {@code from <таблица> as
   * <псевдоним>} / {@code join <таблица> as <псевдоним>}, и {@code ) as <псевдоним> on} за
   * подзаявка.
   *
   * <p>⚠️ ПОДЗАЯВКИТЕ СЕ ЗАПИСВАТ ИЗРИЧНО, А НЕ СЕ ПРЕМЪЛЧАВАТ. Ако непознатият псевдоним просто се
   * прескачаше, печатна грешка в псевдоним („perosn.first_name“) щеше да изглежда точно като
   * подзаявка — тоест да мине зелена.
   */
  static Map<String, String> aliases(String query) {
    Map<String, String> out = new LinkedHashMap<>();
    String body = query.replace("\n", " ").replace("\r", " ");

    Matcher table =
        Pattern.compile(
                "\\b(?:from|join)\\s+([a-z0-9_]+)\\s+as\\s+([a-z0-9_]+)\\b",
                Pattern.CASE_INSENSITIVE)
            .matcher(body);
    while (table.find()) {
      out.put(table.group(2), table.group(1));
    }

    Matcher derived =
        Pattern.compile("\\)\\s+as\\s+([a-z0-9_]+)\\s+on\\b", Pattern.CASE_INSENSITIVE)
            .matcher(body);
    while (derived.find()) {
      out.put(derived.group(1), DERIVED);
    }
    return out;
  }

  /**
   * Всяка препратка от вида {@code <псевдоним>.<колона>} в цялата заявка.
   *
   * <p>⚠️ ЧЕТЕ СЕ ЦЕЛИЯТ ТЕКСТ, а не само списъкът след {@code select} — заявка с join носи имена
   * на колони и в {@code on}, и в {@code where}, и вътре в подзаявките, и сгрешено име на всяко от
   * тези места пада по един и същи начин: при натискане на „Печат“.
   *
   * <p><b>⚠️ И ГЛАВНИТЕ БУКВИ СЕ ЧЕТАТ, МАКАР ВАЛИДНО ИМЕ НА КОЛОНА ДА НЯМА ТАКИВА.</b> Първата
   * редакция на този разбор искаше само малки букви — и с това ИЗТРИВАШЕ от полезрението си точно
   * най-честата грешка: написаното наготово {@code slip.bgWorkedDays}. Тогава {@code \b} не
   * съвпадаше никъде в него, препратката просто не се броеше за препратка и тестът минаваше зелен
   * върху заявка, която при „Печат“ хвърля. Прочетена, тя пада тук, с изведеното име до нея.
   */
  static List<String[]> qualifiedColumns(String query) {
    List<String[]> out = new ArrayList<>();
    Matcher m =
        Pattern.compile("\\b([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z_][A-Za-z0-9_]*)\\b")
            .matcher(query.replace("\n", " ").replace("\r", " "));
    while (m.find()) {
      out.add(new String[] {m.group(1), m.group(2)});
    }
    return out;
  }

  /**
   * Имената на колоните, които заявката НАИСТИНА чете — тоест лявата страна на всяко {@code as} от
   * списъка след {@code select}, плюс имената в {@code where} и {@code order by}.
   *
   * <p>⚠️ ПСЕВДОНИМИТЕ СЕ ИЗХВЪРЛЯТ. Те са имената, с които BIRT връзките работят, и НЕ са колони —
   * проверени като колони, те щяха да дадат лъжливо червено за всеки нарочно преименуван изход.
   *
   * <p>⚠️ И цитираните имена ({@code "sequence"}) минават без кавичките: те са колони, оградени
   * заради ключова дума, а не низове.
   */
  static List<String> queriedColumns(String query) {
    List<String> out = new ArrayList<>();
    String body = query.replace("\n", " ").replace("\r", " ");

    Matcher select =
        Pattern.compile("select\\s+(.*?)\\s+from\\s", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(body);
    if (select.find()) {
      for (String item : select.group(1).split(",")) {
        String left = item.trim();
        int as = left.toLowerCase(java.util.Locale.ROOT).indexOf(" as ");
        if (as >= 0) {
          left = left.substring(0, as).trim();
        }
        left = left.replace("\"", "").trim();
        if (!left.isEmpty() && left.matches("[a-z0-9_]+")) {
          out.add(left);
        }
      }
    }

    Matcher where =
        Pattern.compile("where\\s+([a-z0-9_\"]+)\\s*=", Pattern.CASE_INSENSITIVE).matcher(body);
    while (where.find()) {
      out.add(where.group(1).replace("\"", ""));
    }

    Matcher order =
        Pattern.compile("order\\s+by\\s+(.*)$", Pattern.CASE_INSENSITIVE | Pattern.DOTALL)
            .matcher(body);
    if (order.find()) {
      for (String item : order.group(1).split(",")) {
        String name = item.trim().replace("\"", "").replaceAll("\\s+(asc|desc)$", "").trim();
        if (!name.isEmpty() && name.matches("[a-z0-9_]+")) {
          out.add(name);
        }
      }
    }
    return out;
  }

  private static List<Path> designs() throws IOException {
    Path dir = BgDomainColumns.moduleRoot().resolve(REPORTS);
    try (Stream<Path> paths = Files.list(dir)) {
      List<Path> out = new ArrayList<>();
      paths.filter(p -> p.getFileName().toString().endsWith(".rptdesign")).forEach(out::add);
      out.sort(Path::compareTo);
      return out;
    }
  }
}
