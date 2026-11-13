package com.axelor.apps.bglocalization.service.stock;

import com.axelor.apps.bglocalization.service.stock.BgStockMovementCodes.Direction;

/**
 * Кодът по „Stock movement“ (SAF-T SD.MG.10) на реализирано движение — за изписването (ИЗП) и за
 * SAF-T. Чиста функция.
 *
 * <p>Предимството е на S0: ръчният код на движението бие кода на причината за корекция, а той бие
 * автоматичния. Автоматичният идва от посоката и от РОЛЯТА на насрещната локация в StockConfig на
 * дружеството:
 *
 * <ul>
 *   <li>трансфер между собствени локации — 80;
 *   <li>вход от доставчика — 10, от клиента — 40 (връщане), от инвентаризацията — 110, от
 *       производството — 20;
 *   <li>изход към клиента — 30, към доставчика — 50 (връщане), към инвентаризацията — 120, към
 *       производството — 70, към брака — 160, към подизпълнителя — 80 (S1: стоките остават на
 *       дружеството).
 * </ul>
 *
 * <p>Всичко друго е {@code null} — кодът не се определя и изписването остава неосчетоводено с
 * грешка, докато потребителят не посочи ръчния код. Догадка тук би осчетоводила по грешна сметка.
 */
public final class BgStockMovementClassifier {

  /** Ролята на виртуалната локация в StockConfig на дружеството. */
  public enum Role {
    CUSTOMER,
    SUPPLIER,
    INVENTORY,
    PRODUCTION,
    WASTE,
    OUTSOURCING,
    /** Виртуална локация без роля в StockConfig. */
    OTHER
  }

  private BgStockMovementClassifier() {}

  /**
   * @param manualCode {@code StockMove.bgSaftMovementCode}
   * @param reasonCode {@code StockCorrectionReason.bgStockMovementCode} на движението
   * @param direction посоката по S0 ({@link BgStockMovementCodes#directionOf})
   * @param counterpart ролята на насрещната (виртуална) локация — източникът при вход, целта при
   *     изход; {@code null} при трансфер
   */
  public static String classify(
      String manualCode, String reasonCode, Direction direction, Role counterpart) {
    String manual = blankToNull(manualCode);
    if (manual != null) {
      return manual;
    }
    String reason = blankToNull(reasonCode);
    if (reason != null) {
      return reason;
    }
    if (direction == null) {
      return null;
    }
    switch (direction) {
      case TRANSFER:
        return "80";
      case IN:
        return inCode(counterpart);
      case OUT:
        return outCode(counterpart);
      default:
        return null;
    }
  }

  private static String inCode(Role from) {
    if (from == null) {
      return null;
    }
    switch (from) {
      case SUPPLIER:
        return "10";
      case CUSTOMER:
        return "40";
      case INVENTORY:
        return "110";
      case PRODUCTION:
        return "20";
      default:
        return null;
    }
  }

  private static String outCode(Role to) {
    if (to == null) {
      return null;
    }
    switch (to) {
      case CUSTOMER:
        return "30";
      case SUPPLIER:
        return "50";
      case INVENTORY:
        return "120";
      case PRODUCTION:
        return "70";
      case WASTE:
        return "160";
      case OUTSOURCING:
        return "80";
      default:
        return null;
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }
}
