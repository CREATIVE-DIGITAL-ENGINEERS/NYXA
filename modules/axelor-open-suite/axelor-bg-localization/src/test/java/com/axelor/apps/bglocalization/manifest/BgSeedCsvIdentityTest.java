package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Твърди, че КЛЮЧОВЕТЕ на началните данни са здрави — преди базата да е видяла ред от тях.
 *
 * <p><b>Какво пази.</b> Нов ред със ЗАЕТ {@code importId} — например нов режим по чл. 106 ЗДСл с
 * номер, който вече принадлежи на чл. 221 ал. 1 КТ. {@code CSVImporter} сверява по {@code
 * search="self.importId = :importId"}, тоест по-късният ред НЕ се вписва като нов, а ПРЕЗАПИСВА
 * по-ранния. Резултатът: обезщетението по КТ изчезва от каталога (преименувано в ЗДСл), а редът на
 * фиша, който сочи към него по код, се навързва към ЗДСл вида — едно обезщетение с ДВА реда на
 * фиша.
 *
 * <p><b>Защо мълчи в CI.</b> {@link BgPaySlipLineTypeCatalogueTest} хваща това, но сверява БАЗАТА.
 * В конвейера няма Postgres, {@code BgSkip} го пропуска с обявена причина и конвейерът остава
 * зелен. А локално той пада само ако на машината случайно има вдигната база, и то върху онова
 * състояние, което тя носи.
 *
 * <p><b>Затова тук няма база.</b> Сблъсъкът на ключове се вижда в самия файл: повече редове,
 * отколкото ключове. Тези твърдения падат на всяка машина и в конвейера, при {@code gradlew test},
 * без App Install и без Postgres.
 *
 * <p>Трите проверки хващат три различни начина да се счупи връзка между файловете:
 *
 * <ul>
 *   <li>ДУБЛИРАН {@code importId} в един файл — по-късният ред презаписва по-ранния (случката
 *       горе).
 *   <li>РАЗМИНАЛИ СЕ {@code en} и {@code fr} — двата езика са един и същи каталог с преведени
 *       имена; различен {@code importId → code} значи, че базата получава различни данни според
 *       това кой език е внесен.
 *   <li>ВИСЯЩА препратка между файлове — {@code bgCompensationType} и роднините му се сверяват по
 *       {@code search="self.code = :…"}; код без ред зад себе си оставя полето празно, без да спре
 *       импорта.
 * </ul>
 */
class BgSeedCsvIdentityTest {

  private static final String DIR = "src/main/resources/apps/init-data/%s";
  private static final String[] ЕЗИЦИ = {"en", "fr"};
  private static final char SEP = ';';

  /**
   * Препратките между файловете, както са обявени в {@code bg-localization-config.xml}: всяка е
   * {@code <bind ... search="self.code = :КОЛОНА"/>}, тоест стойността в колоната трябва да е
   * {@code code} на ред в друг файл.
   */
  private static final String[][] ПРЕПРАТКИ = {
    {"bg_insurance_contribution.csv", "fundCode", "bg_contribution_fund.csv"},
    {"bg_pay_slip_line_type_contribution.csv", "bgContributionFund", "bg_contribution_fund.csv"},
    {"bg_pay_slip_line_type_compensation.csv", "bgCompensationType", "bg_compensation_type.csv"},
    {"bg_pay_slip_line_type_social.csv", "bgSocialExpenseCap", "bg_social_expense_cap.csv"},
  };

  @Test
  @DisplayName("никой seed файл не носи два реда с един и същи importId")
  void everySeedRowHasItsOwnImportId() throws IOException {
    List<String> счупени = new ArrayList<>();

    for (String lang : ЕЗИЦИ) {
      for (Path f : csvFiles(lang)) {
        List<String[]> rows = rows(f);
        int idx = columnIndex(rows.get(0), "importId");
        if (idx < 0) {
          continue; // файл без importId — няма ключ, който да се сблъска
        }

        // ключ → редовете, на които се среща (номерата са като във файла, с хедъра)
        Map<String, List<Integer>> срещания = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
          String[] cells = rows.get(i);
          if (idx >= cells.length) {
            continue; // грешната аритметика е работа на BgSeedCsvArityTest
          }
          String key = cells[idx].trim();
          if (!key.isEmpty()) {
            срещания.computeIfAbsent(key, k -> new ArrayList<>()).add(i + 1);
          }
        }

        срещания.forEach(
            (key, редове) -> {
              if (редове.size() > 1) {
                счупени.add(
                    lang
                        + "/"
                        + f.getFileName()
                        + ": importId „"
                        + key
                        + "“ се среща на редове "
                        + редове
                        + " — "
                        + редове.size()
                        + " пъти");
              }
            });
      }
    }

    if (!счупени.isEmpty()) {
      fail(
          "дублиран importId в началните данни:\n  "
              + String.join("\n  ", счупени)
              + "\n  ⚠️ Това НЕ прави втори ред. CSVImporter сверява по"
              + " search=\"self.importId = :importId\", тоест по-късният ред ПРЕЗАПИСВА по-ранния:"
              + " единият запис изчезва (преименуван в другия), а всичко, което сочи към него по"
              + " код, се навързва към чуждия запис. Дай на новия ред СВОЙ, незает номер.");
    }
  }

  @Test
  @DisplayName("en и fr обявяват един и същи importId → code — те са един каталог с два превода")
  void bothLanguagesAgreeOnIdentity() throws IOException {
    List<String> счупени = new ArrayList<>();

    for (Path en : csvFiles("en")) {
      String име = en.getFileName().toString();
      Path fr = Paths.get(String.format(DIR, "fr")).resolve(име);
      if (!Files.exists(fr)) {
        счупени.add(име + " — има го в en, няма го в fr");
        continue;
      }

      Map<String, String> aMap = identity(en);
      Map<String, String> bMap = identity(fr);
      if (aMap == null || bMap == null) {
        continue; // няма importId и/или code — няма самоличност за сверяване
      }

      Set<String> всички = new LinkedHashSet<>(aMap.keySet());
      всички.addAll(bMap.keySet());
      for (String key : всички) {
        String a = aMap.get(key);
        String b = bMap.get(key);
        if (a == null) {
          счупени.add(име + ": " + key + " → „" + b + "“ е само във fr");
        } else if (b == null) {
          счупени.add(име + ": " + key + " → „" + a + "“ е само в en");
        } else if (!a.equals(b)) {
          счупени.add(име + ": " + key + " → en „" + a + "“ ≠ fr „" + b + "“");
        }
      }
    }

    if (!счупени.isEmpty()) {
      fail(
          "en и fr се разминават по самоличност (importId → code):\n  "
              + String.join("\n  ", счупени)
              + "\n  ⚠️ Двата езика са ЕДИН каталог с преведени имена, не два каталога. Различен"
              + " код срещу един и същи importId значи, че базата получава различни данни според"
              + " това кой език е внесен — а внася се en (виж BgLocalizationServerStartListener).");
    }
  }

  @Test
  @DisplayName("всяка препратка по код между seed файловете сочи към съществуващ ред")
  void everyCodeReferenceResolves() throws IOException {
    List<String> счупени = new ArrayList<>();

    for (String lang : ЕЗИЦИ) {
      Path dir = Paths.get(String.format(DIR, lang));
      for (String[] препратка : ПРЕПРАТКИ) {
        Path child = dir.resolve(препратка[0]);
        String колона = препратка[1];
        Path parent = dir.resolve(препратка[2]);

        if (!Files.exists(child) || !Files.exists(parent)) {
          fail(
              "липсва seed файл за препратката "
                  + препратка[0]
                  + "."
                  + колона
                  + " → "
                  + препратка[2]
                  + ": "
                  + (Files.exists(child) ? parent : child).toAbsolutePath());
        }

        Set<String> известни = codes(parent);
        List<String[]> rows = rows(child);
        int idx = columnIndex(rows.get(0), колона);
        if (idx < 0) {
          fail(
              "няма колона „"
                  + колона
                  + "“ в "
                  + lang
                  + "/"
                  + препратка[0]
                  + " — има: "
                  + Arrays.toString(rows.get(0))
                  + ". Ако връзката е преименувана, оправи и ПРЕПРАТКИ в този тест, и"
                  + " bg-localization-config.xml.");
        }

        for (int i = 1; i < rows.size(); i++) {
          String[] cells = rows.get(i);
          if (idx >= cells.length) {
            continue;
          }
          String ref = cells[idx].trim();
          if (ref.isEmpty() || известни.contains(ref)) {
            continue;
          }
          счупени.add(
              lang
                  + "/"
                  + препратка[0]
                  + ", ред "
                  + (i + 1)
                  + ": „"
                  + ref
                  + "“ няма ред в "
                  + препратка[2]);
        }
      }
    }

    if (!счупени.isEmpty()) {
      fail(
          "висяща препратка по код между началните данни:\n  "
              + String.join("\n  ", счупени)
              + "\n  ⚠️ Импортът НЕ пада от това: bind-ът със search=\"self.code = :…\" просто не"
              + " намира нищо и полето остава празно. Редът пристига в базата осиротял — на фиша"
              + " се печата празен.");
    }
  }

  // ─────────────────────────── помощни ───────────────────────────

  /** {@code importId → code}, или {@code null}, когато файлът няма двете колони. */
  private static Map<String, String> identity(Path f) throws IOException {
    List<String[]> rows = rows(f);
    int id = columnIndex(rows.get(0), "importId");
    int code = columnIndex(rows.get(0), "code");
    if (id < 0 || code < 0) {
      return null;
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (int i = 1; i < rows.size(); i++) {
      String[] cells = rows.get(i);
      if (id >= cells.length || code >= cells.length) {
        continue;
      }
      String key = cells[id].trim();
      if (!key.isEmpty()) {
        out.put(key, cells[code].trim());
      }
    }
    return out;
  }

  private static Set<String> codes(Path f) throws IOException {
    List<String[]> rows = rows(f);
    int idx = columnIndex(rows.get(0), "code");
    if (idx < 0) {
      fail("няма колона „code“ в " + f.getFileName() + " — има: " + Arrays.toString(rows.get(0)));
    }
    Set<String> out = new LinkedHashSet<>();
    for (int i = 1; i < rows.size(); i++) {
      String[] cells = rows.get(i);
      if (idx < cells.length && !cells[idx].trim().isEmpty()) {
        out.add(cells[idx].trim());
      }
    }
    return out;
  }

  private static List<Path> csvFiles(String lang) throws IOException {
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
      fail("папката " + dir + " няма нито един CSV — това не е състояние, което тест бива да мине");
    }
    return файлове;
  }

  private static List<String[]> rows(Path f) throws IOException {
    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
    if (lines.isEmpty()) {
      fail("празен seed файл: " + f.toAbsolutePath());
    }
    List<String[]> out = new ArrayList<>();
    for (String line : lines) {
      out.add(line.isEmpty() ? new String[0] : splitQuoted(line));
    }
    return out;
  }

  private static int columnIndex(String[] header, String name) {
    for (int i = 0; i < header.length; i++) {
      if (header[i].trim().equals(name)) {
        return i;
      }
    }
    return -1;
  }

  /**
   * Цепене по {@code ;} извън кавички — същото правило, което прилага и вносителят (RFC 4180,
   * {@code CSVFormat.DEFAULT}). Голото цепене би разместило колоните на законно екранираните
   * редове.
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
}
