package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * КЛАСЪТ ПРОСЛУЖЕНО ВРЕМЕ СЕ ДЪЛЖИ САМО ПО ТРУДОВО ПРАВООТНОШЕНИЕ — НСОРЗ чл. 12 ал. 1.
 *
 * <p><b>Защо числата не стигат.</b> Ако {@code BgEarningsResolver#seniorityLine} пита само за числа
 * — основна заплата, норма, отработени дни, признат стаж и процент — и НИКЪДЕ не пита по какво
 * правоотношение работи лицето, управителят по договор за управление и контрол (вид осигурен 10)
 * получава клас точно както наетият: при 2000,00 основна и 10 години стаж — 120,00 при 0,6 на сто
 * за година. Нормата обаче го дава „на РАБОТНИЦИТЕ И СЛУЖИТЕЛИТЕ“, в процент върху основната
 * заплата, „определена с ИНДИВИДУАЛНИЯ ТРУДОВ ДОГОВОР“ (чл. 12 ал. 1), а чл. 2 ал. 2 стеснява
 * цялата наредба до работещите ПО ТРУДОВО ПРАВООТНОШЕНИЕ.
 *
 * <p><b>⚠️ И ЗАЩО ОСТА Е НОВА, А НЕ ПРЕИЗПОЛЗВАНА.</b> Д1 вече има сигнал „трудово правоотношение
 * ли е“ — т. 29/т. 30 го четат от речника с вноски: кой вид осигурен носи ред за фонд ГВРС ({@code
 * BgD1Emitter#gvrsEligibleCodes}). ДВЕТЕ МНОЖЕСТВА НЕ СЪВПАДАТ, и разминаването е точно в опасната
 * посока: ВИД ОСИГУРЕН 09 — „членове на кооперации, получаващи възнаграждение за работа БЕЗ трудово
 * правоотношение в кооперацията“ — НОСИ ред за ГВРС и НЯМА ТПО. Преизползван, ГВРС-сигналът щеше да
 * начисли клас на код 09 мълчаливо. {@link #theGvrsSignalIsNotTheEntitlementAxis} заковава именно
 * това разминаване, за да не се „опрости“ оста при следващия прочит.
 *
 * <p><b>⚠️ КЛАСЪТ ОСТАВА САМО ЗА ТРИНАЙСЕТ КОДА</b> — онези, които стъпват на трудов договор по КТ,
 * включително СУБСИДИРАНАТА ЗАЕТОСТ (25 и 72): чл. 2 на наредбата няма изключение за програмите за
 * заетост, а заетостта по Закона за насърчаване на заетостта е по трудов договор. Всичко останало
 * го губи — СЛУЖЕБНИТЕ ПРАВООТНОШЕНИЯ (05, 06, 17, 24, 83: държавна служба и специални закони,
 * Наредба Н-13 т. 12, НЗСДА чл. 19) и код 87, при който трудово правоотношение ИМА, но
 * работодателят е ЧУЖД. Ако някому от тях се дължи допълнително възнаграждение за прослужено време,
 * то идва от друг акт и с друга ставка — тоест друго перо, не това. {@link
 * #onlyTheNamedCodesKeepTheBonus} заковава точния списък, за да не се разшири мълчаливо при
 * следваща редакция на речника.
 *
 * <p>Всеки свидетел е чупен преднамерено, преди да остане зелен.
 */
class BgSeniorityEntitlementTest {

  private static final String TYPES_FILE = "bg_insured_type.csv";
  private static final String CONTRIBUTIONS_FILE = "bg_insurance_contribution.csv";
  private static final String FLAG = "bgEntitledToLengthOfServiceBonus";
  private static final String GVRS = "GVRS";

  /** Наето лице по трудов договор — регресия-пазачът. */
  private static final String EMPLOYEE = "01";

  /** Договор за управление и контрол. */
  private static final String DUK = "10";

  /** Членове на кооперации БЕЗ трудово правоотношение — критичният тест на оста. */
  private static final String COOP_NO_CONTRACT = "09";

  /** Работещи без трудови правоотношения (граждански договор) — следващата фаза. */
  private static final String CIVIL = "14";

  /** Държавен служител по ЗДСл — служебно правоотношение, извън НСОРЗ. */
  private static final String CIVIL_SERVANT = "05";

  /** Субсидирана заетост по програма — трудов договор по КТ, класът СЕ дължи. */
  private static final String SUBSIDISED = "25";

  /** Изпратен на работа в чужбина от български посредник — ТПО има, но работодателят е ЧУЖД. */
  private static final String POSTED_ABROAD = "87";

  /**
   * ЕДИНСТВЕНИТЕ кодове, на които класът се начислява.
   *
   * <p><b>⚠️ ЗАКОВАН СПИСЪК, А НЕ ПРАВИЛО — И ТОВА Е ЦЕЛИЯТ МУ СМИСЪЛ.</b> Без него енджинът би
   * начислил клас на ВСЕКИ фиш с попълнена заплата и котва на стажа, без да пита за
   * правоотношението. Тоест обхватът на правилото е „всичко освен тринайсет кода“, и точно това
   * трябва да е записано някъде поименно. Разширен утре мимоходом — вдигнат код, за който никой не
   * е решавал — наборът поваля {@link #onlyTheNamedCodesKeepTheBonus}, вместо да мине като редакция
   * на речника.
   */
  private static final Set<String> KEEP_THE_BONUS =
      new TreeSet<>(
          Arrays.asList(
              "01", "02", "03", "04", "08", "16", "25", "26", "42", "72", "92", "95", "99"));

  /**
   * Служебните правоотношения — държавна служба и специални закони, ИЗВЪН НСОРЗ.
   *
   * <p>Наредба Н-13 т. 12 и НЗСДА чл. 19. Тези кодове получаваха клас от заварения енджин и го
   * губят: правоотношението им не е трудово по КТ, а възнаграждението им се урежда от собствените
   * им актове. Вдигнат утре флагът им, този тест го хваща.
   */
  private static final List<String> CIVIL_SERVICE = Arrays.asList("05", "06", "17", "24", "83");

  private static final BigDecimal BASE_2000 = new BigDecimal("2000.00");

  /** Законовият минимум по ПМС № 147/2007 — 0,6 на сто за всяка навършена година. */
  private static final BigDecimal PCT_MIN = new BigDecimal("0.6000");

  private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate TO = LocalDate.of(2026, 3, 31);

  /** Котва на стажа отпреди 10 години — към края на март 2026 г. дава 10 навършени. */
  private static final LocalDate ANCHOR_10Y = LocalDate.of(2016, 3, 1);

  // ═══ А · ЧИСТАТА АРИТМЕТИКА — СЪЩИЯТ ВХОД, РАЗЛИЧНА ОС ══════════════════

  /**
   * ДУК, 2000,00 основна, 10 години стаж → класът НЕ се дължи.
   *
   * <p>FAIL-FIRST: енджин, който не пита за правоотношението, дава тук {@code COMPUTED 120.00}.
   */
  @Test
  @DisplayName("ДУК · 2000,00 и 10 години стаж → класът НЕ се дължи, а не „нула лева“")
  void aManagementContractGetsNoSeniorityBonus() {
    BgEarningsOutcome out =
        BgEarningsResolver.seniorityLine(Boolean.FALSE, BASE_2000, PCT_MIN, 10, 21, 21);

    assertEquals(BgEarningsOutcome.Status.NOT_ENTITLED, out.status);
    assertTrue(out.isNotEntitled());
    assertFalse(out.isComputed());
    // ⚠️ Сумата е null, а НЕ 0,00: перо с нула би твърдяло, че класът се дължи и този месец е нула.
    assertNull(out.amount);
    assertTrue(out.detail.contains("чл. 12 ал. 1"), out.detail);
  }

  /**
   * Наето лице, СЪЩИТЕ числа → класът е непроменен.
   *
   * <p>⚠️ Двата свидетеля се четат ЗАЕДНО: единственото различно между тях е оста. Ако проверката
   * на правото пипне формулата, тук излиза друго число, а не същото.
   */
  @Test
  @DisplayName("Нает · 2000,00 и 10 години стаж → 120,00, непроменено")
  void anEmployeeKeepsTheSeniorityBonus() {
    BgEarningsOutcome out =
        BgEarningsResolver.seniorityLine(Boolean.TRUE, BASE_2000, PCT_MIN, 10, 21, 21);

    assertTrue(out.isComputed());
    assertEquals(new BigDecimal("120.00"), out.amount);
    // Документацията на сумата: навършените години и процентът за година.
    assertEquals(BigDecimal.valueOf(10), out.quantity);
    assertEquals(PCT_MIN, out.rate);
  }

  /**
   * СЛУЖЕБЕН (05), СЪЩИТЕ ЧИСЛА → класът НЕ се дължи.
   *
   * <p>⚠️ Признакът се ЧЕТЕ ОТ SEED-А, а не се подава наум: това е единственото място, където
   * веригата „ред в речника → факт → сметка“ се доказва цяла за код 05. Вдигнат утре флагът, тестът
   * пада тук, а не някъде в интеграция.
   *
   * <p>FAIL-FIRST: енджин, който не пита за правоотношението, дава тук {@code COMPUTED 120.00}.
   */
  @Test
  @DisplayName("Служебен (05) · 2000,00 и 10 години стаж → класът НЕ се дължи")
  void aCivilServantGetsNoSeniorityBonus() {
    assertEquals("false", flagOf(CIVIL_SERVANT), "вид осигурен 05 пак носи флага");

    BgEarningsOutcome out =
        BgEarningsResolver.seniorityLine(
            entitlementOf(CIVIL_SERVANT), BASE_2000, PCT_MIN, 10, 21, 21);

    assertEquals(BgEarningsOutcome.Status.NOT_ENTITLED, out.status);
    assertNull(out.amount);
  }

  /**
   * СУБСИДИРАНА ЗАЕТОСТ (25), СЪЩИТЕ ЧИСЛА → 120,00, както при всеки друг нает.
   *
   * <p><b>⚠️ ЗАЩО ИЗОБЩО Е СВИДЕТЕЛ.</b> Наименованието на код 25 завършва с „които не се
   * осигуряват за безработица“ и точно тази опашка кара кода да изглежда като изключение. Тя обаче
   * описва ОСИГУРИТЕЛНИЯ обхват, а класът е за придобит ТРУДОВ СТАЖ — две различни неща, прочетени
   * от един и същи ред. Чл. 2 на наредбата се прилага за работниците и служителите по трудово
   * правоотношение във всички предприятия и НЯМА изключение за програмите за заетост, а заетостта
   * по Закона за насърчаване на заетостта е по трудов договор.
   *
   * <p>⚠️ Признакът се ЧЕТЕ ОТ SEED-А, а не се подава наум — свален утре, тестът пада тук с
   * числото.
   */
  @Test
  @DisplayName("Субсидирана заетост (25) · 2000,00 и 10 години стаж → 120,00")
  void subsidisedEmploymentKeepsTheSeniorityBonus() {
    assertEquals("true", flagOf(SUBSIDISED), "вид осигурен 25 загуби класа");

    BgEarningsOutcome out =
        BgEarningsResolver.seniorityLine(entitlementOf(SUBSIDISED), BASE_2000, PCT_MIN, 10, 21, 21);

    assertTrue(out.isComputed());
    assertEquals(new BigDecimal("120.00"), out.amount);
  }

  /**
   * ИЗПРАТЕНИЯТ В ЧУЖБИНА (87) → класът НЕ се дължи, и това НЕ Е същият довод като при 25.
   *
   * <p>При код 87 трудово правоотношение ИМА — но работодателят е ЧУЖД, тоест българската наредба
   * не важи за него. Двата свидетеля стоят един до друг нарочно: „има трудов договор“ само по себе
   * си не стига, наредбата иска и предприятието да е по § 1 т. 2 от ДР на КТ.
   */
  @Test
  @DisplayName("Изпратен в чужбина (87) · трудов договор с ЧУЖД работодател → класът НЕ се дължи")
  void aWorkerPostedAbroadGetsNoSeniorityBonus() {
    assertEquals("false", flagOf(POSTED_ABROAD), "вид осигурен 87 получи клас");

    BgEarningsOutcome out =
        BgEarningsResolver.seniorityLine(
            entitlementOf(POSTED_ABROAD), BASE_2000, PCT_MIN, 10, 21, 21);

    assertEquals(BgEarningsOutcome.Status.NOT_ENTITLED, out.status);
    assertNull(out.amount);
  }

  /** „Никой не е питан“ не е нито „да“, нито „не“ — то е отказ с назована причина. */
  @Test
  @DisplayName("0 не е решение · неустановено правоотношение спира класа с причина")
  void anUnknownRelationRefusesInsteadOfGuessing() {
    BgEarningsOutcome out = BgEarningsResolver.seniorityLine(null, BASE_2000, PCT_MIN, 10, 21, 21);

    assertTrue(out.isRefusal());
    assertNull(out.amount);
    assertTrue(out.detail.contains("вид"), out.detail);
  }

  /**
   * Правото се пита ПРЕДИ липсите — иначе отказът праща оператора да поправи вярното поле.
   *
   * <p>Управителят по ДУК рядко има попълнен признат стаж. Питано второ, лицето щеше да получи
   * „попълни котвата на стажа“ — съобщение, което кара да се въведе стаж, който после пак не бива
   * да дава клас.
   */
  @Test
  @DisplayName("Редът на въпросите · неТПО без стаж и без процент пак отговаря „не се дължи“")
  void theEntitlementIsAskedBeforeAnyMissingInput() {
    BgEarningsOutcome out =
        BgEarningsResolver.seniorityLine(Boolean.FALSE, null, null, null, null, null);

    assertTrue(out.isNotEntitled());
    assertFalse(out.detail.contains("bgPriorExperience"), out.detail);
  }

  // ═══ Б · РЕЧНИКЪТ — КОЙ КОД НОСИ ПРАВОТО ════════════════════════════════

  private static String flagOf(String code) {
    List<String> head = BgSeed.quotedHeader(TYPES_FILE);
    for (String[] row : BgSeed.quotedRows(TYPES_FILE)) {
      if (code.equals(BgSeed.col(head, row, "code"))) {
        return BgSeed.col(head, row, FLAG);
      }
    }
    return null;
  }

  /** Флагът на този код, прочетен от seed-а като отговора, който енджинът получава. */
  private static Boolean entitlementOf(String code) {
    return Boolean.valueOf("true".equals(flagOf(code)));
  }

  @Test
  @DisplayName("ФАЙЛ · речникът носи оста, и тя е ОТДЕЛНА колона")
  void theDictionaryCarriesTheAxis() {
    assertTrue(
        BgSeed.quotedHeader(TYPES_FILE).contains(FLAG), BgSeed.quotedHeader(TYPES_FILE).toString());
  }

  @Test
  @DisplayName("ФАЙЛ · ДУК (10), кооп. член без ТПО (09), ГД (14) и 27 НЯМАТ право на клас")
  void theNonLabourCodesAreNotEntitled() {
    assertEquals("false", flagOf(DUK));
    assertEquals("false", flagOf(COOP_NO_CONTRACT));
    assertEquals("false", flagOf(CIVIL));
    // 27 — обезщетение за времето БЕЗ работа: правоотношението вече е прекратено.
    assertEquals("false", flagOf("27"));
  }

  /**
   * ⚠️ ОБХВАТЪТ НА ПРОМЯНАТА, ЗАКОВАН С ИМЕНА.
   *
   * <p>Този тест не проверява правна теза, а ОБЕЩАНИЕ: класът се начислява на точно тринайсет кода
   * и на нито един друг. Всеки нов вдигнат ред е решение по същество и трябва да се вижда тук.
   */
  @Test
  @DisplayName("ОБХВАТ · точно 13 кода носят класа, и те са НАЗОВАНИ")
  void onlyTheNamedCodesKeepTheBonus() {
    List<String> head = BgSeed.quotedHeader(TYPES_FILE);
    Set<String> up = new TreeSet<>();
    for (String[] row : BgSeed.quotedRows(TYPES_FILE)) {
      if ("true".equals(BgSeed.col(head, row, FLAG))) {
        up.add(BgSeed.col(head, row, "code"));
      }
    }
    assertEquals(
        KEEP_THE_BONUS,
        up,
        "сменил се е ОБХВАТЪТ на промяната, не само един ред. Всеки код тук получава клас върху"
            + " всеки свой фиш — това иска изрично решение по НСОРЗ чл. 12 ал. 1, не редакция на"
            + " seed-а мимоходом.");
  }

  /**
   * СЛУЖЕБНИТЕ ПРАВООТНОШЕНИЯ СА ИЗВЪН НСОРЗ — Наредба Н-13 т. 12, НЗСДА чл. 19.
   *
   * <p>05, 06, 17, 24 и 83 са държавна служба и специални закони — класът по НСОРЗ не им се
   * начислява. Ако им се дължи допълнително възнаграждение за прослужено време, то идва от техния
   * СОБСТВЕН акт и с друга ставка — тоест друго перо, не това.
   */
  @Test
  @DisplayName("ФАЙЛ · служебните (05, 06, 17, 24, 83) и 87 НЯМАТ право на клас")
  void theCivilServiceCodesAreNotEntitled() {
    for (String code : CIVIL_SERVICE) {
      assertEquals(
          "false", flagOf(code), "вид осигурен " + code + " получи клас, който не се дължи");
    }
    // 87 — трудово правоотношение ИМА, но с ЧУЖД работодател: българската наредба не важи.
    assertEquals("false", flagOf(POSTED_ABROAD));
  }

  /**
   * СУБСИДИРАНАТА ЗАЕТОСТ (25, 72) НОСИ КЛАСА — наредбата няма изключение за програмите.
   *
   * <p>⚠️ Двата кода се четат заедно със свидетеля за 87: и трите носят по нещо, което ПРИЛИЧА на
   * основание класът да отпадне — „не се осигуряват за безработица“ при 25/72, „на работа в
   * чужбина“ при 87. Само едното е основание, и то е ДРУГОТО: осигурителният обхват не решава
   * правото на клас, а чуждият работодател изважда правоотношението от обхвата на наредбата.
   */
  @Test
  @DisplayName("ФАЙЛ · субсидираната заетост (25, 72) ЗАПАЗВА класа")
  void theSubsidisedEmploymentCodesAreEntitled() {
    for (String code : Arrays.asList("25", "72")) {
      assertEquals(
          "true",
          flagOf(code),
          "вид осигурен "
              + code
              + " загуби класа. „Не се осигуряват за безработица“ е ОСИГУРИТЕЛЕН обхват, не"
              + " основание: класът е за придобит ТРУДОВ СТАЖ, а заетостта по ЗНЗ е по трудов"
              + " договор по КТ.");
    }
  }

  @Test
  @DisplayName("ФАЙЛ · самоосигуряващите се (12, 13, 22) НЯМАТ право на клас")
  void theSelfInsuredAreNotEntitled() {
    for (String code : Arrays.asList("12", "13", "22")) {
      assertEquals("false", flagOf(code), "вид осигурен " + code);
    }
  }

  /** Регресия-пазач: правилото маха неправомерния клас, а не класа изобщо. */
  @Test
  @DisplayName("ФАЙЛ · наетите по трудов договор ЗАПАЗВАТ правото на клас")
  void theEmployeesKeepTheEntitlement() {
    for (String code : Arrays.asList("01", "02", "03", "04", "08", "16", "26")) {
      assertEquals("true", flagOf(code), "вид осигурен " + code);
    }
  }

  /**
   * ⚠️ КЛЮЧОВИЯТ СВИДЕТЕЛ: „има ГВРС“ НЕ Е „има право на клас“.
   *
   * <p>Код 09 е и в двете страни на разминаването наведнъж: НОСИ ред за фонд ГВРС в речника с
   * вноски (публикуваната таблица на НАП към 01.08.2026 г. изключва от ГВРС само 10, 14 и 27) и
   * НЯМА трудово правоотношение (името му го казва дословно). Тоест сигналът, който т. 29/т. 30 на
   * Д1 ползват, щеше да му начисли клас.
   *
   * <p>Тестът пада, ако някой някога „опрости“ оста до ГВРС-сигнала — и пада с числото 09 в ръката.
   */
  @Test
  @DisplayName("ГВРС НЕ Е ОСТА · код 09 носи ГВРС, но няма ТПО и няма право на клас")
  void theGvrsSignalIsNotTheEntitlementAxis() {
    List<String> head = BgSeed.header(CONTRIBUTIONS_FILE);
    Set<String> gvrs = new TreeSet<>();
    for (String[] row : BgSeed.rows(CONTRIBUTIONS_FILE)) {
      if (GVRS.equals(BgSeed.col(head, row, "fundCode"))) {
        gvrs.add(BgSeed.col(head, row, "bgInsuredTypeCode"));
      }
    }

    List<String> typeHead = BgSeed.quotedHeader(TYPES_FILE);
    Set<String> entitled = new TreeSet<>();
    for (String[] row : BgSeed.quotedRows(TYPES_FILE)) {
      if ("true".equals(BgSeed.col(typeHead, row, FLAG))) {
        entitled.add(BgSeed.col(typeHead, row, "code"));
      }
    }

    assertTrue(
        gvrs.contains(COOP_NO_CONTRACT),
        "код 09 вече не носи ГВРС — свидетелят е остарял: " + gvrs);
    assertFalse(
        entitled.contains(COOP_NO_CONTRACT),
        "код 09 получи право на клас, а няма трудово правоотношение (КСО чл. 4 ал. 1 т. 5) — това е"
            + " точно капанът „ГВРС-посредник“: "
            + entitled);

    Set<String> gvrsButNotEntitled = new LinkedHashSet<>(gvrs);
    gvrsButNotEntitled.removeAll(entitled);
    assertTrue(
        gvrsButNotEntitled.contains(COOP_NO_CONTRACT),
        "двете множества съвпаднаха — тогава някой е сменил едното. Оста „право на клас“ и"
            + " признакът „носи ГВРС“ НЕ са едно и също и не бива да се слеят: "
            + gvrs);
  }

  // ═══ В · ГЕНЕРАТОРЪТ — ПЕРОТО ВЪРХУ ИСТИНСКИ ФИШ ════════════════════════

  private static BgPaySlipLineType type(String code, int number, String name) {
    BgPaySlipLineType type = new BgPaySlipLineType();
    type.setCode(code);
    type.setNumber(number);
    type.setName(name);
    type.setBgSign("CREDIT");
    type.setBgIsTaxable(true);
    type.setBgIsInsuranceIncome(true);
    type.setBgCalculationBase(BgEarningsResolver.CALC_CONTRACT_BASE_SALARY);
    return type;
  }

  private static List<BgPaySlipLineType> catalogue() {
    return Arrays.asList(
        type(BgEarningsResolver.LINE_BASE_SALARY, 100, "Основно възнаграждение"),
        type(BgEarningsResolver.LINE_SENIORITY, 110, "Клас прослужено време"));
  }

  /**
   * Услуга с подменени ЧЕТЦИ; правилата ѝ остават нейни — {@link BgEarningsResolver} се вика
   * истински.
   */
  private static BgEarningsService serviceFor(Boolean entitled) {
    return new BgEarningsService() {
      @Override
      protected BigDecimal statutoryMinPct(LocalDate date) {
        return PCT_MIN;
      }

      /**
       * ⚠️ ЧАСОВИТЕ СТАВКИ НЕ СЕ ЗАРЕЖДАТ, И ТОВА Е ОТГОВОР, А НЕ ПРОПУСК. Този свидетел не гледа
       * часови пера; без подмяна обаче четецът иска Postgres, защото {@code factsOf} вади и седемте
       * параметъра НАВЕДНЪЖ. Липсващият параметър е ОТКАЗ на съответното перо — точно каквото
       * трябва да получи фикстура, която не е обявила нито една часова ставка.
       */
      @Override
      protected BigDecimal payrollParameter(String code, LocalDate date) {
        return null;
      }

      /** Няма валидирани допълнителни часове → няма уговорено увеличение. Инак иска Hibernate. */
      @Override
      protected java.util.Map<String, BigDecimal> agreedOvertimeUplift(
          com.axelor.apps.bglocalization.db.BgPaySlip slip,
          com.axelor.apps.bglocalization.service.payroll.BgPayrollInput input) {
        return java.util.Collections.emptyMap();
      }

      @Override
      protected List<BgPaySlipLineType> lineTypes() {
        return catalogue();
      }

      @Override
      protected BgPaySlipLineType lineType(String code) {
        for (BgPaySlipLineType t : catalogue()) {
          if (t.getCode().equals(code)) {
            return t;
          }
        }
        return null;
      }

      @Override
      protected BgPaySlipLine lineFrom(BgPaySlipLineType type, BigDecimal amount) {
        BgPaySlipLine line = new BgPaySlipLine();
        line.setBgPaySlipLineType(type);
        line.setBgLineNumber(type.getNumber());
        line.setName(type.getName());
        line.setBgSign(type.getBgSign());
        line.setBgAmount(amount);
        return line;
      }

      @Override
      protected Boolean seniorityEntitlement(BgPaySlip slip) {
        return entitled;
      }
    };
  }

  private static BgPaySlip slip(String insuredTypeCode) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    slip.setBgBaseSalary(BASE_2000);
    slip.setBgWorkedDays(21);
    slip.setBgWorkingDaysInPeriod(21);
    slip.setBgPaySlipLineList(new ArrayList<>());

    Period period = new Period();
    period.setFromDate(FROM);
    period.setToDate(TO);
    slip.setPeriod(period);

    EmploymentContract contract = new EmploymentContract();
    contract.setPayCompany(new Company());
    contract.setBgInsuredTypeCode(insuredTypeCode);
    slip.setEmploymentContract(contract);
    return slip;
  }

  /** Вход по § 13.1 с КОТВАТА на стажа — единственият източник за навършените години. */
  private static BgPayrollInput input() {
    return BgPayrollInput.builder()
        .periodFrom(FROM)
        .periodTo(TO)
        .parameterReferenceDate(FROM)
        .recognisedServiceStartDate(ANCHOR_10Y)
        .build();
  }

  private static BgPaySlipLine lineOf(BgPaySlip slip, String code) {
    for (BgPaySlipLine line : slip.getBgPaySlipLineList()) {
      if (line.getBgPaySlipLineType() != null
          && code.equals(line.getBgPaySlipLineType().getCode())) {
        return line;
      }
    }
    return null;
  }

  @Test
  @DisplayName("ФИШ · ДУК получава основната си заплата, но НИТО ЕДНО перо за клас")
  void theManagementContractSlipCarriesNoSeniorityLine() {
    BgPaySlip slip = slip(DUK);
    List<BgEarningsOutcome> outcomes = serviceFor(Boolean.FALSE).generate(slip, input());

    assertNull(lineOf(slip, BgEarningsResolver.LINE_SENIORITY));
    assertNotNull(lineOf(slip, BgEarningsResolver.LINE_BASE_SALARY));
    assertEquals(1, slip.getBgPaySlipLineList().size());
    // ⚠️ Основното перо остава непроменено — правилото е за класа, не за заплатата.
    assertEquals(
        new BigDecimal("2000.00"), lineOf(slip, BgEarningsResolver.LINE_BASE_SALARY).getBgAmount());
    assertEquals(
        BgEarningsOutcome.Status.NOT_ENTITLED,
        outcomes.stream()
            .filter(o -> BgEarningsResolver.LINE_SENIORITY.equals(o.lineCode))
            .findFirst()
            .orElseThrow(AssertionError::new)
            .status);
  }

  @Test
  @DisplayName("ФИШ · код 09 (има ГВРС, няма ТПО) също не получава клас")
  void theCooperativeMemberWithoutAContractCarriesNoSeniorityLine() {
    BgPaySlip slip = slip(COOP_NO_CONTRACT);
    serviceFor(Boolean.FALSE).generate(slip, input());

    assertNull(lineOf(slip, BgEarningsResolver.LINE_SENIORITY));
  }

  @Test
  @DisplayName("ФИШ · гражданският договор (14) получава възнаграждението си, но клас — не")
  void theCivilContractSlipCarriesNoSeniorityLine() {
    BgPaySlip slip = slip(CIVIL);
    List<BgEarningsOutcome> outcomes = serviceFor(entitlementOf(CIVIL)).generate(slip, input());

    assertNull(
        lineOf(slip, BgEarningsResolver.LINE_SENIORITY),
        "класът е допълнително ТРУДОВО възнаграждение по КТ чл. 12 ал. 1 от Наредбата за"
            + " структурата и организацията на работната заплата — предпоставката му е трудово"
            + " правоотношение. КСО чл. 4 ал. 3 т. 5 и 6 назовават лицето отрицателно: „БЕЗ"
            + " трудово правоотношение“.");
    assertNotNull(
        lineOf(slip, BgEarningsResolver.LINE_BASE_SALARY),
        "възнаграждението остава — стеснението е за класа, не за плащането");
    assertEquals(
        BgEarningsOutcome.Status.NOT_ENTITLED,
        outcomes.stream()
            .filter(o -> BgEarningsResolver.LINE_SENIORITY.equals(o.lineCode))
            .findFirst()
            .orElseThrow(AssertionError::new)
            .status,
        "⚠️ NOT_ENTITLED, А НЕ ЛИПСВАЩ ИЗХОД. Двете дават еднакво празен фиш и различно"
            + " твърдение: първото значи „питахме и правото не възниква“, второто — „никой не"
            + " попита“. Само първото се вижда от оператора.");
  }

  @Test
  @DisplayName("ФИШ · служебният (05) получава заплатата си, но НИТО ЕДНО перо за клас")
  void theCivilServantSlipCarriesNoSeniorityLine() {
    BgPaySlip slip = slip(CIVIL_SERVANT);
    serviceFor(entitlementOf(CIVIL_SERVANT)).generate(slip, input());

    assertNull(lineOf(slip, BgEarningsResolver.LINE_SENIORITY));
    assertNotNull(lineOf(slip, BgEarningsResolver.LINE_BASE_SALARY));
  }

  @Test
  @DisplayName("ФИШ · наетият запазва перото си за клас — 120,00")
  void theEmployeeSlipKeepsTheSeniorityLine() {
    BgPaySlip slip = slip(EMPLOYEE);
    serviceFor(Boolean.TRUE).generate(slip, input());

    BgPaySlipLine line = lineOf(slip, BgEarningsResolver.LINE_SENIORITY);
    assertNotNull(line);
    assertEquals(new BigDecimal("120.00"), line.getBgAmount());
  }

  /**
   * ⚠️ ЗАВАРЕНОТО ПЕРО СЕ МАХА, А НЕ СЕ ПРЕСКАЧА.
   *
   * <p>Заварен фиш може да носи клас и на ДУК. Прескочен, той би го запазил завинаги — правилото би
   * важало само за новите фишове, а сгрешените са точно старите. Връзката е {@code orphanRemoval =
   * true}, тоест махнатият ред изчезва и от базата.
   */
  @Test
  @DisplayName("ФИШ · заварен клас на ДУК се МАХА при следващата подготовка")
  void aSeniorityLineFromBeforeTheFixIsRemoved() {
    BgPaySlip slip = slip(DUK);
    // Заварен фиш на ДУК с 120,00 клас.
    BgPaySlipLine stale = new BgPaySlipLine();
    stale.setBgPaySlipLineType(
        type(BgEarningsResolver.LINE_SENIORITY, 110, "Клас прослужено време"));
    stale.setBgLineNumber(110);
    stale.setBgAmount(new BigDecimal("120.00"));
    stale.setPaySlip(slip);
    slip.getBgPaySlipLineList().add(stale);

    serviceFor(Boolean.FALSE).generate(slip, input());

    assertNull(lineOf(slip, BgEarningsResolver.LINE_SENIORITY));
    assertNull(stale.getPaySlip());
  }
}
