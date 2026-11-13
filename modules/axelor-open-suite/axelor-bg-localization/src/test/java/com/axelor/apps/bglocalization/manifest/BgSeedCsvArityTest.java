package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Твърди, че НИТО ЕДИН ред от началните данни не се губи между файла и базата.
 *
 * <p><b>Какво пази.</b> НЕЕКРАНИРАНА точка и запетая в бележка (например „…парите отиват в НОИ;
 * това мени превода…“ на фонд {@code PPF} в {@code bg_contribution_fund.csv}) дава във файл с
 * разделител {@code ;} 12 полета вместо 11: опашката на бележката ляга върху {@code active},
 * истинското {@code true} пада ИЗВЪН реда, Boolean адаптерът гърми и App Install отхвърля реда.
 * Оттам каскадата: фондът липсва в речника, всяка вноска с {@code fundCode='PPF'} остава без
 * родител и умира с него. На фиша това са 12 на сто ППФ за първа категория, тоест 240,00 лв. на
 * база 2000, които просто ги няма — без нито един червен тест.
 *
 * <p><b>Защо мълчи.</b> Проверката трябва да е на две различни места, и двете нужни:
 *
 * <ul>
 *   <li>ФАЙЛЪТ трябва да се брои по колони. Наивно цепене по {@code ;} връща каквото излезе, а
 *       проверка, която пада само при ПО-МАЛКО колони от търсената, при ПОВЕЧЕ връща съседната
 *       стойност и минава зелено. Тази половина е запушена в самия {@code BgSeed} и тук се
 *       разпростира върху ВСЕКИ seed файл — включително тези, които никой тест не чете.
 *   <li>БАЗАТА трябва да се пита дали е получила ФОНДОВЕТЕ. Другите манифестни тестове питат „има
 *       ли редове“ и „съгласуват ли се помежду си“ — а отхвърлен фонд минава и през двата въпроса,
 *       защото каквото е останало си е съгласувано.
 * </ul>
 *
 * <p>Първото твърдение НЕ иска база и пада веднага, на всяка машина. Второто иска база и се
 * пропуска с обявена причина, когато речникът е празен. То нарочно НЕ брои редове — виж бележката
 * при {@link #seedFundCodes()} защо броенето дава червено на всяка заварена инсталация и оттам не
 * значи нищо.
 */
class BgSeedCsvArityTest {

  private static final String DIR = "src/main/resources/apps/init-data/%s";
  private static final char SEP = ';';
  private static final String ФОНД_ФАЙЛ = "bg_contribution_fund.csv";
  private static final String ФОНДОВЕ = "bglocalization_bg_contribution_fund";

  // ─────────────────────────── файловата половина ───────────────────────────

  @Test
  @DisplayName("нито един seed ред не носи неекраниран разделител — броят колони е като на хедъра")
  void everySeedRowHasExactlyAsManyColumnsAsItsHeader() throws IOException {
    List<String> счупени = new ArrayList<>();

    for (String lang : new String[] {"en", "fr"}) {
      Path dir = Paths.get(String.format(DIR, lang));
      if (!Files.isDirectory(dir)) {
        fail("липсва папката с начални данни: " + dir.toAbsolutePath());
      }
      List<Path> файлове;
      try (Stream<Path> s = Files.list(dir)) {
        файлове =
            s.filter(p -> p.getFileName().toString().endsWith(".csv"))
                .sorted()
                .collect(Collectors.toList());
      }
      if (файлове.isEmpty()) {
        fail(
            "папката "
                + dir
                + " няма нито един CSV — това не е състояние, което тест бива да мине");
      }

      for (Path f : файлове) {
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        if (lines.isEmpty()) {
          счупени.add(lang + "/" + f.getFileName() + " — празен файл");
          continue;
        }
        int очаквани = splitQuoted(lines.get(0)).length;
        for (int i = 1; i < lines.size(); i++) {
          String line = lines.get(i);
          if (line.isEmpty()) {
            continue;
          }
          String[] cells = splitQuoted(line);
          if (cells.length != очаквани) {
            счупени.add(
                lang
                    + "/"
                    + f.getFileName()
                    + ", ред "
                    + (i + 1)
                    + ": "
                    + cells.length
                    + " колони вместо "
                    + очаквани
                    + " — първата клетка е „"
                    + (cells.length > 0 ? cells[0] : "")
                    + "“");
          }
        }
      }
    }

    if (!счупени.isEmpty()) {
      fail(
          "редове с грешен брой колони — най-вероятно неекраниран „"
              + SEP
              + "“ в свободен текст (бележка, наименование, описание). Обгради полето в кавички:"
              + " …"
              + SEP
              + "\"текст с "
              + SEP
              + " вътре\""
              + SEP
              + "…\n  "
              + String.join("\n  ", счупени)
              + "\n  ⚠️ Такъв ред НЕ спира импорта: App Install го отхвърля поотделно и мълчи, а"
              + " всичко, което сочи към него, изчезва с него.");
    }
  }

  // ─────────────────────────── базовата половина ───────────────────────────

  @Test
  @DisplayName("всеки фонд, който seed-ът обявява, го има в речника на базата")
  void everyFundTheSeedDeclaresExistsInTheDictionary() throws Exception {
    try (Connection c = open()) {

      // ⚠️ ТЕСТ, КОЙТО МИНАВА ПРИ ПРАЗНА ТАБЛИЦА, НЕ Е ТЕСТ. Празен речник значи „App Install не е
      // пускан“ — среда, не дефект, и се пропуска с обявена причина. Речник С РЕДОВЕ, но без наш
      // фонд, е точно обратното: импортът е минал и е ИЗЯЛ ред.
      if (count(c, ФОНДОВЕ) == 0) {
        BgSkip.because(
            "речникът на фондовете е празен — App Install не е пускан на тази база, тоест"
                + " твърдението „всеки фонд от seed-а е доставен“ НЕ е проверено.");
      }

      List<String> вбазата = codes(c);
      List<String> липсващи = new ArrayList<>();
      for (String code : seedFundCodes()) {
        if (!вбазата.contains(code)) {
          липсващи.add(code);
        }
      }

      if (!липсващи.isEmpty()) {
        fail(
            "фондове, които seed-ът обявява, а речникът в базата ги няма: "
                + String.join(", ", липсващи)
                + "\n  в базата има "
                + вбазата.size()
                + " фонда: "
                + String.join(", ", вбазата)
                + "\n  ⚠️ Липсващ фонд НЕ е самотна щета: всяка вноска и всяко перо, което сочи"
                + " към него, се отхвърля мълчаливо заедно с него. Първо виж дали редът му във"
                + " файла минава другия тест в този клас — неекраниран разделител го чупи точно"
                + " така.");
      }
    }
  }

  /**
   * Защо ПРИСЪСТВИЕ, а не брой редове.
   *
   * <p>Сверката на БРОЯ редове във всяка таблица с броя редове в съответните файлове дава ЧЕРВЕНО
   * на всяка база, на която App Install просто не е пускан наскоро — заварена инсталация, не
   * отхвърлен ред. Такова червено не значи нищо и приучава да не се гледа; точно от него {@code
   * BgSkip} съществува.
   *
   * <p>ПРИСЪСТВИЕТО на фонд различава двете. Речникът на фондовете е малък и се мени рядко, а
   * каскадата минава именно през него: отхвърлен ред на фонд оставя един фонд по-малко и отнася със
   * себе си вноските и перата му. Заварена база със стари броячи пак носи всичките фондове. Тоест
   * твърдението пада там, където има дефект, и мълчи там, където има само стара инсталация.
   */
  private static List<String> seedFundCodes() throws IOException {
    Path csv = Paths.get(String.format(DIR, "en")).resolve(ФОНД_ФАЙЛ);
    if (!Files.exists(csv)) {
      fail("липсва seed файл: " + csv.toAbsolutePath());
    }
    List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
    List<String> header = Arrays.asList(splitQuoted(lines.get(0)));
    int i = header.indexOf("code");
    if (i < 0) {
      fail("няма колона „code“ в " + ФОНД_ФАЙЛ + " — има: " + header);
    }
    List<String> out = new ArrayList<>();
    for (int k = 1; k < lines.size(); k++) {
      if (!lines.get(k).isEmpty()) {
        out.add(splitQuoted(lines.get(k))[i]);
      }
    }
    return out;
  }

  private static List<String> codes(Connection c) throws Exception {
    List<String> out = new ArrayList<>();
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT code FROM " + ФОНДОВЕ + " ORDER BY code")) {
      while (rs.next()) {
        out.add(rs.getString(1));
      }
    }
    return out;
  }

  // ─────────────────────────── помощни ───────────────────────────

  private static int count(Connection c, String table) throws Exception {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + table)) {
      rs.next();
      return rs.getInt(1);
    }
  }

  /**
   * Цепене по {@code ;} извън кавички — същото правило, което прилага и вносителят.
   *
   * <p>{@code CSVImporter} чете през {@code CSVFile.DEFAULT} = {@code CSVFormat.DEFAULT}, тоест
   * кавичка {@code "} по RFC 4180. Голото цепене би обявило за счупени и законно екранираните
   * редове (например кодове 10, 82 и 90 в {@code bg_insured_type.csv}), които вносителят чете
   * правилно.
   */
  private static String[] splitQuoted(String line) {
    List<String> out = new ArrayList<>();
    StringBuilder cell = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < line.length(); i++) {
      char ch = line.charAt(i);
      if (ch == '"') {
        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
          cell.append('"');
          i++;
        } else {
          quoted = !quoted;
        }
      } else if (ch == SEP && !quoted) {
        out.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(ch);
      }
    }
    out.add(cell.toString());
    return out.toArray(new String[0]);
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
              + " — броят доставени seed редове НЕ е проверен.");
      return null;
    }
  }
}
