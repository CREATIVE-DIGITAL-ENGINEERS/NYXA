package com.axelor.apps.bglocalization.service.d1;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Company;
import com.axelor.apps.base.db.Partner;
import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgCompensation;
import com.axelor.apps.bglocalization.db.BgContributionFund;
import com.axelor.apps.bglocalization.db.BgD1Declaration;
import com.axelor.apps.bglocalization.db.BgD1Record;
import com.axelor.apps.bglocalization.db.BgD6Declaration;
import com.axelor.apps.bglocalization.db.BgInsuredType;
import com.axelor.apps.bglocalization.db.BgNkid;
import com.axelor.apps.bglocalization.db.BgNkpd;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD1FileAdapter;
import com.axelor.apps.bglocalization.service.d1.adapter.BgD6FileAdapter;
import com.axelor.apps.bglocalization.service.d1.dto.BgD1FindingDto;
import com.axelor.apps.bglocalization.service.vat.BgVatFileWriter;
import com.axelor.apps.hr.db.Employee;
import com.axelor.apps.hr.db.EmploymentContract;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ОБЕЗЩЕТЕНИЕТО ПО ЧЛ. 222 АЛ. 1 КТ В Д1 И Д6 — ЦЕЛИЯТ ФАЙЛ, ПО ПЪТЯ НА ДЕКЛАРАЦИИТЕ.
 *
 * <p><b>Пътят:</b> фиш → {@link BgD1Emitter#emit} → {@link BgD1FileService#d1Bytes}; фишове →
 * {@link BgD6CompensationLedger#of} → {@link BgD6Emitter#emit} → {@link BgD1FileService#d6Bytes}.
 * Подменени са само шевовете към базата — речникът на видовете осигурен, календарът на празниците
 * за 2025 г. и таванът; всичко останало е производственият код.
 *
 * <p><b>Образците (Microinvest, подадени в НАП; лицето тук е измислено — P0041):</b>
 *
 * <ul>
 *   <li><b>01.2025</b> — прекратяване 01.01.2025, обезщетение 1800,00, изплатено 28.02.2025. Д1:
 *       ред 27, т. 14/15 = 02/31, т. 16 „2288“, т. 21 = т. 31 = 1800,00, ставки 8,22/6,58 и
 *       2,80/2,20, т. 29 = 0,00, ДДФЛ 164,20, нето 0,00. Д6: блок вид 6 (01.2025, 28/02/2025, ДОО
 *       266,40, УПФ 90,00) и блок вид 8 (02.2025, ДДФЛ 164,20) — байт по байт срещу еталона {@code
 *       NRA62007_2025_01_vid6.txt}.
 *   <li><b>03 и 04.2025</b> — прекратяване 23.03.2025, обезщетение 1077,00 (53,85 × 20), изплатено
 *       31.03.2025 с мартенския фиш. Март: 24–31, „0688“, 6 дни, 307,74, ДДФЛ 28,07; април: 01–23,
 *       „1588“, 15 дни, 769,26, ДДФЛ 70,17. Д6 март: блок вид 6, 03.2025, 31/03/2025, ДОО 45,55,
 *       УПФ 15,39. Мартенският фиш носи и заплатата за 1–23.03 — смесен месец: Д1 два реда, Д6 три
 *       блока (5, 6, 8). Април: лицето фиш няма — редът идва от мартенския фиш.
 * </ul>
 */
class BgCompensation27FileTest {

  private static final Charset CP1251 = Charset.forName("windows-1251");
  private static final String SOURCE_FLAG = "876543215";
  private static final BgD1FileService FILES =
      new BgD1FileService(new BgVatFileWriter(), new BgD1FileAdapter(), new BgD6FileAdapter());

  private static final String CREDIT = BgPaySlipResolver.CREDIT;
  private static final String DEBIT = BgPaySlipResolver.DEBIT;

  // ═══ 01.2025 — само обезщетение, един месец ═══════════════════════════════

  @Test
  @DisplayName(
      "01.2025 · Д1: ред 27 като в образеца — 02/31, „2288“, 1800,00, 8,22/6,58, 2,80/2,20")
  void januaryD1RowIsTheSample() throws IOException {
    Company company = company();
    Period january = period(LocalDate.of(2025, 1, 1), 1L);
    BgPaySlip slip = januarySlip(company, january);

    BgD1Emitter.Outcome outcome = new Emitter().emit(declaration(january), list(slip));

    assertEquals(1, outcome.getRecords().size(), "само ред 27 — фишът носи само обезщетение");
    assertEquals(
        "1,2025,\"123456786\",\"8506150012\",0,\"ПЕТРОВА\",\"МИ\",27,02,31,0,0,0,0,0,0,0,0,\"2288\",22,"
            + "0,0,0,0,0,0,0,0,\"\",0,\"0000\",0.00,0.00,1800.00,8.22,6.58,0.00,0.00,0.00,0.00,0.00,"
            + "2.80,2.20,0.00,0.00,0.00,0.00,1800.00,164.20,0.00,000,0,\"876543215\"",
        line(outcome.getRecords()));
  }

  @Test
  @DisplayName("01.2025 · Д6: вид 6 и вид 8 сами, без ръчен вид — байт по байт срещу еталона")
  void januaryD6IsTheEtalon() throws IOException {
    Company company = company();
    Period january = period(LocalDate.of(2025, 1, 1), 1L);
    List<BgPaySlip> slips = list(januarySlip(company, january));
    BgD6Declaration d6 = d6(company, january);
    BgD6CompensationLedger ledger = ledger(january, slips);

    new BgD6Emitter().emit(d6, slips, ledger);
    assertNull(
        BgD6Emitter.paymentKindRefusal(d6, ledger),
        "⚠️ блок на заплатите няма — ръчният вид 5/6 не се иска: вид 6 е по норма");

    assertArrayEquals(
        etalon("NRA62007_2025_01_vid6.txt"),
        FILES.d6Bytes(d6, ledger, SOURCE_FLAG),
        new String(FILES.d6Bytes(d6, ledger, SOURCE_FLAG), CP1251));
  }

  // ═══ 03 И 04.2025 — периодът минава в април; мартенският фиш е смесен ═════

  @Test
  @DisplayName(
      "03.2025 · Д1: ред 01 (заплатата, непроменен) и ред 27 — 24/31, „0688“, 307,74, 28,07")
  void marchD1HasTwoRows() throws IOException {
    Company company = company();
    Period march = period(LocalDate.of(2025, 3, 1), 3L);
    BgPaySlip slip = marchSlip(company, march);

    BgD1Emitter.Outcome outcome = new Emitter().emit(declaration(march), list(slip));

    assertEquals(2, outcome.getRecords().size(), "смесен месец — ред 01 и ред 27");
    BgD1Record regular = outcome.getRecords().get(0);
    assertEquals("01", regular.getT12InsuredTypeCode());
    pin("1500.00", regular.getT21InsuranceIncome(), "т. 21 на ред 01 — БЕЗ обезщетението");
    pin("1500.00", regular.getT31TaxableIncome(), "т. 31 на ред 01 — без обезщетението");
    pin("129.33", regular.getT31aTax(), "т. 31а на ред 01 — остатъкът 227,57 − 98,24");
    pin("1163.97", regular.getT34NetPay(), "т. 34 — нето на заплатата, без обезщетението");
    pin("10.92", regular.getT22DooRateEmployer(), "т. 22 на ред 01 — пълният пакет на вид 01");

    BgD1Record compensation = outcome.getRecords().get(1);
    assertEquals(
        "3,2025,\"123456786\",\"8506150012\",0,\"ПЕТРОВА\",\"МИ\",27,24,31,0,0,0,0,0,0,0,0,\"0688\",6,"
            + "0,0,0,0,0,0,0,0,\"\",0,\"0000\",0.00,0.00,307.74,8.22,6.58,0.00,0.00,0.00,0.00,0.00,"
            + "2.80,2.20,0.00,0.00,0.00,0.00,307.74,28.07,0.00,000,0,\"876543215\"",
        line(Collections.singletonList(compensation)));
    // ⚠️ Сборът е на ВСИЧКИ редове на фиша — 01 и ДВЕТЕ части на обезщетението (март + април).
    BgD1Record april =
        new Emitter()
            .emit(declaration(period(LocalDate.of(2025, 4, 1), 4L)), list(slip))
            .getRecords()
            .get(0);
    assertEquals(
        0,
        new BigDecimal("227.57")
            .compareTo(regular.getT31aTax().add(compensation.getT31aTax()).add(april.getT31aTax())),
        "129,33 + 28,07 + 70,17 = данъкът на фиша до стотинка");
  }

  @Test
  @DisplayName("04.2025 · Д1: ред 27 от МАРТЕНСКИЯ фиш — 01/23, „1588“, 15 дни, 769,26, 70,17")
  void aprilD1RowComesFromTheMarchSlip() throws IOException {
    Company company = company();
    Period march = period(LocalDate.of(2025, 3, 1), 3L);
    Period april = period(LocalDate.of(2025, 4, 1), 4L);
    BgPaySlip slip = marchSlip(company, march);

    BgD1Emitter.Outcome outcome = new Emitter().emit(declaration(april), list(slip));

    assertEquals(1, outcome.getRecords().size(), "само ред 27 — заплатата е на март");
    assertEquals(
        "4,2025,\"123456786\",\"8506150012\",0,\"ПЕТРОВА\",\"МИ\",27,01,23,0,0,0,0,0,0,0,0,\"1588\",15,"
            + "0,0,0,0,0,0,0,0,\"\",0,\"0000\",0.00,0.00,769.26,8.22,6.58,0.00,0.00,0.00,0.00,0.00,"
            + "2.80,2.20,0.00,0.00,0.00,0.00,769.26,70.17,0.00,000,0,\"876543215\"",
        line(outcome.getRecords()));
    assertEquals(slip, outcome.getRecords().get(0).getPaySlip(), "записът сочи носителя");
    assertNull(
        BgD6D1Reconciliation.refuse("04.2025", list(slip), outcome.getRecords()),
        "⚠️ сверката Д1↔Д6 брои мартенския фиш за април — той е действащ за частта");
  }

  @Test
  @DisplayName("03.2025 · Д6: три блока — заплатите (5), обезщетението (6, 31/03), данъкът (8)")
  void marchD6HasThreeBlocks() throws IOException {
    Company company = company();
    Period march = period(LocalDate.of(2025, 3, 1), 3L);
    List<BgPaySlip> slips = list(marchSlip(company, march));
    BgD6Declaration d6 = d6(company, march);
    BgD6CompensationLedger ledger = ledger(march, slips);

    new BgD6Emitter().emit(d6, slips, ledger);
    d6.setT81PaymentKind(BgD6Point.PAYMENT_KIND_SAME_MONTH);
    d6.setT18PaymentDate(LocalDate.of(2025, 3, 31));

    pin("342.55", d6.getT10Doo(), "т. 10 = 297,00 (заплатата) + 45,55 (мартенската част)");
    pin("90.39", d6.getT12Upf(), "т. 12 = 75,00 + 15,39");
    pin("227.57", d6.getT16Tax(), "т. 16 — целият данък на фиша, удържан през март");
    assertEquals(
        d6Line(
            new String[] {
              "03", "2025", "31/03/2025", "0", "5", "297.00", "", "75.00", "", "120.00", "", "", ""
            },
            new String[] {
              "03", "2025", "31/03/2025", "0", "6", "45.55", "", "15.39", "", "", "", "", ""
            },
            new String[] {
              "03", "2025", "31/03/2025", "0", "8", "", "", "", "", "", "", "227.57", ""
            }),
        new String(FILES.d6Bytes(d6, ledger, SOURCE_FLAG), CP1251).replace("\r\n\u001a", ""));
  }

  @Test
  @DisplayName(
      "03.2025 · Д6 ФАЙЛЪТ: блокът с код 6 байт по байт = образецът"
          + " „03,2025,31/03/2025,0,6,45.55,,15.39,,,,,“; празните суми са празни полета, не „0“")
  void marchBlockSixIsTheSampleByteForByte() throws IOException {
    Company company = company();
    Period march = period(LocalDate.of(2025, 3, 1), 3L);
    List<BgPaySlip> slips = list(marchSlip(company, march));
    BgD6Declaration d6 = d6(company, march);
    BgD6CompensationLedger ledger = ledger(march, slips);
    new BgD6Emitter().emit(d6, slips, ledger);
    d6.setT81PaymentKind(BgD6Point.PAYMENT_KIND_SAME_MONTH);
    d6.setT18PaymentDate(LocalDate.of(2025, 3, 31));

    byte[] file = FILES.d6Bytes(d6, ledger, SOURCE_FLAG);
    String[] fields = new String(file, CP1251).replace("\r\n\u001a", "").split(",", -1);
    assertEquals(BgD6FileAdapter.FIELD_COUNT, fields.length, "59 полета");

    // ⚠️ БАЙТ ПО БАЙТ, в кодировката на файла: блокът с код 6 от образеца (Microinvest, 03.2025).
    byte[] sample = "03,2025,31/03/2025,0,6,45.55,,15.39,,,,,".getBytes(CP1251);
    byte[] block6 = null;
    for (int b = 0; b < BgD6FileAdapter.BLOCKS; b++) {
      int start = 6 + b * BgD6FileAdapter.BLOCK_WIDTH;
      if ("6".equals(fields[start + 4])) {
        block6 =
            String.join(",", Arrays.copyOfRange(fields, start, start + BgD6FileAdapter.BLOCK_WIDTH))
                .getBytes(CP1251);
      }
    }
    assertNotNull(block6, "блок с код 6 има: " + String.join(",", fields));
    assertArrayEquals(sample, block6, new String(block6, CP1251));

    // ⚠️ И ВЪВ ВСЕКИ БЛОК: сумите (полета 6–13 на блока) са празни или положителни — никога „0“.
    for (int b = 0; b < BgD6FileAdapter.BLOCKS; b++) {
      int start = 6 + b * BgD6FileAdapter.BLOCK_WIDTH;
      for (int i = 5; i < BgD6FileAdapter.BLOCK_WIDTH; i++) {
        String sum = fields[start + i];
        assertTrue(
            sum.isEmpty() || new BigDecimal(sum).signum() > 0,
            "блок " + (b + 1) + ", поле " + (i + 1) + " = „" + sum + "“ — празната сума е ,,");
      }
    }
  }

  @Test
  @DisplayName("04.2025 · Д6: само блок вид 6 — 04.2025, 31/03/2025, ДОО 113,85, УПФ 38,46")
  void aprilD6HasOnlyTheCompensationBlock() throws IOException {
    Company company = company();
    Period march = period(LocalDate.of(2025, 3, 1), 3L);
    Period april = period(LocalDate.of(2025, 4, 1), 4L);
    List<BgPaySlip> slips = list(marchSlip(company, march));
    BgD6Declaration d6 = d6(company, april);
    BgD6CompensationLedger ledger = ledger(april, slips);

    new BgD6Emitter().emit(d6, slips, ledger);

    pin("113.85", d6.getT10Doo(), "т. 10 — само априлската част; заплатата е на март");
    pin("38.46", d6.getT12Upf(), "т. 12 — само априлската част");
    assertEquals(0, d6.getT16Tax().signum(), "⚠️ данъкът е удържан през март — в Д6 за март");
    assertEquals(
        d6Line(
            new String[] {
              "04", "2025", "31/03/2025", "0", "6", "113.85", "", "38.46", "", "", "", "", ""
            }),
        new String(FILES.d6Bytes(d6, ledger, SOURCE_FLAG), CP1251).replace("\r\n\u001a", ""));
  }

  // ═══ ОБЩИЯТ ТАВАН С ВИД 01 (КСО ЧЛ. 6 АЛ. 11) — вид 01 = 4000,00 при таван 4130,00 ═══

  @Test
  @DisplayName(
      "⚠️ 03.2025 · таван 4130,00, вид 01 = 4000,00 → ред 27: т. 21 = 130,00 (остатъкът до"
          + " тавана), т. 31 = 307,74; т. 21 на двата реда = 4130,00; Д6 блок 6 = 19,24 / 6,50")
  void type27GetsOnlyTheRoomLeftUnderTheCeiling() throws IOException {
    Company company = company();
    Period march = period(LocalDate.of(2025, 3, 1), 3L);
    BgPaySlip slip = marchSlipNearCeiling(company, march);

    BgD1Emitter.Outcome outcome = new Emitter().emit(declaration(march), list(slip));
    assertEquals(2, outcome.getRecords().size(), "ред 01 и ред 27: " + outcome.getFindings());
    BgD1Record regular = outcome.getRecords().get(0);
    BgD1Record compensation = outcome.getRecords().get(1);
    pin("4000.00", regular.getT21InsuranceIncome(), "т. 21 на ред 01 — вид 01 не се реже");
    assertEquals(
        "3,2025,\"123456786\",\"8506150012\",0,\"ПЕТРОВА\",\"МИ\",27,24,31,0,0,0,0,0,0,0,0,\"0688\",6,"
            + "0,0,0,0,0,0,0,0,\"\",0,\"0000\",0.00,0.00,130.00,8.22,6.58,0.00,0.00,0.00,0.00,0.00,"
            + "2.80,2.20,0.00,0.00,0.00,0.00,307.74,29.63,0.00,000,0,\"876543215\"",
        line(Collections.singletonList(compensation)));
    pin(
        BgCompensationPartsTest.CEILING_2025.toPlainString(),
        regular.getT21InsuranceIncome().add(compensation.getT21InsuranceIncome()),
        "⚠️ т. 21 на двата реда = таванът, не 4307,74");
    for (BgD1FindingDto f : outcome.getFindings()) {
      assertTrue(
          !"D1_T27_CEILING_STALE".equals(f.getCode()), "фишът и частите са на една база: " + f);
    }

    // ═══ Д6 — същият месец: вноските на реда 27 = т. 21 × ставките, до стотинка ═══
    List<BgPaySlip> slips = list(slip);
    BgD6Declaration d6 = d6(company, march);
    BgD6CompensationLedger ledger = ledger(march, slips);
    new BgD6Emitter().emit(d6, slips, ledger);
    d6.setT81PaymentKind(BgD6Point.PAYMENT_KIND_SAME_MONTH);
    d6.setT18PaymentDate(LocalDate.of(2025, 3, 31));

    assertEquals(
        0,
        percent(compensation.getT21InsuranceIncome(), compensation.getT22DooRateEmployer())
            .add(percent(compensation.getT21InsuranceIncome(), compensation.getT22DooRateInsured()))
            .compareTo(new BigDecimal("19.24")),
        "Д1: 130,00 × 8,22 % + 130,00 × 6,58 % = 10,69 + 8,55");
    pin("811.24", d6.getT10Doo(), "т. 10 = 792,00 (вид 01) + 19,24 (мартенската част)");
    pin("206.50", d6.getT12Upf(), "т. 12 = 200,00 + 6,50");
    assertEquals(
        d6Line(
            new String[] {
              "03", "2025", "31/03/2025", "0", "5", "792.00", "", "200.00", "", "320.00", "", "", ""
            },
            new String[] {
              "03", "2025", "31/03/2025", "0", "6", "19.24", "", "6.50", "", "", "", "", ""
            },
            new String[] {
              "03", "2025", "31/03/2025", "0", "8", "", "", "", "", "", "", "444.69", ""
            }),
        new String(FILES.d6Bytes(d6, ledger, SOURCE_FLAG), CP1251).replace("\r\n\u001a", ""));
    assertNull(
        BgD6D1Reconciliation.refuse("03.2025", slips, outcome.getRecords()),
        "сверката Д1↔Д6 минава при общия таван");
    assertTrue(
        new BgD6Emitter().checkEquality(d6, slips, outcome.getRecords()).isEmpty(),
        "чл. 2 ал. 4 — Д6 = Д1");
  }

  @Test
  @DisplayName(
      "04.2025 при същия носител · само вид 27 → т. 21 = 769,26, ДОО 113,85, УПФ 38,46 — както"
          + " без тавана")
  void aMonthWithOnlyType27IsNotCapped() throws IOException {
    Company company = company();
    Period march = period(LocalDate.of(2025, 3, 1), 3L);
    Period april = period(LocalDate.of(2025, 4, 1), 4L);
    BgPaySlip slip = marchSlipNearCeiling(company, march);

    BgD1Emitter.Outcome outcome = new Emitter().emit(declaration(april), list(slip));
    assertEquals(1, outcome.getRecords().size());
    pin("769.26", outcome.getRecords().get(0).getT21InsuranceIncome(), "т. 21 — цялата част");
    pin("769.26", outcome.getRecords().get(0).getT31TaxableIncome(), "т. 31");

    List<BgPaySlip> slips = list(slip);
    BgD6Declaration d6 = d6(company, april);
    new BgD6Emitter().emit(d6, slips, ledger(april, slips));
    pin("113.85", d6.getT10Doo(), "63,23 + 50,62 — същото като без тавана");
    pin("38.46", d6.getT12Upf(), "21,54 + 16,92");
  }

  @Test
  @DisplayName(
      "⚠️ 01.2025 · само вид 27, обезщетение 5000,00 при таван 4130,00 → ред 27: т. 21 = 4130,00,"
          + " т. 31 = 5000,00; Д6 блок 6 = 611,24 / 206,50 = таванът × ставките")
  void aCompensationAboveTheCeilingIsInsuredOnlyUpToIt() throws IOException {
    Company company = company();
    Period january = period(LocalDate.of(2025, 1, 1), 1L);
    BgPaySlip slip = januarySlipOverCeiling(company, january);

    BgD1Emitter.Outcome outcome = new Emitter().emit(declaration(january), list(slip));
    assertEquals(1, outcome.getRecords().size(), "само ред 27: " + outcome.getFindings());
    BgD1Record compensation = outcome.getRecords().get(0);
    // ⚠️ Грешният резултат би бил т. 21 = 5000,00 — вноските върху цялата сума, над тавана.
    assertEquals(
        "1,2025,\"123456786\",\"8506150012\",0,\"ПЕТРОВА\",\"МИ\",27,02,31,0,0,0,0,0,0,0,0,\"2288\",22,"
            + "0,0,0,0,0,0,0,0,\"\",0,\"0000\",0.00,0.00,4130.00,8.22,6.58,0.00,0.00,0.00,0.00,0.00,"
            + "2.80,2.20,0.00,0.00,0.00,0.00,5000.00,463.74,0.00,000,0,\"876543215\"",
        line(outcome.getRecords()));
    for (BgD1FindingDto f : outcome.getFindings()) {
      assertTrue(
          !"D1_T27_CEILING_STALE".equals(f.getCode()), "фишът и частта са на една база: " + f);
    }

    // ═══ Д6 — вноските на реда 27 = таванът × ставките, до стотинка ═══
    List<BgPaySlip> slips = list(slip);
    BgD6Declaration d6 = d6(company, january);
    BgD6CompensationLedger ledger = ledger(january, slips);
    new BgD6Emitter().emit(d6, slips, ledger);

    assertEquals(
        0,
        percent(compensation.getT21InsuranceIncome(), compensation.getT22DooRateEmployer())
            .add(percent(compensation.getT21InsuranceIncome(), compensation.getT22DooRateInsured()))
            .compareTo(new BigDecimal("611.24")),
        "Д1: 4130,00 × 8,22 % + 4130,00 × 6,58 % = 339,49 + 271,75");
    pin("611.24", d6.getT10Doo(), "т. 10 — върху тавана, не 740,00 върху 5000,00");
    pin("206.50", d6.getT12Upf(), "т. 12 = 115,64 + 90,86 — не 250,00");
    assertEquals(
        d6Line(
            new String[] {
              "01", "2025", "28/02/2025", "0", "6", "611.24", "", "206.50", "", "", "", "", ""
            },
            new String[] {
              "02", "2025", "28/02/2025", "0", "8", "", "", "", "", "", "", "463.74", ""
            }),
        new String(FILES.d6Bytes(d6, ledger, SOURCE_FLAG), CP1251).replace("\r\n\u001a", ""));
    assertNull(
        BgD6D1Reconciliation.refuse("01.2025", slips, outcome.getRecords()),
        "сверката Д1↔Д6 минава при тавана");
    assertTrue(
        new BgD6Emitter().checkEquality(d6, slips, outcome.getRecords()).isEmpty(),
        "чл. 2 ал. 4 — Д6 = Д1");
  }

  @Test
  @DisplayName("неизплатено обезщетение → Д6 отказва и назовава документа (няма дата за вид 6)")
  void anUnpaidCompensationIsRefused() {
    Company company = company();
    Period march = period(LocalDate.of(2025, 3, 1), 3L);
    BgPaySlip slip = marchSlip(company, march);
    BgPaySlipLine line = slip.getBgPaySlipLineList().get(0);
    for (BgPaySlipLine l : slip.getBgPaySlipLineList()) {
      if (l.getBgCompensation() != null) {
        line = l;
      }
    }
    line.getBgCompensation().setBgPaidDate(null);
    List<BgPaySlip> slips = list(slip);
    BgD6Declaration d6 = d6(company, march);
    BgD6CompensationLedger ledger = ledger(march, slips);
    new BgD6Emitter().emit(d6, slips, ledger);
    d6.setT81PaymentKind(BgD6Point.PAYMENT_KIND_SAME_MONTH);
    d6.setT18PaymentDate(LocalDate.of(2025, 3, 31));

    IllegalStateException refused =
        assertThrows(IllegalStateException.class, () -> BgD6Blocks.of(d6, ledger));
    assertTrue(refused.getMessage().contains("ОБ-0001"), refused.getMessage());
    assertTrue(refused.getMessage().contains("няма дата на изплащане"), refused.getMessage());
  }

  // ═══ ФИШОВЕТЕ ═══

  /** 01.2025 — само обезщетението, 1800,00; вноските върху цялата сума, ДДФЛ 164,20. */
  private static BgPaySlip januarySlip(Company company, Period period) {
    BgPaySlip s = slip(company, period, 41L);
    BgCompensation doc =
        BgCompensationPartsTest.compensation222(
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 28));
    addCompensation(s, doc, new BigDecimal("1800.00"));
    s.getBgPaySlipLineList().add(BgCompensationPartsTest.taxLine("164.20"));
    s.setBgTaxAmount(new BigDecimal("164.20"));
    return s;
  }

  /**
   * 01.2025 — само обезщетението, 5000,00 над тавана 4130,00; вноските върху тавана, както ги пише
   * фишът. Данък (5000,00 − 271,75 − 90,86) × 10 % = 463,74.
   */
  private static BgPaySlip januarySlipOverCeiling(Company company, Period period) {
    BgPaySlip s = slip(company, period, 45L);
    BgCompensation doc =
        BgCompensationPartsTest.compensation222(
            LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 28));
    addCompensation(s, doc, new BigDecimal("5000.00"), BgCompensationPartsTest.CEILING_2025);
    s.getBgPaySlipLineList().add(BgCompensationPartsTest.taxLine("463.74"));
    s.setBgTaxAmount(new BigDecimal("463.74"));
    return s;
  }

  /**
   * 03.2025 — заплата 1500,00 (вид 01, като P0041 от {@code BgMicroinvestEtalonFileTest}) и
   * обезщетение 1077,00, изплатено 31.03.2025. Данък (1500,00 + 1077,00 − 206,70 − 94,56) × 10 % =
   * 227,57.
   */
  private static BgPaySlip marchSlip(Company company, Period period) {
    BgPaySlip s = slip(company, period, 43L);
    BigDecimal base = new BigDecimal("1500.00");
    List<BgPaySlipLine> l = s.getBgPaySlipLineList();
    l.add(salary(base));
    l.add(contribution(501, CREDIT, "PENSION", "22", "8.22", "123.30", base));
    l.add(contribution(502, DEBIT, "PENSION", "22", "6.58", "98.70", base));
    l.add(contribution(503, CREDIT, "OZM", "22", "2.10", "31.50", base));
    l.add(contribution(504, DEBIT, "OZM", "22", "1.40", "21.00", base));
    l.add(contribution(505, CREDIT, "UNEMPLOYMENT", "22", "0.60", "9.00", base));
    l.add(contribution(506, DEBIT, "UNEMPLOYMENT", "22", "0.40", "6.00", base));
    l.add(contribution(507, CREDIT, "TZPB", "22.2", "0.50", "7.50", base));
    l.add(contribution(508, CREDIT, "UPF", "26", "2.80", "42.00", base));
    l.add(contribution(509, DEBIT, "UPF", "26", "2.20", "33.00", base));
    l.add(contribution(510, CREDIT, "HEALTH", "22.1", "4.80", "72.00", base));
    l.add(contribution(511, DEBIT, "HEALTH", "22.1", "3.20", "48.00", base));
    l.add(contribution(512, CREDIT, "GVRS", "30", "0.00", "0.00", base));
    BgCompensation doc =
        BgCompensationPartsTest.compensation222(
            LocalDate.of(2025, 3, 23), LocalDate.of(2025, 3, 31));
    addCompensation(s, doc, new BigDecimal("1077.00"));
    l.add(BgCompensationPartsTest.taxLine("227.57"));
    s.setBgTaxAmount(new BigDecimal("227.57"));
    return s;
  }

  /**
   * 03.2025 — заплата 4000,00 (вид 01, на 130,00 под тавана 4130,00) и обезщетение 1077,00.
   * Вноските по вид 27 са върху 130,00 + 769,26 = 899,26 — както ги пише фишът под общия таван.
   * Данък (4000,00 + 1077,00 − 551,20 − 78,95) × 10 % = 444,69.
   */
  private static BgPaySlip marchSlipNearCeiling(Company company, Period period) {
    BgPaySlip s = slip(company, period, 44L);
    BigDecimal base = new BigDecimal("4000.00");
    List<BgPaySlipLine> l = s.getBgPaySlipLineList();
    l.add(salary(base));
    l.add(contribution(501, CREDIT, "PENSION", "22", "8.22", "328.80", base));
    l.add(contribution(502, DEBIT, "PENSION", "22", "6.58", "263.20", base));
    l.add(contribution(503, CREDIT, "OZM", "22", "2.10", "84.00", base));
    l.add(contribution(504, DEBIT, "OZM", "22", "1.40", "56.00", base));
    l.add(contribution(505, CREDIT, "UNEMPLOYMENT", "22", "0.60", "24.00", base));
    l.add(contribution(506, DEBIT, "UNEMPLOYMENT", "22", "0.40", "16.00", base));
    l.add(contribution(507, CREDIT, "TZPB", "22.2", "0.50", "20.00", base));
    l.add(contribution(508, CREDIT, "UPF", "26", "2.80", "112.00", base));
    l.add(contribution(509, DEBIT, "UPF", "26", "2.20", "88.00", base));
    l.add(contribution(510, CREDIT, "HEALTH", "22.1", "4.80", "192.00", base));
    l.add(contribution(511, DEBIT, "HEALTH", "22.1", "3.20", "128.00", base));
    l.add(contribution(512, CREDIT, "GVRS", "30", "0.00", "0.00", base));
    BgCompensation doc =
        BgCompensationPartsTest.compensation222(
            LocalDate.of(2025, 3, 23), LocalDate.of(2025, 3, 31));
    addCompensation(s, doc, new BigDecimal("1077.00"), new BigDecimal("899.26"));
    l.add(BgCompensationPartsTest.taxLine("444.69"));
    s.setBgTaxAmount(new BigDecimal("444.69"));
    return s;
  }

  /** Перото 900 и вноските на вид 27 върху цялата сума — както ги пише генераторът. */
  private static void addCompensation(BgPaySlip s, BgCompensation doc, BigDecimal amount) {
    addCompensation(s, doc, amount, amount);
  }

  /** Перото 900 и вноските на вид 27 върху {@code base} — сумата под общия таван. */
  private static void addCompensation(
      BgPaySlip s, BgCompensation doc, BigDecimal amount, BigDecimal base) {
    List<BgPaySlipLine> l = s.getBgPaySlipLineList();
    l.add(BgCompensationPartsTest.compensationLine(doc, amount));
    l.add(BgCompensationPartsTest.contribution(doc, 501, CREDIT, "PENSION", "22", "8.22", base));
    l.add(BgCompensationPartsTest.contribution(doc, 502, DEBIT, "PENSION", "22", "6.58", base));
    l.add(BgCompensationPartsTest.contribution(doc, 508, CREDIT, "UPF", "26", "2.80", base));
    l.add(BgCompensationPartsTest.contribution(doc, 509, DEBIT, "UPF", "26", "2.20", base));
  }

  private static BigDecimal percent(BigDecimal base, BigDecimal rate) {
    return base.multiply(rate).divide(new BigDecimal("100"), 2, java.math.RoundingMode.HALF_UP);
  }

  // ═══ ЕМИТЕРЪТ С ПОДМЕНЕНИ ШЕВОВЕ ═══

  /** Шевовете към базата — речник, календар 2025, таван; всичко друго е производственият код. */
  static final class Emitter extends BgD1Emitter {
    Emitter() {
      super(
          new BgD1Validator(),
          new BgD1DaySource() {
            @Override
            public Outcome read(BgPaySlip slip, Period dataPeriod) {
              return new Outcome();
            }
          },
          new BgD1HourSource() {
            @Override
            public Hours read(BgPaySlip slip, Employee employee, LocalDate from, LocalDate to) {
              return new Hours(new BigDecimal("168"), null, 21);
            }
          });
    }

    @Override
    protected BgInsuredType insuredTypeFor(String code, LocalDate date) {
      BgInsuredType t = new BgInsuredType();
      t.setCode(code);
      if ("27".equals(code)) {
        // Както в речника (bg_insured_type.csv, IT-27-2024): т. 31, без часове, без нето.
        t.setBgFillsT31(Boolean.TRUE);
        t.setBgNoHours(Boolean.TRUE);
        t.setBgNoNetPay(Boolean.TRUE);
      } else {
        t.setBgAppliesMod(Boolean.TRUE);
        t.setBgFillsT31(Boolean.TRUE);
      }
      return t;
    }

    @Override
    protected Set<String> gvrsEligibleCodes() {
      return Collections.singleton("01");
    }

    @Override
    protected BigDecimal netPayCapFor(LocalDate on) {
      return new BigDecimal("3750.00");
    }

    @Override
    protected List<BgCompensationParts.Share> compensationShares(
        BgPaySlip slip,
        BgCompensationParts.Carried carried,
        List<BgCompensationParts.Carried> all) {
      return BgCompensationParts.sharesOf(
          slip,
          carried,
          all,
          BgCompensationPartsTest.CALENDAR_2025,
          BgCompensationPartsTest.ceiling2025(slip));
    }

    @Override
    protected BgD1Record previousRecordOf(BgPaySlip slip, Period period, String code) {
      return null;
    }
  }

  private static BgD6CompensationLedger ledger(Period period, List<BgPaySlip> slips) {
    return BgD6CompensationLedger.of(
        period,
        slips,
        (slip, c, all) ->
            BgCompensationParts.sharesOf(
                slip,
                c,
                all,
                BgCompensationPartsTest.CALENDAR_2025,
                BgCompensationPartsTest.ceiling2025(slip)));
  }

  // ═══ строители ═══

  private static String line(List<BgD1Record> records) throws IOException {
    String file = new String(FILES.d1Bytes(records, SOURCE_FLAG), CP1251);
    return file.replace("\r\n\u001a", "");
  }

  /** Редът на Д6: заглавието, подадените блокове, празните до четири, източникът. */
  private static String d6Line(String[]... blocks) {
    List<String> f =
        new ArrayList<>(
            Arrays.asList("\"123456786\"", "\"ТЕСТОВА ФИРМА ЕООД\"", "0", "\"\"", "\"\"", "\"\""));
    for (String[] block : blocks) {
      f.addAll(Arrays.asList(block));
    }
    for (int i = blocks.length; i < BgD6FileAdapter.BLOCKS; i++) {
      f.addAll(Collections.nCopies(BgD6FileAdapter.BLOCK_WIDTH, ""));
    }
    f.add("\"" + SOURCE_FLAG + "\"");
    return String.join(",", f);
  }

  private static BgD1Declaration declaration(Period period) {
    BgD1Declaration d = new BgD1Declaration();
    d.setBgDataPeriod(period);
    return d;
  }

  private static BgD6Declaration d6(Company company, Period period) {
    BgD6Declaration d6 = new BgD6Declaration();
    d6.setCompany(company);
    d6.setBgPeriod(period);
    return d6;
  }

  private static Company company() {
    Partner partner = new Partner();
    partner.setName("Тестова фирма ЕООД");
    partner.setRegistrationCode("123456786");
    BgNkid nkid = new BgNkid();
    nkid.setCode("69.20");
    nkid.setBgMinIncomeRowCode("64");
    Company company = new Company();
    company.setPartner(partner);
    company.setBgNkid(nkid);
    return company;
  }

  private static Period period(LocalDate from, Long id) {
    Period p = new Period();
    p.setId(id);
    p.setName(String.format("%02d.%d", from.getMonthValue(), from.getYear()));
    p.setFromDate(from);
    p.setToDate(from.withDayOfMonth(from.lengthOfMonth()));
    return p;
  }

  /** P0041 — измислено лице: Петрова Мария Иванова, вид 01, III категория, с УПФ. */
  private static BgPaySlip slip(Company company, Period period, Long id) {
    Partner person = new Partner();
    person.setName("Петрова");
    person.setFirstName("Мария");
    person.setBgMiddleName("Иванова");
    Employee employee = new Employee();
    employee.setContactPartner(person);
    employee.setBgPersonalIdNumber("8506150012");
    employee.setBgPersonalIdType("1");
    BgNkpd nkpd = new BgNkpd();
    nkpd.setQualificationGroup(2);
    EmploymentContract contract = new EmploymentContract();
    contract.setStartDate(LocalDate.of(2020, 1, 15));
    contract.setBgInsuredTypeCode("01");
    contract.setPayCompany(company);
    contract.setEmployee(employee);
    contract.setBgNkpd(nkpd);
    contract.setBgHoursPerDay(new BigDecimal("8"));
    contract.setBgLegalHoursPerDay(new BigDecimal("8"));
    BgPaySlip s = new BgPaySlip();
    s.setId(id);
    s.setReference("P0041");
    s.setPeriod(period);
    s.setEmploymentContract(contract);
    s.setBgPaySlipLineList(new ArrayList<>());
    s.setBgWorkedDays(21);
    return s;
  }

  private static BgPaySlipLine salary(BigDecimal amount) {
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(100);
    line.setName("Основно възнаграждение");
    line.setBgSign(CREDIT);
    line.setBgFormsGrossWage(true);
    line.setBgIsTaxable(true);
    line.setBgIsInsuranceIncome(true);
    line.setBgAmount(amount);
    return line;
  }

  private static BgPaySlipLine contribution(
      int number,
      String sign,
      String fundCode,
      String d1Field,
      String rate,
      String amount,
      BigDecimal base) {
    BgContributionFund fund = new BgContributionFund();
    fund.setCode(fundCode);
    fund.setBgD1Field(d1Field);
    BgPaySlipLine line = new BgPaySlipLine();
    line.setBgLineNumber(number);
    line.setBgSign(sign);
    line.setBgContributionFund(fund);
    line.setBgRate(new BigDecimal(rate));
    line.setBgAmount(new BigDecimal(amount));
    line.setBgQuantity(base);
    line.setBgIsTaxable(false);
    line.setBgIsInsuranceIncome(false);
    return line;
  }

  private static List<BgPaySlip> list(BgPaySlip... slips) {
    return new ArrayList<>(Arrays.asList(slips));
  }

  private static void pin(String expected, BigDecimal actual, String what) {
    assertNotNull(actual, what + " — празно");
    assertEquals(0, new BigDecimal(expected).compareTo(actual), what + ": " + actual);
  }

  private static byte[] etalon(String name) throws IOException {
    try (InputStream in =
        BgCompensation27FileTest.class.getResourceAsStream("/d1/etalon/microinvest/" + name)) {
      assertNotNull(in, "еталонът липсва: " + name);
      return readAll(in);
    }
  }

  private static byte[] readAll(InputStream in) throws IOException {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[4096];
    int n;
    while ((n = in.read(buffer)) > 0) {
      out.write(buffer, 0, n);
    }
    return out.toByteArray();
  }
}
