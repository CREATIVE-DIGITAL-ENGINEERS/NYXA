package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ЧАСОВИЯТ ЕНДЖИН — осемте часови пера.
 *
 * <p><b>Какво доказва този клас.</b> Че сметката е ВЯРНА ДО СТОТИНКАТА и че всяко от шестте правила
 * около нея е проверимо поотделно. Тече без Postgres, без Guice и без Hibernate — {@link
 * BgEarningsResolver} е чиста функция от {@link BgEarningsFacts}, а фактите се строят тук на ръка.
 *
 * <p><b>⚠️ ЗАЩО ЕДИН ГОЛЯМ СВИДЕТЕЛ И ОСЕМ МАЛКИ.</b> Голямият (Т8) хваща разминаване в
 * ЗАКРЪГЛЯНЕТО и в реда на факторите — грешка, която всяко отделно перо би преживяло, защото
 * поотделно всяко е „почти вярно“. Малките казват КОЕ правило е паднало, когато Т8 падне: без тях
 * едно число не различава сгрешен процент от сгрешен делител.
 *
 * <p>Всеки свидетел е чупен преднамерено, преди да остане зелен.
 */
class BgEarningsHourEngineTest {

  private static final String UPLIFT = BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT;
  private static final String FIXED = BgEarningsResolver.CALC_HOURLY_FIXED;
  private static final String MONTHLY = BgEarningsResolver.CALC_MONTHLY_FIXED;

  /** Подневно отчитане — стойността „1“ на {@code bg.work.time.mode.select}. */
  private static final String BY_DAY = "1";

  /**
   * ВХОДЪТ НА ЗЛАТНИЯ СВИДЕТЕЛ Т8 — март 2026 г., 21 работни дни, 8 часа на ден.
   *
   * <p>Основната е 1847,53 €, тоест часовата ставка е 1847,53 / (21 × 8) = 10,9972 €. Стажът е нула
   * навършени години НАРОЧНО: класът влиза в базата по чл. 7 и с положителен стаж сумите вече не се
   * четат наум. Свидетелят за богата база стои отделно.
   */
  private static BgEarningsFacts.Builder t8() {
    return BgEarningsFacts.builder()
        .baseSalary(new BigDecimal("1847.53"))
        .workedDays(21)
        .workingDaysInPeriod(21)
        .completedServiceYears(0)
        .seniorityRatePct(new BigDecimal("0.6000"))
        .entitledToSeniorityBonus(Boolean.TRUE)
        .contractHoursPerDay(new BigDecimal("8.00"))
        .workTimeMode(BY_DAY)
        .minWage(new BigDecimal("620.2000"))
        .nightPctOfMinWage(new BigDecimal("0.1500"))
        .nightFloorEur(new BigDecimal("0.5100"))
        .onCallEurPerHour(new BigDecimal("0.0500"))
        .degreeDoctorEur(new BigDecimal("7.6700"))
        .degreeDscEur(new BigDecimal("25.5600"));
  }

  private static void assertAmount(String expected, BgEarningsOutcome out, String what) {
    assertTrue(out.isComputed(), what + " не е сметнато: " + out.status + " — " + out.detail);
    assertEquals(
        0,
        out.amount.compareTo(new BigDecimal(expected)),
        what + " = " + out.amount.toPlainString() + ", а трябва да е " + expected);
  }

  // ── ЗЛАТНИЯТ СВИДЕТЕЛ ───────────────────────────────────────────────────

  /**
   * ⚠️ ЕДИН МЕСЕЦ, ПЕТ ПЕРА, ЕДНО ЧИСЛО НАКРАЯ — и то е точно до стотинката.
   *
   * <p>Март 2026 г.: 21 работни дни, основна 1847,53 €, 8 часа на ден. 24 нощни часа, 10 часа
   * извънреден труд в работен ден, 8 часа в почивен ден, 8 часа работа на официален празник.
   *
   * <p><b>⚠️ СБОРЪТ Е ЧАСТ ОТ ТВЪРДЕНИЕТО, НЕ ОБОБЩЕНИЕ.</b> Пет верни поотделно числа могат да
   * дадат грешен сбор, ако закръглянето се прави на друго място — а точно това § 5.3 урежда с „едно
   * деление накрая“. Проверен само по пера, преход към закръглена часова ставка × часове би минал
   * зелен при тези числа и щеше да се обади при други.
   *
   * <p>FAIL-FIRST: {@code hourlyLineAmount} беше пренаписан да умножава ЗАКРЪГЛЕНАТА на 4 знака
   * часова ставка по часовете → изв. работен ден остана 164,96, но сборът се размина; после
   * делителят беше сменен на законовите 8 часа вместо договорените → паднаха и четирите часови
   * пера.
   */
  @Test
  @DisplayName("⚠️ ЗЛАТЕН Т8 · март 2026 · пет пера, ОБЩО 2364,74 € до стотинка")
  void goldenT8AddsUpToTheCent() {
    BgEarningsFacts facts =
        t8().nightHours(new BigDecimal("24"))
            .overtimeWorkdayHours(new BigDecimal("10"))
            .overtimeRestDayHours(new BigDecimal("8"))
            .holidayWorkHours(new BigDecimal("8"))
            .build();

    BgEarningsOutcome base =
        BgEarningsResolver.resolve(
            BgEarningsResolver.CALC_CONTRACT_BASE_SALARY,
            BgEarningsResolver.LINE_BASE_SALARY,
            facts);
    BgEarningsOutcome night =
        BgEarningsResolver.resolve(FIXED, BgEarningsResolver.LINE_NIGHT_WORK, facts);
    BgEarningsOutcome workday =
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_WORKDAY, facts);
    BgEarningsOutcome restDay =
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_REST_DAY, facts);
    BgEarningsOutcome holiday =
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_HOLIDAY_WORK, facts);

    assertAmount("1847.53", base, "основно възнаграждение (21/21)");
    assertAmount("22.33", night, "нощен труд, 24 ч × 0,9303 €");
    assertAmount("164.96", workday, "извънреден работен ден, 10 ч × 150 %");
    assertAmount("153.96", restDay, "извънреден почивен ден, 8 ч × 175 %");
    assertAmount("175.96", holiday, "работа на празник, 8 ч × 200 %");

    BigDecimal total =
        base.amount.add(night.amount).add(workday.amount).add(restDay.amount).add(holiday.amount);
    assertEquals(
        0,
        total.compareTo(new BigDecimal("2364.74")),
        "СБОРЪТ на петте пера е " + total.toPlainString() + ", а трябва да е 2364.74");
  }

  /**
   * ЧАСОВАТА СТАВКА КАТО ДОКУМЕНТАЦИЯ — 10,9972 €, на ЧЕТИРИ знака.
   *
   * <p>⚠️ Свидетелят е отделен, защото ставката НЕ участва в сметката (§ 5.3, едно деление накрая).
   * Тя се ПОКАЗВА до сумата и на два знака би станала 11,00 — умножена наум, тя би обяснявала друга
   * сума и читателят би заподозрял верния резултат.
   */
  @Test
  @DisplayName("часовата ставка се ПОКАЗВА на 4 знака — 10,9972 €, не 11,00")
  void theDocumentedHourlyRateKeepsFourDigits() {
    BigDecimal rate =
        BgEarningsResolver.resolve(
                UPLIFT,
                BgEarningsResolver.LINE_OVERTIME_WORKDAY,
                t8().overtimeWorkdayHours(BigDecimal.ONE).build())
            .rate;

    assertEquals(
        0, rate.compareTo(new BigDecimal("10.9972")), "часовата ставка е " + rate.toPlainString());
    assertEquals(4, rate.scale(), "ставката трябва да се показва на 4 знака, не на два");
  }

  // ── РЪБ (а) · ПРАВИЛОТО ТРИ НАДНИКА ─────────────────────────────────────

  /**
   * ⚠️ ПРАЗНИК С ИЗВЪНРЕДЕН ТРУД = ТРИ НАДНИКА, НЕ ЧЕТИРИ.
   *
   * <p>КТ чл. 264 дава ДВА надника за всеки празничен час — ОБЩО, а не като добавка. Чл. 262 ал. 1
   * т. 3 добавя ТРЕТИ, сметнат „върху трудовото възнаграждение, определено с трудовия договор“ (ал.
   * 2), а НЕ върху вече удвоения размер. Оттам {@code pl_praznik} носи 200 на сто, а {@code
   * pl_izv_praznik} — само 100. ВАС решения № 3346/2017 и № 10983/2020.
   *
   * <p>FAIL-FIRST: аргументът „самият час“ на празничния извънреден труд беше сменен от нула на
   * {@code THE_HOUR_ITSELF_PCT} → перото стана 175,96 вместо 87,98, тоест четири надника, и тестът
   * падна.
   */
  @Test
  @DisplayName("⚠️ ТРИ НАДНИКА · празник 200 % + извънреден 100 %, не 200 + 200")
  void holidayOvertimePaysThreeShiftsNotFour() {
    BgEarningsFacts facts =
        t8().holidayWorkHours(new BigDecimal("8"))
            .overtimeHolidayHours(new BigDecimal("8"))
            .build();

    BgEarningsOutcome holiday =
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_HOLIDAY_WORK, facts);
    BgEarningsOutcome overtime =
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_HOLIDAY, facts);

    assertAmount("175.96", holiday, "чл. 264 · удвоеният размер за 8 ч");
    assertAmount("87.98", overtime, "чл. 262 ал. 1 т. 3 · третият надник за 8 ч");

    // Един надник за 8 часа е 87,98 €. Двете пера дават 263,94 — тоест ТРИ надника, не четири.
    assertEquals(
        0,
        holiday.amount.add(overtime.amount).compareTo(new BigDecimal("263.94")),
        "празничният час не излиза три надника");
  }

  /**
   * ⚠️ ПАЗАЧЪТ: ИЗВЪНРЕДНИТЕ ПРАЗНИЧНИ ЧАСОВЕ СА ПОДМНОЖЕСТВО НА ПРАЗНИЧНИТЕ.
   *
   * <p>Двата броя идват от РАЗЛИЧНИ таблици — отчетът за време и допълнителните часове — и нищо в
   * модела не ги свързва. Повече извънредни, отколкото празнични изобщо, значи, че единият източник
   * лъже: или производственият календар не е разпознал деня, или редовете са класифицирани грешно.
   *
   * <p>⚠️ Отказват се И ДВЕТЕ пера, не само едното: противоречието обезсилва и двата надника.
   *
   * <p>FAIL-FIRST: пазачът беше махнат от {@code pl_izv_praznik} → перото сметна 87,98 върху
   * часове, за които чл. 264 плаща само 4, и тестът падна.
   */
  @Test
  @DisplayName("⚠️ ПАЗАЧ · повече извънредни празнични от празничните = ОТКАЗ на ДВЕТЕ пера")
  void moreHolidayOvertimeThanHolidayHoursRefusesBothLines() {
    BgEarningsFacts facts =
        t8().holidayWorkHours(new BigDecimal("4"))
            .overtimeHolidayHours(new BigDecimal("8"))
            .build();

    for (String code :
        new String[] {
          BgEarningsResolver.LINE_HOLIDAY_WORK, BgEarningsResolver.LINE_OVERTIME_HOLIDAY
        }) {
      BgEarningsOutcome out = BgEarningsResolver.resolve(UPLIFT, code, facts);
      assertEquals(BgEarningsOutcome.Status.REFUSED, out.status, code + ": " + out);
      assertNull(out.amount, code + " носи сума въпреки противоречието");
      assertTrue(out.detail.contains("ПОДМНОЖЕСТВО"), out.detail);
    }
  }

  // ── РЪБ (б) · НЕНОРМИРАН РАБОТЕН ДЕН ────────────────────────────────────

  /**
   * ⚠️ НЕНОРМИРАН РАБОТЕН ДЕН: ИЗВЪНРЕДНИЯТ В РАБОТЕН ДЕН НЕ СЕ ПЛАЩА, ОСТАНАЛИТЕ — ДА.
   *
   * <p>КТ чл. 263 ал. 1 дословно: „За извънреден труд, положен В РАБОТНИ ДНИ от работници и
   * служители с ненормиран работен ден, не се заплаща трудово възнаграждение.“ Ал. 2 ЗАПАЗВА
   * заплащането за дните на седмичната почивка и на официалните празници, в размерите по чл. 262
   * ал. 1 т. 2 и т. 3.
   *
   * <p>⚠️ Изходът е {@code NOT_ENTITLED}, а не пропускане: заварен ред от предишно {@code
   * prepare()} трябва да СЕ МАХНЕ, инак правилото важи само за новите фишове.
   *
   * <p>FAIL-FIRST: флагът беше подаден и на трите останали пера → почивният ден стана NOT_ENTITLED
   * и тестът падна на 153,96.
   */
  @Test
  @DisplayName("⚠️ чл. 263 · ненормиран ден маха САМО извънредния в работен ден")
  void theUnregulatedWorkDaySuppressesOnlyTheWorkdayOvertime() {
    BgEarningsFacts facts =
        t8().unregulatedWorkDay(true)
            .overtimeWorkdayHours(new BigDecimal("10"))
            .overtimeRestDayHours(new BigDecimal("8"))
            .overtimeSirvHours(new BigDecimal("6"))
            .build();

    BgEarningsOutcome workday =
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_WORKDAY, facts);
    assertEquals(BgEarningsOutcome.Status.NOT_ENTITLED, workday.status, workday.toString());
    assertNull(workday.amount);
    assertTrue(workday.detail.contains("чл. 263"), workday.detail);

    assertAmount(
        "153.96",
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_REST_DAY, facts),
        "чл. 263 ал. 2 ЗАПАЗВА почивния ден");
  }

  /** Петте пера с база „процентно увеличение върху часовата ставка“ — целият дисчпач. */
  private static final String[] UPLIFT_LINES = {
    BgEarningsResolver.LINE_OVERTIME_WORKDAY,
    BgEarningsResolver.LINE_OVERTIME_REST_DAY,
    BgEarningsResolver.LINE_OVERTIME_HOLIDAY,
    BgEarningsResolver.LINE_OVERTIME_SIRV,
    BgEarningsResolver.LINE_HOLIDAY_WORK,
  };

  /**
   * Т8 С НЕДОПУСТИМАТА ДВОЙКА И ЧАСОВЕ ОТ ВСИЧКИТЕ ПЕТ ВИДА.
   *
   * <p>Празничните часове са 8 общо и 4 извънредни — тоест извънредните СА подмножество и {@code
   * holidayHoursContradiction} НЕ се обажда. Инак отказът щеше да идва от чуждия пазач и свидетелят
   * щеше да е зелен, без да мери този.
   */
  private static BgEarningsFacts.Builder forbiddenPair() {
    return t8().workTimeMode(BgEarningsResolver.WORK_TIME_MODE_SIRV)
        .unregulatedWorkDay(true)
        .overtimeWorkdayHours(new BigDecimal("10"))
        .overtimeRestDayHours(new BigDecimal("8"))
        .overtimeSirvHours(new BigDecimal("6"))
        .holidayWorkHours(new BigDecimal("8"))
        .overtimeHolidayHours(new BigDecimal("4"));
  }

  /**
   * ⚠️ НЕНОРМИРАН ДЕН + СИРВ — НЕДОПУСТИМА КОНФИГУРАЦИЯ, ТОЕСТ ОТКАЗ, А НЕ СУМА И НЕ ТИХО ТРИЕНЕ.
   *
   * <p>КТ чл. 142 ал. 5 дословно: „Не се допуска сумирано изчисляване на работното време за
   * работниците и служителите с ненормиран работен ден.“ Тоест двойката е ЗАБРАНЕНА и въпросът
   * „плаща ли се извънредният труд по т. 4 при нея“ няма казус зад себе си — договорът е сгрешен.
   *
   * <p><b>⚠️ НЕ ЧЛ. 139а АЛ. 2.</b> Той забранява ненормиран ден при НАМАЛЕНО работно време;
   * другата посока на забраната е в ДРУГ член (чл. 142 ал. 5), и той затваря въпроса.
   *
   * <p><b>⚠️ ОТКАЗ ({@code REFUSED}) ЗА ВСИЧКИТЕ ЧЕТИРИ, И ЗАЩОТО ЕДНО ОТ ТЯХ ИНАК СЕ ТРИЕ.</b>
   * {@code NOT_ENTITLED} значи „правото го няма на това лице“ и е ВЕРНИЯТ фиш, тоест адресат няма —
   * но {@code BgEarningsService} МАХА заварения ред при този изход. Тоест при {@code
   * pl_izv_raboten} разликата не е в текста на съобщението: {@code NOT_ENTITLED} изтрива мълчаливо
   * перо за 164,96 € върху конфигурация, чиято предпоставка („лицето е ненормиран“) е точно
   * спорната. Тук фишът не е верен и сгрешен е ДОГОВОРЪТ — някой го поправя сега.
   *
   * <p>⚠️ И ДВЕТЕ ПРАЗНИЧНИ ПЕРА ЗАЕДНО. {@code pl_izv_praznik} носи само ТРЕТИЯ надник, защото
   * приема, че самият час е платен от {@code pl_praznik} по чл. 264. Откаже ли {@code pl_praznik} —
   * а той отказва, защото 200 на сто общо и 100 на сто допълване се различават по РЕЖИМА —
   * платеното самò {@code pl_izv_praznik} дава третия надник (43,99 €) върху часове без втори.
   *
   * <p>FAIL-FIRST: пазачът беше МАХНАТ от двете пера → {@code pl_izv_sirv} излезе {@code
   * COMPUTED[98.97]}, тоест надник по режим, който законът не допуска. Същата фикстура хваща и
   * другите две: {@code pl_izv_raboten} → {@code NOT_ENTITLED}, тоест изтрит ред, и {@code
   * pl_izv_praznik} → {@code COMPUTED[43.99]}.
   */
  @Test
  @DisplayName("⚠️ чл. 142 ал. 5 · четирите зависими пера ОТКАЗВАТ на глас, без тихо триене")
  void theForbiddenPairRefusesEveryModeDependentLine() {
    BgEarningsFacts facts = forbiddenPair().build();

    // ⚠️ СЪБИРА ВСИЧКИ РАЗМИНАВАНИЯ, А НЕ ПАДА НА ПЪРВОТО, и това не е педантичност: четирите пера
    // се гейтват от ЧЕТИРИ отделни клона в дисчпача. Паднал на първия, свидетелят щеше да мълчи за
    // останалите три и поправката им щеше да се прави на четири пробега.
    List<String> problems = new ArrayList<>();
    for (String code :
        new String[] {
          BgEarningsResolver.LINE_OVERTIME_WORKDAY,
          BgEarningsResolver.LINE_OVERTIME_SIRV,
          BgEarningsResolver.LINE_HOLIDAY_WORK,
          BgEarningsResolver.LINE_OVERTIME_HOLIDAY
        }) {
      BgEarningsOutcome out = BgEarningsResolver.resolve(UPLIFT, code, facts);
      if (out.status != BgEarningsOutcome.Status.REFUSED) {
        problems.add(code + " → " + out);
      } else if (out.amount != null || !out.detail.contains("чл. 142, ал. 5")) {
        problems.add(code + " отказва, но не по чл. 142 ал. 5: " + out);
      }
    }

    // ⚠️ НУЛА ТИХИ ИЗХОДИ ПО ЦЕЛИЯ ДИСЧПАЧ, а не само по четирите отгоре. NOT_ENTITLED е
    // единственият изход, при който BgEarningsService ТРИЕ заварен ред — тоест единственият, който
    // мени фиша, без никой да го е прочел. При недопустима двойка нито едно перо не бива да го
    // връща.
    for (String code : UPLIFT_LINES) {
      BgEarningsOutcome out = BgEarningsResolver.resolve(UPLIFT, code, facts);
      if (out.isNotEntitled()) {
        problems.add(code + " е NOT_ENTITLED, тоест заварен ред се ТРИЕ мълчаливо: " + out);
      }
    }

    assertTrue(
        problems.isEmpty(),
        "при ЗАБРАНЕНАТА двойка (КТ чл. 142 ал. 5) перата трябва да отказват НА ГЛАС:\n  "
            + String.join("\n  ", problems));
  }

  /**
   * ⚠️ ИЗВЪНРЕДНИЯТ В ПОЧИВЕН ДЕН НЕ СЕ ГЕЙТВА — и това е РЕШЕНИЕ, не пропуск в пазача.
   *
   * <p>Гейтва се перо, чиято СТОЙНОСТ зависи от спорния флаг. Това не зависи: сметката е 100 + 75
   * на сто и не чете нито {@code workTimeMode}, нито {@code unregulatedWorkDay}, а чл. 263 ал. 2
   * дава на ненормирания точно размера по чл. 262 ал. 1 т. 2 — същите 75 на сто, както на всеки
   * друг. Тоест при двата режима числото е ЕДНО И СЪЩО.
   *
   * <p>⚠️ Свидетелят мери именно РАВЕНСТВОТО на трите числа, а не само че перото е сметнато.
   * Отказан „за всеки случай“, той би спрял еднозначно изработен труд, за да съобщи дефект, който
   * другите четири пера вече съобщават — тоест отказът щеше да струва пари, без да добави знание.
   */
  @Test
  @DisplayName("⚠️ почивният ден е ЕДНАКЪВ при двата режима · остава да се смята, не се гейтва")
  void theRestDayOvertimeIsInvariantAndStaysComputed() {
    BgEarningsFacts forbidden = forbiddenPair().build();
    BgEarningsFacts sirvOnly =
        t8().workTimeMode(BgEarningsResolver.WORK_TIME_MODE_SIRV)
            .overtimeRestDayHours(new BigDecimal("8"))
            .build();
    BgEarningsFacts unregulatedOnly =
        t8().unregulatedWorkDay(true).overtimeRestDayHours(new BigDecimal("8")).build();

    for (BgEarningsFacts facts : new BgEarningsFacts[] {forbidden, sirvOnly, unregulatedOnly}) {
      assertAmount(
          "153.96",
          BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_REST_DAY, facts),
          "извънреден почивен ден, 8 ч × 175 % — числото не зависи от режима (чл. 263 ал. 2 дава"
              + " размера по чл. 262 ал. 1 т. 2)");
    }
  }

  /**
   * ⚠️ КОНТРОЛАТА: ЧИСТ СИРВ · ВСИЧКИТЕ ПЕТ ПЕРА СЕ СМЯТАТ, НЕПРОМЕНЕНО.
   *
   * <p>Без този свидетел пазач, написан твърде широко — например по режима вместо по ДВОЙКАТА — би
   * изглеждал точно толкова зелен, колкото верният, и щеше да спре всеки фиш при сумирано отчитане.
   * Числата са същите като в останалите свидетели и не са нови твърдения.
   */
  @Test
  @DisplayName("контрола · чист СИРВ (без ненормиран) · и петте пера се смятат")
  void pureSirvComputesEveryLine() {
    BgEarningsFacts facts = forbiddenPair().unregulatedWorkDay(false).build();

    for (String code : UPLIFT_LINES) {
      BgEarningsOutcome out = BgEarningsResolver.resolve(UPLIFT, code, facts);
      assertTrue(out.isComputed(), code + " не е сметнато при ДОПУСТИМ режим: " + out);
    }
    assertAmount(
        "164.96",
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_WORKDAY, facts),
        "извънреден работен ден, 10 ч × 150 %");
    assertAmount(
        "98.97",
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_SIRV, facts),
        "извънреден при СИРВ, 6 ч × 150 %");
    assertAmount(
        "87.98",
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_HOLIDAY_WORK, facts),
        "работа на празник при СИРВ · допълване до 200 %");
    assertAmount(
        "43.99",
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_HOLIDAY, facts),
        "третият надник по чл. 262 ал. 1 т. 3, 4 ч × 100 %");
  }

  /**
   * ⚠️ И ДВЕТЕ ПОЛОВИНИ ПООТДЕЛНО СА ДОПУСТИМИ — регресия срещу пазач, който мери отметката.
   *
   * <p>Чист СИРВ (чл. 142 ал. 2) и чист ненормиран ден (чл. 139а) са обичайните случаи, а
   * извънредният труд по т. 4 се плаща и в двата: чл. 263 ал. 1 отрича САМО извънредния труд „в
   * работни дни“, а превишението на нормата за отчетния период не е такъв — то се установява чак на
   * края на периода.
   *
   * <p>⚠️ Свидетелят стои, защото пазачът по чл. 142 ал. 5 чете ДВЕ полета. Написан върху едното,
   * той би отказвал всеки договор с ненормиран ден — и щеше да изглежда точно толкова зелен.
   */
  @Test
  @DisplayName("чист СИРВ и чист ненормиран ден · перото се ПЛАЩА както преди")
  void eitherHalfAloneStillPays() {
    BgEarningsFacts sirvOnly =
        t8().workTimeMode(BgEarningsResolver.WORK_TIME_MODE_SIRV)
            .overtimeSirvHours(new BigDecimal("6"))
            .build();
    BgEarningsFacts unregulatedOnly =
        t8().unregulatedWorkDay(true).overtimeSirvHours(new BigDecimal("6")).build();

    assertAmount(
        "98.97",
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_SIRV, sirvOnly),
        "чист СИРВ · извънреден по т. 4, 6 ч × 150 %");
    assertAmount(
        "98.97",
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_SIRV, unregulatedOnly),
        "чист ненормиран ден при подневно отчитане · чл. 263 ал. 1 отрича само работния ден");
  }

  // ── РЪБ (г) · ПРАЗНИК ПРИ СИРВ ──────────────────────────────────────────

  /**
   * ⚠️ ПРАЗНИК ПРИ СИРВ = 200 НА СТО ОБЩО, НЕ 300.
   *
   * <p>КТ чл. 264 иска удвоения размер и в двата режима. Различава се какво вече е платено: при
   * подневно отчитане празникът не е работен ден по производствения календар и основното
   * възнаграждение НЕ го плаща, тоест перото дава целите 200 на сто. При СИРВ часът вече е влязъл в
   * нормата за отчетния период и е платен веднъж — перото ДОПЪЛВА със 100.
   *
   * <p>FAIL-FIRST: клонът по режима беше махнат и перото връщаше 200 винаги → при СИРВ излезе
   * 175,96 вместо 87,98, тоест триста на сто за час, който нормата вече плаща.
   */
  @Test
  @DisplayName("⚠️ СИРВ · празничният час е 200 % ОБЩО — перото допълва със 100, не добавя 200")
  void underSirvTheHolidayLineTopsUpToTwoHundredPercent() {
    BgEarningsFacts byDay = t8().holidayWorkHours(new BigDecimal("8")).build();
    BgEarningsFacts sirv =
        t8().workTimeMode(BgEarningsResolver.WORK_TIME_MODE_SIRV)
            .holidayWorkHours(new BigDecimal("8"))
            .build();

    assertAmount(
        "175.96",
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_HOLIDAY_WORK, byDay),
        "подневно отчитане · целите 200 %");
    assertAmount(
        "87.98",
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_HOLIDAY_WORK, sirv),
        "СИРВ · допълване до 200 %");
  }

  /**
   * ⚠️ БЕЗ ОБЯВЕН РЕЖИМ ПЕРОТО ОТКАЗВА, А НЕ ГАДАЕ.
   *
   * <p>Разликата между двата клона е точно СТО НА СТО от празничния час. Прието наслуки, едното от
   * двете надплаща с цял надник — и то тихо, защото и двете числа изглеждат пресметнати.
   */
  @Test
  @DisplayName("⚠️ празник без обявен режим на отчитане = ОТКАЗ, не подразбиране")
  void aMissingWorkTimeModeRefusesTheHolidayLine() {
    BgEarningsOutcome out =
        BgEarningsResolver.resolve(
            UPLIFT,
            BgEarningsResolver.LINE_HOLIDAY_WORK,
            t8().workTimeMode(null).holidayWorkHours(new BigDecimal("8")).build());

    assertEquals(BgEarningsOutcome.Status.REFUSED, out.status, out.toString());
    assertTrue(out.detail.contains("Режим на отчитане"), out.detail);
  }

  // ── РЪБ (д) · УГОВОРЕНО НАД МИНИМУМА ────────────────────────────────────

  /**
   * ⚠️ УГОВОРЕНО НАД МИНИМУМА Е ОТКАЗ; УГОВОРЕНО ПОД НЕГО СЕ ВДИГА.
   *
   * <p>КТ чл. 262 ал. 1 дава МИНИМУМИ. Уговорка ПОД минимума е нищожна и се вдига — същото правило
   * като при {@code SENIORITY_MIN_PCT}. Уговорка НАД минимума обаче не може да се плати оттук:
   * {@code ExtraHoursType.increase} е поле на ВИДА допълнителни часове, не на договора на лицето, и
   * има таван 100. Сметнато по минимума, надуговореното е НЕДОПЛАТЕНО възнаграждение, което никой
   * отчет не показва.
   *
   * <p>FAIL-FIRST: {@code overtimeUpliftPct} беше пренаписан да връща минимума и при надуговорка →
   * перото сметна 164,96 вместо да откаже, и тестът падна.
   */
  @Test
  @DisplayName("⚠️ уговорени 60 % > законовите 50 % = ОТКАЗ; уговорени 40 % се ВДИГАТ до 50 %")
  void anAgreementAboveTheStatutoryMinimumIsRefusedAndBelowItIsRaised() {
    BgEarningsOutcome above =
        BgEarningsResolver.resolve(
            UPLIFT,
            BgEarningsResolver.LINE_OVERTIME_WORKDAY,
            t8().overtimeWorkdayHours(new BigDecimal("10"))
                .agreedOvertimeWorkdayPct(new BigDecimal("60"))
                .build());
    assertEquals(BgEarningsOutcome.Status.REFUSED, above.status, above.toString());
    assertNull(above.amount, "надуговорката беше сметната по минимума — тихо недоплащане");
    assertTrue(above.detail.contains("НАДХВЪРЛЯ"), above.detail);

    assertAmount(
        "164.96",
        BgEarningsResolver.resolve(
            UPLIFT,
            BgEarningsResolver.LINE_OVERTIME_WORKDAY,
            t8().overtimeWorkdayHours(new BigDecimal("10"))
                .agreedOvertimeWorkdayPct(new BigDecimal("40"))
                .build()),
        "уговорка под минимума трябва да се ВДИГНЕ до него");
  }

  // ── РЪБ (е) · НАУЧНА СТЕПЕН ─────────────────────────────────────────────

  /**
   * ⚠️ НАУЧНАТА СТЕПЕН НЕ СЕ ПРОРАТИРА — ПЪЛНА СУМА И ПРИ НУЛА ОТРАБОТЕНИ ДНИ.
   *
   * <p>НСОРЗ чл. 11 ал. 1 дава „допълнително МЕСЕЧНО възнаграждение в размер не по-малък от“ —
   * фиксирана сума на месец. Пропорцията „отработено/норма“ идва от чл. 12 ал. 8 и е написана САМО
   * за класа прослужено време; пренесена тук по аналогия, тя би отнела пари по правило, което не е
   * за това перо.
   *
   * <p>FAIL-FIRST: перото беше пренаписано да множи по {@code workedDays / workingDaysInPeriod} →
   * при нула отработени дни сумата стана 0,00 и тестът падна.
   */
  @Test
  @DisplayName("⚠️ чл. 11 · научната степен е ПЪЛНА и при нула отработени дни")
  void theAcademicDegreeIsNotProrated() {
    assertAmount(
        "7.67",
        BgEarningsResolver.resolve(
            MONTHLY,
            BgEarningsResolver.LINE_ACADEMIC_DEGREE,
            t8().workedDays(0).academicDegree(BgEarningsResolver.DEGREE_DOCTOR).build()),
        "„доктор“ при нула отработени дни");
    assertAmount(
        "25.56",
        BgEarningsResolver.resolve(
            MONTHLY,
            BgEarningsResolver.LINE_ACADEMIC_DEGREE,
            t8().workedDays(0)
                .academicDegree(BgEarningsResolver.DEGREE_DOCTOR_OF_SCIENCES)
                .build()),
        "„доктор на науките“ при нула отработени дни");
  }

  /**
   * НЕПОПЪЛНЕНАТА СТЕПЕН Е ЛИПСА, А НЕПОЗНАТАТА — ОТКАЗ, и двете не са нула.
   *
   * <p>Разликата е кой действа: празното поле чака ОПЕРАТОРА, непознатата стойност иска РЕШЕНИЕ за
   * нов размер (чл. 11 ал. 1 изброява точно две степени).
   */
  @Test
  @DisplayName("празна степен = липса; непозната степен = ОТКАЗ — нито едното не е 0,00")
  void anEmptyDegreeIsMissingAndAnUnknownDegreeIsRefused() {
    BgEarningsOutcome empty =
        BgEarningsResolver.resolve(MONTHLY, BgEarningsResolver.LINE_ACADEMIC_DEGREE, t8().build());
    assertEquals(BgEarningsOutcome.Status.SOURCE_MISSING, empty.status, empty.toString());

    BgEarningsOutcome unknown =
        BgEarningsResolver.resolve(
            MONTHLY,
            BgEarningsResolver.LINE_ACADEMIC_DEGREE,
            t8().academicDegree("MAGISTER").build());
    assertEquals(BgEarningsOutcome.Status.REFUSED, unknown.status, unknown.toString());
    assertTrue(unknown.detail.contains("MAGISTER"), unknown.detail);
  }

  // ── НОЩНИЯТ ТРУД И ДВАТА МУ МИНИМУМА ────────────────────────────────────

  /**
   * ⚠️ НСОРЗ ЧЛ. 8 НОСИ ДВА МИНИМУМА И ТЕ РАБОТЯТ ЗАЕДНО.
   *
   * <p>Дословно: „в размер не по-малък от 0,15 на сто от минималната работна заплата … НО НЕ
   * ПО-МАЛКО от 0,51 евро“. При МРЗ 620,20 € процентът дава 0,9303 € и подът НЕ хапе; при
   * хипотетична МРЗ 300 € процентът дава 0,45 € и подът хапе.
   *
   * <p><b>⚠️ ВТОРИЯТ СЛУЧАЙ Е ХИПОТЕТИЧЕН ДНЕС И ТОЧНО ЗАТОВА Е ТУК.</b> Правило, чието второ рамо
   * никога не се задейства при днешните числа, е неразличимо от липсващо — махнато, то щеше да мине
   * зелено през целия билд и да се обади чак в годината, в която подът се вдигне. А точно такава
   * промяна влезе с ДВ бр. 115 от 2025 г.
   */
  @Test
  @DisplayName("⚠️ чл. 8 · подът 0,51 €/ч хапе при ниска МРЗ, процентът — при днешната")
  void bothNightMinimumsAreLive() {
    assertAmount(
        "22.33",
        BgEarningsResolver.resolve(
            FIXED,
            BgEarningsResolver.LINE_NIGHT_WORK,
            t8().nightHours(new BigDecimal("24")).build()),
        "МРЗ 620,20 € · печели процентът (0,9303 €/ч)");
    assertAmount(
        "12.24",
        BgEarningsResolver.resolve(
            FIXED,
            BgEarningsResolver.LINE_NIGHT_WORK,
            t8().nightHours(new BigDecimal("24")).minWage(new BigDecimal("300.00")).build()),
        "МРЗ 300 € · печели подът (0,51 €/ч)");
  }

  /**
   * ⚠️ ДЕКЛАРИРАНАТА НУЛА СЛАГА РЕД 0,00; НЕДЕКЛАРИРАНОТО НЕ СЛАГА РЕД.
   *
   * <p>Това е разликата, заради която {@code BgDeclaredWorkHours} е отделен запис: самият РЕД е
   * декларацията. Има ред → нулата е ОТГОВОР и се вижда на фиша. Няма ред → никой не е питан и
   * перото се обявява за липсващо. Слети, недекларираният нощен труд щеше да изглежда като измерена
   * нула.
   *
   * <p>FAIL-FIRST: проверката беше сменена от {@code == null} на {@code signum() == 0} → и двата
   * случая станаха SOURCE_MISSING и тестът падна на декларираната нула.
   */
  @Test
  @DisplayName("⚠️ декларирана нула = ред 0,00; недекларирано = без ред")
  void aDeclaredZeroWritesALineButAnUndeclaredOneDoesNot() {
    assertAmount(
        "0.00",
        BgEarningsResolver.resolve(
            FIXED, BgEarningsResolver.LINE_NIGHT_WORK, t8().nightHours(BigDecimal.ZERO).build()),
        "декларирана нула нощни часа");

    BgEarningsOutcome undeclared =
        BgEarningsResolver.resolve(FIXED, BgEarningsResolver.LINE_NIGHT_WORK, t8().build());
    assertEquals(BgEarningsOutcome.Status.SOURCE_MISSING, undeclared.status, undeclared.toString());
    assertNull(undeclared.amount);
  }

  /** Разположението по чл. 10 — фиксирани 0,05 € на час, независимо от заплатата. */
  @Test
  @DisplayName("чл. 10 · разположение 40 ч × 0,05 € = 2,00 €")
  void onCallPaysTheFlatRate() {
    assertAmount(
        "2.00",
        BgEarningsResolver.resolve(
            FIXED, BgEarningsResolver.LINE_ON_CALL, t8().onCallHours(new BigDecimal("40")).build()),
        "време на разположение");
  }

  // ── БАЗАТА ПО ЧЛ. 7 ─────────────────────────────────────────────────────

  /**
   * ⚠️ БАЗАТА ПО ЧЛ. 7 Е ОСНОВНАТА ПЛЮС ВСИЧКИ ДТВ С ПОСТОЯНЕН ХАРАКТЕР.
   *
   * <p>НСОРЗ чл. 7: увеличението „се изчислява на базата на основната работна заплата И
   * ДОПЪЛНИТЕЛНИТЕ ТРУДОВИ ВЪЗНАГРАЖДЕНИЯ С ПОСТОЯНЕН ХАРАКТЕР на работника или служителя по
   * индивидуалния трудов договор“. Чл. 15 ал. 1 обявява чл. 11 и чл. 12 за такива.
   *
   * <p>Тук: 1847,53 + 10 години × 0,6 % (110,85) + „доктор“ (7,67) + постоянен бонус (100,00) =
   * 2066,05 €, тоест часова ставка 12,2979 € и 10 часа извънреден работен ден дават 184,47 €.
   *
   * <p>FAIL-FIRST: класът беше махнат от базата → сумата падна на 164,96 (както при Т8) и тестът се
   * обади; после постоянните ДТВ бяха махнати → 175,54.
   */
  @Test
  @DisplayName("⚠️ чл. 7 · базата носи класа, степента И постоянните ДТВ, НЕПРОРАТИРАНИ")
  void theArticleSevenBaseCarriesEveryPermanentAllowance() {
    BgEarningsFacts rich =
        t8().completedServiceYears(10)
            .academicDegree(BgEarningsResolver.DEGREE_DOCTOR)
            .permanentAllowances(new BigDecimal("100.00"))
            .overtimeWorkdayHours(new BigDecimal("10"))
            .build();

    assertAmount(
        "184.47",
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_WORKDAY, rich),
        "извънреден работен ден върху пълната база по чл. 7");
  }

  /**
   * ⚠️ БАЗАТА НЕ СЕ ПРОРАТИРА ПО ОТРАБОТЕНО, макар перото на класа да се проратира.
   *
   * <p>Чл. 12 ал. 8 пропорционира САМОТО ПЕРО („за действително отработено време“), а чл. 7 говори
   * за възнагражденията „по ИНДИВИДУАЛНИЯ ТРУДОВ ДОГОВОР“ — тоест за уговореното месечно. Взета от
   * пропорционираните пера, часовата ставка би падала точно в месеците с отсъствия и извънредният
   * труд щеше да се плаща по-евтино заради болест на лицето.
   *
   * <p>FAIL-FIRST: {@code permanentPayBase} беше пренаписан да множи класа по {@code worked/norm} →
   * сумата при половин отработен месец падна и тестът се обади.
   */
  @Test
  @DisplayName("⚠️ чл. 7 · половин отработен месец НЕ намалява часовата ставка")
  void theArticleSevenBaseIsNotProratedByWorkedDays() {
    BgEarningsFacts full =
        t8().completedServiceYears(10).overtimeWorkdayHours(new BigDecimal("10")).build();
    BgEarningsFacts half =
        t8().completedServiceYears(10)
            .workedDays(10)
            .overtimeWorkdayHours(new BigDecimal("10"))
            .build();

    BgEarningsOutcome fullMonth =
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_WORKDAY, full);
    BgEarningsOutcome halfMonth =
        BgEarningsResolver.resolve(UPLIFT, BgEarningsResolver.LINE_OVERTIME_WORKDAY, half);

    assertEquals(
        0,
        fullMonth.amount.compareTo(halfMonth.amount),
        "часовата ставка падна заради неотработени дни: "
            + fullMonth.amount
            + " срещу "
            + halfMonth.amount);
  }

  // ── ДЕЛИТЕЛЯТ ───────────────────────────────────────────────────────────

  /**
   * ⚠️ ДЕЛИТЕЛЯТ Е ДОГОВОРЕНОТО РАБОТНО ВРЕМЕ, НЕ ЗАКОНОВИТЕ ОСЕМ ЧАСА.
   *
   * <p>Лице на половин щат по КТ чл. 138 работи 4 часа на ден; часовата му ставка е ДВОЙНА спрямо
   * сметнатата по осем. Закован осмичен делител би занижил НАПОЛОВИНА всяка негова добавка —
   * правдоподобно и грешно, точно както при чл. 220.
   *
   * <p>И липсващото поле е ОТКАЗ, а не подразбиране осем: осем е вярно за повечето и грешно точно
   * за онези, заради които полето съществува.
   */
  @Test
  @DisplayName("⚠️ половин щат · 4 ч/ден дава ДВОЙНА часова ставка; празно поле е ОТКАЗ")
  void theDivisorIsTheContractedWorkingTime() {
    assertAmount(
        "329.92",
        BgEarningsResolver.resolve(
            UPLIFT,
            BgEarningsResolver.LINE_OVERTIME_WORKDAY,
            t8().contractHoursPerDay(new BigDecimal("4.00"))
                .overtimeWorkdayHours(new BigDecimal("10"))
                .build()),
        "половин щат · 10 ч извънреден труд");

    BgEarningsOutcome missing =
        BgEarningsResolver.resolve(
            UPLIFT,
            BgEarningsResolver.LINE_OVERTIME_WORKDAY,
            t8().contractHoursPerDay(null).overtimeWorkdayHours(new BigDecimal("10")).build());
    assertEquals(BgEarningsOutcome.Status.REFUSED, missing.status, missing.toString());
    assertTrue(missing.detail.contains("Договорени часове"), missing.detail);
  }
}
