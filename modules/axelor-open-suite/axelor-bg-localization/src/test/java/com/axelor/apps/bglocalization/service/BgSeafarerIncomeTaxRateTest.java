package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.testsupport.BgSeed;
import com.axelor.apps.hr.db.EmploymentContract;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ИЗБОРЪТ НА ДАНЪЧНА СТАВКА ПО ЧЛ. 42 АЛ. 4 ЗДДФЛ — 1 на сто за МОРСКО ЛИЦЕ, 10 на сто за всички
 * останали. Файловата половина: „вярно ли е РЕШЕНО и вярно ли е ЗАПИСАНО“. Без Postgres, без Guice,
 * без Hibernate.
 *
 * <p><b>Нормата.</b> Чл. 42 ал. 4: „месечната данъчна основа по ал. 3 се умножи по данъчна ставка
 * 10 на сто, а за доходите, придобити като морско лице — по данъчна ставка 1 на сто“. Тоест
 * ОСНОВАТА е една и съща и се смята по един и същи ред; сменя се САМО ставката. Затова тук се
 * проверява избор на КОД НА ПАРАМЕТЪР и нищо друго.
 *
 * <p><b>⚠️ ЧЛ. 25 АЛ. 3 Е ДРУГ МЕХАНИЗЪМ И Е ИЗВЪН ОБХВАТА.</b> Той прави ГОДИШНАТА данъчна основа
 * на морското лице 10 на сто от тази по ал. 1 — намалена ОСНОВА, а не намалена ставка. Фишът прави
 * месечното авансово удържане по чл. 42 и само него; годишното изравняване по чл. 49 няма нищо общо
 * с този тест.
 *
 * <p><b>⚠️ FAIL-FIRST.</b> {@code INCOME_TAX_RATE_SEAFARER} (1 на сто) е в seed-а и е закован в
 * манифестните тестове, но това не доказва, че производството го чете: {@code
 * BgPaySlipService#incomeTaxRate}, който взима само {@code INCOME_TAX_RATE}, би обложил морското
 * лице с 10 на сто и на заглавната сума, и на перото {@code pl_dod}. ТРИ от тукашните твърдения
 * падат срещу вариант, в който {@code incomeTaxRate} взима {@code INCOME_TAX_RATE} независимо от
 * вида осигурен: {@link #theSeafarerIsTaxedAtOnePercent}, {@link
 * #anExpiredInsuredTypeRowIsNotInForce} и {@link #aMissingSeafarerRateRefusesInsteadOfFallingBack},
 * и трите с „expected: &lt;INCOME_TAX_RATE_SEAFARER&gt; but was: &lt;INCOME_TAX_RATE&gt;“.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ, НЕ ПРАВИЛОТО.</b> Изборът е на {@code
 * BgPaySlipService#incomeTaxRate} и се вика ИСТИНСКИ. Подменени са двата шева към базата — редът на
 * вида осигурен и стойността на параметъра. И ДВАТА се пълнят ОТ SEED ФАЙЛОВЕТЕ: видовете осигурен
 * идват от {@code bg_insured_type.csv} с истинските си кодове, флагове и срокове, а ставките — от
 * {@code bg_payroll_parameter.csv}. Тоест нито 1,0000, нито „92“ са числа, измислени в теста: махне
 * ли някой флага от seed-а, тестът пада.
 *
 * <p><b>Базовата половина</b> — същият избор, но с редовете, ДОСТАВЕНИ в базата — е {@code
 * BgSeafarerIncomeTaxRateDbTest} и се пропуска с обявена причина, когато App Install не е пускан.
 * Слети в един тест, отговорът „няма база“ би маскирал отговора „изборът е сгрешен“.
 *
 * <p><b>⚠️ ЗАЩО ТУК НЯМА „МАРТ, НО МОРСКО ЛИЦЕ“ ЗЛАТЕН ФИШ.</b> Код 92 носи {@code bgZeroWorkTime},
 * {@code bgNoHours} и собствен осигурителен режим по чл. 4а КСО (избран доход, {@code bgFloorType =
 * MIN_INCOME_SOL}, изборът по ал. 2 за безработица). Фиш край до край би вплел цялото това
 * поведение в тест за ЕДНА ставка и първата промяна в осигуряването щеше да го изпраща червен по
 * повод, който няма нищо общо с чл. 42. Затова изборът и сумата се проверяват ПООТДЕЛНО — сумата
 * при 1 на сто е в {@code BgIncomeTaxGeneratorTest}.
 */
class BgSeafarerIncomeTaxRateTest {

  private static final String TYPES = "bg_insured_type.csv";
  private static final String PARAMETERS = "bg_payroll_parameter.csv";

  /** Кодът на морското лице по номенклатурата на НАП (Наредба Н-13, Приложение №1). */
  private static final String SEAFARER = "92";

  /** Работници или служители по чл. 4 ал. 1 т. 1 КСО — най-честият вид осигурен. */
  private static final String ORDINARY = "01";

  /**
   * ⚠️ КОД, ПРЕМАХНАТ ОТ НОМЕНКЛАТУРАТА (ДВ бр. 15/2024) — тоест неразрешим за 2026 г., но не
   * измислен. Точно такъв код може да стои по стар договор.
   */
  private static final String WITHDRAWN = "07";

  private static final LocalDate MARCH_FROM = LocalDate.of(2026, 3, 1);
  private static final LocalDate MARCH_TO = LocalDate.of(2026, 3, 31);

  // ═══ УСЛУГАТА С ПОДМЕНЕНИ ЧЕТЦИ ═══════════════════════════════════════════

  /**
   * ⚠️ Подменени са ДВАТА шева към базата и нищо друго — {@code incomeTaxRate} се вика истински.
   *
   * <p>Темпоралният филтър тук е ПРЕПИСАН в Java, защото {@code fetchOne()} иска Hibernate. Че
   * заявката в производството избира същия ред, се твърди от базовата половина — това е и цялата
   * причина двете половини да съществуват.
   */
  private static final class Service extends BgPaySlipService {

    final List<BgInsuredType> types = new ArrayList<>();
    final Map<String, BigDecimal> parameters = new LinkedHashMap<>();

    /** ⚠️ КОЙ КОД Е ПОИСКАН — така се вижда ИЗБОРЪТ, а не само числото, което е излязло. */
    String askedForCode;

    /** ⚠️ И С КОЯ ДАТА — така се вижда, че се пита за периода на фиша, а не за днес. */
    LocalDate askedForDate;

    @Override
    protected BgInsuredType insuredTypeFor(String insuredTypeCode, LocalDate date) {
      if (insuredTypeCode == null || insuredTypeCode.trim().isEmpty()) {
        return null;
      }
      for (BgInsuredType type : types) {
        if (insuredTypeCode.equals(type.getCode())
            && !date.isBefore(type.getValidFrom())
            && (type.getValidTo() == null || !date.isAfter(type.getValidTo()))) {
          return type;
        }
      }
      return null;
    }

    @Override
    protected BigDecimal payrollParameter(String code, LocalDate date) {
      askedForCode = code;
      askedForDate = date;
      return parameters.get(code);
    }
  }

  // ═══ ФИКСТУРИТЕ, ПРОЧЕТЕНИ ОТ SEED-А ══════════════════════════════════════

  /** Стойността на един параметър — ОТ ФАЙЛА, не число в теста. */
  private static BigDecimal rateFromSeed(String code) {
    List<String> header = BgSeed.quotedHeader(PARAMETERS);
    for (String[] row : BgSeed.quotedRows(PARAMETERS)) {
      if (code.equals(BgSeed.col(header, row, "code"))) {
        return new BigDecimal(BgSeed.col(header, row, "paramValue").trim());
      }
    }
    fail("параметър „" + code + "“ липсва в " + PARAMETERS);
    return null;
  }

  private static LocalDate date(String value) {
    return value == null || value.trim().isEmpty() ? null : LocalDate.parse(value.trim());
  }

  /**
   * ВСИЧКИТЕ 43 ВИДА ОСИГУРЕН — ОТ ФАЙЛА, с истинските си кодове, флагове и срокове.
   *
   * <p>⚠️ Всичките, не само 92 и 01: подаден списък само от двата кода щеше да мълчи точно за
   * въпроса „не е ли вдигнат флагът и другаде“, а зеленото щеше да значи „не бяха питани“.
   */
  private static List<BgInsuredType> insuredTypesFromSeed() {
    List<String> header = BgSeed.quotedHeader(TYPES);
    List<BgInsuredType> out = new ArrayList<>();
    for (String[] row : BgSeed.quotedRows(TYPES)) {
      BgInsuredType type = new BgInsuredType();
      type.setCode(BgSeed.col(header, row, "code"));
      type.setName(BgSeed.col(header, row, "name"));
      type.setBgIsSeafarer("true".equalsIgnoreCase(BgSeed.col(header, row, "bgIsSeafarer").trim()));
      type.setValidFrom(date(BgSeed.col(header, row, "validFrom")));
      type.setValidTo(date(BgSeed.col(header, row, "validTo")));
      out.add(type);
    }
    return out;
  }

  /** Услуга с видовете осигурен и двете ставки от seed-а. */
  private static Service serviceFromSeed() {
    Service service = new Service();
    service.types.addAll(insuredTypesFromSeed());
    service.parameters.put("INCOME_TAX_RATE", rateFromSeed("INCOME_TAX_RATE"));
    service.parameters.put("INCOME_TAX_RATE_SEAFARER", rateFromSeed("INCOME_TAX_RATE_SEAFARER"));
    return service;
  }

  /** Фиш за март 2026 г. по договор с този вид осигурен. */
  private static BgPaySlip slipFor(String insuredTypeCode) {
    return slipFor(insuredTypeCode, MARCH_FROM, MARCH_TO);
  }

  private static BgPaySlip slipFor(String insuredTypeCode, LocalDate from, LocalDate to) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period period = new Period();
    period.setName("03/PY2026");
    period.setFromDate(from);
    period.setToDate(to);
    slip.setPeriod(period);
    EmploymentContract contract = new EmploymentContract();
    contract.setBgInsuredTypeCode(insuredTypeCode);
    slip.setEmploymentContract(contract);
    return slip;
  }

  // ═══ 1 · ИЗБОРЪТ ══════════════════════════════════════════════════════════

  /**
   * ⚠️ СВИДЕТЕЛЯТ НА ЦЕЛИЯ КЛОН. {@code incomeTaxRate}, който не пита за вида осигурен, проваля
   * този тест с искан код {@code INCOME_TAX_RATE} — виж измерването в javadoc-а на класа.
   *
   * <p>⚠️ ПРОВЕРЯВА СЕ И ИЗБОРЪТ, НЕ САМО ЧИСЛОТО. Само 1,0000 би минало и при код, който случайно
   * чете верния параметър по друга причина; искания КОД различава решението от съвпадението.
   */
  @Test
  @DisplayName("⚠️ вид осигурен 92, март 2026 → 1,0000 от INCOME_TAX_RATE_SEAFARER (чл. 42 ал. 4)")
  void theSeafarerIsTaxedAtOnePercent() {
    Service service = serviceFromSeed();

    BigDecimal rate = service.incomeTaxRate(slipFor(SEAFARER));

    assertEquals(
        "INCOME_TAX_RATE_SEAFARER",
        service.askedForCode,
        "⚠️ ИЗБОРЪТ, не резултатът: морското лице трябва да поиска СВОЯ параметър");
    assertEquals(
        0,
        new BigDecimal("1.0000").compareTo(rate),
        "⚠️ чл. 42 ал. 4 ЗДДФЛ — 1 на сто за доходите, придобити като морско лице. Намерено: "
            + rate);
  }

  @Test
  @DisplayName("вид осигурен 01, март 2026 → 10,0000 от INCOME_TAX_RATE")
  void everyoneElseIsTaxedAtTenPercent() {
    Service service = serviceFromSeed();

    BigDecimal rate = service.incomeTaxRate(slipFor(ORDINARY));

    assertEquals("INCOME_TAX_RATE", service.askedForCode);
    assertEquals(
        0,
        new BigDecimal("10.0000").compareTo(rate),
        "⚠️ общата ставка по чл. 42 ал. 4 остава непокътната. Намерено: " + rate);
  }

  /**
   * ⚠️ ДАТАТА Е НА ПЕРИОДА, НЕ ДНЕШНАТА. Взета днешна, коригиращ фиш за минал месец щеше да се
   * облага по ДНЕШНАТА ставка и по ДНЕШНАТА редакция на номенклатурата.
   */
  @Test
  @DisplayName("⚠️ пита се за НАЧАЛОТО НА ПЕРИОДА (01.03.2026), не за днешната дата")
  void theRateIsAskedForThePeriodDate() {
    Service service = serviceFromSeed();

    service.incomeTaxRate(slipFor(SEAFARER));

    assertEquals(MARCH_FROM, service.askedForDate);
  }

  /**
   * ⚠️ ВИДЪТ ОСИГУРЕН СЕ ВЗИМА ВАЛИДЕН ЗА ДАТАТА. Номенклатурата е темпорална — ДВ бр. 15/2024
   * премахна кодове 07, 21, 23 и 93 — и изтекъл ред НЕ бива да носи ставка на следващ период.
   */
  @Test
  @DisplayName("⚠️ изтекъл ред за 92 не се чете: период СЛЕД validTo пада на общите 10 на сто")
  void anExpiredInsuredTypeRowIsNotInForce() {
    Service service = serviceFromSeed();
    for (BgInsuredType type : service.types) {
      if (SEAFARER.equals(type.getCode())) {
        type.setValidTo(LocalDate.of(2026, 3, 31));
      }
    }

    BigDecimal march = service.incomeTaxRate(slipFor(SEAFARER));
    String askedInMarch = service.askedForCode;

    BigDecimal may =
        service.incomeTaxRate(
            slipFor(SEAFARER, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31)));

    assertEquals("INCOME_TAX_RATE_SEAFARER", askedInMarch, "в март редът още е в сила");
    assertEquals(0, new BigDecimal("1.0000").compareTo(march));
    assertEquals("INCOME_TAX_RATE", service.askedForCode, "в май редът вече не е в сила");
    assertEquals(0, new BigDecimal("10.0000").compareTo(may));
  }

  // ═══ 2 · ЛИПСВАЩИЯТ ВХОД ══════════════════════════════════════════════════

  /**
   * ⚠️ ЛИПСВАЩАТА СТАВКА ЗА МОРСКО ЛИЦЕ Е ОТКАЗ, НЕ ТИХИ 10 НА СТО.
   *
   * <p>Замяната с общата ставка е точно грешката, която не се вижда: фишът излиза сметнат, а от
   * морското лице са удържани 10 на сто вместо 1. {@code null} стига до {@code BgIncomeTaxService},
   * който ОТКАЗВА с назована причина — фишът остава непотвърдим и липсата се вижда.
   */
  @Test
  @DisplayName("⚠️ липсващ INCOME_TAX_RATE_SEAFARER за периода → null (ОТКАЗ), НЕ 10 на сто")
  void aMissingSeafarerRateRefusesInsteadOfFallingBack() {
    Service service = serviceFromSeed();
    service.parameters.remove("INCOME_TAX_RATE_SEAFARER");

    BigDecimal rate = service.incomeTaxRate(slipFor(SEAFARER));

    assertEquals(
        "INCOME_TAX_RATE_SEAFARER",
        service.askedForCode,
        "⚠️ второ питане за общата ставка би било мълчалива замяна");
    assertNull(rate, "⚠️ 10,0000 тук значи данък, удържан по чужда ставка. Намерено: " + rate);
  }

  /**
   * ⚠️ НЕОТБЕЛЯЗАНОТО НЕ Е МОРСКО ЛИЦЕ — същата конвенция като {@code
   * BgInsurableIncomeService#floorCodeOf}: непознатият вид не носи признак и не се пази втори път
   * тук. Отказът за непопълнен вид осигурен вече си има дом и назована причина в {@code
   * BgPayrollInputBuilder.REFUSALS}.
   *
   * <p>Посоката на подразбирането е ВЯРНАТА: намалената ставка е изключение, което се ОБЯВЯВА.
   * Обратното — неизвестен вид да пада на 1 на сто — би удържало една десета от дължимия данък.
   */
  @Test
  @DisplayName("⚠️ празен, липсващ и премахнат вид осигурен → общите 10 на сто, не 1")
  void anUnresolvedInsuredTypeIsNotASeafarer() {
    for (String code : new String[] {null, "", "   ", WITHDRAWN}) {
      Service service = serviceFromSeed();

      BigDecimal rate = service.incomeTaxRate(slipFor(code));

      assertEquals("INCOME_TAX_RATE", service.askedForCode, "вид осигурен: " + code);
      assertEquals(
          0, new BigDecimal("10.0000").compareTo(rate), "вид осигурен: " + code + " → " + rate);
    }
  }

  /**
   * ⚠️ РЕДЪТ Е НАМЕРЕН, НО ФЛАГЪТ МУ Е {@code null} — ДРУГИЯТ КЛОН, НЕ ТОЗИ ОТ ГОРНИЯ ТЕСТ.
   *
   * <p>{@link #anUnresolvedInsuredTypeIsNotASeafarer} мери случая, в който ред НЕ СЕ НАМИРА ({@code
   * insuredType == null}). Тук редът СЕ НАМИРА и въпросът е за втората половина на същото условие:
   * {@code insuredType.getBgIsSeafarer()} върху поле, което никой не е попълвал. Двата клона се
   * пазят поотделно, защото се чупят поотделно.
   *
   * <p><b>Това НЕ е измислен случай — това е прозорецът след миграцията.</b> {@code
   * db/migration/2026-08-22-bg-insured-type-is-seafarer.sql} добавя колоната БЕЗ да я попълва,
   * тоест всичките 43 заварени реда влизат с {@code NULL} и Hibernate ги зарежда с непопълнено поле
   * — точно състоянието, което този тест конструира. Ставката трябва да остане общата:
   * неотбелязаното НЕ е морско лице.
   *
   * <p>⚠️ Работата я върши генерираният getter, който връща {@code FALSE} и за {@code NULL} —
   * затова тестът го проверява ИЗРИЧНО. Написан с {@code Boolean} поле, четено направо, същият ред
   * щеше да хвърли {@code NullPointerException} при разопаковането, а не да върне общата ставка.
   */
  @Test
  @DisplayName("⚠️ НАМЕРЕН ред с непопълнен флаг (NULL колона) → общите 10 на сто, не 1")
  void anInsuredTypeRowWithAnUnsetFlagIsNotASeafarer() {
    Service service = serviceFromSeed();
    // ⚠️ Seed-ът се маха, за да е ЕДНОЗНАЧЕН редът, който резолверът ще намери: файлът носи изричен
    // false за код 01 и оставен, той щеше да бъде намерен пръв — тоест тестът щеше да мери пак
    // попълнен флаг, само че с друга буква.
    service.types.clear();
    BgInsuredType unset = new BgInsuredType();
    unset.setCode(ORDINARY);
    unset.setName("Ред, зареден от колона с NULL");
    unset.setValidFrom(LocalDate.of(2024, 1, 1));
    // ⚠️ setBgIsSeafarer НЕ се вика НАРОЧНО — полето остава непопълнено, точно както Hibernate
    // зарежда ред, чиято колона е NULL.
    service.types.add(unset);

    assertNotNull(
        service.insuredTypeFor(ORDINARY, MARCH_FROM),
        "⚠️ КОТВАТА НА ТОЗИ ТЕСТ: редът трябва ДА СЕ НАМИРА, инак това е пак случаят на"
            + " anUnresolvedInsuredTypeIsNotASeafarer и мери друго");
    assertFalse(
        unset.getBgIsSeafarer(),
        "⚠️ генерираният getter връща FALSE и за NULL — оттам идва прочитът „не е морско лице“");

    BigDecimal rate = service.incomeTaxRate(slipFor(ORDINARY));

    assertEquals(
        "INCOME_TAX_RATE",
        service.askedForCode,
        "⚠️ непопълнен флаг НЕ бива да иска ставката за морско лице");
    assertEquals(
        0,
        new BigDecimal("10.0000").compareTo(rate),
        "⚠️ в прозореца след миграцията и преди App Install всеки ред е такъв — 1 на сто там би"
            + " обложил ЦЯЛАТА номенклатура по ставката на морското лице. Намерено: "
            + rate);
  }

  @Test
  @DisplayName("фиш без трудов договор → общите 10 на сто, без изключение")
  void aSlipWithoutAContractStillGetsTheGeneralRate() {
    Service service = serviceFromSeed();
    BgPaySlip slip = slipFor(ORDINARY);
    slip.setEmploymentContract(null);

    BigDecimal rate = service.incomeTaxRate(slip);

    assertEquals("INCOME_TAX_RATE", service.askedForCode);
    assertEquals(0, new BigDecimal("10.0000").compareTo(rate));
  }

  // ═══ 3 · SEED-ЪТ ══════════════════════════════════════════════════════════

  /**
   * ⚠️ ПРИЗНАКЪТ Е ДАННИ, И ДАННИТЕ СА ЕДНОЗНАЧНИ: ТОЧНО ЕДИН КОД Е ОТБЕЛЯЗАН.
   *
   * <p>Вторият вдигнат флаг би облагал цял вид осигурен с 1 на сто вместо 10 — а на фиша това
   * изглежда като нормално сметнат данък. Затова се проверява не „92 е отбелязан“, а „92 е
   * ЕДИНСТВЕНИЯТ отбелязан“.
   */
  @Test
  @DisplayName("⚠️ ФАЙЛ · точно ЕДИН вид осигурен носи bgIsSeafarer — код 92, „Морски лица“")
  void exactlyOneInsuredTypeCarriesTheFlag() {
    List<String> header = BgSeed.quotedHeader(TYPES);
    TreeSet<String> marked = new TreeSet<>();
    String name = null;
    for (String[] row : BgSeed.quotedRows(TYPES)) {
      if ("true".equalsIgnoreCase(BgSeed.col(header, row, "bgIsSeafarer").trim())) {
        marked.add(BgSeed.col(header, row, "code"));
        name = BgSeed.col(header, row, "name");
      }
    }

    assertEquals(
        new TreeSet<>(java.util.Collections.singletonList(SEAFARER)),
        marked,
        "⚠️ вдигнат флаг при друг код облага цял вид осигурен с 1 на сто вместо 10");
    assertEquals("Морски лица", name, "кодът и наименованието трябва да сочат едно и също лице");
  }

  /**
   * ⚠️ И ДВЕТЕ СТАВКИ СА В SEED-А — инак изборът има само един изход.
   *
   * <p>Кодовете са ДВА, а не две версии на един по {@code validFrom}: двете ставки важат
   * ЕДНОВРЕМЕННО и се избират по ЛИЦЕТО, не по датата.
   */
  @Test
  @DisplayName("ФАЙЛ · seed-ът носи и двете ставки: 10,0000 и 1,0000")
  void bothRatesExistInTheSeed() {
    assertEquals(0, new BigDecimal("10.0000").compareTo(rateFromSeed("INCOME_TAX_RATE")));
    assertEquals(0, new BigDecimal("1.0000").compareTo(rateFromSeed("INCOME_TAX_RATE_SEAFARER")));
  }
}
