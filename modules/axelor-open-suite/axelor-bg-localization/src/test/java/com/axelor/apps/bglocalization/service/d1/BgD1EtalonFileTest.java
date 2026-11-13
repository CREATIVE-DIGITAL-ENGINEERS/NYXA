package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD1FileAdapter;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ДВА ФАЙЛА, ПРИЕТИ ОТ НАП — НАШИЯТ РЕД СРЕЩУ ТЯХ, БАЙТ ПО БАЙТ.
 *
 * <p>Еталоните са коригираща (код 1) и заличаваща (код 8) Декларация обр. 1 от друг софтуер,
 * подадени и приети, анонимизирани (фиктивни ЕГН и ЕИК), в cp1251 — {@code
 * src/test/resources/d1/etalon/}, защитени от нормализацията на git с {@code -text}. Записът тук
 * носи СЪЩИТЕ данни, тоест разлика в реда е разлика във ФОРМАТА, не в числата.
 *
 * <p><b>Разликите, които сверката откри и поправи</b> ({@code BgD1FileAdapter}): 54 полета срещу
 * 53; празните суми „0“ срещу „0.00“; месец „09“ срещу „9“ и вид осигурен „01“ срещу „1“; часове
 * „80“ срещу „080“; празна т. 13 {@code ""} срещу {@code "0000"}.
 *
 * <p><b>⚠️ СРАВНЯВА СЕ РЕДЪТ, НЕ ФАЙЛЪТ.</b> Тези еталони са минали през пренос, който е сменил
 * края на реда (LF, без CTRL+Z). Целият файл — Windows-1251, CRLF и CTRL+Z — се сравнява байт по
 * байт в {@code BgMicroinvestEtalonFileTest}, срещу подадените в НАП файлове на Microinvest. Тук
 * остава онова, което само тези два файла носят: КОРИГИРАЩИЯТ и ЗАЛИЧАВАЩИЯТ ред.
 *
 * <p><b>⚠️ ДВЕ ОБЯВЕНИ РАЗЛИКИ:</b>
 *
 * <ul>
 *   <li><b>поле 51.</b> Еталоните носят „0“, Microinvest — „000“. НАП е приел и двете; генераторът
 *       пише „000“, по Microinvest (10.2026);
 *   <li><b>полета 30 и 31 на заличаващия ред.</b> Еталонът носи там {@code "0000",00} — разменени
 *       по форма: поле 30 е „Numeric =2“, поле 31 — „Character =4“, и коригиращият еталон от СЪЩИЯ
 *       софтуер ги има правилно ({@code 99,"0000"}). НАП не ги е проверил при заличаване. Ние пишем
 *       {@code 0,"0000"} — по заповедта.
 * </ul>
 *
 * <p><b>⚠️ И ЕДНО ОТВОРЕНО — Т. 29 (ПОЛЕ 46) НА КОРИГИРАЩАТА.</b> Записът тук е РЕАЛЕН за вид
 * осигурен 01: т. 29 носи брутната работна заплата, сметната от СЪЩАТА функция, с която генераторът
 * я пише ({@code BgPaySlipResolver#grossWageTotal}). Еталонът има {@code 0.00}, ние — {@code
 * 682.06}. За РЕДОВЕН ред на вид 01 въпросът е решен: Microinvest (09.2026) носи брутото в поле 46,
 * а ставката на ГВРС (поле 47, т. 30) е 0.00 — генераторът остава. Нулата тук е в КОРИГИРАЩ ред и
 * чака коригиращ Д1 от Microinvest ({@link #pointTwentyNineOfTheCorrectionAwaitsTheAccountant},
 * изключен до тогава).
 */
class BgD1EtalonFileTest {

  private static final Charset CP1251 = Charset.forName("windows-1251");

  /** Индексът на поле 46 (т. 29) в реда — броено от нула. */
  private static final int T29 = 45;

  /** Индексът на поле 51 (код на осигурителна каса) — броено от нула. */
  private static final int FUND_CODE = 50;

  /** Поле 53 на еталоните — ЕИК на разработчика на софтуер (фиктивен). */
  private static final String SOURCE_FLAG = "876543215";

  private final BgD1FileAdapter adapter = new BgD1FileAdapter();

  /** Очакваната т. 29 на коригиращата за вид 01 — чака коригиращ Д1 от Microinvest. */
  private static final String EXPECTED_T29_TBD = null;

  @Test
  @DisplayName("коригираща (код 1): байт по байт приетият от НАП — без поле 46 и обявеното поле 51")
  void theCorrectionLineIsTheAcceptedOne() throws IOException {
    List<String> etalon = fields(new String(recordBytes("D1_etalon_korekcia_1_anon.txt"), CP1251));

    List<String> ours = fields(adapter.toLine(correction(), SOURCE_FLAG));

    assertEquals(53, ours.size(), "53 полета, а не 54: " + ours);
    // ⚠️ Т. 29 СЕ ЗАПИСВА, НЕ СЕ СЪДИ: двете страни се различават, и коригиращият ред чака
    // образец от Microinvest.
    assertEquals("0.00", etalon.get(T29), "еталонът: т. 29 = 0.00 в коригиращ ред за вид 01");
    assertEquals("682.06", ours.get(T29), "генераторът: брутната работна заплата за вид 01");
    assertFundCodeDifference(etalon, ours);
    List<String> e = new ArrayList<>(etalon);
    List<String> o = new ArrayList<>(ours);
    e.set(T29, "т.29");
    o.set(T29, "т.29");
    e.set(FUND_CODE, "п.51");
    o.set(FUND_CODE, "п.51");
    assertArrayEquals(
        String.join(",", e).getBytes(CP1251),
        String.join(",", o).getBytes(CP1251),
        "байт по байт в cp1251, без поле 46 и поле 51: " + ours);
  }

  @Test
  @Disabled(
      "Чака коригиращ Д1 от Microinvest — т. 29 (поле 46) на КОРИГИРАЩ ред за вид 01: еталонът"
          + " има 0.00, генераторът пише брутната работна заплата (682.06), както и"
          + " редовният ред на Microinvest. Генераторът е непроменен.")
  @DisplayName(
      "т. 29 · поле 46 на коригиращата за вид 01 — очаквана стойност TBD (чака коригиращ Д1 от"
          + " Microinvest)")
  void pointTwentyNineOfTheCorrectionAwaitsTheAccountant() {
    assertEquals(
        EXPECTED_T29_TBD, fields(adapter.toLine(correction(), SOURCE_FLAG)).get(T29), "т. 29");
  }

  @Test
  @DisplayName(
      "заличаваща (код 8): полето по полето = приетият от НАП, без разменените 30 и 31 и поле 51")
  void theDeletionLineIsTheAcceptedOne() throws IOException {
    List<String> etalon =
        fields(new String(recordBytes("D1_etalon_zalichavane_8_anon.txt"), CP1251));

    List<String> ours = fields(adapter.toLine(deletion(), SOURCE_FLAG));

    assertEquals(53, etalon.size(), "еталонът: " + etalon);
    assertEquals(etalon.size(), ours.size(), "броят полета: " + ours);
    for (int i = 0; i < etalon.size(); i++) {
      int position = i + 1;
      if (position == 30 || position == 31 || i == FUND_CODE) {
        continue;
      }
      assertEquals(etalon.get(i), ours.get(i), "поле " + position + ": " + ours);
    }
    // ⚠️ Обявеното изключение — по заповедта, не по еталона (виж javadoc-а на класа).
    assertEquals(Arrays.asList("\"0000\"", "00"), etalon.subList(29, 31), "еталонът, разменен");
    assertEquals(Arrays.asList("0", "\"0000\""), ours.subList(29, 31), "нашият, по заповедта");
    assertFundCodeDifference(etalon, ours);
  }

  /** Поле 51: „0“ в еталона, „000“ при Microinvest и при нас — обявената разлика. */
  private static void assertFundCodeDifference(List<String> etalon, List<String> ours) {
    assertEquals("0", etalon.get(FUND_CODE), "еталонът: поле 51 = 0");
    assertEquals(
        BgD1FileAdapter.NOT_AN_INSURANCE_FUND,
        ours.get(FUND_CODE),
        "генераторът: поле 51 = 000, без кавички — по подадения файл на Microinvest");
    assertEquals("000", ours.get(FUND_CODE));
  }

  // ── записите — със СЪЩИТЕ данни като еталоните ───────────────────────────

  /**
   * Коригиращата: 09/2026, вид 01, 20 дни, 80 часа, КИД 9111, доход 682,06 — и т. 29, сметната като
   * в генератора: брутната работна заплата от перата (основна 682,06, формира БРЗ).
   */
  private static BgD1Record correction() {
    BgD1Record r = identity("К", "09", "5904010014", "Тестова", "01");
    r.setT16Days("20");
    r.setT16LegalHours("8");
    r.setT16ContractedHours("4");
    r.setT161WorkedDays("20");
    r.setT162SickDays("0");
    r.setT163ChildCareDays("0");
    r.setT164NoContributionDays("0");
    r.setT165UnpaidLeaveDays("0");
    r.setT16aEmployerSickDays("0");
    r.setT167WorkedHours("80");
    r.setT121QualificationGroup("2");
    r.setT122ActivityCode("9111");
    r.setT123ActivityRow("99");
    r.setT21InsuranceIncome(new BigDecimal("682.06"));
    r.setT22DooRateEmployer(new BigDecimal("13.72"));
    r.setT22DooRateInsured(new BigDecimal("10.58"));
    r.setT221HealthRateEmployer(new BigDecimal("4.80"));
    r.setT221HealthRateInsured(new BigDecimal("3.20"));
    r.setT222TzpbRate(new BigDecimal("0.50"));
    r.setT29GrossWage(BgPaySlipResolver.grossWageTotal(Collections.singletonList(salary())));
    r.setT31TaxableIncome(new BigDecimal("682.06"));
    r.setT31aTax(new BigDecimal("58.81"));
    r.setT34NetPay(new BigDecimal("529.26"));
    return r;
  }

  /** Перо 100 „Основно възнаграждение“ — CREDIT, формира брутната работна заплата. */
  private static BgPaySlipLine salary() {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(100);
    line.setName("Основно възнаграждение");
    line.setBgSign(BgPaySlipResolver.CREDIT);
    line.setBgFormsGrossWage(true);
    line.setBgAmount(new BigDecimal("682.06"));
    return line;
  }

  /** Заличаващата: 06/2026, вид 14 — само точките, които указание т. 1 попълва. */
  private static BgD1Record deletion() {
    return identity("З", "06", "6106010021", "Тестов", "14");
  }

  private static BgD1Record identity(
      String code, String month, String personalId, String surname, String insuredType) {
    BgD1Record r = new BgD1Record();
    r.setT1CorrectionCode(code);
    r.setT2Month(month);
    r.setT3Year("2026");
    r.setT4ObligedPersonCode("123456786");
    r.setT5PersonalId(personalId);
    r.setT51PersonalIdCode("0");
    r.setT6Surname(surname);
    r.setT7Initials("ТТ");
    r.setT12InsuredTypeCode(insuredType);
    return r;
  }

  // ── еталонът ─────────────────────────────────────────────────────────────

  /** Байтовете на ЕДИНСТВЕНИЯ запис в еталона, без терминатора (LF в еталоните). */
  private static byte[] recordBytes(String name) throws IOException {
    try (InputStream in = BgD1EtalonFileTest.class.getResourceAsStream("/d1/etalon/" + name)) {
      assertNotNull(in, "еталонът липсва в classpath-а: " + name);
      byte[] all = in.readAllBytes();
      int end = all.length;
      while (end > 0 && (all[end - 1] == '\n' || all[end - 1] == '\r' || all[end - 1] == 0x1A)) {
        end--;
      }
      return Arrays.copyOf(all, end);
    }
  }

  /** Полетата на реда — запетаите в {@code Character} са изключени по формат. */
  private static List<String> fields(String line) {
    return new ArrayList<>(Arrays.asList(line.split(",", -1)));
  }
}
