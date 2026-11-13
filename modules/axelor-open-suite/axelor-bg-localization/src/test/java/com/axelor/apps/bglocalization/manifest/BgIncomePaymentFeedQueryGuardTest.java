package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ АВТО-ЗАХРАНВАНЕТО НЕ БИВА ДА ВИЖДА РЪЧНО ВЪВЕДЕН РЕД — и това се пази ОТ КОДА, а не от навик.
 *
 * <p><b>КАКВО ПАЗИ ТОЗИ ТЕСТ.</b> Ръчният ред е недосегаем по ЕДНА причина: той няма {@code
 * sourceRef}, а всяка заявка на захранването ограничава по това поле. Свали ли се ограничението от
 * една заявка — при рефакториране, при добавен трети източник, при „оптимизация“ на филтъра, —
 * ръчните редове мигом стават видими за прогона: той ще ги брои за свои, ще ги обновява с чужди
 * суми и ще ги връща на код корекция „0“.
 *
 * <p><b>⚠️ И ЗАЩО ТОВА НЕ СЕ ХВАЩА ОТ СВИДЕТЕЛ С ЧИСЛА.</b> Тест, който пуска прогона върху
 * подготвени редове, доказва поведението за СВОИТЕ данни. Заявка без ограничение обаче се проваля
 * само когато в базата има ръчен ред със същия вид доход и в същия период — състояние, което никой
 * тест не създава случайно, а всяка продукционна база има.
 *
 * <p><b>ЕДНО ИЗКЛЮЧЕНИЕ, ОБЯВЕНО.</b> Броячът на ръчните редове ЧЕТЕ точно тях — {@code
 * self.sourceRef IS NULL}. Той обаче не пише: числото му отива в границите на прогона, за да ЛИЧИ,
 * че редовете са били там и не са пипани. Затова правилото е „всяка заявка СПОМЕНАВА {@code
 * sourceRef}“, а не „всяка заявка иска {@code = :ref}“: първото допуска брояча, второто би го
 * обявило за нарушение и щеше да ни накара да го изтрием — тоест да загубим точно числото, което
 * доказва твърдението.
 */
class BgIncomePaymentFeedQueryGuardTest {

  private static final String FEED_SERVICE =
      "src/main/java/com/axelor/apps/bglocalization/service/zddfl/BgIncomePaymentFeedService.java";

  /** Полето, което прави ръчния ред невидим за прогона. */
  private static final String KEY_FIELD = "self.sourceRef";

  /**
   * Носителят на регистъра в услугата — заявките по него са тези, които трябва да са ограничени.
   */
  private static final String REGISTER_REPO = "paymentRepo";

  private static final Pattern CHAIN =
      Pattern.compile(
          "([A-Za-z_$][\\w$]*)\\s*\\.all\\(\\)\\s*\\.filter\\(\\s*"
              + "((?:\"(?:[^\"\\\\]|\\\\.)*\"\\s*\\+?\\s*)+)\\)");

  private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

  @Test
  @DisplayName("всяка заявка на захранването към регистъра ограничава по sourceRef")
  void everyRegisterQueryIsKeyedBySourceRef() throws IOException {
    String source = read(FEED_SERVICE);

    List<String> unguarded = new ArrayList<>();
    int checked = 0;

    Matcher chain = CHAIN.matcher(source);
    while (chain.find()) {
      if (!REGISTER_REPO.equals(chain.group(1))) {
        continue;
      }
      checked++;
      String jpql = unquote(chain.group(2));
      if (!jpql.contains(KEY_FIELD)) {
        unguarded.add(jpql);
      }
    }

    assertTrue(
        checked > 0,
        "нито една заявка към регистъра не се разпозна в "
            + FEED_SERVICE
            + " — тестът е останал без обхват и мълчи, вместо да пази");
    assertTrue(
        unguarded.isEmpty(),
        "заявка на захранването към регистъра НЕ ограничава по "
            + KEY_FIELD
            + ". Ръчно въведените редове нямат референция и точно това ги пази: без ограничението"
            + " прогонът ще ги вземе за свои — ще ги обнови с чужди суми и ще върне код корекция"
            + " „1“ или „8“ обратно на „0“. Заявката:\n  "
            + String.join("\n  ", unguarded));
  }

  /**
   * ⚠️ И ВТОРАТА ПОЛОВИНА: захранването НЕ трие.
   *
   * <p>Дори ограничена по референция, заявка с {@code remove} би изтрила СВОЙ ред при промяна на
   * ключа му — например когато договорът смени вида доход и старият ред остане без съответствие.
   * Такъв ред обаче може вече да е подаден в справка; той се заличава с код корекция „8“, а не с
   * изтриване. Затова прогонът само създава и обновява.
   */
  @Test
  @DisplayName("захранването нито трие, нито изчиства редове на регистъра")
  void theFeedNeverDeletes() throws IOException {
    String source = read(FEED_SERVICE);
    for (String forbidden : new String[] {".remove(", ".delete(", "removeAll("}) {
      assertTrue(
          !source.contains(forbidden),
          "BgIncomePaymentFeedService съдържа „"
              + forbidden
              + "“. Прогонът само СЪЗДАВА и ОБНОВЯВА: вече подаден ред се заличава с код корекция"
              + " „8“, който остава видим в справката, а не с изтриване, което не оставя следа.");
    }
  }

  private static String read(String relative) throws IOException {
    Path file = BgDomainColumns.moduleRoot().resolve(relative);
    assertTrue(Files.isRegularFile(file), "липсва " + file);
    return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
  }

  private static String unquote(String literals) {
    StringBuilder out = new StringBuilder();
    Matcher piece = LITERAL.matcher(literals);
    while (piece.find()) {
      out.append(piece.group(1));
    }
    return out.toString();
  }
}
