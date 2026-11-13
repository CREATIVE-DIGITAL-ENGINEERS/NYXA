package com.axelor.apps.bglocalization.traps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИНВАРИАНТ: ВСЕКИ ПУБЛИЧЕН ВХОД НА {@code BgWorkTimeFacts} ИМА ВИКАЩ ИЗВЪН {@code src/test}.
 *
 * <p><b>Какво се твърди и защо точно това.</b> Не се твърди „днес {@code byHours} се вика“ — това е
 * факт за днес и утре е неверен, без нищо да падне. Твърди се архитектурното: наборът факти за
 * пропорцията по НЕВД чл. 1 ал. 3 и ал. 4 е ДОСТИЖИМ от главния код. Тестът пада в деня, в който
 * пропорцията пак стане недостижима от работещото приложение — тоест точно в деня, в който някой
 * махне последния викащ.
 *
 * <p><b>Как изглежда провалът, който този тест лови.</b> {@code byHours()} и {@code byDays()} имат
 * десетки викащи — всичките в тестове, — а в главния код нула. Тогава пропорцията е най-добре
 * покритата функционалност в модула и едновременно недостижима: живият път вика само {@code
 * fullMonth()}. Зелен билд с десетки тестове изглежда точно като работеща пропорция.
 *
 * <p><b>Защо тест върху сорса, а не върху поведение.</b> Твърдението е за СВЪРЗАНОСТ, не за
 * резултат. Тест, който вика {@code byHours} сам, доказва че методът работи — и мълчи за това дали
 * някой го вика. Точно тази разлика е дефектът.
 *
 * <p>Роднина е {@code BgInertSeamTest}: и двата пазят от шев, който изглежда като работеща
 * функционалност, докато не се навърже.
 */
class BgWorkTimeFactsReachTest {

  private static final Path MAIN = Paths.get("src/main/java");

  private static final Path FACTS =
      Paths.get("src/main/java/com/axelor/apps/bglocalization/service/BgWorkTimeFacts.java");

  /**
   * ⚠️ ТОЧНИЯТ СПИСЪК на публичните входове. Ключ: името на входа; стойност: текстът, с който се
   * търси викащ.
   *
   * <p>Конструкторът е ВХОД като всеки друг и е в списъка: публичен конструктор без викащ е също
   * толкова мъртъв, колкото и фабрика без викащ. Неговите викащи са трите фабрики в самия клас —
   * това е ИЗВЪН {@code src/test} и се брои.
   */
  private static final Map<String, String> ENTRY_POINTS = new LinkedHashMap<>();

  static {
    ENTRY_POINTS.put("fullMonth", "BgWorkTimeFacts.fullMonth(");
    ENTRY_POINTS.put("byHours", "BgWorkTimeFacts.byHours(");
    ENTRY_POINTS.put("byDays", "BgWorkTimeFacts.byDays(");
    ENTRY_POINTS.put("<init>", "new BgWorkTimeFacts(");
  }

  /** {@code public static BgWorkTimeFacts byHours(} и {@code public BgWorkTimeFacts(} */
  private static final Pattern PUBLIC_ENTRY =
      Pattern.compile("^\\s*public\\s+(?:static\\s+)?BgWorkTimeFacts\\s*(\\w*)\\s*\\(");

  /**
   * ⚠️ КОТВАТА: сорсът наистина е прочетен и в него наистина има толкова публични входа, колкото
   * списъкът твърди.
   *
   * <p>Без нея преместен файл или счупен regex дават НУЛА намерени входа, цикълът по-долу не се
   * върти нито веднъж и тестът минава зелен, без да е проверил каквото и да е. Същата предпазна
   * мярка като {@code assertParserSeesEveryField} в {@code BgPayrollInputContractTest}.
   *
   * <p>Липсващият файл ПАДА, а не се пропуска: пропуснатата проверка изглежда точно като минала.
   */
  private static List<String> publicEntryPointsInSource() throws IOException {
    assertTrue(
        Files.isRegularFile(FACTS),
        "не намирам сорса на BgWorkTimeFacts: "
            + FACTS.toAbsolutePath()
            + "\n  ⚠️ Пътят е спрямо директорията на модула — проверете от къде се пуска тестът.");
    List<String> found = new ArrayList<>();
    for (String line : Files.readAllLines(FACTS, StandardCharsets.UTF_8)) {
      Matcher m = PUBLIC_ENTRY.matcher(line);
      if (m.find()) {
        found.add(m.group(1).isEmpty() ? "<init>" : m.group(1));
      }
    }
    return found;
  }

  @Test
  @DisplayName("⚠️ Публичните входове на BgWorkTimeFacts са точно тези четири — нов не минава тихо")
  void theEntryPointsAreExactlyTheKnownOnes() throws IOException {
    assertEquals(
        new TreeSet<>(ENTRY_POINTS.keySet()),
        new TreeSet<>(publicEntryPointsInSource()),
        "СПИСЪКЪТ С ПУБЛИЧНИ ВХОДОВЕ НА BgWorkTimeFacts СЕ Е ПРОМЕНИЛ.\n"
            + "  ⚠️ Нов публичен вход без викащ е нова недостижима пропорция. Впишете го в"
            + " ENTRY_POINTS заедно с текста, по който се търси викащ — инак инвариантът отдолу"
            + " просто няма да го провери и ще мълчи за него завинаги.");
  }

  @Test
  @DisplayName("⚠️ ИНВАРИАНТ: всеки публичен вход има поне един викащ в src/main/java")
  void everyEntryPointHasACallerOutsideTests() throws IOException {
    // Котвата важи и тук: без нея цикълът може да е коректен над празен списък.
    assertEquals(
        ENTRY_POINTS.size(),
        publicEntryPointsInSource().size(),
        "котвата: парсерът не вижда толкова входа, колкото списъкът твърди");

    List<Path> sources;
    try (Stream<Path> files = Files.walk(MAIN)) {
      sources = files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList());
    }
    assertTrue(
        sources.size() > 50,
        "котвата: очаквах десетки файла в src/main/java, намерих " + sources.size());

    Map<String, List<String>> callers = new TreeMap<>();
    for (Map.Entry<String, String> entry : ENTRY_POINTS.entrySet()) {
      callers.put(entry.getKey(), new ArrayList<>());
    }
    for (Path file : sources) {
      List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
      for (int i = 0; i < lines.size(); i++) {
        String line = lines.get(i);
        // ⚠️ Само КОД. Ред, който е коментар или javadoc, не е викащ — иначе изречението „byHours
        // не се вика никъде“ би минало за викане и точно този тест би станал безполезен.
        String trimmed = line.trim();
        if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
          continue;
        }
        for (Map.Entry<String, String> entry : ENTRY_POINTS.entrySet()) {
          if (line.contains(entry.getValue())) {
            callers.get(entry.getKey()).add(file.getFileName() + ":" + (i + 1));
          }
        }
      }
    }

    List<String> dead = new ArrayList<>();
    for (Map.Entry<String, List<String>> entry : callers.entrySet()) {
      if (entry.getValue().isEmpty()) {
        dead.add(entry.getKey() + " (търсено: „" + ENTRY_POINTS.get(entry.getKey()) + "“)");
      }
    }

    if (!dead.isEmpty()) {
      fail(
          "ПУБЛИЧЕН ВХОД НА BgWorkTimeFacts БЕЗ НИТО ЕДИН ВИКАЩ В ГЛАВНИЯ КОД:\n  "
              + String.join("\n  ", dead)
              + "\n\n  намерени викащи по вход: "
              + callers
              + "\n\n  ⚠️ Това значи, че пропорцията по НЕВД чл. 1 (ал. 4 — часове, ал. 3 — дни) е"
              + " НЕДОСТИЖИМА от работещото приложение, независимо колко теста я покриват.\n"
              + "  Поправка: навържете входа от главния код (днес това става в"
              + " BgPayrollWorkTime.decide, а той се вика от BgContributionPreviewController).\n"
              + "  ⚠️ Ако викащият съществува, но е през СТАТИЧЕН import, търсеният текст няма да го"
              + " намери. Тогава се допълва ENTRY_POINTS — не се маха твърдението: червено заради"
              + " неразпознат стил е поправимо, зелено заради непроверено твърдение не е.");
    }
  }

  @Test
  @DisplayName("⚠️ Викащите на byHours и byDays НЕ са само в самия BgWorkTimeFacts")
  void theProrationFactoriesAreCalledFromElsewhere() throws IOException {
    for (String name : Arrays.asList("byHours", "byDays")) {
      String needle = ENTRY_POINTS.get(name);
      List<String> outside = new ArrayList<>();
      try (Stream<Path> files = Files.walk(MAIN)) {
        for (Path file :
            files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
          if (file.equals(FACTS)) {
            continue;
          }
          for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
              continue;
            }
            if (line.contains(needle)) {
              outside.add(file.getFileName().toString());
            }
          }
        }
      }
      if (outside.isEmpty()) {
        fail(
            "ФАБРИКАТА "
                + name
                + " СЕ ВИКА САМО ОТ СВОЯ СОБСТВЕН КЛАС (или никъде).\n"
                + "  ⚠️ Отделно твърдение от горното нарочно: класът може да си вика фабриките сам"
                + " (например от toString или от друга фабрика) и тогава горният тест ще е зелен,"
                + " докато пропорцията остава недостижима отвън. Тук се иска викащ ИЗВЪН"
                + " BgWorkTimeFacts.java.");
      }
    }
  }
}
