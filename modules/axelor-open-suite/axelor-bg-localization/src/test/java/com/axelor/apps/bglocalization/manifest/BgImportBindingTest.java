package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.data.csv.CSVBind;
import com.axelor.data.csv.CSVBinder;
import com.axelor.data.csv.CSVConfig;
import com.axelor.data.csv.CSVInput;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИНВАРИАНТ: всяко {@code required} поле, което е и колона в seed-а, се пълни от вносителя.
 *
 * <p><b>Капанът, който го поражда.</b> Изричен {@code <bind column="Y">} ИЗКЛЮЧВА колоната {@code
 * Y} от авто-мапването — {@link CSVBinder} събира всички {@code column} атрибути и прескача
 * едноименните колони ({@code if (boundCols.contains(field)) continue;}). Затова
 *
 * <pre>{@code <bind to="bgContributionFund" column="fundCode" search="self.code = :fundCode"/>}
 * </pre>
 *
 * оставя низа {@code fundCode} без нищо, което да го пълни, макар да е {@code required="true"}.
 *
 * <p><b>Защо остава скрит.</b> Импортът е upsert: заварените редове се ОБНОВЯВАТ, а техният {@code
 * fund_code} вече е записан, тоест валидацията минава. Пада единствено INSERT — тоест нито един нов
 * ред не може да влезе, а това се вижда едва когато някой добави ред.
 *
 * <p><b>Защо тестът вика ИСТИНСКИЯ binder.</b> Правилото „изричен bind изяжда колоната“ не се вижда
 * от конфига — то живее в {@code autoBind}. Тест, който преповтаря правилото вместо да го изпълни,
 * би минал и при сгрешено правило. Затова тук се парсва истинският конфиг ({@link
 * CSVConfig#parse}), подава се истинският заглавен ред на CSV-то и се строи истинският {@link
 * CSVBinder} — същият обект, който App Install ползва.
 *
 * <p><b>⚠️ Какво този тест НЕ доказва.</b> Че редът стига до базата. {@code CSVBinder} се строи без
 * връзка, тоест тук се проверява СВЪРЗВАНЕТО, не записът. Че новият ред пристига с попълнени и низ,
 * и FK, се твърди от {@code BgSeafarerContributionManifestTest} (20 реда за вид осигурен 92) и
 * {@code BgContributionFundConsistencyTest} (низът и FK-ът съвпадат за всеки ред) — те гледат
 * базата след App Install.
 */
class BgImportBindingTest {

  private static final Path INIT_DATA =
      BgDomainColumns.moduleRoot().resolve("src/main/resources/apps/init-data");
  private static final Path CONFIG = INIT_DATA.resolve("bg-localization-config.xml");

  /**
   * Полетата, към които сочи готовият binder — включително добавените от {@code autoBind}.
   *
   * <p>{@code CSVBinder} не излага {@code bindings} публично, а точно те са отговорът на въпроса.
   * Затова се четат по отражение: алтернативата е тестът сам да сметне кое е свързано, тоест да
   * преповтори правилото, което проверява.
   */
  private static List<String> boundFields(CSVBinder binder) throws Exception {
    Field f = CSVBinder.class.getDeclaredField("bindings");
    f.setAccessible(true);
    Object raw = f.get(binder);
    if (!(raw instanceof List)) {
      fail("CSVBinder.bindings вече не е List — формата на класа се е сменила, тестът е сляп");
    }
    List<String> out = new ArrayList<>();
    for (Object o : (List<?>) raw) {
      if (o instanceof CSVBind && ((CSVBind) o).getField() != null) {
        out.add(((CSVBind) o).getField());
      }
    }
    return out;
  }

  private static String[] header(Path csv, char separator) throws Exception {
    String first = Files.readAllLines(csv, StandardCharsets.UTF_8).get(0);
    return first.split(String.valueOf(separator == '|' ? "\\|" : separator), -1);
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: изричен bind не изяжда колоната на required поле")
  void everyRequiredColumnStaysBound() throws Exception {
    Map<String, Map<String, BgDomainColumns.FieldSpec>> declared = BgDomainColumns.allFields();
    assertTrue(declared.size() > 100, "домейн XML-ите не са прочетени: " + declared.size());

    CSVConfig config = CSVConfig.parse(CONFIG.toFile());
    List<CSVInput> inputs = config.getInputs();
    assertTrue(inputs != null && !inputs.isEmpty(), "конфигът не се парсна: " + CONFIG);

    Map<String, String> broken = new LinkedHashMap<>();
    int checked = 0;

    for (CSVInput input : inputs) {
      String type = input.getTypeName();
      Map<String, BgDomainColumns.FieldSpec> fields = declared.get(type);
      if (fields == null) {
        fail("конфигът зарежда " + input.getFileName() + " в " + type + ", а домейн такъв няма");
      }

      Path csv = INIT_DATA.resolve("en").resolve(input.getFileName());
      if (!Files.exists(csv)) {
        continue;
      }
      String[] columns = header(csv, input.getSeparator());

      // Истинският binder — със същия конфиг и същия заглавен ред, които ползва App Install.
      CSVBinder binder = new CSVBinder(Class.forName(type), columns, input);
      List<String> bound = boundFields(binder);

      for (String column : columns) {
        BgDomainColumns.FieldSpec spec = fields.get(column);
        if (spec == null || !spec.required) {
          continue; // не е поле на entity-то, или не е задължително
        }
        checked++;
        if (!bound.contains(column)) {
          broken.put(
              input.getFileName()
                  + " · "
                  + type.substring(type.lastIndexOf('.') + 1)
                  + "."
                  + column,
              "колоната „"
                  + column
                  + "“ е в CSV-то и полето е required=\"true\", но след автоматичното свързване"
                  + " НИЩО не пълни полето — изричен <bind column=\""
                  + column
                  + "\"> я е изключил от авто-мапването. Свързани полета: "
                  + new TreeSet<>(bound));
        }
      }
    }

    assertTrue(
        checked > 20,
        "проверени са само "
            + checked
            + " задължителни колони — твърде малко, тоест свързването на имена е спряло да намира"
            + " полета и тестът мълчи вместо да мери");

    if (!broken.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      sb.append("required полета, които вносителят НЕ пълни — всеки INSERT ще падне с „must not be")
          .append(" null“, докато upsert-ът върху заварени редове минава и скрива дефекта (")
          .append(broken.size())
          .append("):\n");
      broken.forEach((k, v) -> sb.append("  ").append(k).append("\n    ").append(v).append("\n"));
      sb.append(
          "\nПоправката е ВТОРИ изричен bind към самото поле, напр."
              + " <bind to=\"fundCode\" column=\"fundCode\"/> — един източник в CSV-то, две полета.");
      fail(sb.toString());
    }
  }
}
