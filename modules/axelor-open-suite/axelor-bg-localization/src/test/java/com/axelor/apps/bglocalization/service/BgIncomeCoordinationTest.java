package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.service.BgIncomeCoordinationResolver.Declaration;
import com.axelor.apps.bglocalization.service.BgIncomeCoordinationResolver.Outcome;
import com.axelor.apps.bglocalization.service.BgIncomeCoordinationResolver.Status;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ФАЙЛОВАТА половина на координацията по КСО чл. 6 ал. 11: САМО формулата, число по число.
 *
 * <p><b>Какво проверява.</b> Чистия калкулатор — {@link BgIncomeCoordinationResolver#coordinate} и
 * взаимодействието му с двата пода в {@link BgInsurableIncomeService#compute}. Нито едно от двете
 * не пипа база, Guice или Hibernate: границите се ПОДАВАТ като вход, точно както резолверите на
 * модула получават редовете си.
 *
 * <p><b>Базовата половина е {@code BgOtherPayerCoordinationGoldenTest}</b> — тя пуска целия фиш
 * през {@code prepare()} върху ставки, ПРОЧЕТЕНИ ОТ ДЕЙСТВИТЕЛНАТА база, и иска Postgres. Двете НЕ
 * се дублират: тук се пази аритметиката (пада, ако формулата се промени), там — веригата и seed-ът
 * (пада, ако ставка или таван в базата се разминат с очакваното).
 *
 * <p><b>⚠️ ОБХВАТ: САМО ПОДСЛУЧАЯТ „ВТОРИ РАБОТОДАТЕЛ (ДЕКЛАРИРАНО)“.</b> Двата договора при ЕДИН
 * работодател (изводимият случай, за който системата знае и двете страни) и морското лице по чл. 4а
 * ал. 6 НЕ се моделират — второто е ОТКАЗ с назована причина и това е заковано отдолу.
 */
class BgIncomeCoordinationTest {

  /**
   * ЗБДОО за 2026 г., чл. 9 т. 1 б. „в“ — максималният осигурителен доход за 01.01–31.07.2026 г.
   */
  private static final BigDecimal CAP = new BigDecimal("2111.6400");

  private static final LocalDate JUNE = LocalDate.of(2026, 6, 1);

  /**
   * МОД за длъжността. ⚠️ ПРЕДСТАВИТЕЛНО число, не снето от фиш. Ролята му е една: да е ПО-ВИСОК от
   * собствения доход в случая с МОД-пропуска, за да се види, че базата НЕ се вдига до него.
   */
  private static final BigDecimal MOD = new BigDecimal("1077.00");

  private static Outcome coordinated(String own, String declared) {
    return BgIncomeCoordinationResolver.coordinate(
        new BigDecimal(own),
        BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
        false,
        Declaration.ofSecondEmployer(new BigDecimal(declared)),
        CAP);
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ЗЛАТНИТЕ ЧЕТИРИ ЧИСЛА
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName(
      "⚠️ ЗЛАТЕН · лице А: собствен 1252,00 срещу изчерпан таван 2111,64 → база 0,00, не"
          + " отрицателна")
  void anExhaustedCeilingLeavesNothingToInsure() {
    Outcome out = coordinated("1252.00", "2111.6400");

    assertEquals(Status.COORDINATED, out.getStatus());
    assertEquals(
        0,
        new BigDecimal("0.00").compareTo(out.getBase()),
        "⚠️ MAX − деклариран = 0,00, тоест на това правоотношение не остава нищо за осигуряване."
            + " Намерено: "
            + out.getBase());
    // ⚠️ И ЗНАКЪТ Е ЧАСТ ОТ ТВЪРДЕНИЕТО. Отрицателна база би дала отрицателни вноски — перо, което
    // ДОБАВЯ пари към нетото, вместо да ги удържа.
    assertTrue(out.getBase().signum() >= 0, "база под нула не съществува");
    assertEquals(
        0,
        new BigDecimal("2111.6400").compareTo(out.getDeclared()),
        "декларираното пътува с резултата: база 0,00 без числото, което я е изяло, е неразличима от"
            + " база 0,00 при липса на начисления");
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · лице Б: собствен 398,36, деклариран 1500,00 → база 398,36, целият")
  void aSumBelowTheCeilingLeavesTheOwnIncomeWhole() {
    Outcome out = coordinated("398.36", "1500.00");

    assertEquals(Status.COORDINATED, out.getStatus());
    assertEquals(
        0,
        new BigDecimal("398.36").compareTo(out.getBase()),
        "⚠️ 1500,00 + 398,36 = 1898,36 е ПОД тавана, тоест нищо не се реже. Остатъкът (611,64) е"
            + " по-голям от собствения доход и min-ът избира него. Намерено: "
            + out.getBase());
  }

  @Test
  @DisplayName("⚠️ ЗЛАТЕН · частичен: собствен 500,00, деклариран 1900,00 → база 211,64")
  void thePartialCaseInsuresOnlyTheHeadroom() {
    Outcome out = coordinated("500.00", "1900.00");

    assertEquals(Status.COORDINATED, out.getStatus());
    assertEquals(
        0,
        new BigDecimal("211.64").compareTo(out.getBase()),
        "⚠️ 2111,64 − 1900,00 = 211,64, а собственият доход е 500,00 — реже се той, до остатъка."
            + " Намерено: "
            + out.getBase());
    // ⚠️ ГОРНИЦАТА 288,36 ОСТАВА НЕОСИГУРЕНА, И ТОВА НЕ Е ПРОПУСК. Таванът е на ЛИЦЕТО за месеца, а
    // не на всеки договор — сборът 1900,00 + 211,64 е точно 2111,64. Банда „само ЗО върху
    // горницата“
    // в много-ТД случая НЯМА: здравната вноска върви на СЪЩИЯ таван.
    assertEquals(
        0,
        new BigDecimal("2111.64").compareTo(new BigDecimal("1900.00").add(out.getBase())),
        "сборът на двете бази е ТОЧНО таванът — това е самото правило на ал. 11");
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // МОД ПРОПУСКЪТ — ДВЕТЕ ПОСОКИ НА ЕДИН И СЪЩ ВХОД
  // ═══════════════════════════════════════════════════════════════════════════

  /**
   * Базата през пълния калкулатор, тоест С двата пода — за да се види, че при координация те не
   * участват.
   */
  private static BgInsurableIncomeResult withFloors(String own, Declaration declared) {
    return BgInsurableIncomeService.compute(
        new BigDecimal(own),
        BgMinIncomeResult.resolved(MOD, "МОД-представителен", 1),
        null,
        true,
        CAP,
        JUNE,
        BgWorkTimeFacts.fullMonth(),
        BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
        false,
        declared);
  }

  @Test
  @DisplayName(
      "⚠️ ЗЛАТЕН · МОД ПРОПУСКЪТ: доход 300,00 под МОД, но деклариран 1000,00 → база 300,00, НЕ"
          + " вдигната до МОД")
  void anActiveCoordinationDropsTheMinimumIncomeFloor() {
    BgInsurableIncomeResult result =
        withFloors("300.00", Declaration.ofSecondEmployer(new BigDecimal("1000.00")));

    assertTrue(result.isResolved(), "координираната база е РАЗРЕШЕНА база, не отказ");
    assertTrue(result.isCoordinated(), "и се вижда, че е излязла по координирания път");
    assertEquals(
        0,
        new BigDecimal("300.00").compareTo(result.getAmount()),
        "⚠️ 1077,00 ТУК ЗНАЧИ, че МОД подът се е приложил ВТОРИ път: прагът е на ЛИЦЕТО за месеца, а"
            + " при другия платец вече е покрит. Приложен и тук, лице с два договора се осигурява"
            + " върху два МОД-а, а сборът пак изглежда под тавана. Намерено: "
            + result.getAmount());
  }

  @Test
  @DisplayName(
      "⚠️ ЗЛАТЕН · СЪЩИЯТ вход БЕЗ декларация → нормалният МОД под вдига базата до 1077,00")
  void withoutADeclarationTheMinimumIncomeFloorStillApplies() {
    BgInsurableIncomeResult result = withFloors("300.00", Declaration.absent());

    assertTrue(result.isResolved());
    assertTrue(
        !result.isCoordinated(),
        "⚠️ БЕЗ декларация НЕ Е координация с нула — двата пътя дават РАЗЛИЧНА база при доход под"
            + " МОД и точно затова са различни състояния");
    assertEquals(
        0,
        MOD.compareTo(result.getAmount()),
        "⚠️ 300,00 ТУК значи, че МОД подът е паднал за ВСЕКИ фиш, не само за координираните — тоест"
            + " новото правило е изяло старото. Намерено: "
            + result.getAmount());
  }

  @Test
  @DisplayName("⚠️ Декларирана НУЛА не е координация — тя е „другият платец не осигури нищо“")
  void aDeclaredZeroLeavesTheNormalPathAlone() {
    // ⚠️ Носителят е поле на ЗАПИС и генерираният getter за decimal връща ZERO за непопълнена
    // колона: „не съм пипал полето“ и „въведох 0,00“ са НЕРАЗЛИЧИМИ. Щом са неразличими, значат
    // едно — инак изборът между тях щеше да зависи от това дали редът е стар или нов.
    for (String zero : new String[] {"0", "0.00"}) {
      BgInsurableIncomeResult result =
          withFloors("300.00", Declaration.ofSecondEmployer(new BigDecimal(zero)));
      assertTrue(!result.isCoordinated(), "деклариран „" + zero + "“ НЕ активира координация");
      assertEquals(
          0,
          MOD.compareTo(result.getAmount()),
          "и МОД подът остава в сила — инак нулата би смъкнала базата тихо и надолу");
    }
    assertEquals(
        Status.NOT_DECLARED,
        BgIncomeCoordinationResolver.coordinate(
                new BigDecimal("300.00"),
                BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
                false,
                Declaration.absent(),
                CAP)
            .getStatus(),
        "и същото за отсъстваща декларация");
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ХИБРИДЪТ: СТОЯЩА СТОЙНОСТ НА ДОГОВОРА + МЕСЕЧЕН OVERRIDE НА ФИША
  // ═══════════════════════════════════════════════════════════════════════════

  /** {@code BgPaySlipService} само за разрешаването — то не пипа нито база, нито Guice. */
  private static final class Resolver extends BgPaySlipService {}

  private static BgPaySlip slipWith(String monthly) {
    BgPaySlip slip = new BgPaySlip();
    if (monthly != null) {
      slip.setBgOtherPayerInsurableIncome(new BigDecimal(monthly));
    }
    return slip;
  }

  private static EmploymentContract contractWith(String standing) {
    EmploymentContract contract = new EmploymentContract();
    if (standing != null) {
      contract.setBgOtherPayerInsurableIncome(new BigDecimal(standing));
    }
    return contract;
  }

  private static BigDecimal effective(String monthly, String standing) {
    return new Resolver().effectiveOtherPayerIncome(slipWith(monthly), contractWith(standing));
  }

  @Test
  @DisplayName("⚠️ ХИБРИД · месечната стойност на ФИША бие стоящата на договора")
  void theMonthlyValueOverridesTheStandingOne() {
    assertEquals(
        0,
        new BigDecimal("1500.00").compareTo(effective("1500.00", "2000.00")),
        "фишът е override за МЕСЕЦА — инак ТРЗ не може да поправи един месец");
    // ⚠️ И НАДОЛУ, не само надгоре: месечният override може да е ПО-МАЛЪК от стоящия.
    assertEquals(
        0,
        new BigDecimal("300.00").compareTo(effective("300.00", "2000.00")),
        "override-ът не е max(), а замяна");
  }

  @Test
  @DisplayName("⚠️ ХИБРИД · празен фиш ползва СТОЯЩАТА стойност на договора")
  void anEmptySlipFallsBackToTheContract() {
    for (String empty : new String[] {null, "0", "0.00"}) {
      assertEquals(
          0,
          new BigDecimal("2000.00").compareTo(effective(empty, "2000.00")),
          "⚠️ фиш „"
              + empty
              + "“ значи „ползвай договора“, НЕ „без координация“. Второто четене"
              + " връща двойния МОД праг на всеки месец, в който ТРЗ просто не е пипнал полето —"
              + " тихо, защото фишът изглежда нормален");
    }
  }

  @Test
  @DisplayName("⚠️ ХИБРИД · двете празни → ЛИПСА, тоест нормалният единичен път")
  void bothEmptyMeansNoDeclarationAtAll() {
    for (String empty : new String[] {null, "0", "0.00"}) {
      assertNull(effective(empty, empty), "нито месечна, нито стояща стойност значи ЛИПСА");
      assertNull(effective(empty, null), "и при изобщо непопълнен договор");
    }
    // ⚠️ И ЛИПСАТА СТИГА ДО ПРАВИЛОТО КАТО ЛИПСА, не като нула: контрактът носи две състояния,
    // не три, и точно това е обявеното ограничение на хибрида.
    assertTrue(
        !BgInsurableIncomeService.coordinationOf(effective(null, null)).isActive(),
        "липсата НЕ активира координация");
    assertTrue(
        BgInsurableIncomeService.coordinationOf(effective(null, "2000.00")).isActive(),
        "а стоящата стойност — да");
  }

  @Test
  @DisplayName("⚠️ ОБЯВЕНОТО ОГРАНИЧЕНИЕ на хибрида: един месец НЕ може да се занули от фиша")
  void aSingleMonthCannotBeZeroedFromTheSlip() {
    // ⚠️ ТОВА НЕ Е ДЕФЕКТ, А ЗАКОВАНО СЛЕДСТВИЕ от „>0 надписва“. Генерираният getter за decimal
    // връща ZERO за непопълнена колона, тоест „не съм пипал полето“ и „въведох 0,00“ са
    // НЕРАЗЛИЧИМИ на ЗАПИС — а щом са неразличими, не могат да значат различни неща.
    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(effective("0.00", "2000.00")),
        "⚠️ Ако това някога върне null, значи е добавен ТРЕТИ носител (булев признак „този месец"
            + " няма чужд доход“) — обявената бъдеща итерация. Тогава този тест се сменя ЗАЕДНО с"
            + " него, а не преди него. Днес зануляването е зануляване на полето на ДОГОВОРА:");
    assertNull(effective("0.00", null), "занулен договор → липса, тоест нормалният единичен път");
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ТРИТЕ ОТКАЗА — НАЗОВАНИ, НЕ ТИХИ
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ ОТКАЗ · морско лице с деклариран чужд доход → чл. 4а ал. 6, не сметка")
  void aSeafarerWithOtherSourcesIsRefusedByName() {
    Outcome out =
        BgIncomeCoordinationResolver.coordinate(
            new BigDecimal("1252.00"),
            BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
            true,
            Declaration.ofSecondEmployer(new BigDecimal("1500.00")),
            CAP);

    assertEquals(Status.SEAFARER_MULTI_SOURCE, out.getStatus());
    assertTrue(out.isBlocking(), "отказът БЛОКИРА — база няма");
    assertNull(out.getBase(), "и не се връща число, което да мине за сметнато");
    assertNotNull(out.getDetail());
    assertTrue(
        out.getDetail().contains("чл. 4а ал. 6"),
        "⚠️ Причината назовава НОРМАТА, а не „не се поддържа“: читателят трябва да знае КЪДЕ да"
            + " погледне. Намерено: "
            + out.getDetail());
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ · деклариран доход от ПО-НИСЪК приоритет — един скалар не изразява ред")
  void aDeclarationFromALowerPriorityIsRefusedByName() {
    // Това правоотношение е по чл. 4 ал. 1 (т. 1), а декларираното е за работа БЕЗ трудово
    // правоотношение (т. 4) — тоест то се изчерпва СЛЕД него. Формулата реже ТАЗИ база, а редът
    // иска обратното.
    Outcome out =
        BgIncomeCoordinationResolver.coordinate(
            new BigDecimal("1252.00"),
            BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
            false,
            Declaration.of(
                new BigDecimal("1500.00"), BgIncomeCoordinationResolver.LEVEL_NO_EMPLOYMENT),
            CAP);

    assertEquals(Status.DECLARED_LOWER_PRIORITY, out.getStatus());
    assertNull(out.getBase());
    assertTrue(
        out.getDetail().contains("чл. 6 ал. 11"),
        "причината назовава реда, който не може да се изрази: " + out.getDetail());
  }

  @Test
  @DisplayName("⚠️ РАВЕН и ПО-ВИСОК приоритет минават — точно тях моделира този клон")
  void equalAndHigherPrioritiesAreCoordinated() {
    // Равен: два трудови договора при различни работодатели — и двете страни на т. 1.
    assertEquals(
        Status.COORDINATED,
        BgIncomeCoordinationResolver.coordinate(
                new BigDecimal("500.00"),
                BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
                false,
                Declaration.of(
                    new BigDecimal("1900.00"), BgIncomeCoordinationResolver.LEVEL_ACTIVITY),
                CAP)
            .getStatus(),
        "равен приоритет — точно случаят „втори работодател“");
    // По-висок: това правоотношение е на т. 3 (самоосигуряващ се), декларираното — на т. 1, тоест
    // се изчерпва ПРЕДИ него и реже него.
    assertEquals(
        Status.COORDINATED,
        BgIncomeCoordinationResolver.coordinate(
                new BigDecimal("500.00"),
                BgIncomeCoordinationResolver.LEVEL_SELF_INSURED,
                false,
                Declaration.of(
                    new BigDecimal("1900.00"), BgIncomeCoordinationResolver.LEVEL_ACTIVITY),
                CAP)
            .getStatus(),
        "по-висок приоритет на декларираното — режещият е той, рязаният сме ние");
  }

  @Test
  @DisplayName("⚠️ ОТКАЗ · липсващ MAX за периода — при координация таванът Е самото правило")
  void aMissingCeilingIsRefusedByName() {
    Outcome out =
        BgIncomeCoordinationResolver.coordinate(
            new BigDecimal("1252.00"),
            BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
            false,
            Declaration.ofSecondEmployer(new BigDecimal("1500.00")),
            null);

    assertEquals(Status.MISSING_CAP, out.getStatus());
    assertNull(out.getBase());
    assertTrue(
        out.getDetail().contains(BgInsurableIncomeService.CODE_MAX_INSURABLE_INCOME),
        "причината назовава ПАРАМЕТЪРА, който липсва — тоест кой ред да се зареди: "
            + out.getDetail());
    // ⚠️ И през пълния калкулатор пак е отказ, тоест координацията не заобикаля пазача на тавана.
    BgInsurableIncomeResult full =
        BgInsurableIncomeService.compute(
            new BigDecimal("1252.00"),
            BgMinIncomeResult.resolved(MOD, "МОД-представителен", 1),
            null,
            true,
            null,
            JUNE,
            BgWorkTimeFacts.fullMonth(),
            BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
            false,
            Declaration.ofSecondEmployer(new BigDecimal("1500.00")));
    assertEquals(BgInsurableIncomeResult.Status.MISSING_CAP, full.getStatus());
  }

  // ═══════════════════════════════════════════════════════════════════════════
  // ГРАНИЦИТЕ НА ФОРМУЛАТА
  // ═══════════════════════════════════════════════════════════════════════════

  @Test
  @DisplayName("⚠️ Деклариран НАД тавана пак дава 0,00, а не отрицателен остатък")
  void aDeclarationAboveTheCeilingStillClampsToZero() {
    Outcome out = coordinated("1252.00", "2500.00");
    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(out.getBase()),
        "остатъкът е −388,36 и се реже до нула: " + out.getBase());
  }

  @Test
  @DisplayName("⚠️ Таванът влиза САМО през остатъка — единичен cap НЕ се прилага втори път")
  void theCeilingEntersOnlyThroughTheHeadroom() {
    // Собствен доход НАД тавана при малка декларация: базата е остатъкът, не таванът.
    Outcome out = coordinated("3000.00", "111.6400");
    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(out.getBase()),
        "⚠️ 2111,64 тук би значело, че единичният таван е приложен пак и остатъкът е бил подминат."
            + " Намерено: "
            + out.getBase());
  }

  @Test
  @DisplayName("⚠️ ЕДНО ЗАКРЪГЛЯНЕ, 2 знака, половина нагоре — § 5.3")
  void thereIsExactlyOneRoundingAndItIsAtTheEnd() {
    // Таванът в каталога е с ЧЕТИРИ знака (2111,6400). Остатъкът при декларация с два знака остава
    // с четири, и точно затова закръглянето е на изхода, а не на всяка стъпка.
    Outcome out = coordinated("2000.00", "0.005");
    assertEquals(2, out.getBase().scale(), "базата е пари: два знака, нито повече, нито по-малко");
    assertEquals(
        0,
        new BigDecimal("2000.00").compareTo(out.getBase()),
        "min-ът избира собствения доход — той вече е с два знака");
    // А когато остатъкът е този, който излиза, той носи знаците на тавана и се закръгля веднъж.
    Outcome cut = coordinated("2000.00", "1000.005");
    assertEquals(
        0,
        new BigDecimal("1111.64").compareTo(cut.getBase()),
        "2111,6400 − 1000,005 = 1111,6350 → 1111,64 (половина нагоре, веднъж). Намерено: "
            + cut.getBase());
  }

  @Test
  @DisplayName("⚠️ Липсата на начисления е нула, не липса на отговор")
  void aRelationshipWithNoEarningsCoordinatesToZero() {
    Outcome out =
        BgIncomeCoordinationResolver.coordinate(
            null,
            BgIncomeCoordinationResolver.LEVEL_ACTIVITY,
            false,
            Declaration.ofSecondEmployer(new BigDecimal("1500.00")),
            CAP);
    assertEquals(Status.COORDINATED, out.getStatus());
    assertEquals(0, BigDecimal.ZERO.compareTo(out.getBase()));
  }
}
