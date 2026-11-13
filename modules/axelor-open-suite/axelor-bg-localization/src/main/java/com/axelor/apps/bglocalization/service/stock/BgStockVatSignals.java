package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.stock.db.StockCorrectionReason;
import com.axelor.apps.stock.db.StockMove;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ДДС сигналите на складово движение — само ПРЕДУПРЕЖДЕНИЕ. Протоколът е отделна задача. Чиста
 * функция.
 *
 * <p>Сверено със ЗДДС до ДВ бр. 115/2025:
 *
 * <ul>
 *   <li>чл. 6 ал. 3 т. 1 — отделянето за лично ползване е възмездна доставка, ако при придобиването
 *       е приспаднат кредит;
 *   <li>чл. 6 ал. 3 т. 2 — безвъзмездното прехвърляне на трети лица също, при същото условие; ал. 4
 *       т. 2 изключва мострите и стоките с незначителна стойност с рекламна цел, а т. 1 — работното
 *       облекло и ЛПС (затова причината им е без сигнал);
 *   <li>чл. 79 ал. 1 — брак, липса, унищожаване: корекция на ползвания кредит с протокол по ал. 4,
 *       освен в случаите на чл. 80 ал. 2.
 * </ul>
 *
 * <p>Сигналът идва от причината за корекция ({@code bgVatSignalSelect}), а без такава — от кода по
 * „Stock movement“: 150 → чл. 6 ал. 3 т. 2; 120, 160, 170 → чл. 79. Отрицателната корекция от
 * инвентаризация е 120 по посоката си.
 */
public final class BgStockVatSignals {

  public static final String NONE = "NONE";
  public static final String ART_6_3_1 = "ART_6_3_1";
  public static final String ART_6_3_2 = "ART_6_3_2";
  public static final String ART_6_3_2_SAMPLES = "ART_6_3_2_SAMPLES";
  public static final String ART_79 = "ART_79";

  static final String TEXT_ART_79 =
      "Възможна корекция на данъчния кредит по чл. 79, ал. 1 ЗДДС с протокол по ал. 4, освен в"
          + " случаите на чл. 80, ал. 2, т. 1–4 (непреодолима сила, аварии, естествени фири в норма,"
          + " технологичен брак в норма, изтекъл срок в обичайни размери, обективно неизползваеми"
          + " стоки и др.).";

  static final String TEXT_ART_6_3_2 =
      "Безвъзмездното прехвърляне на стоки на трети лица е възмездна доставка по чл. 6, ал. 3, т."
          + " 2 ЗДДС, ако при производството, вноса или придобиването им е приспаднат данъчен кредит"
          + " — данъкът се начислява с протокол.";

  static final String TEXT_ART_6_3_2_SAMPLES =
      TEXT_ART_6_3_2
          + " Не се прилага за мостри и за стоки с незначителна стойност, предоставени с рекламна"
          + " цел (чл. 6, ал. 4, т. 2 ЗДДС).";

  static final String TEXT_ART_6_3_1 =
      "Отделянето на стоки за лично ползване или за цели, различни от икономическата дейност, е"
          + " възмездна доставка по чл. 6, ал. 3, т. 1 ЗДДС, ако при производството, вноса или"
          + " придобиването им е приспаднат данъчен кредит — данъкът се начислява с протокол.";

  /** Сигнал → текст на предупреждението. */
  public static final Map<String, String> TEXTS;

  static {
    Map<String, String> m = new LinkedHashMap<>();
    m.put(ART_6_3_1, TEXT_ART_6_3_1);
    m.put(ART_6_3_2, TEXT_ART_6_3_2);
    m.put(ART_6_3_2_SAMPLES, TEXT_ART_6_3_2_SAMPLES);
    m.put(ART_79, TEXT_ART_79);
    TEXTS = Collections.unmodifiableMap(m);
  }

  private BgStockVatSignals() {}

  /**
   * Сигналът по код и сигнал на причината, или {@code null}. Изричното „NONE“ на причината гаси
   * сигнала на кода.
   */
  public static String signal(String movementCode, String reasonSignal) {
    if (reasonSignal != null && !reasonSignal.trim().isEmpty()) {
      return NONE.equals(reasonSignal) ? null : reasonSignal;
    }
    if (movementCode == null) {
      return null;
    }
    switch (movementCode) {
      case "150":
        return ART_6_3_2;
      case "120":
      case "160":
      case "170":
        return ART_79;
      default:
        return null;
    }
  }

  /** Сигналът на движението: ръчен код, после причина, после отрицателна инвентаризация. */
  public static String signalOf(StockMove move) {
    if (move == null) {
      return null;
    }
    String manual = move.getBgSaftMovementCode();
    if (manual != null && !manual.trim().isEmpty()) {
      return signal(manual.trim(), null);
    }
    StockCorrectionReason reason = move.getStockCorrectionReason();
    if (reason != null) {
      return signal(reason.getBgStockMovementCode(), reason.getBgVatSignalSelect());
    }
    if (move.getInventory() != null
        && BgStockMovementCodes.directionOf(move) == BgStockMovementCodes.Direction.OUT) {
      return signal("120", null);
    }
    return null;
  }

  /** Текстът на предупреждението за движението, или {@code null}. */
  public static String warningOf(StockMove move) {
    String signal = signalOf(move);
    return signal == null ? null : TEXTS.get(signal);
  }
}
