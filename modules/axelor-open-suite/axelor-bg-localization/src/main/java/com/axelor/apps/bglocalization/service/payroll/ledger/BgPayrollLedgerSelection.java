package com.axelor.apps.bglocalization.service.payroll.ledger;

import java.math.BigDecimal;

/**
 * КОЯ ВЕДОМОСТ СЕ ПЕЧАТА — изборът от екрана.
 *
 * <p><b>⚠️ ТОВА НЕ Е {@link BgPayrollLedgerVariant} С ОЩЕ ДВЕ СТОЙНОСТИ, И РАЗЛИКАТА НЕ Е
 * КОЗМЕТИЧНА.</b> Разрезът е РАЗБИВАНЕ: всеки фиш има точно един и сборът на петте разреза е целият
 * период. Изборът тук е ФИЛТЪР и две от стойностите му нарочно не са разрез:
 *
 * <ul>
 *   <li>{@link #ALL} пуска всичко — не е вид правоотношение;
 *   <li>{@link #ADVANCE} реже НАПРЕЧНО на разрезите. „Ведомост за аванси“ на Микроинвест стои до
 *       останалите четири, но не отговаря на техния въпрос: аванс може да има и нает, и управител
 *       по ДУК. Сложен като шеста стойност на разреза, той щеше да ИЗВАДИ тези хора от ведомостта
 *       на трудовите договори — тоест едно и също лице щеше да липсва от листа, на който се
 *       сверява, защото е получило аванс.
 * </ul>
 *
 * <p>Оттам и двата различни свидетеля: разбиването се проверява за ПЪЛНОТА и НЕПРЕСИЧАНЕ, а изборът
 * — за това, че връща подмножество на същите редове.
 */
public enum BgPayrollLedgerSelection {

  /** Трудови договори. */
  TD("Трудови договори", BgPayrollLedgerVariant.EMPLOYMENT),

  /** Граждански договори. */
  GD("Граждански договори", BgPayrollLedgerVariant.CIVIL),

  /** ДУК и приравнени. */
  DUK("ДУК и приравнени (вид осигурен 10)", BgPayrollLedgerVariant.MANAGEMENT),

  /** Самоосигуряващи се. */
  SOL("Самоосигуряващи се", BgPayrollLedgerVariant.SELF_INSURED),

  /** Фишовете без определен вид осигурен — видими нарочно. */
  OTHER("Без определен вид осигурен", BgPayrollLedgerVariant.UNCLASSIFIED),

  /** Целият период, без разрез. */
  ALL("Всички (целият период)", null),

  /**
   * Само лицата с аванс или частично плащане — НАПРЕЧНО на разрезите.
   *
   * <p>⚠️ Границата е {@code signum() != 0}, а не {@code > 0}: отрицателен аванс е поправка на
   * надплатен аванс и той също принадлежи на този лист. Прочетен като „> 0“, редът щеше да изчезне
   * точно когато е интересен.
   */
  ADVANCE("Аванси (всички видове договори)", null);

  private final String label;
  private final BgPayrollLedgerVariant variant;

  BgPayrollLedgerSelection(String label, BgPayrollLedgerVariant variant) {
    this.label = label;
    this.variant = variant;
  }

  /** Заглавието върху хартията и в падащия списък. */
  public String getLabel() {
    return label;
  }

  /** Разрезът зад този избор, или {@code null} за {@link #ALL} и {@link #ADVANCE}. */
  public BgPayrollLedgerVariant getVariant() {
    return variant;
  }

  /** Влиза ли този ред в избраната ведомост. */
  public boolean accepts(BgPayrollLedgerRow row) {
    if (row == null) {
      return false;
    }
    if (this == ALL) {
      return true;
    }
    if (this == ADVANCE) {
      BigDecimal advance = row.getAdvance();
      return advance != null && advance.signum() != 0;
    }
    return variant == row.getVariant();
  }

  /** Изборът по името му; непознато и празно дават {@link #TD} — ведомостта по подразбиране. */
  public static BgPayrollLedgerSelection of(String name) {
    if (name != null) {
      for (BgPayrollLedgerSelection selection : values()) {
        if (selection.name().equals(name.trim())) {
          return selection;
        }
      }
    }
    return TD;
  }
}
