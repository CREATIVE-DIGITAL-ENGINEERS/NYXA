package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.common.csv.CSVFile;
import com.axelor.data.csv.CSVConfig;
import com.axelor.data.csv.CSVInput;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИНВАРИАНТ: seed ред пълни всяко ЧИСЛОВО поле с {@code min="N"}, N&gt;0 — пропусната колона не
 * оставя null, а НУЛА, и целият ред пада на валидация.
 *
 * <p><b>Дефектът, който го поражда.</b> {@code <integer name="requiredSize" min="1"/>} в домейна
 * прави ДВЕ неща наведнъж: слага {@code @Min(1)} И инициализира полето — {@code private Integer
 * requiredSize = 0;}. Bean Validation прескача {@code null}, но НЕ прескача нулата. Тоест колона,
 * която липсва в seed-а, не е „оставена неопределена“, а е ГАРАНТИРАН отказ.
 *
 * <p>Например ред в {@code base_registrationNumberTemplate.csv} без {@code requiredSize}, {@code
 * sirenPos} и {@code nicPos} пада с
 *
 * <pre>{@code
 * com.axelor.data.ImportException: Constraint violation error:
 *     - [requiredSize] - must be greater than or equal to 1
 *     - [sirenPos]     - must be greater than or equal to 1
 *     - [nicPos]       - must be greater than or equal to 1
 * }</pre>
 *
 * <p><b>Защо остава скрит.</b> {@code CSVImporter.run()} лови изключенията ПО ФАЙЛ и продължава със
 * следващия вход (CSVImporter:199-215). Синкът рапортува „Syncing data-init CSVs…“, съседните
 * входове минават, билдът е зелен — и един ред просто липсва, на всяка среда, завинаги. Другите
 * пазачи не могат да го видят по конструкция: CSV-то е безупречно по арност и свързване, а
 * нарушеното ограничение живее в ЧУЖД домейн XML (axelor-base).
 *
 * <p><b>⚠️ Защо само числовите.</b> {@code min} на НИЗ е дължина, а низовете нямат инициализатор —
 * остават null и валидацията ги подминава. Затова {@code base_country.csv} законно няма колони за
 * {@code alpha2Code}/{@code alpha3Code}/{@code numericCode}, макар и трите да са {@code min="2|3"}:
 * онзи вход пълни само връзката и НЕ бива да разширява обхвата си. Стесняването е точно по признака
 * „има ли не-null нулев инициализатор“, а той идва от типа.
 *
 * <p><b>⚠️ Какво този тест НЕ доказва.</b> Че редът стига до базата — проверява се семантиката на
 * CSV-то срещу домейна, без връзка. Че {@code 1} е ИНЕРТНА стойност за България (а не твърдение за
 * дължината на ЕИК) се твърди от коментара до самия вход в {@code bg-localization-config.xml}: и
 * двата ѝ четеца са гейтнати — {@code RegistrationNumberValidator} изобщо не се създава при празен
 * {@code validationMethodSelect}, а {@code checkSirenLegality}/{@code checkNicLegality} висят на
 * {@code useSiren}/{@code useNic}.
 */
class BgSeedMinConstraintTest {

  private static final Path INIT_DATA =
      BgDomainColumns.moduleRoot().resolve("src/main/resources/apps/init-data");
  private static final Path CONFIG = INIT_DATA.resolve("bg-localization-config.xml");

  /**
   * Таговете, на които генераторът дава НЕ-null нулев инициализатор ({@code = 0}, {@code = 0L},
   * {@code = BigDecimal.ZERO}). Само при тях пропуснатата колона поваля {@code @Min}.
   */
  private static final List<String> NUMERIC_TAGS = Arrays.asList("integer", "long", "decimal");

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ ИНВАРИАНТ: seed-ът пълни всяко числово поле с min>0 — нулата не се прескача")
  void everyNumericMinFieldIsFedBySeed() throws Exception {
    Map<String, Map<String, BgDomainColumns.FieldSpec>> declared = BgDomainColumns.allFields();
    assertTrue(declared.size() > 100, "домейн XML-ите не са прочетени: " + declared.size());

    CSVConfig config = CSVConfig.parse(CONFIG.toFile());
    List<CSVInput> inputs = config.getInputs();
    assertTrue(inputs != null && !inputs.isEmpty(), "конфигът не се парсна: " + CONFIG);

    Map<String, String> broken = new LinkedHashMap<>();
    int checked = 0;

    for (CSVInput input : inputs) {
      Map<String, BgDomainColumns.FieldSpec> fields = declared.get(input.getTypeName());
      if (fields == null) {
        continue; // липсващ домейн е грижа на BgImportBindingTest — там пада с име
      }
      if (input.isUpdate()) {
        // update="true" само обновява намерен ред и НИКОГА не създава (CSVBinder: ненамереният ред
        // се прескача). Капанът тук — нулевият инициализатор на НОВ ред — при такъв вход не
        // възниква: зареденият ред носи стойностите, които базата вече има.
        continue;
      }

      // Първо кои полета изобщо са капан, за да не се чете файл без нужда.
      Map<String, BigDecimal> risky = new LinkedHashMap<>();
      for (Map.Entry<String, BgDomainColumns.FieldSpec> e : fields.entrySet()) {
        BgDomainColumns.FieldSpec spec = e.getValue();
        if (spec.min == null || !NUMERIC_TAGS.contains(spec.tag)) {
          continue;
        }
        BigDecimal min;
        try {
          min = new BigDecimal(spec.min.trim());
        } catch (NumberFormatException ex) {
          continue; // не е число — домейнът е странен, но не е този тест
        }
        if (min.signum() > 0) {
          risky.put(e.getKey(), min); // min<=0 се удовлетворява от самия инициализатор
        }
      }
      if (risky.isEmpty()) {
        continue;
      }

      Path csv = INIT_DATA.resolve("en").resolve(input.getFileName());
      if (!Files.exists(csv)) {
        continue;
      }

      String label = input.getTypeName().substring(input.getTypeName().lastIndexOf('.') + 1);
      try (CSVParser parser =
          CSVFile.DEFAULT
              .withDelimiter(input.getSeparator())
              .withFirstRecordAsHeader()
              .parse(csv.toFile(), StandardCharsets.UTF_8)) {

        Map<String, Integer> header = parser.getHeaderMap();
        List<CSVRecord> records = new ArrayList<>();
        for (CSVRecord record : parser) {
          records.add(record);
        }

        for (Map.Entry<String, BigDecimal> e : risky.entrySet()) {
          String column = e.getKey();
          BigDecimal min = e.getValue();
          checked++;

          if (!header.containsKey(column)) {
            broken.put(
                input.getFileName() + " · " + label + "." + column,
                "домейнът обявява <"
                    + fields.get(column).tag
                    + " min=\""
                    + min.toPlainString()
                    + "\">, а колоната ЛИПСВА в seed-а. Генераторът инициализира полето на 0, тоест"
                    + " пропускането ѝ не го оставя неопределено — оставя нула, която НАРУШАВА"
                    + " @Min и поваля целия ред при запис. Добави колоната със стойност ≥ "
                    + min.toPlainString()
                    + " (неутралният минимум, ако полето е инертно за случая).");
            continue;
          }

          for (CSVRecord record : records) {
            String raw = record.isMapped(column) ? record.get(column) : null;
            String value = raw == null ? "" : raw.trim();
            String where = " (ред #" + record.getRecordNumber() + ")";
            if (value.isEmpty()) {
              broken.put(
                  input.getFileName() + " · " + label + "." + column + where,
                  "колоната я има, но клетката е ПРАЗНА — полето пада обратно на нулевия си"
                      + " инициализатор и нарушава @Min("
                      + min.toPlainString()
                      + ").");
              break;
            }
            BigDecimal actual;
            try {
              actual = new BigDecimal(value);
            } catch (NumberFormatException ex) {
              broken.put(
                  input.getFileName() + " · " + label + "." + column + where,
                  "стойността „"
                      + value
                      + "“ не е число, а полето е <"
                      + fields.get(column).tag
                      + ">.");
              break;
            }
            if (actual.compareTo(min) < 0) {
              broken.put(
                  input.getFileName() + " · " + label + "." + column + where,
                  "стойността " + value + " е под обявения минимум " + min.toPlainString() + ".");
              break;
            }
          }
        }
      }
    }

    assertTrue(
        checked >= 3,
        "проверени са само "
            + checked
            + " числови полета с min>0 — твърде малко, тоест четенето на домейните или на конфига е"
            + " спряло да намира полета и тестът мълчи вместо да мери");

    if (!broken.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append("seed редове, които падат на @Min при запис — вносителят лови ПО ФАЙЛ, тоест")
          .append(" съседните входове минават и липсата е тиха (")
          .append(broken.size())
          .append("):\n");
      broken.forEach((k, v) -> sb.append("  ").append(k).append("\n    ").append(v).append("\n"));
      fail(sb.toString());
    }
  }
}
