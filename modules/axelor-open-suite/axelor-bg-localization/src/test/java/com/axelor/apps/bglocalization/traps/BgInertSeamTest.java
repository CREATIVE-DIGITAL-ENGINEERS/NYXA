package com.axelor.apps.bglocalization.traps;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ШЕВ, КОЙТО ВРЪЩА null, ИЗГЛЕЖДА ТОЧНО КАТО РАБОТЕЩА ПРОВЕРКА.
 *
 * <p>Модулът дели правилата (чист резолвер) от базата (услуга) през защитени методи-шевове. Тестът
 * подменя шева, зеленото се появява — а в живото приложение базовата реализация връща {@code null}
 * и проверката над нея мълчи завинаги. Такъв шев не се вижда нито в билда, нито в ревюто: и двата
 * гледат тестовете, а тестовете са зелени.
 *
 * <p>Затова СПИСЪКЪТ с празни шевове е закован тук. Нов празен шев прави този тест червен и авторът
 * му трябва да каже кое от трите е:
 *
 * <ol>
 *   <li>чака друга работна фаза — тогава се добавя тук, с причина, и се пише тест като {@code
 *       BgPaySlipInertGuardsTest}, който заковава, че проверката над него още мълчи;
 *   <li>недоглеждане — тогава се навързва, вместо да се вписва;
 *   <li>нарочно празен завинаги — тогава причината се пише до метода, не само тук.
 * </ol>
 *
 * <p>Обратната посока пази същото: щом шев се навърже, тестът пак става червен, защото списъкът
 * вече не съвпада. Тоест празнотата не може нито да се появи, нито да изчезне тихо.
 */
class BgInertSeamTest {

  private static final Path MAIN = Paths.get("src/main/java");

  /**
   * ⚠️ ТОЧНИЯТ СПИСЪК на празните шевове. Формат: {@code Клас#метод — причина}.
   *
   * <p>ПРАЗЕН СПИСЪК — И ТОВА Е СЪСТОЯНИЕ, НЕ ПРОПУСК. Навързаният шев излиза от списъка — оставен,
   * тестът пада с „вече навързани“, което е другата посока на същата проверка.
   *
   * <p>Празният списък НЕ прави теста безсмислен: той пази обратната посока — нов шев, който връща
   * само {@code null}, няма къде да се скрие и пада веднага. Тоест твърдението е „празни шевове
   * НЯМА“.
   */
  private static final Set<String> KNOWN_INERT = new HashSet<>(Arrays.<String>asList());

  private static final Pattern METHOD =
      Pattern.compile(
          "^(\\s+)(?:(?:public|protected|private|static|final|abstract)\\s+)*"
              + "([\\w<>\\.\\[\\], ]+?)\\s+(\\w+)\\s*\\([^)]*\\)\\s*(?:throws [\\w, \\.]+\\s*)?\\{\\s*$");

  @Test
  @DisplayName("⚠️ Празните шевове са точно тези два — нов такъв не минава тихо")
  void theOnlyInertSeamsAreTheKnownOnes() throws IOException {
    Set<String> found = new TreeSet<>();
    try (Stream<Path> files = Files.walk(MAIN)) {
      for (Path f :
          files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        String type = f.getFileName().toString().replace(".java", "");
        for (int i = 0; i < lines.size(); i++) {
          Matcher m = METHOD.matcher(lines.get(i));
          if (!m.matches()) {
            continue;
          }
          String close = m.group(1) + "}";
          List<String> body = new ArrayList<>();
          int j = i + 1;
          while (j < lines.size() && !lines.get(j).equals(close)) {
            String s = lines.get(j).trim();
            if (!s.isEmpty() && !s.startsWith("//") && !s.startsWith("*") && !s.startsWith("/*")) {
              body.add(s);
            }
            j++;
          }
          if (j < lines.size() && body.size() == 1 && "return null;".equals(body.get(0))) {
            found.add(type + "#" + m.group(3));
          }
        }
      }
    }

    Set<String> appeared = new TreeSet<>(found);
    appeared.removeAll(KNOWN_INERT);
    Set<String> wired = new TreeSet<>(KNOWN_INERT);
    wired.removeAll(found);

    if (!appeared.isEmpty() || !wired.isEmpty()) {
      fail(
          "СПИСЪКЪТ С ПРАЗНИ ШЕВОВЕ СЕ Е ПРОМЕНИЛ.\n"
              + "  нови празни : "
              + appeared
              + "\n  вече навързани: "
              + wired
              + "\n  ⚠️ Празен шев изглежда като работеща проверка, докато не се навърже. Ако е нов:"
              + " навържете го, или го впишете в KNOWN_INERT С ПРИЧИНА и заковете мълчанието му с"
              + " тест. Ако е навързан: махнете го оттам и добавете тест, че проверката вече се"
              + " задейства с истински вход.");
    }
  }
}
