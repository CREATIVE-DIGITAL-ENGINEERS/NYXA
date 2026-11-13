package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD1FileAdapter;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD6FileAdapter;
import com.axelor.apps.bglocalization.service.vat.BgVatFileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * БАЙТ-ФОРМАТЪТ на {@code EMPL2021.TXT} и на файла за Д6 — 53 и 59 полета, знак по знак.
 *
 * <p><b>⚠️ 10.2026: РЕДЪТ Е СВЕРЕН И С ДВА ФАЙЛА, ПРИЕТИ ОТ НАП</b> — виж {@code
 * BgD1EtalonFileTest}. Оттам 53 полета (поле 54 само при каса), „0.00“ за празните суми, месец и
 * вид осигурен без водеща нула и т. 13 „0000“.
 *
 * <p><b>⚠️ КАКВО ТОЗИ ТЕСТ НЕ ДОКАЗВА, КАЗАНО ПРЕДИ ВСИЧКО ОСТАНАЛО.</b> Той сверява изхода срещу
 * ЗАПОВЕДТА по чл. 8 ал. 5, както е разчетена в {@code spravka-nap-fileformat-d1-d6-20260826.md}.
 * ДЕЙСТВИТЕЛНАТА валидация е ТЕСТОВО ПОДАВАНЕ в НАП — тя е ЧОВЕШКА стъпка и още не е правена. Зелен
 * тест тук значи „редът е такъв, каквато е разчетена заповедта“, НЕ „НАП го приема“. Разликата е
 * съществена и не бива да се замъглява: справката е препис, а препис може да е неточен.
 *
 * <p><b>ЗАЩО РЕДЪТ СЕ ЗАКОВАВА ЦЯЛ, а не поле по поле.</b> Форматът е РАЗДЕЛЕН със запетая:
 * липсващо или излишно поле мести всичко след себе си. Проверка на поле № 34 поотделно минава и
 * когато поле № 12 го няма — и точно това е грешката, която файлът прави неразбираем, вместо просто
 * неверен.
 */
class BgD1FileFormatTest {

  private static final BgD1FileAdapter ADAPTER = new BgD1FileAdapter();
  private static final BgD6FileAdapter D6_ADAPTER = new BgD6FileAdapter();

  /**
   * ЗАПИСЪТ, ЧИЙТО РЕД Е СВЕРЕН НА РЪКА. Числата са златните от Етапи 0–3: вид осигурен 01, III
   * категория, май 2026 — ставки 10,92/8,38 за ДОО, 4,80/3,20 за здравно, 2,80/2,20 за УПФ.
   */
  private static BgD1Record golden() {
    BgD1Record r = new BgD1Record();
    r.setT2Month("05");
    r.setT3Year("2026");
    r.setT4ObligedPersonCode("831919536");
    r.setT5PersonalId("7501014567");
    r.setT51PersonalIdCode("0");
    r.setT6Surname("Петров");
    r.setT7Initials("ИГ");
    r.setT12InsuredTypeCode("01");

    r.setT14Day1("12");
    r.setT15Day1("20");

    r.setT16Days("21");
    r.setT16LegalHours("8");
    r.setT16ContractedHours("8");
    r.setT161WorkedDays("16");
    r.setT162SickDays("2");
    r.setT163ChildCareDays("0");
    r.setT164NoContributionDays("0");
    r.setT165UnpaidLeaveDays("0");
    r.setT16aEmployerSickDays("3");

    r.setT121QualificationGroup("3");
    r.setT122ActivityCode("0111");
    r.setT123ActivityRow("42");

    // ⚠️ Т. 13 НЕ Е ПОПЪЛНЕНА НА ТОЗИ РЕД (вид осигурен 01), и поле 31 излиза „0000“: заповедта
    // казва „задължително се попълват 4 позиции“, а приетата от НАП коригираща Д1 за вид 01 носи
    // точно това. Слепването на двете позиции има свой свидетел
    // (BgD1EtapCTest#thePositionsAreGluedIntoFieldThirtyOne).

    r.setT21InsuranceIncome(new BigDecimal("2000.00"));
    r.setT22DooRateEmployer(new BigDecimal("10.92"));
    r.setT22DooRateInsured(new BigDecimal("8.38"));
    r.setT221HealthRateEmployer(new BigDecimal("4.80"));
    r.setT221HealthRateInsured(new BigDecimal("3.20"));
    r.setT222TzpbRate(new BigDecimal("0.47"));
    r.setT26UpfRateEmployer(new BigDecimal("2.80"));
    r.setT26UpfRateInsured(new BigDecimal("2.20"));
    r.setT29GrossWage(new BigDecimal("1700.00"));
    r.setT31TaxableIncome(new BigDecimal("2600.00"));
    r.setT31aTax(new BigDecimal("176.42"));

    // ⚠️ Т. 34 Е ВЪТРЕШНО СВЕРИМА СРЕЩУ САМИЯ ЗЛАТЕН РЕД, а не поставена наум: личните ставки на
    // реда са 8,38 (ДОО) + 3,20 (здравно) + 2,20 (УПФ) = 13,78 на сто върху т. 21, тоест 275,60
    // лични вноски. НПОПДОО чл. 32 ал. 1: 2000,00 − 275,60 − 176,42 = 1547,98, под тавана 2111,64.
    r.setT34NetPay(new BigDecimal("1547.98"));
    return r;
  }

  // ═══ РЕДЪТ, ЗАКОВАН ЦЯЛ ═══

  @Test
  @DisplayName("⚠️ РЕДЪТ НА EMPL2021.TXT · 53 полета, знак по знак, срещу ръчно сверен ред")
  void theWholeLineMatchesTheHandVerifiedOne() {
    String line = ADAPTER.toLine(golden(), "831919536");

    String expected =
        // 1–8 · месец, година, ЕИК, ЕГН, код за ЕГН, фамилия, инициали, вид осигурен
        "5,2026,\"831919536\",\"7501014567\",0,\"ПЕТРОВ\",\"ИГ\",1,"
            // 9–18 · петте двойки; втората до петата са празни → „0“
            + "12,20,0,0,0,0,0,0,0,0,"
            // 19 · т. 16 в ЧЕТИРИ позиции: 21 дни, 8 часа по закон, 8 по договор
            + "\"2188\","
            // 20–27 · кофите и часовете; часовете нямат носител → „0“
            + "16,2,0,0,0,3,0,0,"
            // 28–31 · квалификационна група, КИД, ред по МОД, код за СИРВ (не се полага за код 01)
            + "3,\"0111\",42,\"0000\","
            // 32–33 · т. 17 и т. 18 — този запис не носи болничен
            + "0.00,0.00,"
            // 34 · т. 21
            + "2000.00,"
            // 35–43 · ставките
            + "10.92,8.38,4.80,3.20,0.47,0.00,0.00,2.80,2.20,"
            // 44–45 · т. 27 и т. 28
            + "0.00,0.00,"
            // 46–47 · т. 29 и т. 30 (ГВРС е 0 по ЗБДОО 2026)
            + "1700.00,0.00,"
            // 48–50 · т. 31, т. 31а, т. 34 (нетото по НПОПДОО чл. 32)
            + "2600.00,176.42,1547.98,"
            // 51–53 · код осиг. каса, код корекция, флаг за източник (54 — само при каса 999)
            + "000,0,\"831919536\"";

    assertEquals(
        expected,
        line,
        "⚠️ Сравнението е на ЦЕЛИЯ ред нарочно: форматът е РАЗДЕЛЕН със запетая, тоест липсващо поле"
            + " мести всичко след себе си. Проверка на едно поле минава и когато друго преди него го"
            + " няма.");
    assertEquals(
        53,
        line.split(",", -1).length,
        "точно 53 полета — преброени, не предположени. ⚠️ split с limit −1, инак празните в края се"
            + " губят.");
  }

  // ═══ ПРАВИЛАТА, ВСЯКО СЪС СВОЙ СВИДЕТЕЛ ═══

  @Test
  @DisplayName("Character · в ДВОЙНИ КАВИЧКИ и с ГЛАВНИ кирилски букви")
  void characterFieldsAreQuotedAndUppercased() {
    assertEquals("\"ПЕТРОВ\"", ADAPTER.chr("Петров"));
    assertEquals(
        "\"\"",
        ADAPTER.chr(null),
        "празното Character поле е ДВЕ КАВИЧКИ, не празен низ: без тях полето изчезва и редът се"
            + " скъсява");
    assertEquals("\"\"", ADAPTER.chr("   "), "и само-интервали са празно");
  }

  @Test
  @DisplayName("⚠️ Character · ЗАПЕТАЯТА И КАВИЧКАТА СЕ МАХАТ, а не се екранират")
  void theSeparatorInsideAValueIsStrippedNotEscaped() {
    assertEquals(
        "\"ПЕТРОВ Ж-Р\"",
        ADAPTER.chr("Петров, ж-р\""),
        "⚠️ Форматът НЕ описва екраниране, а разделителят Е запетаята. „ПЕТРОВ, Ж-Р“ би добавило поле"
            + " и разместило всичките 20 след него. Махането губи ЗНАК от името; екранирането губи"
            + " ЦЕЛИЯ ред и всичко след него.\n"
            + "  ⚠️ ТИРЕТО ОБАЧЕ ОСТАВА, и това е нарочно. Правилото „само главни букви на кирилица“"
            + " е за РЕГИСТЪРА, не за пунктуацията: двойните фамилии („Иванова-Петрова“) са обичайни"
            + " и НАП ги приема. Махат се само знаците, които чупят РАЗБОРА — кавичка, запетая, CR,"
            + " LF. Разширен до „всичко, което не е буква“, филтърът щеше да прекръсти всяка втора"
            + " жена в регистъра.");
    assertTrue(
        !ADAPTER.chr("Петров, ж-р").contains(","),
        "нито една запетая не бива да остане вътре в стойност");
    assertEquals(
        "\"ПЕТРОВ\"", ADAPTER.chr("Петров\r\n"), "CR и LF също се махат — те са краят на РЕДА");
  }

  @Test
  @DisplayName("Numeric · без стойност → „0“, и БЕЗ кавички")
  void numericFieldsFallBackToZeroWithoutQuotes() {
    assertEquals("0", ADAPTER.num(null));
    assertEquals("0", ADAPTER.num(""));
    assertEquals("05", ADAPTER.num("05"), "водещата нула Е част от стойността и се запазва");
    assertTrue(
        !ADAPTER.num("05").contains("\""),
        "⚠️ Числово поле В КАВИЧКИ е НИЗ — НАП го отхвърля, а на екрана двете изглеждат почти"
            + " еднакво");
    assertEquals(
        "0.00", ADAPTER.sum(null), "празната сума е „0.00“ — както в приетите от НАП файлове");
    assertEquals("0.00", ADAPTER.sum(BigDecimal.ZERO), "и нулевата");
    assertEquals("0.00", ADAPTER.rate(null), "и празната ставка");
    assertEquals("2000.00", ADAPTER.sum(new BigDecimal("2000")), "сумата излиза с ДВА знака");
    assertEquals(
        "10.92", ADAPTER.rate(new BigDecimal("10.9200")), "ставката реже нулите ДО два знака");
    assertEquals(
        "2000.00",
        ADAPTER.sum(new BigDecimal("-2000.00")),
        "⚠️ „Всички суми са само положителни“. Отрицателно число е дефект по-нагоре, но във файла то"
            + " би счупило РАЗБОРА, не само сметката.");
  }

  @Test
  @DisplayName("⚠️ поле 52 · кодът корекция е ЦИФРА — редовни 0, „К“ 1, „З“ 8")
  void theCorrectionCodeIsADigitInTheFile() {
    BgD1Record regular = golden();
    assertEquals("0", ADAPTER.correctionCode(regular), "празната т. 1 → 0");

    BgD1Record correcting = golden();
    correcting.setT1CorrectionCode(BgD1RecordBuilder.CORRECTION_K);
    assertEquals("1", ADAPTER.correctionCode(correcting), "буква „К“ (U+041A) → 1");

    BgD1Record deletion = golden();
    deletion.setT1CorrectionCode(BgD1RecordBuilder.DELETION_Z);
    assertEquals("8", ADAPTER.correctionCode(deletion), "буква „З“ (U+0417) → 8");

    // ⚠️ И ПОЛЕТО НАИСТИНА СТИГА ДО РЕДА, а не само методът.
    String line = ADAPTER.toLine(deletion, null);
    assertEquals(
        "8",
        line.split(",", -1)[51],
        "поле 52 е с индекс 51. ⚠️ Записът пази БУКВАТА, защото наредбата казва „попълва се буква“ и"
            + " човекът вижда буква на хартиения образец; заповедта иска ЦИФРА. Слети в едно, или"
            + " записът лъже за наредбата, или файлът — за заповедта.");
  }

  @Test
  @DisplayName("⚠️ ЗАЛИЧАВАЩИЯТ РЕД НОСИ ВСИЧКИТЕ 53 ПОЛЕТА, а не девет")
  void aDeletionLineStillCarriesAllFiftyFourFields() {
    BgD1Record deletion = new BgD1Record();
    deletion.setT1CorrectionCode(BgD1RecordBuilder.DELETION_Z);
    deletion.setT2Month("05");
    deletion.setT3Year("2026");
    deletion.setT4ObligedPersonCode("831919536");
    deletion.setT5PersonalId("7501014567");
    deletion.setT51PersonalIdCode("0");
    deletion.setT6Surname("Петров");
    deletion.setT7Initials("ИГ");
    deletion.setT12InsuredTypeCode("01");

    String line = ADAPTER.toLine(deletion, null);
    assertEquals(
        53,
        line.split(",", -1).length,
        "⚠️ Указание т. 1 казва кои точки се ПОПЪЛВАТ (девет). Форматът е РАЗДЕЛЕН и иска всичките си"
            + " полета — ред с девет би бил отхвърлен ЦЕЛИЯТ, а изглежда „по указанието“.");
    assertEquals(
        "5,2026,\"831919536\",\"7501014567\",0,\"ПЕТРОВ\",\"ИГ\",1,"
            + "0,0,0,0,0,0,0,0,0,0,"
            + "\"\","
            + "0,0,0,0,0,0,0,0,"
            + "0,\"\",0,\"0000\","
            // 32–50 · деветнайсетте суми и ставки
            + "0.00,0.00,0.00,0.00,0.00,0.00,0.00,0.00,0.00,0.00,"
            + "0.00,0.00,0.00,0.00,0.00,0.00,0.00,0.00,0.00,"
            + "000,8,\"\"",
        line,
        "деветте носят стойност, останалите излизат като „0“, „0.00“ или празни кавички");
    assertEquals(
        "\"\"",
        line.split(",", -1)[18],
        "⚠️ И Т. 16 (поле 19) ОСТАВА ПРАЗНА, а не „0000“: заличаването твърди, че записът НЕ СЪЩЕСТВУВА, а"
            + " „0000“ е попълнена точка, която твърди нула дни в осигуряване — валидно число,"
            + " което НАП приема.");
  }

  @Test
  @DisplayName("поле 19 · четирите позиции се слепват, а празната т. 16 остава ПРАЗНА")
  void field19GluesFourPositionsButStaysEmptyWhenThePointIs() {
    BgD1Record r = golden();
    assertEquals("2188", ADAPTER.t16(r), "21 дни + 8 часа по закон + 8 по договор");

    // Код 90: т. 16 НЕ се попълва (заб. 3).
    BgD1Record code90 = golden();
    code90.setT16Days(null);
    code90.setT16LegalHours(null);
    code90.setT16ContractedHours(null);
    assertEquals(
        null,
        ADAPTER.t16(code90),
        "⚠️ „0000“ би било ПОПЪЛНЕНА точка. Празната остава празна и в реда излиза като \"\".");

    // Частично попълнена: дните ги има, часовете не.
    BgD1Record partial = golden();
    partial.setT16LegalHours(null);
    assertEquals(
        "2108",
        ADAPTER.t16(partial),
        "⚠️ ТУК празната позиция СТАВА „0“, и това е единственото такова място във файла: поле 19 е с"
            + " ФИКСИРАНА ширина 4 и позициите му НЯМАТ свой разделител. Празна позиция би скъсила"
            + " низа и следващото поле щеше да се прочете едно наляво.");
  }

  // ═══ БАЙТОВЕТЕ ═══

  @Test
  @DisplayName("⚠️ БАЙТОВЕ · CP1251, CRLF след ВСЕКИ ред, и CTRL+Z в края")
  void theBytesCarryTheEncodingTheLineEndingsAndTheEndOfFileMarker() throws IOException {
    BgD1FileService service =
        new BgD1FileService(new BgVatFileWriter(), ADAPTER, new BgD6FileAdapter());
    byte[] bytes = service.d1Bytes(Arrays.asList(golden(), golden()), "831919536");

    assertEquals(
        BgD1FileService.END_OF_FILE,
        bytes[bytes.length - 1],
        "маркерът за край на файл е CTRL+Z, CHR(26), 0x1A. ⚠️ Той се долепя ТУК, а НЕ в"
            + " BgVatFileWriter: ДДС файловете не го искат и добавен там би счупил работещ формат.");
    assertEquals(
        0x0A,
        bytes[bytes.length - 2],
        "и стои СЛЕД последното CRLF, не вместо него — „всеки запис завършва с CR+LF“ важи и за"
            + " последния");
    assertEquals(0x0D, bytes[bytes.length - 3], "CR преди LF, не само LF");

    String decoded = new String(bytes, 0, bytes.length - 1, Charset.forName("windows-1251"));
    assertEquals(
        2,
        decoded.split("\r\n", -1).length - 1,
        "два записа → два CRLF-а. ⚠️ Ако писачът слагаше CRLF само МЕЖДУ редовете, тук щеше да е 1 —"
            + " и последният запис щеше да е без край.");
    assertTrue(
        decoded.contains("\"ПЕТРОВ\""),
        "⚠️ И КИРИЛИЦАТА ПРЕЖИВЯВА CP1251 — четено обратно през windows-1251, името е същото."
            + " Прочетено през UTF-8, то би било четири въпросителни, а файлът пак би имал верния"
            + " брой полета.");
    assertTrue(
        bytes.length < decoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
        "⚠️ CP1251 е ЕДНОБАЙТОВ за кирилица, UTF-8 е двубайтов — тоест ако някой ден писачът мине на"
            + " UTF-8, дължината ще порасне и този свидетел ще падне. Проверката е на дължина,"
            + " защото за BOM-а вече има свидетел в ДДС-стека.");
  }

  @Test
  @DisplayName("името на файла е EMPL2021.TXT — от заповедта, не по избор")
  void theFileNameIsFixed() {
    assertEquals("EMPL2021.TXT", BgD1FileAdapter.FILE_NAME);
    assertEquals(',', BgD1FileAdapter.SEPARATOR, "разделителят е ЗАПЕТАЯ");
  }

  // ═══ Д6 ═══

  @Test
  @DisplayName("⚠️ РЕДЪТ НА Д6 · 59 полета, данъкът в блок 2, датата дд/мм/гггг без кавички")
  void theD6LineMatchesItsFormat() {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 5, 1));
    period.setToDate(LocalDate.of(2026, 5, 31));

    BgD6Declaration d = new BgD6Declaration();
    d.setBgPeriod(period);
    d.setT1ObligedPersonCode("831919536");
    d.setT2Name("Никса ЕООД");
    d.setT3InsuranceFundMember("0");
    d.setT4Email("trz@example.bg");
    d.setT81PaymentKind("5");
    d.setT18PaymentDate(LocalDate.of(2026, 5, 31));
    d.setT10Doo(new BigDecimal("395.40"));
    d.setT11TeacherPf(new BigDecimal("23.60"));
    d.setT12Upf(new BigDecimal("100.00"));
    d.setT13Ppf(new BigDecimal("84.00"));
    d.setT14Health(new BigDecimal("288.94"));
    d.setT15Gvrs(new BigDecimal("1.20"));
    d.setT16Tax(new BigDecimal("264.63"));

    String line = D6_ADAPTER.toLine(d, "876543215");
    assertEquals(
        // 1–6 · заглавие
        "\"831919536\",\"НИКСА ЕООД\",0,\"TRZ@EXAMPLE.BG\",\"\",\"\","
            // 7–19 · блок 1 — вноските, вид 5; данъкът НЕ е тук
            + "05,2026,31/05/2026,0,5,395.40,23.60,100.00,84.00,288.94,1.20,,,"
            // 20–32 · блок 2 — данъкът, вид 8, в поле 31
            + "05,2026,31/05/2026,0,8,,,,,,,264.63,,"
            // 33–58 · блокове 3 и 4 — изцяло празни
            + ",,,,,,,,,,,,,"
            + ",,,,,,,,,,,,,"
            // 59 · източник
            + "\"876543215\"",
        line,
        "59 полета. ⚠️ ДАТАТА Е дд/мм/гггг и в блоковете е БЕЗ кавички: LocalDate.toString() дава"
            + " „2026-05-31“ — низ, който изглежда като дата и НЕ е тази дата.");
    assertEquals(59, line.split(",", -1).length, "точно 59 полета, преброени");
  }

  @Test
  @DisplayName("Д6 · ЕДИН ред на файл, защото декларацията е агрегат на дружеството")
  void theD6FileCarriesExactlyOneRecord() throws IOException {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 5, 1));

    BgD6Declaration d = new BgD6Declaration();
    d.setBgPeriod(period);
    d.setT1ObligedPersonCode("831919536");
    d.setT81PaymentKind("5");
    d.setT10Doo(new BigDecimal("395.40"));

    BgD1FileService service = new BgD1FileService(new BgVatFileWriter(), ADAPTER, D6_ADAPTER);
    byte[] bytes = service.d6Bytes(d, "876543215");
    String decoded = new String(bytes, 0, bytes.length - 1, Charset.forName("windows-1251"));

    assertEquals(
        1,
        decoded.split("\r\n", -1).length - 1,
        "⚠️ ЕДИН запис. Това е най-лесната грешка при преизползване на Д1-пътя, където редовете са по"
            + " фиш: Д6 е агрегат на ДРУЖЕСТВОТО и файлът ѝ носи точно един ред за месеца.");
    assertEquals(BgD1FileService.END_OF_FILE, bytes[bytes.length - 1], "и Д6 иска CTRL+Z");
  }

  @Test
  @DisplayName("⚠️ поле 10 на Д6 · флагът за промяна е ЦИФРА, не буквата „К“")
  void theD6CorrectionFlagIsADigit() {
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 5, 1));

    BgD6Declaration regular = new BgD6Declaration();
    regular.setBgPeriod(period);
    regular.setT81PaymentKind("5");
    assertEquals("0", regular.getT71CorrectionCode() == null ? "0" : "1");
    assertEquals("0", D6_ADAPTER.toLine(regular, null).split(",", -1)[9], "празната т. 7.1 → 0");

    BgD6Declaration correcting = new BgD6Declaration();
    correcting.setBgPeriod(period);
    correcting.setT81PaymentKind("5");
    correcting.setT71CorrectionCode("К");
    assertEquals(
        "1",
        D6_ADAPTER.toLine(correcting, null).split(",", -1)[9],
        "⚠️ Същият превод като при поле 52 на Д1, и по същия довод. И ⚠️ ПОМНИ КАКВО ЗНАЧИ: корекция"
            + " на Д6 се подава САМО при НАМАЛЯВАНЕ на задължение (чл. 9 ал. 7); при увеличаване се"
            + " подава НОВА декларация с положителната разлика.");
  }

  @Test
  @DisplayName("нула записа · файлът е САМО маркерът за край, а не празен")
  void anEmptyFileIsStillTerminated() throws IOException {
    BgD1FileService service = new BgD1FileService(new BgVatFileWriter(), ADAPTER, D6_ADAPTER);
    byte[] bytes = service.d1Bytes(Collections.emptyList(), null);
    assertEquals(1, bytes.length, "нула редове → нула CRLF-а");
    assertEquals(BgD1FileService.END_OF_FILE, bytes[0], "но маркерът пак го има");
  }
}
