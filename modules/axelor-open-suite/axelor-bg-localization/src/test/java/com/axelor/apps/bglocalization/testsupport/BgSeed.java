package com.axelor.apps.bglocalization.testsupport;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Чете началните данни ОТ ФАЙЛА, без Postgres и без App Install.
 *
 * <p><b>Защо изобщо.</b> Манифестните тестове гледат базата и това е вярно за въпроса „стигна ли
 * seed-ът дотам“. Но те падат и когато отговорът е „не, защото App Install не е пускан“ — причина,
 * която няма нищо общо с кода и важи на всяка чиста машина. Червено, което не значи нищо, е по-лошо
 * от липсващ тест: то приучава да не се гледа.
 *
 * <p>Затова всяко твърдение се проверява ДВА пъти и на два различни въпроса. Тук е файловата
 * половина: „вярно ли е записано“. Базовата половина пита „доставено ли е“ и се пропуска с обявена
 * причина, когато не е.
 *
 * <p>Всяко четене минава през {@link #rows}, тоест ограничението „en/ и fr/ са байт по байт
 * еднакви“ се проверява при ВСЯКО използване, а не в отделен тест, който някой може да не напише.
 */
public final class BgSeed {

  private static final String DIR = "src/main/resources/apps/init-data/%s/%s";
  private static final String SEP = ";";

  private BgSeed() {}

  /** Пътят до файла в дадения език. */
  public static Path path(String file, String lang) {
    return Paths.get(String.format(DIR, lang, file));
  }

  /**
   * Редовете БЕЗ заглавния, с проверено съвпадение между en/ и fr/.
   *
   * <p>Колоните се цепят по {@code ;} без обединяване на кавички: нито един ред, който тези тестове
   * четат, не носи разделителя в стойност. Ако някой ден занесе — цепенето ще даде повече колони и
   * проверката на броя им ще се обади.
   */
  public static List<String[]> rows(String file) {
    Path en = path(file, "en");
    Path fr = path(file, "fr");

    if (!Files.exists(en)) {
      fail("липсва seed файл: " + en.toAbsolutePath());
    }
    if (!Files.exists(fr)) {
      fail("липсва seed файл: " + fr.toAbsolutePath());
    }

    try {
      // ⚠️ ОГРАНИЧЕНИЕ НА МОДУЛА: всяка промяна влиза И в двете, байт по байт еднакво.
      if (!Arrays.equals(Files.readAllBytes(en), Files.readAllBytes(fr))) {
        fail(
            "en/ и fr/ се разминават за "
                + file
                + " — всяка промяна в началните данни влиза и в двете, байт по байт еднакво");
      }

      List<String[]> out = new ArrayList<>();
      List<String> lines = Files.readAllLines(en, StandardCharsets.UTF_8);
      int expected = splitQuoted(lines.get(0)).length;
      for (int i = 1; i < lines.size(); i++) {
        String line = lines.get(i);
        if (!line.isEmpty()) {
          checkArity(file, i + 1, line, expected);
          out.add(line.split(SEP, -1));
        }
      }
      if (out.isEmpty()) {
        fail(
            "seed файлът "
                + file
                + " няма нито един ред — това не е състояние, което тест бива да мине");
      }
      return out;
    } catch (IOException e) {
      fail("не се чете " + file + ": " + e.getMessage());
      return null;
    }
  }

  /**
   * Редовете, цепени С УВАЖЕНИЕ КЪМ КАВИЧКИТЕ — и пак с проверено съвпадение между en/ и fr/.
   *
   * <p><b>⚠️ ЗАЩО ОТДЕЛЕН ВХОД, А НЕ ПОПРАВКА НА {@link #rows}.</b> Коментарът на {@link #rows}
   * твърди, че „нито един ред, който тези тестове четат, не носи разделителя в стойност“. Това не е
   * вярно за всички файлове: в {@code bg_insured_type.csv} ТРИ реда — кодове 10, 82 и 90 — носят
   * точка и запетая ВЪТРЕ в кавичките на наименованието си, и голото цепене им дава съответно 17,
   * 13 и 13 колони вместо 12.
   *
   * <p><b>⚠️ И ОБЕЩАНАТА ПРОВЕРКА НЕ СЕ ОБАЖДА.</b> Същият коментар разчита, че „цепенето ще даде
   * повече колони и проверката на броя им ще се обади“. {@link #col} обаче пада само когато
   * колоните са ПО-МАЛКО от търсения номер; при ПОВЕЧЕ колони той връща съседна стойност — тоест
   * прочита {@code bgFloorType} на код 10 от средата на наименованието му и МИНАВА ЗЕЛЕНО. Точно
   * този тих прочит е причината този вход да съществува.
   *
   * <p>{@link #rows} остава непокътнат нарочно: заварените му читатели гледат редове без кавички,
   * за които двете цепения дават еднакъв резултат, и промяна там би сменила поведение, което никой
   * не е поискал.
   */
  public static List<String[]> quotedRows(String file) {
    // Минава през rows(...), за да остане живо ограничението „en/ и fr/ са байт по байт еднакви“.
    rows(file);
    try {
      List<String[]> out = new ArrayList<>();
      List<String> lines = Files.readAllLines(path(file, "en"), StandardCharsets.UTF_8);
      for (int i = 1; i < lines.size(); i++) {
        if (!lines.get(i).isEmpty()) {
          out.add(splitQuoted(lines.get(i)));
        }
      }
      return out;
    } catch (IOException e) {
      fail("не се чете " + file + ": " + e.getMessage());
      return null;
    }
  }

  /**
   * Заглавният ред, цепен по същото правило като {@link #quotedRows}.
   *
   * <p>Отделен вход, а не преизползван {@link #header}: заглавие и редове, цепени по различни
   * правила, дават разместени индекси — точно грешката, от която {@link #col} съществува да пази.
   */
  public static List<String> quotedHeader(String file) {
    try {
      List<String> lines = Files.readAllLines(path(file, "en"), StandardCharsets.UTF_8);
      return Arrays.asList(splitQuoted(lines.get(0)));
    } catch (IOException e) {
      fail("не се чете заглавният ред на " + file + ": " + e.getMessage());
      return null;
    }
  }

  /**
   * Ред с БРОЙ КОЛОНИ, различен от заглавния, спира четенето ШУМНО.
   *
   * <p><b>Защо тук, а не в отделен тест за един файл.</b> НЕЕКРАНИРАНА точка и запетая в бележката
   * на ред (например на фонд {@code PPF} в {@code bg_contribution_fund.csv}: „…парите отиват в НОИ;
   * това мени превода…“) разцепва бележката на две колони, опашката ѝ ляга върху {@code active}, а
   * истинското {@code true} пада извън реда. App Install отхвърля реда, фондът липсва в базата и по
   * каскада изчезват ВСИЧКИ вноски с този {@code fundCode} — за {@code PPF} 16 вноски и 2 пера,
   * тоест 12 на сто ППФ за първа категория изчезват от фиша БЕЗ НИТО ЕДНО ЧЕРВЕНО.
   *
   * <p><b>Защо файловите тестове сами не го хващат.</b> Цепене без сверка на броя колони със
   * заглавния ред връща реда, а {@link #col} пада само при ПО-МАЛКО колони от търсената — при
   * ПОВЕЧЕ той връща съседната стойност и минава зелено. Без тази проверка дефектът се вижда
   * единствено при импорт, върху база, която повечето пускания пропускат.
   *
   * <p>Затова проверката стои на входа, през който минава ВСЯКО четене на начални данни: хваща
   * целия клас за всеки seed файл, не само този ред, и пада веднага — без Postgres и без App
   * Install.
   *
   * <p>Броенето е С УВАЖЕНИЕ КЪМ КАВИЧКИТЕ, защото такова е и цепенето на вносителя: {@code
   * CSVImporter} чете през {@code CSVFile.DEFAULT} = {@code CSVFormat.DEFAULT}, тоест кавичка
   * {@code "} по RFC 4180. Голото броене би обявило за счупени и трите законно екранирани реда на
   * {@code bg_insured_type.csv} (кодове 10, 82 и 90), които вносителят чете правилно.
   */
  private static void checkArity(String file, int lineNumber, String line, int expected) {
    int actual = splitQuoted(line).length;
    if (actual != expected) {
      fail(
          "seed файлът "
              + file
              + ", ред "
              + lineNumber
              + ": "
              + actual
              + " колони вместо "
              + expected
              + " — най-вероятно неекраниран „"
              + SEP
              + "“ в свободен текст. Обгради полето в кавички: …"
              + SEP
              + "\"текст с "
              + SEP
              + " вътре\""
              + SEP
              + "… Редът, както се чете сега: "
              + Arrays.toString(splitQuoted(line)));
    }
  }

  /**
   * Цепене по {@code ;} извън кавички; обгръщащите кавички се махат, а удвоената кавичка вътре в
   * поле се чете като една — правилото на RFC 4180, което и App Install прилага.
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
      } else if (ch == ';' && !quoted) {
        out.add(cell.toString());
        cell.setLength(0);
      } else {
        cell.append(ch);
      }
    }
    out.add(cell.toString());
    return out.toArray(new String[0]);
  }

  /** Заглавният ред, за да се четат колоните по ИМЕ, а не по запомнен номер. */
  public static List<String> header(String file) {
    try {
      List<String> lines = Files.readAllLines(path(file, "en"), StandardCharsets.UTF_8);
      return Arrays.asList(lines.get(0).split(SEP, -1));
    } catch (IOException e) {
      fail("не се чете заглавният ред на " + file + ": " + e.getMessage());
      return null;
    }
  }

  /**
   * Стойността на колона по ИМЕ.
   *
   * <p>Индексите се местят при всяко добавяне на колона и тихо разместват твърденията — точно тази
   * грешка не се вижда в ревю, защото и двете страни изглеждат правдоподобно.
   */
  public static String col(List<String> header, String[] row, String name) {
    int i = header.indexOf(name);
    if (i < 0) {
      fail("няма колона „" + name + "“ — има: " + header);
    }
    if (i >= row.length) {
      fail(
          "ред с "
              + row.length
              + " колони, а „"
              + name
              + "“ е №"
              + (i + 1)
              + ": "
              + Arrays.toString(row));
    }
    return row[i];
  }
}
