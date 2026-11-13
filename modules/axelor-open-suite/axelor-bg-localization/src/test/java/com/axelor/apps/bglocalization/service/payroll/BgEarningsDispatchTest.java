package com.axelor.apps.bglocalization.service.payroll;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.testsupport.BgSeed;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⚠️ ИЗЧЕРПАТЕЛНОСТТА НА ДИСЧПАЧА — твърдение, не стил.
 *
 * <p><b>Какво точно се пази тук.</b> Че {@link BgEarningsResolver#resolve} дава ЯВЕН изход за всяка
 * от шестте стойности на {@code bgCalculationBase}, и че шестте наистина са шест — сверено срещу
 * речника {@code bg.payslip.calculation.base.select} в {@code Selects.xml} и срещу стойностите,
 * употребени в {@code bg_pay_slip_line_type.csv}.
 *
 * <p><b>Защо това е отделен тест, а не ред в друг.</b> Дисчпач, който прескача тихо, не се вижда от
 * тестовете на построеното: те проверяват двете пера, които РАБОТЯТ. Седма стойност, добавена в
 * изгледа без ред в резолвера, би минала през целия билд зелена и щеше да се обади чак на фиша,
 * който я носи — тоест пред оператор, не пред разработчик. Оттам и посоката на всички твърдения
 * тук: не „шестте работят“, а „НЯМА седма и нито една от шестте не мълчи“.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate. Всеки свидетел е чупен преднамерено.
 */
class BgEarningsDispatchTest {

  /** Пълни факти: с тях CONTRACT_BASE_SALARY СМЯТА, тоест мълчанието на останалите е тяхно. */
  /**
   * Пълен вход НА НАЕТ по трудов договор — правото на клас е налице и дисчпачът стига до сметката.
   */
  private static final BgEarningsFacts FACTS =
      BgEarningsFacts.builder()
          .baseSalary(new BigDecimal("2000.00"))
          .workedDays(21)
          .workingDaysInPeriod(21)
          .completedServiceYears(10)
          .seniorityRatePct(new BigDecimal("0.6000"))
          .entitledToSeniorityBonus(Boolean.TRUE)
          // ⚠️ БЕЗ НИТО ЕДИН ЧАС, И ТОВА Е НАРОЧНО. Този клас пита дали дисчпачът МЪЛЧИ, не колко
          // плаща. Часовете са {@code null}, тоест трите часови бази дават „източникът липсва“ по
          // СВОЯ си път — и точно това ги прави годни за свидетел на явния изход: те стигат до
          // края на клаузата си, а не се спират на липсваща ставка.
          .contractHoursPerDay(new BigDecimal("8.00"))
          .workTimeMode("1")
          .build();

  /** Шестте, както енджинът ги познава. Редът е този на речника в {@code Selects.xml}. */
  private static final List<String> SIX =
      Arrays.asList(
          BgEarningsResolver.CALC_CONTRACT_BASE_SALARY,
          BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT,
          BgEarningsResolver.CALC_HOURLY_FIXED,
          BgEarningsResolver.CALC_MONTHLY_FIXED,
          BgEarningsResolver.CALC_MANUAL_AMOUNT,
          BgEarningsResolver.CALC_DERIVED);

  // ── нито една стойност не мълчи ─────────────────────────────────────────

  /**
   * ⚠️ КОТВАТА НА ЦЕЛИЯ КЛАС: всяка от шестте стойности дава отговор, и никой отговор не е {@code
   * null}, празен статус или сума без обяснение.
   *
   * <p><b>⚠️ ТОЗИ СВИДЕТЕЛ Е ПОДЪТ, НЕ ЦЕЛИЯТ ЕТАЖ.</b> Той пази само че НИЩО не се връща като
   * {@code null}, без статус или без причина. КОЙ статус на кого се пада е работа на съседните три
   * теста, и това разделение е измерено, не предположено: махната клауза за {@code CALC_DERIVED}
   * прави стойността REFUSED вместо NOT_THIS_ENGINE, а ТОЗИ тест остава ЗЕЛЕН — отказът също има
   * статус и причина. Пада {@code manualAndDerivedAreNotAWaitingSource}, и точно затова той
   * съществува отделно.
   *
   * <p>FAIL-FIRST: {@code resolve} беше пренаписан да връща {@code null} за {@code
   * CALC_MANUAL_AMOUNT} → тестът падна с „дисчпачът върна null за „MANUAL_AMOUNT“ — тихо
   * прескачане“.
   */
  @Test
  @DisplayName("⚠️ ИЗЧЕРПАТЕЛНО · всяка от шестте стойности има ЯВЕН изход, нито една не мълчи")
  void everyCalculationBaseGetsAnExplicitOutcome() {
    for (String base : SIX) {
      BgEarningsOutcome out = BgEarningsResolver.resolve(base, "pl_probe", FACTS);

      assertNotNull(out, "дисчпачът върна null за „" + base + "“ — тихо прескачане");
      assertNotNull(out.status, "изход без статус за „" + base + "“");
      if (out.isComputed()) {
        assertNotNull(out.amount, "сметнат изход без сума за „" + base + "“");
      } else {
        assertNotNull(
            out.detail,
            "изход БЕЗ ПРИЧИНА за „"
                + base
                + "“ — точно толкова тих, колкото липсващият: читателят не научава какво да поправи");
        assertNull(out.amount, "несметнат изход НЕ бива да носи сума за „" + base + "“");
      }
    }
  }

  /**
   * ⚠️ НЕОТЧЕТЕНИЯТ ЧАС НЕ СЛАГА РЕД С 0: перо със сума 0,00 за нощен труд ТВЪРДИ, че лицето не е
   * работило нощем — а при липсваща декларация истината е, че никой не е питан.
   *
   * <p><b>⚠️ ЛИПСАТА Е ЗА ФАКТ, НЕ ЗА БАЗА.</b> Трите часови бази не отговарят с „източникът
   * липсва“ за КОЙТО И ДА Е код — липсата се произнася за КОНКРЕТНИЯ факт: няма декларация за нощен
   * труд, няма валидирани допълнителни часове, няма попълнена научна степен. Затова и свидетелят
   * минава по РЕАЛНИТЕ кодове, а не по „pl_probe“.
   *
   * <p>FAIL-FIRST: осемте клаузи бяха заменени с {@code computed(typeCode, ZERO, ZERO, ZERO)} →
   * статусът стана COMPUTED и {@code amount} 0.00; тестът падна и на осемте.
   */
  @Test
  @DisplayName("⚠️ неотчетеният час не слага ред с 0 — връща „източникът липсва“")
  void unmeasuredHoursYieldSourceMissingNotZero() {
    for (String[] pair :
        Arrays.asList(
            new String[] {
              BgEarningsResolver.CALC_MONTHLY_FIXED, BgEarningsResolver.LINE_ACADEMIC_DEGREE
            },
            new String[] {BgEarningsResolver.CALC_HOURLY_FIXED, BgEarningsResolver.LINE_NIGHT_WORK},
            new String[] {BgEarningsResolver.CALC_HOURLY_FIXED, BgEarningsResolver.LINE_ON_CALL},
            new String[] {
              BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT, BgEarningsResolver.LINE_OVERTIME_WORKDAY
            },
            new String[] {
              BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT, BgEarningsResolver.LINE_OVERTIME_REST_DAY
            },
            new String[] {
              BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT, BgEarningsResolver.LINE_OVERTIME_HOLIDAY
            },
            new String[] {
              BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT, BgEarningsResolver.LINE_OVERTIME_SIRV
            },
            new String[] {
              BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT, BgEarningsResolver.LINE_HOLIDAY_WORK
            })) {
      BgEarningsOutcome out = BgEarningsResolver.resolve(pair[0], pair[1], FACTS);

      assertEquals(
          BgEarningsOutcome.Status.SOURCE_MISSING,
          out.status,
          "„" + pair[1] + "“ трябва да казва че източникът липсва, а не да дава сума");
      assertNull(
          out.amount,
          "„" + pair[1] + "“ дава СУМА — нула тук е твърдение, което никой не е правил");
      assertTrue(out.detail.contains("източникът липсва"), out.detail);
    }
  }

  /**
   * ⚠️ ВСЯКО ПЕРО СОЧИ СВОЯ ИЗТОЧНИК, а не общо „часовете липсват“ — иначе причината е декор и
   * операторът не научава КОЯ врата да отвори. Трите източника са три различни врати: трудовият
   * договор (степента), декларацията за периода (нощен труд и разположение) и валидираният отчет за
   * допълнителни часове.
   *
   * <p>FAIL-FIRST: причината на нощния труд беше преписана върху разположението → тестът падна на
   * „време на разположение“.
   */
  @Test
  @DisplayName("липсата сочи СВОЯТА врата: договор, декларация или отчет за допълнителни часове")
  void eachWaitingLineNamesItsOwnMissingSource() {
    String degree =
        BgEarningsResolver.resolve(
                BgEarningsResolver.CALC_MONTHLY_FIXED,
                BgEarningsResolver.LINE_ACADEMIC_DEGREE,
                FACTS)
            .detail;
    assertTrue(degree.contains("научната степен"), degree);
    assertTrue(degree.contains("трудовия договор"), degree);

    String night =
        BgEarningsResolver.resolve(
                BgEarningsResolver.CALC_HOURLY_FIXED, BgEarningsResolver.LINE_NIGHT_WORK, FACTS)
            .detail;
    assertTrue(night.contains("нощен труд"), night);
    assertTrue(night.contains("BgDeclaredWorkHours"), night);

    String onCall =
        BgEarningsResolver.resolve(
                BgEarningsResolver.CALC_HOURLY_FIXED, BgEarningsResolver.LINE_ON_CALL, FACTS)
            .detail;
    assertTrue(onCall.contains("време на разположение"), onCall);

    String overtime =
        BgEarningsResolver.resolve(
                BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT,
                BgEarningsResolver.LINE_OVERTIME_WORKDAY,
                FACTS)
            .detail;
    assertTrue(overtime.contains("ExtraHoursType.bgExtraHoursKind"), overtime);

    String holiday =
        BgEarningsResolver.resolve(
                BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT,
                BgEarningsResolver.LINE_HOLIDAY_WORK,
                FACTS)
            .detail;
    assertTrue(holiday.contains("производственият календар"), holiday);
  }

  /**
   * ⚠️ НЕПОЗНАТ КОД С ПОЗНАТА БАЗА Е ОТКАЗ, А НЕ „ЧАКАЩ ИЗТОЧНИК“, и разликата е кой трябва да
   * действа. „Източникът липсва“ праща ОПЕРАТОРА да попълни данни; отказът за непознат код праща
   * РАЗРАБОТЧИКА да добави подформула. Слети, новият ред в речника щеше да чака данни, които никой
   * няма да въведе, защото перото им няма формула.
   *
   * <p>FAIL-FIRST: клаузата за непознат код при MONTHLY_FIXED беше махната → стойността падна до
   * последния отказ („непознат начин на изчисление“) и тестът падна на текста.
   */
  @Test
  @DisplayName("⚠️ познатa база + непознат код = ОТКАЗ към разработчика, не липса към оператора")
  void anUnknownCodeOnAKnownBaseIsARefusal() {
    for (String base :
        Arrays.asList(
            BgEarningsResolver.CALC_CONTRACT_BASE_SALARY,
            BgEarningsResolver.CALC_MONTHLY_FIXED,
            BgEarningsResolver.CALC_HOURLY_FIXED,
            BgEarningsResolver.CALC_HOURLY_RATE_UPLIFT)) {
      BgEarningsOutcome out = BgEarningsResolver.resolve(base, "pl_probe", FACTS);

      assertEquals(BgEarningsOutcome.Status.REFUSED, out.status, base + ": " + out);
      assertTrue(
          out.detail.contains("BgEarningsResolver.resolve"),
          "отказът не казва КЪДЕ се добавя подформулата: " + out.detail);
      assertTrue(out.detail.contains("pl_probe"), out.detail);
    }
  }

  /**
   * ⚠️ „ИЗТОЧНИКЪТ ЛИПСВА“ и „НЕ Е МОЯ РАБОТА“ НЕ СЕ СЛИВАТ. Първото значи „попълни и ела пак“,
   * второто — „никога“. Слети в едно състояние, ръчните пера щяха да изглядат като недовършена
   * работа, а недовършената работа — като нормално състояние.
   */
  @Test
  @DisplayName("ръчното и производното перо са NOT_THIS_ENGINE, не SOURCE_MISSING")
  void manualAndDerivedAreNotAWaitingSource() {
    BgEarningsOutcome manual =
        BgEarningsResolver.resolve(BgEarningsResolver.CALC_MANUAL_AMOUNT, "pl_probe", FACTS);
    assertEquals(BgEarningsOutcome.Status.NOT_THIS_ENGINE, manual.status);
    assertTrue(manual.detail.contains("на ръка"), manual.detail);

    BgEarningsOutcome derived =
        BgEarningsResolver.resolve(BgEarningsResolver.CALC_DERIVED, "pl_probe", FACTS);
    assertEquals(BgEarningsOutcome.Status.NOT_THIS_ENGINE, derived.status);
    assertTrue(derived.detail.contains("друга услуга"), derived.detail);
  }

  // ── непознатото се отказва, не се прескача ──────────────────────────────

  /**
   * FAIL-FIRST: последното {@code return} беше сменено на {@code notThisEngine(...)} → статусът
   * стана NOT_THIS_ENGINE и тестът падна. Разликата е точно тази, която този клас пази: „не е моя
   * работа“ е НОРМАЛНО състояние и никой не го поправя, а непозната стойност ТРЯБВА да се поправи.
   */
  @Test
  @DisplayName("⚠️ НЕПОЗНАТА стойност е ОТКАЗ с името си — не тихо прескачане")
  void anUnknownCalculationBaseIsRefused() {
    BgEarningsOutcome out = BgEarningsResolver.resolve("PERCENT_OF_GROSS", "pl_probe", FACTS);

    assertTrue(
        out.isRefusal(), "непозната база минава без отказ — фиш без перо, за което никой не знае");
    assertNull(out.amount);
    assertTrue(
        out.detail.contains("PERCENT_OF_GROSS"),
        "отказът трябва да НАЗОВЕ стойността: " + out.detail);
    assertTrue(out.detail.contains("pl_probe"), out.detail);
    assertTrue(out.detail.contains("bg_pay_slip_line_type.csv"), out.detail);
  }

  /** Празно и {@code null} са същият случай: тип перо без обявен начин на изчисление. */
  @Test
  @DisplayName("празен и липсващ bgCalculationBase също са ОТКАЗ")
  void blankAndNullAreRefusedToo() {
    assertTrue(BgEarningsResolver.resolve(null, "pl_probe", FACTS).isRefusal());
    assertTrue(BgEarningsResolver.resolve("", "pl_probe", FACTS).isRefusal());
    assertTrue(BgEarningsResolver.resolve("   ", "pl_probe", FACTS).isRefusal());
  }

  /**
   * ⚠️ CONTRACT_BASE_SALARY С ТРЕТИ КОД Е ОТКАЗ, не мълчание. Базата казва върху КАКВО се смята, не
   * КОЛКО: процентът и поводът са на самото перо.
   *
   * <p>FAIL-FIRST: този {@code return} беше сменен на {@code sourceMissing(...)} → статусът стана
   * SOURCE_MISSING и тестът падна. Разликата е кой поправя: „източникът липсва“ праща ОПЕРАТОРА да
   * попълни данни, а тук липсва ПОДФОРМУЛА и никакво попълване няма да я донесе.
   */
  @Test
  @DisplayName("⚠️ трети код с база „процент върху основната“ е ОТКАЗ — липсва подформула")
  void aThirdCodeOnTheContractBaseIsRefused() {
    BgEarningsOutcome out =
        BgEarningsResolver.resolve(
            BgEarningsResolver.CALC_CONTRACT_BASE_SALARY, "pl_nekakva_nova_dobavka", FACTS);

    assertTrue(out.isRefusal());
    assertNull(out.amount);
    assertTrue(out.detail.contains("pl_nekakva_nova_dobavka"), out.detail);
    assertTrue(out.detail.contains("подформула"), out.detail);
  }

  /** Двата познати кода СМЯТАТ — иначе горните отказите щяха да са верни по грешна причина. */
  @Test
  @DisplayName("двата построени кода минават през същия дисчпач и СМЯТАТ")
  void theTwoBuiltCodesCompute() {
    BgEarningsOutcome base =
        BgEarningsResolver.resolve(
            BgEarningsResolver.CALC_CONTRACT_BASE_SALARY,
            BgEarningsResolver.LINE_BASE_SALARY,
            FACTS);
    assertEquals(0, base.amount.compareTo(new BigDecimal("2000.00")));

    BgEarningsOutcome seniority =
        BgEarningsResolver.resolve(
            BgEarningsResolver.CALC_CONTRACT_BASE_SALARY, BgEarningsResolver.LINE_SENIORITY, FACTS);
    assertEquals(0, seniority.amount.compareTo(new BigDecimal("120.00")));
  }

  // ── шестте наистина ли са шест ──────────────────────────────────────────

  private static final Path SELECTS = Paths.get("src/main/resources/views/Selects.xml");

  /**
   * ⚠️ ЗАКОВАВА ДИСЧПАЧА КЪМ РЕЧНИКА. Седма стойност в {@code Selects.xml} без ред в резолвера се
   * обажда ТУК, при билда — не пред оператора, който е отворил фиш с нея.
   *
   * <p>FAIL-FIRST: в речника беше добавена {@code <option value="PERCENT_OF_GROSS">} → тестът падна
   * с „в речника, но НЕ в дисчпача: [PERCENT_OF_GROSS]“. И в обратната посока: {@code
   * CALC_MANUAL_AMOUNT} беше махнат от {@link #SIX} → падна със същото съобщение за MANUAL_AMOUNT,
   * а заедно с него и съседният тест срещу началните данни. ⚠️ Двете посоки дават ЕДНО съобщение,
   * защото {@link #SIX} Е определението за „дисчпача“: сравнението е между два набора, а не между
   * код и код.
   */
  @Test
  @DisplayName("⚠️ дисчпачът и bg.payslip.calculation.base.select са едно към едно")
  void theDispatchMatchesTheSelectionExactly() throws IOException {
    String xml = new String(Files.readAllBytes(SELECTS), StandardCharsets.UTF_8);
    int start = xml.indexOf("<selection name=\"bg.payslip.calculation.base.select\">");
    assertTrue(start > 0, "речникът bg.payslip.calculation.base.select липсва от " + SELECTS);
    int end = xml.indexOf("</selection>", start);

    Set<String> inSelection = new LinkedHashSet<>();
    Matcher m = Pattern.compile("<option value=\"([^\"]+)\"").matcher(xml.substring(start, end));
    while (m.find()) {
      inSelection.add(m.group(1));
    }

    Set<String> inDispatch = new LinkedHashSet<>(SIX);

    Set<String> onlySelection = new LinkedHashSet<>(inSelection);
    onlySelection.removeAll(inDispatch);
    assertTrue(
        onlySelection.isEmpty(),
        "в речника, но НЕ в дисчпача: "
            + onlySelection
            + " — тип перо с такава база ще получи отказ вместо сметка. Добави клауза в"
            + " BgEarningsResolver.resolve и константа до другите пет.");

    Set<String> onlyDispatch = new LinkedHashSet<>(inDispatch);
    onlyDispatch.removeAll(inSelection);
    assertTrue(
        onlyDispatch.isEmpty(),
        "в дисчпача, но НЕ в речника: "
            + onlyDispatch
            + " — мъртва клауза или преименувана стойност.");

    assertEquals(
        6,
        inSelection.size(),
        "шестте стойности станаха " + inSelection.size() + ": " + inSelection);
  }

  /**
   * И третата посока: стойностите, УПОТРЕБЕНИ в началните данни. Речникът може да съдържа стойност,
   * която никой ред не ползва; обратното — ред със стойност извън речника — е тихата повреда.
   *
   * <p>FAIL-FIRST: очакваният набор беше свит до пет стойности → тестът падна с шестата.
   */
  @Test
  @DisplayName("⚠️ всяка база, употребена в bg_pay_slip_line_type.csv, е позната на дисчпача")
  void everyBaseUsedInTheSeedIsKnown() {
    // ⚠️ ПО ИМЕ, НЕ ПО ИНДЕКС. Прочетена по индекс, колоната се мести с всяка нова колона в CSV-то
    // и тестът пада със стойностите на СЪСЕДНАТА колона — с диагноза, която сочи seed-а вместо
    // самия тест. BgSeed.col съществува точно за това.
    List<String> header = BgSeed.header("bg_pay_slip_line_type.csv");
    Set<String> used = new LinkedHashSet<>();
    for (String[] row : BgSeed.rows("bg_pay_slip_line_type.csv")) {
      String base = BgSeed.col(header, row, "bgCalculationBase");
      if (base != null && !base.trim().isEmpty()) {
        used.add(base.trim());
      }
    }

    assertTrue(
        !used.isEmpty(), "нито един ред не обявява начин на изчисление — колоната се е разместила");

    Set<String> unknown = new LinkedHashSet<>(used);
    unknown.removeAll(SIX);
    assertTrue(
        unknown.isEmpty(),
        "начални данни с база, непозната на дисчпача: "
            + unknown
            + " — всеки фиш с такъв ред ще носи отказ вместо перо.");
  }
}
