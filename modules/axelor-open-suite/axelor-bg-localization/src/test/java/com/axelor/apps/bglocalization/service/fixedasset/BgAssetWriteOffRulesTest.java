package com.axelor.apps.bglocalization.service.fixedasset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.account.db.FixedAsset;
import com.axelor.apps.account.db.FixedAssetLine;
import com.axelor.apps.account.db.repo.FixedAssetLineRepository;
import com.axelor.apps.account.db.repo.FixedAssetRepository;
import com.axelor.apps.bglocalization.db.BgAssetMaterialityIncrease;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Отписване по чл. 60 и чл. 66 ЗКПО.
 *
 * <p>Чисти функции, тъй че тестът е без Guice и без база — виж защо в {@link BgAssetWriteOffRules}.
 * {@code FixedAsset} и {@code FixedAssetLine} са обикновени bean-ове и се сглобяват на ръка.
 */
class BgAssetWriteOffRulesTest {

  // ---------------------------------------------------------------- чл. 60 ал. 2

  @Test
  @DisplayName("ДАП се реже в края на месеца ПРЕДИ месеца на счетоводното отписване")
  void дап_се_реже_месец_по_рано() {
    // Чл. 60 ал. 2: отписване „в началото на месеца, през който се отписва за счетоводни цели“ —
    // тоест последният данъчен ред е този на предходния месец, каквато и да е датата в месеца.
    assertEquals(
        LocalDate.of(2028, 5, 31),
        BgAssetWriteOffRules.taxWriteOffCutDate(LocalDate.of(2028, 6, 30)),
        "отписване на 30.06 → ДАП до 31.05");
    assertEquals(
        LocalDate.of(2028, 5, 31),
        BgAssetWriteOffRules.taxWriteOffCutDate(LocalDate.of(2028, 6, 1)),
        "и отписване на 1-ви дава същото: денят в месеца е без значение");
    assertEquals(
        LocalDate.of(2028, 5, 31),
        BgAssetWriteOffRules.taxWriteOffCutDate(LocalDate.of(2028, 6, 15)),
        "и по средата на месеца — ЗКПО не знае дневна пропорция");
  }

  @Test
  @DisplayName("рязането минава границата на годината и високосния февруари")
  void рязането_пази_краищата() {
    assertEquals(
        LocalDate.of(2027, 12, 31),
        BgAssetWriteOffRules.taxWriteOffCutDate(LocalDate.of(2028, 1, 10)),
        "януарско отписване хвърля ДАП в предходната ГОДИНА — там влиза и намалението по чл. 66 ал. 2");
    assertEquals(
        LocalDate.of(2028, 2, 29),
        BgAssetWriteOffRules.taxWriteOffCutDate(LocalDate.of(2028, 3, 15)),
        "2028 е високосна: краят на февруари е 29-и, не 28-и");
    assertEquals(
        LocalDate.of(2027, 2, 28),
        BgAssetWriteOffRules.taxWriteOffCutDate(LocalDate.of(2027, 3, 15)),
        "2027 не е високосна");
    assertNull(BgAssetWriteOffRules.taxWriteOffCutDate(null));
  }

  // ---------------------------------------------------------------- чл. 60 ал. 3 т. 1

  @Test
  @DisplayName("напълно амортизиран за счетоводни цели актив не се отписва от ДАП по ал. 2")
  void чл_60_ал_3_т_1() {
    // Изчерпан счетоводен план: последният ред е с нулева балансова стойност.
    FixedAsset изчерпан = asset("10000.00");
    изчерпан.setFixedAssetLineList(lines(line("2027-12-31", "0.00"), line("2027-11-30", "500.00")));
    assertTrue(
        BgAssetWriteOffRules.isFullyDepreciatedForAccounting(изчерпан),
        "чл. 60 ал. 3 т. 1: активът остава в ДАП и излиза по реда на ал. 1 (ал. 4)");

    FixedAsset тече = asset("10000.00");
    тече.setFixedAssetLineList(lines(line("2028-06-30", "2500.00")));
    assertFalse(BgAssetWriteOffRules.isFullyDepreciatedForAccounting(тече));

    // Актив без нито един ред НЕ е „напълно амортизиран“ — той още не е започнал. Генерираният
    // getter на BigDecimal връща ZERO за празна колона, тъй че двете биха се слели, ако СБС се
    // четеше от FixedAsset.accountingValue вместо от борда.
    FixedAsset нов = asset("10000.00");
    assertFalse(
        BgAssetWriteOffRules.isFullyDepreciatedForAccounting(нов),
        "борд без редове значи неамортизиран, не изцяло амортизиран");
    assertEquals(new BigDecimal("10000.00"), BgAssetWriteOffRules.accountingCarryingValue(нов));
  }

  @Test
  @DisplayName("планираните редове НЕ правят актива напълно амортизиран")
  void планираните_редове_не_се_броят() {
    // Машина с 29 реализирани месеца и още 11 планирани. Последният ПЛАНОВ ред е с нула, защото
    // там свършва планът — но активът струва 2 750 и е далеч от напълно амортизиран.
    FixedAsset тече = asset("10000.00");
    тече.setFixedAssetLineList(
        lines(
            line("2028-05-31", "2750.00"),
            planned("2028-06-30", "2500.00"),
            planned("2029-04-30", "0.00")));

    assertEquals(
        new BigDecimal("2750.00"),
        BgAssetWriteOffRules.accountingCarryingValue(тече),
        "СБС е стойността СЕГА, тоест по последния РЕАЛИЗИРАН ред — не по последния планов");
    assertFalse(
        BgAssetWriteOffRules.isFullyDepreciatedForAccounting(тече),
        "четен заедно с плановите редове, всеки актив изглежда напълно амортизиран още в деня на"
            + " завеждането си — и тогава чл. 60 ал. 3 т. 1 се прилага по погрешка, а данъчният"
            + " борд остава нерязан, тоест поправката по ал. 2 не се случва");
  }

  // ---------------------------------------------------------------- СБС и ДБС

  @Test
  @DisplayName("СБС идва от счетоводния борд, ДБС от данъчния — и двете от най-късния ред")
  void двете_стойности_идват_от_своя_борд() {
    FixedAsset asset = asset("10000.00");
    asset.setFixedAssetLineList(
        lines(
            line("2026-01-31", "9750.00"),
            line("2028-06-30", "2500.00"),
            line("2027-06-30", "5500.00")));
    asset.setFiscalFixedAssetLineList(
        lines(line("2026-01-31", "9750.00"), line("2028-05-31", "2750.00")));

    assertEquals(
        new BigDecimal("2500.00"),
        BgAssetWriteOffRules.accountingCarryingValue(asset),
        "най-късният ред на счетоводния борд, независимо от реда в списъка");
    assertEquals(
        new BigDecimal("2750.00"),
        BgAssetWriteOffRules.taxValue(asset),
        "данъчният борд е отрязан месец по-рано, тъй че ДБС е с една вноска ПОВЕЧЕ от СБС");
  }

  // ---------------------------------------------------------------- чл. 66

  @Test
  @DisplayName("чл. 66: увеличение със СБС, намаление с ДБС")
  void чл_66_нето() {
    assertEquals(
        new BigDecimal("-250.00"),
        BgAssetWriteOffRules.art66Net(
            BgArt66Exception.NONE, new BigDecimal("2500.00"), new BigDecimal("2750.00")),
        "ал. 1 увеличава с 2 500, ал. 2 намалява с 2 750 — нетно намаление 250, точно колкото е"
            + " месецът, който чл. 60 ал. 2 отнема от данъчната амортизация");
    assertEquals(
        new BigDecimal("0.00"),
        BgAssetWriteOffRules.art66Net(
            BgArt66Exception.NONE, new BigDecimal("2500.00"), new BigDecimal("2500.00")),
        "равни стойности се компенсират — така изглежда актив без временни разлики");
  }

  @Test
  @DisplayName("изключенията по ал. 3 т. 1–3 важат САМО когато ДБС превишава СБС")
  void изключенията_са_условни() {
    String[] условни = {
      BgArt66Exception.SHORTAGE, BgArt66Exception.OWN_CAPITAL, BgArt66Exception.ART60_AL6
    };
    for (String изключение : условни) {
      assertFalse(
          BgAssetWriteOffRules.art66Applies(
              изключение, new BigDecimal("1000.00"), new BigDecimal("1500.00")),
          изключение + ": ДБС 1 500 > СБС 1 000 → преобразуването НЕ се прилага");
      assertTrue(
          BgAssetWriteOffRules.art66Applies(
              изключение, new BigDecimal("1500.00"), new BigDecimal("1000.00")),
          изключение
              + ": ДБС 1 000 ≤ СБС 1 500 → преобразуването СЕ прилага. Условието стеснява"
              + " изключението до случаите, в които то е в полза на лицето; пропуснато, би отнело"
              + " и законното намаление по ал. 2");
      assertTrue(
          BgAssetWriteOffRules.art66Applies(
              изключение, new BigDecimal("1000.00"), new BigDecimal("1000.00")),
          изключение + ": равни стойности не са „превишава“");
      assertEquals(
          BigDecimal.ZERO,
          BgAssetWriteOffRules.art66Net(
              изключение, new BigDecimal("1000.00"), new BigDecimal("1500.00")),
          изключение + ": щом не се прилага, нетото е нула, а не отрицателно");
    }
  }

  @Test
  @DisplayName("преобразуването на дружества (ал. 3 т. 4) е БЕЗУСЛОВНО изключение")
  void преобразуването_е_безусловно() {
    assertFalse(
        BgAssetWriteOffRules.art66Applies(
            BgArt66Exception.REORGANISATION, new BigDecimal("1500.00"), new BigDecimal("1000.00")),
        "т. 4 е единствената точка без условие за съотношението ДБС/СБС — глава деветнадесета,"
            + " раздели II и III");
    assertFalse(
        BgAssetWriteOffRules.art66Applies(
            BgArt66Exception.REORGANISATION, new BigDecimal("1000.00"), new BigDecimal("1500.00")));
  }

  @Test
  @DisplayName("липсващо изключение значи, че преобразуването се прилага")
  void липсващото_изключение_не_отменя() {
    assertTrue(BgAssetWriteOffRules.art66Applies(null, BigDecimal.TEN, BigDecimal.ONE));
    assertTrue(
        BgAssetWriteOffRules.art66Applies(BgArt66Exception.NONE, BigDecimal.ONE, BigDecimal.TEN),
        "без изключение условието за ДБС/СБС е без значение — то е част от изключението, не от ал. 1/2");
  }

  // ---------------------------------------------------------------- еталонът от заданието

  @Test
  @DisplayName("еталон: машина 10 000 € кат. II 30%, продадена на 30.06.2028 → чл. 66 нето −250")
  void еталон_продажба_кат_2() {
    BigDecimal дас = new BigDecimal("10000.00");
    BigDecimal норма = new BigDecimal("30");
    LocalDate въвеждане = LocalDate.of(2026, 1, 31);
    LocalDate отписване = LocalDate.of(2028, 6, 30);

    // Месечната вноска излиза от нормата, не се пише на ръка — виж BgTaxDepreciation.
    BigDecimal периоди = BgTaxDepreciation.numberOfPeriods(норма, 1);
    BigDecimal вноска =
        дас.multiply(
                BigDecimal.ONE.divide(
                    периоди, BgTaxDepreciation.CALCULATION_SCALE, RoundingMode.HALF_UP))
            .setScale(2, RoundingMode.HALF_UP);
    assertEquals(new BigDecimal("250.00"), вноска, "10 000 при 30% годишно = 250 на месец");

    // Счетоводно: месецът на отписване носи пълна вноска, защото датата е краят на месеца и
    // базовият пропорционален клон се прескача (nextPlannedDate.equals(disposalDate)).
    int счетоводниМесеци = месеци(въвеждане, отписване);
    assertEquals(30, счетоводниМесеци, "01.2026 до 06.2028 включително");
    BigDecimal сбс = дас.subtract(вноска.multiply(BigDecimal.valueOf(счетоводниМесеци)));
    assertEquals(new BigDecimal("2500.00"), сбс);

    // Данъчно: чл. 60 ал. 2 реже в края на май → юни не носи амортизация.
    LocalDate отрязване = BgAssetWriteOffRules.taxWriteOffCutDate(отписване);
    int данъчниМесеци = месеци(въвеждане, отрязване);
    assertEquals(
        29, данъчниМесеци, "с един месец по-малко от счетоводните — това е цялата разлика");
    BigDecimal дбс = дас.subtract(вноска.multiply(BigDecimal.valueOf(данъчниМесеци)));
    assertEquals(new BigDecimal("2750.00"), дбс);

    assertEquals(
        new BigDecimal("-250.00"),
        BgAssetWriteOffRules.art66Net(BgArt66Exception.NONE, сбс, дбс),
        "+2 500 по ал. 1, −2 750 по ал. 2. Ако данъчният борд се реже като счетоводния — както го"
            + " прави базата — ДБС става 2 500, нетото 0 и разликата изчезва мълчаливо");

    // И контролът: статията за отписване се сверява по СБС и натрупаната амортизация.
    BigDecimal натрупана = вноска.multiply(BigDecimal.valueOf(счетоводниМесеци));
    assertEquals(new BigDecimal("7500.00"), натрупана, "Дт 241");
    assertEquals(
        дас,
        сбс.add(натрупана),
        "Дт 609 (СБС) + Дт 241 (натрупана) = Кт 20x (отчетна) — статията се сверява");
  }

  // ---------------------------------------------------------------- помощни

  /** Броят месеци от първата до последната амортизация, включително двата края. */
  private static int месеци(LocalDate от, LocalDate до) {
    return (до.getYear() - от.getYear()) * 12 + (до.getMonthValue() - от.getMonthValue()) + 1;
  }

  private static FixedAsset asset(String grossValue) {
    FixedAsset asset = new FixedAsset();
    asset.setGrossValue(new BigDecimal(grossValue));
    return asset;
  }

  /** Реализиран ред — само такъв се брои за остатъчна стойност. */
  private static FixedAssetLine line(String date, String accountingValue) {
    return line(date, accountingValue, FixedAssetLineRepository.STATUS_REALIZED);
  }

  /** Планиран ред — описва бъдещето и НЕ участва в остатъчната стойност. */
  private static FixedAssetLine planned(String date, String accountingValue) {
    return line(date, accountingValue, FixedAssetLineRepository.STATUS_PLANNED);
  }

  private static FixedAssetLine line(String date, String accountingValue, int status) {
    FixedAssetLine line = new FixedAssetLine();
    line.setDepreciationDate(LocalDate.parse(date));
    line.setAccountingValue(new BigDecimal(accountingValue));
    line.setStatusSelect(status);
    return line;
  }

  private static List<FixedAssetLine> lines(FixedAssetLine... items) {
    List<FixedAssetLine> list = new ArrayList<>();
    for (FixedAssetLine item : items) {
      list.add(item);
    }
    return list;
  }

  // ---------------------------------------------------------------- ЗДДС: чл. 79 / чл. 80

  @Test
  @DisplayName("десетте процента по чл. 80 ал. 2 т. 3 б. „г“ — строго ПО-НИСКА от 10%")
  void десетте_процента() {
    BigDecimal отчетна = new BigDecimal("10000.00");
    assertTrue(
        BgAssetWriteOffRules.isBelowTenPercentOfGross(new BigDecimal("999.99"), отчетна),
        "999,99 е под 1 000 — условието е изпълнено");
    assertFalse(
        BgAssetWriteOffRules.isBelowTenPercentOfGross(new BigDecimal("1000.00"), отчетна),
        "законът казва „по-ниска от 10 на сто“ — равното на тавана НЕ освобождава");
    assertFalse(
        BgAssetWriteOffRules.isBelowTenPercentOfGross(new BigDecimal("2500.00"), отчетна),
        "25% е далеч над границата");
    assertTrue(
        BgAssetWriteOffRules.isBelowTenPercentOfGross(BigDecimal.ZERO, отчетна),
        "напълно амортизиран счетоводно актив е под границата");
    assertFalse(
        BgAssetWriteOffRules.isBelowTenPercentOfGross(new BigDecimal("100.00"), BigDecimal.ZERO),
        "без отчетна стойност няма с какво да се сравни — не се предполага освобождаване");
  }

  @Test
  @DisplayName("обективното условие е само непреодолима сила или десетте процента")
  void обективното_условие() {
    BigDecimal отчетна = new BigDecimal("10000.00");
    assertTrue(
        BgAssetWriteOffRules.isObjectiveVatReleaseConditionMet(
            BgAssetRemoval.VatGround.ART80_2_1_A, new BigDecimal("2500.00"), отчетна),
        "непреодолимата сила идва от избраната причина, не от преценка — и важи независимо от СБС");
    assertTrue(
        BgAssetWriteOffRules.isObjectiveVatReleaseConditionMet(
            BgAssetRemoval.VatGround.ART80_2_3_G, new BigDecimal("500.00"), отчетна),
        "б. „г“ е чисто число и се проверява");
    assertFalse(
        BgAssetWriteOffRules.isObjectiveVatReleaseConditionMet(
            BgAssetRemoval.VatGround.ART80_2_3_V, new BigDecimal("2500.00"), отчетна),
        "б. „в“ (обективно неизползваем от обичайна употреба) е ПРЕЦЕНКА — този слой не я"
            + " предполага, колкото и износен да изглежда активът");
    assertFalse(
        BgAssetWriteOffRules.isObjectiveVatReleaseConditionMet(
            BgAssetRemoval.VatGround.ART80_2_3_A, new BigDecimal("2500.00"), отчетна),
        "технологичният брак в допустимите норми също е преценка срещу документация");
    assertFalse(
        BgAssetWriteOffRules.isObjectiveVatReleaseConditionMet(
            null, new BigDecimal("2500.00"), отчетна),
        "без обявено основание няма какво да се провери");
  }

  @Test
  @DisplayName("освобождаването иска обективно условие И доказано унищожаване И документ")
  void освобождаването_иска_и_трите() {
    assertTrue(BgAssetWriteOffRules.mayReleaseFromVatCorrection(true, true, true));
    assertFalse(
        BgAssetWriteOffRules.mayReleaseFromVatCorrection(false, true, true),
        "без обективно условие — не. Точно тук се спира активът с обичайно износване и СБС 25%:"
            + " б. „в“ иска преценка, която не се приема на доверие");
    assertFalse(
        BgAssetWriteOffRules.mayReleaseFromVatCorrection(true, false, true),
        "без обявено доказано унищожаване — не. Всяко освобождаване по ал. 2 виси под това условие"
            + " в отварящото изречение на точката");
    assertFalse(
        BgAssetWriteOffRules.mayReleaseFromVatCorrection(true, true, false),
        "без посочен документ — не. Отметка без документ е твърдение без опора");
    assertFalse(BgAssetWriteOffRules.mayReleaseFromVatCorrection(false, false, false));
  }

  @Test
  @DisplayName("еталон: износена машина със СБС 2 500 от 10 000 НЕ може да се освободи")
  void еталон_износена_машина_не_се_освобождава() {
    // Точно случаят, който заданието първоначално смяташе за освободен: брак поради износване.
    BigDecimal сбс = new BigDecimal("2500.00");
    BigDecimal отчетна = new BigDecimal("10000.00");
    boolean обективно =
        BgAssetWriteOffRules.isObjectiveVatReleaseConditionMet(
            BgAssetRemoval.VatGround.ART80_2_3_V, сбс, отчетна);
    assertFalse(обективно, "25% > 10%, а б. „в“ не е изчислима");
    assertFalse(
        BgAssetWriteOffRules.mayReleaseFromVatCorrection(обективно, true, true),
        "дори с отметнато доказано унищожаване и посочен протокол — обективното условие липсва,"
            + " тъй че корекцията по чл. 79 остава дължима");

    // Същата машина, но доамортизирана до 900: б. „г“ вече е изпълнена.
    boolean обективно2 =
        BgAssetWriteOffRules.isObjectiveVatReleaseConditionMet(
            BgAssetRemoval.VatGround.ART80_2_3_G, new BigDecimal("900.00"), отчетна);
    assertTrue(обективно2, "900 < 1 000 — б. „г“ е изпълнена");
    assertTrue(
        BgAssetWriteOffRules.mayReleaseFromVatCorrection(обективно2, true, true),
        "с изпълнено обективно условие плюс доказано унищожаване и документ освобождаването минава");
    assertFalse(
        BgAssetWriteOffRules.mayReleaseFromVatCorrection(обективно2, false, true),
        "но пак не и без обявеното доказване — б. „г“ също виси под отварящото изречение на т. 3");
  }

  @Test
  @DisplayName("новото основание по гл. 19а не отваря път за освобождаване от корекция")
  void обратното_начисляване_не_е_обективно_условие() {
    // Гардрейлът по чл. 79/80 остава непроменен и след добавянето на чл. 163а ал. 2. Двете
    // основания отговарят на различни въпроси: ART80_* е изключение от корекция, ART163A_2 е режим
    // на начисляване при доставка. Ако някой ден ART163A_2 попадне в белия списък на обективните
    // условия, „не подлежи на корекция“ ще стане достъпно с един избор от падащото меню — точно
    // скритият данъчен пасив, срещу който е построен пазачът.
    BigDecimal отчетна = new BigDecimal("10000.00");
    boolean обективно =
        BgAssetWriteOffRules.isObjectiveVatReleaseConditionMet(
            BgAssetRemoval.VatGround.ART163A_2, new BigDecimal("2500.00"), отчетна);
    assertFalse(
        обективно,
        "чл. 163а ал. 2 не е изключение по чл. 80 ал. 2 — той описва КОЙ начислява данъка при"
            + " доставка, а не че корекция не се дължи");
    assertFalse(
        BgAssetWriteOffRules.mayReleaseFromVatCorrection(обективно, true, true),
        "дори с отметнато доказано унищожаване и посочен документ — обективно условие няма");

    // Двете проверими условия си остават точно две.
    assertTrue(
        BgAssetWriteOffRules.isObjectiveVatReleaseConditionMet(
            BgAssetRemoval.VatGround.ART80_2_1_A, new BigDecimal("9999.00"), отчетна),
        "непреодолимата сила остава изчислима от избраното основание, независимо от стойността");
    assertFalse(
        BgAssetWriteOffRules.isObjectiveVatReleaseConditionMet(
            BgAssetRemoval.VatGround.ART80_2_3_A, new BigDecimal("2500.00"), отчетна),
        "технологичният брак в допустимите норми пак не е изчислим");
  }

  @Test
  @DisplayName("чл. 60 ал. 3 — И ДВЕТЕ точки държат данъчния борд нерязан")
  void чл_60_ал_3_има_две_точки() {
    // Дословно: „(3) Алинея 2 не се прилага при отписване на активи: 1. … които са напълно
    // амортизирани за счетоводни цели; 2. в резултат на увеличение в стойностния праг на
    // същественост." Проверка само на т. 1 отрязва данъчния борд на активите по т. 2 — тоест
    // прилага ал. 2 точно там, където законът я изключва.
    FixedAsset остатъчна = asset("10000.000");
    остатъчна.setFixedAssetLineList(new ArrayList<>(Arrays.asList(line("2028-06-30", "2500.000"))));
    assertFalse(
        BgAssetWriteOffRules.isArt60Al3Excluded(остатъчна),
        "актив със СБС 2 500 не е нито напълно амортизиран, нито засегнат от вдигнат праг");

    FixedAsset напълноАмортизиран = asset("10000.000");
    напълноАмортизиран.setFixedAssetLineList(
        new ArrayList<>(Arrays.asList(line("2028-06-30", "0.000"))));
    assertTrue(BgAssetWriteOffRules.isArt60Al3Excluded(напълноАмортизиран), "т. 1 — СБС е нула");

    // ⚠️ Т. 2 се разпознава по ВРЪЗКАТА КЪМ СЪБИТИЕТО, не по стойността на актива: тригерът в
    // закона е УВЕЛИЧЕНИЕ В ПРАГА, тоест събитие за цялото дружество. Актив, който винаги е бил под
    // прага, не попада тук — той никога не е влизал в данъчния амортизационен план.
    FixedAsset подВдигнатПраг = asset("800.000");
    подВдигнатПраг.setFixedAssetLineList(
        new ArrayList<>(Arrays.asList(line("2028-06-30", "600.000"))));
    assertFalse(
        BgAssetWriteOffRules.isArt60Al3Excluded(подВдигнатПраг),
        "малката стойност сама по себе си НЕ е основание — липсва събитието");

    подВдигнатПраг.setBgMaterialityIncrease(new BgAssetMaterialityIncrease());
    assertTrue(
        BgAssetWriteOffRules.isArt60Al3Excluded(подВдигнатПраг),
        "със записано вдигане на прага активът остава в ДАП и излиза по реда на ал. 1 (ал. 4)");
  }

  @Test
  @DisplayName("чл. 64 ал. 4: породеният актив следва стария, но само ако НЕ е напълно амортизиран")
  void чл_64_ал_4_следване() {
    // Дословно: „Когато активът, във връзка с който са извършени последващите разходи, се отпише от
    // счетоводния амортизационен план, ПРЕДИ данъчният амортизируем актив по ал. 1 да е напълно
    // амортизиран, последният се отписва от данъчния амортизационен план при условията и по реда на
    // чл. 60." Условието е за ДАНЪЧНАТА изчерпаност на ПОРОДЕНИЯ актив; счетоводното отписване на
    // стария е тригерът, не условието.
    FixedAsset породен = asset("2000.000");
    породен.setDepreciationPlanSelect(FixedAssetRepository.DEPRECIATION_PLAN_FISCAL);
    породен.setFiscalFixedAssetLineList(
        new ArrayList<>(Arrays.asList(line("2029-06-30", "1200.000"))));
    assertFalse(BgAssetWriteOffRules.isFullyTaxDepreciated(породен), "ДБС 1 200 — планът още тече");
    assertTrue(
        BgAssetWriteOffRules.requiresArt64Cascade(породен),
        "следва стария и излиза от ДАП по реда на чл. 60");

    FixedAsset изчерпан = asset("2000.000");
    изчерпан.setDepreciationPlanSelect(FixedAssetRepository.DEPRECIATION_PLAN_FISCAL);
    изчерпан.setFiscalFixedAssetLineList(
        new ArrayList<>(Arrays.asList(line("2029-06-30", "0.000"))));
    assertTrue(BgAssetWriteOffRules.isFullyTaxDepreciated(изчерпан));
    assertFalse(
        BgAssetWriteOffRules.requiresArt64Cascade(изчерпан),
        "⚠️ вече напълно амортизиран НЕ се пипа — ал. 4 казва „ПРЕДИ да е напълно амортизиран“, а"
            + " такъв излиза сам по чл. 60 ал. 1; второ отписване би рязало несъществуващи редове");

    FixedAsset извънДап = asset("300.000");
    извънДап.setDepreciationPlanSelect(FixedAssetRepository.DEPRECIATION_PLAN_NONE);
    assertFalse(
        BgAssetWriteOffRules.requiresArt64Cascade(извънДап),
        "актив под прага по чл. 50/51 никога не е влизал в ДАП — няма откъде да се отпише");

    assertFalse(BgAssetWriteOffRules.requiresArt64Cascade(null), "без породен актив няма следване");
  }

  @Test
  @DisplayName("чл. 64 ал. 4 реже по реда на чл. 60 ал. 2 — края на ПРЕДХОДНИЯ месец")
  void чл_64_ал_4_реже_по_чл_60() {
    // „При условията и по реда на чл. 60" значи ал. 2: активът се отписва от ДАП „в началото на
    // месеца, през който се отписва за счетоводни цели". Датата е същата, която реже и при
    // обикновено отписване — не втора аритметика.
    assertEquals(
        LocalDate.parse("2029-05-31"),
        BgAssetWriteOffRules.taxWriteOffCutDate(LocalDate.parse("2029-06-15")),
        "счетоводно отписване през юни → последният данъчен ред е 31 май");
  }
}
