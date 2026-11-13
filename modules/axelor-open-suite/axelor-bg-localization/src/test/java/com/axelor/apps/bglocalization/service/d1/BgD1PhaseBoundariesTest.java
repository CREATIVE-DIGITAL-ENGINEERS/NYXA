package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD1RecordAdapter;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ГРАНИЦИТЕ НА ФАЗА 1 — ОБЯВЕНИ, НЕ ПРЕМЪЛЧАНИ.
 *
 * <p><b>Защо тест, а не ред в README.</b> Осем точки от Приложение № 1 днес НЕ се произвеждат.
 * Всяка липсва по своя причина и всяка причина е записана тук. Без този тест липсата е неразличима
 * от сметната нула — а „0,00 лв. вноска“ и „0 дни в осигуряване“ са ВАЛИДНИ числа, които НАП
 * приема. Тоест мълчаливото допълване на някоя от тези точки не би счупило нищо видимо: то би
 * произвело декларация, която изглежда пълна и лъже.
 *
 * <p><b>Тестът пази в ДВЕТЕ посоки.</b> Пада, ако граница изчезне мълчаливо (точката се появи, без
 * някой да е свалил името ѝ оттук), и пада, ако някой махне името, без да построи точката.
 *
 * <p><b>Причините, поотделно:</b>
 *
 * <ul>
 * </ul>
 *
 * <p><b>⚠️ ДНЕС СПИСЪКЪТ Е ПРАЗЕН — всичките 54 полета имат носител.</b> Т. 34 излезе оттук на два
 * хода. Първо границата ѝ СМЕНИ ВИДА СИ: колоната я имаше, флаговото правило за 20-те кода
 * работеше, а липсваше ТЕКСТЪТ на формулата (ПМС 188/2015 не беше набавен) — тоест граница, която
 * се обявява на ниво ЗАПИС, а не с един ред тук. После текстът беше набавен и т. 34 получи СМЕТКА
 * ({@code BgD1NetPayResolver}, НПОПДОО чл. 32). ⚠️ Останаха ТРИ различни празни изхода, всеки със
 * свой код ({@code D1_T34_CAP_NOT_READ}, {@code D1_T34_NOT_ACCRUED}, {@code D1_T34_NEGATIVE}) — и
 * точно защото са три, те не могат да живеят като едно име в списък тук.
 *
 * <p>⚠️ И тестът пази ОБРАТНАТА посока: нова отложена точка не може да се появи, без някой да я
 * впише тук с причина.
 *
 * <p><b>⚠️ Т. 29 И Т. 31 ВЛЯЗОХА.</b> Т. 29 получи СВОЙ агрегат ({@code
 * BgPaySlipResolver#grossWageTotal}) и СВОЙ класификатор на речника ({@code
 * BgPaySlipLineType.bgFormsGrossWage}) — {@code bgGrossTotal} наистина не беше това, защото включва
 * група 9xx. Т. 31 получи {@code accruedTaxableTotal}, който спира ПРЕДИ приспадането на личните
 * вноски — там е разликата с {@code bgTaxBase}.
 *
 * <p>⚠️ Извън този списък стои още едно нещо, което НЕ е отложена ТОЧКА: <b>т. 13 поз. 2</b>
 * (поредността на месеца при СИРВ). Колоната ѝ вече Я ИМА — тя се въвежда НА РЪКА, защото иска
 * НАЧАЛОТО на отчетния период (акт на работодателя по КТ чл. 142 ал. 2), а изведена от календарната
 * година дава ВАЛИДНА и невярна цифра. „Няма автоматичен източник“ и „няма носител“ са различни
 * състояния и този списък описва второто.
 *
 * <p><b>⚠️ Т. 17/18 И Т. 27/28 ВЕЧЕ ГИ ИМА — БЛОКЕРЪТ ПАДНА, И ТО БЕЗ НОВ ФОНД.</b> Дотук тук
 * стоеше „смята се вярно, но НЯМА КЪДЕ да се запише: няма ред във фондовия речник, тоест няма и тип
 * перо“. Диагнозата беше вярна, а изводът — че трябва нов фонд — не: НЗОК е ЕДИН фонд, а трите
 * точки се различават по БАЗА. Разграничителят влезе на ПЕРОТО ({@code
 * BgPaySlipLine.bgHealthBaseKind}), тоест групата 5xx остава огледална на речника с фондовете
 * (двайсет типа, инвариантът е цял), а три пера от един и същи тип могат да съществуват
 * едновременно — всяко със своята база в {@code bgQuantity} и своята ставка в {@code bgRate}.
 *
 * <p><b>⚠️ Т. 12.2 ВЕЧЕ Я ИМА, и границата ѝ се СВИ, вместо да падне.</b> Дотук тя чакаше решение
 * „КИД на ОСИГУРЕНИЯ, а днес има само {@code Company.bgNkid}“. Решението е взето: фирмите са с ЕДНА
 * икономическа дейност, тоест дейността на дружеството Е дейността на осигурения и договорът НЕ
 * получава свое поле. Остана само преводът „01.11“ → „0111“ (поле 29 е Char с размер 4).
 *
 * <p><b>⚠️ Т. 14/15 СЪЩО ВЛЯЗОХА, но само ПОЗИЦИЯ 1 от пет.</b> Позиции 2–5 искат носител за
 * МЕЖДИННО прекъсване на осигуряването, какъвто моделът няма — и това не е точка-число, а позиция в
 * точка, затова се обявява от валидатора при всеки запис, който пълни позиция 1 ({@code
 * D1_T14_T15_ONE_PAIR_ONLY}), не тук.
 */
class BgD1PhaseBoundariesTest {

  /** Точка → защо още я няма. Четимо от човек, защото този текст Е свидетелството. */
  private static final Map<String, String> WHY_NOT_YET = new LinkedHashMap<>();

  static {
  }

  @Test
  @DisplayName(
      "границите са ИМЕНУВАНИ и всяка носи причина — днес списъкът е ПРАЗЕН, и това е обявено")
  void everyBoundaryIsNamedWithItsReason() {
    assertEquals(
        new TreeSet<>(WHY_NOT_YET.keySet()),
        new TreeSet<>(BgD1Point.NOT_YET_EMITTED),
        "списъкът в BgD1Point.NOT_YET_EMITTED и обясненията тук се разминаха. Точка без обяснение"
            + " е премълчана граница; обяснение без точка е остаряла бележка.");
    for (Map.Entry<String, String> e : WHY_NOT_YET.entrySet()) {
      assertTrue(
          e.getValue().length() > 40,
          "т. " + e.getKey() + " има причина, но тя не казва нищо: " + e.getValue());
    }
  }

  /**
   * Точките, които записът НАИСТИНА носи — по име на getter.
   *
   * <p>⚠️ ИЗБРОЕНИ ПОИМЕННО, не изведени с префикс. Първата версия на този тест търсеше „getT“ +
   * номера на отложената точка и пропускаше цял клас появи: колона {@code t161} („16.1“) започва с
   * „getT16“, но следващият знак е ЦИФРА, тоест всяко правило, което гледа само префикса, или я
   * изпуска, или обявява т. 31а за т. 31. Изричният списък няма този проблем и има второ, по-важно
   * свойство: НОВА точка не може да се появи мълчаливо — тя пада тук, докато някой не я обяви.
   */
  private static final TreeSet<String> EMITTED_GETTERS =
      new TreeSet<>(
          java.util.Arrays.asList(
              "getT1CorrectionCode",
              "getT2Month",
              "getT3Year",
              "getT4ObligedPersonCode",
              "getT5PersonalId",
              "getT51PersonalIdCode",
              "getT6Surname",
              "getT7Initials",
              "getT12InsuredTypeCode",
              "getT121QualificationGroup",
              "getT122ActivityCode",
              "getT123ActivityRow",
              "getT13SirvPeriodMonths",
              "getT13SirvMonthIndex",
              "getT14Day1",
              "getT14Day2",
              "getT14Day3",
              "getT14Day4",
              "getT14Day5",
              "getT15Day1",
              "getT15Day2",
              "getT15Day3",
              "getT15Day4",
              "getT15Day5",
              "getT16Days",
              "getT16LegalHours",
              "getT16ContractedHours",
              "getT161WorkedDays",
              "getT162SickDays",
              "getT163ChildCareDays",
              "getT164NoContributionDays",
              "getT165UnpaidLeaveDays",
              "getT16aEmployerSickDays",
              "getT167WorkedHours",
              "getT168OvertimeHours",
              "getT17Art4015Income",
              "getT18HealthRateOnT17",
              "getT21InsuranceIncome",
              "getT29GrossWage",
              "getT22DooRateEmployer",
              "getT22DooRateInsured",
              "getT221HealthRateEmployer",
              "getT221HealthRateInsured",
              "getT222TzpbRate",
              "getT23TeacherPfRate",
              "getT25PpfRate",
              "getT26UpfRateEmployer",
              "getT26UpfRateInsured",
              "getT27HealthOnlyIncome",
              "getT28HealthRateOnT27",
              "getT30GvrsRate",
              "getT31TaxableIncome",
              "getT31aTax",
              "getT34NetPay"));

  /** Всички getter-и на записа, които назовават ТОЧКА (тоест „getT“ + цифра). */
  private static TreeSet<String> pointGetters() {
    TreeSet<String> out = new TreeSet<>();
    for (Method m : BgD1Record.class.getMethods()) {
      String name = m.getName();
      if (name.length() > 4
          && name.startsWith("getT")
          && Character.isDigit(name.charAt(4))
          && m.getParameterCount() == 0) {
        out.add(name);
      }
    }
    return out;
  }

  @Test
  @DisplayName("нито една отложена точка НЕ се появява като поле на записа")
  void noDeferredPointHasAColumnOnTheRecord() {
    // ⚠️ Проверява се СТРУКТУРАТА, не стойност. Празна колона в базата е неразличима от сметната
    // нула — тоест единствената надеждна граница е колоната изобщо да я няма.
    TreeSet<String> actual = pointGetters();

    TreeSet<String> unexpected = new TreeSet<>(actual);
    unexpected.removeAll(EMITTED_GETTERS);
    if (!unexpected.isEmpty()) {
      fail(
          "BgD1Record носи точки, които не са обявени: "
              + unexpected
              + ".\n⚠️ Ако някоя от тях е една от отложените ("
              + BgD1Point.NOT_YET_EMITTED
              + "), тя е построена, без границата да е свалена — а празната ѝ стойност се чете"
              + " като сметната нула. Свали я от BgD1Point.NOT_YET_EMITTED и от WHY_NOT_YET"
              + " ЗАЕДНО с построяването, в един комит.\n  Причини, записани тук: "
              + WHY_NOT_YET);
    }

    TreeSet<String> vanished = new TreeSet<>(EMITTED_GETTERS);
    vanished.removeAll(actual);
    assertTrue(
        vanished.isEmpty(),
        "точки, които Фаза 1 обявява за произведени, вече ги няма на записа: "
            + vanished
            + " — тоест декларацията тихо обедня.");
  }

  @Test
  @DisplayName("адаптерът също не изнася отложена точка — нито като празен низ, нито като 0.00")
  void theAdapterDoesNotEmitDeferredPoints() {
    Map<String, String> fields = new BgD1RecordAdapter().toFields(new BgD1Record());
    for (String point : BgD1Point.NOT_YET_EMITTED) {
      assertTrue(
          !fields.containsKey(point),
          "адаптерът дава ключ за т. "
              + point
              + " със стойност „"
              + fields.get(point)
              + "“. ⚠️ Точно тук нулата е най-опасна: „0.00“ на реда на т. 31 изглежда като"
              + " сметнат облагаем доход. Причина: "
              + WHY_NOT_YET.get(point));
    }
    assertTrue(
        fields.containsKey(BgD1Point.T31A_TAX),
        "т. 31а СЕ емитира — тя е начисленият данък по чл. 42 ал. 4 ЗДДФЛ и има източник"
            + " (BgPaySlip.bgTaxAmount). Да изпадне заедно с т. 31 би било грешка в другата посока.");
  }

  @Test
  @DisplayName("сериализацията към файл НЕ съществува — заповедта по чл. 8 ал. 5 я няма")
  void thereIsNoFileSerialisationYet() {
    // Адаптерът връща КАРТА „точка → низ“, не РЕД. Карта не се преструва, че знае подредба и
    // ширини — те идват от заповед на изпълнителния директор на НАП по чл. 8 ал. 5, която не е в
    // наредбата, не е в репото и не е в бъндъла на Declarations API (там съдържанието на файла е
    // непрозрачен base64 блоб). Ред с измислена подредба щеше да изглежда като готов формат.
    for (Method m : BgD1RecordAdapter.class.getDeclaredMethods()) {
      assertTrue(
          !m.getName().equals("buildLines") && !m.getName().equals("toRecordLine"),
          "BgD1RecordAdapter."
              + m.getName()
              + "() произвежда РЕД. Структурата и форматът на файла се утвърждават със заповед по"
              + " чл. 8 ал. 5 и тя още я няма — измислена подредба е по-лоша от липсваща, защото"
              + " изглежда готова.");
    }
    assertTrue(
        BgD1Point.EMITTED_FROM_FUNDS.size() == 7,
        "седемте точки, които перата произвеждат: 22, 22.1, 22.2, 23, 25, 26 и 30");
  }
}
