package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * СТРУКТУРАТА на ретро-корекциите и заличаванията (WS-3) — файловата половина: „вярно ли е
 * ЗАПИСАНО“.
 *
 * <p><b>Какво пази.</b> Работният поток на корекцията се крепи на ЧЕТИРИ структурни неща и всяко от
 * тях се разминава мълчаливо:
 *
 * <ol>
 *   <li><b>маркерът на перото</b> — {@code BgPaySlipLine.bgCorrectsPeriod}. Той е единственото, по
 *       което делта-редът в майския фиш се различава от собствените му редове. Липсва ли, делтата
 *       влиза в МАЙСКИТЕ Д1/Д6 и същият осигурителен доход се декларира ДВА пъти — веднъж в
 *       коригиращата декларация за март и втори път в майската;
 *   <li><b>четирите признака на фиша</b> — {@code bgIsCorrection}/{@code bgCorrectionOf} и {@code
 *       bgIsDeletion}/{@code bgDeletionOf}. Без тях коригиращият фиш е неразличим от втори редовен
 *       фиш за същия месец, а такъв ПЛАЩА — тоест лицето получава пълните нови числа вместо
 *       разликата;
 *   <li><b>миграцията</b> — колоните трябва да стигнат до заварена база. {@code ddl=update} върху
 *       чужда среда не е обещание на модула;
 *   <li><b>перото на делтата</b> — тип ред с флагове „НЕ е осигурителен доход“ и „извън данъчната
 *       основа“. Вдигнат кой да е от двата флага облага и осигурява РАЗЛИКАТА втори път, докато
 *       вноските и данъкът за март вече седят в коригиращия мартенски фиш.
 * </ol>
 *
 * <p><b>Защо е файлов тест, а не базов.</b> Тече ВИНАГИ, на всяка машина, без Postgres и без App
 * Install — тоест червеното му значи „кодът е сбъркан“, а не „средата не е заредена“. Базовата
 * половина за речника вече съществува ({@code BgPaySlipLineTypeManifestTest}) и отговаря на другия
 * въпрос: „доставено ли е“.
 *
 * <p><b>Нула поведение.</b> Тук се мери СТРУКТУРА. Пазачът на заключен период, {@code correct()},
 * {@code voidRecord()} и срокът 30.04 идват след това и си носят собствените свидетели.
 */
class BgRetroCorrectionModelTest {

  private static final String LINE_ENTITY = "com.axelor.apps.bglocalization.db.BgPaySlipLine";
  private static final String SLIP_ENTITY = "com.axelor.apps.bglocalization.db.BgPaySlip";
  private static final String PERIOD_REF = "com.axelor.apps.base.db.Period";

  private static final Path LINE_DOMAIN = Paths.get("src/main/resources/domains/BgPaySlipLine.xml");
  private static final Path SLIP_DOMAIN = Paths.get("src/main/resources/domains/BgPaySlip.xml");
  private static final Path MIGRATION =
      Paths.get("src/main/resources/db/migration/2026-08-20-bg-retro-corrections.sql");
  private static final Path MANIFEST =
      Paths.get("src/main/resources/apps/init-data/manifest/payslip-line-type.yml");

  /**
   * ЧЕТИРИТЕ CSV-та, от които се сглобява речникът с редове на фиша.
   *
   * <p>⚠️ Групата 2xx живее в {@code bg_pay_slip_line_type_social.csv} — четиринайсетте пера ПОД
   * КАПАЧКА са отделно, защото носят FK колона, точно както 5xx и 9xx. Пропуснат тук, той прави
   * броячите отдолу да мерят 88 реда срещу манифестните 102 и червеното сочи МАНИФЕСТА, при
   * положение че сгрешен е СПИСЪКЪТ.
   *
   * <p>Оттам следва и правилото: НОВ ФАЙЛ В РЕЧНИКА СЕ ДОБАВЯ И ТУК. Списъкът е единственото място,
   * което казва кои файлове съставят речника, и е нарочно закован, а не намерен с glob: файл, който
   * никой не е регистрирал, трябва да се обади, а не да се преброи мълчаливо.
   */
  private static final List<String> SEEDS =
      Arrays.asList(
          "bg_pay_slip_line_type.csv",
          "bg_pay_slip_line_type_compensation.csv",
          "bg_pay_slip_line_type_contribution.csv",
          "bg_pay_slip_line_type_social.csv");

  /** Перото на нетната делта — стабилният ключ и позицията му в групата на плащанията. */
  static final String DELTA_CODE = "pl_korekcia_minal_period";

  static final String DELTA_NUMBER = "620";

  /**
   * ⚠️ ТАБЛИЦАТА И КОЛОНАТА, ПО ЕДНА ЗА ВСЯКО НОВО ПОЛЕ. Физическите имена НЕ се пишат на ръка —
   * извеждат се през същата стратегия, която ползва Hibernate ({@link BgDomainColumns#columnName}).
   * Ръчно написаното правило „главна буква → долна черта“ вече е докладвало собствената си грешка
   * като находка веднъж.
   */
  private static final Map<String, List<String>> NEW_FIELDS = new LinkedHashMap<>();

  static {
    NEW_FIELDS.put("bglocalization_bg_pay_slip_line", Arrays.asList("bgCorrectsPeriod"));
    NEW_FIELDS.put(
        "bglocalization_bg_pay_slip",
        Arrays.asList("bgCorrectionOf", "bgIsCorrection", "bgDeletionOf", "bgIsDeletion"));
  }

  private static String read(Path file) throws IOException {
    if (!Files.isRegularFile(file)) {
      fail("липсва " + file.toAbsolutePath());
    }
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  private static BgDomainColumns.FieldSpec field(String entity, String name) {
    Map<String, Map<String, BgDomainColumns.FieldSpec>> all = BgDomainColumns.allFields();
    Map<String, BgDomainColumns.FieldSpec> fields = all.get(entity);
    if (fields == null) {
      fail("домейнът не обявява " + entity + " изобщо — има: " + all.keySet());
    }
    BgDomainColumns.FieldSpec spec = fields.get(name);
    if (spec == null) {
      fail(entity + " няма поле „" + name + "“ — има: " + new java.util.TreeSet<>(fields.keySet()));
    }
    return spec;
  }

  // ── 1 · маркерът на перото ──────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ Перото носи bgCorrectsPeriod — m2o към Period и НЕЗАДЪЛЖИТЕЛЕН")
  void theLineCarriesTheCorrectsPeriodMarker() throws IOException {
    BgDomainColumns.FieldSpec spec = field(LINE_ENTITY, "bgCorrectsPeriod");

    assertEquals(
        "many-to-one",
        spec.tag,
        "bgCorrectsPeriod трябва да сочи ПЕРИОД, а не да го описва с дата или низ: маркерът се чете"
            + " от агрегатите на Д1/Д6 със сравнение по запис, не по текст");
    assertTrue(
        !spec.required,
        "bgCorrectsPeriod НЕ бива да е required — празното поле значи „перото е за собствения си"
            + " период“, тоест всеки обикновен ред на всеки обикновен фиш. Задължително, то би"
            + " поискало период на всяко перо в модула.");

    String domain = read(LINE_DOMAIN);
    assertTrue(
        Pattern.compile(
                "<many-to-one\\s+name=\"bgCorrectsPeriod\"[^>]*?ref=\""
                    + Pattern.quote(PERIOD_REF)
                    + "\"",
                Pattern.DOTALL)
            .matcher(domain)
            .find(),
        "bgCorrectsPeriod трябва да сочи точно "
            + PERIOD_REF
            + " — стандартният период на Axelor. Собствен ентитет за заплатен период НЕ се създава"
            + " (виж коментара в BgPaySlip.xml).");
  }

  // ── 2 · четирите признака на фиша ───────────────────────────────────────

  @Test
  @DisplayName("⚠️ Фишът носи двете двойки признаци — корекция и заличаване, симетрично")
  void theSlipCarriesBothPairsOfMarkers() throws IOException {
    String domain = read(SLIP_DOMAIN);

    for (String reference : Arrays.asList("bgCorrectionOf", "bgDeletionOf")) {
      BgDomainColumns.FieldSpec spec = field(SLIP_ENTITY, reference);
      assertEquals(
          "many-to-one",
          spec.tag,
          reference + " сочи ДРУГ ФИШ — препратка, не низ с номер: номерът се сменя, записът не");
      assertTrue(
          !spec.required,
          reference
              + " НЕ бива да е required — редовният фиш не коригира и не заличава нищо, тоест"
              + " задължително поле би поискало препратка от всеки фиш в модула");
      assertTrue(
          Pattern.compile(
                  "<many-to-one\\s+name=\""
                      + reference
                      + "\"[^>]*?ref=\""
                      + Pattern.quote(SLIP_ENTITY)
                      + "\"",
                  Pattern.DOTALL)
              .matcher(domain)
              .find(),
          reference + " трябва да сочи " + SLIP_ENTITY + ", инак одитната следа води в друг модел");
    }

    for (String flag : Arrays.asList("bgIsCorrection", "bgIsDeletion")) {
      BgDomainColumns.FieldSpec spec = field(SLIP_ENTITY, flag);
      assertEquals("boolean", spec.tag, flag + " е признак, не низ и не число");
      assertTrue(
          Pattern.compile(
                  "<boolean\\s+name=\"" + flag + "\"[^>]*?default=\"false\"", Pattern.DOTALL)
              .matcher(domain)
              .find(),
          "⚠️ "
              + flag
              + " трябва да е с default=\"false\" — ПОСОКАТА на подразбирането е самото правило:"
              + " обратната обявява всеки заварен и всеки нов фиш за коригиращ, а коригиращият НЕ"
              + " плаща. Тоест сгрешено подразбиране спира заплатите, вместо да ги сбърка с малко.");
    }
  }

  // ── 3 · миграцията ─────────────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ Миграцията покрива ВСЯКА нова колона и е ИДЕМПОТЕНТНА")
  void theMigrationCoversEveryNewColumnAndIsIdempotent() throws IOException {
    String sql = read(MIGRATION);

    assertTrue(
        sql.contains("BEGIN;") && sql.contains("COMMIT;"),
        "миграцията трябва да е в една транзакция — частично приложена схема е по-лоша от"
            + " неприложена: тя изглежда като приложена");

    for (Map.Entry<String, List<String>> table : NEW_FIELDS.entrySet()) {
      assertTrue(
          sql.contains(table.getKey()),
          "миграцията не споменава таблица " + table.getKey() + " — колоните ѝ няма да пристигнат");
      for (String declared : table.getValue()) {
        String column = BgDomainColumns.columnName(declared);
        assertTrue(
            Pattern.compile(
                    "ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+" + column + "\\b",
                    Pattern.CASE_INSENSITIVE)
                .matcher(sql)
                .find(),
            "миграцията не добавя "
                + table.getKey()
                + "."
                + column
                + " с ADD COLUMN IF NOT EXISTS — или колоната липсва, или добавянето не е"
                + " идемпотентно и второто пускане пада");
      }
    }

    // ⚠️ Едно голо ADD COLUMN стига, за да не може файлът да се пусне втори път — а той се пуска на
    // ръка, върху среди, за които никой не помни дали вече е минал.
    Matcher bare =
        Pattern.compile("ADD\\s+COLUMN\\s+(?!IF\\s+NOT\\s+EXISTS)", Pattern.CASE_INSENSITIVE)
            .matcher(sql);
    if (bare.find()) {
      fail(
          "в миграцията има ADD COLUMN без IF NOT EXISTS (позиция "
              + bare.start()
              + ") — второто ѝ пускане ще падне и ще остави транзакцията отменена");
    }

    // Външните ключове: всеки ADD CONSTRAINT иска DROP CONSTRAINT IF EXISTS пред себе си — идиомът
    // на модула (виж ...-fund-fk-2-zaklyuchvane.sql).
    Matcher added =
        Pattern.compile("ADD\\s+CONSTRAINT\\s+(\\w+)", Pattern.CASE_INSENSITIVE).matcher(sql);
    Set<String> dropped = new LinkedHashSet<>();
    Matcher drops =
        Pattern.compile("DROP\\s+CONSTRAINT\\s+IF\\s+EXISTS\\s+(\\w+)", Pattern.CASE_INSENSITIVE)
            .matcher(sql);
    while (drops.find()) {
      dropped.add(drops.group(1));
    }
    while (added.find()) {
      assertTrue(
          dropped.contains(added.group(1)),
          "ограничението „"
              + added.group(1)
              + "“ се добавя без DROP CONSTRAINT IF EXISTS пред себе си — второто пускане на файла"
              + " пада с „already exists“");
    }
  }

  // ── 4 · перото на делтата ──────────────────────────────────────────────

  /** Редът на новото перо, прочетен от seed-а по КОД, а не по позиция. */
  private static Map<String, String> deltaRow() {
    List<String> header = BgSeed.header(SEEDS.get(0));
    for (String[] row : BgSeed.rows(SEEDS.get(0))) {
      if (DELTA_CODE.equals(BgSeed.col(header, row, "code"))) {
        Map<String, String> out = new TreeMap<>();
        for (String column : header) {
          out.put(column, BgSeed.col(header, row, column));
        }
        return out;
      }
    }
    fail(
        "в "
            + SEEDS.get(0)
            + " няма ред с код „"
            + DELTA_CODE
            + "“ — нетната делта към лицето няма перо, тоест корекцията няма как да влезе в текущия"
            + " фиш");
    return null;
  }

  @Test
  @DisplayName("⚠️ Перото на делтата е ИЗВЪН осигурителния доход И извън данъчната основа")
  void theDeltaLineTypeIsNeitherInsurableNorTaxable() {
    Map<String, String> row = deltaRow();

    assertEquals(
        "false",
        row.get("bgIsInsuranceIncome"),
        "⚠️ Делта-редът НЕ е осигурителен доход. Осигурителният доход за март вече е в КОРИГИРАЩИЯ"
            + " мартенски фиш и оттам в коригиращата Д1 за март. Вдигнат тук, същият доход се"
            + " декларира втори път в текущия месец — двойно осигуряване, което никъде не изглежда"
            + " като грешка.");
    assertEquals(
        "false",
        row.get("bgIsTaxable"),
        "⚠️ Делта-редът е ИЗВЪН данъчната основа. Данъкът за март е сметнат в коригиращия мартенски"
            + " фиш; облагаем тук, той се удържа втори път от същите пари.");
    assertEquals(
        "false",
        row.get("bgIsPermanentCharacter"),
        "делтата не е допълнително възнаграждение с постоянен характер по НСОРЗ чл. 15 — тя е"
            + " плащане по вече начислено и не влиза в базата за отпуск по КТ чл. 177 ал. 2");
    assertEquals(
        "PAYMENTS",
        row.get("bgGroup"),
        "⚠️ Групата трябва да е PAYMENTS (6xx). Тя е причината числата да излязат: BgPaySlipResolver"
            + " брои брутото само от 1xx и 9xx, а нетото — от всичко начислено извън 5xx. Тоест 6xx"
            + " ред мени НЕТОТО и не мени брутото, осигурителния доход и разхода за работодателя,"
            + " което е точно устройството на делтата.");
    assertEquals(
        DELTA_NUMBER,
        row.get("number"),
        "номерът трябва да е " + DELTA_NUMBER + " — свободна позиция в 6xx, между 610 и 700");
    assertEquals("true", row.get("active"), "перото трябва да е активно, инак не се избира");
    assertTrue(
        Arrays.asList("CREDIT", "DEBIT").contains(row.get("bgSign")),
        "посоката трябва да е обявена стойност от bg.payslip.line.sign.select, а е „"
            + row.get("bgSign")
            + "“");
    assertTrue(
        row.get("note") != null && row.get("note").contains("bgCorrectsPeriod"),
        "⚠️ Бележката на перото трябва да казва, че редът се маркира с bgCorrectsPeriod. Това е"
            + " ЕДИНСТВЕНОТО перо в речника, чиято ПОСОКА не е окончателна на типа: знакът ѝ следва"
            + " делтата. Изключение от правило на модула, което не е записано на самото място, се"
            + " чете като грешка и се „поправя“.");
  }

  @Test
  @DisplayName("⚠️ Кодът и номерът на новото перо са СВОБОДНИ и в трите файла на речника")
  void theNewCodeAndNumberAreFreeAcrossAllThreeSeeds() {
    Map<String, String> codes = new TreeMap<>();
    Map<String, String> numbers = new TreeMap<>();
    for (String seed : SEEDS) {
      List<String> header = BgSeed.header(seed);
      for (String[] row : BgSeed.rows(seed)) {
        String code = BgSeed.col(header, row, "code");
        String number = BgSeed.col(header, row, "number");
        String previousCode = codes.put(code, seed);
        String previousNumber = numbers.put(number, seed);
        if (previousCode != null) {
          fail("кодът „" + code + "“ се среща в " + previousCode + " и в " + seed);
        }
        if (previousNumber != null) {
          fail("номерът " + number + " се среща в " + previousNumber + " и в " + seed);
        }
      }
    }
    assertTrue(codes.containsKey(DELTA_CODE), "новото перо липсва изобщо");
    assertEquals(
        SEEDS.get(0),
        numbers.get(DELTA_NUMBER),
        "номер " + DELTA_NUMBER + " трябва да живее в ръчно писания файл, не в генериран");
  }

  // ── 5 · броячите ───────────────────────────────────────────────────────

  @Test
  @DisplayName("⚠️ Броячите на манифеста следват ФАЙЛОВЕТЕ — новото перо е преброено")
  @SuppressWarnings("unchecked")
  void theManifestCountersFollowTheSeedFiles() throws Exception {
    List<String[]> all = new ArrayList<>();
    Map<String, Integer> byGroup = new TreeMap<>();
    Set<String> codes = new LinkedHashSet<>();
    Set<String> numbers = new LinkedHashSet<>();
    for (String seed : SEEDS) {
      List<String> header = BgSeed.header(seed);
      for (String[] row : BgSeed.rows(seed)) {
        all.add(row);
        codes.add(BgSeed.col(header, row, "code"));
        numbers.add(BgSeed.col(header, row, "number"));
        byGroup.merge(BgSeed.col(header, row, "bgGroup"), 1, Integer::sum);
      }
    }

    Map<String, Object> manifest =
        new ObjectMapper(new YAMLFactory()).readValue(MANIFEST.toFile(), Map.class);
    Map<String, Object> expected = (Map<String, Object>) manifest.get("очаквано");
    if (expected == null) {
      fail("манифестът " + MANIFEST + " няма раздел „очаквано“");
    }

    // ⚠️ Един и същ брояч важи и за файла, и за базата. Разминат тук, той праща читателя да търси
    // непуснат App Install — а истината е, че никой не е обновил числото след новото перо.
    assertEquals(
        all.size(),
        ((Number) expected.get("редове_общо")).intValue(),
        "„редове_общо“ в манифеста не отговаря на трите CSV-та — базовият тест ще падне срещу вярно"
            + " заредена база и червеното ще сочи средата вместо манифеста");
    assertEquals(
        codes.size(),
        ((Number) expected.get("уникални_кодове")).intValue(),
        "„уникални_кодове“ се е разминал с файловете");
    assertEquals(
        numbers.size(),
        ((Number) expected.get("уникални_номера")).intValue(),
        "„уникални_номера“ се е разминал с файловете");

    Map<String, Object> groups = (Map<String, Object>) expected.get("по_група");
    for (Map.Entry<String, Integer> found : byGroup.entrySet()) {
      Object declared = groups.get(found.getKey());
      assertEquals(
          found.getValue().intValue(),
          declared == null ? -1 : ((Number) declared).intValue(),
          "група " + found.getKey() + " в манифеста не отговаря на файловете");
    }
  }
}
