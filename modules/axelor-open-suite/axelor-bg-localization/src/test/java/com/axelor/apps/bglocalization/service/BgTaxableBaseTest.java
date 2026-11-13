package com.axelor.apps.bglocalization.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axelor.apps.base.db.Period;
import com.axelor.apps.bglocalization.db.BgPaySlip;
import com.axelor.apps.bglocalization.db.BgPaySlipLine;
import com.axelor.apps.bglocalization.db.BgPaySlipLineType;
import com.axelor.apps.bglocalization.service.payroll.BgIncomeTaxService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ПРИСПАДАНЕТО НА ОБЛЕКЧЕНИЕТО — чистото правило и СЪГЛАСУВАНОСТТА на двете страни.
 *
 * <p><b>ЗДДФЛ чл. 42 ал. 2, ДОСЛОВНО:</b> „Месечната данъчна основа се определя, като облагаемият
 * доход по чл. 24 …, се намалява с удържаните от работодателя задължителни осигурителни вноски,
 * които са за сметка на физическото лице …“
 *
 * <p><b>Чл. 42 ал. 3, ДОСЛОВНО:</b> „Месечната данъчна основа се намалява със: 1. данъчното
 * облекчение за намалена работоспособност по чл. 18, ал. 2; 2. данъчното облекчение по чл. 19, ал.
 * 2, когато сумите са удържани от работодателя при изплащане на дохода от трудово правоотношение.“
 *
 * <p><b>⚠️ ВТОРАТА ПОЛОВИНА НА ТОЗИ ТЕСТ Е ЦЯЛАТА ПРИЧИНА ОБЛЕКЧЕНИЕТО ДА СЕ ЧЕТЕ НА ЕДНО
 * МЯСТО.</b> Заглавната сума и перото {@code pl_dod} се смятат от РАЗЛИЧЕН код — {@code recompute}
 * и {@code BgIncomeTaxService} — и {@code refuseTaxMismatch} блокира потвърждаването, щом се
 * разминат. Приспаднато само от едната страна, облекчението би направило ВЕРНИЯ фиш непотвърдим.
 * Затова тук се проверява не само че числото е вярно, а че ДВЕТЕ страни дават ЕДНО число.
 *
 * <p>Тече без Postgres, без Guice и без Hibernate.
 *
 * <p>Сверено срещу официалния ЗДДФЛ на 23.08.2026 г.
 */
class BgTaxableBaseTest {

  /** 337,45 EUR — месечното облекчение по чл. 18 ал. 2 (660 лв. / 1,95583). */
  private static final BigDecimal RELIEF = new BigDecimal("337.45");

  private static final BigDecimal RATE = new BigDecimal("10.0000");

  // ═══ ФИКСТУРИТЕ ═══════════════════════════════════════════════════════════

  private static BgPaySlipLineType type(int number, String code, String sign, boolean taxable) {
    BgPaySlipLineType t = new BgPaySlipLineType();
    t.setNumber(number);
    t.setCode(code);
    t.setName(code);
    t.setBgSign(sign);
    t.setBgIsTaxable(taxable);
    t.setBgIsInsuranceIncome(taxable);
    t.setBgIsPermanentCharacter(false);
    t.setActive(true);
    return t;
  }

  private static BgPaySlipLine line(BgPaySlipLineType t, String amount) {
    BgPaySlipLine l = new BgPaySlipLine();
    l.setBgPaySlipLineType(t);
    l.setBgLineNumber(t.getNumber());
    l.setName(t.getName());
    l.setBgSign(t.getBgSign());
    l.setBgIsTaxable(t.getBgIsTaxable());
    l.setBgIsInsuranceIncome(t.getBgIsInsuranceIncome());
    l.setBgAmount(new BigDecimal(amount));
    return l;
  }

  /**
   * Заплата 1000,00 (облагаема) и лична вноска 137,80 от 5xx DEBIT — основата по ал. 2 е 862,20.
   */
  private static List<BgPaySlipLine> salaryAndContribution() {
    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(line(type(100, "pl_osnovna", BgPaySlipResolver.CREDIT, true), "1000.00"));
    lines.add(line(type(501, "pl_vn_pension_lice", BgPaySlipResolver.DEBIT, false), "137.80"));
    return lines;
  }

  // ═══ 1 · ЧИСТОТО ПРАВИЛО ══════════════════════════════════════════════════

  @Test
  @DisplayName("основа 862,20 − облекчение 337,45 = 524,75")
  void theReliefIsSubtractedFromTheBase() {
    List<BgPaySlipLine> lines = salaryAndContribution();

    assertEquals(
        0,
        new BigDecimal("862.20").compareTo(BgPaySlipResolver.taxBase(lines)),
        "⚠️ taxBase остава ал. 2 — облагаеми минус лични вноски, БЕЗ облекчението");
    assertEquals(
        0,
        new BigDecimal("524.75").compareTo(BgPaySlipResolver.taxableBase(lines, RELIEF)),
        "taxableBase е ал. 3 — същото минус облекчението");
  }

  @Test
  @DisplayName("без облекчение (нула) основата не се мени")
  void aZeroReliefChangesNothing() {
    List<BgPaySlipLine> lines = salaryAndContribution();

    assertEquals(
        0,
        BgPaySlipResolver.taxBase(lines)
            .compareTo(BgPaySlipResolver.taxableBase(lines, BigDecimal.ZERO)),
        "нула облекчение трябва да дава СЪЩОТО число като голата основа");
  }

  /**
   * ⚠️ ОТРИЦАТЕЛНА ОСНОВА НЕ СЪЩЕСТВУВА. Неограничена, тя би дала отрицателен данък — тоест перо,
   * което ДОБАВЯ пари към нетото.
   */
  @Test
  @DisplayName("⚠️ облекчение, по-голямо от основата → 0,00, не отрицателно")
  void aReliefLargerThanTheBaseClampsToZero() {
    List<BgPaySlipLine> lines = new ArrayList<>();
    lines.add(line(type(100, "pl_osnovna", BgPaySlipResolver.CREDIT, true), "300.00"));

    BigDecimal base = BgPaySlipResolver.taxableBase(lines, RELIEF);

    assertEquals(0, BigDecimal.ZERO.compareTo(base), "300,00 − 337,45 се ограничава до нула");
    assertTrue(base.signum() >= 0, "⚠️ отрицателна основа би дала данък, който ПЛАЩА на лицето");
  }

  /**
   * ⚠️ {@code null} СЕ ЧЕТЕ КАТО „БЕЗ НАМАЛЕНИЕ“, И ТОВА Е НАРОЧНО.
   *
   * <p>{@code null} значи ОТКАЗ, а отказът е работа на генератора. Ако тук даваше нула ОСНОВА,
   * заглавният данък също щеше да стане нула и щеше да СЪВПАДНЕ с липсващото перо — {@code
   * refuseTaxMismatch} би замълчал и фишът щеше да се потвърди БЕЗ данък. Прочетено като „без
   * намаление“, заглавната сума остава пълният данък, перо няма и пазачът блокира.
   */
  @Test
  @DisplayName("⚠️ null облекчение → пълната основа, за да се задейства пазачът, а не да замълчи")
  void aNullReliefLeavesTheFullBase() {
    List<BgPaySlipLine> lines = salaryAndContribution();

    assertEquals(
        0,
        new BigDecimal("862.20").compareTo(BgPaySlipResolver.taxableBase(lines, null)),
        "⚠️ нула ОСНОВА тук би направила фиш без данък потвърдим");
  }

  // ═══ 2 · СЪГЛАСУВАНОСТТА НА ДВЕТЕ СТРАНИ ══════════════════════════════════

  /** Услуга с подменени четци — ставката и облекчението идват като константи. */
  private static final class Service extends BgPaySlipService {

    private final BigDecimal relief;

    Service(BigDecimal relief) {
      this.relief = relief;
    }

    @Override
    protected BigDecimal incomeTaxRate(BgPaySlip slip) {
      return RATE;
    }

    @Override
    protected BigDecimal disabilityRelief(BgPaySlip slip) {
      return relief;
    }
  }

  /** Генератор с подменен речник — ДОСЛОВНО копие на идиома в BgIncomeTaxGeneratorTest. */
  private static final class Generator extends BgIncomeTaxService {

    @Override
    protected BgPaySlipLineType lineType(String code) {
      return BgIncomeTaxService.LINE_INCOME_TAX.equals(code)
          ? type(700, BgIncomeTaxService.LINE_INCOME_TAX, BgPaySlipResolver.DEBIT, false)
          : null;
    }

    @Override
    protected BgPaySlipLine lineFrom(BgPaySlipLineType t, BigDecimal amount) {
      return line(t, amount.toPlainString());
    }
  }

  private static BgPaySlip slipWith(List<BgPaySlipLine> lines) {
    BgPaySlip slip = new BgPaySlip();
    slip.setStatusSelect(BgPaySlipResolver.DRAFT);
    Period period = new Period();
    period.setFromDate(LocalDate.of(2026, 3, 1));
    period.setToDate(LocalDate.of(2026, 3, 31));
    slip.setPeriod(period);
    slip.setBgPaySlipLineList(new ArrayList<>(lines));
    return slip;
  }

  /**
   * ⚠️ СВИДЕТЕЛЯТ, КОЙТО ПАЗИ ВЕРНИЯ ФИШ ДА НЕ СТАНЕ НЕПОТВЪРДИМ.
   *
   * <p>Заглавната сума ({@code recompute}) и перото ({@code BgIncomeTaxService}) се смятат от
   * различен код. Приспаднато само от едната страна, облекчението би ги разминало с 33,74 и {@code
   * refuseTaxMismatch} щеше да блокира фиш, който е напълно верен — точно обратното на това, за
   * което пазачът съществува.
   */
  @Test
  @DisplayName("⚠️ заглавната сума и перото pl_dod дават ЕДНО число с облекчението — пазачът мълчи")
  void theHeaderAndTheLineAgreeWithTheRelief() {
    Service service = new Service(RELIEF);
    Generator generator = new Generator();
    BgPaySlip slip = slipWith(salaryAndContribution());

    generator.generate(slip, RATE, RELIEF);
    service.recompute(slip);

    assertEquals(
        0,
        new BigDecimal("524.75").compareTo(slip.getBgTaxBase()),
        "⚠️ заглавната ОСНОВА е намалената. Намерено: " + slip.getBgTaxBase());
    assertEquals(
        0,
        new BigDecimal("52.48").compareTo(slip.getBgTaxAmount()),
        "⚠️ заглавният ДАНЪК е върху намалената основа. Намерено: " + slip.getBgTaxAmount());
    assertEquals(
        0,
        slip.getBgTaxAmount()
            .compareTo(BgPaySlipResolver.taxFromLines(slip.getBgPaySlipLineList())),
        "⚠️ двете страни трябва да съвпадат ТОЧНО");
    assertNull(
        BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList()),
        "⚠️ пазачът трябва да МЪЛЧИ — инак облекчението прави верния фиш непотвърдим");
  }

  /** ⚠️ И БЕЗ облекчение двете страни продължават да съвпадат — заварената съгласуваност стои. */
  @Test
  @DisplayName("без облекчение двете страни пак съвпадат: основа 862,20, данък 86,22")
  void theHeaderAndTheLineAgreeWithoutTheRelief() {
    Service service = new Service(BigDecimal.ZERO);
    BgPaySlip slip = slipWith(salaryAndContribution());

    new Generator().generate(slip, RATE, BigDecimal.ZERO);
    service.recompute(slip);

    assertEquals(0, new BigDecimal("862.20").compareTo(slip.getBgTaxBase()));
    assertEquals(0, new BigDecimal("86.22").compareTo(slip.getBgTaxAmount()));
    assertNull(
        BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList()));
  }

  /**
   * ⚠️ ПРИ ОТКАЗ ПАЗАЧЪТ ТРЯБВА ДА СЕ ЗАДЕЙСТВА, а не да замълчи.
   *
   * <p>Липсващият параметър дава {@code null}: генераторът отказва и перо няма, а заглавната сума
   * остава ПЪЛНИЯТ данък. Двете се разминават и фишът остава непотвърдим — точно каквото трябва.
   * Ако {@code taxableBase} четеше {@code null} като нула основа, и двете щяха да са нула и фиш БЕЗ
   * данък щеше да мине за верен.
   */
  @Test
  @DisplayName("⚠️ при ОТКАЗ (null облекчение) пазачът БЛОКИРА — 86,22 срещу липсващо перо")
  void aRefusedReliefLeavesTheSlipUnconfirmable() {
    Service service = new Service(null);
    BgPaySlip slip = slipWith(salaryAndContribution());

    new Generator().generate(slip, RATE, null);
    service.recompute(slip);

    assertEquals(
        0,
        BigDecimal.ZERO.compareTo(BgPaySlipResolver.taxFromLines(slip.getBgPaySlipLineList())),
        "генераторът отказа — перо няма");
    assertEquals(
        0,
        new BigDecimal("86.22").compareTo(slip.getBgTaxAmount()),
        "⚠️ заглавната сума остава ПЪЛНИЯТ данък. Намерено: " + slip.getBgTaxAmount());
    assertTrue(
        BgPaySlipResolver.refuseTaxMismatch(slip.getBgTaxAmount(), slip.getBgPaySlipLineList())
            != null,
        "⚠️ мълчание тук значи потвърден фиш БЕЗ данък");
  }
}
