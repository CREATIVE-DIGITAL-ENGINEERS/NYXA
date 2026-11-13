package com.axelor.apps.bglocalization.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgGarnishmentScale;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.BgGarnishmentOutcome;
import com.axelor.apps.bglocalization.service.BgGarnishmentResolver;
import com.axelor.apps.bglocalization.service.BgGarnishmentService;
import com.axelor.apps.bglocalization.service.BgPaySlipResolver;
import com.axelor.apps.bglocalization.service.BgPaySlipService;
import com.axelor.apps.bglocalization.testsupport.BgSkip;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ИЗДРЪЖКА ЗА МИНАЛО ВРЕМЕ — базовата половина: „ДОСТАВЕНО ли е перото и работи ли фишът с него“.
 *
 * <p><b>Защо втора половина.</b> {@code BgMaintenancePastDueTest} пита дали АЛГОРИТЪМЪТ различава
 * двете издръжки и тече без база. Този пита другото: стигнал ли е редът PLT-831 до базата и минава
 * ли ЦЕЛИЯТ път — перо на фиша → четците на 8xx → {@code assess} → текстът на отказа — върху
 * ДОСТАВЕНАТА скала и ДОСТАВЕНАТА МРЗ. Слети, отговорът „App Install не е пускан“ би маскирал
 * отговора „разграничението не работи“.
 *
 * <p><b>⚠️ НОМЕРЪТ НА ПЕРОТО СЕ ЧЕТЕ ОТ БАЗАТА, НЕ СЕ ЗАКОВАВА В ТЕСТА.</b> Оттам иде и цялата
 * стойност: seed с номер 832 би минал всеки файлов тест, а {@code BgPaySlipResolver} чете 831 —
 * тоест перото щеше да съществува, да се отпечатва на фиша и да НЕ участва в сметката по чл. 446.
 * Мълчалива грешка за точната сума, която трябва да се ограничи.
 *
 * <p><b>⚠️ ПОДМЕНЕНИ СА САМО ЧЕТЦИТЕ.</b> {@code BgPaySlipService#garnishmentRefusal} и {@code
 * BgGarnishmentResolver#assess} се викат ИСТИНСКИ; през JDBC се четат само скалата, МРЗ и редът на
 * перото, защото тези тестове нямат контейнер.
 *
 * <p><b>Пропуска се при състояния на СРЕДАТА, всяко с обявена причина</b>: няма достъпна база;
 * речникът с редове на фиша още го няма; редът {@code pl_izdrazhka_minalo_vreme} го няма (App
 * Install не е пускан след този комит); липсва скалата или МРЗ за периода.
 *
 * <p>Основание: ГПК чл. 446 ал. 4, дословно, сверено срещу официалния ГПК (justice.government.bg)
 * на 24.08.2026 г. Текстът стои в javadoc-а на {@code BgGarnishmentResolver#assess} и не се
 * преписва тук. ⚠️ Редът вътре в тавана (просрочената преди запора) стъпва на ЗЗД чл. 136 ал. 1 т.
 * 5, която НЕ е сверена срещу официален ЗЗД в тази среда.
 */
class BgMaintenancePastDueDbTest {

  private static final String LINE_TYPES = "bglocalization_bg_pay_slip_line_type";

  /** Кодът на перото — стабилният ключ, по който seed-ът и този тест се срещат. */
  private static final String PAST_DUE_CODE = "pl_izdrazhka_minalo_vreme";

  private static final String CURRENT_CODE = "pl_izdrazhka";

  /** Март 2026 г. — периодът, за който скалата и МРЗ са в сила. */
  private static final LocalDate MARCH = BgGarnishmentScaleDbTest.MARCH;

  // ═══ ШЕВОВЕТЕ КЪМ БАЗАТА ══════════════════════════════════════════════════

  /**
   * ⚠️ ЕДИН ПРОЧИТ, НЕ ВТОРО КОПИЕ. Връзката, скалата и МРЗ се четат през {@code
   * BgGarnishmentScaleDbTest} — собствени заявки тук биха се разминали с неговите при първата
   * промяна на темпоралния филтър и двата теста щяха да мерят различни редове.
   */
  private static Connection open() {
    return BgGarnishmentScaleDbTest.open();
  }

  private static String one(Connection c, String sql) throws Exception {
    try (Statement st = c.createStatement();
        ResultSet rs = st.executeQuery(sql)) {
      return rs.next() ? rs.getString(1) : null;
    }
  }

  private static String cell(Connection c, String column, String code) throws Exception {
    return one(c, "SELECT " + column + " FROM " + LINE_TYPES + " WHERE code = '" + code + "'");
  }

  /** Редът на перото трябва да е ДОСТАВЕН — инак твърдението не е проверено, а средата е стара. */
  private static void requireDeliveredLine(Connection c) throws Exception {
    String table =
        one(
            c,
            "SELECT count(*) FROM information_schema.tables WHERE table_schema = current_schema()"
                + " AND table_name = '"
                + LINE_TYPES
                + "'");
    if ("0".equals(table)) {
      BgSkip.because(
          "таблицата "
              + LINE_TYPES
              + " още я НЯМА — ddl=update не е минавал с домейна на речника. Състояние на средата,"
              + " не на кода.");
    }
    String row =
        one(c, "SELECT count(*) FROM " + LINE_TYPES + " WHERE code = '" + PAST_DUE_CODE + "'");
    if ("0".equals(row)) {
      BgSkip.because(
          "перото „"
              + PAST_DUE_CODE
              + "“ (831, издръжка за минало време) го НЯМА в базата — App Install не е пускан след"
              + " този комит. Дотогава фиш с просрочена издръжка НЯМА как да се въведе, тоест"
              + " средата е безопасна, просто твърдението тук не е проверено. Пусни"
              + " App Install. ⚠️ Липсата се вижда и от"
              + " BgPaySlipLineTypeManifestTest, който брои редовете и ПАДА, а не се пропуска.");
    }
    BgGarnishmentScaleDbTest.requireDeliveredScale(c);
  }

  // ═══ УСЛУГАТА С ЧЕТЦИ, ВЪРЗАНИ ЗА БАЗАТА ══════════════════════════════════

  private static final class Service extends BgPaySlipService {

    private final BigDecimal minWage;
    private final List<BgGarnishmentScale> scale;

    Service(BigDecimal minWage, List<BgGarnishmentScale> scale) {
      this.minWage = minWage;
      this.scale = scale;
    }

    @Override
    protected BgGarnishmentService.Status childrenStatus(BgPaySlip slip, LocalDate date) {
      return BgGarnishmentService.Status.WITHOUT_CHILDREN;
    }

    @Override
    protected BigDecimal payrollParameter(String code, LocalDate date) {
      return PARAM_MIN_WAGE.equals(code) ? minWage : null;
    }

    @Override
    protected List<BgGarnishmentScale> garnishmentScale(LocalDate date) {
      return scale;
    }

    /** Врата към защитения пазач — методът е шев на услугата, а тестът е в друг пакет. */
    String refusalFor(BgPaySlip slip) {
      return garnishmentRefusal(slip);
    }
  }

  // ═══ ФИКСТУРИТЕ — С НОМЕРА, ПРОЧЕТЕНИ ОТ БАЗАТА ═══════════════════════════

  private static BgPaySlipLine line(int number, String sign, String amount) {
    BgPaySlipLineType type = new BgPaySlipLineType();
    type.setNumber(number);
    type.setBgSign(sign);
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgPaySlipLineType(type);
    l.setBgLineNumber(number);
    l.setBgSign(sign);
    l.setBgAmount(new BigDecimal(amount));
    l.setBgIsTaxable(false);
    l.setBgIsInsuranceIncome(false);
    return l;
  }

  private static BgPaySlip slip(String salary, BgPaySlipLine... deductions) {
    BgPaySlip s = new BgPaySlip();
    s.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period p = new Period();
    p.setFromDate(MARCH);
    p.setToDate(MARCH.withDayOfMonth(31));
    s.setPeriod(p);
    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(line(100, BgPaySlipResolver.CREDIT, salary));
    Collections.addAll(lines, deductions);
    s.setBgPaySlipLineList(lines);
    return s;
  }

  // ═══ 1 · ДОСТАВЕНОТО ПЕРО ═════════════════════════════════════════════════

  /**
   * ⚠️ ДВЕ ПЕРА, НЕ ЕДНО, И НОМЕРАТА ИМ СЪВПАДАТ С ТОВА, КОЕТО КОДЪТ ЧЕТЕ. Разминат ли се, перото
   * съществува и не участва в сметката — най-тихият възможен дефект тук.
   */
  @Test
  @DisplayName("БАЗА · перото 831 е ДОСТАВЕНО, отделно от 830, с номера, които кодът чете")
  void theDeliveredCatalogueHasBothMaintenanceLines() throws Exception {
    try (Connection c = open()) {
      requireDeliveredLine(c);

      assertEquals(
          String.valueOf(BgPaySlipResolver.LINE_MAINTENANCE_PAST_DUE),
          cell(c, "line_number", PAST_DUE_CODE),
          "⚠️ номерът в базата трябва да е ТОЧНО този, който BgPaySlipResolver чете — инак перото се"
              + " отпечатва на фиша и НЕ влиза в тавана по чл. 446");
      assertEquals(
          String.valueOf(BgPaySlipResolver.LINE_MAINTENANCE),
          cell(c, "line_number", CURRENT_CODE),
          "текущата издръжка остава на 830");
      assertEquals("DEDUCTIONS", cell(c, "bg_group", PAST_DUE_CODE), "перото е удръжка");
      assertEquals("DEBIT", cell(c, "bg_sign", PAST_DUE_CODE), "и посоката му е дебит");

      String basis = cell(c, "bg_legal_basis", PAST_DUE_CODE);
      assertNotNull(basis, "⚠️ перото без правно основание не казва по кой ред се удържа");
      assertTrue(basis.contains("446"), "основанието е чл. 446 ал. 4 ГПК. Намерено: " + basis);

      String note = cell(c, "note", PAST_DUE_CODE);
      assertNotNull(note, "бележката носи правилото — тя е онова, което операторът чете");
      assertTrue(
          note.contains("минало време"),
          "⚠️ бележката трябва да казва КОЕ перо е това. Намерено: " + note);
      assertTrue(
          note.contains("остатъка"),
          "⚠️ и че се удържа ВЪРХУ ОСТАТЪКА, а не цяло като 830. Намерено: " + note);
    }
  }

  // ═══ 2 · КРАЙ-ДО-КРАЙ ВЪРХУ ДОСТАВЕНИТЕ РЕДОВЕ ════════════════════════════

  /**
   * ЦЕЛИЯТ ПЪТ, ВЪРХУ ДОСТАВЕНОТО: фиш с перата 830, 831 и 820 → четците на 8xx → {@code assess} →
   * пазачът. Нето 1500,00, текуща 200,00 → основа 1300,00, таван 650,00 (праг 2, 1/2). Просрочената
   * 300,00 и запорът 350,00 изчерпват тавана ТОЧНО — пазачът мълчи.
   */
  @Test
  @DisplayName("БАЗА · край-до-край: 830=200, 831=300, 820=350 при таван 650 → пазачът мълчи")
  void theWholeChainPassesWhenBothFitUnderTheDeliveredCeiling() throws Exception {
    try (Connection c = open()) {
      requireDeliveredLine(c);
      BigDecimal minWage = BgGarnishmentScaleDbTest.minWageFromDb(c, MARCH);
      List<BgGarnishmentScale> scale = BgGarnishmentScaleDbTest.scaleFromDb(c, MARCH);
      assertEquals(
          0,
          new BigDecimal("620.20").compareTo(minWage),
          "⚠️ котвата: МРЗ за март 2026 г. трябва да е 620,20 EUR. Намерено: " + minWage);

      int pastDueNumber = Integer.parseInt(cell(c, "line_number", PAST_DUE_CODE));
      int currentNumber = Integer.parseInt(cell(c, "line_number", CURRENT_CODE));

      BgPaySlip slip =
          slip(
              "1500.00",
              line(currentNumber, BgPaySlipResolver.DEBIT, "200.00"),
              line(pastDueNumber, BgPaySlipResolver.DEBIT, "300.00"),
              line(BgPaySlipResolver.LINE_GARNISHMENT, BgPaySlipResolver.DEBIT, "350.00"));

      assertEquals(
          0,
          new BigDecimal("300.00")
              .compareTo(BgPaySlipResolver.pastDueMaintenanceWithheld(slip.getBgPaySlipLineList())),
          "⚠️ четецът трябва да НАМЕРИ реда по номера от базата — иначе всичко по-долу е нула");

      assertNull(
          new Service(minWage, scale).refusalFor(slip),
          "300,00 + 350,00 = 650,00 изчерпват тавана точно");
    }
  }

  /**
   * ⚠️ ЗЛАТЕН 2 ВЪРХУ ДОСТАВЕНОТО: просрочена 700,00 изяжда целия таван, за запора остава нула — и
   * отказът трябва да каже И двете числа, И реда между тях.
   */
  @Test
  @DisplayName("БАЗА · 831=700 + 820=500 → отказ: 831 до 650,00, запор до 0,00, редът назован")
  void theRefusalOnDeliveredRowsNamesBothLinesAndTheOrder() throws Exception {
    try (Connection c = open()) {
      requireDeliveredLine(c);
      BigDecimal minWage = BgGarnishmentScaleDbTest.minWageFromDb(c, MARCH);
      List<BgGarnishmentScale> scale = BgGarnishmentScaleDbTest.scaleFromDb(c, MARCH);
      int pastDueNumber = Integer.parseInt(cell(c, "line_number", PAST_DUE_CODE));
      int currentNumber = Integer.parseInt(cell(c, "line_number", CURRENT_CODE));

      String refusal =
          new Service(minWage, scale)
              .refusalFor(
                  slip(
                      "1500.00",
                      line(currentNumber, BgPaySlipResolver.DEBIT, "200.00"),
                      line(pastDueNumber, BgPaySlipResolver.DEBIT, "700.00"),
                      line(BgPaySlipResolver.LINE_GARNISHMENT, BgPaySlipResolver.DEBIT, "500.00")));

      assertNotNull(refusal, "⚠️ мълчание тук значи 1200,00 удържани при таван 650,00");
      assertTrue(refusal.contains("650.00"), "клампата на 831. " + refusal);
      assertTrue(
          refusal.contains("50.00"), "несъбраното от издръжката: 700,00 − 650,00. " + refusal);
      assertTrue(refusal.contains("500.00"), "несъбраното от запора. " + refusal);
      assertTrue(refusal.contains("831") && refusal.contains("821"), "двете пера. " + refusal);
      assertTrue(refusal.contains("136"), "основанието за реда. " + refusal);
    }
  }

  /**
   * ⚠️ FAIL-FIRST ВЪРХУ ДОСТАВЕНИТЕ РЕДОВЕ. Същите пари, въведени НА ЕДНО перо (830 = 900,00),
   * тоест поведението преди разграничението: основата пада на 600,00, таванът става нула и на
   * лицето остават 600,00 — ПОД МРЗ 620,20. Разграничени, остават 650,00.
   *
   * <p>Свидетелят печата двете числа, защото разликата е точно онова, което ал. 4 забранява на
   * всяко друго вземане освен присъдената ТЕКУЩА издръжка.
   */
  @Test
  @DisplayName("⚠️ БАЗА · FAIL-FIRST: слято остават 600,00 (под МРЗ), разграничено — 650,00")
  void theDeliveredScaleShowsTheFloorBrokenByTheMergedEntry() throws Exception {
    try (Connection c = open()) {
      requireDeliveredLine(c);
      BigDecimal minWage = BgGarnishmentScaleDbTest.minWageFromDb(c, MARCH);
      List<BgGarnishmentScale> scale = BgGarnishmentScaleDbTest.scaleFromDb(c, MARCH);

      BgGarnishmentOutcome merged =
          BgGarnishmentResolver.assess(
              new BigDecimal("1500.00"),
              new BigDecimal("900.00"),
              BigDecimal.ZERO,
              new BigDecimal("500.00"),
              BigDecimal.ZERO,
              minWage,
              false,
              scale);
      BgGarnishmentOutcome split =
          BgGarnishmentResolver.assess(
              new BigDecimal("1500.00"),
              new BigDecimal("200.00"),
              new BigDecimal("700.00"),
              new BigDecimal("500.00"),
              BigDecimal.ZERO,
              minWage,
              false,
              scale);

      BigDecimal remainingMerged =
          merged
              .base
              .subtract(merged.allowedPastDueMaintenance)
              .subtract(merged.allowedGarnishment);
      BigDecimal remainingSplit =
          split.base.subtract(split.allowedPastDueMaintenance).subtract(split.allowedGarnishment);

      assertEquals(
          0,
          new BigDecimal("600.00").compareTo(remainingMerged),
          "слято на лицето остават 600,00. Намерено: " + remainingMerged);
      assertTrue(
          remainingMerged.compareTo(minWage) < 0,
          "⚠️ ТОВА Е ДЕФЕКТЪТ, върху доставената скала: " + remainingMerged + " < МРЗ " + minWage);
      assertEquals(
          0,
          new BigDecimal("650.00").compareTo(remainingSplit),
          "разграничено остават 650,00. Намерено: " + remainingSplit);
      assertTrue(
          remainingSplit.compareTo(minWage) >= 0,
          "разграничението спазва пода: " + remainingSplit + " ≥ " + minWage);
    }
  }
}
