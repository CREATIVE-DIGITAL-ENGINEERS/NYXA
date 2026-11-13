package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Сверява ЗАРЕДЕНИТЕ евро стойности срещу левовите им оригинали по Закона за въвеждане на еврото в
 * Република България.
 *
 * <p><b>Защо изобщо има такъв тест.</b> Числата в {@code bg_payroll_parameter.csv} са в евро и
 * изглеждат като първични данни. Част от тях обаче са ИЗЧИСЛЕНИ от левов праг в по-стар акт: чл. 11
 * ал. 2 казва, че стойностите, посочени в левове в съществуващите правни инструменти, СЕ СЧИТАТ за
 * стойности в евро при прилагане на курса и правилата по чл. 12 и чл. 13. Тоест 620,20 не е
 * „непреобразувана МРЗ“, а самата МРЗ — и ако някой я преизчисли наново по друг курс или по друго
 * закръгляне, ще получи друго число и зелен билд. Тестът зачерква точно тази възможност.
 *
 * <p><b>Правилото, което тестът прилага.</b> чл. 12 ал. 1 — дели се на пълната шестцифрена стойност
 * на курса с всичките пет знака след запетаята; чл. 12 ал. 2 — курсът НЕ се закръглява и НЕ се
 * съкращава; чл. 13 ал. 1 — резултатът се закръглява до втория знак на базата на третия, тоест
 * „половина нагоре“.
 *
 * <p><b>Кои числа НЕ се проверяват тук.</b> Стойностите от ЗБДОО за 2026 г. — максималният
 * осигурителен доход и минималните осигурителни доходи по дейности — са дадени ПРЯКО В ЕВРО в чл. 9
 * на самия закон. Те не са преобразувани от левове и не подлежат на проверката: за тях левов
 * оригинал няма. Единственото изключение в списъка по-долу е {@code MIN_INCOME_SOL} за 01.01–31.07,
 * който ЗБДОО пренася от 2025 г. и чиято левова стойност е известна и записана в самия seed.
 *
 * <p><b>Няма нужда от база.</b> За разлика от останалите тестове в този пакет, тук се чете seed
 * файлът, а не заредената база — правилото е за СТОЙНОСТТА, не за вноса, и тестът не бива да се
 * пропуска мълчаливо, когато няма Postgres.
 *
 * <p><b>⚠️ ГРАНИЦАТА НА ТОЗИ ТЕСТ — прочети я, преди да го цитираш като доказателство.</b> Левовият
 * оригинал (1213, 1077, 660) и очакваният резултат (620,20, 550,66, 337,45) идват от ЕДНО И СЪЩО
 * убеждение. Тестът затваря кръга между тях, но не го закотвя за нищо извън себе си.
 *
 * <p>Какво ХВАЩА:
 *
 * <ul>
 *   <li>разминаване между {@code en/} и {@code fr/} — двете копия на seed-а се четат поотделно и се
 *       сверяват с едно и също очакване;
 *   <li>сменена или изгубена единица — редът трябва да е {@code EUR};
 *   <li>редактирана стойност в seed-а, независимо дали на ръка или от миграция;
 *   <li>приложено ДРУГО закръгляне (HALF_EVEN, отсичане) или ДРУГ курс — те дават друга стотинка;
 *   <li>изчезнал или раздвоен ред за даден код и период.
 * </ul>
 *
 * <p>Какво НЕ хваща:
 *
 * <ul>
 *   <li><b>ГРЕШЕН ЛЕВОВ ОРИГИНАЛ.</b> Ако МРЗ за 2026 г. не е 1213 лв, тестът остава ЗЕЛЕН и просто
 *       сверява една грешка срещу самата себе си. Зеленото тук значи „seed-ът е съгласуван с
 *       левовото число в този файл“, а НЕ „левовото число е вярно“;
 *   <li>дали параметърът изобщо е приложим за периода — периодите се проверяват другаде;
 *   <li>дали ЗБДОО не е дал НОВА стойност пряко в евро, която да измести преобразуваната.
 * </ul>
 *
 * <p>Затова левовите оригинали носят акта си в README на модула ({@code ПМС № 243 от 13.11.2025 г.}
 * за МРЗ 2026 и т. н.). Актът е котвата; този тест пази само че веднъж закотвеното не се е
 * разместило.
 *
 * <p>⚠️ Вноските за ДЗПО за месеци ПРЕДИ въвеждането на еврото се превалутират по чл. 35 ал. 3, в
 * изключение на чл. 13 ал. 1: до втория знак БЕЗ закръгляне, тоест с отсичане. Тук няма такъв
 * случай и правилото по-долу НЕ важи за тях. Виж README на модула.
 */
class BgEuroConversionTest {

  /**
   * Официалният курс, чл. 12 ал. 1 — пълната шестцифрена стойност с всичките пет знака след
   * запетаята. По чл. 12 ал. 2 не се закръглява и не се съкращава: делителят е точно това число,
   * никаква негова скъсена форма.
   */
  private static final BigDecimal RATE = new BigDecimal("1.95583");

  private static final Path SEED_EN =
      Paths.get("src/main/resources/apps/init-data/en/bg_payroll_parameter.csv");
  private static final Path SEED_FR =
      Paths.get("src/main/resources/apps/init-data/fr/bg_payroll_parameter.csv");

  /** Левовият оригинал, кодът на параметъра и датата, от която важи редът. */
  private static final class Case {
    final String bgn;
    final String code;
    final String validFrom;
    final String what;

    Case(String bgn, String code, String validFrom, String what) {
      this.bgn = bgn;
      this.code = code;
      this.validFrom = validFrom;
      this.what = what;
    }
  }

  private static final List<Case> CASES =
      List.of(
          new Case("1213", "MIN_WAGE", "2026-01-01", "минимална работна заплата"),
          new Case(
              "1077",
              "MIN_INCOME_SOL",
              "2026-01-01",
              "мин. осигурителен доход за СОЛ, 01.01–31.07"),
          new Case("660", "DISABILITY_RELIEF_MONTHLY", "2026-01-01", "ТЕЛК месечно облекчение"));

  // ── правилото ───────────────────────────────────────────────────────────

  /** Левове → евро по чл. 12 ал. 1 и чл. 13 ал. 1: делене на пълния курс, после HALF_UP до 2. */
  private static BigDecimal toEuro(String bgn) {
    return new BigDecimal(bgn).divide(RATE, 2, RoundingMode.HALF_UP);
  }

  // ── четене на seed-а ────────────────────────────────────────────────────

  private static List<String[]> rows(Path seed) throws IOException {
    List<String[]> out = new ArrayList<>();
    for (String line : Files.readAllLines(seed, StandardCharsets.UTF_8)) {
      if (line.isEmpty() || line.startsWith("importId")) {
        continue;
      }
      // 7 колони: importId;code;paramValue;unit;validFrom;validTo;description — описанието остава
      // цяло, каквото и да съдържа.
      out.add(line.split(";", 7));
    }
    return out;
  }

  private static String[] row(Path seed, String code, String validFrom) throws IOException {
    List<String[]> hits = new ArrayList<>();
    for (String[] r : rows(seed)) {
      if (code.equals(r[1]) && validFrom.equals(r[4])) {
        hits.add(r);
      }
    }
    assertEquals(
        1,
        hits.size(),
        () ->
            "очаквам точно един ред за "
                + code
                + " от "
                + validFrom
                + " в "
                + seed
                + ", намерих "
                + hits.size());
    return hits.get(0);
  }

  // ── тестовете ───────────────────────────────────────────────────────────

  @Test
  @DisplayName("чл. 12 ал. 2 · курсът е пълната стойност, нито закръглен, нито съкратен")
  void rateIsExact() {
    assertEquals(
        "1.95583", RATE.toPlainString(), "делителят трябва да е точно курсът по чл. 12 ал. 1");
    assertEquals(5, RATE.scale(), "пет знака след запетаята, чл. 12 ал. 2 забранява съкращаването");
  }

  @Test
  @DisplayName("чл. 12 и чл. 13 · заредените стойности съвпадат с изчислените от левовия оригинал")
  void seededValuesMatchTheRule() throws IOException {
    for (Case c : CASES) {
      BigDecimal expected = toEuro(c.bgn);
      for (Path seed : List.of(SEED_EN, SEED_FR)) {
        String[] r = row(seed, c.code, c.validFrom);
        BigDecimal seeded = new BigDecimal(r[2]);

        assertEquals("EUR", r[3], () -> c.code + " в " + seed + " трябва да е в евро");
        assertTrue(
            expected.compareTo(seeded) == 0,
            () ->
                c.what
                    + " ("
                    + c.code
                    + ", "
                    + seed
                    + "): "
                    + c.bgn
                    + " лв ÷ 1.95583 = "
                    + expected.toPlainString()
                    + " по чл. 12 и чл. 13, а в seed-а стои "
                    + seeded.toPlainString());
      }
    }
  }

  @Test
  @DisplayName("чл. 13 ал. 1 · третият знак решава: под 5 надолу, 5 и нагоре — нагоре")
  void roundingIsHalfUpOnTheThirdDigit() {
    // 1213 / 1.95583 = 620,19705… — третият знак е 7, значи нагоре.
    assertEquals("620.20", toEuro("1213").toPlainString());
    // 1077 / 1.95583 = 550,66135… — третият знак е 1, значи надолу.
    assertEquals("550.66", toEuro("1077").toPlainString());
    // 660 / 1.95583 = 337,45264… — третият знак е 2, значи надолу.
    assertEquals("337.45", toEuro("660").toPlainString());
  }
}
