package com.axelor.apps.bglocalization.traps;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ КАПАНЪТ: ГЕНЕРИРАНИЯТ GETTER НЕ ВРЪЩА null.
 *
 * <p>Axelor генерира getter-ите така: {@code return field == null ? DEFAULT : field}. Заради това
 * условие от вида {@code if (x.getSomeDecimal() == null)} НИКОГА не е вярно — то е мъртъв код и
 * мълчи. Мълчи и билдът, и ревюто: няма предупреждение за винаги-невярно условие.
 *
 * <p><b>Кои типове получават стойност по подразбиране</b> — преброено от {@code build/src-gen}, не
 * по спомен:
 *
 * <pre>
 *   BigDecimal → BigDecimal.ZERO   (404 getter-а)
 *   Boolean    → Boolean.FALSE     (506)
 *   Integer    → 0                 (291)
 *   Long       → 0L                (8)
 * </pre>
 *
 * <p>Всичко останало — {@code String}, {@code LocalDate}, връзките към други записи, списъците и
 * множествата — връща полето както е, тоест наистина може да е null и проверката за null там е
 * НЕОБХОДИМА. Изключения в обратната посока: {@code getId()} е {@code Long} БЕЗ подразбиране (нов
 * запис няма идентификатор) и {@code Model#getArchived()} е единственият {@code Boolean} без
 * подразбиране.
 *
 * <p><b>Защо тест, а не правило в README.</b> Капанът ни ухапа два пъти — веднъж при откриването му
 * и веднъж при основната заплата на фиша, където копирането от договора не се беше случило нито
 * веднъж. Записано правило не спира третия път; червен билд спира.
 *
 * <p><b>Какво този тест НЕ хваща.</b> Име, което съществува и с, и без подразбиране (например
 * {@code getStatusSelect} — {@code Integer} на фактурата, {@code String} на нашите документи), се
 * пропуска нарочно: от текста не се вижда какъв е типът на получателя. Такива места се четат на
 * ръка. Пропуска се и всичко извън този модул.
 */
class BgDefaultedGetterTest {

  private static final Path SRC_GEN = Paths.get("build/src-gen/java");
  private static final List<Path> SOURCES =
      Arrays.asList(Paths.get("src/main/java"), Paths.get("src/test/java"));

  /** {@code public BigDecimal getFoo() {} → return foo == null ? BigDecimal.ZERO : foo;} */
  private static final Pattern GENERATED =
      Pattern.compile(
          "public\\s+([\\w<>,\\. ]+?)\\s+(get\\w+|is\\w+)\\(\\)\\s*\\{\\s*\\n\\s*return\\s+([^;]+);");

  private static final Pattern NULL_COMPARISON =
      Pattern.compile("(\\w+)\\(\\)\\s*(?:==|!=)\\s*null");
  private static final Pattern OBJECTS_NULL =
      Pattern.compile("Objects\\.(?:isNull|nonNull)\\(\\s*[\\w\\.\\(\\)]*?(\\w+)\\(\\)\\s*\\)");
  private static final Pattern OPTIONAL_NULL =
      Pattern.compile("Optional\\.ofNullable\\(\\s*[\\w\\.\\(\\)]*?(\\w+)\\(\\)\\s*\\)");
  private static final Pattern LOCAL_FROM_GETTER =
      Pattern.compile(
          "^\\s*(?:final\\s+)?(?:BigDecimal|Integer|Long|Boolean)\\s+(\\w+)\\s*=\\s*"
              + "[\\w\\.\\(\\)]*?\\b(get\\w+|is\\w+)\\(\\)\\s*;");

  /**
   * ⚠️ ИЗКЛЮЧЕНИЯТА СЕ ПИШАТ С ПРИЧИНА, ИНАК СА КРИЕНЕ НА ГРЕШКА.
   *
   * <p>Тук влиза само едно: име на getter, което СЪВПАДА с генерирано, но принадлежи на наш ръчно
   * написан клас, чието поле наистина може да е null. Форматът е {@code файл#getter}.
   */
  private static final Map<String, String> EXEMPT = new HashMap<>();

  static {
    EXEMPT.put(
        "BgInsurableIncomeService.java#getAmount",
        "BgMinIncomeResult е ръчен клас, не запис: неопределеният МОД наистина носи null сума");
    EXEMPT.put("BgContributionPreviewController.java#getAmount", "същият BgMinIncomeResult");
    EXEMPT.put(
        "BgContributionPreviewController.java#getQualificationGroup", "същият BgMinIncomeResult");
    EXEMPT.put(
        "BgArt73Para1FileValidator.java#getPersonCount",
        "BgArt73Para1FileRow е ръчен DTO, не запис: колона 14 се попълва САМО за кодовете с брой"
            + " лица (указание за попълване т. 5), тоест празното е ОТГОВОР. Точно защото"
            + " BgArt73Para1Record.getPersonCount() връща 0 вместо null, събирачът превежда"
            + " нулата в null — виж BgArt73Para1FileCollector#personCount");
    EXEMPT.put(
        "BgArt73Para6FileValidator.java#getIncome",
        "BgArt73Para6FileIncomeRow е ръчен DTO, не запис: „Ред и формат“ (заповед №"
            + " З-ЦУ-1877/05.12.2019 г.) прави дохода ЗАДЪЛЖИТЕЛЕН за прием, тоест ЛИПСАТА му е"
            + " находка, която трябва да бъде назована. Върне ли се 0 вместо null, валидаторът"
            + " щеше да приеме непопълнен ред за попълнен с нула — а файл с липсващо задължително"
            + " поле се отхвърля ЦЕЛИЯТ (т. 3)");
    EXEMPT.put(
        "BgArt73Para6FileValidator.java#getAdvanceTax",
        "същият ръчен DTO и същото правило: авансовият данък по чл. 42 е задължителен за прием");
    EXEMPT.put(
        "BgSaftXmlWriter.java#getPeriodYear",
        "SaftInvoice и SaftPayment са ръчни DTO-та, не записи: Invoice/PeriodYear и"
            + " Payment/PeriodYear са 0..1 в схемата на SAF-T, тоест null значи „елементът не се"
            + " пише“. Нула вместо null би излязла във файла като година 0");
    EXEMPT.put(
        "BgSaftProtocolInvoices.java#getRate",
        "BgSaftDiaryCells.Cell е ръчен клас, не запис: ставката е null, когато не е еднозначна"
            + " (смесени ставки или без ставка) — тогава незадължителният TaxPercentage не се"
            + " пише. Нула вместо null би излязла във файла като 0 %");
    EXEMPT.put(
        "BgSaftInvoicesAssembler.java#getPartnerId",
        "BgSaftProtocolInvoices.DiaryDocument е ръчен клас, не запис: протоколът без доставка (чл."
            + " 79/79а) няма партньор. Нула вместо null би се дочитала като партньор с id 0");
  }

  /** Име на getter → стойностите по подразбиране, които генераторът му е дал. */
  private final Map<String, Set<String>> defaulted = new HashMap<>();

  /** Име на getter → класовете, в които СЪЩОТО име се генерира БЕЗ подразбиране. */
  private final Map<String, Set<String>> plain = new HashMap<>();

  private void indexGeneratedGetters() throws IOException {
    Assumptions.assumeTrue(
        Files.isDirectory(SRC_GEN),
        "build/src-gen липсва — тестът се пропуска вместо да мине празен");
    try (Stream<Path> files = Files.walk(SRC_GEN)) {
      for (Path f :
          files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
        String src = new String(Files.readAllBytes(f), StandardCharsets.UTF_8);
        Matcher m = GENERATED.matcher(src);
        while (m.find()) {
          String name = m.group(2);
          String body = m.group(3);
          if (body.contains("== null ?")) {
            defaulted
                .computeIfAbsent(name, k -> new HashSet<>())
                .add(body.substring(body.indexOf('?') + 1).split(":")[0].trim());
          } else {
            plain.computeIfAbsent(name, k -> new HashSet<>()).add(f.getFileName().toString());
          }
        }
      }
    }
    assertFalse(defaulted.isEmpty(), "нито един генериран getter — индексирането е сгрешено");
    // Котвата: ако това спре да е вярно, генераторът е сменил поведението си и целият тест губи
    // смисъл, вместо да мине зелен.
    assertTrue(
        defaulted.get("getBgGrossTotal").contains("BigDecimal.ZERO"),
        "getBgGrossTotal вече не връща ZERO — проверете какво прави генераторът");
  }

  /** Мъртво ли е сравнение с null върху getter с това име. */
  private boolean isDead(String getter) {
    return defaulted.containsKey(getter) && !plain.containsKey(getter);
  }

  @Test
  @DisplayName("⚠️ Никъде в модула decimal/Integer/Boolean getter не се сравнява с null")
  void noDefaultedGetterIsComparedToNull() throws IOException {
    indexGeneratedGetters();
    List<String> dead = new ArrayList<>();

    for (Path root : SOURCES) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> files = Files.walk(root)) {
        for (Path f :
            files.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
          List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
          String file = f.getFileName().toString();
          for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            for (Pattern p : Arrays.asList(NULL_COMPARISON, OBJECTS_NULL, OPTIONAL_NULL)) {
              Matcher m = p.matcher(line);
              while (m.find()) {
                String getter = m.group(1);
                if (!getter.startsWith("get") && !getter.startsWith("is")) {
                  continue;
                }
                if (!isDead(getter) || EXEMPT.containsKey(file + "#" + getter)) {
                  continue;
                }
                dead.add(
                    f
                        + ":"
                        + (i + 1)
                        + "  "
                        + getter
                        + "() → "
                        + defaulted.get(getter)
                        + "\n      "
                        + line.trim());
              }
            }
            // и същото през междинна променлива: BigDecimal x = doc.getFoo(); ... if (x == null)
            Matcher local = LOCAL_FROM_GETTER.matcher(line);
            if (local.find()
                && isDead(local.group(2))
                && !EXEMPT.containsKey(file + "#" + local.group(2))) {
              Pattern use =
                  Pattern.compile("\\b" + Pattern.quote(local.group(1)) + "\\s*(?:==|!=)\\s*null");
              // ⚠️ БЕЗ break: ако променливата се пита за null на две места, и двете трябва да се
              // видят наведнъж. Иначе поправката върви на кръгове, по едно място на билд.
              for (int j = i + 1; j < Math.min(i + 41, lines.size()); j++) {
                if (use.matcher(lines.get(j)).find()) {
                  dead.add(
                      f
                          + ":"
                          + (j + 1)
                          + "  променливата „"
                          + local.group(1)
                          + "“ идва от "
                          + local.group(2)
                          + "() → "
                          + defaulted.get(local.group(2))
                          + "\n      "
                          + lines.get(j).trim());
                }
              }
            }
          }
        }
      }
    }

    if (!dead.isEmpty()) {
      fail(
          "МЪРТВО УСЛОВИЕ: генерираният getter връща стойност по подразбиране, никога null, тоест"
              + " сравнението по-долу е винаги невярно и мълчи.\n\n  "
              + String.join("\n  ", dead)
              + "\n\n  Поправка: питайте за СТОЙНОСТТА — signum() == 0, compareTo(ZERO), == 0,"
              + " Boolean.TRUE.equals(...). ⚠️ Проверката за null се МАХА, а не се оставя до"
              + " работещата: оставена, тя учи следващия читател, че полето може да е null.\n"
              + "  Ако полето наистина може да е null (ръчно написан клас със същото име на"
              + " getter), добавете го в EXEMPT — С ПРИЧИНА.");
    }
  }
}
