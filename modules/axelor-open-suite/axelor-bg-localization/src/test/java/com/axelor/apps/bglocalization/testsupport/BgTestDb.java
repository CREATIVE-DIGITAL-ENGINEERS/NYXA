package com.axelor.apps.bglocalization.testsupport;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * БАЗАТА, СРЕЩУ КОЯТО МЕРЯТ DB ТЕСТОВЕТЕ — от {@code axelor-config.properties} и отникъде другаде.
 *
 * <p><b>Защо.</b> DB тест, който отваря връзката така: {@code
 * System.getenv().getOrDefault("BG_DB_URL", "jdbc:postgresql://localhost:5432/axelor")}, остава на
 * старата база, щом приложението се премести (от {@code axelor} на {@code nyxa}). Тогава тестовете
 * мерят ЗАВАРЕНАТА {@code axelor} — база, в която никой не пуска App Install — вместо базата, която
 * приложението пълни. Ефектът не е червено: заварената база или няма колоната, или носи стари
 * редове, затова тестовете се ПРОПУСКАТ тихо или потвърждават вчерашните числа. Един и същ билд
 * дава различен резултат само по смяна на {@code BG_DB_URL}.
 *
 * <p><b>Затова базата има ЕДИН източник</b> — {@code db.default.url} в {@code
 * axelor-config.properties}, същият ред, който чете и приложението. Няма подразбиране, няма env
 * променлива, с която базата да се подмени: {@code BG_DB_URL} е премахната нарочно. Пита се
 * конфигът или не се пита никой.
 *
 * <p><b>Липсва конфиг или празен {@code db.default.url} → отказ с назована причина</b> ({@link
 * BgSkip}), не мълчалива втора база. Пропуснат тест с изречение в отчета е състояние на средата,
 * което човек вижда; зелен тест срещу чужда база е лъжа, която никой не вижда.
 *
 * <p><b>Защо потребителят и паролата ОСТАВАТ подменяеми</b> ({@code BG_DB_USER}, {@code
 * BG_DB_PASSWORD}). Те са свойство на машината, не на приложението: комитнатият конфиг иска роля
 * {@code nyxa}, а по локалните инсталации съществува само {@code axelor} и тя притежава двете бази.
 * Сгрешен потребител дава ОТКАЗАНА връзка — шумно и веднага; сгрешена база дава зелено срещу други
 * данни. Различни рискове, различно третиране.
 *
 * <p><b>Кой конфиг.</b> Търси се {@code src/main/resources/axelor-config.properties} нагоре от
 * работната директория, а {@code AXELOR_CONFIG} го посочва изрично. Променливата НЕ е измислена
 * тук: {@code SettingsUtils.EXTERNAL_CONFIG_ENV} на платформата е същата, тоест насочиш ли
 * приложението към друг конфиг, тестовете тръгват след него. Това е път до същия единствен
 * източник, не втора база.
 */
public final class BgTestDb {

  /** Пътят на конфига спрямо корена на репото — същият файл, който чете приложението. */
  public static final String CONFIG_RELATIVE = "src/main/resources/axelor-config.properties";

  /**
   * Изрично посочен конфиг — същата променлива, която чете и платформата ({@code
   * SettingsUtils.EXTERNAL_CONFIG_ENV}), за да не се разминат приложението и тестовете.
   */
  public static final String CONFIG_ENV = "AXELOR_CONFIG";

  private static final String KEY_URL = "db.default.url";
  private static final String KEY_USER = "db.default.user";
  private static final String KEY_PASSWORD = "db.default.password";

  /** Докъде нагоре се търси коренът — дълбочината на модула е 3, останалото е запас. */
  private static final int MAX_PARENTS = 12;

  private static Properties config;
  private static Path source;
  private static String failure;

  private BgTestDb() {}

  /**
   * Адресът на базата на приложението. Никога не подразбира: при липсващ конфиг или празен {@code
   * db.default.url} прекъсва теста с обявена причина.
   */
  public static String url() {
    return trim(config().getProperty(KEY_URL));
  }

  /** Потребител: {@code BG_DB_USER}, инак {@code db.default.user} от конфига. */
  public static String user() {
    return override("BG_DB_USER", KEY_USER);
  }

  /** Парола: {@code BG_DB_PASSWORD}, инак {@code db.default.password} от конфига. */
  public static String password() {
    return override("BG_DB_PASSWORD", KEY_PASSWORD);
  }

  /** Файлът, от който е прочетена базата — за съобщения, които трябва да са проследими. */
  public static Path configPath() {
    config();
    return source;
  }

  private static String override(String envName, String key) {
    String v = System.getenv(envName);
    if (v != null && !v.trim().isEmpty()) {
      return v.trim();
    }
    return trim(config().getProperty(key));
  }

  private static synchronized Properties config() {
    if (config == null && failure == null) {
      resolve();
    }
    if (failure != null) {
      BgSkip.because(failure);
    }
    return config;
  }

  private static void resolve() {
    Path path;
    String explicit = System.getenv(CONFIG_ENV);
    if (explicit != null && !explicit.trim().isEmpty()) {
      path = Paths.get(explicit.trim());
      if (!Files.isRegularFile(path)) {
        failure =
            CONFIG_ENV
                + " сочи "
                + path
                + ", където няма файл — базата на приложението е неизвестна, а тестът НЕ подразбира"
                + " втора.";
        return;
      }
    } else {
      path = search();
      if (path == null) {
        failure =
            "не е намерен "
                + CONFIG_RELATIVE
                + " нагоре от "
                + Paths.get("").toAbsolutePath()
                + " — базата на приложението е неизвестна, а тестът НЕ подразбира втора. Посочи"
                + " конфига с "
                + CONFIG_ENV
                + ".";
        return;
      }
    }
    Resolved r = read(path);
    failure = r.failure;
    config = r.props;
    source = r.failure == null ? path : null;
    if (config != null) {
      announce();
    }
  }

  /**
   * ⚠️ Един ред в лога — коя база е мерена. Отчетът сам НЕ казва това: „24 пропуснати“ изглежда
   * еднакво срещу вярната и срещу заварената база.
   */
  private static void announce() {
    System.out.println(
        "ℹ БАЗА (от "
            + source
            + "): "
            + trim(config.getProperty(KEY_URL))
            + " · потребител "
            + override("BG_DB_USER", KEY_USER));
  }

  /**
   * Прочит на ЕДИН файл: или свойствата, или причината. Изнесен от статичното състояние нарочно —
   * това е единственото място с решение („има ли база“), а решение без свидетел с примери не се
   * проверява. {@code BgTestDbTest} го вика директно върху временни файлове.
   */
  static Resolved read(Path path) {
    Properties props = new Properties();
    try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
      props.load(r);
    } catch (IOException e) {
      return Resolved.failed(
          path + " не се чете (" + e.getMessage() + ") — базата на приложението е неизвестна.");
    }
    if (trim(props.getProperty(KEY_URL)).isEmpty()) {
      return Resolved.failed(
          KEY_URL
              + " липсва или е празен в "
              + path
              + " — базата на приложението е неизвестна, а тестът НЕ подразбира втора.");
    }
    return Resolved.of(props);
  }

  /**
   * Резултатът от един прочит: попълнено е или {@code props}, или {@code failure}, никога двете.
   */
  static final class Resolved {

    final Properties props;
    final String failure;

    private Resolved(Properties props, String failure) {
      this.props = props;
      this.failure = failure;
    }

    static Resolved of(Properties props) {
      return new Resolved(props, null);
    }

    static Resolved failed(String failure) {
      return new Resolved(null, failure);
    }
  }

  private static Path search() {
    Path dir = Paths.get("").toAbsolutePath();
    for (int i = 0; dir != null && i <= MAX_PARENTS; i++, dir = dir.getParent()) {
      Path candidate = dir.resolve(CONFIG_RELATIVE);
      if (Files.isRegularFile(candidate)) {
        return candidate;
      }
    }
    return null;
  }

  private static String trim(String v) {
    return v == null ? "" : v.trim();
  }
}
