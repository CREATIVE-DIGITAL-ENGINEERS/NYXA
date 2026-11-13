package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * МАШИННАТА СВЕРКА НА ШЕСТТЕ ФЛАГА срещу ДОСЛОВНО изброените в Приложение № 1 кодове.
 *
 * <p><b>Защо този тест е нужен.</b> Шестте флага на {@code BgInsuredType} са данни, които изглеждат
 * верни, докато нещо не ги сравни с наредбата. Емитерът на Д1 ги чете, тоест всяка грешка в тях
 * излиза върху декларация на живо лице.
 *
 * <p><b>⚠️ ПРИМЕР.</b> Код 92 („Морски лица“) трябва да е с {@code bgNoNetPay = true}, защото
 * забележката към т. 34 го изброява; с {@code false} този тест пада.
 *
 * <p><b>⚠️ СПИСЪЦИТЕ ТУК СА ПРЕПИС ОТ НАРЕДБАТА, И ТОВА Е ЕДИНСТВЕНОТО МЯСТО, КЪДЕТО ПРЕПИС Е
 * ПРАВИЛЕН.</b> Навсякъде другаде в модула списък в Java е дефект, защото се разминава с речника
 * при първата редакция. Тук РАЗМИНАВАНЕТО Е ЦЕЛТА: тестът съществува, за да сравни два независими
 * източника. Затова всеки списък носи цитата, от който е преписан, с номера на точката — за да може
 * човек да го свери с ДВ, а не с кода.
 *
 * <p>Източник: {@code ~/Downloads/НР_Н13_2024.pdf}, Приложение № 1 към чл. 2 ал. 1 (ДВ бр. 1/2020,
 * посл. изм. ДВ бр. 15/2024, в сила 01.01.2024).
 */
class BgD1FlagsAgainstNaredbaTest {

  private static final String FILE = "bg_insured_type.csv";

  /**
   * Заб. 1 към т. 12: „Точки 12.1, 12.2 и 12.3 се попълват за вид осигурен 01, 02, 03, 04, 08, 10,
   * 16, 18, 19, 20, 25, 26, 42, 72, 82, 95, 97 и 99.“
   */
  private static final String APPLIES_MOD = "01,02,03,04,08,10,16,18,19,20,25,26,42,72,82,95,97,99";

  /**
   * Заб. 2 към т. 12: „Точки 12.1, 12.2 и 12.3 се попълват за вид осигурен 94, когато
   * осигурителният доход на лицето се определя по реда на чл. 6, ал. 3 от КСО.“
   */
  private static final String APPLIES_MOD_CONDITIONAL = "94";

  /**
   * Т. 31: „Попълва се за лица с попълнен „Вид осигурен“ в т. 12 - 01, 02, 03, 04, 05, 06, 08, 09,
   * 10, 12, 14, 16, 17, 18, 20, 21, 24, 25, 26, 27, 42, 71, 72, 82, 83, 90, 92, 94, 95, 97 и 99.“
   *
   * <p>⚠️ Изброените са 31, нашите — 30. Разликата е код <b>21</b>, който ДВ бр. 15/2024 ПРЕМАХНА
   * от номенклатурата. Тоест остатък в текста на наредбата, не пропуск у нас — и това се проверява
   * отделно в {@link #removedCodesAreAbsentFromTheDictionary()}.
   *
   * <p><b>⚠️ И ТОЗИ СПИСЪК Е САМО ИЗБРОЯВАНЕТО — трима от изброените носят УСЛОВИЕ отгоре.</b>
   * Указанието продължава след списъка: код <b>14</b> попълва т. 31 „само от акционери, притежаващи
   * повече от 5 на сто от капитала“ (§ 1 т. 26 б. „и“ и „л“); код <b>12</b> — за доходи по б. „и“;
   * код <b>71</b> — само членовете на ЦИК. Флагът остава {@code true} и за тримата, защото този
   * тест сверява ИЗБРОЯВАНЕТО дословно; свален, той щеше да направи преписа неверен спрямо
   * наредбата и да отнеме единствената машинна проверка на самия списък. Условията живеят в {@code
   * BgD1InsuredTypeRules} — за код 14 вече и се прилагат ({@code taxPointsUndecidable}).
   */
  private static final String FILLS_T31 =
      "01,02,03,04,05,06,08,09,10,12,14,16,17,18,20,21,24,25,26,27,42,71,72,82,83,90,92,94,95,97,99";

  /**
   * Т. 16 позиции 3 и 4: „лицата с вид осигурен 9, 10, 12, 13, 14, 15, 22, 41, 71, 82, 86, 87, 88,
   * 89, 91, 92 и 96 попълват 0.“ Двете позиции носят ЕДИН И СЪЩ списък — проверено дословно.
   */
  private static final String ZERO_WORK_TIME = "9,10,12,13,14,15,22,41,71,82,86,87,88,89,91,92,96";

  /**
   * Т. 16.7 и 16.8: „Тази точка не се попълва за лицата с кодове 09, 10, 12, 13, 14, 15, 18, 19,
   * 20, 22, 27, 28, 41, 71, 82, 86, 87, 88, 89, 90, 91, 92 и 96.“ И двете точки — един и същ
   * списък.
   */
  private static final String NO_HOURS =
      "09,10,12,13,14,15,18,19,20,22,27,28,41,71,82,86,87,88,89,90,91,92,96";

  /**
   * Забележка към т. 34: „Точка 34 не се попълва, когато в т. 12 „Вид осигурен“ е попълнен някой от
   * следните кодове: 12, 13, 14, 15, 18, 19, 20, 22, 27, 28, 41, 86, 87, 88, 89, 90, 91, <b>92</b>,
   * 96, 97.“
   *
   * <p>⚠️ Особено внимание за 92. Морското лице е и с {@code bgIsSeafarer = true}, тоест
   * единственият ред, на който т. 34 и намалената данъчна ставка се срещат — сгрешеният флаг би му
   * дал нето, което наредбата не иска, точно на записа, който и без това е особен.
   */
  private static final String NO_NET_PAY =
      "12,13,14,15,18,19,20,22,27,28,41,86,87,88,89,90,91,92,96,97";

  /** Кодовете, премахнати от ДВ бр. 15/2024 — те НЕ бива да са в речника. */
  private static final String REMOVED_BY_DV_15_2024 = "07,21,23,93";

  /** „09“ и „9“ са един и същ код: наредбата ги пише и с, и без водеща нула. */
  private static String norm(String code) {
    String c = code == null ? "" : code.trim();
    while (c.length() > 1 && c.charAt(0) == '0') {
      c = c.substring(1);
    }
    return c;
  }

  private static Set<String> parse(String csv) {
    return Arrays.stream(csv.split(","))
        .map(BgD1FlagsAgainstNaredbaTest::norm)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /**
   * Кодовете от seed-а, за които този флаг е вдигнат.
   *
   * <p><b>⚠️ {@code quotedRows}, НЕ {@code rows} — и това не е стилов избор.</b> Три реда на този
   * файл носят ТОЧКА И ЗАПЕТАЯ ВЪТРЕ В КАВИЧКИ: кодове 10 („…клонове на чужд. ЮЛ; членове на СД…“),
   * 82 („…изборни длъжности; служители с духовно звание…“) и 90 („…само данък по чл.42; попълват се
   * само т.31 и 31а“). Наивното цепене по {@code ;} им дава ПОВЕЧЕ колони и всяка следваща стойност
   * се чете от съседната — тоест флаговете на тези три кода идват от средата на наименованието им.
   *
   * <p>С наивното цепене повечето твърдения тук падат, макар seed-ът да е НАРЕД: разминаването сочи
   * кодове 10, 82 и 90 — трите цитирани реда, — и „поправка“ на seed-а по това червено би счупила
   * верни данни. Javadoc-ът на {@link BgSeed#quotedRows} назовава същия капан и същия код 10.
   */
  private static Set<String> seedCodesWith(String flag) {
    List<String> h = BgSeed.quotedHeader(FILE);
    Set<String> out = new TreeSet<>();
    for (String[] r : BgSeed.quotedRows(FILE)) {
      if ("true".equals(BgSeed.col(h, r, flag))) {
        out.add(norm(BgSeed.col(h, r, "code")));
      }
    }
    return out;
  }

  /** Всички кодове, които речникът изобщо познава. Пак {@code quotedRows} — по същия довод. */
  private static Set<String> allSeedCodes() {
    List<String> h = BgSeed.quotedHeader(FILE);
    Set<String> out = new TreeSet<>();
    for (String[] r : BgSeed.quotedRows(FILE)) {
      out.add(norm(BgSeed.col(h, r, "code")));
    }
    return out;
  }

  /**
   * Сверява един флаг.
   *
   * <p>⚠️ Очакваното се СЕЧЕ с кодовете, които речникът познава. Иначе тестът щеше да пада за код
   * 21, който наредбата още изброява, а ДВ бр. 15/2024 премахна — тоест щеше да иска от нас да
   * върнем закрит код. Липсата на премахнатите се проверява отделно и в обратната посока.
   */
  private static void assertFlagMatches(String flag, String naredbaCodes, String point) {
    Set<String> known = allSeedCodes();
    Set<String> expected =
        parse(naredbaCodes).stream()
            .filter(known::contains)
            .collect(Collectors.toCollection(TreeSet::new));
    Set<String> actual = seedCodesWith(flag);

    Set<String> missing = new TreeSet<>(expected);
    missing.removeAll(actual);
    Set<String> extra = new TreeSet<>(actual);
    extra.removeAll(expected);

    assertEquals(
        expected,
        actual,
        "флагът "
            + flag
            + " (т. "
            + point
            + ") се разминава с Приложение № 1.\n  наредбата иска: "
            + expected
            + "\n  seed-ът дава:   "
            + actual
            + "\n  ЛИПСВАТ в seed-а: "
            + missing
            + "\n  В ПОВЕЧЕ в seed-а: "
            + extra
            + "\n⚠️ Флагът е ДАННИ и от него зависи какво влиза в декларацията на живо лице."
            + " Поправката е в bg_insured_type.csv — и в en/, и в fr/, байт по байт еднакво.");
  }

  @Test
  @DisplayName("т. 12.1/12.2/12.3 · bgAppliesMod носи точно 18-те кода на заб. 1")
  void appliesModMatchesTheNaredba() {
    assertFlagMatches("bgAppliesMod", APPLIES_MOD, "12.1/12.2/12.3");
  }

  @Test
  @DisplayName("т. 12 заб. 2 · bgAppliesModConditional е САМО код 94 (чл. 6 ал. 3 КСО)")
  void conditionalModIsOnlyCode94() {
    assertFlagMatches("bgAppliesModConditional", APPLIES_MOD_CONDITIONAL, "12 заб. 2");
  }

  @Test
  @DisplayName("т. 31 · bgFillsT31 носи изброените кодове, без премахнатия 21")
  void fillsT31MatchesTheNaredba() {
    assertFlagMatches("bgFillsT31", FILLS_T31, "31");
  }

  @Test
  @DisplayName("т. 16 поз. 3 и 4 · bgZeroWorkTime носи точно 17-те кода, които попълват 0")
  void zeroWorkTimeMatchesTheNaredba() {
    assertFlagMatches("bgZeroWorkTime", ZERO_WORK_TIME, "16 поз. 3/4");
  }

  @Test
  @DisplayName("т. 16.7 и 16.8 · bgNoHours носи точно 23-те кода без часове")
  void noHoursMatchesTheNaredba() {
    assertFlagMatches("bgNoHours", NO_HOURS, "16.7/16.8");
  }

  @Test
  @DisplayName("т. 34 · bgNoNetPay носи точно 20-те кода — ВКЛЮЧИТЕЛНО код 92 (морски лица)")
  void noNetPayMatchesTheNaredbaIncludingCode92() {
    assertFlagMatches("bgNoNetPay", NO_NET_PAY, "34");
  }

  @Test
  @DisplayName("т. 34 · код 92 поотделно — редът, заради който този файл се промени")
  void code92IsExcludedFromNetPay() {
    List<String> h = BgSeed.quotedHeader(FILE);
    String value = null;
    for (String[] r : BgSeed.quotedRows(FILE)) {
      if ("92".equals(norm(BgSeed.col(h, r, "code")))) {
        value = BgSeed.col(h, r, "bgNoNetPay");
      }
    }
    assertEquals(
        "true",
        value,
        "код 92 „Морски лица“ е изброен в забележката към т. 34 („…, 91, 92, 96, 97.“), тоест"
            + " т. 34 НЕ се попълва за него и bgNoNetPay трябва да е true. Стойността решава дали"
            + " морското лице получава нетно възнаграждение в декларацията, което не му се"
            + " полага.");
  }

  @Test
  @DisplayName("ДВ бр. 15/2024 · премахнатите кодове 07, 21, 23 и 93 ги няма в речника")
  void removedCodesAreAbsentFromTheDictionary() {
    Set<String> known = allSeedCodes();
    for (String removed : parse(REMOVED_BY_DV_15_2024)) {
      assertTrue(
          !known.contains(removed),
          "код "
              + removed
              + " е премахнат от номенклатурата с ДВ бр. 15/2024 (в сила 01.01.2024), но стои в"
              + " речника. ⚠️ Ако е върнат нарочно заради коригираща Д1 за минал период, това иска"
              + " validTo, а не мълчаливо присъствие — инак кодът се предлага за ТЕКУЩ период.");
    }
  }

  @Test
  @DisplayName("котва · речникът носи 43-те кода, за които тези списъци бяха сверени")
  void theDictionaryStillHasTheCodesTheseListsWereCheckedAgainst() {
    assertEquals(
        43,
        allSeedCodes().size(),
        "броят видове осигурен се промени. Това НЕ е дефект сам по себе си, но списъците в този"
            + " тест са преписани от Приложение № 1 към редакция ДВ бр. 15/2024 — нов или изпаднал"
            + " код значи, че трябва да се свери и текстът, не само броят.");
  }
}
