package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.db.BgCompensationType;
import com.axelor.apps.bglocalization.testsupport.BgDomainColumns;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import com.axelor.apps.bglocalization.testsupport.BgTestDb;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.persistence.Table;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ОСИГУРИТЕЛНОТО И ДАНЪЧНОТО ТРЕТИРАНЕ НА ОБЕЗЩЕТЕНИЯТА ПРИ ПРЕКРАТЯВАНЕ — характеризиращ тест.
 *
 * <p><b>Какво пази и какво НЕ пази.</b> Пази двойката флагове {@code socialContributionsDue} и
 * {@code isTaxable} за седемте обезщетения при прекратяване по чл. 222 КТ и чл. 106 ЗДСл, плюс чл.
 * 217 КТ. НЕ пази СУМАТА — базата и броят БТВ са отделен въпрос и се мерят от {@code
 * BgCompensationServiceTest}. Двете се бъркат лесно, защото се четат от един и същи ред: тук се
 * твърди само КАК СЕ ТРЕТИРА платеното, не КОЛКО се плаща.
 *
 * <p><b>Източникът е нормата, не спомен.</b> Осигуряване — изключване по НЕВДПОВ чл. 1 ал. 8 (т. 7
 * за КТ, т. 10 за ЗДСл), дължимост при „оставане без работа“ по КСО чл. 9 ал. 3 т. 5, която покрива
 * и двата акта. Данък — освобождаване по ЗДДФЛ чл. 24 ал. 2 т. 8. Матрицата е сверена срещу тези
 * три норми с двама независими проверяващи и стои в „Каталог обезщетения — пълен“, редове 10–13 и
 * 23–26.
 *
 * <p><b>⚠️ ДВА ВЪПРОСА, КОИТО СЕ БЪРКАТ.</b> По-ранният работен каталог обявяваше и трите алинеи на
 * чл. 106 ЗДСл за без вноски „по КСО чл. 6 ал. 5а“, и на същото основание правеше ал. 2
 * необлагаема. Чл. 6 ал. 5а отговаря на друг въпрос — КОЙ внася вноската, тоест РАЗПРЕДЕЛЕНИЕТО ѝ
 * между платците при държавния служител. Дали сумата изобщо е осигурителен доход решават НЕВДПОВ
 * чл. 1 ал. 8 (изключване) и КСО чл. 9 ал. 3 т. 5 (дължимост). Смесени, двете дават точно обратния
 * отговор за ал. 2: „без вноски“ вместо „с вноски“.
 *
 * <p><b>⚠️ Оттам и какво този клас НЕ прави.</b> Позоваванията на чл. 6 ал. 5а в {@code
 * bg_insurance_contribution}, в {@code manifest/zbdoo-2026.yml} и в манифестните тестове са ЗА
 * РАЗПРЕДЕЛЕНИЕТО, верни са на мястото си и НЕ се пипат. Този клас пази двойката флагове, не
 * ставките и не разпределението им.
 *
 * <p><b>ЧЛ. 106 СЕ ТРЕТИРА ПО АЛИНЕЯ.</b> Трите му режима не са един вид: ал. 2 е с вноски и данък,
 * ал. 3 и ал. 3а са без нито едно, ал. 4 е без вноски но с данък. Дискриминаторът е самият ред в
 * каталога — един ред на алинея, с {@code legalBasis} до алинеята и {@code code} от вида {@code
 * comp_zdsl_106_<алинея>}. Затова тестът изброява алинеите поотделно: сборно твърдение („чл. 106 е
 * необлагаем“) минава зелено и при разменени флагове.
 *
 * <p><b>⚠️ ОСМИЯТ РЕД НЕ Е ПРИ ПРЕКРАТЯВАНЕ, и това е нарочно.</b> Чл. 217 КТ се поражда от СЪБИТИЕ
 * ПО ВРЕМЕ на правоотношението — предписание за трудоустрояване, което работодателят не е изпълнил
 * — а не от края му. Стои тук, защото въпросът, който този клас пази, е двойката флагове, и той е
 * един и същ за всяко обезщетение. Разделен в свой клас, чл. 217 щеше да получи собствена матрица,
 * а две матрици за едно правило се разсинхронизират.
 *
 * <p><b>⚠️ ЧЛ. 217 СЕ ОСИГУРЯВА ВЪРХУ СЕБЕ СИ, чл. 213 и чл. 214 — не.</b> И трите са „не работи по
 * вина на работодателя“ и трите се осигуряват, но по РАЗЛИЧНА БАЗА: КСО чл. 9 ал. 3 т. 1 (чл.
 * 213/214) внася върху ПОСЛЕДНОТО БРУТНО ВЪЗНАГРАЖДЕНИЕ, тоест върху друга сума, а т. 4 (чл. 217)
 * внася ВЪРХУ ПОЛАГАЩОТО СЕ ОБЕЗЩЕТЕНИЕ, тоест върху самия ред. Затова {@code bgIsInsuranceIncome =
 * true} на перо 928 стои на СВОЕ основание.
 *
 * <p><b>⚠️ И ТОЧНО ЗАТОВА ЧЛ. 213 И ЧЛ. 214 НЕ СА В {@link #MATRIX}.</b> Днес пера 911, 912 и 913
 * също носят {@code bgIsInsuranceIncome = true} — сверено в seed-а, не предположено. Дали е вярно е
 * ОТДЕЛЕН въпрос: базата им по т. 1 е последното брутно, не обезщетението, и това е точно работата,
 * оставена на WS-3c. Вкарани тук, те биха получили закована стойност от клас, който не е питал този
 * въпрос — а закованото очакване е по-трудно за поправяне от данните. Матрицата пази двойката
 * флагове на каталога; огледалото към фиша важи, докато {@code socialContributionsDue} и {@code
 * bgIsInsuranceIncome} отговарят на един и същи въпрос, а за тези три реда те не отговарят.
 *
 * <p><b>Две половини, както навсякъде в този пакет.</b> Файловата пита „вярно ли е записано“ и тече
 * без Postgres; базовата пита „доставено ли е“ и се пропуска с обявена причина, когато App Install
 * не е пускан. Слети, отговорът „App Install не е пускан“ би маскирал отговора „матрицата е
 * сгрешена“.
 */
class BgTerminationCompensationTreatmentTest {

  private static final String CATALOGUE = "bg_compensation_type.csv";
  private static final String PAY_SLIP_LINES = "bg_pay_slip_line_type_compensation.csv";

  private static final String TABLE =
      BgDomainColumns.tableName(BgCompensationType.class.getAnnotation(Table.class).name())
          .toLowerCase(Locale.ROOT);

  /**
   * ЗАКОВАНАТА МАТРИЦА — осем реда, всеки със своето основание.
   *
   * <p>Стойностите са {@code socialContributionsDue} и {@code isTaxable} в този ред. Разминаване
   * тук значи сгрешен seed, не сгрешено очакване: числата са преписани от класифицирания каталог, а
   * не изведени от кода.
   */
  private static final Map<String, Treatment> MATRIX = new LinkedHashMap<>();

  static {
    // „ОСТАВАНЕ БЕЗ РАБОТА“ — и двете ДА. Осигуряването е по КСО чл. 9 ал. 3 т. 5, която назовава
    // КТ, ЗДСл и ЗВО заедно; наборът фондове е само Пенсии и ДЗПО (ДЗПО — за родените след
    // 31.12.1959), без ЗО, ОЗМ, ТЗПБ и Безработица. Д1 код 27. Данъчната база е обезщетението
    // МИНУС личните пенсионни вноски.
    MATRIX.put("comp_kt_222_1", new Treatment("чл. 222 ал. 1 КТ", true, true, "27"));
    MATRIX.put("comp_zdsl_106_2", new Treatment("чл. 106 ал. 2 ЗДСл", true, true, "27"));

    // „ПЕНСИОНИРАНЕ / БОЛЕСТ“ — и двете НЕ. НЕВДПОВ чл. 1 ал. 8 изключва сумата от осигурителния
    // доход (т. 7 за КТ, т. 10 за ЗДСл), а ЗДДФЛ чл. 24 ал. 2 т. 8 я освобождава от данък.
    MATRIX.put("comp_kt_222_2", new Treatment("чл. 222 ал. 2 КТ", false, false, ""));
    MATRIX.put("comp_kt_222_3", new Treatment("чл. 222 ал. 3 КТ", false, false, ""));
    MATRIX.put("comp_zdsl_106_3", new Treatment("чл. 106 ал. 3 ЗДСл", false, false, ""));
    // ⚠️ АЛ. 3а Е РЕШЕНИЕ, НЕ ПРОЧИТ. ЗДДФЛ чл. 24 ал. 2 т. 8 изброява от чл. 106 само ал. 3, тоест
    // буквално ал. 3а е облагаема. Решението е да се следва режимът на ал. 3, защото обезщетението
    // е пенсионно по същността си.
    //
    // ⚠️ АНАЛОГЪТ ПО КТ — ЧЛ. 222 АЛ. 4 — НЕ Е ОТВОРЕН, но и НЕ Е трети ред: ал. 4 е покрита като
    // ТРИГЕР на comp_kt_222_3, не като собствен ред. Двата случая изглеждат еднакви и не са: при
    // ал. 3а режимът на ал. 3 се следва по аналогия, по преценка, а при ал. 4 самият закон казва
    // „Алинея 3 се прилага“ — необлагаемостта по ЗДДФЛ чл. 24 ал. 2
    // т. 8 („ал. 2 и 3“) стига до нея по ПРЕПРАЩАНЕ, а НЕВД чл. 1 ал. 8 т. 7 я назовава ПРЯКО
    // („чл. 222, ал. 2, 3 и 4“). Виж articleTwoTwentyTwoParagraphFourHasNoRowOfItsOwn().
    MATRIX.put("comp_zdsl_106_3a", new Treatment("чл. 106 ал. 3а ЗДСл", false, false, ""));

    // „НЕСПАЗЕНО ПРЕДИЗВЕСТИЕ“ — САМО ДАНЪК. Единствената алинея на чл. 106 с различна двойка:
    // НЕВДПОВ я изключва от осигурителния доход, но ЗДДФЛ чл. 24 ал. 2 т. 8 не я изброява.
    MATRIX.put("comp_zdsl_106_4", new Treatment("чл. 106 ал. 4 ЗДСл", false, true, ""));

    // „ТРУДОУСТРОЯВАНЕ БЕЗ ПРЕДОСТАВЕНА РАБОТА“ — и двете ДА. Осмият ред и единственият, който НЕ е
    // при прекратяване. Осигуряването е по КСО чл. 9 ал. 3 т. 4, която за разлика от т. 1, 2 и 3
    // внася „върху ПОЛАГАЩОТО СЕ ОБЕЗЩЕТЕНИЕ“, а не върху последното брутно. Изключване няма:
    // НЕВДПОВ чл. 1 ал. 8 т. 7 изброява чл. 200, 213, 214, 216, 220, 221, 222, 224, 225, 226, 232
    // и 331 — чл. 217 НЕ Е сред тях. Данък се дължи по същата причина: ЗДДФЛ чл. 24 ал. 2 т. 8 също
    // не го изброява. Д1 код 20.
    MATRIX.put("comp_kt_217", new Treatment("чл. 217 КТ", true, true, "20"));
  }

  /**
   * Редовете, които ТАЗИ промяна въвежда — трите липсващи алинеи на чл. 106 ЗДСл и чл. 217 КТ.
   *
   * <p>Отделен от {@link #MATRIX} нарочно: матрицата е нормата и важи за осемте, а този списък е
   * обхватът на промяната. Тестът за бележките мери него, за да не съди заварените редове по
   * изискване, което е въведено след тях.
   *
   * <p>⚠️ {@code comp_kt_222_3} НЕ Е в списъка, макар тази промяна да пипна и него. Той е ЗАВАРЕН
   * ред: промяната му е разширяване на тригерите и допълване на бележката, не въвеждане. Вкаран
   * тук, той щеше да мине зелено без да значи нищо — бележката му вече назовава и трите норми.
   */
  private static final List<String> INTRODUCED_HERE =
      java.util.Arrays.asList(
          "comp_zdsl_106_3", "comp_zdsl_106_3a", "comp_zdsl_106_4", "comp_kt_217");

  private static final class Treatment {
    final String legalBasis;
    final boolean social;
    final boolean taxable;
    final String d1Code;

    Treatment(String legalBasis, boolean social, boolean taxable, String d1Code) {
      this.legalBasis = legalBasis;
      this.social = social;
      this.taxable = taxable;
      this.d1Code = d1Code;
    }

    @Override
    public String toString() {
      return legalBasis
          + " · вноски "
          + (social ? "ДА" : "НЕ")
          + ", данък "
          + (taxable ? "ДА" : "НЕ");
    }
  }

  // ───────────────────────────── файловата половина ─────────────────────────────

  private static Map<String, Map<String, String>> read(String file) {
    List<String> header = BgSeed.quotedHeader(file);
    Map<String, Map<String, String>> out = new LinkedHashMap<>();
    for (String[] row : BgSeed.quotedRows(file)) {
      assertEquals(
          header.size(),
          row.length,
          "ред с различен брой колони от заглавния — ще влезе разместен: " + row[0]);
      Map<String, String> cells = new LinkedHashMap<>();
      for (int i = 0; i < header.size(); i++) {
        cells.put(header.get(i), row[i]);
      }
      out.put(cells.get("code"), cells);
    }
    return out;
  }

  @Test
  @DisplayName("⚠️ ГЕЙТ · осемте обезщетения носят точно своята двойка флагове")
  void everyCompensationCarriesItsOwnTreatment() {
    Map<String, Map<String, String>> catalogue = read(CATALOGUE);
    List<String> wrong = new ArrayList<>();

    for (Map.Entry<String, Treatment> e : MATRIX.entrySet()) {
      Map<String, String> row = catalogue.get(e.getKey());
      if (row == null) {
        wrong.add(e.getKey() + " — ЛИПСВА в " + CATALOGUE + " (" + e.getValue() + ")");
        continue;
      }
      Treatment want = e.getValue();
      if (!want.legalBasis.equals(row.get("legalBasis"))) {
        wrong.add(
            e.getKey()
                + " · основание „"
                + row.get("legalBasis")
                + "“, а матрицата стои на „"
                + want.legalBasis
                + "“");
      }
      if (want.social != Boolean.parseBoolean(row.get("socialContributionsDue"))) {
        wrong.add(
            e.getKey()
                + " · socialContributionsDue = "
                + row.get("socialContributionsDue")
                + ", а по "
                + want.legalBasis
                + " трябва да е "
                + want.social);
      }
      if (want.taxable != Boolean.parseBoolean(row.get("isTaxable"))) {
        wrong.add(
            e.getKey()
                + " · isTaxable = "
                + row.get("isTaxable")
                + ", а по "
                + want.legalBasis
                + " трябва да е "
                + want.taxable);
      }
      if (!want.d1Code.equals(row.get("d1Code"))) {
        wrong.add(
            e.getKey()
                + " · d1Code = „"
                + row.get("d1Code")
                + "“, а трябва да е „"
                + want.d1Code
                + "“ — код се носи само когато сумата е осигурителен доход");
      }
    }
    if (!wrong.isEmpty()) {
      fail(
          "третиране, разминато с нормата — "
              + wrong.size()
              + ":\n  "
              + String.join("\n  ", wrong));
    }
  }

  /**
   * ⚠️ ЧЛ. 106 ЗДСл Е ЧЕТИРИ РЕДА, А НЕ ЕДИН — и трите му режима са различни.
   *
   * <p>Този тест е отделен от горния нарочно. Горният пита дали всеки ред носи своята двойка; този
   * пита дали алинеите изобщо СЪЩЕСТВУВАТ като отделни редове. Каталог, който пази чл. 106 като
   * едно, минава горния тест с една вярна двойка и мълчи за останалите три режима.
   */
  @Test
  @DisplayName("⚠️ ГЕЙТ · чл. 106 ЗДСл се пази ПО АЛИНЕЯ — ал. 2, 3, 3а и 4 са отделни редове")
  void article106IsModelledPerParagraph() {
    Map<String, Map<String, String>> catalogue = read(CATALOGUE);
    List<String> missing = new ArrayList<>();
    for (String code :
        new String[] {
          "comp_zdsl_106_2", "comp_zdsl_106_3", "comp_zdsl_106_3a", "comp_zdsl_106_4"
        }) {
      if (!catalogue.containsKey(code)) {
        missing.add(code);
      }
    }
    if (!missing.isEmpty()) {
      fail(
          "чл. 106 ЗДСл без разграничител по алинея — липсват "
              + missing
              + ". Трите режима не са един вид: ал. 2 е с вноски и данък, ал. 3 и 3а без нито едно,"
              + " ал. 4 само с данък.");
    }
    // Едно основание на алинея: два реда със същия legalBasis значат дублиран режим.
    Map<String, String> seen = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, String>> e : catalogue.entrySet()) {
      String basis = e.getValue().get("legalBasis");
      if (basis != null && basis.contains("106") && basis.contains("ЗДСл")) {
        String previous = seen.put(basis, e.getKey());
        if (previous != null) {
          fail("две записи за „" + basis + "“: " + previous + " и " + e.getKey());
        }
      }
    }
    assertEquals(4, seen.size(), "алинеи на чл. 106 ЗДСл в каталога: " + seen);
  }

  /**
   * ⚠️ ЧЛ. 222 СЕ ПАЗИ ПО АЛИНЕЯ — но ал. 4 НЯМА СВОЙ РЕД, и това е самото твърдение.
   *
   * <p>Огледален на {@link #article106IsModelledPerParagraph()} и нарочно с обратен знак за
   * последната алинея. При чл. 106 ЗДСл всяка алинея е СВОЙ ред, защото режимите ѝ са различни. При
   * чл. 222 КТ ал. 1, 2 и 3 също са свои редове — но ал. 4 НЕ Е и не бива да става.
   *
   * <p><b>Защо отделен ред би бил дефект, а не стил.</b> Ал. 4 гласи „Алинея 3 се прилага и
   * когато…“, а ал. 3 завършва с „Обезщетение по тази алинея може да се изплаща само веднъж“. Тоест
   * платеното по ал. 4 Е платено по ал. 3 и изчерпва еднократността. {@code
   * BgTerminationCompensation} носи {@code unique-constraint (employmentContract,
   * bgCompensationType)}: уникалността е на ДВОЙКАТА, не на договора. Два реда в каталога значат
   * два допустими записа по един договор — тоест ал. 3 и ал. 4 биха се платили И ДВЕТЕ за едно
   * право, и то без нищо да светне. Един ред с повече тригери прави еднократността СТРУКТУРНА.
   *
   * <p><b>⚠️ И защо това НЕ е прецедентът на чл. 106 ал. 3а.</b> Там алинеите са РАЗЛИЧНИ права със
   * сходен режим и решението да следват ал. 3 е преценка. Тук е ЕДНО право с две основания, казано
   * от самия закон. Обратната посока на README.md („Правилото за разбивката в каталога на
   * обезщетенията“) сочи същото: чл. 221 ал. 3 разширява ал. 2 и затова {@code comp_kt_221_2} е
   * един ред с два тригера.
   */
  @Test
  @DisplayName("⚠️ ГЕЙТ · чл. 222 ал. 4 НЯМА свой ред — тя е тригер на ал. 3, не второ право")
  void articleTwoTwentyTwoParagraphFourHasNoRowOfItsOwn() {
    Map<String, Map<String, String>> catalogue = read(CATALOGUE);

    for (String code : new String[] {"comp_kt_222_1", "comp_kt_222_2", "comp_kt_222_3"}) {
      if (!catalogue.containsKey(code)) {
        fail("чл. 222 КТ без разграничител по алинея — липсва " + code);
      }
    }

    // ⚠️ САМОТО ТВЪРДЕНИЕ. Проверява се и по код, и по основание: ред с код comp_kt_222_4 и ред с
    // legalBasis „чл. 222 ал. 4 КТ“ под друг код са един и същи дефект.
    if (catalogue.containsKey("comp_kt_222_4")) {
      fail(
          "comp_kt_222_4 е добавен като отделен ред. Ал. 4 не е второ право, а второ ОСНОВАНИЕ на"
              + " ал. 3 („Алинея 3 се прилага и когато…“), а ал. 3 се плаща само веднъж. Два реда"
              + " при unique-constraint (employmentContract, bgCompensationType) значат ДВЕ"
              + " плащания за едно право. Мястото ѝ е в triggerGrounds на comp_kt_222_3.");
    }
    for (Map.Entry<String, Map<String, String>> e : catalogue.entrySet()) {
      String basis = e.getValue().get("legalBasis");
      if (basis != null && basis.replace(" ", "").contains("222ал.4")) {
        fail(
            "ред „"
                + e.getKey()
                + "“ носи основание „"
                + basis
                + "“ — ал. 4 се плаща ПО ал. 3 и не бива да е самостоятелно основание");
      }
    }

    // Едно основание на алинея, както при чл. 106: три реда, три различни legalBasis.
    Map<String, String> seen = new LinkedHashMap<>();
    for (Map.Entry<String, Map<String, String>> e : catalogue.entrySet()) {
      String basis = e.getValue().get("legalBasis");
      if (basis != null && basis.contains("222") && basis.contains("КТ")) {
        String previous = seen.put(basis, e.getKey());
        if (previous != null) {
          fail("две записи за „" + basis + "“: " + previous + " и " + e.getKey());
        }
      }
    }
    assertEquals(3, seen.size(), "алинеи на чл. 222 КТ в каталога: " + seen);
  }

  /**
   * ⚠️ И ОБРАТНОТО НА ГОРНОТО: ал. 4 трябва да е ДОСТИЖИМА, не само непродублирана.
   *
   * <p>Горният тест пази ал. 4 да няма свой ред. Сам той минава зелено и когато ал. 4 е просто
   * ЗАБРАВЕНА — например ако {@code comp_kt_222_3} се пали само от {@code 328_1_10}, „при
   * придобиване право на пенсия за осигурителен стаж и възраст“.
   *
   * <p><b>Такова стеснение прави ал. 4 недостижима.</b> Чл. 328 ал. 1 т. 10 иска ПРИДОБИТО право на
   * пенсия по чл. 68 КСО. Лицето по ал. 4 тъкмо НЯМА такова — то отговаря на условията за пенсия в
   * НАМАЛЕН размер по чл. 68а. Такова лице си тръгва по взаимно съгласие, с предизвестие или по
   * друго основание, тоест по всяко основание ОСВЕН 328_1_10.
   *
   * <p>И самата ал. 3 казва „НЕЗАВИСИМО ОТ ОСНОВАНИЕТО за прекратяването“. Затова редът носи {@code
   * appliesToAnyGround} и празен {@code triggerGrounds}: списъкът с основанията по КТ би растял с
   * всяка нова точка и би надхвърлил колона от 255 знака, а вносителят отхвърля такъв ред. Правото
   * на пенсия не се проверява и с флага — редът остава предложение, което ТРЗ преглежда;
   * поведението по основания е в {@code BgCompensationEtzGroundsTest}.
   */
  @Test
  @DisplayName(
      "⚠️ ГЕЙТ · чл. 222 ал. 3 се пали независимо от основанието, тоест и в случая по ал. 4")
  void paragraphFourIsReachableThroughTheTriggersOfParagraphThree() {
    Map<String, String> row = read(CATALOGUE).get("comp_kt_222_3");
    if (row == null) {
      fail("comp_kt_222_3 липсва — носителят на ал. 3 И ал. 4");
    }
    assertTrue(
        Boolean.parseBoolean(row.get("appliesToAnyGround")),
        "Чл. 222 ал. 3 се дължи „независимо от основанието за прекратяването“, а случаят по ал. 4"
            + " (чл. 68а КСО) НЕ може да мине през 328_1_10 — затова флагът, не списък.");
    assertEquals(
        "",
        row.get("triggerGrounds"),
        "При „всяко основание“ списък не се ползва — два източника на едно и също се разминават.");
  }

  /**
   * ⚠️ ВТОРОТО МЯСТО, КЪДЕТО СТОИ СЪЩАТА ДВОЙКА ФЛАГОВЕ.
   *
   * <p>Редът на фиша носи собствени {@code bgIsTaxable} и {@code bgIsInsuranceIncome}, преписани от
   * каталога. Преписаното може да се разсинхронизира — а разминаването се вижда чак на фиша, върху
   * пари. {@code BgPaySlipLineTypeCatalogueTest} пази същото срещу БАЗАТА; тук се пази срещу ФАЙЛА,
   * тоест грешката пада преди App Install, а не след него.
   */
  @Test
  @DisplayName("⚠️ ГЕЙТ · редът на фиша носи същото третиране като каталога")
  void thePaySlipLineMirrorsTheCatalogue() {
    Map<String, Map<String, String>> catalogue = read(CATALOGUE);
    Map<String, Map<String, String>> lines = read(PAY_SLIP_LINES);

    Map<String, Map<String, String>> byType = new LinkedHashMap<>();
    for (Map<String, String> line : lines.values()) {
      byType.put(line.get("bgCompensationType"), line);
    }

    List<String> wrong = new ArrayList<>();
    for (Map.Entry<String, Treatment> e : MATRIX.entrySet()) {
      Map<String, String> line = byType.get(e.getKey());
      if (line == null) {
        wrong.add(e.getKey() + " — няма ред на фиша, няма къде да се отпечата");
        continue;
      }
      Treatment want = e.getValue();
      if (want.taxable != Boolean.parseBoolean(line.get("bgIsTaxable"))) {
        wrong.add(
            line.get("code")
                + " · bgIsTaxable = "
                + line.get("bgIsTaxable")
                + ", а каталогът казва "
                + want.taxable);
      }
      if (want.social != Boolean.parseBoolean(line.get("bgIsInsuranceIncome"))) {
        wrong.add(
            line.get("code")
                + " · bgIsInsuranceIncome = "
                + line.get("bgIsInsuranceIncome")
                + ", а каталогът казва "
                + want.social);
      }
      Map<String, String> type = catalogue.get(e.getKey());
      if (type != null && !type.get("name").equals(line.get("name"))) {
        wrong.add(
            line.get("code")
                + " · име „"
                + line.get("name")
                + "“ ≠ „"
                + type.get("name")
                + "“ — BgPaySlipLineTypeCatalogueTest пада на същото срещу базата");
      }
    }
    if (!wrong.isEmpty()) {
      fail(
          "фишът се е разминал с каталога — "
              + wrong.size()
              + ":\n  "
              + String.join("\n  ", wrong));
    }
  }

  /**
   * ⚠️ ВСЕКИ НОВ РЕД НАЗОВАВА НОРМАТА, КОЯТО РЕШАВА ВЪПРОСА.
   *
   * <p>Матрицата стъпва на три норми — НЕВДПОВ чл. 1 ал. 8 (изключване от осигурителния доход), КСО
   * чл. 9 ал. 3 т. 5 (дължимост при оставане без работа) и ЗДДФЛ чл. 24 ал. 2 т. 8 (освобождаване
   * от данък). Флаг без основание в бележката е число, което никой не може да провери — следващият
   * читател или го приема на доверие, или го „поправя“ по усет.
   *
   * <p><b>⚠️ МЕРИ САМО РЕДОВЕТЕ, КОИТО ТАЗИ ПРОМЯНА ВЪВЕЖДА, и това е нарочно свиване.</b> Двата
   * заварени реда за „оставане без работа“ — {@code comp_kt_222_1} и {@code comp_zdsl_106_2} —
   * носят {@code socialContributionsDue = true}, но бележките им НЕ назовават КСО чл. 9 ал. 3 т. 5:
   * първата се позовава на потвърждение, втората сочи към ставките. Тоест най-съществената стойност
   * в матрицата стои на потвърждение, а не на норма. ЗАПИСАНО Е КАТО НАМЕРЕНО, НЕ Е ПОПРАВЕНО:
   * обхватът на тази промяна са флаговете, а флаговете и на двата реда са верни. Разширяването на
   * теста върху тях е отделно решение и иска отделен преглед.
   */
  @Test
  @DisplayName("⚠️ ГЕЙТ · бележката на всеки НОВ ред назовава решаващата норма")
  void theNotesCarryTheRightLegalBasis() {
    Map<String, Map<String, String>> catalogue = read(CATALOGUE);
    List<String> wrong = new ArrayList<>();
    for (String code : INTRODUCED_HERE) {
      Map<String, String> row = catalogue.get(code);
      if (row == null) {
        continue;
      }
      String note = row.get("note") == null ? "" : row.get("note");
      boolean mentionsRight =
          note.contains("чл. 1 ал. 8")
              || note.contains("9 ал. 3 т. 5")
              || note.contains("24 ал. 2 т. 8");
      if (!mentionsRight) {
        wrong.add(
            code
                + " · бележката не назовава нито една от трите решаващи норми — НЕВДПОВ чл. 1 ал."
                + " 8, КСО чл. 9 ал. 3 т. 5, ЗДДФЛ чл. 24 ал. 2 т. 8");
      }
    }
    if (!wrong.isEmpty()) {
      fail("основания в бележките — " + wrong.size() + ":\n  " + String.join("\n  ", wrong));
    }
  }

  // ───────────────────────────── базовата половина ─────────────────────────────

  /**
   * ⚠️ ПАЗАЧ НА ПРИСЪСТВИЕ, НЕ НА БРОЙ. Проверява третирането на онези от осемте, които базата ВЕЧЕ
   * носи, и изброява останалите, без да пада заради тях. Броенето би светвало червено на всяка
   * заварена инсталация — а клонът нарочно не пуска App Install, защото общата база се чете и от
   * други работни копия. Присъствието различава дефект от стара база; броят не ги различава.
   *
   * <p>⚠️ Булевите се четат с {@code getBoolean}, не с {@code getString}: {@code getString} върху
   * {@code boolean} колона в Postgres дава „t“/„f“, а сравнено с „true“ това е тихо НЕ винаги.
   */
  @Test
  @DisplayName("БАЗА · заредените обезщетения носят третирането от матрицата")
  void theInstalledRowsCarryTheMatrix() throws Exception {
    try (Connection c = open()) {
      if (c == null || !ready(c)) {
        return;
      }
      List<String> wrong = new ArrayList<>();
      List<String> notYet = new ArrayList<>();

      for (Map.Entry<String, Treatment> e : MATRIX.entrySet()) {
        try (PreparedStatement ps =
            c.prepareStatement(
                "SELECT legal_basis, social_contributions_due, is_taxable FROM "
                    + TABLE
                    + " WHERE code = ?")) {
          ps.setString(1, e.getKey());
          try (ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
              notYet.add(e.getKey());
              continue;
            }
            Treatment want = e.getValue();
            String basis = rs.getString(1);
            boolean social = rs.getBoolean(2);
            boolean socialNull = rs.wasNull();
            boolean taxable = rs.getBoolean(3);
            boolean taxableNull = rs.wasNull();

            if (socialNull) {
              wrong.add(e.getKey() + " · social_contributions_due е NULL — никой не е казал");
            } else if (social != want.social) {
              wrong.add(
                  e.getKey()
                      + " ("
                      + basis
                      + ") · вноски в базата "
                      + social
                      + ", а по нормата "
                      + want.social);
            }
            if (taxableNull) {
              wrong.add(e.getKey() + " · is_taxable е NULL — никой не е казал");
            } else if (taxable != want.taxable) {
              wrong.add(
                  e.getKey()
                      + " ("
                      + basis
                      + ") · данък в базата "
                      + taxable
                      + ", а по нормата "
                      + want.taxable);
            }
          }
        }
      }

      if (!notYet.isEmpty()) {
        System.out.println(
            "ℹ още не са в базата (App Install не е пускан след този комит): "
                + String.join(", ", notYet));
      }
      assertFalse(
          wrong.size() > 0,
          "заредено третиране, разминато с нормата — "
              + wrong.size()
              + ":\n  "
              + String.join("\n  ", wrong));
      assertFalse(
          notYet.size() == MATRIX.size(),
          "нито едно от седемте обезщетения не е в базата, при непразна таблица — това не е стара"
              + " инсталация, а разминат каталог");
    }
  }

  private static boolean ready(Connection c) throws Exception {
    if (!tableExists(c)) {
      BgSkip.because(
          "таблицата "
              + TABLE
              + " още я няма — модулът не е минавал през ddl=update на тази база. Състояние на"
              + " средата, не на кода. Файловата половина на този клас тече и без база.");
      return false;
    }
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery("SELECT count(*) FROM " + TABLE)) {
      rs.next();
      if (rs.getInt(1) == 0) {
        BgSkip.because(
            "таблицата "
                + TABLE
                + " е празна — App Install не е пускан. ⚠️ ТОВА Е НОРМАЛНОТО състояние на клона:"
                + " той нарочно не пуска App Install, защото общата база се чете и от други работни"
                + " копия.");
        return false;
      }
    }
    return true;
  }

  private static boolean tableExists(Connection c) throws Exception {
    try (PreparedStatement ps =
        c.prepareStatement(
            "SELECT count(*) FROM information_schema.tables WHERE lower(table_name) = ?")) {
      ps.setString(1, TABLE);
      try (ResultSet rs = ps.executeQuery()) {
        rs.next();
        return rs.getInt(1) > 0;
      }
    }
  }

  private static Connection open() {
    String url = BgTestDb.url();
    try {
      Class.forName("org.postgresql.Driver");
      return DriverManager.getConnection(url, BgTestDb.user(), BgTestDb.password());
    } catch (Exception e) {
      BgSkip.because(
          "няма достъпна база ("
              + url
              + "): "
              + e.getMessage()
              + " — доставката на матрицата НЕ е проверена. Файловата половина тече и без база.");
      return null;
    }
  }
}
