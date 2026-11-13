package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЗДРАВНАТА ВНОСКА КАТО ИНВАРИАНТ, а не като факт за днес.
 *
 * <p><b>Защо съществува.</b> Липса на HEALTH редове лесно се записва като нарочна — за вид осигурен
 * 92 (морски лица) с довода „чл. 4а ал. 1 изброява фондовете на ДОО и не урежда здравното
 * осигуряване“. Доводът е верен, изводът — не: тъкмо защото КСО не урежда здравното, то се урежда
 * от ЗЗО, а ЗЗО чл. 40 ал. 1 т. 2а хваща морските лица ИЗРИЧНО. Записаната липса се чете като решен
 * въпрос и никой не се връща към нея. Този тест е връщането — постоянно и автоматично.
 *
 * <p><b>Защо няма списък с изключения.</b> Условието е „носи ли кодът {@code STANDARD} редове“, а
 * не „в списъка ли е“. Кодове 18 и 27 нямат HEALTH ред и това е ВЯРНО: техните редове са само
 * обезщетения ({@code comp_kt_*}, {@code comp_zdsl_*}, {@code comp_zvo_*}), а обезщетението по КТ
 * не е осигурителен доход за ЗОВ. Те падат извън проверката по СОБСТВЕНИТЕ СИ данни, не по ръчно
 * поддържан whitelist — списък, който трябва да се помни, остарява точно както остаря записът за
 * 92.
 *
 * <p><b>⚠️ И ВТОРИЯТ ИЗХОД ОТ ПРОВЕРКАТА — СРЕЗЪТ ПО ЧЛ. 9 АЛ. 5 — Е ПАК ОТ ДАННИТЕ, НЕ ОТ
 * СПИСЪК.</b> Вид осигурен 19 носи {@code STANDARD} редове (за него няма и не бива да има измислен
 * вид обезщетение — чл. 9 ал. 3 т. 3 не е обезщетение, а ЗАЧЕТЕН ПЕРИОД), а HEALTH ред няма. Това
 * не е пропуснат фонд: КСО чл. 9 ал. 5 изброява ИЗЧЕРПАТЕЛНО дължимото по цялата ал. 3 — фонд
 * „Пенсии“, съответно фондът по чл. 69, и ДЗПО/УчПФ. Не ОЗМ, не безработица, не ТЗПБ. Тоест такъв
 * код се ПОЗНАВА по набора си фондове и не иска име в списък: наборът {@value #ARTICLE_9_5_SET} е
 * самата норма, преписана в данните.
 *
 * <p><b>⚠️ ЗАЩО ИМЕННО ТОЧНО РАВЕНСТВО НА НАБОРА.</b> Изключение „кодът е 19“ би минало и когато
 * утре някой махне HEALTH реда на 19, но остави ОЗМ — тоест би пазило ИМЕТО, а не нормата. Точното
 * равенство пази нормата: код, който изпусне ЗОВ, но задържи ОЗМ, безработица или ТЗПБ, НЕ е период
 * по чл. 9 ал. 3 и пада тук, както преди. Обратното също държи: код 20 е СЪЩАТА алинея (т. 4), но
 * при него правоотношението ПРОДЪЛЖАВА, ЗЗО чл. 40 ал. 1 т. 1 стъпва върху същия доход и HEALTH ред
 * ИМА — затова 20 минава по общото правило, а не по този изход.
 *
 * <p><b>Защо и по КАТЕГОРИЯ.</b> {@code BgInsuranceCalculationService} филтрира {@code
 * self.laborCategory = :cat} с ТОЧНО РАВЕНСТВО — за разлика от {@code bgUpfRegime}, {@code
 * bgSolScope} и {@code bgTeacherScope}, които носят позволителния заместител {@code ANY}. Тоест
 * категорията НЕ пада обратно към трета: фонд, заведен за категория 3 и пропуснат за 1, изчезва
 * безшумно за работещия при първа категория. Затова HEALTH ред се иска за ВСЯКА категория, която
 * кодът носи — при вид осигурен 92 това са три реда, не един.
 */
class BgHealthContributionInvariantTest {

  private static final String CONTRIBUTIONS = "bg_insurance_contribution.csv";
  private static final String HEALTH = "HEALTH";
  private static final String STANDARD = "STANDARD";

  /** Видът на ставка, чиято стойност НЕ е установена — виж {@code BgContributionResolver}. */
  private static final String MISSING = "MISSING";

  /**
   * Наборът фондове по КСО чл. 9 ал. 5 — единственият изход от проверката освен „носи HEALTH ред“.
   *
   * <p>ДЗПО стои тук като {@code UPF}, защото това е кодът му в {@code bg_contribution_fund}.
   * Учителският фонд НЕ е в набора и не бива да се добавя „за пълнота“: чл. 9 ал. 5 го дължи по ал.
   * 3, но при т. 5 изрично го изважда, а нито един от кодовете, които този изход хваща, не е
   * учителски. Появи ли се такъв, той трябва да се обади тук, а не да се промъкне.
   */
  static final String ARTICLE_9_5_SET = "[PENSION, UPF]";

  /**
   * ⚠️ КОДОВЕТЕ, ЧИЕТО ЗДРАВНО ИЗОБЩО НЕ МИНАВА ПРЕЗ ТОЗИ ПОТОК — ЗЗО чл. 40 ал. 5.
   *
   * <p><b>Защо въпреки „не добавяй изключение“ горе.</b> Онова предупреждение е срещу изключение по
   * ИМЕ и по догадка. Тук основанието е норма и е потвърдено: лицето НЕ подлежи на осигуряване по
   * чл. 40 ал. 1, 2 и 3 ЗЗО, тоест попада в ал. 5 и се здравоосигурява САМО, с декларация обр. 7.
   * Вноската му не е нито наша, нито на подателя на Д1 — тя изобщо не е в тази декларация.
   *
   * <p><b>⚠️ И ТОВА НЕ Е ПРЕПИСВАНЕ НА ЧЛ. 9 АЛ. 3 ИЗХОДА.</b> Кодове 86, 87 и 91 носят ТОЧНО
   * набора {@value #ARTICLE_9_5_SET} и биха минали през него МЪЛЧАЛИВО — но по ЧУЖДА норма: чл. 9
   * ал. 3 КСО е за периоди без правоотношение, а тези хора имат правоотношение и просто плащат
   * здравното си сами. Зелено по случайност е по-лошо от червено: затова списъкът се пита ПЪРВИ и
   * записва ЗАЩО, вместо да остави съвпадението да свърши работата.
   *
   * <p>Източник за всеки: НАП Приложение № 1 (ВК-30-10237/12.08.2026) изписва НЗОК само в колоната
   * ОБХВАТ, а колоните със СТАВКИ нямат ЗО — за разлика от разд. 10, 15 и 18.
   */
  private static final Map<String, String> SELF_PAID_HEALTH_ART40_5 = new LinkedHashMap<>();

  static {
    SELF_PAID_HEALTH_ART40_5.put(
        "86",
        "лица в международни органи и организации (НАП разд. 14) — обхватът изписва НЗОК, ставка за"
            + " ЗО НЯМА. Здравното е по ЗЗО чл. 40 ал. 5, декларация обр. 7");
    SELF_PAID_HEALTH_ART40_5.put(
        "87",
        "изпратени на работа в чужбина от български посредник (НАП разд. 13) — ЗЗО чл. 40 ал. 5,"
            + " обр. 7. ⚠️ И при престой над 183 дни в чужбина чл. 40а дава ВЪЗМОЖНОСТ ДА НЕ СЕ"
            + " заплащат вноски след заявление до НАП — тоест здравен ред тук би бил не просто"
            + " несверен, а възможно противоположен на дължимото");
    SELF_PAID_HEALTH_ART40_5.put(
        "91",
        "съпрузи на дългосрочно командировани в дипломатическа служба (НАП разд. 13, същата секция"
            + " като 87) — ЗЗО чл. 40 ал. 5, обр. 7");
    SELF_PAID_HEALTH_ART40_5.put(
        "96",
        "съпрузи по чл. 4 ал. 9 КСО (НАП разд. 15) — тук обхватът дори НЕ споменава НЗОК: пише само"
            + " Фондове на ДОО — Пенсии и ОЗМ. ЗЗО чл. 40 ал. 1 не ги изброява в нито една от осемте"
            + " си точки, тоест попадат в ал. 5 и плащат сами с обр. 7");
  }

  /** Наборът горе, като множество — държи се синхронен от {@link #theExitIsTheNormNotAName}. */
  private static Set<String> article95Funds() {
    Set<String> out = new TreeSet<>();
    out.add("PENSION");
    out.add("UPF");
    return out;
  }

  /** Фондовете, които всеки вид осигурен носи на {@code STANDARD} редовете си. */
  private static Map<String, Set<String>> fundsByCode() {
    List<String> h = BgSeed.header(CONTRIBUTIONS);
    Map<String, Set<String>> out = new TreeMap<>();
    for (String[] r : BgSeed.rows(CONTRIBUTIONS)) {
      if (!STANDARD.equals(BgSeed.col(h, r, "bgCompensationTypeCode"))) {
        continue;
      }
      out.computeIfAbsent(BgSeed.col(h, r, "bgInsuredTypeCode"), k -> new TreeSet<>())
          .add(BgSeed.col(h, r, "fundCode"));
    }
    return out;
  }

  /** Категориите с {@code STANDARD} редове по вид осигурен, и отделно тези с HEALTH ред. */
  private static Map<String, Set<String>> categories(boolean healthOnly) {
    List<String> h = BgSeed.header(CONTRIBUTIONS);
    Map<String, Set<String>> out = new TreeMap<>();

    for (String[] r : BgSeed.rows(CONTRIBUTIONS)) {
      if (!STANDARD.equals(BgSeed.col(h, r, "bgCompensationTypeCode"))) {
        continue;
      }
      if (healthOnly && !HEALTH.equals(BgSeed.col(h, r, "fundCode"))) {
        continue;
      }
      out.computeIfAbsent(BgSeed.col(h, r, "bgInsuredTypeCode"), k -> new TreeSet<>())
          .add(BgSeed.col(h, r, "laborCategory"));
    }
    return out;
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: всеки вид осигурен със STANDARD редове носи и HEALTH ред")
  void everyStandardInsuredTypeCarriesAHealthRow() {
    Map<String, Set<String>> standard = categories(false);
    Map<String, Set<String>> health = categories(true);

    if (standard.isEmpty()) {
      fail(
          "нито един STANDARD ред в "
              + CONTRIBUTIONS
              + " — или колоната bgCompensationTypeCode се е сменила, или файлът е празен. Тест,"
              + " който няма какво да провери, минава от празнота, а не от вярност");
    }

    Map<String, Set<String>> funds = fundsByCode();
    List<String> missing = new ArrayList<>();
    for (String code : standard.keySet()) {
      if (health.containsKey(code)) {
        continue;
      }
      // ⚠️ ПЪРВО НАЗОВАНОТО ОСНОВАНИЕ, чак после изходът по набор. Редът НЕ е свободен: 86, 87 и 91
      // носят точно {PENSION, UPF} и биха минали през чл. 9 ал. 3 изхода МЪЛЧАЛИВО — по чужда
      // норма. Питан пръв, списъкът записва ЗАЩО минават.
      if (SELF_PAID_HEALTH_ART40_5.containsKey(code)) {
        continue;
      }
      // ⚠️ ВТОРИЯТ ИЗХОД, И ТОЙ Е ОТ ДАННИТЕ. Точно набор {PENSION, UPF} значи период по КСО чл. 9
      // ал. 3: чл. 9 ал. 5 изчерпва дължимото до пенсионните фондове и ЗОВ ред просто няма откъде
      // да дойде. Виж javadoc-а на класа — равенството е ТОЧНО нарочно.
      if (article95Funds().equals(funds.get(code))) {
        continue;
      }
      missing.add(
          "\n  вид осигурен "
              + code
              + ": носи STANDARD редове за категории "
              + standard.get(code)
              + ", а HEALTH ред НЯМА нито един (фондовете му са "
              + funds.get(code)
              + ")");
    }

    if (!missing.isEmpty()) {
      fail(
          "ВИДОВЕ ОСИГУРЕНИ БЕЗ ЗДРАВНА ВНОСКА:"
              + String.join("", missing)
              + "\n\n  ⚠️ ЗОВ не е по КСО и точно това го прави лесен за пропускане: който сверява"
              + " фондовете по чл. 4 или чл. 4а, няма да го намери там. Основанието е в ЗЗО —"
              + " чл. 40 ал. 1 т. 1 за наетите, т. 2а за морските лица по чл. 4а КСО."
              + "\n  ⚠️ Ако кодът НАИСТИНА не дължи ЗОВ, редовете му не бива да са STANDARD:"
              + " обезщетенията носят собствен bgCompensationTypeCode и падат извън тази проверка"
              + " сами (както 18 и 27). Не добавяй изключение — поправи основанието."
              + "\n  ⚠️ Единственото друго, което минава, е период по КСО чл. 9 ал. 3: там чл. 9"
              + " ал. 5 изчерпва дължимото до "
              + ARTICLE_9_5_SET
              + " и наборът трябва да е ТОЧНО този. Код с ОЗМ, безработица или ТЗПБ до пенсионните"
              + " фондове НЕ е такъв период и ЗОВ му се дължи.");
    }
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: HEALTH редът стои при ВСЯКА категория, която кодът носи")
  void theHealthRowCoversEveryLaborCategoryTheCodeCarries() {
    Map<String, Set<String>> standard = categories(false);
    Map<String, Set<String>> health = categories(true);

    Map<String, Set<String>> holes = new LinkedHashMap<>();
    for (Map.Entry<String, Set<String>> e : standard.entrySet()) {
      Set<String> covered = health.get(e.getKey());
      if (covered == null) {
        continue; // казва го другият тест — тук не се дублира
      }
      Set<String> gap = new TreeSet<>(e.getValue());
      gap.removeAll(covered);
      if (!gap.isEmpty()) {
        holes.put(e.getKey(), gap);
      }
    }

    if (!holes.isEmpty()) {
      List<String> lines = new ArrayList<>();
      for (Map.Entry<String, Set<String>> e : holes.entrySet()) {
        lines.add(
            "\n  вид осигурен "
                + e.getKey()
                + ": HEALTH ред липсва за категории "
                + e.getValue()
                + " (носи го за "
                + health.get(e.getKey())
                + ")");
      }
      fail(
          "HEALTH РЕД, ПОКРИВАЩ САМО ЧАСТ ОТ КАТЕГОРИИТЕ:"
              + String.join("", lines)
              + "\n\n  ⚠️ BgInsuranceCalculationService филтрира laborCategory с ТОЧНО РАВЕНСТВО —"
              + " няма заместител ANY и няма падане назад към категория 3, каквито имат bgUpfRegime,"
              + " bgSolScope и bgTeacherScope. Тоест непокритата категория не взима чуждия ред: за"
              + " нея здравна вноска просто НЯМА, тихо."
              + "\n  ⚠️ Ставката по ЗЗО не зависи от категорията труд — категорията е ос на"
              + " ПЕНСИОННИТЕ фондове. Затова редът се повтаря дословно за всяка категория, а не се"
              + " „оптимизира“ до един.");
    }
  }

  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ вторият изход е НОРМАТА, а не име: наборът по чл. 9 ал. 5 и текстът му съвпадат")
  void theExitIsTheNormNotAName() {
    // 1 · Низът в съобщенията и множеството в проверката са ЕДНО И СЪЩО. Разминат ли се, червеното
    // ще сочи читателя към набор, различен от този, по който тестът е решил — най-скъпият вид
    // подвеждащо съобщение.
    if (!ARTICLE_9_5_SET.equals(article95Funds().toString())) {
      fail(
          "ARTICLE_9_5_SET казва "
              + ARTICLE_9_5_SET
              + ", а проверката ползва "
              + article95Funds()
              + " — съобщението и правилото са се разминали");
    }

    // 2 · ⚠️ КОТВА: изходът трябва да ХВАЩА нещо. Изход, който не важи за нито един код, е мъртъв
    // код, който изглежда като правило — и следващият, който добави период по чл. 9 ал. 3, няма да
    // разбере, че той е бил предвиден.
    Map<String, Set<String>> funds = fundsByCode();
    Set<String> throughTheExit = new TreeSet<>();
    Set<String> collide = new TreeSet<>();
    for (Map.Entry<String, Set<String>> e : funds.entrySet()) {
      if (!article95Funds().equals(e.getValue())) {
        continue;
      }
      // ⚠️ РЕДЪТ Е СЪЩИЯТ КАТО В САМИЯ ИНВАРИАНТ, И ТОВА НЕ Е ПОДРЕЖДАНЕ. Кодове 86, 87 и 91 носят
      // ТОЧНО набора по чл. 9 ал. 5 и биха минали през този изход, без някой да е писал ред за тях
      // — но по ЧУЖДА норма: чл. 9 ал. 3 КСО е за периоди БЕЗ правоотношение, а те имат такова и
      // просто плащат здравното си сами по ЗЗО чл. 40 ал. 5. Питан пръв, назованото основание ги
      // изважда оттук и изходът остава онова, което твърди, че е.
      if (SELF_PAID_HEALTH_ART40_5.containsKey(e.getKey())) {
        collide.add(e.getKey());
        continue;
      }
      throughTheExit.add(e.getKey());
    }

    // 2а · ⚠️ И СБЛЪСЪКЪТ СЕ ТВЪРДИ, А НЕ СЕ ПРЕМЪЛЧАВА. Филтър, който само изважда кодове, утре би
    // изхвърлил и код, попаднал в списъка по погрешка — тихо. Записан като очакване, той пада в
    // деня, в който някой от тримата получи трети фонд или изчезне от списъка с основания.
    Set<String> expectedCollision = new TreeSet<>(Arrays.asList("86", "87", "91"));
    if (!expectedCollision.equals(collide)) {
      fail(
          "с набора по чл. 9 ал. 5 се сблъскват "
              + collide
              + ", а се очакваше "
              + expectedCollision
              + ".\n  ⚠️ Тези кодове минават през ПЪРВИЯ изход (ЗЗО чл. 40 ал. 5, обр. 7), не през"
              + " този. Промяна тук значи, че някой е сменил набор от фондове или е пипнал"
              + " SELF_PAID_HEALTH_ART40_5 — и в двата случая проверката иска препрочитане, а не"
              + " дописване на очакването.");
    }
    if (throughTheExit.isEmpty()) {
      fail(
          "нито един вид осигурен не минава през изхода по чл. 9 ал. 5 — или наборът "
              + ARTICLE_9_5_SET
              + " вече не описва нито един период по чл. 9 ал. 3, или кодовете са махнати. Изход"
              + " без обхват е мъртво правило: махни го или го поправи, но не го оставяй да"
              + " изглежда като пазач.");
    }

    // 3 · И обратното: изходът е ТЕСЕН. Днес през него минава точно код 19 — периодът по чл. 9
    // ал. 3 т. 3, при който лицето е УВОЛНЕНО и здравното му е по ЗЗО чл. 40 ал. 3 т. 6, за сметка
    // на държавния бюджет и извън ведомостта. Код 20 е СЪЩАТА алинея (т. 4), но правоотношението
    // продължава, ЗЗО чл. 40 ал. 1 т. 1 важи и той носи ЗОВ ред — тоест НЕ минава оттук.
    Set<String> expected = new TreeSet<>();
    expected.add("19");
    if (!expected.equals(throughTheExit)) {
      fail(
          "през изхода по чл. 9 ал. 5 минават "
              + throughTheExit
              + ", а се очакваше "
              + expected
              + ".\n  ⚠️ Нов код тук значи, че някой е завел период по чл. 9 ал. 3 — тогава редът"
              + " му е верен и това твърдение се допълва С ПРИЧИНА. Изчезнал код значи обратното:"
              + " или е получил ЗОВ ред, или е загубил пенсионен — второто е дефект.");
    }
  }

  @Test
  @DisplayName("ФАЙЛ · ⚠️ ИНВАРИАНТ: двете части на ЗОВ дават ЕДИН И СЪЩ сбор при всеки транш")
  void theTwoSharesAlwaysSumToTheWholeContribution() {
    List<String> h = BgSeed.header(CONTRIBUTIONS);
    Map<String, List<String>> byTotal = new TreeMap<>();
    int seen = 0;

    for (String[] r : BgSeed.rows(CONTRIBUTIONS)) {
      if (!STANDARD.equals(BgSeed.col(h, r, "bgCompensationTypeCode"))
          || !HEALTH.equals(BgSeed.col(h, r, "fundCode"))) {
        continue;
      }
      // ⚠️ MISSING значи „стойността не е установена“ и е РАЗЛИЧНО от нула — такъв ред няма сбор и
      // не бива да го дава. Същото правило като в BgContributionResolver.refuseOnKind.
      if (MISSING.equals(BgSeed.col(h, r, "bgEmployerRateKind"))
          || MISSING.equals(BgSeed.col(h, r, "bgEmployeeRateKind"))) {
        continue;
      }
      seen++;
      BigDecimal total =
          new BigDecimal(BgSeed.col(h, r, "employerRate"))
              .add(new BigDecimal(BgSeed.col(h, r, "employeeRate")));
      byTotal
          .computeIfAbsent(total.stripTrailingZeros().toPlainString(), k -> new ArrayList<>())
          .add(
              BgSeed.col(h, r, "importId")
                  + " (вид "
                  + BgSeed.col(h, r, "bgInsuredTypeCode")
                  + ", кат. "
                  + BgSeed.col(h, r, "laborCategory")
                  + ", от "
                  + BgSeed.col(h, r, "validFrom")
                  + ": "
                  + BgSeed.col(h, r, "employerRate")
                  + " + "
                  + BgSeed.col(h, r, "employeeRate")
                  + ")");
    }

    if (seen == 0) {
      fail(
          "нито един HEALTH ред с установени ставки в "
              + CONTRIBUTIONS
              + " — тест, който няма какво да провери, минава от празнота, а не от вярност");
    }

    if (byTotal.size() != 1) {
      List<String> lines = new ArrayList<>();
      for (Map.Entry<String, List<String>> e : byTotal.entrySet()) {
        lines.add(
            "\n  сбор "
                + e.getKey()
                + " на сто · "
                + e.getValue().size()
                + " реда: "
                + e.getValue());
      }
      fail(
          "ЗОВ СЕ СЪБИРА ДО РАЗЛИЧНИ СБОРОВЕ:"
              + String.join("", lines)
              + "\n\n  ⚠️ ЧАСТИТЕ се менят по транш и по вид осигурен (4,8 + 3,2 за вид 01, а 6,4 +"
              + " 1,6 за видове 05 и 24 от 01.08.2026 г.), но СБОРЪТ е размерът на"
              + " здравноосигурителната вноска по ЗЗО чл. 29 ал. 3 и е един за всички."
              + "\n  ⚠️ ЗАЩО ТОВА СЕ БРОИ ТУК. Върху този сбор стъпва здравната вноска при НЕПЛАТЕН"
              + " ОТПУСК (ЗЗО чл. 40 ал. 1 т. 1 б. „б“): там вноската е ИЗЦЯЛО за сметка на един"
              + " платец, тоест иска ЦЯЛАТА ставка, а не частта му — виж"
              + " BgUnpaidLeaveHealthResolver.RATE_IS_THE_WHOLE_CONTRIBUTION и"
              + " BgInsuranceCalculationService.findHealthTotalRate. Разноредни сборове значат, че"
              + " един и същ ръб на закона се смята различно според вида осигурен."
              + "\n  ⚠️ Ако сборът НАИСТИНА се смени със закон, това твърдение се допълва С ПРИЧИНА"
              + " и с транш по дата — не се трие.");
    }
  }

  /**
   * ⚠️ ОБРАТНАТА ПОСОКА: всеки запис в списъка НАИСТИНА е без ЗОВ ред.
   *
   * <p>Образецът е {@code BgInertSeamTest}: изключение за код, който вече носи здравна вноска, е
   * точно толкова тихо, колкото липсваща проверка — то би прикрило деня, в който нормата се смени и
   * ЗОВ ред се появи. Тогава записът трябва да СЕ МАХНЕ, а не да продължи да пази нищо.
   */
  @Test
  @DisplayName(
      "ФАЙЛ · ⚠️ Всеки код по ЗЗО чл. 40 ал. 5 наистина НЯМА здравен ред — обратната посока")
  void everySelfPaidHealthEntryIsStillWithoutAHealthRow() {
    Map<String, Set<String>> funds = fundsByCode();
    List<String> stale = new ArrayList<>();
    for (Map.Entry<String, String> e : SELF_PAID_HEALTH_ART40_5.entrySet()) {
      Set<String> own = funds.get(e.getKey());
      if (own == null) {
        stale.add(e.getKey() + " · няма НИТО ЕДИН STANDARD ред — записът е за несъществуващ код");
      } else if (own.contains(HEALTH)) {
        stale.add(e.getKey() + " · ВЕЧЕ носи HEALTH ред, тоест изключението го прикрива");
      }
      if (e.getValue() == null || e.getValue().trim().isEmpty()) {
        stale.add(e.getKey() + " · записът е БЕЗ причина");
      }
    }
    if (!stale.isEmpty()) {
      fail(
          "ОСТАРЯЛ ЗАПИС В SELF_PAID_HEALTH_ART40_5:\n    "
              + String.join("\n    ", stale)
              + "\n\n  ⚠️ Изключение, което вече нищо не пази, е тихо точно както липсващата"
              + " проверка. Махни записа заедно с появата на здравния ред.");
    }
  }
}
