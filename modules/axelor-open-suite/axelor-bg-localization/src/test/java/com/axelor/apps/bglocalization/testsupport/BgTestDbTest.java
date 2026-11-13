package com.axelor.apps.bglocalization.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * ПАЗАЧЪТ НА ЕДИНСТВЕНИЯ ИЗТОЧНИК: срещу коя база мерят DB тестовете.
 *
 * <p><b>Какво пази.</b> DB тест със свой ред {@code getOrDefault("BG_DB_URL", "…/axelor")} остава
 * на старата база, щом приложението се премести на друга, и мери ЗАВАРЕНАТА {@code axelor}: тихо
 * пропуснат там, където колоната липсва, и зелен срещу вчерашни числа там, където има ред. Дефектът
 * не е в един файл — той е в това адресът на базата да се пише на много места. {@link BgTestDb} го
 * свежда до конфига на приложението; долният {@code #никой_тест_не_пише_свой_адрес_на_база} пази
 * това да не се разсее пак.
 *
 * <p>Тестът е чист: временни файлове и четене на изходния код, без Postgres.
 */
class BgTestDbTest {

  /**
   * Пакетът, който ИМА право да назовава база. Правилото не е „думата да не се среща“, а „адресът
   * да се решава на едно място“ — тук, където този клас го проверява. Всичко останало под {@code
   * src/test/java} е потребител на решението, не негов автор.
   */
  private static final String SOURCE_PACKAGE = "/testsupport/";

  @Test
  @DisplayName("конфиг с db.default.url се чете, без подразбиране")
  void чете_адреса_от_конфига(@TempDir Path dir) throws Exception {
    Path cfg =
        write(
            dir,
            "db.default.driver = org.postgresql.Driver",
            "db.default.url = jdbc:postgresql://localhost:5432/примерна",
            "db.default.user = потребител",
            "db.default.password = тайна");

    BgTestDb.Resolved r = BgTestDb.read(cfg);

    assertNull(r.failure, "конфиг с попълнен адрес не бива да се отказва");
    assertNotNull(r.props);
    assertEquals(
        "jdbc:postgresql://localhost:5432/примерна", r.props.getProperty("db.default.url"));
    assertEquals("потребител", r.props.getProperty("db.default.user"));
  }

  @Test
  @DisplayName("липсващ db.default.url → отказ с назована причина, не втора база")
  void без_адрес_отказва(@TempDir Path dir) throws Exception {
    Path cfg = write(dir, "db.default.driver = org.postgresql.Driver");

    BgTestDb.Resolved r = BgTestDb.read(cfg);

    assertNull(r.props, "без адрес не бива да се връщат свойства");
    assertNotNull(r.failure, "мълчаливото подразбиране е точно дефектът, който този клас премахва");
    assertTrue(r.failure.contains("db.default.url"), "причината именува ключа: " + r.failure);
    assertTrue(r.failure.contains(cfg.toString()), "причината именува файла: " + r.failure);
  }

  @Test
  @DisplayName("празен db.default.url се брои за липсващ")
  void празен_адрес_отказва(@TempDir Path dir) throws Exception {
    Path cfg = write(dir, "db.default.url =    ");

    BgTestDb.Resolved r = BgTestDb.read(cfg);

    assertNull(r.props);
    assertNotNull(r.failure, "празният ред е също толкова неизвестна база, колкото липсващият");
  }

  @Test
  @DisplayName("нечетим конфиг → отказ, който именува файла")
  void нечетим_конфиг_отказва(@TempDir Path dir) {
    Path missing = dir.resolve("няма-го.properties");

    BgTestDb.Resolved r = BgTestDb.read(missing);

    assertNull(r.props);
    assertNotNull(r.failure);
    assertTrue(r.failure.contains(missing.toString()), "причината именува файла: " + r.failure);
  }

  @Test
  @DisplayName("в репото се намира конфигът на ПРИЛОЖЕНИЕТО и от него излиза jdbc адрес")
  void намира_конфига_на_репото() {
    Path cfg = BgTestDb.configPath();

    assertNotNull(cfg, "конфигът на приложението не е намерен");
    assertEquals(
        "axelor-config.properties",
        cfg.getFileName().toString(),
        "адресът трябва да идва от конфига на приложението, не от друг файл");
    assertTrue(
        BgTestDb.url().startsWith("jdbc:"),
        "db.default.url в " + cfg + " не прилича на jdbc адрес: " + BgTestDb.url());
  }

  /**
   * ⚠️ Регресията, а не симптомът. Един върнат {@code getOrDefault("BG_DB_URL", …)} стига, за да
   * почне един тест пак да мери чужда база — и то без нито едно червено.
   */
  @Test
  @DisplayName("никой тест не пише свой адрес на база")
  void никой_тест_не_пише_свой_адрес_на_база() throws Exception {
    Path root = Paths.get("src/test/java");
    if (!Files.isDirectory(root)) {
      BgSkip.because(
          "работната директория е "
              + Paths.get("").toAbsolutePath()
              + ", а не модулът — изходният код на тестовете не е проверен за свои адреси на база.");
      return;
    }
    List<String> нарушения = new ArrayList<>();
    try (Stream<Path> files = Files.walk(root)) {
      for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
        if (f.toString().replace('\\', '/').contains(SOURCE_PACKAGE)) {
          continue;
        }
        List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
          String l = lines.get(i);
          if (l.contains("BG_DB_URL") || l.contains("jdbc:postgresql:")) {
            нарушения.add(f + ":" + (i + 1) + "  " + l.trim());
          }
        }
      }
    }
    if (!нарушения.isEmpty()) {
      fail(
          "адресът на базата се пише САМО в axelor-config.properties и се чете САМО през BgTestDb."
              + " Собствен адрес в теста води до мерене срещу заварена база:\n  "
              + String.join("\n  ", нарушения));
    }
  }

  private static Path write(Path dir, String... lines) throws Exception {
    Path cfg = dir.resolve("axelor-config.properties");
    Files.write(cfg, String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    return cfg;
  }
}
